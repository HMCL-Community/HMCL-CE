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

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.annotations.SerializedName;
import org.jackhuang.hmcl.plugin.PluginDependency;
import org.jackhuang.hmcl.plugin.PluginKind;
import org.jackhuang.hmcl.plugin.PluginManifest;
import org.jackhuang.hmcl.plugin.PluginPermission;
import org.jackhuang.hmcl.plugin.PluginVersion;
import org.jackhuang.hmcl.plugin.PluginVersionConstraint;
import org.jackhuang.hmcl.plugin.runtime.PluginAbi;
import org.jackhuang.hmcl.plugin.runtime.PluginCompatibilityRequirements;
import org.jackhuang.hmcl.plugin.runtime.PluginExecutionMode;
import org.jackhuang.hmcl.plugin.runtime.PluginPlatformTarget;
import org.jackhuang.hmcl.plugin.runtime.PluginRuntimeTypes;
import org.jackhuang.hmcl.plugin.runtime.RuntimeFeature;
import org.jackhuang.hmcl.plugin.runtime.RuntimeProviderDeclaration;
import org.jackhuang.hmcl.plugin.runtime.RuntimeRequirement;
import org.jackhuang.hmcl.plugin.trust.PluginTrustResult;
import org.jackhuang.hmcl.util.gson.JsonUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/// Describes all downloadable versions published by one plugin repository.
@NotNullByDefault
public final class PluginStoreManifest {
    /// Current plugin repository manifest schema version.
    public static final int CURRENT_SCHEMA_VERSION = 2;

    /// Required SHA-256 representation for downloadable packages.
    private static final Pattern SHA256_PATTERN = Pattern.compile("[0-9a-fA-F]{64}");

    /// Manifest schema version.
    @SerializedName("schemaVersion")
    private int schemaVersion = 1;

    /// Plugin ID bound to this repository manifest.
    @SerializedName("id")
    private @Nullable String id;

    /// GitHub repository identity used by repository and artifact attestations.
    @SerializedName("repository")
    private @Nullable String repository;

    /// Deprecated manifest-controlled proof URL retained only so validation can reject it.
    @SerializedName("repositoryAttestationUrl")
    private @Nullable String repositoryAttestationUrl;

    /// Published versions.
    @SerializedName("versions")
    private @Nullable List<@Nullable PluginVersionEntry> versions;

    /// Optional SPDX license expression.
    @SerializedName("license")
    private @Nullable String license;

    /// Optional project website URL.
    @SerializedName("website")
    private @Nullable String website;

    /// Optional source repository URL.
    @SerializedName("source")
    private @Nullable String source;

    /// Optional raw README URL displayed by the plugin details view.
    @SerializedName("readmeUrl")
    private @Nullable String readmeUrl;

    /// Creates an empty repository manifest for Gson deserialization.
    public PluginStoreManifest() {
    }

    /// Returns the schema version.
    ///
    /// @return schema version
    public int getSchemaVersion() {
        return schemaVersion;
    }

    /// Returns the validated plugin ID.
    ///
    /// @return plugin ID
    public String getId() {
        return Objects.requireNonNull(id, "Plugin store manifest has no id");
    }

    /// Returns the repository identity declared for dual certification.
    ///
    /// @return repository identity or an empty string for community manifests
    public String getRepository() {
        return Objects.requireNonNullElse(repository, "");
    }

    /// Returns immutable published versions.
    ///
    /// @return version list
    public @Unmodifiable List<PluginVersionEntry> getVersions() {
        @Nullable List<@Nullable PluginVersionEntry> values = versions;
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream().map(Objects::requireNonNull).toList();
    }

    /// Returns published versions sorted from newest to oldest.
    ///
    /// @return descending immutable version list
    public @Unmodifiable List<PluginVersionEntry> getVersionsNewestFirst() {
        List<PluginVersionEntry> sorted = new ArrayList<>(getVersions());
        sorted.sort((left, right) -> PluginVersion.compare(right.getVersion(), left.getVersion()));
        return List.copyOf(sorted);
    }

    /// Finds one published version by its exact version string.
    ///
    /// @param version exact version string
    /// @return matching version or `null`
    public @Nullable PluginVersionEntry getVersion(String version) {
        return getVersions().stream()
                .filter(candidate -> candidate.getVersion().equals(version))
                .findFirst()
                .orElse(null);
    }

    /// Returns the greatest published version independent of JSON array ordering.
    ///
    /// @return latest version or `null`
    public @Nullable PluginVersionEntry getLatestVersion() {
        return getVersions().stream()
                .max(Comparator.comparing(PluginVersionEntry::getVersion, PluginVersion::compare))
                .orElse(null);
    }

    /// Returns the optional license expression.
    ///
    /// @return license expression
    public String getLicense() {
        return Objects.requireNonNullElse(license, "");
    }

    /// Returns the optional project website.
    ///
    /// @return website URL
    public String getWebsite() {
        return Objects.requireNonNullElse(website, "");
    }

    /// Returns the optional source repository.
    ///
    /// @return source URL
    public String getSource() {
        return Objects.requireNonNullElse(source, "");
    }

