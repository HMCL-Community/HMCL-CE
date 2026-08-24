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

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/// Composes one existing process listener with exactly-once after-game-launch exit observation.
@NotNullByDefault
public final class GameLaunchHookProcessListener implements ProcessListener {
    /// Existing application listener, or `null` when only Hook observation is required.
    private final @Nullable ProcessListener delegate;

    /// Coordinator clock used to capture process end time.
    private final Clock clock;

    /// Exit observer invoked after the existing delegate.
    private final ExitObserver observer;

    /// Ensures delegate and Hook exit callbacks run at most once.
    private final AtomicBoolean exited = new AtomicBoolean();

    /// First successfully created process and its atomically published start time.
    private final AtomicReference<@Nullable ProcessStart> processStart = new AtomicReference<>();

    /// Creates one listener composition without creating a second process waiter.
    ///
    /// @param delegate existing process listener, or `null`
    /// @param clock clock used for process start and end time
    /// @param observer exactly-once exit observer
    public GameLaunchHookProcessListener(
            @Nullable ProcessListener delegate,
            Clock clock,
            ExitObserver observer
    ) {
        this.delegate = delegate;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.observer = Objects.requireNonNull(observer, "observer");
    }

    /// Records the first created process and its start time before forwarding to the existing delegate.
    ///
    /// Repeated calls remain delegated but cannot replace the process identity or start time used by after Hooks.
    ///
    /// @param process managed process
    @Override
    public void setProcess(ManagedProcess process) {
        ManagedProcess createdProcess = Objects.requireNonNull(process, "process");
        if (processStart.get() == null) {
            processStart.compareAndSet(null, new ProcessStart(createdProcess, clock.instant()));
        }
        if (delegate != null) {
            delegate.setProcess(createdProcess);
        }
    }

    /// Forwards one process log line to the existing delegate.
    ///
    /// @param log log line
    /// @param isErrorStream whether the line came from stderr
    @Override
    public void onLog(String log, boolean isErrorStream) {
        if (delegate != null) {
            delegate.onLog(log, isErrorStream);
        }
    }

    /// Forwards and observes the first exit callback only.
    ///
    /// @param exitCode process exit code
    /// @param exitType existing launcher exit classification
    @Override
    public void onExit(int exitCode, ExitType exitType) {
        Objects.requireNonNull(exitType, "exitType");
        if (!exited.compareAndSet(false, true)) {
            return;
        }

        @Nullable ProcessStart start = processStart.get();
        @Nullable ExitObservation observation = null;
        if (start != null) {
            Instant endedAt = clock.instant();
            long elapsedMilliseconds = Math.max(
                    0L, Duration.between(start.startedAt(), endedAt).toMillis());
            observation = new ExitObservation(
                    start.process().getProcess().pid(),
                    exitCode,
                    terminationKind(exitCode, exitType),
                    start.startedAt(),
                    endedAt,
                    elapsedMilliseconds
            );
        }
        try {
            if (delegate != null) {
                delegate.onExit(exitCode, exitType);
            }
        } finally {
            if (observation != null) {
                observer.onExit(observation);
            }
        }
    }

    /// Maps existing launcher exit state to one stable runtime-neutral identifier.
    ///
    /// @param exitCode process exit code
    /// @param exitType existing launcher exit classification
    /// @return stable termination kind
    private static String terminationKind(int exitCode, ExitType exitType) {
        return switch (exitType) {
            case NORMAL -> exitCode == 0 ? "normal" : "nonzero-exit";
            case JVM_ERROR, APPLICATION_ERROR -> "nonzero-exit";
            case SIGKILL -> "externally-killed";
            case INTERRUPTED -> "launcher-stop";
        };
    }

    /// Atomically pairs the first created process with its start instant.
    ///
    /// @param process first managed process supplied to the listener
    /// @param startedAt instant of the first `setProcess` call
    @NotNullByDefault
    private record ProcessStart(ManagedProcess process, Instant startedAt) {
        /// Rejects incomplete process start state.
        private ProcessStart {
            Objects.requireNonNull(process, "process");
            Objects.requireNonNull(startedAt, "startedAt");
        }
    }

    /// Receives one immutable exit observation after existing listener handling.
    @FunctionalInterface
    @NotNullByDefault
    public interface ExitObserver {
        /// Observes one owned process exit.
        ///
        /// @param observation immutable process exit observation
        void onExit(ExitObservation observation);
    }

    /// Carries runtime-neutral process termination facts into the Hook coordinator.
    ///
    /// @param pid owned process ID
    /// @param exitCode process exit code
    /// @param terminationKind stable termination identifier
    /// @param startedAt process start instant captured at successful creation
    /// @param endedAt process end instant
    /// @param elapsedMilliseconds nonnegative elapsed process lifetime
    @NotNullByDefault
    public record ExitObservation(
            long pid,
            int exitCode,
            String terminationKind,
            Instant startedAt,
            Instant endedAt,
            long elapsedMilliseconds
    ) {
        /// Rejects invalid or incomplete process observations.
        public ExitObservation {
            if (pid < 0L) {
                throw new IllegalArgumentException("Process ID must not be negative");
            }
            Objects.requireNonNull(terminationKind, "terminationKind");
            Objects.requireNonNull(startedAt, "startedAt");
            Objects.requireNonNull(endedAt, "endedAt");
            if (elapsedMilliseconds < 0L) {
                throw new IllegalArgumentException("Elapsed process lifetime must not be negative");
            }
        }
    }
}
