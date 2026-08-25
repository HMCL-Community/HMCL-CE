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
package org.jackhuang.hmcl.plugin.runtime;

import org.jackhuang.hmcl.plugin.PluginArtifactIdentity;
import org.jackhuang.hmcl.plugin.PluginHookDispatchException;
import org.jackhuang.hmcl.plugin.PluginHookEndpoint;
import org.jackhuang.hmcl.plugin.PluginHookEvent;
import org.jackhuang.hmcl.plugin.PluginHookResult;
import org.jackhuang.hmcl.plugin.PluginPermission;
import org.jackhuang.hmcl.plugin.bridge.PluginCapabilityToken;
import org.jackhuang.hmcl.plugin.bridge.PluginPermissionAuthority;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Supplier;

/// Adapts one selected external Runtime Provider callback to the launcher's shared Hook dispatcher contract.
///
/// Event immutability, secret filtering, result validation, safe cancellation, ordering, timeout enforcement,
/// callback leases, and failure categorization remain owned by the existing dispatcher and Hook policy.
@NotNullByDefault
public final class RuntimeHookEndpoint implements PluginHookEndpoint {
    /// Capability domain shared by one external payload lifecycle session.
    static final String CALLBACK_DOMAIN = "runtime.payload";

    /// Fallback deadline used only by callers of the legacy single-argument endpoint method.
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    /// Exact external payload package identity.
    private final PluginArtifactIdentity artifactIdentity;

    /// External payload execution boundary.
    private final PluginExecutionMode executionMode;

    /// Launcher-owned authority which verifies every callback token before Provider entry.
    private final PluginPermissionAuthority permissionAuthority;

    /// Issues a token from the payload's current lifecycle-session generation.
    private final Supplier<PluginCapabilityToken> capabilityTokenSupplier;

    /// Selected Provider callback, or `null` when a declared external Hook has no callable endpoint.
    private final @Nullable ProviderInvoker providerInvoker;

    /// Creates one external Hook endpoint bound to an exact payload and selected Provider.
    ///
    /// @param artifactIdentity exact external payload identity
    /// @param executionMode payload execution boundary
    /// @param permissionAuthority launcher-owned token verifier
    /// @param capabilityTokenSupplier current payload-session token source
    /// @param providerInvoker selected Provider callback, or `null` when unavailable
    public RuntimeHookEndpoint(
            PluginArtifactIdentity artifactIdentity,
            PluginExecutionMode executionMode,
            PluginPermissionAuthority permissionAuthority,
            Supplier<PluginCapabilityToken> capabilityTokenSupplier,
            @Nullable ProviderInvoker providerInvoker
    ) {
        this.artifactIdentity = Objects.requireNonNull(artifactIdentity, "artifactIdentity");
        this.executionMode = Objects.requireNonNull(executionMode, "executionMode");
        this.permissionAuthority = Objects.requireNonNull(permissionAuthority, "permissionAuthority");
        this.capabilityTokenSupplier = Objects.requireNonNull(
                capabilityTokenSupplier, "capabilityTokenSupplier");
        this.providerInvoker = providerInvoker;
    }

    /// Invokes the selected Provider with the production default deadline.
    ///
    /// Dispatcher calls use [invoke(PluginHookEvent, Duration)] and therefore preserve injected test or future
    /// configuration deadlines.
    ///
    /// @param event immutable Hook event
    /// @return Provider result, or `null` when the Provider violates its contract
    /// @throws Exception if authority verification, transport, or the external callback fails
    @Override
    public @Nullable PluginHookResult invoke(PluginHookEvent event) throws Exception {
        return invoke(event, DEFAULT_TIMEOUT);
    }

    /// Verifies current plugin-scoped Hook authority and invokes the selected Provider with the exact deadline.
    ///
    /// @param event immutable Hook event
    /// @param timeout positive dispatcher callback deadline
    /// @return Provider result, or `null` when the Provider violates its contract
    /// @throws Exception if authority verification, transport, or the external callback fails
    @Override
    public @Nullable PluginHookResult invoke(PluginHookEvent event, Duration timeout) throws Exception {
        PluginHookEvent immutableEvent = Objects.requireNonNull(event, "event");
        Duration callbackTimeout = requirePositiveTimeout(timeout);
        @Nullable ProviderInvoker invoker = providerInvoker;
        if (invoker == null) {
            throw new PluginHookDispatchException(
                    immutableEvent.point(),
                    artifactIdentity.getPluginId(),
                    PluginHookDispatchException.Category.MISSING_ENDPOINT
            );
        }
        PluginCapabilityToken token = Objects.requireNonNull(
                capabilityTokenSupplier.get(), "capabilityTokenSupplier result");
        permissionAuthority.requirePermission(
                token,
                artifactIdentity.getPluginId(),
                artifactIdentity,
                executionMode,
                PluginPermission.LAUNCHER_HOOK,
                CALLBACK_DOMAIN
        );
        try {
            return invoker.invokeHook(
                    artifactIdentity.getPluginId(),
                    token,
                    immutableEvent,
                    callbackTimeout
            );
        } finally {
            permissionAuthority.revoke(token);
        }
    }

    /// Requires a positive timeout without changing its exact value.
    ///
    /// @param timeout candidate callback deadline
    /// @return unchanged positive deadline
    private static Duration requirePositiveTimeout(Duration timeout) {
        Duration value = Objects.requireNonNull(timeout, "timeout");
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("Runtime Hook timeout must be positive");
        }
        return value;
    }

    /// Provider-side transport callback for one external runtime payload.
    @FunctionalInterface
    @NotNullByDefault
    public interface ProviderInvoker {
        /// Invokes one external Hook callback without applying launcher Hook policy locally.
        ///
        /// @param ownerPluginId exact dependent plugin owner
        /// @param token short-lived plugin-scoped capability token
        /// @param event immutable Hook event
        /// @param timeout positive dispatcher callback deadline
        /// @return external callback result, or `null` for malformed Provider output
        /// @throws Exception if the Provider transport or external callback fails
        @Nullable PluginHookResult invokeHook(
                String ownerPluginId,
                PluginCapabilityToken token,
                PluginHookEvent event,
                Duration timeout
        ) throws Exception;
    }
}
