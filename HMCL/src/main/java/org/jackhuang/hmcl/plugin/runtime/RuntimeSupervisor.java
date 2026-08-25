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
import org.jackhuang.hmcl.plugin.PluginHookEvent;
import org.jackhuang.hmcl.plugin.PluginHookResult;
import org.jackhuang.hmcl.plugin.PluginManifest;
import org.jackhuang.hmcl.plugin.PluginPatchDeclaration;
import org.jackhuang.hmcl.plugin.bridge.PluginCapabilityToken;
import org.jackhuang.hmcl.plugin.bridge.PluginPermissionAuthority;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/// Serializes external runtime Provider startup, payload delegation, rollback, and reverse-order shutdown.
@NotNullByDefault
public final class RuntimeSupervisor {
    /// Runtime registry used for Provider lookup and dependent bindings.
    private final RuntimeProviderRegistry registry;

    /// Current lifecycle state keyed by canonical Provider plugin ID.
    private final Map<String, RuntimeProviderState> states = new LinkedHashMap<>();

    /// Complete observable state history keyed by canonical Provider plugin ID.
    private final Map<String, List<RuntimeProviderState>> histories = new LinkedHashMap<>();

    /// Active Host-owned registrations keyed by Provider plugin ID.
    private final Map<String, RuntimeProviderRegistration> registrations = new LinkedHashMap<>();

    /// Provider Hosts whose plugin containers currently permit dependent callbacks.
    private final Set<String> enabledHosts = new LinkedHashSet<>();

    /// Loaded payloads in insertion order for strict reverse teardown.
    private final Map<RuntimePayloadHandle, PayloadRecord> payloads = new LinkedHashMap<>();

    /// Creates a lifecycle owner around one runtime registry.
    ///
    /// @param registry Provider and binding registry
    public RuntimeSupervisor(RuntimeProviderRegistry registry) {
        this.registry = registry;
    }

    /// Records discovery of one enabled external Provider package.
    ///
    /// @param providerId canonical Provider plugin ID
    public synchronized void discover(String providerId) {
        requireCanonicalId(providerId);
        @Nullable RuntimeProviderState current = states.get(providerId);
        if (current != null && current != RuntimeProviderState.STOPPED && current != RuntimeProviderState.FAILED) {
            throw invalidTransition(providerId, current, RuntimeProviderState.DISCOVERED);
        }
        transition(providerId, RuntimeProviderState.DISCOVERED);
    }

    /// Records completion of concrete and virtual dependency resolution.
    ///
    /// @param providerId canonical Provider plugin ID
    public synchronized void resolve(String providerId) {
        transitionFrom(providerId, RuntimeProviderState.DISCOVERED, RuntimeProviderState.RESOLVED);
    }

    /// Records successful Java bootstrap instantiation before the Host's `onLoad` callback.
    ///
    /// @param providerId canonical Provider plugin ID
    public synchronized void bootstrapLoaded(String providerId) {
        transitionFrom(providerId, RuntimeProviderState.RESOLVED, RuntimeProviderState.BOOTSTRAP_LOADED);
    }

    /// Registers one exact Provider implementation on behalf of its owning Host plugin.
    ///
    /// @param ownerPluginId canonical Host plugin ID
    /// @param provider Provider implementation supplied by the Host
    /// @return Host-owned idempotent registration handle
    public synchronized RuntimeProviderRegistration register(String ownerPluginId, RuntimeProvider provider) {
        requireCanonicalId(ownerPluginId);
        String providerId = provider.descriptor().providerId();
        if (!ownerPluginId.equals(providerId)) {
            throw new IllegalArgumentException("Runtime Provider registration owner does not match descriptor: "
                    + ownerPluginId + " != " + providerId);
        }
        requireState(providerId, RuntimeProviderState.BOOTSTRAP_LOADED);
        if (registrations.containsKey(providerId)) {
            throw new IllegalStateException("Runtime Provider Host already owns a registration: " + providerId);
        }
        registry.register(provider);
        RuntimeProviderRegistration registration = new RuntimeProviderRegistration(this, ownerPluginId, provider);
        registrations.put(providerId, registration);
        transition(providerId, RuntimeProviderState.REGISTERED);
        return registration;
    }

