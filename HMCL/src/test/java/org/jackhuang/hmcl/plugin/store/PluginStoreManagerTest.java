/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2020  huangyuhui <huanghongxun2008@126.com> and contributors
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

import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.jackhuang.hmcl.Metadata;
import org.jackhuang.hmcl.plugin.runtime.PluginAbi;
import org.jackhuang.hmcl.plugin.runtime.PluginCompatibilityEvaluator;
import org.jackhuang.hmcl.plugin.runtime.PluginPlatformTarget;
import org.jackhuang.hmcl.plugin.runtime.RuntimeProvider;
import org.jackhuang.hmcl.plugin.runtime.RuntimeProviderRegistry;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies store preferences, bounded text transport, exact-version staging, and atomic package validation.
@NotNullByDefault
public final class PluginStoreManagerTest {
    /// README size boundary mirrored from the store transport contract.
    private static final int README_LIMIT_BYTES = 2 * 1024 * 1024;

    /// Bounded wait used by deterministic HTTP concurrency coordination.
    private static final long CONCURRENCY_TIMEOUT_SECONDS = 5;

    /// Binds loaded registry items to the source and manager that produced their manifest caches.
    @Test
    public void loadedItemsRemainBoundToTheirSourceAndManager() throws Exception {
        try (RegistryFixture fixture = RegistryFixture.start("Bound Store", "dev.hmclce.bound")) {
            PluginSource source = new PluginSource(
                    "source_bound", fixture.registryUrl(), "Bound", true, false
            );
            PluginStoreManager manager = new PluginStoreManager();

            manager.loadSource(source);
            PluginStoreItem item = manager.getStoreItems().get(0);

            assertEquals(source, item.getSource());
            assertEquals("Bound Store", item.getRegistry().getName());
            assertSame(manager, item.getSourceManager());

            PluginSource replacement = new PluginSource(
                    "source_replacement", fixture.unavailableRegistryUrl(), null, true, false
            );
            assertThrows(IOException.class, () -> manager.loadSource(replacement));
            assertEquals(source, manager.getSource());
            assertEquals("Bound Store", manager.getRegistry().getName());
        }
    }

    /// Keeps a new source context free of a manifest result that began under the old source.
    @Test
    public void sourceReplacementDoesNotRetainStaleManifestCache() throws Exception {
        CountDownLatch oldManifestStarted = new CountDownLatch(1);
        CountDownLatch releaseOldManifest = new CountDownLatch(1);
        AtomicInteger manifestRequests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        String pluginId = "dev.hmclce.context";
        String manifestUrl = baseUrl + "/manifest";
        server.createContext("/old-registry", exchange -> respond(exchange, registryWithEntry(
                "Old Store", pluginId, manifestUrl
        )));
        server.createContext("/replacement-registry", exchange -> respond(exchange, registryWithEntry(
                "Replacement Store", pluginId, manifestUrl
        )));
        server.createContext("/manifest", exchange -> {
            if (manifestRequests.incrementAndGet() == 1) {
                oldManifestStarted.countDown();
                awaitLatch(releaseOldManifest, "old manifest release");
            }
            respond(exchange, validManifest(pluginId));
        });
        ExecutorService serverExecutor = Executors.newCachedThreadPool();
        server.setExecutor(serverExecutor);
        server.start();

        try {
            PluginStoreManager manager = new PluginStoreManager();
            PluginSource oldSource = new PluginSource(
                    "source_old", baseUrl + "/old-registry", null, true, false
            );
            PluginSource replacementSource = new PluginSource(
                    "source_replacement", baseUrl + "/replacement-registry", null, true, false
            );
            manager.loadSource(oldSource);
            AtomicReference<@Nullable Throwable> oldRequestFailure = new AtomicReference<>();
            Thread oldRequest = new Thread(() -> {
                try {
                    manager.getPluginManifest(pluginId, manifestUrl);
                } catch (Throwable exception) {
                    oldRequestFailure.set(exception);
                }
            }, "plugin-store-old-manifest");

            oldRequest.start();
            assertTrue(oldManifestStarted.await(CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS));
            manager.loadSource(replacementSource);
            releaseOldManifest.countDown();
            oldRequest.join(TimeUnit.SECONDS.toMillis(CONCURRENCY_TIMEOUT_SECONDS));

            assertFalse(oldRequest.isAlive());
            assertEquals(null, oldRequestFailure.get());
            PluginStoreItem replacementItem = manager.getStoreItems().get(0);
            assertEquals(replacementSource, replacementItem.getSource());
            assertEquals("Replacement Store", replacementItem.getRegistry().getName());
            assertEquals(2, manifestRequests.get());
        } finally {
            releaseOldManifest.countDown();
            server.stop(0);
            serverExecutor.shutdownNow();
        }
    }

    /// Keeps same-URL README responses bound to the source context that started each request.
    @Test
    public void sourceReplacementDoesNotRetainStaleReadmeCache() throws Exception {
        CountDownLatch oldReadmeStarted = new CountDownLatch(1);
        CountDownLatch releaseOldReadme = new CountDownLatch(1);
        AtomicInteger readmeRequests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        String pluginId = "dev.hmclce.readme-context";
        String readmeUrl = baseUrl + "/readme";
        server.createContext("/old-registry", exchange -> respond(exchange, registryWithEntry(
                "Old Store", pluginId, baseUrl + "/old-manifest"
        )));
        server.createContext("/replacement-registry", exchange -> respond(exchange, registryWithEntry(
                "Replacement Store", pluginId, baseUrl + "/replacement-manifest"
        )));
        server.createContext("/old-manifest", exchange -> respond(exchange, manifestWithReadme(pluginId, readmeUrl)));
        server.createContext("/replacement-manifest", exchange -> respond(
                exchange,
                manifestWithReadme(pluginId, readmeUrl)
        ));
        server.createContext("/readme", exchange -> {
            if (readmeRequests.incrementAndGet() == 1) {
                oldReadmeStarted.countDown();
                awaitLatch(releaseOldReadme, "old README release");
                respond(exchange, "Old README".getBytes(StandardCharsets.UTF_8));
                return;
            }
            respond(exchange, "Replacement README".getBytes(StandardCharsets.UTF_8));
        });
        ExecutorService serverExecutor = Executors.newCachedThreadPool();
        server.setExecutor(serverExecutor);
        server.start();

        try {
            PluginStoreManager manager = new PluginStoreManager();
            PluginSource oldSource = new PluginSource(
                    "source_old", baseUrl + "/old-registry", null, true, false
            );
            PluginSource replacementSource = new PluginSource(
                    "source_replacement", baseUrl + "/replacement-registry", null, true, false
            );
            manager.loadSource(oldSource);
            PluginStoreItem oldItem = manager.getStoreItems().get(0);
            AtomicReference<@Nullable Throwable> oldRequestFailure = new AtomicReference<>();
            Thread oldRequest = new Thread(() -> {
                try {
                    manager.fetchReadme(oldItem);
                } catch (Throwable exception) {
                    oldRequestFailure.set(exception);
                }
            }, "plugin-store-old-readme");

            oldRequest.start();
            assertTrue(oldReadmeStarted.await(CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS));
            manager.loadSource(replacementSource);
            PluginStoreItem replacementItem = manager.getStoreItems().get(0);
            releaseOldReadme.countDown();
            oldRequest.join(TimeUnit.SECONDS.toMillis(CONCURRENCY_TIMEOUT_SECONDS));

            assertFalse(oldRequest.isAlive());
            assertEquals(null, oldRequestFailure.get());
            assertEquals("Replacement README", manager.fetchReadme(replacementItem));
            assertEquals(2, readmeRequests.get());
        } finally {
            releaseOldReadme.countDown();
            server.stop(0);
            serverExecutor.shutdownNow();
        }
    }

