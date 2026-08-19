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
import org.jackhuang.hmcl.plugin.internal.PluginPackageVersions;
import org.jackhuang.hmcl.util.io.FileUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// Publishes plugin package installs, removals, permission decisions, and state changes through one recovery journal.
///
/// Callers must already hold the launcher-local [PluginMutationLock]. This service owns the durable ordering: write a
/// prepared journal, mutate permission/state documents, move packages, write the committed phase, then clean backups.
@NotNullByDefault
final class PluginPackageMutationService {
    /// Current pending storage-cleanup document schema.
    private static final int CLEANUP_SCHEMA_VERSION = 2;

    /// Legacy ID-only cleanup document schema that cannot safely authorize deletion.
    private static final int LEGACY_CLEANUP_SCHEMA_VERSION = 1;

    /// Maximum accepted pending storage-cleanup document size.
    private static final int MAX_CLEANUP_DOCUMENT_BYTES = 1024 * 1024;

    /// JSON codec used for durable storage-cleanup tombstones.
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /// Directory containing stable, staged, and backup plugin packages.
    private final Path pluginsDirectory;

    /// Installed package repository used to find every same-ID package before replacement.
    private final PluginPackageRepository packageRepository;

    /// Crash-recovery journal shared by installation and removal operations.
    private final PluginBatchTransactionJournal transactionJournal;

    /// Private per-plugin storage root deleted only after package/document commit.
    private final Path pluginStorageDirectory;

    /// Durable exact plugin artifacts whose private storage deletion must be retried.
    private final Path cleanupFile;

    /// Creates a launcher-local package mutation service.
    ///
    /// @param localHome launcher-local home
    /// @param pluginsDirectory installed plugin directory
    /// @param packageRepository installed package repository
    PluginPackageMutationService(
            Path localHome,
            Path pluginsDirectory,
            PluginPackageRepository packageRepository
    ) {
        this.pluginsDirectory = pluginsDirectory.toAbsolutePath().normalize();
        this.packageRepository = packageRepository;
        transactionJournal = new PluginBatchTransactionJournal(localHome, pluginsDirectory);
        pluginStorageDirectory = localHome.resolve("plugin-storage").toAbsolutePath().normalize();
        cleanupFile = localHome.resolve("plugin-cleanup-pending.json").toAbsolutePath().normalize();
    }

    /// Recovers or cleans any interrupted package, permission, and state transaction.
    ///
    /// @return whether no unresolved transaction remains
    boolean recover() {
        boolean recovered = transactionJournal.recover();
        if (recovered) {
            retryPendingStorageCleanup();
        }
        return recovered;
    }

    /// Atomically publishes verified package replacements and their permission/state documents.
    ///
    /// @param artifacts inspected install artifacts indexed in publication order
    /// @param writePermissions writes exact artifact-bound permission decisions
    /// @param writeState writes desired enablement and pending-removal state after package publication
    /// @param reloadAfterRollback reloads in-memory permission state after journal recovery
    /// @throws IOException if staging, publication, document persistence, or recovery fails
    void publishInstallations(
            Map<String, InstallArtifact> artifacts,
            PluginMutationLock.IORunnable writePermissions,
            PluginMutationLock.IORunnable writeState,
            PluginMutationLock.IORunnable reloadAfterRollback
    ) throws IOException {
        requireRecoveredJournal();
        Map<String, Path> preparedPackages = new LinkedHashMap<>();
        @Nullable PluginBatchTransactionJournal.Transaction transaction = null;
        try {
            for (Map.Entry<String, InstallArtifact> entry : artifacts.entrySet()) {
                preparedPackages.put(entry.getKey(), copyInspectedPackage(entry.getValue()));
            }
            transaction = beginInstallation(preparedPackages);
            executePreparedTransaction(
                    transaction,
                    writePermissions,
                    writeState,
                    reloadAfterRollback
            );
        } finally {
            cleanupPreparedPackages(List.copyOf(preparedPackages.values()));
        }
    }

