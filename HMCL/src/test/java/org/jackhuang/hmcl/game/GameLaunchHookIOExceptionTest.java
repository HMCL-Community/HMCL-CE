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
package org.jackhuang.hmcl.game;

import org.jackhuang.hmcl.plugin.PluginHookDispatchException;
import org.jackhuang.hmcl.plugin.PluginHookPoint;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies checked launch-boundary translation of redacted plugin Hook failures.
@NotNullByDefault
public final class GameLaunchHookIOExceptionTest {
    /// Retains stable failure identity without leaking an internal cause message.
    @Test
    public void wrapsRedactedHookFailureForLaunchTasks() {
        IllegalStateException secretCause = new IllegalStateException("top-secret");
        PluginHookDispatchException failure = new PluginHookDispatchException(
                PluginHookPoint.BEFORE_GAME_LAUNCH,
                "dev.test.policy",
                PluginHookDispatchException.Category.CANCELLED,
                secretCause
        );

        GameLaunchHookIOException translated = new GameLaunchHookIOException(failure);

        assertSame(failure, translated.getCause());
        assertEquals("dev.test.policy", translated.pluginId());
        assertEquals(PluginHookDispatchException.Category.CANCELLED, translated.category());
        assertTrue(translated.getMessage().contains("dev.test.policy"));
        assertTrue(translated.getMessage().contains("cancelled"));
        assertFalse(translated.getMessage().contains("top-secret"));
    }
}
