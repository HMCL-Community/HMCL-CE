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

import org.jackhuang.hmcl.ApplicationShutdownCoordinator;
import org.jackhuang.hmcl.game.GameLaunchHookProcessListener;
import org.jackhuang.hmcl.launch.LaunchAuxiliaryProcessPlan;
import org.jackhuang.hmcl.launch.LaunchCommandPlan;
import org.jackhuang.hmcl.launch.LaunchExecutionMode;
import org.jackhuang.hmcl.launch.LaunchPlanText;
import org.jackhuang.hmcl.launch.LaunchPreparation;
import org.jackhuang.hmcl.launch.LaunchProcessPlan;
import org.jackhuang.hmcl.launch.ProcessListener;
import org.jackhuang.hmcl.util.StringUtils;
import org.jackhuang.hmcl.util.platform.ManagedProcess;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies launch-scoped before-Hook coordination, transactional transformations, and secret isolation.
@NotNullByDefault
public final class GameLaunchHookCoordinatorTest {
    /// Deterministic launch and callback time.
    private static final Instant STARTED_AT = Instant.parse("2026-08-24T02:03:04Z");

    /// No-op delegate used to verify listener identity when no after event is owed.
    private static final ProcessListener NO_OP_LISTENER = new ProcessListener() {
        /// Ignores process logs.
        ///
        /// @param log log line
        /// @param isErrorStream whether the line came from stderr
        @Override
        public void onLog(String log, boolean isErrorStream) {
        }

        /// Ignores process exits.
        ///
        /// @param exitCode process exit code
        /// @param exitType classified exit type
        @Override
        public void onExit(int exitCode, ExitType exitType) {
        }
    };

    /// Executors owned by individual test coordinators.
    private final List<ExecutorService> executors = new ArrayList<>();

    /// Temporary root for absolute launch-plan paths.
    @TempDir
    public Path temporaryDirectory;

    /// Stops every test-owned callback executor.
    @AfterEach
    public void stopExecutors() {
        executors.forEach(ExecutorService::shutdownNow);
    }

    /// Preserves the caller's preparation when no before subscriber exists while creating immutable session state.
    @Test
    public void noSubscriberPreservesPreparationAndCreatesSessionIdentity() {
        LaunchPreparation original = preparation(LaunchExecutionMode.DIRECT);
        PluginDataObject metadata = metadata("direct");
        GameLaunchHookCoordinator coordinator = coordinator(List.of(), true);

        GameLaunchHookCoordinator.LaunchSession session = coordinator.beforeLaunch(original, metadata);

        assertSame(original, session.preparation());
        assertSame(original.plan(), session.finalPlan());
        assertEquals(metadata, session.metadata());
        assertNotSame(metadata, session.metadata());
        assertEquals(session.dispatchId(), UUID.fromString(session.dispatchId()).toString());
        assertTrue(session.hasAfterSubscribers());
    }

    /// Returns the original listener when no after subscriber was eligible for the direct session.
    @Test
    public void productionCompositionKeepsOriginalListenerWithoutAfterSubscriber() {
        GameLaunchHookCoordinator.LaunchSession session = coordinator(List.of(), false)
                .beforeLaunch(preparation(LaunchExecutionMode.DIRECT), metadata("direct"));

        assertSame(NO_OP_LISTENER, session.processListener(NO_OP_LISTENER));
    }

    /// Lets close-mode shutdown start immediately when no after subscriber owns a session lease.
    @Test
    public void productionCompositionCloseWithoutAfterSubscriberShutsDownImmediately() {
        AtomicInteger shutdowns = new AtomicInteger();
        AtomicInteger hides = new AtomicInteger();
        ApplicationShutdownCoordinator shutdownCoordinator =
                new ApplicationShutdownCoordinator(shutdowns::incrementAndGet, hides::incrementAndGet);
        GameLaunchHookCoordinator hookCoordinator = coordinator(
                List.of(), List.of(), false, shutdownCoordinator::acquireLease);

        GameLaunchHookCoordinator.LaunchSession session = hookCoordinator.beforeLaunch(
                preparation(LaunchExecutionMode.DIRECT), metadata("direct"));
        shutdownCoordinator.requestShutdown();

        assertNull(session.processListener(null));
        assertEquals(1, shutdowns.get());
        assertEquals(0, hides.get());
    }

    /// Installs a Hook-only listener for close mode and releases its shutdown lease without a created process.
    @Test
    public void productionCompositionCloseWithAfterSubscriberDefersUntilNoProcessClose() {
        AtomicInteger shutdowns = new AtomicInteger();
        AtomicInteger hides = new AtomicInteger();
        AtomicInteger afterCalls = new AtomicInteger();
        ApplicationShutdownCoordinator shutdownCoordinator =
                new ApplicationShutdownCoordinator(shutdowns::incrementAndGet, hides::incrementAndGet);
        GameLaunchHookCoordinator hookCoordinator = coordinator(
                List.of(),
                List.of(subscriber("dev.test.after-close", Set.of(), event -> {
                    afterCalls.incrementAndGet();
                    return PluginHookResult.unchanged();
                })),
                true,
                shutdownCoordinator::acquireLease
        );

        GameLaunchHookCoordinator.LaunchSession session = hookCoordinator.beforeLaunch(
                preparation(LaunchExecutionMode.DIRECT), metadata("direct"));
        shutdownCoordinator.requestShutdown();

        assertTrue(session.processListener(null) instanceof GameLaunchHookProcessListener);
        assertEquals(0, shutdowns.get());
        assertEquals(1, hides.get());
        session.closeWithoutProcess();
        assertEquals(1, shutdowns.get());
        assertEquals(0, afterCalls.get());
    }

