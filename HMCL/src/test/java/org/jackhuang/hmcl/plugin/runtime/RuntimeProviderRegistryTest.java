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
import org.jackhuang.hmcl.plugin.bridge.PluginCapabilityToken;
import org.jackhuang.hmcl.plugin.bridge.PluginPermissionAuthority;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies multi-provider registration, deterministic selection, and dependent-scoped bindings.
@NotNullByDefault
public final class RuntimeProviderRegistryTest {
    /// Rejects restoration when exact Provider A is incompatible even though Provider B could satisfy the requirement.
    @Test
    public void restoreBindingAgainstExactProviderRequirement() {
        RuntimeProviderRegistry registry = new RuntimeProviderRegistry();
        registry.register(provider("dev.host.rust.bound", "rust", "1.0.0", true, true, 0, 1,
                Set.of(PluginExecutionMode.ISOLATED), Set.of(RuntimeFeature.BRIDGE)));
        registry.register(provider("dev.host.rust.fallback", "rust", "2.0.0", true, true, 0, 1,
                Set.of(PluginExecutionMode.EMBEDDED), Set.of(RuntimeFeature.BRIDGE, RuntimeFeature.HOOKS)));
        RuntimeProviderBinding binding = new RuntimeProviderBinding(
                "dev.plugin.bound", "dev.host.rust.bound", "rust");
        RuntimeRequirement requirement = new RuntimeRequirement(
                "rust", PluginAbi.ABI_2, 1, PluginExecutionMode.EMBEDDED,
                Set.of(RuntimeFeature.BRIDGE, RuntimeFeature.HOOKS), "dev.host.rust.bound");

        assertThrows(IllegalStateException.class, () -> registry.restoreBinding(binding, requirement));
        assertTrue(registry.bindingFor("dev.plugin.bound").isEmpty());
    }

    /// Binds each dependent to its selected provider and prevents removal while the binding exists.
    @Test
    public void bindEachDependentToItsSelectedProvider() {
        RuntimeProviderRegistry registry = new RuntimeProviderRegistry();
        RuntimeProvider first = provider("dev.host.rust.a", "rust", "1.0.0", true, true, 0, 1,
                Set.of(PluginExecutionMode.EMBEDDED), Set.of(RuntimeFeature.BRIDGE));
        RuntimeProvider second = provider("dev.host.rust.b", "rust", "2.0.0", true, true, 0, 1,
                Set.of(PluginExecutionMode.EMBEDDED), Set.of(RuntimeFeature.BRIDGE));
        registry.register(first);
        registry.register(second);

        RuntimeProviderBinding pinned = registry.bind("dev.plugin.pinned",
                requirement("rust", "dev.host.rust.a"));
        RuntimeProviderBinding ranked = registry.bind("dev.plugin.ranked", requirement("rust", null));

        assertEquals("dev.host.rust.a", pinned.providerId());
        assertEquals("dev.host.rust.b", ranked.providerId());
        assertEquals(pinned, registry.bindingFor("dev.plugin.pinned").orElseThrow());
        assertThrows(IllegalStateException.class, () -> registry.unregister("dev.host.rust.a"));
        registry.unbind("dev.plugin.pinned");
        registry.unregister("dev.host.rust.a");
        assertFalse(registry.findById("dev.host.rust.a").isPresent());
    }

    /// Skips a statically compatible provider whose live ABI check fails and binds the next ranked provider.
    @Test
    public void fallBackWhenPreferredProviderFailsLiveAbiCheck() {
        RuntimeProviderRegistry registry = new RuntimeProviderRegistry();
        AtomicInteger preferredChecks = new AtomicInteger();
        AtomicInteger fallbackChecks = new AtomicInteger();
        registry.register(liveProvider("dev.host.rust.preferred", "2.0.0", () -> {
            preferredChecks.incrementAndGet();
            return false;
        }));
        registry.register(liveProvider("dev.host.rust.fallback", "1.0.0", () -> {
            fallbackChecks.incrementAndGet();
            return true;
        }));

        RuntimeProviderBinding binding = registry.bind("dev.plugin.dynamic", requirement("rust", null));

        assertEquals("dev.host.rust.fallback", binding.providerId());
        assertEquals(1, preferredChecks.get());
        assertEquals(1, fallbackChecks.get());
    }

