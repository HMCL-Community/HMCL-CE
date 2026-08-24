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

    /// Exact fully eligible artifacts whose only reuse failure is their disabled state.
    private final @Unmodifiable Map<String, PluginArtifactIdentity> activatableArtifacts;

    /// Creates one validated immutable planning snapshot.
    ///
    /// @param manifests installed manifests indexed by plugin ID
    /// @param installedArtifacts exact prior artifact for every installed manifest
    /// @param reusableArtifacts reusable subset of `installedArtifacts`
    /// @param activatableArtifacts disabled but otherwise reusable subset of `installedArtifacts`
    PluginInstallationPlanningSnapshot(
            @Unmodifiable Map<String, PluginManifest> manifests,
            @Unmodifiable Map<String, PluginArtifactIdentity> installedArtifacts,
            @Unmodifiable Map<String, PluginArtifactIdentity> reusableArtifacts,
            @Unmodifiable Map<String, PluginArtifactIdentity> activatableArtifacts
    ) {
        this.manifests = Map.copyOf(manifests);
        this.installedArtifacts = Map.copyOf(installedArtifacts);
        this.reusableArtifacts = Map.copyOf(reusableArtifacts);
        this.activatableArtifacts = Map.copyOf(activatableArtifacts);
        if (!this.manifests.keySet().equals(this.installedArtifacts.keySet())) {
            throw new IllegalArgumentException("Every planning manifest must have exactly one artifact identity");
        }
        if (!this.installedArtifacts.keySet().containsAll(this.reusableArtifacts.keySet())
                || !this.installedArtifacts.keySet().containsAll(this.activatableArtifacts.keySet())) {
            throw new IllegalArgumentException("Eligible artifacts must belong to the installed snapshot");
        }
        if (this.reusableArtifacts.keySet().stream().anyMatch(this.activatableArtifacts::containsKey)) {
            throw new IllegalArgumentException("An installed artifact cannot be reusable and activatable");
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
            @Nullable PluginArtifactIdentity activatable = this.activatableArtifacts.get(entry.getKey());
            if (activatable != null && !identity.equals(activatable)) {
                throw new IllegalArgumentException("Activatable artifact differs from the installed snapshot: "
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

    /// Returns the exact disabled subset eligible for explicit activation.
    ///
    /// @return immutable activatable artifact identities
    public @Unmodifiable Map<String, PluginArtifactIdentity> getActivatableArtifacts() {
        return activatableArtifacts;
    }
}