    /// Keeps script generation free of process listeners and shutdown leases.
    @Test
    public void productionCompositionScriptKeepsNullListenerAndNoLease() {
        AtomicInteger shutdowns = new AtomicInteger();
        AtomicInteger hides = new AtomicInteger();
        ApplicationShutdownCoordinator shutdownCoordinator =
                new ApplicationShutdownCoordinator(shutdowns::incrementAndGet, hides::incrementAndGet);
        GameLaunchHookCoordinator hookCoordinator = coordinator(
                List.of(), List.of(), true, shutdownCoordinator::acquireLease);

        GameLaunchHookCoordinator.LaunchSession session = hookCoordinator.beforeLaunch(
                preparation(LaunchExecutionMode.SCRIPT), metadata("script"));
        shutdownCoordinator.requestShutdown();

        assertFalse(session.hasAfterSubscribers());
        assertNull(session.processListener(null));
        assertEquals(1, shutdowns.get());
        assertEquals(0, hides.get());
    }

    /// Acquires one direct-session shutdown lease and releases it idempotently at exit completion.
    @Test
    public void eligibleDirectSessionOwnsShutdownLeaseUntilFinishExit() {
        List<String> owners = new ArrayList<>();
        AtomicInteger releases = new AtomicInteger();
        GameLaunchHookCoordinator coordinator = coordinator(
                List.of(),
                List.of(),
                true,
                owner -> {
                    owners.add(owner);
                    return releases::incrementAndGet;
                }
        );

        GameLaunchHookCoordinator.LaunchSession session = coordinator.beforeLaunch(
                preparation(LaunchExecutionMode.DIRECT), metadata("direct"));

        assertEquals(List.of("after-game-launch:" + session.dispatchId()), owners);
        assertEquals(0, releases.get());
        session.finishExit();
        session.finishExit();
        assertEquals(1, releases.get());
    }

    /// Never acquires a shutdown lease for script generation even when an after subscriber is eligible.
    @Test
    public void scriptSessionNeverAcquiresShutdownLease() {
        AtomicInteger acquisitions = new AtomicInteger();
        GameLaunchHookCoordinator coordinator = coordinator(
                List.of(),
                List.of(),
                true,
                owner -> {
                    acquisitions.incrementAndGet();
                    return () -> {
                    };
                }
        );

        coordinator.beforeLaunch(preparation(LaunchExecutionMode.SCRIPT), metadata("script"));

        assertEquals(0, acquisitions.get());
    }

    /// Never acquires a shutdown lease for a direct session without an eligible after subscriber.
    @Test
    public void directSessionWithoutAfterSubscriberNeverAcquiresShutdownLease() {
        AtomicInteger acquisitions = new AtomicInteger();
        GameLaunchHookCoordinator coordinator = coordinator(
                List.of(),
                List.of(),
                false,
                owner -> {
                    acquisitions.incrementAndGet();
                    return () -> {
                    };
                }
        );

        coordinator.beforeLaunch(preparation(LaunchExecutionMode.DIRECT), metadata("direct"));

        assertEquals(0, acquisitions.get());
    }

    /// Releases the application lease only after the after callback has completed.
    @Test
    public void afterDispatchCompletesBeforeShutdownLeaseRelease() {
        List<String> order = new ArrayList<>();
        PluginHookSubscriber after = subscriber("dev.test.after-order", Set.of(), event -> {
            order.add("after");
            return PluginHookResult.unchanged();
        });
        GameLaunchHookCoordinator coordinator = coordinator(
                List.of(),
                List.of(after),
                true,
                owner -> () -> order.add("release")
        );
        GameLaunchHookCoordinator.LaunchSession session = coordinator.beforeLaunch(
                preparation(LaunchExecutionMode.DIRECT), metadata("direct"));

        session.afterLaunch(new GameLaunchHookProcessListener.ExitObservation(
                42L, 0, "normal", STARTED_AT, STARTED_AT.plusSeconds(1), 1000L));

        assertEquals(List.of("after"), order);
        session.finishExit();
        assertEquals(List.of("after", "release"), order);
    }

