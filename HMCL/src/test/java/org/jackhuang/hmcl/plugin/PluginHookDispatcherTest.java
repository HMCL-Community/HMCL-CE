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
import org.jackhuang.hmcl.plugin.bridge.PluginPermissionAuthority;
import org.jackhuang.hmcl.plugin.runtime.PluginAbi;
import org.jackhuang.hmcl.plugin.runtime.PluginCompatibilityEvaluator;
import org.jackhuang.hmcl.plugin.runtime.PluginExecutionMode;
import org.jackhuang.hmcl.plugin.runtime.PluginPlatformTarget;
import org.jackhuang.hmcl.plugin.runtime.RuntimeFeature;
import org.jackhuang.hmcl.plugin.runtime.RuntimeHookEndpoint;
import org.jackhuang.hmcl.plugin.runtime.RuntimeProvider;
import org.jackhuang.hmcl.plugin.runtime.RuntimeProviderDeclaration;
import org.jackhuang.hmcl.plugin.runtime.RuntimeProviderDescriptor;
import org.jackhuang.hmcl.plugin.runtime.RuntimeProviderRegistry;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.StringReader;
import java.lang.reflect.Field;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies transactional Hook dispatch, categorized failures, timeout isolation, and callback lease ownership.
@NotNullByDefault
public final class PluginHookDispatcherTest {
    /// Fixed callback time used by deterministic event policies.
    private static final Instant CALLBACK_TIME = Instant.parse("2026-08-24T00:00:00Z");

    /// Executors created by each test and stopped after assertions complete.
    private final List<ExecutorService> executors = new ArrayList<>();

    /// Stops every test-owned daemon executor.
    @AfterEach
    public void stopExecutors() {
        executors.forEach(ExecutorService::shutdownNow);
    }

    /// Commits each validated replacement before constructing the next subscriber's immutable event.
    @Test
    public void dispatchBeforeCommitsValidatedResultsInOrder() {
        List<String> observed = new ArrayList<>();
        AtomicInteger released = new AtomicInteger();
        NamePolicy policy = new NamePolicy(true);
        PluginHookDispatcher dispatcher = dispatcher(Duration.ofSeconds(1), () -> List.of(
                subscriber("dev.test.alpha", event -> {
                    observed.add(event.data().requireString("name"));
                    return replaceName("alpha");
                }, released),
                subscriber("dev.test.beta", event -> {
                    observed.add(event.data().requireString("name"));
                    return replaceName("beta");
                }, released)
        ));

        PluginDataObject result = dispatcher.dispatchBefore(
                PluginHookPoint.BEFORE_GAME_LAUNCH,
                dataWithName("initial"),
                policy
        );

        assertEquals(List.of("initial", "alpha"), observed);
        assertEquals("beta", result.requireString("name"));
        assertEquals(List.of("alpha", "beta"), policy.committedNames());
        assertEquals(2, released.get());
    }

    /// Preserves data through unchanged results and returns the original object when no subscriber exists.
    @Test
    public void preserveUnchangedAndEmptyDispatchData() {
        PluginDataObject initial = dataWithName("initial");
        AtomicInteger released = new AtomicInteger();
        NamePolicy policy = new NamePolicy(true);
        PluginHookDispatcher unchangedDispatcher = dispatcher(Duration.ofSeconds(1), () -> List.of(
                subscriber("dev.test.unchanged", event -> PluginHookResult.unchanged(), released)
        ));
        PluginHookDispatcher emptyDispatcher = dispatcher(Duration.ofSeconds(1), List::of);

        assertSame(initial, unchangedDispatcher.dispatchBefore(
                PluginHookPoint.BEFORE_GAME_LAUNCH, initial, policy));
        assertSame(initial, emptyDispatcher.dispatchBefore(
                PluginHookPoint.BEFORE_GAME_LAUNCH, initial, policy));
        assertTrue(policy.committedNames().isEmpty());
        assertEquals(1, released.get());
    }

    /// Rejects one malformed replacement before its commit and never invokes later subscribers.
    @Test
    public void rejectMalformedReplacementBeforeCommitAndFailFast() {
        AtomicBoolean laterInvoked = new AtomicBoolean();
        AtomicInteger released = new AtomicInteger();
        NamePolicy policy = new NamePolicy(true);
        PluginHookDispatcher dispatcher = dispatcher(Duration.ofSeconds(1), () -> List.of(
                subscriber("dev.test.valid", event -> replaceName("valid"), released),
                subscriber("dev.test.malformed", event -> PluginHookResult.replace(
                        PluginDataObject.empty()), released),
                subscriber("dev.test.later", event -> {
                    laterInvoked.set(true);
                    return PluginHookResult.unchanged();
                }, released)
        ));

        PluginHookDispatchException failure = assertThrows(PluginHookDispatchException.class,
                () -> dispatcher.dispatchBefore(
                        PluginHookPoint.BEFORE_GAME_LAUNCH, dataWithName("initial"), policy));

        assertEquals(PluginHookDispatchException.Category.INVALID_RESULT, failure.category());
        assertEquals("dev.test.malformed", failure.pluginId());
        assertEquals(List.of("valid"), policy.committedNames());
        assertFalse(laterInvoked.get());
        assertEquals(3, released.get());
    }

