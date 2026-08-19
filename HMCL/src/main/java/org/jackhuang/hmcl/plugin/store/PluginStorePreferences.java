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

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.jackhuang.hmcl.util.StringUtils;
import org.jackhuang.hmcl.util.function.ExceptionalRunnable;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// Persists ordered plugin sources and local favorites independently of remote registry results.
@NotNullByDefault
public final class PluginStorePreferences implements PluginSourceRepository {
    /// Current serialized preference schema.
    private static final int CURRENT_SCHEMA_VERSION = 2;

    /// JSON codec used for the small preference document.
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /// Pattern accepted for favorite plugin IDs loaded from disk.
    private static final Pattern ID_PATTERN = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9._-]{1,127}");

    /// Preference file below the launcher-local home.
    private final Path stateFile;

    /// Generates stable IDs for newly persisted custom sources.
    private final Supplier<String> sourceIdSupplier;

    /// Favorite plugin IDs in stable insertion order.
    private final Set<String> favoritePluginIds = new LinkedHashSet<>();

    /// Sources in persisted priority order.
    private final List<PluginSource> sources = new ArrayList<>();

    /// Monotonic in-process revision advanced after every successfully persisted source mutation.
    private long sourceRevision;

    /// Loads preferences from the supplied launcher-local home.
    ///
    /// @param localHome launcher-local home
    public PluginStorePreferences(Path localHome) {
        this(localHome, () -> "source_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8));
    }

    /// Loads preferences with deterministic custom source IDs for package-local tests.
    ///
    /// @param localHome launcher-local home
    /// @param sourceIdSupplier source ID supplier
    PluginStorePreferences(Path localHome, Supplier<String> sourceIdSupplier) {
        stateFile = localHome.resolve("plugin-store.json");
        this.sourceIdSupplier = sourceIdSupplier;
        load();
    }

    /// Loads a valid subset of persisted values and migrates legacy schema version one when possible.
    private synchronized void load() {
        if (!Files.isRegularFile(stateFile)) {
            sources.addAll(builtInSources());
            return;
        }
        try {
            @Nullable State state = GSON.fromJson(Files.readString(stateFile, StandardCharsets.UTF_8), State.class);
            if (state == null) {
                sources.addAll(builtInSources());
                return;
            }
            loadFavorites(state.favoritePluginIds);
            if (state.schemaVersion >= CURRENT_SCHEMA_VERSION) {
                sources.addAll(loadVersionTwoSources(state.sources));
            } else {
                List<PluginSource> migrated = migrateVersionOne(state);
                sources.addAll(migrated);
                migrateAndPersist(migrated);
            }
        } catch (IOException | RuntimeException exception) {
            LOG.warning("Failed to load plugin store preferences", exception);
            sources.clear();
            sources.addAll(builtInSources());
        }
    }

    /// Adds every syntactically safe favorite while ignoring malformed entries.
    ///
    /// @param persistedFavorites favorite IDs from the serialized state
    private void loadFavorites(@Nullable List<@Nullable String> persistedFavorites) {
        if (persistedFavorites == null) {
            return;
        }
        for (@Nullable String pluginId : persistedFavorites) {
            if (pluginId != null && ID_PATTERN.matcher(pluginId).matches()) {
                favoritePluginIds.add(pluginId);
            }
        }
    }

    /// Loads and validates version-two sources while ensuring the required official source remains present.
    ///
    /// @param persistedSources serialized source list
    /// @return valid source snapshot
    private List<PluginSource> loadVersionTwoSources(@Nullable List<@Nullable SourceState> persistedSources) {
        List<PluginSource> loaded = new ArrayList<>();
        if (persistedSources != null) {
            for (@Nullable SourceState sourceState : persistedSources) {
                @Nullable PluginSource source = toPluginSource(sourceState);
                if (source != null) {
                    loaded.add(source);
                }
            }
        }
        if (loaded.stream().noneMatch(PluginSource::isGitHubTopic)) {
            int officialIndex = java.util.stream.IntStream.range(0, loaded.size())
                    .filter(index -> loaded.get(index).isOfficial())
                    .findFirst()
                    .orElse(-1);
            loaded.add(officialIndex < 0 ? 0 : officialIndex + 1, topicSource());
        }
        try {
            validateSources(loaded);
            return loaded;
        } catch (IllegalArgumentException exception) {
            LOG.warning("Ignoring invalid persisted plugin sources", exception);
            return recoverSources(loaded);
        }
    }

    /// Recovers valid non-official sources after restoring the required official source.
    ///
    /// @param loaded sources parsed before full-list validation failed
    /// @return valid source snapshot
    private List<PluginSource> recoverSources(List<PluginSource> loaded) {
        List<PluginSource> recovered = new ArrayList<>();
        recovered.addAll(builtInSources());
        Set<String> ids = new HashSet<>(Set.of(PluginSource.OFFICIAL_ID, PluginSource.HMCLCE_TOPIC_ID));
        Set<URI> urls = new HashSet<>();
        try {
            urls.add(canonicalRegistryUri(PluginStoreManager.DEFAULT_REGISTRY_URL));
            urls.add(canonicalRegistryUri(PluginStoreManager.GITHUB_TOPIC_API_URL));
        } catch (IOException exception) {
            throw new IllegalStateException("The official plugin registry URL is invalid", exception);
        }
        for (PluginSource source : loaded) {
            if (source.isBuiltIn()) {
                continue;
            }
            try {
                URI canonicalUrl = canonicalRegistryUri(source.getUrl());
                if (source.getId().isBlank() || !ids.add(source.getId()) || !urls.add(canonicalUrl)) {
                    continue;
                }
                recovered.add(new PluginSource(
                        source.getId(), source.getUrl(), source.getAlias(), source.isEnabled(), false
                ));
            } catch (IOException ignored) {
                // Invalid sources are discarded individually so valid entries remain usable.
            }
        }
        return recovered;
    }

    /// Converts one serialized source to a source model when every required field is usable.
    ///
    /// @param sourceState serialized source, possibly `null`
    /// @return parsed source, or `null` for malformed state
    private @Nullable PluginSource toPluginSource(@Nullable SourceState sourceState) {
        if (sourceState == null
                || sourceState.id == null
                || sourceState.url == null
                || sourceState.id.isBlank()
                || sourceState.url.isBlank()) {
            return null;
        }
        boolean official = PluginSource.OFFICIAL_ID.equals(sourceState.id);
        boolean topic = PluginSource.HMCLCE_TOPIC_ID.equals(sourceState.id);
        if (official != sourceState.official) {
            return null;
        }
        try {
            PluginStoreManager.validateRemoteUrl(sourceState.url, "plugin registry");
            if (official && !PluginStoreManager.DEFAULT_REGISTRY_URL.equals(sourceState.url)) {
                return null;
            }
            if (topic && (!PluginStoreManager.GITHUB_TOPIC_API_URL.equals(sourceState.url) || sourceState.official)) {
                return null;
            }
            return new PluginSource(
                    sourceState.id,
                    sourceState.url,
                    sourceState.alias,
                    sourceState.enabled,
                    official
            );
        } catch (IOException exception) {
            return null;
        }
    }

    /// Converts legacy preference fields into valid version-two sources.
    ///
    /// @param state legacy serialized state
    /// @return migrated source snapshot
    private List<PluginSource> migrateVersionOne(State state) {
        List<PluginSource> migrated = new ArrayList<>();
        migrated.addAll(builtInSources());
        Set<URI> urls = new HashSet<>();
        try {
            urls.add(canonicalRegistryUri(PluginStoreManager.DEFAULT_REGISTRY_URL));
            urls.add(canonicalRegistryUri(PluginStoreManager.GITHUB_TOPIC_API_URL));
        } catch (IOException exception) {
            throw new IllegalStateException("The official plugin registry URL is invalid", exception);
        }
        if (state.customRegistryUrls != null) {
            for (@Nullable String registryUrl : state.customRegistryUrls) {
                if (registryUrl == null || StringUtils.isBlank(registryUrl)) {
                    continue;
                }
                try {
                    URI canonicalUrl = canonicalRegistryUri(registryUrl);
                    if (urls.add(canonicalUrl)) {
                        migrated.add(new PluginSource(newSourceId(migrated), registryUrl, null, true, false));
                    }
                } catch (IOException exception) {
                    LOG.warning("Ignoring invalid persisted plugin registry: " + migrationDiagnostic(registryUrl));
                }
            }
        }
        if (state.activeRegistryUrl != null) {
            try {
                URI active = canonicalRegistryUri(state.activeRegistryUrl);
                for (int index = builtInSources().size(); index < migrated.size(); index++) {
                    if (canonicalRegistryUri(migrated.get(index).getUrl()).equals(active)) {
                        PluginSource preferred = migrated.remove(index);
                        migrated.add(1, preferred);
                        break;
                    }
                }
            } catch (IOException exception) {
                LOG.warning("Ignoring invalid active plugin registry: " + migrationDiagnostic(state.activeRegistryUrl));
            }
        }
        return migrated;
    }

    /// Returns a credential-safe legacy migration diagnostic without including exception messages.
    ///
    /// @param registryUrl legacy configured registry URL
    /// @return credential-free URI diagnostic or a generic safe marker
    static String migrationDiagnostic(String registryUrl) {
        try {
            URI uri = new URI(registryUrl);
            if (StringUtils.isBlank(uri.getScheme()) || StringUtils.isBlank(uri.getHost())) {
                return "invalid plugin registry";
            }
            return PluginSourceLabels.diagnosticUrl(registryUrl);
        } catch (URISyntaxException exception) {
            return "invalid plugin registry";
        }
    }

    /// Writes a version-two replacement while retaining a legacy backup until publication succeeds.
    ///
    /// @param migrated migrated source snapshot
    private void migrateAndPersist(List<PluginSource> migrated) {
        Path backup = stateFile.resolveSibling(stateFile.getFileName() + ".v1.bak");
        try {
            Files.copy(stateFile, backup, StandardCopyOption.REPLACE_EXISTING);
            save(migrated, favoritePluginIds);
            Files.deleteIfExists(backup);
        } catch (IOException exception) {
            LOG.warning("Failed to migrate plugin store preferences", exception);
        }
    }

    /// Returns the immutable built-in source configuration.
    ///
    /// @return official plugin source
    private static PluginSource officialSource() {
        return new PluginSource(
                PluginSource.OFFICIAL_ID,
                PluginStoreManager.DEFAULT_REGISTRY_URL,
                null,
                PluginStoreManager.DEFAULT_REGISTRY_ENABLED,
                true
        );
    }

    /// Returns the immutable built-in GitHub Topic source configuration.
    private static PluginSource topicSource() {
        return new PluginSource(
                PluginSource.HMCLCE_TOPIC_ID,
                PluginStoreManager.GITHUB_TOPIC_API_URL,
                "GitHub Topic: hmclce",
                true,
                false
        );
    }

    /// Returns built-in sources in their default priority order.
    private static @Unmodifiable List<PluginSource> builtInSources() {
        return List.of(officialSource(), topicSource());
    }

    /// Creates a collision-free custom source ID.
    ///
    /// @param candidate existing source snapshot
    /// @return unused generated source ID
    private String newSourceId(List<PluginSource> candidate) {
        Set<String> existingIds = candidate.stream().map(PluginSource::getId).collect(java.util.stream.Collectors.toSet());
        while (true) {
            String sourceId = sourceIdSupplier.get();
            if (!sourceId.isBlank()
                    && !PluginSource.OFFICIAL_ID.equals(sourceId)
                    && !PluginSource.HMCLCE_TOPIC_ID.equals(sourceId)
                    && !existingIds.contains(sourceId)) {
                return sourceId;
            }
        }
    }

    /// Returns sources in ascending catalog-priority order.
    ///
    /// @return immutable source snapshot
    @Override
    public synchronized @Unmodifiable List<PluginSource> getSources() {
        return List.copyOf(sources);
    }

    /// Returns sources and their monotonic configuration revision as one atomic snapshot.
    ///
    /// @return immutable revision-bearing source configuration
    @Override
    public synchronized PluginSourceConfiguration getSourceConfiguration() {
        return new PluginSourceConfiguration(sourceRevision, sources);
    }

    /// Adds an enabled custom registry source.
    ///
    /// @param url registry URL
    /// @param alias optional local display name
    /// @return persisted source
    /// @throws IOException if validation or persistence fails
    @Override
    public synchronized PluginSource addSource(String url, @Nullable String alias) throws IOException {
        List<PluginSource> candidate = new ArrayList<>(sources);
        PluginSource source = new PluginSource(newSourceId(candidate), url, alias, true, false);
        candidate.add(source);
        return persistSources(candidate).get(candidate.size() - 1);
    }

    /// Replaces the URL and alias of one custom source while retaining its ID and priority.
    ///
    /// @param sourceId stable source identifier
    /// @param url replacement registry URL
    /// @param alias replacement optional local display name
    /// @return persisted source
    /// @throws IOException if validation or persistence fails
    @Override
    public synchronized PluginSource updateSource(String sourceId, String url, @Nullable String alias) throws IOException {
        int index = requireSourceIndex(sourceId);
        PluginSource source = sources.get(index);
        if (source.isBuiltIn()) {
            throw new IllegalArgumentException("Built-in plugin source URLs cannot be modified");
        }
        List<PluginSource> candidate = new ArrayList<>(sources);
        candidate.set(index, source.withConfiguration(url, alias));
        return persistSources(candidate).get(index);
    }

    /// Replaces the local alias of one source.
    ///
    /// @param sourceId stable source identifier
    /// @param alias replacement optional local display name
    /// @return persisted source
    /// @throws IOException if validation or persistence fails
    @Override
    public synchronized PluginSource updateAlias(String sourceId, @Nullable String alias) throws IOException {
        int index = requireSourceIndex(sourceId);
        PluginSource source = sources.get(index);
        List<PluginSource> candidate = new ArrayList<>(sources);
        candidate.set(index, source.withConfiguration(source.getUrl(), alias));
        return persistSources(candidate).get(index);
    }

    /// Removes one custom source.
    ///
    /// @param sourceId stable source identifier
    /// @throws IOException if persistence fails
    @Override
    public synchronized void removeSource(String sourceId) throws IOException {
        int index = requireSourceIndex(sourceId);
        if (sources.get(index).isBuiltIn()) {
            throw new IllegalArgumentException("Built-in plugin sources cannot be removed");
        }
        List<PluginSource> candidate = new ArrayList<>(sources);
        candidate.remove(index);
        persistSources(candidate);
    }

    /// Changes whether one source participates in aggregation.
    ///
    /// @param sourceId stable source identifier
    /// @param enabled desired enablement
    /// @return persisted source
    /// @throws IOException if validation or persistence fails
    @Override
    public synchronized PluginSource setEnabled(String sourceId, boolean enabled) throws IOException {
        int index = requireSourceIndex(sourceId);
        List<PluginSource> candidate = new ArrayList<>(sources);
        candidate.set(index, sources.get(index).withEnabled(enabled));
        return persistSources(candidate).get(index);
    }

    /// Replaces source priority with an exact permutation of current source IDs.
    ///
    /// @param sourceIds every current source ID exactly once in desired order
    /// @return immutable persisted source snapshot
    /// @throws IOException if validation or persistence fails
    @Override
    public synchronized @Unmodifiable List<PluginSource> reorder(@Unmodifiable List<String> sourceIds) throws IOException {
        if (sourceIds.size() != sources.size()) {
            throw new IllegalArgumentException("Plugin source reorder must include every source exactly once");
        }
        Map<String, PluginSource> byId = new LinkedHashMap<>();
        for (PluginSource source : sources) {
            byId.put(source.getId(), source);
        }
        List<PluginSource> candidate = new ArrayList<>(sources.size());
        for (String sourceId : sourceIds) {
            @Nullable PluginSource source = byId.remove(sourceId);
            if (source == null) {
                throw new IllegalArgumentException("Plugin source reorder contains an unknown or duplicate source ID");
            }
            candidate.add(source);
        }
        if (!byId.isEmpty()) {
            throw new IllegalArgumentException("Plugin source reorder must include every source exactly once");
        }
        return persistSources(candidate);
    }

    /// Returns the source index or rejects unknown source IDs.
    ///
    /// @param sourceId stable source identifier
    /// @return source priority index
    private int requireSourceIndex(String sourceId) {
        for (int index = 0; index < sources.size(); index++) {
            if (sources.get(index).getId().equals(sourceId)) {
                return index;
            }
        }
        throw new IllegalArgumentException("Unknown plugin source: " + sourceId);
    }

    /// Validates and atomically saves candidate sources before replacing the in-memory snapshot.
    ///
    /// @param candidate prospective source snapshot
    /// @return immutable persisted source snapshot
    /// @throws IOException if the disk replacement fails
    private synchronized @Unmodifiable List<PluginSource> persistSources(List<PluginSource> candidate) throws IOException {
        validateSources(candidate);
        if (PluginStoreSnapshot.matchesSourceConfigurations(candidate, sources)) {
            return List.copyOf(sources);
        }
        save(candidate, favoritePluginIds);
        sources.clear();
        sources.addAll(candidate);
        sourceRevision++;
        return List.copyOf(sources);
    }

    /// Validates all source invariants required by persisted and user-authored source snapshots.
    ///
    /// @param candidate prospective source snapshot
    /// @throws IllegalArgumentException if any source invariant is violated
    private static void validateSources(List<PluginSource> candidate) {
        if (candidate.isEmpty()) {
            throw new IllegalArgumentException("Plugin sources must contain the official source");
        }
        Set<String> ids = new HashSet<>();
        Set<URI> urls = new HashSet<>();
        boolean officialFound = false;
        boolean topicFound = false;
        for (PluginSource source : candidate) {
            if (source.getId().isBlank() || !ids.add(source.getId())) {
                throw new IllegalArgumentException("Plugin source IDs must be unique and nonblank");
            }
            try {
                URI canonicalUrl = canonicalRegistryUri(source.getUrl());
                if (!urls.add(canonicalUrl)) {
                    throw new IllegalArgumentException("Plugin source URLs must be unique");
                }
            } catch (IOException exception) {
                throw new IllegalArgumentException(
                        "Invalid plugin source URL: " + PluginSourceLabels.diagnosticUrl(source.getUrl())
                );
            }
            if (source.isOfficial()) {
                if (!PluginSource.OFFICIAL_ID.equals(source.getId())
                        || !PluginStoreManager.DEFAULT_REGISTRY_URL.equals(source.getUrl())
                        || officialFound) {
                    throw new IllegalArgumentException("The official plugin source is invalid");
                }
                officialFound = true;
            } else if (source.isGitHubTopic()) {
                if (!PluginStoreManager.GITHUB_TOPIC_API_URL.equals(source.getUrl()) || topicFound) {
                    throw new IllegalArgumentException("The hmclce GitHub Topic source is invalid");
                }
                topicFound = true;
            } else if (PluginSource.OFFICIAL_ID.equals(source.getId())) {
                throw new IllegalArgumentException("The official plugin source ID is reserved");
            } else if (PluginSource.HMCLCE_TOPIC_ID.equals(source.getId())) {
                throw new IllegalArgumentException("The hmclce GitHub Topic source ID is reserved");
            }
        }
        if (!officialFound) {
            throw new IllegalArgumentException("Plugin sources must contain the official source");
        }
        if (!topicFound) {
            throw new IllegalArgumentException("Plugin sources must contain the hmclce GitHub Topic source");
        }
    }

    /// Canonicalizes a registry URL only for duplicate comparison without changing its persisted presentation.
    ///
    /// @param url registry URL
    /// @return canonical registry URI
    /// @throws IOException if the URL violates registry transport policy or URI syntax
    static URI canonicalRegistryUri(String url) throws IOException {
        PluginStoreManager.validateRemoteUrl(url, "plugin registry");
        try {
            URI uri = new URI(url);
            int port = uri.getPort();
            if (port == 443 && "https".equalsIgnoreCase(uri.getScheme())
                    || port == 80 && "http".equalsIgnoreCase(uri.getScheme())) {
                port = -1;
            }
            String path = StringUtils.isBlank(uri.getPath()) ? "/" : uri.getPath();
            return new URI(uri.getScheme().toLowerCase(Locale.ROOT), uri.getUserInfo(),
                    uri.getHost().toLowerCase(Locale.ROOT), port, path,
                    uri.getQuery(), uri.getFragment());
        } catch (URISyntaxException exception) {
            throw new IOException("Invalid plugin registry URL", exception);
        }
    }

    /// Serializes the complete state through a temporary file and atomic replacement when supported.
    ///
    /// @param candidateSources sources to serialize
    /// @param candidateFavorites favorites to serialize
    /// @throws IOException if the complete state cannot be persisted
    private void save(List<PluginSource> candidateSources, Set<String> candidateFavorites) throws IOException {
        State state = new State();
        state.schemaVersion = CURRENT_SCHEMA_VERSION;
        state.favoritePluginIds = candidateFavorites.stream().sorted().toList();
        state.sources = candidateSources.stream().map(SourceState::from).toList();

        Path temporaryFile = stateFile.resolveSibling(stateFile.getFileName() + ".tmp");
        try {
            Files.createDirectories(stateFile.getParent());
            Files.writeString(temporaryFile, GSON.toJson(state), StandardCharsets.UTF_8);
            try {
                Files.move(temporaryFile, stateFile, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporaryFile, stateFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            try {
                Files.deleteIfExists(temporaryFile);
            } catch (IOException exception) {
                LOG.warning("Failed to delete temporary plugin store preference file", exception);
            }
        }
    }

    /// Returns custom source URLs for the legacy single-source store client.
    ///
    /// @return immutable custom source URL snapshot
    synchronized @Unmodifiable List<String> getCustomRegistryUrls() {
        return sources.stream()
                .filter(source -> !source.isBuiltIn() && source.isEnabled())
                .map(PluginSource::getUrl)
                .toList();
    }

    /// Returns the legacy active source represented by the highest-priority enabled custom source.
    ///
    /// @return active registry URL
    synchronized String getActiveRegistryUrl() {
        return sources.stream()
                .filter(source -> !source.isBuiltIn() && source.isEnabled())
                .findFirst()
                .map(PluginSource::getUrl)
                .orElse(PluginStoreManager.DEFAULT_REGISTRY_URL);
    }

    /// Adds a custom source for the legacy single-source store client and logs persistence failures.
    ///
    /// @param registryUrl validated registry URL
    synchronized void addCustomRegistryUrl(String registryUrl) {
        try {
            URI canonicalUrl = canonicalRegistryUri(registryUrl);
            if (sources.stream().map(PluginSource::getUrl).noneMatch(url -> {
                try {
                    return canonicalRegistryUri(url).equals(canonicalUrl);
                } catch (IOException exception) {
                    return false;
                }
            })) {
                addSource(registryUrl, null);
            }
        } catch (IOException exception) {
            LOG.warning("Failed to save custom plugin registry");
        }
    }

    /// Moves a legacy active custom registry to highest priority.
    ///
    /// @param registryUrl validated registry URL
    synchronized void setActiveRegistryUrl(String registryUrl) {
        try {
            @Nullable PluginSource active = sources.stream()
                    .filter(source -> source.getUrl().equals(registryUrl))
                    .findFirst()
                    .orElse(null);
            if (active == null) {
                active = addSource(registryUrl, null);
            }
            if (active.isBuiltIn()) {
                return;
            }
            List<String> reorderedIds = new ArrayList<>(sources.stream().map(PluginSource::getId).toList());
            reorderedIds.remove(active.getId());
            reorderedIds.add(0, active.getId());
            reorder(reorderedIds);
        } catch (IOException exception) {
            LOG.warning("Failed to persist active plugin registry");
        }
    }

    /// Runs publication while holding the same monitor used by every source mutation.
    ///
    /// @param expectedConfiguration revision-bearing source configuration that selected the operation
    /// @param action publication action that must not race a source mutation
    /// @throws IOException if the expected configuration is stale or publication fails
    @Override
    public synchronized void executeIfSourcesMatch(
            PluginSourceConfiguration expectedConfiguration,
            ExceptionalRunnable<IOException> action
    ) throws IOException {
        if (expectedConfiguration.getRevision() != sourceRevision
                || !PluginStoreSnapshot.matchesSourceConfigurations(
                        expectedConfiguration.getSources(),
                        sources
                )) {
            throw new IOException("Plugin source configuration changed");
        }
        action.run();
    }

    /// Returns whether the supplied plugin is a user favorite.
    ///
    /// @param pluginId plugin ID
    /// @return favorite state
    @Override
    public synchronized boolean isFavorite(String pluginId) {
        return favoritePluginIds.contains(pluginId);
    }

    /// Updates one favorite and logs persistence failures without changing the in-memory state.
    ///
    /// @param pluginId plugin ID
    /// @param favorite desired favorite state
    /// @throws IllegalArgumentException if the plugin ID cannot be persisted safely
    @Override
    public synchronized void setFavorite(String pluginId, boolean favorite) {
        if (!ID_PATTERN.matcher(pluginId).matches()) {
            throw new IllegalArgumentException("Invalid favorite plugin ID: " + pluginId);
        }
        Set<String> candidate = new LinkedHashSet<>(favoritePluginIds);
        boolean changed = favorite ? candidate.add(pluginId) : candidate.remove(pluginId);
        if (!changed) {
            return;
        }
        try {
            save(sources, candidate);
            favoritePluginIds.clear();
            favoritePluginIds.addAll(candidate);
        } catch (IOException exception) {
            LOG.warning("Failed to save plugin store preferences", exception);
        }
    }

    /// Returns an immutable snapshot of favorite plugin IDs.
    ///
    /// @return favorite plugin IDs
    @Override
    public synchronized @Unmodifiable Set<String> getFavoritePluginIds() {
        return Set.copyOf(favoritePluginIds);
    }

    /// Gson storage model for plugin-store preferences.
    @NotNullByDefault
    private static final class State {
        /// Serialized schema version, where omitted and lower versions are treated as version one.
        private int schemaVersion;

        /// Favorite IDs, or `null` in malformed documents.
        private @Nullable List<@Nullable String> favoritePluginIds;

        /// Version-two source state, or `null` in malformed documents.
        private @Nullable List<@Nullable SourceState> sources;

        /// Version-one custom registry URLs retained read-only for migration.
        private @Nullable List<@Nullable String> customRegistryUrls;

        /// Version-one active registry URL retained read-only for migration.
        private @Nullable String activeRegistryUrl;

        /// Creates an empty preference state for Gson.
        private State() {
        }
    }

    /// Gson storage model for one version-two source.
    @NotNullByDefault
    private static final class SourceState {
        /// Stable local source identifier.
        private @Nullable String id;

        /// Persisted registry URL.
        private @Nullable String url;

        /// Optional local display name.
        private @Nullable String alias;

        /// Whether the source is enabled.
        private boolean enabled;

        /// Whether the source is official.
        private boolean official;

        /// Creates an empty source state for Gson.
        private SourceState() {
        }

        /// Converts an immutable source to serialized state.
        ///
        /// @param source source configuration
        /// @return serialized source state
        private static SourceState from(PluginSource source) {
            SourceState state = new SourceState();
            state.id = source.getId();
            state.url = source.getUrl();
            state.alias = source.getAlias();
            state.enabled = source.isEnabled();
            state.official = source.isOfficial();
            return state;
        }
    }
}
