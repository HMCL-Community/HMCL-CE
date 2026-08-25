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
package org.jackhuang.hmcl.plugin.bridge;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies owner-scoped handles, revocation, cancellation, and redacted dispatch failures.
@NotNullByDefault
class BridgeHandleRegistryTest {
    /// Dedicated callback executor used by dispatcher tests.
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    /// Stops callback workers after each test.
    @AfterEach
    void closeExecutor() {
        executor.shutdownNow();
    }

    /// Resolves a handle only when the authority owner, generation, and type all match.
    @Test
    void validatesOwnerGenerationAndTypeBeforeReturningReferences() {
        BridgeHandleRegistry<String> registry = ownerRegistry();
        Object target = new Object();
        BridgeHandle handle = registry.register("plugin-a", "launcher.profile", target);

        assertSame(target, registry.resolve("plugin-a", handle, "launcher.profile"));
        assertCategory(BridgeError.Category.PERMISSION_DENIED,
                () -> registry.resolve("plugin-b", handle, "launcher.profile"));
        assertCategory(BridgeError.Category.TYPE_MISMATCH,
                () -> registry.resolve("plugin-a", handle, "launcher.account"));
        assertCategory(BridgeError.Category.STALE_HANDLE,
                () -> registry.resolve("plugin-a",
                        new BridgeHandle(handle.id(), handle.generation() + 1, handle.type()),
                        "launcher.profile"));
    }

    /// Revokes all owner references and runs every release action after all generations are invalidated.
    @Test
    void revokesCompleteOwnerBeforeReleasingReferences() {
        BridgeHandleRegistry<String> registry = ownerRegistry();
        AtomicInteger releases = new AtomicInteger();
        BridgeHandle first = registry.register("plugin-a", "callback", new Object(), () -> {
            assertEquals(0, registry.liveCount("plugin-a"), "all owner handles must already be stale");
            releases.incrementAndGet();
        });
        BridgeHandle second = registry.register("plugin-a", "stream", new Object(), releases::incrementAndGet);
        BridgeHandle other = registry.register("plugin-b", "stream", new Object(), releases::incrementAndGet);

        registry.revokeOwner("plugin-a");

        assertEquals(2, releases.get());
        assertCategory(BridgeError.Category.STALE_HANDLE,
                () -> registry.resolve("plugin-a", first, first.type()));
        assertCategory(BridgeError.Category.STALE_HANDLE,
                () -> registry.resolve("plugin-a", second, second.type()));
        assertTrue(registry.isLive(other));
    }

    /// Reuses released numeric IDs only with a new generation so old handles cannot regain authority.
    @Test
    void incrementsGenerationBeforeReusingIds() {
        BridgeHandleRegistry<String> registry = ownerRegistry();
        BridgeHandle stale = registry.register("plugin-a", "launcher.profile", new Object());
        registry.revokeOwner("plugin-a");

        Object replacement = new Object();
        BridgeHandle current = registry.register("plugin-a", "launcher.profile", replacement);

        assertEquals(stale.id(), current.id());
        assertNotEquals(stale.generation(), current.generation());
        assertCategory(BridgeError.Category.STALE_HANDLE,
                () -> registry.resolve("plugin-a", stale, stale.type()));
        assertSame(replacement, registry.resolve("plugin-a", current, current.type()));
    }

