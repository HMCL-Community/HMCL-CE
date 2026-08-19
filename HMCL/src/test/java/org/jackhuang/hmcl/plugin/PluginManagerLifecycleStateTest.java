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
import org.jackhuang.hmcl.plugin.internal.PluginPackageVersions;
import org.jackhuang.hmcl.plugin.trust.PluginRuntimeTrustGuard;
import org.jackhuang.hmcl.plugin.trust.PluginRuntimeTrustTestSupport;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies persisted enablement closure, dependency activation diagnostics, and reverse disable cascades.
@EnabledIf("org.jackhuang.hmcl.JavaFXLauncher#isStarted")
@NotNullByDefault
public final class PluginManagerLifecycleStateTest {
    /// Blocks a revoked certified artifact before normal lifecycle classes are defined or constructed.
    ///
    /// @param temporaryDirectory isolated launcher-local home
    /// @throws Exception if package or signed receipt fixture creation fails
    @Test
    public void blockRevokedCertifiedArtifactBeforeNormalLoad(
            @TempDir Path temporaryDirectory
    ) throws Exception {
        Path localHome = temporaryDirectory.resolve("home");
        String pluginId = "dev.hmclce.test.runtime-revoked";
        Path packageFile = localHome.resolve("plugins").resolve(pluginId + ".npl");
        writePluginPackage(
                packageFile,
                pluginId,
                "1.0.0",
                "[]",
                PackagedTestPlugin.class
        );
        String sha256 = PluginPackageVersions.calculateSha256(packageFile);
        PluginRuntimeTrustGuard runtimeTrustGuard = PluginRuntimeTrustTestSupport.revokedArtifactGuard(
                pluginId,
                "1.0.0",
                sha256,
                Files.size(packageFile)
        );
        PluginManager manager = new PluginManager(localHome, runtimeTrustGuard);
        manager.enablePlugin(pluginId);

        FXThreadTestSupport.runOnFxThread(manager::discoverPlugins);

        assertEquals(PluginRuntimeStatus.BLOCKED_REVOKED, manager.getPluginRuntimeStatus(pluginId));
        assertNull(manager.getPlugin(pluginId));
    }

    /// Preserves an exact legacy-policy diagnostic while rejecting a repeated enable request.
    ///
    /// @param temporaryDirectory isolated test directory
    /// @throws IOException if test package creation fails
    @Test
    public void preserveBlockedStatusWhenReenablingUnloadedPlugin(
            @TempDir Path temporaryDirectory
    ) throws IOException {
        clearLifecycleProbeProperties();
        PluginManager manager = new PluginManager(temporaryDirectory.resolve("home"));
        String pluginId = "dev.hmclce.test.reenable-blocked";
        writeLegacyPluginPackage(
                manager.getPluginsDirectory().resolve(pluginId + ".npl"),
                pluginId,
                "1.0.0",
                LifecycleProbePlugin.class
        );
        manager.enablePlugin(pluginId);
        FXThreadTestSupport.runOnFxThread(manager::discoverPlugins);
        assertEquals(PluginRuntimeStatus.BLOCKED_LEGACY, manager.getPluginRuntimeStatus(pluginId));
        String blockedDetail = Objects.requireNonNull(manager.getPluginRuntimeDetail(pluginId));

        manager.disablePlugin(pluginId);
        assertFalse(manager.isPluginEnabled(pluginId));
        assertFalse(manager.enablePlugin(pluginId));

        assertFalse(manager.isPluginEnabled(pluginId));
        assertEquals(PluginRuntimeStatus.BLOCKED_LEGACY, manager.getPluginRuntimeStatus(pluginId));
        assertEquals(blockedDetail, manager.getPluginRuntimeDetail(pluginId));
        assertLifecycleProbeNeverRan();
        clearLifecycleProbeProperties();
    }

