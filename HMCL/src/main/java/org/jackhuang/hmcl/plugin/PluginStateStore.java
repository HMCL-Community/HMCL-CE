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
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// Loads and atomically persists desired plugin enablement, pending-removal, and recovery quarantine state.
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
    /// @param quarantined destination for recovery-quarantined plugin IDs
    /// @return persisted secret-free quarantine report, or empty when absent or unreadable
    Optional<PluginQuarantineReport> load(
            Set<String> enabled,
            Set<String> pendingUninstall,
            Set<String> quarantined
    ) {
        try {
            return loadStrict(enabled, pendingUninstall, quarantined);
        } catch (IOException exception) {
            LOG.warning("Failed to load plugin states", exception);
            enabled.clear();
            pendingUninstall.clear();
            quarantined.clear();
            return Optional.empty();
        }
    }

    /// Loads valid IDs into caller-owned mutable sets and propagates read failures to strict startup transactions.
    ///
    /// @param enabled destination for desired-enabled plugin IDs
    /// @param pendingUninstall destination for pending-removal plugin IDs
    /// @param quarantined destination for recovery-quarantined plugin IDs
    /// @return persisted secret-free quarantine report, or empty when absent
    /// @throws IOException if the state document cannot be read
    Optional<PluginQuarantineReport> loadStrict(
            Set<String> enabled,
            Set<String> pendingUninstall,
            Set<String> quarantined
    ) throws IOException {
        return mutationLock.call(() -> {
            Set<String> loadedEnabled = new HashSet<>();
            Set<String> loadedPendingUninstall = new HashSet<>();
            Set<String> loadedQuarantined = new HashSet<>();
            Optional<PluginQuarantineReport> loadedReport = loadLocked(
                    loadedEnabled,
                    loadedPendingUninstall,
                    loadedQuarantined
            );
            enabled.clear();
            enabled.addAll(loadedEnabled);
            pendingUninstall.clear();
            pendingUninstall.addAll(loadedPendingUninstall);
            quarantined.clear();
            quarantined.addAll(loadedQuarantined);
            return loadedReport;
        });
    }

    /// Persists complete state snapshots while holding the shared mutation lock.
    ///
    /// @param enabled desired-enabled plugin IDs
    /// @param pendingUninstall pending-removal plugin IDs
    /// @param quarantined recovery-quarantined plugin IDs
    /// @param quarantineReport secret-free recovery report, or `null` before recovery
    void save(
            Set<String> enabled,
            Set<String> pendingUninstall,
            Set<String> quarantined,
            @Nullable PluginQuarantineReport quarantineReport
    ) {
        try {
            saveStrict(enabled, pendingUninstall, quarantined, quarantineReport);
        } catch (IOException exception) {
            LOG.warning("Failed to save plugin states", exception);
        }
    }

    /// Persists complete state snapshots and reports failure to a surrounding transaction.
    ///
    /// @param enabled desired-enabled plugin IDs
    /// @param pendingUninstall pending-removal plugin IDs
    /// @param quarantined recovery-quarantined plugin IDs
    /// @param quarantineReport secret-free recovery report, or `null` before recovery
    /// @throws IOException if serialization or replacement fails
    void saveStrict(
            Set<String> enabled,
            Set<String> pendingUninstall,
            Set<String> quarantined,
            @Nullable PluginQuarantineReport quarantineReport
    ) throws IOException {
        PluginPersistedStates states = new PluginPersistedStates();
        states.enabled = enabled.stream().sorted().toList();
        states.pendingUninstall = pendingUninstall.stream().sorted().toList();
        states.quarantined = quarantined.stream().sorted().toList();
        states.quarantineReport = quarantineReport;
        mutationLock.run(() -> writeLocked(states));
    }

    /// Reads one state document after the shared lock has been acquired.
    ///
    /// @param enabled destination for desired-enabled plugin IDs
    /// @param pendingUninstall destination for pending-removal plugin IDs
    /// @param quarantined destination for recovery-quarantined plugin IDs
    /// @return persisted secret-free quarantine report, or empty when absent
    /// @throws IOException if the state file cannot be read
    private Optional<PluginQuarantineReport> loadLocked(
            Set<String> enabled,
            Set<String> pendingUninstall,
            Set<String> quarantined
    ) throws IOException {
        enabled.clear();
        pendingUninstall.clear();
        quarantined.clear();
        if (!Files.isRegularFile(stateFile)) {
            return Optional.empty();
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
                copyValidIds(states.quarantined, quarantined);
                return Optional.ofNullable(states.quarantineReport);
            }
            return Optional.empty();
        } catch (RuntimeException exception) {
            enabled.clear();
            pendingUninstall.clear();
            quarantined.clear();
            throw new IOException("Failed to parse plugin states", exception);
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
        Path parentDirectory = Objects.requireNonNull(stateFile.getParent());
        try {
            Files.createDirectories(parentDirectory);
            byte @Unmodifiable [] stateBytes = GSON.toJson(states).getBytes(StandardCharsets.UTF_8);
            try (FileChannel channel = FileChannel.open(
                    temporaryFile,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS
            )) {
                ByteBuffer buffer = ByteBuffer.wrap(stateBytes);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            Files.move(
                    temporaryFile,
                    stateFile,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
            );
            try (FileChannel channel = FileChannel.open(
                    stateFile,
                    StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS
            )) {
                channel.force(true);
            }
            forceParentDirectoryBestEffort(parentDirectory);
        } finally {
            Files.deleteIfExists(temporaryFile);
        }
    }

    /// Forces replacement directory metadata when the active file-system provider supports directory channels.
    ///
    /// @param directory state-document parent directory
    private static void forceParentDirectoryBestEffort(Path directory) {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException | UnsupportedOperationException ignored) {
            // Windows and some custom providers do not expose directories as forceable file channels.
        }
    }
}
