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
package org.jackhuang.hmcl.plugin.loader;

import org.jackhuang.hmcl.plugin.internal.VerifiedPluginPackage;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;

/// Identifies classes loaded from an ordinary Java or Kotlin plugin package.
///
/// Production instances deliberately have no mutable file or JAR URLs. Class definitions and resource URLs are built
/// directly from byte arrays captured and verified by [VerifiedPluginPackage] during the same lookup operation.
@NotNullByDefault
public final class PluginClassLoader extends URLClassLoader {
    /// Binary-name prefixes that form an explicit shared third-party API boundary.
    private static final @Unmodifiable List<String> SHARED_API_PREFIXES = List.of(
            "javafx.",
            "org.jetbrains.annotations.",
            "org.objectweb.asm.",
            "org.spongepowered.asm."
    );

    /// Launcher-owned namespaces whose classes fall back to the host loader when absent from the verified package.
    ///
    /// Covers the launcher itself and the third-party libraries bundled inside the launcher artifact, so plugins may
    /// use launcher UI and service classes without a per-class whitelist entry.
    private static final @Unmodifiable List<String> HOST_FALLBACK_PREFIXES = List.of(
            "org.jackhuang.hmcl.",
            "com.jfoenix.",
            "com.google.gson.",
            "org.jsoup.",
            "org.glavo.",
            "org.tomlj.",
            "org.hildan.",
            "org.girod.",
            "kala.compress.",
            "net.jpountz.",
            "org.tukaani.",
            "fi.iki.elonen."
    );

    /// Exact launcher API classes that must retain host type identity, including their nested types.
    private static final @Unmodifiable Set<String> SHARED_HMCL_API_CLASSES = Set.of(
            "org.jackhuang.hmcl.plugin.Plugin",
            "org.jackhuang.hmcl.plugin.PluginArtifactIdentity",
            "org.jackhuang.hmcl.plugin.PluginContext",
            "org.jackhuang.hmcl.plugin.PluginDependency",
            "org.jackhuang.hmcl.plugin.PluginManifest",
            "org.jackhuang.hmcl.plugin.PluginPermission",
            "org.jackhuang.hmcl.plugin.PluginPermissionException",
            "org.jackhuang.hmcl.plugin.PluginVersion",
            "org.jackhuang.hmcl.plugin.PluginVersionConstraint",
            "org.jackhuang.hmcl.ui.Controllers",
            "org.jackhuang.hmcl.ui.decorator.DecoratorPage"
    );

    /// Exact package inventory used by production plugin loaders, or `null` for detached guard tests.
    private final @Nullable VerifiedPluginPackage pluginPackage;

    /// Creates a detached plugin loader used by launcher-administrative guard tests.
    ///
    /// @param urls detached test class path
    /// @param parent HMCL application class loader
    public PluginClassLoader(URL @Unmodifiable [] urls, @Nullable ClassLoader parent) {
        super(urls, parent);
        pluginPackage = null;
    }

    /// Creates a production plugin loader with no URL-backed class path.
    ///
    /// @param parent HMCL application class loader
    /// @param pluginPackage exact verified package inventory
    public PluginClassLoader(@Nullable ClassLoader parent, VerifiedPluginPackage pluginPackage) {
        super(new URL[0], parent);
        this.pluginPackage = pluginPackage;
    }

    /// Defines the lifecycle entry point from this loader's own verified package without parent delegation.
    ///
    /// Using [findClass] prevents an HMCL or test class-path class with the same binary name from executing under a
    /// different artifact's permission grants.
    ///
    /// @param binaryName manifest entry-point binary name
    /// @return class defined from captured package bytes
    /// @throws ClassNotFoundException if the verified package does not define the class
    public synchronized Class<?> loadPluginClass(String binaryName) throws ClassNotFoundException {
        if (pluginPackage != null
                && (isSharedApiClass(binaryName) || findPlatformClass(binaryName) != null)) {
            throw new ClassNotFoundException("Plugin entry point uses a protected shared or platform class: "
                    + binaryName);
        }
        @Nullable Class<?> loaded = findLoadedClass(binaryName);
        return loaded == null ? findClass(binaryName) : loaded;
    }

    /// Loads package-owned helper classes from the verified artifact and shared API classes only from HMCL.
    ///
    /// Launcher-namespace classes absent from the verified package fall back to the host loader, so plugins may use
    /// launcher UI and service classes without a per-class whitelist entry. Production loaders never fall back to an
    /// unrelated parent-classpath class for an ordinary plugin namespace. Detached guard-test loaders retain standard
    /// [URLClassLoader] delegation.
    ///
    /// @param binaryName requested binary class name
    /// @param resolve whether to resolve the returned class
    /// @return class from the only authorized source for its namespace
    /// @throws ClassNotFoundException if that source does not define the class
    @Override
    protected synchronized Class<?> loadClass(String binaryName, boolean resolve) throws ClassNotFoundException {
        if (pluginPackage == null) {
            return super.loadClass(binaryName, resolve);
        }

        @Nullable Class<?> loaded = findLoadedClass(binaryName);
        Class<?> result;
        if (loaded != null) {
            result = loaded;
        } else if (isSharedApiClass(binaryName)) {
            result = loadParentClass(binaryName);
        } else {
            @Nullable Class<?> platformClass = findPlatformClass(binaryName);
            result = platformClass == null ? findPackageClassWithHostFallback(binaryName) : platformClass;
        }
        if (resolve) {
            resolveClass(result);
        }
        return result;
    }

