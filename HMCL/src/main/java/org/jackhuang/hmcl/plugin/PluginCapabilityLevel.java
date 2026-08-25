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
/// Level 1 API plugins only call Core API functions. Level 2 describes lifecycle Hook declarations routed through
/// the Hook dispatcher, including supported external Runtime endpoints. Level 3 reserves declarative patches;
/// HMCL validates and exposes Patch declarations but does not yet apply them through a bytecode engine.
@NotNullByDefault
public enum PluginCapabilityLevel {
    /// Calls Core API functions only; the safest tier.
    API,

    /// Declares launcher lifecycle Hook subscriptions routed through the Hook dispatcher.
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
