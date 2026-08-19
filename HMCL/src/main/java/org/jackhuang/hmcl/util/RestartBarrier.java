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

import org.jetbrains.annotations.NotNullByDefault;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/// Delays a restarted HMCL process until its parent has released process-lifetime resources.
@NotNullByDefault
public final class RestartBarrier {
    /// Internal launcher argument carrying the parent process identifier.
    public static final String PARENT_PROCESS_ARGUMENT = "--hmcl-restart-parent";

    /// Waits for restart parents declared in launcher arguments and removes the internal arguments.
    ///
    /// This method runs before Mixin cache discovery so a normal launcher restart never overlaps the
    /// old process's plugin JAR handles. The wait is intentionally unbounded: proceeding while the
    /// parent is still alive would reintroduce the Windows file-lock race this barrier prevents.
    ///
    /// @param args raw launcher arguments
    /// @return arguments with restart barrier markers removed
    public static String[] awaitParentsAndStrip(String[] args) {
        List<String> remaining = new ArrayList<>(args.length);
        for (int index = 0; index < args.length; index++) {
            String argument = args[index];
            if (!PARENT_PROCESS_ARGUMENT.equals(argument)) {
                remaining.add(argument);
                continue;
            }
            if (index + 1 >= args.length) {
                report("Ignoring restart parent argument without a process ID");
                continue;
            }
            waitForParent(args[++index]);
        }
        return remaining.toArray(String[]::new);
    }

    /// Waits for one parent process when the identifier is valid and still alive.
    ///
    /// @param processIdText decimal process identifier
    private static void waitForParent(String processIdText) {
        final long processId;
        try {
            processId = Long.parseLong(processIdText);
        } catch (NumberFormatException exception) {
            report("Ignoring invalid restart parent process ID: " + processIdText);
            return;
        }
        if (processId == ProcessHandle.current().pid()) {
            report("Ignoring restart parent process ID that refers to the current process");
            return;
        }

        Optional<ProcessHandle> parent = ProcessHandle.of(processId);
        if (parent.isEmpty() || !parent.get().isAlive()) {
            return;
        }
        report("Waiting for restart parent " + processId + " to exit");
        parent.get().onExit().join();
    }

    /// Prints a diagnostic before the launcher logger is initialized.
    ///
    /// @param message diagnostic text
    private static void report(String message) {
        System.err.println("[HMCL Restart] " + message);
    }

    /// Prevents construction of the restart barrier utility.
    private RestartBarrier() {
    }
}
