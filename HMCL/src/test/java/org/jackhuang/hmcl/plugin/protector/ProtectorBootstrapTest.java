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
package org.jackhuang.hmcl.plugin.protector;

import org.jackhuang.hmcl.EntryPoint;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies real-process startup supervision, terminal classification, deadlines, and termination escalation.
@NotNullByDefault
public final class ProtectorBootstrapTest {
    /// Leaves no recovery record after authenticated UI readiness.
    ///
    /// @param temporaryDirectory isolated launcher-local home
    /// @throws Exception if subprocess supervision fails
    @Test
    public void readyLeavesNoRecoveryRecord(@TempDir Path temporaryDirectory) throws Exception {
        runFixture(temporaryDirectory, "ready");

        assertTrue(new PluginRecoveryStore(temporaryDirectory).load().isEmpty());
    }

    /// Leaves no recovery record after explicit startup cancellation.
    ///
    /// @param temporaryDirectory isolated launcher-local home
    /// @throws Exception if subprocess supervision fails
    @Test
    public void cancelLeavesNoRecoveryRecord(@TempDir Path temporaryDirectory) throws Exception {
        runFixture(temporaryDirectory, "cancel");

        assertTrue(new PluginRecoveryStore(temporaryDirectory).load().isEmpty());
    }

    /// Leaves no recovery record when the launcher exits with an explicit zero-status cancellation.
    ///
    /// @param temporaryDirectory isolated launcher-local home
    /// @throws Exception if subprocess supervision fails
    @Test
    public void zeroLauncherExitLeavesNoRecoveryRecord(@TempDir Path temporaryDirectory) throws Exception {
        runFixture(temporaryDirectory, "zero-exit");

        assertTrue(new PluginRecoveryStore(temporaryDirectory).load().isEmpty());
    }

    /// Leaves no recovery record when the protected child shuts down normally before UI readiness.
    ///
    /// @param temporaryDirectory isolated launcher-local home
    /// @throws Exception if subprocess supervision fails
    @Test
    @Timeout(10)
    public void normalPreReadyShutdownLeavesNoRecoveryRecord(@TempDir Path temporaryDirectory) throws Exception {
        runFixture(temporaryDirectory, "normal");

        assertTrue(new PluginRecoveryStore(temporaryDirectory).load().isEmpty());
    }

    /// Persists recovery when the protected child requests a nonzero launcher exit before UI readiness.
    ///
    /// @param temporaryDirectory isolated launcher-local home
    /// @throws Exception if subprocess supervision or recovery loading fails
    @Test
    public void nonzeroLauncherExitPersistsCrashRecovery(@TempDir Path temporaryDirectory) throws Exception {
        runFixture(temporaryDirectory, "nonzero-exit");

        PluginRecoveryRecord record = new PluginRecoveryStore(temporaryDirectory).load().orElseThrow();
        assertEquals(PluginRecoveryRecord.FailureReason.CHILD_CRASH, record.failureReason());
        assertEquals(ProtectorStage.JVM_STARTED, record.lastStage());
    }

    /// Persists recovery when an uncaught child failure terminates the protected JVM before UI readiness.
    ///
    /// @param temporaryDirectory isolated launcher-local home
    /// @throws Exception if subprocess supervision or recovery loading fails
    @Test
    public void uncaughtChildFailurePersistsCrashRecovery(@TempDir Path temporaryDirectory) throws Exception {
        runFixture(temporaryDirectory, "uncaught");

        PluginRecoveryRecord record = new PluginRecoveryStore(temporaryDirectory).load().orElseThrow();
        assertEquals(PluginRecoveryRecord.FailureReason.CHILD_CRASH, record.failureReason());
        assertEquals(ProtectorStage.JVM_STARTED, record.lastStage());
    }

    /// Treats a closing Windows pipe as terminal instead of retrying its writes indefinitely.
    @Test
    public void closingWindowsPipeWriteIsNotRetryable() {
        assertFalse(ProtectorBootstrap.isRetryablePipeWriteError(232));
        assertTrue(ProtectorBootstrap.isRetryablePipeWriteError(231));
    }

