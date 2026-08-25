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
import org.jackhuang.hmcl.plugin.bridge.PluginCapabilitySession;
import org.jackhuang.hmcl.plugin.bridge.PluginCapabilityToken;
import org.jackhuang.hmcl.plugin.bridge.PluginPermissionAuthority;
import org.jackhuang.hmcl.plugin.runtime.PluginAbi;
import org.jackhuang.hmcl.plugin.runtime.PluginExecutionMode;
import org.jackhuang.hmcl.plugin.runtime.RuntimeFeature;
import org.jackhuang.hmcl.plugin.runtime.RuntimePatchEndpoint;
import org.jackhuang.hmcl.plugin.runtime.RuntimeProvider;
import org.jackhuang.hmcl.plugin.runtime.RuntimeProviderBinding;
import org.jackhuang.hmcl.plugin.runtime.RuntimeProviderDeclaration;
import org.jackhuang.hmcl.plugin.runtime.RuntimeProviderDescriptor;
import org.jackhuang.hmcl.plugin.runtime.RuntimeProviderRegistry;
import org.jackhuang.hmcl.plugin.runtime.RuntimeSupervisor;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies Provider-first Manager startup, ready-gated payload delegation, rollback, and reverse shutdown.
@EnabledIf("org.jackhuang.hmcl.JavaFXLauncher#isStarted")
@NotNullByDefault
public final class PluginManagerRuntimeProviderLifecycleTest {
    /// External payload ID which sorts before its virtual Runtime Provider Host.
    private static final String EARLY_PAYLOAD_ID = "dev.hmclce.test.aaa-runtime-payload";

    /// Canonical external payload plugin ID used by generated packages and bindings.
    private static final String PAYLOAD_ID = "dev.hmclce.test.runtime-payload";

    /// Canonical Java dependent plugin ID used by live graph replacement tests.
    private static final String JAVA_DEPENDENT_ID = "dev.hmclce.test.runtime-dependent";

    /// Independent ordinary plugin whose package filename sorts before the Runtime Provider Host.
    private static final String EARLY_ORDINARY_ID = "dev.hmclce.test.aaa-ordinary";

    /// Secondary Provider Host used as a concrete dependency of the primary Host.
    private static final String DEPENDENCY_PROVIDER_ID = "dev.hmclce.test.runtime-host-dependency";

    /// Restores the dependent Provider ID and lease after recursively loading its Provider dependency.
    ///
    /// @param temporaryDirectory isolated launcher home
    /// @throws Exception if package creation, reflective construction, discovery, or cleanup fails
    @Test
    public void reportDependentProviderAfterItsProviderDependency(@TempDir Path temporaryDirectory) throws Exception {
        Path localHome = temporaryDirectory.resolve("home");
        RuntimeProviderRegistry registry = RuntimeProviderRegistry.processWide();
        clearFixture(registry);
        List<String> reports = new java.util.ArrayList<>();
        try {
            var constructor = PluginManager.class.getDeclaredConstructor(Path.class, BiConsumer.class);
            constructor.setAccessible(true);
            @SuppressWarnings("unchecked")
            BiConsumer<PluginKind, String> reporter = (kind, pluginId) -> reports.add(kind + ":" + pluginId);
            PluginManager manager = constructor.newInstance(localHome, reporter);
            writeHostPackageWithDependency(
                    manager.getPluginsDirectory().resolve("00-dependent-host.npl"),
                    DEPENDENCY_PROVIDER_ID
            );
            writeSecondaryHostPackage(manager.getPluginsDirectory().resolve("99-dependency-host.npl"));
            manager.enablePlugin(PackagedRuntimeProviderPlugin.PROVIDER_ID);
            manager.enablePlugin(DEPENDENCY_PROVIDER_ID);

            FXThreadTestSupport.runOnFxThread(manager::discoverPlugins);

            assertEquals(List.of(
                    PluginKind.RUNTIME_PROVIDER + ":" + DEPENDENCY_PROVIDER_ID,
                    PluginKind.RUNTIME_PROVIDER + ":" + PackagedRuntimeProviderPlugin.PROVIDER_ID
            ), reports);
        } finally {
            registry.unregister(DEPENDENCY_PROVIDER_ID);
            clearFixture(registry);
        }
    }

    /// Loads every enabled Runtime Provider Host before independent ordinary plugins regardless of filename order.
    ///
    /// @param temporaryDirectory isolated launcher home
    /// @throws Exception if package creation, discovery, or cleanup fails
    @Test
    public void loadAllProviderHostsBeforeIndependentOrdinaryPlugins(@TempDir Path temporaryDirectory) throws Exception {
        Path localHome = temporaryDirectory.resolve("home");
        RuntimeProviderRegistry registry = RuntimeProviderRegistry.processWide();
        clearFixture(registry);
        try {
            PluginManager manager = new PluginManager(localHome);
            writeIndependentOrdinaryPackage(manager.getPluginsDirectory().resolve("00-ordinary.npl"));
            writeHostPackage(manager.getPluginsDirectory().resolve("99-host.npl"));
            manager.enablePlugin(EARLY_ORDINARY_ID);
            manager.enablePlugin(PackagedRuntimeProviderPlugin.PROVIDER_ID);

            FXThreadTestSupport.runOnFxThread(manager::discoverPlugins);

            assertEquals(
                    List.of(PackagedRuntimeProviderPlugin.PROVIDER_ID, EARLY_ORDINARY_ID),
                    manager.getPlugins().stream().map(container -> container.getManifest().getId()).toList()
            );
        } finally {
            clearFixture(registry);
        }
    }

    /// Orders a runtime payload after its selected Host even without a concrete manifest dependency.
    ///
    /// @param temporaryDirectory isolated launcher home
    /// @throws Exception if package creation, discovery, snapshotting, or cleanup fails
    @Test
    public void orderRuntimeHookAfterVirtualProviderDependency(@TempDir Path temporaryDirectory) throws Exception {
        Path localHome = temporaryDirectory.resolve("home");
        RuntimeProviderRegistry registry = RuntimeProviderRegistry.processWide();
        clearFixture(registry);
        try {
            PluginManager manager = new PluginManager(localHome);
            writeHookPayloadPackage(
                    manager.getPluginsDirectory().resolve("00-early-payload.npl"), EARLY_PAYLOAD_ID);
            writeHookHostPackage(manager.getPluginsDirectory().resolve("99-hook-host.npl"));
            writeBinding(localHome, EARLY_PAYLOAD_ID);
            manager.setGrantedPermissions(
                    PackagedRuntimeProviderPlugin.PROVIDER_ID,
                    Set.of(PluginPermission.LAUNCHER_HOOK)
            );
            manager.setGrantedPermissions(
                    EARLY_PAYLOAD_ID,
                    Set.of(PluginPermission.LAUNCHER_HOOK, PluginPermission.LAUNCHER_PATCH)
            );
            manager.enablePlugin(PackagedRuntimeProviderPlugin.PROVIDER_ID);
            manager.enablePlugin(EARLY_PAYLOAD_ID);
            FXThreadTestSupport.runOnFxThread(manager::discoverPlugins);
            PluginContainer hostContainer = Objects.requireNonNull(
                    manager.getPlugin(PackagedRuntimeProviderPlugin.PROVIDER_ID));
            PluginContainer payloadContainer = Objects.requireNonNull(manager.getPlugin(EARLY_PAYLOAD_ID));

            @Unmodifiable List<PluginHookSubscriber> subscribers =
                    manager.snapshotHookSubscribers(PluginHookPoint.BEFORE_GAME_LAUNCH);
            try {
                assertEquals(
                        List.of(PackagedRuntimeProviderPlugin.PROVIDER_ID, EARLY_PAYLOAD_ID),
                        subscribers.stream().map(PluginHookSubscriber::pluginId).toList()
                );
                PluginHookEvent event = new PluginHookEvent(
                        PluginHookEvent.CURRENT_CONTRACT_VERSION,
                        "real-runtime-manager-hook",
                        PluginHookPoint.BEFORE_GAME_LAUNCH,
                        java.time.Instant.EPOCH,
                        PluginDataObject.empty(),
                        PluginSecretAccess.denied(EARLY_PAYLOAD_ID)
                );
                assertEquals(PluginHookResult.Action.UNCHANGED,
                        subscribers.get(0).endpoint().invoke(event, Duration.ofMillis(225)).action());
                assertEquals(PluginHookResult.Action.UNCHANGED,
                        subscribers.get(1).endpoint().invoke(event, Duration.ofMillis(225)).action());
                assertEquals(
                        List.of("host.hook", "payload.hook:" + EARLY_PAYLOAD_ID),
                        events().subList(events().size() - 2, events().size())
                );
                RuntimeProvider provider = registry.findById(PackagedRuntimeProviderPlugin.PROVIDER_ID)
                        .orElseThrow();
                ClassLoader providerClassLoader = provider.getClass().getClassLoader();
                assertEquals(
                        Integer.toString(System.identityHashCode(providerClassLoader)),
                        System.getProperty(PackagedRuntimeProviderPlugin.HOOK_TCCL_PROPERTY)
                );
                RuntimePatchEndpoint patchEndpoint = runtimeSupervisor(manager)
                        .patchEndpoint(EARLY_PAYLOAD_ID)
                        .orElseThrow();
                assertEquals(1, patchEndpoint.declarations().size());
                assertEquals(
                        RuntimePatchEndpoint.RegistrationStatus.PATCH_ENGINE_UNAVAILABLE,
                        patchEndpoint.register(patchEndpoint.declarations().get(0))
                );
                FXThreadTestSupport.runOnFxThread(() -> manager.unloadPlugin(EARLY_PAYLOAD_ID));
                FXThreadTestSupport.runOnFxThread(() ->
                        manager.unloadPlugin(PackagedRuntimeProviderPlugin.PROVIDER_ID));
                assertHookLeaseState(payloadContainer, 1, true, false);
                assertHookLeaseState(hostContainer, 2, true, false);
                assertThrows(IllegalStateException.class,
                        () -> subscribers.get(1).endpoint().invoke(event, Duration.ofMillis(225)));
                subscribers.forEach(PluginHookSubscriber::close);
                assertHookLeaseState(payloadContainer, 0, true, true);
                assertHookLeaseState(hostContainer, 0, true, true);
            } finally {
                subscribers.forEach(PluginHookSubscriber::close);
            }
        } finally {
            clearFixture(registry);
        }
    }

