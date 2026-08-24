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
package org.jackhuang.hmcl.launch;

import org.jackhuang.hmcl.util.platform.ManagedProcess;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies that process-exit callbacks follow the raw operating-system process lifetime.
@NotNullByDefault
public final class ExitWaiterTest {
    /// Maximum duration allowed for deterministic thread coordination.
    private static final int TIMEOUT_SECONDS = 5;

    /// Waits for the raw process after launcher stop and reports its actual exit code exactly once.
    @Test
    public void waitsForRawProcessExitAfterLauncherStop() throws Exception {
        ControlledProcess rawProcess = new ControlledProcess(false, 23, new CountDownLatch(0));
        ManagedProcess managedProcess = new ManagedProcess(rawProcess, List.of("java", "Main"));
        AtomicInteger callbackCount = new AtomicInteger();
        AtomicInteger observedExitCode = new AtomicInteger(Integer.MIN_VALUE);
        AtomicReference<ProcessListener.ExitType> observedExitType =
                new AtomicReference<>(ProcessListener.ExitType.NORMAL);
        CountDownLatch callbackObserved = new CountDownLatch(1);
        CountDownLatch joinStarted = new CountDownLatch(1);
        CountDownLatch releaseJoin = new CountDownLatch(1);
        Thread joinedThread = new Thread(() -> {
            joinStarted.countDown();
            try {
                releaseJoin.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "ExitWaiterTest-Joined");
        joinedThread.start();
        Thread waiter = startWaiter(
                managedProcess, List.of(joinedThread), callbackCount,
                observedExitCode, observedExitType, callbackObserved);

        try {
            assertTrue(joinStarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "Joined thread did not start");
            assertTrue(rawProcess.awaitFirstWait(), "Exit waiter did not enter Process.waitFor()");

            managedProcess.stop();

            assertTrue(rawProcess.awaitSecondWait(), "Interrupted exit waiter did not resume Process.waitFor()");
            assertEquals(0, callbackCount.get(), "Exit callback ran before the raw process exited");
            assertFalse(callbackObserved.await(0, TimeUnit.SECONDS),
                    "Exit callback ran before the raw process exited");

            rawProcess.complete(23);

            assertTrue(rawProcess.awaitWaitForReturn(), "Exit waiter did not observe raw process completion");
            assertTrue(awaitCondition(() -> waiter.getState() == Thread.State.WAITING),
                    "Exit waiter did not wait for the joined thread");
            assertEquals(0, callbackCount.get(), "Exit callback ran before the joined thread exited");

            waiter.interrupt();
            assertTrue(awaitCondition(() -> !waiter.isInterrupted() && waiter.getState() == Thread.State.WAITING),
                    "Interrupted exit waiter did not resume waiting for the joined thread");
            assertEquals(0, callbackCount.get(), "Exit callback ran after an interrupted join");

            releaseJoin.countDown();
            assertTrue(callbackObserved.await(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    "Exit callback did not run after the raw process exited");
            waiter.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
            assertFalse(waiter.isAlive(), "Exit waiter did not finish");
            assertEquals(1, callbackCount.get());
            assertEquals(23, observedExitCode.get());
            assertEquals(ProcessListener.ExitType.INTERRUPTED, observedExitType.get());
        } finally {
            rawProcess.complete(23);
            releaseJoin.countDown();
            waiter.interrupt();
            waiter.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
            joinedThread.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
        }
    }

    /// Classifies a process that exits inside destroy as launcher-stopped even before waiter interruption.
    @Test
    public void publishesLauncherStopIntentBeforeDestroy() throws Exception {
        CountDownLatch callbackObserved = new CountDownLatch(1);
        ControlledProcess rawProcess = new ControlledProcess(true, 130, callbackObserved);
        ManagedProcess managedProcess = new ManagedProcess(rawProcess, List.of("java", "Main"));
        AtomicInteger callbackCount = new AtomicInteger();
        AtomicInteger observedExitCode = new AtomicInteger(Integer.MIN_VALUE);
        AtomicReference<ProcessListener.ExitType> observedExitType =
                new AtomicReference<>(ProcessListener.ExitType.NORMAL);
        Thread waiter = startWaiter(
                managedProcess, List.of(), callbackCount,
                observedExitCode, observedExitType, callbackObserved);

        try {
            assertTrue(rawProcess.awaitFirstWait(), "Exit waiter did not enter Process.waitFor()");

            managedProcess.stop();

            waiter.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
            assertFalse(waiter.isAlive(), "Exit waiter did not finish");
            assertEquals(1, callbackCount.get());
            assertEquals(130, observedExitCode.get());
            assertEquals(ProcessListener.ExitType.INTERRUPTED, observedExitType.get());
        } finally {
            rawProcess.complete(130);
            waiter.interrupt();
            waiter.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
        }
    }

    /// Starts an exit waiter and captures its single terminal callback.
    ///
    /// @param process managed process under test
    /// @param joins threads that must finish before callback
    /// @param callbackCount callback invocation counter
    /// @param observedExitCode captured exit code
    /// @param observedExitType captured exit classification
    /// @param callbackObserved latch released by the callback
    /// @return started waiter thread
    private static Thread startWaiter(
            ManagedProcess process,
            List<Thread> joins,
            AtomicInteger callbackCount,
            AtomicInteger observedExitCode,
            AtomicReference<ProcessListener.ExitType> observedExitType,
            CountDownLatch callbackObserved
    ) {
        Thread waiter = new Thread(new ExitWaiter(process, joins, (exitCode, exitType) -> {
            observedExitCode.set(exitCode);
            observedExitType.set(exitType);
            callbackCount.incrementAndGet();
            callbackObserved.countDown();
        }), "ExitWaiterTest");
        process.addRelatedThread(waiter);
        waiter.start();
        return waiter;
    }

    /// Spins until a deterministic concurrency condition becomes true or the timeout expires.
    ///
    /// @param condition condition to observe
    /// @return whether the condition became true before the timeout
    private static boolean awaitCondition(BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.onSpinWait();
        }
        return condition.getAsBoolean();
    }

    /// Provides a deterministic process lifecycle for exit-waiter concurrency tests.
    @NotNullByDefault
    private static final class ControlledProcess extends Process {
        /// Whether destroy completes the raw process immediately.
        private final boolean completeOnDestroy;
        /// Exit code used when destroy completes the process.
        private final int destroyExitCode;
        /// Callback latch that destroy waits for in the fast-exit scenario.
        private final CountDownLatch callbackObserved;
        /// Signals that the raw process has completed.
        private final CountDownLatch completed = new CountDownLatch(1);
        /// Signals the first waitFor invocation.
        private final CountDownLatch firstWaitEntered = new CountDownLatch(1);
        /// Signals a resumed waitFor invocation after interruption.
        private final CountDownLatch secondWaitEntered = new CountDownLatch(1);
        /// Signals that waitFor is returning the completed process result.
        private final CountDownLatch waitForReturned = new CountDownLatch(1);
        /// Counts waitFor invocations.
        private final AtomicInteger waitCount = new AtomicInteger();
        /// Raw process exit code, published through the completed latch.
        private int exitCode;

        /// Creates a controlled raw process.
        ///
        /// @param completeOnDestroy whether destroy completes the process
        /// @param destroyExitCode exit code used by destroy
        /// @param callbackObserved callback latch that destroy waits for after immediate completion
        private ControlledProcess(boolean completeOnDestroy, int destroyExitCode, CountDownLatch callbackObserved) {
            this.completeOnDestroy = completeOnDestroy;
            this.destroyExitCode = destroyExitCode;
            this.callbackObserved = callbackObserved;
        }

        /// Waits until the exit waiter first calls waitFor.
        ///
        /// @return whether the call was observed before the timeout
        private boolean awaitFirstWait() throws InterruptedException {
            return firstWaitEntered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }

        /// Waits until the exit waiter resumes waitFor after interruption.
        ///
        /// @return whether the resumed call was observed before the timeout
        private boolean awaitSecondWait() throws InterruptedException {
            return secondWaitEntered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }

        /// Waits until waitFor observes completion and begins returning.
        ///
        /// @return whether waitFor began returning before the timeout
        private boolean awaitWaitForReturn() throws InterruptedException {
            return waitForReturned.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }

        /// Completes the raw process with the supplied code.
        ///
        /// @param exitCode terminal raw process exit code
        private void complete(int exitCode) {
            this.exitCode = exitCode;
            completed.countDown();
        }

        /// Returns a sink for process standard input.
        ///
        /// @return null output stream
        @Override
        public OutputStream getOutputStream() {
            return OutputStream.nullOutputStream();
        }

        /// Returns an empty process standard-output stream.
        ///
        /// @return empty input stream
        @Override
        public InputStream getInputStream() {
            return InputStream.nullInputStream();
        }

        /// Returns an empty process standard-error stream.
        ///
        /// @return empty input stream
        @Override
        public InputStream getErrorStream() {
            return InputStream.nullInputStream();
        }

        /// Blocks until controlled process completion or thread interruption.
        ///
        /// @return configured raw process exit code
        @Override
        public int waitFor() throws InterruptedException {
            if (waitCount.incrementAndGet() == 1) {
                firstWaitEntered.countDown();
            } else {
                secondWaitEntered.countDown();
            }
            completed.await();
            waitForReturned.countDown();
            return exitCode;
        }

        /// Returns the exit code after completion.
        ///
        /// @return configured raw process exit code
        /// @throws IllegalThreadStateException if the process remains active
        @Override
        public int exitValue() {
            if (completed.getCount() != 0) {
                throw new IllegalThreadStateException("Process is still running");
            }
            return exitCode;
        }

        /// Optionally completes the process and holds destroy until the callback observes that completion.
        @Override
        public void destroy() {
            if (!completeOnDestroy) {
                return;
            }

            complete(destroyExitCode);
            try {
                assertTrue(callbackObserved.await(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                        "Exit callback did not observe destroy-time process completion");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Destroy wait was interrupted", e);
            }
        }
    }
}
