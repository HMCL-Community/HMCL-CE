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
package org.jackhuang.hmcl.ui.main;

import org.jackhuang.hmcl.plugin.store.PluginSource;
import org.jackhuang.hmcl.plugin.store.PluginSourceLoadResult;
import org.jackhuang.hmcl.plugin.store.PluginStoreItem;
import org.jackhuang.hmcl.plugin.store.PluginStoreManager;
import org.jackhuang.hmcl.plugin.store.PluginStoreRegistry;
import org.jackhuang.hmcl.plugin.store.PluginStoreSnapshot;
import org.jackhuang.hmcl.util.gson.JsonUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies compact source presentation and source-specific action availability.
@NotNullByDefault
public final class PluginSourceManagementPageTest {
    /// Prefers aliases and remote registry names before deriving a safe compact fallback from the source URL.
    @Test
    public void sourceNameUsesAliasThenRemoteNameThenCompactFallback() {
        PluginSource source = new PluginSource(
                "source_one", "https://plugins.example.org/catalog/plugins.json",
                "My plugins", true, false);

        assertEquals("My plugins", PluginSourceManagementPage.displayName(source, "Remote"));
        assertEquals("Remote", PluginSourceManagementPage.displayName(
                source.withConfiguration(source.getUrl(), null), "Remote"));
        assertEquals("plugins.example.org",
                PluginSourceManagementPage.displayName(
                        source.withConfiguration(source.getUrl(), null), null));
    }

    /// Exposes no configuration or deletion controls for the fixed official source.
    @Test
    public void officialActionsExcludeEditAndDelete() {
        PluginSource official = new PluginSource(
                PluginSource.OFFICIAL_ID,
                org.jackhuang.hmcl.plugin.store.PluginStoreManager.DEFAULT_REGISTRY_URL,
                null,
                true,
                true
        );
        assertEquals(Set.of(PluginSourceManagementPage.Action.TEST, PluginSourceManagementPage.Action.DETAILS),
                PluginSourceManagementPage.secondaryActions(official));
    }

    /// Protects the built-in Topic discovery identity from being edited into an arbitrary registry source.
    @Test
    public void topicActionsExcludeEditAndDelete() {
        PluginSource topic = new PluginSource(
                PluginSource.HMCLCE_TOPIC_ID,
                PluginStoreManager.GITHUB_TOPIC_API_URL,
                null,
                true,
                false
        );
        assertEquals(Set.of(PluginSourceManagementPage.Action.TEST, PluginSourceManagementPage.Action.DETAILS),
                PluginSourceManagementPage.secondaryActions(topic));
    }

    /// Replaces hostile aliases and remote registry names throughout compact rows and accessibility text.
    @Test
    public void compactRowsAndAccessibilitySanitizeHostileSourceLabels() {
        PluginSource source = new PluginSource(
                "source_one",
                "https://user:secret@plugins.example.org/catalog/plugins.json?token=secret#fragment",
                "https://user:secret@host/catalog?token=secret#fragment",
                true,
                false
        );
        String hostileRemoteName = "https://user:secret@host/catalog?token=secret#fragment";
        PluginSourceManagementPage.SourceRow row = PluginSourceManagementPage.sourceRow(source, hostileRemoteName, null);
        String accessibleText = PluginSourceManagementPage.sourceRowAccessibleText(
                PluginSourceManagementPage.accessibleSourceName(1, row.title()), row);

        assertEquals("plugins.example.org", row.title());
        assertFalse(row.subtitle().contains("secret"));
        assertFalse(row.subtitle().contains("token"));
        assertFalse(accessibleText.contains("secret"));
        assertFalse(accessibleText.contains("token"));
        assertFalse(accessibleText.contains("#fragment"));
    }

