# Plugin source manager implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the single active registry selector with an ordered source manager and a catalog that safely aggregates every enabled plugin source.

**Architecture:** `PluginStorePreferences` persists ordered `PluginSource` objects and favorites behind a repository interface. `PluginStoreManager` remains a single-source security client, while `PluginStoreAggregator` loads enabled sources through a bounded executor and publishes one generation-protected snapshot. JavaFX pages consume the snapshot's winning items, and dependency planning never falls back to lower-priority conflict candidates.

**Tech Stack:** Java 17, JavaFX, JFoenix, Gson, JUnit Jupiter, Gradle, JDK `HttpServer`

## Global constraints

- Follow `AGENTS.md`: every new Java class uses `@NotNullByDefault`
- Mark every nullable type use with `@Nullable`
- Mark immutable arrays and collections with `@Unmodifiable` or `@UnmodifiableView`
- Document every added or modified class, field, and method with `///` Markdown Javadoc
- Keep the official source at fixed ID `official` and `PluginStoreManager.DEFAULT_REGISTRY_URL`
- Allow disabling the official source, but reject its deletion and URL modification
- Resolve duplicate plugin IDs by source order, independent of plugin version
- Continue to use existing URL, redirect, size, schema, checksum, and package metadata validation
- Use at most four concurrent source loads on a dedicated executor
- Do not add persistent registry, manifest, README, or source-health caches
- Keep favorites keyed by plugin ID
- Do not push, open a pull request, or merge without explicit authorization

## File map

### New production files

- `HMCL/src/main/java/org/jackhuang/hmcl/plugin/store/PluginSource.java`: Immutable source identity and display-name rules
- `HMCL/src/main/java/org/jackhuang/hmcl/plugin/store/PluginSourceRepository.java`: Source and favorite persistence contract
- `HMCL/src/main/java/org/jackhuang/hmcl/plugin/store/PluginSourceLoadResult.java`: One source's runtime load state
- `HMCL/src/main/java/org/jackhuang/hmcl/plugin/store/PluginStoreSnapshot.java`: Immutable aggregate winners, conflicts, and failures
- `HMCL/src/main/java/org/jackhuang/hmcl/plugin/store/PluginStoreAggregator.java`: Bounded multi-source orchestration and generation gate
- `HMCL/src/main/java/org/jackhuang/hmcl/plugin/store/PluginStoreDependencyResolver.java`: Winner-only dependency planning
- `HMCL/src/main/java/org/jackhuang/hmcl/ui/main/PluginSourceManagementPage.java`: Independent compact-row source management page

### Modified production files

- `HMCL/src/main/java/org/jackhuang/hmcl/plugin/store/PluginStorePreferences.java`: Version 2 storage, migration, atomic source mutations, and favorites
- `HMCL/src/main/java/org/jackhuang/hmcl/plugin/store/PluginStoreManager.java`: Remove source-selection persistence; expose single-source loading and existing security policy
- `HMCL/src/main/java/org/jackhuang/hmcl/plugin/store/PluginStoreItem.java`: Bind each item to its source, registry, and source manager
- `HMCL/src/main/java/org/jackhuang/hmcl/plugin/store/PluginInstallPlan.java`: Carry source identity for every downloadable entry
- `HMCL/src/main/java/org/jackhuang/hmcl/ui/main/PluginStorePage.java`: Consume aggregate snapshots and navigate to source management
- `HMCL/src/main/java/org/jackhuang/hmcl/ui/main/PluginDialogs.java`: Show source and degraded-result warnings during permission review
- `HMCL/src/main/resources/assets/lang/I18N.properties`: English source-manager strings
- `HMCL/src/main/resources/assets/lang/I18N_zh_CN.properties`: Simplified Chinese source-manager strings
- `HMCL/src/main/resources/assets/lang/I18N_zh.properties`: Traditional Chinese source-manager strings

### New test files

- `HMCL/src/test/java/org/jackhuang/hmcl/plugin/store/PluginSourceRepositoryTest.java`
- `HMCL/src/test/java/org/jackhuang/hmcl/plugin/store/PluginStoreAggregatorTest.java`
- `HMCL/src/test/java/org/jackhuang/hmcl/ui/main/PluginSourceManagementPageTest.java`

### Modified test files

- `HMCL/src/test/java/org/jackhuang/hmcl/plugin/store/PluginStoreManagerTest.java`
- `HMCL/src/test/java/org/jackhuang/hmcl/plugin/store/PluginStoreDependencyResolverTest.java`
- `HMCL/src/test/java/org/jackhuang/hmcl/ui/main/PluginStorePageTest.java`

### Task 1: Persist ordered plugin sources and migrate version 1 preferences

**Files:**
- Create: `HMCL/src/main/java/org/jackhuang/hmcl/plugin/store/PluginSource.java`
- Create: `HMCL/src/main/java/org/jackhuang/hmcl/plugin/store/PluginSourceRepository.java`
- Modify: `HMCL/src/main/java/org/jackhuang/hmcl/plugin/store/PluginStorePreferences.java:40-208`
- Create: `HMCL/src/test/java/org/jackhuang/hmcl/plugin/store/PluginSourceRepositoryTest.java`

**Interfaces:**
- Produces: `PluginSource`, `PluginSourceRepository`, and `PluginStorePreferences(Path)`
- Produces: ordered source snapshots and atomic `addSource`, `updateSource`, `updateAlias`, `removeSource`, `setEnabled`, and `reorder` mutations
- Preserves: `isFavorite`, `setFavorite`, and `getFavoritePluginIds`

- [ ] **Step 1: Write migration and invariant tests**

Create `PluginSourceRepositoryTest` with local-home JSON fixtures. Start with these tests and add one helper that reads the saved JSON:

