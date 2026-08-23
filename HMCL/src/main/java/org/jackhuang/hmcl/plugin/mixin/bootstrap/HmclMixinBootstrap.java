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
package org.jackhuang.hmcl.plugin.mixin.bootstrap;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import org.jackhuang.hmcl.plugin.PluginArtifactIdentity;
import org.jackhuang.hmcl.plugin.PluginDependency;
import org.jackhuang.hmcl.plugin.PluginManifest;
import org.jackhuang.hmcl.plugin.PluginMixinPermissionGuard;
import org.jackhuang.hmcl.plugin.PluginMutationLock;
import org.jackhuang.hmcl.plugin.PluginPermission;
import org.jackhuang.hmcl.plugin.internal.PluginPackageVersions;
import org.jackhuang.hmcl.plugin.internal.VerifiedPluginPackage;
import org.jackhuang.hmcl.plugin.runtime.PluginCompatibilityEvaluator;
import org.jackhuang.hmcl.plugin.runtime.PluginCompatibilityRequirements;
import org.jackhuang.hmcl.plugin.runtime.PluginCompatibilityResult;
import org.jackhuang.hmcl.plugin.trust.PluginRuntimeTrustGuard;
import org.jackhuang.hmcl.util.SelfDependencyPatcher;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/// Discovers enabled JVM plugins and relaunches HMCL with its premain Mixin instrumentation agent.
@NotNullByDefault
public final class HmclMixinBootstrap {
    /// System property that disables plugin Mixin discovery for recovery and diagnostics.
    public static final String DISABLE_PROPERTY = "hmcl.plugin.mixins.disabled";

    /// System property listing plugin IDs whose Mixin configurations are active in this process.
    public static final String ACTIVE_PROPERTY = "hmcl.plugin.mixins.active";

    /// System property set in the relaunched JVM whose premain agent owns Mixin transformation.
    public static final String AGENT_ACTIVE_PROPERTY = "hmcl.plugin.mixins.agent.active";

    /// Plugin manifest entry stored at the root of every `.npl` archive.
    private static final String PLUGIN_MANIFEST = "plugin.json";

    /// Persistent file containing enabled and pending-uninstall plugin IDs.
    private static final String PLUGIN_STATE_FILE = "plugin-states.json";

    /// Private artifact-bound user permission decisions consulted before premain.
    private static final String PLUGIN_PERMISSION_FILE = "plugin-permissions.json";

    /// Crash-recovery journal that must be resolved by the regular plugin manager before Mixin discovery.
    private static final String PLUGIN_TRANSACTION_FILE = "plugin-install-transaction.json";

    /// Launcher-version override also consumed by the regular HMCL metadata path.
    private static final String LAUNCHER_VERSION_OVERRIDE_PROPERTY = "hmcl.version.override";

    /// HMCL build metadata resource used when package implementation metadata is unavailable.
    private static final String LAUNCHER_METADATA_RESOURCE = "/assets/hmcl.properties";

    /// Property containing the launcher version in the HMCL build metadata resource.
    private static final String LAUNCHER_VERSION_PROPERTY = "hmcl.version";

    /// Development fallback matching the regular HMCL metadata path for exploded class directories.
    private static final String DEVELOPMENT_LAUNCHER_VERSION = "@develop@";

    /// Maximum accepted uncompressed size of one plugin manifest.
    private static final int MAX_MANIFEST_BYTES = 1024 * 1024;

    /// Maximum accepted size of the minimal startup plugin-state document.
    private static final int MAX_PLUGIN_STATE_BYTES = 1024 * 1024;

    /// Maximum complete plugin package size captured into one startup authorization snapshot.
    private static final int MAX_PACKAGE_BYTES = 512 * 1024 * 1024;

    /// Maximum entries accepted while hashing one JAR that will enter the Agent class path.
    private static final int MAX_AGENT_JAR_ENTRIES = 10_000;

    /// Maximum total uncompressed JAR bytes accepted while binding one Agent class-path handle.
    private static final long MAX_AGENT_JAR_BYTES = 512L * 1024L * 1024L;

    /// Maximum bytecode retained while fingerprinting one Agent-owned class definition.
    private static final int MAX_AGENT_CLASS_BYTES = 16 * 1024 * 1024;

    /// Maximum Mixin configuration bytes parsed during ownership validation.
    private static final int MAX_MIXIN_CONFIG_BYTES = 1024 * 1024;

    /// Prefix used by multi-release JAR class variants before the Java binary resource path.
    private static final String MULTI_RELEASE_PREFIX = "META-INF/versions/";

