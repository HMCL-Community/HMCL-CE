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
import java.util.Optional;
import java.util.Set;

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

    /// Returns the exact host target used by this evaluator for package and Store artifact compatibility.
    ///
    /// @return configured host operating system and architecture
    public PluginPlatformTarget getHostPlatform() {
        return hostPlatform;
    }

    /// Returns the exact runtime registry used by this evaluator.
    ///
    /// Lifecycle managers use the same instance so compatibility checks, restored bindings, and payload delegation
    /// cannot observe different Provider sets.
    ///
    /// @return configured runtime Provider registry
    public RuntimeProviderRegistry getRuntimeProviders() {
        return runtimeProviders;
    }

    /// Evaluates package requirements in deterministic diagnostic-priority order.
    ///
    /// @param requirements package compatibility requirements
    /// @param launcherVersion current launcher version
    /// @return first incompatibility, or a compatible result when every dimension passes
    public PluginCompatibilityResult evaluate(
            PluginCompatibilityRequirements requirements,
            String launcherVersion) {
        return evaluate(requirements, launcherVersion, null);
    }

    /// Evaluates package requirements against one exact previously selected Provider.
    ///
    /// @param requirements package compatibility requirements
    /// @param launcherVersion current launcher version
    /// @param providerId exact bound Provider plugin ID
    /// @return first incompatibility, or a compatible result when the bound Provider satisfies every dimension
    public PluginCompatibilityResult evaluateForProvider(
            PluginCompatibilityRequirements requirements,
            String launcherVersion,
            String providerId
    ) {
        if (!PluginManifest.isCanonicalExecutableId(providerId)) {
            throw new IllegalArgumentException("Bound runtime Provider ID must be canonical: " + providerId);
        }
        return evaluate(requirements, launcherVersion, providerId);
    }

    /// Evaluates common package requirements with an optional exact Provider constraint.
    ///
    /// @param requirements package compatibility requirements
    /// @param launcherVersion current launcher version
    /// @param exactProviderId exact bound Provider ID, or `null` for ordinary candidate selection
    /// @return first incompatibility, or a compatible result
    private PluginCompatibilityResult evaluate(
            PluginCompatibilityRequirements requirements,
            String launcherVersion,
            @Nullable String exactProviderId
    ) {
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
        @Unmodifiable List<RuntimeProviderDescriptor> candidates = runtimeProviders.candidates(runtime);
        if (exactProviderId != null) {
            candidates = candidates.stream()
                    .filter(candidate -> exactProviderId.equals(candidate.providerId()))
                    .toList();
            if (candidates.isEmpty()) {
                return new PluginCompatibilityResult(
                        PluginCompatibilityStatus.MISSING_RUNTIME,
                        "Bound runtime Provider " + exactProviderId + " does not advertise " + runtime
                );
            }
        }
        @Nullable String pinnedProviderId = requirements.pinnedProviderId();
        if (pinnedProviderId != null) {
            Optional<RuntimeProviderDescriptor> pinnedDescriptor = candidates.stream()
                    .filter(candidate -> pinnedProviderId.equals(candidate.providerId()))
                    .findFirst();
            if (pinnedDescriptor.isEmpty()) {
                return new PluginCompatibilityResult(
                        PluginCompatibilityStatus.MISSING_RUNTIME,
                        "Pinned runtime provider " + pinnedProviderId + " does not advertise " + runtime
                );
            }
            candidates = List.of(pinnedDescriptor.orElseThrow());
        }
        if (candidates.isEmpty()) {
            return new PluginCompatibilityResult(
                    PluginCompatibilityStatus.MISSING_RUNTIME,
                    "No plugin runtime provider is registered for " + runtime
            );
        }
        int requiredAbi = requirements.abi();
        @Unmodifiable List<RuntimeProviderDeclaration> runtimeCapabilities = capabilities(candidates, runtime);
        @Unmodifiable List<RuntimeProviderDescriptor> abiCandidates = candidates.stream()
                .filter(candidate -> runtimeProviders.findById(candidate.providerId())
                        .map(provider -> provider.supportsAbi(runtime, requiredAbi))
                        .orElse(false))
                .toList();
        if (abiCandidates.isEmpty()) {
            String implementedAbis = runtimeCapabilities.size() == 1
                    ? "provider implements ABIs " + runtimeCapabilities.get(0).getAbis()
                    : "providers implement ABI sets " + runtimeCapabilities.stream()
                            .map(RuntimeProviderDeclaration::getAbis)
                            .toList();
            return new PluginCompatibilityResult(
                    PluginCompatibilityStatus.UNSUPPORTED_ABI,
                    "Plugin runtime " + runtime + " does not support requested ABI " + requiredAbi
                            + "; " + implementedAbis
            );
        }
        PluginExecutionMode executionMode = requirements.executionMode();
        @Unmodifiable List<RuntimeProviderDescriptor> modeCandidates = abiCandidates.stream()
                .filter(candidate -> candidate.capability(runtime).orElseThrow()
                        .getExecutionModes().contains(executionMode))
                .toList();
        if (modeCandidates.isEmpty()) {
            return new PluginCompatibilityResult(
                    PluginCompatibilityStatus.UNSUPPORTED_EXECUTION_MODE,
                    "Plugin runtime " + runtime + " does not support execution mode " + executionMode
            );
        }
        int requiredBridgeAbi = requirements.bridgeAbi();
        @Unmodifiable List<RuntimeProviderDescriptor> bridgeCandidates = modeCandidates.stream()
                .filter(candidate -> candidate.capability(runtime).orElseThrow().getBridgeAbi() == requiredBridgeAbi)
                .toList();
        if (bridgeCandidates.isEmpty()) {
            return new PluginCompatibilityResult(
                    PluginCompatibilityStatus.UNSUPPORTED_BRIDGE_ABI,
                    "Plugin runtime " + runtime + " does not support Bridge ABI " + requiredBridgeAbi
            );
        }
        @Unmodifiable Set<RuntimeFeature> requiredFeatures = requirements.requiredFeatures();
        @Unmodifiable List<RuntimeProviderDescriptor> featureCandidates = bridgeCandidates.stream()
                .filter(candidate -> candidate.capability(runtime).orElseThrow()
                        .getFeatures().containsAll(requiredFeatures))
                .toList();
        if (featureCandidates.isEmpty()) {
            return new PluginCompatibilityResult(
                    PluginCompatibilityStatus.UNSUPPORTED_RUNTIME_FEATURE,
                    "Plugin runtime " + runtime + " does not implement required features " + requiredFeatures
            );
        }
        RuntimeProviderDescriptor selected = new RuntimeProviderSelector()
                .select(requirements.runtimeRequirement(), featureCandidates)
                .orElseThrow();
        if (runtimeProviders.findById(selected.providerId()).isEmpty()) {
            return new PluginCompatibilityResult(
                    PluginCompatibilityStatus.MISSING_RUNTIME,
                    "No plugin runtime provider is registered for " + runtime
            );
        }
        return new PluginCompatibilityResult(
                PluginCompatibilityStatus.COMPATIBLE,
                "All plugin compatibility requirements are satisfied for launcher "
                        + launcherVersion + " on " + hostPlatform.getId()
        );
    }

    /// Extracts the advertised capability for one runtime from every candidate descriptor.
    ///
    /// @param candidates provider candidates known to advertise the runtime
    /// @param runtime canonical runtime identifier
    /// @return immutable capability list in candidate order
    private static @Unmodifiable List<RuntimeProviderDeclaration> capabilities(
            @Unmodifiable List<RuntimeProviderDescriptor> candidates,
            String runtime) {
        return candidates.stream()
                .map(candidate -> candidate.capability(runtime).orElseThrow())
                .toList();
    }
}
