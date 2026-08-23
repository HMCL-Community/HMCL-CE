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
import org.jackhuang.hmcl.plugin.trust.PluginTrustLevel;
import org.jackhuang.hmcl.plugin.trust.PluginTrustVerifier;
import org.jackhuang.hmcl.util.gson.JsonUtils;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies bounded, source-priority plugin catalog aggregation against deterministic local registries.
@NotNullByDefault
public final class PluginStoreAggregatorTest {
    /// Bounded wait used by local-server concurrency coordination.
    private static final long TIMEOUT_SECONDS = 5;

    /// Certifies every community version when the official registry references the same plugin repository.
    @Test
    public void officialRepositoryReferenceCertifiesAllMatchingCommunityVersions() throws Exception {
        PluginStoreManager manager = new PluginStoreManager(PluginTrustVerifier.loadDefault());
        PluginSource officialSource = new PluginSource(
                PluginSource.OFFICIAL_ID,
                PluginStoreManager.DEFAULT_REGISTRY_URL,
                null,
                true,
                true
        );
        PluginSource communitySource = source("community", "https://community.example/plugins.json", true);
        PluginStoreRegistry officialRegistry = registry(
                "Official",
                "dev.test.referenced",
                "https://github.com/Example/Plugin.git"
        );
        PluginStoreRegistry communityRegistry = registry(
                "Community",
                "dev.test.referenced",
                "github.com/example/plugin"
        );
        PluginStoreManifest communityManifest = manifest("dev.test.referenced", "1.0.0", "2.0.0");
        PluginStoreItem officialItem = new PluginStoreItem(
                officialSource,
                officialRegistry,
                manager,
                officialRegistry.getPlugins().get(0),
                null,
                PluginTrustResult.official("ed25519:official")
        );
        PluginStoreItem communityItem = new PluginStoreItem(
                communitySource,
                communityRegistry,
                manager,
                communityRegistry.getPlugins().get(0),
                communityManifest,
                PluginTrustResult.community()
        );

        new PluginStoreSnapshot(1, List.of(
                PluginSourceLoadResult.success(officialSource, 1, List.of(officialItem), 1, officialRegistry, manager),
                PluginSourceLoadResult.success(communitySource, 1, List.of(communityItem), 0, communityRegistry, manager)
        ));

        assertTrue(communityManifest.getVersions().stream()
                .allMatch(version -> version.getTrust().level() == PluginTrustLevel.CERTIFIED));
    }

    /// Requires the official reference to match both plugin ID and normalized repository identity.
    @Test
    public void officialRepositoryReferenceDoesNotCertifyRepositoryMismatch() throws Exception {
        PluginStoreManager manager = new PluginStoreManager(PluginTrustVerifier.loadDefault());
        PluginSource officialSource = new PluginSource(
                PluginSource.OFFICIAL_ID,
                PluginStoreManager.DEFAULT_REGISTRY_URL,
                null,
                true,
                true
        );
        PluginSource communitySource = source("community", "https://community.example/plugins.json", true);
        PluginStoreRegistry officialRegistry = registry(
                "Official",
                "dev.test.collision",
                "https://github.com/example/reviewed"
        );
        PluginStoreRegistry communityRegistry = registry(
                "Community",
                "dev.test.collision",
                "https://github.com/example/unreviewed"
        );
        PluginStoreManifest communityManifest = manifest("dev.test.collision", "1.0.0");
        PluginStoreItem officialItem = new PluginStoreItem(
                officialSource,
                officialRegistry,
                manager,
                officialRegistry.getPlugins().get(0),
                null,
                PluginTrustResult.official("ed25519:official")
        );
        PluginStoreItem communityItem = new PluginStoreItem(
                communitySource,
                communityRegistry,
                manager,
                communityRegistry.getPlugins().get(0),
                communityManifest,
                PluginTrustResult.community()
        );

        new PluginStoreSnapshot(1, List.of(
                PluginSourceLoadResult.success(officialSource, 1, List.of(officialItem), 1, officialRegistry, manager),
                PluginSourceLoadResult.success(communitySource, 1, List.of(communityItem), 0, communityRegistry, manager)
        ));

        assertEquals(PluginTrustLevel.COMMUNITY, communityManifest.getVersions().get(0).getTrust().level());
    }

    /// Leaves community repositories uncertified when the official source did not load successfully.
    @Test
    public void failedOfficialSourceCannotCertifyCommunityRepository() throws Exception {
        PluginStoreManager manager = new PluginStoreManager(PluginTrustVerifier.loadDefault());
        PluginSource officialSource = new PluginSource(
                PluginSource.OFFICIAL_ID,
                PluginStoreManager.DEFAULT_REGISTRY_URL,
                null,
                true,
                true
        );
        PluginSource communitySource = source("community", "https://community.example/plugins.json", true);
        PluginStoreRegistry communityRegistry = registry(
                "Community",
                "dev.test.unreviewed",
                "https://github.com/example/unreviewed"
        );
        PluginStoreManifest communityManifest = manifest("dev.test.unreviewed", "1.0.0");
        PluginStoreItem communityItem = new PluginStoreItem(
                communitySource,
                communityRegistry,
                manager,
                communityRegistry.getPlugins().get(0),
                communityManifest,
                PluginTrustResult.community()
        );

        new PluginStoreSnapshot(1, List.of(
                PluginSourceLoadResult.failed(officialSource, 1, new IOException("official source unavailable")),
                PluginSourceLoadResult.success(communitySource, 1, List.of(communityItem), 0, communityRegistry, manager)
        ));

        assertEquals(PluginTrustLevel.COMMUNITY, communityManifest.getVersions().get(0).getTrust().level());
    }

