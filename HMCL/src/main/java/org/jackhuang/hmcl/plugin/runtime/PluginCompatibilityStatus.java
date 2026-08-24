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
package org.jackhuang.hmcl.plugin.runtime;

import org.jetbrains.annotations.NotNullByDefault;

/// Outcome of evaluating one plugin package against the current launcher host.
@NotNullByDefault
public enum PluginCompatibilityStatus {
    /// Every compatibility requirement is satisfied.
    COMPATIBLE,

    /// The package manifest schema cannot execute on this launcher.
    UNSUPPORTED_SCHEMA,

    /// The launcher version does not satisfy the package constraint.
    UNSUPPORTED_LAUNCHER,

    /// The current host does not match any declared package platform.
    UNSUPPORTED_PLATFORM,

    /// No provider is registered for the package runtime.
    MISSING_RUNTIME,

    /// The registered runtime provider does not implement the required ABI.
    UNSUPPORTED_ABI,

    /// Registered providers do not support the requested execution boundary.
    UNSUPPORTED_EXECUTION_MODE,

    /// Registered providers do not implement the required launcher-to-provider Bridge ABI.
    UNSUPPORTED_BRIDGE_ABI,

    /// Registered providers lack at least one required runtime feature.
    UNSUPPORTED_RUNTIME_FEATURE
}
