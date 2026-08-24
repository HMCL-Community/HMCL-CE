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
import org.jackhuang.hmcl.plugin.trust.PluginCertificationReceiptStore;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;
import static org.jackhuang.hmcl.plugin.PluginRecoveryFileOperations.matchesDigest;
import static org.jackhuang.hmcl.plugin.PluginRecoveryFileOperations.move;
import static org.jackhuang.hmcl.plugin.PluginRecoveryFileOperations.pathExists;
import static org.jackhuang.hmcl.plugin.PluginRecoveryFileOperations.requireDigest;
import static org.jackhuang.hmcl.plugin.PluginRecoveryFileOperations.restoreVerifiedIfUnoccupied;

/// Persists and recovers crash-consistent multi-package plugin publication transactions.
@NotNullByDefault
final class PluginBatchTransactionJournal {
    /// Legacy package-digest-bound journal schema without a persistent recovery nonce.
    private static final int LEGACY_SCHEMA_VERSION = 2;

    /// Recovery journal schema before certification receipts joined package publication.
    private static final int PRE_RECEIPT_SCHEMA_VERSION = 3;

    /// Recovery schema before runtime Provider bindings joined package publication.
    private static final int PRE_RUNTIME_BINDING_SCHEMA_VERSION = 4;

    /// Current recovery journal schema with transaction-specific quarantine paths and runtime Provider bindings.
    private static final int SCHEMA_VERSION = 5;

    /// Role component used for quarantined published targets.
    private static final String TARGET_QUARANTINE_ROLE = "target";

    /// Role component used for quarantined old-package backups.
    private static final String BACKUP_QUARANTINE_ROLE = "backup";

    /// Role component used for quarantined staging packages.
    private static final String PREPARED_QUARANTINE_ROLE = "prepared";

    /// Role component used while an old backup is reacquired for restoration.
    private static final String BACKUP_RESTORE_ROLE = "backup-restore";

    /// Role component used by legacy final-disposal handoffs for a published target.
    private static final String TARGET_DISPOSAL_ROLE = "target-disposal";

    /// Role component used by legacy final-disposal handoffs for an old backup.
    private static final String BACKUP_DISPOSAL_ROLE = "backup-disposal";

    /// Role component used by legacy final-disposal handoffs for a staging package.
    private static final String PREPARED_DISPOSAL_ROLE = "prepared-disposal";

    /// Maximum serialized journal size accepted during recovery.
    private static final int MAX_TRANSACTION_BYTES = 4 * 1024 * 1024;

    /// JSON codec for the private recovery document.
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /// Directory containing installed, prepared, and backup plugin package files.
    private final Path pluginsDirectory;

    /// Recovery journal stored in the launcher-local home.
    private final Path transactionFile;

    /// Artifact-bound permission document participating in package publication.
    private final Path permissionFile;

    /// Desired enablement and pending-uninstall document participating in publication.
    private final Path stateFile;

    /// Pending private-storage cleanup tombstones participating in removal publication.
    private final Path cleanupFile;

    /// Proof-backed certification receipts participating in package publication.
    private final Path certificationReceiptFile;

    /// Dependent-scoped runtime Provider bindings participating in package publication.
    private final Path runtimeBindingFile;

    /// Ownership-safe recovery file transitions.
    private final PluginRecoveryFileOperations recoveryFiles;

    /// Creates a journal bound to one launcher-local plugin directory.
    ///
    /// @param localHome launcher-local home
    /// @param pluginsDirectory installed plugin directory
    PluginBatchTransactionJournal(Path localHome, Path pluginsDirectory) {
        this(localHome, pluginsDirectory, PluginRecoveryFileOperations.NO_OP_HOOK);
    }

    /// Creates a journal with a hook invoked immediately before each quarantine rename.
    ///
    /// @param localHome launcher-local home
    /// @param pluginsDirectory installed plugin directory
    /// @param recoveryQuarantineHook deterministic recovery hook
    PluginBatchTransactionJournal(
            Path localHome,
            Path pluginsDirectory,
            PluginRecoveryFileOperations.Hook recoveryQuarantineHook
    ) {
        this.pluginsDirectory = pluginsDirectory.toAbsolutePath().normalize();
        transactionFile = localHome.resolve("plugin-install-transaction.json").toAbsolutePath().normalize();
        permissionFile = localHome.resolve("plugin-permissions.json").toAbsolutePath().normalize();
        stateFile = localHome.resolve("plugin-states.json").toAbsolutePath().normalize();
        cleanupFile = localHome.resolve("plugin-cleanup-pending.json").toAbsolutePath().normalize();
        certificationReceiptFile = localHome.resolve(PluginCertificationReceiptStore.FILE_NAME)
                .toAbsolutePath().normalize();
        runtimeBindingFile = localHome.resolve(PluginRuntimeBindingStore.FILE_NAME).toAbsolutePath().normalize();
        recoveryFiles = new PluginRecoveryFileOperations(recoveryQuarantineHook);
    }

