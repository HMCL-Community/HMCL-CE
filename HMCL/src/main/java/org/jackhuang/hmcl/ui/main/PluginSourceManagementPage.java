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
import com.jfoenix.controls.JFXDialogLayout;
import com.jfoenix.controls.JFXPopup;
import com.jfoenix.controls.JFXTextField;
import com.jfoenix.controls.JFXToggleButton;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.jackhuang.hmcl.plugin.store.PluginSource;
import org.jackhuang.hmcl.plugin.store.PluginSourceLoadExecutor;
import org.jackhuang.hmcl.plugin.store.PluginSourceLoadResult;
import org.jackhuang.hmcl.plugin.store.PluginSourceLabels;
import org.jackhuang.hmcl.plugin.store.PluginSourceRepository;
import org.jackhuang.hmcl.plugin.store.PluginStoreManager;
import org.jackhuang.hmcl.plugin.store.PluginStoreRegistry;
import org.jackhuang.hmcl.plugin.store.PluginStoreSnapshot;
import org.jackhuang.hmcl.task.Schedulers;
import org.jackhuang.hmcl.task.Task;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.construct.ComponentList;
import org.jackhuang.hmcl.ui.construct.DialogCloseEvent;
import org.jackhuang.hmcl.ui.construct.IconedMenuItem;
import org.jackhuang.hmcl.ui.construct.LineButton;
import org.jackhuang.hmcl.ui.construct.MenuSeparator;
import org.jackhuang.hmcl.ui.construct.PopupMenu;
import org.jackhuang.hmcl.ui.decorator.DecoratorPage;
import org.jackhuang.hmcl.util.StringUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

import static org.jackhuang.hmcl.ui.ToolbarListPageSkin.createToolbarButton2;
import static org.jackhuang.hmcl.util.i18n.I18n.i18n;

/// Manages ordered, independently loadable plugin sources without retaining a persistent catalog cache.
@NotNullByDefault
public final class PluginSourceManagementPage extends VBox implements DecoratorPage {
    /// Decorator navigation state for the source-management page.
    private final ReadOnlyObjectWrapper<State> state =
            new ReadOnlyObjectWrapper<>(State.fromTitle(i18n("plugin.store.settings")));

    /// Transactional source and favorite persistence shared with the plugin store.
    private final PluginSourceRepository repository;

    /// Most recently completed aggregate snapshot supplied by the store page, or `null` before one completes.
    private final Supplier<@Nullable PluginStoreSnapshot> snapshotSupplier;

    /// Installed plugin IDs used to select a stronger deletion warning.
    private final Supplier<@Unmodifiable Set<String>> installedPluginIdsSupplier;

    /// Callback that refreshes the plugin store after a persisted source mutation.
    private final Runnable refreshStore;

    /// Scrollable collection of compact source rows.
    private final ComponentList sourceList = new ComponentList();

    /// Adds a source without allowing concurrent repository writes.
    private final JFXButton addButton = createToolbarButton2(i18n("button.add"), SVG.ADD, () -> showEditor(null));

    /// Prevents concurrent controls from publishing changes before the active repository write completes.
    private boolean writing;

    /// Transient source test outcomes retained only until a newer aggregate snapshot is published.
    private final Map<String, PluginSourceLoadResult> testedResults = new LinkedHashMap<>();

    /// Aggregate generation current when each manual result was published.
    private final Map<String, Long> testedResultSnapshotGenerations = new LinkedHashMap<>();

    /// Latest independent test generation requested for each source ID.
    private final Map<String, Long> testGenerations = new LinkedHashMap<>();

    /// Configuration generation invalidating test requests after any successful source mutation.
    private long sourceConfigurationGeneration;