    /// Rejects an external payload without an exact binding even when a compatible Provider is registered.
    ///
    /// @param temporaryDirectory isolated launcher home
    /// @throws Exception if package creation, discovery, or cleanup fails
    @Test
    public void rejectExternalPayloadWithoutExactBinding(@TempDir Path temporaryDirectory) throws Exception {
        Path localHome = temporaryDirectory.resolve("home");
        RuntimeProviderRegistry registry = RuntimeProviderRegistry.processWide();
        clearFixture(registry);
        registry.register(compatibleProvider());
        try {
            PluginManager manager = new PluginManager(localHome);
            writeJavaBackedExternalPayloadPackage(manager.getPluginsDirectory().resolve("payload.npl"));
            manager.enablePlugin(PAYLOAD_ID);

            FXThreadTestSupport.runOnFxThread(manager::discoverPlugins);

            assertNull(manager.getPlugin(PAYLOAD_ID));
            assertEquals(PluginRuntimeStatus.LOAD_FAILED, manager.getPluginRuntimeStatus(PAYLOAD_ID));
            assertTrue(Objects.requireNonNull(manager.getPluginRuntimeDetail(PAYLOAD_ID))
                    .contains("runtime Provider binding"));
        } finally {
            clearFixture(registry);
        }
    }

    /// Loads a virtual runtime dependency before its lexically later Host package and tears it down first.
    ///
    /// @param temporaryDirectory isolated launcher home
    /// @throws Exception if package creation, binding persistence, discovery, or teardown fails
    @Test
    public void loadProviderBeforeBoundPayloadAndShutdownInReverse(@TempDir Path temporaryDirectory) throws Exception {
        Path localHome = temporaryDirectory.resolve("home");
        RuntimeProviderRegistry registry = RuntimeProviderRegistry.processWide();
        clearFixture(registry);
        try {
            PluginManager manager = new PluginManager(localHome);
            writePayloadPackage(manager.getPluginsDirectory().resolve("00-payload.npl"));
            writeHostPackage(manager.getPluginsDirectory().resolve("99-host.npl"));
            writeBinding(localHome);
            manager.enablePlugin(PackagedRuntimeProviderPlugin.PROVIDER_ID);
            manager.enablePlugin(PAYLOAD_ID);

            FXThreadTestSupport.runOnFxThread(manager::discoverPlugins);

            assertTrue(Objects.requireNonNull(manager.getPlugin(PackagedRuntimeProviderPlugin.PROVIDER_ID)).isEnabled());
            assertTrue(Objects.requireNonNull(manager.getPlugin(PAYLOAD_ID)).isEnabled());
            assertEquals(List.of(
                    "host.onLoad",
                    "provider.initialize",
                    "provider.health",
                    "host.onEnable",
                    "payload.load",
                    "payload.enable"
            ), events());

            FXThreadTestSupport.runOnFxThread(
                    () -> manager.unloadPlugin(PackagedRuntimeProviderPlugin.PROVIDER_ID));

            assertEquals(List.of(
                    "host.onLoad", "provider.initialize", "provider.health", "host.onEnable",
                    "payload.load", "payload.enable",
                    "payload.disable", "payload.unload",
                    "host.onDisable", "provider.close", "host.onUnload"
            ), events());
            assertNull(manager.getPlugin(PAYLOAD_ID));
            assertNull(manager.getPlugin(PackagedRuntimeProviderPlugin.PROVIDER_ID));
            assertTrue(registry.findById(PackagedRuntimeProviderPlugin.PROVIDER_ID).isEmpty());
        } finally {
            clearFixture(registry);
        }
    }

    /// Re-enables a disabled bound Host before delegating dependent payload enablement.
    ///
    /// @param temporaryDirectory isolated launcher home
    /// @throws Exception if package creation, binding persistence, discovery, or state persistence fails
    @Test
    public void reenableBoundHostBeforeDependentPayload(@TempDir Path temporaryDirectory) throws Exception {
        Path localHome = temporaryDirectory.resolve("home");
        RuntimeProviderRegistry registry = RuntimeProviderRegistry.processWide();
        clearFixture(registry);
        try {
            PluginManager manager = new PluginManager(localHome);
            writePayloadPackage(manager.getPluginsDirectory().resolve("00-payload.npl"));
            writeHostPackage(manager.getPluginsDirectory().resolve("99-host.npl"));
            writeBinding(localHome);
            manager.enablePlugin(PackagedRuntimeProviderPlugin.PROVIDER_ID);
            manager.enablePlugin(PAYLOAD_ID);
            FXThreadTestSupport.runOnFxThread(manager::discoverPlugins);

            manager.disablePlugin(PAYLOAD_ID);
            manager.disablePlugin(PackagedRuntimeProviderPlugin.PROVIDER_ID);
            assertTrue(!Objects.requireNonNull(
                    manager.getPlugin(PackagedRuntimeProviderPlugin.PROVIDER_ID)).isEnabled());
            assertThrows(UncheckedIOException.class, () -> Objects.requireNonNull(
                    manager.getPlugin(PAYLOAD_ID)).getPlugin().onEnable());

            assertTrue(manager.enablePlugin(PAYLOAD_ID));

            assertTrue(Objects.requireNonNull(
                    manager.getPlugin(PackagedRuntimeProviderPlugin.PROVIDER_ID)).isEnabled());
            assertEquals(List.of(
                    "host.onLoad", "provider.initialize", "provider.health", "host.onEnable",
                    "payload.load", "payload.enable", "payload.disable", "host.onDisable",
                    "host.onEnable", "payload.enable"
            ), events());
        } finally {
            clearFixture(registry);
        }
    }

