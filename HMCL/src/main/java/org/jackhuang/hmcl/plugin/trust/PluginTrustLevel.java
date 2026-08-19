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
package org.jackhuang.hmcl.plugin.trust;

import org.jetbrains.annotations.NotNullByDefault;

/// Locally derived trust level for one plugin-store document.
@NotNullByDefault
public enum PluginTrustLevel {
    /// Registry content signed by the official repository role.
    OFFICIAL(3, true, false),

    /// Community content signed by a currently valid constrained developer certificate.
    CERTIFIED(2, true, false),

    /// Unsigned community content that requires explicit source confirmation.
    COMMUNITY(1, true, true),

    /// Content that declared signing material but failed verification.
    REJECTED(0, false, true);

    /// Selection priority used when multiple repositories publish the same plugin ID.
    private final int priority;

    /// Whether installation may proceed to normal permission review.
    private final boolean installable;

    /// Whether installation must include an untrusted-source warning.
    private final boolean sourceWarning;

    /// Creates one immutable trust policy value.
    PluginTrustLevel(int priority, boolean installable, boolean sourceWarning) {
        this.priority = priority;
        this.installable = installable;
        this.sourceWarning = sourceWarning;
    }

    /// Returns conflict-selection priority.
    public int getPriority() {
        return priority;
    }

    /// Returns whether normal installation may proceed.
    public boolean isInstallable() {
        return installable;
    }

    /// Returns whether a source warning is required.
    public boolean requiresSourceWarning() {
        return sourceWarning;
    }
}