    /// Writes a prepared journal before any installed package is moved.
    ///
    /// @param backups original paths mapped to hidden backup paths
    /// @param targets stable publication targets
    /// @param preparedPackages hidden verified packages
    /// @return persisted prepared transaction
    /// @throws IOException if serialization or journal replacement fails
    Transaction begin(
            Map<Path, Path> backups,
            List<Path> targets,
            @Unmodifiable List<Path> preparedPackages
    ) throws IOException {
        if (Files.exists(transactionFile, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("An unresolved plugin transaction journal already exists: " + transactionFile);
        }
        if (targets.size() != preparedPackages.size()) {
            throw new IOException("Plugin transaction target and prepared package counts differ");
        }
        Transaction transaction = Transaction.prepared(
                backups,
                targets,
                preparedPackages,
                capture(permissionFile),
                capture(stateFile),
                capture(cleanupFile),
                capture(certificationReceiptFile),
                capture(runtimeBindingFile)
        );
        write(transaction);
        return transaction;
    }

    /// Persists the committed phase after every new target has been published.
    ///
    /// @param transaction active transaction
    /// @throws IOException if the committed journal cannot be written
    void markCommitted(Transaction transaction) throws IOException {
        transaction.markCommitted();
        write(transaction);
    }

    /// Persists the boundary after final graph validation and before transaction-owned copies are disposed.
    ///
    /// @param transaction active transaction
    /// @throws IOException if the cleanup authorization cannot be forced to disk
    private void markCleanupAuthorized(Transaction transaction) throws IOException {
        if (transaction.isCleanupAuthorized()) {
            return;
        }
        transaction.markCleanupAuthorized();
        write(transaction);
    }

    /// Publishes every package described by one prepared transaction without changing its recovery phase.
    ///
    /// @param transaction prepared transaction
    /// @throws IOException if a backup or target move fails
    void publishPrepared(Transaction transaction) throws IOException {
        Map<Path, Path> backups = resolveBackups(transaction);
        Map<Path, String> backupDigests = resolveBackupDigests(transaction);
        @Unmodifiable List<Path> targets = resolvePaths(transaction.getTargetFileNames());
        @Unmodifiable List<Path> preparedPackages = resolvePaths(transaction.getPreparedFileNames());
        @Unmodifiable List<String> targetDigests = transaction.getTargetSha256();
        if (targets.size() != preparedPackages.size()) {
            throw new IOException("Plugin transaction target and prepared package counts differ");
        }
        for (Map.Entry<Path, Path> backup : backups.entrySet()) {
            requireDigest(backup.getKey(), backupDigests.get(backup.getKey()), "installed plugin backup source");
            move(backup.getKey(), backup.getValue(), false);
            requireDigest(backup.getValue(), backupDigests.get(backup.getKey()), "installed plugin backup");
        }
        for (int index = 0; index < targets.size(); index++) {
            requireDigest(preparedPackages.get(index), targetDigests.get(index), "prepared plugin package");
            move(preparedPackages.get(index), targets.get(index), false);
            requireDigest(targets.get(index), targetDigests.get(index), "published plugin package");
        }
    }

    /// Disposes committed backups and removes the journal, retaining it when cleanup must be retried at startup.
    ///
    /// @param transaction committed transaction
    /// @throws IOException if a serialized journal path is invalid
    void finishCommitted(Transaction transaction) throws IOException {
        Map<Path, Path> backups = resolveBackups(transaction);
        Map<Path, String> backupDigests = resolveBackupDigests(transaction);
        @Unmodifiable List<Path> targets = resolvePaths(transaction.getTargetFileNames());
        @Unmodifiable List<Path> preparedPackages = resolvePaths(transaction.getPreparedFileNames());
        validateRecoveryPathGraph(backups, targets, preparedPackages, transaction);
        try {
            recoverCommitted(
                    backups,
                    backupDigests,
                    targets,
                    transaction.getTargetSha256(),
                    preparedPackages,
                    transaction
            );
        } catch (IOException exception) {
            if (!transaction.isCleanupAuthorized()) {
                restoreOwnedQuarantines(
                        backups,
                        backupDigests,
                        targets,
                        transaction.getTargetSha256(),
                        preparedPackages,
                        transaction,
                        exception
                );
            }
            throw exception;
        }
        Files.deleteIfExists(transactionFile);
    }

    /// Removes a prepared journal after successful in-process rollback.
    ///
    /// A failed journal deletion is attached to the publication failure; leaving the journal is safe because recovery
    /// is idempotent and will observe already restored originals.
    ///
    /// @param rollbackSuccessful whether every rollback operation succeeded
    /// @param publicationFailure root publication failure
    void finishRollback(boolean rollbackSuccessful, IOException publicationFailure) {
        if (!rollbackSuccessful) {
            return;
        }
        try {
            Files.deleteIfExists(transactionFile);
        } catch (IOException exception) {
            publicationFailure.addSuppressed(exception);
        }
    }

    /// Recovers or cleans a journal left by an interrupted publication.
    ///
    /// Prepared transactions are rolled back. Committed transactions retain new targets and dispose old backups.
    /// Invalid or partially unrecoverable journals remain in place so plugin discovery can refuse a mixed graph.
    ///
    /// @return whether no unresolved transaction remains
    boolean recover() {
        try {
            @Nullable String serializedTransaction = readTransactionFile();
            if (serializedTransaction == null) {
                return true;
            }
            @Nullable Transaction transaction = GSON.fromJson(
                    serializedTransaction,
                    Transaction.class
            );
            if (transaction == null || !transaction.hasValidPhase()) {
                throw new IOException("Plugin batch transaction journal is invalid");
            }
            if (transaction.requiresRecoveryUpgrade()) {
                transaction.upgradeForRecovery();
                if (!transaction.hasValidPhase()) {
                    throw new IOException("Upgraded plugin batch transaction journal is invalid");
                }
                write(transaction);
            }

            Map<Path, Path> backups = resolveBackups(transaction);
            Map<Path, String> backupDigests = resolveBackupDigests(transaction);
            @Unmodifiable List<Path> targets = resolvePaths(transaction.getTargetFileNames());
            @Unmodifiable List<Path> preparedPackages = resolvePaths(transaction.getPreparedFileNames());
            validateRecoveryPathGraph(backups, targets, preparedPackages, transaction);
            boolean documentsRecovered;
            try {
                if (transaction.isCommitted()) {
                    recoverCommitted(
                            backups,
                            backupDigests,
                            targets,
                            transaction.getTargetSha256(),
                            preparedPackages,
                            transaction
                    );
                    documentsRecovered = true;
                } else {
                    documentsRecovered = recoverPrepared(
                            backups,
                            backupDigests,
                            targets,
                            transaction.getTargetSha256(),
                            preparedPackages,
                            transaction
                    );
                }
            } catch (IOException exception) {
                if (!transaction.isCleanupAuthorized()) {
                    restoreOwnedQuarantines(
                            backups,
                            backupDigests,
                            targets,
                            transaction.getTargetSha256(),
                            preparedPackages,
                            transaction,
                            exception
                    );
                }
                throw exception;
            }
            if (!documentsRecovered) {
                IOException snapshotFailure = new IOException(
                        "Plugin transaction document snapshots could not be restored"
                );
                restoreOwnedQuarantines(
                        backups,
                        backupDigests,
                        targets,
                        transaction.getTargetSha256(),
                        preparedPackages,
                        transaction,
                        snapshotFailure
                );
                return false;
            }
            Files.deleteIfExists(transactionFile);
            LOG.info(transaction.isCommitted()
                    ? "Completed cleanup for committed plugin batch transaction"
                    : "Rolled back interrupted plugin batch transaction");
            return true;
        } catch (IOException | RuntimeException exception) {
            LOG.error("Failed to recover plugin batch transaction", exception);
            return false;
        }
    }

    /// Reads and strictly decodes the bounded journal through one no-follow file handle.
    ///
    /// @return serialized journal JSON, or `null` when the direct path is absent at handle acquisition
    /// @throws IOException if the path cannot be opened directly, exceeds the limit, or is not valid UTF-8
    private @Nullable String readTransactionFile() throws IOException {
        try (FileChannel channel = FileChannel.open(
                transactionFile,
                StandardOpenOption.READ,
                LinkOption.NOFOLLOW_LINKS
        )) {
            if (channel.size() > MAX_TRANSACTION_BYTES) {
                throw new IOException("Plugin batch transaction journal is too large");
            }
            ByteBuffer buffer = ByteBuffer.allocate(MAX_TRANSACTION_BYTES + 1);
            while (buffer.hasRemaining() && channel.read(buffer) != -1) {
                // A growing journal is bounded by the extra sentinel byte on this same open handle.
            }
            if (buffer.position() > MAX_TRANSACTION_BYTES) {
                throw new IOException("Plugin batch transaction journal is too large");
            }
            buffer.flip();
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(buffer)
                    .toString();
        } catch (NoSuchFileException ignored) {
            return null;
        }
    }

    /// Serializes and forces a journal before atomic replacement when supported.
    ///
    /// File contents are forced both before and after publication. Parent-directory metadata is forced on providers
    /// that support opening directories as channels; unsupported platforms retain the strongest available file-level
    /// durability guarantee.
    ///
    /// @param transaction transaction state
    /// @throws IOException if writing or replacement fails
    private void write(Transaction transaction) throws IOException {
        String json = GSON.toJson(transaction);
        byte @Unmodifiable [] jsonBytes = json.getBytes(StandardCharsets.UTF_8);
        if (jsonBytes.length > MAX_TRANSACTION_BYTES) {
            throw new IOException("Plugin batch transaction journal is too large");
        }
        Path temporaryFile = transactionFile.resolveSibling(
                transactionFile.getFileName() + ".tmp-" + newRecoveryNonce()
        );
        try {
            try (FileChannel channel = FileChannel.open(
                    temporaryFile,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS
            )) {
                ByteBuffer buffer = ByteBuffer.wrap(jsonBytes);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            try {
                Files.move(
                        temporaryFile,
                        transactionFile,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING
                );
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporaryFile, transactionFile, StandardCopyOption.REPLACE_EXISTING);
            }
            try (FileChannel channel = FileChannel.open(
                    transactionFile,
                    StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS
            )) {
                channel.force(true);
            }
            forceParentDirectoryBestEffort(Objects.requireNonNull(transactionFile.getParent()));
        } finally {
            Files.deleteIfExists(temporaryFile);
        }
    }

    /// Forces directory metadata when the current file-system provider exposes directory channels.
    ///
    /// @param directory parent directory whose replacement entry should be persisted
    private static void forceParentDirectoryBestEffort(Path directory) {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException | UnsupportedOperationException ignored) {
            // Windows and some custom providers do not expose directories as forceable file channels.
        }
    }

    /// Forces retained package contents and their directory entries before cleanup authorization is persisted.
    ///
    /// @param files final retained package paths
    /// @throws IOException if a retained package cannot be opened without following links or forced
    private static void forceRetainedPackages(Iterable<Path> files) throws IOException {
        for (Path file : files) {
            forceFileAndParent(file);
        }
    }

    /// Forces one regular file and then attempts to force its parent directory entry.
    ///
    /// @param file retained or atomically replaced file
    /// @throws IOException if the file itself cannot be opened without following links or forced
    private static void forceFileAndParent(Path file) throws IOException {
        try (FileChannel channel = FileChannel.open(
                file,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS
        )) {
            channel.force(true);
        }
        forceParentDirectoryBestEffort(Objects.requireNonNull(file.getParent()));
    }

    /// Resolves all validated backup mappings from a transaction.
    ///
    /// @param transaction validated transaction
    /// @return mutable original-to-backup path map
    /// @throws IOException if a file name is invalid
    private Map<Path, Path> resolveBackups(Transaction transaction) throws IOException {
        Map<Path, Path> backups = new LinkedHashMap<>();
        for (Backup backup : transaction.getBackups()) {
            backups.put(resolve(backup.getOriginalFileName()), resolve(backup.getBackupFileName()));
        }
        return backups;
    }

    /// Resolves expected original package digests indexed by their stable installed paths.
    ///
    /// @param transaction validated transaction
    /// @return mutable original-path to SHA-256 map
    /// @throws IOException if a serialized original file name is invalid
    private Map<Path, String> resolveBackupDigests(Transaction transaction) throws IOException {
        Map<Path, String> digests = new LinkedHashMap<>();
        for (Backup backup : transaction.getBackups()) {
            digests.put(resolve(backup.getOriginalFileName()), backup.getOriginalSha256());
        }
        return digests;
    }

    /// Resolves immutable direct-child paths from serialized file names.
    ///
    /// @param fileNames validated serialized file names
    /// @return immutable normalized paths
    /// @throws IOException if any file name is invalid
    private @Unmodifiable List<Path> resolvePaths(@Unmodifiable List<String> fileNames) throws IOException {
        List<Path> paths = new ArrayList<>();
        for (String fileName : fileNames) {
            paths.add(resolve(fileName));
        }
        return List.copyOf(paths);
    }

