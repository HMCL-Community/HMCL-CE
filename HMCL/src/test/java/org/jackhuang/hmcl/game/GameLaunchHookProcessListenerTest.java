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
package org.jackhuang.hmcl.game;

import org.jackhuang.hmcl.launch.ProcessListener;
import org.jackhuang.hmcl.util.platform.ManagedProcess;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies exactly-once exit observation while preserving every existing process-listener callback.
@NotNullByDefault
public final class GameLaunchHookProcessListenerTest {
    /// Fixed process start time.
    private static final Instant STARTED_AT = Instant.parse("2026-08-24T03:00:00Z");

    /// Forwards process, log, and one exit callback while emitting one after-Hook observation.
    @Test
    public void exitDispatchesAfterOnceAndPreservesDelegate() {
        MutableClock clock = new MutableClock(STARTED_AT);
        ListenerProbe delegate = new ListenerProbe();
        List<GameLaunchHookProcessListener.ExitObservation> observations = new ArrayList<>();
        GameLaunchHookProcessListener listener = new GameLaunchHookProcessListener(
                delegate, clock, observations::add);
        ManagedProcess process = managedProcess(4242L);

        clock.setInstant(STARTED_AT.plusSeconds(1));
        listener.setProcess(process);
        clock.setInstant(STARTED_AT.plusMillis(1500));
        listener.setProcess(process);
        clock.setInstant(STARTED_AT.plusMillis(2500));
        listener.onLog("game log", true);
        listener.onExit(137, ProcessListener.ExitType.SIGKILL);
        listener.onExit(137, ProcessListener.ExitType.SIGKILL);

        assertSame(process, delegate.process);
        assertEquals(List.of("game log:true"), delegate.logs);
        assertEquals(1, delegate.exitCalls.get());
        assertEquals(137, delegate.exitCode);
        assertEquals(ProcessListener.ExitType.SIGKILL, delegate.exitType);
        assertEquals(1, observations.size());
        GameLaunchHookProcessListener.ExitObservation observation = observations.get(0);
        assertEquals(4242L, observation.pid());
        assertEquals(137, observation.exitCode());
        assertEquals("externally-killed", observation.terminationKind());
        assertEquals(STARTED_AT.plusMillis(2500), observation.endedAt());
        assertEquals(1500L, observation.elapsedMilliseconds());
    }

    /// Maps every existing exit type and normal nonzero exit to stable Hook termination identifiers.
    @Test
    public void mapsAllExitTypesDeterministically() {
        assertTermination(ProcessListener.ExitType.NORMAL, 0, "normal");
        assertTermination(ProcessListener.ExitType.NORMAL, 1, "nonzero-exit");
        assertTermination(ProcessListener.ExitType.JVM_ERROR, 1, "nonzero-exit");
        assertTermination(ProcessListener.ExitType.APPLICATION_ERROR, 1, "nonzero-exit");
        assertTermination(ProcessListener.ExitType.SIGKILL, 137, "externally-killed");
        assertTermination(ProcessListener.ExitType.INTERRUPTED, 130, "launcher-stop");
    }

    /// Emits the after observation from a finally path when the existing delegate throws on exit.
    @Test
    public void delegateExitFailureDoesNotSuppressAfterObservation() {
        AtomicInteger observations = new AtomicInteger();
        RuntimeException delegateFailure = new RuntimeException("delegate failed");
        ProcessListener delegate = new ProcessListener() {
            /// Ignores logs for this exit-only probe.
            ///
            /// @param log log line
            /// @param isErrorStream whether the line came from stderr
            @Override
            public void onLog(String log, boolean isErrorStream) {
            }

            /// Throws the expected delegate failure.
            ///
            /// @param exitCode process exit code
            /// @param exitType classified exit type
            @Override
            public void onExit(int exitCode, ExitType exitType) {
                throw delegateFailure;
            }
        };
        GameLaunchHookProcessListener listener = new GameLaunchHookProcessListener(
                delegate,
                Clock.fixed(STARTED_AT.plusSeconds(1), ZoneOffset.UTC),
                observation -> observations.incrementAndGet()
        );
        listener.setProcess(managedProcess(55L));

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> listener.onExit(1, ProcessListener.ExitType.APPLICATION_ERROR));

