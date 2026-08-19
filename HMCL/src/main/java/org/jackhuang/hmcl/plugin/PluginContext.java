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

import javafx.scene.Node;
import javafx.stage.Stage;
import org.jackhuang.hmcl.Metadata;
import org.jackhuang.hmcl.ui.Controllers;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.nio.file.Path;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/// Exposes package metadata, storage locations, class loading, and UI registration to one plugin.
@NotNullByDefault
public final class PluginContext {
    /// Authoritative package manifest.
    private final PluginManifest manifest;

    /// Directory containing extracted package resources.
    private final Path packageDirectory;

    /// Persistent private storage directory for this plugin ID.
    private final Path dataDirectory;

    /// Class loader that defined the plugin lifecycle implementation.
    private final ClassLoader classLoader;

    /// Exact package SHA-256 used by the manager's artifact-bound permission lookup.
    private final String artifactSha256;

    /// Dynamic source of user grants for the exact package artifact.
    private final Supplier<@Unmodifiable Set<PluginPermission>> grantedPermissionProvider;

    /// Creates a plugin context.
    ///
    /// @param manifest package manifest
    /// @param packageDirectory extracted package directory
    /// @param dataDirectory persistent plugin data directory
    /// @param classLoader plugin class loader
    public PluginContext(
            PluginManifest manifest,
            Path packageDirectory,
            Path dataDirectory,
            ClassLoader classLoader
    ) {
        this(manifest, packageDirectory, dataDirectory, classLoader, "", Set::of);
    }

    /// Creates a manager-owned context with dynamic artifact-bound permission decisions.
    ///
    /// @param manifest package manifest
    /// @param packageDirectory extracted package directory
    /// @param dataDirectory persistent plugin data directory
    /// @param classLoader plugin class loader
    /// @param artifactSha256 exact `.npl` package digest
    /// @param grantedPermissionProvider dynamic user-grant provider
    PluginContext(
            PluginManifest manifest,
            Path packageDirectory,
            Path dataDirectory,
            ClassLoader classLoader,
            String artifactSha256,
            Supplier<@Unmodifiable Set<PluginPermission>> grantedPermissionProvider
    ) {
        this.manifest = manifest;
        this.packageDirectory = packageDirectory;
        this.dataDirectory = dataDirectory;
        this.classLoader = classLoader;
        this.artifactSha256 = artifactSha256;
        this.grantedPermissionProvider = grantedPermissionProvider;
    }

    /// Returns the authoritative package manifest.
    ///
    /// @return plugin manifest
    public PluginManifest getManifest() {
        return manifest;
    }

    /// Returns the read-only extracted package directory containing bundled resources and libraries.
    ///
    /// The directory is content-addressed and may change after a plugin update. Plugins must not
    /// persist this path or write private state here; use [getDataDirectory] for persistent data.
    ///
    /// @return extracted package directory
    public Path getPluginDirectory() {
        return packageDirectory;
    }

    /// Returns the read-only extracted package directory containing bundled resources and libraries.
    ///
    /// The directory is content-addressed and may change after a plugin update. Plugins must not
    /// persist this path or write private state here; use [getDataDirectory] for persistent data.
    ///
    /// @return extracted package directory
    public Path getPackageDirectory() {
        return packageDirectory;
    }

    /// Returns the loader that defined the lifecycle implementation.
    ///
    /// @return plugin class loader
    public ClassLoader getClassLoader() {
        return classLoader;
    }

    /// Returns the current launcher version.
    ///
    /// @return launcher version
    public String getLauncherVersion() {
        return Metadata.VERSION;
    }

    /// Returns the primary JavaFX stage.
    ///
    /// @return primary stage
    public Stage getPrimaryStage() {
        requirePermission(PluginPermission.LAUNCHER_UI);
        return Controllers.getStage();
    }

    /// Returns HMCL's launcher-wide local data directory.
    ///
    /// @return launcher data directory
    public Path getLauncherDataDirectory() {
        requirePermission(PluginPermission.FILESYSTEM);
        return Metadata.HMCL_LOCAL_HOME;
    }

    /// Returns the persistent private data directory assigned to this plugin ID.
    ///
    /// @return plugin data directory
    public Path getDataDirectory() {
        return dataDirectory;
    }

    /// Returns the sensitive launcher capabilities declared by this plugin package.
    ///
    /// @return immutable declared permission list
    public @Unmodifiable List<PluginPermission> getPermissions() {
        return manifest.getPermissions();
    }

    /// Returns the capabilities requested by the developer in this exact package manifest.
    ///
    /// @return immutable declared permission set
    public @Unmodifiable Set<PluginPermission> getDeclaredPermissions() {
        return immutablePermissions(manifest.getPermissions());
    }

