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
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/// Loads and durably atomically replaces one bounded startup recovery document.
@NotNullByDefault
public final class PluginRecoveryStore {
    /// Launcher-local recovery document filename.
    public static final String FILE_NAME = "plugin-startup-recovery.json";

    /// Maximum accepted or emitted recovery-document size.
    public static final int MAX_RECOVERY_BYTES = 1024 * 1024;

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

    /// Exact managed sibling temporary path.
    private final Path temporaryFile;

    /// Creates a recovery store rooted at one launcher-local directory.
    ///
    /// @param launcherHome launcher-local directory
    public PluginRecoveryStore(Path launcherHome) {
        this.launcherHome = launcherHome.toAbsolutePath().normalize();
        this.recoveryFile = this.launcherHome.resolve(FILE_NAME);
        this.temporaryFile = this.launcherHome.resolve(FILE_NAME + ".tmp");
    }

    /// Loads the exact recovery record through one bounded no-follow file handle.
    ///
    /// @return stored record, or empty when the direct path was absent at handle acquisition
    /// @throws IOException if the present document is unsafe, corrupt, truncated, unsupported, or unreadable
    public Optional<PluginRecoveryRecord> load() throws IOException {
        @Nullable String json = readRecoveryFile();
        if (json == null) {
            return Optional.empty();
        }
        return Optional.of(parseRecord(json));
    }

    /// Forces one complete record to a sibling file and atomically replaces the recovery document when supported.
    ///
    /// @param record complete validated record
    /// @throws IOException if serialization, forcing, or replacement fails
    public void save(PluginRecoveryRecord record) throws IOException {
        byte @Unmodifiable [] bytes = encodeRecord(record).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_RECOVERY_BYTES) {
            throw new IOException("Plugin recovery document is too large");
        }
        requireSafeLauncherHome();

        boolean createdTemporaryFile = false;
        try {
            try (FileChannel channel = FileChannel.open(
                    temporaryFile,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS
            )) {
                createdTemporaryFile = true;
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            try {
                Files.move(
                        temporaryFile,
                        recoveryFile,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING
                );
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporaryFile, recoveryFile, StandardCopyOption.REPLACE_EXISTING);
            }
            createdTemporaryFile = false;
            try (FileChannel channel = FileChannel.open(
                    recoveryFile,
                    StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS
            )) {
                channel.force(true);
            }
            forceParentDirectoryBestEffort(launcherHome);
        } finally {
            if (createdTemporaryFile) {
                Files.deleteIfExists(temporaryFile);
            }
        }
    }

    /// Removes only the exact recovery document and a regular managed temporary sibling.
    ///
    /// @throws IOException if an exact target exists with an unsafe file type or cannot be removed
    public void clear() throws IOException {
        deleteExactTarget(recoveryFile);
        deleteExactTarget(temporaryFile);
        forceParentDirectoryBestEffort(launcherHome);
    }

    /// Rejects a symlinked launcher root and creates an absent direct directory.
    ///
    /// @throws IOException if the launcher-local root is unsafe or cannot be created
    private void requireSafeLauncherHome() throws IOException {
        if (Files.exists(launcherHome, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(launcherHome)
                    || !Files.isDirectory(launcherHome, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Plugin recovery directory is unsafe");
            }
            return;
        }
        Files.createDirectories(launcherHome);
        if (Files.isSymbolicLink(launcherHome)
                || !Files.isDirectory(launcherHome, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Plugin recovery directory is unsafe");
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
        document.addProperty("failureReason", record.failureReason());
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
        @Nullable String failureReason = null;
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
                        failureReason = readRequiredString(reader);
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
                || !hasFailureReason || failureReason == null || !hasLastStage || lastStageName == null
                || !hasLastHeartbeat || !hasActiveProvider || !hasActivePlugin
                || !hasLauncherLog || !hasDiagnosticDump) {
            throw invalidRecord();
        }
        @Nullable PluginRecoveryRecord.FailureCategory failureCategory =
                PluginRecoveryRecord.FailureCategory.fromWireName(failureCategoryName);
        @Nullable ProtectorStage lastStage = ProtectorStage.fromWireName(lastStageName);
        if (failureCategory == null || lastStage == null) {
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
    private static void forceParentDirectoryBestEffort(Path directory) {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException | UnsupportedOperationException ignored) {
            // Windows and some custom providers do not expose directories as forceable file channels.
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
}
