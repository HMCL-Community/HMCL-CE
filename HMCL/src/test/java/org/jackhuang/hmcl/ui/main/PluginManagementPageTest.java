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
package org.jackhuang.hmcl.ui.main;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies deterministic installed-plugin summaries used by the minimum-width management list.
@NotNullByDefault
public final class PluginManagementPageTest {
    /// Normalizes line breaks and bounds both narrow and wide text without splitting Unicode code points.
    @Test
    public void summarizeInstalledPluginText() {
        assertEquals("First second", PluginManagementPage.summarizeDisplayText(" First\n\nsecond ", 20));

        String narrow = PluginManagementPage.summarizeDisplayText("x".repeat(80), 20);
        assertTrue(narrow.endsWith("\u2026"));
        assertTrue(narrow.length() <= 19);

        String wide = PluginManagementPage.summarizeDisplayText("\u63d2\u4ef6".repeat(30), 20);
        assertTrue(wide.endsWith("\u2026"));
        assertTrue(wide.codePointCount(0, wide.length()) <= 10);
    }

}