    /// Converts an allowed before cancellation into a dedicated failure and closes untouched subscribers.
    @Test
    public void cancelBeforeDispatchAndReleaseUntouchedSubscribers() {
        AtomicBoolean laterInvoked = new AtomicBoolean();
        AtomicInteger released = new AtomicInteger();
        PluginHookDispatcher dispatcher = dispatcher(Duration.ofSeconds(1), () -> List.of(
                subscriber("dev.test.policy", event ->
                        PluginHookResult.cancel("policy-denied", "Launch denied by policy"), released),
                subscriber("dev.test.later", event -> {
                    laterInvoked.set(true);
                    return PluginHookResult.unchanged();
                }, released)
        ));

        PluginHookDispatchException failure = assertThrows(PluginHookDispatchException.class,
                () -> dispatcher.dispatchBefore(
                        PluginHookPoint.BEFORE_GAME_LAUNCH,
                        dataWithName("initial"),
                        new NamePolicy(true)
                ));

        assertEquals(PluginHookDispatchException.Category.CANCELLED, failure.category());
        assertEquals("dev.test.policy", failure.pluginId());
        assertEquals("policy-denied", failure.cancellationReasonCode());
        assertEquals("Launch denied by policy", failure.cancellationMessage());
        assertFalse(failure.getMessage().contains("policy-denied"));
        assertFalse(failure.getMessage().contains("Launch denied by policy"));
        assertFalse(laterInvoked.get());
        assertEquals(2, released.get());
    }

    /// Passes the dispatcher deadline through the runtime adapter while retaining common cancellation and lease policy.
    @Test
    public void dispatchRuntimeEndpointWithCommonDeadlineCancellationAndLeasePolicy() {
        Duration timeout = Duration.ofMillis(375);
        PluginArtifactIdentity identity = new PluginArtifactIdentity(
                "dev.test.runtime-hook",
                "1.0.0-next",
                "c".repeat(64)
        );
        PluginPermissionAuthority authority = new PluginPermissionAuthority();
        PluginCapabilitySession session = authority.openSession(
                identity,
                PluginExecutionMode.EMBEDDED,
                () -> Set.of(PluginPermission.LAUNCHER_HOOK),
                "runtime.payload",
                Duration.ofSeconds(30)
        );
        AtomicReference<PluginHookEvent> receivedEvent = new AtomicReference<>();
        AtomicReference<Duration> receivedTimeout = new AtomicReference<>();
        AtomicInteger released = new AtomicInteger();
        RuntimeHookEndpoint endpoint = new RuntimeHookEndpoint(
                identity,
                PluginExecutionMode.EMBEDDED,
                authority,
                session::issue,
                (ownerPluginId, token, event, providerTimeout) -> {
                    receivedEvent.set(event);
                    receivedTimeout.set(providerTimeout);
                    return PluginHookResult.cancel("runtime-policy", "Runtime Provider cancelled launch");
                }
        );
        PluginHookDispatcher dispatcher = dispatcher(timeout, () -> List.of(
                subscriber(identity.getPluginId(), endpoint, released)
        ));

        PluginHookDispatchException failure = assertThrows(PluginHookDispatchException.class,
                () -> dispatcher.dispatchBefore(
                        PluginHookPoint.BEFORE_GAME_LAUNCH,
                        dataWithName("redacted"),
                        new NamePolicy(true)
                ));

        assertEquals(PluginHookDispatchException.Category.CANCELLED, failure.category());
        assertEquals(timeout, receivedTimeout.get());
        assertEquals("redacted", receivedEvent.get().data().requireString("name"));
        assertThrows(SecurityException.class,
                () -> receivedEvent.get().secrets().resolve("access-token"));
        assertEquals(1, released.get());
    }