    /// Suspends retained payload capability issuance after disable and resumes a fresh generation before re-enable.
    ///
    /// @param temporaryDirectory isolated launcher home
    /// @throws Exception if package creation, discovery, lifecycle changes, or cleanup fails
    @Test
    public void suspendPayloadCapabilitiesWhileDisabled(@TempDir Path temporaryDirectory) throws Exception {
        Path localHome = temporaryDirectory.resolve("home");
        RuntimeProviderRegistry registry = RuntimeProviderRegistry.processWide();
        clearFixture(registry);
        try {
            PluginManager manager = loadEnabledPayload(localHome);
            RuntimeProvider provider = registry.findById(PackagedRuntimeProviderPlugin.PROVIDER_ID).orElseThrow();

            manager.disablePlugin(PAYLOAD_ID);

            assertFalse(probePayloadCapability(provider));
            assertTrue(manager.enablePlugin(PAYLOAD_ID));
            assertTrue(probePayloadCapability(provider));
        } finally {
            clearFixture(registry);
        }
    }

    /// Suspends payload authority before the Provider begins its bounded disable callback.
    ///
    /// @param temporaryDirectory isolated launcher home
    /// @throws Exception if package creation, discovery, lifecycle changes, or cleanup fails
    @Test
    public void suspendPayloadCapabilitiesBeforeDisableCallback(@TempDir Path temporaryDirectory) throws Exception {
        Path localHome = temporaryDirectory.resolve("home");
        RuntimeProviderRegistry registry = RuntimeProviderRegistry.processWide();
        clearFixture(registry);
        try {
            PluginManager manager = loadEnabledPayload(localHome);

            manager.disablePlugin(PAYLOAD_ID);

            assertEquals("true", System.getProperty(
                    PackagedRuntimeProviderPlugin.DISABLE_CAPABILITY_SUSPENDED_PROPERTY));
        } finally {
            clearFixture(registry);
        }
    }

    /// Suspends capability issuance when payload enablement fails after obtaining a token.
    ///
    /// @param temporaryDirectory isolated launcher home
    /// @throws Exception if package creation, discovery, lifecycle changes, or cleanup fails
    @Test
    public void suspendPayloadCapabilitiesAfterEnableFailure(@TempDir Path temporaryDirectory) throws Exception {
        Path localHome = temporaryDirectory.resolve("home");
        RuntimeProviderRegistry registry = RuntimeProviderRegistry.processWide();
        clearFixture(registry);
        try {
            PluginManager manager = loadEnabledPayload(localHome);
            RuntimeProvider provider = registry.findById(PackagedRuntimeProviderPlugin.PROVIDER_ID).orElseThrow();
            manager.disablePlugin(PAYLOAD_ID);
            System.setProperty(PackagedRuntimeProviderPlugin.FAIL_PAYLOAD_ENABLE_ONCE_PROPERTY, "true");

            assertFalse(manager.enablePlugin(PAYLOAD_ID));

            assertFalse(probePayloadCapability(provider));
            assertTrue(manager.enablePlugin(PAYLOAD_ID));
            assertTrue(probePayloadCapability(provider));
        } finally {
            clearFixture(registry);
        }
    }

    /// Closes capability issuance permanently before the Provider receives payload unload.
    ///
    /// @param temporaryDirectory isolated launcher home
    /// @throws Exception if package creation, discovery, unload, or cleanup fails
    @Test
    public void closePayloadCapabilitiesBeforeUnloadCallback(@TempDir Path temporaryDirectory) throws Exception {
        Path localHome = temporaryDirectory.resolve("home");
        RuntimeProviderRegistry registry = RuntimeProviderRegistry.processWide();
        clearFixture(registry);
        try {
            PluginManager manager = loadEnabledPayload(localHome);
            RuntimeProvider provider = registry.findById(PackagedRuntimeProviderPlugin.PROVIDER_ID).orElseThrow();

            FXThreadTestSupport.runOnFxThread(() -> manager.unloadPlugin(PAYLOAD_ID));

            assertEquals("true", System.getProperty(
                    PackagedRuntimeProviderPlugin.UNLOAD_CAPABILITY_CLOSED_PROPERTY));
            assertFalse(probePayloadCapability(provider));
        } finally {
            clearFixture(registry);
        }
    }

    /// Rotates only the Manager-owned payload session when effective permissions change.
    ///
    /// @param temporaryDirectory isolated launcher home
    /// @throws Exception if package creation, discovery, permission persistence, or reflection fails
    @Test
    public void isolatePermissionRotationFromConcurrentArtifactSession(
            @TempDir Path temporaryDirectory
    ) throws Exception {
        Path localHome = temporaryDirectory.resolve("home");
        RuntimeProviderRegistry registry = RuntimeProviderRegistry.processWide();
        clearFixture(registry);
        try {
            PluginManager manager = loadEnabledPayload(localHome, "[\"launcher-core\"]");
            PluginContainer payload = Objects.requireNonNull(manager.getPlugin(PAYLOAD_ID));
            PluginArtifactIdentity identity = PluginArtifactIdentity.of(
                    payload.getManifest(), payload.getContext().getArtifactSha256());
            PluginPermissionAuthority authority = permissionAuthority(manager);
            try (PluginCapabilitySession independentSession = authority.openSession(
                    identity,
                    PluginExecutionMode.EMBEDDED,
                    () -> Set.of(PluginPermission.LAUNCHER_CORE),
                    "runtime.payload",
                    Duration.ofMinutes(1)
            )) {
                PluginCapabilityToken independentToken = independentSession.issue();

                manager.setGrantedPermissions(PAYLOAD_ID, Set.of(PluginPermission.LAUNCHER_CORE));

                assertDoesNotThrow(() -> authority.requirePermission(
                        independentToken,
                        PAYLOAD_ID,
                        identity,
                        PluginExecutionMode.EMBEDDED,
                        PluginPermission.LAUNCHER_CORE,
                        "runtime.payload"
                ));
            }
        } finally {
            clearFixture(registry);
        }
    }

    /// Rolls an unhealthy Host out of the registry and blocks its bound payload before payload loading.
    ///
    /// @param temporaryDirectory isolated launcher home
    /// @throws Exception if package creation, binding persistence, or discovery fails
    @Test
    public void rollbackUnhealthyProviderAndBlockPayload(@TempDir Path temporaryDirectory) throws Exception {
        Path localHome = temporaryDirectory.resolve("home");
        RuntimeProviderRegistry registry = RuntimeProviderRegistry.processWide();
        clearFixture(registry);
        System.setProperty(PackagedRuntimeProviderPlugin.FAIL_HEALTH_PROPERTY, "true");
        try {
            PluginManager manager = new PluginManager(localHome);
            writePayloadPackage(manager.getPluginsDirectory().resolve("00-payload.npl"));
            writeHostPackage(manager.getPluginsDirectory().resolve("99-host.npl"));
            writeBinding(localHome);
            manager.enablePlugin(PackagedRuntimeProviderPlugin.PROVIDER_ID);
            manager.enablePlugin(PAYLOAD_ID);

            FXThreadTestSupport.runOnFxThread(manager::discoverPlugins);

            assertNull(manager.getPlugin(PackagedRuntimeProviderPlugin.PROVIDER_ID));
            assertNull(manager.getPlugin(PAYLOAD_ID));
            assertEquals(PluginRuntimeStatus.LOAD_FAILED,
                    manager.getPluginRuntimeStatus(PackagedRuntimeProviderPlugin.PROVIDER_ID));
            assertEquals(PluginRuntimeStatus.LOAD_FAILED, manager.getPluginRuntimeStatus(PAYLOAD_ID));
            assertTrue(registry.findById(PackagedRuntimeProviderPlugin.PROVIDER_ID).isEmpty());
            assertEquals(List.of("host.onLoad", "provider.initialize", "provider.health",
                    "provider.close", "host.onUnload"), events());
        } finally {
            System.clearProperty(PackagedRuntimeProviderPlugin.FAIL_HEALTH_PROPERTY);
            clearFixture(registry);
        }
    }

