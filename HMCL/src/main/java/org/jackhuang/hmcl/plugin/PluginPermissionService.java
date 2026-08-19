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
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/// Applies declaration validation and artifact-binding policy around persisted plugin permission decisions.
@NotNullByDefault
final class PluginPermissionService {
    /// Persistent artifact-bound permission decisions.
    private final PluginPermissionStore store;

    /// Resolver for the currently loaded or installed artifact of a plugin ID.
    private final CurrentArtifactResolver currentArtifactResolver;

    /// Creates a permission service.
    ///
    /// @param permissionFile private atomic permission document
    /// @param currentArtifactResolver resolver for active or installed package identity
    PluginPermissionService(
            Path permissionFile,
            CurrentArtifactResolver currentArtifactResolver,
            PluginMutationLock mutationLock
    ) {
        store = new PluginPermissionStore(permissionFile, mutationLock);
        this.currentArtifactResolver = currentArtifactResolver;
    }

    /// Returns developer-requested capabilities for the current artifact of a plugin ID.
    ///
    /// @param pluginId plugin ID to query
    /// @return immutable declared permission set
    /// @throws IOException if the plugin is absent or cannot be inspected
    @Unmodifiable Set<PluginPermission> getDeclaredPermissions(String pluginId) throws IOException {
        return immutablePermissions(requireCurrentArtifact(pluginId).manifest.getPermissions());
    }

    /// Returns effective user grants for the current artifact of a plugin ID.
    ///
    /// @param pluginId plugin ID to query
    /// @return immutable effective permission set
    /// @throws IOException if the plugin is absent or cannot be inspected
    @Unmodifiable Set<PluginPermission> getGrantedPermissions(String pluginId) throws IOException {
        ResolvedArtifact resolved = requireCurrentArtifact(pluginId);
        return getGrantedPermissions(resolved.manifest, resolved.artifact);
    }

    /// Replaces user grants for the current artifact of a plugin ID.
    ///
    /// @param pluginId plugin ID to update
    /// @param grantedPermissions explicit user grants
    /// @throws IOException if the plugin is absent or persistence fails
    void setGrantedPermissions(String pluginId, Set<PluginPermission> grantedPermissions) throws IOException {
        ResolvedArtifact resolved = requireCurrentArtifact(pluginId);
        store.setGrantedPermissions(
                resolved.artifact,
                normalizeGrantedPermissions(resolved.manifest, grantedPermissions)
        );
    }

    /// Returns effective grants for one exact manifest and package digest.
    ///
    /// @param manifest exact artifact manifest
    /// @param sha256 full package digest
    /// @return immutable effective grant set
    @Unmodifiable Set<PluginPermission> getGrantedPermissions(PluginManifest manifest, String sha256) {
        return getGrantedPermissions(manifest, artifact(manifest, sha256));
    }

    /// Suggests initial permission toggle values for one installation prompt.
    ///
    /// New schema-v4 plugin IDs start with their required permissions granted and every optional permission denied.
    /// Every update preserves only optional permissions that were explicitly granted to the currently installed
    /// artifact and are still optional in the target, then adds the target's non-revocable required permissions. A
    /// target artifact's own historical record is never reused for prompt defaults.
    ///
    /// @param manifest inspected target manifest
    /// @param hasInstalledManifest whether inspection observed an existing same-ID plugin
    /// @return immutable suggested grant set
    /// @throws IOException if the current artifact cannot be inspected
    @Unmodifiable Set<PluginPermission> getSuggestedGrantedPermissions(
            PluginManifest manifest,
            boolean hasInstalledManifest
    ) throws IOException {
        if (!hasInstalledManifest) {
            return requiredDefaults(manifest);
        }

        @Nullable ResolvedArtifact current = currentArtifactResolver.resolve(manifest.getId());
        if (current == null) {
            return requiredDefaults(manifest);
        }

        EnumSet<PluginPermission> suggested = EnumSet.noneOf(PluginPermission.class);
        suggested.addAll(store.getGrantedPermissions(current.artifact));
        if (current.manifest.getSchemaVersion() >= 4) {
            suggested.removeAll(current.manifest.getRequiredPermissions());
        }
        suggested.retainAll(manifest.getOptionalPermissions());
        if (manifest.getSchemaVersion() >= 4) {
            suggested.addAll(manifest.getRequiredPermissions());
        }
        return immutablePermissions(suggested);
    }

