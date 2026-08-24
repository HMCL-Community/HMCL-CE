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

import org.jackhuang.hmcl.plugin.runtime.RuntimeProviderBinding;
import org.jackhuang.hmcl.plugin.runtime.RuntimeProviderDeclaration;
import org.jackhuang.hmcl.plugin.runtime.RuntimeRequirement;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/// Builds prospective installed-plugin graphs and validates dependency and reverse-dependency constraints.
@NotNullByDefault
final class PluginDependencyPlanner {
    /// Installed package repository used to include readable packages that did not load.
    private final PluginPackageRepository packageRepository;

    /// Persisted virtual runtime edges included in reverse-dependency decisions.
    private final PluginRuntimeBindingStore runtimeBindingStore;

    /// Creates a dependency planner over one installed package repository.
    ///
    /// @param packageRepository installed package repository
    /// @param runtimeBindingStore persisted runtime Provider binding store
    PluginDependencyPlanner(
            PluginPackageRepository packageRepository,
            PluginRuntimeBindingStore runtimeBindingStore
    ) {
        this.packageRepository = packageRepository;
        this.runtimeBindingStore = runtimeBindingStore;
    }

    /// Reads manifests that will remain after pending restart-time removals complete.
    ///
    /// @param loadedPlugins currently loaded plugin containers
    /// @param pendingUninstall plugin IDs excluded from the future graph
    /// @return mutable future installed manifests indexed by plugin ID
    /// @throws IOException if installed packages cannot be enumerated
    Map<String, PluginManifest> readInstallPlanningManifests(
            List<PluginContainer> loadedPlugins,
            Set<String> pendingUninstall
    ) throws IOException {
        Map<String, PluginManifest> manifests = new LinkedHashMap<>(
                packageRepository.readInstalledManifests(loadedPlugins)
        );
        pendingUninstall.forEach(manifests::remove);
        return manifests;
    }

    /// Validates replacement closures and installed edges that point into a replacement batch.
    ///
    /// @param manifests complete prospective manifests indexed by ID
    /// @param replacementIds plugin IDs replaced by the batch
    /// @throws IOException if a dependency is missing, incompatible, or cyclic
    void validateReplacementGraph(
            Map<String, PluginManifest> manifests,
            Set<String> replacementIds
    ) throws IOException {
        validateReplacementGraph(manifests, replacementIds, runtimeBindingStore.readStrict());
    }

    /// Validates a replacement graph against the exact prospective virtual runtime edges.
    ///
    /// @param manifests complete prospective manifests indexed by ID
    /// @param replacementIds plugin IDs replaced by the batch
    /// @param runtimeBindings complete prospective runtime bindings
    /// @throws IOException if a dependency or runtime edge is missing, incompatible, or cyclic
    void validateReplacementGraph(
            Map<String, PluginManifest> manifests,
            Set<String> replacementIds,
            @Unmodifiable Map<String, RuntimeProviderBinding> runtimeBindings
    ) throws IOException {
        Set<String> validationRoots = new LinkedHashSet<>(replacementIds);
        // Provider replacements can affect bound dependents that are not themselves part of the replacement batch.
        runtimeBindings.values().stream()
                .filter(binding -> replacementIds.contains(binding.dependentPluginId())
                        || replacementIds.contains(binding.providerId()))
                .map(RuntimeProviderBinding::dependentPluginId)
                .filter(manifests::containsKey)
                .forEach(validationRoots::add);
        Set<String> visited = new HashSet<>();
        for (String pluginId : validationRoots) {
            validateDependencyClosure(pluginId, manifests, runtimeBindings, new HashSet<>(), visited);
        }
        for (PluginManifest manifest : manifests.values()) {
            if (replacementIds.contains(manifest.getId())) {
                continue;
            }
            if (manifest.getSchemaVersion() < PluginManifest.MIN_EXECUTABLE_SCHEMA_VERSION) {
                continue;
            }
            for (PluginDependency dependency : manifest.getPluginDependencies()) {
                if (!replacementIds.contains(dependency.getId())) {
                    continue;
                }
                @Nullable PluginManifest replacement = manifests.get(dependency.getId());
                if (replacement == null || !dependency.matchesVersion(replacement.getVersion())) {
                    throw new IOException("Plugin " + manifest.getId() + " requires dependency "
                            + dependency.getId() + " " + dependency.getVersion() + " but the batch provides "
                            + (replacement == null ? "nothing" : replacement.getVersion()));
                }
            }
        }
        for (RuntimeProviderBinding binding : runtimeBindings.values()) {
            if (!replacementIds.contains(binding.dependentPluginId())
                    && !replacementIds.contains(binding.providerId())) {
                continue;
            }
            @Nullable PluginManifest dependent = manifests.get(binding.dependentPluginId());
            @Nullable PluginManifest provider = manifests.get(binding.providerId());
            if (dependent == null || provider == null) {
                throw new IOException("Runtime binding " + binding.dependentPluginId()
                        + " -> " + binding.providerId() + " references a missing package");
            }
            RuntimeRequirement requirement = dependent.getRuntimeRequirement();
            boolean compatible = binding.runtime().equals(requirement.getRuntime())
                    && provider.getProvidesRuntimes().stream().anyMatch(declaration ->
                    supportsRuntimeRequirement(declaration, requirement));
            if (!compatible) {
                throw new IOException("Runtime Provider " + binding.providerId()
                        + " no longer satisfies bound dependent " + binding.dependentPluginId());
            }
        }
    }

