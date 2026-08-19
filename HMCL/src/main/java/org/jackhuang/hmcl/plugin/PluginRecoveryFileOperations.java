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

import org.jackhuang.hmcl.plugin.internal.PluginPackageVersions;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/// Performs ownership-safe file transitions for plugin transaction recovery.
@NotNullByDefault
final class PluginRecoveryFileOperations {
    /// Stable prefix identifying a handle-bound disposed-artifact marker.
    private static final String DISPOSED_MARKER_PREFIX = "HMCL plugin artifact disposed\nsha256=";

    /// Production hook that leaves recovery files unchanged at every transition.
    static final Hook NO_OP_HOOK = (event, source, target) -> {
    };

    /// Hook invoked around ownership transitions.
    private final Hook hook;

    /// Creates recovery file operations using one deterministic test hook.
    ///
    /// @param hook transition hook
    PluginRecoveryFileOperations(Hook hook) {
        this.hook = hook;
    }

    /// Moves one present source into its same-directory quarantine and verifies the moved object again.
    ///
    /// If secondary verification fails, the unknown object is moved back without replacement when possible. It is
    /// never deleted, and an occupied source path is left untouched.
    ///
    /// @param source transaction-owned source path
    /// @param quarantine transaction-specific quarantine path
    /// @param expectedSha256 expected package digest, or `null` for an inconsistent journal
    /// @param description diagnostic role
    /// @throws IOException if acquisition races, moved bytes differ, or the quarantine cannot be restored safely
    void quarantineIfPresent(
            Path source,
            Path quarantine,
            @Nullable String expectedSha256,
            String description
    ) throws IOException {
        if (pathExists(quarantine)) {
            if (pathExists(source)) {
                throw new IOException("Plugin transaction has both " + description + " and its quarantine: "
                        + source);
            }
            requireDigest(quarantine, expectedSha256, description + " quarantine");
            return;
        }
        if (!pathExists(source)) {
            return;
        }

        hook.onRecoveryEvent(Event.BEFORE_QUARANTINE_ACQUIRE, source, quarantine);
        move(source, quarantine, false);
        try {
            requireDigest(quarantine, expectedSha256, description + " quarantine");
            hook.onRecoveryEvent(Event.AFTER_QUARANTINE_ACQUIRE, source, quarantine);
        } catch (IOException verificationFailure) {
            restoreMovedArtifactWithoutReplacement(quarantine, source, verificationFailure);
            throw verificationFailure;
        }
    }

    /// Restores one isolated old package through a separately verified same-directory handoff.
    ///
    /// @param quarantine transaction-specific quarantine path
    /// @param restoreHandoff transaction-specific restore acquisition path
    /// @param destination stable original package path
    /// @param expectedSha256 expected package digest, or `null` for an inconsistent journal
    /// @param description diagnostic role
    /// @throws IOException if acquisition races, bytes change, or the destination becomes occupied
    void restoreQuarantinedArtifact(
            Path quarantine,
            Path restoreHandoff,
            Path destination,
            @Nullable String expectedSha256,
            String description
    ) throws IOException {
        boolean quarantineExists = pathExists(quarantine);
        boolean handoffExists = pathExists(restoreHandoff);
        if (quarantineExists && handoffExists) {
            throw new IOException("Plugin transaction has both " + description + " quarantine and restore handoff: "
                    + quarantine);
        }
        if (!quarantineExists && !handoffExists) {
            requireDigest(destination, expectedSha256, "previously restored " + description);
            return;
        }
        if (quarantineExists) {
            requireDigest(quarantine, expectedSha256, description + " quarantine");
            hook.onRecoveryEvent(Event.BEFORE_RESTORE_ACQUIRE, quarantine, restoreHandoff);
            move(quarantine, restoreHandoff, false);
            try {
                requireDigest(restoreHandoff, expectedSha256, description + " restore handoff");
            } catch (IOException verificationFailure) {
                restoreMovedArtifactWithoutReplacement(restoreHandoff, quarantine, verificationFailure);
                throw verificationFailure;
            }
        }

        hook.onRecoveryEvent(Event.BEFORE_RESTORE_PUBLISH, restoreHandoff, destination);
        requireDigest(restoreHandoff, expectedSha256, description + " restore handoff");
        if (pathExists(destination)) {
            throw new IOException("Plugin transaction restore destination became occupied: " + destination);
        }
        move(restoreHandoff, destination, false);
        requireDigest(destination, expectedSha256, "restored " + description);
    }

