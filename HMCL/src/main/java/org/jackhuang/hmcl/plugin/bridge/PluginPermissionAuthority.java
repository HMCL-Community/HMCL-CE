/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2026 huangyuhui <huanghongxun2008@126.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.jackhuang.hmcl.plugin.bridge;

import org.jackhuang.hmcl.plugin.PluginArtifactIdentity;
import org.jackhuang.hmcl.plugin.PluginManifest;
import org.jackhuang.hmcl.plugin.PluginPermission;
import org.jackhuang.hmcl.plugin.runtime.PluginExecutionMode;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/// Issues and verifies short-lived capabilities scoped to one exact plugin artifact and callback domain.
@NotNullByDefault
public final class PluginPermissionAuthority {
    /// Canonical hierarchical callback-domain grammar.
    private static final Pattern CALLBACK_DOMAIN_PATTERN = Pattern.compile(
            "[a-z][a-z0-9-]*(?:\\.[a-z][a-z0-9-]*)*"
    );

    /// Clock used for issuance and expiry verification.
    private final Clock clock;

    /// Cryptographically strong identifier generator.
    private final SecureRandom secureRandom;

    /// Active authorization records indexed by opaque random token.
    private final Map<PluginCapabilityToken, Grant> grants = new HashMap<>();

    /// Active token identifiers indexed by exclusive expiry for incremental time-based cleanup.
    private final NavigableMap<Instant, Set<PluginCapabilityToken>> grantsByExpiry = new TreeMap<>();

    /// Creates a production authority using UTC wall-clock time and the platform secure random source.
    public PluginPermissionAuthority() {
        this(Clock.systemUTC(), new SecureRandom());
    }

