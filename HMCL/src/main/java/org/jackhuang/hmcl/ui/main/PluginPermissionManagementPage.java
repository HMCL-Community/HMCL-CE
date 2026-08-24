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
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import org.jackhuang.hmcl.plugin.PluginContainer;
import org.jackhuang.hmcl.plugin.PluginManager;
import org.jackhuang.hmcl.plugin.PluginManifest;
import org.jackhuang.hmcl.plugin.PluginPermission;
import org.jackhuang.hmcl.plugin.PluginRuntimeStatus;
import org.jackhuang.hmcl.task.Schedulers;
import org.jackhuang.hmcl.task.Task;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.construct.ComponentList;
import org.jackhuang.hmcl.ui.construct.HintPane;
import org.jackhuang.hmcl.ui.construct.LineButton;
import org.jackhuang.hmcl.ui.construct.MessageDialogPane;
import org.jackhuang.hmcl.ui.construct.PageCloseEvent;
import org.jackhuang.hmcl.ui.decorator.DecoratorPage;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.util.Set;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;
import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// Lets the user review and change grants for one installed plugin using HMCL settings controls.
@NotNullByDefault
final class PluginPermissionManagementPage extends BorderPane implements DecoratorPage {
    /// Decorator navigation state for this plugin-specific management page.
    private final ReadOnlyObjectWrapper<State> state;

    /// Process-wide plugin lifecycle and permission manager.
    private final PluginManager pluginManager = PluginManager.getInstance();

    /// Installed artifact manifest whose current permissions are being managed.
    private final PluginManifest manifest;

    /// Loaded plugin container, or `null` when discovery or `onLoad` failed.
    private final @Nullable PluginContainer container;

    /// Callback used to refresh the parent installed-plugin list after mutations.
    private final Runnable refreshParent;

    /// Editable rows for exactly the permissions declared by the current artifact.
    private final PluginPermissionPane permissionPane;

    /// Last successfully persisted immutable grant set.
    private @Unmodifiable Set<PluginPermission> savedPermissions;

    /// Current lifecycle status row.
    private final LineButton statusRow = new LineButton();

    /// Holds at most one runtime, failure, restart, or operation status message.
    private final VBox statusHintContainer = new VBox();

    /// Lifecycle action that changes between enable and disable.
    private final JFXButton lifecycleButton = new JFXButton();

    /// Saves changed permission switches and remains disabled while no selection differs.
    private final JFXButton applyButton = new JFXButton(i18n("button.apply"));

    /// Restarts the launcher only when current changes cannot take effect in this process.
    private final JFXButton restartButton = new JFXButton(i18n("plugin.install.restart.now"));

    /// Removes the installed artifact or schedules removal for restart.
    private final JFXButton uninstallButton = new JFXButton(i18n("plugin.uninstall"));

    /// Whether a completed permission action introduced an explicit restart requirement.
    private boolean restartRequired;

    /// Whether an asynchronous grant replacement currently owns all permission-page controls.
    private boolean savingPermissions;

    /// Whether the current runtime state permits an enable or disable request.
    private boolean lifecycleActionAvailable;

    /// Creates a plugin permission page and loads current grants fail-closed.
    ///
    /// @param manifest installed plugin manifest
    /// @param container loaded plugin container or `null`
    /// @param refreshParent callback that refreshes the installed-plugin list
    PluginPermissionManagementPage(
            PluginManifest manifest,
            @Nullable PluginContainer container,
            Runnable refreshParent
    ) {
        this.manifest = manifest;
        this.container = container;
        this.refreshParent = refreshParent;

        state = new ReadOnlyObjectWrapper<>(State.fromTitle(i18n(
                "plugin.permissions.manage.title",
                manifest.getName()
        )));

        @Unmodifiable Set<PluginPermission> loadedPermissions;
        try {
            loadedPermissions = pluginManager.getGrantedPermissions(manifest.getId());
        } catch (IOException exception) {
            LOG.warning("Failed to load plugin permission grants: " + manifest.getId(), exception);
            loadedPermissions = Set.of();
            PluginDialogs.showError(i18n("plugin.permissions.load_failed"), failureMessage(exception));
        }
        savedPermissions = Set.copyOf(loadedPermissions);
        permissionPane = new PluginPermissionPane(
                manifest.getRequiredPermissions(),
                manifest.getOptionalPermissions(),
                savedPermissions,
                true,
                Set.of(),
                Set.of()
        );
        permissionPane.setOnPermissionChange(this::refreshActionState);

        getStyleClass().add("gray-background");
        setCenter(createContent());
        setBottom(createActions());
        refreshStatus();
        refreshActionState();
    }

