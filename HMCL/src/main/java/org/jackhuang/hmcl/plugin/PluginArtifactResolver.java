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

import org.jackhuang.hmcl.plugin.internal.PluginPackageVersions;
import org.jackhuang.hmcl.plugin.mixin.bootstrap.PluginAgentSnapshot;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// Resolves exact installed and loaded artifact identities without owning lifecycle or persistence policy.
@NotNullByDefault
final class PluginArtifactResolver {
    /// Installed package and manifest repository.
    private final PluginPackageRepository packageRepository;

    /// Live lifecycle containers indexed by plugin ID.
    private final Map<String, PluginContainer> pluginMap;

    /// Process-local exact artifact status store.
    private final PluginRuntimeStateStore runtimeState;

    /// Creates an artifact resolver over one manager's repository and live runtime state.
    ///
    /// @param packageRepository installed package repository
    /// @param pluginMap live plugin containers
    /// @param runtimeState exact runtime artifact store
    PluginArtifactResolver(
            PluginPackageRepository packageRepository,
            Map<String, PluginContainer> pluginMap,
            PluginRuntimeStateStore runtimeState
    ) {
        this.packageRepository = packageRepository;
        this.pluginMap = pluginMap;
        this.runtimeState = runtimeState;
    }

    /// Resolves and remembers the currently published artifact without throwing from lifecycle controls.
    ///
    /// @param pluginId plugin ID to resolve
    /// @return exact installed artifact or `null` when absent or unreadable
    @Nullable PluginArtifactIdentity resolveInstalledIdentity(String pluginId) {
        @Nullable PluginArtifactIdentity remembered = runtimeState.getCurrent(pluginId);
        if (remembered != null) {
            return remembered;
        }
        try {
            for (Path packageFile : packageRepository.findInstalledPackages(pluginId)) {
                PluginManifest manifest = packageRepository.readManifest(packageFile);
                PluginArtifactIdentity identity = PluginArtifactIdentity.of(
                        manifest,
                        PluginPackageVersions.calculateSha256(packageFile)
                );
                runtimeState.remember(identity);
                return identity;
            }
        } catch (IOException | RuntimeException exception) {
            LOG.warning("Unable to resolve installed plugin artifact " + pluginId, exception);
        }
        return null;
    }

    /// Finds the artifact published for next launch, falling back to loaded code when no package exists.
    ///
    /// @param pluginId plugin ID to resolve
    /// @return resolved artifact or `null` when absent
    /// @throws IOException if installed packages cannot be enumerated or hashed
    @Nullable PluginPermissionService.ResolvedArtifact findCurrentPermissionArtifact(
            String pluginId
    ) throws IOException {
        List<Path> installedPackages = packageRepository.findInstalledPackages(pluginId);
        if (installedPackages.size() > 1) {
            throw new IOException("Installed plugin artifact is ambiguous: " + pluginId);
        }
        for (Path packageFile : installedPackages) {
            PluginManifest manifest = packageRepository.readManifest(packageFile);
            return resolvedArtifact(manifest, PluginPackageVersions.calculateSha256(packageFile));
        }
        @Nullable PluginContainer container = pluginMap.get(pluginId);
        if (container == null) {
            return null;
        }
        return resolvedArtifact(
                container.getManifest(),
                container.getContext().getArtifactSha256()
        );
    }

    /// Resolves the exact package identity currently published or active for one plugin ID.
    ///
    /// A published package takes precedence over loaded lifecycle code. Duplicate installed packages are rejected so
    /// an installation confirmation can never bind to an arbitrary same-ID file.
    ///
    /// @param pluginId plugin ID to resolve
    /// @return current exact artifact identity or `null` when the plugin is absent
    /// @throws IOException if installed package enumeration, manifest reading, or hashing fails
    @Nullable PluginArtifactIdentity findCurrentArtifactIdentity(String pluginId) throws IOException {
        @Nullable PluginPermissionService.ResolvedArtifact resolved = findCurrentPermissionArtifact(pluginId);
        if (resolved == null) {
            return null;
        }
        PluginPermissionStore.Artifact artifact = resolved.getArtifact();
        return new PluginArtifactIdentity(
                artifact.getPluginId(),
                artifact.getVersion(),
                artifact.getSha256()
        );
    }

    /// Resolves the exact artifact whose ordinary lifecycle code is active in the current process.
    ///
    /// @param pluginId plugin ID to resolve
    /// @return loaded artifact or `null` when no lifecycle container exists
    @Nullable PluginPermissionService.ResolvedArtifact findLoadedPermissionArtifact(String pluginId) {
        @Nullable PluginContainer container = pluginMap.get(pluginId);
        if (container == null) {
            return null;
        }
        return resolvedArtifact(
                container.getManifest(),
                container.getContext().getArtifactSha256()
        );
    }

