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

import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import org.jackhuang.hmcl.plugin.PluginPermission;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.construct.ComponentList;
import org.jackhuang.hmcl.ui.construct.HintPane;
import org.jackhuang.hmcl.ui.construct.LineButton;
import org.jackhuang.hmcl.ui.construct.LineToggleButton;
import org.jackhuang.hmcl.ui.construct.MessageDialogPane;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;

/// Renders required and optional permissions requested by one plugin as native HMCL list groups.
///
/// Undeclared permissions never receive a row, so callers cannot use this control to grant capabilities
/// that are absent from the plugin manifest. Required permissions remain selected and locked, while only optional
/// permissions expose user-controlled switches.
@NotNullByDefault
final class PluginPermissionPane extends VBox {
    /// Permissions required for this plugin artifact to run.
    private final @Unmodifiable List<PluginPermission> requiredPermissions;

    /// Permissions whose related features may be disabled without blocking ordinary lifecycle execution.
    private final @Unmodifiable List<PluginPermission> optionalPermissions;

    /// Whether this pane represents an interactive permission decision.
    private final boolean editable;

    /// Editable switches indexed only by optional permissions.
    private final Map<PluginPermission, LineToggleButton> permissionToggles =
            new EnumMap<>(PluginPermission.class);

    /// Inline warning shown whenever at least one optional capability is denied.
    private final HintPane optionalDeniedWarning = new HintPane(MessageDialogPane.MessageType.WARNING);

    /// Creates grouped permission lists in declaration-only or editable grant mode.
    ///
    /// @param requiredPermissions permissions required by the developer
    /// @param optionalPermissions permissions the user may deny while keeping the plugin runnable
    /// @param grantedPermissions initially granted requested permissions
    /// @param editable whether rows are grant switches rather than declaration summaries
    /// @param newlyRequiredPermissions required permissions introduced or promoted by the target update
    /// @param newlyOptionalPermissions optional capabilities introduced by the target update
    PluginPermissionPane(
            List<PluginPermission> requiredPermissions,
            List<PluginPermission> optionalPermissions,
            Set<PluginPermission> grantedPermissions,
            boolean editable,
            Set<PluginPermission> newlyRequiredPermissions,
            Set<PluginPermission> newlyOptionalPermissions
    ) {
        this.requiredPermissions = List.copyOf(requiredPermissions);
        this.optionalPermissions = List.copyOf(optionalPermissions);
        this.editable = editable;
        validateDisjointPermissions(this.requiredPermissions, this.optionalPermissions);
        setSpacing(10);
        setFillWidth(true);

        if (this.requiredPermissions.isEmpty() && this.optionalPermissions.isEmpty()) {
            ComponentList emptyList = createPermissionList();
            LineButton empty = new LineButton();
            empty.setLeading(SVG.INFO);
            empty.setTitle(i18n("plugin.permissions.none.title"));
            empty.setSubtitle(i18n("plugin.permissions.none.description"));
            empty.setMouseTransparent(true);
            emptyList.getContent().add(empty);
            getChildren().add(emptyList);
            return;
        }

        if (!this.requiredPermissions.isEmpty()) {
            ComponentList requiredList = createPermissionList();
            for (PluginPermission permission : this.requiredPermissions) {
                requiredList.getContent().add(createPermissionRow(
                        permission,
                        true,
                        editable,
                        true,
                        newlyRequiredPermissions.contains(permission)
                ));
            }
            getChildren().addAll(
                    ComponentList.createComponentListTitle(i18n("plugin.permissions.required")),
                    requiredList
            );
        }

        if (!this.optionalPermissions.isEmpty()) {
            ComponentList optionalList = createPermissionList();
            for (PluginPermission permission : this.optionalPermissions) {
                optionalList.getContent().add(createPermissionRow(
                        permission,
                        false,
                        editable,
                        grantedPermissions.contains(permission),
                        newlyOptionalPermissions.contains(permission)
                ));
            }
            getChildren().addAll(
                    ComponentList.createComponentListTitle(i18n("plugin.permissions.optional")),
                    optionalList
            );
        }

        optionalDeniedWarning.setText(i18n("plugin.permissions.optional_denied_warning"));
        optionalDeniedWarning.setManaged(false);
        optionalDeniedWarning.setVisible(false);
        if (editable && !this.optionalPermissions.isEmpty()) {
            getChildren().add(optionalDeniedWarning);
            refreshOptionalDeniedWarning();
        }
    }

