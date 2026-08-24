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

import java.util.Locale;
import java.util.Objects;

/// Reports one categorized Hook failure without placing callback data or secret values in its message.
@NotNullByDefault
public final class PluginHookDispatchException extends RuntimeException {
    /// Hook point whose dispatch failed.
    private final PluginHookPoint point;

    /// Plugin responsible for the failure.
    private final String pluginId;

    /// Stable failure category.
    private final Category category;

    /// Creates one redacted categorized Hook failure.
    ///
    /// @param point Hook point being dispatched
    /// @param pluginId failing plugin ID
    /// @param category stable failure category
    public PluginHookDispatchException(
            PluginHookPoint point,
            String pluginId,
            Category category
    ) {
        this(point, pluginId, category, null);
    }

    /// Creates one redacted categorized Hook failure with an internal cause.
    ///
    /// @param point Hook point being dispatched
    /// @param pluginId failing plugin ID
    /// @param category stable failure category
    /// @param cause internal failure cause
    public PluginHookDispatchException(
            PluginHookPoint point,
            String pluginId,
            Category category,
            @Nullable Throwable cause
    ) {
        super(message(point, pluginId, category), cause);
        this.point = Objects.requireNonNull(point, "point");
        this.pluginId = requirePluginId(pluginId);
        this.category = Objects.requireNonNull(category, "category");
    }

    /// Returns the Hook point whose dispatch failed.
    ///
    /// @return Hook point
    public PluginHookPoint point() {
        return point;
    }

    /// Returns the plugin responsible for the failure.
    ///
    /// @return plugin ID
    public String pluginId() {
        return pluginId;
    }

    /// Returns the stable failure category.
    ///
    /// @return failure category
    public Category category() {
        return category;
    }

    /// Builds the stable redacted exception message.
    ///
    /// @param point Hook point
    /// @param pluginId plugin ID
    /// @param category failure category
    /// @return redacted message
    private static String message(PluginHookPoint point, String pluginId, Category category) {
        return "Plugin Hook dispatch failed: point=" + Objects.requireNonNull(point, "point").getId()
                + ", plugin=" + requirePluginId(pluginId)
                + ", category=" + Objects.requireNonNull(category, "category").name().toLowerCase(Locale.ROOT);
    }

    /// Validates one plugin ID without incorporating callback data into diagnostics.
    ///
    /// @param pluginId plugin ID
    /// @return validated ID
    private static String requirePluginId(String pluginId) {
        Objects.requireNonNull(pluginId, "pluginId");
        if (pluginId.isBlank()) {
            throw new IllegalArgumentException("Plugin ID must not be blank");
        }
        return pluginId;
    }

    /// Categorizes stable dispatcher and validation failure modes.
    @NotNullByDefault
    public enum Category {
        /// Plugin endpoint threw an exception.
        EXCEPTION,

        /// Plugin endpoint exceeded its execution deadline.
        TIMEOUT,

        /// Plugin endpoint returned malformed or policy-invalid data.
        INVALID_RESULT,

        /// Plugin deliberately cancelled a cancellable Hook.
        CANCELLED,

        /// A declared subscriber has no callable endpoint.
        MISSING_ENDPOINT
    }
}
