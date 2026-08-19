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

import org.jackhuang.hmcl.plugin.PluginMutationLock;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.Mixins;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;
import org.spongepowered.asm.service.MixinService;

import java.io.IOException;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarFile;

/// Installs SpongePowered Mixin as an instrumentation transformer before HMCL application classes load.
@NotNullByDefault
public final class HmclMixinAgent {
    /// Plugin JAR handles retained because instrumentation may continue reading them for the process lifetime.
    private static final List<JarFile> OPEN_PLUGIN_JARS = new ArrayList<>();

    /// Prevents construction of the premain agent utility.
    private HmclMixinAgent() {
    }

    /// Initializes plugin class paths, Mixin configuration, and the HMCL class-file transformer.
    ///
    /// @param agentArguments optional `-javaagent` argument text
    /// @param instrumentation active JVM instrumentation handle
    public static void premain(@Nullable String agentArguments, Instrumentation instrumentation) {
        PluginAgentSnapshot.clear();
        System.setProperty(HmclMixinBootstrap.AGENT_ACTIVE_PROPERTY, "true");
        if (Boolean.getBoolean(HmclMixinBootstrap.DISABLE_PROPERTY)) {
            System.clearProperty(HmclMixinBootstrap.ACTIVE_PROPERTY);
            report("Plugin Mixins are disabled for this launch");
            return;
        }

        try {
            Path localHome = HmclMixinBootstrap.resolveLocalHome();
            runInitializationUnderMutationLock(
                    localHome,
                    () -> initializePluginMixins(localHome, instrumentation)
            );
        } catch (Throwable throwable) {
            handleInitializationFailure(throwable);
        }
    }

    /// Holds the launcher-local mutation lock for the complete second verification and Agent publication sequence.
    ///
    /// @param localHome launcher-local home
    /// @param initialization complete Agent initialization action
    /// @throws IOException if lock acquisition or initialization fails
    static void runInitializationUnderMutationLock(
            Path localHome,
            PluginMutationLock.IORunnable initialization
    ) throws IOException {
        new PluginMutationLock(localHome).run(initialization);
    }

    /// Revalidates packages and grants, appends verified class paths, and publishes exact active artifacts.
    ///
    /// The caller must hold the launcher-local mutation lock for this complete method so installation or permission
    /// changes cannot split the configuration snapshot from the class path and authorization snapshot it publishes.
    ///
    /// @param localHome launcher-local home protected by the mutation lock
    /// @param instrumentation active JVM instrumentation handle
    /// @throws IOException if discovery, class-path publication, or Mixin initialization fails
    private static void initializePluginMixins(Path localHome, Instrumentation instrumentation) throws IOException {
        HmclMixinBootstrap.AgentConfiguration configuration =
                HmclMixinBootstrap.prepareAgentConfiguration(localHome);
        if (configuration.mixinConfigs().isEmpty()) {
            return;
        }

        configuration.verifyInstalledArtifacts();
        appendPluginClassPath(configuration.classPathArtifacts(), instrumentation);
        ClassLoader systemClassLoader = ClassLoader.getSystemClassLoader();
        HmclMixinService.configure(systemClassLoader, instrumentation);

        MixinBootstrap.init();
        MixinEnvironment.getDefaultEnvironment().setSide(MixinEnvironment.Side.CLIENT);
        Mixins.addConfigurations(configuration.mixinConfigs().toArray(String[]::new), null);

        HmclMixinService service = (HmclMixinService) MixinService.getService();
        IMixinTransformer transformer = service.createTransformer();
        try {
            enterDefaultPhase();
        } catch (ReflectiveOperationException exception) {
            throw new IOException("Unable to enter the default Mixin phase", exception);
        }
        configuration.verifyInstalledArtifacts();
        instrumentation.addTransformer(new HmclClassFileTransformer(transformer), false);
        PluginAgentSnapshot.publish(configuration.registrations());

        System.setProperty(
                HmclMixinBootstrap.ACTIVE_PROPERTY,
                String.join(",", configuration.activePluginIds())
        );
        report("Enabled " + configuration.mixinConfigs().size() + " Mixin configuration(s) from "
                + configuration.activePluginIds().size() + " plugin(s)");
    }

    /// Fails closed without throwing from premain, so HMCL remains available for plugin management and removal.
    ///
    /// @param failure Agent initialization failure
    static void handleInitializationFailure(Throwable failure) {
        PluginAgentSnapshot.clear();
        System.setProperty(HmclMixinBootstrap.DISABLE_PROPERTY, "true");
        System.clearProperty(HmclMixinBootstrap.ACTIVE_PROPERTY);
        report("Failed to initialize plugin Mixins; continuing with every Mixin plugin blocked: " + failure);
    }

