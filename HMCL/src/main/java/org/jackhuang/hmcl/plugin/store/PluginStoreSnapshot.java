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

import org.jackhuang.hmcl.plugin.trust.PluginTrustResult;
import org.jackhuang.hmcl.plugin.trust.PluginTrustVerifier;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/// Immutable aggregate catalog produced by one plugin-source refresh generation.
@NotNullByDefault
public final class PluginStoreSnapshot {
    /// Refresh generation associated with this independently usable aggregate result.
    private final long generation;

    /// Monotonic repository source revision captured when this refresh began.
    private final long sourceRevision;

    /// Source outcomes in the supplied source priority order.
    private final @Unmodifiable List<PluginSourceLoadResult> sourceResults;

    /// First successful item for every plugin ID, retaining source priority order.
    private final @Unmodifiable Map<String, PluginStoreItem> winningItems;

    /// Lower-priority successful items grouped below their selected winner by plugin ID.
    private final @Unmodifiable Map<String, @Unmodifiable List<PluginStoreItem>> conflictCandidates;

    /// Registry-level source failures retained for source detail views and banners.
    private final @Unmodifiable List<PluginSourceLoadResult> failures;

    /// Creates an aggregate snapshot and derives deterministic winners from ordered source results.
    ///
    /// @param generation refresh generation that computed this snapshot
    /// @param sourceResults source outcomes ordered by configured priority
    public PluginStoreSnapshot(long generation, @Unmodifiable List<PluginSourceLoadResult> sourceResults) {
        this(generation, 0, sourceResults);
    }

    /// Creates an aggregate snapshot carrying the source revision that selected the refresh.
    ///
    /// @param generation refresh generation that computed this snapshot
    /// @param sourceRevision monotonic source configuration revision captured when refresh began
    /// @param sourceResults source outcomes ordered by configured priority
    public PluginStoreSnapshot(
            long generation,
            long sourceRevision,
            @Unmodifiable List<PluginSourceLoadResult> sourceResults
    ) {
        if (generation < 0) {
            throw new IllegalArgumentException("generation must not be negative");
        }
        if (sourceRevision < 0) {
            throw new IllegalArgumentException("sourceRevision must not be negative");
        }
        this.generation = generation;
        this.sourceRevision = sourceRevision;
        this.sourceResults = List.copyOf(sourceResults);

        @Unmodifiable Set<OfficialReference> officialReferences = collectOfficialReferences(this.sourceResults);
        Map<String, PluginStoreItem> winners = new LinkedHashMap<>();
        Map<String, List<PluginStoreItem>> conflicts = new LinkedHashMap<>();
        List<PluginSourceLoadResult> sourceFailures = new ArrayList<>();
        for (PluginSourceLoadResult result : this.sourceResults) {
            if (result.getStatus() == PluginSourceLoadResult.Status.FAILED) {
                sourceFailures.add(result);
            }
            if (!result.isSuccessful()) {
                continue;
            }
            for (PluginStoreItem item : result.getItems()) {
                applyOfficialCertification(item, officialReferences);
                String pluginId = item.getEntry().getId();
                PluginStoreItem previous = winners.get(pluginId);
                if (previous == null) {
                    winners.put(pluginId, item);
                } else if (item.getTrust().level().getPriority() > previous.getTrust().level().getPriority()) {
                    winners.put(pluginId, item);
                    conflicts.computeIfAbsent(pluginId, ignored -> new ArrayList<>()).add(previous);
                } else {
                    conflicts.computeIfAbsent(pluginId, ignored -> new ArrayList<>()).add(item);
                }
            }
        }

        winningItems = Collections.unmodifiableMap(winners);
        conflictCandidates = copyConflicts(conflicts);
        failures = List.copyOf(sourceFailures);
    }

    /// Collects the plugin and repository pairs approved by every successfully verified official source.
    ///
    /// @param sourceResults completed source outcomes
    /// @return immutable official reference set
    private static @Unmodifiable Set<OfficialReference> collectOfficialReferences(
            @Unmodifiable List<PluginSourceLoadResult> sourceResults
    ) {
        Set<OfficialReference> references = new LinkedHashSet<>();
        for (PluginSourceLoadResult result : sourceResults) {
            if (!result.isSuccessful() || !result.getSource().isOfficial()) {
                continue;
            }
            @Nullable PluginStoreRegistry registry = result.getRegistry();
            if (registry == null) {
                continue;
            }
            for (PluginStoreRegistry.PluginStoreEntry entry : registry.getPlugins()) {
                @Nullable OfficialReference reference = normalizedReference(entry);
                if (reference != null) {
                    references.add(reference);
                }
            }
        }
        return Set.copyOf(references);
    }

    /// Applies official community review to every version from the referenced non-official repository.
    ///
    /// @param item source-bound community item
    /// @param officialReferences approved plugin and repository pairs
    private static void applyOfficialCertification(
            PluginStoreItem item,
            @Unmodifiable Set<OfficialReference> officialReferences
    ) {
        if (item.getSource().isOfficial() || item.getManifest() == null) {
            return;
        }
        @Nullable OfficialReference reference = normalizedReference(item.getEntry());
        if (reference == null || !officialReferences.contains(reference)) {
            return;
        }
        PluginTrustResult trust = PluginTrustResult.certifiedByOfficialReference();
        item.getManifest().getVersions().forEach(version -> version.setTrust(trust));
    }

