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

import org.jackhuang.hmcl.plugin.runtime.PluginAbi;
import org.jackhuang.hmcl.plugin.runtime.PluginExecutionMode;
import org.jackhuang.hmcl.plugin.runtime.RuntimeFeature;
import org.jackhuang.hmcl.plugin.runtime.RuntimePayloadContext;
import org.jackhuang.hmcl.plugin.runtime.RuntimePayloadHandle;
import org.jackhuang.hmcl.plugin.runtime.RuntimeProvider;
import org.jackhuang.hmcl.plugin.runtime.RuntimeProviderDeclaration;
import org.jackhuang.hmcl.plugin.runtime.RuntimeProviderDescriptor;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/// Package-owned runtime Provider Host fixture whose callbacks are recorded through process properties.
@NotNullByDefault
public final class PackagedRuntimeProviderPlugin implements Plugin, RuntimeProvider {
    /// Canonical Host plugin ID shared with generated test manifests.
    public static final String PROVIDER_ID = "dev.hmclce.test.runtime-host";

    /// Process property containing comma-separated callback order.
    public static final String EVENTS_PROPERTY = "hmcl.test.runtime-provider.events";

    /// Process property forcing the health check to fail when set to `true`.
    public static final String FAIL_HEALTH_PROPERTY = "hmcl.test.runtime-provider.fail-health";

    /// Process property forcing the next payload unload to fail once when set to `true`.
    public static final String FAIL_UNLOAD_ONCE_PROPERTY = "hmcl.test.runtime-provider.fail-unload-once";

    /// Manifest received during Host loading, or `null` before registration.
    private @Nullable PluginManifest manifest;

    /// Creates the package-owned Host lifecycle.
    public PackagedRuntimeProviderPlugin() {
    }

    /// Registers this exact Host implementation with its manager-owned context.
    ///
    /// @param context Host plugin context
    @Override
    public void onLoad(PluginContext context) {
        manifest = context.getManifest();
        append("host.onLoad");
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

    /// Records Host bootstrap unloading.
    @Override
    public void onUnload() {
        append("host.onUnload");
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
                PROVIDER_ID,
                getManifest().getVersion(),
                List.of(new RuntimeProviderDeclaration(
                        "rust",
                        Set.of(PluginAbi.ABI_2),
                        1,
                        Set.of(PluginExecutionMode.EMBEDDED),
                        Set.of(RuntimeFeature.BRIDGE)
                )),
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
        return !Boolean.getBoolean(FAIL_HEALTH_PROPERTY);
    }

    /// Records exact payload loading and returns an opaque handle.
    @Override
    public RuntimePayloadHandle loadPayload(RuntimePayloadContext context) {
        append("payload.load");
        return new RuntimePayloadHandle(
                context.artifactIdentity().getPluginId(),
                PROVIDER_ID,
                "payload-" + context.artifactIdentity().getPluginId()
        );
    }

    /// Records payload enablement.
    @Override
    public void enablePayload(RuntimePayloadHandle handle) {
        append("payload.enable");
    }

    /// Records payload disablement.
    @Override
    public void disablePayload(RuntimePayloadHandle handle) {
        append("payload.disable");
    }

    /// Records payload unloading.
    @Override
    public void unloadPayload(RuntimePayloadHandle handle) throws IOException {
        append("payload.unload");
        if (Boolean.getBoolean(FAIL_UNLOAD_ONCE_PROPERTY)) {
            System.clearProperty(FAIL_UNLOAD_ONCE_PROPERTY);
            throw new IOException("Configured one-shot payload unload failure");
        }
    }

    /// Records Provider-wide resource shutdown.
    @Override
    public void close() {
        append("provider.close");
    }

    /// Appends one callback marker to the process-global fixture log.
    ///
    /// @param event callback marker
    private static synchronized void append(String event) {
        @Nullable String existing = System.getProperty(EVENTS_PROPERTY);
        System.setProperty(EVENTS_PROPERTY, existing == null || existing.isEmpty() ? event : existing + "," + event);
    }
}
