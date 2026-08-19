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

import java.util.Objects;

/// Top-level lifecycle fixture whose bytecode is copied into generated `.npl` test packages.
@NotNullByDefault
public final class PackagedTestPlugin implements Plugin {
    /// Manifest received during `onLoad`, or `null` before registration.
    private @Nullable PluginManifest manifest;

    /// Creates the package-owned lifecycle fixture.
    public PackagedTestPlugin() {
    }

    /// Stores the exact package context supplied by the manager.
    ///
    /// @param context plugin runtime context
    @Override
    public void onLoad(PluginContext context) {
        manifest = context.getManifest();
    }

    /// Activates the no-op fixture.
    @Override
    public void onEnable() {
    }

    /// Deactivates the no-op fixture.
    @Override
    public void onDisable() {
    }

    /// Returns the manifest received during registration.
    ///
    /// @return plugin manifest
    @Override
    public PluginManifest getManifest() {
        return Objects.requireNonNull(manifest);
    }
}
