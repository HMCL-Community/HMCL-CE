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
package org.jackhuang.hmcl.plugin.runtime;

import org.jackhuang.hmcl.plugin.PluginArtifactIdentity;
import org.jackhuang.hmcl.plugin.PluginDataObject;
import org.jackhuang.hmcl.plugin.PluginHookDispatchException;
import org.jackhuang.hmcl.plugin.PluginHookEvent;
import org.jackhuang.hmcl.plugin.PluginHookPoint;
import org.jackhuang.hmcl.plugin.PluginHookResult;
import org.jackhuang.hmcl.plugin.PluginPermission;
import org.jackhuang.hmcl.plugin.PluginPatchDeclaration;
import org.jackhuang.hmcl.plugin.PluginSecretAccess;
import org.jackhuang.hmcl.plugin.bridge.PluginCapabilityToken;
import org.jackhuang.hmcl.plugin.bridge.PluginPermissionAuthority;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.lang.management.LockInfo;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies launcher-owned runtime Provider lifecycle transitions, rollback, ownership, and payload ordering.
@NotNullByDefault
public final class RuntimeSupervisorTest {
    /// Exact provider state history required before and during a clean shutdown.
    private static final @Unmodifiable List<RuntimeProviderState> COMPLETE_LIFECYCLE = List.of(
            RuntimeProviderState.DISCOVERED,
            RuntimeProviderState.RESOLVED,
            RuntimeProviderState.BOOTSTRAP_LOADED,
            RuntimeProviderState.REGISTERED,
            RuntimeProviderState.NEGOTIATED,
            RuntimeProviderState.INITIALIZED,
            RuntimeProviderState.HEALTHY,
            RuntimeProviderState.READY,
            RuntimeProviderState.STOPPING,
            RuntimeProviderState.STOPPED
    );

    /// Advances through every lifecycle state and rejects payload loading before `READY`.
    ///
    /// @param temporaryDirectory isolated payload paths
    /// @throws Exception if provider callbacks fail unexpectedly
    @Test
    public void requireExactLifecycleAndReadyGate(@TempDir Path temporaryDirectory) throws Exception {
        RuntimeProviderRegistry registry = new RuntimeProviderRegistry();
        RuntimeSupervisor supervisor = new RuntimeSupervisor(registry);
        RecordingProvider provider = new RecordingProvider("dev.host.rust", true);

        supervisor.discover("dev.host.rust");
        supervisor.resolve("dev.host.rust");
        supervisor.bootstrapLoaded("dev.host.rust");
        RuntimeProviderRegistration registration = supervisor.register("dev.host.rust", provider);
        RuntimePayloadContext context = payloadContext("dev.plugin.rust", temporaryDirectory);

        assertThrows(IOException.class, () -> supervisor.loadPayload("dev.plugin.rust", context));
        supervisor.activate(registration);
        RuntimeProviderBinding binding = registry.bind("dev.plugin.rust", requirement("rust"));
        RuntimePayloadHandle handle = supervisor.loadPayload(binding.dependentPluginId(), context);

        assertEquals(RuntimeProviderState.READY, supervisor.state("dev.host.rust").orElseThrow());
        assertEquals(List.of("initialize", "health", "load:dev.plugin.rust"), provider.events);
        assertEquals("dev.plugin.rust", handle.ownerPluginId());
        assertEquals("dev.host.rust", handle.providerId());

        supervisor.unloadPayload(handle);
        registration.close();

        assertEquals(COMPLETE_LIFECYCLE, supervisor.history("dev.host.rust"));
        assertEquals(List.of("initialize", "health", "load:dev.plugin.rust",
                "unload:dev.plugin.rust", "close"), provider.events);
        assertTrue(registry.findById("dev.host.rust").isEmpty());
    }

    /// Routes Hook work only through the exact current enabled payload record and rejects a stale equal handle.
    ///
    /// @param temporaryDirectory isolated payload paths
    /// @throws Exception if lifecycle or Hook callbacks fail unexpectedly
    @Test
    public void routeHookThroughExactEnabledPayloadRecord(@TempDir Path temporaryDirectory) throws Exception {
        String payloadId = "dev.plugin.hook";
        RuntimeProviderRegistry registry = new RuntimeProviderRegistry();
        RuntimeSupervisor supervisor = new RuntimeSupervisor(registry);
        RecordingProvider provider = new RecordingProvider("dev.host.rust", true);
        advanceToBootstrap(supervisor, "dev.host.rust");
        RuntimeProviderRegistration registration = supervisor.register("dev.host.rust", provider);
        supervisor.activate(registration);
        registry.bind(payloadId, requirement("rust"));
        RuntimePayloadHandle handle = supervisor.loadPayload(
                payloadId, payloadContext(payloadId, temporaryDirectory));
        supervisor.enablePayload(handle);
        PluginArtifactIdentity identity = new PluginArtifactIdentity(payloadId, "1.0.0", "a".repeat(64));
        PluginPermissionAuthority authority = new PluginPermissionAuthority();
        RuntimeHookEndpoint endpoint = hookEndpoint(supervisor, identity, authority);

        assertEquals(PluginHookResult.Action.UNCHANGED,
                endpoint.invoke(hookEvent(payloadId), Duration.ofMillis(240)).action());
        assertTrue(provider.events.contains("hook:" + payloadId));

        supervisor.disablePayload(handle);
        assertThrows(IOException.class,
                () -> endpoint.invoke(hookEvent(payloadId), Duration.ofMillis(240)));
        supervisor.enablePayload(handle);
        supervisor.unloadPayload(handle);
        registry.bind(payloadId, requirement("rust"));
        RuntimePayloadHandle replacement = supervisor.loadPayload(
                payloadId, payloadContext(payloadId, temporaryDirectory));
        assertEquals(handle, replacement);
        supervisor.enablePayload(replacement);

        IllegalStateException stale = assertThrows(IllegalStateException.class,
                () -> endpoint.invoke(hookEvent(payloadId), Duration.ofMillis(240)));
        assertTrue(stale.getMessage().contains("stale runtime payload"));

        supervisor.unloadPayload(replacement);
        registration.close();
    }

