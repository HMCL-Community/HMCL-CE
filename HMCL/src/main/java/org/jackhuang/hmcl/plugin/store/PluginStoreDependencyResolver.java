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
package org.jackhuang.hmcl.plugin.store;

import org.jackhuang.hmcl.plugin.PluginArtifactIdentity;
import org.jackhuang.hmcl.plugin.PluginDependency;
import org.jackhuang.hmcl.plugin.PluginManifest;
import org.jackhuang.hmcl.plugin.trust.PluginTrustResult;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/// Resolves dependency installation plans exclusively from the selected source-priority catalog winners.
@NotNullByDefault
public final class PluginStoreDependencyResolver {
    /// Source-priority catalog winners indexed by plugin ID.
    private final @Unmodifiable Map<String, PluginStoreItem> winningItems;

    /// Creates a resolver bound to one immutable aggregate catalog snapshot.
    ///
    /// Conflict candidates are deliberately not accepted: once a source wins a plugin ID, its metadata is the only
    /// remote metadata eligible for dependency planning.
    ///
    /// @param winningItems selected catalog winners indexed by plugin ID
    public PluginStoreDependencyResolver(@Unmodifiable Map<String, PluginStoreItem> winningItems) {
        this.winningItems = Map.copyOf(winningItems);
    }

    /// Resolves a requested version and all transitive dependencies using one complete exact-artifact snapshot.
    ///
    /// Every installed manifest must have one exact prior identity, including disabled or unauthorized artifacts that
    /// will be updated rather than reused. Reusable artifacts must be an exact subset of that same snapshot. Selected
    /// identities are retained so final publication can compare both replacement prior state and reused dependencies.
    ///
    /// @param pluginId requested root plugin ID
    /// @param requestedVersion exact requested remote version
    /// @param installedManifests installed plugin manifests indexed by ID
    /// @param installedArtifactIdentities exact current artifact for every installed manifest
    /// @param reusableInstalledArtifacts exact installed artifacts approved for reuse during planning
    /// @return immutable dependency-first install plan
    /// @throws IOException if metadata is unavailable or the dependency graph cannot be satisfied
    public PluginInstallPlan resolveInstallPlan(
            String pluginId,
            PluginStoreManifest.PluginVersionEntry requestedVersion,
            @Unmodifiable Map<String, PluginManifest> installedManifests,
            @Unmodifiable Map<String, PluginArtifactIdentity> installedArtifactIdentities,
            @Unmodifiable Map<String, PluginArtifactIdentity> reusableInstalledArtifacts
    ) throws IOException {
        PluginStoreItem rootItem = requireWinningItem(pluginId);
        PluginStoreManifest rootManifest = requireManifest(rootItem, pluginId);
        PluginStoreManifest.PluginVersionEntry rootVersion = requirePublishedVersion(
                pluginId,
                rootManifest,
                requestedVersion
        );
        rootItem.getSourceManager().validateCompatibility(rootVersion);

        @Unmodifiable Map<String, PluginManifest> installed = Map.copyOf(installedManifests);
        @Unmodifiable Map<String, PluginArtifactIdentity> installedArtifacts =
                Map.copyOf(installedArtifactIdentities);
        @Unmodifiable Map<String, PluginArtifactIdentity> reusableInstalled =
                Map.copyOf(reusableInstalledArtifacts);
        validateArtifactSnapshots(installed, installedArtifacts, reusableInstalled);

        Map<String, PluginInstallPlan.Entry> selected = new LinkedHashMap<>();
        selected.put(pluginId, createRemotePlanEntry(pluginId, rootItem, rootVersion, installed));
        Map<String, PluginInstallPlan.Entry> solution = new LinkedHashMap<>();
        List<IOException> failures = new ArrayList<>();
        if (!solvePlanSelections(
                pluginId,
                installed,
                reusableInstalled.keySet(),
                selected,
                solution,
                failures
        )) {
            if (!failures.isEmpty()) {
                throw failures.get(failures.size() - 1);
            }
            throw new IOException("Plugin dependency graph cannot be satisfied for " + pluginId);
        }

        validateReverseDependents(installed, solution);
        Map<String, PluginArtifactIdentity> selectedReusableArtifacts = new LinkedHashMap<>();
        Map<String, Optional<PluginArtifactIdentity>> expectedPriorArtifacts = new LinkedHashMap<>();
        for (PluginInstallPlan.Entry entry : solution.values()) {
            if (entry.getAction() == PluginInstallPlan.Action.REUSE) {
                @Nullable PluginArtifactIdentity identity = reusableInstalled.get(entry.getPluginId());
                if (identity == null) {
                    throw new IllegalStateException("Selected reusable entry has no exact artifact identity: "
                            + entry.getPluginId());
                }
                selectedReusableArtifacts.put(entry.getPluginId(), identity);
            } else if (entry.getAction() == PluginInstallPlan.Action.UPDATE) {
                @Nullable PluginArtifactIdentity priorIdentity = installedArtifacts.get(entry.getPluginId());
                if (priorIdentity == null) {
                    throw new IllegalStateException("Selected update has no exact prior artifact identity: "
                            + entry.getPluginId());
                }
                expectedPriorArtifacts.put(entry.getPluginId(), Optional.of(priorIdentity));
            } else {
                expectedPriorArtifacts.put(entry.getPluginId(), Optional.empty());
            }
        }
        return new PluginInstallPlan(
                pluginId,
                buildDependencyOrder(pluginId, solution),
                Map.copyOf(selectedReusableArtifacts),
                Map.copyOf(expectedPriorArtifacts)
        );
    }

