/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2026  huangyuhui <huanghongxun2008@126.com> and contributors
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

import javafx.application.Platform;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/// Executes test actions on the JavaFX application thread for production APIs guarded by
/// [org.jackhuang.hmcl.ui.FXUtils#checkFxUserThread()].
///
/// Only the guarded call itself should be wrapped. Wrapping a whole test body would run blocking waits and
/// background-thread assertions on the FX thread, and would stop the guard from catching real threading bugs.
@NotNullByDefault
public final class FXThreadTestSupport {
    /// Upper bound for one FX action; large enough for slow CI, small enough to fail instead of hanging Gradle.
    private static final long TIMEOUT_SECONDS = 30L;

    /// Prevents instantiation.
    private FXThreadTestSupport() {
    }

    /// Runs one action on the JavaFX application thread and waits for it to finish.
    ///
    /// Failures are rethrown on the calling thread with their original type, so `AssertionError` and
    /// `assertThrows` semantics are preserved.
    ///
    /// @param action action requiring the JavaFX application thread
    /// @throws AssertionError if the toolkit is unavailable, the wait times out, or the wait is interrupted
    public static void runOnFxThread(Runnable action) {
        if (Platform.isFxApplicationThread()) {
            action.run();
            return;
        }
        if (!JavaFXLauncher.isStarted()) {
            throw new AssertionError("JavaFX toolkit unavailable; gate this test with "
                    + "@EnabledIf(\"org.jackhuang.hmcl.JavaFXLauncher#isStarted\")");
        }

        AtomicReference<@Nullable Throwable> failure = new AtomicReference<>();
        CountDownLatch finished = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                action.run();
            } catch (Throwable thrown) {
                failure.set(thrown);
            } finally {
                finished.countDown();
            }
        });
        try {
            if (!finished.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out after " + TIMEOUT_SECONDS
                        + "s waiting for the JavaFX application thread");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for the JavaFX application thread", exception);
        }

        @Nullable Throwable thrown = failure.get();
        if (thrown instanceof Error error) {
            // Preserves AssertionError and opentest4j assertion failures.
            throw error;
        }
        if (thrown instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        if (thrown != null) {
            throw new AssertionError("Checked exception on the JavaFX application thread", thrown);
        }
    }
}
