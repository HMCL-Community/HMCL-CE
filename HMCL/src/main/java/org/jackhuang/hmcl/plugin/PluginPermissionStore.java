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
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// Persists user-approved plugin capabilities under package-artifact identities.
@NotNullByDefault
final class PluginPermissionStore {
    /// Current private permission document schema.
    private static final int SCHEMA_VERSION = 1;

    /// Maximum permission document size accepted from disk.
    private static final int MAX_DOCUMENT_BYTES = 1024 * 1024;

    /// JSON codec used for the private permission document.
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /// Atomic permission document path.
    private final Path permissionFile;

    /// Shared package, state, and permission mutation lock.
    private final PluginMutationLock mutationLock;

    /// Effective stored records indexed by exact package artifact.
    private Map<Artifact, @Unmodifiable Set<PluginPermission>> grants = new LinkedHashMap<>();

    /// Creates and loads a permission store.
    ///
    /// Malformed data is ignored fail-closed so no capability is granted from an invalid record.
    ///
    /// @param permissionFile private permission document path
    PluginPermissionStore(Path permissionFile) {
        this(permissionFile, new PluginMutationLock(Objects.requireNonNull(permissionFile.getParent())));
    }

    /// Creates and loads a permission store using the manager's shared mutation lock.
    ///
    /// @param permissionFile private permission document path
    /// @param mutationLock shared launcher-local mutation lock
    PluginPermissionStore(Path permissionFile, PluginMutationLock mutationLock) {
        this.permissionFile = permissionFile.toAbsolutePath().normalize();
        this.mutationLock = mutationLock;
        try {
            mutationLock.run(this::load);
        } catch (IOException exception) {
            grants = new LinkedHashMap<>();
            LOG.warning("Failed to acquire the plugin mutation lock while loading permissions; "
                    + "all plugin capabilities are denied", exception);
        }
    }

    /// Returns the immutable grants stored for an exact plugin artifact.
    ///
    /// @param artifact exact package identity
    /// @return immutable granted permission set
    @Unmodifiable Set<PluginPermission> getGrantedPermissions(Artifact artifact) {
        return readSnapshotFailClosed().getOrDefault(artifact, Set.of());
    }

    /// Returns whether an explicit decision record exists for an exact plugin artifact.
    ///
    /// An empty record is significant because it represents an explicit denial of every requested capability.
    ///
    /// @param artifact exact package identity
    /// @return whether the artifact has a stored decision
    boolean containsArtifact(Artifact artifact) {
        return readSnapshotFailClosed().containsKey(artifact);
    }

    /// Reloads and snapshots permission decisions while holding the cross-process mutation lock.
    ///
    /// Every capability check reads the current document so a revocation written by another launcher process takes
    /// effect before the next protected API call. Lock or read failures deny every permission.
    ///
    /// @return immutable current permission snapshot, or an empty snapshot on failure
    private @Unmodifiable Map<Artifact, @Unmodifiable Set<PluginPermission>> readSnapshotFailClosed() {
        try {
            return mutationLock.call(() -> {
                synchronized (this) {
                    load();
                    return Map.copyOf(grants);
                }
            });
        } catch (IOException exception) {
            synchronized (this) {
                grants = new LinkedHashMap<>();
            }
            LOG.warning("Failed to refresh plugin permission decisions; all plugin capabilities are denied", exception);
            return Map.of();
        }
    }

    /// Reloads the complete permission document under the shared mutation lock.
    ///
    /// @throws IOException if lock acquisition fails
    void reload() throws IOException {
        mutationLock.run(() -> {
            synchronized (this) {
                load();
            }
        });
    }

    /// Captures an immutable state snapshot for an installation transaction.
    ///
    /// @return immutable permission state snapshot
    /// @throws IOException if lock acquisition fails
    Snapshot snapshot() throws IOException {
        return mutationLock.call(() -> {
            synchronized (this) {
                load();
                return new Snapshot(Map.copyOf(grants));
            }
        });
    }

