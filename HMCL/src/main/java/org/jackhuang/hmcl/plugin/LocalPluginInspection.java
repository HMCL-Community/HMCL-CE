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
package org.jackhuang.hmcl.plugin;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;

/// Immutable read-only metadata used to confirm a local package before installation changes launcher state.
@NotNullByDefault
public final class LocalPluginInspection {
    /// Normalized source package path inspected by the manager.
    final Path sourcePackage;

    /// Validated source package manifest displayed for confirmation.
    final PluginManifest manifest;

    /// Lower-case SHA-256 digest binding preparation to the inspected source bytes.
    final String sha256;

    /// Currently installed manifest for the same ID, or `null` for a new plugin.
    final @Nullable PluginManifest oldManifest;

    /// Exact prior artifact observed while the package was inspected, or `null` for a confirmed new ID.
    final @Nullable PluginArtifactIdentity priorArtifactIdentity;

    /// Creates an immutable local package inspection.
    ///
    /// @param sourcePackage normalized inspected source path
    /// @param manifest validated source manifest
    /// @param sha256 lower-case source digest
    /// @param oldManifest currently installed manifest or `null`
    /// @param priorArtifactIdentity exact prior artifact or `null` when absent
    LocalPluginInspection(
            Path sourcePackage,
            PluginManifest manifest,
            String sha256,
            @Nullable PluginManifest oldManifest,
            @Nullable PluginArtifactIdentity priorArtifactIdentity
    ) {
        this.sourcePackage = sourcePackage;
        this.manifest = manifest;
        this.sha256 = sha256;
        this.oldManifest = oldManifest;
        this.priorArtifactIdentity = priorArtifactIdentity;
    }

    /// Returns the normalized package path that was inspected.
    ///
    /// @return inspected source package
    public Path getSourcePackage() {
        return sourcePackage;
    }

    /// Returns the validated source package manifest.
    ///
    /// @return inspected manifest
    public PluginManifest getManifest() {
        return manifest;
    }

    /// Returns the lower-case SHA-256 digest of the inspected source bytes.
    ///
    /// @return package SHA-256
    public String getSha256() {
        return sha256;
    }

    /// Returns the currently installed manifest for the same plugin ID.
    ///
    /// @return old manifest or `null` when installing a new plugin ID
    public @Nullable PluginManifest getOldManifest() {
        return oldManifest;
    }

    /// Returns the exact prior artifact observed during inspection.
    ///
    /// @return prior artifact identity or `null` when the plugin ID was absent
    public @Nullable PluginArtifactIdentity getPriorArtifactIdentity() {
        return priorArtifactIdentity;
    }
}
