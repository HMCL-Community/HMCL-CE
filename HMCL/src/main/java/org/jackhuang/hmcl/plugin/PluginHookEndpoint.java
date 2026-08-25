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

import java.time.Duration;

/// Invokes one runtime-neutral Hook endpoint through the immutable public event contract.
@FunctionalInterface
@NotNullByDefault
public interface PluginHookEndpoint {
    /// Invokes the endpoint.
    ///
    /// @param event immutable Hook event
    /// @return endpoint result
    /// @throws Exception if the endpoint transport or plugin callback fails
    @Nullable PluginHookResult invoke(PluginHookEvent event) throws Exception;

    /// Invokes the endpoint with the dispatcher's exact callback deadline.
    ///
    /// Java endpoints retain the source-compatible single-argument callback. Runtime endpoints override this
    /// overload so their Provider transport can apply the same deadline as the launcher dispatcher.
    ///
    /// @param event immutable Hook event
    /// @param timeout positive per-subscriber callback deadline
    /// @return endpoint result, or `null` when a malformed endpoint violates the contract
    /// @throws Exception if the endpoint transport or plugin callback fails
    default @Nullable PluginHookResult invoke(PluginHookEvent event, Duration timeout) throws Exception {
        return invoke(event);
    }
}
