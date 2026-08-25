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
package org.jackhuang.hmcl.plugin.protector;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

/// Loads and durably replaces one bounded startup recovery document, using atomic publication when supported.
@NotNullByDefault
public final class PluginRecoveryStore {
    /// Describes whether one save definitely published, definitely did not publish, or cannot be classified.
    @NotNullByDefault
    public enum PublicationOutcome {
        /// The requested record is the exact current recovery target.
        PUBLISHED,

        /// The requested record was not published and the exact previous target state was retained or repaired.
        NOT_PUBLISHED,

        /// Target or launcher-home state could not be classified or restored after a possible publication.
        INDETERMINATE
    }

    /// Describes which filesystem durability barriers are known to have completed for one outcome.
    @NotNullByDefault
    public enum PublicationDurability {
        /// Durability does not apply because the requested record was definitely not published.
        NOT_APPLICABLE,

        /// Durability is unknown because a published target could not be forced or state is indeterminate.
        UNKNOWN,

        /// Both file content and parent-directory metadata were forced to stable storage.
        FILE_AND_DIRECTORY_FORCED,

        /// File content was forced, but the provider could not force parent-directory metadata.
        FILE_FORCED_ONLY
    }

    /// Typed publication state returned or attached to a save failure.
    ///
    /// @param outcome publication classification
    /// @param durability completed durability barriers for that classification
    @NotNullByDefault
    public record PublicationResult(PublicationOutcome outcome, PublicationDurability durability) {
        /// Validates one coherent publication classification.
        public PublicationResult {
            boolean valid = switch (outcome) {
                case PUBLISHED -> durability == PublicationDurability.UNKNOWN
                        || durability == PublicationDurability.FILE_AND_DIRECTORY_FORCED
                        || durability == PublicationDurability.FILE_FORCED_ONLY;
                case NOT_PUBLISHED -> durability == PublicationDurability.NOT_APPLICABLE;
                case INDETERMINATE -> durability == PublicationDurability.UNKNOWN;
            };
            if (!valid) {
                throw new IllegalArgumentException("Publication outcome and durability are inconsistent");
            }
        }
    }

    /// Save failure carrying an explicit publication classification for safe caller recovery.
    ///
    /// Task 10 may retry only a `NOT_PUBLISHED` result. `PUBLISHED` and `INDETERMINATE` must be treated as already
    /// published on the safe side so a retry cannot overwrite a record that may already be durable.
    @NotNullByDefault
    public static final class PublicationException extends IOException {
        /// Explicit publication classification at the failure boundary.
        private final PublicationResult result;

        /// Creates one classified save failure without exposing record content.
        ///
        /// @param message fixed safe diagnostic
        /// @param result explicit publication classification
        /// @param cause filesystem failure
        private PublicationException(String message, PublicationResult result, IOException cause) {
            super(message, cause);
            this.result = result;
        }

        /// Returns the publication classification callers must use for retry decisions.
        ///
        /// @return explicit publication classification
        public PublicationResult result() {
            return result;
        }
    }

    /// Launcher-local recovery document filename.
    public static final String FILE_NAME = "plugin-startup-recovery.json";

    /// Maximum accepted or emitted recovery-document size.
    public static final int MAX_RECOVERY_BYTES = 1024 * 1024;

    /// Reserved sibling prefix for uniquely owned crash-recoverable temporary files.
    static final String TEMP_FILE_PREFIX = FILE_NAME + ".tmp-";

    /// Stable repository lock filename shared by every launcher process using the recovery store.
    static final String LOCK_FILE_NAME = "plugin-startup-recovery.lock";

    /// Current strict recovery-document schema version.
    private static final int SCHEMA_VERSION = 1;

    /// Unsigned canonical JSON integer syntax.
    private static final Pattern UNSIGNED_INTEGER_PATTERN = Pattern.compile("0|[1-9][0-9]*");

    /// Compact JSON encoder preserving explicit field insertion order.
    private static final Gson GSON = new GsonBuilder().serializeNulls().create();

    /// Stable JVM-wide operation locks keyed by validated no-follow launcher-home real paths.
    private static final ConcurrentMap<Path, ReentrantLock> HOME_LOCKS = new ConcurrentHashMap<>();

    /// Definite non-publication result used for all pre-publication and successfully repaired failures.
    private static final PublicationResult NOT_PUBLISHED = new PublicationResult(
            PublicationOutcome.NOT_PUBLISHED,
            PublicationDurability.NOT_APPLICABLE
    );

    /// Indeterminate result used when publication or repair cannot be classified safely.
    private static final PublicationResult INDETERMINATE = new PublicationResult(
            PublicationOutcome.INDETERMINATE,
            PublicationDurability.UNKNOWN
    );

    /// Known publication whose reconciled target could not complete a verified file durability barrier.
    private static final PublicationResult PUBLISHED_UNKNOWN = new PublicationResult(
            PublicationOutcome.PUBLISHED,
            PublicationDurability.UNKNOWN
    );

    /// Normalized launcher-local directory containing the exact recovery targets.
    private final Path launcherHome;

    /// Exact recovery document path.
    private final Path recoveryFile;

    /// Stable recovery operation lock-file path.
    private final Path operationLockFile;

    /// Injectable filesystem operations used for path identity and durable publication.
    private final FileOperations fileOperations;

    /// Creates a recovery store rooted at one launcher-local directory.
    ///
    /// @param launcherHome launcher-local directory
    public PluginRecoveryStore(Path launcherHome) {
        this(launcherHome, new FileOperations());
    }

