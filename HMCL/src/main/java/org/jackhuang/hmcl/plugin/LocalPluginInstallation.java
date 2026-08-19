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

/// Describes a plugin package published for execution after the next launcher restart.
@NotNullByDefault
public final class LocalPluginInstallation {
    /// Validated package manifest.
    private final PluginManifest manifest;

    /// Legacy prepared lifecycle value, always `null` because every installation is staged-only.
    private final @Nullable PreparedPlugin preparedPlugin;

    /// Creates a local installation result.
    ///
    /// @param manifest validated package manifest
    /// @param preparedPlugin legacy prepared plugin value or `null` for the required staged installation
    private LocalPluginInstallation(
            PluginManifest manifest,
            @Nullable PreparedPlugin preparedPlugin
    ) {
        this.manifest = manifest;
        this.preparedPlugin = preparedPlugin;
    }

    /// Creates a result for a package staged for restart.
    ///
    /// @param manifest installed package manifest
    /// @return restart-staged installation result
    static LocalPluginInstallation staged(PluginManifest manifest) {
        return new LocalPluginInstallation(manifest, null);
    }

    /// Returns the validated replacement or installation manifest.
    ///
    /// @return package manifest
    public PluginManifest getManifest() {
        return manifest;
    }

    /// Returns whether this installation waits for a launcher restart.
    ///
    /// @return whether no runtime registration should be attempted
    public boolean isRestartRequired() {
        return preparedPlugin == null;
    }

    /// Rejects access to the removed immediate-registration result.
    ///
    /// @return prepared plugin
    /// @throws IllegalStateException because public installations are always restart-staged
    public PreparedPlugin getPreparedPlugin() {
        if (preparedPlugin == null) {
            throw new IllegalStateException("Plugin installation is staged for restart");
        }
        return preparedPlugin;
    }
}
