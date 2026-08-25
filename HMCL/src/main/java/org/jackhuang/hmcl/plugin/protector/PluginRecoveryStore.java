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
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/// Loads and durably replaces one bounded startup recovery document, using atomic publication when supported.
@NotNullByDefault
public final class PluginRecoveryStore {
    /// Describes which filesystem durability barriers completed after a successful publication.
    @NotNullByDefault
    public enum PublicationDurability {
        /// Both file content and parent-directory metadata were forced to stable storage.
        FILE_AND_DIRECTORY_FORCED,

        /// File content was forced, but the provider could not force parent-directory metadata.
        FILE_FORCED_ONLY
    }

    /// Launcher-local recovery document filename.
    public static final String FILE_NAME = "plugin-startup-recovery.json";

    /// Maximum accepted or emitted recovery-document size.
    public static final int MAX_RECOVERY_BYTES = 1024 * 1024;

    /// Reserved sibling prefix for uniquely owned crash-recoverable temporary files.
    static final String TEMP_FILE_PREFIX = FILE_NAME + ".tmp-";

    /// Current strict recovery-document schema version.
    private static final int SCHEMA_VERSION = 1;

    /// Unsigned canonical JSON integer syntax.
    private static final Pattern UNSIGNED_INTEGER_PATTERN = Pattern.compile("0|[1-9][0-9]*");

    /// Compact JSON encoder preserving explicit field insertion order.
    private static final Gson GSON = new GsonBuilder().serializeNulls().create();

    /// Normalized launcher-local directory containing the exact recovery targets.
    private final Path launcherHome;

    /// Exact recovery document path.
    private final Path recoveryFile;

    /// Injectable filesystem operations used for path identity and durable publication.
    private final FileOperations fileOperations;

    /// Per-store publication lock that serializes Windows replacement while allowing concurrent temporary writes.
    private final Object publicationLock = new Object();

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

    /// Forces one complete record to a sibling file and replaces the recovery document.
    ///
    /// The successful move is the publication boundary. Atomic replacement is preferred and ordinary replacement is
    /// used only when the provider explicitly reports that atomic moves are unsupported. A failure before the move
    /// preserves the prior record. A failure while validating path identity after the move leaves publication state
    /// indeterminate. Parent-directory metadata forcing occurs after publication and is reported in the return value.
    ///
    /// @param record complete validated record
    /// @return completed durability barriers for the published record
    /// @throws IOException if serialization, forcing, or replacement fails
    public PublicationDurability save(PluginRecoveryRecord record) throws IOException {
        byte @Unmodifiable [] bytes = encodeRecord(record).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_RECOVERY_BYTES) {
            throw new IOException("Plugin recovery document is too large");
        }
        @Nullable HomeIdentity homeIdentity = requireSafeLauncherHome(true);
        if (homeIdentity == null) {
            throw new IOException("Plugin recovery directory could not be created safely");
        }
        verifyHomeIdentity(homeIdentity);

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
            synchronized (publicationLock) {
                verifyHomeIdentity(homeIdentity);
                try {
                    fileOperations.atomicReplace(temporaryFile, recoveryFile);
                } catch (AtomicMoveNotSupportedException ignored) {
                    fileOperations.replace(temporaryFile, recoveryFile);
                }
                createdTemporaryFile = false;
                verifyHomeIdentity(homeIdentity);
            }
        } catch (IOException failure) {
            if (createdTemporaryFile) {
                try {
                    fileOperations.deleteOwnedTemporary(temporaryFile);
                } catch (IOException cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
            throw failure;
        }
        return forceParentDirectoryBestEffort(launcherHome);
    }

    /// Removes only the exact recovery document and managed regular temporary siblings.
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
        verifyHomeIdentity(homeIdentity);
        deleteExactTarget(recoveryFile);
        deleteStaleTemporaryFiles();
        verifyHomeIdentity(homeIdentity);
        forceParentDirectoryBestEffort(launcherHome);
    }

    /// Deletes only no-follow regular siblings carrying this store's reserved temporary prefix.
    ///
    /// Directories and symbolic links are retained because this process cannot prove ownership of their contents or
    /// targets. Each save independently owns and cleans its unique path, while this scan recovers regular files left
    /// by a process crash.
    ///
    /// @throws IOException if enumeration or deletion of a controlled regular temporary file fails
    private void deleteStaleTemporaryFiles() throws IOException {
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(launcherHome, TEMP_FILE_PREFIX + "*")) {
            for (Path entry : entries) {
                if (Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) {
                    Files.delete(entry);
                }
            }
        }
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
        components.add(captureDirectDirectory(component));
        for (Path name : root.relativize(launcherHome)) {
            component = component.resolve(name);
            if (!Files.exists(component, LinkOption.NOFOLLOW_LINKS)) {
                return null;
            }
            components.add(captureDirectDirectory(component));
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
        try (FileChannel channel = FileChannel.open(
                recoveryFile,
                StandardOpenOption.READ,
                LinkOption.NOFOLLOW_LINKS
        )) {
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
    private static void deleteExactTarget(Path target) throws IOException {
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(target)) {
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

        /// Forces all temporary file content and metadata before publication.
        ///
        /// @param channel open owned temporary-file channel
        /// @throws IOException if the provider cannot force the file
        void forceTemporary(FileChannel channel) throws IOException {
            channel.force(true);
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
}