    /// Retries Provider discovery on the same manager after an early dependency failure is repaired.
    ///
    /// @param temporaryDirectory isolated launcher home
    /// @throws Exception if package creation, discovery, replacement, or cleanup fails
    @Test
    public void retryProviderDiscoveryAfterEarlyDependencyFailure(@TempDir Path temporaryDirectory) throws Exception {
        Path localHome = temporaryDirectory.resolve("home");
        RuntimeProviderRegistry registry = RuntimeProviderRegistry.processWide();
        clearFixture(registry);
        try {
            PluginManager manager = new PluginManager(localHome);
            Path hostPackage = manager.getPluginsDirectory().resolve("99-host.npl");
            writeHostPackageWithDependency(hostPackage, "dev.hmclce.test.missing");
            manager.enablePlugin(PackagedRuntimeProviderPlugin.PROVIDER_ID);

            FXThreadTestSupport.runOnFxThread(manager::discoverPlugins);
            assertNull(manager.getPlugin(PackagedRuntimeProviderPlugin.PROVIDER_ID));

            writeHostPackage(hostPackage);
            FXThreadTestSupport.runOnFxThread(manager::discoverPlugins);

            assertTrue(Objects.requireNonNull(
                    manager.getPlugin(PackagedRuntimeProviderPlugin.PROVIDER_ID)).isEnabled());
            assertEquals(PluginRuntimeStatus.ENABLED,
                    manager.getPluginRuntimeStatus(PackagedRuntimeProviderPlugin.PROVIDER_ID));
            assertTrue(registry.findById(PackagedRuntimeProviderPlugin.PROVIDER_ID).isPresent());
        } finally {
            clearFixture(registry);
        }
    }

    /// Restores the old Host package, binding, and enablement when a replacement fails health validation.
    ///
    /// @param temporaryDirectory isolated launcher home
    /// @throws Exception if package creation, discovery, staging, or rollback verification fails
    @Test
    public void rollbackUnhealthyProviderUpdateBeforeCommit(@TempDir Path temporaryDirectory) throws Exception {
        Path localHome = temporaryDirectory.resolve("home");
        RuntimeProviderRegistry registry = RuntimeProviderRegistry.processWide();
        clearFixture(registry);
        try {
            PluginManager manager = new PluginManager(localHome);
            Path oldHostPackage = manager.getPluginsDirectory().resolve("99-host.npl");
            writePayloadPackage(manager.getPluginsDirectory().resolve("00-payload.npl"));
            writeHostPackage(oldHostPackage, "1.0.0", true);
            writeBinding(localHome);
            manager.setGrantedPermissions(
                    PackagedRuntimeProviderPlugin.PROVIDER_ID,
                    Set.of(PluginPermission.LAUNCHER_UI)
            );
            manager.enablePlugin(PackagedRuntimeProviderPlugin.PROVIDER_ID);
            manager.enablePlugin(PAYLOAD_ID);
            System.setProperty(PackagedRuntimeProviderPlugin.REGISTER_UI_PROPERTY, "true");
            FXThreadTestSupport.runOnFxThread(manager::discoverPlugins);

            String oldHostSha256 = org.jackhuang.hmcl.plugin.internal.PluginPackageVersions
                    .calculateSha256(oldHostPackage);
            PluginContainer oldContainer = Objects.requireNonNull(
                    manager.getPlugin(PackagedRuntimeProviderPlugin.PROVIDER_ID));
            RuntimeProvider oldProvider = registry.findById(PackagedRuntimeProviderPlugin.PROVIDER_ID)
                    .orElseThrow();
            assertEquals(List.of("Runtime Host 1.0.0"), sidebarTitles());
            assertEquals("1", System.getProperty(PackagedRuntimeProviderPlugin.ACTIVE_INSTANCES_PROPERTY));
            Map<String, RuntimeProviderBinding> oldBindings =
                    new PluginRuntimeBindingStore(localHome, new PluginMutationLock(localHome)).readStrict();
            Path replacement = temporaryDirectory.resolve("runtime-host-v2.npl");
            writeHostPackage(replacement, "2.0.0", true);
            System.setProperty(PackagedRuntimeProviderPlugin.FAIL_HEALTH_VERSION_PROPERTY, "2.0.0");

            IOException failure = assertThrows(IOException.class, () -> manager.stagePluginInstallations(
                    List.of(manager.inspectLocalPluginPackage(replacement)),
                    Map.of(PackagedRuntimeProviderPlugin.PROVIDER_ID, Set.of(PluginPermission.LAUNCHER_UI))
            ));
            FXThreadTestSupport.runOnFxThread(() -> {
            });

            assertTrue(Objects.requireNonNull(failure.getMessage()).contains("health check failed"));
            assertTrue(Files.isRegularFile(oldHostPackage));
            assertEquals(oldHostSha256, org.jackhuang.hmcl.plugin.internal.PluginPackageVersions
                    .calculateSha256(oldHostPackage));
            assertEquals("1.0.0", manager.inspectLocalPluginPackage(oldHostPackage).getManifest().getVersion());
            assertEquals(oldBindings,
                    new PluginRuntimeBindingStore(localHome, new PluginMutationLock(localHome)).readStrict());

            Set<String> enabled = new HashSet<>();
            Set<String> pendingUninstall = new HashSet<>();
            Set<String> quarantined = new HashSet<>();
            new PluginStateStore(localHome.resolve("plugin-states.json"), new PluginMutationLock(localHome))
                    .load(enabled, pendingUninstall, quarantined);
            assertEquals(Set.of(PackagedRuntimeProviderPlugin.PROVIDER_ID, PAYLOAD_ID), enabled);
            assertTrue(pendingUninstall.isEmpty());
            assertTrue(quarantined.isEmpty());
            PluginContainer restoredContainer = Objects.requireNonNull(
                    manager.getPlugin(PackagedRuntimeProviderPlugin.PROVIDER_ID));
            assertNotSame(oldContainer, restoredContainer);
            assertTrue(restoredContainer.isEnabled());
            assertTrue(Objects.requireNonNull(manager.getPlugin(PAYLOAD_ID)).isEnabled());
            assertEquals("1.0.0", Objects.requireNonNull(
                    manager.getPlugin(PackagedRuntimeProviderPlugin.PROVIDER_ID)).getManifest().getVersion());
            RuntimeProvider restoredProvider = registry.findById(PackagedRuntimeProviderPlugin.PROVIDER_ID)
                    .orElseThrow();
            assertNotSame(oldProvider, restoredProvider);
            assertEquals("1.0.0", restoredProvider.descriptor().version());
            assertEquals(PluginRuntimeStatus.ENABLED,
                    manager.getPluginRuntimeStatus(PackagedRuntimeProviderPlugin.PROVIDER_ID));
            assertEquals(1, registry.candidates("rust").size());
            assertEquals(List.of("Runtime Host 1.0.0"), sidebarTitles());
            assertEquals("1", System.getProperty(PackagedRuntimeProviderPlugin.ACTIVE_INSTANCES_PROPERTY));
            assertEquals(
                    localHome.resolve("plugin-storage").resolve(PackagedRuntimeProviderPlugin.PROVIDER_ID)
                            .toAbsolutePath().normalize().toString(),
                    System.getProperty(PackagedRuntimeProviderPlugin.DATA_PATH_PROPERTY)
            );
            assertFalse(Files.exists(localHome.resolve("plugin-install-transaction.json")));
            assertFalse(Files.exists(manager.getPluginsDirectory().resolve(
                    PackagedRuntimeProviderPlugin.PROVIDER_ID + ".npl")));
            try (var packages = Files.list(manager.getPluginsDirectory())) {
                assertFalse(packages.map(path -> path.getFileName().toString()).anyMatch(
                        name -> name.endsWith(".backup") || name.endsWith(".installing")
                ));
            }
            assertNoValidationDirectories(localHome);
            assertTrue(events().stream().filter("provider.close"::equals).count() >= 2);
            assertTrue(events().stream().filter("host.onUnload"::equals).count() >= 2);
        } finally {
            clearFixture(registry);
        }
    }