    /// Uses a first-instance pipe and an explicit owner-only security descriptor on Windows.
    ///
    /// @throws Exception if the native security descriptor cannot be created or inspected
    @Test
    @EnabledOnOs(OS.WINDOWS)
    public void windowsPipeUsesFirstInstanceAndExplicitOwnerSecurity() throws Exception {
        var openMode = ProtectorBootstrap.class.getDeclaredField("WINDOWS_PIPE_OPEN_MODE");
        openMode.setAccessible(true);
        assertTrue((((int) openMode.get(null)) & 0x00080000) != 0);

        Class<?> securityType = Class.forName(
                "org.jackhuang.hmcl.plugin.protector.ProtectorBootstrap$WindowsPipeSecurity"
        );
        Method create = securityType.getDeclaredMethod("create");
        create.setAccessible(true);
        Method attributesPointer = securityType.getDeclaredMethod("attributesPointer");
        attributesPointer.setAccessible(true);
        Method hasSecurityDescriptor = securityType.getDeclaredMethod("hasSecurityDescriptor");
        hasSecurityDescriptor.setAccessible(true);
        try (AutoCloseable security = (AutoCloseable) create.invoke(null)) {
            assertTrue((long) attributesPointer.invoke(security) != 0L);
            assertTrue((boolean) hasSecurityDescriptor.invoke(security));
        }
    }

    /// Persists recovery and terminates a child that remains alive beyond the connection deadline.
    ///
    /// @param temporaryDirectory isolated launcher-local home and process-marker directory
    /// @throws Exception if subprocess supervision or marker verification fails
    @Test
    @Timeout(10)
    public void connectTimeoutPersistsRecoveryAndTerminatesChild(@TempDir Path temporaryDirectory) throws Exception {
        Path marker = temporaryDirectory.resolve("child.pid");
        ProtectorBootstrap.SupervisionPolicy policy = new ProtectorBootstrap.SupervisionPolicy(
                Duration.ofMillis(500L),
                Duration.ofSeconds(5),
                Duration.ofSeconds(5),
                Duration.ofSeconds(5),
                Duration.ofSeconds(5),
                Duration.ofSeconds(5),
                Duration.ofSeconds(1)
        );

        assertThrows(IOException.class, () -> runFixture(
                temporaryDirectory,
                "never-connect:" + marker,
                policy
        ));
        long processId = Long.parseLong(Files.readString(marker, StandardCharsets.UTF_8));
        Optional<ProcessHandle> child = ProcessHandle.of(processId);
        try {
            assertFalse(new PluginRecoveryStore(temporaryDirectory).load().isEmpty());
            assertFalse(child.map(ProcessHandle::isAlive).orElse(false));
        } finally {
            child.filter(ProcessHandle::isAlive).ifPresent(ProcessHandle::destroyForcibly);
        }
    }

    /// Persists a crash record when the child exits before opening its authenticated control channel.
    ///
    /// @param temporaryDirectory isolated launcher-local home
    /// @throws Exception if subprocess supervision or recovery loading fails
    @Test
    public void preConnectCrashPersistsRecoveryAtJvmStarted(@TempDir Path temporaryDirectory) throws Exception {
        runFixture(temporaryDirectory, "preconnect-crash");

        PluginRecoveryRecord record = new PluginRecoveryStore(temporaryDirectory).load().orElseThrow();
        assertEquals(PluginRecoveryRecord.FailureReason.CHILD_CRASH, record.failureReason());
        assertEquals(ProtectorStage.JVM_STARTED, record.lastStage());
    }

    /// Waits through process-state publication before classifying a closed child transport.
    ///
    /// @throws Exception if reflective classification fails
    @Test
    public void transportClosureWaitsForPendingNonzeroExit() throws Exception {
        DelayedExitProcess child = new DelayedExitProcess(23);
        Method classifier = ProtectorBootstrap.class.getDeclaredMethod("failureForExit", Process.class);
        classifier.setAccessible(true);

        Object reason = classifier.invoke(null, child);

        assertEquals(PluginRecoveryRecord.FailureReason.CHILD_CRASH, reason);
        assertTrue(child.timedWaitRequested());
    }

    /// Suppresses normal startup completion after a synchronous JavaFX start failure was recorded.
    ///
    /// @throws Exception if reflective startup-outcome invocation fails
    @Test
    public void synchronousLauncherStartFailureSuppressesNormalShutdown() throws Exception {
        Class<?> outcomeType = Class.forName("org.jackhuang.hmcl.Launcher$StartupOutcome");
        var constructor = outcomeType.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object outcome = constructor.newInstance();
        Method recordFailure = outcomeType.getDeclaredMethod("recordFailure");
        recordFailure.setAccessible(true);
        Method reportNormal = outcomeType.getDeclaredMethod("reportNormal", Runnable.class);
        reportNormal.setAccessible(true);
        AtomicInteger reports = new AtomicInteger();

        recordFailure.invoke(outcome);
        reportNormal.invoke(outcome, (Runnable) reports::incrementAndGet);

        assertEquals(0, reports.get());
    }

