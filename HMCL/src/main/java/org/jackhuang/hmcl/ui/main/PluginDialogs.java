/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2020  huangyuhui <huanghongxun2008@126.com> and contributors
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
import com.jfoenix.controls.JFXDialogLayout;
import com.jfoenix.controls.JFXSpinner;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import org.jackhuang.hmcl.Launcher;
import org.jackhuang.hmcl.plugin.PluginPermission;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.construct.ComponentList;
import org.jackhuang.hmcl.ui.construct.DialogCloseEvent;
import org.jackhuang.hmcl.ui.construct.HintPane;
import org.jackhuang.hmcl.ui.construct.LineButton;
import org.jackhuang.hmcl.ui.construct.MessageDialogPane;
import org.jackhuang.hmcl.util.Restarter;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static org.jackhuang.hmcl.ui.FXUtils.onEscPressed;
import static org.jackhuang.hmcl.util.i18n.I18n.i18n;
import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// Builds HMCL-styled plugin installation, progress, result, and lifecycle dialogs.
@NotNullByDefault
final class PluginDialogs {
    /// Shows permission review without an aggregate-source warning for local package installation.
    ///
    /// @param pluginName display name of the root package
    /// @param update whether the root package replaces an installed artifact
    /// @param permissionRequests permission forms for every package that will be installed or updated
    /// @param installPlan localized dependency-first installation plan
    /// @param callback receives immutable grants indexed by plugin ID after confirmation
    static void confirmPluginInstall(
            String pluginName,
            boolean update,
            List<PluginPermissionRequest> permissionRequests,
            List<String> installPlan,
            Consumer<@Unmodifiable Map<String, @Unmodifiable Set<PluginPermission>>> callback
    ) {
        confirmPluginInstall(pluginName, update, permissionRequests, installPlan, null, callback);
    }

    /// Shows permission review with an optional aggregate-source warning.
    ///
    /// @param pluginName display name of the root package
    /// @param update whether the root package replaces an installed artifact
    /// @param permissionRequests permission forms for every package that will be installed or updated
    /// @param installPlan localized dependency-first installation plan
    /// @param catalogWarning aggregate-source warning without source URLs, or `null` when every source is available
    /// @param callback receives immutable grants indexed by plugin ID after confirmation
    static void confirmPluginInstall(
            String pluginName,
            boolean update,
            List<PluginPermissionRequest> permissionRequests,
            List<String> installPlan,
            @Nullable String catalogWarning,
            Consumer<@Unmodifiable Map<String, @Unmodifiable Set<PluginPermission>>> callback
    ) {
        Platform.runLater(() -> Controllers.dialog(new PluginInstallPermissionDialog(
                pluginName,
                update,
                permissionRequests,
                installPlan,
                catalogWarning,
                callback
        )));
    }

    /// Opens a non-blocking HMCL progress dialog and returns its update handle.
    ///
    /// @param title localized operation title
    /// @param status initial localized status
    /// @return visible progress dialog handle
    static ProgressDialog showProgress(String title, String status) {
        ProgressDialog dialog = new ProgressDialog(title, status);
        Controllers.dialog(dialog);
        return dialog;
    }

    /// Shows an HMCL message dialog for a plugin operation failure.
    ///
    /// @param title localized failure title
    /// @param message failure detail
    static void showError(String title, String message) {
        Platform.runLater(() -> Controllers.dialog(
                message,
                title,
                MessageDialogPane.MessageType.ERROR
        ));
    }

    /// Shows an HMCL confirmation dialog for one destructive plugin action.
    ///
    /// @param title localized dialog title
    /// @param message localized action description
    /// @param confirmText localized confirmation button text
    /// @param action action invoked after confirmation
    static void confirmAction(String title, String message, String confirmText, Runnable action) {
        Platform.runLater(() -> {
            MessageDialogPane pane = new MessageDialogPane(
                    message,
                    title,
                    MessageDialogPane.MessageType.WARNING
            );

            JFXButton confirmButton = new JFXButton(confirmText);
            confirmButton.getStyleClass().add("dialog-accept");
            confirmButton.setOnAction(event -> action.run());

            JFXButton cancelButton = new JFXButton(i18n("button.cancel"));
            cancelButton.getStyleClass().add("dialog-cancel");

            pane.addButton(confirmButton);
            pane.addButton(cancelButton);
            pane.setCancelButton(cancelButton);
            Controllers.dialog(pane);
        });
    }

    /// Announces a successful installation and offers an immediate launcher restart.
    ///
    /// @param pluginName display name of the installed plugin
    static void showInstallFinishedAndOfferRestart(String pluginName) {
        showInstallFinished(pluginName, true);
    }

