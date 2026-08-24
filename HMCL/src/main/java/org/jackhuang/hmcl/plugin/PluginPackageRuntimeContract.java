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

import org.jackhuang.hmcl.plugin.runtime.PluginExecutionMode;
import org.jackhuang.hmcl.plugin.runtime.PluginRuntimeTypes;
import org.jackhuang.hmcl.plugin.runtime.RuntimeProviderDeclaration;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.List;
import java.util.Objects;

/// Immutable Store expectation for every package field that defines a schema-v5 runtime contract.
@NotNullByDefault
public record PluginPackageRuntimeContract(
        String version,
        int schemaVersion,
        String runtime,
        int abi,
        @Unmodifiable List<String> platforms,
        PluginKind pluginKind,
        PluginExecutionMode executionMode,
        @Nullable String runtimeProvider,
        @Unmodifiable List<RuntimeProviderDeclaration> providesRuntimes
) {
    /// Creates a validated defensive snapshot independent of mutable Store metadata.
    public PluginPackageRuntimeContract {
        version = Objects.requireNonNull(version);
        if (version.isBlank()) {
            throw new IllegalArgumentException("Package runtime contract version cannot be blank");
        }
        if (schemaVersion <= 0) {
            throw new IllegalArgumentException("Package runtime contract schema version must be positive");
        }
        runtime = PluginRuntimeTypes.requireValid(runtime);
        if (abi <= 0) {
            throw new IllegalArgumentException("Package runtime contract ABI must be positive");
        }
        platforms = List.copyOf(platforms);
        pluginKind = Objects.requireNonNull(pluginKind);
        executionMode = Objects.requireNonNull(executionMode);
        providesRuntimes = List.copyOf(providesRuntimes);
    }

    /// Captures the exact runtime contract parsed from a downloaded package manifest.
    ///
    /// @param manifest validated downloaded package manifest
    /// @return immutable package runtime contract
    public static PluginPackageRuntimeContract fromManifest(PluginManifest manifest) {
        return new PluginPackageRuntimeContract(
                manifest.getVersion(),
                manifest.getSchemaVersion(),
                manifest.getRuntime(),
                manifest.getAbi(),
                manifest.getPlatforms(),
                manifest.getPluginKind(),
                manifest.getExecutionMode(),
                manifest.getRuntimeProvider(),
                manifest.getProvidesRuntimes()
        );
    }
}
