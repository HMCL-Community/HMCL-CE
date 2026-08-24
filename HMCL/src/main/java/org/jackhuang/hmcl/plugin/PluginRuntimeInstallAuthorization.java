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
package org.jackhuang.hmcl.plugin;

import org.jackhuang.hmcl.plugin.runtime.RuntimeProviderBinding;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.util.Map;
import java.util.Set;

/// Captures runtime Provider bindings, enablements, and source acknowledgements for one confirmed Store transaction.
@NotNullByDefault
public final class PluginRuntimeInstallAuthorization {
    /// Empty authorization used by local and schema-v4 installation paths.
    private static final PluginRuntimeInstallAuthorization EMPTY = new PluginRuntimeInstallAuthorization(
            Map.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of());

    /// Virtual runtime bindings selected by the confirmed dependency plan.
    private final @Unmodifiable Map<String, RuntimeProviderBinding> runtimeBindings;

    /// Exact installed Runtime Hosts that the transaction must enable.
    private final @Unmodifiable Set<String> enablementPluginIds;

    /// Custom-source Runtime Hosts that require an independent acknowledgement.
    private final @Unmodifiable Set<String> requiredCustomSourceProviderIds;

    /// Custom-source Runtime Hosts explicitly acknowledged in the confirmation UI.
    private final @Unmodifiable Set<String> confirmedCustomSourceProviderIds;

    /// Changed plugin artifacts whose dangerous permissions require an independent acknowledgement.
    private final @Unmodifiable Set<String> requiredDangerousPermissionPluginIds;

    /// Changed plugin artifacts whose dangerous permissions were explicitly acknowledged.
    private final @Unmodifiable Set<String> confirmedDangerousPermissionPluginIds;

    /// Exact Store runtime contracts for every package changed by the transaction.
    private final @Unmodifiable Map<String, PluginPackageRuntimeContract> expectedPackageRuntimeContracts;

    /// Creates one immutable runtime installation authorization.
    ///
    /// @param runtimeBindings selected bindings indexed by dependent plugin ID
    /// @param enablementPluginIds installed Provider IDs to enable atomically
    /// @param requiredCustomSourceProviderIds Provider IDs requiring custom-source confirmation
    /// @param confirmedCustomSourceProviderIds Provider IDs explicitly confirmed by the user
    public PluginRuntimeInstallAuthorization(
            @Unmodifiable Map<String, RuntimeProviderBinding> runtimeBindings,
            @Unmodifiable Set<String> enablementPluginIds,
            @Unmodifiable Set<String> requiredCustomSourceProviderIds,
            @Unmodifiable Set<String> confirmedCustomSourceProviderIds
    ) {
        this(runtimeBindings, enablementPluginIds, requiredCustomSourceProviderIds,
                confirmedCustomSourceProviderIds, Set.of(), Set.of(), Map.of());
    }

    /// Creates one immutable runtime installation authorization with independent dangerous-permission consent.
    ///
    /// @param runtimeBindings selected bindings indexed by dependent plugin ID
    /// @param enablementPluginIds installed Provider IDs to enable atomically
    /// @param requiredCustomSourceProviderIds Provider IDs requiring custom-source confirmation
    /// @param confirmedCustomSourceProviderIds Provider IDs explicitly confirmed for their custom source
    /// @param requiredDangerousPermissionPluginIds changed plugin IDs requiring dangerous-permission confirmation
    /// @param confirmedDangerousPermissionPluginIds changed plugin IDs explicitly confirmed for dangerous permissions
    public PluginRuntimeInstallAuthorization(
            @Unmodifiable Map<String, RuntimeProviderBinding> runtimeBindings,
            @Unmodifiable Set<String> enablementPluginIds,
            @Unmodifiable Set<String> requiredCustomSourceProviderIds,
            @Unmodifiable Set<String> confirmedCustomSourceProviderIds,
            @Unmodifiable Set<String> requiredDangerousPermissionPluginIds,
            @Unmodifiable Set<String> confirmedDangerousPermissionPluginIds
    ) {
        this(runtimeBindings, enablementPluginIds, requiredCustomSourceProviderIds,
                confirmedCustomSourceProviderIds, requiredDangerousPermissionPluginIds,
                confirmedDangerousPermissionPluginIds, Map.of());
    }

