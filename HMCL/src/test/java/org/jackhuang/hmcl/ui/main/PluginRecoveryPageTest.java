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

import org.jackhuang.hmcl.plugin.PluginQuarantineReport;
import org.jackhuang.hmcl.plugin.protector.PluginRecoveryRecord;
import org.jackhuang.hmcl.plugin.protector.ProtectorStage;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies the secret-free recovery presentation and exact restore action routing without constructing JavaFX UI.
@NotNullByDefault
public final class PluginRecoveryPageTest {
    /// Produces only approved recovery fields and performs no mutation while constructing or reading the model.
    @Test
    public void presentRecoveryDetailsWithoutMutationOrSecretFields() {
        RecordingBackend backend = new RecordingBackend();
        PluginRecoveryPage.ActionModel model = new PluginRecoveryPage.ActionModel(backend);

        assertTrueNoOperations(backend);
        PluginRecoveryPage.Presentation presentation = model.presentation().orElseThrow();

        assertEquals(List.of(
                PluginRecoveryPage.DetailKind.FAILURE_TIME,
                PluginRecoveryPage.DetailKind.FAILURE_REASON,
                PluginRecoveryPage.DetailKind.LAST_STAGE,
                PluginRecoveryPage.DetailKind.LAST_HEARTBEAT,
                PluginRecoveryPage.DetailKind.ACTIVE_PROVIDER,
                PluginRecoveryPage.DetailKind.ACTIVE_PLUGIN,
                PluginRecoveryPage.DetailKind.LAUNCHER_LOG,
                PluginRecoveryPage.DetailKind.DIAGNOSTIC_DUMP,
                PluginRecoveryPage.DetailKind.RETAINED_FILES
        ), presentation.details().stream().map(PluginRecoveryPage.Detail::kind).toList());
        assertEquals("987654321", presentation.details().get(3).value());
        assertEquals(List.of("dev.hmclce.test.alpha", "dev.hmclce.test.zeta"),
                presentation.quarantinedPluginIds());
        assertFalse(presentation.toString().toLowerCase(java.util.Locale.ROOT).contains("nonce"));
        assertTrueNoOperations(backend);
    }

    /// Routes one, selected-group, and all restore actions to their exact backend operations.
    ///
    /// @throws Exception if the recording backend unexpectedly rejects an action
    @Test
    public void routeExactRestoreActions() throws Exception {
        RecordingBackend backend = new RecordingBackend();
        PluginRecoveryPage.ActionModel model = new PluginRecoveryPage.ActionModel(backend);

        assertEquals(List.of("dev.hmclce.test.alpha"), model.restoreOne("dev.hmclce.test.alpha"));
        assertEquals(List.of("dev.hmclce.test.alpha", "dev.hmclce.test.zeta"),
                model.restoreSelected(Set.of("dev.hmclce.test.zeta", "dev.hmclce.test.alpha")));
        assertEquals(List.of("dev.hmclce.test.alpha", "dev.hmclce.test.zeta"), model.restoreAll());

        assertEquals(List.of(
                "one:dev.hmclce.test.alpha",
                "selected:dev.hmclce.test.alpha,dev.hmclce.test.zeta",
                "all"
        ), backend.operations);
    }

    /// Treats recovery as available only while a report and at least one quarantined plugin are both present.
    @Test
    public void requireReportAndQuarantinedPluginForRecoveryAvailability() {
        RecordingBackend backend = new RecordingBackend();
        PluginRecoveryPage.ActionModel model = new PluginRecoveryPage.ActionModel(backend);

        assertTrue(model.hasRecovery());
        backend.quarantinedPluginIds = Set.of();
        assertFalse(model.hasRecovery());
        backend.report = Optional.empty();
        backend.quarantinedPluginIds = Set.of("dev.hmclce.test.alpha");
        assertFalse(model.hasRecovery());
    }

    /// Asserts that model construction and presentation reads do not invoke a restore operation.
    ///
    /// @param backend recording backend
    private static void assertTrueNoOperations(RecordingBackend backend) {
        assertEquals(List.of(), backend.operations);
    }

    /// Deterministic backend fixture recording only explicit restore calls.
    @NotNullByDefault
    private static final class RecordingBackend implements PluginRecoveryPage.RecoveryBackend {
        /// Explicit mutation operations received from the action model.
        private final List<String> operations = new ArrayList<>();

        /// Current fixture report.
        private Optional<PluginQuarantineReport> report = Optional.of(createReport());

        /// Current fixture quarantine state.
        private @Unmodifiable Set<String> quarantinedPluginIds =
                Set.of("dev.hmclce.test.zeta", "dev.hmclce.test.alpha");

        /// Returns one complete secret-free recovery report.
        ///
        /// @return fixture report
        @Override
        public Optional<PluginQuarantineReport> getReport() {
            return report;
        }

        /// Creates one complete fixture report.
        ///
        /// @return fixture report
        private static PluginQuarantineReport createReport() {
            return new PluginQuarantineReport(
                    1_777_000_000_000L,
                    PluginRecoveryRecord.FailureCategory.STAGE_TIMEOUT,
                    PluginRecoveryRecord.FailureReason.PROVIDER_DEADLINE_EXCEEDED,
                    ProtectorStage.RUNTIME_PROVIDERS_LOADING,
                    987_654_321L,
                    "dev.hmclce.test.provider",
                    null,
                    "logs/hmcl.log",
                    "diagnostics/startup.txt",
                    Set.of("dev.hmclce.test.zeta", "dev.hmclce.test.alpha"),
                    true,
                    true,
                    true
            );
        }

        /// Returns the currently quarantined IDs.
        ///
        /// @return immutable fixture IDs
        @Override
        public @Unmodifiable Set<String> getQuarantinedPluginIds() {
            return quarantinedPluginIds;
        }

        /// Records a one-plugin restore.
        ///
        /// @param pluginId selected plugin ID
        /// @return restored closure
        @Override
        public @Unmodifiable List<String> restoreOne(String pluginId) {
            operations.add("one:" + pluginId);
            return List.of(pluginId);
        }

        /// Records a selected-group restore.
        ///
        /// @param pluginIds selected plugin IDs
        /// @return restored closure
        @Override
        public @Unmodifiable List<String> restoreSelected(@Unmodifiable Set<String> pluginIds) {
            List<String> sorted = pluginIds.stream().sorted().toList();
            operations.add("selected:" + String.join(",", sorted));
            return sorted;
        }

        /// Records an all-plugin restore.
        ///
        /// @return restored closure
        @Override
        public @Unmodifiable List<String> restoreAll() throws IOException {
            operations.add("all");
            return List.of("dev.hmclce.test.alpha", "dev.hmclce.test.zeta");
        }
    }
}
