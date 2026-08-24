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
import com.jfoenix.controls.JFXComboBox;
import com.jfoenix.controls.JFXSpinner;
import com.jfoenix.controls.JFXTextField;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.css.PseudoClass;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.TextAlignment;
import org.jackhuang.hmcl.plugin.LocalPluginInspection;
import org.jackhuang.hmcl.plugin.PluginDependency;
import org.jackhuang.hmcl.plugin.PluginInstallationPlanningSnapshot;
import org.jackhuang.hmcl.plugin.PluginManager;
import org.jackhuang.hmcl.plugin.PluginManifest;
import org.jackhuang.hmcl.plugin.PluginPermission;
import org.jackhuang.hmcl.plugin.PluginRuntimeInstallAuthorization;
import org.jackhuang.hmcl.plugin.PluginRuntimeStatus;
import org.jackhuang.hmcl.Metadata;
import org.jackhuang.hmcl.plugin.runtime.PluginPlatformTarget;
import org.jackhuang.hmcl.plugin.runtime.RuntimeProviderBinding;
import org.jackhuang.hmcl.plugin.store.PluginInstallPlan;
import org.jackhuang.hmcl.plugin.store.PluginSource;
import org.jackhuang.hmcl.plugin.store.PluginSourceConfiguration;
import org.jackhuang.hmcl.plugin.store.PluginSourceLoadResult;
import org.jackhuang.hmcl.plugin.store.PluginSourceLabels;
import org.jackhuang.hmcl.plugin.store.PluginSourceProvenance;
import org.jackhuang.hmcl.plugin.store.PluginStoreAggregator;
import org.jackhuang.hmcl.plugin.store.PluginStoreDependencyResolver;
import org.jackhuang.hmcl.plugin.store.PluginStoreItem;
import org.jackhuang.hmcl.plugin.store.PluginStoreManager;
import org.jackhuang.hmcl.plugin.store.PluginStorePreferences;
import org.jackhuang.hmcl.plugin.store.PluginStoreManifest;
import org.jackhuang.hmcl.plugin.store.PluginStoreSnapshot;
import org.jackhuang.hmcl.plugin.store.PluginStoreRegistry;
import org.jackhuang.hmcl.plugin.trust.PluginCertificationReceipt;
import org.jackhuang.hmcl.plugin.trust.PluginTrustLevel;
import org.jackhuang.hmcl.plugin.trust.PluginTrustResult;
import org.jackhuang.hmcl.task.Schedulers;
import org.jackhuang.hmcl.task.Task;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.HTMLRenderer;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.construct.ComponentList;
import org.jackhuang.hmcl.ui.construct.LineButton;
import org.jackhuang.hmcl.ui.construct.LineSelectButton;
import org.jackhuang.hmcl.ui.construct.PageAware;
import org.jackhuang.hmcl.ui.decorator.DecoratorPage;
import org.jackhuang.hmcl.util.StringUtils;
import org.jackhuang.hmcl.util.io.FileUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static org.jackhuang.hmcl.ui.ToolbarListPageSkin.createToolbarButton2;
import static org.jackhuang.hmcl.util.i18n.I18n.i18n;
import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// Presents the remote plugin registry, version details, favorites, and dependency-aware installation workflow.
@NotNullByDefault
public class PluginStorePage extends VBox implements DecoratorPage, PageAware {
    /// Sentinel value used by the category selector for an unfiltered view.
    private static final String ALL_CATEGORIES = "__all__";

    /// Maximum weighted text width reserved for a two-line description at the 580px layout.
    private static final int DESCRIPTION_DISPLAY_UNITS = 72;

    /// Maximum weighted plugin-name width that leaves the minimum-width trailing actions fully visible.
    private static final int TITLE_DISPLAY_UNITS = 36;

    /// Error presentation state used by incompatible plugin metadata rows.
    private static final PseudoClass PSEUDO_ERROR = PseudoClass.getPseudoClass("error");

    /// Decorator navigation state for the plugin store page.
    private final ReadOnlyObjectWrapper<State> state =
            new ReadOnlyObjectWrapper<>(State.fromTitle(i18n("plugin.store")));

    /// Page-owned persistence for ordered sources and favorite plugin IDs.
    private final PluginStorePreferences sourceRepository = new PluginStorePreferences(Metadata.HMCL_LOCAL_HOME);

    /// Persistent aggregate loader shared by every refresh while this cached page exists.
    private final PluginStoreAggregator storeAggregator = new PluginStoreAggregator();

    /// Summary of enabled sources displayed beside the store toolbar.
    private final Label sourceSummaryLabel = new Label();

    /// Process-wide plugin lifecycle manager used to inspect and publish downloaded packages.
    private final PluginManager pluginManager = PluginManager.getInstance();

    /// Filtered visual list of HMCL plugin rows.
    private final ComponentList pluginList = new ComponentList();

    /// Activity indicator displayed while the registry is loading.
    private final JFXSpinner loadingSpinner = new JFXSpinner();

    /// Registry and filtering status text.
    private final Label statusLabel = new Label();

    /// Free-text plugin search field.
    private final JFXTextField searchField = new JFXTextField();

    /// Category filter selector.
    private final JFXComboBox<CategoryItem> categoryBox = new JFXComboBox<>();

    /// Whether the current result is limited to persisted favorite plugins.
    private final BooleanProperty favoritesOnly = new SimpleBooleanProperty();

    /// Fixed star command that toggles the favorites-only filter.
    private final JFXButton favoritesOnlyButton = new JFXButton();

    /// Resolved registry items before client-side filtering.
    private final List<PluginStoreItem> allItems = new ArrayList<>();

    /// Readable installed package manifests, including plugins whose lifecycle failed to load.
    private @Unmodifiable Map<String, PluginManifest> installedManifests = Map.of();

    /// Future installed manifests used by the dependency planner after pending removals complete.
    private @Unmodifiable Map<String, PluginManifest> installPlanningManifests = Map.of();

    /// Installed-package read failure, or `null` when update and installation state is trustworthy.
    private @Nullable String installedManifestFailure;

    /// Latest accepted aggregate catalog, or `null` before the first refresh completes.
    private @Nullable PluginStoreSnapshot currentSnapshot;

    /// Whether a newer aggregate source refresh is underway while prior rows remain usable.
    private boolean storeRefreshInProgress;

    /// Latest page refresh request generation used to reject stale JavaFX completions.
    private long storeRefreshGeneration;

    /// Guards the single application-lifetime shutdown of the persistent aggregate loader.
    private final AtomicBoolean storeAggregatorClosed = new AtomicBoolean();

    /// Current source-management page waiting for explicit source snapshot publication.
    private @Nullable PluginSourceManagementPage sourceManagementPage;

    /// Whether the first visible-page registry request has already been started.
    private boolean initialLoadRequested;

    /// Most recently opened details page, retained so a completed installation can refresh its local state.
    private @Nullable PluginDetailsPage activeDetailsPage;

    /// Creates the HMCL toolbar, filters, status area, and scrollable plugin list.
    public PluginStorePage() {
        getStyleClass().add("gray-background");
        setSpacing(10);
        setPadding(new Insets(10));

        HBox toolbar = new HBox(12);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.setMinHeight(44);
        toolbar.setPrefHeight(44);

        searchField.setPromptText(i18n("plugin.store.search"));
        searchField.setMinWidth(120);
        searchField.setPrefHeight(36);
        HBox.setHgrow(searchField, Priority.ALWAYS);
        searchField.textProperty().addListener((observable, oldValue, newValue) -> applyFilter());

        JFXButton refreshButton = createIconButton(
                i18n("plugin.store.refresh"),
                SVG.REFRESH,
                this::loadPluginStore
        );
        String manageSources = i18n("plugin.store.manage_sources");
        JFXButton settingsButton = createToolbarButton2(
                manageSources,
                SVG.SETTINGS,
                this::showSourceManagement
        );
        sourceSummaryLabel.setTooltip(new Tooltip(manageSources));
        sourceSummaryLabel.setAccessibleText(manageSources);
        refreshSourceSummary();
        favoritesOnlyButton.getStyleClass().add("jfx-tool-bar-button");
        favoritesOnlyButton.setMinSize(40, 40);
        favoritesOnlyButton.setPrefSize(40, 40);
        favoritesOnlyButton.setMaxSize(40, 40);
        favoritesOnlyButton.setOnAction(event -> favoritesOnly.set(!favoritesOnly.get()));
        favoritesOnly.addListener((observable, oldValue, newValue) -> {
            refreshFavoritesFilterButton();
            applyFilter();
        });
        refreshFavoritesFilterButton();
        toolbar.getChildren().addAll(searchField, sourceSummaryLabel, favoritesOnlyButton, refreshButton, settingsButton);

        FlowPane filters = new FlowPane(12, 8);
        filters.setAlignment(Pos.CENTER_LEFT);
        filters.prefWrapLengthProperty().bind(widthProperty().subtract(20));

        categoryBox.setMinWidth(140);
        categoryBox.setPrefWidth(170);
        categoryBox.valueProperty().addListener((var observable, @Nullable var oldValue, @Nullable var newValue) ->
                applyFilter());

        filters.getChildren().add(categoryBox);

        HBox statusRow = new HBox(statusLabel);
        statusRow.setAlignment(Pos.CENTER_LEFT);
        statusLabel.setWrapText(true);
        statusLabel.setVisible(false);
        statusLabel.setManaged(false);
        statusLabel.visibleProperty().addListener((observable, oldValue, visible) ->
                statusLabel.setManaged(visible));
        statusRow.visibleProperty().bind(statusLabel.visibleProperty());
        statusRow.managedProperty().bind(statusRow.visibleProperty());
        loadingSpinner.setVisible(false);
        loadingSpinner.setManaged(false);
        loadingSpinner.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        loadingSpinner.setAccessibleText(i18n("plugin.store.loading"));
        loadingSpinner.visibleProperty().addListener((observable, oldValue, visible) ->
                loadingSpinner.setManaged(visible));

        pluginList.getStyleClass().add("no-padding");
        ScrollPane scrollPane = new ScrollPane(pluginList);
        scrollPane.setFitToWidth(true);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        FXUtils.smoothScrolling(scrollPane);
        FXUtils.setOverflowHidden(scrollPane, 8);

        StackPane contentPane = new StackPane(scrollPane, loadingSpinner);
        VBox.setVgrow(contentPane, Priority.ALWAYS);

        getChildren().setAll(toolbar, filters, statusRow, contentPane);
    }

    /// Creates a fixed icon-only toolbar command with an accessible name and hover explanation.
    ///
    /// @param tooltip localized command description
    /// @param icon familiar command glyph
    /// @param action command callback
    /// @return configured icon button
    private static JFXButton createIconButton(String tooltip, SVG icon, Runnable action) {
        JFXButton button = createToolbarButton2(null, icon, action);
        button.setMinSize(40, 40);
        button.setPrefSize(40, 40);
        button.setMaxSize(40, 40);
        button.setTooltip(new Tooltip(tooltip));
        button.setAccessibleText(tooltip);
        return button;
    }

    /// Synchronizes the favorites filter glyph, accent, tooltip, and accessibility text.
    private void refreshFavoritesFilterButton() {
        boolean selected = favoritesOnly.get();
        favoritesOnlyButton.setText(selected ? "\u2605" : "\u2606");
        String tooltip = i18n(selected
                ? "plugin.store.favorite.show_all"
                : "plugin.store.favorite.only");
        favoritesOnlyButton.setTooltip(new Tooltip(tooltip));
        favoritesOnlyButton.setAccessibleText(tooltip);
        favoritesOnlyButton.setStyle(selected ? "-fx-text-fill: -monet-primary;" : null);
    }

