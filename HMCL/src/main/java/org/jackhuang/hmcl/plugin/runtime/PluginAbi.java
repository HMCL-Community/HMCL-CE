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

/// Versioned contract between the launcher and plugin packages.
///
/// The HMCL Plugin ABI is the compatibility boundary for plugin packages and is deliberately independent
/// from launcher release versions: a launcher that implements ABI N can execute every package that
/// requires ABI M with M &lt;= N. Runtime providers declare which ABI generations they implement, so
/// refreshing a language runtime never strands the plugins that depend on it.
@NotNullByDefault
public final class PluginAbi {
    /// First generation shipped with the 26.x JVM-only plugin system (schema v4 manifests).
    public static final int ABI_1 = 1;

    /// Next generation introduced by the 27.x line: runtime providers, platform targets, schema v5.
    public static final int ABI_2 = 2;

    /// Highest ABI generation implemented by this launcher build.
    public static final int CURRENT_PLUGIN_ABI = ABI_2;

    private PluginAbi() {
    }

    /// Returns whether this launcher build can execute a package that requires the given generation.
    public static boolean supports(int requiredAbi) {
        return requiredAbi >= ABI_1 && requiredAbi <= CURRENT_PLUGIN_ABI;
    }

    /// Validates an ABI number parsed from a manifest.
    ///
    /// @throws IllegalArgumentException when the generation is unknown to this build
    public static int requireValid(int abi) {
        if (!supports(abi)) {
            throw new IllegalArgumentException("Unsupported HMCL plugin ABI: " + abi);
        }
        return abi;
    }
}