```java
@Test
public void migratesVersionOneAndMovesTheOldActiveCustomSourceAfterOfficial(
        @TempDir Path localHome
) throws Exception {
    String first = "https://one.example/plugins.json";
    String active = "https://two.example/plugins.json";
    Files.writeString(localHome.resolve("plugin-store.json"), """
            {
              "favoritePluginIds": ["dev.hmclce.pcltheme"],
              "customRegistryUrls": ["%s", "%s"],
              "activeRegistryUrl": "%s"
            }
            """.formatted(first, active, active));

    PluginStorePreferences preferences = new PluginStorePreferences(localHome);

    assertEquals(3, preferences.getSources().size());
    assertEquals(PluginSource.OFFICIAL_ID, preferences.getSources().get(0).getId());
    assertEquals(active, preferences.getSources().get(1).getUrl());
    assertEquals(first, preferences.getSources().get(2).getUrl());
    assertTrue(preferences.isFavorite("dev.hmclce.pcltheme"));
}

@Test
public void rejectsOfficialDeletionAndUrlModification(@TempDir Path localHome) {
    PluginStorePreferences preferences = new PluginStorePreferences(localHome);

    assertThrows(IllegalArgumentException.class,
            () -> preferences.removeSource(PluginSource.OFFICIAL_ID));
    assertThrows(IllegalArgumentException.class,
            () -> preferences.updateSource(
                    PluginSource.OFFICIAL_ID,
                    "https://example.org/plugins.json",
                    null
            ));
}

@Test
public void failedWriteLeavesDiskAndMemoryUnchanged(@TempDir Path localHome) throws Exception {
    PluginStorePreferences preferences = new PluginStorePreferences(localHome);
    PluginSource existing = preferences.addSource("https://one.example/plugins.json", "One");
    String before = Files.readString(localHome.resolve("plugin-store.json"));
    Files.createDirectory(localHome.resolve("plugin-store.json.tmp"));

    assertThrows(IOException.class,
            () -> preferences.setEnabled(existing.getId(), false));
    assertEquals(before, Files.readString(localHome.resolve("plugin-store.json")));
    assertTrue(preferences.getSources().stream()
            .filter(source -> source.getId().equals(existing.getId()))
            .findFirst()
            .orElseThrow()
            .isEnabled());
}
```

Also cover version 2 round-trip, canonical duplicate URLs, stable ID and position after URL edit, exact reorder membership, official disable, malformed JSON fallback, partial favorite recovery, and preservation of the version 1 file when migration cannot publish version 2.

- [ ] **Step 2: Run the repository tests and verify they fail**

Run:

```powershell
.\gradlew.bat :HMCL:test --tests "org.jackhuang.hmcl.plugin.store.PluginSourceRepositoryTest"
```

Expected: compilation fails because `PluginSource`, `PluginSourceRepository`, and source mutation methods do not exist.

- [ ] **Step 3: Add the immutable source model and repository contract**

Create `PluginSource` with copying methods so repository mutations never alter an existing object:

```java
@NotNullByDefault
public final class PluginSource {
    public static final String OFFICIAL_ID = "official";

    private final String id;
    private final String url;
    private final @Nullable String alias;
    private final boolean enabled;
    private final boolean official;

    public PluginSource(String id, String url, @Nullable String alias,
                        boolean enabled, boolean official) {
        this.id = id;
        this.url = url;
        this.alias = normalizeAlias(alias);
        this.enabled = enabled;
        this.official = official;
    }

    public PluginSource withConfiguration(String url, @Nullable String alias) {
        return new PluginSource(id, url, alias, enabled, official);
    }

    public PluginSource withEnabled(boolean enabled) {
        return new PluginSource(id, url, alias, enabled, official);
    }
}
```

Create the repository interface with exact collection contracts:

```java
@NotNullByDefault
public interface PluginSourceRepository {
    @Unmodifiable List<PluginSource> getSources();
    PluginSource addSource(String url, @Nullable String alias) throws IOException;
    PluginSource updateSource(String sourceId, String url, @Nullable String alias) throws IOException;
    PluginSource updateAlias(String sourceId, @Nullable String alias) throws IOException;
    void removeSource(String sourceId) throws IOException;
    PluginSource setEnabled(String sourceId, boolean enabled) throws IOException;
    @Unmodifiable List<PluginSource> reorder(@Unmodifiable List<String> sourceIds) throws IOException;
    boolean isFavorite(String pluginId);
    void setFavorite(String pluginId, boolean favorite);
    @Unmodifiable Set<String> getFavoritePluginIds();
}
```

Add every getter and required `///` documentation while implementing these excerpts.

- [ ] **Step 4: Upgrade preferences to schema version 2 with transactional mutation**

Make `PluginStorePreferences` public and implement `PluginSourceRepository`. Keep one lock by retaining synchronized public methods. Build candidate copies, persist those copies, then replace in-memory state:

```java
private synchronized @Unmodifiable List<PluginSource> persistSources(
        List<PluginSource> candidate
) throws IOException {
    validateSources(candidate);
    save(candidate, favoritePluginIds);
    sources.clear();
    sources.addAll(candidate);
    return List.copyOf(sources);
}
```

Each mutation creates and changes its own candidate before calling `persistSources`. For example, `setEnabled` finds the target index, replaces that element with `source.withEnabled(enabled)`, and returns the corresponding element from the persisted snapshot. `reorder` maps the submitted IDs to existing objects and rejects missing, duplicate, or unknown IDs before calling `persistSources`.

Store this version 2 state:

```java
private static final int CURRENT_SCHEMA_VERSION = 2;

private static final class State {
    private int schemaVersion;
    private @Nullable List<@Nullable String> favoritePluginIds;
    private @Nullable List<@Nullable SourceState> sources;
    private @Nullable List<@Nullable String> customRegistryUrls;
    private @Nullable String activeRegistryUrl;
}
```

Treat a missing or lower schema version as version 1. Insert the official source, canonicalize only for duplicate comparison, generate custom IDs with a dedicated `newSourceId()` method, and move the old active custom source after official. In production, `newSourceId()` returns `"source_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8)` and retries on collision. Add a package-private constructor that accepts a `Supplier<String>` for deterministic migration tests. Keep old fields read-only for migration and omit them from version 2 writes.

