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
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.StringReader;
import java.lang.reflect.Field;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies stable Hook subscriber selection, ordering, callback scope, and class-loader leases.
@NotNullByDefault
public final class PluginHookSubscriberOrderTest {
    /// Orders dependencies before dependents, breaks unrelated ties by ID, and excludes every ineligible state.
    ///
    /// @param temporaryDirectory isolated manager home
    /// @throws Exception if manifest creation, registration, or snapshotting fails
    @Test
    @EnabledIf("org.jackhuang.hmcl.JavaFXLauncher#isStarted")
    public void orderEligibleSubscribersAndExcludeIneligiblePlugins(
            @TempDir Path temporaryDirectory
    ) throws Exception {
        PluginManager manager = new PluginManager(temporaryDirectory.resolve("home"));
        AtomicReference<@Unmodifiable Set<PluginPermission>> granted = grantedHookPermission();
        AtomicReference<@Unmodifiable Set<PluginPermission>> revoked = new AtomicReference<>(Set.of());

        register(manager, temporaryDirectory, schemaFiveManifest(
                "dev.test.final", List.of("dev.test.alpha", "dev.test.beta"), true), granted, true);
        register(manager, temporaryDirectory, schemaFiveManifest(
                "dev.test.beta", List.of("dev.test.base"), true), granted, true);
        register(manager, temporaryDirectory, schemaFourManifest("dev.test.schema-four"), revoked, true);
        register(manager, temporaryDirectory, schemaFiveManifest(
                "dev.test.base", List.of(), true), granted, true);
        register(manager, temporaryDirectory, schemaFiveManifest(
                "dev.test.alpha", List.of("dev.test.base"), true), granted, true);
        register(manager, temporaryDirectory, schemaFiveManifest(
                "dev.test.disabled", List.of(), true), granted, false);
        register(manager, temporaryDirectory, schemaFiveManifest(
                "dev.test.undeclared", List.of(), false), granted, true);
        register(manager, temporaryDirectory, schemaFiveManifest(
                "dev.test.revoked", List.of(), true), revoked, true);

        List<PluginHookSubscriber> subscribers =
                manager.snapshotHookSubscribers(PluginHookPoint.BEFORE_GAME_LAUNCH);
        try {
            assertEquals(List.of(
                    "dev.test.base",
                    "dev.test.alpha",
                    "dev.test.beta",
                    "dev.test.final"
            ), subscribers.stream().map(PluginHookSubscriber::pluginId).toList());
            assertEquals(Set.of(PluginPermission.LAUNCHER_HOOK), subscribers.get(0).permissions());
            assertThrows(UnsupportedOperationException.class, subscribers::clear);
            assertTrue(manager.hasEligibleHookSubscriber(PluginHookPoint.BEFORE_GAME_LAUNCH));
            assertFalse(manager.hasEligibleHookSubscriber(PluginHookPoint.AFTER_GAME_LAUNCH));
        } finally {
            subscribers.forEach(PluginHookSubscriber::close);
        }
    }

    /// Applies permission revocation to the next snapshot while preserving an already acquired immutable snapshot.
    ///
    /// @param temporaryDirectory isolated manager home
    /// @throws Exception if manifest creation, registration, or snapshotting fails
    @Test
    @EnabledIf("org.jackhuang.hmcl.JavaFXLauncher#isStarted")
    public void applyPermissionRevocationToSubsequentSnapshots(
            @TempDir Path temporaryDirectory
    ) throws Exception {
        PluginManager manager = new PluginManager(temporaryDirectory.resolve("home"));
        AtomicReference<@Unmodifiable Set<PluginPermission>> grants = grantedHookPermission();
        register(manager, temporaryDirectory, schemaFiveManifest(
                "dev.test.revocable", List.of(), true), grants, true);

        List<PluginHookSubscriber> beforeRevocation =
                manager.snapshotHookSubscribers(PluginHookPoint.BEFORE_GAME_LAUNCH);
        try {
            assertEquals(List.of("dev.test.revocable"), pluginIds(beforeRevocation));
            grants.set(Set.of());
            assertTrue(manager.snapshotHookSubscribers(PluginHookPoint.BEFORE_GAME_LAUNCH).isEmpty());
            assertFalse(manager.hasEligibleHookSubscriber(PluginHookPoint.BEFORE_GAME_LAUNCH));
            assertEquals(Set.of(PluginPermission.LAUNCHER_HOOK), beforeRevocation.get(0).permissions());
        } finally {
            beforeRevocation.forEach(PluginHookSubscriber::close);
        }
    }