    /// Returns the optional raw README URL.
    ///
    /// @return README URL or an empty string
    public String getReadmeUrl() {
        return Objects.requireNonNullElse(readmeUrl, "");
    }

    /// Parses and validates one repository manifest while preserving per-version field presence.
    ///
    /// @param json parsed repository manifest document
    /// @param expectedPluginId plugin ID from the parent registry entry
    /// @return validated repository manifest
    /// @throws IOException if the document is empty, malformed, invalid, or belongs to another plugin
    public static PluginStoreManifest fromJson(JsonElement json, String expectedPluginId) throws IOException {
        if (!json.isJsonObject()) {
            throw new IOException("Plugin repository manifest is not an object");
        }
        JsonObject root = json.getAsJsonObject();
        @Nullable PluginStoreManifest manifest;
        try {
            manifest = JsonUtils.GSON.fromJson(root, PluginStoreManifest.class);
        } catch (JsonParseException exception) {
            throw new IOException("Plugin repository manifest is malformed", exception);
        }
        if (manifest == null) {
            throw new IOException("Plugin repository manifest is empty");
        }
        manifest.captureVersionFieldPresence(root);
        manifest.validate(expectedPluginId);
        return manifest;
    }

    /// Records compatibility-field presence before Gson's null/default mapping discards that distinction.
    ///
    /// @param root repository manifest object parsed from the source document
    private void captureVersionFieldPresence(JsonObject root) {
        @Nullable JsonElement versionsElement = root.get("versions");
        if (versionsElement == null || !versionsElement.isJsonArray() || versions == null) {
            return;
        }
        int count = Math.min(versions.size(), versionsElement.getAsJsonArray().size());
        for (int index = 0; index < count; index++) {
            @Nullable PluginVersionEntry entry = versions.get(index);
            JsonElement source = versionsElement.getAsJsonArray().get(index);
            if (entry == null || !source.isJsonObject()) {
                continue;
            }
            JsonObject versionObject = source.getAsJsonObject();
            entry.runtimeDeclared = versionObject.has("runtime");
            entry.abiDeclared = versionObject.has("abi");
            entry.platformsDeclared = versionObject.has("platforms");
            entry.pluginKindDeclared = versionObject.has("pluginKind");
            @Nullable JsonElement pluginKindElement = versionObject.get("pluginKind");
            entry.pluginKindToken = pluginKindElement != null
                    && pluginKindElement.isJsonPrimitive()
                    && pluginKindElement.getAsJsonPrimitive().isString()
                    ? pluginKindElement.getAsString()
                    : null;
            entry.executionModeDeclared = versionObject.has("executionMode");
            @Nullable JsonElement executionModeElement = versionObject.get("executionMode");
            entry.executionModeToken = executionModeElement != null
                    && executionModeElement.isJsonPrimitive()
                    && executionModeElement.getAsJsonPrimitive().isString()
                    ? executionModeElement.getAsString()
                    : null;
            entry.runtimeProviderDeclared = versionObject.has("runtimeProvider");
            entry.providesRuntimesDeclared = versionObject.has("providesRuntimes");
            entry.packageUrlDeclared = versionObject.has("packageUrl");
            entry.sha256Declared = versionObject.has("sha256");
            entry.sizeDeclared = versionObject.has("size");
            entry.artifactsDeclared = versionObject.has("artifacts");
        }
    }

    /// Validates schema, plugin identity, version uniqueness, checksums, and API declarations.
    ///
    /// @param expectedPluginId plugin ID from the parent registry entry
    /// @throws IOException if the manifest is invalid or belongs to another plugin
    public void validate(String expectedPluginId) throws IOException {
        if (schemaVersion < 1 || schemaVersion > CURRENT_SCHEMA_VERSION) {
            throw new IOException("Unsupported plugin repository schemaVersion: " + schemaVersion);
        }
        if (!expectedPluginId.equals(id)) {
            throw new IOException("Plugin repository manifest ID " + id
                    + " does not match registry entry " + expectedPluginId);
        }
        if (repositoryAttestationUrl != null) {
            throw new IOException("Plugin manifests cannot select a repository attestation URL");
        }
        if (versions == null || versions.isEmpty()) {
            throw new IOException("Plugin repository has no versions: " + expectedPluginId);
        }

        Set<String> publishedVersions = new HashSet<>();
        for (@Nullable PluginVersionEntry version : versions) {
            if (version == null) {
                throw new IOException("Plugin repository contains a null version: " + expectedPluginId);
            }
            version.validate(schemaVersion);
            if (version.getDependencies().stream().anyMatch(dependency -> expectedPluginId.equals(dependency.getId()))) {
                throw new IOException("Plugin version " + version.getVersion() + " cannot depend on itself");
            }
            if (!publishedVersions.add(version.getVersion())) {
                throw new IOException("Duplicate plugin version " + version.getVersion() + " for " + expectedPluginId);
            }
        }
    }

    /// Metadata for one downloadable plugin package.
    @NotNullByDefault
    public static final class PluginVersionEntry {
        /// Published package version.
        @SerializedName("version")
        private @Nullable String version;

