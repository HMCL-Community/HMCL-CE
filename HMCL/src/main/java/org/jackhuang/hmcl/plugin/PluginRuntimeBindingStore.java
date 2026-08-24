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
import org.jackhuang.hmcl.plugin.runtime.RuntimeProviderBinding;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/// Atomically persists dependent-scoped runtime Provider bindings selected by confirmed Store plans.
@NotNullByDefault
final class PluginRuntimeBindingStore {
    /// Stable launcher-local document name also captured by the transaction journal.
    static final String FILE_NAME = "plugin-runtime-bindings.json";

    /// Current private document schema.
    private static final int SCHEMA_VERSION = 1;

    /// Maximum accepted binding document size.
    private static final int MAX_DOCUMENT_BYTES = 1024 * 1024;

    /// JSON codec for the private binding document.
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /// Atomic binding document path.
    private final Path bindingFile;

    /// Shared package and document mutation lock.
    private final PluginMutationLock mutationLock;

    /// Creates one launcher-local binding store.
    ///
    /// @param localHome launcher-local home
    /// @param mutationLock shared mutation lock
    PluginRuntimeBindingStore(Path localHome, PluginMutationLock mutationLock) {
        bindingFile = localHome.resolve(FILE_NAME).toAbsolutePath().normalize();
        this.mutationLock = mutationLock;
    }

    /// Merges confirmed bindings and atomically replaces the complete binding document.
    ///
    /// @param additions confirmed bindings indexed by dependent plugin ID
    /// @throws IOException if existing state is invalid or replacement fails
    void mergeStrict(@Unmodifiable Map<String, RuntimeProviderBinding> additions) throws IOException {
        mutationLock.run(() -> {
            Map<String, RuntimeProviderBinding> merged = new LinkedHashMap<>(readLocked());
            merged.putAll(additions);
            writeLocked(Map.copyOf(merged));
        });
    }

    /// Atomically replaces the complete binding document with an exact prospective snapshot.
    ///
    /// @param bindings complete bindings indexed by dependent plugin ID
    /// @throws IOException if validation or replacement fails
    void replaceStrict(@Unmodifiable Map<String, RuntimeProviderBinding> bindings) throws IOException {
        mutationLock.run(() -> {
            for (Map.Entry<String, RuntimeProviderBinding> entry : bindings.entrySet()) {
                if (!entry.getKey().equals(entry.getValue().dependentPluginId())) {
                    throw new IOException("Runtime binding key does not match dependent plugin ID: "
                            + entry.getKey());
                }
            }
            writeLocked(Map.copyOf(bindings));
        });
    }

    /// Removes selected dependent-owned bindings while preserving every unrelated edge.
    ///
    /// @param dependentPluginIds dependent plugin IDs whose bindings must be removed
    /// @throws IOException if existing state is invalid or replacement fails
    void removeDependentsStrict(@Unmodifiable Set<String> dependentPluginIds) throws IOException {
        mutationLock.run(() -> {
            Map<String, RuntimeProviderBinding> remaining = new LinkedHashMap<>(readLocked());
            dependentPluginIds.forEach(remaining::remove);
            writeLocked(Map.copyOf(remaining));
        });
    }

    /// Reads a complete immutable binding snapshot under the shared mutation lock.
    ///
    /// @return immutable bindings indexed by dependent plugin ID
    /// @throws IOException if existing state is invalid or unreadable
    @Unmodifiable Map<String, RuntimeProviderBinding> readStrict() throws IOException {
        return mutationLock.call(this::readLocked);
    }