    /// Uses the loaded context's exact-artifact permission provider instead of another artifact's current grant.
    ///
    /// @param temporaryDirectory isolated manager home
    /// @throws Exception if manifest creation, registration, or snapshotting fails
    @Test
    @EnabledIf("org.jackhuang.hmcl.JavaFXLauncher#isStarted")
    public void requireGrantForTheExactLoadedArtifact(
            @TempDir Path temporaryDirectory
    ) throws Exception {
        PluginManager manager = new PluginManager(temporaryDirectory.resolve("home"));
        String loadedSha256 = "loaded-artifact-sha256";
        AtomicReference<String> grantedArtifact = new AtomicReference<>("different-artifact-sha256");
        Supplier<@Unmodifiable Set<PluginPermission>> exactArtifactPermissions = () ->
                loadedSha256.equals(grantedArtifact.get())
                        ? Set.of(PluginPermission.LAUNCHER_HOOK)
                        : Set.of();
        register(manager, temporaryDirectory, schemaFiveManifest(
                "dev.test.artifact-bound", List.of(), true), loadedSha256,
                exactArtifactPermissions, PluginManager.class.getClassLoader(), true);

        assertTrue(manager.snapshotHookSubscribers(PluginHookPoint.BEFORE_GAME_LAUNCH).isEmpty());
        assertFalse(manager.hasEligibleHookSubscriber(PluginHookPoint.BEFORE_GAME_LAUNCH));

        grantedArtifact.set(loadedSha256);
        List<PluginHookSubscriber> authorized =
                manager.snapshotHookSubscribers(PluginHookPoint.BEFORE_GAME_LAUNCH);
        try {
            assertEquals(List.of("dev.test.artifact-bound"), pluginIds(authorized));
        } finally {
            authorized.forEach(PluginHookSubscriber::close);
        }
    }

    /// Invokes Java endpoints after releasing the manager state lock with the plugin TCCL and guard installed.
    ///
    /// @param temporaryDirectory isolated manager home
    /// @throws Exception if registration, reflection, or endpoint invocation fails
    @Test
    @EnabledIf("org.jackhuang.hmcl.JavaFXLauncher#isStarted")
    public void invokeEndpointOutsideStateLockWithPluginCallbackScope(
            @TempDir Path temporaryDirectory
    ) throws Exception {
        PluginManager manager = new PluginManager(temporaryDirectory.resolve("home"));
        ClassLoader endpointClassLoader = new URLClassLoader(
                new URL[0],
                PluginManager.class.getClassLoader()
        );
        PluginManifest manifest = schemaFiveManifest("dev.test.callback-scope", List.of(), true);
        AtomicReference<Boolean> invoked = new AtomicReference<>(false);
        HookPlugin plugin = new HookPlugin(manifest, event -> {
            assertSame(endpointClassLoader, Thread.currentThread().getContextClassLoader());
            assertEquals(0, currentThreadReadHoldCount(manager));
            assertThrows(SecurityException.class, manager::getPluginsDirectory);
            invoked.set(true);
            return PluginHookResult.unchanged();
        });
        register(manager, temporaryDirectory, plugin, manifest, "callback-artifact",
                () -> Set.of(PluginPermission.LAUNCHER_HOOK), endpointClassLoader, true);

        List<PluginHookSubscriber> subscribers =
                manager.snapshotHookSubscribers(PluginHookPoint.BEFORE_GAME_LAUNCH);
        try {
            assertEquals(PluginHookResult.Action.UNCHANGED,
                    subscribers.get(0).endpoint().invoke(event(PluginHookPoint.BEFORE_GAME_LAUNCH)).action());
            assertTrue(invoked.get());
        } finally {
            subscribers.forEach(PluginHookSubscriber::close);
        }
    }

