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

import java.io.IOException;
import java.util.Set;

/// Test helper loaded through a detached ordinary `URLClassLoader` to attempt self-authorization.
@NotNullByDefault
public final class DetachedPluginAdministrativeCaller implements Runnable {
    /// Manager targeted by the detached plugin-like caller.
    private final PluginManager manager;

    /// Installed plugin ID whose denied permission must remain denied.
    private final String pluginId;

    /// Creates a detached administrative caller.
    ///
    /// @param manager isolated plugin manager
    /// @param pluginId installed plugin ID
    public DetachedPluginAdministrativeCaller(PluginManager manager, String pluginId) {
        this.manager = manager;
        this.pluginId = pluginId;
    }

    /// Attempts to grant filesystem access without launcher confirmation.
    @Override
    public void run() {
        try {
            manager.setGrantedPermissions(pluginId, Set.of(PluginPermission.FILESYSTEM));
        } catch (IOException exception) {
            throw new IllegalStateException("Ucepected permission persistence failure", exception);
        }
    }
}