    /// Suppresses normal completion when the deferred first-paint callback reports a startup failure.
    ///
    /// @throws Exception if reflective startup-outcome invocation fails
    @Test
    public void asynchronousLauncherCallbackFailureSuppressesNormalShutdown() throws Exception {
        Class<?> outcomeType = Class.forName("org.jackhuang.hmcl.Launcher$StartupOutcome");
        var constructor = outcomeType.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object outcome = constructor.newInstance();
        Method guardStartupCallback = outcomeType.getDeclaredMethod(
                "guardStartupCallback",
                Runnable.class,
                Thread.UncaughtExceptionHandler.class
        );
        guardStartupCallback.setAccessible(true);
        Method reportNormal = outcomeType.getDeclaredMethod("reportNormal", Runnable.class);
        reportNormal.setAccessible(true);
        AtomicInteger crashReports = new AtomicInteger();
        AtomicInteger normalReports = new AtomicInteger();
        IllegalStateException failure = new IllegalStateException("pre-UI startup failure");

        Runnable guardedCallback = (Runnable) guardStartupCallback.invoke(
                outcome,
                (Runnable) () -> {
                    throw failure;
                },
                (Thread.UncaughtExceptionHandler) (thread, throwable) -> {
                    assertSame(Thread.currentThread(), thread);
                    assertSame(failure, throwable);
                    crashReports.incrementAndGet();
                }
        );
        guardedCallback.run();
        reportNormal.invoke(outcome, (Runnable) normalReports::incrementAndGet);

        assertEquals(1, crashReports.get());
        assertEquals(0, normalReports.get());
    }

    /// Persists protocol failure and reaps a live child that reports UI readiness before Core readiness.
    ///
    /// @param temporaryDirectory isolated launcher-local home and process-marker directory
    /// @throws Exception if subprocess supervision or recovery loading fails
    @Test
    @Timeout(10)
    public void invalidAuthenticatedStateTerminatesAndReapsChild(@TempDir Path temporaryDirectory) throws Exception {
        Path marker = temporaryDirectory.resolve("invalid-state-child.pid");

        assertThrows(IOException.class, () -> runFixture(
                temporaryDirectory,
                "invalid-ready:" + marker
        ));

        PluginRecoveryRecord record = new PluginRecoveryStore(temporaryDirectory).load().orElseThrow();
        assertEquals(PluginRecoveryRecord.FailureReason.UNEXPECTED_PROCESS_EXIT, record.failureReason());
        long processId = Long.parseLong(Files.readString(marker, StandardCharsets.UTF_8));
        assertFalse(ProcessHandle.of(processId).map(ProcessHandle::isAlive).orElse(false));
    }

    /// Skips Protector and launcher startup when Mixin relaunches the process.
    ///
    /// @throws Exception if reflective orchestration invocation fails
    @Test
    public void entryPointSkipsProtectorAfterMixinRelaunch() throws Exception {
        List<String> events = new ArrayList<>();

        invokeEntryPointOrchestration(
                new String[]{"original"},
                ignored -> {
                    events.add("mixin");
                    return true;
                },
                (ignoredProxy, ignoredMethod, ignoredArguments) -> {
                    events.add("protector");
                    return null;
                },
                ignored -> events.add("launcher")
        );

        assertEquals(List.of("mixin"), events);
    }

    /// Enters Protector immediately after Mixin and stops before launcher runtime in the parent role.
    ///
    /// @throws Exception if reflective orchestration invocation fails
    @Test
    public void entryPointParentStopsBeforeLauncherRuntime() throws Exception {
        List<String> events = new ArrayList<>();

        invokeEntryPointOrchestration(
                new String[]{"original"},
                ignored -> {
                    events.add("mixin");
                    return false;
                },
                (ignoredProxy, ignoredMethod, ignoredArguments) -> {
                    events.add("protector");
                    return null;
                },
                ignored -> events.add("launcher")
        );

        assertEquals(List.of("mixin", "protector"), events);
    }

