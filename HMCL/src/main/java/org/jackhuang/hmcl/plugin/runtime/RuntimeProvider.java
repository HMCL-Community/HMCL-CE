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

import org.jackhuang.hmcl.plugin.PluginHookEvent;
import org.jackhuang.hmcl.plugin.PluginHookResult;
import org.jackhuang.hmcl.plugin.bridge.PluginCapabilityToken;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Set;

/// SPI implemented by every plugin runtime provider.
///
/// The launcher ships one in-process Java provider. Optional Provider plugins register this SPI after package
/// discovery; the plugin manager resolves Store dependencies, the runtime supervisor owns their lifecycle, and the
/// runtime loader delegates external payloads. Concrete external Runtime Hosts remain separately distributed plugins.
@NotNullByDefault
public interface RuntimeProvider extends AutoCloseable {
    /// Returns the immutable provider identity, ranking metadata, and advertised capabilities.
    ///
    /// Legacy Java implementations may keep overriding [runtimeType], [implementedPluginAbis], and [describe];
    /// this default maps that contract to one installed, enabled embedded-Bridge capability.
    default RuntimeProviderDescriptor descriptor() {
        String runtime = PluginRuntimeTypes.requireValid(runtimeType());
        return new RuntimeProviderDescriptor(
                runtime,
                "0.0.0",
                List.of(new RuntimeProviderDeclaration(
                        runtime,
                        implementedPluginAbis(),
                        1,
                        Set.of(PluginExecutionMode.EMBEDDED),
                        Set.of(RuntimeFeature.BRIDGE)
                )),
                true,
                true,
                Integer.MAX_VALUE,
                false
        );
    }

    /// Canonical runtime identifier this provider serves, such as java or dotnet.
    default String runtimeType() {
        RuntimeProviderDescriptor providerDescriptor = descriptor();
        if (providerDescriptor.capabilities().size() != 1) {
            throw new IllegalStateException("A multi-runtime provider has no singular runtime type: "
                    + providerDescriptor.providerId());
        }
        return providerDescriptor.capabilities().keySet().iterator().next();
    }

    /// ABI generations this provider can execute, for example [1, 2] for a current host.
    default @Unmodifiable Set<Integer> implementedPluginAbis() {
        return descriptor().capability(runtimeType()).orElseThrow().getAbis();
    }

    /// Returns whether the provider can execute a package that requires the given ABI generation.
    default boolean supportsAbi(int requiredAbi) {
        return implementedPluginAbis().contains(requiredAbi);
    }

    /// Returns whether the provider can execute one runtime at the required ABI generation.
    ///
    /// Single-runtime legacy implementations delegate to [supportsAbi] so their live availability checks remain
    /// observable. Multi-runtime providers resolve the ABI against the named descriptor capability.
    ///
    /// @param runtime canonical runtime identifier
    /// @param requiredAbi required plugin ABI generation
    /// @return whether the named runtime capability implements the ABI
    default boolean supportsAbi(String runtime, int requiredAbi) {
        RuntimeProviderDescriptor providerDescriptor = descriptor();
        if (providerDescriptor.capabilities().size() == 1) {
            return supportsAbi(requiredAbi);
        }
        return providerDescriptor.capability(runtime)
                .map(capability -> capability.getAbis().contains(requiredAbi))
                .orElse(false);
    }

    /// Human-readable provider description shown in plugin manager diagnostics.
    default String describe() {
        return "Plugin runtime provider " + descriptor().providerId();
    }

    /// Initializes provider-owned runtime resources before health negotiation.
    ///
    /// @throws IOException if provider initialization fails
    default void initialize() throws IOException {
    }

    /// Checks whether the initialized provider can accept payload work.
    ///
    /// @return whether the provider is healthy
    /// @throws IOException if health negotiation cannot complete
    default boolean healthCheck() throws IOException {
        return true;
    }

    /// Loads one exact payload and returns an opaque provider-owned handle.
    ///
    /// @param context immutable payload loading context
    /// @return provider-owned payload handle
    /// @throws IOException if loading fails
    default RuntimePayloadHandle loadPayload(RuntimePayloadContext context) throws IOException {
        throw new IOException("Runtime provider does not implement payload loading: " + descriptor().providerId());
    }

    /// Enables one previously loaded provider-owned payload.
    ///
    /// @param handle provider-owned payload handle
    /// @throws IOException if enablement fails
    default void enablePayload(RuntimePayloadHandle handle) throws IOException {
        throw new IOException("Runtime provider does not implement payload enablement: "
                + descriptor().providerId());
    }

    /// Disables one enabled provider-owned payload while retaining its loaded resources.
    ///
    /// @param handle provider-owned payload handle
    /// @throws IOException if disablement fails
    default void disablePayload(RuntimePayloadHandle handle) throws IOException {
        throw new IOException("Runtime provider does not implement payload disablement: "
                + descriptor().providerId());
    }

    /// Unloads one disabled provider-owned payload and releases its resources.
    ///
    /// @param handle provider-owned payload handle
    /// @throws IOException if unloading fails
    default void unloadPayload(RuntimePayloadHandle handle) throws IOException {
        throw new IOException("Runtime provider does not implement payload unloading: "
                + descriptor().providerId());
    }

    /// Releases provider-wide runtime resources after every dependent payload is unloaded.
    ///
    /// @throws IOException if provider shutdown fails
    @Override
    default void close() throws IOException {
    }

    /// Optional Provider transport for Hook callbacks against one exact Supervisor-owned payload handle.
    @FunctionalInterface
    @NotNullByDefault
    interface HookInvoker {
        /// Invokes one external payload Hook without applying launcher Hook policy inside the Provider.
        ///
        /// @param handle exact current payload handle
        /// @param token short-lived plugin-scoped capability token
        /// @param event immutable Hook event
        /// @param timeout positive dispatcher callback deadline
        /// @return external callback result, or `null` for malformed Provider output
        /// @throws Exception if Provider transport or external callback fails
        @Nullable PluginHookResult invokeHook(
                RuntimePayloadHandle handle,
                PluginCapabilityToken token,
                PluginHookEvent event,
                Duration timeout
        ) throws Exception;
    }
}
