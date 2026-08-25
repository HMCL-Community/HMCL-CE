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
package org.jackhuang.hmcl.plugin.bridge;

import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;

/// Carries one stable, redacted Bridge failure category across Runtime implementations.
///
/// Bridge errors deliberately retain neither a JVM cause nor caller-controlled diagnostic text.
@NotNullByDefault
public final class BridgeError extends RuntimeException {
    /// Portable failure category.
    private final Category category;

    /// Creates one redacted error for a stable category.
    ///
    /// @param category portable failure category
    private BridgeError(Category category) {
        super(Objects.requireNonNull(category, "category").message(), null, false, false);
        this.category = category;
    }

    /// Creates one redacted error for a stable category.
    ///
    /// @param category portable failure category
    /// @return redacted Bridge error
    public static BridgeError of(Category category) {
        return new BridgeError(category);
    }

    /// Returns the portable failure category.
    ///
    /// @return failure category
    public Category category() {
        return category;
    }

    /// Returns the stable lower-case wire code.
    ///
    /// @return language-neutral error code
    public String code() {
        return category.code();
    }

    /// Compares portable category and wire code rather than exception identity.
    ///
    /// @param other candidate error
    /// @return whether both errors carry the same stable Bridge failure
    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof BridgeError that
                && category == that.category
                && code().equals(that.code());
    }

    /// Returns the portable category and wire-code hash.
    ///
    /// @return structural error hash
    @Override
    public int hashCode() {
        return Objects.hash(category, code());
    }

    /// Enumerates errors that every embedded and isolated Runtime transport must preserve.
    @NotNullByDefault
    public enum Category {
        /// A Bridge value, operation, owner, or argument is malformed.
        INVALID_ARGUMENT("invalid-argument", "Bridge argument is invalid"),

        /// A callback returned no valid Bridge value.
        INVALID_RESULT("invalid-result", "Bridge callback returned an invalid result"),

        /// The presented authority does not own the requested resource.
        PERMISSION_DENIED("permission-denied", "Bridge access is denied"),

        /// The handle slot does not exist at the presented generation.
        STALE_HANDLE("stale-handle", "Bridge handle is stale or revoked"),

        /// The expected and actual opaque handle types differ.
        TYPE_MISMATCH("type-mismatch", "Bridge handle type does not match"),

        /// Dispatch was explicitly cancelled before its result was committed.
        CANCELLED("cancelled", "Bridge callback was cancelled"),

        /// Callback execution failed without exposing the originating exception.
        CALLBACK_FAILED("callback-failed", "Bridge callback failed"),

        /// Dispatch capacity or a required service is currently unavailable.
        UNAVAILABLE("unavailable", "Bridge service is unavailable"),

        /// Internal Bridge cleanup or bookkeeping failed.
        INTERNAL("internal", "Bridge internal operation failed");

        /// Stable wire code.
        private final String code;

        /// Redacted diagnostic message.
        private final String message;

        /// Creates one stable portable category.
        ///
        /// @param code stable wire code
        /// @param message redacted diagnostic message
        Category(String code, String message) {
            this.code = code;
            this.message = message;
        }

        /// Returns the stable lower-case wire code.
        ///
        /// @return language-neutral error code
        public String code() {
            return code;
        }

        /// Returns the fixed redacted diagnostic message.
        ///
        /// @return safe message
        String message() {
            return message;
        }
    }
}
