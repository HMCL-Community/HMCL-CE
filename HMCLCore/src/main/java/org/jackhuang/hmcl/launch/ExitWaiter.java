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
package org.jackhuang.hmcl.launch;

import org.jackhuang.hmcl.event.EventBus;
import org.jackhuang.hmcl.event.JVMLaunchFailedEvent;
import org.jackhuang.hmcl.event.ProcessExitedAbnormallyEvent;
import org.jackhuang.hmcl.event.ProcessStoppedEvent;
import org.jackhuang.hmcl.util.Log4jLevel;
import org.jackhuang.hmcl.util.StringUtils;
import org.jackhuang.hmcl.util.platform.ManagedProcess;
import org.jackhuang.hmcl.util.platform.OperatingSystem;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.util.Collection;
import java.util.List;
import java.util.function.BiConsumer;

/// Waits for raw process termination, joins its stream pumps, and emits one terminal callback.
///
/// @author huangyuhui
@NotNullByDefault
final class ExitWaiter implements Runnable {
    /// Managed process whose raw lifetime is observed.
    private final ManagedProcess process;
    /// Immutable stream-pump threads that must finish before the exit callback.
    private final @Unmodifiable List<Thread> joins;
    /// Consumer invoked exactly once after raw process exit and stream completion.
    private final BiConsumer<Integer, ProcessListener.ExitType> watcher;

    /// Creates an exit waiter.
    ///
    /// @param process process to wait for
    /// @param joins stream-pump threads to join after raw process exit
    /// @param watcher callback invoked after the process and stream pumps stop
    public ExitWaiter(ManagedProcess process, Collection<Thread> joins, BiConsumer<Integer, ProcessListener.ExitType> watcher) {
        this.process = process;
        this.joins = List.copyOf(joins);
        this.watcher = watcher;
    }

    /// Waits through launcher interruptions until the raw process and stream pumps have terminated.
    @Override
    public void run() {
        boolean interrupted = false;
        int exitCode;

        while (true) {
            try {
                exitCode = process.getProcess().waitFor();
                break;
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }

        for (Thread thread : joins) {
            while (thread.isAlive()) {
                try {
                    thread.join();
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
        }

        try {
            ProcessListener.ExitType exitType;
            if (process.isLauncherStopRequested() || interrupted) {
                exitType = ProcessListener.ExitType.INTERRUPTED;
            } else {
                exitType = classifyNaturalExit(exitCode);
            }

            EventBus.EVENT_BUS.fireEvent(new ProcessStoppedEvent(this, process));
            watcher.accept(exitCode, exitType);
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /// Classifies a raw process that was not stopped by launcher intent or waiter interruption.
    ///
    /// @param exitCode raw process exit code
    /// @return natural process exit classification
    private ProcessListener.ExitType classifyNaturalExit(int exitCode) {
        List<String> errorLines = process.getLines(Log4jLevel::guessLogLineError);

        // LaunchWrapper catches these logged VM failures and may otherwise exit normally.
        if (exitCode != 0 && StringUtils.containsOne(errorLines,
                "Could not create the Java Virtual Machine.",
                "Error occurred during initialization of VM",
                "A fatal exception has occurred. Program will exit.")) {
            EventBus.EVENT_BUS.fireEvent(new JVMLaunchFailedEvent(this, process));
            return ProcessListener.ExitType.JVM_ERROR;
        } else if (exitCode != 0 || StringUtils.containsOne(errorLines,
                "Crash report saved to", "Could not save crash report to", "This crash report has been saved to:",
                "Unable to launch", "An exception was thrown, the game will display an error screen and halt.")) {
            EventBus.EVENT_BUS.fireEvent(new ProcessExitedAbnormallyEvent(this, process));

            if (exitCode == 137 && OperatingSystem.CURRENT_OS.isLinuxOrBSD()) {
                return ProcessListener.ExitType.SIGKILL;
            } else {
                return ProcessListener.ExitType.APPLICATION_ERROR;
            }
        } else {
            return ProcessListener.ExitType.NORMAL;
        }
    }
}
