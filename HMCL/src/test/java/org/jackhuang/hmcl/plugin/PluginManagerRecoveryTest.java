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
import org.jackhuang.hmcl.plugin.protector.PluginRecoveryRecord;
import org.jackhuang.hmcl.plugin.protector.PluginRecoveryStore;
import org.jackhuang.hmcl.plugin.protector.ProtectorStage;
import org.jackhuang.hmcl.plugin.runtime.RuntimeProviderBinding;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies startup recovery quarantine publication before any third-party lifecycle execution.
@EnabledIf("org.jackhuang.hmcl.JavaFXLauncher#isStarted")
@NotNullByDefault
public final class PluginManagerRecoveryTest {
    /// Ordinary schema-v4 plugin ID used to prove recovery still permits executable legacy-compatible manifests.
    private static final String ORDINARY_ID = "dev.hmclce.test.recovery-ordinary";

    /// Runtime Provider Host ID included in every-third-party quarantine assertions.
    private static final String PROVIDER_ID = "dev.hmclce.test.recovery-provider";

    /// Concrete dependency required by the external-runtime payload restoration fixture.
    private static final String DEPENDENCY_ID = "dev.hmclce.test.recovery-dependency";

    /// External-runtime payload whose closure includes both a concrete dependency and a Runtime Provider.
    private static final String PAYLOAD_ID = "dev.hmclce.test.recovery-payload";

    /// Independent quarantined plugin used to distinguish selected-group and restore-all behavior.
    private static final String INDEPENDENT_ID = "dev.hmclce.test.recovery-independent";

