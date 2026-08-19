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

import org.jackhuang.hmcl.plugin.internal.VerifiedPluginPackage;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/// Holds a package path, validated manifest, and exact identity selected before dependency traversal.
@NotNullByDefault
final class PluginPackageCandidate {
    /// Installed package path.
    final Path nplFile;

    /// Validated package manifest.
    final PluginManifest manifest;

    /// Exact package identity used for runtime policy and diagnostics.
    final PluginArtifactIdentity identity;

    /// Creates a package candidate.
    ///
    /// @param nplFile installed package path
    /// @param manifest validated manifest
    /// @param identity exact package identity
    PluginPackageCandidate(
            Path nplFile,
            PluginManifest manifest,
            PluginArtifactIdentity identity
    ) {
        this.nplFile = nplFile;
        this.manifest = manifest;
        this.identity = identity;
    }

    /// Verifies that the manifest captured during discovery belongs to the exact immutable package snapshot.
    ///
    /// @param pluginPackage verified package snapshot selected for lifecycle execution
    /// @throws IOException if the snapshot manifest is absent or differs from the discovered execution contract
    void verifySnapshotManifest(VerifiedPluginPackage pluginPackage) throws IOException {
        byte @Nullable @Unmodifiable [] verifiedManifestBytes = pluginPackage.readResourceBytes("plugin.json");
        if (verifiedManifestBytes == null) {
            throw new IOException("Verified plugin package has no root plugin.json: " + manifest.getId());
        }
        PluginManifest verifiedManifest = PluginManifest.fromJson(new InputStreamReader(
                new ByteArrayInputStream(verifiedManifestBytes),
                StandardCharsets.UTF_8
        ));
        if (!manifest.equals(verifiedManifest)
                || !identity.equals(PluginArtifactIdentity.of(verifiedManifest, identity.getSha256()))) {
            throw new IOException("Plugin manifest changed while the package was being verified: "
                    + manifest.getId());
        }
    }
}
