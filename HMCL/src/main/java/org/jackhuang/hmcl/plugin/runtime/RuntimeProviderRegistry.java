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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/// Process-wide registry of provider implementations and dependent-scoped runtime bindings.
///
/// Providers are keyed by provider plugin ID. Runtime lookup uses immutable deterministic candidate snapshots, while
/// bindings retain the exact selected provider until explicitly released.
@NotNullByDefault
public final class RuntimeProviderRegistry {
    /// Registry shared by production plugin compatibility consumers.
    private static final RuntimeProviderRegistry PROCESS_WIDE = new RuntimeProviderRegistry();

    /// Maximum provider-registration retries allowed after the initial binding attempt.
    private static final int MAX_BINDING_RETRIES = 8;

    /// Stateless deterministic selector shared by registry operations.
    private final RuntimeProviderSelector selector = new RuntimeProviderSelector();

    /// Provider implementations keyed by canonical provider plugin ID.
    private final Map<String, RuntimeProvider> providersById = new LinkedHashMap<>();

    /// Immutable descriptors captured once when each provider is registered.
    private final Map<String, RuntimeProviderDescriptor> descriptorsById = new LinkedHashMap<>();

    /// Monotonic generation changed after every successful provider registration mutation.
    private long registrationGeneration;

    /// Immutable candidate snapshots keyed by canonical runtime identifier.
    private volatile @Unmodifiable Map<String, @Unmodifiable List<RuntimeProviderDescriptor>> candidatesByRuntime =
            Map.of();

    /// Active bindings keyed by canonical dependent plugin ID.
    private final Map<String, RuntimeProviderBinding> bindingsByDependent = new LinkedHashMap<>();

    /// Creates a registry containing the reserved built-in Java provider.
    public RuntimeProviderRegistry() {
        RuntimeProvider javaProvider = new JavaRuntimeProvider();
        providersById.put(javaProvider.descriptor().providerId(), javaProvider);
        descriptorsById.put(javaProvider.descriptor().providerId(), javaProvider.descriptor());
        rebuildCandidateSnapshots();
    }

    /// Returns the process-wide registry used by production plugin services.
    public static RuntimeProviderRegistry processWide() {
        return PROCESS_WIDE;
    }

    /// Registers one provider plugin without replacing an existing provider ID or built-in Java capability.
    ///
    /// @param provider provider implementation
    /// @throws IllegalStateException if the provider ID is already registered or it attempts to supply Java
    public synchronized void register(RuntimeProvider provider) {
        RuntimeProviderDescriptor descriptor = provider.descriptor();
        if (descriptor.reserved()) {
            throw new IllegalStateException("Reserved runtime provider registrations are launcher-owned: "
                    + descriptor.providerId());
        }
        if (descriptor.capability(PluginRuntimeTypes.JAVA).isPresent()) {
            throw new IllegalStateException("The built-in Java runtime provider cannot be replaced: "
                    + descriptor.providerId());
        }
        @Nullable RuntimeProvider existing = providersById.putIfAbsent(descriptor.providerId(), provider);
        if (existing != null) {
            throw new IllegalStateException("Plugin runtime provider is already registered: "
                    + descriptor.providerId());
        }
        descriptorsById.put(descriptor.providerId(), descriptor);
        rebuildCandidateSnapshots();
        registrationGeneration++;
    }

    /// Removes one provider by provider plugin ID after confirming no dependent remains bound.
    ///
    /// The reserved Java registration ignores removal requests for compatibility with existing callers.
    ///
    /// @param providerId provider plugin ID
    /// @throws IllegalStateException when one or more dependents remain bound
    public synchronized void unregister(String providerId) {
        String canonicalProviderId = canonicalProviderId(providerId);
        @Nullable RuntimeProvider provider = providersById.get(canonicalProviderId);
        @Nullable RuntimeProviderDescriptor descriptor = descriptorsById.get(canonicalProviderId);
        if (provider == null || descriptor == null || descriptor.reserved()) {
            return;
        }
        boolean bound = bindingsByDependent.values().stream()
                .anyMatch(binding -> canonicalProviderId.equals(binding.providerId()));
        if (bound) {
            throw new IllegalStateException("Runtime provider remains bound to dependent plugins: "
                    + canonicalProviderId);
        }
        providersById.remove(canonicalProviderId);
        descriptorsById.remove(canonicalProviderId);
        rebuildCandidateSnapshots();
        registrationGeneration++;
    }