    /// Commits complete structured replacements in subscriber order, including every process-plan field.
    @Test
    public void sequentialStructuredTransformsReplaceEveryMutableField() {
        LaunchPreparation original = preparation(LaunchExecutionMode.DIRECT);
        PluginDataObject metadata = metadata("direct");
        Path replacementRoot = temporaryDirectory.resolve("replacement").toAbsolutePath();
        LaunchAuxiliaryProcessPlan replacementPostExit = new LaunchAuxiliaryProcessPlan(
                List.of(LaunchPlanText.literal("post-helper")), replacementRoot, false,
                Map.of("POST_MODE", LaunchPlanText.literal("enabled")), Set.of("OLD_POST"));
        List<String> observedDirectories = new ArrayList<>();

        GameLaunchHookCoordinator coordinator = coordinator(List.of(
                subscriber("dev.test.structure", Set.of(), event -> {
                    LaunchProcessPlan current = decode(event, original.secrets().keySet());
                    LaunchProcessPlan replacement = current
                            .withWorkingDirectory(replacementRoot)
                            .withEnvironment(false,
                                    Map.of("PLUGIN_VALUE", LaunchPlanText.literal("enabled")),
                                    Set.of("INHERITED_VALUE"))
                            .withPreLaunch(null)
                            .withPostExit(replacementPostExit)
                            .withProcessBehavior("close", true, false);
                    return replace(event, replacement);
                }),
                subscriber("dev.test.command", Set.of("dev.test.structure"), event -> {
                    LaunchProcessPlan current = decode(event, original.secrets().keySet());
                    observedDirectories.add(current.workingDirectory().toString());
                    LaunchCommandPlan command = LaunchCommandPlan.structuredJava(
                            List.of(LaunchPlanText.literal("wrapper")),
                            LaunchPlanText.literal(replacementRoot.resolve("java").toString()),
                            List.of(LaunchPlanText.literal("-Xmx4G")),
                            List.of(LaunchPlanText.literal(replacementRoot.resolve("client.jar").toString())),
                            LaunchPlanText.literal("example.Main"),
                            List.of(LaunchPlanText.literal("--demo"))
                    );
                    return replace(event, current.withCommand(command));
                })
        ), false);

        GameLaunchHookCoordinator.LaunchSession session = coordinator.beforeLaunch(original, metadata);
        LaunchProcessPlan transformed = session.preparation().plan();

        assertEquals(List.of(replacementRoot.toString()), observedDirectories);
        assertEquals(replacementRoot, transformed.workingDirectory());
        assertFalse(transformed.inheritEnvironment());
        assertEquals(Set.of("INHERITED_VALUE"), transformed.environmentUnset());
        assertEquals(Set.of("PLUGIN_VALUE"), transformed.environmentSet().keySet());
        assertEquals(null, transformed.preLaunch());
        assertEquals(replacementPostExit, transformed.postExit());
        assertEquals("close", transformed.launcherVisibility());
        assertTrue(transformed.inheritIo());
        assertFalse(transformed.daemonMonitors());
        assertEquals(LaunchCommandPlan.Mode.STRUCTURED_JAVA, transformed.command().mode());
        assertEquals(List.of("wrapper", replacementRoot.resolve("java").toString(), "-Xmx4G", "-cp",
                        replacementRoot.resolve("client.jar").toString(), "example.Main", "--demo"),
                transformed.command().resolve(slot -> session.preparation().secrets().get(slot)));
        assertEquals(original.secrets(), session.preparation().secrets());
        assertEquals(original.plan(), preparation(LaunchExecutionMode.DIRECT).plan());
        assertFalse(session.hasAfterSubscribers());
    }

    /// Accepts a complete raw command replacement without retaining stale structured Java fields.
    @Test
    public void rawCommandReplacementBecomesAuthoritative() {
        LaunchPreparation original = preparation(LaunchExecutionMode.DIRECT);
        GameLaunchHookCoordinator coordinator = coordinator(List.of(
                subscriber("dev.test.raw", Set.of(), event -> {
                    LaunchProcessPlan current = decode(event, original.secrets().keySet());
                    return replace(event, current.withCommand(LaunchCommandPlan.raw(List.of(
                            LaunchPlanText.literal("runtime-host"),
                            LaunchPlanText.literal("--launch"),
                            secretText("--token=")
                    ))));
                })
        ), false);

        GameLaunchHookCoordinator.LaunchSession session = coordinator.beforeLaunch(original, metadata("direct"));

        assertEquals(LaunchCommandPlan.Mode.RAW, session.finalPlan().command().mode());
        assertEquals(List.of("runtime-host", "--launch", "--token=top-secret"),
                session.finalPlan().command().resolve(session.preparation().secrets()::get));
        assertTrue(session.finalPlan().command().jvmArguments().isEmpty());
        assertTrue(session.finalPlan().command().classpathEntries().isEmpty());
    }