    /// Builds the scrollable overview, single status message, and permission sections.
    ///
    /// @return scrollable page content
    private ScrollPane createContent() {
        VBox content = new VBox(10);
        content.setPadding(new Insets(10));

        statusHintContainer.setManaged(false);
        statusHintContainer.setVisible(false);
        content.getChildren().add(statusHintContainer);

        ComponentList overview = new ComponentList();
        overview.getStyleClass().add("no-padding");
        overview.getContent().addAll(
                createInfoRow(SVG.EXTENSION, i18n("plugin.name"), manifest.getName()),
                createInfoRow(SVG.INFO, "ID", manifest.getId()),
                createInfoRow(SVG.UPDATE, i18n("plugin.store.version"), manifest.getVersion()),
                createInfoRow(
                        SVG.PACKAGE2,
                        i18n("plugin.type"),
                        manifest.getType().name() + (manifest.hasMixins() ? " + MIXIN" : "")
                ),
                statusRow
        );
        content.getChildren().addAll(
                ComponentList.createComponentListTitle(i18n("plugin.details")),
                overview,
                ComponentList.createComponentListTitle(i18n("plugin.permissions.requested")),
                permissionPane
        );

        ScrollPane scrollPane = new ScrollPane(content);
        scrollPane.setFitToWidth(true);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        FXUtils.smoothScrolling(scrollPane);
        FXUtils.setOverflowHidden(scrollPane, 8);
        return scrollPane;
    }

    /// Builds the stable bottom action bar for grant and lifecycle commands.
    ///
    /// @return page action bar
    private HBox createActions() {
        HBox actions = new HBox(8);
        actions.setAlignment(Pos.CENTER_RIGHT);
        actions.setPadding(new Insets(8, 10, 10, 10));

        restartButton.setOnAction(event -> PluginDialogs.restartLauncher());

        applyButton.getStyleClass().add("jfx-button-raised");
        applyButton.setOnAction(event -> savePermissions());

        lifecycleButton.setOnAction(event -> changeLifecycle());

        uninstallButton.setOnAction(event -> confirmUninstall());

        actions.getChildren().addAll(restartButton, applyButton, lifecycleButton, uninstallButton);
        return actions;
    }

    /// Creates a non-interactive HMCL information row.
    ///
    /// @param icon leading icon
    /// @param title field title
    /// @param value field value
    /// @return configured information row
    private static LineButton createInfoRow(SVG icon, String title, String value) {
        LineButton row = new LineButton();
        row.setLeading(icon);
        row.setTitle(title);
        row.setTrailingText(value);
        row.setMouseTransparent(true);
        return row;
    }

    /// Persists the selected requested permissions and leaves restart guidance inline.
    ///
    private void savePermissions() {
        @Unmodifiable Set<PluginPermission> selected = permissionPane.getGrantedPermissions();
        if (selected.equals(savedPermissions)) {
            return;
        }
        Set<PluginPermission> changedPermissions = new java.util.HashSet<>(savedPermissions);
        for (PluginPermission permission : selected) {
            if (!changedPermissions.add(permission)) {
                changedPermissions.remove(permission);
            }
        }
        setSavingPermissions(true);
        Task.runAsync(() -> {
            try {
                pluginManager.setGrantedPermissions(manifest.getId(), selected);
            } catch (IOException exception) {
                throw new RuntimeException(exception);
            }
        }).whenComplete(Schedulers.javafx(), (@Nullable var result, @Nullable var exception) -> {
            if (exception != null) {
                setSavingPermissions(false);
                showStatusHint(
                        MessageDialogPane.MessageType.ERROR,
                        i18n("plugin.permissions.save_failed") + ": " + failureMessage(exception)
                );
                return;
            }
            restartRequired |= manifest.hasMixins()
                    || changedPermissions.contains(PluginPermission.MIXIN)
                    || changedPermissions.contains(PluginPermission.NATIVE_CODE);
            savedPermissions = Set.copyOf(selected);
            setSavingPermissions(false);
            refreshStatus();
            refreshActionState();
            refreshParent.run();
        }).start();
    }

