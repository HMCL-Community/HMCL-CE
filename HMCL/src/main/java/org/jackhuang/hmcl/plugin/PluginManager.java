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

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.image.Image;
import org.jackhuang.hmcl.Metadata;
import org.jackhuang.hmcl.plugin.internal.PluginPackageVersions;
import org.jackhuang.hmcl.plugin.internal.VerifiedPluginPackage;
import org.jackhuang.hmcl.plugin.bridge.PluginCapabilitySession;
import org.jackhuang.hmcl.plugin.bridge.PluginPermissionAuthority;
import org.jackhuang.hmcl.plugin.loader.JavaPluginLoader;
import org.jackhuang.hmcl.plugin.loader.PluginLoader;
import org.jackhuang.hmcl.plugin.loader.RuntimePluginLoader;
import org.jackhuang.hmcl.plugin.mixin.bootstrap.PluginAgentSnapshot;
import org.jackhuang.hmcl.plugin.runtime.PluginCompatibilityEvaluator;
import org.jackhuang.hmcl.plugin.runtime.PluginCompatibilityRequirements;
import org.jackhuang.hmcl.plugin.runtime.PluginCompatibilityResult;
import org.jackhuang.hmcl.plugin.runtime.PluginCompatibilityStatus;
import org.jackhuang.hmcl.plugin.runtime.PluginRuntimeTypes;
import org.jackhuang.hmcl.plugin.runtime.RuntimeHookEndpoint;
import org.jackhuang.hmcl.plugin.runtime.RuntimeProviderBinding;
import org.jackhuang.hmcl.plugin.runtime.RuntimeProviderDeclaration;
import org.jackhuang.hmcl.plugin.runtime.RuntimeProviderRegistry;
import org.jackhuang.hmcl.plugin.runtime.RuntimeRequirement;
import org.jackhuang.hmcl.plugin.runtime.RuntimeSupervisor;
import org.jackhuang.hmcl.plugin.protector.PluginRecoveryRecord;
import org.jackhuang.hmcl.plugin.protector.PluginRecoveryStore;
import org.jackhuang.hmcl.plugin.protector.StartupReporter;
import org.jackhuang.hmcl.plugin.trust.PluginCertificationReceipt;
import org.jackhuang.hmcl.plugin.trust.PluginCertificationReceiptStore;
import org.jackhuang.hmcl.plugin.trust.PluginRuntimeTrustGuard;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.jetbrains.annotations.UnmodifiableView;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
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
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;
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
    /// Strict startup recovery evidence store consumed only after durable quarantine publication.
    private final PluginRecoveryStore recoveryStore;
    /// Proof-backed certified installation receipts changed in the same transaction as packages.
    private final PluginCertificationReceiptStore certificationReceiptStore;
    /// Dependent-scoped runtime Provider bindings changed atomically with package publication.
    private final PluginRuntimeBindingStore runtimeBindingStore;
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
    /// Process-local authority for external runtime Bridge capability tokens.
    private final PluginPermissionAuthority permissionAuthority;
    /// Exact-artifact policy for plugin-store dependency reuse.
    private final PluginReusePolicy reusePolicy;
    /// Exact prior-state capture and final replacement revalidation.
    private final PluginInstallationStateGuard installationStateGuard;
    /// Shared launcher, platform, runtime, and ABI compatibility policy for every execution path.
    private final PluginCompatibilityEvaluator compatibilityEvaluator;
    /// Exact runtime registry shared with compatibility evaluation and payload delegation.
    private final RuntimeProviderRegistry runtimeProviders;
    /// Launcher-owned external Provider lifecycle and payload supervisor.
    private final RuntimeSupervisor runtimeSupervisor;
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
    /// Installed plugin IDs retained but blocked from execution after startup recovery.
    private final Set<String> quarantinedStates = new HashSet<>();
    /// Persisted secret-free report from the latest consumed recovery record, or `null` when absent.
    private volatile @Nullable PluginQuarantineReport quarantineReport;
    /// Reports exact provider and ordinary-plugin startup stages to the process Protector.
    private final BiConsumer<PluginKind, String> startupStageReporter;
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
    /// @param explicitRuntimeTrustGuard explicit proof-backed runtime gate, or `null` for the inactive gate
    private PluginManager(
            Path localHome,
            boolean trustConstructionStack,
            @Nullable PluginRuntimeTrustGuard explicitRuntimeTrustGuard
    ) {
        this(
                localHome,
                trustConstructionStack,
                explicitRuntimeTrustGuard,
                PluginCompatibilityEvaluator.processWide()
        );
    }

    /// Creates an isolated manager with a deterministic compatibility evaluator for lifecycle gate tests.
    ///
    /// @param localHome isolated HMCL home
    /// @param compatibilityEvaluator explicit launcher-host compatibility policy
    PluginManager(Path localHome, PluginCompatibilityEvaluator compatibilityEvaluator) {
        this(localHome, true, null, compatibilityEvaluator);
    }

    /// Creates an isolated manager with an injected startup-stage reporter for ordering tests.
    ///
    /// @param localHome isolated HMCL home
    /// @param startupStageReporter exact plugin-kind and ID reporter
    PluginManager(Path localHome, BiConsumer<PluginKind, String> startupStageReporter) {
        this(localHome, true, null, PluginCompatibilityEvaluator.processWide(), startupStageReporter);
    }

    /// Creates one manager with explicit construction, trust, and compatibility policies.
    ///
    /// @param localHome launcher-local home
    /// @param trustConstructionStack whether to trust exact test-framework loaders on the construction stack
    /// @param explicitRuntimeTrustGuard explicit proof-backed runtime gate, or `null` for the inactive gate
    /// @param compatibilityEvaluator shared launcher-host compatibility policy
    private PluginManager(
            Path localHome,
            boolean trustConstructionStack,
            @Nullable PluginRuntimeTrustGuard explicitRuntimeTrustGuard,
            PluginCompatibilityEvaluator compatibilityEvaluator
    ) {
        this(
                localHome,
                trustConstructionStack,
                explicitRuntimeTrustGuard,
                compatibilityEvaluator,
                PluginManager::reportStartupStage
        );
    }

    /// Creates one manager with explicit construction, trust, compatibility, and startup-reporting policies.
    ///
    /// @param localHome launcher-local home
    /// @param trustConstructionStack whether to trust exact test-framework loaders on the construction stack
    /// @param explicitRuntimeTrustGuard explicit proof-backed runtime gate, or `null` for the inactive gate
    /// @param compatibilityEvaluator shared launcher-host compatibility policy
    /// @param startupStageReporter exact plugin-kind and ID reporter
    private PluginManager(
            Path localHome,
            boolean trustConstructionStack,
            @Nullable PluginRuntimeTrustGuard explicitRuntimeTrustGuard,
            PluginCompatibilityEvaluator compatibilityEvaluator,
            BiConsumer<PluginKind, String> startupStageReporter
    ) {
        this.compatibilityEvaluator = compatibilityEvaluator;
        this.startupStageReporter = startupStageReporter;
        runtimeProviders = compatibilityEvaluator.getRuntimeProviders();
        runtimeSupervisor = new RuntimeSupervisor(runtimeProviders);
        administrativeGuard = new PluginAdministrativeGuard(trustConstructionStack);
        pluginsDirectory = localHome.resolve("plugins");
        pluginPackageDirectory = localHome.resolve("plugin-data");
        pluginStorageDirectory = localHome.resolve("plugin-storage");
        mutationLock = new PluginMutationLock(localHome);
        runtimeBindingStore = new PluginRuntimeBindingStore(localHome, mutationLock);
        packageRepository = new PluginPackageRepository(pluginsDirectory);
        artifactResolver = new PluginArtifactResolver(packageRepository, pluginMap, runtimeState);
        installationStateGuard = new PluginInstallationStateGuard(artifactResolver);
        dependencyPlanner = new PluginDependencyPlanner(packageRepository, runtimeBindingStore);
        stateStore = new PluginStateStore(localHome.resolve("plugin-states.json"), mutationLock);
        recoveryStore = new PluginRecoveryStore(localHome);
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
        permissionAuthority = new PluginPermissionAuthority();
        reusePolicy = new PluginReusePolicy(
                packageRepository,
                permissionService,
                compatibilityEvaluator,
                Metadata.VERSION,
                runtimeTrustGuard
        );
        quarantineReport = stateStore.load(enabledStates, pendingUninstall, quarantinedStates).orElse(null);
        loaders.put(PluginManifest.PluginType.JAVA, new JavaPluginLoader());
        loaders.put(PluginManifest.PluginType.KOTLIN, new JavaPluginLoader());
    }

    /// Returns the process-wide plugin manager.
    /// @return plugin manager singleton
    public static PluginManager getInstance() {
        return PluginManagerHolder.INSTANCE;
    }

    /// Reports one exact production startup stage without fabricating plugin identities.
    ///
    /// @param kind validated manifest plugin kind
    /// @param pluginId validated canonical manifest ID
    private static void reportStartupStage(PluginKind kind, String pluginId) {
        if (kind == PluginKind.RUNTIME_PROVIDER) {
            StartupReporter.reportRuntimeProvider(pluginId);
        } else {
            StartupReporter.reportOrdinaryPlugin(pluginId);
        }
    }

    /// Strictly persists all plugin state, including the secret-free quarantine report.
    ///
    /// @throws IOException if the complete state snapshot cannot be published durably
    private void saveStates() throws IOException {
        stateStore.saveStrict(enabledStates, pendingUninstall, quarantinedStates, quarantineReport);
    }

    /// Strictly refreshes all persisted plugin state, including the secret-free quarantine report.
    ///
    /// @throws IOException if the state document is unreadable or malformed
    private void loadStates() throws IOException {
        quarantineReport = stateStore.loadStrict(enabledStates, pendingUninstall, quarantinedStates).orElse(null);
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
        Optional<PluginRecoveryRecord> recoveryRecord = recoveryStore.load();
        loadStates();
        Map<String, PluginPackageCandidate> candidates = readCandidates(recoveryRecord.isPresent());
        if (recoveryRecord.isPresent()) {
            quarantineRecoveredPlugins(recoveryRecord.get(), candidates.keySet());
        }
        try {
            permissionService.reload();
        } catch (IOException exception) {
            LOG.error("Cannot reload plugin permissions after transaction recovery", exception);
            return;
        }

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
        @Unmodifiable Map<String, RuntimeProviderBinding> startupRuntimeBindings = runtimeBindingStore.readStrict();
        for (PluginKind startupKind : List.of(PluginKind.RUNTIME_PROVIDER, PluginKind.NORMAL)) {
            for (PluginPackageCandidate candidate : candidates.values()) {
                if (candidate.manifest.getPluginKind() != startupKind) {
                    continue;
                }
                boolean enabled = enabledStates.contains(candidate.manifest.getId());
                boolean deferExternalRuntimeCompatibility = enabled
                        && isExternalRuntimePayload(candidate.manifest)
                        && startupRuntimeBindings.containsKey(candidate.manifest.getId());
                if (!deferExternalRuntimeCompatibility) {
                    PluginCompatibilityResult compatibility = evaluateCompatibility(candidate.manifest);
                    if (!compatibility.isCompatible()) {
                        if (compatibility.status() == PluginCompatibilityStatus.UNSUPPORTED_SCHEMA) {
                            enabledStates.remove(candidate.manifest.getId());
                        }
                        setRuntimeStatus(
                                candidate.identity,
                                runtimeStatusFor(compatibility),
                                compatibility.detail()
                        );
                        continue;
                    }
                }
                if (enabled) {
                    loadCandidate(
                            candidate,
                            candidates,
                            visitStates,
                            failed,
                            startupKind == PluginKind.RUNTIME_PROVIDER
                    );
                } else {
                    setRuntimeStatus(candidate.identity, PluginRuntimeStatus.INSTALLED_DISABLED, null);
                }
            }
        }
        saveStates();
        LOG.info("Discovered " + plugins.size() + " plugin(s)");
    }

    /// Durably quarantines every installed third-party package before publishing and consuming recovery evidence.
    ///
    /// Installed package, extracted configuration, and persistent data files are not opened for mutation or removed.
    /// Pending removals for retained packages are cancelled so a later safe startup cannot delete quarantine evidence.
    ///
    /// @param recoveryRecord strict previous-startup recovery evidence
    /// @param installedPluginIds manifest-only installed third-party IDs
    /// @throws IOException if quarantine persistence or exact recovery-record removal fails
    private void quarantineRecoveredPlugins(
            PluginRecoveryRecord recoveryRecord,
            Set<String> installedPluginIds
    ) throws IOException {
        Set<String> quarantinedForRecovery = installedPluginIds.stream()
                .sorted()
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
        Set<String> nextEnabledStates = new HashSet<>(enabledStates);
        Set<String> nextPendingUninstall = new HashSet<>(pendingUninstall);
        Set<String> nextQuarantinedStates = new HashSet<>(quarantinedStates);
        nextEnabledStates.removeAll(quarantinedForRecovery);
        nextPendingUninstall.removeAll(quarantinedForRecovery);
        nextQuarantinedStates.addAll(quarantinedForRecovery);
        PluginQuarantineReport nextReport = PluginQuarantineReport.fromRecovery(
                recoveryRecord,
                Set.copyOf(quarantinedForRecovery)
        );

        stateStore.saveStrict(nextEnabledStates, nextPendingUninstall, nextQuarantinedStates, nextReport);
        enabledStates.clear();
        enabledStates.addAll(nextEnabledStates);
        pendingUninstall.clear();
        pendingUninstall.addAll(nextPendingUninstall);
        quarantinedStates.clear();
        quarantinedStates.addAll(nextQuarantinedStates);
        quarantineReport = nextReport;
        recoveryStore.clear();
    }

    /// Returns whether compatibility must wait for a persisted external Provider binding to start.
    ///
    /// @param manifest candidate manifest
    /// @return whether the package consumes a non-Java schema-v5 runtime
    private static boolean isExternalRuntimePayload(PluginManifest manifest) {
        return manifest.getSchemaVersion() >= 5
                && manifest.getPluginKind() == PluginKind.NORMAL
                && !PluginRuntimeTypes.JAVA.equals(manifest.getRuntime());
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

    /// Reads and validates every package manifest, rejecting unsafe recovery enumeration deterministically.
    ///
    /// @param failClosed whether any malformed package or duplicate ID must abort enumeration
    /// @return package candidates indexed by ID
    /// @throws IOException if the plugin directory cannot be listed or strict enumeration is incomplete
    private Map<String, PluginPackageCandidate> readCandidates(boolean failClosed) throws IOException {
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
                        if (failClosed) {
                            throw new IOException("Duplicate installed plugin ID during recovery: "
                                    + manifest.getId());
                        }
                        LOG.error("Duplicate plugin ID " + manifest.getId() + " in "
                                + previous.nplFile.getFileName() + " and " + nplFile.getFileName());
                    } else {
                        runtimeState.remember(identity);
                    }
                } catch (IOException | RuntimeException exception) {
                    if (failClosed) {
                        throw new IOException(
                                "Cannot enumerate installed plugin package during recovery: "
                                        + nplFile.getFileName(),
                                exception
                        );
                    }
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
                Set<String> nextQuarantinedStates = new HashSet<>(quarantinedStates);
                nextEnabledStates.remove(pluginId);
                nextPendingUninstall.remove(pluginId);
                nextQuarantinedStates.remove(pluginId);
                @Unmodifiable List<Path> installedPackages = packageRepository.findInstalledPackages(pluginId);
                packageMutationService.publishRemoval(
                        installedPackages,
                        pluginId,
                        () -> {
                            permissionService.removePlugin(pluginId);
                            certificationReceiptStore.removePlugin(pluginId);
                            runtimeBindingStore.removeDependentsStrict(Set.of(pluginId));
                            stateStore.saveStrict(
                                    nextEnabledStates,
                                    nextPendingUninstall,
                                    nextQuarantinedStates,
                                    quarantineReport
                            );
                        },
                        () -> {
                            permissionService.reload();
                            loadStates();
                        }
                );

                enabledStates.clear();
                enabledStates.addAll(nextEnabledStates);
                pendingUninstall.clear();
                pendingUninstall.addAll(nextPendingUninstall);
                quarantinedStates.clear();
                quarantinedStates.addAll(nextQuarantinedStates);
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
    /// @param providerStartupPhase whether this traversal is rooted at a Runtime Provider Host
    /// @return whether the candidate loaded and enabled successfully
    private boolean loadCandidate(
            PluginPackageCandidate candidate,
            Map<String, PluginPackageCandidate> candidates,
            Map<String, PluginVisitState> visitStates,
            Set<String> failed,
            boolean providerStartupPhase
    ) {
        return loadCandidate(candidate, candidates, visitStates, failed, null, providerStartupPhase);
    }

    /// Loads one candidate while optionally retaining the original lifecycle exception for transaction diagnostics.
    ///
    /// @param candidate candidate to load
    /// @param candidates available candidates
    /// @param visitStates dependency traversal states
    /// @param failed plugin IDs that cannot be loaded
    /// @param failuresByPluginId optional mutable original failures indexed by plugin ID
    /// @return whether the candidate loaded and enabled successfully
    private boolean loadCandidate(
            PluginPackageCandidate candidate,
            Map<String, PluginPackageCandidate> candidates,
            Map<String, PluginVisitState> visitStates,
            Set<String> failed,
            @Nullable Map<String, Throwable> failuresByPluginId
    ) {
        return loadCandidate(candidate, candidates, visitStates, failed, failuresByPluginId, false);
    }

    /// Loads one candidate within the current provider-first or ordinary startup traversal.
    ///
    /// @param candidate candidate to load
    /// @param candidates available candidates
    /// @param visitStates dependency traversal states
    /// @param failed plugin IDs that cannot be loaded
    /// @param failuresByPluginId optional mutable original failures indexed by plugin ID
    /// @param providerStartupPhase whether this traversal is rooted at a Runtime Provider Host
    /// @return whether the candidate loaded and enabled successfully
    private boolean loadCandidate(
            PluginPackageCandidate candidate,
            Map<String, PluginPackageCandidate> candidates,
            Map<String, PluginVisitState> visitStates,
            Set<String> failed,
            @Nullable Map<String, Throwable> failuresByPluginId,
            boolean providerStartupPhase
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

        boolean runtimeProviderHost = candidate.manifest.getPluginKind() == PluginKind.RUNTIME_PROVIDER;
        visitStates.put(pluginId, PluginVisitState.VISITING);

        @Nullable RuntimeProviderBinding persistedRuntimeBinding = null;
        if (isExternalRuntimePayload(candidate.manifest)) {
            try {
                persistedRuntimeBinding = runtimeBindingStore.readStrict().get(pluginId);
            } catch (IOException exception) {
                setRuntimeStatus(candidate.identity, PluginRuntimeStatus.LOAD_FAILED, exception.getMessage());
                failed.add(pluginId);
                visitStates.put(pluginId, PluginVisitState.VISITED);
                return false;
            }
            if (persistedRuntimeBinding == null) {
                persistedRuntimeBinding = runtimeProviders.bindingFor(pluginId).orElse(null);
            }
            if (persistedRuntimeBinding == null) {
                String message = "Plugin " + pluginId + " has no confirmed runtime Provider binding";
                setRuntimeStatus(candidate.identity, PluginRuntimeStatus.LOAD_FAILED, message);
                failed.add(pluginId);
                visitStates.put(pluginId, PluginVisitState.VISITED);
                return false;
            }
        }
        if (persistedRuntimeBinding != null) {
            RuntimeProviderBinding runtimeBinding = persistedRuntimeBinding;
            String providerId = runtimeBinding.providerId();
            @Nullable PluginPackageCandidate providerCandidate = candidates.get(providerId);
            @Nullable PluginContainer loadedProvider = pluginMap.get(providerId);
            if (providerCandidate == null && loadedProvider == null) {
                String message = "Plugin " + pluginId + " requires missing runtime Provider " + providerId;
                setRuntimeStatus(candidate.identity, PluginRuntimeStatus.LOAD_FAILED, message);
                failed.add(pluginId);
                visitStates.put(pluginId, PluginVisitState.VISITED);
                return false;
            }
            if (providerCandidate != null && !enabledStates.contains(providerId)) {
                String message = "Plugin " + pluginId + " requires disabled runtime Provider " + providerId;
                setRuntimeStatus(candidate.identity, PluginRuntimeStatus.LOAD_FAILED, message);
                failed.add(pluginId);
                visitStates.put(pluginId, PluginVisitState.VISITED);
                return false;
            }
            if (providerCandidate != null && !loadCandidate(
                    providerCandidate, candidates, visitStates, failed, failuresByPluginId, providerStartupPhase)) {
                String message = "Plugin " + pluginId + " cannot load because runtime Provider "
                        + providerId + " failed";
                setRuntimeStatus(candidate.identity, PluginRuntimeStatus.LOAD_FAILED, message);
                failed.add(pluginId);
                visitStates.put(pluginId, PluginVisitState.VISITED);
                return false;
            }
            try {
                runtimeProviders.restoreBinding(runtimeBinding, candidate.manifest.getRuntimeRequirement());
            } catch (RuntimeException exception) {
                @Nullable String detail = exception.getMessage();
                setRuntimeStatus(candidate.identity, PluginRuntimeStatus.LOAD_FAILED,
                        detail == null || detail.isBlank() ? exception.toString() : detail);
                failed.add(pluginId);
                visitStates.put(pluginId, PluginVisitState.VISITED);
                return false;
            }
        }

        @Nullable PluginRuntimeStatus blockedStatus = getPreLoadBlock(candidate);
        if (blockedStatus != null) {
            failed.add(pluginId);
            visitStates.put(pluginId, PluginVisitState.VISITED);
            return false;
        }

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
                    && !loadCandidate(
                            dependency, candidates, visitStates, failed, failuresByPluginId, providerStartupPhase)) {
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

        if (runtimeProviderHost) {
            startupStageReporter.accept(PluginKind.RUNTIME_PROVIDER, pluginId);
            runtimeSupervisor.discover(pluginId);
            runtimeSupervisor.resolve(pluginId);
        } else if (!providerStartupPhase) {
            startupStageReporter.accept(PluginKind.NORMAL, pluginId);
        }

        try {
            loadPlugin(candidate);
            if (!enablePlugin(pluginId, new HashSet<>())) {
                failed.add(pluginId);
            }
        } catch (IOException | RuntimeException | Error exception) {
            if (failuresByPluginId != null) {
                failuresByPluginId.putIfAbsent(pluginId, exception);
            }
            if (runtimeProviderHost) {
                runtimeSupervisor.fail(pluginId);
            }
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
        PluginCompatibilityResult compatibility = evaluateCompatibility(manifest);
        if (!compatibility.isCompatible()) {
            PluginRuntimeStatus status = runtimeStatusFor(compatibility);
            setRuntimeStatus(candidate.identity, status, compatibility.detail());
            return status;
        }
        if (!PluginManifest.isCanonicalExecutableId(manifest.getId())) {
            String detail = "Plugin " + manifest.getId()
                    + " does not use a portable canonical lower-case ID";
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

    /// Evaluates one validated manifest against this manager's shared launcher-host capabilities.
    ///
    /// @param manifest validated plugin manifest
    /// @return compatibility outcome with a specific diagnostic
    private PluginCompatibilityResult evaluateCompatibility(PluginManifest manifest) {
        PluginCompatibilityRequirements requirements = PluginCompatibilityRequirements.fromManifest(manifest);
        if (isExternalRuntimePayload(manifest)) {
            @Nullable RuntimeProviderBinding binding = runtimeProviders.bindingFor(manifest.getId()).orElse(null);
            if (binding != null) {
                return compatibilityEvaluator.evaluateForProvider(
                        requirements,
                        Metadata.VERSION,
                        binding.providerId()
                );
            }
        }
        return compatibilityEvaluator.evaluate(requirements, Metadata.VERSION);
    }

    /// Maps a compatibility rejection to the established lifecycle status model.
    ///
    /// @param compatibility incompatible evaluation result
    /// @return lifecycle status representing the rejection
    private static PluginRuntimeStatus runtimeStatusFor(PluginCompatibilityResult compatibility) {
        return compatibility.status() == PluginCompatibilityStatus.UNSUPPORTED_SCHEMA
                ? PluginRuntimeStatus.BLOCKED_LEGACY
                : PluginRuntimeStatus.LOAD_FAILED;
    }

    /// Requires one validated manifest to satisfy this manager's complete compatibility policy.
    ///
    /// @param manifest validated plugin manifest
    /// @throws IOException with the evaluator detail when compatibility fails
    private void requireCompatible(PluginManifest manifest) throws IOException {
        PluginCompatibilityResult compatibility = evaluateCompatibility(manifest);
        if (!compatibility.isCompatible()) {
            throw new IOException(compatibility.detail());
        }
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

        requireCompatible(manifest);
        if (pluginMap.containsKey(pluginId)) {
            throw new IOException("Plugin already loaded: " + pluginId);
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

        boolean externalRuntimePayload = isExternalRuntimePayload(manifest);
        @Nullable PluginCapabilitySession capabilitySession = externalRuntimePayload
                ? permissionAuthority.openSession(
                        pluginPackage.getIdentity(),
                        manifest.getExecutionMode(),
                        () -> permissionService.getGrantedPermissions(manifest, artifactSha256),
                        "runtime.payload",
                        Duration.ofSeconds(30)
                )
                : null;
        @Nullable PluginLoader loader;
        if (externalRuntimePayload) {
            PluginCapabilitySession payloadCapabilitySession = Objects.requireNonNull(capabilitySession);
            loader = new RuntimePluginLoader(
                    runtimeSupervisor,
                    ignored -> dataDirectory,
                    ignored -> payloadCapabilitySession::issue,
                    permissionAuthority
            );
        } else {
            loader = loaders.get(manifest.getType());
        }
        if (loader == null) {
            throw new IOException("No loader found for plugin type: " + manifest.getType());
        }

        Plugin plugin;
        try {
            plugin = administrativeGuard.callPluginLoadingCallback(
                    () -> loader.load(manifest, pluginPackage, nplFile)
            );
        } catch (IOException | RuntimeException | Error exception) {
            if (capabilitySession != null) {
                capabilitySession.close();
            }
            throw exception;
        }
        if (manifest.getPluginKind() == PluginKind.RUNTIME_PROVIDER) {
            runtimeSupervisor.bootstrapLoaded(pluginId);
        }
        ClassLoader classLoader = plugin.getClass().getClassLoader();

        PluginContext context = new PluginContext(
                manifest,
                pluginPackage.getDirectory(),
                dataDirectory,
                classLoader,
                artifactSha256,
                () -> permissionService.getGrantedPermissions(manifest, artifactSha256),
                provider -> runtimeSupervisor.register(pluginId, provider),
                externalRuntimePayload ? null : permissionAuthority,
                capabilitySession
        );
        return new PreparedPlugin(
                plugin,
                context,
                manifest,
                nplFile
        );
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

    /// Calls one plugin callback with administrative APIs denied and the exact plugin loader installed as TCCL.
    ///
    /// @param classLoader loader that owns the plugin callback and resources
    /// @param callback plugin-owned value callback
    /// @param <T> callback result type
    /// @return callback result
    /// @throws Exception if the callback fails
    <T> T runPluginCallback(ClassLoader classLoader, Callable<T> callback) throws Exception {
        return administrativeGuard.callPluginCallback((Callable<T>) () -> {
            Thread thread = Thread.currentThread();
            @Nullable ClassLoader previousClassLoader = thread.getContextClassLoader();
            thread.setContextClassLoader(classLoader);
            try {
                return callback.call();
            } finally {
                thread.setContextClassLoader(previousClassLoader);
            }
        });
    }

    /// Takes an immutable, leased snapshot of currently eligible subscribers in deterministic dependency order.
    ///
    /// The manager state lock is released before sorting and before any returned endpoint can execute. Callers must
    /// close every returned subscriber after the endpoint has completed or permanently timed out.
    ///
    /// @param point dispatched Hook point
    /// @return ordered immutable subscriber snapshot
    @Unmodifiable List<PluginHookSubscriber> snapshotHookSubscribers(PluginHookPoint point) {
        List<PluginHookSubscriber> subscribers = new ArrayList<>();
        try {
            stateLock.readLock().lock();
            try {
                for (PluginContainer container : pluginMap.values()) {
                    PluginManifest manifest = container.getManifest();
                    @Unmodifiable Set<PluginPermission> permissions =
                            container.getContext().getGrantedPermissions();
                    if (!isEligibleHookSubscriber(container, manifest, permissions, point)) {
                        continue;
                    }
                    Runnable releaseLease = container.acquireHookLease();
                    try {
                        Set<String> resolvedDependencyIds = manifest.getPluginDependencies().stream()
                                .map(PluginDependency::getId)
                                .collect(Collectors.toCollection(HashSet::new));
                        PluginHookEndpoint endpoint;
                        if (isExternalRuntimePayload(manifest)) {
                            @Nullable RuntimeHookEndpoint.ProviderInvoker providerInvoker = null;
                            @Nullable RuntimeProviderBinding binding =
                                    runtimeProviders.bindingFor(manifest.getId()).orElse(null);
                            if (binding != null) {
                                resolvedDependencyIds.add(binding.providerId());
                                @Nullable PluginContainer providerContainer = pluginMap.get(binding.providerId());
                                if (providerContainer != null && providerContainer.isEnabled()) {
                                    @Nullable RuntimeHookEndpoint.ProviderInvoker supervisedInvoker;
                                    try {
                                        supervisedInvoker = runtimeSupervisor.hookInvoker(manifest.getId());
                                    } catch (IOException exception) {
                                        supervisedInvoker = null;
                                    }
                                    if (supervisedInvoker != null) {
                                        Runnable providerReleaseLease = providerContainer.acquireHookLease();
                                        Runnable payloadReleaseLease = releaseLease;
                                        releaseLease = () -> {
                                            try {
                                                providerReleaseLease.run();
                                            } finally {
                                                payloadReleaseLease.run();
                                            }
                                        };
                                        ClassLoader providerClassLoader =
                                                providerContainer.getContext().getClassLoader();
                                        RuntimeHookEndpoint.ProviderInvoker selectedInvoker = supervisedInvoker;
                                        providerInvoker = (ownerPluginId, token, event, timeout, cancellation) ->
                                                runPluginCallback(
                                                providerClassLoader,
                                                () -> selectedInvoker.invokeHook(
                                                        ownerPluginId, token, event, timeout, cancellation)
                                                );
                                    }
                                }
                            }
                            endpoint = new RuntimeHookEndpoint(
                                    PluginArtifactIdentity.of(
                                            manifest, container.getContext().getArtifactSha256()),
                                    manifest.getExecutionMode(),
                                    permissionAuthority,
                                    container.getContext()::issueRuntimeCapabilityToken,
                                    providerInvoker
                            );
                        } else {
                            endpoint = event -> runPluginCallback(
                                    container.getContext().getClassLoader(),
                                    () -> container.getPlugin().onHook(event)
                            );
                        }
                        @Unmodifiable Set<String> dependencyIds = Set.copyOf(resolvedDependencyIds);
                        subscribers.add(new PluginHookSubscriber(
                                manifest.getId(),
                                dependencyIds,
                                permissions,
                                endpoint,
                                releaseLease
                        ));
                    } catch (RuntimeException | Error exception) {
                        releaseLease.run();
                        throw exception;
                    }
                }
            } finally {
                stateLock.readLock().unlock();
            }
            return List.copyOf(orderHookSubscribers(subscribers));
        } catch (RuntimeException | Error exception) {
            subscribers.forEach(PluginHookSubscriber::close);
            throw exception;
        }
    }

    /// Returns whether at least one current plugin is eligible without retaining a callback or class-loader lease.
    ///
    /// @param point Hook point used for the eligibility decision
    /// @return whether at least one eligible subscriber exists
    boolean hasEligibleHookSubscriber(PluginHookPoint point) {
        stateLock.readLock().lock();
        try {
            for (PluginContainer container : pluginMap.values()) {
                PluginManifest manifest = container.getManifest();
                @Unmodifiable Set<PluginPermission> permissions =
                        container.getContext().getGrantedPermissions();
                if (isEligibleHookSubscriber(container, manifest, permissions, point)) {
                    return true;
                }
            }
            return false;
        } finally {
            stateLock.readLock().unlock();
        }
    }

    /// Applies the common loaded-container Hook eligibility predicate.
    ///
    /// @param container loaded plugin container
    /// @param manifest authoritative loaded manifest
    /// @param permissions exact-artifact effective permissions
    /// @param point Hook point being queried
    /// @return whether the plugin must participate
    private static boolean isEligibleHookSubscriber(
            PluginContainer container,
            PluginManifest manifest,
            Set<PluginPermission> permissions,
            PluginHookPoint point
    ) {
        return container.isEnabled()
                && manifest.getSchemaVersion() == PluginManifest.CURRENT_SCHEMA_VERSION
                && manifest.getHooks().contains(point)
                && permissions.contains(PluginPermission.LAUNCHER_HOOK);
    }

    /// Orders subscribers by dependency topology and canonical ID for every unrelated ready node.
    ///
    /// Dependencies absent from the eligible snapshot do not form dispatch edges.
    ///
    /// @param subscribers unordered leased snapshot
    /// @return ordered subscriber list
    private static List<PluginHookSubscriber> orderHookSubscribers(List<PluginHookSubscriber> subscribers) {
        Map<String, PluginHookSubscriber> subscribersById = new HashMap<>();
        Map<String, Integer> incomingEdges = new HashMap<>();
        Map<String, List<String>> dependentsById = new HashMap<>();
        for (PluginHookSubscriber subscriber : subscribers) {
            if (subscribersById.put(subscriber.pluginId(), subscriber) != null) {
                throw new IllegalStateException("Duplicate eligible plugin Hook subscriber: "
                        + subscriber.pluginId());
            }
            incomingEdges.put(subscriber.pluginId(), 0);
        }
        for (PluginHookSubscriber subscriber : subscribers) {
            for (String dependencyId : subscriber.dependencyIds()) {
                if (!subscribersById.containsKey(dependencyId)) {
                    continue;
                }
                incomingEdges.merge(subscriber.pluginId(), 1, Integer::sum);
                dependentsById.computeIfAbsent(dependencyId, ignored -> new ArrayList<>())
                        .add(subscriber.pluginId());
            }
        }

        PriorityQueue<String> ready = new PriorityQueue<>();
        incomingEdges.forEach((pluginId, incoming) -> {
            if (incoming == 0) {
                ready.add(pluginId);
            }
        });
        List<PluginHookSubscriber> ordered = new ArrayList<>(subscribers.size());
        while (!ready.isEmpty()) {
            String pluginId = ready.remove();
            ordered.add(Objects.requireNonNull(subscribersById.get(pluginId)));
            for (String dependentId : dependentsById.getOrDefault(pluginId, List.of())) {
                int remaining = incomingEdges.merge(dependentId, -1, Integer::sum);
                if (remaining == 0) {
                    ready.add(dependentId);
                }
            }
        }
        if (ordered.size() != subscribers.size()) {
            throw new IllegalStateException("Eligible plugin Hook dependency graph contains a cycle");
        }
        return ordered;
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
                prepared.context.closeCapabilitySession();
                prepared.context.revokeCapabilityTokens();
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
            if (prepared.manifest.getPluginKind() == PluginKind.RUNTIME_PROVIDER) {
                try {
                    runtimeSupervisor.activateOwnedRegistration(pluginId);
                } catch (IOException exception) {
                    throw new UncheckedIOException("Runtime Provider Host failed activation: " + pluginId, exception);
                }
            }
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
            container.closeCapabilitySession();
            container.revokeCapabilityTokens();
            try {
                runPluginCallback(prepared.context.getClassLoader(), prepared.plugin::onUnload);
            } catch (RuntimeException | Error unloadException) {
                exception.addSuppressed(unloadException);
            }
            try {
                container.closeRuntimeProviderRegistrations();
            } catch (IOException closeException) {
                exception.addSuppressed(closeException);
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
                loadStates();
                @Unmodifiable Map<String, PluginManifest> installedManifests =
                        packageRepository.readInstalledManifests(plugins);
                @Unmodifiable Map<String, RuntimeProviderBinding> runtimeBindings = runtimeBindingStore.readStrict();
                if (enablementClosureIntersectsQuarantine(
                        pluginId,
                        installedManifests,
                        runtimeBindings,
                        new HashSet<>()
                )) {
                    LOG.warning("Cannot enable quarantined plugin closure without explicit restoration: " + pluginId);
                    return false;
                }
                @Nullable PluginManifest requestedManifest = installedManifests.get(pluginId);
                if (requestedManifest != null) {
                    PluginCompatibilityResult compatibility = evaluateCompatibility(requestedManifest);
                    if (!compatibility.isCompatible()) {
                        if (compatibility.status() == PluginCompatibilityStatus.UNSUPPORTED_SCHEMA) {
                            enabledStates.remove(pluginId);
                        } else {
                            enabledStates.add(pluginId);
                            pendingUninstall.remove(pluginId);
                        }
                        @Nullable PluginArtifactIdentity identity =
                                artifactResolver.resolveInstalledIdentity(pluginId);
                        if (identity != null) {
                            setRuntimeStatus(
                                    identity,
                                    runtimeStatusFor(compatibility),
                                    compatibility.detail()
                            );
                        }
                        saveStates();
                        return false;
                    }
                }
                recordEnableIntent(
                        pluginId,
                        installedManifests,
                        runtimeBindings,
                        new HashSet<>()
                );
                boolean enabled = enablePlugin(pluginId, new HashSet<>());
                saveStates();
                return enabled;
            });
        } catch (IOException exception) {
            LOG.warning("Cannot persist plugin enablement for " + pluginId, exception);
            return false;
        }
    }

    /// Returns whether one requested enablement closure contains a recovery-quarantined plugin.
    ///
    /// @param pluginId closure root
    /// @param installedManifests immutable installed manifests indexed by ID
    /// @param runtimeBindings immutable runtime bindings indexed by dependent ID
    /// @param visited IDs already inspected
    /// @return whether explicit quarantine restoration is required
    private boolean enablementClosureIntersectsQuarantine(
            String pluginId,
            @Unmodifiable Map<String, PluginManifest> installedManifests,
            @Unmodifiable Map<String, RuntimeProviderBinding> runtimeBindings,
            Set<String> visited
    ) {
        if (!visited.add(pluginId)) {
            return false;
        }
        if (quarantinedStates.contains(pluginId)) {
            return true;
        }
        @Nullable RuntimeProviderBinding runtimeBinding = runtimeBindings.get(pluginId);
        if (runtimeBinding != null && enablementClosureIntersectsQuarantine(
                runtimeBinding.providerId(), installedManifests, runtimeBindings, visited)) {
            return true;
        }
        @Nullable PluginManifest manifest = installedManifests.get(pluginId);
        if (manifest == null) {
            return false;
        }
        for (PluginDependency dependency : manifest.getPluginDependencies()) {
            if (enablementClosureIntersectsQuarantine(
                    dependency.getId(), installedManifests, runtimeBindings, visited)) {
                return true;
            }
        }
        return false;
    }

    /// Records desired enablement for one installed plugin and its executable dependency closure.
    ///
    /// This operation does not claim that lifecycle activation succeeded. It only ensures that a restart-pending or
    /// currently blocked dependency is not left persistently disabled when the user enables its dependent.
    ///
    /// @param pluginId plugin whose enablement was requested
    /// @param installedManifests immutable installed manifests indexed by plugin ID
    /// @param runtimeBindings immutable confirmed external runtime edges indexed by dependent ID
    /// @param visited IDs whose dependency closure has already been recorded
    private void recordEnableIntent(
            String pluginId,
            @Unmodifiable Map<String, PluginManifest> installedManifests,
            @Unmodifiable Map<String, RuntimeProviderBinding> runtimeBindings,
            Set<String> visited
    ) {
        if (!visited.add(pluginId)) {
            return;
        }
        @Nullable PluginManifest manifest = installedManifests.get(pluginId);
        if (manifest == null) {
            return;
        }
        PluginCompatibilityResult compatibility = evaluateCompatibility(manifest);
        if (compatibility.status() == PluginCompatibilityStatus.UNSUPPORTED_SCHEMA) {
            return;
        }
        enabledStates.add(pluginId);
        pendingUninstall.remove(pluginId);
        @Nullable RuntimeProviderBinding runtimeBinding = runtimeBindings.get(pluginId);
        if (runtimeBinding != null && installedManifests.containsKey(runtimeBinding.providerId())) {
            recordEnableIntent(runtimeBinding.providerId(), installedManifests, runtimeBindings, visited);
        }
        for (PluginDependency dependency : manifest.getPluginDependencies()) {
            @Nullable PluginManifest dependencyManifest = installedManifests.get(dependency.getId());
            if (dependencyManifest != null) {
                recordEnableIntent(dependency.getId(), installedManifests, runtimeBindings, visited);
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
        PluginCompatibilityResult compatibility = evaluateCompatibility(container.getManifest());
        if (!compatibility.isCompatible()) {
            setLoadedRuntimeStatus(container, runtimeStatusFor(compatibility), compatibility.detail());
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

        @Nullable RuntimeProviderBinding runtimeBinding = runtimeProviders.bindingFor(pluginId).orElse(null);
        if (runtimeBinding != null) {
            String providerId = runtimeBinding.providerId();
            @Nullable PluginContainer providerContainer = pluginMap.get(providerId);
            if (providerContainer == null || !enablePlugin(providerId, visiting)) {
                String message = "Cannot enable plugin " + pluginId
                        + " because runtime Provider " + providerId + " is not enabled";
                setLoadedRuntimeStatus(container, PluginRuntimeStatus.LOAD_FAILED, message);
                LOG.error(message);
                visiting.remove(pluginId);
                return false;
            }
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

        container.resumeCapabilitySession();
        try {
            runPluginCallback(
                    container.getContext().getClassLoader(),
                    container.getPlugin()::onEnable
            );
            if (container.getManifest().getPluginKind() == PluginKind.RUNTIME_PROVIDER) {
                runtimeSupervisor.hostEnabled(pluginId);
            }
            container.setEnabled(true);
            container.setRestartRequired(false);
            enabledStates.add(pluginId);
            setLoadedRuntimeStatus(container, PluginRuntimeStatus.ENABLED, null);
            LOG.info("Enabled plugin: " + pluginId);
            visiting.remove(pluginId);
            return true;
        } catch (RuntimeException | Error exception) {
            container.suspendCapabilitySession();
            if (container.getManifest().getPluginKind() == PluginKind.RUNTIME_PROVIDER) {
                runtimeSupervisor.hostDisabled(pluginId);
            }
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

    /// Disables one plugin unless enabled external-runtime plugins remain bound to it as their Runtime Provider.
    ///
    /// Active Mixin bytecode remains until restart and is reflected by `restartRequired`.
    ///
    /// @param pluginId plugin ID
    /// @throws IOException if enabled runtime dependents block the operation or lifecycle state cannot be persisted
    public void disablePlugin(String pluginId) throws IOException {
        administrativeGuard.checkTrustedCaller();
        mutationLock.run(() -> {
            loadStates();
            @Unmodifiable List<String> enabledRuntimeDependents =
                    dependencyPlanner.findEnabledRuntimeDependents(pluginId, Set.copyOf(enabledStates));
            if (!enabledRuntimeDependents.isEmpty()) {
                throw new IOException("Cannot disable Runtime Provider " + pluginId
                        + "; enabled runtime dependents: " + String.join(", ", enabledRuntimeDependents));
            }
            disablePluginLocked(pluginId);
        });
    }

    /// Explicitly disables every direct or transitive dependent before disabling the requested plugin.
    ///
    /// Active Mixin bytecode remains until restart and is reflected by `restartRequired`.
    ///
    /// @param pluginId dependency or Runtime Provider plugin ID
    /// @throws IOException if the installed dependency graph cannot be read or lifecycle state cannot be persisted
    public void disablePluginCascade(String pluginId) throws IOException {
        administrativeGuard.checkTrustedCaller();
        mutationLock.run(() -> {
            loadStates();
            disablePluginLocked(pluginId);
        });
    }

    /// Disables one plugin and its dependents while the shared mutation lock is held.
    ///
    /// @param pluginId plugin ID
    /// @throws IOException if the installed dependency graph cannot be read
    private void disablePluginLocked(String pluginId) throws IOException {
        disablePluginLocked(pluginId, new HashSet<>());
        saveStates();
    }

    /// Disables one plugin after recursively clearing every executable dependent's desired enablement.
    ///
    /// The immutable installed graph covers plugins that failed before registration or are waiting for restart. Loaded
    /// manifests are also considered so an updated package cannot hide a dependency edge still active in this process.
    ///
    /// @param pluginId plugin ID to disable
    /// @param visited IDs already processed during reverse traversal
    /// @throws IOException if installed manifests or runtime bindings cannot be read
    private void disablePluginLocked(
            String pluginId,
            Set<String> visited
    ) throws IOException {
        if (!visited.add(pluginId)) {
            return;
        }
        @Unmodifiable List<String> dependentIds = dependencyPlanner.findBlockingDependents(
                pluginId,
                plugins,
                pendingUninstall
        );
        for (String dependentId : dependentIds) {
            disablePluginLocked(dependentId, visited);
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
            container.suspendCapabilitySession();
            try {
                runPluginCallback(
                        container.getContext().getClassLoader(),
                        container.getPlugin()::onDisable
                );
                LOG.info("Disabled plugin: " + pluginId);
            } catch (RuntimeException | Error exception) {
                LOG.error("Failed to disable plugin: " + pluginId, exception);
            } finally {
                if (container.getManifest().getPluginKind() == PluginKind.RUNTIME_PROVIDER) {
                    runtimeSupervisor.hostDisabled(pluginId);
                }
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
                loadStates();
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
    /// @throws IOException if dependency discovery fails or an external runtime payload cannot release its handle
    private void unloadPluginLocked(String pluginId) throws IOException {
        // TODO: Add FXUtils.checkFxUserThread() once background callers are refactored to schedule on FX thread
        stateLock.readLock().lock();
        List<PluginContainer> pluginsCopy;
        try {
            pluginsCopy = List.copyOf(plugins);
        } finally {
            stateLock.readLock().unlock();
        }
        
        @Unmodifiable List<String> dependentIds = dependencyPlanner.findBlockingDependents(
                pluginId,
                pluginsCopy,
                pendingUninstall
        );
        for (String dependentId : dependentIds) {
            unloadPluginLocked(dependentId);
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

        container.closeCapabilitySession();
        container.revokeCapabilityTokens();
        if (container.getManifest().getPluginKind() == PluginKind.RUNTIME_PROVIDER) {
            container.closeRuntimeProviderRegistrations();
        }
        try {
            runPluginCallback(
                    container.getContext().getClassLoader(),
                    container.getPlugin()::onUnload
            );
        } catch (RuntimeException | Error exception) {
            if (isExternalRuntimePayload(container.getManifest())) {
                if (exception instanceof Error error) {
                    throw error;
                }
                if (exception instanceof UncheckedIOException uncheckedIOException) {
                    throw uncheckedIOException.getCause();
                }
                throw new IOException("External runtime payload unload failed: " + pluginId, exception);
            }
            LOG.warning("Plugin onUnload failed: " + pluginId, exception);
        }
        if (container.getManifest().getPluginKind() != PluginKind.RUNTIME_PROVIDER) {
            container.closeRuntimeProviderRegistrations();
        }
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
        return inspectPluginPackage(sourcePackage, true);
    }

    /// Inspects a Store-staged package while deferring non-Java runtime selection to the confirmed batch plan.
    ///
    /// Java packages, including Runtime Hosts, still pass the complete current-process compatibility gate. External
    /// runtime consumers are bound against the prospective Provider graph under the mutation lock during publication.
    ///
    /// @param sourcePackage Store-staged `.npl` package
    /// @return immutable package inspection
    /// @throws IOException if the package, manifest, Java compatibility, or digest is invalid
    public LocalPluginInspection inspectStorePluginPackage(Path sourcePackage) throws IOException {
        return inspectPluginPackage(sourcePackage, false);
    }

    /// Creates an immutable inspection with caller-selected external-runtime availability enforcement.
    ///
    /// @param sourcePackage package to inspect
    /// @param requireAvailableExternalRuntime whether non-Java runtimes must already be process-registered
    /// @return immutable package inspection
    /// @throws IOException if package validation, compatibility, or hashing fails
    private LocalPluginInspection inspectPluginPackage(
            Path sourcePackage,
            boolean requireAvailableExternalRuntime
    ) throws IOException {
        Path source = sourcePackage.toAbsolutePath().normalize();
        PluginPackageRepository.validateLocalPackage(source);

        String initialSha256 = PluginPackageVersions.calculateSha256(source);
        PluginManifest manifest = packageRepository.readManifest(source);
        String verifiedSha256 = PluginPackageVersions.calculateSha256(source);
        if (!initialSha256.equals(verifiedSha256)) {
            throw new IOException("Plugin package changed while it was being inspected: " + source);
        }
        if (requireAvailableExternalRuntime || PluginRuntimeTypes.JAVA.equals(manifest.getRuntime())) {
            requireCompatible(manifest);
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
        loadStates();
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
        if (loaded != null && !loadedBefore.equals(loadedAfter)) {
            @Nullable PluginContainer loadedContainer = pluginMap.get(pluginId);
            if (loadedContainer != null && isExternalRuntimePayload(loadedContainer.getManifest())) {
                loadedContainer.rotateCapabilitySession();
            } else {
                permissionAuthority.revokeArtifact(PluginArtifactIdentity.of(
                        loaded.getManifest(), loaded.getArtifact().getSha256()));
            }
        }
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
            loadStates();
            @Unmodifiable Map<String, PluginManifest> manifests = Map.copyOf(
                    dependencyPlanner.readInstallPlanningManifests(plugins, pendingUninstall)
            );
            @Unmodifiable Map<String, PluginArtifactIdentity> artifacts =
                    installationStateGuard.resolvePlanningArtifactIdentities(manifests);
            Map<String, PluginArtifactIdentity> reusable = new LinkedHashMap<>();
            Map<String, PluginArtifactIdentity> activatable = new LinkedHashMap<>();
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
                    continue;
                }
                @Nullable PluginArtifactIdentity activatableIdentity = reusePolicy.resolveActivatableIdentity(
                        entry.getKey(),
                        entry.getValue(),
                        enabledStates
                );
                if (activatableIdentity != null) {
                    PluginArtifactIdentity plannedIdentity = Objects.requireNonNull(artifacts.get(entry.getKey()));
                    if (!plannedIdentity.equals(activatableIdentity)) {
                        throw new IOException("Plugin artifact changed while the installation plan was captured: "
                                + entry.getKey());
                    }
                    activatable.put(entry.getKey(), activatableIdentity);
                }
            }
            return new PluginInstallationPlanningSnapshot(
                    manifests,
                    artifacts,
                    Map.copyOf(reusable),
                    Map.copyOf(activatable)
            );
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
            loadStates();
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
        return stagePluginInstallationsOnLifecycleThread(inspections, () -> mutationLock.call(
                () -> stagePluginInstallationsLocked(
                inspections,
                grantsByPluginId,
                Map.of(),
                false,
                expectedPriorArtifacts,
                Map.of(),
                PluginRuntimeInstallAuthorization.empty()
        )));
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
        return stagePluginInstallationsOnLifecycleThread(inspections, () -> mutationLock.call(
                () -> stagePluginInstallationsLocked(
                inspections,
                grantsByPluginId,
                expectedSnapshot,
                true,
                expectedPriorArtifacts,
                Map.of(),
                PluginRuntimeInstallAuthorization.empty()
        )));
    }

    /// Publishes a confirmed Store plan with runtime Provider bindings and explicit custom-source receipts.
    ///
    /// Runtime authorization is validated before transaction recovery, package inspection, or journal preparation.
    ///
    /// @param inspections immutable inspected packages in dependency-first order
    /// @param grantsByPluginId explicit user decisions indexed by inspected plugin ID
    /// @param expectedReusableArtifacts exact identities for unchanged plan entries
    /// @param expectedPriorArtifacts exact prior state for every installation or update
    /// @param certificationReceipts proof-backed receipts for certified replacement artifacts
    /// @param runtimeAuthorization confirmed bindings, Host enablements, and custom-source receipts
    /// @return immutable replacement manifests in the supplied order
    /// @throws IOException if current state differs from the confirmed plan or publication fails
    public @Unmodifiable List<PluginManifest> stagePluginInstallations(
            @Unmodifiable List<LocalPluginInspection> inspections,
            @Unmodifiable Map<String, @Unmodifiable Set<PluginPermission>> grantsByPluginId,
            @Unmodifiable Map<String, PluginArtifactIdentity> expectedReusableArtifacts,
            @Unmodifiable Map<String, Optional<PluginArtifactIdentity>> expectedPriorArtifacts,
            @Unmodifiable Map<String, PluginCertificationReceipt> certificationReceipts,
            PluginRuntimeInstallAuthorization runtimeAuthorization
    ) throws IOException {
        administrativeGuard.checkTrustedCaller();
        runtimeAuthorization.requireAcknowledgements();
        validateDangerousPermissionAcknowledgements(runtimeAuthorization, inspections);
        validateExpectedPackageRuntimeContracts(runtimeAuthorization, inspections);
        @Unmodifiable Map<String, PluginArtifactIdentity> reusableSnapshot = Map.copyOf(expectedReusableArtifacts);
        @Unmodifiable Map<String, Optional<PluginArtifactIdentity>> priorSnapshot = Map.copyOf(expectedPriorArtifacts);
        @Unmodifiable Map<String, PluginCertificationReceipt> receiptSnapshot = Map.copyOf(certificationReceipts);
        return stagePluginInstallationsOnLifecycleThread(inspections, () -> mutationLock.call(
                () -> stagePluginInstallationsLocked(
                inspections,
                grantsByPluginId,
                reusableSnapshot,
                true,
                priorSnapshot,
                receiptSnapshot,
                runtimeAuthorization
        )));
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
        return stagePluginInstallationsOnLifecycleThread(inspections, () -> mutationLock.call(
                () -> stagePluginInstallationsLocked(
                inspections,
                grantsByPluginId,
                reusableSnapshot,
                true,
                priorSnapshot,
                Map.of(),
                PluginRuntimeInstallAuthorization.empty()
        )));
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
        return stagePluginInstallationsOnLifecycleThread(inspections, () -> mutationLock.call(
                () -> stagePluginInstallationsLocked(
                inspections,
                grantsByPluginId,
                reusableSnapshot,
                true,
                priorSnapshot,
                receiptSnapshot,
                PluginRuntimeInstallAuthorization.empty()
        )));
    }

    /// Executes a stage mutation on JavaFX only when it must replace a live enabled runtime Host.
    ///
    /// The dispatch happens before the mutation lock is acquired. This prevents the caller from holding the lock while
    /// JavaFX lifecycle code re-enters binding and permission stores guarded by the same lock.
    ///
    /// @param inspections immutable inspected replacement packages
    /// @param action complete lock-owning stage mutation
    /// @param <T> non-null mutation result type
    /// @return mutation result
    /// @throws IOException if dispatch, staging, or the lifecycle mutation fails
    private <T> T stagePluginInstallationsOnLifecycleThread(
            @Unmodifiable List<LocalPluginInspection> inspections,
            PluginMutationLock.IOCallable<T> action
    ) throws IOException {
        if (!mayReplaceInstalledRuntimeProvider(inspections) || Platform.isFxApplicationThread()) {
            return action.call();
        }
        FutureTask<T> task = new FutureTask<>(action::call);
        try {
            Platform.runLater(task);
        } catch (IllegalStateException exception) {
            throw new IOException("JavaFX is unavailable for live runtime Provider replacement", exception);
        }
        try {
            return task.get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for live runtime Provider replacement", exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof IOException ioException) {
                throw ioException;
            }
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IOException("Live runtime Provider replacement failed", cause);
        }
    }

    /// Returns whether an installation batch may replace an installed runtime Host.
    ///
    /// Existing Host replacements are dispatched conservatively so discovery or enablement cannot race the initial
    /// loaded-state observation and move lifecycle work onto a background thread. New Hosts and ordinary plugins keep
    /// their caller thread because they cannot own a live graph yet.
    ///
    /// @param inspections immutable inspected replacement packages
    /// @return whether JavaFX lifecycle execution is required
    private static boolean mayReplaceInstalledRuntimeProvider(
            @Unmodifiable List<LocalPluginInspection> inspections
    ) {
        return inspections.stream().anyMatch(inspection ->
                inspection.manifest.getPluginKind() == PluginKind.RUNTIME_PROVIDER
                        && inspection.oldManifest != null);
    }

    /// Publishes an installation batch while the shared package, state, and permission lock is held.
    ///
    /// @param inspections immutable inspected packages in dependency-first order
    /// @param grantsByPluginId explicit user decisions indexed by inspected plugin ID
    /// @param expectedReusableArtifacts exact dependency identities captured during planning
    /// @param requireExpectedReusableArtifacts whether every reused dependency must match the planning snapshot
    /// @param expectedPriorArtifacts exact prior state for every replacement ID
    /// @param certificationReceipts proof-backed receipts for certified replacements
    /// @param runtimeAuthorization confirmed virtual bindings and Provider enablements
    /// @return immutable replacement manifests in the supplied order
    /// @throws IOException if validation, persistence, publication, or rollback fails
    private @Unmodifiable List<PluginManifest> stagePluginInstallationsLocked(
            @Unmodifiable List<LocalPluginInspection> inspections,
            @Unmodifiable Map<String, @Unmodifiable Set<PluginPermission>> grantsByPluginId,
            @Unmodifiable Map<String, PluginArtifactIdentity> expectedReusableArtifacts,
            boolean requireExpectedReusableArtifacts,
            @Unmodifiable Map<String, Optional<PluginArtifactIdentity>> expectedPriorArtifacts,
            @Unmodifiable Map<String, PluginCertificationReceipt> certificationReceipts,
            PluginRuntimeInstallAuthorization runtimeAuthorization
    ) throws IOException {
        runtimeAuthorization.requireAcknowledgements();
        loadStates();
        if (inspections.isEmpty()) {
            if (!grantsByPluginId.isEmpty()
                    || !expectedReusableArtifacts.isEmpty()
                    || !expectedPriorArtifacts.isEmpty()
                    || !certificationReceipts.isEmpty()
                    || !runtimeAuthorization.isEmpty()) {
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
            if (requiresLivePublicationCompatibility(manifest, runtimeAuthorization)) {
                requireCompatible(manifest);
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
        @Unmodifiable Map<String, RuntimeProviderBinding> prospectiveRuntimeBindings =
                createProspectiveRuntimeBindings(
                        Set.copyOf(replacements.keySet()),
                        runtimeAuthorization.getRuntimeBindings()
                );
        validateRuntimeInstallAuthorization(
                runtimeAuthorization,
                Map.copyOf(effectiveManifests),
                Set.copyOf(replacements.keySet()),
                expectedReusableArtifacts
        );
        dependencyPlanner.validateReplacementGraph(
                effectiveManifests,
                replacements.keySet(),
                prospectiveRuntimeBindings
        );
        Set<String> virtualProviderIds = runtimeAuthorization.getRuntimeBindings().values().stream()
                .map(RuntimeProviderBinding::providerId)
                .filter(providerId -> !replacements.containsKey(providerId))
                .collect(Collectors.toUnmodifiableSet());
        Map<String, PluginArtifactIdentity> concreteReusableArtifacts = new LinkedHashMap<>(expectedReusableArtifacts);
        virtualProviderIds.forEach(concreteReusableArtifacts::remove);
        @Unmodifiable Set<String> plannedDependencyIds = requireExpectedReusableArtifacts
                ? reusePolicy.validateDependencyClosure(
                        Map.copyOf(effectiveManifests),
                        Set.copyOf(replacements.keySet()),
                        Set.copyOf(enabledStates),
                        Map.copyOf(concreteReusableArtifacts)
                )
                : reusePolicy.validateDependencyClosure(
                        Map.copyOf(effectiveManifests),
                        Set.copyOf(replacements.keySet()),
                        Set.copyOf(enabledStates)
                );

        Set<String> nextEnabledStates = new HashSet<>(enabledStates);
        nextEnabledStates.addAll(plannedDependencyIds);
        nextEnabledStates.addAll(runtimeAuthorization.getEnablementPluginIds());
        Set<String> nextPendingUninstall = new HashSet<>(pendingUninstall);
        Set<String> nextQuarantinedStates = new HashSet<>(quarantinedStates);
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
        LiveRuntimeProviderSwapSession liveSwapSession = captureLiveRuntimeProviderSwapSession(
                installedBefore,
                inspectionsById,
                expectedPriorArtifacts,
                runtimeBindingStore.readStrict(),
                Set.copyOf(enabledStates),
                Set.copyOf(pendingUninstall),
                Set.copyOf(quarantinedStates)
        );
        // Runtime providers are live process state, so close the planning-to-publication compatibility window.
        for (PluginManifest replacement : replacements.values()) {
            if (requiresLivePublicationCompatibility(replacement, runtimeAuthorization)) {
                requireCompatible(replacement);
            }
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
                    runtimeBindingStore.replaceStrict(prospectiveRuntimeBindings);
                },
                () -> stateStore.saveStrict(
                        nextEnabledStates,
                        nextPendingUninstall,
                        nextQuarantinedStates,
                        quarantineReport
                ),
                () -> activateLiveRuntimeProviderReplacements(
                        liveSwapSession,
                        inspectionsById,
                        nextEnabledStates,
                        nextPendingUninstall,
                        nextQuarantinedStates
                ),
                () -> {
                    permissionService.reload();
                    restoreLiveRuntimeProviderReplacements(liveSwapSession);
                }
        );

        enabledStates.clear();
        enabledStates.addAll(nextEnabledStates);
        pendingUninstall.clear();
        pendingUninstall.addAll(nextPendingUninstall);
        quarantinedStates.clear();
        quarantinedStates.addAll(nextQuarantinedStates);
        for (Map.Entry<String, PluginManifest> replacement : replacements.entrySet()) {
            String pluginId = replacement.getKey();
            LocalPluginInspection inspection = Objects.requireNonNull(inspectionsById.get(pluginId));
            PluginArtifactIdentity identity = PluginArtifactIdentity.of(
                    inspection.manifest,
                    inspection.sha256
            );
            clearArtifactState(pluginId);
            runtimeState.remember(identity);
            Path installedPackage = pluginsDirectory.resolve(pluginId + ".npl").toAbsolutePath().normalize();
            @Nullable PluginContainer container = pluginMap.get(pluginId);
            if (liveSwapSession.liveGraphIds().contains(pluginId)
                    && container != null
                    && identity.equals(loadedIdentity(container))) {
                container.setNplFile(installedPackage);
                container.setRestartRequired(false);
                setLoadedRuntimeStatus(
                        container,
                        container.isEnabled()
                                ? PluginRuntimeStatus.ENABLED
                                : PluginRuntimeStatus.INSTALLED_DISABLED,
                        null
                );
                LOG.info("Activated live runtime Provider replacement: " + pluginId + " "
                        + replacement.getValue().getVersion());
                continue;
            }
            setRuntimeStatus(identity, PluginRuntimeStatus.WAITING_FOR_RESTART, null);
            if (container != null) {
                container.setNplFile(installedPackage);
                container.setRestartRequired(true);
            }
            LOG.info("Staged plugin for next restart: " + pluginId + " " + replacement.getValue().getVersion());
        }
        return List.copyOf(replacements.values());
    }

    /// Captures every active Host replacement and its exact loaded dependent graph before publication.
    ///
    /// @param installedBefore immutable installed manifests before publication
    /// @param inspections immutable replacement inspections indexed by plugin ID
    /// @param expectedPriorArtifacts confirmed prior artifact for every replacement ID
    /// @param runtimeBindings immutable live dependent-to-Provider bindings before publication
    /// @param originalEnabledStates immutable desired enablement before publication
    /// @param originalPendingUninstall immutable pending removals before publication
    /// @param originalQuarantinedStates immutable recovery quarantine before publication
    /// @return mutable transaction-local swap session containing immutable lifecycle snapshots
    /// @throws IOException if two replaced Host graphs overlap
    private LiveRuntimeProviderSwapSession captureLiveRuntimeProviderSwapSession(
            @Unmodifiable Map<String, PluginManifest> installedBefore,
            @Unmodifiable Map<String, LocalPluginInspection> inspections,
            @Unmodifiable Map<String, Optional<PluginArtifactIdentity>> expectedPriorArtifacts,
            @Unmodifiable Map<String, RuntimeProviderBinding> runtimeBindings,
            @Unmodifiable Set<String> originalEnabledStates,
            @Unmodifiable Set<String> originalPendingUninstall,
            @Unmodifiable Set<String> originalQuarantinedStates
    ) throws IOException {
        Map<String, PluginContainer> loadedById = new LinkedHashMap<>();
        stateLock.readLock().lock();
        try {
            for (PluginContainer container : plugins) {
                loadedById.put(container.getManifest().getId(), container);
            }
        } finally {
            stateLock.readLock().unlock();
        }

        List<LiveRuntimeProviderSwap> swaps = new ArrayList<>();
        Set<String> claimedPluginIds = new HashSet<>();
        for (Map.Entry<String, LocalPluginInspection> entry : inspections.entrySet()) {
            String providerId = entry.getKey();
            @Nullable PluginContainer providerContainer = loadedById.get(providerId);
            if (entry.getValue().manifest.getPluginKind() != PluginKind.RUNTIME_PROVIDER
                    || !installedBefore.containsKey(providerId)
                    || providerContainer == null
                    || !providerContainer.isEnabled()) {
                continue;
            }
            PluginArtifactIdentity loadedProviderIdentity = loadedIdentity(providerContainer);
            Optional<PluginArtifactIdentity> expectedPrior = Objects.requireNonNull(
                    expectedPriorArtifacts.get(providerId)
            );
            if (expectedPrior.isEmpty() || !loadedProviderIdentity.equals(expectedPrior.get())) {
                throw new IOException("Live runtime Provider loaded artifact does not match confirmed prior artifact: "
                        + providerId);
            }
            List<LivePluginSnapshot> dependents = new ArrayList<>();
            collectLoadedDependents(
                    providerId,
                    loadedById,
                    runtimeBindings,
                    new HashSet<>(),
                    dependents
            );
            if (!claimedPluginIds.add(providerId)) {
                throw new IOException("Overlapping live runtime Provider replacement graph: " + providerId);
            }
            for (LivePluginSnapshot dependent : dependents) {
                if (!claimedPluginIds.add(dependent.identity().getPluginId())) {
                    throw new IOException("Overlapping live runtime Provider replacement graph: "
                            + dependent.identity().getPluginId());
                }
            }
            swaps.add(new LiveRuntimeProviderSwap(
                    snapshot(providerContainer),
                    List.copyOf(dependents)
            ));
        }
        return new LiveRuntimeProviderSwapSession(
                List.copyOf(swaps),
                Set.copyOf(originalEnabledStates),
                Set.copyOf(originalPendingUninstall),
                Set.copyOf(originalQuarantinedStates)
        );
    }

    /// Collects loaded dependents in leaf-first unload order from the pre-publication graph.
    ///
    /// @param dependencyId dependency whose loaded dependents are collected
    /// @param loadedById immutable loaded containers indexed by plugin ID
    /// @param runtimeBindings immutable pre-publication runtime bindings
    /// @param visited plugin IDs already traversed
    /// @param ordered mutable leaf-first result
    private void collectLoadedDependents(
            String dependencyId,
            @Unmodifiable Map<String, PluginContainer> loadedById,
            @Unmodifiable Map<String, RuntimeProviderBinding> runtimeBindings,
            Set<String> visited,
            List<LivePluginSnapshot> ordered
    ) {
        if (!visited.add(dependencyId)) {
            return;
        }
        loadedById.entrySet().stream()
                .filter(entry -> directlyDependsOn(entry.getValue().getManifest(), dependencyId, runtimeBindings))
                .filter(entry -> !visited.contains(entry.getKey()))
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    collectLoadedDependents(entry.getKey(), loadedById, runtimeBindings, visited, ordered);
                    ordered.add(snapshot(entry.getValue()));
                });
    }

    /// Returns whether one loaded manifest has a direct ordinary or virtual runtime edge to a dependency.
    ///
    /// @param manifest loaded dependent manifest
    /// @param dependencyId candidate dependency ID
    /// @param runtimeBindings immutable pre-publication runtime bindings
    /// @return whether the direct edge exists
    private static boolean directlyDependsOn(
            PluginManifest manifest,
            String dependencyId,
            @Unmodifiable Map<String, RuntimeProviderBinding> runtimeBindings
    ) {
        if (manifest.getDependencies().contains(dependencyId)) {
            return true;
        }
        @Nullable RuntimeProviderBinding binding = runtimeBindings.get(manifest.getId());
        return binding != null && binding.providerId().equals(dependencyId);
    }

    /// Creates one exact loaded lifecycle snapshot.
    ///
    /// @param container loaded plugin container
    /// @return immutable exact identity and enablement snapshot
    private static LivePluginSnapshot snapshot(PluginContainer container) {
        return new LivePluginSnapshot(loadedIdentity(container), container.isEnabled());
    }

    /// Returns the exact artifact identity represented by one loaded container.
    ///
    /// @param container loaded plugin container
    /// @return exact loaded artifact identity
    private static PluginArtifactIdentity loadedIdentity(PluginContainer container) {
        return PluginArtifactIdentity.of(
                container.getManifest(),
                container.getContext().getArtifactSha256()
        );
    }

    /// Replaces every captured live Host graph while the package journal remains prepared.
    ///
    /// @param session transaction-local old graph snapshots and progress
    /// @param inspections immutable replacement inspections indexed by plugin ID
    /// @param nextEnabledStates immutable desired enablement after publication
    /// @param nextPendingUninstall immutable pending removals after publication
    /// @param nextQuarantinedStates immutable recovery quarantine after publication
    /// @throws IOException if teardown, canonical loading, activation, health, or dependent restoration fails
    private void activateLiveRuntimeProviderReplacements(
            LiveRuntimeProviderSwapSession session,
            @Unmodifiable Map<String, LocalPluginInspection> inspections,
            @Unmodifiable Set<String> nextEnabledStates,
            @Unmodifiable Set<String> nextPendingUninstall,
            @Unmodifiable Set<String> nextQuarantinedStates
    ) throws IOException {
        if (session.swaps().isEmpty()) {
            return;
        }
        for (LiveRuntimeProviderSwap swap : session.swaps()) {
            session.markStarted(swap.provider().identity().getPluginId());
            unloadLiveRuntimeGraph(swap);
        }
        replaceDesiredState(nextEnabledStates, nextPendingUninstall, nextQuarantinedStates);
        for (LiveRuntimeProviderSwap swap : session.swaps()) {
            loadLiveRuntimeGraph(swap, inspections, nextEnabledStates, true);
        }
        replaceDesiredState(nextEnabledStates, nextPendingUninstall, nextQuarantinedStates);
    }

    /// Restores each started old Host graph after the journal has restored packages and documents.
    ///
    /// @param session transaction-local old graph snapshots and progress
    /// @throws IOException if new graph cleanup or exact old graph restoration fails
    private void restoreLiveRuntimeProviderReplacements(LiveRuntimeProviderSwapSession session) throws IOException {
        @Unmodifiable List<LiveRuntimeProviderSwap> started = session.startedSwaps();
        if (started.isEmpty()) {
            return;
        }
        @Nullable IOException failure = null;
        for (LiveRuntimeProviderSwap swap : started) {
            try {
                unloadLiveRuntimeGraph(swap);
            } catch (IOException | RuntimeException exception) {
                failure = appendLifecycleFailure(
                        failure,
                        new IOException("Failed to clean replacement runtime Provider graph: "
                                + swap.provider().identity().getPluginId(), exception)
                );
            }
        }
        if (failure == null) {
            try {
                replaceDesiredState(
                        session.originalEnabledStates(),
                        session.originalPendingUninstall(),
                        session.originalQuarantinedStates()
                );
                for (LiveRuntimeProviderSwap swap : started) {
                    loadLiveRuntimeGraph(swap, Map.of(), session.originalEnabledStates(), false);
                }
                replaceDesiredState(
                        session.originalEnabledStates(),
                        session.originalPendingUninstall(),
                        session.originalQuarantinedStates()
                );
                return;
            } catch (IOException | RuntimeException exception) {
                failure = new IOException("Failed to restore the previous live runtime Provider graph", exception);
            }
        }

        for (LiveRuntimeProviderSwap swap : started) {
            try {
                unloadLiveRuntimeGraph(swap);
            } catch (IOException | RuntimeException cleanupFailure) {
                failure = appendLifecycleFailure(
                        Objects.requireNonNull(failure),
                        new IOException("Failed to clean partially restored runtime Provider graph: "
                                + swap.provider().identity().getPluginId(), cleanupFailure)
                );
            }
        }
        Set<String> disabled = new HashSet<>(session.originalEnabledStates());
        for (LiveRuntimeProviderSwap swap : started) {
            disabled.remove(swap.provider().identity().getPluginId());
            swap.dependents().forEach(dependent -> disabled.remove(dependent.identity().getPluginId()));
        }
        try {
            replaceDesiredState(
                    Set.copyOf(disabled),
                    session.originalPendingUninstall(),
                    session.originalQuarantinedStates()
            );
        } catch (IOException stateFailure) {
            Objects.requireNonNull(failure).addSuppressed(stateFailure);
        }
        throw Objects.requireNonNull(failure);
    }

    /// Unloads one captured graph in leaf-first order and verifies that its Host registration is gone.
    ///
    /// @param swap exact graph snapshot whose plugin IDs identify current containers
    /// @throws IOException if a lifecycle refuses teardown or any graph member remains live
    private void unloadLiveRuntimeGraph(LiveRuntimeProviderSwap swap) throws IOException {
        @Nullable IOException failure = null;
        for (LivePluginSnapshot dependent : swap.dependents()) {
            try {
                unloadPluginLocked(dependent.identity().getPluginId());
            } catch (IOException | RuntimeException exception) {
                failure = appendLifecycleFailure(
                        failure,
                        new IOException("Failed to unload runtime Provider dependent: "
                                + dependent.identity().getPluginId(), exception)
                );
            }
        }
        String providerId = swap.provider().identity().getPluginId();
        try {
            unloadPluginLocked(providerId);
        } catch (IOException | RuntimeException exception) {
            failure = appendLifecycleFailure(
                    failure,
                    new IOException("Failed to unload runtime Provider Host: " + providerId, exception)
            );
        }
        for (LivePluginSnapshot dependent : swap.dependents()) {
            if (pluginMap.containsKey(dependent.identity().getPluginId())) {
                failure = appendLifecycleFailure(failure, new IOException(
                        "Runtime Provider dependent remains loaded: " + dependent.identity().getPluginId()));
            }
        }
        if (pluginMap.containsKey(providerId) || runtimeProviders.findById(providerId).isPresent()) {
            failure = appendLifecycleFailure(
                    failure,
                    new IOException("Runtime Provider Host remains live after unload: " + providerId)
            );
        }
        if (failure != null) {
            throw failure;
        }
    }

    /// Loads one exact Host graph through the ordinary manager lifecycle and verifies final identity and enablement.
    ///
    /// @param swap old graph topology and fallback identities
    /// @param inspections immutable replacements whose published canonical identities take precedence
    /// @param desiredEnabledStates immutable desired final enablement
    /// @param replacementsCanonical whether replacement identities must resolve at canonical package paths
    /// @throws IOException if any exact package, dependency, binding, activation, or final state is invalid
    private void loadLiveRuntimeGraph(
            LiveRuntimeProviderSwap swap,
            @Unmodifiable Map<String, LocalPluginInspection> inspections,
            @Unmodifiable Set<String> desiredEnabledStates,
            boolean replacementsCanonical
    ) throws IOException {
        Map<String, PluginPackageCandidate> candidates = new LinkedHashMap<>();
        List<LivePluginSnapshot> loadOrder = new ArrayList<>();
        loadOrder.add(swap.provider());
        List<LivePluginSnapshot> dependencyFirst = new ArrayList<>(swap.dependents());
        java.util.Collections.reverse(dependencyFirst);
        loadOrder.addAll(dependencyFirst);
        for (LivePluginSnapshot snapshot : loadOrder) {
            String pluginId = snapshot.identity().getPluginId();
            @Nullable LocalPluginInspection replacement = inspections.get(pluginId);
            PluginArtifactIdentity targetIdentity = replacement == null
                    ? snapshot.identity()
                    : PluginArtifactIdentity.of(replacement.manifest, replacement.sha256);
            candidates.put(
                    pluginId,
                    resolveExactCandidate(targetIdentity, replacementsCanonical && replacement != null)
            );
        }

        Set<String> graphIds = new HashSet<>(candidates.keySet());
        enabledStates.addAll(graphIds);
        Map<String, PluginVisitState> visitStates = new HashMap<>();
        Set<String> failed = new HashSet<>();
        Map<String, Throwable> activationFailures = new LinkedHashMap<>();
        for (LivePluginSnapshot snapshot : loadOrder) {
            String pluginId = snapshot.identity().getPluginId();
            PluginPackageCandidate candidate = Objects.requireNonNull(candidates.get(pluginId));
            if (!loadCandidate(candidate, candidates, visitStates, failed, activationFailures)) {
                @Nullable String detail = getPluginRuntimeDetail(pluginId);
                @Nullable Throwable activationFailure = activationFailures.values().stream().findFirst().orElse(null);
                String diagnostic = activationFailure == null
                        ? detail == null ? "" : detail
                        : lifecycleFailureDiagnostic(activationFailure);
                throw new IOException("Live runtime Provider graph activation failed: " + pluginId
                        + (diagnostic.isBlank() ? "" : " (" + diagnostic + ")"), activationFailure);
            }
        }
        for (LivePluginSnapshot snapshot : swap.dependents()) {
            String pluginId = snapshot.identity().getPluginId();
            if (!desiredEnabledStates.contains(pluginId)) {
                disablePluginLocked(pluginId);
            }
        }
        if (!desiredEnabledStates.contains(swap.provider().identity().getPluginId())) {
            disablePluginLocked(swap.provider().identity().getPluginId());
        }
        for (Map.Entry<String, PluginPackageCandidate> entry : candidates.entrySet()) {
            @Nullable PluginContainer container = pluginMap.get(entry.getKey());
            if (container == null
                    || !entry.getValue().identity.equals(loadedIdentity(container))
                    || container.isEnabled() != desiredEnabledStates.contains(entry.getKey())) {
                throw new IOException("Live runtime Provider graph did not reach its exact target state: "
                        + entry.getKey());
            }
        }
    }

    /// Resolves one exact installed artifact, optionally requiring its canonical replacement path.
    ///
    /// @param identity exact artifact identity to load
    /// @param canonical whether the artifact must be the canonical transaction target
    /// @return exact verified package candidate
    /// @throws IOException if the exact artifact is absent, duplicated, or changed
    private PluginPackageCandidate resolveExactCandidate(
            PluginArtifactIdentity identity,
            boolean canonical
    ) throws IOException {
        List<Path> matches = new ArrayList<>();
        if (canonical) {
            matches.add(pluginsDirectory.resolve(identity.getPluginId() + ".npl").toAbsolutePath().normalize());
        } else {
            for (Path packageFile : packageRepository.findInstalledPackages(identity.getPluginId())) {
                PluginManifest manifest = packageRepository.readManifest(packageFile);
                String sha256 = PluginPackageVersions.calculateSha256(packageFile);
                if (identity.equals(PluginArtifactIdentity.of(manifest, sha256))) {
                    matches.add(packageFile.toAbsolutePath().normalize());
                }
            }
        }
        if (matches.size() != 1 || !Files.isRegularFile(matches.get(0), LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Expected exactly one installed runtime graph artifact: " + identity);
        }
        Path packageFile = matches.get(0);
        PluginManifest manifest = packageRepository.readManifest(packageFile);
        String sha256 = PluginPackageVersions.calculateSha256(packageFile);
        PluginArtifactIdentity actual = PluginArtifactIdentity.of(manifest, sha256);
        if (!identity.equals(actual)) {
            throw new IOException("Installed runtime graph artifact identity changed: " + identity.getPluginId());
        }
        return new PluginPackageCandidate(packageFile, manifest, actual);
    }

    /// Replaces the manager's desired state and persists it without changing lifecycle containers.
    ///
    /// @param desiredEnabledStates immutable enabled plugin IDs
    /// @param desiredPendingUninstall immutable pending removal IDs
    /// @param desiredQuarantinedStates immutable recovery quarantine IDs
    /// @throws IOException if strict state persistence fails
    private void replaceDesiredState(
            @Unmodifiable Set<String> desiredEnabledStates,
            @Unmodifiable Set<String> desiredPendingUninstall,
            @Unmodifiable Set<String> desiredQuarantinedStates
    ) throws IOException {
        enabledStates.clear();
        enabledStates.addAll(desiredEnabledStates);
        pendingUninstall.clear();
        pendingUninstall.addAll(desiredPendingUninstall);
        quarantinedStates.clear();
        quarantinedStates.addAll(desiredQuarantinedStates);
        stateStore.saveStrict(enabledStates, pendingUninstall, quarantinedStates, quarantineReport);
    }

    /// Appends one lifecycle failure to an optional aggregate.
    ///
    /// @param current current aggregate or `null`
    /// @param next next failure
    /// @return aggregate rooted at the first failure
    private static IOException appendLifecycleFailure(@Nullable IOException current, IOException next) {
        if (current == null) {
            return next;
        }
        current.addSuppressed(next);
        return current;
    }

    /// Formats non-empty messages from one lifecycle exception chain without discarding the root cause.
    ///
    /// @param failure original lifecycle failure
    /// @return colon-separated diagnostic chain
    private static String lifecycleFailureDiagnostic(Throwable failure) {
        List<String> messages = new ArrayList<>();
        @Nullable Throwable current = failure;
        while (current != null) {
            @Nullable String message = current.getMessage();
            if (message != null && !message.isBlank() && !messages.contains(message)) {
                messages.add(message);
            }
            current = current.getCause();
        }
        return String.join(": ", messages);
    }

    /// Returns whether publication depends on this manager's current live runtime registry.
    ///
    /// A confirmed Store batch carries a prospective binding for each external payload and validates that future
    /// graph separately. Local and compatibility overloads carry no such authorization and must recheck the current
    /// Provider immediately before package publication.
    ///
    /// @param manifest replacement manifest
    /// @param runtimeAuthorization confirmed prospective runtime edges
    /// @return whether current live compatibility must be enforced
    private static boolean requiresLivePublicationCompatibility(
            PluginManifest manifest,
            PluginRuntimeInstallAuthorization runtimeAuthorization
    ) {
        return PluginRuntimeTypes.JAVA.equals(manifest.getRuntime())
                || !runtimeAuthorization.getRuntimeBindings().containsKey(manifest.getId());
    }

    /// Applies dependent-owned binding removals and replacements to the current durable binding snapshot.
    ///
    /// @param replacementIds package IDs replaced by the transaction
    /// @param replacementBindings confirmed new bindings for external-runtime dependents
    /// @return immutable complete prospective binding document
    /// @throws IOException if the current binding document is invalid
    private @Unmodifiable Map<String, RuntimeProviderBinding> createProspectiveRuntimeBindings(
            @Unmodifiable Set<String> replacementIds,
            @Unmodifiable Map<String, RuntimeProviderBinding> replacementBindings
    ) throws IOException {
        if (!replacementIds.containsAll(replacementBindings.keySet())) {
            throw new IOException("Runtime binding replacement belongs to a package outside the install batch");
        }
        Map<String, RuntimeProviderBinding> prospective = new LinkedHashMap<>(runtimeBindingStore.readStrict());
        replacementIds.forEach(prospective::remove);
        prospective.putAll(replacementBindings);
        return Map.copyOf(prospective);
    }

    /// Verifies that Store authorization exactly covers every changed artifact declaring a dangerous permission.
    ///
    /// @param authorization confirmed Store authorization
    /// @param inspections inspected replacement packages
    /// @throws IOException if the authorization omits or invents a dangerous-permission artifact
    private static void validateDangerousPermissionAcknowledgements(
            PluginRuntimeInstallAuthorization authorization,
            @Unmodifiable List<LocalPluginInspection> inspections
    ) throws IOException {
        Set<String> actualDangerousPluginIds = inspections.stream()
                .map(LocalPluginInspection::getManifest)
                .filter(manifest -> java.util.stream.Stream.concat(
                                manifest.getRequiredPermissions().stream(),
                                manifest.getOptionalPermissions().stream())
                        .anyMatch(permission -> PluginPermissionTier.tierOf(permission)
                                == PluginPermissionTier.DANGEROUS))
                .map(PluginManifest::getId)
                .collect(Collectors.toUnmodifiableSet());
        if (!actualDangerousPluginIds.equals(authorization.getRequiredDangerousPermissionPluginIds())) {
            throw new IOException("Dangerous-permission confirmation does not match inspected Store packages");
        }
    }

    /// Verifies exact Store runtime metadata against every inspected downloaded package before transaction recovery.
    ///
    /// @param authorization confirmed Store authorization
    /// @param inspections inspected replacement packages
    /// @throws IOException if a package is absent from the plan or differs from its confirmed runtime contract
    private static void validateExpectedPackageRuntimeContracts(
            PluginRuntimeInstallAuthorization authorization,
            @Unmodifiable List<LocalPluginInspection> inspections
    ) throws IOException {
        Map<String, PluginPackageRuntimeContract> actualContracts = new LinkedHashMap<>();
        for (LocalPluginInspection inspection : inspections) {
            PluginManifest manifest = inspection.getManifest();
            if (actualContracts.putIfAbsent(
                    manifest.getId(),
                    PluginPackageRuntimeContract.fromManifest(manifest)
            ) != null) {
                throw new IOException("Plugin installation batch contains duplicate ID: " + manifest.getId());
            }
        }
        @Unmodifiable Map<String, PluginPackageRuntimeContract> expectedContracts =
                authorization.getExpectedPackageRuntimeContracts();
        if (!actualContracts.keySet().equals(expectedContracts.keySet())) {
            throw new IOException("Downloaded package set does not match confirmed Store runtime contracts");
        }
        for (Map.Entry<String, PluginPackageRuntimeContract> entry : actualContracts.entrySet()) {
            if (!entry.getValue().equals(expectedContracts.get(entry.getKey()))) {
                throw new IOException("Downloaded package runtime contract does not match Store metadata: "
                        + entry.getKey());
            }
        }
    }

    /// Validates virtual bindings and exact installed Provider identities against the prospective package graph.
    ///
    /// @param authorization confirmed runtime authorization
    /// @param effectiveManifests prospective installed manifests
    /// @param replacementIds package IDs replaced by this transaction
    /// @param expectedReusableArtifacts exact identities captured by Store planning
    /// @throws IOException if a binding or installed Provider differs from the confirmed plan
    private void validateRuntimeInstallAuthorization(
            PluginRuntimeInstallAuthorization authorization,
            @Unmodifiable Map<String, PluginManifest> effectiveManifests,
            @Unmodifiable Set<String> replacementIds,
            @Unmodifiable Map<String, PluginArtifactIdentity> expectedReusableArtifacts
    ) throws IOException {
        for (RuntimeProviderBinding binding : authorization.getRuntimeBindings().values()) {
            @Nullable PluginManifest dependent = effectiveManifests.get(binding.dependentPluginId());
            @Nullable PluginManifest provider = effectiveManifests.get(binding.providerId());
            if (dependent == null || provider == null) {
                throw new IOException("Runtime Provider binding references a missing package: "
                        + binding.dependentPluginId() + " -> " + binding.providerId());
            }
            RuntimeRequirement requirement = dependent.getRuntimeRequirement();
            boolean compatible = binding.runtime().equals(requirement.getRuntime())
                    && provider.getProvidesRuntimes().stream().anyMatch(declaration ->
                    supportsRuntimeRequirement(declaration, requirement));
            if (!compatible) {
                throw new IOException("Runtime Provider " + binding.providerId()
                        + " cannot satisfy " + binding.dependentPluginId());
            }
            if (!replacementIds.contains(binding.providerId())) {
                @Nullable PluginArtifactIdentity expected = expectedReusableArtifacts.get(binding.providerId());
                @Nullable PluginArtifactIdentity current = artifactResolver.resolveInstalledIdentity(
                        binding.providerId());
                if (expected == null || !expected.equals(current)) {
                    throw new IOException("Installed Runtime Provider changed after planning: "
                            + binding.providerId());
                }
            }
        }
        if (!authorization.getRuntimeBindings().values().stream()
                .map(RuntimeProviderBinding::providerId)
                .collect(Collectors.toUnmodifiableSet())
                .containsAll(authorization.getEnablementPluginIds())) {
            throw new IOException("Runtime Provider enablement is not part of the confirmed bindings");
        }
        for (String providerId : authorization.getEnablementPluginIds()) {
            if (replacementIds.contains(providerId)) {
                throw new IOException("Runtime Provider enablement cannot replace a package: " + providerId);
            }
            PluginManifest provider = Objects.requireNonNull(effectiveManifests.get(providerId));
            @Nullable PluginArtifactIdentity expected = expectedReusableArtifacts.get(providerId);
            @Nullable PluginArtifactIdentity activatable = reusePolicy.resolveActivatableIdentity(
                    providerId,
                    provider,
                    enabledStates
            );
            if (expected == null || !expected.equals(activatable)) {
                throw new IOException("Installed Runtime Provider is no longer safely activatable: " + providerId);
            }
        }
    }

    /// Returns whether one Provider declaration satisfies the dependent's runtime, ABI, mode, and feature contract.
    ///
    /// @param declaration Provider capability declaration
    /// @param requirement dependent runtime requirement
    /// @return whether the declaration satisfies every derived requirement
    private static boolean supportsRuntimeRequirement(
            RuntimeProviderDeclaration declaration,
            RuntimeRequirement requirement
    ) {
        return declaration.getRuntime().equals(requirement.getRuntime())
                && declaration.getAbis().contains(requirement.getPluginAbi())
                && declaration.getBridgeAbi() == requirement.getBridgeAbi()
                && declaration.getExecutionModes().contains(requirement.getExecutionMode())
                && declaration.getFeatures().containsAll(requirement.getRequiredFeatures());
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
        loadStates();
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
        Set<String> nextQuarantinedStates = new HashSet<>(quarantinedStates);
        nextEnabledStates.remove(pluginId);
        nextPendingUninstall.remove(pluginId);
        nextQuarantinedStates.remove(pluginId);
        packageMutationService.publishRemoval(
                List.copyOf(packagesToRemove),
                pluginId,
                () -> {
                    permissionService.removePlugin(pluginId);
                    certificationReceiptStore.removePlugin(pluginId);
                    runtimeBindingStore.removeDependentsStrict(Set.of(pluginId));
                    stateStore.saveStrict(
                            nextEnabledStates,
                            nextPendingUninstall,
                            nextQuarantinedStates,
                            quarantineReport
                    );
                },
                () -> {
                    permissionService.reload();
                    loadStates();
                }
        );
        enabledStates.clear();
        enabledStates.addAll(nextEnabledStates);
        pendingUninstall.clear();
        pendingUninstall.addAll(nextPendingUninstall);
        quarantinedStates.clear();
        quarantinedStates.addAll(nextQuarantinedStates);
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
        loadStates();
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
        Set<String> nextQuarantinedStates = new HashSet<>(quarantinedStates);
        nextPendingUninstall.add(pluginId);
        nextEnabledStates.remove(pluginId);
        nextQuarantinedStates.remove(pluginId);
        packageMutationService.publishDocuments(
                () -> {
                    permissionService.removePlugin(pluginId);
                    runtimeBindingStore.removeDependentsStrict(Set.of(pluginId));
                    stateStore.saveStrict(
                            nextEnabledStates,
                            nextPendingUninstall,
                            nextQuarantinedStates,
                            quarantineReport
                    );
                },
                () -> {
                    permissionService.reload();
                    loadStates();
                }
        );

        enabledStates.clear();
        enabledStates.addAll(nextEnabledStates);
        pendingUninstall.clear();
        pendingUninstall.addAll(nextPendingUninstall);
        quarantinedStates.clear();
        quarantinedStates.addAll(nextQuarantinedStates);
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

    /// Returns the persisted secret-free report from the latest consumed startup recovery evidence.
    ///
    /// @return persisted quarantine report, or empty before any recovery evidence has been consumed
    public Optional<PluginQuarantineReport> getQuarantineReport() {
        return Optional.ofNullable(quarantineReport);
    }

    /// Returns the immutable persisted recovery quarantine.
    ///
    /// @return immutable quarantined plugin IDs
    public @Unmodifiable Set<String> getQuarantinedPluginIds() {
        stateLock.readLock().lock();
        try {
            return Set.copyOf(quarantinedStates);
        } finally {
            stateLock.readLock().unlock();
        }
    }

    /// Returns whether one installed plugin remains in the persisted recovery quarantine.
    ///
    /// @param pluginId plugin ID
    /// @return whether the plugin is quarantined
    public boolean isPluginQuarantined(String pluginId) {
        stateLock.readLock().lock();
        try {
            return quarantinedStates.contains(pluginId);
        } finally {
            stateLock.readLock().unlock();
        }
    }

    /// Restores one quarantined plugin together with its executable dependency and Runtime Provider closure.
    ///
    /// @param pluginId quarantined plugin ID
    /// @return immutable provider-first restored closure
    /// @throws IOException if the installed closure is missing, incompatible, cyclic, pending removal, or cannot persist
    public @Unmodifiable List<String> restoreQuarantinedPlugin(String pluginId) throws IOException {
        return restoreQuarantinedPlugins(Set.of(pluginId));
    }

    /// Restores a selected quarantined group together with its executable dependency and Runtime Provider closure.
    ///
    /// @param pluginIds selected quarantined plugin IDs
    /// @return immutable provider-first restored closure
    /// @throws IOException if the installed closure is missing, incompatible, cyclic, pending removal, or cannot persist
    public @Unmodifiable List<String> restoreQuarantinedPlugins(Set<String> pluginIds) throws IOException {
        administrativeGuard.checkTrustedCaller();
        Set<String> requestedIds = Set.copyOf(pluginIds);
        return mutationLock.call(() -> {
            loadStates();
            return restoreQuarantinedPluginsLocked(requestedIds);
        });
    }

    /// Restores every quarantined plugin through one executable dependency and Runtime Provider closure.
    ///
    /// @return immutable provider-first restored closure
    /// @throws IOException if the installed closure is missing, incompatible, cyclic, pending removal, or cannot persist
    public @Unmodifiable List<String> restoreAllQuarantinedPlugins() throws IOException {
        administrativeGuard.checkTrustedCaller();
        return mutationLock.call(() -> {
            loadStates();
            return restoreQuarantinedPluginsLocked(Set.copyOf(quarantinedStates));
        });
    }

    /// Computes and strictly persists one dependency-consistent quarantine restoration while holding the mutation lock.
    ///
    /// @param requestedIds exact quarantined roots selected by the caller
    /// @return immutable provider-first restored closure
    /// @throws IOException if the installed graph is not executable or strict state publication fails
    private @Unmodifiable List<String> restoreQuarantinedPluginsLocked(
            @Unmodifiable Set<String> requestedIds
    ) throws IOException {
        if (requestedIds.isEmpty()) {
            return List.of();
        }
        for (String pluginId : requestedIds) {
            if (!PluginManifest.isValidId(pluginId)) {
                throw new IllegalArgumentException("Invalid plugin ID: " + pluginId);
            }
            if (!quarantinedStates.contains(pluginId)) {
                throw new IOException("Plugin is not quarantined: " + pluginId);
            }
        }

        @Unmodifiable Map<String, PluginManifest> manifests = Map.copyOf(
                dependencyPlanner.readInstallPlanningManifests(plugins, pendingUninstall)
        );
        @Unmodifiable Map<String, RuntimeProviderBinding> runtimeBindings = runtimeBindingStore.readStrict();
        Set<String> closure = new HashSet<>();
        for (String pluginId : requestedIds.stream().sorted().toList()) {
            collectRestoreClosure(pluginId, manifests, runtimeBindings, new HashSet<>(), closure);
        }
        dependencyPlanner.validateReplacementGraph(manifests, Set.copyOf(closure), runtimeBindings);

        List<String> ordered = new ArrayList<>();
        Set<String> orderedIds = new HashSet<>();
        manifests.values().stream()
                .filter(manifest -> closure.contains(manifest.getId()))
                .filter(manifest -> manifest.getPluginKind() == PluginKind.RUNTIME_PROVIDER)
                .sorted(java.util.Comparator.comparing(PluginManifest::getId))
                .forEach(manifest -> appendRestoreOrder(
                        manifest.getId(), manifests, runtimeBindings, closure, orderedIds, ordered));
        manifests.values().stream()
                .filter(manifest -> closure.contains(manifest.getId()))
                .filter(manifest -> manifest.getPluginKind() != PluginKind.RUNTIME_PROVIDER)
                .sorted(java.util.Comparator.comparing(PluginManifest::getId))
                .forEach(manifest -> appendRestoreOrder(
                        manifest.getId(), manifests, runtimeBindings, closure, orderedIds, ordered));

        Set<String> nextEnabledStates = new HashSet<>(enabledStates);
        Set<String> nextQuarantinedStates = new HashSet<>(quarantinedStates);
        nextEnabledStates.addAll(closure);
        nextQuarantinedStates.removeAll(closure);
        stateStore.saveStrict(
                nextEnabledStates,
                pendingUninstall,
                nextQuarantinedStates,
                quarantineReport
        );
        enabledStates.clear();
        enabledStates.addAll(nextEnabledStates);
        quarantinedStates.clear();
        quarantinedStates.addAll(nextQuarantinedStates);
        return List.copyOf(ordered);
    }

    /// Collects one executable restore closure, following runtime bindings before concrete plugin dependencies.
    ///
    /// @param pluginId closure root
    /// @param manifests immutable installed manifests indexed by ID
    /// @param runtimeBindings immutable runtime bindings indexed by dependent ID
    /// @param visiting IDs on the current traversal stack
    /// @param closure mutable collected closure
    /// @throws IOException if an executable dependency is missing, legacy, incompatible, or cyclic
    private static void collectRestoreClosure(
            String pluginId,
            @Unmodifiable Map<String, PluginManifest> manifests,
            @Unmodifiable Map<String, RuntimeProviderBinding> runtimeBindings,
            Set<String> visiting,
            Set<String> closure
    ) throws IOException {
        if (closure.contains(pluginId)) {
            return;
        }
        if (!visiting.add(pluginId)) {
            throw new IOException("Cyclic plugin dependency detected at " + pluginId);
        }
        @Nullable PluginManifest manifest = manifests.get(pluginId);
        if (manifest == null) {
            throw new IOException("Missing installed plugin in quarantine restoration closure: " + pluginId);
        }
        if (manifest.getSchemaVersion() < PluginManifest.MIN_EXECUTABLE_SCHEMA_VERSION) {
            throw new IOException("Legacy plugin cannot be restored to execution: " + pluginId);
        }
        @Nullable RuntimeProviderBinding runtimeBinding = runtimeBindings.get(pluginId);
        if (runtimeBinding != null) {
            collectRestoreClosure(runtimeBinding.providerId(), manifests, runtimeBindings, visiting, closure);
        }
        for (PluginDependency dependency : manifest.getPluginDependencies()) {
            @Nullable PluginManifest dependencyManifest = manifests.get(dependency.getId());
            if (dependencyManifest == null || !dependency.matchesVersion(dependencyManifest.getVersion())) {
                throw new IOException("Plugin " + pluginId + " requires unavailable dependency "
                        + dependency.getId() + " " + dependency.getVersion());
            }
            collectRestoreClosure(dependency.getId(), manifests, runtimeBindings, visiting, closure);
        }
        visiting.remove(pluginId);
        closure.add(pluginId);
    }

    /// Appends one closure member after its runtime and concrete dependencies in deterministic order.
    ///
    /// @param pluginId closure member
    /// @param manifests immutable installed manifests indexed by ID
    /// @param runtimeBindings immutable runtime bindings indexed by dependent ID
    /// @param closure immutable selected closure
    /// @param visited IDs already appended
    /// @param ordered mutable provider-first topological result
    private static void appendRestoreOrder(
            String pluginId,
            @Unmodifiable Map<String, PluginManifest> manifests,
            @Unmodifiable Map<String, RuntimeProviderBinding> runtimeBindings,
            @Unmodifiable Set<String> closure,
            Set<String> visited,
            List<String> ordered
    ) {
        if (!visited.add(pluginId)) {
            return;
        }
        @Nullable RuntimeProviderBinding runtimeBinding = runtimeBindings.get(pluginId);
        if (runtimeBinding != null && closure.contains(runtimeBinding.providerId())) {
            appendRestoreOrder(
                    runtimeBinding.providerId(), manifests, runtimeBindings, closure, visited, ordered);
        }
        PluginManifest manifest = Objects.requireNonNull(manifests.get(pluginId));
        manifest.getPluginDependencies().stream()
                .map(PluginDependency::getId)
                .filter(closure::contains)
                .sorted()
                .forEach(dependencyId -> appendRestoreOrder(
                        dependencyId, manifests, runtimeBindings, closure, visited, ordered));
        ordered.add(pluginId);
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

    /// Immutable exact snapshot of one loaded plugin lifecycle.
    ///
    /// @param identity exact loaded artifact identity
    /// @param enabled whether the lifecycle was enabled when captured
    @NotNullByDefault
    private record LivePluginSnapshot(PluginArtifactIdentity identity, boolean enabled) {
    }

    /// Immutable old Host graph in leaf-first dependent unload order.
    ///
    /// @param provider exact old Host lifecycle snapshot
    /// @param dependents immutable loaded dependent snapshots in leaf-first order
    @NotNullByDefault
    private record LiveRuntimeProviderSwap(
            LivePluginSnapshot provider,
            @Unmodifiable List<LivePluginSnapshot> dependents
    ) {
    }

    /// Holds immutable old graphs and mutable progress for one prepared package transaction.
    @NotNullByDefault
    private static final class LiveRuntimeProviderSwapSession {
        /// Immutable active Host replacements captured before publication.
        private final @Unmodifiable List<LiveRuntimeProviderSwap> swaps;

        /// Immutable IDs of every actively reloaded Host and dependent used by post-commit status publication.
        private final @Unmodifiable Set<String> liveGraphIds;

        /// Immutable desired enablement restored by journal rollback.
        private final @Unmodifiable Set<String> originalEnabledStates;

        /// Immutable pending removals restored by journal rollback.
        private final @Unmodifiable Set<String> originalPendingUninstall;

        /// Immutable recovery quarantine restored by journal rollback.
        private final @Unmodifiable Set<String> originalQuarantinedStates;

        /// Host IDs whose old graph teardown started before commit.
        private final Set<String> startedProviderIds = new HashSet<>();

        /// Creates one transaction-local live swap session.
        ///
        /// @param swaps immutable active Host replacement graphs
        /// @param originalEnabledStates immutable desired enablement before publication
        /// @param originalPendingUninstall immutable pending removals before publication
        /// @param originalQuarantinedStates immutable recovery quarantine before publication
        private LiveRuntimeProviderSwapSession(
                @Unmodifiable List<LiveRuntimeProviderSwap> swaps,
                @Unmodifiable Set<String> originalEnabledStates,
                @Unmodifiable Set<String> originalPendingUninstall,
                @Unmodifiable Set<String> originalQuarantinedStates
        ) {
            this.swaps = List.copyOf(swaps);
            liveGraphIds = swaps.stream()
                    .flatMap(swap -> java.util.stream.Stream.concat(
                            java.util.stream.Stream.of(swap.provider()),
                            swap.dependents().stream()
                    ))
                    .map(snapshot -> snapshot.identity().getPluginId())
                    .collect(Collectors.toUnmodifiableSet());
            this.originalEnabledStates = Set.copyOf(originalEnabledStates);
            this.originalPendingUninstall = Set.copyOf(originalPendingUninstall);
            this.originalQuarantinedStates = Set.copyOf(originalQuarantinedStates);
        }

        /// Returns every immutable captured Host graph.
        ///
        /// @return immutable Host graph list
        private @Unmodifiable List<LiveRuntimeProviderSwap> swaps() {
            return swaps;
        }

        /// Returns every plugin ID whose exact target artifact was actively reloaded with a Host graph.
        ///
        /// @return immutable live graph ID set
        private @Unmodifiable Set<String> liveGraphIds() {
            return liveGraphIds;
        }

        /// Marks one Host graph as requiring rollback restoration.
        ///
        /// @param providerId active Host ID
        private void markStarted(String providerId) {
            startedProviderIds.add(providerId);
        }

        /// Returns captured graphs whose old lifecycle teardown began.
        ///
        /// @return immutable started graph list in original order
        private @Unmodifiable List<LiveRuntimeProviderSwap> startedSwaps() {
            return swaps.stream()
                    .filter(swap -> startedProviderIds.contains(swap.provider().identity().getPluginId()))
                    .toList();
        }

        /// Returns desired enablement from before publication.
        ///
        /// @return immutable enabled plugin IDs
        private @Unmodifiable Set<String> originalEnabledStates() {
            return originalEnabledStates;
        }

        /// Returns pending removals from before publication.
        ///
        /// @return immutable pending removal IDs
        private @Unmodifiable Set<String> originalPendingUninstall() {
            return originalPendingUninstall;
        }

        /// Returns recovery quarantine from before publication.
        ///
        /// @return immutable recovery quarantine IDs
        private @Unmodifiable Set<String> originalQuarantinedStates() {
            return originalQuarantinedStates;
        }
    }

}