    /// Atomically removes installed packages together with permission and state document changes.
    ///
    /// @param packages direct installed package paths to remove
    /// @param cleanupPluginId plugin ID whose private storage is deleted after commit
    /// @param writeDocuments writes permission and state replacements before package publication
    /// @param reloadAfterRollback reloads in-memory permission state after journal recovery
    /// @throws IOException if journal preparation, document persistence, removal, or recovery fails
    void publishRemoval(
            @Unmodifiable List<Path> packages,
            String cleanupPluginId,
            PluginMutationLock.IORunnable writeDocuments,
            PluginMutationLock.IORunnable reloadAfterRollback
    ) throws IOException {
        requireRecoveredJournal();
        Map<Path, Path> removalBackups = createRemovalBackups(packages);
        PluginBatchTransactionJournal.Transaction transaction = transactionJournal.begin(
                removalBackups,
                List.of(),
                List.of()
        );
        executePreparedTransaction(
                transaction,
                writeDocuments,
                () -> addPendingStorageCleanup(readRemovedArtifactIdentities(
                        removalBackups.values(),
                        cleanupPluginId
                )),
                reloadAfterRollback
        );
        retryPendingStorageCleanup();
    }

    /// Atomically publishes permission and state documents without moving a package.
    ///
    /// @param writeDocuments writes complete permission and state replacements
    /// @param reloadAfterRollback reloads in-memory permission state after journal recovery
    /// @throws IOException if document persistence, commit, or recovery fails
    void publishDocuments(
            PluginMutationLock.IORunnable writeDocuments,
            PluginMutationLock.IORunnable reloadAfterRollback
    ) throws IOException {
        requireRecoveredJournal();
        PluginBatchTransactionJournal.Transaction transaction = transactionJournal.begin(
                Map.of(),
                List.of(),
                List.of()
        );
        executePreparedTransaction(
                transaction,
                writeDocuments,
                () -> {
                },
                reloadAfterRollback
        );
    }

    /// Refuses to start a new mutation until every previous journal is fully recovered or cleaned.
    ///
    /// @throws IOException if an unresolved transaction remains
    private void requireRecoveredJournal() throws IOException {
        if (!recover()) {
            throw new IOException("A previous plugin transaction could not be recovered");
        }
    }

    /// Adds exact removed artifact identities to the durable post-commit storage cleanup set.
    ///
    /// @param artifacts removed artifacts whose shared private storage must be deleted
    /// @throws IOException if the cleanup document is malformed or cannot be replaced
    private void addPendingStorageCleanup(@Unmodifiable Set<PluginArtifactIdentity> artifacts) throws IOException {
        if (artifacts.isEmpty()) {
            return;
        }
        Set<PluginArtifactIdentity> pendingArtifacts = new LinkedHashSet<>(readPendingStorageCleanup());
        pendingArtifacts.addAll(artifacts);
        writePendingStorageCleanup(pendingArtifacts);
    }

    /// Retries every committed private-storage cleanup and retains failures for a later startup.
    private void retryPendingStorageCleanup() {
        final Set<PluginArtifactIdentity> pending;
        try {
            pending = new LinkedHashSet<>(readPendingStorageCleanup());
        } catch (IOException exception) {
            LOG.warning("Cannot read pending plugin storage cleanup tombstones", exception);
            return;
        }
        if (pending.isEmpty()) {
            return;
        }

        Set<PluginArtifactIdentity> remaining = new LinkedHashSet<>();
        for (PluginArtifactIdentity artifact : pending) {
            String pluginId = artifact.getPluginId();
            Path storageDirectory = pluginStorageDirectory.resolve(pluginId).normalize();
            try {
                if (!isStorageCleanupStillOwnedBy(artifact)) {
                    continue;
                }
                if (!Objects.equals(pluginStorageDirectory, storageDirectory.getParent())) {
                    throw new IOException("Plugin storage cleanup path escaped its root: " + pluginId);
                }
                if (Files.isSymbolicLink(storageDirectory)) {
                    throw new IOException("Plugin storage cleanup path is a symbolic link: " + storageDirectory);
                }
                FileUtils.deleteDirectory(storageDirectory);
            } catch (IOException | RuntimeException exception) {
                remaining.add(artifact);
                LOG.warning("Failed to remove committed plugin storage for " + artifact, exception);
            }
        }
        try {
            writePendingStorageCleanup(remaining);
        } catch (IOException exception) {
            LOG.warning("Cannot persist remaining plugin storage cleanup tombstones", exception);
        }
    }