    /// Returns permissions that must remain effective for this plugin artifact to execute.
    ///
    /// @return immutable required permission set
    public @Unmodifiable Set<PluginPermission> getRequiredPermissions() {
        return immutablePermissions(manifest.getRequiredPermissions());
    }

    /// Returns declared permissions that the user may deny without blocking plugin lifecycle execution.
    ///
    /// @return immutable optional permission set
    public @Unmodifiable Set<PluginPermission> getOptionalPermissions() {
        return immutablePermissions(manifest.getOptionalPermissions());
    }

    /// Returns capabilities that are both declared by the package and currently granted by the user.
    ///
    /// The result is evaluated for every call, so permission changes take effect for subsequent official API calls.
    ///
    /// @return immutable effective permission set
    public @Unmodifiable Set<PluginPermission> getGrantedPermissions() {
        EnumSet<PluginPermission> effective = EnumSet.noneOf(PluginPermission.class);
        effective.addAll(grantedPermissionProvider.get());
        effective.retainAll(manifest.getPermissions());
        return immutablePermissions(effective);
    }

    /// Returns whether a declared capability is currently granted by the user.
    ///
    /// @param permission capability to query
    /// @return whether the capability is declared and granted
    public boolean isPermissionGranted(PluginPermission permission) {
        return manifest.declaresPermission(permission)
                && grantedPermissionProvider.get().contains(permission);
    }

    /// Requires a declared and currently granted capability for an official launcher operation.
    ///
    /// @param permission capability required by the operation
    /// @throws PluginPermissionException if the package did not declare or the user did not grant the capability
    public void requirePermission(PluginPermission permission) {
        if (!manifest.declaresPermission(permission)) {
            throw new PluginPermissionException(
                    manifest.getId(),
                    permission,
                    PluginPermissionException.Reason.NOT_DECLARED
            );
        }
        if (!grantedPermissionProvider.get().contains(permission)) {
            throw new PluginPermissionException(
                    manifest.getId(),
                    permission,
                    PluginPermissionException.Reason.USER_DENIED
            );
        }
    }

    /// Returns whether this plugin declared one sensitive launcher capability.
    ///
    /// @param permission capability to query
    /// @return whether the permission is declared
    public boolean declaresPermission(PluginPermission permission) {
        return manifest.declaresPermission(permission);
    }

    /// Returns whether one declared capability is required for this artifact to execute.
    ///
    /// @param permission capability to query
    /// @return whether the permission belongs to the manifest's effective required set
    public boolean isPermissionRequired(PluginPermission permission) {
        return manifest.isPermissionRequired(permission);
    }

    /// Returns the structured dependencies declared by this plugin package.
    ///
    /// @return immutable plugin dependency list
    public @Unmodifiable List<PluginDependency> getPluginDependencies() {
        return manifest.getPluginDependencies();
    }

    /// Returns whether this plugin declares a dependency on the supplied plugin ID.
    ///
    /// @param pluginId dependency ID to query
    /// @return whether the dependency is declared
    public boolean declaresDependency(String pluginId) {
        return manifest.getDependencies().contains(pluginId);
    }

    /// Registers a JavaFX sidebar action owned by this plugin.
    ///
    /// @param title displayed sidebar title
    /// @param onAction action invoked when the item is selected
    public void registerSidebarItem(String title, Runnable onAction) {
        requirePermission(PluginPermission.LAUNCHER_UI);
        PluginUIRegistry.registerSidebarItem(manifest.getId(), title, onAction);
    }

    /// Registers a lazy page that themes may render inside their own content area.
    ///
    /// @param title displayed sidebar title
    /// @param pageSupplier creates the page when selected
    public void registerSidebarPage(String title, Supplier<? extends Node> pageSupplier) {
        requirePermission(PluginPermission.LAUNCHER_UI);
        PluginUIRegistry.registerSidebarPage(manifest.getId(), title, pageSupplier);
    }

    /// Returns the exact package digest used for manager-internal permission lookup.
    ///
    /// @return package SHA-256 or an empty string for manually constructed compatibility contexts
    String getArtifactSha256() {
        return artifactSha256;
    }

    /// Returns an immutable enum set copy of the supplied permissions.
    ///
    /// @param permissions source permissions
    /// @return immutable permission set
    private static @Unmodifiable Set<PluginPermission> immutablePermissions(
            Iterable<PluginPermission> permissions
    ) {
        EnumSet<PluginPermission> copy = EnumSet.noneOf(PluginPermission.class);
        permissions.forEach(copy::add);
        if (copy.isEmpty()) {
            return Set.of();
        }
        return Collections.unmodifiableSet(copy);
    }
}
