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
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/// Process-wide registry of available plugin runtime providers.
///
/// The Java provider is registered eagerly, and compatibility checks consult the providers currently present.
/// The registration API is the boundary reserved for future external providers; automatic provider discovery,
/// external-runtime lifecycle management, and integration with store install plans are not yet implemented.
@NotNullByDefault
public final class RuntimeProviderRegistry {
    /// Registry shared by production plugin compatibility consumers.
    private static final RuntimeProviderRegistry PROCESS_WIDE = new RuntimeProviderRegistry();

    /// Providers keyed by canonical runtime identifier.
    private final Map<String, RuntimeProvider> providers = new ConcurrentHashMap<>();

    /// Creates a registry containing the built-in Java provider.
    public RuntimeProviderRegistry() {
        register(new JavaRuntimeProvider());
    }

    /// Returns the process-wide registry used by production plugin services.
    public static RuntimeProviderRegistry processWide() {
        return PROCESS_WIDE;
    }

    /// Registers the provider serving one runtime type without replacing an existing provider.
    ///
    /// @throws IllegalArgumentException when the runtime identifier is malformed
    /// @throws IllegalStateException when the canonical runtime identifier is already registered
    public void register(RuntimeProvider provider) {
        String type = PluginRuntimeTypes.requireValid(provider.runtimeType());
        @Nullable RuntimeProvider existing = providers.putIfAbsent(type, provider);
        if (existing != null) {
            throw new IllegalStateException("Plugin runtime provider is already registered: " + type);
        }
    }

    /// Removes the provider serving one runtime type; the built-in Java provider is never removed.
    public void unregister(String runtimeType) {
        String type = PluginRuntimeTypes.requireValid(runtimeType);
        if (!PluginRuntimeTypes.JAVA.equals(type)) {
            providers.remove(type);
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
    public @Unmodifiable Map<String, String> describeAll() {
        return providers.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, entry -> entry.getValue().describe()));
    }

    /// Returns the number of registered providers, used by diagnostics and tests.
    public int size() {
        return providers.size();
    }
}
