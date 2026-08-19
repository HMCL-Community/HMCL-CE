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

/// Package-owned lifecycle fixture that fails deterministically during activation.
@NotNullByDefault
public final class PackagedThrowingOnEnablePlugin implements Plugin {
    /// Manifest received during `onLoad`, or `null` before registration.
    private @Nullable PluginManifest manifest;

    /// Creates the activation-failure fixture.
    public PackagedThrowingOnEnablePlugin() {
    }

    /// Stores the package manifest before activation is attempted.
    ///
    /// @param context plugin runtime context
    @Override
    public void onLoad(PluginContext context) {
        manifest = context.getManifest();
    }

    /// Fails activation so dependent lifecycle isolation can be verified.
    @Override
    public void onEnable() {
        throw new IllegalStateException("Expected onEnable failure");
    }

    /// Performs no cleanup because activation never succeeds.
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