    /// Reports a missing endpoint when the exact enabled payload Provider has no Hook transport.
    ///
    /// @param temporaryDirectory isolated payload paths
    /// @throws Exception if fixture lifecycle or cleanup fails
    @Test
    public void rejectHookWhenSupervisedProviderHasNoInvoker(@TempDir Path temporaryDirectory) throws Exception {
        String payloadId = "dev.plugin.hook-missing";
        RuntimeProviderRegistry registry = new RuntimeProviderRegistry();
        RuntimeSupervisor supervisor = new RuntimeSupervisor(registry);
        LifecycleOnlyProvider provider = new LifecycleOnlyProvider("dev.host.rust");
        advanceToBootstrap(supervisor, "dev.host.rust");
        RuntimeProviderRegistration registration = supervisor.register("dev.host.rust", provider);
        supervisor.activate(registration);
        registry.bind(payloadId, requirement("rust"));
        RuntimePayloadHandle handle = supervisor.loadPayload(
                payloadId, payloadContext(payloadId, temporaryDirectory));
        supervisor.enablePayload(handle);
        PluginArtifactIdentity identity = new PluginArtifactIdentity(payloadId, "1.0.0", "a".repeat(64));
        RuntimeHookEndpoint endpoint = hookEndpoint(
                supervisor, identity, new PluginPermissionAuthority());

        PluginHookDispatchException failure = assertThrows(
                PluginHookDispatchException.class,
                () -> endpoint.invoke(hookEvent(payloadId), Duration.ofMillis(240))
        );
        assertEquals(PluginHookDispatchException.Category.MISSING_ENDPOINT, failure.category());

        supervisor.unloadPayload(handle);
        registration.close();
    }

