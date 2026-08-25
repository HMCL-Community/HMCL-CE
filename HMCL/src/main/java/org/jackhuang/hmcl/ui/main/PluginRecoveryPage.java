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
import com.jfoenix.controls.JFXCheckBox;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Control;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import org.jackhuang.hmcl.plugin.PluginManager;
import org.jackhuang.hmcl.plugin.PluginQuarantineReport;
import org.jackhuang.hmcl.task.Schedulers;
import org.jackhuang.hmcl.task.Task;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.construct.ComponentList;
import org.jackhuang.hmcl.ui.construct.HintPane;
import org.jackhuang.hmcl.ui.construct.LineButton;
import org.jackhuang.hmcl.ui.construct.MessageDialogPane;
import org.jackhuang.hmcl.ui.decorator.DecoratorPage;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;
import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// Presents secret-free startup recovery evidence and explicit quarantine restoration actions.
@NotNullByDefault
public final class PluginRecoveryPage extends VBox implements DecoratorPage {
    /// Decorator navigation state for the recovery page.
    private final ReadOnlyObjectWrapper<State> state =
            new ReadOnlyObjectWrapper<>(State.fromTitle(i18n("plugin.recovery.title")));

    /// Side-effect-free presentation and explicit mutation router.
    private final ActionModel actionModel;

    /// Callback used by an owning management surface after recovery state changes.
    private final Runnable recoveryStateChanged;

    /// Secret-free failure detail rows.
    private final ComponentList detailList = new ComponentList();

    /// Quarantined plugin selection and single-restore rows.
    private final ComponentList pluginList = new ComponentList();

    /// Selection controls rebuilt from the latest persisted quarantine state.
    private final List<JFXCheckBox> pluginSelectors = new ArrayList<>();

    /// Every restore control owned by the active mutation.
    private final List<Control> restoreControls = new ArrayList<>();

    /// Restores the checked plugin group and its combined dependency closure.
    private final JFXButton restoreSelectedButton = new JFXButton(i18n("plugin.recovery.restore.selected"));

    /// Restores the complete quarantined graph.
    private final JFXButton restoreAllButton = new JFXButton(i18n("plugin.recovery.restore.all"));

    /// Whether one confirmed restore mutation currently owns all restore controls.
    private boolean mutationRunning;

    /// Creates the process-wide plugin recovery page.
    public PluginRecoveryPage() {
        this(new PluginManagerBackend(PluginManager.getInstance()), () -> {
        });
    }

    /// Creates the process-wide recovery page with a state-change callback for its owner.
    ///
    /// @param recoveryStateChanged callback invoked after a successful restore
    PluginRecoveryPage(Runnable recoveryStateChanged) {
        this(new PluginManagerBackend(PluginManager.getInstance()), recoveryStateChanged);
    }

    /// Creates a recovery page backed by an explicit state owner.
    ///
    /// @param backend recovery state and mutation backend
    /// @param recoveryStateChanged callback invoked after a successful restore
    PluginRecoveryPage(RecoveryBackend backend, Runnable recoveryStateChanged) {
        actionModel = new ActionModel(backend);
        this.recoveryStateChanged = recoveryStateChanged;

        getStyleClass().add("gray-background");
        setSpacing(10);
        setPadding(new Insets(10));

        HintPane warning = new HintPane(MessageDialogPane.MessageType.WARNING);
        warning.setText(i18n("plugin.recovery.explanation"));

        detailList.getStyleClass().add("no-padding");
        pluginList.getStyleClass().add("no-padding");

        VBox content = new VBox(8,
                ComponentList.createComponentListTitle(i18n("plugin.recovery.details")),
                detailList,
                ComponentList.createComponentListTitle(i18n("plugin.recovery.plugins")),
                pluginList
        );
        ScrollPane scrollPane = new ScrollPane(content);
        scrollPane.setFitToWidth(true);
        scrollPane.setFitToHeight(true);
        VBox.setVgrow(scrollPane, Priority.ALWAYS);
        FXUtils.smoothScrolling(scrollPane);
        FXUtils.setOverflowHidden(scrollPane, 8);

        restoreSelectedButton.setGraphic(SVG.RESTORE.createIcon(18));
        restoreSelectedButton.setOnAction(event -> confirmRestoreSelected());
        restoreAllButton.setGraphic(SVG.RESTORE.createIcon(18));
        restoreAllButton.setOnAction(event -> confirmRestoreAll());
        restoreControls.add(restoreSelectedButton);
        restoreControls.add(restoreAllButton);

        HBox actions = new HBox(8, restoreSelectedButton, restoreAllButton);
        actions.setAlignment(Pos.CENTER_RIGHT);
        getChildren().addAll(warning, scrollPane, actions);
        refresh();
    }

