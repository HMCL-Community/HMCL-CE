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

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/// Process-wide registry of available plugin runtime providers.
///
/// The Java provider is registered eagerly. Runtime plugins register additional providers (dotnet,
/// python, javascript, native) once their host artifacts are installed, and the plugin manager consults
/// the registry both when resolving install plans and when a package without a matching provider loads.
@NotNullByDefault
public final class RuntimeProviderRegistry {
    private final Map<String, RuntimeProvider> providers = new ConcurrentHashMap<>();

    /// Creates a registry containing the built-in Java provider.
    public RuntimeProviderRegistry() {
        register(new JavaRuntimeProvider());
    }

    /// Registers or replaces the provider serving one runtime type.
    ///
    /// @throws IllegalArgumentException when the runtime identifier is malformed
    public void register(RuntimeProvider provider) {
        String type = PluginRuntimeTypes.requireValid(provider.runtimeType());
        providers.put(type, provider);
    }

    /// Removes the provider serving one runtime type; the built-in Java provider is never removed.
    public void unregister(String runtimeType) {
        if (!PluginRuntimeTypes.JAVA.equals(runtimeType)) {
            providers.remove(PluginRuntimeTypes.requireValid(runtimeType));
        }
    }

    /// Returns the provider serving one runtime type.
    public Optional<RuntimeProvider> find(String runtimeType) {
        return Optional.ofNullable(providers.get(PluginRuntimeTypes.requireValid(runtimeType)));
    }

    /// Returns whether a provider is currently available for the runtime type.
    public boolean isAvailable(String runtimeType) {
        return find(runtimeType).isPresent();
    }

    /// Returns a snapshot description of every registered provider keyed by runtime type.
    public Map<String, String> describeAll() {
        return providers.values().stream()
                .collect(Collectors.toMap(RuntimeProvider::runtimeType, RuntimeProvider::describe));
    }

    /// Returns the number of registered providers, used by diagnostics and tests.
    public int size() {
        return providers.size();
    }
}
