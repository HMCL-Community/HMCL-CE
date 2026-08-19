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

import java.util.Objects;

/// Immutable values derived by re-verifying both signed certification envelopes for one installed NPL.
///
/// @param pluginId signed plugin ID
/// @param version signed package version
/// @param sha256 signed complete package SHA-256
/// @param size signed complete package size
/// @param repositoryId signed immutable GitHub repository ID
/// @param repository signed normalized GitHub repository identity
/// @param repositorySignerKeyId root-authorized repository-attestor key ID
/// @param artifactSignerKeyId root-authorized artifact-attestor key ID
/// @param repositoryVerificationId shared historical repository verification ID
@NotNullByDefault
public record PluginVerifiedCertification(
        String pluginId,
        String version,
        String sha256,
        long size,
        long repositoryId,
        String repository,
        String repositorySignerKeyId,
        String artifactSignerKeyId,
        String repositoryVerificationId
) {
    /// Rejects null components and non-positive numeric identities.
    public PluginVerifiedCertification {
        Objects.requireNonNull(pluginId, "pluginId");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(sha256, "sha256");
        Objects.requireNonNull(repository, "repository");
        Objects.requireNonNull(repositorySignerKeyId, "repositorySignerKeyId");
        Objects.requireNonNull(artifactSignerKeyId, "artifactSignerKeyId");
        Objects.requireNonNull(repositoryVerificationId, "repositoryVerificationId");
        if (size <= 0 || size > CanonicalJson.MAX_SAFE_INTEGER
                || repositoryId <= 0 || repositoryId > CanonicalJson.MAX_SAFE_INTEGER) {
            throw new IllegalArgumentException("Certification size and repository ID must be positive safe integers");
        }
    }
}