    /// Limits full URL disclosure to the configured source URL, never hostile aliases or remote registry names.
    @Test
    public void detailsAndPreviewSanitizeHostileAliasAndRegistryName() {
        PluginSource source = new PluginSource(
                "source_one",
                "https://configured.example.org/catalog.json",
                "https://user:alias-secret@host.example/catalog?token=alias-secret#fragment",
                true,
                false
        );
        String remoteName = "https://user:remote-secret@host.example/catalog?token=remote-secret#fragment";
        String hostileDescription = "Mirror https://user:description-secret@host.example/catalog?token=description-secret#fragment";
        PluginSourceLoadResult result = successfulResult(source, "dev.hmclce.description");
        String details = PluginSourceManagementPage.sourceDetails(source, remoteName,
                PluginSourceLoadResult.success(
                        source,
                        result.getDurationMillis(),
                        result.getItems(),
                        result.getPartialManifestFailureCount(),
                        org.jackhuang.hmcl.util.gson.JsonUtils.GSON.fromJson("""
                                {
                                  "schemaVersion": 1,
                                  "name": "Registry",
                                  "description": "%s",
                                  "plugins": []
                                }
                                """.formatted(hostileDescription),
                                org.jackhuang.hmcl.plugin.store.PluginStoreRegistry.class),
                        Objects.requireNonNull(result.getManager())
                )).message();
        String preview = PluginSourceManagementPage.previewMessage(
                source.getUrl(), source.getAlias(), remoteName, hostileDescription, null, 0);

        for (String text : List.of(details, preview)) {
            assertFalse(text.contains("alias-secret"));
            assertFalse(text.contains("remote-secret"));
            assertFalse(text.contains("token="));
            assertFalse(text.contains("#fragment"));
        }
        assertTrue(details.contains(source.getUrl()));
        assertTrue(preview.contains(source.getUrl()));
    }

    /// Keeps full URLs out of compact rows while retaining them in an explicit source details model.
    @Test
    public void compactRowsHideUrlsWhileDetailsRetainThem() {
        PluginSource source = new PluginSource(
                "source_one", "https://plugins.example.org/catalog/plugins.json?credential=secret",
                null, true, false);

        PluginSourceManagementPage.SourceRow row = PluginSourceManagementPage.sourceRow(source, "Remote", null);
        PluginSourceManagementPage.SourceDetails details = PluginSourceManagementPage.sourceDetails(source, "Remote", null);

        assertFalse(row.title().contains(source.getUrl()));
        assertFalse(row.subtitle().contains(source.getUrl()));
        assertTrue(details.url().contains(source.getUrl()));
    }

    /// Requires network validation for adds and URL changes but lets alias-only edits persist directly.
    @Test
    public void sourceSavesPreviewOnlyNewOrChangedUrls() {
        PluginSource source = new PluginSource(
                "source_one", "https://plugins.example.org/catalog/plugins.json", "Before", true, false);

        assertTrue(PluginSourceManagementPage.requiresPreview(null, source.getUrl()));
        assertTrue(PluginSourceManagementPage.requiresPreview(source, "https://plugins.example.org/changed.json"));
        assertFalse(PluginSourceManagementPage.requiresPreview(source, source.getUrl()));
    }

    /// Reserves compact count and duration fields before a source has produced its first load result.
    @Test
    public void compactRowsIncludeCountAndDurationBeforeFirstLoad() {
        PluginSource source = new PluginSource(
                "source_one", "https://plugins.example.org/catalog/plugins.json", null, true, false);

        PluginSourceManagementPage.SourceRow row = PluginSourceManagementPage.sourceRow(source, null, null);

        assertTrue(row.subtitle().contains(
                org.jackhuang.hmcl.util.i18n.I18n.i18n("plugin.store.source.details.plugins", 0)
        ));
        assertTrue(row.subtitle().endsWith("· -"));
    }

