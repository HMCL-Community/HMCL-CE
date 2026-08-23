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

/// Capability tier a plugin operates at, derived from the declarations in its manifest.
///
/// Level 1 API plugins only call Core API functions. Level 2 and Level 3 describe hook and patch declarations
/// reserved by the schema-v5 contract. HMCL currently parses, validates, and exposes those declarations, but it
/// does not yet dispatch hooks or apply declarative patches.
@NotNullByDefault
public enum PluginCapabilityLevel {
    /// Calls Core API functions only; the safest tier.
    API,

    /// Declares launcher lifecycle hook subscriptions for the future hook dispatcher.
    HOOK,

    /// Declares method patches for the future JVM patch engine.
    PATCH;

    /// Returns the highest capability level a manifest enables.
    ///
    /// @param hookSubscribed whether the manifest declares hook points
    /// @param patchDeclared whether the manifest declares patches
    /// @return the highest enabled level, never null
    public static PluginCapabilityLevel of(boolean hookSubscribed, boolean patchDeclared) {
        if (patchDeclared) {
            return PATCH;
        }
        if (hookSubscribed) {
            return HOOK;
        }
        return API;
    }
}