    /// Negotiates, initializes, health-checks, and publishes one registration as ready.
    ///
    /// @param registration exact active registration
    /// @throws IOException if initialization or health negotiation fails
    public void activate(RuntimeProviderRegistration registration) throws IOException {
        synchronized (registration.lifecycleLock()) {
            activateLocked(registration);
        }
    }

    /// Activates one registration while holding its Provider-scoped lifecycle monitor.
    ///
    /// @param registration exact active registration
    /// @throws IOException if initialization or health negotiation fails
    private void activateLocked(RuntimeProviderRegistration registration) throws IOException {
        String providerId = registration.provider().descriptor().providerId();
        synchronized (this) {
            requireRegistration(registration);
            transitionFrom(providerId, RuntimeProviderState.REGISTERED, RuntimeProviderState.NEGOTIATED);
        }
        try {
            registration.provider().initialize();
            synchronized (this) {
                transitionFrom(providerId, RuntimeProviderState.NEGOTIATED, RuntimeProviderState.INITIALIZED);
            }
            if (!registration.provider().healthCheck()) {
                throw new IOException("Runtime Provider health check failed: " + providerId);
            }
            synchronized (this) {
                transitionFrom(providerId, RuntimeProviderState.INITIALIZED, RuntimeProviderState.HEALTHY);
                transitionFrom(providerId, RuntimeProviderState.HEALTHY, RuntimeProviderState.READY);
                enabledHosts.add(providerId);
            }
        } catch (IOException | RuntimeException | Error exception) {
            rollbackFailedRegistration(registration, exception);
            if (exception instanceof IOException ioException) {
                throw ioException;
            }
            throw new IOException("Runtime Provider activation failed: " + providerId, exception);
        }
    }

    /// Activates the single registration created by one Host's completed `onLoad` callback.
    ///
    /// @param ownerPluginId canonical Host plugin ID
    /// @throws IOException if the Host did not register or Provider activation fails
    public void activateOwnedRegistration(String ownerPluginId) throws IOException {
        RuntimeProviderRegistration registration;
        synchronized (this) {
            registration = Optional.ofNullable(registrations.get(ownerPluginId))
                    .orElseThrow(() -> new IOException(
                            "Runtime Provider Host did not register an implementation: " + ownerPluginId));
        }
        activate(registration);
    }

    /// Records a Host bootstrap failure which occurred before a registration could activate.
    ///
    /// @param providerId canonical Host plugin ID
    public synchronized void fail(String providerId) {
        requireCanonicalId(providerId);
        @Nullable RuntimeProviderState state = states.get(providerId);
        if (state != null && state != RuntimeProviderState.STOPPED && state != RuntimeProviderState.FAILED) {
            enabledHosts.remove(providerId);
            transition(providerId, RuntimeProviderState.FAILED);
        }
    }

    /// Allows dependent callbacks after the owning Host container enables successfully.
    ///
    /// @param providerId canonical Host plugin ID
    public synchronized void hostEnabled(String providerId) {
        requireState(providerId, RuntimeProviderState.READY);
        enabledHosts.add(providerId);
    }

    /// Blocks dependent callbacks while the owning Host container is disabled.
    ///
    /// @param providerId canonical Host plugin ID
    public synchronized void hostDisabled(String providerId) {
        requireCanonicalId(providerId);
        enabledHosts.remove(providerId);
    }

    /// Loads one bound payload only after its selected Provider reaches `READY`.
    ///
    /// @param dependentPluginId canonical dependent plugin ID
    /// @param context exact immutable payload context
    /// @return opaque Provider-owned payload handle
    /// @throws IOException if the binding, state, Provider callback, or returned handle is invalid
    public RuntimePayloadHandle loadPayload(
            String dependentPluginId,
            RuntimePayloadContext context
    ) throws IOException {
        RuntimeProviderRegistration registration;
        synchronized (this) {
            RuntimeProviderBinding binding = requirePayloadBinding(dependentPluginId, context);
            registration = requireProviderRegistration(binding.providerId());
        }
        synchronized (registration.lifecycleLock()) {
            return loadPayloadLocked(registration, dependentPluginId, context);
        }
    }

