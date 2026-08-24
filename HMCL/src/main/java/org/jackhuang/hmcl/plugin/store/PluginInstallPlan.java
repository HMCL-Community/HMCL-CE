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
package org.jackhuang.hmcl.plugin.store;

import org.jackhuang.hmcl.plugin.PluginArtifactIdentity;
import org.jackhuang.hmcl.plugin.PluginDependency;
import org.jackhuang.hmcl.plugin.PluginManifest;
import org.jackhuang.hmcl.plugin.PluginPackageRuntimeContract;
import org.jackhuang.hmcl.plugin.PluginPermission;
import org.jackhuang.hmcl.plugin.PluginPermissionTier;
import org.jackhuang.hmcl.plugin.PluginKind;
import org.jackhuang.hmcl.plugin.runtime.PluginExecutionMode;
import org.jackhuang.hmcl.plugin.runtime.RuntimeProviderBinding;
import org.jackhuang.hmcl.plugin.runtime.RuntimeProviderDeclaration;
import org.jackhuang.hmcl.plugin.runtime.RuntimeRequirement;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/// Describes a dependency-ordered plugin-store operation before any package is downloaded or installed.
@NotNullByDefault
public final class PluginInstallPlan {
    /// Requested root plugin ID.
    private final String rootPluginId;

    /// Effective dependencies followed by the requested plugin.
    private final @Unmodifiable List<Entry> entries;

    /// Exact installed identities authorized for every selected `REUSE` entry.
    private final @Unmodifiable Map<String, PluginArtifactIdentity> reusableArtifactIdentities;

    /// Confirmed prior state for every `INSTALL` or `UPDATE` entry.
    private final @Unmodifiable Map<String, Optional<PluginArtifactIdentity>> expectedPriorArtifacts;

    /// Virtual runtime Provider bindings indexed by dependent plugin ID.
    private final @Unmodifiable Map<String, RuntimeProviderBinding> runtimeBindings;

    /// Creates an immutable fail-closed plan with exact reuse and replacement prior identities.
    ///
    /// @param rootPluginId requested root plugin ID
    /// @param entries topologically ordered effective entries
    /// @param reusableArtifactIdentities exact installed identities for selected `REUSE` entries
    /// @param expectedPriorArtifacts confirmed absence or exact old artifact for every changed entry
    PluginInstallPlan(
            String rootPluginId,
            @Unmodifiable List<Entry> entries,
            @Unmodifiable Map<String, PluginArtifactIdentity> reusableArtifactIdentities,
            @Unmodifiable Map<String, Optional<PluginArtifactIdentity>> expectedPriorArtifacts
    ) {
        this(rootPluginId, entries, reusableArtifactIdentities, expectedPriorArtifacts, Map.of());
    }

