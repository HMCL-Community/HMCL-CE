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

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jackhuang.hmcl.plugin.internal.PluginPackageVersions;
import org.jackhuang.hmcl.plugin.trust.PluginCertificationReceiptStore;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// Verifies crash recovery for prepared and committed multi-package publication journals.
@NotNullByDefault
public final class PluginBatchTransactionJournalTest {
    /// Refuses a present journal directory instead of treating it as an absent transaction.
    ///
    /// @param temporaryDirectory isolated launcher-local home
    /// @throws Exception if test directory setup fails
    @Test
    public void rejectNonRegularTransactionJournal(@TempDir Path temporaryDirectory) throws Exception {
        Path pluginsDirectory = temporaryDirectory.resolve("plugins");
        Files.createDirectories(pluginsDirectory);
        Files.createDirectory(temporaryDirectory.resolve("plugin-install-transaction.json"));

        assertFalse(new PluginBatchTransactionJournal(temporaryDirectory, pluginsDirectory).recover());
    }

    /// Refuses a symbolic-link journal even when its target is a regular JSON file.
    ///
    /// @param temporaryDirectory isolated launcher-local home
    /// @throws Exception if test file setup fails
    @Test
    public void rejectSymbolicLinkTransactionJournal(@TempDir Path temporaryDirectory) throws Exception {
        Path pluginsDirectory = temporaryDirectory.resolve("plugins");
        Files.createDirectories(pluginsDirectory);
        Path linkedJournal = temporaryDirectory.resolve("linked-journal.json");
        Files.writeString(linkedJournal, "{}", StandardCharsets.UTF_8);
        Path transactionFile = temporaryDirectory.resolve("plugin-install-transaction.json");
        try {
            Files.createSymbolicLink(transactionFile, linkedJournal.getFileName());
        } catch (IOException | UnsupportedOperationException exception) {
            assumeTrue(false, "Symbolic links are unavailable in this test environment: " + exception);
        }

        assertFalse(new PluginBatchTransactionJournal(temporaryDirectory, pluginsDirectory).recover());
    }

    /// Rejects malformed UTF-8 instead of replacing invalid bytes before JSON parsing.
    ///
    /// @param temporaryDirectory isolated launcher-local home
    /// @throws Exception if test file setup fails
    @Test
    public void rejectMalformedUtf8TransactionJournal(@TempDir Path temporaryDirectory) throws Exception {
        Path pluginsDirectory = temporaryDirectory.resolve("plugins");
        Files.createDirectories(pluginsDirectory);
        try (OutputStream output = Files.newOutputStream(transactionFile(temporaryDirectory))) {
            output.write(0xc3);
            output.write(0x28);
        }

        assertFalse(new PluginBatchTransactionJournal(temporaryDirectory, pluginsDirectory).recover());
    }

    /// Rejects an empty permission snapshot before any package path is moved.
    ///
    /// @param temporaryDirectory isolated launcher-local home
    /// @throws Exception if transaction setup or JSON mutation fails
    @Test
    public void rejectEmptySnapshotBeforePackageMutation(@TempDir Path temporaryDirectory) throws Exception {
        PreparedFixture fixture = createUnpublishedPreparedFixture(temporaryDirectory);
        JsonObject transaction = readTransaction(temporaryDirectory);
        transaction.add("permissionSnapshot", new JsonObject());
        writeTransaction(temporaryDirectory, transaction);

        assertFalse(new PluginBatchTransactionJournal(temporaryDirectory, fixture.pluginsDirectory()).recover());
        assertEquals("old", Files.readString(fixture.original(), StandardCharsets.UTF_8));
        assertEquals("new", Files.readString(fixture.prepared(), StandardCharsets.UTF_8));
        assertFalse(Files.exists(fixture.backup()));
    }

    /// Rejects a current transaction missing its cleanup snapshot before package mutation.
    ///
    /// @param temporaryDirectory isolated launcher-local home
    /// @throws Exception if transaction setup or JSON mutation fails
    @Test
    public void rejectMissingCleanupSnapshotBeforePackageMutation(@TempDir Path temporaryDirectory) throws Exception {
        PreparedFixture fixture = createUnpublishedPreparedFixture(temporaryDirectory);
        JsonObject transaction = readTransaction(temporaryDirectory);
        transaction.remove("cleanupSnapshot");
        writeTransaction(temporaryDirectory, transaction);

        assertFalse(new PluginBatchTransactionJournal(temporaryDirectory, fixture.pluginsDirectory()).recover());
        assertEquals("old", Files.readString(fixture.original(), StandardCharsets.UTF_8));
        assertEquals("new", Files.readString(fixture.prepared(), StandardCharsets.UTF_8));
        assertFalse(Files.exists(fixture.backup()));
    }

    /// Rejects a current transaction missing its certification receipt snapshot before package mutation.
    ///
    /// @param temporaryDirectory isolated launcher-local home
    /// @throws Exception if transaction setup or JSON mutation fails
    @Test
    public void rejectMissingCertificationReceiptSnapshotBeforePackageMutation(
            @TempDir Path temporaryDirectory
    ) throws Exception {
        PreparedFixture fixture = createUnpublishedPreparedFixture(temporaryDirectory);
        JsonObject transaction = readTransaction(temporaryDirectory);
        transaction.remove("certificationReceiptSnapshot");
        writeTransaction(temporaryDirectory, transaction);

        assertFalse(new PluginBatchTransactionJournal(temporaryDirectory, fixture.pluginsDirectory()).recover());
        assertEquals("old", Files.readString(fixture.original(), StandardCharsets.UTF_8));
        assertEquals("new", Files.readString(fixture.prepared(), StandardCharsets.UTF_8));
        assertFalse(Files.exists(fixture.backup()));
    }

    /// Restores the old centralized receipt document when a prepared package publication rolls back.
    ///
    /// @param temporaryDirectory isolated launcher-local home
    /// @throws Exception if transaction setup or recovery fails
    @Test
    public void rollbackRestoresCertificationReceiptDocument(@TempDir Path temporaryDirectory) throws Exception {
        PreparedFixture fixture = createPreparedFixtureFiles(temporaryDirectory);
        Path receiptFile = temporaryDirectory.resolve(PluginCertificationReceiptStore.FILE_NAME);
        Files.writeString(receiptFile, "old-receipts", StandardCharsets.UTF_8);
        PluginBatchTransactionJournal journal = new PluginBatchTransactionJournal(
                temporaryDirectory,
                fixture.pluginsDirectory()
        );
        journal.begin(
                Map.of(fixture.original(), fixture.backup()),
                List.of(fixture.target()),
                List.of(fixture.prepared())
        );
        Files.writeString(receiptFile, "new-receipts", StandardCharsets.UTF_8);

        assertTrue(journal.recover());

        assertEquals("old-receipts", Files.readString(receiptFile, StandardCharsets.UTF_8));
        assertEquals("old", Files.readString(fixture.original(), StandardCharsets.UTF_8));
        assertFalse(Files.exists(fixture.target()));
    }

    /// Restores the old runtime Provider binding document when a prepared publication rolls back.
    ///
    /// @param temporaryDirectory isolated launcher-local home
    /// @throws Exception if transaction setup or recovery fails
    @Test
    public void rollbackRestoresRuntimeProviderBindingDocument(@TempDir Path temporaryDirectory) throws Exception {
        PreparedFixture fixture = createPreparedFixtureFiles(temporaryDirectory);
        Path bindingFile = temporaryDirectory.resolve("plugin-runtime-bindings.json");
        Files.writeString(bindingFile, "old-bindings", StandardCharsets.UTF_8);
        PluginBatchTransactionJournal journal = new PluginBatchTransactionJournal(
                temporaryDirectory,
                fixture.pluginsDirectory()
        );
        journal.begin(
                Map.of(fixture.original(), fixture.backup()),
                List.of(fixture.target()),
                List.of(fixture.prepared())
        );
        Files.writeString(bindingFile, "new-bindings", StandardCharsets.UTF_8);

        assertTrue(journal.recover());

        assertEquals("old-bindings", Files.readString(bindingFile, StandardCharsets.UTF_8));
        assertEquals("old", Files.readString(fixture.original(), StandardCharsets.UTF_8));
        assertFalse(Files.exists(fixture.target()));
    }