    /// Applies the desired lifecycle state immediately when possible or queues a restart-time retry.
    private void changeLifecycle() {
        String pluginId = manifest.getId();
        boolean desiredEnabled = pluginManager.isPluginEnabled(pluginId);
        if (desiredEnabled) {
            try {
                pluginManager.disablePlugin(pluginId);
            } catch (IOException exception) {
                showStatusHint(MessageDialogPane.MessageType.ERROR, failureMessage(exception));
                return;
            }
        } else {
            pluginManager.enablePlugin(pluginId);
        }
        refreshStatus();
        refreshActionState();
        refreshParent.run();
    }

    /// Confirms removal and either removes immediately or marks the plugin for restart-time removal.
    private void confirmUninstall() {
        PluginDialogs.confirmAction(
                i18n("plugin.uninstall"),
                i18n(
                        "plugin.uninstall_confirm.detail",
                        manifest.getName(),
                        manifest.getVersion()
                ),
                i18n("plugin.uninstall"),
                this::uninstall
        );
    }

    /// Removes the plugin using the manager's restart-aware uninstall policy.
    private void uninstall() {
        String pluginId = manifest.getId();
        if (pluginManager.requiresRestartForUninstall(pluginId)) {
            pluginManager.markForUninstall(pluginId);
            refreshStatus();
            refreshActionState();
            refreshParent.run();
            return;
        }

        Task.runAsync(() -> {
            try {
                pluginManager.uninstallPlugin(pluginId);
            } catch (IOException exception) {
                throw new RuntimeException(exception);
            }
        }).whenComplete(Schedulers.javafx(), (@Nullable var result, @Nullable var exception) -> {
            if (exception != null) {
                PluginDialogs.showError(i18n("plugin.uninstall_failed"), failureMessage(exception));
                return;
            }
            refreshParent.run();
            fireEvent(new PageCloseEvent());
        }).start();
    }

    /// Refreshes lifecycle text, the single status message, and the enable or disable command.
    private void refreshStatus() {
        statusRow.setLeading(SVG.INFO);
        statusRow.setTitle(i18n("plugin.status"));
        statusRow.setMouseTransparent(true);

        PluginRuntimeStatus status = pluginManager.getPluginRuntimeStatus(manifest.getId());
        statusRow.setTrailingText(runtimeStatusLabel(status));
        boolean executableArtifact = isExecutableSchema(manifest.getSchemaVersion());
        lifecycleActionAvailable = executableArtifact && status != PluginRuntimeStatus.PENDING_UNINSTALL;
        lifecycleButton.setManaged(executableArtifact);
        lifecycleButton.setVisible(executableArtifact);
        boolean desiredEnabled = pluginManager.isPluginEnabled(manifest.getId());
        lifecycleButton.setText(i18n(desiredEnabled ? "plugin.disable" : "plugin.enable"));
        refreshRuntimeStatusHint(status);
    }

    /// Updates control ownership and restart visibility from the current transaction and runtime state.
    private void refreshActionState() {
        boolean executableArtifact = isExecutableSchema(manifest.getSchemaVersion());
        permissionPane.setPermissionControlsDisabled(savingPermissions || !executableArtifact);
        applyButton.setManaged(executableArtifact);
        applyButton.setVisible(executableArtifact);
        applyButton.setDisable(savingPermissions
                || !executableArtifact
                || permissionPane.getGrantedPermissions().equals(savedPermissions));
        lifecycleButton.setDisable(savingPermissions || !lifecycleActionAvailable);
        uninstallButton.setDisable(savingPermissions);
        restartButton.setDisable(savingPermissions);

        PluginRuntimeStatus status = pluginManager.getPluginRuntimeStatus(manifest.getId());
        boolean showRestart = restartRequired
                || status == PluginRuntimeStatus.WAITING_FOR_RESTART
                || status == PluginRuntimeStatus.BLOCKED_AGENT
                || status == PluginRuntimeStatus.PENDING_UNINSTALL;
        restartButton.setManaged(showRestart);
        restartButton.setVisible(showRestart);
    }

    /// Atomically gives or releases ownership of every permission-page control to the save task.
    ///
    /// @param saving whether a permission replacement is running
    private void setSavingPermissions(boolean saving) {
        savingPermissions = saving;
        refreshActionState();
    }