    /// Gives account-authorized callbacks secret snapshots and commits protected updates before the next callback.
    @Test
    public void protectedSecretUpdatesArePermissionScopedAndSequential() {
        LaunchPreparation original = preparation(LaunchExecutionMode.DIRECT);
        AtomicBoolean deniedSubscriberObservedNewSlot = new AtomicBoolean();
        GameLaunchHookCoordinator coordinator = coordinator(List.of(
                accountSubscriber("dev.test.account", event -> {
                    assertEquals("top-secret", event.secrets().resolve("access-token"));
                    LaunchProcessPlan current = decode(event, original.secrets().keySet());
                    LaunchProcessPlan replacement = current.withCommand(LaunchCommandPlan.raw(List.of(
                            LaunchPlanText.literal("runtime-host"), secretText("rotated-token"))));
                    return PluginHookResult.replace(
                            GameLaunchHookCodec.encodeBefore(replacement, event.data().requireObject("metadata")),
                            Map.of("rotated-token", "rotated-secret"));
                }),
                subscriber("dev.test.denied", Set.of("dev.test.account"), event -> {
                    assertThrows(PluginPermissionException.class,
                            () -> event.secrets().resolve("rotated-token"));
                    LaunchProcessPlan current = decode(event, Set.of("access-token", "rotated-token"));
                    deniedSubscriberObservedNewSlot.set(current.command().secretSlots().contains("rotated-token"));
                    return PluginHookResult.unchanged();
                })
        ), false);

        GameLaunchHookCoordinator.LaunchSession session = coordinator.beforeLaunch(original, metadata("direct"));

        assertTrue(deniedSubscriberObservedNewSlot.get());
        assertEquals("rotated-secret", session.preparation().secrets().get("rotated-token"));
        assertFalse(original.secrets().containsKey("rotated-token"));
        assertEquals(List.of("runtime-host", "rotated-secret"),
                session.finalPlan().command().resolve(session.preparation().secrets()::get));
    }

    /// Rejects a slot rotation that copies its previously visible secret into ordinary replacement data.
    @Test
    public void rotatedSecretCannotEscapeThroughOrdinaryReplacementData() {
        LaunchPreparation original = preparation(LaunchExecutionMode.DIRECT);
        AtomicBoolean laterInvoked = new AtomicBoolean();
        GameLaunchHookCoordinator coordinator = coordinator(List.of(
                accountSubscriber("dev.test.rotating-leak", event -> {
                    String oldSecret = event.secrets().resolve("access-token");
                    LaunchProcessPlan current = decode(event, original.secrets().keySet());
                    Map<String, LaunchPlanText> environment = new LinkedHashMap<>(current.environmentSet());
                    environment.put("LEAKED_SECRET", LaunchPlanText.literal(oldSecret));
                    LaunchProcessPlan replacement = current.withEnvironment(
                            current.inheritEnvironment(), environment, current.environmentUnset());
                    return PluginHookResult.replace(
                            GameLaunchHookCodec.encodeBefore(
                                    replacement, event.data().requireObject("metadata")),
                            Map.of("access-token", "rotated-secret"));
                }),
                subscriber("dev.test.after-rotating-leak", Set.of("dev.test.rotating-leak"), event -> {
                    laterInvoked.set(true);
                    return PluginHookResult.unchanged();
                })
        ), false);

        PluginHookDispatchException failure = assertThrows(PluginHookDispatchException.class,
                () -> coordinator.beforeLaunch(original, metadata("direct")));

        assertEquals(PluginHookDispatchException.Category.INVALID_RESULT, failure.category());
        assertEquals("dev.test.rotating-leak", failure.pluginId());
        assertFalse(failure.getMessage().contains("top-secret"));
        assertFalse(failure.getMessage().contains("rotated-secret"));
        assertFalse(laterInvoked.get());
        assertEquals("top-secret", original.secrets().get("access-token"));
        assertFalse(original.plan().environmentSet().containsKey("LEAKED_SECRET"));
    }

    /// Rejects an account-visible secret used as a nested environment object key.
    @Test
    public void secretCannotEscapeThroughEnvironmentObjectKey() {
        LaunchPreparation original = preparation(LaunchExecutionMode.DIRECT)
                .withSecrets(Map.of("access-token", "TOPSECRET123"));
        AtomicBoolean laterInvoked = new AtomicBoolean();
        GameLaunchHookCoordinator coordinator = coordinator(List.of(
                accountSubscriber("dev.test.key-leak", event -> {
                    String visibleSecret = event.secrets().resolve("access-token");
                    LaunchProcessPlan current = decode(event, original.secrets().keySet());
                    Map<String, LaunchPlanText> environment = new LinkedHashMap<>(current.environmentSet());
                    environment.put(visibleSecret, LaunchPlanText.literal("leaked-through-key"));
                    return replace(event, current.withEnvironment(
                            current.inheritEnvironment(), environment, current.environmentUnset()));
                }),
                subscriber("dev.test.after-key-leak", Set.of("dev.test.key-leak"), event -> {
                    laterInvoked.set(true);
                    assertTrue(event.data().requireObject("plan")
                            .requireObject("environmentSet").values().containsKey("TOPSECRET123"));
                    return PluginHookResult.unchanged();
                })
        ), false);

        PluginHookDispatchException failure = assertThrows(PluginHookDispatchException.class,
                () -> coordinator.beforeLaunch(original, metadata("direct")));

        assertEquals(PluginHookDispatchException.Category.INVALID_RESULT, failure.category());
        assertEquals("dev.test.key-leak", failure.pluginId());
        assertFalse(failure.getMessage().contains("TOPSECRET123"));
        assertFalse(Objects.toString(failure.getCause(), "").contains("TOPSECRET123"));
        assertFalse(laterInvoked.get());
        assertFalse(original.plan().environmentSet().containsKey("TOPSECRET123"));
    }