    /// Propagates one loaded dependency's activation failure without discarding either desired enablement state.
    ///
    /// @param temporaryDirectory isolated test directory
    /// @throws IOException if test package creation or dependency inspection fails
    @Test
    public void propagateLoadedDependencyEnableFailure(
            @TempDir Path temporaryDirectory
    ) throws IOException {
        String dependencyId = "dev.hmclce.test.reenable-failure-base";
        String dependentId = "dev.hmclce.test.reenable-failure-dependent";
        System.clearProperty(ConditionalOnEnablePlugin.FAIL_PROPERTY);
        try {
            PluginManager manager = new PluginManager(temporaryDirectory.resolve("home"));
            writePluginPackage(
                    manager.getPluginsDirectory().resolve(dependencyId + ".npl"),
                    dependencyId,
                    "1.0.0",
                    "[]",
                    ConditionalOnEnablePlugin.class
            );
            writePluginPackage(
                    manager.getPluginsDirectory().resolve(dependentId + ".npl"),
                    dependentId,
                    "1.0.0",
                    "[{\"id\":\"" + dependencyId + "\",\"version\":\">=1.0.0\"}]",
                    PackagedTestPlugin.class
            );
            assertFalse(manager.enablePlugin(dependentId));
            assertTrue(manager.isPluginEnabled(dependencyId));
            assertTrue(manager.isPluginEnabled(dependentId));
            FXThreadTestSupport.runOnFxThread(manager::discoverPlugins);
            assertEquals(PluginRuntimeStatus.ENABLED, manager.getPluginRuntimeStatus(dependencyId));
            assertEquals(PluginRuntimeStatus.ENABLED, manager.getPluginRuntimeStatus(dependentId));

            manager.disablePlugin(dependencyId);
            assertFalse(manager.isPluginEnabled(dependencyId));
            assertFalse(manager.isPluginEnabled(dependentId));
            System.setProperty(ConditionalOnEnablePlugin.FAIL_PROPERTY, "true");

            assertFalse(manager.enablePlugin(dependentId));

            assertTrue(manager.isPluginEnabled(dependencyId));
            assertTrue(manager.isPluginEnabled(dependentId));
            assertEquals(PluginRuntimeStatus.LOAD_FAILED, manager.getPluginRuntimeStatus(dependencyId));
            assertEquals(PluginRuntimeStatus.LOAD_FAILED, manager.getPluginRuntimeStatus(dependentId));
            assertTrue(Objects.requireNonNull(manager.getPluginRuntimeDetail(dependentId)).contains(dependencyId));
            assertTrue(Objects.requireNonNull(manager.getPluginRuntimeDetail(dependentId))
                    .contains("Expected conditional onEnable failure"));
        } finally {
            System.clearProperty(ConditionalOnEnablePlugin.FAIL_PROPERTY);
        }
    }

    /// Clears restart-pending enablement for an unloaded dependent when its installed dependency is disabled.
    ///
    /// @param temporaryDirectory isolated test directory
    /// @throws IOException if test package creation or dependency inspection fails
    @Test
    public void disableUnloadedRestartPendingDependents(
            @TempDir Path temporaryDirectory
    ) throws IOException {
        PluginManager manager = new PluginManager(temporaryDirectory.resolve("home"));
        String dependencyId = "dev.hmclce.test.pending-disable-base";
        String dependentId = "dev.hmclce.test.pending-disable-dependent";
        writePluginPackage(
                manager.getPluginsDirectory().resolve(dependencyId + ".npl"),
                dependencyId,
                "1.0.0",
                "[]",
                PackagedTestPlugin.class
        );
        writePluginPackage(
                manager.getPluginsDirectory().resolve(dependentId + ".npl"),
                dependentId,
                "1.0.0",
                "[{\"id\":\"" + dependencyId + "\",\"version\":\">=1.0.0\"}]",
                PackagedTestPlugin.class
        );

        assertFalse(manager.enablePlugin(dependentId));
        assertTrue(manager.isPluginEnabled(dependencyId));
        assertTrue(manager.isPluginEnabled(dependentId));
        assertEquals(PluginRuntimeStatus.WAITING_FOR_RESTART, manager.getPluginRuntimeStatus(dependencyId));
        assertEquals(PluginRuntimeStatus.WAITING_FOR_RESTART, manager.getPluginRuntimeStatus(dependentId));

        manager.disablePlugin(dependencyId);

        assertFalse(manager.isPluginEnabled(dependencyId));
        assertFalse(manager.isPluginEnabled(dependentId));
        assertEquals(PluginRuntimeStatus.INSTALLED_DISABLED, manager.getPluginRuntimeStatus(dependencyId));
        assertEquals(PluginRuntimeStatus.INSTALLED_DISABLED, manager.getPluginRuntimeStatus(dependentId));
    }

    /// Rejects legacy enablement without recording either the plugin or its dependencies as enabled.
    ///
    /// @param temporaryDirectory isolated test directory
    /// @throws IOException if test package creation or state persistence fails
    @Test
    public void doNotEnableDependenciesDeclaredByLegacyPlugin(
            @TempDir Path temporaryDirectory
    ) throws IOException {
        PluginManager manager = new PluginManager(temporaryDirectory.resolve("home"));
        String dependencyId = "dev.hmclce.test.legacy-enable-base";
        String legacyId = "dev.hmclce.test.legacy-enable-dependent";
        writePluginPackage(
                manager.getPluginsDirectory().resolve(dependencyId + ".npl"),
                dependencyId,
                "1.0.0",
                "[]",
                PackagedTestPlugin.class
        );
        writeLegacyPluginPackage(
                manager.getPluginsDirectory().resolve(legacyId + ".npl"),
                legacyId,
                "1.0.0",
                "[{\"id\":\"" + dependencyId + "\",\"version\":\">=1.0.0\"}]",
                LifecycleProbePlugin.class
        );

        assertFalse(manager.enablePlugin(legacyId));

        assertFalse(manager.isPluginEnabled(legacyId));
        assertFalse(manager.isPluginEnabled(dependencyId));
        assertEquals(PluginRuntimeStatus.BLOCKED_LEGACY, manager.getPluginRuntimeStatus(legacyId));
    }