    /// Quarantines every installed external plugin before lifecycle code and retains every package-owned byte.
    ///
    /// @param temporaryDirectory isolated launcher-local home parent
    /// @throws Exception if fixture publication, discovery, or state inspection fails
    @Test
    public void quarantineRecoveryBeforeThirdPartyDiscoveryAndRetainFiles(
            @TempDir Path temporaryDirectory
    ) throws Exception {
        clearLifecycleMarkers();
        Path localHome = temporaryDirectory.resolve("home");
        Path ordinaryPackage = localHome.resolve("plugins/ordinary.npl");
        Path providerPackage = localHome.resolve("plugins/provider.npl");
        writeOrdinaryPackage(ordinaryPackage, ORDINARY_ID, "[]");
        writeProviderPackage(providerPackage, PROVIDER_ID);
        Path configFile = localHome.resolve("plugin-data").resolve(ORDINARY_ID).resolve("config.bin");
        Path dataFile = localHome.resolve("plugin-storage").resolve(PROVIDER_ID).resolve("data.bin");
        Files.createDirectories(Objects.requireNonNull(configFile.getParent()));
        Files.createDirectories(Objects.requireNonNull(dataFile.getParent()));
        Files.write(configFile, new byte[]{3, 1, 4, 1});
        Files.write(dataFile, new byte[]{5, 9, 2, 6});
        byte @Unmodifiable [] ordinaryBytes = Files.readAllBytes(ordinaryPackage);
        byte @Unmodifiable [] providerBytes = Files.readAllBytes(providerPackage);
        byte @Unmodifiable [] configBytes = Files.readAllBytes(configFile);
        byte @Unmodifiable [] dataBytes = Files.readAllBytes(dataFile);
        PluginMutationLock mutationLock = new PluginMutationLock(localHome);
        new PluginStateStore(localHome.resolve("plugin-states.json"), mutationLock).saveStrict(
                Set.of(ORDINARY_ID, PROVIDER_ID),
                Set.of(),
                Set.of(),
                null
        );
        PluginRecoveryRecord recoveryRecord = recoveryRecord();
        new PluginRecoveryStore(localHome).save(recoveryRecord);
        PluginManager manager = new PluginManager(localHome);

        FXThreadTestSupport.runOnFxThread(manager::discoverPlugins);

        assertNull(System.getProperty(LifecycleProbePlugin.CONSTRUCTED_PROPERTY));
        assertNull(System.getProperty(LifecycleProbePlugin.LOADED_PROPERTY));
        assertNull(System.getProperty(LifecycleProbePlugin.ENABLED_PROPERTY));
        assertNull(System.getProperty(PackagedRuntimeProviderPlugin.EVENTS_PROPERTY));
        assertNull(manager.getPlugin(ORDINARY_ID));
        assertNull(manager.getPlugin(PROVIDER_ID));
        assertArrayEquals(ordinaryBytes, Files.readAllBytes(ordinaryPackage));
        assertArrayEquals(providerBytes, Files.readAllBytes(providerPackage));
        assertArrayEquals(configBytes, Files.readAllBytes(configFile));
        assertArrayEquals(dataBytes, Files.readAllBytes(dataFile));

        Set<String> enabled = new HashSet<>();
        Set<String> pendingUninstall = new HashSet<>();
        Set<String> quarantined = new HashSet<>();
        new PluginStateStore(localHome.resolve("plugin-states.json"), mutationLock)
                .loadStrict(enabled, pendingUninstall, quarantined);
        assertTrue(enabled.isEmpty());
        assertTrue(pendingUninstall.isEmpty());
        assertEquals(Set.of(ORDINARY_ID, PROVIDER_ID), quarantined);
        assertTrue(new PluginRecoveryStore(localHome).load().isEmpty());

        PluginQuarantineReport report = manager.getQuarantineReport().orElseThrow();
        assertEquals(recoveryRecord.failureTimestampEpochMillis(), report.failureTimestampEpochMillis());
        assertEquals(recoveryRecord.failureReason(), report.failureReason());
        assertEquals(recoveryRecord.lastStage(), report.lastStage());
        assertEquals(recoveryRecord.lastHeartbeatMonotonicNanos(), report.lastHeartbeatMonotonicNanos());
        assertEquals(recoveryRecord.activeProviderId(), report.activeProviderId());
        assertEquals(recoveryRecord.activePluginId(), report.activePluginId());
        assertEquals(recoveryRecord.launcherLogReference(), report.launcherLogReference());
        assertEquals(recoveryRecord.diagnosticDumpReference(), report.diagnosticDumpReference());
        assertEquals(Set.of(ORDINARY_ID, PROVIDER_ID), report.quarantinedPluginIds());
        assertTrue(report.pluginPackagesRetained());
        assertTrue(report.pluginConfigurationRetained());
        assertTrue(report.pluginDataRetained());
        assertFalse(report.toString().contains("nonce"));

        PluginManager safeRestart = new PluginManager(localHome);
        FXThreadTestSupport.runOnFxThread(safeRestart::discoverPlugins);
        assertNull(safeRestart.getPlugin(ORDINARY_ID));
        assertNull(safeRestart.getPlugin(PROVIDER_ID));
        assertFalse(safeRestart.isPluginEnabled(ORDINARY_ID));
        assertFalse(safeRestart.isPluginEnabled(PROVIDER_ID));
        assertEquals(report, safeRestart.getQuarantineReport().orElseThrow());
        clearLifecycleMarkers();
    }

    /// Restores one requested plugin with its concrete dependency and Runtime Provider closure in provider-first order.
    ///
    /// @param temporaryDirectory isolated launcher-local home parent
    /// @throws Exception if fixture publication, restoration planning, or state inspection fails
    @Test
    public void restoreOneWithDependencyAndRuntimeProviderClosure(@TempDir Path temporaryDirectory) throws Exception {
        Path localHome = temporaryDirectory.resolve("home");
        @Unmodifiable Map<String, byte @Unmodifiable []> packageBytes = prepareQuarantinedGraph(localHome);
        PluginManager manager = new PluginManager(localHome);

        @Unmodifiable List<String> restored = manager.restoreQuarantinedPlugin(PAYLOAD_ID);

        assertEquals(List.of(PROVIDER_ID, DEPENDENCY_ID, PAYLOAD_ID), restored);
        PersistedState state = readState(localHome);
        assertEquals(Set.of(PROVIDER_ID, DEPENDENCY_ID, PAYLOAD_ID), state.enabled());
        assertEquals(Set.of(INDEPENDENT_ID), state.quarantined());
        assertPackageBytesUnchanged(localHome, packageBytes);
    }