        /// Download URL for the `.npl` package.
        @SerializedName("packageUrl")
        private @Nullable String packageUrl;

        /// Whether the source JSON explicitly contained the legacy single-package URL.
        private transient boolean packageUrlDeclared;

        /// Required SHA-256 checksum.
        @SerializedName("sha256")
        private @Nullable String sha256;

        /// Whether the source JSON explicitly contained the legacy single-package checksum.
        private transient boolean sha256Declared;

        /// Minimum compatible launcher version.
        @SerializedName("minLauncherVersion")
        private @Nullable String minLauncherVersion;

        /// Schema-v4 launcher version constraint expressed with [PluginVersionConstraint] syntax.
        @SerializedName("launcherVersion")
        private @Nullable String launcherVersion;

        /// Optional release notes.
        @SerializedName("releaseNotes")
        private @Nullable String releaseNotes;

        /// Optional ISO release date.
        @SerializedName("releaseDate")
        private @Nullable String releaseDate;

        /// Minimum Java feature version represented as text for registry compatibility.
        @SerializedName("requiredJavaVersion")
        private @Nullable String requiredJavaVersion;

        /// Expected package size in bytes.
        @SerializedName("size")
        private @Nullable Long size;

        /// Whether the source JSON explicitly contained the legacy single-package size.
        private transient boolean sizeDeclared;

        /// Exact platform-specific package artifacts for schema-v5 versions.
        @SerializedName("artifacts")
        private @Nullable List<@Nullable PluginStoreArtifact> artifacts;

        /// Whether the source JSON explicitly contained the schema-v5 artifact matrix.
        private transient boolean artifactsDeclared;

        /// Required HMCL plugin manifest/API schema version.
        @SerializedName("pluginApiVersion")
        private int pluginApiVersion = 1;

        /// Canonical runtime identifier required by schema-v5 packages.
        @SerializedName("runtime")
        private @Nullable String runtime;

        /// Whether the source JSON explicitly contained the schema-v5 `runtime` property.
        private transient boolean runtimeDeclared;

        /// HMCL Plugin ABI generation required by schema-v5 packages; ABI 1 for legacy packages.
        @SerializedName("abi")
        private @Nullable Integer abi = PluginAbi.ABI_1;

        /// Whether the source JSON explicitly contained the schema-v5 `abi` property.
        private transient boolean abiDeclared;

        /// Canonical platform targets supported by schema-v5 packages; empty means unrestricted.
        @SerializedName("platforms")
        private @Nullable List<@Nullable String> platforms;

        /// Whether the source JSON explicitly contained the schema-v5 `platforms` property.
        private transient boolean platformsDeclared;

        /// Schema-v5 role of the package represented by this Store entry.
        @SerializedName("pluginKind")
        private @Nullable PluginKind pluginKind;

        /// Whether the source JSON explicitly contained the schema-v5 plugin role.
        private transient boolean pluginKindDeclared;

        /// Exact serialized plugin role token retained for canonical-spelling validation.
        private transient @Nullable String pluginKindToken;

        /// Execution boundary requested by a schema-v5 runtime consumer.
        @SerializedName("executionMode")
        private @Nullable PluginExecutionMode executionMode;

        /// Whether the source JSON explicitly contained the execution boundary.
        private transient boolean executionModeDeclared;

        /// Exact serialized execution mode retained for canonical-spelling validation.
        private transient @Nullable String executionModeToken;

        /// Optional provider plugin ID pinned by a schema-v5 runtime consumer.
        @SerializedName("runtimeProvider")
        private @Nullable String runtimeProvider;

        /// Whether the source JSON explicitly contained a provider pin.
        private transient boolean runtimeProviderDeclared;

        /// Runtime capabilities advertised by a schema-v5 Provider package.
        @SerializedName("providesRuntimes")
        private @Nullable List<@Nullable RuntimeProviderDeclaration> providesRuntimes;

        /// Whether the source JSON explicitly contained Provider capabilities.
        private transient boolean providesRuntimesDeclared;

        /// Whether installation or update is expected to require a launcher restart.
        @SerializedName("requiresRestart")
        private boolean requiresRestart;

        /// Release channel such as `stable`, `beta`, or `nightly`.
        @SerializedName("channel")
        private @Nullable String channel = "stable";

        /// Permissions declared by this exact package version.
        @SerializedName("permissions")
        private @Nullable List<@Nullable PluginPermission> permissions;

        /// Permissions required before this exact schema-v4 package version may execute.
        @SerializedName("requiredPermissions")
        private @Nullable List<@Nullable PluginPermission> requiredPermissions;

        /// Required plugins and version constraints for this exact package version.
        @SerializedName("dependencies")
        private @Nullable List<@Nullable PluginDependency> dependencies = List.of();

        /// Optional per-version certification declaration containing the exact NPL proof envelope.
        @SerializedName("certification")
        private @Nullable JsonObject certification;

        /// Locally derived trust for this exact version; never read from or written to remote JSON.
        private transient PluginTrustResult trust = PluginTrustResult.community();

        /// Whether the parent repository schema makes this dependency metadata authoritative for package checks.
        private transient boolean dependencyMetadataAuthoritative;