    /// Waits for an admitted Hook callback to exit before payload disablement reaches Provider code.
    ///
    /// @param temporaryDirectory isolated payload paths
    /// @throws Exception if callback coordination or lifecycle cleanup fails
    @Test
    public void drainRunningHookBeforePayloadDisable(@TempDir Path temporaryDirectory) throws Exception {
        String payloadId = "dev.plugin.hook-drain";
        RuntimeProviderRegistry registry = new RuntimeProviderRegistry();
        RuntimeSupervisor supervisor = new RuntimeSupervisor(registry);
        RecordingProvider provider = new RecordingProvider("dev.host.rust", true);
        advanceToBootstrap(supervisor, "dev.host.rust");
        RuntimeProviderRegistration registration = supervisor.register("dev.host.rust", provider);
        supervisor.activate(registration);
        registry.bind(payloadId, requirement("rust"));
        RuntimePayloadHandle handle = supervisor.loadPayload(
                payloadId, payloadContext(payloadId, temporaryDirectory));
        supervisor.enablePayload(handle);
        CountDownLatch hookEntered = new CountDownLatch(1);
        CountDownLatch releaseHook = new CountDownLatch(1);
        provider.blockHook(hookEntered, releaseHook);
        PluginArtifactIdentity identity = new PluginArtifactIdentity(payloadId, "1.0.0", "a".repeat(64));
        RuntimeHookEndpoint endpoint = hookEndpoint(
                supervisor, identity, new PluginPermissionAuthority());
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<PluginHookResult> hook = executor.submit(() ->
                    endpoint.invoke(hookEvent(payloadId), Duration.ofSeconds(1)));
            assertTrue(hookEntered.await(5, TimeUnit.SECONDS));
            Future<Void> disabling = executor.submit(() -> {
                supervisor.disablePayload(handle);
                return null;
            });

            assertFalse(disabling.isDone());
            assertFalse(provider.events.contains("disable:" + payloadId));
            releaseHook.countDown();
            assertEquals(PluginHookResult.Action.UNCHANGED,
                    hook.get(5, TimeUnit.SECONDS).action());
            disabling.get(5, TimeUnit.SECONDS);
            assertTrue(provider.events.indexOf("hook:" + payloadId)
                    < provider.events.indexOf("disable:" + payloadId));
        } finally {
            releaseHook.countDown();
            executor.shutdownNow();
            supervisor.unloadPayload(handle);
            registration.close();
        }
    }

    /// Keeps Hook admission closed when Provider payload enablement fails.
    ///
    /// @param temporaryDirectory isolated payload paths
    /// @throws Exception if fixture setup or cleanup fails
    @Test
    public void rejectHookAfterPayloadEnableFailure(@TempDir Path temporaryDirectory) throws Exception {
        String payloadId = "dev.plugin.hook-enable-failure";
        RuntimeProviderRegistry registry = new RuntimeProviderRegistry();
        RuntimeSupervisor supervisor = new RuntimeSupervisor(registry);
        RecordingProvider provider = new RecordingProvider("dev.host.rust", true);
        advanceToBootstrap(supervisor, "dev.host.rust");
        RuntimeProviderRegistration registration = supervisor.register("dev.host.rust", provider);
        supervisor.activate(registration);
        registry.bind(payloadId, requirement("rust"));
        RuntimePayloadHandle handle = supervisor.loadPayload(
                payloadId, payloadContext(payloadId, temporaryDirectory));
        provider.failNextPayloadEnable = true;
        PluginArtifactIdentity identity = new PluginArtifactIdentity(payloadId, "1.0.0", "a".repeat(64));
        RuntimeHookEndpoint endpoint = hookEndpoint(
                supervisor, identity, new PluginPermissionAuthority());

        assertThrows(IOException.class, () -> supervisor.enablePayload(handle));
        assertThrows(IOException.class,
                () -> endpoint.invoke(hookEvent(payloadId), Duration.ofSeconds(1)));
        assertFalse(provider.events.contains("hook:" + payloadId));

        supervisor.unloadPayload(handle);
        registration.close();
    }

    /// Retains Stage-1 Patch declarations on the exact payload record and invalidates them on unload.
    ///
    /// @param temporaryDirectory isolated payload paths
    /// @throws Exception if fixture lifecycle or cleanup fails
    @Test
    public void retainPatchEndpointOnExactPayloadRecord(@TempDir Path temporaryDirectory) throws Exception {
        String payloadId = "dev.plugin.patch";
        RuntimeProviderRegistry registry = new RuntimeProviderRegistry();
        RuntimeSupervisor supervisor = new RuntimeSupervisor(registry);
        RecordingProvider provider = new RecordingProvider("dev.host.rust", true);
        advanceToBootstrap(supervisor, "dev.host.rust");
        RuntimeProviderRegistration registration = supervisor.register("dev.host.rust", provider);
        supervisor.activate(registration);
        registry.bind(payloadId, requirement("rust"));
        RuntimePayloadHandle handle = supervisor.loadPayload(
                payloadId, payloadContext(payloadId, temporaryDirectory));
        supervisor.enablePayload(handle);
        PluginArtifactIdentity identity = new PluginArtifactIdentity(payloadId, "1.0.0", "a".repeat(64));
        PluginPermissionAuthority authority = new PluginPermissionAuthority();
        PluginPatchDeclaration declaration = new PluginPatchDeclaration(
                "org.jackhuang.hmcl.test.PatchTarget",
                "launch",
                PluginPatchDeclaration.PatchType.BEFORE,
                List.of("java.lang.String")
        );

        RuntimePatchEndpoint endpoint = supervisor.retainPatchEndpoint(
                handle,
                identity,
                PluginExecutionMode.EMBEDDED,
                authority,
                () -> authority.issue(
                        identity,
                        PluginExecutionMode.EMBEDDED,
                        Set.of(PluginPermission.LAUNCHER_PATCH),
                        RuntimeHookEndpoint.CALLBACK_DOMAIN,
                        Duration.ofMinutes(1)
                ),
                List.of(declaration)
        );

        assertSame(endpoint, supervisor.patchEndpoint(payloadId).orElseThrow());
        assertEquals(
                RuntimePatchEndpoint.RegistrationStatus.PATCH_ENGINE_UNAVAILABLE,
                endpoint.register(declaration)
        );
        supervisor.disablePayload(handle);
        assertThrows(IllegalStateException.class, () -> endpoint.register(declaration));
        supervisor.unloadPayload(handle);
        assertTrue(supervisor.patchEndpoint(payloadId).isEmpty());
        registry.bind(payloadId, requirement("rust"));
        RuntimePayloadHandle replacement = supervisor.loadPayload(
                payloadId, payloadContext(payloadId, temporaryDirectory));
        assertEquals(handle, replacement);
        supervisor.enablePayload(replacement);
        assertThrows(IllegalStateException.class, () -> endpoint.register(declaration));

        supervisor.unloadPayload(replacement);
        registration.close();
    }

    /// Rolls registration back and records `FAILED` when health negotiation rejects the Provider.
    @Test
    public void rollbackHealthFailure() {
        RuntimeProviderRegistry registry = new RuntimeProviderRegistry();
        RuntimeSupervisor supervisor = new RuntimeSupervisor(registry);
        RecordingProvider provider = new RecordingProvider("dev.host.rust", false);
        supervisor.discover("dev.host.rust");
        supervisor.resolve("dev.host.rust");
        supervisor.bootstrapLoaded("dev.host.rust");
        RuntimeProviderRegistration registration = supervisor.register("dev.host.rust", provider);

        IOException failure = assertThrows(IOException.class, () -> supervisor.activate(registration));

        assertTrue(failure.getMessage().contains("health"));
        assertEquals(RuntimeProviderState.FAILED, supervisor.state("dev.host.rust").orElseThrow());
        assertEquals(List.of(
                RuntimeProviderState.DISCOVERED,
                RuntimeProviderState.RESOLVED,
                RuntimeProviderState.BOOTSTRAP_LOADED,
                RuntimeProviderState.REGISTERED,
                RuntimeProviderState.NEGOTIATED,
                RuntimeProviderState.INITIALIZED,
                RuntimeProviderState.FAILED
        ), supervisor.history("dev.host.rust"));
        assertEquals(List.of("initialize", "health", "close"), provider.events);
        assertTrue(registry.findById("dev.host.rust").isEmpty());
        assertTrue(registration.isClosed());
    }

    /// Binds a registration to its Host owner, rejects duplicates, and makes owner cleanup idempotent.
    @Test
    public void ownAndAutomaticallyCloseRegistrations() throws Exception {
        RuntimeProviderRegistry registry = new RuntimeProviderRegistry();
        RuntimeSupervisor supervisor = new RuntimeSupervisor(registry);
        RecordingProvider provider = new RecordingProvider("dev.host.rust", true);
        advanceToBootstrap(supervisor, "dev.host.rust");
        RuntimeProviderRegistration registration = supervisor.register("dev.host.rust", provider);

        assertEquals("dev.host.rust", registration.ownerPluginId());
        assertSame(provider, registration.provider());
        assertThrows(IllegalStateException.class,
                () -> supervisor.register("dev.host.rust", new RecordingProvider("dev.host.rust", true)));
        assertThrows(IllegalArgumentException.class,
                () -> supervisor.register("dev.host.other", new RecordingProvider("dev.host.unowned", true)));

        supervisor.activate(registration);
        supervisor.closeOwnedRegistrations("dev.host.rust");
        supervisor.closeOwnedRegistrations("dev.host.rust");

        assertTrue(registration.isClosed());
        assertEquals(1, provider.events.stream().filter("close"::equals).count());
        assertEquals(RuntimeProviderState.STOPPED, supervisor.state("dev.host.rust").orElseThrow());
    }

    /// Disables and unloads Provider payloads in strict reverse load order before stopping the Provider.
    ///
    /// @param temporaryDirectory isolated payload paths
    /// @throws Exception if lifecycle callbacks fail unexpectedly
    @Test
    public void stopDependentsInReverseLoadOrder(@TempDir Path temporaryDirectory) throws Exception {
        RuntimeProviderRegistry registry = new RuntimeProviderRegistry();
        RuntimeSupervisor supervisor = new RuntimeSupervisor(registry);
        RecordingProvider provider = new RecordingProvider("dev.host.rust", true);
        advanceToBootstrap(supervisor, "dev.host.rust");
        RuntimeProviderRegistration registration = supervisor.register("dev.host.rust", provider);
        supervisor.activate(registration);
        registry.bind("dev.plugin.first", requirement("rust"));
        registry.bind("dev.plugin.second", requirement("rust"));
        RuntimePayloadHandle first = supervisor.loadPayload(
                "dev.plugin.first", payloadContext("dev.plugin.first", temporaryDirectory));
        RuntimePayloadHandle second = supervisor.loadPayload(
                "dev.plugin.second", payloadContext("dev.plugin.second", temporaryDirectory));
        supervisor.enablePayload(first);
        supervisor.enablePayload(second);

        registration.close();

        assertEquals(List.of(
                "initialize", "health",
                "load:dev.plugin.first", "load:dev.plugin.second",
                "enable:dev.plugin.first", "enable:dev.plugin.second",
                "disable:dev.plugin.second", "unload:dev.plugin.second",
                "disable:dev.plugin.first", "unload:dev.plugin.first",
                "close"
        ), provider.events);
        assertTrue(registry.bindingFor("dev.plugin.first").isEmpty());
        assertTrue(registry.bindingFor("dev.plugin.second").isEmpty());
    }

    /// Retains an incomplete registration across payload, Provider, and registry cleanup failures until retry succeeds.
    ///
    /// @param temporaryDirectory isolated payload paths
    /// @throws Exception if fixture setup or the final retry fails unexpectedly
    @Test
    public void retryIncompleteRegistrationClose(@TempDir Path temporaryDirectory) throws Exception {
        RuntimeProviderRegistry registry = new RuntimeProviderRegistry();
        RuntimeSupervisor supervisor = new RuntimeSupervisor(registry);
        RecordingProvider provider = new RecordingProvider("dev.host.rust", true);
        provider.failNextPayloadUnload = true;
        provider.failNextClose = true;
        advanceToBootstrap(supervisor, "dev.host.rust");
        RuntimeProviderRegistration registration = supervisor.register("dev.host.rust", provider);
        supervisor.activate(registration);
        registry.bind("dev.plugin.loaded", requirement("rust"));
        registry.bind("dev.plugin.bound-only", requirement("rust"));
        RuntimePayloadHandle handle = supervisor.loadPayload(
                "dev.plugin.loaded", payloadContext("dev.plugin.loaded", temporaryDirectory));
        supervisor.enablePayload(handle);

        assertThrows(IOException.class, registration::close);
        assertFalse(registration.isClosed());
        assertSame(provider, registry.findById("dev.host.rust").orElseThrow());
        assertTrue(registry.bindingFor("dev.plugin.loaded").isPresent());
        assertEquals(0, provider.events.stream().filter("close"::equals).count());

        assertThrows(IOException.class, registration::close);
        assertFalse(registration.isClosed());
        assertTrue(registry.bindingFor("dev.plugin.loaded").isEmpty());
        assertEquals(1, provider.events.stream().filter("close"::equals).count());

        assertThrows(IOException.class, registration::close);
        assertFalse(registration.isClosed());
        assertEquals(2, provider.events.stream().filter("close"::equals).count());
        assertSame(provider, registry.findById("dev.host.rust").orElseThrow());

        registry.unbind("dev.plugin.bound-only");
        registration.close();

        assertTrue(registration.isClosed());
        assertTrue(registry.findById("dev.host.rust").isEmpty());
        assertEquals(RuntimeProviderState.STOPPED, supervisor.state("dev.host.rust").orElseThrow());
        assertEquals(2, provider.events.stream().filter("unload:dev.plugin.loaded"::equals).count());
        assertEquals(2, provider.events.stream().filter("close"::equals).count());
    }

    /// Serializes a blocked payload load with registration close so the returned handle is tracked and released.
    ///
    /// @param temporaryDirectory isolated payload paths
    /// @throws Exception if fixture setup, synchronization, or lifecycle completion fails
    @Test
    public void serializePayloadLoadWithRegistrationClose(@TempDir Path temporaryDirectory) throws Exception {
        RuntimeProviderRegistry registry = new RuntimeProviderRegistry();
        RuntimeSupervisor supervisor = new RuntimeSupervisor(registry);
        RecordingProvider provider = new RecordingProvider("dev.host.rust", true);
        CountDownLatch loadEntered = new CountDownLatch(1);
        CountDownLatch releaseLoad = new CountDownLatch(1);
        CountDownLatch closeEntered = new CountDownLatch(1);
        provider.blockLoad(loadEntered, releaseLoad);
        provider.closeEntered = closeEntered;
        advanceToBootstrap(supervisor, "dev.host.rust");
        RuntimeProviderRegistration registration = supervisor.register("dev.host.rust", provider);
        supervisor.activate(registration);
        registry.bind("dev.plugin.loaded", requirement("rust"));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<RuntimePayloadHandle> loading = executor.submit(() -> supervisor.loadPayload(
                    "dev.plugin.loaded", payloadContext("dev.plugin.loaded", temporaryDirectory)));
            assertTrue(loadEntered.await(5, TimeUnit.SECONDS));
            Future<Void> closing = executor.submit(() -> {
                registration.close();
                return null;
            });

            assertFalse(closeEntered.await(200, TimeUnit.MILLISECONDS));
            releaseLoad.countDown();

            assertEquals("dev.plugin.loaded", loading.get(5, TimeUnit.SECONDS).ownerPluginId());
            closing.get(5, TimeUnit.SECONDS);
            assertTrue(registration.isClosed());
            assertEquals(1, provider.events.stream().filter("unload:dev.plugin.loaded"::equals).count());
            assertEquals(1, provider.events.stream().filter("close"::equals).count());
        } finally {
            releaseLoad.countDown();
            executor.shutdownNow();
        }
    }

    /// Allows an unrelated Provider to make progress while another Provider callback is blocked.
    ///
    /// @param temporaryDirectory isolated payload paths
    /// @throws Exception if fixture setup, synchronization, or lifecycle completion fails
    @Test
    public void isolateLifecycleSerializationByProvider(@TempDir Path temporaryDirectory) throws Exception {
        RuntimeProviderRegistry registry = new RuntimeProviderRegistry();
        RuntimeSupervisor supervisor = new RuntimeSupervisor(registry);
        RecordingProvider rustProvider = new RecordingProvider("dev.host.rust", "rust", true);
        RecordingProvider pythonProvider = new RecordingProvider("dev.host.python", "python", true);
        CountDownLatch rustLoadEntered = new CountDownLatch(1);
        CountDownLatch releaseRustLoad = new CountDownLatch(1);
        rustProvider.blockLoad(rustLoadEntered, releaseRustLoad);
        advanceToBootstrap(supervisor, "dev.host.rust");
        RuntimeProviderRegistration rustRegistration = supervisor.register("dev.host.rust", rustProvider);
        supervisor.activate(rustRegistration);
        advanceToBootstrap(supervisor, "dev.host.python");
        RuntimeProviderRegistration pythonRegistration = supervisor.register("dev.host.python", pythonProvider);
        supervisor.activate(pythonRegistration);
        registry.bind("dev.plugin.rust", requirement("rust"));
        registry.bind("dev.plugin.python", requirement("python"));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<RuntimePayloadHandle> rustLoad = executor.submit(() -> supervisor.loadPayload(
                    "dev.plugin.rust", payloadContext("dev.plugin.rust", temporaryDirectory)));
            assertTrue(rustLoadEntered.await(5, TimeUnit.SECONDS));

            Future<RuntimePayloadHandle> pythonLoad = executor.submit(() -> supervisor.loadPayload(
                    "dev.plugin.python", payloadContext("dev.plugin.python", temporaryDirectory)));
            assertEquals("dev.plugin.python", pythonLoad.get(1, TimeUnit.SECONDS).ownerPluginId());

            releaseRustLoad.countDown();
            RuntimePayloadHandle rustHandle = rustLoad.get(5, TimeUnit.SECONDS);
            supervisor.unloadPayload(rustHandle);
            supervisor.unloadPayload(pythonLoad.get());
            rustRegistration.close();
            pythonRegistration.close();
        } finally {
            releaseRustLoad.countDown();
            executor.shutdownNow();
        }
    }

    /// Compensates a Provider-owned handle when post-callback ownership validation rejects publication.
    ///
    /// @param temporaryDirectory isolated payload paths
    /// @throws Exception if fixture setup or registration cleanup fails
    @Test
    public void unloadPayloadHandleRejectedAfterLoad(@TempDir Path temporaryDirectory) throws Exception {
        RuntimeProviderRegistry registry = new RuntimeProviderRegistry();
        RuntimeSupervisor supervisor = new RuntimeSupervisor(registry);
        RecordingProvider provider = new RecordingProvider("dev.host.rust", true);
        provider.returnWrongPayloadOwner = true;
        advanceToBootstrap(supervisor, "dev.host.rust");
        RuntimeProviderRegistration registration = supervisor.register("dev.host.rust", provider);
        supervisor.activate(registration);
        registry.bind("dev.plugin.loaded", requirement("rust"));

        assertThrows(IOException.class, () -> supervisor.loadPayload(
                "dev.plugin.loaded", payloadContext("dev.plugin.loaded", temporaryDirectory)));

        assertEquals(1, provider.events.stream().filter("unload:dev.plugin.wrong"::equals).count());
        registry.unbind("dev.plugin.loaded");
        registration.close();
    }

    /// Serializes concurrent enable, disable, and unload requests without duplicating callbacks or leaking a handle.
    ///
    /// @param temporaryDirectory isolated payload paths
    /// @throws Exception if fixture setup, synchronization, or lifecycle completion fails
    @Test
    public void serializeConcurrentPayloadMutations(@TempDir Path temporaryDirectory) throws Exception {
        RuntimeProviderRegistry registry = new RuntimeProviderRegistry();
        RuntimeSupervisor supervisor = new RuntimeSupervisor(registry);
        RecordingProvider provider = new RecordingProvider("dev.host.rust", true);
        advanceToBootstrap(supervisor, "dev.host.rust");
        RuntimeProviderRegistration registration = supervisor.register("dev.host.rust", provider);
        supervisor.activate(registration);
        registry.bind("dev.plugin.loaded", requirement("rust"));
        RuntimePayloadHandle handle = supervisor.loadPayload(
                "dev.plugin.loaded", payloadContext("dev.plugin.loaded", temporaryDirectory));
        CountDownLatch enableEntered = new CountDownLatch(1);
        CountDownLatch releaseEnable = new CountDownLatch(1);
        provider.blockEnable(enableEntered, releaseEnable);
        ExecutorService executor = Executors.newFixedThreadPool(3);
        try {
            Future<Void> enabling = executor.submit(() -> {
                supervisor.enablePayload(handle);
                return null;
            });
            assertTrue(enableEntered.await(5, TimeUnit.SECONDS));
            Future<Void> disabling = executor.submit(() -> {
                supervisor.disablePayload(handle);
                return null;
            });
            Future<Void> unloading = executor.submit(() -> {
                supervisor.unloadPayload(handle);
                return null;
            });

            releaseEnable.countDown();
            enabling.get(5, TimeUnit.SECONDS);
            awaitConcurrentMutation(disabling);
            unloading.get(5, TimeUnit.SECONDS);

            assertEquals(1, provider.events.stream().filter("enable:dev.plugin.loaded"::equals).count());
            assertEquals(1, provider.events.stream().filter("disable:dev.plugin.loaded"::equals).count());
            assertEquals(1, provider.events.stream().filter("unload:dev.plugin.loaded"::equals).count());
            assertThrows(IOException.class, () -> supervisor.unloadPayload(handle));
            assertTrue(registry.bindingFor("dev.plugin.loaded").isEmpty());
            registration.close();
        } finally {
            releaseEnable.countDown();
            executor.shutdownNow();
        }
    }

    /// Rejects an operation which captured an old registration before an identical handle was reissued.
    ///
    /// @param mutation stale payload operation to exercise
    /// @param temporaryDirectory isolated payload paths
    /// @throws Exception if fixture setup, synchronization, replacement, or cleanup fails
    @ParameterizedTest
    @EnumSource(PayloadMutation.class)
    public void rejectReissuedPayloadHandleFromOldRegistration(
            PayloadMutation mutation,
            @TempDir Path temporaryDirectory
    ) throws Exception {
        RuntimeProviderRegistry registry = new RuntimeProviderRegistry();
        RuntimeSupervisor supervisor = new RuntimeSupervisor(registry);
        RecordingProvider originalProvider = new RecordingProvider("dev.host.rust", true);
        advanceToBootstrap(supervisor, "dev.host.rust");
        RuntimeProviderRegistration originalRegistration = supervisor.register("dev.host.rust", originalProvider);
        supervisor.activate(originalRegistration);
        registry.bind("dev.plugin.loaded", requirement("rust"));
        RuntimePayloadHandle handle = supervisor.loadPayload(
                "dev.plugin.loaded", payloadContext("dev.plugin.loaded", temporaryDirectory));
        if (mutation == PayloadMutation.DISABLE) {
            supervisor.enablePayload(handle);
        }

        CompletableFuture<Thread> workerThread = new CompletableFuture<>();
        ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "runtime-supervisor-stale-handle");
            workerThread.complete(thread);
            return thread;
        });
        try {
            Future<Void> staleMutation;
            RecordingProvider replacementProvider;
            RuntimeProviderRegistration replacementRegistration;
            synchronized (originalRegistration.lifecycleLock()) {
                staleMutation = executor.submit(() -> {
                    mutation.invoke(supervisor, handle);
                    return null;
                });
                awaitBlockedOn(
                        workerThread.get(5, TimeUnit.SECONDS),
                        originalRegistration.lifecycleLock()
                );

                originalRegistration.close();
                replacementProvider = new RecordingProvider("dev.host.rust", true);
                advanceToBootstrap(supervisor, "dev.host.rust");
                replacementRegistration = supervisor.register("dev.host.rust", replacementProvider);
                supervisor.activate(replacementRegistration);
                registry.bind("dev.plugin.loaded", requirement("rust"));
                assertEquals(handle, supervisor.loadPayload(
                        "dev.plugin.loaded", payloadContext("dev.plugin.loaded", temporaryDirectory)));
            }

            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> staleMutation.get(5, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof IllegalStateException);
            assertTrue(Objects.requireNonNull(failure.getCause().getMessage()).contains("stale runtime payload handle"));
            assertFalse(replacementProvider.events.contains(mutation.providerEvent()));

            replacementRegistration.close();
            assertTrue(replacementRegistration.isClosed());
        } finally {
            executor.shutdownNow();
        }
    }

    /// Restores the previous Host registration after a replacement fails health negotiation.
    @Test
    public void restorePreviousHostAfterFailedUpdate() throws Exception {
        RuntimeProviderRegistry registry = new RuntimeProviderRegistry();
        RuntimeSupervisor supervisor = new RuntimeSupervisor(registry);
        RecordingProvider original = new RecordingProvider("dev.host.rust", true);
        advanceToBootstrap(supervisor, "dev.host.rust");
        RuntimeProviderRegistration originalRegistration = supervisor.register("dev.host.rust", original);
        supervisor.activate(originalRegistration);
        originalRegistration.close();

        RecordingProvider replacement = new RecordingProvider("dev.host.rust", false);
        advanceToBootstrap(supervisor, "dev.host.rust");
        RuntimeProviderRegistration replacementRegistration = supervisor.register("dev.host.rust", replacement);
        assertThrows(IOException.class, () -> supervisor.activate(replacementRegistration));

        RecordingProvider restored = new RecordingProvider("dev.host.rust", true);
        advanceToBootstrap(supervisor, "dev.host.rust");
        RuntimeProviderRegistration restoredRegistration = supervisor.register("dev.host.rust", restored);
        supervisor.activate(restoredRegistration);

        assertSame(restored, registry.findById("dev.host.rust").orElseThrow());
        assertEquals(RuntimeProviderState.READY, supervisor.state("dev.host.rust").orElseThrow());
        assertFalse(restoredRegistration.isClosed());
    }

    /// Advances one Provider through discovery, resolution, and Java bootstrap loading.
    ///
    /// @param supervisor lifecycle owner
    /// @param providerId Provider plugin ID
    private static void advanceToBootstrap(RuntimeSupervisor supervisor, String providerId) {
        supervisor.discover(providerId);
        supervisor.resolve(providerId);
        supervisor.bootstrapLoaded(providerId);
    }

    /// Creates the runtime requirement used by fake Rust dependents.
    ///
    /// @param runtime canonical runtime
    /// @return embedded Bridge requirement
    private static RuntimeRequirement requirement(String runtime) {
        return new RuntimeRequirement(runtime, PluginAbi.ABI_2, 1, PluginExecutionMode.EMBEDDED,
                Set.of(RuntimeFeature.BRIDGE), null);
    }

    /// Creates an exact immutable payload context under one temporary directory.
    ///
    /// @param pluginId dependent plugin ID
    /// @param temporaryDirectory isolated test root
    /// @return payload loading context
    private static RuntimePayloadContext payloadContext(String pluginId, Path temporaryDirectory) {
        PluginArtifactIdentity identity = new PluginArtifactIdentity(pluginId, "1.0.0", "a".repeat(64));
        return new RuntimePayloadContext(
                identity,
                temporaryDirectory.resolve(pluginId).resolve("package"),
                "payload/plugin.dll",
                PluginExecutionMode.EMBEDDED,
                temporaryDirectory.resolve(pluginId).resolve("data"),
                () -> capabilityToken(identity)
        );
    }

    /// Issues one opaque token for a payload-context fixture.
    ///
    /// @param identity exact test artifact identity
    /// @return opaque token
    private static PluginCapabilityToken capabilityToken(PluginArtifactIdentity identity) {
        return new PluginPermissionAuthority().issue(
                identity,
                PluginExecutionMode.EMBEDDED,
                Set.of(),
                "runtime.payload",
                Duration.ofMinutes(1)
        );
    }

    /// Creates one capability-authorized Hook endpoint backed by an exact Supervisor payload record.
    ///
    /// @param supervisor lifecycle owner
    /// @param identity exact payload identity
    /// @param authority launcher-owned capability authority
    /// @return supervised Hook endpoint
    /// @throws IOException if no current payload record exists
    private static RuntimeHookEndpoint hookEndpoint(
            RuntimeSupervisor supervisor,
            PluginArtifactIdentity identity,
            PluginPermissionAuthority authority
    ) throws IOException {
        return new RuntimeHookEndpoint(
                identity,
                PluginExecutionMode.EMBEDDED,
                authority,
                () -> authority.issue(
                        identity,
                        PluginExecutionMode.EMBEDDED,
                        Set.of(PluginPermission.LAUNCHER_HOOK),
                        RuntimeHookEndpoint.CALLBACK_DOMAIN,
                        Duration.ofMinutes(1)
                ),
                supervisor.hookInvoker(identity.getPluginId())
        );
    }

    /// Creates one immutable Hook event for an exact external payload.
    ///
    /// @param pluginId event secret owner
    /// @return immutable Hook event
    private static PluginHookEvent hookEvent(String pluginId) {
        return new PluginHookEvent(
                PluginHookEvent.CURRENT_CONTRACT_VERSION,
                "runtime-supervisor-hook",
                PluginHookPoint.BEFORE_GAME_LAUNCH,
                Instant.EPOCH,
                PluginDataObject.empty(),
                PluginSecretAccess.denied(pluginId)
        );
    }

    /// Awaits a mutation which may lose a valid race to an unload that removed the shared handle.
    ///
    /// @param mutation concurrent payload mutation
    /// @throws Exception if waiting times out or the mutation fails for any reason except an unknown handle
    private static void awaitConcurrentMutation(Future<Void> mutation) throws Exception {
        try {
            mutation.get(5, TimeUnit.SECONDS);
        } catch (ExecutionException exception) {
            if (!(exception.getCause() instanceof IOException ioException)
                    || !ioException.getMessage().contains("Unknown runtime payload handle")) {
                throw exception;
            }
        }
    }

    /// Waits until one worker has completed its initial payload lookup and is blocked on an exact lifecycle monitor.
    ///
    /// @param thread payload mutation worker
    /// @param monitor expected lifecycle monitor
    /// @throws InterruptedException if the test thread is interrupted
    private static void awaitBlockedOn(Thread thread, Object monitor) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        int expectedMonitorIdentity = System.identityHashCode(monitor);
        while (true) {
            @Nullable ThreadInfo threadInfo = ManagementFactory.getThreadMXBean().getThreadInfo(thread.getId());
            @Nullable LockInfo lockInfo = threadInfo == null ? null : threadInfo.getLockInfo();
            if (thread.getState() == Thread.State.BLOCKED
                    && lockInfo != null
                    && lockInfo.getIdentityHashCode() == expectedMonitorIdentity) {
                return;
            }
            if (!thread.isAlive()) {
                throw new AssertionError("Payload mutation worker exited before blocking on the lifecycle monitor");
            }
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("Payload mutation worker did not block on the lifecycle monitor");
            }
            Thread.sleep(10);
        }
    }

    /// Payload operation whose old-registration race is exercised by the parameterized concurrency test.
    @NotNullByDefault
    private enum PayloadMutation {
        /// Enables a disabled payload.
        ENABLE,

        /// Disables an enabled payload.
        DISABLE,

        /// Unloads a payload and removes its binding.
        UNLOAD;

        /// Invokes this operation on one exact handle.
        ///
        /// @param supervisor lifecycle owner
        /// @param handle payload handle
        /// @throws IOException if the Supervisor rejects or cannot complete the operation
        private void invoke(RuntimeSupervisor supervisor, RuntimePayloadHandle handle) throws IOException {
            switch (this) {
                case ENABLE -> supervisor.enablePayload(handle);
                case DISABLE -> supervisor.disablePayload(handle);
                case UNLOAD -> supervisor.unloadPayload(handle);
            }
        }

        /// Returns the callback marker which must not reach the replacement Provider.
        ///
        /// @return replacement Provider callback marker
        private String providerEvent() {
            return switch (this) {
                case ENABLE -> "enable:dev.plugin.loaded";
                case DISABLE -> "disable:dev.plugin.loaded";
                case UNLOAD -> "unload:dev.plugin.loaded";
            };
        }
    }

    /// Runtime Provider fixture which supports payload lifecycle but intentionally exposes no Hook transport.
    @NotNullByDefault
    private static final class LifecycleOnlyProvider implements RuntimeProvider {
        /// Immutable fake Provider descriptor.
        private final RuntimeProviderDescriptor descriptor;

        /// Creates one lifecycle-only embedded Rust Provider.
        ///
        /// @param providerId Provider plugin ID
        private LifecycleOnlyProvider(String providerId) {
            descriptor = new RuntimeProviderDescriptor(
                    providerId,
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
        }

        /// Returns the immutable fake descriptor.
        ///
        /// @return Provider descriptor
        @Override
        public RuntimeProviderDescriptor descriptor() {
            return descriptor;
        }

        /// Returns one opaque payload handle for the supplied context.
        ///
        /// @param context immutable payload loading context
        /// @return opaque payload handle
        @Override
        public RuntimePayloadHandle loadPayload(RuntimePayloadContext context) {
            return new RuntimePayloadHandle(
                    context.artifactIdentity().getPluginId(),
                    descriptor.providerId(),
                    "lifecycle-only-payload"
            );
        }

        /// Enables the loaded payload without additional behavior.
        ///
        /// @param handle provider-owned payload handle
        @Override
        public void enablePayload(RuntimePayloadHandle handle) {
        }

        /// Disables the enabled payload without additional behavior.
        ///
        /// @param handle provider-owned payload handle
        @Override
        public void disablePayload(RuntimePayloadHandle handle) {
        }

        /// Unloads the disabled payload without additional behavior.
        ///
        /// @param handle provider-owned payload handle
        @Override
        public void unloadPayload(RuntimePayloadHandle handle) {
        }
    }

    /// Recording Provider fixture with deterministic health and opaque payload IDs.
    @NotNullByDefault
    private static final class RecordingProvider implements RuntimeProvider, RuntimeProvider.HookInvoker {
        /// Immutable fake Provider descriptor.
        private final RuntimeProviderDescriptor descriptor;

        /// Health result returned during activation.
        private final boolean healthy;

        /// Ordered Provider callbacks.
        private final List<String> events = new CopyOnWriteArrayList<>();

        /// Whether the next payload unload callback must fail before releasing its handle.
        private boolean failNextPayloadUnload;

        /// Whether the next Provider close callback must fail before cleanup completes.
        private boolean failNextClose;

        /// Whether the next payload enable callback must fail.
        private boolean failNextPayloadEnable;

        /// Whether the next load callback must return a handle owned by another plugin.
        private boolean returnWrongPayloadOwner;

        /// Optional signal emitted when payload loading enters the Provider callback.
        private @Nullable CountDownLatch loadEntered;

        /// Optional gate which blocks payload loading until the test releases it.
        private @Nullable CountDownLatch releaseLoad;

        /// Optional signal emitted when payload enablement enters the Provider callback.
        private @Nullable CountDownLatch enableEntered;

        /// Optional gate which blocks payload enablement until the test releases it.
        private @Nullable CountDownLatch releaseEnable;

        /// Optional signal emitted when Hook invocation enters Provider code.
        private @Nullable CountDownLatch hookEntered;

        /// Optional gate which blocks Hook invocation until the test releases it.
        private @Nullable CountDownLatch releaseHook;

        /// Optional signal emitted when Provider shutdown enters the callback.
        private @Nullable CountDownLatch closeEntered;

        /// Creates a fake embedded Rust Provider.
        ///
        /// @param providerId Provider plugin ID
        /// @param healthy health result
        private RecordingProvider(String providerId, boolean healthy) {
            this(providerId, "rust", healthy);
        }

        /// Creates a fake embedded Provider for one exact runtime.
        ///
        /// @param providerId Provider plugin ID
        /// @param runtime canonical provided runtime
        /// @param healthy health result
        private RecordingProvider(String providerId, String runtime, boolean healthy) {
            this.descriptor = new RuntimeProviderDescriptor(
                    providerId,
                    "1.0.0",
                    List.of(new RuntimeProviderDeclaration(
                            runtime,
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
            this.healthy = healthy;
        }

        /// Configures the next payload load callback to block between the supplied latches.
        ///
        /// @param entered signal emitted on callback entry
        /// @param release gate allowing callback completion
        private void blockLoad(CountDownLatch entered, CountDownLatch release) {
            loadEntered = entered;
            releaseLoad = release;
        }

        /// Configures the next payload enable callback to block between the supplied latches.
        ///
        /// @param entered signal emitted on callback entry
        /// @param release gate allowing callback completion
        private void blockEnable(CountDownLatch entered, CountDownLatch release) {
            enableEntered = entered;
            releaseEnable = release;
        }

        /// Configures the next Hook callback to block between the supplied latches.
        ///
        /// @param entered signal emitted on callback entry
        /// @param release gate allowing callback completion
        private void blockHook(CountDownLatch entered, CountDownLatch release) {
            hookEntered = entered;
            releaseHook = release;
        }

        /// Returns the immutable fake descriptor.
        @Override
        public RuntimeProviderDescriptor descriptor() {
            return descriptor;
        }

        /// Records Provider initialization.
        @Override
        public void initialize() {
            events.add("initialize");
        }

        /// Records and returns the configured health result.
        @Override
        public boolean healthCheck() {
            events.add("health");
            return healthy;
        }

        /// Records payload loading and returns an opaque owner/provider/payload tuple.
        @Override
        public RuntimePayloadHandle loadPayload(RuntimePayloadContext context) throws IOException {
            String pluginId = context.artifactIdentity().getPluginId();
            events.add("load:" + pluginId);
            awaitCallback(loadEntered, releaseLoad);
            String returnedOwner = returnWrongPayloadOwner ? "dev.plugin.wrong" : pluginId;
            return new RuntimePayloadHandle(returnedOwner, descriptor.providerId(), "payload-" + pluginId);
        }

        /// Records payload enablement.
        @Override
        public void enablePayload(RuntimePayloadHandle handle) throws IOException {
            events.add("enable:" + handle.ownerPluginId());
            if (failNextPayloadEnable) {
                failNextPayloadEnable = false;
                throw new IOException("configured payload enable failure");
            }
            awaitCallback(enableEntered, releaseEnable);
        }

        /// Records payload disablement.
        @Override
        public void disablePayload(RuntimePayloadHandle handle) {
            events.add("disable:" + handle.ownerPluginId());
        }

        /// Records payload unloading.
        @Override
        public void unloadPayload(RuntimePayloadHandle handle) throws IOException {
            events.add("unload:" + handle.ownerPluginId());
            if (failNextPayloadUnload) {
                failNextPayloadUnload = false;
                throw new IOException("configured payload unload failure");
            }
        }

        /// Records one exact handle-aware external Hook invocation.
        ///
        /// @param handle exact current payload handle
        /// @param token short-lived payload capability token
        /// @param event immutable Hook event
        /// @param timeout dispatcher callback deadline
        /// @return unchanged result
        /// @throws IOException if callback coordination is interrupted or times out
        @Override
        public PluginHookResult invokeHook(
                RuntimePayloadHandle handle,
                PluginCapabilityToken token,
                PluginHookEvent event,
                Duration timeout
        ) throws IOException {
            events.add("hook:" + handle.ownerPluginId());
            awaitCallback(hookEntered, releaseHook);
            return PluginHookResult.unchanged();
        }

        /// Records Provider shutdown.
        @Override
        public void close() throws IOException {
            events.add("close");
            @Nullable CountDownLatch entered = closeEntered;
            if (entered != null) {
                entered.countDown();
            }
            if (failNextClose) {
                failNextClose = false;
                throw new IOException("configured Provider close failure");
            }
        }

        /// Signals callback entry and waits for its optional release gate with a bounded timeout.
        ///
        /// @param entered optional callback-entry signal
        /// @param release optional callback release gate
        /// @throws IOException if the callback is interrupted or its release times out
        private static void awaitCallback(
                @Nullable CountDownLatch entered,
                @Nullable CountDownLatch release
        ) throws IOException {
            if (entered != null) {
                entered.countDown();
            }
            if (release == null) {
                return;
            }
            try {
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new IOException("Timed out waiting to release Provider callback");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while waiting to release Provider callback", exception);
            }
        }
    }
}
