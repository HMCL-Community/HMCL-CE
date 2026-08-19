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
package org.jackhuang.hmcl.setting;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertFalse;

/// Verifies that launcher scenes receive the resolved theme palette.
@NotNullByDefault
public final class StyleSheetsTest {
    /// The CE default theme must not fall back to HMCL's legacy blue palette.
    @Test
    public void defaultThemeUsesResolvedPaletteInsteadOfLegacyBlueSheet() throws Exception {
        try (InputStream input = StyleSheetsTest.class.getResourceAsStream("StyleSheets.class")) {
            String classFile = new String(
                    Objects.requireNonNull(input, "Missing compiled StyleSheets class").readAllBytes(),
                    StandardCharsets.ISO_8859_1);
            assertFalse(classFile.contains("/assets/css/blue.css"),
                    "StyleSheets still contains the legacy blue default-theme fallback");
        }
    }
}
