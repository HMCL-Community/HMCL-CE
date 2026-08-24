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
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

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

    /// Recording Provider fixture with deterministic health and opaque payload IDs.
    @NotNullByDefault
    private static final class RecordingProvider implements RuntimeProvider {
        /// Immutable fake Provider descriptor.
        private final RuntimeProviderDescriptor descriptor;

        /// Health result returned during activation.
        private final boolean healthy;

        /// Ordered Provider callbacks.
        private final List<String> events = new ArrayList<>();

        /// Creates a fake embedded Rust Provider.
        ///
        /// @param providerId Provider plugin ID
        /// @param healthy health result
        private RecordingProvider(String providerId, boolean healthy) {
            this.descriptor = new RuntimeProviderDescriptor(
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
            this.healthy = healthy;
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
        public RuntimePayloadHandle loadPayload(RuntimePayloadContext context) {
            String pluginId = context.artifactIdentity().getPluginId();
            events.add("load:" + pluginId);
            return new RuntimePayloadHandle(pluginId, descriptor.providerId(), "payload-" + pluginId);
        }

        /// Records payload enablement.
        @Override
        public void enablePayload(RuntimePayloadHandle handle) {
            events.add("enable:" + handle.ownerPluginId());
        }

        /// Records payload disablement.
        @Override
        public void disablePayload(RuntimePayloadHandle handle) {
            events.add("disable:" + handle.ownerPluginId());
        }

        /// Records payload unloading.
        @Override
        public void unloadPayload(RuntimePayloadHandle handle) {
            events.add("unload:" + handle.ownerPluginId());
        }

        /// Records Provider shutdown.
        @Override
        public void close() {
            events.add("close");
        }
    }
}
