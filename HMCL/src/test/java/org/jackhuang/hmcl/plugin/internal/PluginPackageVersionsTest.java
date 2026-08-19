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
package org.jackhuang.hmcl.plugin.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies immutable, content-addressed plugin package publication and reuse.
@NotNullByDefault
public final class PluginPackageVersionsTest {
    /// Reuses an unchanged package without rewriting it and preserves a locked older version on update.
    ///
    /// @param temporaryDirectory isolated test directory
    /// @throws Exception if package creation or preparation fails
    @Test
    public void reusePackageAndPreserveLockedOlderVersion(@TempDir Path temporaryDirectory) throws Exception {
        String pluginId = "dev.hmclce.test.locking";
        Path nplFile = temporaryDirectory.resolve("plugin.npl");
        Path packageRoot = temporaryDirectory.resolve("plugin-data");
        writePackage(nplFile, "first");

        Path firstVersion = PluginPackageVersions.prepareLifecyclePackage(nplFile, packageRoot, pluginId);
        FileTime firstManifestTime = Files.getLastModifiedTime(firstVersion.resolve("plugin.json"));
        Path reusedVersion = PluginPackageVersions.prepareLifecyclePackage(nplFile, packageRoot, pluginId);

        assertEquals(firstVersion, reusedVersion);
        assertEquals(firstManifestTime, Files.getLastModifiedTime(reusedVersion.resolve("plugin.json")));
        assertEquals(PluginPackageVersions.calculateSha256(nplFile), firstVersion.getFileName().toString());

        try (JarFile ignored = new JarFile(firstVersion.resolve("libs/plugin.jar").toFile())) {
            writePackage(nplFile, "second");
            Path secondVersion = PluginPackageVersions.prepareLifecyclePackage(nplFile, packageRoot, pluginId);

            assertNotEquals(firstVersion, secondVersion);
            assertTrue(Files.isRegularFile(firstVersion.resolve("libs/plugin.jar")));
            assertTrue(Files.isRegularFile(secondVersion.resolve("libs/plugin.jar")));
            assertEquals(PluginPackageVersions.calculateSha256(nplFile), secondVersion.getFileName().toString());
        }
    }

    /// Publishes a Mixin package with a complete root-resource JAR before the directory becomes visible.
    ///
    /// @param temporaryDirectory isolated test directory
    /// @throws Exception if package creation or preparation fails
    @Test
    public void publishMixinRootResourcesBeforeUse(@TempDir Path temporaryDirectory) throws Exception {
        String pluginId = "dev.hmclce.test.mixin";
        Path nplFile = temporaryDirectory.resolve("plugin.npl");
        Path cacheRoot = temporaryDirectory.resolve("plugin-cache");
        writePackage(nplFile, "mixin");

        Path version = PluginPackageVersions.prepareMixinPackage(nplFile, cacheRoot, pluginId);
        Path rootResourceJar = version.resolve(PluginPackageVersions.ROOT_RESOURCE_JAR);

        assertTrue(Files.isRegularFile(rootResourceJar));
        try (JarFile jarFile = new JarFile(rootResourceJar.toFile())) {
            assertNotNull(jarFile.getEntry("plugin.json"));
            assertNotNull(jarFile.getEntry("mixins.test.json"));
            assertNotNull(jarFile.getEntry("loose-resource.txt"));
        }
    }

    /// Publishes a separate repair cache without moving or deleting a tampered canonical directory.
    ///
    /// @param temporaryDirectory isolated test directory
    /// @throws Exception if package preparation, tampering, or repair fails
    @Test
    public void repairMixinCacheWithInjectedJar(@TempDir Path temporaryDirectory) throws Exception {
        String pluginId = "dev.hmclce.test.mixin-repair";
        Path nplFile = temporaryDirectory.resolve("plugin.npl");
        Path cacheRoot = temporaryDirectory.resolve("plugin-cache");
        writePackage(nplFile, "mixin-repair");
        Path originalVersion = PluginPackageVersions.prepareMixinPackage(nplFile, cacheRoot, pluginId);
        Files.write(originalVersion.resolve("injected.jar"), new byte[]{1, 2, 3});

        Path repairedVersion = PluginPackageVersions.prepareMixinPackage(nplFile, cacheRoot, pluginId);

        assertNotEquals(originalVersion, repairedVersion);
        assertTrue(repairedVersion.getFileName().toString().startsWith(
                PluginPackageVersions.calculateSha256(nplFile) + ".repair-"
        ));
        assertFalse(Files.exists(repairedVersion.resolve("injected.jar")));
        assertTrue(Files.exists(originalVersion.resolve("injected.jar")));
        assertTrue(Files.isRegularFile(repairedVersion.resolve(PluginPackageVersions.ROOT_RESOURCE_JAR)));
    }

