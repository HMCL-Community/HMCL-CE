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
package org.jackhuang.hmcl.game;

import org.jackhuang.hmcl.Metadata;
import org.jackhuang.hmcl.auth.AuthInfo;
import org.jackhuang.hmcl.launch.DefaultLauncher;
import org.jackhuang.hmcl.launch.LaunchExecutionMode;
import org.jackhuang.hmcl.launch.ProcessListener;
import org.jackhuang.hmcl.plugin.GameLaunchHookCoordinator;
import org.jackhuang.hmcl.plugin.PluginDataObject;
import org.jackhuang.hmcl.plugin.PluginDataValue;
import org.jackhuang.hmcl.plugin.PluginHookDispatchException;
import org.jackhuang.hmcl.util.NativePatcher;
import org.jackhuang.hmcl.util.i18n.LocaleUtils;
import org.jackhuang.hmcl.util.io.FileUtils;
import org.jackhuang.hmcl.util.io.JarUtils;
import org.jackhuang.hmcl.util.platform.Architecture;
import org.jackhuang.hmcl.util.platform.CommandBuilder;
import org.jackhuang.hmcl.util.platform.ManagedProcess;
import org.jackhuang.hmcl.util.platform.OperatingSystem;
import org.jackhuang.hmcl.util.versioning.GameVersionNumber;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// Adapts HMCL-specific launch metadata and resource preparation to the launcher-neutral execution pipeline.
@NotNullByDefault
public final class HMCLGameLauncher extends DefaultLauncher {
    /// Launch Hook coordinator used by both direct execution and script rendering.
    private final GameLaunchHookCoordinator hookCoordinator;

    /// Creates a launcher without a process listener.
    ///
    /// @param repository game repository
    /// @param manifest resolved launch manifest
    /// @param authInfo authenticated account information
    /// @param options launch options
    public HMCLGameLauncher(GameRepository repository, GameInstanceManifest manifest, AuthInfo authInfo, LaunchOptions options) {
        this(repository, manifest, authInfo, options, null);
    }

    /// Creates a launcher with an optional process listener.
    ///
    /// @param repository game repository
    /// @param manifest resolved launch manifest
    /// @param authInfo authenticated account information
    /// @param options launch options
    /// @param listener optional process listener
    public HMCLGameLauncher(
            GameRepository repository,
            GameInstanceManifest manifest,
            AuthInfo authInfo,
            LaunchOptions options,
            @Nullable ProcessListener listener
    ) {
        this(repository, manifest, authInfo, options, listener, true);
    }

    /// Creates a launcher with explicit process-monitor daemon behavior.
    ///
    /// @param repository game repository
    /// @param manifest resolved launch manifest
    /// @param authInfo authenticated account information
    /// @param options launch options
    /// @param listener optional process listener
    /// @param daemon whether process monitors use daemon threads
    public HMCLGameLauncher(
            GameRepository repository,
            GameInstanceManifest manifest,
            AuthInfo authInfo,
            LaunchOptions options,
            @Nullable ProcessListener listener,
            boolean daemon
    ) {
        this(repository, manifest, authInfo, options, listener, daemon,
                GameLaunchHookCoordinator.getInstance());
    }

    /// Creates an injectable launcher for Hook pipeline tests.
    ///
    /// @param repository game repository
    /// @param manifest resolved launch manifest
    /// @param authInfo authenticated account information
    /// @param options launch options
    /// @param listener optional process listener
    /// @param daemon whether process monitors use daemon threads
    /// @param hookCoordinator launch Hook coordinator
    HMCLGameLauncher(
            GameRepository repository,
            GameInstanceManifest manifest,
            AuthInfo authInfo,
            LaunchOptions options,
            @Nullable ProcessListener listener,
            boolean daemon,
            GameLaunchHookCoordinator hookCoordinator
    ) {
        super(repository, manifest, authInfo, options, listener, daemon);
        this.hookCoordinator = Objects.requireNonNull(hookCoordinator, "hookCoordinator");
    }

    /// Adds HMCL branding substitutions to the base launch configuration.
    ///
    /// @return mutable configuration map used during command generation
    @Override
    protected Map<String, String> getConfigurations() {
        Map<String, String> res = super.getConfigurations();
        res.put("${launcher_name}", Metadata.NAME);
        res.put("${launcher_version}", Metadata.VERSION);
        return res;
    }