    /// Redacts an account-authorized endpoint throwable whose message contains a resolved secret.
    @Test
    public void accountEndpointThrowableDoesNotExposeResolvedSecret() {
        GameLaunchHookCoordinator coordinator = coordinator(List.of(
                accountSubscriber("dev.test.throwing-account", event -> {
                    throw new IllegalStateException(event.secrets().resolve("access-token"));
                })
        ), false);

        PluginHookDispatchException failure = assertThrows(PluginHookDispatchException.class,
                () -> coordinator.beforeLaunch(
                        preparation(LaunchExecutionMode.DIRECT), metadata("direct")));

        assertEquals(PluginHookDispatchException.Category.EXCEPTION, failure.category());
        assertEquals("dev.test.throwing-account", failure.pluginId());
        assertNull(failure.getCause());
        assertFalse(failure.toString().contains("top-secret"));
        assertFalse(StringUtils.getStackTrace(failure).contains("top-secret"));
    }

    /// Coordinates script plans without allocating direct-execution after state.
    @Test
    public void scriptModeTransformsAndNeverMarksAfterSubscribers() {
        LaunchPreparation original = preparation(LaunchExecutionMode.SCRIPT);
        GameLaunchHookCoordinator coordinator = coordinator(List.of(
                subscriber("dev.test.script", Set.of(), event -> {
                    assertEquals("script", event.data().requireObject("metadata").requireString("executionMode"));
                    LaunchProcessPlan current = decode(event, original.secrets().keySet());
                    return replace(event, current.withWorkingDirectory(
                            temporaryDirectory.resolve("script-root").toAbsolutePath()));
                })
        ), true);

        GameLaunchHookCoordinator.LaunchSession session = coordinator.beforeLaunch(original, metadata("script"));

        assertEquals(LaunchExecutionMode.SCRIPT, session.finalPlan().executionMode());
        assertEquals(temporaryDirectory.resolve("script-root").toAbsolutePath(),
                session.finalPlan().workingDirectory());
        assertFalse(session.hasAfterSubscribers());
    }

    /// Converts deliberate cancellation to the stable category and never invokes a later subscriber.
    @Test
    public void cancellationReturnsNoExecutablePreparation() {
        AtomicBoolean laterInvoked = new AtomicBoolean();
        GameLaunchHookCoordinator coordinator = coordinator(List.of(
                subscriber("dev.test.policy", Set.of(), event ->
                        PluginHookResult.cancel("policy-denied", "Launch denied by policy")),
                subscriber("dev.test.later", Set.of(), event -> {
                    laterInvoked.set(true);
                    return PluginHookResult.unchanged();
                })
        ), false);

        PluginHookDispatchException failure = assertThrows(PluginHookDispatchException.class,
                () -> coordinator.beforeLaunch(preparation(LaunchExecutionMode.DIRECT), metadata("direct")));

        assertEquals(PluginHookDispatchException.Category.CANCELLED, failure.category());
        assertEquals("dev.test.policy", failure.pluginId());
        assertEquals("policy-denied", failure.cancellationReasonCode());
        assertEquals("Launch denied by policy", failure.cancellationMessage());
        assertFalse(failure.getMessage().contains("Launch denied by policy"));
        assertFalse(laterInvoked.get());
    }

    /// Rejects a cancellation message containing a secret visible to an account-authorized callback.
    @Test
    public void cancellationMessageCannotExposeResolvedSecret() {
        AtomicBoolean laterInvoked = new AtomicBoolean();
        GameLaunchHookCoordinator coordinator = coordinator(List.of(
                accountSubscriber("dev.test.cancelling-account", event -> PluginHookResult.cancel(
                        "policy-secret",
                        "Launch denied for " + event.secrets().resolve("access-token")
                )),
                subscriber("dev.test.after-secret-cancel", Set.of("dev.test.cancelling-account"), event -> {
                    laterInvoked.set(true);
                    return PluginHookResult.unchanged();
                })
        ), false);

        PluginHookDispatchException failure = assertThrows(PluginHookDispatchException.class,
                () -> coordinator.beforeLaunch(
                        preparation(LaunchExecutionMode.DIRECT), metadata("direct")));

        assertEquals(PluginHookDispatchException.Category.INVALID_RESULT, failure.category());
        assertEquals("dev.test.cancelling-account", failure.pluginId());
        assertNull(failure.cancellationReasonCode());
        assertNull(failure.cancellationMessage());
        assertFalse(failure.toString().contains("top-secret"));
        assertFalse(StringUtils.getStackTrace(failure).contains("top-secret"));
        assertFalse(laterInvoked.get());
    }

