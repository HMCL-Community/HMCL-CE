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

/// Composes one existing process listener with exactly-once after-game-launch exit observation.
@NotNullByDefault
public final class GameLaunchHookProcessListener implements ProcessListener {
    /// Existing application listener, or `null` when only Hook observation is required.
    private final @Nullable ProcessListener delegate;

    /// Launch session start instant.
    private final Instant startedAt;

    /// Coordinator clock used to capture process end time.
    private final Clock clock;

    /// Exit observer invoked after the existing delegate.
    private final ExitObserver observer;

    /// Ensures delegate and Hook exit callbacks run at most once.
    private final AtomicBoolean exited = new AtomicBoolean();

    /// Managed process supplied after successful process creation.
    private volatile @Nullable ManagedProcess process;

    /// Creates one listener composition without creating a second process waiter.
    ///
    /// @param delegate existing process listener, or `null`
    /// @param startedAt launch session start instant
    /// @param clock clock used for process end time
    /// @param observer exactly-once exit observer
    public GameLaunchHookProcessListener(
            @Nullable ProcessListener delegate,
            Instant startedAt,
            Clock clock,
            ExitObserver observer
    ) {
        this.delegate = delegate;
        this.startedAt = Objects.requireNonNull(startedAt, "startedAt");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.observer = Objects.requireNonNull(observer, "observer");
    }

    /// Stores the created process before forwarding it to the existing delegate.
    ///
    /// @param process managed process
    @Override
    public void setProcess(ManagedProcess process) {
        this.process = Objects.requireNonNull(process, "process");
        if (delegate != null) {
            delegate.setProcess(process);
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

        @Nullable ManagedProcess exitedProcess = process;
        Instant endedAt = clock.instant();
        long elapsedMilliseconds = Math.max(0L, Duration.between(startedAt, endedAt).toMillis());
        @Nullable ExitObservation observation = exitedProcess == null ? null : new ExitObservation(
                exitedProcess.getProcess().pid(),
                exitCode,
                terminationKind(exitCode, exitType),
                endedAt,
                elapsedMilliseconds
        );
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
    /// @param endedAt process end instant
    /// @param elapsedMilliseconds nonnegative elapsed process lifetime
    @NotNullByDefault
    public record ExitObservation(
            long pid,
            int exitCode,
            String terminationKind,
            Instant endedAt,
            long elapsedMilliseconds
    ) {
        /// Rejects invalid or incomplete process observations.
        public ExitObservation {
            if (pid < 0L) {
                throw new IllegalArgumentException("Process ID must not be negative");
            }
            Objects.requireNonNull(terminationKind, "terminationKind");
            Objects.requireNonNull(endedAt, "endedAt");
            if (elapsedMilliseconds < 0L) {
                throw new IllegalArgumentException("Elapsed process lifetime must not be negative");
            }
        }
    }
}
