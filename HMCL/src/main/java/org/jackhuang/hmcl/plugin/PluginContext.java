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
import org.jackhuang.hmcl.plugin.bridge.PluginCapabilitySession;
import org.jackhuang.hmcl.plugin.bridge.PluginCapabilityToken;
import org.jackhuang.hmcl.plugin.bridge.PluginPermissionAuthority;
import org.jackhuang.hmcl.plugin.runtime.RuntimeProvider;
import org.jackhuang.hmcl.plugin.runtime.RuntimeProviderDescriptor;
import org.jackhuang.hmcl.plugin.runtime.RuntimeProviderRegistration;
import org.jackhuang.hmcl.ui.Controllers;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
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

    /// Host-bound callback which publishes one manifest-validated runtime Provider registration.
    private final Function<RuntimeProvider, RuntimeProviderRegistration> runtimeProviderRegistrar;

    /// Optional launcher-owned authority used by manager-created contexts.
    private final @Nullable PluginPermissionAuthority permissionAuthority;

    /// Exact package identity bound to tokens issued by this context.
    private final @Nullable PluginArtifactIdentity artifactIdentity;

    /// Optional external-payload capability session owned by this exact loaded lifecycle.
    private final @Nullable PluginCapabilitySession capabilitySession;

    /// Runtime Provider registrations owned by this exact Host context in registration order.
    private final List<RuntimeProviderRegistration> runtimeProviderRegistrations = new ArrayList<>();

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
        this(manifest, packageDirectory, dataDirectory, classLoader, "", Set::of, provider -> {
            throw new IllegalStateException("Runtime Provider registration requires a manager-owned plugin context");
        }, null, null);
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
        this(manifest, packageDirectory, dataDirectory, classLoader, artifactSha256,
                grantedPermissionProvider, provider -> {
                    throw new IllegalStateException(
                            "Runtime Provider registration requires a Supervisor-enabled plugin manager");
                }, null, null);
    }

    /// Creates a manager-owned context with dynamic permissions and Host-bound Provider registration.
    ///
    /// @param manifest package manifest
    /// @param packageDirectory extracted package directory
    /// @param dataDirectory persistent plugin data directory
    /// @param classLoader plugin class loader
    /// @param artifactSha256 exact `.npl` package digest
    /// @param grantedPermissionProvider dynamic user-grant provider
    /// @param runtimeProviderRegistrar Host-bound Provider registration callback
    PluginContext(
            PluginManifest manifest,
            Path packageDirectory,
            Path dataDirectory,
            ClassLoader classLoader,
            String artifactSha256,
            Supplier<@Unmodifiable Set<PluginPermission>> grantedPermissionProvider,
            Function<RuntimeProvider, RuntimeProviderRegistration> runtimeProviderRegistrar
    ) {
        this(
                manifest,
                packageDirectory,
                dataDirectory,
                classLoader,
                artifactSha256,
                grantedPermissionProvider,
                runtimeProviderRegistrar,
                null,
                null
        );
    }

    /// Creates a manager-owned context with token issuance bound to this exact artifact.
    ///
    /// @param manifest package manifest
    /// @param packageDirectory extracted package directory
    /// @param dataDirectory persistent plugin data directory
    /// @param classLoader plugin class loader
    /// @param artifactSha256 exact `.npl` package digest
    /// @param grantedPermissionProvider dynamic user-grant provider
    /// @param runtimeProviderRegistrar Host-bound Provider registration callback
    /// @param permissionAuthority launcher-owned capability authority
    PluginContext(
            PluginManifest manifest,
            Path packageDirectory,
            Path dataDirectory,
            ClassLoader classLoader,
            String artifactSha256,
            Supplier<@Unmodifiable Set<PluginPermission>> grantedPermissionProvider,
            Function<RuntimeProvider, RuntimeProviderRegistration> runtimeProviderRegistrar,
            @Nullable PluginPermissionAuthority permissionAuthority
    ) {
        this(
                manifest,
                packageDirectory,
                dataDirectory,
                classLoader,
                artifactSha256,
                grantedPermissionProvider,
                runtimeProviderRegistrar,
                permissionAuthority,
                null
        );
    }

    /// Creates a manager-owned context with optional standalone and external-payload capability ownership.
    ///
    /// @param manifest package manifest
    /// @param packageDirectory extracted package directory
    /// @param dataDirectory persistent plugin data directory
    /// @param classLoader plugin class loader
    /// @param artifactSha256 exact `.npl` package digest
    /// @param grantedPermissionProvider dynamic user-grant provider
    /// @param runtimeProviderRegistrar Host-bound Provider registration callback
    /// @param permissionAuthority optional authority for standalone JVM-context token issuance
    /// @param capabilitySession optional external-payload lifecycle session
    PluginContext(
            PluginManifest manifest,
            Path packageDirectory,
            Path dataDirectory,
            ClassLoader classLoader,
            String artifactSha256,
            Supplier<@Unmodifiable Set<PluginPermission>> grantedPermissionProvider,
            Function<RuntimeProvider, RuntimeProviderRegistration> runtimeProviderRegistrar,
            @Nullable PluginPermissionAuthority permissionAuthority,
            @Nullable PluginCapabilitySession capabilitySession
    ) {
        this.manifest = manifest;
        this.packageDirectory = packageDirectory;
        this.dataDirectory = dataDirectory;
        this.classLoader = classLoader;
        this.artifactSha256 = artifactSha256;
        this.grantedPermissionProvider = grantedPermissionProvider;
        this.runtimeProviderRegistrar = runtimeProviderRegistrar;
        this.permissionAuthority = permissionAuthority;
        this.capabilitySession = capabilitySession;
        this.artifactIdentity = permissionAuthority == null
                ? null
                : new PluginArtifactIdentity(manifest.getId(), manifest.getVersion(), artifactSha256);
    }

    /// Issues a short-lived token from this exact artifact's current effective grants.
    ///
    /// Tokens issued through this context are all revoked together during lifecycle teardown.
    ///
    /// @param callbackDomain exact callback domain
    /// @param lifetime positive token lifetime
    /// @return opaque artifact-bound token
    PluginCapabilityToken issueCapabilityToken(String callbackDomain, Duration lifetime) {
        @Nullable PluginPermissionAuthority authority = permissionAuthority;
        @Nullable PluginArtifactIdentity identity = artifactIdentity;
        if (authority == null || identity == null) {
            throw new IllegalStateException("Capability tokens require a manager-owned plugin context");
        }
        return authority.issue(
                identity,
                manifest.getExecutionMode(),
                getGrantedPermissions(),
                callbackDomain,
                lifetime
        );
    }

    /// Issues one token from this external payload lifecycle's current active session generation.
    ///
    /// @return opaque plugin-scoped runtime payload token
    /// @throws IllegalStateException if this is not an active external payload context
    PluginCapabilityToken issueRuntimeCapabilityToken() {
        @Nullable PluginCapabilitySession session = capabilitySession;
        if (session == null) {
            throw new IllegalStateException("Runtime capability tokens require an external payload session");
        }
        return session.issue();
    }

    /// Revokes every token issued for this context's exact package artifact.
    void revokeCapabilityTokens() {
        @Nullable PluginPermissionAuthority authority = permissionAuthority;
        @Nullable PluginArtifactIdentity identity = artifactIdentity;
        if (authority != null && identity != null) {
            authority.revokeArtifact(identity);
        }
    }

    /// Resumes external payload capability issuance in a fresh generation when currently suspended.
    void resumeCapabilitySession() {
        @Nullable PluginCapabilitySession session = capabilitySession;
        if (session != null) {
            session.resume();
        }
    }

    /// Suspends external payload capability issuance and revokes its current generation.
    void suspendCapabilitySession() {
        @Nullable PluginCapabilitySession session = capabilitySession;
        if (session != null) {
            session.suspend();
        }
    }

    /// Rotates the external payload capability generation after an effective permission change.
    void rotateCapabilitySession() {
        @Nullable PluginCapabilitySession session = capabilitySession;
        if (session != null) {
            session.rotate();
        }
    }

    /// Permanently closes external payload capability issuance before lifecycle unloading.
    void closeCapabilitySession() {
        @Nullable PluginCapabilitySession session = capabilitySession;
        if (session != null) {
            session.close();
        }
    }

    /// Registers one external runtime Provider whose descriptor exactly matches this Host manifest.
    ///
    /// The returned handle belongs to this context and is closed automatically when its Host container unloads.
    ///
    /// @param provider Provider implementation created by this Host
    /// @return Host-owned registration handle
    public synchronized RuntimeProviderRegistration registerRuntimeProvider(RuntimeProvider provider) {
        if (manifest.getPluginKind() != PluginKind.RUNTIME_PROVIDER) {
            throw new IllegalStateException("Only a runtime-provider Host may register a runtime Provider: "
                    + manifest.getId());
        }
        RuntimeProviderDescriptor descriptor = provider.descriptor();
        if (!manifest.getId().equals(descriptor.providerId())) {
            throw new IllegalArgumentException("Runtime Provider descriptor ID does not match its Host manifest: "
                    + descriptor.providerId());
        }
        if (!manifest.getVersion().equals(descriptor.version())) {
            throw new IllegalArgumentException("Runtime Provider descriptor version does not match its Host manifest: "
                    + descriptor.version());
        }
        if (!manifest.getProvidesRuntimes().equals(List.copyOf(descriptor.capabilities().values()))) {
            throw new IllegalArgumentException("Runtime Provider descriptor capabilities do not match its Host manifest: "
                    + manifest.getId());
        }
        if (!descriptor.installed() || !descriptor.enabled() || descriptor.reserved()) {
            throw new IllegalArgumentException("External runtime Provider descriptor must be installed and enabled: "
                    + manifest.getId());
        }
        RuntimeProviderRegistration registration = runtimeProviderRegistrar.apply(provider);
        runtimeProviderRegistrations.add(registration);
        return registration;
    }

    /// Closes every Host-owned runtime Provider registration in reverse registration order.
    ///
    /// @throws IOException if Provider or dependent payload cleanup fails
    synchronized void closeRuntimeProviderRegistrations() throws IOException {
        @Nullable IOException failure = null;
        for (int index = runtimeProviderRegistrations.size() - 1; index >= 0; index--) {
            try {
                runtimeProviderRegistrations.get(index).close();
                runtimeProviderRegistrations.remove(index);
            } catch (IOException exception) {
                if (failure == null) {
                    failure = exception;
                } else {
                    failure.addSuppressed(exception);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
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
