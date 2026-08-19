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
import org.jetbrains.annotations.Unmodifiable;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/// Immutable, role-verified weekly approval for one exact GitHub repository identity.
///
/// @param repository normalized GitHub repository identity
/// @param repositoryId immutable GitHub repository numeric ID
/// @param defaultBranch verified default branch
/// @param topics verified repository topics
/// @param pluginIds plugin IDs authorized by this repository
/// @param status approval state recorded by the repository verifier
/// @param checkedAt verification instant
/// @param validUntil exclusive proof expiry
/// @param policyVersion applied approval policy
/// @param sourceCommit source commit inspected by the verifier
/// @param verificationId stable audit reference shared with artifact proofs and online status
/// @param keyId root-authorized signing key that verified this proof
@NotNullByDefault
public record PluginRepositoryAttestation(
        String repository,
        long repositoryId,
        String defaultBranch,
        @Unmodifiable List<String> topics,
        @Unmodifiable List<String> pluginIds,
        String status,
        Instant checkedAt,
        Instant validUntil,
        String policyVersion,
        String sourceCommit,
        String verificationId,
        String keyId
) {
    /// Defensively copies collection values and rejects null components.
    public PluginRepositoryAttestation {
        Objects.requireNonNull(repository, "repository");
        if (repositoryId <= 0 || repositoryId > CanonicalJson.MAX_SAFE_INTEGER) {
            throw new IllegalArgumentException("Repository ID must be a positive safe integer");
        }
        Objects.requireNonNull(defaultBranch, "defaultBranch");
        topics = List.copyOf(topics);
        pluginIds = List.copyOf(pluginIds);
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(checkedAt, "checkedAt");
        Objects.requireNonNull(validUntil, "validUntil");
        Objects.requireNonNull(policyVersion, "policyVersion");
        Objects.requireNonNull(sourceCommit, "sourceCommit");
        Objects.requireNonNull(verificationId, "verificationId");
        Objects.requireNonNull(keyId, "keyId");
    }

    /// Returns whether this repository proof remains approved and unexpired at the supplied instant.
    ///
    /// @param now validation instant
    /// @return whether artifact certification may use this proof
    public boolean isApprovedAt(Instant now) {
        return "approved".equals(status) && !now.isBefore(checkedAt) && now.isBefore(validUntil);
    }
}