    /// Creates complete Store authorization including exact downloaded-package runtime contracts.
    ///
    /// @param runtimeBindings selected bindings indexed by dependent plugin ID
    /// @param enablementPluginIds installed Provider IDs to enable atomically
    /// @param requiredCustomSourceProviderIds Provider IDs requiring custom-source confirmation
    /// @param confirmedCustomSourceProviderIds Provider IDs explicitly confirmed for their custom source
    /// @param requiredDangerousPermissionPluginIds changed plugin IDs requiring dangerous-permission confirmation
    /// @param confirmedDangerousPermissionPluginIds changed plugin IDs explicitly confirmed for dangerous permissions
    /// @param expectedPackageRuntimeContracts exact Store runtime contracts indexed by changed plugin ID
    public PluginRuntimeInstallAuthorization(
            @Unmodifiable Map<String, RuntimeProviderBinding> runtimeBindings,
            @Unmodifiable Set<String> enablementPluginIds,
            @Unmodifiable Set<String> requiredCustomSourceProviderIds,
            @Unmodifiable Set<String> confirmedCustomSourceProviderIds,
            @Unmodifiable Set<String> requiredDangerousPermissionPluginIds,
            @Unmodifiable Set<String> confirmedDangerousPermissionPluginIds,
            @Unmodifiable Map<String, PluginPackageRuntimeContract> expectedPackageRuntimeContracts
    ) {
        this.runtimeBindings = Map.copyOf(runtimeBindings);
        this.enablementPluginIds = Set.copyOf(enablementPluginIds);
        this.requiredCustomSourceProviderIds = Set.copyOf(requiredCustomSourceProviderIds);
        this.confirmedCustomSourceProviderIds = Set.copyOf(confirmedCustomSourceProviderIds);
        this.requiredDangerousPermissionPluginIds = Set.copyOf(requiredDangerousPermissionPluginIds);
        this.confirmedDangerousPermissionPluginIds = Set.copyOf(confirmedDangerousPermissionPluginIds);
        this.expectedPackageRuntimeContracts = Map.copyOf(expectedPackageRuntimeContracts);
        for (Map.Entry<String, RuntimeProviderBinding> entry : this.runtimeBindings.entrySet()) {
            if (!entry.getKey().equals(entry.getValue().dependentPluginId())) {
                throw new IllegalArgumentException("Runtime binding key does not match dependent plugin ID: "
                        + entry.getKey());
            }
        }
        Set<String> selectedProviderIds = this.runtimeBindings.values().stream()
                .map(RuntimeProviderBinding::providerId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (!selectedProviderIds.containsAll(this.enablementPluginIds)
                || !selectedProviderIds.containsAll(this.requiredCustomSourceProviderIds)
                || !this.requiredCustomSourceProviderIds.containsAll(this.confirmedCustomSourceProviderIds)) {
            throw new IllegalArgumentException("Runtime installation authorization references an unselected Provider");
        }
        if (!this.requiredDangerousPermissionPluginIds.containsAll(
                this.confirmedDangerousPermissionPluginIds)) {
            throw new IllegalArgumentException(
                    "Dangerous-permission acknowledgement references an unrequired plugin");
        }
        for (String pluginId : this.expectedPackageRuntimeContracts.keySet()) {
            if (pluginId.isBlank()) {
                throw new IllegalArgumentException("Package runtime contract plugin ID cannot be blank");
            }
        }
    }

    /// Returns selected virtual runtime bindings.
    ///
    /// @return immutable bindings indexed by dependent plugin ID
    public @Unmodifiable Map<String, RuntimeProviderBinding> getRuntimeBindings() {
        return runtimeBindings;
    }

    /// Returns installed Runtime Hosts that must be enabled in the transaction.
    ///
    /// @return immutable Provider ID set
    public @Unmodifiable Set<String> getEnablementPluginIds() {
        return enablementPluginIds;
    }

    /// Returns Provider IDs whose custom source requires a receipt.
    ///
    /// @return immutable required acknowledgement set
    public @Unmodifiable Set<String> getRequiredCustomSourceProviderIds() {
        return requiredCustomSourceProviderIds;
    }

    /// Returns changed plugin IDs whose dangerous permissions require acknowledgement.
    ///
    /// @return immutable required dangerous-permission acknowledgement set
    public @Unmodifiable Set<String> getRequiredDangerousPermissionPluginIds() {
        return requiredDangerousPermissionPluginIds;
    }

    /// Returns exact Store runtime contracts for every package changed by the transaction.
    ///
    /// @return immutable package contract map indexed by changed plugin ID
    public @Unmodifiable Map<String, PluginPackageRuntimeContract> getExpectedPackageRuntimeContracts() {
        return expectedPackageRuntimeContracts;
    }

    /// Returns an authorization without virtual runtime mutations.
    ///
    /// @return shared empty authorization
    public static PluginRuntimeInstallAuthorization empty() {
        return EMPTY;
    }

    /// Returns whether this authorization carries no binding, enablement, or source-confirmation state.
    ///
    /// @return whether the authorization is empty
    public boolean isEmpty() {
        return runtimeBindings.isEmpty()
                && enablementPluginIds.isEmpty()
                && requiredCustomSourceProviderIds.isEmpty()
                && confirmedCustomSourceProviderIds.isEmpty()
                && requiredDangerousPermissionPluginIds.isEmpty()
                && confirmedDangerousPermissionPluginIds.isEmpty()
                && expectedPackageRuntimeContracts.isEmpty();
    }

    /// Rejects an incomplete custom-source acknowledgement before transaction preparation.
    public void requireCustomSourceReceipts() {
        Set<String> missing = requiredCustomSourceProviderIds.stream()
                .filter(providerId -> !confirmedCustomSourceProviderIds.contains(providerId))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("Missing custom-source Runtime Provider confirmation: "
                    + String.join(", ", missing.stream().sorted().toList()));
        }
    }

    /// Rejects an incomplete dangerous-permission acknowledgement before transaction preparation.
    public void requireDangerousPermissionAcknowledgements() {
        Set<String> missing = requiredDangerousPermissionPluginIds.stream()
                .filter(pluginId -> !confirmedDangerousPermissionPluginIds.contains(pluginId))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("Missing dangerous-permission confirmation: "
                    + String.join(", ", missing.stream().sorted().toList()));
        }
    }

    /// Rejects either kind of missing independent installation acknowledgement.
    public void requireAcknowledgements() {
        requireCustomSourceReceipts();
        requireDangerousPermissionAcknowledgements();
    }
}