    /// Restores a selected group through one dependency-consistent provider-first closure.
    ///
    /// @param temporaryDirectory isolated launcher-local home parent
    /// @throws Exception if fixture publication, restoration planning, or state inspection fails
    @Test
    public void restoreSelectedGroupWithDependencyConsistentClosure(@TempDir Path temporaryDirectory) throws Exception {
        Path localHome = temporaryDirectory.resolve("home");
        @Unmodifiable Map<String, byte @Unmodifiable []> packageBytes = prepareQuarantinedGraph(localHome);
        PluginManager manager = new PluginManager(localHome);

        @Unmodifiable List<String> restored = manager.restoreQuarantinedPlugins(Set.of(PAYLOAD_ID, INDEPENDENT_ID));

        assertEquals(List.of(PROVIDER_ID, DEPENDENCY_ID, INDEPENDENT_ID, PAYLOAD_ID), restored);
        PersistedState state = readState(localHome);
        assertEquals(Set.of(PROVIDER_ID, DEPENDENCY_ID, INDEPENDENT_ID, PAYLOAD_ID), state.enabled());
        assertTrue(state.quarantined().isEmpty());
        assertPackageBytesUnchanged(localHome, packageBytes);
    }

    /// Restores every quarantined plugin without loading code or deleting retained packages.
    ///
    /// @param temporaryDirectory isolated launcher-local home parent
    /// @throws Exception if fixture publication, restoration planning, or state inspection fails
    @Test
    public void restoreAllWithProviderFirstDependencyClosure(@TempDir Path temporaryDirectory) throws Exception {
        Path localHome = temporaryDirectory.resolve("home");
        @Unmodifiable Map<String, byte @Unmodifiable []> packageBytes = prepareQuarantinedGraph(localHome);
        PluginManager manager = new PluginManager(localHome);

        @Unmodifiable List<String> restored = manager.restoreAllQuarantinedPlugins();

        assertEquals(List.of(PROVIDER_ID, DEPENDENCY_ID, INDEPENDENT_ID, PAYLOAD_ID), restored);
        PersistedState state = readState(localHome);
        assertEquals(Set.of(PROVIDER_ID, DEPENDENCY_ID, INDEPENDENT_ID, PAYLOAD_ID), state.enabled());
        assertTrue(state.quarantined().isEmpty());
        assertPackageBytesUnchanged(localHome, packageBytes);
    }

    /// Prevents the ordinary lifecycle enable API from bypassing explicit quarantine restoration planning.
    ///
    /// @param temporaryDirectory isolated launcher-local home parent
    /// @throws Exception if fixture publication, enablement, or state inspection fails
    @Test
    public void ordinaryEnableDoesNotBypassQuarantine(@TempDir Path temporaryDirectory) throws Exception {
        Path localHome = temporaryDirectory.resolve("home");
        prepareQuarantinedGraph(localHome);
        PluginManager manager = new PluginManager(localHome);

        assertFalse(manager.enablePlugin(INDEPENDENT_ID));

        PersistedState state = readState(localHome);
        assertTrue(state.enabled().isEmpty());
        assertEquals(Set.of(PROVIDER_ID, DEPENDENCY_ID, PAYLOAD_ID, INDEPENDENT_ID), state.quarantined());
    }