    /// Validates that installed and reusable snapshots describe the same exact artifacts.
    ///
    /// @param installed installed manifests
    /// @param installedArtifacts exact current artifact identities
    /// @param reusableInstalled exact artifacts approved for reuse
    private static void validateArtifactSnapshots(
            @Unmodifiable Map<String, PluginManifest> installed,
            @Unmodifiable Map<String, PluginArtifactIdentity> installedArtifacts,
            @Unmodifiable Map<String, PluginArtifactIdentity> reusableInstalled
    ) {
        if (!installed.keySet().equals(installedArtifacts.keySet())) {
            throw new IllegalArgumentException("Every installed manifest must have one exact prior artifact identity");
        }
        if (!installedArtifacts.keySet().containsAll(reusableInstalled.keySet())) {
            throw new IllegalArgumentException("Reusable artifacts must belong to the installed manifest snapshot");
        }
        for (Map.Entry<String, PluginArtifactIdentity> entry : installedArtifacts.entrySet()) {
            @Nullable PluginManifest installedManifest = installed.get(entry.getKey());
            PluginArtifactIdentity identity = entry.getValue();
            if (!entry.getKey().equals(identity.getPluginId())
                    || installedManifest == null
                    || !installedManifest.getVersion().equals(identity.getVersion())) {
                throw new IllegalArgumentException("Reusable artifact identity does not match the installed snapshot: "
                        + entry.getKey());
            }
        }
        for (Map.Entry<String, PluginArtifactIdentity> entry : reusableInstalled.entrySet()) {
            if (!entry.getValue().equals(installedArtifacts.get(entry.getKey()))) {
                throw new IllegalArgumentException("Reusable artifact differs from the installed snapshot: "
                        + entry.getKey());
            }
        }
    }

    /// Searches the complete dependency graph with backtracking so constraints discovered by later siblings can
    /// revise an earlier version choice.
    ///
    /// @param rootPluginId requested root plugin ID
    /// @param installedManifests installed manifests
    /// @param reusableInstalledPluginIds exact installed artifacts approved for reuse
    /// @param selected mutable candidate assignment for the current branch
    /// @param solution successful assignment copied when the graph is complete
    /// @param failures branch diagnostics retained for the final error
    /// @return whether a complete, acyclic assignment was found
    private boolean solvePlanSelections(
            String rootPluginId,
            @Unmodifiable Map<String, PluginManifest> installedManifests,
            @Unmodifiable Set<String> reusableInstalledPluginIds,
            Map<String, PluginInstallPlan.Entry> selected,
            Map<String, PluginInstallPlan.Entry> solution,
            List<IOException> failures
    ) {
        Map<String, List<PluginDependency>> requirements = collectRequirements(
                rootPluginId,
                selected,
                installedManifests
        );
        for (Map.Entry<String, PluginInstallPlan.Entry> assignment : selected.entrySet()) {
            @Nullable List<PluginDependency> constraints = requirements.get(assignment.getKey());
            if (constraints != null && !matchesAll(assignment.getValue().getVersion(), constraints)) {
                failures.add(new IOException("Conflicting dependency constraints for plugin " + assignment.getKey()
                        + ": selected " + assignment.getValue().getVersion() + " does not satisfy "
                        + formatConstraints(constraints)));
                return false;
            }
        }

        @Nullable String unresolvedPluginId = requirements.keySet().stream()
                .filter(candidate -> !selected.containsKey(candidate))
                .findFirst()
                .orElse(null);
        if (unresolvedPluginId == null) {
            try {
                buildDependencyOrder(rootPluginId, selected);
                solution.clear();
                solution.putAll(selected);
                return true;
            } catch (IOException exception) {
                failures.add(exception);
                return false;
            }
        }

        @Unmodifiable List<PluginInstallPlan.Entry> candidates;
        try {
            candidates = getCandidateEntries(
                    unresolvedPluginId,
                    requirements.getOrDefault(unresolvedPluginId, List.of()),
                    installedManifests,
                    reusableInstalledPluginIds
            );
        } catch (IOException exception) {
            failures.add(exception);
            return false;
        }
        if (candidates.isEmpty()) {
            failures.add(new IOException("No compatible version of dependency " + unresolvedPluginId
                    + " satisfies " + formatConstraints(requirements.getOrDefault(unresolvedPluginId, List.of()))));
            return false;
        }

        for (PluginInstallPlan.Entry candidate : candidates) {
            selected.put(unresolvedPluginId, candidate);
            if (solvePlanSelections(
                    rootPluginId,
                    installedManifests,
                    reusableInstalledPluginIds,
                    selected,
                    solution,
                    failures
            )) {
                return true;
            }
            selected.remove(unresolvedPluginId);
        }
        return false;
    }

