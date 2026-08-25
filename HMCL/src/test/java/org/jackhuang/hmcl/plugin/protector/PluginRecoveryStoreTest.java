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
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
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
        for (String reserved : List.of(
                "CON",
                "con.txt",
                "logs/PrN.log",
                "AUX",
                "nul.dump",
                "COM1",
                "com9.txt",
                "LPT1",
                "lPt9.log"
        )) {
            assertThrows(IllegalArgumentException.class, () -> recordWithLog(reserved));
        }
        assertThrows(IllegalArgumentException.class, () -> recordWithLog("logs/file."));
        assertThrows(IllegalArgumentException.class, () -> recordWithLog("logs/file "));
        assertThrows(IllegalArgumentException.class, () -> recordWithLog("logs/file.txt:secret"));
        assertThrows(IllegalArgumentException.class, () -> recordWithLog("logs/./file.log"));
        assertThrows(IllegalArgumentException.class, () -> recordWithLog("logs/../file.log"));
        assertThrows(IllegalArgumentException.class, () -> recordWithLog("logs//file.log"));
    }

    /// Enforces the complete failure-reason, pre-ready stage, and active-identity matrix in constructors and JSON.
    ///
    /// @param temporaryDirectory isolated launcher-local home
    /// @throws Exception if fixture persistence fails
    @Test
    public void enforceRecoverySemanticMatrixConsistently(@TempDir Path temporaryDirectory) throws Exception {
        List<ActiveIdentities> identities = List.of(
                new ActiveIdentities(null, null),
                new ActiveIdentities("org.example.provider", null),
                new ActiveIdentities(null, "org.example.plugin"),
                new ActiveIdentities("org.example.provider", "org.example.plugin")
        );
        PluginRecoveryStore store = new PluginRecoveryStore(temporaryDirectory);
        Path recoveryFile = temporaryDirectory.resolve(PluginRecoveryStore.FILE_NAME);

        for (PluginRecoveryRecord.FailureReason reason : PluginRecoveryRecord.FailureReason.values()) {
            for (ProtectorStage stage : ProtectorStage.values()) {
                for (ActiveIdentities active : identities) {
                    boolean legal = isLegalRecoveryCombination(reason, stage, active);
                    if (legal) {
                        PluginRecoveryRecord expected = assertDoesNotThrow(
                                () -> recoveryRecord(reason, stage, active)
                        );
                        Files.writeString(
                                recoveryFile,
                                recoveryDocument(reason, stage, active),
                                StandardCharsets.UTF_8
                        );
                        assertEquals(expected, store.load().orElseThrow());
                    } else {
                        assertThrows(
                                IllegalArgumentException.class,
                                () -> recoveryRecord(reason, stage, active)
                        );
                        Files.writeString(
                                recoveryFile,
                                recoveryDocument(reason, stage, active),
                                StandardCharsets.UTF_8
                        );
                        assertThrows(IOException.class, store::load);
                    }
                }
            }
        }
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

    /// Uses no-follow attributes to establish absence and propagates every uncertain attribute failure.
    ///
    /// @param temporaryDirectory isolated filesystem root
    /// @throws Exception if fixture setup fails
    @Test
    public void inspectHomeAbsenceAndFailClosedForUncertainAttributes(@TempDir Path temporaryDirectory)
            throws Exception {
        Path absentHome = temporaryDirectory.resolve("absent-home");
        AttributeFailureOperations missing = new AttributeFailureOperations(absentHome, true, false);
        assertTrue(new PluginRecoveryStore(absentHome, missing).load().isEmpty());
        assertEquals(1, missing.targetInspections);

        Path deniedHome = temporaryDirectory.resolve("denied-home");
        Files.createDirectories(deniedHome);
        assertThrows(
                AccessDeniedException.class,
                () -> new PluginRecoveryStore(
                        deniedHome,
                        new AttributeFailureOperations(deniedHome, false, true)
                ).load()
        );

        Path uncertainHome = temporaryDirectory.resolve("uncertain-home");
        Files.createDirectories(uncertainHome);
        assertThrows(
                IOException.class,
                () -> new PluginRecoveryStore(
                        uncertainHome,
                        new AttributeFailureOperations(uncertainHome, false, false)
                ).load()
        );
    }

    /// Fails closed when clear cannot establish the exact target's no-follow type.
    ///
    /// @param temporaryDirectory isolated filesystem root
    /// @throws Exception if fixture setup or verification fails
    @Test
    public void rejectUncertainTargetAttributesDuringClear(@TempDir Path temporaryDirectory) throws Exception {
        for (boolean denied : List.of(true, false)) {
            Path home = temporaryDirectory.resolve(Boolean.toString(denied));
            PluginRecoveryRecord retained = record(
                    PluginRecoveryRecord.FailureReason.UNEXPECTED_PROCESS_EXIT,
                    100L
            );
            new PluginRecoveryStore(home).save(retained);
            Path recoveryFile = home.resolve(PluginRecoveryStore.FILE_NAME);
            PluginRecoveryStore store = new PluginRecoveryStore(
                    home,
                    new TargetAttributeFailureOperations(recoveryFile, denied)
            );

            if (denied) {
                assertThrows(AccessDeniedException.class, store::clear);
            } else {
                assertThrows(IOException.class, store::clear);
            }
            assertEquals(retained, new PluginRecoveryStore(home).load().orElseThrow());
        }
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

    /// Rejects a non-regular no-follow target before load or snapshot opens a potentially blocking handle.
    ///
    /// @param temporaryDirectory isolated launcher-local home
    /// @throws Exception if fixture setup or verification fails
    @Test
    public void rejectSpecialRecoveryTargetBeforeOpeningIt(@TempDir Path temporaryDirectory) throws Exception {
        PluginRecoveryRecord retained = record(
                PluginRecoveryRecord.FailureReason.UNEXPECTED_PROCESS_EXIT,
                100L
        );
        new PluginRecoveryStore(temporaryDirectory).save(retained);
        Path recoveryFile = temporaryDirectory.resolve(PluginRecoveryStore.FILE_NAME);
        SpecialTargetOperations operations = new SpecialTargetOperations(recoveryFile);
        PluginRecoveryStore guarded = new PluginRecoveryStore(temporaryDirectory, operations);

        assertThrows(IOException.class, guarded::load);
        PluginRecoveryStore.PublicationException exception = assertThrows(
                PluginRecoveryStore.PublicationException.class,
                () -> guarded.save(record(
                        PluginRecoveryRecord.FailureReason.UNEXPECTED_PROCESS_EXIT,
                        200L
                ))
        );

        assertEquals(PluginRecoveryStore.PublicationOutcome.NOT_PUBLISHED, exception.result().outcome());
        assertEquals(0, operations.readOpens);
        assertEquals(retained, new PluginRecoveryStore(temporaryDirectory).load().orElseThrow());
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
                    retainedHome,
                    root.resolve("external-operation.lock")
            );
            PluginRecoveryStore guardedStore = new PluginRecoveryStore(directHome, operations);

            assertThrows(IOException.class, () -> runStoreOperation(operation, guardedStore));

            assertTrue(Files.isDirectory(movedDirectHome), operation + " fixture did not complete directory swap");
            assertEquals(original, new PluginRecoveryStore(movedDirectHome).load().orElseThrow());
            assertEquals(retained, new PluginRecoveryStore(retainedHome).load().orElseThrow());
        }
    }

    /// Never deletes a same-UUID file through a replacement home after temp creation makes identity uncertain.
    ///
    /// @param temporaryDirectory isolated filesystem root
    /// @throws Exception if fixture mutation or verification fails
    @Test
    public void retainReplacementHomeTemporaryWhenIdentityChangesAfterCreation(@TempDir Path temporaryDirectory)
            throws Exception {
        Path directHome = temporaryDirectory.resolve("direct-home");
        Path movedHome = temporaryDirectory.resolve("moved-home");
        Files.createDirectories(directHome);
        SwapAfterTemporaryCreationOperations operations = new SwapAfterTemporaryCreationOperations(
                directHome,
                movedHome,
                temporaryDirectory.resolve("external-operation.lock")
        );

        assertThrows(
                PluginRecoveryStore.PublicationException.class,
                () -> new PluginRecoveryStore(directHome, operations).save(record(
                        PluginRecoveryRecord.FailureReason.UNEXPECTED_PROCESS_EXIT,
                        100L
                ))
        );

        Path createdPath = java.util.Objects.requireNonNull(operations.createdPath);
        assertFalse(operations.cleanupAttempted);
        assertTrue(Files.exists(directHome.resolve(createdPath.getFileName())));
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

    /// Clears only the exact recovery document and never claims ownership of residual temporary-looking siblings.
    ///
    /// @param temporaryDirectory isolated launcher-local home
    /// @throws Exception if fixture setup or clearing fails
    @Test
    public void clearOnlyExactRecoveryTargets(@TempDir Path temporaryDirectory) throws Exception {
        PluginRecoveryStore store = new PluginRecoveryStore(temporaryDirectory);
        store.save(record(PluginRecoveryRecord.FailureReason.UNEXPECTED_PROCESS_EXIT, 100L));
        Path exactUuidTemporary = temporaryDirectory.resolve(
                PluginRecoveryStore.FILE_NAME + ".tmp-00000000-0000-0000-0000-000000000000"
        );
        Files.writeString(exactUuidTemporary, "retain-exact", StandardCharsets.UTF_8);
        Path prefixCollision = temporaryDirectory.resolve(
                PluginRecoveryStore.FILE_NAME + ".tmp-00000000-0000-0000-0000-000000000000.extra"
        );
        Files.writeString(prefixCollision, "retain-collision", StandardCharsets.UTF_8);
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
        assertEquals("retain-exact", Files.readString(exactUuidTemporary, StandardCharsets.UTF_8));
        assertEquals("retain-collision", Files.readString(prefixCollision, StandardCharsets.UTF_8));
        assertEquals("retain-fixed", Files.readString(unrelatedFixedTemporary, StandardCharsets.UTF_8));
        assertEquals(
                "retain-directory",
                Files.readString(staleTemporaryDirectory.resolve("sentinel"), StandardCharsets.UTF_8)
        );
        assertEquals("retain", Files.readString(unrelated, StandardCharsets.UTF_8));
    }

    /// Serializes two store instances for the same real home before the second can create an owned temporary file.
    ///
    /// @param temporaryDirectory isolated launcher-local home
    /// @throws Exception if concurrent persistence or verification fails
    @Test
    public void publishConcurrentSavesThroughUniqueTemporaryFiles(@TempDir Path temporaryDirectory) throws Exception {
        BlockingTemporaryForceOperations firstOperations = new BlockingTemporaryForceOperations();
        ObservingCreateOperations secondOperations = new ObservingCreateOperations();
        PluginRecoveryStore firstStore = new PluginRecoveryStore(temporaryDirectory, firstOperations);
        PluginRecoveryStore secondStore = new PluginRecoveryStore(temporaryDirectory, secondOperations);
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
            Future<?> firstSave = executor.submit(() -> saveUnchecked(firstStore, first));
            assertTrue(firstOperations.forceStarted.await(5L, TimeUnit.SECONDS));
            Future<?> secondSave = executor.submit(() -> saveUnchecked(secondStore, second));

            assertFalse(secondOperations.created.await(250L, TimeUnit.MILLISECONDS));
            firstOperations.allowForce.countDown();
            firstSave.get(10L, TimeUnit.SECONDS);
            secondSave.get(10L, TimeUnit.SECONDS);

            assertEquals(second, secondStore.load().orElseThrow());
            assertCanonicalTemporaryName(firstOperations.createdPath);
            assertCanonicalTemporaryName(secondOperations.createdPath);
            assertNoControlledTemporaryFiles(temporaryDirectory);
        } finally {
            firstOperations.allowForce.countDown();
            executor.shutdownNow();
        }
    }

    /// Serializes clear behind an active save from another store instance so clear wins deterministically.
    ///
    /// @param temporaryDirectory isolated launcher-local home
    /// @throws Exception if concurrent persistence or verification fails
    @Test
    public void serializeCrossInstanceSaveAndClear(@TempDir Path temporaryDirectory) throws Exception {
        new PluginRecoveryStore(temporaryDirectory).save(record(
                PluginRecoveryRecord.FailureReason.UNEXPECTED_PROCESS_EXIT,
                100L
        ));
        BlockingTemporaryForceOperations operations = new BlockingTemporaryForceOperations();
        PluginRecoveryStore savingStore = new PluginRecoveryStore(temporaryDirectory, operations);
        PluginRecoveryStore clearingStore = new PluginRecoveryStore(temporaryDirectory);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> save = executor.submit(() -> saveUnchecked(savingStore, record(
                    PluginRecoveryRecord.FailureReason.UNEXPECTED_PROCESS_EXIT,
                    200L
            )));
            assertTrue(operations.forceStarted.await(5L, TimeUnit.SECONDS));
            Future<?> clear = executor.submit(() -> clearUnchecked(clearingStore));

            assertThrows(TimeoutException.class, () -> clear.get(250L, TimeUnit.MILLISECONDS));
            operations.allowForce.countDown();
            save.get(10L, TimeUnit.SECONDS);
            clear.get(10L, TimeUnit.SECONDS);

            assertTrue(clearingStore.load().isEmpty());
            assertNoControlledTemporaryFiles(temporaryDirectory);
        } finally {
            operations.allowForce.countDown();
            executor.shutdownNow();
        }
    }

    /// Holds the repository lock as another process so its repair cannot overwrite a later publication.
    ///
    /// @param temporaryDirectory isolated launcher-local home
    /// @throws Exception if lock acquisition, persistence, or verification fails
    @Test
    public void serializePublicationAndRepairAcrossIndependentFileLocks(@TempDir Path temporaryDirectory)
            throws Exception {
        PluginRecoveryRecord previous = record(
                PluginRecoveryRecord.FailureReason.UNEXPECTED_PROCESS_EXIT,
                100L
        );
        PluginRecoveryRecord replacement = record(
                PluginRecoveryRecord.FailureReason.UNEXPECTED_PROCESS_EXIT,
                200L
        );
        PluginRecoveryStore store = new PluginRecoveryStore(temporaryDirectory);
        store.save(previous);
        byte[] previousBytes = Files.readAllBytes(temporaryDirectory.resolve(PluginRecoveryStore.FILE_NAME));
        Path lockFile = temporaryDirectory.resolve("plugin-startup-recovery.lock");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (FileChannel lockChannel = FileChannel.open(
                lockFile,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.WRITE
        ); FileLock ignored = lockChannel.lock()) {
            Future<?> publication = executor.submit(() -> saveUnchecked(
                    new PluginRecoveryStore(temporaryDirectory),
                    replacement
            ));

            assertThrows(TimeoutException.class, () -> publication.get(250L, TimeUnit.MILLISECONDS));
            Files.write(temporaryDirectory.resolve(PluginRecoveryStore.FILE_NAME), previousBytes);
        } finally {
            executor.shutdown();
        }

        assertTrue(executor.awaitTermination(10L, TimeUnit.SECONDS));
        assertEquals(replacement, store.load().orElseThrow());
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

        PluginRecoveryStore.PublicationResult result =
                new PluginRecoveryStore(temporaryDirectory, operations).save(replacement);

        assertEquals(1, operations.directoryForces);
        assertEquals(PluginRecoveryStore.PublicationOutcome.PUBLISHED, result.outcome());
        assertEquals(PluginRecoveryStore.PublicationDurability.FILE_FORCED_ONLY, result.durability());
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

        PluginRecoveryStore.PublicationException exception = assertThrows(
                PluginRecoveryStore.PublicationException.class,
                () -> new PluginRecoveryStore(temporaryDirectory, operations).save(record(
                        PluginRecoveryRecord.FailureReason.UNEXPECTED_PROCESS_EXIT,
                        200L
                ))
        );

        assertEquals(PluginRecoveryStore.PublicationOutcome.NOT_PUBLISHED, exception.result().outcome());
        assertEquals(1, exception.getSuppressed().length);
        assertEquals(previous, new PluginRecoveryStore(temporaryDirectory).load().orElseThrow());
        assertEquals(1L, countControlledTemporaryFiles(temporaryDirectory));
    }

    /// Reconciles real atomic and fallback moves that publish successfully before their wrappers throw.
    ///
    /// @param temporaryDirectory isolated launcher-local home
    /// @throws Exception if fixture setup or reconciliation fails
    @Test
    public void recognizePublishedRecordWhenMoveThrowsAfterCompletion(@TempDir Path temporaryDirectory)
            throws Exception {
        for (AmbiguousMoveMode mode : List.of(
                AmbiguousMoveMode.ATOMIC_MOVE_THEN_THROW,
                AmbiguousMoveMode.FALLBACK_MOVE_THEN_THROW
        )) {
            Path home = temporaryDirectory.resolve(mode.name());
            PluginRecoveryRecord previous = record(
                    PluginRecoveryRecord.FailureReason.UNEXPECTED_PROCESS_EXIT,
                    100L
            );
            PluginRecoveryRecord replacement = record(
                    PluginRecoveryRecord.FailureReason.UNEXPECTED_PROCESS_EXIT,
                    200L
            );
            new PluginRecoveryStore(home).save(previous);

            AmbiguousMoveOperations operations = new AmbiguousMoveOperations(home, mode);
            PluginRecoveryStore.PublicationResult result = new PluginRecoveryStore(home, operations).save(replacement);

            assertEquals(PluginRecoveryStore.PublicationOutcome.PUBLISHED, result.outcome());
            assertTrue(result.durability() == PluginRecoveryStore.PublicationDurability.FILE_FORCED_ONLY
                    || result.durability() == PluginRecoveryStore.PublicationDurability.FILE_AND_DIRECTORY_FORCED);
            assertEquals(1, operations.publishedTargetForces);
            assertEquals(replacement, new PluginRecoveryStore(home).load().orElseThrow());
            assertNoControlledTemporaryFiles(home);
        }
    }

    /// Reports known reconciled publication with unknown durability when the requested target cannot be forced.
    ///
    /// @param temporaryDirectory isolated launcher-local home
    /// @throws Exception if fixture setup or reconciliation fails
    @Test
    public void reportUnknownDurabilityWhenReconciledTargetForceFails(@TempDir Path temporaryDirectory)
            throws Exception {
        PluginRecoveryRecord replacement = record(
                PluginRecoveryRecord.FailureReason.UNEXPECTED_PROCESS_EXIT,
                200L
        );
        new PluginRecoveryStore(temporaryDirectory).save(record(
                PluginRecoveryRecord.FailureReason.UNEXPECTED_PROCESS_EXIT,
                100L
        ));
        AmbiguousMoveOperations operations = new AmbiguousMoveOperations(
                temporaryDirectory,
                AmbiguousMoveMode.ATOMIC_MOVE_THEN_THROW
        );
        operations.failPublishedTargetForce = true;

        PluginRecoveryStore.PublicationResult result =
                new PluginRecoveryStore(temporaryDirectory, operations).save(replacement);

        assertEquals(PluginRecoveryStore.PublicationOutcome.PUBLISHED, result.outcome());
        assertEquals(PluginRecoveryStore.PublicationDurability.UNKNOWN, result.durability());
        assertEquals(1, operations.publishedTargetForces);
        assertEquals(replacement, new PluginRecoveryStore(temporaryDirectory).load().orElseThrow());
        assertNoControlledTemporaryFiles(temporaryDirectory);
    }

    /// Repairs partial, missing, and corrupt fallback targets to the exact previous bounded snapshot.
    ///
    /// @param temporaryDirectory isolated launcher-local home
    /// @throws Exception if fixture setup or repair verification fails
    @Test
    public void repairAmbiguousFallbackTargetsToPreviousSnapshot(@TempDir Path temporaryDirectory) throws Exception {
        for (AmbiguousMoveMode mode : List.of(
                AmbiguousMoveMode.FALLBACK_PARTIAL_TARGET,
                AmbiguousMoveMode.FALLBACK_MISSING_TARGET,
                AmbiguousMoveMode.FALLBACK_CORRUPT_TARGET
        )) {
            assertFallbackRepair(temporaryDirectory.resolve(mode.name()), mode, null);
        }
        byte[] corruptPrevious = "{previous-corrupt".getBytes(StandardCharsets.UTF_8);
        assertFallbackRepair(
                temporaryDirectory.resolve("corrupt-previous"),
                AmbiguousMoveMode.FALLBACK_CORRUPT_TARGET,
                corruptPrevious
        );
    }

    /// Reports an explicit indeterminate outcome when an ambiguous target cannot be repaired atomically.
    ///
    /// @param temporaryDirectory isolated launcher-local home
    /// @throws Exception if fixture setup fails
    @Test
    public void reportIndeterminateOutcomeWhenFallbackRepairFails(@TempDir Path temporaryDirectory) throws Exception {
        PluginRecoveryRecord previous = record(
                PluginRecoveryRecord.FailureReason.UNEXPECTED_PROCESS_EXIT,
                100L
        );
        new PluginRecoveryStore(temporaryDirectory).save(previous);
        PluginRecoveryStore.PublicationException exception = assertThrows(
                PluginRecoveryStore.PublicationException.class,
                () -> new PluginRecoveryStore(
                        temporaryDirectory,
                        new AmbiguousMoveOperations(
                                temporaryDirectory,
                                AmbiguousMoveMode.FALLBACK_REPAIR_FAILURE
                        )
                ).save(record(PluginRecoveryRecord.FailureReason.UNEXPECTED_PROCESS_EXIT, 200L))
        );

        assertEquals(PluginRecoveryStore.PublicationOutcome.INDETERMINATE, exception.result().outcome());
        assertEquals(PluginRecoveryStore.PublicationDurability.UNKNOWN, exception.result().durability());
    }

    /// Reports a typed indeterminate outcome when home identity cannot be revalidated after a successful move.
    ///
    /// @param temporaryDirectory isolated launcher-local home
    /// @throws Exception if fixture setup fails
    @Test
    public void reportIndeterminateOutcomeAfterPostMoveIdentityFailure(@TempDir Path temporaryDirectory)
            throws Exception {
        new PluginRecoveryStore(temporaryDirectory).save(record(
                PluginRecoveryRecord.FailureReason.UNEXPECTED_PROCESS_EXIT,
                100L
        ));
        PluginRecoveryStore.PublicationException exception = assertThrows(
                PluginRecoveryStore.PublicationException.class,
                () -> new PluginRecoveryStore(
                        temporaryDirectory,
                        new AmbiguousMoveOperations(
                                temporaryDirectory,
                                AmbiguousMoveMode.POST_MOVE_IDENTITY_FAILURE
                        )
                ).save(record(PluginRecoveryRecord.FailureReason.UNEXPECTED_PROCESS_EXIT, 200L))
        );

        assertEquals(PluginRecoveryStore.PublicationOutcome.INDETERMINATE, exception.result().outcome());
        assertEquals(PluginRecoveryStore.PublicationDurability.UNKNOWN, exception.result().durability());
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

    /// Builds one recovery record with caller-selected semantic coordinates.
    ///
    /// @param reason controlled failure reason
    /// @param stage last startup stage
    /// @param active active identity fields
    /// @return recovery record candidate
    private static PluginRecoveryRecord recoveryRecord(
            PluginRecoveryRecord.FailureReason reason,
            ProtectorStage stage,
            ActiveIdentities active
    ) {
        return new PluginRecoveryRecord(
                1_777_000_000_000L,
                reason.category(),
                reason,
                stage,
                100L,
                active.providerId(),
                active.pluginId(),
                null,
                null
        );
    }

    /// Serializes one raw semantic matrix fixture without invoking the record constructor.
    ///
    /// @param reason controlled failure reason
    /// @param stage last startup stage
    /// @param active active identity fields
    /// @return strict recovery JSON
    private static String recoveryDocument(
            PluginRecoveryRecord.FailureReason reason,
            ProtectorStage stage,
            ActiveIdentities active
    ) {
        return "{\"schemaVersion\":1"
                + ",\"failureTimestampEpochMillis\":1777000000000"
                + ",\"failureCategory\":\"" + reason.category().wireName() + "\""
                + ",\"failureReason\":\"" + reason.wireName() + "\""
                + ",\"lastStage\":\"" + stage.wireName() + "\""
                + ",\"lastHeartbeatMonotonicNanos\":100"
                + ",\"activeProviderId\":" + jsonString(active.providerId())
                + ",\"activePluginId\":" + jsonString(active.pluginId())
                + ",\"launcherLogReference\":null"
                + ",\"diagnosticDumpReference\":null}";
    }

    /// Returns one simple JSON string or null fixture value.
    ///
    /// @param value canonical identifier, or `null`
    /// @return JSON scalar
    private static String jsonString(@org.jetbrains.annotations.Nullable String value) {
        return value == null ? "null" : "\"" + value + "\"";
    }

    /// Returns whether one semantic matrix coordinate represents a possible supervised startup failure.
    ///
    /// @param reason controlled failure reason
    /// @param stage last startup stage
    /// @param active active identity fields
    /// @return whether the combination is legal
    private static boolean isLegalRecoveryCombination(
            PluginRecoveryRecord.FailureReason reason,
            ProtectorStage stage,
            ActiveIdentities active
    ) {
        if (stage == ProtectorStage.UI_READY) {
            return false;
        }
        boolean structurallyValid = active.providerId() == null && active.pluginId() == null
                || stage == ProtectorStage.RUNTIME_PROVIDERS_LOADING
                && active.providerId() != null
                && active.pluginId() == null
                || stage == ProtectorStage.ORDINARY_PLUGINS_LOADING
                && active.providerId() == null
                && active.pluginId() != null;
        if (!structurallyValid) {
            return false;
        }
        return switch (reason) {
            case CORE_DEADLINE_EXCEEDED -> stage == ProtectorStage.JVM_STARTED
                    && active.providerId() == null
                    && active.pluginId() == null;
            case PROVIDER_DEADLINE_EXCEEDED -> stage == ProtectorStage.RUNTIME_PROVIDERS_LOADING
                    && active.providerId() != null;
            case PLUGIN_DEADLINE_EXCEEDED -> stage == ProtectorStage.ORDINARY_PLUGINS_LOADING
                    && active.pluginId() != null;
            case UNEXPECTED_PROCESS_EXIT, CHILD_CRASH, HEARTBEAT_LOST, HARD_STARTUP_DEADLINE_EXCEEDED -> true;
        };
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

    /// Clears one store from an executor while preserving an `IOException` as the task failure cause.
    ///
    /// @param store concurrent recovery store
    private static void clearUnchecked(PluginRecoveryStore store) {
        try {
            store.clear();
        } catch (IOException exception) {
            throw new java.io.UncheckedIOException(exception);
        }
    }

    /// Asserts one captured owned path uses exactly the canonical UUID temporary filename grammar.
    ///
    /// @param path captured owned temporary path, or `null` when creation did not occur
    private static void assertCanonicalTemporaryName(@org.jetbrains.annotations.Nullable Path path) {
        assertTrue(path != null);
        assertTrue(path.getFileName().toString().matches(
                "plugin-startup-recovery\\.json\\.tmp-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-"
                        + "[0-9a-f]{4}-[0-9a-f]{12}"
        ));
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

        PluginRecoveryStore.PublicationException exception = assertThrows(
                PluginRecoveryStore.PublicationException.class,
                () -> new PluginRecoveryStore(temporaryDirectory, operations).save(record(
                        PluginRecoveryRecord.FailureReason.UNEXPECTED_PROCESS_EXIT,
                        200L
                ))
        );

        assertEquals(PluginRecoveryStore.PublicationOutcome.NOT_PUBLISHED, exception.result().outcome());
        assertEquals(PluginRecoveryStore.PublicationDurability.NOT_APPLICABLE, exception.result().durability());
        assertEquals(previous, new PluginRecoveryStore(temporaryDirectory).load().orElseThrow());
        assertNoControlledTemporaryFiles(temporaryDirectory);
    }

    /// Exercises one ambiguous fallback outcome and verifies exact restoration of the bounded prior snapshot.
    ///
    /// @param home isolated launcher-local home
    /// @param mode fallback target outcome
    /// @param rawPrevious raw prior bytes, or `null` to create a valid prior record
    /// @throws Exception if fixture setup or repair verification fails
    private static void assertFallbackRepair(
            Path home,
            AmbiguousMoveMode mode,
            byte @org.jetbrains.annotations.Nullable [] rawPrevious
    ) throws Exception {
        PluginRecoveryRecord previous = record(
                PluginRecoveryRecord.FailureReason.UNEXPECTED_PROCESS_EXIT,
                100L
        );
        new PluginRecoveryStore(home).save(previous);
        Path recoveryFile = home.resolve(PluginRecoveryStore.FILE_NAME);
        if (rawPrevious != null) {
            Files.write(recoveryFile, rawPrevious);
        }
        byte[] expectedPrevious = Files.readAllBytes(recoveryFile);

        PluginRecoveryStore.PublicationException exception = assertThrows(
                PluginRecoveryStore.PublicationException.class,
                () -> new PluginRecoveryStore(home, new AmbiguousMoveOperations(home, mode)).save(record(
                        PluginRecoveryRecord.FailureReason.UNEXPECTED_PROCESS_EXIT,
                        200L
                ))
        );

        assertEquals(PluginRecoveryStore.PublicationOutcome.NOT_PUBLISHED, exception.result().outcome());
        assertEquals(PluginRecoveryStore.PublicationDurability.NOT_APPLICABLE, exception.result().durability());
        assertTrue(java.util.Arrays.equals(expectedPrevious, Files.readAllBytes(recoveryFile)));
        assertNoControlledTemporaryFiles(home);
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

        /// Lock file outside launcher home so Windows permits the deterministic directory move.
        private final Path externalLockFile;

        /// Number of completed direct-home attribute inspections.
        private int inspections;

        /// Creates deterministic swap operations for one isolated fixture.
        ///
        /// @param directHome direct launcher home
        /// @param movedDirectHome destination retaining the direct home
        /// @param redirectedHome replacement symlink target
        /// @param externalLockFile external injected operation lock file
        private SwapOnSecondInspectionOperations(
                Path directHome,
                Path movedDirectHome,
                Path redirectedHome,
                Path externalLockFile
        ) {
            this.directHome = directHome;
            this.movedDirectHome = movedDirectHome;
            this.redirectedHome = redirectedHome;
            this.externalLockFile = externalLockFile;
        }

        /// Opens the operation lock outside launcher home for this directory-swap fixture.
        ///
        /// @param ignored normal repository lock path
        /// @return open external lock-file channel
        /// @throws IOException if opening fails
        @Override
        FileChannel openOperationLock(Path ignored) throws IOException {
            return FileChannel.open(
                    externalLockFile,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.WRITE
            );
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

    /// File operations that replace launcher home immediately after creating an owned temporary file.
    @NotNullByDefault
    private static final class SwapAfterTemporaryCreationOperations extends PluginRecoveryStore.FileOperations {
        /// Direct launcher home replaced during the save.
        private final Path directHome;

        /// Destination retaining the original home and owned temporary file.
        private final Path movedHome;

        /// Lock file outside launcher home so Windows permits the deterministic directory move.
        private final Path externalLockFile;

        /// Exact created temporary path, or `null` before creation.
        private volatile @org.jetbrains.annotations.Nullable Path createdPath;

        /// Whether launcher home has been physically replaced.
        private boolean swapped;

        /// Whether production attempted pathname cleanup after identity became uncertain.
        private boolean cleanupAttempted;

        /// Creates deterministic post-creation swap operations.
        ///
        /// @param directHome direct launcher home
        /// @param movedHome destination retaining the original home
        /// @param externalLockFile external injected operation lock file
        private SwapAfterTemporaryCreationOperations(Path directHome, Path movedHome, Path externalLockFile) {
            this.directHome = directHome;
            this.movedHome = movedHome;
            this.externalLockFile = externalLockFile;
        }

        /// Opens the operation lock outside launcher home for this directory-swap fixture.
        ///
        /// @param ignored normal repository lock path
        /// @return open external lock-file channel
        /// @throws IOException if opening fails
        @Override
        FileChannel openOperationLock(Path ignored) throws IOException {
            return FileChannel.open(
                    externalLockFile,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.WRITE
            );
        }

        /// Creates the owned temp and replaces launcher home before returning its original-home channel.
        ///
        /// @param path unique temporary path
        /// @return channel bound to the moved original-home file
        /// @throws IOException if creation or fixture mutation fails
        @Override
        FileChannel createNewWritable(Path path) throws IOException {
            FileChannel channel = super.createNewWritable(path);
            createdPath = path;
            try {
                Files.move(directHome, movedHome, StandardCopyOption.ATOMIC_MOVE);
                Files.createDirectories(directHome);
                return channel;
            } catch (IOException failure) {
                channel.close();
                throw failure;
            }
        }

        /// Forces the original-home temp before planting a same-UUID replacement-home sentinel.
        ///
        /// @param channel open original-home temporary channel
        /// @throws IOException if forcing or sentinel creation fails
        @Override
        void forceTemporary(FileChannel channel) throws IOException {
            super.forceTemporary(channel);
            Path originalPath = java.util.Objects.requireNonNull(createdPath);
            Files.writeString(
                    directHome.resolve(originalPath.getFileName()),
                    "replacement-sentinel",
                    StandardCharsets.UTF_8
            );
            swapped = true;
        }

        /// Makes post-swap home identity explicitly indeterminate on every filesystem provider.
        ///
        /// @param path inspected path
        /// @return no-follow attributes before the swap
        /// @throws IOException after the exact launcher home is replaced or when normal inspection fails
        @Override
        BasicFileAttributes readAttributes(Path path) throws IOException {
            if (swapped && path.equals(directHome)) {
                throw new IOException("injected post-creation identity uncertainty");
            }
            return super.readAttributes(path);
        }

        /// Records any unsafe pathname cleanup attempt before delegating.
        ///
        /// @param temporaryFile exact temporary path requested for deletion
        /// @throws IOException if delegated deletion fails
        @Override
        void deleteOwnedTemporary(Path temporaryFile) throws IOException {
            cleanupAttempted = true;
            super.deleteOwnedTemporary(temporaryFile);
        }
    }

    /// File operations that block one save while its owned temporary file is being forced.
    @NotNullByDefault
    private static final class BlockingTemporaryForceOperations extends PluginRecoveryStore.FileOperations {
        /// Signal emitted when temporary forcing begins.
        private final CountDownLatch forceStarted = new CountDownLatch(1);

        /// Gate allowing temporary forcing to continue.
        private final CountDownLatch allowForce = new CountDownLatch(1);

        /// Captured owned temporary path, or `null` before creation.
        private volatile @org.jetbrains.annotations.Nullable Path createdPath;

        /// Creates blocking publication operations.
        private BlockingTemporaryForceOperations() {
        }

        /// Captures one uniquely owned temporary path.
        ///
        /// @param path unique temporary path
        /// @return open writable channel
        /// @throws IOException if creation fails
        @Override
        FileChannel createNewWritable(Path path) throws IOException {
            createdPath = path;
            return super.createNewWritable(path);
        }

        /// Blocks temporary forcing until the concurrent assertion releases it.
        ///
        /// @param channel open owned temporary-file channel
        /// @throws IOException if waiting, interruption, or forcing fails
        @Override
        void forceTemporary(FileChannel channel) throws IOException {
            forceStarted.countDown();
            try {
                if (!allowForce.await(5L, TimeUnit.SECONDS)) {
                    throw new IOException("Timed out waiting to force the active temporary file");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("Temporary forcing was interrupted", exception);
            }
            super.forceTemporary(channel);
        }
    }

    /// File operations that report when a second store instance creates its temporary file.
    @NotNullByDefault
    private static final class ObservingCreateOperations extends PluginRecoveryStore.FileOperations {
        /// Signal emitted on owned temporary creation.
        private final CountDownLatch created = new CountDownLatch(1);

        /// Captured owned temporary path, or `null` before creation.
        private volatile @org.jetbrains.annotations.Nullable Path createdPath;

        /// Creates observing publication operations.
        private ObservingCreateOperations() {
        }

        /// Captures and reports one uniquely owned temporary path.
        ///
        /// @param path unique temporary path
        /// @return open writable channel
        /// @throws IOException if creation fails
        @Override
        FileChannel createNewWritable(Path path) throws IOException {
            createdPath = path;
            created.countDown();
            return super.createNewWritable(path);
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

    /// Attribute operations that expose deterministic missing, denied, or uncertain launcher-home inspection.
    @NotNullByDefault
    private static final class AttributeFailureOperations extends PluginRecoveryStore.FileOperations {
        /// Exact launcher home whose inspection is controlled.
        private final Path target;

        /// Whether controlled inspection reports definite absence.
        private final boolean missing;

        /// Whether controlled inspection reports access denial rather than an indeterminate failure.
        private final boolean denied;

        /// Number of controlled target inspections.
        private int targetInspections;

        /// Creates one controlled attribute boundary.
        ///
        /// @param target exact launcher home
        /// @param missing whether the home is definitely absent
        /// @param denied whether inspection is denied
        private AttributeFailureOperations(Path target, boolean missing, boolean denied) {
            this.target = target;
            this.missing = missing;
            this.denied = denied;
        }

        /// Reads normal components and emits the selected outcome for the exact launcher home.
        ///
        /// @param path inspected path
        /// @return no-follow attributes for uncontrolled components
        /// @throws IOException for the controlled missing, denied, or uncertain outcome
        @Override
        BasicFileAttributes readAttributes(Path path) throws IOException {
            if (!path.equals(target)) {
                return super.readAttributes(path);
            }
            targetInspections++;
            if (missing) {
                throw new NoSuchFileException(path.toString());
            }
            if (denied) {
                throw new AccessDeniedException(path.toString());
            }
            throw new IOException("injected indeterminate attributes");
        }
    }

    /// File operations that make exact recovery-target attribute inspection denied or indeterminate.
    @NotNullByDefault
    private static final class TargetAttributeFailureOperations extends PluginRecoveryStore.FileOperations {
        /// Exact recovery target whose attributes cannot be established.
        private final Path target;

        /// Whether inspection reports access denial.
        private final boolean denied;

        /// Creates one fail-closed target attribute boundary.
        ///
        /// @param target exact recovery target
        /// @param denied whether inspection reports access denial
        private TargetAttributeFailureOperations(Path target, boolean denied) {
            this.target = target;
            this.denied = denied;
        }

        /// Rejects exact target inspection while delegating every home and lock-file component.
        ///
        /// @param path inspected path
        /// @return delegated no-follow attributes
        /// @throws IOException for the controlled target outcome or delegated failure
        @Override
        BasicFileAttributes readAttributes(Path path) throws IOException {
            if (!path.equals(target)) {
                return super.readAttributes(path);
            }
            if (denied) {
                throw new AccessDeniedException(path.toString());
            }
            throw new IOException("injected indeterminate target attributes");
        }
    }

    /// File operations that report the recovery target as a special file and count direct read opens.
    @NotNullByDefault
    private static final class SpecialTargetOperations extends PluginRecoveryStore.FileOperations {
        /// Exact recovery target reported with non-regular attributes.
        private final Path target;

        /// Number of attempted direct read-handle opens.
        private int readOpens;

        /// Creates one deterministic special-target boundary.
        ///
        /// @param target exact recovery target
        private SpecialTargetOperations(Path target) {
            this.target = target;
        }

        /// Reports directory attributes for the exact target and normal attributes for every other path.
        ///
        /// @param path inspected path
        /// @return no-follow attributes
        /// @throws IOException if delegated inspection fails
        @Override
        BasicFileAttributes readAttributes(Path path) throws IOException {
            return path.equals(target) ? super.readAttributes(target.getParent()) : super.readAttributes(path);
        }

        /// Counts any unsafe read open before delegating.
        ///
        /// @param path direct read target
        /// @return open read channel
        /// @throws IOException if opening fails
        @Override
        FileChannel openReadable(Path path) throws IOException {
            readOpens++;
            return super.openReadable(path);
        }
    }

    /// Real filesystem move wrappers that create deterministic ambiguous publication outcomes.
    @NotNullByDefault
    private static final class AmbiguousMoveOperations extends PluginRecoveryStore.FileOperations {
        /// Exact launcher home used for post-move identity failure injection.
        private final Path launcherHome;

        /// Selected ambiguous move behavior.
        private final AmbiguousMoveMode mode;

        /// Number of atomic replacement attempts, including repair.
        private int atomicAttempts;

        /// Whether a real move completed.
        private boolean moveCompleted;

        /// Number of attempts to force a reconciled published target.
        private int publishedTargetForces;

        /// Whether forcing a reconciled published target fails.
        private boolean failPublishedTargetForce;

        /// Creates one deterministic ambiguous move wrapper.
        ///
        /// @param launcherHome exact launcher home
        /// @param mode selected behavior
        private AmbiguousMoveOperations(Path launcherHome, AmbiguousMoveMode mode) {
            this.launcherHome = launcherHome;
            this.mode = mode;
        }

        /// Executes, rejects, or fails atomic replacement according to the selected behavior.
        ///
        /// @param source owned forced temporary file
        /// @param target exact recovery document
        /// @throws IOException after the configured filesystem effect
        @Override
        void atomicReplace(Path source, Path target) throws IOException {
            atomicAttempts++;
            if (mode == AmbiguousMoveMode.ATOMIC_MOVE_THEN_THROW) {
                super.atomicReplace(source, target);
                throw new IOException("injected exception after atomic move");
            }
            if (mode == AmbiguousMoveMode.POST_MOVE_IDENTITY_FAILURE) {
                super.atomicReplace(source, target);
                moveCompleted = true;
                return;
            }
            if (mode.usesFallback()) {
                if (atomicAttempts > 1) {
                    if (mode == AmbiguousMoveMode.FALLBACK_REPAIR_FAILURE) {
                        throw new IOException("injected atomic repair failure");
                    }
                    super.atomicReplace(source, target);
                    return;
                }
                throw new AtomicMoveNotSupportedException(source.toString(), target.toString(), "injected");
            }
            super.atomicReplace(source, target);
        }

        /// Executes a real fallback move or leaves the configured ambiguous target before throwing.
        ///
        /// @param source owned forced temporary file
        /// @param target exact recovery document
        /// @throws IOException after the configured filesystem effect
        @Override
        void replace(Path source, Path target) throws IOException {
            if (mode == AmbiguousMoveMode.FALLBACK_MOVE_THEN_THROW) {
                super.replace(source, target);
                throw new IOException("injected exception after fallback move");
            }
            switch (mode) {
                case FALLBACK_PARTIAL_TARGET, FALLBACK_REPAIR_FAILURE -> Files.writeString(
                        target,
                        "partial",
                        StandardCharsets.UTF_8
                );
                case FALLBACK_MISSING_TARGET -> Files.deleteIfExists(target);
                case FALLBACK_CORRUPT_TARGET -> Files.writeString(
                        target,
                        "{corrupt-target",
                        StandardCharsets.UTF_8
                );
                default -> throw new AssertionError("Unexpected fallback mode");
            }
            Files.deleteIfExists(source);
            throw new IOException("injected ambiguous fallback failure");
        }

        /// Counts and optionally rejects the durability barrier for a reconciled published target.
        ///
        /// @param target verified recovery target
        /// @throws IOException when failure is injected or forcing fails
        @Override
        void forcePublishedTarget(Path target) throws IOException {
            publishedTargetForces++;
            if (failPublishedTargetForce) {
                throw new IOException("injected reconciled target force failure");
            }
            super.forcePublishedTarget(target);
        }

        /// Fails launcher-home inspection only after one real move completed.
        ///
        /// @param path inspected path
        /// @return no-follow attributes before publication
        /// @throws IOException after publication or when normal inspection fails
        @Override
        BasicFileAttributes readAttributes(Path path) throws IOException {
            if (moveCompleted && path.equals(launcherHome)) {
                throw new IOException("injected post-move identity failure");
            }
            return super.readAttributes(path);
        }
    }

    /// Ambiguous filesystem outcomes exercised by publication reconciliation tests.
    @NotNullByDefault
    private enum AmbiguousMoveMode {
        /// Atomic movement completes before its wrapper throws.
        ATOMIC_MOVE_THEN_THROW,

        /// Fallback movement completes before its wrapper throws.
        FALLBACK_MOVE_THEN_THROW,

        /// Fallback failure leaves a partial target.
        FALLBACK_PARTIAL_TARGET,

        /// Fallback failure leaves no target.
        FALLBACK_MISSING_TARGET,

        /// Fallback failure leaves a corrupt target.
        FALLBACK_CORRUPT_TARGET,

        /// Fallback failure leaves a partial target and atomic repair fails.
        FALLBACK_REPAIR_FAILURE,

        /// A successful atomic move is followed by launcher-home identity failure.
        POST_MOVE_IDENTITY_FAILURE;

        /// Returns whether the initial atomic attempt should select fallback replacement.
        ///
        /// @return whether fallback is selected
        private boolean usesFallback() {
            return this == FALLBACK_MOVE_THEN_THROW
                    || this == FALLBACK_PARTIAL_TARGET
                    || this == FALLBACK_MISSING_TARGET
                    || this == FALLBACK_CORRUPT_TARGET
                    || this == FALLBACK_REPAIR_FAILURE;
        }
    }

    /// Optional active identities used to enumerate recovery semantic combinations.
    ///
    /// @param providerId active Runtime Provider ID, or `null`
    /// @param pluginId active ordinary plugin ID, or `null`
    @NotNullByDefault
    private record ActiveIdentities(
            @org.jetbrains.annotations.Nullable String providerId,
            @org.jetbrains.annotations.Nullable String pluginId
    ) {
        /// Creates one active-identity matrix coordinate.
        private ActiveIdentities {
        }
    }
}