    /// Replaces transaction-owned cleanup contents with a small durable disposed marker.
    ///
    /// Quarantine and legacy disposal paths are processed independently. A matching package is verified, truncated,
    /// marked, and forced through one open no-follow handle, so a concurrent path replacement remains untouched. A
    /// missing path, an existing matching marker, or foreign bytes are accepted as completed external debris.
    ///
    /// @param quarantine transaction-specific quarantine path
    /// @param disposal transaction-specific final disposal path
    /// @param expectedSha256 expected package digest, or `null` for an inconsistent journal
    /// @param description diagnostic role
    /// @throws IOException if a transaction-owned file cannot be read, marked, or forced
    void disposeVerifiedQuarantine(
            Path quarantine,
            Path disposal,
            @Nullable String expectedSha256,
            String description
    ) throws IOException {
        if (expectedSha256 == null) {
            throw new IOException("Plugin transaction " + description + " has no recorded artifact digest");
        }
        if (pathExists(quarantine)) {
            hook.onRecoveryEvent(Event.BEFORE_DISPOSAL_ACQUIRE, quarantine, disposal);
        }
        disposeOwnedPath(quarantine, expectedSha256, description);
        disposeOwnedPath(disposal, expectedSha256, description);
    }

    /// Tombstones one matching cleanup path without acting on its directory entry after verification.
    ///
    /// @param path cleanup path that may contain an owned package, marker, or foreign debris
    /// @param expectedSha256 expected package digest
    /// @param description stable artifact role written into the marker
    /// @throws IOException if an owned package cannot be read, marked, or forced
    private void disposeOwnedPath(Path path, String expectedSha256, String description) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        byte @Unmodifiable [] marker = disposedMarker(expectedSha256, description);
        FileChannel acquiredChannel;
        try {
            acquiredChannel = FileChannel.open(
                    path,
                    StandardOpenOption.READ,
                    StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS
            );
        } catch (NoSuchFileException ignored) {
            return;
        } catch (IOException acquisitionFailure) {
            if (isReadOnlyForeignOrDisposed(path, expectedSha256, marker, acquisitionFailure)) {
                return;
            }
            throw acquisitionFailure;
        }
        try (FileChannel channel = acquiredChannel) {
            if (matchesMarker(channel, marker)) {
                return;
            }
            if (!expectedSha256.equals(calculateSha256(channel))) {
                return;
            }
            hook.onRecoveryEvent(Event.AFTER_DISPOSAL_VALIDATION, path, path);
            channel.truncate(0);
            channel.position(0);
            writeFully(channel, ByteBuffer.wrap(marker));
            channel.force(true);
            hook.onRecoveryEvent(Event.AFTER_DISPOSAL_MARKER, path, path);
        }
    }

    /// Classifies a cleanup path that could not be acquired for writing through a separate no-follow read handle.
    ///
    /// A disposed marker or digest mismatch is external debris and remains untouched. A matching transaction artifact
    /// must still fail closed because cleanup could not neutralize its contents.
    ///
    /// @param path cleanup path that failed writable acquisition
    /// @param expectedSha256 expected package digest
    /// @param marker exact disposed marker
    /// @param acquisitionFailure writable acquisition failure receiving read diagnostics
    /// @return whether the path is safely classified as disposed or foreign
    /// @throws IOException if the path remains regular but cannot be classified through a read handle
    private static boolean isReadOnlyForeignOrDisposed(
            Path path,
            String expectedSha256,
            byte @Unmodifiable [] marker,
            IOException acquisitionFailure
    ) throws IOException {
        try (FileChannel channel = FileChannel.open(
                path,
                StandardOpenOption.READ,
                LinkOption.NOFOLLOW_LINKS
        )) {
            return matchesMarker(channel, marker) || !expectedSha256.equals(calculateSha256(channel));
        } catch (NoSuchFileException ignored) {
            return true;
        } catch (IOException classificationFailure) {
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                return true;
            }
            acquisitionFailure.addSuppressed(classificationFailure);
            throw acquisitionFailure;
        }
    }

    /// Builds the exact marker bound to one expected digest and artifact role.
    ///
    /// @param expectedSha256 expected package digest
    /// @param description stable artifact role
    /// @return immutable UTF-8 marker bytes
    private static byte @Unmodifiable [] disposedMarker(String expectedSha256, String description) {
        return (DISPOSED_MARKER_PREFIX + expectedSha256 + "\nrole=" + description + "\n")
                .getBytes(StandardCharsets.UTF_8);
    }

    /// Returns whether one open file contains exactly the expected disposed marker.
    ///
    /// @param channel open cleanup file
    /// @param marker expected marker bytes
    /// @return whether the channel contains the exact marker
    /// @throws IOException if the channel cannot be read
    private static boolean matchesMarker(FileChannel channel, byte @Unmodifiable [] marker) throws IOException {
        if (channel.size() != marker.length) {
            return false;
        }
        channel.position(0);
        ByteBuffer contents = ByteBuffer.allocate(marker.length);
        while (contents.hasRemaining() && channel.read(contents) != -1) {
            // Read the complete small marker from the already acquired handle.
        }
        contents.flip();
        for (byte expected : marker) {
            if (!contents.hasRemaining() || contents.get() != expected) {
                return false;
            }
        }
        return !contents.hasRemaining();
    }

    /// Calculates lower-case SHA-256 from one already acquired file handle.
    ///
    /// @param channel open cleanup file
    /// @return lower-case SHA-256
    /// @throws IOException if reading fails or SHA-256 is unavailable
    private static String calculateSha256(FileChannel channel) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IOException("SHA-256 is unavailable", exception);
        }
        channel.position(0);
        ByteBuffer buffer = ByteBuffer.allocate(8192);
        while (channel.read(buffer) != -1) {
            buffer.flip();
            digest.update(buffer);
            buffer.clear();
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /// Writes every remaining byte to one open file handle.
    ///
    /// @param channel destination file
    /// @param buffer bytes to write
    /// @throws IOException if the complete buffer cannot be written
    private static void writeFully(FileChannel channel, ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }
    }

    /// Restores one verified recovery location when its previous path is unoccupied.
    ///
    /// @param recoveryPath transaction-owned recovery path
    /// @param previousPath previous transaction path
    /// @param expectedSha256 expected package digest, or `null` for an inconsistent journal
    /// @param description diagnostic role
    /// @param recoveryFailure failure receiving any restoration error as suppressed context
    static void restoreVerifiedIfUnoccupied(
            Path recoveryPath,
            Path previousPath,
            @Nullable String expectedSha256,
            String description,
            IOException recoveryFailure
    ) {
        if (!pathExists(recoveryPath) || pathExists(previousPath)) {
            return;
        }
        try {
            requireDigest(recoveryPath, expectedSha256, description);
            move(recoveryPath, previousPath, false);
            requireDigest(previousPath, expectedSha256, "restored " + description);
        } catch (IOException restoreFailure) {
            recoveryFailure.addSuppressed(restoreFailure);
        }
    }

    /// Requires one regular file to match a transaction-bound SHA-256 digest.
    ///
    /// @param file file whose bytes must match
    /// @param expectedSha256 expected lower-case SHA-256, or `null` for an invalid journal lookup
    /// @param description diagnostic role
    /// @throws IOException if the file is absent, irregular, or has different bytes
    static void requireDigest(Path file, @Nullable String expectedSha256, String description) throws IOException {
        if (expectedSha256 == null
                || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                || !expectedSha256.equals(PluginPackageVersions.calculateSha256(file))) {
            throw new IOException("Plugin transaction " + description + " does not match its recorded artifact: "
                    + file);
        }
    }

    /// Returns whether one existing regular file matches the expected transaction digest.
    ///
    /// @param file candidate file
    /// @param expectedSha256 expected lower-case SHA-256
    /// @return whether the file exists as a regular file with exactly the expected bytes
    static boolean matchesDigest(Path file, String expectedSha256) {
        try {
            return Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                    && expectedSha256.equals(PluginPackageVersions.calculateSha256(file));
        } catch (IOException exception) {
            return false;
        }
    }

    /// Returns whether a path exists without following a final symbolic link.
    ///
    /// @param path candidate path
    /// @return whether a directory entry exists
    static boolean pathExists(Path path) {
        return Files.exists(path, LinkOption.NOFOLLOW_LINKS);
    }

    /// Moves a recovery file, preserving a strict no-replace contract when requested.
    ///
    /// @param source source path
    /// @param target target path
    /// @param replaceExisting whether an existing target may be replaced
    /// @throws IOException if the move fails
    static void move(Path source, Path target, boolean replaceExisting) throws IOException {
        if (!replaceExisting) {
            // ATOMIC_MOVE has implementation-specific behavior when the target exists. The default same-directory
            // move preserves the required no-replace contract, so a raced-in object is never overwritten.
            Files.move(source, target);
            return;
        }
        try {
            Files.move(
                    source,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
            );
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /// Moves a just-acquired object back when secondary verification fails and the old path remains free.
    ///
    /// @param acquired acquired handoff path
    /// @param previous previous transaction path
    /// @param verificationFailure failure receiving restoration errors as suppressed context
    private static void restoreMovedArtifactWithoutReplacement(
            Path acquired,
            Path previous,
            IOException verificationFailure
    ) {
        try {
            if (!pathExists(previous) && pathExists(acquired)) {
                move(acquired, previous, false);
            }
        } catch (IOException restoreFailure) {
            verificationFailure.addSuppressed(restoreFailure);
        }
    }

    /// Recovery ownership transition exposed to deterministic race tests.
    @NotNullByDefault
    enum Event {
        /// Immediately before a transaction source is renamed into its persistent quarantine.
        BEFORE_QUARANTINE_ACQUIRE,

        /// Immediately after a transaction source was verified inside its persistent quarantine.
        AFTER_QUARANTINE_ACQUIRE,

        /// Immediately before a rollback quarantine is reacquired into its restore handoff path.
        BEFORE_RESTORE_ACQUIRE,

        /// Immediately before a verified restore handoff is published to the original path.
        BEFORE_RESTORE_PUBLISH,

        /// Immediately before a cleanup quarantine is opened for handle-bound disposal.
        BEFORE_DISPOSAL_ACQUIRE,

        /// Immediately after handle-bound digest verification and before the verified object is tombstoned.
        AFTER_DISPOSAL_VALIDATION,

        /// Immediately after the disposed marker has been forced through the verified object handle.
        AFTER_DISPOSAL_MARKER
    }

    /// Test interception point invoked at recovery ownership transitions.
    @FunctionalInterface
    @NotNullByDefault
    interface Hook {
        /// Observes or perturbs one recovery ownership transition for deterministic race verification.
        ///
        /// @param event transition about to occur or just completed
        /// @param source transition source path
        /// @param target transition target path
        /// @throws IOException if the hook intentionally aborts recovery
        void onRecoveryEvent(Event event, Path source, Path target) throws IOException;
    }
}