    /// Loads and publishes one payload while holding its Provider-scoped lifecycle monitor.
    ///
    /// @param registration selected Provider registration
    /// @param dependentPluginId canonical dependent plugin ID
    /// @param context exact immutable payload context
    /// @return opaque Provider-owned payload handle
    /// @throws IOException if loading or publication validation fails
    private RuntimePayloadHandle loadPayloadLocked(
            RuntimeProviderRegistration registration,
            String dependentPluginId,
            RuntimePayloadContext context
    ) throws IOException {
        RuntimeProviderBinding binding;
        synchronized (this) {
            binding = requirePayloadBinding(dependentPluginId, context);
            requireRegistration(registration);
            if (!binding.providerId().equals(registration.ownerPluginId())) {
                throw new IOException("Runtime Provider binding changed before payload loading: " + dependentPluginId);
            }
        }
        RuntimeProvider provider = registration.provider();
        RuntimePayloadHandle handle = provider.loadPayload(context);
        try {
            synchronized (this) {
                requireReady(binding.providerId());
                requireRegistration(registration);
                if (!dependentPluginId.equals(handle.ownerPluginId())
                        || !binding.providerId().equals(handle.providerId())) {
                    throw new IOException("Runtime Provider returned a payload handle outside its binding: "
                            + dependentPluginId);
                }
                if (payloads.putIfAbsent(handle, new PayloadRecord(registration)) != null) {
                    throw new IOException("Runtime Provider returned a duplicate payload handle: "
                            + handle.payloadId());
                }
            }
        } catch (IOException | RuntimeException | Error exception) {
            try {
                provider.unloadPayload(handle);
            } catch (IOException | RuntimeException | Error cleanupFailure) {
                exception.addSuppressed(cleanupFailure);
            }
            throw exception;
        }
        return handle;
    }

    /// Enables one loaded payload through the Provider that issued its opaque handle.
    ///
    /// @param handle exact loaded payload handle
    /// @throws IOException if the handle is unknown, Provider is not ready, or enablement fails
    public void enablePayload(RuntimePayloadHandle handle) throws IOException {
        PayloadRecord initial;
        synchronized (this) {
            initial = requirePayload(handle);
        }
        synchronized (initial.registration.lifecycleLock()) {
            PayloadRecord record;
            synchronized (this) {
                record = requirePayloadForRegistration(handle, initial.registration);
                requireReady(handle.providerId());
                if (record.enabled) {
                    return;
                }
            }
            record.registration.provider().enablePayload(handle);
            synchronized (this) {
                PayloadRecord current = requirePayloadForRegistration(handle, initial.registration);
                current.enabled = true;
                current.acceptingCallbacks = true;
            }
        }
    }

    /// Disables one enabled payload while retaining Provider-owned loaded resources.
    ///
    /// @param handle exact loaded payload handle
    /// @throws IOException if the handle is unknown or disablement fails
    public void disablePayload(RuntimePayloadHandle handle) throws IOException {
        PayloadRecord initial;
        synchronized (this) {
            initial = requirePayload(handle);
            initial.acceptingCallbacks = false;
        }
        synchronized (initial.registration.lifecycleLock()) {
            PayloadRecord record;
            synchronized (this) {
                record = requirePayloadForRegistration(handle, initial.registration);
                if (!record.enabled) {
                    return;
                }
            }
            record.registration.provider().disablePayload(handle);
            synchronized (this) {
                requirePayloadForRegistration(handle, initial.registration).enabled = false;
            }
        }
    }