    /// Announces a completed installation and only offers restart when the applied plan requires it.
    ///
    /// @param pluginName display name of the installed plugin
    /// @param restartRecommended whether installed or staged entries require a launcher restart
    static void showInstallFinished(String pluginName, boolean restartRecommended) {
        Platform.runLater(() -> {
            MessageDialogPane pane = new MessageDialogPane(
                    i18n(restartRecommended
                            ? "plugin.install.complete.content"
                            : "plugin.install.complete.content.no_restart", pluginName),
                    i18n("plugin.install.complete.title"),
                    MessageDialogPane.MessageType.SUCCESS
            );

            if (restartRecommended) {
                JFXButton restartButton = new JFXButton(i18n("plugin.install.restart.now"));
                restartButton.getStyleClass().add("dialog-accept");
                restartButton.setOnAction(event -> restartLauncher());

                JFXButton laterButton = new JFXButton(i18n("plugin.install.restart.later"));
                laterButton.getStyleClass().add("dialog-cancel");

                pane.addButton(restartButton);
                pane.addButton(laterButton);
                pane.setCancelButton(laterButton);
            } else {
                JFXButton okButton = new JFXButton(i18n("button.ok"));
                okButton.getStyleClass().add("dialog-accept");
                pane.addButton(okButton);
                pane.setCancelButton(okButton);
            }

            Controllers.dialog(pane);
        });
    }

    /// Shows a restart requirement raised by another plugin lifecycle action.
    ///
    /// @param message localized explanation of why restart is required
    static void showRestartRequired(String message) {
        Platform.runLater(() -> {
            MessageDialogPane pane = new MessageDialogPane(
                    message,
                    i18n("plugin.restart_required.title"),
                    MessageDialogPane.MessageType.INFO
            );

            JFXButton restartButton = new JFXButton(i18n("plugin.install.restart.now"));
            restartButton.getStyleClass().add("dialog-accept");
            restartButton.setOnAction(event -> restartLauncher());

            JFXButton laterButton = new JFXButton(i18n("plugin.install.restart.later"));
            laterButton.getStyleClass().add("dialog-cancel");

            pane.addButton(restartButton);
            pane.addButton(laterButton);
            pane.setCancelButton(laterButton);

            Controllers.dialog(pane);
        });
    }

    /// Restarts the launcher and reports restart failures in a dedicated error dialog.
    static void restartLauncher() {
        try {
            Restarter.restartSelf();
            Launcher.stopApplication();
        } catch (IOException exception) {
            LOG.warning("Failed to restart self", exception);
            @Nullable String message = exception.getMessage();
            showError(
                    i18n("plugin.install.restart.failed"),
                    message == null ? exception.toString() : message
            );
        }
    }

    /// Prevents construction of the stateless dialog helper.
    private PluginDialogs() {
    }

    /// HMCL dialog containing independent permission switches for every changed plugin artifact.
    @NotNullByDefault
    private static final class PluginInstallPermissionDialog extends JFXDialogLayout {
        /// Editable permission panes indexed by plugin ID.
        private final Map<String, PluginPermissionPane> permissionPanes = new LinkedHashMap<>();

