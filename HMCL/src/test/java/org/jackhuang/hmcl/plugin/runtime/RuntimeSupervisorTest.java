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
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
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
        return new RuntimePayloadContext(
                new PluginArtifactIdentity(pluginId, "1.0.0", "a".repeat(64)),
                temporaryDirectory.resolve(pluginId).resolve("package"),
                "payload/plugin.dll",
                PluginExecutionMode.EMBEDDED,
                temporaryDirectory.resolve(pluginId).resolve("data"),
                Object::new
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

    /// Recording Provider fixture with deterministic health and opaque payload IDs.
    @NotNullByDefault
    private static final class RecordingProvider implements RuntimeProvider {
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
