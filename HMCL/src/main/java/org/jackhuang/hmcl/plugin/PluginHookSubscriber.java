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
import org.jetbrains.annotations.Unmodifiable;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/// Captures one eligible Hook endpoint and its dispatch-time authorization and dependency state.
@NotNullByDefault
final class PluginHookSubscriber implements AutoCloseable {
    /// Canonical plugin ID.
    private final String pluginId;

    /// Declared plugin dependencies copied at snapshot time.
    private final @Unmodifiable Set<String> dependencyIds;

    /// Effective exact-artifact permissions copied at snapshot time.
    private final @Unmodifiable Set<PluginPermission> permissions;

    /// Runtime-neutral callback endpoint.
    private final PluginHookEndpoint endpoint;

    /// Releases the container callback lease.
    private final Runnable releaseLease;

    /// Whether this snapshot entry already released its lease.
    private final AtomicBoolean closed = new AtomicBoolean();

    /// Creates one immutable subscriber snapshot entry.
    ///
    /// @param pluginId canonical plugin ID
    /// @param dependencyIds declared plugin dependency IDs
    /// @param permissions exact-artifact permissions effective at snapshot time
    /// @param endpoint runtime-neutral callback endpoint
    /// @param releaseLease callback lease release action
    PluginHookSubscriber(
            String pluginId,
            Set<String> dependencyIds,
            Set<PluginPermission> permissions,
            PluginHookEndpoint endpoint,
            Runnable releaseLease
    ) {
        this.pluginId = Objects.requireNonNull(pluginId, "pluginId");
        this.dependencyIds = Set.copyOf(dependencyIds);
        this.permissions = Set.copyOf(permissions);
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.releaseLease = Objects.requireNonNull(releaseLease, "releaseLease");
    }

    /// Returns the canonical plugin ID.
    ///
    /// @return plugin ID
    String pluginId() {
        return pluginId;
    }

    /// Returns the immutable declared dependency ID snapshot.
    ///
    /// @return dependency IDs
    @Unmodifiable Set<String> dependencyIds() {
        return dependencyIds;
    }

    /// Returns the immutable effective permission snapshot.
    ///
    /// @return exact-artifact permissions
    @Unmodifiable Set<PluginPermission> permissions() {
        return permissions;
    }

    /// Returns the runtime-neutral endpoint.
    ///
    /// @return callback endpoint
    PluginHookEndpoint endpoint() {
        return endpoint;
    }

    /// Releases the callback lease exactly once.
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            releaseLease.run();
        }
    }
}