    /// Resolves one validated direct child of the plugin directory.
    ///
    /// @param fileName serialized direct file name
    /// @return normalized path inside the plugin directory
    /// @throws IOException if the value is invalid or escapes the plugin directory
    private Path resolve(String fileName) throws IOException {
        final Path relativePath;
        try {
            relativePath = Path.of(fileName);
        } catch (InvalidPathException exception) {
            throw new IOException("Invalid plugin transaction path: " + fileName, exception);
        }
        if (relativePath.isAbsolute() || relativePath.getNameCount() != 1 || fileName.isBlank()) {
            throw new IOException("Plugin transaction path is not a direct file name: " + fileName);
        }
        Path resolved = pluginsDirectory.resolve(relativePath).normalize();
        if (!Objects.equals(pluginsDirectory, resolved.getParent())) {
            throw new IOException("Plugin transaction path escapes the plugin directory: " + fileName);
        }
        return resolved;
    }

    /// Returns whether text is a lower-case SHA-256 digest accepted in a recovery journal.
    ///
    /// @param value candidate text
    /// @return whether the value is exactly 64 lower-case hexadecimal characters
    private static boolean isSha256(@Nullable String value) {
        if (value == null || value.length() != 64) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (!(character >= '0' && character <= '9') && !(character >= 'a' && character <= 'f')) {
                return false;
            }
        }
        return true;
    }

    /// Creates an unpredictable lower-case token used to derive direct-child quarantine paths.
    ///
    /// @return 128-bit hexadecimal recovery nonce
    private static String newRecoveryNonce() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /// Returns whether text is a valid transaction recovery nonce.
    ///
    /// @param value candidate text
    /// @return whether the value is exactly 32 lower-case hexadecimal characters
    private static boolean isRecoveryNonce(@Nullable String value) {
        if (value == null || value.length() != 32) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (!(character >= '0' && character <= '9') && !(character >= 'a' && character <= 'f')) {
                return false;
            }
        }
        return true;
    }

    /// Validates that serialized recovery paths cannot alias destructive roles or quarantine paths.
    ///
    /// Stable targets may equal their own backup original paths because an update publishes in place. Every other
    /// source role and every generated quarantine path must be distinct.
    ///
    /// @param backups original paths mapped to backup paths
    /// @param targets stable publication targets
    /// @param preparedPackages hidden prepared packages
    /// @param transaction active transaction
    /// @throws IOException if path roles alias or a serialized backup original was duplicated
    private void validateRecoveryPathGraph(
            Map<Path, Path> backups,
            @Unmodifiable List<Path> targets,
            @Unmodifiable List<Path> preparedPackages,
            Transaction transaction
    ) throws IOException {
        if (backups.size() != transaction.getBackups().size()) {
            throw new IOException("Plugin transaction contains duplicate backup original paths");
        }

        Set<Path> backupPaths = new HashSet<>();
        for (Path backup : backups.values()) {
            if (!backupPaths.add(backup)) {
                throw new IOException("Plugin transaction contains duplicate backup paths: " + backup);
            }
        }
        Set<Path> targetPaths = new HashSet<>();
        for (Path target : targets) {
            if (!targetPaths.add(target)) {
                throw new IOException("Plugin transaction contains duplicate target paths: " + target);
            }
        }
        Set<Path> preparedPaths = new HashSet<>();
        for (Path preparedPackage : preparedPackages) {
            if (!preparedPaths.add(preparedPackage)) {
                throw new IOException("Plugin transaction contains duplicate staging paths: " + preparedPackage);
            }
        }

        Set<Path> exclusivePaths = new HashSet<>(backups.keySet());
        for (Path backup : backupPaths) {
            if (!exclusivePaths.add(backup)) {
                throw new IOException("Plugin transaction backup path aliases another package path: " + backup);
            }
        }
        for (Path target : targets) {
            if (!backups.containsKey(target) && !exclusivePaths.add(target)) {
                throw new IOException("Plugin transaction target aliases another package path: " + target);
            }
        }
        for (Path preparedPackage : preparedPackages) {
            if (!exclusivePaths.add(preparedPackage)) {
                throw new IOException("Plugin transaction staging path aliases another package path: "
                        + preparedPackage);
            }
        }

        Set<Path> quarantinePaths = new HashSet<>();
        for (int index = 0; index < targets.size(); index++) {
            quarantinePaths.add(quarantinePath(transaction, TARGET_QUARANTINE_ROLE, index));
            quarantinePaths.add(quarantinePath(transaction, TARGET_DISPOSAL_ROLE, index));
        }
        for (int index = 0; index < backups.size(); index++) {
            quarantinePaths.add(quarantinePath(transaction, BACKUP_QUARANTINE_ROLE, index));
            quarantinePaths.add(quarantinePath(transaction, BACKUP_RESTORE_ROLE, index));
            quarantinePaths.add(quarantinePath(transaction, BACKUP_DISPOSAL_ROLE, index));
        }
        for (int index = 0; index < preparedPackages.size(); index++) {
            quarantinePaths.add(quarantinePath(transaction, PREPARED_QUARANTINE_ROLE, index));
            quarantinePaths.add(quarantinePath(transaction, PREPARED_DISPOSAL_ROLE, index));
        }
        int expectedQuarantinePaths = targets.size() * 2 + backups.size() * 3 + preparedPackages.size() * 2;
        if (quarantinePaths.size() != expectedQuarantinePaths) {
            throw new IOException("Plugin transaction quarantine paths are not unique");
        }
        for (Path quarantine : quarantinePaths) {
            if (!Objects.equals(pluginsDirectory, quarantine.getParent())) {
                throw new IOException("Plugin transaction quarantine escapes the plugin directory: " + quarantine);
            }
            if (exclusivePaths.contains(quarantine)) {
                throw new IOException("Plugin transaction package path aliases a recovery quarantine: "
                        + quarantine);
            }
        }
    }

    /// Completes cleanup for a committed transaction through verified same-directory quarantines.
    ///
    /// @param backups original paths mapped to backup paths
    /// @param backupDigests expected old-package digests
    /// @param targets stable publication targets
    /// @param targetDigests expected new-package digests
    /// @param preparedPackages hidden prepared packages
    /// @param transaction active transaction
    /// @throws IOException if any package path changes, disappears ucepectedly, or cannot be quarantined
    private void recoverCommitted(
            Map<Path, Path> backups,
            Map<Path, String> backupDigests,
            @Unmodifiable List<Path> targets,
            @Unmodifiable List<String> targetDigests,
            @Unmodifiable List<Path> preparedPackages,
            Transaction transaction
    ) throws IOException {
        requireConsistentRecoveryLists(targets, targetDigests, preparedPackages);
        if (transaction.isCleanupAuthorized()) {
            finishCommittedOwnedCleanup(
                    backups,
                    backupDigests,
                    preparedPackages,
                    targetDigests,
                    transaction
            );
            return;
        }
        validateCommittedGraph(
                backups,
                backupDigests,
                targets,
                targetDigests,
                preparedPackages,
                transaction,
                false
        );

        int backupIndex = 0;
        for (Map.Entry<Path, Path> entry : backups.entrySet()) {
            recoveryFiles.quarantineIfPresent(
                    entry.getValue(),
                    quarantinePath(transaction, BACKUP_QUARANTINE_ROLE, backupIndex),
                    backupDigests.get(entry.getKey()),
                    "committed plugin backup"
            );
            backupIndex++;
        }
        for (int index = 0; index < preparedPackages.size(); index++) {
            recoveryFiles.quarantineIfPresent(
                    preparedPackages.get(index),
                    quarantinePath(transaction, PREPARED_QUARANTINE_ROLE, index),
                    targetDigests.get(index),
                    "committed plugin staging file"
            );
        }

        // Force retained targets before the final validation boundary. A replacement during forcing is therefore
        // observed by the validation below instead of being authorized without another digest check.
        forceRetainedPackages(targets);
        // Revalidate retained targets after acquiring every destructively cleaned file. If another process replaced a
        // target during quarantine acquisition or forcing, its unknown bytes remain untouched and the old backup stays
        // isolated.
        validateCommittedGraph(
                backups,
                backupDigests,
                targets,
                targetDigests,
                preparedPackages,
                transaction,
                false
        );
        markCleanupAuthorized(transaction);
        finishCommittedOwnedCleanup(
                backups,
                backupDigests,
                preparedPackages,
                targetDigests,
                transaction
        );
    }

    /// Tombstones transaction-owned committed cleanup artifacts after durable final graph authorization.
    ///
    /// @param backups original paths mapped to backup paths
    /// @param backupDigests expected old-package digests
    /// @param preparedPackages hidden prepared packages
    /// @param targetDigests expected new-package digests
    /// @param transaction active transaction
    /// @throws IOException if an owned cleanup object cannot be read, marked, or forced
    private void finishCommittedOwnedCleanup(
            Map<Path, Path> backups,
            Map<Path, String> backupDigests,
            @Unmodifiable List<Path> preparedPackages,
            @Unmodifiable List<String> targetDigests,
            Transaction transaction
    ) throws IOException {
        int backupIndex = 0;
        for (Map.Entry<Path, Path> entry : backups.entrySet()) {
            recoveryFiles.disposeVerifiedQuarantine(
                    quarantinePath(transaction, BACKUP_QUARANTINE_ROLE, backupIndex),
                    quarantinePath(transaction, BACKUP_DISPOSAL_ROLE, backupIndex),
                    backupDigests.get(entry.getKey()),
                    "committed plugin backup"
            );
            backupIndex++;
        }
        for (int index = 0; index < preparedPackages.size(); index++) {
            recoveryFiles.disposeVerifiedQuarantine(
                    quarantinePath(transaction, PREPARED_QUARANTINE_ROLE, index),
                    quarantinePath(transaction, PREPARED_DISPOSAL_ROLE, index),
                    targetDigests.get(index),
                    "committed plugin staging file"
            );
        }
    }

    /// Validates the complete package graph for committed cleanup.
    ///
    /// @param backups original paths mapped to backup paths
    /// @param backupDigests expected old-package digests
    /// @param targets stable publication targets
    /// @param targetDigests expected new-package digests
    /// @param preparedPackages hidden prepared packages
    /// @param transaction active transaction
    /// @param cleanupComplete whether all cleanup sources and quarantines must be absent
    /// @throws IOException if any graph node has an ucepected type, digest, or existence state
    private void validateCommittedGraph(
            Map<Path, Path> backups,
            Map<Path, String> backupDigests,
            @Unmodifiable List<Path> targets,
            @Unmodifiable List<String> targetDigests,
            @Unmodifiable List<Path> preparedPackages,
            Transaction transaction,
            boolean cleanupComplete
    ) throws IOException {
        Map<Path, String> targetDigestByPath = targetDigestByPath(targets, targetDigests);
        for (int index = 0; index < targets.size(); index++) {
            requireDigest(targets.get(index), targetDigests.get(index), "committed plugin target");
        }

        int backupIndex = 0;
        for (Map.Entry<Path, Path> entry : backups.entrySet()) {
            Path original = entry.getKey();
            Path backup = entry.getValue();
            Path quarantine = quarantinePath(transaction, BACKUP_QUARANTINE_ROLE, backupIndex);
            String expectedOldDigest = requireRecordedDigest(backupDigests.get(original), backup);
            @Nullable String expectedTargetDigest = targetDigestByPath.get(original);
            if (expectedTargetDigest == null && pathExists(original)) {
                throw new IOException("Plugin transaction committed original path was recreated: " + original);
            }
            validateCleanupArtifact(
                    backup,
                    quarantine,
                    quarantinePath(transaction, BACKUP_DISPOSAL_ROLE, backupIndex),
                    expectedOldDigest,
                    "committed plugin backup",
                    cleanupComplete
            );
            backupIndex++;
        }
        for (int index = 0; index < preparedPackages.size(); index++) {
            validateCleanupArtifact(
                    preparedPackages.get(index),
                    quarantinePath(transaction, PREPARED_QUARANTINE_ROLE, index),
                    quarantinePath(transaction, PREPARED_DISPOSAL_ROLE, index),
                    targetDigests.get(index),
                    "committed plugin staging file",
                    cleanupComplete
            );
        }
    }

    /// Validates one cleanup source and its transaction-specific quarantine.
    ///
    /// @param source original cleanup source
    /// @param quarantine recovery-owned quarantine path
    /// @param disposal final disposal handoff
    /// @param expectedSha256 expected package digest
    /// @param description diagnostic role
    /// @param cleanupComplete whether both paths must already be absent
    /// @throws IOException if paths are ambiguous, changed, or remain after final cleanup
    private static void validateCleanupArtifact(
            Path source,
            Path quarantine,
            Path disposal,
            String expectedSha256,
            String description,
            boolean cleanupComplete
    ) throws IOException {
        boolean sourceExists = pathExists(source);
        boolean quarantineExists = pathExists(quarantine);
        boolean disposalExists = pathExists(disposal);
        int existingLocations = (sourceExists ? 1 : 0)
                + (quarantineExists ? 1 : 0)
                + (disposalExists ? 1 : 0);
        if (existingLocations > 1) {
            throw new IOException("Plugin transaction has multiple locations for " + description + ": " + source);
        }
        if (cleanupComplete && existingLocations != 0) {
            throw new IOException("Plugin transaction did not remove " + description + ": " + source);
        }
        if (sourceExists) {
            requireDigest(source, expectedSha256, description);
        }
        if (quarantineExists) {
            requireDigest(quarantine, expectedSha256, description + " quarantine");
        }
        if (disposalExists) {
            requireDigest(disposal, expectedSha256, description + " disposal");
        }
    }

    /// Restores a prepared transaction that did not reach the committed journal phase.
    ///
    /// @param backups original paths mapped to backup paths
    /// @param backupDigests expected original package digests indexed by original path
    /// @param targets stable target paths
    /// @param targetDigests expected published package digests in target order
    /// @param preparedPackages hidden prepared packages
    /// @param transaction persisted document snapshots
    /// @return whether every rollback operation succeeded
    private boolean recoverPrepared(
            Map<Path, Path> backups,
            Map<Path, String> backupDigests,
            @Unmodifiable List<Path> targets,
            @Unmodifiable List<String> targetDigests,
            @Unmodifiable List<Path> preparedPackages,
            Transaction transaction
    ) throws IOException {
        requireConsistentRecoveryLists(targets, targetDigests, preparedPackages);
        if (transaction.isCleanupAuthorized()) {
            finishPreparedOwnedCleanup(targets, targetDigests, preparedPackages, transaction);
            return true;
        }
        validatePreparedGraph(
                backups,
                backupDigests,
                targets,
                targetDigests,
                preparedPackages,
                transaction
        );

        Map<Path, Path> backupQuarantines = backupQuarantines(backups, transaction);
        Map<Path, Path> backupRestoreHandoffs = backupRestoreHandoffs(backups, transaction);
        for (int index = 0; index < targets.size(); index++) {
            Path target = targets.get(index);
            if (!isBackupRestored(
                    target,
                    backups,
                    backupDigests,
                    backupQuarantines,
                    backupRestoreHandoffs
            )) {
                recoveryFiles.quarantineIfPresent(
                        target,
                        quarantinePath(transaction, TARGET_QUARANTINE_ROLE, index),
                        targetDigests.get(index),
                        "interrupted plugin batch target"
                );
            }
        }

        int backupIndex = 0;
        for (Map.Entry<Path, Path> entry : backups.entrySet()) {
            if (!isBackupRestored(
                    entry.getKey(),
                    backups,
                    backupDigests,
                    backupQuarantines,
                    backupRestoreHandoffs
            )) {
                recoveryFiles.quarantineIfPresent(
                        entry.getValue(),
                        quarantinePath(transaction, BACKUP_QUARANTINE_ROLE, backupIndex),
                        backupDigests.get(entry.getKey()),
                        "interrupted plugin backup"
                );
            }
            backupIndex++;
        }
        for (int index = 0; index < preparedPackages.size(); index++) {
            recoveryFiles.quarantineIfPresent(
                    preparedPackages.get(index),
                    quarantinePath(transaction, PREPARED_QUARANTINE_ROLE, index),
                    targetDigests.get(index),
                    "interrupted plugin staging file"
            );
        }

        // The second graph validation closes races that place a new path after another artifact was quarantined. No
        // restore or delete is attempted until every current path still belongs to the recorded transaction.
        validatePreparedGraph(
                backups,
                backupDigests,
                targets,
                targetDigests,
                preparedPackages,
                transaction
        );

        List<Map.Entry<Path, Path>> entries = new ArrayList<>(backups.entrySet());
        for (int index = entries.size() - 1; index >= 0; index--) {
            Map.Entry<Path, Path> backup = entries.get(index);
            Path quarantine = Objects.requireNonNull(backupQuarantines.get(backup.getKey()));
            String expectedOldDigest = requireRecordedDigest(
                    backupDigests.get(backup.getKey()),
                    backup.getValue()
            );
            recoveryFiles.restoreQuarantinedArtifact(
                    quarantine,
                    quarantinePath(transaction, BACKUP_RESTORE_ROLE, index),
                    backup.getKey(),
                    expectedOldDigest,
                    "plugin backup"
            );
        }

        validatePreparedRestoredGraph(
                backups,
                backupDigests,
                targets,
                targetDigests,
                preparedPackages,
                transaction,
                false
        );
        boolean snapshotsRestored = restoreSnapshot(transaction.getPermissionSnapshot(), permissionFile);
        snapshotsRestored &= restoreSnapshot(transaction.getStateSnapshot(), stateFile);
        snapshotsRestored &= restoreSnapshot(transaction.getCleanupSnapshot(), cleanupFile);
        snapshotsRestored &= restoreSnapshot(
                transaction.getCertificationReceiptSnapshot(),
                certificationReceiptFile
        );
        snapshotsRestored &= restoreSnapshot(transaction.getRuntimeBindingSnapshot(), runtimeBindingFile);
        if (!snapshotsRestored) {
            return false;
        }

        // Force restored packages before the final validation boundary. A replacement during forcing is therefore
        // observed below instead of being authorized without another digest check.
        forceRetainedPackages(backups.keySet());
        // This is the final retained-graph validation boundary. Every rollback copy remains present until this check
        // succeeds. Changes to restored targets after this point are independent external tampering and cannot turn
        // successful cleanup into an unrecoverable journal after transaction-owned copies have been disposed.
        validatePreparedRestoredGraph(
                backups,
                backupDigests,
                targets,
                targetDigests,
                preparedPackages,
                transaction,
                false
        );
        markCleanupAuthorized(transaction);
        finishPreparedOwnedCleanup(targets, targetDigests, preparedPackages, transaction);
        return true;
    }

    /// Tombstones transaction-owned prepared rollback artifacts after durable final graph authorization.
    ///
    /// @param targets stable publication targets
    /// @param targetDigests expected new-package digests
    /// @param preparedPackages hidden prepared packages
    /// @param transaction active transaction
    /// @throws IOException if an owned cleanup object cannot be read, marked, or forced
    private void finishPreparedOwnedCleanup(
            @Unmodifiable List<Path> targets,
            @Unmodifiable List<String> targetDigests,
            @Unmodifiable List<Path> preparedPackages,
            Transaction transaction
    ) throws IOException {
        for (int index = 0; index < targets.size(); index++) {
            recoveryFiles.disposeVerifiedQuarantine(
                    quarantinePath(transaction, TARGET_QUARANTINE_ROLE, index),
                    quarantinePath(transaction, TARGET_DISPOSAL_ROLE, index),
                    targetDigests.get(index),
                    "interrupted plugin batch target"
            );
        }
        for (int index = 0; index < preparedPackages.size(); index++) {
            recoveryFiles.disposeVerifiedQuarantine(
                    quarantinePath(transaction, PREPARED_QUARANTINE_ROLE, index),
                    quarantinePath(transaction, PREPARED_DISPOSAL_ROLE, index),
                    targetDigests.get(index),
                    "interrupted plugin staging file"
            );
        }
    }

    /// Validates the package graph before or during prepared rollback quarantine acquisition.
    ///
    /// @param backups original paths mapped to backup paths
    /// @param backupDigests expected old-package digests
    /// @param targets stable publication targets
    /// @param targetDigests expected new-package digests
    /// @param preparedPackages hidden prepared packages
    /// @param transaction active transaction
    /// @throws IOException if any package path is ambiguous, missing unsafely, or has ucepected bytes
    private void validatePreparedGraph(
            Map<Path, Path> backups,
            Map<Path, String> backupDigests,
            @Unmodifiable List<Path> targets,
            @Unmodifiable List<String> targetDigests,
            @Unmodifiable List<Path> preparedPackages,
            Transaction transaction
    ) throws IOException {
        Map<Path, String> targetDigestByPath = targetDigestByPath(targets, targetDigests);
        Map<Path, Path> backupQuarantines = backupQuarantines(backups, transaction);
        Map<Path, Path> backupRestoreHandoffs = backupRestoreHandoffs(backups, transaction);
        int backupIndex = 0;
        for (Map.Entry<Path, Path> entry : backups.entrySet()) {
            Path original = entry.getKey();
            Path backup = entry.getValue();
            Path quarantine = quarantinePath(transaction, BACKUP_QUARANTINE_ROLE, backupIndex);
            Path restoreHandoff = quarantinePath(transaction, BACKUP_RESTORE_ROLE, backupIndex);
            String expectedOldDigest = requireRecordedDigest(backupDigests.get(original), backup);
            boolean backupExists = pathExists(backup);
            boolean quarantineExists = pathExists(quarantine);
            boolean restoreHandoffExists = pathExists(restoreHandoff);
            int oldPackageLocations = (backupExists ? 1 : 0)
                    + (quarantineExists ? 1 : 0)
                    + (restoreHandoffExists ? 1 : 0);
            if (oldPackageLocations > 1) {
                throw new IOException("Plugin transaction has multiple old-package backup locations: " + backup);
            }
            if (backupExists) {
                requireDigest(backup, expectedOldDigest, "interrupted plugin backup");
            }
            if (quarantineExists) {
                requireDigest(quarantine, expectedOldDigest, "interrupted plugin backup quarantine");
            }
            if (restoreHandoffExists) {
                requireDigest(restoreHandoff, expectedOldDigest, "interrupted plugin backup restore handoff");
            }
            if (oldPackageLocations == 0) {
                requireDigest(original, expectedOldDigest, "previously restored plugin package");
            } else if (pathExists(original)) {
                @Nullable String expectedNewDigest = targetDigestByPath.get(original);
                requireDigest(original, expectedNewDigest, "published plugin package awaiting rollback");
            }
            backupIndex++;
        }

        for (int index = 0; index < targets.size(); index++) {
            Path target = targets.get(index);
            Path quarantine = quarantinePath(transaction, TARGET_QUARANTINE_ROLE, index);
            Path disposal = quarantinePath(transaction, TARGET_DISPOSAL_ROLE, index);
            String expectedNewDigest = targetDigests.get(index);
            boolean restored = isBackupRestored(
                    target,
                    backups,
                    backupDigests,
                    backupQuarantines,
                    backupRestoreHandoffs
            );
            boolean targetExists = pathExists(target);
            boolean quarantineExists = pathExists(quarantine);
            boolean disposalExists = pathExists(disposal);
            if (restored) {
                if (quarantineExists && disposalExists) {
                    throw new IOException("Plugin transaction has multiple interrupted target cleanup locations: "
                            + target);
                }
                if (quarantineExists) {
                    requireDigest(quarantine, expectedNewDigest, "interrupted plugin target quarantine");
                }
                if (disposalExists) {
                    requireDigest(disposal, expectedNewDigest, "interrupted plugin target disposal");
                }
                continue;
            }
            int targetLocations = (targetExists ? 1 : 0)
                    + (quarantineExists ? 1 : 0)
                    + (disposalExists ? 1 : 0);
            if (targetLocations > 1) {
                throw new IOException("Plugin transaction has multiple published target locations: " + target);
            }
            if (targetExists) {
                requireDigest(target, expectedNewDigest, "published plugin package awaiting rollback");
            }
            if (quarantineExists) {
                requireDigest(quarantine, expectedNewDigest, "interrupted plugin target quarantine");
            }
            if (disposalExists) {
                requireDigest(disposal, expectedNewDigest, "interrupted plugin target disposal");
            }
        }

        for (int index = 0; index < preparedPackages.size(); index++) {
            Path preparedPackage = preparedPackages.get(index);
            Path quarantine = quarantinePath(transaction, PREPARED_QUARANTINE_ROLE, index);
            Path disposal = quarantinePath(transaction, PREPARED_DISPOSAL_ROLE, index);
            boolean preparedExists = pathExists(preparedPackage);
            boolean quarantineExists = pathExists(quarantine);
            boolean disposalExists = pathExists(disposal);
            int preparedLocations = (preparedExists ? 1 : 0)
                    + (quarantineExists ? 1 : 0)
                    + (disposalExists ? 1 : 0);
            if (preparedLocations > 1) {
                throw new IOException("Plugin transaction has multiple staging package locations: " + preparedPackage);
            }
            if (preparedExists) {
                requireDigest(preparedPackage, targetDigests.get(index), "interrupted plugin staging file");
            }
            if (quarantineExists) {
                requireDigest(
                        quarantine,
                        targetDigests.get(index),
                        "interrupted plugin staging quarantine"
                );
            }
            if (disposalExists) {
                requireDigest(disposal, targetDigests.get(index), "interrupted plugin staging disposal");
            }
        }
    }

    /// Validates the rollback graph after every old package has been restored.
    ///
    /// @param backups original paths mapped to backup paths
    /// @param backupDigests expected old-package digests
    /// @param targets stable publication targets
    /// @param targetDigests expected new-package digests
    /// @param preparedPackages hidden prepared packages
    /// @param transaction active transaction
    /// @param cleanupComplete whether target and staging quarantines must be absent
    /// @throws IOException if the restored graph is incomplete or contains an ucepected current path
    private void validatePreparedRestoredGraph(
            Map<Path, Path> backups,
            Map<Path, String> backupDigests,
            @Unmodifiable List<Path> targets,
            @Unmodifiable List<String> targetDigests,
            @Unmodifiable List<Path> preparedPackages,
            Transaction transaction,
            boolean cleanupComplete
    ) throws IOException {
        for (Map.Entry<Path, Path> entry : backups.entrySet()) {
            requireDigest(
                    entry.getKey(),
                    backupDigests.get(entry.getKey()),
                    "restored plugin package"
            );
            if (pathExists(entry.getValue())) {
                throw new IOException("Plugin transaction backup source remained after restore: " + entry.getValue());
            }
        }
        int backupIndex = 0;
        for (Path ignored : backups.keySet()) {
            Path quarantine = quarantinePath(transaction, BACKUP_QUARANTINE_ROLE, backupIndex);
            if (pathExists(quarantine)) {
                throw new IOException("Plugin transaction backup quarantine remained after restore: " + quarantine);
            }
            Path restoreHandoff = quarantinePath(transaction, BACKUP_RESTORE_ROLE, backupIndex);
            if (pathExists(restoreHandoff)) {
                throw new IOException("Plugin transaction backup restore handoff remained after restore: "
                        + restoreHandoff);
            }
            backupIndex++;
        }

        for (int index = 0; index < targets.size(); index++) {
            Path target = targets.get(index);
            if (!backups.containsKey(target) && pathExists(target)) {
                throw new IOException("Plugin transaction published target remained after rollback: " + target);
            }
            Path quarantine = quarantinePath(transaction, TARGET_QUARANTINE_ROLE, index);
            Path disposal = quarantinePath(transaction, TARGET_DISPOSAL_ROLE, index);
            boolean quarantineExists = pathExists(quarantine);
            boolean disposalExists = pathExists(disposal);
            if (quarantineExists && disposalExists) {
                throw new IOException("Plugin transaction has multiple target cleanup locations: " + target);
            }
            if (cleanupComplete && (quarantineExists || disposalExists)) {
                throw new IOException("Plugin transaction target cleanup remained after rollback: " + target);
            }
            if (!cleanupComplete && quarantineExists) {
                requireDigest(quarantine, targetDigests.get(index), "interrupted plugin target quarantine");
            }
            if (!cleanupComplete && disposalExists) {
                requireDigest(disposal, targetDigests.get(index), "interrupted plugin target disposal");
            }
        }
        for (int index = 0; index < preparedPackages.size(); index++) {
            Path preparedPackage = preparedPackages.get(index);
            if (pathExists(preparedPackage)) {
                throw new IOException("Plugin transaction staging source remained after rollback: "
                        + preparedPackage);
            }
            Path quarantine = quarantinePath(transaction, PREPARED_QUARANTINE_ROLE, index);
            Path disposal = quarantinePath(transaction, PREPARED_DISPOSAL_ROLE, index);
            boolean quarantineExists = pathExists(quarantine);
            boolean disposalExists = pathExists(disposal);
            if (quarantineExists && disposalExists) {
                throw new IOException("Plugin transaction has multiple staging cleanup locations: "
                        + preparedPackage);
            }
            if (cleanupComplete && (quarantineExists || disposalExists)) {
                throw new IOException("Plugin transaction staging cleanup remained after rollback: "
                        + preparedPackage);
            }
            if (!cleanupComplete && quarantineExists) {
                requireDigest(quarantine, targetDigests.get(index), "interrupted plugin staging quarantine");
            }
            if (!cleanupComplete && disposalExists) {
                requireDigest(disposal, targetDigests.get(index), "interrupted plugin staging disposal");
            }
        }
    }

    /// Returns transaction-specific backup quarantines indexed by original installed paths.
    ///
    /// @param backups original paths mapped to backup paths
    /// @param transaction active transaction
    /// @return mutable original-to-quarantine path map
    private Map<Path, Path> backupQuarantines(Map<Path, Path> backups, Transaction transaction) {
        Map<Path, Path> quarantines = new LinkedHashMap<>();
        int index = 0;
        for (Path original : backups.keySet()) {
            quarantines.put(original, quarantinePath(transaction, BACKUP_QUARANTINE_ROLE, index));
            index++;
        }
        return quarantines;
    }

    /// Returns transaction-specific restore handoffs indexed by original installed paths.
    ///
    /// @param backups original paths mapped to backup paths
    /// @param transaction active transaction
    /// @return mutable original-to-restore-handoff path map
    private Map<Path, Path> backupRestoreHandoffs(Map<Path, Path> backups, Transaction transaction) {
        Map<Path, Path> handoffs = new LinkedHashMap<>();
        int index = 0;
        for (Path original : backups.keySet()) {
            handoffs.put(original, quarantinePath(transaction, BACKUP_RESTORE_ROLE, index));
            index++;
        }
        return handoffs;
    }

    /// Returns whether an old package is already restored and no backup object remains to move.
    ///
    /// @param original stable original path
    /// @param backups original paths mapped to backup paths
    /// @param backupDigests expected old-package digests
    /// @param backupQuarantines original paths mapped to recovery quarantines
    /// @param backupRestoreHandoffs original paths mapped to restore handoffs
    /// @return whether the original contains the old bytes and every backup staging location is absent
    private static boolean isBackupRestored(
            Path original,
            Map<Path, Path> backups,
            Map<Path, String> backupDigests,
            Map<Path, Path> backupQuarantines,
            Map<Path, Path> backupRestoreHandoffs
    ) {
        @Nullable Path backup = backups.get(original);
        @Nullable Path quarantine = backupQuarantines.get(original);
        @Nullable Path restoreHandoff = backupRestoreHandoffs.get(original);
        @Nullable String expectedOldDigest = backupDigests.get(original);
        return backup != null
                && quarantine != null
                && restoreHandoff != null
                && expectedOldDigest != null
                && !pathExists(backup)
                && !pathExists(quarantine)
                && !pathExists(restoreHandoff)
                && matchesDigest(original, expectedOldDigest);
    }

    /// Creates a unique target-to-digest map and rejects duplicate targets.
    ///
    /// @param targets stable publication targets
    /// @param targetDigests expected new-package digests
    /// @return mutable target-to-digest map
    /// @throws IOException if list sizes differ or a target path is duplicated
    private static Map<Path, String> targetDigestByPath(
            @Unmodifiable List<Path> targets,
            @Unmodifiable List<String> targetDigests
    ) throws IOException {
        if (targets.size() != targetDigests.size()) {
            throw new IOException("Plugin transaction target and digest counts differ");
        }
        Map<Path, String> digests = new LinkedHashMap<>();
        for (int index = 0; index < targets.size(); index++) {
            if (digests.put(targets.get(index), targetDigests.get(index)) != null) {
                throw new IOException("Plugin transaction contains duplicate target paths: " + targets.get(index));
            }
        }
        return digests;
    }

    /// Requires target, digest, and staging lists to describe the same publication set.
    ///
    /// @param targets stable publication targets
    /// @param targetDigests expected new-package digests
    /// @param preparedPackages hidden prepared packages
    /// @throws IOException if list lengths differ
    private static void requireConsistentRecoveryLists(
            @Unmodifiable List<Path> targets,
            @Unmodifiable List<String> targetDigests,
            @Unmodifiable List<Path> preparedPackages
    ) throws IOException {
        if (targets.size() != targetDigests.size() || targets.size() != preparedPackages.size()) {
            throw new IOException("Plugin transaction recovery lists have inconsistent lengths");
        }
    }

    /// Returns a recorded digest or rejects an inconsistent journal lookup.
    ///
    /// @param expectedSha256 recorded digest, or `null` when the graph is inconsistent
    /// @param file diagnostic package path
    /// @return non-null recorded digest
    /// @throws IOException if no digest exists
    private static String requireRecordedDigest(@Nullable String expectedSha256, Path file) throws IOException {
        if (expectedSha256 == null) {
            throw new IOException("Plugin transaction package has no recorded artifact identity: " + file);
        }
        return expectedSha256;
    }

    /// Restores every verified quarantine to its original source path without replacing a current directory entry.
    ///
    /// Quarantines remain in place when an external object occupies the source path. Restoration failures are attached
    /// to the recovery failure so the journal remains unresolved and no unknown object is overwritten or deleted.
    ///
    /// @param backups original paths mapped to backup paths
    /// @param backupDigests expected old-package digests
    /// @param targets stable publication targets
    /// @param targetDigests expected new-package digests
    /// @param preparedPackages hidden prepared packages
    /// @param transaction active transaction
    /// @param recoveryFailure failure that caused recovery to stop
    private void restoreOwnedQuarantines(
            Map<Path, Path> backups,
            Map<Path, String> backupDigests,
            @Unmodifiable List<Path> targets,
            @Unmodifiable List<String> targetDigests,
            @Unmodifiable List<Path> preparedPackages,
            Transaction transaction,
            IOException recoveryFailure
    ) {
        for (int index = 0; index < targets.size(); index++) {
            restoreVerifiedIfUnoccupied(
                    quarantinePath(transaction, TARGET_DISPOSAL_ROLE, index),
                    quarantinePath(transaction, TARGET_QUARANTINE_ROLE, index),
                    targetDigests.get(index),
                    "interrupted plugin target disposal",
                    recoveryFailure
            );
            restoreVerifiedIfUnoccupied(
                    quarantinePath(transaction, TARGET_QUARANTINE_ROLE, index),
                    targets.get(index),
                    targetDigests.get(index),
                    "interrupted plugin target",
                    recoveryFailure
            );
        }
        int backupIndex = 0;
        for (Map.Entry<Path, Path> entry : backups.entrySet()) {
            restoreVerifiedIfUnoccupied(
                    quarantinePath(transaction, BACKUP_DISPOSAL_ROLE, backupIndex),
                    quarantinePath(transaction, BACKUP_QUARANTINE_ROLE, backupIndex),
                    backupDigests.get(entry.getKey()),
                    "interrupted plugin backup disposal",
                    recoveryFailure
            );
            restoreVerifiedIfUnoccupied(
                    quarantinePath(transaction, BACKUP_RESTORE_ROLE, backupIndex),
                    quarantinePath(transaction, BACKUP_QUARANTINE_ROLE, backupIndex),
                    backupDigests.get(entry.getKey()),
                    "interrupted plugin backup restore handoff",
                    recoveryFailure
            );
            restoreVerifiedIfUnoccupied(
                    quarantinePath(transaction, BACKUP_QUARANTINE_ROLE, backupIndex),
                    entry.getValue(),
                    backupDigests.get(entry.getKey()),
                    "interrupted plugin backup",
                    recoveryFailure
            );
            backupIndex++;
        }
        for (int index = 0; index < preparedPackages.size(); index++) {
            restoreVerifiedIfUnoccupied(
                    quarantinePath(transaction, PREPARED_DISPOSAL_ROLE, index),
                    quarantinePath(transaction, PREPARED_QUARANTINE_ROLE, index),
                    targetDigests.get(index),
                    "interrupted plugin staging disposal",
                    recoveryFailure
            );
            restoreVerifiedIfUnoccupied(
                    quarantinePath(transaction, PREPARED_QUARANTINE_ROLE, index),
                    preparedPackages.get(index),
                    targetDigests.get(index),
                    "interrupted plugin staging file",
                    recoveryFailure
            );
        }
    }

    /// Returns the same-directory quarantine path for one transaction artifact role and index.
    ///
    /// @param transaction active transaction
    /// @param role stable artifact role
    /// @param index zero-based role index
    /// @return normalized direct child of the plugin directory
    private Path quarantinePath(Transaction transaction, String role, int index) {
        return pluginsDirectory.resolve(".hmcl-recovery-"
                + transaction.getRecoveryNonce()
                + "-"
                + role
                + "-"
                + index
                + ".quarantine").normalize();
    }

    /// Captures one direct launcher-local document for rollback.
    ///
    /// @param file file to capture
    /// @return immutable serialized snapshot
    /// @throws IOException if an existing path is not a regular file or cannot be read
    private static FileSnapshot capture(Path file) throws IOException {
        if (!Files.exists(file)) {
            return FileSnapshot.absent();
        }
        if (!Files.isRegularFile(file)) {
            throw new IOException("Plugin transaction document is not a regular file: " + file);
        }
        return FileSnapshot.present(Base64.getEncoder().encodeToString(Files.readAllBytes(file)));
    }

    /// Restores one launcher-local document snapshot through atomic replacement.
    ///
    /// @param snapshot serialized old document
    /// @param file validated destination path
    /// @return whether restoration succeeded
    private static boolean restoreSnapshot(FileSnapshot snapshot, Path file) {
        try {
            if (!snapshot.existed()) {
                Files.deleteIfExists(file);
                forceParentDirectoryBestEffort(Objects.requireNonNull(file.getParent()));
                return true;
            }
            byte @Unmodifiable [] contents = Base64.getDecoder().decode(snapshot.contents());
            Path temporaryFile = file.resolveSibling(file.getFileName() + ".restore.tmp-" + newRecoveryNonce());
            try {
                try (FileChannel channel = FileChannel.open(
                        temporaryFile,
                        StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE,
                        LinkOption.NOFOLLOW_LINKS
                )) {
                    ByteBuffer buffer = ByteBuffer.wrap(contents);
                    while (buffer.hasRemaining()) {
                        channel.write(buffer);
                    }
                    channel.force(true);
                }
                try {
                    Files.move(
                            temporaryFile,
                            file,
                            StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING
                    );
                } catch (AtomicMoveNotSupportedException ignored) {
                    Files.move(temporaryFile, file, StandardCopyOption.REPLACE_EXISTING);
                }
                forceFileAndParent(file);
            } finally {
                Files.deleteIfExists(temporaryFile);
            }
            return true;
        } catch (IOException | IllegalArgumentException exception) {
            LOG.warning("Failed to restore interrupted plugin transaction document: " + file, exception);
            return false;
        }
    }

    /// Returns the final direct file-name component serialized in the journal.
    ///
    /// @param path direct child path
    /// @return file-name component
    private static String fileName(Path path) {
        return Objects.requireNonNull(path.getFileName(), "Plugin transaction path has no file name").toString();
    }

    /// Mutable journal model whose collections are exposed only through immutable snapshots.
    @NotNullByDefault
    static final class Transaction {
        /// Phase before all targets are committed.
        private static final String PHASE_PREPARED = "prepared";

        /// Phase after all targets are committed.
        private static final String PHASE_COMMITTED = "committed";

        /// Serialized recovery journal schema version.
        private int schemaVersion;

        /// Persistent random token used to derive recovery quarantine paths, or `null` in schema 2 journals.
        private @Nullable String recoveryNonce;

        /// Whether the final retained graph was durably validated and only owned cleanup remains.
        private @Nullable Boolean cleanupAuthorized;

        /// Current phase, or `null` in malformed files.
        private @Nullable String phase;

        /// Backup mappings, or `null` in malformed files.
        private @Nullable List<@Nullable Backup> backups;

        /// Stable target file names, or `null` in malformed files.
        private @Nullable List<@Nullable String> targetFileNames;

        /// Hidden prepared file names, or `null` in malformed files.
        private @Nullable List<@Nullable String> preparedFileNames;

        /// Expected SHA-256 digests for targets and prepared files in matching list order.
        private @Nullable List<@Nullable String> targetSha256;

        /// Old permission document restored when the prepared transaction is interrupted.
        private @Nullable FileSnapshot permissionSnapshot;

        /// Old plugin state document restored when the prepared transaction is interrupted.
        private @Nullable FileSnapshot stateSnapshot;

        /// Old storage-cleanup tombstone document restored when a removal transaction is interrupted.
        private @Nullable FileSnapshot cleanupSnapshot;

        /// Old proof-backed certification receipt document restored when a transaction is interrupted.
        private @Nullable FileSnapshot certificationReceiptSnapshot;

        /// Old runtime Provider binding document restored when a transaction is interrupted.
        private @Nullable FileSnapshot runtimeBindingSnapshot;

        /// Creates an empty transaction for Gson.
        private Transaction() {
        }

        /// Creates a prepared transaction from direct plugin-directory paths.
        ///
        /// @param backups original paths mapped to backup paths
        /// @param targets stable targets
        /// @param preparedPackages hidden prepared packages
        /// @return prepared transaction
        private static Transaction prepared(
                Map<Path, Path> backups,
                List<Path> targets,
                @Unmodifiable List<Path> preparedPackages,
                FileSnapshot permissionSnapshot,
                FileSnapshot stateSnapshot,
                FileSnapshot cleanupSnapshot,
                FileSnapshot certificationReceiptSnapshot,
                FileSnapshot runtimeBindingSnapshot
        ) throws IOException {
            Transaction transaction = new Transaction();
            transaction.schemaVersion = SCHEMA_VERSION;
            transaction.recoveryNonce = newRecoveryNonce();
            transaction.cleanupAuthorized = false;
            transaction.phase = PHASE_PREPARED;
            List<Backup> serializedBackups = new ArrayList<>();
            for (Map.Entry<Path, Path> entry : backups.entrySet()) {
                serializedBackups.add(new Backup(
                        fileName(entry.getKey()),
                        fileName(entry.getValue()),
                        PluginPackageVersions.calculateSha256(entry.getKey())
                ));
            }
            transaction.backups = List.copyOf(serializedBackups);
            transaction.targetFileNames = targets.stream().map(PluginBatchTransactionJournal::fileName).toList();
            transaction.preparedFileNames = preparedPackages.stream()
                    .map(PluginBatchTransactionJournal::fileName)
                    .toList();
            List<String> targetDigests = new ArrayList<>();
            for (Path preparedPackage : preparedPackages) {
                targetDigests.add(PluginPackageVersions.calculateSha256(preparedPackage));
            }
            transaction.targetSha256 = List.copyOf(targetDigests);
            transaction.permissionSnapshot = permissionSnapshot;
            transaction.stateSnapshot = stateSnapshot;
            transaction.cleanupSnapshot = cleanupSnapshot;
            transaction.certificationReceiptSnapshot = certificationReceiptSnapshot;
            transaction.runtimeBindingSnapshot = runtimeBindingSnapshot;
            return transaction;
        }

        /// Marks the transaction committed.
        private void markCommitted() {
            phase = PHASE_COMMITTED;
        }

        /// Returns whether the journal phase and collections are complete.
        ///
        /// @return whether recovery can consume the journal
        private boolean hasValidPhase() {
            boolean validSchema = schemaVersion == LEGACY_SCHEMA_VERSION
                    && recoveryNonce == null
                    && !Boolean.TRUE.equals(cleanupAuthorized)
                    || (schemaVersion == PRE_RECEIPT_SCHEMA_VERSION
                    || schemaVersion == PRE_RUNTIME_BINDING_SCHEMA_VERSION
                    || schemaVersion == SCHEMA_VERSION)
                    && isRecoveryNonce(recoveryNonce)
                    && cleanupAuthorized != null;
            boolean validCleanupSnapshot = schemaVersion == LEGACY_SCHEMA_VERSION
                    ? cleanupSnapshot == null || cleanupSnapshot.isValid()
                    : cleanupSnapshot != null && cleanupSnapshot.isValid();
            boolean validCertificationReceiptSnapshot = schemaVersion >= PRE_RUNTIME_BINDING_SCHEMA_VERSION
                    ? certificationReceiptSnapshot != null && certificationReceiptSnapshot.isValid()
                    : certificationReceiptSnapshot == null || certificationReceiptSnapshot.isValid();
            boolean validRuntimeBindingSnapshot = schemaVersion == SCHEMA_VERSION
                    ? runtimeBindingSnapshot != null && runtimeBindingSnapshot.isValid()
                    : runtimeBindingSnapshot == null || runtimeBindingSnapshot.isValid();
            return validSchema
                    && (PHASE_PREPARED.equals(phase) || PHASE_COMMITTED.equals(phase))
                    && backups != null
                    && backups.stream().allMatch((@Nullable Backup backup) -> backup != null && backup.isValid())
                    && targetFileNames != null
                    && targetFileNames.stream().allMatch(Objects::nonNull)
                    && preparedFileNames != null
                    && preparedFileNames.stream().allMatch(Objects::nonNull)
                    && targetSha256 != null
                    && targetSha256.size() == targetFileNames.size()
                    && targetSha256.size() == preparedFileNames.size()
                    && targetSha256.stream().allMatch(PluginBatchTransactionJournal::isSha256)
                    && permissionSnapshot != null
                    && permissionSnapshot.isValid()
                    && stateSnapshot != null
                    && stateSnapshot.isValid()
                    && validCleanupSnapshot
                    && validCertificationReceiptSnapshot
                    && validRuntimeBindingSnapshot;
        }

        /// Returns whether this valid journal must be durably upgraded before recovery mutates package paths.
        ///
        /// @return whether the journal predates certification receipt snapshots
        private boolean requiresRecoveryUpgrade() {
            return schemaVersion == LEGACY_SCHEMA_VERSION
                    || schemaVersion == PRE_RECEIPT_SCHEMA_VERSION
                    || schemaVersion == PRE_RUNTIME_BINDING_SCHEMA_VERSION;
        }

        /// Upgrades a schema 2 or 3 transaction while preserving any existing quarantine and cleanup boundary.
        private void upgradeForRecovery() {
            if (cleanupSnapshot == null) {
                cleanupSnapshot = FileSnapshot.absent();
            }
            if (certificationReceiptSnapshot == null) {
                certificationReceiptSnapshot = FileSnapshot.absent();
            }
            runtimeBindingSnapshot = FileSnapshot.absent();
            if (schemaVersion == LEGACY_SCHEMA_VERSION) {
                recoveryNonce = newRecoveryNonce();
                cleanupAuthorized = false;
            }
            schemaVersion = SCHEMA_VERSION;
        }

        /// Marks that retained package and document state was validated before destructive cleanup.
        private void markCleanupAuthorized() {
            cleanupAuthorized = true;
        }

        /// Returns whether recovery may finish owned cleanup without rereading externally mutable targets.
        ///
        /// @return whether the durable final validation boundary was crossed
        private boolean isCleanupAuthorized() {
            return Boolean.TRUE.equals(cleanupAuthorized);
        }

        /// Returns the validated persistent recovery nonce.
        ///
        /// @return lower-case hexadecimal recovery nonce
        private String getRecoveryNonce() {
            return Objects.requireNonNull(recoveryNonce);
        }

        /// Returns whether every new target was committed.
        ///
        /// @return committed phase state
        private boolean isCommitted() {
            return PHASE_COMMITTED.equals(phase);
        }

        /// Returns immutable backup mappings.
        ///
        /// @return backup mappings
        private @Unmodifiable List<Backup> getBackups() {
            return Objects.requireNonNull(backups).stream().map(Objects::requireNonNull).toList();
        }

        /// Returns immutable target file names.
        ///
        /// @return target file names
        private @Unmodifiable List<String> getTargetFileNames() {
            return Objects.requireNonNull(targetFileNames).stream().map(Objects::requireNonNull).toList();
        }

        /// Returns immutable prepared file names.
        ///
        /// @return prepared file names
        private @Unmodifiable List<String> getPreparedFileNames() {
            return Objects.requireNonNull(preparedFileNames).stream().map(Objects::requireNonNull).toList();
        }

        /// Returns immutable expected target and staging package digests.
        ///
        /// @return SHA-256 values in target order
        private @Unmodifiable List<String> getTargetSha256() {
            return Objects.requireNonNull(targetSha256).stream().map(Objects::requireNonNull).toList();
        }

        /// Returns the captured permission document.
        ///
        /// @return permission snapshot
        private FileSnapshot getPermissionSnapshot() {
            return Objects.requireNonNull(permissionSnapshot);
        }

        /// Returns the captured plugin state document.
        ///
        /// @return state snapshot
        private FileSnapshot getStateSnapshot() {
            return Objects.requireNonNull(stateSnapshot);
        }

        /// Returns the captured pending storage-cleanup document.
        ///
        /// Missing legacy values are normalized to an absent snapshot during the durable schema upgrade.
        ///
        /// @return cleanup snapshot
        private FileSnapshot getCleanupSnapshot() {
            return Objects.requireNonNull(cleanupSnapshot);
        }

        /// Returns the captured proof-backed certification receipt document.
        ///
        /// Missing schema 2 and 3 values are normalized to an absent snapshot during durable upgrade.
        ///
        /// @return certification receipt snapshot
        private FileSnapshot getCertificationReceiptSnapshot() {
            return Objects.requireNonNull(certificationReceiptSnapshot);
        }

        /// Returns the captured runtime Provider binding document.
        ///
        /// Missing older-schema values are normalized to an absent snapshot during durable upgrade.
        ///
        /// @return runtime binding snapshot
        private FileSnapshot getRuntimeBindingSnapshot() {
            return Objects.requireNonNull(runtimeBindingSnapshot);
        }

    }

    /// Serialized snapshot of one fixed launcher-local transaction document.
    @NotNullByDefault
    private static final class FileSnapshot {
        /// Whether the document existed before the transaction, or `null` when the field was omitted.
        private @Nullable Boolean existed;

        /// Base64-encoded old contents, or `null` when the document was absent.
        private @Nullable String contents;

        /// Creates an empty snapshot for Gson.
        private FileSnapshot() {
        }

        /// Creates one complete snapshot.
        ///
        /// @param existed whether the document existed
        /// @param contents Base64 contents or `null`
        private FileSnapshot(boolean existed, @Nullable String contents) {
            this.existed = existed;
            this.contents = contents;
        }

        /// Creates an absent-file snapshot.
        ///
        /// @return absent snapshot
        private static FileSnapshot absent() {
            return new FileSnapshot(false, null);
        }

        /// Creates a present-file snapshot.
        ///
        /// @param contents Base64 old contents
        /// @return present snapshot
        private static FileSnapshot present(String contents) {
            return new FileSnapshot(true, contents);
        }

        /// Returns whether serialized fields form a coherent snapshot.
        ///
        /// @return structural validity
        private boolean isValid() {
            if (existed == null || existed != (contents != null)) {
                return false;
            }
            if (contents == null) {
                return true;
            }
            try {
                Base64.getDecoder().decode(contents);
                return true;
            } catch (IllegalArgumentException exception) {
                return false;
            }
        }

        /// Returns whether the old document existed.
        ///
        /// @return old existence state
        private boolean existed() {
            return Boolean.TRUE.equals(existed);
        }

        /// Returns Base64 old contents.
        ///
        /// @return old contents
        private String contents() {
            return Objects.requireNonNull(contents);
        }

    }

    /// One original-to-backup file-name mapping.
    @NotNullByDefault
    private static final class Backup {
        /// Original installed file name, or `null` in malformed files.
        private @Nullable String originalFileName;

        /// Hidden backup file name, or `null` in malformed files.
        private @Nullable String backupFileName;

        /// SHA-256 of the original installed package, or `null` in malformed files.
        private @Nullable String originalSha256;

        /// Creates an empty mapping for Gson.
        private Backup() {
        }

        /// Creates one complete backup mapping.
        ///
        /// @param originalFileName original installed file name
        /// @param backupFileName hidden backup file name
        /// @param originalSha256 SHA-256 of the original installed package
        private Backup(String originalFileName, String backupFileName, String originalSha256) {
            this.originalFileName = originalFileName;
            this.backupFileName = backupFileName;
            this.originalSha256 = originalSha256;
        }

        /// Returns whether both file names are present.
        ///
        /// @return structural validity
        private boolean isValid() {
            return originalFileName != null
                    && backupFileName != null
                    && PluginBatchTransactionJournal.isSha256(originalSha256);
        }

        /// Returns the original file name.
        ///
        /// @return original file name
        private String getOriginalFileName() {
            return Objects.requireNonNull(originalFileName);
        }

        /// Returns the backup file name.
        ///
        /// @return backup file name
        private String getBackupFileName() {
            return Objects.requireNonNull(backupFileName);
        }

        /// Returns the expected original installed package digest.
        ///
        /// @return lower-case SHA-256
        private String getOriginalSha256() {
            return Objects.requireNonNull(originalSha256);
        }
    }
}