    /// Atomically stores the granted capabilities for one exact package artifact.
    ///
    /// @param artifact exact package identity
    /// @param permissions immutable normalized grant set
    /// @throws IOException if the replacement document cannot be persisted
    void setGrantedPermissions(
            Artifact artifact,
            @Unmodifiable Set<PluginPermission> permissions
    ) throws IOException {
        setGrantedPermissions(Map.of(artifact, permissions));
    }

    /// Atomically stores permission decisions for multiple exact artifacts in one document replacement.
    ///
    /// @param decisions normalized immutable decisions indexed by exact artifact
    /// @throws IOException if the replacement document cannot be persisted
    void setGrantedPermissions(
            @Unmodifiable Map<Artifact, @Unmodifiable Set<PluginPermission>> decisions
    ) throws IOException {
        mutationLock.run(() -> {
            synchronized (this) {
                load();
                Map<Artifact, @Unmodifiable Set<PluginPermission>> replacement = new LinkedHashMap<>(grants);
                decisions.forEach((artifact, permissions) ->
                        replacement.put(artifact, immutablePermissions(permissions))
                );
                replace(replacement);
            }
        });
    }

    /// Atomically removes every permission decision belonging to one plugin ID.
    ///
    /// @param pluginId plugin ID to remove
    /// @throws IOException if the replacement document cannot be persisted
    void removePlugin(String pluginId) throws IOException {
        mutationLock.run(() -> {
            synchronized (this) {
                load();
                Map<Artifact, @Unmodifiable Set<PluginPermission>> replacement = new LinkedHashMap<>(grants);
                boolean changed = replacement.keySet().removeIf(artifact -> artifact.pluginId.equals(pluginId));
                if (changed) {
                    replace(replacement);
                }
            }
        });
    }

    /// Atomically removes records that no longer correspond to installed or currently loaded artifacts.
    ///
    /// @param retainedArtifacts artifacts that may continue using their stored decisions
    /// @throws IOException if stale records exist and the replacement document cannot be persisted
    void retainArtifacts(Set<Artifact> retainedArtifacts) throws IOException {
        mutationLock.run(() -> {
            synchronized (this) {
                load();
                Map<Artifact, @Unmodifiable Set<PluginPermission>> replacement = new LinkedHashMap<>(grants);
                boolean changed = replacement.keySet().removeIf(artifact -> !retainedArtifacts.contains(artifact));
                if (changed) {
                    replace(replacement);
                }
            }
        });
    }

    /// Restores a previously captured transaction snapshot through an atomic replacement.
    ///
    /// @param snapshot state captured before the failed transaction
    /// @throws IOException if the previous state cannot be persisted
    void restore(Snapshot snapshot) throws IOException {
        mutationLock.run(() -> {
            synchronized (this) {
                replace(snapshot.grants);
            }
        });
    }

    /// Loads validated records from disk and ignores malformed records fail-closed.
    private void load() {
        grants = new LinkedHashMap<>();
        if (!Files.isRegularFile(permissionFile)) {
            return;
        }
        try {
            if (Files.size(permissionFile) > MAX_DOCUMENT_BYTES) {
                throw new IOException("Plugin permission document is too large");
            }
            @Nullable PermissionDocument document = GSON.fromJson(
                    Files.readString(permissionFile, StandardCharsets.UTF_8),
                    PermissionDocument.class
            );
            if (document == null || document.schemaVersion != SCHEMA_VERSION || document.grants == null) {
                throw new IOException("Plugin permission document has an unsupported or invalid schema");
            }

            Map<Artifact, @Unmodifiable Set<PluginPermission>> loaded = new LinkedHashMap<>();
            for (Map.Entry<@Nullable String, @Nullable List<@Nullable PermissionRecord>> pluginEntry
                    : document.grants.entrySet()) {
                @Nullable String pluginId = pluginEntry.getKey();
                @Nullable List<@Nullable PermissionRecord> records = pluginEntry.getValue();
                if (pluginId == null || !PluginManifest.isValidId(pluginId) || records == null) {
                    continue;
                }
                for (@Nullable PermissionRecord record : records) {
                    loadRecord(loaded, pluginId, record);
                }
            }
            grants = loaded;
        } catch (IOException | RuntimeException exception) {
            LOG.warning("Failed to load plugin permission decisions; all plugin capabilities are denied", exception);
        }
    }

