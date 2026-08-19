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
package org.jackhuang.hmcl.plugin.mixin.bootstrap;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// Verifies that the Mixin relaunch keeps a self-patched JavaFX runtime available in the child JVM.
@NotNullByDefault
public final class HmclMixinBootstrapRelaunchTest {
    /// Adds the cached JavaFX modules to the child process before premain can load UI-dependent Mixins.
    @Test
    public void addSelfPatchedJavaFxModulesToAgentProcess() {
        List<String> command = new ArrayList<>(List.of("java"));
        List<Path> modulePath = List.of(
                Path.of("cache", "javafx-base.jar"),
                Path.of("cache", "javafx-graphics.jar"),
                Path.of("cache", "javafx-controls.jar")
        );

        HmclMixinBootstrap.appendJavaFxRuntimeArguments(command, modulePath);

        assertEquals(List.of(
                "java",
                "--module-path",
                String.join(File.pathSeparator, modulePath.stream().map(Path::toString).toList()),
                "--add-modules",
                "javafx.base,javafx.graphics,javafx.controls"
        ), command);
    }

    /// Leaves the child command untouched when HMCL used JavaFX supplied by the selected Java runtime.
    @Test
    public void omitModuleArgumentsWithoutSelfPatchedJavaFx() {
        List<String> command = new ArrayList<>(List.of("java"));

        HmclMixinBootstrap.appendJavaFxRuntimeArguments(command, List.of());

        assertEquals(List.of("java"), command);
    }
}