    /// Creates a plan carrying concrete package edges and separate virtual runtime bindings.
    ///
    /// @param rootPluginId requested root plugin ID
    /// @param entries topologically ordered effective entries
    /// @param reusableArtifactIdentities exact installed identities for selected `REUSE` entries
    /// @param expectedPriorArtifacts confirmed absence or exact old artifact for every changed entry
    /// @param runtimeBindings Provider bindings indexed by dependent plugin ID
    PluginInstallPlan(
            String rootPluginId,
            @Unmodifiable List<Entry> entries,
            @Unmodifiable Map<String, PluginArtifactIdentity> reusableArtifactIdentities,
            @Unmodifiable Map<String, Optional<PluginArtifactIdentity>> expectedPriorArtifacts,
            @Unmodifiable Map<String, RuntimeProviderBinding> runtimeBindings
    ) {
        this.rootPluginId = rootPluginId;
        this.entries = List.copyOf(entries);
        this.reusableArtifactIdentities = Map.copyOf(reusableArtifactIdentities);
        this.expectedPriorArtifacts = Map.copyOf(expectedPriorArtifacts);
        this.runtimeBindings = Map.copyOf(runtimeBindings);

        Set<String> reuseIds = new HashSet<>();
        Set<String> replacementIds = new HashSet<>();
        for (Entry entry : this.entries) {
            if (!entry.requiresDownload()) {
                reuseIds.add(entry.getPluginId());
            } else {
                replacementIds.add(entry.getPluginId());
            }
        }
        Set<String> entryIds = this.entries.stream().map(Entry::getPluginId).collect(java.util.stream.Collectors.toSet());
        for (Map.Entry<String, RuntimeProviderBinding> bindingEntry : this.runtimeBindings.entrySet()) {
            RuntimeProviderBinding binding = bindingEntry.getValue();
            if (!bindingEntry.getKey().equals(binding.dependentPluginId())
                    || !entryIds.contains(binding.dependentPluginId())
                    || !entryIds.contains(binding.providerId())) {
                throw new IllegalArgumentException("Runtime binding does not belong to this install plan: "
                        + bindingEntry.getKey());
            }
        }
        if (!reuseIds.equals(this.reusableArtifactIdentities.keySet())) {
            throw new IllegalArgumentException("Every REUSE plan entry must have exactly one artifact identity");
        }
        for (Map.Entry<String, PluginArtifactIdentity> identityEntry
                : this.reusableArtifactIdentities.entrySet()) {
            PluginArtifactIdentity identity = identityEntry.getValue();
            if (!identityEntry.getKey().equals(identity.getPluginId())) {
                throw new IllegalArgumentException("Reusable artifact key does not match its identity: "
                        + identityEntry.getKey());
            }
            Entry planEntry = this.entries.stream()
                    .filter(entry -> entry.getPluginId().equals(identityEntry.getKey()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Reusable artifact has no plan entry: " + identityEntry.getKey()
                    ));
            if (!planEntry.getVersion().equals(identity.getVersion())) {
                throw new IllegalArgumentException("Reusable artifact version does not match its plan entry: "
                        + identityEntry.getKey());
            }
        }
        if (!replacementIds.equals(this.expectedPriorArtifacts.keySet())) {
            throw new IllegalArgumentException("Every changed plan entry must have exactly one prior expectation");
        }
        for (Entry entry : this.entries) {
            if (!entry.requiresDownload()) {
                continue;
            }
            Optional<PluginArtifactIdentity> expected = this.expectedPriorArtifacts.get(entry.getPluginId());
            if (expected == null) {
                throw new IllegalArgumentException("Changed plan entry has no prior expectation: "
                        + entry.getPluginId());
            }
            if (entry.getAction() == Action.INSTALL && expected.isPresent()) {
                throw new IllegalArgumentException("INSTALL plan entry must expect the plugin ID to be absent: "
                        + entry.getPluginId());
            }
            if (entry.getAction() == Action.UPDATE) {
                PluginArtifactIdentity identity = expected.orElseThrow(() -> new IllegalArgumentException(
                        "UPDATE plan entry must identify the exact prior artifact: " + entry.getPluginId()
                ));
                if (!entry.getPluginId().equals(identity.getPluginId())) {
                    throw new IllegalArgumentException("UPDATE prior identity has the wrong plugin ID: "
                            + entry.getPluginId());
                }
                @Nullable PluginManifest installedManifest = entry.getInstalledManifest();
                if (installedManifest == null
                        || !installedManifest.getVersion().equals(identity.getVersion())) {
                    throw new IllegalArgumentException("UPDATE prior identity does not match its installed manifest: "
                            + entry.getPluginId());
                }
            }
        }
    }

    /// Returns the requested root plugin ID.
    ///
    /// @return root plugin ID
    public String getRootPluginId() {
        return rootPluginId;
    }

    /// Returns effective entries in dependency-first order.
    ///
    /// @return immutable plan entries
    public @Unmodifiable List<Entry> getEntries() {
        return entries;
    }

    /// Returns the exact installed identities authorized for selected reused dependencies.
    ///
    /// @return immutable artifact identities indexed by plugin ID
    public @Unmodifiable Map<String, PluginArtifactIdentity> getReusableArtifactIdentities() {
        return reusableArtifactIdentities;
    }

