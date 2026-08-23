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
package org.jackhuang.hmcl.plugin;

import com.google.gson.JsonParseException;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.annotations.SerializedName;
import org.jackhuang.hmcl.plugin.runtime.PluginAbi;
import org.jackhuang.hmcl.plugin.runtime.PluginPlatformTarget;
import org.jackhuang.hmcl.plugin.runtime.PluginRuntimeTypes;
import org.jackhuang.hmcl.util.gson.JsonUtils;
import org.jackhuang.hmcl.util.gson.LowerCaseEnumTypeAdapter;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.io.Reader;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/// Describes one HMCL plugin package through its root `plugin.json` file.
@NotNullByDefault
public final class PluginManifest {
    /// Current manifest schema understood by HMCL and the plugin SDK.
    public static final int CURRENT_SCHEMA_VERSION = 5;

    /// Only manifest schema whose plugin code may install or execute.
    public static final int MIN_EXECUTABLE_SCHEMA_VERSION = 4;

    /// Pattern accepted for plugin IDs and dependency IDs.
    private static final Pattern ID_PATTERN = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9._-]{1,127}");

    /// Windows device basenames that cannot safely identify files or cache directories.
    private static final @Unmodifiable Set<String> WINDOWS_DEVICE_NAMES = Set.of(
            "con", "prn", "aux", "nul",
            "com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8", "com9",
            "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9"
    );

    /// Manifest schema version; legacy packages without this field use version 1.
    @SerializedName("schemaVersion")
    private int schemaVersion = 1;

    /// Globally unique plugin identifier populated by Gson.
    @SerializedName("id")
    private @Nullable String id;

    /// Human-readable plugin name populated by Gson.
    @SerializedName("name")
    private @Nullable String name;

    /// Plugin release version populated by Gson.
    @SerializedName("version")
    private @Nullable String version;

    /// Optional plugin description populated by Gson.
    @SerializedName("description")
    private @Nullable String description = "";

    /// Optional plugin author populated by Gson.
    @SerializedName("author")
    private @Nullable String author = "";

    /// Optional package-relative icon resource populated by Gson.
    @SerializedName("icon")
    private @Nullable String icon;

    /// Runtime implementation type populated by Gson.
    @SerializedName("type")
    private @Nullable PluginType type;

    /// Lifecycle entry point class populated by Gson.
    @SerializedName("entrypoint")
    private @Nullable String entrypoint;

    /// Required plugins and compatible installed versions.
    @SerializedName("dependencies")
    private @Nullable List<@Nullable PluginDependency> dependencies = List.of();

    /// Sensitive launcher capabilities explicitly declared by schema-v3 and newer packages.
    @SerializedName("permissions")
    private @Nullable List<@Nullable PluginPermission> permissions = List.of();

    /// Whether the source JSON explicitly contained the `permissions` property.
    private transient boolean permissionsDeclared;

    /// Permissions that must be granted before a schema-v4 plugin may execute.
    @SerializedName("requiredPermissions")
    private @Nullable List<@Nullable PluginPermission> requiredPermissions = List.of();

    /// Whether the source JSON explicitly contained the schema-v4 `requiredPermissions` property.
    private transient boolean requiredPermissionsDeclared;

    /// Minimum compatible HMCL version, or an empty string when unrestricted.
    @SerializedName("minLauncherVersion")
    private @Nullable String minLauncherVersion = "";

    /// Whether the source JSON explicitly contained the legacy `minLauncherVersion` property.
    private transient boolean minLauncherVersionDeclared;

    /// Schema-v4 launcher version constraint expressed with [PluginVersionConstraint] syntax.
    @SerializedName("launcherVersion")
    private @Nullable String launcherVersion;

    /// Whether the source JSON explicitly contained the schema-v4 `launcherVersion` property.
    private transient boolean launcherVersionDeclared;

    /// Schema-v5 runtime identifier; defaults to the built-in Java runtime.
    @SerializedName("runtime")
    private @Nullable String runtime;

    /// Whether the source JSON explicitly contained the schema-v5 `runtime` property.
    private transient boolean runtimeDeclared;

    /// Schema-v5 HMCL Plugin ABI generation required by this package; ABI 1 when omitted.
    @SerializedName("abi")
    private int abi = PluginAbi.ABI_1;

    /// Whether the source JSON explicitly contained the schema-v5 `abi` property.
    private transient boolean abiDeclared;

    /// Schema-v5 platform targets; null means platform independent.
    @SerializedName("platforms")
    private @Nullable List<@Nullable String> platforms;

    /// Whether the source JSON explicitly contained the schema-v5 `platforms` property.
    private transient boolean platformsDeclared;

    /// Optional schema-v5 launcher lifecycle hook subscriptions.
    @SerializedName("hooks")
    private @Nullable List<@Nullable PluginHookPoint> hooks = List.of();

    /// Whether the source JSON explicitly contained the schema-v5 `hooks` property.
    private transient boolean hooksDeclared;

    /// Optional schema-v5 declarative method patches.
    @SerializedName("patches")
    private @Nullable List<@Nullable PluginPatchDeclaration> patches = List.of();

    /// Whether the source JSON explicitly contained the schema-v5 `patches` property.
    private transient boolean patchesDeclared;

    /// Mixin configuration resources contributed by Java or Kotlin plugins.
    @SerializedName("mixins")
    private @Nullable List<@Nullable String> mixins = List.of();

    /// Creates an empty manifest for Gson deserialization.
    public PluginManifest() {
    }

    /// Creates the required portion of a plugin manifest programmatically.
    ///
    /// @param id globally unique plugin ID
    /// @param name human-readable plugin name
    /// @param version plugin version
    /// @param type plugin implementation type
    /// @param entrypoint lifecycle entry point
    public PluginManifest(String id, String name, String version, PluginType type, String entrypoint) {
        this.schemaVersion = CURRENT_SCHEMA_VERSION;
        this.id = id;
        this.name = name;
        this.version = version;
        this.type = type;
        this.entrypoint = entrypoint;
        this.permissionsDeclared = true;
        this.requiredPermissionsDeclared = true;
        this.launcherVersion = PluginVersionConstraint.ANY.getExpression();
        this.launcherVersionDeclared = true;
        this.runtime = PluginRuntimeTypes.JAVA;
        this.runtimeDeclared = true;
        this.abi = PluginAbi.ABI_2;
        this.abiDeclared = true;
    }

    /// Returns the manifest schema version.
    ///
    /// @return schema version
    public int getSchemaVersion() {
        return schemaVersion;
    }

    /// Returns the validated plugin ID.
    ///
    /// @return plugin ID
    public String getId() {
        return Objects.requireNonNull(id, "Plugin manifest has no id");
    }

    /// Returns the validated plugin display name.
    ///
    /// @return plugin name
    public String getName() {
        return Objects.requireNonNull(name, "Plugin manifest has no name");
    }

    /// Returns the validated plugin version.
    ///
    /// @return plugin version
    public String getVersion() {
        return Objects.requireNonNull(version, "Plugin manifest has no version");
    }

    /// Returns the optional description as a non-null string.
    ///
    /// @return plugin description
    public String getDescription() {
        return Objects.requireNonNullElse(description, "");
    }

    /// Returns the optional author as a non-null string.
    ///
    /// @return plugin author
    public String getAuthor() {
        return Objects.requireNonNullElse(author, "");
    }

    /// Returns the optional package-relative icon resource.
    ///
    /// @return icon resource path, or `null` when the package uses the launcher fallback icon
    public @Nullable String getIcon() {
        return icon;
    }

    /// Returns the validated plugin implementation type.
    ///
    /// @return plugin type
    public PluginType getType() {
        return Objects.requireNonNull(type, "Plugin manifest has no type");
    }

    /// Returns the validated lifecycle entry point.
    ///
    /// @return entry point class or script path
    public String getEntrypoint() {
        return Objects.requireNonNull(entrypoint, "Plugin manifest has no entrypoint");
    }

    /// Returns an immutable snapshot of required plugin IDs.
    ///
    /// @return dependency IDs
    public @Unmodifiable List<String> getDependencies() {
        @Nullable List<@Nullable PluginDependency> values = dependencies;
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .map(Objects::requireNonNull)
                .map(PluginDependency::getId)
                .toList();
    }

    /// Returns an immutable snapshot of structured plugin dependencies.
    ///
    /// @return plugin dependencies and version constraints
    public @Unmodifiable List<PluginDependency> getPluginDependencies() {
        @Nullable List<@Nullable PluginDependency> values = dependencies;
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream().map(Objects::requireNonNull).toList();
    }

    /// Returns an immutable snapshot of explicitly declared sensitive capabilities.
    ///
    /// Legacy schema versions always return an empty list because they cannot declare permissions.
    ///
    /// @return declared plugin permissions
    public @Unmodifiable List<PluginPermission> getPermissions() {
        @Nullable List<@Nullable PluginPermission> values = permissions;
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream().map(Objects::requireNonNull).toList();
    }

    /// Returns permissions that must be granted before this plugin may execute.
    ///
    /// Schema-v3 ordinary plugins have no required permissions, while schema-v3 Mixin plugins preserve their
    /// historical atomic policy by treating every declared permission as required. Schema-v4 packages use their
    /// explicit `requiredPermissions` declaration.
    ///
    /// @return immutable required permission list
    public @Unmodifiable List<PluginPermission> getRequiredPermissions() {
        if (schemaVersion < 4) {
            return hasMixins() ? getPermissions() : List.of();
        }
        @Nullable List<@Nullable PluginPermission> values = requiredPermissions;
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream().map(Objects::requireNonNull).toList();
    }

    /// Returns declared permissions that may be denied without blocking ordinary plugin execution.
    ///
    /// @return immutable optional permission list in declaration order
    public @Unmodifiable List<PluginPermission> getOptionalPermissions() {
        @Unmodifiable List<PluginPermission> required = getRequiredPermissions();
        if (required.isEmpty()) {
            return getPermissions();
        }
        return getPermissions().stream().filter(permission -> !required.contains(permission)).toList();
    }

    /// Returns whether one declared permission is required for plugin execution.
    ///
    /// @param permission permission to query
    /// @return whether the permission is required
    public boolean isPermissionRequired(PluginPermission permission) {
        return getRequiredPermissions().contains(permission);
    }

    /// Returns whether this manifest explicitly declares one sensitive capability.
    ///
    /// @param permission capability to query
    /// @return whether the permission is declared
    public boolean declaresPermission(PluginPermission permission) {
        return getPermissions().contains(permission);
    }

    /// Returns the minimum compatible launcher version, or an empty string.
    ///
    /// @return minimum launcher version
    public String getMinLauncherVersion() {
        return Objects.requireNonNullElse(minLauncherVersion, "");
    }

    /// Returns the normalized launcher version constraint for this package.
    ///
    /// Schema-v1 through schema-v3 `minLauncherVersion` values are exposed as equivalent `>=` constraints. An
    /// absent legacy minimum accepts every launcher version.
    ///
    /// @return launcher version constraint expression
    public String getLauncherVersion() {
        if (schemaVersion >= 4) {
            return PluginVersionConstraint.parse(
                    Objects.requireNonNull(launcherVersion, "Schema-v4 manifest has no launcherVersion")
            ).getExpression();
        }
        String minimum = getMinLauncherVersion();
        return minimum.isBlank()
                ? PluginVersionConstraint.ANY.getExpression()
                : PluginVersionConstraint.parse(">=" + minimum).getExpression();
    }

    /// Returns the parsed launcher version constraint for compatibility checks.
    ///
    /// @return parsed launcher version constraint
    public PluginVersionConstraint getLauncherVersionConstraint() {
        return PluginVersionConstraint.parse(getLauncherVersion());
    }

    /// Returns whether one launcher version satisfies this package's declared constraint.
    ///
    /// @param version launcher version to test
    /// @return whether the launcher is compatible
    public boolean matchesLauncherVersion(String version) {
        return getLauncherVersionConstraint().matches(version);
    }

    /// Returns an immutable snapshot of declared Mixin configuration resources.
    ///
    /// @return Mixin configuration resource names
    public @Unmodifiable List<String> getMixins() {
        @Nullable List<@Nullable String> values = mixins;
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream().map(Objects::requireNonNull).toList();
    }

    /// Returns whether this plugin contributes startup-time Mixin transformations.
    ///
    /// @return whether at least one Mixin configuration is declared
    public boolean hasMixins() {
        return mixins != null && !mixins.isEmpty();
    }

    /// Compares every validated field that can affect plugin identity, authorization, dependency ordering, or loading.
    ///
    /// @param other comparison target
    /// @return whether both manifests describe the same executable package contract
    @Override
    public boolean equals(@Nullable Object other) {
        return this == other
                || other instanceof PluginManifest manifest
                && schemaVersion == manifest.schemaVersion
                && getId().equals(manifest.getId())
                && getName().equals(manifest.getName())
                && getVersion().equals(manifest.getVersion())
                && getDescription().equals(manifest.getDescription())
                && getAuthor().equals(manifest.getAuthor())
                && Objects.equals(getIcon(), manifest.getIcon())
                && getType() == manifest.getType()
                && getEntrypoint().equals(manifest.getEntrypoint())
                && getPluginDependencies().equals(manifest.getPluginDependencies())
                && getPermissions().equals(manifest.getPermissions())
                && getRequiredPermissions().equals(manifest.getRequiredPermissions())
                && getLauncherVersion().equals(manifest.getLauncherVersion())
                && getRuntime().equals(manifest.getRuntime())
                && getAbi() == manifest.getAbi()
                && getPlatforms().equals(manifest.getPlatforms())
                && getHooks().equals(manifest.getHooks())
                && getPatches().equals(manifest.getPatches())
                && getMixins().equals(manifest.getMixins());
    }

    /// Returns a hash derived from every executable package-contract field.
    ///
    /// @return manifest contract hash
    @Override
    public int hashCode() {
        return Objects.hash(
                schemaVersion,
                getId(),
                getName(),
                getVersion(),
                getDescription(),
                getAuthor(),
                getIcon(),
                getType(),
                getEntrypoint(),
                getPluginDependencies(),
                getPermissions(),
                getRequiredPermissions(),
                getLauncherVersion(),
                getRuntime(),
                getAbi(),
                getPlatforms(),
                getHooks(),
                getPatches(),
                getMixins()
        );
    }

    /// Returns the schema-v5 runtime identifier, defaulting to the built-in Java runtime.
    public String getRuntime() {
        return runtime == null || runtime.isBlank() ? PluginRuntimeTypes.JAVA : runtime;
    }

    /// Returns the HMCL Plugin ABI generation required by this package; ABI 1 when omitted.
    public int getAbi() {
        return abi;
    }

    /// Returns whether this package is restricted to at least one declared platform target.
    public boolean isPlatformRestricted() {
        return platforms != null && !platforms.isEmpty();
    }

    /// Returns a sorted immutable snapshot of canonical schema-v5 platform target identifiers.
    public @Unmodifiable List<String> getPlatforms() {
        @Nullable List<@Nullable String> values = platforms;
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream().map(Objects::requireNonNull).sorted().toList();
    }

    /// Returns an immutable snapshot of declared lifecycle hook points.
    ///
    /// @return lifecycle hook points in declaration order
    public @Unmodifiable List<PluginHookPoint> getHooks() {
        @Nullable List<@Nullable PluginHookPoint> values = hooks;
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream().map(Objects::requireNonNull).toList();
    }

    /// Returns an immutable snapshot of declared method patches.
    ///
    /// @return method patches in declaration order
    public @Unmodifiable List<PluginPatchDeclaration> getPatches() {
        @Nullable List<@Nullable PluginPatchDeclaration> values = patches;
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream().map(Objects::requireNonNull).toList();
    }

    /// Returns whether this manifest subscribes to at least one lifecycle hook.
    ///
    /// @return whether hooks are declared
    public boolean hasHooks() {
        return hooks != null && !hooks.isEmpty();
    }

    /// Returns whether this manifest declares at least one method patch.
    ///
    /// @return whether patches are declared
    public boolean hasPatches() {
        return patches != null && !patches.isEmpty();
    }

    /// Returns the highest capability tier enabled by hook and patch declarations.
    ///
    /// @return derived plugin capability tier
    public PluginCapabilityLevel getCapabilityLevel() {
        return PluginCapabilityLevel.of(hasHooks(), hasPatches());
    }

    /// Validates all fields used by discovery, dependency resolution, lifecycle loading, and Mixin bootstrap.
    ///
    /// @throws IOException if the manifest is invalid or unsupported
    public void validate() throws IOException {
        if (schemaVersion < 1 || schemaVersion > CURRENT_SCHEMA_VERSION) {
            throw new IOException("Unsupported plugin manifest schemaVersion: " + schemaVersion);
        }
        if (!isValidId(id)) {
            throw new IOException("Invalid plugin id: " + id);
        }
        requireNonBlank(name, "name");
        requireNonBlank(version, "version");
        if (type == null) {
            throw new IOException("Missing plugin type");
        }
        requireNonBlank(entrypoint, "entrypoint");
        requireValidIconResource(icon);

        if (schemaVersion < 5
                && (runtimeDeclared || abiDeclared || platformsDeclared || hooksDeclared || patchesDeclared)) {
            throw new IOException("Plugin manifest schemaVersion " + schemaVersion
                    + " cannot declare schema-v5 runtime capabilities");
        }
        if (schemaVersion >= 5) {
            if (!runtimeDeclared || runtime == null || runtime.isBlank()) {
                throw new IOException("Schema-v5 plugin manifest must declare runtime");
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
                throw new IOException("Schema-v5 plugin manifest must declare abi");
            }
            try {
                PluginAbi.requireValid(abi);
            } catch (IllegalArgumentException exception) {
                throw new IOException("Unsupported plugin manifest abi: " + abi, exception);
            }
        } else if (runtime != null) {
            throw new IOException("Plugin manifest schemaVersion " + schemaVersion
                    + " cannot declare runtime");
        }
        if (platformsDeclared) {
            if (schemaVersion < 5) {
                throw new IOException("Plugin manifest schemaVersion " + schemaVersion
                        + " cannot declare platforms");
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

        if (hooksDeclared) {
            if (schemaVersion < 5) {
                throw new IOException("Plugin manifest schemaVersion " + schemaVersion
                        + " cannot declare hooks");
            }
            if (hooks == null) {
                throw new IOException("Plugin hooks cannot be null");
            }
        }
        Set<PluginHookPoint> seenHooks = EnumSet.noneOf(PluginHookPoint.class);
        if (hooks != null) {
            for (@Nullable PluginHookPoint hook : hooks) {
                if (hook == null) {
                    throw new IOException("Plugin hook point cannot be null or unknown");
                }
                if (!seenHooks.add(hook)) {
                    throw new IOException("Duplicate plugin hook point: " + hook.getId());
                }
            }
        }

        if (patchesDeclared) {
            if (schemaVersion < 5) {
                throw new IOException("Plugin manifest schemaVersion " + schemaVersion
                        + " cannot declare patches");
            }
            if (patches == null) {
                throw new IOException("Plugin patches cannot be null");
            }
        }
        Set<PluginPatchDeclaration> seenPatches = new HashSet<>();
        if (patches != null) {
            for (@Nullable PluginPatchDeclaration patch : patches) {
                if (patch == null) {
                    throw new IOException("Plugin patch declaration cannot be null");
                }
                try {
                    patch.validate();
                } catch (IllegalArgumentException exception) {
                    throw new IOException("Invalid plugin patch declaration: " + exception.getMessage(), exception);
                }
                if (!seenPatches.add(patch)) {
                    throw new IOException("Duplicate plugin patch declaration: "
                            + patch.getTarget() + "." + patch.getMethod());
                }
            }
        }

        if (schemaVersion >= 3 && !permissionsDeclared) {
            throw new IOException("Schema-v3 plugin manifest must declare permissions");
        }
        if (schemaVersion < 3 && permissionsDeclared) {
            throw new IOException("Plugin manifest schemaVersion " + schemaVersion
                    + " cannot declare permissions");
        }
        if (permissions == null) {
            throw new IOException("Plugin permissions cannot be null");
        }
        Set<PluginPermission> declaredPermissions = EnumSet.noneOf(PluginPermission.class);
        for (@Nullable PluginPermission permission : permissions) {
            if (permission == null) {
                throw new IOException("Plugin permission cannot be null or unknown");
            }
            if (!declaredPermissions.add(permission)) {
                throw new IOException("Duplicate plugin permission: " + permission.getId());
            }
        }
        if (schemaVersion < 5
                && (declaredPermissions.contains(PluginPermission.LAUNCHER_HOOK)
                || declaredPermissions.contains(PluginPermission.LAUNCHER_PATCH))) {
            throw new IOException("Plugin manifest schemaVersion " + schemaVersion
                    + " cannot declare schema-v5 launcher permissions");
        }

        if (schemaVersion >= 4 && !requiredPermissionsDeclared) {
            throw new IOException("Schema-v4 plugin manifest must declare requiredPermissions");
        }
        if (schemaVersion < 4 && requiredPermissionsDeclared) {
            throw new IOException("Plugin manifest schemaVersion " + schemaVersion
                    + " cannot declare requiredPermissions");
        }
        if (requiredPermissions == null) {
            throw new IOException("Plugin requiredPermissions cannot be null");
        }
        Set<PluginPermission> required = EnumSet.noneOf(PluginPermission.class);
        for (@Nullable PluginPermission permission : requiredPermissions) {
            if (permission == null) {
                throw new IOException("Required plugin permission cannot be null or unknown");
            }
            if (!required.add(permission)) {
                throw new IOException("Duplicate required plugin permission: " + permission.getId());
            }
            if (!declaredPermissions.contains(permission)) {
                throw new IOException("Required plugin permission is not declared: " + permission.getId());
            }
        }

        if (hasHooks()
                && (!declaredPermissions.contains(PluginPermission.LAUNCHER_HOOK)
                || !required.contains(PluginPermission.LAUNCHER_HOOK))) {
            throw new IOException("Plugin hooks require launcher-hook in permissions and requiredPermissions");
        }
        if (hasPatches()
                && (!declaredPermissions.contains(PluginPermission.LAUNCHER_PATCH)
                || !required.contains(PluginPermission.LAUNCHER_PATCH))) {
            throw new IOException("Plugin patches require launcher-patch in permissions and requiredPermissions");
        }

        if (schemaVersion >= 4) {
            if (!launcherVersionDeclared) {
                throw new IOException("Schema-v4 plugin manifest must declare launcherVersion");
            }
            if (minLauncherVersionDeclared) {
                throw new IOException("Schema-v4 plugin manifest cannot declare minLauncherVersion");
            }
            requireValidLauncherVersionConstraint(launcherVersion);
        } else if (launcherVersionDeclared) {
            throw new IOException("Plugin manifest schemaVersion " + schemaVersion
                    + " cannot declare launcherVersion");
        } else if (!getMinLauncherVersion().isBlank()) {
            requireValidLegacyLauncherMinimum(getMinLauncherVersion());
        }

        if (schemaVersion >= 4
                && declaredPermissions.contains(PluginPermission.MIXIN)
                && !required.contains(PluginPermission.MIXIN)) {
            throw new IOException("Schema-v4 plugin must require declared permission mixin");
        }

        Set<String> dependencyIds = new HashSet<>();
        if (dependencies == null) {
            throw new IOException("Plugin dependencies cannot be null");
        }
        for (@Nullable PluginDependency dependency : dependencies) {
            if (dependency == null) {
                throw new IOException("Plugin dependency cannot be null");
            }
            String dependencyId = dependency.getId();
            if (!isValidId(dependencyId)) {
                throw new IOException("Invalid plugin dependency: " + dependencyId);
            }
            if (getId().equals(dependencyId)) {
                throw new IOException("Plugin cannot depend on itself: " + dependencyId);
            }
            if (!dependencyIds.add(dependencyId)) {
                throw new IOException("Duplicate plugin dependency: " + dependencyId);
            }
        }

        if (mixins != null && !mixins.isEmpty()) {
            if (schemaVersion >= 3 && !declaredPermissions.contains(PluginPermission.MIXIN)) {
                throw new IOException("Plugin with Mixins must declare permission mixin");
            }
            Set<String> configNames = new HashSet<>();
            for (@Nullable String candidate : mixins) {
                if (candidate == null) {
                    throw new IOException("Mixin configuration name cannot be null");
                }
                String config = candidate.trim();
                if (config.isEmpty()
                        || config.startsWith("/")
                        || config.contains("\\")
                        || config.contains(":")
                        || config.contains("../")
                        || !config.endsWith(".json")) {
                    throw new IOException("Invalid Mixin configuration resource: " + candidate);
                }
                if (!configNames.add(config.toLowerCase(Locale.ROOT))) {
                    throw new IOException("Duplicate Mixin configuration resource: " + candidate);
                }
            }
        }
    }

    /// Reads and validates a plugin manifest from JSON.
    ///
    /// @param reader UTF-8 JSON reader
    /// @return validated manifest
    /// @throws IOException if parsing or validation fails
    /// @throws JsonParseException if Gson rejects the JSON representation
    public static PluginManifest fromJson(Reader reader) throws IOException, JsonParseException {
        @Nullable JsonElement json = JsonParser.parseReader(reader);
        @Nullable JsonObject root = json != null && json.isJsonObject() ? json.getAsJsonObject() : null;
        @Nullable PluginManifest manifest = JsonUtils.GSON.fromJson(json, PluginManifest.class);
        if (manifest == null) {
            throw new IOException("Plugin manifest is empty");
        }
        manifest.permissionsDeclared = root != null && root.has("permissions");
        manifest.requiredPermissionsDeclared = root != null && root.has("requiredPermissions");
        manifest.minLauncherVersionDeclared = root != null && root.has("minLauncherVersion");
        manifest.launcherVersionDeclared = root != null && root.has("launcherVersion");
        manifest.runtimeDeclared = root != null && root.has("runtime");
        manifest.abiDeclared = root != null && root.has("abi");
        manifest.platformsDeclared = root != null && root.has("platforms");
        manifest.hooksDeclared = root != null && root.has("hooks");
        manifest.patchesDeclared = root != null && root.has("patches");
        if (manifest.schemaVersion == CURRENT_SCHEMA_VERSION && root != null) {
            if (root.has("abi") && root.get("abi").isJsonNull()) {
                throw new IOException("Plugin manifest abi cannot be null");
            }
            requireKnownHookTokens(root);
            requireKnownPatchTypeTokens(root);
        }
        manifest.validate();
        return manifest;
    }

    /// Rejects unknown string hook identifiers before enum deserialization loses the source token.
    ///
    /// Other malformed hook representations remain the responsibility of normal manifest validation.
    ///
    /// @param root parsed manifest root
    /// @throws IOException if a hook string does not identify a supported lifecycle point
    private static void requireKnownHookTokens(JsonObject root) throws IOException {
        @Nullable JsonElement hooksValue = root.get("hooks");
        if (hooksValue == null || !hooksValue.isJsonArray()) {
            return;
        }
        for (JsonElement candidate : hooksValue.getAsJsonArray()) {
            if (candidate.isJsonPrimitive() && candidate.getAsJsonPrimitive().isString()) {
                String token = candidate.getAsString();
                if (LowerCaseEnumTypeAdapter.fromJson(PluginHookPoint.class, token) == null) {
                    throw new IOException("Unknown plugin hook point: " + token);
                }
            }
        }
    }

    /// Rejects unknown string patch types before enum deserialization loses the source token.
    ///
    /// Other malformed patch representations remain the responsibility of normal manifest validation.
    ///
    /// @param root parsed manifest root
    /// @throws IOException if a patch type string does not identify a supported callback position
    private static void requireKnownPatchTypeTokens(JsonObject root) throws IOException {
        @Nullable JsonElement patchesValue = root.get("patches");
        if (patchesValue == null || !patchesValue.isJsonArray()) {
            return;
        }
        for (JsonElement candidate : patchesValue.getAsJsonArray()) {
            if (!candidate.isJsonObject()) {
                continue;
            }
            @Nullable JsonElement typeValue = candidate.getAsJsonObject().get("type");
            if (typeValue != null && typeValue.isJsonPrimitive() && typeValue.getAsJsonPrimitive().isString()) {
                String token = typeValue.getAsString();
                if (LowerCaseEnumTypeAdapter.fromJson(PluginPatchDeclaration.PatchType.class, token) == null) {
                    throw new IOException("Unknown plugin patch type: " + token);
                }
            }
        }
    }

    /// Returns whether a nullable string is a structurally valid plugin ID.
    ///
    /// @param value candidate ID
    /// @return whether the ID is valid
    static boolean isValidId(@Nullable String value) {
        return value != null && ID_PATTERN.matcher(value).matches();
    }

    /// Returns whether an ID has one portable canonical spelling suitable for executable schema-v4 artifacts.
    ///
    /// Lower-case spelling prevents case-folding collisions, while trailing dots and Windows device basenames are
    /// rejected so package files and content-addressed cache directories never alias on Windows.
    ///
    /// @param value candidate plugin ID
    /// @return whether the ID is portable and canonical
    public static boolean isCanonicalExecutableId(@Nullable String value) {
        if (!isValidId(value)
                || !value.equals(value.toLowerCase(Locale.ROOT))
                || value.endsWith(".")) {
            return false;
        }
        int dot = value.indexOf('.');
        String basename = dot < 0 ? value : value.substring(0, dot);
        return !WINDOWS_DEVICE_NAMES.contains(basename);
    }

    /// Requires a JSON string field to contain non-whitespace text.
    ///
    /// @param value field value
    /// @param fieldName field name used in diagnostics
    /// @throws IOException if the field is missing or blank
    private static void requireNonBlank(@Nullable String value, String fieldName) throws IOException {
        if (value == null || value.isBlank()) {
            throw new IOException("Missing or blank plugin " + fieldName);
        }
    }

    /// Requires an optional icon to identify exactly one resource inside the package archive.
    ///
    /// @param value declared icon path, or `null` when the package uses the launcher fallback
    /// @throws IOException if a non-null path is blank, absolute, or uses unsafe path syntax
    private static void requireValidIconResource(@Nullable String value) throws IOException {
        if (value == null) {
            return;
        }
        if (value.isBlank()
                || value.startsWith("/")
                || value.contains("\\")
                || value.contains(":")) {
            throw new IOException("Invalid plugin icon resource: " + value);
        }
        String[] components = value.split("/", -1);
        for (String component : components) {
            if (component.isEmpty() || component.equals(".") || component.equals("..")) {
                throw new IOException("Invalid plugin icon resource: " + value);
            }
        }
    }

    /// Requires a schema-v4 launcher constraint to be present and accepted by the shared version parser.
    ///
    /// @param value serialized launcher constraint
    /// @throws IOException if the value is missing, blank, or malformed
    private static void requireValidLauncherVersionConstraint(@Nullable String value) throws IOException {
        requireNonBlank(value, "launcherVersion");
        try {
            PluginVersionConstraint.parse(Objects.requireNonNull(value));
        } catch (IllegalArgumentException exception) {
            throw new IOException("Invalid plugin launcherVersion constraint: " + value, exception);
        }
    }

    /// Validates one legacy minimum launcher version through the shared constraint parser.
    ///
    /// @param value legacy minimum launcher version
    /// @throws IOException if the minimum cannot form a valid `>=` constraint
    private static void requireValidLegacyLauncherMinimum(String value) throws IOException {
        try {
            PluginVersionConstraint.parse(">=" + value);
        } catch (IllegalArgumentException exception) {
            throw new IOException("Invalid plugin minLauncherVersion: " + value, exception);
        }
    }

    /// Supported plugin runtime implementations.
    @NotNullByDefault
    public enum PluginType {
        /// Java bytecode plugin loaded from one or more JAR files.
        @SerializedName("java")
        JAVA,

        /// Kotlin bytecode plugin loaded through the Java plugin loader.
        @SerializedName("kotlin")
        KOTLIN
    }
}