    /// Re-quarantines the complete installed third-party set when a later startup fails after partial restoration.
    ///
    /// @param temporaryDirectory isolated launcher-local home parent
    /// @throws Exception if fixture publication, restoration, recovery publication, or discovery fails
    @Test
    public void laterFailureRequarantinesEntireInstalledSet(@TempDir Path temporaryDirectory) throws Exception {
        clearLifecycleMarkers();
        Path localHome = temporaryDirectory.resolve("home");
        prepareQuarantinedGraph(localHome);
        PluginManager restorationManager = new PluginManager(localHome);
        restorationManager.restoreQuarantinedPlugin(PAYLOAD_ID);
        PluginRecoveryRecord laterRecovery = new PluginRecoveryRecord(
                1_777_000_000_001L,
                PluginRecoveryRecord.FailureCategory.CRASH,
                PluginRecoveryRecord.FailureReason.CHILD_CRASH,
                ProtectorStage.JVM_STARTED,
                456L,
                null,
                null,
                "logs/later.log",
                "diagnostics/later.txt"
        );
        new PluginRecoveryStore(localHome).save(laterRecovery);
        PluginManager failedRestart = new PluginManager(localHome);

        FXThreadTestSupport.runOnFxThread(failedRestart::discoverPlugins);

        PersistedState state = readState(localHome);
        assertTrue(state.enabled().isEmpty());
        assertEquals(Set.of(PROVIDER_ID, DEPENDENCY_ID, PAYLOAD_ID, INDEPENDENT_ID), state.quarantined());
        PluginQuarantineReport report = failedRestart.getQuarantineReport().orElseThrow();
        assertEquals(laterRecovery.failureTimestampEpochMillis(), report.failureTimestampEpochMillis());
        assertEquals(laterRecovery.failureReason(), report.failureReason());
        assertEquals(state.quarantined(), report.quarantinedPluginIds());
        assertNull(System.getProperty(LifecycleProbePlugin.CONSTRUCTED_PROPERTY));
        assertNull(System.getProperty(PackagedRuntimeProviderPlugin.EVENTS_PROPERTY));
        clearLifecycleMarkers();
    }

    /// Retains strict recovery evidence when quarantine and report cannot replace the state document.
    ///
    /// @param temporaryDirectory isolated launcher-local home parent
    /// @throws Exception if fixture or recovery publication fails
    @Test
    public void retainRecoveryEvidenceWhenQuarantinePublicationFails(
            @TempDir Path temporaryDirectory
    ) throws Exception {
        clearLifecycleMarkers();
        Path localHome = temporaryDirectory.resolve("home");
        Path packageFile = localHome.resolve("plugins/ordinary.npl");
        writeOrdinaryPackage(packageFile, ORDINARY_ID, "[]");
        byte @Unmodifiable [] packageBytes = Files.readAllBytes(packageFile);
        Path statePath = localHome.resolve("plugin-states.json");
        Files.createDirectories(statePath);
        Files.writeString(statePath.resolve("blocker"), "prevent replacement", StandardCharsets.UTF_8);
        PluginRecoveryRecord recoveryRecord = recoveryRecord();
        new PluginRecoveryStore(localHome).save(recoveryRecord);
        PluginManager manager = new PluginManager(localHome);

        FXThreadTestSupport.runOnFxThread(manager::discoverPlugins);

        assertEquals(recoveryRecord, new PluginRecoveryStore(localHome).load().orElseThrow());
        assertTrue(manager.getQuarantineReport().isEmpty());
        assertArrayEquals(packageBytes, Files.readAllBytes(packageFile));
        assertNull(System.getProperty(LifecycleProbePlugin.CONSTRUCTED_PROPERTY));
        assertNull(System.getProperty(LifecycleProbePlugin.LOADED_PROPERTY));
        assertNull(System.getProperty(LifecycleProbePlugin.ENABLED_PROPERTY));
        clearLifecycleMarkers();
    }

    /// Retains recovery evidence and skips every loader when any installed package cannot be enumerated safely.
    ///
    /// @param temporaryDirectory isolated launcher-local home parent
    /// @throws Exception if fixture or recovery publication fails
    @Test
    public void failClosedWhenRecoveryEnumerationContainsMalformedPackage(
            @TempDir Path temporaryDirectory
    ) throws Exception {
        clearLifecycleMarkers();
        Path localHome = temporaryDirectory.resolve("home");
        Path validPackage = localHome.resolve("plugins/valid.npl");
        Path malformedPackage = localHome.resolve("plugins/malformed.npl");
        writeOrdinaryPackage(validPackage, ORDINARY_ID, "[]");
        byte @Unmodifiable [] malformedBytes = {3, 1, 4, 1, 5, 9};
        Files.write(malformedPackage, malformedBytes);
        PluginMutationLock mutationLock = new PluginMutationLock(localHome);
        new PluginStateStore(localHome.resolve("plugin-states.json"), mutationLock).saveStrict(
                Set.of(ORDINARY_ID),
                Set.of(),
                Set.of(),
                null
        );
        PluginRecoveryRecord recoveryRecord = recoveryRecord();
        new PluginRecoveryStore(localHome).save(recoveryRecord);
        PluginManager manager = new PluginManager(localHome);

        FXThreadTestSupport.runOnFxThread(manager::discoverPlugins);

        assertEquals(recoveryRecord, new PluginRecoveryStore(localHome).load().orElseThrow());
        assertTrue(manager.getQuarantineReport().isEmpty());
        PersistedState state = readState(localHome);
        assertEquals(Set.of(ORDINARY_ID), state.enabled());
        assertTrue(state.quarantined().isEmpty());
        assertArrayEquals(malformedBytes, Files.readAllBytes(malformedPackage));
        assertNull(System.getProperty(LifecycleProbePlugin.CONSTRUCTED_PROPERTY));
        assertNull(System.getProperty(LifecycleProbePlugin.LOADED_PROPERTY));
        assertNull(System.getProperty(LifecycleProbePlugin.ENABLED_PROPERTY));
        clearLifecycleMarkers();
    }

