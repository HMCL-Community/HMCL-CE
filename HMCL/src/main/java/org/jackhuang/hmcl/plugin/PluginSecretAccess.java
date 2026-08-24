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

import java.util.Objects;

/// Resolves opaque launch-scoped secret slots when the plugin holds the `account` permission.
@FunctionalInterface
@NotNullByDefault
public interface PluginSecretAccess {
    /// Resolves one opaque slot without exposing other slot names.
    ///
    /// @param slot canonical secret slot
    /// @return copied secret value
    /// @throws PluginPermissionException if account access is unavailable
    /// @throws IllegalArgumentException if the granted accessor does not contain the slot
    String resolve(String slot);

    /// Creates an accessor that uniformly denies every slot lookup.
    ///
    /// @param pluginId plugin receiving the accessor
    /// @return permission-denied accessor
    static PluginSecretAccess denied(String pluginId) {
        Objects.requireNonNull(pluginId, "pluginId");
        if (pluginId.isBlank()) {
            throw new IllegalArgumentException("Plugin ID must not be blank");
        }
        return slot -> {
            Objects.requireNonNull(slot, "slot");
            throw new PluginPermissionException(pluginId, PluginPermission.ACCOUNT);
        };
    }
}