    /// Captures the complete permission state for an installation transaction.
    ///
    /// @return immutable permission snapshot
    PluginPermissionStore.Snapshot snapshot() throws IOException {
        return store.snapshot();
    }

    /// Reloads permission decisions after transaction recovery restores the old document.
    ///
    /// @throws IOException if the shared lock cannot be acquired
    void reload() throws IOException {
        store.reload();
    }

    /// Persists explicit grants for one exact artifact after validating its manifest requests.
    ///
    /// @param manifest target artifact manifest
    /// @param sha256 target package digest
    /// @param grantedPermissions explicit user grants
    /// @throws IOException if persistence fails
    void setGrantedPermissions(
            PluginManifest manifest,
            String sha256,
            Set<PluginPermission> grantedPermissions
    ) throws IOException {
        store.setGrantedPermissions(
                artifact(manifest, sha256),
                normalizeGrantedPermissions(manifest, grantedPermissions)
        );
    }

    /// Atomically persists normalized decisions for multiple resolved artifacts.
    ///
    /// @param decisions user decisions indexed by exact resolved artifact
    /// @throws IOException if persistence fails
    void setGrantedPermissions(
            Map<ResolvedArtifact, Set<PluginPermission>> decisions
    ) throws IOException {
        Map<PluginPermissionStore.Artifact, @Unmodifiable Set<PluginPermission>> normalized =
                new LinkedHashMap<>();
        decisions.forEach((resolved, permissions) -> normalized.put(
                resolved.artifact,
                normalizeGrantedPermissions(resolved.manifest, permissions)
        ));
        store.setGrantedPermissions(Map.copyOf(normalized));
    }

    /// Restores a permission snapshot and attaches restoration failure to the root transaction error.
    ///
    /// @param snapshot state captured before the transaction
    /// @param failure root transaction failure
    void restore(PluginPermissionStore.Snapshot snapshot, Throwable failure) {
        try {
            store.restore(snapshot);
        } catch (IOException rollbackException) {
            failure.addSuppressed(rollbackException);
        }
    }

    /// Removes every decision belonging to one plugin ID.
    ///
    /// @param pluginId plugin ID to remove
    /// @throws IOException if persistence fails
    void removePlugin(String pluginId) throws IOException {
        store.removePlugin(pluginId);
    }

    /// Removes decisions not belonging to an installed or loaded package artifact.
    ///
    /// @param retainedArtifacts exact artifacts allowed to keep decisions
    /// @throws IOException if persistence fails
    void retainArtifacts(Set<PluginPermissionStore.Artifact> retainedArtifacts) throws IOException {
        store.retainArtifacts(retainedArtifacts);
    }

    /// Creates an exact permission identity for one validated package artifact.
    ///
    /// @param manifest artifact manifest
    /// @param sha256 full package digest
    /// @return exact artifact identity
    PluginPermissionStore.Artifact artifact(PluginManifest manifest, String sha256) {
        return new PluginPermissionStore.Artifact(manifest.getId(), manifest.getVersion(), sha256);
    }

    /// Returns effective grants after intersecting stored decisions with developer requests.
    ///
    /// No permission is synthesized while reading. This keeps manually dropped packages and missing, damaged, or
    /// edited permission documents fail-closed before plugin code executes.
    ///
    /// @param manifest artifact manifest
    /// @param artifact exact artifact identity
    /// @return immutable effective grant set
    private @Unmodifiable Set<PluginPermission> getGrantedPermissions(
            PluginManifest manifest,
            PluginPermissionStore.Artifact artifact
    ) {
        EnumSet<PluginPermission> effective = EnumSet.noneOf(PluginPermission.class);
        effective.addAll(store.getGrantedPermissions(artifact));
        effective.retainAll(manifest.getPermissions());
        return immutablePermissions(effective);
    }

    /// Resolves an installed artifact or reports that the plugin is absent.
    ///
    /// @param pluginId plugin ID to resolve
    /// @return resolved current artifact
    /// @throws IOException if the plugin is absent or cannot be inspected
    private ResolvedArtifact requireCurrentArtifact(String pluginId) throws IOException {
        @Nullable ResolvedArtifact resolved = currentArtifactResolver.resolve(pluginId);
        if (resolved == null) {
            throw new IOException("Plugin is not installed: " + pluginId);
        }
        return resolved;
    }