Implement canonical comparison with `URI`:

```java
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
```

Persist source mutations through the existing temporary file and atomic move, but propagate `IOException`. Do not mutate source memory before the move succeeds. Keep the existing favorite API nonthrowing for compatibility: attempt a transactional full-state write, update the in-memory favorite set only after success, and log an `IOException` without changing memory. During migration, copy the original to `plugin-store.json.v1.bak`, publish version 2, and delete the backup only after successful replacement.

- [ ] **Step 5: Run repository tests and the existing manager tests**

Run:

```powershell
.\gradlew.bat :HMCL:test --tests "org.jackhuang.hmcl.plugin.store.PluginSourceRepositoryTest" --tests "org.jackhuang.hmcl.plugin.store.PluginStoreManagerTest"
```

Expected: all repository tests pass. Update the existing favorite persistence assertions in `PluginStoreManagerTest` to construct one `PluginStorePreferences` directly, because Task 2 removes favorites from `PluginStoreManager`; do not weaken transport assertions.

- [ ] **Step 6: Commit the repository milestone**

```bash
git add HMCL/src/main/java/org/jackhuang/hmcl/plugin/store/PluginSource.java \
  HMCL/src/main/java/org/jackhuang/hmcl/plugin/store/PluginSourceRepository.java \
  HMCL/src/main/java/org/jackhuang/hmcl/plugin/store/PluginStorePreferences.java \
  HMCL/src/test/java/org/jackhuang/hmcl/plugin/store/PluginSourceRepositoryTest.java
git commit -m "feat: persist ordered plugin sources"
```

### Task 2: Make the store manager source-scoped

**Files:**
- Modify: `HMCL/src/main/java/org/jackhuang/hmcl/plugin/store/PluginStoreManager.java:92-260,1000-1133`
- Modify: `HMCL/src/main/java/org/jackhuang/hmcl/plugin/store/PluginStoreItem.java:23-64`
- Modify: `HMCL/src/test/java/org/jackhuang/hmcl/plugin/store/PluginStoreManagerTest.java:59-119`

**Interfaces:**
- Consumes: `PluginSource`
- Produces: `loadSource(PluginSource)` and source-bound `PluginStoreItem`
- Preserves: manifest, README, redirect, package, compatibility, and checksum behavior

- [ ] **Step 1: Replace the active-source race test with source binding tests**

Remove `lateRegistryRequestCannotOverwriteNewerCommittedSource`; generation ownership moves to the aggregator. Add:

```java
@Test
public void loadedItemsRemainBoundToTheirSourceAndManager(@TempDir Path localHome) throws Exception {
    try (RegistryFixture fixture = RegistryFixture.start("Bound Store", "dev.hmclce.bound")) {
        PluginSource source = new PluginSource(
                "source_bound", fixture.registryUrl(), "Bound", true, false);
        PluginStoreManager manager = new PluginStoreManager();

        manager.loadSource(source);
        PluginStoreItem item = manager.getStoreItems().get(0);

        assertEquals(source, item.getSource());
        assertEquals("Bound Store", item.getRegistry().getName());
        assertSame(manager, item.getSourceManager());
    }
}
```

Add malformed registry, duplicate plugin ID, unsupported manifest scheme, and invalid manifest URL tests. Keep all package and README tests unchanged.

- [ ] **Step 2: Run the manager test and verify the new test fails**

```powershell
.\gradlew.bat :HMCL:test --tests "org.jackhuang.hmcl.plugin.store.PluginStoreManagerTest"
```

Expected: compilation fails because `loadSource` and source-bound item accessors do not exist.

- [ ] **Step 3: Remove preference and source-list state from the manager**

Delete `preferences`, `registryUrls`, `commitActiveRegistry`, `addCustomRegistry`, `setActiveRegistryUrl`, `getRegistryUrls`, and favorite facade methods. Keep the no-argument constructor. Track the current source only after successful registry validation:

```java
private @Nullable PluginSource source;
private @Nullable PluginStoreRegistry registry;

public void loadSource(PluginSource source) throws IOException {
    loadRegistryForRequest(source.getUrl());
    this.source = source;
}

public PluginSource getSource() {
    return Objects.requireNonNull(source, "Plugin source is not loaded");
}
```

Keep `getRegistryUrl()` as a convenience that returns `getSource().getUrl()`. Change `loadDefaultRegistry()` to construct the fixed official source without persistence.

Change `validateRemoteUrl` from private to package-visible static so preferences and tests reuse the authoritative policy:

```java
static void validateRemoteUrl(String url, String purpose) throws IOException {
    // Keep the existing URI, HTTPS, and loopback checks unchanged.
}
```

- [ ] **Step 4: Bind every store item to the producing source context**

Expand `PluginStoreItem`:

```java
private final PluginSource source;
private final PluginStoreRegistry registry;
private final PluginStoreManager sourceManager;
private final PluginStoreRegistry.PluginStoreEntry entry;
private final @Nullable PluginStoreManifest manifest;

public PluginStoreItem(PluginSource source, PluginStoreRegistry registry,
                       PluginStoreManager sourceManager,
                       PluginStoreRegistry.PluginStoreEntry entry,
                       @Nullable PluginStoreManifest manifest) {
    this.source = source;
    this.registry = registry;
    this.sourceManager = sourceManager;
    this.entry = entry;
    this.manifest = manifest;
}
```

Construct items only after both `source` and `registry` exist. Add documented getters. Do not expose mutable caches.

- [ ] **Step 5: Run all store manager security tests**

```powershell
.\gradlew.bat :HMCL:test --tests "org.jackhuang.hmcl.plugin.store.PluginStoreManagerTest" --tests "org.jackhuang.hmcl.plugin.store.PluginStoreManifestTest"
```