    /// Refuses ordinary enablement without replacing an unreadable persisted quarantine snapshot.
    ///
    /// @param temporaryDirectory isolated launcher-local home parent
    /// @throws Exception if fixture publication or state inspection fails
    @Test
    public void ordinaryEnableFailsClosedWhenPersistedStateIsUnreadable(
            @TempDir Path temporaryDirectory
    ) throws Exception {
        Path localHome = temporaryDirectory.resolve("home");
        PluginManager manager = new PluginManager(localHome);
        writeOrdinaryPackage(localHome.resolve("plugins/ordinary.npl"), ORDINARY_ID, "[]");
        Path stateFile = localHome.resolve("plugin-states.json");
        byte @Unmodifiable [] oversizedState = new byte[1024 * 1024 + 1];
        Files.write(stateFile, oversizedState);

        assertFalse(manager.enablePlugin(ORDINARY_ID));

        assertArrayEquals(oversizedState, Files.readAllBytes(stateFile));
    }

    /// Creates the strict recovery record consumed by the manager fixture.
    ///
    /// @return validated provider-stage recovery record
    private static PluginRecoveryRecord recoveryRecord() {
        return new PluginRecoveryRecord(
                1_777_000_000_000L,
                PluginRecoveryRecord.FailureCategory.STAGE_TIMEOUT,
                PluginRecoveryRecord.FailureReason.PROVIDER_DEADLINE_EXCEEDED,
                ProtectorStage.RUNTIME_PROVIDERS_LOADING,
                123L,
                PROVIDER_ID,
                null,
                "logs/hmcl.log",
                "diagnostics/startup.txt"
        );
    }

