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

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies reentrant and cross-instance serialization of plugin mutation locks.
@NotNullByDefault
public final class PluginMutationLockTest {
    /// Allows nested helpers bound to the same path without an overlapping-file-lock failure.
    ///
    /// @param temporaryDirectory isolated launcher home
    /// @throws Exception if lock acquisition fails
    @Test
    public void allowSameThreadNestedHelpers(@TempDir Path temporaryDirectory) throws Exception {
        PluginMutationLock first = new PluginMutationLock(temporaryDirectory);
        PluginMutationLock second = new PluginMutationLock(temporaryDirectory);
        List<String> calls = new ArrayList<>();

        first.run(() -> {
            calls.add("outer");
            second.run(() -> calls.add("inner"));
        });

        assertEquals(List.of("outer", "inner"), calls);
    }

    /// Serializes different helper instances on different threads before either requests the OS lock.
    ///
    /// @param temporaryDirectory isolated launcher home
    /// @throws Exception if concurrency coordination or lock acquisition fails
    @Test
    public void serializeDifferentThreads(@TempDir Path temporaryDirectory) throws Exception {
        PluginMutationLock first = new PluginMutationLock(temporaryDirectory);
        PluginMutationLock second = new PluginMutationLock(temporaryDirectory);
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondAttempted = new CountDownLatch(1);
        CountDownLatch secondEntered = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> firstFuture = executor.submit(() -> {
                first.run(() -> {
                    firstEntered.countDown();
                    await(releaseFirst);
                });
                return null;
            });
            firstEntered.await();
            Future<?> secondFuture = executor.submit(() -> {
                secondAttempted.countDown();
                second.run(secondEntered::countDown);
                return null;
            });

            secondAttempted.await();
            assertEquals(1L, secondEntered.getCount());
            releaseFirst.countDown();
            firstFuture.get();
            secondFuture.get();
            assertEquals(0L, secondEntered.getCount());
        } finally {
            executor.shutdownNow();
        }
    }

    /// Releases both lock layers after a failed mutation.
    ///
    /// @param temporaryDirectory isolated launcher home
    /// @throws Exception if the follow-up acquisition fails
    @Test
    public void releaseAfterFailure(@TempDir Path temporaryDirectory) throws Exception {
        PluginMutationLock lock = new PluginMutationLock(temporaryDirectory);

        assertThrows(IOException.class, () -> lock.run(() -> {
            throw new IOException("expected");
        }));
        assertEquals("released", lock.call(() -> "released"));
    }

    /// Waits for a test latch while translating interruption into I/O failure.
    ///
    /// @param latch latch to await
    /// @throws IOException if the test thread is interrupted
    private static void await(CountDownLatch latch) throws IOException {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for lock test coordination", exception);
        }
    }
}
