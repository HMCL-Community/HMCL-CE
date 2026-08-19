# Manage and aggregate plugin sources

This design replaces the single active registry selector with a source manager and a multi-source plugin catalog. It defines source persistence, loading, conflict resolution, management UI, migration, failure behavior, and verification requirements.

## Plan

- **Content type**: Conceptual design specification
- **Goal**: Define an implementation-ready design for managing, validating, ordering, and aggregating multiple HMCL CE plugin sources
- **Audience**: HMCL CE maintainers implementing or reviewing the plugin store changes
- **Content plan**: Describe requirements, architecture, persistence, aggregation, UI, errors, migration, tests, and acceptance criteria
- **Open questions**: None
- **References**: `AGENTS.md`, `docs/PLUGIN_SYSTEM.md`, `PluginStoreManager`, `PluginStorePreferences`, and `PluginStorePage`

## Goals and constraints

The feature must let people add, edit, remove, enable, disable, test, and reorder plugin sources. The store loads every enabled source and presents one merged catalog.

The design preserves these existing guarantees:

- The official HMCL CE source always exists. People can disable it but cannot delete it or edit its URL
- Every registry, manifest, README, redirect, and package continues through the current transport and schema validation
- The store persists an active selection only after a successful request. A stale request cannot overwrite newer state
- A broken plugin manifest affects one plugin. A broken registry affects one source
- Favorites remain keyed by plugin ID
- Installed plugins remain installed when their source is disabled or deleted

The first version does not add persistent registry or manifest caches. It also does not add source signatures, publisher trust scores, automatic mirrors, source import or export, or separate entries for duplicate plugin IDs.

## Architecture

The implementation separates source configuration, single-source loading, aggregation, and presentation. This keeps network security rules out of the JavaFX page and keeps multi-source policy out of the existing single-source client.

### Source configuration model

Add an immutable `PluginSource` model with these fields:

- `id`: Stable local identifier
- `url`: Registry URL
- `alias`: Optional local display name
- `enabled`: Whether aggregation includes the source
- `official`: Whether this is the built-in source

List position defines priority. Lower indexes have higher priority. The official source uses a fixed ID and URL. Custom sources receive a generated stable ID when saved.

The display name follows this order:

1. Nonblank local alias
2. Last successfully loaded remote registry name
3. Compact host and path fallback

The remote name does not become persistent identity. Source IDs preserve identity when a person changes a custom source URL.

### Source repository

Extract source persistence from `PluginStorePreferences` behind a `PluginSourceRepository`. The repository owns these operations:

- Read and migrate source configuration
- Add a validated source
- Edit a custom source alias or URL
- Remove a custom source
- Enable or disable a source
- Reorder sources
- Persist a complete source list with a temporary file and atomic replacement

Every mutation validates the complete resulting list before writing. The repository rejects duplicate stable IDs, duplicate canonical URLs, a missing official source, an edited official URL, and deletion of the official source.

Keep favorite persistence in the same file and preserve the existing favorite API. Source mutations and favorite mutations must serialize through one repository instance so one write cannot discard another change.

### Single-source client

Keep `PluginStoreManager` responsible for one registry request and its artifacts. Reuse its existing rules for:

- HTTPS and loopback development URLs
- Redirect validation on every hop
- Response size limits
- Registry and manifest schema validation
- Registry entry to manifest ID binding
- Package size and Secure Hash Algorithm 256-bit (SHA-256) verification
- Package metadata cross-checks
- README sanitization and bounded loading

Refactor only where required to return source-scoped load results or to support dependency resolution against an aggregate catalog. Do not duplicate URL or artifact validation in the repository, aggregator, or page.

### Aggregation service

Add `PluginStoreAggregator` as the coordinator for enabled sources. It accepts an immutable source snapshot and returns an immutable `PluginStoreSnapshot`.

The aggregator performs these steps:

1. Assign a generation to the request
2. Load enabled sources with bounded concurrency
3. Record source metadata, duration, plugin count, partial manifest failures, or a source-level error
4. Traverse successful sources in priority order
5. Select the first occurrence of each plugin ID as the winning item
6. Attach later occurrences to that item as conflict candidates
7. Build the dependency lookup from winning items only
8. Publish the snapshot only if its generation remains current

Use a dedicated bounded executor rather than `ForkJoinPool.commonPool()`. Start with four concurrent source loads. Keep manifests within one source on the existing path for the first implementation unless measurements justify a separate bounded manifest pool.

