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
package org.jackhuang.hmcl.util;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// Verifies the repository configuration used to download a missing JavaFX runtime.
@NotNullByDefault
public final class SelfDependencyPatcherTest {
    /// Uses the live Tencent Maven mirror path rather than the obsolete endpoint that returns HTTP 404.
    @Test
    public void useWorkingTencentCloudMirrorEndpoint() {
        assertEquals(
                "https://mirrors.cloud.tencent.com/nexus/repository/maven-public",
                SelfDependencyPatcher.getTencentCloudMirrorUrl()
        );
    }
}
