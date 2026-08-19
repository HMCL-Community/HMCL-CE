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
import org.jetbrains.annotations.Unmodifiable;

import java.util.Map;

/// Immutable atomic snapshot used to plan and later revalidate one plugin-store installation.
@NotNullByDefault
public final class PluginInstallationPlanningSnapshot {
    /// Installed manifests visible to dependency resolution.
    private final @Unmodifiable Map<String, PluginManifest> manifests;

    /// Exact prior artifacts for every installed manifest.
    private final @Unmodifiable Map<String, PluginArtifactIdentity> installedArtifacts;

    /// Exact installed artifacts currently eligible for dependency reuse.
    private final @Unmodifiable Map<String, PluginArtifactIdentity> reusableArtifacts;

    /// Creates one validated immutable planning snapshot.
    ///
    /// @param manifests installed manifests indexed by plugin ID
    /// @param installedArtifacts exact prior artifact for every installed manifest
    /// @param reusableArtifacts reusable subset of `installedArtifacts`
    PluginInstallationPlanningSnapshot(
            @Unmodifiable Map<String, PluginManifest> manifests,
            @Unmodifiable Map<String, PluginArtifactIdentity> installedArtifacts,
            @Unmodifiable Map<String, PluginArtifactIdentity> reusableArtifacts
    ) {
        this.manifests = Map.copyOf(manifests);
        this.installedArtifacts = Map.copyOf(installedArtifacts);
        this.reusableArtifacts = Map.copyOf(reusableArtifacts);
        if (!this.manifests.keySet().equals(this.installedArtifacts.keySet())) {
            throw new IllegalArgumentException("Every planning manifest must have exactly one artifact identity");
        }
        if (!this.installedArtifacts.keySet().containsAll(this.reusableArtifacts.keySet())) {
            throw new IllegalArgumentException("Reusable artifacts must belong to the installed snapshot");
        }
        for (Map.Entry<String, PluginManifest> entry : this.manifests.entrySet()) {
            PluginArtifactIdentity identity = this.installedArtifacts.get(entry.getKey());
            if (identity == null
                    || !entry.getKey().equals(identity.getPluginId())
                    || !entry.getValue().getVersion().equals(identity.getVersion())) {
                throw new IllegalArgumentException("Installed artifact does not match its planning manifest: "
                        + entry.getKey());
            }
            @Nullable PluginArtifactIdentity reusable = this.reusableArtifacts.get(entry.getKey());
            if (reusable != null && !identity.equals(reusable)) {
                throw new IllegalArgumentException("Reusable artifact differs from the installed snapshot: "
                        + entry.getKey());
            }
        }
    }

    /// Returns installed manifests used for dependency resolution.
    ///
    /// @return immutable manifests indexed by plugin ID
    public @Unmodifiable Map<String, PluginManifest> getManifests() {
        return manifests;
    }

    /// Returns exact prior artifacts for all installed plugin IDs.
    ///
    /// @return immutable installed artifact identities
    public @Unmodifiable Map<String, PluginArtifactIdentity> getInstalledArtifacts() {
        return installedArtifacts;
    }

    /// Returns the exact installed subset eligible for dependency reuse.
    ///
    /// @return immutable reusable artifact identities
    public @Unmodifiable Map<String, PluginArtifactIdentity> getReusableArtifacts() {
        return reusableArtifacts;
    }
}