    /// Finds the current manifest for an installed or loaded plugin ID.
    ///
    /// A replacement published for restart takes precedence over lifecycle classes still active in memory.
    ///
    /// @param pluginId validated plugin ID
    /// @return current installed manifest or `null` when the ID is new
    /// @throws IOException if the plugin directory cannot be listed
    @Nullable PluginManifest findInstalledManifest(String pluginId) throws IOException {
        for (Path packageFile : packageRepository.findInstalledPackages(pluginId)) {
            return packageRepository.readManifest(packageFile);
        }
        @Nullable PluginContainer loaded = pluginMap.get(pluginId);
        return loaded == null ? null : loaded.getManifest();
    }

    /// Returns the authoritative state of the artifact currently published for one plugin ID.
    ///
    /// @param pluginId plugin ID
    /// @param enabledStates persisted desired-enabled plugin IDs
    /// @param pendingUninstall persisted pending-removal plugin IDs
    /// @return artifact-bound runtime state
    PluginRuntimeStatus getRuntimeStatus(
            String pluginId,
            Set<String> enabledStates,
            Set<String> pendingUninstall
    ) {
        if (pendingUninstall.contains(pluginId)) {
            return PluginRuntimeStatus.PENDING_UNINSTALL;
        }
        @Nullable PluginArtifactIdentity identity = resolveInstalledIdentity(pluginId);
        if (identity == null) {
            @Nullable PluginContainer container = pluginMap.get(pluginId);
            if (container == null) {
                return enabledStates.contains(pluginId)
                        ? PluginRuntimeStatus.WAITING_FOR_RESTART
                        : PluginRuntimeStatus.INSTALLED_DISABLED;
            }
            identity = PluginArtifactIdentity.of(
                    container.getManifest(),
                    container.getContext().getArtifactSha256()
            );
        }
        @Nullable PluginRuntimeStatus status = runtimeState.getStatus(identity);
        if (status != null) {
            return status;
        }
        @Nullable PluginContainer container = pluginMap.get(pluginId);
        if (container != null && container.isEnabled()) {
            return PluginRuntimeStatus.ENABLED;
        }
        return enabledStates.contains(pluginId)
                ? PluginRuntimeStatus.WAITING_FOR_RESTART
                : PluginRuntimeStatus.INSTALLED_DISABLED;
    }

    /// Returns the current artifact's policy, dependency, loading, or lifecycle diagnostic.
    ///
    /// @param pluginId plugin ID
    /// @return artifact-bound detail or `null` when no diagnostic is present
    @Nullable String getRuntimeDetail(String pluginId) {
        @Nullable PluginArtifactIdentity identity = resolveInstalledIdentity(pluginId);
        return identity == null ? null : runtimeState.getDetail(identity);
    }

    /// Returns whether the premain Agent registered the exact installed or loaded Mixin artifact.
    ///
    /// @param pluginId plugin ID
    /// @return whether the exact plugin artifact's Mixins are active
    boolean isMixinActive(String pluginId) {
        @Nullable PluginContainer container = pluginMap.get(pluginId);
        if (container != null) {
            PluginArtifactIdentity identity = PluginArtifactIdentity.of(
                    container.getManifest(),
                    container.getContext().getArtifactSha256()
            );
            return PluginAgentSnapshot.current().confirms(
                    identity,
                    PluginAgentSnapshot.calculateMixinConfigurationDigest(container.getManifest().getMixins())
            );
        }
        @Nullable PluginArtifactIdentity identity = resolveInstalledIdentity(pluginId);
        if (identity == null) {
            return false;
        }
        try {
            for (Path packageFile : packageRepository.findInstalledPackages(pluginId)) {
                PluginManifest manifest = packageRepository.readManifest(packageFile);
                if (identity.equals(PluginArtifactIdentity.of(
                        manifest,
                        PluginPackageVersions.calculateSha256(packageFile)
                ))) {
                    return PluginAgentSnapshot.current().confirms(
                            identity,
                            PluginAgentSnapshot.calculateMixinConfigurationDigest(manifest.getMixins())
                    );
                }
            }
        } catch (IOException | RuntimeException exception) {
            LOG.warning("Unable to inspect active Mixin artifact " + pluginId, exception);
        }
        return false;
    }

    /// Creates one permission-service artifact from validated manifest and complete package digest values.
    ///
    /// @param manifest exact artifact manifest
    /// @param sha256 complete package digest
    /// @return resolved manifest and artifact-bound permission key
    private static PluginPermissionService.ResolvedArtifact resolvedArtifact(
            PluginManifest manifest,
            String sha256
    ) {
        return new PluginPermissionService.ResolvedArtifact(
                manifest,
                new PluginPermissionStore.Artifact(
                        manifest.getId(),
                        manifest.getVersion(),
                        sha256
                )
        );
    }
}
