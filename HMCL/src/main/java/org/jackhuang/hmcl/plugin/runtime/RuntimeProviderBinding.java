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

import org.jackhuang.hmcl.plugin.PluginManifest;
import org.jetbrains.annotations.NotNullByDefault;

/// Records the provider selected for one dependent plugin and runtime requirement.
///
/// @param dependentPluginId plugin whose payload consumes the runtime
/// @param providerId selected provider plugin ID
/// @param runtime canonical runtime capability bound to the dependent
@NotNullByDefault
public record RuntimeProviderBinding(String dependentPluginId, String providerId, String runtime) {
    /// Validates the immutable binding identity.
    public RuntimeProviderBinding {
        if (!PluginManifest.isCanonicalExecutableId(dependentPluginId)) {
            throw new IllegalArgumentException("Dependent plugin ID must be canonical: " + dependentPluginId);
        }
        if (!PluginManifest.isCanonicalExecutableId(providerId)) {
            throw new IllegalArgumentException("Runtime provider ID must be canonical: " + providerId);
        }
        String canonicalRuntime = PluginRuntimeTypes.requireValid(runtime);
        if (!canonicalRuntime.equals(runtime)) {
            throw new IllegalArgumentException("Bound runtime identifier must be canonical: " + runtime);
        }
    }
}
