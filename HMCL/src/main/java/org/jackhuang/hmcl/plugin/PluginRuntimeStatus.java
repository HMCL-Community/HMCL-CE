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

/// Describes the authoritative lifecycle state of the currently published plugin artifact.
@NotNullByDefault
public enum PluginRuntimeStatus {
    /// The package is installed but its lifecycle is not selected for startup.
    INSTALLED_DISABLED,

    /// The package uses a legacy manifest schema and is retained only for management or update.
    BLOCKED_LEGACY,

    /// A proof-backed certified package is revoked or its retained certification no longer verifies.
    BLOCKED_REVOKED,

    /// A Mixin package is missing a declaration or at least one requested capability was denied.
    BLOCKED_PERMISSION,

    /// The package or desired enablement changed and can execute only after a clean restart.
    WAITING_FOR_RESTART,

    /// The exact Mixin artifact was not confirmed by the active premain Agent.
    BLOCKED_AGENT,

    /// The exact artifact completed `onLoad` and `onEnable` in this process.
    ENABLED,

    /// Package validation, dependency resolution, loading, or lifecycle activation failed.
    LOAD_FAILED,

    /// The package remains visible but will be removed before the next startup discovery.
    PENDING_UNINSTALL
}
