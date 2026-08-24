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

import org.jackhuang.hmcl.plugin.PluginManifest;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
            transition(providerId, RuntimeProviderState.FAILED);
        }
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
        RuntimeProviderBinding binding;
        RuntimeProvider provider;
        synchronized (this) {
            requireCanonicalId(dependentPluginId);
            if (!dependentPluginId.equals(context.artifactIdentity().getPluginId())) {
                throw new IOException("Runtime payload context owner does not match dependent binding: "
                        + dependentPluginId);
            }
            binding = registry.bindingFor(dependentPluginId)
                    .orElseThrow(() -> new IOException("Plugin has no runtime Provider binding: "
                            + dependentPluginId));
            requireReady(binding.providerId());
            provider = registry.findById(binding.providerId())
                    .orElseThrow(() -> new IOException("Bound runtime Provider is not registered: "
                            + binding.providerId()));
        }

        RuntimePayloadHandle handle = provider.loadPayload(context);
        synchronized (this) {
            requireReady(binding.providerId());
            if (!dependentPluginId.equals(handle.ownerPluginId())
                    || !binding.providerId().equals(handle.providerId())) {
                throw new IOException("Runtime Provider returned a payload handle outside its binding: "
                        + dependentPluginId);
            }
            if (payloads.putIfAbsent(handle, new PayloadRecord(provider)) != null) {
                throw new IOException("Runtime Provider returned a duplicate payload handle: " + handle.payloadId());
            }
        }
        return handle;
    }

    /// Enables one loaded payload through the Provider that issued its opaque handle.
    ///
    /// @param handle exact loaded payload handle
    /// @throws IOException if the handle is unknown, Provider is not ready, or enablement fails
    public void enablePayload(RuntimePayloadHandle handle) throws IOException {
        PayloadRecord record;
        synchronized (this) {
            record = requirePayload(handle);
            requireReady(handle.providerId());
            if (record.enabled) {
                return;
            }
        }
        record.provider.enablePayload(handle);
        synchronized (this) {
            requirePayload(handle).enabled = true;
        }
    }

    /// Disables one enabled payload while retaining Provider-owned loaded resources.
    ///
    /// @param handle exact loaded payload handle
    /// @throws IOException if the handle is unknown or disablement fails
    public void disablePayload(RuntimePayloadHandle handle) throws IOException {
        PayloadRecord record;
        synchronized (this) {
            record = requirePayload(handle);
            if (!record.enabled) {
                return;
            }
        }
        record.provider.disablePayload(handle);
        synchronized (this) {
            requirePayload(handle).enabled = false;
        }
    }

    /// Unloads one payload and removes its dependent binding.
    ///
    /// @param handle exact loaded payload handle
    /// @throws IOException if disablement or unloading fails
    public void unloadPayload(RuntimePayloadHandle handle) throws IOException {
        disablePayload(handle);
        PayloadRecord record;
        synchronized (this) {
            record = requirePayload(handle);
        }
        record.provider.unloadPayload(handle);
        synchronized (this) {
            payloads.remove(handle);
            registry.unbind(handle.ownerPluginId());
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
            transition(providerId, RuntimeProviderState.STOPPING);
        }

        @Nullable IOException failure = null;
        for (RuntimePayloadHandle handle : reversePayloadsFor(providerId)) {
            try {
                unloadPayload(handle);
            } catch (IOException exception) {
                failure = append(failure, exception);
            }
        }
        try {
            registration.provider().close();
        } catch (IOException exception) {
            failure = append(failure, exception);
        }
        synchronized (this) {
            registrations.remove(providerId);
            try {
                registry.unregister(providerId);
            } catch (RuntimeException exception) {
                failure = append(failure, new IOException("Failed to unregister runtime Provider: " + providerId,
                        exception));
            }
            transition(providerId, failure == null ? RuntimeProviderState.STOPPED : RuntimeProviderState.FAILED);
        }
        if (failure != null) {
            throw failure;
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
        if (state != RuntimeProviderState.READY) {
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

    /// Requires that one exact registration still owns the current Provider ID.
    ///
    /// @param registration registration to validate
    private synchronized void requireRegistration(RuntimeProviderRegistration registration) {
        String providerId = registration.provider().descriptor().providerId();
        if (registration.isClosed() || registrations.get(providerId) != registration) {
            throw new IllegalStateException("Runtime Provider registration is not active: " + providerId);
        }
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
        /// Provider implementation which issued the handle.
        private final RuntimeProvider provider;

        /// Whether Provider enablement completed.
        private boolean enabled;

        /// Creates one loaded disabled payload record.
        ///
        /// @param provider issuing Provider
        private PayloadRecord(RuntimeProvider provider) {
            this.provider = provider;
        }
    }
}
