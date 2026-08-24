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

    /// Stateless deterministic selector shared by registry operations.
    private final RuntimeProviderSelector selector = new RuntimeProviderSelector();

    /// Provider implementations keyed by canonical provider plugin ID.
    private final Map<String, RuntimeProvider> providersById = new LinkedHashMap<>();

    /// Immutable descriptors captured once when each provider is registered.
    private final Map<String, RuntimeProviderDescriptor> descriptorsById = new LinkedHashMap<>();

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
    }

    /// Binds one dependent plugin to its pinned or highest-ranked compatible registered provider.
    ///
    /// @param dependentPluginId canonical dependent plugin ID
    /// @param requirement runtime capability requirement
    /// @return immutable selected binding
    /// @throws IllegalStateException if the dependent is already bound or no compatible provider exists
    public synchronized RuntimeProviderBinding bind(
            String dependentPluginId,
            RuntimeRequirement requirement) {
        if (!PluginManifest.isCanonicalExecutableId(dependentPluginId)) {
            throw new IllegalArgumentException("Dependent plugin ID must be canonical: " + dependentPluginId);
        }
        if (bindingsByDependent.containsKey(dependentPluginId)) {
            throw new IllegalStateException("Plugin already has a runtime provider binding: " + dependentPluginId);
        }
        RuntimeProviderDescriptor selected = selector.select(requirement, candidates(requirement.getRuntime()))
                .orElseThrow(() -> new IllegalStateException(
                        "No compatible runtime provider is registered for " + requirement.getRuntime()));
        RuntimeProviderBinding binding = new RuntimeProviderBinding(
                dependentPluginId, selected.providerId(), requirement.getRuntime());
        bindingsByDependent.put(dependentPluginId, binding);
        return binding;
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
}
