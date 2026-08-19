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
import org.jackhuang.hmcl.plugin.mixin.bootstrap.HmclMixinBootstrap;
import org.jackhuang.hmcl.plugin.mixin.bootstrap.PluginAgentSnapshotTestSupport;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies safe local `.npl` installation and restart-only update staging.
@EnabledIf("org.jackhuang.hmcl.JavaFXLauncher#isStarted")
@NotNullByDefault
public final class PluginManagerLocalInstallTest {
    /// Inspects a package without mutation and rejects preparation after the source bytes change.
    ///
    /// @param temporaryDirectory isolated test directory
    /// @throws Exception if package inspection or test package creation fails
    @Test
    public void inspectAndVerifyLocalPackage(@TempDir Path temporaryDirectory) throws Exception {
        PluginManager manager = new PluginManager(temporaryDirectory.resolve("home"));
        Path sourcePackage = temporaryDirectory.resolve("inspected.npl");
        writePluginPackage(sourcePackage, "dev.hmclce.test.inspected", "1.0.0");

        LocalPluginInspection inspection = manager.inspectLocalPluginPackage(sourcePackage);

        assertEquals(sourcePackage.toAbsolutePath().normalize(), inspection.getSourcePackage());
        assertEquals("dev.hmclce.test.inspected", inspection.getManifest().getId());
        assertEquals(64, inspection.getSha256().length());
        assertNull(inspection.getOldManifest());

        writePluginPackage(sourcePackage, "dev.hmclce.test.inspected", "2.0.0");
        assertThrows(IOException.class, () -> manager.prepareLocalPluginInstallation(inspection));
    }

    /// Includes the current installed manifest when inspecting a same-ID replacement.
    ///
    /// @param temporaryDirectory isolated test directory
    /// @throws Exception if package inspection or test package creation fails
    @Test
    public void inspectReplacementIncludesOldManifest(@TempDir Path temporaryDirectory) throws Exception {
        PluginManager manager = new PluginManager(temporaryDirectory.resolve("home"));
        String pluginId = "dev.hmclce.test.inspected-update";
        writePluginPackage(manager.getPluginsDirectory().resolve("old.npl"), pluginId, "1.0.0");
        Path replacement = temporaryDirectory.resolve("replacement.npl");
        writePluginPackage(replacement, pluginId, "2.0.0");

        LocalPluginInspection inspection = manager.inspectLocalPluginPackage(replacement);

        assertNotNull(inspection.getOldManifest());
        assertEquals("1.0.0", inspection.getOldManifest().getVersion());
    }

    /// Stages a previously unknown plugin ID without executing it in the current process.
    ///
    /// @param temporaryDirectory isolated test directory
    /// @throws Exception if package preparation or lifecycle registration fails
    @Test
    public void stageNewPluginUntilRestart(@TempDir Path temporaryDirectory) throws Exception {
        Path localHome = temporaryDirectory.resolve("home");
        PluginManager manager = new PluginManager(localHome);
        Path sourcePackage = temporaryDirectory.resolve("new-plugin.npl");
        writePluginPackage(sourcePackage, "dev.hmclce.test.local", "1.0.0");

        LocalPluginInstallation installation =
                manager.prepareLocalPluginInstallation(sourcePackage);

        assertTrue(installation.isRestartRequired());
        assertEquals("dev.hmclce.test.local", installation.getManifest().getId());
        assertThrows(IllegalStateException.class, installation::getPreparedPlugin);
        assertNull(manager.getPlugin("dev.hmclce.test.local"));
        assertEquals(
                PluginRuntimeStatus.WAITING_FOR_RESTART,
                manager.getPluginRuntimeStatus("dev.hmclce.test.local")
        );

        PluginManager restarted = new PluginManager(localHome);
        FXThreadTestSupport.runOnFxThread(restarted::discoverPlugins);
        PluginContainer container = Objects.requireNonNull(restarted.getPlugin("dev.hmclce.test.local"));
        assertTrue(container.isEnabled());
        assertEquals(PluginRuntimeStatus.ENABLED, restarted.getPluginRuntimeStatus("dev.hmclce.test.local"));
    }

    /// Merges desired enablement changes from two stale manager instances under the shared mutation lock.
    ///
    /// @param temporaryDirectory isolated test directory
    /// @throws Exception if package creation, concurrency, or state persistence fails
    @Test
    public void mergeConcurrentEnablementFromTwoManagers(@TempDir Path temporaryDirectory) throws Exception {
        Path localHome = temporaryDirectory.resolve("home");
        String firstId = "dev.hmclce.test.concurrent-state-first";
        String secondId = "dev.hmclce.test.concurrent-state-second";
        PluginManager first = new PluginManager(localHome);
        writePluginPackage(first.getPluginsDirectory().resolve(firstId + ".npl"), firstId, "1.0.0");
        writePluginPackage(first.getPluginsDirectory().resolve(secondId + ".npl"), secondId, "1.0.0");
        PluginManager second = new PluginManager(localHome);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> firstChange = executor.submit(() -> {
                start.await();
                return first.enablePlugin(firstId);
            });
            Future<Boolean> secondChange = executor.submit(() -> {
                start.await();
                return second.enablePlugin(secondId);
            });
            start.countDown();
            assertFalse(firstChange.get(10, TimeUnit.SECONDS));
            assertFalse(secondChange.get(10, TimeUnit.SECONDS));
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }

