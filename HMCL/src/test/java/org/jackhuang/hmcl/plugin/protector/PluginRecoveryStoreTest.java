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

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// Verifies bounded, strict, atomic persistence of startup recovery records.
@NotNullByDefault
public final class PluginRecoveryStoreTest {
    /// Persists every recovery field and atomically replaces an earlier valid record.
    ///
    /// @param temporaryDirectory isolated launcher-local home
    /// @throws Exception if persistence fails
    @Test
    public void persistAndAtomicallyReplaceCompleteRecord(@TempDir Path temporaryDirectory) throws Exception {
        PluginRecoveryStore store = new PluginRecoveryStore(temporaryDirectory);
        PluginRecoveryRecord first = record("first process exit", 100L);
        PluginRecoveryRecord replacement = new PluginRecoveryRecord(
                1_777_000_000_001L,
                PluginRecoveryRecord.FailureCategory.STAGE_TIMEOUT,
                "provider startup timed out",
                ProtectorStage.RUNTIME_PROVIDERS_LOADING,
                200L,
                "org.example.provider",
                null,
                "logs/hmcl.log",
                "diagnostics/startup.txt"
        );

        store.save(first);
        assertEquals(first, store.load().orElseThrow());
        store.save(replacement);

        assertEquals(replacement, store.load().orElseThrow());
        assertFalse(Files.exists(temporaryDirectory.resolve(PluginRecoveryStore.FILE_NAME + ".tmp")));
    }

    /// Redacts nonce, secret, token, and internal control arguments before serialization.
    ///
    /// @param temporaryDirectory isolated launcher-local home
    /// @throws Exception if persistence fails
    @Test
    public void excludeSecretsNonceAndControlArguments(@TempDir Path temporaryDirectory) throws Exception {
        String secret = "never-persist-this-value";
        PluginRecoveryRecord record = new PluginRecoveryRecord(
                1_777_000_000_000L,
                PluginRecoveryRecord.FailureCategory.PROCESS_EXIT,
                "child failed --hmcl-protector-child --hmcl-protector-nonce=" + secret
                        + " token=" + secret + " secret=" + secret,
                ProtectorStage.ORDINARY_PLUGINS_LOADING,
                100L,
                null,
                "org.example.plugin",
                "logs/hmcl.log",
                null
        );
        PluginRecoveryStore store = new PluginRecoveryStore(temporaryDirectory);

        store.save(record);

        String json = Files.readString(
                temporaryDirectory.resolve(PluginRecoveryStore.FILE_NAME),
                StandardCharsets.UTF_8
        );
        assertFalse(record.failureReason().contains(secret));
        assertFalse(json.contains(secret));
        assertFalse(json.contains("hmcl-protector-child"));
        assertFalse(json.contains("hmcl-protector-nonce"));
        assertTrue(json.contains("[redacted]"));
    }

    /// Rejects path escapes, absolute paths, control characters, and overlong record text.
    @Test
    public void rejectUnsafeReferencesAndUnboundedText() {
        assertThrows(IllegalArgumentException.class, () -> recordWithLog("../outside.log"));
        assertThrows(IllegalArgumentException.class, () -> recordWithLog("C:/outside.log"));
        assertThrows(IllegalArgumentException.class, () -> recordWithLog("logs/line\nfeed.log"));
        assertThrows(IllegalArgumentException.class, () -> recordWithLog("logs/" + "x".repeat(600)));
        assertThrows(IllegalArgumentException.class, () -> record("x".repeat(4_097), 100L));
        assertThrows(IllegalArgumentException.class, () -> record("process\u0000exit", 100L));
    }

