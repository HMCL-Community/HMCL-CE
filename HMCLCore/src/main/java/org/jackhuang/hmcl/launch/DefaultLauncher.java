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
package org.jackhuang.hmcl.launch;

import org.glavo.uuid.UUIDs;
import org.jackhuang.hmcl.auth.AuthInfo;
import org.jackhuang.hmcl.download.LibraryAnalyzer;
import org.jackhuang.hmcl.game.*;
import org.jackhuang.hmcl.util.Lang;
import org.jackhuang.hmcl.util.ServerAddress;
import org.jackhuang.hmcl.util.StringUtils;
import org.jackhuang.hmcl.util.io.FileUtils;
import org.jackhuang.hmcl.util.io.Unzipper;
import org.jackhuang.hmcl.util.platform.*;
import org.jackhuang.hmcl.util.platform.macos.HomebrewUtils;
import org.jackhuang.hmcl.util.versioning.GameVersionNumber;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import static org.jackhuang.hmcl.util.Lang.mapOf;
import static org.jackhuang.hmcl.util.Pair.pair;
import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/**
 * @author huangyuhui
 */
public class DefaultLauncher extends Launcher {

    /// Canonical launch-scoped slot that protects the authentication access token.
    private static final String ACCESS_TOKEN_SECRET = "access-token";

    /// Library feature analysis used while preparing the launch command and environment.
    private final LibraryAnalyzer analyzer;

    public DefaultLauncher(GameRepository repository, GameInstanceManifest manifest, AuthInfo authInfo, LaunchOptions options) {
        this(repository, manifest, authInfo, options, null);
    }

    public DefaultLauncher(GameRepository repository, GameInstanceManifest manifest, AuthInfo authInfo, LaunchOptions options, ProcessListener listener) {
        this(repository, manifest, authInfo, options, listener, true);
    }

    public DefaultLauncher(GameRepository repository, GameInstanceManifest manifest, AuthInfo authInfo, LaunchOptions options, ProcessListener listener, boolean daemon) {
        super(repository, manifest, authInfo, options, listener, daemon);

        this.analyzer = LibraryAnalyzer.analyze(manifest, repository.getGameVersion(manifest).orElse(null));
    }