    /// Replaces a live Host against its canonical artifact and persistent private data directory.
    ///
    /// @param temporaryDirectory isolated launcher home
    /// @throws Exception if package creation, discovery, staging, or verification fails
    @Test
    public void validateProviderReplacementAgainstPersistentData(@TempDir Path temporaryDirectory) throws Exception {
        Path localHome = temporaryDirectory.resolve("home");
        RuntimeProviderRegistry registry = RuntimeProviderRegistry.processWide();
        clearFixture(registry);
        try {
            PluginManager manager = new PluginManager(localHome);
            writePayloadPackage(manager.getPluginsDirectory().resolve("00-payload.npl"));
            writeHostPackage(manager.getPluginsDirectory().resolve("99-host.npl"));
            writeBinding(localHome);
            manager.enablePlugin(PackagedRuntimeProviderPlugin.PROVIDER_ID);
            manager.enablePlugin(PAYLOAD_ID);
            FXThreadTestSupport.runOnFxThread(manager::discoverPlugins);

            PluginContainer oldContainer = Objects.requireNonNull(
                    manager.getPlugin(PackagedRuntimeProviderPlugin.PROVIDER_ID));
            RuntimeProvider oldProvider = registry.findById(PackagedRuntimeProviderPlugin.PROVIDER_ID)
                    .orElseThrow();
            Path persistentData = localHome.resolve("plugin-storage")
                    .resolve(PackagedRuntimeProviderPlugin.PROVIDER_ID).toAbsolutePath().normalize();
            Files.createDirectories(persistentData);
            Files.writeString(persistentData.resolve("health.marker"), "ready", StandardCharsets.UTF_8);
            System.setProperty(PackagedRuntimeProviderPlugin.REQUIRED_DATA_MARKER_PROPERTY, "health.marker");
            Path replacement = temporaryDirectory.resolve("runtime-host-v2.npl");
            writeHostPackage(replacement, "2.0.0");

            manager.stagePluginInstallations(List.of(manager.inspectLocalPluginPackage(replacement)));

            PluginContainer replacementContainer = Objects.requireNonNull(
                    manager.getPlugin(PackagedRuntimeProviderPlugin.PROVIDER_ID));
            RuntimeProvider replacementProvider = registry.findById(PackagedRuntimeProviderPlugin.PROVIDER_ID)
                    .orElseThrow();
            assertNotSame(oldContainer, replacementContainer);
            assertNotSame(oldProvider, replacementProvider);
            assertEquals("2.0.0", replacementContainer.getManifest().getVersion());
            assertEquals("2.0.0", replacementProvider.descriptor().version());
            assertTrue(replacementContainer.isEnabled());
            assertTrue(Objects.requireNonNull(manager.getPlugin(PAYLOAD_ID)).isEnabled());
            assertEquals(PluginRuntimeStatus.ENABLED,
                    manager.getPluginRuntimeStatus(PackagedRuntimeProviderPlugin.PROVIDER_ID));
            assertEquals(persistentData.toString(),
                    System.getProperty(PackagedRuntimeProviderPlugin.DATA_PATH_PROPERTY));
            Path installedReplacement = manager.getPluginsDirectory()
                    .resolve(PackagedRuntimeProviderPlugin.PROVIDER_ID + ".npl");
            assertTrue(Files.isRegularFile(installedReplacement));
            assertEquals("2.0.0",
                    manager.inspectLocalPluginPackage(installedReplacement).getManifest().getVersion());
            assertEquals("1", System.getProperty(PackagedRuntimeProviderPlugin.ACTIVE_INSTANCES_PROPERTY));
            assertNoValidationDirectories(localHome);
        } finally {
            clearFixture(registry);
        }
    }

    /// Rejects live replacement when the loaded Host differs from the confirmed prior package artifact.
    ///
    /// @param temporaryDirectory isolated launcher home
    /// @throws Exception if package creation, discovery, inspection, or verification fails
    @Test
    public void rejectLiveSwapWhenLoadedHostDiffersFromPriorArtifact(@TempDir Path temporaryDirectory)
            throws Exception {
        Path localHome = temporaryDirectory.resolve("home");
        RuntimeProviderRegistry registry = RuntimeProviderRegistry.processWide();
        clearFixture(registry);
        try {
            PluginManager manager = new PluginManager(localHome);
            Path installedHost = manager.getPluginsDirectory().resolve("99-host.npl");
            writeHostPackage(installedHost, "1.0.0");
            manager.enablePlugin(PackagedRuntimeProviderPlugin.PROVIDER_ID);
            FXThreadTestSupport.runOnFxThread(manager::discoverPlugins);
            PluginContainer loadedHost = Objects.requireNonNull(
                    manager.getPlugin(PackagedRuntimeProviderPlugin.PROVIDER_ID));
            RuntimeProvider loadedProvider = registry.findById(PackagedRuntimeProviderPlugin.PROVIDER_ID)
                    .orElseThrow();

            writeHostPackage(installedHost, "1.5.0");
            Path replacement = temporaryDirectory.resolve("runtime-host-v2.npl");
            writeHostPackage(replacement, "2.0.0");
            LocalPluginInspection inspection = manager.inspectLocalPluginPackage(replacement);

            IOException failure = assertThrows(IOException.class,
                    () -> manager.stagePluginInstallations(List.of(inspection)));

            assertTrue(Objects.requireNonNull(failure.getMessage()).contains("loaded artifact"));
            assertSame(loadedHost, manager.getPlugin(PackagedRuntimeProviderPlugin.PROVIDER_ID));
            assertSame(loadedProvider,
                    registry.findById(PackagedRuntimeProviderPlugin.PROVIDER_ID).orElseThrow());
            assertTrue(loadedHost.isEnabled());
            assertEquals("1.0.0", loadedHost.getManifest().getVersion());
            assertEquals("1.5.0", manager.inspectLocalPluginPackage(installedHost).getManifest().getVersion());
            assertEquals(0, events().stream().filter("host.onUnload"::equals).count());
            assertEquals(0, events().stream().filter("provider.close"::equals).count());
        } finally {
            clearFixture(registry);
        }
    }

    /// Keeps a successfully reloaded dependent out of restart-waiting state after a live Host batch replacement.
    ///
    /// @param temporaryDirectory isolated launcher home
    /// @throws Exception if package creation, discovery, staging, or verification fails
    @Test
    public void keepReloadedDependentsInLiveRuntimeState(@TempDir Path temporaryDirectory) throws Exception {
        Path localHome = temporaryDirectory.resolve("home");
        RuntimeProviderRegistry registry = RuntimeProviderRegistry.processWide();
        clearFixture(registry);
        try {
            PluginManager manager = new PluginManager(localHome);
            writeHostPackage(manager.getPluginsDirectory().resolve("99-host.npl"), "1.0.0");
            writeJavaDependentPackage(
                    manager.getPluginsDirectory().resolve("98-dependent.npl"), "1.0.0");
            manager.enablePlugin(PackagedRuntimeProviderPlugin.PROVIDER_ID);
            manager.enablePlugin(JAVA_DEPENDENT_ID);
            FXThreadTestSupport.runOnFxThread(manager::discoverPlugins);

            Path replacementHost = temporaryDirectory.resolve("runtime-host-v2.npl");
            Path replacementDependent = temporaryDirectory.resolve("runtime-dependent-v2.npl");
            writeHostPackage(replacementHost, "2.0.0");
            writeJavaDependentPackage(replacementDependent, "2.0.0");

            manager.stagePluginInstallations(List.of(
                    manager.inspectLocalPluginPackage(replacementHost),
                    manager.inspectLocalPluginPackage(replacementDependent)
            ));

            PluginContainer dependent = Objects.requireNonNull(manager.getPlugin(JAVA_DEPENDENT_ID));
            assertEquals("2.0.0", dependent.getManifest().getVersion());
            assertTrue(dependent.isEnabled());
            assertFalse(dependent.isRestartRequired());
            assertEquals(PluginRuntimeStatus.ENABLED, manager.getPluginRuntimeStatus(JAVA_DEPENDENT_ID));
        } finally {
            clearFixture(registry);
        }
    }

