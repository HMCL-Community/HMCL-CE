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

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.image.Image;
import org.jackhuang.hmcl.Metadata;
import org.jackhuang.hmcl.plugin.internal.PluginPackageVersions;
import org.jackhuang.hmcl.plugin.internal.VerifiedPluginPackage;
import org.jackhuang.hmcl.plugin.loader.JavaPluginLoader;
import org.jackhuang.hmcl.plugin.loader.PluginLoader;
import org.jackhuang.hmcl.plugin.mixin.bootstrap.PluginAgentSnapshot;
import org.jackhuang.hmcl.plugin.trust.PluginCertificationReceipt;
import org.jackhuang.hmcl.plugin.trust.PluginCertificationReceiptStore;
import org.jackhuang.hmcl.plugin.trust.PluginRuntimeTrustGuard;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.jetbrains.annotations.UnmodifiableView;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Stream;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// Discovers, validates, orders, loads, enables, disables, and removes HMCL plugins.
/// State-changing entry points reject ordinary plugin class loaders and lifecycle callbacks. This guard prevents
/// plugins from casually bypassing launcher confirmation through the public singleton, but it is not a security
/// boundary against Mixin-injected HMCL classes, unrestricted reflection, `Unsafe`, or direct filesystem access in
/// the shared JVM.
@NotNullByDefault
public final class PluginManager {
    /// Directory containing installed `.npl` files.
    private final Path pluginsDirectory;
    /// Directory containing extracted package contents used for normal lifecycle loading.
    private final Path pluginPackageDirectory;
    /// Directory containing persistent per-plugin private data.
    private final Path pluginStorageDirectory;
    /// Persisted desired enablement and pending-uninstall state store.
    private final PluginStateStore stateStore;
    /// Proof-backed certified installation receipts changed in the same transaction as packages.
    private final PluginCertificationReceiptStore certificationReceiptStore;
    /// Read-only installed package and manifest repository.
    private final PluginPackageRepository packageRepository;
    /// Exact installed and loaded artifact identity resolver.
    private final PluginArtifactResolver artifactResolver;
    /// Prospective dependency graph and reverse-dependency planner.
    private final PluginDependencyPlanner dependencyPlanner;
    /// Durable package, permission, and state publication service.
    private final PluginPackageMutationService packageMutationService;
    /// Cross-process lock shared by package, state, and permission mutations.
    private final PluginMutationLock mutationLock;
    /// Artifact-bound user permission decisions.
    private final PluginPermissionService permissionService;
    /// Exact-artifact policy for plugin-store dependency reuse.
    private final PluginReusePolicy reusePolicy;
    /// Exact prior-state capture and final replacement revalidation.
    private final PluginInstallationStateGuard installationStateGuard;
    /// Same-process guard protecting launcher-administrative entry points from ordinary plugin code.
    private final PluginAdministrativeGuard administrativeGuard;
    /// Startup snapshot that re-verifies certified receipts and applies authenticated revocations.
    private final PluginRuntimeTrustGuard runtimeTrustGuard;
    /// Lightweight JVM-local lock protecting in-memory runtime state from concurrent UI reads and background mutations.
    private final ReadWriteLock stateLock = new ReentrantReadWriteLock();
    /// Mutable observable list backing the plugin management UI.
    private final ObservableList<PluginContainer> plugins = FXCollections.observableArrayList();
    /// Loaded plugins indexed by validated plugin ID.
    private final Map<String, PluginContainer> pluginMap = new LinkedHashMap<>();
    /// Process-local exact artifact status and diagnostic store.
    private final PluginRuntimeStateStore runtimeState = new PluginRuntimeStateStore();
    /// Runtime loaders indexed by plugin implementation type.
    private final Map<PluginManifest.PluginType, PluginLoader> loaders = new EnumMap<>(PluginManifest.PluginType.class);
    /// Plugin IDs that should be enabled now or after the next Mixin-capable restart.
    private final Set<String> enabledStates = new HashSet<>();
    /// Plugin IDs whose files and data should be removed at the next startup.
    private final Set<String> pendingUninstall = new HashSet<>();
    /// Creates the singleton manager and its storage directories.
    PluginManager() {
        this(Metadata.HMCL_LOCAL_HOME, false, null);
    }

    /// Creates an isolated manager rooted at the supplied HMCL home.
    /// This constructor is package-private so lifecycle and installation behavior can be tested without
    /// mutating the user's launcher directory.
    /// @param localHome isolated HMCL home
    PluginManager(Path localHome) {
        this(localHome, true, null);
    }

    /// Creates an isolated manager with an explicit runtime trust snapshot for revocation integration tests.
    ///
    /// @param localHome isolated HMCL home
    /// @param runtimeTrustGuard explicit proof-backed runtime gate
    PluginManager(Path localHome, PluginRuntimeTrustGuard runtimeTrustGuard) {
        this(localHome, true, runtimeTrustGuard);
    }

    /// Creates one manager with an explicit construction-stack trust policy.
    /// @param localHome launcher-local home
    /// @param trustConstructionStack whether to trust exact test-framework loaders on the construction stack
    private PluginManager(
            Path localHome,
            boolean trustConstructionStack,
            @Nullable PluginRuntimeTrustGuard explicitRuntimeTrustGuard
    ) {
        administrativeGuard = new PluginAdministrativeGuard(trustConstructionStack);
        pluginsDirectory = localHome.resolve("plugins");
        pluginPackageDirectory = localHome.resolve("plugin-data");
        pluginStorageDirectory = localHome.resolve("plugin-storage");
        packageRepository = new PluginPackageRepository(pluginsDirectory);
        artifactResolver = new PluginArtifactResolver(packageRepository, pluginMap, runtimeState);
        installationStateGuard = new PluginInstallationStateGuard(artifactResolver);
        dependencyPlanner = new PluginDependencyPlanner(packageRepository);
        mutationLock = new PluginMutationLock(localHome);
        stateStore = new PluginStateStore(localHome.resolve("plugin-states.json"), mutationLock);
        certificationReceiptStore = new PluginCertificationReceiptStore(localHome);
        packageMutationService = new PluginPackageMutationService(
                localHome,
                pluginsDirectory,
                packageRepository
        );
        try {
            Files.createDirectories(pluginsDirectory);
            Files.createDirectories(pluginPackageDirectory);
            Files.createDirectories(pluginStorageDirectory);
        } catch (IOException exception) {
            LOG.error("Failed to create plugin directories", exception);
        }
        if (!recoverBatchTransaction()) {
            LOG.error("Plugin batch recovery is incomplete; discovery will retry before loading plugins");
        }
        runtimeTrustGuard = explicitRuntimeTrustGuard == null
                ? PluginRuntimeTrustGuard.inactive()
                : explicitRuntimeTrustGuard;
        permissionService = new PluginPermissionService(
                localHome.resolve("plugin-permissions.json"),
                artifactResolver::findCurrentPermissionArtifact,
                mutationLock
        );
        reusePolicy = new PluginReusePolicy(packageRepository, permissionService, PluginManager::isLauncherCompatible);
        stateStore.load(enabledStates, pendingUninstall);
        loaders.put(PluginManifest.PluginType.JAVA, new JavaPluginLoader());
        loaders.put(PluginManifest.PluginType.KOTLIN, new JavaPluginLoader());
    }

    /// Returns the process-wide plugin manager.
    /// @return plugin manager singleton
    public static PluginManager getInstance() {
        return PluginManagerHolder.INSTANCE;
    }

    /// Persists plugin state through the dedicated shared-lock state store.
    private void saveStates() {
        stateStore.save(enabledStates, pendingUninstall);
    }

    /// Recovers the package journal while excluding concurrent launcher mutations.
    ///
    /// @return whether no unresolved package transaction remains
    private boolean recoverBatchTransaction() {
        try {
            return mutationLock.call(packageMutationService::recover);
        } catch (IOException exception) {
            LOG.error("Failed to acquire the plugin mutation lock for transaction recovery", exception);
            return false;
        }
    }

    /// Discovers packages, applies pending removals, loads dependencies first, and restores enablement state.
    public void discoverPlugins() {
        administrativeGuard.checkTrustedCaller();
        LOG.info("Discovering plugins...");
        try {
            mutationLock.run(this::discoverPluginsLocked);
        } catch (IOException | RuntimeException | Error exception) {
            LOG.error("Failed to discover plugins", exception);
        }
    }

    /// Performs one complete discovery pass under the shared package, state, and permission lock.
    ///
    /// Holding the lock through lifecycle construction keeps the final permission snapshot and package identity
    /// unchanged between policy evaluation and the first plugin callback.
    ///
    /// @throws IOException if package recovery, permission reload, or package discovery fails
    private void discoverPluginsLocked() throws IOException {
        runtimeState.clear();
        if (!packageMutationService.recover()) {
            LOG.error("Cannot discover plugins while batch-install recovery is incomplete");
            return;
        }
        try {
            permissionService.reload();
        } catch (IOException exception) {
            LOG.error("Cannot reload plugin permissions after transaction recovery", exception);
            return;
        }

        stateStore.load(enabledStates, pendingUninstall);
        Map<String, PluginPackageCandidate> candidates = readCandidates();
        applyPendingUninstalls(candidates);
        reconcileLoadedContainers(candidates);
        try {
            retainInstalledPermissionArtifacts(candidates);
        } catch (IOException exception) {
            LOG.warning(
                    "Failed to prune stale plugin permission decisions; exact artifact binding remains fail-closed",
                    exception
            );
        }

        Map<String, PluginVisitState> visitStates = new HashMap<>();
        Set<String> failed = new HashSet<>();
        for (PluginPackageCandidate candidate : candidates.values()) {
            if (candidate.manifest.getSchemaVersion() != PluginManifest.CURRENT_SCHEMA_VERSION) {
                enabledStates.remove(candidate.manifest.getId());
                setRuntimeStatus(
                        candidate.identity,
                        PluginRuntimeStatus.BLOCKED_LEGACY,
                        "Plugin " + candidate.manifest.getId() + " uses legacy manifest schema "
                                + candidate.manifest.getSchemaVersion() + " and cannot execute"
                );
                continue;
            }
            if (enabledStates.contains(candidate.manifest.getId())) {
                loadCandidate(candidate, candidates, visitStates, failed);
            } else {
                setRuntimeStatus(candidate.identity, PluginRuntimeStatus.INSTALLED_DISABLED, null);
            }
        }
        saveStates();
        LOG.info("Discovered " + plugins.size() + " plugin(s)");
    }

    /// Reconciles process-local containers with the exact package set and persisted enablement read for this pass.
    ///
    /// Missing packages are unloaded, exact disabled artifacts are stopped, and replacements remain on their old
    /// in-process code until restart while the newly published artifact is reported as waiting for restart.
    ///
    /// @param candidates exact packages published for the next launcher start
    /// @throws IOException if installed dependency manifests cannot be read while stopping stale containers
    private void reconcileLoadedContainers(Map<String, PluginPackageCandidate> candidates) throws IOException {
        for (PluginContainer container : List.copyOf(plugins)) {
            String pluginId = container.getManifest().getId();
            @Nullable PluginPackageCandidate candidate = candidates.get(pluginId);
            if (candidate == null) {
                unloadPluginLocked(pluginId);
                continue;
            }

            PluginArtifactIdentity loadedIdentity = PluginArtifactIdentity.of(
                    container.getManifest(),
                    container.getContext().getArtifactSha256()
            );
            if (!candidate.identity.equals(loadedIdentity)) {
                setRuntimeStatus(candidate.identity, PluginRuntimeStatus.WAITING_FOR_RESTART, null);
                if (!enabledStates.contains(pluginId) && container.isEnabled()) {
                    disablePluginLocked(pluginId);
                }
                continue;
            }
            if (!enabledStates.contains(pluginId) && container.isEnabled()) {
                disablePluginLocked(pluginId);
            }
        }
    }

