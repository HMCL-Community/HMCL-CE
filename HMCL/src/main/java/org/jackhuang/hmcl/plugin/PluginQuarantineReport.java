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

import org.jackhuang.hmcl.plugin.protector.PluginRecoveryRecord;
import org.jackhuang.hmcl.plugin.protector.ProtectorStage;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/// Secret-free recovery summary published after every installed third-party plugin is durably quarantined.
///
/// @param failureTimestampEpochMillis wall-clock failure time in Unix epoch milliseconds
/// @param failureCategory stable broad failure category
/// @param failureReason controlled startup failure reason
/// @param lastStage last authenticated startup stage
/// @param lastHeartbeatMonotonicNanos last authenticated protector heartbeat from the failed startup
/// @param activeProviderId active Runtime Provider at failure, or `null`
/// @param activePluginId active ordinary plugin at failure, or `null`
/// @param launcherLogReference safe launcher-local relative log reference, or `null`
/// @param diagnosticDumpReference safe launcher-local relative diagnostic reference, or `null`
/// @param quarantinedPluginIds immutable sorted IDs quarantined for this recovery record
/// @param pluginPackagesRetained whether installed plugin package files were retained
/// @param pluginConfigurationRetained whether extracted plugin configuration files were retained
/// @param pluginDataRetained whether persistent plugin data files were retained
@NotNullByDefault
public record PluginQuarantineReport(
        long failureTimestampEpochMillis,
        PluginRecoveryRecord.FailureCategory failureCategory,
        PluginRecoveryRecord.FailureReason failureReason,
        ProtectorStage lastStage,
        long lastHeartbeatMonotonicNanos,
        @Nullable String activeProviderId,
        @Nullable String activePluginId,
        @Nullable String launcherLogReference,
        @Nullable String diagnosticDumpReference,
        @Unmodifiable Set<String> quarantinedPluginIds,
        boolean pluginPackagesRetained,
        boolean pluginConfigurationRetained,
        boolean pluginDataRetained
) {
    /// Validates immutable report data and captures quarantine IDs in deterministic order.
    public PluginQuarantineReport {
        new PluginRecoveryRecord(
                failureTimestampEpochMillis,
                failureCategory,
                failureReason,
                lastStage,
                lastHeartbeatMonotonicNanos,
                activeProviderId,
                activePluginId,
                launcherLogReference,
                diagnosticDumpReference
        );
        Objects.requireNonNull(quarantinedPluginIds, "quarantinedPluginIds");
        if (!pluginPackagesRetained || !pluginConfigurationRetained || !pluginDataRetained) {
            throw new IllegalArgumentException("Quarantine report must retain all plugin-owned files");
        }
        LinkedHashSet<String> sortedIds = new LinkedHashSet<>();
        quarantinedPluginIds.stream().sorted().forEach(pluginId -> {
            if (!PluginManifest.isValidId(pluginId)) {
                throw new IllegalArgumentException("Invalid quarantined plugin ID: " + pluginId);
            }
            sortedIds.add(pluginId);
        });
        quarantinedPluginIds = Collections.unmodifiableSet(sortedIds);
    }

    /// Creates a retained-files report from one strict recovery record and the exact quarantined package IDs.
    ///
    /// @param recoveryRecord consumed strict recovery record
    /// @param quarantinedPluginIds exact installed third-party IDs quarantined for the record
    /// @return immutable secret-free recovery report
    static PluginQuarantineReport fromRecovery(
            PluginRecoveryRecord recoveryRecord,
            @Unmodifiable Set<String> quarantinedPluginIds
    ) {
        return new PluginQuarantineReport(
                recoveryRecord.failureTimestampEpochMillis(),
                recoveryRecord.failureCategory(),
                recoveryRecord.failureReason(),
                recoveryRecord.lastStage(),
                recoveryRecord.lastHeartbeatMonotonicNanos(),
                recoveryRecord.activeProviderId(),
                recoveryRecord.activePluginId(),
                recoveryRecord.launcherLogReference(),
                recoveryRecord.diagnosticDumpReference(),
                quarantinedPluginIds,
                true,
                true,
                true
        );
    }
}