    /// Selects cryptographic trust before configured source order for colliding plugin IDs.
    @Test
    public void certifiedSourceOutranksEarlierCommunitySource() throws Exception {
        PluginStoreRegistry registry = JsonUtils.GSON.fromJson("""
                {"schemaVersion":1,"name":"Fixture","plugins":[{
                  "id":"dev.test.same","manifestUrl":"https://example.com/manifest.json"
                }]}
                """, PluginStoreRegistry.class);
        registry.validate();
        PluginStoreRegistry.PluginStoreEntry entry = registry.getPlugins().get(0);
        PluginStoreManager manager = new PluginStoreManager();
        PluginStoreItem community = new PluginStoreItem(
                source("community", "https://community.example/plugins.json", true),
                registry, manager, entry, null, PluginTrustResult.community()
        );
        PluginStoreItem certified = new PluginStoreItem(
                source("certified", "https://certified.example/plugins.json", true),
                registry, manager, entry, null,
                PluginTrustResult.certified("ed25519:test", "serial-test")
        );
        PluginStoreSnapshot snapshot = new PluginStoreSnapshot(1, List.of(
                PluginSourceLoadResult.success(community.getSource(), 1, List.of(community), 1, registry, manager),
                PluginSourceLoadResult.success(certified.getSource(), 1, List.of(certified), 1, registry, manager)
        ));

        assertSame(certified, snapshot.getWinningItems().get("dev.test.same"));
        assertEquals(List.of(community), snapshot.getConflictCandidates().get("dev.test.same"));
    }

    /// Selects the earlier source when both sources publish the same plugin regardless of repository version.
    @Test
    public void sourcePriorityWinsRegardlessOfVersion() throws Exception {
        try (RegistryFixture high = RegistryFixture.start("High", "dev.test.same", "1.0.0");
             RegistryFixture low = RegistryFixture.start("Low", "dev.test.same", "99.0.0");
             PluginStoreAggregator aggregator = new PluginStoreAggregator()) {
            PluginSource highSource = source("high", high.registryUrl(), true);
            PluginSource lowSource = source("low", low.registryUrl(), true);

            PluginStoreSnapshot snapshot = aggregator.refresh(
                    List.of(highSource, lowSource)).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            assertEquals("high", snapshot.getWinningItems()
                    .get("dev.test.same").getSource().getId());
            assertEquals(List.of("low"), snapshot.getConflictCandidates()
                    .get("dev.test.same").stream()
                    .map(item -> item.getSource().getId()).toList());
        }
    }

    /// Carries the repository source revision into the completed aggregate snapshot.
    @Test
    public void aggregateSnapshotRetainsSourceConfigurationRevision() throws Exception {
        try (RegistryFixture fixture = RegistryFixture.start("Revision", "dev.test.revision", "1.0.0");
             PluginStoreAggregator aggregator = new PluginStoreAggregator()) {
            PluginSource source = source("revision", fixture.registryUrl(), true);

            PluginStoreSnapshot snapshot = aggregator.refresh(new PluginSourceConfiguration(
                    17,
                    List.of(source)
            )).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            assertEquals(17, snapshot.getSourceRevision());
            assertTrue(snapshot.matchesSourceConfiguration(new PluginSourceConfiguration(17, List.of(source))));
        }
    }

    /// Combines entries with distinct IDs from independent enabled sources.
    @Test
    public void aggregatesUniqueItemsFromEachEnabledSource() throws Exception {
        try (RegistryFixture first = RegistryFixture.start("First", "dev.test.first", "1.0.0");
             RegistryFixture second = RegistryFixture.start("Second", "dev.test.second", "1.0.0");
             PluginStoreAggregator aggregator = new PluginStoreAggregator()) {
            PluginStoreSnapshot snapshot = aggregator.refresh(List.of(
                    source("first", first.registryUrl(), true),
                    source("second", second.registryUrl(), true)
            )).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            assertEquals(List.of("dev.test.first", "dev.test.second"),
                    snapshot.getWinningItems().keySet().stream().toList());
            assertEquals(2, snapshot.getSourceResults().size());
            assertTrue(snapshot.getConflictCandidates().isEmpty());
        }
    }

    /// Recomputes the winner using the supplied source priority rather than a previous refresh result.
    @Test
    public void reorderedSourcesSelectTheNewFirstWinner() throws Exception {
        try (RegistryFixture first = RegistryFixture.start("First", "dev.test.same", "1.0.0");
             RegistryFixture second = RegistryFixture.start("Second", "dev.test.same", "2.0.0");
             PluginStoreAggregator aggregator = new PluginStoreAggregator()) {
            PluginStoreSnapshot firstSnapshot = aggregator.refresh(List.of(
                    source("first", first.registryUrl(), true),
                    source("second", second.registryUrl(), true)
            )).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            PluginStoreSnapshot reorderedSnapshot = aggregator.refresh(List.of(
                    source("second", second.registryUrl(), true),
                    source("first", first.registryUrl(), true)
            )).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            assertEquals("first", firstSnapshot.getWinningItems().get("dev.test.same").getSource().getId());
            assertEquals("second", reorderedSnapshot.getWinningItems().get("dev.test.same").getSource().getId());
        }
    }

    /// Uses a lower-priority source when an earlier source cannot load its registry.
    @Test
    public void registryFailureDegradesToTheLowerPriorityWinner() throws Exception {
        try (RegistryFixture failed = RegistryFixture.startRegistryFailure();
             RegistryFixture lower = RegistryFixture.start("Lower", "dev.test.same", "1.0.0");
             PluginStoreAggregator aggregator = new PluginStoreAggregator()) {
            PluginStoreSnapshot snapshot = aggregator.refresh(List.of(
                    source("high", failed.registryUrl(), true),
                    source("low", lower.registryUrl(), true)
            )).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            assertEquals("low", snapshot.getWinningItems().get("dev.test.same").getSource().getId());
            assertEquals(PluginSourceLoadResult.Status.FAILED, snapshot.getSourceResults().get(0).getStatus());
            assertEquals(1, snapshot.getFailures().size());
        }
    }

