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
package org.jackhuang.hmcl;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies process-wide shutdown deferral while launch-owned leases remain active.
@NotNullByDefault
public final class ApplicationShutdownCoordinatorTest {
    /// Starts shutdown immediately when no lease is active.
    @Test
    public void noLeaseStartsShutdownImmediately() {
        AtomicInteger shutdowns = new AtomicInteger();
        AtomicInteger hides = new AtomicInteger();
        ApplicationShutdownCoordinator coordinator =
                new ApplicationShutdownCoordinator(shutdowns::incrementAndGet, hides::incrementAndGet);

        coordinator.requestShutdown();

        assertEquals(1, shutdowns.get());
        assertEquals(0, hides.get());
    }

    /// Defers one requested shutdown until every active lease closes.
    @Test
    public void requestedShutdownWaitsForAllLeases() throws Exception {
        AtomicInteger shutdowns = new AtomicInteger();
        AtomicInteger hides = new AtomicInteger();
        ApplicationShutdownCoordinator coordinator =
                new ApplicationShutdownCoordinator(shutdowns::incrementAndGet, hides::incrementAndGet);
        AutoCloseable first = coordinator.acquireLease("after-game-launch:first");
        AutoCloseable second = coordinator.acquireLease("after-game-launch:second");

        coordinator.requestShutdown();

        assertEquals(1, hides.get());
        assertEquals(0, shutdowns.get());
        first.close();
        assertEquals(0, shutdowns.get());
        second.close();
        assertEquals(1, shutdowns.get());
    }

    /// Makes repeated shutdown requests and repeated lease closure side-effect free.
    @Test
    public void requestAndLeaseCloseAreIdempotent() throws Exception {
        AtomicInteger shutdowns = new AtomicInteger();
        AtomicInteger hides = new AtomicInteger();
        ApplicationShutdownCoordinator coordinator =
                new ApplicationShutdownCoordinator(shutdowns::incrementAndGet, hides::incrementAndGet);
        AutoCloseable lease = coordinator.acquireLease("after-game-launch:idempotent");

        coordinator.requestShutdown();
        coordinator.requestShutdown();
        lease.close();
        lease.close();
        coordinator.requestShutdown();

        assertEquals(1, hides.get());
        assertEquals(1, shutdowns.get());
    }

    /// Keeps lease acquisition inert until a shutdown request actually becomes pending.
    @Test
    public void acquiringLeaseBeforeRequestHasNoUiSideEffects() throws Exception {
        AtomicInteger shutdowns = new AtomicInteger();
        AtomicInteger hides = new AtomicInteger();
        ApplicationShutdownCoordinator coordinator =
                new ApplicationShutdownCoordinator(shutdowns::incrementAndGet, hides::incrementAndGet);
        AutoCloseable lease = coordinator.acquireLease("after-game-launch:idle");

        assertEquals(0, hides.get());
        assertEquals(0, shutdowns.get());
        lease.close();
        assertEquals(0, hides.get());
        assertEquals(0, shutdowns.get());
    }

    /// Rejects leases acquired after the real shutdown action has already started.
    @Test
    public void acquireAfterShutdownStartedIsRejected() {
        ApplicationShutdownCoordinator coordinator =
                new ApplicationShutdownCoordinator(() -> {
                }, () -> {
                });
        coordinator.requestShutdown();

        assertThrows(IllegalStateException.class,
                () -> coordinator.acquireLease("after-game-launch:too-late"));
    }

    /// Starts shutdown once when competing threads close the same final lease.
    @Test
    public void concurrentCloseOfFinalLeaseStartsShutdownOnce() throws Exception {
        AtomicInteger shutdowns = new AtomicInteger();
        AtomicInteger hides = new AtomicInteger();
        ApplicationShutdownCoordinator coordinator =
                new ApplicationShutdownCoordinator(shutdowns::incrementAndGet, hides::incrementAndGet);
        AutoCloseable lease = coordinator.acquireLease("after-game-launch:concurrent");
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        coordinator.requestShutdown();

        try {
            Future<?> first = executor.submit(() -> closeAfter(start, lease));
            Future<?> second = executor.submit(() -> closeAfter(start, lease));
            start.countDown();
            first.get();
            second.get();
        } finally {
            executor.shutdownNow();
        }

        assertEquals(1, hides.get());
        assertEquals(1, shutdowns.get());
    }

    /// Completes the defer action before a concurrent final release may start real shutdown.
    @Test
    public void concurrentFinalReleaseCannotOvertakeDeferAction() throws Exception {
        AtomicInteger sequence = new AtomicInteger();
        AtomicInteger deferFinishedOrder = new AtomicInteger();
        AtomicInteger shutdownOrder = new AtomicInteger();
        CountDownLatch deferStarted = new CountDownLatch(1);
        CountDownLatch allowDeferCompletion = new CountDownLatch(1);
        ApplicationShutdownCoordinator coordinator = new ApplicationShutdownCoordinator(
                () -> shutdownOrder.set(sequence.incrementAndGet()),
                () -> {
                    deferStarted.countDown();
                    await(allowDeferCompletion);
                    deferFinishedOrder.set(sequence.incrementAndGet());
                }
        );
        AutoCloseable lease = coordinator.acquireLease("after-game-launch:ordering");
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            Future<?> request = executor.submit(coordinator::requestShutdown);
            assertTrue(deferStarted.await(5, TimeUnit.SECONDS));
            lease.close();
            allowDeferCompletion.countDown();
            request.get();
        } finally {
            allowDeferCompletion.countDown();
            executor.shutdownNow();
        }

        assertEquals(1, deferFinishedOrder.get());
        assertEquals(2, shutdownOrder.get());
    }

    /// Waits for a shared start signal and closes one lease without leaking checked exceptions from the thread.
    ///
    /// @param start shared close signal
    /// @param lease lease to close
    private static void closeAfter(CountDownLatch start, AutoCloseable lease) {
        try {
            start.await();
            lease.close();
        } catch (Exception exception) {
            throw new AssertionError("Failed to close shutdown lease", exception);
        }
    }

    /// Waits for one latch inside a callback that cannot declare checked exceptions.
    ///
    /// @param latch latch to await
    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for shutdown callback ordering", exception);
        }
    }
}