    /// Retains a failed external payload unload so the same container, handle, and binding can be retried.
    ///
    /// @param temporaryDirectory isolated launcher home
    /// @throws Exception if package creation, discovery, unload, or cleanup fails
    @Test
    public void retryFailedExternalPayloadUnload(@TempDir Path temporaryDirectory) throws Exception {
        Path localHome = temporaryDirectory.resolve("home");
        RuntimeProviderRegistry registry = RuntimeProviderRegistry.processWide();
        clearFixture(registry);
        try {
            PluginManager manager = new PluginManager(localHome);
            writePayloadPackage(manager.getPluginsDirectory().resolve("00-payload.npl"));
            writeHostPackage(manager.getPluginsDirectory().resolve("99-host.npl"));
            writeBinding(localHome);
            manager.enablePlugin(PackagedRuntimeProviderPlugin.PROVIDER_ID);
            manager.enablePlugin(PAYLOAD_ID);
            FXThreadTestSupport.runOnFxThread(manager::discoverPlugins);
            PluginContainer payloadContainer = Objects.requireNonNull(manager.getPlugin(PAYLOAD_ID));
            RuntimeProviderBinding binding = registry.bindingFor(PAYLOAD_ID).orElseThrow();
            System.setProperty(PackagedRuntimeProviderPlugin.FAIL_UNLOAD_ONCE_PROPERTY, "true");

            FXThreadTestSupport.runOnFxThread(() -> manager.unloadPlugin(PAYLOAD_ID));

            assertSame(payloadContainer, manager.getPlugin(PAYLOAD_ID));
            assertFalse(payloadContainer.isEnabled());
            assertEquals(binding, registry.bindingFor(PAYLOAD_ID).orElseThrow());
            assertEquals(1, events().stream().filter("payload.load"::equals).count());
            assertEquals(1, events().stream().filter("payload.unload"::equals).count());

            FXThreadTestSupport.runOnFxThread(() -> manager.unloadPlugin(PAYLOAD_ID));

            assertNull(manager.getPlugin(PAYLOAD_ID));
            assertTrue(registry.bindingFor(PAYLOAD_ID).isEmpty());
            assertEquals(1, events().stream().filter("payload.load"::equals).count());
            assertEquals(2, events().stream().filter("payload.unload"::equals).count());
        } finally {
            clearFixture(registry);
        }
    }

    /// Retains a Host container and registration until a failed Provider close succeeds on retry.
    ///
    /// @param temporaryDirectory isolated launcher home
    /// @throws Exception if package creation, discovery, unload, or retry verification fails
    @Test
    public void retryFailedRuntimeProviderClose(@TempDir Path temporaryDirectory) throws Exception {
        Path localHome = temporaryDirectory.resolve("home");
        RuntimeProviderRegistry registry = RuntimeProviderRegistry.processWide();
        clearFixture(registry);
        try {
            PluginManager manager = new PluginManager(localHome);
            writeHostPackage(manager.getPluginsDirectory().resolve("99-host.npl"));
            manager.enablePlugin(PackagedRuntimeProviderPlugin.PROVIDER_ID);
            FXThreadTestSupport.runOnFxThread(manager::discoverPlugins);
            PluginContainer host = Objects.requireNonNull(
                    manager.getPlugin(PackagedRuntimeProviderPlugin.PROVIDER_ID));
            RuntimeProvider provider = registry.findById(PackagedRuntimeProviderPlugin.PROVIDER_ID).orElseThrow();
            System.setProperty(PackagedRuntimeProviderPlugin.FAIL_CLOSE_ONCE_PROPERTY, "true");

            FXThreadTestSupport.runOnFxThread(
                    () -> manager.unloadPlugin(PackagedRuntimeProviderPlugin.PROVIDER_ID));

            assertSame(host, manager.getPlugin(PackagedRuntimeProviderPlugin.PROVIDER_ID));
            assertSame(provider, registry.findById(PackagedRuntimeProviderPlugin.PROVIDER_ID).orElseThrow());
            assertEquals("1", System.getProperty(PackagedRuntimeProviderPlugin.ACTIVE_INSTANCES_PROPERTY));

            FXThreadTestSupport.runOnFxThread(
                    () -> manager.unloadPlugin(PackagedRuntimeProviderPlugin.PROVIDER_ID));

            assertNull(manager.getPlugin(PackagedRuntimeProviderPlugin.PROVIDER_ID));
            assertTrue(registry.findById(PackagedRuntimeProviderPlugin.PROVIDER_ID).isEmpty());
            assertEquals("0", System.getProperty(PackagedRuntimeProviderPlugin.ACTIVE_INSTANCES_PROPERTY));
            assertEquals(2, events().stream().filter("provider.close"::equals).count());
            assertEquals(1, events().stream().filter("host.onUnload"::equals).count());
        } finally {
            clearFixture(registry);
        }
    }

    /// Persists the exact virtual Provider binding selected for the external payload.
    ///
    /// @param localHome launcher-local home
    /// @throws IOException if binding publication fails
    private static void writeBinding(Path localHome) throws IOException {
        writeBinding(localHome, PAYLOAD_ID);
    }

    /// Persists one virtual Provider binding for a caller-selected external payload.
    ///
    /// @param localHome launcher-local home
    /// @param payloadId canonical external payload ID
    /// @throws IOException if binding publication fails
    private static void writeBinding(Path localHome, String payloadId) throws IOException {
        PluginMutationLock mutationLock = new PluginMutationLock(localHome);
        new PluginRuntimeBindingStore(localHome, mutationLock).mergeStrict(Map.of(
                payloadId,
                new RuntimeProviderBinding(payloadId, PackagedRuntimeProviderPlugin.PROVIDER_ID, "rust")
        ));
    }

    /// Creates and discovers one enabled Host with one bound enabled external payload.
    ///
    /// @param localHome isolated launcher home
    /// @return manager containing both enabled plugin containers
    /// @throws Exception if package creation, binding persistence, or discovery fails
    private static PluginManager loadEnabledPayload(Path localHome) throws Exception {
        return loadEnabledPayload(localHome, "[]");
    }

    /// Creates and discovers one enabled Host with a permission-configurable external payload.
    ///
    /// @param localHome isolated launcher home
    /// @param permissionsJson payload manifest permission array
    /// @return manager containing both enabled plugin containers
    /// @throws Exception if package creation, binding persistence, or discovery fails
    private static PluginManager loadEnabledPayload(
            Path localHome,
            String permissionsJson
    ) throws Exception {
        PluginManager manager = new PluginManager(localHome);
        writePayloadPackage(manager.getPluginsDirectory().resolve("00-payload.npl"), permissionsJson);
        writeHostPackage(manager.getPluginsDirectory().resolve("99-host.npl"));
        writeBinding(localHome);
        manager.enablePlugin(PackagedRuntimeProviderPlugin.PROVIDER_ID);
        manager.enablePlugin(PAYLOAD_ID);
        FXThreadTestSupport.runOnFxThread(manager::discoverPlugins);
        assertTrue(Objects.requireNonNull(manager.getPlugin(PAYLOAD_ID)).isEnabled());
        return manager;
    }

    /// Returns the Manager's process-local permission authority for exact lifecycle integration verification.
    ///
    /// @param manager manager under test
    /// @return manager-owned permission authority
    /// @throws ReflectiveOperationException if the private implementation field cannot be accessed
    private static PluginPermissionAuthority permissionAuthority(
            PluginManager manager
    ) throws ReflectiveOperationException {
        var field = PluginManager.class.getDeclaredField("permissionAuthority");
        field.setAccessible(true);
        return (PluginPermissionAuthority) field.get(manager);
    }

    /// Returns the Manager's launcher-owned Runtime Supervisor for lifecycle retention verification.
    ///
    /// @param manager manager under test
    /// @return manager-owned Runtime Supervisor
    /// @throws ReflectiveOperationException if the private implementation field cannot be accessed
    private static RuntimeSupervisor runtimeSupervisor(PluginManager manager) throws ReflectiveOperationException {
        var field = PluginManager.class.getDeclaredField("runtimeSupervisor");
        field.setAccessible(true);
        return (RuntimeSupervisor) field.get(manager);
    }

    /// Verifies one real Manager container's callback lease and deferred class-loader close state.
    ///
    /// @param container exact loaded container retained across unload
    /// @param expectedLeases expected active Hook lease count
    /// @param expectedCloseRequested expected close-request state
    /// @param expectedClosed expected physical-close selection state
    /// @throws ReflectiveOperationException if lifecycle state cannot be read
    private static void assertHookLeaseState(
            PluginContainer container,
            int expectedLeases,
            boolean expectedCloseRequested,
            boolean expectedClosed
    ) throws ReflectiveOperationException {
        var leases = PluginContainer.class.getDeclaredField("activeHookLeases");
        var closeRequested = PluginContainer.class.getDeclaredField("classLoaderCloseRequested");
        var closed = PluginContainer.class.getDeclaredField("classLoaderClosed");
        leases.setAccessible(true);
        closeRequested.setAccessible(true);
        closed.setAccessible(true);
        assertEquals(expectedLeases, leases.getInt(container));
        assertEquals(expectedCloseRequested, closeRequested.getBoolean(container));
        assertEquals(expectedClosed, closed.getBoolean(container));
    }