        /// Creates an empty version entry for Gson deserialization.
        public PluginVersionEntry() {
        }

        /// Returns the published version.
        ///
        /// @return version string
        public String getVersion() {
            return Objects.requireNonNull(version, "Plugin version has no version string");
        }

        /// Returns the package download URL.
        ///
        /// @return package URL
        public String getPackageUrl() {
            return Objects.requireNonNull(packageUrl, "Plugin version has no packageUrl");
        }

        /// Returns the required SHA-256 checksum.
        ///
        /// @return lower- or upper-case hexadecimal checksum
        public String getSha256() {
            return Objects.requireNonNull(sha256, "Plugin version has no sha256");
        }

        /// Returns the minimum launcher version or an empty string.
        ///
        /// @return minimum launcher version
        public String getMinLauncherVersion() {
            return Objects.requireNonNullElse(minLauncherVersion, "");
        }

        /// Returns the normalized launcher version constraint for this package version.
        ///
        /// API-v1 through API-v3 minimum versions are exposed as equivalent `>=` constraints. An absent legacy
        /// minimum accepts every launcher version.
        ///
        /// @return launcher version constraint expression
        public String getLauncherVersion() {
            if (pluginApiVersion >= 4) {
                return PluginVersionConstraint.parse(
                        Objects.requireNonNull(launcherVersion, "API-v4 version has no launcherVersion")
                ).getExpression();
            }
            String minimum = getMinLauncherVersion();
            return minimum.isBlank()
                    ? PluginVersionConstraint.ANY.getExpression()
                    : PluginVersionConstraint.parse(">=" + minimum).getExpression();
        }

        /// Returns the parsed launcher version constraint used by compatibility filtering.
        ///
        /// @return parsed launcher version constraint
        public PluginVersionConstraint getLauncherVersionConstraint() {
            return PluginVersionConstraint.parse(getLauncherVersion());
        }

        /// Returns whether one launcher version satisfies this package version's constraint.
        ///
        /// @param version launcher version to test
        /// @return whether the launcher is compatible
        public boolean matchesLauncherVersion(String version) {
            return getLauncherVersionConstraint().matches(version);
        }

        /// Returns optional release notes.
        ///
        /// @return release notes
        public String getReleaseNotes() {
            return Objects.requireNonNullElse(releaseNotes, "");
        }

        /// Returns the optional release date.
        ///
        /// @return release date
        public String getReleaseDate() {
            return Objects.requireNonNullElse(releaseDate, "");
        }

        /// Returns the minimum Java feature version or an empty string.
        ///
        /// @return required Java version
        public String getRequiredJavaVersion() {
            return Objects.requireNonNullElse(requiredJavaVersion, "");
        }

        /// Returns the expected package size.
        ///
        /// @return package size or `null`
        public @Nullable Long getSize() {
            return size;
        }

        /// Returns immutable exact platform artifacts in declaration order.
        ///
        /// @return platform artifact matrix, or an empty list for a legacy single package
        public @Unmodifiable List<PluginStoreArtifact> getArtifacts() {
            @Nullable List<@Nullable PluginStoreArtifact> values = artifacts;
            if (values == null || values.isEmpty()) {
                return List.of();
            }
            return values.stream().map(Objects::requireNonNull).toList();
        }

        /// Selects the package metadata for an exact operating-system and architecture target.
        ///
        /// Legacy single-package entries produce an immutable compatibility view for the requested target.
        /// Platform matrices never use operating-system-only or architecture translation fallback.
        ///
        /// @param target exact host target
        /// @return matching platform artifact or the legacy package compatibility view
        /// @throws IOException if a platform matrix has no exact target match
        public PluginStoreArtifact requireArtifact(PluginPlatformTarget target) throws IOException {
            @Unmodifiable List<PluginStoreArtifact> matrix = getArtifacts();
            if (matrix.isEmpty()) {
                return new PluginStoreArtifact(target, getPackageUrl(), getSha256().toLowerCase(Locale.ROOT),
                        Objects.requireNonNull(getSize(), "Plugin version has no size"));
            }
            for (PluginStoreArtifact artifact : matrix) {
                if (artifact.platform().equals(target)) {
                    return artifact;
                }
            }
            throw new IOException("No exact plugin artifact for " + target.getId()
                    + "; available targets: " + matrix.stream()
                    .map(artifact -> artifact.platform().getId())
                    .sorted()
                    .toList());
        }

        /// Returns the required plugin API schema version.
        ///
        /// @return plugin API version
        public int getPluginApiVersion() {
            return pluginApiVersion;
        }

        /// Returns the package role, defaulting omitted schema-v5 metadata to an ordinary plugin.
        ///
        /// @return package role
        public PluginKind getPluginKind() {
            return Objects.requireNonNullElse(pluginKind, PluginKind.NORMAL);
        }

        /// Returns the requested runtime execution boundary, defaulting to embedded execution.
        ///
        /// @return runtime execution boundary
        public PluginExecutionMode getExecutionMode() {
            return Objects.requireNonNullElse(executionMode, PluginExecutionMode.EMBEDDED);
        }