    /// Creates one launcher-side Hook transport bound to the exact current payload record.
    ///
    /// The returned invoker never captures a bare Provider. Every call re-enters Supervisor ownership and fails
    /// closed if the handle was unloaded, reissued, rebound, disabled, or moved to another registration generation.
    ///
    /// @param dependentPluginId canonical external payload owner
    /// @return exact-record supervised Hook transport
    /// @throws IOException if no payload is currently loaded for the owner
    public RuntimeHookEndpoint.ProviderInvoker hookInvoker(String dependentPluginId) throws IOException {
        RuntimePayloadHandle handle;
        PayloadRecord record;
        synchronized (this) {
            handle = payloads.keySet().stream()
                    .filter(candidate -> candidate.ownerPluginId().equals(dependentPluginId))
                    .findFirst()
                    .orElseThrow(() -> new IOException(
                            "No loaded runtime payload for Hook owner: " + dependentPluginId));
            record = requirePayload(handle);
        }
        PayloadRecord exactRecord = record;
        RuntimePayloadHandle exactHandle = handle;
        return (ownerPluginId, token, event, timeout) -> invokeHook(
                exactHandle,
                exactRecord,
                ownerPluginId,
                token,
                event,
                timeout
        );
    }

    /// Retains one Stage-1 Patch endpoint on the exact current payload record.
    ///
    /// @param handle exact loaded payload handle
    /// @param artifactIdentity exact payload package identity
    /// @param executionMode payload execution boundary
    /// @param permissionAuthority launcher-owned token verifier
    /// @param capabilityTokenSupplier current payload-session token source
    /// @param declarations authoritative manifest Patch declarations
    /// @return retained fail-closed Patch endpoint
    /// @throws IOException if the handle, identity, binding, registration, or Provider readiness is invalid
    public RuntimePatchEndpoint retainPatchEndpoint(
            RuntimePayloadHandle handle,
            PluginArtifactIdentity artifactIdentity,
            PluginExecutionMode executionMode,
            PluginPermissionAuthority permissionAuthority,
            Supplier<PluginCapabilityToken> capabilityTokenSupplier,
            Collection<PluginPatchDeclaration> declarations
    ) throws IOException {
        PayloadRecord record;
        synchronized (this) {
            record = requirePayload(handle);
        }
        synchronized (record.registration.lifecycleLock()) {
            synchronized (this) {
                requireExactPayloadRecord(handle, record);
                requireRegistration(record.registration);
                requireReady(handle.providerId());
                if (!handle.ownerPluginId().equals(artifactIdentity.getPluginId())) {
                    throw new IOException("Runtime Patch identity does not match payload handle: "
                            + artifactIdentity.getPluginId());
                }
                RuntimeProviderBinding binding = registry.bindingFor(handle.ownerPluginId())
                        .orElseThrow(() -> new IOException(
                                "Plugin has no runtime Provider binding: " + handle.ownerPluginId()));
                if (!binding.providerId().equals(handle.providerId())) {
                    throw new IOException("Runtime Provider binding changed before Patch retention: "
                            + handle.ownerPluginId());
                }
                if (record.patchEndpoint != null) {
                    throw new IllegalStateException("Runtime Patch endpoint is already retained: "
                            + handle.ownerPluginId());
                }
                PayloadRecord exactRecord = record;
                RuntimePatchEndpoint endpoint = new RuntimePatchEndpoint(
                        artifactIdentity,
                        executionMode,
                        permissionAuthority,
                        capabilityTokenSupplier,
                        declarations,
                        () -> requireActivePatchRecord(handle, exactRecord)
                );
                record.patchEndpoint = endpoint;
                return endpoint;
            }
        }
    }

    /// Returns the retained Patch endpoint for one currently loaded payload.
    ///
    /// @param dependentPluginId canonical external payload owner
    /// @return retained endpoint, or empty when the payload is absent or declares no Patches
    public synchronized Optional<RuntimePatchEndpoint> patchEndpoint(String dependentPluginId) {
        requireCanonicalId(dependentPluginId);
        for (Map.Entry<RuntimePayloadHandle, PayloadRecord> entry : payloads.entrySet()) {
            if (entry.getKey().ownerPluginId().equals(dependentPluginId)) {
                return Optional.ofNullable(entry.getValue().patchEndpoint);
            }
        }
        return Optional.empty();
    }

