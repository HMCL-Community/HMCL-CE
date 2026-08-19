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

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/// Package-owned lifecycle fixture that records callbacks through process-global diagnostic properties.
@NotNullByDefault
public final class LifecycleProbePlugin implements Plugin {
    /// Property set if the plugin constructor executes.
    public static final String CONSTRUCTED_PROPERTY = "hmcl.test.plugin.probe.constructed";

    /// Property set if `onLoad` executes.
    public static final String LOADED_PROPERTY = "hmcl.test.plugin.probe.loaded";

    /// Property set if `onEnable` executes.
    public static final String ENABLED_PROPERTY = "hmcl.test.plugin.probe.enabled";

    /// Property set if `onDisable` executes.
    public static final String DISABLED_PROPERTY = "hmcl.test.plugin.probe.disabled";

    /// Property set if `onUnload` executes.
    public static final String UNLOADED_PROPERTY = "hmcl.test.plugin.probe.unloaded";

    /// Property that makes `onDisable` throw an [AssertionError] after recording the callback.
    public static final String THROW_DISABLE_PROPERTY = "hmcl.test.plugin.probe.throw-disable";

    /// Property that makes `onUnload` throw an [AssertionError] after recording the callback.
    public static final String THROW_UNLOAD_PROPERTY = "hmcl.test.plugin.probe.throw-unload";

    /// Manifest received during `onLoad`, or `null` before registration.
    private @Nullable PluginManifest manifest;

    /// Records construction without relying on class-loader-shared static fields.
    public LifecycleProbePlugin() {
        System.setProperty(CONSTRUCTED_PROPERTY, "true");
    }

    /// Records lifecycle registration and stores the manifest.
    ///
    /// @param context plugin runtime context
    @Override
    public void onLoad(PluginContext context) {
        manifest = context.getManifest();
        System.setProperty(LOADED_PROPERTY, "true");
    }

    /// Records lifecycle activation.
    @Override
    public void onEnable() {
        System.setProperty(ENABLED_PROPERTY, "true");
    }

    /// Records ordinary lifecycle deactivation.
    @Override
    public void onDisable() {
        System.setProperty(DISABLED_PROPERTY, "true");
        if (Boolean.getBoolean(THROW_DISABLE_PROPERTY)) {
            throw new AssertionError("Requested onDisable failure");
        }
    }

    /// Records ordinary lifecycle unloading.
    @Override
    public void onUnload() {
        System.setProperty(UNLOADED_PROPERTY, "true");
        if (Boolean.getBoolean(THROW_UNLOAD_PROPERTY)) {
            throw new AssertionError("Requested onUnload failure");
        }
    }

    /// Returns the manifest received during registration.
    ///
    /// @return plugin manifest
    @Override
    public PluginManifest getManifest() {
        return Objects.requireNonNull(manifest);
    }
}