    /// Keeps an old item's README cache isolated when its first README fetch occurs after source replacement.
    @Test
    public void oldItemReadmeAfterSourceReplacementUsesOriginalContext() throws Exception {
        AtomicInteger readmeRequests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        String pluginId = "dev.hmclce.old-item-readme-context";
        String readmeUrl = baseUrl + "/readme";
        server.createContext("/source-a-registry", exchange -> respond(exchange, registryWithEntry(
                "Source A Store", pluginId, baseUrl + "/source-a-manifest"
        )));
        server.createContext("/source-b-registry", exchange -> respond(exchange, registryWithEntry(
                "Source B Store", pluginId, baseUrl + "/source-b-manifest"
        )));
        server.createContext("/source-a-manifest", exchange -> respond(
                exchange,
                manifestWithReadme(pluginId, readmeUrl)
        ));
        server.createContext("/source-b-manifest", exchange -> respond(
                exchange,
                manifestWithReadme(pluginId, readmeUrl)
        ));
        server.createContext("/readme", exchange -> respond(
                exchange,
                (readmeRequests.incrementAndGet() == 1 ? "Source A README" : "Source B README")
                        .getBytes(StandardCharsets.UTF_8)
        ));
        server.start();

        try {
            PluginStoreManager manager = new PluginStoreManager();
            PluginSource sourceA = new PluginSource(
                    "source_a", baseUrl + "/source-a-registry", null, true, false
            );
            PluginSource sourceB = new PluginSource(
                    "source_b", baseUrl + "/source-b-registry", null, true, false
            );
            manager.loadSource(sourceA);
            PluginStoreItem oldItem = manager.getStoreItems().get(0);

            manager.loadSource(sourceB);
            PluginStoreItem sourceBItem = manager.getStoreItems().get(0);

            assertEquals("Source A README", manager.fetchReadme(oldItem));
            assertEquals(1, readmeRequests.get());
            assertEquals("Source B README", manager.fetchReadme(sourceBItem));
            assertEquals(2, readmeRequests.get());
            assertEquals("Source A README", manager.fetchReadme(oldItem));
            assertEquals("Source B README", manager.fetchReadme(sourceBItem));
            assertEquals(2, readmeRequests.get());
        } finally {
            server.stop(0);
        }
    }

    /// Keeps observed item source and registry values from the same replacement generation.
    @Test
    public void concurrentReadersNeverObserveMixedSourceAndRegistry() throws Exception {
        CountDownLatch replacementRegistryStarted = new CountDownLatch(1);
        CountDownLatch releaseReplacementRegistry = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        String oldPluginId = "dev.hmclce.old";
        String replacementPluginId = "dev.hmclce.replacement";
        server.createContext("/old-registry", exchange -> respond(exchange, registryWithEntry(
                "Old Store", oldPluginId, baseUrl + "/old-manifest"
        )));
        server.createContext("/replacement-registry", exchange -> {
            replacementRegistryStarted.countDown();
            awaitLatch(releaseReplacementRegistry, "replacement registry release");
            respond(exchange, registryWithEntry(
                    "Replacement Store", replacementPluginId, baseUrl + "/replacement-manifest"
            ));
        });
        server.createContext("/old-manifest", exchange -> respond(exchange, validManifest(oldPluginId)));
        server.createContext("/replacement-manifest", exchange -> respond(exchange, validManifest(replacementPluginId)));
        ExecutorService serverExecutor = Executors.newCachedThreadPool();
        server.setExecutor(serverExecutor);
        server.start();

        try {
            PluginStoreManager manager = new PluginStoreManager();
            PluginSource oldSource = new PluginSource(
                    "source_old", baseUrl + "/old-registry", null, true, false
            );
            PluginSource replacementSource = new PluginSource(
                    "source_replacement", baseUrl + "/replacement-registry", null, true, false
            );
            manager.loadSource(oldSource);
            AtomicReference<@Nullable Throwable> replacementFailure = new AtomicReference<>();
            Thread replacement = new Thread(() -> {
                try {
                    manager.loadSource(replacementSource);
                } catch (Throwable exception) {
                    replacementFailure.set(exception);
                }
            }, "plugin-store-replacement");

            replacement.start();
            assertTrue(replacementRegistryStarted.await(CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS));
            for (int index = 0; index < 100; index++) {
                PluginStoreItem item = manager.getStoreItems().get(0);
                assertEquals("source_old", item.getSource().getId());
                assertEquals("Old Store", item.getRegistry().getName());
            }
            releaseReplacementRegistry.countDown();
            replacement.join(TimeUnit.SECONDS.toMillis(CONCURRENCY_TIMEOUT_SECONDS));

            assertFalse(replacement.isAlive());
            assertEquals(null, replacementFailure.get());
            PluginStoreItem replacementItem = manager.getStoreItems().get(0);
            assertEquals("source_replacement", replacementItem.getSource().getId());
            assertEquals("Replacement Store", replacementItem.getRegistry().getName());
        } finally {
            releaseReplacementRegistry.countDown();
            server.stop(0);
            serverExecutor.shutdownNow();
        }
    }

    /// Rejects a syntactically invalid registry document before publishing any source state.
    @Test
    public void rejectsMalformedRegistry() throws Exception {
        try (RegistryFixture fixture = RegistryFixture.startRaw("{not valid JSON")) {
            PluginStoreManager manager = new PluginStoreManager();
            PluginSource source = new PluginSource("source_malformed", fixture.registryUrl(), null, true, false);

            assertThrows(IOException.class, () -> manager.loadSource(source));
            assertThrows(IllegalStateException.class, manager::getSource);
        }
    }

    /// Rejects registries that publish the same plugin ID more than once.
    @Test
    public void rejectsDuplicatePluginIds() throws Exception {
        try (RegistryFixture fixture = RegistryFixture.startRaw("""
                {
                  "schemaVersion": 1,
                  "name": "Duplicate Store",
                  "plugins": [
                    {
                      "id": "dev.hmclce.duplicate",
                      "name": "Duplicate",
                      "manifestUrl": "http://127.0.0.1:1/manifest.json"
                    },
                    {
                      "id": "dev.hmclce.duplicate",
                      "name": "Duplicate",
                      "manifestUrl": "http://127.0.0.1:1/manifest.json"
                    }
                  ]
                }
                """)) {
            PluginStoreManager manager = new PluginStoreManager();
            PluginSource source = new PluginSource("source_duplicate", fixture.registryUrl(), null, true, false);

            assertThrows(IOException.class, () -> manager.loadSource(source));
            assertThrows(IllegalStateException.class, manager::getSource);
        }
    }

    /// Rejects unsupported manifest URL schemes during registry validation.
    @Test
    public void rejectsUnsupportedManifestScheme() throws Exception {
        try (RegistryFixture fixture = RegistryFixture.startRaw("""
                {
                  "schemaVersion": 1,
                  "name": "Scheme Store",
                  "plugins": [
                    {
                      "id": "dev.hmclce.scheme",
                      "name": "Unsupported Scheme",
                      "manifestUrl": "file:///plugin.json"
                    }
                  ]
                }
                """)) {
            PluginStoreManager manager = new PluginStoreManager();
            PluginSource source = new PluginSource("source_scheme", fixture.registryUrl(), null, true, false);

            assertThrows(IOException.class, () -> manager.loadSource(source));
            assertThrows(IllegalStateException.class, manager::getSource);
        }
    }

    /// Rejects registry entries that omit their required manifest URL.
    @Test
    public void rejectsInvalidManifestUrl() throws Exception {
        try (RegistryFixture fixture = RegistryFixture.startRaw("""
                {
                  "schemaVersion": 1,
                  "name": "Missing Manifest Store",
                  "plugins": [
                    {
                      "id": "dev.hmclce.no-manifest",
                      "name": "No Manifest"
                    }
                  ]
                }
                """)) {
            PluginStoreManager manager = new PluginStoreManager();
            PluginSource source = new PluginSource("source_no_manifest", fixture.registryUrl(), null, true, false);

            assertThrows(IOException.class, () -> manager.loadSource(source));
            assertThrows(IllegalStateException.class, manager::getSource);
        }
    }

