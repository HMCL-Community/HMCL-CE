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
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies schema-v4 required and optional permission behavior across installation and lifecycle updates.
///
/// Discovery reaches [PluginManager#registerPreparedPlugin(PreparedPlugin)], which requires the JavaFX
/// application thread, so this class is skipped when the JavaFX toolkit cannot start (e.g. headless CI).
@EnabledIf("org.jackhuang.hmcl.JavaFXLauncher#isStarted")
@NotNullByDefault
public final class PluginManagerSchemaFourPermissionTest {
    /// Loads a schema-v4 plugin with its required grant and a denied optional capability, then rejects an attempted
    /// required-permission revocation without stopping the active lifecycle.
    ///
    /// @param temporaryDirectory isolated test directory
    /// @throws Exception if package creation, permission persistence, or lifecycle loading fails
    @Test
    public void keepRequiredGrantWhileOptionalPermissionIsDenied(
            @TempDir Path temporaryDirectory
    ) throws Exception {
        Path localHome = temporaryDirectory.resolve("home");
        PluginManager manager = new PluginManager(localHome);
        String pluginId = "dev.hmclce.test.schema-four-permissions";
        Path sourcePackage = temporaryDirectory.resolve("schema-four-permissions.npl");
        writePluginPackage(
                sourcePackage,
                pluginId,
                "1.0.0",
                "[\"filesystem\",\"launcher-ui\"]",
                "[\"filesystem\"]"
        );
        clearLifecycleProbeProperties();
        LocalPluginInspection inspection = manager.inspectLocalPluginPackage(sourcePackage);
        @Unmodifiable Set<PluginPermission> suggested = manager.getSuggestedGrantedPermissions(inspection);

        assertEquals(Set.of(PluginPermission.FILESYSTEM), suggested);
        manager.prepareLocalPluginInstallation(inspection, suggested);

        PluginManager restarted = new PluginManager(localHome);
        FXThreadTestSupport.runOnFxThread(restarted::discoverPlugins);
        PluginContainer container = Objects.requireNonNull(restarted.getPlugin(pluginId));
        assertTrue(container.isEnabled());
        assertEquals("true", System.getProperty(LifecycleProbePlugin.CONSTRUCTED_PROPERTY));
        assertEquals("true", System.getProperty(LifecycleProbePlugin.LOADED_PROPERTY));
        assertEquals("true", System.getProperty(LifecycleProbePlugin.ENABLED_PROPERTY));
        assertEquals(Set.of(PluginPermission.FILESYSTEM), restarted.getGrantedPermissions(pluginId));

        assertThrows(IllegalArgumentException.class, () -> restarted.setGrantedPermissions(pluginId, Set.of()));

        assertSame(container, restarted.getPlugin(pluginId));
        assertTrue(container.isEnabled());
        assertEquals(Set.of(PluginPermission.FILESYSTEM), restarted.getGrantedPermissions(pluginId));
        assertEquals(PluginRuntimeStatus.ENABLED, restarted.getPluginRuntimeStatus(pluginId));
    }

    /// Blocks a manually dropped schema-v4 package with required permissions before its lifecycle class is loaded.
    ///
    /// @param temporaryDirectory isolated test directory
    /// @throws Exception if package creation, state persistence, or discovery fails
    @Test
    public void blockPackageWithoutArtifactPermissionDecision(
            @TempDir Path temporaryDirectory
    ) throws Exception {
        Path localHome = temporaryDirectory.resolve("home");
        PluginManager manager = new PluginManager(localHome);
        String pluginId = "dev.hmclce.test.schema-four-unconfirmed";
        writePluginPackage(
                manager.getPluginsDirectory().resolve(pluginId + ".npl"),
                pluginId,
                "1.0.0",
                "[\"filesystem\"]",
                "[\"filesystem\"]"
        );
        clearLifecycleProbeProperties();
        manager.enablePlugin(pluginId);

        FXThreadTestSupport.runOnFxThread(manager::discoverPlugins);

        assertNull(manager.getPlugin(pluginId));
        assertEquals(PluginRuntimeStatus.BLOCKED_PERMISSION, manager.getPluginRuntimeStatus(pluginId));
        assertLifecycleProbeNotInvoked();
    }