    /// Replaces the plugin rows with one explicit loading, error, empty, or no-result state.
    ///
    /// @param icon state glyph
    /// @param title localized state title
    /// @param subtitle optional localized explanation
    private void showStoreMessage(SVG icon, String title, @Nullable String subtitle) {
        LineButton message = new LineButton();
        message.setLeading(icon);
        message.setTitle(title);
        message.setSubtitle(subtitle);
        message.setMinHeight(72);
        message.setMouseTransparent(true);
        pluginList.getContent().setAll(message);
    }

    /// Shows all failed source diagnostics and direct actions without making the terminal message anonymous.
    ///
    /// @param snapshot completed aggregate whose enabled sources all failed
    /// @param presentation current terminal presentation state
    private void showAllFailedMessage(PluginStoreSnapshot snapshot, StorePresentation presentation) {
        List<Node> content = new ArrayList<>();
        LineButton message = new LineButton();
        message.setLeading(SVG.ERROR);
        message.setTitle(i18n("plugin.store.sources.all_failed"));
        message.setSubtitle(i18n("plugin.store.sources.all_failed.description"));
        message.setMouseTransparent(true);
        content.add(message);
        for (SourceFailureDiagnostic diagnostic : allFailedDiagnostics(snapshot)) {
            LineButton failure = new LineButton();
            failure.setLeading(SVG.WARNING);
            failure.setTitle(diagnostic.sourceName());
            failure.setSubtitle(diagnostic.message());
            failure.setMouseTransparent(true);
            content.add(failure);
        }
        JFXButton retry = createToolbarButton2(i18n("plugin.store.refresh"), SVG.REFRESH, this::loadPluginStore);
        retry.setDisable(!canRetryFailedSources(presentation, storeRefreshInProgress));
        JFXButton manage = createToolbarButton2(i18n("plugin.store.manage_sources"), SVG.SETTINGS, this::showSourceManagement);
        HBox actions = new HBox(8, retry, manage);
        actions.setAlignment(Pos.CENTER_LEFT);
        content.add(actions);
        pluginList.getContent().setAll(content);
    }

    /// Rebuilds the enabled-source summary without selecting or loading an individual custom source.
    private void refreshSourceSummary() {
        sourceSummaryLabel.setText(sourceSummary(sourceRepository.getSources()));
    }

    /// Counts enabled sources for the toolbar summary.
    ///
    /// @param sources configured source snapshot
    /// @return compact enabled-source summary
    static String sourceSummary(List<PluginSource> sources) {
        long enabled = sources.stream().filter(PluginSource::isEnabled).count();
        return i18n("plugin.store.sources.summary", enabled, sources.size());
    }

    /// Selects the aggregate presentation state without discarding a prior accepted snapshot during refresh.
    ///
    /// @param snapshot accepted aggregate snapshot
    /// @return immutable state used for catalog rows and banners
    static StorePresentation presentationFor(PluginStoreSnapshot snapshot) {
        return presentationFor(snapshot, false);
    }

    /// Selects the aggregate presentation state while documenting that a newer refresh may still be active.
    ///
    /// @param snapshot accepted aggregate snapshot
    /// @param refreshInProgress whether a newer source refresh remains in flight
    /// @return immutable state used for catalog rows and banners
    static StorePresentation presentationFor(PluginStoreSnapshot snapshot, boolean refreshInProgress) {
        @Unmodifiable List<PluginSourceLoadResult> enabledResults = snapshot.getSourceResults().stream()
                .filter(result -> result.getSource().isEnabled())
                .toList();
        int enabledSourceCount = enabledResults.size();
        int registryFailureCount = (int) enabledResults.stream()
                .filter(result -> result.getStatus() == PluginSourceLoadResult.Status.FAILED)
                .count();
        int degradedSourceCount = (int) enabledResults.stream()
                .filter(PluginStorePage::isDegradedSourceResult)
                .count();
        boolean hasConflicts = !snapshot.getConflictCandidates().isEmpty();
        StoreState state;
        if (enabledSourceCount == 0) {
            state = StoreState.NO_ENABLED_SOURCES;
        } else if (registryFailureCount == enabledSourceCount) {
            state = StoreState.ALL_FAILED;
        } else if (degradedSourceCount > 0) {
            state = StoreState.DEGRADED;
        } else if (hasConflicts) {
            state = StoreState.CONFLICTS;
        } else {
            state = StoreState.READY;
        }
        return new StorePresentation(state, enabledSourceCount, degradedSourceCount, hasConflicts, refreshInProgress);
    }

    /// Returns whether one enabled source left the aggregate catalog incomplete or unavailable.
    ///
    /// @param result source load outcome
    /// @return whether the source failed at registry level or has unavailable repository manifests
    private static boolean isDegradedSourceResult(PluginSourceLoadResult result) {
        return result.getStatus() == PluginSourceLoadResult.Status.FAILED
                || result.getStatus() == PluginSourceLoadResult.Status.PARTIAL_FAILURE;
    }

    /// Returns whether a completed aggregate result may replace the page's accepted snapshot.
    ///
    /// @param requestGeneration page refresh generation captured at request start
    /// @param currentGeneration latest page refresh generation
    /// @param completed completed aggregate result
    /// @param published newest snapshot admitted by the aggregator
    /// @param currentConfiguration current revision-bearing persisted source configuration
    /// @return whether the completion is current in the page, aggregator, and repository gates
    static boolean canPublishSnapshot(
            long requestGeneration,
            long currentGeneration,
            PluginStoreSnapshot completed,
            @Nullable PluginStoreSnapshot published,
            PluginSourceConfiguration currentConfiguration
    ) {
        return requestGeneration == currentGeneration
                && completed == published
                && completed.matchesSourceConfiguration(currentConfiguration);
    }

    /// Returns whether an aggregate snapshot remains usable for installation against persisted source configuration.
    ///
    /// @param snapshot aggregate catalog selected by the user
    /// @param configuration current revision-bearing persisted source configuration
    /// @return whether the snapshot has the current revision and exact ordered source values
    static boolean canUseSnapshotForInstallation(
            PluginStoreSnapshot snapshot,
            PluginSourceConfiguration configuration
    ) {
        return snapshot.matchesSourceConfiguration(configuration);
    }

    /// Returns whether an asynchronous install may continue using its originally confirmed source snapshot.
    ///
    /// @param snapshot aggregate snapshot that selected every remote plan entry
    /// @param configuration latest revision-bearing persisted source configuration
    /// @return whether downloading or publishing may continue
    static boolean canContinueInstallOperation(
            PluginStoreSnapshot snapshot,
            PluginSourceConfiguration configuration
    ) {
        return canUseSnapshotForInstallation(snapshot, configuration);
    }

    /// Returns safe priority-ordered diagnostics for every terminally failed enabled source.
    ///
    /// @param snapshot aggregate source outcome
    /// @return safe source identity and failure message pairs
    static @Unmodifiable List<SourceFailureDiagnostic> allFailedDiagnostics(PluginStoreSnapshot snapshot) {
        return snapshot.getSourceResults().stream()
                .filter(result -> result.getSource().isEnabled())
                .filter(result -> result.getStatus() == PluginSourceLoadResult.Status.FAILED)
                .map(result -> new SourceFailureDiagnostic(
                        PluginSourceLabels.displayName(result.getSource(), null),
                        Objects.requireNonNullElse(result.getFailureMessage(), i18n("plugin.store.source.unavailable"))
                ))
                .toList();
    }

    /// Returns whether the all-failed presentation may start another aggregate refresh.
    ///
    /// @param presentation current aggregate presentation
    /// @param refreshInProgress whether a retry or regular refresh is already pending
    /// @return whether a direct retry control should be enabled
    static boolean canRetryFailedSources(StorePresentation presentation, boolean refreshInProgress) {
        return presentation.state() == StoreState.ALL_FAILED && !refreshInProgress;
    }

    /// Tests whether a source-independent favorite ID includes the supplied aggregate winner.
    ///
    /// @param favoritePluginIds persisted favorite IDs
    /// @param item aggregate winner
    /// @return whether the winner is favorited by its stable plugin ID
    static boolean matchesFavorite(@Unmodifiable Set<String> favoritePluginIds, PluginStoreItem item) {
        return favoritePluginIds.contains(item.getEntry().getId());
    }

    /// Closes the persistent aggregate loader once after the owning application reports shutdown.
    ///
    /// This method intentionally does nothing while the cached tab is merely hidden, because tab reuse requires the
    /// same aggregator and source refresh executor to remain available until the application lifetime ends.
    void closeAtApplicationShutdown(boolean applicationStopped) {
        closeAggregatorWhenApplicationStops(applicationStopped, storeAggregatorClosed, storeAggregator::close);
    }

    /// Applies the same one-time shutdown rule to testable application and close callbacks.
    ///
    /// @param applicationStopped whether the application lifetime has ended
    /// @param closed whether the aggregate loader was already closed
    /// @param closeAggregator callback closing the page-owned aggregator
    static void closeAggregatorWhenApplicationStops(
            boolean applicationStopped,
            AtomicBoolean closed,
            Runnable closeAggregator
    ) {
        if (applicationStopped && closed.compareAndSet(false, true)) {
            closeAggregator.run();
        }
    }

    /// Opens the independent source-management page while retaining the store catalog on this page.
    private void showSourceManagement() {
        PluginSourceManagementPage page = new PluginSourceManagementPage(
                sourceRepository,
                () -> currentSnapshot,
                () -> Set.copyOf(installedManifests.keySet()),
                this::loadPluginStore
        );
        sourceManagementPage = page;
        Controllers.navigate(page);
    }

    /// Refreshes the aggregate catalog without clearing an accepted snapshot while requests are in flight.
    private void loadPluginStore() {
        long requestGeneration = ++storeRefreshGeneration;
        storeRefreshInProgress = true;
        refreshSourceSummary();
        applyFilter();
        loadingSpinner.setVisible(currentSnapshot == null);
        refreshInstalledManifests();
        storeAggregator.refresh(sourceRepository.getSourceConfiguration()).whenCompleteAsync(
                (@Nullable var snapshot, @Nullable var exception) -> {
                    if (exception != null) {
                        if (requestGeneration != storeRefreshGeneration) {
                            return;
                        }
                        if (currentSnapshot == null) {
                            showStoreMessage(
                                    SVG.ERROR,
                                    i18n("plugin.store.load_failed"),
                                    i18n("plugin.store.load_failed.detail", failureMessage(exception))
                            );
                        }
                        storeRefreshInProgress = false;
                        loadingSpinner.setVisible(false);
                        return;
                    }
                    PluginStoreSnapshot completed = Objects.requireNonNull(snapshot);
                    if (!canPublishSnapshot(
                            requestGeneration,
                            storeRefreshGeneration,
                            completed,
                            storeAggregator.getCurrentSnapshot(),
                            sourceRepository.getSourceConfiguration()
                    )) {
                        return;
                    }
                    publishCurrentSnapshot(completed);
                    allItems.clear();
                    allItems.addAll(completed.getWinningItems().values());
                    storeRefreshInProgress = false;
                    loadingSpinner.setVisible(false);
                    refreshCategories();
                    applyFilter();
                },
                Schedulers.javafx()
        );
    }

    /// Replaces the current source snapshot before rebuilding the open source-management page.
    ///
    /// @param snapshot newly published current source snapshot
    private void publishCurrentSnapshot(PluginStoreSnapshot snapshot) {
        publishSnapshotThenNotify(
                published -> currentSnapshot = published,
                snapshot,
                ignored -> publishSnapshotThenRefresh(currentSnapshot, sourceManagementPage)
        );
    }

    /// Publishes a source snapshot and then refreshes the open source-management page in strict publication order.
    ///
    /// @param snapshot new current source snapshot
    /// @param page open source-management page, or `null` when the page is not visible
    static void publishSnapshotThenRefresh(
            @Nullable PluginStoreSnapshot snapshot,
            @Nullable PluginSourceManagementPage page
    ) {
        if (snapshot != null && page != null) {
            page.refreshSourceRows();
        }
    }