    /// Reads and validates the complete current binding document.
    ///
    /// @return immutable bindings indexed by dependent plugin ID
    /// @throws IOException if the document is unreadable or malformed
    private @Unmodifiable Map<String, RuntimeProviderBinding> readLocked() throws IOException {
        if (!Files.exists(bindingFile)) {
            return Map.of();
        }
        if (!Files.isRegularFile(bindingFile)) {
            throw new IOException("Runtime Provider binding document is not a regular file");
        }
        String json;
        try (InputStream input = Files.newInputStream(bindingFile)) {
            byte @Unmodifiable [] bytes = input.readNBytes(MAX_DOCUMENT_BYTES + 1);
            if (bytes.length > MAX_DOCUMENT_BYTES) {
                throw new IOException("Runtime Provider binding document is too large");
            }
            json = new String(bytes, StandardCharsets.UTF_8);
        }
        try {
            @Nullable Document document = GSON.fromJson(json, Document.class);
            if (document == null || document.schemaVersion != SCHEMA_VERSION || document.bindings == null) {
                throw new IOException("Runtime Provider binding document is invalid");
            }
            Map<String, RuntimeProviderBinding> bindings = new LinkedHashMap<>();
            for (@Nullable BindingData data : document.bindings) {
                if (data == null || data.dependentPluginId == null || data.providerId == null || data.runtime == null) {
                    throw new IOException("Runtime Provider binding document contains an incomplete entry");
                }
                RuntimeProviderBinding binding;
                try {
                    binding = new RuntimeProviderBinding(data.dependentPluginId, data.providerId, data.runtime);
                } catch (IllegalArgumentException exception) {
                    throw new IOException("Runtime Provider binding document contains an invalid entry", exception);
                }
                if (bindings.putIfAbsent(binding.dependentPluginId(), binding) != null) {
                    throw new IOException("Runtime Provider binding document contains duplicate dependents");
                }
            }
            return Map.copyOf(bindings);
        } catch (RuntimeException exception) {
            throw new IOException("Runtime Provider binding document cannot be parsed", exception);
        }
    }

    /// Writes a complete deterministic binding snapshot through atomic replacement.
    ///
    /// @param bindings complete bindings indexed by dependent plugin ID
    /// @throws IOException if serialization or replacement fails
    private void writeLocked(@Unmodifiable Map<String, RuntimeProviderBinding> bindings) throws IOException {
        Document document = new Document();
        document.schemaVersion = SCHEMA_VERSION;
        document.bindings = bindings.values().stream()
                .sorted(java.util.Comparator.comparing(RuntimeProviderBinding::dependentPluginId))
                .map(binding -> new BindingData(
                        binding.dependentPluginId(), binding.providerId(), binding.runtime()))
                .toList();
        Path temporaryFile = bindingFile.resolveSibling(bindingFile.getFileName() + ".tmp");
        try {
            Files.createDirectories(bindingFile.getParent());
            Files.writeString(temporaryFile, GSON.toJson(document), StandardCharsets.UTF_8);
            try {
                Files.move(temporaryFile, bindingFile,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporaryFile, bindingFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporaryFile);
        }
    }

    /// Mutable serialized root used only by Gson.
    @NotNullByDefault
    private static final class Document {
        /// Serialized schema version.
        private int schemaVersion;

        /// Serialized binding entries, or `null` for malformed input.
        private @Nullable List<@Nullable BindingData> bindings = new ArrayList<>();

        /// Creates an empty Gson document.
        private Document() {
        }
    }

    /// Mutable serialized binding used only by Gson.
    @NotNullByDefault
    private static final class BindingData {
        /// Dependent plugin ID, or `null` for malformed input.
        private @Nullable String dependentPluginId;

        /// Provider plugin ID, or `null` for malformed input.
        private @Nullable String providerId;

        /// Runtime capability, or `null` for malformed input.
        private @Nullable String runtime;

        /// Creates an empty Gson binding.
        private BindingData() {
        }

        /// Creates one complete serialized binding.
        ///
        /// @param dependentPluginId dependent plugin ID
        /// @param providerId Provider plugin ID
        /// @param runtime runtime capability
        private BindingData(String dependentPluginId, String providerId, String runtime) {
            this.dependentPluginId = dependentPluginId;
            this.providerId = providerId;
            this.runtime = runtime;
        }
    }
}