        PluginManager reloaded = new PluginManager(localHome);
        assertTrue(reloaded.isPluginEnabled(firstId));
        assertTrue(reloaded.isPluginEnabled(secondId));
    }

    /// Rejects installation when the explicit API-v4 Mixin permission is omitted from the grant decision.
    ///
    /// @param temporaryDirectory isolated test directory
    /// @throws Exception if package preparation or lifecycle registration fails
    @Test
    public void blockLifecycleWhenMixinPermissionIsDenied(@TempDir Path temporaryDirectory) throws Exception {
        Path localHome = temporaryDirectory.resolve("home");
        PluginManager manager = new PluginManager(localHome);
        String pluginId = "dev.hmclce.test.mixin-denied";
        Path sourcePackage = temporaryDirectory.resolve("mixin-denied.npl");
        writeMixinPluginPackage(sourcePackage, pluginId, "1.0.0");
        assertThrows(
                IllegalArgumentException.class,
                () -> manager.prepareLocalPluginInstallation(sourcePackage, Set.of())
        );
        assertFalse(Files.exists(manager.getPluginsDirectory().resolve(pluginId + ".npl")));
    }

    /// Allows an optional capability to be denied while retaining the required Mixin grant.
    ///
    /// @param temporaryDirectory isolated test directory
    /// @throws Exception if package creation or permission persistence fails
    @Test
    public void blockLifecycleWhenAnyMixinCapabilityIsDenied(@TempDir Path temporaryDirectory) throws Exception {
        Path localHome = temporaryDirectory.resolve("home");
        PluginManager manager = new PluginManager(localHome);
        String pluginId = "dev.hmclce.test.mixin-partial-denial";
        Path sourcePackage = temporaryDirectory.resolve("mixin-partial-denial.npl");
        writeMixinPluginPackage(
                sourcePackage,
                pluginId,
                "1.0.0",
                "[\"mixin\",\"launcher-ui\"]",
                "[\"mixin\"]",
                true
        );
        clearLifecycleProbeProperties();
        manager.prepareLocalPluginInstallation(sourcePackage, Set.of(PluginPermission.MIXIN));

        PluginManager restarted = new PluginManager(localHome);
        FXThreadTestSupport.runOnFxThread(restarted::discoverPlugins);

        assertNull(restarted.getPlugin(pluginId));
        assertEquals(PluginRuntimeStatus.BLOCKED_AGENT, restarted.getPluginRuntimeStatus(pluginId));
        assertEquals(Set.of(PluginPermission.MIXIN), restarted.getGrantedPermissions(pluginId));
        assertLifecycleProbeNotInvoked();
    }

    /// Allows an optional capability to be granted later without changing an Agent-blocked lifecycle state.
    ///
    /// @param temporaryDirectory isolated test directory
    /// @throws Exception if package creation or permission persistence fails
    @Test
    public void waitForRestartAfterGrantingPreviouslyDeniedMixinPermissions(
            @TempDir Path temporaryDirectory
    ) throws Exception {
        Path localHome = temporaryDirectory.resolve("home");
        PluginManager manager = new PluginManager(localHome);
        String pluginId = "dev.hmclce.test.mixin-regrant";
        Path sourcePackage = temporaryDirectory.resolve("mixin-regrant.npl");
        writeMixinPluginPackage(
                sourcePackage,
                pluginId,
                "1.0.0",
                "[\"mixin\",\"launcher-ui\"]",
                "[\"mixin\"]",
                true
        );
        manager.prepareLocalPluginInstallation(sourcePackage, Set.of(PluginPermission.MIXIN));
        PluginManager restarted = new PluginManager(localHome);
        FXThreadTestSupport.runOnFxThread(restarted::discoverPlugins);
        assertEquals(PluginRuntimeStatus.BLOCKED_AGENT, restarted.getPluginRuntimeStatus(pluginId));

        restarted.setGrantedPermissions(
                pluginId,
                Set.of(PluginPermission.MIXIN, PluginPermission.LAUNCHER_UI)
        );

        assertNull(restarted.getPlugin(pluginId));
        assertEquals(PluginRuntimeStatus.BLOCKED_AGENT, restarted.getPluginRuntimeStatus(pluginId));
        assertEquals(
                Set.of(PluginPermission.MIXIN, PluginPermission.LAUNCHER_UI),
                restarted.getGrantedPermissions(pluginId)
        );
    }

    /// Ignores forged diagnostic properties when no premain Agent snapshot confirms the exact package.
    ///
    /// @param temporaryDirectory isolated test directory
    /// @throws Exception if package creation or permission persistence fails
    @Test
    public void rejectForgedMixinAgentPropertiesBeforeConstruction(@TempDir Path temporaryDirectory) throws Exception {
        Path localHome = temporaryDirectory.resolve("home");
        PluginManager manager = new PluginManager(localHome);
        String pluginId = "dev.hmclce.test.mixin-forged-properties";
        Path sourcePackage = temporaryDirectory.resolve("mixin-forged-properties.npl");
        writeMixinPluginPackage(sourcePackage, pluginId, "1.0.0");
        clearLifecycleProbeProperties();
        manager.prepareLocalPluginInstallation(sourcePackage, Set.of(PluginPermission.MIXIN));

        System.setProperty(HmclMixinBootstrap.ACTIVE_PROPERTY, "true");
        System.setProperty(HmclMixinBootstrap.AGENT_ACTIVE_PROPERTY, "true");
        try {
            PluginManager restarted = new PluginManager(localHome);
            FXThreadTestSupport.runOnFxThread(restarted::discoverPlugins);

            assertNull(restarted.getPlugin(pluginId));
            assertEquals(PluginRuntimeStatus.BLOCKED_AGENT, restarted.getPluginRuntimeStatus(pluginId));
            assertLifecycleProbeNotInvoked();
        } finally {
            System.clearProperty(HmclMixinBootstrap.ACTIVE_PROPERTY);
            System.clearProperty(HmclMixinBootstrap.AGENT_ACTIVE_PROPERTY);
            PluginAgentSnapshotTestSupport.clear();
        }
    }

    /// Rejects a missing Mixin configuration before the Agent-owned lifecycle class is initialized or constructed.
    ///
    /// @param temporaryDirectory isolated test directory
    /// @throws Exception if package creation, hashing, or Agent test registration fails
    @Test
    public void rejectMissingMixinConfigurationBeforeConstruction(@TempDir Path temporaryDirectory) throws Exception {
        Path localHome = temporaryDirectory.resolve("home");
        PluginManager manager = new PluginManager(localHome);
        String pluginId = "dev.hmclce.test.mixin-missing-config";
        String version = "1.0.0";
        String mixinConfig = "mixins." + pluginId + ".json";
        Path sourcePackage = temporaryDirectory.resolve("mixin-missing-config.npl");
        writeMixinPluginPackage(sourcePackage, pluginId, version, "[\"mixin\"]", false);
        clearLifecycleProbeProperties();
        manager.prepareLocalPluginInstallation(sourcePackage, Set.of(PluginPermission.MIXIN));
        Path installedPackage = manager.getPluginsDirectory().resolve(pluginId + ".npl");
        PluginArtifactIdentity identity = new PluginArtifactIdentity(
                pluginId,
                version,
                PluginPackageVersions.calculateSha256(installedPackage)
        );

        PluginAgentSnapshotTestSupport.publish(identity, List.of(mixinConfig), LifecycleProbePlugin.class);
        try {
            PluginManager restarted = new PluginManager(localHome);
            FXThreadTestSupport.runOnFxThread(restarted::discoverPlugins);

            assertNull(restarted.getPlugin(pluginId));
            assertEquals(PluginRuntimeStatus.LOAD_FAILED, restarted.getPluginRuntimeStatus(pluginId));
            assertTrue(Objects.requireNonNull(restarted.getPluginRuntimeDetail(pluginId))
                    .contains("Mixin configuration resource not found"));
            assertLifecycleProbeNotInvoked();
        } finally {
            PluginAgentSnapshotTestSupport.clear();
        }
    }

    /// Keeps lifecycle active when only an optional capability of an active API-v4 Mixin plugin is revoked.
    ///
    /// @param temporaryDirectory isolated test directory
    /// @throws Exception if package creation, Agent registration, or permission persistence fails
    @Test
    public void stopActiveMixinLifecycleAfterPermissionRevocation(@TempDir Path temporaryDirectory) throws Exception {
        Path localHome = temporaryDirectory.resolve("home");
        PluginManager manager = new PluginManager(localHome);
        String pluginId = "dev.hmclce.test.mixin-revocation";
        String version = "1.0.0";
        String mixinConfig = "mixins." + pluginId + ".json";
        Path sourcePackage = temporaryDirectory.resolve("mixin-revocation.npl");
        writeMixinPluginPackage(
                sourcePackage,
                pluginId,
                version,
                "[\"mixin\",\"launcher-ui\"]",
                "[\"mixin\"]",
                true
        );
        clearLifecycleProbeProperties();
        manager.prepareLocalPluginInstallation(
                sourcePackage,
                Set.of(PluginPermission.MIXIN, PluginPermission.LAUNCHER_UI)
        );
        Path installedPackage = manager.getPluginsDirectory().resolve(pluginId + ".npl");
        PluginArtifactIdentity identity = new PluginArtifactIdentity(
                pluginId,
                version,
                PluginPackageVersions.calculateSha256(installedPackage)
        );

        PluginAgentSnapshotTestSupport.publish(identity, List.of(mixinConfig), LifecycleProbePlugin.class);
        try {
            PluginManager restarted = new PluginManager(localHome);
            FXThreadTestSupport.runOnFxThread(restarted::discoverPlugins);
            assertTrue(Objects.requireNonNull(restarted.getPlugin(pluginId)).isEnabled());

            System.setProperty(LifecycleProbePlugin.THROW_DISABLE_PROPERTY, "true");
            System.setProperty(LifecycleProbePlugin.THROW_UNLOAD_PROPERTY, "true");
            restarted.setGrantedPermissions(pluginId, Set.of(PluginPermission.MIXIN));

            assertTrue(Objects.requireNonNull(restarted.getPlugin(pluginId)).isEnabled());
            assertTrue(restarted.isPluginEnabled(pluginId));
            assertEquals(PluginRuntimeStatus.ENABLED, restarted.getPluginRuntimeStatus(pluginId));
            assertNull(System.getProperty(LifecycleProbePlugin.DISABLED_PROPERTY));
            assertNull(System.getProperty(LifecycleProbePlugin.UNLOADED_PROPERTY));
            assertEquals(Set.of(PluginPermission.MIXIN), restarted.getGrantedPermissions(pluginId));
        } finally {
            System.clearProperty(LifecycleProbePlugin.THROW_DISABLE_PROPERTY);
            System.clearProperty(LifecycleProbePlugin.THROW_UNLOAD_PROPERTY);
            PluginAgentSnapshotTestSupport.clear();
        }
    }

    /// Keeps a schema-v2 package manageable while refusing to execute its real packaged entry-point bytes.
    ///
    /// @param temporaryDirectory isolated test directory
    /// @throws Exception if package creation or discovery fails
    @Test
    public void blockLegacyPackageBeforeConstruction(@TempDir Path temporaryDirectory) throws Exception {
        Path localHome = temporaryDirectory.resolve("home");
        PluginManager manager = new PluginManager(localHome);
        String pluginId = "dev.hmclce.test.legacy-lifecycle";
        Path installedPackage = manager.getPluginsDirectory().resolve(pluginId + ".npl");
        writeLegacyPluginPackage(installedPackage, pluginId, "1.0.0", LifecycleProbePlugin.class);
        clearLifecycleProbeProperties();
        assertFalse(manager.enablePlugin(pluginId));
        assertFalse(manager.isPluginEnabled(pluginId));

        FXThreadTestSupport.runOnFxThread(manager::discoverPlugins);

        assertNull(manager.getPlugin(pluginId));
        assertEquals(PluginRuntimeStatus.BLOCKED_LEGACY, manager.getPluginRuntimeStatus(pluginId));
        assertLifecycleProbeNotInvoked();
        assertEquals("1.0.0", manager.getInstalledManifests().get(pluginId).getVersion());
    }

    /// Rejects a schema-v3 package selected for a new local installation.
    ///
    /// @param temporaryDirectory isolated launcher home and source directory
    /// @throws IOException if the legacy fixture cannot be created
    @Test
    public void rejectSchemaThreeLocalInstallation(@TempDir Path temporaryDirectory) throws IOException {
        PluginManager manager = new PluginManager(temporaryDirectory.resolve("home"));
        Path sourcePackage = temporaryDirectory.resolve("legacy-v3-install.npl");
        writeSchemaThreePluginPackage(
                sourcePackage,
                "dev.hmclce.test.legacy-v3-install",
                "1.0.0",
                "[]",
                PackagedTestPlugin.class
        );

        assertThrows(IOException.class, () -> manager.inspectLocalPluginPackage(sourcePackage));
        assertTrue(manager.getInstalledManifests().isEmpty());
    }

    /// Keeps a schema-v3 package manageable so it can be upgraded to API v4 and then uninstalled.
    ///
    /// @param temporaryDirectory isolated launcher home and source directory
    /// @throws Exception if fixture creation, update publication, or uninstallation fails
    @Test
    public void updateAndUninstallSchemaThreeInstalledPackage(@TempDir Path temporaryDirectory) throws Exception {
        PluginManager manager = new PluginManager(temporaryDirectory.resolve("home"));
        String pluginId = "dev.hmclce.test.legacy-v3-manage";
        Path installedPackage = manager.getPluginsDirectory().resolve(pluginId + ".npl");
        writeSchemaThreePluginPackage(
                installedPackage,
                pluginId,
                "1.0.0",
                "[]",
                PackagedTestPlugin.class
        );
        assertEquals(3, Objects.requireNonNull(manager.getInstalledManifests().get(pluginId)).getSchemaVersion());

        Path replacement = temporaryDirectory.resolve("legacy-v3-upgrade.npl");
        writePluginPackage(replacement, pluginId, "2.0.0");
        manager.prepareLocalPluginInstallation(replacement, Set.of());

        PluginManifest updated = Objects.requireNonNull(manager.getInstalledManifests().get(pluginId));
        assertEquals(4, updated.getSchemaVersion());
        assertEquals("2.0.0", updated.getVersion());

        manager.uninstallPlugin(pluginId);

        assertFalse(Files.exists(installedPackage));
        assertFalse(manager.getPublishedPluginManifests().containsKey(pluginId));
    }

    /// Retains a staged package for management when its first startup `onLoad` invocation fails.
    ///
    /// @param temporaryDirectory isolated test directory
    /// @throws Exception if package creation or preparation fails
    @Test
    public void retainNewPluginWhenOnLoadFails(@TempDir Path temporaryDirectory) throws Exception {
        Path localHome = temporaryDirectory.resolve("home");
        PluginManager manager = new PluginManager(localHome);
        String pluginId = "dev.hmclce.test.throwing-load";
        Path sourcePackage = temporaryDirectory.resolve("throwing-load.npl");
        writePluginPackage(
                sourcePackage,
                pluginId,
                "1.0.0",
                "[]",
                "[\"filesystem\"]",
                PackagedThrowingOnLoadPlugin.class,
                "throwing"
        );

        LocalPluginInspection inspection = manager.inspectLocalPluginPackage(sourcePackage);
        manager.prepareLocalPluginInstallation(
                inspection,
                Set.of(PluginPermission.FILESYSTEM)
        );
        Path installedPackage = manager.getPluginsDirectory().resolve(pluginId + ".npl");
        assertTrue(Files.isRegularFile(installedPackage));
        assertEquals(Set.of(PluginPermission.FILESYSTEM), manager.getGrantedPermissions(inspection));

        PluginManager restartedManager = new PluginManager(localHome);
        FXThreadTestSupport.runOnFxThread(restartedManager::discoverPlugins);

        assertTrue(Files.exists(installedPackage));
        assertEquals(Set.of(PluginPermission.FILESYSTEM), manager.getGrantedPermissions(inspection));
        assertNull(manager.getPlugin(pluginId));
        assertNull(restartedManager.getPlugin(pluginId));
        assertEquals(PluginRuntimeStatus.LOAD_FAILED, restartedManager.getPluginRuntimeStatus(pluginId));
        assertNotNull(restartedManager.getPluginRuntimeDetail(pluginId));

        Path replacement = temporaryDirectory.resolve("throwing-load-v2.npl");
        writePluginPackage(replacement, pluginId, "2.0.0");
        restartedManager.prepareLocalPluginInstallation(replacement, Set.of());

        assertEquals(PluginRuntimeStatus.WAITING_FOR_RESTART, restartedManager.getPluginRuntimeStatus(pluginId));
        assertNull(restartedManager.getPluginRuntimeDetail(pluginId));
    }

    /// Does not invoke constructors or lifecycle callbacks while publishing a new package.
    ///
    /// @param temporaryDirectory isolated test directory
    /// @throws Exception if package creation or preparation fails
    @Test
    public void doNotExecuteLifecycleDuringInstallation(@TempDir Path temporaryDirectory) throws Exception {
        Path localHome = temporaryDirectory.resolve("home");
        PluginManager manager = new PluginManager(localHome);
        String pluginId = "dev.hmclce.test.staged-probe";
        Path sourcePackage = temporaryDirectory.resolve("staged-probe.npl");
        writePluginPackage(
                sourcePackage,
                pluginId,
                "1.0.0",
                "[]",
                "[]",
                LifecycleProbePlugin.class,
                "probe"
        );
        System.clearProperty(LifecycleProbePlugin.CONSTRUCTED_PROPERTY);
        System.clearProperty(LifecycleProbePlugin.LOADED_PROPERTY);
        System.clearProperty(LifecycleProbePlugin.ENABLED_PROPERTY);
        LocalPluginInspection inspection = manager.inspectLocalPluginPackage(sourcePackage);
        manager.prepareLocalPluginInstallation(inspection, Set.of());

        assertTrue(Files.exists(manager.getPluginsDirectory().resolve(pluginId + ".npl")));
        assertTrue(manager.getGrantedPermissions(inspection).isEmpty());
        assertNull(manager.getPlugin(pluginId));
        assertNull(System.getProperty(LifecycleProbePlugin.CONSTRUCTED_PROPERTY));
        assertNull(System.getProperty(LifecycleProbePlugin.LOADED_PROPERTY));
        assertNull(System.getProperty(LifecycleProbePlugin.ENABLED_PROPERTY));
    }

    /// Rejects administrative access from a plugin constructor before any container is registered.
    ///
    /// @param temporaryDirectory isolated test directory
    /// @throws Exception if package creation or inspection fails
    @Test
    public void rejectAdministrativeAccessFromPluginConstructor(@TempDir Path temporaryDirectory) throws Exception {
        PluginManager manager = new PluginManager(temporaryDirectory.resolve("home"));
        String pluginId = "dev.hmclce.test.constructor-admin-attack";
        Path sourcePackage = temporaryDirectory.resolve("constructor-admin-attack.npl");
        writePluginPackage(
                sourcePackage,
                pluginId,
                "1.0.0",
                "[]",
                "[]",
                AdministrativeConstructorPlugin.class,
                "constructor-attack",
                false
        );
        LocalPluginInspection inspection = manager.inspectLocalPluginPackage(sourcePackage);

        AdministrativeConstructorPlugin.attackTarget = manager;
        AdministrativeConstructorPlugin.constructed = false;
        try {
            manager.prepareLocalPluginInstallation(inspection, Set.of());
            PluginManager restarted = new PluginManager(temporaryDirectory.resolve("home"));
            FXThreadTestSupport.runOnFxThread(restarted::discoverPlugins);
            assertNull(restarted.getPlugin(pluginId));
            assertEquals(PluginRuntimeStatus.LOAD_FAILED, restarted.getPluginRuntimeStatus(pluginId));
            assertFalse(AdministrativeConstructorPlugin.constructed);
        } finally {
            AdministrativeConstructorPlugin.attackTarget = null;
        }

        assertTrue(Files.exists(manager.getPluginsDirectory().resolve(pluginId + ".npl")));
        assertNull(manager.getPlugin(pluginId));
    }

    /// Rejects direct administrative calls whose stack contains a child plugin class loader.
    ///
    /// @param temporaryDirectory isolated test directory
    /// @throws Exception if test package creation or inspection fails
    @Test
    public void rejectAdministrativeCallsFromPluginClassLoader(@TempDir Path temporaryDirectory) throws Exception {
        PluginManager manager = new PluginManager(temporaryDirectory.resolve("home"));
        String installedId = "dev.hmclce.test.direct-admin-attack";
        Path installedPackage = manager.getPluginsDirectory().resolve(installedId + ".npl");
        writePluginPackage(
                installedPackage,
                installedId,
                "1.0.0",
                "[]",
                "[\"filesystem\"]",
                PackagedTestPlugin.class,
                "installed"
        );
        Path newPackage = temporaryDirectory.resolve("unauthorized-install.npl");
        writePluginPackage(newPackage, "dev.hmclce.test.unauthorized-install", "1.0.0");
        LocalPluginInspection inspection = manager.inspectLocalPluginPackage(newPackage);

        assertThrows(
                SecurityException.class,
                PluginAdministrativeTestSupport.pluginCaller(() -> manager.setGrantedPermissions(
                        installedId,
                        Set.of(PluginPermission.FILESYSTEM)
                ))::run
        );
        assertThrows(
                SecurityException.class,
                PluginAdministrativeTestSupport.pluginCaller(
                        () -> manager.prepareLocalPluginInstallation(inspection, Set.of())
                )::run
        );
        assertThrows(
                SecurityException.class,
                PluginAdministrativeTestSupport.pluginCaller(() -> manager.uninstallPlugin(installedId))::run
        );
        assertThrows(
                SecurityException.class,
                PluginAdministrativeTestSupport.pluginCaller(manager::getPluginsDirectory)::run
        );

        assertTrue(manager.getGrantedPermissions(installedId).isEmpty());
        assertTrue(Files.isRegularFile(installedPackage));
        assertFalse(Files.exists(manager.getPluginsDirectory().resolve(
                inspection.getManifest().getId() + ".npl"
        )));
    }

    /// Rejects a detached ordinary URL loader that calls the manager from a new thread without reflection frames.
    ///
    /// @param temporaryDirectory isolated test directory
    /// @throws Exception if package creation, detached loading, or thread coordination fails
    @Test
    public void rejectDetachedUrlClassLoaderOnNewThread(@TempDir Path temporaryDirectory) throws Exception {
        PluginManager manager = new PluginManager(temporaryDirectory.resolve("home"));
        String pluginId = "dev.hmclce.test.detached-loader-attack";
        Path installedPackage = manager.getPluginsDirectory().resolve(pluginId + ".npl");
        writePluginPackage(
                installedPackage,
                pluginId,
                "1.0.0",
                "[]",
                "[\"filesystem\"]",
                PackagedTestPlugin.class,
                "installed"
        );

        URL testClasses = Objects.requireNonNull(
                PluginManagerLocalInstallTest.class.getProtectionDomain().getCodeSource()
        ).getLocation();
        URL @Unmodifiable [] classPath = new URL[]{testClasses};
        AtomicReference<@Nullable Throwable> failure = new AtomicReference<>();
        try (URLClassLoader detachedLoader = PluginAdministrativeTestSupport.newTargetFirstUrlClassLoader(
                classPath,
                PluginManager.class.getClassLoader(),
                DetachedPluginAdministrativeCaller.class.getName()
        )) {
            Class<?> callerClass = Class.forName(
                    DetachedPluginAdministrativeCaller.class.getName(),
                    true,
                    detachedLoader
            );
            assertSame(detachedLoader, callerClass.getClassLoader());
            Runnable attack = (Runnable) callerClass.getConstructor(
                    PluginManager.class,
                    String.class
            ).newInstance(manager, pluginId);
            Thread detachedThread = new Thread(() -> {
                try {
                    attack.run();
                } catch (Throwable exception) {
                    failure.set(exception);
                }
            }, "detached-plugin-admin-attack");
            detachedThread.start();
            detachedThread.join();
        }

        assertInstanceOf(SecurityException.class, Objects.requireNonNull(failure.get()));
        assertTrue(manager.getGrantedPermissions(pluginId).isEmpty());
    }

    /// Keeps lifecycle implementations, permission contexts, and writable state properties manager-internal.
    ///
    /// @throws NoSuchMethodException if a checked member is ucepectedly absent
    @Test
    public void keepPluginContainerAuthorityPackagePrivate() throws NoSuchMethodException {
        assertFalse(Modifier.isPublic(PluginContainer.class.getDeclaredConstructor(
                Plugin.class,
                PluginContext.class,
                Path.class
        ).getModifiers()));
        assertFalse(Modifier.isPublic(PluginContainer.class.getDeclaredMethod("getPlugin").getModifiers()));
        assertFalse(Modifier.isPublic(PluginContainer.class.getDeclaredMethod("getContext").getModifiers()));
        assertFalse(Modifier.isPublic(PluginContainer.class.getDeclaredMethod(
                "setEnabled",
                boolean.class
        ).getModifiers()));
        assertFalse(Modifier.isPublic(PluginContainer.class.getDeclaredMethod(
                "setRestartRequired",
                boolean.class
        ).getModifiers()));
        assertFalse(Modifier.isPublic(PluginContainer.class.getDeclaredMethod("enabledProperty").getModifiers()));
        assertFalse(Modifier.isPublic(PluginContainer.class.getDeclaredMethod(
                "restartRequiredProperty"
        ).getModifiers()));
        assertFalse(Modifier.isPublic(PluginContainer.class.getDeclaredMethod("closeClassLoader").getModifiers()));
    }

    /// Stages a same-ID replacement without runtime registration and cancels pending uninstall state.
    ///
    /// @param temporaryDirectory isolated test directory
    /// @throws Exception if package staging or inspection fails
    @Test
    public void stageExistingPluginForRestart(@TempDir Path temporaryDirectory) throws Exception {
        PluginManager manager = new PluginManager(temporaryDirectory.resolve("home"));
        String pluginId = "dev.hmclce.test.update";
        Path oldPackage = manager.getPluginsDirectory().resolve("legacy-name.npl");
        Path replacementPackage = temporaryDirectory.resolve("legacy-name.npl");
        writePluginPackage(oldPackage, pluginId, "1.0.0");
        writePluginPackage(replacementPackage, pluginId, "2.0.0");
        manager.markForUninstall(pluginId);

        LocalPluginInstallation installation =
                manager.prepareLocalPluginInstallation(replacementPackage);

        assertTrue(installation.isRestartRequired());
        assertFalse(manager.isMarkedForUninstall(pluginId));
        assertTrue(manager.getPlugins().isEmpty());
        assertFalse(Files.exists(oldPackage));

        try (Stream<Path> files = Files.list(manager.getPluginsDirectory())) {
            Path installedPackage = files
                    .filter(path -> path.getFileName().toString().endsWith(".npl"))
                    .findFirst()
                    .orElseThrow();
            assertEquals("2.0.0", readManifest(installedPackage).getVersion());
        }
    }

    /// Keeps an already loaded same-ID plugin registered exactly once while replacing its package for restart.
    ///
    /// @param temporaryDirectory isolated test directory
    /// @throws Exception if initial preparation, registration, or update staging fails
    @Test
    public void stageLoadedPluginWithoutDuplicateRegistration(@TempDir Path temporaryDirectory) throws Exception {
        Path localHome = temporaryDirectory.resolve("home");
        PluginManager manager = new PluginManager(localHome);
        String pluginId = "dev.hmclce.test.loaded-update";
        Path initialPackage = temporaryDirectory.resolve("initial.npl");
        Path replacementPackage = temporaryDirectory.resolve("replacement.npl");
        writePluginPackage(initialPackage, pluginId, "1.0.0");
        writePluginPackage(replacementPackage, pluginId, "2.0.0");

        manager.prepareLocalPluginInstallation(initialPackage);
        PluginManager activeManager = new PluginManager(localHome);
        FXThreadTestSupport.runOnFxThread(activeManager::discoverPlugins);
        PluginContainer originalContainer = Objects.requireNonNull(activeManager.getPlugin(pluginId));

        LocalPluginInstallation updateInstallation =
                activeManager.prepareLocalPluginInstallation(replacementPackage);

        assertTrue(updateInstallation.isRestartRequired());
        assertEquals(1, activeManager.getPlugins().size());
        assertSame(originalContainer, activeManager.getPlugin(pluginId));
        assertTrue(originalContainer.isRestartRequired());
        assertEquals("1.0.0", originalContainer.getManifest().getVersion());
        assertEquals("2.0.0", readManifest(originalContainer.getNplFile()).getVersion());
    }

    /// Rejects paths that are not readable regular `.npl` files before installation work begins.
    ///
    /// @param temporaryDirectory isolated test directory
    @Test
    public void rejectInvalidLocalPackagePath(@TempDir Path temporaryDirectory) {
        PluginManager manager = new PluginManager(temporaryDirectory.resolve("home"));

        assertThrows(
                IOException.class,
                () -> manager.prepareLocalPluginInstallation(temporaryDirectory.resolve("missing.npl"))
        );
        assertThrows(
                IOException.class,
                () -> manager.prepareLocalPluginInstallation(temporaryDirectory.resolve("wrong.zip"))
        );
    }

    /// Skips a plugin whose required dependency is not installed.
    ///
    /// @param temporaryDirectory isolated test directory
    /// @throws IOException if test package creation fails
    @Test
    public void rejectMissingDependencyDuringDiscovery(@TempDir Path temporaryDirectory) throws IOException {
        PluginManager manager = new PluginManager(temporaryDirectory.resolve("home"));
        writePluginPackage(
                manager.getPluginsDirectory().resolve("dependent.npl"),
                "dev.hmclce.test.missing-dependent",
                "1.0.0",
                "[{\"id\": \"dev.hmclce.test.missing-base\", \"version\": \">=1.0.0\"}]"
        );
        manager.enablePlugin("dev.hmclce.test.missing-dependent");

        FXThreadTestSupport.runOnFxThread(manager::discoverPlugins);

        assertTrue(manager.getPlugins().isEmpty());
    }

    /// Prevents a dependent's constructor and lifecycle callbacks when its dependency cannot activate.
    ///
    /// @param temporaryDirectory isolated test directory
    /// @throws IOException if test package creation fails
    @Test
    public void dependencyEnableFailureBlocksDependentBeforeConstruction(
            @TempDir Path temporaryDirectory
    ) throws IOException {
        clearLifecycleProbeProperties();
        PluginManager manager = new PluginManager(temporaryDirectory.resolve("home"));
        String dependencyId = "dev.hmclce.test.enable-failure-base";
        String dependentId = "dev.hmclce.test.enable-failure-dependent";
        writePluginPackage(
                manager.getPluginsDirectory().resolve(dependencyId + ".npl"),
                dependencyId,
                "1.0.0",
                "[]",
                PackagedThrowingOnEnablePlugin.class
        );
        writePluginPackage(
                manager.getPluginsDirectory().resolve(dependentId + ".npl"),
                dependentId,
                "1.0.0",
                "[{\"id\":\"" + dependencyId + "\",\"version\":\">=1.0.0\"}]",
                LifecycleProbePlugin.class
        );
        manager.enablePlugin(dependencyId);
        manager.enablePlugin(dependentId);

        FXThreadTestSupport.runOnFxThread(manager::discoverPlugins);

        assertEquals(PluginRuntimeStatus.LOAD_FAILED, manager.getPluginRuntimeStatus(dependencyId));
        assertEquals(PluginRuntimeStatus.LOAD_FAILED, manager.getPluginRuntimeStatus(dependentId));
        assertNull(manager.getPlugin(dependentId));
        assertLifecycleProbeNeverRan();

        FXThreadTestSupport.runOnFxThread(manager::discoverPlugins);

        assertEquals(PluginRuntimeStatus.LOAD_FAILED, manager.getPluginRuntimeStatus(dependencyId));
        assertEquals(PluginRuntimeStatus.LOAD_FAILED, manager.getPluginRuntimeStatus(dependentId));
        assertNull(manager.getPlugin(dependentId));
        assertLifecycleProbeNeverRan();
        clearLifecycleProbeProperties();
    }

    /// Continues discovery after one plugin throws an [Error] during `onLoad`.
    ///
    /// @param temporaryDirectory isolated test directory
    /// @throws IOException if test package creation fails
    @Test
    public void isolateOnLoadErrorFromLaterPlugins(@TempDir Path temporaryDirectory) throws IOException {
        PluginManager manager = new PluginManager(temporaryDirectory.resolve("home"));
        String failingId = "dev.hmclce.test.onload-error";
        String validId = "dev.hmclce.test.after-onload-error";
        writePluginPackage(
                manager.getPluginsDirectory().resolve("a-failing.npl"),
                failingId,
                "1.0.0",
                "[]",
                PackagedThrowingOnLoadErrorPlugin.class
        );
        writePluginPackage(
                manager.getPluginsDirectory().resolve("b-valid.npl"),
                validId,
                "1.0.0"
        );
        manager.enablePlugin(failingId);
        manager.enablePlugin(validId);

        FXThreadTestSupport.runOnFxThread(manager::discoverPlugins);

        assertEquals(PluginRuntimeStatus.LOAD_FAILED, manager.getPluginRuntimeStatus(failingId));
        assertNull(manager.getPlugin(failingId));
        assertNotNull(
                manager.getPlugin(validId),
                () -> "Later plugin status=" + manager.getPluginRuntimeStatus(validId)
                        + ", detail=" + manager.getPluginRuntimeDetail(validId)
        );
        assertEquals(PluginRuntimeStatus.ENABLED, manager.getPluginRuntimeStatus(validId));
    }

    /// Continues discovery when stale permission-record pruning cannot be persisted.
    ///
    /// @param temporaryDirectory isolated test directory
    /// @throws Exception if package creation or fault setup fails
    @Test
    public void continueDiscoveryWhenPermissionPruneFails(@TempDir Path temporaryDirectory) throws Exception {
        Path localHome = temporaryDirectory.resolve("home");
        PluginManager manager = new PluginManager(localHome);
        String staleId = "dev.hmclce.test.stale-permission";
        Path stalePackage = manager.getPluginsDirectory().resolve(staleId + ".npl");
        writePluginPackage(
                stalePackage,
                staleId,
                "1.0.0",
                "[]",
                "[\"filesystem\"]",
                PackagedTestPlugin.class,
                "stale"
        );
        manager.setGrantedPermissions(staleId, Set.of(PluginPermission.FILESYSTEM));
        Files.delete(stalePackage);

        String validId = "dev.hmclce.test.prune-failure-valid";
        writePluginPackage(
                manager.getPluginsDirectory().resolve(validId + ".npl"),
                validId,
                "1.0.0"
        );
        manager.enablePlugin(validId);
        Path permissionFile = localHome.resolve("plugin-permissions.json");
        Files.delete(permissionFile);
        Files.createDirectory(permissionFile);

        FXThreadTestSupport.runOnFxThread(manager::discoverPlugins);

        assertNotNull(manager.getPlugin(validId));
        assertNull(manager.getPlugin(staleId));
    }

    /// Loads a compatible base plugin but skips a dependent whose version constraint is not satisfied.
    ///
    /// @param temporaryDirectory isolated test directory
    /// @throws IOException if test package creation fails
    @Test
    public void rejectIncompatibleDependencyDuringDiscovery(@TempDir Path temporaryDirectory) throws IOException {
        PluginManager manager = new PluginManager(temporaryDirectory.resolve("home"));
        writePluginPackage(
                manager.getPluginsDirectory().resolve("base.npl"),
                "dev.hmclce.test.version-base",
                "1.5.0"
        );
        writePluginPackage(
                manager.getPluginsDirectory().resolve("dependent.npl"),
                "dev.hmclce.test.version-dependent",
                "1.0.0",
                "[{\"id\": \"dev.hmclce.test.version-base\", \"version\": \">=2.0.0\"}]"
        );
        manager.enablePlugin("dev.hmclce.test.version-base");
        manager.enablePlugin("dev.hmclce.test.version-dependent");

        FXThreadTestSupport.runOnFxThread(manager::discoverPlugins);

        assertEquals(1, manager.getPlugins().size());
        assertNotNull(manager.getPlugin("dev.hmclce.test.version-base"));
        assertNull(manager.getPlugin("dev.hmclce.test.version-dependent"));
    }

    /// Rejects every member of an installed dependency cycle.
    ///
    /// @param temporaryDirectory isolated test directory
    /// @throws IOException if test package creation fails
    @Test
    public void rejectDependencyCycleDuringDiscovery(@TempDir Path temporaryDirectory) throws IOException {
        PluginManager manager = new PluginManager(temporaryDirectory.resolve("home"));
        writePluginPackage(
                manager.getPluginsDirectory().resolve("a.npl"),
                "dev.hmclce.test.cycle-a",
                "1.0.0",
                "[\"dev.hmclce.test.cycle-b\"]"
        );
        writePluginPackage(
                manager.getPluginsDirectory().resolve("b.npl"),
                "dev.hmclce.test.cycle-b",
                "1.0.0",
                "[\"dev.hmclce.test.cycle-a\"]"
        );
        manager.enablePlugin("dev.hmclce.test.cycle-a");
        manager.enablePlugin("dev.hmclce.test.cycle-b");

        FXThreadTestSupport.runOnFxThread(manager::discoverPlugins);

        assertTrue(manager.getPlugins().isEmpty());
    }

    /// Publishes a dependency update and its new dependent together without validating against an intermediate graph.
    ///
    /// @param temporaryDirectory isolated test directory
    /// @throws Exception if package creation, inspection, staging, or restart discovery fails
    @Test
    public void stageDependencyUpdateAndDependentAsOneBatch(@TempDir Path temporaryDirectory) throws Exception {
        Path localHome = temporaryDirectory.resolve("home");
        PluginManager manager = new PluginManager(localHome);
        String baseId = "dev.hmclce.test.batch-base";
        String dependentId = "dev.hmclce.test.batch-dependent";
        writePluginPackage(manager.getPluginsDirectory().resolve(baseId + ".npl"), baseId, "1.0.0");
        manager.enablePlugin(baseId);
        FXThreadTestSupport.runOnFxThread(manager::discoverPlugins);
        assertNotNull(manager.getPlugin(baseId));

        Path baseReplacement = temporaryDirectory.resolve("base-replacement.npl");
        Path dependentPackage = temporaryDirectory.resolve("dependent-install.npl");
        writePluginPackage(baseReplacement, baseId, "2.0.0");
        writePluginPackage(
                dependentPackage,
                dependentId,
                "1.0.0",
                "[{\"id\":\"" + baseId + "\",\"version\":\">=2.0.0\"}]"
        );

        manager.stagePluginInstallations(List.of(
                manager.inspectLocalPluginPackage(baseReplacement),
                manager.inspectLocalPluginPackage(dependentPackage)
        ));

        PluginContainer activeBase = Objects.requireNonNull(manager.getPlugin(baseId));
        assertEquals("1.0.0", activeBase.getManifest().getVersion());
        assertEquals("2.0.0", readManifest(activeBase.getNplFile()).getVersion());
        assertTrue(activeBase.isRestartRequired());
        assertEquals(
                "1.0.0",
                readManifest(manager.getPluginsDirectory().resolve(dependentId + ".npl")).getVersion()
        );

        PluginManager restartedManager = new PluginManager(localHome);
        FXThreadTestSupport.runOnFxThread(restartedManager::discoverPlugins);
        assertEquals("2.0.0", Objects.requireNonNull(restartedManager.getPlugin(baseId)).getManifest().getVersion());
        assertNotNull(restartedManager.getPlugin(dependentId));
    }

    /// Ignores a legacy package's reverse dependency while updating and uninstalling an API-v4 plugin.
    ///
    /// @param temporaryDirectory isolated launcher home and source directory
    /// @throws Exception if fixture creation, replacement publication, or uninstallation fails
    @Test
    public void legacyReverseDependencyDoesNotBlockV4Mutation(@TempDir Path temporaryDirectory) throws Exception {
        PluginManager manager = new PluginManager(temporaryDirectory.resolve("home"));
        String baseId = "dev.hmclce.test.legacy-reverse-base";
        String legacyId = "dev.hmclce.test.legacy-reverse-consumer";
        Path basePackage = manager.getPluginsDirectory().resolve(baseId + ".npl");
        writePluginPackage(basePackage, baseId, "1.0.0");
        writeSchemaThreePluginPackage(
                manager.getPluginsDirectory().resolve(legacyId + ".npl"),
                legacyId,
                "1.0.0",
                "[{\"id\":\"" + baseId + "\",\"version\":\"<2.0.0\"}]",
                PackagedTestPlugin.class
        );

        Path replacement = temporaryDirectory.resolve("legacy-reverse-base-v2.npl");
        writePluginPackage(replacement, baseId, "2.0.0");
        manager.prepareLocalPluginInstallation(replacement, Set.of());

        assertEquals(
                "2.0.0",
                Objects.requireNonNull(manager.getInstalledManifests().get(baseId)).getVersion()
        );
        manager.uninstallPlugin(baseId);
        assertFalse(Files.exists(basePackage));
        assertTrue(manager.getPublishedPluginManifests().containsKey(legacyId));
    }

    /// Rejects a v4 installation whose dependency is available only as a legacy non-executable package.
    ///
    /// @param temporaryDirectory isolated launcher home and source directory
    /// @throws Exception if fixture creation or package inspection fails
    @Test
    public void rejectLegacyPackageAsV4Dependency(@TempDir Path temporaryDirectory) throws Exception {
        PluginManager manager = new PluginManager(temporaryDirectory.resolve("home"));
        String legacyId = "dev.hmclce.test.legacy-dependency";
        String rootId = "dev.hmclce.test.legacy-dependent-root";
        writeSchemaThreePluginPackage(
                manager.getPluginsDirectory().resolve(legacyId + ".npl"),
                legacyId,
                "1.0.0",
                "[]",
                PackagedTestPlugin.class
        );
        Path rootPackage = temporaryDirectory.resolve("legacy-dependent-root.npl");
        writePluginPackage(
                rootPackage,
                rootId,
                "1.0.0",
                "[{\"id\":\"" + legacyId + "\",\"version\":\"1.0.0\"}]"
        );
        LocalPluginInspection inspection = manager.inspectLocalPluginPackage(rootPackage);

        assertThrows(
                IOException.class,
                () -> manager.prepareLocalPluginInstallation(inspection, Set.of())
        );
        assertFalse(Files.exists(manager.getPluginsDirectory().resolve(rootId + ".npl")));
    }

    /// Restores every previous package when a later target in a batch cannot be published.
    ///
    /// @param temporaryDirectory isolated test directory
    /// @throws Exception if package creation or inspection fails
    @Test
    public void rollbackBatchWhenLaterPublicationFails(@TempDir Path temporaryDirectory) throws Exception {
        PluginManager manager = new PluginManager(temporaryDirectory.resolve("home"));
        String baseId = "dev.hmclce.test.rollback-base";
        String dependentId = "dev.hmclce.test.rollback-dependent";
        Path installedBase = manager.getPluginsDirectory().resolve(baseId + ".npl");
        writePluginPackage(installedBase, baseId, "1.0.0");

        Path baseReplacement = temporaryDirectory.resolve("rollback-base.npl");
        Path dependentPackage = temporaryDirectory.resolve("rollback-dependent.npl");
        writePluginPackage(baseReplacement, baseId, "2.0.0");
        writePluginPackage(
                dependentPackage,
                dependentId,
                "1.0.0",
                "[{\"id\":\"" + baseId + "\",\"version\":\">=2.0.0\"}]"
        );
        Path blockedTarget = manager.getPluginsDirectory().resolve(dependentId + ".npl");
        Files.createDirectory(blockedTarget);

        assertThrows(IOException.class, () -> manager.stagePluginInstallations(List.of(
                manager.inspectLocalPluginPackage(baseReplacement),
                manager.inspectLocalPluginPackage(dependentPackage)
        )));

        assertEquals("1.0.0", readManifest(installedBase).getVersion());
        assertTrue(Files.isDirectory(blockedTarget));
        try (Stream<Path> files = Files.list(manager.getPluginsDirectory())) {
            assertEquals(1, files.filter(Files::isRegularFile).count());
        }
    }

    /// Excludes packages scheduled for removal from the dependency graph planned for the same restart.
    ///
    /// @param temporaryDirectory isolated test directory
    /// @throws Exception if package creation or installed-manifest enumeration fails
    @Test
    public void excludePendingUninstallFromInstallPlanning(@TempDir Path temporaryDirectory) throws Exception {
        PluginManager manager = new PluginManager(temporaryDirectory.resolve("home"));
        String baseId = "dev.hmclce.test.pending-base";
        String consumerId = "dev.hmclce.test.pending-consumer";
        writePluginPackage(manager.getPluginsDirectory().resolve(baseId + ".npl"), baseId, "1.0.0");
        writePluginPackage(
                manager.getPluginsDirectory().resolve(consumerId + ".npl"),
                consumerId,
                "1.0.0",
                "[{\"id\":\"" + baseId + "\",\"version\":\"<2.0.0\"}]"
        );

        manager.markForUninstall(consumerId);

        assertTrue(manager.getInstalledManifests().containsKey(baseId));
        assertFalse(manager.getInstalledManifests().containsKey(consumerId));
        assertTrue(manager.getPublishedPluginManifests().containsKey(consumerId));
        assertEquals(PluginRuntimeStatus.PENDING_UNINSTALL, manager.getPluginRuntimeStatus(consumerId));
    }

    /// Rejects an explicit grant that the package developer did not request.
    ///
    /// @param temporaryDirectory isolated test directory
    /// @throws Exception if package creation or inspection fails
    @Test
    public void rejectUndeclaredExplicitPermission(@TempDir Path temporaryDirectory) throws Exception {
        PluginManager manager = new PluginManager(temporaryDirectory.resolve("home"));
        Path sourcePackage = temporaryDirectory.resolve("undeclared-permission.npl");
        writePluginPackage(
                sourcePackage,
                "dev.hmclce.test.undeclared-permission",
                "1.0.0",
                "[]",
                "[\"filesystem\"]",
                PackagedTestPlugin.class,
                "undeclared"
        );
        LocalPluginInspection inspection = manager.inspectLocalPluginPackage(sourcePackage);

        assertThrows(
                IllegalArgumentException.class,
                () -> manager.prepareLocalPluginInstallation(inspection, Set.of(PluginPermission.NETWORK))
        );
        assertTrue(manager.getGrantedPermissions(inspection).isEmpty());
    }

    /// Carries current grants into every update prompt, including a same-version package digest change.
    ///
    /// @param temporaryDirectory isolated test directory
    /// @throws Exception if package creation, hashing, or permission persistence fails
    @Test
    public void suggestPermissionsByVersionAndArtifact(@TempDir Path temporaryDirectory) throws Exception {
        PluginManager manager = new PluginManager(temporaryDirectory.resolve("home"));
        String pluginId = "dev.hmclce.test.permission-update";
        Path installedPackage = manager.getPluginsDirectory().resolve(pluginId + ".npl");
        writePluginPackage(
                installedPackage,
                pluginId,
                "1.0.0",
                "[]",
                "[\"filesystem\"]",
                PackagedTestPlugin.class,
                "original"
        );
        manager.setGrantedPermissions(pluginId, Set.of(PluginPermission.FILESYSTEM));

        Path versionUpdate = temporaryDirectory.resolve("version-update.npl");
        writePluginPackage(
                versionUpdate,
                pluginId,
                "2.0.0",
                "[]",
                "[\"filesystem\",\"network\"]",
                PackagedTestPlugin.class,
                "version-update"
        );
        LocalPluginInspection versionInspection = manager.inspectLocalPluginPackage(versionUpdate);
        assertEquals(
                Set.of(PluginPermission.FILESYSTEM),
                manager.getSuggestedGrantedPermissions(versionInspection)
        );

        Path repackedVersion = temporaryDirectory.resolve("repacked-version.npl");
        writePluginPackage(
                repackedVersion,
                pluginId,
                "1.0.0",
                "[]",
                "[\"filesystem\"]",
                PackagedTestPlugin.class,
                "repacked"
        );
        LocalPluginInspection repackedInspection = manager.inspectLocalPluginPackage(repackedVersion);
        assertEquals(
                Set.of(PluginPermission.FILESYSTEM),
                manager.getSuggestedGrantedPermissions(repackedInspection)
        );
    }

    /// Uses the latest staged artifact rather than older loaded code when preselecting a chained update.
    ///
    /// @param temporaryDirectory isolated test directory
    /// @throws Exception if package creation, loading, staging, or permission persistence fails
    @Test
    public void suggestFromLatestStagedArtifactAcrossUpdates(@TempDir Path temporaryDirectory) throws Exception {
        PluginManager manager = new PluginManager(temporaryDirectory.resolve("home"));
        String pluginId = "dev.hmclce.test.chained-staged-update";
        Path installedPackage = manager.getPluginsDirectory().resolve(pluginId + ".npl");
        writePluginPackage(
                installedPackage,
                pluginId,
                "1.0.0",
                "[]",
                "[\"filesystem\",\"network\"]",
                PackagedTestPlugin.class,
                "v1"
        );
        manager.setGrantedPermissions(
                pluginId,
                Set.of(PluginPermission.FILESYSTEM, PluginPermission.NETWORK)
        );
        manager.enablePlugin(pluginId);
        FXThreadTestSupport.runOnFxThread(manager::discoverPlugins);
        assertEquals("1.0.0", Objects.requireNonNull(manager.getPlugin(pluginId)).getManifest().getVersion());

        Path versionTwo = temporaryDirectory.resolve("chained-v2.npl");
        writePluginPackage(
                versionTwo,
                pluginId,
                "2.0.0",
                "[]",
                "[\"filesystem\",\"network\"]",
                PackagedTestPlugin.class,
                "v2"
        );
        LocalPluginInspection versionTwoInspection = manager.inspectLocalPluginPackage(versionTwo);
        manager.prepareLocalPluginInstallation(versionTwoInspection, Set.of(PluginPermission.FILESYSTEM));

        assertEquals(Set.of(PluginPermission.FILESYSTEM), manager.getGrantedPermissions(pluginId));
        assertEquals("2.0.0", manager.getInstalledManifests().get(pluginId).getVersion());

        Path versionThree = temporaryDirectory.resolve("chained-v3.npl");
        writePluginPackage(
                versionThree,
                pluginId,
                "3.0.0",
                "[]",
                "[\"filesystem\",\"network\"]",
                PackagedTestPlugin.class,
                "v3"
        );
        LocalPluginInspection versionThreeInspection = manager.inspectLocalPluginPackage(versionThree);

        assertEquals(
                Set.of(PluginPermission.FILESYSTEM),
                manager.getSuggestedGrantedPermissions(versionThreeInspection)
        );
    }

    /// Manages a pending artifact while atomically synchronizing compatible permissions to older loaded code.
    ///
    /// @param temporaryDirectory isolated test directory
    /// @throws Exception if package creation, loading, staging, or permission persistence fails
    @Test
    public void managePendingAndLoadedArtifactPermissionsTogether(@TempDir Path temporaryDirectory) throws Exception {
        Path localHome = temporaryDirectory.resolve("home");
        PluginManager manager = new PluginManager(localHome);
        String pluginId = "dev.hmclce.test.pending-permission-management";
        Path installedPackage = manager.getPluginsDirectory().resolve(pluginId + ".npl");
        writePluginPackage(
                installedPackage,
                pluginId,
                "1.0.0",
                "[]",
                "[\"filesystem\",\"network\"]",
                PackagedTestPlugin.class,
                "v1"
        );
        manager.setGrantedPermissions(
                pluginId,
                Set.of(PluginPermission.FILESYSTEM, PluginPermission.NETWORK)
        );
        manager.enablePlugin(pluginId);
        FXThreadTestSupport.runOnFxThread(manager::discoverPlugins);
        PluginContainer loaded = Objects.requireNonNull(manager.getPlugin(pluginId));

        Path replacement = temporaryDirectory.resolve("pending-v2.npl");
        writePluginPackage(
                replacement,
                pluginId,
                "2.0.0",
                "[]",
                "[\"filesystem\",\"process\"]",
                PackagedTestPlugin.class,
                "v2"
        );
        LocalPluginInspection inspection = manager.inspectLocalPluginPackage(replacement);
        manager.prepareLocalPluginInstallation(
                inspection,
                Set.of(PluginPermission.FILESYSTEM, PluginPermission.PROCESS)
        );

        assertEquals("2.0.0", manager.getInstalledManifests().get(pluginId).getVersion());
        assertEquals(
                Set.of(PluginPermission.FILESYSTEM, PluginPermission.PROCESS),
                manager.getGrantedPermissions(pluginId)
        );

        manager.setGrantedPermissions(pluginId, Set.of(PluginPermission.PROCESS));

        assertEquals(Set.of(PluginPermission.PROCESS), manager.getGrantedPermissions(pluginId));
        assertTrue(loaded.getContext().getGrantedPermissions().isEmpty());

        PluginManager restarted = new PluginManager(localHome);
        assertEquals(Set.of(PluginPermission.PROCESS), restarted.getGrantedPermissions(pluginId));
    }

    /// Ignores a target artifact's historical grants and derives update defaults from the installed artifact only.
    ///
    /// @param temporaryDirectory isolated test directory
    /// @throws Exception if package creation, hashing, or permission persistence fails
    @Test
    public void ignoreHistoricalTargetArtifactGrants(@TempDir Path temporaryDirectory) throws Exception {
        Path localHome = temporaryDirectory.resolve("home");
        Path pluginsDirectory = localHome.resolve("plugins");
        Files.createDirectories(pluginsDirectory);
        String pluginId = "dev.hmclce.test.historical-target";
        Path installedPackage = pluginsDirectory.resolve(pluginId + ".npl");
        Path targetPackage = temporaryDirectory.resolve("historical-target.npl");
        writePluginPackage(
                installedPackage,
                pluginId,
                "1.0.0",
                "[]",
                "[\"filesystem\"]",
                PackagedTestPlugin.class,
                "installed"
        );
        writePluginPackage(
                targetPackage,
                pluginId,
                "2.0.0",
                "[]",
                "[\"filesystem\",\"network\"]",
                PackagedTestPlugin.class,
                "target"
        );
        PluginManifest installedManifest = readManifest(installedPackage);
        PluginManifest targetManifest = readManifest(targetPackage);
        PluginPermissionStore permissionStore = new PluginPermissionStore(
                localHome.resolve("plugin-permissions.json")
        );
        permissionStore.setGrantedPermissions(
                new PluginPermissionStore.Artifact(
                        pluginId,
                        installedManifest.getVersion(),
                        PluginPackageVersions.calculateSha256(installedPackage)
                ),
                Set.of(PluginPermission.FILESYSTEM)
        );
        permissionStore.setGrantedPermissions(
                new PluginPermissionStore.Artifact(
                        pluginId,
                        targetManifest.getVersion(),
                        PluginPackageVersions.calculateSha256(targetPackage)
                ),
                Set.of(PluginPermission.NETWORK)
        );

        PluginManager manager = new PluginManager(localHome);
        LocalPluginInspection inspection = manager.inspectLocalPluginPackage(targetPackage);

        assertEquals(
                Set.of(PluginPermission.FILESYSTEM),
                manager.getSuggestedGrantedPermissions(inspection)
        );
    }

    /// Defaults a new install to no grants even when its exact artifact has an abandoned historical record.
    ///
    /// @param temporaryDirectory isolated test directory
    /// @throws Exception if package creation, hashing, or permission persistence fails
    @Test
    public void ignoreHistoricalArtifactForNewInstall(@TempDir Path temporaryDirectory) throws Exception {
        Path localHome = temporaryDirectory.resolve("home");
        Path targetPackage = temporaryDirectory.resolve("historical-new-install.npl");
        String pluginId = "dev.hmclce.test.historical-new-install";
        writePluginPackage(
                targetPackage,
                pluginId,
                "1.0.0",
                "[]",
                "[\"network\"]",
                PackagedTestPlugin.class,
                "target"
        );
        PluginManifest targetManifest = readManifest(targetPackage);
        PluginPermissionStore permissionStore = new PluginPermissionStore(
                localHome.resolve("plugin-permissions.json")
        );
        permissionStore.setGrantedPermissions(
                new PluginPermissionStore.Artifact(
                        pluginId,
                        targetManifest.getVersion(),
                        PluginPackageVersions.calculateSha256(targetPackage)
                ),
                Set.of(PluginPermission.NETWORK)
        );

        PluginManager manager = new PluginManager(localHome);
        LocalPluginInspection inspection = manager.inspectLocalPluginPackage(targetPackage);

        assertTrue(manager.getSuggestedGrantedPermissions(inspection).isEmpty());
    }

    /// Makes compatibility overloads deny every capability instead of silently reusing update suggestions.
    ///
    /// @param temporaryDirectory isolated test directory
    /// @throws Exception if package creation, inspection, or staging fails
    @Test
    public void denyPermissionsInCompatibilityUpdateOverloads(@TempDir Path temporaryDirectory) throws Exception {
        PluginManager manager = new PluginManager(temporaryDirectory.resolve("home"));
        String pluginId = "dev.hmclce.test.fail-closed-overload";
        Path installedPackage = manager.getPluginsDirectory().resolve(pluginId + ".npl");
        writePluginPackage(
                installedPackage,
                pluginId,
                "1.0.0",
                "[]",
                "[\"filesystem\"]",
                PackagedTestPlugin.class,
                "installed"
        );
        manager.setGrantedPermissions(pluginId, Set.of(PluginPermission.FILESYSTEM));

        Path singleUpdate = temporaryDirectory.resolve("single-update.npl");
        writePluginPackage(
                singleUpdate,
                pluginId,
                "2.0.0",
                "[]",
                "[\"filesystem\"]",
                PackagedTestPlugin.class,
                "single"
        );
        LocalPluginInspection singleInspection = manager.inspectLocalPluginPackage(singleUpdate);
        manager.prepareLocalPluginInstallation(singleInspection);
        assertTrue(manager.getGrantedPermissions(singleInspection).isEmpty());

        manager.setGrantedPermissions(pluginId, Set.of(PluginPermission.FILESYSTEM));
        Path batchUpdate = temporaryDirectory.resolve("batch-update.npl");
        writePluginPackage(
                batchUpdate,
                pluginId,
                "3.0.0",
                "[]",
                "[\"filesystem\"]",
                PackagedTestPlugin.class,
                "batch"
        );
        LocalPluginInspection batchInspection = manager.inspectLocalPluginPackage(batchUpdate);
        manager.stagePluginInstallations(List.of(batchInspection));
        assertTrue(manager.getGrantedPermissions(batchInspection).isEmpty());
    }

    /// Leaves no discoverable package when permission persistence fails before single-package publication.
    ///
    /// @param temporaryDirectory isolated test directory
    /// @throws Exception if package creation or fault setup fails
    @Test
    public void keepSingleInstallHiddenWhenPermissionPersistenceFails(@TempDir Path temporaryDirectory)
            throws Exception {
        Path localHome = temporaryDirectory.resolve("home");
        PluginManager manager = new PluginManager(localHome);
        Path sourcePackage = temporaryDirectory.resolve("permission-failure.npl");
        writePluginPackage(
                sourcePackage,
                "dev.hmclce.test.permission-failure",
                "1.0.0",
                "[]",
                "[\"filesystem\"]",
                PackagedTestPlugin.class,
                "failure"
        );
        LocalPluginInspection inspection = manager.inspectLocalPluginPackage(sourcePackage);
        Files.createDirectory(localHome.resolve("plugin-permissions.json"));

        assertThrows(
                IOException.class,
                () -> manager.prepareLocalPluginInstallation(
                        inspection,
                        Set.of(PluginPermission.FILESYSTEM)
                )
        );

        try (Stream<Path> files = Files.list(manager.getPluginsDirectory())) {
            assertTrue(files.findAny().isEmpty());
        }
    }

    /// Treats staging cleanup failure as post-commit housekeeping rather than a transaction failure.
    ///
    /// @param temporaryDirectory isolated test directory
    /// @throws IOException if the non-empty staging directory cannot be created
    @Test
    public void ignorePreparedCleanupFailureAfterCommit(@TempDir Path temporaryDirectory) throws IOException {
        Path nonEmptyStagingDirectory = temporaryDirectory.resolve(".stubborn.installing");
        Files.createDirectories(nonEmptyStagingDirectory.resolve("child"));

        assertDoesNotThrow(() -> PluginManager.cleanupPreparedPackages(List.of(nonEmptyStagingDirectory)));
        assertTrue(Files.isDirectory(nonEmptyStagingDirectory));
    }

    /// Removes a valid installed package and its permission decisions even when lifecycle loading never succeeded.
    ///
    /// @param temporaryDirectory isolated test directory
    /// @throws Exception if package creation, persistence, or removal fails
    @Test
    public void uninstallPackageThatIsNotLoaded(@TempDir Path temporaryDirectory) throws Exception {
        Path localHome = temporaryDirectory.resolve("home");
        PluginManager manager = new PluginManager(localHome);
        String pluginId = "dev.hmclce.test.unloaded-uninstall";
        Path installedPackage = manager.getPluginsDirectory().resolve(pluginId + ".npl");
        writePluginPackage(
                installedPackage,
                pluginId,
                "1.0.0",
                "[]",
                "[\"filesystem\"]",
                PackagedTestPlugin.class,
                "unloaded",
                false
        );
        manager.setGrantedPermissions(pluginId, Set.of(PluginPermission.FILESYSTEM));
        manager.enablePlugin(pluginId);
        FXThreadTestSupport.runOnFxThread(manager::discoverPlugins);
        assertNull(manager.getPlugin(pluginId));
        assertEquals(PluginRuntimeStatus.LOAD_FAILED, manager.getPluginRuntimeStatus(pluginId));
        assertNotNull(manager.getPluginRuntimeDetail(pluginId));

        manager.uninstallPlugin(pluginId);

        assertFalse(Files.exists(installedPackage));
        assertTrue(manager.getInstalledManifests().isEmpty());
        assertNull(manager.getPluginRuntimeDetail(pluginId));
        assertThrows(IOException.class, () -> manager.getGrantedPermissions(pluginId));
        PluginManager reloaded = new PluginManager(localHome);
        assertTrue(reloaded.getInstalledManifests().isEmpty());
    }

    /// Writes a minimal valid package whose entry point is supplied by the test class path.
    ///
    /// @param target target package path
    /// @param pluginId package plugin ID
    /// @param version package version
    /// @throws IOException if package creation fails
    private static void writePluginPackage(Path target, String pluginId, String version) throws IOException {
        writePluginPackage(target, pluginId, version, "[]");
    }

    /// Writes an API-v4 package with caller-provided dependency JSON.
    ///
    /// @param target target package path
    /// @param pluginId package plugin ID
    /// @param version package version
    /// @param dependenciesJson raw dependency JSON array
    /// @throws IOException if package creation fails
    private static void writePluginPackage(
            Path target,
            String pluginId,
            String version,
            String dependenciesJson
    ) throws IOException {
        writePluginPackage(target, pluginId, version, dependenciesJson, PackagedTestPlugin.class);
    }

    /// Writes an API-v4 package with a caller-provided dependency array and lifecycle entry point.
    ///
    /// @param target target package path
    /// @param pluginId package plugin ID
    /// @param version package version
    /// @param dependenciesJson raw dependency JSON array
    /// @param entrypoint lifecycle entry-point class
    /// @throws IOException if package creation fails
    private static void writePluginPackage(
            Path target,
            String pluginId,
            String version,
            String dependenciesJson,
            Class<? extends Plugin> entrypoint
    ) throws IOException {
        writePluginPackage(target, pluginId, version, dependenciesJson, "[]", entrypoint, "default");
    }

    /// Writes an API-v4 package with caller-provided dependencies, permissions, lifecycle, and payload marker.
    ///
    /// @param target target package path
    /// @param pluginId package plugin ID
    /// @param version package version
    /// @param dependenciesJson raw dependency JSON array
    /// @param permissionsJson raw permission JSON array
    /// @param entrypoint lifecycle entry-point class
    /// @param marker package payload used to create distinct same-version artifacts
    /// @throws IOException if package creation fails
    private static void writePluginPackage(
            Path target,
            String pluginId,
            String version,
            String dependenciesJson,
            String permissionsJson,
            Class<? extends Plugin> entrypoint,
            String marker
    ) throws IOException {
        writePluginPackage(
                target,
                pluginId,
                version,
                dependenciesJson,
                permissionsJson,
                entrypoint,
                marker,
                true
        );
    }

    /// Writes an API-v4 package while optionally omitting entry-point bytes for parent-classpath rejection tests.
    ///
    /// @param target target package path
    /// @param pluginId package plugin ID
    /// @param version package version
    /// @param dependenciesJson raw dependency JSON array
    /// @param permissionsJson raw permission JSON array
    /// @param entrypoint lifecycle entry-point class named by the manifest
    /// @param marker package payload used to create distinct artifacts
    /// @param includeEntrypointClass whether the package owns the entry-point class bytes
    /// @throws IOException if package creation fails
    private static void writePluginPackage(
            Path target,
            String pluginId,
            String version,
            String dependenciesJson,
            String permissionsJson,
            Class<? extends Plugin> entrypoint,
            String marker,
            boolean includeEntrypointClass
    ) throws IOException {
        Files.createDirectories(Objects.requireNonNull(target.getParent()));
        String manifest = """
                {
                  "schemaVersion": 4,
                  "id": "%s",
                  "name": "Local Install Test",
                  "version": "%s",
                  "type": "java",
                  "entrypoint": "%s",
                  "permissions": %s,
                  "requiredPermissions": %s,
                  "launcherVersion": "*",
                  "dependencies": %s
                }
                """.formatted(
                        pluginId,
                        version,
                        entrypoint.getName(),
                        permissionsJson,
                        "[]",
                        dependenciesJson
                );

        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(target))) {
            ZipEntry entry = new ZipEntry("plugin.json");
            entry.setTime(0);
            output.putNextEntry(entry);
            output.write(manifest.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();

            ZipEntry markerEntry = new ZipEntry("marker.txt");
            markerEntry.setTime(0);
            output.putNextEntry(markerEntry);
            output.write(marker.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();

            if (includeEntrypointClass) {
                writeClassEntry(output, entrypoint);
            }
        }
    }

    /// Writes an API-v4 JVM plugin that requests Mixin while retaining a normal lifecycle entry point.
    ///
    /// @param target target package path
    /// @param pluginId package plugin ID
    /// @param version package version
    /// @throws IOException if package creation fails
    private static void writeMixinPluginPackage(Path target, String pluginId, String version) throws IOException {
        writeMixinPluginPackage(target, pluginId, version, "[\"mixin\"]", true);
    }

    /// Writes an API-v4 JVM Mixin package with configurable permissions and configuration presence.
    ///
    /// @param target target package path
    /// @param pluginId package plugin ID
    /// @param version package version
    /// @param permissionsJson raw permission JSON array
    /// @param includeMixinConfiguration whether to include the declared Mixin configuration resource
    /// @throws IOException if package creation fails
    private static void writeMixinPluginPackage(
            Path target,
            String pluginId,
            String version,
            String permissionsJson,
            boolean includeMixinConfiguration
    ) throws IOException {
        writeMixinPluginPackage(
                target,
                pluginId,
                version,
                permissionsJson,
                permissionsJson,
                includeMixinConfiguration
        );
    }

    /// Writes an API-v4 JVM Mixin package with explicit required and optional permission classification.
    ///
    /// @param target target package path
    /// @param pluginId package plugin ID
    /// @param version package version
    /// @param permissionsJson raw complete permission JSON array
    /// @param requiredPermissionsJson raw required permission JSON array
    /// @param includeMixinConfiguration whether to include the declared Mixin configuration resource
    /// @throws IOException if package creation fails
    private static void writeMixinPluginPackage(
            Path target,
            String pluginId,
            String version,
            String permissionsJson,
            String requiredPermissionsJson,
            boolean includeMixinConfiguration
    ) throws IOException {
        Files.createDirectories(Objects.requireNonNull(target.getParent()));
        String mixinConfig = "mixins." + pluginId + ".json";
        String manifest = """
                {
                  "schemaVersion": 4,
                  "id": "%s",
                  "name": "Mixin Permission Test",
                  "version": "%s",
                  "type": "java",
                  "entrypoint": "%s",
                  "permissions": %s,
                  "requiredPermissions": %s,
                  "launcherVersion": "*",
                  "dependencies": [],
                  "mixins": ["%s"]
                }
                """.formatted(
                        pluginId,
                        version,
                        LifecycleProbePlugin.class.getName(),
                        permissionsJson,
                        requiredPermissionsJson,
                        mixinConfig
                );
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(target))) {
            ZipEntry manifestEntry = new ZipEntry("plugin.json");
            manifestEntry.setTime(0);
            output.putNextEntry(manifestEntry);
            output.write(manifest.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();

            if (includeMixinConfiguration) {
                ZipEntry mixinEntry = new ZipEntry(mixinConfig);
                mixinEntry.setTime(0);
                output.putNextEntry(mixinEntry);
                output.write("{}".getBytes(StandardCharsets.UTF_8));
                output.closeEntry();
            }

            writeClassEntry(output, LifecycleProbePlugin.class);
        }
    }

    /// Writes a schema-v2 package containing real lifecycle bytecode that runtime policy must never execute.
    ///
    /// @param target target package path
    /// @param pluginId package plugin ID
    /// @param version package version
    /// @param entrypoint packaged lifecycle entry-point class
    /// @throws IOException if package creation fails
    private static void writeLegacyPluginPackage(
            Path target,
            String pluginId,
            String version,
            Class<? extends Plugin> entrypoint
    ) throws IOException {
        Files.createDirectories(Objects.requireNonNull(target.getParent()));
        String manifest = """
                {
                  "schemaVersion": 2,
                  "id": "%s",
                  "name": "Legacy Lifecycle Test",
                  "version": "%s",
                  "type": "java",
                  "entrypoint": "%s",
                  "dependencies": []
                }
                """.formatted(pluginId, version, entrypoint.getName());
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(target))) {
            ZipEntry manifestEntry = new ZipEntry("plugin.json");
            manifestEntry.setTime(0);
            output.putNextEntry(manifestEntry);
            output.write(manifest.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
            writeClassEntry(output, entrypoint);
        }
    }

    /// Writes one schema-v3 package retained only for management and migration behavior tests.
    ///
    /// @param target target package path
    /// @param pluginId package plugin ID
    /// @param version package version
    /// @param dependenciesJson raw dependency array JSON
    /// @param entrypoint package-owned lifecycle entry point
    /// @throws IOException if package creation fails
    private static void writeSchemaThreePluginPackage(
            Path target,
            String pluginId,
            String version,
            String dependenciesJson,
            Class<? extends Plugin> entrypoint
    ) throws IOException {
        Files.createDirectories(Objects.requireNonNull(target.getParent()));
        String manifest = """
                {
                  "schemaVersion": 3,
                  "id": "%s",
                  "name": "Legacy Schema Three Test",
                  "version": "%s",
                  "type": "java",
                  "entrypoint": "%s",
                  "permissions": [],
                  "dependencies": %s
                }
                """.formatted(pluginId, version, entrypoint.getName(), dependenciesJson);
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(target))) {
            ZipEntry manifestEntry = new ZipEntry("plugin.json");
            manifestEntry.setTime(0);
            output.putNextEntry(manifestEntry);
            output.write(manifest.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
            writeClassEntry(output, entrypoint);
        }
    }

    /// Clears every system-property lifecycle counter used by [LifecycleProbePlugin].
    private static void clearLifecycleProbeProperties() {
        System.clearProperty(LifecycleProbePlugin.CONSTRUCTED_PROPERTY);
        System.clearProperty(LifecycleProbePlugin.LOADED_PROPERTY);
        System.clearProperty(LifecycleProbePlugin.ENABLED_PROPERTY);
        System.clearProperty(LifecycleProbePlugin.DISABLED_PROPERTY);
        System.clearProperty(LifecycleProbePlugin.UNLOADED_PROPERTY);
        System.clearProperty(LifecycleProbePlugin.THROW_DISABLE_PROPERTY);
        System.clearProperty(LifecycleProbePlugin.THROW_UNLOAD_PROPERTY);
    }

    /// Asserts that the package-owned lifecycle probe did not reach construction or either startup callback.
    private static void assertLifecycleProbeNeverRan() {
        assertNull(System.getProperty(LifecycleProbePlugin.CONSTRUCTED_PROPERTY));
        assertNull(System.getProperty(LifecycleProbePlugin.LOADED_PROPERTY));
        assertNull(System.getProperty(LifecycleProbePlugin.ENABLED_PROPERTY));
    }

    /// Asserts that no constructor or ordinary lifecycle callback has executed for the probe plugin.
    private static void assertLifecycleProbeNotInvoked() {
        assertNull(System.getProperty(LifecycleProbePlugin.CONSTRUCTED_PROPERTY));
        assertNull(System.getProperty(LifecycleProbePlugin.LOADED_PROPERTY));
        assertNull(System.getProperty(LifecycleProbePlugin.ENABLED_PROPERTY));
        assertNull(System.getProperty(LifecycleProbePlugin.DISABLED_PROPERTY));
        assertNull(System.getProperty(LifecycleProbePlugin.UNLOADED_PROPERTY));
    }

    /// Copies one compiled top-level lifecycle class into a generated plugin package.
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

    /// Reads the validated manifest from a test package.
    ///
    /// @param packageFile test package
    /// @return validated manifest
    /// @throws IOException if the package or manifest is invalid
    private static PluginManifest readManifest(Path packageFile) throws IOException {
        try (ZipFile zipFile = new ZipFile(packageFile.toFile())) {
            ZipEntry entry = Objects.requireNonNull(zipFile.getEntry("plugin.json"));
            try (InputStreamReader reader = new InputStreamReader(
                    zipFile.getInputStream(entry),
                    StandardCharsets.UTF_8
            )) {
                return PluginManifest.fromJson(reader);
            }
        }
    }

    /// Lifecycle implementation that attempts administrative access from its constructor.
    @NotNullByDefault
    public static final class AdministrativeConstructorPlugin implements Plugin {
        /// Isolated manager selected by the active test, or `null` outside construction.
        private static @Nullable PluginManager attackTarget;

        /// Whether the parent-classpath constructor executed.
        private static boolean constructed;

        /// Manifest received during `onLoad`, or `null` when construction was rejected first.
        private @Nullable PluginManifest manifest;

        /// Attempts to access a launcher-administrative path before container registration.
        public AdministrativeConstructorPlugin() {
            constructed = true;
            Objects.requireNonNull(attackTarget).getPluginsDirectory();
        }

        /// Stores the context manifest if construction ucepectedly succeeds.
        ///
        /// @param context plugin context supplied by the manager
        @Override
        public void onLoad(PluginContext context) {
            manifest = context.getManifest();
        }

        /// Activates the no-op constructor attack plugin.
        @Override
        public void onEnable() {
        }

        /// Deactivates the no-op constructor attack plugin.
        @Override
        public void onDisable() {
        }

        /// Returns the manifest received during lifecycle registration.
        ///
        /// @return test plugin manifest
        @Override
        public PluginManifest getManifest() {
            return Objects.requireNonNull(manifest);
        }
    }

}
