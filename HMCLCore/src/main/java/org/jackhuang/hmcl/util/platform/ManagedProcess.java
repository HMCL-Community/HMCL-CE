/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2020  huangyuhui <huanghongxun2008@126.com> and contributors
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
package org.jackhuang.hmcl.util.platform;

import org.jackhuang.hmcl.launch.StreamPump;
import org.jackhuang.hmcl.util.Lang;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Predicate;

/// The managed process.
///
/// @author huangyuhui
/// <!-- @see org.jackhuang.hmcl.launch.ExitWaiter -->
/// @see org.jackhuang.hmcl.launch.StreamPump
@NotNullByDefault
public final class ManagedProcess {
    /// Guards mutable output and related-thread state.
    private final ReentrantLock lock = new ReentrantLock();
    /// Raw operating-system process.
    private final Process process;
    /// Command tokens used to launch the process.
    private final List<String> commands;
    /// Java classpath when known.
    private final @Nullable String classpath;
    /// Mutable caller-owned process metadata.
    private final Map<String, @Nullable Object> properties = new HashMap<>();
    /// Captured standard-output and standard-error lines.
    private final List<String> lines = new ArrayList<>();
    /// Threads monitoring streams or process termination.
    private final List<Thread> relatedThreads = new ArrayList<>();
    /// Records launcher stop intent before process destruction can complete.
    private final AtomicBoolean launcherStopRequested = new AtomicBoolean();

    /// Starts and manages a process from the supplied builder.
    ///
    /// @param processBuilder configured process builder
    /// @throws IOException if the raw process cannot be started
    public ManagedProcess(ProcessBuilder processBuilder) throws IOException {
        this.process = processBuilder.start();
        this.commands = processBuilder.command();
        this.classpath = null;
    }

    /// Manages an existing process without a known Java classpath.
    ///
    /// @param process the raw system process that this instance manages
    /// @param commands the command line of `process`
    public ManagedProcess(Process process, List<String> commands) {
        this.process = process;
        this.commands = List.copyOf(commands);
        this.classpath = null;
    }

    /// Manages an existing process with optional Java classpath metadata.
    ///
    /// @param process the raw system process that this instance manages
    /// @param commands the command line of `process`
    /// @param classpath the Java classpath, or `null` when unavailable
    public ManagedProcess(Process process, List<String> commands, @Nullable String classpath) {
        this.process = process;
        this.commands = List.copyOf(commands);
        this.classpath = classpath;
    }

    /// Returns the raw operating-system process.
    ///
    /// @return managed raw process
    public Process getProcess() {
        return process;
    }

    /// Returns the stored command tokens.
    ///
    /// @return command tokens
    public List<String> getCommands() {
        return commands;
    }

    /// Returns the Java classpath when supplied by the launcher.
    ///
    /// @return classpath or `null` when unavailable
    public @Nullable String getClasspath() {
        return classpath;
    }

    /// Returns mutable metadata associated with this process.
    ///
    /// @return mutable process metadata; values may be `null`
    public Map<String, @Nullable Object> getProperties() {
        return properties;
    }

    /// Returns an immutable snapshot of captured output lines matching the optional filter.
    ///
    /// @param lineFilter line predicate, or `null` to include every line
    /// @return immutable output-line snapshot
    /// @see #addLine
    public @Unmodifiable List<String> getLines(@Nullable Predicate<String> lineFilter) {
        lock.lock();
        try {
            if (lineFilter == null)
                return List.copyOf(lines);

            ArrayList<String> res = new ArrayList<>();
            for (String line : this.lines) {
                if (lineFilter.test(line))
                    res.add(line);
            }
            return Collections.unmodifiableList(res);
        } finally {
            lock.unlock();
        }
    }

    /// Captures one standard-output or standard-error line.
    ///
    /// @param line captured line
    public void addLine(String line) {
        lock.lock();
        try {
            lines.add(line);
        } finally {
            lock.unlock();
        }
    }

    /// Registers a thread that monitors the raw process and should be interrupted on launcher stop.
    ///
    /// @param thread related monitoring thread
    public void addRelatedThread(Thread thread) {
        lock.lock();
        try {
            relatedThreads.add(thread);
        } finally {
            lock.unlock();
        }
    }

    /// Starts a daemon pump for raw process standard output.
    ///
    /// @param onLogLine consumer invoked for each decoded line
    public void pumpInputStream(Consumer<String> onLogLine) {
        addRelatedThread(Lang.thread(new StreamPump(process.getInputStream(), onLogLine, OperatingSystem.NATIVE_CHARSET), "ProcessInputStreamPump", true));
    }

    /// Starts a daemon pump for raw process standard error.
    ///
    /// @param onLogLine consumer invoked for each decoded line
    public void pumpErrorStream(Consumer<String> onLogLine) {
        addRelatedThread(Lang.thread(new StreamPump(process.getErrorStream(), onLogLine, OperatingSystem.NATIVE_CHARSET), "ProcessErrorStreamPump", true));
    }

    /// Reports whether the raw process is still running.
    ///
    /// @return `true` while the process has no exit value
    public boolean isRunning() {
        try {
            process.exitValue();
            return false;
        } catch (IllegalThreadStateException e) {
            return true;
        }
    }

    /// Returns the raw process exit code.
    ///
    /// @return terminal process exit code
    /// @throws IllegalThreadStateException if the process remains active
    public int getExitCode() {
        return process.exitValue();
    }

    /// Records launcher stop intent, requests graceful process destruction, and interrupts monitoring threads.
    public void stop() {
        launcherStopRequested.set(true);
        process.destroy();
        destroyRelatedThreads();
    }

    /// Reports whether the launcher requested this process to stop.
    ///
    /// @return `true` after {@link #stop()} begins
    public boolean isLauncherStopRequested() {
        return launcherStopRequested.get();
    }

    /// Interrupts every registered process-monitoring thread.
    public void destroyRelatedThreads() {
        lock.lock();
        try {
            relatedThreads.forEach(Thread::interrupt);
        } finally {
            lock.unlock();
        }
    }

    /// Returns a diagnostic command and running-state representation.
    ///
    /// @return diagnostic representation
    @Override
    public String toString() {
        return "ManagedProcess[commands=" + commands + ", isRunning=" + isRunning() + "]";
    }
}
