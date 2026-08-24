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
import org.jetbrains.annotations.Unmodifiable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/// Represents one closed, transactional result returned by a plugin Hook endpoint.
@NotNullByDefault
public final class PluginHookResult {
    /// Stable cancellation codes use lower-case kebab syntax.
    private static final Pattern REASON_CODE = Pattern.compile("[a-z][a-z0-9]*(?:-[a-z0-9]+)*");

    /// Shared unchanged result with no replacement or protected values.
    private static final PluginHookResult UNCHANGED = new PluginHookResult(
            Action.UNCHANGED, null, Map.of(), null, null);

    /// Selected result action.
    private final Action action;

    /// Complete replacement data for a replace result.
    private final @Nullable PluginDataObject data;

    /// Protected secret updates excluded from ordinary event data.
    private final @Unmodifiable Map<String, String> protectedSecrets;

    /// Stable reason code for a cancellation result.
    private final @Nullable String reasonCode;

    /// User-facing message for a cancellation result.
    private final @Nullable String message;

    /// Creates and validates one action-specific result state.
    ///
    /// @param action selected action
    /// @param data complete replacement data
    /// @param protectedSecrets protected secret updates
    /// @param reasonCode stable cancellation reason
    /// @param message user-facing cancellation message
    private PluginHookResult(
            Action action,
            @Nullable PluginDataObject data,
            Map<String, String> protectedSecrets,
            @Nullable String reasonCode,
            @Nullable String message
    ) {
        this.action = Objects.requireNonNull(action, "action");
        this.protectedSecrets = copyProtectedSecrets(protectedSecrets);
        switch (action) {
            case UNCHANGED -> {
                if (data != null || !this.protectedSecrets.isEmpty() || reasonCode != null || message != null) {
                    throw new IllegalArgumentException("Unchanged Hook result cannot carry data");
                }
            }
            case REPLACE -> {
                Objects.requireNonNull(data, "Replacement data");
                if (reasonCode != null || message != null) {
                    throw new IllegalArgumentException("Replacement Hook result cannot carry cancellation data");
                }
            }
            case CANCEL -> {
                if (data != null || !this.protectedSecrets.isEmpty()) {
                    throw new IllegalArgumentException("Cancelled Hook result cannot carry replacement data");
                }
                validateCancellation(reasonCode, message);
            }
        }
        this.data = data;
        this.reasonCode = reasonCode;
        this.message = message;
    }

    /// Returns a result that preserves the current event data.
    ///
    /// @return shared unchanged result
    public static PluginHookResult unchanged() {
        return UNCHANGED;
    }

    /// Returns a complete ordinary-data replacement with no protected secret updates.
    ///
    /// @param data complete replacement data
    /// @return replacement result
    public static PluginHookResult replace(PluginDataObject data) {
        return replace(data, Map.of());
    }

    /// Returns a complete ordinary-data replacement with out-of-band protected secret updates.
    ///
    /// @param data complete replacement data
    /// @param protectedSecrets protected secret values keyed by slot
    /// @return replacement result
    public static PluginHookResult replace(PluginDataObject data, Map<String, String> protectedSecrets) {
        return new PluginHookResult(Action.REPLACE, data, protectedSecrets, null, null);
    }

    /// Returns a deliberate cancellation result.
    ///
    /// @param reasonCode stable lower-case kebab reason
    /// @param message non-blank user-facing message
    /// @return cancellation result
    public static PluginHookResult cancel(String reasonCode, String message) {
        return new PluginHookResult(Action.CANCEL, null, Map.of(), reasonCode, message);
    }

    /// Returns the selected result action.
    ///
    /// @return result action
    public Action action() {
        return action;
    }

    /// Returns complete replacement data when this is a replace result.
    ///
    /// @return replacement data or `null`
    public @Nullable PluginDataObject data() {
        return data;
    }

    /// Returns immutable out-of-band secret updates.
    ///
    /// @return protected secret updates
    public @Unmodifiable Map<String, String> protectedSecrets() {
        return protectedSecrets;
    }

    /// Returns the stable cancellation code when this is a cancel result.
    ///
    /// @return reason code or `null`
    public @Nullable String reasonCode() {
        return reasonCode;
    }

    /// Returns the user-facing cancellation message when this is a cancel result.
    ///
    /// @return cancellation message or `null`
    public @Nullable String message() {
        return message;
    }

    /// Returns a diagnostic representation that never includes protected secret values or cancellation messages.
    ///
    /// @return redacted result representation
    @Override
    public String toString() {
        return "PluginHookResult[action=" + action
                + ", data=" + data
                + ", protectedSecretSlots=" + protectedSecrets.keySet()
                + ", reasonCode=" + reasonCode
                + ", cancellationMessagePresent=" + (message != null) + "]";
    }

    /// Copies protected values while rejecting null keys or values.
    ///
    /// @param source protected value source
    /// @return immutable copied values
    private static @Unmodifiable Map<String, String> copyProtectedSecrets(Map<String, String> source) {
        Objects.requireNonNull(source, "protectedSecrets");
        Map<String, String> copy = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : source.entrySet()) {
            copy.put(
                    Objects.requireNonNull(entry.getKey(), "Protected secret slot"),
                    Objects.requireNonNull(entry.getValue(), "Protected secret value")
            );
        }
        return Collections.unmodifiableMap(copy);
    }

    /// Validates cancellation-specific fields without accepting replacement state.
    ///
    /// @param reasonCode stable cancellation reason
    /// @param message user-facing cancellation message
    private static void validateCancellation(@Nullable String reasonCode, @Nullable String message) {
        if (reasonCode == null || !REASON_CODE.matcher(reasonCode).matches()) {
            throw new IllegalArgumentException("Plugin Hook cancellation reason must be lower-case kebab text");
        }
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("Plugin Hook cancellation message must not be blank");
        }
    }

    /// Selects the only valid state transitions a Hook endpoint may request.
    @NotNullByDefault
    public enum Action {
        /// Preserve the current event data.
        UNCHANGED,

        /// Replace the complete mutable event data.
        REPLACE,

        /// Cancel a Hook point whose policy permits cancellation.
        CANCEL
    }
}