    /// Returns whether no installed package has taken ownership of one plugin's shared storage path.
    ///
    /// The launcher mutation lock prevents a package publication between this check and deletion. Any installed
    /// package, including a byte-identical reinstall, transfers ownership away from the old cleanup tombstone.
    ///
    /// @param pendingArtifact exact removed artifact requesting cleanup
    /// @return whether deletion is still authorized by the pending removal
    /// @throws IOException if current package ownership cannot be established safely
    private boolean isStorageCleanupStillOwnedBy(PluginArtifactIdentity pendingArtifact) throws IOException {
        String pluginId = pendingArtifact.getPluginId();
        for (Path installedPackage : packageRepository.findInstalledPackages(pluginId)) {
            PluginManifest installedManifest = packageRepository.readManifest(installedPackage);
            PluginArtifactIdentity installedArtifact = PluginArtifactIdentity.of(
                    installedManifest,
                    PluginPackageVersions.calculateSha256(installedPackage)
            );
            LOG.info("Discarding stale storage cleanup for " + pendingArtifact
                    + " because storage is owned by installed artifact " + installedArtifact);
            return false;
        }

        Path stablePackage = pluginsDirectory.resolve(pluginId + ".npl").toAbsolutePath().normalize();
        if (!Objects.equals(pluginsDirectory, stablePackage.getParent())) {
            throw new IOException("Plugin package ownership path escaped its root: " + pluginId);
        }
        if (Files.exists(stablePackage, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Cannot verify plugin storage ownership while package is unreadable: "
                    + stablePackage);
        }
        return true;
    }

    /// Reads exact artifact identities from package backups after the removal transaction moved them.
    ///
    /// @param backupPackages committed-removal backup package paths
    /// @param expectedPluginId plugin ID whose shared private storage is being removed
    /// @return immutable exact removed artifact identities
    /// @throws IOException if a backup is invalid, changes while hashing, or belongs to another plugin
    private @Unmodifiable Set<PluginArtifactIdentity> readRemovedArtifactIdentities(
            Iterable<Path> backupPackages,
            String expectedPluginId
    ) throws IOException {
        if (!PluginManifest.isValidId(expectedPluginId)) {
            throw new IOException("Invalid plugin storage cleanup ID: " + expectedPluginId);
        }
        Set<PluginArtifactIdentity> artifacts = new LinkedHashSet<>();
        for (Path backupPackage : backupPackages) {
            String sha256BeforeManifest = PluginPackageVersions.calculateSha256(backupPackage);
            PluginManifest manifest = packageRepository.readManifest(backupPackage);
            if (!expectedPluginId.equals(manifest.getId())) {
                throw new IOException("Plugin removal backup belongs to " + manifest.getId()
                        + " instead of " + expectedPluginId + ": " + backupPackage);
            }
            String sha256AfterManifest = PluginPackageVersions.calculateSha256(backupPackage);
            if (!sha256BeforeManifest.equals(sha256AfterManifest)) {
                throw new IOException("Plugin removal backup changed while its identity was captured: "
                        + backupPackage);
            }
            artifacts.add(PluginArtifactIdentity.of(manifest, sha256AfterManifest));
        }
        return Set.copyOf(artifacts);
    }