    /// Preserves the current artifact's required grant while a different staged artifact's permissions are managed.
    ///
    /// @param temporaryDirectory isolated test directory
    /// @throws Exception if package creation, staging, permission persistence, or lifecycle loading fails
    @Test
    public void preserveLoadedRequiredGrantWhileManagingPendingArtifact(
            @TempDir Path temporaryDirectory
    ) throws Exception {
        Path localHome = temporaryDirectory.resolve("home");
        PluginManager manager = new PluginManager(localHome);
        String pluginId = "dev.hmclce.test.schema-four-pending";
        Path versionOne = temporaryDirectory.resolve("schema-four-v1.npl");
        writePluginPackage(
                versionOne,
                pluginId,
                "1.0.0",
                "[\"filesystem\"]",
                "[\"filesystem\"]"
        );
        manager.prepareLocalPluginInstallation(versionOne, Set.of(PluginPermission.FILESYSTEM));
        PluginManager activeManager = new PluginManager(localHome);
        FXThreadTestSupport.runOnFxThread(activeManager::discoverPlugins);
        PluginContainer loaded = Objects.requireNonNull(activeManager.getPlugin(pluginId));

        Path versionTwo = temporaryDirectory.resolve("schema-four-v2.npl");
        writePluginPackage(
                versionTwo,
                pluginId,
                "2.0.0",
                "[\"network\"]",
                "[\"network\"]"
        );
        activeManager.prepareLocalPluginInstallation(versionTwo, Set.of(PluginPermission.NETWORK));

        activeManager.setGrantedPermissions(pluginId, Set.of(PluginPermission.NETWORK));

        assertEquals(Set.of(PluginPermission.NETWORK), activeManager.getGrantedPermissions(pluginId));
        assertEquals(Set.of(PluginPermission.FILESYSTEM), loaded.getContext().getGrantedPermissions());
        assertSame(loaded, activeManager.getPlugin(pluginId));
        assertTrue(loaded.isEnabled());
    }

    /// Writes a schema-v4 lifecycle package with explicit required permissions.
    ///
    /// @param target target package path
    /// @param pluginId package plugin ID
    /// @param version package version
    /// @param permissionsJson raw declared-permission JSON array
    /// @param requiredPermissionsJson raw required-permission JSON array
    /// @throws IOException if package creation fails
    private static void writePluginPackage(
            Path target,
            String pluginId,
            String version,
            String permissionsJson,
            String requiredPermissionsJson
    ) throws IOException {
        Files.createDirectories(Objects.requireNonNull(target.getParent()));
        String manifest = """
                {
                  "schemaVersion": 4,
                  "id": "%s",
                  "name": "Schema Four Permission Test",
                  "version": "%s",
                  "type": "java",
                  "entrypoint": "%s",
                  "permissions": %s,
                  "requiredPermissions": %s,
                  "launcherVersion": "*",
                  "dependencies": []
                }
                """.formatted(
                        pluginId,
                        version,
                        LifecycleProbePlugin.class.getName(),
                        permissionsJson,
                        requiredPermissionsJson
                );
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(target))) {
            ZipEntry manifestEntry = new ZipEntry("plugin.json");
            manifestEntry.setTime(0);
            output.putNextEntry(manifestEntry);
            output.write(manifest.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
            writeClassEntry(output, LifecycleProbePlugin.class);
        }
    }

    /// Copies one compiled lifecycle class into a generated plugin package.
    ///
    /// @param output package output stream
    /// @param entrypoint lifecycle class whose bytes belong to the package
    /// @throws IOException if the compiled class resource cannot be read or written
    private static void writeClassEntry(
            ZipOutputStream output,
            Class<? extends Plugin> entrypoint
    ) throws IOException {
        String resource = entrypoint.getName().replace('.', '/') + ".class";
        try (@Nullable var input = entrypoint.getClassLoader().getResourceAsStream(resource)) {
            if (input == null) {
                throw new IOException("Compiled test plugin class not found: " + resource);
            }
            ZipEntry classEntry = new ZipEntry(resource);
            classEntry.setTime(0);
            output.putNextEntry(classEntry);
            input.transferTo(output);
            output.closeEntry();
        }
    }

    /// Clears every lifecycle marker used by the package-owned test plugin.
    private static void clearLifecycleProbeProperties() {
        System.clearProperty(LifecycleProbePlugin.CONSTRUCTED_PROPERTY);
        System.clearProperty(LifecycleProbePlugin.LOADED_PROPERTY);
        System.clearProperty(LifecycleProbePlugin.ENABLED_PROPERTY);
        System.clearProperty(LifecycleProbePlugin.DISABLED_PROPERTY);
        System.clearProperty(LifecycleProbePlugin.UNLOADED_PROPERTY);
    }

    /// Asserts that no package-owned constructor or lifecycle callback executed.
    private static void assertLifecycleProbeNotInvoked() {
        assertNull(System.getProperty(LifecycleProbePlugin.CONSTRUCTED_PROPERTY));
        assertNull(System.getProperty(LifecycleProbePlugin.LOADED_PROPERTY));
        assertNull(System.getProperty(LifecycleProbePlugin.ENABLED_PROPERTY));
        assertNull(System.getProperty(LifecycleProbePlugin.DISABLED_PROPERTY));
        assertNull(System.getProperty(LifecycleProbePlugin.UNLOADED_PROPERTY));
    }
}
