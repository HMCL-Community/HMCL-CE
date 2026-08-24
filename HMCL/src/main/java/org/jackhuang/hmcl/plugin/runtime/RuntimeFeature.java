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

/// Names one launcher-to-runtime capability negotiated between a plugin and a runtime provider.
@NotNullByDefault
public enum RuntimeFeature {
    /// Supports the base launcher-to-runtime bridge contract.
    BRIDGE("bridge"),

    /// Supports launcher lifecycle hook delivery.
    HOOKS("hooks"),

    /// Supports declarative launcher method patches.
    PATCHES("patches"),

    /// Supports direct JVM access from the runtime boundary.
    RAW_JVM("raw-jvm"),

    /// Supports provider-managed native code.
    NATIVE("native");

    /// Stable serialized identifier used in `plugin.json`.
    private final String id;

    /// Creates one runtime feature with its serialized identifier.
    ///
    /// @param id stable serialized identifier
    RuntimeFeature(String id) {
        this.id = id;
    }

    /// Returns the stable serialized identifier.
    ///
    /// @return serialized identifier
    public String getId() {
        return id;
    }

    /// Returns the stable serialized identifier for Gson's lower-case enum adapter.
    ///
    /// @return serialized identifier
    @Override
    public String toString() {
        return id;
    }
}