    /// Adds one structurally valid deserialized record to the in-memory state.
    ///
    /// @param loaded mutable validated destination
    /// @param pluginId validated plugin ID
    /// @param record deserialized record or `null`
    private static void loadRecord(
            Map<Artifact, @Unmodifiable Set<PluginPermission>> loaded,
            String pluginId,
            @Nullable PermissionRecord record
    ) {
        if (record == null
                || record.version == null
                || record.version.isBlank()
                || record.sha256 == null
                || !isSha256(record.sha256)
                || record.permissions == null
                || record.permissions.stream().anyMatch(Objects::isNull)) {
            return;
        }
        EnumSet<PluginPermission> permissions = EnumSet.noneOf(PluginPermission.class);
        for (@Nullable PluginPermission permission : record.permissions) {
            permissions.add(Objects.requireNonNull(permission));
        }
        Artifact artifact = new Artifact(pluginId, record.version, record.sha256);
        loaded.putIfAbsent(artifact, immutablePermissions(permissions));
    }

    /// Persists and publishes one complete replacement state.
    ///
    /// @param replacement complete replacement records
    /// @throws IOException if serialization or atomic replacement fails
    private void replace(Map<Artifact, @Unmodifiable Set<PluginPermission>> replacement) throws IOException {
        Map<Artifact, @Unmodifiable Set<PluginPermission>> immutableReplacement = new LinkedHashMap<>();
        replacement.forEach((artifact, permissions) ->
                immutableReplacement.put(artifact, immutablePermissions(permissions))
        );
        write(immutableReplacement);
        grants = immutableReplacement;
    }

    /// Writes one complete state through an atomic file replacement when supported.
    ///
    /// @param state complete permission state
    /// @throws IOException if serialization or replacement fails
    private void write(Map<Artifact, @Unmodifiable Set<PluginPermission>> state) throws IOException {
        PermissionDocument document = new PermissionDocument();
        document.schemaVersion = SCHEMA_VERSION;
        document.grants = new LinkedHashMap<>();

        List<Map.Entry<Artifact, @Unmodifiable Set<PluginPermission>>> entries = new ArrayList<>(state.entrySet());
        entries.sort(Comparator
                .comparing((Map.Entry<Artifact, @Unmodifiable Set<PluginPermission>> entry) ->
                        entry.getKey().pluginId)
                .thenComparing(entry -> entry.getKey().version)
                .thenComparing(entry -> entry.getKey().sha256));
        for (Map.Entry<Artifact, @Unmodifiable Set<PluginPermission>> entry : entries) {
            Artifact artifact = entry.getKey();
            PermissionRecord record = new PermissionRecord();
            record.version = artifact.version;
            record.sha256 = artifact.sha256;
            record.permissions = entry.getValue().stream().sorted().toList();
            document.grants.computeIfAbsent(artifact.pluginId, ignored -> new ArrayList<>()).add(record);
        }

        String json = GSON.toJson(document);
        if (json.getBytes(StandardCharsets.UTF_8).length > MAX_DOCUMENT_BYTES) {
            throw new IOException("Plugin permission document is too large");
        }
        Files.createDirectories(Objects.requireNonNull(permissionFile.getParent()));
        Path temporaryFile = permissionFile.resolveSibling(permissionFile.getFileName() + ".tmp");
        try {
            Files.writeString(temporaryFile, json, StandardCharsets.UTF_8);
            try {
                Files.move(
                        temporaryFile,
                        permissionFile,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING
                );
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporaryFile, permissionFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporaryFile);
        }
    }