    /// Rejects immutable metadata edits and does not mutate the caller's preparation or invoke later callbacks.
    @Test
    public void invalidReplacementIsTransactional() {
        LaunchPreparation original = preparation(LaunchExecutionMode.DIRECT);
        AtomicBoolean laterInvoked = new AtomicBoolean();
        GameLaunchHookCoordinator coordinator = coordinator(List.of(
                accountSubscriber("dev.test.invalid", event -> {
                    PluginDataObject rewrittenMetadata = event.data().requireObject("metadata")
                            .with("instanceId", PluginDataValue.string("other-instance"));
                    PluginDataObject replacement = event.data()
                            .with("metadata", PluginDataValue.object(rewrittenMetadata));
                    return PluginHookResult.replace(replacement,
                            Map.of("access-token", "must-not-commit"));
                }),
                subscriber("dev.test.later", Set.of(), event -> {
                    laterInvoked.set(true);
                    return PluginHookResult.unchanged();
                })
        ), false);

        PluginHookDispatchException failure = assertThrows(PluginHookDispatchException.class,
                () -> coordinator.beforeLaunch(original, metadata("direct")));

        assertEquals(PluginHookDispatchException.Category.INVALID_RESULT, failure.category());
        assertEquals("dev.test.invalid", failure.pluginId());
        assertFalse(laterInvoked.get());
        assertEquals("top-secret", original.secrets().get("access-token"));
        assertNotEquals("must-not-commit", original.secrets().get("access-token"));
    }

    /// Encodes one redacted exit event and continues after an invalid after result.
    @Test
    public void afterDispatchEncodesExitAndContinuesAfterInvalidResult() {
        List<String> invoked = new ArrayList<>();
        List<PluginHookEvent> observed = new ArrayList<>();
        PluginHookSubscriber invalid = subscriber("dev.test.after-invalid", Set.of(), event -> {
            invoked.add("invalid");
            return PluginHookResult.replace(event.data().with(
                    "exitCode", PluginDataValue.number(java.math.BigDecimal.valueOf(999))));
        });
        PluginHookSubscriber later = subscriber("dev.test.after-later", Set.of(), event -> {
            invoked.add("later");
            observed.add(event);
            return PluginHookResult.unchanged();
        });
        GameLaunchHookCoordinator coordinator = coordinator(List.of(), List.of(invalid, later), true);
        GameLaunchHookCoordinator.LaunchSession session = coordinator.beforeLaunch(
                preparation(LaunchExecutionMode.DIRECT), metadata("direct"));
        Instant endedAt = STARTED_AT.plusMillis(2500);

        session.afterLaunch(new GameLaunchHookProcessListener.ExitObservation(
                4242L, 137, "externally-killed", STARTED_AT, endedAt, 2500L));

        assertEquals(List.of("invalid", "later"), invoked);
        assertEquals(1, observed.size());
        PluginHookEvent event = observed.get(0);
        assertEquals(session.dispatchId(), event.dispatchId());
        assertEquals(PluginHookPoint.AFTER_GAME_LAUNCH, event.point());
        assertEquals(STARTED_AT, event.occurredAt());
        assertEquals(new java.math.BigDecimal("4242"), event.data().requireNumber("pid"));
        assertEquals(new java.math.BigDecimal("137"), event.data().requireNumber("exitCode"));
        assertEquals("externally-killed", event.data().requireString("terminationKind"));
        assertEquals(STARTED_AT.toString(), event.data().requireString("startedAt"));
        assertEquals(endedAt.toString(), event.data().requireString("endedAt"));
        assertEquals(new java.math.BigDecimal("2500"),
                event.data().requireNumber("elapsedMilliseconds"));
        assertFalse(event.data().toString().contains("top-secret"));
    }

    /// Starts after timing at successful process creation instead of before-Hook session creation.
    @Test
    public void afterDispatchUsesProcessStartTimeAndElapsedLifetime() {
        Instant processStartedAt = STARTED_AT.plusSeconds(5);
        Instant endedAt = processStartedAt.plusMillis(2500);
        MutableClock clock = new MutableClock(STARTED_AT);
        List<PluginHookEvent> observed = new ArrayList<>();
        PluginHookSubscriber after = subscriber("dev.test.after-timing", Set.of(), event -> {
            observed.add(event);
            return PluginHookResult.unchanged();
        });
        GameLaunchHookCoordinator coordinator = coordinator(
                List.of(),
                List.of(after),
                true,
                owner -> () -> {
                },
                clock
        );

        GameLaunchHookCoordinator.LaunchSession session = coordinator.beforeLaunch(
                preparation(LaunchExecutionMode.DIRECT), metadata("direct"));
        ProcessListener listener = Objects.requireNonNull(session.processListener(null), "processListener");
        clock.setInstant(processStartedAt);
        listener.setProcess(managedProcess(4242L));
        clock.setInstant(endedAt);
        listener.onExit(0, ProcessListener.ExitType.NORMAL);

        assertEquals(1, observed.size());
        PluginDataObject data = observed.get(0).data();
        assertEquals(processStartedAt.toString(), data.requireString("startedAt"));
        assertEquals(endedAt.toString(), data.requireString("endedAt"));
        assertEquals(new java.math.BigDecimal("2500"), data.requireNumber("elapsedMilliseconds"));
    }

    /// Creates a coordinator with deterministic scheduling and ordered before subscribers.
    ///
    /// @param subscribers before subscriber snapshot
    /// @param afterEligible whether an after subscriber is currently eligible
    /// @return deterministic coordinator
    private GameLaunchHookCoordinator coordinator(List<PluginHookSubscriber> subscribers, boolean afterEligible) {
        return coordinator(subscribers, List.of(), afterEligible);
    }