    /// Creates a recovery store with package-private injectable filesystem operations.
    ///
    /// @param launcherHome launcher-local directory
    /// @param fileOperations filesystem operations and move strategy
    PluginRecoveryStore(Path launcherHome, FileOperations fileOperations) {
        this.launcherHome = launcherHome.toAbsolutePath().normalize();
        this.recoveryFile = this.launcherHome.resolve(FILE_NAME);
        this.operationLockFile = this.launcherHome.resolve(LOCK_FILE_NAME);
        this.fileOperations = fileOperations;
    }

    /// Loads the exact recovery record through one bounded no-follow file handle.
    ///
    /// @return stored record, or empty when the direct path was absent at handle acquisition
    /// @throws IOException if the present document is unsafe, corrupt, truncated, unsupported, or unreadable
    public Optional<PluginRecoveryRecord> load() throws IOException {
        @Nullable HomeIdentity homeIdentity = requireSafeLauncherHome(false);
        if (homeIdentity == null) {
            return Optional.empty();
        }
        verifyHomeIdentity(homeIdentity);
        @Nullable String json = readRecoveryFile();
        verifyHomeIdentity(homeIdentity);
        if (json == null) {
            return Optional.empty();
        }
        return Optional.of(parseRecord(json));
    }

    /// Forces one complete record to a sibling file and replaces the recovery document with explicit outcome state.
    ///
    /// The successful move is the publication boundary. Atomic replacement is preferred and ordinary replacement is
    /// used only when the provider explicitly reports that atomic moves are unsupported. Move failures are reconciled
    /// against exact bounded snapshots of the prior and requested bytes. An ambiguous target is repaired atomically
    /// from the prior snapshot when possible. Parent-directory metadata forcing occurs after publication.
    ///
    /// A normal return is always `PUBLISHED`. A `PublicationException` explicitly reports `NOT_PUBLISHED`,
    /// `PUBLISHED`, or `INDETERMINATE`. Task 10 may retry only `NOT_PUBLISHED`; it must treat `PUBLISHED` and
    /// `INDETERMINATE` as already published so a retry cannot destroy a record that may have reached the target.
    ///
    /// @param record complete validated record
    /// @return explicit published outcome and completed durability barriers
    /// @throws PublicationException if publication fails or cannot be classified safely
    public PublicationResult save(PluginRecoveryRecord record) throws PublicationException {
        byte @Unmodifiable [] bytes = encodeRecord(record).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_RECOVERY_BYTES) {
            throw publicationFailure(NOT_PUBLISHED, "Plugin recovery document is too large", new IOException());
        }
        @Nullable HomeIdentity homeIdentity;
        try {
            homeIdentity = requireSafeLauncherHome(true);
        } catch (IOException failure) {
            throw publicationFailure(NOT_PUBLISHED, "Plugin recovery directory is unavailable", failure);
        }
        if (homeIdentity == null) {
            throw publicationFailure(
                    NOT_PUBLISHED,
                    "Plugin recovery directory could not be created safely",
                    new IOException()
            );
        }
        try (OperationLease ignored = acquireOperationLease(homeIdentity)) {
            return saveLocked(bytes, homeIdentity);
        } catch (PublicationException exception) {
            throw exception;
        } catch (IOException failure) {
            throw publicationFailure(NOT_PUBLISHED, "Plugin recovery operation lock is unavailable", failure);
        }
    }

    /// Publishes one encoded record while holding the stable JVM-wide launcher-home operation lock.
    ///
    /// @param bytes bounded encoded recovery document
    /// @param homeIdentity validated launcher-home identity
    /// @return explicit published outcome and completed durability barriers
    /// @throws PublicationException if publication fails or cannot be classified safely
    private PublicationResult saveLocked(byte @Unmodifiable [] bytes, HomeIdentity homeIdentity)
            throws PublicationException {
        TargetSnapshot previous;
        try {
            verifyHomeIdentity(homeIdentity);
            previous = readTargetSnapshot();
            verifyHomeIdentity(homeIdentity);
        } catch (IOException failure) {
            throw publicationFailure(NOT_PUBLISHED, "Plugin recovery target could not be inspected", failure);
        }
        Path temporaryFile = launcherHome.resolve(TEMP_FILE_PREFIX + UUID.randomUUID());
        boolean createdTemporaryFile = false;
        try {
            try (FileChannel channel = fileOperations.createNewWritable(temporaryFile)) {
                createdTemporaryFile = true;
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                fileOperations.forceTemporary(channel);
            }
            verifyHomeIdentity(homeIdentity);
        } catch (IOException failure) {
            PublicationException exception = publicationFailure(
                    NOT_PUBLISHED,
                    "Plugin recovery publication failed before replacement",
                    failure
            );
            cleanupOwnedTemporary(temporaryFile, createdTemporaryFile, homeIdentity, exception);
            throw exception;
        }

        try {
            try {
                fileOperations.atomicReplace(temporaryFile, recoveryFile);
            } catch (AtomicMoveNotSupportedException ignored) {
                fileOperations.replace(temporaryFile, recoveryFile);
            }
            createdTemporaryFile = false;
        } catch (IOException moveFailure) {
            return reconcileMoveFailure(bytes, previous, temporaryFile, createdTemporaryFile, homeIdentity, moveFailure);
        }

        try {
            verifyHomeIdentity(homeIdentity);
        } catch (IOException failure) {
            throw publicationFailure(
                    INDETERMINATE,
                    "Plugin recovery publication completed but home identity is indeterminate",
                    failure
            );
        }
        return publishedResult(forceParentDirectoryBestEffort(launcherHome));
    }

    /// Reconciles an exception from a move that may have changed the target before reporting failure.
    ///
    /// @param requested exact requested bytes
    /// @param previous exact bounded target snapshot before the move
    /// @param temporaryFile exact temporary path owned by this save
    /// @param createdTemporaryFile whether this save created its temporary path
    /// @param homeIdentity validated launcher-home identity
    /// @param moveFailure ambiguous move failure
    /// @return published result when the target contains the exact requested bytes
    /// @throws PublicationException when the prior state is retained, repaired, or cannot be restored safely
    private PublicationResult reconcileMoveFailure(
            byte @Unmodifiable [] requested,
            TargetSnapshot previous,
            Path temporaryFile,
            boolean createdTemporaryFile,
            HomeIdentity homeIdentity,
            IOException moveFailure
    ) throws PublicationException {
        TargetSnapshot current;
        try {
            verifyHomeIdentity(homeIdentity);
            current = readTargetSnapshot();
            verifyHomeIdentity(homeIdentity);
        } catch (IOException inspectionFailure) {
            PublicationException exception = publicationFailure(
                    INDETERMINATE,
                    "Plugin recovery target is indeterminate after replacement failure",
                    moveFailure
            );
            exception.addSuppressed(inspectionFailure);
            cleanupOwnedTemporary(temporaryFile, createdTemporaryFile, homeIdentity, exception);
            throw exception;
        }

        if (current.matches(requested)) {
            PublicationResult result = forceReconciledPublication(homeIdentity);
            try {
                if (createdTemporaryFile) {
                    verifyHomeIdentity(homeIdentity);
                    fileOperations.deleteOwnedTemporary(temporaryFile);
                }
            } catch (IOException cleanupFailure) {
                PublicationException exception = publicationFailure(
                        result,
                        "Plugin recovery record was published but temporary cleanup failed",
                        cleanupFailure
                );
                exception.addSuppressed(moveFailure);
                throw exception;
            }
            return result;
        }

        if (current.equalsContent(previous)) {
            PublicationException exception = publicationFailure(
                    NOT_PUBLISHED,
                    "Plugin recovery replacement did not publish the requested record",
                    moveFailure
            );
            cleanupOwnedTemporary(temporaryFile, createdTemporaryFile, homeIdentity, exception);
            throw exception;
        }

        try {
            repairPreviousSnapshot(previous, homeIdentity);
        } catch (IOException repairFailure) {
            PublicationException exception = publicationFailure(
                    INDETERMINATE,
                    "Plugin recovery target could not be restored after replacement failure",
                    moveFailure
            );
            exception.addSuppressed(repairFailure);
            cleanupOwnedTemporary(temporaryFile, createdTemporaryFile, homeIdentity, exception);
            throw exception;
        }

        PublicationException exception = publicationFailure(
                NOT_PUBLISHED,
                "Plugin recovery target was restored after replacement failure",
                moveFailure
        );
        cleanupOwnedTemporary(temporaryFile, createdTemporaryFile, homeIdentity, exception);
        throw exception;
    }

    /// Forces a target recognized after an ambiguous move before advertising file durability.
    ///
    /// @param homeIdentity launcher-home identity held across reconciliation
    /// @return completed durability barriers, or known publication with unknown durability when forcing fails
    private PublicationResult forceReconciledPublication(HomeIdentity homeIdentity) {
        try {
            verifyHomeIdentity(homeIdentity);
            if (!isRegularRecoveryTarget()) {
                return PUBLISHED_UNKNOWN;
            }
            fileOperations.forcePublishedTarget(recoveryFile);
            verifyHomeIdentity(homeIdentity);
            return publishedResult(forceParentDirectoryBestEffort(launcherHome));
        } catch (IOException ignored) {
            return PUBLISHED_UNKNOWN;
        }
    }

    /// Restores the exact bounded target snapshot using atomic replacement when prior bytes existed.
    ///
    /// An absent prior target is restored by deleting only the exact regular or symbolic-link target. Existing bytes
    /// are written and forced through another uniquely owned sibling, then moved atomically without a weaker fallback.
    ///
    /// @param previous exact target state before the failed publication
    /// @param homeIdentity validated launcher-home identity
    /// @throws IOException if repair, atomic movement, identity validation, or exact verification fails
    private void repairPreviousSnapshot(TargetSnapshot previous, HomeIdentity homeIdentity) throws IOException {
        if (!previous.present()) {
            deleteExactTarget(recoveryFile);
            verifyHomeIdentity(homeIdentity);
        } else {
            Path repairFile = launcherHome.resolve(TEMP_FILE_PREFIX + UUID.randomUUID());
            boolean createdRepairFile = false;
            @Nullable IOException failure = null;
            try {
                try (FileChannel channel = fileOperations.createNewWritable(repairFile)) {
                    createdRepairFile = true;
                    ByteBuffer buffer = ByteBuffer.wrap(previous.bytes());
                    while (buffer.hasRemaining()) {
                        channel.write(buffer);
                    }
                    fileOperations.forceTemporary(channel);
                }
                verifyHomeIdentity(homeIdentity);
                fileOperations.atomicReplace(repairFile, recoveryFile);
                createdRepairFile = false;
                verifyHomeIdentity(homeIdentity);
            } catch (IOException repairFailure) {
                failure = repairFailure;
                throw repairFailure;
            } finally {
                if (createdRepairFile) {
                    try {
                        verifyHomeIdentity(homeIdentity);
                        fileOperations.deleteOwnedTemporary(repairFile);
                    } catch (IOException cleanupFailure) {
                        if (failure != null) {
                            failure.addSuppressed(cleanupFailure);
                        } else {
                            throw cleanupFailure;
                        }
                    }
                }
            }
        }

        TargetSnapshot repaired = readTargetSnapshot();
        verifyHomeIdentity(homeIdentity);
        if (!repaired.equalsContent(previous)) {
            throw new IOException("Plugin recovery target repair could not be verified");
        }
        forceParentDirectoryBestEffort(launcherHome);
    }

    /// Adds owned-temporary cleanup failure to an already classified publication failure.
    ///
    /// @param temporaryFile exact temporary path owned by this save
    /// @param createdTemporaryFile whether this save created its temporary path
    /// @param homeIdentity launcher-home identity captured before creation
    /// @param exception classified primary failure
    private void cleanupOwnedTemporary(
            Path temporaryFile,
            boolean createdTemporaryFile,
            HomeIdentity homeIdentity,
            PublicationException exception
    ) {
        if (!createdTemporaryFile) {
            return;
        }
        try {
            verifyHomeIdentity(homeIdentity);
            fileOperations.deleteOwnedTemporary(temporaryFile);
        } catch (IOException cleanupFailure) {
            exception.addSuppressed(cleanupFailure);
        }
    }

    /// Removes only the exact recovery document and never scans or claims residual temporary-looking siblings.
    ///
    /// Parent-directory metadata is forced when the filesystem provider supports directory channels; failure of that
    /// post-removal durability barrier is best effort and does not turn an already completed removal into a failure.
    ///
    /// @throws IOException if an exact target exists with an unsafe file type or cannot be removed
    public void clear() throws IOException {
        @Nullable HomeIdentity homeIdentity = requireSafeLauncherHome(false);
        if (homeIdentity == null) {
            return;
        }
        try (OperationLease ignored = acquireOperationLease(homeIdentity)) {
            verifyHomeIdentity(homeIdentity);
            deleteExactTarget(recoveryFile);
            verifyHomeIdentity(homeIdentity);
            forceParentDirectoryBestEffort(launcherHome);
        }
    }

    /// Acquires the shared JVM lock and stable OS file lock for one validated launcher home.
    ///
    /// The JVM lock prevents ordinary overlapping-lock exceptions between store instances in this process. A retry
    /// handles an independently opened lock in this JVM using the same semantics as another process holding the file.
    ///
    /// @param homeIdentity validated launcher-home identity
    /// @return held operation lease
    /// @throws IOException if the lock file is unsafe, acquisition is interrupted, or opening fails
    private OperationLease acquireOperationLease(HomeIdentity homeIdentity) throws IOException {
        ReentrantLock processLock = operationLock(homeIdentity);
        processLock.lock();
        @Nullable FileChannel channel = null;
        try {
            try {
                BasicFileAttributes attributes = fileOperations.readAttributes(operationLockFile);
                if (!attributes.isRegularFile() || attributes.isSymbolicLink()) {
                    throw new IOException("Plugin recovery operation lock has an unsafe file type");
                }
            } catch (NoSuchFileException ignored) {
                // Exclusive JVM ownership below safely creates the stable lock file when absent.
            }
            channel = fileOperations.openOperationLock(operationLockFile);
            while (true) {
                try {
                    @Nullable FileLock fileLock = channel.tryLock();
                    if (fileLock != null) {
                        return new OperationLease(processLock, channel, fileLock);
                    }
                } catch (OverlappingFileLockException ignored) {
                    // A test harness or independent subsystem in this JVM owns the same OS lock.
                }
                try {
                    Thread.sleep(10L);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Plugin recovery operation lock acquisition was interrupted", exception);
                }
            }
        } catch (IOException | RuntimeException failure) {
            if (channel != null) {
                try {
                    channel.close();
                } catch (IOException cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
            processLock.unlock();
            throw failure;
        }
    }

    /// Returns the permanent JVM-wide lock for one validated launcher-home real path.
    ///
    /// Keys are intentionally retained for process lifetime so removing an idle lock cannot split synchronization
    /// identity from a thread that has resolved the same lock but has not yet acquired it.
    ///
    /// @param homeIdentity validated launcher-home identity
    /// @return shared operation lock
    private static ReentrantLock operationLock(HomeIdentity homeIdentity) {
        List<ComponentIdentity> components = homeIdentity.components();
        Path realHome = components.get(components.size() - 1).realPath();
        return HOME_LOCKS.computeIfAbsent(realHome, ignored -> new ReentrantLock());
    }

    /// Validates every existing launcher-home component without following links or observable reparse redirects.
    ///
    /// @param create whether to create an absent direct launcher-home path
    /// @return whether the complete validated launcher-home directory exists
    /// @throws IOException if a component redirects, has an unsafe type, changes during creation, or cannot be read
    private @Nullable HomeIdentity requireSafeLauncherHome(boolean create) throws IOException {
        @Nullable HomeIdentity identity = captureExistingHomeIdentity();
        if (identity == null && create) {
            Files.createDirectories(launcherHome);
            identity = captureExistingHomeIdentity();
        }
        if (create && identity == null) {
            throw new IOException("Plugin recovery directory could not be created safely");
        }
        return identity;
    }

    /// Walks the absolute normalized launcher-home path and rejects every observable redirecting component.
    ///
    /// Comparing followed and no-follow real paths detects Windows junctions and other reparse points that Java may
    /// expose as directories rather than symbolic links. This validates static path state; operation-time identity
    /// revalidation separately detects observable directory replacement races.
    ///
    /// @return whether every launcher-home component currently exists as a direct directory
    /// @throws IOException if an existing component is unsafe or cannot be inspected
    private @Nullable HomeIdentity captureExistingHomeIdentity() throws IOException {
        if (!launcherHome.isAbsolute() || !launcherHome.equals(launcherHome.normalize())) {
            throw new IOException("Plugin recovery directory path is not absolute and normalized");
        }
        @Nullable Path root = launcherHome.getRoot();
        if (root == null) {
            throw new IOException("Plugin recovery directory has no filesystem root");
        }

        Path component = root;
        List<ComponentIdentity> components = new ArrayList<>();
        try {
            components.add(captureDirectDirectory(component));
        } catch (NoSuchFileException ignored) {
            return null;
        }
        for (Path name : root.relativize(launcherHome)) {
            component = component.resolve(name);
            try {
                components.add(captureDirectDirectory(component));
            } catch (NoSuchFileException ignored) {
                return null;
            }
        }
        return new HomeIdentity(List.copyOf(components));
    }

    /// Rejects one path component unless it is a direct non-redirecting directory.
    ///
    /// @param component absolute existing path component
    /// @throws IOException if the component is a link, observable reparse redirect, or another file type
    private ComponentIdentity captureDirectDirectory(Path component) throws IOException {
        BasicFileAttributes attributes = fileOperations.readAttributes(component);
        if (attributes.isSymbolicLink() || !attributes.isDirectory()) {
            throw new IOException("Plugin recovery directory contains an unsafe path component");
        }
        Path noFollowRealPath = fileOperations.toRealPath(component, LinkOption.NOFOLLOW_LINKS);
        Path followedRealPath = fileOperations.toRealPath(component);
        if (!noFollowRealPath.equals(followedRealPath)) {
            throw new IOException("Plugin recovery directory contains a redirecting path component");
        }
        return new ComponentIdentity(
                component,
                noFollowRealPath,
                attributes.fileKey(),
                attributes.creationTime()
        );
    }

    /// Re-captures the complete launcher-home identity and rejects disappearance, replacement, or redirection.
    ///
    /// @param expected identity captured before the operation
    /// @throws IOException if any path component changed or became unsafe
    private void verifyHomeIdentity(HomeIdentity expected) throws IOException {
        @Nullable HomeIdentity actual = captureExistingHomeIdentity();
        if (!expected.equals(actual)) {
            throw new IOException("Plugin recovery directory identity changed during an operation");
        }
    }

    /// Reads one bounded direct recovery file and reports malformed UTF-8.
    ///
    /// @return decoded JSON, or `null` when absent at handle acquisition
    /// @throws IOException if the direct path cannot be safely read
    private @Nullable String readRecoveryFile() throws IOException {
        if (!isRegularRecoveryTarget()) {
            return null;
        }
        try (FileChannel channel = fileOperations.openReadable(recoveryFile)) {
            if (channel.size() > MAX_RECOVERY_BYTES) {
                throw new IOException("Plugin recovery document is too large");
            }
            ByteBuffer buffer = ByteBuffer.allocate(MAX_RECOVERY_BYTES + 1);
            while (buffer.hasRemaining() && channel.read(buffer) != -1) {
                // The sentinel byte bounds a file that grows after the same no-follow handle is opened.
            }
            if (buffer.position() > MAX_RECOVERY_BYTES) {
                throw new IOException("Plugin recovery document is too large");
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

    /// Reads an exact bounded target snapshot through one no-follow handle without parsing its content.
    ///
    /// @return present exact bytes or a definite absent snapshot
    /// @throws IOException if the direct target is unsafe, oversized, unstable, or unreadable
    private TargetSnapshot readTargetSnapshot() throws IOException {
        if (!isRegularRecoveryTarget()) {
            return TargetSnapshot.absent();
        }
        try (FileChannel channel = fileOperations.openReadable(recoveryFile)) {
            if (channel.size() > MAX_RECOVERY_BYTES) {
                throw new IOException("Plugin recovery document is too large to snapshot");
            }
            ByteBuffer buffer = ByteBuffer.allocate(MAX_RECOVERY_BYTES + 1);
            while (buffer.hasRemaining() && channel.read(buffer) != -1) {
                // The sentinel byte detects growth through the already-open no-follow handle.
            }
            if (buffer.position() > MAX_RECOVERY_BYTES) {
                throw new IOException("Plugin recovery document is too large to snapshot");
            }
            byte[] bytes = new byte[buffer.position()];
            buffer.flip();
            buffer.get(bytes);
            return new TargetSnapshot(true, bytes);
        } catch (NoSuchFileException ignored) {
            return TargetSnapshot.absent();
        }
    }

    /// Classifies the direct recovery target before opening a potentially blocking read handle.
    ///
    /// @return `true` for an existing regular file, or `false` when definitely absent
    /// @throws IOException if target attributes are uncertain or identify a non-regular entry
    private boolean isRegularRecoveryTarget() throws IOException {
        try {
            BasicFileAttributes attributes = fileOperations.readAttributes(recoveryFile);
            if (!attributes.isRegularFile()) {
                throw new IOException("Plugin recovery target is not a regular file");
            }
            return true;
        } catch (NoSuchFileException ignored) {
            return false;
        }
    }

    /// Serializes one record in stable explicit field order.
    ///
    /// @param record validated record
    /// @return compact JSON document
    private static String encodeRecord(PluginRecoveryRecord record) {
        JsonObject document = new JsonObject();
        document.addProperty("schemaVersion", SCHEMA_VERSION);
        document.addProperty("failureTimestampEpochMillis", record.failureTimestampEpochMillis());
        document.addProperty("failureCategory", record.failureCategory().wireName());
        document.addProperty("failureReason", record.failureReason().wireName());
        document.addProperty("lastStage", record.lastStage().wireName());
        document.addProperty("lastHeartbeatMonotonicNanos", record.lastHeartbeatMonotonicNanos());
        addNullableString(document, "activeProviderId", record.activeProviderId());
        addNullableString(document, "activePluginId", record.activePluginId());
        addNullableString(document, "launcherLogReference", record.launcherLogReference());
        addNullableString(document, "diagnosticDumpReference", record.diagnosticDumpReference());
        return GSON.toJson(document);
    }

    /// Adds one explicit JSON string or JSON null property.
    ///
    /// @param object destination object
    /// @param name property name
    /// @param value property value, or `null`
    private static void addNullableString(JsonObject object, String name, @Nullable String value) {
        if (value == null) {
            object.add(name, JsonNull.INSTANCE);
        } else {
            object.addProperty(name, value);
        }
    }

    /// Strictly parses every required recovery field and rejects extensions or duplicate properties.
    ///
    /// @param json bounded valid-UTF-8 document
    /// @return validated recovery record
    /// @throws IOException if schema or record validation fails
    private static PluginRecoveryRecord parseRecord(String json) throws IOException {
        boolean hasSchemaVersion = false;
        boolean hasFailureTimestamp = false;
        boolean hasFailureCategory = false;
        boolean hasFailureReason = false;
        boolean hasLastStage = false;
        boolean hasLastHeartbeat = false;
        boolean hasActiveProvider = false;
        boolean hasActivePlugin = false;
        boolean hasLauncherLog = false;
        boolean hasDiagnosticDump = false;
        int schemaVersion = 0;
        long failureTimestamp = 0L;
        long lastHeartbeat = -1L;
        @Nullable String failureCategoryName = null;
        @Nullable String failureReasonName = null;
        @Nullable String lastStageName = null;
        @Nullable String activeProviderId = null;
        @Nullable String activePluginId = null;
        @Nullable String launcherLogReference = null;
        @Nullable String diagnosticDumpReference = null;
        Set<String> fields = new HashSet<>();

        try (JsonReader reader = new JsonReader(new StringReader(json))) {
            reader.setStrictness(Strictness.STRICT);
            reader.beginObject();
            while (reader.hasNext()) {
                String name = reader.nextName();
                if (!fields.add(name)) {
                    throw invalidRecord();
                }
                switch (name) {
                    case "schemaVersion" -> {
                        schemaVersion = readUnsignedInt(reader);
                        hasSchemaVersion = true;
                    }
                    case "failureTimestampEpochMillis" -> {
                        failureTimestamp = readUnsignedLong(reader);
                        hasFailureTimestamp = true;
                    }
                    case "failureCategory" -> {
                        failureCategoryName = readRequiredString(reader);
                        hasFailureCategory = true;
                    }
                    case "failureReason" -> {
                        failureReasonName = readRequiredString(reader);
                        hasFailureReason = true;
                    }
                    case "lastStage" -> {
                        lastStageName = readRequiredString(reader);
                        hasLastStage = true;
                    }
                    case "lastHeartbeatMonotonicNanos" -> {
                        lastHeartbeat = readUnsignedLong(reader);
                        hasLastHeartbeat = true;
                    }
                    case "activeProviderId" -> {
                        activeProviderId = readNullableString(reader);
                        hasActiveProvider = true;
                    }
                    case "activePluginId" -> {
                        activePluginId = readNullableString(reader);
                        hasActivePlugin = true;
                    }
                    case "launcherLogReference" -> {
                        launcherLogReference = readNullableString(reader);
                        hasLauncherLog = true;
                    }
                    case "diagnosticDumpReference" -> {
                        diagnosticDumpReference = readNullableString(reader);
                        hasDiagnosticDump = true;
                    }
                    default -> throw invalidRecord();
                }
            }
            reader.endObject();
            if (reader.peek() != JsonToken.END_DOCUMENT) {
                throw invalidRecord();
            }
        } catch (IOException | IllegalStateException | NumberFormatException exception) {
            throw invalidRecord();
        }

        if (!hasSchemaVersion || schemaVersion != SCHEMA_VERSION
                || !hasFailureTimestamp || !hasFailureCategory || failureCategoryName == null
                || !hasFailureReason || failureReasonName == null || !hasLastStage || lastStageName == null
                || !hasLastHeartbeat || !hasActiveProvider || !hasActivePlugin
                || !hasLauncherLog || !hasDiagnosticDump) {
            throw invalidRecord();
        }
        @Nullable PluginRecoveryRecord.FailureCategory failureCategory =
                PluginRecoveryRecord.FailureCategory.fromWireName(failureCategoryName);
        @Nullable PluginRecoveryRecord.FailureReason failureReason =
                PluginRecoveryRecord.FailureReason.fromWireName(failureReasonName);
        @Nullable ProtectorStage lastStage = ProtectorStage.fromWireName(lastStageName);
        if (failureCategory == null || failureReason == null || lastStage == null) {
            throw invalidRecord();
        }
        try {
            return new PluginRecoveryRecord(
                    failureTimestamp,
                    failureCategory,
                    failureReason,
                    lastStage,
                    lastHeartbeat,
                    activeProviderId,
                    activePluginId,
                    launcherLogReference,
                    diagnosticDumpReference
            );
        } catch (IllegalArgumentException exception) {
            throw invalidRecord(exception);
        }
    }

    /// Reads one exact JSON string token.
    ///
    /// @param reader strict JSON reader
    /// @return decoded string
    /// @throws IOException if the token has another type
    private static String readRequiredString(JsonReader reader) throws IOException {
        if (reader.peek() != JsonToken.STRING) {
            throw invalidRecord();
        }
        return reader.nextString();
    }

    /// Reads one exact JSON string or null token.
    ///
    /// @param reader strict JSON reader
    /// @return decoded string, or `null`
    /// @throws IOException if the token has another type
    private static @Nullable String readNullableString(JsonReader reader) throws IOException {
        JsonToken token = reader.peek();
        if (token == JsonToken.NULL) {
            reader.nextNull();
            return null;
        }
        if (token != JsonToken.STRING) {
            throw invalidRecord();
        }
        return reader.nextString();
    }

    /// Reads one canonical non-negative JSON integer in the Java `int` range.
    ///
    /// @param reader strict JSON reader
    /// @return decoded integer
    /// @throws IOException if the token is invalid or out of range
    private static int readUnsignedInt(JsonReader reader) throws IOException {
        long value = readUnsignedLong(reader);
        if (value > Integer.MAX_VALUE) {
            throw invalidRecord();
        }
        return (int) value;
    }

    /// Reads one canonical non-negative JSON integer in the Java `long` range.
    ///
    /// @param reader strict JSON reader
    /// @return decoded integer
    /// @throws IOException if the token is invalid or out of range
    private static long readUnsignedLong(JsonReader reader) throws IOException {
        if (reader.peek() != JsonToken.NUMBER) {
            throw invalidRecord();
        }
        String number = reader.nextString();
        if (!UNSIGNED_INTEGER_PATTERN.matcher(number).matches()) {
            throw invalidRecord();
        }
        try {
            return Long.parseLong(number);
        } catch (NumberFormatException exception) {
            throw invalidRecord(exception);
        }
    }

    /// Deletes one exact regular file or symbolic link without traversing directories.
    ///
    /// @param target exact managed path
    /// @throws IOException if another file type occupies the path or deletion fails
    private void deleteExactTarget(Path target) throws IOException {
        BasicFileAttributes attributes;
        try {
            attributes = fileOperations.readAttributes(target);
        } catch (NoSuchFileException ignored) {
            return;
        }
        if (!attributes.isRegularFile() && !attributes.isSymbolicLink()) {
            throw new IOException("Plugin recovery target has an unsafe file type");
        }
        Files.delete(target);
    }

    /// Forces parent-directory metadata when supported by the current file-system provider.
    ///
    /// @param directory exact parent directory
    /// @return completed durability barriers for the preceding publication
    private PublicationDurability forceParentDirectoryBestEffort(Path directory) {
        try {
            fileOperations.forceDirectory(directory);
            return PublicationDurability.FILE_AND_DIRECTORY_FORCED;
        } catch (IOException | UnsupportedOperationException ignored) {
            // Windows and some custom providers do not expose directories as forceable file channels.
            return PublicationDurability.FILE_FORCED_ONLY;
        }
    }

    /// Creates one successful publication result from completed durability barriers.
    ///
    /// @param durability completed published-record durability barriers
    /// @return explicit published result
    private static PublicationResult publishedResult(PublicationDurability durability) {
        return new PublicationResult(PublicationOutcome.PUBLISHED, durability);
    }

    /// Creates one fixed classified publication failure without exposing record bytes or paths.
    ///
    /// @param result explicit publication classification
    /// @param message fixed safe diagnostic
    /// @param cause filesystem failure
    /// @return classified save exception
    private static PublicationException publicationFailure(
            PublicationResult result,
            String message,
            IOException cause
    ) {
        return new PublicationException(message, result, cause);
    }

    /// Creates a fixed diagnostic that contains no hostile record content.
    ///
    /// @return safe recovery exception
    private static IOException invalidRecord() {
        return new IOException("Invalid plugin recovery document");
    }

    /// Creates a fixed diagnostic with a parser cause that contains no raw document content.
    ///
    /// @param cause parser or record-validation failure
    /// @return safe recovery exception
    private static IOException invalidRecord(Exception cause) {
        return new IOException("Invalid plugin recovery document", cause);
    }

    /// Injectable package-private filesystem operations used by path guards and publication tests.
    @NotNullByDefault
    static class FileOperations {
        /// Creates the default real-filesystem operation set.
        FileOperations() {
        }

        /// Reads one existing component without following its final link.
        ///
        /// @param path existing path component
        /// @return no-follow basic attributes
        /// @throws IOException if attributes cannot be read
        BasicFileAttributes readAttributes(Path path) throws IOException {
            return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        }

        /// Opens one existing regular recovery target without following its final link.
        ///
        /// @param path direct recovery target
        /// @return open readable channel
        /// @throws IOException if the target cannot be opened safely
        FileChannel openReadable(Path path) throws IOException {
            return FileChannel.open(
                    path,
                    StandardOpenOption.READ,
                    LinkOption.NOFOLLOW_LINKS
            );
        }

        /// Resolves one real path with caller-selected link handling.
        ///
        /// @param path source path
        /// @param options link options
        /// @return resolved real path
        /// @throws IOException if resolution fails
        Path toRealPath(Path path, LinkOption @Unmodifiable ... options) throws IOException {
            return path.toRealPath(options);
        }

        /// Creates one exclusively owned no-follow writable temporary file.
        ///
        /// @param path unique sibling path
        /// @return open writable channel
        /// @throws IOException if exclusive creation fails
        FileChannel createNewWritable(Path path) throws IOException {
            return FileChannel.open(
                    path,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS
            );
        }

        /// Opens the stable no-follow operation lock file, creating it when absent.
        ///
        /// @param path stable repository lock path
        /// @return open writable lock-file channel
        /// @throws IOException if the direct lock file cannot be opened safely
        FileChannel openOperationLock(Path path) throws IOException {
            return FileChannel.open(
                    path,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS
            );
        }

        /// Forces all temporary file content and metadata before publication.
        ///
        /// @param channel open owned temporary-file channel
        /// @throws IOException if the provider cannot force the file
        void forceTemporary(FileChannel channel) throws IOException {
            channel.force(true);
        }

        /// Opens and forces a reconciled published target through its exact no-follow path.
        ///
        /// @param target verified regular recovery target
        /// @throws IOException if the target cannot be opened or forced
        void forcePublishedTarget(Path target) throws IOException {
            try (FileChannel channel = FileChannel.open(
                    target,
                    StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS
            )) {
                channel.force(true);
            }
        }

        /// Atomically replaces the recovery document when supported by the provider.
        ///
        /// @param source owned forced temporary file
        /// @param target exact recovery document
        /// @throws IOException if movement fails, including explicit atomic-move non-support
        void atomicReplace(Path source, Path target) throws IOException {
            Files.move(
                    source,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
            );
        }

        /// Replaces the recovery document without an atomicity guarantee.
        ///
        /// @param source owned forced temporary file
        /// @param target exact recovery document
        /// @throws IOException if replacement fails
        void replace(Path source, Path target) throws IOException {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }

        /// Forces parent-directory metadata after publication when the provider supports directory channels.
        ///
        /// @param directory launcher-local home
        /// @throws IOException if opening or forcing directory metadata fails
        void forceDirectory(Path directory) throws IOException {
            try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
                channel.force(true);
            }
        }

        /// Deletes only the exact temporary path owned by the current save invocation.
        ///
        /// @param temporaryFile exact owned temporary path
        /// @throws IOException if cleanup fails
        void deleteOwnedTemporary(Path temporaryFile) throws IOException {
            Files.deleteIfExists(temporaryFile);
        }
    }

    /// Stable identity snapshot for every direct launcher-home path component.
    ///
    /// @param components root-to-home component identities
    @NotNullByDefault
    private record HomeIdentity(@Unmodifiable List<ComponentIdentity> components) {
        /// Copies one complete root-to-home component identity sequence.
        private HomeIdentity {
            components = List.copyOf(components);
        }
    }

    /// Stable identity for one direct existing directory component.
    ///
    /// @param path absolute normalized component path
    /// @param realPath no-follow real path
    /// @param fileKey provider-stable directory identity when exposed, or `null` on Windows providers
    /// @param creationTime fallback replacement signal when the provider does not expose a file key
    @NotNullByDefault
    private record ComponentIdentity(
            Path path,
            Path realPath,
            @Nullable Object fileKey,
            FileTime creationTime
    ) {
        /// Captures one validated direct directory component.
        private ComponentIdentity {
        }
    }

    /// Exact bounded recovery target content captured before or after an ambiguous move.
    ///
    /// @param present whether the exact target existed at handle acquisition
    /// @param bytes exact bounded target bytes, empty when absent
    @NotNullByDefault
    private record TargetSnapshot(boolean present, byte @Unmodifiable [] bytes) {
        /// Copies exact target bytes so snapshot state cannot change after capture.
        private TargetSnapshot {
            bytes = bytes.clone();
            if (!present && bytes.length != 0) {
                throw new IllegalArgumentException("An absent target snapshot cannot contain bytes");
            }
        }

        /// Creates one definite absent target snapshot.
        ///
        /// @return absent snapshot
        private static TargetSnapshot absent() {
            return new TargetSnapshot(false, new byte[0]);
        }

        /// Returns whether this snapshot exactly equals requested bytes.
        ///
        /// @param requested requested recovery bytes
        /// @return whether the present target exactly matches
        private boolean matches(byte @Unmodifiable [] requested) {
            return present && Arrays.equals(bytes, requested);
        }

        /// Returns whether two snapshots have identical presence and exact bytes.
        ///
        /// @param other comparison snapshot
        /// @return whether both target states are exact matches
        private boolean equalsContent(TargetSnapshot other) {
            return present == other.present && Arrays.equals(bytes, other.bytes);
        }
    }

    /// Held JVM and OS lock resources spanning one complete recovery mutation.
    @NotNullByDefault
    private static final class OperationLease implements AutoCloseable {
        /// Shared per-real-home JVM lock.
        private final ReentrantLock processLock;

        /// Open stable lock-file channel.
        private final FileChannel channel;

        /// Held exclusive OS file lock.
        private final FileLock fileLock;

        /// Creates one held operation lease.
        ///
        /// @param processLock held JVM lock
        /// @param channel open lock-file channel
        /// @param fileLock held OS file lock
        private OperationLease(ReentrantLock processLock, FileChannel channel, FileLock fileLock) {
            this.processLock = processLock;
            this.channel = channel;
            this.fileLock = fileLock;
        }

        /// Releases the OS resources before allowing another JVM-local operation to acquire them.
        ///
        /// Lock release is best effort after the mutation outcome is already known; closing the channel also releases
        /// its lock on every supported provider.
        @Override
        public void close() {
            try {
                fileLock.release();
            } catch (IOException ignored) {
                // Closing the channel below provides the second release mechanism.
            }
            try {
                channel.close();
            } catch (IOException ignored) {
                // The mutation result remains authoritative after release was attempted.
            } finally {
                processLock.unlock();
            }
        }
    }
}