    /// Invokes one Provider Hook under the exact registration lifecycle monitor and payload generation.
    ///
    /// @param handle exact captured payload handle
    /// @param expectedRecord exact captured payload record
    /// @param ownerPluginId expected payload owner
    /// @param token verified short-lived payload token
    /// @param event immutable Hook event
    /// @param timeout positive dispatcher deadline
    /// @return Provider Hook result, or `null` for malformed Provider output
    /// @throws Exception if lifecycle validation, Provider transport, or callback fails
    private @Nullable PluginHookResult invokeHook(
            RuntimePayloadHandle handle,
            PayloadRecord expectedRecord,
            String ownerPluginId,
            PluginCapabilityToken token,
            PluginHookEvent event,
            Duration timeout
    ) throws Exception {
        if (!handle.ownerPluginId().equals(ownerPluginId)) {
            throw new IOException("Runtime Hook owner does not match payload handle: " + ownerPluginId);
        }
        synchronized (expectedRecord.registration.lifecycleLock()) {
            RuntimeProvider provider;
            synchronized (this) {
                requireExactPayloadRecord(handle, expectedRecord);
                requireRegistration(expectedRecord.registration);
                requireReady(handle.providerId());
                RuntimeProviderBinding binding = registry.bindingFor(ownerPluginId)
                        .orElseThrow(() -> new IOException(
                                "Plugin has no runtime Provider binding: " + ownerPluginId));
                if (!binding.providerId().equals(handle.providerId())) {
                    throw new IOException("Runtime Provider binding changed before Hook callback: " + ownerPluginId);
                }
                if (!expectedRecord.enabled || !expectedRecord.acceptingCallbacks) {
                    throw new IOException("Runtime payload is not enabled for Hook callbacks: " + ownerPluginId);
                }
                provider = expectedRecord.registration.provider();
            }
            if (!(provider instanceof RuntimeProvider.HookInvoker hookProvider)) {
                throw new PluginHookDispatchException(
                        event.point(),
                        ownerPluginId,
                        PluginHookDispatchException.Category.MISSING_ENDPOINT
                );
            }
            return hookProvider.invokeHook(handle, token, event, timeout);
        }
    }

    /// Unloads one payload and removes its dependent binding.
    ///
    /// @param handle exact loaded payload handle
    /// @throws IOException if disablement or unloading fails
    public void unloadPayload(RuntimePayloadHandle handle) throws IOException {
        PayloadRecord initial;
        synchronized (this) {
            initial = requirePayload(handle);
        }
        synchronized (initial.registration.lifecycleLock()) {
            synchronized (this) {
                requirePayloadForRegistration(handle, initial.registration);
            }
            disablePayload(handle);
            PayloadRecord record;
            synchronized (this) {
                record = requirePayloadForRegistration(handle, initial.registration);
            }
            record.registration.provider().unloadPayload(handle);
            synchronized (this) {
                payloads.remove(handle);
                registry.unbind(handle.ownerPluginId());
            }
        }
    }

    /// Closes every registration owned by one unloading Host container.
    ///
    /// @param ownerPluginId canonical Host plugin ID
    /// @throws IOException if Provider or payload cleanup fails
    public void closeOwnedRegistrations(String ownerPluginId) throws IOException {
        @Nullable RuntimeProviderRegistration registration;
        synchronized (this) {
            registration = registrations.get(ownerPluginId);
        }
        if (registration != null) {
            registration.close();
        }
    }

    /// Returns the current state for one Provider Host.
    ///
    /// @param providerId canonical Provider plugin ID
    /// @return current state, or empty before discovery
    public synchronized Optional<RuntimeProviderState> state(String providerId) {
        requireCanonicalId(providerId);
        return Optional.ofNullable(states.get(providerId));
    }

    /// Returns an immutable complete state history for one Provider Host.
    ///
    /// @param providerId canonical Provider plugin ID
    /// @return immutable state history
    public synchronized @Unmodifiable List<RuntimeProviderState> history(String providerId) {
        requireCanonicalId(providerId);
        @Nullable List<RuntimeProviderState> history = histories.get(providerId);
        return history == null ? List.of() : List.copyOf(history);
    }

