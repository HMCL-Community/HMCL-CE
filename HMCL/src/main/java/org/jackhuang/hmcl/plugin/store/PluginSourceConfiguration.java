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
package org.jackhuang.hmcl.plugin.store;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.util.List;

/// Immutable ordered plugin-source configuration paired with its monotonic repository revision.
@NotNullByDefault
public final class PluginSourceConfiguration {
    /// Monotonic revision advanced after every successful source mutation.
    private final long revision;

    /// Exact ordered source configuration at this revision.
    private final @Unmodifiable List<PluginSource> sources;

    /// Creates an immutable revision-bearing source configuration.
    ///
    /// @param revision non-negative source configuration revision
    /// @param sources exact ordered source configuration
    public PluginSourceConfiguration(long revision, @Unmodifiable List<PluginSource> sources) {
        if (revision < 0) {
            throw new IllegalArgumentException("revision must not be negative");
        }
        this.revision = revision;
        this.sources = List.copyOf(sources);
    }

    /// Returns the monotonic source configuration revision.
    ///
    /// @return source configuration revision
    public long getRevision() {
        return revision;
    }

    /// Returns the exact ordered source configuration captured with this revision.
    ///
    /// @return immutable ordered source configuration
    public @Unmodifiable List<PluginSource> getSources() {
        return sources;
    }
}
