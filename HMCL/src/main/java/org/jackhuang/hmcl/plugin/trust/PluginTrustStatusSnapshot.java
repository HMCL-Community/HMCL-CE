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

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/// Immutable root-role-verified online repository and artifact revocation state.
///
/// @param version monotonically increasing snapshot version
/// @param generatedAt snapshot generation instant
/// @param expires exclusive snapshot freshness expiry
/// @param policyVersion applied online policy version
/// @param repositories verified repository states
/// @param revokedArtifacts exact revoked NPL artifacts
/// @param revokedKeyIds revoked online repository or artifact attestation key IDs
/// @param keyId root-authorized key that verified this snapshot
@NotNullByDefault
public record PluginTrustStatusSnapshot(
        long version,
        Instant generatedAt,
        Instant expires,
        String policyVersion,
        @Unmodifiable List<RepositoryStatus> repositories,
        @Unmodifiable List<RevokedArtifact> revokedArtifacts,
        @Unmodifiable Set<String> revokedKeyIds,
        String keyId
) {
    /// Defensively copies collections and rejects null components.
    public PluginTrustStatusSnapshot {
        if (version <= 0 || version > CanonicalJson.MAX_SAFE_INTEGER) {
            throw new IllegalArgumentException("Trust-status version must be a positive safe integer");
        }
        Objects.requireNonNull(generatedAt, "generatedAt");
        Objects.requireNonNull(expires, "expires");
        Objects.requireNonNull(policyVersion, "policyVersion");
        repositories = List.copyOf(repositories);
        revokedArtifacts = List.copyOf(revokedArtifacts);
        revokedKeyIds = Set.copyOf(revokedKeyIds);
        Objects.requireNonNull(keyId, "keyId");
    }

    /// Returns whether this snapshot can currently grant new certification.
    ///
    /// @param now validation instant
    /// @return whether the snapshot has been generated and has not expired
    public boolean isFreshAt(Instant now) {
        return !now.isBefore(generatedAt) && now.isBefore(expires);
    }

    /// Finds the online state for one immutable repository ID.
    ///
    /// @param repositoryId GitHub repository ID
    /// @return matching state or `null`
    public @Nullable RepositoryStatus findRepository(long repositoryId) {
        return repositories.stream()
                .filter(repository -> repository.repositoryId() == repositoryId)
                .findFirst()
                .orElse(null);
    }

    /// Finds an explicit revocation for one exact artifact identity.
    ///
    /// @param pluginId plugin ID
    /// @param version package version
    /// @param sha256 complete package SHA-256
    /// @return matching revocation or `null`
    public @Nullable RevokedArtifact findRevokedArtifact(String pluginId, String version, String sha256) {
        return revokedArtifacts.stream()
                .filter(revocation -> revocation.matches(pluginId, version, sha256))
                .findFirst()
                .orElse(null);
    }

    /// Returns whether an online attestation signer has been explicitly revoked.
    ///
    /// @param signerKeyId Ed25519 signer key ID
    /// @return whether the key ID is revoked
    public boolean isKeyRevoked(String signerKeyId) {
        return revokedKeyIds.contains(signerKeyId);
    }

    /// Online state for one repository approval record.
    ///
    /// @param repositoryId immutable GitHub repository ID
    /// @param repository normalized GitHub repository identity
    /// @param verificationId audit reference shared with the weekly proof
    /// @param status current `approved`, `suspended`, or `revoked` state
    /// @param lastVerifiedAt most recent completed weekly verification
    /// @param nextVerificationAt scheduled next weekly verification
    /// @param pluginIds plugin IDs currently authorized for the repository
    @NotNullByDefault
    public record RepositoryStatus(
            long repositoryId,
            String repository,
            String verificationId,
            String status,
            Instant lastVerifiedAt,
            Instant nextVerificationAt,
            @Unmodifiable List<String> pluginIds
    ) {
        /// Defensively copies plugin IDs and rejects null components.
        public RepositoryStatus {
            if (repositoryId <= 0 || repositoryId > CanonicalJson.MAX_SAFE_INTEGER) {
                throw new IllegalArgumentException("Repository status ID must be a positive safe integer");
            }
            Objects.requireNonNull(repository, "repository");
            Objects.requireNonNull(verificationId, "verificationId");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(lastVerifiedAt, "lastVerifiedAt");
            Objects.requireNonNull(nextVerificationAt, "nextVerificationAt");
            pluginIds = List.copyOf(pluginIds);
        }
    }

    /// Exact NPL artifact revocation retained even when the latest cached snapshot becomes stale.
    ///
    /// @param sha256 revoked package SHA-256
    /// @param pluginId revoked package plugin ID
    /// @param version revoked package version
    /// @param revokedAt revocation publication instant
    /// @param reasonCode credential-safe machine-readable reason
    @NotNullByDefault
    public record RevokedArtifact(
            String sha256,
            String pluginId,
            String version,
            Instant revokedAt,
            String reasonCode
    ) {
        /// Rejects null revocation components.
        public RevokedArtifact {
            Objects.requireNonNull(sha256, "sha256");
            Objects.requireNonNull(pluginId, "pluginId");
            Objects.requireNonNull(version, "version");
            Objects.requireNonNull(revokedAt, "revokedAt");
            Objects.requireNonNull(reasonCode, "reasonCode");
        }

        /// Returns whether this revocation identifies the supplied exact package bytes.
        ///
        /// @param expectedPluginId plugin ID
        /// @param expectedVersion version
        /// @param expectedSha256 complete package SHA-256
        /// @return whether every artifact identity field matches
        public boolean matches(String expectedPluginId, String expectedVersion, String expectedSha256) {
            return pluginId.equals(expectedPluginId)
                    && version.equals(expectedVersion)
                    && sha256.equalsIgnoreCase(expectedSha256);
        }
    }
}