    /// Generates an immutable launch preparation without performing launch side effects.
    ///
    /// @param mode direct execution or script rendering
    /// @param nativeFolder selected native-library directory
    /// @return complete immutable preparation
    /// @throws IOException if required launch inputs cannot be read
    private LaunchPreparation generateLaunchPreparation(LaunchExecutionMode mode, Path nativeFolder)
            throws IOException {
        CommandBuilder res = new CommandBuilder();

        switch (options.getProcessPriority()) {
            case HIGH:
                if (OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS) {
                    // res.add("cmd", "/C", "start", "unused title", "/B", "/high");
                } else if (OperatingSystem.CURRENT_OS.isLinuxOrBSD() || OperatingSystem.CURRENT_OS == OperatingSystem.MACOS) {
                    res.addAll("nice", "-n", "-5");
                }
                break;
            case ABOVE_NORMAL:
                if (OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS) {
                    // res.add("cmd", "/C", "start", "unused title", "/B", "/abovenormal");
                } else if (OperatingSystem.CURRENT_OS.isLinuxOrBSD() || OperatingSystem.CURRENT_OS == OperatingSystem.MACOS) {
                    res.addAll("nice", "-n", "-1");
                }
                break;
            case NORMAL:
                // do nothing
                break;
            case BELOW_NORMAL:
                if (OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS) {
                    // res.add("cmd", "/C", "start", "unused title", "/B", "/belownormal");
                } else if (OperatingSystem.CURRENT_OS.isLinuxOrBSD() || OperatingSystem.CURRENT_OS == OperatingSystem.MACOS) {
                    res.addAll("nice", "-n", "1");
                }
                break;
            case LOW:
                if (OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS) {
                    // res.add("cmd", "/C", "start", "unused title", "/B", "/low");
                } else if (OperatingSystem.CURRENT_OS.isLinuxOrBSD() || OperatingSystem.CURRENT_OS == OperatingSystem.MACOS) {
                    res.addAll("nice", "-n", "5");
                }
                break;
        }

        // Executable
        if (StringUtils.isNotBlank(options.getWrapper()))
            res.addAllWithoutParsing(StringUtils.tokenize(options.getWrapper(), getEnvVars(nativeFolder)));

        int prefixTokenCount = res.asList().size();
        res.add(options.getJava().getBinary().toString());

        res.addAllWithoutParsingAndReadExternal(options.getOverrideJavaArguments());

        if (options.getMaxMemory() != null && options.getMaxMemory() > 0)
            res.addDefault("-Xmx", options.getMaxMemory() + "m");

        if (options.getMinMemory() != null && options.getMinMemory() > 0
                && (options.getMaxMemory() == null || options.getMinMemory() <= options.getMaxMemory()))
            res.addDefault("-Xms", options.getMinMemory() + "m");

        if (options.getMetaspace() != null && options.getMetaspace() > 0)
            if (options.getJava().getParsedVersion() < 8)
                res.addDefault("-XX:PermSize=", options.getMetaspace() + "m");
            else
                res.addDefault("-XX:MetaspaceSize=", options.getMetaspace() + "m");

        res.addAllDefaultWithoutParsing(options.getJavaArguments());

        Charset encoding = OperatingSystem.NATIVE_CHARSET;
        String fileEncoding = res.addDefault("-Dfile.encoding=", encoding.name());
        if (fileEncoding != null && !"-Dfile.encoding=COMPAT".equals(fileEncoding)) {
            try {
                encoding = Charset.forName(fileEncoding.substring("-Dfile.encoding=".length()));
            } catch (Throwable ex) {
                LOG.warning("Bad file encoding", ex);
            }
        }

        if (options.getJava().getParsedVersion() < 19) {
            res.addDefault("-Dsun.stdout.encoding=", encoding.name());
            res.addDefault("-Dsun.stderr.encoding=", encoding.name());
        } else {
            res.addDefault("-Dstdout.encoding=", encoding.name());
            res.addDefault("-Dstderr.encoding=", encoding.name());
        }

        // Fix RCE vulnerability of log4j2
        res.addDefault("-Djava.rmi.server.useCodebaseOnly=", "true");
        res.addDefault("-Dcom.sun.jndi.rmi.object.trustURLCodebase=", "false");
        res.addDefault("-Dcom.sun.jndi.cosnaming.object.trustURLCodebase=", "false");

        String formatMsgNoLookups = res.addDefault("-Dlog4j2.formatMsgNoLookups=", "true");
        if (isUsingLog4j() && (options.isEnableDebugLogOutput() || !"-Dlog4j2.formatMsgNoLookups=false".equals(formatMsgNoLookups))) {
            res.addDefault("-Dlog4j.configurationFile=", FileUtils.getAbsolutePath(getLog4jConfigurationFile()));
        }

        // Default JVM Args
        if (!options.isNoGeneratedJVMArgs()) {
            appendJvmArgs(res);

            res.addDefault("-Dminecraft.client.jar=", FileUtils.getAbsolutePath(repository.getInstanceJar(manifest)));

            if (OperatingSystem.CURRENT_OS == OperatingSystem.MACOS) {
                res.addDefault("-Xdock:name=", "Minecraft " + manifest.id());
                repository.getAssetObject(manifest.id(), manifest.getAssetIndex().getId(), "icons/minecraft.icns")
                        .ifPresent(minecraftIcns -> {
                            res.addDefault("-Xdock:icon=", FileUtils.getAbsolutePath(minecraftIcns));
                        });
            }

            if (OperatingSystem.CURRENT_OS != OperatingSystem.WINDOWS)
                res.addDefault("-Duser.home=", options.getGameDir().toAbsolutePath().getParent().toString());

            boolean addProxyOptions = res.noneMatch(arg ->
                    arg.startsWith("-Djava.net.useSystemProxies=")
                            || arg.startsWith("-Dhttp.proxy")
                            || arg.startsWith("-Dhttps.proxy")
                            || arg.startsWith("-DsocksProxy")
                            || arg.startsWith("-Djava.net.socks.")
            );

            if (addProxyOptions) {
                if (options.getProxyOption() == null || options.getProxyOption() == ProxyOption.Default.INSTANCE) {
                    res.add("-Djava.net.useSystemProxies=true");
                } else if (options.getProxyOption() instanceof ProxyOption.Http httpProxy) {
                    res.add("-Dhttp.proxyHost=" + httpProxy.host());
                    res.add("-Dhttp.proxyPort=" + httpProxy.port());
                    res.add("-Dhttps.proxyHost=" + httpProxy.host());
                    res.add("-Dhttps.proxyPort=" + httpProxy.port());

                    if (StringUtils.isNotBlank(httpProxy.username())) {
                        res.add("-Dhttp.proxyUser=" + httpProxy.username());
                        res.add("-Dhttp.proxyPassword=" + Objects.requireNonNullElse(httpProxy.password(), ""));
                        res.add("-Dhttps.proxyUser=" + httpProxy.username());
                        res.add("-Dhttps.proxyPassword=" + Objects.requireNonNullElse(httpProxy.password(), ""));
                    }
                } else if (options.getProxyOption() instanceof ProxyOption.Socks socksProxy) {
                    res.add("-DsocksProxyHost=" + socksProxy.host());
                    res.add("-DsocksProxyPort=" + socksProxy.port());

                    if (StringUtils.isNotBlank(socksProxy.username())) {
                        res.add("-Djava.net.socks.username=" + socksProxy.username());
                        res.add("-Djava.net.socks.password=" + Objects.requireNonNullElse(socksProxy.password(), ""));
                    }
                }
            }

            final int javaVersion = options.getJava().getParsedVersion();
            final boolean is64bit = options.getJava().getBits() == Bits.BIT_64;

            if (!options.isNoGeneratedOptimizingJVMArgs()) {
                res.addUnstableDefault("UnlockExperimentalVMOptions", true);
                res.addUnstableDefault("UnlockDiagnosticVMOptions", true);

                // Using G1GC with its settings by default
                if (javaVersion >= 8
                        && res.noneMatch(arg -> "-XX:-UseG1GC".equals(arg) || (arg.startsWith("-XX:+Use") && arg.endsWith("GC")))) {
                    res.addUnstableDefault("UseG1GC", true);
                    res.addUnstableDefault("G1MixedGCCountTarget", "5");
                    res.addUnstableDefault("G1NewSizePercent", "20");
                    res.addUnstableDefault("G1ReservePercent", "20");
                    res.addUnstableDefault("MaxGCPauseMillis", "50");
                    res.addUnstableDefault("G1HeapRegionSize", "32m");
                }

                res.addUnstableDefault("OmitStackTraceInFastThrow", false);

                // JIT Options
                if (javaVersion <= 8) {
                    res.addUnstableDefault("MaxInlineLevel", "15");
                }
                if (is64bit && SystemInfo.getTotalMemorySize() > 4L * 1024 * 1024 * 1024) {
                    res.addUnstableDefault("DontCompileHugeMethods", false);
                    res.addUnstableDefault("MaxNodeLimit", "240000");
                    res.addUnstableDefault("NodeLimitFudgeFactor", "8000");
                    res.addUnstableDefault("TieredCompileTaskTimeout", "10000");
                    res.addUnstableDefault("ReservedCodeCacheSize", "400M");
                    if (javaVersion >= 9) {
                        res.addUnstableDefault("NonNMethodCodeHeapSize", "12M");
                        res.addUnstableDefault("ProfiledCodeHeapSize", "194M");
                    }

                    if (javaVersion >= 8) {
                        res.addUnstableDefault("NmethodSweepActivity", "1");
                    }
                }

                if (is64bit && (javaVersion >= 25 && javaVersion <= 26)) {
                    res.addUnstableDefault("UseCompactObjectHeaders", true);
                }

                // As 32-bit JVM allocate 320KB for stack by default rather than 64-bit version allocating 1MB,
                // causing Minecraft 1.13 crashed accounting for java.lang.StackOverflowError.
                if (!is64bit) {
                    res.addDefault("-Xss", "1m");
                }
            }

            if (javaVersion == 16)
                res.addDefault("--illegal-access=", "permit");

            if (javaVersion == 24 || javaVersion == 25)
                res.addDefault("--sun-misc-unsafe-memory-access=", "allow");

            res.addDefault("-Dfml.ignoreInvalidMinecraftCertificates=", "true");
            res.addDefault("-Dfml.ignorePatchDiscrepancies=", "true");
        }

        if (OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS
                && options.getRenderer() instanceof Renderer.Driver renderer
                && renderer.mesaDriverName() != null) {
            res.addDefault("-Dorg.glavo.mesa.loader.nativeDir=", FileUtils.getAbsolutePath(nativeFolder.resolve("mesa-loader")));
        }

        if (OperatingSystem.CURRENT_OS == OperatingSystem.MACOS
                && options.getJava().getArchitecture() == Architecture.SYSTEM_ARCH
                && options.getRenderer() instanceof Renderer.Vulkan vulkanDriver
                && vulkanDriver.icdFile() != null) {
            if (Files.isRegularFile(HomebrewUtils.LIB_VULKAN)) {
                res.addDefault("-Dorg.lwjgl.vulkan.libname=", FileUtils.getAbsolutePath(HomebrewUtils.LIB_VULKAN));
            }
        }

        Set<String> classpath = repository.getClasspath(manifest);

        if (analyzer.has(LibraryAnalyzer.LibraryType.CLEANROOM)) {
            classpath.removeIf(c -> c.contains("2.9.4-nightly-20150209"));
        }

        Path jar = repository.getInstanceJar(manifest);
        if (!Files.isRegularFile(jar))
            throw new IOException("Minecraft jar does not exist");
        classpath.add(FileUtils.getAbsolutePath(jar.toAbsolutePath()));

        // Provided Minecraft arguments
        Path gameAssets = repository.getActualAssetDirectory(manifest.id(), manifest.getAssetIndex().getId());
        Map<String, String> configuration = getConfigurations();
        configuration.put("${classpath}", String.join(File.pathSeparator, classpath));
        configuration.put("${game_assets}", FileUtils.getAbsolutePath(gameAssets));
        configuration.put("${assets_root}", FileUtils.getAbsolutePath(gameAssets));

        Optional<String> gameVersion = repository.getGameVersion(manifest);

        // lwjgl assumes path to native libraries encoded by ASCII.
        // Here is a workaround for this issue: https://github.com/HMCL-dev/HMCL/issues/1141.
        String nativeFolderPath = FileUtils.getAbsolutePath(nativeFolder);
        Path tempNativeFolder = null;
        if ((OperatingSystem.CURRENT_OS == OperatingSystem.LINUX || OperatingSystem.CURRENT_OS == OperatingSystem.MACOS)
                && !StringUtils.isASCII(nativeFolderPath)
                && gameVersion.isPresent() && GameVersionNumber.compare(gameVersion.get(), "1.19") < 0) {
            tempNativeFolder = Paths.get("/", "tmp", "hmcl-natives-" + UUID.randomUUID());
            nativeFolderPath = tempNativeFolder + File.pathSeparator + nativeFolderPath;
        }
        configuration.put("${natives_directory}", nativeFolderPath);

        Path javaNativeFolder = FileUtils.toAbsolute(nativeFolder);
        @Nullable List<Argument> jvmArguments = Optional.ofNullable(manifest.arguments()).map(Arguments::jvm).orElse(null);

        if (jvmArguments != null) {
            for (Argument jvmArgument : jvmArguments) {
                if (jvmArgument instanceof StringArgument stringArgument
                        && stringArgument.argument().startsWith("-Djava.library.path=")) {

                    // We conservatively handle parameters like "-Djava.library.path=${natives_directory}/java"
                    // to avoid extracting native libraries to unexpected locations.

                    String prefix = "-Djava.library.path=${natives_directory}/";
                    if (stringArgument.argument().startsWith(prefix)) {
                        try {
                            String subDir = stringArgument.argument().substring(prefix.length());
                            Path actualNativeFolder = FileUtils.toAbsolute(javaNativeFolder.resolve(subDir));

                            if (actualNativeFolder.startsWith(javaNativeFolder)) {
                                javaNativeFolder = actualNativeFolder;
                            }
                        } catch (IllegalArgumentException ignored) {
                        }
                    }

                    break;
                }
            }
        }

        res.addAll(Arguments.parseArguments(Objects.requireNonNullElseGet(jvmArguments, this::getDefaultJVMArguments), configuration));
        Arguments argumentsFromAuthInfo = authInfo.getLaunchArguments(options);
        if (argumentsFromAuthInfo != null && argumentsFromAuthInfo.jvm() != null && !argumentsFromAuthInfo.jvm().isEmpty())
            res.addAll(Arguments.parseArguments(argumentsFromAuthInfo.jvm(), configuration));

        for (String javaAgent : options.getJavaAgents()) {
            res.add("-javaagent:" + javaAgent);
        }

        if (manifest.mainClass() == null) {
            throw new IllegalStateException("Main class is null for instance " + manifest.id());
        }

        int mainClassIndex = res.asList().size();
        res.add(manifest.mainClass());

        res.addAll(Arguments.parseStringArguments(Optional.ofNullable(manifest.minecraftArguments()).map(StringUtils::tokenize).orElseGet(ArrayList::new), configuration));

        Map<String, Boolean> features = getFeatures();
        Optional.ofNullable(manifest.arguments()).map(Arguments::game).ifPresent(arguments -> res.addAll(Arguments.parseArguments(arguments, configuration, features)));
        if (Optional.ofNullable(manifest.minecraftArguments()).isPresent()) {
            res.addAll(Arguments.parseArguments(this.getDefaultGameArguments(), configuration, features));
        }
        if (argumentsFromAuthInfo != null && argumentsFromAuthInfo.game() != null && !argumentsFromAuthInfo.game().isEmpty())
            res.addAll(Arguments.parseArguments(argumentsFromAuthInfo.game(), configuration, features));

        if (options.getQuickPlayOption() instanceof QuickPlayOption.MultiPlayer multiPlayer) {
            String address = multiPlayer.serverIP();

            try {
                ServerAddress parsed = ServerAddress.parse(address);
                if (World.supportQuickPlay(GameVersionNumber.asGameVersion(gameVersion))) {
                    res.add("--quickPlayMultiplayer");
                    res.add(parsed.port() >= 0 ? address : parsed.host() + ":25565");
                } else {
                    res.add("--server");
                    res.add(parsed.host());
                    res.add("--port");
                    res.add(parsed.port() >= 0 ? String.valueOf(parsed.port()) : "25565");
                }
            } catch (IllegalArgumentException e) {
                LOG.warning("Invalid server address: " + address, e);
            }
        } else if (options.getQuickPlayOption() instanceof QuickPlayOption.SinglePlayer singlePlayer
                && World.supportQuickPlay(GameVersionNumber.asGameVersion(gameVersion))) {
            res.add("--quickPlaySingleplayer");
            res.add(singlePlayer.worldFolderName());
        } else if (options.getQuickPlayOption() instanceof QuickPlayOption.Realm realm
                && World.supportQuickPlay(GameVersionNumber.asGameVersion(gameVersion))) {
            res.add("--quickPlayRealms");
            res.add(realm.realmID());
        }

        if (options.isFullscreen())
            res.add("--fullscreen");

        // https://github.com/HMCL-dev/HMCL/issues/774
        if (options.getProxyOption() instanceof ProxyOption.Socks socksProxy) {
            res.add("--proxyHost");
            res.add(socksProxy.host());
            res.add("--proxyPort");
            res.add(String.valueOf(socksProxy.port()));
            if (StringUtils.isNotBlank(socksProxy.username())) {
                res.add("--proxyUser");
                res.add(socksProxy.username());
                res.add("--proxyPass");
                res.add(Objects.requireNonNullElse(socksProxy.password(), ""));
            }
        }

        if (options.getGraphicsBackend() != GraphicsAPI.DEFAULT
                && gameVersion.isPresent() && GameVersionNumber.compare(gameVersion.get(), "26.2-snapshot-2") >= 0) {
            res.add("--graphicsBackend");
            res.add(options.getGraphicsBackend().getMinecraftArg());
        }

        res.addAllWithoutParsing(Arguments.parseStringArguments(options.getGameArguments(), configuration));

        List<String> generatedTokens = res.asList();
        List<String> prefixTokens = filterForbidden(generatedTokens.subList(0, prefixTokenCount));
        List<String> resolvedJvmArguments = filterForbidden(
                generatedTokens.subList(prefixTokenCount + 1, mainClassIndex));
        List<String> gameArguments = filterForbidden(generatedTokens.subList(mainClassIndex + 1,
                generatedTokens.size()));
        if (isForbidden(generatedTokens.get(prefixTokenCount))
                || isForbidden(generatedTokens.get(mainClassIndex))) {
            throw new IllegalStateException("Java executable and main class cannot be forbidden");
        }

        List<String> classpathEntries = extractTrailingClasspath(resolvedJvmArguments);
        String accessToken = authInfo.getAccessToken();
        LaunchCommandPlan command = LaunchCommandPlan.structuredJava(
                protectSecrets(prefixTokens, accessToken),
                protectSecret(generatedTokens.get(prefixTokenCount), accessToken),
                protectSecrets(resolvedJvmArguments, accessToken),
                protectSecrets(classpathEntries, accessToken),
                protectSecret(generatedTokens.get(mainClassIndex), accessToken),
                protectSecrets(gameArguments, accessToken)
        );

        Path runDirectory = FileUtils.toAbsolute(repository.getRunDirectory(manifest.id()));
        Map<String, String> auxiliaryEnvironment = getEnvVars(nativeFolder);
        @Nullable LaunchAuxiliaryProcessPlan preLaunch = auxiliaryProcess(
                options.getPreLaunchCommand(), runDirectory, auxiliaryEnvironment, accessToken);
        @Nullable LaunchAuxiliaryProcessPlan postExit = auxiliaryProcess(
                options.getPostExitCommand(), FileUtils.toAbsolute(options.getGameDir()),
                auxiliaryEnvironment, accessToken);

        Map<String, String> processEnvironment = new LinkedHashMap<>(auxiliaryEnvironment);
        @Nullable Path appdata = options.getGameDir().toAbsolutePath().getParent();
        if (appdata != null) {
            processEnvironment.put("APPDATA", appdata.toString());
        }
        LaunchProcessPlan plan = new LaunchProcessPlan(
                LaunchProcessPlan.CURRENT_PLAN_VERSION,
                mode,
                command,
                runDirectory,
                true,
                protectEnvironment(processEnvironment, accessToken),
                Set.of(),
                preLaunch,
                postExit,
                daemon ? "keep" : "close",
                listener == null,
                daemon
        );
        Map<String, String> secrets = Map.of(ACCESS_TOKEN_SECRET, accessToken);
        plan.validate(secrets.keySet());
        return new LaunchPreparation(plan, secrets, tempNativeFolder, FileUtils.toAbsolute(nativeFolder),
                javaNativeFolder, encoding);
    }

