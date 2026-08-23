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
import org.jetbrains.annotations.Unmodifiable;

import java.util.Set;

/// Built-in runtime provider that executes Java and Kotlin plugins inside the launcher JVM.
///
/// This provider is always registered first and cannot be replaced by an installed runtime plugin, so
/// the Java plugin system keeps working even when every external runtime is missing.
@NotNullByDefault
public final class JavaRuntimeProvider implements RuntimeProvider {
    @Override
    public String runtimeType() {
        return PluginRuntimeTypes.JAVA;
    }

    /// Returns the Plugin ABI generations recognized for in-process Java and Kotlin packages.
    ///
    /// @return immutable set of supported ABI generations
    @Override
    public @Unmodifiable Set<Integer> implementedPluginAbis() {
        return Set.of(PluginAbi.ABI_1, PluginAbi.ABI_2);
    }

    @Override
    public String describe() {
        return "Built-in Java plugin runtime (in-process JVM)";
    }
}
