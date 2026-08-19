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

/// Package-owned lifecycle fixture that throws an [AssertionError] during registration.
@NotNullByDefault
public final class PackagedThrowingOnLoadErrorPlugin implements Plugin {
    /// Creates the registration-error fixture.
    public PackagedThrowingOnLoadErrorPlugin() {
    }

    /// Throws an error to verify discovery isolates plugin failures from later candidates.
    ///
    /// @param context ignored plugin runtime context
    @Override
    public void onLoad(PluginContext context) {
        throw new AssertionError("Expected onLoad error");
    }

    /// Performs no activation because registration always fails.
    @Override
    public void onEnable() {
    }

    /// Performs no deactivation because registration always fails.
    @Override
    public void onDisable() {
    }

    /// Cannot return a manifest because registration always fails first.
    ///
    /// @return never returns normally
    @Override
    public PluginManifest getManifest() {
        throw new IllegalStateException("Plugin was not loaded");
    }
}