    /// Passes Protector-stripped child arguments to launcher runtime after ordered role selection.
    ///
    /// @throws Exception if reflective orchestration invocation fails
    @Test
    public void entryPointChildPassesProtectedArgumentsToLauncher() throws Exception {
        List<String> events = new ArrayList<>();
        String[] childArguments = {"child"};

        invokeEntryPointOrchestration(
                new String[]{"original"},
                ignored -> {
                    events.add("mixin");
                    return false;
                },
                (ignoredProxy, ignoredMethod, ignoredArguments) -> {
                    events.add("protector");
                    return childArguments;
                },
                actualArguments -> {
                    events.add("launcher");
                    assertTrue(actualArguments == childArguments);
                }
        );

        assertEquals(List.of("mixin", "protector", "launcher"), events);
    }

    /// Persists a controlled crash record at every authenticated pre-ready stage.
    ///
    /// @param temporaryDirectory isolated launcher-local home
    /// @throws Exception if subprocess supervision or recovery loading fails
    @Test
    public void preReadyCrashPersistsRecoveryAtCurrentStage(@TempDir Path temporaryDirectory) throws Exception {
        for (ProtectorStage stage : List.of(
                ProtectorStage.JVM_STARTED,
                ProtectorStage.CORE_READY,
                ProtectorStage.RUNTIME_PROVIDERS_LOADING,
                ProtectorStage.ORDINARY_PLUGINS_LOADING
        )) {
            Path home = temporaryDirectory.resolve(stage.name());
            runFixture(home, "crash:" + stage.name());

            PluginRecoveryRecord record = new PluginRecoveryStore(home).load().orElseThrow();
            assertEquals(PluginRecoveryRecord.FailureReason.CHILD_CRASH, record.failureReason());
            assertEquals(stage, record.lastStage());
        }
    }

    /// Persists heartbeat-loss recovery for a child hanging at every pre-ready stage.
    ///
    /// @param temporaryDirectory isolated launcher-local home
    /// @throws Exception if subprocess supervision or recovery loading fails
    @Test
    public void preReadyHangPersistsRecoveryAtCurrentStage(@TempDir Path temporaryDirectory) throws Exception {
        for (ProtectorStage stage : List.of(
                ProtectorStage.JVM_STARTED,
                ProtectorStage.CORE_READY,
                ProtectorStage.RUNTIME_PROVIDERS_LOADING,
                ProtectorStage.ORDINARY_PLUGINS_LOADING
        )) {
            Path home = temporaryDirectory.resolve(stage.name());
            runFixture(home, "hang:" + stage.name());

            PluginRecoveryRecord record = new PluginRecoveryStore(home).load().orElseThrow();
            assertEquals(PluginRecoveryRecord.FailureReason.HEARTBEAT_LOST, record.failureReason());
            assertEquals(stage, record.lastStage());
        }
    }

    /// Does not let lease renewal conceal a lost heartbeat.
    ///
    /// @throws Exception if state validation fails unexpectedly
    @Test
    public void renewalRequiresContinuingHeartbeat() throws Exception {
        ProtectorBootstrap.SupervisionPolicy policy = productionPolicy();
        ProtectorBootstrap.StartupState state = new ProtectorBootstrap.StartupState(0L);
        state.accept(message(
                ProtectorMessage.Kind.STAGE,
                1L,
                ProtectorStage.RUNTIME_PROVIDERS_LOADING,
                "org.example.provider"
        ), 1L);
        long afterHeartbeatLoss = ProtectorProtocol.HEARTBEAT_LOSS_TIMEOUT.toNanos() + 2L;

        state.accept(message(
                ProtectorMessage.Kind.LEASE_RENEWAL,
                2L,
                ProtectorStage.RUNTIME_PROVIDERS_LOADING,
                "org.example.provider"
        ), afterHeartbeatLoss);

        assertEquals(PluginRecoveryRecord.FailureReason.HEARTBEAT_LOST, state.timeoutAt(afterHeartbeatLoss, policy));
    }

    /// Starts heartbeat-loss measurement when the child connects without extending startup deadlines.
    @Test
    public void heartbeatLossStartsWhenChildConnects() {
        ProtectorBootstrap.SupervisionPolicy policy = productionPolicy();
        ProtectorBootstrap.StartupState state = new ProtectorBootstrap.StartupState(0L);
        long connectionNanos = Duration.ofSeconds(25).toNanos();

        state.connected(connectionNanos);

        assertTrue(state.timeoutAt(connectionNanos, policy) == null);
        assertEquals(
                PluginRecoveryRecord.FailureReason.HEARTBEAT_LOST,
                state.timeoutAt(connectionNanos + ProtectorProtocol.HEARTBEAT_LOSS_TIMEOUT.toNanos(), policy)
        );
    }

