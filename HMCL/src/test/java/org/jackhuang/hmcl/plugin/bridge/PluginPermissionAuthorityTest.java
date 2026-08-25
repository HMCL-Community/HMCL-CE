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
import org.jackhuang.hmcl.plugin.PluginPermission;
import org.jackhuang.hmcl.plugin.runtime.PluginExecutionMode;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies artifact-bound capability tokens at every external-runtime authorization boundary.
@NotNullByDefault
public final class PluginPermissionAuthorityTest {
    /// Exact package identity used by the primary test plugin.
    private static final PluginArtifactIdentity PLUGIN_A = artifact("dev.hmclce.test.plugin-a", "1.0.0", 'a');

    /// Exact package identity used by a second dependent sharing the same runtime Host.
    private static final PluginArtifactIdentity PLUGIN_B = artifact("dev.hmclce.test.plugin-b", "1.0.0", 'b');

    /// Initial deterministic authority time.
    private static final Instant NOW = Instant.parse("2026-08-25T00:00:00Z");

    /// Keeps capability identifiers opaque and unavailable to ordinary plugin constructors.
    @Test
    public void exposeNoPublicCapabilityTokenConstructor() {
        assertFalse(Set.of(PluginCapabilityToken.class.getDeclaredConstructors()).stream()
                .anyMatch(constructor -> Modifier.isPublic(constructor.getModifiers())));
    }

    /// Binds authorization to plugin ID, exact bytes, version, execution mode, grant, and callback domain.
    @Test
    public void bindEveryAuthorizationDimension() {
        MutableClock clock = new MutableClock(NOW);
        PluginPermissionAuthority authority = authority(clock);
        PluginCapabilityToken token = authority.issue(
                PLUGIN_A,
                PluginExecutionMode.EMBEDDED,
                Set.of(PluginPermission.LAUNCHER_CORE, PluginPermission.FILESYSTEM),
                "bridge.core",
                NOW.plusSeconds(30)
        );

        assertDoesNotThrow(() -> authority.requirePermission(
                token,
                PLUGIN_A.getPluginId(),
                PLUGIN_A,
                PluginExecutionMode.EMBEDDED,
                PluginPermission.LAUNCHER_CORE,
                "bridge.core"
        ));
        assertThrows(SecurityException.class, () -> authority.requirePermission(
                token, PLUGIN_B.getPluginId(), PLUGIN_B, PluginExecutionMode.EMBEDDED,
                PluginPermission.LAUNCHER_CORE, "bridge.core"));
        assertThrows(SecurityException.class, () -> authority.requirePermission(
                token, PLUGIN_A.getPluginId(), artifact(PLUGIN_A.getPluginId(), "2.0.0", 'a'),
                PluginExecutionMode.EMBEDDED, PluginPermission.LAUNCHER_CORE, "bridge.core"));
        assertThrows(SecurityException.class, () -> authority.requirePermission(
                token, PLUGIN_A.getPluginId(), artifact(PLUGIN_A.getPluginId(), "1.0.0", 'c'),
                PluginExecutionMode.EMBEDDED, PluginPermission.LAUNCHER_CORE, "bridge.core"));
        assertThrows(SecurityException.class, () -> authority.requirePermission(
                token, PLUGIN_A.getPluginId(), PLUGIN_A, PluginExecutionMode.ISOLATED,
                PluginPermission.LAUNCHER_CORE, "bridge.core"));
        assertThrows(SecurityException.class, () -> authority.requirePermission(
                token, PLUGIN_A.getPluginId(), PLUGIN_A, PluginExecutionMode.EMBEDDED,
                PluginPermission.NETWORK, "bridge.core"));
        assertThrows(SecurityException.class, () -> authority.requirePermission(
                token, PLUGIN_A.getPluginId(), PLUGIN_A, PluginExecutionMode.EMBEDDED,
                PluginPermission.LAUNCHER_CORE, "bridge.ui"));
    }

