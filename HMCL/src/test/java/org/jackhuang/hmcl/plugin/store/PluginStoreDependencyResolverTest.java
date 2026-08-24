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

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.jackhuang.hmcl.plugin.PluginArtifactIdentity;
import org.jackhuang.hmcl.plugin.PluginManifest;
import org.jackhuang.hmcl.plugin.PluginPermission;
import org.jackhuang.hmcl.plugin.runtime.PluginPlatformTarget;
import org.jackhuang.hmcl.plugin.trust.PluginTrustLevel;
import org.jackhuang.hmcl.plugin.trust.PluginTrustResult;
import org.jackhuang.hmcl.util.gson.JsonUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.StringReader;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies complete-graph dependency selection for plugin-store installation plans.
@NotNullByDefault
public final class PluginStoreDependencyResolverTest {
    /// Preserves configured source priority when a later source replaces a winner without moving its map key.
    @Test
    public void configuredSourcePrioritySurvivesWinnerReplacementPosition() throws Exception {
        PluginStoreSnapshot snapshot = runtimeSourcePrioritySnapshot();
        String rootId = "dev.test.priority-rust-tool";
        assertEquals("configured-second", snapshot.getWinningItems().values().iterator().next()
                .getSource().getId());

        PluginInstallPlan plan = new PluginStoreDependencyResolver(
                snapshot.getWinningItems(),
                snapshot.getSourceConfiguration().getSources()
        ).resolveInstallPlan(
                rootId,
                runtimeVersion(snapshot.getWinningItems(), rootId),
                Map.of(), Map.of(), Map.of()
        );

        assertEquals("dev.test.configured-first-rust-host",
                Objects.requireNonNull(plan.getRuntimeBindings().get(rootId)).providerId());
    }

    /// Keeps Runtime Provider selection stable when the same winner map is published in another insertion order.
    @Test
    public void winnerInsertionOrderDoesNotChangeConfiguredProviderPriority() throws Exception {
        PluginStoreSnapshot snapshot = runtimeSourcePrioritySnapshot();
        List<Map.Entry<String, PluginStoreItem>> reversedEntries =
                new ArrayList<>(snapshot.getWinningItems().entrySet());
        Collections.reverse(reversedEntries);
        Map<String, PluginStoreItem> reorderedWinners = new LinkedHashMap<>();
        reversedEntries.forEach(entry -> reorderedWinners.put(entry.getKey(), entry.getValue()));
        String rootId = "dev.test.priority-rust-tool";
        @Unmodifiable List<PluginSource> configuredSources = snapshot.getSourceConfiguration().getSources();
        assertNotEquals(
                List.copyOf(snapshot.getWinningItems().keySet()),
                List.copyOf(reorderedWinners.keySet())
        );

        PluginInstallPlan original = new PluginStoreDependencyResolver(
                snapshot.getWinningItems(), configuredSources
        ).resolveInstallPlan(
                rootId, runtimeVersion(snapshot.getWinningItems(), rootId), Map.of(), Map.of(), Map.of()
        );
        PluginInstallPlan reordered = new PluginStoreDependencyResolver(
                Collections.unmodifiableMap(reorderedWinners), configuredSources
        ).resolveInstallPlan(
                rootId, runtimeVersion(reorderedWinners, rootId), Map.of(), Map.of(), Map.of()
        );

        assertEquals("dev.test.configured-first-rust-host",
                Objects.requireNonNull(original.getRuntimeBindings().get(rootId)).providerId());
        assertEquals(original.getRuntimeBindings(), reordered.getRuntimeBindings());
    }

    /// Adds a compatible runtime Host before its Rust dependent and records the exact binding and artifact.
    @Test
    public void addsCompatibleRuntimeProviderBeforeRustPlugin() throws Exception {
        @Unmodifiable Map<String, PluginStoreItem> catalog = runtimeCatalog(true, "");
        PluginStoreManifest.PluginVersionEntry rootVersion = runtimeVersion(catalog, "dev.test.rust-tool");

        PluginInstallPlan plan = new PluginStoreDependencyResolver(catalog, configuredSources(catalog)).resolveInstallPlan(
                "dev.test.rust-tool", rootVersion, Map.of(), Map.of(), Map.of()
        );

        assertEquals(List.of("dev.test.rust-host", "dev.test.rust-tool"), plan.getEntries().stream()
                .map(PluginInstallPlan.Entry::getPluginId)
                .toList());
        @SuppressWarnings("unchecked")
        Map<String, Object> bindings = (Map<String, Object>) plan.getClass()
                .getMethod("getRuntimeBindings")
                .invoke(plan);
        assertEquals("dev.test.rust-host", bindings.get("dev.test.rust-tool").getClass()
                .getMethod("providerId")
                .invoke(bindings.get("dev.test.rust-tool")));
        PluginStoreArtifact artifact = (PluginStoreArtifact) plan.getEntries().get(0).getClass()
                .getMethod("getSelectedArtifact")
                .invoke(plan.getEntries().get(0));
        assertEquals(PluginPlatformTarget.current(), artifact.platform());
        assertEquals(41, artifact.size());
    }

    /// Reuses one already installed compatible Host instead of planning a duplicate download.
    @Test
    public void reusesInstalledRuntimeProvider() throws Exception {
        @Unmodifiable Map<String, PluginStoreItem> catalog = runtimeCatalog(true, "");
        PluginManifest installedHost = installedRuntimeProviderManifest();
        PluginArtifactIdentity hostIdentity = PluginArtifactIdentity.of(installedHost, "a".repeat(64));

        PluginInstallPlan plan = new PluginStoreDependencyResolver(catalog, configuredSources(catalog)).resolveInstallPlan(
                "dev.test.rust-tool",
                runtimeVersion(catalog, "dev.test.rust-tool"),
                Map.of(installedHost.getId(), installedHost),
                Map.of(installedHost.getId(), hostIdentity),
                Map.of(installedHost.getId(), hostIdentity)
        );

        assertEquals(2, plan.getEntries().size());
        assertEquals("dev.test.rust-host", plan.getEntries().get(0).getPluginId());
        assertEquals(PluginInstallPlan.Action.REUSE, plan.getEntries().get(0).getAction());
    }

    /// Reuses an exact installed but disabled Host and records its atomic enablement instead of downloading it again.
    @Test
    public void enablesInstalledDisabledRuntimeProvider() throws Exception {
        @Unmodifiable Map<String, PluginStoreItem> catalog = runtimeCatalog(true, "");
        PluginManifest installedHost = installedRuntimeProviderManifest();
        PluginArtifactIdentity hostIdentity = PluginArtifactIdentity.of(installedHost, "a".repeat(64));

        PluginInstallPlan plan = new PluginStoreDependencyResolver(catalog, configuredSources(catalog)).resolveInstallPlan(
                "dev.test.rust-tool",
                runtimeVersion(catalog, "dev.test.rust-tool"),
                Map.of(installedHost.getId(), installedHost),
                Map.of(installedHost.getId(), hostIdentity),
                Map.of(),
                Map.of(installedHost.getId(), hostIdentity)
        );

        assertEquals(PluginInstallPlan.Action.ENABLE, plan.getEntries().get(0).getAction());
        assertEquals(List.of("dev.test.rust-host"), plan.getEnablementPluginIds());
        assertEquals(17, plan.getTotalDownloadSize());
    }

    /// Never treats an installed Provider that lacks explicit activatable state as merely disabled.
    @Test
    public void ineligibleInstalledRuntimeProviderIsUpdatedInsteadOfEnabled() throws Exception {
        @Unmodifiable Map<String, PluginStoreItem> catalog = runtimeCatalog(true, "");
        PluginManifest installedHost = installedRuntimeProviderManifest();
        PluginArtifactIdentity hostIdentity = PluginArtifactIdentity.of(installedHost, "b".repeat(64));

        PluginInstallPlan plan = new PluginStoreDependencyResolver(catalog, configuredSources(catalog)).resolveInstallPlan(
                "dev.test.rust-tool",
                runtimeVersion(catalog, "dev.test.rust-tool"),
                Map.of(installedHost.getId(), installedHost),
                Map.of(installedHost.getId(), hostIdentity),
                Map.of(),
                Map.of()
        );

        PluginInstallPlan.Entry host = plan.getEntries().stream()
                .filter(entry -> entry.getPluginId().equals(installedHost.getId()))
                .findFirst()
                .orElseThrow();
        assertEquals(PluginInstallPlan.Action.UPDATE, host.getAction());
        assertTrue(plan.getEnablementPluginIds().isEmpty());
    }

    /// Selects one shared Host when two language packages in the same concrete graph require the same runtime.
    @Test
    public void sharesOneRuntimeProviderAcrossTwoDependents() throws Exception {
        @Unmodifiable Map<String, PluginStoreItem> catalog = runtimeGraphCatalog(
                "[{\"id\":\"dev.test.rust-addon\",\"version\":\"*\"}]",
                "[]",
                true
        );

        PluginInstallPlan plan = new PluginStoreDependencyResolver(catalog, configuredSources(catalog)).resolveInstallPlan(
                "dev.test.rust-tool",
                runtimeVersion(catalog, "dev.test.rust-tool"),
                Map.of(),
                Map.of(),
                Map.of()
        );

        assertEquals(List.of("dev.test.rust-host", "dev.test.rust-addon", "dev.test.rust-tool"),
                plan.getEntries().stream().map(PluginInstallPlan.Entry::getPluginId).toList());
        assertEquals(Set.of("dev.test.rust-tool", "dev.test.rust-addon"), plan.getRuntimeBindings().keySet());
        assertEquals(1, plan.getRuntimeBindings().values().stream()
                .map(binding -> binding.providerId())
                .distinct()
                .count());
    }