    /// GitHub Releases and common CDNs redirect stable download URLs to a generated asset URL.
    @Test
    public void followsRedirectWhenDownloadingPackage(@TempDir Path temporaryDirectory) throws Exception {
        byte @Unmodifiable [] packageBytes = createPluginPackage(
                "dev.hmclce.test.redirect",
                "1.0.0",
                4,
                "[]",
                "[]"
        );
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/download", exchange -> {
            requests.incrementAndGet();
            redirect(exchange, "/asset");
        });
        server.createContext("/asset", exchange -> {
            requests.incrementAndGet();
            respond(exchange, packageBytes);
        });
        server.start();

        try {
            String packageUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/download";
            PluginStoreManifest manifest = parseManifest("dev.hmclce.test.redirect", """
                    {
                      "schemaVersion": 1,
                      "id": "dev.hmclce.test.redirect",
                      "versions": [
                        {
                          "version": "1.0.0",
                          "packageUrl": "%s",
                          "sha256": "%s",
                          "pluginApiVersion": 4,
                          "permissions": [],
                          "requiredPermissions": [],
                          "launcherVersion": "*",
                          "dependencies": [],
                          "size": %d
                        }
                      ]
                    }
                    """.formatted(packageUrl, sha256(packageBytes), packageBytes.length));
            PluginStoreManifest.PluginVersionEntry version = Objects.requireNonNull(manifest.getLatestVersion());

            Path installed = new PluginStoreManager().downloadPlugin(
                    "dev.hmclce.test.redirect",
                    version,
                    temporaryDirectory.resolve("plugins")
            );

            assertArrayEquals(packageBytes, Files.readAllBytes(installed));
            assertEquals(2, requests.get());
        } finally {
            server.stop(0);
        }
    }

    /// Downloads only the artifact matching the current host and verifies its artifact-owned hash and size.
    @Test
    public void downloadSelectedPlatformArtifact(@TempDir Path temporaryDirectory) throws Exception {
        PluginPlatformTarget currentPlatform = PluginPlatformTarget.current();
        String currentArchitecture = Objects.requireNonNull(currentPlatform.getArchitecture());
        String otherPlatform = currentPlatform.getOperatingSystem().equals("windows")
                && currentArchitecture.equals("x64") ? "windows-arm64" : "windows-x64";
        byte @Unmodifiable [] selectedPackage = createPluginPackageFive(
                "dev.hmclce.test.platform-artifact",
                "1.0.0",
                "java",
                2,
                "[\"" + currentPlatform.getId() + "\"]"
        );
        AtomicInteger selectedRequests = new AtomicInteger();
        AtomicInteger otherRequests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/selected", exchange -> {
            selectedRequests.incrementAndGet();
            respond(exchange, selectedPackage);
        });
        server.createContext("/other", exchange -> {
            otherRequests.incrementAndGet();
            respond(exchange, new byte[]{1});
        });
        server.start();

        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            PluginStoreManifest manifest = parseManifest("dev.hmclce.test.platform-artifact", """
                    {
                      "schemaVersion": 2,
                      "id": "dev.hmclce.test.platform-artifact",
                      "versions": [{
                        "version": "1.0.0",
                        "pluginApiVersion": 5,
                        "permissions": [],
                        "requiredPermissions": [],
                        "launcherVersion": "*",
                        "runtime": "java",
                        "abi": 2,
                        "platforms": ["%s"],
                        "pluginKind": "normal",
                        "artifacts": [
                          {"platform": "%s", "packageUrl": "%s/other",
                           "sha256": "%s", "size": 1},
                          {"platform": "%s", "packageUrl": "%s/selected",
                           "sha256": "%s", "size": %d}
                        ],
                        "dependencies": []
                      }]
                    }
                    """.formatted(
                    currentPlatform.getId(),
                    otherPlatform,
                    baseUrl,
                    "0".repeat(64),
                    currentPlatform.getId(),
                    baseUrl,
                    sha256(selectedPackage),
                    selectedPackage.length
            ));

            Path installed = new PluginStoreManager().downloadPlugin(
                    manifest.getId(),
                    manifest.getVersions().get(0),
                    temporaryDirectory.resolve("plugins")
            );

            assertArrayEquals(selectedPackage, Files.readAllBytes(installed));
            assertEquals(1, selectedRequests.get());
            assertEquals(0, otherRequests.get());
        } finally {
            server.stop(0);
        }
    }

    /// Keeps historical API-v1 through API-v3 metadata visible while excluding every old package from installation.
    @Test
    public void rejectsEveryPluginApiBeforeVersionFour(@TempDir Path temporaryDirectory) throws Exception {
        String pluginId = "dev.hmclce.test.legacy-api";
        PluginStoreManifest manifest = parseManifest(pluginId, """
                {
                  "schemaVersion": 2,
                  "id": "%s",
                  "versions": [
                    {
                      "version": "1.0.0",
                      "packageUrl": "https://example.com/v1.npl",
                      "sha256": "0000000000000000000000000000000000000000000000000000000000000000",
                      "pluginApiVersion": 1,
                      "size": 1
                    },
                    {
                      "version": "2.0.0",
                      "packageUrl": "https://example.com/v2.npl",
                      "sha256": "1111111111111111111111111111111111111111111111111111111111111111",
                      "pluginApiVersion": 2,
                      "size": 1
                    },
                    {
                      "version": "3.0.0",
                      "packageUrl": "https://example.com/v3.npl",
                      "sha256": "2222222222222222222222222222222222222222222222222222222222222222",
                      "pluginApiVersion": 3,
                      "permissions": [],
                      "dependencies": [],
                      "size": 1
                    }
                  ]
                }
                """.formatted(pluginId));
        PluginStoreManager manager = new PluginStoreManager();

        assertEquals(3, manifest.getVersionsNewestFirst().size());
        assertTrue(manager.getCompatibleVersions(manifest).isEmpty());
        for (PluginStoreManifest.PluginVersionEntry version : manifest.getVersions()) {
            IOException exception = assertThrows(IOException.class, () -> manager.validateCompatibility(version));
            assertTrue(exception.getMessage().contains("schema " + version.getPluginApiVersion()));
            assertTrue(exception.getMessage().contains("4..5"));
            assertFalse(manager.isCompatible(version));
        }
    }

    /// Applies shared launcher, platform, runtime, and ABI compatibility rules to store entries.
    @Test
    public void evaluateStoreCompatibilityWithSharedRuntimeContract() throws IOException {
        RuntimeProviderRegistry runtimeProviders = new RuntimeProviderRegistry();
        runtimeProviders.register(runtimeProvider("limited", Set.of(1)));
        PluginStoreManager manager = new PluginStoreManager(new PluginCompatibilityEvaluator(
                runtimeProviders,
                PluginPlatformTarget.parse("windows-x64")
        ));

        PluginStoreManifest.PluginVersionEntry schemaFour = compatibilityVersion(4, """
                "permissions": [],
                "requiredPermissions": [],
                "launcherVersion": "*",
                "dependencies": []
                """);
        PluginStoreManifest.PluginVersionEntry schemaFive = compatibilityVersion(5, """
                "permissions": [],
                "requiredPermissions": [],
                "launcherVersion": "*",
                "runtime": "java",
                "abi": 2,
                "platforms": ["windows"],
                "dependencies": []
                """);
        assertDoesNotThrow(() -> manager.validateCompatibility(schemaFour));
        assertDoesNotThrow(() -> manager.validateCompatibility(schemaFive));
        assertTrue(manager.isCompatible(schemaFour));
        assertTrue(manager.isCompatible(schemaFive));

        assertCompatibilityRejected(manager, compatibilityVersion(4, """
                "permissions": [],
                "requiredPermissions": [],
                "launcherVersion": ">=9999",
                "dependencies": []
                """), Metadata.VERSION, ">=9999");
        assertCompatibilityRejected(manager, compatibilityVersion(5, """
                "permissions": [],
                "requiredPermissions": [],
                "launcherVersion": "*",
                "runtime": "java",
                "abi": 2,
                "platforms": ["linux"],
                "dependencies": []
                """), "linux", "windows-x64");
        assertCompatibilityRejected(manager, compatibilityVersion(5, """
                "permissions": [],
                "requiredPermissions": [],
                "launcherVersion": "*",
                "runtime": "dotnet",
                "abi": 2,
                "dependencies": []
                """), "dotnet");
        assertCompatibilityRejected(manager, compatibilityVersion(5, """
                "permissions": [],
                "requiredPermissions": [],
                "launcherVersion": "*",
                "runtime": "limited",
                "abi": 2,
                "dependencies": []
                """), "limited", "ABI 2", "[1]");
    }

    /// Excludes artifact matrices without an exact current target even when package platforms allow the host.
    @Test
    public void rejectCompatibleVersionWithoutCurrentPlatformArtifact() throws IOException {
        PluginPlatformTarget currentPlatform = PluginPlatformTarget.current();
        String currentArchitecture = Objects.requireNonNull(currentPlatform.getArchitecture());
        String otherPlatform = currentPlatform.getOperatingSystem().equals("windows")
                && currentArchitecture.equals("x64") ? "windows-arm64" : "windows-x64";
        PluginStoreManager manager = new PluginStoreManager();

        for (String platforms : new String[]{"[]", "[\"" + currentPlatform.getId() + "\"]"}) {
            PluginStoreManifest manifest = parseManifest("dev.hmclce.test.missing-host-artifact", """
                    {
                      "schemaVersion": 2,
                      "id": "dev.hmclce.test.missing-host-artifact",
                      "versions": [{
                        "version": "1.0.0",
                        "pluginApiVersion": 5,
                        "permissions": [],
                        "requiredPermissions": [],
                        "launcherVersion": "*",
                        "runtime": "java",
                        "abi": 2,
                        "platforms": %s,
                        "pluginKind": "normal",
                        "artifacts": [{
                          "platform": "%s",
                          "packageUrl": "https://example.test/other.npl",
                          "sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                          "size": 1
                        }],
                        "dependencies": []
                      }]
                    }
                    """.formatted(platforms, otherPlatform));
            PluginStoreManifest.PluginVersionEntry version = manifest.getVersions().get(0);

            IOException exception = assertThrows(IOException.class, () -> manager.validateCompatibility(version));
            assertTrue(exception.getMessage().contains(currentPlatform.getId()), exception.getMessage());
            assertTrue(exception.getMessage().contains(otherPlatform), exception.getMessage());
            assertFalse(manager.isCompatible(version));
            assertTrue(manager.getCompatibleVersions(manifest).isEmpty());
        }

        assertTrue(manager.isCompatible(compatibilityVersion(5, """
                "permissions": [],
                "requiredPermissions": [],
                "launcherVersion": "*",
                "runtime": "java",
                "abi": 2,
                "platforms": [],
                "dependencies": []
                """)));
    }

    /// Uses the compatibility evaluator's injected host for both matrix filtering and artifact download selection.
    @Test
    public void useInjectedHostForPlatformArtifactSelection(@TempDir Path temporaryDirectory) throws Exception {
        PluginPlatformTarget currentPlatform = PluginPlatformTarget.current();
        PluginPlatformTarget injectedPlatform = currentPlatform.equals(PluginPlatformTarget.parse("linux-x64"))
                ? PluginPlatformTarget.parse("macos-arm64")
                : PluginPlatformTarget.parse("linux-x64");
        String pluginId = "dev.hmclce.test.injected-platform-artifact";
        byte @Unmodifiable [] packageBytes = createPluginPackageFive(
                pluginId,
                "1.0.0",
                "java",
                2,
                "[\"" + injectedPlatform.getId() + "\"]"
        );
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/package", exchange -> respond(exchange, packageBytes));
        server.start();

        try {
            String packageUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/package";
            PluginStoreManifest manifest = parseManifest(pluginId, """
                    {
                      "schemaVersion": 2,
                      "id": "%s",
                      "versions": [{
                        "version": "1.0.0",
                        "pluginApiVersion": 5,
                        "permissions": [],
                        "requiredPermissions": [],
                        "launcherVersion": "*",
                        "runtime": "java",
                        "abi": 2,
                        "platforms": ["%s"],
                        "pluginKind": "normal",
                        "artifacts": [{
                          "platform": "%s",
                          "packageUrl": "%s",
                          "sha256": "%s",
                          "size": %d
                        }],
                        "dependencies": []
                      }]
                    }
                    """.formatted(
                    pluginId,
                    injectedPlatform.getId(),
                    injectedPlatform.getId(),
                    packageUrl,
                    sha256(packageBytes),
                    packageBytes.length
            ));
            PluginStoreManager manager = new PluginStoreManager(new PluginCompatibilityEvaluator(
                    new RuntimeProviderRegistry(),
                    injectedPlatform
            ));
            PluginStoreManifest.PluginVersionEntry version = manifest.getVersions().get(0);

            assertDoesNotThrow(() -> manager.validateCompatibility(version));
            Path installed = manager.downloadPlugin(pluginId, version, temporaryDirectory.resolve("plugins"));

            assertArrayEquals(packageBytes, Files.readAllBytes(installed));
        } finally {
            server.stop(0);
        }
    }

    /// Observes providers registered after a production store manager has been constructed.
    @Test
    public void useProcessWideRuntimeProvidersForStoreCompatibility() throws IOException {
        String runtimeType = "store-shared-test";
        RuntimeProviderRegistry runtimeProviders = RuntimeProviderRegistry.processWide();
        runtimeProviders.unregister(runtimeType);
        PluginStoreManager manager = new PluginStoreManager();
        PluginStoreManifest.PluginVersionEntry version = compatibilityVersion(5, """
                "permissions": [],
                "requiredPermissions": [],
                "launcherVersion": "*",
                "runtime": "%s",
                "abi": 1,
                "dependencies": []
                """.formatted(runtimeType));

        assertFalse(manager.isCompatible(version));
        try {
            runtimeProviders.register(runtimeProvider(runtimeType, Set.of(PluginAbi.ABI_1)));
            assertTrue(manager.isCompatible(version));
        } finally {
            runtimeProviders.unregister(runtimeType);
        }
    }

    /// Persists favorite changes across repository instances and rejects IDs that cannot be stored safely.
    @Test
    public void persistFavoritesAndRejectInvalidIds(@TempDir Path temporaryDirectory) {
        String pluginId = "dev.hmclce.test.favorite";
        PluginStorePreferences preferences = new PluginStorePreferences(temporaryDirectory);

        preferences.setFavorite(pluginId, true);
        assertTrue(preferences.isFavorite(pluginId));
        assertEquals(Set.of(pluginId), preferences.getFavoritePluginIds());

        PluginStorePreferences reloaded = new PluginStorePreferences(temporaryDirectory);
        assertTrue(reloaded.isFavorite(pluginId));
        assertThrows(IllegalArgumentException.class, () -> reloaded.setFavorite("invalid favorite id", true));
        assertEquals(Set.of(pluginId), reloaded.getFavoritePluginIds());

        reloaded.setFavorite(pluginId, false);
        assertFalse(new PluginStorePreferences(temporaryDirectory).isFavorite(pluginId));
    }

    /// Reuses a cached README until clearing the manager caches forces a fresh request.
    @Test
    public void cacheReadmeUntilCacheIsCleared(@TempDir Path temporaryDirectory) throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/readme", exchange -> {
            int requestNumber = requests.incrementAndGet();
            respond(exchange, ("README request " + requestNumber).getBytes(StandardCharsets.UTF_8));
        });
        server.start();

        try {
            String readmeUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/readme";
            PluginStoreManifest manifest = readmeManifest(readmeUrl);
            PluginStoreManager manager = new PluginStoreManager();

            assertEquals("README request 1", manager.fetchReadme(manifest));
            assertEquals("README request 1", manager.fetchReadme(manifest));
            assertEquals(1, requests.get());

            manager.clearCache();
            assertEquals("README request 2", manager.fetchReadme(manifest));
            assertEquals(2, requests.get());
        } finally {
            server.stop(0);
        }
    }

    /// Resolves every relative README redirect hop before returning the final Markdown body.
    @Test
    public void followRedirectsWhenFetchingReadme(@TempDir Path temporaryDirectory) throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/readme", exchange -> {
            requests.incrementAndGet();
            redirect(exchange, "/readme-middle");
        });
        server.createContext("/readme-middle", exchange -> {
            requests.incrementAndGet();
            redirect(exchange, "/readme-content");
        });
        server.createContext("/readme-content", exchange -> {
            requests.incrementAndGet();
            respond(exchange, "# Redirected README".getBytes(StandardCharsets.UTF_8));
        });
        server.start();

        try {
            String readmeUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/readme";
            PluginStoreManager manager = new PluginStoreManager();

            assertEquals("# Redirected README", manager.fetchReadme(readmeManifest(readmeUrl)));
            assertEquals(3, requests.get());
        } finally {
            server.stop(0);
        }
    }

    /// Rejects remote-to-loopback redirects while preserving loopback HTTP development chains.
    @Test
    public void enforceRedirectTargetPolicy() {
        assertThrows(
                IOException.class,
                () -> PluginStoreManager.validateRedirectTarget(
                        URI.create("https://example.com/readme"),
                        URI.create("http://127.0.0.1/x"),
                        "plugin README"
                )
        );
        assertDoesNotThrow(() -> PluginStoreManager.validateRedirectTarget(
                URI.create("http://127.0.0.1/readme"),
                URI.create("http://127.0.0.1/x"),
                "plugin README"
        ));
    }

    /// Accepts the exact README byte limit and rejects a chunked response that exceeds it.
    @Test
    public void enforceReadmeSizeLimit(@TempDir Path temporaryDirectory) throws Exception {
        byte @Unmodifiable [] maximumReadme = new byte[README_LIMIT_BYTES];
        byte @Unmodifiable [] oversizedReadme = new byte[README_LIMIT_BYTES + 1];
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/maximum", exchange -> respond(exchange, maximumReadme));
        server.createContext("/oversized", exchange -> respondChunked(exchange, oversizedReadme));
        server.start();

        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            PluginStoreManager manager = new PluginStoreManager();

            assertEquals(
                    README_LIMIT_BYTES,
                    manager.fetchReadme(readmeManifest(baseUrl + "/maximum")).length()
            );
            assertThrows(
                    IOException.class,
                    () -> manager.fetchReadme(readmeManifest(baseUrl + "/oversized"))
            );
        } finally {
            server.stop(0);
        }
    }

    /// Downloads the explicitly selected historical version into its deterministic staging path.
    @Test
    public void stageSelectedHistoricalVersion(@TempDir Path temporaryDirectory) throws Exception {
        String pluginId = "dev.hmclce.test.staging";
        byte @Unmodifiable [] oldPackage = createPluginPackage(pluginId, "1.0.0", 4, "[]", "[]");
        byte @Unmodifiable [] latestPackage = createPluginPackage(pluginId, "2.0.0", 4, "[]", "[]");
        AtomicInteger oldRequests = new AtomicInteger();
        AtomicInteger latestRequests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/old", exchange -> {
            oldRequests.incrementAndGet();
            respond(exchange, oldPackage);
        });
        server.createContext("/latest", exchange -> {
            latestRequests.incrementAndGet();
            respond(exchange, latestPackage);
        });
        server.start();

        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            PluginStoreManifest manifest = parseManifest(pluginId, repositoryManifest(
                    pluginId,
                    repositoryVersion("1.0.0", baseUrl + "/old", oldPackage, "[]", "[]") + ","
                            + repositoryVersion("2.0.0", baseUrl + "/latest", latestPackage, "[]", "[]")
            ));
            PluginStoreManifest.PluginVersionEntry selected = Objects.requireNonNull(manifest.getVersion("1.0.0"));
            Path stagingDirectory = temporaryDirectory.resolve("staging");

            Path staged = new PluginStoreManager().downloadPluginToStaging(
                    pluginId,
                    selected,
                    stagingDirectory
            );

            assertEquals(
                    pluginId + "-" + sha256(oldPackage).substring(0, 12) + ".npl",
                    staged.getFileName().toString()
            );
            assertArrayEquals(oldPackage, Files.readAllBytes(staged));
            assertEquals(1, oldRequests.get());
            assertEquals(0, latestRequests.get());
        } finally {
            server.stop(0);
        }
    }

    /// Leaves an existing installed package untouched for identity, version, schema, permission, or dependency drift.
    @Test
    public void preserveExistingTargetWhenPackageMetadataDiffers(@TempDir Path temporaryDirectory) throws Exception {
        String pluginId = "dev.hmclce.test.metadata";
        String selectedVersion = "2.0.0";
        AtomicReference<byte @Unmodifiable []> responseBody = new AtomicReference<>(new byte[0]);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/package", exchange -> respond(exchange, responseBody.get()));
        server.start();

        try {
            String packageUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/package";
            Path installedPackage = temporaryDirectory.resolve("plugins").resolve(pluginId + ".npl");
            Files.createDirectories(installedPackage.getParent());
            byte @Unmodifiable [] existingPackage = "existing verified package".getBytes(StandardCharsets.UTF_8);
            Files.write(installedPackage, existingPackage);
            PluginStoreManager manager = new PluginStoreManager(new PluginCompatibilityEvaluator(
                    new RuntimeProviderRegistry(),
                    PluginPlatformTarget.parse("windows-x64")
            ));

            assertMetadataMismatchPreservesTarget(
                    manager,
                    responseBody,
                    packageUrl,
                    pluginId,
                    selectedVersion,
                    installedPackage,
                    existingPackage,
                    createPluginPackage("dev.hmclce.test.other", selectedVersion, 4, "[]", "[]"),
                    "does not match registry entry"
            );
            assertMetadataMismatchPreservesTarget(
                    manager,
                    responseBody,
                    packageUrl,
                    pluginId,
                    selectedVersion,
                    installedPackage,
                    existingPackage,
                    createPluginPackage(pluginId, "9.0.0", 4, "[]", "[]"),
                    "does not match selected version"
            );
            assertMetadataMismatchPreservesTarget(
                    manager,
                    responseBody,
                    packageUrl,
                    pluginId,
                    selectedVersion,
                    installedPackage,
                    existingPackage,
                    createPluginPackage(pluginId, selectedVersion, 2, "[]", "[]"),
                    "does not match pluginApiVersion"
            );
            assertMetadataMismatchPreservesTarget(
                    manager,
                    responseBody,
                    packageUrl,
                    pluginId,
                    selectedVersion,
                    installedPackage,
                    existingPackage,
                    createPluginPackage(pluginId, selectedVersion, 4, "[\"network\"]", "[]"),
                    "permissions do not match"
            );
            assertMetadataMismatchPreservesTarget(
                    manager,
                    responseBody,
                    packageUrl,
                    pluginId,
                    selectedVersion,
                    installedPackage,
                    existingPackage,
                    createPluginPackage(
                            pluginId,
                            selectedVersion,
                            4,
                            "[]",
                            "[{\"id\":\"dev.hmclce.test.metadata-base\",\"version\":\">=1.0.0\"}]"
                    ),
                    "dependencies do not match"
            );
            byte @Unmodifiable [] requiredMismatch = createPluginPackage(
                    pluginId,
                    selectedVersion,
                    4,
                    "[\"filesystem\",\"network\"]",
                    "[]",
                    "[\"network\"]",
                    "*"
            );
            assertMetadataMismatchPreservesTarget(
                    manager,
                    responseBody,
                    packageUrl,
                    pluginId,
                    selectedVersion,
                    installedPackage,
                    existingPackage,
                    requiredMismatch,
                    repositoryVersionFour(
                            selectedVersion,
                            packageUrl,
                            requiredMismatch,
                            "[\"filesystem\",\"network\"]",
                            "[\"filesystem\"]",
                            "*",
                            "[]"
                    ),
                    "requiredPermissions do not match"
            );
            byte @Unmodifiable [] launcherMismatch = createPluginPackage(
                    pluginId,
                    selectedVersion,
                    4,
                    "[]",
                    "[]",
                    "[]",
                    ">=9999"
            );
            assertMetadataMismatchPreservesTarget(
                    manager,
                    responseBody,
                    packageUrl,
                    pluginId,
                    selectedVersion,
                    installedPackage,
                    existingPackage,
                    launcherMismatch,
                    repositoryVersionFour(
                            selectedVersion,
                            packageUrl,
                            launcherMismatch,
                            "[]",
                            "[]",
                            "*",
                            "[]"
                    ),
                    "launcherVersion does not match"
            );
            byte @Unmodifiable [] runtimeMismatch = createPluginPackageFive(
                    pluginId, selectedVersion, "dotnet", 2, "[]"
            );
            assertMetadataMismatchPreservesTarget(
                    manager,
                    responseBody,
                    packageUrl,
                    pluginId,
                    selectedVersion,
                    installedPackage,
                    existingPackage,
                    runtimeMismatch,
                    repositoryVersionFive(
                            selectedVersion, packageUrl, runtimeMismatch, "java", 2, "[]"
                    ),
                    "runtime does not match"
            );
            byte @Unmodifiable [] abiMismatch = createPluginPackageFive(
                    pluginId, selectedVersion, "java", 2, "[]"
            );
            assertMetadataMismatchPreservesTarget(
                    manager,
                    responseBody,
                    packageUrl,
                    pluginId,
                    selectedVersion,
                    installedPackage,
                    existingPackage,
                    abiMismatch,
                    repositoryVersionFive(
                            selectedVersion, packageUrl, abiMismatch, "java", 1, "[]"
                    ),
                    "ABI does not match"
            );
            byte @Unmodifiable [] platformMismatch = createPluginPackageFive(
                    pluginId, selectedVersion, "java", 2, "[\"linux\"]"
            );
            assertMetadataMismatchPreservesTarget(
                    manager,
                    responseBody,
                    packageUrl,
                    pluginId,
                    selectedVersion,
                    installedPackage,
                    existingPackage,
                    platformMismatch,
                    repositoryVersionFive(
                            selectedVersion, packageUrl, platformMismatch, "java", 2, "[\"windows\"]"
                    ),
                    "platforms do not match"
            );
        } finally {
            server.stop(0);
        }
    }

    /// Accepts equivalent canonical platform sets regardless of their serialized input order.
    @Test
    public void reconcileNormalizedPlatformOrder(@TempDir Path temporaryDirectory) throws Exception {
        String pluginId = "dev.hmclce.test.platform-order";
        String selectedVersion = "1.0.0";
        byte @Unmodifiable [] packageBytes = createPluginPackageFive(
                pluginId,
                selectedVersion,
                "java",
                2,
                "[\"linux\", \"windows-x64\"]"
        );
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/package", exchange -> respond(exchange, packageBytes));
        server.start();

        try {
            String packageUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/package";
            PluginStoreManifest manifest = parseManifest(pluginId, repositoryManifest(
                    pluginId,
                    repositoryVersionFive(
                            selectedVersion,
                            packageUrl,
                            packageBytes,
                            "java",
                            2,
                            "[\"windows-x64\", \"linux\"]"
                    )
            ));
            PluginStoreManager manager = new PluginStoreManager(new PluginCompatibilityEvaluator(
                    new RuntimeProviderRegistry(),
                    PluginPlatformTarget.parse("windows-x64")
            ));
            PluginStoreManifest.PluginVersionEntry version = Objects.requireNonNull(manifest.getLatestVersion());

            Path installed = manager.downloadPlugin(
                    pluginId,
                    version,
                    temporaryDirectory.resolve("plugins")
            );

            assertArrayEquals(packageBytes, Files.readAllBytes(installed));
        } finally {
            server.stop(0);
        }
    }

    /// Downloads one invalid package and verifies that validation fails before replacing the installed target.
    ///
    /// @param manager store manager under test
    /// @param responseBody body served by the package endpoint
    /// @param packageUrl local package endpoint
    /// @param pluginId expected plugin ID
    /// @param selectedVersion selected repository version
    /// @param installedPackage existing installed package path
    /// @param existingPackage original package bytes
    /// @param candidatePackage downloaded package with mismatched metadata
    /// @param expectedMessage diagnostic fragment identifying the metadata mismatch
    /// @throws Exception if fixture creation or target inspection fails ucepectedly
    private static void assertMetadataMismatchPreservesTarget(
            PluginStoreManager manager,
            AtomicReference<byte @Unmodifiable []> responseBody,
            String packageUrl,
            String pluginId,
            String selectedVersion,
            Path installedPackage,
            byte @Unmodifiable [] existingPackage,
            byte @Unmodifiable [] candidatePackage,
            String expectedMessage
    ) throws Exception {
        assertMetadataMismatchPreservesTarget(
                manager,
                responseBody,
                packageUrl,
                pluginId,
                selectedVersion,
                installedPackage,
                existingPackage,
                candidatePackage,
                repositoryVersion(selectedVersion, packageUrl, candidatePackage, "[]", "[]"),
                expectedMessage
        );
    }

    /// Downloads one invalid package against explicit remote metadata and preserves the installed target.
    ///
    /// @param manager store manager under test
    /// @param responseBody body served by the package endpoint
    /// @param packageUrl local package endpoint
    /// @param pluginId expected plugin ID
    /// @param selectedVersion selected repository version
    /// @param installedPackage existing installed package path
    /// @param existingPackage original package bytes
    /// @param candidatePackage downloaded package with mismatched metadata
    /// @param repositoryVersionJson exact remote version metadata
    /// @param expectedMessage diagnostic fragment identifying the metadata mismatch
    /// @throws Exception if fixture creation or target inspection fails ucepectedly
    private static void assertMetadataMismatchPreservesTarget(
            PluginStoreManager manager,
            AtomicReference<byte @Unmodifiable []> responseBody,
            String packageUrl,
            String pluginId,
            String selectedVersion,
            Path installedPackage,
            byte @Unmodifiable [] existingPackage,
            byte @Unmodifiable [] candidatePackage,
            String repositoryVersionJson,
            String expectedMessage
    ) throws Exception {
        responseBody.set(candidatePackage);
        PluginStoreManifest manifest = parseManifest(pluginId, repositoryManifest(
                pluginId,
                repositoryVersionJson
        ));
        PluginStoreManifest.PluginVersionEntry version = Objects.requireNonNull(
                manifest.getVersion(selectedVersion)
        );

        IOException exception = assertThrows(
                IOException.class,
                () -> manager.downloadPlugin(pluginId, version, installedPackage.getParent())
        );
        assertTrue(exception.getMessage().contains(expectedMessage), exception.getMessage());
        assertArrayEquals(existingPackage, Files.readAllBytes(installedPackage));
    }

    /// Awaits a concurrency gate and turns timeouts into deterministic test failures.
    ///
    /// @param latch gate to await
    /// @param description gate description for diagnostics
    /// @throws IOException if waiting times out or is interrupted
    private static void awaitLatch(CountDownLatch latch, String description) throws IOException {
        try {
            if (!latch.await(CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IOException("Timed out waiting for " + description);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for " + description, exception);
        }
    }

    /// Serializes one valid registry with a single manifest entry.
    ///
    /// @param name registry display name
    /// @param pluginId indexed plugin ID
    /// @param manifestUrl manifest endpoint
    /// @return registry JSON
    private static byte @Unmodifiable [] registryWithEntry(String name, String pluginId, String manifestUrl) {
        return """
                {
                  "schemaVersion": 1,
                  "name": "%s",
                  "plugins": [
                    {
                      "id": "%s",
                      "name": "Context Plugin",
                      "manifestUrl": "%s"
                    }
                  ]
                }
                """.formatted(name, pluginId, manifestUrl).getBytes(StandardCharsets.UTF_8);
    }

    /// Serializes one valid schema-two manifest for the supplied registry ID.
    ///
    /// @param pluginId expected plugin ID
    /// @return manifest JSON
    private static byte @Unmodifiable [] validManifest(String pluginId) {
        return """
                {
                  "schemaVersion": 2,
                  "id": "%s",
                  "versions": [
                    {
                      "version": "1.0.0",
                      "packageUrl": "https://example.com/plugin.npl",
                      "sha256": "0000000000000000000000000000000000000000000000000000000000000000",
                      "pluginApiVersion": 4,
                      "permissions": [],
                      "requiredPermissions": [],
                      "launcherVersion": "*",
                      "dependencies": [],
                      "size": 1
                    }
                  ]
                }
                """.formatted(pluginId).getBytes(StandardCharsets.UTF_8);
    }

    /// Serializes one valid schema-two manifest with a caller-selected README URL.
    ///
    /// @param pluginId expected plugin ID
    /// @param readmeUrl README endpoint
    /// @return manifest JSON
    private static byte @Unmodifiable [] manifestWithReadme(String pluginId, String readmeUrl) {
        return """
                {
                  "schemaVersion": 2,
                  "id": "%s",
                  "readmeUrl": "%s",
                  "versions": [
                    {
                      "version": "1.0.0",
                      "packageUrl": "https://example.com/plugin.npl",
                      "sha256": "0000000000000000000000000000000000000000000000000000000000000000",
                      "pluginApiVersion": 4,
                      "permissions": [],
                      "requiredPermissions": [],
                      "launcherVersion": "*",
                      "dependencies": [],
                      "size": 1
                    }
                  ]
                }
                """.formatted(pluginId, readmeUrl).getBytes(StandardCharsets.UTF_8);
    }

    /// Owns a local registry fixture that can serve a valid catalog and repository manifest.
    @NotNullByDefault
    private static final class RegistryFixture implements AutoCloseable {
        /// Local server serving the generated catalog and manifest.
        private final HttpServer server;

        /// Creates a started fixture with its response handlers already installed.
        ///
        /// @param server local HTTP server
        private RegistryFixture(HttpServer server) {
            this.server = server;
        }

        /// Starts a fixture whose catalog contains one resolvable plugin entry.
        ///
        /// @param registryName catalog display name
        /// @param pluginId plugin ID in the catalog and manifest
        /// @return started fixture
        /// @throws IOException if the local server cannot start
        private static RegistryFixture start(String registryName, String pluginId) throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            AtomicReference<String> registryBody = new AtomicReference<>("""
                    {
                      "schemaVersion": 1,
                      "name": "%s",
                      "plugins": [
                        {
                          "id": "%s",
                          "name": "Bound Plugin",
                          "manifestUrl": "%s/manifest.json"
                        }
                      ]
                    }
                    """.formatted(registryName, pluginId, baseUrl));
            server.createContext("/plugins.json", exchange -> respond(
                    exchange,
                    registryBody.get().getBytes(StandardCharsets.UTF_8)
            ));
            server.createContext("/manifest.json", exchange -> respond(exchange, """
                    {
                      "schemaVersion": 2,
                      "id": "%s",
                      "versions": [
                        {
                          "version": "1.0.0",
                          "packageUrl": "https://example.com/plugin.npl",
                          "sha256": "0000000000000000000000000000000000000000000000000000000000000000",
                          "pluginApiVersion": 4,
                          "permissions": [],
                          "requiredPermissions": [],
                          "launcherVersion": "*",
                          "dependencies": [],
                          "size": 1
                        }
                      ]
                    }
                    """.formatted(pluginId).getBytes(StandardCharsets.UTF_8)));
            server.start();
            return new RegistryFixture(server);
        }

        /// Starts a fixture that serves one caller-provided catalog response.
        ///
        /// @param registryBody catalog response body
        /// @return started fixture
        /// @throws IOException if the local server cannot start
        private static RegistryFixture startRaw(String registryBody) throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            AtomicReference<String> body = new AtomicReference<>(registryBody);
            server.createContext("/plugins.json", exchange -> respond(
                    exchange,
                    body.get().getBytes(StandardCharsets.UTF_8)
            ));
            server.start();
            return new RegistryFixture(server);
        }

        /// Returns the catalog endpoint URL.
        ///
        /// @return local catalog URL
        private String registryUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/plugins.json";
        }

        /// Returns an unhandled local endpoint that deterministically responds with HTTP 404.
        ///
        /// @return unavailable registry URL
        private String unavailableRegistryUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/missing";
        }

        /// Stops the server after a test completes.
        @Override
        public void close() {
            server.stop(0);
        }
    }

    /// Creates a minimal validated repository manifest that points at one README URL.
    ///
    /// @param readmeUrl README endpoint
    /// @return validated repository manifest
    /// @throws IOException if the generated fixture is invalid
    private static PluginStoreManifest readmeManifest(String readmeUrl) throws IOException {
        return parseManifest("dev.hmclce.test.readme", """
                {
                  "schemaVersion": 1,
                  "id": "dev.hmclce.test.readme",
                  "readmeUrl": "%s",
                  "versions": [
                    {
                      "version": "1.0.0",
                      "packageUrl": "https://example.com/plugin.npl",
                      "sha256": "0000000000000000000000000000000000000000000000000000000000000000",
                      "pluginApiVersion": 2,
                      "size": 1
                    }
                  ]
                }
                """.formatted(readmeUrl));
    }

    /// Creates one valid empty registry response with the supplied display name.
    ///
    /// @param name registry display name
    /// @return serialized UTF-8 registry body
    private static byte @Unmodifiable [] registry(String name) {
        return """
                {
                  "schemaVersion": 1,
                  "name": "%s",
                  "plugins": []
                }
                """.formatted(name).getBytes(StandardCharsets.UTF_8);
    }

    /// Creates one schema-v2 repository manifest around serialized version entries.
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

    /// Creates one API-v4 repository version entry with no required permissions and unrestricted launcher support.
    ///
    /// @param version selected version string
    /// @param packageUrl package endpoint
    /// @param packageBytes exact package body
    /// @param permissionsJson permission array JSON
    /// @param dependenciesJson dependency array JSON
    /// @return serialized repository version entry
    /// @throws NoSuchAlgorithmException if SHA-256 is unavailable
    private static String repositoryVersion(
            String version,
            String packageUrl,
            byte @Unmodifiable [] packageBytes,
            String permissionsJson,
            String dependenciesJson
    ) throws NoSuchAlgorithmException {
        return """
                {
                  "version": "%s",
                  "packageUrl": "%s",
                  "sha256": "%s",
                  "pluginApiVersion": 4,
                  "permissions": %s,
                  "requiredPermissions": [],
                  "launcherVersion": "*",
                  "dependencies": %s,
                  "size": %d
                }
                """.formatted(
                version,
                packageUrl,
                sha256(packageBytes),
                permissionsJson,
                dependenciesJson,
                packageBytes.length
        );
    }

    /// Creates one API-v4 repository version entry with exact package policy metadata.
    ///
    /// @param version selected version string
    /// @param packageUrl package endpoint
    /// @param packageBytes exact package body
    /// @param permissionsJson complete permission array JSON
    /// @param requiredPermissionsJson required permission array JSON
    /// @param launcherVersion launcher version constraint
    /// @param dependenciesJson dependency array JSON
    /// @return serialized repository version entry
    /// @throws NoSuchAlgorithmException if SHA-256 is unavailable
    private static String repositoryVersionFour(
            String version,
            String packageUrl,
            byte @Unmodifiable [] packageBytes,
            String permissionsJson,
            String requiredPermissionsJson,
            String launcherVersion,
            String dependenciesJson
    ) throws NoSuchAlgorithmException {
        return """
                {
                  "version": "%s",
                  "packageUrl": "%s",
                  "sha256": "%s",
                  "pluginApiVersion": 4,
                  "permissions": %s,
                  "requiredPermissions": %s,
                  "launcherVersion": "%s",
                  "dependencies": %s,
                  "size": %d
                }
                """.formatted(
                version,
                packageUrl,
                sha256(packageBytes),
                permissionsJson,
                requiredPermissionsJson,
                launcherVersion,
                dependenciesJson,
                packageBytes.length
        );
    }

    /// Creates one API-v5 repository version entry with exact runtime compatibility metadata.
    ///
    /// @param version selected version string
    /// @param packageUrl package endpoint
    /// @param packageBytes exact package body
    /// @param runtime canonical runtime identifier
    /// @param abi required HMCL Plugin ABI generation
    /// @param platformsJson platform target array JSON
    /// @return serialized repository version entry
    /// @throws NoSuchAlgorithmException if SHA-256 is unavailable
    private static String repositoryVersionFive(
            String version,
            String packageUrl,
            byte @Unmodifiable [] packageBytes,
            String runtime,
            int abi,
            String platformsJson
    ) throws NoSuchAlgorithmException {
        return """
                {
                  "version": "%s",
                  "packageUrl": "%s",
                  "sha256": "%s",
                  "pluginApiVersion": 5,
                  "permissions": [],
                  "requiredPermissions": [],
                  "launcherVersion": "*",
                  "runtime": "%s",
                  "abi": %d,
                  "platforms": %s,
                  "dependencies": [],
                  "size": %d
                }
                """.formatted(
                version,
                packageUrl,
                sha256(packageBytes),
                runtime,
                abi,
                platformsJson,
                packageBytes.length
        );
    }

    /// Creates one validated store entry with caller-selected compatibility declarations.
    ///
    /// @param pluginApiVersion package manifest schema version
    /// @param declarationsJson launcher, runtime, ABI, platform, permission, and dependency declarations
    /// @return validated store version entry
    /// @throws IOException if the generated declaration is invalid
    private static PluginStoreManifest.PluginVersionEntry compatibilityVersion(
            int pluginApiVersion,
            String declarationsJson
    ) throws IOException {
        String pluginId = "dev.hmclce.test.compatibility";
        PluginStoreManifest manifest = parseManifest(pluginId, """
                {
                  "schemaVersion": 2,
                  "id": "%s",
                  "versions": [{
                    "version": "1.0.0",
                    "packageUrl": "https://example.com/plugin.npl",
                    "sha256": "0000000000000000000000000000000000000000000000000000000000000000",
                    "pluginApiVersion": %d,
                    "size": 1,
                    %s
                  }]
                }
                """.formatted(pluginId, pluginApiVersion, declarationsJson));
        return Objects.requireNonNull(manifest.getLatestVersion());
    }

    /// Asserts that shared compatibility diagnostics retain every relevant rejected value.
    ///
    /// @param manager store manager using a deterministic runtime registry and host target
    /// @param version incompatible store version
    /// @param expectedDetails values that must appear in the compatibility detail
    private static void assertCompatibilityRejected(
            PluginStoreManager manager,
            PluginStoreManifest.PluginVersionEntry version,
            String @Unmodifiable ... expectedDetails
    ) {
        IOException exception = assertThrows(IOException.class, () -> manager.validateCompatibility(version));
        for (String expectedDetail : expectedDetails) {
            assertTrue(exception.getMessage().contains(expectedDetail), exception.getMessage());
        }
        assertFalse(manager.isCompatible(version));
    }

    /// Creates a runtime provider with the supplied canonical type and ABI generations.
    ///
    /// @param runtimeType canonical runtime identifier
    /// @param implementedAbis ABI generations implemented by the provider
    /// @return test runtime provider
    private static RuntimeProvider runtimeProvider(
            String runtimeType,
            @Unmodifiable Set<Integer> implementedAbis
    ) {
        return new RuntimeProvider() {
            /// Returns the configured runtime identifier.
            @Override
            public String runtimeType() {
                return runtimeType;
            }

            /// Returns the configured immutable ABI set.
            @Override
            public @Unmodifiable Set<Integer> implementedPluginAbis() {
                return implementedAbis;
            }

            /// Describes the deterministic test runtime provider.
            @Override
            public String describe() {
                return "Test runtime provider " + runtimeType;
            }
        };
    }

    /// Parses and validates one repository manifest fixture.
    ///
    /// @param expectedPluginId plugin ID bound to the repository
    /// @param json repository manifest JSON
    /// @return validated repository manifest
    /// @throws IOException if the fixture violates repository validation
    private static PluginStoreManifest parseManifest(String expectedPluginId, String json) throws IOException {
        return PluginStoreManifest.fromJson(JsonParser.parseString(json), expectedPluginId);
    }

    /// Creates the smallest package that passes package-manifest parsing for the requested schema.
    ///
    /// @param pluginId package plugin ID
    /// @param version package version
    /// @param schemaVersion package manifest schema version
    /// @param permissionsJson permission array used by schema-v3 packages
    /// @param dependenciesJson dependency array JSON
    /// @return complete `.npl` package bytes
    /// @throws IOException if ZIP creation fails
    private static byte @Unmodifiable [] createPluginPackage(
            String pluginId,
            String version,
            int schemaVersion,
            String permissionsJson,
            String dependenciesJson
    ) throws IOException {
        return createPluginPackage(
                pluginId,
                version,
                schemaVersion,
                permissionsJson,
                dependenciesJson,
                "[]",
                "*"
        );
    }

    /// Creates the smallest package with caller-selected schema-v4 security and launcher metadata.
    ///
    /// @param pluginId package plugin ID
    /// @param version package version
    /// @param schemaVersion package manifest schema version
    /// @param permissionsJson complete permission array JSON
    /// @param dependenciesJson dependency array JSON
    /// @param requiredPermissionsJson required permission array JSON for schema-v4 packages
    /// @param launcherVersion launcher constraint for schema-v4 packages
    /// @return complete `.npl` package bytes
    /// @throws IOException if ZIP creation fails
    private static byte @Unmodifiable [] createPluginPackage(
            String pluginId,
            String version,
            int schemaVersion,
            String permissionsJson,
            String dependenciesJson,
            String requiredPermissionsJson,
            String launcherVersion
    ) throws IOException {
        String permissionDeclaration = schemaVersion >= 3
                ? "\"permissions\": " + permissionsJson + ","
                : "";
        String schemaFourDeclarations = schemaVersion >= 4
                ? "\"requiredPermissions\": " + requiredPermissionsJson + ",\n"
                + "      \"launcherVersion\": \"" + launcherVersion + "\","
                : "";
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("plugin.json"));
            zip.write("""
                    {
                      "schemaVersion": %d,
                      "id": "%s",
                      "name": "Plugin Store Test",
                      "version": "%s",
                      "type": "java",
                      "entrypoint": "dev.hmclce.test.Plugin",
                      %s
                      %s
                      "dependencies": %s
                    }
                    """.formatted(
                    schemaVersion,
                    pluginId,
                    version,
                    permissionDeclaration,
                    schemaFourDeclarations,
                    dependenciesJson
            ).getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    /// Creates the smallest valid schema-v5 package with exact runtime compatibility metadata.
    ///
    /// @param pluginId package plugin ID
    /// @param version package version
    /// @param runtime canonical runtime identifier
    /// @param abi required HMCL Plugin ABI generation
    /// @param platformsJson platform target array JSON
    /// @return complete `.npl` package bytes
    /// @throws IOException if ZIP creation fails
    private static byte @Unmodifiable [] createPluginPackageFive(
            String pluginId,
            String version,
            String runtime,
            int abi,
            String platformsJson
    ) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("plugin.json"));
            zip.write("""
                    {
                      "schemaVersion": 5,
                      "id": "%s",
                      "name": "Plugin Store Test",
                      "version": "%s",
                      "type": "java",
                      "entrypoint": "dev.hmclce.test.Plugin",
                      "permissions": [],
                      "requiredPermissions": [],
                      "launcherVersion": "*",
                      "runtime": "%s",
                      "abi": %d,
                      "platforms": %s,
                      "dependencies": []
                    }
                    """.formatted(pluginId, version, runtime, abi, platformsJson)
                    .getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    /// Writes one HTTP response with an exact content length.
    ///
    /// @param exchange incoming HTTP exchange
    /// @param body response body
    /// @throws IOException if the response cannot be written
    private static void respond(HttpExchange exchange, byte @Unmodifiable [] body) throws IOException {
        exchange.sendResponseHeaders(HttpURLConnection.HTTP_OK, body.length);
        try (var output = exchange.getResponseBody()) {
            output.write(body);
        }
    }

    /// Writes one HTTP response using chunked transfer encoding.
    ///
    /// @param exchange incoming HTTP exchange
    /// @param body response body
    /// @throws IOException if the response cannot be written
    private static void respondChunked(HttpExchange exchange, byte @Unmodifiable [] body) throws IOException {
        exchange.sendResponseHeaders(HttpURLConnection.HTTP_OK, 0);
        try (var output = exchange.getResponseBody()) {
            output.write(body);
        }
    }

    /// Writes one relative temporary redirect without a response body.
    ///
    /// @param exchange incoming HTTP exchange
    /// @param location relative redirect target
    /// @throws IOException if the redirect cannot be written
    private static void redirect(HttpExchange exchange, String location) throws IOException {
        exchange.getResponseHeaders().set("Location", location);
        exchange.sendResponseHeaders(HttpURLConnection.HTTP_MOVED_TEMP, -1);
        exchange.close();
    }

    /// Returns the lower-case digest format used by repository manifests.
    ///
    /// @param bytes content to hash
    /// @return lower-case SHA-256 digest
    /// @throws NoSuchAlgorithmException if SHA-256 is unavailable
    private static String sha256(byte @Unmodifiable [] bytes) throws NoSuchAlgorithmException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