    /// Rejects cancellation and normal-shutdown classifications that name a stale startup stage.
    ///
    /// @throws Exception if setup state validation fails unexpectedly
    @Test
    public void terminalShutdownMessagesMustMatchCurrentStage() throws Exception {
        ProtectorBootstrap.StartupState state = new ProtectorBootstrap.StartupState(0L);
        state.accept(message(ProtectorMessage.Kind.STAGE, 1L, ProtectorStage.CORE_READY, null), 1L);

        assertThrows(IOException.class, () -> state.accept(
                message(ProtectorMessage.Kind.CANCEL, 2L, ProtectorStage.JVM_STARTED, null),
                2L
        ));
        assertThrows(IOException.class, () -> state.accept(
                message(ProtectorMessage.Kind.NORMAL_SHUTDOWN, 3L, ProtectorStage.JVM_STARTED, null),
                3L
        ));
    }

    /// Rejects UI readiness before authenticated Core readiness.
    ///
    /// @throws Exception if state validation fails unexpectedly
    @Test
    public void readyRequiresCoreInitialization() throws Exception {
        ProtectorBootstrap.StartupState state = new ProtectorBootstrap.StartupState(0L);
        ProtectorMessage ready = new ProtectorMessage(
                ProtectorMessage.Kind.READY,
                1L,
                ProtectorStage.UI_READY,
                null,
                null
        );

        assertThrows(IOException.class, () -> state.accept(ready, 1L));
    }

    /// Produces strictly increasing parent control timestamps even when the monotonic clock does not advance.
    @Test
    public void parentControlTimestampsRemainStrictlyIncreasing() {
        ProtectorBootstrap.StartupState state = new ProtectorBootstrap.StartupState(0L);

        assertEquals(5L, state.nextControlTimestamp(5L));
        assertEquals(6L, state.nextControlTimestamp(5L));
    }

    /// Enforces the absolute ten-minute cap after a valid current-stage heartbeat and renewal at the boundary.
    ///
    /// @throws Exception if state validation fails unexpectedly
    @Test
    public void renewalCannotExtendInjectedHardStartupDeadline() throws Exception {
        ProtectorBootstrap.SupervisionPolicy policy = productionPolicy();
        ProtectorBootstrap.StartupState state = new ProtectorBootstrap.StartupState(0L);
        state.accept(message(
                ProtectorMessage.Kind.STAGE,
                1L,
                ProtectorStage.RUNTIME_PROVIDERS_LOADING,
                "org.example.provider"
        ), 1L);
        long justBeforeHardDeadline = ProtectorProtocol.HARD_STARTUP_TIMEOUT.toNanos() - 1L;
        state.accept(message(
                ProtectorMessage.Kind.HEARTBEAT,
                2L,
                ProtectorStage.RUNTIME_PROVIDERS_LOADING,
                "org.example.provider"
        ), justBeforeHardDeadline);
        state.accept(message(
                ProtectorMessage.Kind.LEASE_RENEWAL,
                3L,
                ProtectorStage.RUNTIME_PROVIDERS_LOADING,
                "org.example.provider"
        ), justBeforeHardDeadline);

        assertTrue(state.timeoutAt(justBeforeHardDeadline, policy) == null);
        assertEquals(
                PluginRecoveryRecord.FailureReason.HARD_STARTUP_DEADLINE_EXCEEDED,
                state.timeoutAt(ProtectorProtocol.HARD_STARTUP_TIMEOUT.toNanos(), policy)
        );
    }

    /// Enforces the hard startup cap while a real child continuously renews each renewable pre-ready stage.
    ///
    /// @param temporaryDirectory isolated launcher-local home
    /// @throws Exception if subprocess supervision or recovery loading fails
    @Test
    @Timeout(15)
    public void renewalSubprocessCannotExtendHardStartupDeadline(@TempDir Path temporaryDirectory) throws Exception {
        ProtectorBootstrap.SupervisionPolicy policy = new ProtectorBootstrap.SupervisionPolicy(
                Duration.ofSeconds(5),
                Duration.ofSeconds(5),
                Duration.ofSeconds(5),
                Duration.ofMillis(300L),
                Duration.ofMillis(300L),
                Duration.ofSeconds(2),
                Duration.ofSeconds(1)
        );
        for (ProtectorStage stage : List.of(
                ProtectorStage.CORE_READY,
                ProtectorStage.RUNTIME_PROVIDERS_LOADING,
                ProtectorStage.ORDINARY_PLUGINS_LOADING
        )) {
            Path home = temporaryDirectory.resolve(stage.name());
            runFixture(home, "renew:" + stage.name(), policy);

            PluginRecoveryRecord record = new PluginRecoveryStore(home).load().orElseThrow();
            assertEquals(PluginRecoveryRecord.FailureReason.HARD_STARTUP_DEADLINE_EXCEEDED, record.failureReason());
            assertEquals(stage, record.lastStage());
        }
    }