        /// Creates and opens one complete installation review form.
        ///
        /// @param pluginName root plugin display name
        /// @param update whether the root package is an update
        /// @param permissionRequests permission groups for mutable packages
        /// @param installPlan localized dependency-first plan rows
        /// @param catalogWarning aggregate-source warning without source URLs, or `null` when unavailable
        /// @param callback receives confirmed immutable grants
        private PluginInstallPermissionDialog(
                String pluginName,
                boolean update,
                List<PluginPermissionRequest> permissionRequests,
                List<String> installPlan,
                @Nullable String catalogWarning,
                Consumer<@Unmodifiable Map<String, @Unmodifiable Set<PluginPermission>>> callback
        ) {
            setHeading(new HBox(new Label(i18n(
                    update ? "plugin.update.permissions.title" : "plugin.install.permissions.title",
                    pluginName
            ))));

            VBox content = new VBox(10);
            content.setPadding(new Insets(4, 2, 4, 2));

            HintPane runtimeHint = new HintPane(MessageDialogPane.MessageType.WARNING);
            String runtimeWarning = i18n("plugin.install.permissions.jvm_warning");
            boolean containsMixinPlugin = permissionRequests.stream()
                    .anyMatch(request -> request.getDeclaredPermissions().contains(PluginPermission.MIXIN));
            if (containsMixinPlugin) {
                runtimeWarning += "\n\n" + i18n("plugin.install.permissions.mixin_atomic_warning");
            }
            runtimeHint.setText(runtimeWarning);
            content.getChildren().add(runtimeHint);
            if (catalogWarning != null) {
                HintPane catalogWarningHint = new HintPane(MessageDialogPane.MessageType.WARNING);
                catalogWarningHint.setText(catalogWarning);
                content.getChildren().add(catalogWarningHint);
            }

            content.getChildren().add(ComponentList.createComponentListTitle(i18n("plugin.install.plan")));
            ComponentList planList = new ComponentList();
            planList.getStyleClass().add("no-padding");
            for (String planRow : installPlan) {
                LineButton row = new LineButton();
                row.setLeading(SVG.PACKAGE2);
                row.setTitle(planRow);
                row.setMouseTransparent(true);
                planList.getContent().add(row);
            }
            if (installPlan.isEmpty()) {
                LineButton emptyPlan = new LineButton();
                emptyPlan.setLeading(SVG.INFO);
                emptyPlan.setTitle(i18n("plugin.install.plan.none"));
                emptyPlan.setMouseTransparent(true);
                planList.getContent().add(emptyPlan);
            }
            content.getChildren().add(planList);

            for (PluginPermissionRequest request : permissionRequests) {
                content.getChildren().add(ComponentList.createComponentListTitle(i18n(
                        request.isUpdate()
                                ? "plugin.install.permissions.plugin.update"
                                : "plugin.install.permissions.plugin",
                        request.getDisplayName(),
                        request.getVersion()
                )));
                PluginPermissionPane pane = new PluginPermissionPane(
                        request.getRequiredPermissions(),
                        request.getOptionalPermissions(),
                        request.getInitiallyGrantedPermissions(),
                        request.isEditable(),
                        request.getNewlyRequiredPermissions(),
                        request.getNewlyOptionalPermissions()
                );
                content.getChildren().add(pane);
                if (request.isEditable()) {
                    permissionPanes.put(request.getPluginId(), pane);
                }
            }

            ScrollPane scrollPane = new ScrollPane(content);
            scrollPane.setFitToWidth(true);
            scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
            scrollPane.setPrefViewportWidth(680);
            scrollPane.setPrefViewportHeight(520);
            scrollPane.maxHeightProperty().bind(Controllers.getStage().heightProperty().multiply(0.72));
            FXUtils.smoothScrolling(scrollPane);
            FXUtils.setOverflowHidden(scrollPane, 8);
            setBody(scrollPane);

            JFXButton confirmButton = new JFXButton(i18n(update
                    ? "plugin.install.warning.update_anyway"
                    : "plugin.install.warning.install_anyway"));
            confirmButton.getStyleClass().add("dialog-accept");
            confirmButton.setOnAction(event -> {
                Map<String, @Unmodifiable Set<PluginPermission>> grants = new LinkedHashMap<>();
                permissionPanes.forEach((pluginId, pane) ->
                        grants.put(pluginId, pane.getGrantedPermissions()));
                fireEvent(new DialogCloseEvent());
                callback.accept(Map.copyOf(grants));
            });

            JFXButton cancelButton = new JFXButton(i18n("button.cancel"));
            cancelButton.getStyleClass().add("dialog-cancel");
            cancelButton.setOnAction(event -> fireEvent(new DialogCloseEvent()));

            setActions(confirmButton, cancelButton);
            onEscPressed(this, cancelButton::fire);
        }
    }

    /// Non-dismissible HMCL progress pane updated by asynchronous plugin workflows.
    @NotNullByDefault
    static final class ProgressDialog extends JFXDialogLayout {
        /// Mutable status line below the activity spinner.
        private final Label statusLabel = new Label();

        /// Creates a progress pane with stable dimensions.
        ///
        /// @param title localized operation title
        /// @param status initial localized status
        private ProgressDialog(String title, String status) {
            setHeading(new HBox(new Label(title)));

            JFXSpinner spinner = new JFXSpinner();
            spinner.setMinSize(36, 36);
            spinner.setPrefSize(36, 36);

            statusLabel.setText(status);
            statusLabel.setWrapText(true);
            HBox body = new HBox(12, spinner, statusLabel);
            body.setAlignment(Pos.CENTER_LEFT);
            body.setPadding(new Insets(12, 4, 12, 4));
            HBox.setHgrow(statusLabel, Priority.ALWAYS);
            setBody(body);
            setPrefWidth(460);
        }

        /// Replaces the current progress status on the JavaFX thread.
        ///
        /// @param status new localized status
        void setStatus(String status) {
            if (Platform.isFxApplicationThread()) {
                statusLabel.setText(status);
            } else {
                Platform.runLater(() -> statusLabel.setText(status));
            }
        }

        /// Closes this progress pane on the JavaFX thread.
        void close() {
            if (Platform.isFxApplicationThread()) {
                fireEvent(new DialogCloseEvent());
            } else {
                Platform.runLater(() -> fireEvent(new DialogCloseEvent()));
            }
        }
    }
}
