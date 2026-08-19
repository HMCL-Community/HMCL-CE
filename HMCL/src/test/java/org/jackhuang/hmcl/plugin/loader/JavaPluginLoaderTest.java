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
package org.jackhuang.hmcl.plugin.loader;

import org.jackhuang.hmcl.plugin.Plugin;
import org.jackhuang.hmcl.plugin.PluginArtifactIdentity;
import org.jackhuang.hmcl.plugin.PluginManifest;
import org.jackhuang.hmcl.plugin.internal.PluginPackageVersions;
import org.jackhuang.hmcl.plugin.internal.VerifiedPluginPackage;
import org.jackhuang.hmcl.plugin.loader.fixture.ContextClassLoaderPlugin;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies fail-closed Mixin loading and plugin context-class-loader scopes for JVM lifecycle code.
@NotNullByDefault
public final class JavaPluginLoaderTest {
    /// Refuses to construct a Mixin lifecycle implementation when the Agent did not confirm the exact artifact.
    ///
    /// @param temporaryDirectory isolated package and cache directory
    /// @throws Exception if package creation or verification fails ucepectedly
    @Test
    public void rejectUnconfirmedMixinBeforeConstruction(@TempDir Path temporaryDirectory) throws Exception {
        String pluginId = "dev.hmclce.test.loader-unconfirmed-mixin";
        String version = "1.0.0";
        Path nplFile = temporaryDirectory.resolve("unconfirmed-mixin.npl");
        String manifestJson = writePluginPackage(nplFile, pluginId, version, true);
        PluginManifest manifest = parseManifest(manifestJson);
        VerifiedPluginPackage pluginPackage = prepareVerifiedPackage(
                temporaryDirectory,
                nplFile,
                pluginId,
                version
        );
        System.clearProperty(ContextClassLoaderPlugin.CONSTRUCTED_PROPERTY);
        try {
            IOException failure = assertThrows(
                    IOException.class,
                    () -> new JavaPluginLoader().load(manifest, pluginPackage, nplFile)
            );

            assertTrue(failure.getMessage().contains("did not confirm exact artifact"));
            assertNull(System.getProperty(ContextClassLoaderPlugin.CONSTRUCTED_PROPERTY));
        } finally {
            System.clearProperty(ContextClassLoaderPlugin.CONSTRUCTED_PROPERTY);
        }
    }

    /// Installs the package loader during construction and exposes the same reusable scope for lifecycle callbacks.
    ///
    /// @param temporaryDirectory isolated package and cache directory
    /// @throws Exception if package creation, loading, or class-loader cleanup fails
    @Test
    public void usePluginContextClassLoaderForConstructionAndLifecycle(@TempDir Path temporaryDirectory)
            throws Exception {
        String pluginId = "dev.hmclce.test.loader-context";
        String version = "1.0.0";
        Path nplFile = temporaryDirectory.resolve("context-loader.npl");
        String manifestJson = writePluginPackage(nplFile, pluginId, version, false);
        PluginManifest manifest = parseManifest(manifestJson);
        VerifiedPluginPackage pluginPackage = prepareVerifiedPackage(
                temporaryDirectory,
                nplFile,
                pluginId,
                version
        );
        @Nullable ClassLoader previousClassLoader = Thread.currentThread().getContextClassLoader();
        System.clearProperty(ContextClassLoaderPlugin.CONSTRUCTED_PROPERTY);
        System.clearProperty(ContextClassLoaderPlugin.ENABLED_PROPERTY);
        @Nullable URLClassLoader closeableLoader = null;
        try {
            Plugin plugin = new JavaPluginLoader().load(manifest, pluginPackage, nplFile);
            ClassLoader pluginClassLoader = Objects.requireNonNull(plugin.getClass().getClassLoader());
            if (pluginClassLoader instanceof URLClassLoader urlClassLoader) {
                closeableLoader = urlClassLoader;
            }

            assertEquals("true", System.getProperty(ContextClassLoaderPlugin.CONSTRUCTED_PROPERTY));
            assertSame(previousClassLoader, Thread.currentThread().getContextClassLoader());
            assertThrows(IllegalStateException.class, plugin::onEnable);

            JavaPluginLoader.runWithPluginContextClassLoader(pluginClassLoader, plugin::onEnable);
            assertEquals("true", System.getProperty(ContextClassLoaderPlugin.ENABLED_PROPERTY));
            assertSame(previousClassLoader, Thread.currentThread().getContextClassLoader());

            IllegalStateException expected = new IllegalStateException("expected lifecycle failure");
            IllegalStateException actual = assertThrows(
                    IllegalStateException.class,
                    () -> JavaPluginLoader.runWithPluginContextClassLoader(pluginClassLoader, () -> {
                        assertSame(pluginClassLoader, Thread.currentThread().getContextClassLoader());
                        throw expected;
                    })
            );
            assertSame(expected, actual);
            assertSame(previousClassLoader, Thread.currentThread().getContextClassLoader());
        } finally {
            if (closeableLoader != null) {
                closeableLoader.close();
            }
            System.clearProperty(ContextClassLoaderPlugin.CONSTRUCTED_PROPERTY);
            System.clearProperty(ContextClassLoaderPlugin.ENABLED_PROPERTY);
            Thread.currentThread().setContextClassLoader(previousClassLoader);
        }
    }