    /// Confirms the eligibility probe does not retain a callback lease that delays immediate class-loader close.
    ///
    /// @param temporaryDirectory isolated manager home
    /// @throws Exception if registration or class-loader close fails
    @Test
    @EnabledIf("org.jackhuang.hmcl.JavaFXLauncher#isStarted")
    public void checkEligibilityWithoutAcquiringHookLease(
            @TempDir Path temporaryDirectory
    ) throws Exception {
        PluginManager manager = new PluginManager(temporaryDirectory.resolve("home"));
        TrackingClassLoader classLoader = new TrackingClassLoader();
        PluginContainer container = register(manager, temporaryDirectory, schemaFiveManifest(
                "dev.test.eligibility-probe", List.of(), true), "probe-artifact",
                () -> Set.of(PluginPermission.LAUNCHER_HOOK), classLoader, true);

        assertTrue(manager.hasEligibleHookSubscriber(PluginHookPoint.BEFORE_GAME_LAUNCH));
        container.closeClassLoader();

        assertEquals(1, classLoader.closeCount());
    }

    /// Defers class-loader close until the final Hook lease is released and makes every release idempotent.
    ///
    /// @param temporaryDirectory isolated package and storage paths
    /// @throws Exception if manifest creation or immediate close fails
    @Test
    public void deferClassLoaderCloseUntilLastLeaseRelease(
            @TempDir Path temporaryDirectory
    ) throws Exception {
        TrackingClassLoader classLoader = new TrackingClassLoader();
        PluginManifest manifest = schemaFiveManifest("dev.test.lease", List.of(), true);
        PluginContext context = new PluginContext(
                manifest,
                temporaryDirectory.resolve("package"),
                temporaryDirectory.resolve("data"),
                classLoader,
                "lease-artifact",
                () -> Set.of(PluginPermission.LAUNCHER_HOOK)
        );
        PluginContainer container = new PluginContainer(new HookPlugin(manifest), context,
                temporaryDirectory.resolve("lease.npl"));
        Runnable firstLease = container.acquireHookLease();
        Runnable secondLease = container.acquireHookLease();

        container.closeClassLoader();
        assertEquals(0, classLoader.closeCount());
        firstLease.run();
        firstLease.run();
        assertEquals(0, classLoader.closeCount());
        secondLease.run();
        secondLease.run();
        container.closeClassLoader();

        assertEquals(1, classLoader.closeCount());
    }

    /// Releases all leases when deterministic sorting rejects a synthetic eligible dependency cycle.
    ///
    /// @param temporaryDirectory isolated manager home
    /// @throws Exception if manifest creation, registration, or class-loader close fails
    @Test
    @EnabledIf("org.jackhuang.hmcl.JavaFXLauncher#isStarted")
    public void releaseSnapshotLeasesWhenSortingFails(
            @TempDir Path temporaryDirectory
    ) throws Exception {
        PluginManager manager = new PluginManager(temporaryDirectory.resolve("home"));
        TrackingClassLoader alphaLoader = new TrackingClassLoader();
        TrackingClassLoader betaLoader = new TrackingClassLoader();
        PluginContainer alpha = register(manager, temporaryDirectory, schemaFiveManifest(
                "dev.test.cycle-alpha", List.of("dev.test.cycle-beta"), true), "cycle-alpha",
                () -> Set.of(PluginPermission.LAUNCHER_HOOK), alphaLoader, true);
        PluginContainer beta = register(manager, temporaryDirectory, schemaFiveManifest(
                "dev.test.cycle-beta", List.of("dev.test.cycle-alpha"), true), "cycle-beta",
                () -> Set.of(PluginPermission.LAUNCHER_HOOK), betaLoader, true);

        assertThrows(IllegalStateException.class,
                () -> manager.snapshotHookSubscribers(PluginHookPoint.BEFORE_GAME_LAUNCH));
        alpha.closeClassLoader();
        beta.closeClassLoader();

        assertEquals(1, alphaLoader.closeCount());
        assertEquals(1, betaLoader.closeCount());
    }

    /// Creates a mutable exact-artifact permission decision initially granting Hook access.
    ///
    /// @return permission decision reference
    private static AtomicReference<@Unmodifiable Set<PluginPermission>> grantedHookPermission() {
        return new AtomicReference<>(Set.of(PluginPermission.LAUNCHER_HOOK));
    }

    /// Returns subscriber IDs in dispatch order.
    ///
    /// @param subscribers ordered subscriber snapshot
    /// @return ordered plugin IDs
    private static List<String> pluginIds(List<PluginHookSubscriber> subscribers) {
        return subscribers.stream().map(PluginHookSubscriber::pluginId).toList();
    }

