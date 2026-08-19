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

import org.jetbrains.annotations.NotNullByDefault;

import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Set;

/// Applies the persisted, artifact-bound permission policy before any plugin Mixin can run.
///
/// This guard deliberately reuses the regular permission store so startup and runtime decisions interpret the same
/// fail-closed document schema. Only API-v4 artifacts are eligible, and they require every effective required grant
/// while denied optional requests remain unavailable without blocking startup.
@NotNullByDefault
public final class PluginMixinPermissionGuard {
    /// Immutable snapshot of persisted permission decisions loaded for the current startup.
    private final PluginPermissionStore store;

    /// Loads startup permission decisions from the launcher's private permission document.
    ///
    /// Missing or malformed documents yield no grants through [PluginPermissionStore].
    ///
    /// @param permissionFile private `plugin-permissions.json` path
    public PluginMixinPermissionGuard(Path permissionFile) {
        store = new PluginPermissionStore(permissionFile);
    }

    /// Returns whether one exact package may contribute startup-time Mixin transformations.
    ///
    /// @param manifest validated package manifest
    /// @param packageSha256 lower-case SHA-256 digest of the complete `.npl` package
    /// @return whether the exact executable artifact may contribute Mixin transformations
    public boolean isGranted(PluginManifest manifest, String packageSha256) {
        if (manifest.getSchemaVersion() < PluginManifest.MIN_EXECUTABLE_SCHEMA_VERSION
                || !manifest.hasMixins()
                || !manifest.isPermissionRequired(PluginPermission.MIXIN)) {
            return false;
        }

        return hasRequiredPermissions(manifest, packageSha256);
    }

    /// Returns whether one exact executable artifact has every effective required permission.
    ///
    /// Every API-v4 artifact relies on exact-artifact stored grants and its explicit required subset. Earlier schema
    /// versions, missing decisions, and damaged permission state remain fail-closed during premain.
    ///
    /// @param manifest validated package manifest
    /// @param packageSha256 lower-case SHA-256 digest of the complete `.npl` package
    /// @return whether every effective required permission is available
    public boolean hasRequiredPermissions(PluginManifest manifest, String packageSha256) {
        if (manifest.getSchemaVersion() < PluginManifest.MIN_EXECUTABLE_SCHEMA_VERSION) {
            return false;
        }

        PluginPermissionStore.Artifact artifact = new PluginPermissionStore.Artifact(
                manifest.getId(),
                manifest.getVersion(),
                packageSha256
        );
        EnumSet<PluginPermission> effective = EnumSet.noneOf(PluginPermission.class);
        effective.addAll(store.getGrantedPermissions(artifact));
        effective.retainAll(manifest.getPermissions());
        Set<PluginPermission> required = Set.copyOf(manifest.getRequiredPermissions());
        return effective.containsAll(required);
    }
}
