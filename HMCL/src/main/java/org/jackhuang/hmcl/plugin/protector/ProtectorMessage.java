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

import org.jackhuang.hmcl.plugin.PluginManifest;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/// Immutable authenticated control-message payload independent of its session nonce.
///
/// @param kind control operation
/// @param monotonicTimestampNanos sender monotonic timestamp in nanoseconds
/// @param stage current startup stage
/// @param activeProviderId active Runtime Provider ID only while Providers are loading, or `null`
/// @param activePluginId active ordinary plugin ID only while ordinary plugins are loading, or `null`
@NotNullByDefault
public record ProtectorMessage(
        Kind kind,
        long monotonicTimestampNanos,
        ProtectorStage stage,
        @Nullable String activeProviderId,
        @Nullable String activePluginId
) {
    /// Validates the bounded monotonic timestamp and stage-scoped plugin identities.
    public ProtectorMessage {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(stage, "stage");
        if (monotonicTimestampNanos < 0L) {
            throw new IllegalArgumentException("Protector monotonic timestamp cannot be negative");
        }
        validateKindAndStage(kind, stage, activeProviderId, activePluginId);
        validateActiveIdentities(stage, activeProviderId, activePluginId);
    }

    /// Validates the control-flow stage matrix and confines active identities to state-bearing messages.
    ///
    /// @param kind control operation
    /// @param stage current startup stage
    /// @param activeProviderId active Runtime Provider ID, or `null`
    /// @param activePluginId active ordinary plugin ID, or `null`
    private static void validateKindAndStage(
            Kind kind,
            ProtectorStage stage,
            @Nullable String activeProviderId,
            @Nullable String activePluginId
    ) {
        boolean allowedStage = switch (kind) {
            case HEARTBEAT, NORMAL_SHUTDOWN -> true;
            case STAGE, CANCEL, DIAGNOSTICS_REQUEST, DIAGNOSTICS_RESPONSE,
                    TERMINATION_REQUEST, TERMINATION_ACKNOWLEDGED -> stage != ProtectorStage.UI_READY;
            case READY -> stage == ProtectorStage.UI_READY;
            case LEASE_RENEWAL -> stage == ProtectorStage.CORE_READY
                    || stage == ProtectorStage.RUNTIME_PROVIDERS_LOADING
                    || stage == ProtectorStage.ORDINARY_PLUGINS_LOADING;
        };
        if (!allowedStage) {
            throw new IllegalArgumentException("Control message kind is invalid for the startup stage");
        }

        boolean identityBearing = kind == Kind.HEARTBEAT || kind == Kind.STAGE || kind == Kind.LEASE_RENEWAL;
        if (!identityBearing && (activeProviderId != null || activePluginId != null)) {
            throw new IllegalArgumentException("Control message kind cannot carry an active plugin identity");
        }
    }

    /// Validates canonical identities and confines them to the matching loading stage.
    ///
    /// @param stage current startup stage
    /// @param activeProviderId active Runtime Provider ID, or `null`
    /// @param activePluginId active ordinary plugin ID, or `null`
    static void validateActiveIdentities(
            ProtectorStage stage,
            @Nullable String activeProviderId,
            @Nullable String activePluginId
    ) {
        if (activeProviderId != null) {
            if (stage != ProtectorStage.RUNTIME_PROVIDERS_LOADING
                    || !PluginManifest.isCanonicalExecutableId(activeProviderId)) {
                throw new IllegalArgumentException("Invalid active Runtime Provider identity");
            }
        }
        if (activePluginId != null) {
            if (stage != ProtectorStage.ORDINARY_PLUGINS_LOADING
                    || !PluginManifest.isCanonicalExecutableId(activePluginId)) {
                throw new IllegalArgumentException("Invalid active plugin identity");
            }
        }
    }

    /// Control operations exchanged by Protector and the protected launcher child.
    @NotNullByDefault
    public enum Kind {
        /// Periodic liveness report.
        HEARTBEAT("heartbeat"),

        /// Startup-stage transition report.
        STAGE("stage"),

        /// Successful UI-ready terminal report.
        READY("ready"),

        /// Explicit user-cancellation terminal report.
        CANCEL("cancel"),

        /// Normal child shutdown terminal report.
        NORMAL_SHUTDOWN("normal-shutdown"),

        /// Parent request for child diagnostics.
        DIAGNOSTICS_REQUEST("diagnostics-request"),

        /// Child acknowledgement that diagnostics were captured.
        DIAGNOSTICS_RESPONSE("diagnostics-response"),

        /// Controlled migration request to renew the current stage lease.
        LEASE_RENEWAL("lease-renewal"),

        /// Parent request for graceful child termination.
        TERMINATION_REQUEST("termination-request"),

        /// Child acknowledgement of a graceful termination request.
        TERMINATION_ACKNOWLEDGED("termination-acknowledged");

        /// Stable control-protocol spelling.
        private final String wireName;

        /// Creates one kind with its stable wire spelling.
        ///
        /// @param wireName stable wire spelling
        Kind(String wireName) {
            this.wireName = wireName;
        }

        /// Returns the stable wire spelling.
        ///
        /// @return stable wire spelling
        public String wireName() {
            return wireName;
        }

        /// Resolves an exact wire spelling without accepting future values implicitly.
        ///
        /// @param wireName candidate wire spelling
        /// @return matching kind, or `null` when unknown
        static @Nullable Kind fromWireName(String wireName) {
            for (Kind kind : values()) {
                if (kind.wireName.equals(wireName)) {
                    return kind;
                }
            }
            return null;
        }
    }
}