    /// Returns an immutable enum set copy.
    ///
    /// @param permissions source permissions
    /// @return immutable permission set
    private static @Unmodifiable Set<PluginPermission> immutablePermissions(Set<PluginPermission> permissions) {
        if (permissions.isEmpty()) {
            return Set.of();
        }
        return Collections.unmodifiableSet(EnumSet.copyOf(permissions));
    }

    /// Returns whether a string is a lower- or upper-case SHA-256 hexadecimal digest.
    ///
    /// @param value candidate digest
    /// @return whether the digest is structurally valid
    private static boolean isSha256(String value) {
        if (value.length() != 64) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (!(character >= '0' && character <= '9')
                    && !(character >= 'a' && character <= 'f')
                    && !(character >= 'A' && character <= 'F')) {
                return false;
            }
        }
        return true;
    }

    /// Exact identity of one plugin package artifact.
    @NotNullByDefault
    static final class Artifact {
        /// Validated plugin ID.
        private final String pluginId;

        /// Manifest version belonging to the artifact.
        private final String version;

        /// SHA-256 digest of the complete `.npl` bytes.
        private final String sha256;

        /// Creates an exact package identity.
        ///
        /// @param pluginId validated plugin ID
        /// @param version validated manifest version
        /// @param sha256 package SHA-256 digest
        Artifact(String pluginId, String version, String sha256) {
            this.pluginId = pluginId;
            this.version = version;
            this.sha256 = sha256.toLowerCase(java.util.Locale.ROOT);
        }

        /// Returns the plugin ID.
        ///
        /// @return plugin ID
        String getPluginId() {
            return pluginId;
        }

        /// Returns the manifest version.
        ///
        /// @return plugin version
        String getVersion() {
            return version;
        }

        /// Returns the package SHA-256 digest.
        ///
        /// @return package digest
        String getSha256() {
            return sha256;
        }

        /// Compares exact package identity fields.
        ///
        /// @param other comparison value
        /// @return whether both identities describe the same package artifact
        @Override
        public boolean equals(@Nullable Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Artifact artifact)) {
                return false;
            }
            return pluginId.equals(artifact.pluginId)
                    && version.equals(artifact.version)
                    && sha256.equals(artifact.sha256);
        }

        /// Returns a hash derived from every exact package identity field.
        ///
        /// @return artifact hash code
        @Override
        public int hashCode() {
            return Objects.hash(pluginId, version, sha256);
        }
    }

    /// Immutable permission-state snapshot used to roll back installation failures.
    @NotNullByDefault
    static final class Snapshot {
        /// Complete immutable permission records captured before a transaction.
        private final @Unmodifiable Map<Artifact, @Unmodifiable Set<PluginPermission>> grants;

        /// Creates an immutable permission snapshot.
        ///
        /// @param grants complete immutable permission records
        private Snapshot(@Unmodifiable Map<Artifact, @Unmodifiable Set<PluginPermission>> grants) {
            this.grants = grants;
        }
    }

    /// Serialized root of `plugin-permissions.json`.
    @NotNullByDefault
    private static final class PermissionDocument {
        /// Document schema version.
        private int schemaVersion;

        /// Artifact records grouped by plugin ID, or `null` in malformed documents.
        private @Nullable Map<@Nullable String, @Nullable List<@Nullable PermissionRecord>> grants;

        /// Creates an empty document for Gson and serialization.
        private PermissionDocument() {
        }
    }

    /// Serialized decision for one plugin version and package digest.
    @NotNullByDefault
    private static final class PermissionRecord {
        /// Manifest version, or `null` in malformed documents.
        private @Nullable String version;

        /// Full package SHA-256, or `null` in malformed documents.
        private @Nullable String sha256;

        /// User-granted capabilities, or `null` in malformed documents.
        private @Nullable List<@Nullable PluginPermission> permissions;

        /// Creates an empty record for Gson and serialization.
        private PermissionRecord() {
        }
    }
}