    /// Creates an authority with explicit time and entropy sources.
    ///
    /// Package visibility keeps deterministic construction confined to Bridge tests.
    ///
    /// @param clock authorization clock
    /// @param secureRandom token identifier generator
    PluginPermissionAuthority(Clock clock, SecureRandom secureRandom) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.secureRandom = Objects.requireNonNull(secureRandom, "secureRandom");
    }

    /// Opens one independently revocable capability family for a runtime payload lifecycle.
    ///
    /// @param artifactIdentity exact package identity
    /// @param executionMode current payload execution mode
    /// @param grantedPermissionProvider dynamic source of current effective artifact grants
    /// @param callbackDomain callback domain assigned to every root token
    /// @param lifetime positive lifetime assigned to every token
    /// @return initially active capability session
    public PluginCapabilitySession openSession(
            PluginArtifactIdentity artifactIdentity,
            PluginExecutionMode executionMode,
            Supplier<@Unmodifiable Set<PluginPermission>> grantedPermissionProvider,
            String callbackDomain,
            Duration lifetime
    ) {
        return new PluginCapabilitySession(
                this,
                artifactIdentity,
                executionMode,
                grantedPermissionProvider,
                requireCallbackDomain(callbackDomain),
                requirePositiveLifetime(lifetime)
        );
    }

    /// Issues one token expiring after a relative lifetime.
    ///
    /// @param artifactIdentity exact package identity
    /// @param executionMode current payload execution mode
    /// @param grantedPermissions current effective artifact grants
    /// @param callbackDomain current callback domain
    /// @param lifetime positive token lifetime
    /// @return opaque plugin-scoped capability token
    public PluginCapabilityToken issue(
            PluginArtifactIdentity artifactIdentity,
            PluginExecutionMode executionMode,
            Set<PluginPermission> grantedPermissions,
            String callbackDomain,
            Duration lifetime
    ) {
        Duration validLifetime = requirePositiveLifetime(lifetime);
        return issue(artifactIdentity, executionMode, grantedPermissions,
                callbackDomain, clock.instant().plus(validLifetime));
    }

    /// Issues one token bound to exact package bytes, mode, grants, callback domain, and expiry.
    ///
    /// @param artifactIdentity exact package identity
    /// @param executionMode current payload execution mode
    /// @param grantedPermissions current effective artifact grants
    /// @param callbackDomain current callback domain
    /// @param expiresAt exclusive token expiry
    /// @return opaque plugin-scoped capability token
    public synchronized PluginCapabilityToken issue(
            PluginArtifactIdentity artifactIdentity,
            PluginExecutionMode executionMode,
            Set<PluginPermission> grantedPermissions,
            String callbackDomain,
            Instant expiresAt
    ) {
        Instant now = clock.instant();
        cleanupExpiredGrants(now);
        PluginArtifactIdentity identity = Objects.requireNonNull(artifactIdentity, "artifactIdentity");
        PluginExecutionMode mode = Objects.requireNonNull(executionMode, "executionMode");
        @Unmodifiable Set<PluginPermission> permissions = immutablePermissions(grantedPermissions);
        String domain = requireCallbackDomain(callbackDomain);
        Instant expiry = Objects.requireNonNull(expiresAt, "expiresAt");
        if (!expiry.isAfter(now)) {
            throw new IllegalArgumentException("Capability token expiry must be in the future");
        }
        if (mode != PluginExecutionMode.EMBEDDED && permissions.contains(PluginPermission.JVM_RAW)) {
            throw new IllegalArgumentException("jvm-raw capability requires embedded execution");
        }

        return issueGrant(identity, mode, permissions, domain, expiry, null, 0, null);
    }

    /// Issues one token owned by an exact capability session generation.
    ///
    /// The caller must hold the session monitor before entering this synchronized authority method.
    ///
    /// @param session owning capability session
    /// @param generation owning session generation
    /// @param artifactIdentity exact package identity
    /// @param executionMode current payload execution mode
    /// @param grantedPermissions current effective artifact grants
    /// @param callbackDomain current callback domain
    /// @param lifetime positive token lifetime
    /// @return opaque session-scoped capability token
    synchronized PluginCapabilityToken issueForSession(
            PluginCapabilitySession session,
            long generation,
            PluginArtifactIdentity artifactIdentity,
            PluginExecutionMode executionMode,
            Set<PluginPermission> grantedPermissions,
            String callbackDomain,
            Duration lifetime
    ) {
        Instant now = clock.instant();
        cleanupExpiredGrants(now);
        PluginArtifactIdentity identity = Objects.requireNonNull(artifactIdentity, "artifactIdentity");
        PluginExecutionMode mode = Objects.requireNonNull(executionMode, "executionMode");
        @Unmodifiable Set<PluginPermission> permissions = immutablePermissions(grantedPermissions);
        String domain = requireCallbackDomain(callbackDomain);
        Instant expiry = now.plus(requirePositiveLifetime(lifetime));
        if (mode != PluginExecutionMode.EMBEDDED && permissions.contains(PluginPermission.JVM_RAW)) {
            throw new IllegalArgumentException("jvm-raw capability requires embedded execution");
        }
        return issueGrant(
                identity,
                mode,
                permissions,
                domain,
                expiry,
                Objects.requireNonNull(session, "session"),
                generation,
                null
        );
    }

    /// Narrows an existing token to one equal or descendant callback domain and a non-extended expiry.
    ///
    /// @param parent parent capability token
    /// @param expectedPluginId expected dependent plugin ID
    /// @param expectedArtifactIdentity exact currently loaded package identity
    /// @param expectedExecutionMode current payload execution mode
    /// @param callbackDomain equal or descendant callback domain
    /// @param expiresAt expiry no later than the parent expiry
    /// @return newly generated child token
    public synchronized PluginCapabilityToken narrow(
            PluginCapabilityToken parent,
            String expectedPluginId,
            PluginArtifactIdentity expectedArtifactIdentity,
            PluginExecutionMode expectedExecutionMode,
            String callbackDomain,
            Instant expiresAt
    ) {
        Grant parentGrant = requireGrant(
                parent, expectedPluginId, expectedArtifactIdentity, expectedExecutionMode);
        String narrowedDomain = requireCallbackDomain(callbackDomain);
        if (!narrowedDomain.equals(parentGrant.callbackDomain)
                && !narrowedDomain.startsWith(parentGrant.callbackDomain + ".")) {
            throw new IllegalArgumentException("Callback domain cannot be widened");
        }
        Instant narrowedExpiry = Objects.requireNonNull(expiresAt, "expiresAt");
        if (!narrowedExpiry.isAfter(clock.instant()) || narrowedExpiry.isAfter(parentGrant.expiresAt)) {
            throw new IllegalArgumentException("Narrowed token cannot extend its parent lifetime");
        }

        return issueGrant(
                parentGrant.artifactIdentity,
                parentGrant.executionMode,
                parentGrant.grantedPermissions,
                narrowedDomain,
                narrowedExpiry,
                parentGrant.session,
                parentGrant.generation,
                parent
        );
    }

    /// Requires one permission under every expected authorization dimension.
    ///
    /// @param token presented opaque token
    /// @param expectedPluginId expected dependent plugin ID
    /// @param expectedArtifactIdentity exact currently loaded package identity
    /// @param expectedExecutionMode current payload execution mode
    /// @param permission operation-required permission
    /// @param callbackDomain operation callback domain
    /// @throws SecurityException if any binding, grant, lifetime, or revocation check fails
    public synchronized void requirePermission(
            PluginCapabilityToken token,
            String expectedPluginId,
            PluginArtifactIdentity expectedArtifactIdentity,
            PluginExecutionMode expectedExecutionMode,
            PluginPermission permission,
            String callbackDomain
    ) {
        Grant grant = requireGrant(token, expectedPluginId, expectedArtifactIdentity, expectedExecutionMode);
        if (!grant.grantedPermissions.contains(Objects.requireNonNull(permission, "permission"))
                || !grant.callbackDomain.equals(requireCallbackDomain(callbackDomain))) {
            throw denied();
        }
    }

    /// Creates a Bridge handle owner verifier bound to exact operation requirements.
    ///
    /// @param expectedArtifactIdentity exact currently loaded package identity
    /// @param expectedExecutionMode current payload execution mode
    /// @param permission handle operation-required permission
    /// @param callbackDomain handle operation callback domain
    /// @return verifier that also compares the token owner with each handle owner
    public BridgeHandleRegistry.OwnerVerifier<PluginCapabilityToken> ownerVerifier(
            PluginArtifactIdentity expectedArtifactIdentity,
            PluginExecutionMode expectedExecutionMode,
            PluginPermission permission,
            String callbackDomain
    ) {
        Objects.requireNonNull(expectedArtifactIdentity, "expectedArtifactIdentity");
        Objects.requireNonNull(expectedExecutionMode, "expectedExecutionMode");
        Objects.requireNonNull(permission, "permission");
        requireCallbackDomain(callbackDomain);
        return ownerVerifier(
                ownerPluginId -> expectedArtifactIdentity.getPluginId().equals(ownerPluginId)
                        ? expectedArtifactIdentity
                        : null,
                ignored -> expectedExecutionMode,
                permission,
                callbackDomain
        );
    }

    /// Creates a shared Bridge verifier that resolves current artifact and mode bindings for each handle owner.
    ///
    /// @param artifactIdentityResolver exact loaded artifact resolver by expected owner plugin ID
    /// @param executionModeResolver current execution-mode resolver by expected owner plugin ID
    /// @param permission handle operation-required permission
    /// @param callbackDomain handle operation callback domain
    /// @return verifier suitable for a registry shared by multiple runtime dependents
    public BridgeHandleRegistry.OwnerVerifier<PluginCapabilityToken> ownerVerifier(
            Function<String, @Nullable PluginArtifactIdentity> artifactIdentityResolver,
            Function<String, @Nullable PluginExecutionMode> executionModeResolver,
            PluginPermission permission,
            String callbackDomain
    ) {
        Objects.requireNonNull(artifactIdentityResolver, "artifactIdentityResolver");
        Objects.requireNonNull(executionModeResolver, "executionModeResolver");
        Objects.requireNonNull(permission, "permission");
        requireCallbackDomain(callbackDomain);
        return (token, expectedOwnerPluginId) -> {
            @Nullable PluginArtifactIdentity expectedArtifactIdentity =
                    artifactIdentityResolver.apply(expectedOwnerPluginId);
            @Nullable PluginExecutionMode expectedExecutionMode =
                    executionModeResolver.apply(expectedOwnerPluginId);
            if (expectedArtifactIdentity == null || expectedExecutionMode == null) {
                throw denied();
            }
            requirePermission(
                    token,
                    expectedOwnerPluginId,
                    expectedArtifactIdentity,
                    expectedExecutionMode,
                    permission,
                    callbackDomain
            );
            return expectedOwnerPluginId;
        };
    }

    /// Revokes one token and every transitively narrowed child token.
    ///
    /// @param token token family root to revoke
    public synchronized void revoke(PluginCapabilityToken token) {
        Objects.requireNonNull(token, "token");
        cleanupExpiredGrants(clock.instant());
        Set<PluginCapabilityToken> revokedFamily = new HashSet<>();
        grants.forEach((candidate, grant) -> {
            if (candidate.equals(token) || descendsFrom(grant, token)) {
                revokedFamily.add(candidate);
            }
        });
        removeGrants(revokedFamily);
    }

    /// Revokes every token issued for one exact package artifact.
    ///
    /// @param artifactIdentity artifact whose authority must end
    public synchronized void revokeArtifact(PluginArtifactIdentity artifactIdentity) {
        Objects.requireNonNull(artifactIdentity, "artifactIdentity");
        cleanupExpiredGrants(clock.instant());
        Set<PluginCapabilityToken> revokedArtifact = new HashSet<>();
        grants.forEach((token, grant) -> {
            if (grant.artifactIdentity.equals(artifactIdentity)) {
                revokedArtifact.add(token);
            }
        });
        removeGrants(revokedArtifact);
    }

    /// Revokes every root and narrowed token owned by one exact session generation.
    ///
    /// @param session owning capability session
    /// @param generation owning session generation
    synchronized void revokeFamily(PluginCapabilitySession session, long generation) {
        Objects.requireNonNull(session, "session");
        cleanupExpiredGrants(clock.instant());
        Set<PluginCapabilityToken> revokedFamily = new HashSet<>();
        grants.forEach((token, grant) -> {
            if (grant.session == session && grant.generation == generation) {
                revokedFamily.add(token);
            }
        });
        removeGrants(revokedFamily);
    }

    /// Returns the number of grant records currently retained by this authority without triggering cleanup.
    ///
    /// Package visibility confines this lifecycle-retention diagnostic to Bridge tests.
    ///
    /// @return retained grant record count
    synchronized int activeGrantCount() {
        return grants.size();
    }

    /// Inserts one validated grant using a fresh opaque random identifier.
    ///
    /// @param artifactIdentity exact package identity
    /// @param executionMode approved execution boundary
    /// @param grantedPermissions immutable effective grants
    /// @param callbackDomain exact callback domain
    /// @param expiresAt exclusive expiry
    /// @param session optional owning lifecycle session
    /// @param generation owning session generation, or zero for standalone tokens
    /// @param parent optional narrowed-token parent
    /// @return newly generated opaque token
    private PluginCapabilityToken issueGrant(
            PluginArtifactIdentity artifactIdentity,
            PluginExecutionMode executionMode,
            @Unmodifiable Set<PluginPermission> grantedPermissions,
            String callbackDomain,
            Instant expiresAt,
            @Nullable PluginCapabilitySession session,
            long generation,
            @Nullable PluginCapabilityToken parent
    ) {
        PluginCapabilityToken token;
        do {
            byte[] identifier = new byte[PluginCapabilityToken.IDENTIFIER_BYTES];
            secureRandom.nextBytes(identifier);
            token = new PluginCapabilityToken(identifier);
        } while (grants.containsKey(token));
        grants.put(token, new Grant(
                artifactIdentity,
                executionMode,
                grantedPermissions,
                callbackDomain,
                expiresAt,
                session,
                generation,
                parent
        ));
        grantsByExpiry.computeIfAbsent(expiresAt, ignored -> new HashSet<>()).add(token);
        return token;
    }

    /// Removes every grant whose exclusive expiry is at or before the supplied authority time.
    ///
    /// Each expiry bucket is processed once, keeping ordinary issuance independent of the active grant count.
    ///
    /// @param now current authority time
    private void cleanupExpiredGrants(Instant now) {
        while (!grantsByExpiry.isEmpty() && !grantsByExpiry.firstKey().isAfter(now)) {
            Map.Entry<Instant, Set<PluginCapabilityToken>> expired = grantsByExpiry.pollFirstEntry();
            for (PluginCapabilityToken token : expired.getValue()) {
                grants.remove(token);
            }
        }
    }

    /// Removes exact grant records and their expiry-index entries after the full removal set is collected.
    ///
    /// @param tokens complete token set to remove
    private void removeGrants(Set<PluginCapabilityToken> tokens) {
        for (PluginCapabilityToken token : tokens) {
            @Nullable Grant removed = grants.remove(token);
            if (removed == null) {
                continue;
            }
            @Nullable Set<PluginCapabilityToken> expiryBucket = grantsByExpiry.get(removed.expiresAt);
            if (expiryBucket != null) {
                expiryBucket.remove(token);
                if (expiryBucket.isEmpty()) {
                    grantsByExpiry.remove(removed.expiresAt);
                }
            }
        }
    }

    /// Returns a valid token record after plugin, artifact, mode, expiry, and revocation checks.
    ///
    /// @param token presented token
    /// @param expectedPluginId expected plugin ID
    /// @param expectedArtifactIdentity exact current artifact
    /// @param expectedExecutionMode current execution mode
    /// @return validated private grant record
    private Grant requireGrant(
            PluginCapabilityToken token,
            String expectedPluginId,
            PluginArtifactIdentity expectedArtifactIdentity,
            PluginExecutionMode expectedExecutionMode
    ) {
        Instant now = clock.instant();
        cleanupExpiredGrants(now);
        if (!PluginManifest.isCanonicalExecutableId(expectedPluginId)) {
            throw denied();
        }
        Grant grant = grants.get(Objects.requireNonNull(token, "token"));
        if (grant == null
                || !now.isBefore(grant.expiresAt)
                || !grant.artifactIdentity.getPluginId().equals(expectedPluginId)
                || !grant.artifactIdentity.equals(Objects.requireNonNull(
                        expectedArtifactIdentity, "expectedArtifactIdentity"))
                || grant.executionMode != Objects.requireNonNull(expectedExecutionMode, "expectedExecutionMode")) {
            throw denied();
        }
        return grant;
    }

    /// Returns whether one grant descends from the supplied parent token.
    ///
    /// @param grant candidate descendant grant
    /// @param ancestor candidate ancestor token
    /// @return whether the parent chain includes the ancestor
    private boolean descendsFrom(Grant grant, PluginCapabilityToken ancestor) {
        @Nullable PluginCapabilityToken parent = grant.parent;
        while (parent != null) {
            if (parent.equals(ancestor)) {
                return true;
            }
            @Nullable Grant parentGrant = grants.get(parent);
            parent = parentGrant == null ? null : parentGrant.parent;
        }
        return false;
    }

    /// Validates one canonical callback domain.
    ///
    /// @param callbackDomain candidate callback domain
    /// @return unchanged canonical domain
    private static String requireCallbackDomain(String callbackDomain) {
        if (!CALLBACK_DOMAIN_PATTERN.matcher(callbackDomain).matches()) {
            throw new IllegalArgumentException("Invalid callback domain");
        }
        return callbackDomain;
    }

    /// Validates one positive capability-token lifetime.
    ///
    /// @param lifetime candidate relative lifetime
    /// @return unchanged positive lifetime
    private static Duration requirePositiveLifetime(Duration lifetime) {
        Objects.requireNonNull(lifetime, "lifetime");
        if (lifetime.isZero() || lifetime.isNegative()) {
            throw new IllegalArgumentException("Capability token lifetime must be positive");
        }
        return lifetime;
    }

    /// Copies a caller grant set into immutable stable enum order.
    ///
    /// @param permissions caller-supplied effective grants
    /// @return immutable permission set
    private static @Unmodifiable Set<PluginPermission> immutablePermissions(Set<PluginPermission> permissions) {
        Objects.requireNonNull(permissions, "permissions");
        EnumSet<PluginPermission> copy = EnumSet.noneOf(PluginPermission.class);
        for (@Nullable PluginPermission permission : permissions) {
            if (permission == null) {
                throw new IllegalArgumentException("Capability grants cannot contain null");
            }
            copy.add(permission);
        }
        return copy.isEmpty() ? Set.of() : Collections.unmodifiableSet(copy);
    }

    /// Creates a uniform authorization failure without disclosing which binding failed.
    ///
    /// @return generic permission-denied exception
    private static SecurityException denied() {
        return new SecurityException("Plugin capability denied");
    }

    /// Private authorization record for one active opaque token.
    @NotNullByDefault
    private static final class Grant {
        /// Exact approved package identity.
        private final PluginArtifactIdentity artifactIdentity;

        /// Approved execution boundary.
        private final PluginExecutionMode executionMode;

        /// Immutable effective grants at issuance time.
        private final @Unmodifiable Set<PluginPermission> grantedPermissions;

        /// Exact callback domain for direct use of this token.
        private final String callbackDomain;

        /// Exclusive token expiry.
        private final Instant expiresAt;

        /// Optional lifecycle session owning this grant family.
        private final @Nullable PluginCapabilitySession session;

        /// Owning session generation, or zero for standalone grants.
        private final long generation;

        /// Optional parent token when this scope was narrowed.
        private final @Nullable PluginCapabilityToken parent;

        /// Creates one private authorization record.
        ///
        /// @param artifactIdentity exact package identity
        /// @param executionMode approved execution boundary
        /// @param grantedPermissions immutable effective grants
        /// @param callbackDomain exact callback domain
        /// @param expiresAt exclusive expiry
        /// @param session owning lifecycle session or `null` for a standalone token
        /// @param generation owning session generation or zero for a standalone token
        /// @param parent parent token or `null` for a root token
        private Grant(
                PluginArtifactIdentity artifactIdentity,
                PluginExecutionMode executionMode,
                @Unmodifiable Set<PluginPermission> grantedPermissions,
                String callbackDomain,
                Instant expiresAt,
                @Nullable PluginCapabilitySession session,
                long generation,
                @Nullable PluginCapabilityToken parent
        ) {
            this.artifactIdentity = artifactIdentity;
            this.executionMode = executionMode;
            this.grantedPermissions = grantedPermissions;
            this.callbackDomain = callbackDomain;
            this.expiresAt = expiresAt;
            this.session = session;
            this.generation = generation;
            this.parent = parent;
        }
    }
}