    /// Rejects a cycle formed only after a language package's virtual Provider edge is added.
    @Test
    public void rejectsRuntimeProviderVirtualEdgeCycle() throws Exception {
        @Unmodifiable Map<String, PluginStoreItem> catalog = runtimeGraphCatalog(
                "[]",
                "[{\"id\":\"dev.test.rust-tool\",\"version\":\"*\"}]",
                false
        );

        IOException exception = assertThrows(IOException.class, () -> new PluginStoreDependencyResolver(
                catalog, configuredSources(catalog))
                .resolveInstallPlan(
                        "dev.test.rust-tool",
                        runtimeVersion(catalog, "dev.test.rust-tool"),
                        Map.of(),
                        Map.of(),
                        Map.of()
                ));

        assertTrue(Objects.requireNonNull(exception.getMessage()).contains("Cyclic"));
        assertTrue(exception.getMessage().contains("dev.test.rust-tool"));
    }

    /// Falls back to the next ranked Provider when the preferred Host has an unsatisfied concrete dependency.
    @Test
    public void fallsBackWhenPreferredRuntimeProviderDependencyIsMissing() throws Exception {
        @Unmodifiable Map<String, PluginStoreItem> catalog = runtimeFallbackCatalog(
                "",
                "[{\"id\":\"dev.test.missing-host-dependency\",\"version\":\"*\"}]"
        );

        PluginInstallPlan plan = new PluginStoreDependencyResolver(catalog, configuredSources(catalog))
                .resolveInstallPlan(
                        "dev.test.fallback-rust-tool",
                        runtimeVersion(catalog, "dev.test.fallback-rust-tool"),
                        Map.of(), Map.of(), Map.of()
                );

        assertEquals(List.of("dev.test.fallback-rust-host", "dev.test.fallback-rust-tool"),
                plan.getEntries().stream().map(PluginInstallPlan.Entry::getPluginId).toList());
        assertEquals("dev.test.fallback-rust-host",
                Objects.requireNonNull(plan.getRuntimeBindings().get("dev.test.fallback-rust-tool")).providerId());
    }

    /// Falls back without retaining a failed binding when the preferred Host forms a mixed runtime/concrete cycle.
    @Test
    public void fallsBackWhenPreferredRuntimeProviderFormsMixedCycle() throws Exception {
        @Unmodifiable Map<String, PluginStoreItem> catalog = runtimeFallbackCatalog(
                "",
                "[{\"id\":\"dev.test.fallback-rust-tool\",\"version\":\"*\"}]"
        );

        PluginInstallPlan plan = new PluginStoreDependencyResolver(catalog, configuredSources(catalog))
                .resolveInstallPlan(
                        "dev.test.fallback-rust-tool",
                        runtimeVersion(catalog, "dev.test.fallback-rust-tool"),
                        Map.of(), Map.of(), Map.of()
                );

        assertEquals(List.of("dev.test.fallback-rust-host", "dev.test.fallback-rust-tool"),
                plan.getEntries().stream().map(PluginInstallPlan.Entry::getPluginId).toList());
        assertEquals("dev.test.fallback-rust-host",
                Objects.requireNonNull(plan.getRuntimeBindings().get("dev.test.fallback-rust-tool")).providerId());
    }

    /// Keeps a pinned Provider fail-closed when its concrete dependency graph cannot be satisfied.
    @Test
    public void pinnedRuntimeProviderWithMissingDependencyDoesNotFallBack() throws Exception {
        @Unmodifiable Map<String, PluginStoreItem> catalog = runtimeFallbackCatalog(
                "dev.test.preferred-rust-host",
                "[{\"id\":\"dev.test.missing-host-dependency\",\"version\":\"*\"}]"
        );

        IOException exception = assertThrows(IOException.class, () -> new PluginStoreDependencyResolver(
                catalog, configuredSources(catalog))
                .resolveInstallPlan(
                        "dev.test.fallback-rust-tool",
                        runtimeVersion(catalog, "dev.test.fallback-rust-tool"),
                        Map.of(), Map.of(), Map.of()
                ));

        assertTrue(Objects.requireNonNull(exception.getMessage()).contains("dev.test.missing-host-dependency"));
    }

    /// Requires a separate receipt when the only compatible Host comes from a custom source.
    @Test
    public void customSourceRuntimeProviderRequiresConfirmation() throws Exception {
        @Unmodifiable Map<String, PluginStoreItem> catalog = runtimeCatalog(false, "");

        PluginInstallPlan plan = new PluginStoreDependencyResolver(catalog, configuredSources(catalog)).resolveInstallPlan(
                "dev.test.rust-tool", runtimeVersion(catalog, "dev.test.rust-tool"),
                Map.of(), Map.of(), Map.of()
        );

        assertEquals(true, plan.getClass().getMethod("requiresCustomSourceConfirmation").invoke(plan));
    }

    /// Makes an unavailable explicit provider pin fail closed without selecting another compatible Host.
    @Test
    public void unavailableRuntimeProviderPinDoesNotFallBack() throws Exception {
        @Unmodifiable Map<String, PluginStoreItem> catalog = runtimeCatalog(
                true,
                "dev.test.missing-rust-host"
        );

        IOException exception = assertThrows(IOException.class, () -> new PluginStoreDependencyResolver(
                catalog, configuredSources(catalog))
                .resolveInstallPlan(
                        "dev.test.rust-tool", runtimeVersion(catalog, "dev.test.rust-tool"),
                        Map.of(), Map.of(), Map.of()
                ));

        assertTrue(Objects.requireNonNull(exception.getMessage()).contains("dev.test.missing-rust-host"));
    }

    /// Uses the exact selected version's trust instead of inheriting certification from another version or item.
    @Test
    public void selectedVersionTrustControlsDependencyResolution() throws Exception {
        String pluginId = "dev.test.version-trust";
        PluginStoreManifest manifest = Objects.requireNonNull(JsonUtils.GSON.fromJson("""
                {
                  "schemaVersion": 2,
                  "id": "%s",
                  "versions": [
                    {
                      "version": "2.0.0",
                      "packageUrl": "https://example.com/v2.npl",
                      "sha256": "%s",
                      "pluginApiVersion": 4,
                      "permissions": [],
                      "requiredPermissions": [],
                      "launcherVersion": "*",
                      "dependencies": [],
                      "size": 2
                    },
                    {
                      "version": "1.0.0",
                      "packageUrl": "https://example.com/v1.npl",
                      "sha256": "%s",
                      "pluginApiVersion": 4,
                      "permissions": [],
                      "requiredPermissions": [],
                      "launcherVersion": "*",
                      "dependencies": [],
                      "size": 1
                    }
                  ]
                }
                """.formatted(pluginId, "2".repeat(64), "1".repeat(64)), PluginStoreManifest.class));
        manifest.validate(pluginId);
        PluginStoreManifest.PluginVersionEntry rejected = Objects.requireNonNull(manifest.getVersion("2.0.0"));
        PluginStoreManifest.PluginVersionEntry certified = Objects.requireNonNull(manifest.getVersion("1.0.0"));
        rejected.setTrust(PluginTrustResult.rejected("artifact proof does not match"));
        certified.setTrust(PluginTrustResult.certified("ed25519:artifact", "verification-17"));
        PluginStoreRegistry registry = Objects.requireNonNull(JsonUtils.GSON.fromJson("""
                {"schemaVersion":1,"name":"Fixture","plugins":[{
                  "id":"%s","name":"Version Trust","manifestUrl":"https://example.com/manifest.json"
                }]}
                """.formatted(pluginId), PluginStoreRegistry.class));
        registry.validate();
        PluginSource source = new PluginSource(
                "fixture", "https://example.com/plugins.json", "Fixture", true, false
        );
        PluginStoreItem item = new PluginStoreItem(
                source,
                registry,
                new PluginStoreManager(),
                registry.getPlugins().get(0),
                manifest,
                PluginTrustResult.certified("ed25519:legacy-item", "legacy")
        );
        @Unmodifiable Map<String, PluginStoreItem> catalog = Map.of(pluginId, item);
        PluginStoreDependencyResolver resolver = new PluginStoreDependencyResolver(
                catalog, configuredSources(catalog));

        IOException rejectedFailure = assertThrows(IOException.class, () -> resolver.resolveInstallPlan(
                pluginId, rejected, Map.of(), Map.of(), Map.of()
        ));
        PluginInstallPlan accepted = resolver.resolveInstallPlan(
                pluginId, certified, Map.of(), Map.of(), Map.of()
        );

        assertTrue(Objects.requireNonNull(rejectedFailure.getMessage()).contains("artifact proof"));
        assertEquals(PluginTrustLevel.CERTIFIED,
                accepted.getEntries().get(0).getSourceProvenance().getTrustLevel());
    }