        assertSame(delegateFailure, thrown);
        assertEquals(1, observations.get());
    }

    /// Does not invent an after event when no managed process was ever supplied.
    @Test
    public void exitWithoutCreatedProcessNotifiesOnlyDelegate() {
        ListenerProbe delegate = new ListenerProbe();
        AtomicInteger observations = new AtomicInteger();
        MutableClock clock = new MutableClock(STARTED_AT.plusSeconds(1));
        GameLaunchHookProcessListener listener = new GameLaunchHookProcessListener(
                delegate,
                clock,
                observation -> observations.incrementAndGet()
        );

        listener.onExit(1, ProcessListener.ExitType.APPLICATION_ERROR);

        assertEquals(1, delegate.exitCalls.get());
        assertEquals(0, observations.get());
        assertEquals(0, clock.instantCalls());
    }

    /// Supports an absent delegate while retaining after observation and nonnegative elapsed time.
    @Test
    public void closeModeHookOnlyListenerReportsExitWithoutDelegate() {
        List<GameLaunchHookProcessListener.ExitObservation> observations = new ArrayList<>();
        GameLaunchHookProcessListener listener = new GameLaunchHookProcessListener(
                null,
                Clock.fixed(STARTED_AT.minusSeconds(1), ZoneOffset.UTC),
                observations::add
        );

        listener.setProcess(managedProcess(99L));
        listener.onLog("ignored", false);
        listener.onExit(0, ProcessListener.ExitType.NORMAL);

        assertEquals(1, observations.size());
        assertEquals(0L, observations.get(0).elapsedMilliseconds());
    }

    /// Observes one exit mapping for a fresh listener.
    ///
    /// @param exitType existing exit type
    /// @param exitCode process exit code
    /// @param expectedKind expected stable termination kind
    private static void assertTermination(
            ProcessListener.ExitType exitType,
            int exitCode,
            String expectedKind
    ) {
        List<GameLaunchHookProcessListener.ExitObservation> observations = new ArrayList<>();
        GameLaunchHookProcessListener listener = new GameLaunchHookProcessListener(
                null,
                Clock.fixed(STARTED_AT.plusMillis(10), ZoneOffset.UTC),
                observations::add
        );
        listener.setProcess(managedProcess(7L));

        listener.onExit(exitCode, exitType);

        assertEquals(1, observations.size());
        assertEquals(expectedKind, observations.get(0).terminationKind());
    }

    /// Wraps one deterministic fake process.
    ///
    /// @param pid fake process ID
    /// @return managed fake process
    private static ManagedProcess managedProcess(long pid) {
        return new ManagedProcess(new FakeProcess(pid), List.of("java"));
    }

    /// Records every delegated process-listener callback.
    @NotNullByDefault
    private static final class ListenerProbe implements ProcessListener {
        /// Process received through `setProcess`, or `null` before that callback.
        private @Nullable ManagedProcess process;

        /// Delegated log lines with their stream marker.
        private final List<String> logs = new ArrayList<>();

        /// Number of delegated exit calls.
        private final AtomicInteger exitCalls = new AtomicInteger();

        /// Last exit code.
        private int exitCode;

        /// Last exit type, or `null` before exit.
        private @Nullable ExitType exitType;

        /// Records the managed process.
        ///
        /// @param process managed process
        @Override
        public void setProcess(ManagedProcess process) {
            this.process = process;
        }

        /// Records one process log line.
        ///
        /// @param log log line
        /// @param isErrorStream whether the line came from stderr
        @Override
        public void onLog(String log, boolean isErrorStream) {
            logs.add(log + ":" + isErrorStream);
        }

        /// Records one process exit callback.
        ///
        /// @param exitCode process exit code
        /// @param exitType classified exit type
        @Override
        public void onExit(int exitCode, ExitType exitType) {
            this.exitCode = exitCode;
            this.exitType = exitType;
            exitCalls.incrementAndGet();
        }
    }

    /// Supplies deterministic mutable instants to the listener.
    @NotNullByDefault
    private static final class MutableClock extends Clock {
        /// Current clock instant.
        private Instant instant;

        /// Number of instant reads.
        private int instantCalls;

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

        /// Returns the number of instant reads.
        ///
        /// @return instant read count
        private int instantCalls() {
            return instantCalls;
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
            instantCalls++;
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