    /// Replaces a snapshot target before notifying its consumer, preserving observable publication order.
    ///
    /// @param target current source snapshot replacement callback
    /// @param snapshot newly published snapshot
    /// @param onPublished consumer invoked after the target contains the snapshot
    static void publishSnapshotThenNotify(
            Consumer<PluginStoreSnapshot> target,
            PluginStoreSnapshot snapshot,
            Consumer<PluginStoreSnapshot> onPublished
    ) {
        target.accept(snapshot);
        onPublished.accept(snapshot);
    }

    /// Refreshes disk-level installation state without depending on successful lifecycle loading.
    private void refreshInstalledManifests() {
        try {
            @Unmodifiable Map<String, PluginManifest> published = pluginManager.getPublishedPluginManifests();
            @Unmodifiable Map<String, PluginManifest> planning = pluginManager.getInstalledManifests();
            installedManifests = published;
            installPlanningManifests = planning;
            installedManifestFailure = null;
        } catch (IOException exception) {
            installedManifests = Map.of();
            installPlanningManifests = Map.of();
            installedManifestFailure = failureMessage(exception);
            LOG.warning("Failed to read installed plugin manifests for the store", exception);
        }
    }

    /// Rebuilds category choices while preserving the previously selected category when possible.
    private void refreshCategories() {
        @Nullable CategoryItem selected = categoryBox.getSelectionModel().getSelectedItem();
        Set<String> categories = allItems.stream()
                .map(item -> item.getEntry().getCategory())
                .filter(StringUtils::isNotBlank)
                .collect(Collectors.toCollection(TreeSet::new));

        List<CategoryItem> items = new ArrayList<>();
        items.add(new CategoryItem(ALL_CATEGORIES, i18n("plugin.store.category.all")));
        categories.forEach(category -> items.add(new CategoryItem(category, category)));
        categoryBox.getItems().setAll(items);

        if (selected == null) {
            categoryBox.getSelectionModel().selectFirst();
            return;
        }
        categoryBox.getItems().stream()
                .filter(item -> item.value.equals(selected.value))
                .findFirst()
                .ifPresentOrElse(
                        categoryBox.getSelectionModel()::select,
                        categoryBox.getSelectionModel()::selectFirst
        );
    }

    /// Applies search, category, and favorites filters without resetting any filter control.
    private void applyFilter() {
        @Nullable PluginStoreSnapshot snapshot = currentSnapshot;
        if (snapshot == null) {
            if (!storeRefreshInProgress) {
                pluginList.getContent().clear();
            }
            return;
        }

        StorePresentation presentation = presentationFor(snapshot, storeRefreshInProgress);
        if (!presentation.showPluginRows()) {
            switch (presentation.state()) {
                case NO_ENABLED_SOURCES -> showStoreMessage(
                        SVG.SETTINGS,
                        i18n("plugin.store.sources.none_enabled"),
                        i18n("plugin.store.sources.none_enabled.description")
                );
                case ALL_FAILED -> showAllFailedMessage(snapshot, presentation);
                default -> showStoreMessage(SVG.INFO, i18n("plugin.store.empty"), i18n("plugin.store.empty.description"));
            }
            statusLabel.setText(statusTextFor(presentation, 0, 0, installedManifestFailure));
            statusLabel.setVisible(true);
            return;
        }

        pluginList.getContent().clear();
        String keyword = Optional.ofNullable(searchField.getText())
                .orElse("")
                .trim()
                .toLowerCase(Locale.ROOT);
        @Nullable CategoryItem category = categoryBox.getSelectionModel().getSelectedItem();

        @Unmodifiable List<PluginStoreItem> filtered = allItems.stream()
                .filter(item -> matchesCategory(item, category))
                .filter(item -> matchesKeyword(item, keyword))
                .filter(item -> !favoritesOnly.get()
                        || matchesFavorite(sourceRepository.getFavoritePluginIds(), item))
                .sorted((left, right) -> displayName(left).compareToIgnoreCase(displayName(right)))
                .toList();

        if (filtered.isEmpty()) {
            boolean registryEmpty = allItems.isEmpty();
            showStoreMessage(
                    SVG.INFO,
                    i18n(registryEmpty ? "plugin.store.empty" : "plugin.store.no_result"),
                    i18n(registryEmpty
                            ? "plugin.store.empty.description"
                            : "plugin.store.no_result.description")
            );
        } else {
            filtered.forEach(item -> pluginList.getContent().add(createPluginRow(item)));
        }

        statusLabel.setText(statusTextFor(presentation, filtered.size(), allItems.size(), installedManifestFailure));
        statusLabel.setVisible(true);
    }

    /// Builds current aggregate status text without allowing terminal states to retain a prior loaded counter.
    ///
    /// @param presentation accepted aggregate presentation
    /// @param filteredCount currently visible winner count
    /// @param totalCount accepted winner count before filtering
    /// @param installedFailure installed-manifest read failure, or `null` when readable
    /// @return current status text
    static String statusTextFor(
            StorePresentation presentation,
            int filteredCount,
            int totalCount,
            @Nullable String installedFailure
    ) {
        String status = switch (presentation.state()) {
            case NO_ENABLED_SOURCES -> i18n("plugin.store.sources.none_enabled");
            case ALL_FAILED -> i18n("plugin.store.sources.all_failed");
            default -> i18n("plugin.store.loaded") + ": " + filteredCount + "/" + totalCount
                    + " " + i18n("plugin.store.plugins");
        };
        List<String> statusLines = new ArrayList<>(List.of(status));
        if (presentation.state() == StoreState.DEGRADED) {
            statusLines.add(i18n("plugin.store.sources.degraded"));
        }
        if (presentation.hasConflicts()) {
            statusLines.add(i18n("plugin.store.sources.conflicts"));
        }
        if (installedFailure != null) {
            statusLines.add(i18n("plugin.installed.load_failed") + ": " + installedFailure);
        }
        return String.join("\n", statusLines);
    }

    /// Tests one item against the selected category.
    ///
    /// @param item store item
    /// @param category selected category or `null`
    /// @return whether the item belongs in the category result
    private static boolean matchesCategory(PluginStoreItem item, @Nullable CategoryItem category) {
        return category == null
                || ALL_CATEGORIES.equals(category.value)
                || Objects.equals(category.value, item.getEntry().getCategory());
    }

    /// Tests searchable registry metadata against a normalized keyword.
    ///
    /// @param item store item
    /// @param keyword lower-case keyword
    /// @return whether any searchable field contains the keyword
    private static boolean matchesKeyword(PluginStoreItem item, String keyword) {
        if (keyword.isEmpty()) {
            return true;
        }
        PluginStoreRegistry.PluginStoreEntry entry = item.getEntry();
        return contains(entry.getId(), keyword)
                || contains(entry.getName(), keyword)
                || contains(entry.getAuthor(), keyword)
                || contains(entry.getDescription(), keyword)
                || contains(entry.getCategory(), keyword)
                || entry.getTags().stream().anyMatch(tag -> contains(tag, keyword))
                || entry.getCapabilities().stream().anyMatch(capability -> contains(capability, keyword));
    }