    /// Rejects invalid timestamps and active IDs that do not match the recorded stage.
    @Test
    public void rejectUnsafeTimestampsAndActiveIdentityCombinations() {
        assertThrows(IllegalArgumentException.class, () -> new PluginRecoveryRecord(
                0L,
                PluginRecoveryRecord.FailureCategory.CRASH,
                "crash",
                ProtectorStage.JVM_STARTED,
                1L,
                null,
                null,
                null,
                null
        ));
        assertThrows(IllegalArgumentException.class, () -> record("crash", -1L));
        assertThrows(IllegalArgumentException.class, () -> new PluginRecoveryRecord(
                1_777_000_000_000L,
                PluginRecoveryRecord.FailureCategory.CRASH,
                "crash",
                ProtectorStage.RUNTIME_PROVIDERS_LOADING,
                1L,
                "",
                null,
                null,
                null
        ));
        assertThrows(IllegalArgumentException.class, () -> new PluginRecoveryRecord(
                1_777_000_000_000L,
                PluginRecoveryRecord.FailureCategory.CRASH,
                "crash",
                ProtectorStage.CORE_READY,
                1L,
                "org.example.provider",
                null,
                null,
                null
        ));
    }

    /// Treats an absent recovery file as empty but rejects corrupt, truncated, unknown, or extended schemas.
    ///
    /// @param temporaryDirectory isolated launcher-local home
    /// @throws Exception if fixture I/O fails
    @Test
    public void failClosedForInvalidRecoveryDocuments(@TempDir Path temporaryDirectory) throws Exception {
        PluginRecoveryStore store = new PluginRecoveryStore(temporaryDirectory);
        Path recoveryFile = temporaryDirectory.resolve(PluginRecoveryStore.FILE_NAME);
        assertTrue(store.load().isEmpty());

        assertInvalidDocument(store, recoveryFile, "{ truncated");
        assertInvalidDocument(store, recoveryFile, "{\"schemaVersion\":2}");
        store.save(record("valid", 100L));
        String valid = Files.readString(recoveryFile, StandardCharsets.UTF_8);
        assertInvalidDocument(store, recoveryFile, valid.replace(
                "\"schemaVersion\":1",
                "\"schemaVersion\":1,\"unknown\":true"
        ));
        assertInvalidDocument(store, recoveryFile, valid.replace(
                "\"failureReason\":\"valid\"",
                "\"failureReason\":null"
        ));
    }

    /// Rejects a recovery file larger than one MiB without deleting or truncating it.
    ///
    /// @param temporaryDirectory isolated launcher-local home
    /// @throws Exception if fixture I/O fails
    @Test
    public void rejectOversizedRecoveryFileWithoutDeletingIt(@TempDir Path temporaryDirectory) throws Exception {
        Path recoveryFile = temporaryDirectory.resolve(PluginRecoveryStore.FILE_NAME);
        byte[] oversized = new byte[PluginRecoveryStore.MAX_RECOVERY_BYTES + 1];
        Files.write(recoveryFile, oversized);

        assertThrows(IOException.class, () -> new PluginRecoveryStore(temporaryDirectory).load());
        assertEquals(oversized.length, Files.size(recoveryFile));
    }

    /// Rejects malformed UTF-8 without deleting or rewriting the hostile recovery document.
    ///
    /// @param temporaryDirectory isolated launcher-local home
    /// @throws Exception if fixture I/O fails
    @Test
    public void rejectMalformedUtf8WithoutChangingIt(@TempDir Path temporaryDirectory) throws Exception {
        Path recoveryFile = temporaryDirectory.resolve(PluginRecoveryStore.FILE_NAME);
        try (OutputStream output = Files.newOutputStream(recoveryFile)) {
            output.write(0xc3);
            output.write(0x28);
        }

        assertThrows(IOException.class, () -> new PluginRecoveryStore(temporaryDirectory).load());
        assertEquals(2L, Files.size(recoveryFile));
    }

    /// Rejects symbolic links through the no-follow recovery handle and leaves both link and target intact.
    ///
    /// @param temporaryDirectory isolated launcher-local home
    /// @throws Exception if fixture I/O fails
    @Test
    public void rejectSymbolicLinkRecoveryFile(@TempDir Path temporaryDirectory) throws Exception {
        Path target = temporaryDirectory.resolve("outside.json");
        Files.writeString(target, "{}", StandardCharsets.UTF_8);
        Path recoveryFile = temporaryDirectory.resolve(PluginRecoveryStore.FILE_NAME);
        try {
            Files.createSymbolicLink(recoveryFile, target.getFileName());
        } catch (IOException | UnsupportedOperationException exception) {
            assumeTrue(false, "Symbolic links are unavailable in this test environment: " + exception);
        }

        assertThrows(IOException.class, () -> new PluginRecoveryStore(temporaryDirectory).load());
        assertTrue(Files.isSymbolicLink(recoveryFile));
        assertEquals("{}", Files.readString(target, StandardCharsets.UTF_8));
    }