    /// Registers one no-op Hook plugin with a mutable permission decision.
    ///
    /// @param manager target manager
    /// @param temporaryDirectory isolated package paths
    /// @param manifest plugin manifest
    /// @param permissions dynamic exact-artifact permission decision
    /// @param enabled initial lifecycle state
    /// @return registered container
    private static PluginContainer register(
            PluginManager manager,
            Path temporaryDirectory,
            PluginManifest manifest,
            AtomicReference<@Unmodifiable Set<PluginPermission>> permissions,
            boolean enabled
    ) {
        return register(manager, temporaryDirectory, manifest, manifest.getId() + "-artifact",
                permissions::get, PluginManager.class.getClassLoader(), enabled);
    }

    /// Registers one no-op Hook plugin with explicit artifact and class-loader identity.
    ///
    /// @param manager target manager
    /// @param temporaryDirectory isolated package paths
    /// @param manifest plugin manifest
    /// @param artifactSha256 loaded artifact identity
    /// @param permissions dynamic exact-artifact permission decision
    /// @param classLoader callback context class loader
    /// @param enabled initial lifecycle state
    /// @return registered container
    private static PluginContainer register(
            PluginManager manager,
            Path temporaryDirectory,
            PluginManifest manifest,
            String artifactSha256,
            Supplier<@Unmodifiable Set<PluginPermission>> permissions,
            ClassLoader classLoader,
            boolean enabled
    ) {
        return register(manager, temporaryDirectory, new HookPlugin(manifest), manifest,
                artifactSha256, permissions, classLoader, enabled);
    }

    /// Registers one caller-supplied Hook plugin through the normal manager path.
    ///
    /// @param manager target manager
    /// @param temporaryDirectory isolated package paths
    /// @param plugin lifecycle endpoint
    /// @param manifest plugin manifest
    /// @param artifactSha256 loaded artifact identity
    /// @param permissions dynamic exact-artifact permission decision
    /// @param classLoader callback context class loader
    /// @param enabled initial lifecycle state
    /// @return registered container
    private static PluginContainer register(
            PluginManager manager,
            Path temporaryDirectory,
            HookPlugin plugin,
            PluginManifest manifest,
            String artifactSha256,
            Supplier<@Unmodifiable Set<PluginPermission>> permissions,
            ClassLoader classLoader,
            boolean enabled
    ) {
        PluginContext context = new PluginContext(
                manifest,
                temporaryDirectory.resolve("package-" + manifest.getId()),
                temporaryDirectory.resolve("data-" + manifest.getId()),
                classLoader,
                artifactSha256,
                permissions
        );
        PreparedPlugin prepared = new PreparedPlugin(
                plugin,
                context,
                manifest,
                temporaryDirectory.resolve(manifest.getId() + ".npl")
        );
        AtomicReference<PluginContainer> registered = new AtomicReference<>();
        FXThreadTestSupport.runOnFxThread(() -> registered.set(manager.registerPreparedPlugin(prepared)));
        PluginContainer container = Objects.requireNonNull(registered.get());
        container.setEnabled(enabled);
        return container;
    }

    /// Parses one schema-v5 Java manifest with caller-selected dependencies and Hook declaration.
    ///
    /// @param pluginId plugin ID
    /// @param dependencyIds dependency IDs
    /// @param declaresHook whether to declare `before-game-launch`
    /// @return validated manifest
    /// @throws IOException if the generated manifest is invalid
    private static PluginManifest schemaFiveManifest(
            String pluginId,
            List<String> dependencyIds,
            boolean declaresHook
    ) throws IOException {
        String dependencies = dependencyIds.stream()
                .map(dependency -> "\"" + dependency + "\"")
                .collect(Collectors.joining(", ", "[", "]"));
        String hooks = declaresHook ? "[\"before-game-launch\"]" : "[]";
        return PluginManifest.fromJson(new StringReader("""
                {
                  "schemaVersion": 5,
                  "id": "%s",
                  "name": "Hook Subscriber Test",
                  "version": "1.0.0",
                  "type": "java",
                  "entrypoint": "%s",
                  "permissions": ["launcher-hook"],
                  "requiredPermissions": ["launcher-hook"],
                  "launcherVersion": "*",
                  "runtime": "java",
                  "abi": 2,
                  "hooks": %s,
                  "dependencies": %s
                }
                """.formatted(pluginId, HookPlugin.class.getName(), hooks, dependencies)));
    }