        /// Returns the optional pinned runtime Provider ID.
        ///
        /// @return pinned Provider ID, or `null` for deterministic selection
        public @Nullable String getRuntimeProvider() {
            return runtimeProvider;
        }

        /// Returns immutable runtime capabilities advertised by a Provider package.
        ///
        /// @return immutable Provider declarations
        public @Unmodifiable List<RuntimeProviderDeclaration> getProvidesRuntimes() {
            @Nullable List<@Nullable RuntimeProviderDeclaration> values = providesRuntimes;
            if (values == null || values.isEmpty()) {
                return List.of();
            }
            return values.stream().map(Objects::requireNonNull).toList();
        }

        /// Derives the virtual runtime requirement represented by this Store version.
        ///
        /// @return immutable runtime requirement
        public RuntimeRequirement getRuntimeRequirement() {
            return new RuntimeRequirement(
                    getRuntime(),
                    getAbi(),
                    1,
                    getExecutionMode(),
                    Set.of(RuntimeFeature.BRIDGE),
                    getRuntimeProvider()
            );
        }

        /// Returns the canonical required runtime, defaulting legacy packages to built-in Java.
        ///
        /// @return canonical runtime identifier
        public String getRuntime() {
            return runtime == null || runtime.isBlank() ? PluginRuntimeTypes.JAVA : runtime;
        }

        /// Returns the required ABI generation, defaulting legacy packages to ABI 1.
        ///
        /// @return required HMCL Plugin ABI generation
        public int getAbi() {
            return Objects.requireNonNullElse(abi, PluginAbi.ABI_1);
        }

        /// Returns sorted canonical platform targets, or an empty immutable list when unrestricted.
        ///
        /// @return normalized immutable platform target identifiers
        public @Unmodifiable List<String> getPlatforms() {
            @Nullable List<@Nullable String> values = platforms;
            if (values == null || values.isEmpty()) {
                return List.of();
            }
            return values.stream().map(Objects::requireNonNull).sorted().toList();
        }

        /// Converts this validated store entry to the shared compatibility evaluator contract.
        ///
        /// @return immutable launcher, schema, runtime, ABI, and platform requirements
        public PluginCompatibilityRequirements toCompatibilityRequirements() {
            @Unmodifiable List<PluginPlatformTarget> platformTargets = getPlatforms().stream()
                    .map(PluginPlatformTarget::parse)
                    .toList();
            return new PluginCompatibilityRequirements(
                    getPluginApiVersion(),
                    getLauncherVersion(),
                    getRuntime(),
                    getAbi(),
                    platformTargets
            );
        }

        /// Returns whether this version is expected to require a restart.
        ///
        /// @return restart requirement
        public boolean isRequiresRestart() {
            return requiresRestart;
        }

        /// Returns the normalized release channel.
        ///
        /// @return channel
        public String getChannel() {
            return Objects.requireNonNullElse(channel, "stable");
        }

        /// Returns declared permissions in manifest order.
        ///
        /// @return immutable permission list
        public @Unmodifiable List<PluginPermission> getPermissions() {
            @Nullable List<@Nullable PluginPermission> values = permissions;
            if (values == null || values.isEmpty()) {
                return List.of();
            }
            return values.stream().map(Objects::requireNonNull).toList();
        }

        /// Returns permissions required before this package version may execute.
        ///
        /// API-v3 entries preserve the launcher policy by treating every declared permission as required when the
        /// `mixin` capability is present and no permission as required otherwise. API-v4 entries use the explicit
        /// `requiredPermissions` declaration.
        ///
        /// @return immutable required permission list
        public @Unmodifiable List<PluginPermission> getRequiredPermissions() {
            if (pluginApiVersion < 4) {
                return getPermissions().contains(PluginPermission.MIXIN) ? getPermissions() : List.of();
            }
            @Nullable List<@Nullable PluginPermission> values = requiredPermissions;
            if (values == null || values.isEmpty()) {
                return List.of();
            }
            return values.stream().map(Objects::requireNonNull).toList();
        }

        /// Returns permissions that may be denied without blocking ordinary package execution.
        ///
        /// @return immutable optional permission list in declaration order
        public @Unmodifiable List<PluginPermission> getOptionalPermissions() {
            @Unmodifiable List<PluginPermission> required = getRequiredPermissions();
            if (required.isEmpty()) {
                return getPermissions();
            }
            return getPermissions().stream().filter(permission -> !required.contains(permission)).toList();
        }

        /// Returns required plugin dependencies and version constraints.
        ///
        /// @return immutable dependency list
        public @Unmodifiable List<PluginDependency> getDependencies() {
            @Nullable List<@Nullable PluginDependency> values = dependencies;
            if (values == null || values.isEmpty()) {
                return List.of();
            }
            return values.stream().map(Objects::requireNonNull).toList();
        }

        /// Returns whether this version declares any certification object.
        ///
        /// @return whether partial or complete certification metadata is present
        public boolean hasCertificationDeclaration() {
            return certification != null;
        }