    /// Binds one dependent plugin to its pinned or highest-ranked compatible registered provider.
    ///
    /// @param dependentPluginId canonical dependent plugin ID
    /// @param requirement runtime capability requirement
    /// @return immutable selected binding
    /// @throws IllegalStateException if the dependent is already bound, no compatible provider exists, or provider
    /// registration changes repeatedly during live compatibility checks
    public RuntimeProviderBinding bind(
            String dependentPluginId,
            RuntimeRequirement requirement) {
        if (!PluginManifest.isCanonicalExecutableId(dependentPluginId)) {
            throw new IllegalArgumentException("Dependent plugin ID must be canonical: " + dependentPluginId);
        }
        for (int attempt = 0; attempt <= MAX_BINDING_RETRIES; attempt++) {
            BindingSnapshot snapshot = snapshotBindingCandidates(dependentPluginId, requirement);
            if (snapshot.candidates().isEmpty()) {
                throw noCompatibleProvider(requirement);
            }

            boolean retry = false;
            for (BindingCandidate candidate : snapshot.candidates()) {
                boolean supportsAbi = candidate.provider()
                        .supportsAbi(requirement.getRuntime(), requirement.getPluginAbi());
                synchronized (this) {
                    ensureUnbound(dependentPluginId);
                    if (!isCurrent(snapshot, candidate, requirement)) {
                        retry = true;
                        break;
                    }
                    if (!supportsAbi) {
                        if (requirement.getPinnedProviderId() != null) {
                            throw noCompatibleProvider(requirement);
                        }
                        continue;
                    }
                    RuntimeProviderBinding binding = new RuntimeProviderBinding(
                            dependentPluginId, candidate.descriptor().providerId(), requirement.getRuntime());
                    bindingsByDependent.put(dependentPluginId, binding);
                    return binding;
                }
            }
            if (!retry) {
                throw noCompatibleProvider(requirement);
            }
        }
        throw new IllegalStateException("Runtime provider registry changed repeatedly while binding "
                + dependentPluginId + " for " + requirement.getRuntime() + " after "
                + (MAX_BINDING_RETRIES + 1) + " attempts");
    }

    /// Removes and returns one dependent plugin binding.
    ///
    /// @param dependentPluginId dependent plugin ID
    /// @return removed binding, or empty when the dependent was unbound
    public synchronized Optional<RuntimeProviderBinding> unbind(String dependentPluginId) {
        return Optional.ofNullable(bindingsByDependent.remove(canonicalProviderId(dependentPluginId)));
    }

    /// Returns one dependent plugin's current provider binding.
    ///
    /// @param dependentPluginId dependent plugin ID
    /// @return immutable binding when present
    public synchronized Optional<RuntimeProviderBinding> bindingFor(String dependentPluginId) {
        return Optional.ofNullable(bindingsByDependent.get(canonicalProviderId(dependentPluginId)));
    }

    /// Restores one previously confirmed dependent binding after its exact Provider registers during startup.
    ///
    /// This path never reselects a Provider. Store planning already selected and persisted the exact Host, so startup
    /// must either restore that edge or fail closed.
    ///
    /// @param binding persisted dependent-to-Provider edge
    /// @param requirement complete runtime requirement of the dependent package
    /// @throws IllegalStateException if the exact Provider cannot satisfy every requirement or another binding exists
    public synchronized void restoreBinding(RuntimeProviderBinding binding, RuntimeRequirement requirement) {
        requireCompatibleBinding(binding, requirement);
        @Nullable RuntimeProviderBinding existing = bindingsByDependent.get(binding.dependentPluginId());
        if (existing != null) {
            if (existing.equals(binding)) {
                return;
            }
            throw new IllegalStateException("Plugin already has another runtime Provider binding: "
                    + binding.dependentPluginId());
        }
        bindingsByDependent.put(binding.dependentPluginId(), binding);
    }