    /// Creates the source-management page using shared persistence and store refresh dependencies.
    ///
    /// @param repository transactional source repository
    /// @param snapshotSupplier current aggregate snapshot supplier
    /// @param installedPluginIdsSupplier installed plugin ID snapshot supplier
    /// @param refreshStore callback that refreshes the store after a persisted change
    public PluginSourceManagementPage(
            PluginSourceRepository repository,
            Supplier<@Nullable PluginStoreSnapshot> snapshotSupplier,
            Supplier<@Unmodifiable Set<String>> installedPluginIdsSupplier,
            Runnable refreshStore
    ) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.snapshotSupplier = Objects.requireNonNull(snapshotSupplier, "snapshotSupplier");
        this.installedPluginIdsSupplier = Objects.requireNonNull(installedPluginIdsSupplier, "installedPluginIdsSupplier");
        this.refreshStore = Objects.requireNonNull(refreshStore, "refreshStore");
        buildPage();
        refreshRows();
    }

    /// Builds the source toolbar and scrollable native component list.
    private void buildPage() {
        getStyleClass().add("gray-background");
        setSpacing(10);
        setPadding(new Insets(10));

        HBox toolbar = new HBox(10);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.getChildren().addAll(
                addButton,
                createToolbarButton2(i18n("plugin.store.refresh"), SVG.REFRESH, refreshStore)
        );

        sourceList.getStyleClass().add("no-padding");
        ScrollPane scrollPane = new ScrollPane(sourceList);
        scrollPane.setFitToWidth(true);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        FXUtils.smoothScrolling(scrollPane);
        FXUtils.setOverflowHidden(scrollPane, 8);
        VBox.setVgrow(scrollPane, Priority.ALWAYS);

        getChildren().setAll(toolbar, scrollPane);
    }

    /// Rebuilds compact rows from the persisted source ordering and the latest available source outcomes.
    void refreshSourceRows() {
        refreshRows();
    }

    /// Rebuilds compact rows from the persisted source ordering and the latest available source outcomes.
    private void refreshRows() {
        List<PluginSource> sources = repository.getSources();
        Map<String, PluginSourceLoadResult> resultsById = sourceResultsById();
        addButton.setDisable(writing);
        sourceList.getContent().clear();
        for (int index = 0; index < sources.size(); index++) {
            PluginSource source = sources.get(index);
            sourceList.getContent().add(createSourceRow(source, index, sources.size(), resultsById.get(source.getId())));
        }
        if (sources.isEmpty()) {
            LineButton empty = new LineButton();
            empty.setLeading(SVG.INFO);
            empty.setTitle(i18n("plugin.store.empty"));
            empty.setMouseTransparent(true);
            sourceList.getContent().add(empty);
        }
    }

    /// Indexes the current snapshot outcomes by source ID for compact status and detail presentation.
    ///
    /// @return source result snapshot indexed by configured source ID
    private @Unmodifiable Map<String, PluginSourceLoadResult> sourceResultsById() {
        return mergeSourceResults(
                snapshotSupplier.get(),
                testedResults,
                testedResultSnapshotGenerations,
                repository.getSources()
        );
    }

    /// Merges current aggregate outcomes with manual tests that are newer than the aggregate publication.
    ///
    /// @param snapshot current aggregate snapshot, or `null` before a refresh completes
    /// @param manualResults latest successfully completed manual test results by source ID
    /// @param manualSnapshotGenerations aggregate generation current for each manual result
    /// @param sources current persisted source configuration
    /// @return immutable current source outcomes indexed by source ID
    static @Unmodifiable Map<String, PluginSourceLoadResult> mergeSourceResults(
            @Nullable PluginStoreSnapshot snapshot,
            @Unmodifiable Map<String, PluginSourceLoadResult> manualResults,
            @Unmodifiable Map<String, Long> manualSnapshotGenerations,
            @Unmodifiable List<PluginSource> sources
    ) {
        Map<String, PluginSourceLoadResult> results = new LinkedHashMap<>();
        if (snapshot != null && snapshot.matchesSources(sources)) {
            for (PluginSourceLoadResult result : snapshot.getSourceResults()) {
                results.put(result.getSource().getId(), result);
            }
        }
        for (Map.Entry<String, PluginSourceLoadResult> entry : manualResults.entrySet()) {
            if (isCurrentManualResult(
                    snapshot,
                    entry.getKey(),
                    entry.getValue(),
                    manualSnapshotGenerations.get(entry.getKey()),
                    sources
            )) {
                results.put(entry.getKey(), entry.getValue());
            }
        }
        return Map.copyOf(results);
    }

    /// Merges current aggregate outcomes with manual results published after the supplied aggregate generation.
    ///
    /// @param snapshot current aggregate snapshot, or `null` before a refresh completes
    /// @param manualResults latest successfully completed manual test results by source ID
    /// @param manualSnapshotGeneration aggregate generation current when every manual result was published
    /// @param sources current persisted source configuration
    /// @return immutable current source outcomes indexed by source ID
    static @Unmodifiable Map<String, PluginSourceLoadResult> mergeSourceResults(
            @Nullable PluginStoreSnapshot snapshot,
            @Unmodifiable Map<String, PluginSourceLoadResult> manualResults,
            long manualSnapshotGeneration,
            @Unmodifiable List<PluginSource> sources
    ) {
        Map<String, Long> manualSnapshotGenerations = new LinkedHashMap<>();
        manualResults.keySet().forEach(sourceId -> manualSnapshotGenerations.put(sourceId, manualSnapshotGeneration));
        return mergeSourceResults(snapshot, manualResults, manualSnapshotGenerations, sources);
    }

    /// Merges current aggregate outcomes with manual results assumed newer than the supplied aggregate snapshot.
    ///
    /// @param snapshot current aggregate snapshot, or `null` before a refresh completes
    /// @param manualResults latest successfully completed manual test results by source ID
    /// @param sources current persisted source configuration
    /// @return immutable current source outcomes indexed by source ID
    static @Unmodifiable Map<String, PluginSourceLoadResult> mergeSourceResults(
            @Nullable PluginStoreSnapshot snapshot,
            @Unmodifiable Map<String, PluginSourceLoadResult> manualResults,
            @Unmodifiable List<PluginSource> sources
    ) {
        return mergeSourceResults(
                snapshot,
                manualResults,
                snapshot == null ? -1 : snapshot.getGeneration(),
                sources
        );
    }

    /// Returns whether a manual result is still current after a matching aggregate snapshot publishes.
    ///
    /// @param snapshot current aggregate snapshot, or `null` before one publishes
    /// @param sourceId source ID indexed by manual result maps
    /// @param manual manual source result
    /// @param manualSnapshotGeneration aggregate generation current when the manual result completed
    /// @param sources current persisted source configuration
    /// @return whether the manual result may override the aggregate result
    private static boolean isCurrentManualResult(
            @Nullable PluginStoreSnapshot snapshot,
            String sourceId,
            PluginSourceLoadResult manual,
            @Nullable Long manualSnapshotGeneration,
            @Unmodifiable List<PluginSource> sources
    ) {
        return sourceId.equals(manual.getSource().getId())
                && containsCurrentSource(sources, manual.getSource())
                && (snapshot == null || manualSnapshotGeneration == null
                || manualSnapshotGeneration >= snapshot.getGeneration());
    }

    /// Returns whether the specified source configuration still matches the persisted snapshot.
    ///
    /// @param sources current persisted sources
    /// @param candidate aggregate or manual-test source configuration
    /// @return whether every aggregation-relevant source field remains current
    private static boolean containsCurrentSource(@Unmodifiable List<PluginSource> sources, PluginSource candidate) {
        return sources.stream().anyMatch(source -> PluginStoreSnapshot.sourceConfigurationsMatch(candidate, source));
    }

    /// Builds one compact source row with source metadata, a direct enablement switch, and overflow actions.
    ///
    /// @param source persisted source configuration
    /// @param index current priority index
    /// @param sourceCount configured source count
    /// @param result latest source load result, or `null` before a refresh completes
    /// @return configured source row
    private Node createSourceRow(
            PluginSource source,
            int index,
            int sourceCount,
            @Nullable PluginSourceLoadResult result
    ) {
        SourceRow model = sourceRow(source, remoteName(result), result);
        String accessibleName = accessibleSourceName(index + 1, model.title());
        LineButton row = new LineButton();
        row.setLeading(source.isOfficial() ? SVG.CHECK_CIRCLE : SVG.EXTENSION);
        row.setTitle((index + 1) + ". " + model.title());
        row.setSubtitle(model.subtitle());
        row.setMinHeight(88);
        row.setFocusTraversable(true);
        row.setAccessibleText(sourceRowAccessibleText(accessibleName, model));
        row.setOnAction(event -> showDetails(source, result));
        row.addEventFilter(KeyEvent.KEY_PRESSED, event -> reorderOnShortcut(source, event));
        row.setOnDragDetected(event -> {
            if (writing) {
                return;
            }
            Dragboard dragboard = row.startDragAndDrop(TransferMode.MOVE);
            ClipboardContent content = new ClipboardContent();
            content.putString(source.getId());
            dragboard.setContent(content);
            event.consume();
        });
        row.setOnDragOver(event -> {
            Dragboard dragboard = event.getDragboard();
            if (!writing && dragboard.hasString() && !source.getId().equals(dragboard.getString())) {
                event.acceptTransferModes(TransferMode.MOVE);
            }
            event.consume();
        });
        row.setOnDragDropped(event -> {
            Dragboard dragboard = event.getDragboard();
            boolean completed = !writing && dragboard.hasString()
                    && moveBefore(dragboard.getString(), source.getId());
            event.setDropCompleted(completed);
            event.consume();
        });

        HBox actions = new HBox(4);
        actions.setAlignment(Pos.CENTER_RIGHT);
        actions.setMinWidth(Region.USE_PREF_SIZE);
        JFXToggleButton enabled = new JFXToggleButton();
        enabled.setSelected(source.isEnabled());
        enabled.setTooltip(new Tooltip(i18n(source.isEnabled() ? "button.disable" : "button.enable")));
        enabled.setAccessibleText(sourceActionAccessibleText(source.isEnabled() ? "disable" : "enable", accessibleName));
        enabled.setOnMouseClicked(event -> event.consume());
        enabled.setOnAction(event -> setEnabled(source, enabled.isSelected()));

        JFXButton previous = createActionButton(SVG.KEYBOARD_ARROW_UP, () -> move(source, -1));
        previous.setDisable(index == 0 || writing);
        previous.setAccessibleText(sourceActionAccessibleText("previous", accessibleName));
        JFXButton next = createActionButton(SVG.KEYBOARD_ARROW_DOWN, () -> move(source, 1));
        next.setDisable(index == sourceCount - 1 || writing);
        next.setAccessibleText(sourceActionAccessibleText("next", accessibleName));
        JFXButton more = createActionButton(SVG.MORE_VERT, () -> {
        });
        more.setAccessibleText(sourceActionAccessibleText("more", accessibleName));
        more.setOnAction(event -> showSourceActions(source, result, more));
        enabled.setDisable(writing);
        actions.getChildren().addAll(enabled, previous, next, more);
        row.setTrailingIcon(actions);
        row.setDisable(writing);
        return row;
    }

    /// Reorders a source when the current source row receives the documented control shortcut.
    ///
    /// @param source source to reorder
    /// @param event pressed key event from the row or one of its child controls
    private void reorderOnShortcut(PluginSource source, KeyEvent event) {
        if (!event.isControlDown()) {
            return;
        }
        if (event.getCode() == KeyCode.UP) {
            move(source, -1);
            event.consume();
        } else if (event.getCode() == KeyCode.DOWN) {
            move(source, 1);
            event.consume();
        }
    }

    /// Creates a fixed-size action control suitable for source-row trailing controls.
    ///
    /// @param icon action glyph
    /// @param action action callback
    /// @return configured action button
    private static JFXButton createActionButton(SVG icon, Runnable action) {
        JFXButton button = new JFXButton();
        button.setGraphic(icon.createIcon(18));
        button.setMinSize(32, 32);
        button.setPrefSize(32, 32);
        button.setMaxSize(32, 32);
        button.setOnMouseClicked(event -> event.consume());
        button.setOnAction(event -> action.run());
        return button;
    }

    /// Opens the allowed overflow actions for one source in an HMCL-native popup menu.
    ///
    /// @param source persisted source configuration
    /// @param result latest source result, or `null` before a refresh completes
    /// @param anchor action button that anchors the popup
    private void showSourceActions(
            PluginSource source,
            @Nullable PluginSourceLoadResult result,
            JFXButton anchor
    ) {
        PopupMenu menu = new PopupMenu();
        JFXPopup popup = new JFXPopup(menu);
        List<Node> actions = new ArrayList<>();
        actions.add(new IconedMenuItem(SVG.REFRESH, i18n("plugin.store.refresh"), () -> testSource(source), popup));
        actions.add(new IconedMenuItem(SVG.INFO, i18n("plugin.store.details"), () -> showDetails(source, result), popup));
        if (!source.isBuiltIn()) {
            actions.add(new MenuSeparator());
            actions.add(new IconedMenuItem(SVG.EDIT, i18n("button.edit"), () -> showEditor(source), popup));
            actions.add(new IconedMenuItem(SVG.DELETE, i18n("button.delete"), () -> confirmDelete(source), popup));
        }
        menu.getContent().setAll(actions);
        popup.show(anchor, JFXPopup.PopupVPosition.BOTTOM, JFXPopup.PopupHPosition.RIGHT, 0, 0);
    }

    /// Persists one enablement change and refreshes only after the write finishes successfully.
    ///
    /// @param source persisted source configuration
    /// @param enabled requested source enablement
    private void setEnabled(PluginSource source, boolean enabled) {
        persist(() -> repository.setEnabled(source.getId(), enabled), null);
    }

    /// Persists an exact source-ID reordering before refreshing rows and the aggregate store.
    ///
    /// @param source source to move
    /// @param offset requested priority offset
    private void move(PluginSource source, int offset) {
        List<String> sourceIds = new ArrayList<>(repository.getSources().stream().map(PluginSource::getId).toList());
        int index = sourceIds.indexOf(source.getId());
        int target = index + offset;
        if (index < 0 || target < 0 || target >= sourceIds.size()) {
            return;
        }
        sourceIds.remove(index);
        sourceIds.add(target, source.getId());
        reorder(sourceIds);
    }

    /// Moves the dragged source immediately before the target source through one exact repository reorder request.
    ///
    /// @param draggedSourceId dragged source ID
    /// @param targetSourceId source ID whose current position becomes the insertion point
    /// @return whether the drag described a real reorder
    private boolean moveBefore(String draggedSourceId, String targetSourceId) {
        if (draggedSourceId.equals(targetSourceId)) {
            return false;
        }
        List<String> sourceIds = reorderedIds(
                repository.getSources().stream().map(PluginSource::getId).toList(),
                draggedSourceId,
                targetSourceId
        );
        if (sourceIds.equals(repository.getSources().stream().map(PluginSource::getId).toList())) {
            return false;
        }
        reorder(sourceIds);
        return true;
    }

    /// Returns the exact source-ID order produced by moving a dragged source before its drop target.
    ///
    /// @param sourceIds current source-ID order
    /// @param draggedSourceId dragged source ID
    /// @param targetSourceId source ID whose current position becomes the insertion point
    /// @return immutable reordered IDs, or the unchanged order when either ID is absent
    static @Unmodifiable List<String> reorderedIds(
            @Unmodifiable List<String> sourceIds,
            String draggedSourceId,
            String targetSourceId
    ) {
        if (draggedSourceId.equals(targetSourceId)) {
            return List.copyOf(sourceIds);
        }
        List<String> reordered = new ArrayList<>(sourceIds);
        if (!reordered.remove(draggedSourceId)) {
            return List.copyOf(sourceIds);
        }
        int target = reordered.indexOf(targetSourceId);
        if (target < 0) {
            return List.copyOf(sourceIds);
        }
        reordered.add(target, draggedSourceId);
        return List.copyOf(reordered);
    }

    /// Submits one exact source-ID ordering to transactional persistence.
    ///
    /// @param sourceIds every current source ID exactly once in desired priority order
    private void reorder(@Unmodifiable List<String> sourceIds) {
        persist(() -> repository.reorder(sourceIds), null);
    }

    /// Exercises one source through a request-scoped manager without retaining a page-level result cache.
    ///
    /// @param source source to exercise
    private void testSource(PluginSource source) {
        long requestGeneration = testGenerations.getOrDefault(source.getId(), 0L) + 1;
        testGenerations.put(source.getId(), requestGeneration);
        long configurationGeneration = sourceConfigurationGeneration;
        Task.supplyAsync(() -> {
            long startedAt = System.nanoTime();
            try {
                return PluginSourceLoadExecutor.call(() -> loadTestResult(source, startedAt));
            } catch (Exception exception) {
                return PluginSourceLoadResult.failed(
                        source,
                        Math.max(0, (System.nanoTime() - startedAt) / 1_000_000),
                        exception instanceof IOException ioException
                                ? ioException
                                : new IOException(i18n("plugin.store.source.test.failed"), exception)
                );
            }
        }).whenComplete(Schedulers.javafx(), (@Nullable var result, @Nullable var exception) -> {
            if (!canPublishTestResult(
                    source,
                    repository.getSources(),
                    requestGeneration,
                    testGenerations.getOrDefault(source.getId(), 0L),
                    configurationGeneration,
                    sourceConfigurationGeneration
            )) {
                return;
            }
            if (exception != null) {
                PluginDialogs.showError(i18n("plugin.store.load_failed"), failureMessage(exception));
                return;
            }
            testedResults.put(source.getId(), Objects.requireNonNull(result));
            @Nullable PluginStoreSnapshot snapshot = snapshotSupplier.get();
            testedResultSnapshotGenerations.put(source.getId(), snapshot == null ? -1 : snapshot.getGeneration());
            refreshRows();
            refreshStore.run();
        }).start();
    }

    /// Loads one manual source test while holding the shared process-wide source-load permit.
    ///
    /// @param source source configuration to test
    /// @param startedAt monotonic source request start timestamp
    /// @return completed source result
    /// @throws IOException if source transport, parsing, or validation fails
    private static PluginSourceLoadResult loadTestResult(PluginSource source, long startedAt) throws IOException {
        PluginStoreManager manager = new PluginStoreManager();
        manager.loadSource(source);
        @Nullable PluginStoreRegistry registry = manager.getRegistry();
        if (registry == null) {
            throw new IOException(i18n("plugin.store.source.test.missing_registry"));
        }
        List<org.jackhuang.hmcl.plugin.store.PluginStoreItem> items = manager.getStoreItems();
        int partialFailures = (int) items.stream().filter(item -> item.getManifest() == null).count();
        return PluginSourceLoadResult.success(
                source,
                Math.max(0, (System.nanoTime() - startedAt) / 1_000_000),
                items,
                partialFailures,
                manager.getSkippedRepositoryCount(),
                registry,
                manager
        );
    }

    /// Returns whether a completed source test still matches the latest request and source configuration.
    ///
    /// @param testedSource source configuration captured when the test started
    /// @param currentSources current persisted source snapshot
    /// @param requestGeneration test generation captured when the test started
    /// @param latestTestGeneration latest requested test generation for the source
    /// @param configurationGeneration configuration generation captured when the test started
    /// @param latestConfigurationGeneration latest successful source mutation generation
    /// @return whether this completed test may update source presentation
    static boolean canPublishTestResult(
            PluginSource testedSource,
            @Unmodifiable List<PluginSource> currentSources,
            long requestGeneration,
            long latestTestGeneration,
            long configurationGeneration,
            long latestConfigurationGeneration
    ) {
        return requestGeneration == latestTestGeneration
                && configurationGeneration == latestConfigurationGeneration
                && currentSources.stream().anyMatch(source ->
                PluginStoreSnapshot.sourceConfigurationsMatch(testedSource, source));
    }

    /// Shows source details including the full configured URL and latest source outcome.
    ///
    /// @param source persisted source configuration
    /// @param result latest source result, or `null` before a refresh completes
    private void showDetails(PluginSource source, @Nullable PluginSourceLoadResult result) {
        int priority = repository.getSources().indexOf(source) + 1;
        SourceDetails details = sourceDetails(source, remoteName(result), result, priority, snapshotSupplier.get());
        Controllers.dialog(details.message(), details.title(),
                org.jackhuang.hmcl.ui.construct.MessageDialogPane.MessageType.INFO);
    }

    /// Opens the add or edit dialog for one source.
    ///
    /// @param source source to edit, or `null` to add one
    private void showEditor(@Nullable PluginSource source) {
        Controllers.dialog(new SourceEditorDialog(source));
    }

    /// Shows the correct destructive confirmation and removes the source only after user confirmation.
    ///
    /// @param source custom source to remove
    private void confirmDelete(PluginSource source) {
        if (source.isBuiltIn()) {
            return;
        }
        boolean affectsInstalledPlugins = latestSourceAffectsInstalledPlugins(source);
        String message = i18n(affectsInstalledPlugins
                ? "plugin.store.source.delete.installed_confirm"
                : "plugin.store.source.delete.confirm");
        PluginDialogs.confirmAction(
                i18n("button.delete"),
                message,
                i18n("button.delete"),
                () -> persist(() -> {
                    repository.removeSource(source.getId());
                    return null;
                }, null)
        );
    }

    /// Returns whether the freshest successful source items include an installed plugin ID.
    ///
    /// @param source source whose deletion impact is queried
    /// @return whether deletion may affect installed-plugin updates
    private boolean latestSourceAffectsInstalledPlugins(PluginSource source) {
        return latestSourceItems(source).stream()
                .map(item -> item.getEntry().getId())
                .anyMatch(installedPluginIdsSupplier.get()::contains);
    }

    /// Computes installed-plugin deletion impact from the same fresh manual-or-aggregate selection used at runtime.
    ///
    /// @param source source whose deletion impact is queried
    /// @param manualResults latest manually tested source results by source ID
    /// @param manualSnapshotGeneration aggregate generation current when every manual result was published
    /// @param snapshot current aggregate snapshot, or `null` before a refresh completes
    /// @param sources current persisted source configuration
    /// @param installedPluginIds installed plugin IDs
    /// @return whether the freshest successful source items intersect installed plugin IDs
    static boolean sourceAffectsInstalledPlugins(
            PluginSource source,
            @Unmodifiable Map<String, PluginSourceLoadResult> manualResults,
            long manualSnapshotGeneration,
            @Nullable PluginStoreSnapshot snapshot,
            @Unmodifiable List<PluginSource> sources,
            @Unmodifiable Set<String> installedPluginIds
    ) {
        return selectLatestSourceItems(source, manualResults, manualSnapshotGeneration, snapshot, sources).stream()
                .map(item -> item.getEntry().getId())
                .anyMatch(installedPluginIds::contains);
    }

    /// Computes installed-plugin deletion impact with manual results assumed newer than the current snapshot.
    ///
    /// @param source source whose deletion impact is queried
    /// @param manualResults latest manually tested source results by source ID
    /// @param snapshot current aggregate snapshot, or `null` before a refresh completes
    /// @param sources current persisted source configuration
    /// @param installedPluginIds installed plugin IDs
    /// @return whether the freshest successful source items intersect installed plugin IDs
    static boolean sourceAffectsInstalledPlugins(
            PluginSource source,
            @Unmodifiable Map<String, PluginSourceLoadResult> manualResults,
            @Nullable PluginStoreSnapshot snapshot,
            @Unmodifiable List<PluginSource> sources,
            @Unmodifiable Set<String> installedPluginIds
    ) {
        return sourceAffectsInstalledPlugins(
                source,
                manualResults,
                snapshot == null ? -1 : snapshot.getGeneration(),
                snapshot,
                sources,
                installedPluginIds
        );
    }

    /// Selects fresh successful manual source items before falling back to the matching aggregate source result.
    ///
    /// @param source source whose latest items are selected
    /// @param manualResults latest manually tested source results by source ID
    /// @param manualSnapshotGeneration aggregate generation current when every manual result was published
    /// @param snapshot current aggregate snapshot, or `null` before a refresh completes
    /// @param sources current persisted source configuration
    /// @return immutable selected source item snapshot
    static @Unmodifiable List<org.jackhuang.hmcl.plugin.store.PluginStoreItem> selectLatestSourceItems(
            PluginSource source,
            @Unmodifiable Map<String, PluginSourceLoadResult> manualResults,
            long manualSnapshotGeneration,
            @Nullable PluginStoreSnapshot snapshot,
            @Unmodifiable List<PluginSource> sources
    ) {
        @Nullable PluginSourceLoadResult manual = manualResults.get(source.getId());
        if (manual != null
                && manual.isSuccessful()
                && isCurrentManualResult(snapshot, source.getId(), manual, manualSnapshotGeneration, sources)) {
            return manual.getItems();
        }
        if (snapshot == null || !snapshot.matchesSources(sources)) {
            return List.of();
        }
        return snapshot.getSourceResults().stream()
                .filter(result -> result.getSource().getId().equals(source.getId()))
                .filter(PluginSourceLoadResult::isSuccessful)
                .findFirst()
                .map(PluginSourceLoadResult::getItems)
                .orElse(List.of());
    }

    /// Retrieves source-bound items from the freshest successful manual or aggregate result for deletion impact checks.
    ///
    /// @param source source whose items are queried
    /// @return immutable latest source item snapshot
    private @Unmodifiable List<org.jackhuang.hmcl.plugin.store.PluginStoreItem> latestSourceItems(PluginSource source) {
        @Nullable PluginStoreSnapshot snapshot = snapshotSupplier.get();
        @Nullable Long manualSnapshotGeneration = testedResultSnapshotGenerations.get(source.getId());
        return selectLatestSourceItems(
                source,
                testedResults,
                manualSnapshotGeneration == null ? -1 : manualSnapshotGeneration,
                snapshot,
                repository.getSources()
        );
    }

    /// Runs a single repository write, rebuilding from persistence when the write fails.
    ///
    /// @param write source mutation that persists its own candidate state
    /// @param onSuccess optional JavaFX-thread callback after successful persistence
    private void persist(RepositoryWrite write, @Nullable Runnable onSuccess) {
        if (writing) {
            return;
        }
        writing = true;
        refreshRows();
        Task.supplyAsync(() -> {
            try {
                return write.run();
            } catch (IOException exception) {
                throw new RuntimeException(exception);
            }
        }).whenComplete(Schedulers.javafx(), (@Nullable var result, @Nullable var exception) -> {
            writing = false;
            refreshRows();
            if (exception != null) {
                PluginDialogs.showError(i18n("plugin.store.load_failed"), failureMessage(exception));
                return;
            }
            sourceConfigurationGeneration++;
            testedResults.clear();
            testedResultSnapshotGenerations.clear();
            if (onSuccess != null) {
                onSuccess.run();
            }
            refreshStore.run();
        }).start();
    }

    /// Returns the remote registry display name from a completed source result.
    ///
    /// @param result source result, or `null` before a refresh completes
    /// @return remote registry name, or `null` when not available
    private static @Nullable String remoteName(@Nullable PluginSourceLoadResult result) {
        @Nullable PluginStoreRegistry registry = result == null ? null : result.getRegistry();
        return registry == null || StringUtils.isBlank(registry.getName()) ? null : registry.getName();
    }

    /// Returns the shared credential-safe compact source label.
    ///
    /// @param source persisted source configuration
    /// @param remoteName latest remote registry name, or `null` when unavailable
    /// @return safe compact source display name
    static String displayName(PluginSource source, @Nullable String remoteName) {
        return PluginSourceLabels.displayName(source, remoteName);
    }

    /// Returns source overflow actions, excluding edit and delete for launcher-defined built-in sources.
    ///
    /// @param source persisted source configuration
    /// @return immutable allowed action set
    static @Unmodifiable Set<Action> secondaryActions(PluginSource source) {
        return source.isBuiltIn()
                ? Set.of(Action.TEST, Action.DETAILS)
                : Set.of(Action.TEST, Action.DETAILS, Action.EDIT, Action.DELETE);
    }

    /// Returns a compact row presentation model without a full URL.
    ///
    /// @param source persisted source configuration
    /// @param remoteName latest remote registry name, or `null` when unavailable
    /// @param result latest source result, or `null` before a refresh completes
    /// @return immutable compact source row model
    static SourceRow sourceRow(
            PluginSource source,
            @Nullable String remoteName,
            @Nullable PluginSourceLoadResult result
    ) {
        String title = displayName(source, remoteName);
        List<String> details = new ArrayList<>();
        if (PluginSourceLabels.isSafeHumanLabel(remoteName)
                && !Objects.requireNonNull(remoteName).trim().equals(title)) {
            details.add(remoteName.trim());
        }
        details.add(i18n(source.isOfficial() ? "plugin.store.source.official" : "plugin.store.source.third_party"));
        details.add(i18n(source.isEnabled() ? "plugin.enabled" : "plugin.disabled"));
        details.add(sourceStatusLabel(result));
        details.add(i18n("plugin.store.source.details.plugins", result == null ? 0 : result.getItems().size()));
        details.add(result == null ? "-" : i18n("plugin.store.source.details.duration", result.getDurationMillis()));
        return new SourceRow(title, String.join(" · ", details));
    }

    /// Creates a source-specific, URL-safe accessibility name with a unique visible priority.
    ///
    /// @param priority current one-based source priority
    /// @param sourceName compact source display name
    /// @return localized priority and source name
    static String accessibleSourceName(int priority, String sourceName) {
        return i18n("plugin.store.source.details.priority", priority) + ", " + sourceName;
    }

    /// Creates URL-safe accessibility text that identifies a compact source row and its status.
    ///
    /// @param sourceName unique compact source accessibility name
    /// @param row compact source row content
    /// @return localized source-row accessibility text
    static String sourceRowAccessibleText(String sourceName, SourceRow row) {
        return i18n("plugin.store.source.accessible.row", sourceName, row.subtitle());
    }

    /// Creates source-specific accessibility text for an icon-only source-row action.
    ///
    /// @param action action identifier
    /// @param sourceName compact source display name
    /// @return localized action accessibility text
    static String sourceActionAccessibleText(String action, String sourceName) {
        return i18n("plugin.store.source.accessible." + action, sourceName);
    }

    /// Returns a localized compact label for the latest source load outcome.
    ///
    /// @param result latest source result, or `null` before a refresh completes
    /// @return localized source status label
    private static String sourceStatusLabel(@Nullable PluginSourceLoadResult result) {
        if (result == null) {
            return i18n("plugin.store.source.status.unchecked");
        }
        return switch (result.getStatus()) {
            case DISABLED -> i18n("plugin.disabled");
            case SUCCESS -> i18n("plugin.store.source.status.loaded");
            case PARTIAL_FAILURE -> i18n(
                    "plugin.store.source.status.partial_failure",
                    result.getPartialManifestFailureCount()
            );
            case FAILED -> i18n("plugin.store.source.status.failed");
        };
    }

    /// Returns explicit source details including the full URL and latest source status.
    ///
    /// @param source persisted source configuration
    /// @param remoteName latest remote registry name, or `null` when unavailable
    /// @param result latest source result, or `null` before a refresh completes
    /// @return immutable source details model
    static SourceDetails sourceDetails(
            PluginSource source,
            @Nullable String remoteName,
            @Nullable PluginSourceLoadResult result
    ) {
        return sourceDetails(source, remoteName, result, 0, null);
    }

    /// Returns complete details for one source including registry metadata and aggregate conflicts.
    ///
    /// @param source persisted source configuration
    /// @param remoteName latest remote registry name, or `null` when unavailable
    /// @param result latest source result, or `null` before a refresh completes
    /// @param priority current one-based source priority, or zero when not available
    /// @param snapshot current aggregate snapshot, or `null` before an aggregate refresh completes
    /// @return immutable source details model
    static SourceDetails sourceDetails(
            PluginSource source,
            @Nullable String remoteName,
            @Nullable PluginSourceLoadResult result,
            int priority,
            @Nullable PluginStoreSnapshot snapshot
    ) {
        @Nullable PluginStoreRegistry registry = result == null ? null : result.getRegistry();
        @Nullable String alias = safeOptionalSourceLabel(source.getAlias());
        @Nullable String safeRemoteName = safeOptionalSourceLabel(remoteName);
        String status = sourceStatusLabel(result);
        @Nullable String failureMessage = result == null ? null : result.getFailureMessage();
        int pluginCount = result == null ? 0 : result.getItems().size();
        long durationMillis = result == null ? 0 : result.getDurationMillis();
        int partialFailures = result == null ? 0 : result.getPartialManifestFailureCount();
        String conflicts = conflictSummary(source, snapshot);
        return new SourceDetails(
                displayName(source, remoteName),
                source.getUrl(),
                alias,
                safeRemoteName,
                registry == null ? null : PluginSourceLabels.sanitizeMetadata(registry.getDescription()),
                registry == null ? null : homepageHost(registry.getHomepageUrl()),
                source.isOfficial(),
                source.isEnabled(),
                priority,
                status,
                failureMessage,
                pluginCount,
                durationMillis,
                partialFailures,
                conflicts
        );
    }

    /// Returns an ordinary alias or registry name when it can be safely repeated outside an explicit URL field.
    ///
    /// @param label source alias or registry name
    /// @return trimmed safe label, or `null` for URL-shaped untrusted metadata
    private static @Nullable String safeOptionalSourceLabel(@Nullable String label) {
        return PluginSourceLabels.isSafeHumanLabel(label) ? Objects.requireNonNull(label).trim() : null;
    }

    /// Returns the aggregate winner conflicts involving one source, or a localized empty value.
    ///
    /// @param source source whose winner and candidate conflicts are counted
    /// @param snapshot current aggregate snapshot, or `null` before an aggregate refresh completes
    /// @return compact conflict count
    private static String conflictSummary(PluginSource source, @Nullable PluginStoreSnapshot snapshot) {
        if (snapshot == null) {
            return "0";
        }
        long count = snapshot.getConflictCandidates().entrySet().stream()
                .filter(entry -> snapshot.getWinningItems().get(entry.getKey()).getSource().getId().equals(source.getId())
                        || entry.getValue().stream().anyMatch(item -> item.getSource().getId().equals(source.getId())))
                .count();
        return Long.toString(count);
    }

    /// Reduces a configured homepage to its safe host presentation for previews and details.
    ///
    /// @param homepage optional registry homepage URL
    /// @return homepage host, or `null` when unavailable
    private static @Nullable String homepageHost(String homepage) {
        if (StringUtils.isBlank(homepage)) {
            return null;
        }
        try {
            return new URI(homepage).getHost();
        } catch (URISyntaxException exception) {
            return null;
        }
    }

    /// Builds the complete pre-save preview message from proposed source configuration and validated remote metadata.
    ///
    /// @param url normalized proposed source URL
    /// @param alias proposed optional local alias
    /// @param name remote registry name
    /// @param description remote registry description
    /// @param homepageHost safe remote homepage host, or `null` when unavailable
    /// @param pluginCount resolved preview plugin count
    /// @return complete preview confirmation message
    static String previewMessage(
            String url,
            @Nullable String alias,
            String name,
            String description,
            @Nullable String homepageHost,
            int pluginCount
    ) {
        List<String> lines = new ArrayList<>();
        lines.add(i18n("plugin.store.source.details.url", url));
        @Nullable String safeAlias = safeOptionalSourceLabel(alias);
        @Nullable String safeName = safeOptionalSourceLabel(name);
        if (safeAlias != null) {
            lines.add(i18n("plugin.store.source.details.alias", safeAlias));
        }
        if (safeName != null) {
            lines.add(safeName);
        }
        if (StringUtils.isNotBlank(description)) {
            lines.add(PluginSourceLabels.sanitizeMetadata(description));
        }
        if (homepageHost != null) {
            lines.add(i18n("plugin.store.source.preview.homepage", homepageHost));
        }
        lines.add(i18n("plugin.store.source.details.plugins", pluginCount));
        return String.join("\n", lines);
    }

    /// Returns whether an add or edit must complete an isolated registry preview before persistence.
    ///
    /// @param source existing source, or `null` for an add
    /// @param url proposed source URL
    /// @return whether the proposed URL requires a network preview
    static boolean requiresPreview(@Nullable PluginSource source, String url) {
        return source == null || !source.getUrl().equals(url);
    }

    /// Returns the decorator navigation state.
    ///
    /// @return read-only page state
    @Override
    public ReadOnlyObjectProperty<State> stateProperty() {
        return state.getReadOnlyProperty();
    }

    /// Represents an action permitted by a compact source row's overflow menu.
    enum Action {
        /// Reloads the source through the shared store refresh flow.
        TEST,

        /// Opens the full configured URL and latest source outcome.
        DETAILS,

        /// Edits a custom source configuration.
        EDIT,

        /// Deletes a custom source after confirmation.
        DELETE
    }

    /// Holds URL-safe compact source row content.
    @NotNullByDefault
    static final class SourceRow {
        /// Compact source display name.
        private final String title;

        /// Compact source metadata without a full URL.
        private final String subtitle;

        /// Creates immutable compact source row content.
        ///
        /// @param title compact source display name
        /// @param subtitle compact source metadata
        private SourceRow(String title, String subtitle) {
            this.title = title;
            this.subtitle = subtitle;
        }

        /// Returns the compact source display name.
        ///
        /// @return compact source display name
        String title() {
            return title;
        }

        /// Returns compact source metadata without a full URL.
        ///
        /// @return compact source metadata
        String subtitle() {
            return subtitle;
        }
    }

    /// Holds explicit source details whose URL is intentionally only shown in the details presentation.
    @NotNullByDefault
    static final class SourceDetails {
        /// Explicit source detail title.
        private final String title;

        /// Full configured source URL for the details presentation only.
        private final String url;

        /// Optional local source alias.
        private final @Nullable String alias;

        /// Optional remote registry name.
        private final @Nullable String remoteName;

        /// Optional remote registry description.
        private final @Nullable String description;

        /// Optional safe remote homepage host.
        private final @Nullable String homepageHost;

        /// Whether this source is the fixed official registry.
        private final boolean official;

        /// Whether this source currently participates in aggregation.
        private final boolean enabled;

        /// Current one-based source priority, or zero when unavailable.
        private final int priority;

        /// Current source load status.
        private final String status;

        /// Sanitized source failure reason, or `null` when this source did not fail.
        private final @Nullable String failureMessage;

        /// Number of source items in the latest result.
        private final int pluginCount;

        /// Elapsed latest source request duration.
        private final long durationMillis;

        /// Number of unavailable repository candidates or plugin entries in an otherwise successful source result.
        private final int partialManifestFailures;

        /// Number of aggregate winner conflicts involving this source.
        private final String conflicts;

        /// Creates immutable explicit source details content.
        ///
        /// @param title explicit source detail title
        /// @param url full configured source URL
        /// @param alias optional local source alias
        /// @param remoteName optional remote registry name
        /// @param description optional remote registry description
        /// @param homepageHost optional safe remote homepage host
        /// @param official whether this is the fixed official registry
        /// @param enabled whether this source currently participates in aggregation
        /// @param priority current one-based source priority
        /// @param status current source load status
        /// @param failureMessage optional sanitized source failure reason
        /// @param pluginCount number of source items in the latest result
        /// @param durationMillis elapsed latest source request duration
        /// @param partialManifestFailures number of unavailable repository candidates or plugin entries
        /// @param conflicts number of aggregate winner conflicts involving this source
        private SourceDetails(
                String title,
                String url,
                @Nullable String alias,
                @Nullable String remoteName,
                @Nullable String description,
                @Nullable String homepageHost,
                boolean official,
                boolean enabled,
                int priority,
                String status,
                @Nullable String failureMessage,
                int pluginCount,
                long durationMillis,
                int partialManifestFailures,
                String conflicts
        ) {
            this.title = title;
            this.url = url;
            this.alias = alias;
            this.remoteName = remoteName;
            this.description = description;
            this.homepageHost = homepageHost;
            this.official = official;
            this.enabled = enabled;
            this.priority = priority;
            this.status = status;
            this.failureMessage = failureMessage;
            this.pluginCount = pluginCount;
            this.durationMillis = durationMillis;
            this.partialManifestFailures = partialManifestFailures;
            this.conflicts = conflicts;
        }

        /// Returns the explicit source detail title.
        ///
        /// @return explicit source detail title
        String title() {
            return title;
        }

        /// Returns the full configured source URL.
        ///
        /// @return full configured source URL
        String url() {
            return url;
        }

        /// Returns a complete line-oriented details message without omitting source diagnostics.
        ///
        /// @return complete source details message
        String message() {
            List<String> fields = new ArrayList<>();
            fields.add(i18n("plugin.store.source.details.url", url));
            if (alias != null) {
                fields.add(i18n("plugin.store.source.details.alias", alias));
            }
            if (remoteName != null) {
                fields.add(i18n("plugin.store.source.details.registry", remoteName));
            }
            if (description != null) {
                fields.add(i18n("plugin.store.source.details.description", description));
            }
            if (homepageHost != null) {
                fields.add(i18n("plugin.store.source.details.homepage", homepageHost));
            }
            fields.add(i18n(
                    "plugin.store.source.details.type",
                    i18n(official ? "plugin.store.source.official" : "plugin.store.source.third_party")
            ));
            fields.add(i18n("plugin.store.source.details.enabled", i18n(enabled ? "plugin.enabled" : "plugin.disabled")));
            if (priority > 0) {
                fields.add(i18n("plugin.store.source.details.priority", priority));
            }
            fields.add(i18n("plugin.store.source.details.status", status));
            if (failureMessage != null) {
                fields.add(i18n("plugin.store.source.details.failure", failureMessage));
            }
            fields.add(i18n("plugin.store.source.details.plugins", pluginCount));
            fields.add(i18n("plugin.store.source.details.duration", durationMillis));
            fields.add(i18n("plugin.store.source.details.partial_manifest_failures", partialManifestFailures));
            fields.add(i18n("plugin.store.source.details.conflicts", conflicts));
            return String.join("\n", fields);
        }
    }

    /// Abstracts a repository write with checked I/O failure handling.
    @FunctionalInterface
    private interface RepositoryWrite {
        /// Executes one repository mutation.
        ///
        /// @return ignored successful result
        /// @throws IOException if persistence fails
        @Nullable Object run() throws IOException;
    }

    /// Represents a successfully validated preview ready for user confirmation and persistence.
    @NotNullByDefault
    private static final class SourcePreview {
        /// Preview source configuration that has not yet been persisted.
        private final PluginSource source;

        /// Validated preview registry.
        private final PluginStoreRegistry registry;

        /// Number of source-bound items resolved by the preview.
        private final int pluginCount;

        /// Creates an immutable successful source preview.
        ///
        /// @param source preview source configuration
        /// @param registry validated preview registry
        /// @param pluginCount number of preview source items
        private SourcePreview(PluginSource source, PluginStoreRegistry registry, int pluginCount) {
            this.source = source;
            this.registry = registry;
            this.pluginCount = pluginCount;
        }

        /// Returns the preview source configuration.
        ///
        /// @return preview source configuration
        private PluginSource source() {
            return source;
        }

        /// Returns the validated preview registry.
        ///
        /// @return validated preview registry
        private PluginStoreRegistry registry() {
            return registry;
        }

        /// Returns the number of preview source items.
        ///
        /// @return number of preview source items
        private int pluginCount() {
            return pluginCount;
        }
    }

    /// Edits one add or custom-update request and persists only after a successful URL preview.
    @NotNullByDefault
    private final class SourceEditorDialog extends JFXDialogLayout {
        /// Existing source being edited, or `null` for an add request.
        private final @Nullable PluginSource source;

        /// Editable source URL.
        private final JFXTextField urlField = new JFXTextField();

        /// Editable local source alias.
        private final JFXTextField aliasField = new JFXTextField();

        /// Displays preview or validation errors while retaining editable input.
        private final Label feedback = new Label();

        /// Prevents repeated preview requests from one dialog.
        private boolean previewing;

        /// Identifies the current valid dialog and invalidates asynchronous previews after closure.
        private long generation;

        /// Whether this dialog still accepts preview completions and source persistence.
        private boolean active = true;

        /// Creates a source add or edit dialog.
        ///
        /// @param source existing source to edit, or `null` for an add request
        private SourceEditorDialog(@Nullable PluginSource source) {
            this.source = source;
            setHeading(new HBox(new Label(i18n(source == null ? "button.add" : "button.edit"))));
            urlField.setPromptText(i18n("plugin.store.registry_url"));
            aliasField.setPromptText(i18n("plugin.store.source.custom"));
            if (source != null) {
                urlField.setText(source.getUrl());
                @Nullable String alias = source.getAlias();
                aliasField.setText(alias == null ? "" : alias);
                urlField.setDisable(source.isBuiltIn());
            }
            feedback.setWrapText(true);
            VBox body = new VBox(10, urlField, aliasField, feedback);
            body.setPadding(new Insets(4, 2, 4, 2));
            setBody(body);

            JFXButton save = new JFXButton(i18n("button.save"));
            save.getStyleClass().add("dialog-accept");
            save.setOnAction(event -> save());
            JFXButton cancel = new JFXButton(i18n("button.cancel"));
            cancel.getStyleClass().add("dialog-cancel");
            cancel.setOnAction(event -> close());
            setActions(save, cancel);
            FXUtils.onEscPressed(this, cancel::fire);
        }

        /// Validates alias-only updates locally or runs a request-scoped preview before any repository mutation.
        private void save() {
            if (!active || writing || previewing) {
                return;
            }
            String url = urlField.getText().trim();
            @Nullable String alias = StringUtils.isBlank(aliasField.getText()) ? null : aliasField.getText().trim();
            if (url.isBlank()) {
                feedback.setText(i18n("plugin.store.registry_url"));
                return;
            }
            if (!requiresPreview(source, url)) {
                persistAliasOnly(Objects.requireNonNull(source), alias);
                return;
            }
            previewing = true;
            long requestGeneration = ++generation;
            feedback.setText(i18n("plugin.store.source.status.loading"));
            Task.supplyAsync(() -> preview(url, alias)).whenComplete(Schedulers.javafx(),
                    (@Nullable var preview, @Nullable var exception) -> {
                        if (!active || requestGeneration != generation) {
                            return;
                        }
                        previewing = false;
                        if (exception != null) {
                            feedback.setText(failureMessage(exception));
                            return;
                        }
                        showPreviewOrError(Objects.requireNonNull(preview));
                    }).start();
        }

        /// Builds one request-scoped manager and source preview without altering persisted source configuration.
        ///
        /// @param normalizedUrl trimmed URL proposed by the user
        /// @param alias proposed local alias
        /// @return successful isolated preview
        private static SourcePreview preview(String normalizedUrl, @Nullable String alias) {
            try {
                return PluginSourceLoadExecutor.call(() -> {
                    PluginStoreManager previewManager = new PluginStoreManager();
                    PluginSource previewSource = new PluginSource("preview", normalizedUrl, alias, true, false);
                    previewManager.loadSource(previewSource);
                    @Nullable PluginStoreRegistry registry = previewManager.getRegistry();
                    if (registry == null) {
                        throw new IOException(i18n("plugin.store.source.preview.missing_registry"));
                    }
                    return new SourcePreview(previewSource, registry, previewManager.getStoreItems().size());
                });
            } catch (Exception exception) {
                throw new RuntimeException(exception);
            }
        }

        /// Shows preview confirmation and defers the repository write until its explicit confirm callback.
        ///
        /// @param preview successful isolated preview
        private void showPreviewOrError(SourcePreview preview) {
            feedback.setText(previewMessage(
                    preview.source().getUrl(),
                    preview.source().getAlias(),
                    preview.registry().getName(),
                    preview.registry().getDescription(),
                    homepageHost(preview.registry().getHomepageUrl()),
                    preview.pluginCount()
            ));
            PluginDialogs.confirmAction(
                    i18n("plugin.store.source.preview.confirm"),
                    feedback.getText(),
                    i18n("button.save"),
                    () -> persistPreview(preview)
            );
        }

        /// Persists a successful preview as an add or configuration update and closes this dialog only after success.
        ///
        /// @param preview successful preview confirmed by the user
        private void persistPreview(SourcePreview preview) {
            if (!active || writing) {
                return;
            }
            String url = preview.source().getUrl();
            @Nullable String alias = preview.source().getAlias();
            persist(() -> {
                if (source == null) {
                    repository.addSource(url, alias);
                } else {
                    repository.updateSource(source.getId(), url, alias);
                }
                return null;
            }, this::close);
        }

        /// Persists a local alias replacement without requiring network access and closes only after success.
        ///
        /// @param source existing source whose URL remains unchanged
        /// @param alias new optional local alias
        private void persistAliasOnly(PluginSource source, @Nullable String alias) {
            persist(() -> {
                repository.updateAlias(source.getId(), alias);
                return null;
            }, this::close);
        }

        /// Invalidates pending previews and closes this dialog without allowing later persistence.
        private void close() {
            if (!active) {
                return;
            }
            active = false;
            generation++;
            fireEvent(new DialogCloseEvent());
        }
    }

    /// Returns a compact root-cause diagnostic for repository and preview failures.
    ///
    /// @param exception asynchronous or direct failure
    /// @return nonblank root-cause message
    private static String failureMessage(Throwable exception) {
        Throwable cause = exception;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        @Nullable String message = cause.getMessage();
        return StringUtils.isBlank(message) ? cause.toString() : Objects.requireNonNull(message);
    }
}