    /// Cancels an in-flight callback and completes it with the portable cancellation category.
    @Test
    void propagatesCallbackCancellation() throws Exception {
        BridgeDispatcher dispatcher = new BridgeDispatcher(executor);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch cancellationObserved = new CountDownLatch(1);
        AtomicBoolean observedCancellation = new AtomicBoolean();
        BridgeDispatcher.Dispatch dispatch = dispatcher.dispatch("plugin-a", "core.blocking", cancellation -> {
            entered.countDown();
            while (!cancellation.isCancellationRequested()) {
                try {
                    Thread.sleep(5L);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            }
            observedCancellation.set(true);
            cancellationObserved.countDown();
            return BridgeValue.string("late-result");
        });

        assertTrue(entered.await(5, TimeUnit.SECONDS));
        assertTrue(dispatch.cancel());
        BridgeValue result = dispatch.completion().get(5, TimeUnit.SECONDS);

        assertEquals(BridgeError.Category.CANCELLED, ((BridgeValue.ErrorValue) result).value().category());
        assertTrue(cancellationObserved.await(5, TimeUnit.SECONDS));
        assertTrue(observedCancellation.get());
    }

    /// Cancels every callback owned by an unloading plugin without affecting callbacks of another owner.
    @Test
    void cancelsCallbacksByOwner() throws Exception {
        BridgeDispatcher dispatcher = new BridgeDispatcher(executor);
        CountDownLatch entered = new CountDownLatch(1);
        BridgeDispatcher.Dispatch cancelled = dispatcher.dispatch("plugin-a", "core.wait", cancellation -> {
            entered.countDown();
            while (!cancellation.isCancellationRequested()) {
                Thread.onSpinWait();
            }
            return BridgeValue.nullValue();
        });
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        BridgeDispatcher.Dispatch untouched = dispatcher.dispatch(
                "plugin-b", "core.read", cancellation -> BridgeValue.integer(7));

        dispatcher.cancelOwner("plugin-a");

        assertEquals(BridgeError.Category.CANCELLED,
                ((BridgeValue.ErrorValue) cancelled.completion().get(5, TimeUnit.SECONDS)).value().category());
        assertEquals(BridgeValue.integer(7), untouched.completion().get(5, TimeUnit.SECONDS));
    }

    /// Removes a cancelled callback from owner tracking even when it never starts on the executor.
    @Test
    void forgetsCallbackCancelledWhileQueued() throws Exception {
        BridgeDispatcher dispatcher = new BridgeDispatcher(executor);
        CountDownLatch blockerEntered = new CountDownLatch(1);
        CountDownLatch releaseBlocker = new CountDownLatch(1);
        AtomicBoolean queuedCallbackRan = new AtomicBoolean();
        BridgeDispatcher.Dispatch blocker = dispatcher.dispatch("plugin-b", "core.block", cancellation -> {
            blockerEntered.countDown();
            releaseBlocker.await(5, TimeUnit.SECONDS);
            return BridgeValue.nullValue();
        });
        assertTrue(blockerEntered.await(5, TimeUnit.SECONDS));
        BridgeDispatcher.Dispatch queued = dispatcher.dispatch("plugin-a", "core.queued", cancellation -> {
            queuedCallbackRan.set(true);
            return BridgeValue.nullValue();
        });

        dispatcher.cancelOwner("plugin-a");

        assertEquals(BridgeError.Category.CANCELLED,
                ((BridgeValue.ErrorValue) queued.completion().get(5, TimeUnit.SECONDS)).value().category());
        assertEquals(0, dispatcher.activeCount("plugin-a"));
        assertFalse(queuedCallbackRan.get());
        releaseBlocker.countDown();
        blocker.completion().get(5, TimeUnit.SECONDS);
    }

    /// Converts callback exceptions to a stable category without retaining their cause or secret message.
    @Test
    void redactsCallbackExceptions() throws Exception {
        BridgeDispatcher dispatcher = new BridgeDispatcher(executor);
        BridgeDispatcher.Dispatch dispatch = dispatcher.dispatch("plugin-a", "core.secret", cancellation -> {
            throw new IllegalStateException("access-token=very-secret");
        });

        BridgeError error = ((BridgeValue.ErrorValue) dispatch.completion().get(5, TimeUnit.SECONDS)).value();

        assertEquals(BridgeError.Category.CALLBACK_FAILED, error.category());
        assertFalse(error.getMessage().contains("very-secret"));
        assertEquals(null, error.getCause());
    }

    /// Creates a registry whose opaque test authority resolves directly to one canonical plugin owner.
    private static BridgeHandleRegistry<String> ownerRegistry() {
        return new BridgeHandleRegistry<>(token -> token);
    }

    /// Verifies one Bridge operation fails with the expected portable category.
    ///
    /// @param expected expected category
    /// @param operation operation expected to fail
    private static void assertCategory(BridgeError.Category expected, Runnable operation) {
        BridgeError error = assertThrows(BridgeError.class, operation::run);
        assertEquals(expected, error.category());
    }

}