    /// Rejects invalid snapshot Base64 before package mutation begins.
    ///
    /// @param temporaryDirectory isolated launcher-local home
    /// @throws Exception if transaction setup or JSON mutation fails
    @Test
    public void rejectInvalidSnapshotBase64BeforePackageMutation(@TempDir Path temporaryDirectory) throws Exception {
        PreparedFixture fixture = createUnpublishedPreparedFixture(temporaryDirectory);
        JsonObject transaction = readTransaction(temporaryDirectory);
        JsonObject invalidSnapshot = new JsonObject();
        invalidSnapshot.addProperty("existed", true);
        invalidSnapshot.addProperty("contents", "%%%not-base64%%%");
        transaction.add("stateSnapshot", invalidSnapshot);
        writeTransaction(temporaryDirectory, transaction);

        assertFalse(new PluginBatchTransactionJournal(temporaryDirectory, fixture.pluginsDirectory()).recover());
        assertEquals("old", Files.readString(fixture.original(), StandardCharsets.UTF_8));
        assertEquals("new", Files.readString(fixture.prepared(), StandardCharsets.UTF_8));
        assertFalse(Files.exists(fixture.backup()));
    }

    /// Persists a schema 2 upgrade nonce and resumes the same quarantine with a fresh journal instance.
    ///
    /// @param temporaryDirectory isolated launcher-local home
    /// @throws Exception if transaction setup, crash injection, or recovery fails
    @Test
    public void resumeSchemaTwoUpgradeFromPersistedQuarantine(@TempDir Path temporaryDirectory) throws Exception {
        PreparedFixture fixture = createPublishedPreparedFixture(temporaryDirectory, false);
        String targetSha256 = PluginPackageVersions.calculateSha256(fixture.target());
        JsonObject legacyTransaction = readTransaction(temporaryDirectory);
        legacyTransaction.addProperty("schemaVersion", 2);
        legacyTransaction.remove("recoveryNonce");
        legacyTransaction.remove("cleanupAuthorized");
        legacyTransaction.remove("cleanupSnapshot");
        writeTransaction(temporaryDirectory, legacyTransaction);
        List<Path> quarantines = new ArrayList<>();

        PluginBatchTransactionJournal firstRecovery = new PluginBatchTransactionJournal(
                temporaryDirectory,
                fixture.pluginsDirectory(),
                (event, source, target) -> {
                    if (event == PluginRecoveryFileOperations.Event.AFTER_QUARANTINE_ACQUIRE
                            && source.equals(fixture.target())) {
                        quarantines.add(target);
                        throw new SimulatedCrashError();
                    }
                }
        );

        assertThrows(SimulatedCrashError.class, firstRecovery::recover);
        assertEquals(1, quarantines.size());
        Path persistedQuarantine = quarantines.get(0);
        assertTrue(Files.exists(persistedQuarantine));
        JsonObject upgradedTransaction = readTransaction(temporaryDirectory);
        assertEquals(5, upgradedTransaction.get("schemaVersion").getAsInt());
        JsonObject cleanupSnapshot = upgradedTransaction.getAsJsonObject("cleanupSnapshot");
        assertFalse(cleanupSnapshot.get("existed").getAsBoolean());
        assertFalse(cleanupSnapshot.has("contents"));
        JsonObject certificationReceiptSnapshot =
                upgradedTransaction.getAsJsonObject("certificationReceiptSnapshot");
        assertFalse(certificationReceiptSnapshot.get("existed").getAsBoolean());
        assertFalse(certificationReceiptSnapshot.has("contents"));
        String recoveryNonce = upgradedTransaction.get("recoveryNonce").getAsString();
        assertTrue(persistedQuarantine.getFileName().toString().contains(recoveryNonce));

        assertTrue(new PluginBatchTransactionJournal(
                temporaryDirectory,
                fixture.pluginsDirectory()
        ).recover());
        assertEquals("old", Files.readString(fixture.original(), StandardCharsets.UTF_8));
        assertFalse(Files.exists(fixture.target()));
        assertEquals(
                "HMCL plugin artifact disposed\nsha256=" + targetSha256
                        + "\nrole=interrupted plugin batch target\n",
                Files.readString(persistedQuarantine, StandardCharsets.UTF_8)
        );
        assertFalse(Files.exists(transactionFile(temporaryDirectory)));
    }

    /// Upgrades schema 3 and 4 journals by synthesizing only the document snapshots introduced later.
    ///
    /// @param temporaryDirectory isolated root for both legacy recovery fixtures
    /// @throws Exception if transaction setup, mutation, upgrade, or recovery fails
    @Test
    public void recoverSchemaThreeAndFourJournals(@TempDir Path temporaryDirectory) throws Exception {
        for (int schemaVersion : List.of(3, 4)) {
            Path localHome = temporaryDirectory.resolve("schema-" + schemaVersion);
            Files.createDirectories(localHome);
            PreparedFixture fixture = createPublishedPreparedFixture(localHome, false);
            JsonObject legacyTransaction = readTransaction(localHome);
            legacyTransaction.addProperty("schemaVersion", schemaVersion);
            legacyTransaction.remove("runtimeBindingSnapshot");
            if (schemaVersion == 3) {
                legacyTransaction.remove("certificationReceiptSnapshot");
            }
            writeTransaction(localHome, legacyTransaction);

            assertTrue(new PluginBatchTransactionJournal(localHome, fixture.pluginsDirectory()).recover());
            assertEquals("old", Files.readString(fixture.original(), StandardCharsets.UTF_8));
            assertFalse(Files.exists(fixture.target()));
            assertFalse(Files.exists(transactionFile(localHome)));
        }
    }

    /// Does not publish an object that replaced a backup quarantine immediately before restore acquisition.
    ///
    /// @param temporaryDirectory isolated launcher-local home
    /// @throws Exception if transaction setup or deterministic race injection fails
    @Test
    public void doNotElevateReplacementRacedIntoRestoreHandoff(@TempDir Path temporaryDirectory) throws Exception {
        PreparedFixture fixture = createPublishedPreparedFixture(temporaryDirectory, false);
        List<Path> racedQuarantines = new ArrayList<>();
        PluginBatchTransactionJournal recoveringJournal = new PluginBatchTransactionJournal(
                temporaryDirectory,
                fixture.pluginsDirectory(),
                (event, source, target) -> {
                    if (event == PluginRecoveryFileOperations.Event.BEFORE_RESTORE_ACQUIRE) {
                        racedQuarantines.add(source);
                        Files.writeString(source, "external restore replacement", StandardCharsets.UTF_8);
                    }
                }
        );

        assertFalse(recoveringJournal.recover());
        assertFalse(Files.exists(fixture.original()));
        assertEquals("new", Files.readString(fixture.target(), StandardCharsets.UTF_8));
        assertEquals(1, racedQuarantines.size());
        assertEquals(
                "external restore replacement",
                Files.readString(racedQuarantines.get(0), StandardCharsets.UTF_8)
        );
        assertTrue(Files.exists(transactionFile(temporaryDirectory)));
    }