    /// Returns whether one Provider declaration satisfies a dependent's complete runtime contract.
    ///
    /// @param declaration Provider capability declaration
    /// @param requirement dependent runtime requirement
    /// @return whether every runtime selection field is supported
    private static boolean supportsRuntimeRequirement(
            RuntimeProviderDeclaration declaration,
            RuntimeRequirement requirement
    ) {
        return declaration.getRuntime().equals(requirement.getRuntime())
                && declaration.getAbis().contains(requirement.getPluginAbi())
                && declaration.getBridgeAbi() == requirement.getBridgeAbi()
                && declaration.getExecutionModes().contains(requirement.getExecutionMode())
                && declaration.getFeatures().containsAll(requirement.getRequiredFeatures());
    }

    /// Returns installed plugins that directly require one prospective uninstall target.
    ///
    /// @param pluginId prospective uninstall target
    /// @param loadedPlugins currently loaded plugin containers
    /// @param pendingUninstall plugin IDs already excluded from the future graph
    /// @return sorted blocking dependent IDs
    /// @throws IOException if installed packages cannot be enumerated
    @Unmodifiable List<String> findBlockingDependents(
            String pluginId,
            List<PluginContainer> loadedPlugins,
            Set<String> pendingUninstall
    ) throws IOException {
        @Unmodifiable Map<String, PluginManifest> installed =
                packageRepository.readInstalledManifests(loadedPlugins);
        @Unmodifiable Map<String, RuntimeProviderBinding> runtimeBindings = runtimeBindingStore.readStrict();
        return java.util.stream.Stream.concat(
                installed.values().stream()
                .filter(manifest -> !manifest.getId().equals(pluginId))
                .filter(manifest -> !pendingUninstall.contains(manifest.getId()))
                .filter(manifest -> manifest.getSchemaVersion()
                        >= PluginManifest.MIN_EXECUTABLE_SCHEMA_VERSION)
                .filter(manifest -> manifest.getDependencies().contains(pluginId))
                .map(PluginManifest::getId),
                runtimeBindings.values().stream()
                        .filter(binding -> binding.providerId().equals(pluginId))
                        .map(RuntimeProviderBinding::dependentPluginId)
                        .filter(installed::containsKey)
                        .filter(dependentId -> !pendingUninstall.contains(dependentId))
        )
                .distinct()
                .sorted()
                .toList();
    }

    /// Returns enabled external-runtime plugins bound directly to one Runtime Provider.
    ///
    /// @param providerId Runtime Provider plugin ID
    /// @param enabledPluginIds plugin IDs whose desired lifecycle state is enabled
    /// @return sorted enabled bound dependent IDs
    /// @throws IOException if the runtime binding document cannot be read
    @Unmodifiable List<String> findEnabledRuntimeDependents(
            String providerId,
            @Unmodifiable Set<String> enabledPluginIds
    ) throws IOException {
        return runtimeBindingStore.readStrict().values().stream()
                .filter(binding -> binding.providerId().equals(providerId))
                .map(RuntimeProviderBinding::dependentPluginId)
                .filter(enabledPluginIds::contains)
                .distinct()
                .sorted()
                .toList();
    }

    /// Validates one complete installed dependency closure.
    ///
    /// @param pluginId closure root
    /// @param manifests installed manifests indexed by ID
    /// @param runtimeBindings complete prospective virtual runtime edges
    /// @param visiting IDs on the current traversal stack
    /// @param visited IDs already validated
    /// @throws IOException if the dependency closure is invalid
    private static void validateDependencyClosure(
            String pluginId,
            Map<String, PluginManifest> manifests,
            @Unmodifiable Map<String, RuntimeProviderBinding> runtimeBindings,
            Set<String> visiting,
            Set<String> visited
    ) throws IOException {
        if (visited.contains(pluginId)) {
            return;
        }
        if (!visiting.add(pluginId)) {
            throw new IOException("Cyclic plugin dependency detected at " + pluginId);
        }

        PluginManifest manifest = Objects.requireNonNull(manifests.get(pluginId));
        for (PluginDependency dependency : manifest.getPluginDependencies()) {
            @Nullable PluginManifest installedDependency = manifests.get(dependency.getId());
            if (installedDependency == null) {
                throw new IOException("Plugin " + pluginId + " requires missing dependency "
                        + dependency.getId());
            }
            if (installedDependency.getSchemaVersion() < PluginManifest.MIN_EXECUTABLE_SCHEMA_VERSION) {
                throw new IOException("Plugin " + pluginId + " requires legacy dependency "
                        + dependency.getId() + " whose API version cannot execute");
            }
            if (!dependency.matchesVersion(installedDependency.getVersion())) {
                throw new IOException("Plugin " + pluginId + " requires dependency " + dependency.getId()
                        + " " + dependency.getVersion() + " but found " + installedDependency.getVersion());
            }
            validateDependencyClosure(dependency.getId(), manifests, runtimeBindings, visiting, visited);
        }
        @Nullable RuntimeProviderBinding runtimeBinding = runtimeBindings.get(pluginId);
        if (runtimeBinding != null) {
            if (!manifests.containsKey(runtimeBinding.providerId())) {
                throw new IOException("Plugin " + pluginId + " requires missing runtime Provider "
                        + runtimeBinding.providerId());
            }
            validateDependencyClosure(runtimeBinding.providerId(), manifests, runtimeBindings, visiting, visited);
        }
        visiting.remove(pluginId);
        visited.add(pluginId);
    }
}