    /// Stops every dependent payload in reverse load order and unregisters one Provider.
    ///
    /// @param registration registration selected by its idempotent handle
    /// @throws IOException if Provider cleanup fails
    void closeRegistration(RuntimeProviderRegistration registration) throws IOException {
        String providerId = registration.provider().descriptor().providerId();
        synchronized (this) {
            @Nullable RuntimeProviderRegistration current = registrations.get(providerId);
            if (current != registration) {
                return;
            }
            RuntimeProviderState state = requireKnownState(providerId);
            if (state == RuntimeProviderState.FAILED || state == RuntimeProviderState.STOPPED) {
                registrations.remove(providerId);
                return;
            }
            if (state != RuntimeProviderState.STOPPING) {
                transition(providerId, RuntimeProviderState.STOPPING);
            }
            enabledHosts.remove(providerId);
        }

        @Nullable IOException failure = null;
        for (RuntimePayloadHandle handle : reversePayloadsFor(providerId)) {
            try {
                unloadPayload(handle);
            } catch (IOException exception) {
                failure = append(failure, exception);
            }
        }
        if (failure != null) {
            throw failure;
        }
        if (!registration.isProviderClosed()) {
            registration.provider().close();
            registration.markProviderClosed();
        }
        synchronized (this) {
            try {
                registry.unregister(providerId);
            } catch (RuntimeException exception) {
                throw new IOException("Failed to unregister runtime Provider: " + providerId, exception);
            }
            registrations.remove(providerId);
            enabledHosts.remove(providerId);
            transition(providerId, RuntimeProviderState.STOPPED);
        }
    }

    /// Rolls back a Provider which failed between registration and readiness.
    ///
    /// @param registration failed registration
    /// @param originalFailure activation failure receiving cleanup details
    private void rollbackFailedRegistration(
            RuntimeProviderRegistration registration,
            Throwable originalFailure
    ) {
        String providerId = registration.provider().descriptor().providerId();
        try {
            registration.provider().close();
        } catch (IOException | RuntimeException | Error cleanupFailure) {
            originalFailure.addSuppressed(cleanupFailure);
        }
        synchronized (this) {
            registrations.remove(providerId);
            enabledHosts.remove(providerId);
            try {
                registry.unregister(providerId);
            } catch (RuntimeException cleanupFailure) {
                originalFailure.addSuppressed(cleanupFailure);
            }
            transition(providerId, RuntimeProviderState.FAILED);
            registration.markClosed();
        }
    }

    /// Requires one Provider to be ready before any dependent callback begins.
    ///
    /// @param providerId bound Provider plugin ID
    /// @throws IOException if the Provider is absent or not ready
    private synchronized void requireReady(String providerId) throws IOException {
        @Nullable RuntimeProviderState state = states.get(providerId);
        if (state != RuntimeProviderState.READY || !enabledHosts.contains(providerId)) {
            throw new IOException("Runtime Provider is not ready: " + providerId + " (" + state + ")");
        }
    }

    /// Returns loaded handles belonging to one Provider in reverse insertion order.
    ///
    /// @param providerId Provider plugin ID
    /// @return immutable reverse load order
    private synchronized @Unmodifiable List<RuntimePayloadHandle> reversePayloadsFor(String providerId) {
        List<RuntimePayloadHandle> handles = payloads.keySet().stream()
                .filter(handle -> providerId.equals(handle.providerId()))
                .toList();
        List<RuntimePayloadHandle> reversed = new ArrayList<>(handles);
        java.util.Collections.reverse(reversed);
        return List.copyOf(reversed);
    }

    /// Returns an active payload record for one exact opaque handle.
    ///
    /// @param handle Provider-issued handle
    /// @return active payload record
    /// @throws IOException if the handle is unknown
    private synchronized PayloadRecord requirePayload(RuntimePayloadHandle handle) throws IOException {
        @Nullable PayloadRecord record = payloads.get(handle);
        if (record == null) {
            throw new IOException("Unknown runtime payload handle: " + handle.payloadId());
        }
        return record;
    }

