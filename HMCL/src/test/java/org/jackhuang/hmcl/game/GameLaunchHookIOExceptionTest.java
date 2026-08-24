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
import org.jackhuang.hmcl.util.StringUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies checked launch-boundary translation of redacted plugin Hook failures.
@NotNullByDefault
public final class GameLaunchHookIOExceptionTest {
    /// Retains stable failure identity without retaining an internal cause chain.
    @Test
    public void wrapsRedactedHookFailureForLaunchTasks() {
        IllegalStateException secretCause = new IllegalStateException("top-secret");
        PluginHookDispatchException failure = new PluginHookDispatchException(
                PluginHookPoint.BEFORE_GAME_LAUNCH,
                "dev.test.policy",
                PluginHookDispatchException.Category.EXCEPTION,
                secretCause
        );

        GameLaunchHookIOException translated = new GameLaunchHookIOException(failure);

        assertNull(translated.getCause());
        assertEquals(PluginHookPoint.BEFORE_GAME_LAUNCH, translated.point());
        assertEquals("dev.test.policy", translated.pluginId());
        assertEquals(PluginHookDispatchException.Category.EXCEPTION, translated.category());
        assertNull(translated.cancellationReasonCode());
        assertNull(translated.cancellationMessage());
        assertTrue(translated.getMessage().contains("before-game-launch"));
        assertTrue(translated.getMessage().contains("dev.test.policy"));
        assertTrue(translated.getMessage().contains("exception"));
        assertFalse(translated.getMessage().contains("top-secret"));
        assertFalse(translated.toString().contains("top-secret"));
        assertFalse(StringUtils.getStackTrace(translated).contains("top-secret"));
    }

    /// Formats Hook launch failures through the controlled dialog message instead of a generic stack trace.
    @Test
    public void launcherHelperFormatsHookFailureWithoutGenericStackTrace() {
        PluginHookDispatchException failure = new PluginHookDispatchException(
                PluginHookPoint.BEFORE_GAME_LAUNCH,
                "dev.test.throwing-account",
                PluginHookDispatchException.Category.EXCEPTION,
                new IllegalStateException("top-secret")
        );
        GameLaunchHookIOException translated = new GameLaunchHookIOException(failure);

        String display = LauncherHelper.formatLaunchFailure(translated);

        assertEquals(translated.getMessage(), display);
        assertFalse(display.contains("GameLaunchHookIOException"));
        assertFalse(display.contains("top-secret"));
    }

    /// Preserves a validated cancellation for the checked boundary and displays only its controlled message.
    @Test
    public void launcherHelperDisplaysValidatedCancellationMessage() {
        PluginHookDispatchException failure = PluginHookDispatchException.cancelled(
                PluginHookPoint.BEFORE_GAME_LAUNCH,
                "dev.test.policy",
                "policy-denied",
                "Launch denied by policy"
        );

        GameLaunchHookIOException translated = new GameLaunchHookIOException(failure);
        String display = LauncherHelper.formatLaunchFailure(translated);

        assertNull(translated.getCause());
        assertEquals(PluginHookDispatchException.Category.CANCELLED, translated.category());
        assertEquals("policy-denied", translated.cancellationReasonCode());
        assertEquals("Launch denied by policy", translated.cancellationMessage());
        assertFalse(translated.getMessage().contains("Launch denied by policy"));
        assertEquals("Launch denied by policy", display);
        assertFalse(display.contains("GameLaunchHookIOException"));
    }
}