    /// Probes the Provider's retained payload supplier through its process-global health callback.
    ///
    /// @param provider loaded package-owned runtime Provider
    /// @return whether the retained supplier could issue a token
    private static boolean probePayloadCapability(RuntimeProvider provider) throws IOException {
        System.setProperty(PackagedRuntimeProviderPlugin.CHECK_PAYLOAD_CAPABILITY_PROPERTY, "true");
        provider.healthCheck();
        return Boolean.parseBoolean(System.getProperty(
                PackagedRuntimeProviderPlugin.PAYLOAD_CAPABILITY_AVAILABLE_PROPERTY));
    }

    /// Writes the Java bootstrap Host package with its runtime declaration.
    ///
    /// @param target Host package path
    /// @throws IOException if package creation fails
    private static void writeHostPackage(Path target) throws IOException {
        writeHostPackage(target, "1.0.0");
    }

    /// Writes a versioned Java bootstrap Host package with its runtime declaration.
    ///
    /// @param target Host package path
    /// @param version Host package and Provider descriptor version
    /// @throws IOException if package creation fails
    private static void writeHostPackage(Path target, String version) throws IOException {
        writeHostPackage(target, version, false);
    }

    /// Writes a versioned Java bootstrap Host package with optional launcher UI capability.
    ///
    /// @param target Host package path
    /// @param version Host package and Provider descriptor version
    /// @param launcherUi whether the fixture may register a sidebar item
    /// @throws IOException if package creation fails
    private static void writeHostPackage(Path target, String version, boolean launcherUi) throws IOException {
        String permissions = launcherUi ? "[\"launcher-ui\"]" : "[]";
        String manifest = """
                {
                  "schemaVersion": 5,
                  "id": "%s",
                  "name": "Runtime Host",
                  "version": "%s",
                  "type": "java",
                  "entrypoint": "%s",
                  "permissions": %s,
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
                """.formatted(PackagedRuntimeProviderPlugin.PROVIDER_ID, version,
                PackagedRuntimeProviderPlugin.class.getName(),
                permissions);
        writePackage(target, manifest, PackagedRuntimeProviderPlugin.class, false);
    }

    /// Writes a Runtime Provider Host which also subscribes to the launch Hook it transports.
    ///
    /// @param target Host package path
    /// @throws IOException if package creation fails
    private static void writeHookHostPackage(Path target) throws IOException {
        String manifest = """
                {
                  "schemaVersion": 5,
                  "id": "%s",
                  "name": "Runtime Hook Host",
                  "version": "1.0.0",
                  "type": "java",
                  "entrypoint": "%s",
                  "permissions": ["launcher-hook"],
                  "requiredPermissions": ["launcher-hook"],
                  "launcherVersion": "*",
                  "runtime": "java",
                  "abi": 2,
                  "hooks": ["before-game-launch"],
                  "pluginKind": "runtime-provider",
                  "providesRuntimes": [{
                    "runtime": "rust",
                    "abis": [2],
                    "bridgeAbi": 1,
                    "executionModes": ["embedded"],
                    "features": ["bridge", "hooks", "patches"]
                  }]
                }
                """.formatted(
                PackagedRuntimeProviderPlugin.PROVIDER_ID,
                PackagedRuntimeProviderPlugin.class.getName()
        );
        writePackage(target, manifest, PackagedRuntimeProviderPlugin.class, false);
    }

    /// Writes a Java bootstrap Host package with one required concrete dependency.
    ///
    /// @param target Host package path
    /// @param dependencyId required dependency plugin ID
    /// @throws IOException if package creation fails
    private static void writeHostPackageWithDependency(Path target, String dependencyId) throws IOException {
        String manifest = """
                {
                  "schemaVersion": 5,
                  "id": "%s",
                  "name": "Runtime Host",
                  "version": "1.0.0",
                  "type": "java",
                  "entrypoint": "%s",
                  "permissions": [],
                  "requiredPermissions": [],
                  "launcherVersion": "*",
                  "runtime": "java",
                  "abi": 2,
                  "pluginKind": "runtime-provider",
                  "dependencies": ["%s"],
                  "providesRuntimes": [{
                    "runtime": "rust",
                    "abis": [2],
                    "bridgeAbi": 1,
                    "executionModes": ["embedded"],
                    "features": ["bridge"]
                  }]
                }
                """.formatted(PackagedRuntimeProviderPlugin.PROVIDER_ID,
                PackagedRuntimeProviderPlugin.class.getName(), dependencyId);
        writePackage(target, manifest, PackagedRuntimeProviderPlugin.class, false);
    }

    /// Writes the secondary Runtime Provider Host dependency package.
    ///
    /// @param target Host package path
    /// @throws IOException if package creation fails
    private static void writeSecondaryHostPackage(Path target) throws IOException {
        String manifest = """
                {
                  "schemaVersion": 5,
                  "id": "%s",
                  "name": "Runtime Dependency Host",
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
                    "runtime": "rust-dependency",
                    "abis": [2],
                    "bridgeAbi": 1,
                    "executionModes": ["embedded"],
                    "features": ["bridge"]
                  }]
                }
                """.formatted(DEPENDENCY_PROVIDER_ID, PackagedRuntimeProviderPlugin.class.getName());
        writePackage(target, manifest, PackagedRuntimeProviderPlugin.class, false);
    }

    /// Writes a Java plugin package which depends directly on the runtime Host package.
    ///
    /// @param target dependent package path
    /// @param version dependent package version
    /// @throws IOException if package creation fails
    private static void writeJavaDependentPackage(Path target, String version) throws IOException {
        String manifest = """
                {
                  "schemaVersion": 5,
                  "id": "%s",
                  "name": "Runtime Host Dependent",
                  "version": "%s",
                  "type": "java",
                  "entrypoint": "%s",
                  "permissions": [],
                  "requiredPermissions": [],
                  "launcherVersion": "*",
                  "runtime": "java",
                  "abi": 2,
                  "dependencies": ["%s"]
                }
                """.formatted(JAVA_DEPENDENT_ID, version, PackagedTestPlugin.class.getName(),
                PackagedRuntimeProviderPlugin.PROVIDER_ID);
        writePackage(target, manifest, PackagedTestPlugin.class, false);
    }

    /// Writes one independent ordinary Java plugin package.
    ///
    /// @param target ordinary package path
    /// @throws IOException if package creation fails
    private static void writeIndependentOrdinaryPackage(Path target) throws IOException {
        String manifest = """
                {
                  "schemaVersion": 5,
                  "id": "%s",
                  "name": "Early Ordinary Plugin",
                  "version": "1.0.0",
                  "type": "java",
                  "entrypoint": "%s",
                  "permissions": [],
                  "requiredPermissions": [],
                  "launcherVersion": "*",
                  "runtime": "java",
                  "abi": 2
                }
                """.formatted(EARLY_ORDINARY_ID, PackagedTestPlugin.class.getName());
        writePackage(target, manifest, PackagedTestPlugin.class, false);
    }

    /// Writes an external payload package without a JVM lifecycle class.
    ///
    /// @param target payload package path
    /// @throws IOException if package creation fails
    private static void writePayloadPackage(Path target) throws IOException {
        writePayloadPackage(target, "[]");
    }

    /// Writes an external payload package with a caller-selected optional permission list.
    ///
    /// @param target payload package path
    /// @param permissionsJson manifest permission array
    /// @throws IOException if package creation fails
    private static void writePayloadPackage(Path target, String permissionsJson) throws IOException {
        String manifest = """
                {
                  "schemaVersion": 5,
                  "id": "%s",
                  "name": "Runtime Payload",
                  "version": "1.0.0",
                  "type": "java",
                  "entrypoint": "payload/plugin.dll",
                  "permissions": %s,
                  "requiredPermissions": [],
                  "launcherVersion": "*",
                  "runtime": "rust",
                  "abi": 2,
                  "executionMode": "embedded"
                }
                """.formatted(PAYLOAD_ID, permissionsJson);
        writePackage(target, manifest, null, true);
    }