    /// Keeps an incompatible legacy dependent disabled when its declared dependency is disabled.
    ///
    /// @param temporaryDirectory isolated test directory
    /// @throws IOException if test package creation or state persistence fails
    @Test
    public void doNotDisableLegacyDependents(@TempDir Path temporaryDirectory) throws IOException {
        PluginManager manager = new PluginManager(temporaryDirectory.resolve("home"));
        String dependencyId = "dev.hmclce.test.legacy-disable-base";
        String legacyId = "dev.hmclce.test.legacy-disable-dependent";
        writePluginPackage(
                manager.getPluginsDirectory().resolve(dependencyId + ".npl"),
                dependencyId,
                "1.0.0",
                "[]",
                PackagedTestPlugin.class
        );
        writeLegacyPluginPackage(
                manager.getPluginsDirectory().resolve(legacyId + ".npl"),
                legacyId,
                "1.0.0",
                "[{\"id\":\"" + dependencyId + "\",\"version\":\">=1.0.0\"}]",
                LifecycleProbePlugin.class
        );
        assertFalse(manager.enablePlugin(dependencyId));
        assertFalse(manager.enablePlugin(legacyId));

        manager.disablePlugin(dependencyId);

        assertFalse(manager.isPluginEnabled(dependencyId));
        assertFalse(manager.isPluginEnabled(legacyId));
        assertEquals(PluginRuntimeStatus.BLOCKED_LEGACY, manager.getPluginRuntimeStatus(legacyId));
    }

    /// Completes restart-time removal even when a retained legacy package declares the target as a dependency.
    ///
    /// @param temporaryDirectory isolated launcher home and package directory
    /// @throws IOException if package creation, state publication, or restart discovery fails
    @Test
    public void legacyDependentDoesNotBlockPendingUninstall(
            @TempDir Path temporaryDirectory
    ) throws IOException {
        Path localHome = temporaryDirectory.resolve("home");
        PluginManager manager = new PluginManager(localHome);
        String dependencyId = "dev.hmclce.test.legacy-pending-base";
        String legacyId = "dev.hmclce.test.legacy-pending-dependent";
        Path dependencyPackage = manager.getPluginsDirectory().resolve(dependencyId + ".npl");
        Path legacyPackage = manager.getPluginsDirectory().resolve(legacyId + ".npl");
        writePluginPackage(
                dependencyPackage,
                dependencyId,
                "1.0.0",
                "[]",
                PackagedTestPlugin.class
        );
        writeLegacyPluginPackage(
                legacyPackage,
                legacyId,
                "1.0.0",
                "[{\"id\":\"" + dependencyId + "\",\"version\":\">=1.0.0\"}]",
                LifecycleProbePlugin.class
        );
        manager.markForUninstall(dependencyId);
        assertTrue(manager.isMarkedForUninstall(dependencyId));

        PluginManager restarted = new PluginManager(localHome);
        FXThreadTestSupport.runOnFxThread(restarted::discoverPlugins);

        assertFalse(Files.exists(dependencyPackage));
        assertTrue(Files.isRegularFile(legacyPackage));
        assertFalse(restarted.isMarkedForUninstall(dependencyId));
        assertFalse(restarted.getInstalledManifests().containsKey(dependencyId));
        assertTrue(restarted.getInstalledManifests().containsKey(legacyId));
    }