    /// Makes a live-incompatible explicit provider pin fail closed without probing a compatible fallback.
    @Test
    public void failClosedWhenPinnedProviderFailsLiveAbiCheck() {
        RuntimeProviderRegistry registry = new RuntimeProviderRegistry();
        AtomicInteger fallbackChecks = new AtomicInteger();
        registry.register(liveProvider("dev.host.rust.pinned", "1.0.0", () -> false));
        registry.register(liveProvider("dev.host.rust.fallback", "2.0.0", () -> {
            fallbackChecks.incrementAndGet();
            return true;
        }));

        assertThrows(IllegalStateException.class, () -> registry.bind(
                "dev.plugin.pinned.dynamic", requirement("rust", "dev.host.rust.pinned")));
        assertEquals(0, fallbackChecks.get());
        assertTrue(registry.bindingFor("dev.plugin.pinned.dynamic").isEmpty());
    }

    /// Retries selection when a live ABI callback unregisters its own candidate before binding publication.
    @Test
    public void retryWhenLiveAbiCallbackUnregistersProvider() {
        RuntimeProviderRegistry registry = new RuntimeProviderRegistry();
        RuntimeProvider selfRemoving = liveProvider("dev.host.rust.removed", "2.0.0", () -> {
            registry.unregister("dev.host.rust.removed");
            return true;
        });
        registry.register(selfRemoving);
        registry.register(liveProvider("dev.host.rust.fallback", "1.0.0", () -> true));

        RuntimeProviderBinding binding = assertTimeoutPreemptively(Duration.ofSeconds(5),
                () -> registry.bind("dev.plugin.self-removing", requirement("rust", null)));

        assertEquals("dev.host.rust.fallback", binding.providerId());
        assertTrue(registry.findById("dev.host.rust.removed").isEmpty());
        assertThrows(IllegalStateException.class, () -> registry.unregister("dev.host.rust.fallback"));
    }

    /// Revalidates provider identity when the registered instance changes during an unlocked live ABI callback.
    @Test
    public void retryWhenProviderInstanceChangesDuringLiveAbiCheck() throws Exception {
        RuntimeProviderRegistry registry = new RuntimeProviderRegistry();
        CountDownLatch checkStarted = new CountDownLatch(1);
        CountDownLatch allowCheckToFinish = new CountDownLatch(1);
        AtomicInteger originalChecks = new AtomicInteger();
        AtomicInteger replacementChecks = new AtomicInteger();
        RuntimeProvider original = liveProvider("dev.host.rust.racing", "1.0.0", () -> {
            originalChecks.incrementAndGet();
            checkStarted.countDown();
            try {
                return allowCheckToFinish.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return false;
            }
        });
        RuntimeProvider replacement = liveProvider("dev.host.rust.racing", "1.0.0", () -> {
            replacementChecks.incrementAndGet();
            return true;
        });
        registry.register(original);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<RuntimeProviderBinding> result = executor.submit(
                    () -> registry.bind("dev.plugin.racing", requirement("rust", null)));
            assertTrue(checkStarted.await(5, TimeUnit.SECONDS));
            registry.unregister("dev.host.rust.racing");
            registry.register(replacement);
            allowCheckToFinish.countDown();

            assertEquals("dev.host.rust.racing", result.get(5, TimeUnit.SECONDS).providerId());
        } finally {
            allowCheckToFinish.countDown();
            executor.shutdownNow();
        }