    /// Prevents one shared Host from presenting dependent A's authority for dependent B.
    @Test
    public void rejectCrossPluginTokenTheftThroughSharedHost() {
        PluginPermissionAuthority authority = authority(new MutableClock(NOW));
        PluginCapabilityToken pluginAToken = issueCoreToken(authority, PLUGIN_A);
        PluginCapabilityToken pluginBToken = issueCoreToken(authority, PLUGIN_B);

        assertNotEquals(pluginAToken, pluginBToken);
        assertThrows(SecurityException.class, () -> authority.requirePermission(
                pluginAToken, PLUGIN_B.getPluginId(), PLUGIN_B, PluginExecutionMode.EMBEDDED,
                PluginPermission.LAUNCHER_CORE, "bridge.core"));
        assertDoesNotThrow(() -> authority.requirePermission(
                pluginBToken, PLUGIN_B.getPluginId(), PLUGIN_B, PluginExecutionMode.EMBEDDED,
                PluginPermission.LAUNCHER_CORE, "bridge.core"));
    }

    /// Narrows callbacks to child domains without widening scope or extending token lifetime.
    @Test
    public void narrowCallbackDomainAndLifetime() {
        MutableClock clock = new MutableClock(NOW);
        PluginPermissionAuthority authority = authority(clock);
        PluginCapabilityToken parent = issueCoreToken(authority, PLUGIN_A);
        PluginCapabilityToken child = authority.narrow(
                parent,
                PLUGIN_A.getPluginId(),
                PLUGIN_A,
                PluginExecutionMode.EMBEDDED,
                "bridge.core.profile",
                NOW.plusSeconds(15)
        );

        assertDoesNotThrow(() -> authority.requirePermission(
                child, PLUGIN_A.getPluginId(), PLUGIN_A, PluginExecutionMode.EMBEDDED,
                PluginPermission.LAUNCHER_CORE, "bridge.core.profile"));
        assertThrows(SecurityException.class, () -> authority.requirePermission(
                child, PLUGIN_A.getPluginId(), PLUGIN_A, PluginExecutionMode.EMBEDDED,
                PluginPermission.LAUNCHER_CORE, "bridge.core"));
        assertThrows(IllegalArgumentException.class, () -> authority.narrow(
                parent, PLUGIN_A.getPluginId(), PLUGIN_A, PluginExecutionMode.EMBEDDED,
                "bridge.ui", NOW.plusSeconds(15)));
        assertThrows(IllegalArgumentException.class, () -> authority.narrow(
                parent, PLUGIN_A.getPluginId(), PLUGIN_A, PluginExecutionMode.EMBEDDED,
                "bridge.core.profile", NOW.plusSeconds(61)));
    }

    /// Rejects expired and revoked authorities, including children of a revoked parent scope.
    @Test
    public void expireAndRevokeCapabilityFamilies() {
        MutableClock clock = new MutableClock(NOW);
        PluginPermissionAuthority authority = authority(clock);
        PluginCapabilityToken parent = issueCoreToken(authority, PLUGIN_A);
        PluginCapabilityToken child = authority.narrow(
                parent, PLUGIN_A.getPluginId(), PLUGIN_A, PluginExecutionMode.EMBEDDED,
                "bridge.core.profile", NOW.plusSeconds(20));

        authority.revoke(parent);

        assertThrows(SecurityException.class, () -> authority.requirePermission(
                parent, PLUGIN_A.getPluginId(), PLUGIN_A, PluginExecutionMode.EMBEDDED,
                PluginPermission.LAUNCHER_CORE, "bridge.core"));
        assertThrows(SecurityException.class, () -> authority.requirePermission(
                child, PLUGIN_A.getPluginId(), PLUGIN_A, PluginExecutionMode.EMBEDDED,
                PluginPermission.LAUNCHER_CORE, "bridge.core.profile"));

        PluginCapabilityToken expiring = issueCoreToken(authority, PLUGIN_A);
        clock.setInstant(NOW.plusSeconds(61));
        assertThrows(SecurityException.class, () -> authority.requirePermission(
                expiring, PLUGIN_A.getPluginId(), PLUGIN_A, PluginExecutionMode.EMBEDDED,
                PluginPermission.LAUNCHER_CORE, "bridge.core"));
    }