    /// Collects dependency constraints contributed by all candidates selected in the current search branch.
    ///
    /// @param rootPluginId requested root plugin ID
    /// @param selected current candidate assignment
    /// @param installedManifests installed manifests whose out-of-plan reverse constraints must remain valid
    /// @return mutable insertion-ordered constraints indexed by dependency ID
    private static Map<String, List<PluginDependency>> collectRequirements(
            String rootPluginId,
            Map<String, PluginInstallPlan.Entry> selected,
            @Unmodifiable Map<String, PluginManifest> installedManifests
    ) {
        Map<String, List<PluginDependency>> requirements = new LinkedHashMap<>();
        requirements.put(rootPluginId, new ArrayList<>());
        for (PluginInstallPlan.Entry entry : selected.values()) {
            for (PluginDependency dependency : entry.getDependencies()) {
                requirements.computeIfAbsent(dependency.getId(), ignored -> new ArrayList<>()).add(dependency);
            }
        }

        // Only executable API-v4 plugins can constrain the active dependency graph.
        for (PluginManifest installed : installedManifests.values()) {
            if (installed.getSchemaVersion() < PluginManifest.MIN_EXECUTABLE_SCHEMA_VERSION
                    || requirements.containsKey(installed.getId())) {
                continue;
            }
            for (PluginDependency dependency : installed.getPluginDependencies()) {
                @Nullable List<PluginDependency> dependencyRequirements = requirements.get(dependency.getId());
                if (dependencyRequirements != null) {
                    dependencyRequirements.add(dependency);
                }
            }
        }
        return requirements;
    }

    /// Builds candidate versions in preference order for one dependency under all currently known constraints.
    ///
    /// @param pluginId dependency plugin ID
    /// @param requirements all incoming version requirements
    /// @param installedManifests installed manifests
    /// @param reusableInstalledPluginIds exact installed artifacts approved for reuse
    /// @return immutable candidate list, with an approved compatible installed package first
    /// @throws IOException if remote metadata is required but unavailable
    private @Unmodifiable List<PluginInstallPlan.Entry> getCandidateEntries(
            String pluginId,
            @Unmodifiable List<PluginDependency> requirements,
            @Unmodifiable Map<String, PluginManifest> installedManifests,
            @Unmodifiable Set<String> reusableInstalledPluginIds
    ) throws IOException {
        List<PluginInstallPlan.Entry> candidates = new ArrayList<>();
        @Nullable PluginManifest installed = installedManifests.get(pluginId);
        boolean installedVersionMatches = installed != null && matchesAll(installed.getVersion(), requirements);
        boolean installedArtifactMayBeReused = installed != null
                && installed.getSchemaVersion() >= PluginManifest.MIN_EXECUTABLE_SCHEMA_VERSION
                && installedVersionMatches
                && reusableInstalledPluginIds.contains(pluginId);
        if (installedArtifactMayBeReused) {
            candidates.add(new PluginInstallPlan.Entry(
                    pluginId,
                    installed.getName(),
                    installed.getVersion(),
                    PluginInstallPlan.Action.REUSE,
                    null,
                    null,
                    installed,
                    null,
                    null,
                    null,
                    null
            ));
        }

        @Nullable PluginStoreItem item = winningItems.get(pluginId);
        if (item == null) {
            if (candidates.isEmpty()) {
                if (installedVersionMatches) {
                    throw new IOException("Installed dependency " + pluginId
                            + " cannot be reused without complete artifact-bound required grants, and no enabled "
                            + "source provides a package for a fresh permission review");
                }
                throw new IOException("Missing plugin dependency in enabled sources: " + pluginId);
            }
            return List.copyOf(candidates);
        }

        PluginStoreItem winningItem = requireWinningItem(pluginId);
        PluginStoreManifest manifest = requireManifest(winningItem, pluginId);
        for (PluginStoreManifest.PluginVersionEntry version : winningItem.getSourceManager().getCompatibleVersions(manifest)) {
            if (matchesAll(version.getVersion(), requirements)) {
                candidates.add(createRemotePlanEntry(pluginId, winningItem, version, installedManifests));
            }
        }
        if (candidates.isEmpty() && installedVersionMatches) {
            throw new IOException("Installed dependency " + pluginId
                    + " cannot be reused without complete artifact-bound required grants, and no compatible remote "
                    + "package is available from " + getSourceDisplayName(winningItem)
                    + " for a fresh permission review");
        }
        if (candidates.isEmpty()) {
            throw new IOException("No compatible version of dependency " + pluginId + " from "
                    + getSourceDisplayName(winningItem) + " satisfies " + formatConstraints(requirements));
        }
        return List.copyOf(candidates);
    }