    /// Requires an exact binding to satisfy the complete dependent runtime contract.
    ///
    /// @param binding exact dependent-to-Provider edge
    /// @param requirement complete dependent requirement
    /// @throws IllegalStateException if the edge or exact live Provider is incompatible
    private void requireCompatibleBinding(RuntimeProviderBinding binding, RuntimeRequirement requirement) {
        if (!binding.runtime().equals(requirement.getRuntime())) {
            throw incompatibleBinding(binding, "bound runtime differs from the package requirement");
        }
        @Nullable String pinnedProviderId = requirement.getPinnedProviderId();
        if (pinnedProviderId != null && !binding.providerId().equals(pinnedProviderId)) {
            throw incompatibleBinding(binding, "bound Provider differs from the package pin");
        }
        @Nullable RuntimeProvider provider = providersById.get(binding.providerId());
        @Nullable RuntimeProviderDescriptor descriptor = descriptorsById.get(binding.providerId());
        if (provider == null || descriptor == null) {
            throw incompatibleBinding(binding, "Provider is unavailable");
        }
        if (new RuntimeProviderSelector().select(requirement, List.of(descriptor)).isEmpty()
                || !provider.supportsAbi(requirement.getRuntime(), requirement.getPluginAbi())
                || providersById.get(binding.providerId()) != provider) {
            throw incompatibleBinding(binding, "Provider does not satisfy the complete runtime requirement");
        }
    }

    /// Creates a deterministic exact-binding rejection.
    ///
    /// @param binding rejected binding
    /// @param reason incompatibility reason
    /// @return binding rejection
    private static IllegalStateException incompatibleBinding(RuntimeProviderBinding binding, String reason) {
        return new IllegalStateException("Persisted runtime Provider binding is incompatible: "
                + binding.dependentPluginId() + " -> " + binding.providerId() + " (" + reason + ")");
    }

    /// Returns one registered provider by provider plugin ID.
    ///
    /// @param providerId provider plugin ID
    /// @return registered provider when present
    public synchronized Optional<RuntimeProvider> findById(String providerId) {
        return Optional.ofNullable(providersById.get(canonicalProviderId(providerId)));
    }

    /// Returns the highest-ranked registered provider advertising one runtime.
    ///
    /// This compatibility lookup does not apply an ABI requirement. New consumers should bind an explicit
    /// [RuntimeRequirement] instead.
    ///
    /// @param runtimeType runtime identifier
    /// @return highest-ranked provider advertising the runtime
    public synchronized Optional<RuntimeProvider> find(String runtimeType) {
        @Unmodifiable List<RuntimeProviderDescriptor> candidates = candidates(runtimeType);
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(providersById.get(candidates.get(0).providerId()));
    }

    /// Returns an immutable deterministic candidate snapshot for one runtime.
    ///
    /// @param runtimeType runtime identifier
    /// @return candidate descriptors ordered by availability, source, version, and provider ID
    public @Unmodifiable List<RuntimeProviderDescriptor> candidates(String runtimeType) {
        String runtime = PluginRuntimeTypes.requireValid(runtimeType);
        return candidatesByRuntime.getOrDefault(runtime, List.of());
    }

    /// Returns whether at least one provider currently advertises the runtime type.
    public boolean isAvailable(String runtimeType) {
        return !candidates(runtimeType).isEmpty();
    }

