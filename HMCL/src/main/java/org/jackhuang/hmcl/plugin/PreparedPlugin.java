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

import java.nio.file.Path;

/// Holds one internally verified lifecycle instance during startup discovery before JavaFX registration.
@NotNullByDefault
public final class PreparedPlugin {
    /// Loaded lifecycle implementation.
    final Plugin plugin;

    /// Context prepared for the lifecycle implementation.
    final PluginContext context;

    /// Validated package manifest.
    final PluginManifest manifest;

    /// Installed package path.
    final Path nplFile;

    /// Creates a prepared plugin value.
    ///
    /// @param plugin lifecycle implementation
    /// @param context plugin context
    /// @param manifest validated manifest
    /// @param nplFile installed package path
    PreparedPlugin(
            Plugin plugin,
            PluginContext context,
            PluginManifest manifest,
            Path nplFile
    ) {
        this.plugin = plugin;
        this.context = context;
        this.manifest = manifest;
        this.nplFile = nplFile;
    }

    /// Returns the validated manifest for installation UI decisions.
    ///
    /// @return plugin manifest
    public PluginManifest getManifest() {
        return manifest;
    }
}