    /// Requests diagnostics then graceful termination and force-kills only after the configured grace expires.
    ///
    /// @param temporaryDirectory isolated launcher-local home and marker directory
    /// @throws Exception if subprocess supervision or marker verification fails
    @Test
    public void timeoutEscalatesAfterGraceBeforeForceTermination(@TempDir Path temporaryDirectory) throws Exception {
        Path marker = temporaryDirectory.resolve("requests.txt");
        Duration grace = Duration.ofMillis(700L);
        long startNanos = System.nanoTime();

        ProtectorBootstrap.supervise(
                temporaryDirectory,
                fixtureCommand(),
                new String[]{"stubborn", marker.toString()},
                new ProtectorBootstrap.SupervisionPolicy(
                        Duration.ofSeconds(5),
                        Duration.ofMillis(400L),
                        Duration.ofSeconds(2),
                        Duration.ofSeconds(2),
                        Duration.ofSeconds(2),
                        Duration.ofSeconds(5),
                        grace
                )
        );

        long elapsedNanos = System.nanoTime() - startNanos;
        assertTrue(elapsedNanos >= grace.toNanos());
        assertEquals("diagnostics-request\ntermination-request\n", Files.readString(marker, StandardCharsets.UTF_8));
        assertFalse(new PluginRecoveryStore(temporaryDirectory).load().isEmpty());
    }

    /// Preserves timeout recovery and reaps the child when a control request cannot be written.
    ///
    /// @param temporaryDirectory isolated launcher-local home and process-marker directory
    /// @throws Exception if process setup, supervision, or recovery loading fails
    @Test
    @Timeout(10)
    public void timeoutControlWriteFailureTerminatesAndReapsChild(@TempDir Path temporaryDirectory) throws Exception {
        Path marker = temporaryDirectory.resolve("timeout-child.pid");
        Process child = startNeverConnectingFixture(marker);
        OutputStream closedOutput = Files.newOutputStream(temporaryDirectory.resolve("closed-output"));
        closedOutput.close();
        ProtectorBootstrap.StartupState state = new ProtectorBootstrap.StartupState(System.nanoTime());
        ProtectorBootstrap.SupervisionPolicy policy = new ProtectorBootstrap.SupervisionPolicy(
                Duration.ofSeconds(5),
                Duration.ofSeconds(5),
                Duration.ofSeconds(5),
                Duration.ofSeconds(5),
                Duration.ofSeconds(5),
                Duration.ofSeconds(5),
                Duration.ofSeconds(1)
        );
        try {
            assertThrows(IOException.class, () -> ProtectorBootstrap.handleTimeout(
                    new PluginRecoveryStore(temporaryDirectory),
                    PluginRecoveryRecord.FailureReason.HEARTBEAT_LOST,
                    child,
                    closedOutput,
                    new ProtectorProtocol("A".repeat(43)),
                    state,
                    policy
            ));

            PluginRecoveryRecord record = new PluginRecoveryStore(temporaryDirectory).load().orElseThrow();
            assertEquals(PluginRecoveryRecord.FailureReason.HEARTBEAT_LOST, record.failureReason());
            assertFalse(child.isAlive());
            assertTrue(child.waitFor(1L, java.util.concurrent.TimeUnit.SECONDS));
        } finally {
            if (child.isAlive()) {
                child.destroyForcibly();
            }
        }
    }

    /// Continues to graceful termination when child diagnostic persistence fails.
    ///
    /// @param temporaryDirectory isolated launcher-local home and marker directory
    /// @throws Exception if subprocess supervision or marker verification fails
    @Test
    @Timeout(10)
    public void diagnosticFailureStillProcessesTerminationRequest(@TempDir Path temporaryDirectory) throws Exception {
        Path invalidHome = temporaryDirectory.resolve("not-a-directory");
        Files.writeString(invalidHome, "file", StandardCharsets.UTF_8);
        Path shutdownMarker = temporaryDirectory.resolve("shutdown.txt");
        Path recoveryHome = temporaryDirectory.resolve("recovery");

        ProtectorBootstrap.supervise(
                recoveryHome,
                fixtureCommand(invalidHome),
                new String[]{"shutdown-marker:" + shutdownMarker},
                new ProtectorBootstrap.SupervisionPolicy(
                        Duration.ofSeconds(5),
                        Duration.ofMillis(400L),
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(1)
                )
        );

        assertEquals("shutdown", Files.readString(shutdownMarker, StandardCharsets.UTF_8));
        PluginRecoveryRecord record = new PluginRecoveryStore(recoveryHome).load().orElseThrow();
        assertEquals(PluginRecoveryRecord.FailureReason.HEARTBEAT_LOST, record.failureReason());
    }