    /// Validates that a decision contains only capabilities requested by the developer and contains every schema-v4
    /// required permission. Missing required grants are rejected instead of synthesized so authorization always
    /// originates from an explicit artifact-bound confirmation.
    ///
    /// @param manifest target artifact manifest
    /// @param grantedPermissions caller-supplied decision
    /// @return immutable normalized grant set
    private static @Unmodifiable Set<PluginPermission> normalizeGrantedPermissions(
            PluginManifest manifest,
            Set<PluginPermission> grantedPermissions
    ) {
        Objects.requireNonNull(grantedPermissions, "Granted permissions");
        EnumSet<PluginPermission> normalized = EnumSet.noneOf(PluginPermission.class);
        for (@Nullable PluginPermission permission : grantedPermissions) {
            if (permission == null) {
                throw new IllegalArgumentException("Granted permissions cannot contain null");
            }
            if (!manifest.declaresPermission(permission)) {
                throw new IllegalArgumentException("Plugin " + manifest.getId()
                        + " did not declare permission " + permission.getId());
            }
            normalized.add(permission);
        }
        if (manifest.getSchemaVersion() >= 4
                && !normalized.containsAll(manifest.getRequiredPermissions())) {
            EnumSet<PluginPermission> missing = EnumSet.noneOf(PluginPermission.class);
            missing.addAll(manifest.getRequiredPermissions());
            missing.removeAll(normalized);
            throw new IllegalArgumentException("Plugin " + manifest.getId()
                    + " required permissions cannot be revoked: "
                    + missing.stream().map(PluginPermission::getId).sorted().toList());
        }
        return immutablePermissions(normalized);
    }

    /// Returns the non-revocable default grants for a newly inspected artifact.
    ///
    /// @param manifest target artifact manifest
    /// @return immutable schema-v4 required grants or an empty schema-v3 decision
    private static @Unmodifiable Set<PluginPermission> requiredDefaults(PluginManifest manifest) {
        return manifest.getSchemaVersion() >= 4
                ? immutablePermissions(manifest.getRequiredPermissions())
                : Set.of();
    }

    /// Returns an immutable enum-set copy preserving stable enum order.
    ///
    /// @param permissions source permissions
    /// @return immutable permission set
    private static @Unmodifiable Set<PluginPermission> immutablePermissions(
            Iterable<PluginPermission> permissions
    ) {
        EnumSet<PluginPermission> copy = EnumSet.noneOf(PluginPermission.class);
        permissions.forEach(copy::add);
        return copy.isEmpty() ? Set.of() : Collections.unmodifiableSet(copy);
    }

    /// Resolves the current exact artifact for a plugin ID.
    @FunctionalInterface
    @NotNullByDefault
    interface CurrentArtifactResolver {
        /// Resolves one loaded or installed plugin artifact.
        ///
        /// @param pluginId plugin ID to resolve
        /// @return resolved artifact or `null` when absent
        /// @throws IOException if installed package inspection fails
        @Nullable ResolvedArtifact resolve(String pluginId) throws IOException;
    }

    /// Pairs a validated manifest with its exact artifact-bound permission key.
    @NotNullByDefault
    static final class ResolvedArtifact {
        /// Manifest belonging to the resolved artifact.
        private final PluginManifest manifest;

        /// Exact artifact-bound permission key.
        private final PluginPermissionStore.Artifact artifact;

        /// Creates a resolved artifact.
        ///
        /// @param manifest validated artifact manifest
        /// @param artifact exact artifact-bound permission key
        ResolvedArtifact(PluginManifest manifest, PluginPermissionStore.Artifact artifact) {
            this.manifest = manifest;
            this.artifact = artifact;
        }

        /// Returns the resolved artifact manifest.
        ///
        /// @return artifact manifest
        PluginManifest getManifest() {
            return manifest;
        }

        /// Returns the exact permission-store artifact key.
        ///
        /// @return artifact key
        PluginPermissionStore.Artifact getArtifact() {
            return artifact;
        }
    }
}