    /// Publishes one quarantined installed graph and its exact external-runtime binding.
    ///
    /// @param localHome isolated launcher-local home
    /// @return immutable original package bytes indexed by plugin ID
    /// @throws IOException if package, binding, or state publication fails
    private static @Unmodifiable Map<String, byte @Unmodifiable []> prepareQuarantinedGraph(
            Path localHome
    ) throws IOException {
        Map<String, Path> packages = Map.of(
                PROVIDER_ID, localHome.resolve("plugins/provider.npl"),
                DEPENDENCY_ID, localHome.resolve("plugins/dependency.npl"),
                PAYLOAD_ID, localHome.resolve("plugins/payload.npl"),
                INDEPENDENT_ID, localHome.resolve("plugins/independent.npl")
        );
        writeProviderPackage(Objects.requireNonNull(packages.get(PROVIDER_ID)), PROVIDER_ID);
        writeOrdinaryPackage(Objects.requireNonNull(packages.get(DEPENDENCY_ID)), DEPENDENCY_ID, "[]");
        writeExternalPayloadPackage(
                Objects.requireNonNull(packages.get(PAYLOAD_ID)),
                PAYLOAD_ID,
                "[{\"id\":\"" + DEPENDENCY_ID + "\",\"version\":\">=1.0.0\"}]"
        );
        writeOrdinaryPackage(Objects.requireNonNull(packages.get(INDEPENDENT_ID)), INDEPENDENT_ID, "[]");
        PluginMutationLock mutationLock = new PluginMutationLock(localHome);
        new PluginRuntimeBindingStore(localHome, mutationLock).replaceStrict(Map.of(
                PAYLOAD_ID,
                new RuntimeProviderBinding(PAYLOAD_ID, PROVIDER_ID, "rust")
        ));
        Set<String> allIds = Set.copyOf(packages.keySet());
        new PluginStateStore(localHome.resolve("plugin-states.json"), mutationLock).saveStrict(
                Set.of(),
                Set.of(),
                allIds,
                null
        );
        Map<String, byte @Unmodifiable []> packageBytes = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, Path> entry : packages.entrySet()) {
            packageBytes.put(entry.getKey(), Files.readAllBytes(entry.getValue()));
        }
        return Map.copyOf(packageBytes);
    }

    /// Loads the three persisted plugin-state ID sets.
    ///
    /// @param localHome isolated launcher-local home
    /// @return immutable persisted state snapshot
    /// @throws IOException if strict state loading fails
    private static PersistedState readState(Path localHome) throws IOException {
        Set<String> enabled = new HashSet<>();
        Set<String> pendingUninstall = new HashSet<>();
        Set<String> quarantined = new HashSet<>();
        new PluginStateStore(localHome.resolve("plugin-states.json"), new PluginMutationLock(localHome))
                .loadStrict(enabled, pendingUninstall, quarantined);
        return new PersistedState(Set.copyOf(enabled), Set.copyOf(pendingUninstall), Set.copyOf(quarantined));
    }

    /// Verifies every installed package still contains its exact pre-restoration bytes.
    ///
    /// @param localHome isolated launcher-local home
    /// @param expectedBytes immutable original bytes indexed by plugin ID
    /// @throws IOException if an installed package cannot be read
    private static void assertPackageBytesUnchanged(
            Path localHome,
            @Unmodifiable Map<String, byte @Unmodifiable []> expectedBytes
    ) throws IOException {
        for (Map.Entry<String, byte @Unmodifiable []> entry : expectedBytes.entrySet()) {
            Path packageFile = switch (entry.getKey()) {
                case PROVIDER_ID -> localHome.resolve("plugins/provider.npl");
                case DEPENDENCY_ID -> localHome.resolve("plugins/dependency.npl");
                case PAYLOAD_ID -> localHome.resolve("plugins/payload.npl");
                case INDEPENDENT_ID -> localHome.resolve("plugins/independent.npl");
                default -> throw new AssertionError("Unexpected package ID: " + entry.getKey());
            };
            assertArrayEquals(entry.getValue(), Files.readAllBytes(packageFile));
        }
    }

    /// Writes one executable schema-v4 ordinary Java plugin package.
    ///
    /// @param target package path
    /// @param pluginId plugin ID
    /// @param dependenciesJson raw dependency array
    /// @throws IOException if package creation fails
    private static void writeOrdinaryPackage(
            Path target,
            String pluginId,
            String dependenciesJson
    ) throws IOException {
        String manifest = """
                {
                  "schemaVersion": 4,
                  "id": "%s",
                  "name": "Recovery Ordinary",
                  "version": "1.0.0",
                  "type": "java",
                  "entrypoint": "%s",
                  "permissions": [],
                  "requiredPermissions": [],
                  "launcherVersion": "*",
                  "dependencies": %s
                }
                """.formatted(pluginId, LifecycleProbePlugin.class.getName(), dependenciesJson);
        writePackage(target, manifest, LifecycleProbePlugin.class);
    }

    /// Writes one schema-v5 Runtime Provider Host package.
    ///
    /// @param target package path
    /// @param pluginId plugin ID
    /// @throws IOException if package creation fails
    private static void writeProviderPackage(Path target, String pluginId) throws IOException {
        String manifest = """
                {
                  "schemaVersion": 5,
                  "id": "%s",
                  "name": "Recovery Runtime Provider",
                  "version": "1.0.0",
                  "type": "java",
                  "entrypoint": "%s",
                  "permissions": [],
                  "requiredPermissions": [],
                  "launcherVersion": "*",
                  "runtime": "java",
                  "abi": 2,
                  "pluginKind": "runtime-provider",
                  "providesRuntimes": [{
                    "runtime": "rust",
                    "abis": [2],
                    "bridgeAbi": 1,
                    "executionModes": ["embedded"],
                    "features": ["bridge"]
                  }]
                }
                """.formatted(pluginId, PackagedRuntimeProviderPlugin.class.getName());
        writePackage(target, manifest, PackagedRuntimeProviderPlugin.class);
    }

    /// Writes one schema-v5 external-runtime payload with caller-selected concrete dependencies.
    ///
    /// @param target package path
    /// @param pluginId plugin ID
    /// @param dependenciesJson raw dependency array
    /// @throws IOException if package creation fails
    private static void writeExternalPayloadPackage(
            Path target,
            String pluginId,
            String dependenciesJson
    ) throws IOException {
        String manifest = """
                {
                  "schemaVersion": 5,
                  "id": "%s",
                  "name": "Recovery Runtime Payload",
                  "version": "1.0.0",
                  "type": "java",
                  "entrypoint": "payload/plugin.bin",
                  "permissions": [],
                  "requiredPermissions": [],
                  "launcherVersion": "*",
                  "runtime": "rust",
                  "abi": 2,
                  "executionMode": "embedded",
                  "dependencies": %s
                }
                """.formatted(pluginId, dependenciesJson);
        Files.createDirectories(Objects.requireNonNull(target.getParent()));
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(target))) {
            writeEntry(output, "plugin.json", manifest.getBytes(StandardCharsets.UTF_8));
            writeEntry(output, "payload/plugin.bin", new byte[]{1, 2, 3, 4});
        }
    }

    /// Writes one manifest and package-owned lifecycle class into a deterministic `.npl` archive.
    ///
    /// @param target package path
    /// @param manifest validated manifest JSON fixture
    /// @param entrypoint package-owned lifecycle class
    /// @throws IOException if archive creation or class copying fails
    private static void writePackage(
            Path target,
            String manifest,
            Class<? extends Plugin> entrypoint
    ) throws IOException {
        Files.createDirectories(Objects.requireNonNull(target.getParent()));
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(target))) {
            writeEntry(output, "plugin.json", manifest.getBytes(StandardCharsets.UTF_8));
            String classResource = entrypoint.getName().replace('.', '/') + ".class";
            try (@Nullable var input = entrypoint.getClassLoader().getResourceAsStream(classResource)) {
                if (input == null) {
                    throw new IOException("Compiled test plugin class not found: " + classResource);
                }
                ZipEntry classEntry = new ZipEntry(classResource);
                classEntry.setTime(0L);
                output.putNextEntry(classEntry);
                input.transferTo(output);
                output.closeEntry();
            }
        }
    }

    /// Writes one deterministic archive entry.
    ///
    /// @param output target archive
    /// @param name entry name
    /// @param bytes entry bytes
    /// @throws IOException if writing fails
    private static void writeEntry(
            ZipOutputStream output,
            String name,
            byte @Unmodifiable [] bytes
    ) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(0L);
        output.putNextEntry(entry);
        output.write(bytes);
        output.closeEntry();
    }

    /// Clears process-global lifecycle markers shared by package-owned fixtures.
    private static void clearLifecycleMarkers() {
        System.clearProperty(LifecycleProbePlugin.CONSTRUCTED_PROPERTY);
        System.clearProperty(LifecycleProbePlugin.LOADED_PROPERTY);
        System.clearProperty(LifecycleProbePlugin.ENABLED_PROPERTY);
        System.clearProperty(PackagedRuntimeProviderPlugin.EVENTS_PROPERTY);
    }

    /// Immutable persisted plugin-state snapshot used by restoration assertions.
    ///
    /// @param enabled immutable desired-enabled IDs
    /// @param pendingUninstall immutable pending-removal IDs
    /// @param quarantined immutable recovery-quarantined IDs
    @NotNullByDefault
    private record PersistedState(
            @Unmodifiable Set<String> enabled,
            @Unmodifiable Set<String> pendingUninstall,
            @Unmodifiable Set<String> quarantined
    ) {
    }
}
