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

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/// Serializes plugin package, state, and permission mutations across threads and launcher processes.
@NotNullByDefault
public final class PluginMutationLock {
    /// Process-wide lock states indexed by normalized OS lock-file path.
    private static final Map<Path, LockState> PROCESS_LOCKS = new ConcurrentHashMap<>();

    /// Stable OS lock-file path for one launcher-local home.
    private final Path lockFile;

    /// Shared reentrant state for every helper bound to the same lock file in this JVM.
    private final LockState state;

    /// Creates a mutation lock rooted in one launcher-local home.
    ///
    /// @param localHome launcher-local home
    public PluginMutationLock(Path localHome) {
        lockFile = localHome.resolve("plugin-mutation.lock").toAbsolutePath().normalize();
        state = PROCESS_LOCKS.computeIfAbsent(lockFile, ignored -> new LockState());
    }

    /// Executes an I/O action while holding the shared reentrant process and OS file locks.
    ///
    /// @param action mutation action
    /// @throws IOException if lock acquisition or the mutation fails
    public void run(IORunnable action) throws IOException {
        call(() -> {
            action.run();
            return Boolean.TRUE;
        });
    }

    /// Executes an I/O calculation while holding the shared reentrant process and OS file locks.
    ///
    /// @param action mutation calculation
    /// @param <T> non-null result type
    /// @return calculation result
    /// @throws IOException if lock acquisition or the calculation fails
    public <T> T call(IOCallable<T> action) throws IOException {
        if (state.depth.get() > 0) {
            state.depth.set(state.depth.get() + 1);
            try {
                return action.call();
            } finally {
                state.depth.set(state.depth.get() - 1);
            }
        }

        state.processLock.lock();
        try {
            Files.createDirectories(lockFile.getParent());
            try (FileChannel channel = FileChannel.open(
                    lockFile,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE
            ); FileLock ignored = channel.lock()) {
                state.depth.set(1);
                try {
                    return action.call();
                } finally {
                    state.depth.remove();
                }
            }
        } finally {
            state.processLock.unlock();
        }
    }

    /// I/O action accepted by [run].
    @FunctionalInterface
    @NotNullByDefault
    public interface IORunnable {
        /// Performs the mutation.
        ///
        /// @throws IOException if the mutation fails
        void run() throws IOException;
    }

    /// I/O calculation accepted by [call].
    ///
    /// @param <T> non-null result type
    @FunctionalInterface
    @NotNullByDefault
    public interface IOCallable<T> {
        /// Performs the calculation.
        ///
        /// @return non-null calculation result
        /// @throws IOException if the calculation fails
        T call() throws IOException;
    }

    /// Reentrant state shared by all helpers targeting one lock file in the current JVM.
    @NotNullByDefault
    private static final class LockState {
        /// JVM-local serialization that prevents overlapping OS locks from different threads.
        private final ReentrantLock processLock = new ReentrantLock();

        /// Per-thread nesting depth shared by different helper instances for the same path.
        private final ThreadLocal<Integer> depth = ThreadLocal.withInitial(() -> 0);

        /// Creates an empty unlocked state.
        private LockState() {
        }
    }
}