    /// Returns one payload only when it still belongs to the registration captured before lifecycle locking.
    ///
    /// @param handle Provider-issued handle
    /// @param expectedRegistration registration captured before acquiring its lifecycle monitor
    /// @return active payload record owned by the expected registration
    /// @throws IOException if the handle is no longer loaded
    /// @throws IllegalStateException if a replacement registration reissued an equal handle
    private synchronized PayloadRecord requirePayloadForRegistration(
            RuntimePayloadHandle handle,
            RuntimeProviderRegistration expectedRegistration
    ) throws IOException {
        PayloadRecord record = requirePayload(handle);
        if (record.registration != expectedRegistration) {
            throw new IllegalStateException("Rejected stale runtime payload handle reissued by a replacement Provider: "
                    + handle.payloadId());
        }
        return record;
    }

    /// Requires that an exact payload record still owns its captured handle.
    ///
    /// Record identity detects stale endpoints even when one Provider reissues an equal opaque handle.
    ///
    /// @param handle captured payload handle
    /// @param expectedRecord captured payload record
    /// @return active exact record
    /// @throws IOException if the payload was unloaded
    /// @throws IllegalStateException if the handle was reissued for another payload generation
    private synchronized PayloadRecord requireExactPayloadRecord(
            RuntimePayloadHandle handle,
            PayloadRecord expectedRecord
    ) throws IOException {
        PayloadRecord current = requirePayload(handle);
        if (current != expectedRecord) {
            throw new IllegalStateException(
                    "Rejected stale runtime payload endpoint for reissued handle: " + handle.payloadId());
        }
        return current;
    }

    /// Requires one exact retained Patch endpoint to remain bound to an enabled payload generation.
    ///
    /// @param handle captured payload handle
    /// @param expectedRecord captured payload record
    private void requireActivePatchRecord(RuntimePayloadHandle handle, PayloadRecord expectedRecord) {
        synchronized (expectedRecord.registration.lifecycleLock()) {
            try {
                synchronized (this) {
                    requireExactPayloadRecord(handle, expectedRecord);
                    requireRegistration(expectedRecord.registration);
                    requireReady(handle.providerId());
                    RuntimeProviderBinding binding = registry.bindingFor(handle.ownerPluginId())
                            .orElseThrow(() -> new IOException(
                                    "Plugin has no runtime Provider binding: " + handle.ownerPluginId()));
                    if (!binding.providerId().equals(handle.providerId())
                            || !expectedRecord.enabled
                            || !expectedRecord.acceptingCallbacks) {
                        throw new IOException("Runtime Patch payload is not active: " + handle.ownerPluginId());
                    }
                }
            } catch (IOException exception) {
                throw new IllegalStateException(
                        "Runtime Patch endpoint is not active: " + handle.ownerPluginId(), exception);
            }
        }
    }

    /// Requires that one exact registration still owns the current Provider ID.
    ///
    /// @param registration registration to validate
    private synchronized void requireRegistration(RuntimeProviderRegistration registration) {
        String providerId = registration.provider().descriptor().providerId();
        if (registration.isClosed() || registrations.get(providerId) != registration) {
            throw new IllegalStateException("Runtime Provider registration is not active: " + providerId);
        }
    }

    /// Resolves and validates the current binding and readiness for one exact payload context.
    ///
    /// @param dependentPluginId canonical dependent plugin ID
    /// @param context exact immutable payload context
    /// @return current ready binding
    /// @throws IOException if ownership, binding, or readiness is invalid
    private synchronized RuntimeProviderBinding requirePayloadBinding(
            String dependentPluginId,
            RuntimePayloadContext context
    ) throws IOException {
        requireCanonicalId(dependentPluginId);
        if (!dependentPluginId.equals(context.artifactIdentity().getPluginId())) {
            throw new IOException("Runtime payload context owner does not match dependent binding: "
                    + dependentPluginId);
        }
        RuntimeProviderBinding binding = registry.bindingFor(dependentPluginId)
                .orElseThrow(() -> new IOException("Plugin has no runtime Provider binding: "
                        + dependentPluginId));
        requireReady(binding.providerId());
        return binding;
    }