    /// Creates a coordinator with deterministic before and after subscriber snapshots.
    ///
    /// @param beforeSubscribers before subscriber snapshot
    /// @param afterSubscribers after subscriber snapshot
    /// @param afterEligible whether an after subscriber is currently eligible
    /// @return deterministic coordinator
    private GameLaunchHookCoordinator coordinator(
            List<PluginHookSubscriber> beforeSubscribers,
            List<PluginHookSubscriber> afterSubscribers,
            boolean afterEligible
    ) {
        return coordinator(beforeSubscribers, afterSubscribers, afterEligible, owner -> () -> {
        });
    }

    /// Creates a coordinator with deterministic subscribers and an injectable shutdown lease factory.
    ///
    /// @param beforeSubscribers before subscriber snapshot
    /// @param afterSubscribers after subscriber snapshot
    /// @param afterEligible whether an after subscriber is currently eligible
    /// @param shutdownLeaseFactory factory for direct-session application shutdown leases
    /// @return deterministic coordinator
    private GameLaunchHookCoordinator coordinator(
            List<PluginHookSubscriber> beforeSubscribers,
            List<PluginHookSubscriber> afterSubscribers,
            boolean afterEligible,
            Function<String, AutoCloseable> shutdownLeaseFactory
    ) {
        return coordinator(
                beforeSubscribers,
                afterSubscribers,
                afterEligible,
                shutdownLeaseFactory,
                Clock.fixed(STARTED_AT, ZoneOffset.UTC)
        );
    }

    /// Creates a coordinator with deterministic subscribers, shutdown leases, and an injectable clock.
    ///
    /// @param beforeSubscribers before subscriber snapshot
    /// @param afterSubscribers after subscriber snapshot
    /// @param afterEligible whether an after subscriber is currently eligible
    /// @param shutdownLeaseFactory factory for direct-session application shutdown leases
    /// @param clock event and process timing clock
    /// @return deterministic coordinator
    private GameLaunchHookCoordinator coordinator(
            List<PluginHookSubscriber> beforeSubscribers,
            List<PluginHookSubscriber> afterSubscribers,
            boolean afterEligible,
            Function<String, AutoCloseable> shutdownLeaseFactory,
            Clock clock
    ) {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executors.add(executor);
        PluginHookDispatcher dispatcher = new PluginHookDispatcher(
                executor,
                Duration.ofSeconds(1),
                clock,
                point -> point == PluginHookPoint.BEFORE_GAME_LAUNCH
                        ? beforeSubscribers
                        : afterSubscribers
        );
        return new GameLaunchHookCoordinator(dispatcher, () -> afterEligible, shutdownLeaseFactory);
    }

    /// Wraps one deterministic fake process.
    ///
    /// @param pid fake process ID
    /// @return managed fake process
    private static ManagedProcess managedProcess(long pid) {
        return new ManagedProcess(new FakeProcess(pid), List.of("java"));
    }

    /// Creates one leased test subscriber with the launch-Hook permission.
    ///
    /// @param pluginId plugin ID
    /// @param dependencies dependency IDs
    /// @param endpoint endpoint implementation
    /// @return test subscriber
    private static PluginHookSubscriber subscriber(
            String pluginId,
            Set<String> dependencies,
            PluginHookEndpoint endpoint
    ) {
        return subscriber(pluginId, dependencies, false, endpoint);
    }

    /// Creates one leased account-authorized test subscriber.
    ///
    /// @param pluginId plugin ID
    /// @param endpoint endpoint implementation
    /// @return test subscriber
    private static PluginHookSubscriber accountSubscriber(
            String pluginId,
            PluginHookEndpoint endpoint
    ) {
        return subscriber(pluginId, Set.of(), true, endpoint);
    }

    /// Creates one leased test subscriber from complete dependency and permission state.
    ///
    /// @param pluginId plugin ID
    /// @param dependencies dependency IDs
    /// @param accountGranted whether the account permission is effective
    /// @param endpoint endpoint implementation
    /// @return test subscriber
    private static PluginHookSubscriber subscriber(
            String pluginId,
            Set<String> dependencies,
            boolean accountGranted,
            PluginHookEndpoint endpoint
    ) {
        Set<PluginPermission> permissions = accountGranted
                ? Set.of(PluginPermission.LAUNCHER_HOOK, PluginPermission.ACCOUNT)
                : Set.of(PluginPermission.LAUNCHER_HOOK);
        return new PluginHookSubscriber(pluginId, dependencies, permissions, endpoint, () -> {
        });
    }

    /// Decodes the current event plan against the supplied secret slots.
    ///
    /// @param event current event
    /// @param secretSlots available secret slots
    /// @return decoded plan
    private static LaunchProcessPlan decode(PluginHookEvent event, Set<String> secretSlots) {
        return GameLaunchHookCodec.decodeBefore(
                event.data(), event.data().requireObject("metadata"), secretSlots);
    }

    /// Encodes one plan as a complete replacement retaining event metadata.
    ///
    /// @param event current event
    /// @param plan replacement plan
    /// @return replacement result
    private static PluginHookResult replace(PluginHookEvent event, LaunchProcessPlan plan) {
        return PluginHookResult.replace(GameLaunchHookCodec.encodeBefore(
                plan, event.data().requireObject("metadata")));
    }

