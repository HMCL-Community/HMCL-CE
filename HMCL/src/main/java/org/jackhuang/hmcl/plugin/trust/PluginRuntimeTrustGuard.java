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
package org.jackhuang.hmcl.plugin.trust;

import org.jackhuang.hmcl.plugin.PluginArtifactIdentity;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/// Applies authenticated stale-safe revocations only to artifacts carrying an exact signed installation receipt.
@NotNullByDefault
public final class PluginRuntimeTrustGuard {
    /// Raw receipts indexed by plugin ID so an identity-field mutation cannot turn a certified artifact into community.
    private final @Unmodifiable Map<String, PluginCertificationReceipt> receipts;

    /// Embedded-root verifier required whenever at least one receipt exists.
    private final @Nullable PluginTrustVerifier verifier;

    /// Dynamic source of the latest authenticated status, including expired snapshots retained for revocations.
    private final Supplier<@Nullable PluginTrustStatusSnapshot> snapshotSource;

    /// Global integrity failure that blocks loading while a present receipt document cannot be trusted.
    private final @Nullable String unavailableReason;

    /// Creates one immutable runtime guard.
    private PluginRuntimeTrustGuard(
            Map<String, PluginCertificationReceipt> receipts,
            @Nullable PluginTrustVerifier verifier,
            Supplier<@Nullable PluginTrustStatusSnapshot> snapshotSource,
            @Nullable String unavailableReason
    ) {
        this.receipts = Map.copyOf(receipts);
        this.verifier = verifier;
        this.snapshotSource = Objects.requireNonNull(snapshotSource, "snapshotSource");
        this.unavailableReason = unavailableReason;
    }

    /// Creates a guard from explicit receipts, verifier, and authenticated status for deterministic tests.
    ///
    /// @param receipts signed certification receipts
    /// @param snapshot latest authenticated status, or `null`
    /// @param verifier embedded-root verifier
    /// @return immutable runtime guard
    public static PluginRuntimeTrustGuard of(
            Collection<PluginCertificationReceipt> receipts,
            @Nullable PluginTrustStatusSnapshot snapshot,
            PluginTrustVerifier verifier
    ) {
        Map<String, PluginCertificationReceipt> indexed = new LinkedHashMap<>();
        for (PluginCertificationReceipt receipt : receipts) {
            if (indexed.putIfAbsent(receipt.pluginId(), receipt) != null) {
                throw new IllegalArgumentException("Duplicate certification receipt for " + receipt.pluginId());
            }
        }
        return new PluginRuntimeTrustGuard(indexed, Objects.requireNonNull(verifier), () -> snapshot, null);
    }

    /// Creates a guard whose authenticated status source can advance after construction.
    ///
    /// This is used by tests to prove that a refresh completed during one launcher run blocks plugins that have not
    /// yet loaded. Production guards use the persisted signed cache as the dynamic source.
    ///
    /// @param receipts signed certification receipts
    /// @param verifier embedded-root verifier
    /// @param snapshotSource latest authenticated status supplier
    /// @return dynamic runtime guard
    public static PluginRuntimeTrustGuard ofDynamic(
            Collection<PluginCertificationReceipt> receipts,
            PluginTrustVerifier verifier,
            Supplier<@Nullable PluginTrustStatusSnapshot> snapshotSource
    ) {
        Map<String, PluginCertificationReceipt> indexed = new LinkedHashMap<>();
        for (PluginCertificationReceipt receipt : receipts) {
            if (indexed.putIfAbsent(receipt.pluginId(), receipt) != null) {
                throw new IllegalArgumentException("Duplicate certification receipt for " + receipt.pluginId());
            }
        }
        return new PluginRuntimeTrustGuard(
                indexed,
                Objects.requireNonNull(verifier),
                snapshotSource,
                null
        );
    }

    /// Creates a guard that blocks all execution after receipt-state integrity becomes unavailable.
    ///
    /// @param reason credential-safe failure detail
    /// @return fail-closed guard
    public static PluginRuntimeTrustGuard unavailable(String reason) {
        return new PluginRuntimeTrustGuard(Map.of(), null, () -> null, Objects.requireNonNull(reason));
    }

