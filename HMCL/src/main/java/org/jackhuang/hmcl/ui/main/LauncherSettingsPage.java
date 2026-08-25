/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2021  huangyuhui <huanghongxun2008@126.com> and contributors
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
import org.jackhuang.hmcl.game.HMCLGameRepository;
import org.jackhuang.hmcl.setting.GameSettings;
import org.jackhuang.hmcl.setting.GameDirectoryManager;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.animation.TransitionPane;
import org.jackhuang.hmcl.ui.construct.*;
import org.jackhuang.hmcl.ui.decorator.DecoratorAnimatedPage;
import org.jackhuang.hmcl.ui.decorator.DecoratorPage;
import org.jackhuang.hmcl.ui.game.GameSettingsPage;
import org.jetbrains.annotations.NotNullByDefault;
import java.util.Locale;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;

/// Hosts launcher settings inside one native tab and transition surface.
@NotNullByDefault
public class LauncherSettingsPage extends DecoratorAnimatedPage implements DecoratorPage, PageAware {
    /// Decorator title state for the settings surface.
    private final ReadOnlyObjectWrapper<State> state = new ReadOnlyObjectWrapper<>(State.fromTitle(i18n("settings")));
    /// Native tab controller shared by the built-in settings pages.
    private final TabHeader tab;
    /// Built-in global game settings tab.
    private final TabHeader.Tab<GameSettingsPage<GameSettings.Preset>> gameTab = new TabHeader.Tab<>("versionSettingsPage");
    /// Built-in Java management tab.
    private final TabControl.Tab<JavaManagementPage> javaManagementTab = new TabControl.Tab<>("javaManagementPage");
    /// Built-in launcher settings tab.
    private final TabHeader.Tab<SettingsPage> settingsTab = new TabHeader.Tab<>("settingsPage");
    /// Built-in launcher personalization tab.
    private final TabHeader.Tab<PersonalizationPage> personalizationTab = new TabHeader.Tab<>("personalizationPage");
    /// Built-in download settings tab.
    private final TabHeader.Tab<DownloadSettingsPage> downloadTab = new TabHeader.Tab<>("downloadSettingsPage");
    /// Built-in plugin management tab.
    private final TabHeader.Tab<PluginManagementPage> pluginTab = new TabHeader.Tab<>("pluginManagementPage");
    /// Conditional plugin startup recovery tab.
    private final TabHeader.Tab<PluginRecoveryPage> pluginRecoveryTab = new TabHeader.Tab<>("pluginRecoveryPage");
    /// Built-in plugin store tab.
    private final TabHeader.Tab<PluginStorePage> pluginStoreTab = new TabHeader.Tab<>("pluginStorePage");
    /// Built-in help tab.
    private final TabHeader.Tab<HelpPage> helpTab = new TabHeader.Tab<>("helpPage");
    /// Built-in about tab.
    private final TabHeader.Tab<AboutPage> aboutTab = new TabHeader.Tab<>("aboutPage");
    /// Built-in feedback tab.
    private final TabHeader.Tab<FeedbackPage> feedbackTab = new TabHeader.Tab<>("feedbackPage");
    /// Animated content host selected by [#tab].
    private final TransitionPane transitionPane = new TransitionPane();
    /// Native launcher settings navigation list.
    private final AdvancedListBox sideBar;
    /// Stable heading for the built-in help navigation section.
    private final ClassTitle helpCategory = new ClassTitle(i18n("help").toUpperCase(Locale.ROOT));
    /// Sidebar item whose visibility follows actionable plugin recovery state.
    private final AdvancedListItem pluginRecoveryNavigation = new AdvancedListItem();

    /// Creates the built-in settings tabs.
    public LauncherSettingsPage() {
        this(PluginRecoveryPage.isRecoveryAvailable());
    }

