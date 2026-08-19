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
package org.jackhuang.hmcl.upgrade;

import org.jackhuang.hmcl.plugin.mixin.bootstrap.HmclMixinBootstrap;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies that HMCL restarts retain user JVM options without leaking process-local Mixin state.
@NotNullByDefault
public final class UpdateHandlerTest {
    /// Retains ordinary system properties and memory options.
    @Test
    public void retainReusableJvmArguments() {
        assertTrue(UpdateHandler.shouldInheritJvmInputArgument("-Dhmcl.home=C:\\HMCL"));
        assertTrue(UpdateHandler.shouldInheritJvmInputArgument("-Dfile.encoding=UTF-8"));
        assertTrue(UpdateHandler.shouldInheritJvmInputArgument("-Xmx2G"));
    }

    /// Drops all Mixin flags whose values are valid only inside the current JVM.
    @Test
    public void dropTransientMixinArguments() {
        assertFalse(UpdateHandler.shouldInheritJvmInputArgument(
                "-D" + HmclMixinBootstrap.AGENT_ACTIVE_PROPERTY + "=true"));
        assertFalse(UpdateHandler.shouldInheritJvmInputArgument(
                "-D" + HmclMixinBootstrap.ACTIVE_PROPERTY + "=dev.hmclce.test"));
        assertFalse(UpdateHandler.shouldInheritJvmInputArgument(
                "-D" + HmclMixinBootstrap.DISABLE_PROPERTY + "=true"));
        assertFalse(UpdateHandler.shouldInheritJvmInputArgument("-javaagent:HMCL.jar"));
    }
}
