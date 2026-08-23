/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2020  huangyuhui <huanghongxun2008@126.com> and contributors
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
package org.jackhuang.hmcl.plugin.store;

import com.google.gson.annotations.SerializedName;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/// Describes a remote plugin-store registry and its indexed plugin repositories.
@NotNullByDefault
public final class PluginStoreRegistry {
    /// Current remote registry schema version.
    public static final int CURRENT_SCHEMA_VERSION = 1;

    /// Plugin ID pattern shared with package manifests.
    private static final Pattern ID_PATTERN = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9._-]{1,127}");

    /// Lowercase SHA-256 digest used to pin official manifest bytes.
    private static final Pattern SHA256_PATTERN = Pattern.compile("[0-9a-f]{64}");

    /// Registry schema version.
    @SerializedName("schemaVersion")
    private int schemaVersion = 1;

    /// Human-readable registry name.
    @SerializedName("name")
    private @Nullable String name;

    /// Optional registry description.
    @SerializedName("description")
    private @Nullable String description;

    /// Optional registry homepage URL.
    @SerializedName("homepageUrl")
    private @Nullable String homepageUrl;

    /// Indexed plugin entries.
    @SerializedName("plugins")
    private @Nullable List<@Nullable PluginStoreEntry> plugins;

    /// Creates an empty registry for Gson deserialization.
    public PluginStoreRegistry() {
    }

    /// Creates one validated synthetic registry for GitHub Topic discovery.
    ///
    /// @param name synthetic registry name
    /// @param plugins discovered plugin entries
    /// @return synthetic registry
    static PluginStoreRegistry discovered(String name, List<PluginStoreEntry> plugins) {
        PluginStoreRegistry registry = new PluginStoreRegistry();
        registry.schemaVersion = CURRENT_SCHEMA_VERSION;
        registry.name = Objects.requireNonNull(name, "name");
        registry.description = "GitHub Topic discovery";
        registry.homepageUrl = "https://github.com/topics/hmclce";
        registry.plugins = List.copyOf(plugins);
        return registry;
    }

    /// Returns the schema version.
    ///
    /// @return schema version
    public int getSchemaVersion() {
        return schemaVersion;
    }

    /// Returns the registry name.
    ///
    /// @return registry name
    public String getName() {
        return Objects.requireNonNullElse(name, "");
    }

    /// Returns the optional registry description.
    ///
    /// @return description
    public String getDescription() {
        return Objects.requireNonNullElse(description, "");
    }

    /// Returns the optional homepage URL.
    ///
    /// @return homepage URL
    public String getHomepageUrl() {
        return Objects.requireNonNullElse(homepageUrl, "");
    }

    /// Returns an immutable snapshot of indexed plugin entries.
    ///
    /// @return plugin entries
    public @Unmodifiable List<PluginStoreEntry> getPlugins() {
        @Nullable List<@Nullable PluginStoreEntry> values = plugins;
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream().map(Objects::requireNonNull).toList();
    }

    /// Finds a registry entry by its exact plugin ID.
    ///
    /// @param pluginId plugin ID
    /// @return matching entry or `null`
    public @Nullable PluginStoreEntry findPlugin(String pluginId) {
        return getPlugins().stream()
                .filter(entry -> entry.getId().equals(pluginId))
                .findFirst()
                .orElse(null);
    }

    /// Validates registry structure, entry IDs, and duplicates.
    ///
    /// @throws IOException if the registry is malformed or unsupported
    public void validate() throws IOException {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IOException("Unsupported plugin registry schemaVersion: " + schemaVersion);
        }
        if (name == null || name.isBlank()) {
            throw new IOException("Plugin registry has no name");
        }
        if (plugins == null) {
            throw new IOException("Plugin registry has no plugins array");
        }

        Set<String> ids = new HashSet<>();
        for (@Nullable PluginStoreEntry entry : plugins) {
            if (entry == null) {
                throw new IOException("Plugin registry contains a null entry");
            }
            entry.validate();
            if (!ids.add(entry.getId())) {
                throw new IOException("Plugin registry contains duplicate ID: " + entry.getId());
            }
        }
    }

    /// Metadata for one plugin repository indexed by the remote registry.
    @NotNullByDefault
    public static final class PluginStoreEntry {
        /// Globally unique plugin ID.
        @SerializedName("id")
        private @Nullable String id;

        /// Human-readable plugin name.
        @SerializedName("name")
        private @Nullable String name;

        /// Optional author string.
        @SerializedName("author")
        private @Nullable String author;

        /// Optional short description.
        @SerializedName("description")
        private @Nullable String description;

        /// URL of the plugin repository's version manifest.
        @SerializedName("manifestUrl")
        private @Nullable String manifestUrl;

        /// Optional manifest byte digest; mandatory for signed official registries.
        @SerializedName("manifestSha256")
        private @Nullable String manifestSha256;

        /// Optional source repository URL.
        @SerializedName("repository")
        private @Nullable String repository;

        /// Optional project homepage URL.
        @SerializedName("homepage")
        private @Nullable String homepage;

        /// Optional normalized category.
        @SerializedName("category")
        private @Nullable String category;

        /// Optional search tags.
        @SerializedName("tags")
        private @Nullable List<@Nullable String> tags;

        /// Optional advertised capabilities such as `mixin` or `javafx`.
        @SerializedName("capabilities")
        private @Nullable List<@Nullable String> capabilities;

        /// Creates an empty entry for Gson deserialization.
        public PluginStoreEntry() {
        }

        /// Creates one synthetic entry whose repository identity came from GitHub API discovery.
        ///
        /// @throws IOException if the discovered entry metadata is invalid
        static PluginStoreEntry discovered(
                String id,
                String name,
                String author,
                String description,
                String manifestUrl,
                String repository
        ) throws IOException {
            PluginStoreEntry entry = new PluginStoreEntry();
            entry.id = id;
            entry.name = name;
            entry.author = author;
            entry.description = description;
            entry.manifestUrl = manifestUrl;
            entry.repository = repository;
            entry.tags = List.of("hmclce");
            entry.capabilities = List.of();
            entry.validate();
            return entry;
        }

        /// Returns the validated plugin ID.
        ///
        /// @return plugin ID
        public String getId() {
            return Objects.requireNonNull(id, "Store entry has no id");
        }

        /// Returns the plugin display name, falling back to its ID.
        ///
        /// @return plugin name
        public String getName() {
            return name == null || name.isBlank() ? getId() : name;
        }

        /// Returns the optional author.
        ///
        /// @return author string
        public String getAuthor() {
            return Objects.requireNonNullElse(author, "");
        }

        /// Returns the optional description.
        ///
        /// @return description
        public String getDescription() {
            return Objects.requireNonNullElse(description, "");
        }

        /// Returns the validated remote manifest URL.
        ///
        /// @return manifest URL
        public String getManifestUrl() {
            return Objects.requireNonNull(manifestUrl, "Store entry has no manifestUrl");
        }

        /// Returns the optional lowercase SHA-256 pin for the exact remote manifest bytes.
        ///
        /// @return manifest digest or an empty string for community registries
        public String getManifestSha256() {
            return Objects.requireNonNullElse(manifestSha256, "");
        }

        /// Returns the optional repository URL.
        ///
        /// @return repository URL
        public String getRepository() {
            return Objects.requireNonNullElse(repository, "");
        }

        /// Returns the optional homepage URL.
        ///
        /// @return homepage URL
        public String getHomepage() {
            return Objects.requireNonNullElse(homepage, "");
        }

        /// Returns the optional category.
        ///
        /// @return category
        public String getCategory() {
            return Objects.requireNonNullElse(category, "");
        }

        /// Returns immutable non-null tags.
        ///
        /// @return tag list
        public @Unmodifiable List<String> getTags() {
            return immutableStrings(tags);
        }

        /// Returns immutable advertised capabilities.
        ///
        /// @return capability list
        public @Unmodifiable List<String> getCapabilities() {
            return immutableStrings(capabilities);
        }

        /// Returns whether the entry advertises a capability.
        ///
        /// @param capability normalized capability name
        /// @return whether the capability is advertised
        public boolean hasCapability(String capability) {
            return getCapabilities().stream().anyMatch(capability::equalsIgnoreCase);
        }

        /// Validates fields required to resolve this entry safely.
        ///
        /// @throws IOException if required metadata is missing
        private void validate() throws IOException {
            if (id == null || !ID_PATTERN.matcher(id).matches()) {
                throw new IOException("Invalid plugin store entry ID: " + id);
            }
            if (manifestUrl == null || manifestUrl.isBlank()) {
                throw new IOException("Store entry " + id + " has no manifestUrl");
            }
            if (manifestSha256 != null && !SHA256_PATTERN.matcher(manifestSha256).matches()) {
                throw new IOException("Store entry " + id + " has an invalid manifestSha256");
            }
            validateStringList(tags, "tags");
            validateStringList(capabilities, "capabilities");
        }

        /// Validates that an optional string list has no null or blank values.
        ///
        /// @param values list to validate
        /// @param fieldName field name used in diagnostics
        /// @throws IOException if a value is null or blank
        private void validateStringList(@Nullable List<@Nullable String> values, String fieldName) throws IOException {
            if (values == null) {
                return;
            }
            for (@Nullable String value : values) {
                if (value == null || value.isBlank()) {
                    throw new IOException("Store entry " + id + " has an invalid " + fieldName + " value");
                }
            }
        }

        /// Copies a nullable Gson string list to an immutable non-null list.
        ///
        /// @param values source values
        /// @return immutable strings
        private static @Unmodifiable List<String> immutableStrings(@Nullable List<@Nullable String> values) {
            if (values == null || values.isEmpty()) {
                return List.of();
            }
            return values.stream().map(Objects::requireNonNull).toList();
        }
    }
}
