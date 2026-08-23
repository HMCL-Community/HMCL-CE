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
import org.jackhuang.hmcl.plugin.PluginVersionConstraint;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.util.List;

/// Immutable compatibility requirements shared by installation and runtime checks.
@NotNullByDefault
public final class PluginCompatibilityRequirements {
    /// Manifest schema generation required by the package.
    private final int schemaVersion;

    /// Launcher version constraint expression required by the package.
    private final String launcherVersion;

    /// Canonical runtime identifier required by the package.
    private final String runtime;

    /// Runtime ABI generation required by the package.
    private final int abi;

    /// Immutable platform targets on which the package may run.
    private final @Unmodifiable List<PluginPlatformTarget> platforms;

    /// Creates an immutable set of plugin compatibility requirements.
    ///
    /// @param schemaVersion manifest schema generation
    /// @param launcherVersion launcher version constraint expression
    /// @param runtime runtime identifier
    /// @param abi required runtime ABI generation
    /// @param platforms supported platform targets, or an empty list when unrestricted
    public PluginCompatibilityRequirements(
            int schemaVersion,
            String launcherVersion,
            String runtime,
            int abi,
            List<PluginPlatformTarget> platforms) {
        this.schemaVersion = schemaVersion;
        this.launcherVersion = PluginVersionConstraint.parse(launcherVersion).getExpression();
        this.runtime = PluginRuntimeTypes.requireValid(runtime);
        this.abi = abi;
        this.platforms = List.copyOf(platforms);
    }

    /// Derives compatibility requirements from the manifest's normalized public contract.
    ///
    /// @param manifest validated plugin manifest
    /// @return immutable compatibility requirements
    public static PluginCompatibilityRequirements fromManifest(PluginManifest manifest) {
        @Unmodifiable List<PluginPlatformTarget> platformTargets = manifest.getPlatforms().stream()
                .map(PluginPlatformTarget::parse)
                .toList();
        return new PluginCompatibilityRequirements(
                manifest.getSchemaVersion(),
                manifest.getLauncherVersion(),
                manifest.getRuntime(),
                manifest.getAbi(),
                platformTargets
        );
    }

    /// Returns the required manifest schema generation.
    public int schemaVersion() {
        return schemaVersion;
    }

    /// Returns the required launcher version constraint expression.
    public String launcherVersion() {
        return launcherVersion;
    }

    /// Returns the canonical required runtime identifier.
    public String runtime() {
        return runtime;
    }

    /// Returns the required runtime ABI generation.
    public int abi() {
        return abi;
    }

    /// Returns the immutable supported platform targets, empty when unrestricted.
    public @Unmodifiable List<PluginPlatformTarget> platforms() {
        return platforms;
    }
}