    /// Includes normalized source configuration and validated registry metadata in the save preview.
    @Test
    public void sourcePreviewIncludesConfigurationRegistryMetadataAndPluginCount() {
        assertEquals(
                String.join("\n",
                        org.jackhuang.hmcl.util.i18n.I18n.i18n(
                                "plugin.store.source.details.url",
                                "https://plugins.example.org/catalog/plugins.json"
                        ),
                        org.jackhuang.hmcl.util.i18n.I18n.i18n("plugin.store.source.details.alias", "Local"),
                        "Remote",
                        "A curated plugin registry",
                        org.jackhuang.hmcl.util.i18n.I18n.i18n(
                                "plugin.store.source.preview.homepage",
                                "plugins.example.org"
                        ),
                        org.jackhuang.hmcl.util.i18n.I18n.i18n("plugin.store.source.details.plugins", 3)
                ),
                PluginSourceManagementPage.previewMessage(
                        "https://plugins.example.org/catalog/plugins.json",
                        "Local",
                        "Remote",
                        "A curated plugin registry",
                        "plugins.example.org",
                        3
                )
        );
    }

    /// Prefers a configuration-matching manual source success over an older aggregate failure and its duration/count.
    @Test
    public void currentManualResultsOverrideSnapshotFailureCountAndDuration() {
        PluginSource source = new PluginSource(
                "source_one", "https://plugins.example.org/catalog/plugins.json", null, true, false);
        PluginSourceLoadResult aggregate = PluginSourceLoadResult.failed(source, 10, new IOException("aggregate failure"));
        PluginStoreRegistry registry = JsonUtils.GSON.fromJson("""
                {
                  "schemaVersion": 1,
                  "name": "Manual",
                  "plugins": [{
                    "id": "dev.hmclce.manual",
                    "name": "Manual",
                    "manifestUrl": "https://plugins.example.org/manifest.json"
                  }]
                }
                """, PluginStoreRegistry.class);
        PluginStoreManager manager = new PluginStoreManager();
        PluginStoreItem item = new PluginStoreItem(
                source, registry, manager, registry.getPlugins().get(0), null);
        PluginSourceLoadResult manual = PluginSourceLoadResult.success(
                source, 20, List.of(item), 1, registry, manager);
        PluginStoreSnapshot snapshot = new PluginStoreSnapshot(1, List.of(aggregate));

        Map<String, PluginSourceLoadResult> merged = PluginSourceManagementPage.mergeSourceResults(
                snapshot,
                Map.of(source.getId(), manual),
                List.of(source)
        );

        assertEquals(manual, merged.get(source.getId()));
        assertEquals(PluginSourceLoadResult.Status.PARTIAL_FAILURE, merged.get(source.getId()).getStatus());
        assertEquals(20, merged.get(source.getId()).getDurationMillis());
        assertEquals(1, merged.get(source.getId()).getItems().size());
    }

    /// Replaces a manual test result once a newer matching aggregate snapshot publishes.
    @Test
    public void newerAggregateResultsSupersedeManualResultsForRowsAndDeletionWarnings() {
        PluginSource source = new PluginSource(
                "source_one", "https://plugins.example.org/catalog/plugins.json", null, true, false);
        PluginSourceLoadResult manual = successfulResult(source, "dev.hmclce.manual");
        PluginSourceLoadResult aggregate = successfulResult(source, "dev.hmclce.aggregate");
        PluginStoreSnapshot snapshot = new PluginStoreSnapshot(2, List.of(aggregate));

        Map<String, PluginSourceLoadResult> merged = PluginSourceManagementPage.mergeSourceResults(
                snapshot,
                Map.of(source.getId(), manual),
                1,
                List.of(source)
        );

        assertEquals(aggregate, merged.get(source.getId()));
        assertTrue(PluginSourceManagementPage.sourceAffectsInstalledPlugins(
                source,
                Map.of(source.getId(), manual),
                1,
                snapshot,
                List.of(source),
                Set.of("dev.hmclce.aggregate")
        ));
        assertFalse(PluginSourceManagementPage.sourceAffectsInstalledPlugins(
                source,
                Map.of(source.getId(), manual),
                1,
                snapshot,
                List.of(source),
                Set.of("dev.hmclce.manual")
        ));
    }