    /// Returns the confirmed prior state for every package that will be installed or updated.
    ///
    /// An empty optional means the plugin ID must still be absent. A present identity means final publication must
    /// replace exactly those package bytes.
    ///
    /// @return immutable prior expectations indexed by replacement plugin ID
    public @Unmodifiable Map<String, Optional<PluginArtifactIdentity>> getExpectedPriorArtifacts() {
        return expectedPriorArtifacts;
    }

    /// Returns virtual runtime Provider bindings separately from serialized plugin dependencies.
    ///
    /// @return immutable bindings indexed by dependent plugin ID
    public @Unmodifiable Map<String, RuntimeProviderBinding> getRuntimeBindings() {
        return runtimeBindings;
    }

    /// Returns whether a selected runtime Host comes from a custom source requiring a separate receipt.
    ///
    /// @return whether custom-source confirmation is mandatory
    public boolean requiresCustomSourceConfirmation() {
        return runtimeBindings.values().stream()
                .map(RuntimeProviderBinding::providerId)
                .distinct()
                .map(this::requireEntry)
                .anyMatch(Entry::requiresSourceConfirmation);
    }

    /// Returns runtime Host IDs that require custom-source confirmation.
    ///
    /// @return immutable sorted Provider ID list
    public @Unmodifiable List<String> getCustomSourceProviderIds() {
        return runtimeBindings.values().stream()
                .map(RuntimeProviderBinding::providerId)
                .distinct()
                .filter(providerId -> requireEntry(providerId).requiresSourceConfirmation())
                .sorted()
                .toList();
    }

    /// Returns exact installed Runtime Hosts that must be enabled by the publishing transaction.
    ///
    /// @return immutable Provider IDs in dependency-first plan order
    public @Unmodifiable List<String> getEnablementPluginIds() {
        return entries.stream()
                .filter(entry -> entry.getAction() == Action.ENABLE)
                .map(Entry::getPluginId)
                .toList();
    }

    /// Returns changed plugin IDs that declare at least one dangerous permission.
    ///
    /// Both required and optional permissions are included because the permission switches remain editable until the
    /// confirmation action is submitted.
    ///
    /// @return immutable dangerous-permission plugin IDs in dependency-first order
    public @Unmodifiable List<String> getDangerousPermissionPluginIds() {
        return getPermissionReviewEntries().stream()
                .filter(entry -> java.util.stream.Stream.concat(
                                entry.getRequiredPermissions().stream(),
                                entry.getOptionalPermissions().stream())
                        .anyMatch(permission -> PluginPermissionTier.tierOf(permission)
                                == PluginPermissionTier.DANGEROUS))
                .map(Entry::getPluginId)
                .toList();
    }

