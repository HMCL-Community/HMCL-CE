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
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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
        PluginRecoveryRecord first = record(PluginRecoveryRecord.FailureReason.UNEXPECTED_PROCESS_EXIT, 100L);
        PluginRecoveryRecord replacement = new PluginRecoveryRecord(
                1_777_000_000_001L,
                PluginRecoveryRecord.FailureCategory.STAGE_TIMEOUT,
                PluginRecoveryRecord.FailureReason.PROVIDER_DEADLINE_EXCEEDED,
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

    /// Persists only a typed reason code and offers no raw exception or control-argument field.
    ///
    /// @param temporaryDirectory isolated launcher-local home
    /// @throws Exception if persistence fails
    @Test
    public void persistOnlyTypedFailureReason(@TempDir Path temporaryDirectory) throws Exception {
        PluginRecoveryRecord record = new PluginRecoveryRecord(
                1_777_000_000_000L,
                PluginRecoveryRecord.FailureCategory.PROCESS_EXIT,
                PluginRecoveryRecord.FailureReason.UNEXPECTED_PROCESS_EXIT,
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
        assertEquals(PluginRecoveryRecord.FailureReason.UNEXPECTED_PROCESS_EXIT, record.failureReason());
        assertFalse(json.contains("hmcl-protector-child"));
        assertFalse(json.contains("hmcl-protector-nonce"));
        assertTrue(json.contains("\"failureReason\":\"unexpected-process-exit\""));
    }

    /// Rejects path escapes, absolute paths, control characters, and overlong record text.
    @Test
    public void rejectUnsafeReferencesAndUnboundedText() {
        assertThrows(IllegalArgumentException.class, () -> recordWithLog("../outside.log"));
        assertThrows(IllegalArgumentException.class, () -> recordWithLog("C:/outside.log"));
        assertThrows(IllegalArgumentException.class, () -> recordWithLog("logs/line\nfeed.log"));
        assertThrows(IllegalArgumentException.class, () -> recordWithLog("logs/" + "x".repeat(600)));
        assertThrows(IllegalArgumentException.class, () -> recordWithLog("logs/Authorization=Bearer-secret.log"));
        assertThrows(IllegalArgumentException.class, () -> recordWithLog("logs/{\"token\":\"secret\"}.log"));
    }

    /// Rejects invalid timestamps and active IDs that do not match the recorded stage.
    @Test
    public void rejectUnsafeTimestampsAndActiveIdentityCombinations() {
        assertThrows(IllegalArgumentException.class, () -> new PluginRecoveryRecord(
                0L,
                PluginRecoveryRecord.FailureCategory.CRASH,
                PluginRecoveryRecord.FailureReason.CHILD_CRASH,
                ProtectorStage.JVM_STARTED,
                1L,
                null,
                null,
                null,
                null
        ));
        assertThrows(IllegalArgumentException.class, () -> record(
                PluginRecoveryRecord.FailureReason.UNEXPECTED_PROCESS_EXIT,
                -1L
        ));
        assertThrows(IllegalArgumentException.class, () -> new PluginRecoveryRecord(
                1_777_000_000_000L,
                PluginRecoveryRecord.FailureCategory.CRASH,
                PluginRecoveryRecord.FailureReason.CHILD_CRASH,
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
                PluginRecoveryRecord.FailureReason.CHILD_CRASH,
                ProtectorStage.CORE_READY,
                1L,
                "org.example.provider",
                null,
                null,
                null
        ));
        assertThrows(IllegalArgumentException.class, () -> new PluginRecoveryRecord(
                1_777_000_000_000L,
                PluginRecoveryRecord.FailureCategory.CRASH,
                PluginRecoveryRecord.FailureReason.PLUGIN_DEADLINE_EXCEEDED,
                ProtectorStage.JVM_STARTED,
                1L,
                null,
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
        store.save(record(PluginRecoveryRecord.FailureReason.UNEXPECTED_PROCESS_EXIT, 100L));
        String valid = Files.readString(recoveryFile, StandardCharsets.UTF_8);
        assertInvalidDocument(store, recoveryFile, valid.replace(
                "\"schemaVersion\":1",
                "\"schemaVersion\":1,\"unknown\":true"
        ));
        assertInvalidDocument(store, recoveryFile, valid.replace(
                "\"failureReason\":\"unexpected-process-exit\"",
                "\"failureReason\":null"
        ));
    }

    /// Rejects arbitrary exception text and secret-shaped reason values without echoing them through errors.
    ///
    /// @param temporaryDirectory isolated launcher-local home
    /// @throws Exception if fixture setup fails
    @Test
    public void rejectUntrustedFailureReasonsWithoutEcho(@TempDir Path temporaryDirectory) throws Exception {
        PluginRecoveryStore store = new PluginRecoveryStore(temporaryDirectory);
        store.save(record(PluginRecoveryRecord.FailureReason.UNEXPECTED_PROCESS_EXIT, 100L));
        Path recoveryFile = temporaryDirectory.resolve(PluginRecoveryStore.FILE_NAME);
        String valid = Files.readString(recoveryFile, StandardCharsets.UTF_8);
        String[] hostileReasons = {
                "Bearer AbCdEf012345",
                " authorization  :  bearer quoted-secret ",
                "NoNcE = 'mixed-case-secret'",
                "{token:password, nested:{secret:quoted}}",
                "\\\"password\\\":\\\"json-secret\\\"",
                "line\\nsecret=hidden"
        };

        for (String hostileReason : hostileReasons) {
            String hostileDocument = valid.replace(
                    "\"failureReason\":\"unexpected-process-exit\"",
                    "\"failureReason\":\"" + hostileReason + "\""
            );
            Files.writeString(recoveryFile, hostileDocument, StandardCharsets.UTF_8);

            IOException exception = assertThrows(IOException.class, store::load);
            assertThrowableDoesNotContain(exception, hostileReason);
            assertEquals(hostileDocument, Files.readString(recoveryFile, StandardCharsets.UTF_8));
        }
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

    /// Rejects a launcher home that is itself a symbolic link for load, clear, and save.
    ///
    /// @param temporaryDirectory isolated filesystem root
    /// @throws Exception if fixture setup fails
    @Test
    public void rejectSymbolicLinkLauncherHomeForEveryOperation(@TempDir Path temporaryDirectory) throws Exception {
        Path targetHome = temporaryDirectory.resolve("target-home");
        Files.createDirectories(targetHome);
        Path linkedHome = temporaryDirectory.resolve("linked-home");
        try {
            Files.createSymbolicLink(linkedHome, targetHome.getFileName());
        } catch (IOException | UnsupportedOperationException exception) {
            assumeTrue(false, "Symbolic links are unavailable in this test environment: " + exception);
        }

        assertRejectRedirectedHome(linkedHome, targetHome);
    }

    /// Rejects a symbolic link in an intermediate launcher-home component for every operation.
    ///
    /// @param temporaryDirectory isolated filesystem root
    /// @throws Exception if fixture setup fails
    @Test
    public void rejectIntermediateSymbolicLinkForEveryOperation(@TempDir Path temporaryDirectory) throws Exception {
        Path targetBase = temporaryDirectory.resolve("target-base");
        Path targetHome = targetBase.resolve("nested-home");
        Files.createDirectories(targetHome);
        Path linkedBase = temporaryDirectory.resolve("linked-base");
        try {
            Files.createSymbolicLink(linkedBase, targetBase.getFileName());
        } catch (IOException | UnsupportedOperationException exception) {
            assumeTrue(false, "Symbolic links are unavailable in this test environment: " + exception);
        }

        assertRejectRedirectedHome(linkedBase.resolve("nested-home"), targetHome);
    }

    /// Rejects a Windows junction launcher home for load, clear, and save.
    ///
    /// @param temporaryDirectory isolated filesystem root
    /// @throws Exception if fixture setup or junction removal fails
    @Test
    public void rejectWindowsJunctionLauncherHomeForEveryOperation(@TempDir Path temporaryDirectory) throws Exception {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"), "Windows junction test");
        Path targetHome = temporaryDirectory.resolve("junction-target");
        Files.createDirectories(targetHome);
        Path junctionHome = temporaryDirectory.resolve("junction-home");
        Process process = new ProcessBuilder(
                "cmd.exe",
                "/c",
                "mklink",
                "/J",
                junctionHome.toString(),
                targetHome.toString()
        ).redirectErrorStream(true).start();
        assumeTrue(process.waitFor() == 0, "Windows junction creation is unavailable");
        try {
            assertRejectRedirectedHome(junctionHome, targetHome);
        } finally {
            Files.deleteIfExists(junctionHome);
        }
    }

    /// Detects an observable launcher-home swap before load, clear, or save can traverse the replacement link.
    ///
    /// @param temporaryDirectory isolated filesystem root
    /// @throws Exception if fixture setup or verification fails
    @Test
    public void rejectObservableDirectorySwapForEveryOperation(@TempDir Path temporaryDirectory) throws Exception {
        for (String operation : List.of("load", "clear", "save")) {
            Path root = temporaryDirectory.resolve(operation);
            Path directHome = root.resolve("direct-home");
            Path retainedHome = root.resolve("retained-home");
            Files.createDirectories(directHome);
            Files.createDirectories(retainedHome);
            PluginRecoveryRecord original = record(
                    PluginRecoveryRecord.FailureReason.UNEXPECTED_PROCESS_EXIT,
                    100L
            );
            PluginRecoveryRecord retained = record(
                    PluginRecoveryRecord.FailureReason.UNEXPECTED_PROCESS_EXIT,
                    200L
            );
            new PluginRecoveryStore(directHome).save(original);
            new PluginRecoveryStore(retainedHome).save(retained);
            Path movedDirectHome = root.resolve("moved-direct-home");
            SwapOnSecondInspectionOperations operations = new SwapOnSecondInspectionOperations(
                    directHome,
                    movedDirectHome,
                    retainedHome
            );
            PluginRecoveryStore guardedStore = new PluginRecoveryStore(directHome, operations);

            assertThrows(IOException.class, () -> runStoreOperation(operation, guardedStore));

            assertEquals(original, new PluginRecoveryStore(movedDirectHome).load().orElseThrow());
            assertEquals(retained, new PluginRecoveryStore(retainedHome).load().orElseThrow());
        }
    }

    /// Ignores an unrelated fixed-name temporary directory when publishing through a unique sibling.
    ///
    /// @param temporaryDirectory isolated launcher-local home
    /// @throws Exception if fixture setup fails
    @Test
    public void ignoreResidualFixedTemporaryDirectory(@TempDir Path temporaryDirectory) throws Exception {
        PluginRecoveryStore store = new PluginRecoveryStore(temporaryDirectory);
        PluginRecoveryRecord previous = record(PluginRecoveryRecord.FailureReason.UNEXPECTED_PROCESS_EXIT, 100L);
        store.save(previous);
        Path temporaryFile = temporaryDirectory.resolve(PluginRecoveryStore.FILE_NAME + ".tmp");
        Files.createDirectory(temporaryFile);
        Files.writeString(temporaryFile.resolve("sentinel"), "retain", StandardCharsets.UTF_8);
        PluginRecoveryRecord replacement = record(
                PluginRecoveryRecord.FailureReason.UNEXPECTED_PROCESS_EXIT,
                200L
        );

        store.save(replacement);

        assertEquals(replacement, store.load().orElseThrow());
        assertEquals("retain", Files.readString(temporaryFile.resolve("sentinel"), StandardCharsets.UTF_8));
    }

    /// Clears only the exact recovery document and regular stale siblings with the controlled temporary prefix.
    ///
    /// @param temporaryDirectory isolated launcher-local home
    /// @throws Exception if fixture setup or clearing fails
    @Test
    public void clearOnlyExactRecoveryTargets(@TempDir Path temporaryDirectory) throws Exception {
        PluginRecoveryStore store = new PluginRecoveryStore(temporaryDirectory);
        store.save(record(PluginRecoveryRecord.FailureReason.UNEXPECTED_PROCESS_EXIT, 100L));
        Path staleTemporaryFile = temporaryDirectory.resolve(PluginRecoveryStore.FILE_NAME + ".tmp-stale-owned");
        Files.writeString(staleTemporaryFile, "stale", StandardCharsets.UTF_8);
        Path unrelatedFixedTemporary = temporaryDirectory.resolve(PluginRecoveryStore.FILE_NAME + ".tmp");
        Files.writeString(unrelatedFixedTemporary, "retain-fixed", StandardCharsets.UTF_8);
        Path staleTemporaryDirectory = temporaryDirectory.resolve(
                PluginRecoveryStore.FILE_NAME + ".tmp-stale-directory"
        );
        Files.createDirectory(staleTemporaryDirectory);
        Files.writeString(staleTemporaryDirectory.resolve("sentinel"), "retain-directory", StandardCharsets.UTF_8);
        Path unrelated = temporaryDirectory.resolve(PluginRecoveryStore.FILE_NAME + ".backup");
        Files.writeString(unrelated, "retain", StandardCharsets.UTF_8);

        store.clear();

        assertTrue(store.load().isEmpty());
        assertFalse(Files.exists(staleTemporaryFile));
        assertEquals("retain-fixed", Files.readString(unrelatedFixedTemporary, StandardCharsets.UTF_8));
        assertEquals(
                "retain-directory",
                Files.readString(staleTemporaryDirectory.resolve("sentinel"), StandardCharsets.UTF_8)
        );
        assertEquals("retain", Files.readString(unrelated, StandardCharsets.UTF_8));
    }

    /// Publishes concurrent saves through distinct owned temporary siblings without collisions or residue.
    ///
    /// @param temporaryDirectory isolated launcher-local home
    /// @throws Exception if concurrent persistence or verification fails
    @Test
    public void publishConcurrentSavesThroughUniqueTemporaryFiles(@TempDir Path temporaryDirectory) throws Exception {
        ConcurrentCreateOperations operations = new ConcurrentCreateOperations();
        PluginRecoveryStore store = new PluginRecoveryStore(temporaryDirectory, operations);
        PluginRecoveryRecord first = record(
                PluginRecoveryRecord.FailureReason.UNEXPECTED_PROCESS_EXIT,
                100L
        );
        PluginRecoveryRecord second = record(
                PluginRecoveryRecord.FailureReason.UNEXPECTED_PROCESS_EXIT,
                200L
        );
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> firstSave = executor.submit(() -> saveUnchecked(store, first));
            Future<?> secondSave = executor.submit(() -> saveUnchecked(store, second));

            firstSave.get(10L, TimeUnit.SECONDS);
            secondSave.get(10L, TimeUnit.SECONDS);

            assertTrue(Set.of(first, second).contains(store.load().orElseThrow()));
            try (java.nio.file.DirectoryStream<Path> entries = Files.newDirectoryStream(
                    temporaryDirectory,
                    PluginRecoveryStore.TEMP_FILE_PREFIX + "*"
            )) {
                assertFalse(entries.iterator().hasNext());
            }
        } finally {
            executor.shutdownNow();
        }
    }

    /// Falls back to non-atomic replacement only when atomic movement is explicitly unsupported.
    ///
    /// @param temporaryDirectory isolated launcher-local home
    /// @throws Exception if fixture setup or verification fails
    @Test
    public void fallbackWhenAtomicReplacementIsUnsupported(@TempDir Path temporaryDirectory) throws Exception {
        PluginRecoveryRecord previous = record(
                PluginRecoveryRecord.FailureReason.UNEXPECTED_PROCESS_EXIT,
                100L
        );
        PluginRecoveryRecord replacement = record(
                PluginRecoveryRecord.FailureReason.UNEXPECTED_PROCESS_EXIT,
                200L
        );
        new PluginRecoveryStore(temporaryDirectory).save(previous);
        FaultInjectingOperations operations = new FaultInjectingOperations();
        operations.atomicUnsupported = true;

        new PluginRecoveryStore(temporaryDirectory, operations).save(replacement);

        assertEquals(1, operations.atomicMoves);
        assertEquals(1, operations.fallbackMoves);
        assertEquals(replacement, new PluginRecoveryStore(temporaryDirectory).load().orElseThrow());
        assertNoControlledTemporaryFiles(temporaryDirectory);
    }

    /// Does not attempt a weaker fallback after an ordinary atomic-move failure.
    ///
    /// @param temporaryDirectory isolated launcher-local home
    /// @throws Exception if fixture setup or verification fails
    @Test
    public void preservePreviousRecordWhenAtomicReplacementFails(@TempDir Path temporaryDirectory) throws Exception {
        assertPrePublicationFailure(temporaryDirectory, operations -> operations.failAtomicMove = true);
    }

    /// Preserves the previous record when the explicitly selected fallback move also fails.
    ///
    /// @param temporaryDirectory isolated launcher-local home
    /// @throws Exception if fixture setup or verification fails
    @Test
    public void preservePreviousRecordWhenFallbackReplacementFails(@TempDir Path temporaryDirectory) throws Exception {
        assertPrePublicationFailure(temporaryDirectory, operations -> {
            operations.atomicUnsupported = true;
            operations.failFallbackMove = true;
        });
    }

    /// Preserves the previous record and cleans its owned sibling when forcing temporary content fails.
    ///
    /// @param temporaryDirectory isolated launcher-local home
    /// @throws Exception if fixture setup or verification fails
    @Test
    public void preservePreviousRecordWhenTemporaryForceFails(@TempDir Path temporaryDirectory) throws Exception {
        assertPrePublicationFailure(temporaryDirectory, operations -> operations.failTemporaryForce = true);
    }

    /// Treats directory-force failure after replacement as a durability warning, not a failed publication.
    ///
    /// @param temporaryDirectory isolated launcher-local home
    /// @throws Exception if fixture setup or verification fails
    @Test
    public void retainPublishedRecordWhenDirectoryForceFails(@TempDir Path temporaryDirectory) throws Exception {
        PluginRecoveryRecord previous = record(
                PluginRecoveryRecord.FailureReason.UNEXPECTED_PROCESS_EXIT,
                100L
        );
        PluginRecoveryRecord replacement = record(
                PluginRecoveryRecord.FailureReason.UNEXPECTED_PROCESS_EXIT,
                200L
        );
        new PluginRecoveryStore(temporaryDirectory).save(previous);
        FaultInjectingOperations operations = new FaultInjectingOperations();
        operations.failDirectoryForce = true;

        PluginRecoveryStore.PublicationDurability durability =
                new PluginRecoveryStore(temporaryDirectory, operations).save(replacement);

        assertEquals(1, operations.directoryForces);
        assertEquals(PluginRecoveryStore.PublicationDurability.FILE_FORCED_ONLY, durability);
        assertEquals(replacement, new PluginRecoveryStore(temporaryDirectory).load().orElseThrow());
        assertNoControlledTemporaryFiles(temporaryDirectory);
    }

    /// Retains the primary pre-publication failure and reports cleanup failure as suppressed without touching old data.
    ///
    /// @param temporaryDirectory isolated launcher-local home
    /// @throws Exception if fixture setup or verification fails
    @Test
    public void preservePrimaryFailureWhenOwnedTemporaryCleanupFails(@TempDir Path temporaryDirectory) throws Exception {
        PluginRecoveryRecord previous = record(
                PluginRecoveryRecord.FailureReason.UNEXPECTED_PROCESS_EXIT,
                100L
        );
        new PluginRecoveryStore(temporaryDirectory).save(previous);
        FaultInjectingOperations operations = new FaultInjectingOperations();
        operations.failAtomicMove = true;
        operations.failCleanup = true;

        IOException exception = assertThrows(
                IOException.class,
                () -> new PluginRecoveryStore(temporaryDirectory, operations).save(record(
                        PluginRecoveryRecord.FailureReason.UNEXPECTED_PROCESS_EXIT,
                        200L
                ))
        );

        assertEquals(1, exception.getSuppressed().length);
        assertEquals(previous, new PluginRecoveryStore(temporaryDirectory).load().orElseThrow());
        assertEquals(1L, countControlledTemporaryFiles(temporaryDirectory));
    }

    /// Builds one complete ordinary-plugin recovery record.
    ///
    /// @param reason controlled failure reason
    /// @param heartbeat last monotonic heartbeat
    /// @return validated recovery record
    private static PluginRecoveryRecord record(PluginRecoveryRecord.FailureReason reason, long heartbeat) {
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
                PluginRecoveryRecord.FailureReason.CHILD_CRASH,
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

    /// Asserts no message in an exception chain contains one hostile value.
    ///
    /// @param exception top-level safe exception
    /// @param hostile hostile source value
    private static void assertThrowableDoesNotContain(Throwable exception, String hostile) {
        @org.jetbrains.annotations.Nullable Throwable current = exception;
        while (current != null) {
            @org.jetbrains.annotations.Nullable String message = current.getMessage();
            if (message != null) {
                assertFalse(message.contains(hostile));
            }
            current = current.getCause();
        }
    }

    /// Asserts all operations reject one redirected launcher home without changing its target record.
    ///
    /// @param redirectedHome symlinked or reparse-point launcher home
    /// @param targetHome direct target launcher home
    /// @throws Exception if direct target setup or verification fails
    private static void assertRejectRedirectedHome(Path redirectedHome, Path targetHome) throws Exception {
        PluginRecoveryStore targetStore = new PluginRecoveryStore(targetHome);
        PluginRecoveryRecord retained = record(
                PluginRecoveryRecord.FailureReason.UNEXPECTED_PROCESS_EXIT,
                100L
        );
        targetStore.save(retained);
        PluginRecoveryStore redirectedStore = new PluginRecoveryStore(redirectedHome);

        assertThrows(IOException.class, redirectedStore::load);
        assertThrows(IOException.class, redirectedStore::clear);
        assertThrows(IOException.class, () -> redirectedStore.save(record(
                PluginRecoveryRecord.FailureReason.UNEXPECTED_PROCESS_EXIT,
                200L
        )));
        assertEquals(retained, targetStore.load().orElseThrow());
    }

    /// Runs one named recovery-store operation for a directory-swap fixture.
    ///
    /// @param operation operation name
    /// @param store guarded recovery store
    /// @throws IOException if the operation rejects the swap
    private static void runStoreOperation(String operation, PluginRecoveryStore store) throws IOException {
        switch (operation) {
            case "load" -> store.load();
            case "clear" -> store.clear();
            case "save" -> store.save(record(
                    PluginRecoveryRecord.FailureReason.UNEXPECTED_PROCESS_EXIT,
                    300L
            ));
            default -> throw new AssertionError("Unknown test operation");
        }
    }

    /// Saves one record from an executor while preserving an `IOException` as the task failure cause.
    ///
    /// @param store concurrent recovery store
    /// @param record record to publish
    private static void saveUnchecked(PluginRecoveryStore store, PluginRecoveryRecord record) {
        try {
            store.save(record);
        } catch (IOException exception) {
            throw new java.io.UncheckedIOException(exception);
        }
    }

    /// Exercises one injected pre-publication fault and verifies rollback and owned-temp cleanup.
    ///
    /// @param temporaryDirectory isolated launcher-local home
    /// @param configuration fault configuration
    /// @throws Exception if fixture setup or verification fails
    private static void assertPrePublicationFailure(
            Path temporaryDirectory,
            java.util.function.Consumer<FaultInjectingOperations> configuration
    ) throws Exception {
        PluginRecoveryRecord previous = record(
                PluginRecoveryRecord.FailureReason.UNEXPECTED_PROCESS_EXIT,
                100L
        );
        new PluginRecoveryStore(temporaryDirectory).save(previous);
        FaultInjectingOperations operations = new FaultInjectingOperations();
        configuration.accept(operations);

        assertThrows(IOException.class, () -> new PluginRecoveryStore(temporaryDirectory, operations).save(record(
                PluginRecoveryRecord.FailureReason.UNEXPECTED_PROCESS_EXIT,
                200L
        )));

        assertEquals(previous, new PluginRecoveryStore(temporaryDirectory).load().orElseThrow());
        assertNoControlledTemporaryFiles(temporaryDirectory);
    }

    /// Asserts no regular controlled temporary sibling remains.
    ///
    /// @param temporaryDirectory launcher-local home
    /// @throws IOException if enumeration fails
    private static void assertNoControlledTemporaryFiles(Path temporaryDirectory) throws IOException {
        assertEquals(0L, countControlledTemporaryFiles(temporaryDirectory));
    }

    /// Counts controlled temporary siblings without traversing their contents.
    ///
    /// @param temporaryDirectory launcher-local home
    /// @return matching sibling count
    /// @throws IOException if enumeration fails
    private static long countControlledTemporaryFiles(Path temporaryDirectory) throws IOException {
        try (java.nio.file.DirectoryStream<Path> entries = Files.newDirectoryStream(
                temporaryDirectory,
                PluginRecoveryStore.TEMP_FILE_PREFIX + "*"
        )) {
            long count = 0L;
            for (Path ignored : entries) {
                count++;
            }
            return count;
        }
    }

    /// File operations that replace the launcher home with a symlink during its second identity inspection.
    @NotNullByDefault
    private static final class SwapOnSecondInspectionOperations extends PluginRecoveryStore.FileOperations {
        /// Direct launcher home that will be replaced.
        private final Path directHome;

        /// Destination retaining the original direct launcher home.
        private final Path movedDirectHome;

        /// Existing directory targeted by the replacement symlink.
        private final Path redirectedHome;

        /// Number of completed direct-home attribute inspections.
        private int inspections;

        /// Creates deterministic swap operations for one isolated fixture.
        ///
        /// @param directHome direct launcher home
        /// @param movedDirectHome destination retaining the direct home
        /// @param redirectedHome replacement symlink target
        private SwapOnSecondInspectionOperations(
                Path directHome,
                Path movedDirectHome,
                Path redirectedHome
        ) {
            this.directHome = directHome;
            this.movedDirectHome = movedDirectHome;
            this.redirectedHome = redirectedHome;
        }

        /// Swaps the exact launcher home after its second no-follow attribute read.
        ///
        /// @param path inspected component
        /// @return attributes captured before the deterministic swap
        /// @throws IOException if inspection or fixture mutation fails
        @Override
        BasicFileAttributes readAttributes(Path path) throws IOException {
            BasicFileAttributes attributes = super.readAttributes(path);
            if (path.equals(directHome) && ++inspections == 2) {
                Files.move(directHome, movedDirectHome, StandardCopyOption.ATOMIC_MOVE);
                Files.createSymbolicLink(directHome, redirectedHome);
            }
            return attributes;
        }
    }

    /// File operations that hold both created temporary files open until concurrent ownership is proven.
    @NotNullByDefault
    private static final class ConcurrentCreateOperations extends PluginRecoveryStore.FileOperations {
        /// Barrier reached after each distinct temporary file is created.
        private final CountDownLatch createdFiles = new CountDownLatch(2);

        /// Creates concurrent publication operations.
        private ConcurrentCreateOperations() {
        }

        /// Creates one unique temporary file and waits for the other save to create its own file.
        ///
        /// @param path unique temporary path
        /// @return open writable channel
        /// @throws IOException if creation, waiting, or interruption fails
        @Override
        FileChannel createNewWritable(Path path) throws IOException {
            FileChannel channel = super.createNewWritable(path);
            createdFiles.countDown();
            try {
                if (!createdFiles.await(5L, TimeUnit.SECONDS)) {
                    channel.close();
                    throw new IOException("Concurrent temporary-file creation did not reach the barrier");
                }
                return channel;
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                channel.close();
                throw new IOException("Concurrent temporary-file creation was interrupted", exception);
            }
        }
    }

    /// Scriptable filesystem failures for publication-boundary and cleanup tests.
    @NotNullByDefault
    private static final class FaultInjectingOperations extends PluginRecoveryStore.FileOperations {
        /// Whether atomic replacement reports explicit platform non-support.
        private boolean atomicUnsupported;

        /// Whether atomic replacement fails for an ordinary I/O reason.
        private boolean failAtomicMove;

        /// Whether non-atomic replacement fails.
        private boolean failFallbackMove;

        /// Whether forcing temporary file contents fails.
        private boolean failTemporaryForce;

        /// Whether forcing parent-directory metadata fails after publication.
        private boolean failDirectoryForce;

        /// Whether cleanup of an owned temporary file fails.
        private boolean failCleanup;

        /// Number of attempted atomic replacements.
        private int atomicMoves;

        /// Number of attempted fallback replacements.
        private int fallbackMoves;

        /// Number of attempted directory forces.
        private int directoryForces;

        /// Creates filesystem operations with every fault disabled.
        private FaultInjectingOperations() {
        }

        /// Forces temporary contents unless the scripted fault is enabled.
        ///
        /// @param channel open owned temporary-file channel
        /// @throws IOException when scripted or when the real force fails
        @Override
        void forceTemporary(FileChannel channel) throws IOException {
            if (failTemporaryForce) {
                throw new IOException("injected temporary force failure");
            }
            super.forceTemporary(channel);
        }

        /// Attempts atomic replacement or emits the configured failure.
        ///
        /// @param source owned temporary file
        /// @param target recovery document
        /// @throws IOException when scripted or when the real move fails
        @Override
        void atomicReplace(Path source, Path target) throws IOException {
            atomicMoves++;
            if (atomicUnsupported) {
                throw new AtomicMoveNotSupportedException(source.toString(), target.toString(), "injected");
            }
            if (failAtomicMove) {
                throw new IOException("injected atomic move failure");
            }
            super.atomicReplace(source, target);
        }

        /// Attempts fallback replacement or emits the configured failure.
        ///
        /// @param source owned temporary file
        /// @param target recovery document
        /// @throws IOException when scripted or when the real move fails
        @Override
        void replace(Path source, Path target) throws IOException {
            fallbackMoves++;
            if (failFallbackMove) {
                throw new IOException("injected fallback move failure");
            }
            super.replace(source, target);
        }

        /// Forces directory metadata or emits the configured post-publication failure.
        ///
        /// @param directory launcher-local home
        /// @throws IOException when scripted or when the real force fails
        @Override
        void forceDirectory(Path directory) throws IOException {
            directoryForces++;
            if (failDirectoryForce) {
                throw new IOException("injected directory force failure");
            }
            super.forceDirectory(directory);
        }

        /// Deletes one owned temporary file or emits the configured cleanup failure.
        ///
        /// @param temporaryFile exact owned temporary path
        /// @throws IOException when scripted or when deletion fails
        @Override
        void deleteOwnedTemporary(Path temporaryFile) throws IOException {
            if (failCleanup) {
                throw new IOException("injected cleanup failure");
            }
            super.deleteOwnedTemporary(temporaryFile);
        }
    }
}