        assertEquals(1, originalChecks.get());
        assertEquals(1, replacementChecks.get());
        assertSame(replacement, registry.findById("dev.host.rust.racing").orElseThrow());
    }

    /// Fails closed within a bounded time when every live ABI check replaces its registered provider instance.
    @Test
    public void failClosedWhenProviderRegistryNeverStabilizes() throws Exception {
        RuntimeProviderRegistry registry = new RuntimeProviderRegistry();
        AtomicInteger liveChecks = new AtomicInteger();
        registry.register(selfReplacingProvider(registry, liveChecks));

        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<RuntimeProviderBinding> result = executor.submit(
                () -> registry.bind("dev.plugin.unstable", requirement("rust", null)));
        try {
            ExecutionException failure = assertThrows(
                    ExecutionException.class, () -> result.get(2, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof IllegalStateException);
            assertTrue(failure.getCause().getMessage().contains("changed repeatedly"));
            assertTrue(failure.getCause().getMessage().contains("dev.plugin.unstable"));
        } finally {
            result.cancel(true);
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }

        assertTrue(liveChecks.get() > 1);
        assertTrue(registry.bindingFor("dev.plugin.unstable").isEmpty());
    }

    /// Prefers enabled installed providers before disabled installed and remote candidates.
    @Test
    public void preferInstalledProviderEnablementTiers() {
        RuntimeProvider enabled = provider("dev.host.rust.enabled", "rust", "1.0.0", true, true, 50, 1,
                Set.of(PluginExecutionMode.EMBEDDED), Set.of(RuntimeFeature.BRIDGE));
        RuntimeProvider disabled = provider("dev.host.rust.disabled", "rust", "9.0.0", true, false, 0, 1,
                Set.of(PluginExecutionMode.EMBEDDED), Set.of(RuntimeFeature.BRIDGE));
        RuntimeProvider remote = provider("dev.host.rust.remote", "rust", "99.0.0", false, false, 0, 1,
                Set.of(PluginExecutionMode.EMBEDDED), Set.of(RuntimeFeature.BRIDGE));
        RuntimeProviderSelector selector = new RuntimeProviderSelector();

        assertEquals("dev.host.rust.enabled", selector.select(requirement("rust", null),
                List.of(remote.descriptor(), disabled.descriptor(), enabled.descriptor()))
                .orElseThrow().providerId());
        assertEquals("dev.host.rust.disabled", selector.select(requirement("rust", null),
                List.of(remote.descriptor(), disabled.descriptor())).orElseThrow().providerId());
    }

    /// Applies source priority, descending version, and provider ID in that exact tie-break order.
    @Test
    public void rankBySourceVersionAndProviderId() {
        RuntimeProvider lowPriority = provider("dev.host.rust.priority", "rust", "1.0.0", false, false, 0, 1,
                Set.of(PluginExecutionMode.EMBEDDED), Set.of(RuntimeFeature.BRIDGE));
        RuntimeProvider highVersion = provider("dev.host.rust.z", "rust", "3.0.0", false, false, 1, 1,
                Set.of(PluginExecutionMode.EMBEDDED), Set.of(RuntimeFeature.BRIDGE));
        RuntimeProvider lexicalWinner = provider("dev.host.rust.a", "rust", "3.0.0", false, false, 1, 1,
                Set.of(PluginExecutionMode.EMBEDDED), Set.of(RuntimeFeature.BRIDGE));
        RuntimeProvider lowerVersion = provider("dev.host.rust.older", "rust", "2.0.0", false, false, 1, 1,
                Set.of(PluginExecutionMode.EMBEDDED), Set.of(RuntimeFeature.BRIDGE));
        RuntimeProviderSelector selector = new RuntimeProviderSelector();

        assertEquals("dev.host.rust.priority", selector.select(requirement("rust", null),
                List.of(highVersion.descriptor(), lowPriority.descriptor())).orElseThrow().providerId());
        assertEquals("dev.host.rust.z", selector.select(requirement("rust", null),
                List.of(lowerVersion.descriptor(), highVersion.descriptor())).orElseThrow().providerId());
        assertEquals("dev.host.rust.a", selector.select(requirement("rust", null),
                List.of(highVersion.descriptor(), lexicalWinner.descriptor())).orElseThrow().providerId());
    }

    /// Makes explicit pins fail closed when the target is absent or incompatible.
    @Test
    public void pinFailsClosed() {
        RuntimeProvider compatible = provider("dev.host.rust.compatible", "rust", "2.0.0", true, true, 0, 1,
                Set.of(PluginExecutionMode.EMBEDDED), Set.of(RuntimeFeature.BRIDGE));
        RuntimeProvider incompatible = provider("dev.host.rust.incompatible", "rust", "9.0.0", true, true, 0, 1,
                Set.of(PluginExecutionMode.ISOLATED), Set.of(RuntimeFeature.BRIDGE));
        RuntimeProviderSelector selector = new RuntimeProviderSelector();

        assertTrue(selector.select(requirement("rust", "dev.host.rust.missing"),
                List.of(compatible.descriptor())).isEmpty());
        assertTrue(selector.select(requirement("rust", "dev.host.rust.incompatible"),
                List.of(compatible.descriptor(), incompatible.descriptor())).isEmpty());
    }

    /// Rejects candidates missing any required ABI, mode, Bridge ABI, or feature.
    @Test
    public void rejectIncompatibleProviderCapabilities() {
        RuntimeProvider wrongAbi = provider("dev.host.rust.abi", "rust", "1.0.0", true, true, 0, 1,
                Set.of(PluginExecutionMode.EMBEDDED), Set.of(RuntimeFeature.BRIDGE), Set.of(PluginAbi.ABI_1));
        RuntimeProvider wrongMode = provider("dev.host.rust.mode", "rust", "1.0.0", true, true, 0, 1,
                Set.of(PluginExecutionMode.ISOLATED), Set.of(RuntimeFeature.BRIDGE));
        RuntimeProvider wrongBridge = provider("dev.host.rust.bridge", "rust", "1.0.0", true, true, 0, 2,
                Set.of(PluginExecutionMode.EMBEDDED), Set.of(RuntimeFeature.BRIDGE));
        RuntimeProvider wrongFeatures = provider("dev.host.rust.features", "rust", "1.0.0", true, true, 0, 1,
                Set.of(PluginExecutionMode.EMBEDDED), Set.of(RuntimeFeature.BRIDGE));
        RuntimeProviderSelector selector = new RuntimeProviderSelector();
        RuntimeRequirement fullRequirement = new RuntimeRequirement(
                "rust", PluginAbi.ABI_2, 1, PluginExecutionMode.EMBEDDED,
                Set.of(RuntimeFeature.BRIDGE, RuntimeFeature.HOOKS), null);

        assertTrue(selector.select(fullRequirement, List.of(
                wrongAbi.descriptor(), wrongMode.descriptor(), wrongBridge.descriptor(), wrongFeatures.descriptor()
        )).isEmpty());
    }

    /// Publishes immutable candidate snapshots which are unaffected by later registry mutations.
    @Test
    public void exposeImmutableCandidateSnapshots() {
        RuntimeProviderRegistry registry = new RuntimeProviderRegistry();
        RuntimeProvider first = provider("dev.host.rust.a", "rust", "1.0.0", true, true, 0, 1,
                Set.of(PluginExecutionMode.EMBEDDED), Set.of(RuntimeFeature.BRIDGE));
        registry.register(first);
        @Unmodifiable List<RuntimeProviderDescriptor> snapshot = registry.candidates("rust");

        registry.register(provider("dev.host.rust.b", "rust", "2.0.0", true, true, 0, 1,
                Set.of(PluginExecutionMode.EMBEDDED), Set.of(RuntimeFeature.BRIDGE)));

        assertEquals(List.of(first.descriptor()), snapshot);
        assertThrows(UnsupportedOperationException.class, () -> snapshot.add(first.descriptor()));
        assertEquals(2, registry.candidates("rust").size());
    }

    /// Resolves each capability of one multi-runtime host without conflating their ABI contracts.
    @Test
    public void bindMultiRuntimeProviderCapabilitiesIndependently() {
        RuntimeProviderDescriptor descriptor = new RuntimeProviderDescriptor(
                "dev.host.script",
                "1.0.0",
                List.of(
                        new RuntimeProviderDeclaration("javascript", Set.of(PluginAbi.ABI_1), 1,
                                Set.of(PluginExecutionMode.EMBEDDED), Set.of(RuntimeFeature.BRIDGE)),
                        new RuntimeProviderDeclaration("wasm", Set.of(PluginAbi.ABI_2), 1,
                                Set.of(PluginExecutionMode.ISOLATED), Set.of(RuntimeFeature.BRIDGE))
                ),
                true,
                true,
                0,
                false
        );
        RuntimeProviderRegistry registry = new RuntimeProviderRegistry();
        registry.register(new RuntimeProvider() {
            /// Returns both independently negotiated script runtime capabilities.
            @Override
            public RuntimeProviderDescriptor descriptor() {
                return descriptor;
            }
        });

        RuntimeProviderBinding javascript = registry.bind("dev.plugin.javascript", new RuntimeRequirement(
                "javascript", PluginAbi.ABI_1, 1, PluginExecutionMode.EMBEDDED,
                Set.of(RuntimeFeature.BRIDGE), null));
        RuntimeProviderBinding wasm = registry.bind("dev.plugin.wasm", new RuntimeRequirement(
                "wasm", PluginAbi.ABI_2, 1, PluginExecutionMode.ISOLATED,
                Set.of(RuntimeFeature.BRIDGE), null));

        assertEquals("dev.host.script", javascript.providerId());
        assertEquals("dev.host.script", wasm.providerId());
    }

    /// Keeps the built-in Java provider reserved, unique, and impossible to unregister.
    @Test
    public void preserveReservedJavaProvider() {
        RuntimeProviderRegistry registry = new RuntimeProviderRegistry();
        RuntimeProvider javaProvider = registry.find(PluginRuntimeTypes.JAVA).orElseThrow();
        RuntimeProvider replacement = provider("dev.host.java", PluginRuntimeTypes.JAVA, "99.0.0",
                true, true, 0, 1, Set.of(PluginExecutionMode.EMBEDDED), Set.of(RuntimeFeature.BRIDGE));

        assertTrue(javaProvider.descriptor().reserved());
        assertThrows(IllegalStateException.class, () -> registry.register(replacement));
        registry.unregister(javaProvider.descriptor().providerId());

        assertSame(javaProvider, registry.find(PluginRuntimeTypes.JAVA).orElseThrow());
        assertEquals(1, registry.candidates(PluginRuntimeTypes.JAVA).size());
    }

    /// Preserves exact payload identity while normalizing filesystem paths and keeping authority opaque.
    @Test
    public void exposeImmutableRuntimePayloadContext(@TempDir Path temporaryDirectory) {
        PluginArtifactIdentity identity = new PluginArtifactIdentity(
                "dev.plugin.payload", "1.0.0", "a".repeat(64));
        PluginCapabilityToken token = capabilityToken(identity);
        RuntimePayloadContext context = new RuntimePayloadContext(
                identity,
                temporaryDirectory.resolve("package").resolve("..").resolve("package"),
                "payload/plugin.dll",
                PluginExecutionMode.EMBEDDED,
                temporaryDirectory.resolve("data").resolve(".."),
                () -> token
        );

        assertSame(identity, context.artifactIdentity());
        assertEquals(temporaryDirectory.resolve("package").toAbsolutePath().normalize(), context.packagePath());
        assertEquals("payload/plugin.dll", context.entrypoint());
        assertEquals(PluginExecutionMode.EMBEDDED, context.executionMode());
        assertEquals(temporaryDirectory.toAbsolutePath().normalize(), context.dataDirectory());
        assertSame(token, context.capabilityTokenSupplier().get());
    }

    /// Rejects absolute, platform-specific, escaping, empty-segment, and non-normalized payload entrypoints.
    ///
    /// @param entrypoint unsafe runtime-owned package path
    @ParameterizedTest
    @ValueSource(strings = {
            "",
            " ",
            "/payload/plugin.dll",
            "C:/payload/plugin.dll",
            "C:\\payload\\plugin.dll",
            "\\\\server\\share\\plugin.dll",
            "payload\\plugin.dll",
            ".",
            "..",
            "./payload.dll",
            "payload/./plugin.dll",
            "payload/../plugin.dll",
            "payload//plugin.dll",
            "payload/",
            " payload/plugin.dll",
            "payload/plugin.dll "
    })
    public void rejectUnsafeRuntimePayloadEntrypoint(String entrypoint) {
        PluginArtifactIdentity identity = new PluginArtifactIdentity(
                "dev.plugin.payload", "1.0.0", "a".repeat(64));

        assertThrows(IllegalArgumentException.class, () -> new RuntimePayloadContext(
                identity,
                Path.of("package"),
                entrypoint,
                PluginExecutionMode.EMBEDDED,
                Path.of("data"),
                () -> capabilityToken(identity)
        ));
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

    /// Keeps runtime payload handles opaque and rejects malformed owner identities.
    @Test
    public void validateOpaqueRuntimePayloadHandles() {
        RuntimePayloadHandle handle = new RuntimePayloadHandle(
                "dev.plugin.payload", "dev.host.rust", "provider-payload-1");

        assertEquals("dev.plugin.payload", handle.ownerPluginId());
        assertEquals("dev.host.rust", handle.providerId());
        assertEquals("provider-payload-1", handle.payloadId());
        assertThrows(IllegalArgumentException.class,
                () -> new RuntimePayloadHandle("Dev.plugin.payload", "dev.host.rust", "payload"));
        assertThrows(IllegalArgumentException.class,
                () -> new RuntimePayloadHandle("dev.plugin.payload", "dev.host.rust", " "));
    }

    /// Creates one unpinned runtime requirement for selector and registry tests.
    ///
    /// @param runtime required runtime
    /// @param pinnedProviderId optional provider pin
    /// @return runtime requirement
    private static RuntimeRequirement requirement(String runtime, String pinnedProviderId) {
        return new RuntimeRequirement(runtime, PluginAbi.ABI_2, 1, PluginExecutionMode.EMBEDDED,
                Set.of(RuntimeFeature.BRIDGE), pinnedProviderId);
    }

    /// Creates one descriptor-backed test provider with ABI 2.
    private static RuntimeProvider provider(
            String providerId,
            String runtime,
            String version,
            boolean installed,
            boolean enabled,
            int sourcePriority,
            int bridgeAbi,
            @Unmodifiable Set<PluginExecutionMode> modes,
            @Unmodifiable Set<RuntimeFeature> features) {
        return provider(providerId, runtime, version, installed, enabled, sourcePriority, bridgeAbi,
                modes, features, Set.of(PluginAbi.ABI_2));
    }

    /// Creates one descriptor-backed test provider with explicit capabilities.
    private static RuntimeProvider provider(
            String providerId,
            String runtime,
            String version,
            boolean installed,
            boolean enabled,
            int sourcePriority,
            int bridgeAbi,
            @Unmodifiable Set<PluginExecutionMode> modes,
            @Unmodifiable Set<RuntimeFeature> features,
            @Unmodifiable Set<Integer> abis) {
        RuntimeProviderDescriptor descriptor = new RuntimeProviderDescriptor(
                providerId,
                version,
                List.of(new RuntimeProviderDeclaration(runtime, abis, bridgeAbi, modes, features)),
                installed,
                enabled,
                sourcePriority,
                false
        );
        return new RuntimeProvider() {
            /// Returns the immutable test descriptor.
            @Override
            public RuntimeProviderDescriptor descriptor() {
                return descriptor;
            }
        };
    }

    /// Creates one descriptor-backed Rust provider with a caller-controlled live ABI response.
    ///
    /// @param providerId provider plugin ID
    /// @param version provider version
    /// @param liveSupport current live ABI response
    /// @return test runtime provider
    private static RuntimeProvider liveProvider(String providerId, String version, BooleanSupplier liveSupport) {
        RuntimeProviderDescriptor descriptor = provider(providerId, "rust", version, true, true, 0, 1,
                Set.of(PluginExecutionMode.EMBEDDED), Set.of(RuntimeFeature.BRIDGE)).descriptor();
        return new RuntimeProvider() {
            /// Returns the immutable test descriptor.
            @Override
            public RuntimeProviderDescriptor descriptor() {
                return descriptor;
            }

            /// Returns the caller-controlled live ABI availability.
            @Override
            public boolean supportsAbi(String runtime, int requiredAbi) {
                return liveSupport.getAsBoolean();
            }
        };
    }

    /// Creates a provider which replaces itself with a fresh equivalent instance after every live ABI check.
    ///
    /// @param registry registry mutated by live checks
    /// @param liveChecks completed live check counter
    /// @return self-replacing provider
    private static RuntimeProvider selfReplacingProvider(
            RuntimeProviderRegistry registry,
            AtomicInteger liveChecks) {
        RuntimeProviderDescriptor descriptor = provider("dev.host.rust.unstable", "rust", "1.0.0", true, true,
                0, 1, Set.of(PluginExecutionMode.EMBEDDED), Set.of(RuntimeFeature.BRIDGE)).descriptor();
        return new RuntimeProvider() {
            /// Returns the immutable test descriptor.
            @Override
            public RuntimeProviderDescriptor descriptor() {
                return descriptor;
            }

            /// Replaces this provider instance and reports live ABI support until the test cancels the task.
            @Override
            public boolean supportsAbi(String runtime, int requiredAbi) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new IllegalStateException("Interrupted unstable provider test callback");
                }
                liveChecks.incrementAndGet();
                registry.unregister(descriptor.providerId());
                registry.register(selfReplacingProvider(registry, liveChecks));
                return true;
            }
        };
    }
}