    /// Serializes concurrent preparation of the same version and leaves no partial package directories.
    ///
    /// @param temporaryDirectory isolated test directory
    /// @throws Exception if package creation, concurrency, or preparation fails
    @Test
    public void prepareSameVersionConcurrently(@TempDir Path temporaryDirectory) throws Exception {
        String pluginId = "dev.hmclce.test.concurrent";
        Path nplFile = temporaryDirectory.resolve("plugin.npl");
        Path packageRoot = temporaryDirectory.resolve("plugin-data");
        writePackage(nplFile, "concurrent");

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Path> first = executor.submit(() -> prepareAfterSignal(
                    nplFile,
                    packageRoot,
                    pluginId,
                    ready,
                    start
            ));
            Future<Path> second = executor.submit(() -> prepareAfterSignal(
                    nplFile,
                    packageRoot,
                    pluginId,
                    ready,
                    start
            ));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();

            assertEquals(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }

        Path versionsDirectory = PluginPackageVersions.getPluginVersionsDirectory(packageRoot, pluginId);
        try (Stream<Path> children = Files.list(versionsDirectory)) {
            assertFalse(children.anyMatch(path -> path.getFileName().toString().startsWith(".tmp-")));
        }
    }

    /// Retries a transient access denial and publishes the prepared directory without replacing data.
    ///
    /// @param temporaryDirectory isolated test directory
    /// @throws Exception if directory preparation or publication fails
    @Test
    public void retryTransientAccessDeniedDuringPublication(@TempDir Path temporaryDirectory) throws Exception {
        Path source = temporaryDirectory.resolve("source");
        Path target = temporaryDirectory.resolve("target");
        Files.createDirectory(source);
        Files.writeString(source.resolve("payload.txt"), "payload", StandardCharsets.UTF_8);
        AtomicInteger attempts = new AtomicInteger();

        PluginPackageVersions.publishWithRetries(source, target, (moveSource, moveTarget) -> {
            if (attempts.getAndIncrement() == 0) {
                throw new AccessDeniedException(
                        moveSource.toString(),
                        moveTarget.toString(),
                        "simulated transient sharing violation"
                );
            }
            Files.move(moveSource, moveTarget);
        });

        assertEquals(2, attempts.get());
        assertFalse(Files.exists(source));
        assertEquals("payload", Files.readString(target.resolve("payload.txt"), StandardCharsets.UTF_8));
    }

    /// Refuses to replace a target that appears while an access-denied publication is waiting to retry.
    ///
    /// @param temporaryDirectory isolated test directory
    /// @throws Exception if directory preparation or publication probing fails
    @Test
    public void refuseRacedPublicationTarget(@TempDir Path temporaryDirectory) throws Exception {
        Path source = temporaryDirectory.resolve("source");
        Path target = temporaryDirectory.resolve("target");
        Files.createDirectory(source);
        Files.writeString(source.resolve("payload.txt"), "source", StandardCharsets.UTF_8);

        FileAlreadyExistsException exception = assertThrows(
                FileAlreadyExistsException.class,
                () -> PluginPackageVersions.publishWithRetries(source, target, (moveSource, moveTarget) -> {
                    Files.createDirectory(moveTarget);
                    Files.writeString(moveTarget.resolve("payload.txt"), "raced", StandardCharsets.UTF_8);
                    throw new AccessDeniedException(
                            moveSource.toString(),
                            moveTarget.toString(),
                            "simulated publication race"
                    );
                })
        );

        assertTrue(exception.getMessage().contains("target appeared"));
        assertEquals("source", Files.readString(source.resolve("payload.txt"), StandardCharsets.UTF_8));
        assertEquals("raced", Files.readString(target.resolve("payload.txt"), StandardCharsets.UTF_8));
    }

    /// Stops after the bounded retry budget when publication remains access-denied.
    ///
    /// @param temporaryDirectory isolated test directory
    /// @throws Exception if directory preparation or publication probing fails
    @Test
    public void stopAfterPersistentPublicationDenial(@TempDir Path temporaryDirectory) throws Exception {
        Path source = temporaryDirectory.resolve("source");
        Path target = temporaryDirectory.resolve("target");
        Files.createDirectory(source);
        AtomicInteger attempts = new AtomicInteger();

        IOException exception = assertThrows(
                IOException.class,
                () -> PluginPackageVersions.publishWithRetries(source, target, (moveSource, moveTarget) -> {
                    attempts.incrementAndGet();
                    throw new AccessDeniedException(
                            moveSource.toString(),
                            moveTarget.toString(),
                            "simulated persistent sharing violation"
                    );
                })
        );

        assertEquals(5, attempts.get());
        assertTrue(exception.getMessage().contains("after 5 attempts"));
        assertTrue(Files.isDirectory(source));
        assertFalse(Files.exists(target));
    }

