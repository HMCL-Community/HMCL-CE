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

/// Package-owned lifecycle fixture that fails deterministically during `onLoad`.
@NotNullByDefault
public final class PackagedThrowingOnLoadPlugin implements Plugin {
    /// Creates the failing lifecycle fixture.
    public PackagedThrowingOnLoadPlugin() {
    }

    /// Throws the deterministic registration failure.
    ///
    /// @param context ignored runtime context
    @Override
    public void onLoad(PluginContext context) {
        throw new IllegalStateException("Expected packaged onLoad failure");
    }

    /// Activates the unreachable fixture.
    @Override
    public void onEnable() {
    }

    /// Deactivates the unreachable fixture.
    @Override
    public void onDisable() {
    }

    /// Reports that registration never completed.
    ///
    /// @return never returns normally
    @Override
    public PluginManifest getManifest() {
        throw new IllegalStateException("Plugin did not finish loading");
    }
}