    /// Creates one native permission row with classification and update markers.
    ///
    /// @param permission permission represented by the row
    /// @param required whether the permission cannot be denied
    /// @param editable whether this pane records a user decision
    /// @param selected initial optional selection or the locked required state
    /// @param newlyDeclared whether the selected update newly introduces this classification
    /// @return configured native HMCL row
    private org.jackhuang.hmcl.ui.construct.LineComponent createPermissionRow(
            PluginPermission permission,
            boolean required,
            boolean editable,
            boolean selected,
            boolean newlyDeclared
    ) {
        String classificationKey = required ? "plugin.permission.required" : "plugin.permission.optional";
        String newPermissionKey = required
                ? "plugin.install.permissions.new_required"
                : "plugin.install.permissions.new_optional";
        if (editable) {
            LineToggleButton row = new LineToggleButton();
            configureRow(row, permission);
            markClassification(row, classificationKey, newlyDeclared ? newPermissionKey : null);
            row.setSelected(required || selected);
            if (required) {
                row.setDisable(true);
            } else {
                permissionToggles.put(permission, row);
                row.selectedProperty().addListener((observable, oldValue, newValue) ->
                        refreshOptionalDeniedWarning());
            }
            return row;
        } else {
            LineButton row = new LineButton();
            configureRow(row, permission);
            markClassification(row, null, newlyDeclared ? newPermissionKey : null);
            row.setTrailingText(i18n(classificationKey));
            row.setMouseTransparent(true);
            return row;
        }
    }

    /// Creates a borderless native component list for one permission classification.
    ///
    /// @return configured component list
    private static ComponentList createPermissionList() {
        ComponentList list = new ComponentList();
        list.getStyleClass().add("no-padding");
        return list;
    }

    /// Rejects duplicate or overlapping permission classifications at the UI boundary.
    ///
    /// @param requiredPermissions required permission list
    /// @param optionalPermissions optional permission list
    private static void validateDisjointPermissions(
            List<PluginPermission> requiredPermissions,
            List<PluginPermission> optionalPermissions
    ) {
        Set<PluginPermission> declared = EnumSet.noneOf(PluginPermission.class);
        for (PluginPermission permission : requiredPermissions) {
            if (!declared.add(permission)) {
                throw new IllegalArgumentException("Duplicate required permission: " + permission.getId());
            }
        }
        for (PluginPermission permission : optionalPermissions) {
            if (!declared.add(permission)) {
                throw new IllegalArgumentException("Permission cannot be both required and optional: "
                        + permission.getId());
            }
        }
    }

    /// Updates the optional-denial warning without affecting the plugin runtime status message.
    private void refreshOptionalDeniedWarning() {
        boolean denied = hasDeniedOptionalPermissions();
        optionalDeniedWarning.setManaged(denied);
        optionalDeniedWarning.setVisible(denied);
    }

    /// Returns whether at least one optional capability is currently denied.
    ///
    /// @return whether optional functionality may be unavailable
    boolean hasDeniedOptionalPermissions() {
        return editable && (permissionToggles.size() < optionalPermissions.size()
                || permissionToggles.values().stream().anyMatch(toggle -> !toggle.isSelected()));
    }

    /// Applies the shared permission name, description, and icon to one HMCL row.
    ///
    /// @param row row to configure
    /// @param permission requested permission represented by the row
    private static void configureRow(org.jackhuang.hmcl.ui.construct.LineComponent row, PluginPermission permission) {
        row.setLeading(SVG.EXTENSION);
        row.setTitle(i18n("plugin.permission." + permission.getId()));
        row.setSubtitle(i18n("plugin.permission." + permission.getId() + ".description"));
    }

