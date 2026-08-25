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

import javafx.scene.Node;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import org.jackhuang.hmcl.FXThreadTestSupport;
import org.jackhuang.hmcl.setting.GameSettingsPresets;
import org.jackhuang.hmcl.setting.LauncherSettings;
import org.jackhuang.hmcl.setting.SettingsManager;
import org.jackhuang.hmcl.ui.construct.AdvancedListBox;
import org.jackhuang.hmcl.ui.construct.AdvancedListItem;
import org.jackhuang.hmcl.ui.construct.ClassTitle;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.lang.reflect.Field;
import java.util.List;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies that the temporarily detached C# Companion runtime does not alter launcher settings navigation.
@EnabledIf("org.jackhuang.hmcl.JavaFXLauncher#isStarted")
@NotNullByDefault
public final class LauncherSettingsPageTest {
    /// Exposes only built-in settings navigation while the external runtime integration is disabled.
    @Test
    public void excludeCompanionCatalogFromNativeSettings() throws ReflectiveOperationException {
        Field launcherSettingsField = SettingsManager.class.getDeclaredField("launcherSettings");
        launcherSettingsField.setAccessible(true);
        @Nullable Object previousLauncherSettings = launcherSettingsField.get(null);
        Field gameSettingsPresetsField = SettingsManager.class.getDeclaredField("gameSettingsPresets");
        gameSettingsPresetsField.setAccessible(true);
        @Nullable Object previousGameSettingsPresets = gameSettingsPresetsField.get(null);

        try {
            launcherSettingsField.set(null, new LauncherSettings());
            gameSettingsPresetsField.set(null, new GameSettingsPresets());
            FXThreadTestSupport.runOnFxThread(() -> {
                LauncherSettingsPage page = new LauncherSettingsPage();
                AdvancedListBox sideBar = getField(page, "sideBar", AdvancedListBox.class);

                assertFalse(categoryTitles(sideBar).stream().anyMatch(title -> title.startsWith("C#")));
                assertFalse(navigationTitles(sideBar).stream().anyMatch(title -> title.startsWith("C#")));
            });
        } finally {
            gameSettingsPresetsField.set(null, previousGameSettingsPresets);
            launcherSettingsField.set(null, previousLauncherSettings);
        }
    }

    /// Adds the native recovery navigation item only when persisted recovery state is actionable.
    @Test
    public void includeRecoveryNavigationConditionally() throws ReflectiveOperationException {
        Field launcherSettingsField = SettingsManager.class.getDeclaredField("launcherSettings");
        launcherSettingsField.setAccessible(true);
        @Nullable Object previousLauncherSettings = launcherSettingsField.get(null);
        Field gameSettingsPresetsField = SettingsManager.class.getDeclaredField("gameSettingsPresets");
        gameSettingsPresetsField.setAccessible(true);
        @Nullable Object previousGameSettingsPresets = gameSettingsPresetsField.get(null);

        try {
            launcherSettingsField.set(null, new LauncherSettings());
            gameSettingsPresetsField.set(null, new GameSettingsPresets());
            FXThreadTestSupport.runOnFxThread(() -> {
                LauncherSettingsPage withoutRecovery = new LauncherSettingsPage(false);
                LauncherSettingsPage withRecovery = new LauncherSettingsPage(true);

                assertFalse(visibleNavigationTitles(getField(
                        withoutRecovery,
                        "sideBar",
                        AdvancedListBox.class
                )).contains(i18n("plugin.recovery.title")));
                assertTrue(visibleNavigationTitles(getField(
                        withRecovery,
                        "sideBar",
                        AdvancedListBox.class
                )).contains(i18n("plugin.recovery.title")));

                withRecovery.updateRecoveryNavigation(false);
                assertFalse(visibleNavigationTitles(getField(
                        withRecovery,
                        "sideBar",
                        AdvancedListBox.class
                )).contains(i18n("plugin.recovery.title")));
                withRecovery.updateRecoveryNavigation(true);
                assertTrue(visibleNavigationTitles(getField(
                        withRecovery,
                        "sideBar",
                        AdvancedListBox.class
                )).contains(i18n("plugin.recovery.title")));
            });
        } finally {
            gameSettingsPresetsField.set(null, previousGameSettingsPresets);
            launcherSettingsField.set(null, previousLauncherSettings);
        }
    }

    /// Reads a private production field from the composed settings page.
    private static <T> T getField(Object owner, String name, Class<T> type) {
        try {
            Field field = owner.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return type.cast(field.get(owner));
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Cannot inspect " + name, exception);
        }
    }

    /// Returns the direct nodes rendered by an advanced list box.
    private static List<Node> navigationNodes(AdvancedListBox sideBar) {
        return ((VBox) sideBar.getContent()).getChildren();
    }

    /// Returns visible category headings in visual order.
    private static List<String> categoryTitles(AdvancedListBox sideBar) {
        return navigationNodes(sideBar).stream()
                .filter(ClassTitle.class::isInstance)
                .map(ClassTitle.class::cast)
                .map(ClassTitle::getContent)
                .filter(Text.class::isInstance)
                .map(Text.class::cast)
                .map(Text::getText)
                .toList();
    }

    /// Returns visible navigation titles in visual order.
    private static List<String> navigationTitles(AdvancedListBox sideBar) {
        return navigationNodes(sideBar).stream()
                .filter(AdvancedListItem.class::isInstance)
                .map(AdvancedListItem.class::cast)
                .map(AdvancedListItem::getTitle)
                .toList();
    }

    /// Returns only navigation titles currently participating in the sidebar layout.
    private static List<String> visibleNavigationTitles(AdvancedListBox sideBar) {
        return navigationNodes(sideBar).stream()
                .filter(AdvancedListItem.class::isInstance)
                .map(AdvancedListItem.class::cast)
                .filter(Node::isManaged)
                .filter(Node::isVisible)
                .map(AdvancedListItem::getTitle)
                .toList();
    }
}
