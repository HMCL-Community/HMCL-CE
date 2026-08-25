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
package org.jackhuang.hmcl.plugin.protector;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.regex.Pattern;

/// Immutable, bounded, credential-safe description of one failed pre-UI startup.
///
/// @param failureTimestampEpochMillis wall-clock failure time in Unix epoch milliseconds
/// @param failureCategory stable failure category
/// @param failureReason controlled failure reason
/// @param lastStage last authenticated startup stage
/// @param lastHeartbeatMonotonicNanos last authenticated monotonic heartbeat timestamp
/// @param activeProviderId active Runtime Provider at failure, or `null`
/// @param activePluginId active ordinary plugin at failure, or `null`
/// @param launcherLogReference safe launcher-local relative log reference, or `null`
/// @param diagnosticDumpReference safe launcher-local relative diagnostic reference, or `null`
@NotNullByDefault
public record PluginRecoveryRecord(
        long failureTimestampEpochMillis,
        FailureCategory failureCategory,
        FailureReason failureReason,
        ProtectorStage lastStage,
        long lastHeartbeatMonotonicNanos,
        @Nullable String activeProviderId,
        @Nullable String activePluginId,
        @Nullable String launcherLogReference,
        @Nullable String diagnosticDumpReference
) {
    /// Maximum retained launcher-local reference length.
    public static final int MAX_REFERENCE_LENGTH = 512;

    /// Portable relative-reference syntax independent of host path separators.
    private static final Pattern REFERENCE_PATTERN = Pattern.compile("[A-Za-z0-9._/-]+");

    /// Windows device names that remain reserved with any case or filename extension.
    private static final Pattern WINDOWS_RESERVED_COMPONENT_PATTERN = Pattern.compile(
            "(?i)(?:CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\\..*)?"
    );

    /// Validates, bounds, and redacts all persisted fields.
    public PluginRecoveryRecord {
        Objects.requireNonNull(failureCategory, "failureCategory");
        Objects.requireNonNull(failureReason, "failureReason");
        Objects.requireNonNull(lastStage, "lastStage");
        if (failureTimestampEpochMillis <= 0L) {
            throw new IllegalArgumentException("Recovery failure timestamp must be positive");
        }
        if (lastHeartbeatMonotonicNanos < 0L) {
            throw new IllegalArgumentException("Recovery heartbeat timestamp cannot be negative");
        }
        if (failureReason.category() != failureCategory) {
            throw new IllegalArgumentException("Recovery failure reason does not match its category");
        }
        validateFailureState(failureReason, lastStage, activeProviderId, activePluginId);
        launcherLogReference = validateReference(launcherLogReference);
        diagnosticDumpReference = validateReference(diagnosticDumpReference);
    }

    /// Validates one optional portable launcher-local reference without resolving or opening it.
    ///
    /// @param reference candidate relative reference, or `null`
    /// @return unchanged safe reference, or `null`
    private static @Nullable String validateReference(@Nullable String reference) {
        if (reference == null) {
            return null;
        }
        if (reference.isBlank()
                || reference.length() > MAX_REFERENCE_LENGTH
                || !REFERENCE_PATTERN.matcher(reference).matches()
                || reference.startsWith("/")
                || reference.endsWith("/")
                || reference.contains("//")) {
            throw new IllegalArgumentException("Recovery reference must be a bounded portable relative path");
        }
        for (String component : reference.split("/", -1)) {
            if (component.equals(".")
                    || component.equals("..")
                    || component.endsWith(".")
                    || component.endsWith(" ")
                    || component.contains(":")
                    || WINDOWS_RESERVED_COMPONENT_PATTERN.matcher(component).matches()) {
                throw new IllegalArgumentException("Recovery reference contains a non-portable path component");
            }
        }
        return reference;
    }

    /// Validates that one controlled reason could occur at the recorded pre-ready stage and identity.
    ///
    /// Broad process, crash, heartbeat, and hard-deadline failures may occur at any pre-ready stage with an optional
    /// matching active identity. Stage-specific deadlines are confined to their exact stage, and Provider or plugin
    /// deadlines require the corresponding active identity.
    ///
    /// @param reason controlled failure reason
    /// @param stage last authenticated startup stage
    /// @param activeProviderId active Runtime Provider ID, or `null`
    /// @param activePluginId active ordinary plugin ID, or `null`
    private static void validateFailureState(
            FailureReason reason,
            ProtectorStage stage,
            @Nullable String activeProviderId,
            @Nullable String activePluginId
    ) {
        if (stage == ProtectorStage.UI_READY) {
            throw new IllegalArgumentException("Recovery records cannot describe completed startup");
        }
        ProtectorMessage.validateActiveIdentities(stage, activeProviderId, activePluginId);
        boolean valid = switch (reason) {
            case CORE_DEADLINE_EXCEEDED -> stage == ProtectorStage.JVM_STARTED
                    && activeProviderId == null
                    && activePluginId == null;
            case PROVIDER_DEADLINE_EXCEEDED -> stage == ProtectorStage.RUNTIME_PROVIDERS_LOADING
                    && activeProviderId != null;
            case PLUGIN_DEADLINE_EXCEEDED -> stage == ProtectorStage.ORDINARY_PLUGINS_LOADING
                    && activePluginId != null;
            case UNEXPECTED_PROCESS_EXIT, CHILD_CRASH, HEARTBEAT_LOST, HARD_STARTUP_DEADLINE_EXCEEDED -> true;
        };
        if (!valid) {
            throw new IllegalArgumentException("Recovery failure reason does not match its startup state");
        }
    }

    /// Controlled startup failure reasons that cannot contain exception text, arguments, credentials, or IPC data.
    @NotNullByDefault
    public enum FailureReason {
        /// The protected child exited unexpectedly before UI readiness.
        UNEXPECTED_PROCESS_EXIT("unexpected-process-exit", FailureCategory.PROCESS_EXIT),

        /// The protected child crashed before UI readiness.
        CHILD_CRASH("child-crash", FailureCategory.CRASH),

        /// Authenticated heartbeat traffic stopped before UI readiness.
        HEARTBEAT_LOST("heartbeat-lost", FailureCategory.HEARTBEAT_LOSS),

        /// Core initialization exceeded its deadline.
        CORE_DEADLINE_EXCEEDED("core-deadline-exceeded", FailureCategory.STAGE_TIMEOUT),

        /// One active Runtime Provider exceeded its startup deadline.
        PROVIDER_DEADLINE_EXCEEDED("provider-deadline-exceeded", FailureCategory.STAGE_TIMEOUT),

        /// One active ordinary plugin exceeded its startup deadline.
        PLUGIN_DEADLINE_EXCEEDED("plugin-deadline-exceeded", FailureCategory.STAGE_TIMEOUT),

        /// Overall startup exceeded the non-renewable hard deadline.
        HARD_STARTUP_DEADLINE_EXCEEDED(
                "hard-startup-deadline-exceeded",
                FailureCategory.HARD_STARTUP_TIMEOUT
        );

        /// Stable recovery-document spelling.
        private final String wireName;

        /// Required broad classification for this reason.
        private final FailureCategory category;

        /// Creates one controlled reason with its stable wire spelling and classification.
        ///
        /// @param wireName stable wire spelling
        /// @param category required broad classification
        FailureReason(String wireName, FailureCategory category) {
            this.wireName = wireName;
            this.category = category;
        }

        /// Returns the stable recovery-document spelling.
        ///
        /// @return stable wire spelling
        public String wireName() {
            return wireName;
        }

        /// Returns the required broad classification.
        ///
        /// @return required failure category
        public FailureCategory category() {
            return category;
        }

        /// Resolves an exact wire spelling without retaining an unknown source value.
        ///
        /// @param wireName candidate wire spelling
        /// @return matching reason, or `null` when unknown
        static @Nullable FailureReason fromWireName(String wireName) {
            for (FailureReason reason : values()) {
                if (reason.wireName.equals(wireName)) {
                    return reason;
                }
            }
            return null;
        }
    }

    /// Categories that distinguish startup failure behavior without retaining raw process output.
    @NotNullByDefault
    public enum FailureCategory {
        /// Child process exited unexpectedly before UI readiness.
        PROCESS_EXIT("process-exit"),

        /// Child process reported or produced a crash before UI readiness.
        CRASH("crash"),

        /// Authenticated heartbeat traffic stopped for longer than the liveness deadline.
        HEARTBEAT_LOSS("heartbeat-loss"),

        /// Current Core, Provider, or plugin stage exceeded its lease.
        STAGE_TIMEOUT("stage-timeout"),

        /// Overall startup exceeded the non-renewable hard deadline.
        HARD_STARTUP_TIMEOUT("hard-startup-timeout");

        /// Stable recovery-document spelling.
        private final String wireName;

        /// Creates one failure category with its stable wire spelling.
        ///
        /// @param wireName stable wire spelling
        FailureCategory(String wireName) {
            this.wireName = wireName;
        }

        /// Returns the stable recovery-document spelling.
        ///
        /// @return stable wire spelling
        public String wireName() {
            return wireName;
        }

        /// Resolves an exact wire spelling without accepting future categories implicitly.
        ///
        /// @param wireName candidate wire spelling
        /// @return matching category, or `null` when unknown
        static @Nullable FailureCategory fromWireName(String wireName) {
            for (FailureCategory category : values()) {
                if (category.wireName.equals(wireName)) {
                    return category;
                }
            }
            return null;
        }
    }
}