    /// Discards aggregate and manual outcomes whose source URL no longer matches persisted configuration.
    @Test
    public void sourceResultMergeDiscardsOutcomesAfterSourceMutation() {
        PluginSource original = new PluginSource(
                "source_one", "https://plugins.example.org/catalog/plugins.json", null, true, false);
        PluginSource current = original.withConfiguration("https://plugins.example.org/changed.json", null);
        PluginSourceLoadResult stale = PluginSourceLoadResult.failed(original, 10, new IOException("stale"));

        assertTrue(PluginSourceManagementPage.mergeSourceResults(
                new PluginStoreSnapshot(1, List.of(stale)),
                Map.of(original.getId(), stale),
                List.of(current)
        ).isEmpty());
    }

    /// Uses current successful manual source data before an aggregate source result when checking deletion impact.
    @Test
    public void manualSourceItemsWinDeletionImpactSelection() {
        PluginSource source = new PluginSource(
                "source_one", "https://plugins.example.org/catalog/plugins.json", null, true, false);
        PluginSourceLoadResult manual = successfulResult(source, "dev.hmclce.manual");
        PluginSourceLoadResult aggregate = successfulResult(source, "dev.hmclce.aggregate");

        assertTrue(PluginSourceManagementPage.sourceAffectsInstalledPlugins(
                source,
                Map.of(source.getId(), manual),
                new PluginStoreSnapshot(1, List.of(aggregate)),
                List.of(source),
                Set.of("dev.hmclce.manual")
        ));
        assertFalse(PluginSourceManagementPage.sourceAffectsInstalledPlugins(
                source,
                Map.of(source.getId(), manual),
                new PluginStoreSnapshot(1, List.of(aggregate)),
                List.of(source),
                Set.of("dev.hmclce.aggregate")
        ));
    }

    /// Falls back to a current successful aggregate result when no manual result is available.
    @Test
    public void aggregateSourceItemsProvideDeletionImpactFallback() {
        PluginSource source = new PluginSource(
                "source_one", "https://plugins.example.org/catalog/plugins.json", null, true, false);
        PluginSourceLoadResult aggregate = successfulResult(source, "dev.hmclce.aggregate");

        assertTrue(PluginSourceManagementPage.sourceAffectsInstalledPlugins(
                source,
                Map.of(),
                new PluginStoreSnapshot(1, List.of(aggregate)),
                List.of(source),
                Set.of("dev.hmclce.aggregate")
        ));
    }

    /// Ignores manual outcomes whose URL no longer matches the configured source before checking deletion impact.
    @Test
    public void staleManualSourceItemsDoNotAffectDeletionWarning() {
        PluginSource source = new PluginSource(
                "source_one", "https://plugins.example.org/catalog/plugins.json", null, true, false);
        PluginSource stale = source.withConfiguration("https://plugins.example.org/changed.json", null);
        PluginSourceLoadResult staleManual = successfulResult(stale, "dev.hmclce.installed");

        assertFalse(PluginSourceManagementPage.sourceAffectsInstalledPlugins(
                source,
                Map.of(source.getId(), staleManual),
                null,
                List.of(source),
                Set.of("dev.hmclce.installed")
        ));
    }

    /// Falls back to a current aggregate result when the newest manual test result failed.
    @Test
    public void failedManualSourceResultFallsBackToAggregateDeletionImpact() {
        PluginSource source = new PluginSource(
                "source_one", "https://plugins.example.org/catalog/plugins.json", null, true, false);
        PluginSourceLoadResult failedManual = PluginSourceLoadResult.failed(
                source, 3, new IOException("manual failure"));
        PluginSourceLoadResult aggregate = successfulResult(source, "dev.hmclce.aggregate");

        assertTrue(PluginSourceManagementPage.sourceAffectsInstalledPlugins(
                source,
                Map.of(source.getId(), failedManual),
                new PluginStoreSnapshot(1, List.of(aggregate)),
                List.of(source),
                Set.of("dev.hmclce.aggregate")
        ));
    }

