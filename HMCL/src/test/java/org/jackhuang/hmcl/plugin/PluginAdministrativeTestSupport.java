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

import org.jackhuang.hmcl.plugin.loader.PluginClassLoader;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;

/// Supplies isolated class-loader frames for plugin administrative-boundary tests.
@NotNullByDefault
final class PluginAdministrativeTestSupport {
    /// Prevents construction of the stateless test support class.
    private PluginAdministrativeTestSupport() {
    }

    /// Wraps one administrative call in a proxy class defined by a child plugin-like class loader.
    ///
    /// @param attack administrative call to attempt
    /// @return proxy operation whose stack includes the child loader
    static AdministrativeAttack pluginCaller(AdministrativeAttack attack) {
        Class<?> @Unmodifiable [] interfaces = new Class<?>[]{AdministrativeAttack.class};
        URL @Unmodifiable [] classPath = new URL[0];
        return (AdministrativeAttack) Proxy.newProxyInstance(
                new PluginClassLoader(classPath, PluginAdministrativeTestSupport.class.getClassLoader()),
                interfaces,
                (proxy, method, arguments) -> {
                    attack.run();
                    return null;
                }
        );
    }

    /// Creates a loader that defines one selected test helper child-first.
    ///
    /// @param urls immutable class-path URLs searched for the selected class
    /// @param parent parent loader used for all other classes, or `null` for bootstrap-only delegation
    /// @param targetClassName binary name that must be defined by the returned loader
    /// @return closeable target-first URL class loader
    static URLClassLoader newTargetFirstUrlClassLoader(
            URL @Unmodifiable [] urls,
            @Nullable ClassLoader parent,
            String targetClassName
    ) {
        return new TargetFirstUrlClassLoader(urls, parent, targetClassName);
    }

    /// Loads one selected test helper from its own URL loader while delegating every other class to the parent.
    @NotNullByDefault
    private static final class TargetFirstUrlClassLoader extends URLClassLoader {
        /// Binary name that must be defined by this loader instead of its parent.
        private final String targetClassName;

        /// Creates a loader that defines exactly one selected class from the supplied URLs.
        ///
        /// @param urls immutable class-path URLs searched for the selected class
        /// @param parent parent loader used for all other classes, or `null` for bootstrap-only delegation
        /// @param targetClassName binary name that must be defined by this loader
        private TargetFirstUrlClassLoader(
                URL @Unmodifiable [] urls,
                @Nullable ClassLoader parent,
                String targetClassName
        ) {
            super(urls, parent);
            this.targetClassName = targetClassName;
        }

        /// Loads the selected helper child-first and preserves normal parent-first loading for every other class.
        ///
        /// @param name binary class name to load
        /// @param resolve whether to resolve the returned class
        /// @return class loaded by this loader or its parent
        /// @throws ClassNotFoundException if neither the selected URL path nor the parent can define the class
        @Override
        protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!targetClassName.equals(name)) {
                return super.loadClass(name, resolve);
            }
            @Nullable Class<?> loaded = findLoadedClass(name);
            Class<?> result = loaded == null ? findClass(name) : loaded;
            if (resolve) {
                resolveClass(result);
            }
            return result;
        }
    }

    /// Checked operation used to invoke manager APIs through a generated plugin-class-loader frame.
    @FunctionalInterface
    @NotNullByDefault
    public interface AdministrativeAttack {
        /// Attempts one launcher-administrative operation.
        ///
        /// @throws Exception if the attempted API reports a checked failure
        void run() throws Exception;
    }
}