    /// Routes an external subscriber through its bound Provider and leases both payload and shared Host containers.
    ///
    /// @param temporaryDirectory isolated manager and package paths
    /// @throws Exception if manifest parsing, registration, callback invocation, or loader close fails
    @Test
    @EnabledIf("org.jackhuang.hmcl.JavaFXLauncher#isStarted")
    public void snapshotExternalSubscriberThroughBoundProviderWithDualLease(
            @TempDir Path temporaryDirectory
    ) throws Exception {
        RuntimeProviderRegistry registry = new RuntimeProviderRegistry();
        ManagerRuntimeProvider provider = new ManagerRuntimeProvider();
        registry.register(provider);
        PluginManager manager = new PluginManager(
                temporaryDirectory.resolve("home"),
                new PluginCompatibilityEvaluator(registry, PluginPlatformTarget.current())
        );
        PluginManifest hostManifest = managerManifest(
                ManagerRuntimeProvider.PROVIDER_ID,
                "java",
                List.of(PluginHookPoint.BEFORE_GAME_LAUNCH),
                List.of()
        );
        PluginManifest payloadManifest = managerManifest(
                ManagerRuntimeProvider.PAYLOAD_ID,
                "rust",
                List.of(PluginHookPoint.BEFORE_GAME_LAUNCH),
                List.of(ManagerRuntimeProvider.PROVIDER_ID)
        );
        registry.bind(payloadManifest.getId(), payloadManifest.getRuntimeRequirement());
        TrackingClassLoader hostLoader = new TrackingClassLoader();
        TrackingClassLoader payloadLoader = new TrackingClassLoader();
        PluginContainer hostContainer = registerManagerFixture(
                manager,
                temporaryDirectory,
                new ManagerFixturePlugin(hostManifest),
                hostManifest,
                "d".repeat(64),
                hostLoader,
                null
        );
        PluginPermissionAuthority authority = managerPermissionAuthority(manager);
        PluginArtifactIdentity payloadIdentity = PluginArtifactIdentity.of(
                payloadManifest, "e".repeat(64));
        PluginCapabilitySession payloadSession = authority.openSession(
                payloadIdentity,
                PluginExecutionMode.EMBEDDED,
                () -> Set.of(PluginPermission.LAUNCHER_HOOK),
                "runtime.payload",
                Duration.ofSeconds(30)
        );
        PluginContainer payloadContainer = registerManagerFixture(
                manager,
                temporaryDirectory,
                new ManagerFixturePlugin(payloadManifest),
                payloadManifest,
                payloadIdentity.getSha256(),
                payloadLoader,
                payloadSession
        );

        @Unmodifiable List<PluginHookSubscriber> subscribers =
                manager.snapshotHookSubscribers(PluginHookPoint.BEFORE_GAME_LAUNCH);
        try {
            assertEquals(List.of(hostManifest.getId(), payloadManifest.getId()),
                    subscribers.stream().map(PluginHookSubscriber::pluginId).toList());
            PluginHookEvent event = new PluginHookEvent(
                    PluginHookEvent.CURRENT_CONTRACT_VERSION,
                    "manager-runtime-hook",
                    PluginHookPoint.BEFORE_GAME_LAUNCH,
                    CALLBACK_TIME,
                    dataWithName("external"),
                    PluginSecretAccess.denied(payloadManifest.getId())
            );

            assertEquals(PluginHookResult.Action.UNCHANGED,
                    subscribers.get(0).endpoint().invoke(event, Duration.ofMillis(225)).action());
            assertEquals(PluginHookResult.Action.UNCHANGED,
                    subscribers.get(1).endpoint().invoke(event, Duration.ofMillis(225)).action());
            assertTrue(provider.invoked());
            assertSame(hostLoader, provider.callbackClassLoader());

            hostContainer.closeClassLoader();
            payloadContainer.closeClassLoader();
            assertEquals(0, hostLoader.physicalCloseCount());
            assertEquals(0, payloadLoader.physicalCloseCount());
        } finally {
            subscribers.forEach(PluginHookSubscriber::close);
            payloadSession.close();
        }
        assertEquals(1, hostLoader.physicalCloseCount());
        assertEquals(1, payloadLoader.physicalCloseCount());
    }

    /// Rejects construction of an unvalidated cancelled category without cancellation fields.
    @Test
    public void rejectUnvalidatedCancelledCategoryConstruction() {
        assertThrows(IllegalArgumentException.class, () -> new PluginHookDispatchException(
                PluginHookPoint.BEFORE_GAME_LAUNCH,
                "dev.test.forged-cancel",
                PluginHookDispatchException.Category.CANCELLED
        ));
    }

    /// Categorizes endpoint exceptions without retaining the plugin-controlled throwable.
    @Test
    public void categorizeEndpointExceptionWithoutLeakingCauseText() {
        AtomicInteger released = new AtomicInteger();
        IllegalStateException endpointFailure = new IllegalStateException("credential-value");
        PluginHookDispatcher dispatcher = dispatcher(Duration.ofSeconds(1), () -> List.of(
                subscriber("dev.test.failure", event -> {
                    throw endpointFailure;
                }, released)
        ));

        PluginHookDispatchException failure = assertThrows(PluginHookDispatchException.class,
                () -> dispatcher.dispatchBefore(
                        PluginHookPoint.BEFORE_GAME_LAUNCH,
                        dataWithName("initial"),
                        new NamePolicy(true)
                ));

        assertEquals(PluginHookDispatchException.Category.EXCEPTION, failure.category());
        assertNull(failure.getCause());
        assertNull(failure.cancellationReasonCode());
        assertNull(failure.cancellationMessage());
        assertFalse(failure.getMessage().contains("credential-value"));
        assertEquals(1, released.get());
    }

