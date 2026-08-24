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
import org.jackhuang.hmcl.plugin.runtime.PluginAbi;
import org.jackhuang.hmcl.plugin.runtime.PluginCompatibilityEvaluator;
import org.jackhuang.hmcl.plugin.runtime.PluginPlatformTarget;
import org.jackhuang.hmcl.plugin.runtime.PluginRuntimeTypes;
import org.jackhuang.hmcl.plugin.runtime.RuntimeProvider;
import org.jackhuang.hmcl.plugin.runtime.RuntimeProviderRegistry;
import org.jackhuang.hmcl.plugin.trust.PluginRuntimeTrustGuard;
import org.jackhuang.hmcl.plugin.trust.PluginRuntimeTrustTestSupport;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    /// Preserves dependency enablement intent while an available external Provider remains unbound.
    ///
    /// @param temporaryDirectory isolated launcher home
    /// @throws IOException if package creation, state persistence, or discovery fails
    @Test
    public void keepDependencyBlockedAfterUnboundRuntimeProviderBecomesAvailable(
            @TempDir Path temporaryDirectory
    ) throws IOException {
        Path localHome = temporaryDirectory.resolve("home");
        String dependencyId = "dev.hmclce.test.runtime-intent-dependency";
        String dependentId = "dev.hmclce.test.runtime-intent-dependent";
        String runtimeType = "lifecycle-shared-test";
        RuntimeProviderRegistry runtimeProviders = RuntimeProviderRegistry.processWide();
        runtimeProviders.unregister(runtimeType);
        try {
            PluginManager manager = new PluginManager(localHome);
            writeSchemaFivePluginPackage(
                    manager.getPluginsDirectory().resolve(dependencyId + ".npl"),
                    dependencyId,
                    runtimeType,
                    PluginAbi.ABI_1,
                    "[]"
            );
            writeSchemaFivePluginPackage(
                    manager.getPluginsDirectory().resolve(dependentId + ".npl"),
                    dependentId,
                    PluginRuntimeTypes.JAVA,
                    PluginAbi.ABI_1,
                    "[]",
                    "[{\"id\":\"" + dependencyId + "\",\"version\":\">=1.0.0\"}]"
            );

            assertFalse(manager.enablePlugin(dependentId));
            assertTrue(manager.isPluginEnabled(dependentId));
            assertTrue(manager.isPluginEnabled(dependencyId));

            runtimeProviders.register(runtimeProvider(runtimeType, Set.of(PluginAbi.ABI_1)));
            FXThreadTestSupport.runOnFxThread(manager::discoverPlugins);

            assertNull(manager.getPlugin(dependencyId));
            assertNull(manager.getPlugin(dependentId));
            assertTrue(manager.isPluginEnabled(dependentId));
            assertTrue(manager.isPluginEnabled(dependencyId));
            assertEquals(PluginRuntimeStatus.LOAD_FAILED, manager.getPluginRuntimeStatus(dependencyId));
            assertTrue(Objects.requireNonNull(manager.getPluginRuntimeDetail(dependencyId))
                    .contains("runtime Provider binding"));
        } finally {
            runtimeProviders.unregister(runtimeType);
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

    /// Executes a schema-v4 Java/ABI1 package through inspection, staging, restart discovery, and lifecycle loading.
    ///
    /// @param temporaryDirectory isolated launcher home and source package directory
    /// @throws Exception if package creation, installation, discovery, or lifecycle loading fails
    @Test
    public void executeSchemaFourJavaAbiOnePackage(@TempDir Path temporaryDirectory) throws Exception {
        clearLifecycleProbeProperties();
        try {
            Path localHome = temporaryDirectory.resolve("home");
            PluginManager manager = new PluginManager(localHome);
            String pluginId = "dev.hmclce.test.schema-four-execution";
            Path sourcePackage = temporaryDirectory.resolve("schema-four-execution.npl");
            writePluginPackage(sourcePackage, pluginId, "1.0.0", "[]", LifecycleProbePlugin.class);

            LocalPluginInspection inspection = manager.inspectLocalPluginPackage(sourcePackage);
            assertEquals(4, inspection.getManifest().getSchemaVersion());
            assertEquals(PluginRuntimeTypes.JAVA, inspection.getManifest().getRuntime());
            assertEquals(PluginAbi.ABI_1, inspection.getManifest().getAbi());

            LocalPluginInstallation installation = manager.prepareLocalPluginInstallation(inspection, Set.of());
            assertTrue(installation.isRestartRequired());
            assertEquals(PluginRuntimeStatus.WAITING_FOR_RESTART, manager.getPluginRuntimeStatus(pluginId));
            assertLifecycleProbeNeverRan();

            PluginManager restarted = new PluginManager(localHome);
            FXThreadTestSupport.runOnFxThread(restarted::discoverPlugins);

            assertTrue(Objects.requireNonNull(restarted.getPlugin(pluginId)).isEnabled());
            assertEquals(PluginRuntimeStatus.ENABLED, restarted.getPluginRuntimeStatus(pluginId));
            assertEquals("true", System.getProperty(LifecycleProbePlugin.CONSTRUCTED_PROPERTY));
            assertEquals("true", System.getProperty(LifecycleProbePlugin.LOADED_PROPERTY));
            assertEquals("true", System.getProperty(LifecycleProbePlugin.ENABLED_PROPERTY));
        } finally {
            clearLifecycleProbeProperties();
        }
    }

    /// Blocks an incompatible schema-v5 platform before plugin construction or lifecycle callbacks.
    ///
    /// @param temporaryDirectory isolated launcher home
    /// @throws IOException if package creation, state persistence, or discovery fails
    @Test
    public void blockUnsupportedPlatformBeforeLifecycleLoad(@TempDir Path temporaryDirectory) throws IOException {
        clearLifecycleProbeProperties();
        try {
            Path localHome = temporaryDirectory.resolve("home");
            PluginCompatibilityEvaluator evaluator = new PluginCompatibilityEvaluator(
                    new RuntimeProviderRegistry(),
                    PluginPlatformTarget.parse("linux-x64")
            );
            PluginManager manager = new PluginManager(localHome, evaluator);
            String pluginId = "dev.hmclce.test.unsupported-platform";
            writeSchemaFivePluginPackage(
                    manager.getPluginsDirectory().resolve(pluginId + ".npl"),
                    pluginId,
                    "java",
                    PluginAbi.ABI_1,
                    "[\"windows-x64\"]"
            );
            manager.enablePlugin(pluginId);

            FXThreadTestSupport.runOnFxThread(manager::discoverPlugins);

            assertCompatibilityBlockedBeforeLifecycle(manager, pluginId, "do not match host linux-x64");
        } finally {
            clearLifecycleProbeProperties();
        }
    }

    /// Blocks a schema-v5 package with no runtime provider before plugin construction or lifecycle callbacks.
    ///
    /// @param temporaryDirectory isolated launcher home
    /// @throws IOException if package creation, state persistence, or discovery fails
    @Test
    public void blockMissingRuntimeBeforeLifecycleLoad(@TempDir Path temporaryDirectory) throws IOException {
        clearLifecycleProbeProperties();
        try {
            Path localHome = temporaryDirectory.resolve("home");
            PluginManager manager = new PluginManager(localHome);
            String pluginId = "dev.hmclce.test.missing-runtime";
            writeSchemaFivePluginPackage(
                    manager.getPluginsDirectory().resolve(pluginId + ".npl"),
                    pluginId,
                    "dotnet",
                    PluginAbi.ABI_1,
                    "[]"
            );
            manager.enablePlugin(pluginId);

            FXThreadTestSupport.runOnFxThread(manager::discoverPlugins);

            assertCompatibilityBlockedBeforeLifecycle(
                    manager,
                    pluginId,
                    "No plugin runtime provider is registered for dotnet"
            );
        } finally {
            clearLifecycleProbeProperties();
        }
    }

    /// Blocks a schema-v5 package requiring an ABI its registered provider does not implement.
    ///
    /// @param temporaryDirectory isolated launcher home
    /// @throws IOException if package creation, state persistence, or discovery fails
    @Test
    public void blockUnsupportedProviderAbiBeforeLifecycleLoad(@TempDir Path temporaryDirectory) throws IOException {
        clearLifecycleProbeProperties();
        try {
            Path localHome = temporaryDirectory.resolve("home");
            RuntimeProviderRegistry runtimeProviders = new RuntimeProviderRegistry();
            runtimeProviders.register(runtimeProvider("dotnet", Set.of(PluginAbi.ABI_1)));
            PluginCompatibilityEvaluator evaluator = new PluginCompatibilityEvaluator(
                    runtimeProviders,
                    PluginPlatformTarget.current()
            );
            PluginManager manager = new PluginManager(localHome, evaluator);
            String pluginId = "dev.hmclce.test.unsupported-provider-abi";
            writeSchemaFivePluginPackage(
                    manager.getPluginsDirectory().resolve(pluginId + ".npl"),
                    pluginId,
                    "dotnet",
                    PluginAbi.ABI_2,
                    "[]"
            );
            manager.enablePlugin(pluginId);

            FXThreadTestSupport.runOnFxThread(manager::discoverPlugins);

            assertCompatibilityBlockedBeforeLifecycle(manager, pluginId, "does not support requested ABI 2");
        } finally {
            clearLifecycleProbeProperties();
        }
    }

    /// Rejects platform, runtime, and ABI incompatibilities during read-only local package inspection.
    ///
    /// @param temporaryDirectory isolated launcher homes and source package directory
    /// @throws IOException if fixture creation or inspection fails unexpectedly
    @Test
    public void rejectSchemaFiveIncompatibilitiesDuringLocalInspection(
            @TempDir Path temporaryDirectory
    ) throws IOException {
        Path platformPackage = temporaryDirectory.resolve("inspect-platform.npl");
        writeSchemaFivePluginPackage(
                platformPackage,
                "dev.hmclce.test.inspect-platform",
                PluginRuntimeTypes.JAVA,
                PluginAbi.ABI_1,
                "[\"windows-x64\"]"
        );
        PluginManager platformManager = new PluginManager(
                temporaryDirectory.resolve("platform-home"),
                new PluginCompatibilityEvaluator(
                        new RuntimeProviderRegistry(),
                        PluginPlatformTarget.parse("linux-x64")
                )
        );
        IOException platformFailure = assertThrows(
                IOException.class,
                () -> platformManager.inspectLocalPluginPackage(platformPackage)
        );
        assertTrue(Objects.requireNonNull(platformFailure.getMessage()).contains("do not match host linux-x64"));

        Path runtimePackage = temporaryDirectory.resolve("inspect-runtime.npl");
        writeSchemaFivePluginPackage(
                runtimePackage,
                "dev.hmclce.test.inspect-runtime",
                "dotnet",
                PluginAbi.ABI_1,
                "[]"
        );
        PluginManager runtimeManager = new PluginManager(temporaryDirectory.resolve("runtime-home"));
        IOException runtimeFailure = assertThrows(
                IOException.class,
                () -> runtimeManager.inspectLocalPluginPackage(runtimePackage)
        );
        assertTrue(Objects.requireNonNull(runtimeFailure.getMessage())
                .contains("No plugin runtime provider is registered for dotnet"));

        Path abiPackage = temporaryDirectory.resolve("inspect-abi.npl");
        writeSchemaFivePluginPackage(
                abiPackage,
                "dev.hmclce.test.inspect-abi",
                "dotnet",
                PluginAbi.ABI_2,
                "[]"
        );
        PluginManager abiManager = new PluginManager(
                temporaryDirectory.resolve("abi-home"),
                compatibilityEvaluator(PluginPlatformTarget.current(), "dotnet", Set.of(PluginAbi.ABI_1))
        );
        IOException abiFailure = assertThrows(
                IOException.class,
                () -> abiManager.inspectLocalPluginPackage(abiPackage)
        );
        assertTrue(Objects.requireNonNull(abiFailure.getMessage()).contains("does not support requested ABI 2"));
    }

    /// Rechecks platform compatibility after confirmation and before atomic package publication.
    ///
    /// @param temporaryDirectory isolated launcher home and source package directory
    /// @throws IOException if fixture creation or inspection fails unexpectedly
    @Test
    public void recheckUnsupportedPlatformBeforeAtomicPublication(
            @TempDir Path temporaryDirectory
    ) throws IOException {
        Path localHome = temporaryDirectory.resolve("home");
        String pluginId = "dev.hmclce.test.stage-platform";
        Path sourcePackage = temporaryDirectory.resolve("stage-platform.npl");
        writeSchemaFivePluginPackage(
                sourcePackage,
                pluginId,
                PluginRuntimeTypes.JAVA,
                PluginAbi.ABI_1,
                "[\"windows-x64\"]"
        );
        PluginManager inspectionManager = new PluginManager(
                localHome,
                new PluginCompatibilityEvaluator(
                        new RuntimeProviderRegistry(),
                        PluginPlatformTarget.parse("windows-x64")
                )
        );
        LocalPluginInspection inspection = inspectionManager.inspectLocalPluginPackage(sourcePackage);
        PluginManager publicationManager = new PluginManager(
                localHome,
                new PluginCompatibilityEvaluator(
                        new RuntimeProviderRegistry(),
                        PluginPlatformTarget.parse("linux-x64")
                )
        );

        IOException failure = assertThrows(
                IOException.class,
                () -> publicationManager.prepareLocalPluginInstallation(inspection, Set.of())
        );

        assertTrue(Objects.requireNonNull(failure.getMessage()).contains("do not match host linux-x64"));
        assertFalse(Files.exists(publicationManager.getPluginsDirectory().resolve(pluginId + ".npl")));
    }

    /// Rechecks runtime availability after confirmation and before atomic package publication.
    ///
    /// @param temporaryDirectory isolated launcher home and source package directory
    /// @throws IOException if fixture creation or inspection fails unexpectedly
    @Test
    public void recheckMissingRuntimeBeforeAtomicPublication(@TempDir Path temporaryDirectory) throws IOException {
        Path localHome = temporaryDirectory.resolve("home");
        String pluginId = "dev.hmclce.test.stage-runtime";
        Path sourcePackage = temporaryDirectory.resolve("stage-runtime.npl");
        writeSchemaFivePluginPackage(sourcePackage, pluginId, "dotnet", PluginAbi.ABI_1, "[]");
        PluginManager inspectionManager = new PluginManager(
                localHome,
                compatibilityEvaluator(PluginPlatformTarget.current(), "dotnet", Set.of(PluginAbi.ABI_1))
        );
        LocalPluginInspection inspection = inspectionManager.inspectLocalPluginPackage(sourcePackage);
        PluginManager publicationManager = new PluginManager(localHome);

        IOException failure = assertThrows(
                IOException.class,
                () -> publicationManager.prepareLocalPluginInstallation(inspection, Set.of())
        );

        assertTrue(Objects.requireNonNull(failure.getMessage())
                .contains("No plugin runtime provider is registered for dotnet"));
        assertFalse(Files.exists(publicationManager.getPluginsDirectory().resolve(pluginId + ".npl")));
    }

    /// Rechecks provider ABI support after confirmation and before atomic package publication.
    ///
    /// @param temporaryDirectory isolated launcher home and source package directory
    /// @throws IOException if fixture creation or inspection fails unexpectedly
    @Test
    public void recheckUnsupportedProviderAbiBeforeAtomicPublication(
            @TempDir Path temporaryDirectory
    ) throws IOException {
        Path localHome = temporaryDirectory.resolve("home");
        String pluginId = "dev.hmclce.test.stage-abi";
        Path sourcePackage = temporaryDirectory.resolve("stage-abi.npl");
        writeSchemaFivePluginPackage(sourcePackage, pluginId, "dotnet", PluginAbi.ABI_2, "[]");
        PluginManager inspectionManager = new PluginManager(
                localHome,
                compatibilityEvaluator(PluginPlatformTarget.current(), "dotnet", Set.of(PluginAbi.ABI_2))
        );
        LocalPluginInspection inspection = inspectionManager.inspectLocalPluginPackage(sourcePackage);
        PluginManager publicationManager = new PluginManager(
                localHome,
                compatibilityEvaluator(PluginPlatformTarget.current(), "dotnet", Set.of(PluginAbi.ABI_1))
        );

        IOException failure = assertThrows(
                IOException.class,
                () -> publicationManager.prepareLocalPluginInstallation(inspection, Set.of())
        );

        assertTrue(Objects.requireNonNull(failure.getMessage()).contains("does not support requested ABI 2"));
        assertFalse(Files.exists(publicationManager.getPluginsDirectory().resolve(pluginId + ".npl")));
    }

    /// Rechecks the publication manager's live runtime registry immediately before atomic publication.
    ///
    /// @param temporaryDirectory isolated launcher home and source package directory
    /// @throws IOException if fixture creation or inspection fails unexpectedly
    @Test
    public void recheckRuntimeProviderImmediatelyBeforeAtomicPublication(
            @TempDir Path temporaryDirectory
    ) throws IOException {
        Path localHome = temporaryDirectory.resolve("home");
        String pluginId = "dev.hmclce.test.stage-runtime-race";
        String runtimeType = "dotnet";
        Path sourcePackage = temporaryDirectory.resolve("stage-runtime-race.npl");
        writeSchemaFivePluginPackage(sourcePackage, pluginId, runtimeType, PluginAbi.ABI_1, "[]");
        PluginManager inspectionManager = new PluginManager(
                localHome,
                compatibilityEvaluator(
                        PluginPlatformTarget.current(),
                        runtimeType,
                        Set.of(PluginAbi.ABI_1)
                )
        );
        LocalPluginInspection inspection = inspectionManager.inspectLocalPluginPackage(sourcePackage);

        RuntimeProviderRegistry publicationProviders = new RuntimeProviderRegistry();
        publicationProviders.register(new RuntimeProvider() {
            /// Returns the runtime that disappears after its first ABI check.
            @Override
            public String runtimeType() {
                return runtimeType;
            }

            /// Returns the single ABI accepted by this provider.
            @Override
            public @Unmodifiable Set<Integer> implementedPluginAbis() {
                return Set.of(PluginAbi.ABI_1);
            }

            /// Simulates provider withdrawal after the planning-time compatibility result is captured.
            @Override
            public boolean supportsAbi(int requiredAbi) {
                publicationProviders.unregister(runtimeType);
                return requiredAbi == PluginAbi.ABI_1;
            }

            /// Describes this stateful publication-race fixture.
            @Override
            public String describe() {
                return "Self-unregistering publication compatibility provider";
            }
        });
        PluginManager publicationManager = new PluginManager(
                localHome,
                new PluginCompatibilityEvaluator(publicationProviders, PluginPlatformTarget.current())
        );

        IOException failure = assertThrows(
                IOException.class,
                () -> publicationManager.prepareLocalPluginInstallation(inspection, Set.of())
        );

        assertTrue(Objects.requireNonNull(failure.getMessage())
                .contains("No plugin runtime provider is registered for dotnet"));
        assertFalse(Files.exists(publicationManager.getPluginsDirectory().resolve(pluginId + ".npl")));
        assertFalse(Files.exists(localHome.resolve("plugin-states.json")));
    }

    /// Creates a compatibility evaluator with one additional deterministic runtime provider.
    ///
    /// @param hostPlatform host target used for platform matching
    /// @param runtimeType canonical runtime identifier
    /// @param implementedAbis immutable implemented ABI generations
    /// @return compatibility evaluator fixture
    private static PluginCompatibilityEvaluator compatibilityEvaluator(
            PluginPlatformTarget hostPlatform,
            String runtimeType,
            @Unmodifiable Set<Integer> implementedAbis
    ) {
        RuntimeProviderRegistry runtimeProviders = new RuntimeProviderRegistry();
        runtimeProviders.register(runtimeProvider(runtimeType, implementedAbis));
        return new PluginCompatibilityEvaluator(runtimeProviders, hostPlatform);
    }

    /// Writes one schema-v5 lifecycle probe package with explicit runtime compatibility requirements.
    ///
    /// @param target package path
    /// @param pluginId plugin ID
    /// @param runtime canonical runtime identifier
    /// @param abi required runtime ABI
    /// @param platformsJson raw platform target array
    /// @throws IOException if package creation fails
    private static void writeSchemaFivePluginPackage(
            Path target,
            String pluginId,
            String runtime,
            int abi,
            String platformsJson
    ) throws IOException {
        writeSchemaFivePluginPackage(target, pluginId, runtime, abi, platformsJson, "[]");
    }

    /// Writes one schema-v5 lifecycle probe package with explicit compatibility and dependency requirements.
    ///
    /// @param target package path
    /// @param pluginId plugin ID
    /// @param runtime canonical runtime identifier
    /// @param abi required runtime ABI
    /// @param platformsJson raw platform target array
    /// @param dependenciesJson raw dependency array
    /// @throws IOException if package creation fails
    private static void writeSchemaFivePluginPackage(
            Path target,
            String pluginId,
            String runtime,
            int abi,
            String platformsJson,
            String dependenciesJson
    ) throws IOException {
        Files.createDirectories(Objects.requireNonNull(target.getParent()));
        String manifest = """
                {
                  "schemaVersion": 5,
                  "id": "%s",
                  "name": "Lifecycle Compatibility Test",
                  "version": "1.0.0",
                  "type": "java",
                  "entrypoint": "%s",
                  "permissions": [],
                  "requiredPermissions": [],
                  "launcherVersion": "*",
                  "runtime": "%s",
                  "abi": %s,
                  "platforms": %s,
                  "dependencies": %s
                }
                """.formatted(
                        pluginId,
                        LifecycleProbePlugin.class.getName(),
                        runtime,
                        abi,
                        platformsJson,
                        dependenciesJson
                );
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(target))) {
            writeTextEntry(output, "plugin.json", manifest);
            writeClassEntry(output, LifecycleProbePlugin.class);
        }
    }

    /// Creates a deterministic runtime provider fixture for compatibility-gate tests.
    ///
    /// @param runtimeType canonical runtime identifier
    /// @param implementedAbis immutable implemented ABI generations
    /// @return runtime provider fixture
    private static RuntimeProvider runtimeProvider(
            String runtimeType,
            @Unmodifiable Set<Integer> implementedAbis
    ) {
        return new RuntimeProvider() {
            /// Returns the caller-selected runtime identifier.
            @Override
            public String runtimeType() {
                return runtimeType;
            }

            /// Returns the caller-selected immutable ABI set.
            @Override
            public @Unmodifiable Set<Integer> implementedPluginAbis() {
                return Set.copyOf(implementedAbis);
            }

            /// Describes this deterministic test provider.
            @Override
            public String describe() {
                return "Lifecycle compatibility test provider";
            }
        };
    }

    /// Asserts a compatibility failure occurred before construction and every lifecycle callback.
    ///
    /// @param manager manager that attempted discovery
    /// @param pluginId rejected plugin ID
    /// @param expectedDetail evaluator detail fragment
    private static void assertCompatibilityBlockedBeforeLifecycle(
            PluginManager manager,
            String pluginId,
            String expectedDetail
    ) {
        assertEquals(PluginRuntimeStatus.LOAD_FAILED, manager.getPluginRuntimeStatus(pluginId));
        assertTrue(Objects.requireNonNull(manager.getPluginRuntimeDetail(pluginId)).contains(expectedDetail));
        assertNull(manager.getPlugin(pluginId));
        assertLifecycleProbeNeverRan();
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