    /// Filters launcher-forbidden tokens while preserving encounter order.
    ///
    /// @param tokens generated command tokens
    /// @return mutable filtered token list
    private List<String> filterForbidden(List<String> tokens) {
        List<String> filtered = new ArrayList<>(tokens.size());
        for (String token : tokens) {
            if (!isForbidden(token)) {
                filtered.add(token);
            }
        }
        return filtered;
    }

    /// Returns whether one generated token is forbidden for the selected Java runtime.
    ///
    /// @param token generated token
    /// @return whether the token must be removed
    private boolean isForbidden(String token) {
        @Nullable Supplier<Boolean> condition = getForbiddens().get(token);
        return condition != null && condition.get();
    }

    /// Extracts a trailing `-cp` pair without changing the final command order.
    ///
    /// A non-trailing classpath pair remains among the JVM arguments because moving it would change
    /// the existing command token order.
    ///
    /// @param jvmArguments mutable generated JVM arguments
    /// @return mutable classpath entry list
    private static List<String> extractTrailingClasspath(List<String> jvmArguments) {
        int optionIndex = jvmArguments.size() - 2;
        if (optionIndex < 0 || !"-cp".equals(jvmArguments.get(optionIndex))) {
            return new ArrayList<>();
        }
        String classpath = jvmArguments.remove(optionIndex + 1);
        jvmArguments.remove(optionIndex);
        return new ArrayList<>(Arrays.asList(classpath.split(Pattern.quote(File.pathSeparator), -1)));
    }