Expected: all tests pass, including redirect chains, loopback restrictions, response size limits, package SHA-256, and package metadata drift.

- [ ] **Step 6: Commit the source-scoped client**

```bash
git add HMCL/src/main/java/org/jackhuang/hmcl/plugin/store/PluginStoreManager.java \
  HMCL/src/main/java/org/jackhuang/hmcl/plugin/store/PluginStoreItem.java \
  HMCL/src/test/java/org/jackhuang/hmcl/plugin/store/PluginStoreManagerTest.java
git commit -m "refactor: scope plugin store clients to one source"
```

### Task 3: Aggregate enabled sources with deterministic winners

**Files:**
- Create: `HMCL/src/main/java/org/jackhuang/hmcl/plugin/store/PluginSourceLoadResult.java`
- Create: `HMCL/src/main/java/org/jackhuang/hmcl/plugin/store/PluginStoreSnapshot.java`
- Create: `HMCL/src/main/java/org/jackhuang/hmcl/plugin/store/PluginStoreAggregator.java`
- Create: `HMCL/src/test/java/org/jackhuang/hmcl/plugin/store/PluginStoreAggregatorTest.java`

**Interfaces:**
- Consumes: ordered `List<PluginSource>`
- Produces: `CompletableFuture<PluginStoreSnapshot> refresh(List<PluginSource>)`
- Produces: source results, winning items, conflicts, failures, and generation state

- [ ] **Step 1: Write aggregation, failure, generation, and concurrency tests**

Use local `HttpServer`, `CountDownLatch`, and `AtomicInteger`. Include this conflict test:

```java
@Test
public void sourcePriorityWinsRegardlessOfVersion(@TempDir Path localHome) throws Exception {
    try (RegistryFixture high = RegistryFixture.start("High", "dev.test.same", "1.0.0");
         RegistryFixture low = RegistryFixture.start("Low", "dev.test.same", "99.0.0");
         PluginStoreAggregator aggregator = new PluginStoreAggregator()) {
        PluginSource highSource = source("high", high.registryUrl(), true);
        PluginSource lowSource = source("low", low.registryUrl(), true);

        PluginStoreSnapshot snapshot = aggregator.refresh(
                List.of(highSource, lowSource)).get(5, TimeUnit.SECONDS);

        assertEquals("high", snapshot.getWinningItems()
                .get("dev.test.same").getSource().getId());
        assertEquals(List.of("low"), snapshot.getConflictCandidates()
                .get("dev.test.same").stream()
                .map(item -> item.getSource().getId()).toList());
    }
}
```

Add tests for two unique sources, reordered winner, high-priority failure with degraded lower winner, all failures, one manifest failure, disabled sources making no requests, stale generation, and maximum active requests no greater than configured concurrency.

- [ ] **Step 2: Run aggregator tests and verify they fail**

```powershell
.\gradlew.bat :HMCL:test --tests "org.jackhuang.hmcl.plugin.store.PluginStoreAggregatorTest"
```

Expected: compilation fails because aggregate result types do not exist.

- [ ] **Step 3: Implement immutable source results and snapshots**

Represent source state explicitly:

```java
public enum Status {
    DISABLED,
    SUCCESS,
    PARTIAL_FAILURE,
    FAILED
}
```

`PluginSourceLoadResult` stores the source, status, duration in milliseconds, item list, partial manifest failure count, optional registry, optional manager, and optional failure. Keep the full `@Nullable IOException` for logs and source details; derive a sanitized compact message without credentials or complete query strings for rows and banners. Enforce valid field combinations in factory methods.

Build snapshot maps in source priority order:

```java
Map<String, PluginStoreItem> winners = new LinkedHashMap<>();
Map<String, List<PluginStoreItem>> conflicts = new LinkedHashMap<>();
for (PluginSourceLoadResult result : sourceResults) {
    if (!result.isSuccessful()) {
        continue;
    }
    for (PluginStoreItem item : result.getItems()) {
        PluginStoreItem previous = winners.putIfAbsent(item.getEntry().getId(), item);
        if (previous != null) {
            conflicts.computeIfAbsent(item.getEntry().getId(), ignored -> new ArrayList<>())
                    .add(item);
        }
    }
}
```

Copy nested conflict lists before exposing them.

- [ ] **Step 4: Implement bounded loading and the publication gate**

Use a dedicated daemon pool and an atomic generation. Build threads with a named daemon `ThreadFactory` such as `plugin-store-source-%d`, and call `shutdownNow()` from `close()`:

```java
private final ExecutorService executor;
private final AtomicLong generation = new AtomicLong();
private final AtomicReference<@Nullable PluginStoreSnapshot> currentSnapshot =
        new AtomicReference<>();

public CompletableFuture<PluginStoreSnapshot> refresh(@Unmodifiable List<PluginSource> sources) {
    long requestGeneration = generation.incrementAndGet();
    List<PluginSource> snapshot = List.copyOf(sources);
    List<CompletableFuture<PluginSourceLoadResult>> requests = snapshot.stream()
            .map(source -> source.isEnabled()
                    ? CompletableFuture.supplyAsync(() -> load(source), executor)
                    : CompletableFuture.completedFuture(PluginSourceLoadResult.disabled(source)))
            .toList();
    return CompletableFuture.allOf(requests.toArray(CompletableFuture[]::new))
            .thenApply(ignored -> publishIfCurrent(requestGeneration, snapshot, requests));
}
```

Package-private constructors may inject concurrency and a `Function<PluginSource, PluginStoreManager>` client factory for deterministic tests. `publishIfCurrent` may return the stale request's computed snapshot to its caller, but it updates `currentSnapshot` only when the generation still matches.

- [ ] **Step 5: Run aggregator and manager tests**

```powershell
.\gradlew.bat :HMCL:test --tests "org.jackhuang.hmcl.plugin.store.PluginStoreAggregatorTest" --tests "org.jackhuang.hmcl.plugin.store.PluginStoreManagerTest"
```

