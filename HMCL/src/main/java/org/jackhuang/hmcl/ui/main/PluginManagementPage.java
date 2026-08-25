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

import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.layout.*;
import javafx.scene.text.TextAlignment;
import javafx.stage.FileChooser;
import org.jackhuang.hmcl.plugin.LocalPluginInspection;
import org.jackhuang.hmcl.plugin.PluginContainer;
import org.jackhuang.hmcl.plugin.PluginDependency;
import org.jackhuang.hmcl.plugin.PluginManager;
import org.jackhuang.hmcl.plugin.PluginManifest;
import org.jackhuang.hmcl.plugin.PluginPermission;
import org.jackhuang.hmcl.plugin.PluginRuntimeStatus;
import org.jackhuang.hmcl.task.Schedulers;
import org.jackhuang.hmcl.task.Task;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.construct.ComponentList;
import org.jackhuang.hmcl.ui.construct.LineButton;
import org.jackhuang.hmcl.ui.decorator.DecoratorPage;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;
import static org.jackhuang.hmcl.util.logging.Logger.LOG;
import static org.jackhuang.hmcl.ui.ToolbarListPageSkin.createToolbarButton2;

/// Lists installed plugins and provides lifecycle and local installation actions.
@NotNullByDefault
public class PluginManagementPage extends VBox implements DecoratorPage {
    /// Maximum weighted width of a plugin name before the installed list replaces its tail with an ellipsis.
    private static final int ROW_TITLE_DISPLAY_UNITS = 40;

    /// Maximum weighted width of a plugin description before the installed list replaces its tail with an ellipsis.
    private static final int ROW_DESCRIPTION_DISPLAY_UNITS = 52;

    /// Stable width reserved for the complete version and runtime status at the minimum launcher window size.
    private static final double ROW_STATUS_WIDTH = 176;

    /// Stable installed-plugin row height with room for two summary and two status lines.
    private static final double PLUGIN_ROW_HEIGHT = 96;

    /// Decorator navigation state for the plugin management page.
    private final ReadOnlyObjectWrapper<State> state = new ReadOnlyObjectWrapper<>(State.fromTitle(i18n("plugin.manage")));

    /// Process-wide plugin lifecycle manager.
    private final PluginManager pluginManager = PluginManager.getInstance();

    /// Visual list containing installed plugin rows.
    private final ComponentList pluginList = new ComponentList();

    /// Conditional navigation banner for persisted startup recovery state.
    private final LineButton recoveryBanner = LineButton.createNavigationButton();

    /// Creates and populates the plugin management page.
    public PluginManagementPage() {
        getStyleClass().add("gray-background");
        setSpacing(8);
        setPadding(new Insets(10));

        // JavaFX exposes desktop file drops consistently on Windows, macOS,
        // X11 and Wayland-capable Linux desktops. Reuse HMCL's shared drag
        // listener so installing an .npl does not depend on platform-specific
        // native APIs or a file chooser implementation.
        FXUtils.applyDragListener(this,
                path -> Files.isRegularFile(path)
                        && Files.isReadable(path)
                        && path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".npl"),
                paths -> installPlugin(paths.get(0).toFile()));

        HBox topBar = new HBox(10);
        topBar.setAlignment(Pos.CENTER_LEFT);

        topBar.getChildren().addAll(
                createToolbarButton2(i18n("plugin.install"), SVG.ADD, this::installPlugin),
                createToolbarButton2(i18n("plugin.refresh"), SVG.REFRESH, this::refresh),
                createToolbarButton2(
                        i18n("plugin.open_folder"),
                        SVG.FOLDER_OPEN,
                        () -> FXUtils.openFolder(pluginManager.getPluginsDirectory())
                )
        );

        pluginList.getStyleClass().add("no-padding");

        recoveryBanner.setLeading(SVG.WARNING);
        recoveryBanner.setTitle(i18n("plugin.recovery.banner"));
        recoveryBanner.setSubtitle(i18n("plugin.recovery.banner.description"));
        recoveryBanner.setOnAction(event -> Controllers.navigate(new PluginRecoveryPage(this::refresh)));

        VBox content = new VBox(8);
        content.getChildren().addAll(
                ComponentList.createComponentListTitle(i18n("plugin.installed")),
                pluginList
        );