    /// Rejects a null endpoint result as malformed callback output.
    @Test
    public void rejectNullEndpointResult() {
        AtomicInteger released = new AtomicInteger();
        PluginHookDispatcher dispatcher = dispatcher(Duration.ofSeconds(1), () -> List.of(
                subscriber("dev.test.null-result", event -> null, released)
        ));

        PluginHookDispatchException failure = assertThrows(PluginHookDispatchException.class,
                () -> dispatcher.dispatchBefore(
                        PluginHookPoint.BEFORE_GAME_LAUNCH,
                        dataWithName("initial"),
                        new NamePolicy(true)
                ));

        assertEquals(PluginHookDispatchException.Category.INVALID_RESULT, failure.category());
        assertEquals(1, released.get());
    }

    /// Preserves the infrastructure category exposed by a declared subscriber with no callable endpoint.
    @Test
    public void preserveMissingEndpointFailureCategory() {
        AtomicInteger released = new AtomicInteger();
        PluginHookDispatchException missing = new PluginHookDispatchException(
                PluginHookPoint.BEFORE_GAME_LAUNCH,
                "dev.test.missing",
                PluginHookDispatchException.Category.MISSING_ENDPOINT
        );
        PluginHookDispatcher dispatcher = dispatcher(Duration.ofSeconds(1), () -> List.of(
                subscriber("dev.test.missing", event -> {
                    throw missing;
                }, released)
        ));

        PluginHookDispatchException failure = assertThrows(PluginHookDispatchException.class,
                () -> dispatcher.dispatchBefore(
                        PluginHookPoint.BEFORE_GAME_LAUNCH,
                        dataWithName("initial"),
                        new NamePolicy(true)
                ));

        assertSame(missing, failure);
        assertEquals(1, released.get());
    }

    /// Reports invalid cancellation and other after failures while continuing with the original notification data.
    @Test
    public void dispatchAfterReportsFailuresAndContinuesWithoutCommittingReplacement() {
        List<String> observed = new ArrayList<>();
        AtomicInteger released = new AtomicInteger();
        NamePolicy policy = new NamePolicy(false, PluginHookPoint.AFTER_GAME_LAUNCH);
        PluginHookDispatcher dispatcher = dispatcher(Duration.ofSeconds(1), () -> List.of(
                subscriber("dev.test.cancel", event ->
                        PluginHookResult.cancel("too-late", "Cannot cancel an after Hook"), released),
                subscriber("dev.test.exception", event -> {
                    throw new IllegalStateException("after failure");
                }, released),
                subscriber("dev.test.replace", event -> replaceName("ignored"), released),
                subscriber("dev.test.null", event -> null, released),
                subscriber("dev.test.final", event -> {
                    observed.add(event.data().requireString("name"));
                    return PluginHookResult.unchanged();
                }, released)
        ));

        dispatcher.dispatchAfter(
                PluginHookPoint.AFTER_GAME_LAUNCH,
                dataWithName("notification"),
                policy
        );

        assertEquals(List.of("notification"), observed);
        assertEquals(List.of(
                PluginHookDispatchException.Category.INVALID_RESULT,
                PluginHookDispatchException.Category.EXCEPTION,
                PluginHookDispatchException.Category.INVALID_RESULT
        ), policy.failureCategories());
        assertNull(policy.failures().get(0).cancellationReasonCode());
        assertNull(policy.failures().get(0).cancellationMessage());
        assertFalse(policy.failures().get(0).getMessage().contains("too-late"));
        assertFalse(policy.failures().get(0).getMessage().contains("Cannot cancel an after Hook"));
        assertTrue(policy.committedNames().isEmpty());
        assertEquals(5, released.get());
    }

    /// Runs a direct subscriber through the manager endpoint adapter with its exact class loader as TCCL.
    ///
    /// @param temporaryDirectory isolated manager home
    /// @throws Exception if endpoint invocation or class-loader close fails
    @Test
    public void preserveManagerEndpointTcclScope(@TempDir Path temporaryDirectory) throws Exception {
        PluginManager manager = new PluginManager(temporaryDirectory.resolve("home"));
        try (URLClassLoader endpointClassLoader = new URLClassLoader(
                new URL[0], PluginManager.class.getClassLoader())) {
            AtomicInteger released = new AtomicInteger();
            PluginHookDispatcher dispatcher = dispatcher(Duration.ofSeconds(1), () -> List.of(
                    subscriber("dev.test.tccl", event -> manager.runPluginCallback(
                            endpointClassLoader,
                            () -> {
                                assertSame(endpointClassLoader, Thread.currentThread().getContextClassLoader());
                                return PluginHookResult.unchanged();
                            }
                    ), released)
            ));

            dispatcher.dispatchBefore(
                    PluginHookPoint.BEFORE_GAME_LAUNCH,
                    dataWithName("initial"),
                    new NamePolicy(true)
            );

            assertEquals(1, released.get());
        }
    }