        /// Returns the inline artifact-attestation envelope only when it is a JSON object.
        ///
        /// @return artifact proof envelope or `null` for absent or partial declarations
        public @Nullable JsonObject getArtifactAttestation() {
            @Nullable JsonObject declaration = certification;
            if (declaration == null) {
                return null;
            }
            JsonElement artifactAttestation = declaration.get("artifactAttestation");
            return artifactAttestation != null && artifactAttestation.isJsonObject()
                    ? artifactAttestation.getAsJsonObject()
                    : null;
        }

        /// Returns the locally derived trust decision for this exact package version.
        public PluginTrustResult getTrust() {
            return trust;
        }

        /// Assigns the locally derived trust decision before the enclosing manifest is published.
        ///
        /// @param trust verified decision for this exact version
        void setTrust(PluginTrustResult trust) {
            this.trust = Objects.requireNonNull(trust, "trust");
        }

        /// Returns whether downloaded package dependencies must exactly match this repository entry.
        ///
        /// Repository schema v2 introduced version-scoped dependency metadata. API-v3 entries are also treated as
        /// authoritative because their package schema requires an explicit security and dependency declaration.
        ///
        /// @return whether package dependency metadata must match
        public boolean hasAuthoritativeDependencies() {
            return dependencyMetadataAuthoritative || pluginApiVersion >= 3;
        }

        /// Validates required download metadata and supported plugin API version.
        ///
        /// @param repositorySchemaVersion parent repository schema version
        /// @throws IOException if the version entry is invalid
        private void validate(int repositorySchemaVersion) throws IOException {
            dependencyMetadataAuthoritative = repositorySchemaVersion >= 2;
            if (version == null || version.isBlank()) {
                throw new IOException("Plugin version entry has no version");
            }
            try {
                PluginVersion.compare(version, version);
            } catch (IllegalArgumentException exception) {
                throw new IOException("Plugin version entry has an invalid version", exception);
            }
            if (pluginApiVersion < 1) {
                throw new IOException("Plugin version " + version + " has an invalid plugin API "
                        + pluginApiVersion);
            }
            if (pluginApiVersion > PluginManifest.CURRENT_SCHEMA_VERSION) {
                throw new IOException("Plugin version " + version + " requires unsupported plugin API "
                        + pluginApiVersion);
            }
            validateArtifactMetadata();
            validateRuntimeCompatibilityMetadata();
            validateRuntimeProviderContract();
            if (releaseDate != null && !releaseDate.isBlank()) {
                try {
                    LocalDate.parse(releaseDate);
                } catch (DateTimeException exception) {
                    throw new IOException("Plugin version " + version + " has an invalid releaseDate", exception);
                }
            }
            String normalizedChannel = getChannel();
            if (!normalizedChannel.equals("stable")
                    && !normalizedChannel.equals("beta")
                    && !normalizedChannel.equals("nightly")) {
                throw new IOException("Plugin version " + version + " has an invalid channel: " + normalizedChannel);
            }

            if (pluginApiVersion >= 3 && permissions == null) {
                throw new IOException("Plugin version " + version + " must declare permissions");
            }
            EnumSet<PluginPermission> seenPermissions = EnumSet.noneOf(PluginPermission.class);
            if (permissions != null) {
                for (@Nullable PluginPermission permission : permissions) {
                    if (permission == null) {
                        throw new IOException("Plugin version " + version + " has an unknown permission");
                    }
                    if (!seenPermissions.add(permission)) {
                        throw new IOException("Plugin version " + version + " has duplicate permission "
                                + permission.getId());
                    }
                    if (pluginApiVersion < 5 && permission.isSchemaFiveOnly()) {
                        throw new IOException("Plugin version " + version
                                + " cannot declare schema-v5 permission " + permission.getId());
                    }
                }
            }

            if (pluginApiVersion >= 4 && requiredPermissions == null) {
                throw new IOException("Plugin version " + version + " must declare requiredPermissions");
            }
            if (pluginApiVersion < 4 && requiredPermissions != null) {
                throw new IOException("Plugin version " + version
                        + " cannot declare requiredPermissions before plugin API 4");
            }
            EnumSet<PluginPermission> seenRequiredPermissions = EnumSet.noneOf(PluginPermission.class);
            if (requiredPermissions != null) {
                for (@Nullable PluginPermission permission : requiredPermissions) {
                    if (permission == null) {
                        throw new IOException("Plugin version " + version + " has an unknown required permission");
                    }
                    if (!seenRequiredPermissions.add(permission)) {
                        throw new IOException("Plugin version " + version + " has duplicate required permission "
                                + permission.getId());
                    }
                    if (!seenPermissions.contains(permission)) {
                        throw new IOException("Plugin version " + version + " requires undeclared permission "
                                + permission.getId());
                    }
                }
            }
            if (pluginApiVersion >= 4
                    && seenPermissions.contains(PluginPermission.MIXIN)
                    && !seenRequiredPermissions.contains(PluginPermission.MIXIN)) {
                throw new IOException("Plugin version " + version + " must require permission mixin");
            }

            if (pluginApiVersion >= 4) {
                if (minLauncherVersion != null) {
                    throw new IOException("Plugin version " + version
                            + " cannot declare minLauncherVersion with plugin API 4");
                }
                requireValidLauncherVersionConstraint(launcherVersion, version);
            } else if (launcherVersion != null) {
                throw new IOException("Plugin version " + version
                        + " cannot declare launcherVersion before plugin API 4");
            } else if (!getMinLauncherVersion().isBlank()) {
                requireValidLegacyLauncherMinimum(getMinLauncherVersion(), version);
            }

            Set<String> dependencyIds = new HashSet<>();
            if (dependencies != null) {
                for (@Nullable PluginDependency dependency : dependencies) {
                    if (dependency == null) {
                        throw new IOException("Plugin version " + version + " has a null dependency");
                    }
                    if (!dependencyIds.add(dependency.getId())) {
                        throw new IOException("Plugin version " + version + " has duplicate dependency "
                                + dependency.getId());
                    }
                }
            }
        }

