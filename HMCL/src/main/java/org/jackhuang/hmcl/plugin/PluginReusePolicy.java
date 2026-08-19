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
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/// Validates whether exact installed artifacts may be reused by a plugin installation dependency plan.
@NotNullByDefault
final class PluginReusePolicy {
    /// Installed package repository used to re-read exact package bytes.
    private final PluginPackageRepository packageRepository;

    /// Artifact-bound permission service used for current required grants.
    private final PluginPermissionService permissionService;

    /// Launcher compatibility policy shared with ordinary lifecycle loading.
    private final Predicate<PluginManifest> launcherCompatibility;

    /// Creates one exact-artifact dependency reuse policy.
    ///
    /// @param packageRepository installed package repository
    /// @param permissionService artifact-bound permission service
    /// @param launcherCompatibility launcher version compatibility predicate
    PluginReusePolicy(
            PluginPackageRepository packageRepository,
            PluginPermissionService permissionService,
            Predicate<PluginManifest> launcherCompatibility
    ) {
        this.packageRepository = packageRepository;
        this.permissionService = permissionService;
        this.launcherCompatibility = launcherCompatibility;
    }

    /// Returns whether one installed manifest currently satisfies every reuse gate.
    ///
    /// @param pluginId expected installed plugin ID
    /// @param manifest installation-planning manifest
    /// @param enabledPluginIds current desired-enabled plugin IDs
    /// @return whether the exact current package may be reused
    /// @throws IOException if installed package or permission state cannot be inspected
    boolean isReusable(
            String pluginId,
            PluginManifest manifest,
            @Unmodifiable Set<String> enabledPluginIds
    ) throws IOException {
        return resolveReusableIdentity(pluginId, manifest, enabledPluginIds) != null;
    }

    /// Resolves the exact identity of one currently reusable installed artifact.
    ///
    /// @param pluginId expected installed plugin ID
    /// @param manifest installation-planning manifest
    /// @param enabledPluginIds current desired-enabled plugin IDs
    /// @return exact reusable identity or `null` when any reuse gate fails
    /// @throws IOException if installed package or permission state cannot be inspected
    @Nullable PluginArtifactIdentity resolveReusableIdentity(
            String pluginId,
            PluginManifest manifest,
            @Unmodifiable Set<String> enabledPluginIds
    ) throws IOException {
        if (!pluginId.equals(manifest.getId())) {
            return null;
        }
        if (!enabledPluginIds.contains(pluginId)) {
            return null;
        }
        if (manifest.getSchemaVersion() < PluginManifest.MIN_EXECUTABLE_SCHEMA_VERSION) {
            return null;
        }
        if (!PluginManifest.isCanonicalExecutableId(pluginId)) {
            return null;
        }
        if (!launcherCompatibility.test(manifest)) {
            return null;
        }
        @Unmodifiable List<Path> packages = packageRepository.findInstalledPackages(pluginId);
        if (packages.size() != 1) {
            return null;
        }
        Path packageFile = packages.get(0);
        PluginManifest currentManifest = packageRepository.readManifest(packageFile);
        if (!manifest.equals(currentManifest)) {
            return null;
        }
        String sha256 = PluginPackageVersions.calculateSha256(packageFile);
        @Unmodifiable Set<PluginPermission> granted = permissionService.getGrantedPermissions(
                currentManifest,
                sha256
        );
        if (!granted.containsAll(currentManifest.getRequiredPermissions())) {
            return null;
        }
        return PluginArtifactIdentity.of(currentManifest, sha256);
    }

    /// Revalidates every unreplaced installed dependency in a replacement batch's complete dependency closure.
    ///
    /// @param effectiveManifests prospective installed manifests after replacement
    /// @param replacementIds IDs whose packages will be replaced by this transaction
    /// @param enabledPluginIds current desired-enabled plugin IDs
    /// @return immutable IDs that are dependencies of at least one replacement and must be enabled after publication
    /// @throws IOException if any unreplaced dependency is missing, disabled, incompatible, changed, or under-granted
    @Unmodifiable Set<String> validateDependencyClosure(
            @Unmodifiable Map<String, PluginManifest> effectiveManifests,
            @Unmodifiable Set<String> replacementIds,
            @Unmodifiable Set<String> enabledPluginIds
    ) throws IOException {
        return validateDependencyClosure(
                effectiveManifests,
                replacementIds,
                enabledPluginIds,
                Map.of(),
                false
        );
    }

    /// Revalidates a replacement batch against the exact reusable identities captured during store planning.
    ///
    /// Every unreplaced dependency in the complete replacement closure must have one and only one expected identity.
    /// This prevents a same-ID and same-version package rewrite from inheriting a previous plan, including artifacts
    /// with no required permissions whose grant state alone cannot reveal changed bytes.
    ///
    /// @param effectiveManifests prospective installed manifests after replacement
    /// @param replacementIds IDs whose packages will be replaced by this transaction
    /// @param enabledPluginIds current desired-enabled plugin IDs
    /// @param expectedReusableArtifacts exact identities captured by the confirmed installation plan
    /// @return immutable IDs that are dependencies of at least one replacement and must be enabled after publication
    /// @throws IOException if any unreplaced dependency differs from the confirmed exact artifact
    @Unmodifiable Set<String> validateDependencyClosure(
            @Unmodifiable Map<String, PluginManifest> effectiveManifests,
            @Unmodifiable Set<String> replacementIds,
            @Unmodifiable Set<String> enabledPluginIds,
            @Unmodifiable Map<String, PluginArtifactIdentity> expectedReusableArtifacts
    ) throws IOException {
        return validateDependencyClosure(
                effectiveManifests,
                replacementIds,
                enabledPluginIds,
                expectedReusableArtifacts,
                true
        );
    }

