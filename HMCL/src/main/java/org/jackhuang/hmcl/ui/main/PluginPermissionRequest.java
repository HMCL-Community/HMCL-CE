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
package org.jackhuang.hmcl.ui.main;

import org.jackhuang.hmcl.plugin.PluginPermission;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/// Describes one plugin artifact whose requested permissions can be reviewed during installation.
@NotNullByDefault
final class PluginPermissionRequest {
    /// Stable plugin ID used to associate the user's choices with the matching package.
    private final String pluginId;

    /// Human-readable plugin name displayed above its permission rows.
    private final String displayName;

    /// Exact plugin version whose requested permissions are being reviewed.
    private final String version;

    /// Immutable permissions required for this exact plugin version to run.
    private final @Unmodifiable List<PluginPermission> requiredPermissions;

    /// Immutable permissions whose related features may be disabled by the user.
    private final @Unmodifiable List<PluginPermission> optionalPermissions;

    /// Immutable initial grants, always restricted to declared permissions.
    private final @Unmodifiable Set<PluginPermission> initiallyGrantedPermissions;

    /// Whether the installation dialog lets the user change this plugin's grants.
    private final boolean editable;

    /// Whether this request belongs to an update rather than a first installation.
    private final boolean update;

    /// Required permissions not required by the installed version, including optional-to-required promotions.
    private final @Unmodifiable Set<PluginPermission> newlyRequiredPermissions;

    /// Optional permissions representing capabilities absent from the installed version.
    private final @Unmodifiable Set<PluginPermission> newlyOptionalPermissions;

    /// Creates one permission request after validating its classification and normalizing initial grants.
    ///
    /// @param pluginId stable plugin ID
    /// @param displayName human-readable plugin name
    /// @param version exact plugin version
    /// @param requiredPermissions permissions required by the developer
    /// @param optionalPermissions permissions the user may deny while keeping the plugin runnable
    /// @param initiallyGrantedPermissions permissions selected when the dialog opens
    /// @param editable whether the user may change this request
    /// @param update whether this request replaces an installed artifact
    /// @param previousRequiredPermissions permissions required by the installed artifact
    /// @param previousOptionalPermissions optional permissions declared by the installed artifact
    PluginPermissionRequest(
            String pluginId,
            String displayName,
            String version,
            List<PluginPermission> requiredPermissions,
            List<PluginPermission> optionalPermissions,
            Set<PluginPermission> initiallyGrantedPermissions,
            boolean editable,
            boolean update,
            List<PluginPermission> previousRequiredPermissions,
            List<PluginPermission> previousOptionalPermissions
    ) {
        this.pluginId = pluginId;
        this.displayName = displayName;
        this.version = version;
        this.requiredPermissions = List.copyOf(requiredPermissions);
        this.optionalPermissions = List.copyOf(optionalPermissions);
        validateDisjointPermissions(this.requiredPermissions, this.optionalPermissions);

        LinkedHashSet<PluginPermission> normalizedGrants = new LinkedHashSet<>(this.requiredPermissions);
        initiallyGrantedPermissions.stream()
                .filter(this.optionalPermissions::contains)
                .forEach(normalizedGrants::add);
        this.initiallyGrantedPermissions = Set.copyOf(normalizedGrants);
        this.editable = editable;
        this.update = update;
        this.newlyRequiredPermissions = update
                ? this.requiredPermissions.stream()
                .filter(permission -> !previousRequiredPermissions.contains(permission))
                .collect(Collectors.toUnmodifiableSet())
                : Set.of();
        Set<PluginPermission> previouslyDeclared = new LinkedHashSet<>(previousRequiredPermissions);
        previouslyDeclared.addAll(previousOptionalPermissions);
        this.newlyOptionalPermissions = update
                ? this.optionalPermissions.stream()
                .filter(permission -> !previouslyDeclared.contains(permission))
                .collect(Collectors.toUnmodifiableSet())
                : Set.of();
    }

    /// Rejects ambiguous permission declarations before they reach an interactive grant form.
    ///
    /// @param requiredPermissions required permission list
    /// @param optionalPermissions optional permission list
    private static void validateDisjointPermissions(
            List<PluginPermission> requiredPermissions,
            List<PluginPermission> optionalPermissions
    ) {
        Set<PluginPermission> declared = new LinkedHashSet<>();
        for (PluginPermission permission : requiredPermissions) {
            if (!declared.add(permission)) {
                throw new IllegalArgumentException("Duplicate required permission: " + permission.getId());
            }
        }
        for (PluginPermission permission : optionalPermissions) {
            if (!declared.add(permission)) {
                throw new IllegalArgumentException("Permission cannot be both required and optional: "
                        + permission.getId());
            }
        }
    }

    /// Returns the stable plugin ID.
    ///
    /// @return stable plugin ID
    String getPluginId() {
        return pluginId;
    }

    /// Returns the human-readable plugin name.
    ///
    /// @return plugin display name
    String getDisplayName() {
        return displayName;
    }

    /// Returns the exact version covered by this request.
    ///
    /// @return plugin version
    String getVersion() {
        return version;
    }

    /// Returns all permissions requested by the developer in required-first order.
    ///
    /// @return declared permissions
    @Unmodifiable List<PluginPermission> getDeclaredPermissions() {
        LinkedHashSet<PluginPermission> declared = new LinkedHashSet<>(requiredPermissions);
        declared.addAll(optionalPermissions);
        return List.copyOf(declared);
    }

    /// Returns permissions that must be granted for this artifact to run.
    ///
    /// @return immutable required permission list
    @Unmodifiable List<PluginPermission> getRequiredPermissions() {
        return requiredPermissions;
    }

    /// Returns permissions the user may deny without blocking ordinary plugin execution.
    ///
    /// @return immutable optional permission list
    @Unmodifiable List<PluginPermission> getOptionalPermissions() {
        return optionalPermissions;
    }

    /// Returns the immutable grants selected when the dialog opens.
    ///
    /// @return initially granted permissions
    @Unmodifiable Set<PluginPermission> getInitiallyGrantedPermissions() {
        return initiallyGrantedPermissions;
    }

    /// Returns whether the user may change this request.
    ///
    /// @return whether permission switches are editable
    boolean isEditable() {
        return editable;
    }

    /// Returns whether this request requires a fresh update authorization decision.
    ///
    /// @return `true` for an update, or `false` for a first installation
    boolean isUpdate() {
        return update;
    }

    /// Returns permissions newly required by the target version.
    ///
    /// An optional-to-required promotion is intentionally included because it changes the user's ability to deny
    /// the permission while keeping the plugin enabled.
    ///
    /// @return immutable newly required permission set
    @Unmodifiable Set<PluginPermission> getNewlyRequiredPermissions() {
        return newlyRequiredPermissions;
    }

    /// Returns newly introduced optional capabilities for the target version.
    ///
    /// Required-to-optional reclassification is excluded because it does not introduce a new capability.
    ///
    /// @return immutable newly optional permission set
    @Unmodifiable Set<PluginPermission> getNewlyOptionalPermissions() {
        return newlyOptionalPermissions;
    }
}