        /// Validates the mutually exclusive single-package and schema-v5 artifact-matrix representations.
        ///
        /// @throws IOException if package metadata is absent, mixed, duplicated, or schema-incompatible
        private void validateArtifactMetadata() throws IOException {
            if (pluginApiVersion < 5 && (artifactsDeclared || artifacts != null)) {
                throw new IOException("Plugin API " + pluginApiVersion + " cannot declare artifacts");
            }
            if (pluginApiVersion < 5 && (pluginKindDeclared || pluginKind != null)) {
                throw new IOException("Plugin API " + pluginApiVersion + " cannot declare pluginKind");
            }
            if (pluginApiVersion >= 5 && pluginKindDeclared
                    && (pluginKind == null || !pluginKind.getId().equals(pluginKindToken))) {
                throw new IOException("Plugin API 5 version " + version + " has invalid pluginKind");
            }
            if (pluginApiVersion >= 5 && artifactsDeclared
                    && (packageUrlDeclared || sha256Declared || sizeDeclared)) {
                throw new IOException("Plugin API 5 version " + version
                        + " cannot combine packageUrl, sha256, or size with artifacts");
            }
            if (pluginApiVersion >= 5 && artifactsDeclared) {
                if (artifacts == null || artifacts.isEmpty()) {
                    throw new IOException("Plugin API 5 version " + version + " has an empty artifact matrix");
                }
                Set<PluginPlatformTarget> targets = new HashSet<>();
                for (@Nullable PluginStoreArtifact artifact : artifacts) {
                    if (artifact == null) {
                        throw new IOException("Plugin API 5 version " + version + " has a null artifact");
                    }
                    if (artifact.platform().getArchitecture() == null) {
                        throw new IOException("Plugin artifact target must include an architecture: "
                                + artifact.platform().getId());
                    }
                    if (!targets.add(artifact.platform())) {
                        throw new IOException("Duplicate plugin artifact target: " + artifact.platform().getId());
                    }
                }
            } else {
                if (packageUrl == null || packageUrl.isBlank()) {
                    throw new IOException("Plugin version " + version + " has no packageUrl");
                }
                if (sha256 == null || !SHA256_PATTERN.matcher(sha256).matches()) {
                    throw new IOException("Plugin version " + version + " has an invalid SHA-256 checksum");
                }
                if (size == null || size <= 0) {
                    throw new IOException("Plugin version " + version + " has an invalid size");
                }
            }
            if (pluginApiVersion >= 5 && getPluginKind() == PluginKind.RUNTIME_PROVIDER && !artifactsDeclared) {
                throw new IOException("Runtime provider " + version + " must declare a platform artifact matrix");
            }
        }

