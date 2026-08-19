/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2026  huangyuhui <huanghongxun2008@126.com> and contributors
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
package org.jackhuang.hmcl.ui;

import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// Shows the launcher icon while the main window is being built.
///
/// The logo is opt-in through the `hmcl.startupLogo` system property, so ordinary launches remain unchanged. A
/// plugin can set the property from its `onLoad`, which runs during `initializeSettingsRuntime()` before [#show()].
///
/// The logo is shown from `Launcher.start()` so that method can return to the event loop for a render pulse before
/// main-window initialization occupies the JavaFX thread.
@NotNullByDefault
public final class StartupLogo {

    /// System property that enables the opt-in startup logo.
    private static final String ENABLE_PROPERTY = "hmcl.startupLogo";

    /// Width and height used to render the source icon without resampling.
    private static final double SIZE = 128;

    /// Time the logo remains opaque after the main window appears.
    private static final Duration DISPLAY_DURATION = Duration.millis(800);

    /// Duration of the opacity transition used to dismiss the logo.
    private static final Duration FADE_OUT = Duration.millis(600);

    /// Delay before main-window initialization, allowing roughly three frames at 60 Hz for the first render pulse.
    private static final Duration FIRST_PAINT_DELAY = Duration.millis(48);

    /// Hard ceiling that closes the logo if startup never reaches [#dismiss()].
    private static final Duration MAX_LIFETIME = Duration.seconds(15);

    /// Built-in image resource displayed by the startup stage.
    private static final String ICON = "/assets/img/icon@4x.png";

    /// Active transparent startup stage, or `null` when no logo is visible.
    private static @Nullable Stage stage;

    /// Active scene root whose opacity is animated during dismissal.
    private static @Nullable StackPane content;

    /// Timeout that prevents the startup stage from remaining open indefinitely.
    private static @Nullable PauseTransition watchdog;

    /// Prevents construction of this static lifecycle utility.
    private StartupLogo() {
    }

    /// Shows the logo when enabled and not already visible.
    ///
    /// This method must run on the JavaFX thread and return to the event loop before the logo can be painted.
    public static void show() {
        if (stage != null || !Boolean.getBoolean(ENABLE_PROPERTY)) {
            return;
        }

        // A decorative logo must never be able to stop the launcher from
        // starting, so nothing here is allowed to propagate.
        try {
            Image icon = FXUtils.newBuiltinImage(ICON, SIZE, SIZE, true, true);

            ImageView view = new ImageView(icon);
            view.setFitWidth(SIZE);
            view.setFitHeight(SIZE);
            view.setPreserveRatio(true);
            view.setSmooth(true);

            StackPane root = new StackPane(view);
            root.setBackground(null);

            Scene scene = new Scene(root, SIZE, SIZE);
            scene.setFill(Color.TRANSPARENT);

            Stage logo = new Stage(StageStyle.TRANSPARENT);
            logo.setScene(scene);
            logo.setAlwaysOnTop(true);
            logo.setResizable(false);
            logo.setWidth(SIZE);
            logo.setHeight(SIZE);
            logo.centerOnScreen();

            stage = logo;
            content = root;
            logo.show();
            LOG.info("Startup logo shown");

            PauseTransition limit = new PauseTransition(MAX_LIFETIME);
            limit.setOnFinished(event -> {
                LOG.warning("Startup logo was never dismissed after "
                        + (long) MAX_LIFETIME.toSeconds() + "s, closing it");
                close();
            });
            watchdog = limit;
            limit.play();
        } catch (Throwable e) {
            LOG.warning("Failed to show startup logo", e);
            close();
        }
    }

    /// Runs an action after the logo has had an opportunity to receive its first render pulse.
    ///
    /// Without a visible logo this delegates directly to [Platform#runLater(Runnable)]. With a logo, a
    /// [PauseTransition] delays the action until render pulses have advanced the transition.
    ///
    /// @param action main-window initialization to schedule on the JavaFX thread
    public static void runAfterFirstPaint(Runnable action) {
        if (stage == null) {
            Platform.runLater(action);
            return;
        }

        PauseTransition firstPaint = new PauseTransition(FIRST_PAINT_DELAY);
        firstPaint.setOnFinished(event -> action.run());
        firstPaint.play();
    }

    /// Fades out and closes the logo if it is visible.
    ///
    /// Repeated calls and calls made without an active logo are ignored.
    public static void dismiss() {
        if (stage == null || content == null) {
            return;
        }

        try {
            stopWatchdog();
            // Wait for DISPLAY_DURATION before starting fade
            PauseTransition delay = new PauseTransition(DISPLAY_DURATION);
            delay.setOnFinished(e -> {
                Timeline fadeOut = new Timeline(new KeyFrame(FADE_OUT,
                        new KeyValue(content.opacityProperty(), 0)));
                fadeOut.setOnFinished(event -> close());
                fadeOut.play();
                LOG.info("Startup logo dismissed");
            });
            delay.play();
        } catch (Throwable e) {
            LOG.warning("Failed to fade out startup logo", e);
            close();
        }
    }

    /// Stops the watchdog, clears active stage state, and closes the startup stage when present.
    private static void close() {
        stopWatchdog();
        @Nullable Stage logo = stage;
        stage = null;
        content = null;
        if (logo != null) {
            logo.close();
        }
    }

    /// Stops and clears the active maximum-lifetime watchdog when present.
    private static void stopWatchdog() {
        @Nullable PauseTransition limit = watchdog;
        watchdog = null;
        if (limit != null) {
            limit.stop();
        }
    }
}
