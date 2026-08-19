/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2020  huangyuhui <huanghongxun2008@126.com> and contributors
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

/// Defines the lifecycle implemented by Java and Kotlin HMCL plugins.
@NotNullByDefault
public interface Plugin {
    /// Receives immutable package metadata and launcher services after the plugin class is created.
    ///
    /// @param context plugin context
    void onLoad(PluginContext context);

    /// Activates the plugin after all declared dependencies are loaded and enabled.
    void onEnable();

    /// Deactivates runtime registrations owned by the plugin.
    void onDisable();

    /// Releases resources immediately before the plugin class loader is closed.
    default void onUnload() {
    }

    /// Returns the plugin manifest associated with this instance.
    ///
    /// The manager uses the package manifest as the authoritative value and exposes this method for compatibility.
    ///
    /// @return plugin manifest
    PluginManifest getManifest();
}