        /// Validates schema-v5 runtime, ABI, and platform metadata and rejects it on legacy packages.
        ///
        /// @throws IOException if compatibility metadata is absent, unsupported, or not canonical
        private void validateRuntimeCompatibilityMetadata() throws IOException {
            if (pluginApiVersion < 5) {
                if (runtimeDeclared || runtime != null) {
                    throw new IOException("Plugin API " + pluginApiVersion + " cannot declare runtime");
                }
                if (abiDeclared) {
                    throw new IOException("Plugin API " + pluginApiVersion + " cannot declare abi");
                }
                if (platformsDeclared || platforms != null) {
                    throw new IOException("Plugin API " + pluginApiVersion + " cannot declare platforms");
                }
                if (executionModeDeclared || executionMode != null
                        || runtimeProviderDeclared || runtimeProvider != null
                        || providesRuntimesDeclared || providesRuntimes != null) {
                    throw new IOException("Plugin API " + pluginApiVersion
                            + " cannot declare runtime Provider metadata");
                }
                return;
            }

            if (!runtimeDeclared) {
                throw new IOException("Plugin API 5 version " + version + " must declare runtime");
            }
            if (runtime == null || runtime.isBlank()) {
                throw new IOException("Plugin API 5 version " + version + " has null or blank runtime");
            }
            try {
                String canonicalRuntime = PluginRuntimeTypes.requireValid(runtime);
                if (!runtime.equals(canonicalRuntime)) {
                    throw new IOException("Plugin runtime identifier must be canonical: " + runtime);
                }
            } catch (IllegalArgumentException exception) {
                throw new IOException("Invalid plugin runtime identifier: " + runtime, exception);
            }

            if (!abiDeclared) {
                throw new IOException("Plugin API 5 version " + version + " must declare abi");
            }
            if (abi == null) {
                throw new IOException("Plugin API 5 version " + version + " has null abi");
            }

            if (executionModeDeclared && (executionMode == null
                    || !executionMode.getId().equals(executionModeToken))) {
                throw new IOException("Plugin API 5 version " + version + " has invalid executionMode");
            }
            if (runtimeProviderDeclared) {
                if (runtimeProvider == null || !PluginManifest.isCanonicalExecutableId(runtimeProvider)) {
                    throw new IOException("Plugin API 5 version " + version + " has invalid runtimeProvider");
                }
            }
            if (providesRuntimesDeclared && providesRuntimes == null) {
                throw new IOException("Plugin API 5 version " + version + " has null providesRuntimes");
            }
            if (providesRuntimes != null && providesRuntimes.stream().anyMatch(Objects::isNull)) {
                throw new IOException("Plugin API 5 version " + version + " has a null runtime declaration");
            }
            try {
                PluginAbi.requireValid(abi);
            } catch (IllegalArgumentException exception) {
                throw new IOException("Unsupported plugin ABI: " + abi, exception);
            }

            if (!platformsDeclared) {
                return;
            }
            if (platforms == null) {
                throw new IOException("Plugin platforms cannot be null");
            }
            Set<String> seenPlatforms = new HashSet<>();
            for (@Nullable String platform : platforms) {
                if (platform == null) {
                    throw new IOException("Plugin platform target cannot be null");
                }
                try {
                    String canonicalPlatform = PluginPlatformTarget.parse(platform).getId();
                    if (!canonicalPlatform.equals(platform)) {
                        throw new IOException("Plugin platform target must be canonical: " + platform);
                    }
                    if (!seenPlatforms.add(canonicalPlatform)) {
                        throw new IOException("Duplicate plugin platform target: " + platform);
                    }
                } catch (IllegalArgumentException exception) {
                    throw new IOException("Invalid plugin platform target: " + platform, exception);
                }
            }
        }

        /// Validates Store role metadata needed for deterministic virtual runtime resolution.
        ///
        /// @throws IOException if consumer and Provider declarations are inconsistent
        private void validateRuntimeProviderContract() throws IOException {
            if (pluginApiVersion < 5) {
                return;
            }
            @Unmodifiable List<RuntimeProviderDeclaration> declarations = getProvidesRuntimes();
            if (getPluginKind() == PluginKind.NORMAL) {
                if (!declarations.isEmpty()) {
                    throw new IOException("Normal Store plugins cannot provide runtimes");
                }
                try {
                    getRuntimeRequirement();
                } catch (IllegalArgumentException exception) {
                    throw new IOException("Invalid Store runtime requirement: " + exception.getMessage(), exception);
                }
                return;
            }
            if (!PluginRuntimeTypes.JAVA.equals(getRuntime())) {
                throw new IOException("Store runtime Providers must use the java runtime");
            }
            if (getExecutionMode() != PluginExecutionMode.EMBEDDED) {
                throw new IOException("Store runtime Providers must use embedded Java bootstrap execution");
            }
            if (runtimeProvider != null) {
                throw new IOException("Store runtime Providers cannot pin another Provider");
            }
            if (declarations.isEmpty()) {
                throw new IOException("Store runtime Providers must advertise at least one runtime");
            }
            Set<String> runtimeIds = new HashSet<>();
            for (RuntimeProviderDeclaration declaration : declarations) {
                if (PluginRuntimeTypes.JAVA.equals(declaration.getRuntime())) {
                    throw new IOException("Store runtime Providers cannot replace the built-in java runtime");
                }
                if (!runtimeIds.add(declaration.getRuntime())) {
                    throw new IOException("Duplicate Store provided runtime: " + declaration.getRuntime());
                }
            }
        }

        /// Requires a launcher constraint to be present and accepted by the shared version parser.
        ///
        /// @param value serialized launcher constraint
        /// @param versionName package version used in diagnostics
        /// @throws IOException if the value is missing, blank, or malformed
        private static void requireValidLauncherVersionConstraint(
                @Nullable String value,
                String versionName
        ) throws IOException {
            if (value == null || value.isBlank()) {
                throw new IOException("Plugin version " + versionName + " must declare launcherVersion");
            }
            try {
                PluginVersionConstraint.parse(value);
            } catch (IllegalArgumentException exception) {
                throw new IOException("Plugin version " + versionName
                        + " has invalid launcherVersion constraint " + value, exception);
            }
        }

        /// Validates one legacy minimum launcher version through the shared constraint parser.
        ///
        /// @param value legacy minimum launcher version
        /// @param versionName package version used in diagnostics
        /// @throws IOException if the minimum cannot form a valid `>=` constraint
        private static void requireValidLegacyLauncherMinimum(
                String value,
                String versionName
        ) throws IOException {
            try {
                PluginVersionConstraint.parse(">=" + value);
            } catch (IllegalArgumentException exception) {
                throw new IOException("Plugin version " + versionName
                        + " has invalid minLauncherVersion " + value, exception);
            }
        }
    }
}
