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

/// Launcher-owned lifecycle states for an external runtime Provider Host.
@NotNullByDefault
public enum RuntimeProviderState {
    /// The Provider package was found during plugin discovery.
    DISCOVERED,

    /// Concrete and virtual Provider dependencies were resolved.
    RESOLVED,

    /// The Host's Java bootstrap lifecycle was instantiated.
    BOOTSTRAP_LOADED,

    /// The Host registered its Provider implementation.
    REGISTERED,

    /// The registered descriptor matched the Host's advertised capability contract.
    NEGOTIATED,

    /// Provider-owned runtime resources were initialized.
    INITIALIZED,

    /// The initialized Provider passed its health check.
    HEALTHY,

    /// The Provider may accept dependent payload work.
    READY,

    /// New callbacks are blocked while dependent payloads stop in reverse order.
    STOPPING,

    /// Every payload and Provider-owned resource was released.
    STOPPED,

    /// A lifecycle transition failed and the Provider cannot accept work.
    FAILED
}