    /// Writes one deterministic ordinary or Mixin test package with a service-discovering lifecycle implementation.
    ///
    /// @param target target `.npl` file
    /// @param pluginId plugin identifier
    /// @param version plugin version
    /// @param mixin whether the package declares a Mixin configuration
    /// @return exact manifest JSON stored in the package
    /// @throws IOException if the package cannot be written
    private static String writePluginPackage(
            Path target,
            String pluginId,
            String version,
            boolean mixin
    ) throws IOException {
        String mixinConfig = "mixins." + pluginId + ".json";
        String manifest = """
                {
                  "schemaVersion": 3,
                  "id": "%s",
                  "name": "Context Class Loader Test",
                  "version": "%s",
                  "type": "java",
                  "entrypoint": "%s",
                  "permissions": %s,
                  "dependencies": [],
                  "mixins": %s
                }
                """.formatted(
                pluginId,
                version,
                ContextClassLoaderPlugin.class.getName(),
                mixin ? "[\"mixin\"]" : "[]",
                mixin ? "[\"" + mixinConfig + "\"]" : "[]"
        );
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(target))) {
            writeZipEntry(output, "plugin.json", manifest.getBytes(StandardCharsets.UTF_8));
            if (mixin) {
                writeZipEntry(output, mixinConfig, "{}".getBytes(StandardCharsets.UTF_8));
            }
            writeClassEntry(output, ContextClassLoaderPlugin.class);
            writeClassEntry(output, ContextClassLoaderPlugin.ProbeService.class);
            writeClassEntry(output, ContextClassLoaderPlugin.ProbeServiceProvider.class);
            writeZipEntry(
                    output,
                    "META-INF/services/" + ContextClassLoaderPlugin.ProbeService.class.getName(),
                    (ContextClassLoaderPlugin.ProbeServiceProvider.class.getName() + "\n")
                            .getBytes(StandardCharsets.UTF_8)
            );
        }
        return manifest;
    }

    /// Parses one manifest fixture using the production schema validator.
    ///
    /// @param manifestJson exact manifest JSON
    /// @return validated plugin manifest
    /// @throws IOException if the manifest is invalid
    private static PluginManifest parseManifest(String manifestJson) throws IOException {
        return PluginManifest.fromJson(new StringReader(manifestJson));
    }

    /// Prepares one exact verified lifecycle package from the written fixture.
    ///
    /// @param temporaryDirectory isolated package cache parent
    /// @param nplFile source package
    /// @param pluginId plugin identifier
    /// @param version plugin version
    /// @return verified package inventory
    /// @throws IOException if hashing or package preparation fails
    private static VerifiedPluginPackage prepareVerifiedPackage(
            Path temporaryDirectory,
            Path nplFile,
            String pluginId,
            String version
    ) throws IOException {
        PluginArtifactIdentity identity = new PluginArtifactIdentity(
                pluginId,
                version,
                PluginPackageVersions.calculateSha256(nplFile)
        );
        return PluginPackageVersions.prepareVerifiedLifecyclePackage(
                nplFile,
                temporaryDirectory.resolve("plugin-data"),
                identity
        );
    }

    /// Writes one compiled test class into the package under its binary resource name.
    ///
    /// @param output package output
    /// @param testClass compiled fixture class
    /// @throws IOException if the class bytes cannot be read or written
    private static void writeClassEntry(ZipOutputStream output, Class<?> testClass) throws IOException {
        String resourceName = testClass.getName().replace('.', '/') + ".class";
        try (InputStream input = Objects.requireNonNull(testClass.getResourceAsStream("/" + resourceName))) {
            writeZipEntry(output, resourceName, input.readAllBytes());
        }
    }

    /// Writes one deterministic package entry.
    ///
    /// @param output package output
    /// @param name entry name
    /// @param bytes entry bytes
    /// @throws IOException if the entry cannot be written
    private static void writeZipEntry(
            ZipOutputStream output,
            String name,
            byte @Unmodifiable [] bytes
    ) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(0);
        output.putNextEntry(entry);
        output.write(bytes);
        output.closeEntry();
    }
}