    /// Reads and validates every package manifest, rejecting duplicate IDs deterministically.
    /// @return package candidates indexed by ID
    /// @throws IOException if the plugin directory cannot be listed
    private Map<String, PluginPackageCandidate> readCandidates() throws IOException {
        Map<String, PluginPackageCandidate> candidates = new LinkedHashMap<>();
        try (Stream<Path> files = Files.list(pluginsDirectory)) {
            for (Path nplFile : files
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> !Files.isSymbolicLink(path))
                    .filter(path -> path.getFileName().toString()
                            .toLowerCase(java.util.Locale.ROOT).endsWith(".npl"))
                    .sorted()
                    .toList()) {
                try {
                    PluginManifest manifest = packageRepository.readManifest(nplFile);
                    String sha256 = PluginPackageVersions.calculateSha256(nplFile);
                    PluginArtifactIdentity identity = PluginArtifactIdentity.of(manifest, sha256);
                    @Nullable PluginPackageCandidate previous = candidates.putIfAbsent(
                            manifest.getId(),
                            new PluginPackageCandidate(
                                    nplFile,
                                    manifest,
                                    identity
                            )
                    );
                    if (previous != null) {
                        LOG.error("Duplicate plugin ID " + manifest.getId() + " in "
                                + previous.nplFile.getFileName() + " and " + nplFile.getFileName());
                    } else {
                        runtimeState.remember(identity);
                    }
                } catch (IOException | RuntimeException exception) {
                    LOG.error("Invalid plugin package: " + nplFile.getFileName(), exception);
                }
            }
        }
        return candidates;
    }

    /// Removes packages and data marked for uninstall before any plugin classes are loaded.
    /// @param candidates mutable package candidates
    private void applyPendingUninstalls(Map<String, PluginPackageCandidate> candidates) {
        for (String pluginId : List.copyOf(pendingUninstall)) {
            @Unmodifiable List<String> blockingDependents = candidates.values().stream()
                    .map(candidate -> candidate.manifest)
                    .filter(manifest -> !manifest.getId().equals(pluginId))
                    .filter(manifest -> !pendingUninstall.contains(manifest.getId()))
                    .filter(manifest -> manifest.getSchemaVersion()
                            >= PluginManifest.MIN_EXECUTABLE_SCHEMA_VERSION)
                    .filter(manifest -> manifest.getDependencies().contains(pluginId))
                    .map(PluginManifest::getId)
                    .sorted()
                    .toList();
            if (!blockingDependents.isEmpty()) {
                LOG.warning("Cannot complete pending uninstall of " + pluginId
                        + " because installed plugins depend on it: " + blockingDependents);
                continue;
            }
            try {
                Set<String> nextEnabledStates = new HashSet<>(enabledStates);
                Set<String> nextPendingUninstall = new HashSet<>(pendingUninstall);
                nextEnabledStates.remove(pluginId);
                nextPendingUninstall.remove(pluginId);
                @Unmodifiable List<Path> installedPackages = packageRepository.findInstalledPackages(pluginId);
                packageMutationService.publishRemoval(
                        installedPackages,
                        pluginId,
                        () -> {
                            permissionService.removePlugin(pluginId);
                            certificationReceiptStore.removePlugin(pluginId);
                            stateStore.saveStrict(nextEnabledStates, nextPendingUninstall);
                        },
                        () -> {
                            permissionService.reload();
                            stateStore.load(enabledStates, pendingUninstall);
                        }
                );

                enabledStates.clear();
                enabledStates.addAll(nextEnabledStates);
                pendingUninstall.clear();
                pendingUninstall.addAll(nextPendingUninstall);
                candidates.remove(pluginId);
                clearArtifactState(pluginId);
                LOG.info("Uninstalled plugin marked for removal: " + pluginId);
            } catch (IOException exception) {
                LOG.warning("Failed to complete pending plugin uninstall: " + pluginId, exception);
            }
        }
    }

    /// Removes permission decisions that do not belong to an installed or currently loaded artifact.
    /// @param candidates installed package candidates selected for discovery
    /// @throws IOException if stale decisions cannot be removed atomically
    private void retainInstalledPermissionArtifacts(
            Map<String, PluginPackageCandidate> candidates
    ) throws IOException {
        Set<PluginPermissionStore.Artifact> retainedArtifacts = new HashSet<>();
        candidates.values().stream()
                .map(candidate -> permissionService.artifact(
                        candidate.manifest,
                        candidate.identity.getSha256()
                ))
                .forEach(retainedArtifacts::add);
        for (PluginContainer container : plugins) {
            retainedArtifacts.add(permissionService.artifact(
                    container.getManifest(),
                    container.getContext().getArtifactSha256()
            ));
        }
        permissionService.retainArtifacts(retainedArtifacts);
    }

    /// Loads a candidate after recursively loading all declared dependencies.
    /// @param candidate candidate to load
    /// @param candidates available candidates
    /// @param visitStates dependency traversal states
    /// @param failed plugin IDs that cannot be loaded
    /// @return whether the candidate loaded and enabled successfully
    private boolean loadCandidate(
            PluginPackageCandidate candidate,
            Map<String, PluginPackageCandidate> candidates,
            Map<String, PluginVisitState> visitStates,
            Set<String> failed
    ) {
        String pluginId = candidate.manifest.getId();
        if (failed.contains(pluginId)) {
            return false;
        }
        @Nullable PluginContainer existing = pluginMap.get(pluginId);
        if (existing != null) {
            PluginArtifactIdentity loadedIdentity = PluginArtifactIdentity.of(
                    existing.getManifest(),
                    existing.getContext().getArtifactSha256()
            );
            if (!candidate.identity.equals(loadedIdentity)) {
                setRuntimeStatus(candidate.identity, PluginRuntimeStatus.WAITING_FOR_RESTART, null);
                failed.add(pluginId);
                return false;
            }
            if (existing.isEnabled()) {
                return true;
            }
            if (!enablePlugin(pluginId, new HashSet<>())) {
                failed.add(pluginId);
                return false;
            }
            return true;
        }

        @Nullable PluginVisitState state = visitStates.get(pluginId);
        if (state == PluginVisitState.VISITING) {
            String message = "Cyclic plugin dependency detected at " + pluginId;
            LOG.error(message);
            setRuntimeStatus(candidate.identity, PluginRuntimeStatus.LOAD_FAILED, message);
            failed.add(pluginId);
            return false;
        }
        if (state == PluginVisitState.VISITED) {
            return !failed.contains(pluginId);
        }

        @Nullable PluginRuntimeStatus blockedStatus = getPreLoadBlock(candidate);
        if (blockedStatus != null) {
            failed.add(pluginId);
            visitStates.put(pluginId, PluginVisitState.VISITED);
            return false;
        }

        visitStates.put(pluginId, PluginVisitState.VISITING);
        for (PluginDependency declaredDependency : candidate.manifest.getPluginDependencies()) {
            String dependencyId = declaredDependency.getId();
            @Nullable PluginPackageCandidate dependency = candidates.get(dependencyId);
            @Nullable PluginContainer loadedDependency = pluginMap.get(dependencyId);
            if (dependency == null && loadedDependency == null) {
                String message = "Plugin " + pluginId + " requires missing dependency " + dependencyId;
                LOG.error(message);
                setRuntimeStatus(candidate.identity, PluginRuntimeStatus.LOAD_FAILED, message);
                failed.add(pluginId);
                visitStates.put(pluginId, PluginVisitState.VISITED);
                return false;
            }
            if (dependency != null && !enabledStates.contains(dependencyId)) {
                String message = "Plugin " + pluginId + " requires disabled dependency " + dependencyId;
                LOG.error(message);
                setRuntimeStatus(candidate.identity, PluginRuntimeStatus.LOAD_FAILED, message);
                failed.add(pluginId);
                visitStates.put(pluginId, PluginVisitState.VISITED);
                return false;
            }
            if (dependency != null
                    && !loadCandidate(dependency, candidates, visitStates, failed)) {
                String message = "Plugin " + pluginId + " cannot load because dependency "
                        + dependencyId + " failed";
                LOG.error(message);
                setRuntimeStatus(candidate.identity, PluginRuntimeStatus.LOAD_FAILED, message);
                failed.add(pluginId);
                visitStates.put(pluginId, PluginVisitState.VISITED);
                return false;
            }
            String dependencyVersion = dependency != null
                    ? dependency.manifest.getVersion()
                    : Objects.requireNonNull(loadedDependency).getManifest().getVersion();
            if (!declaredDependency.matchesVersion(dependencyVersion)) {
                String message = "Plugin " + pluginId + " requires dependency " + dependencyId + " "
                        + declaredDependency.getVersion() + " but found " + dependencyVersion;
                LOG.error(message);
                setRuntimeStatus(candidate.identity, PluginRuntimeStatus.LOAD_FAILED, message);
                failed.add(pluginId);
                visitStates.put(pluginId, PluginVisitState.VISITED);
                return false;
            }
        }

        try {
            loadPlugin(candidate);
            if (!enablePlugin(pluginId, new HashSet<>())) {
                failed.add(pluginId);
            }
        } catch (IOException | RuntimeException | Error exception) {
            failed.add(pluginId);
            @Nullable String failureMessage = exception.getMessage();
            setRuntimeStatus(
                    candidate.identity,
                    PluginRuntimeStatus.LOAD_FAILED,
                    failureMessage == null || failureMessage.isBlank() ? exception.toString() : failureMessage
            );
            LOG.error("Failed to load plugin: " + candidate.nplFile.getFileName(), exception);
        }
        visitStates.put(pluginId, PluginVisitState.VISITED);
        return !failed.contains(pluginId);
    }

    /// Returns the fail-closed policy state that must prevent any class loading for one candidate.
    ///
    /// @param candidate exact installed package candidate
    /// @return blocking status or `null` when lifecycle preparation may continue
    private @Nullable PluginRuntimeStatus getPreLoadBlock(PluginPackageCandidate candidate) {
        PluginManifest manifest = candidate.manifest;
        if (manifest.getSchemaVersion() < PluginManifest.MIN_EXECUTABLE_SCHEMA_VERSION) {
            String detail = "Plugin " + manifest.getId() + " uses legacy manifest schema "
                    + manifest.getSchemaVersion() + " and cannot execute";
            setRuntimeStatus(candidate.identity, PluginRuntimeStatus.BLOCKED_LEGACY, detail);
            return PluginRuntimeStatus.BLOCKED_LEGACY;
        }
        if (!PluginManifest.isCanonicalExecutableId(manifest.getId())) {
            String detail = "Plugin " + manifest.getId()
                    + " does not use a portable canonical lower-case ID";
            setRuntimeStatus(candidate.identity, PluginRuntimeStatus.LOAD_FAILED, detail);
            return PluginRuntimeStatus.LOAD_FAILED;
        }
        if (!isLauncherCompatible(manifest)) {
            String detail = "Plugin " + manifest.getId() + " requires launcher version "
                    + manifest.getLauncherVersion() + " but this launcher is " + Metadata.VERSION;
            setRuntimeStatus(candidate.identity, PluginRuntimeStatus.LOAD_FAILED, detail);
            return PluginRuntimeStatus.LOAD_FAILED;
        }
        final @Nullable String trustBlock;
        try {
            trustBlock = runtimeTrustGuard.getBlockReason(
                    candidate.identity,
                    Files.size(candidate.nplFile)
            );
        } catch (IOException exception) {
            String detail = "Plugin " + manifest.getId() + " package size could not be verified";
            setRuntimeStatus(candidate.identity, PluginRuntimeStatus.LOAD_FAILED, detail);
            return PluginRuntimeStatus.LOAD_FAILED;
        }
        if (trustBlock != null) {
            setRuntimeStatus(candidate.identity, PluginRuntimeStatus.BLOCKED_REVOKED, trustBlock);
            return PluginRuntimeStatus.BLOCKED_REVOKED;
        }
        @Unmodifiable Set<PluginPermission> granted = permissionService.getGrantedPermissions(
                manifest,
                candidate.identity.getSha256()
        );
        if (!granted.containsAll(manifest.getRequiredPermissions())) {
            EnumSet<PluginPermission> denied = EnumSet.noneOf(PluginPermission.class);
            denied.addAll(manifest.getRequiredPermissions());
            denied.removeAll(granted);
            String detail = "Plugin " + manifest.getId() + " cannot run until every required permission is granted: "
                    + denied.stream().map(PluginPermission::getId).sorted().toList();
            setRuntimeStatus(candidate.identity, PluginRuntimeStatus.BLOCKED_PERMISSION, detail);
            return PluginRuntimeStatus.BLOCKED_PERMISSION;
        }
        if (!manifest.hasMixins()) {
            return null;
        }
        if (!manifest.isPermissionRequired(PluginPermission.MIXIN)) {
            String detail = "Plugin " + manifest.getId() + " declares Mixins without required permission mixin";
            setRuntimeStatus(candidate.identity, PluginRuntimeStatus.LOAD_FAILED, detail);
            return PluginRuntimeStatus.LOAD_FAILED;
        }

        String mixinDigest = PluginAgentSnapshot.calculateMixinConfigurationDigest(manifest.getMixins());
        if (!PluginAgentSnapshot.current().confirms(candidate.identity, mixinDigest)) {
            String detail = "The active Mixin Agent did not confirm exact artifact " + candidate.identity;
            setRuntimeStatus(candidate.identity, PluginRuntimeStatus.BLOCKED_AGENT, detail);
            return PluginRuntimeStatus.BLOCKED_AGENT;
        }
        return null;
    }

    /// Records one artifact-bound runtime status and optional diagnostic.
    ///
    /// @param identity exact artifact
    /// @param status authoritative runtime status
    /// @param detail diagnostic or `null`
    private void setRuntimeStatus(
            PluginArtifactIdentity identity,
            PluginRuntimeStatus status,
            @Nullable String detail
    ) {
        runtimeState.set(identity, status, detail);
    }

    /// Removes every runtime identity and diagnostic belonging to one plugin ID.
    ///
    /// @param pluginId plugin ID
    private void clearArtifactState(String pluginId) {
        runtimeState.removePlugin(pluginId);
    }

    /// Extracts, loads, registers, and invokes `onLoad` for a plugin package.
    /// This method mutates the observable plugin list and must run on the JavaFX thread.
    ///
    /// @param candidate exact installed package candidate
    /// @return registered plugin container
    /// @throws IOException if preparation or registration fails
    private PluginContainer loadPlugin(PluginPackageCandidate candidate) throws IOException {
        return registerPreparedPlugin(preparePluginInternal(candidate));
    }

    /// Performs compatibility checks, verified extraction, and lifecycle class loading.
    ///
    /// @param candidate exact package candidate that already passed runtime policy
    /// @return prepared plugin value
    /// @throws IOException if preparation fails
    private PreparedPlugin preparePluginInternal(PluginPackageCandidate candidate) throws IOException {
        Path nplFile = candidate.nplFile;
        LOG.info("Preparing plugin: " + nplFile.getFileName());
        String artifactSha256 = candidate.identity.getSha256();
        PluginPackageMutationService.verifyPackageHash(nplFile, artifactSha256);
        PluginManifest manifest = candidate.manifest;
        String pluginId = manifest.getId();

        if (pluginMap.containsKey(pluginId)) {
            throw new IOException("Plugin already loaded: " + pluginId);
        }
        if (!isLauncherCompatible(manifest)) {
            throw new IOException("Plugin " + pluginId + " requires launcher version "
                    + manifest.getLauncherVersion() + " but this launcher is " + Metadata.VERSION);
        }
        for (PluginDependency dependency : manifest.getPluginDependencies()) {
            @Nullable PluginContainer dependencyContainer = pluginMap.get(dependency.getId());
            if (dependencyContainer == null) {
                throw new IOException("Plugin " + pluginId + " requires loaded dependency "
                        + dependency.getId());
            }
            String installedVersion = dependencyContainer.getManifest().getVersion();
            if (!dependency.matchesVersion(installedVersion)) {
                throw new IOException("Plugin " + pluginId + " requires dependency " + dependency.getId()
                        + " " + dependency.getVersion() + " but found " + installedVersion);
            }
        }

        VerifiedPluginPackage pluginPackage = PluginPackageVersions.prepareVerifiedLifecyclePackage(
                nplFile,
                pluginPackageDirectory,
                candidate.identity
        );
        candidate.verifySnapshotManifest(pluginPackage);
        PluginPackageMutationService.verifyPackageHash(nplFile, artifactSha256);
        if (!artifactSha256.equals(PluginPackageVersions.calculateSha256(nplFile))) {
            throw new IOException("Plugin package changed while it was being prepared: " + nplFile);
        }
        for (String mixinConfig : manifest.getMixins()) {
            if (!pluginPackage.containsResource(mixinConfig)) {
                throw new IOException("Mixin configuration resource not found: " + mixinConfig);
            }
        }
        pluginPackage.verifyIntegrity();
        Path dataDirectory = pluginStorageDirectory.resolve(pluginId);
        Files.createDirectories(dataDirectory);

        @Nullable PluginLoader loader = loaders.get(manifest.getType());
        if (loader == null) {
            throw new IOException("No loader found for plugin type: " + manifest.getType());
        }

        Plugin plugin = administrativeGuard.callPluginLoadingCallback(
                () -> loader.load(manifest, pluginPackage, nplFile)
        );
        ClassLoader classLoader = plugin.getClass().getClassLoader();

        PluginContext context = new PluginContext(
                manifest,
                pluginPackage.getDirectory(),
                dataDirectory,
                classLoader,
                artifactSha256,
                () -> permissionService.getGrantedPermissions(manifest, artifactSha256)
        );
        return new PreparedPlugin(
                plugin,
                context,
                manifest,
                nplFile
        );
    }

    /// Returns whether the current launcher version satisfies one manifest's normalized version constraint.
    ///
    /// @param manifest plugin manifest to check
    /// @return whether the launcher is compatible
    private static boolean isLauncherCompatible(PluginManifest manifest) {
        return manifest.matchesLauncherVersion(Metadata.VERSION);
    }

    /// Closes a dedicated plugin loader after preparation fails.
    ///
    /// @param plugin partially loaded plugin instance
    /// @param classLoader class loader that defined the plugin
    private void closeLoaderAfterFailure(Plugin plugin, ClassLoader classLoader) {
        try {
            runPluginCallback(classLoader, plugin::onUnload);
        } catch (RuntimeException | Error exception) {
            LOG.warning("Plugin cleanup failed after preparation error", exception);
        }
        if (classLoader != PluginManager.class.getClassLoader()
                && classLoader instanceof java.net.URLClassLoader urlClassLoader) {
            try {
                urlClassLoader.close();
            } catch (IOException exception) {
                LOG.warning("Failed to close plugin class loader after preparation error", exception);
            }
        }
    }

    /// Runs one lifecycle callback with administrative APIs denied and the exact plugin loader installed as TCCL.
    ///
    /// @param classLoader loader that owns the plugin lifecycle and resources
    /// @param callback plugin-owned lifecycle callback
    private void runPluginCallback(ClassLoader classLoader, Runnable callback) {
        administrativeGuard.runPluginCallback(() ->
                JavaPluginLoader.runWithPluginContextClassLoader(classLoader, callback));
    }

    /// Registers a prepared plugin and invokes `onLoad` on the JavaFX thread.
    ///
    /// @param prepared prepared plugin value
    /// @return registered container
    public PluginContainer registerPreparedPlugin(PreparedPlugin prepared) {
        FXUtils.checkFxUserThread();
        administrativeGuard.checkTrustedCaller();
        String pluginId = prepared.manifest.getId();
        stateLock.readLock().lock();
        try {
            if (pluginMap.containsKey(pluginId)) {
                IllegalStateException exception = new IllegalStateException("Plugin already loaded: " + pluginId);
                closeLoaderAfterFailure(prepared.plugin, prepared.context.getClassLoader());
                throw exception;
            }
        } finally {
            stateLock.readLock().unlock();
        }

        PluginContainer container = new PluginContainer(prepared.plugin, prepared.context, prepared.nplFile);
        stateLock.writeLock().lock();
        try {
            plugins.add(container);
            pluginMap.put(pluginId, container);
        } finally {
            stateLock.writeLock().unlock();
        }
        try {
            runPluginCallback(
                    prepared.context.getClassLoader(),
                    () -> prepared.plugin.onLoad(prepared.context)
            );
            LOG.info("Loaded plugin: " + prepared.manifest.getName() + " v" + prepared.manifest.getVersion());
            return container;
        } catch (RuntimeException | Error exception) {
            stateLock.writeLock().lock();
            try {
                plugins.remove(container);
                pluginMap.remove(pluginId);
            } finally {
                stateLock.writeLock().unlock();
            }
            try {
                PluginUIRegistry.unregisterAll(pluginId);
            } catch (RuntimeException | Error cleanupException) {
                exception.addSuppressed(cleanupException);
            }
            try {
                runPluginCallback(prepared.context.getClassLoader(), prepared.plugin::onUnload);
            } catch (RuntimeException | Error unloadException) {
                exception.addSuppressed(unloadException);
            }
            try {
                container.closeClassLoader();
            } catch (IOException closeException) {
                exception.addSuppressed(closeException);
            }
            throw exception;
        }
    }

    /// Enables a plugin and its dependencies, or records a restart-pending Mixin enablement.
    ///
    /// @param pluginId plugin ID
    /// @return whether the plugin lifecycle is active now
    public boolean enablePlugin(String pluginId) {
        administrativeGuard.checkTrustedCaller();
        try {
            return mutationLock.call(() -> {
                stateStore.load(enabledStates, pendingUninstall);
                @Unmodifiable Map<String, PluginManifest> installedManifests =
                        packageRepository.readInstalledManifests(plugins);
                @Nullable PluginManifest requestedManifest = installedManifests.get(pluginId);
                if (requestedManifest != null
                        && requestedManifest.getSchemaVersion() != PluginManifest.CURRENT_SCHEMA_VERSION) {
                    enabledStates.remove(pluginId);
                    @Nullable PluginArtifactIdentity identity = artifactResolver.resolveInstalledIdentity(pluginId);
                    if (identity != null) {
                        setRuntimeStatus(
                                identity,
                                PluginRuntimeStatus.BLOCKED_LEGACY,
                                "Plugin " + pluginId + " uses legacy manifest schema "
                                        + requestedManifest.getSchemaVersion() + " and cannot execute"
                        );
                    }
                    saveStates();
                    return false;
                }
                recordEnableIntent(pluginId, installedManifests, new HashSet<>());
                boolean enabled = enablePlugin(pluginId, new HashSet<>());
                saveStates();
                return enabled;
            });
        } catch (IOException exception) {
            LOG.warning("Cannot persist plugin enablement for " + pluginId, exception);
            return false;
        }
    }

    /// Records desired enablement for one installed plugin and its executable dependency closure.
    ///
    /// This operation does not claim that lifecycle activation succeeded. It only ensures that a restart-pending or
    /// currently blocked dependency is not left persistently disabled when the user enables its dependent.
    ///
    /// @param pluginId plugin whose enablement was requested
    /// @param installedManifests immutable installed manifests indexed by plugin ID
    /// @param visited IDs whose dependency closure has already been recorded
    private void recordEnableIntent(
            String pluginId,
            @Unmodifiable Map<String, PluginManifest> installedManifests,
            Set<String> visited
    ) {
        if (!visited.add(pluginId)) {
            return;
        }
        @Nullable PluginManifest manifest = installedManifests.get(pluginId);
        if (manifest == null) {
            return;
        }
        if (manifest.getSchemaVersion() != PluginManifest.CURRENT_SCHEMA_VERSION) {
            return;
        }
        enabledStates.add(pluginId);
        pendingUninstall.remove(pluginId);
        for (PluginDependency dependency : manifest.getPluginDependencies()) {
            @Nullable PluginManifest dependencyManifest = installedManifests.get(dependency.getId());
            if (dependencyManifest != null
                    && dependencyManifest.getSchemaVersion() >= PluginManifest.MIN_EXECUTABLE_SCHEMA_VERSION) {
                recordEnableIntent(dependency.getId(), installedManifests, visited);
            }
        }
    }

    /// Recursively enables one plugin while detecting ucepected runtime dependency cycles.
    ///
    /// @param pluginId plugin ID
    /// @param visiting IDs in the current enable traversal
    /// @return whether the lifecycle is active now
    private boolean enablePlugin(String pluginId, Set<String> visiting) {
        @Nullable PluginContainer container = pluginMap.get(pluginId);
        if (container == null) {
            @Nullable PluginArtifactIdentity installedIdentity = artifactResolver.resolveInstalledIdentity(pluginId);
            if (installedIdentity != null) {
                enabledStates.add(pluginId);
                pendingUninstall.remove(pluginId);
                @Nullable PluginRuntimeStatus existingStatus = runtimeState.getStatus(installedIdentity);
                if (existingStatus == null
                        || existingStatus == PluginRuntimeStatus.INSTALLED_DISABLED
                        || existingStatus == PluginRuntimeStatus.ENABLED) {
                    setRuntimeStatus(installedIdentity, PluginRuntimeStatus.WAITING_FOR_RESTART, null);
                }
                LOG.info("Plugin " + pluginId + " will enable after restart");
                return false;
            }
            LOG.error("Cannot enable missing plugin: " + pluginId);
            return false;
        }
        enabledStates.add(pluginId);
        pendingUninstall.remove(pluginId);
        if (container.isEnabled()) {
            return true;
        }
        if (!visiting.add(pluginId)) {
            String message = "Cyclic plugin enablement detected at " + pluginId;
            setLoadedRuntimeStatus(container, PluginRuntimeStatus.LOAD_FAILED, message);
            LOG.error(message);
            return false;
        }

        for (PluginDependency dependency : container.getManifest().getPluginDependencies()) {
            @Nullable PluginContainer dependencyContainer = pluginMap.get(dependency.getId());
            if (dependencyContainer == null
                    || !dependency.matchesVersion(dependencyContainer.getManifest().getVersion())) {
                String message = "Cannot enable plugin " + pluginId + " because dependency " + dependency.getId()
                        + " does not satisfy " + dependency.getVersion();
                setLoadedRuntimeStatus(container, PluginRuntimeStatus.LOAD_FAILED, message);
                LOG.error(message);
                visiting.remove(pluginId);
                return false;
            }
            if (!enablePlugin(dependency.getId(), visiting)) {
                PluginRuntimeStatus dependencyStatus = getPluginRuntimeStatus(dependency.getId());
                @Nullable String dependencyDetail = getPluginRuntimeDetail(dependency.getId());
                boolean waitingForRestart = dependencyStatus == PluginRuntimeStatus.WAITING_FOR_RESTART;
                String message = "Cannot enable plugin " + pluginId + " because dependency " + dependency.getId()
                        + " is " + dependencyStatus
                        + (dependencyDetail == null || dependencyDetail.isBlank()
                        ? ""
                        : ": " + dependencyDetail);
                setLoadedRuntimeStatus(
                        container,
                        waitingForRestart
                                ? PluginRuntimeStatus.WAITING_FOR_RESTART
                                : PluginRuntimeStatus.LOAD_FAILED,
                        message
                );
                container.setRestartRequired(waitingForRestart);
                LOG.error(message);
                visiting.remove(pluginId);
                return false;
            }
        }

        if (container.getManifest().hasMixins()
                && container.getContext().getGrantedPermissions().contains(PluginPermission.MIXIN)
                && !isMixinActive(pluginId)) {
            enabledStates.add(pluginId);
            container.setRestartRequired(true);
            setLoadedRuntimeStatus(
                    container,
                    PluginRuntimeStatus.WAITING_FOR_RESTART,
                    "Plugin " + pluginId + " requires a restart before its Mixins can activate"
            );
            LOG.info("Plugin " + pluginId + " will enable after restart so its Mixins can be applied");
            visiting.remove(pluginId);
            return false;
        }

        try {
            runPluginCallback(
                    container.getContext().getClassLoader(),
                    container.getPlugin()::onEnable
            );
            container.setEnabled(true);
            container.setRestartRequired(false);
            enabledStates.add(pluginId);
            setLoadedRuntimeStatus(container, PluginRuntimeStatus.ENABLED, null);
            LOG.info("Enabled plugin: " + pluginId);
            visiting.remove(pluginId);
            return true;
        } catch (RuntimeException | Error exception) {
            @Nullable String message = exception.getMessage();
            setLoadedRuntimeStatus(
                    container,
                    PluginRuntimeStatus.LOAD_FAILED,
                    message == null || message.isBlank() ? exception.toString() : message
            );
            LOG.error("Failed to enable plugin: " + pluginId, exception);
            visiting.remove(pluginId);
            return false;
        }
    }

    /// Records one status and diagnostic against the exact artifact represented by a loaded container.
    ///
    /// @param container loaded lifecycle container
    /// @param status authoritative runtime status
    /// @param detail diagnostic detail or `null`
    private void setLoadedRuntimeStatus(
            PluginContainer container,
            PluginRuntimeStatus status,
            @Nullable String detail
    ) {
        setRuntimeStatus(
                PluginArtifactIdentity.of(
                        container.getManifest(),
                        container.getContext().getArtifactSha256()
                ),
                status,
                detail
        );
    }

    /// Disables dependents first, then disables the requested plugin.
    ///
    /// Active Mixin bytecode remains until restart and is reflected by `restartRequired`.
    ///
    /// @param pluginId plugin ID
    public void disablePlugin(String pluginId) {
        administrativeGuard.checkTrustedCaller();
        try {
            mutationLock.run(() -> {
                stateStore.load(enabledStates, pendingUninstall);
                disablePluginLocked(pluginId);
            });
        } catch (IOException exception) {
            LOG.warning("Cannot persist plugin disablement for " + pluginId, exception);
        }
    }

    /// Disables one plugin and its dependents while the shared mutation lock is held.
    ///
    /// @param pluginId plugin ID
    /// @throws IOException if the installed dependency graph cannot be read
    private void disablePluginLocked(String pluginId) throws IOException {
        @Unmodifiable Map<String, PluginManifest> installedManifests =
                packageRepository.readInstalledManifests(plugins);
        disablePluginLocked(pluginId, installedManifests, new HashSet<>());
        saveStates();
    }

    /// Disables one plugin after recursively clearing every executable dependent's desired enablement.
    ///
    /// The immutable installed graph covers plugins that failed before registration or are waiting for restart. Loaded
    /// manifests are also considered so an updated package cannot hide a dependency edge still active in this process.
    ///
    /// @param pluginId plugin ID to disable
    /// @param installedManifests immutable installed manifests indexed by plugin ID
    /// @param visited IDs already processed during reverse traversal
    private void disablePluginLocked(
            String pluginId,
            @Unmodifiable Map<String, PluginManifest> installedManifests,
            Set<String> visited
    ) {
        if (!visited.add(pluginId)) {
            return;
        }
        @Unmodifiable List<String> dependentIds = Stream.concat(
                        installedManifests.values().stream(),
                        plugins.stream().map(PluginContainer::getManifest)
                )
                .filter(manifest -> !manifest.getId().equals(pluginId))
                .filter(manifest -> manifest.getSchemaVersion()
                        >= PluginManifest.MIN_EXECUTABLE_SCHEMA_VERSION)
                .filter(manifest -> manifest.getDependencies().contains(pluginId))
                .map(PluginManifest::getId)
                .distinct()
                .sorted()
                .toList();
        for (String dependentId : dependentIds) {
            disablePluginLocked(dependentId, installedManifests, visited);
        }

        @Nullable PluginContainer container = pluginMap.get(pluginId);
        if (container == null) {
            if (enabledStates.remove(pluginId)) {
                LOG.info("Disabled failed or restart-pending plugin: " + pluginId);
            }
            @Nullable PluginArtifactIdentity installedIdentity = artifactResolver.resolveInstalledIdentity(pluginId);
            if (installedIdentity != null
                    && runtimeState.getStatus(installedIdentity) == PluginRuntimeStatus.WAITING_FOR_RESTART) {
                setRuntimeStatus(installedIdentity, PluginRuntimeStatus.INSTALLED_DISABLED, null);
            }
            return;
        }
        if (container.isEnabled()) {
            try {
                runPluginCallback(
                        container.getContext().getClassLoader(),
                        container.getPlugin()::onDisable
                );
                LOG.info("Disabled plugin: " + pluginId);
            } catch (RuntimeException | Error exception) {
                LOG.error("Failed to disable plugin: " + pluginId, exception);
            } finally {
                container.setEnabled(false);
                PluginUIRegistry.unregisterAll(pluginId);
            }
        }

        enabledStates.remove(pluginId);
        boolean restartRequired = container.getManifest().hasMixins() && isMixinActive(pluginId);
        container.setRestartRequired(restartRequired);
        setRuntimeStatus(
                PluginArtifactIdentity.of(
                        container.getManifest(),
                        container.getContext().getArtifactSha256()
                ),
                restartRequired
                        ? PluginRuntimeStatus.WAITING_FOR_RESTART
                        : PluginRuntimeStatus.INSTALLED_DISABLED,
                null
        );
    }

    /// Unloads dependents first, invokes lifecycle cleanup, and closes a dedicated class loader.
    ///
    /// @param pluginId plugin ID
    public void unloadPlugin(String pluginId) {
        administrativeGuard.checkTrustedCaller();
        try {
            mutationLock.run(() -> {
                stateStore.load(enabledStates, pendingUninstall);
                unloadPluginLocked(pluginId);
            });
        } catch (IOException exception) {
            LOG.warning("Cannot persist plugin unload state for " + pluginId, exception);
        }
    }

    /// Unloads one plugin and its dependents while the shared mutation lock is held.
    ///
    /// IMPORTANT: This method modifies JavaFX ObservableList and must execute on the JavaFX application thread.
    /// Background permission/uninstall operations should schedule lifecycle teardown via Schedulers.javafx().
    ///
    /// @param pluginId plugin ID
    /// @throws IOException if disabling an active lifecycle requires an unreadable installed dependency graph
    private void unloadPluginLocked(String pluginId) throws IOException {
        // TODO: Add FXUtils.checkFxUserThread() once background callers are refactored to schedule on FX thread
        stateLock.readLock().lock();
        List<PluginContainer> pluginsCopy;
        try {
            pluginsCopy = List.copyOf(plugins);
        } finally {
            stateLock.readLock().unlock();
        }
        
        for (PluginContainer dependent : pluginsCopy) {
            if (dependent.getManifest().getDependencies().contains(pluginId)) {
                unloadPluginLocked(dependent.getManifest().getId());
            }
        }

        stateLock.readLock().lock();
        @Nullable PluginContainer container;
        try {
            container = pluginMap.get(pluginId);
        } finally {
            stateLock.readLock().unlock();
        }
        
        if (container == null) {
            return;
        }
        if (container.isEnabled()) {
            disablePluginLocked(pluginId);
        }

        try {
            runPluginCallback(
                    container.getContext().getClassLoader(),
                    container.getPlugin()::onUnload
            );
        } catch (RuntimeException | Error exception) {
            LOG.warning("Plugin onUnload failed: " + pluginId, exception);
        } finally {
            stateLock.writeLock().lock();
            try {
                plugins.remove(container);
                pluginMap.remove(pluginId);
            } finally {
                stateLock.writeLock().unlock();
            }
            PluginUIRegistry.unregisterAll(pluginId);
            try {
                container.closeClassLoader();
            } catch (IOException exception) {
                LOG.warning("Failed to close plugin class loader: " + pluginId, exception);
            }
        }
        LOG.info("Unloaded plugin: " + pluginId);
    }

    /// Inspects a local package without copying, extracting, loading, or otherwise modifying launcher state.
    ///
    /// The returned SHA-256 digest binds the displayed manifest and permission confirmation to subsequent
    /// preparation. The old manifest is present when the same plugin ID is already installed or loaded.
    ///
    /// @param sourcePackage user-selected `.npl` package
    /// @return immutable package inspection
    /// @throws IOException if the package, manifest, compatibility, or digest is invalid
    public LocalPluginInspection inspectLocalPluginPackage(Path sourcePackage) throws IOException {
        Path source = sourcePackage.toAbsolutePath().normalize();
        PluginPackageRepository.validateLocalPackage(source);

        String initialSha256 = PluginPackageVersions.calculateSha256(source);
        PluginManifest manifest = packageRepository.readManifest(source);
        String verifiedSha256 = PluginPackageVersions.calculateSha256(source);
        if (!initialSha256.equals(verifiedSha256)) {
            throw new IOException("Plugin package changed while it was being inspected: " + source);
        }
        if (manifest.getSchemaVersion() != PluginManifest.CURRENT_SCHEMA_VERSION) {
            throw new IOException("Only plugin API v" + PluginManifest.CURRENT_SCHEMA_VERSION
                    + " packages can be installed; found schema " + manifest.getSchemaVersion());
        }
        if (!isLauncherCompatible(manifest)) {
            throw new IOException("Plugin " + manifest.getId() + " requires launcher version "
                    + manifest.getLauncherVersion() + " but this launcher is " + Metadata.VERSION);
        }
        if (manifest.getSchemaVersion() >= PluginManifest.MIN_EXECUTABLE_SCHEMA_VERSION
                && !PluginManifest.isCanonicalExecutableId(manifest.getId())) {
            throw new IOException("Executable plugin ID must be portable canonical lower-case text: "
                    + manifest.getId());
        }
        @Nullable PluginPermissionService.ResolvedArtifact priorArtifact = mutationLock.call(
                () -> artifactResolver.findCurrentPermissionArtifact(manifest.getId())
        );
        @Nullable PluginManifest oldManifest = priorArtifact == null ? null : priorArtifact.getManifest();
        @Nullable PluginArtifactIdentity priorIdentity = priorArtifact == null
                ? null
                : PluginArtifactIdentity.of(oldManifest, priorArtifact.getArtifact().getSha256());
        return new LocalPluginInspection(
                source,
                manifest,
                verifiedSha256,
                oldManifest,
                priorIdentity
        );
    }

    /// Returns the capabilities requested by the artifact published for the next launch.
    /// @param pluginId plugin ID to query
    /// @return immutable declared permission set
    /// @throws IOException if the plugin is absent or its installed package cannot be read
    public @Unmodifiable Set<PluginPermission> getDeclaredPermissions(String pluginId) throws IOException {
        return permissionService.getDeclaredPermissions(pluginId);
    }

    /// Returns the user grants stored for the artifact published for the next launch.
    /// When an update is waiting for restart, this returns the pending artifact decision. Already loaded plugin code
    /// continues querying its own exact artifact through [PluginContext].
    /// @param pluginId plugin ID to query
    /// @return immutable granted permission set
    /// @throws IOException if the plugin is absent or its installed package cannot be read
    public @Unmodifiable Set<PluginPermission> getGrantedPermissions(String pluginId) throws IOException {
        return permissionService.getGrantedPermissions(pluginId);
    }

    /// Replaces grants for the artifact published for restart and synchronizes any older loaded artifact.
    /// The published artifact receives the user's complete decision. If older code remains loaded, only revocations
    /// propagate to its existing grants; a newly granted capability never authorizes different bytes in the current
    /// process. Both exact records are persisted in one atomic document replacement.
    /// @param pluginId plugin ID to update
    /// @param grantedPermissions permissions explicitly granted from the developer's requests; schema-v4 required
    /// permissions must remain present
    /// @throws IOException if the plugin is absent or the decision cannot be persisted atomically
    public void setGrantedPermissions(
            String pluginId,
            Set<PluginPermission> grantedPermissions
    ) throws IOException {
        administrativeGuard.checkTrustedCaller();
        mutationLock.run(() -> setGrantedPermissionsLocked(pluginId, grantedPermissions));
    }

    /// Applies one permission decision while holding the shared mutation lock.
    ///
    /// @param pluginId plugin ID to update
    /// @param grantedPermissions permissions explicitly granted from the developer's requests; schema-v4 required
    /// permissions must remain present
    /// @throws IOException if the plugin is absent or persistence fails
    private void setGrantedPermissionsLocked(
            String pluginId,
            Set<PluginPermission> grantedPermissions
    ) throws IOException {
        stateStore.load(enabledStates, pendingUninstall);
        Objects.requireNonNull(grantedPermissions, "Granted permissions");
        @Nullable PluginPermissionService.ResolvedArtifact published =
                artifactResolver.findCurrentPermissionArtifact(pluginId);
        if (published == null) {
            throw new IOException("Plugin is not installed: " + pluginId);
        }
        @Nullable PluginPermissionService.ResolvedArtifact loaded =
                artifactResolver.findLoadedPermissionArtifact(pluginId);
        @Unmodifiable Set<PluginPermission> loadedBefore = loaded == null
                ? Set.of()
                : permissionService.getGrantedPermissions(
                        loaded.getManifest(),
                        loaded.getArtifact().getSha256()
                );
        boolean launcherUiBefore = loadedBefore.contains(PluginPermission.LAUNCHER_UI);
        @Unmodifiable Set<PluginPermission> loadedRequired = loaded == null
                ? Set.of()
                : Set.copyOf(loaded.getManifest().getRequiredPermissions());
        boolean activeArtifactHadRequiredPermissions = loaded != null
                && loadedBefore.containsAll(loadedRequired);
        boolean activeMixinRequiresRestart = loaded != null
                && loaded.getManifest().hasMixins()
                && isMixinActive(pluginId);

        Map<PluginPermissionService.ResolvedArtifact, Set<PluginPermission>> decisions = new LinkedHashMap<>();
        decisions.put(published, grantedPermissions);
        @Unmodifiable Set<PluginPermission> loadedDecision = Set.of();
        if (loaded != null && !loaded.getArtifact().equals(published.getArtifact())) {
            EnumSet<PluginPermission> compatibleDecision = EnumSet.noneOf(PluginPermission.class);
            if (loaded.getManifest().getSchemaVersion() >= 4) {
                compatibleDecision.addAll(loaded.getManifest().getRequiredPermissions());
            }
            for (PluginPermission permission : loadedBefore) {
                if (grantedPermissions.contains(permission)
                        && loaded.getManifest().declaresPermission(permission)) {
                    compatibleDecision.add(permission);
                }
            }
            loadedDecision = compatibleDecision.isEmpty()
                    ? Set.of()
                    : java.util.Collections.unmodifiableSet(compatibleDecision);
            decisions.put(loaded, loadedDecision);
        }
        permissionService.setGrantedPermissions(Map.copyOf(decisions));

        @Unmodifiable Set<PluginPermission> loadedAfter = loaded == null
                ? Set.of()
                : permissionService.getGrantedPermissions(
                        loaded.getManifest(),
                        loaded.getArtifact().getSha256()
                );
        @Unmodifiable Set<PluginPermission> publishedAfter = permissionService.getGrantedPermissions(
                published.getManifest(),
                published.getArtifact().getSha256()
        );
        PluginArtifactIdentity publishedIdentity = PluginArtifactIdentity.of(
                published.getManifest(),
                published.getArtifact().getSha256()
        );
        if (!publishedAfter.containsAll(published.getManifest().getRequiredPermissions())) {
            setRuntimeStatus(
                    publishedIdentity,
                    PluginRuntimeStatus.BLOCKED_PERMISSION,
                    "Every required plugin permission must be granted"
            );
        } else if (runtimeState.getStatus(publishedIdentity) == PluginRuntimeStatus.BLOCKED_PERMISSION) {
            setRuntimeStatus(publishedIdentity, PluginRuntimeStatus.WAITING_FOR_RESTART, null);
        }
        boolean launcherUiAfter = loadedAfter.contains(PluginPermission.LAUNCHER_UI);
        if (activeArtifactHadRequiredPermissions
                && loaded != null
                && !loadedAfter.containsAll(loadedRequired)) {
            @Unmodifiable Set<String> desiredEnablement = Set.copyOf(enabledStates);
            PluginArtifactIdentity loadedIdentity = PluginArtifactIdentity.of(
                    loaded.getManifest(),
                    loaded.getArtifact().getSha256()
            );
            unloadPluginLocked(pluginId);
            enabledStates.addAll(desiredEnablement);
            String detail = activeMixinRequiresRestart
                    ? "An active plugin required permission was revoked; lifecycle execution stopped, and a launcher "
                    + "restart is required to remove transformed bytecode"
                    : "An active plugin required permission was revoked; lifecycle execution stopped";
            setRuntimeStatus(loadedIdentity, PluginRuntimeStatus.BLOCKED_PERMISSION, detail);
            if (!publishedAfter.containsAll(published.getManifest().getRequiredPermissions())) {
                setRuntimeStatus(publishedIdentity, PluginRuntimeStatus.BLOCKED_PERMISSION, detail);
            } else if (!publishedIdentity.equals(loadedIdentity)) {
                setRuntimeStatus(publishedIdentity, PluginRuntimeStatus.WAITING_FOR_RESTART, detail);
            }
            saveStates();
            return;
        }
        if (launcherUiBefore && !launcherUiAfter) {
            PluginUIRegistry.unregisterAll(pluginId);
        }
    }

    /// Returns the stored user decision for the exact artifact represented by an inspection.
    /// @param inspection inspected package artifact
    /// @return immutable effective permission set, or an empty set when no decision exists
    public @Unmodifiable Set<PluginPermission> getGrantedPermissions(LocalPluginInspection inspection) {
        return permissionService.getGrantedPermissions(
                inspection.manifest,
                inspection.sha256
        );
    }

    /// Suggests initial toggle values for an installation permission prompt.
    /// New schema-v4 plugin IDs include their required permissions and deny every optional request; schema-v3 IDs
    /// still default to no grants. Every update carries forward only compatible optional grants from the currently
    /// installed artifact and includes target required permissions. Historical decisions belonging to the target
    /// artifact are deliberately ignored so an abandoned installation cannot pre-authorize a later prompt.
    /// @param inspection inspected target artifact
    /// @return immutable suggested grant set
    /// @throws IOException if the currently installed artifact cannot be inspected
    public @Unmodifiable Set<PluginPermission> getSuggestedGrantedPermissions(
            LocalPluginInspection inspection
    ) throws IOException {
        return permissionService.getSuggestedGrantedPermissions(
                inspection.manifest,
                inspection.oldManifest != null
        );
    }

    /// Validates and stages a user-selected local plugin package without caller-supplied grants.
    /// Every new installation and replacement is published for the next restart; no lifecycle class is loaded or
    /// registered in the current process.
    /// This package-private compatibility overload is fail-closed, never carries an old decision into an update, and
    /// rejects schema-v4 packages that require any capability.
    /// Production UI must use an overload accepting an explicit user decision.
    /// @param sourcePackage user-selected `.npl` package
    /// @return restart-staged installation result
    /// @throws IOException if validation, copying, permission persistence, or staging fails
    LocalPluginInstallation prepareLocalPluginInstallation(Path sourcePackage) throws IOException {
        administrativeGuard.checkTrustedCaller();
        LocalPluginInspection inspection = inspectLocalPluginPackage(sourcePackage);
        return prepareLocalPluginInstallation(inspection, Set.of());
    }

    /// Validates and prepares a local package with the user's explicit capability decisions.
    /// @param sourcePackage user-selected `.npl` package
    /// @param grantedPermissions permissions explicitly granted from the package's declared requests
    /// @return restart-staged installation result
    /// @throws IOException if validation, permission persistence, copying, or staging fails
    public LocalPluginInstallation prepareLocalPluginInstallation(
            Path sourcePackage,
            Set<PluginPermission> grantedPermissions
    ) throws IOException {
        administrativeGuard.checkTrustedCaller();
        return prepareLocalPluginInstallation(inspectLocalPluginPackage(sourcePackage), grantedPermissions);
    }

    /// Prepares a previously inspected package without caller-supplied grants.
    /// This package-private compatibility overload is fail-closed, never carries an old decision into an update, and
    /// rejects schema-v4 packages that require any capability.
    /// Production UI must use the overload accepting an explicit user decision.
    /// @param inspection read-only package inspection previously returned by [inspectLocalPluginPackage]
    /// @return restart-staged installation result
    /// @throws IOException if the package changed or installation validation fails
    LocalPluginInstallation prepareLocalPluginInstallation(LocalPluginInspection inspection) throws IOException {
        administrativeGuard.checkTrustedCaller();
        return prepareLocalPluginInstallation(inspection, Set.of());
    }

    /// Prepares a previously inspected package with the user's explicit capability decisions.
    /// Only permissions requested by the inspected manifest are accepted. The decision is bound to the inspected
    /// package version and SHA-256 before package publication, so the currently loaded artifact never receives a
    /// replacement artifact's grants.
    /// @param inspection read-only package inspection previously returned by [inspectLocalPluginPackage]
    /// @param grantedPermissions permissions explicitly granted from the package's declared requests
    /// @return restart-staged installation result
    /// @throws IOException if the package changed or installation and permission persistence fail
    public LocalPluginInstallation prepareLocalPluginInstallation(
            LocalPluginInspection inspection,
            Set<PluginPermission> grantedPermissions
    ) throws IOException {
        administrativeGuard.checkTrustedCaller();
        stagePluginInstallations(
                List.of(inspection),
                Map.of(inspection.manifest.getId(), Set.copyOf(grantedPermissions))
        );
        return LocalPluginInstallation.staged(inspection.manifest);
    }

    /// Returns every readable installed package manifest, including packages that failed or have not yet loaded.
    ///
    /// A replacement package waiting for restart takes precedence over old loaded classes so management and future
    /// installation planning consistently describe the artifact that will run next.
    ///
    /// @return immutable installed manifests indexed by plugin ID
    /// @throws IOException if the plugin directory cannot be listed
    public @Unmodifiable Map<String, PluginManifest> getInstalledManifests() throws IOException {
        return Map.copyOf(dependencyPlanner.readInstallPlanningManifests(List.copyOf(plugins), pendingUninstall));
    }

    /// Captures one atomic plugin-store planning snapshot under the shared mutation lock.
    ///
    /// The snapshot binds every planning manifest to the exact currently published or active artifact, and separately
    /// marks the subset eligible for dependency reuse. Installation confirmation and final publication must preserve
    /// this object so both replacement prior state and reused dependencies can be revalidated byte-for-byte.
    ///
    /// @return immutable manifests, exact current identities, and reusable identities from one locked snapshot
    /// @throws IOException if package, state, permission, manifest, or digest inspection fails
    public PluginInstallationPlanningSnapshot getInstallationPlanningSnapshot() throws IOException {
        return mutationLock.call(() -> {
            stateStore.load(enabledStates, pendingUninstall);
            @Unmodifiable Map<String, PluginManifest> manifests = Map.copyOf(
                    dependencyPlanner.readInstallPlanningManifests(plugins, pendingUninstall)
            );
            @Unmodifiable Map<String, PluginArtifactIdentity> artifacts =
                    installationStateGuard.resolvePlanningArtifactIdentities(manifests);
            Map<String, PluginArtifactIdentity> reusable = new LinkedHashMap<>();
            for (Map.Entry<String, PluginManifest> entry : manifests.entrySet()) {
                @Nullable PluginArtifactIdentity reusableIdentity = reusePolicy.resolveReusableIdentity(
                        entry.getKey(),
                        entry.getValue(),
                        enabledStates
                );
                if (reusableIdentity != null) {
                    PluginArtifactIdentity plannedIdentity = Objects.requireNonNull(artifacts.get(entry.getKey()));
                    if (!plannedIdentity.equals(reusableIdentity)) {
                        throw new IOException("Plugin artifact changed while the installation plan was captured: "
                                + entry.getKey());
                    }
                    reusable.put(entry.getKey(), reusableIdentity);
                }
            }
            return new PluginInstallationPlanningSnapshot(manifests, artifacts, Map.copyOf(reusable));
        });
    }

    /// Returns IDs from one installation-planning manifest snapshot whose exact current artifacts are safe to reuse.
    ///
    /// Reuse requires a non-pending, canonical executable and launcher-compatible manifest, byte-for-byte
    /// correspondence with the currently published artifact, and every required permission granted to that artifact's
    /// exact SHA-256 identity. The calculation runs under the shared package and permission lock so a store resolver
    /// never treats a version-only match as authorization.
    ///
    /// @param installedManifests immutable installation-planning manifest snapshot
    /// @return immutable IDs eligible for dependency reuse
    /// @throws IOException if package identity or permission state cannot be inspected
    public @Unmodifiable Set<String> getReusableInstalledPluginIds(
            @Unmodifiable Map<String, PluginManifest> installedManifests
    ) throws IOException {
        return Set.copyOf(getReusableInstalledPluginArtifacts(installedManifests).keySet());
    }

    /// Returns exact identities from one installation-planning snapshot that are currently safe to reuse.
    ///
    /// The returned map is the authorization snapshot that a store installation plan must preserve until final
    /// publication. Final staging compares every unreplaced dependency against the same ID, version, and complete
    /// package SHA-256 while holding the shared mutation lock.
    ///
    /// @param installedManifests immutable installation-planning manifest snapshot
    /// @return immutable reusable artifact identities indexed by plugin ID
    /// @throws IOException if package identity or permission state cannot be inspected
    public @Unmodifiable Map<String, PluginArtifactIdentity> getReusableInstalledPluginArtifacts(
            @Unmodifiable Map<String, PluginManifest> installedManifests
    ) throws IOException {
        @Unmodifiable Map<String, PluginManifest> snapshot = Map.copyOf(installedManifests);
        return mutationLock.call(() -> {
            stateStore.load(enabledStates, pendingUninstall);
            Map<String, PluginArtifactIdentity> reusable = new LinkedHashMap<>();
            for (Map.Entry<String, PluginManifest> entry : snapshot.entrySet()) {
                if (pendingUninstall.contains(entry.getKey())) {
                    continue;
                }
                @Nullable PluginArtifactIdentity identity = reusePolicy.resolveReusableIdentity(
                        entry.getKey(),
                        entry.getValue(),
                        enabledStates
                );
                if (identity != null) {
                    reusable.put(entry.getKey(), identity);
                }
            }
            return Map.copyOf(reusable);
        });
    }

    /// Returns every readable package currently published on disk, including artifacts pending uninstallation.
    ///
    /// Loaded lifecycle manifests are used only when their package is absent. This view is intended for management
    /// and status presentation; dependency planning should continue to use [getInstalledManifests].
    ///
    /// @return immutable published manifests indexed by plugin ID
    /// @throws IOException if the plugin directory cannot be listed
    public @Unmodifiable Map<String, PluginManifest> getPublishedPluginManifests() throws IOException {
        return packageRepository.readInstalledManifests(List.copyOf(plugins));
    }

    /// Validates and atomically publishes multiple inspected packages without caller-supplied grants.
    ///
    /// The complete future dependency graph is checked before any installed file changes. Every source is copied and
    /// hash-verified first; existing packages are then backed up and all replacements are published as one transaction.
    /// A publication failure restores every previous package. New plugin IDs are enabled for their first startup,
    /// while existing enablement state is preserved.
    ///
    /// This package-private compatibility overload is fail-closed, never carries old decisions into updates, and
    /// rejects schema-v4 packages that require any capability. Production UI must supply one explicit decision set
    /// for every package.
    ///
    /// @param inspections immutable inspected packages in dependency-first order
    /// @return immutable replacement manifests in the supplied order
    /// @throws IOException if validation, copying, publication, or rollback fails
    @Unmodifiable List<PluginManifest> stagePluginInstallations(
            @Unmodifiable List<LocalPluginInspection> inspections
    ) throws IOException {
        administrativeGuard.checkTrustedCaller();
        Map<String, @Unmodifiable Set<PluginPermission>> deniedGrants = new LinkedHashMap<>();
        for (LocalPluginInspection inspection : inspections) {
            deniedGrants.put(inspection.manifest.getId(), Set.of());
        }
        return stagePluginInstallations(inspections, Map.copyOf(deniedGrants));
    }

    /// Validates and atomically publishes multiple inspected packages with explicit per-plugin permission decisions.
    ///
    /// Every inspected plugin ID must have one decision set, including an empty set when the user denies every
    /// requested capability. Permission decisions are artifact-bound and restored when package publication fails.
    ///
    /// @param inspections immutable inspected packages in dependency-first order
    /// @param grantsByPluginId explicit user decisions indexed by inspected plugin ID
    /// @return immutable replacement manifests in the supplied order
    /// @throws IOException if validation, permission persistence, copying, publication, or rollback fails
    public @Unmodifiable List<PluginManifest> stagePluginInstallations(
            @Unmodifiable List<LocalPluginInspection> inspections,
            @Unmodifiable Map<String, @Unmodifiable Set<PluginPermission>> grantsByPluginId
    ) throws IOException {
        administrativeGuard.checkTrustedCaller();
        @Unmodifiable Map<String, Optional<PluginArtifactIdentity>> expectedPriorArtifacts =
                PluginInstallationStateGuard.expectedPriorArtifactsFromInspections(inspections);
        return mutationLock.call(() -> stagePluginInstallationsLocked(
                inspections,
                grantsByPluginId,
                Map.of(),
                false,
                expectedPriorArtifacts,
                Map.of()
        ));
    }

    /// Validates and atomically publishes a confirmed store plan with exact reusable dependency identities.
    ///
    /// Every unreplaced dependency in the final replacement closure must have an identity in
    /// `expectedReusableArtifacts`, and its current ID, version, and complete package SHA-256 must still match. The
    /// check occurs under the shared mutation lock before any package, state, or permission file is changed.
    ///
    /// @param inspections immutable inspected packages in dependency-first order
    /// @param grantsByPluginId explicit user decisions indexed by inspected plugin ID
    /// @param expectedReusableArtifacts exact dependency identities captured by the confirmed store plan
    /// @return immutable replacement manifests in the supplied order
    /// @throws IOException if validation, identity comparison, persistence, publication, or rollback fails
    public @Unmodifiable List<PluginManifest> stagePluginInstallations(
            @Unmodifiable List<LocalPluginInspection> inspections,
            @Unmodifiable Map<String, @Unmodifiable Set<PluginPermission>> grantsByPluginId,
            @Unmodifiable Map<String, PluginArtifactIdentity> expectedReusableArtifacts
    ) throws IOException {
        administrativeGuard.checkTrustedCaller();
        @Unmodifiable Map<String, PluginArtifactIdentity> expectedSnapshot =
                Map.copyOf(expectedReusableArtifacts);
        @Unmodifiable Map<String, Optional<PluginArtifactIdentity>> expectedPriorArtifacts =
                PluginInstallationStateGuard.expectedPriorArtifactsFromInspections(inspections);
        return mutationLock.call(() -> stagePluginInstallationsLocked(
                inspections,
                grantsByPluginId,
                expectedSnapshot,
                true,
                expectedPriorArtifacts,
                Map.of()
        ));
    }

    /// Publishes a confirmed store plan with exact reusable dependencies and exact replacement prior state.
    ///
    /// `expectedPriorArtifacts` must contain every replacement ID. An empty optional means the plugin was absent when
    /// the user confirmed an installation; a present identity means the user confirmed replacement of exactly those
    /// package bytes. Both replacement and reuse expectations are re-read under the mutation lock before publication.
    ///
    /// @param inspections immutable inspected packages in dependency-first order
    /// @param grantsByPluginId explicit user decisions indexed by inspected plugin ID
    /// @param expectedReusableArtifacts exact identities for dependencies selected as `REUSE`
    /// @param expectedPriorArtifacts exact prior state for every installation or update
    /// @return immutable replacement manifests in the supplied order
    /// @throws IOException if current package state differs from the confirmed plan or publication fails
    public @Unmodifiable List<PluginManifest> stagePluginInstallations(
            @Unmodifiable List<LocalPluginInspection> inspections,
            @Unmodifiable Map<String, @Unmodifiable Set<PluginPermission>> grantsByPluginId,
            @Unmodifiable Map<String, PluginArtifactIdentity> expectedReusableArtifacts,
            @Unmodifiable Map<String, Optional<PluginArtifactIdentity>> expectedPriorArtifacts
    ) throws IOException {
        administrativeGuard.checkTrustedCaller();
        @Unmodifiable Map<String, PluginArtifactIdentity> reusableSnapshot =
                Map.copyOf(expectedReusableArtifacts);
        @Unmodifiable Map<String, Optional<PluginArtifactIdentity>> priorSnapshot =
                Map.copyOf(expectedPriorArtifacts);
        return mutationLock.call(() -> stagePluginInstallationsLocked(
                inspections,
                grantsByPluginId,
                reusableSnapshot,
                true,
                priorSnapshot,
                Map.of()
        ));
    }

    /// Publishes a confirmed store plan together with proof-backed certification receipts for certified downloads.
    ///
    /// Receipt entries may cover only replacement IDs. Every replacement without an entry atomically loses any old
    /// receipt, which prevents local, community, or official bytes from inheriting an earlier repository identity.
    ///
    /// @param inspections immutable inspected packages in dependency-first order
    /// @param grantsByPluginId explicit user decisions indexed by inspected plugin ID
    /// @param expectedReusableArtifacts exact identities for dependencies selected as `REUSE`
    /// @param expectedPriorArtifacts exact prior state for every installation or update
    /// @param certificationReceipts proof-backed receipts for certified replacement artifacts
    /// @return immutable replacement manifests in the supplied order
    /// @throws IOException if current package state, receipt binding, or publication fails
    public @Unmodifiable List<PluginManifest> stagePluginInstallations(
            @Unmodifiable List<LocalPluginInspection> inspections,
            @Unmodifiable Map<String, @Unmodifiable Set<PluginPermission>> grantsByPluginId,
            @Unmodifiable Map<String, PluginArtifactIdentity> expectedReusableArtifacts,
            @Unmodifiable Map<String, Optional<PluginArtifactIdentity>> expectedPriorArtifacts,
            @Unmodifiable Map<String, PluginCertificationReceipt> certificationReceipts
    ) throws IOException {
        administrativeGuard.checkTrustedCaller();
        @Unmodifiable Map<String, PluginArtifactIdentity> reusableSnapshot =
                Map.copyOf(expectedReusableArtifacts);
        @Unmodifiable Map<String, Optional<PluginArtifactIdentity>> priorSnapshot =
                Map.copyOf(expectedPriorArtifacts);
        @Unmodifiable Map<String, PluginCertificationReceipt> receiptSnapshot =
                Map.copyOf(certificationReceipts);
        return mutationLock.call(() -> stagePluginInstallationsLocked(
                inspections,
                grantsByPluginId,
                reusableSnapshot,
                true,
                priorSnapshot,
                receiptSnapshot
        ));
    }

    /// Publishes an installation batch while the shared package, state, and permission lock is held.
    ///
    /// @param inspections immutable inspected packages in dependency-first order
    /// @param grantsByPluginId explicit user decisions indexed by inspected plugin ID
    /// @param expectedReusableArtifacts exact dependency identities captured during planning
    /// @param requireExpectedReusableArtifacts whether every reused dependency must match the planning snapshot
    /// @param expectedPriorArtifacts exact prior state for every replacement ID
    /// @param certificationReceipts proof-backed receipts for certified replacements
    /// @return immutable replacement manifests in the supplied order
    /// @throws IOException if validation, persistence, publication, or rollback fails
    private @Unmodifiable List<PluginManifest> stagePluginInstallationsLocked(
            @Unmodifiable List<LocalPluginInspection> inspections,
            @Unmodifiable Map<String, @Unmodifiable Set<PluginPermission>> grantsByPluginId,
            @Unmodifiable Map<String, PluginArtifactIdentity> expectedReusableArtifacts,
            boolean requireExpectedReusableArtifacts,
            @Unmodifiable Map<String, Optional<PluginArtifactIdentity>> expectedPriorArtifacts,
            @Unmodifiable Map<String, PluginCertificationReceipt> certificationReceipts
    ) throws IOException {
        stateStore.load(enabledStates, pendingUninstall);
        if (inspections.isEmpty()) {
            if (!grantsByPluginId.isEmpty()
                    || !expectedReusableArtifacts.isEmpty()
                    || !expectedPriorArtifacts.isEmpty()
                    || !certificationReceipts.isEmpty()) {
                throw new IllegalArgumentException("State expectations were supplied for an empty installation");
            }
            return List.of();
        }
        if (!packageMutationService.recover()) {
            throw new IOException("A previous plugin installation transaction could not be recovered");
        }
        Map<String, LocalPluginInspection> inspectionsById = new LinkedHashMap<>();
        Map<String, PluginManifest> replacements = new LinkedHashMap<>();
        for (LocalPluginInspection inspection : inspections) {
            Path source = inspection.sourcePackage;
            PluginPackageRepository.validateLocalPackage(source);
            PluginPackageMutationService.verifyPackageHash(source, inspection.sha256);
            PluginManifest manifest = inspection.manifest;
            if (manifest.getSchemaVersion() != PluginManifest.CURRENT_SCHEMA_VERSION) {
                throw new IOException("Only plugin API v" + PluginManifest.CURRENT_SCHEMA_VERSION
                        + " packages can be installed; found schema " + manifest.getSchemaVersion()
                        + " for " + manifest.getId());
            }
            if (!isLauncherCompatible(manifest)) {
                throw new IOException("Plugin " + manifest.getId() + " requires launcher version "
                        + manifest.getLauncherVersion() + " but this launcher is " + Metadata.VERSION);
            }
            if (inspectionsById.putIfAbsent(manifest.getId(), inspection) != null) {
                throw new IOException("Plugin installation batch contains duplicate ID: " + manifest.getId());
            }
            replacements.put(manifest.getId(), manifest);
        }

        if (grantsByPluginId.size() != replacements.size()
                || !grantsByPluginId.keySet().containsAll(replacements.keySet())) {
            throw new IllegalArgumentException("Every inspected plugin must have exactly one permission decision");
        }
        for (String pluginId : replacements.keySet()) {
            if (grantsByPluginId.get(pluginId) == null) {
                throw new IllegalArgumentException("Missing permission decision for plugin " + pluginId);
            }
        }
        if (!replacements.keySet().containsAll(certificationReceipts.keySet())) {
            throw new IllegalArgumentException("Certification receipt belongs to a plugin outside the install batch");
        }
        for (Map.Entry<String, PluginCertificationReceipt> entry : certificationReceipts.entrySet()) {
            LocalPluginInspection inspection = Objects.requireNonNull(inspectionsById.get(entry.getKey()));
            PluginCertificationReceipt receipt = entry.getValue();
            if (!entry.getKey().equals(receipt.pluginId())
                    || !inspection.manifest.getVersion().equals(receipt.version())
                    || !inspection.sha256.equals(receipt.sha256())
                    || Files.size(inspection.sourcePackage) != receipt.size()) {
                throw new IOException("Certification receipt does not match inspected package " + entry.getKey());
            }
        }
        installationStateGuard.validateReplacementPriorArtifacts(
                Set.copyOf(replacements.keySet()),
                expectedPriorArtifacts
        );

        Map<String, PluginManifest> installedBefore =
                dependencyPlanner.readInstallPlanningManifests(plugins, pendingUninstall);
        Map<String, PluginManifest> effectiveManifests = new LinkedHashMap<>(installedBefore);
        effectiveManifests.putAll(replacements);
        dependencyPlanner.validateReplacementGraph(effectiveManifests, replacements.keySet());
        @Unmodifiable Set<String> plannedDependencyIds = requireExpectedReusableArtifacts
                ? reusePolicy.validateDependencyClosure(
                        Map.copyOf(effectiveManifests),
                        Set.copyOf(replacements.keySet()),
                        Set.copyOf(enabledStates),
                        expectedReusableArtifacts
                )
                : reusePolicy.validateDependencyClosure(
                        Map.copyOf(effectiveManifests),
                        Set.copyOf(replacements.keySet()),
                        Set.copyOf(enabledStates)
                );

        Set<String> nextEnabledStates = new HashSet<>(enabledStates);
        nextEnabledStates.addAll(plannedDependencyIds);
        Set<String> nextPendingUninstall = new HashSet<>(pendingUninstall);
        for (String pluginId : replacements.keySet()) {
            if (!installedBefore.containsKey(pluginId)) {
                nextEnabledStates.add(pluginId);
            }
            nextPendingUninstall.remove(pluginId);
        }
        Map<String, PluginPackageMutationService.InstallArtifact> installArtifacts = new LinkedHashMap<>();
        for (Map.Entry<String, LocalPluginInspection> entry : inspectionsById.entrySet()) {
            LocalPluginInspection inspection = entry.getValue();
            installArtifacts.put(entry.getKey(), new PluginPackageMutationService.InstallArtifact(
                    inspection.sourcePackage,
                    inspection.manifest,
                    inspection.sha256
            ));
        }
        packageMutationService.publishInstallations(
                installArtifacts,
                () -> {
                    for (Map.Entry<String, LocalPluginInspection> entry : inspectionsById.entrySet()) {
                        permissionService.setGrantedPermissions(
                                entry.getValue().manifest,
                                entry.getValue().sha256,
                                Objects.requireNonNull(grantsByPluginId.get(entry.getKey()))
                        );
                    }
                    certificationReceiptStore.replaceInstallations(
                            Set.copyOf(replacements.keySet()),
                            certificationReceipts
                    );
                },
                () -> stateStore.saveStrict(nextEnabledStates, nextPendingUninstall),
                permissionService::reload
        );

        enabledStates.clear();
        enabledStates.addAll(nextEnabledStates);
        pendingUninstall.clear();
        pendingUninstall.addAll(nextPendingUninstall);
        for (Map.Entry<String, PluginManifest> replacement : replacements.entrySet()) {
            String pluginId = replacement.getKey();
            LocalPluginInspection inspection = Objects.requireNonNull(inspectionsById.get(pluginId));
            PluginArtifactIdentity identity = PluginArtifactIdentity.of(
                    inspection.manifest,
                    inspection.sha256
            );
            clearArtifactState(pluginId);
            runtimeState.remember(identity);
            setRuntimeStatus(identity, PluginRuntimeStatus.WAITING_FOR_RESTART, null);
            Path installedPackage = pluginsDirectory.resolve(pluginId + ".npl").toAbsolutePath().normalize();
            @Nullable PluginContainer container = pluginMap.get(pluginId);
            if (container != null) {
                container.setNplFile(installedPackage);
                container.setRestartRequired(true);
            }
            LOG.info("Staged plugin for next restart: " + pluginId + " " + replacement.getValue().getVersion());
        }
        return List.copyOf(replacements.values());
    }

    /// Best-effort removes hidden staging files without changing transaction success or permission decisions.
    ///
    /// A committed journal owns any file that could not be removed and retries cleanup during the next startup.
    /// Cleanup failure after publication must never restore old permissions while retaining new packages.
    ///
    /// @param preparedPackages hidden staging paths to remove when still present
    static void cleanupPreparedPackages(@Unmodifiable List<Path> preparedPackages) {
        PluginPackageMutationService.cleanupPreparedPackages(preparedPackages);
    }

    /// Uninstalls a plugin immediately when safe, otherwise marks it for restart-time removal.
    ///
    /// @param pluginId plugin ID
    /// @throws IOException if package or directory deletion fails
    public void uninstallPlugin(String pluginId) throws IOException {
        administrativeGuard.checkTrustedCaller();
        mutationLock.run(() -> uninstallPluginLocked(pluginId));
    }

    /// Uninstalls one plugin while the shared mutation lock is held.
    ///
    /// @param pluginId plugin ID
    /// @throws IOException if package, state, or permission mutation fails
    private void uninstallPluginLocked(String pluginId) throws IOException {
        stateStore.load(enabledStates, pendingUninstall);
        @Nullable PluginContainer container = pluginMap.get(pluginId);
        @Unmodifiable List<Path> installedPackages = packageRepository.findInstalledPackages(pluginId);
        if (container == null && installedPackages.isEmpty()) {
            return;
        }
        @Unmodifiable List<String> blockingDependents = dependencyPlanner.findBlockingDependents(
                pluginId,
                plugins,
                pendingUninstall
        );
        if (!blockingDependents.isEmpty()) {
            throw new IOException("Cannot uninstall plugin " + pluginId
                    + " because installed plugins depend on it: " + blockingDependents);
        }
        if (requiresRestartForUninstall(pluginId)) {
            markForUninstallLocked(pluginId);
            return;
        }
        if (container != null) {
            unloadPluginLocked(pluginId);
        }
        List<Path> packagesToRemove = new ArrayList<>(installedPackages);
        if (container != null) {
            packagesToRemove.add(container.getNplFile());
        }
        Set<String> nextEnabledStates = new HashSet<>(enabledStates);
        Set<String> nextPendingUninstall = new HashSet<>(pendingUninstall);
        nextEnabledStates.remove(pluginId);
        nextPendingUninstall.remove(pluginId);
        packageMutationService.publishRemoval(
                List.copyOf(packagesToRemove),
                pluginId,
                () -> {
                    permissionService.removePlugin(pluginId);
                    certificationReceiptStore.removePlugin(pluginId);
                    stateStore.saveStrict(nextEnabledStates, nextPendingUninstall);
                },
                () -> {
                    permissionService.reload();
                    stateStore.load(enabledStates, pendingUninstall);
                }
        );
        enabledStates.clear();
        enabledStates.addAll(nextEnabledStates);
        pendingUninstall.clear();
        pendingUninstall.addAll(nextPendingUninstall);
        clearArtifactState(pluginId);
        LOG.info("Uninstalled plugin: " + pluginId);
    }

    /// Returns whether uninstalling the plugin must wait until a restart.
    ///
    /// @param pluginId plugin ID
    /// @return whether restart-time removal is required
    public boolean requiresRestartForUninstall(String pluginId) {
        @Nullable PluginContainer container = pluginMap.get(pluginId);
        return isMixinActive(pluginId) || container != null && container.isEnabled();
    }

    /// Marks a plugin for removal before the next Mixin bootstrap and launcher load.
    ///
    /// @param pluginId plugin ID
    public void markForUninstall(String pluginId) {
        administrativeGuard.checkTrustedCaller();
        try {
            mutationLock.run(() -> markForUninstallLocked(pluginId));
        } catch (IOException exception) {
            LOG.warning("Cannot durably mark plugin for uninstall: " + pluginId, exception);
        }
    }

    /// Marks one plugin for restart-time removal while the shared mutation lock is held.
    ///
    /// @param pluginId plugin ID
    /// @throws IOException if dependency inspection or durable state publication fails
    private void markForUninstallLocked(String pluginId) throws IOException {
        stateStore.load(enabledStates, pendingUninstall);
        @Unmodifiable List<String> blockingDependents = dependencyPlanner.findBlockingDependents(
                pluginId,
                plugins,
                pendingUninstall
        );
        if (!blockingDependents.isEmpty()) {
            LOG.warning("Cannot mark plugin " + pluginId
                    + " for uninstall because installed plugins depend on it: " + blockingDependents);
            return;
        }
        Set<String> nextEnabledStates = new HashSet<>(enabledStates);
        Set<String> nextPendingUninstall = new HashSet<>(pendingUninstall);
        nextPendingUninstall.add(pluginId);
        nextEnabledStates.remove(pluginId);
        packageMutationService.publishDocuments(
                () -> {
                    permissionService.removePlugin(pluginId);
                    stateStore.saveStrict(nextEnabledStates, nextPendingUninstall);
                },
                () -> {
                    permissionService.reload();
                    stateStore.load(enabledStates, pendingUninstall);
                }
        );

        enabledStates.clear();
        enabledStates.addAll(nextEnabledStates);
        pendingUninstall.clear();
        pendingUninstall.addAll(nextPendingUninstall);
        @Nullable PluginContainer container = pluginMap.get(pluginId);
        if (container != null && container.isEnabled()) {
            disablePluginLocked(pluginId);
        }
        clearArtifactState(pluginId);
        if (container != null) {
            container.setRestartRequired(true);
        }
        LOG.info("Marked plugin for uninstall on next restart: " + pluginId);
    }

    /// Returns whether a plugin is marked for restart-time removal.
    ///
    /// @param pluginId plugin ID
    /// @return pending-uninstall state
    public boolean isMarkedForUninstall(String pluginId) {
        stateLock.readLock().lock();
        try {
            return pendingUninstall.contains(pluginId);
        } finally {
            stateLock.readLock().unlock();
        }
    }

    /// Returns whether the plugin is configured to enable during this or the next launcher start.
    ///
    /// @param pluginId plugin ID
    /// @return persisted desired enablement state
    public boolean isPluginEnabled(String pluginId) {
        stateLock.readLock().lock();
        try {
            return enabledStates.contains(pluginId);
        } finally {
            stateLock.readLock().unlock();
        }
    }

    /// Returns the authoritative state of the artifact currently published for one plugin ID.
    ///
    /// @param pluginId plugin ID
    /// @return artifact-bound runtime state
    public PluginRuntimeStatus getPluginRuntimeStatus(String pluginId) {
        return artifactResolver.getRuntimeStatus(pluginId, enabledStates, pendingUninstall);
    }

    /// Returns the current artifact's policy, dependency, loading, or lifecycle diagnostic.
    ///
    /// @param pluginId plugin ID
    /// @return artifact-bound detail or `null` when no diagnostic is present
    public @Nullable String getPluginRuntimeDetail(String pluginId) {
        return artifactResolver.getRuntimeDetail(pluginId);
    }

    /// Returns the current artifact's runtime diagnostic for compatibility with existing management UI.
    ///
    /// @param pluginId plugin ID
    /// @return artifact-bound detail or `null`
    public @Nullable String getPluginLoadFailure(String pluginId) {
        return getPluginRuntimeDetail(pluginId);
    }

    /// Loads the declared icon for one currently installed local plugin package.
    ///
    /// Package icons are a presentation detail: a missing, removed, or malformed resource deliberately falls back
    /// to the caller's generic plugin icon without changing discovery or lifecycle state.
    ///
    /// @param manifest installed plugin manifest
    /// @return decoded local package icon, or `null` when the UI should use its fallback
    public @Nullable Image getPluginIcon(PluginManifest manifest) {
        if (manifest.getIcon() == null) {
            return null;
        }
        try {
            for (Path packageFile : packageRepository.findInstalledPackages(manifest.getId())) {
                try {
                    PluginManifest packageManifest = packageRepository.readManifest(packageFile);
                    if (manifest.equals(packageManifest)) {
                        return packageRepository.readIcon(packageFile, packageManifest);
                    }
                } catch (IOException | RuntimeException ignored) {
                    // A damaged optional presentation resource must never hide the installed package.
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // The installed-package view will report directory failures separately.
        }
        return null;
    }

    /// Returns whether a plugin's Mixin configurations were registered before this launcher instance loaded.
    ///
    /// @param pluginId plugin ID
    /// @return whether the plugin's Mixins are active
    public boolean isMixinActive(String pluginId) {
        return artifactResolver.isMixinActive(pluginId);
    }

    /// Returns an unmodifiable observable view of loaded plugins.
    ///
    /// @return loaded plugin view
    public @UnmodifiableView ObservableList<PluginContainer> getPlugins() {
        return FXCollections.unmodifiableObservableList(plugins);
    }

    /// Returns a loaded plugin by ID.
    ///
    /// @param pluginId plugin ID
    /// @return loaded container or `null`
    public @Nullable PluginContainer getPlugin(String pluginId) {
        stateLock.readLock().lock();
        try {
            return pluginMap.get(pluginId);
        } finally {
            stateLock.readLock().unlock();
        }
    }

    /// Returns the installed package directory.
    ///
    /// @return plugin package directory
    public Path getPluginsDirectory() {
        administrativeGuard.checkTrustedCaller();
        return pluginsDirectory;
    }

}