    /// Adds a compact classification and update marker beside the permission title.
    ///
    /// @param row permission row
    /// @param classificationKey optional required or optional classification key
    /// @param newPermissionKey optional update marker key
    private static void markClassification(
            org.jackhuang.hmcl.ui.construct.LineComponent row,
            @Nullable String classificationKey,
            @Nullable String newPermissionKey
    ) {
        if (classificationKey == null && newPermissionKey == null) {
            return;
        }
        StringBuilder text = new StringBuilder();
        if (classificationKey != null) {
            text.append(i18n(classificationKey));
        }
        if (newPermissionKey != null) {
            if (!text.isEmpty()) {
                text.append(" / ");
            }
            text.append(i18n(newPermissionKey));
        }
        Label marker = new Label(text.toString());
        marker.getStyleClass().add("subtitle-label");
        row.setTitleTrailing(marker);
    }

    /// Returns the immutable permissions currently allowed by the form.
    ///
    /// Required capabilities are always included; declaration-only panes return required permissions because they
    /// describe the minimum grant set rather than an editable user decision.
    ///
    /// @return required permissions plus selected optional permissions
    @Unmodifiable Set<PluginPermission> getGrantedPermissions() {
        EnumSet<PluginPermission> selectedOptional = EnumSet.noneOf(PluginPermission.class);
        permissionToggles.forEach((permission, toggle) -> {
            if (toggle.isSelected()) {
                selectedOptional.add(permission);
            }
        });
        return mergeGrantedPermissions(requiredPermissions, optionalPermissions, selectedOptional);
    }

    /// Merges locked required permissions with declaration-limited optional selections.
    ///
    /// @param requiredPermissions permissions that must always be granted
    /// @param optionalPermissions permissions the form is allowed to select
    /// @param selectedOptionalPermissions caller-selected optional permissions
    /// @return immutable required and selected optional grant set
    static @Unmodifiable Set<PluginPermission> mergeGrantedPermissions(
            List<PluginPermission> requiredPermissions,
            List<PluginPermission> optionalPermissions,
            Set<PluginPermission> selectedOptionalPermissions
    ) {
        validateDisjointPermissions(requiredPermissions, optionalPermissions);
        EnumSet<PluginPermission> granted = EnumSet.noneOf(PluginPermission.class);
        granted.addAll(requiredPermissions);
        for (PluginPermission permission : selectedOptionalPermissions) {
            if (optionalPermissions.contains(permission)) {
                granted.add(permission);
            }
        }
        return granted.isEmpty() ? Set.of() : Collections.unmodifiableSet(granted);
    }

    /// Returns all permissions represented by this pane in required-first order.
    ///
    /// @return developer-requested permissions
    @Unmodifiable List<PluginPermission> getDeclaredPermissions() {
        LinkedHashSet<PluginPermission> declared = new LinkedHashSet<>(requiredPermissions);
        declared.addAll(optionalPermissions);
        return List.copyOf(declared);
    }

    /// Returns permissions that are selected and locked by the developer declaration.
    ///
    /// @return immutable required permissions
    @Unmodifiable List<PluginPermission> getRequiredPermissions() {
        return requiredPermissions;
    }

    /// Returns permissions controlled by user switches.
    ///
    /// @return immutable optional permissions
    @Unmodifiable List<PluginPermission> getOptionalPermissions() {
        return optionalPermissions;
    }

    /// Registers a callback invoked whenever an optional permission switch changes.
    ///
    /// Declaration-only panes and required permissions never invoke the callback.
    ///
    /// @param listener permission selection change callback
    void setOnPermissionChange(Runnable listener) {
        permissionToggles.values().forEach(toggle ->
                toggle.selectedProperty().addListener((observable, oldValue, newValue) -> listener.run()));
    }

    /// Enables or disables every optional permission switch as one transaction boundary.
    ///
    /// Required permissions remain locked independently of this operation.
    ///
    /// @param disabled whether optional permission decisions must reject user input
    void setPermissionControlsDisabled(boolean disabled) {
        permissionToggles.values().forEach(toggle -> toggle.setDisable(disabled));
    }
}