    /// Retains the source-level failures when no registry can produce a catalog.
    @Test
    public void reportsEveryFailureWhenAllSourcesFail() throws Exception {
        try (RegistryFixture first = RegistryFixture.startRegistryFailure();
             RegistryFixture second = RegistryFixture.startRegistryFailure();
             PluginStoreAggregator aggregator = new PluginStoreAggregator()) {
            PluginStoreSnapshot snapshot = aggregator.refresh(List.of(
                    source("first", first.registryUrl(), true),
                    source("second", second.registryUrl(), true)
            )).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            assertTrue(snapshot.getWinningItems().isEmpty());
            assertEquals(2, snapshot.getFailures().size());
            assertEquals(List.of(
                    PluginSourceLoadResult.Status.FAILED,
                    PluginSourceLoadResult.Status.FAILED
            ), snapshot.getSourceResults().stream().map(PluginSourceLoadResult::getStatus).toList());
        }
    }

    /// Retains the registry item while identifying an individual manifest load as a partial source failure.
    @Test
    public void recordsManifestFailureWithoutDiscardingTheRegistryItem() throws Exception {
        try (RegistryFixture fixture = RegistryFixture.startManifestFailure("Partial", "dev.test.partial");
             PluginStoreAggregator aggregator = new PluginStoreAggregator()) {
            PluginStoreSnapshot snapshot = aggregator.refresh(List.of(
                    source("partial", fixture.registryUrl(), true)
            )).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            PluginSourceLoadResult result = snapshot.getSourceResults().get(0);

            assertEquals(PluginSourceLoadResult.Status.PARTIAL_FAILURE, result.getStatus());
            assertEquals(1, result.getPartialManifestFailureCount());
            assertEquals(1, result.getItems().size());
            assertEquals(null, result.getItems().get(0).getManifest());
            assertEquals("partial", snapshot.getWinningItems().get("dev.test.partial").getSource().getId());
        }
    }

    /// Isolates an unparseable repository version while retaining later valid items from the same source.
    @Test
    public void malformedVersionManifestDoesNotDiscardOtherSourceItems() throws Exception {
        try (RegistryFixture fixture = RegistryFixture.startMalformedVersionWithValidRepository();
             PluginStoreAggregator aggregator = new PluginStoreAggregator()) {
            PluginStoreSnapshot snapshot = aggregator.refresh(List.of(
                    source("mixed-manifests", fixture.registryUrl(), true)
            )).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            PluginSourceLoadResult result = snapshot.getSourceResults().get(0);

            assertEquals(PluginSourceLoadResult.Status.PARTIAL_FAILURE, result.getStatus());
            assertEquals(1, result.getPartialManifestFailureCount());
            assertEquals(2, result.getItems().size());
            assertEquals("dev.test.malformed-version", result.getItems().get(0).getEntry().getId());
            assertEquals(null, result.getItems().get(0).getManifest());
            assertEquals("dev.test.valid-version", result.getItems().get(1).getEntry().getId());
            assertEquals(
                    "1.0.0",
                    Objects.requireNonNull(result.getItems().get(1).getLatestVersion()).getVersion()
            );
            assertEquals(2, snapshot.getWinningItems().size());
            assertTrue(snapshot.getFailures().isEmpty());
        }
    }

