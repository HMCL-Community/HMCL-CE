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

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies that popup selections complete their action before closing the popup.
@NotNullByDefault
public final class RootPageTest {
    /// Runs the selected action once before dismissing the popup once.
    @Test
    public void pluginPopupSelectionRunsActionBeforeDismissal() {
        List<String> events = new ArrayList<>();

        RootPage.PluginPopupSelection.runAndDismiss(() -> events.add("action"), () -> events.add("dismiss")).run();

        assertEquals(List.of("action", "dismiss"), events);
    }

    /// Dismisses the popup when a selected action fails so it cannot remain open.
    @Test
    public void failedPluginPopupSelectionStillDismissesPopup() {
        List<String> events = new ArrayList<>();

        assertThrows(IllegalStateException.class, () -> RootPage.PluginPopupSelection.runAndDismiss(
                () -> {
                    events.add("action");
                    throw new IllegalStateException("failure");
                },
                () -> events.add("dismiss")
        ).run());

        assertEquals(List.of("action", "dismiss"), events);
    }
}
