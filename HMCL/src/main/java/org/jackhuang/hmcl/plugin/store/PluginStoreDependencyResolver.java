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

import org.jackhuang.hmcl.Metadata;
import org.jackhuang.hmcl.plugin.PluginArtifactIdentity;
import org.jackhuang.hmcl.plugin.PluginDependency;
import org.jackhuang.hmcl.plugin.PluginManifest;
import org.jackhuang.hmcl.plugin.runtime.PluginPlatformTarget;
import org.jackhuang.hmcl.plugin.runtime.PluginRuntimeTypes;
import org.jackhuang.hmcl.plugin.runtime.RuntimeProviderBinding;
import org.jackhuang.hmcl.plugin.runtime.RuntimeProviderDescriptor;
import org.jackhuang.hmcl.plugin.runtime.RuntimeProviderSelector;
import org.jackhuang.hmcl.plugin.runtime.RuntimeRequirement;
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

    /// Shared deterministic runtime Provider compatibility and ranking policy.
    private final RuntimeProviderSelector runtimeProviderSelector = new RuntimeProviderSelector();

    /// Source priority captured from the complete configured source order.
    private final @Unmodifiable Map<String, Integer> sourcePriorities;

    /// Creates a resolver bound to one immutable aggregate catalog snapshot.
    ///
    /// Conflict candidates are deliberately not accepted: once a source wins a plugin ID, its metadata is the only
    /// remote metadata eligible for dependency planning.
    ///
    /// @param winningItems selected catalog winners indexed by plugin ID
    /// @param configuredSources complete configured sources in priority order
    public PluginStoreDependencyResolver(
            @Unmodifiable Map<String, PluginStoreItem> winningItems,
            @Unmodifiable List<PluginSource> configuredSources
    ) {
        this.winningItems = Map.copyOf(winningItems);
        Map<String, Integer> priorities = new LinkedHashMap<>();
        for (PluginSource source : configuredSources) {
            if (priorities.putIfAbsent(source.getId(), priorities.size()) != null) {
                throw new IllegalArgumentException("Duplicate configured plugin source ID: " + source.getId());
            }
        }
        for (PluginStoreItem item : winningItems.values()) {
            if (!priorities.containsKey(item.getSource().getId())) {
                throw new IllegalArgumentException("Catalog winner source is absent from the configured snapshot: "
                        + item.getSource().getId());
            }
        }
        sourcePriorities = Map.copyOf(priorities);
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
        return resolveInstallPlan(
                pluginId,
                requestedVersion,
                installedManifests,
                installedArtifactIdentities,
                reusableInstalledArtifacts,
                Map.of()
        );
    }

    /// Resolves a requested version with explicit enabled-reusable and disabled-activatable artifact states.
    ///
    /// @param pluginId requested root plugin ID
    /// @param requestedVersion exact requested remote version
    /// @param installedManifests installed plugin manifests indexed by ID
    /// @param installedArtifactIdentities exact current artifact for every installed manifest
    /// @param reusableInstalledArtifacts exact enabled artifacts approved for reuse
    /// @param activatableInstalledArtifacts exact disabled artifacts approved for activation
    /// @return immutable dependency-first install plan
    /// @throws IOException if metadata is unavailable or the dependency graph cannot be satisfied
    public PluginInstallPlan resolveInstallPlan(
            String pluginId,
            PluginStoreManifest.PluginVersionEntry requestedVersion,
            @Unmodifiable Map<String, PluginManifest> installedManifests,
            @Unmodifiable Map<String, PluginArtifactIdentity> installedArtifactIdentities,
            @Unmodifiable Map<String, PluginArtifactIdentity> reusableInstalledArtifacts,
            @Unmodifiable Map<String, PluginArtifactIdentity> activatableInstalledArtifacts
    ) throws IOException {
        PluginStoreItem rootItem = requireWinningItem(pluginId);
        PluginStoreManifest rootManifest = requireManifest(rootItem, pluginId);
        PluginStoreManifest.PluginVersionEntry rootVersion = requirePublishedVersion(
                pluginId,
                rootManifest,
                requestedVersion
        );
        validateResolvableCompatibility(rootItem, rootVersion);

        @Unmodifiable Map<String, PluginManifest> installed = Map.copyOf(installedManifests);
        @Unmodifiable Map<String, PluginArtifactIdentity> installedArtifacts =
                Map.copyOf(installedArtifactIdentities);
        @Unmodifiable Map<String, PluginArtifactIdentity> reusableInstalled =
                Map.copyOf(reusableInstalledArtifacts);
        @Unmodifiable Map<String, PluginArtifactIdentity> activatableInstalled =
                Map.copyOf(activatableInstalledArtifacts);
        validateArtifactSnapshots(installed, installedArtifacts, reusableInstalled, activatableInstalled);

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

        Map<String, RuntimeProviderBinding> runtimeBindings = new LinkedHashMap<>();
        resolveRuntimeProviders(
                pluginId,
                installed,
                reusableInstalled.keySet(),
                activatableInstalled.keySet(),
                solution,
                runtimeBindings,
                failures
        );
        validateReverseDependents(installed, solution);
        Map<String, PluginArtifactIdentity> selectedReusableArtifacts = new LinkedHashMap<>();
        Map<String, Optional<PluginArtifactIdentity>> expectedPriorArtifacts = new LinkedHashMap<>();
        for (PluginInstallPlan.Entry entry : solution.values()) {
            if (!entry.requiresDownload()) {
                @Nullable PluginArtifactIdentity identity = entry.getAction() == PluginInstallPlan.Action.ENABLE
                        ? activatableInstalled.get(entry.getPluginId())
                        : reusableInstalled.get(entry.getPluginId());
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
                buildDependencyOrder(pluginId, solution, runtimeBindings),
                Map.copyOf(selectedReusableArtifacts),
                Map.copyOf(expectedPriorArtifacts),
                Map.copyOf(runtimeBindings)
        );
    }

    /// Adds separate virtual runtime edges and their selected concrete Provider package entries.
    ///
    /// @param rootPluginId requested root plugin ID
    /// @param installedManifests installed manifest snapshot
    /// @param reusableInstalledPluginIds installed artifacts eligible for exact reuse
    /// @param activatableInstalledPluginIds installed artifacts eligible for atomic enablement
    /// @param solution mutable complete concrete package selection
    /// @param runtimeBindings mutable virtual bindings indexed by dependent ID
    /// @param failures solver diagnostics
    /// @throws IOException if a runtime has no compatible Provider or adds an unsatisfied concrete graph
    private void resolveRuntimeProviders(
            String rootPluginId,
            @Unmodifiable Map<String, PluginManifest> installedManifests,
            @Unmodifiable Set<String> reusableInstalledPluginIds,
            @Unmodifiable Set<String> activatableInstalledPluginIds,
            Map<String, PluginInstallPlan.Entry> solution,
            Map<String, RuntimeProviderBinding> runtimeBindings,
            List<IOException> failures
    ) throws IOException {
        List<IOException> runtimeFailures = new ArrayList<>();
        if (!solveRuntimeProviderSelections(
                rootPluginId,
                installedManifests,
                reusableInstalledPluginIds,
                activatableInstalledPluginIds,
                solution,
                runtimeBindings,
                runtimeFailures
        )) {
            failures.addAll(runtimeFailures);
            throw runtimeFailures.isEmpty()
                    ? new IOException("Runtime Provider dependency graph cannot be satisfied")
                    : runtimeFailures.get(runtimeFailures.size() - 1);
        }
    }

    /// Searches ranked Provider candidates without publishing a partial concrete selection or virtual binding.
    ///
    /// Each candidate branch resolves the Provider's complete concrete closure, recursively resolves any virtual
    /// requirements introduced by that closure, and validates the combined graph before committing its snapshots.
    ///
    /// @param rootPluginId requested root plugin ID
    /// @param installedManifests installed manifest snapshot
    /// @param reusableInstalledPluginIds installed artifacts eligible for exact reuse
    /// @param activatableInstalledPluginIds installed artifacts eligible for atomic enablement
    /// @param solution mutable concrete selection committed only when a complete branch succeeds
    /// @param runtimeBindings mutable virtual bindings committed only when a complete branch succeeds
    /// @param failures branch diagnostics retained for the final error
    /// @return whether a complete concrete and virtual graph was found
    private boolean solveRuntimeProviderSelections(
            String rootPluginId,
            @Unmodifiable Map<String, PluginManifest> installedManifests,
            @Unmodifiable Set<String> reusableInstalledPluginIds,
            @Unmodifiable Set<String> activatableInstalledPluginIds,
            Map<String, PluginInstallPlan.Entry> solution,
            Map<String, RuntimeProviderBinding> runtimeBindings,
            List<IOException> failures
    ) {
        @Nullable PluginInstallPlan.Entry dependent = solution.values().stream()
                .filter(entry -> !entry.isRuntimeProvider())
                .filter(entry -> !PluginRuntimeTypes.JAVA.equals(entry.getRuntimeRequirement().getRuntime()))
                .filter(entry -> !runtimeBindings.containsKey(entry.getPluginId()))
                .findFirst()
                .orElse(null);
        if (dependent == null) {
            try {
                buildDependencyOrder(rootPluginId, solution, runtimeBindings);
                return true;
            } catch (IOException exception) {
                failures.add(exception);
                return false;
            }
        }

        RuntimeRequirement requirement = dependent.getRuntimeRequirement();
        @Unmodifiable List<ProviderCandidate> candidates;
        try {
            candidates = getRuntimeProviderCandidates(
                    requirement,
                    installedManifests,
                    reusableInstalledPluginIds,
                    activatableInstalledPluginIds,
                    solution
            );
        } catch (IOException exception) {
            failures.add(exception);
            return false;
        }

        for (ProviderCandidate provider : candidates) {
            // A rejected branch must not leak its package choices or bindings into the next ranked candidate.
            Map<String, PluginInstallPlan.Entry> candidateSelection = new LinkedHashMap<>(solution);
            @Nullable PluginInstallPlan.Entry existing = candidateSelection.get(provider.entry.getPluginId());
            if (existing != null && (!existing.getVersion().equals(provider.entry.getVersion())
                    || existing.getAction() != provider.entry.getAction())) {
                failures.add(new IOException("Runtime Provider selection conflicts with package selection for "
                        + provider.entry.getPluginId()));
                continue;
            }
            candidateSelection.putIfAbsent(provider.entry.getPluginId(), provider.entry);
            Map<String, RuntimeProviderBinding> candidateBindings = new LinkedHashMap<>(runtimeBindings);
            candidateBindings.put(dependent.getPluginId(), new RuntimeProviderBinding(
                    dependent.getPluginId(), provider.entry.getPluginId(), requirement.getRuntime()));

            Map<String, PluginInstallPlan.Entry> expanded = new LinkedHashMap<>();
            List<IOException> candidateFailures = new ArrayList<>();
            if (solvePlanSelections(
                    rootPluginId,
                    installedManifests,
                    reusableInstalledPluginIds,
                    candidateSelection,
                    expanded,
                    candidateFailures
            ) && solveRuntimeProviderSelections(
                    rootPluginId,
                    installedManifests,
                    reusableInstalledPluginIds,
                    activatableInstalledPluginIds,
                    expanded,
                    candidateBindings,
                    candidateFailures
            )) {
                solution.clear();
                solution.putAll(expanded);
                runtimeBindings.clear();
                runtimeBindings.putAll(candidateBindings);
                return true;
            }
            if (candidateFailures.isEmpty()) {
                candidateFailures.add(new IOException("Runtime Provider dependency graph cannot be satisfied for "
                        + provider.entry.getPluginId()));
            }
            failures.addAll(candidateFailures);
        }
        return false;
    }

    /// Returns all compatible installed or remote Providers in deterministic selection order.
    ///
    /// @param requirement dependent runtime requirement
    /// @param installedManifests installed manifest snapshot
    /// @param reusableInstalledPluginIds installed artifacts eligible for exact reuse
    /// @param activatableInstalledPluginIds installed artifacts eligible for atomic enablement
    /// @param selected current package selection
    /// @return immutable ordered Provider candidates
    /// @throws IOException if no Provider satisfies the requirement
    private @Unmodifiable List<ProviderCandidate> getRuntimeProviderCandidates(
            RuntimeRequirement requirement,
            @Unmodifiable Map<String, PluginManifest> installedManifests,
            @Unmodifiable Set<String> reusableInstalledPluginIds,
            @Unmodifiable Set<String> activatableInstalledPluginIds,
            Map<String, PluginInstallPlan.Entry> selected
    ) throws IOException {
        List<ProviderCandidate> candidates = new ArrayList<>();
        for (PluginManifest manifest : installedManifests.values()) {
            if (manifest.getProvidesRuntimes().isEmpty()) {
                continue;
            }
            boolean enabled = reusableInstalledPluginIds.contains(manifest.getId());
            boolean activatable = activatableInstalledPluginIds.contains(manifest.getId());
            if (!enabled && !activatable) {
                continue;
            }
            PluginInstallPlan.Entry entry = new PluginInstallPlan.Entry(
                    manifest.getId(), manifest.getName(), manifest.getVersion(), enabled
                    ? PluginInstallPlan.Action.REUSE
                    : PluginInstallPlan.Action.ENABLE,
                    null, null, manifest, null, null, null, null
            );
            candidates.add(new ProviderCandidate(new RuntimeProviderDescriptor(
                    manifest.getId(), manifest.getVersion(), manifest.getProvidesRuntimes(),
                    true, enabled, Integer.MAX_VALUE, false
            ), entry));
        }
        for (PluginInstallPlan.Entry entry : selected.values()) {
            if (!entry.getProvidesRuntimes().isEmpty()
                    && candidates.stream().noneMatch(candidate -> candidate.entry.getPluginId()
                    .equals(entry.getPluginId()))) {
                candidates.add(new ProviderCandidate(new RuntimeProviderDescriptor(
                        entry.getPluginId(), entry.getVersion(), entry.getProvidesRuntimes(),
                        entry.getAction() == PluginInstallPlan.Action.REUSE,
                        entry.getAction() == PluginInstallPlan.Action.REUSE,
                        sourcePriority(entry), false
                ), entry));
            }
        }
        for (Map.Entry<String, PluginStoreItem> itemEntry : winningItems.entrySet()) {
            PluginStoreItem item = itemEntry.getValue();
            PluginStoreManifest manifest = requireManifest(item, itemEntry.getKey());
            for (PluginStoreManifest.PluginVersionEntry version : manifest.getVersionsNewestFirst()) {
                if (version.getProvidesRuntimes().isEmpty()) {
                    continue;
                }
                try {
                    validateResolvableCompatibility(item, version);
                    PluginInstallPlan.Entry entry = createRemotePlanEntry(
                            itemEntry.getKey(), item, version, installedManifests
                    );
                    candidates.add(new ProviderCandidate(new RuntimeProviderDescriptor(
                            itemEntry.getKey(), version.getVersion(), version.getProvidesRuntimes(),
                            false, false, sourcePriority(item), false
                    ), entry));
                } catch (IOException ignored) {
                    // Other versions and Providers remain eligible for deterministic selection.
                }
            }
        }
        @Nullable String pin = requirement.getPinnedProviderId();
        @Unmodifiable List<RuntimeProviderDescriptor> orderedDescriptors = pin == null
                ? runtimeProviderSelector.ordered(candidates.stream().map(candidate -> candidate.descriptor).toList())
                : candidates.stream().map(candidate -> candidate.descriptor)
                        .filter(descriptor -> pin.equals(descriptor.providerId()))
                        .toList();
        List<ProviderCandidate> orderedCandidates = new ArrayList<>();
        for (RuntimeProviderDescriptor descriptor : orderedDescriptors) {
            if (!runtimeProviderSelector.isCompatible(descriptor, requirement)) {
                continue;
            }
            // orderedDescriptors contains the exact descriptor instances created beside their plan entries above.
            candidates.stream()
                    .filter(candidate -> candidate.descriptor == descriptor)
                    .findFirst()
                    .ifPresent(orderedCandidates::add);
        }
        if (orderedCandidates.isEmpty()) {
            throw new IOException(pin == null
                    ? "No compatible runtime Provider for " + requirement.getRuntime()
                    : "Pinned runtime Provider " + pin + " cannot satisfy " + requirement.getRuntime());
        }
        return List.copyOf(orderedCandidates);
    }

    /// Returns one winning item's zero-based source priority.
    ///
    /// @param item source-bound Store item
    /// @return stable non-negative source priority
    private int sourcePriority(PluginStoreItem item) {
        return sourcePriorities.getOrDefault(item.getSource().getId(), sourcePriorities.size());
    }

    /// Returns one selected entry's source priority, or the installed tier fallback.
    ///
    /// @param entry selected plan entry
    /// @return stable non-negative source priority
    private int sourcePriority(PluginInstallPlan.Entry entry) {
        @Nullable String sourceId = entry.getSourceId();
        return sourceId == null ? Integer.MAX_VALUE : sourcePriorities.getOrDefault(sourceId, sourcePriorities.size());
    }

    /// Validates launcher, platform, Java, and built-in Java compatibility while allowing runtime resolution later.
    ///
    /// @param item source-bound Store item
    /// @param version candidate version
    /// @throws IOException if non-runtime compatibility fails
    private static void validateResolvableCompatibility(
            PluginStoreItem item,
            PluginStoreManifest.PluginVersionEntry version
    ) throws IOException {
        if (!version.matchesLauncherVersion(Metadata.VERSION)) {
            throw new IOException("Plugin requires launcher " + version.getLauncherVersion());
        }
        PluginPlatformTarget host = PluginPlatformTarget.current();
        if (!version.getPlatforms().isEmpty() && version.getPlatforms().stream()
                .map(PluginPlatformTarget::parse)
                .noneMatch(platform -> platform.matches(host))) {
            throw new IOException("Plugin does not support " + host.getId());
        }
        version.requireArtifact(host);
        if (PluginRuntimeTypes.JAVA.equals(version.getRuntime())) {
            item.getSourceManager().validateCompatibility(version);
        }
    }

    /// Validates that installed and reusable snapshots describe the same exact artifacts.
    ///
    /// @param installed installed manifests
    /// @param installedArtifacts exact current artifact identities
    /// @param reusableInstalled exact artifacts approved for reuse
    private static void validateArtifactSnapshots(
            @Unmodifiable Map<String, PluginManifest> installed,
            @Unmodifiable Map<String, PluginArtifactIdentity> installedArtifacts,
            @Unmodifiable Map<String, PluginArtifactIdentity> reusableInstalled,
            @Unmodifiable Map<String, PluginArtifactIdentity> activatableInstalled
    ) {
        if (!installed.keySet().equals(installedArtifacts.keySet())) {
            throw new IllegalArgumentException("Every installed manifest must have one exact prior artifact identity");
        }
        if (!installedArtifacts.keySet().containsAll(reusableInstalled.keySet())
                || !installedArtifacts.keySet().containsAll(activatableInstalled.keySet())) {
            throw new IllegalArgumentException("Eligible artifacts must belong to the installed manifest snapshot");
        }
        if (reusableInstalled.keySet().stream().anyMatch(activatableInstalled::containsKey)) {
            throw new IllegalArgumentException("An installed artifact cannot be reusable and activatable");
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
        for (Map.Entry<String, PluginArtifactIdentity> entry : activatableInstalled.entrySet()) {
            if (!entry.getValue().equals(installedArtifacts.get(entry.getKey()))) {
                throw new IllegalArgumentException("Activatable artifact differs from the installed snapshot: "
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
                buildDependencyOrder(rootPluginId, selected, Map.of());
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
        for (PluginStoreManifest.PluginVersionEntry version : manifest.getVersionsNewestFirst()) {
            if (!matchesAll(version.getVersion(), requirements)) {
                continue;
            }
            try {
                validateResolvableCompatibility(winningItem, version);
                candidates.add(createRemotePlanEntry(pluginId, winningItem, version, installedManifests));
            } catch (IOException ignored) {
                // Continue to an older package version that can run on this launcher and platform.
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
                item.getSourceManager(),
                version.requireArtifact(PluginPlatformTarget.current()),
                !item.getSource().isBuiltIn()
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
            Map<String, PluginInstallPlan.Entry> selected,
            @Unmodifiable Map<String, RuntimeProviderBinding> runtimeBindings
    ) throws IOException {
        List<PluginInstallPlan.Entry> order = new ArrayList<>();
        Set<String> visiting = new HashSet<>();
        Set<String> visited = new HashSet<>();
        appendDependencyOrder(rootPluginId, selected, runtimeBindings, visiting, visited, order);
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
            @Unmodifiable Map<String, RuntimeProviderBinding> runtimeBindings,
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
            appendDependencyOrder(dependency.getId(), selected, runtimeBindings, visiting, visited, order);
        }
        @Nullable RuntimeProviderBinding binding = runtimeBindings.get(pluginId);
        if (binding != null) {
            appendDependencyOrder(binding.providerId(), selected, runtimeBindings, visiting, visited, order);
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

    /// Couples one ranked Provider descriptor to the exact concrete package plan entry that produced it.
    @NotNullByDefault
    private static final class ProviderCandidate {
        /// Descriptor consumed by the shared deterministic selector.
        private final RuntimeProviderDescriptor descriptor;

        /// Concrete package operation contributed when selected.
        private final PluginInstallPlan.Entry entry;

        /// Creates one immutable candidate pair.
        ///
        /// @param descriptor runtime capability and ranking descriptor
        /// @param entry exact concrete package plan entry
        private ProviderCandidate(RuntimeProviderDescriptor descriptor, PluginInstallPlan.Entry entry) {
            this.descriptor = descriptor;
            this.entry = entry;
        }
    }
}
