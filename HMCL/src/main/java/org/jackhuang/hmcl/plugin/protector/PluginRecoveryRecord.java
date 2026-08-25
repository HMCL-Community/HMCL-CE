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
/// @param failureReason bounded redacted failure summary
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
        String failureReason,
        ProtectorStage lastStage,
        long lastHeartbeatMonotonicNanos,
        @Nullable String activeProviderId,
        @Nullable String activePluginId,
        @Nullable String launcherLogReference,
        @Nullable String diagnosticDumpReference
) {
    /// Maximum retained failure-summary length after redaction.
    public static final int MAX_FAILURE_REASON_LENGTH = 4_096;

    /// Maximum retained launcher-local reference length.
    public static final int MAX_REFERENCE_LENGTH = 512;

    /// ASCII control characters that may forge recovery UI or log lines.
    private static final Pattern CONTROL_CHARACTER_PATTERN = Pattern.compile("[\\x00-\\x1f\\x7f]");

    /// Internal Protector arguments and common credential assignments removed as complete tokens.
    private static final Pattern SECRET_PATTERN = Pattern.compile(
            "(?i)(?:--hmcl-protector-(?:child|safe-mode)(?:=[^\\s,;]*)?"
                    + "|--hmcl-protector-(?:nonce|endpoint|recovery-nonce)(?:=|\\s+)[^\\s,;]+"
                    + "|(?:nonce|secret|token|password|authorization)\\s*[=:]\\s*[^\\s,;]+)"
    );

    /// Portable relative-reference syntax independent of host path separators.
    private static final Pattern REFERENCE_PATTERN = Pattern.compile("[A-Za-z0-9._/-]+");

    /// Validates, bounds, and redacts all persisted fields.
    public PluginRecoveryRecord {
        Objects.requireNonNull(failureCategory, "failureCategory");
        Objects.requireNonNull(lastStage, "lastStage");
        if (failureTimestampEpochMillis <= 0L) {
            throw new IllegalArgumentException("Recovery failure timestamp must be positive");
        }
        if (lastHeartbeatMonotonicNanos < 0L) {
            throw new IllegalArgumentException("Recovery heartbeat timestamp cannot be negative");
        }
        failureReason = redactFailureReason(failureReason);
        ProtectorMessage.validateActiveIdentities(lastStage, activeProviderId, activePluginId);
        launcherLogReference = validateReference(launcherLogReference);
        diagnosticDumpReference = validateReference(diagnosticDumpReference);
    }

    /// Redacts credentials and internal control arguments from one bounded, single-line failure reason.
    ///
    /// @param reason untrusted failure summary
    /// @return bounded redacted summary
    private static String redactFailureReason(String reason) {
        Objects.requireNonNull(reason, "reason");
        if (reason.isBlank()
                || reason.length() > MAX_FAILURE_REASON_LENGTH
                || CONTROL_CHARACTER_PATTERN.matcher(reason).find()) {
            throw new IllegalArgumentException("Recovery failure reason is missing, unbounded, or contains controls");
        }
        String redacted = SECRET_PATTERN.matcher(reason).replaceAll("[redacted]");
        if (redacted.isBlank()) {
            throw new IllegalArgumentException("Recovery failure reason is empty after redaction");
        }
        return redacted;
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
            if (component.equals(".") || component.equals("..")) {
                throw new IllegalArgumentException("Recovery reference cannot escape launcher-local storage");
            }
        }
        return reference;
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