    /// Keeps both directories unchanged when the real no-replace move finds an existing target.
    ///
    /// @param temporaryDirectory isolated test directory
    /// @throws Exception if directory preparation or publication probing fails
    @Test
    public void neverReplaceExistingPublicationTarget(@TempDir Path temporaryDirectory) throws Exception {
        Path source = temporaryDirectory.resolve("source");
        Path target = temporaryDirectory.resolve("target");
        Files.createDirectory(source);
        Files.createDirectory(target);
        Files.writeString(source.resolve("payload.txt"), "source", StandardCharsets.UTF_8);
        Files.writeString(target.resolve("payload.txt"), "existing", StandardCharsets.UTF_8);

        assertThrows(
                FileAlreadyExistsException.class,
                () -> PluginPackageVersions.publishWithRetries(source, target, Files::move)
        );

        assertEquals("source", Files.readString(source.resolve("payload.txt"), StandardCharsets.UTF_8));
        assertEquals("existing", Files.readString(target.resolve("payload.txt"), StandardCharsets.UTF_8));
    }

    /// Keeps a locked legacy extraction untouched while publishing the new immutable layout.
    ///
    /// @param temporaryDirectory isolated test directory
    /// @throws Exception if package creation or preparation fails
    @Test
    public void ignoreLockedLegacyLayout(@TempDir Path temporaryDirectory) throws Exception {
        String pluginId = "dev.hmclce.test.legacy";
        Path nplFile = temporaryDirectory.resolve("plugin.npl");
        Path packageRoot = temporaryDirectory.resolve("plugin-data");
        Path legacyJar = packageRoot.resolve(pluginId).resolve("libs/plugin.jar");
        Files.createDirectories(legacyJar.getParent());
        Files.write(legacyJar, createNestedJar("legacy"));
        writePackage(nplFile, "current");

        try (JarFile ignored = new JarFile(legacyJar.toFile())) {
            Path version = PluginPackageVersions.prepareLifecyclePackage(nplFile, packageRoot, pluginId);

            assertTrue(version.startsWith(packageRoot.resolve(PluginPackageVersions.VERSIONS_DIRECTORY)));
            assertTrue(Files.isRegularFile(legacyJar));
            assertTrue(Files.isRegularFile(version.resolve("libs/plugin.jar")));
        }
    }

    /// Waits for both test workers and prepares the package after the shared start signal.
    ///
    /// @param nplFile source package
    /// @param packageRoot package root
    /// @param pluginId plugin identifier
    /// @param ready worker readiness latch
    /// @param start shared start latch
    /// @return prepared immutable directory
    /// @throws Exception if waiting or preparation fails
    private static Path prepareAfterSignal(
            Path nplFile,
            Path packageRoot,
            String pluginId,
            CountDownLatch ready,
            CountDownLatch start
    ) throws Exception {
        ready.countDown();
        assertTrue(start.await(5, TimeUnit.SECONDS));
        return PluginPackageVersions.prepareLifecyclePackage(nplFile, packageRoot, pluginId);
    }

    /// Writes a deterministic test plugin package with root resources and one nested JAR.
    ///
    /// @param target target NPL path
    /// @param payload version-specific payload
    /// @throws IOException if package creation fails
    private static void writePackage(Path target, String payload) throws IOException {
        byte @Unmodifiable [] nestedJar = createNestedJar(payload);
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(target))) {
            writeZipEntry(output, "plugin.json", "{\"id\":\"dev.hmclce.test\"}".getBytes(StandardCharsets.UTF_8));
            writeZipEntry(output, "mixins.test.json", "{}".getBytes(StandardCharsets.UTF_8));
            writeZipEntry(output, "loose-resource.txt", payload.getBytes(StandardCharsets.UTF_8));
            writeZipEntry(output, "libs/plugin.jar", nestedJar);
        }
    }

    /// Creates a valid nested JAR whose contents distinguish package versions.
    ///
    /// @param payload version-specific payload
    /// @return complete JAR bytes
    /// @throws IOException if JAR creation fails
    private static byte @Unmodifiable [] createNestedJar(String payload) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (JarOutputStream output = new JarOutputStream(bytes)) {
            JarEntry entry = new JarEntry("payload.txt");
            entry.setTime(0);
            output.putNextEntry(entry);
            output.write(payload.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
        return bytes.toByteArray();
    }

    /// Writes one ZIP entry with a stable timestamp.
    ///
    /// @param output destination ZIP stream
    /// @param name entry name
    /// @param contents entry bytes
    /// @throws IOException if writing fails
    private static void writeZipEntry(
            ZipOutputStream output,
            String name,
            byte @Unmodifiable [] contents
    ) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(0);
        output.putNextEntry(entry);
        output.write(contents);
        output.closeEntry();
    }
}