    /// Appends immutable generated and nested plugin JAR files to the system class loader search path.
    ///
    /// @param entries extracted roots and JAR files
    /// @param instrumentation active instrumentation handle
    /// @throws IOException if a resource JAR cannot be created or opened
    static void appendPluginClassPath(
            @Unmodifiable List<HmclMixinBootstrap.AgentClassPathEntry> entries,
            Instrumentation instrumentation
    ) throws IOException {
        for (HmclMixinBootstrap.AgentClassPathEntry entry : entries) {
            Path jarPath = entry.path();
            verifyNoSymbolicLinkComponents(jarPath);
            if (!Files.isRegularFile(jarPath, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Verified Agent class-path entry is not a regular file: " + jarPath);
            }

            JarFile jarFile = new JarFile(jarPath.toFile());
            boolean retained = false;
            try {
                String actualDigest = HmclMixinBootstrap.calculateAgentJarDigest(jarFile);
                if (!entry.contentDigest().equals(actualDigest)) {
                    throw new IOException("Verified Agent JAR changed before append: " + jarPath);
                }
                instrumentation.appendToSystemClassLoaderSearch(jarFile);
                OPEN_PLUGIN_JARS.add(jarFile);
                retained = true;
            } finally {
                if (!retained) {
                    jarFile.close();
                }
            }
        }
    }

    /// Rejects a JAR path when any existing component is a symbolic link.
    ///
    /// @param path normalized Agent JAR path
    /// @throws IOException if a symbolic link appears in the path
    private static void verifyNoSymbolicLinkComponents(Path path) throws IOException {
        Path absolutePath = path.toAbsolutePath().normalize();
        @Nullable Path current = absolutePath.getRoot();
        for (Path component : absolutePath) {
            current = current == null ? component : current.resolve(component);
            if (Files.isSymbolicLink(current)) {
                throw new IOException("Agent class-path entry contains a symbolic link: " + current);
            }
        }
    }

    /// Advances the fixed Mixin 0.8.7 environment from `PREINIT` to `DEFAULT` before HMCL loads.
    ///
    /// @throws ReflectiveOperationException if the bundled host hook changes
    private static void enterDefaultPhase() throws ReflectiveOperationException {
        Method gotoPhase = MixinEnvironment.class.getDeclaredMethod(
                "gotoPhase",
                MixinEnvironment.Phase.class
        );
        gotoPhase.setAccessible(true);
        try {
            gotoPhase.invoke(null, MixinEnvironment.Phase.DEFAULT);
        } catch (InvocationTargetException exception) {
            @Nullable Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw exception;
        }
    }

    /// Prints an agent diagnostic before the regular HMCL logger is initialized.
    ///
    /// @param message diagnostic text
    private static void report(String message) {
        System.err.println("[HMCL Plugin Mixin] " + message);
    }

    /// Applies Mixin only to HMCL application classes loaded by the system loader.
    @NotNullByDefault
    private static final class HmclClassFileTransformer implements ClassFileTransformer {
        /// Internal Mixin transformer configured during premain.
        private final IMixinTransformer transformer;

        /// Creates an instrumentation bridge for one active Mixin transformer.
        ///
        /// @param transformer active Mixin transformer
        private HmclClassFileTransformer(IMixinTransformer transformer) {
            this.transformer = transformer;
        }

        /// Applies Mixin before an HMCL class is defined.
        ///
        /// @param module defining module
        /// @param loader defining loader, or `null` for bootstrap classes
        /// @param className internal class name, or `null`
        /// @param classBeingRedefined class being redefined, or `null` for first definition
        /// @param protectionDomain target protection domain
        /// @param classFileBuffer original bytecode
        /// @return transformed bytecode or `null` when unchanged
        /// @throws IllegalClassFormatException if Mixin rejects the target class
        @Override
        public byte @Nullable [] transform(
                @Nullable Module module,
                @Nullable ClassLoader loader,
                @Nullable String className,
                @Nullable Class<?> classBeingRedefined,
                @Nullable ProtectionDomain protectionDomain,
                byte[] classFileBuffer
        ) throws IllegalClassFormatException {
            if (loader == null
                    || className == null
                    || classBeingRedefined != null
                    || !className.startsWith("org/jackhuang/hmcl/")
                    || className.startsWith("org/jackhuang/hmcl/plugin/mixin/bootstrap/")) {
                return null;
            }

            String binaryName = className.replace('/', '.');
            try {
                byte[] transformed = transformer.transformClassBytes(binaryName, binaryName, classFileBuffer);
                return transformed == classFileBuffer ? null : transformed;
            } catch (Throwable throwable) {
                IllegalClassFormatException exception = new IllegalClassFormatException(
                        "Mixin failed for " + binaryName + ": " + throwable.getMessage()
                );
                exception.initCause(throwable);
                throw exception;
            }
        }
    }
}