    /// Leaves the previous valid record intact when sibling temporary-file creation fails.
    ///
    /// @param temporaryDirectory isolated launcher-local home
    /// @throws Exception if fixture setup fails
    @Test
    public void preservePreviousRecordWhenReplacementFails(@TempDir Path temporaryDirectory) throws Exception {
        PluginRecoveryStore store = new PluginRecoveryStore(temporaryDirectory);
        PluginRecoveryRecord previous = record("previous", 100L);
        store.save(previous);
        Path temporaryFile = temporaryDirectory.resolve(PluginRecoveryStore.FILE_NAME + ".tmp");
        Files.createDirectory(temporaryFile);
        Files.writeString(temporaryFile.resolve("sentinel"), "retain", StandardCharsets.UTF_8);

        assertThrows(IOException.class, () -> store.save(record("replacement", 200L)));

        assertEquals(previous, store.load().orElseThrow());
        assertEquals("retain", Files.readString(temporaryFile.resolve("sentinel"), StandardCharsets.UTF_8));
    }

    /// Clears only the exact recovery document and its managed temporary sibling.
    ///
    /// @param temporaryDirectory isolated launcher-local home
    /// @throws Exception if fixture setup or clearing fails
    @Test
    public void clearOnlyExactRecoveryTargets(@TempDir Path temporaryDirectory) throws Exception {
        PluginRecoveryStore store = new PluginRecoveryStore(temporaryDirectory);
        store.save(record("clear", 100L));
        Path temporaryFile = temporaryDirectory.resolve(PluginRecoveryStore.FILE_NAME + ".tmp");
        Files.writeString(temporaryFile, "stale", StandardCharsets.UTF_8);
        Path unrelated = temporaryDirectory.resolve(PluginRecoveryStore.FILE_NAME + ".backup");
        Files.writeString(unrelated, "retain", StandardCharsets.UTF_8);

        store.clear();

        assertTrue(store.load().isEmpty());
        assertFalse(Files.exists(temporaryFile));
        assertEquals("retain", Files.readString(unrelated, StandardCharsets.UTF_8));
    }

    /// Builds one complete ordinary-plugin recovery record.
    ///
    /// @param reason failure reason
    /// @param heartbeat last monotonic heartbeat
    /// @return validated recovery record
    private static PluginRecoveryRecord record(String reason, long heartbeat) {
        return new PluginRecoveryRecord(
                1_777_000_000_000L,
                PluginRecoveryRecord.FailureCategory.PROCESS_EXIT,
                reason,
                ProtectorStage.ORDINARY_PLUGINS_LOADING,
                heartbeat,
                null,
                "org.example.plugin",
                "logs/hmcl.log",
                "diagnostics/startup.txt"
        );
    }

    /// Builds one record with a caller-selected launcher-log reference.
    ///
    /// @param launcherLogReference candidate relative log reference
    /// @return validated recovery record
    private static PluginRecoveryRecord recordWithLog(String launcherLogReference) {
        return new PluginRecoveryRecord(
                1_777_000_000_000L,
                PluginRecoveryRecord.FailureCategory.CRASH,
                "crash",
                ProtectorStage.JVM_STARTED,
                100L,
                null,
                null,
                launcherLogReference,
                null
        );
    }

    /// Writes one invalid document, asserts fail-closed loading, and verifies the original remains untouched.
    ///
    /// @param store recovery store
    /// @param recoveryFile exact recovery document
    /// @param invalidJson invalid serialized content
    /// @throws Exception if fixture I/O fails
    private static void assertInvalidDocument(
            PluginRecoveryStore store,
            Path recoveryFile,
            String invalidJson
    ) throws Exception {
        Files.writeString(recoveryFile, invalidJson, StandardCharsets.UTF_8);

        assertThrows(IOException.class, store::load);
        assertEquals(invalidJson, Files.readString(recoveryFile, StandardCharsets.UTF_8));
    }
}