    /// Uses the protocol-defined ten-second graceful termination period in production supervision.
    @Test
    public void productionPolicyUsesProtocolTerminationGrace() {
        assertEquals(
                ProtectorProtocol.TERMINATION_GRACE_TIMEOUT,
                ProtectorBootstrap.SupervisionPolicy.production().terminationGrace()
        );
        assertEquals(Duration.ofSeconds(10), ProtectorProtocol.TERMINATION_GRACE_TIMEOUT);
    }

    /// Creates one state-bearing test message with no active ordinary plugin.
    ///
    /// @param kind control operation
    /// @param timestamp child monotonic timestamp
    /// @param stage startup stage
    /// @param providerId active Provider ID
    /// @return validated message
    private static ProtectorMessage message(
            ProtectorMessage.Kind kind,
            long timestamp,
            ProtectorStage stage,
            String providerId
    ) {
        return new ProtectorMessage(kind, timestamp, stage, providerId, null);
    }

    /// Returns the protocol production deadline policy for deterministic state tests.
    ///
    /// @return production deadlines
    private static ProtectorBootstrap.SupervisionPolicy productionPolicy() {
        return new ProtectorBootstrap.SupervisionPolicy(
                ProtectorProtocol.CONNECT_TIMEOUT,
                ProtectorProtocol.HEARTBEAT_LOSS_TIMEOUT,
                ProtectorProtocol.CORE_READY_TIMEOUT,
                ProtectorProtocol.PROVIDER_READY_TIMEOUT,
                ProtectorProtocol.PLUGIN_READY_TIMEOUT,
                ProtectorProtocol.HARD_STARTUP_TIMEOUT,
                ProtectorProtocol.TERMINATION_GRACE_TIMEOUT
        );
    }

    /// Runs one fixture through the same local transport and process supervision used by production entry.
    ///
    /// @param launcherHome isolated launcher-local home
    /// @param mode requested fixture behavior
    /// @throws Exception if child launch or supervision fails
    private static void runFixture(Path launcherHome, String mode) throws Exception {
        runFixture(
                launcherHome,
                mode,
                new ProtectorBootstrap.SupervisionPolicy(
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(2),
                        Duration.ofSeconds(2),
                        Duration.ofSeconds(2),
                        Duration.ofSeconds(2),
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(1)
                )
        );
    }

    /// Runs one fixture with an injected supervision policy.
    ///
    /// @param launcherHome isolated launcher-local home
    /// @param mode requested fixture behavior
    /// @param policy injected deadlines
    /// @throws Exception if child launch or supervision fails
    private static void runFixture(
            Path launcherHome,
            String mode,
            ProtectorBootstrap.SupervisionPolicy policy
    ) throws Exception {
        ProtectorBootstrap.supervise(launcherHome, fixtureCommand(), new String[]{mode}, policy);
    }

    /// Invokes EntryPoint's production role-selection seam with test callbacks.
    ///
    /// @param launcherArgs restart-barrier-stripped launcher arguments
    /// @param mixinRelaunch injected Mixin role selector
    /// @param protectorEntry injected Protector role selector
    /// @param launcher injected normal launcher-runtime entry
    /// @throws Exception if reflective lookup or invoked orchestration fails
    private static void invokeEntryPointOrchestration(
            String @Unmodifiable [] launcherArgs,
            Predicate<String[]> mixinRelaunch,
            InvocationHandler protectorEntry,
            Consumer<String[]> launcher
    ) throws Exception {
        Class<?> protectorEntryType = Class.forName("org.jackhuang.hmcl.EntryPoint$ProtectorEntry");
        Object protectorEntryProxy = java.lang.reflect.Proxy.newProxyInstance(
                EntryPoint.class.getClassLoader(),
                new Class<?>[]{protectorEntryType},
                protectorEntry
        );
        Method orchestration = EntryPoint.class.getDeclaredMethod(
                "runProtectedLauncher",
                String[].class,
                Predicate.class,
                protectorEntryType,
                Consumer.class
        );
        orchestration.setAccessible(true);
        try {
            orchestration.invoke(null, launcherArgs, mixinRelaunch, protectorEntryProxy, launcher);
        } catch (InvocationTargetException exception) {
            Throwable cause = Objects.requireNonNull(exception.getCause());
            if (cause instanceof Exception invocationFailure) {
                throw invocationFailure;
            }
            if (cause instanceof Error invocationError) {
                throw invocationError;
            }
            throw new IllegalStateException("EntryPoint orchestration failed", cause);
        }
    }