    /// Keeps a Topic source usable when discovery and manifest resolution each reject one repository.
    @Test
    public void topicRepositoryFailuresRemainPartialAcrossTheFullSourcePipeline() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/search/repositories", exchange -> respond(exchange, """
                {
                  "total_count": 3,
                  "items": [{
                    "full_name": "Example/Missing",
                    "name": "Missing",
                    "html_url": "https://github.com/Example/Missing",
                    "default_branch": "main"
                  }, {
                    "full_name": "Example/Malformed",
                    "name": "Malformed",
                    "html_url": "https://github.com/Example/Malformed",
                    "default_branch": "main"
                  }, {
                    "full_name": "Example/Available",
                    "name": "Available",
                    "html_url": "https://github.com/Example/Available",
                    "default_branch": "main"
                  }]
                }
                """.getBytes(StandardCharsets.UTF_8)));
        server.createContext("/raw/Example/Malformed/main/manifest.json", exchange -> respond(
                exchange,
                RegistryFixture.manifestWithVersions("dev.test.malformed-topic", "garbage", "1.0.0")
        ));
        server.createContext("/raw/Example/Available/main/manifest.json", exchange -> respond(
                exchange,
                RegistryFixture.manifest("dev.test.available-topic", "1.0.0")
        ));
        server.start();
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            PluginSource source = new PluginSource(
                    PluginSource.HMCLCE_TOPIC_ID,
                    baseUrl + "/search/repositories",
                    null,
                    true,
                    false
            );
            try (PluginStoreAggregator aggregator = new PluginStoreAggregator(
                    1,
                    ignored -> new PluginStoreManager(baseUrl + "/raw")
            )) {
                PluginStoreSnapshot snapshot = aggregator.refresh(List.of(source))
                        .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                PluginSourceLoadResult result = snapshot.getSourceResults().get(0);

                assertEquals(PluginSourceLoadResult.Status.PARTIAL_FAILURE, result.getStatus());
                assertEquals(2, result.getPartialManifestFailureCount());
                assertEquals(2, result.getItems().size());
                assertEquals(null, result.getItems().get(0).getManifest());
                assertEquals("dev.test.available-topic", result.getItems().get(1).getEntry().getId());
                assertEquals("1.0.0", Objects.requireNonNull(result.getItems().get(1).getLatestVersion()).getVersion());
                assertTrue(snapshot.getWinningItems().containsKey("dev.test.available-topic"));
                assertTrue(snapshot.getFailures().isEmpty());
            }
        } finally {
            server.stop(0);
        }
    }

    /// Requires a snapshot's sources to exactly match the current persisted source configuration in order.
    @Test
    public void snapshotFreshnessIncludesEveryBehaviorRelevantSourceFieldAndOrder() {
        PluginSource original = new PluginSource(
                "source", "https://plugins.example.test/catalog.json", "Community", true, false);
        PluginStoreSnapshot snapshot = new PluginStoreSnapshot(1, List.of(PluginSourceLoadResult.disabled(original)));

        assertTrue(snapshot.matchesSources(List.of(original)));
        assertTrue(snapshot.matchesSources(List.of(new PluginSource(
                "source", "https://plugins.example.test/catalog.json", "Community", true, false))));
        assertTrue(!snapshot.matchesSources(List.of(original.withConfiguration(
                "https://plugins.example.test/changed.json", "Community"))));
        assertTrue(!snapshot.matchesSources(List.of(original.withConfiguration(
                original.getUrl(), "Changed"))));
        assertTrue(!snapshot.matchesSources(List.of(original.withEnabled(false))));
        assertTrue(!snapshot.matchesSources(List.of(new PluginSource(
                original.getId(), original.getUrl(), original.getAlias(), true, true))));
        assertTrue(!snapshot.matchesSources(List.of(original, new PluginSource(
                "second", "https://plugins.example.test/second.json", null, true, false))));
    }

    /// Removes non-HTTP URL credentials, queries, and fragments from source failure diagnostics while retaining the original failure.
    @Test
    public void sourceFailureMessageSanitizesNonHttpUrlCredentialsAndParameters() {
        IOException failure = new IOException(
                "Unable to load ftp://user:secret@example.test/plugins.json?token=private#fragment"
        );
        PluginSourceLoadResult result = PluginSourceLoadResult.failed(
                source("failed", "https://example.test/plugins.json", true),
                0,
                failure
        );

        assertEquals("Unable to load ftp://example.test", result.getFailureMessage());
        assertSame(failure, result.getFailure());
        assertTrue(Objects.requireNonNull(result.getFailureMessage()).contains("example.test"));
        assertTrue(!result.getFailureMessage().contains("secret"));
        assertTrue(!result.getFailureMessage().contains("token=private"));
        assertTrue(!result.getFailureMessage().contains("fragment"));
    }

    /// Never decodes encoded credential and query delimiters from a failure URL path.
    @Test
    public void sourceFailureMessageDoesNotDiscloseEncodedSensitivePath() {
        IOException failure = new IOException(
                "Unable to load https://example.test/catalog/user%3Asecret%40host%3Ftoken%3Dprivate"
        );
        PluginSourceLoadResult result = PluginSourceLoadResult.failed(
                source("failed", "https://example.test/plugins.json", true),
                0,
                failure
        );

        assertEquals("Unable to load https://example.test", result.getFailureMessage());
        assertTrue(!Objects.requireNonNull(result.getFailureMessage()).contains("secret"));
        assertTrue(!result.getFailureMessage().contains("token"));
        assertTrue(!result.getFailureMessage().contains("%"));
    }

    /// Redacts every component of malformed URI tokens while preserving harmless surrounding failure text.
    @Test
    public void sourceFailureMessageDoesNotDiscloseMalformedUriSecrets() {
        String malformedUrl = "ftp://user:user-secret@example.test/plain-path-secret%zz?token=query-secret#fragment-secret";
        IOException failure = new IOException("Unable to load " + malformedUrl + " while refreshing");
        PluginSourceLoadResult result = PluginSourceLoadResult.failed(
                source("failed", "https://example.test/plugins.json", true),
                0,
                failure
        );

        String message = Objects.requireNonNull(result.getFailureMessage());
        assertEquals("Unable to load " + PluginSourceLabels.diagnosticUrl(malformedUrl) + " while refreshing", message);
        assertSame(failure, result.getFailure());
        assertTrue(!message.contains("user-secret"));
        assertTrue(!message.contains("plain-path-secret"));
        assertTrue(!message.contains("query-secret"));
        assertTrue(!message.contains("fragment-secret"));
    }

    /// Redacts credential-bearing remote URLs from validation diagnostics before they can reach logs or source status UI.
    @Test
    public void remoteUrlValidationDiagnosticsRedactCredentialsAndParameters() {
        IOException exception = assertThrows(IOException.class, () -> PluginStoreManager.validateRemoteUrl(
                "ftp://user:secret@example.test/plugins.json?token=private#fragment",
                "plugin registry"
        ));

        String message = Objects.requireNonNull(exception.getMessage());
        assertTrue(message.contains("ftp://example.test"));
        assertTrue(!message.contains("secret"));
        assertTrue(!message.contains("token"));
        assertTrue(!message.contains("?"));
        assertTrue(!message.contains("#"));
    }

    /// Rejects success-result counts that do not match the source items with unresolved manifests.
    @Test
    public void sourceResultSuccessRejectsMismatchedPartialManifestCounts() throws Exception {
        try (RegistryFixture fixture = RegistryFixture.start("Result", "dev.test.result", "1.0.0")) {
            PluginSource source = source("result", fixture.registryUrl(), true);
            PluginStoreManager manager = new PluginStoreManager();
            manager.loadSource(source);
            PluginStoreRegistry registry = Objects.requireNonNull(manager.getRegistry());
            PluginStoreItem resolved = manager.getStoreItems().get(0);
            PluginStoreItem unresolved = new PluginStoreItem(
                    source,
                    registry,
                    manager,
                    resolved.getEntry(),
                    null
            );

            PluginSourceLoadResult success = PluginSourceLoadResult.success(
                    source, 0, List.of(resolved), 0, registry, manager
            );
            PluginSourceLoadResult partial = PluginSourceLoadResult.success(
                    source, 0, List.of(unresolved), 1, registry, manager
            );

            assertEquals(PluginSourceLoadResult.Status.SUCCESS, success.getStatus());
            assertEquals(PluginSourceLoadResult.Status.PARTIAL_FAILURE, partial.getStatus());
            assertThrows(
                    IllegalArgumentException.class,
                    () -> PluginSourceLoadResult.success(source, 0, List.of(resolved), 1, registry, manager)
            );
            assertThrows(
                    IllegalArgumentException.class,
                    () -> PluginSourceLoadResult.success(source, 0, List.of(unresolved), 0, registry, manager)
            );
        }
    }

    /// Includes Topic repositories skipped before registry publication in the partial-failure status and count.
    @Test
    public void sourceResultCountsSkippedRepositoriesAsPartialFailures() throws Exception {
        try (RegistryFixture fixture = RegistryFixture.start("Result", "dev.test.result", "1.0.0")) {
            PluginSource source = source("result", fixture.registryUrl(), true);
            PluginStoreManager manager = new PluginStoreManager();
            manager.loadSource(source);
            PluginStoreRegistry registry = Objects.requireNonNull(manager.getRegistry());
            PluginStoreItem resolved = manager.getStoreItems().get(0);

            PluginSourceLoadResult result = PluginSourceLoadResult.success(
                    source, 0, List.of(resolved), 0, 1, registry, manager
            );

            assertEquals(PluginSourceLoadResult.Status.PARTIAL_FAILURE, result.getStatus());
            assertEquals(1, result.getPartialManifestFailureCount());
            assertThrows(
                    IllegalArgumentException.class,
                    () -> PluginSourceLoadResult.success(source, 0, List.of(resolved), 0, -1, registry, manager)
            );
        }
    }

    /// Does not submit disabled source requests to the remote registry.
    @Test
    public void disabledSourcesMakeNoRequests() throws Exception {
        try (RegistryFixture fixture = RegistryFixture.start("Disabled", "dev.test.disabled", "1.0.0");
             PluginStoreAggregator aggregator = new PluginStoreAggregator()) {
            PluginStoreSnapshot snapshot = aggregator.refresh(List.of(
                    source("disabled", fixture.registryUrl(), false)
            )).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            assertEquals(0, fixture.getRegistryRequests());
            assertEquals(PluginSourceLoadResult.Status.DISABLED, snapshot.getSourceResults().get(0).getStatus());
            assertTrue(snapshot.getWinningItems().isEmpty());
        }
    }

    /// Keeps an older request result available to its caller without allowing it to replace a newer published snapshot.
    @Test
    public void staleGenerationCannotReplaceTheCurrentSnapshot() throws Exception {
        CountDownLatch firstRegistryStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstRegistry = new CountDownLatch(1);
        try (RegistryFixture old = RegistryFixture.startBlocking(
                "Old", firstRegistryStarted, releaseFirstRegistry, new AtomicInteger(), new AtomicInteger()
        );
             RegistryFixture replacement = RegistryFixture.start("Replacement", "dev.test.replacement", "1.0.0");
             PluginStoreAggregator aggregator = new PluginStoreAggregator()) {
            var staleFuture = aggregator.refresh(List.of(source("old", old.registryUrl(), true)));
            assertTrue(firstRegistryStarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));

            PluginStoreSnapshot current = aggregator.refresh(List.of(
                    source("replacement", replacement.registryUrl(), true)
            )).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            releaseFirstRegistry.countDown();
            PluginStoreSnapshot stale = staleFuture.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            assertEquals("old", stale.getSourceResults().get(0).getSource().getId());
            assertEquals("replacement", current.getWinningItems().get("dev.test.replacement").getSource().getId());
            assertSame(current, aggregator.getCurrentSnapshot());
            assertEquals(current.getGeneration(), aggregator.getCurrentSnapshot().getGeneration());
        } finally {
            releaseFirstRegistry.countDown();
        }
    }

    /// Limits active registry requests to the configured executor concurrency.
    @Test
    public void limitsConcurrentSourceRequests() throws Exception {
        int concurrency = 4;
        AtomicInteger activeRequests = new AtomicInteger();
        AtomicInteger maximumActiveRequests = new AtomicInteger();
        CountDownLatch requestsStarted = new CountDownLatch(concurrency);
        CountDownLatch releaseRequests = new CountDownLatch(1);
        try (RegistryFixture first = RegistryFixture.startBlocking(
                "First", requestsStarted, releaseRequests, activeRequests, maximumActiveRequests
        );
             RegistryFixture second = RegistryFixture.startBlocking(
                     "Second", requestsStarted, releaseRequests, activeRequests, maximumActiveRequests
             );
             RegistryFixture third = RegistryFixture.startBlocking(
                     "Third", requestsStarted, releaseRequests, activeRequests, maximumActiveRequests
             );
             RegistryFixture fourth = RegistryFixture.startBlocking(
                     "Fourth", requestsStarted, releaseRequests, activeRequests, maximumActiveRequests
             );
             RegistryFixture fifth = RegistryFixture.startBlocking(
                     "Fifth", requestsStarted, releaseRequests, activeRequests, maximumActiveRequests
             );
             RegistryFixture sixth = RegistryFixture.startBlocking(
                     "Sixth", requestsStarted, releaseRequests, activeRequests, maximumActiveRequests
             );
             PluginStoreAggregator aggregator = new PluginStoreAggregator(concurrency)) {
            var snapshotFuture = aggregator.refresh(List.of(
                    source("first", first.registryUrl(), true),
                    source("second", second.registryUrl(), true),
                    source("third", third.registryUrl(), true),
                    source("fourth", fourth.registryUrl(), true),
                    source("fifth", fifth.registryUrl(), true),
                    source("sixth", sixth.registryUrl(), true)
            ));
            assertTrue(requestsStarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            assertEquals(concurrency, activeRequests.get());
            assertTrue(maximumActiveRequests.get() <= concurrency);

            releaseRequests.countDown();
            snapshotFuture.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertTrue(maximumActiveRequests.get() <= concurrency);
        } finally {
            releaseRequests.countDown();
        }
    }

    /// Limits concurrent requests across independently owned aggregators to the shared process-wide budget.
    @Test
    public void globalSourceLoadBudgetSpansIndependentAggregators() throws Exception {
        AtomicInteger activeRequests = new AtomicInteger();
        AtomicInteger maximumActiveRequests = new AtomicInteger();
        CountDownLatch requestsStarted = new CountDownLatch(PluginSourceLoadExecutor.MAX_CONCURRENCY);
        CountDownLatch releaseRequests = new CountDownLatch(1);
        try (RegistryFixture first = RegistryFixture.startBlocking(
                "First", requestsStarted, releaseRequests, activeRequests, maximumActiveRequests
        );
             RegistryFixture second = RegistryFixture.startBlocking(
                     "Second", requestsStarted, releaseRequests, activeRequests, maximumActiveRequests
             );
             RegistryFixture third = RegistryFixture.startBlocking(
                     "Third", requestsStarted, releaseRequests, activeRequests, maximumActiveRequests
             );
             RegistryFixture fourth = RegistryFixture.startBlocking(
                     "Fourth", requestsStarted, releaseRequests, activeRequests, maximumActiveRequests
             );
             RegistryFixture fifth = RegistryFixture.startBlocking(
                     "Fifth", requestsStarted, releaseRequests, activeRequests, maximumActiveRequests
             );
             RegistryFixture sixth = RegistryFixture.startBlocking(
                     "Sixth", requestsStarted, releaseRequests, activeRequests, maximumActiveRequests
             );
             PluginStoreAggregator left = new PluginStoreAggregator(PluginSourceLoadExecutor.MAX_CONCURRENCY);
             PluginStoreAggregator right = new PluginStoreAggregator(PluginSourceLoadExecutor.MAX_CONCURRENCY)) {
            var leftFuture = left.refresh(List.of(
                    source("first", first.registryUrl(), true),
                    source("second", second.registryUrl(), true),
                    source("third", third.registryUrl(), true)
            ));
            var rightFuture = right.refresh(List.of(
                    source("fourth", fourth.registryUrl(), true),
                    source("fifth", fifth.registryUrl(), true),
                    source("sixth", sixth.registryUrl(), true)
            ));
            assertTrue(requestsStarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            assertTrue(maximumActiveRequests.get() <= PluginSourceLoadExecutor.MAX_CONCURRENCY);

            releaseRequests.countDown();
            leftFuture.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            rightFuture.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertTrue(maximumActiveRequests.get() <= PluginSourceLoadExecutor.MAX_CONCURRENCY);
        } finally {
            releaseRequests.countDown();
        }
    }

    /// Preserves exact permit accounting when a source load is interrupted before permit acquisition.
    @Test
    public void interruptedGlobalPermitWaitDoesNotOverRelease() throws Exception {
        CountDownLatch permitsOccupied = new CountDownLatch(PluginSourceLoadExecutor.MAX_CONCURRENCY);
        CountDownLatch releasePermits = new CountDownLatch(1);
        List<Thread> holders = new java.util.ArrayList<>();
        try {
            for (int index = 0; index < PluginSourceLoadExecutor.MAX_CONCURRENCY; index++) {
                Thread holder = new Thread(() -> {
                    try {
                        PluginSourceLoadExecutor.call(() -> {
                            permitsOccupied.countDown();
                            releasePermits.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                            return null;
                        });
                    } catch (Exception exception) {
                        throw new AssertionError(exception);
                    }
                });
                holder.start();
                holders.add(holder);
            }
            assertTrue(permitsOccupied.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));

            FutureTask<Boolean> interruptedWaiter = new FutureTask<>(() -> {
                try {
                    PluginSourceLoadExecutor.call(() -> true);
                    return false;
                } catch (InterruptedException exception) {
                    return true;
                }
            });
            Thread waiter = new Thread(interruptedWaiter);
            waiter.start();
            waiter.interrupt();
            assertTrue(interruptedWaiter.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));

            releasePermits.countDown();
            for (Thread holder : holders) {
                holder.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
            }

            AtomicInteger active = new AtomicInteger();
            AtomicInteger maximum = new AtomicInteger();
            List<Thread> probes = new java.util.ArrayList<>();
            CountDownLatch probeRelease = new CountDownLatch(1);
            CountDownLatch probesStarted = new CountDownLatch(PluginSourceLoadExecutor.MAX_CONCURRENCY);
            for (int index = 0; index < PluginSourceLoadExecutor.MAX_CONCURRENCY + 1; index++) {
                Thread probe = new Thread(() -> {
                    try {
                        PluginSourceLoadExecutor.call(() -> {
                            updateMaximum(maximum, active.incrementAndGet());
                            probesStarted.countDown();
                            probeRelease.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                            active.decrementAndGet();
                            return null;
                        });
                    } catch (Exception exception) {
                        throw new AssertionError(exception);
                    }
                });
                probe.start();
                probes.add(probe);
            }
            assertTrue(probesStarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            assertEquals(PluginSourceLoadExecutor.MAX_CONCURRENCY, active.get());
            assertEquals(PluginSourceLoadExecutor.MAX_CONCURRENCY, maximum.get());
            probeRelease.countDown();
            for (Thread probe : probes) {
                probe.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
            }
        } finally {
            releasePermits.countDown();
        }
    }

    /// Keeps the global source-load permit available after an independently owned aggregator closes.
    @Test
    public void closingOneAggregatorDoesNotDisableLaterSourceLoads() throws Exception {
        try (RegistryFixture fixture = RegistryFixture.start("Later", "dev.test.later", "1.0.0")) {
            try (PluginStoreAggregator closed = new PluginStoreAggregator()) {
                closed.refresh(List.of(source("first", fixture.registryUrl(), true)))
                        .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            }
            try (PluginStoreAggregator later = new PluginStoreAggregator()) {
                PluginStoreSnapshot snapshot = later.refresh(List.of(source("later", fixture.registryUrl(), true)))
                        .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                assertEquals("later", snapshot.getSourceResults().get(0).getSource().getId());
            }
        }
    }

    /// Creates one source configuration for a local fixture.
    ///
    /// @param id source identifier
    /// @param registryUrl registry endpoint
    /// @param enabled whether the aggregator may request the source
    /// @return source configuration
    private static PluginSource source(String id, String registryUrl, boolean enabled) {
        return new PluginSource(id, registryUrl, null, enabled, false);
    }

    /// Creates a validated one-entry registry with an explicit repository identity.
    ///
    /// @param name registry display name
    /// @param pluginId plugin ID
    /// @param repository source repository identity
    /// @return validated registry
    private static PluginStoreRegistry registry(String name, String pluginId, String repository) throws IOException {
        PluginStoreRegistry registry = JsonUtils.GSON.fromJson("""
                {"schemaVersion":1,"name":"%s","plugins":[{
                  "id":"%s",
                  "manifestUrl":"https://example.com/%s/manifest.json",
                  "repository":"%s"
                }]}
                """.formatted(name, pluginId, pluginId, repository), PluginStoreRegistry.class);
        registry.validate();
        return registry;
    }

    /// Creates a validated repository manifest containing the requested versions.
    ///
    /// @param pluginId plugin ID
    /// @param versions version strings in publication order
    /// @return validated manifest
    private static PluginStoreManifest manifest(String pluginId, String @Unmodifiable ... versions) throws IOException {
        String versionEntries = java.util.Arrays.stream(versions)
                .map(version -> """
                        {
                          "version":"%s",
                          "packageUrl":"https://example.com/%s.npl",
                          "sha256":"%s",
                          "pluginApiVersion":4,
                          "permissions":[],
                          "requiredPermissions":[],
                          "launcherVersion":"*",
                          "dependencies":[],
                          "size":1
                        }
                        """.formatted(version, version, "0".repeat(64)))
                .collect(java.util.stream.Collectors.joining(","));
        PluginStoreManifest manifest = JsonUtils.GSON.fromJson("""
                {"schemaVersion":2,"id":"%s","versions":[%s]}
                """.formatted(pluginId, versionEntries), PluginStoreManifest.class);
        manifest.validate(pluginId);
        return manifest;
    }

    /// Sends one successful local HTTP response.
    ///
    /// @param exchange local HTTP exchange
    /// @param body response bytes
    /// @throws IOException if the response cannot be written
    private static void respond(HttpExchange exchange, byte @Unmodifiable [] body) throws IOException {
        exchange.sendResponseHeaders(HttpURLConnection.HTTP_OK, body.length);
        try (var output = exchange.getResponseBody()) {
            output.write(body);
        }
    }

    /// Updates an atomic maximum with a newly observed request count.
    ///
    /// @param maximum destination maximum
    /// @param candidate observed active request count
    private static void updateMaximum(AtomicInteger maximum, int candidate) {
        maximum.accumulateAndGet(candidate, Math::max);
    }

    /// Owns a local registry and optional manifest endpoint for one aggregator test source.
    @NotNullByDefault
    private static final class RegistryFixture implements AutoCloseable {
        /// Local HTTP server serving the registry and manifest responses.
        private final HttpServer server;

        /// Number of requests received by the registry endpoint.
        private final AtomicInteger registryRequests;

        /// Creates one started local registry fixture.
        ///
        /// @param server local HTTP server
        /// @param registryRequests registry request counter
        private RegistryFixture(HttpServer server, AtomicInteger registryRequests) {
            this.server = server;
            this.registryRequests = registryRequests;
        }

        /// Starts a registry that contains one entry and a resolvable manifest.
        ///
        /// @param registryName source-visible registry name
        /// @param pluginId registry entry ID
        /// @param version manifest version
        /// @return started registry fixture
        /// @throws IOException if the local server cannot be created
        private static RegistryFixture start(String registryName, String pluginId, String version) throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            AtomicInteger registryRequests = new AtomicInteger();
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            server.createContext("/plugins.json", exchange -> {
                registryRequests.incrementAndGet();
                respond(exchange, registryWithEntry(registryName, pluginId, baseUrl + "/manifest.json"));
            });
            server.createContext("/manifest.json", exchange -> respond(exchange, manifest(pluginId, version)));
            server.start();
            return new RegistryFixture(server, registryRequests);
        }

        /// Starts a registry that always rejects catalog requests.
        ///
        /// @return started failing registry fixture
        /// @throws IOException if the local server cannot be created
        private static RegistryFixture startRegistryFailure() throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            AtomicInteger registryRequests = new AtomicInteger();
            server.createContext("/plugins.json", exchange -> {
                registryRequests.incrementAndGet();
                exchange.sendResponseHeaders(HttpURLConnection.HTTP_INTERNAL_ERROR, -1);
                exchange.close();
            });
            server.start();
            return new RegistryFixture(server, registryRequests);
        }

        /// Starts a registry whose sole repository manifest request fails.
        ///
        /// @param registryName source-visible registry name
        /// @param pluginId registry entry ID
        /// @return started partial-failure fixture
        /// @throws IOException if the local server cannot be created
        private static RegistryFixture startManifestFailure(String registryName, String pluginId) throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            AtomicInteger registryRequests = new AtomicInteger();
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            server.createContext("/plugins.json", exchange -> {
                registryRequests.incrementAndGet();
                respond(exchange, registryWithEntry(registryName, pluginId, baseUrl + "/manifest.json"));
            });
            server.createContext("/manifest.json", exchange -> {
                exchange.sendResponseHeaders(HttpURLConnection.HTTP_INTERNAL_ERROR, -1);
                exchange.close();
            });
            server.start();
            return new RegistryFixture(server, registryRequests);
        }

        /// Starts one source with an unparseable-version manifest followed by a valid repository manifest.
        ///
        /// @return started mixed-validity fixture
        /// @throws IOException if the local server cannot be created
        private static RegistryFixture startMalformedVersionWithValidRepository() throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            AtomicInteger registryRequests = new AtomicInteger();
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            server.createContext("/plugins.json", exchange -> {
                registryRequests.incrementAndGet();
                respond(exchange, registryWithMixedManifestValidity(baseUrl));
            });
            server.createContext("/malformed-manifest.json", exchange -> respond(
                    exchange,
                    manifestWithVersions("dev.test.malformed-version", "garbage", "1.0.0")
            ));
            server.createContext("/valid-manifest.json", exchange -> respond(
                    exchange,
                    manifest("dev.test.valid-version", "1.0.0")
            ));
            server.start();
            return new RegistryFixture(server, registryRequests);
        }

        /// Starts a registry request that waits until the test releases its shared request gate.
        ///
        /// @param registryName source-visible registry name
        /// @param started signal counted down after the request becomes active
        /// @param release gate controlling response completion
        /// @param activeRequests active registry request count
        /// @param maximumActiveRequests observed maximum active registry request count
        /// @return started blocking registry fixture
        /// @throws IOException if the local server cannot be created
        private static RegistryFixture startBlocking(
                String registryName,
                CountDownLatch started,
                CountDownLatch release,
                AtomicInteger activeRequests,
                AtomicInteger maximumActiveRequests
        ) throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            AtomicInteger registryRequests = new AtomicInteger();
            server.createContext("/plugins.json", exchange -> {
                registryRequests.incrementAndGet();
                int active = activeRequests.incrementAndGet();
                updateMaximum(maximumActiveRequests, active);
                started.countDown();
                try {
                    if (!release.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                        throw new IOException("Timed out waiting to release registry request");
                    }
                    respond(exchange, emptyRegistry(registryName));
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while waiting to release registry request", exception);
                } finally {
                    activeRequests.decrementAndGet();
                }
            });
            server.start();
            return new RegistryFixture(server, registryRequests);
        }

        /// Returns this fixture's registry endpoint.
        ///
        /// @return loopback registry URL
        private String registryUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/plugins.json";
        }

        /// Returns the observed registry request count.
        ///
        /// @return registry request count
        private int getRegistryRequests() {
            return registryRequests.get();
        }

        /// Stops the fixture server after its test completes.
        @Override
        public void close() {
            server.stop(0);
        }

        /// Serializes a registry containing one repository entry.
        ///
        /// @param name registry display name
        /// @param pluginId registry entry ID
        /// @param manifestUrl repository manifest URL
        /// @return registry JSON bytes
        private static byte @Unmodifiable [] registryWithEntry(String name, String pluginId, String manifestUrl) {
            return """
                    {
                      "schemaVersion": 1,
                      "name": "%s",
                      "plugins": [
                        {
                          "id": "%s",
                          "name": "Aggregator Plugin",
                          "manifestUrl": "%s"
                        }
                      ]
                    }
                    """.formatted(name, pluginId, manifestUrl).getBytes(StandardCharsets.UTF_8);
        }

        /// Serializes two repository entries whose manifests have different version validity.
        ///
        /// @param baseUrl local fixture origin
        /// @return registry JSON bytes
        private static byte @Unmodifiable [] registryWithMixedManifestValidity(String baseUrl) {
            return """
                    {
                      "schemaVersion": 1,
                      "name": "Mixed manifests",
                      "plugins": [
                        {
                          "id": "dev.test.malformed-version",
                          "name": "Malformed version",
                          "manifestUrl": "%s/malformed-manifest.json"
                        },
                        {
                          "id": "dev.test.valid-version",
                          "name": "Valid version",
                          "manifestUrl": "%s/valid-manifest.json"
                        }
                      ]
                    }
                    """.formatted(baseUrl, baseUrl).getBytes(StandardCharsets.UTF_8);
        }

        /// Serializes an empty registry for request-concurrency tests.
        ///
        /// @param name registry display name
        /// @return registry JSON bytes
        private static byte @Unmodifiable [] emptyRegistry(String name) {
            return """
                    {
                      "schemaVersion": 1,
                      "name": "%s",
                      "plugins": []
                    }
                    """.formatted(name).getBytes(StandardCharsets.UTF_8);
        }

        /// Serializes a valid source-bound repository manifest.
        ///
        /// @param pluginId registry entry ID
        /// @param version repository version
        /// @return manifest JSON bytes
        private static byte @Unmodifiable [] manifest(String pluginId, String version) {
            return manifestWithVersions(pluginId, version);
        }

        /// Serializes one source-bound repository manifest containing every supplied version string.
        ///
        /// @param pluginId registry entry ID
        /// @param versions repository versions in publication order
        /// @return manifest JSON bytes
        private static byte @Unmodifiable [] manifestWithVersions(
                String pluginId,
                String @Unmodifiable ... versions
        ) {
            String entries = java.util.Arrays.stream(versions)
                    .map(RegistryFixture::manifestVersion)
                    .collect(java.util.stream.Collectors.joining(","));
            return """
                    {
                      "schemaVersion": 2,
                      "id": "%s",
                      "versions": [%s]
                    }
                    """.formatted(pluginId, entries).getBytes(StandardCharsets.UTF_8);
        }

        /// Serializes one version object for a repository manifest fixture.
        ///
        /// @param version repository version string
        /// @return version object JSON
        private static String manifestVersion(String version) {
            return """
                    {
                      "version": "%s",
                      "packageUrl": "https://example.com/plugin.npl",
                      "sha256": "0000000000000000000000000000000000000000000000000000000000000000",
                      "pluginApiVersion": 4,
                      "permissions": [],
                      "requiredPermissions": [],
                      "launcherVersion": "*",
                      "dependencies": [],
                      "size": 1
                    }
                    """.formatted(version);
        }
    }
}
