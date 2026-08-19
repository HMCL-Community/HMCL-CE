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

import javafx.scene.Node;
import javafx.scene.layout.StackPane;
import org.jackhuang.hmcl.FXThreadTestSupport;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import static org.junit.jupiter.api.Assertions.assertSame;

/// Verifies plugin-owned sidebar UI registrations on the JavaFX application thread.
@NotNullByDefault
@EnabledIf("org.jackhuang.hmcl.JavaFXLauncher#isStarted")
final class PluginUIRegistryTest {
    /// Verifies a page-backed sidebar item retains its original lazy page supplier.
    @Test
    void retainsThePageSupplierForThemeOwnedSidebarRendering() {
        FXThreadTestSupport.runOnFxThread(() -> {
            Node page = new StackPane();

            try {
                PluginUIRegistry.registerSidebarPage("test.theme-page", "Theme page", () -> page);

                PluginUIRegistry.SidebarItem item = PluginUIRegistry.getSidebarItems()
                        .get(PluginUIRegistry.getSidebarItems().size() - 1);
                assertSame(page, item.getPageSupplier().get());
            } finally {
                PluginUIRegistry.unregisterAll("test.theme-page");
            }
        });
    }
}
