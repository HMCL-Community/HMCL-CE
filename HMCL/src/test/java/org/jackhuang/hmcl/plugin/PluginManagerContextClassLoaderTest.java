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
package org.jackhuang.hmcl.plugin;

import org.jackhuang.hmcl.FXThreadTestSupport;
import org.jackhuang.hmcl.plugin.loader.fixture.ContextClassLoaderPlugin;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/// Verifies that every manager-owned lifecycle callback uses the exact package class loader as TCCL.
///
/// Discovery reaches [PluginManager#registerPreparedPlugin(PreparedPlugin)], which requires the JavaFX
/// application thread, so this class is skipped when the JavaFX toolkit cannot start (e.g. headless CI).
@EnabledIf("org.jackhuang.hmcl.JavaFXLauncher#isStarted")
@NotNullByDefault
public final class PluginManagerContextClassLoaderTest {
    /// Stable plugin ID used by the isolated lifecycle package.
    private static final String PLUGIN_ID = "dev.hmclce.test.context-class-loader";

    /// Loads, enables, disables, and unloads a package whose callbacks require package-local `ServiceLoader` lookup.
    ///
    /// @param temporaryDirectory isolated launcher home
    /// @throws IOException if package creation or manager mutation fails
    @Test
    public void lifecycleCallbacksUsePackageContextClassLoader(@TempDir Path temporaryDirectory) throws IOException {
        clearProbeProperties();
        try {
            PluginManager manager = new PluginManager(temporaryDirectory.resolve("home"));
            writePluginPackage(manager.getPluginsDirectory().resolve(PLUGIN_ID + ".npl"));
            manager.enablePlugin(PLUGIN_ID);

            FXThreadTestSupport.runOnFxThread(manager::discoverPlugins);

            assertNotNull(manager.getPlugin(PLUGIN_ID));
            assertEquals(PluginRuntimeStatus.ENABLED, manager.getPluginRuntimeStatus(PLUGIN_ID));
            assertEquals("true", System.getProperty(ContextClassLoaderPlugin.CONSTRUCTED_PROPERTY));
            assertEquals("true", System.getProperty(ContextClassLoaderPlugin.ENABLED_PROPERTY));

            manager.disablePlugin(PLUGIN_ID);
            assertEquals("true", System.getProperty(ContextClassLoaderPlugin.DISABLED_PROPERTY));

            manager.unloadPlugin(PLUGIN_ID);
            assertEquals("true", System.getProperty(ContextClassLoaderPlugin.UNLOADED_PROPERTY));
        } finally {
            clearProbeProperties();
        }
    }

    /// Writes one API-v4 package with package-owned lifecycle, service contract, provider, and descriptor bytes.
    ///
    /// @param target target `.npl` path
    /// @throws IOException if the package cannot be written
    private static void writePluginPackage(Path target) throws IOException {
        Files.createDirectories(Objects.requireNonNull(target.getParent()));
        String manifest = """
                {
                  "schemaVersion": 4,
                  "id": "%s",
                  "name": "Context Class Loader Test",
                  "version": "1.0.0",
                  "type": "java",
                  "entrypoint": "%s",
                  "permissions": [],
                  "requiredPermissions": [],
                  "launcherVersion": "*",
                  "dependencies": []
                }
                """.formatted(PLUGIN_ID, ContextClassLoaderPlugin.class.getName());
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(target))) {
            writeEntry(output, "plugin.json", manifest.getBytes(StandardCharsets.UTF_8));
            writeClassEntry(output, ContextClassLoaderPlugin.class);
            writeClassEntry(output, ContextClassLoaderPlugin.ProbeService.class);
            writeClassEntry(output, ContextClassLoaderPlugin.ProbeServiceProvider.class);
            writeEntry(
                    output,
                    "META-INF/services/" + ContextClassLoaderPlugin.ProbeService.class.getName(),
                    (ContextClassLoaderPlugin.ProbeServiceProvider.class.getName() + "\n")
                            .getBytes(StandardCharsets.UTF_8)
            );
        }
    }

    /// Copies one compiled class into the generated package under its binary resource name.
    ///
    /// @param output open package stream
    /// @param type compiled class to copy
    /// @throws IOException if class bytes cannot be read or written
    private static void writeClassEntry(ZipOutputStream output, Class<?> type) throws IOException {
        String resourceName = type.getName().replace('.', '/') + ".class";
        try (InputStream input = Objects.requireNonNull(
                type.getResourceAsStream("/" + resourceName),
                "Compiled context-loader fixture is unavailable: " + resourceName
        )) {
            writeEntry(output, resourceName, input.readAllBytes());
        }
    }

    /// Writes one deterministic package entry.
    ///
    /// @param output open package stream
    /// @param name package-relative resource name
    /// @param bytes immutable entry bytes
    /// @throws IOException if the entry cannot be written
    private static void writeEntry(
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

    /// Clears every probe property before and after the isolated lifecycle assertion.
    private static void clearProbeProperties() {
        System.clearProperty(ContextClassLoaderPlugin.CONSTRUCTED_PROPERTY);
        System.clearProperty(ContextClassLoaderPlugin.ENABLED_PROPERTY);
        System.clearProperty(ContextClassLoaderPlugin.DISABLED_PROPERTY);
        System.clearProperty(ContextClassLoaderPlugin.UNLOADED_PROPERTY);
    }
}
