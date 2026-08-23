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

/// SPI implemented by every plugin runtime provider.
///
/// The launcher currently ships one in-process Java provider. This interface defines the registration and
/// compatibility boundary for future external providers; runtime-plugin discovery, lifecycle management, and
/// store installation are not yet connected to it.
@NotNullByDefault
public interface RuntimeProvider {
    /// Canonical runtime identifier this provider serves, such as java or dotnet.
    String runtimeType();

    /// ABI generations this provider can execute, for example [1, 2] for a current host.
    @Unmodifiable Set<Integer> implementedPluginAbis();

    /// Returns whether the provider can execute a package that requires the given ABI generation.
    default boolean supportsAbi(int requiredAbi) {
        return implementedPluginAbis().contains(requiredAbi);
    }

    /// Human-readable provider description shown in plugin manager diagnostics.
    String describe();
}