    /// Interrupts a timed-out callback, discards its late result, and retains its lease until it actually exits.
    ///
    /// @throws Exception if latch waits are interrupted
    @Test
    public void timeoutInterruptsAndDiscardsLateResultWhileRetainingRunningLease() throws Exception {
        CountDownLatch callbackStarted = new CountDownLatch(1);
        CountDownLatch callbackInterrupted = new CountDownLatch(1);
        CountDownLatch allowCallbackExit = new CountDownLatch(1);
        CountDownLatch callbackFinished = new CountDownLatch(1);
        AtomicInteger timedOutRelease = new AtomicInteger();
        AtomicInteger untouchedRelease = new AtomicInteger();
        AtomicBoolean laterInvoked = new AtomicBoolean();
        NamePolicy policy = new NamePolicy(true);
        PluginHookDispatcher dispatcher = dispatcher(Duration.ofMillis(80), () -> List.of(
                subscriber("dev.test.timeout", event -> {
                    assertTrue(Thread.currentThread().isDaemon());
                    callbackStarted.countDown();
                    boolean waiting = true;
                    while (waiting) {
                        try {
                            waiting = !allowCallbackExit.await(20, TimeUnit.MILLISECONDS);
                        } catch (InterruptedException exception) {
                            callbackInterrupted.countDown();
                        }
                    }
                    callbackFinished.countDown();
                    return replaceName("late-result");
                }, timedOutRelease),
                subscriber("dev.test.untouched", event -> {
                    laterInvoked.set(true);
                    return PluginHookResult.unchanged();
                }, untouchedRelease)
        ));

        PluginHookDispatchException failure = assertThrows(PluginHookDispatchException.class,
                () -> dispatcher.dispatchBefore(
                        PluginHookPoint.BEFORE_GAME_LAUNCH,
                        dataWithName("initial"),
                        policy
                ));

        assertTrue(callbackStarted.await(1, TimeUnit.SECONDS));
        assertTrue(callbackInterrupted.await(1, TimeUnit.SECONDS));
        assertEquals(PluginHookDispatchException.Category.TIMEOUT, failure.category());
        assertEquals(0, timedOutRelease.get());
        assertEquals(1, untouchedRelease.get());
        assertFalse(laterInvoked.get());
        assertTrue(policy.committedNames().isEmpty());

        allowCallbackExit.countDown();
        assertTrue(callbackFinished.await(1, TimeUnit.SECONDS));
        awaitValue(timedOutRelease, 1);
        assertTrue(policy.committedNames().isEmpty());
    }

