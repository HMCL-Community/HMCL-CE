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

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Set;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// Loads and atomically persists desired plugin enablement and pending-removal state.
@NotNullByDefault
final class PluginStateStore {
    /// Maximum accepted size of the private state document.
    private static final int MAX_STATE_BYTES = 1024 * 1024;

    /// JSON codec used for the private state document.
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /// Atomic state document path.
    private final Path stateFile;

    /// Shared package, state, and permission mutation lock.
    private final PluginMutationLock mutationLock;

    /// Creates a launcher-local state store.
    ///
    /// @param stateFile private state document path
    /// @param mutationLock shared launcher-local mutation lock
    PluginStateStore(Path stateFile, PluginMutationLock mutationLock) {
        this.stateFile = stateFile.toAbsolutePath().normalize();
        this.mutationLock = mutationLock;
    }

    /// Loads valid IDs into caller-owned mutable sets while holding the shared mutation lock.
    ///
    /// @param enabled destination for desired-enabled plugin IDs
    /// @param pendingUninstall destination for pending-removal plugin IDs
    void load(Set<String> enabled, Set<String> pendingUninstall) {
        try {
            mutationLock.run(() -> loadLocked(enabled, pendingUninstall));
        } catch (IOException exception) {
            LOG.warning("Failed to load plugin states", exception);
        }
    }

    /// Persists complete state snapshots while holding the shared mutation lock.
    ///
    /// @param enabled desired-enabled plugin IDs
    /// @param pendingUninstall pending-removal plugin IDs
    void save(Set<String> enabled, Set<String> pendingUninstall) {
        try {
            saveStrict(enabled, pendingUninstall);
        } catch (IOException exception) {
            LOG.warning("Failed to save plugin states", exception);
        }
    }

    /// Persists complete state snapshots and reports failure to a surrounding transaction.
    ///
    /// @param enabled desired-enabled plugin IDs
    /// @param pendingUninstall pending-removal plugin IDs
    /// @throws IOException if serialization or replacement fails
    void saveStrict(Set<String> enabled, Set<String> pendingUninstall) throws IOException {
        PluginPersistedStates states = new PluginPersistedStates();
        states.enabled = enabled.stream().sorted().toList();
        states.pendingUninstall = pendingUninstall.stream().sorted().toList();
        mutationLock.run(() -> writeLocked(states));
    }

    /// Reads one state document after the shared lock has been acquired.
    ///
    /// @param enabled destination for desired-enabled plugin IDs
    /// @param pendingUninstall destination for pending-removal plugin IDs
    /// @throws IOException if the state file cannot be read
    private void loadLocked(Set<String> enabled, Set<String> pendingUninstall) throws IOException {
        enabled.clear();
        pendingUninstall.clear();
        if (!Files.isRegularFile(stateFile)) {
            return;
        }
        try {
            String stateJson;
            try (InputStream input = Files.newInputStream(stateFile)) {
                byte @Unmodifiable [] stateBytes = input.readNBytes(MAX_STATE_BYTES + 1);
                if (stateBytes.length > MAX_STATE_BYTES) {
                    throw new IOException("Plugin state document exceeds " + MAX_STATE_BYTES + " bytes");
                }
                stateJson = new String(stateBytes, StandardCharsets.UTF_8);
            }
            @Nullable PluginPersistedStates states = GSON.fromJson(
                    stateJson,
                    PluginPersistedStates.class
            );
            if (states != null) {
                copyValidIds(states.enabled, enabled);
                copyValidIds(states.pendingUninstall, pendingUninstall);
            }
        } catch (RuntimeException exception) {
            LOG.warning("Failed to parse plugin states", exception);
        }
    }

    /// Copies non-null valid plugin IDs from deserialized input.
    ///
    /// @param source deserialized values or `null`
    /// @param target destination set
    private static void copyValidIds(@Nullable List<@Nullable String> source, Set<String> target) {
        if (source == null) {
            return;
        }
        for (@Nullable String value : source) {
            if (value != null && PluginManifest.isValidId(value)) {
                target.add(value);
            }
        }
    }

    /// Writes one captured state document through an atomic replacement.
    ///
    /// @param states complete state snapshot
    /// @throws IOException if serialization or replacement fails
    private void writeLocked(PluginPersistedStates states) throws IOException {
        Path temporaryFile = stateFile.resolveSibling(stateFile.getFileName() + ".tmp");
        try {
            Files.createDirectories(stateFile.getParent());
            Files.writeString(temporaryFile, GSON.toJson(states), StandardCharsets.UTF_8);
            try {
                Files.move(
                        temporaryFile,
                        stateFile,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING
                );
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporaryFile, stateFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporaryFile);
        }
    }
}
