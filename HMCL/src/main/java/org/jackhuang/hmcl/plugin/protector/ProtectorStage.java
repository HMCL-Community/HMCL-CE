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
package org.jackhuang.hmcl.plugin.protector;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/// Startup milestones reported by the protected launcher child before ordinary operation begins.
@NotNullByDefault
public enum ProtectorStage {
    /// The child JVM has started and established the Protector session.
    JVM_STARTED("jvm-started"),

    /// Launcher Core initialization has completed.
    CORE_READY("core-ready"),

    /// Runtime Provider plugins are being initialized.
    RUNTIME_PROVIDERS_LOADING("runtime-providers-loading"),

    /// Ordinary third-party plugins are being initialized.
    ORDINARY_PLUGINS_LOADING("ordinary-plugins-loading"),

    /// The launcher UI is ready and startup supervision has completed.
    UI_READY("ui-ready");

    /// Stable control-protocol spelling.
    private final String wireName;

    /// Creates one stage with its stable wire spelling.
    ///
    /// @param wireName stable wire spelling
    ProtectorStage(String wireName) {
        this.wireName = wireName;
    }

    /// Returns the stable wire spelling.
    ///
    /// @return stable wire spelling
    public String wireName() {
        return wireName;
    }

    /// Resolves an exact wire spelling without accepting future values implicitly.
    ///
    /// @param wireName candidate wire spelling
    /// @return matching stage, or `null` when unknown
    static @Nullable ProtectorStage fromWireName(String wireName) {
        for (ProtectorStage stage : values()) {
            if (stage.wireName.equals(wireName)) {
                return stage;
            }
        }
        return null;
    }
}