Expected: all tests pass and the concurrency test observes at most four active source requests.

- [ ] **Step 6: Commit the aggregate catalog core**

```bash
git add HMCL/src/main/java/org/jackhuang/hmcl/plugin/store/PluginSourceLoadResult.java \
  HMCL/src/main/java/org/jackhuang/hmcl/plugin/store/PluginStoreSnapshot.java \
  HMCL/src/main/java/org/jackhuang/hmcl/plugin/store/PluginStoreAggregator.java \
  HMCL/src/test/java/org/jackhuang/hmcl/plugin/store/PluginStoreAggregatorTest.java
git commit -m "feat: aggregate enabled plugin sources"
```

### Task 4: Resolve dependencies from catalog winners only

**Files:**
- Create: `HMCL/src/main/java/org/jackhuang/hmcl/plugin/store/PluginStoreDependencyResolver.java`
- Modify: `HMCL/src/main/java/org/jackhuang/hmcl/plugin/store/PluginStoreManager.java:262-770`
- Modify: `HMCL/src/main/java/org/jackhuang/hmcl/plugin/store/PluginInstallPlan.java:189-360`
- Modify: `HMCL/src/test/java/org/jackhuang/hmcl/plugin/store/PluginStoreDependencyResolverTest.java`

**Interfaces:**
- Consumes: `Map<String, PluginStoreItem> winningItems`
- Produces: `PluginInstallPlan` whose downloadable entries carry source ID, source display name, and source manager
- Preserves: exact installed-artifact and permission reuse checks

- [ ] **Step 1: Add winner-only and source identity tests**

Add a fixture where the winning dependency cannot satisfy `>=2.0.0` but a conflict candidate can. Construct the resolver only with winners and assert failure:

```java
PluginStoreDependencyResolver resolver = new PluginStoreDependencyResolver(
        snapshot.getWinningItems());
IOException failure = assertThrows(IOException.class, () -> resolver.resolveInstallPlan(
        "dev.test.root", rootVersion, installedManifests,
        installedArtifactIdentities, reusableArtifacts));
assertTrue(failure.getMessage().contains("dev.test.dependency"));
assertTrue(failure.getMessage().contains("High Priority"));
```

Add source identity assertions for root and transitive `INSTALL` and `UPDATE` entries. Assert `REUSE` entries have no remote source.

- [ ] **Step 2: Run resolver tests and verify they fail**

```powershell
.\gradlew.bat :HMCL:test --tests "org.jackhuang.hmcl.plugin.store.PluginStoreDependencyResolverTest"
```

Expected: compilation fails because the new resolver and source fields do not exist.

- [ ] **Step 3: Extract the existing resolver without changing its algorithm**

Move candidate selection, constraint collection, backtracking, cycle detection, reverse dependent validation, and dependency-first plan ordering from `PluginStoreManager` into `PluginStoreDependencyResolver`. Replace registry lookup with winner lookup:

```java
private PluginStoreItem requireWinningItem(String pluginId) throws IOException {
    @Nullable PluginStoreItem item = winningItems.get(pluginId);
    if (item == null) {
        throw new IOException("Plugin is not published by an enabled source: " + pluginId);
    }
    if (item.getManifest() == null) {
        throw new IOException("Plugin manifest is unavailable from "
                + item.getSourceDisplayName() + ": " + pluginId);
    }
    return item;
}
```

Do not pass conflicts to the resolver. Keep compatibility checks delegated to the winning item's source manager.

- [ ] **Step 4: Carry source identity in downloadable plan entries**

Add nullable fields to `PluginInstallPlan.Entry`:

```java
private final @Nullable String sourceId;
private final @Nullable String sourceDisplayName;
private final @Nullable PluginStoreManager sourceManager;
```

Require all three fields for `INSTALL` and `UPDATE`; require all three to be null for `REUSE`. Add documented accessors and `requireSourceManager()` for download code:

```java
public PluginStoreManager requireSourceManager() {
    if (!requiresDownload() || sourceManager == null) {
        throw new IllegalStateException("Plan entry has no downloadable source: " + pluginId);
    }
    return sourceManager;
}
```

- [ ] **Step 5: Run resolver, manager, and manifest tests**

```powershell
.\gradlew.bat :HMCL:test --tests "org.jackhuang.hmcl.plugin.store.PluginStoreDependencyResolverTest" --tests "org.jackhuang.hmcl.plugin.store.PluginStoreManagerTest" --tests "org.jackhuang.hmcl.plugin.store.PluginStoreManifestTest"
```

Expected: all existing backtracking, reverse-constraint, exact-artifact reuse, and permission tests remain green.

- [ ] **Step 6: Commit winner-only dependency planning**

```bash
git add HMCL/src/main/java/org/jackhuang/hmcl/plugin/store/PluginStoreDependencyResolver.java \
  HMCL/src/main/java/org/jackhuang/hmcl/plugin/store/PluginStoreManager.java \
  HMCL/src/main/java/org/jackhuang/hmcl/plugin/store/PluginInstallPlan.java \
  HMCL/src/test/java/org/jackhuang/hmcl/plugin/store/PluginStoreDependencyResolverTest.java
git commit -m "feat: resolve plugin dependencies from source winners"
```

### Task 5: Add the independent compact source management page

**Files:**
- Create: `HMCL/src/main/java/org/jackhuang/hmcl/ui/main/PluginSourceManagementPage.java`
- Create: `HMCL/src/test/java/org/jackhuang/hmcl/ui/main/PluginSourceManagementPageTest.java`
- Modify: `HMCL/src/main/java/org/jackhuang/hmcl/ui/main/PluginStorePage.java:198-274,333-368,1415-1420,1841-1910`

**Interfaces:**
- Consumes: shared `PluginSourceRepository`, current snapshot supplier, and refresh callback
- Produces: source add, edit, delete, enable, disable, test, details, and reorder interactions
- Produces: navigation from the store toolbar to source management