    /// Writes one API-v4 JVM plugin package with a caller-selected dependency array and entry point.
    ///
    /// @param target package path
    /// @param pluginId plugin ID
    /// @param version plugin version
    /// @param dependenciesJson raw dependency array
    /// @param entrypoint package-owned lifecycle class
    /// @throws IOException if package creation fails
    private static void writePluginPackage(
            Path target,
            String pluginId,
            String version,
            String dependenciesJson,
            Class<? extends Plugin> entrypoint
    ) throws IOException {
        Files.createDirectories(Objects.requireNonNull(target.getParent()));
        String manifest = """
                {
                  "schemaVersion": 4,
                  "id": "%s",
                  "name": "Lifecycle State Test",
                  "version": "%s",
                  "type": "java",
                  "entrypoint": "%s",
                  "permissions": [],
                  "requiredPermissions": [],
                  "launcherVersion": "*",
                  "dependencies": %s
                }
                """.formatted(pluginId, version, entrypoint.getName(), dependenciesJson);
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(target))) {
            writeTextEntry(output, "plugin.json", manifest);
            writeClassEntry(output, entrypoint);
        }
    }

    /// Writes one schema-v2 package whose lifecycle must remain blocked before construction.
    ///
    /// @param target package path
    /// @param pluginId plugin ID
    /// @param version plugin version
    /// @param entrypoint package-owned lifecycle class
    /// @throws IOException if package creation fails
    private static void writeLegacyPluginPackage(
            Path target,
            String pluginId,
            String version,
            Class<? extends Plugin> entrypoint
    ) throws IOException {
        writeLegacyPluginPackage(target, pluginId, version, "[]", entrypoint);
    }

    /// Writes one schema-v2 package with caller-provided legacy dependency declarations.
    ///
    /// @param target package path
    /// @param pluginId plugin ID
    /// @param version plugin version
    /// @param dependenciesJson raw dependency array
    /// @param entrypoint package-owned lifecycle class
    /// @throws IOException if package creation fails
    private static void writeLegacyPluginPackage(
            Path target,
            String pluginId,
            String version,
            String dependenciesJson,
            Class<? extends Plugin> entrypoint
    ) throws IOException {
        Files.createDirectories(Objects.requireNonNull(target.getParent()));
        String manifest = """
                {
                  "schemaVersion": 2,
                  "id": "%s",
                  "name": "Legacy Lifecycle State Test",
                  "version": "%s",
                  "type": "java",
                  "entrypoint": "%s",
                  "dependencies": %s
                }
                """.formatted(pluginId, version, entrypoint.getName(), dependenciesJson);
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(target))) {
            writeTextEntry(output, "plugin.json", manifest);
            writeClassEntry(output, entrypoint);
        }
    }

    /// Writes one deterministic UTF-8 text entry into a generated package.
    ///
    /// @param output target archive
    /// @param name entry name
    /// @param value entry contents
    /// @throws IOException if the entry cannot be written
    private static void writeTextEntry(ZipOutputStream output, String name, String value) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(0);
        output.putNextEntry(entry);
        output.write(value.getBytes(StandardCharsets.UTF_8));
        output.closeEntry();
    }

    /// Copies one compiled lifecycle class into a generated plugin package.
    ///
    /// @param output target archive
    /// @param entrypoint lifecycle class whose bytes belong to the package
    /// @throws IOException if compiled class bytes cannot be read or written
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

    /// Clears process-global lifecycle markers used by the legacy-construction assertion.
    private static void clearLifecycleProbeProperties() {
        System.clearProperty(LifecycleProbePlugin.CONSTRUCTED_PROPERTY);
        System.clearProperty(LifecycleProbePlugin.LOADED_PROPERTY);
        System.clearProperty(LifecycleProbePlugin.ENABLED_PROPERTY);
    }

    /// Asserts that the legacy package did not reach construction or either startup callback.
    private static void assertLifecycleProbeNeverRan() {
        assertNull(System.getProperty(LifecycleProbePlugin.CONSTRUCTED_PROPERTY));
        assertNull(System.getProperty(LifecycleProbePlugin.LOADED_PROPERTY));
        assertNull(System.getProperty(LifecycleProbePlugin.ENABLED_PROPERTY));
    }

    /// Lifecycle fixture whose later activation attempts can be failed through a process-global test property.
    @NotNullByDefault
    public static final class ConditionalOnEnablePlugin implements Plugin {
        /// Boolean property that makes `onEnable` fail when set to `true`.
        public static final String FAIL_PROPERTY = "hmcl.test.plugin.conditional-enable.fail";

        /// Manifest received during `onLoad`, or `null` before registration.
        private @Nullable PluginManifest manifest;

        /// Creates the conditional activation fixture.
        public ConditionalOnEnablePlugin() {
        }

        /// Stores the package manifest before activation is attempted.
        ///
        /// @param context plugin runtime context
        @Override
        public void onLoad(PluginContext context) {
            manifest = context.getManifest();
        }

        /// Activates normally unless the test has requested a deterministic failure.
        @Override
        public void onEnable() {
            if (Boolean.getBoolean(FAIL_PROPERTY)) {
                throw new IllegalStateException("Expected conditional onEnable failure");
            }
        }

        /// Deactivates the fixture without additional behavior.
        @Override
        public void onDisable() {
        }

        /// Returns the manifest received during registration.
        ///
        /// @return plugin manifest
        @Override
        public PluginManifest getManifest() {
            return Objects.requireNonNull(manifest);
        }
    }
}