    /// Plugin IDs accepted for cache directory names and dependency references.
    private static final Pattern PLUGIN_ID_PATTERN = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9._-]{1,127}");

    /// JSON codec used before the full launcher logging and settings systems are available.
    private static final Gson GSON = new Gson();

    /// Prevents construction of the startup utility.
    private HmclMixinBootstrap() {
    }

    /// Relaunches HMCL when at least one enabled JVM plugin declares Mixin configurations.
    ///
    /// The relaunched JVM calls this method again after premain sets [AGENT_ACTIVE_PROPERTY], preventing recursion.
    ///
    /// @param args launcher arguments
    /// @return whether control was transferred to an agent-enabled JVM
    public static boolean relaunchIfNeeded(String @Unmodifiable [] args) {
        if (Boolean.getBoolean(DISABLE_PROPERTY)) {
            return false;
        }

        if (Boolean.getBoolean(AGENT_ACTIVE_PROPERTY)) {
            if (!PluginAgentSnapshot.current().getActiveArtifacts().isEmpty()) {
                return false;
            }

            // A property can be inherited or forged, while only premain can publish the in-memory exact snapshot.
            System.clearProperty(AGENT_ACTIVE_PROPERTY);
            report("Ignoring plugin Mixin Agent marker without an exact in-process snapshot");
        }

        try {
            AgentConfiguration configuration = prepareAgentConfiguration();
            if (configuration.mixinConfigs.isEmpty()) {
                return false;
            }
            startAgentProcess(args);
            report("Relaunched HMCL with the plugin Mixin agent for "
                    + configuration.mixinConfigs.size() + " configuration(s)");
            return true;
        } catch (IOException exception) {
            report("Unable to relaunch HMCL with plugin Mixins; continuing without them", exception);
            System.setProperty(DISABLE_PROPERTY, "true");
            return false;
        }
    }

    /// Prepares immutable plugin paths, unique configurations, and active plugin IDs for premain.
    ///
    /// @return agent configuration, possibly with no Mixin configurations
    /// @throws IOException if package discovery or resource validation fails
    static AgentConfiguration prepareAgentConfiguration() throws IOException {
        return prepareAgentConfiguration(resolveLocalHome());
    }

    /// Prepares an agent configuration from one explicit launcher-local home.
    ///
    /// @param localHome launcher-local directory containing plugin state, packages, and grants
    /// @return agent configuration, possibly with no Mixin configurations
    /// @throws IOException if package discovery or resource validation fails
    static AgentConfiguration prepareAgentConfiguration(Path localHome) throws IOException {
        Path normalizedLocalHome = localHome.toAbsolutePath().normalize();
        return new PluginMutationLock(normalizedLocalHome).call(
                () -> prepareAgentConfigurationLocked(normalizedLocalHome, null, null)
        );
    }

    /// Prepares an agent configuration with an explicit runtime trust source for deterministic revocation tests.
    ///
    /// @param localHome launcher-local directory
    /// @param runtimeTrustGuard explicit proof-backed runtime gate
    /// @return agent configuration, possibly with no Mixin configurations
    /// @throws IOException if package discovery or resource validation fails
    static AgentConfiguration prepareAgentConfiguration(
            Path localHome,
            PluginRuntimeTrustGuard runtimeTrustGuard
    ) throws IOException {
        Path normalizedLocalHome = localHome.toAbsolutePath().normalize();
        return new PluginMutationLock(normalizedLocalHome).call(
                () -> prepareAgentConfigurationLocked(normalizedLocalHome, runtimeTrustGuard, null)
        );
    }

    /// Prepares an agent configuration with explicit trust and compatibility policies for deterministic tests.
    ///
    /// @param localHome launcher-local directory
    /// @param runtimeTrustGuard explicit proof-backed runtime gate
    /// @param compatibilityEvaluator explicit launcher-host compatibility policy
    /// @return agent configuration, possibly with no Mixin configurations
    /// @throws IOException if package discovery or resource validation fails
    static AgentConfiguration prepareAgentConfiguration(
            Path localHome,
            PluginRuntimeTrustGuard runtimeTrustGuard,
            PluginCompatibilityEvaluator compatibilityEvaluator
    ) throws IOException {
        Path normalizedLocalHome = localHome.toAbsolutePath().normalize();
        return new PluginMutationLock(normalizedLocalHome).call(
                () -> prepareAgentConfigurationLocked(
                        normalizedLocalHome,
                        runtimeTrustGuard,
                        compatibilityEvaluator
                )
        );
    }

    /// Prepares one exact Agent configuration while the launcher-local mutation lock is held.
    ///
    /// @param localHome normalized launcher-local directory containing plugin state, packages, and grants
    /// @param explicitRuntimeTrustGuard explicit proof-backed runtime gate, or `null` for the inactive gate
    /// @param explicitCompatibilityEvaluator explicit launcher-host policy, or `null` for the current host policy
    /// @return agent configuration, possibly with no Mixin configurations
    /// @throws IOException if package discovery or resource validation fails
    private static AgentConfiguration prepareAgentConfigurationLocked(
            Path localHome,
            @Nullable PluginRuntimeTrustGuard explicitRuntimeTrustGuard,
            @Nullable PluginCompatibilityEvaluator explicitCompatibilityEvaluator
    ) throws IOException {
        Path transactionFile = localHome.resolve(PLUGIN_TRANSACTION_FILE);
        if (Files.exists(transactionFile, LinkOption.NOFOLLOW_LINKS)) {
            report("Blocking plugin Mixins until the pending plugin transaction is recovered: "
                    + transactionFile.getFileName());
            return new AgentConfiguration(List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        }
        PluginRuntimeTrustGuard runtimeTrustGuard;
        if (explicitRuntimeTrustGuard != null) {
            runtimeTrustGuard = explicitRuntimeTrustGuard;
        } else {
            runtimeTrustGuard = PluginRuntimeTrustGuard.inactive();
        }
        PluginCompatibilityEvaluator compatibilityEvaluator = explicitCompatibilityEvaluator == null
                ? PluginCompatibilityEvaluator.processWide()
                : explicitCompatibilityEvaluator;
        @Unmodifiable List<PluginLaunchDescriptor> descriptors =
                discoverEnabledJvmPlugins(localHome, runtimeTrustGuard, compatibilityEvaluator);
        @Unmodifiable Map<String, AgentClassDefinition> agentClasses = validateAgentClassOwnership(descriptors);
        @Unmodifiable List<String> configs = collectMixinConfigs(descriptors);
        for (PluginLaunchDescriptor descriptor : descriptors) {
            for (String config : descriptor.mixinConfigs) {
                validateMixinResourceOwnership(descriptors, descriptor, config);
                validateMixinConfigurationClasses(descriptors, descriptor, config, agentClasses);
            }
        }

        @Unmodifiable List<AgentClassPathEntry> classPathArtifacts = collectAgentClassPath(descriptors);
        @Unmodifiable List<Path> classPathEntries = classPathArtifacts.stream()
                .map(AgentClassPathEntry::path)
                .toList();
        @Unmodifiable List<String> activePluginIds = descriptors.stream()
                .filter(descriptor -> !descriptor.mixinConfigs.isEmpty())
                .map(descriptor -> descriptor.identity.getPluginId())
                .distinct()
                .toList();
        @Unmodifiable List<PluginAgentSnapshot.Registration> registrations = descriptors.stream()
                .filter(descriptor -> !descriptor.mixinConfigs.isEmpty())
                .map(descriptor -> PluginAgentSnapshot.registration(
                        descriptor.identity,
                        PluginAgentSnapshot.calculateMixinConfigurationDigest(descriptor.mixinConfigs),
                        descriptor.classPath.stream().map(AgentClassPathEntry::path).toList()
                ))
                .toList();
        @Unmodifiable List<ArtifactSource> artifactSources = descriptors.stream()
                .map(descriptor -> new ArtifactSource(descriptor.nplFile, descriptor.identity))
                .toList();
        return new AgentConfiguration(
                classPathEntries,
                classPathArtifacts,
                configs,
                activePluginIds,
                registrations,
                artifactSources
        );
    }

    /// Starts a new JVM with the current HMCL artifact installed as a premain Java agent.
    ///
    /// @param args launcher arguments
    /// @throws IOException if the current artifact is not a file or the process cannot start
    private static void startAgentProcess(String @Unmodifiable [] args) throws IOException {
        @Nullable URI codeSource = getCodeSource();
        if (codeSource == null || !"file".equalsIgnoreCase(codeSource.getScheme())) {
            throw new IOException("Plugin Mixins require a packaged HMCL JAR");
        }
        Path hmclArtifact = Path.of(codeSource).toAbsolutePath().normalize();
        if (!Files.isRegularFile(hmclArtifact)) {
            throw new IOException("Plugin Mixins require a packaged HMCL JAR: " + hmclArtifact);
        }

        String executableName = ProcessHandle.current().info().command()
                .map(Path::of)
                .map(path -> path.getFileName().toString())
                .filter(name -> name.equalsIgnoreCase("javaw.exe") || name.equalsIgnoreCase("javaw"))
                .orElse(System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("windows")
                        ? "java.exe"
                        : "java");
        Path javaExecutable = Path.of(System.getProperty("java.home"), "bin", executableName);

        List<String> command = new ArrayList<>();
        command.add(javaExecutable.toString());
        for (String inputArgument : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
            if (!isTransientRuntimeArgument(inputArgument)) {
                command.add(inputArgument);
            }
        }
        appendJavaFxRuntimeArguments(command, SelfDependencyPatcher.getPatchedModulePath());
        command.add("-javaagent:" + hmclArtifact);
        command.add("-jar");
        command.add(hmclArtifact.toString());
        command.addAll(List.of(args));

        new ProcessBuilder(command)
                .directory(Path.of(System.getProperty("user.dir")).toFile())
                .inheritIO()
                .start();
    }

    /// Adds dynamically downloaded JavaFX modules to an Agent child before premain loads UI-dependent Mixins.
    ///
    /// @param command mutable child JVM command
    /// @param modulePath cached OpenJFX module JARs loaded by the parent process
    static void appendJavaFxRuntimeArguments(List<String> command, @Unmodifiable List<Path> modulePath) {
        if (modulePath.isEmpty()) {
            return;
        }
        command.add("--module-path");
        command.add(modulePath.stream().map(Path::toString).collect(java.util.stream.Collectors.joining(File.pathSeparator)));
        command.add("--add-modules");
        command.add("javafx.base,javafx.graphics,javafx.controls");
    }

    /// Returns whether an input argument carries Mixin state that belongs only to the current JVM.
    ///
    /// These flags must not be copied into the Agent child because they can describe a stale
    /// process without proving that the child actually has instrumentation installed.
    ///
    /// @param inputArgument current JVM input argument
    /// @return whether the argument must be omitted from the Agent child
    private static boolean isTransientRuntimeArgument(String inputArgument) {
        if (!inputArgument.startsWith("-D")) {
            return false;
        }
        int equals = inputArgument.indexOf('=', 2);
        String propertyName = inputArgument.substring(2, equals >= 0 ? equals : inputArgument.length());
        return propertyName.equals(DISABLE_PROPERTY)
                || propertyName.equals(ACTIVE_PROPERTY)
                || propertyName.equals(AGENT_ACTIVE_PROPERTY);
    }

    /// Resolves the same local HMCL directory convention used by `Metadata` without loading launcher classes early.
    ///
    /// @return local HMCL home directory
    static Path resolveLocalHome() {
        @Nullable String configured = System.getProperty("hmcl.dir", System.getenv("HMCL_LOCAL_HOME"));
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured).toAbsolutePath().normalize();
        }
        return Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize().resolve(".hmcl");
    }

    /// Resolves the launcher version without initializing `Metadata`, `EntryPoint`, or other transformable HMCL code.
    ///
    /// Packaged builds use their own implementation metadata. Exploded development runs fall back to the same
    /// generated properties resource and development marker consumed by the regular metadata path.
    ///
    /// @return current launcher version used for plugin compatibility checks
    private static String resolveLauncherVersion() {
        @Nullable String override = System.getProperty(LAUNCHER_VERSION_OVERRIDE_PROPERTY);
        if (override != null) {
            return override;
        }

        @Nullable String implementationVersion = HmclMixinBootstrap.class.getPackage().getImplementationVersion();
        if (implementationVersion != null && !implementationVersion.isBlank()) {
            return implementationVersion;
        }

        try (@Nullable InputStream input =
                     HmclMixinBootstrap.class.getResourceAsStream(LAUNCHER_METADATA_RESOURCE)) {
            if (input != null) {
                Properties properties = new Properties();
                properties.load(new java.io.InputStreamReader(input, StandardCharsets.UTF_8));
                @Nullable String resourceVersion = properties.getProperty(LAUNCHER_VERSION_PROPERTY);
                if (resourceVersion != null && !resourceVersion.isBlank()) {
                    return resourceVersion;
                }
            }
        } catch (IOException exception) {
            report("Unable to read launcher version metadata before Mixin startup", exception);
        }
        return DEVELOPMENT_LAUNCHER_VERSION;
    }

    /// Discovers enabled Java and Kotlin packages, preparing a safe immutable launch cache for each one.
    ///
    /// @param localHome local HMCL directory
    /// @param runtimeTrustGuard proof-backed certified artifact revocation policy
    /// @param compatibilityEvaluator complete launcher-host compatibility policy
    /// @return enabled JVM plugin descriptors
    /// @throws IOException if state or package discovery fails
    private static @Unmodifiable List<PluginLaunchDescriptor> discoverEnabledJvmPlugins(
            Path localHome,
            PluginRuntimeTrustGuard runtimeTrustGuard,
            PluginCompatibilityEvaluator compatibilityEvaluator
    ) throws IOException {
        Path pluginsDirectory = localHome.resolve("plugins");
        if (!Files.isDirectory(pluginsDirectory)) {
            return List.of();
        }

        PluginStates states = readPluginStates(localHome.resolve(PLUGIN_STATE_FILE));
        Set<String> enabled = copyValidIds(states.enabled);
        Set<String> pendingUninstall = copyValidIds(states.pendingUninstall);
        enabled.removeAll(pendingUninstall);
        if (enabled.isEmpty()) {
            return List.of();
        }
        String launcherVersion = resolveLauncherVersion();

        Path cacheRoot = localHome.resolve("plugin-cache");
        Files.createDirectories(cacheRoot);
        PluginMixinPermissionGuard permissionGuard = new PluginMixinPermissionGuard(
                localHome.resolve(PLUGIN_PERMISSION_FILE)
        );
        Map<String, PluginDiscoveryCandidate> discoveredCandidates = new LinkedHashMap<>();

        try (Stream<Path> files = Files.list(pluginsDirectory)) {
            for (Path nplFile : files
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".npl"))
                    .sorted()
                    .toList()) {
                try {
                    PluginManifest manifest = readPluginManifest(nplFile);
                    @Nullable PluginDiscoveryCandidate previous = discoveredCandidates.putIfAbsent(
                            manifest.getId(),
                            new PluginDiscoveryCandidate(nplFile, manifest)
                    );
                    if (previous != null) {
                        report("Ignoring duplicate plugin ID " + manifest.getId() + " in "
                                + nplFile.getFileName());
                    }
                } catch (IOException | RuntimeException exception) {
                    report("Skipping plugin package " + nplFile.getFileName() + " during Mixin discovery", exception);
                }
            }
        }

        Map<String, BootstrapCandidate> candidates = new LinkedHashMap<>();
        for (String pluginId : enabled.stream().sorted().toList()) {
            @Nullable PluginDiscoveryCandidate discovered = discoveredCandidates.get(pluginId);
            if (discovered == null) {
                report("Enabled plugin " + pluginId + " is missing or invalid during Mixin discovery");
                continue;
            }
            try {
                BootstrapCandidate captured = capturePluginPackage(discovered.nplFile, cacheRoot);
                if (!discovered.manifest.equals(captured.manifest)
                        || !pluginId.equals(captured.manifest.getId())) {
                    captured.close();
                    report("Skipping plugin " + pluginId
                            + " because its manifest changed during Mixin discovery");
                    continue;
                }
                candidates.put(pluginId, captured);
            } catch (IOException | RuntimeException exception) {
                report("Skipping enabled plugin package " + discovered.nplFile.getFileName()
                        + " during Mixin snapshot capture", exception);
            }
        }

        try {
            Map<String, VisitState> visitStates = new HashMap<>();
            Set<String> failed = new HashSet<>();
            List<BootstrapCandidate> orderedCandidates = new ArrayList<>();
            for (String pluginId : enabled.stream().sorted().toList()) {
                @Nullable BootstrapCandidate candidate = candidates.get(pluginId);
                if (candidate == null) {
                    continue;
                }
                orderExecutableCandidate(
                        candidate,
                        candidates,
                        enabled,
                        launcherVersion,
                        compatibilityEvaluator,
                        permissionGuard,
                        runtimeTrustGuard,
                        visitStates,
                        failed,
                        orderedCandidates
                );
            }

            List<PluginLaunchDescriptor> descriptors = new ArrayList<>();
            for (BootstrapCandidate candidate : orderedCandidates) {
                PluginManifest manifest = candidate.manifest;
                if (!manifest.hasMixins()) {
                    continue;
                }
                VerifiedPluginPackage pluginPackage = prepareVerifiedPluginCache(candidate, cacheRoot);
                validateJvmEntrypointOwnership(manifest, pluginPackage);
                descriptors.add(new PluginLaunchDescriptor(
                        candidate.identity,
                        candidate.nplFile,
                        createAgentClassPath(pluginPackage),
                        manifest.getEntrypoint(),
                        manifest.getMixins()
                ));
            }
            return List.copyOf(descriptors);
        } finally {
            for (BootstrapCandidate candidate : candidates.values()) {
                try {
                    candidate.close();
                } catch (IOException exception) {
                    report("Unable to delete Mixin startup snapshot for "
                            + candidate.manifest.getId(), exception);
                }
            }
        }
    }

    /// Captures content digests for every verified JAR that may enter the system class-loader search path.
    ///
    /// @param pluginPackage exact verified Mixin package cache
    /// @return immutable class-path entries with content digests
    /// @throws IOException if an inventoried JAR changes or is malformed
    private static @Unmodifiable List<AgentClassPathEntry> createAgentClassPath(
            VerifiedPluginPackage pluginPackage
    ) throws IOException {
        List<AgentClassPathEntry> entries = new ArrayList<>();
        @Unmodifiable List<Path> orderedJarFiles = pluginPackage.getJarFiles().stream()
                .sorted((first, second) -> {
                    boolean firstIsRoot = first.getFileName().toString()
                            .equals(PluginPackageVersions.ROOT_RESOURCE_JAR);
                    boolean secondIsRoot = second.getFileName().toString()
                            .equals(PluginPackageVersions.ROOT_RESOURCE_JAR);
                    if (firstIsRoot != secondIsRoot) {
                        return firstIsRoot ? -1 : 1;
                    }
                    return first.toString().compareTo(second.toString());
                })
                .toList();
        for (Path jarFile : orderedJarFiles) {
            Path relative = pluginPackage.getDirectory().relativize(jarFile.toAbsolutePath().normalize());
            String resourceName = relative.toString().replace('\\', '/');
            byte @Nullable @Unmodifiable [] jarBytes = pluginPackage.readResourceBytes(resourceName);
            if (jarBytes == null) {
                throw new IOException("Verified Agent JAR disappeared from the package inventory: " + relative);
            }
            entries.add(new AgentClassPathEntry(jarFile, calculateAgentJarDigest(jarBytes)));
        }
        return List.copyOf(entries);
    }

    /// Confirms that a JVM Mixin lifecycle entry point is owned by the exact verified plugin artifact.
    ///
    /// This check consumes only package-inventoried bytes and never delegates to the host or system class path.
    /// It therefore completes before any plugin JAR is appended by premain.
    ///
    /// @param manifest exact plugin manifest
    /// @param pluginPackage exact verified package inventory
    /// @throws IOException if the declared JVM entry point is absent from the artifact
    private static void validateJvmEntrypointOwnership(
            PluginManifest manifest,
            VerifiedPluginPackage pluginPackage
    ) throws IOException {
        if (manifest.getType() != PluginManifest.PluginType.JAVA
                && manifest.getType() != PluginManifest.PluginType.KOTLIN) {
            return;
        }
        if (!pluginPackage.containsClass(manifest.getEntrypoint())) {
            throw new IOException("Mixin plugin entry point is not present in the exact verified package: "
                    + manifest.getId() + " -> " + manifest.getEntrypoint());
        }
    }

    /// Captures one installed package so its manifest and identity originate from the same complete bytes.
    ///
    /// @param nplFile installed package path
    /// @return immutable bootstrap package candidate
    /// @throws IOException if the package snapshot or manifest is invalid
    static BootstrapCandidate capturePluginPackage(Path nplFile) throws IOException {
        @Nullable Path parent = nplFile.toAbsolutePath().normalize().getParent();
        if (parent == null) {
            throw new IOException("Plugin package has no parent directory: " + nplFile);
        }
        return capturePluginPackage(nplFile, parent);
    }

    /// Captures one complete package in a private disk-backed startup snapshot.
    ///
    /// @param nplFile installed package path
    /// @param snapshotRoot trusted directory used for the temporary snapshot
    /// @return immutable bootstrap package candidate
    /// @throws IOException if copying, hashing, or manifest validation fails
    private static BootstrapCandidate capturePluginPackage(Path nplFile, Path snapshotRoot) throws IOException {
        Path packageSnapshot = createPluginPackageSnapshot(nplFile, snapshotRoot);
        boolean retained = false;
        try {
            PluginManifest manifest = readPluginManifest(packageSnapshot);
            BootstrapCandidate candidate = new BootstrapCandidate(
                    nplFile,
                    packageSnapshot,
                    manifest,
                    PluginArtifactIdentity.of(manifest, calculatePluginPackageSha256(packageSnapshot))
            );
            retained = true;
            return candidate;
        } finally {
            if (!retained) {
                Files.deleteIfExists(packageSnapshot);
            }
        }
    }

    /// Adds one executable candidate after its complete authorized dependency closure in deterministic order.
    ///
    /// @param candidate candidate to order
    /// @param candidates all valid installed candidates
    /// @param enabled enabled plugin IDs
    /// @param launcherVersion current launcher version
    /// @param compatibilityEvaluator complete launcher-host compatibility policy
    /// @param permissionGuard exact artifact-bound Mixin permission policy
    /// @param runtimeTrustGuard proof-backed certified artifact revocation policy
    /// @param visitStates dependency traversal states
    /// @param failed plugin IDs rejected during traversal
    /// @param orderedCandidates successfully validated topological order
    /// @return whether the candidate and its complete dependency closure may execute
    private static boolean orderExecutableCandidate(
            BootstrapCandidate candidate,
            Map<String, BootstrapCandidate> candidates,
            Set<String> enabled,
            String launcherVersion,
            PluginCompatibilityEvaluator compatibilityEvaluator,
            PluginMixinPermissionGuard permissionGuard,
            PluginRuntimeTrustGuard runtimeTrustGuard,
            Map<String, VisitState> visitStates,
            Set<String> failed,
            List<BootstrapCandidate> orderedCandidates
    ) {
        String pluginId = candidate.manifest.getId();
        if (failed.contains(pluginId)) {
            return false;
        }
        @Nullable VisitState state = visitStates.get(pluginId);
        if (state == VisitState.VISITING) {
            report("Cyclic enabled plugin dependency detected at " + pluginId + " during Mixin discovery");
            failed.add(pluginId);
            return false;
        }
        if (state == VisitState.VISITED) {
            return !failed.contains(pluginId);
        }
        if (!isExecutableCandidateAuthorized(
                candidate,
                launcherVersion,
                compatibilityEvaluator,
                permissionGuard,
                runtimeTrustGuard
        )) {
            failed.add(pluginId);
            visitStates.put(pluginId, VisitState.VISITED);
            return false;
        }

        visitStates.put(pluginId, VisitState.VISITING);
        for (PluginDependency dependency : candidate.manifest.getPluginDependencies()) {
            if (!enabled.contains(dependency.getId())) {
                report("Enabled plugin " + pluginId + " requires disabled dependency " + dependency.getId());
                failed.add(pluginId);
                visitStates.put(pluginId, VisitState.VISITED);
                return false;
            }
            @Nullable BootstrapCandidate dependencyCandidate = candidates.get(dependency.getId());
            if (dependencyCandidate == null) {
                report("Enabled plugin " + pluginId + " requires missing dependency " + dependency.getId());
                failed.add(pluginId);
                visitStates.put(pluginId, VisitState.VISITED);
                return false;
            }
            if (!dependency.matchesVersion(dependencyCandidate.manifest.getVersion())) {
                report("Enabled plugin " + pluginId + " requires dependency " + dependency.getId() + " "
                        + dependency.getVersion() + " but found " + dependencyCandidate.manifest.getVersion());
                failed.add(pluginId);
                visitStates.put(pluginId, VisitState.VISITED);
                return false;
            }
            if (!orderExecutableCandidate(
                    dependencyCandidate,
                    candidates,
                    enabled,
                    launcherVersion,
                    compatibilityEvaluator,
                    permissionGuard,
                    runtimeTrustGuard,
                    visitStates,
                    failed,
                    orderedCandidates
            )) {
                report("Enabled plugin " + pluginId + " is blocked because dependency "
                        + dependency.getId() + " cannot execute");
                failed.add(pluginId);
                visitStates.put(pluginId, VisitState.VISITED);
                return false;
            }
        }

        visitStates.put(pluginId, VisitState.VISITED);
        orderedCandidates.add(candidate);
        return true;
    }

    /// Returns whether one enabled artifact passes all pre-class-loading execution gates relevant to premain.
    ///
    /// Every executable dependency-closure member must satisfy its effective required permissions. Optional denials
    /// do not block premain participation unless the denied permission belongs to the manifest's required subset.
    ///
    /// @param candidate exact installed artifact candidate
    /// @param launcherVersion current launcher version
    /// @param compatibilityEvaluator complete launcher-host compatibility policy
    /// @param permissionGuard exact artifact-bound Mixin permission policy
    /// @param runtimeTrustGuard proof-backed certified artifact revocation policy
    /// @return whether the artifact may participate in an executable dependency closure
    private static boolean isExecutableCandidateAuthorized(
            BootstrapCandidate candidate,
            String launcherVersion,
            PluginCompatibilityEvaluator compatibilityEvaluator,
            PluginMixinPermissionGuard permissionGuard,
            PluginRuntimeTrustGuard runtimeTrustGuard
    ) {
        PluginManifest manifest = candidate.manifest;
        PluginCompatibilityResult compatibility = compatibilityEvaluator.evaluate(
                PluginCompatibilityRequirements.fromManifest(manifest),
                launcherVersion
        );
        if (!compatibility.isCompatible()) {
            report("Skipping plugin " + manifest.getId() + " " + manifest.getVersion()
                    + " because " + compatibility.detail());
            return false;
        }
        if (!PluginManifest.isCanonicalExecutableId(manifest.getId())) {
            report("Skipping plugin " + manifest.getId()
                    + " because executable plugin IDs must be portable canonical lower-case text");
            return false;
        }
        final @Nullable String trustBlock;
        try {
            trustBlock = runtimeTrustGuard.getBlockReason(
                    candidate.identity,
                    Files.size(candidate.packageSnapshot)
            );
        } catch (IOException exception) {
            report("Skipping plugin " + manifest.getId()
                    + " because its startup package size could not be verified", exception);
            return false;
        }
        if (trustBlock != null) {
            report("Skipping certified plugin " + manifest.getId() + " before Mixin startup: " + trustBlock);
            return false;
        }
        if (!permissionGuard.hasRequiredPermissions(manifest, candidate.identity.getSha256())) {
            report("Skipping plugin " + manifest.getId() + " " + manifest.getVersion()
                    + " because not every required permission is effective for package "
                    + candidate.identity.getSha256());
            return false;
        }
        if (!manifest.hasMixins()) {
            return true;
        }
        if (!manifest.isPermissionRequired(PluginPermission.MIXIN)) {
            report("Skipping Mixin plugin " + manifest.getId()
                    + " because its manifest does not require permission mixin");
            return false;
        }
        if (!permissionGuard.isGranted(manifest, candidate.identity.getSha256())) {
            report("Skipping Mixin plugin " + manifest.getId() + " " + manifest.getVersion()
                    + " because not every required permission is effective for package "
                    + candidate.identity.getSha256());
            return false;
        }
        return true;
    }

    /// Reads persisted plugin enablement state, treating absent or malformed state as empty.
    ///
    /// @param stateFile state file path
    /// @return parsed state object
    private static PluginStates readPluginStates(Path stateFile) {
        if (!Files.isRegularFile(stateFile)) {
            return new PluginStates();
        }

        try (InputStream input = Files.newInputStream(stateFile)) {
            byte @Unmodifiable [] stateBytes = readBounded(input, MAX_PLUGIN_STATE_BYTES);
            @Nullable PluginStates states = GSON.fromJson(
                    new java.io.StringReader(new String(stateBytes, StandardCharsets.UTF_8)),
                    PluginStates.class
            );
            return states == null ? new PluginStates() : states;
        } catch (IOException | RuntimeException exception) {
            report("Unable to read plugin state before Mixin startup", exception);
            return new PluginStates();
        }
    }

    /// Copies valid, non-null plugin IDs from a deserialized list.
    ///
    /// @param ids deserialized ID list or `null`
    /// @return mutable validated ID set owned by the caller
    private static Set<String> copyValidIds(@Nullable List<@Nullable String> ids) {
        Set<String> result = new HashSet<>();
        if (ids == null || ids.isEmpty()) {
            return result;
        }
        for (@Nullable String id : ids) {
            if (isValidPluginId(id)) {
                result.add(id);
            }
        }
        return result;
    }

    /// Copies one complete installed package into a bounded private startup snapshot.
    ///
    /// @param nplFile plugin package
    /// @param snapshotRoot trusted launcher-owned snapshot directory
    /// @return private snapshot path
    /// @throws IOException if the source or snapshot directory is unsafe, unreadable, or too large
    private static Path createPluginPackageSnapshot(Path nplFile, Path snapshotRoot) throws IOException {
        validatePluginPackagePath(nplFile);
        Files.createDirectories(snapshotRoot);
        Path normalizedSnapshotRoot = snapshotRoot.toAbsolutePath().normalize();
        if (!Files.isDirectory(normalizedSnapshotRoot, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(normalizedSnapshotRoot)
                || !normalizedSnapshotRoot.equals(normalizedSnapshotRoot.toRealPath())) {
            throw new IOException("Plugin snapshot root is symbolic or redirected: " + snapshotRoot);
        }

        Path snapshot = Files.createTempFile(normalizedSnapshotRoot, ".mixin-bootstrap-", ".tmp");
        boolean complete = false;
        try (InputStream input = Files.newInputStream(nplFile, LinkOption.NOFOLLOW_LINKS);
             OutputStream output = Files.newOutputStream(snapshot, StandardOpenOption.TRUNCATE_EXISTING)) {
            byte[] buffer = new byte[8192];
            long total = 0;
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) {
                    continue;
                }
                total = Math.addExact(total, read);
                if (total > MAX_PACKAGE_BYTES) {
                    throw new IOException("Plugin package is too large: " + nplFile);
                }
                output.write(buffer, 0, read);
            }
            complete = true;
            return snapshot;
        } finally {
            if (!complete) {
                Files.deleteIfExists(snapshot);
            }
        }
    }

    /// Validates one plugin package path before discovery, hashing, or snapshot capture.
    ///
    /// @param nplFile package path
    /// @throws IOException if the path is symbolic, non-regular, or too large
    private static void validatePluginPackagePath(Path nplFile) throws IOException {
        if (!Files.isRegularFile(nplFile, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(nplFile)) {
            throw new IOException("Plugin package is not a non-symbolic regular file: " + nplFile);
        }
        if (Files.size(nplFile) > MAX_PACKAGE_BYTES) {
            throw new IOException("Plugin package is too large: " + nplFile);
        }
    }

    /// Reads one unique bounded `plugin.json` directly from one package path.
    ///
    /// @param packageFile package or private snapshot path
    /// @return validated manifest
    /// @throws IOException if the archive or manifest is invalid
    private static PluginManifest readPluginManifest(Path packageFile) throws IOException {
        validatePluginPackagePath(packageFile);
        byte @Nullable @Unmodifiable [] manifestBytes = null;
        try (ZipFile zipFile = new ZipFile(packageFile.toFile())) {
            var entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!entry.isDirectory() && PLUGIN_MANIFEST.equals(entry.getName())) {
                    if (manifestBytes != null) {
                        throw new IOException("Duplicate " + PLUGIN_MANIFEST);
                    }
                    if (entry.getSize() > MAX_MANIFEST_BYTES) {
                        throw new IOException("Plugin manifest is too large");
                    }
                    try (InputStream input = zipFile.getInputStream(entry)) {
                        manifestBytes = readBounded(input, MAX_MANIFEST_BYTES);
                    }
                }
            }
        }
        if (manifestBytes == null) {
            throw new IOException("Missing " + PLUGIN_MANIFEST);
        }

        return parsePluginManifest(manifestBytes);
    }

    /// Parses one bounded manifest JSON payload into the validated launcher model.
    ///
    /// @param manifestBytes UTF-8 manifest bytes
    /// @return validated plugin manifest
    /// @throws IOException if parsing or manifest validation fails
    private static PluginManifest parsePluginManifest(byte @Unmodifiable [] manifestBytes) throws IOException {
        try {
            return PluginManifest.fromJson(new java.io.StringReader(
                    new String(manifestBytes, StandardCharsets.UTF_8)
            ));
        } catch (RuntimeException exception) {
            throw new IOException("Invalid plugin manifest", exception);
        }
    }

    /// Reads an input stream while enforcing a maximum number of bytes.
    ///
    /// @param input source stream
    /// @param maximumBytes maximum accepted bytes
    /// @return complete stream contents
    /// @throws IOException if reading fails or the limit is exceeded
    private static byte @Unmodifiable [] readBounded(InputStream input, int maximumBytes) throws IOException {
        byte[] buffer = new byte[8192];
        int total = 0;
        List<byte[]> chunks = new ArrayList<>();
        int read;
        while ((read = input.read(buffer)) >= 0) {
            if (read == 0) {
                continue;
            }
            total = Math.addExact(total, read);
            if (total > maximumBytes) {
                throw new IOException("Input exceeds " + maximumBytes + " bytes");
            }
            byte[] chunk = new byte[read];
            System.arraycopy(buffer, 0, chunk, 0, read);
            chunks.add(chunk);
        }

        byte[] result = new byte[total];
        int offset = 0;
        for (byte[] chunk : chunks) {
            System.arraycopy(chunk, 0, result, offset, chunk.length);
            offset += chunk.length;
        }
        return result;
    }

    /// Calculates a bounded lower-case SHA-256 digest over one package path.
    ///
    /// @param packageFile package or private snapshot path
    /// @return lower-case SHA-256 digest
    /// @throws IOException if the package is unsafe, unreadable, or too large
    private static String calculatePluginPackageSha256(Path packageFile) throws IOException {
        validatePluginPackagePath(packageFile);
        MessageDigest digest = createSha256();
        try (InputStream input = Files.newInputStream(packageFile, LinkOption.NOFOLLOW_LINKS)) {
            byte[] buffer = new byte[8192];
            long total = 0;
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) {
                    continue;
                }
                total = Math.addExact(total, read);
                if (total > MAX_PACKAGE_BYTES) {
                    throw new IOException("Plugin package is too large: " + packageFile);
                }
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /// Calculates a deterministic digest of every entry and uncompressed byte from captured JAR bytes.
    ///
    /// @param jarBytes complete verified JAR bytes
    /// @return lower-case deterministic content digest
    /// @throws IOException if the archive is malformed or exceeds Agent limits
    static String calculateAgentJarDigest(byte @Unmodifiable [] jarBytes) throws IOException {
        MessageDigest digest = createSha256();
        int entryCount = 0;
        long totalBytes = 0;
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(jarBytes))) {
            @Nullable ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                entryCount = Math.addExact(entryCount, 1);
                if (entryCount > MAX_AGENT_JAR_ENTRIES) {
                    throw new IOException("Agent JAR contains too many entries");
                }
                totalBytes = updateAgentJarEntryDigest(
                        digest,
                        entry.getName(),
                        entry.isDirectory(),
                        input,
                        totalBytes
                );
                input.closeEntry();
            }
        }
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(entryCount).array());
        return HexFormat.of().formatHex(digest.digest());
    }

    /// Calculates a deterministic digest by fully reading the same open JAR handle later appended by premain.
    ///
    /// @param jarFile open Agent class-path handle
    /// @return lower-case deterministic content digest
    /// @throws IOException if the archive cannot be read or exceeds Agent limits
    static String calculateAgentJarDigest(ZipFile jarFile) throws IOException {
        MessageDigest digest = createSha256();
        int entryCount = 0;
        long totalBytes = 0;
        var entries = jarFile.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            entryCount = Math.addExact(entryCount, 1);
            if (entryCount > MAX_AGENT_JAR_ENTRIES) {
                throw new IOException("Agent JAR contains too many entries: " + jarFile.getName());
            }
            try (InputStream input = jarFile.getInputStream(entry)) {
                totalBytes = updateAgentJarEntryDigest(
                        digest,
                        entry.getName(),
                        entry.isDirectory(),
                        input,
                        totalBytes
                );
            }
        }
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(entryCount).array());
        return HexFormat.of().formatHex(digest.digest());
    }

    /// Adds one archive entry name, type, content, and length to an Agent class-path digest.
    ///
    /// @param digest active content digest
    /// @param entryName archive entry name
    /// @param directory whether the entry is a directory
    /// @param input entry content stream
    /// @param previousTotal total uncompressed bytes from preceding entries
    /// @return updated total uncompressed byte count
    /// @throws IOException if reading fails or the archive exceeds the size limit
    private static long updateAgentJarEntryDigest(
            MessageDigest digest,
            String entryName,
            boolean directory,
            InputStream input,
            long previousTotal
    ) throws IOException {
        byte @Unmodifiable [] nameBytes = entryName.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(nameBytes.length).array());
        digest.update(nameBytes);
        digest.update((byte) (directory ? 1 : 0));

        byte[] buffer = new byte[8192];
        long entryBytes = 0;
        int read;
        while ((read = input.read(buffer)) >= 0) {
            if (read == 0) {
                continue;
            }
            entryBytes = Math.addExact(entryBytes, read);
            if (Math.addExact(previousTotal, entryBytes) > MAX_AGENT_JAR_BYTES) {
                throw new IOException("Agent JAR uncompressed content is too large");
            }
            digest.update(buffer, 0, read);
        }
        digest.update(ByteBuffer.allocate(Long.BYTES).putLong(entryBytes).array());
        return Math.addExact(previousTotal, entryBytes);
    }

    /// Creates a SHA-256 digest or reports an impossible runtime configuration failure.
    ///
    /// @return fresh SHA-256 digest
    private static MessageDigest createSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    /// Returns whether a plugin ID is safe and structurally valid.
    ///
    /// @param id plugin ID or `null`
    /// @return whether the ID is valid
    private static boolean isValidPluginId(@Nullable String id) {
        return id != null && PLUGIN_ID_PATTERN.matcher(id).matches();
    }

    /// Prepares an immutable extracted cache version for one enabled Mixin plugin.
    ///
    /// @param nplFile source plugin package
    /// @param cacheRoot Mixin cache root
    /// @param pluginId validated plugin identifier
    /// @return ready immutable cache directory
    /// @throws IOException if hashing, extraction, validation, or publication fails
    static Path preparePluginCache(Path nplFile, Path cacheRoot, String pluginId) throws IOException {
        return PluginPackageVersions.prepareMixinPackage(nplFile, cacheRoot, pluginId);
    }

    /// Prepares one startup cache only while the package still matches the artifact that was authorized.
    ///
    /// @param candidate validated and hashed package candidate
    /// @param cacheRoot Mixin cache root
    /// @return ready immutable verified cache inventory
    /// @throws IOException if the package changes before or during cache preparation
    static VerifiedPluginPackage prepareVerifiedPluginCache(
            BootstrapCandidate candidate,
            Path cacheRoot
    ) throws IOException {
        VerifiedPluginPackage pluginPackage = PluginPackageVersions.prepareVerifiedMixinPackage(
                candidate.packageSnapshot,
                cacheRoot,
                candidate.identity
        );
        String installedHash = calculatePluginPackageSha256(candidate.nplFile);
        if (!candidate.identity.getSha256().equals(installedHash)) {
            throw new IOException("Plugin package changed after Mixin authorization snapshot: "
                    + candidate.manifest.getId());
        }
        pluginPackage.verifyIntegrity();
        byte @Nullable @Unmodifiable [] cachedManifestBytes = pluginPackage.readResourceBytes(PLUGIN_MANIFEST);
        if (cachedManifestBytes == null) {
            throw new IOException("Verified Mixin cache is missing " + PLUGIN_MANIFEST + ": "
                    + candidate.manifest.getId());
        }
        PluginManifest cachedManifest = parsePluginManifest(cachedManifestBytes);
        PluginArtifactIdentity cachedIdentity = PluginArtifactIdentity.of(
                cachedManifest,
                candidate.identity.getSha256()
        );
        if (!candidate.manifest.equals(cachedManifest) || !candidate.identity.equals(cachedIdentity)) {
            throw new IOException("Verified Mixin cache manifest differs from the authorized package snapshot: "
                    + candidate.manifest.getId());
        }
        return pluginPackage;
    }

    /// Builds a deterministic class ownership index before any plugin JAR enters the system loader.
    ///
    /// Host-visible classes, duplicate classes inside one artifact, and conflicting classes across artifacts are
    /// rejected before premain can make them globally visible. Identical Kotlin standard-library definitions may be
    /// shared because Kotlin plugins commonly package the same fixed runtime; plugin entry points and all other
    /// classes remain exclusively owned by one artifact.
    ///
    /// @param descriptors authorized Mixin plugin descriptors
    /// @return immutable class definitions indexed by Java binary name
    /// @throws IOException if a class source is ambiguous, host-visible, malformed, or unreadable
    private static @Unmodifiable Map<String, AgentClassDefinition> validateAgentClassOwnership(
            @Unmodifiable List<PluginLaunchDescriptor> descriptors
    ) throws IOException {
        Map<String, AgentClassDefinition> owners = new LinkedHashMap<>();
        for (PluginLaunchDescriptor descriptor : descriptors) {
            for (AgentClassPathEntry classPathEntry : descriptor.classPath) {
                @Unmodifiable Map<String, String> classFingerprints =
                        readAgentClassFingerprints(classPathEntry);
                for (Map.Entry<String, String> classEntry : classFingerprints.entrySet()) {
                    String binaryName = classEntry.getKey();
                    validateAgentClassAbsentFromHost(descriptor, binaryName);
                    AgentClassDefinition definition = new AgentClassDefinition(
                            descriptor,
                            classPathEntry.path,
                            classEntry.getValue()
                    );
                    @Nullable AgentClassDefinition previous = owners.putIfAbsent(binaryName, definition);
                    if (previous == null) {
                        continue;
                    }
                    if (previous.owner == descriptor) {
                        throw new IOException("Agent class " + binaryName + " appears in multiple JARs from "
                                + descriptor.identity.getPluginId() + ": " + previous.source.getFileName()
                                + " and " + classPathEntry.path.getFileName());
                    }
                    if (binaryName.startsWith("kotlin.")
                            && previous.fingerprint.equals(definition.fingerprint)) {
                        continue;
                    }
                    throw new IOException("Agent class " + binaryName + " is provided by both "
                            + previous.owner.identity.getPluginId() + " and "
                            + descriptor.identity.getPluginId());
                }
            }
        }

        for (PluginLaunchDescriptor descriptor : descriptors) {
            @Nullable AgentClassDefinition entrypoint = owners.get(descriptor.entrypoint);
            if (entrypoint == null || entrypoint.owner != descriptor) {
                throw new IOException("Mixin lifecycle entry point is not exclusively owned by "
                        + descriptor.identity.getPluginId() + ": " + descriptor.entrypoint);
            }
        }
        return Map.copyOf(owners);
    }

    /// Reads every effective class name from one verified Agent JAR and fingerprints its multi-release variants.
    ///
    /// @param classPathEntry exact verified Agent class-path entry
    /// @return immutable binary-name to structured-content digest map
    /// @throws IOException if the JAR is malformed or a class exceeds the bounded inspection limit
    private static @Unmodifiable Map<String, String> readAgentClassFingerprints(
            AgentClassPathEntry classPathEntry
    ) throws IOException {
        Map<String, Map<String, String>> variants = new TreeMap<>();
        try (ZipFile jarFile = new ZipFile(classPathEntry.path.toFile())) {
            for (ZipEntry entry : jarFile.stream()
                    .filter(candidate -> !candidate.isDirectory())
                    .sorted((first, second) -> first.getName().compareTo(second.getName()))
                    .toList()) {
                @Nullable String binaryName = normalizeAgentClassName(entry.getName());
                if (binaryName == null) {
                    continue;
                }
                String digest;
                try (InputStream input = jarFile.getInputStream(entry)) {
                    digest = calculateBoundedEntryDigest(
                            input,
                            MAX_AGENT_CLASS_BYTES,
                            "Agent class exceeds 16 MiB: " + entry.getName()
                    );
                }
                variants.computeIfAbsent(binaryName, ignored -> new TreeMap<>())
                        .put(entry.getName(), digest);
            }
        }

        Map<String, String> fingerprints = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, String>> classEntry : variants.entrySet()) {
            MessageDigest digest = createSha256();
            for (Map.Entry<String, String> variant : classEntry.getValue().entrySet()) {
                updateLengthPrefixedDigest(digest, variant.getKey());
                updateLengthPrefixedDigest(digest, variant.getValue());
            }
            fingerprints.put(classEntry.getKey(), HexFormat.of().formatHex(digest.digest()));
        }
        return Map.copyOf(fingerprints);
    }

    /// Normalizes a base or multi-release JAR class resource into one Java binary name.
    ///
    /// @param entryName raw JAR entry name
    /// @return binary class name, or `null` for non-class and module-descriptor entries
    /// @throws IOException if a class entry uses an unsafe or malformed path
    private static @Nullable String normalizeAgentClassName(String entryName) throws IOException {
        if (!entryName.endsWith(".class")) {
            return null;
        }
        if (entryName.indexOf('\\') >= 0 || entryName.startsWith("/") || entryName.contains("//")) {
            throw new IOException("Unsafe Agent class entry path: " + entryName);
        }

        String classResource = entryName;
        if (classResource.startsWith(MULTI_RELEASE_PREFIX)) {
            int versionEnd = classResource.indexOf('/', MULTI_RELEASE_PREFIX.length());
            if (versionEnd < 0) {
                throw new IOException("Malformed multi-release Agent class entry: " + entryName);
            }
            String version = classResource.substring(MULTI_RELEASE_PREFIX.length(), versionEnd);
            try {
                if (Integer.parseInt(version) < 9) {
                    throw new IOException("Invalid multi-release Agent class version: " + entryName);
                }
            } catch (NumberFormatException exception) {
                throw new IOException("Invalid multi-release Agent class version: " + entryName, exception);
            }
            classResource = classResource.substring(versionEnd + 1);
        }
        if (classResource.equals("module-info.class") || classResource.startsWith("META-INF/")) {
            return null;
        }
        for (String component : classResource.split("/", -1)) {
            if (component.isEmpty() || component.equals(".") || component.equals("..")) {
                throw new IOException("Unsafe Agent class entry path: " + entryName);
            }
        }
        return classResource.substring(0, classResource.length() - ".class".length()).replace('/', '.');
    }

    /// Rejects a plugin class that the bootstrap, platform, system, or HMCL loader could already resolve.
    ///
    /// @param owner artifact declaring the class
    /// @param binaryName Java binary class name
    /// @throws IOException if a host class-path resource can shadow the plugin class
    private static void validateAgentClassAbsentFromHost(
            PluginLaunchDescriptor owner,
            String binaryName
    ) throws IOException {
        String resourceName = binaryName.replace('.', '/') + ".class";
        var systemResources = ClassLoader.getSystemResources(resourceName);
        if (systemResources.hasMoreElements()) {
            throw new IOException("Agent class " + binaryName + " declared by "
                    + owner.identity.getPluginId() + " is already visible from the system class path: "
                    + systemResources.nextElement());
        }

        @Nullable ClassLoader bootstrapLoader = HmclMixinBootstrap.class.getClassLoader();
        @Nullable ClassLoader systemLoader = ClassLoader.getSystemClassLoader();
        if (bootstrapLoader != null && bootstrapLoader != systemLoader) {
            var bootstrapResources = bootstrapLoader.getResources(resourceName);
            if (bootstrapResources.hasMoreElements()) {
                throw new IOException("Agent class " + binaryName + " declared by "
                        + owner.identity.getPluginId() + " is already visible from the HMCL class path: "
                        + bootstrapResources.nextElement());
            }
        }
    }

    /// Parses one exact Mixin configuration and confirms all named Mixin and plugin classes belong to its artifact.
    ///
    /// @param descriptors authorized Mixin plugin descriptors
    /// @param owner artifact declaring the configuration
    /// @param config configuration resource name
    /// @param classes complete Agent class ownership index
    /// @throws IOException if JSON is malformed or a referenced class belongs to another source
    private static void validateMixinConfigurationClasses(
            @Unmodifiable List<PluginLaunchDescriptor> descriptors,
            PluginLaunchDescriptor owner,
            String config,
            @Unmodifiable Map<String, AgentClassDefinition> classes
    ) throws IOException {
        byte @Unmodifiable [] configBytes = readOwnedAgentResource(owner, config, MAX_MIXIN_CONFIG_BYTES);
        JsonObject root;
        try {
            JsonElement parsed = JsonParser.parseString(new String(configBytes, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) {
                throw new IOException("Mixin configuration must contain a JSON object: " + config);
            }
            root = parsed.getAsJsonObject();
        } catch (JsonParseException exception) {
            throw new IOException("Invalid Mixin configuration JSON: " + config, exception);
        }

        @Nullable String mixinPackage = optionalJsonString(root, "package", config);
        validateMixinClassArray(owner, root, "mixins", mixinPackage, config, classes);
        validateMixinClassArray(owner, root, "client", mixinPackage, config, classes);
        validateMixinClassArray(owner, root, "server", mixinPackage, config, classes);
        @Nullable String pluginClass = optionalJsonString(root, "plugin", config);
        if (pluginClass != null && !pluginClass.isBlank()) {
            validateOwnedAgentClass(owner, pluginClass, config, classes);
        }
        @Nullable String refmap = optionalJsonString(root, "refmap", config);
        if (refmap != null && !refmap.isBlank()) {
            validateSafeAgentResourceName(refmap, "Mixin refmap");
            validateExclusiveAgentResourceOwnership(
                    descriptors,
                    owner,
                    refmap,
                    "Mixin refmap resource"
            );
        }
    }

    /// Validates one optional Mixin class-name array from a parsed configuration.
    ///
    /// @param owner artifact declaring the configuration
    /// @param root parsed configuration object
    /// @param property array property name
    /// @param mixinPackage optional configuration package prefix
    /// @param config configuration resource name
    /// @param classes complete Agent class ownership index
    /// @throws IOException if the array is malformed or names an unowned class
    private static void validateMixinClassArray(
            PluginLaunchDescriptor owner,
            JsonObject root,
            String property,
            @Nullable String mixinPackage,
            String config,
            @Unmodifiable Map<String, AgentClassDefinition> classes
    ) throws IOException {
        @Nullable JsonElement value = root.get(property);
        if (value == null || value.isJsonNull()) {
            return;
        }
        if (!value.isJsonArray()) {
            throw new IOException("Mixin configuration property " + property + " must be an array: " + config);
        }
        for (JsonElement classElement : value.getAsJsonArray()) {
            if (!classElement.isJsonPrimitive() || !classElement.getAsJsonPrimitive().isString()) {
                throw new IOException("Mixin configuration property " + property
                        + " contains a non-string class name: " + config);
            }
            String relativeName = classElement.getAsString();
            if (relativeName.isBlank()) {
                throw new IOException("Mixin configuration contains an empty class name: " + config);
            }
            String binaryName = mixinPackage == null || mixinPackage.isBlank()
                    ? relativeName
                    : mixinPackage + "." + relativeName;
            validateOwnedAgentClass(owner, binaryName, config, classes);
        }
    }

    /// Confirms that one configuration-referenced class is exclusively owned by the declaring artifact.
    ///
    /// @param owner artifact declaring the configuration
    /// @param binaryName referenced Java binary class name
    /// @param config configuration resource name
    /// @param classes complete Agent class ownership index
    /// @throws IOException if the class is absent or belongs to another artifact
    private static void validateOwnedAgentClass(
            PluginLaunchDescriptor owner,
            String binaryName,
            String config,
            @Unmodifiable Map<String, AgentClassDefinition> classes
    ) throws IOException {
        @Nullable AgentClassDefinition definition = classes.get(binaryName);
        if (definition == null || definition.owner != owner) {
            throw new IOException("Mixin configuration " + config + " from "
                    + owner.identity.getPluginId() + " references class outside its exact artifact: "
                    + binaryName);
        }
    }

    /// Reads one resource exactly once from an artifact's verified Agent class path.
    ///
    /// @param owner owning artifact descriptor
    /// @param resource exact JAR resource name
    /// @param maximumBytes maximum accepted resource size
    /// @return privately owned resource bytes
    /// @throws IOException if the resource is missing, duplicated, or oversized
    private static byte @Unmodifiable [] readOwnedAgentResource(
            PluginLaunchDescriptor owner,
            String resource,
            int maximumBytes
    ) throws IOException {
        byte @Nullable @Unmodifiable [] result = null;
        for (AgentClassPathEntry classPathEntry : owner.classPath) {
            try (ZipFile jarFile = new ZipFile(classPathEntry.path.toFile())) {
                for (ZipEntry entry : jarFile.stream()
                        .filter(candidate -> !candidate.isDirectory())
                        .filter(candidate -> resource.equals(candidate.getName()))
                        .toList()) {
                    if (result != null) {
                        throw new IOException("Agent resource appears multiple times in "
                                + owner.identity.getPluginId() + ": " + resource);
                    }
                    try (InputStream input = jarFile.getInputStream(entry)) {
                        result = readBoundedEntry(
                                input,
                                maximumBytes,
                                "Agent resource exceeds " + maximumBytes + " bytes: " + resource
                        );
                    }
                }
            }
        }
        if (result == null) {
            throw new IOException("Missing Agent resource " + resource + " in "
                    + owner.identity.getPluginId());
        }
        return result;
    }

    /// Reads one bounded archive entry into a private byte array.
    ///
    /// @param input open entry stream
    /// @param maximumBytes maximum accepted byte count
    /// @param failureMessage oversize failure detail
    /// @return captured entry bytes
    /// @throws IOException if reading fails or exceeds the bound
    private static byte @Unmodifiable [] readBoundedEntry(
            InputStream input,
            int maximumBytes,
            String failureMessage
    ) throws IOException {
        byte[] bytes = input.readNBytes(maximumBytes + 1);
        if (bytes.length > maximumBytes) {
            throw new IOException(failureMessage);
        }
        return bytes;
    }

    /// Calculates a SHA-256 digest while enforcing one archive-entry byte limit.
    ///
    /// @param input open entry stream
    /// @param maximumBytes maximum accepted byte count
    /// @param failureMessage oversize failure detail
    /// @return lower-case SHA-256 digest
    /// @throws IOException if reading fails or exceeds the bound
    private static String calculateBoundedEntryDigest(
            InputStream input,
            int maximumBytes,
            String failureMessage
    ) throws IOException {
        MessageDigest digest = createSha256();
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) >= 0) {
            if (read == 0) {
                continue;
            }
            if (total > maximumBytes - read) {
                throw new IOException(failureMessage);
            }
            total += read;
            digest.update(buffer, 0, read);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /// Adds one UTF-8 string with its length to a structured digest.
    ///
    /// @param digest active digest
    /// @param value value to append
    private static void updateLengthPrefixedDigest(MessageDigest digest, String value) {
        byte @Unmodifiable [] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    /// Reads one optional string property from a Mixin configuration object.
    ///
    /// @param root parsed configuration object
    /// @param property property name
    /// @param config configuration resource name
    /// @return property text or `null` when absent
    /// @throws IOException if the property exists but is not a string
    private static @Nullable String optionalJsonString(
            JsonObject root,
            String property,
            String config
    ) throws IOException {
        @Nullable JsonElement value = root.get(property);
        if (value == null || value.isJsonNull()) {
            return null;
        }
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IOException("Mixin configuration property " + property + " must be a string: " + config);
        }
        return value.getAsString();
    }

    /// Collects globally unique Mixin configuration names and rejects ambiguous resources across plugins.
    ///
    /// @param descriptors enabled JVM plugin descriptors
    /// @return configuration names in deterministic plugin order
    /// @throws IOException if two plugins declare the same resource name
    private static @Unmodifiable List<String> collectMixinConfigs(
            @Unmodifiable List<PluginLaunchDescriptor> descriptors
    ) throws IOException {
        Map<String, String> owners = new java.util.LinkedHashMap<>();
        for (PluginLaunchDescriptor descriptor : descriptors) {
            for (String config : descriptor.mixinConfigs) {
                @Nullable String previousOwner = owners.putIfAbsent(
                        config,
                        descriptor.identity.getPluginId()
                );
                if (previousOwner != null) {
                    throw new IOException("Mixin configuration resource " + config
                            + " is declared by both " + previousOwner + " and "
                            + descriptor.identity.getPluginId());
                }
            }
        }
        return List.copyOf(owners.keySet());
    }

    /// Collects unique Agent JAR paths while rejecting conflicting digests for one normalized path.
    ///
    /// @param descriptors authorized Mixin plugin descriptors
    /// @return immutable unique Agent class path in plugin order
    /// @throws IOException if one path is associated with different verified content digests
    private static @Unmodifiable List<AgentClassPathEntry> collectAgentClassPath(
            @Unmodifiable List<PluginLaunchDescriptor> descriptors
    ) throws IOException {
        Map<Path, AgentClassPathEntry> entries = new LinkedHashMap<>();
        for (PluginLaunchDescriptor descriptor : descriptors) {
            for (AgentClassPathEntry entry : descriptor.classPath) {
                @Nullable AgentClassPathEntry previous = entries.putIfAbsent(entry.path, entry);
                if (previous != null && !previous.contentDigest.equals(entry.contentDigest)) {
                    throw new IOException("Conflicting verified Agent JAR digests for " + entry.path);
                }
            }
        }
        return List.copyOf(entries.values());
    }

    /// Returns the code source containing the bootstrap class when available.
    ///
    /// @return bootstrap code source URI or `null`
    private static @Nullable URI getCodeSource() {
        try {
            if (HmclMixinBootstrap.class.getProtectionDomain() == null
                    || HmclMixinBootstrap.class.getProtectionDomain().getCodeSource() == null
                    || HmclMixinBootstrap.class.getProtectionDomain().getCodeSource().getLocation() == null) {
                return null;
            }
            return HmclMixinBootstrap.class.getProtectionDomain().getCodeSource().getLocation().toURI();
        } catch (Exception exception) {
            report("Unable to resolve the HMCL code source", exception);
            return null;
        }
    }

    /// Verifies that a declared Mixin configuration exists exactly once and only in its owning plugin.
    ///
    /// Preventing foreign packages from contributing the same resource name ensures system-loader lookup cannot
    /// replace an authorized plugin's configuration with bytes from another artifact.
    ///
    /// @param descriptors authorized Mixin plugin descriptors
    /// @param owner plugin that declared the configuration
    /// @param config declared resource name
    /// @throws IOException if the resource is missing, duplicated, or present in another plugin
    private static void validateMixinResourceOwnership(
            @Unmodifiable List<PluginLaunchDescriptor> descriptors,
            PluginLaunchDescriptor owner,
            String config
    ) throws IOException {
        validateExclusiveAgentResourceOwnership(
                descriptors,
                owner,
                config,
                "Mixin configuration resource"
        );
    }

    /// Confirms that one Agent resource exists exactly once in its artifact and nowhere else on the search path.
    ///
    /// @param descriptors authorized Mixin plugin descriptors
    /// @param owner artifact declaring the resource
    /// @param resource exact resource name
    /// @param description diagnostic resource description
    /// @throws IOException if the resource is missing, duplicated, foreign, or host-visible
    private static void validateExclusiveAgentResourceOwnership(
            @Unmodifiable List<PluginLaunchDescriptor> descriptors,
            PluginLaunchDescriptor owner,
            String resource,
            String description
    ) throws IOException {
        validateAgentResourceAbsentFromHostClassPath(owner, resource, description);
        int ownerMatches = 0;
        for (PluginLaunchDescriptor descriptor : descriptors) {
            for (AgentClassPathEntry classPathEntry : descriptor.classPath) {
                Path entry = classPathEntry.path;
                if (Files.isRegularFile(entry)) {
                    try (ZipFile zipFile = new ZipFile(entry.toFile())) {
                        long matches = zipFile.stream()
                                .filter(zipEntry -> !zipEntry.isDirectory())
                                .filter(zipEntry -> resource.equals(zipEntry.getName()))
                                .count();
                        if (matches == 0) {
                            continue;
                        }
                        if (descriptor != owner) {
                            throw new IOException(description + " " + resource
                                    + " declared by " + owner.identity.getPluginId() + " is also present in "
                                    + descriptor.identity.getPluginId());
                        }
                        ownerMatches = Math.addExact(ownerMatches, Math.toIntExact(matches));
                    }
                }
            }
        }
        if (ownerMatches != 1) {
            throw new IOException(ownerMatches == 0
                    ? "Missing " + description + " " + resource + " in "
                            + owner.identity.getPluginId()
                    : description + " " + resource + " appears multiple times in "
                            + owner.identity.getPluginId());
        }
    }

    /// Rejects an Agent resource already visible from HMCL's host or system class path.
    ///
    /// @param owner plugin that declared the resource
    /// @param resource declared resource name
    /// @param description diagnostic resource description
    /// @throws IOException if host code could shadow the plugin-owned resource
    private static void validateAgentResourceAbsentFromHostClassPath(
            PluginLaunchDescriptor owner,
            String resource,
            String description
    ) throws IOException {
        var systemResources = ClassLoader.getSystemResources(resource);
        if (systemResources.hasMoreElements()) {
            throw new IOException(description + " " + resource + " declared by "
                    + owner.identity.getPluginId() + " is already visible from the system class path: "
                    + systemResources.nextElement());
        }

        @Nullable ClassLoader bootstrapLoader = HmclMixinBootstrap.class.getClassLoader();
        @Nullable ClassLoader systemLoader = ClassLoader.getSystemClassLoader();
        if (bootstrapLoader != null && bootstrapLoader != systemLoader) {
            var bootstrapResources = bootstrapLoader.getResources(resource);
            if (bootstrapResources.hasMoreElements()) {
                throw new IOException(description + " " + resource + " declared by "
                        + owner.identity.getPluginId() + " is already visible from the HMCL class path: "
                        + bootstrapResources.nextElement());
            }
        }
    }

    /// Validates a configuration-provided Agent resource as a normalized forward-slash path.
    ///
    /// @param resource resource name
    /// @param description diagnostic resource description
    /// @throws IOException if the path is blank, absolute, malformed, or contains traversal components
    private static void validateSafeAgentResourceName(String resource, String description) throws IOException {
        if (resource.isBlank()
                || resource.startsWith("/")
                || resource.endsWith("/")
                || resource.indexOf('\\') >= 0
                || resource.indexOf(':') >= 0
                || resource.contains("//")) {
            throw new IOException(description + " uses an unsafe resource path: " + resource);
        }
        for (String component : resource.split("/", -1)) {
            if (component.isEmpty() || component.equals(".") || component.equals("..")) {
                throw new IOException(description + " uses an unsafe resource path: " + resource);
            }
        }
    }

    /// Prints a bootstrap diagnostic before the regular logger is initialized.
    ///
    /// @param message diagnostic message
    private static void report(String message) {
        System.err.println("[HMCL Plugin Mixin] " + message);
    }

    /// Prints a bootstrap diagnostic and its underlying failure.
    ///
    /// @param message diagnostic message
    /// @param throwable underlying failure
    private static void report(String message, Throwable throwable) {
        report(message + ": " + throwable.getMessage());
        throwable.printStackTrace(System.err);
    }

    /// Manifest-only installed package discovered without retaining complete archive bytes.
    @NotNullByDefault
    private static final class PluginDiscoveryCandidate {
        /// Installed plugin package path.
        private final Path nplFile;

        /// Manifest read during bounded directory discovery.
        private final PluginManifest manifest;

        /// Creates one lightweight discovery candidate.
        ///
        /// @param nplFile installed package path
        /// @param manifest bounded validated manifest
        private PluginDiscoveryCandidate(Path nplFile, PluginManifest manifest) {
            this.nplFile = nplFile.toAbsolutePath().normalize();
            this.manifest = manifest;
        }
    }

    /// Validated disk-backed package snapshot used during launch-time dependency ordering.
    @NotNullByDefault
    static final class BootstrapCandidate implements AutoCloseable {
        /// Installed plugin package path.
        private final Path nplFile;

        /// Private immutable NPL snapshot used for hashing, manifest parsing, and cache extraction.
        private final Path packageSnapshot;

        /// Fully validated package manifest.
        private final PluginManifest manifest;

        /// Exact artifact identity binding startup authorization to complete installed package bytes.
        private final PluginArtifactIdentity identity;

        /// Creates an immutable bootstrap candidate.
        ///
        /// @param nplFile installed package path
        /// @param packageSnapshot private immutable NPL snapshot
        /// @param manifest validated package manifest
        /// @param identity exact installed artifact identity
        private BootstrapCandidate(
                Path nplFile,
                Path packageSnapshot,
                PluginManifest manifest,
                PluginArtifactIdentity identity
        ) {
            this.nplFile = nplFile.toAbsolutePath().normalize();
            this.packageSnapshot = packageSnapshot.toAbsolutePath().normalize();
            this.manifest = manifest;
            this.identity = identity;
        }

        /// Deletes the private package snapshot after configuration preparation finishes.
        ///
        /// @throws IOException if the snapshot cannot be removed
        @Override
        public void close() throws IOException {
            Files.deleteIfExists(packageSnapshot);
        }
    }

    /// Dependency traversal state used to build a deterministic topological order.
    @NotNullByDefault
    private enum VisitState {
        /// Candidate is currently on the dependency traversal stack.
        VISITING,

        /// Candidate traversal has completed successfully or unsuccessfully.
        VISITED
    }

    /// One verified JAR path and deterministic digest transferred into premain.
    @NotNullByDefault
    static final class AgentClassPathEntry {
        /// Normalized direct JAR path opened by the Agent.
        private final Path path;

        /// Digest of every archive entry name, order, type, and uncompressed byte.
        private final String contentDigest;

        /// Creates one immutable Agent class-path binding.
        ///
        /// @param path verified JAR path
        /// @param contentDigest deterministic JAR content digest
        AgentClassPathEntry(Path path, String contentDigest) {
            this.path = path.toAbsolutePath().normalize();
            this.contentDigest = contentDigest;
        }

        /// Returns the normalized JAR path.
        ///
        /// @return Agent JAR path
        Path path() {
            return path;
        }

        /// Returns the expected deterministic JAR content digest.
        ///
        /// @return expected JAR digest
        String contentDigest() {
            return contentDigest;
        }
    }

    /// One exclusively indexed class definition contributed to the premain system-loader class path.
    @NotNullByDefault
    private static final class AgentClassDefinition {
        /// Artifact whose Agent class path contains the definition.
        private final PluginLaunchDescriptor owner;

        /// Exact JAR containing the indexed class variants.
        private final Path source;

        /// Structured digest of every base and multi-release class variant in the source JAR.
        private final String fingerprint;

        /// Creates one immutable Agent class ownership record.
        ///
        /// @param owner owning artifact descriptor
        /// @param source exact source JAR
        /// @param fingerprint structured class-content digest
        private AgentClassDefinition(
                PluginLaunchDescriptor owner,
                Path source,
                String fingerprint
        ) {
            this.owner = owner;
            this.source = source.toAbsolutePath().normalize();
            this.fingerprint = fingerprint;
        }
    }

    /// Installed source path whose exact NPL identity must still match before the transformer is installed.
    @NotNullByDefault
    private static final class ArtifactSource {
        /// Installed NPL path.
        private final Path path;

        /// Exact authorized package identity.
        private final PluginArtifactIdentity identity;

        /// Creates one immutable installed artifact verification target.
        ///
        /// @param path installed NPL path
        /// @param identity authorized identity
        private ArtifactSource(Path path, PluginArtifactIdentity identity) {
            this.path = path.toAbsolutePath().normalize();
            this.identity = identity;
        }

        /// Verifies that the installed package still has the authorized complete bytes.
        ///
        /// @throws IOException if the path changed, disappeared, or no longer matches the identity
        private void verify() throws IOException {
            String actualDigest = calculatePluginPackageSha256(path);
            if (!identity.getSha256().equals(actualDigest)) {
                throw new IOException("Installed Mixin package changed before Agent publication: "
                        + identity.getPluginId());
            }
        }
    }

    /// Minimal persisted state projection used before the regular plugin manager is loaded.
    @NotNullByDefault
    private static final class PluginStates {
        /// Enabled plugin IDs, or `null` when absent.
        private @Nullable List<@Nullable String> enabled;

        /// Plugin IDs awaiting uninstall, or `null` when absent.
        private @Nullable List<@Nullable String> pendingUninstall;

        /// Creates an empty state projection for missing files and Gson deserialization.
        private PluginStates() {
        }
    }

    /// Immutable launch-time metadata for one enabled JVM plugin.
    @NotNullByDefault
    private static final class PluginLaunchDescriptor {
        /// Exact plugin artifact activated by the descriptor.
        private final PluginArtifactIdentity identity;

        /// Installed package path revalidated before the transformer is installed.
        private final Path nplFile;

        /// Extracted root and nested JAR paths appended to the system class loader by premain.
        private final @Unmodifiable List<AgentClassPathEntry> classPath;

        /// JVM lifecycle entry point that must remain exclusively owned by this artifact.
        private final String entrypoint;

        /// Validated Mixin configuration resource names.
        private final @Unmodifiable List<String> mixinConfigs;

        /// Creates an immutable plugin launch descriptor.
        ///
        /// @param identity exact plugin artifact identity
        /// @param nplFile installed package path
        /// @param classPath plugin class path entries
        /// @param entrypoint JVM lifecycle entry point
        /// @param mixinConfigs Mixin configuration resources
        private PluginLaunchDescriptor(
                PluginArtifactIdentity identity,
                Path nplFile,
                @Unmodifiable List<AgentClassPathEntry> classPath,
                String entrypoint,
                @Unmodifiable List<String> mixinConfigs
        ) {
            this.identity = identity;
            this.nplFile = nplFile.toAbsolutePath().normalize();
            this.classPath = List.copyOf(classPath);
            this.entrypoint = entrypoint;
            this.mixinConfigs = List.copyOf(mixinConfigs);
        }
    }

    /// Immutable configuration transferred from package discovery to the premain agent.
    @NotNullByDefault
    static final class AgentConfiguration {
        /// Extracted roots and nested JARs appended to the system class loader.
        private final @Unmodifiable List<Path> classPathEntries;

        /// Exact expected content digests for the JAR handles appended by premain.
        private final @Unmodifiable List<AgentClassPathEntry> classPathArtifacts;

        /// Globally unique Mixin configuration resources.
        private final @Unmodifiable List<String> mixinConfigs;

        /// Plugin IDs whose Mixin configurations are active in this process.
        private final @Unmodifiable List<String> activePluginIds;

        /// Exact artifact registrations published only after premain succeeds.
        private final @Unmodifiable List<PluginAgentSnapshot.Registration> registrations;

        /// Installed NPL sources revalidated immediately before transformer installation.
        private final @Unmodifiable List<ArtifactSource> artifactSources;

        /// Creates an immutable agent configuration.
        ///
        /// @param classPathEntries plugin class path entries
        /// @param classPathArtifacts exact JAR digest bindings
        /// @param mixinConfigs Mixin configuration resources
        /// @param activePluginIds active Mixin plugin IDs
        /// @param registrations exact active artifact registrations
        /// @param artifactSources installed NPL verification targets
        private AgentConfiguration(
                @Unmodifiable List<Path> classPathEntries,
                @Unmodifiable List<AgentClassPathEntry> classPathArtifacts,
                @Unmodifiable List<String> mixinConfigs,
                @Unmodifiable List<String> activePluginIds,
                @Unmodifiable List<PluginAgentSnapshot.Registration> registrations,
                @Unmodifiable List<ArtifactSource> artifactSources
        ) {
            this.classPathEntries = List.copyOf(classPathEntries);
            this.classPathArtifacts = List.copyOf(classPathArtifacts);
            this.mixinConfigs = List.copyOf(mixinConfigs);
            this.activePluginIds = List.copyOf(activePluginIds);
            this.registrations = List.copyOf(registrations);
            this.artifactSources = List.copyOf(artifactSources);
        }

        /// Returns plugin class path entries.
        ///
        /// @return class path entries
        @Unmodifiable List<Path> classPathEntries() {
            return classPathEntries;
        }

        /// Returns exact JAR digest bindings for premain append verification.
        ///
        /// @return immutable class-path artifact bindings
        @Unmodifiable List<AgentClassPathEntry> classPathArtifacts() {
            return classPathArtifacts;
        }

        /// Returns Mixin configuration resources.
        ///
        /// @return Mixin configurations
        @Unmodifiable List<String> mixinConfigs() {
            return mixinConfigs;
        }

        /// Returns active Mixin plugin IDs.
        ///
        /// @return active plugin IDs
        @Unmodifiable List<String> activePluginIds() {
            return activePluginIds;
        }

        /// Returns exact artifact registrations to publish after transformer installation succeeds.
        ///
        /// @return immutable Agent registrations
        @Unmodifiable List<PluginAgentSnapshot.Registration> registrations() {
            return registrations;
        }

        /// Revalidates every installed NPL against the identity derived from its captured startup bytes.
        ///
        /// @throws IOException if any installed package changed before transformer installation
        void verifyInstalledArtifacts() throws IOException {
            for (ArtifactSource artifactSource : artifactSources) {
                artifactSource.verify();
            }
        }
    }
}