    /// Creates an inactive guard for the community-reviewed certification policy.
    ///
    /// This guard performs no receipt or online-status I/O and never blocks an artifact.
    ///
    /// @return stateless inactive runtime guard
    public static PluginRuntimeTrustGuard inactive() {
        return new PluginRuntimeTrustGuard(Map.of(), null, () -> null, null);
    }

    /// Loads receipts and the latest root-verified status cache from one launcher-local home.
    ///
    /// An absent receipt file is a normal community-only state and does not initialize signing infrastructure.
    /// An absent status cache permits historically certified code to run but cannot erase a previously persisted
    /// explicit revocation.
    ///
    /// @param localHome launcher-local home
    /// @return runtime guard
    /// @throws IOException if a present receipt or status document cannot be authenticated
    public static PluginRuntimeTrustGuard load(Path localHome) throws IOException {
        @Unmodifiable Map<String, PluginCertificationReceipt> receipts =
                new PluginCertificationReceiptStore(localHome).readAll();
        if (receipts.isEmpty()) {
            return new PluginRuntimeTrustGuard(Map.of(), null, () -> null, null);
        }
        PluginTrustVerifier verifier = PluginTrustVerifier.loadDefault();
        Path statusFile = localHome.resolve("plugin-trust").resolve("trust-status.json");
        Supplier<@Nullable PluginTrustStatusSnapshot> snapshotSource = () -> {
            if (!Files.exists(statusFile)) {
                return null;
            }
            try (PluginTrustStatusCache cache = new PluginTrustStatusCache(
                    verifier,
                    statusFile,
                    Clock.systemUTC()
            )) {
                return cache.getLatestSnapshot();
            } catch (IOException exception) {
                throw new IllegalStateException("Plugin trust-status cache authentication failed", exception);
            }
        };
        return new PluginRuntimeTrustGuard(receipts, verifier, snapshotSource, null);
    }

    /// Returns the reason an exact NPL must be blocked before any class loading.
    ///
    /// @param identity exact current package identity
    /// @param actualSize exact current package size
    /// @return blocking detail or `null` when no authenticated revocation applies
    public @Nullable String getBlockReason(PluginArtifactIdentity identity, long actualSize) {
        if (unavailableReason != null) {
            return "Plugin certification state is unavailable: " + unavailableReason;
        }
        @Nullable PluginCertificationReceipt receipt = receipts.get(identity.getPluginId());
        if (receipt == null) {
            return null;
        }
        final PluginVerifiedCertification certification;
        try {
            certification = receipt.verify(Objects.requireNonNull(verifier), identity, actualSize);
        } catch (RuntimeException exception) {
            @Nullable String message = exception.getMessage();
            return "Installed plugin certification receipt failed verification"
                    + (message == null || message.isBlank() ? "" : ": " + message);
        }

        final @Nullable PluginTrustStatusSnapshot current;
        try {
            current = snapshotSource.get();
        } catch (RuntimeException exception) {
            return "Authenticated plugin trust status is unavailable";
        }
        if (current == null) {
            return null;
        }
        @Nullable PluginTrustStatusSnapshot.RevokedArtifact artifact = current.findRevokedArtifact(
                identity.getPluginId(),
                identity.getVersion(),
                identity.getSha256()
        );
        if (artifact != null) {
            return "Certified plugin artifact is revoked: " + artifact.reasonCode();
        }
        if (current.isKeyRevoked(certification.repositorySignerKeyId())
                || current.isKeyRevoked(certification.artifactSignerKeyId())) {
            return "A signer used by the installed plugin certification is revoked";
        }
        @Nullable PluginTrustStatusSnapshot.RepositoryStatus repository =
                current.findRepository(certification.repositoryId());
        if (repository == null) {
            return null;
        }
        if (!repository.repository().equals(certification.repository())) {
            return "Certified repository identity no longer matches its immutable repository ID";
        }
        if (repository.status().equals("suspended") || repository.status().equals("revoked")) {
            return "Certified repository is " + repository.status();
        }
        return null;
    }
}