    /// Returns exact Store runtime contracts for every package downloaded by this plan.
    ///
    /// @return immutable expected contracts indexed by changed plugin ID
    public @Unmodifiable Map<String, PluginPackageRuntimeContract> getExpectedPackageRuntimeContracts() {
        return getDownloadEntries().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                Entry::getPluginId,
                entry -> {
                    PluginStoreManifest.PluginVersionEntry version = entry.requireRemoteVersion();
                    return new PluginPackageRuntimeContract(
                            version.getVersion(),
                            version.getPluginApiVersion(),
                            version.getRuntime(),
                            version.getAbi(),
                            version.getPlatforms(),
                            version.getPluginKind(),
                            version.getExecutionMode(),
                            version.getRuntimeProvider(),
                            version.getProvidesRuntimes()
                    );
                }
        ));
    }

    /// Returns the exact aggregate bytes downloaded by this plan.
    ///
    /// @return non-negative total selected artifact size
    public long getTotalDownloadSize() {
        return getDownloadEntries().stream().mapToLong(Entry::getSelectedDownloadSize).sum();
    }

    /// Resolves one plan entry by ID.
    ///
    /// @param pluginId selected plugin ID
    /// @return selected entry
    private Entry requireEntry(String pluginId) {
        return entries.stream().filter(entry -> entry.getPluginId().equals(pluginId)).findFirst()
                .orElseThrow(() -> new IllegalStateException("Install plan has no entry for " + pluginId));
    }

    /// Returns only entries whose packages must be downloaded and published.
    ///
    /// @return immutable download entries
    public @Unmodifiable List<Entry> getDownloadEntries() {
        return entries.stream().filter(Entry::requiresDownload).toList();
    }

    /// Returns every changed artifact that must receive a fresh user permission decision.
    ///
    /// Installations and updates are always included, even when the requested permission set is unchanged or empty.
    /// Reused dependencies are excluded because the resolver only emits `REUSE` after the caller proves that the
    /// exact installed artifact still has every required permission grant.
    ///
    /// @return immutable permission-review entries in dependency-first order
    public @Unmodifiable List<Entry> getPermissionReviewEntries() {
        return entries.stream().filter(Entry::requiresFreshPermissionReview).toList();
    }

    /// Returns the requested root entry.
    ///
    /// @return root entry
    public Entry getRootEntry() {
        return entries.stream()
                .filter(entry -> entry.getPluginId().equals(rootPluginId))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Install plan has no root entry"));
    }

    /// One effective plugin version in a dependency installation plan.
    @NotNullByDefault
    public static final class Entry {
        /// Effective plugin ID.
        private final String pluginId;

        /// Display name from the registry or installed manifest.
        private final String displayName;

        /// Effective plugin version.
        private final String version;

        /// Operation required for this entry.
        private final Action action;

        /// Registry metadata for a downloadable package.
        private final @Nullable PluginStoreRegistry.PluginStoreEntry storeEntry;

        /// Remote version metadata for a downloadable package.
        private final @Nullable PluginStoreManifest.PluginVersionEntry remoteVersion;

        /// Existing installed manifest when one is available.
        private final @Nullable PluginManifest installedManifest;

        /// Stable identifier of the source that selected a downloadable package.
        private final @Nullable String sourceId;

        /// Human-readable cosmetic name of the source that selected a downloadable package.
        private final @Nullable String sourceDisplayName;

        /// Immutable security provenance of the source that selected a downloadable package.
        private final @Nullable PluginSourceProvenance sourceProvenance;

        /// Source-scoped manager that must download a downloadable package.
        private final @Nullable PluginStoreManager sourceManager;

        /// Exact platform artifact selected during planning, or `null` for reuse and legacy compatibility entries.
        private final @Nullable PluginStoreArtifact selectedArtifact;

        /// Whether this entry's source requires a separate custom-source receipt.
        private final boolean sourceConfirmationRequired;

        /// Creates one immutable effective plan entry.
        ///
        /// @param pluginId effective plugin ID
        /// @param displayName display name
        /// @param version effective version
        /// @param action required operation
        /// @param storeEntry registry metadata or `null` for a reused installed plugin
        /// @param remoteVersion remote metadata or `null` for a reused installed plugin
        /// @param installedManifest previous installed manifest or `null`
        /// @param sourceId selected source ID or `null` for a reused installed plugin
        /// @param sourceDisplayName selected cosmetic source name or `null` for a reused installed plugin
        /// @param sourceProvenance selected immutable security provenance or `null` for a reused installed plugin
        /// @param sourceManager selected source manager or `null` for a reused installed plugin
        Entry(
                String pluginId,
                String displayName,
                String version,
                Action action,
                @Nullable PluginStoreRegistry.PluginStoreEntry storeEntry,
                @Nullable PluginStoreManifest.PluginVersionEntry remoteVersion,
                @Nullable PluginManifest installedManifest,
                @Nullable String sourceId,
                @Nullable String sourceDisplayName,
                @Nullable PluginSourceProvenance sourceProvenance,
                @Nullable PluginStoreManager sourceManager
        ) {
            this(pluginId, displayName, version, action, storeEntry, remoteVersion, installedManifest,
                    sourceId, sourceDisplayName, sourceProvenance, sourceManager, null, false);
        }

        /// Creates one effective plan entry with exact platform and source-confirmation metadata.
        ///
        /// @param pluginId effective plugin ID
        /// @param displayName display name
        /// @param version effective version
        /// @param action required operation
        /// @param storeEntry registry metadata or `null` for reuse
        /// @param remoteVersion remote metadata or `null` for reuse
        /// @param installedManifest previous installed manifest or `null`
        /// @param sourceId selected source ID or `null` for reuse
        /// @param sourceDisplayName selected source display name or `null` for reuse
        /// @param sourceProvenance selected source provenance or `null` for reuse
        /// @param sourceManager selected source manager or `null` for reuse
        /// @param selectedArtifact exact selected platform artifact or `null` for reuse
        /// @param sourceConfirmationRequired whether a custom-source receipt is required
        Entry(
                String pluginId,
                String displayName,
                String version,
                Action action,
                @Nullable PluginStoreRegistry.PluginStoreEntry storeEntry,
                @Nullable PluginStoreManifest.PluginVersionEntry remoteVersion,
                @Nullable PluginManifest installedManifest,
                @Nullable String sourceId,
                @Nullable String sourceDisplayName,
                @Nullable PluginSourceProvenance sourceProvenance,
                @Nullable PluginStoreManager sourceManager,
                @Nullable PluginStoreArtifact selectedArtifact,
                boolean sourceConfirmationRequired
        ) {
            this.pluginId = pluginId;
            this.displayName = displayName;
            this.version = version;
            this.action = action;
            this.storeEntry = storeEntry;
            this.remoteVersion = remoteVersion;
            this.installedManifest = installedManifest;
            this.sourceId = sourceId;
            this.sourceDisplayName = sourceDisplayName;
            this.sourceProvenance = sourceProvenance;
            this.sourceManager = sourceManager;
            this.selectedArtifact = selectedArtifact;
            this.sourceConfirmationRequired = sourceConfirmationRequired;

            boolean hasCompleteSource = sourceId != null
                    && sourceDisplayName != null
                    && sourceProvenance != null
                    && sourceManager != null;
            boolean hasNoSource = sourceId == null
                    && sourceDisplayName == null
                    && sourceProvenance == null
                    && sourceManager == null;
            if (requiresDownload() && !hasCompleteSource) {
                throw new IllegalArgumentException("Downloadable plan entry has no complete source: " + pluginId);
            }
            if (!requiresDownload() && !hasNoSource) {
                throw new IllegalArgumentException("Reused plan entry must not have a remote source: " + pluginId);
            }
            if (!requiresDownload() && (selectedArtifact != null || sourceConfirmationRequired)) {
                throw new IllegalArgumentException("Reused plan entry cannot carry remote artifact metadata: "
                        + pluginId);
            }
            if (sourceConfirmationRequired && sourceProvenance == null) {
                throw new IllegalArgumentException("Custom-source entry has no source provenance: " + pluginId);
            }
        }

        /// Returns the effective plugin ID.
        ///
        /// @return plugin ID
        public String getPluginId() {
            return pluginId;
        }

        /// Returns the display name.
        ///
        /// @return display name
        public String getDisplayName() {
            return displayName;
        }

        /// Returns the effective version.
        ///
        /// @return version string
        public String getVersion() {
            return version;
        }

        /// Returns the operation required for this entry.
        ///
        /// @return plan action
        public Action getAction() {
            return action;
        }

        /// Returns whether this entry requires a remote package download.
        ///
        /// @return download requirement
        public boolean requiresDownload() {
            return action == Action.INSTALL || action == Action.UPDATE;
        }

        /// Returns whether this exact artifact must be shown in a new permission grant window.
        ///
        /// Every install and update requires an explicit fresh confirmation. Previous grants may only initialize
        /// update switches; they never turn an update into a silent operation.
        ///
        /// @return `true` for installations and updates, or `false` for pre-authorized reused artifacts
        public boolean requiresFreshPermissionReview() {
            return requiresDownload();
        }

        /// Returns registry metadata for a downloadable entry.
        ///
        /// @return registry entry or `null`
        public @Nullable PluginStoreRegistry.PluginStoreEntry getStoreEntry() {
            return storeEntry;
        }

        /// Returns remote version metadata for a downloadable entry.
        ///
        /// @return remote version or `null`
        public @Nullable PluginStoreManifest.PluginVersionEntry getRemoteVersion() {
            return remoteVersion;
        }

        /// Returns the installed manifest before the plan is applied.
        ///
        /// @return installed manifest or `null`
        public @Nullable PluginManifest getInstalledManifest() {
            return installedManifest;
        }

        /// Returns the stable selected source ID for a downloadable package.
        ///
        /// @return source ID or `null` for a reused artifact
        public @Nullable String getSourceId() {
            return sourceId;
        }

        /// Returns the selected source's human-readable cosmetic name for a downloadable package.
        ///
        /// This value must not be used for security decisions because local aliases and registry names are mutable.
        ///
        /// @return source display name or `null` for a reused artifact
        public @Nullable String getSourceDisplayName() {
            return sourceDisplayName;
        }

        /// Returns immutable selected-source security provenance for a downloadable package.
        ///
        /// @return source provenance or `null` for a reused artifact
        public @Nullable PluginSourceProvenance getSourceProvenance() {
            return sourceProvenance;
        }

        /// Returns immutable security provenance required by a downloadable package.
        ///
        /// @return source provenance for an installation or update
        /// @throws IllegalStateException if this entry reuses an installed artifact
        public PluginSourceProvenance requireSourceProvenance() {
            if (!requiresDownload() || sourceProvenance == null) {
                throw new IllegalStateException("Plan entry has no downloadable source provenance: " + pluginId);
            }
            return sourceProvenance;
        }

        /// Returns the source-scoped manager for a downloadable package.
        ///
        /// @return source manager or `null` for a reused artifact
        public @Nullable PluginStoreManager getSourceManager() {
            return sourceManager;
        }

        /// Returns the source-scoped manager required to download this entry.
        ///
        /// @return source manager for an installation or update
        /// @throws IllegalStateException if this entry reuses an installed artifact
        public PluginStoreManager requireSourceManager() {
            if (!requiresDownload() || sourceManager == null) {
                throw new IllegalStateException("Plan entry has no downloadable source: " + pluginId);
            }
            return sourceManager;
        }

        /// Returns the exact platform artifact selected during planning.
        ///
        /// @return selected artifact or `null` for reuse and legacy compatibility entries
        public @Nullable PluginStoreArtifact getSelectedArtifact() {
            return selectedArtifact;
        }

        /// Returns the exact selected artifact required by a downloadable entry.
        ///
        /// @return selected platform artifact
        /// @throws IllegalStateException if planning did not bind an exact artifact
        public PluginStoreArtifact requireSelectedArtifact() {
            if (!requiresDownload() || selectedArtifact == null) {
                throw new IllegalStateException("Plan entry has no selected platform artifact: " + pluginId);
            }
            return selectedArtifact;
        }

        /// Returns the exact number of bytes downloaded for this entry.
        ///
        /// @return zero for reuse or selected artifact bytes for a download
        public long getSelectedDownloadSize() {
            if (!requiresDownload()) {
                return 0;
            }
            if (selectedArtifact != null) {
                return selectedArtifact.size();
            }
            return Objects.requireNonNull(requireRemoteVersion().getSize(), "Remote version has no size");
        }

        /// Returns whether a separate custom-source receipt is required.
        ///
        /// @return source confirmation requirement
        public boolean requiresSourceConfirmation() {
            return sourceConfirmationRequired;
        }

        /// Returns whether this entry represents a runtime Host package.
        ///
        /// @return whether the effective package advertises runtime capabilities
        public boolean isRuntimeProvider() {
            return getPluginKind() == PluginKind.RUNTIME_PROVIDER;
        }

        /// Returns the effective package role.
        ///
        /// @return package role
        public PluginKind getPluginKind() {
            return remoteVersion != null ? remoteVersion.getPluginKind() : requireInstalledManifest().getPluginKind();
        }

        /// Returns the effective execution boundary.
        ///
        /// @return requested execution mode
        public PluginExecutionMode getExecutionMode() {
            return remoteVersion != null
                    ? remoteVersion.getExecutionMode()
                    : requireInstalledManifest().getExecutionMode();
        }

        /// Returns the effective runtime requirement.
        ///
        /// @return runtime requirement
        public RuntimeRequirement getRuntimeRequirement() {
            return remoteVersion != null
                    ? remoteVersion.getRuntimeRequirement()
                    : requireInstalledManifest().getRuntimeRequirement();
        }

        /// Returns runtime capabilities advertised by this effective package.
        ///
        /// @return immutable Provider declarations
        public @Unmodifiable List<RuntimeProviderDeclaration> getProvidesRuntimes() {
            return remoteVersion != null
                    ? remoteVersion.getProvidesRuntimes()
                    : requireInstalledManifest().getProvidesRuntimes();
        }

        /// Returns remote metadata required by a downloadable entry.
        ///
        /// @return remote version metadata
        private PluginStoreManifest.PluginVersionEntry requireRemoteVersion() {
            if (remoteVersion == null) {
                throw new IllegalStateException("Plan entry has no remote version: " + pluginId);
            }
            return remoteVersion;
        }

        /// Returns the effective permission declaration.
        ///
        /// @return immutable permissions
        public @Unmodifiable List<PluginPermission> getPermissions() {
            return remoteVersion != null
                    ? remoteVersion.getPermissions()
                    : requireInstalledManifest().getPermissions();
        }

        /// Returns permissions required before the effective artifact may execute.
        ///
        /// @return immutable required permissions
        public @Unmodifiable List<PluginPermission> getRequiredPermissions() {
            return remoteVersion != null
                    ? remoteVersion.getRequiredPermissions()
                    : requireInstalledManifest().getRequiredPermissions();
        }

        /// Returns permissions that may be denied without blocking ordinary artifact execution.
        ///
        /// @return immutable optional permissions
        public @Unmodifiable List<PluginPermission> getOptionalPermissions() {
            return remoteVersion != null
                    ? remoteVersion.getOptionalPermissions()
                    : requireInstalledManifest().getOptionalPermissions();
        }

        /// Returns the launcher version constraint for the effective artifact.
        ///
        /// @return launcher version constraint expression
        public String getLauncherVersion() {
            return remoteVersion != null
                    ? remoteVersion.getLauncherVersion()
                    : requireInstalledManifest().getLauncherVersion();
        }

        /// Returns the effective plugin dependencies.
        ///
        /// @return immutable dependencies
        public @Unmodifiable List<PluginDependency> getDependencies() {
            return remoteVersion != null
                    ? remoteVersion.getDependencies()
                    : requireInstalledManifest().getPluginDependencies();
        }

        /// Returns the installed manifest required by a reuse entry.
        ///
        /// @return installed manifest
        private PluginManifest requireInstalledManifest() {
            if (installedManifest == null) {
                throw new IllegalStateException("Plan entry has no effective metadata: " + pluginId);
            }
            return installedManifest;
        }
    }

    /// Operation required for one effective plugin version.
    @NotNullByDefault
    public enum Action {
        /// Keep an already installed compatible version whose exact artifact has complete required grants.
        REUSE,

        /// Keep an exact installed compatible Runtime Host and enable it atomically with its new dependent.
        ENABLE,

        /// Install a plugin that is not currently installed.
        INSTALL,

        /// Replace an installed plugin with the selected remote version.
        UPDATE
    }
}