    /// Creates a complete launch preparation for one execution mode.
    ///
    /// @param mode direct or script mode
    /// @return immutable preparation
    private LaunchPreparation preparation(LaunchExecutionMode mode) {
        Path root = temporaryDirectory.resolve("original").toAbsolutePath();
        LaunchAuxiliaryProcessPlan preLaunch = new LaunchAuxiliaryProcessPlan(
                List.of(LaunchPlanText.literal("pre-helper")), root, true,
                Map.of("PRE_MODE", LaunchPlanText.literal("enabled")), Set.of());
        LaunchProcessPlan plan = new LaunchProcessPlan(
                LaunchProcessPlan.CURRENT_PLAN_VERSION,
                mode,
                LaunchCommandPlan.structuredJava(
                        List.of(),
                        LaunchPlanText.literal(root.resolve("java").toString()),
                        List.of(LaunchPlanText.literal("-Xmx2G"), secretText("-Dtoken=")),
                        List.of(LaunchPlanText.literal(root.resolve("client.jar").toString())),
                        LaunchPlanText.literal("net.minecraft.client.main.Main"),
                        List.of(LaunchPlanText.literal("--username"), LaunchPlanText.literal("Alex"))
                ),
                root,
                true,
                Map.of("INSTANCE_ID", LaunchPlanText.literal("example")),
                Set.of("OLD_VALUE"),
                preLaunch,
                null,
                "hide-and-reopen",
                false,
                true
        );
        return new LaunchPreparation(
                plan,
                Map.of("access-token", "top-secret"),
                temporaryDirectory.resolve("native-link"),
                temporaryDirectory.resolve("natives"),
                temporaryDirectory.resolve("java-natives"),
                StandardCharsets.UTF_8
        );
    }

    /// Creates immutable launch metadata for one execution mode.
    ///
    /// @param executionMode stable execution mode identifier
    /// @return metadata object
    private static PluginDataObject metadata(String executionMode) {
        return PluginDataObject.of(Map.of(
                "instanceId", PluginDataValue.string("example-instance"),
                "gameVersion", PluginDataValue.string("1.21.8"),
                "launcherVersion", PluginDataValue.string("3.6-next"),
                "hostOs", PluginDataValue.string("windows"),
                "hostArchitecture", PluginDataValue.string("x86_64"),
                "executionMode", PluginDataValue.string(executionMode)
        ));
    }

    /// Creates one template ending in a protected secret slot.
    ///
    /// @param slot secret slot
    /// @return secret-aware text
    private static LaunchPlanText secretText(String slot) {
        String prefix = slot.endsWith("=") ? slot : "";
        String secretSlot = prefix.isEmpty() ? slot : "access-token";
        return LaunchPlanText.template(List.of(
                new LaunchPlanText.LiteralSegment(prefix),
                new LaunchPlanText.SecretSegment(secretSlot)
        ));
    }

    /// Supplies deterministic mutable instants to coordinator and listener code.
    @NotNullByDefault
    private static final class MutableClock extends Clock {
        /// Current clock instant.
        private Instant instant;

        /// Creates a UTC clock at one instant.
        ///
        /// @param instant initial instant
        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        /// Moves the deterministic clock to a new instant.
        ///
        /// @param instant new current instant
        private void setInstant(Instant instant) {
            this.instant = instant;
        }

        /// Returns UTC as the fixed zone.
        ///
        /// @return UTC zone
        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        /// Returns this clock for UTC and rejects other zones.
        ///
        /// @param zone requested zone
        /// @return this clock
        @Override
        public Clock withZone(ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) {
                throw new IllegalArgumentException("Only UTC is supported");
            }
            return this;
        }

        /// Returns the current deterministic instant.
        ///
        /// @return current instant
        @Override
        public Instant instant() {
            return instant;
        }
    }

    /// Implements the minimum deterministic process surface required by `ManagedProcess`.
    @NotNullByDefault
    private static final class FakeProcess extends Process {
        /// Stable fake process ID.
        private final long pid;

        /// Creates one exited fake process.
        ///
        /// @param pid fake process ID
        private FakeProcess(long pid) {
            this.pid = pid;
        }

        /// Returns a writable in-memory stdin stream.
        ///
        /// @return fake stdin
        @Override
        public OutputStream getOutputStream() {
            return new ByteArrayOutputStream();
        }

        /// Returns an empty stdout stream.
        ///
        /// @return fake stdout
        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        /// Returns an empty stderr stream.
        ///
        /// @return fake stderr
        @Override
        public InputStream getErrorStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        /// Returns a successful exit code immediately.
        ///
        /// @return zero
        @Override
        public int waitFor() {
            return 0;
        }

        /// Returns a successful exit code.
        ///
        /// @return zero
        @Override
        public int exitValue() {
            return 0;
        }

        /// Performs no work because the fake process is already exited.
        @Override
        public void destroy() {
        }

        /// Returns the stable fake process ID.
        ///
        /// @return fake process ID
        @Override
        public long pid() {
            return pid;
        }
    }
}
