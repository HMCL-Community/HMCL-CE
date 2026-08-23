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

import com.jfoenix.controls.JFXButton;
import javafx.scene.Scene;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import org.jackhuang.hmcl.FXThreadTestSupport;
import org.jackhuang.hmcl.setting.LauncherSettings;
import org.jackhuang.hmcl.setting.SettingsManager;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.construct.ComponentList;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/// Guards CE-specific feedback content and theme-aware action button colors.
@EnabledIf("org.jackhuang.hmcl.JavaFXLauncher#isStarted")
@NotNullByDefault
public final class CommunityUiRegressionTest {
    /// Keeps the feedback page focused on the maintained GitHub contact without upstream chat links.
    @Test
    public void feedbackPageContainsOnlyGitHubFeedbackSection() throws ReflectiveOperationException {
        Field launcherSettingsField = SettingsManager.class.getDeclaredField("launcherSettings");
        launcherSettingsField.setAccessible(true);
        @Nullable Object previousLauncherSettings = launcherSettingsField.get(null);
        try {
            launcherSettingsField.set(null, new LauncherSettings());
            FXThreadTestSupport.runOnFxThread(() -> {
                FeedbackPage page = new FeedbackPage();
                ScrollPane scrollPane = assertInstanceOf(ScrollPane.class, page.getContent());
                VBox content = assertInstanceOf(VBox.class, scrollPane.getContent());

                assertEquals(2, content.getChildren().size());
                ComponentList feedback = assertInstanceOf(ComponentList.class, content.getChildren().get(1));
                assertEquals(1, feedback.getContent().size());
            });
        } finally {
            launcherSettingsField.set(null, previousLauncherSettings);
        }
    }

    /// Resolves ordinary action button text from the active surface color in a dark palette.
    @Test
    public void ordinaryButtonUsesThemeSurfaceTextColor() {
        FXThreadTestSupport.runOnFxThread(() -> {
            JFXButton button = new JFXButton("Uninstall");
            StackPane root = new StackPane(button);
            root.setStyle("-monet-on-surface: #f1f1f1;"
                    + "-monet-on-primary-container: #202020;"
                    + "-monet-on-primary: #101010;"
                    + "-monet-primary: #d0d0d0;");
            Scene scene = new Scene(root);
            scene.getStylesheets().add(FXUtils.class.getResource("/assets/css/root.css").toExternalForm());

            root.applyCss();

            assertEquals(Color.web("#f1f1f1"), button.getTextFill());
        });
    }
}
