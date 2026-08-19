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
package org.jackhuang.hmcl.plugin.loader;

import org.jackhuang.hmcl.plugin.Plugin;
import org.jackhuang.hmcl.plugin.PluginManifest;
import org.jackhuang.hmcl.plugin.internal.PluginPackageVersions;
import org.jackhuang.hmcl.plugin.internal.VerifiedPluginPackage;
import org.jackhuang.hmcl.plugin.mixin.bootstrap.PluginAgentSnapshot;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;

/// Loads Java and Kotlin lifecycle entry points from extracted plugin packages.
@NotNullByDefault
public final class JavaPluginLoader implements PluginLoader {
    /// Creates a JVM plugin loader.
    public JavaPluginLoader() {
    }

    /// Loads and instantiates the manifest entry point with access to HMCL and startup Mixin classes.
    ///
    /// @param manifest validated plugin manifest
    /// @param pluginPackage exact verified package inventory
    /// @param nplFile installed package path
    /// @return instantiated lifecycle implementation
    /// @throws IOException if class path discovery or instantiation fails
    @Override
    public Plugin load(
            PluginManifest manifest,
            VerifiedPluginPackage pluginPackage,
            Path nplFile
    ) throws IOException {
        verifySourceIdentity(pluginPackage, nplFile);
        String mixinDigest = PluginAgentSnapshot.calculateMixinConfigurationDigest(manifest.getMixins());
        if (manifest.hasMixins()) {
            if (!PluginAgentSnapshot.current().confirms(pluginPackage.getIdentity(), mixinDigest)) {
                throw new IOException("The active Mixin Agent did not confirm exact artifact "
                        + pluginPackage.getIdentity());
            }
            pluginPackage.verifyIntegrity();
            if (!pluginPackage.containsClass(manifest.getEntrypoint())) {
                throw new IOException("Plugin entry point is not present in the verified package: "
                        + manifest.getEntrypoint());
            }
            return loadAgentOwnedPlugin(manifest, pluginPackage, nplFile, mixinDigest);
        }
        PluginClassLoader classLoader = new PluginClassLoader(
                JavaPluginLoader.class.getClassLoader(),
                pluginPackage
        );
        try {
            Class<?> pluginClass = classLoader.loadPluginClass(manifest.getEntrypoint());
            verifySourceIdentity(pluginPackage, nplFile);
            if (!Plugin.class.isAssignableFrom(pluginClass)) {
                throw new IOException("Plugin class must implement Plugin: " + manifest.getEntrypoint());
            }
            return instantiatePlugin(pluginClass, classLoader);
        } catch (ReflectiveOperationException | LinkageError exception) {
            classLoader.close();
            throw new IOException("Failed to instantiate plugin: " + manifest.getEntrypoint(), exception);
        } catch (IOException exception) {
            classLoader.close();
            throw exception;
        }
    }

    /// Loads an exact Agent-confirmed Mixin lifecycle class from the system loader without defining a second copy.
    ///
    /// @param manifest validated Mixin plugin manifest
    /// @param pluginPackage verified lifecycle package inventory
    /// @param nplFile installed source package
    /// @param mixinDigest ordered Mixin declaration digest
    /// @return instantiated Agent-owned lifecycle implementation
    /// @throws IOException if class loading, source ownership, or instantiation fails
    private static Plugin loadAgentOwnedPlugin(
            PluginManifest manifest,
            VerifiedPluginPackage pluginPackage,
            Path nplFile,
            String mixinDigest
    ) throws IOException {
        try {
            Class<?> pluginClass = Class.forName(
                    manifest.getEntrypoint(),
                    false,
                    ClassLoader.getSystemClassLoader()
            );
            if (!PluginAgentSnapshot.current().ownsClass(
                    pluginPackage.getIdentity(),
                    mixinDigest,
                    pluginClass
            )) {
                throw new IOException("Agent-confirmed plugin entry point resolved from another code source: "
                        + manifest.getEntrypoint());
            }
            verifySourceIdentity(pluginPackage, nplFile);
            pluginPackage.verifyIntegrity();
            if (!Plugin.class.isAssignableFrom(pluginClass)) {
                throw new IOException("Plugin class must implement Plugin: " + manifest.getEntrypoint());
            }
            @Nullable ClassLoader classLoader = pluginClass.getClassLoader();
            if (classLoader == null) {
                throw new IOException("Agent-owned plugin entry point was defined by the bootstrap loader: "
                        + manifest.getEntrypoint());
            }
            return instantiatePlugin(pluginClass, classLoader);
        } catch (ReflectiveOperationException | LinkageError exception) {
            throw new IOException("Failed to instantiate Agent-owned plugin: " + manifest.getEntrypoint(), exception);
        }
    }

    /// Runs one lifecycle callback with the plugin's defining loader installed as the thread context class loader.
    ///
    /// This scope enables standard facilities such as `ServiceLoader`, resource bundles, and FXML discovery to find
    /// only the resources exposed by an ordinary [PluginClassLoader]. The previous context loader is restored even
    /// when the callback fails. PluginManager should use this helper for every lifecycle callback.
    ///
    /// @param pluginClassLoader loader that defines the plugin lifecycle implementation
    /// @param callback plugin lifecycle callback
    public static void runWithPluginContextClassLoader(ClassLoader pluginClassLoader, Runnable callback) {
        Thread thread = Thread.currentThread();
        @Nullable ClassLoader previousClassLoader = thread.getContextClassLoader();
        thread.setContextClassLoader(pluginClassLoader);
        try {
            callback.run();
        } finally {
            thread.setContextClassLoader(previousClassLoader);
        }
    }

    /// Instantiates one plugin while its defining loader is the thread context class loader.
    ///
    /// @param pluginClass verified lifecycle implementation class
    /// @param pluginClassLoader loader that defined the lifecycle class
    /// @return instantiated plugin
    /// @throws ReflectiveOperationException if the default constructor cannot be invoked
    private static Plugin instantiatePlugin(
            Class<?> pluginClass,
            ClassLoader pluginClassLoader
    ) throws ReflectiveOperationException {
        Thread thread = Thread.currentThread();
        @Nullable ClassLoader previousClassLoader = thread.getContextClassLoader();
        thread.setContextClassLoader(pluginClassLoader);
        try {
            return (Plugin) pluginClass.getDeclaredConstructor().newInstance();
        } finally {
            thread.setContextClassLoader(previousClassLoader);
        }
    }

    /// Verifies that the published `.npl` still matches the identity approved before loader entry.
    ///
    /// @param pluginPackage verified package identity and inventory
    /// @param nplFile installed source package
    /// @throws IOException if the complete package bytes changed
    private static void verifySourceIdentity(
            VerifiedPluginPackage pluginPackage,
            Path nplFile
    ) throws IOException {
        String actualSha256 = PluginPackageVersions.calculateSha256(nplFile);
        if (!pluginPackage.getIdentity().getSha256().equals(actualSha256)) {
            throw new IOException("Plugin package changed during entry-point loading: " + nplFile);
        }
    }
}