    /// Requires a fresh decision for updates with unchanged or empty declarations, but never for a reused artifact.
    @Test
    public void changedArtifactsAlwaysRequireFreshPermissionReview() throws IOException {
        PluginManifest unchangedInstalled = packageManifestWithPermissions(
                "dev.test.update.unchanged",
                "1.0.0",
                "[\"network\"]"
        );
        PluginManifest emptyInstalled = packageManifestWithPermissions(
                "dev.test.update.empty",
                "1.0.0",
                "[]"
        );
        PluginManifest reusedInstalled = packageManifestWithPermissions(
                "dev.test.reuse",
                "1.0.0",
                "[\"filesystem\"]"
        );
        PluginInstallPlan.Entry installation = new PluginInstallPlan.Entry(
                "dev.test.install",
                "Install",
                "1.0.0",
                PluginInstallPlan.Action.INSTALL,
                null,
                remoteVersion("dev.test.install", "1.0.0", "1", "[\"clipboard\"]"),
                null,
                "test",
                "Test Source",
                PluginSourceProvenance.from(new PluginSource(
                        "test", "https://test.example.test/registry.json", "Test Source", true, false
                )),
                new PluginStoreManager()
        );
        PluginInstallPlan.Entry unchangedUpdate = new PluginInstallPlan.Entry(
                "dev.test.update.unchanged",
                "Unchanged Update",
                "1.0.0",
                PluginInstallPlan.Action.UPDATE,
                null,
                remoteVersion("dev.test.update.unchanged", "1.0.0", "2", "[\"network\"]"),
                unchangedInstalled,
                "test",
                "Test Source",
                PluginSourceProvenance.from(new PluginSource(
                        "test", "https://test.example.test/registry.json", "Test Source", true, false
                )),
                new PluginStoreManager()
        );
        PluginInstallPlan.Entry emptyUpdate = new PluginInstallPlan.Entry(
                "dev.test.update.empty",
                "Empty Update",
                "1.0.0",
                PluginInstallPlan.Action.UPDATE,
                null,
                remoteVersion("dev.test.update.empty", "1.0.0", "3", "[]"),
                emptyInstalled,
                "test",
                "Test Source",
                PluginSourceProvenance.from(new PluginSource(
                        "test", "https://test.example.test/registry.json", "Test Source", true, false
                )),
                new PluginStoreManager()
        );
        PluginInstallPlan.Entry reuse = new PluginInstallPlan.Entry(
                "dev.test.reuse",
                "Reuse",
                "1.0.0",
                PluginInstallPlan.Action.REUSE,
                null,
                null,
                reusedInstalled,
                null,
                null,
                null,
                null
        );
        PluginInstallPlan plan = new PluginInstallPlan(
                "dev.test.update.unchanged",
                List.of(reuse, installation, unchangedUpdate, emptyUpdate),
                Map.of(
                        "dev.test.reuse",
                        new PluginArtifactIdentity("dev.test.reuse", "1.0.0", "f".repeat(64))
                ),
                Map.of(
                        "dev.test.install",
                        Optional.empty(),
                        "dev.test.update.unchanged",
                        Optional.of(new PluginArtifactIdentity(
                                "dev.test.update.unchanged",
                                "1.0.0",
                                "d".repeat(64)
                        )),
                        "dev.test.update.empty",
                        Optional.of(new PluginArtifactIdentity(
                                "dev.test.update.empty",
                                "1.0.0",
                                "e".repeat(64)
                        ))
                )
        );

        assertTrue(installation.requiresFreshPermissionReview());
        assertTrue(unchangedUpdate.requiresFreshPermissionReview());
        assertTrue(emptyUpdate.requiresFreshPermissionReview());
        assertFalse(reuse.requiresFreshPermissionReview());
        assertEquals(List.of(installation, unchangedUpdate, emptyUpdate), plan.getPermissionReviewEntries());
        assertEquals(List.of(PluginPermission.NETWORK), unchangedUpdate.getPermissions());
        assertTrue(emptyUpdate.getPermissions().isEmpty());
    }