    /// Requires a selected current source item to intersect installed plugin IDs before using the strong warning.
    @Test
    public void installedPluginIntersectionControlsDeletionWarning() {
        PluginSource source = new PluginSource(
                "source_one", "https://plugins.example.org/catalog/plugins.json", null, true, false);
        PluginSourceLoadResult manual = successfulResult(source, "dev.hmclce.available");

        assertFalse(PluginSourceManagementPage.sourceAffectsInstalledPlugins(
                source,
                Map.of(source.getId(), manual),
                null,
                List.of(source),
                Set.of("dev.hmclce.installed")
        ));
    }

    /// Creates a successful one-item source result for manual and aggregate deletion-impact selection tests.
    ///
    /// @param source current source configuration
    /// @param pluginId source plugin ID
    /// @return successful source result containing the supplied plugin ID
    private static PluginSourceLoadResult successfulResult(PluginSource source, String pluginId) {
        PluginStoreRegistry registry = JsonUtils.GSON.fromJson("""
                {
                  "schemaVersion": 1,
                  "name": "Registry",
                  "plugins": [{
                    "id": "%s",
                    "name": "Plugin",
                    "manifestUrl": "https://plugins.example.org/manifest.json"
                  }]
                }
                """.formatted(pluginId), PluginStoreRegistry.class);
        PluginStoreManager manager = new PluginStoreManager();
        PluginStoreItem item = new PluginStoreItem(
                source, registry, manager, registry.getPlugins().get(0), null);
        return PluginSourceLoadResult.success(source, 1, List.of(item), 1, registry, manager);
    }

    /// Exposes explicit source diagnostics in details while retaining the full URL only there.
    @Test
    public void sourceDetailsIncludeAliasRegistryMetadataAndSourceDiagnostics() {
        PluginSource source = new PluginSource(
                "source_one", "https://plugins.example.org/catalog/plugins.json", "Local", true, false);
        PluginStoreRegistry registry = JsonUtils.GSON.fromJson("""
                {
                  "schemaVersion": 1,
                  "name": "Remote",
                  "description": "A curated plugin registry",
                  "homepageUrl": "https://plugins.example.org/home",
                  "plugins": []
                }
                """, PluginStoreRegistry.class);
        PluginStoreManager manager = new PluginStoreManager();
        PluginSourceLoadResult result = PluginSourceLoadResult.success(
                source, 42, List.of(), 0, registry, manager);
        PluginStoreSnapshot snapshot = new PluginStoreSnapshot(1, List.of(result));

        PluginSourceManagementPage.SourceDetails details = PluginSourceManagementPage.sourceDetails(
                source, "Remote", result, 2, snapshot);

        assertEquals("Local", details.title());
        assertTrue(details.message().contains(source.getUrl()));
        assertTrue(details.message().contains(
                org.jackhuang.hmcl.util.i18n.I18n.i18n("plugin.store.source.details.alias", "Local")
        ));
        assertTrue(details.message().contains(
                org.jackhuang.hmcl.util.i18n.I18n.i18n("plugin.store.source.details.registry", "Remote")
        ));
        assertTrue(details.message().contains(org.jackhuang.hmcl.util.i18n.I18n.i18n(
                "plugin.store.source.details.description", "A curated plugin registry"
        )));
        assertTrue(details.message().contains(
                org.jackhuang.hmcl.util.i18n.I18n.i18n("plugin.store.source.details.homepage", "plugins.example.org")
        ));
        assertTrue(details.message().contains(
                org.jackhuang.hmcl.util.i18n.I18n.i18n("plugin.store.source.details.priority", 2)
        ));
        assertTrue(details.message().contains(
                org.jackhuang.hmcl.util.i18n.I18n.i18n("plugin.store.source.details.duration", 42)
        ));
        assertTrue(details.message().contains(
                org.jackhuang.hmcl.util.i18n.I18n.i18n("plugin.store.source.details.plugins", 0)
        ));
        assertTrue(details.message().contains(org.jackhuang.hmcl.util.i18n.I18n.i18n(
                "plugin.store.source.details.partial_manifest_failures", 0
        )));
        assertTrue(details.message().contains(
                org.jackhuang.hmcl.util.i18n.I18n.i18n("plugin.store.source.details.conflicts", 0)
        ));
        assertTrue(details.message().contains(org.jackhuang.hmcl.util.i18n.I18n.i18n(
                "plugin.store.source.details.type",
                org.jackhuang.hmcl.util.i18n.I18n.i18n("plugin.store.source.third_party")
        )));
        assertTrue(details.message().contains(org.jackhuang.hmcl.util.i18n.I18n.i18n(
                "plugin.store.source.details.enabled",
                org.jackhuang.hmcl.util.i18n.I18n.i18n("plugin.enabled")
        )));
    }

