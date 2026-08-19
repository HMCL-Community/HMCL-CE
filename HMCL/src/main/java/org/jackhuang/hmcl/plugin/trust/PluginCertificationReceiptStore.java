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
package org.jackhuang.hmcl.plugin.trust;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/// Persists the one exact signed certification receipt currently associated with each installed plugin ID.
@NotNullByDefault
public final class PluginCertificationReceiptStore {
    /// Current receipt document schema.
    private static final int SCHEMA_VERSION = 1;

    /// Maximum accepted or emitted receipt document size.
    private static final int MAX_DOCUMENT_BYTES = 4 * 1024 * 1024;

    /// Stable launcher-local file name also captured by the package transaction journal.
    public static final String FILE_NAME = "plugin-certification-receipts.json";

    /// JSON codec for the private launcher-local document.
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /// Exact receipt document path.
    private final Path receiptFile;

    /// Creates a store rooted at one launcher-local home.
    ///
    /// @param localHome launcher-local home
    public PluginCertificationReceiptStore(Path localHome) {
        receiptFile = localHome.resolve(FILE_NAME).toAbsolutePath().normalize();
    }

    /// Reads every structurally valid receipt and rejects a malformed document as a whole.
    ///
    /// @return immutable receipts indexed by plugin ID
    /// @throws IOException if the document is unsafe, oversized, malformed, or internally inconsistent
    public synchronized @Unmodifiable Map<String, PluginCertificationReceipt> readAll() throws IOException {
        if (!Files.exists(receiptFile, LinkOption.NOFOLLOW_LINKS)) {
            return Map.of();
        }
        if (!Files.isRegularFile(receiptFile, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(receiptFile)) {
            throw new IOException("Plugin certification receipt document is not a regular file");
        }
        if (Files.size(receiptFile) > MAX_DOCUMENT_BYTES) {
            throw new IOException("Plugin certification receipt document is too large");
        }
        final @Nullable ReceiptDocument document;
        try {
            document = GSON.fromJson(
                    Files.readString(receiptFile, StandardCharsets.UTF_8),
                    ReceiptDocument.class
            );
        } catch (RuntimeException exception) {
            throw new IOException("Plugin certification receipt document is malformed", exception);
        }
        if (document == null || document.schemaVersion != SCHEMA_VERSION || document.receipts == null) {
            throw new IOException("Plugin certification receipt document has an unsupported schema");
        }
        Map<String, PluginCertificationReceipt> receipts = new LinkedHashMap<>();
        for (@Nullable PluginCertificationReceipt receipt : document.receipts) {
            if (receipt == null || receipts.putIfAbsent(receipt.pluginId(), receipt) != null) {
                throw new IOException("Plugin certification receipt document contains an invalid duplicate");
            }
        }
        return Map.copyOf(receipts);
    }

    /// Replaces receipts for a complete installation batch while retaining unrelated installed identities.
    ///
    /// Every replaced plugin ID without a new certified receipt loses any old receipt. This makes local, community,
    /// and official replacement semantics explicit and prevents old repository identity from attaching to new bytes.
    ///
    /// @param replacedPluginIds every plugin ID whose NPL is being replaced
    /// @param newReceipts certified replacement receipts indexed by plugin ID
    /// @throws IOException if current state is invalid or replacement cannot be persisted atomically
    public synchronized void replaceInstallations(
            Set<String> replacedPluginIds,
            Map<String, PluginCertificationReceipt> newReceipts
    ) throws IOException {
        if (!replacedPluginIds.containsAll(newReceipts.keySet())) {
            throw new IllegalArgumentException("Certification receipts contain a plugin outside the install batch");
        }
        Map<String, PluginCertificationReceipt> replacement = new LinkedHashMap<>(readAll());
        replacedPluginIds.forEach(replacement::remove);
        for (Map.Entry<String, PluginCertificationReceipt> entry : newReceipts.entrySet()) {
            if (!entry.getKey().equals(entry.getValue().pluginId())) {
                throw new IllegalArgumentException("Certification receipt map key does not match its plugin ID");
            }
            replacement.put(entry.getKey(), entry.getValue());
        }
        write(replacement);
    }

    /// Removes one uninstalled plugin's receipt while retaining every unrelated receipt.
    ///
    /// @param pluginId removed plugin ID
    /// @throws IOException if current state is invalid or replacement cannot be persisted
    public synchronized void removePlugin(String pluginId) throws IOException {
        Map<String, PluginCertificationReceipt> replacement = new LinkedHashMap<>(readAll());
        if (replacement.remove(pluginId) != null) {
            write(replacement);
        }
    }

    /// Writes one complete deterministic document through same-directory atomic replacement when available.
    ///
    /// @param receipts complete receipt state
    /// @throws IOException if serialization or replacement fails
    private void write(Map<String, PluginCertificationReceipt> receipts) throws IOException {
        if (receipts.isEmpty()) {
            Files.deleteIfExists(receiptFile);
            return;
        }
        ReceiptDocument document = new ReceiptDocument();
        document.schemaVersion = SCHEMA_VERSION;
        document.receipts = receipts.values().stream()
                .sorted(Comparator.comparing(PluginCertificationReceipt::pluginId))
                .toList();
        String json = GSON.toJson(document);
        if (json.getBytes(StandardCharsets.UTF_8).length > MAX_DOCUMENT_BYTES) {
            throw new IOException("Plugin certification receipt document is too large");
        }
        Files.createDirectories(Objects.requireNonNull(receiptFile.getParent()));
        Path temporaryFile = receiptFile.resolveSibling(receiptFile.getFileName() + ".tmp");
        try {
            Files.writeString(temporaryFile, json, StandardCharsets.UTF_8);
            try {
                Files.move(
                        temporaryFile,
                        receiptFile,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING
                );
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporaryFile, receiptFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporaryFile);
        }
    }

    /// Serialized root of the certification receipt document.
    @NotNullByDefault
    private static final class ReceiptDocument {
        /// Serialized schema version.
        private int schemaVersion;

        /// Serialized receipts, or `null` in malformed documents.
        private @Nullable List<@Nullable PluginCertificationReceipt> receipts;

        /// Creates an empty document for Gson.
        private ReceiptDocument() {
        }
    }
}
