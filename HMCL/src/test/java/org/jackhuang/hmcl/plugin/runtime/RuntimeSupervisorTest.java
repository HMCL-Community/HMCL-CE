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
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.lang.management.LockInfo;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
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

    /// Cancels an admitted cooperative Hook callback before payload disablement reaches Provider code.
    ///
    /// @param temporaryDirectory isolated payload paths
    /// @throws Exception if callback coordination or lifecycle cleanup fails
    @Test
    public void cancelRunningHookBeforePayloadDisable(@TempDir Path temporaryDirectory) throws Exception {
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

            disabling.get(5, TimeUnit.SECONDS);
            ExecutionException failure = assertThrows(
                    ExecutionException.class,
                    () -> hook.get(5, TimeUnit.SECONDS)
            );
            assertTrue(failure.getCause() instanceof CancellationException);
            assertTrue(provider.events.indexOf("hook:" + payloadId)
                    < provider.events.indexOf("disable:" + payloadId));
        } finally {
            releaseHook.countDown();
            executor.shutdownNow();
            supervisor.unloadPayload(handle);
            registration.close();
        }
    }

    /// Allows a second payload callback through a shared Host while the first payload ignores interruption.
    ///
    /// @param temporaryDirectory isolated payload paths
    /// @throws Exception if callback coordination or lifecycle cleanup fails
    @Test
    public void isolateSharedHostPayloadHooks(@TempDir Path temporaryDirectory) throws Exception {
        String firstPayloadId = "dev.plugin.hook-shared-first";
        String secondPayloadId = "dev.plugin.hook-shared-second";
        RuntimeProviderRegistry registry = new RuntimeProviderRegistry();
        RuntimeSupervisor supervisor = new RuntimeSupervisor(registry);
        RecordingProvider provider = new RecordingProvider("dev.host.rust", true);
        advanceToBootstrap(supervisor, "dev.host.rust");
        RuntimeProviderRegistration registration = supervisor.register("dev.host.rust", provider);
        supervisor.activate(registration);
        registry.bind(firstPayloadId, requirement("rust"));
        registry.bind(secondPayloadId, requirement("rust"));
        RuntimePayloadHandle firstHandle = supervisor.loadPayload(
                firstPayloadId, payloadContext(firstPayloadId, temporaryDirectory));
        RuntimePayloadHandle secondHandle = supervisor.loadPayload(
                secondPayloadId, payloadContext(secondPayloadId, temporaryDirectory));
        supervisor.enablePayload(firstHandle);
        supervisor.enablePayload(secondHandle);
        CountDownLatch firstHookEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstHook = new CountDownLatch(1);
        provider.blockHookIgnoringInterrupt(firstPayloadId, firstHookEntered, releaseFirstHook);
        RuntimeHookEndpoint.ProviderInvoker firstInvoker = supervisor.hookInvoker(firstPayloadId);
        RuntimeHookEndpoint.ProviderInvoker secondInvoker = supervisor.hookInvoker(secondPayloadId);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            TestCancellationSignal firstCancellation = new TestCancellationSignal(registration.lifecycleLock());
            Future<@Nullable PluginHookResult> firstHook = executor.submit(() -> firstInvoker.invokeHook(
                    firstPayloadId,
                    capabilityToken(new PluginArtifactIdentity(firstPayloadId, "1.0.0", "a".repeat(64))),
                    hookEvent(firstPayloadId),
                    Duration.ofSeconds(1),
                    firstCancellation
            ));
            assertTrue(firstHookEntered.await(5, TimeUnit.SECONDS));

            PluginHookResult secondResult = secondInvoker.invokeHook(
                    secondPayloadId,
                    capabilityToken(new PluginArtifactIdentity(secondPayloadId, "1.0.0", "a".repeat(64))),
                    hookEvent(secondPayloadId),
                    Duration.ofSeconds(1),
                    new TestCancellationSignal(registration.lifecycleLock())
            );

            assertEquals(PluginHookResult.Action.UNCHANGED, secondResult.action());
            firstCancellation.cancel();
            assertFalse(firstHook.isDone());
            releaseFirstHook.countDown();
            assertFutureCancelled(firstHook);
        } finally {
            releaseFirstHook.countDown();
            executor.shutdownNow();
            supervisor.unloadPayload(secondHandle);
            supervisor.unloadPayload(firstHandle);
            registration.close();
        }
    }

    /// Bounds payload unload, revokes exact authority, and discards late Provider success or failure.
    ///
    /// @param failHook whether the detached Provider callback fails after its release
    /// @param temporaryDirectory isolated payload paths
    /// @throws Exception if callback coordination or lifecycle cleanup fails
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    public void discardLateHookCompletionAfterBoundedPayloadUnload(
            boolean failHook,
            @TempDir Path temporaryDirectory
    ) throws Exception {
        String payloadId = failHook
                ? "dev.plugin.hook-unload-late-error"
                : "dev.plugin.hook-unload-late-success";
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
        provider.blockHookIgnoringInterrupt(payloadId, hookEntered, releaseHook);
        if (failHook) {
            provider.failHookAfterRelease(payloadId);
        }
        PluginArtifactIdentity identity = new PluginArtifactIdentity(payloadId, "1.0.0", "a".repeat(64));
        PluginPermissionAuthority authority = new PluginPermissionAuthority();
        RuntimeHookEndpoint endpoint = hookEndpoint(supervisor, identity, authority);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<PluginHookResult> hook = executor.submit(() ->
                    endpoint.invoke(hookEvent(payloadId), Duration.ofSeconds(2)));
            assertTrue(hookEntered.await(5, TimeUnit.SECONDS));
            Future<Void> unloading = executor.submit(() -> {
                supervisor.unloadPayload(handle);
                return null;
            });

            unloading.get(1, TimeUnit.SECONDS);
            assertFalse(hook.isDone());
            PluginCapabilityToken token = provider.hookToken(payloadId);
            assertThrows(SecurityException.class, () -> authority.requirePermission(
                    token,
                    payloadId,
                    identity,
                    PluginExecutionMode.EMBEDDED,
                    PluginPermission.LAUNCHER_HOOK,
                    RuntimeHookEndpoint.CALLBACK_DOMAIN
            ));
            releaseHook.countDown();
            assertFutureCancelled(hook);
            assertThrows(SecurityException.class, () -> authority.requirePermission(
                    token,
                    payloadId,
                    identity,
                    PluginExecutionMode.EMBEDDED,
                    PluginPermission.LAUNCHER_HOOK,
                    RuntimeHookEndpoint.CALLBACK_DOMAIN
            ));
        } finally {
            releaseHook.countDown();
            executor.shutdownNow();
            registration.close();
        }
    }

    /// Cancels callback authority outside the Provider lifecycle monitor during bounded Host teardown.
    ///
    /// @param temporaryDirectory isolated payload paths
    /// @throws Exception if callback coordination or lifecycle cleanup fails
    @Test
    public void cancelHookOutsideLifecycleMonitorDuringHostClose(@TempDir Path temporaryDirectory) throws Exception {
        String payloadId = "dev.plugin.hook-host-close";
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
        provider.blockHookIgnoringInterrupt(payloadId, hookEntered, releaseHook);
        TestCancellationSignal cancellation = new TestCancellationSignal(registration.lifecycleLock());
        PluginArtifactIdentity identity = new PluginArtifactIdentity(payloadId, "1.0.0", "a".repeat(64));
        RuntimeHookEndpoint.ProviderInvoker invoker = supervisor.hookInvoker(payloadId);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<@Nullable PluginHookResult> hook = executor.submit(() -> invoker.invokeHook(
                    payloadId,
                    capabilityToken(identity),
                    hookEvent(payloadId),
                    Duration.ofSeconds(1),
                    cancellation
            ));
            assertTrue(hookEntered.await(5, TimeUnit.SECONDS));
            Future<Void> closing = executor.submit(() -> {
                registration.close();
                return null;
            });

            closing.get(1, TimeUnit.SECONDS);
            assertFalse(cancellation.cancelledWhileHoldingMonitor());
            assertTrue(registration.isClosed());
            assertFalse(hook.isDone());

            RecordingProvider replacementProvider = new RecordingProvider("dev.host.rust", true);
            advanceToBootstrap(supervisor, "dev.host.rust");
            RuntimeProviderRegistration replacementRegistration =
                    supervisor.register("dev.host.rust", replacementProvider);
            supervisor.activate(replacementRegistration);
            assertSame(replacementProvider, registry.findById("dev.host.rust").orElseThrow());

            releaseHook.countDown();
            assertFutureCancelled(hook);
            replacementRegistration.close();
        } finally {
            releaseHook.countDown();
            executor.shutdownNow();
        }
    }

    /// Rejects a callback completion after Host close enters `STOPPING` but before payload cancellation can begin.
    ///
    /// @param temporaryDirectory isolated payload paths
    /// @throws Exception if callback coordination or lifecycle cleanup fails
    @Test
    public void rejectHookCompletionAfterHostCloseStarts(@TempDir Path temporaryDirectory) throws Exception {
        String payloadId = "dev.plugin.hook-close-started";
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
        TestCancellationSignal cancellation = new TestCancellationSignal(registration.lifecycleLock());
        PluginArtifactIdentity identity = new PluginArtifactIdentity(payloadId, "1.0.0", "a".repeat(64));
        RuntimeHookEndpoint.ProviderInvoker invoker = supervisor.hookInvoker(payloadId);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        FutureTask<Void> closing = new FutureTask<>(() -> {
            registration.close();
            return null;
        });
        Thread closingThread = new Thread(closing, "runtime-provider-close-test");
        boolean closingStarted = false;
        try {
            Future<@Nullable PluginHookResult> hook = executor.submit(() -> invoker.invokeHook(
                    payloadId,
                    capabilityToken(identity),
                    hookEvent(payloadId),
                    Duration.ofSeconds(1),
                    cancellation
            ));
            assertTrue(hookEntered.await(5, TimeUnit.SECONDS));
            synchronized (registration.lifecycleLock()) {
                closingThread.start();
                closingStarted = true;
                awaitBlockedOn(closingThread, registration.lifecycleLock());
                assertEquals(RuntimeProviderState.STOPPING, supervisor.state("dev.host.rust").orElseThrow());

                releaseHook.countDown();
                assertFutureCancelled(hook);
            }
            closing.get(2, TimeUnit.SECONDS);
        } finally {
            releaseHook.countDown();
            if (closingStarted) {
                closing.get(2, TimeUnit.SECONDS);
            } else {
                registration.close();
            }
            executor.shutdownNow();
        }
    }

    /// Cancels every shared-Host payload generation before spending one total registration drain budget.
    ///
    /// @param temporaryDirectory isolated payload paths
    /// @throws Exception if callback coordination or bounded Host teardown fails
    @Test
    public void cancelAllSharedHostHooksBeforeRegistrationDrain(@TempDir Path temporaryDirectory) throws Exception {
        String firstPayloadId = "dev.plugin.hook-close-first";
        String secondPayloadId = "dev.plugin.hook-close-second";
        RuntimeProviderRegistry registry = new RuntimeProviderRegistry();
        RuntimeSupervisor supervisor = new RuntimeSupervisor(registry);
        RecordingProvider provider = new RecordingProvider("dev.host.rust", true);
        advanceToBootstrap(supervisor, "dev.host.rust");
        RuntimeProviderRegistration registration = supervisor.register("dev.host.rust", provider);
        supervisor.activate(registration);
        registry.bind(firstPayloadId, requirement("rust"));
        registry.bind(secondPayloadId, requirement("rust"));
        RuntimePayloadHandle firstHandle = supervisor.loadPayload(
                firstPayloadId, payloadContext(firstPayloadId, temporaryDirectory));
        RuntimePayloadHandle secondHandle = supervisor.loadPayload(
                secondPayloadId, payloadContext(secondPayloadId, temporaryDirectory));
        supervisor.enablePayload(firstHandle);
        supervisor.enablePayload(secondHandle);
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch secondEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch releaseSecond = new CountDownLatch(1);
        provider.blockHookIgnoringInterrupt(firstPayloadId, firstEntered, releaseFirst);
        provider.blockHookIgnoringInterrupt(secondPayloadId, secondEntered, releaseSecond);
        TestCancellationSignal firstCancellation = new TestCancellationSignal(registration.lifecycleLock());
        TestCancellationSignal secondCancellation = new TestCancellationSignal(registration.lifecycleLock());
        ExecutorService executor = Executors.newFixedThreadPool(3);
        @Nullable Future<Void> closing = null;
        try {
            Future<@Nullable PluginHookResult> firstHook = executor.submit(() ->
                    supervisor.hookInvoker(firstPayloadId).invokeHook(
                            firstPayloadId,
                            capabilityToken(new PluginArtifactIdentity(
                                    firstPayloadId, "1.0.0", "a".repeat(64))),
                            hookEvent(firstPayloadId),
                            Duration.ofSeconds(2),
                            firstCancellation
                    ));
            Future<@Nullable PluginHookResult> secondHook = executor.submit(() ->
                    supervisor.hookInvoker(secondPayloadId).invokeHook(
                            secondPayloadId,
                            capabilityToken(new PluginArtifactIdentity(
                                    secondPayloadId, "1.0.0", "a".repeat(64))),
                            hookEvent(secondPayloadId),
                            Duration.ofSeconds(2),
                            secondCancellation
                    ));
            assertTrue(firstEntered.await(1, TimeUnit.SECONDS));
            assertTrue(secondEntered.await(1, TimeUnit.SECONDS));
            closing = executor.submit(() -> {
                registration.close();
                return null;
            });

            assertTrue(secondCancellation.awaitCancelled());
            assertTrue(firstCancellation.awaitCancelled());
            closing.get(1, TimeUnit.SECONDS);
            assertFalse(firstHook.isDone());
            assertFalse(secondHook.isDone());
            releaseFirst.countDown();
            releaseSecond.countDown();
            assertFutureCancelled(firstHook);
            assertFutureCancelled(secondHook);
        } finally {
            releaseFirst.countDown();
            releaseSecond.countDown();
            if (closing != null) {
                closing.get(2, TimeUnit.SECONDS);
            }
            executor.shutdownNow();
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

    /// Reopens Hook admission when explicit enablement retries after Provider disablement fails.
    ///
    /// @param temporaryDirectory isolated payload paths
    /// @throws Exception if fixture setup, recovery, or cleanup fails
    @Test
    public void reopenHookAdmissionAfterPayloadDisableFailure(@TempDir Path temporaryDirectory) throws Exception {
        String payloadId = "dev.plugin.hook-disable-recovery";
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
        RuntimeHookEndpoint.ProviderInvoker invoker = supervisor.hookInvoker(payloadId);
        TestCancellationSignal cancellation = new TestCancellationSignal(registration.lifecycleLock());
        provider.failNextPayloadDisable = true;

        assertThrows(IOException.class, () -> supervisor.disablePayload(handle));
        assertThrows(IOException.class, () -> invoker.invokeHook(
                payloadId,
                capabilityToken(new PluginArtifactIdentity(payloadId, "1.0.0", "a".repeat(64))),
                hookEvent(payloadId),
                Duration.ofSeconds(1),
                cancellation
        ));

        supervisor.enablePayload(handle);
        assertEquals(PluginHookResult.Action.UNCHANGED, invoker.invokeHook(
                payloadId,
                capabilityToken(new PluginArtifactIdentity(payloadId, "1.0.0", "a".repeat(64))),
                hookEvent(payloadId),
                Duration.ofSeconds(1),
                new TestCancellationSignal(registration.lifecycleLock())
        ).action());
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

    /// Fails a blocked payload load closed when registration close wins before handle publication.
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

            ExecutionException loadingFailure = assertThrows(
                    ExecutionException.class,
                    () -> loading.get(5, TimeUnit.SECONDS)
            );
            assertTrue(loadingFailure.getCause() instanceof IOException);
            assertTrue(Objects.requireNonNull(loadingFailure.getCause().getMessage()).contains("STOPPING"));
            ExecutionException closeFailure = assertThrows(
                    ExecutionException.class,
                    () -> closing.get(5, TimeUnit.SECONDS)
            );
            assertTrue(closeFailure.getCause() instanceof IOException);
            assertFalse(registration.isClosed());
            registry.unbind("dev.plugin.loaded");
            registration.close();
            assertTrue(registration.isClosed());
            assertEquals(1, provider.events.stream().filter("unload:dev.plugin.loaded"::equals).count());
            assertEquals(1, provider.events.stream().filter("close"::equals).count());
        } finally {
            releaseLoad.countDown();
            executor.shutdownNow();
        }
    }

    /// Closes a Provider only once when registration close races activation rollback.
    ///
    /// @throws Exception if activation or close coordination fails unexpectedly
    @Test
    public void avoidDuplicateProviderCloseDuringActivationRollback() throws Exception {
        RuntimeProviderRegistry registry = new RuntimeProviderRegistry();
        RuntimeSupervisor supervisor = new RuntimeSupervisor(registry);
        RecordingProvider provider = new RecordingProvider("dev.host.rust", true);
        CountDownLatch initializeEntered = new CountDownLatch(1);
        CountDownLatch releaseInitialize = new CountDownLatch(1);
        provider.blockInitialize(initializeEntered, releaseInitialize);
        provider.rejectDuplicateClose = true;
        advanceToBootstrap(supervisor, "dev.host.rust");
        RuntimeProviderRegistration registration = supervisor.register("dev.host.rust", provider);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Void> activating = executor.submit(() -> {
                supervisor.activate(registration);
                return null;
            });
            assertTrue(initializeEntered.await(5, TimeUnit.SECONDS));
            Future<Void> closing = executor.submit(() -> {
                registration.close();
                return null;
            });
            awaitState(supervisor, "dev.host.rust", RuntimeProviderState.STOPPING);

            releaseInitialize.countDown();
            ExecutionException activationFailure = assertThrows(
                    ExecutionException.class,
                    () -> activating.get(5, TimeUnit.SECONDS)
            );
            assertTrue(activationFailure.getCause() instanceof IOException);
            closing.get(5, TimeUnit.SECONDS);
            assertEquals(1, provider.events.stream().filter("close"::equals).count());
            assertTrue(registration.isClosed());
            assertTrue(registry.findById("dev.host.rust").isEmpty());
            assertEquals(RuntimeProviderState.FAILED, supervisor.state("dev.host.rust").orElseThrow());
            assertThrows(IOException.class, () -> supervisor.activateOwnedRegistration("dev.host.rust"));
        } finally {
            releaseInitialize.countDown();
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

    /// Requires one asynchronous Hook invocation to complete only with fail-closed cancellation.
    ///
    /// @param hook callback future
    /// @throws Exception if waiting is interrupted or times out
    private static void assertFutureCancelled(Future<?> hook) throws Exception {
        ExecutionException failure = assertThrows(
                ExecutionException.class,
                () -> hook.get(5, TimeUnit.SECONDS)
        );
        assertTrue(failure.getCause() instanceof CancellationException);
    }

    /// Waits until one Provider reaches an exact lifecycle state.
    ///
    /// @param supervisor lifecycle owner
    /// @param providerId canonical Provider ID
    /// @param expected expected lifecycle state
    /// @throws InterruptedException if polling is interrupted
    private static void awaitState(
            RuntimeSupervisor supervisor,
            String providerId,
            RuntimeProviderState expected
    ) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (supervisor.state(providerId).orElseThrow() != expected) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("Runtime Provider did not reach state " + expected + ": " + providerId);
            }
            Thread.sleep(10);
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

    /// Cancellation probe which detects forbidden authority callbacks under one lifecycle monitor.
    @NotNullByDefault
    private static final class TestCancellationSignal implements RuntimeHookEndpoint.CancellationSignal {
        /// Provider lifecycle monitor which must not be held during cancellation.
        private final Object forbiddenMonitor;

        /// Supervisor action installed for the exact admitted callback, or `null` before admission.
        private @Nullable Runnable action;

        /// Whether cancellation already won.
        private boolean cancelled;

        /// Whether cancellation was invoked while holding the forbidden monitor.
        private volatile boolean cancelledWhileHoldingMonitor;

        /// Signal emitted when cancellation wins.
        private final CountDownLatch cancelledSignal = new CountDownLatch(1);

        /// Creates one cancellation lock-order probe.
        ///
        /// @param forbiddenMonitor monitor which cancellation must not hold
        private TestCancellationSignal(Object forbiddenMonitor) {
            this.forbiddenMonitor = forbiddenMonitor;
        }

        /// Cancels once and invokes the installed Supervisor action outside this probe's monitor.
        @Override
        public void cancel() {
            @Nullable Runnable currentAction;
            synchronized (this) {
                if (cancelled) {
                    return;
                }
                cancelled = true;
                cancelledWhileHoldingMonitor = Thread.holdsLock(forbiddenMonitor);
                currentAction = action;
            }
            cancelledSignal.countDown();
            if (currentAction != null) {
                currentAction.run();
            }
        }

        /// Returns whether cancellation already won.
        ///
        /// @return whether this signal is cancelled
        @Override
        public synchronized boolean isCancelled() {
            return cancelled;
        }

        /// Installs one exact Supervisor callback action and runs it immediately after earlier cancellation.
        ///
        /// @param action exact callback cancellation action
        @Override
        public void onCancel(Runnable action) {
            boolean runNow;
            synchronized (this) {
                if (this.action != null) {
                    throw new IllegalStateException("Cancellation action is already installed");
                }
                this.action = action;
                runNow = cancelled;
            }
            if (runNow) {
                action.run();
            }
        }

        /// Returns whether cancellation crossed the forbidden lifecycle monitor.
        ///
        /// @return forbidden monitor observation
        private boolean cancelledWhileHoldingMonitor() {
            return cancelledWhileHoldingMonitor;
        }

        /// Waits for cancellation with a hard test bound.
        ///
        /// @return whether cancellation won before the bound
        /// @throws InterruptedException if waiting is interrupted
        private boolean awaitCancelled() throws InterruptedException {
            return cancelledSignal.await(1, TimeUnit.SECONDS);
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

        /// Whether a duplicate Provider close callback must fail as a non-idempotence probe.
        private boolean rejectDuplicateClose;

        /// Whether the next payload enable callback must fail.
        private boolean failNextPayloadEnable;

        /// Whether the next payload disable callback must fail.
        private boolean failNextPayloadDisable;

        /// Whether the next load callback must return a handle owned by another plugin.
        private boolean returnWrongPayloadOwner;

        /// Optional signal emitted when payload loading enters the Provider callback.
        private @Nullable CountDownLatch loadEntered;

        /// Optional gate which blocks payload loading until the test releases it.
        private @Nullable CountDownLatch releaseLoad;

        /// Optional signal emitted when Provider initialization begins.
        private @Nullable CountDownLatch initializeEntered;

        /// Optional gate which blocks Provider initialization until the test releases it.
        private @Nullable CountDownLatch releaseInitialize;

        /// Optional signal emitted when payload enablement enters the Provider callback.
        private @Nullable CountDownLatch enableEntered;

        /// Optional gate which blocks payload enablement until the test releases it.
        private @Nullable CountDownLatch releaseEnable;

        /// Optional signal emitted when Hook invocation enters Provider code.
        private @Nullable CountDownLatch hookEntered;

        /// Optional gate which blocks Hook invocation until the test releases it.
        private @Nullable CountDownLatch releaseHook;

        /// Callback-entry signals keyed by payloads configured to ignore interruption.
        private final Map<String, CountDownLatch> nonCooperativeHookEntered = new ConcurrentHashMap<>();

        /// Callback-release gates keyed by payloads configured to ignore interruption.
        private final Map<String, CountDownLatch> nonCooperativeHookRelease = new ConcurrentHashMap<>();

        /// Payloads whose non-cooperative Hook must fail after its release gate opens.
        private final Set<String> failingHooks = ConcurrentHashMap.newKeySet();

        /// Exact callback tokens observed by payload owner.
        private final Map<String, PluginCapabilityToken> hookTokens = new ConcurrentHashMap<>();

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

        /// Configures Provider initialization to block between the supplied latches.
        ///
        /// @param entered signal emitted on callback entry
        /// @param release gate allowing callback completion
        private void blockInitialize(CountDownLatch entered, CountDownLatch release) {
            initializeEntered = entered;
            releaseInitialize = release;
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

        /// Configures one payload Hook to remain blocked when cooperative interruption arrives.
        ///
        /// @param ownerPluginId exact payload owner
        /// @param entered signal emitted on callback entry
        /// @param release gate allowing callback completion
        private void blockHookIgnoringInterrupt(
                String ownerPluginId,
                CountDownLatch entered,
                CountDownLatch release
        ) {
            nonCooperativeHookEntered.put(ownerPluginId, entered);
            nonCooperativeHookRelease.put(ownerPluginId, release);
        }

        /// Configures one non-cooperative payload Hook to fail after its release gate opens.
        ///
        /// @param ownerPluginId exact payload owner
        private void failHookAfterRelease(String ownerPluginId) {
            failingHooks.add(ownerPluginId);
        }

        /// Returns the exact token observed by one payload Hook.
        ///
        /// @param ownerPluginId exact payload owner
        /// @return callback token
        private PluginCapabilityToken hookToken(String ownerPluginId) {
            return Objects.requireNonNull(hookTokens.get(ownerPluginId));
        }

        /// Returns the immutable fake descriptor.
        @Override
        public RuntimeProviderDescriptor descriptor() {
            return descriptor;
        }

        /// Records Provider initialization.
        @Override
        public void initialize() throws IOException {
            events.add("initialize");
            awaitCallback(initializeEntered, releaseInitialize);
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
        public void disablePayload(RuntimePayloadHandle handle) throws IOException {
            events.add("disable:" + handle.ownerPluginId());
            if (failNextPayloadDisable) {
                failNextPayloadDisable = false;
                throw new IOException("configured payload disable failure");
            }
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
            String ownerPluginId = handle.ownerPluginId();
            events.add("hook:" + ownerPluginId);
            hookTokens.put(ownerPluginId, token);
            @Nullable CountDownLatch nonCooperativeEntered = nonCooperativeHookEntered.get(ownerPluginId);
            @Nullable CountDownLatch nonCooperativeRelease = nonCooperativeHookRelease.get(ownerPluginId);
            if (nonCooperativeEntered != null && nonCooperativeRelease != null) {
                nonCooperativeEntered.countDown();
                awaitIgnoringInterrupt(nonCooperativeRelease);
                if (failingHooks.contains(ownerPluginId)) {
                    throw new IOException("configured late Hook failure");
                }
                return PluginHookResult.unchanged();
            }
            awaitCallback(hookEntered, releaseHook);
            return PluginHookResult.unchanged();
        }

        /// Records Provider shutdown.
        @Override
        public void close() throws IOException {
            if (rejectDuplicateClose && events.contains("close")) {
                throw new IOException("configured duplicate Provider close");
            }
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

        /// Waits for a release gate while deliberately consuming cooperative interruption.
        ///
        /// @param release callback release gate
        private static void awaitIgnoringInterrupt(CountDownLatch release) {
            while (true) {
                try {
                    release.await();
                    return;
                } catch (InterruptedException exception) {
                    // Deliberately remain in Provider code so bounded lifecycle detachment is exercised.
                }
            }
        }
    }
}