    /// Creates built-in settings tabs with an explicit startup recovery navigation state.
    ///
    /// @param recoveryAvailable whether persisted quarantine recovery is actionable
    LauncherSettingsPage(boolean recoveryAvailable) {
        gameTab.setNodeSupplier(() -> new GameSettingsPage<>(GameSettings.Preset.class));
        javaManagementTab.setNodeSupplier(JavaManagementPage::new);
        settingsTab.setNodeSupplier(SettingsPage::new);
        personalizationTab.setNodeSupplier(PersonalizationPage::new);
        downloadTab.setNodeSupplier(DownloadSettingsPage::new);
        pluginTab.setNodeSupplier(PluginManagementPage::new);
        pluginRecoveryTab.setNodeSupplier(() -> new PluginRecoveryPage(this::refreshRecoveryNavigation));
        pluginStoreTab.setNodeSupplier(PluginStorePage::new);
        helpTab.setNodeSupplier(HelpPage::new);
        feedbackTab.setNodeSupplier(FeedbackPage::new);
        aboutTab.setNodeSupplier(AboutPage::new);
        tab = new TabHeader(transitionPane, gameTab, javaManagementTab, settingsTab, personalizationTab, downloadTab, pluginTab, pluginRecoveryTab, pluginStoreTab, helpTab, feedbackTab, aboutTab);

        tab.select(gameTab);
        addEventHandler(Navigator.NavigationEvent.NAVIGATED, event -> gameTab.getNode().loadInstance(GameDirectoryManager.getSelectedRepository(), null));

        sideBar = new AdvancedListBox()
                .addNavigationDrawerTab(tab, gameTab, i18n("settings.type.global.manage"), SVG.STADIA_CONTROLLER, SVG.STADIA_CONTROLLER_FILL)
                .addNavigationDrawerTab(tab, javaManagementTab, i18n("java.management"), SVG.LOCAL_CAFE, SVG.LOCAL_CAFE_FILL)
                .startCategory(i18n("launcher").toUpperCase(Locale.ROOT))
                .addNavigationDrawerTab(tab, settingsTab, i18n("settings.launcher.general"), SVG.TUNE)
                .addNavigationDrawerTab(tab, personalizationTab, i18n("settings.launcher.appearance"), SVG.STYLE, SVG.STYLE_FILL)
                .addNavigationDrawerTab(tab, downloadTab, i18n("download"), SVG.DOWNLOAD)
                .addNavigationDrawerTab(tab, pluginTab, i18n("plugin.manage"), SVG.EXTENSION, SVG.EXTENSION)
                ;
        pluginRecoveryNavigation.getStyleClass().add("navigation-drawer-item");
        pluginRecoveryNavigation.setTitle(i18n("plugin.recovery.title"));
        pluginRecoveryNavigation.setLeftIcon(SVG.RESTORE);
        pluginRecoveryNavigation.activeProperty().bind(
                tab.getSelectionModel().selectedItemProperty().isEqualTo(pluginRecoveryTab)
        );
        pluginRecoveryNavigation.setOnAction(event -> tab.select(pluginRecoveryTab));
        sideBar.add(pluginRecoveryNavigation);
        sideBar.addNavigationDrawerTab(tab, pluginStoreTab, i18n("plugin.store"), SVG.LISTS, SVG.LISTS);
        sideBar.add(helpCategory)
                .addNavigationDrawerTab(tab, helpTab, i18n("help"), SVG.HELP, SVG.HELP_FILL)
                .addNavigationDrawerTab(tab, feedbackTab, i18n("contact"), SVG.FEEDBACK, SVG.FEEDBACK_FILL)
                .addNavigationDrawerTab(tab, aboutTab, i18n("about"), SVG.INFO, SVG.INFO_FILL);
        FXUtils.setLimitWidth(sideBar, 200);
        setLeft(sideBar);
        updateRecoveryNavigation(recoveryAvailable);

        setCenter(transitionPane);
    }

    /// Propagates visibility to the selected native settings tab.
    @Override
    public void onPageShown() {
        refreshRecoveryNavigation();
        tab.onPageShown();
    }

    /// Propagates hiding to the selected native settings tab.
    @Override
    public void onPageHidden() {
        tab.onPageHidden();
    }

    /// Selects global game settings for the requested repository.
    ///
    /// @param repository game repository whose global preset settings should be displayed
    public void showGameSettings(HMCLGameRepository repository) {
        gameTab.getNode().loadInstance(repository, null);
        tab.select(gameTab, false);
    }

    /// Selects the built-in feedback tab without transition animation.
    public void showFeedback() {
        tab.select(feedbackTab, false);
    }

    /// Selects the built-in plugin management tab without transition animation.
    public void showPluginManagement() {
        tab.select(pluginTab, false);
    }

    /// Refreshes conditional recovery navigation from current process-wide plugin state.
    private void refreshRecoveryNavigation() {
        updateRecoveryNavigation(PluginRecoveryPage.isRecoveryAvailable());
    }

    /// Applies one recovery availability snapshot to the sidebar and current selection.
    ///
    /// @param recoveryAvailable whether recovery remains actionable
    void updateRecoveryNavigation(boolean recoveryAvailable) {
        pluginRecoveryNavigation.setManaged(recoveryAvailable);
        pluginRecoveryNavigation.setVisible(recoveryAvailable);
        if (!recoveryAvailable && tab.getSelectionModel().getSelectedItem() == pluginRecoveryTab) {
            tab.select(pluginTab, false);
        }
    }

    /// Selects the cached plugin-store tab without recreating its aggregate loader.
    public void showPluginStore() {
        tab.select(pluginStoreTab, false);
    }

    /// Closes the cached plugin-store aggregate loader at the owning application's shutdown boundary.
    ///
    /// The tab keeps the page instance while hidden, so this must not run during tab navigation.
    public void closePluginStoreAtApplicationShutdown() {
        if (pluginStoreTab.isInitialized()) {
            pluginStoreTab.getNode().closeAtApplicationShutdown(true);
        }
    }

    /// Returns the decorator state describing this settings container.
    ///
    /// @return read-only decorator state
    @Override
    public ReadOnlyObjectProperty<State> stateProperty() {
        return state.getReadOnlyProperty();
    }
}