    /// Starts a direct fixture process that records its PID and remains alive without opening Protector IPC.
    ///
    /// @param marker process-marker path
    /// @return live fixture process
    /// @throws Exception if process launch or marker waiting fails
    private static Process startNeverConnectingFixture(Path marker) throws Exception {
        List<String> command = new ArrayList<>(fixtureCommand());
        command.add("never-connect:" + marker);
        Process process = new ProcessBuilder(command).start();
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!Files.exists(marker) && process.isAlive() && System.nanoTime() < deadline) {
            Thread.sleep(10L);
        }
        if (!Files.exists(marker)) {
            process.destroyForcibly();
            throw new IOException("Never-connecting fixture did not publish its process marker");
        }
        return process;
    }

    /// Builds the current test-worker classpath command for the fixture JVM.
    ///
    /// @return immutable Java command prefix ending with the fixture main class
    private static @Unmodifiable List<String> fixtureCommand() {
        Path javaExecutable = Path.of(
                System.getProperty("java.home"),
                "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java"
        );
        return List.of(
                javaExecutable.toString(),
                "-cp",
                System.getProperty("java.class.path"),
                ProtectorFixtureMain.class.getName()
        );
    }

    /// Builds a fixture command whose child resolves launcher-local diagnostics beneath one injected path.
    ///
    /// @param launcherHome injected child `hmcl.dir` value
    /// @return immutable Java command prefix ending with the fixture main class
    private static @Unmodifiable List<String> fixtureCommand(Path launcherHome) {
        List<String> command = new ArrayList<>(fixtureCommand());
        command.add(1, "-Dhmcl.dir=" + launcherHome);
        return List.copyOf(command);
    }

    /// Process fixture that publishes its nonzero exit only during a bounded supervisor wait.
    private static final class DelayedExitProcess extends Process {
        /// Exit status published after the bounded wait.
        private final int exitCode;

        /// Whether the process has published its exit.
        private boolean exited;

        /// Whether the supervisor requested a bounded wait.
        private boolean timedWaitRequested;

        /// Creates one pending nonzero process exit.
        ///
        /// @param exitCode eventual process exit status
        private DelayedExitProcess(int exitCode) {
            this.exitCode = exitCode;
        }

        /// Returns a sink for unused child standard input.
        ///
        /// @return null output stream
        @Override
        public OutputStream getOutputStream() {
            return OutputStream.nullOutputStream();
        }

        /// Returns an empty child standard-output stream.
        ///
        /// @return null input stream
        @Override
        public InputStream getInputStream() {
            return InputStream.nullInputStream();
        }

        /// Returns an empty child standard-error stream.
        ///
        /// @return null input stream
        @Override
        public InputStream getErrorStream() {
            return InputStream.nullInputStream();
        }

        /// Publishes and returns the configured exit status.
        ///
        /// @return configured exit status
        @Override
        public int waitFor() {
            exited = true;
            return exitCode;
        }

        /// Publishes the pending exit during a bounded supervisor wait.
        ///
        /// @param timeout requested timeout
        /// @param unit timeout unit
        /// @return always `true` after publishing the exit
        @Override
        public boolean waitFor(long timeout, TimeUnit unit) {
            timedWaitRequested = true;
            exited = true;
            return true;
        }

        /// Returns the published exit status.
        ///
        /// @return configured exit status
        /// @throws IllegalThreadStateException while the exit remains pending
        @Override
        public int exitValue() {
            if (!exited) {
                throw new IllegalThreadStateException("Exit remains pending");
            }
            return exitCode;
        }

        /// Publishes a terminated process state.
        @Override
        public void destroy() {
            exited = true;
        }

        /// Returns whether the pending exit has not yet been published.
        ///
        /// @return whether the process remains alive
        @Override
        public boolean isAlive() {
            return !exited;
        }

        /// Returns whether supervision requested a bounded wait.
        ///
        /// @return bounded-wait observation
        private boolean timedWaitRequested() {
            return timedWaitRequested;
        }
    }
}
