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
package org.jackhuang.hmcl.plugin.runtime;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.util.Locale;
import java.util.Set;

/// Canonical runtime identifiers shared by plugin manifests and the official runtime repository.
///
/// The launcher itself only ships the Java runtime. Every other runtime (dotnet, python,
/// javascript, native) is an ordinary plugin installed on demand through the plugin store.
@NotNullByDefault
public final class PluginRuntimeTypes {
    /// Built-in JVM runtime loaded directly by the launcher; never provided by an external plugin.
    public static final String JAVA = "java";

    /// Runtime identifiers reserved for the official runtime repository.
    public static final @Unmodifiable Set<String> RESERVED = Set.of(JAVA, "dotnet", "python", "javascript", "native");

    private PluginRuntimeTypes() {
    }

    /// Returns whether the identifier is structurally valid: lower-case letters, digits, and hyphens.
    public static boolean isValid(String value) {
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty() || normalized.length() > 32) {
            return false;
        }
        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            boolean accepted = (c >= 97 && c <= 122) || (c >= 48 && c <= 57) || c == 45;
            if (!accepted) {
                return false;
            }
        }
        return true;
    }

    /// Validates and canonicalizes a runtime identifier.
    ///
    /// @throws IllegalArgumentException when the identifier is malformed
    public static String requireValid(String value) {
        if (!isValid(value)) {
            throw new IllegalArgumentException("Invalid plugin runtime identifier: " + value);
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