The aggregator does not persist runtime health. A new process starts each source in an unchecked state.

### Aggregate snapshot

`PluginStoreSnapshot` contains:

- Ordered source results
- Winning plugin items keyed by plugin ID
- Conflict candidates keyed by plugin ID
- Source failures
- Whether any enabled source failed
- The generation that produced the snapshot

Each winning plugin item carries its source ID, source display name, registry metadata, entry, and optional manifest. Plugin details, dependency planning, installation, and update checks consume the same snapshot. They must not recalculate source priority independently.

## Conflict and dependency rules

Source order resolves duplicate plugin IDs. The highest-priority successful source wins, regardless of version numbers in lower-priority sources.

The store shows one normal entry for the winner and marks it when other enabled sources contain the same ID. Conflict details list the competing source, available version summary, and the reason it lost. The first version does not allow a per-plugin override.

Dependency resolution uses only winning entries. This rule prevents a dependency from silently selecting an artifact from a lower-priority source after the UI showed a different source. If the winning entry cannot satisfy a dependency, planning fails and explains the constraint. It does not fall through to a conflict candidate.

If a higher-priority source fails to load, a lower-priority source may temporarily win an ID. The snapshot is then degraded. The store banner and installation confirmation show that failed sources may have changed conflict outcomes, and the confirmation identifies the source that supplies each changed artifact.

## Persistence and migration

Add a schema version to `.hmcl/plugin-store.json`. The new source representation stores ordered source objects. Runtime health and remote registry metadata remain in memory.

A conceptual state shape is:

```json
{
  "schemaVersion": 2,
  "favoritePluginIds": ["dev.hmclce.pcltheme"],
  "sources": [
    {
      "id": "official",
      "url": "https://raw.githubusercontent.com/PCL-Nex-Developer/HMCL-CE-Plugin-Store/main/plugins.json",
      "alias": null,
      "enabled": true,
      "official": true
    },
    {
      "id": "source_7fc2c89b",
      "url": "https://plugins.example.org/registry.json",
      "alias": "Community plugins",
      "enabled": true,
      "official": false
    }
  ]
}
```

Migration applies these rules:

1. Insert the official source first
2. Convert each unique valid `customRegistryUrls` value into an enabled custom source in original order
3. Preserve every valid favorite ID
4. If `activeRegistryUrl` identifies a custom source, move that source directly after the official source to preserve the old preferred source as closely as possible
5. If the official source was the old active source, keep the converted order
6. Ignore invalid source URLs with a warning
7. Write schema version 2 only after the migrated state passes validation

Keep a best-effort backup of the version 1 file until the version 2 write succeeds. A malformed file falls back to the official source and logs the parsing failure, matching the current fail-safe behavior.

Canonical URL comparison normalizes scheme and host case, removes default ports, and normalizes an empty path to `/`. It does not reorder query parameters, remove fragments without validation, follow redirects, or change path case. The persisted URL remains the validated form entered by the person.

## Source management page

Add a dedicated page reachable from the plugin store toolbar. Replace the current source selector and URL-only dialog with an enabled-source summary and a **Manage plugin sources** action.

The page uses the existing `ComponentList` and `LineButton` visual language. Each compact row shows:

- Drag handle and priority position
- Local alias or remote name
- Remote name when an alias is active
- Official or third-party label
- Enabled toggle
- Latest in-process status
- Plugin count and response duration after a successful check
- A compact error and retry action after failure
- An overflow menu for allowed operations

Dragging a row persists its new order and starts a new aggregation. Toggling a source follows the same flow. The page disables destructive or reorder actions while that mutation is being saved.

The official row allows enable, disable, connection test, and details. It does not allow URL editing or deletion. A custom row also allows alias or URL editing and deletion.

### Add and edit flow

Adding a source follows this sequence:

1. Enter an HTTPS registry URL and optional local alias
2. Validate the URL with the existing remote URL policy
3. Load and validate the registry without persisting it
4. Show the remote name, description, homepage host, and plugin count
5. Confirm the preview
6. Save the source as enabled
7. Start a new aggregation

A failed validation keeps the dialog open and shows a specific error. It does not create a source record.

Editing an alias writes immediately after validation. Editing a URL repeats the full preview flow. If validation fails, the previous source remains unchanged. A successful URL edit keeps the stable source ID and priority.

Deleting a custom source requires confirmation. If installed plugins match entries last seen in that source, the confirmation explains that deletion does not uninstall them and may affect updates. Deletion starts a new aggregation after persistence succeeds.