    /// Keeps URL-bearing failure diagnostics out of compact rows while retaining them in details.
    @Test
    public void failedCompactRowsHideDiagnosticUrlWhileDetailsShowIt() {
        PluginSource source = new PluginSource(
                "source_one", "https://plugins.example.org/catalog/plugins.json", null, true, false);
        PluginSourceLoadResult failed = PluginSourceLoadResult.failed(
                source,
                7,
                new IOException("Cannot read https://plugins.example.org/catalog/plugins.json?token=secret")
        );

        PluginSourceManagementPage.SourceRow row = PluginSourceManagementPage.sourceRow(source, null, failed);
        PluginSourceManagementPage.SourceDetails details = PluginSourceManagementPage.sourceDetails(source, null, failed);

        assertFalse(row.subtitle().contains("https://"));
        assertTrue(row.subtitle().contains(
                org.jackhuang.hmcl.util.i18n.I18n.i18n("plugin.store.source.status.failed")
        ));
        assertTrue(details.message().contains(org.jackhuang.hmcl.util.i18n.I18n.i18n(
                "plugin.store.source.details.failure",
                "Cannot read https://plugins.example.org"
        )));
        assertTrue(details.message().contains(org.jackhuang.hmcl.util.i18n.I18n.i18n(
                "plugin.store.source.details.type",
                org.jackhuang.hmcl.util.i18n.I18n.i18n("plugin.store.source.third_party")
        )));
        assertTrue(details.message().contains(org.jackhuang.hmcl.util.i18n.I18n.i18n(
                "plugin.store.source.details.enabled",
                org.jackhuang.hmcl.util.i18n.I18n.i18n("plugin.enabled")
        )));
    }

    /// Presents separate enabled and source result statuses for compact source rows.
    @Test
    public void compactRowsRenderEnabledAndActualLoadStatuses() {
        PluginSource source = new PluginSource(
                "source_one", "https://plugins.example.org/catalog/plugins.json", null, false, false);
        PluginSourceLoadResult disabled = PluginSourceLoadResult.disabled(source);

        PluginSourceManagementPage.SourceRow unavailable = PluginSourceManagementPage.sourceRow(source, null, null);
        PluginSourceManagementPage.SourceRow loaded = PluginSourceManagementPage.sourceRow(source, null, disabled);

        assertTrue(unavailable.subtitle().contains(org.jackhuang.hmcl.util.i18n.I18n.i18n("plugin.disabled")));
        assertTrue(unavailable.subtitle().contains(
                org.jackhuang.hmcl.util.i18n.I18n.i18n("plugin.store.source.status.unchecked")
        ));
        assertTrue(loaded.subtitle().contains(org.jackhuang.hmcl.util.i18n.I18n.i18n("plugin.disabled")));
    }