- [ ] **Step 1: Extract and test pure source presentation rules**

Place package-visible helpers on `PluginSourceManagementPage` or in a nested immutable presentation model. Test:

```java
@Test
public void sourceNameUsesAliasThenRemoteNameThenCompactFallback() {
    PluginSource source = new PluginSource(
            "source_one", "https://plugins.example.org/catalog/plugins.json",
            "My plugins", true, false);

    assertEquals("My plugins", PluginSourceManagementPage.displayName(source, "Remote"));
    assertEquals("Remote", PluginSourceManagementPage.displayName(
            source.withConfiguration(source.getUrl(), null), "Remote"));
    assertEquals("plugins.example.org / plugins.json",
            PluginSourceManagementPage.displayName(
                    source.withConfiguration(source.getUrl(), null), null));
}

@Test
public void officialActionsExcludeEditAndDelete() {
    assertEquals(Set.of(Action.TEST, Action.DETAILS),
            PluginSourceManagementPage.secondaryActions(PluginSource.official(true)));
}
```

Also test that compact row title/subtitle models do not contain full URLs, while details models do.

- [ ] **Step 2: Run source management page tests and verify they fail**

```powershell
.\gradlew.bat :HMCL:test --tests "org.jackhuang.hmcl.ui.main.PluginSourceManagementPageTest"
```

Expected: compilation fails because the page and presentation helpers do not exist.

- [ ] **Step 3: Build the independent page and compact rows**

Create a `VBox implements DecoratorPage` using `ComponentList` and `LineButton`. Inject dependencies:

```java
public PluginSourceManagementPage(
        PluginSourceRepository repository,
        Supplier<@Nullable PluginStoreSnapshot> snapshotSupplier,
        Supplier<@Unmodifiable Set<String>> installedPluginIdsSupplier,
        Runnable refreshStore
) {
    this.repository = repository;
    this.snapshotSupplier = snapshotSupplier;
    this.installedPluginIdsSupplier = installedPluginIdsSupplier;
    this.refreshStore = refreshStore;
    buildPage();
    refreshRows();
}
```

Each row includes a priority label, source name, optional remote name, official or third-party label, status, plugin count, duration, a `JFXToggleButton`, and overflow actions. Disable row mutations while a repository write is running. Apply a UI mutation only after persistence succeeds; on `IOException`, rebuild from `repository.getSources()` and call `PluginDialogs.showError`.

Use a reorder control that works with keyboard commands in addition to drag gestures. Move operations submit an exact ID list to `repository.reorder` before rebuilding rows and invoking `refreshStore`.

- [ ] **Step 4: Implement validate-before-save add and edit dialogs**

Use a request-scoped manager for preview:

```java
Task.supplyAsync(() -> {
    PluginStoreManager previewManager = new PluginStoreManager();
    PluginSource previewSource = new PluginSource(
            "preview", normalizedUrl, alias, true, false);
    previewManager.loadSource(previewSource);
    return new SourcePreview(previewSource, previewManager.getRegistry(),
            previewManager.getStoreItems().size());
}).whenComplete(Schedulers.javafx(), this::showPreviewOrError).start();
```

Only the preview confirmation callback calls `repository.addSource` or `repository.updateSource`. A failed preview leaves repository state unchanged and keeps the input dialog available. Alias-only edits may persist without network access.

Use `PluginDialogs.confirmAction` before delete. Compare `installedPluginIdsSupplier.get()` with the source's latest successful snapshot items. If any IDs match, use the stronger confirmation text that installed plugins remain installed and updates may be affected; otherwise use the ordinary removal text. The official row never constructs edit or delete actions.

- [ ] **Step 5: Replace the old source selector and dialog with navigation**

Delete `sourceBox`, `sourceNames`, `updatingSourceSelection`, `RegistrySourceDialog`, and related helper code from `PluginStorePage`. Add an enabled-source summary label and a **Manage plugin sources** toolbar button. Navigate through the global decorator controller, matching `PluginStorePage.showPluginDetails`:

```java
private void showSourceManagement() {
    Controllers.navigate(new PluginSourceManagementPage(
            sourceRepository,
            () -> currentSnapshot,
            () -> Set.copyOf(installedManifests.keySet()),
            this::loadPluginStore
    ));
}
```

Keep the store list itself on the current page; Task 6 replaces its data source.

- [ ] **Step 6: Run source page and existing store UI tests**

```powershell
.\gradlew.bat :HMCL:test --tests "org.jackhuang.hmcl.ui.main.PluginSourceManagementPageTest" --tests "org.jackhuang.hmcl.ui.main.PluginStorePageTest"
```

Expected: source presentation and action tests pass. Existing selector tests are replaced with source summary and compact fallback tests.

- [ ] **Step 7: Commit the source manager page**

```bash
git add HMCL/src/main/java/org/jackhuang/hmcl/ui/main/PluginSourceManagementPage.java \
  HMCL/src/main/java/org/jackhuang/hmcl/ui/main/PluginStorePage.java \
  HMCL/src/test/java/org/jackhuang/hmcl/ui/main/PluginSourceManagementPageTest.java \
  HMCL/src/test/java/org/jackhuang/hmcl/ui/main/PluginStorePageTest.java
git commit -m "feat: add plugin source management page"
```

### Task 6: Present and install from aggregate snapshots

**Files:**
- Modify: `HMCL/src/main/java/org/jackhuang/hmcl/ui/main/PluginStorePage.java:118-179,385-840,1062-1388,1449-1663`
- Modify: `HMCL/src/main/java/org/jackhuang/hmcl/ui/main/PluginDialogs.java:60-84,225-332`
- Modify: `HMCL/src/test/java/org/jackhuang/hmcl/ui/main/PluginStorePageTest.java`