    /// Looks up one selected source-priority winner and rejects absent or incomplete catalog metadata.
    ///
    /// @param pluginId requested plugin ID
    /// @return complete winning catalog item
    /// @throws IOException if no enabled source publishes the ID or its manifest is unavailable
    private PluginStoreItem requireWinningItem(String pluginId) throws IOException {
        @Nullable PluginStoreItem item = winningItems.get(pluginId);
        if (item == null) {
            throw new IOException("Plugin is not published by an enabled source: " + pluginId);
        }
        requireManifest(item, pluginId);
        return item;
    }

    /// Returns a winning item's resolved manifest or reports its source-specific failure.
    ///
    /// @param item selected winner
    /// @param pluginId requested plugin ID
    /// @return resolved manifest
    /// @throws IOException if the manifest could not be loaded
    private static PluginStoreManifest requireManifest(PluginStoreItem item, String pluginId) throws IOException {
        @Nullable PluginStoreManifest manifest = item.getManifest();
        if (manifest == null) {
            throw new IOException("Plugin manifest is unavailable from "
                    + getSourceDisplayName(item) + ": " + pluginId);
        }
        return manifest;
    }

    /// Returns a credential-safe source label for dependency diagnostics and install plans.
    ///
    /// @param item item bound to one source
    /// @return human-readable source name
    private static String getSourceDisplayName(PluginStoreItem item) {
        return PluginSourceLabels.displayName(item.getSource(), item.getRegistry().getName());
    }

    /// Creates a downloadable plan entry for an exact remote version.
    ///
    /// @param pluginId plugin ID
    /// @param item selected winning catalog item
    /// @param version exact remote version metadata
    /// @param installedManifests installed manifests
    /// @return remote install or update entry
    private static PluginInstallPlan.Entry createRemotePlanEntry(
            String pluginId,
            PluginStoreItem item,
            PluginStoreManifest.PluginVersionEntry version,
            @Unmodifiable Map<String, PluginManifest> installedManifests
    ) throws IOException {
        PluginTrustResult selectedTrust = item.getSourceManager().refreshVersionTrust(item, version);
        if (!selectedTrust.canInstall()) {
            throw new IOException("Plugin trust verification rejected " + pluginId + ": " + selectedTrust.detail());
        }
        @Nullable PluginManifest installed = installedManifests.get(pluginId);
        PluginStoreRegistry.PluginStoreEntry storeEntry = item.getEntry();
        return new PluginInstallPlan.Entry(
                pluginId,
                storeEntry.getName(),
                version.getVersion(),
                installed == null ? PluginInstallPlan.Action.INSTALL : PluginInstallPlan.Action.UPDATE,
                storeEntry,
                version,
                installed,
                item.getSource().getId(),
                getSourceDisplayName(item),
                PluginSourceProvenance.from(item, version),
                item.getSourceManager()
        );
    }

    /// Returns whether a version satisfies every incoming dependency requirement.
    ///
    /// @param version candidate plugin version
    /// @param requirements incoming requirements
    /// @return whether all requirements match
    private static boolean matchesAll(String version, @Unmodifiable List<PluginDependency> requirements) {
        return requirements.stream().allMatch(requirement -> requirement.matchesVersion(version));
    }