    /// Returns a verified package resource without consulting the mutable parent class path.
    ///
    /// @param name resource name
    /// @return captured package resource or `null`
    @Override
    public @Nullable URL getResource(String name) {
        return pluginPackage == null ? super.getResource(name) : findResource(name);
    }

    /// Returns verified package resources without merging unowned parent-classpath entries.
    ///
    /// @param name resource name
    /// @return captured package resource enumeration
    /// @throws IOException if verified resource capture fails
    @Override
    public Enumeration<URL> getResources(String name) throws IOException {
        return pluginPackage == null ? super.getResources(name) : findResources(name);
    }

    /// Defines a class from the same byte array whose loose file or owning nested JAR passed digest verification.
    ///
    /// @param binaryName requested binary class name
    /// @return package-owned class definition
    /// @throws ClassNotFoundException if the class is absent or its inventoried source changed
    @Override
    protected Class<?> findClass(String binaryName) throws ClassNotFoundException {
        if (pluginPackage == null) {
            return super.findClass(binaryName);
        }
        String resourceName = binaryName.replace('.', '/') + ".class";
        try {
            byte @Nullable @Unmodifiable [] bytecode = pluginPackage.readResourceBytes(resourceName);
            if (bytecode == null) {
                throw new ClassNotFoundException("Plugin package does not define " + binaryName);
            }
            return defineClass(binaryName, bytecode, 0, bytecode.length);
        } catch (IOException exception) {
            throw new ClassNotFoundException("Verified plugin package changed while loading " + binaryName, exception);
        }
    }

    /// Finds one resource as an immutable in-memory URL captured during this lookup.
    ///
    /// @param name resource name
    /// @return captured resource URL or `null`
    @Override
    public @Nullable URL findResource(String name) {
        if (pluginPackage == null) {
            return super.findResource(name);
        }
        try {
            return pluginPackage.snapshotResourceUrl(name);
        } catch (IOException exception) {
            return null;
        }
    }

    /// Finds resources as immutable in-memory URLs captured during this lookup.
    ///
    /// @param name resource name
    /// @return captured resource enumeration in verified class-path order
    /// @throws IOException if an inventoried source changed during capture
    @Override
    public Enumeration<URL> findResources(String name) throws IOException {
        if (pluginPackage == null) {
            return super.findResources(name);
        }
        return Collections.enumeration(pluginPackage.snapshotResourceUrls(name));
    }

    /// Returns whether one binary name belongs to an explicit launcher or plugin API namespace.
    ///
    /// @param binaryName requested class name
    /// @return whether the class must come only from the parent loader
    private static boolean isSharedApiClass(String binaryName) {
        if (SHARED_API_PREFIXES.stream().anyMatch(binaryName::startsWith)) {
            return true;
        }
        return SHARED_HMCL_API_CLASSES.stream().anyMatch(
                sharedClass -> binaryName.equals(sharedClass) || binaryName.startsWith(sharedClass + "$"));
    }

    /// Loads one class only when the bootstrap or platform loader owns it through a named runtime module.
    ///
    /// Querying the platform loader by exact binary name avoids broad namespace rules such as `javax.*` and
    /// `com.sun.*`, which also contain third-party libraries that must remain package-owned. The named-module check
    /// prevents an ucepected unnamed platform class path from becoming an implicit plugin dependency.
    ///
    /// @param binaryName requested binary class name
    /// @return exact bootstrap or platform class, or `null` when the verified package must own the name
    private static @Nullable Class<?> findPlatformClass(String binaryName) {
        try {
            Class<?> platformClass = Class.forName(binaryName, false, ClassLoader.getPlatformClassLoader());
            return platformClass.getModule().isNamed() ? platformClass : null;
        } catch (ClassNotFoundException exception) {
            return null;
        }
    }

    /// Loads a protected shared class without allowing [URLClassLoader] to fall back into the plugin package.
    ///
    /// @param binaryName protected binary class name
    /// @return parent or bootstrap class
    /// @throws ClassNotFoundException if the shared runtime does not define the class
    private Class<?> loadParentClass(String binaryName) throws ClassNotFoundException {
        @Nullable ClassLoader parent = getParent();
        return Class.forName(binaryName, false, parent);
    }

    /// Loads a class from the verified package, falling back to the host loader for launcher-owned namespaces.
    ///
    /// Package-owned bytes always win, so a plugin cannot be silently overridden by a same-named host class. The
    /// launcher-namespace fallback keeps host type identity for launcher UI and service classes that plugins use
    /// directly, without maintaining a per-class whitelist. Other namespaces stay package-owned and never fall back
    /// to unrelated parent-classpath classes.
    ///
    /// @param binaryName requested binary class name
    /// @return package-owned class, or host launcher class for an absent launcher-namespace name
    /// @throws ClassNotFoundException if neither authorized source defines the class
    private Class<?> findPackageClassWithHostFallback(String binaryName) throws ClassNotFoundException {
        try {
            return findClass(binaryName);
        } catch (ClassNotFoundException packageMiss) {
            if (HOST_FALLBACK_PREFIXES.stream().noneMatch(binaryName::startsWith)) {
                throw packageMiss;
            }
            try {
                return loadParentClass(binaryName);
            } catch (ClassNotFoundException hostMiss) {
                packageMiss.addSuppressed(hostMiss);
                throw packageMiss;
            }
        }
    }
}