    /// Replaces the single status message with guidance for the exact current artifact state.
    ///
    /// @param status exact runtime state reported by the plugin manager
    private void refreshRuntimeStatusHint(PluginRuntimeStatus status) {
        @Nullable String detail = pluginManager.getPluginRuntimeDetail(manifest.getId());
        switch (status) {
            case BLOCKED_LEGACY -> showStatusHint(
                    MessageDialogPane.MessageType.WARNING,
                    appendDetail(i18n("plugin.runtime_status.blocked_legacy.detail"), detail)
            );
            case BLOCKED_REVOKED -> showStatusHint(
                    MessageDialogPane.MessageType.ERROR,
                    appendDetail(i18n("plugin.runtime_status.blocked_revoked.detail"), detail)
            );
            case BLOCKED_PERMISSION -> showStatusHint(
                    MessageDialogPane.MessageType.WARNING,
                    appendDetail(i18n("plugin.runtime_status.blocked_permission.detail"), detail)
            );
            case WAITING_FOR_RESTART -> showStatusHint(
                    MessageDialogPane.MessageType.INFO,
                    i18n("plugin.runtime_status.waiting_for_restart.detail")
            );
            case BLOCKED_AGENT -> showStatusHint(
                    MessageDialogPane.MessageType.ERROR,
                    appendDetail(i18n("plugin.runtime_status.blocked_agent.detail"), detail)
            );
            case LOAD_FAILED -> showStatusHint(
                    MessageDialogPane.MessageType.ERROR,
                    detail == null || detail.isBlank()
                            ? i18n("plugin.runtime_status.load_failed.detail")
                            : i18n("plugin.load_failure.detail", detail)
            );
            case PENDING_UNINSTALL -> showStatusHint(
                    MessageDialogPane.MessageType.INFO,
                    i18n("plugin.restart_required.uninstall")
            );
            case INSTALLED_DISABLED, ENABLED -> clearStatusHint();
        }
    }

    /// Returns the localized compact label shared by installed-plugin rows and permission details.
    ///
    /// @param status exact artifact runtime state
    /// @return localized compact status label
    static String runtimeStatusLabel(PluginRuntimeStatus status) {
        return i18n("plugin.runtime_status." + switch (status) {
            case INSTALLED_DISABLED -> "installed_disabled";
            case BLOCKED_LEGACY -> "blocked_legacy";
            case BLOCKED_REVOKED -> "blocked_revoked";
            case BLOCKED_PERMISSION -> "blocked_permission";
            case WAITING_FOR_RESTART -> "waiting_for_restart";
            case BLOCKED_AGENT -> "blocked_agent";
            case ENABLED -> "enabled";
            case LOAD_FAILED -> "load_failed";
            case PENDING_UNINSTALL -> "pending_uninstall";
        });
    }

    /// Returns whether the manifest schema can execute on this launcher build.
    ///
    /// @param schemaVersion plugin manifest schema generation
    /// @return whether lifecycle and permission controls may operate on the artifact
    static boolean isExecutableSchema(int schemaVersion) {
        return schemaVersion >= PluginManifest.MIN_EXECUTABLE_SCHEMA_VERSION
                && schemaVersion <= PluginManifest.CURRENT_SCHEMA_VERSION;
    }

    /// Adds an exact runtime diagnostic below stable user-facing guidance when one is available.
    ///
    /// @param message stable localized guidance
    /// @param detail optional artifact-bound diagnostic
    /// @return combined status text
    private static String appendDetail(String message, @Nullable String detail) {
        return detail == null || detail.isBlank() ? message : message + "\n" + detail;
    }

    /// Shows exactly one inline status pane and replaces any earlier runtime or operation message.
    ///
    /// @param type visual severity
    /// @param message localized message
    private void showStatusHint(MessageDialogPane.MessageType type, String message) {
        HintPane hint = new HintPane(type);
        hint.setText(message);
        statusHintContainer.getChildren().setAll(hint);
        statusHintContainer.setManaged(true);
        statusHintContainer.setVisible(true);
    }

    /// Removes the inline status pane for normal enabled and disabled states.
    private void clearStatusHint() {
        statusHintContainer.getChildren().clear();
        statusHintContainer.setManaged(false);
        statusHintContainer.setVisible(false);
    }

    /// Unwraps an asynchronous failure into a stable user-facing message.
    ///
    /// @param exception failure to unwrap
    /// @return root cause message
    private static String failureMessage(Throwable exception) {
        Throwable cause = exception;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        @Nullable String message = cause.getMessage();
        return message == null || message.isBlank() ? cause.toString() : message;
    }

    /// Returns the decorator navigation state.
    ///
    /// @return read-only page state
    @Override
    public ReadOnlyObjectProperty<State> stateProperty() {
        return state.getReadOnlyProperty();
    }
}
