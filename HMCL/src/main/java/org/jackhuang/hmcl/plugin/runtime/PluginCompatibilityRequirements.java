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
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.List;
import java.util.Set;

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

    /// Launcher-to-provider Bridge ABI generation required by the package.
    private final int bridgeAbi;

    /// Requested runtime execution boundary.
    private final PluginExecutionMode executionMode;

    /// Immutable runtime features required by the package.
    private final @Unmodifiable Set<RuntimeFeature> requiredFeatures;

    /// Optional explicit provider plugin ID pin.
    private final @Nullable String pinnedProviderId;

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
        this(schemaVersion, launcherVersion, new RuntimeRequirement(
                PluginRuntimeTypes.requireValid(runtime),
                abi,
                1,
                PluginExecutionMode.EMBEDDED,
                Set.of(RuntimeFeature.BRIDGE),
                null
        ), platforms);
    }

    /// Creates compatibility requirements with a complete schema-v5 runtime provider contract.
    ///
    /// @param schemaVersion manifest schema generation
    /// @param launcherVersion launcher version constraint expression
    /// @param runtimeRequirement complete runtime provider selection requirement
    /// @param platforms supported platform targets, or an empty list when unrestricted
    public PluginCompatibilityRequirements(
            int schemaVersion,
            String launcherVersion,
            RuntimeRequirement runtimeRequirement,
            List<PluginPlatformTarget> platforms) {
        this.schemaVersion = schemaVersion;
        this.launcherVersion = PluginVersionConstraint.parse(launcherVersion).getExpression();
        this.runtime = runtimeRequirement.getRuntime();
        this.abi = runtimeRequirement.getPluginAbi();
        this.bridgeAbi = runtimeRequirement.getBridgeAbi();
        this.executionMode = runtimeRequirement.getExecutionMode();
        this.requiredFeatures = Set.copyOf(runtimeRequirement.getRequiredFeatures());
        this.pinnedProviderId = runtimeRequirement.getPinnedProviderId();
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
        if (manifest.getSchemaVersion() >= 5) {
            return new PluginCompatibilityRequirements(
                    manifest.getSchemaVersion(),
                    manifest.getLauncherVersion(),
                    manifest.getRuntimeRequirement(),
                    platformTargets
            );
        }
        return new PluginCompatibilityRequirements(manifest.getSchemaVersion(), manifest.getLauncherVersion(),
                manifest.getRuntime(), manifest.getAbi(), platformTargets);
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

    /// Returns the required launcher-to-provider Bridge ABI generation.
    public int bridgeAbi() {
        return bridgeAbi;
    }

    /// Returns the requested execution boundary.
    public PluginExecutionMode executionMode() {
        return executionMode;
    }

    /// Returns the immutable required runtime features.
    public @Unmodifiable Set<RuntimeFeature> requiredFeatures() {
        return requiredFeatures;
    }

    /// Returns the explicitly pinned provider plugin ID, or `null` when unpinned.
    public @Nullable String pinnedProviderId() {
        return pinnedProviderId;
    }

    /// Reconstructs the immutable runtime selection contract used by the registry and selector.
    public RuntimeRequirement runtimeRequirement() {
        return new RuntimeRequirement(runtime, abi, bridgeAbi, executionMode, requiredFeatures, pinnedProviderId);
    }

    /// Returns the immutable supported platform targets, empty when unrestricted.
    public @Unmodifiable List<PluginPlatformTarget> platforms() {
        return platforms;
    }
}
