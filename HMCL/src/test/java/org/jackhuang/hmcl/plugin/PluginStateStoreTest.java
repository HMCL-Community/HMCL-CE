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
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies bounded and caller-owned plugin runtime-state persistence.
@NotNullByDefault
public final class PluginStateStoreTest {
    /// Treats an oversized state document as empty without retaining stale caller state.
    ///
    /// @param temporaryDirectory isolated launcher-local directory
    /// @throws Exception if the test document cannot be written
    @Test
    public void rejectOversizedStateDocument(@TempDir Path temporaryDirectory) throws Exception {
        Path stateFile = temporaryDirectory.resolve("plugin-states.json");
        byte @Unmodifiable [] oversizedState = new byte[1024 * 1024 + 1];
        Files.write(stateFile, oversizedState);
        Set<String> enabled = new HashSet<>(Set.of("dev.hmclce.test.enabled"));
        Set<String> pendingUninstall = new HashSet<>(Set.of("dev.hmclce.test.pending"));
        PluginStateStore store = new PluginStateStore(stateFile, new PluginMutationLock(temporaryDirectory));

        store.load(enabled, pendingUninstall);

        assertTrue(enabled.isEmpty());
        assertTrue(pendingUninstall.isEmpty());
    }
}