**Interfaces:**
- Consumes: `PluginStoreAggregator`, `PluginStoreSnapshot`, and `PluginStoreDependencyResolver`
- Produces: aggregate browsing, conflict/source labels, degraded-state warnings, and source-correct downloads

- [ ] **Step 1: Write aggregate page-state and install-summary tests**

Extract package-visible presentation helpers and add tests for:

```java
@Test
public void partialSourceFailureProducesVisibleDegradedState() {
    StorePresentation presentation = PluginStorePage.presentationFor(degradedSnapshot());

    assertEquals(StoreState.DEGRADED, presentation.state());
    assertTrue(presentation.showPluginRows());
    assertEquals(1, presentation.failedSourceCount());
}

@Test
public void everyDownloadedPlanRowIdentifiesItsWinningSource() {
    List<String> rows = PluginStorePage.formatInstallPlan(planWithTwoRemoteSources());

    assertTrue(rows.get(0).contains("Source A"));
    assertTrue(rows.get(1).contains("Source B"));
}
```

Cover no enabled sources, all sources failed, conflicts, source badge, prior snapshot visibility during refresh, stale completion, favorites keyed only by ID, and installed-state preservation after source removal.

- [ ] **Step 2: Run store page tests and verify they fail**

```powershell
.\gradlew.bat :HMCL:test --tests "org.jackhuang.hmcl.ui.main.PluginStorePageTest"
```

Expected: compilation fails because aggregate presentation helpers and source-aware plan formatting do not exist.

- [ ] **Step 3: Replace single-manager fields and refresh flow**

Use one preferences instance and one aggregator:

```java
private final PluginStorePreferences sourceRepository =
        new PluginStorePreferences(Metadata.HMCL_LOCAL_HOME);
private final PluginStoreAggregator storeAggregator = new PluginStoreAggregator();
private @Nullable PluginStoreSnapshot currentSnapshot;
private boolean storeRefreshInProgress;
```

Refresh without clearing an existing snapshot:

```java
private void loadPluginStore() {
    storeRefreshInProgress = true;
    loadingSpinner.setVisible(currentSnapshot == null);
    refreshInstalledManifests();
    storeAggregator.refresh(sourceRepository.getSources())
            .whenComplete((snapshot, failure) -> Platform.runLater(() -> {
                if (failure != null) {
                    storeRefreshInProgress = false;
                    showAggregateFailure(failure);
                    return;
                }
                if (!storeAggregator.isCurrent(snapshot.getGeneration())) {
                    return;
                }
                currentSnapshot = snapshot;
                allItems.clear();
                allItems.addAll(snapshot.getWinningItems().values());
                storeRefreshInProgress = false;
                refreshCategories();
                applyFilter();
            }));
}
```

Adapt to the project's `Task` and `Schedulers.javafx()` style if direct `CompletableFuture` callbacks do not satisfy JavaFX thread rules. The publication check remains mandatory.

- [ ] **Step 4: Render aggregate states and source provenance**

Filter only `snapshot.getWinningItems().values()`. Read favorites from `sourceRepository`. Add state branches:

```java
if (enabledSourceCount == 0) {
    showManageSourcesMessage();
} else if (snapshot.getWinningItems().isEmpty() && !snapshot.getSourceFailures().isEmpty()) {
    showAllSourcesFailedMessage(snapshot);
} else {
    showWinningItems(snapshot);
    degradedBanner.setVisible(snapshot.hasEnabledSourceFailures());
    conflictBanner.setVisible(!snapshot.getConflictCandidates().isEmpty());
}
```

Add winning source to plugin row subtitles and details. Add a conflict badge and a details section listing candidate source names and versions. Do not offer a per-plugin override.

- [ ] **Step 5: Resolve and download through the winning source**

Construct `PluginStoreDependencyResolver` from `currentSnapshot.getWinningItems()`. Replace the global manager download call:

```java
PluginStoreManager sourceManager = entry.requireSourceManager();
Path staged = sourceManager.downloadPluginToStaging(
        entry.getPluginId(),
        Objects.requireNonNull(entry.getRemoteVersion()),
        stagingDirectory
);
```

Use each item's manager for README and compatibility calculations. The page must not retain a “last loaded manager.” Add source display names to every `INSTALL` and `UPDATE` row.

- [ ] **Step 6: Show degraded-result warnings in permission review**

Extend `PluginDialogs.confirmPluginInstall` with `@Nullable String catalogWarning`. When present, add a warning `HintPane` above the plan list. Build this warning only when the accepted snapshot has source failures. It states that unavailable higher-priority sources may have changed winners and lists failed source display names without full URLs.

- [ ] **Step 7: Run store, resolver, and manager tests**

```powershell
.\gradlew.bat :HMCL:test --tests "org.jackhuang.hmcl.ui.main.PluginStorePageTest" --tests "org.jackhuang.hmcl.plugin.store.PluginStoreDependencyResolverTest" --tests "org.jackhuang.hmcl.plugin.store.PluginStoreAggregatorTest" --tests "org.jackhuang.hmcl.plugin.store.PluginStoreManagerTest"
```

Expected: aggregate states, source-aware downloads, generation checks, conflict policy, and existing security tests pass.

- [ ] **Step 8: Commit aggregate store integration**

```bash
git add HMCL/src/main/java/org/jackhuang/hmcl/ui/main/PluginStorePage.java \
  HMCL/src/main/java/org/jackhuang/hmcl/ui/main/PluginDialogs.java \
  HMCL/src/test/java/org/jackhuang/hmcl/ui/main/PluginStorePageTest.java
git commit -m "feat: present aggregated plugin catalog"
```

### Task 7: Localize, verify, and document the completed behavior

**Files:**
- Modify: `HMCL/src/main/resources/assets/lang/I18N.properties`
- Modify: `HMCL/src/main/resources/assets/lang/I18N_zh_CN.properties`
- Modify: `HMCL/src/main/resources/assets/lang/I18N_zh.properties`
- Modify: `HMCL/src/test/java/org/jackhuang/hmcl/ui/main/PluginStorePageTest.java`
- Modify: `docs/PLUGIN_SYSTEM.md:203-210`

