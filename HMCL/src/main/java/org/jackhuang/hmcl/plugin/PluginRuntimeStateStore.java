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

import java.util.HashMap;
import java.util.Map;

/// Stores exact published artifact identities, runtime states, and diagnostics for one manager process.
@NotNullByDefault
final class PluginRuntimeStateStore {
    /// Current published artifacts indexed by plugin ID.
    private final Map<String, PluginArtifactIdentity> currentArtifacts = new HashMap<>();

    /// Runtime states indexed by exact package artifact.
    private final Map<PluginArtifactIdentity, PluginRuntimeStatus> statuses = new HashMap<>();

    /// Runtime diagnostics indexed by exact package artifact.
    private final Map<PluginArtifactIdentity, String> details = new HashMap<>();

    /// Creates an empty process-local state store.
    PluginRuntimeStateStore() {
    }

    /// Clears every remembered artifact and diagnostic before a fresh discovery pass.
    void clear() {
        currentArtifacts.clear();
        statuses.clear();
        details.clear();
    }

    /// Remembers one exact artifact without discarding its existing status.
    ///
    /// @param identity exact published artifact
    void remember(PluginArtifactIdentity identity) {
        currentArtifacts.put(identity.getPluginId(), identity);
    }

    /// Replaces all old state for a plugin ID with one newly published artifact identity.
    ///
    /// @param identity newly published artifact
    void replace(PluginArtifactIdentity identity) {
        removePlugin(identity.getPluginId());
        remember(identity);
    }

    /// Removes every identity, status, and diagnostic belonging to one plugin ID.
    ///
    /// @param pluginId plugin ID
    void removePlugin(String pluginId) {
        currentArtifacts.remove(pluginId);
        statuses.keySet().removeIf(identity -> identity.getPluginId().equals(pluginId));
        details.keySet().removeIf(identity -> identity.getPluginId().equals(pluginId));
    }

    /// Returns the remembered current artifact for one plugin ID.
    ///
    /// @param pluginId plugin ID
    /// @return exact artifact or `null`
    @Nullable PluginArtifactIdentity getCurrent(String pluginId) {
        return currentArtifacts.get(pluginId);
    }

    /// Records one artifact-bound runtime state and optional diagnostic.
    ///
    /// @param identity exact artifact
    /// @param status authoritative runtime state
    /// @param detail diagnostic or `null`
    void set(PluginArtifactIdentity identity, PluginRuntimeStatus status, @Nullable String detail) {
        statuses.put(identity, status);
        if (detail == null || detail.isBlank()) {
            details.remove(identity);
        } else {
            details.put(identity, detail);
        }
    }

    /// Returns one exact artifact's recorded runtime state.
    ///
    /// @param identity exact artifact
    /// @return state or `null`
    @Nullable PluginRuntimeStatus getStatus(PluginArtifactIdentity identity) {
        return statuses.get(identity);
    }

    /// Returns one exact artifact's recorded diagnostic.
    ///
    /// @param identity exact artifact
    /// @return diagnostic or `null`
    @Nullable String getDetail(PluginArtifactIdentity identity) {
        return details.get(identity);
    }
}