    /// Releases a queued subscriber immediately when timeout cancellation wins before endpoint execution starts.
    ///
    /// @throws Exception if latch waits are interrupted
    @Test
    public void releaseLeaseWhenTimedOutTaskIsCancelledBeforeStarting() throws Exception {
        ExecutorService executor = daemonExecutor(1);
        CountDownLatch allowWorker = new CountDownLatch(1);
        CountDownLatch blockerStarted = new CountDownLatch(1);
        executor.submit(() -> {
            blockerStarted.countDown();
            try {
                allowWorker.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(blockerStarted.await(1, TimeUnit.SECONDS));
        AtomicBoolean endpointInvoked = new AtomicBoolean();
        AtomicInteger released = new AtomicInteger();
        PluginHookDispatcher dispatcher = dispatcher(
                executor,
                Duration.ofMillis(60),
                () -> List.of(subscriber("dev.test.queued", event -> {
                    endpointInvoked.set(true);
                    return PluginHookResult.unchanged();
                }, released))
        );

        PluginHookDispatchException failure = assertThrows(PluginHookDispatchException.class,
                () -> dispatcher.dispatchBefore(
                        PluginHookPoint.BEFORE_GAME_LAUNCH,
                        dataWithName("initial"),
                        new NamePolicy(true)
                ));

        assertEquals(PluginHookDispatchException.Category.TIMEOUT, failure.category());
        assertEquals(1, released.get());
        allowWorker.countDown();
        assertFalse(endpointInvoked.get());
    }

    /// Rejects a zero callback timeout during dispatcher construction.
    @Test
    public void rejectNonPositiveTimeout() {
        ExecutorService executor = daemonExecutor(1);

        assertThrows(IllegalArgumentException.class, () -> new PluginHookDispatcher(
                executor,
                Duration.ZERO,
                Clock.fixed(CALLBACK_TIME, ZoneOffset.UTC),
                point -> List.of()
        ));
    }

    /// Creates a dispatcher with a fresh two-worker daemon executor.
    ///
    /// @param timeout per-callback deadline
    /// @param subscribers subscriber snapshot source
    /// @return test dispatcher
    private PluginHookDispatcher dispatcher(
            Duration timeout,
            Supplier<@Unmodifiable List<PluginHookSubscriber>> subscribers
    ) {
        return dispatcher(daemonExecutor(2), timeout, subscribers);
    }

    /// Creates a dispatcher with an explicit executor.
    ///
    /// @param executor callback executor
    /// @param timeout per-callback deadline
    /// @param subscribers subscriber snapshot source
    /// @return test dispatcher
    private static PluginHookDispatcher dispatcher(
            ExecutorService executor,
            Duration timeout,
            Supplier<@Unmodifiable List<PluginHookSubscriber>> subscribers
    ) {
        return new PluginHookDispatcher(
                executor,
                timeout,
                Clock.fixed(CALLBACK_TIME, ZoneOffset.UTC),
                point -> subscribers.get()
        );
    }

    /// Creates and records a fixed-size daemon executor.
    ///
    /// @param threads worker count
    /// @return executor
    private ExecutorService daemonExecutor(int threads) {
        AtomicInteger number = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(threads, runnable -> {
            Thread thread = new Thread(runnable, "plugin-hook-dispatch-test-" + number.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        executors.add(executor);
        return executor;
    }

    /// Creates one direct subscriber double with an observable idempotent release action.
    ///
    /// @param pluginId plugin ID
    /// @param endpoint endpoint behavior
    /// @param releaseCount lease release counter
    /// @return subscriber double
    private static PluginHookSubscriber subscriber(
            String pluginId,
            PluginHookEndpoint endpoint,
            AtomicInteger releaseCount
    ) {
        return new PluginHookSubscriber(
                pluginId,
                Set.of(),
                Set.of(PluginPermission.LAUNCHER_HOOK),
                endpoint,
                releaseCount::incrementAndGet
        );
    }

    /// Creates immutable callback data with one required name field.
    ///
    /// @param name name value
    /// @return callback data
    private static PluginDataObject dataWithName(String name) {
        return PluginDataObject.of(java.util.Map.of("name", PluginDataValue.string(name)));
    }

    /// Creates one complete replacement with the supplied name.
    ///
    /// @param name replacement name
    /// @return replacement result
    private static PluginHookResult replaceName(String name) {
        return PluginHookResult.replace(dataWithName(name));
    }

    /// Registers one synthetic manager container with an optional external capability session.
    ///
    /// @param manager target manager
    /// @param temporaryDirectory isolated package paths
    /// @param plugin fixture lifecycle implementation
    /// @param manifest authoritative fixture manifest
    /// @param artifactSha256 exact fixture artifact digest
    /// @param classLoader fixture callback class loader
    /// @param capabilitySession optional external payload capability session
    /// @throws Exception if JavaFX registration fails
    private static PluginContainer registerManagerFixture(
            PluginManager manager,
            Path temporaryDirectory,
            Plugin plugin,
            PluginManifest manifest,
            String artifactSha256,
            ClassLoader classLoader,
            @Nullable PluginCapabilitySession capabilitySession
    ) throws Exception {
        PluginContext context = new PluginContext(
                manifest,
                temporaryDirectory.resolve("package-" + manifest.getId()),
                temporaryDirectory.resolve("data-" + manifest.getId()),
                classLoader,
                artifactSha256,
                () -> manifest.getHooks().isEmpty()
                        ? Set.of()
                        : Set.of(PluginPermission.LAUNCHER_HOOK),
                provider -> {
                    throw new IllegalStateException("Synthetic fixture does not register Providers through context");
                },
                null,
                capabilitySession
        );
        PreparedPlugin prepared = new PreparedPlugin(
                plugin,
                context,
                manifest,
                temporaryDirectory.resolve(manifest.getId() + ".npl")
        );
        AtomicReference<PluginContainer> registered = new AtomicReference<>();
        FXThreadTestSupport.runOnFxThread(() -> registered.set(manager.registerPreparedPlugin(prepared)));
        PluginContainer container = java.util.Objects.requireNonNull(registered.get());
        container.setEnabled(true);
        return container;
    }

    /// Reads the isolated manager's launcher-owned authority for an exact external payload context fixture.
    ///
    /// @param manager target isolated manager
    /// @return launcher-owned permission authority
    /// @throws ReflectiveOperationException if the authority field cannot be read
    private static PluginPermissionAuthority managerPermissionAuthority(PluginManager manager)
            throws ReflectiveOperationException {
        Field field = PluginManager.class.getDeclaredField("permissionAuthority");
        field.setAccessible(true);
        return (PluginPermissionAuthority) field.get(manager);
    }

    /// Parses one minimal schema-v5 manager fixture manifest.
    ///
    /// @param pluginId canonical plugin ID
    /// @param runtime canonical runtime ID
    /// @param hooks declared Hook points
    /// @param dependencies declared dependency plugin IDs
    /// @return validated fixture manifest
    /// @throws IOException if the fixture JSON is invalid
    private static PluginManifest managerManifest(
            String pluginId,
            String runtime,
            @Unmodifiable List<PluginHookPoint> hooks,
            @Unmodifiable List<String> dependencies
    ) throws IOException {
        String hookJson = hooks.stream()
                .map(point -> "\"" + point.getId() + "\"")
                .collect(java.util.stream.Collectors.joining(", ", "[", "]"));
        String permissions = hooks.isEmpty() ? "[]" : "[\"launcher-hook\"]";
        String dependencyJson = dependencies.stream()
                .map(dependency -> "\"" + dependency + "\"")
                .collect(java.util.stream.Collectors.joining(", ", "[", "]"));
        return PluginManifest.fromJson(new StringReader("""
                {
                  "schemaVersion": 5,
                  "id": "%s",
                  "name": "Runtime Hook Manager Fixture",
                  "version": "1.0.0-next",
                  "type": "java",
                  "entrypoint": "%s",
                  "permissions": %s,
                  "requiredPermissions": %s,
                  "launcherVersion": "*",
                  "runtime": "%s",
                  "abi": 2,
                  "hooks": %s,
                  "dependencies": %s
                }
                """.formatted(
                pluginId,
                ManagerFixturePlugin.class.getName(),
                permissions,
                permissions,
                runtime,
                hookJson,
                dependencyJson
        )));
    }

    /// Waits briefly for an asynchronous counter to reach an expected value.
    ///
    /// @param value observed counter
    /// @param expected expected value
    /// @throws InterruptedException if the wait is interrupted
    private static void awaitValue(AtomicInteger value, int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (value.get() != expected && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertEquals(expected, value.get());
    }

    /// Validates name-bearing callback data and records atomic candidate commits and after failures.
    @NotNullByDefault
    private static final class NamePolicy implements PluginHookDispatcher.Policy {
        /// Whether this policy permits deliberate cancellation.
        private final boolean cancellationAllowed;

        /// Hook point placed in events constructed by this policy.
        private final PluginHookPoint point;

        /// Names whose validated replacement commits ran.
        private final List<String> committedNames = new ArrayList<>();

        /// Best-effort after failures reported by the dispatcher.
        private final List<PluginHookDispatchException> afterFailures = new ArrayList<>();

        /// Creates one name policy.
        ///
        /// @param cancellationAllowed whether cancellation is valid
        private NamePolicy(boolean cancellationAllowed) {
            this(cancellationAllowed, PluginHookPoint.BEFORE_GAME_LAUNCH);
        }

        /// Creates one name policy for an explicit Hook point.
        ///
        /// @param cancellationAllowed whether cancellation is valid
        /// @param point Hook point placed in callback events
        private NamePolicy(boolean cancellationAllowed, PluginHookPoint point) {
            this.cancellationAllowed = cancellationAllowed;
            this.point = point;
        }

        /// Builds one fresh event around the currently committed data.
        ///
        /// @param subscriber current subscriber
        /// @param currentData currently committed callback data
        /// @return immutable event
        @Override
        public PluginHookEvent eventFor(
                PluginHookSubscriber subscriber,
                PluginDataObject currentData
        ) {
            return new PluginHookEvent(
                    PluginHookEvent.CURRENT_CONTRACT_VERSION,
                    "dispatcher-test",
                    point,
                    CALLBACK_TIME,
                    currentData,
                    PluginSecretAccess.denied(subscriber.pluginId())
            );
        }

        /// Validates unchanged or complete name-bearing replacement data without mutating committed state.
        ///
        /// @param subscriber current subscriber
        /// @param currentData currently committed callback data
        /// @param result endpoint result
        /// @return staged candidate
        @Override
        public PluginHookDispatcher.Candidate validate(
                PluginHookSubscriber subscriber,
                PluginDataObject currentData,
                PluginHookResult result
        ) {
            if (result.action() == PluginHookResult.Action.UNCHANGED) {
                return new PluginHookDispatcher.Candidate(currentData, () -> {
                });
            }
            @Nullable PluginDataObject replacement = result.data();
            try {
                String name = java.util.Objects.requireNonNull(replacement, "replacement").requireString("name");
                return new PluginHookDispatcher.Candidate(replacement, () -> committedNames.add(name));
            } catch (RuntimeException exception) {
                throw new PluginHookDispatchException(
                        PluginHookPoint.BEFORE_GAME_LAUNCH,
                        subscriber.pluginId(),
                        PluginHookDispatchException.Category.INVALID_RESULT,
                        exception
                );
            }
        }

        /// Validates whether this policy permits a cancel result.
        ///
        /// @param subscriber cancelling subscriber
        /// @param result cancel endpoint result
        /// @throws PluginHookDispatchException if this policy rejects cancellation
        @Override
        public void validateCancellation(
                PluginHookSubscriber subscriber,
                PluginHookResult result
        ) throws PluginHookDispatchException {
            if (!cancellationAllowed) {
                throw new PluginHookDispatchException(
                        point,
                        subscriber.pluginId(),
                        PluginHookDispatchException.Category.INVALID_RESULT
                );
            }
        }

        /// Records one isolated after failure.
        ///
        /// @param subscriber failed subscriber
        /// @param failure categorized failure
        @Override
        public void reportAfterFailure(
                PluginHookSubscriber subscriber,
                PluginHookDispatchException failure
        ) {
            afterFailures.add(failure);
        }

        /// Returns committed replacement names.
        ///
        /// @return immutable commit list
        private @Unmodifiable List<String> committedNames() {
            return List.copyOf(committedNames);
        }

        /// Returns reported after failure categories.
        ///
        /// @return immutable category list
        private @Unmodifiable List<PluginHookDispatchException.Category> failureCategories() {
            return afterFailures.stream().map(PluginHookDispatchException::category).toList();
        }

        /// Returns reported after failures.
        ///
        /// @return immutable failure list
        private @Unmodifiable List<PluginHookDispatchException> failures() {
            return List.copyOf(afterFailures);
        }
    }

    /// Minimal lifecycle fixture whose Java Hook callback intentionally remains unchanged.
    @NotNullByDefault
    private static final class ManagerFixturePlugin implements Plugin {
        /// Authoritative fixture manifest.
        private final PluginManifest manifest;

        /// Creates one fixture plugin.
        ///
        /// @param manifest authoritative manifest
        private ManagerFixturePlugin(PluginManifest manifest) {
            this.manifest = manifest;
        }

        /// Accepts the synthetic context without side effects.
        ///
        /// @param context synthetic plugin context
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

        /// Returns the authoritative fixture manifest.
        ///
        /// @return fixture manifest
        @Override
        public PluginManifest getManifest() {
            return manifest;
        }
    }

    /// Selected Runtime Provider fixture that records external Hook callback scope.
    @NotNullByDefault
    private static final class ManagerRuntimeProvider
            implements RuntimeProvider, RuntimeHookEndpoint.ProviderInvoker {
        /// Canonical Provider Host ID.
        private static final String PROVIDER_ID = "dev.test.runtime-host";

        /// Canonical external payload ID.
        private static final String PAYLOAD_ID = "dev.test.runtime-payload";

        /// Whether the Provider Hook callback ran.
        private final AtomicBoolean invoked = new AtomicBoolean();

        /// TCCL observed by the Provider callback.
        private final AtomicReference<@Nullable ClassLoader> callbackClassLoader = new AtomicReference<>();

        /// Returns a compatible installed Rust Provider descriptor.
        ///
        /// @return Provider descriptor
        @Override
        public RuntimeProviderDescriptor descriptor() {
            return new RuntimeProviderDescriptor(
                    PROVIDER_ID,
                    "1.0.0-next",
                    List.of(new RuntimeProviderDeclaration(
                            "rust",
                            Set.of(PluginAbi.ABI_2),
                            1,
                            Set.of(PluginExecutionMode.EMBEDDED),
                            Set.of(RuntimeFeature.BRIDGE, RuntimeFeature.HOOKS)
                    )),
                    true,
                    true,
                    0,
                    false
            );
        }

        /// Records one Provider-backed Hook invocation.
        ///
        /// @param ownerPluginId exact payload owner
        /// @param token plugin-scoped token
        /// @param event immutable event
        /// @param timeout dispatcher deadline
        /// @return unchanged result
        @Override
        public PluginHookResult invokeHook(
                String ownerPluginId,
                org.jackhuang.hmcl.plugin.bridge.PluginCapabilityToken token,
                PluginHookEvent event,
                Duration timeout
        ) {
            assertEquals(PAYLOAD_ID, ownerPluginId);
            assertEquals(Duration.ofMillis(225), timeout);
            invoked.set(true);
            callbackClassLoader.set(Thread.currentThread().getContextClassLoader());
            return PluginHookResult.unchanged();
        }

        /// Returns whether the Provider callback ran.
        ///
        /// @return callback state
        private boolean invoked() {
            return invoked.get();
        }

        /// Returns the callback TCCL.
        ///
        /// @return observed class loader or `null`
        private @Nullable ClassLoader callbackClassLoader() {
            return callbackClassLoader.get();
        }
    }

    /// URL class loader that delays its physical close while manager callback leases remain active.
    @NotNullByDefault
    private static final class TrackingClassLoader extends URLClassLoader {
        /// Number of physical URL class-loader closes.
        private final AtomicInteger physicalCloseCount = new AtomicInteger();

        /// Creates an empty child loader.
        private TrackingClassLoader() {
            super(new URL[0], PluginManager.class.getClassLoader());
        }

        /// Records and performs one physical close.
        ///
        /// @throws IOException if the base loader cannot close
        @Override
        public void close() throws IOException {
            physicalCloseCount.incrementAndGet();
            super.close();
        }

        /// Returns the number of physical close calls.
        ///
        /// @return physical close count
        private int physicalCloseCount() {
            return physicalCloseCount.get();
        }
    }
}