    /// Rejects stale source-test completions after a newer request or source configuration mutation.
    @Test
    public void staleSourceTestsCannotPublishAfterNewerRequestOrConfigurationChange() {
        PluginSource source = new PluginSource(
                "source_one", "https://plugins.example.org/catalog/plugins.json", null, true, false);

        assertFalse(PluginSourceManagementPage.canPublishTestResult(
                source, List.of(source), 1, 2, 1, 1));
        assertFalse(PluginSourceManagementPage.canPublishTestResult(
                source, List.of(source), 2, 2, 1, 2));
        assertFalse(PluginSourceManagementPage.canPublishTestResult(
                source, List.of(source.withConfiguration("https://plugins.example.org/changed.json", null)),
                2, 2, 1, 1));
        assertFalse(PluginSourceManagementPage.canPublishTestResult(
                source, List.of(source.withConfiguration(source.getUrl(), "Renamed")),
                2, 2, 1, 1));
        assertFalse(PluginSourceManagementPage.canPublishTestResult(
                source, List.of(source.withEnabled(false)),
                2, 2, 1, 1));
        assertFalse(PluginSourceManagementPage.canPublishTestResult(
                source, List.of(new PluginSource(source.getId(), source.getUrl(), null, true, true)),
                2, 2, 1, 1));
        assertTrue(PluginSourceManagementPage.canPublishTestResult(
                source, List.of(source), 2, 2, 1, 1));
    }

    /// Creates localized, URL-safe source-row and source-specific action accessibility text.
    @Test
    public void accessibleTextUsesCompactSourceContentWithoutUrlCredentials() {
        PluginSource source = new PluginSource(
                "source_one",
                "https://user:secret@plugins.example.org/catalog/plugins.json?token=secret#fragment",
                null,
                false,
                false
        );
        PluginSourceManagementPage.SourceRow row = PluginSourceManagementPage.sourceRow(source, null, null);

        String accessibleName = PluginSourceManagementPage.accessibleSourceName(2, row.title());
        assertEquals(i18n("plugin.store.source.details.priority", 2) + ", " + row.title(), accessibleName);
        assertEquals(i18n("plugin.store.source.accessible.row", accessibleName, row.subtitle()),
                PluginSourceManagementPage.sourceRowAccessibleText(accessibleName, row));
        assertEquals(i18n("plugin.store.source.accessible.enable", accessibleName),
                PluginSourceManagementPage.sourceActionAccessibleText("enable", accessibleName));
        assertEquals(i18n("plugin.store.source.accessible.previous", accessibleName),
                PluginSourceManagementPage.sourceActionAccessibleText("previous", accessibleName));
        assertEquals(i18n("plugin.store.source.accessible.next", accessibleName),
                PluginSourceManagementPage.sourceActionAccessibleText("next", accessibleName));
        assertEquals(i18n("plugin.store.source.accessible.more", accessibleName),
                PluginSourceManagementPage.sourceActionAccessibleText("more", accessibleName));
        assertFalse(PluginSourceManagementPage.sourceRowAccessibleText(accessibleName, row).contains("secret"));
        assertFalse(PluginSourceManagementPage.sourceRowAccessibleText(accessibleName, row).contains("token="));
        assertFalse(PluginSourceManagementPage.sourceRowAccessibleText(accessibleName, row).contains("#fragment"));
        assertFalse(
                PluginSourceManagementPage.accessibleSourceName(1, "Duplicate")
                        .equals(PluginSourceManagementPage.accessibleSourceName(2, "Duplicate"))
        );
    }

    /// Moves a dragged source directly before its drop target while retaining every configured ID exactly once.
    @Test
    public void draggedSourceMovesBeforeDropTarget() {
        assertEquals(
                java.util.List.of("third", "first", "second"),
                PluginSourceManagementPage.reorderedIds(
                        java.util.List.of("first", "second", "third"),
                        "third",
                        "first"
                )
        );
    }

    /// Leaves the configured order untouched when a drag cannot identify both current source IDs.
    @Test
    public void invalidDraggedSourceLeavesOrderUnchanged() {
        assertEquals(
                java.util.List.of("first", "second"),
                PluginSourceManagementPage.reorderedIds(
                        java.util.List.of("first", "second"),
                        "missing",
                        "second"
                )
        );
    }

}