    /// Creates an optional immutable auxiliary process from one configured command string.
    ///
    /// @param command configured command string
    /// @param workingDirectory process working directory
    /// @param environment inherited launcher environment additions
    /// @param accessToken launch access token
    /// @return auxiliary process plan, or `null` for a blank command
    private static @Nullable LaunchAuxiliaryProcessPlan auxiliaryProcess(
            @Nullable String command,
            Path workingDirectory,
            Map<String, String> environment,
            String accessToken
    ) {
        if (StringUtils.isBlank(command)) {
            return null;
        }
        return new LaunchAuxiliaryProcessPlan(
                protectSecrets(StringUtils.tokenize(command, environment), accessToken),
                workingDirectory,
                true,
                protectEnvironment(environment, accessToken),
                Set.of()
        );
    }

    /// Converts resolved environment values into immutable secret-aware plan text.
    ///
    /// @param environment resolved environment values
    /// @param accessToken launch access token
    /// @return immutable protected environment map
    private static @Unmodifiable Map<String, LaunchPlanText> protectEnvironment(
            Map<String, String> environment,
            String accessToken
    ) {
        Map<String, LaunchPlanText> protectedEnvironment = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : environment.entrySet()) {
            protectedEnvironment.put(entry.getKey(), protectSecret(entry.getValue(), accessToken));
        }
        return Collections.unmodifiableMap(protectedEnvironment);
    }

    /// Converts resolved tokens into immutable secret-aware plan text.
    ///
    /// @param tokens resolved tokens
    /// @param accessToken launch access token
    /// @return immutable protected tokens
    private static @Unmodifiable List<LaunchPlanText> protectSecrets(
            List<String> tokens,
            String accessToken
    ) {
        List<LaunchPlanText> protectedTokens = new ArrayList<>(tokens.size());
        for (String token : tokens) {
            protectedTokens.add(protectSecret(token, accessToken));
        }
        return List.copyOf(protectedTokens);
    }

    /// Replaces every access-token occurrence with an opaque secret segment.
    ///
    /// @param value resolved text
    /// @param accessToken launch access token
    /// @return immutable secret-aware text
    private static LaunchPlanText protectSecret(String value, String accessToken) {
        if (accessToken.isEmpty() || !value.contains(accessToken)) {
            return LaunchPlanText.literal(value);
        }
        List<LaunchPlanText.Segment> segments = new ArrayList<>();
        int offset = 0;
        int match;
        while ((match = value.indexOf(accessToken, offset)) >= 0) {
            if (match > offset) {
                segments.add(new LaunchPlanText.LiteralSegment(value.substring(offset, match)));
            }
            segments.add(new LaunchPlanText.SecretSegment(ACCESS_TOKEN_SECRET));
            offset = match + accessToken.length();
        }
        if (offset < value.length()) {
            segments.add(new LaunchPlanText.LiteralSegment(value.substring(offset)));
        }
        return LaunchPlanText.template(segments);
    }

    public Map<String, Boolean> getFeatures() {
        return Collections.singletonMap(
                "has_custom_resolution",
                options.getHeight() != null && options.getHeight() != 0 && options.getWidth() != null && options.getWidth() != 0
        );
    }

    private final Map<String, Supplier<Boolean>> forbiddens = mapOf(
            pair("-Xincgc", () -> options.getJava().getParsedVersion() >= 9)
    );

    protected Map<String, Supplier<Boolean>> getForbiddens() {
        return forbiddens;
    }

    protected List<Argument> getDefaultJVMArguments() {
        return Arguments.DEFAULT_JVM_ARGUMENTS;
    }

    protected List<Argument> getDefaultGameArguments() {
        return Arguments.DEFAULT_GAME_ARGUMENTS;
    }

    /**
     * Do something here.
     * i.e.
     * -Dminecraft.launcher.version=&lt;Your launcher name&gt;
     * -Dminecraft.launcher.brand=&lt;Your launcher version&gt;
     * -Dlog4j.configurationFile=&lt;Your custom log4j configuration&gt;
     */
    protected void appendJvmArgs(CommandBuilder result) {
    }

    public void decompressNatives(Path destination) throws NotDecompressingNativesException {
        LOG.info("Decompress native libraries to " + destination);

        try {
            FileUtils.cleanDirectoryQuietly(destination);
            for (Library library : manifest.getLibraries())
                if (library.isNative())
                    new Unzipper(repository.getLibraryFile(manifest, library), destination)
                            .setFilter((zipEntry, destFile, relativePath) -> {
                                if (!zipEntry.isDirectory() && !zipEntry.isUnixSymlink()
                                        && Files.isRegularFile(destFile)
                                        && zipEntry.getSize() == Files.size(destFile)) {
                                    return false;
                                }
                                String ext = FileUtils.getExtension(destFile);
                                if (ext.equals("sha1") || ext.equals("git"))
                                    return false;

                                if (options.isUseNativeGLFW() && FileUtils.getName(destFile).toLowerCase(Locale.ROOT).contains("glfw")) {
                                    return false;
                                }
                                if (options.isUseNativeOpenAL() && FileUtils.getName(destFile).toLowerCase(Locale.ROOT).contains("openal")) {
                                    return false;
                                }

                                return library.getExtract().shouldExtract(relativePath);
                            })
                            .setReplaceExistentFile(false).unzip();
        } catch (IOException e) {
            throw new NotDecompressingNativesException(e);
        }
    }

    private boolean isUsingLog4j() {
        return GameVersionNumber.compare(repository.getGameVersion(manifest).orElse("1.7"), "1.7") >= 0;
    }

    public Path getLog4jConfigurationFile() {
        return repository.getInstanceRoot(manifest.id()).resolve("log4j2.xml");
    }

    public void extractLog4jConfigurationFile() throws IOException {
        Path targetFile = getLog4jConfigurationFile();

        String sourcePath;

        if (GameVersionNumber.asGameVersion(repository.getGameVersion(manifest)).compareTo("1.12") < 0) {
            if (options.isEnableDebugLogOutput()) {
                sourcePath = "/assets/game/log4j2-1.7-debug.xml";
            } else {
                sourcePath = "/assets/game/log4j2-1.7.xml";
            }
        } else {
            if (options.isEnableDebugLogOutput()) {
                sourcePath = "/assets/game/log4j2-1.12-debug.xml";
            } else {
                sourcePath = "/assets/game/log4j2-1.12.xml";
            }
        }

        try (InputStream input = DefaultLauncher.class.getResourceAsStream(sourcePath)) {
            Files.copy(input, targetFile, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    protected Map<String, String> getConfigurations() {
        return mapOf(
                // defined by Minecraft official launcher
                pair("${auth_player_name}", authInfo.getUsername()),
                pair("${auth_session}", authInfo.getAccessToken()),
                pair("${auth_access_token}", authInfo.getAccessToken()),
                pair("${auth_uuid}", UUIDs.toCompactString(authInfo.getUUID())),
                pair("${version_name}", Optional.ofNullable(options.getVersionName()).orElse(manifest.id().toString())),
                pair("${profile_name}", Optional.ofNullable(options.getProfileName()).orElse("Minecraft")),
                pair("${version_type}", Optional.ofNullable(options.getVersionType()).orElse(manifest.type() != null ? manifest.type().getId() : ReleaseType.UNKNOWN.getId())),
                pair("${game_directory}", FileUtils.getAbsolutePath(repository.getRunDirectory(manifest.id()))),
                pair("${user_type}", authInfo.getUserType()),
                pair("${assets_index_name}", manifest.getAssetIndex().getId()),
                pair("${user_properties}", authInfo.getUserProperties()),
                pair("${resolution_width}", options.getWidth().toString()),
                pair("${resolution_height}", options.getHeight().toString()),
                pair("${library_directory}", FileUtils.getAbsolutePath(repository.getLibrariesDirectory(manifest))),
                pair("${classpath_separator}", File.pathSeparator),
                pair("${primary_jar}", FileUtils.getAbsolutePath(repository.getInstanceJar(manifest))),
                pair("${language}", Locale.getDefault().toLanguageTag()),

                // defined by HMCL
                // libraries_directory stands for historical reasons here. We don't know the official launcher
                // had already defined "library_directory" as the placeholder for path to ".minecraft/libraries"
                // when we propose this placeholder.
                pair("${libraries_directory}", FileUtils.getAbsolutePath(repository.getLibrariesDirectory(manifest))),
                // file_separator is used in -DignoreList
                pair("${file_separator}", File.separator),
                pair("${primary_jar_name}", FileUtils.getName(repository.getInstanceJar(manifest)))
        );
    }

    /// Returns the native library directory selected by the launch options.
    private Path getNativeFolder() {
        if (StringUtils.isBlank(options.getNativesDir())) {
            return repository.getNativeDirectory(manifest.id(), options.getJava().getPlatform());
        }

        return Path.of(options.getNativesDir());
    }

    @Override
    public ManagedProcess launch() throws IOException, InterruptedException {
        return executeLaunch(prepareLaunch(LaunchExecutionMode.DIRECT), listener);
    }

    /// Prepares one immutable launch without extracting resources or starting processes.
    ///
    /// @param mode direct execution or script rendering
    /// @return complete immutable launch preparation
    /// @throws IOException if required launch inputs cannot be read
    protected LaunchPreparation prepareLaunch(LaunchExecutionMode mode) throws IOException {
        return generateLaunchPreparation(mode, getNativeFolder());
    }

    /// Executes one prepared direct launch with no additional exit cleanup.
    ///
    /// @param preparation immutable validated launch preparation
    /// @param processListener optional process listener
    /// @return managed game process
    /// @throws IOException if resource preparation or process creation fails
    /// @throws InterruptedException if an auxiliary process is interrupted
    protected ManagedProcess executeLaunch(
            LaunchPreparation preparation,
            @Nullable ProcessListener processListener
    ) throws IOException, InterruptedException {
        return executeLaunch(preparation, processListener, () -> {
        });
    }

    /// Executes one prepared direct launch and runs cleanup after listener and post-exit handling.
    ///
    /// @param preparation immutable validated launch preparation
    /// @param processListener optional process listener
    /// @param exitCleanup cleanup invoked after observed process exit handling
    /// @return managed game process
    /// @throws IOException if resource preparation or process creation fails
    /// @throws InterruptedException if an auxiliary process is interrupted
    protected ManagedProcess executeLaunch(
            LaunchPreparation preparation,
            @Nullable ProcessListener processListener,
            Runnable exitCleanup
    ) throws IOException, InterruptedException {
        Objects.requireNonNull(preparation, "preparation");
        Objects.requireNonNull(exitCleanup, "exitCleanup");
        LaunchProcessPlan plan = preparation.plan();
        if (plan.executionMode() != LaunchExecutionMode.DIRECT) {
            throw new IllegalArgumentException("Direct execution requires a direct launch plan");
        }
        plan.validate(preparation.secrets().keySet());
        Function<String, @Nullable String> secretResolver = preparation.secrets()::get;
        List<String> rawCommandLine = plan.command().resolve(secretResolver);

        @Nullable Path temporaryNativeLink = preparation.temporaryNativeLink();
        if (temporaryNativeLink != null) {
            Files.deleteIfExists(temporaryNativeLink);
            Files.createSymbolicLink(temporaryNativeLink, preparation.nativeFolder());
        }
        preparePrivateLaunchResources(preparation);

        if (plan.preLaunch() != null) {
            runAuxiliaryProcess(plan.preLaunch(), secretResolver);
        }

        Process process;
        try {
            ProcessBuilder builder = new ProcessBuilder(rawCommandLine)
                    .directory(plan.workingDirectory().toFile());
            applyEnvironment(builder, plan.inheritEnvironment(), plan.environmentSet(),
                    plan.environmentUnset(), secretResolver);
            if (plan.inheritIo()) {
                builder.inheritIO();
            }
            process = builder.start();
        } catch (IOException e) {
            throw new ProcessCreationException(e);
        }

        ManagedProcess managedProcess = new ManagedProcess(process, rawCommandLine);
        if (processListener != null) {
            startMonitors(managedProcess, processListener, preparation.outputEncoding(),
                    plan.daemonMonitors(), plan.postExit(), secretResolver, exitCleanup);
        }
        return managedProcess;
    }

    /// Extracts launcher-private resources after Hook transformations have completed.
    ///
    /// @param preparation transformed launch preparation
    /// @throws IOException if native or logging resources cannot be prepared
    private void preparePrivateLaunchResources(LaunchPreparation preparation) throws IOException {
        if (!options.isUseCustomNatives()) {
            decompressNatives(preparation.javaNativeFolder());
        }
        if (isUsingLog4j()) {
            extractLog4jConfigurationFile();
        }
    }

    /// Applies one plan's exact environment inheritance and edit policy to a process builder.
    ///
    /// @param builder target process builder
    /// @param inheritEnvironment whether to retain the launcher environment
    /// @param environmentSet environment values to set
    /// @param environmentUnset environment names to remove
    /// @param secretResolver final secret resolver
    private static void applyEnvironment(
            ProcessBuilder builder,
            boolean inheritEnvironment,
            Map<String, LaunchPlanText> environmentSet,
            Set<String> environmentUnset,
            Function<String, @Nullable String> secretResolver
    ) {
        Map<String, String> environment = builder.environment();
        if (!inheritEnvironment) {
            environment.clear();
        }
        for (String name : environmentUnset) {
            environment.remove(name);
        }
        for (Map.Entry<String, LaunchPlanText> entry : environmentSet.entrySet()) {
            environment.put(entry.getKey(), entry.getValue().resolve(secretResolver));
        }
    }

    /// Runs one resolved pre-launch or post-exit process synchronously.
    ///
    /// @param auxiliary immutable auxiliary process plan
    /// @param secretResolver final secret resolver
    /// @throws IOException if the process cannot be created
    /// @throws InterruptedException if process waiting is interrupted
    private static void runAuxiliaryProcess(
            LaunchAuxiliaryProcessPlan auxiliary,
            Function<String, @Nullable String> secretResolver
    ) throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(auxiliary.resolveCommand(secretResolver))
                .directory(auxiliary.workingDirectory().toFile());
        applyEnvironment(builder, auxiliary.inheritEnvironment(), auxiliary.environmentSet(),
                auxiliary.environmentUnset(), secretResolver);
        SystemUtils.callExternalProcess(builder);
    }

    private Map<String, String> getEnvVars(Path nativeFolder) {
        String versionName = Optional.ofNullable(options.getVersionName()).orElse(manifest.id().toString());

        Map<String, String> env = new LinkedHashMap<>();
        env.put("INST_NAME", versionName);
        env.put("INST_ID", versionName);
        env.put("INST_DIR", FileUtils.getAbsolutePath(repository.getInstanceRoot(manifest.id())));
        env.put("INST_MC_DIR", FileUtils.getAbsolutePath(repository.getRunDirectory(manifest.id())));
        env.put("INST_JAVA", options.getJava().getBinary().toString());

        if (options.getRenderer() instanceof Renderer.Driver driver) {
            if (OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS) {
                if (driver.mesaDriverName() != null) {
                    if (driver instanceof Renderer.OpenGL && driver != Renderer.OpenGL.LLVMPIPE)
                        env.put("GALLIUM_DRIVER", driver.mesaDriverName());
                    else if (driver instanceof Renderer.Vulkan vulkanDriver) {
                        String icdFile = FileUtils.getAbsolutePath(nativeFolder.resolve("mesa-loader/" + vulkanDriver.icdName() + "_icd.json"));

                        env.put("VK_ICD_FILENAMES", icdFile);
                        env.put("VK_DRIVER_FILES", icdFile);
                    }
                } else if (driver instanceof Renderer.Vulkan vulkanDriver
                        && vulkanDriver.icdFile() != null
                        && options.getJava().getArchitecture() == Architecture.SYSTEM_ARCH) {
                    String icdFile = FileUtils.getAbsolutePath(vulkanDriver.icdFile());

                    env.put("VK_ICD_FILENAMES", icdFile);
                    env.put("VK_DRIVER_FILES", icdFile);
                }
            } else if (OperatingSystem.CURRENT_OS.isLinuxOrBSD()) {
                if (driver instanceof Renderer.OpenGL oglDriver) {
                    if (oglDriver == Renderer.OpenGL.LLVMPIPE) {
                        env.put("__GLX_VENDOR_LIBRARY_NAME", "mesa");
                        env.put("LIBGL_ALWAYS_SOFTWARE", "1");
                    } else if (oglDriver == Renderer.OpenGL.ZINK) {
                        env.put("__GLX_VENDOR_LIBRARY_NAME", "mesa");
                        env.put("MESA_LOADER_DRIVER_OVERRIDE", "zink");
                        /*
                         * The amdgpu DDX is missing support for modifiers, causing Zink to fail.
                         * Disable DRI3 to workaround this issue.
                         *
                         * Link: https://gitlab.freedesktop.org/mesa/mesa/-/issues/10093
                         */
                        env.put("LIBGL_KOPPER_DRI2", "1");
                    }
                } else if (driver instanceof Renderer.Vulkan vulkanDriver
                        && options.getJava().getArchitecture() == Architecture.SYSTEM_ARCH) {
                    if (vulkanDriver.icdFile() != null) {
                        String absolutePath = FileUtils.getAbsolutePath(vulkanDriver.icdFile());
                        env.put("VK_ICD_FILENAMES", absolutePath);
                        env.put("VK_DRIVER_FILES", absolutePath);
                    }
                }
            } else if (OperatingSystem.CURRENT_OS == OperatingSystem.MACOS
                    && options.getJava().getArchitecture() == Architecture.SYSTEM_ARCH) {
                if (driver instanceof Renderer.Vulkan vulkanDriver
                        && vulkanDriver != Renderer.Vulkan.MOLTENVK
                        && vulkanDriver.icdFile() != null) {
                    String absolutePath = FileUtils.getAbsolutePath(vulkanDriver.icdFile());
                    env.put("VK_ICD_FILENAMES", absolutePath);
                    env.put("VK_DRIVER_FILES", absolutePath);
                }
            }
        }

        if (analyzer.has(LibraryAnalyzer.LibraryType.FORGE)) {
            env.put("INST_FORGE", "1");
        }
        if (analyzer.has(LibraryAnalyzer.LibraryType.CLEANROOM)) {
            env.put("INST_CLEANROOM", "1");
        }
        if (analyzer.has(LibraryAnalyzer.LibraryType.NEO_FORGE)) {
            env.put("INST_NEOFORGE", "1");
        }
        if (analyzer.has(LibraryAnalyzer.LibraryType.LITELOADER)) {
            env.put("INST_LITELOADER", "1");
        }
        if (analyzer.has(LibraryAnalyzer.LibraryType.FABRIC)) {
            env.put("INST_FABRIC", "1");
        }
        if (analyzer.has(LibraryAnalyzer.LibraryType.OPTIFINE)) {
            env.put("INST_OPTIFINE", "1");
        }
        if (analyzer.has(LibraryAnalyzer.LibraryType.QUILT)) {
            env.put("INST_QUILT", "1");
        }
        if (analyzer.has(LibraryAnalyzer.LibraryType.LEGACY_FABRIC)) {
            env.put("INST_LEGACYFABRIC", "1");
        }

        env.putAll(options.getEnvironmentVariables());

        return env;
    }

    @Override
    public void makeLaunchScript(Path scriptFile) throws IOException {
        renderLaunchScript(prepareLaunch(LaunchExecutionMode.SCRIPT), scriptFile);
    }

    /// Renders one prepared script plan after preparing launcher-private resources.
    ///
    /// @param preparation immutable validated launch preparation
    /// @param scriptFile target launch script
    /// @throws IOException if resource preparation or script writing fails
    protected void renderLaunchScript(LaunchPreparation preparation, Path scriptFile) throws IOException {
        Objects.requireNonNull(preparation, "preparation");
        Objects.requireNonNull(scriptFile, "scriptFile");
        LaunchProcessPlan plan = preparation.plan();
        if (plan.executionMode() != LaunchExecutionMode.SCRIPT) {
            throw new IllegalArgumentException("Script rendering requires a script launch plan");
        }
        plan.validate(preparation.secrets().keySet());
        preparePrivateLaunchResources(preparation);
        LaunchScriptRenderer.render(scriptFile, plan, preparation.secrets()::get,
                preparation.temporaryNativeLink(), preparation.nativeFolder());
        if ("ps1".equalsIgnoreCase(FileUtils.getExtension(scriptFile))
                && !CommandBuilder.hasExecutionPolicy()) {
            throw new ExecutionPolicyLimitException();
        }
    }

    /// Starts output pumps and one exit waiter for an observed game process.
    ///
    /// @param managedProcess managed game process
    /// @param processListener process event listener
    /// @param encoding process output encoding
    /// @param isDaemon whether monitor threads are daemon threads
    /// @param postExit optional post-exit process
    /// @param secretResolver final secret resolver
    /// @param exitCleanup cleanup invoked after listener and post-exit handling
    private void startMonitors(
            ManagedProcess managedProcess,
            ProcessListener processListener,
            Charset encoding,
            boolean isDaemon,
            @Nullable LaunchAuxiliaryProcessPlan postExit,
            Function<String, @Nullable String> secretResolver,
            Runnable exitCleanup
    ) {
        processListener.setProcess(managedProcess);
        Thread stdout = Lang.thread(new StreamPump(managedProcess.getProcess().getInputStream(), it -> {
            processListener.onLog(it, false);
            managedProcess.addLine(it);
        }, encoding), "stdout-pump", isDaemon);
        managedProcess.addRelatedThread(stdout);
        Thread stderr = Lang.thread(new StreamPump(managedProcess.getProcess().getErrorStream(), it -> {
            processListener.onLog(it, true);
            managedProcess.addLine(it);
        }, encoding), "stderr-pump", isDaemon);
        managedProcess.addRelatedThread(stderr);
        managedProcess.addRelatedThread(Lang.thread(new ExitWaiter(managedProcess, Arrays.asList(stdout, stderr), (exitCode, exitType) -> {
            try {
                try {
                    processListener.onExit(exitCode, exitType);
                } finally {
                    if (postExit != null) {
                        try {
                            runAuxiliaryProcess(postExit, secretResolver);
                        } catch (Throwable e) {
                            LOG.warning("An Exception happened while running exit command.", e);
                        }
                    }
                }
            } finally {
                exitCleanup.run();
            }
        }), "exit-waiter", isDaemon));
    }
}