    /// Returns a snapshot description of every registered provider keyed by provider plugin ID.
    public synchronized @Unmodifiable Map<String, String> describeAll() {
        return providersById.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, entry -> entry.getValue().describe()));
    }

    /// Returns the number of registered provider implementations.
    public synchronized int size() {
        return providersById.size();
    }

    /// Captures statically compatible providers and their exact registered instances for one binding attempt.
    ///
    /// @param dependentPluginId dependent plugin ID
    /// @param requirement runtime requirement
    /// @return immutable binding-attempt snapshot
    private synchronized BindingSnapshot snapshotBindingCandidates(
            String dependentPluginId,
            RuntimeRequirement requirement) {
        ensureUnbound(dependentPluginId);
        @Unmodifiable List<RuntimeProviderDescriptor> compatibleDescriptors;
        if (requirement.getPinnedProviderId() != null) {
            compatibleDescriptors = selector.select(requirement, candidates(requirement.getRuntime()))
                    .map(List::of)
                    .orElseGet(List::of);
        } else {
            compatibleDescriptors = selector.ordered(candidates(requirement.getRuntime())).stream()
                    .filter(descriptor -> selector.isCompatible(descriptor, requirement))
                    .toList();
        }
        @Unmodifiable List<BindingCandidate> candidates = compatibleDescriptors.stream()
                .map(descriptor -> new BindingCandidate(
                        descriptor, Objects.requireNonNull(providersById.get(descriptor.providerId()),
                                "Registered runtime provider disappeared: " + descriptor.providerId())))
                .toList();
        return new BindingSnapshot(registrationGeneration, candidates);
    }

    /// Returns whether a callback result still describes the same registered and statically compatible candidate.
    ///
    /// @param snapshot binding-attempt snapshot
    /// @param candidate candidate whose callback completed
    /// @param requirement runtime requirement
    /// @return whether the candidate can be considered for binding publication
    private synchronized boolean isCurrent(
            BindingSnapshot snapshot,
            BindingCandidate candidate,
            RuntimeRequirement requirement) {
        String providerId = candidate.descriptor().providerId();
        return registrationGeneration == snapshot.registrationGeneration()
                && providersById.get(providerId) == candidate.provider()
                && descriptorsById.get(providerId) == candidate.descriptor()
                && selector.isCompatible(candidate.descriptor(), requirement);
    }

    /// Rejects a binding attempt when the dependent already owns a published binding.
    ///
    /// @param dependentPluginId dependent plugin ID
    /// @throws IllegalStateException if the dependent is already bound
    private synchronized void ensureUnbound(String dependentPluginId) {
        if (bindingsByDependent.containsKey(dependentPluginId)) {
            throw new IllegalStateException("Plugin already has a runtime provider binding: " + dependentPluginId);
        }
    }

    /// Creates the consistent failure used when no static and live-compatible provider remains.
    ///
    /// @param requirement unsatisfied runtime requirement
    /// @return binding failure
    private static IllegalStateException noCompatibleProvider(RuntimeRequirement requirement) {
        return new IllegalStateException(
                "No compatible runtime provider is registered for " + requirement.getRuntime());
    }

    /// Rebuilds immutable runtime candidate lists after a provider registration mutation.
    private void rebuildCandidateSnapshots() {
        Map<String, List<RuntimeProviderDescriptor>> mutable = new LinkedHashMap<>();
        for (RuntimeProviderDescriptor descriptor : descriptorsById.values()) {
            for (String runtime : descriptor.capabilities().keySet()) {
                mutable.computeIfAbsent(runtime, ignored -> new ArrayList<>()).add(descriptor);
            }
        }
        Map<String, List<RuntimeProviderDescriptor>> snapshots = new LinkedHashMap<>();
        mutable.forEach((runtime, descriptors) -> snapshots.put(runtime, selector.ordered(descriptors)));
        candidatesByRuntime = Map.copyOf(snapshots);
    }

    /// Canonicalizes a provider or dependent plugin ID for compatibility with legacy registry callers.
    ///
    /// @param providerId caller-supplied plugin ID
    /// @return canonical lower-case trimmed plugin ID
    private static String canonicalProviderId(String providerId) {
        String canonical = providerId.trim().toLowerCase(Locale.ROOT);
        if (!PluginManifest.isCanonicalExecutableId(canonical)) {
            throw new IllegalArgumentException("Invalid runtime provider ID: " + providerId);
        }
        return canonical;
    }

    /// Captures one immutable descriptor and the exact provider instance registered for it.
    ///
    /// @param descriptor registered immutable descriptor
    /// @param provider registered provider instance
    private record BindingCandidate(RuntimeProviderDescriptor descriptor, RuntimeProvider provider) {
    }

    /// Captures ordered candidates under one provider-registration generation.
    ///
    /// @param registrationGeneration provider-registration generation
    /// @param candidates immutable ordered binding candidates
    private record BindingSnapshot(
            long registrationGeneration,
            @Unmodifiable List<BindingCandidate> candidates) {
    }
}
