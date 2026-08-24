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
import java.util.regex.Pattern;

/// Reports one categorized Hook failure without placing callback data or secret values in its message.
@NotNullByDefault
public final class PluginHookDispatchException extends RuntimeException {
    /// Stable cancellation codes use lower-case kebab syntax.
    private static final Pattern CANCELLATION_REASON_CODE =
            Pattern.compile("[a-z][a-z0-9]*(?:-[a-z0-9]+)*");

    /// Hook point whose dispatch failed.
    private final PluginHookPoint point;

    /// Plugin responsible for the failure.
    private final String pluginId;

    /// Stable failure category.
    private final Category category;

    /// Validated stable reason for a deliberate cancellation.
    private final @Nullable String cancellationReasonCode;

    /// Validated user-facing message for a deliberate cancellation.
    private final @Nullable String cancellationMessage;

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
        this(point, pluginId, category, null, null, null);
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
        this(point, pluginId, category, cause, null, null);
    }

    /// Creates one validated deliberate cancellation without placing its user message in generic diagnostics.
    ///
    /// @param point Hook point being cancelled
    /// @param pluginId cancelling plugin ID
    /// @param reasonCode stable lower-case kebab reason
    /// @param userMessage validated user-facing cancellation message
    /// @return categorized cancellation failure
    public static PluginHookDispatchException cancelled(
            PluginHookPoint point,
            String pluginId,
            String reasonCode,
            String userMessage
    ) {
        return new PluginHookDispatchException(
                point, pluginId, Category.CANCELLED, null, reasonCode, userMessage);
    }

    /// Creates one categorized failure and enforces cancellation-field invariants.
    ///
    /// @param point Hook point being dispatched
    /// @param pluginId failing plugin ID
    /// @param category stable failure category
    /// @param cause internal failure cause
    /// @param cancellationReasonCode validated cancellation reason
    /// @param cancellationMessage validated user-facing cancellation message
    private PluginHookDispatchException(
            PluginHookPoint point,
            String pluginId,
            Category category,
            @Nullable Throwable cause,
            @Nullable String cancellationReasonCode,
            @Nullable String cancellationMessage
    ) {
        super(message(point, pluginId, category), cause);
        this.point = Objects.requireNonNull(point, "point");
        this.pluginId = requirePluginId(pluginId);
        this.category = Objects.requireNonNull(category, "category");
        if (category == Category.CANCELLED) {
            this.cancellationReasonCode = requireCancellationReasonCode(cancellationReasonCode);
            this.cancellationMessage = requireCancellationMessage(cancellationMessage);
        } else {
            if (cancellationReasonCode != null || cancellationMessage != null) {
                throw new IllegalArgumentException("Only cancelled Hook failures may carry cancellation fields");
            }
            this.cancellationReasonCode = null;
            this.cancellationMessage = null;
        }
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

    /// Validates one stable cancellation reason.
    ///
    /// @param reasonCode candidate reason
    /// @return validated reason
    private static String requireCancellationReasonCode(@Nullable String reasonCode) {
        if (reasonCode == null || !CANCELLATION_REASON_CODE.matcher(reasonCode).matches()) {
            throw new IllegalArgumentException("Plugin Hook cancellation reason must be lower-case kebab text");
        }
        return reasonCode;
    }

    /// Validates one non-blank cancellation message.
    ///
    /// @param userMessage candidate user-facing message
    /// @return validated message
    private static String requireCancellationMessage(@Nullable String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            throw new IllegalArgumentException("Plugin Hook cancellation message must not be blank");
        }
        return userMessage;
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