    /// Parses one executable schema-v4 Java manifest that cannot declare Hooks.
    ///
    /// @param pluginId plugin ID
    /// @return validated schema-v4 manifest
    /// @throws IOException if the generated manifest is invalid
    private static PluginManifest schemaFourManifest(String pluginId) throws IOException {
        return PluginManifest.fromJson(new StringReader("""
                {
                  "schemaVersion": 4,
                  "id": "%s",
                  "name": "Schema Four Subscriber Test",
                  "version": "1.0.0",
                  "type": "java",
                  "entrypoint": "%s",
                  "permissions": [],
                  "requiredPermissions": [],
                  "launcherVersion": "*",
                  "dependencies": []
                }
                """.formatted(pluginId, HookPlugin.class.getName())));
    }

    /// Returns the current thread's read-lock hold count for the manager state lock.
    ///
    /// @param manager manager whose lock is inspected
    /// @return current-thread read hold count
    private static int currentThreadReadHoldCount(PluginManager manager) {
        try {
            Field field = PluginManager.class.getDeclaredField("stateLock");
            field.setAccessible(true);
            ReadWriteLock lock = (ReadWriteLock) field.get(manager);
            return ((ReentrantReadWriteLock) lock).getReadHoldCount();
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Cannot inspect plugin manager state lock", exception);
        }
    }

    /// Creates one minimal immutable event for endpoint adapter assertions.
    ///
    /// @param point dispatched Hook point
    /// @return test event
    private static PluginHookEvent event(PluginHookPoint point) {
        return new PluginHookEvent(
                PluginHookEvent.CURRENT_CONTRACT_VERSION,
                "subscriber-order-test",
                point,
                Instant.EPOCH,
                PluginDataObject.empty(),
                PluginSecretAccess.denied("dev.test.callback-scope")
        );
    }

    /// Minimal lifecycle fixture with a caller-supplied Hook callback.
    @NotNullByDefault
    private static final class HookPlugin implements Plugin {
        /// Authoritative test manifest.
        private final PluginManifest manifest;

        /// Hook callback behavior.
        private final Function<PluginHookEvent, PluginHookResult> hook;

        /// Creates a no-op Hook fixture.
        ///
        /// @param manifest authoritative manifest
        private HookPlugin(PluginManifest manifest) {
            this(manifest, event -> PluginHookResult.unchanged());
        }

        /// Creates a Hook fixture with caller-selected callback behavior.
        ///
        /// @param manifest authoritative manifest
        /// @param hook Hook callback behavior
        private HookPlugin(
                PluginManifest manifest,
                Function<PluginHookEvent, PluginHookResult> hook
        ) {
            this.manifest = manifest;
            this.hook = hook;
        }

        /// Accepts the already captured test context.
        ///
        /// @param context plugin context
        @Override
        public void onLoad(PluginContext context) {
        }

        /// Enables without side effects.
        @Override
        public void onEnable() {
        }

        /// Disables without side effects.
        @Override
        public void onDisable() {
        }

        /// Applies the caller-supplied Hook behavior.
        ///
        /// @param event immutable Hook event
        /// @return callback result
        @Override
        public PluginHookResult onHook(PluginHookEvent event) {
            return hook.apply(event);
        }

        /// Returns the fixture manifest.
        ///
        /// @return plugin manifest
        @Override
        public PluginManifest getManifest() {
            return manifest;
        }
    }

    /// URL class loader that counts close requests for lease lifetime assertions.
    @NotNullByDefault
    private static final class TrackingClassLoader extends URLClassLoader {
        /// Number of physical close calls.
        private final AtomicInteger closeCount = new AtomicInteger();

        /// Creates an empty child class loader of the HMCL application loader.
        private TrackingClassLoader() {
            super(new URL[0], PluginManager.class.getClassLoader());
        }

        /// Records and performs one physical close.
        ///
        /// @throws IOException if the base loader cannot close
        @Override
        public void close() throws IOException {
            closeCount.incrementAndGet();
            super.close();
        }

        /// Returns the number of physical close calls.
        ///
        /// @return close count
        private int closeCount() {
            return closeCount.get();
        }
    }
}
