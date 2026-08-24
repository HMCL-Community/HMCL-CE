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

import org.jetbrains.annotations.NotNullByDefault;

import java.time.Instant;
import java.util.Objects;

/// Carries one immutable, versioned Hook invocation to a plugin endpoint.
@NotNullByDefault
public final class PluginHookEvent {
    /// Contract generation implemented by this launcher.
    public static final int CURRENT_CONTRACT_VERSION = 1;

    /// Version of the event and result data contract.
    private final int contractVersion;

    /// Opaque identifier shared by all callbacks for one dispatch.
    private final String dispatchId;

    /// Hook point being dispatched.
    private final PluginHookPoint point;

    /// Time at which this event envelope was created.
    private final Instant occurredAt;

    /// Immutable operation data.
    private final PluginDataObject data;

    /// Permission-scoped out-of-band secret accessor.
    private final PluginSecretAccess secrets;

    /// Creates one Hook event envelope.
    ///
    /// @param contractVersion supported contract generation
    /// @param dispatchId opaque non-blank dispatch identifier
    /// @param point Hook point
    /// @param occurredAt event creation time
    /// @param data immutable operation data
    /// @param secrets permission-scoped secret accessor
    public PluginHookEvent(
            int contractVersion,
            String dispatchId,
            PluginHookPoint point,
            Instant occurredAt,
            PluginDataObject data,
            PluginSecretAccess secrets
    ) {
        if (contractVersion != CURRENT_CONTRACT_VERSION) {
            throw new IllegalArgumentException("Unsupported plugin Hook contract version: " + contractVersion);
        }
        if (dispatchId.isBlank()) {
            throw new IllegalArgumentException("Plugin Hook dispatch ID must not be blank");
        }
        this.contractVersion = contractVersion;
        this.dispatchId = dispatchId;
        this.point = Objects.requireNonNull(point, "point");
        this.occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
        this.data = Objects.requireNonNull(data, "data");
        this.secrets = Objects.requireNonNull(secrets, "secrets");
    }

    /// Returns the data contract generation.
    ///
    /// @return contract generation
    public int contractVersion() {
        return contractVersion;
    }

    /// Returns the opaque dispatch identifier.
    ///
    /// @return dispatch identifier
    public String dispatchId() {
        return dispatchId;
    }

    /// Returns the dispatched Hook point.
    ///
    /// @return Hook point
    public PluginHookPoint point() {
        return point;
    }

    /// Returns when this event was created.
    ///
    /// @return event creation time
    public Instant occurredAt() {
        return occurredAt;
    }

    /// Returns immutable operation data.
    ///
    /// @return operation data
    public PluginDataObject data() {
        return data;
    }

    /// Returns the permission-scoped secret accessor.
    ///
    /// @return secret accessor
    public PluginSecretAccess secrets() {
        return secrets;
    }
}