    /// Creates a default language option file only when the instance has no existing language configuration.
    private void generateOptionsTxt() {
        if (options.isDisableAutoGameOptions())
            return;

        Path runDir = repository.getRunDirectory(manifest.id());
        Path optionsFile = runDir.resolve("options.txt");
        Path configFolder = runDir.resolve("config");

        if (Files.exists(optionsFile))
            return;

        if (Files.isDirectory(configFolder)) {
            try (Stream<Path> stream = Files.walk(configFolder, 2, FileVisitOption.FOLLOW_LINKS)) {
                if (stream.anyMatch(file -> "options.txt".equals(FileUtils.getName(file))))
                    return;
            } catch (IOException e) {
                LOG.warning("Failed to visit config folder", e);
            }
        }

        Locale locale = Locale.getDefault();

        /*
         *  1.0         : No language option, do not set for these versions
         *  1.1  ~ 1.5  : zh_CN works fine, zh_cn will crash (the last two letters must be uppercase, otherwise it will cause an NPE crash)
         *  1.6  ~ 1.10 : zh_CN works fine, zh_cn will automatically switch to English
         *  1.11 ~ 1.12 : zh_cn works fine, zh_CN will display Chinese but the language setting will incorrectly show English as selected
         *  1.13+       : zh_cn works fine, zh_CN will automatically switch to English
         */
        GameVersionNumber gameVersion = GameVersionNumber.asGameVersion(repository.getGameVersion(manifest));
        if (gameVersion.compareTo("1.1") < 0)
            return;

        String lang = normalizedLanguageTag(locale, gameVersion);
        if (lang.isEmpty())
            return;

        if (gameVersion.compareTo("1.11") >= 0)
            lang = lang.toLowerCase(Locale.ROOT);

        try {
            Files.createDirectories(optionsFile.getParent());
            Files.writeString(optionsFile, String.format("lang:%s\n", lang));
        } catch (IOException e) {
            LOG.warning("Unable to generate options.txt", e);
        }
    }

    /// Selects the legacy or modern Minecraft language identifier for one locale and game version.
    ///
    /// @param locale host locale
    /// @param gameVersion resolved game version
    /// @return normalized language identifier, or an empty string when no override is appropriate
    private static String normalizedLanguageTag(Locale locale, GameVersionNumber gameVersion) {
        String region = locale.getCountry();

        return switch (LocaleUtils.getRootLanguage(locale)) {
            case "ar" -> "ar_SA";
            case "es" -> "es_ES";
            case "ja" -> "ja_JP";
            case "ru" -> "ru_RU";
            case "uk" -> "uk_UA";
            case "zh" -> {
                if ("lzh".equals(locale.getLanguage()) && gameVersion.compareTo("1.16") >= 0)
                    yield "lzh";

                String script = LocaleUtils.getScript(locale);
                if ("Hant".equals(script)) {
                    if ((region.equals("HK") || region.equals("MO") && gameVersion.compareTo("1.16") >= 0))
                        yield "zh_HK";
                    yield "zh_TW";
                }
                yield "zh_CN";
            }
            case "en" -> {
                if ("Qabs".equals(LocaleUtils.getScript(locale)) && gameVersion.compareTo("1.16") >= 0) {
                    yield "en_UD";
                }

                yield "";
            }
            default -> "";
        };
    }

    /// Coordinates before Hooks and executes the resulting direct process plan.
    ///
    /// @return managed game process
    /// @throws IOException if preparation, Hook coordination, or process creation fails
    /// @throws InterruptedException if an auxiliary process is interrupted
    @Override
    public ManagedProcess launch() throws IOException, InterruptedException {
        generateOptionsTxt();
        GameLaunchHookCoordinator.LaunchSession session = coordinateLaunch(LaunchExecutionMode.DIRECT);
        try {
            return executeLaunch(
                    session.preparation(),
                    session.processListener(listener),
                    session::finishExit
            );
        } catch (IOException | InterruptedException | RuntimeException | Error failure) {
            session.closeWithoutProcess();
            throw failure;
        }
    }

