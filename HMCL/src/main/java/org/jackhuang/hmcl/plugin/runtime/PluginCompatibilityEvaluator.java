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
import java.util.Optional;

/// Evaluates plugin package requirements against launcher, platform, runtime, and ABI capabilities.
@NotNullByDefault
public final class PluginCompatibilityEvaluator {
    /// Evaluator shared by production plugin compatibility consumers.
    private static final PluginCompatibilityEvaluator PROCESS_WIDE = new PluginCompatibilityEvaluator(
            RuntimeProviderRegistry.processWide(),
            PluginPlatformTarget.current()
    );

    /// Runtime providers currently available to execute plugin packages.
    private final RuntimeProviderRegistry runtimeProviders;

    /// Host platform used for package target matching.
    private final PluginPlatformTarget hostPlatform;

    /// Creates a compatibility evaluator for one runtime registry and host platform.
    ///
    /// @param runtimeProviders available runtime provider registry
    /// @param hostPlatform host operating-system and architecture target
    public PluginCompatibilityEvaluator(
            RuntimeProviderRegistry runtimeProviders,
            PluginPlatformTarget hostPlatform) {
        this.runtimeProviders = runtimeProviders;
        this.hostPlatform = hostPlatform;
    }

    /// Returns the process-wide evaluator backed by the shared runtime provider registry.
    public static PluginCompatibilityEvaluator processWide() {
        return PROCESS_WIDE;
    }

    /// Evaluates package requirements in deterministic diagnostic-priority order.
    ///
    /// @param requirements package compatibility requirements
    /// @param launcherVersion current launcher version
    /// @return first incompatibility, or a compatible result when every dimension passes
    public PluginCompatibilityResult evaluate(
            PluginCompatibilityRequirements requirements,
            String launcherVersion) {
        int schemaVersion = requirements.schemaVersion();
        if (schemaVersion < PluginManifest.MIN_EXECUTABLE_SCHEMA_VERSION
                || schemaVersion > PluginManifest.CURRENT_SCHEMA_VERSION) {
            return new PluginCompatibilityResult(
                    PluginCompatibilityStatus.UNSUPPORTED_SCHEMA,
                    "Plugin manifest schema " + schemaVersion + " is outside executable range "
                            + PluginManifest.MIN_EXECUTABLE_SCHEMA_VERSION + ".."
                            + PluginManifest.CURRENT_SCHEMA_VERSION
            );
        }
        String launcherConstraint = requirements.launcherVersion();
        if (!PluginVersionConstraint.parse(launcherConstraint).matches(launcherVersion)) {
            return new PluginCompatibilityResult(
                    PluginCompatibilityStatus.UNSUPPORTED_LAUNCHER,
                    "Launcher version " + launcherVersion
                            + " does not satisfy plugin constraint " + launcherConstraint
            );
        }
        @Unmodifiable List<PluginPlatformTarget> declaredPlatforms = requirements.platforms();
        if (!declaredPlatforms.isEmpty()
                && declaredPlatforms.stream().noneMatch(platform -> platform.matches(hostPlatform))) {
            return new PluginCompatibilityResult(
                    PluginCompatibilityStatus.UNSUPPORTED_PLATFORM,
                    "Declared plugin platforms " + declaredPlatforms
                            + " do not match host " + hostPlatform.getId()
            );
        }
        String runtime = requirements.runtime();
        Optional<RuntimeProvider> runtimeProvider = runtimeProviders.find(runtime);
        if (runtimeProvider.isEmpty()) {
            return new PluginCompatibilityResult(
                    PluginCompatibilityStatus.MISSING_RUNTIME,
                    "No plugin runtime provider is registered for " + runtime
            );
        }
        RuntimeProvider provider = runtimeProvider.orElseThrow();
        int requiredAbi = requirements.abi();
        if (!provider.supportsAbi(requiredAbi)) {
            return new PluginCompatibilityResult(
                    PluginCompatibilityStatus.UNSUPPORTED_ABI,
                    "Plugin runtime " + runtime + " does not support requested ABI " + requiredAbi
                            + "; provider implements ABIs " + provider.implementedPluginAbis()
            );
        }
        return new PluginCompatibilityResult(
                PluginCompatibilityStatus.COMPATIBLE,
                "All plugin compatibility requirements are satisfied for launcher "
                        + launcherVersion + " on " + hostPlatform.getId()
        );
    }
}
