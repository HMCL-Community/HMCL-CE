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
package org.jackhuang.hmcl.util;

import org.jackhuang.hmcl.util.testsupport.RestartBarrierParent;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies that internal restart arguments are consumed before ordinary launcher startup.
@NotNullByDefault
public final class RestartBarrierTest {
    /// Removes a restart marker for a process that no longer exists and retains user arguments.
    @Test
    public void stripFinishedParentArgument() {
        String[] result = RestartBarrier.awaitParentsAndStrip(new String[]{
                "before",
                RestartBarrier.PARENT_PROCESS_ARGUMENT,
                Long.toString(Long.MAX_VALUE),
                "after"
        });

        assertArrayEquals(new String[]{"before", "after"}, result);
    }

    /// Removes malformed internal markers instead of forwarding them to the regular launcher.
    @Test
    public void stripMalformedParentArguments() {
        String[] result = RestartBarrier.awaitParentsAndStrip(new String[]{
                RestartBarrier.PARENT_PROCESS_ARGUMENT,
                "not-a-process",
                RestartBarrier.PARENT_PROCESS_ARGUMENT
        });

        assertArrayEquals(new String[0], result);
    }

    /// Keeps startup blocked until a live parent process has actually terminated.
    ///
    /// @throws Exception if process creation, synchronization, or cleanup fails
    @Test
    public void waitForLiveParentProcess() throws Exception {
        Path javaExecutable = Path.of(
                System.getProperty("java.home"),
                "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java"
        );
        Process parent = new ProcessBuilder(
                javaExecutable.toString(),
                "-cp",
                System.getProperty("java.class.path"),
                RestartBarrierParent.class.getName()
        ).redirectErrorStream(true).start();
        try (BufferedReader output = new BufferedReader(new InputStreamReader(
                parent.getInputStream(),
                StandardCharsets.UTF_8
        ))) {
            assertEquals("READY", output.readLine());

            AtomicReference<String @Nullable []> result = new AtomicReference<>();
            AtomicReference<@Nullable Throwable> failure = new AtomicReference<>();
            Thread barrierThread = new Thread(() -> {
                try {
                    result.set(RestartBarrier.awaitParentsAndStrip(new String[]{
                            "before",
                            RestartBarrier.PARENT_PROCESS_ARGUMENT,
                            Long.toString(parent.pid()),
                            "after"
                    }));
                } catch (Throwable throwable) {
                    failure.set(throwable);
                }
            }, "Restart Barrier Test");
            barrierThread.start();

            awaitWaitingState(barrierThread);
            assertTrue(parent.isAlive());
            assertNull(result.get());

            parent.getOutputStream().close();
            assertTrue(parent.waitFor(5, TimeUnit.SECONDS));
            barrierThread.join(5_000);

            assertFalse(barrierThread.isAlive());
            assertNull(failure.get());
            assertArrayEquals(new String[]{"before", "after"}, result.get());
        } finally {
            parent.destroyForcibly();
            parent.waitFor(5, TimeUnit.SECONDS);
        }
    }

    /// Waits until the barrier thread is blocked in the parent process exit future.
    ///
    /// @param thread barrier thread
    private static void awaitWaitingState(Thread thread) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            Thread.State state = thread.getState();
            if (state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING) {
                return;
            }
            Thread.onSpinWait();
        }
        throw new AssertionError("Restart barrier thread did not enter a waiting state");
    }
}