    /// Formats incoming dependency constraints for deterministic diagnostics.
    ///
    /// @param requirements incoming requirements
    /// @return comma-separated constraint expressions
    private static String formatConstraints(@Unmodifiable List<PluginDependency> requirements) {
        if (requirements.isEmpty()) {
            return "*";
        }
        return requirements.stream().map(PluginDependency::getVersion).distinct().reduce((left, right) -> left
                + ", " + right).orElse("*");
    }

    /// Produces a dependency-first order and rejects cycles in an otherwise complete assignment.
    ///
    /// @param rootPluginId requested root plugin ID
    /// @param selected complete selected entries indexed by ID
    /// @return immutable dependency-first plan order
    /// @throws IOException if the selected dependency graph contains a cycle or incomplete edge
    private static @Unmodifiable List<PluginInstallPlan.Entry> buildDependencyOrder(
            String rootPluginId,
            Map<String, PluginInstallPlan.Entry> selected
    ) throws IOException {
        List<PluginInstallPlan.Entry> order = new ArrayList<>();
        Set<String> visiting = new HashSet<>();
        Set<String> visited = new HashSet<>();
        appendDependencyOrder(rootPluginId, selected, visiting, visited, order);
        return List.copyOf(order);
    }

    /// Appends one selected entry after recursively appending all of its dependencies.
    ///
    /// @param pluginId selected plugin ID
    /// @param selected complete selected entries indexed by ID
    /// @param visiting current recursion stack
    /// @param visited completed plugin IDs
    /// @param order dependency-first output
    /// @throws IOException if a cycle or missing selected dependency is found
    private static void appendDependencyOrder(
            String pluginId,
            Map<String, PluginInstallPlan.Entry> selected,
            Set<String> visiting,
            Set<String> visited,
            List<PluginInstallPlan.Entry> order
    ) throws IOException {
        if (visited.contains(pluginId)) {
            return;
        }
        if (!visiting.add(pluginId)) {
            throw new IOException("Cyclic plugin dependency detected at " + pluginId);
        }
        @Nullable PluginInstallPlan.Entry entry = selected.get(pluginId);
        if (entry == null) {
            throw new IOException("Dependency plan has no selected version for " + pluginId);
        }
        for (PluginDependency dependency : entry.getDependencies()) {
            appendDependencyOrder(dependency.getId(), selected, visiting, visited, order);
        }
        visiting.remove(pluginId);
        visited.add(pluginId);
        order.add(entry);
    }

    /// Verifies that an exact requested version belongs to the resolved repository manifest.
    ///
    /// @param pluginId plugin ID
    /// @param manifest repository manifest
    /// @param requestedVersion requested version metadata
    /// @return canonical version entry from the manifest
    /// @throws IOException if the requested version is not published
    private static PluginStoreManifest.PluginVersionEntry requirePublishedVersion(
            String pluginId,
            PluginStoreManifest manifest,
            PluginStoreManifest.PluginVersionEntry requestedVersion
    ) throws IOException {
        @Nullable PluginStoreManifest.PluginVersionEntry published = manifest.getVersion(requestedVersion.getVersion());
        if (published == null) {
            throw new IOException("Plugin " + pluginId + " does not publish version " + requestedVersion.getVersion());
        }
        return published;
    }

    /// Ensures selected dependency updates do not break installed plugins outside the plan.
    ///
    /// @param installedManifests installed manifests
    /// @param resolved resolved plan entries
    /// @throws IOException if an installed reverse dependent would become invalid
    private static void validateReverseDependents(
            @Unmodifiable Map<String, PluginManifest> installedManifests,
            Map<String, PluginInstallPlan.Entry> resolved
    ) throws IOException {
        Map<String, String> effectiveVersions = new HashMap<>();
        installedManifests.forEach((id, manifest) -> effectiveVersions.put(id, manifest.getVersion()));
        resolved.forEach((id, entry) -> effectiveVersions.put(id, entry.getVersion()));

        for (PluginManifest installed : installedManifests.values()) {
            if (installed.getSchemaVersion() < PluginManifest.MIN_EXECUTABLE_SCHEMA_VERSION) {
                continue;
            }
            if (resolved.containsKey(installed.getId())
                    && resolved.get(installed.getId()).getAction() != PluginInstallPlan.Action.REUSE) {
                continue;
            }
            for (PluginDependency dependency : installed.getPluginDependencies()) {
                @Nullable String effectiveVersion = effectiveVersions.get(dependency.getId());
                if (effectiveVersion == null || !dependency.matchesVersion(effectiveVersion)) {
                    throw new IOException("Installing this plan would break " + installed.getId()
                            + ": dependency " + dependency.getId() + " " + dependency.getVersion()
                            + " would resolve to " + (effectiveVersion == null ? "missing" : effectiveVersion));
                }
            }
        }
    }
}