    /// Allows raw JVM authority only for payloads executing in the launcher JVM.
    @Test
    public void restrictRawJvmAuthorityToEmbeddedExecution() {
        PluginPermissionAuthority authority = authority(new MutableClock(NOW));

        assertThrows(IllegalArgumentException.class, () -> authority.issue(
                PLUGIN_A,
                PluginExecutionMode.ISOLATED,
                Set.of(PluginPermission.JVM_RAW),
                "bridge.raw-jvm",
                NOW.plusSeconds(30)
        ));
    }

    /// Produces a Bridge verifier that validates a real token against the handle's expected owner.
    @Test
    public void authorizeBridgeHandleOwnersWithCapabilityTokens() {
        PluginPermissionAuthority authority = authority(new MutableClock(NOW));
        Map<String, PluginArtifactIdentity> identities = Map.of(
                PLUGIN_A.getPluginId(), PLUGIN_A,
                PLUGIN_B.getPluginId(), PLUGIN_B
        );
        BridgeHandleRegistry<PluginCapabilityToken> registry = new BridgeHandleRegistry<>(
                authority.ownerVerifier(
                        identities::get,
                        ignored -> PluginExecutionMode.EMBEDDED,
                        PluginPermission.LAUNCHER_CORE,
                        "bridge.core"
                )
        );
        BridgeHandle pluginAHandle = registry.register(
                PLUGIN_A.getPluginId(), "launcher.profile", new Object());
        BridgeHandle pluginBHandle = registry.register(
                PLUGIN_B.getPluginId(), "launcher.profile", new Object());

        assertDoesNotThrow(() -> registry.resolve(
                issueCoreToken(authority, PLUGIN_A), pluginAHandle, "launcher.profile"));
        assertDoesNotThrow(() -> registry.resolve(
                issueCoreToken(authority, PLUGIN_B), pluginBHandle, "launcher.profile"));
        assertThrows(BridgeError.class,
                () -> registry.resolve(
                        issueCoreToken(authority, PLUGIN_A), pluginBHandle, "launcher.profile"));
    }

    /// Creates a deterministic authority while retaining production-strength token generation.
    ///
    /// @param clock mutable verification clock
    /// @return empty permission authority
    private static PluginPermissionAuthority authority(Clock clock) {
        return new PluginPermissionAuthority(clock, new SecureRandom());
    }

    /// Issues one minute of launcher-core authority for one exact artifact.
    ///
    /// @param authority permission authority
    /// @param identity exact plugin artifact
    /// @return opaque capability token
    private static PluginCapabilityToken issueCoreToken(
            PluginPermissionAuthority authority,
            PluginArtifactIdentity identity
    ) {
        return authority.issue(
                identity,
                PluginExecutionMode.EMBEDDED,
                Set.of(PluginPermission.LAUNCHER_CORE),
                "bridge.core",
                NOW.plusSeconds(60)
        );
    }

    /// Creates one exact artifact identity with a repeated test digest character.
    ///
    /// @param pluginId canonical plugin ID
    /// @param version package version
    /// @param digestCharacter repeated lower-case hexadecimal digest character
    /// @return exact test artifact identity
    private static PluginArtifactIdentity artifact(String pluginId, String version, char digestCharacter) {
        return new PluginArtifactIdentity(pluginId, version, String.valueOf(digestCharacter).repeat(64));
    }

    /// Mutable UTC clock used to cross expiry boundaries without sleeping.
    @NotNullByDefault
    private static final class MutableClock extends Clock {
        /// Current clock instant.
        private Instant instant;

        /// Creates a UTC clock at one instant.
        ///
        /// @param instant initial instant
        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        /// Replaces the current instant.
        ///
        /// @param instant new instant
        private void setInstant(Instant instant) {
            this.instant = instant;
        }

        /// Returns UTC as the fixed clock zone.
        ///
        /// @return UTC
        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        /// Rejects alternate zones because test authorization timestamps are UTC.
        ///
        /// @param zone requested zone
        /// @return this clock when UTC
        @Override
        public Clock withZone(ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) {
                throw new IllegalArgumentException("Only UTC is supported");
            }
            return this;
        }

        /// Returns the mutable test instant.
        ///
        /// @return current instant
        @Override
        public Instant instant() {
            return instant;
        }
    }
}