    /// Reports whether the process-wide manager currently has actionable recovery state.
    ///
    /// @return whether a report and quarantined plugin are both present
    static boolean isRecoveryAvailable() {
        return isRecoveryAvailable(PluginManager.getInstance());
    }

    /// Reports whether one manager currently has actionable recovery state.
    ///
    /// @param pluginManager plugin manager to inspect without mutation
    /// @return whether a report and quarantined plugin are both present
    static boolean isRecoveryAvailable(PluginManager pluginManager) {
        return new ActionModel(new PluginManagerBackend(pluginManager)).hasRecovery();
    }

    /// Rebuilds failure evidence and quarantine controls from persisted manager state.
    @Override
    public void refresh() {
        detailList.getContent().clear();
        pluginList.getContent().clear();
        pluginSelectors.clear();
        restoreControls.removeIf(control -> control != restoreSelectedButton && control != restoreAllButton);

        Optional<Presentation> current = actionModel.presentation();
        if (current.isEmpty() || current.orElseThrow().quarantinedPluginIds().isEmpty()) {
            LineButton empty = new LineButton();
            empty.setLeading(SVG.CHECK_CIRCLE);
            empty.setTitle(i18n("plugin.recovery.empty"));
            empty.setSubtitle(i18n("plugin.recovery.empty.description"));
            empty.setMouseTransparent(true);
            pluginList.getContent().add(empty);
            refreshActionState();
            return;
        }

        Presentation presentation = current.orElseThrow();
        presentation.details().forEach(detail -> detailList.getContent().add(createDetailRow(detail)));
        presentation.quarantinedPluginIds().forEach(
                pluginId -> pluginList.getContent().add(createPluginRow(pluginId))
        );
        refreshActionState();
    }

    /// Builds one read-only allowlisted recovery detail row.
    ///
    /// @param detail recovery detail
    /// @return configured read-only row
    private static LineButton createDetailRow(Detail detail) {
        LineButton row = new LineButton();
        row.setLeading(SVG.INFO);
        row.setTitle(i18n("plugin.recovery.detail." + detail.kind().translationSuffix()));
        row.setSubtitle(formatDetailValue(detail));
        row.setMouseTransparent(true);
        return row;
    }

    /// Formats one approved detail for the current locale without adding report fields.
    ///
    /// @param detail approved recovery detail
    /// @return localized display value
    private static String formatDetailValue(Detail detail) {
        if (detail.kind() == DetailKind.FAILURE_TIME) {
            Instant instant = Instant.ofEpochMilli(Long.parseLong(detail.value()));
            return DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
                    .withLocale(Locale.getDefault())
                    .withZone(ZoneId.systemDefault())
                    .format(instant);
        }
        if (detail.kind() == DetailKind.RETAINED_FILES) {
            return i18n("plugin.recovery.retained_files.value");
        }
        return detail.value();
    }