    /// Writes a Hook-declaring external payload without a concrete Host dependency.
    ///
    /// @param target payload package path
    /// @param payloadId canonical payload ID
    /// @throws IOException if package creation fails
    private static void writeHookPayloadPackage(Path target, String payloadId) throws IOException {
        String manifest = """
                {
                  "schemaVersion": 5,
                  "id": "%s",
                  "name": "Runtime Hook Payload",
                  "version": "1.0.0",
                  "type": "java",
                  "entrypoint": "payload/plugin.dll",
                  "permissions": ["launcher-hook", "launcher-patch"],
                  "requiredPermissions": ["launcher-hook", "launcher-patch"],
                  "launcherVersion": "*",
                  "runtime": "rust",
                  "abi": 2,
                  "executionMode": "embedded",
                  "hooks": ["before-game-launch"],
                  "patches": [{
                    "target": "org.jackhuang.hmcl.test.PatchTarget",
                    "method": "launch",
                    "type": "before",
                    "parameters": ["java.lang.String"]
                  }]
                }
                """.formatted(payloadId);
        writePackage(target, manifest, null, true);
    }

    /// Writes an external payload whose entrypoint would be executable by the Java loader if fallback occurred.
    ///
    /// @param target payload package path
    /// @throws IOException if package creation fails
    private static void writeJavaBackedExternalPayloadPackage(Path target) throws IOException {
        String manifest = """
                {
                  "schemaVersion": 5,
                  "id": "%s",
                  "name": "Unbound Runtime Payload",
                  "version": "1.0.0",
                  "type": "java",
                  "entrypoint": "%s",
                  "permissions": [],
                  "requiredPermissions": [],
                  "launcherVersion": "*",
                  "runtime": "rust",
                  "abi": 2,
                  "executionMode": "embedded"
                }
                """.formatted(PAYLOAD_ID, PackagedTestPlugin.class.getName());
        writePackage(target, manifest, PackagedTestPlugin.class, false);
    }

    /// Creates one compatibility-only Provider which is not owned by a runtime Host lifecycle.
    ///
    /// @return compatible Rust Provider
    private static RuntimeProvider compatibleProvider() {
        RuntimeProviderDescriptor descriptor = new RuntimeProviderDescriptor(
                PackagedRuntimeProviderPlugin.PROVIDER_ID,
                "1.0.0",
                List.of(new RuntimeProviderDeclaration(
                        "rust",
                        Set.of(PluginAbi.ABI_2),
                        1,
                        Set.of(PluginExecutionMode.EMBEDDED),
                        Set.of(RuntimeFeature.BRIDGE)
                )),
                true,
                true,
                0,
                false
        );
        return new RuntimeProvider() {
            /// Returns the compatibility-only descriptor.
            @Override
            public RuntimeProviderDescriptor descriptor() {
                return descriptor;
            }
        };
    }

    /// Writes one deterministic test package and optional lifecycle class or payload entry.
    ///
    /// @param target package path
    /// @param manifest manifest JSON
    /// @param entrypoint optional Java lifecycle class
    /// @param payload whether to include the runtime payload resource
    /// @throws IOException if package creation fails
    private static void writePackage(
            Path target,
            String manifest,
            @Nullable Class<? extends Plugin> entrypoint,
            boolean payload
    ) throws IOException {
        Files.createDirectories(Objects.requireNonNull(target.getParent()));
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(target))) {
            writeEntry(output, "plugin.json", manifest.getBytes(StandardCharsets.UTF_8));
            if (entrypoint != null) {
                writeClassEntry(output, entrypoint);
            }
            if (payload) {
                writeEntry(output, "payload/plugin.dll", new byte[]{1, 2, 3, 4});
            }
        }
    }

    /// Copies one compiled lifecycle class into a generated package.
    ///
    /// @param output target archive
    /// @param entrypoint lifecycle class
    /// @throws IOException if class bytes cannot be read or written
    private static void writeClassEntry(
            ZipOutputStream output,
            Class<? extends Plugin> entrypoint
    ) throws IOException {
        String resource = entrypoint.getName().replace('.', '/') + ".class";
        try (@Nullable InputStream input = entrypoint.getClassLoader().getResourceAsStream(resource)) {
            if (input == null) {
                throw new IOException("Compiled test plugin class not found: " + resource);
            }
            ZipEntry entry = new ZipEntry(resource);
            entry.setTime(0);
            output.putNextEntry(entry);
            input.transferTo(output);
            output.closeEntry();
        }
    }

    /// Writes one deterministic archive entry.
    ///
    /// @param output target archive
    /// @param name package-relative name
    /// @param bytes entry bytes
    /// @throws IOException if writing fails
    private static void writeEntry(ZipOutputStream output, String name, byte[] bytes) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(0);
        output.putNextEntry(entry);
        output.write(bytes);
        output.closeEntry();
    }

    /// Returns the current ordered fixture events.
    ///
    /// @return immutable event list
    private static List<String> events() {
        @Nullable String events = System.getProperty(PackagedRuntimeProviderPlugin.EVENTS_PROPERTY);
        return events == null || events.isBlank() ? List.of() : List.of(events.split(","));
    }

    /// Returns the current Host-owned sidebar titles after synchronizing with the JavaFX queue.
    ///
    /// @return immutable ordered sidebar title list
    private static List<String> sidebarTitles() {
        List<String> titles = new java.util.ArrayList<>();
        FXThreadTestSupport.runOnFxThread(() -> PluginUIRegistry.getSidebarItems().stream()
                .filter(item -> item.getPluginId().equals(PackagedRuntimeProviderPlugin.PROVIDER_ID))
                .map(PluginUIRegistry.SidebarItem::getTitle)
                .forEach(titles::add));
        return List.copyOf(titles);
    }

    /// Verifies that no obsolete isolated runtime validation directory remains.
    ///
    /// @param localHome isolated launcher home
    /// @throws IOException if package cache enumeration fails
    private static void assertNoValidationDirectories(Path localHome) throws IOException {
        try (var paths = Files.list(localHome.resolve("plugin-data"))) {
            assertFalse(paths.anyMatch(path -> path.getFileName().toString()
                    .startsWith(".runtime-provider-validation-")));
        }
    }

    /// Clears process-global fixture state and any stale Provider registration.
    ///
    /// @param registry process-wide runtime registry
    private static void clearFixture(RuntimeProviderRegistry registry) {
        registry.unbind(PAYLOAD_ID);
        registry.unbind(EARLY_PAYLOAD_ID);
        registry.unbind(JAVA_DEPENDENT_ID);
        registry.unregister(PackagedRuntimeProviderPlugin.PROVIDER_ID);
        System.clearProperty(PackagedRuntimeProviderPlugin.EVENTS_PROPERTY);
        System.clearProperty(PackagedRuntimeProviderPlugin.FAIL_HEALTH_PROPERTY);
        System.clearProperty(PackagedRuntimeProviderPlugin.REQUIRED_DATA_MARKER_PROPERTY);
        System.clearProperty(PackagedRuntimeProviderPlugin.DATA_PATH_PROPERTY);
        System.clearProperty(PackagedRuntimeProviderPlugin.REGISTER_UI_PROPERTY);
        System.clearProperty(PackagedRuntimeProviderPlugin.FAIL_HEALTH_VERSION_PROPERTY);
        System.clearProperty(PackagedRuntimeProviderPlugin.ACTIVE_INSTANCES_PROPERTY);
        System.clearProperty(PackagedRuntimeProviderPlugin.FAIL_UNLOAD_ONCE_PROPERTY);
        System.clearProperty(PackagedRuntimeProviderPlugin.FAIL_CLOSE_ONCE_PROPERTY);
        System.clearProperty(PackagedRuntimeProviderPlugin.FAIL_PAYLOAD_ENABLE_ONCE_PROPERTY);
        System.clearProperty(PackagedRuntimeProviderPlugin.CHECK_PAYLOAD_CAPABILITY_PROPERTY);
        System.clearProperty(PackagedRuntimeProviderPlugin.PAYLOAD_CAPABILITY_AVAILABLE_PROPERTY);
        System.clearProperty(PackagedRuntimeProviderPlugin.UNLOAD_CAPABILITY_CLOSED_PROPERTY);
        System.clearProperty(PackagedRuntimeProviderPlugin.DISABLE_CAPABILITY_SUSPENDED_PROPERTY);
        System.clearProperty(PackagedRuntimeProviderPlugin.HOOK_TCCL_PROPERTY);
        FXThreadTestSupport.runOnFxThread(
                () -> PluginUIRegistry.unregisterAll(PackagedRuntimeProviderPlugin.PROVIDER_ID));
    }
}