**Interfaces:**
- Consumes: final source manager and aggregate UI
- Produces: complete required locale keys, user documentation, and full regression evidence

- [ ] **Step 1: Add a failing localization completeness test**

Add a fixed key set to `PluginStorePageTest`:

```java
@Test
public void everyPluginSourceManagementKeyExists() {
    List<String> keys = List.of(
            "plugin.store.manage_sources",
            "plugin.store.sources.none_enabled",
            "plugin.store.sources.all_failed",
            "plugin.store.sources.degraded",
            "plugin.store.sources.conflicts",
            "plugin.store.source.official",
            "plugin.store.source.third_party",
            "plugin.store.source.status.unchecked",
            "plugin.store.source.status.loading",
            "plugin.store.source.status.loaded",
            "plugin.store.source.status.partial_failure",
            "plugin.store.source.status.failed",
            "plugin.store.source.preview.confirm",
            "plugin.store.source.delete.confirm",
            "plugin.store.install.degraded_sources_warning"
    );
    keys.forEach(key -> assertTrue(I18n.hasKey(key), key));
}
```

Expand the list with every key referenced by production code.

- [ ] **Step 2: Run localization tests and verify they fail**

```powershell
.\gradlew.bat :HMCL:test --tests "org.jackhuang.hmcl.ui.main.PluginStorePageTest.everyPluginSourceManagementKeyExists"
```

Expected: assertion failure for the first missing source-manager key.

- [ ] **Step 3: Add English, Simplified Chinese, and Traditional Chinese strings**

Add matching key sets to all three required bundles. Use concise labels such as:

```properties
plugin.store.manage_sources=Manage plugin sources
plugin.store.sources.none_enabled=No plugin sources are enabled
plugin.store.sources.degraded=Some plugin sources could not be loaded
plugin.store.sources.conflicts={0} plugin conflicts follow source priority
plugin.store.source.status.partial_failure=Loaded with {0} unavailable plugins
plugin.store.source.delete.confirm=Removing this source will not uninstall plugins, but it may affect future updates.
plugin.store.install.degraded_sources_warning=Some higher-priority sources are unavailable. Verify the selected source for each plugin before continuing.
```

Use equivalent natural Chinese strings in `I18N_zh_CN.properties` and `I18N_zh.properties`. Use the Unicode ellipsis `…` in loading labels.

- [ ] **Step 4: Document source management and conflict behavior**

Extend `docs/PLUGIN_SYSTEM.md` with a focused **Plugin sources** subsection. Document:

- Official source disable and deletion rules
- Validate-before-save custom sources
- Enabled-source aggregation
- Priority-based duplicate ID resolution
- Partial source failure and degraded results
- Source identity shown before installation

Do not repeat transport implementation details already covered by code comments.

- [ ] **Step 5: Run translation and focused test checks**

```powershell
.\gradlew.bat :HMCL:checkTranslations
.\gradlew.bat :HMCL:test --tests "org.jackhuang.hmcl.plugin.store.*" --tests "org.jackhuang.hmcl.ui.main.PluginStorePageTest" --tests "org.jackhuang.hmcl.ui.main.PluginSourceManagementPageTest"
```

Expected: translation checks and every plugin-store test pass with zero failures.

- [ ] **Step 6: Run the full Java 17 verification suite**

```powershell
.\gradlew.bat :HMCL:compileJava
.\gradlew.bat :HMCL:test
.\gradlew.bat :HMCL:check
.\gradlew.bat :HMCL:build
```

Expected: every command exits with code 0. If a command fails, retain the branch and investigate before reporting completion.

- [ ] **Step 7: Perform JavaFX manual acceptance checks**

Run HMCL with local registry fixtures and verify:

1. The official source can be disabled but not edited or deleted
2. Adding and editing a URL writes only after a successful preview
3. Two sources merge and duplicate IDs follow visible priority
4. Reordering changes plugin details, dependencies, and install source together
5. One failed source shows a degraded banner while successful plugins remain usable
6. One failed manifest shows a partial source state without hiding other plugins
7. Saving errors restore toggles, order, and fields
8. Keyboard controls can reorder and operate every row
9. Compact rows do not expose full URLs; details and edit controls do
10. Removing a source leaves installed packages installed
11. Rapid refresh, reorder, toggle, edit, and delete actions never publish stale state

Record the fixture URLs and observed outcome in the implementation summary. Do not commit local fixture files under `build/` or `.hmcl/`.

- [ ] **Step 8: Commit localization and documentation**

```bash
git add HMCL/src/main/resources/assets/lang/I18N.properties \
  HMCL/src/main/resources/assets/lang/I18N_zh_CN.properties \
  HMCL/src/main/resources/assets/lang/I18N_zh.properties \
  HMCL/src/test/java/org/jackhuang/hmcl/ui/main/PluginStorePageTest.java \
  docs/PLUGIN_SYSTEM.md
git commit -m "docs: explain aggregated plugin sources"
```

## Final review checklist

- [ ] Every requirement in `docs/superpowers/specs/2026-07-29-plugin-source-manager-design.md` maps to a task above
- [ ] `PluginStoreManager` contains no source-list or favorite persistence
- [ ] `PluginStoreAggregator` is the only component that chooses duplicate ID winners
- [ ] Dependency planning sees winners only
- [ ] Every downloaded plan entry carries its source manager and display name
- [ ] The official source cannot be deleted or redirected
- [ ] Failed source writes leave disk and memory unchanged
- [ ] Stale aggregate generations cannot publish snapshots or source status
- [ ] Source failures remain nonblocking unless all enabled sources fail
- [ ] Existing transport and package validation tests remain unchanged and green
- [ ] New Java code satisfies repository nullability, immutability, and `///` documentation requirements
- [ ] No remote push, pull request, or merge occurs without explicit authorization
