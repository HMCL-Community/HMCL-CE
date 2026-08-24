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
package org.jackhuang.hmcl.game;

import org.jackhuang.hmcl.plugin.PluginHookDispatchException;
import org.jackhuang.hmcl.plugin.PluginHookPoint;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.Locale;
import java.util.Objects;

/// Exposes a redacted checked launch failure when a before-game-launch plugin Hook cannot complete.
@NotNullByDefault
public final class GameLaunchHookIOException extends IOException {
    /// Hook point whose coordination failed.
    private final PluginHookPoint point;

    /// Plugin responsible for the failed launch coordination.
    private final String pluginId;

    /// Stable Hook failure category.
    private final PluginHookDispatchException.Category category;

    /// Validated stable reason for a deliberate cancellation.
    private final @Nullable String cancellationReasonCode;

    /// Validated user-facing message for a deliberate cancellation.
    private final @Nullable String cancellationMessage;

    /// Converts one categorized Hook failure to the checked launcher boundary.
    ///
    /// @param failure categorized redacted Hook failure
    GameLaunchHookIOException(PluginHookDispatchException failure) {
        super(message(failure));
        this.point = failure.point();
        this.pluginId = failure.pluginId();
        this.category = failure.category();
        this.cancellationReasonCode = failure.cancellationReasonCode();
        this.cancellationMessage = failure.cancellationMessage();
    }

    /// Returns the Hook point whose coordination failed.
    ///
    /// @return Hook point
    public PluginHookPoint point() {
        return point;
    }

    /// Returns the plugin responsible for the failed launch.
    ///
    /// @return plugin ID
    public String pluginId() {
        return pluginId;
    }

    /// Returns the stable Hook failure category.
    ///
    /// @return failure category
    public PluginHookDispatchException.Category category() {
        return category;
    }

    /// Returns the validated stable reason for a deliberate cancellation.
    ///
    /// @return cancellation reason or `null` for other failure categories
    public @Nullable String cancellationReasonCode() {
        return cancellationReasonCode;
    }

    /// Returns the validated user-facing message for a deliberate cancellation.
    ///
    /// @return cancellation message or `null` for other failure categories
    public @Nullable String cancellationMessage() {
        return cancellationMessage;
    }

    /// Builds a user-visible message from redacted stable failure identity only.
    ///
    /// @param failure categorized Hook failure
    /// @return redacted launch failure message
    private static String message(PluginHookDispatchException failure) {
        Objects.requireNonNull(failure, "failure");
        return "Plugin Hook " + failure.point().getId() + " for " + failure.pluginId()
                + " stopped game launch: "
                + failure.category().name().toLowerCase(Locale.ROOT);
    }
}