    /// Returns the active registration for one bound Provider ID.
    ///
    /// @param providerId canonical Provider plugin ID
    /// @return active registration
    /// @throws IOException if the Provider is not registered
    private synchronized RuntimeProviderRegistration requireProviderRegistration(String providerId)
            throws IOException {
        @Nullable RuntimeProviderRegistration registration = registrations.get(providerId);
        if (registration == null || registration.isClosed()) {
            throw new IOException("Bound runtime Provider is not registered: " + providerId);
        }
        return registration;
    }

    /// Advances one exact expected state to its successor.
    ///
    /// @param providerId Provider plugin ID
    /// @param expected required current state
    /// @param next successor state
    private synchronized void transitionFrom(
            String providerId,
            RuntimeProviderState expected,
            RuntimeProviderState next
    ) {
        requireState(providerId, expected);
        transition(providerId, next);
    }

    /// Requires one exact current Provider state.
    ///
    /// @param providerId Provider plugin ID
    /// @param expected required state
    private synchronized void requireState(String providerId, RuntimeProviderState expected) {
        RuntimeProviderState current = requireKnownState(providerId);
        if (current != expected) {
            throw invalidTransition(providerId, current, expected);
        }
    }

    /// Returns one known Provider state.
    ///
    /// @param providerId Provider plugin ID
    /// @return current state
    private synchronized RuntimeProviderState requireKnownState(String providerId) {
        @Nullable RuntimeProviderState current = states.get(providerId);
        if (current == null) {
            throw new IllegalStateException("Runtime Provider has not been discovered: " + providerId);
        }
        return current;
    }

    /// Publishes one state and appends it to the diagnostic history.
    ///
    /// @param providerId Provider plugin ID
    /// @param state new state
    private synchronized void transition(String providerId, RuntimeProviderState state) {
        states.put(providerId, state);
        histories.computeIfAbsent(providerId, ignored -> new ArrayList<>()).add(state);
    }

    /// Requires a canonical executable plugin ID.
    ///
    /// @param pluginId plugin ID
    private static void requireCanonicalId(String pluginId) {
        if (!PluginManifest.isCanonicalExecutableId(pluginId)) {
            throw new IllegalArgumentException("Plugin ID must be canonical: " + pluginId);
        }
    }

    /// Creates a deterministic invalid-transition failure.
    ///
    /// @param providerId Provider plugin ID
    /// @param current current state
    /// @param requested requested state
    /// @return transition failure
    private static IllegalStateException invalidTransition(
            String providerId,
            RuntimeProviderState current,
            RuntimeProviderState requested
    ) {
        return new IllegalStateException("Invalid runtime Provider transition for " + providerId + ": "
                + current + " -> " + requested);
    }

    /// Aggregates cleanup failures without dropping the first exception.
    ///
    /// @param current current aggregate, or `null`
    /// @param next next cleanup failure
    /// @return aggregate rooted at the first failure
    private static IOException append(@Nullable IOException current, IOException next) {
        if (current == null) {
            return next;
        }
        current.addSuppressed(next);
        return current;
    }

    /// Mutable Supervisor-owned payload lifecycle record.
    @NotNullByDefault
    private static final class PayloadRecord {
        /// Registration which issued the handle and owns its Provider-scoped lifecycle monitor.
        private final RuntimeProviderRegistration registration;

        /// Whether Provider enablement completed.
        private boolean enabled;

        /// Whether new callbacks may enter this enabled payload generation.
        private boolean acceptingCallbacks;

        /// Stage-1 fail-closed Patch endpoint retained for this exact payload record, or `null` when undeclared.
        private @Nullable RuntimePatchEndpoint patchEndpoint;

        /// Creates one loaded disabled payload record.
        ///
        /// @param registration issuing registration
        private PayloadRecord(RuntimeProviderRegistration registration) {
            this.registration = registration;
        }
    }
}
