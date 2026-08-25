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
import java.util.regex.Pattern;

/// Identifies one owner-scoped opaque JVM reference without exposing its owner or referenced object.
///
/// @param id registry-local numeric slot
/// @param generation slot generation that prevents ABA reuse
/// @param type stable language-neutral type descriptor
@NotNullByDefault
public record BridgeHandle(long id, long generation, String type) {
    /// Maximum encoded type descriptor length.
    public static final int MAX_TYPE_LENGTH = 128;

    /// Canonical syntax for a language-neutral type descriptor.
    private static final Pattern TYPE_PATTERN = Pattern.compile("[a-z][a-z0-9]*(?:[.-][a-z0-9]+)*");

    /// Validates the positive slot identity, generation, and canonical type descriptor.
    public BridgeHandle {
        if (id <= 0L) {
            throw new IllegalArgumentException("Bridge handle ID must be positive");
        }
        if (generation <= 0L) {
            throw new IllegalArgumentException("Bridge handle generation must be positive");
        }
        type = requireValidType(type);
    }

    /// Validates and returns one canonical language-neutral type descriptor.
    ///
    /// @param type candidate type descriptor
    /// @return validated descriptor
    static String requireValidType(String type) {
        Objects.requireNonNull(type, "type");
        if (type.length() > MAX_TYPE_LENGTH || !TYPE_PATTERN.matcher(type).matches()) {
            throw new IllegalArgumentException("Bridge handle type must be canonical");
        }
        return type;
    }
}