    /// Sanitizes hostile aliases and remote registry names in install-plan source labels and dependency diagnostics.
    @Test
    public void sourceLabelsInPlansAndDependencyDiagnosticsNeverExposeCredentials() throws Exception {
        String rootId = "dev.test.root";
        String dependencyId = "dev.test.dependency";
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/registry", exchange -> respond(exchange, """
                {
                  "schemaVersion": 1,
                  "name": "https://user:secret@host/catalog?token=secret#fragment",
                  "plugins": [
                    {"id":"%s","name":"Root","manifestUrl":"%s/root"},
                    {"id":"%s","name":"Dependency","manifestUrl":"%s/dependency"}
                  ]
                }
                """.formatted(rootId, baseUrl, dependencyId, baseUrl)));
        server.createContext("/root", exchange -> respond(exchange, repositoryManifest(
                rootId,
                version(baseUrl, "root", "1.0.0", "1", """
                        [{"id":"%s","version":">=2.0.0"}]
                        """.formatted(dependencyId))
        )));
        server.createContext("/dependency", exchange -> respond(exchange, repositoryManifest(
                dependencyId, version(baseUrl, "dependency", "1.0.0", "2", "[]")
        )));
        server.start();

        try (PluginStoreAggregator aggregator = new PluginStoreAggregator()) {
            PluginSource source = new PluginSource(
                    "hostile",
                    baseUrl + "/registry",
                    "https://user:secret@host/catalog?token=secret#fragment",
                    true,
                    false
            );
            PluginStoreSnapshot snapshot = aggregator.refresh(List.of(source)).get();
            PluginStoreItem root = Objects.requireNonNull(snapshot.getWinningItems().get(rootId));
            PluginStoreManifest.PluginVersionEntry rootVersion = Objects.requireNonNull(
                    Objects.requireNonNull(root.getManifest()).getVersion("1.0.0")
            );
            PluginStoreDependencyResolver resolver = new PluginStoreDependencyResolver(
                    snapshot.getWinningItems(), snapshot.getSourceConfiguration().getSources());
            IOException failure = assertThrows(IOException.class, () -> resolver.resolveInstallPlan(
                    rootId, rootVersion, Map.of(), Map.of(), Map.of()
            ));

            assertSafeSourceText(Objects.requireNonNull(failure.getMessage()));
        } finally {
            server.stop(0);
        }
    }

    /// Rejects a conflict candidate when the selected catalog winner cannot satisfy a dependency constraint.
    @Test
    public void resolvesDependenciesFromWinnersWithoutConflictFallback(@TempDir Path temporaryDirectory) throws Exception {
        String rootId = "dev.test.root";
        String dependencyId = "dev.test.dependency";
        HttpServer highPriority = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        HttpServer lowPriority = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String highUrl = "http://127.0.0.1:" + highPriority.getAddress().getPort();
        String lowUrl = "http://127.0.0.1:" + lowPriority.getAddress().getPort();
        highPriority.createContext("/registry", exchange -> respond(exchange, """
                {
                  "schemaVersion": 1,
                  "name": "High Priority",
                  "plugins": [
                    {"id":"%s","name":"Root","manifestUrl":"%s/root"},
                    {"id":"%s","name":"High Dependency","manifestUrl":"%s/dependency"}
                  ]
                }
                """.formatted(rootId, highUrl, dependencyId, highUrl)));
        highPriority.createContext("/root", exchange -> respond(exchange, repositoryManifest(
                rootId,
                version(highUrl, "root", "1.0.0", "1", """
                        [{"id":"%s","version":">=2.0.0"}]
                        """.formatted(dependencyId))
        )));
        highPriority.createContext("/dependency", exchange -> respond(exchange, repositoryManifest(
                dependencyId,
                version(highUrl, "dependency", "1.0.0", "2", "[]")
        )));
        lowPriority.createContext("/registry", exchange -> respond(exchange, """
                {
                  "schemaVersion": 1,
                  "name": "Low Priority",
                  "plugins": [
                    {"id":"%s","name":"Low Dependency","manifestUrl":"%s/dependency"}
                  ]
                }
                """.formatted(dependencyId, lowUrl)));
        lowPriority.createContext("/dependency", exchange -> respond(exchange, repositoryManifest(
                dependencyId,
                version(lowUrl, "dependency", "2.0.0", "3", "[]")
        )));
        highPriority.start();
        lowPriority.start();

        try (PluginStoreAggregator aggregator = new PluginStoreAggregator()) {
            PluginStoreSnapshot snapshot = aggregator.refresh(List.of(
                    new PluginSource("high", highUrl + "/registry", null, true, false),
                    new PluginSource("low", lowUrl + "/registry", null, true, false)
            )).get();
            PluginStoreItem root = Objects.requireNonNull(snapshot.getWinningItems().get(rootId));
            PluginStoreManifest.PluginVersionEntry rootVersion = Objects.requireNonNull(
                    Objects.requireNonNull(root.getManifest()).getVersion("1.0.0")
            );

            PluginStoreDependencyResolver resolver = new PluginStoreDependencyResolver(
                    snapshot.getWinningItems(), snapshot.getSourceConfiguration().getSources());
            IOException failure = assertThrows(IOException.class, () -> resolver.resolveInstallPlan(
                    rootId, rootVersion, Map.of(), Map.of(), Map.of()
            ));

            assertTrue(Objects.requireNonNull(failure.getMessage()).contains(dependencyId));
            assertTrue(failure.getMessage().contains("High Priority"));
        } finally {
            highPriority.stop(0);
            lowPriority.stop(0);
        }
    }

    /// Retains each winner's source metadata for downloads while leaving reused entries without a remote source.
    @Test
    public void downloadableEntriesRetainTheirWinningSource(@TempDir Path temporaryDirectory) throws Exception {
        String rootId = "dev.test.source-root";
        String dependencyId = "dev.test.source-dependency";
        HttpServer rootServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        HttpServer dependencyServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String rootUrl = "http://127.0.0.1:" + rootServer.getAddress().getPort();
        String dependencyUrl = "http://127.0.0.1:" + dependencyServer.getAddress().getPort();
        rootServer.createContext("/registry", exchange -> respond(exchange, """
                {
                  "schemaVersion": 1,
                  "name": "Root Store",
                  "plugins": [
                    {"id":"%s","name":"Root","manifestUrl":"%s/root"}
                  ]
                }
                """.formatted(rootId, rootUrl)));
        rootServer.createContext("/root", exchange -> respond(exchange, repositoryManifest(
                rootId,
                version(rootUrl, "root", "1.0.0", "4", """
                        [{"id":"%s","version":"1.0.0"}]
                        """.formatted(dependencyId))
        )));
        dependencyServer.createContext("/registry", exchange -> respond(exchange, """
                {
                  "schemaVersion": 1,
                  "name": "Dependency Store",
                  "plugins": [
                    {"id":"%s","name":"Dependency","manifestUrl":"%s/dependency"}
                  ]
                }
                """.formatted(dependencyId, dependencyUrl)));
        dependencyServer.createContext("/dependency", exchange -> respond(exchange, repositoryManifest(
                dependencyId,
                version(dependencyUrl, "dependency", "1.0.0", "5", "[]")
        )));
        rootServer.start();
        dependencyServer.start();

        try (PluginStoreAggregator aggregator = new PluginStoreAggregator()) {
            PluginSource rootSource = new PluginSource(
                    "root-source", rootUrl + "/registry", "Root Alias", true, false);
            PluginSource dependencySource = new PluginSource(
                    "dependency-source", dependencyUrl + "/registry", "Dependency Alias", true, false);
            PluginStoreSnapshot snapshot = aggregator.refresh(List.of(rootSource, dependencySource)).get();
            PluginStoreItem root = Objects.requireNonNull(snapshot.getWinningItems().get(rootId));
            PluginStoreItem dependency = Objects.requireNonNull(snapshot.getWinningItems().get(dependencyId));
            PluginStoreManifest.PluginVersionEntry rootVersion = Objects.requireNonNull(
                    Objects.requireNonNull(root.getManifest()).getVersion("1.0.0")
            );
            PluginStoreDependencyResolver resolver = new PluginStoreDependencyResolver(
                    snapshot.getWinningItems(), snapshot.getSourceConfiguration().getSources());

            PluginInstallPlan installPlan = resolver.resolveInstallPlan(rootId, rootVersion, Map.of(), Map.of(), Map.of());
            PluginInstallPlan.Entry installedDependency = installPlan.getEntries().get(0);
            PluginInstallPlan.Entry installedRoot = installPlan.getRootEntry();
            assertEquals(dependencySource.getId(), installedDependency.getSourceId());
            assertEquals("Dependency Alias", installedDependency.getSourceDisplayName());
            assertFalse(installedDependency.requireSourceProvenance().isOfficial());
            assertEquals("127.0.0.1", installedDependency.requireSourceProvenance().getHostIdentity());
            assertSame(dependency.getSourceManager(), installedDependency.requireSourceManager());
            assertEquals(rootSource.getId(), installedRoot.getSourceId());
            assertEquals("Root Alias", installedRoot.getSourceDisplayName());
            assertFalse(installedRoot.requireSourceProvenance().isOfficial());
            assertEquals("127.0.0.1", installedRoot.requireSourceProvenance().getHostIdentity());
            assertSame(root.getSourceManager(), installedRoot.requireSourceManager());

            PluginManifest outdatedDependencyManifest = packageManifest(dependencyId, "0.9.0", "[]");
            PluginArtifactIdentity outdatedDependencyIdentity = PluginArtifactIdentity.of(
                    outdatedDependencyManifest,
                    "9".repeat(64)
            );
            PluginInstallPlan updatePlan = resolver.resolveInstallPlan(
                    rootId,
                    rootVersion,
                    Map.of(dependencyId, outdatedDependencyManifest),
                    Map.of(dependencyId, outdatedDependencyIdentity),
                    Map.of()
            );
            PluginInstallPlan.Entry updatedDependency = updatePlan.getEntries().stream()
                    .filter(entry -> entry.getPluginId().equals(dependencyId))
                    .findFirst()
                    .orElseThrow();

            assertEquals(PluginInstallPlan.Action.UPDATE, updatedDependency.getAction());
            assertEquals(dependencySource.getId(), updatedDependency.getSourceId());
            assertEquals("Dependency Alias", updatedDependency.getSourceDisplayName());
            assertSame(dependency.getSourceManager(), updatedDependency.requireSourceManager());

            PluginManifest installedDependencyManifest = packageManifest(dependencyId, "1.0.0", "[]");
            PluginArtifactIdentity dependencyIdentity = PluginArtifactIdentity.of(installedDependencyManifest, "a".repeat(64));
            PluginInstallPlan reusePlan = resolver.resolveInstallPlan(
                    rootId,
                    rootVersion,
                    Map.of(dependencyId, installedDependencyManifest),
                    Map.of(dependencyId, dependencyIdentity),
                    Map.of(dependencyId, dependencyIdentity)
            );
            PluginInstallPlan.Entry reusedDependency = reusePlan.getEntries().stream()
                    .filter(entry -> entry.getPluginId().equals(dependencyId))
                    .findFirst()
                    .orElseThrow();
            PluginInstallPlan.Entry updatedRoot = reusePlan.getRootEntry();

            assertEquals(PluginInstallPlan.Action.REUSE, reusedDependency.getAction());
            assertNull(reusedDependency.getSourceId());
            assertNull(reusedDependency.getSourceDisplayName());
            assertNull(reusedDependency.getSourceProvenance());
            assertNull(reusedDependency.getSourceManager());
            assertThrows(IllegalStateException.class, reusedDependency::requireSourceProvenance);
            assertThrows(IllegalStateException.class, reusedDependency::requireSourceManager);
            assertEquals(rootSource.getId(), updatedRoot.getSourceId());
            assertEquals("Root Alias", updatedRoot.getSourceDisplayName());
            assertSame(root.getSourceManager(), updatedRoot.requireSourceManager());
        } finally {
            rootServer.stop(0);
            dependencyServer.stop(0);
        }
    }

    /// Treats a selected remote artifact as an update even when its version string matches the installed package.
    @Test
    public void sameVersionDifferentShaStillResolvesAsUpdate(@TempDir Path temporaryDirectory) throws Exception {
        String pluginId = "dev.test.same-version-update";
        String previousArtifactSha256 = "a".repeat(64);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/registry", exchange -> respond(exchange, """
                {
                  "schemaVersion": 1,
                  "name": "Same Version Update Store",
                  "plugins": [
                    {"id":"%s","name":"Same Version","manifestUrl":"%s/plugin"}
                  ]
                }
                """.formatted(pluginId, baseUrl)));
        server.createContext("/plugin", exchange -> respond(exchange, repositoryManifest(
                pluginId,
                version(baseUrl, "plugin", "1.0.0", "b", "[]")
        )));
        server.start();

        try {
            PluginStoreManager manager = new PluginStoreManager();
            manager.loadSource(new PluginSource("test", baseUrl + "/registry", null, true, false));
            PluginStoreManifest manifest = manager.getPluginManifest(pluginId, baseUrl + "/plugin");
            PluginStoreManifest.PluginVersionEntry selectedVersion = manifest.getVersion("1.0.0");
            assertNotNull(selectedVersion);
            @Unmodifiable Map<String, PluginManifest> installed = Map.of(
                    pluginId,
                    packageManifest(pluginId, "1.0.0", "[]")
            );
            PluginArtifactIdentity previousIdentity = new PluginArtifactIdentity(
                    pluginId,
                    "1.0.0",
                    previousArtifactSha256
            );

            PluginInstallPlan plan = manager.resolveInstallPlan(
                    pluginId,
                    selectedVersion,
                    installed,
                    Map.of(pluginId, previousIdentity),
                    Map.of()
            );
            PluginInstallPlan.Entry root = plan.getRootEntry();

            assertEquals(PluginInstallPlan.Action.UPDATE, root.getAction());
            assertEquals("1.0.0", root.getVersion());
            assertNotNull(root.getRemoteVersion());
            assertNotEquals(previousArtifactSha256, root.getRemoteVersion().getSha256());
            assertEquals(List.of(root), plan.getPermissionReviewEntries());
            assertEquals(
                    Map.of(pluginId, Optional.of(previousIdentity)),
                    plan.getExpectedPriorArtifacts()
            );
        } finally {
            server.stop(0);
        }
    }

    /// Reuses an installed dependency only when the caller explicitly confirms its exact artifact permission state.
    @Test
    public void installedDependencyRequiresExplicitReuseEligibility(@TempDir Path temporaryDirectory) throws Exception {
        String rootId = "dev.test.permission-root";
        String dependencyId = "dev.test.permission-dependency";
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/registry", exchange -> respond(exchange, """
                {
                  "schemaVersion": 1,
                  "name": "Permission-Aware Reuse Store",
                  "plugins": [
                    {"id":"%s","name":"Root","manifestUrl":"%s/root"},
                    {"id":"%s","name":"Dependency","manifestUrl":"%s/dependency"}
                  ]
                }
                """.formatted(rootId, baseUrl, dependencyId, baseUrl)));
        server.createContext("/root", exchange -> respond(exchange, repositoryManifest(
                rootId,
                version(baseUrl, "root", "1.0.0", "7", """
                        [{"id":"%s","version":"1.0.0"}]
                        """.formatted(dependencyId))
        )));
        server.createContext("/dependency", exchange -> respond(exchange, repositoryManifest(
                dependencyId,
                version(baseUrl, "dependency", "1.0.0", "8", "[]")
        )));
        server.start();

        try {
            PluginStoreManager manager = new PluginStoreManager();
            manager.loadSource(new PluginSource("test", baseUrl + "/registry", null, true, false));
            PluginStoreManifest rootManifest = manager.getPluginManifest(rootId, baseUrl + "/root");
            PluginStoreManifest.PluginVersionEntry rootVersion = rootManifest.getVersion("1.0.0");
            assertNotNull(rootVersion);
            @Unmodifiable Map<String, PluginManifest> installed = Map.of(
                    dependencyId,
                    packageManifest(dependencyId, "1.0.0", "[]")
            );
            PluginArtifactIdentity dependencyIdentity = new PluginArtifactIdentity(
                    dependencyId,
                    "1.0.0",
                    "a".repeat(64)
            );

            PluginInstallPlan deniedReuse = manager.resolveInstallPlan(
                    rootId,
                    rootVersion,
                    installed,
                    Map.of(dependencyId, dependencyIdentity),
                    Map.of()
            );
            PluginInstallPlan approvedReuse = manager.resolveInstallPlan(
                    rootId,
                    rootVersion,
                    installed,
                    Map.of(dependencyId, dependencyIdentity),
                    Map.of(dependencyId, dependencyIdentity)
            );

            assertEquals(PluginInstallPlan.Action.UPDATE, deniedReuse.getEntries().get(0).getAction());
            assertEquals(
                    List.of(dependencyId, rootId),
                    deniedReuse.getPermissionReviewEntries().stream()
                            .map(PluginInstallPlan.Entry::getPluginId)
                            .toList()
            );
            assertEquals(PluginInstallPlan.Action.REUSE, approvedReuse.getEntries().get(0).getAction());
            assertEquals(
                    Map.of(dependencyId, dependencyIdentity),
                    approvedReuse.getReusableArtifactIdentities()
            );
            assertEquals(
                    List.of(rootId),
                    approvedReuse.getPermissionReviewEntries().stream()
                            .map(PluginInstallPlan.Entry::getPluginId)
                            .toList()
            );
            assertThrows(
                    IllegalArgumentException.class,
                    () -> manager.resolveInstallPlan(rootId, rootVersion, installed)
            );
            assertThrows(
                    IllegalArgumentException.class,
                    () -> manager.resolveInstallPlan(rootId, rootVersion, installed, Set.of(dependencyId))
            );
            assertThrows(
                    IllegalArgumentException.class,
                    () -> manager.resolveInstallPlan(
                            rootId,
                            rootVersion,
                            installed,
                            Map.of(dependencyId, dependencyIdentity),
                            Map.of(
                                    dependencyId,
                                    new PluginArtifactIdentity(dependencyId, "2.0.0", "b".repeat(64))
                            )
                    )
            );

            @Unmodifiable Map<String, PluginManifest> legacyInstalled = Map.of(
                    dependencyId,
                    legacyPackageManifest(dependencyId, "1.0.0", "[]")
            );
            PluginArtifactIdentity legacyIdentity = new PluginArtifactIdentity(
                    dependencyId,
                    "1.0.0",
                    "e".repeat(64)
            );
            PluginInstallPlan legacyPlan = manager.resolveInstallPlan(
                    rootId,
                    rootVersion,
                    legacyInstalled,
                    Map.of(dependencyId, legacyIdentity),
                    Map.of(dependencyId, legacyIdentity)
            );

            assertEquals(PluginInstallPlan.Action.UPDATE, legacyPlan.getEntries().get(0).getAction());
            assertTrue(legacyPlan.getReusableArtifactIdentities().isEmpty());
        } finally {
            server.stop(0);
        }
    }

    /// Fails clearly when an unauthorized installed dependency has no remote artifact for a fresh review.
    @Test
    public void unauthorizedReuseWithoutRemotePackageFailsExplicitly(@TempDir Path temporaryDirectory)
            throws Exception {
        String rootId = "dev.test.unpublished-root";
        String dependencyId = "dev.test.unpublished-dependency";
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/registry", exchange -> respond(exchange, """
                {
                  "schemaVersion": 1,
                  "name": "Unpublished Dependency Store",
                  "plugins": [
                    {"id":"%s","name":"Root","manifestUrl":"%s/root"}
                  ]
                }
                """.formatted(rootId, baseUrl)));
        server.createContext("/root", exchange -> respond(exchange, repositoryManifest(
                rootId,
                version(baseUrl, "root", "1.0.0", "9", """
                        [{"id":"%s","version":"1.0.0"}]
                        """.formatted(dependencyId))
        )));
        server.start();

        try {
            PluginStoreManager manager = new PluginStoreManager();
            manager.loadSource(new PluginSource("test", baseUrl + "/registry", null, true, false));
            PluginStoreManifest rootManifest = manager.getPluginManifest(rootId, baseUrl + "/root");
            PluginStoreManifest.PluginVersionEntry rootVersion = rootManifest.getVersion("1.0.0");
            assertNotNull(rootVersion);
            @Unmodifiable Map<String, PluginManifest> installed = Map.of(
                    dependencyId,
                    packageManifest(dependencyId, "1.0.0", "[]")
            );
            PluginArtifactIdentity dependencyIdentity = new PluginArtifactIdentity(
                    dependencyId,
                    "1.0.0",
                    "c".repeat(64)
            );

            IOException failure = assertThrows(
                    IOException.class,
                    () -> manager.resolveInstallPlan(
                            rootId,
                            rootVersion,
                            installed,
                            Map.of(dependencyId, dependencyIdentity),
                            Map.of()
                    )
            );
            PluginInstallPlan approved = manager.resolveInstallPlan(
                    rootId,
                    rootVersion,
                    installed,
                    Map.of(dependencyId, dependencyIdentity),
                    Map.of(dependencyId, dependencyIdentity)
            );

            assertTrue(Objects.requireNonNull(failure.getMessage()).contains("cannot be reused"));
            assertEquals(PluginInstallPlan.Action.REUSE, approved.getEntries().get(0).getAction());
        } finally {
            server.stop(0);
        }
    }

    /// Selects a lower shared dependency version when a later sibling contributes an additional compatible range.
    @Test
    public void backtracksToSharedVersionSatisfyingAllConstraints(@TempDir Path temporaryDirectory) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/registry", exchange -> respond(exchange, """
                {
                  "schemaVersion": 1,
                  "name": "Dependency Test Store",
                  "plugins": [
                    {"id":"dev.test.root","name":"Root","manifestUrl":"%s/root"},
                    {"id":"dev.test.bridge","name":"Bridge","manifestUrl":"%s/bridge"},
                    {"id":"dev.test.base","name":"Base","manifestUrl":"%s/base"}
                  ]
                }
                """.formatted(baseUrl, baseUrl, baseUrl)));
        server.createContext("/root", exchange -> respond(exchange, repositoryManifest(
                "dev.test.root",
                version(baseUrl, "root", "1.0.0", "0", """
                        [
                          {"id":"dev.test.base","version":">=1.0.0 <3.0.0"},
                          {"id":"dev.test.bridge","version":"1.0.0"}
                        ]
                        """)
        )));
        server.createContext("/bridge", exchange -> respond(exchange, repositoryManifest(
                "dev.test.bridge",
                version(baseUrl, "bridge", "1.0.0", "1", """
                        [{"id":"dev.test.base","version":"<2.0.0"}]
                        """)
        )));
        server.createContext("/base", exchange -> respond(exchange, repositoryManifest(
                "dev.test.base",
                version(baseUrl, "base", "2.5.0", "2", "[]") + ","
                        + version(baseUrl, "base", "1.5.0", "3", "[]")
        )));
        server.start();

        try {
            PluginStoreManager manager = new PluginStoreManager();
            manager.loadSource(new PluginSource("test", baseUrl + "/registry", null, true, false));
            PluginStoreManifest rootManifest = manager.getPluginManifest("dev.test.root", baseUrl + "/root");
            PluginStoreManifest.PluginVersionEntry rootVersion = rootManifest.getVersion("1.0.0");
            assertNotNull(rootVersion);

            PluginInstallPlan plan = manager.resolveInstallPlan("dev.test.root", rootVersion, Map.of());

            assertEquals(
                    List.of("dev.test.base", "dev.test.bridge", "dev.test.root"),
                    plan.getEntries().stream().map(PluginInstallPlan.Entry::getPluginId).toList()
            );
            assertEquals("1.5.0", plan.getEntries().get(0).getVersion());
        } finally {
            server.stop(0);
        }
    }

    /// Selects a lower remote dependency version that preserves an installed plugin's reverse constraint.
    @Test
    public void includesInstalledReverseConstraintsDuringSelection(@TempDir Path temporaryDirectory) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/registry", exchange -> respond(exchange, """
                {
                  "schemaVersion": 1,
                  "name": "Reverse Constraint Store",
                  "plugins": [
                    {"id":"dev.test.root","name":"Root","manifestUrl":"%s/root"},
                    {"id":"dev.test.base","name":"Base","manifestUrl":"%s/base"}
                  ]
                }
                """.formatted(baseUrl, baseUrl)));
        server.createContext("/root", exchange -> respond(exchange, repositoryManifest(
                "dev.test.root",
                version(baseUrl, "root", "1.0.0", "4", """
                        [{"id":"dev.test.base","version":">=1.5.0"}]
                        """)
        )));
        server.createContext("/base", exchange -> respond(exchange, repositoryManifest(
                "dev.test.base",
                version(baseUrl, "base", "2.0.0", "5", "[]") + ","
                        + version(baseUrl, "base", "1.5.0", "6", "[]")
        )));
        server.start();

        try {
            PluginStoreManager manager = new PluginStoreManager();
            manager.loadSource(new PluginSource("test", baseUrl + "/registry", null, true, false));
            PluginStoreManifest rootManifest = manager.getPluginManifest("dev.test.root", baseUrl + "/root");
            PluginStoreManifest.PluginVersionEntry rootVersion = rootManifest.getVersion("1.0.0");
            assertNotNull(rootVersion);
            @Unmodifiable Map<String, PluginManifest> installed = Map.of(
                    "dev.test.base",
                    packageManifest("dev.test.base", "1.0.0", "[]"),
                    "dev.test.consumer",
                    packageManifest("dev.test.consumer", "1.0.0", """
                            [{"id":"dev.test.base","version":"<2.0.0"}]
                            """)
            );

            PluginInstallPlan plan = manager.resolveInstallPlan(
                    "dev.test.root",
                    rootVersion,
                    installed,
                    artifactIdentities(installed, "d"),
                    Map.of()
            );

            assertEquals("1.5.0", plan.getEntries().get(0).getVersion());
            assertEquals(PluginInstallPlan.Action.UPDATE, plan.getEntries().get(0).getAction());

            @Unmodifiable Map<String, PluginManifest> legacyReverseDependent = Map.of(
                    "dev.test.base",
                    packageManifest("dev.test.base", "1.0.0", "[]"),
                    "dev.test.consumer",
                    legacyPackageManifest("dev.test.consumer", "1.0.0", """
                            [{"id":"dev.test.base","version":"<2.0.0"}]
                            """)
            );
            PluginInstallPlan legacyPlan = manager.resolveInstallPlan(
                    "dev.test.root",
                    rootVersion,
                    legacyReverseDependent,
                    artifactIdentities(legacyReverseDependent, "e"),
                    Map.of()
            );

            assertEquals("2.0.0", legacyPlan.getEntries().get(0).getVersion());
        } finally {
            server.stop(0);
        }
    }

    /// Creates exact fake artifact identities for one installed-manifest test snapshot.
    ///
    /// @param manifests installed manifests indexed by plugin ID
    /// @param digestCharacter one lower-case hexadecimal character repeated to form each digest
    /// @return immutable exact identities matching every installed manifest
    private static @Unmodifiable Map<String, PluginArtifactIdentity> artifactIdentities(
            @Unmodifiable Map<String, PluginManifest> manifests,
            String digestCharacter
    ) {
        Map<String, PluginArtifactIdentity> identities = new LinkedHashMap<>();
        for (Map.Entry<String, PluginManifest> entry : manifests.entrySet()) {
            identities.put(
                    entry.getKey(),
                    PluginArtifactIdentity.of(entry.getValue(), digestCharacter.repeat(64))
            );
        }
        return Map.copyOf(identities);
    }

    /// Builds one official Rust consumer and one official or custom Rust Host catalog.
    ///
    /// @param providerOfficial whether the Host belongs to the official source
    /// @param providerPin optional provider ID pin, or an empty string for unpinned selection
    /// @return immutable winning catalog indexed by plugin ID
    /// @throws IOException if generated Store metadata is invalid
    private static @Unmodifiable Map<String, PluginStoreItem> runtimeCatalog(
            boolean providerOfficial,
            String providerPin
    ) throws IOException {
        String target = PluginPlatformTarget.current().getId();
        PluginStoreRegistry registry = Objects.requireNonNull(JsonUtils.GSON.fromJson("""
                {"schemaVersion":1,"name":"Runtime Fixtures","plugins":[
                  {"id":"dev.test.rust-tool","name":"Rust Tool",
                   "manifestUrl":"https://example.com/rust-tool.json"},
                  {"id":"dev.test.rust-host","name":"Rust Host",
                   "manifestUrl":"https://example.com/rust-host.json"}
                ]}
                """, PluginStoreRegistry.class));
        registry.validate();
        PluginStoreManifest rootManifest = parseRuntimeStoreManifest("dev.test.rust-tool", """
                "runtime": "rust",
                "abi": 2,
                "pluginKind": "normal",
                "executionMode": "embedded",
                %s
                "artifacts": [{"platform": "%s", "packageUrl": "https://example.com/rust-tool.npl",
                  "sha256": "%s", "size": 17}]
                """.formatted(
                providerPin.isEmpty() ? "" : "\"runtimeProvider\": \"" + providerPin + "\",",
                target,
                "b".repeat(64)
        ));
        PluginStoreManifest hostManifest = parseRuntimeStoreManifest("dev.test.rust-host", """
                "runtime": "java",
                "abi": 2,
                "pluginKind": "runtime-provider",
                "providesRuntimes": [{"runtime": "rust", "abis": [2], "bridgeAbi": 1,
                  "executionModes": ["embedded"], "features": ["bridge"]}],
                "artifacts": [{"platform": "%s", "packageUrl": "https://example.com/rust-host.npl",
                  "sha256": "%s", "size": 41}]
                """.formatted(target, "c".repeat(64)));
        PluginStoreManifest.PluginVersionEntry rootVersion = rootManifest.getVersions().get(0);
        PluginStoreManifest.PluginVersionEntry hostVersion = hostManifest.getVersions().get(0);
        rootVersion.setTrust(PluginTrustResult.official("root-key"));
        hostVersion.setTrust(providerOfficial
                ? PluginTrustResult.official("provider-key")
                : PluginTrustResult.community());
        PluginStoreManager manager = new PluginStoreManager();
        PluginSource rootSource = new PluginSource(
                "official", "https://example.com/official.json", "Official", true, true
        );
        PluginSource providerSource = providerOfficial
                ? rootSource
                : new PluginSource("custom", "https://custom.example/plugins.json", "Custom", true, false);
        Map<String, PluginStoreItem> items = new LinkedHashMap<>();
        items.put("dev.test.rust-tool", new PluginStoreItem(
                rootSource, registry, manager, registry.getPlugins().get(0), rootManifest
        ));
        items.put("dev.test.rust-host", new PluginStoreItem(
                providerSource, registry, manager, registry.getPlugins().get(1), hostManifest
        ));
        return Map.copyOf(items);
    }

    /// Builds a configured-order snapshot whose first winner is replaced by a lower-priority source.
    ///
    /// @return immutable snapshot with two compatible Runtime Providers
    /// @throws IOException if generated Store metadata is invalid
    private static PluginStoreSnapshot runtimeSourcePrioritySnapshot() throws IOException {
        String target = PluginPlatformTarget.current().getId();
        String replacedProviderId = "dev.test.replaced-rust-host";
        String preferredProviderId = "dev.test.configured-first-rust-host";
        String rootId = "dev.test.priority-rust-tool";
        PluginStoreRegistry firstRegistry = Objects.requireNonNull(JsonUtils.GSON.fromJson("""
                {"schemaVersion":1,"name":"Configured First","plugins":[
                  {"id":"%s","name":"Replaced Host","manifestUrl":"https://first.example/replaced.json"},
                  {"id":"%s","name":"Preferred Host","manifestUrl":"https://first.example/preferred.json"}
                ]}
                """.formatted(replacedProviderId, preferredProviderId), PluginStoreRegistry.class));
        PluginStoreRegistry secondRegistry = Objects.requireNonNull(JsonUtils.GSON.fromJson("""
                {"schemaVersion":1,"name":"Configured Second","plugins":[
                  {"id":"%s","name":"Winning Replacement Host",
                   "manifestUrl":"https://second.example/replaced.json"},
                  {"id":"%s","name":"Rust Tool","manifestUrl":"https://second.example/tool.json"}
                ]}
                """.formatted(replacedProviderId, rootId), PluginStoreRegistry.class));
        firstRegistry.validate();
        secondRegistry.validate();
        String providerDeclarations = """
                "runtime": "java", "abi": 2, "pluginKind": "runtime-provider",
                "providesRuntimes": [{"runtime": "rust", "abis": [2], "bridgeAbi": 1,
                  "executionModes": ["embedded"], "features": ["bridge"]}],
                "artifacts": [{"platform": "%s", "packageUrl": "https://example.com/host.npl",
                  "sha256": "%s", "size": 41}]
                """;
        PluginStoreManifest replacedFromFirst = parseRuntimeStoreManifest(
                replacedProviderId, providerDeclarations.formatted(target, "a".repeat(64)));
        PluginStoreManifest replacementFromSecond = parseRuntimeStoreManifest(
                replacedProviderId, providerDeclarations.formatted(target, "b".repeat(64)));
        PluginStoreManifest preferred = parseRuntimeStoreManifest(
                preferredProviderId, providerDeclarations.formatted(target, "c".repeat(64)));
        PluginStoreManifest root = parseRuntimeStoreManifest(rootId, """
                "runtime": "rust", "abi": 2, "pluginKind": "normal", "executionMode": "embedded",
                "artifacts": [{"platform": "%s", "packageUrl": "https://example.com/tool.npl",
                  "sha256": "%s", "size": 17}]
                """.formatted(target, "d".repeat(64)));
        replacedFromFirst.getVersions().get(0).setTrust(PluginTrustResult.community());
        replacementFromSecond.getVersions().get(0).setTrust(PluginTrustResult.official("replacement-key"));
        preferred.getVersions().get(0).setTrust(PluginTrustResult.official("preferred-key"));
        root.getVersions().get(0).setTrust(PluginTrustResult.official("root-key"));
        PluginSource firstSource = new PluginSource(
                "configured-first", "https://first.example/registry.json", "First", true, false);
        PluginSource secondSource = new PluginSource(
                "configured-second", "https://second.example/registry.json", "Second", true, false);
        PluginStoreManager firstManager = new PluginStoreManager();
        PluginStoreManager secondManager = new PluginStoreManager();
        PluginStoreItem replacedFirstItem = new PluginStoreItem(
                firstSource, firstRegistry, firstManager, firstRegistry.getPlugins().get(0), replacedFromFirst);
        PluginStoreItem preferredItem = new PluginStoreItem(
                firstSource, firstRegistry, firstManager, firstRegistry.getPlugins().get(1), preferred);
        PluginStoreItem replacementSecondItem = new PluginStoreItem(
                secondSource, secondRegistry, secondManager, secondRegistry.getPlugins().get(0), replacementFromSecond);
        PluginStoreItem rootItem = new PluginStoreItem(
                secondSource, secondRegistry, secondManager, secondRegistry.getPlugins().get(1), root);
        return new PluginStoreSnapshot(1, List.of(
                PluginSourceLoadResult.success(
                        firstSource, 1, List.of(replacedFirstItem, preferredItem), 0, firstRegistry, firstManager),
                PluginSourceLoadResult.success(
                        secondSource, 1, List.of(replacementSecondItem, rootItem), 0,
                        secondRegistry, secondManager)
        ));
    }

    /// Builds a runtime graph with configurable root and Host dependencies and an optional second Rust consumer.
    ///
    /// @param rootDependenciesJson root dependency array JSON
    /// @param hostDependenciesJson Host dependency array JSON
    /// @param includeSecondConsumer whether the root dependency target is published
    /// @return immutable source-priority catalog
    /// @throws IOException if generated Store metadata is invalid
    private static @Unmodifiable Map<String, PluginStoreItem> runtimeGraphCatalog(
            String rootDependenciesJson,
            String hostDependenciesJson,
            boolean includeSecondConsumer
    ) throws IOException {
        String target = PluginPlatformTarget.current().getId();
        String secondRegistryEntry = includeSecondConsumer
                ? ",{\"id\":\"dev.test.rust-addon\",\"name\":\"Rust Addon\","
                + "\"manifestUrl\":\"https://example.com/rust-addon.json\"}"
                : "";
        PluginStoreRegistry registry = Objects.requireNonNull(JsonUtils.GSON.fromJson("""
                {"schemaVersion":1,"name":"Runtime Graph","plugins":[
                  {"id":"dev.test.rust-tool","name":"Rust Tool",
                   "manifestUrl":"https://example.com/rust-tool.json"},
                  {"id":"dev.test.rust-host","name":"Rust Host",
                   "manifestUrl":"https://example.com/rust-host.json"}%s
                ]}
                """.formatted(secondRegistryEntry), PluginStoreRegistry.class));
        registry.validate();
        PluginStoreManifest rootManifest = parseRuntimeStoreManifest("dev.test.rust-tool", """
                "runtime": "rust",
                "abi": 2,
                "pluginKind": "normal",
                "executionMode": "embedded",
                "dependencies": %s,
                "artifacts": [{"platform": "%s", "packageUrl": "https://example.com/rust-tool.npl",
                  "sha256": "%s", "size": 17}]
                """.formatted(rootDependenciesJson, target, "b".repeat(64)));
        PluginStoreManifest hostManifest = parseRuntimeStoreManifest("dev.test.rust-host", """
                "runtime": "java",
                "abi": 2,
                "pluginKind": "runtime-provider",
                "providesRuntimes": [{"runtime": "rust", "abis": [2], "bridgeAbi": 1,
                  "executionModes": ["embedded"], "features": ["bridge"]}],
                "dependencies": %s,
                "artifacts": [{"platform": "%s", "packageUrl": "https://example.com/rust-host.npl",
                  "sha256": "%s", "size": 41}]
                """.formatted(hostDependenciesJson, target, "c".repeat(64)));
        @Nullable PluginStoreManifest addonManifest = includeSecondConsumer
                ? parseRuntimeStoreManifest("dev.test.rust-addon", """
                        "runtime": "rust",
                        "abi": 2,
                        "pluginKind": "normal",
                        "executionMode": "embedded",
                        "artifacts": [{"platform": "%s", "packageUrl": "https://example.com/rust-addon.npl",
                          "sha256": "%s", "size": 19}]
                        """.formatted(target, "d".repeat(64)))
                : null;
        rootManifest.getVersions().get(0).setTrust(PluginTrustResult.official("root-key"));
        hostManifest.getVersions().get(0).setTrust(PluginTrustResult.official("host-key"));
        if (addonManifest != null) {
            addonManifest.getVersions().get(0).setTrust(PluginTrustResult.official("addon-key"));
        }
        PluginSource source = new PluginSource(
                "official", "https://example.com/official.json", "Official", true, true
        );
        PluginStoreManager manager = new PluginStoreManager();
        Map<String, PluginStoreItem> items = new LinkedHashMap<>();
        items.put("dev.test.rust-tool", new PluginStoreItem(
                source, registry, manager, registry.getPlugins().get(0), rootManifest
        ));
        items.put("dev.test.rust-host", new PluginStoreItem(
                source, registry, manager, registry.getPlugins().get(1), hostManifest
        ));
        if (addonManifest != null) {
            items.put("dev.test.rust-addon", new PluginStoreItem(
                    source, registry, manager, registry.getPlugins().get(2), addonManifest
            ));
        }
        return Map.copyOf(items);
    }

    /// Builds a Rust consumer with a higher-version preferred Host and a valid lower-version fallback Host.
    ///
    /// @param providerPin optional preferred Provider pin, or an empty string for ranked selection
    /// @param preferredDependenciesJson concrete dependency array for the preferred Provider
    /// @return immutable source-priority catalog
    /// @throws IOException if generated Store metadata is invalid
    private static @Unmodifiable Map<String, PluginStoreItem> runtimeFallbackCatalog(
            String providerPin,
            String preferredDependenciesJson
    ) throws IOException {
        String target = PluginPlatformTarget.current().getId();
        PluginStoreRegistry registry = Objects.requireNonNull(JsonUtils.GSON.fromJson("""
                {"schemaVersion":1,"name":"Runtime Fallback","plugins":[
                  {"id":"dev.test.fallback-rust-tool","name":"Rust Tool",
                   "manifestUrl":"https://example.com/rust-tool.json"},
                  {"id":"dev.test.preferred-rust-host","name":"Preferred Rust Host",
                   "manifestUrl":"https://example.com/preferred-rust-host.json"},
                  {"id":"dev.test.fallback-rust-host","name":"Fallback Rust Host",
                   "manifestUrl":"https://example.com/fallback-rust-host.json"}
                ]}
                """, PluginStoreRegistry.class));
        registry.validate();
        PluginStoreManifest rootManifest = parseRuntimeStoreManifest("dev.test.fallback-rust-tool", """
                "runtime": "rust", "abi": 2, "pluginKind": "normal", "executionMode": "embedded",
                %s
                "artifacts": [{"platform": "%s", "packageUrl": "https://example.com/rust-tool.npl",
                  "sha256": "%s", "size": 17}]
                """.formatted(
                providerPin.isEmpty() ? "" : "\"runtimeProvider\": \"" + providerPin + "\",",
                target,
                "a".repeat(64)
        ));
        String providerDeclarations = """
                "runtime": "java", "abi": 2, "pluginKind": "runtime-provider",
                "providesRuntimes": [{"runtime": "rust", "abis": [2], "bridgeAbi": 1,
                  "executionModes": ["embedded"], "features": ["bridge"]}],
                "dependencies": %s,
                "artifacts": [{"platform": "%s", "packageUrl": "https://example.com/rust-host.npl",
                  "sha256": "%s", "size": 41}]
                """;
        PluginStoreManifest preferredManifest = parseRuntimeStoreManifestVersion(
                "dev.test.preferred-rust-host",
                "2.0.0",
                providerDeclarations.formatted(preferredDependenciesJson, target, "b".repeat(64))
        );
        PluginStoreManifest fallbackManifest = parseRuntimeStoreManifestVersion(
                "dev.test.fallback-rust-host",
                "1.0.0",
                providerDeclarations.formatted("[]", target, "c".repeat(64))
        );
        for (PluginStoreManifest manifest : List.of(rootManifest, preferredManifest, fallbackManifest)) {
            manifest.getVersions().get(0).setTrust(PluginTrustResult.official("runtime-fallback-key"));
        }
        PluginSource source = new PluginSource(
                "official", "https://example.com/official.json", "Official", true, true
        );
        PluginStoreManager manager = new PluginStoreManager();
        Map<String, PluginStoreItem> items = new LinkedHashMap<>();
        items.put("dev.test.fallback-rust-tool", new PluginStoreItem(
                source, registry, manager, registry.getPlugins().get(0), rootManifest));
        items.put("dev.test.preferred-rust-host", new PluginStoreItem(
                source, registry, manager, registry.getPlugins().get(1), preferredManifest));
        items.put("dev.test.fallback-rust-host", new PluginStoreItem(
                source, registry, manager, registry.getPlugins().get(2), fallbackManifest));
        return Map.copyOf(items);
    }

    /// Parses one schema-v5 Store version with exact current-platform artifacts.
    ///
    /// @param pluginId repository plugin ID
    /// @param declarations runtime role and artifact declarations
    /// @return validated repository manifest
    /// @throws IOException if generated metadata violates Store validation
    private static PluginStoreManifest parseRuntimeStoreManifest(
            String pluginId,
            String declarations
    ) throws IOException {
        return parseRuntimeStoreManifestVersion(pluginId, "1.0.0", declarations);
    }

    /// Parses one schema-v5 Store version with an explicit version and exact current-platform artifacts.
    ///
    /// @param pluginId repository plugin ID
    /// @param version package version
    /// @param declarations runtime role and artifact declarations
    /// @return validated repository manifest
    /// @throws IOException if generated metadata violates Store validation
    private static PluginStoreManifest parseRuntimeStoreManifestVersion(
            String pluginId,
            String version,
            String declarations
    ) throws IOException {
        return PluginStoreManifest.fromJson(JsonUtils.GSON.fromJson("""
                {
                  "schemaVersion": 2,
                  "id": "%s",
                  "versions": [{
                    "version": "%s",
                    "pluginApiVersion": 5,
                    "permissions": [],
                    "requiredPermissions": [],
                    "launcherVersion": "*",
                    "platforms": ["%s"],
                    "dependencies": [],
                    %s
                  }]
                }
                """.formatted(pluginId, version, PluginPlatformTarget.current().getId(), declarations),
                com.google.gson.JsonElement.class), pluginId);
    }

    /// Returns the selected version for one complete catalog item.
    ///
    /// @param catalog winning catalog
    /// @param pluginId selected plugin ID
    /// @return sole fixture version
    private static PluginStoreManifest.PluginVersionEntry runtimeVersion(
            @Unmodifiable Map<String, PluginStoreItem> catalog,
            String pluginId
    ) {
        return Objects.requireNonNull(catalog.get(pluginId).getManifest()).getVersions().get(0);
    }

    /// Lists the explicit source order already encoded by simple hand-built catalog fixtures.
    ///
    /// @param catalog hand-built winning catalog
    /// @return immutable first-appearance source list
    private static @Unmodifiable List<PluginSource> configuredSources(
            @Unmodifiable Map<String, PluginStoreItem> catalog
    ) {
        Map<String, PluginSource> sources = new LinkedHashMap<>();
        catalog.values().forEach(item -> sources.putIfAbsent(item.getSource().getId(), item.getSource()));
        return List.copyOf(sources.values());
    }

    /// Parses one installed Java Host that provides the Rust runtime.
    ///
    /// @return installed provider manifest
    /// @throws IOException if the package fixture is invalid
    private static PluginManifest installedRuntimeProviderManifest() throws IOException {
        return PluginManifest.fromJson(new StringReader("""
                {
                  "schemaVersion": 5,
                  "id": "dev.test.rust-host",
                  "name": "Rust Host",
                  "version": "1.0.0",
                  "type": "java",
                  "entrypoint": "dev.test.RustHost",
                  "permissions": [],
                  "requiredPermissions": [],
                  "launcherVersion": "*",
                  "runtime": "java",
                  "abi": 2,
                  "platforms": ["%s"],
                  "pluginKind": "runtime-provider",
                  "providesRuntimes": [{"runtime": "rust", "abis": [2], "bridgeAbi": 1,
                    "executionModes": ["embedded"], "features": ["bridge"]}]
                }
                """.formatted(PluginPlatformTarget.current().getId())));
    }

    /// Creates one schema-v2 repository manifest around already serialized version entries.
    ///
    /// @param pluginId repository plugin ID
    /// @param versionsJson one or more comma-separated version objects
    /// @return repository manifest JSON
    private static String repositoryManifest(String pluginId, String versionsJson) {
        return """
                {
                  "schemaVersion": 2,
                  "id": "%s",
                  "versions": [%s]
                }
                """.formatted(pluginId, versionsJson);
    }

    /// Creates one compatible API-v4 repository version with explicit permissions and dependencies.
    ///
    /// @param baseUrl local test server base URL
    /// @param packageName unused package route name
    /// @param pluginVersion published plugin version
    /// @param hashDigit repeated hexadecimal checksum digit
    /// @param dependenciesJson dependency array JSON
    /// @return version entry JSON
    private static String version(
            String baseUrl,
            String packageName,
            String pluginVersion,
            String hashDigit,
            String dependenciesJson
    ) {
        return """
                {
                  "version": "%s",
                  "packageUrl": "%s/%s.npl",
                  "sha256": "%s",
                  "pluginApiVersion": 4,
                  "permissions": [],
                  "requiredPermissions": [],
                  "launcherVersion": "*",
                  "dependencies": %s,
                  "size": 1
                }
                """.formatted(pluginVersion, baseUrl, packageName, hashDigit.repeat(64), dependenciesJson);
    }

    /// Parses one remote API-v4 version with an explicit permission declaration.
    ///
    /// @param pluginId repository plugin ID
    /// @param pluginVersion published version
    /// @param hashDigit repeated hexadecimal checksum digit
    /// @param permissionsJson permission declaration JSON
    /// @return validated remote version metadata
    /// @throws IOException if the generated fixture is invalid
    private static PluginStoreManifest.PluginVersionEntry remoteVersion(
            String pluginId,
            String pluginVersion,
            String hashDigit,
            String permissionsJson
    ) throws IOException {
        PluginStoreManifest manifest = Objects.requireNonNull(
                JsonUtils.GSON.fromJson("""
                        {
                          "schemaVersion": 2,
                          "id": "%s",
                          "versions": [
                            {
                              "version": "%s",
                              "packageUrl": "https://example.com/%s.npl",
                              "sha256": "%s",
                              "pluginApiVersion": 4,
                              "permissions": %s,
                              "requiredPermissions": [],
                              "launcherVersion": "*",
                              "dependencies": [],
                              "size": 1
                            }
                          ]
                        }
                        """.formatted(
                        pluginId,
                        pluginVersion,
                        pluginId,
                        hashDigit.repeat(64),
                        permissionsJson
                ), PluginStoreManifest.class),
                "Generated repository manifest was null"
        );
        manifest.validate(pluginId);
        return Objects.requireNonNull(manifest.getVersion(pluginVersion), "Generated version was missing");
    }

    /// Parses one minimal API-v4 package manifest for installed-graph tests.
    ///
    /// @param pluginId plugin ID
    /// @param pluginVersion installed version
    /// @param dependenciesJson dependency array JSON
    /// @return validated package manifest
    /// @throws IOException if the generated fixture is invalid
    private static PluginManifest packageManifest(
            String pluginId,
            String pluginVersion,
            String dependenciesJson
    ) throws IOException {
        return PluginManifest.fromJson(new StringReader("""
                {
                  "schemaVersion": 4,
                  "id": "%s",
                  "name": "%s",
                  "version": "%s",
                  "type": "java",
                  "entrypoint": "dev.test.Plugin",
                  "permissions": [],
                  "requiredPermissions": [],
                  "launcherVersion": "*",
                  "dependencies": %s
                }
                """.formatted(pluginId, pluginId, pluginVersion, dependenciesJson)));
    }

    /// Parses one minimal installed manifest with an explicit permission declaration.
    ///
    /// @param pluginId plugin ID
    /// @param pluginVersion installed version
    /// @param permissionsJson permission declaration JSON
    /// @return validated package manifest
    /// @throws IOException if the generated fixture is invalid
    private static PluginManifest packageManifestWithPermissions(
            String pluginId,
            String pluginVersion,
            String permissionsJson
    ) throws IOException {
        return PluginManifest.fromJson(new StringReader("""
                {
                  "schemaVersion": 4,
                  "id": "%s",
                  "name": "%s",
                  "version": "%s",
                  "type": "java",
                  "entrypoint": "dev.test.Plugin",
                  "permissions": %s,
                  "requiredPermissions": [],
                  "launcherVersion": "*",
                  "dependencies": []
                }
                """.formatted(pluginId, pluginId, pluginVersion, permissionsJson)));
    }

    /// Parses one legacy schema-v3 package manifest retained only for management and update tests.
    ///
    /// @param pluginId plugin ID
    /// @param pluginVersion installed version
    /// @param dependenciesJson dependency array JSON
    /// @return validated legacy package manifest
    /// @throws IOException if the generated fixture is invalid
    private static PluginManifest legacyPackageManifest(
            String pluginId,
            String pluginVersion,
            String dependenciesJson
    ) throws IOException {
        return PluginManifest.fromJson(new StringReader("""
                {
                  "schemaVersion": 3,
                  "id": "%s",
                  "name": "%s",
                  "version": "%s",
                  "type": "java",
                  "entrypoint": "dev.test.Plugin",
                  "permissions": [],
                  "dependencies": %s
                }
                """.formatted(pluginId, pluginId, pluginVersion, dependenciesJson)));
    }

    /// Asserts that source text cannot disclose a credential-bearing hostile label.
    ///
    /// @param text source-derived text shown to a user
    private static void assertSafeSourceText(String text) {
        assertTrue(text.contains("127.0.0.1"));
        assertTrue(!text.contains("secret"));
        assertTrue(!text.contains("token"));
        assertTrue(!text.contains("#fragment"));
        assertTrue(!text.contains("https://user:"));
    }

    /// Writes a UTF-8 JSON response for one local repository route.
    ///
    /// @param exchange incoming HTTP exchange
    /// @param body response body
    /// @throws IOException if the response cannot be written
    private static void respond(HttpExchange exchange, String body) throws IOException {
        byte @Unmodifiable [] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(HttpURLConnection.HTTP_OK, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