        ScrollPane scrollPane = new ScrollPane(content);
        scrollPane.setFitToWidth(true);
        scrollPane.setFitToHeight(true);
        VBox.setVgrow(scrollPane, Priority.ALWAYS);
        FXUtils.smoothScrolling(scrollPane);
        FXUtils.setOverflowHidden(scrollPane, 8);

        getChildren().addAll(topBar, recoveryBanner, scrollPane);

        refresh();
    }

    /// Rebuilds the installed plugin list from the manager's observable state.
    @Override
    public void refresh() {
        boolean recoveryAvailable = PluginRecoveryPage.isRecoveryAvailable(pluginManager);
        recoveryBanner.setManaged(recoveryAvailable);
        recoveryBanner.setVisible(recoveryAvailable);
        pluginList.getContent().clear();

        @Unmodifiable Map<String, PluginManifest> installedManifests;
        try {
            installedManifests = pluginManager.getPublishedPluginManifests();
        } catch (IOException exception) {
            LOG.warning("Failed to enumerate installed plugins", exception);
            LineButton failure = new LineButton();
            failure.setLeading(SVG.ERROR);
            failure.setTitle(i18n("plugin.installed.load_failed"));
            failure.setSubtitle(failureMessage(exception));
            failure.setMouseTransparent(true);
            pluginList.getContent().add(failure);
            return;
        }

        if (installedManifests.isEmpty()) {
            LineButton empty = new LineButton();
            empty.setLeading(SVG.INFO);
            empty.setTitle(i18n("plugin.empty"));
            empty.setSubtitle(i18n("plugin.empty.description"));
            empty.setMouseTransparent(true);
            pluginList.getContent().add(empty);
        } else {
            installedManifests.values().stream()
                    .sorted((left, right) -> left.getName().compareToIgnoreCase(right.getName()))
                    .forEach(manifest -> pluginList.getContent().add(createPluginItem(
                            manifest,
                            pluginManager.getPlugin(manifest.getId())
                    )));
        }
    }

    /// Builds one installed plugin row with status and lifecycle actions.
    ///
    /// @param manifest installed plugin manifest
    /// @param container loaded plugin container or `null`
    /// @return plugin row
    private LineButton createPluginItem(PluginManifest manifest, @Nullable PluginContainer container) {
        LineButton row = LineButton.createNavigationButton();
        @Nullable Image pluginIcon = pluginManager.getPluginIcon(manifest);
        if (pluginIcon == null) {
            row.setLeading(SVG.EXTENSION);
        } else {
            row.setLeading(pluginIcon, 42);
        }
        row.setTitle(summarizeDisplayText(manifest.getName(), ROW_TITLE_DISPLAY_UNITS));
        String subtitleSource = manifest.getDescription().isBlank()
                ? manifest.getId()
                : manifest.getDescription();
        row.setSubtitle(summarizeDisplayText(subtitleSource, ROW_DESCRIPTION_DISPLAY_UNITS));
        PluginRuntimeStatus runtimeStatus = pluginManager.getPluginRuntimeStatus(manifest.getId());
        @Nullable String runtimeDetail = pluginManager.getPluginRuntimeDetail(manifest.getId());
        row.setTrailingText(null);
        row.setTrailingIcon(createRuntimeStatusGroup(manifest.getVersion(), runtimeStatus));
        FXUtils.setLimitHeight(row, PLUGIN_ROW_HEIGHT);
        configurePluginRowTooltip(row, manifest, runtimeDetail);
        row.setOnAction(event -> Controllers.navigate(new PluginPermissionManagementPage(
                manifest,
                container,
                this::refresh
        )));
        return row;
    }

    /// Builds a fixed-width, right-aligned status group without the ellipsis behavior of `LineButton.trailingText`.
    ///
    /// @param version installed plugin version
    /// @param status exact artifact runtime state
    /// @return stable version, status, and navigation-arrow group
    private static HBox createRuntimeStatusGroup(String version, PluginRuntimeStatus status) {
        Label versionLabel = new Label("v" + version);
        versionLabel.getStyleClass().add("subtitle-label");
        versionLabel.setAlignment(Pos.CENTER_RIGHT);
        versionLabel.setMaxWidth(Double.MAX_VALUE);

        Label statusLabel = new Label(PluginPermissionManagementPage.runtimeStatusLabel(status));
        statusLabel.getStyleClass().add("trailing-label");
        statusLabel.setAlignment(Pos.CENTER_RIGHT);
        statusLabel.setTextAlignment(TextAlignment.RIGHT);
        statusLabel.setWrapText(true);
        statusLabel.setMinHeight(Region.USE_PREF_SIZE);
        statusLabel.setMaxWidth(Double.MAX_VALUE);

        VBox statusText = new VBox(2, versionLabel, statusLabel);
        statusText.setAlignment(Pos.CENTER_RIGHT);
        FXUtils.setLimitWidth(statusText, ROW_STATUS_WIDTH);

        Node arrow = SVG.ARROW_FORWARD.createIcon(20);
        arrow.getStyleClass().add("trailing-icon");
        arrow.setMouseTransparent(true);

        HBox trailing = new HBox(8, statusText, arrow);
        trailing.setAlignment(Pos.CENTER_RIGHT);
        trailing.setMinWidth(Region.USE_PREF_SIZE);
        trailing.setAccessibleText(versionLabel.getText() + ", " + statusLabel.getText());
        return trailing;
    }

    /// Adds complete metadata and artifact-bound diagnostics without placing long prose in the row layout.
    ///
    /// @param row installed plugin navigation row
    /// @param manifest installed plugin manifest
    /// @param runtimeDetail exact runtime diagnostic or `null`
    private static void configurePluginRowTooltip(
            LineButton row,
            PluginManifest manifest,
            @Nullable String runtimeDetail
    ) {
        List<String> sections = new ArrayList<>();
        sections.add(manifest.getName());
        if (!manifest.getDescription().isBlank()) {
            sections.add(manifest.getDescription().trim());
        }
        if (runtimeDetail != null && !runtimeDetail.isBlank()) {
            sections.add(runtimeDetail.trim());
        }
        Tooltip tooltip = new Tooltip(String.join("\n\n", sections));
        tooltip.setWrapText(true);
        tooltip.setMaxWidth(520);
        Tooltip.install(row, tooltip);
    }

    /// Normalizes one row string and bounds its weighted display width for the minimum launcher window.
    ///
    /// ASCII code points count as one unit and wider Unicode code points count as two. The returned value is always a
    /// single normalized paragraph; callers rely on the row width to wrap it to at most two short visual lines.
    ///
    /// @param value source text
    /// @param maximumUnits maximum weighted display width
    /// @return normalized and possibly ellipsized text
    static String summarizeDisplayText(String value, int maximumUnits) {
        if (maximumUnits < 3) {
            throw new IllegalArgumentException("maximumUnits must be at least 3");
        }
        String normalized = value.trim().replaceAll("\\s+", " ");
        if (displayUnits(normalized) <= maximumUnits) {
            return normalized;
        }

        StringBuilder result = new StringBuilder();
        int usedUnits = 0;
        int contentLimit = maximumUnits - 2;
        for (int offset = 0; offset < normalized.length();) {
            int codePoint = normalized.codePointAt(offset);
            int codePointUnits = codePoint <= 0x7f ? 1 : 2;
            if (usedUnits + codePointUnits > contentLimit) {
                break;
            }
            result.appendCodePoint(codePoint);
            usedUnits += codePointUnits;
            offset += Character.charCount(codePoint);
        }
        return result.toString().stripTrailing() + "\u2026";
    }

    /// Counts deterministic narrow and wide display units for installed-plugin row summaries.
    ///
    /// @param value normalized row text
    /// @return weighted display width
    private static int displayUnits(String value) {
        return value.codePoints().map(codePoint -> codePoint <= 0x7f ? 1 : 2).sum();
    }

    /// Opens a file chooser for one local `.npl` package.
    private void installPlugin() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle(i18n("plugin.install"));
        fileChooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter(i18n("plugin.file"), "*.npl")
        );

        @Nullable File file = fileChooser.showOpenDialog(Controllers.getStage());
        if (file != null) {
            installPlugin(file);
        }
    }

    /// Inspects a selected package and asks for metadata-aware confirmation before installation mutates state.
    ///
    /// @param file selected local package
    private void installPlugin(File file) {
        Path pluginPath = file.toPath();

        if (!Files.isRegularFile(pluginPath) || !Files.isReadable(pluginPath)) {
            showInstallError(new IOException("Plugin package is not readable: " + pluginPath));
            return;
        }

        PluginDialogs.ProgressDialog inspectionDialog = PluginDialogs.showProgress(
                i18n("plugin.install"),
                i18n("plugin.local.inspecting")
        );

        Task.supplyAsync(() -> {
            try {
                return pluginManager.inspectLocalPluginPackage(pluginPath);
            } catch (IOException exception) {
                LOG.error("Failed to inspect local plugin package", exception);
                throw new RuntimeException(exception);
            }
        }).whenComplete(Schedulers.javafx(), (@Nullable var inspection, @Nullable var exception) -> {
            inspectionDialog.close();
            if (exception != null) {
                showInstallError(exception);
                return;
            }
            confirmLocalPluginInstallation(inspection);
        }).start();
    }

    /// Shows package permissions and update deltas, then stages the exact inspected bytes for restart.
    ///
    /// @param inspection immutable package inspection produced before any installation mutation
    private void confirmLocalPluginInstallation(LocalPluginInspection inspection) {
        PluginManifest manifest = inspection.getManifest();
        String pluginName = manifest.getName().isBlank()
                ? inspection.getSourcePackage().getFileName().toString()
                : manifest.getName();

        @Unmodifiable Set<PluginPermission> suggestedPermissions;
        try {
            suggestedPermissions = pluginManager.getSuggestedGrantedPermissions(inspection);
        } catch (IOException exception) {
            showInstallError(exception);
            return;
        }

        @Nullable PluginManifest oldManifest = inspection.getOldManifest();

        PluginPermissionRequest permissionRequest = new PluginPermissionRequest(
                manifest.getId(),
                pluginName,
                manifest.getVersion(),
                manifest.getRequiredPermissions(),
                manifest.getOptionalPermissions(),
                suggestedPermissions,
                true,
                oldManifest != null,
                oldManifest == null ? List.of() : oldManifest.getRequiredPermissions(),
                oldManifest == null ? List.of() : oldManifest.getOptionalPermissions()
        );

        PluginDialogs.confirmPluginInstall(
                pluginName,
                oldManifest != null,
                List.of(permissionRequest),
                formatLocalInstallPlan(inspection),
                grantsByPluginId -> {
                    @Unmodifiable Set<PluginPermission> grantedPermissions =
                            grantsByPluginId.getOrDefault(manifest.getId(), Set.of());
                    Task.runAsync(() -> {
                        try {
                            pluginManager.stagePluginInstallations(
                                    List.of(inspection),
                                    Map.of(manifest.getId(), grantedPermissions)
                            );
                        } catch (IOException exception) {
                            LOG.error("Failed to stage plugin", exception);
                            throw new RuntimeException(exception);
                        }
                    }).whenComplete(Schedulers.javafx(), (@Nullable var result, @Nullable var exception) -> {
                        if (exception != null) {
                            showInstallError(exception);
                        } else {
                            refresh();
                            PluginDialogs.showInstallFinishedAndOfferRestart(pluginName);
                        }
                    }).start();
                }
        );
    }

    /// Formats the root package action and declared dependency constraints for local confirmation.
    ///
    /// @param inspection inspected package and optional installed manifest
    /// @return immutable confirmation plan rows
    private static @Unmodifiable List<String> formatLocalInstallPlan(
            LocalPluginInspection inspection
    ) {
        PluginManifest manifest = inspection.getManifest();
        @Nullable PluginManifest oldManifest = inspection.getOldManifest();
        List<String> rows = new ArrayList<>();
        if (oldManifest == null) {
            rows.add(i18n("plugin.local.plan.install", manifest.getName(), manifest.getVersion()));
        } else {
            rows.add(i18n(
                    "plugin.local.plan.update",
                    manifest.getName(),
                    oldManifest.getVersion(),
                    manifest.getVersion()
            ));
        }
        rows.add(i18n("plugin.local.plan.launcher", manifest.getLauncherVersion()));
        for (PluginDependency dependency : manifest.getPluginDependencies()) {
            rows.add(i18n(
                    "plugin.local.plan.dependency",
                    dependency.getId(),
                    dependency.getVersion()
            ));
        }
        return List.copyOf(rows);
    }

    /// Shows the root cause of a local installation failure.
    ///
    /// @param exception asynchronous or direct installation failure
    private void showInstallError(Throwable exception) {
        PluginDialogs.showError(i18n("plugin.install_failed"), failureMessage(exception));
    }

    /// Unwraps nested asynchronous failures into a stable message.
    ///
    /// @param exception failure to unwrap
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
    /// @return read-only page state
    @Override
    public ReadOnlyObjectProperty<State> stateProperty() {
        return state.getReadOnlyProperty();
    }
}