    /// Coordinates before Hooks and renders the resulting script process plan.
    ///
    /// @param scriptFile target script path
    /// @throws IOException if preparation, Hook coordination, or rendering fails
    @Override
    public void makeLaunchScript(Path scriptFile) throws IOException {
        generateOptionsTxt();
        GameLaunchHookCoordinator.LaunchSession session = coordinateLaunch(LaunchExecutionMode.SCRIPT);
        renderLaunchScript(session.preparation(), scriptFile);
    }

    /// Prepares and coordinates one launch before any auxiliary process or native extraction begins.
    ///
    /// @param mode direct or script mode
    /// @return transformed launch session
    /// @throws IOException if preparation or Hook coordination fails
    private GameLaunchHookCoordinator.LaunchSession coordinateLaunch(LaunchExecutionMode mode) throws IOException {
        try {
            return hookCoordinator.beforeLaunch(prepareLaunch(mode), launchMetadata(mode));
        } catch (PluginHookDispatchException failure) {
            throw new GameLaunchHookIOException(failure);
        }
    }

    /// Creates immutable launcher and host metadata that plugins cannot replace.
    ///
    /// @param mode direct or script execution mode
    /// @return immutable launch metadata
    private PluginDataObject launchMetadata(LaunchExecutionMode mode) {
        return PluginDataObject.of(Map.of(
                "instanceId", PluginDataValue.string(manifest.id().id()),
                "gameVersion", PluginDataValue.string(repository.getGameVersion(manifest).orElse("unknown")),
                "launcherVersion", PluginDataValue.string(Metadata.VERSION),
                "hostOs", PluginDataValue.string(OperatingSystem.CURRENT_OS.getCheckedName()),
                "hostArchitecture", PluginDataValue.string(Architecture.SYSTEM_ARCH.getCheckedName()),
                "executionMode", PluginDataValue.string(mode.name().toLowerCase(Locale.ROOT))
        ));
    }

    /// Appends HMCL-specific JVM agents after the base JVM arguments.
    ///
    /// @param result mutable command builder
    @Override
    protected void appendJvmArgs(CommandBuilder result) {
        super.appendJvmArgs(result);

        if (options.isAllowAutoAgent()
                && !options.isNoGeneratedJVMArgs()
                && !options.isNoGeneratedOptimizingJVMArgs()
                && NativePatcher.needPatchMemoryUtil(manifest, options.getJava().getParsedVersion())) {
            LOG.info("Attempting to patch game with lwjgl-unsafe-agent");
            try {
                result.add("-javaagent:" + extractLwjglUnsafeAgent());
            } catch (Exception e) {
                LOG.warning("Failed to extract lwjgl-unsafe-agent", e);
            }
        }
    }

    /// Extracts the bundled LWJGL unsafe agent when the launch requires it.
    ///
    /// @return absolute agent path
    /// @throws IOException if the embedded agent is missing or cannot be written
    private Path extractLwjglUnsafeAgent() throws IOException {
        String agentVersion = JarUtils.getAttribute("hmcl.lwjgl-unsafe-agent.version", null);
        if (agentVersion == null) {
            throw new IOException("Missing hmcl.lwjgl-unsafe-agent.version attribute");
        }

        Library library = new Library(new Artifact("org.glavo", "lwjgl-unsafe-agent", agentVersion));
        String fileName = library.artifact().getFileName();

        Path agentPath = repository.getLibraryFile(manifest, library).toAbsolutePath().normalize();
        if (agentPath.toString().contains("=")) {
            throw new IOException("Invalid library path: " + agentPath);
        }

        byte[] bytes;
        try (InputStream input = DefaultLauncher.class.getResourceAsStream("/assets/" + fileName)) {
            if (input == null) {
                throw new IOException("/assets/" + fileName + " not found");
            }

            bytes = input.readAllBytes();
        }

        if (Files.isRegularFile(agentPath)) {
            try {
                if (Files.size(agentPath) == bytes.length) {
                    return agentPath;
                }
            } catch (IOException e) {
                LOG.warning("Failed to check size of " + agentPath, e);
            }
        }

        Files.createDirectories(agentPath.getParent());
        FileUtils.saveSafely(agentPath, output -> output.write(bytes));
        return agentPath;
    }

}
