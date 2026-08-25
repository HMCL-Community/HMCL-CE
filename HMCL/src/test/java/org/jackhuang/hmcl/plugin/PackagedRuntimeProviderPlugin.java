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

import org.jackhuang.hmcl.plugin.bridge.PluginCapabilityToken;
import org.jackhuang.hmcl.plugin.runtime.RuntimePayloadContext;
import org.jackhuang.hmcl.plugin.runtime.RuntimePayloadHandle;
import org.jackhuang.hmcl.plugin.runtime.RuntimeProvider;
import org.jackhuang.hmcl.plugin.runtime.RuntimeProviderDescriptor;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Supplier;

/// Package-owned runtime Provider Host fixture whose callbacks are recorded through process properties.
@NotNullByDefault
public final class PackagedRuntimeProviderPlugin
        implements Plugin, RuntimeProvider, RuntimeProvider.HookInvoker {
    /// Canonical Host plugin ID shared with generated test manifests.
    public static final String PROVIDER_ID = "dev.hmclce.test.runtime-host";

    /// Process property containing comma-separated callback order.
    public static final String EVENTS_PROPERTY = "hmcl.test.runtime-provider.events";

    /// Process property forcing the health check to fail when set to `true`.
    public static final String FAIL_HEALTH_PROPERTY = "hmcl.test.runtime-provider.fail-health";

    /// Process property naming a persistent data marker required by the health check.
    public static final String REQUIRED_DATA_MARKER_PROPERTY =
            "hmcl.test.runtime-provider.required-data-marker";

    /// Process property containing the data directory observed by the most recently loaded Host.
    public static final String DATA_PATH_PROPERTY = "hmcl.test.runtime-provider.data-path";

    /// Process property enabling one sidebar registration from `onLoad`.
    public static final String REGISTER_UI_PROPERTY = "hmcl.test.runtime-provider.register-ui";

    /// Process property naming the exact Host version whose health check must fail.
    public static final String FAIL_HEALTH_VERSION_PROPERTY =
            "hmcl.test.runtime-provider.fail-health-version";

    /// Process property containing the number of Host instances whose `onLoad` has not been unloaded.
    public static final String ACTIVE_INSTANCES_PROPERTY =
            "hmcl.test.runtime-provider.active-instances";

    /// Process property forcing the next payload unload to fail once when set to `true`.
    public static final String FAIL_UNLOAD_ONCE_PROPERTY = "hmcl.test.runtime-provider.fail-unload-once";

    /// Process property forcing the next Provider close to fail once when set to `true`.
    public static final String FAIL_CLOSE_ONCE_PROPERTY = "hmcl.test.runtime-provider.fail-close-once";

    /// Process property forcing the next payload enable callback to fail once when set to `true`.
    public static final String FAIL_PAYLOAD_ENABLE_ONCE_PROPERTY =
            "hmcl.test.runtime-provider.fail-payload-enable-once";

    /// Process property asking the next health check to probe the retained payload capability supplier.
    public static final String CHECK_PAYLOAD_CAPABILITY_PROPERTY =
            "hmcl.test.runtime-provider.check-payload-capability";

    /// Process property recording whether the most recent retained-supplier probe could issue a token.
    public static final String PAYLOAD_CAPABILITY_AVAILABLE_PROPERTY =
            "hmcl.test.runtime-provider.payload-capability-available";

    /// Process property recording whether payload unload observed its capability session already closed.
    public static final String UNLOAD_CAPABILITY_CLOSED_PROPERTY =
            "hmcl.test.runtime-provider.unload-capability-closed";

    /// Process property recording whether payload disable observed its capability session already suspended.
    public static final String DISABLE_CAPABILITY_SUSPENDED_PROPERTY =
            "hmcl.test.runtime-provider.disable-capability-suspended";

    /// Process property containing the identity hash of the TCCL observed by the latest Hook callback.
    public static final String HOOK_TCCL_PROPERTY = "hmcl.test.runtime-provider.hook-tccl";

    /// Manifest received during Host loading, or `null` before registration.
    private @Nullable PluginManifest manifest;

    /// Persistent private data directory received during Host loading, or `null` before `onLoad`.
    private @Nullable Path dataDirectory;

    /// Capability supplier retained from the most recently loaded payload context.
    private @Nullable Supplier<PluginCapabilityToken> payloadCapabilityTokenSupplier;

    /// Creates the package-owned Host lifecycle.
    public PackagedRuntimeProviderPlugin() {
    }

    /// Registers this exact Host implementation with its manager-owned context.
    ///
    /// @param context Host plugin context
    @Override
    public void onLoad(PluginContext context) {
        manifest = context.getManifest();
        dataDirectory = context.getDataDirectory().toAbsolutePath().normalize();
        System.setProperty(DATA_PATH_PROPERTY, dataDirectory.toString());
        changeActiveInstances(1);
        append("host.onLoad");
        if (Boolean.getBoolean(REGISTER_UI_PROPERTY)) {
            context.registerSidebarItem("Runtime Host " + manifest.getVersion(), () -> {
            });
        }
        context.registerRuntimeProvider(this);
    }

    /// Records Host enablement after Provider readiness.
    @Override
    public void onEnable() {
        append("host.onEnable");
    }

    /// Records Host disablement after dependent payload shutdown.
    @Override
    public void onDisable() {
        append("host.onDisable");
    }

    /// Records the Host plugin's own Java Hook callback.
    ///
    /// @param event immutable Hook event
    /// @return unchanged Hook result
    @Override
    public PluginHookResult onHook(PluginHookEvent event) {
        append("host.hook");
        return PluginHookResult.unchanged();
    }

    /// Records Host bootstrap unloading.
    @Override
    public void onUnload() {
        append("host.onUnload");
        changeActiveInstances(-1);
    }

    /// Returns the authoritative package manifest.
    ///
    /// @return Host manifest
    @Override
    public PluginManifest getManifest() {
        return Objects.requireNonNull(manifest);
    }

    /// Returns the exact capabilities advertised by the Host manifest.
    @Override
    public RuntimeProviderDescriptor descriptor() {
        return new RuntimeProviderDescriptor(
                getManifest().getId(),
                getManifest().getVersion(),
                getManifest().getProvidesRuntimes(),
                true,
                true,
                0,
                false
        );
    }

    /// Records Provider initialization.
    @Override
    public void initialize() {
        append("provider.initialize");
    }

    /// Records Provider health negotiation and returns the configured result.
    @Override
    public boolean healthCheck() {
        append("provider.health");
        if (Boolean.getBoolean(CHECK_PAYLOAD_CAPABILITY_PROPERTY)) {
            System.clearProperty(CHECK_PAYLOAD_CAPABILITY_PROPERTY);
            System.setProperty(
                    PAYLOAD_CAPABILITY_AVAILABLE_PROPERTY,
                    Boolean.toString(canIssuePayloadCapability())
            );
        }
        if (Boolean.getBoolean(FAIL_HEALTH_PROPERTY)) {
            return false;
        }
        @Nullable String failedVersion = System.getProperty(FAIL_HEALTH_VERSION_PROPERTY);
        if (getManifest().getVersion().equals(failedVersion)) {
            return false;
        }
        @Nullable String requiredMarker = System.getProperty(REQUIRED_DATA_MARKER_PROPERTY);
        return requiredMarker == null
                || Files.isRegularFile(Objects.requireNonNull(dataDirectory).resolve(requiredMarker));
    }

    /// Records exact payload loading and returns an opaque handle.
    @Override
    public RuntimePayloadHandle loadPayload(RuntimePayloadContext context) {
        append("payload.load");
        payloadCapabilityTokenSupplier = context.capabilityTokenSupplier();
        return new RuntimePayloadHandle(
                context.artifactIdentity().getPluginId(),
                PROVIDER_ID,
                "payload-" + context.artifactIdentity().getPluginId()
        );
    }

    /// Records payload enablement.
    @Override
    public void enablePayload(RuntimePayloadHandle handle) throws IOException {
        requirePayloadCapabilityTokenSupplier().get();
        append("payload.enable");
        if (Boolean.getBoolean(FAIL_PAYLOAD_ENABLE_ONCE_PROPERTY)) {
            System.clearProperty(FAIL_PAYLOAD_ENABLE_ONCE_PROPERTY);
            throw new IOException("Configured one-shot payload enable failure");
        }
    }

    /// Records payload disablement.
    @Override
    public void disablePayload(RuntimePayloadHandle handle) {
        System.setProperty(
                DISABLE_CAPABILITY_SUSPENDED_PROPERTY,
                Boolean.toString(!canIssuePayloadCapability())
        );
        append("payload.disable");
    }

    /// Records payload unloading.
    @Override
    public void unloadPayload(RuntimePayloadHandle handle) throws IOException {
        System.setProperty(
                UNLOAD_CAPABILITY_CLOSED_PROPERTY,
                Boolean.toString(!canIssuePayloadCapability())
        );
        append("payload.unload");
        if (Boolean.getBoolean(FAIL_UNLOAD_ONCE_PROPERTY)) {
            System.clearProperty(FAIL_UNLOAD_ONCE_PROPERTY);
            throw new IOException("Configured one-shot payload unload failure");
        }
    }

    /// Records one external payload Hook callback.
    ///
    /// @param handle exact current payload handle
    /// @param token short-lived payload capability token
    /// @param event immutable Hook event
    /// @param timeout dispatcher callback deadline
    /// @return unchanged Hook result
    @Override
    public PluginHookResult invokeHook(
            RuntimePayloadHandle handle,
            PluginCapabilityToken token,
            PluginHookEvent event,
            Duration timeout
    ) {
        System.setProperty(
                HOOK_TCCL_PROPERTY,
                Integer.toString(System.identityHashCode(Thread.currentThread().getContextClassLoader()))
        );
        append("payload.hook:" + handle.ownerPluginId());
        return PluginHookResult.unchanged();
    }

    /// Records Provider-wide resource shutdown.
    @Override
    public void close() throws IOException {
        append("provider.close");
        if (Boolean.getBoolean(FAIL_CLOSE_ONCE_PROPERTY)) {
            System.clearProperty(FAIL_CLOSE_ONCE_PROPERTY);
            throw new IOException("Configured one-shot Provider close failure");
        }
    }

    /// Returns whether the retained payload supplier can currently issue a token.
    ///
    /// @return whether token issuance succeeded
    private boolean canIssuePayloadCapability() {
        try {
            requirePayloadCapabilityTokenSupplier().get();
            return true;
        } catch (IllegalStateException exception) {
            return false;
        }
    }

    /// Returns the retained payload capability supplier after payload loading.
    ///
    /// @return retained capability supplier
    /// @throws IllegalStateException if no payload was loaded
    private Supplier<PluginCapabilityToken> requirePayloadCapabilityTokenSupplier() {
        @Nullable Supplier<PluginCapabilityToken> supplier = payloadCapabilityTokenSupplier;
        if (supplier == null) {
            throw new IllegalStateException("No runtime payload capability supplier is retained");
        }
        return supplier;
    }

    /// Appends one callback marker to the process-global fixture log.
    ///
    /// @param event callback marker
    private static synchronized void append(String event) {
        @Nullable String existing = System.getProperty(EVENTS_PROPERTY);
        System.setProperty(EVENTS_PROPERTY, existing == null || existing.isEmpty() ? event : existing + "," + event);
    }

    /// Changes the process-global count of currently loaded Host instances.
    ///
    /// @param delta positive for load and negative for unload
    private static synchronized void changeActiveInstances(int delta) {
        @Nullable String currentValue = System.getProperty(ACTIVE_INSTANCES_PROPERTY);
        int current = currentValue == null ? 0 : Integer.parseInt(currentValue);
        System.setProperty(ACTIVE_INSTANCES_PROPERTY, Integer.toString(current + delta));
    }
}