    /// Performs a null-aware case-insensitive substring test.
    ///
    /// @param value source text or `null`
    /// @param keyword normalized keyword
    /// @return whether the source contains the keyword
    private static boolean contains(@Nullable String value, String keyword) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(keyword);
    }

    /// Returns the preferred display name for sorting and presentation.
    ///
    /// @param item store item
    /// @return registry name or plugin ID
    private static String displayName(PluginStoreItem item) {
        String name = item.getEntry().getName();
        return StringUtils.isBlank(name) ? item.getEntry().getId() : name;
    }

    /// Builds one native HMCL list row using the newest version compatible with the current runtime.
    ///
    /// @param item resolved store item
    /// @return plugin navigation row
    private LineButton createPluginRow(PluginStoreItem item) {
        PluginStoreRegistry.PluginStoreEntry entry = item.getEntry();
        PluginStoreManager sourceManager = item.getSourceManager();
        @Nullable PluginStoreManifest.PluginVersionEntry compatibleVersion =
                sourceManager.getLatestCompatibleVersion(item.getManifest());
        @Nullable PluginManifest installed = installedManifests.get(entry.getId());
        boolean hasUpdate = sourceManager.hasUpdate(installed, compatibleVersion);
        boolean hasConflict = currentSnapshot != null
                && currentSnapshot.getConflictCandidates().containsKey(entry.getId());
        boolean manifestLoadFailed = item.getManifest() == null;
        boolean installedStateUnavailable = installedManifestFailure != null;
        @Nullable PluginRuntimeStatus runtimeStatus = installed == null
                ? null
                : pluginManager.getPluginRuntimeStatus(entry.getId());

        LineButton row = LineButton.createNavigationButton();
        row.getStyleClass().add("plugin-store-row");
        row.setLeading(SVG.EXTENSION);
        row.setTitle(summarizeTitle(displayName(item)));
        row.setLargeTitle(true);
        row.setSubtitle(buildPluginRowSubtitle(item, compatibleVersion));
        row.setMinHeight(112);
        String status;
        if (manifestLoadFailed) {
            status = i18n("plugin.store.manifest_load_failed");
        } else if (installedStateUnavailable) {
            status = i18n("plugin.installed.load_failed");
        } else if (hasUpdate) {
            status = runtimeStatus == null
                    ? i18n("plugin.store.update_available")
                    : i18n("plugin.store.update_available") + "\n" + installedRuntimeStatus(runtimeStatus);
        } else if (installed != null) {
            status = installedRuntimeStatus(Objects.requireNonNull(runtimeStatus));
        } else if (compatibleVersion == null) {
            status = i18n("plugin.store.version.incompatible");
        } else {
            status = versionText(compatibleVersion);
        }
        row.setTrailingText(null);
        row.setTrailingIcon(createPluginTrailingActions(entry.getId(),
                hasConflict ? status + "\n" + i18n("plugin.store.source.conflict") : status, hasUpdate));
        row.setOnAction(event -> showPluginDetails(item));
        return row;
    }

    /// Returns the user-facing store status for one installed runtime artifact.
    ///
    /// A successfully enabled artifact retains the familiar store-level `Installed` label. Every non-running state
    /// uses the shared runtime-status translation so blocked, failed, staged, disabled, and pending-removal packages
    /// remain distinguishable.
    ///
    /// @param status authoritative artifact runtime status
    /// @return localized store row status
    private static String installedRuntimeStatus(PluginRuntimeStatus status) {
        return status == PluginRuntimeStatus.ENABLED
                ? i18n("plugin.store.installed")
                : PluginPermissionManagementPage.runtimeStatusLabel(status);
    }

    /// Builds the layered description, author, category, and version summary shown in one store row.
    ///
    /// @param item resolved store item
    /// @param version newest compatible version or `null`
    /// @return wrapped row subtitle
    static String buildPluginRowSubtitle(
            PluginStoreItem item,
            @Nullable PluginStoreManifest.PluginVersionEntry version
    ) {
        PluginStoreRegistry.PluginStoreEntry entry = item.getEntry();
        List<String> metadataLines = new ArrayList<>();
        metadataLines.add(i18n("plugin.store.source.label", sourceDisplayName(item)));
        String description = summarizeDescription(entry.getDescription());
        if (StringUtils.isNotBlank(entry.getAuthor())) {
            metadataLines.add(i18n("plugin.author") + ": " + entry.getAuthor());
        }
        if (StringUtils.isNotBlank(entry.getCategory())) {
            metadataLines.add(i18n("plugin.store.category") + ": " + entry.getCategory());
        }
        String versionSummary;
        if (item.getManifest() == null) {
            versionSummary = i18n("plugin.store.manifest_load_failed");
        } else if (version == null) {
            versionSummary = i18n("plugin.store.version.incompatible");
        } else {
            versionSummary = versionText(version);
        }
        metadataLines.add(i18n("plugin.store.version") + ": " + versionSummary);
        return composePluginRowSubtitle(description, metadataLines);
    }

    /// Returns a shared credential-safe provenance label for one source-bound catalog item.
    ///
    /// @param item source-bound aggregate winner
    /// @return compact source provenance label
    private static String sourceDisplayName(PluginStoreItem item) {
        return PluginSourceLabels.displayName(item.getSource(), item.getRegistry().getName());
    }

    /// Returns the newest compatible version label for a conflict candidate.
    ///
    /// @param item lower-priority candidate
    /// @return compact compatible version label or an unavailable marker
    private static String latestVersionText(PluginStoreItem item) {
        @Nullable PluginStoreManifest.PluginVersionEntry version = item.getSourceManager()
                .getLatestCompatibleVersion(item.getManifest());
        return version == null ? i18n("plugin.store.source.unavailable") : versionText(version);
    }

    /// Separates descriptive prose from scan-friendly metadata without producing empty edge lines.
    ///
    /// @param description normalized description or an empty string
    /// @param metadataLines author, category, and version lines
    /// @return subtitle with a blank separator only when both sections are present
    static String composePluginRowSubtitle(String description, List<String> metadataLines) {
        if (description.isEmpty()) {
            return String.join("\n", metadataLines);
        }
        if (metadataLines.isEmpty()) {
            return description;
        }
        return description + "\n\n" + String.join("\n", metadataLines);
    }

    /// Normalizes registry prose and bounds it to two narrow-window display lines.
    ///
    /// @param description registry description or `null`
    /// @return compact one-paragraph summary
    static String summarizeDescription(@Nullable String description) {
        if (StringUtils.isBlank(description)) {
            return "";
        }
        return summarizeDisplayText(Objects.requireNonNull(description), DESCRIPTION_DISPLAY_UNITS);
    }

    /// Normalizes and bounds a plugin name so fixed trailing actions retain their minimum-width allocation.
    ///
    /// @param title registry plugin name
    /// @return compact single-paragraph row title
    static String summarizeTitle(String title) {
        return summarizeDisplayText(title, TITLE_DISPLAY_UNITS);
    }

    /// Normalizes one row string and truncates it by deterministic narrow and wide display units.
    ///
    /// @param value source row text
    /// @param maximumUnits maximum weighted display width
    /// @return normalized and possibly ellipsized text
    private static String summarizeDisplayText(String value, int maximumUnits) {
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

    /// Counts ASCII as one display unit and wide Unicode text as two for deterministic narrow-row truncation.
    ///
    /// @param value normalized display text
    /// @return weighted display width
    private static int displayUnits(String value) {
        return value.codePoints().map(codePoint -> codePoint <= 0x7f ? 1 : 2).sum();
    }

    /// Groups the right-aligned status, fixed favorite button, and navigation arrow outside the title column.
    ///
    /// @param pluginId plugin ID controlled by the favorite button
    /// @param status current installed, update, or version label
    /// @param updateAvailable whether the newest compatible version is newer than the installed artifact
    /// @return stable trailing action group
    private HBox createPluginTrailingActions(
            String pluginId,
            String status,
            boolean updateAvailable
    ) {
        HBox actions = new HBox(8);
        actions.setAlignment(Pos.CENTER_RIGHT);
        actions.setMinWidth(Region.USE_PREF_SIZE);
        actions.setMinHeight(68);
        actions.setPrefHeight(68);

        Label statusLabel = new Label(status);
        statusLabel.getStyleClass().add("trailing-label");
        statusLabel.setAlignment(Pos.CENTER_RIGHT);
        statusLabel.setTextAlignment(TextAlignment.RIGHT);
        statusLabel.setWrapText(true);
        statusLabel.setMinWidth(104);
        statusLabel.setMaxWidth(140);
        if (updateAvailable) {
            statusLabel.setStyle("-fx-text-fill: -monet-primary;");
        }
        Node arrow = SVG.ARROW_FORWARD.createIcon(20);
        arrow.getStyleClass().add("trailing-icon");
        arrow.setMouseTransparent(true);
        actions.getChildren().addAll(statusLabel, createFavoriteButton(pluginId), arrow);
        return actions;
    }

    /// Returns a compact display label for a resolved compatible remote version.
    ///
    /// @param version resolved remote version
    /// @return localized version text
    private static String versionText(PluginStoreManifest.PluginVersionEntry version) {
        return "v" + version.getVersion();
    }

    /// Creates the familiar star affordance for a persisted favorite.
    ///
    /// @param pluginId plugin ID whose favorite state is controlled
    /// @return configured star button
    private JFXButton createFavoriteButton(String pluginId) {
        JFXButton button = new JFXButton();
        button.getStyleClass().add("jfx-tool-bar-button");
        button.setMinSize(36, 36);
        button.setPrefSize(36, 36);
        button.setMaxSize(36, 36);
        updateFavoriteButton(button, sourceRepository.isFavorite(pluginId));
        button.setOnMouseClicked(event -> event.consume());
        button.setOnAction(event -> {
            boolean favorite = !sourceRepository.isFavorite(pluginId);
            sourceRepository.setFavorite(pluginId, favorite);
            updateFavoriteButton(button, sourceRepository.isFavorite(pluginId));
            if (favoritesOnly.get() && !favorite) {
                applyFilter();
            }
        });
        return button;
    }

    /// Synchronizes star glyph, color, and tooltip with favorite state.
    ///
    /// @param button favorite button
    /// @param favorite current favorite state
    private static void updateFavoriteButton(JFXButton button, boolean favorite) {
        button.setText(favorite ? "\u2605" : "\u2606");
        button.setTooltip(new Tooltip(i18n(favorite
                ? "plugin.store.favorite.remove"
                : "plugin.store.favorite.add")));
        button.setAccessibleText(button.getTooltip().getText());
    }

    /// Shows all published versions and updates every detail section when selection changes.
    ///
    /// @param item resolved store item
    private void showPluginDetails(PluginStoreItem item) {
        @Nullable PluginStoreManifest manifest = item.getManifest();
        if (manifest == null) {
            showError(i18n("plugin.store.load_failed"), i18n("plugin.store.manifest_load_failed"));
            return;
        }
        PluginDetailsPage detailsPage = new PluginDetailsPage(item, manifest);
        activeDetailsPage = detailsPage;
        Controllers.navigate(detailsPage);
    }

    /// Returns a localized compatibility status and backend validation reason.
    ///
    /// @param sourceManager manager bound to the selected store item
    /// @param version selected remote version
    /// @return compatibility text
    static String compatibilityText(
            PluginStoreManager sourceManager,
            PluginStoreManifest.PluginVersionEntry version
    ) {
        try {
            sourceManager.validateCompatibility(version);
            List<String> requirements = new ArrayList<>();
            String launcherVersion = version.getLauncherVersion();
            if (!launcherVersion.isBlank() && !launcherVersion.equals("*")) {
                requirements.add(i18n("plugin.store.compatibility.launcher", launcherVersion));
            }
            if (!version.getRequiredJavaVersion().isBlank()) {
                requirements.add(i18n("plugin.store.compatibility.java", version.getRequiredJavaVersion()));
            }
            requirements.add(i18n("plugin.store.compatibility.plugin_api", version.getPluginApiVersion()));
            return i18n("plugin.store.compatibility.current") + " - " + String.join(", ", requirements);
        } catch (IOException exception) {
            return i18n("plugin.store.compatibility.incompatible", failureMessage(exception));
        }
    }

    /// Returns the complete launcher constraint for display without reducing it to a minimum version.
    ///
    /// @param launcherVersion normalized HMCL CE version constraint
    /// @return full constraint or a localized unrestricted label
    static String launcherVersionRequirementText(String launcherVersion) {
        return launcherVersion.isBlank() || launcherVersion.equals("*")
                ? i18n("plugin.store.compatibility.any_launcher")
                : launcherVersion;
    }

    /// Selects the semantic icon for one version compatibility result.
    ///
    /// @param compatible whether the version can run on the current launcher
    /// @return success or error icon matching the compatibility state
    static SVG compatibilityIcon(boolean compatible) {
        return compatible ? SVG.CHECK_CIRCLE : SVG.ERROR;
    }

    /// Loads README text asynchronously and exposes loading, retry, unavailable, and error states.
    ///
    /// @param item source-bound item declaring the repository manifest
    /// @param manifest repository manifest
    /// @param container README section content
    /// @param status status label retained across retries
    /// @param retryButton retry action
    private void loadReadme(
            PluginStoreItem item,
            PluginStoreManifest manifest,
            VBox container,
            Label status,
            JFXButton retryButton
    ) {
        container.getChildren().setAll(status, retryButton);
        status.setVisible(true);
        status.setManaged(true);
        retryButton.setVisible(false);
        retryButton.setManaged(false);
        if (manifest.getReadmeUrl().isBlank()) {
            status.setText(i18n("plugin.store.readme.unavailable"));
            return;
        }
        status.setText(i18n("plugin.store.readme.loading"));

        Task.supplyAsync(() -> {
            try {
                return item.getSourceManager().fetchReadme(item);
            } catch (IOException exception) {
                throw new RuntimeException(exception);
            }
        }).whenComplete(Schedulers.javafx(), (@Nullable var readme, @Nullable var exception) -> {
            if (exception != null) {
                status.setText(i18n("plugin.store.readme.failed", failureMessage(exception)));
                retryButton.setVisible(true);
                retryButton.setManaged(true);
                return;
            }
            if (readme.isBlank()) {
                status.setText(i18n("plugin.store.readme.unavailable"));
                return;
            }
            if (looksLikeHtml(readme)) {
                renderHtmlReadmeAsync(readme, manifest.getReadmeUrl(), container, status, retryButton);
            } else {
                status.setText("");
                status.setVisible(false);
                status.setManaged(false);
                TextArea text = new TextArea(readme);
                text.setEditable(false);
                text.setWrapText(true);
                text.setPrefRowCount(16);
                text.setMaxHeight(360);
                container.getChildren().setAll(text);
            }
        }).start();
    }

    /// Renders HTML README content off the JavaFX thread using the launcher's existing HTML renderer.
    ///
    /// @param html bounded README HTML
    /// @param baseUri README source URL used for relative links
    /// @param container README destination
    /// @param status reusable status label
    /// @param retryButton reusable retry action
    private void renderHtmlReadmeAsync(
            String html,
            String baseUri,
            VBox container,
            Label status,
            JFXButton retryButton
    ) {
        Task.supplyAsync(() -> renderHtmlReadme(html, baseUri))
                .whenComplete(Schedulers.javafx(), (@Nullable var rendered, @Nullable var exception) -> {
                    if (exception != null) {
                        status.setVisible(true);
                        status.setManaged(true);
                        status.setText(i18n("plugin.store.readme.failed", failureMessage(exception)));
                        retryButton.setVisible(true);
                        retryButton.setManaged(true);
                        container.getChildren().setAll(status, retryButton);
                        return;
                    }
                    container.getChildren().setAll(rendered);
                }).start();
    }

    /// Converts HTML into a JavaFX text flow with clickable links.
    ///
    /// @param html HTML source
    /// @param baseUri base URI for relative resources
    /// @return rendered JavaFX node
    private static Node renderHtmlReadme(String html, String baseUri) {
        Document document = parseSafeReadmeHtml(html, baseUri);
        HTMLRenderer renderer = new HTMLRenderer(uri -> {
            if (isSafeExternalLink(uri.toString())) {
                FXUtils.openLink(uri.toString());
            }
        });
        for (org.jsoup.nodes.Node node : document.body().childNodes()) {
            renderer.appendNode(node);
        }
        renderer.mergeLineBreaks();
        return renderer.render();
    }

    /// Parses repository HTML while removing elements that could automatically load remote resources.
    ///
    /// Hyperlinks remain available as explicit user actions, but images, embedded documents, media, scripts,
    /// and styles are excluded so README rendering cannot bypass the plugin-store transport policy.
    ///
    /// @param html bounded README HTML
    /// @param baseUri README source URL used to resolve user-clicked links
    /// @return sanitized HTML document
    static Document parseSafeReadmeHtml(String html, String baseUri) {
        Document document = Jsoup.parse(html, baseUri);
        document.select("img, picture, source, video, audio, iframe, object, embed, link, script, style").remove();
        for (Element link : document.select("a[href]")) {
            String resolved = link.absUrl("href");
            if (isSafeExternalLink(resolved)) {
                link.attr("href", resolved);
            } else {
                link.removeAttr("href");
            }
        }
        return document;
    }

    /// Returns whether an explicit store hyperlink uses HTTPS or loopback-only development HTTP.
    ///
    /// @param url resolved hyperlink URL or `null`
    /// @return whether the link may be handed to the operating-system browser
    static boolean isSafeExternalLink(@Nullable String url) {
        if (StringUtils.isBlank(url)) {
            return false;
        }
        try {
            URI uri = new URI(Objects.requireNonNull(url));
            @Nullable String host = uri.getHost();
            if (host == null) {
                return false;
            }
            if ("https".equalsIgnoreCase(uri.getScheme())) {
                return true;
            }
            return "http".equalsIgnoreCase(uri.getScheme()) && isLoopbackHost(host);
        } catch (URISyntaxException exception) {
            return false;
        }
    }

    /// Returns whether a host denotes the local machine for development-only HTTP links.
    ///
    /// @param host parsed URI host
    /// @return whether the host is the literal loopback hostname or an exact loopback address
    private static boolean isLoopbackHost(String host) {
        String normalized = host.toLowerCase(Locale.ROOT);
        if ("localhost".equals(normalized) || "::1".equals(normalized) || "[::1]".equals(normalized)) {
            return true;
        }
        String[] octets = normalized.split("\\.", -1);
        if (octets.length != 4 || !"127".equals(octets[0])) {
            return false;
        }
        for (int index = 1; index < octets.length; index++) {
            try {
                int octet = Integer.parseInt(octets[index]);
                if (octet < 0 || octet > 255 || !Integer.toString(octet).equals(octets[index])) {
                    return false;
                }
            } catch (NumberFormatException exception) {
                return false;
            }
        }
        return true;
    }

    /// Detects content that is already HTML instead of Markdown or plain text.
    ///
    /// @param content README content
    /// @return whether an HTML renderer should be used
    private static boolean looksLikeHtml(String content) {
        return content.matches("(?is).*<(html|body|h[1-6]|p|div|ul|ol|li|table|pre|blockquote|a|img|br)(\\s|>|/).*?");
    }

    /// Resolves a complete dependency plan for the explicitly selected version before confirmation.
    ///
    /// @param item selected store item
    /// @param version explicitly selected version
    private void resolveAndConfirmInstall(
            PluginStoreItem item,
            PluginStoreManifest.PluginVersionEntry version
    ) {
        PluginDialogs.ProgressDialog resolvingDialog = PluginDialogs.showProgress(
                i18n("plugin.store.installing"),
                i18n("plugin.store.install.resolving")
        );

        Task.supplyAsync(() -> {
            try {
                PluginInstallationPlanningSnapshot planningSnapshot =
                        pluginManager.getInstallationPlanningSnapshot();
                @Nullable PluginStoreSnapshot snapshot = currentSnapshot;
                if (snapshot == null || !canUseSnapshotForInstallation(snapshot, sourceRepository.getSourceConfiguration())) {
                    throw new IOException(i18n("plugin.store.catalog.unavailable"));
                }
                PluginInstallPlan plan = new PluginStoreDependencyResolver(
                        snapshot.getWinningItems(),
                        snapshot.getSourceConfiguration().getSources()
                ).resolveInstallPlan(
                        item.getEntry().getId(),
                        version,
                        planningSnapshot.getManifests(),
                        planningSnapshot.getInstalledArtifacts(),
                        planningSnapshot.getReusableArtifacts(),
                        planningSnapshot.getActivatableArtifacts()
                );
                return new ResolvedInstallPlan(plan, snapshot);
            } catch (IOException exception) {
                throw new RuntimeException(exception);
            }
        }).whenComplete(Schedulers.javafx(), (@Nullable var plan, @Nullable var exception) -> {
            resolvingDialog.close();
            if (exception != null) {
                showError(
                        i18n("plugin.store.install.conflict"),
                        i18n("plugin.store.install.plan_failed", failureMessage(exception))
                );
                return;
            }
            ResolvedInstallPlan resolved = Objects.requireNonNull(plan);
            confirmInstallPlan(resolved.plan, resolved.snapshot);
        }).start();
    }

    /// Shows one declaration-limited permission grant group for every package that will change.
    ///
    /// Updates always require a fresh confirmation. Existing grants that remain declared are preselected,
    /// while permissions newly requested by the selected version start denied. Reused dependencies are shown
    /// in the plan but cannot modify permission state.
    ///
    /// @param plan dependency-first resolved plan
    /// @param snapshot immutable aggregate snapshot that selected the plan's winners
    private void confirmInstallPlan(PluginInstallPlan plan, PluginStoreSnapshot snapshot) {
        if (!canUseSnapshotForInstallation(snapshot, sourceRepository.getSourceConfiguration())) {
            showError(i18n("plugin.store.install.conflict"), i18n("plugin.store.catalog.unavailable"));
            return;
        }
        PluginInstallPlan.Entry root = plan.getRootEntry();
        List<PluginPermissionRequest> requests = new ArrayList<>();
        try {
            for (PluginInstallPlan.Entry entry : plan.getPermissionReviewEntries()) {
                @Unmodifiable Set<PluginPermission> previousGrants =
                        entry.getAction() == PluginInstallPlan.Action.UPDATE
                                ? pluginManager.getGrantedPermissions(entry.getPluginId())
                                : Set.of();
                @Nullable PluginManifest installedManifest = entry.getInstalledManifest();
                @Unmodifiable List<PluginPermission> previousRequiredPermissions =
                        installedManifest == null ? List.of() : installedManifest.getRequiredPermissions();
                @Unmodifiable List<PluginPermission> previousOptionalPermissions =
                        installedManifest == null ? List.of() : installedManifest.getOptionalPermissions();
                requests.add(createPermissionReviewRequest(
                        entry.getPluginId(),
                        entry.getDisplayName(),
                        entry.getVersion(),
                        entry.getAction(),
                        entry.getRequiredPermissions(),
                        entry.getOptionalPermissions(),
                        previousGrants,
                        previousRequiredPermissions,
                        previousOptionalPermissions
                ));
            }
        } catch (IOException exception) {
            showError(i18n("plugin.store.install_failed"), failureMessage(exception));
            return;
        }

        PluginDialogs.confirmPluginInstall(
                root.getDisplayName(),
                root.getAction() == PluginInstallPlan.Action.UPDATE,
                List.copyOf(requests),
                formatInstallPlan(plan),
                degradedCatalogWarning(snapshot),
                plan.getCustomSourceProviderIds(),
                plan.getDangerousPermissionPluginIds(),
                (grantsByPluginId, confirmedCustomSourceProviderIds, confirmedDangerousPermissionPluginIds) -> {
                    if (!canUseSnapshotForInstallation(snapshot, sourceRepository.getSourceConfiguration())) {
                        showError(i18n("plugin.store.install.conflict"), i18n("plugin.store.catalog.unavailable"));
                        return;
                    }
                    executeInstallPlan(
                            plan,
                            snapshot,
                            grantsByPluginId,
                            new PluginRuntimeInstallAuthorization(
                                    plan.getRuntimeBindings(),
                                    Set.copyOf(plan.getEnablementPluginIds()),
                                    Set.copyOf(plan.getCustomSourceProviderIds()),
                                    confirmedCustomSourceProviderIds,
                                    Set.copyOf(plan.getDangerousPermissionPluginIds()),
                                    confirmedDangerousPermissionPluginIds,
                                    plan.getExpectedPackageRuntimeContracts()
                            )
                    );
                }
        );
    }

    /// Returns the root action produced by dependency planning for the current future installed state.
    ///
    /// Pending-uninstall artifacts are absent from the planning snapshot, so reinstalling one is presented as a
    /// fresh installation just as [PluginStoreManager#resolveInstallPlan] will classify it.
    ///
    /// @param planningManifest future installed manifest or `null` when the root will be newly installed
    /// @return install or update action used by both the details button and confirmation workflow
    static PluginInstallPlan.Action rootInstallationAction(@Nullable PluginManifest planningManifest) {
        return planningManifest == null
                ? PluginInstallPlan.Action.INSTALL
                : PluginInstallPlan.Action.UPDATE;
    }

    /// Calculates the preselected grants for one fresh installation review.
    ///
    /// Required permissions are always selected. New installations leave every optional permission denied, while
    /// updates preserve only optional permissions that were previously granted and remain optional in the target.
    ///
    /// @param action resolved plan action
    /// @param requiredPermissions permissions required by the selected version
    /// @param optionalPermissions optional permissions requested by the selected version
    /// @param previousGrants grants stored for the installed version
    /// @return immutable required permissions plus inherited optional grants
    static @Unmodifiable Set<PluginPermission> initialPermissionsFor(
            PluginInstallPlan.Action action,
            List<PluginPermission> requiredPermissions,
            List<PluginPermission> optionalPermissions,
            Set<PluginPermission> previousGrants
    ) {
        Set<PluginPermission> initiallyGranted = new LinkedHashSet<>(requiredPermissions);
        if (action == PluginInstallPlan.Action.UPDATE) {
            for (PluginPermission permission : optionalPermissions) {
                if (previousGrants.contains(permission)) {
                    initiallyGranted.add(permission);
                }
            }
        }
        return Set.copyOf(initiallyGranted);
    }

    /// Creates one editable permission request for an artifact that will be installed or updated.
    ///
    /// Updates always produce a request, including when their declaration is unchanged or empty. Reused artifacts
    /// cannot produce a request because their package and permission decision remain unchanged.
    ///
    /// @param pluginId stable plugin ID
    /// @param displayName human-readable plugin name
    /// @param version exact target version
    /// @param action resolved install-plan action
    /// @param requiredPermissions permissions required by the target artifact
    /// @param optionalPermissions optional permissions requested by the target artifact
    /// @param previousGrants grants belonging to the currently installed artifact
    /// @param previousRequiredPermissions permissions required by the installed artifact
    /// @param previousOptionalPermissions optional permissions declared by the installed artifact
    /// @return editable declaration-limited permission review request
    /// @throws IllegalArgumentException if the artifact is reused rather than changed
    static PluginPermissionRequest createPermissionReviewRequest(
            String pluginId,
            String displayName,
            String version,
            PluginInstallPlan.Action action,
            List<PluginPermission> requiredPermissions,
            List<PluginPermission> optionalPermissions,
            Set<PluginPermission> previousGrants,
            List<PluginPermission> previousRequiredPermissions,
            List<PluginPermission> previousOptionalPermissions
    ) {
        if (action == PluginInstallPlan.Action.REUSE) {
            throw new IllegalArgumentException("Reused plugin artifacts do not require permission review");
        }
        return new PluginPermissionRequest(
                pluginId,
                displayName,
                version,
                requiredPermissions,
                optionalPermissions,
                initialPermissionsFor(action, requiredPermissions, optionalPermissions, previousGrants),
                true,
                action == PluginInstallPlan.Action.UPDATE,
                previousRequiredPermissions,
                previousOptionalPermissions
        );
    }

    /// Builds a compact warning from the exact immutable snapshot that selected an installation plan.
    ///
    /// @param snapshot aggregate snapshot used during dependency resolution, or `null` when unavailable
    /// @return warning without source URLs, or `null` when every enabled source is available
    static @Nullable String degradedCatalogWarning(@Nullable PluginStoreSnapshot snapshot) {
        if (snapshot == null) {
            return null;
        }
        @Unmodifiable List<PluginSourceLoadResult> degradedResults = snapshot.getSourceResults().stream()
                .filter(result -> result.getSource().isEnabled())
                .filter(PluginStorePage::isDegradedSourceResult)
                .toList();
        if (degradedResults.isEmpty()) {
            return null;
        }
        String failedSources = degradedResults.stream()
                .map(PluginSourceLoadResult::getSource)
                .map(PluginStorePage::warningSourceLabel)
                .collect(Collectors.joining(", "));
        return i18n("plugin.store.install.degraded_sources_warning", failedSources);
    }

    /// Returns a shared credential-safe source warning label.
    ///
    /// @param source failed source configuration
    /// @return display-safe source label
    private static String warningSourceLabel(PluginSource source) {
        return PluginSourceLabels.displayName(source, null);
    }

    /// Formats immutable selected-source security provenance for final installation confirmation.
    ///
    /// @param provenance source trust status and configured host captured during resolution
    /// @return localized provenance that never depends on an alias or remote registry name
    private static String formatInstallProvenance(PluginSourceProvenance provenance) {
        String key = switch (provenance.getTrustLevel()) {
            case OFFICIAL -> "plugin.store.source.provenance.official";
            case CERTIFIED -> "plugin.store.source.provenance.certified";
            case COMMUNITY -> "plugin.store.source.provenance.third_party";
            case REJECTED -> "plugin.store.source.provenance.rejected";
        };
        return i18n(key, provenance.getHostIdentity());
    }

    /// Returns the localized label key for one exact selected version's trust level.
    ///
    /// @param level verified version trust level
    /// @return resource-bundle key
    static String trustLabelKey(PluginTrustLevel level) {
        return switch (level) {
            case OFFICIAL -> "plugin.store.trust.official";
            case CERTIFIED -> "plugin.store.trust.certified";
            case COMMUNITY -> "plugin.store.trust.community";
            case REJECTED -> "plugin.store.trust.rejected";
        };
    }

    /// Returns whether the selected compatible version may proceed to permission review.
    ///
    /// @param compatible runtime compatibility
    /// @param trust exact selected-version trust
    /// @return whether installation or update remains enabled
    static boolean canInstallSelectedVersion(boolean compatible, PluginTrustResult trust) {
        return compatible && trust.canInstall();
    }

    /// Formats actions and dependency constraints for every plan entry in topological order.
    ///
    /// @param plan resolved plan
    /// @return immutable plan rows
    static @Unmodifiable List<String> formatInstallPlan(PluginInstallPlan plan) {
        List<String> rows = new ArrayList<>();
        for (PluginInstallPlan.Entry entry : plan.getEntries()) {
            switch (entry.getAction()) {
                case REUSE -> rows.add(i18n(
                        "plugin.store.plan.keep",
                        entry.getDisplayName(),
                        entry.getVersion()
                ));
                case ENABLE -> rows.add(i18n(
                        "plugin.store.plan.enable",
                        entry.getDisplayName(),
                        entry.getVersion()
                ));
                case INSTALL -> rows.add(i18n(
                        "plugin.store.source.plan_entry",
                        i18n(
                                "plugin.store.plan.install",
                                entry.getDisplayName(),
                                entry.getVersion()
                        ),
                        formatInstallProvenance(entry.requireSourceProvenance())
                ));
                case UPDATE -> {
                    @Nullable PluginManifest installed = entry.getInstalledManifest();
                    rows.add(i18n(
                            "plugin.store.source.plan_entry",
                            i18n(
                                    "plugin.store.plan.update",
                                    entry.getDisplayName(),
                                    installed == null ? "?" : installed.getVersion(),
                                    entry.getVersion()
                            ),
                            formatInstallProvenance(entry.requireSourceProvenance())
                    ));
                }
            }
            for (PluginDependency dependency : entry.getDependencies()) {
                rows.add(i18n(
                        "plugin.store.plan.dependency",
                        entry.getDisplayName(),
                        dependency.getId(),
                        dependency.getVersion()
                ));
            }
            if (entry.requiresDownload()) {
                rows.add(i18n(
                        "plugin.store.plan.artifact",
                        entry.getPluginId(),
                        entry.requireSelectedArtifact().platform().getId(),
                        entry.getSelectedDownloadSize()
                ));
            }
            if (entry.isRuntimeProvider()) {
                rows.add(i18n("plugin.store.plan.runtime_provider", entry.getPluginId()));
            }
        }
        for (RuntimeProviderBinding binding : plan.getRuntimeBindings().values()) {
            PluginInstallPlan.Entry dependent = plan.getEntries().stream()
                    .filter(entry -> entry.getPluginId().equals(binding.dependentPluginId()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "Runtime binding dependent is absent from the plan: " + binding.dependentPluginId()));
            rows.add(i18n(
                    "plugin.store.plan.runtime_binding",
                    binding.dependentPluginId(),
                    binding.providerId(),
                    binding.runtime(),
                    dependent.getExecutionMode().getId()
            ));
        }
        if (!plan.getDownloadEntries().isEmpty()) {
            rows.add(i18n("plugin.store.plan.download_total", plan.getTotalDownloadSize()));
        }
        return List.copyOf(rows);
    }

    /// Downloads every mutable plan entry into isolated staging before any installed file is touched.
    ///
    /// @param plan confirmed installation plan
    /// @param snapshot aggregate snapshot that selected every remote plan entry
    /// @param grantsByPluginId immutable grants chosen for every changed plugin
    /// @param runtimeAuthorization confirmed runtime Provider authorization
    private void executeInstallPlan(
            PluginInstallPlan plan,
            PluginStoreSnapshot snapshot,
            @Unmodifiable Map<String, @Unmodifiable Set<PluginPermission>> grantsByPluginId,
            PluginRuntimeInstallAuthorization runtimeAuthorization
    ) {
        PluginDialogs.ProgressDialog progressDialog = PluginDialogs.showProgress(
                i18n("plugin.store.installing"),
                i18n("plugin.store.install.resolving")
        );

        Task.supplyAsync(() -> {
            @Nullable Path stagingDirectory = null;
            try {
                stagingDirectory = Files.createTempDirectory("hmcl-plugin-plan-");
                Map<String, Path> stagedPackages = new LinkedHashMap<>();
                Map<String, PluginCertificationReceipt> certificationReceipts = new LinkedHashMap<>();
                @Unmodifiable List<PluginInstallPlan.Entry> downloads = plan.getDownloadEntries();
                for (int index = 0; index < downloads.size(); index++) {
                    requireCurrentInstallSnapshot(snapshot);
                    PluginInstallPlan.Entry entry = downloads.get(index);
                    int current = index + 1;
                    progressDialog.setStatus(i18n(
                            "plugin.store.install.downloading_package",
                            entry.getDisplayName(),
                            current,
                            downloads.size()
                    ));
                    PluginStoreManifest.PluginVersionEntry remoteVersion =
                            Objects.requireNonNull(entry.getRemoteVersion(), "Download entry has no remote version");
                    Path staged = entry.requireSourceManager().downloadPluginToStaging(
                            entry.getPluginId(),
                            remoteVersion,
                            stagingDirectory
                    );
                    stagedPackages.put(entry.getPluginId(), staged);
                    collectCertificationReceipt(
                            certificationReceipts,
                            entry.getPluginId(),
                            remoteVersion.getTrust()
                    );
                }
                requireCurrentInstallSnapshot(snapshot);
                return new InstallOperation(
                        plan,
                        snapshot,
                        stagingDirectory,
                        stagedPackages,
                        grantsByPluginId,
                        certificationReceipts,
                        runtimeAuthorization
                );
            } catch (IOException | RuntimeException exception) {
                if (stagingDirectory != null) {
                    cleanupStaging(stagingDirectory);
                }
                throw new RuntimeException(exception);
            }
        }).whenComplete(Schedulers.javafx(), (@Nullable var operation, @Nullable var exception) -> {
            if (exception != null) {
                progressDialog.close();
                showError(i18n("plugin.store.install_failed"), failureMessage(exception));
                return;
            }
            progressDialog.setStatus(i18n("plugin.store.install.download_complete"));
            publishRestartPlan(operation, progressDialog);
        }).start();
    }

    /// Retains an optional legacy proof receipt without requiring one for official-reference certification.
    ///
    /// @param receipts mutable receipt map for the pending installation transaction
    /// @param pluginId plugin receiving the selected package
    /// @param trust selected package trust result
    static void collectCertificationReceipt(
            Map<String, PluginCertificationReceipt> receipts,
            String pluginId,
            PluginTrustResult trust
    ) {
        if (trust.level() != PluginTrustLevel.CERTIFIED) {
            return;
        }
        @Nullable PluginCertificationReceipt receipt = trust.certificationReceipt();
        if (receipt != null) {
            receipts.put(pluginId, receipt);
        }
    }

    /// Rejects a background install operation whose exact source configuration is no longer persisted.
    ///
    /// @param snapshot aggregate snapshot that selected the operation
    /// @throws IOException if a source was disabled, deleted, edited, or reordered
    private void requireCurrentInstallSnapshot(PluginStoreSnapshot snapshot) throws IOException {
        if (!canContinueInstallOperation(snapshot, sourceRepository.getSourceConfiguration())) {
            throw new IOException(i18n("plugin.store.catalog.unavailable"));
        }
    }

    /// Inspects every staged package, then atomically publishes the complete restart installation in one manager call.
    ///
    /// The inspection list retains dependency-first plan order. [PluginManager#stagePluginInstallations] validates
    /// the complete future graph and rolls back publication as one transaction, so no package is loaded against an
    /// old in-memory dependency and no intermediate replacement graph is exposed.
    ///
    /// @param operation staged install operation
    /// @param progressDialog shared progress dialog
    private void publishRestartPlan(
            InstallOperation operation,
            PluginDialogs.ProgressDialog progressDialog
    ) {
        Task.runAsync(() -> {
            try {
                @Unmodifiable List<PluginInstallPlan.Entry> downloads = operation.plan.getDownloadEntries();
                List<LocalPluginInspection> inspections = new ArrayList<>();
                for (int index = 0; index < downloads.size(); index++) {
                    PluginInstallPlan.Entry entry = downloads.get(index);
                    int current = index + 1;
                    progressDialog.setStatus(i18n(
                            "plugin.store.install.publishing_package",
                            entry.getDisplayName(),
                            current,
                            downloads.size()
                    ));
                    Path stagedPackage = Objects.requireNonNull(
                            operation.stagedPackages.get(entry.getPluginId()),
                            "No staged package for " + entry.getPluginId()
                    );
                    inspections.add(pluginManager.inspectStorePluginPackage(stagedPackage));
                }
                sourceRepository.executeIfSourcesMatch(
                        operation.snapshot.getSourceConfiguration(),
                        () -> pluginManager.stagePluginInstallations(
                                List.copyOf(inspections),
                                operation.grantsByPluginId,
                                operation.plan.getReusableArtifactIdentities(),
                                operation.plan.getExpectedPriorArtifacts(),
                                operation.certificationReceipts,
                                operation.runtimeAuthorization
                        )
                );
            } catch (IOException exception) {
                throw new RuntimeException(exception);
            }
        }).whenComplete(Schedulers.javafx(), (@Nullable var result, @Nullable var exception) -> {
            if (exception != null) {
                cleanupStaging(operation.stagingDirectory);
                progressDialog.close();
                showError(i18n("plugin.store.install_failed"), failureMessage(exception));
                return;
            }
            cleanupStaging(operation.stagingDirectory);
            progressDialog.close();
            refreshInstalledManifests();
            applyFilter();
            @Nullable PluginDetailsPage detailsPage = activeDetailsPage;
            if (detailsPage != null) {
                detailsPage.refreshLocalInstallationState();
            }
            PluginDialogs.showInstallFinished(
                    operation.plan.getRootEntry().getDisplayName(),
                    true
            );
        }).start();
    }

    /// Removes an isolated staging directory and logs cleanup failures without masking the primary result.
    ///
    /// @param stagingDirectory staging directory
    private static void cleanupStaging(Path stagingDirectory) {
        try {
            FileUtils.deleteDirectory(stagingDirectory);
        } catch (IOException exception) {
            LOG.warning("Failed to clean plugin installation staging directory: " + stagingDirectory, exception);
        }
    }

    /// Formats a byte count using compact binary units.
    ///
    /// @param size byte count
    /// @return formatted size
    private static String formatSize(long size) {
        if (size < 1024) {
            return size + " B";
        }
        if (size < 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f KB", size / 1024.0);
        }
        return String.format(Locale.ROOT, "%.1f MB", size / 1024.0 / 1024.0);
    }

    /// Selects the exact current-platform package size shown for one Store version.
    ///
    /// @param version selected Store version
    /// @return exact platform artifact bytes, or `null` when the current platform is unavailable
    static @Nullable Long selectedVersionSize(PluginStoreManifest.PluginVersionEntry version) {
        try {
            return version.requireArtifact(PluginPlatformTarget.current()).size();
        } catch (IOException exception) {
            return null;
        }
    }

    /// Shows an application-owned HMCL error dialog on the JavaFX thread.
    ///
    /// @param title localized title
    /// @param message failure detail
    private static void showError(String title, String message) {
        PluginDialogs.showError(title, message);
    }

    /// Unwraps nested asynchronous exceptions and always returns usable diagnostic text.
    ///
    /// @param exception failure to unwrap
    /// @return root-cause message
    private static String failureMessage(Throwable exception) {
        Throwable cause = exception;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        @Nullable String message = cause.getMessage();
        return StringUtils.isBlank(message) ? cause.toString() : Objects.requireNonNull(message);
    }

    /// Returns the decorator navigation state.
    ///
    /// @return read-only page state
    @Override
    public ReadOnlyObjectProperty<State> stateProperty() {
        return state.getReadOnlyProperty();
    }

    /// Starts the first registry request when this lazily-created settings tab becomes visible.
    @Override
    public void onPageShown() {
        if (!initialLoadRequested) {
            initialLoadRequested = true;
            loadPluginStore();
            return;
        }
        refreshInstalledManifests();
        applyFilter();
    }

    /// Reloads the selected store source after an explicit refresh request.
    @Override
    public void refresh() {
        loadPluginStore();
    }

    /// HMCL navigation page displaying every detail of one remote plugin and its selected version.
    @NotNullByDefault
    private final class PluginDetailsPage extends VBox implements DecoratorPage {
        /// Decorator navigation state for the selected plugin.
        private final ReadOnlyObjectWrapper<State> detailsState;

        /// Store item whose metadata and versions are displayed.
        private final PluginStoreItem item;

        /// Repository manifest containing versions and README metadata.
        private final PluginStoreManifest manifest;

        /// Source-scoped manager retained with the selected store item.
        private final PluginStoreManager sourceManager;

        /// Native HMCL selector for the exact version displayed and installed.
        private final LineSelectButton<VersionItem> versionSelector = new LineSelectButton<>();

        /// Compatibility detail row updated with the selected version.
        private final LineButton compatibilityRow = createReadOnlyRow(
                SVG.CHECK_CIRCLE,
                i18n("plugin.store.compatibility"),
                null
        );

        /// Certification row updated from the exact selected version.
        private final LineButton certificationRow = createReadOnlyRow(
                SVG.INFO,
                i18n("plugin.store.trust"),
                null
        );

        /// Complete HMCL CE version constraint declared by the selected package version.
        private final LineButton launcherVersionRow = createReadOnlyRow(
                SVG.SCHEMA,
                i18n("plugin.store.launcher_version"),
                null
        );

        /// Release channel detail row updated with the selected version.
        private final LineButton channelRow = createReadOnlyRow(
                SVG.RELEASE_CIRCLE,
                i18n("plugin.store.channel"),
                null
        );

        /// Release date detail row updated with the selected version.
        private final LineButton releaseDateRow = createReadOnlyRow(
                SVG.UPDATE,
                i18n("plugin.store.release_date"),
                null
        );

        /// Package size detail row updated with the selected version.
        private final LineButton sizeRow = createReadOnlyRow(
                SVG.ARCHIVE,
                i18n("plugin.store.size"),
                null
        );

        /// Restart requirement detail row updated with the selected version.
        private final LineButton restartRow = createReadOnlyRow(
                SVG.REFRESH,
                i18n("plugin.store.requires_restart"),
                null
        );

        /// Section that is replaced with the selected version's declaration-only permission pane.
        private final VBox permissionSection = new VBox();

        /// Native dependency rows for the selected version.
        private final ComponentList dependencyList = new ComponentList();

        /// Native changelog row for the selected version.
        private final LineButton changelogRow = createReadOnlyRow(
                SVG.CHAT,
                i18n("plugin.store.changelog"),
                null
        );

        /// Installs the exact selected compatible version.
        private final JFXButton installButton;

        /// Identity and installed-version rows rebuilt after local plugin state changes.
        private final ComponentList identityList = new ComponentList();

        /// Creates a scrollable HMCL settings-style details page.
        ///
        /// @param item selected store item
        /// @param manifest resolved repository manifest
        private PluginDetailsPage(PluginStoreItem item, PluginStoreManifest manifest) {
            this.item = item;
            this.manifest = manifest;
            this.sourceManager = item.getSourceManager();
            this.detailsState = new ReadOnlyObjectWrapper<>(State.fromTitle(displayName(item)));
            getStyleClass().add("gray-background");

            VBox content = new VBox(10);
            content.setPadding(new Insets(10));
            content.setFillWidth(true);

            HBox actions = new HBox(8);
            actions.setAlignment(Pos.CENTER_RIGHT);
            actions.setMinHeight(56);
            actions.setPadding(new Insets(8, 10, 10, 10));
            actions.getChildren().add(createFavoriteButton(item.getEntry().getId()));
            if (isSafeExternalLink(item.getEntry().getRepository())) {
                actions.getChildren().add(createIconButton(
                        i18n("plugin.store.open_repository"),
                        SVG.OPEN_IN_NEW,
                        () -> FXUtils.openLink(item.getEntry().getRepository())
                ));
            }
            installButton = createToolbarButton2(
                    i18n("plugin.store.install.selected"),
                    SVG.DOWNLOAD,
                    this::installSelectedVersion
            );
            installButton.getStyleClass().add("jfx-button-raised");
            installButton.setMinHeight(40);
            actions.getChildren().add(installButton);

            content.getChildren().add(ComponentList.createComponentListTitle(i18n("plugin.store.details")));
            populateIdentityList();
            content.getChildren().add(identityList);

            versionSelector.setLeading(SVG.UPDATE);
            versionSelector.setTitle(i18n("plugin.store.version"));
            versionSelector.setNullSafeConverter(VersionItem::toString);
            versionSelector.setDescriptionConverter((@Nullable var candidate) -> candidate == null
                    ? ""
                    : compatibilityText(sourceManager, candidate.version));
            @Unmodifiable List<VersionItem> versions = manifest.getVersionsNewestFirst().stream()
                    .map(version -> new VersionItem(version, sourceManager.isCompatible(version)))
                    .toList();
            versionSelector.setItems(versions);
            versionSelector.valueProperty().addListener((var observable, @Nullable var oldValue,
                    @Nullable var selected) -> {
                if (selected != null) {
                    updateSelectedVersion(selected);
                }
            });

            ComponentList versionList = new ComponentList();
            versionList.getContent().add(versionSelector);
            content.getChildren().addAll(
                    ComponentList.createComponentListTitle(i18n("plugin.store.version")),
                    versionList
            );

            ComponentList metadataList = new ComponentList();
            metadataList.getContent().addAll(
                    certificationRow,
                    compatibilityRow,
                    launcherVersionRow,
                    channelRow,
                    releaseDateRow,
                    sizeRow,
                    restartRow
            );
            content.getChildren().addAll(
                    ComponentList.createComponentListTitle(i18n("plugin.store.compatibility")),
                    metadataList
            );

            content.getChildren().add(permissionSection);
            dependencyList.getStyleClass().add("no-padding");
            content.getChildren().addAll(
                    ComponentList.createComponentListTitle(i18n("plugin.store.dependencies")),
                    dependencyList,
                    ComponentList.createComponentListTitle(i18n("plugin.store.changelog"))
            );
            ComponentList changelogList = new ComponentList();
            changelogList.getContent().add(changelogRow);
            content.getChildren().add(changelogList);

            VBox readmeContainer = new VBox(8);
            Label readmeStatus = new Label();
            readmeStatus.setWrapText(true);
            JFXButton retryButton = new JFXButton(i18n("plugin.store.readme.retry"));
            retryButton.setOnAction(event ->
                    loadReadme(item, manifest, readmeContainer, readmeStatus, retryButton));
            content.getChildren().addAll(
                    ComponentList.createComponentListTitle(i18n("plugin.store.readme")),
                    readmeContainer
            );

            ScrollPane scrollPane = new ScrollPane(content);
            scrollPane.setFitToWidth(true);
            scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
            VBox.setVgrow(scrollPane, Priority.ALWAYS);
            FXUtils.smoothScrolling(scrollPane);
            FXUtils.setOverflowHidden(scrollPane, 8);
            getChildren().setAll(scrollPane, actions);

            @Nullable PluginStoreManifest.PluginVersionEntry latestCompatible =
                    sourceManager.getLatestCompatibleVersion(manifest);
            @Nullable VersionItem initial = versions.stream()
                    .filter(candidate -> latestCompatible != null
                            && candidate.version.getVersion().equals(latestCompatible.getVersion()))
                    .findFirst()
                    .orElse(versions.isEmpty() ? null : versions.get(0));
            if (initial == null) {
                installButton.setDisable(true);
            } else {
                versionSelector.setValue(initial);
            }
            loadReadme(item, manifest, readmeContainer, readmeStatus, retryButton);
        }

        /// Builds the plugin identity, author, category, tags, installed version, and license rows.
        ///
        private void populateIdentityList() {
            PluginStoreRegistry.PluginStoreEntry entry = item.getEntry();
            identityList.getContent().clear();

            LineButton identity = createReadOnlyRow(
                    SVG.EXTENSION,
                    displayName(item),
                    StringUtils.isBlank(entry.getDescription()) ? null : entry.getDescription()
            );
            identity.setLargeTitle(true);
            identityList.getContent().add(identity);
            identityList.getContent().add(createReadOnlyRow(
                    SVG.INFO,
                    i18n("plugin.store.id"),
                    entry.getId()
            ));

            identityList.getContent().add(createReadOnlyRow(
                    SVG.EXTENSION,
                    i18n("plugin.store.source"),
                    sourceDisplayName(item)
            ));
            @Nullable PluginStoreSnapshot snapshot = currentSnapshot;
            if (snapshot != null) {
                @Unmodifiable List<PluginStoreItem> conflicts = snapshot.getConflictCandidates()
                        .getOrDefault(entry.getId(), List.of());
                if (!conflicts.isEmpty()) {
                    String candidates = conflicts.stream()
                            .map(candidate -> sourceDisplayName(candidate) + " " + latestVersionText(candidate))
                            .collect(Collectors.joining("\n"));
                    identityList.getContent().add(createReadOnlyRow(
                            SVG.WARNING,
                            i18n("plugin.store.source.other_candidates"),
                            candidates
                    ));
                }
            }
            if (StringUtils.isNotBlank(entry.getAuthor())) {
                identityList.getContent().add(createReadOnlyRow(
                        SVG.PERSON,
                        i18n("plugin.author"),
                        entry.getAuthor()
                ));
            }
            if (StringUtils.isNotBlank(entry.getCategory())) {
                identityList.getContent().add(createReadOnlyRow(
                        SVG.LIST,
                        i18n("plugin.store.category"),
                        entry.getCategory()
                ));
            }
            @Unmodifiable List<String> tags = entry.getTags().stream()
                    .filter(StringUtils::isNotBlank)
                    .toList();
            if (!tags.isEmpty()) {
                identityList.getContent().add(createReadOnlyRow(
                        SVG.LISTS,
                        i18n("plugin.store.tags"),
                        String.join(", ", tags)
                ));
            }
            @Nullable PluginManifest installed = installedManifests.get(entry.getId());
            if (installed != null) {
                identityList.getContent().add(createReadOnlyRow(
                        SVG.CHECK_CIRCLE,
                        i18n("plugin.store.installed_version"),
                        installed.getVersion()
                ));
            }
            if (StringUtils.isNotBlank(manifest.getLicense())) {
                identityList.getContent().add(createReadOnlyRow(
                        SVG.SCRIPT,
                        i18n("plugin.store.license"),
                        manifest.getLicense()
                ));
            }
        }

        /// Refreshes installed metadata and the selected version's action after a local state publication.
        private void refreshLocalInstallationState() {
            populateIdentityList();
            @Nullable VersionItem selected = versionSelector.getValue();
            if (selected != null) {
                updateSelectedVersion(selected);
            }
        }

        /// Refreshes all version-specific metadata and declaration-only lists.
        ///
        /// @param selected selected version wrapper
        private void updateSelectedVersion(VersionItem selected) {
            PluginStoreManifest.PluginVersionEntry version = selected.version;
            PluginTrustResult selectedTrust = sourceManager.refreshVersionTrust(item, version);
            certificationRow.setLeading(switch (selectedTrust.level()) {
                case OFFICIAL, CERTIFIED -> SVG.CHECK_CIRCLE;
                case COMMUNITY -> SVG.INFO;
                case REJECTED -> SVG.WARNING;
            });
            certificationRow.pseudoClassStateChanged(
                    PSEUDO_ERROR,
                    selectedTrust.level() == PluginTrustLevel.REJECTED
            );
            certificationRow.setSubtitle(i18n(trustLabelKey(selectedTrust.level())));
            compatibilityRow.setLeading(compatibilityIcon(selected.compatible));
            compatibilityRow.pseudoClassStateChanged(PSEUDO_ERROR, !selected.compatible);
            compatibilityRow.setSubtitle(compatibilityText(sourceManager, version));
            String launcherVersion = version.getLauncherVersion();
            launcherVersionRow.setSubtitle(launcherVersionRequirementText(launcherVersion));
            channelRow.setSubtitle(version.getChannel());
            releaseDateRow.setSubtitle(version.getReleaseDate().isBlank() ? "-" : version.getReleaseDate());
            @Nullable Long size = selectedVersionSize(version);
            sizeRow.setSubtitle(size == null ? "-" : formatSize(size));
            restartRow.setSubtitle(i18n(version.isRequiresRestart()
                    ? "plugin.store.requires_restart.yes"
                    : "plugin.store.requires_restart.no"));
            changelogRow.setSubtitle(version.getReleaseNotes().isBlank()
                    ? i18n("plugin.store.changelog.empty")
                    : version.getReleaseNotes());

            permissionSection.getChildren().setAll(
                    ComponentList.createComponentListTitle(i18n("plugin.store.permissions")),
                    new PluginPermissionPane(
                            version.getRequiredPermissions(),
                            version.getOptionalPermissions(),
                            Set.of(),
                            false,
                            Set.of(),
                            Set.of()
                    )
            );
            populateDependencyRows(version.getDependencies());
            @Nullable String installedFailure = installedManifestFailure;
            if (installedFailure != null) {
                installButton.setTooltip(new Tooltip(
                        i18n("plugin.installed.load_failed") + ": " + installedFailure
                ));
                installButton.setDisable(true);
                return;
            }
            @Nullable PluginManifest planningManifest =
                    installPlanningManifests.get(item.getEntry().getId());
            PluginInstallPlan.Action action = rootInstallationAction(planningManifest);
            installButton.setText(i18n(action == PluginInstallPlan.Action.UPDATE
                    ? "plugin.store.update"
                    : "plugin.store.install.selected"));
            installButton.setTooltip(selected.compatible
                    ? null
                    : new Tooltip(i18n("plugin.store.install.incompatible", compatibilityText(sourceManager, version))));
            if (!selectedTrust.canInstall()) {
                installButton.setTooltip(new Tooltip(i18n(
                        "plugin.store.trust.rejected.detail",
                        selectedTrust.detail()
                )));
            }
            installButton.setDisable(!canInstallSelectedVersion(selected.compatible, selectedTrust));
        }

        /// Rebuilds dependency rows for the currently selected version.
        ///
        /// @param dependencies developer-declared plugin dependencies
        private void populateDependencyRows(List<PluginDependency> dependencies) {
            dependencyList.getContent().clear();
            if (dependencies.isEmpty()) {
                dependencyList.getContent().add(createReadOnlyRow(
                        SVG.INFO,
                        i18n("plugin.store.dependencies.none"),
                        null
                ));
                return;
            }
            for (PluginDependency dependency : dependencies) {
                dependencyList.getContent().add(createReadOnlyRow(
                        SVG.SCHEMA,
                        dependency.getId(),
                        dependency.getVersion()
                ));
            }
        }

        /// Starts dependency resolution for the exact version selected on this page.
        private void installSelectedVersion() {
            @Nullable VersionItem selected = versionSelector.getValue();
            if (selected != null && selected.compatible) {
                resolveAndConfirmInstall(item, selected.version);
            }
        }

        /// Returns the decorator navigation state for this detail page.
        ///
        /// @return read-only page state
        @Override
        public ReadOnlyObjectProperty<State> stateProperty() {
            return detailsState.getReadOnlyProperty();
        }
    }

    /// Creates a non-interactive native row for metadata, changelog, and empty states.
    ///
    /// @param icon leading icon
    /// @param title row title
    /// @param subtitle optional wrapped detail
    /// @return configured read-only row
    private static LineButton createReadOnlyRow(SVG icon, String title, @Nullable String subtitle) {
        LineButton row = new LineButton();
        row.setLeading(icon);
        row.setTitle(title);
        row.setSubtitle(subtitle);
        row.setMouseTransparent(true);
        return row;
    }

    /// Enumerates aggregate catalog states that require distinct row and warning presentation.
    @NotNullByDefault
    enum StoreState {
        /// No configured source currently participates in catalog loading.
        NO_ENABLED_SOURCES,

        /// Every enabled source failed to provide catalog winners.
        ALL_FAILED,

        /// Successful winners remain visible while one or more enabled sources failed.
        DEGRADED,

        /// Winners are visible and one or more lower-priority candidates conflict.
        CONFLICTS,

        /// Winners are visible without source failures or conflicts.
        READY
    }

    /// Couples an immutable aggregate snapshot to the dependency plan it selected.
    @NotNullByDefault
    private static final class ResolvedInstallPlan {
        /// Dependency-first resolved install plan.
        private final PluginInstallPlan plan;

        /// Aggregate snapshot whose winners selected every remote plan entry.
        private final PluginStoreSnapshot snapshot;

        /// Creates one immutable plan and snapshot pair.
        ///
        /// @param plan dependency-first plan
        /// @param snapshot winner snapshot used for resolution
        private ResolvedInstallPlan(PluginInstallPlan plan, PluginStoreSnapshot snapshot) {
            this.plan = plan;
            this.snapshot = snapshot;
        }
    }

    /// One credential-safe failed-source diagnostic in configured priority order.
    @NotNullByDefault
    record SourceFailureDiagnostic(String sourceName, String message) {
    }

    /// Immutable aggregate page state derived from the accepted source snapshot.
    @NotNullByDefault
    record StorePresentation(
            StoreState state,
            int enabledSourceCount,
            int failedSourceCount,
            boolean hasConflicts,
            boolean refreshInProgress
    ) {
        /// Returns whether aggregate winner rows remain appropriate for this page state.
        ///
        /// @return whether plugin rows should remain visible
        boolean showPluginRows() {
            return state != StoreState.NO_ENABLED_SOURCES && state != StoreState.ALL_FAILED;
        }
    }

    /// One category selector value with a stable filter key and localized display label.
    @NotNullByDefault
    private static final class CategoryItem {
        /// Stable category value used by filtering.
        private final String value;

        /// Localized or registry-provided display label.
        private final String label;

        /// Creates one category option.
        ///
        /// @param value stable filter value
        /// @param label display label
        private CategoryItem(String value, String label) {
            this.value = value;
            this.label = label;
        }

        /// Returns the selector display label.
        ///
        /// @return category label
        @Override
        public String toString() {
            return label;
        }
    }

    /// One version selector row carrying exact metadata and compatibility state.
    @NotNullByDefault
    private static final class VersionItem {
        /// Exact published version selected for details or installation.
        private final PluginStoreManifest.PluginVersionEntry version;

        /// Whether the current launcher and Java runtime can install this version.
        private final boolean compatible;

        /// Creates a version selector row.
        ///
        /// @param version exact version metadata
        /// @param compatible current runtime compatibility
        private VersionItem(PluginStoreManifest.PluginVersionEntry version, boolean compatible) {
            this.version = version;
            this.compatible = compatible;
        }

        /// Returns version, channel, and incompatibility marker for the selector.
        ///
        /// @return selector label
        @Override
        public String toString() {
            String label = "v" + version.getVersion() + " - " + version.getChannel();
            return compatible ? label : label + " - " + i18n("plugin.store.version.incompatible");
        }
    }

    /// Mutable orchestration state retained across asynchronous per-entry publication.
    @NotNullByDefault
    private static final class InstallOperation {
        /// Confirmed dependency-first plan.
        private final PluginInstallPlan plan;

        /// Aggregate snapshot that selected every remote plan entry.
        private final PluginStoreSnapshot snapshot;

        /// Isolated directory containing every verified download.
        private final Path stagingDirectory;

        /// Staged package path indexed by plugin ID.
        private final @Unmodifiable Map<String, Path> stagedPackages;

        /// Confirmed declaration-limited grants indexed by changed plugin ID.
        private final @Unmodifiable Map<String, @Unmodifiable Set<PluginPermission>> grantsByPluginId;

        /// Proof-backed receipts for certified downloads indexed by exact replacement plugin ID.
        private final @Unmodifiable Map<String, PluginCertificationReceipt> certificationReceipts;

        /// Confirmed virtual bindings, Host enablements, and custom-source acknowledgements.
        private final PluginRuntimeInstallAuthorization runtimeAuthorization;

        /// Creates an operation after every required package has downloaded successfully.
        ///
        /// @param plan confirmed plan
        /// @param snapshot aggregate snapshot that selected the plan
        /// @param stagingDirectory isolated staging directory
        /// @param stagedPackages staged packages by plugin ID
        /// @param grantsByPluginId confirmed permission grants by changed plugin ID
        /// @param certificationReceipts proof-backed receipts for certified downloads
        /// @param runtimeAuthorization confirmed runtime Provider authorization
        private InstallOperation(
                PluginInstallPlan plan,
                PluginStoreSnapshot snapshot,
                Path stagingDirectory,
                Map<String, Path> stagedPackages,
                Map<String, @Unmodifiable Set<PluginPermission>> grantsByPluginId,
                Map<String, PluginCertificationReceipt> certificationReceipts,
                PluginRuntimeInstallAuthorization runtimeAuthorization
        ) {
            this.plan = plan;
            this.snapshot = snapshot;
            this.stagingDirectory = stagingDirectory;
            this.stagedPackages = Map.copyOf(stagedPackages);
            this.grantsByPluginId = Map.copyOf(grantsByPluginId);
            this.certificationReceipts = Map.copyOf(certificationReceipts);
            this.runtimeAuthorization = runtimeAuthorization;
        }
    }
}