    /// Reads and validates the pending private-storage cleanup document, discarding unsafe legacy ID-only records.
    ///
    /// @return immutable pending exact artifact identities
    /// @throws IOException if the document is malformed, unsafe, or too large
    private @Unmodifiable Set<PluginArtifactIdentity> readPendingStorageCleanup() throws IOException {
        if (!Files.exists(cleanupFile, LinkOption.NOFOLLOW_LINKS)) {
            return Set.of();
        }
        if (!Files.isRegularFile(cleanupFile, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Plugin cleanup tombstone is not a regular file: " + cleanupFile);
        }
        if (Files.size(cleanupFile) > MAX_CLEANUP_DOCUMENT_BYTES) {
            throw new IOException("Plugin cleanup tombstone is too large");
        }
        final @Nullable CleanupDocument document;
        try {
            document = GSON.fromJson(
                    Files.readString(cleanupFile, StandardCharsets.UTF_8),
                    CleanupDocument.class
            );
        } catch (RuntimeException exception) {
            throw new IOException("Plugin cleanup tombstone is malformed", exception);
        }
        if (document == null) {
            throw new IOException("Plugin cleanup tombstone has an unsupported schema");
        }

        if (document.schemaVersion == LEGACY_CLEANUP_SCHEMA_VERSION) {
            validateLegacyCleanupDocument(document);
            Files.deleteIfExists(cleanupFile);
            LOG.warning("Discarded legacy ID-only plugin storage cleanup tombstones because artifact ownership "
                    + "cannot be verified safely");
            return Set.of();
        }
        if (document.schemaVersion != CLEANUP_SCHEMA_VERSION || document.artifacts == null) {
            throw new IOException("Plugin cleanup tombstone has an unsupported schema");
        }

        Set<PluginArtifactIdentity> artifacts = new LinkedHashSet<>();
        for (@Nullable CleanupArtifactRecord record : document.artifacts) {
            if (record == null || record.pluginId == null || record.version == null || record.sha256 == null) {
                throw new IOException("Plugin cleanup tombstone contains an incomplete artifact identity");
            }
            final PluginArtifactIdentity artifact;
            try {
                artifact = new PluginArtifactIdentity(record.pluginId, record.version, record.sha256);
            } catch (IllegalArgumentException exception) {
                throw new IOException("Plugin cleanup tombstone contains an invalid artifact identity", exception);
            }
            if (!artifacts.add(artifact)) {
                throw new IOException("Plugin cleanup tombstone contains a duplicate artifact identity");
            }
        }
        return Set.copyOf(artifacts);
    }

    /// Validates a legacy ID-only document before discarding its unverifiable destructive requests.
    ///
    /// @param document parsed legacy document
    /// @throws IOException if the legacy document is malformed
    private static void validateLegacyCleanupDocument(CleanupDocument document) throws IOException {
        if (document.pluginIds == null) {
            throw new IOException("Plugin cleanup tombstone has an unsupported schema");
        }
        Set<String> pluginIds = new LinkedHashSet<>();
        for (@Nullable String pluginId : document.pluginIds) {
            if (pluginId == null || !PluginManifest.isValidId(pluginId) || !pluginIds.add(pluginId)) {
                throw new IOException("Plugin cleanup tombstone contains an invalid plugin ID");
            }
        }
    }

    /// Atomically replaces or removes the pending private-storage cleanup document.
    ///
    /// @param artifacts complete pending exact artifact set
    /// @throws IOException if serialization or replacement fails
    private void writePendingStorageCleanup(Set<PluginArtifactIdentity> artifacts) throws IOException {
        if (artifacts.isEmpty()) {
            Files.deleteIfExists(cleanupFile);
            return;
        }
        CleanupDocument document = new CleanupDocument();
        document.schemaVersion = CLEANUP_SCHEMA_VERSION;
        document.artifacts = artifacts.stream()
                .sorted(Comparator.comparing(PluginArtifactIdentity::getPluginId)
                        .thenComparing(PluginArtifactIdentity::getVersion)
                        .thenComparing(PluginArtifactIdentity::getSha256))
                .map(CleanupArtifactRecord::new)
                .toList();
        String json = GSON.toJson(document);
        if (json.getBytes(StandardCharsets.UTF_8).length > MAX_CLEANUP_DOCUMENT_BYTES) {
            throw new IOException("Plugin cleanup tombstone is too large");
        }
        Files.createDirectories(Objects.requireNonNull(cleanupFile.getParent()));
        Path temporaryFile = cleanupFile.resolveSibling(cleanupFile.getFileName() + ".tmp");
        try {
            Files.writeString(temporaryFile, json, StandardCharsets.UTF_8);
            try {
                Files.move(
                        temporaryFile,
                        cleanupFile,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING
                );
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporaryFile, cleanupFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporaryFile);
        }
    }

    /// Executes one prepared transaction and recovers every package and document on failure.
    ///
    /// @param transaction persisted prepared transaction
    /// @param beforePackages document mutation performed before package moves
    /// @param afterPackages document mutation performed after package moves
    /// @param reloadAfterRollback reloads in-memory document state after recovery
    /// @throws IOException if publication, persistence, commit, or recovery fails
    private void executePreparedTransaction(
            PluginBatchTransactionJournal.Transaction transaction,
            PluginMutationLock.IORunnable beforePackages,
            PluginMutationLock.IORunnable afterPackages,
            PluginMutationLock.IORunnable reloadAfterRollback
    ) throws IOException {
        try {
            beforePackages.run();
            transactionJournal.publishPrepared(transaction);
            afterPackages.run();
            transactionJournal.markCommitted(transaction);
            try {
                transactionJournal.finishCommitted(transaction);
            } catch (IOException cleanupFailure) {
                LOG.warning("Committed plugin transaction cleanup will be retried at startup", cleanupFailure);
            }
        } catch (IOException | RuntimeException | Error exception) {
            if (!transactionJournal.recover()) {
                exception.addSuppressed(new IOException("Plugin transaction rollback is incomplete"));
            }
            try {
                reloadAfterRollback.run();
            } catch (IOException reloadFailure) {
                exception.addSuppressed(reloadFailure);
            }
            throw exception;
        }
    }

    /// Copies and revalidates one inspected source into a hidden same-filesystem staging package.
    ///
    /// @param artifact inspected source artifact
    /// @return hidden verified staging package
    /// @throws IOException if copying, hashing, or manifest verification fails
    private Path copyInspectedPackage(InstallArtifact artifact) throws IOException {
        Path preparedPackage = pluginsDirectory.resolve(
                "." + artifact.manifest.getId() + "-" + UUID.randomUUID() + ".installing"
        );
        try {
            Files.copy(artifact.sourcePackage, preparedPackage);
            verifyPackageHash(preparedPackage, artifact.sha256);
            PluginManifest copiedManifest = packageRepository.readManifest(preparedPackage);
            if (!artifact.manifest.equals(copiedManifest)) {
                throw new IOException("Plugin manifest changed after inspection: " + artifact.sourcePackage);
            }
            return preparedPackage;
        } catch (IOException | RuntimeException | Error exception) {
            try {
                Files.deleteIfExists(preparedPackage);
            } catch (IOException cleanupFailure) {
                exception.addSuppressed(cleanupFailure);
            }
            throw exception;
        }
    }

    /// Begins a replacement transaction for every staged plugin ID.
    ///
    /// @param preparedPackages hidden verified packages indexed by plugin ID
    /// @return persisted prepared transaction
    /// @throws IOException if existing packages, targets, or document snapshots are invalid
    private PluginBatchTransactionJournal.Transaction beginInstallation(
            Map<String, Path> preparedPackages
    ) throws IOException {
        Map<Path, Path> backups = new LinkedHashMap<>();
        List<Path> targetPaths = new ArrayList<>();
        for (String pluginId : preparedPackages.keySet()) {
            for (Path existingPackage : packageRepository.findInstalledPackages(pluginId)) {
                backups.put(existingPackage, createBackupPath(pluginId));
            }
            Path target = pluginsDirectory.resolve(pluginId + ".npl").toAbsolutePath().normalize();
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) && !backups.containsKey(target)) {
                throw new IOException("Plugin batch target is occupied by an unrelated path: " + target);
            }
            targetPaths.add(target);
        }
        return transactionJournal.begin(
                backups,
                targetPaths,
                List.copyOf(preparedPackages.values())
        );
    }

    /// Creates backup mappings for direct installed packages participating in a removal transaction.
    ///
    /// @param packages requested installed package paths
    /// @return immutable unique original-to-backup mappings
    /// @throws IOException if a path escapes the plugin directory or is not a regular file
    private @Unmodifiable Map<Path, Path> createRemovalBackups(
            @Unmodifiable List<Path> packages
    ) throws IOException {
        Set<Path> uniquePackages = new LinkedHashSet<>();
        for (Path packageFile : packages) {
            Path normalized = packageFile.toAbsolutePath().normalize();
            if (!Objects.equals(pluginsDirectory, normalized.getParent())) {
                throw new IOException("Plugin removal path escapes the plugin directory: " + packageFile);
            }
            if (!Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            if (!Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Plugin removal path is not a regular file: " + normalized);
            }
            uniquePackages.add(normalized);
        }

        Map<Path, Path> backups = new LinkedHashMap<>();
        for (Path packageFile : uniquePackages) {
            backups.put(packageFile, createBackupPath("remove"));
        }
        return Map.copyOf(backups);
    }

    /// Creates one hidden unique backup path inside the installed plugin directory.
    ///
    /// @param label diagnostic-safe backup label
    /// @return unused backup path
    private Path createBackupPath(String label) {
        return pluginsDirectory.resolve("." + label + "-" + UUID.randomUUID() + ".backup");
    }

    /// Verifies a package against an optional inspection-time SHA-256 digest.
    ///
    /// @param packageFile package to verify
    /// @param expectedSha256 expected lower-case digest, or `null` when no inspection preceded preparation
    /// @throws IOException if hashing fails or the package bytes changed
    static void verifyPackageHash(Path packageFile, @Nullable String expectedSha256) throws IOException {
        if (expectedSha256 == null) {
            return;
        }
        String actualSha256 = PluginPackageVersions.calculateSha256(packageFile);
        if (!expectedSha256.equals(actualSha256)) {
            throw new IOException("Plugin package changed after inspection: " + packageFile);
        }
    }

    /// Best-effort removes hidden staging files after success or recovery.
    ///
    /// A committed journal retains ownership of any staging file that cannot yet be removed and retries cleanup on
    /// the next startup.
    ///
    /// @param preparedPackages hidden staging paths
    static void cleanupPreparedPackages(@Unmodifiable List<Path> preparedPackages) {
        for (Path preparedPackage : preparedPackages) {
            try {
                Files.deleteIfExists(preparedPackage);
            } catch (IOException exception) {
                LOG.warning("Failed to remove plugin staging file: " + preparedPackage, exception);
            }
        }
    }

    /// Immutable inspected package values required for same-filesystem staging and revalidation.
    @NotNullByDefault
    static final class InstallArtifact {
        /// Inspected source package path.
        private final Path sourcePackage;

        /// Manifest parsed from the inspected source bytes.
        private final PluginManifest manifest;

        /// Exact complete source package SHA-256.
        private final String sha256;

        /// Creates one immutable install artifact.
        ///
        /// @param sourcePackage inspected source package
        /// @param manifest inspected manifest
        /// @param sha256 inspected package digest
        InstallArtifact(Path sourcePackage, PluginManifest manifest, String sha256) {
            this.sourcePackage = sourcePackage.toAbsolutePath().normalize();
            this.manifest = manifest;
            this.sha256 = sha256;
        }
    }

    /// Serialized root of `plugin-cleanup-pending.json`.
    @NotNullByDefault
    private static final class CleanupDocument {
        /// Cleanup document schema version.
        private int schemaVersion;

        /// Exact pending artifacts, or `null` in malformed and legacy documents.
        private @Nullable List<@Nullable CleanupArtifactRecord> artifacts;

        /// Legacy pending plugin IDs, or `null` outside schema 1 documents.
        private @Nullable List<@Nullable String> pluginIds;

        /// Creates an empty document for Gson and serialization.
        private CleanupDocument() {
        }
    }

    /// Serialized exact artifact identity inside `plugin-cleanup-pending.json`.
    @NotNullByDefault
    private static final class CleanupArtifactRecord {
        /// Plugin ID, or `null` in malformed documents.
        private @Nullable String pluginId;

        /// Package version, or `null` in malformed documents.
        private @Nullable String version;

        /// Complete package SHA-256, or `null` in malformed documents.
        private @Nullable String sha256;

        /// Creates an empty record for Gson.
        private CleanupArtifactRecord() {
        }

        /// Creates a complete serialized record from a validated identity.
        ///
        /// @param artifact exact validated artifact identity
        private CleanupArtifactRecord(PluginArtifactIdentity artifact) {
            pluginId = artifact.getPluginId();
            version = artifact.getVersion();
            sha256 = artifact.getSha256();
        }
    }
}