    /// Does not delete or truncate an object replacing final disposal after handle-bound verification.
    ///
    /// @param temporaryDirectory isolated launcher-local home
    /// @throws Exception if transaction setup or deterministic race injection fails
    @Test
    public void preserveReplacementRacedIntoFinalDisposal(@TempDir Path temporaryDirectory) throws Exception {
        PreparedFixture fixture = createPublishedPreparedFixture(temporaryDirectory, true);
        List<Path> disposalPaths = new ArrayList<>();
        PluginBatchTransactionJournal recoveringJournal = new PluginBatchTransactionJournal(
                temporaryDirectory,
                fixture.pluginsDirectory(),
                (event, source, target) -> {
                    if (event == PluginRecoveryFileOperations.Event.AFTER_DISPOSAL_VALIDATION
                            && source.getFileName().toString().contains("-backup-")) {
                        disposalPaths.add(source);
                        Path replacement = source.resolveSibling(source.getFileName() + ".external");
                        Files.writeString(replacement, "external disposal replacement", StandardCharsets.UTF_8);
                        Files.move(replacement, source, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
        );

        assertTrue(recoveringJournal.recover());
        assertEquals("new", Files.readString(fixture.target(), StandardCharsets.UTF_8));
        assertEquals(1, disposalPaths.size());
        assertEquals(
                "external disposal replacement",
                Files.readString(disposalPaths.get(0), StandardCharsets.UTF_8)
        );
        assertFalse(Files.exists(transactionFile(temporaryDirectory)));
    }

    /// Preserves mismatched cleanup bytes introduced before handle acquisition and treats them as external debris.
    ///
    /// @param temporaryDirectory isolated launcher-local home
    /// @throws Exception if transaction setup or deterministic mismatch injection fails
    @Test
    public void preserveForeignCleanupPathBeforeHandleAcquisition(@TempDir Path temporaryDirectory) throws Exception {
        PreparedFixture fixture = createPublishedPreparedFixture(temporaryDirectory, true);
        List<Path> cleanupPaths = new ArrayList<>();
        PluginBatchTransactionJournal recoveringJournal = new PluginBatchTransactionJournal(
                temporaryDirectory,
                fixture.pluginsDirectory(),
                (event, source, target) -> {
                    if (event == PluginRecoveryFileOperations.Event.BEFORE_DISPOSAL_ACQUIRE
                            && source.getFileName().toString().contains("-backup-")) {
                        cleanupPaths.add(source);
                        Files.writeString(source, "external cleanup debris", StandardCharsets.UTF_8);
                    }
                }
        );

        assertTrue(recoveringJournal.recover());
        assertEquals(1, cleanupPaths.size());
        assertEquals(
                "external cleanup debris",
                Files.readString(cleanupPaths.get(0), StandardCharsets.UTF_8)
        );
        assertEquals("new", Files.readString(fixture.target(), StandardCharsets.UTF_8));
        assertFalse(Files.exists(transactionFile(temporaryDirectory)));
    }

    /// Preserves read-only foreign cleanup debris when writable handle acquisition is denied.
    ///
    /// @param temporaryDirectory isolated launcher-local home
    /// @throws Exception if transaction setup, read-only classification, or cleanup fails
    @Test
    public void preserveReadOnlyForeignCleanupDebris(@TempDir Path temporaryDirectory) throws Exception {
        PreparedFixture fixture = createPublishedPreparedFixture(temporaryDirectory, true);
        List<Path> cleanupPaths = new ArrayList<>();
        PluginBatchTransactionJournal recoveringJournal = new PluginBatchTransactionJournal(
                temporaryDirectory,
                fixture.pluginsDirectory(),
                (event, source, target) -> {
                    if (event == PluginRecoveryFileOperations.Event.BEFORE_DISPOSAL_ACQUIRE
                            && source.getFileName().toString().contains("-backup-")) {
                        cleanupPaths.add(source);
                        Files.writeString(source, "read-only external cleanup debris", StandardCharsets.UTF_8);
                        if (!source.toFile().setReadOnly()) {
                            throw new IOException("Could not make cleanup debris read-only: " + source);
                        }
                    }
                }
        );

        try {
            assertTrue(recoveringJournal.recover());
            assertEquals(1, cleanupPaths.size());
            assertEquals(
                    "read-only external cleanup debris",
                    Files.readString(cleanupPaths.get(0), StandardCharsets.UTF_8)
            );
            assertEquals("new", Files.readString(fixture.target(), StandardCharsets.UTF_8));
            assertFalse(Files.exists(transactionFile(temporaryDirectory)));
        } finally {
            for (Path cleanupPath : cleanupPaths) {
                cleanupPath.toFile().setWritable(true);
            }
        }
    }

    /// Recognizes a forced disposed marker in a legacy disposal path after a crash and completes cleanup on retry.
    ///
    /// @param temporaryDirectory isolated launcher-local home
    /// @throws Exception if transaction setup, crash injection, or cleanup retry fails
    @Test
    public void retryPersistedDisposedMarkerWithNewInstance(@TempDir Path temporaryDirectory) throws Exception {
        PreparedFixture fixture = createPublishedPreparedFixture(temporaryDirectory, true);
        String backupSha256 = PluginPackageVersions.calculateSha256(fixture.backup());
        List<Path> disposalPaths = new ArrayList<>();
        PluginBatchTransactionJournal firstRecovery = new PluginBatchTransactionJournal(
                temporaryDirectory,
                fixture.pluginsDirectory(),
                (event, source, target) -> {
                    if (event == PluginRecoveryFileOperations.Event.BEFORE_DISPOSAL_ACQUIRE
                            && source.getFileName().toString().contains("-backup-")) {
                        Files.move(source, target);
                        disposalPaths.add(target);
                        throw new SimulatedCrashError();
                    }
                }
        );

        assertThrows(SimulatedCrashError.class, firstRecovery::recover);
        assertEquals(1, disposalPaths.size());
        assertTrue(Files.exists(disposalPaths.get(0)));
        PluginBatchTransactionJournal markerRecovery = new PluginBatchTransactionJournal(
                temporaryDirectory,
                fixture.pluginsDirectory(),
                (event, source, target) -> {
                    if (event == PluginRecoveryFileOperations.Event.AFTER_DISPOSAL_MARKER
                            && source.equals(disposalPaths.get(0))) {
                        throw new SimulatedCrashError();
                    }
                }
        );

        assertThrows(SimulatedCrashError.class, markerRecovery::recover);
        String disposedMarker = Files.readString(disposalPaths.get(0), StandardCharsets.UTF_8);
        assertEquals(
                "HMCL plugin artifact disposed\nsha256=" + backupSha256
                        + "\nrole=committed plugin backup\n",
                disposedMarker
        );

        assertTrue(new PluginBatchTransactionJournal(
                temporaryDirectory,
                fixture.pluginsDirectory()
        ).recover());
        assertEquals("new", Files.readString(fixture.target(), StandardCharsets.UTF_8));
        assertEquals(disposedMarker, Files.readString(disposalPaths.get(0), StandardCharsets.UTF_8));
        assertFalse(Files.exists(transactionFile(temporaryDirectory)));
    }

    /// Treats target replacement after durable final validation as independent tampering, not failed cleanup.
    ///
    /// @param temporaryDirectory isolated launcher-local home
    /// @throws Exception if transaction setup or deterministic race injection fails
    @Test
    public void completeCleanupWhenTargetChangesAfterValidationBoundary(@TempDir Path temporaryDirectory)
            throws Exception {
        PreparedFixture fixture = createPublishedPreparedFixture(temporaryDirectory, true);
        PluginBatchTransactionJournal recoveringJournal = new PluginBatchTransactionJournal(
                temporaryDirectory,
                fixture.pluginsDirectory(),
                (event, source, target) -> {
                    if (event == PluginRecoveryFileOperations.Event.AFTER_DISPOSAL_VALIDATION
                            && source.getFileName().toString().contains("-backup-")) {
                        Files.writeString(
                                fixture.target(),
                                "external target after validation",
                                StandardCharsets.UTF_8
                        );
                    }
                }
        );

        assertTrue(recoveringJournal.recover());
        assertEquals(
                "external target after validation",
                Files.readString(fixture.target(), StandardCharsets.UTF_8)
        );
        assertFalse(Files.exists(transactionFile(temporaryDirectory)));
    }

    /// Restores old packages and removes new targets when interruption occurs before commit.
    ///
    /// @param temporaryDirectory isolated launcher-local home
    /// @throws Exception if test file setup or journal recovery fails
    @Test
    public void rollbackPreparedTransactionAfterRestart(@TempDir Path temporaryDirectory) throws Exception {
        Path pluginsDirectory = temporaryDirectory.resolve("plugins");
        Files.createDirectories(pluginsDirectory);
        Path original = pluginsDirectory.resolve("legacy.npl");
        Path backup = pluginsDirectory.resolve(".legacy.backup");
        Path prepared = pluginsDirectory.resolve(".plugin.installing");
        Path target = pluginsDirectory.resolve("plugin.npl");
        Path permissionFile = temporaryDirectory.resolve("plugin-permissions.json");
        Path stateFile = temporaryDirectory.resolve("plugin-states.json");
        Files.writeString(original, "old", StandardCharsets.UTF_8);
        Files.writeString(prepared, "new", StandardCharsets.UTF_8);
        Files.writeString(permissionFile, "old-permissions", StandardCharsets.UTF_8);
        Files.writeString(stateFile, "old-state", StandardCharsets.UTF_8);

        PluginBatchTransactionJournal journal = new PluginBatchTransactionJournal(
                temporaryDirectory,
                pluginsDirectory
        );
        PluginBatchTransactionJournal.Transaction transaction = journal.begin(
                Map.of(original, backup),
                List.of(target),
                List.of(prepared)
        );
        Files.writeString(permissionFile, "new-permissions", StandardCharsets.UTF_8);
        Files.writeString(stateFile, "new-state", StandardCharsets.UTF_8);
        journal.publishPrepared(transaction);

        assertTrue(new PluginBatchTransactionJournal(temporaryDirectory, pluginsDirectory).recover());
        assertEquals("old", Files.readString(original, StandardCharsets.UTF_8));
        assertFalse(Files.exists(target));
        assertFalse(Files.exists(backup));
        assertEquals("old-permissions", Files.readString(permissionFile, StandardCharsets.UTF_8));
        assertEquals("old-state", Files.readString(stateFile, StandardCharsets.UTF_8));
        assertFalse(Files.exists(temporaryDirectory.resolve("plugin-install-transaction.json")));
    }

    /// Refuses to delete a target whose bytes no longer belong to the interrupted transaction.
    ///
    /// @param temporaryDirectory isolated launcher-local home
    /// @throws Exception if test file setup or journal recovery fails
    @Test
    public void preserveChangedTargetDuringPreparedRecovery(@TempDir Path temporaryDirectory) throws Exception {
        Path pluginsDirectory = temporaryDirectory.resolve("plugins");
        Files.createDirectories(pluginsDirectory);
        Path original = pluginsDirectory.resolve("legacy.npl");
        Path backup = pluginsDirectory.resolve(".legacy.backup");
        Path prepared = pluginsDirectory.resolve(".plugin.installing");
        Path target = pluginsDirectory.resolve("plugin.npl");
        Files.writeString(original, "old", StandardCharsets.UTF_8);
        Files.writeString(prepared, "new", StandardCharsets.UTF_8);
        PluginBatchTransactionJournal journal = new PluginBatchTransactionJournal(
                temporaryDirectory,
                pluginsDirectory
        );
        PluginBatchTransactionJournal.Transaction transaction = journal.begin(
                Map.of(original, backup),
                List.of(target),
                List.of(prepared)
        );
        journal.publishPrepared(transaction);
        Files.writeString(target, "external replacement", StandardCharsets.UTF_8);

        assertFalse(new PluginBatchTransactionJournal(temporaryDirectory, pluginsDirectory).recover());
        assertEquals("external replacement", Files.readString(target, StandardCharsets.UTF_8));
        assertEquals("old", Files.readString(backup, StandardCharsets.UTF_8));
        assertTrue(Files.exists(temporaryDirectory.resolve("plugin-install-transaction.json")));
    }

    /// Preserves a target replacement introduced after graph prevalidation but before quarantine acquisition.
    ///
    /// @param temporaryDirectory isolated launcher-local home
    /// @throws Exception if transaction setup or deterministic race injection fails
    @Test
    public void preserveTargetReplacedImmediatelyBeforeQuarantine(@TempDir Path temporaryDirectory) throws Exception {
        Path pluginsDirectory = temporaryDirectory.resolve("plugins");
        Files.createDirectories(pluginsDirectory);
        Path original = pluginsDirectory.resolve("legacy.npl");
        Path backup = pluginsDirectory.resolve(".legacy.backup");
        Path prepared = pluginsDirectory.resolve(".plugin.installing");
        Path target = pluginsDirectory.resolve("plugin.npl");
        Files.writeString(original, "old", StandardCharsets.UTF_8);
        Files.writeString(prepared, "new", StandardCharsets.UTF_8);
        PluginBatchTransactionJournal journal = new PluginBatchTransactionJournal(
                temporaryDirectory,
                pluginsDirectory
        );
        PluginBatchTransactionJournal.Transaction transaction = journal.begin(
                Map.of(original, backup),
                List.of(target),
                List.of(prepared)
        );
        journal.publishPrepared(transaction);

        PluginBatchTransactionJournal recoveringJournal = new PluginBatchTransactionJournal(
                temporaryDirectory,
                pluginsDirectory,
                (event, source, quarantine) -> {
                    if (event == PluginRecoveryFileOperations.Event.BEFORE_QUARANTINE_ACQUIRE
                            && source.equals(target)) {
                        Files.writeString(source, "raced replacement", StandardCharsets.UTF_8);
                    }
                }
        );

        assertFalse(recoveringJournal.recover());
        assertEquals("raced replacement", Files.readString(target, StandardCharsets.UTF_8));
        assertEquals("old", Files.readString(backup, StandardCharsets.UTF_8));
        assertTrue(Files.exists(temporaryDirectory.resolve("plugin-install-transaction.json")));
    }

    /// Leaves a newly occupied target untouched when it appears after the old target entered quarantine.
    ///
    /// The transaction-owned new target remains in quarantine, while the verified old backup returns to its backup
    /// source and the journal remains available for a later retry.
    ///
    /// @param temporaryDirectory isolated launcher-local home
    /// @throws Exception if transaction setup or deterministic race injection fails
    @Test
    public void preserveTargetCreatedAfterQuarantineAcquisition(@TempDir Path temporaryDirectory) throws Exception {
        Path pluginsDirectory = temporaryDirectory.resolve("plugins");
        Files.createDirectories(pluginsDirectory);
        Path original = pluginsDirectory.resolve("legacy.npl");
        Path backup = pluginsDirectory.resolve(".legacy.backup");
        Path prepared = pluginsDirectory.resolve(".plugin.installing");
        Path target = pluginsDirectory.resolve("plugin.npl");
        Files.writeString(original, "old", StandardCharsets.UTF_8);
        Files.writeString(prepared, "new", StandardCharsets.UTF_8);
        PluginBatchTransactionJournal journal = new PluginBatchTransactionJournal(
                temporaryDirectory,
                pluginsDirectory
        );
        PluginBatchTransactionJournal.Transaction transaction = journal.begin(
                Map.of(original, backup),
                List.of(target),
                List.of(prepared)
        );
        journal.publishPrepared(transaction);
        List<Path> targetQuarantines = new ArrayList<>();

        PluginBatchTransactionJournal recoveringJournal = new PluginBatchTransactionJournal(
                temporaryDirectory,
                pluginsDirectory,
                (event, source, quarantine) -> {
                    if (event == PluginRecoveryFileOperations.Event.BEFORE_QUARANTINE_ACQUIRE
                            && source.equals(target)) {
                        targetQuarantines.add(quarantine);
                    } else if (event == PluginRecoveryFileOperations.Event.BEFORE_QUARANTINE_ACQUIRE
                            && source.equals(backup)) {
                        Files.writeString(target, "external current target", StandardCharsets.UTF_8);
                    }
                }
        );

        assertFalse(recoveringJournal.recover());
        assertEquals("external current target", Files.readString(target, StandardCharsets.UTF_8));
        assertEquals("old", Files.readString(backup, StandardCharsets.UTF_8));
        assertEquals(1, targetQuarantines.size());
        assertEquals("new", Files.readString(targetQuarantines.get(0), StandardCharsets.UTF_8));
        assertTrue(Files.exists(temporaryDirectory.resolve("plugin-install-transaction.json")));
    }

    /// Refuses a quarantine rename whose destination is occupied after graph prevalidation.
    ///
    /// @param temporaryDirectory isolated launcher-local home
    /// @throws Exception if transaction setup or deterministic race injection fails
    @Test
    public void preserveQuarantineAndSourceWhenRenameLosesRace(@TempDir Path temporaryDirectory) throws Exception {
        Path pluginsDirectory = temporaryDirectory.resolve("plugins");
        Files.createDirectories(pluginsDirectory);
        Path original = pluginsDirectory.resolve("legacy.npl");
        Path backup = pluginsDirectory.resolve(".legacy.backup");
        Path prepared = pluginsDirectory.resolve(".plugin.installing");
        Path target = pluginsDirectory.resolve("plugin.npl");
        Files.writeString(original, "old", StandardCharsets.UTF_8);
        Files.writeString(prepared, "new", StandardCharsets.UTF_8);
        PluginBatchTransactionJournal journal = new PluginBatchTransactionJournal(
                temporaryDirectory,
                pluginsDirectory
        );
        PluginBatchTransactionJournal.Transaction transaction = journal.begin(
                Map.of(original, backup),
                List.of(target),
                List.of(prepared)
        );
        journal.publishPrepared(transaction);
        List<Path> racedQuarantines = new ArrayList<>();

        PluginBatchTransactionJournal recoveringJournal = new PluginBatchTransactionJournal(
                temporaryDirectory,
                pluginsDirectory,
                (event, source, quarantine) -> {
                    if (event == PluginRecoveryFileOperations.Event.BEFORE_QUARANTINE_ACQUIRE
                            && source.equals(target)) {
                        racedQuarantines.add(quarantine);
                        Files.writeString(quarantine, "external quarantine", StandardCharsets.UTF_8);
                    }
                }
        );

        assertFalse(recoveringJournal.recover());
        assertEquals("new", Files.readString(target, StandardCharsets.UTF_8));
        assertEquals("old", Files.readString(backup, StandardCharsets.UTF_8));
        assertEquals(1, racedQuarantines.size());
        assertEquals("external quarantine", Files.readString(racedQuarantines.get(0), StandardCharsets.UTF_8));
        assertTrue(Files.exists(temporaryDirectory.resolve("plugin-install-transaction.json")));
    }

    /// Rejects a non-regular target captured by quarantine and restores it without replacement.
    ///
    /// @param temporaryDirectory isolated launcher-local home
    /// @throws Exception if transaction setup or deterministic race injection fails
    @Test
    public void restoreNonRegularTargetCapturedDuringQuarantine(@TempDir Path temporaryDirectory) throws Exception {
        Path pluginsDirectory = temporaryDirectory.resolve("plugins");
        Files.createDirectories(pluginsDirectory);
        Path original = pluginsDirectory.resolve("legacy.npl");
        Path backup = pluginsDirectory.resolve(".legacy.backup");
        Path prepared = pluginsDirectory.resolve(".plugin.installing");
        Path target = pluginsDirectory.resolve("plugin.npl");
        Files.writeString(original, "old", StandardCharsets.UTF_8);
        Files.writeString(prepared, "new", StandardCharsets.UTF_8);
        PluginBatchTransactionJournal journal = new PluginBatchTransactionJournal(
                temporaryDirectory,
                pluginsDirectory
        );
        PluginBatchTransactionJournal.Transaction transaction = journal.begin(
                Map.of(original, backup),
                List.of(target),
                List.of(prepared)
        );
        journal.publishPrepared(transaction);

        PluginBatchTransactionJournal recoveringJournal = new PluginBatchTransactionJournal(
                temporaryDirectory,
                pluginsDirectory,
                (event, source, quarantine) -> {
                    if (event == PluginRecoveryFileOperations.Event.BEFORE_QUARANTINE_ACQUIRE
                            && source.equals(target)) {
                        Files.delete(source);
                        Files.createDirectory(source);
                    }
                }
        );

        assertFalse(recoveringJournal.recover());
        assertTrue(Files.isDirectory(target));
        assertEquals("old", Files.readString(backup, StandardCharsets.UTF_8));
        assertTrue(Files.exists(temporaryDirectory.resolve("plugin-install-transaction.json")));
    }

    /// Retains a committed backup when the published target changes during cleanup quarantine acquisition.
    ///
    /// @param temporaryDirectory isolated launcher-local home
    /// @throws Exception if transaction setup or deterministic race injection fails
    @Test
    public void keepCommittedBackupWhenTargetChangesDuringCleanup(@TempDir Path temporaryDirectory) throws Exception {
        Path pluginsDirectory = temporaryDirectory.resolve("plugins");
        Files.createDirectories(pluginsDirectory);
        Path original = pluginsDirectory.resolve("plugin.npl");
        Path backup = pluginsDirectory.resolve(".plugin.backup");
        Path prepared = pluginsDirectory.resolve(".plugin.installing");
        Path target = original;
        Files.writeString(original, "old", StandardCharsets.UTF_8);
        Files.writeString(prepared, "new", StandardCharsets.UTF_8);
        PluginBatchTransactionJournal journal = new PluginBatchTransactionJournal(
                temporaryDirectory,
                pluginsDirectory
        );
        PluginBatchTransactionJournal.Transaction transaction = journal.begin(
                Map.of(original, backup),
                List.of(target),
                List.of(prepared)
        );
        journal.publishPrepared(transaction);
        journal.markCommitted(transaction);

        PluginBatchTransactionJournal recoveringJournal = new PluginBatchTransactionJournal(
                temporaryDirectory,
                pluginsDirectory,
                (event, source, quarantine) -> {
                    if (event == PluginRecoveryFileOperations.Event.BEFORE_QUARANTINE_ACQUIRE
                            && source.equals(backup)) {
                        Files.writeString(target, "committed target replacement", StandardCharsets.UTF_8);
                    }
                }
        );

        assertFalse(recoveringJournal.recover());
        assertEquals("committed target replacement", Files.readString(target, StandardCharsets.UTF_8));
        assertEquals("old", Files.readString(backup, StandardCharsets.UTF_8));
        assertTrue(Files.exists(temporaryDirectory.resolve("plugin-install-transaction.json")));
    }

    /// Keeps an unresolved journal when the old package backup disappeared before rollback.
    ///
    /// @param temporaryDirectory isolated launcher-local home
    /// @throws Exception if test file setup or journal recovery fails
    @Test
    public void keepJournalWhenPreparedBackupIsMissing(@TempDir Path temporaryDirectory) throws Exception {
        Path pluginsDirectory = temporaryDirectory.resolve("plugins");
        Files.createDirectories(pluginsDirectory);
        Path original = pluginsDirectory.resolve("legacy.npl");
        Path backup = pluginsDirectory.resolve(".legacy.backup");
        Path prepared = pluginsDirectory.resolve(".plugin.installing");
        Path target = pluginsDirectory.resolve("plugin.npl");
        Files.writeString(original, "old", StandardCharsets.UTF_8);
        Files.writeString(prepared, "new", StandardCharsets.UTF_8);
        PluginBatchTransactionJournal journal = new PluginBatchTransactionJournal(
                temporaryDirectory,
                pluginsDirectory
        );
        PluginBatchTransactionJournal.Transaction transaction = journal.begin(
                Map.of(original, backup),
                List.of(target),
                List.of(prepared)
        );
        journal.publishPrepared(transaction);
        Files.delete(backup);

        assertFalse(new PluginBatchTransactionJournal(temporaryDirectory, pluginsDirectory).recover());
        assertEquals("new", Files.readString(target, StandardCharsets.UTF_8));
        assertTrue(Files.exists(temporaryDirectory.resolve("plugin-install-transaction.json")));
    }

    /// Retains new targets and removes old backups when interruption occurs after commit.
    ///
    /// @param temporaryDirectory isolated launcher-local home
    /// @throws Exception if test file setup or journal recovery fails
    @Test
    public void finishCommittedTransactionAfterRestart(@TempDir Path temporaryDirectory) throws Exception {
        Path pluginsDirectory = temporaryDirectory.resolve("plugins");
        Files.createDirectories(pluginsDirectory);
        Path original = pluginsDirectory.resolve("legacy.npl");
        Path backup = pluginsDirectory.resolve(".legacy.backup");
        Path prepared = pluginsDirectory.resolve(".plugin.installing");
        Path target = pluginsDirectory.resolve("plugin.npl");
        Path permissionFile = temporaryDirectory.resolve("plugin-permissions.json");
        Path stateFile = temporaryDirectory.resolve("plugin-states.json");
        Files.writeString(original, "old", StandardCharsets.UTF_8);
        Files.writeString(prepared, "new", StandardCharsets.UTF_8);
        Files.writeString(permissionFile, "old-permissions", StandardCharsets.UTF_8);
        Files.writeString(stateFile, "old-state", StandardCharsets.UTF_8);

        PluginBatchTransactionJournal journal = new PluginBatchTransactionJournal(
                temporaryDirectory,
                pluginsDirectory
        );
        PluginBatchTransactionJournal.Transaction transaction = journal.begin(
                Map.of(original, backup),
                List.of(target),
                List.of(prepared)
        );
        Files.writeString(permissionFile, "new-permissions", StandardCharsets.UTF_8);
        Files.writeString(stateFile, "new-state", StandardCharsets.UTF_8);
        journal.publishPrepared(transaction);
        journal.markCommitted(transaction);

        assertTrue(new PluginBatchTransactionJournal(temporaryDirectory, pluginsDirectory).recover());
        assertEquals("new", Files.readString(target, StandardCharsets.UTF_8));
        assertFalse(Files.exists(original));
        assertFalse(Files.exists(backup));
        assertEquals("new-permissions", Files.readString(permissionFile, StandardCharsets.UTF_8));
        assertEquals("new-state", Files.readString(stateFile, StandardCharsets.UTF_8));
        assertFalse(Files.exists(temporaryDirectory.resolve("plugin-install-transaction.json")));
    }

    /// Restores a removed package and old documents when interruption occurs before removal commit.
    ///
    /// @param temporaryDirectory isolated launcher-local home
    /// @throws Exception if test file setup or journal recovery fails
    @Test
    public void rollbackPreparedRemovalAfterRestart(@TempDir Path temporaryDirectory) throws Exception {
        Path pluginsDirectory = temporaryDirectory.resolve("plugins");
        Files.createDirectories(pluginsDirectory);
        Path original = pluginsDirectory.resolve("plugin.npl");
        Path backup = pluginsDirectory.resolve(".remove.backup");
        Path permissionFile = temporaryDirectory.resolve("plugin-permissions.json");
        Path stateFile = temporaryDirectory.resolve("plugin-states.json");
        Path cleanupFile = temporaryDirectory.resolve("plugin-cleanup-pending.json");
        Files.writeString(original, "installed", StandardCharsets.UTF_8);
        Files.writeString(permissionFile, "old-permissions", StandardCharsets.UTF_8);
        Files.writeString(stateFile, "old-state", StandardCharsets.UTF_8);
        Files.writeString(cleanupFile, "old-cleanup", StandardCharsets.UTF_8);

        PluginBatchTransactionJournal journal = new PluginBatchTransactionJournal(
                temporaryDirectory,
                pluginsDirectory
        );
        PluginBatchTransactionJournal.Transaction transaction = journal.begin(
                Map.of(original, backup),
                List.of(),
                List.of()
        );
        Files.writeString(permissionFile, "removed-permissions", StandardCharsets.UTF_8);
        Files.writeString(stateFile, "removed-state", StandardCharsets.UTF_8);
        Files.writeString(cleanupFile, "new-cleanup", StandardCharsets.UTF_8);
        journal.publishPrepared(transaction);

        assertTrue(new PluginBatchTransactionJournal(temporaryDirectory, pluginsDirectory).recover());
        assertEquals("installed", Files.readString(original, StandardCharsets.UTF_8));
        assertFalse(Files.exists(backup));
        assertEquals("old-permissions", Files.readString(permissionFile, StandardCharsets.UTF_8));
        assertEquals("old-state", Files.readString(stateFile, StandardCharsets.UTF_8));
        assertEquals("old-cleanup", Files.readString(cleanupFile, StandardCharsets.UTF_8));
    }

    /// Retains a committed cleanup tombstone after private storage deletion fails and clears it after a later retry.
    ///
    /// @param temporaryDirectory isolated launcher-local home
    /// @throws Exception if package removal, tombstone persistence, or retry cleanup fails
    @Test
    public void retryCommittedStorageCleanupFromDurableTombstone(@TempDir Path temporaryDirectory) throws Exception {
        Path pluginsDirectory = temporaryDirectory.resolve("plugins");
        Files.createDirectories(pluginsDirectory);
        Path packageFile = pluginsDirectory.resolve("plugin.npl");
        String pluginId = "example.cleanup";
        writePluginPackage(packageFile, pluginId, "1.0.0", "old artifact");
        String packageSha256 = PluginPackageVersions.calculateSha256(packageFile);
        Path storageDirectory = temporaryDirectory.resolve("plugin-storage").resolve(pluginId);
        Files.createDirectories(storageDirectory.getParent());
        Files.writeString(storageDirectory, "not-a-directory", StandardCharsets.UTF_8);

        PluginPackageMutationService mutationService = new PluginPackageMutationService(
                temporaryDirectory,
                pluginsDirectory,
                new PluginPackageRepository(pluginsDirectory)
        );
        mutationService.publishRemoval(
                List.of(packageFile),
                pluginId,
                () -> {
                },
                () -> {
                }
        );

        Path cleanupFile = temporaryDirectory.resolve("plugin-cleanup-pending.json");
        assertFalse(Files.exists(packageFile));
        assertTrue(Files.exists(cleanupFile));
        assertTrue(Files.readString(cleanupFile, StandardCharsets.UTF_8).contains(pluginId));
        assertTrue(Files.readString(cleanupFile, StandardCharsets.UTF_8).contains("1.0.0"));
        assertTrue(Files.readString(cleanupFile, StandardCharsets.UTF_8).contains(packageSha256));

        Files.delete(storageDirectory);
        Files.createDirectories(storageDirectory);
        Files.writeString(storageDirectory.resolve("data.txt"), "plugin data", StandardCharsets.UTF_8);

        assertTrue(mutationService.recover());
        assertFalse(Files.exists(storageDirectory));
        assertFalse(Files.exists(cleanupFile));
    }

    /// Preserves storage created for a replacement artifact when an old committed cleanup is retried.
    ///
    /// @param temporaryDirectory isolated launcher-local home
    /// @throws Exception if package generation, removal, replacement, or cleanup recovery fails
    @Test
    public void staleCleanupDoesNotDeleteReplacementArtifactStorage(@TempDir Path temporaryDirectory) throws Exception {
        Path pluginsDirectory = temporaryDirectory.resolve("plugins");
        Files.createDirectories(pluginsDirectory);
        String pluginId = "example.cleanup.replacement";
        Path packageFile = pluginsDirectory.resolve(pluginId + ".npl");
        writePluginPackage(packageFile, pluginId, "1.0.0", "old artifact");

        Path storageDirectory = temporaryDirectory.resolve("plugin-storage").resolve(pluginId);
        Files.createDirectories(storageDirectory.getParent());
        Files.writeString(storageDirectory, "not-a-directory", StandardCharsets.UTF_8);
        PluginPackageMutationService mutationService = new PluginPackageMutationService(
                temporaryDirectory,
                pluginsDirectory,
                new PluginPackageRepository(pluginsDirectory)
        );
        mutationService.publishRemoval(
                List.of(packageFile),
                pluginId,
                () -> {
                },
                () -> {
                }
        );

        writePluginPackage(packageFile, pluginId, "2.0.0", "replacement artifact");
        Files.delete(storageDirectory);
        Files.createDirectories(storageDirectory);
        Path replacementData = storageDirectory.resolve("replacement-data.txt");
        Files.writeString(replacementData, "replacement storage", StandardCharsets.UTF_8);

        assertTrue(mutationService.recover());
        assertTrue(Files.exists(packageFile));
        assertEquals("replacement storage", Files.readString(replacementData, StandardCharsets.UTF_8));
        assertFalse(Files.exists(temporaryDirectory.resolve("plugin-cleanup-pending.json")));
    }

    /// Preserves newly created storage when the user reinstalls byte-identical package content.
    ///
    /// @param temporaryDirectory isolated launcher-local home
    /// @throws Exception if package generation, removal, reinstall, or cleanup recovery fails
    @Test
    public void staleCleanupDoesNotDeleteIdenticalReinstalledArtifactStorage(
            @TempDir Path temporaryDirectory
    ) throws Exception {
        Path pluginsDirectory = temporaryDirectory.resolve("plugins");
        Files.createDirectories(pluginsDirectory);
        String pluginId = "example.cleanup.identical";
        Path packageFile = pluginsDirectory.resolve(pluginId + ".npl");
        writePluginPackage(packageFile, pluginId, "1.0.0", "identical artifact");
        byte @Unmodifiable [] packageBytes = Files.readAllBytes(packageFile);

        Path storageDirectory = temporaryDirectory.resolve("plugin-storage").resolve(pluginId);
        Files.createDirectories(storageDirectory.getParent());
        Files.writeString(storageDirectory, "not-a-directory", StandardCharsets.UTF_8);
        PluginPackageMutationService mutationService = new PluginPackageMutationService(
                temporaryDirectory,
                pluginsDirectory,
                new PluginPackageRepository(pluginsDirectory)
        );
        mutationService.publishRemoval(
                List.of(packageFile),
                pluginId,
                () -> {
                },
                () -> {
                }
        );

        Files.write(packageFile, packageBytes);
        Files.delete(storageDirectory);
        Files.createDirectories(storageDirectory);
        Path reinstalledData = storageDirectory.resolve("reinstalled-data.txt");
        Files.writeString(reinstalledData, "reinstalled storage", StandardCharsets.UTF_8);

        assertTrue(mutationService.recover());
        assertTrue(Files.exists(packageFile));
        assertEquals("reinstalled storage", Files.readString(reinstalledData, StandardCharsets.UTF_8));
        assertFalse(Files.exists(temporaryDirectory.resolve("plugin-cleanup-pending.json")));
    }

    /// Discards legacy ID-only cleanup records without applying an unverifiable destructive operation.
    ///
    /// @param temporaryDirectory isolated launcher-local home
    /// @throws Exception if legacy tombstone migration or recovery fails
    @Test
    public void legacyIdOnlyCleanupDoesNotDeleteStorage(@TempDir Path temporaryDirectory) throws Exception {
        Path pluginsDirectory = temporaryDirectory.resolve("plugins");
        Files.createDirectories(pluginsDirectory);
        String pluginId = "example.cleanup.legacy";
        Path storageDirectory = temporaryDirectory.resolve("plugin-storage").resolve(pluginId);
        Files.createDirectories(storageDirectory);
        Path storageData = storageDirectory.resolve("legacy-data.txt");
        Files.writeString(storageData, "preserve legacy storage", StandardCharsets.UTF_8);
        Path cleanupFile = temporaryDirectory.resolve("plugin-cleanup-pending.json");
        Files.writeString(cleanupFile, """
                {
                  "schemaVersion": 1,
                  "pluginIds": ["%s"]
                }
                """.formatted(pluginId), StandardCharsets.UTF_8);

        PluginPackageMutationService mutationService = new PluginPackageMutationService(
                temporaryDirectory,
                pluginsDirectory,
                new PluginPackageRepository(pluginsDirectory)
        );

        assertTrue(mutationService.recover());
        assertEquals("preserve legacy storage", Files.readString(storageData, StandardCharsets.UTF_8));
        assertFalse(Files.exists(cleanupFile));
    }

    /// Retains package removal and new documents after a committed removal is interrupted before cleanup.
    ///
    /// @param temporaryDirectory isolated launcher-local home
    /// @throws Exception if test file setup or journal recovery fails
    @Test
    public void finishCommittedRemovalAfterRestart(@TempDir Path temporaryDirectory) throws Exception {
        Path pluginsDirectory = temporaryDirectory.resolve("plugins");
        Files.createDirectories(pluginsDirectory);
        Path original = pluginsDirectory.resolve("plugin.npl");
        Path backup = pluginsDirectory.resolve(".remove.backup");
        Path permissionFile = temporaryDirectory.resolve("plugin-permissions.json");
        Path stateFile = temporaryDirectory.resolve("plugin-states.json");
        Files.writeString(original, "installed", StandardCharsets.UTF_8);
        Files.writeString(permissionFile, "old-permissions", StandardCharsets.UTF_8);
        Files.writeString(stateFile, "old-state", StandardCharsets.UTF_8);

        PluginBatchTransactionJournal journal = new PluginBatchTransactionJournal(
                temporaryDirectory,
                pluginsDirectory
        );
        PluginBatchTransactionJournal.Transaction transaction = journal.begin(
                Map.of(original, backup),
                List.of(),
                List.of()
        );
        Files.writeString(permissionFile, "removed-permissions", StandardCharsets.UTF_8);
        Files.writeString(stateFile, "removed-state", StandardCharsets.UTF_8);
        journal.publishPrepared(transaction);
        journal.markCommitted(transaction);

        assertTrue(new PluginBatchTransactionJournal(temporaryDirectory, pluginsDirectory).recover());
        assertFalse(Files.exists(original));
        assertFalse(Files.exists(backup));
        assertEquals("removed-permissions", Files.readString(permissionFile, StandardCharsets.UTF_8));
        assertEquals("removed-state", Files.readString(stateFile, StandardCharsets.UTF_8));
    }

    /// Restores permission and state documents for an interrupted pending-uninstall marker transaction.
    ///
    /// @param temporaryDirectory isolated launcher-local home
    /// @throws Exception if test file setup or journal recovery fails
    @Test
    public void rollbackPreparedDocumentOnlyRemovalAfterRestart(@TempDir Path temporaryDirectory) throws Exception {
        Path pluginsDirectory = temporaryDirectory.resolve("plugins");
        Files.createDirectories(pluginsDirectory);
        Path permissionFile = temporaryDirectory.resolve("plugin-permissions.json");
        Path stateFile = temporaryDirectory.resolve("plugin-states.json");
        Files.writeString(permissionFile, "old-permissions", StandardCharsets.UTF_8);
        Files.writeString(stateFile, "old-state", StandardCharsets.UTF_8);

        PluginBatchTransactionJournal journal = new PluginBatchTransactionJournal(
                temporaryDirectory,
                pluginsDirectory
        );
        journal.begin(Map.of(), List.of(), List.of());
        Files.writeString(permissionFile, "removed-permissions", StandardCharsets.UTF_8);
        Files.writeString(stateFile, "pending-uninstall", StandardCharsets.UTF_8);

        assertTrue(new PluginBatchTransactionJournal(temporaryDirectory, pluginsDirectory).recover());
        assertEquals("old-permissions", Files.readString(permissionFile, StandardCharsets.UTF_8));
        assertEquals("old-state", Files.readString(stateFile, StandardCharsets.UTF_8));
    }

    /// Refuses to overwrite an unresolved journal with a new mutation transaction.
    ///
    /// @param temporaryDirectory isolated launcher-local home
    /// @throws Exception if journal setup fails
    @Test
    public void rejectBeginWhileJournalExists(@TempDir Path temporaryDirectory) throws Exception {
        Path pluginsDirectory = temporaryDirectory.resolve("plugins");
        Files.createDirectories(pluginsDirectory);
        PluginBatchTransactionJournal journal = new PluginBatchTransactionJournal(
                temporaryDirectory,
                pluginsDirectory
        );
        journal.begin(Map.of(), List.of(), List.of());

        assertThrows(IOException.class, () -> journal.begin(Map.of(), List.of(), List.of()));
        assertTrue(journal.recover());
    }

    /// Creates an unpublished prepared transaction with one old package and one staging package.
    ///
    /// @param temporaryDirectory isolated launcher-local home
    /// @return fixture containing transaction package paths
    /// @throws IOException if fixture files or the journal cannot be written
    private static PreparedFixture createUnpublishedPreparedFixture(Path temporaryDirectory) throws IOException {
        PreparedFixture fixture = createPreparedFixtureFiles(temporaryDirectory);
        PluginBatchTransactionJournal journal = new PluginBatchTransactionJournal(
                temporaryDirectory,
                fixture.pluginsDirectory()
        );
        journal.begin(
                Map.of(fixture.original(), fixture.backup()),
                List.of(fixture.target()),
                List.of(fixture.prepared())
        );
        return fixture;
    }

    /// Creates and publishes one prepared transaction, optionally persisting its committed phase.
    ///
    /// @param temporaryDirectory isolated launcher-local home
    /// @param committed whether to persist the committed phase after publication
    /// @return fixture containing transaction package paths
    /// @throws IOException if fixture files, publication, or journal persistence fails
    private static PreparedFixture createPublishedPreparedFixture(
            Path temporaryDirectory,
            boolean committed
    ) throws IOException {
        PreparedFixture fixture = createPreparedFixtureFiles(temporaryDirectory);
        PluginBatchTransactionJournal journal = new PluginBatchTransactionJournal(
                temporaryDirectory,
                fixture.pluginsDirectory()
        );
        PluginBatchTransactionJournal.Transaction transaction = journal.begin(
                Map.of(fixture.original(), fixture.backup()),
                List.of(fixture.target()),
                List.of(fixture.prepared())
        );
        journal.publishPrepared(transaction);
        if (committed) {
            journal.markCommitted(transaction);
        }
        return fixture;
    }

    /// Creates deterministic package files used by transaction recovery fixtures.
    ///
    /// @param temporaryDirectory isolated launcher-local home
    /// @return fixture containing created paths
    /// @throws IOException if package files cannot be created
    private static PreparedFixture createPreparedFixtureFiles(Path temporaryDirectory) throws IOException {
        Path pluginsDirectory = temporaryDirectory.resolve("plugins");
        Files.createDirectories(pluginsDirectory);
        Path original = pluginsDirectory.resolve("legacy.npl");
        Path backup = pluginsDirectory.resolve(".legacy.backup");
        Path prepared = pluginsDirectory.resolve(".plugin.installing");
        Path target = pluginsDirectory.resolve("plugin.npl");
        Files.writeString(original, "old", StandardCharsets.UTF_8);
        Files.writeString(prepared, "new", StandardCharsets.UTF_8);
        return new PreparedFixture(pluginsDirectory, original, backup, prepared, target);
    }

    /// Reads the current transaction journal as a mutable JSON object.
    ///
    /// @param temporaryDirectory isolated launcher-local home
    /// @return parsed transaction object
    /// @throws IOException if the journal cannot be read
    private static JsonObject readTransaction(Path temporaryDirectory) throws IOException {
        return JsonParser.parseString(Files.readString(
                transactionFile(temporaryDirectory),
                StandardCharsets.UTF_8
        )).getAsJsonObject();
    }

    /// Writes one intentionally modified transaction journal for malformed-input tests.
    ///
    /// @param temporaryDirectory isolated launcher-local home
    /// @param transaction modified transaction object
    /// @throws IOException if the journal cannot be written
    private static void writeTransaction(Path temporaryDirectory, JsonObject transaction) throws IOException {
        Files.writeString(transactionFile(temporaryDirectory), transaction.toString(), StandardCharsets.UTF_8);
    }

    /// Returns the fixed transaction journal path for one test home.
    ///
    /// @param temporaryDirectory isolated launcher-local home
    /// @return transaction journal path
    private static Path transactionFile(Path temporaryDirectory) {
        return temporaryDirectory.resolve("plugin-install-transaction.json");
    }

    /// Package paths participating in one deterministic prepared transaction fixture.
    @NotNullByDefault
    private static final class PreparedFixture {
        /// Installed plugin directory.
        private final Path pluginsDirectory;

        /// Original old package path.
        private final Path original;

        /// Hidden old-package backup path.
        private final Path backup;

        /// Hidden prepared package path.
        private final Path prepared;

        /// Stable new-package target path.
        private final Path target;

        /// Creates one complete fixture.
        ///
        /// @param pluginsDirectory installed plugin directory
        /// @param original original old package path
        /// @param backup hidden old-package backup path
        /// @param prepared hidden prepared package path
        /// @param target stable new-package target path
        private PreparedFixture(
                Path pluginsDirectory,
                Path original,
                Path backup,
                Path prepared,
                Path target
        ) {
            this.pluginsDirectory = pluginsDirectory;
            this.original = original;
            this.backup = backup;
            this.prepared = prepared;
            this.target = target;
        }

        /// Returns the installed plugin directory.
        ///
        /// @return plugin directory
        private Path pluginsDirectory() {
            return pluginsDirectory;
        }

        /// Returns the original old package path.
        ///
        /// @return original path
        private Path original() {
            return original;
        }

        /// Returns the hidden old-package backup path.
        ///
        /// @return backup path
        private Path backup() {
            return backup;
        }

        /// Returns the hidden prepared package path.
        ///
        /// @return prepared path
        private Path prepared() {
            return prepared;
        }

        /// Returns the stable new-package target path.
        ///
        /// @return target path
        private Path target() {
            return target;
        }
    }

    /// Error used to model process termination without running journal recovery cleanup.
    @NotNullByDefault
    private static final class SimulatedCrashError extends Error {
        /// Stable serialization identity.
        private static final long serialVersionUID = 1L;
    }

    /// Writes one deterministic schema-v3 package for storage-cleanup identity tests.
    ///
    /// @param target target NPL path
    /// @param pluginId package plugin ID
    /// @param version package version
    /// @param marker package content that distinguishes otherwise identical manifests
    /// @throws IOException if the package cannot be written
    private static void writePluginPackage(
            Path target,
            String pluginId,
            String version,
            String marker
    ) throws IOException {
        String manifest = """
                {
                  "schemaVersion": 3,
                  "id": "%s",
                  "name": "Storage Cleanup Test",
                  "version": "%s",
                  "type": "java",
                  "entrypoint": "example.StorageCleanupPlugin",
                  "permissions": [],
                  "dependencies": []
                }
                """.formatted(pluginId, version);
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(target))) {
            writeZipEntry(output, "plugin.json", manifest.getBytes(StandardCharsets.UTF_8));
            writeZipEntry(output, "marker.txt", marker.getBytes(StandardCharsets.UTF_8));
        }
    }

    /// Writes one deterministic archive entry.
    ///
    /// @param output target archive
    /// @param name entry name
    /// @param contents entry bytes
    /// @throws IOException if the entry cannot be written
    private static void writeZipEntry(
            ZipOutputStream output,
            String name,
            byte @Unmodifiable [] contents
    ) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(0);
        output.putNextEntry(entry);
        output.write(contents);
        output.closeEntry();
    }
}