    /// Normalizes one registry entry into a comparable certification reference.
    ///
    /// @param entry registry entry with source-controlled repository metadata
    /// @return normalized reference, or `null` when no valid repository identity is present
    private static @Nullable OfficialReference normalizedReference(
            PluginStoreRegistry.PluginStoreEntry entry
    ) {
        if (entry.getRepository().isBlank()) {
            return null;
        }
        try {
            return new OfficialReference(
                    entry.getId(),
                    PluginTrustVerifier.normalizeRepository(entry.getRepository())
            );
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    /// Identifies one plugin repository reviewed through the signed official registry.
    ///
    /// @param pluginId globally unique plugin ID
    /// @param repository normalized GitHub repository identity
    private record OfficialReference(String pluginId, String repository) {
    }

    /// Copies conflict candidates and their nested lists before the snapshot exposes them.
    ///
    /// @param conflicts mutable conflict candidates collected while deriving winners
    /// @return immutable priority-ordered conflict candidates
    private static @Unmodifiable Map<String, @Unmodifiable List<PluginStoreItem>> copyConflicts(
            Map<String, List<PluginStoreItem>> conflicts
    ) {
        Map<String, @Unmodifiable List<PluginStoreItem>> copy = new LinkedHashMap<>();
        for (Map.Entry<String, List<PluginStoreItem>> entry : conflicts.entrySet()) {
            copy.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        return Collections.unmodifiableMap(copy);
    }

    /// Returns whether two ordered source configurations match in every behavior-relevant field.
    ///
    /// @param candidates captured ordered source configuration
    /// @param sources current persisted source configuration
    /// @return whether every source configuration matches in order
    public static boolean matchesSourceConfigurations(
            @Unmodifiable List<PluginSource> candidates,
            @Unmodifiable List<PluginSource> sources
    ) {
        if (candidates.size() != sources.size()) {
            return false;
        }
        for (int index = 0; index < sources.size(); index++) {
            if (!sourceConfigurationsMatch(candidates.get(index), sources.get(index))) {
                return false;
            }
        }
        return true;
    }

    /// Returns whether this aggregate reflects the exact persisted source configuration in priority order.
    ///
    /// @param sources current persisted source configuration
    /// @return whether every source's behavior-relevant configuration matches in order
    public static boolean matchesSources(
            @Unmodifiable List<PluginSourceLoadResult> sourceResults,
            @Unmodifiable List<PluginSource> sources
    ) {
        if (sourceResults.size() != sources.size()) {
            return false;
        }
        for (int index = 0; index < sources.size(); index++) {
            if (!sourceConfigurationsMatch(sourceResults.get(index).getSource(), sources.get(index))) {
                return false;
            }
        }
        return true;
    }

    /// Returns whether two source configurations are equal for all catalog behavior-relevant fields.
    ///
    /// @param candidate configuration captured by an aggregate or manual test result
    /// @param current current persisted source configuration
    /// @return whether both configurations are behaviorally identical
    public static boolean sourceConfigurationsMatch(PluginSource candidate, PluginSource current) {
        return candidate.getId().equals(current.getId())
                && candidate.getUrl().equals(current.getUrl())
                && Objects.equals(candidate.getAlias(), current.getAlias())
                && candidate.isEnabled() == current.isEnabled()
                && candidate.isOfficial() == current.isOfficial();
    }

    /// Returns whether this aggregate reflects the exact persisted source configuration in priority order.
    ///
    /// @param sources current persisted source configuration
    /// @return whether every source's behavior-relevant configuration matches in order
    public boolean matchesSources(@Unmodifiable List<PluginSource> sources) {
        return matchesSources(sourceResults, sources);
    }

    /// Returns whether this aggregate still matches both the monotonic revision and exact source configuration.
    ///
    /// @param configuration current revision-bearing persisted source configuration
    /// @return whether no successful source mutation occurred since this refresh began
    public boolean matchesSourceConfiguration(PluginSourceConfiguration configuration) {
        return sourceRevision == configuration.getRevision() && matchesSources(configuration.getSources());
    }

    /// Returns this snapshot's revision-bearing ordered source configuration.
    ///
    /// @return immutable revision-bearing source configuration
    public PluginSourceConfiguration getSourceConfiguration() {
        return new PluginSourceConfiguration(
                sourceRevision,
                sourceResults.stream().map(PluginSourceLoadResult::getSource).toList()
        );
    }

    /// Returns the monotonic source revision captured when this refresh began.
    ///
    /// @return captured source configuration revision
    public long getSourceRevision() {
        return sourceRevision;
    }

    /// Returns the refresh generation that produced this snapshot.
    ///
    /// @return aggregate refresh generation
    public long getGeneration() {
        return generation;
    }

    /// Returns all configured-source outcomes in priority order.
    ///
    /// @return immutable source outcomes
    public @Unmodifiable List<PluginSourceLoadResult> getSourceResults() {
        return sourceResults;
    }

    /// Returns the first successful item for every plugin ID in source priority order.
    ///
    /// @return immutable winning items by plugin ID
    public @Unmodifiable Map<String, PluginStoreItem> getWinningItems() {
        return winningItems;
    }

    /// Returns successful lower-priority items that conflict with each winner.
    ///
    /// @return immutable conflict candidates by plugin ID
    public @Unmodifiable Map<String, @Unmodifiable List<PluginStoreItem>> getConflictCandidates() {
        return conflictCandidates;
    }

    /// Returns source results whose registries could not be loaded.
    ///
    /// @return immutable failed source results
    public @Unmodifiable List<PluginSourceLoadResult> getFailures() {
        return failures;
    }
}