### Source details

The details view shows the local alias, remote registry name, full URL, description, homepage, priority, current status, latest duration, plugin count, partial manifest failure count, and conflicts involving this source. Remote links use the existing safe external-link policy.

## Store presentation

The plugin store represents all enabled sources rather than one active source. Its toolbar includes search, favorites, refresh, category, an enabled-source summary, and the management action.

The page shows these states:

- **No enabled sources**: Explain that the catalog has no enabled sources and link to source management
- **All enabled sources failed**: Show a failure page with each source error and retry actions
- **Some sources failed**: Show successful plugins and a nonblocking degraded-results banner
- **Conflicts found**: Show a conflict summary link and a source badge on affected plugins
- **Manifest failed**: Keep the existing per-plugin unavailable state
- **Loading**: Keep prior results visible with a refresh indicator when possible. On first load, show the existing loading state

Plugin rows and details identify the winning source. Installation and update confirmation identify the source for every artifact that changes. The store never presents a lower-priority candidate as an alternative version of the winner.

## Error handling and consistency

Repository failures leave the previous in-memory and on-disk configuration unchanged. The UI reports the write error and restores the prior toggle, order, or field value.

A source-level load error records the root cause for source details and a concise message for the list. Other sources continue. A manifest-level error affects one plugin and contributes to the source's partial failure count.

Generation checks protect these transitions:

- Refresh followed by another refresh
- Source reorder during refresh
- Enable or disable during refresh
- URL edit during refresh
- Source deletion during refresh

Old tasks may complete, but they cannot publish a snapshot or runtime source status. Closing the page does not persist request results.

The implementation logs full exceptions without placing credentials or complete query strings in compact UI messages. Full source URLs remain visible only in explicit details, edit controls, tooltips, and accessibility text.

## Testing strategy

Use JUnit Jupiter, `@TempDir`, and local `HttpServer` fixtures to match existing tests. Avoid external network dependencies.

### Source repository tests

Cover these cases:

- Version 1 migration preserves favorites, source order, and old active preference
- Version 2 round-trip preserves stable IDs, aliases, enabled state, and order
- The official source cannot be removed or edited
- Duplicate canonical URLs are rejected
- A URL edit keeps source ID and priority
- Failed writes preserve the previous file
- Corrupt or partially valid state falls back without discarding valid favorites when recoverable

### Aggregator tests

Cover these cases:

- Multiple successful sources produce one merged catalog
- Source priority selects the duplicate ID winner
- Reordering changes the winner deterministically
- Version numbers do not override source priority
- Dependencies resolve from winning items only
- One source failure yields a degraded snapshot with successful sources
- All source failures yield an empty failed snapshot
- One manifest failure does not fail its source
- A stale generation cannot replace a newer snapshot
- Bounded concurrency never exceeds the configured source limit

### Manager and security regression tests

Retain all current redirect, loopback, size, schema, package identity, dependency, permission, and SHA-256 tests. Add direct registry validation tests for malformed schema, duplicate IDs, invalid entry URLs, and unsupported URL schemes.

### UI model and interaction tests

Extract pure helpers where possible and cover these cases:

- Display name precedence: alias, remote name, fallback
- Compact source rows never expose full URLs
- Official source actions omit edit and delete
- Add and URL edit persist only after successful preview
- Failed edits preserve old configuration
- Empty, partial failure, all-failure, and conflict states map to the correct presentation
- Conflict badges and installation summaries show the winning source
- Reorder and toggle actions restore state after persistence failure

Run the existing Gradle test suite and the Java 17 build. Manually verify drag ordering, keyboard navigation, screen-reader labels, dialogs, loading transitions, and narrow window behavior under JavaFX.

## Acceptance criteria

The feature is complete when all of these statements are true:

- The store can load and merge at least two enabled sources
- A person can add, validate, edit, remove, enable, disable, test, and reorder custom sources
- The official source can be disabled but not deleted or redirected
- Duplicate plugin IDs always follow visible source priority
- Plugin details, dependencies, installation, and updates use one consistent winning source
- A failed source does not hide successful sources
- Partial and degraded results remain visible and identify their limitations
- Invalid sources are not saved during add or URL edit
- Existing version 1 preferences migrate without losing favorites or custom URLs
- A stale request cannot overwrite newer source configuration or catalog state
- Existing transport and package security tests remain green
- New repository, aggregator, conflict, migration, and UI model tests pass
- The source manager matches the established HMCL `ComponentList` and compact row design language
