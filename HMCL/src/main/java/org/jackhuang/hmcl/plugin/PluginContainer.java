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

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// Holds one loaded plugin instance together with its runtime state and package location.
@NotNullByDefault
public final class PluginContainer {
    /// Loaded lifecycle implementation.
    private final Plugin plugin;

    /// Context passed to the lifecycle implementation.
    private final PluginContext context;

    /// Installed `.npl` package path.
    private Path nplFile;

    /// Observable lifecycle enablement state.
    private final BooleanProperty enabled = new SimpleBooleanProperty(false);

    /// Observable flag indicating that a Mixin-related state change needs a restart.
    private final BooleanProperty restartRequired = new SimpleBooleanProperty(false);

    /// Optional plugin-owned value retained for compatibility with existing integrations.
    private @Nullable Object userData;

    /// Number of dispatch snapshots currently retaining this container's callback endpoint.
    private int activeHookLeases;

    /// Whether unload requested that the dedicated class loader close after active callbacks finish.
    private boolean classLoaderCloseRequested;

    /// Whether the dedicated class loader close path has already been selected once.
    private boolean classLoaderClosed;

    /// Creates a plugin container.
    ///
    /// @param plugin lifecycle implementation
    /// @param context plugin context
    /// @param nplFile installed package path
    PluginContainer(Plugin plugin, PluginContext context, Path nplFile) {
        this.plugin = plugin;
        this.context = context;
        this.nplFile = nplFile;
    }

    /// Returns the lifecycle implementation.
    ///
    /// @return plugin instance
    Plugin getPlugin() {
        return plugin;
    }

    /// Returns the plugin context.
    ///
    /// @return plugin context
    PluginContext getContext() {
        return context;
    }

    /// Returns the installed package path.
    ///
    /// @return `.npl` path
    public Path getNplFile() {
        return nplFile;
    }

    /// Replaces the installed package path after an update is staged for restart.
    ///
    /// The current lifecycle classes continue using their already extracted package version.
    ///
    /// @param nplFile replacement package path
    void setNplFile(Path nplFile) {
        this.nplFile = nplFile;
    }

    /// Returns whether the lifecycle is currently enabled.
    ///
    /// @return enablement state
    public boolean isEnabled() {
        return enabled.get();
    }

    /// Updates the lifecycle enablement state.
    ///
    /// @param enabled new enablement state
    void setEnabled(boolean enabled) {
        this.enabled.set(enabled);
    }

    /// Returns the observable enablement property.
    ///
    /// @return enablement property
    BooleanProperty enabledProperty() {
        return enabled;
    }

    /// Returns whether a restart is required to apply the requested plugin state.
    ///
    /// @return restart-required state
    public boolean isRestartRequired() {
        return restartRequired.get();
    }

    /// Updates the restart-required state.
    ///
    /// @param restartRequired new restart-required state
    void setRestartRequired(boolean restartRequired) {
        this.restartRequired.set(restartRequired);
    }

    /// Returns the observable restart-required property.
    ///
    /// @return restart-required property
    BooleanProperty restartRequiredProperty() {
        return restartRequired;
    }

    /// Returns the authoritative package manifest.
    ///
    /// @return plugin manifest
    public PluginManifest getManifest() {
        return context.getManifest();
    }

    /// Returns the optional compatibility value owned by the plugin.
    ///
    /// @return stored value or `null`
    public @Nullable Object getUserData() {
        return userData;
    }

    /// Replaces the optional compatibility value owned by the plugin.
    ///
    /// @param userData new value or `null`
    public void setUserData(@Nullable Object userData) {
        this.userData = userData;
    }

    /// Acquires one callback lease that prevents the dedicated class loader from closing during Hook dispatch.
    ///
    /// @return idempotent lease release action
    Runnable acquireHookLease() {
        synchronized (this) {
            if (classLoaderCloseRequested || classLoaderClosed) {
                throw new IllegalStateException("Plugin class loader is already closing: " + getManifest().getId());
            }
            activeHookLeases++;
        }
        AtomicBoolean released = new AtomicBoolean();
        return () -> {
            if (released.compareAndSet(false, true)) {
                releaseHookLease();
            }
        };
    }

    /// Closes a dedicated plugin URL class loader when the plugin is unloadable.
    ///
    /// Startup Mixin plugins share HMCL's transforming loader and are intentionally left open for the process lifetime.
    ///
    /// @throws IOException if closing a dedicated loader fails
    void closeClassLoader() throws IOException {
        @Nullable URLClassLoader classLoader;
        synchronized (this) {
            if (classLoaderCloseRequested || classLoaderClosed) {
                return;
            }
            classLoaderCloseRequested = true;
            if (activeHookLeases > 0) {
                return;
            }
            classLoaderClosed = true;
            classLoader = dedicatedClassLoader();
        }
        if (classLoader != null) {
            classLoader.close();
        }
    }

    /// Releases one callback lease and performs a pending close after the final callback exits.
    private void releaseHookLease() {
        @Nullable URLClassLoader classLoader = null;
        synchronized (this) {
            if (activeHookLeases <= 0) {
                throw new IllegalStateException("Plugin Hook lease count underflow: " + getManifest().getId());
            }
            activeHookLeases--;
            if (activeHookLeases == 0 && classLoaderCloseRequested && !classLoaderClosed) {
                classLoaderClosed = true;
                classLoader = dedicatedClassLoader();
            }
        }
        if (classLoader != null) {
            try {
                classLoader.close();
            } catch (IOException exception) {
                LOG.warning("Failed to close plugin class loader after Hook callback: "
                        + getManifest().getId(), exception);
            }
        }
    }

    /// Returns the dedicated closeable loader, excluding HMCL's shared application loader.
    ///
    /// @return dedicated URL class loader, or `null` when the loader is shared or not closeable
    private @Nullable URLClassLoader dedicatedClassLoader() {
        ClassLoader pluginClassLoader = context.getClassLoader();
        return pluginClassLoader != PluginContainer.class.getClassLoader()
                && pluginClassLoader instanceof URLClassLoader urlClassLoader
                ? urlClassLoader
                : null;
    }
}