    /// Performs dependency closure validation with optional exact planning identity enforcement.
    ///
    /// @param effectiveManifests prospective installed manifests after replacement
    /// @param replacementIds IDs whose packages will be replaced by this transaction
    /// @param enabledPluginIds current desired-enabled plugin IDs
    /// @param expectedReusableArtifacts exact identities captured during planning
    /// @param requireExpectedIdentities whether every unreplaced dependency must match the planning snapshot
    /// @return immutable dependency IDs that must be enabled after publication
    /// @throws IOException if the dependency closure cannot be reused safely
    private @Unmodifiable Set<String> validateDependencyClosure(
            @Unmodifiable Map<String, PluginManifest> effectiveManifests,
            @Unmodifiable Set<String> replacementIds,
            @Unmodifiable Set<String> enabledPluginIds,
            @Unmodifiable Map<String, PluginArtifactIdentity> expectedReusableArtifacts,
            boolean requireExpectedIdentities
    ) throws IOException {
        for (Map.Entry<String, PluginArtifactIdentity> entry : expectedReusableArtifacts.entrySet()) {
            if (!entry.getKey().equals(entry.getValue().getPluginId())) {
                throw new IllegalArgumentException("Reusable artifact identity key does not match its plugin ID: "
                        + entry.getKey());
            }
        }
        Set<String> dependencyIds = new HashSet<>();
        Set<String> reusedDependencyIds = new HashSet<>();
        Set<String> visited = new HashSet<>();
        for (String replacementId : replacementIds) {
            @Nullable PluginManifest replacement = effectiveManifests.get(replacementId);
            if (replacement == null) {
                throw new IOException("Missing replacement manifest during dependency reuse validation: "
                        + replacementId);
            }
            if (visited.add(replacementId)) {
                visitDependencies(
                        replacement,
                        effectiveManifests,
                        replacementIds,
                        enabledPluginIds,
                        expectedReusableArtifacts,
                        requireExpectedIdentities,
                        dependencyIds,
                        reusedDependencyIds,
                        visited
                );
            }
        }
        if (requireExpectedIdentities && !expectedReusableArtifacts.keySet().equals(reusedDependencyIds)) {
            throw new IOException("Confirmed reusable artifact identities do not match the final dependency closure");
        }
        return Set.copyOf(dependencyIds);
    }

    /// Traverses one manifest's dependencies and revalidates every artifact not replaced by the transaction.
    ///
    /// @param manifest manifest whose dependencies are visited
    /// @param effectiveManifests prospective installed manifest graph
    /// @param replacementIds transaction replacement IDs
    /// @param enabledPluginIds current desired-enabled IDs
    /// @param expectedReusableArtifacts exact identities captured during planning
    /// @param requireExpectedIdentities whether exact planning identities are mandatory
    /// @param dependencyIds collected dependency IDs
    /// @param reusedDependencyIds collected unreplaced dependency IDs
    /// @param visited manifests whose dependencies have already been traversed
    /// @throws IOException if a dependency cannot be reused safely
    private void visitDependencies(
            PluginManifest manifest,
            @Unmodifiable Map<String, PluginManifest> effectiveManifests,
            @Unmodifiable Set<String> replacementIds,
            @Unmodifiable Set<String> enabledPluginIds,
            @Unmodifiable Map<String, PluginArtifactIdentity> expectedReusableArtifacts,
            boolean requireExpectedIdentities,
            Set<String> dependencyIds,
            Set<String> reusedDependencyIds,
            Set<String> visited
    ) throws IOException {
        for (PluginDependency dependency : manifest.getPluginDependencies()) {
            String dependencyId = dependency.getId();
            @Nullable PluginManifest dependencyManifest = effectiveManifests.get(dependencyId);
            if (dependencyManifest == null) {
                throw new IOException("Missing dependency during final reuse validation: " + dependencyId);
            }
            dependencyIds.add(dependencyId);
            if (!replacementIds.contains(dependencyId)) {
                reusedDependencyIds.add(dependencyId);
                @Nullable PluginArtifactIdentity currentIdentity = resolveReusableIdentity(
                        dependencyId,
                        dependencyManifest,
                        enabledPluginIds
                );
                if (currentIdentity == null) {
                    throw new IOException("Installed dependency " + dependencyId
                            + " no longer satisfies the reuse policy");
                }
                if (requireExpectedIdentities) {
                    @Nullable PluginArtifactIdentity expectedIdentity =
                            expectedReusableArtifacts.get(dependencyId);
                    if (expectedIdentity == null) {
                        throw new IOException("Confirmed installation plan has no exact identity for reused dependency "
                                + dependencyId);
                    }
                    if (!expectedIdentity.equals(currentIdentity)) {
                        throw new IOException("Installed dependency " + dependencyId
                                + " changed after planning: expected " + expectedIdentity
                                + " but found " + currentIdentity);
                    }
                }
            }
            if (visited.add(dependencyId)) {
                visitDependencies(
                        dependencyManifest,
                        effectiveManifests,
                        replacementIds,
                        enabledPluginIds,
                        expectedReusableArtifacts,
                        requireExpectedIdentities,
                        dependencyIds,
                        reusedDependencyIds,
                        visited
                );
            }
        }
    }
}
