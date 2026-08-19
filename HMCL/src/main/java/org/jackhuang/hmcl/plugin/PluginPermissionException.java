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

/// Reports an attempt to use an official launcher capability that the user has not granted.
@NotNullByDefault
public final class PluginPermissionException extends SecurityException {
    /// Plugin ID whose capability request was denied.
    private final String pluginId;

    /// Capability required by the rejected operation.
    private final PluginPermission permission;

    /// Reason the capability is unavailable to the plugin.
    private final Reason reason;

    /// Creates a permission-denied exception for one plugin capability.
    ///
    /// @param pluginId plugin requesting the capability
    /// @param permission capability that is not currently granted
    public PluginPermissionException(String pluginId, PluginPermission permission) {
        this(pluginId, permission, Reason.USER_DENIED);
    }

    /// Creates a permission-denied exception with an explicit denial reason.
    ///
    /// @param pluginId plugin requesting the capability
    /// @param permission capability that is unavailable
    /// @param reason whether the developer omitted the request or the user denied it
    public PluginPermissionException(String pluginId, PluginPermission permission, Reason reason) {
        super(reason == Reason.NOT_DECLARED
                ? "Plugin " + pluginId + " did not declare permission " + permission.getId()
                : "Plugin " + pluginId + " is not granted permission " + permission.getId());
        this.pluginId = pluginId;
        this.permission = permission;
        this.reason = reason;
    }

    /// Returns the plugin whose operation was rejected.
    ///
    /// @return requesting plugin ID
    public String getPluginId() {
        return pluginId;
    }

    /// Returns the capability required by the rejected operation.
    ///
    /// @return missing permission
    public PluginPermission getPermission() {
        return permission;
    }

    /// Returns why the capability is unavailable.
    ///
    /// @return denial reason
    public Reason getReason() {
        return reason;
    }

    /// Distinguishes missing developer declarations from user-denied requests.
    @NotNullByDefault
    public enum Reason {
        /// The package manifest did not request the capability, so the user cannot grant it.
        NOT_DECLARED,

        /// The package requested the capability, but the user has not granted it.
        USER_DENIED
    }
}
