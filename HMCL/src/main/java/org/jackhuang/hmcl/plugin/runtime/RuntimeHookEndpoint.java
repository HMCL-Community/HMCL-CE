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
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
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
        return prepareInvocation(event).invoke(requirePositiveTimeout(timeout));
    }

    /// Prepares one exact runtime callback whose dispatch token can be revoked before callback completion.
    ///
    /// @param event immutable Hook event
    /// @return exact runtime callback invocation
    @Override
    public Invocation prepareInvocation(PluginHookEvent event) {
        return new RuntimeInvocation(Objects.requireNonNull(event, "event"));
    }

    /// One exact runtime callback invocation and its independently revocable dispatch token.
    @NotNullByDefault
    private final class RuntimeInvocation implements Invocation, CancellationSignal {
        /// Immutable event captured for this exact invocation.
        private final PluginHookEvent event;

        /// Current invocation lifecycle state.
        private final AtomicReference<InvocationState> state = new AtomicReference<>(InvocationState.PREPARED);

        /// Cancellation request published before waiting for token-issuance serialization.
        private final AtomicBoolean cancellationRequested = new AtomicBoolean();

        /// Private monitor serializing token issuance, terminal-state selection, and exact revocation.
        private final Object authorityLock = new Object();

        /// Exact issued dispatch token, or `null` before issuance and after cleanup.
        private final AtomicReference<@Nullable PluginCapabilityToken> token = new AtomicReference<>();

        /// Supervisor cancellation action installed after exact callback admission, or `null` before admission.
        private @Nullable Runnable cancellationAction;

        /// Whether the installed Supervisor cancellation action was already selected for execution.
        private boolean cancellationActionRun;

        /// Creates one initially prepared runtime invocation.
        ///
        /// @param event immutable Hook event
        private RuntimeInvocation(PluginHookEvent event) {
            this.event = event;
        }

        /// Issues, verifies, and invokes with only the dispatcher's remaining callback budget.
        ///
        /// @param remainingTimeout positive remaining dispatcher budget
        /// @return Provider result, or `null` when the Provider violates its contract
        /// @throws Exception if authority verification, transport, or the external callback fails
        @Override
        public @Nullable PluginHookResult invoke(Duration remainingTimeout) throws Exception {
            Duration callbackTimeout = requirePositiveTimeout(remainingTimeout);
            synchronized (authorityLock) {
                if (!state.compareAndSet(InvocationState.PREPARED, InvocationState.RUNNING)) {
                    throw cancelled();
                }
            }
            try {
                @Nullable ProviderInvoker invoker = providerInvoker;
                if (invoker == null) {
                    throw new PluginHookDispatchException(
                            event.point(),
                            artifactIdentity.getPluginId(),
                            PluginHookDispatchException.Category.MISSING_ENDPOINT
                    );
                }
                PluginCapabilityToken issuedToken;
                synchronized (authorityLock) {
                    requireRunning();
                    issuedToken = Objects.requireNonNull(
                            capabilityTokenSupplier.get(), "capabilityTokenSupplier result");
                    token.set(issuedToken);
                }
                permissionAuthority.requirePermission(
                        issuedToken,
                        artifactIdentity.getPluginId(),
                        artifactIdentity,
                        executionMode,
                        PluginPermission.LAUNCHER_HOOK,
                        CALLBACK_DOMAIN
                );
                requireRunning();
                @Nullable PluginHookResult result = invoker.invokeHook(
                        artifactIdentity.getPluginId(),
                        issuedToken,
                        event,
                        callbackTimeout,
                        this
                );
                synchronized (authorityLock) {
                    requireRunning();
                    revokeTokenWhileLocked();
                    state.set(InvocationState.COMPLETED);
                }
                return result;
            } finally {
                synchronized (authorityLock) {
                    revokeTokenWhileLocked();
                    state.compareAndSet(InvocationState.RUNNING, InvocationState.COMPLETED);
                }
            }
        }

        /// Cancels this exact callback and immediately revokes its issued dispatch token.
        @Override
        public void cancel() {
            cancellationRequested.set(true);
            synchronized (authorityLock) {
                InvocationState current = state.get();
                if (current == InvocationState.CANCELLED || current == InvocationState.COMPLETED) {
                    return;
                }
                state.set(InvocationState.CANCELLED);
                revokeTokenWhileLocked();
            }
            runCancellationActionIfReady();
        }

        /// Returns whether cancellation won this exact invocation.
        ///
        /// @return whether the invocation is cancelled
        @Override
        public boolean isCancelled() {
            return state.get() == InvocationState.CANCELLED;
        }

        /// Installs the Supervisor action which cancels the exact admitted callback record.
        ///
        /// @param action exact callback cancellation action
        @Override
        public void onCancel(Runnable action) {
            Objects.requireNonNull(action, "action");
            synchronized (this) {
                if (cancellationAction != null) {
                    throw new IllegalStateException("Runtime Hook cancellation action is already installed");
                }
                cancellationAction = action;
            }
            runCancellationActionIfReady();
        }

        /// Revokes and clears the exact issued token while holding [authorityLock].
        private void revokeTokenWhileLocked() {
            @Nullable PluginCapabilityToken issuedToken = token.getAndSet(null);
            if (issuedToken != null) {
                permissionAuthority.revoke(issuedToken);
            }
        }

        /// Selects and runs the installed Supervisor cancellation action at most once.
        private void runCancellationActionIfReady() {
            @Nullable Runnable action = null;
            synchronized (this) {
                if (!cancellationActionRun
                        && state.get() == InvocationState.CANCELLED
                        && cancellationAction != null) {
                    cancellationActionRun = true;
                    action = cancellationAction;
                }
            }
            if (action != null) {
                action.run();
            }
        }

        /// Requires cancellation not to have won the current invocation race.
        private void requireRunning() {
            if (cancellationRequested.get() || state.get() != InvocationState.RUNNING) {
                throw cancelled();
            }
        }

        /// Creates one stable cancellation failure without Provider-controlled details.
        ///
        /// @return cancellation failure
        private CancellationException cancelled() {
            return new CancellationException("Runtime Hook invocation was cancelled");
        }
    }

    /// Lifecycle state for one exact runtime Hook invocation.
    @NotNullByDefault
    private enum InvocationState {
        /// Invocation exists but has not entered the Provider path.
        PREPARED,

        /// Invocation issued authority and may be executing Provider code.
        RUNNING,

        /// Invocation completed or failed and released its authority.
        COMPLETED,

        /// Cancellation won and revoked invocation authority.
        CANCELLED
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
        /// @param cancellation exact invocation cancellation signal
        /// @return external callback result, or `null` for malformed Provider output
        /// @throws Exception if the Provider transport or external callback fails
        @Nullable PluginHookResult invokeHook(
                String ownerPluginId,
                PluginCapabilityToken token,
                PluginHookEvent event,
                Duration timeout,
                CancellationSignal cancellation
        ) throws Exception;
    }

    /// Exact invocation cancellation boundary shared by the endpoint and Runtime Supervisor.
    @NotNullByDefault
    public interface CancellationSignal {
        /// Cancels the invocation and synchronously revokes its dispatch authority.
        void cancel();

        /// Returns whether cancellation already won.
        ///
        /// @return whether the invocation is cancelled
        boolean isCancelled();

        /// Installs one idempotent Supervisor-owned callback cancellation action.
        ///
        /// @param action exact callback cancellation action
        void onCancel(Runnable action);
    }
}