    /// Builds one selectable quarantined plugin row with an icon-only single restore command.
    ///
    /// @param pluginId quarantined plugin ID
    /// @return configured selection row
    private HBox createPluginRow(String pluginId) {
        JFXCheckBox selector = new JFXCheckBox(pluginId);
        selector.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(selector, Priority.ALWAYS);
        selector.selectedProperty().addListener((observable, oldValue, newValue) -> refreshActionState());

        JFXButton restoreOneButton = new JFXButton();
        restoreOneButton.getStyleClass().add("toggle-icon4");
        restoreOneButton.setGraphic(SVG.RESTORE.createIcon(18));
        restoreOneButton.setTooltip(new Tooltip(i18n("plugin.recovery.restore.one")));
        restoreOneButton.setOnAction(event -> confirmRestoreOne(pluginId));

        pluginSelectors.add(selector);
        restoreControls.add(selector);
        restoreControls.add(restoreOneButton);

        HBox row = new HBox(8, selector, restoreOneButton);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(8, 12, 8, 12));
        ComponentList.setNoPadding(row);
        return row;
    }

    /// Confirms restoration of one exact plugin and its required closure.
    ///
    /// @param pluginId selected quarantined plugin ID
    private void confirmRestoreOne(String pluginId) {
        confirmRestore(
                i18n("plugin.recovery.confirm.one", pluginId),
                () -> actionModel.restoreOne(pluginId)
        );
    }

    /// Confirms restoration of the current selected group and its combined closure.
    private void confirmRestoreSelected() {
        Set<String> selectedIds = new HashSet<>();
        pluginSelectors.stream()
                .filter(JFXCheckBox::isSelected)
                .map(JFXCheckBox::getText)
                .forEach(selectedIds::add);
        if (selectedIds.isEmpty()) {
            return;
        }
        confirmRestore(
                i18n("plugin.recovery.confirm.selected", selectedIds.size()),
                () -> actionModel.restoreSelected(Set.copyOf(selectedIds))
        );
    }

    /// Confirms restoration of every currently quarantined plugin.
    private void confirmRestoreAll() {
        confirmRestore(
                i18n("plugin.recovery.confirm.all"),
                actionModel::restoreAll
        );
    }

    /// Opens the shared confirmation dialog before one recovery mutation begins.
    ///
    /// @param message localized mutation scope
    /// @param action exact restore action
    private void confirmRestore(String message, Callable<@Unmodifiable List<String>> action) {
        PluginDialogs.confirmAction(
                i18n("plugin.recovery.confirm.title"),
                message,
                i18n("plugin.recovery.confirm.action"),
                () -> runRestore(action)
        );
    }

    /// Runs a confirmed recovery mutation off the JavaFX thread and refreshes persisted state afterward.
    ///
    /// @param action exact restore action
    private void runRestore(Callable<@Unmodifiable List<String>> action) {
        setMutationRunning(true);
        Task.supplyAsync(action).whenComplete(
                Schedulers.javafx(),
                (@Nullable List<String> restored, @Nullable Exception exception) -> {
                    setMutationRunning(false);
                    if (exception != null) {
                        LOG.warning("Failed to restore quarantined plugins", exception);
                        PluginDialogs.showError(
                                i18n("plugin.recovery.restore.failed"),
                                failureMessage(exception)
                        );
                        refresh();
                        return;
                    }
                    refresh();
                    recoveryStateChanged.run();
                    Controllers.showToast(i18n("plugin.recovery.restore.success", restored.size()));
                }
        ).start();
    }

    /// Atomically gives or releases every restore control to the active mutation.
    ///
    /// @param running whether a restore mutation owns the controls
    private void setMutationRunning(boolean running) {
        mutationRunning = running;
        refreshActionState();
    }

    /// Applies the current mutation and selection state to every restore control.
    private void refreshActionState() {
        restoreControls.forEach(control -> control.setDisable(mutationRunning));
        boolean hasPlugins = !pluginSelectors.isEmpty();
        boolean hasSelection = pluginSelectors.stream().anyMatch(JFXCheckBox::isSelected);
        restoreSelectedButton.setDisable(mutationRunning || !hasSelection);
        restoreAllButton.setDisable(mutationRunning || !hasPlugins);
    }

    /// Unwraps an asynchronous restore failure into a stable user-visible message.
    ///
    /// @param exception asynchronous restore failure
    /// @return root-cause message
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
    /// @return read-only recovery page state
    @Override
    public ReadOnlyObjectProperty<State> stateProperty() {
        return state.getReadOnlyProperty();
    }

    /// Supplies recovery state and performs only explicitly requested restore mutations.
    @NotNullByDefault
    public interface RecoveryBackend {
        /// Returns the persisted startup recovery report.
        ///
        /// @return persisted report, or empty when no startup recovery remains
        Optional<PluginQuarantineReport> getReport();

        /// Returns plugin IDs that remain quarantined.
        ///
        /// @return immutable quarantined IDs
        @Unmodifiable Set<String> getQuarantinedPluginIds();

        /// Restores one plugin and its executable dependency closure.
        ///
        /// @param pluginId selected plugin ID
        /// @return immutable provider-first restored closure
        /// @throws IOException if the closure cannot be validated or persisted
        @Unmodifiable List<String> restoreOne(String pluginId) throws IOException;

        /// Restores selected plugins and their combined executable dependency closure.
        ///
        /// @param pluginIds selected plugin IDs
        /// @return immutable provider-first restored closure
        /// @throws IOException if the closure cannot be validated or persisted
        @Unmodifiable List<String> restoreSelected(@Unmodifiable Set<String> pluginIds) throws IOException;

        /// Restores every quarantined plugin through one dependency-consistent mutation.
        ///
        /// @return immutable provider-first restored closure
        /// @throws IOException if the closure cannot be validated or persisted
        @Unmodifiable List<String> restoreAll() throws IOException;
    }

    /// Exposes side-effect-free recovery presentation and exact explicit restore routing.
    @NotNullByDefault
    public static final class ActionModel {
        /// Backend that owns recovery state and mutations.
        private final RecoveryBackend backend;

        /// Creates a recovery action model without reading or mutating backend state.
        ///
        /// @param backend recovery state and mutation backend
        public ActionModel(RecoveryBackend backend) {
            this.backend = backend;
        }

        /// Builds the current secret-free presentation without restoring or enabling plugins.
        ///
        /// @return current presentation, or empty when no recovery report exists
        public Optional<Presentation> presentation() {
            return backend.getReport().map(report -> Presentation.from(
                    report,
                    backend.getQuarantinedPluginIds()
            ));
        }

        /// Reports whether a recovery report and at least one quarantined plugin are both present.
        ///
        /// @return whether explicit recovery actions are currently available
        public boolean hasRecovery() {
            return presentation().filter(value -> !value.quarantinedPluginIds().isEmpty()).isPresent();
        }

        /// Restores one exact selected plugin through the backend.
        ///
        /// @param pluginId selected plugin ID
        /// @return immutable restored closure
        /// @throws IOException if the restore cannot be completed
        public @Unmodifiable List<String> restoreOne(String pluginId) throws IOException {
            return List.copyOf(backend.restoreOne(pluginId));
        }

        /// Restores one exact selected group through the backend.
        ///
        /// @param pluginIds selected plugin IDs
        /// @return immutable restored closure
        /// @throws IOException if the restore cannot be completed
        public @Unmodifiable List<String> restoreSelected(@Unmodifiable Set<String> pluginIds) throws IOException {
            return List.copyOf(backend.restoreSelected(Set.copyOf(pluginIds)));
        }

        /// Restores every quarantined plugin through the backend.
        ///
        /// @return immutable restored closure
        /// @throws IOException if the restore cannot be completed
        public @Unmodifiable List<String> restoreAll() throws IOException {
            return List.copyOf(backend.restoreAll());
        }
    }

    /// Immutable recovery information approved for presentation to launcher users.
    ///
    /// @param details ordered secret-free failure and retained-file details
    /// @param quarantinedPluginIds ordered plugin IDs that remain quarantined
    @NotNullByDefault
    public record Presentation(
            @Unmodifiable List<Detail> details,
            @Unmodifiable List<String> quarantinedPluginIds
    ) {
        /// Captures immutable presentation data.
        public Presentation {
            details = List.copyOf(details);
            quarantinedPluginIds = List.copyOf(quarantinedPluginIds);
        }

        /// Converts a strict quarantine report into the approved display allowlist.
        ///
        /// @param report persisted secret-free recovery report
        /// @param quarantinedPluginIds IDs that remain quarantined
        /// @return immutable ordered presentation
        private static Presentation from(
                PluginQuarantineReport report,
                @Unmodifiable Set<String> quarantinedPluginIds
        ) {
            return new Presentation(
                    List.of(
                            new Detail(DetailKind.FAILURE_TIME, Long.toString(report.failureTimestampEpochMillis())),
                            new Detail(DetailKind.FAILURE_REASON, report.failureReason().name()),
                            new Detail(DetailKind.LAST_STAGE, report.lastStage().name()),
                            new Detail(DetailKind.LAST_HEARTBEAT,
                                    Long.toString(report.lastHeartbeatMonotonicNanos())),
                            new Detail(DetailKind.ACTIVE_PROVIDER, displayNullable(report.activeProviderId())),
                            new Detail(DetailKind.ACTIVE_PLUGIN, displayNullable(report.activePluginId())),
                            new Detail(DetailKind.LAUNCHER_LOG, displayNullable(report.launcherLogReference())),
                            new Detail(DetailKind.DIAGNOSTIC_DUMP,
                                    displayNullable(report.diagnosticDumpReference())),
                            new Detail(DetailKind.RETAINED_FILES, "packages, configuration, data")
                    ),
                    quarantinedPluginIds.stream().sorted().toList()
            );
        }

        /// Converts an absent optional report field into a stable display value.
        ///
        /// @param value optional report field
        /// @return field value or an empty display marker
        private static String displayNullable(@org.jetbrains.annotations.Nullable String value) {
            return value == null ? "-" : value;
        }
    }

    /// One allowlisted recovery detail.
    ///
    /// @param kind stable detail identity
    /// @param value secret-free display value
    @NotNullByDefault
    public record Detail(DetailKind kind, String value) {
    }

    /// Stable allowlist of recovery details that may be rendered by the launcher.
    @NotNullByDefault
    public enum DetailKind {
        /// Wall-clock time of the failed startup.
        FAILURE_TIME,
        /// Controlled reason recorded by the startup protector.
        FAILURE_REASON,
        /// Last authenticated startup stage.
        LAST_STAGE,
        /// Last authenticated monotonic heartbeat value.
        LAST_HEARTBEAT,
        /// Runtime Provider active when startup failed.
        ACTIVE_PROVIDER,
        /// Ordinary plugin active when startup failed.
        ACTIVE_PLUGIN,
        /// Launcher-local log reference.
        LAUNCHER_LOG,
        /// Launcher-local diagnostic dump reference.
        DIAGNOSTIC_DUMP,
        /// Summary of retained plugin-owned files.
        RETAINED_FILES;

        /// Returns the lower-case translation-key suffix for this allowlisted detail.
        ///
        /// @return stable translation suffix
        private String translationSuffix() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /// Adapts the process plugin manager to the narrow recovery backend contract.
    @NotNullByDefault
    private static final class PluginManagerBackend implements RecoveryBackend {
        /// Plugin manager owning the persisted quarantine state.
        private final PluginManager pluginManager;

        /// Creates a manager-backed recovery adapter.
        ///
        /// @param pluginManager plugin manager state owner
        private PluginManagerBackend(PluginManager pluginManager) {
            this.pluginManager = pluginManager;
        }

        /// Returns the persisted secret-free recovery report.
        ///
        /// @return persisted report, or empty before recovery
        @Override
        public Optional<PluginQuarantineReport> getReport() {
            return pluginManager.getQuarantineReport();
        }

        /// Returns immutable quarantined plugin IDs.
        ///
        /// @return quarantined plugin IDs
        @Override
        public @Unmodifiable Set<String> getQuarantinedPluginIds() {
            return pluginManager.getQuarantinedPluginIds();
        }

        /// Restores one plugin through manager dependency planning.
        ///
        /// @param pluginId selected plugin ID
        /// @return provider-first restored closure
        /// @throws IOException if validation or persistence fails
        @Override
        public @Unmodifiable List<String> restoreOne(String pluginId) throws IOException {
            return pluginManager.restoreQuarantinedPlugin(pluginId);
        }

        /// Restores a selected group through manager dependency planning.
        ///
        /// @param pluginIds selected plugin IDs
        /// @return provider-first restored closure
        /// @throws IOException if validation or persistence fails
        @Override
        public @Unmodifiable List<String> restoreSelected(@Unmodifiable Set<String> pluginIds) throws IOException {
            return pluginManager.restoreQuarantinedPlugins(pluginIds);
        }

        /// Restores all quarantined plugins through manager dependency planning.
        ///
        /// @return provider-first restored closure
        /// @throws IOException if validation or persistence fails
        @Override
        public @Unmodifiable List<String> restoreAll() throws IOException {
            return pluginManager.restoreAllQuarantinedPlugins();
        }
    }
}
