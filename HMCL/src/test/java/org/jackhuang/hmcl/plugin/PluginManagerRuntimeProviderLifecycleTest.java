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

import org.jackhuang.hmcl.FXThreadTestSupport;
import org.jackhuang.hmcl.plugin.runtime.RuntimeProviderBinding;
import org.jackhuang.hmcl.plugin.runtime.RuntimeProviderRegistry;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies Provider-first Manager startup, ready-gated payload delegation, rollback, and reverse shutdown.
@EnabledIf("org.jackhuang.hmcl.JavaFXLauncher#isStarted")
@NotNullByDefault
public final class PluginManagerRuntimeProviderLifecycleTest {
    /// Canonical external payload plugin ID used by generated packages and bindings.
    private static final String PAYLOAD_ID = "dev.hmclce.test.runtime-payload";

    /// Loads a virtual runtime dependency before its lexically later Host package and tears it down first.
    ///
    /// @param temporaryDirectory isolated launcher home
    /// @throws Exception if package creation, binding persistence, discovery, or teardown fails
    @Test
    public void loadProviderBeforeBoundPayloadAndShutdownInReverse(@TempDir Path temporaryDirectory) throws Exception {
        Path localHome = temporaryDirectory.resolve("home");
        RuntimeProviderRegistry registry = RuntimeProviderRegistry.processWide();
        clearFixture(registry);
        try {
            PluginManager manager = new PluginManager(localHome);
            writePayloadPackage(manager.getPluginsDirectory().resolve("00-payload.npl"));
            writeHostPackage(manager.getPluginsDirectory().resolve("99-host.npl"));
            writeBinding(localHome);
            manager.enablePlugin(PackagedRuntimeProviderPlugin.PROVIDER_ID);
            manager.enablePlugin(PAYLOAD_ID);

            FXThreadTestSupport.runOnFxThread(manager::discoverPlugins);

            assertTrue(Objects.requireNonNull(manager.getPlugin(PackagedRuntimeProviderPlugin.PROVIDER_ID)).isEnabled());
            assertTrue(Objects.requireNonNull(manager.getPlugin(PAYLOAD_ID)).isEnabled());
            assertEquals(List.of(
                    "host.onLoad",
                    "provider.initialize",
                    "provider.health",
                    "host.onEnable",
                    "payload.load",
                    "payload.enable"
            ), events());

            FXThreadTestSupport.runOnFxThread(
                    () -> manager.unloadPlugin(PackagedRuntimeProviderPlugin.PROVIDER_ID));

            assertEquals(List.of(
                    "host.onLoad", "provider.initialize", "provider.health", "host.onEnable",
                    "payload.load", "payload.enable",
                    "payload.disable", "payload.unload",
                    "host.onDisable", "host.onUnload", "provider.close"
            ), events());
            assertNull(manager.getPlugin(PAYLOAD_ID));
            assertNull(manager.getPlugin(PackagedRuntimeProviderPlugin.PROVIDER_ID));
            assertTrue(registry.findById(PackagedRuntimeProviderPlugin.PROVIDER_ID).isEmpty());
        } finally {
            clearFixture(registry);
        }
    }

    /// Rolls an unhealthy Host out of the registry and blocks its bound payload before payload loading.
    ///
    /// @param temporaryDirectory isolated launcher home
    /// @throws Exception if package creation, binding persistence, or discovery fails
    @Test
    public void rollbackUnhealthyProviderAndBlockPayload(@TempDir Path temporaryDirectory) throws Exception {
        Path localHome = temporaryDirectory.resolve("home");
        RuntimeProviderRegistry registry = RuntimeProviderRegistry.processWide();
        clearFixture(registry);
        System.setProperty(PackagedRuntimeProviderPlugin.FAIL_HEALTH_PROPERTY, "true");
        try {
            PluginManager manager = new PluginManager(localHome);
            writePayloadPackage(manager.getPluginsDirectory().resolve("00-payload.npl"));
            writeHostPackage(manager.getPluginsDirectory().resolve("99-host.npl"));
            writeBinding(localHome);
            manager.enablePlugin(PackagedRuntimeProviderPlugin.PROVIDER_ID);
            manager.enablePlugin(PAYLOAD_ID);

            FXThreadTestSupport.runOnFxThread(manager::discoverPlugins);

            assertNull(manager.getPlugin(PackagedRuntimeProviderPlugin.PROVIDER_ID));
            assertNull(manager.getPlugin(PAYLOAD_ID));
            assertEquals(PluginRuntimeStatus.LOAD_FAILED,
                    manager.getPluginRuntimeStatus(PackagedRuntimeProviderPlugin.PROVIDER_ID));
            assertEquals(PluginRuntimeStatus.LOAD_FAILED, manager.getPluginRuntimeStatus(PAYLOAD_ID));
            assertTrue(registry.findById(PackagedRuntimeProviderPlugin.PROVIDER_ID).isEmpty());
            assertEquals(List.of("host.onLoad", "provider.initialize", "provider.health",
                    "provider.close", "host.onUnload"), events());
        } finally {
            System.clearProperty(PackagedRuntimeProviderPlugin.FAIL_HEALTH_PROPERTY);
            clearFixture(registry);
        }
    }

    /// Persists the exact virtual Provider binding selected for the external payload.
    ///
    /// @param localHome launcher-local home
    /// @throws IOException if binding publication fails
    private static void writeBinding(Path localHome) throws IOException {
        PluginMutationLock mutationLock = new PluginMutationLock(localHome);
        new PluginRuntimeBindingStore(localHome, mutationLock).mergeStrict(Map.of(
                PAYLOAD_ID,
                new RuntimeProviderBinding(PAYLOAD_ID, PackagedRuntimeProviderPlugin.PROVIDER_ID, "rust")
        ));
    }

    /// Writes the Java bootstrap Host package with its runtime declaration.
    ///
    /// @param target Host package path
    /// @throws IOException if package creation fails
    private static void writeHostPackage(Path target) throws IOException {
        String manifest = """
                {
                  "schemaVersion": 5,
                  "id": "%s",
                  "name": "Runtime Host",
                  "version": "1.0.0",
                  "type": "java",
                  "entrypoint": "%s",
                  "permissions": [],
                  "requiredPermissions": [],
                  "launcherVersion": "*",
                  "runtime": "java",
                  "abi": 2,
                  "pluginKind": "runtime-provider",
                  "providesRuntimes": [{
                    "runtime": "rust",
                    "abis": [2],
                    "bridgeAbi": 1,
                    "executionModes": ["embedded"],
                    "features": ["bridge"]
                  }]
                }
                """.formatted(PackagedRuntimeProviderPlugin.PROVIDER_ID,
                PackagedRuntimeProviderPlugin.class.getName());
        writePackage(target, manifest, PackagedRuntimeProviderPlugin.class, false);
    }

    /// Writes an external payload package without a JVM lifecycle class.
    ///
    /// @param target payload package path
    /// @throws IOException if package creation fails
    private static void writePayloadPackage(Path target) throws IOException {
        String manifest = """
                {
                  "schemaVersion": 5,
                  "id": "%s",
                  "name": "Runtime Payload",
                  "version": "1.0.0",
                  "type": "java",
                  "entrypoint": "payload/plugin.dll",
                  "permissions": [],
                  "requiredPermissions": [],
                  "launcherVersion": "*",
                  "runtime": "rust",
                  "abi": 2,
                  "executionMode": "embedded"
                }
                """.formatted(PAYLOAD_ID);
        writePackage(target, manifest, null, true);
    }

    /// Writes one deterministic test package and optional lifecycle class or payload entry.
    ///
    /// @param target package path
    /// @param manifest manifest JSON
    /// @param entrypoint optional Java lifecycle class
    /// @param payload whether to include the runtime payload resource
    /// @throws IOException if package creation fails
    private static void writePackage(
            Path target,
            String manifest,
            @Nullable Class<? extends Plugin> entrypoint,
            boolean payload
    ) throws IOException {
        Files.createDirectories(Objects.requireNonNull(target.getParent()));
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(target))) {
            writeEntry(output, "plugin.json", manifest.getBytes(StandardCharsets.UTF_8));
            if (entrypoint != null) {
                writeClassEntry(output, entrypoint);
            }
            if (payload) {
                writeEntry(output, "payload/plugin.dll", new byte[]{1, 2, 3, 4});
            }
        }
    }

    /// Copies one compiled lifecycle class into a generated package.
    ///
    /// @param output target archive
    /// @param entrypoint lifecycle class
    /// @throws IOException if class bytes cannot be read or written
    private static void writeClassEntry(
            ZipOutputStream output,
            Class<? extends Plugin> entrypoint
    ) throws IOException {
        String resource = entrypoint.getName().replace('.', '/') + ".class";
        try (@Nullable InputStream input = entrypoint.getClassLoader().getResourceAsStream(resource)) {
            if (input == null) {
                throw new IOException("Compiled test plugin class not found: " + resource);
            }
            ZipEntry entry = new ZipEntry(resource);
            entry.setTime(0);
            output.putNextEntry(entry);
            input.transferTo(output);
            output.closeEntry();
        }
    }

    /// Writes one deterministic archive entry.
    ///
    /// @param output target archive
    /// @param name package-relative name
    /// @param bytes entry bytes
    /// @throws IOException if writing fails
    private static void writeEntry(ZipOutputStream output, String name, byte[] bytes) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(0);
        output.putNextEntry(entry);
        output.write(bytes);
        output.closeEntry();
    }

    /// Returns the current ordered fixture events.
    ///
    /// @return immutable event list
    private static List<String> events() {
        @Nullable String events = System.getProperty(PackagedRuntimeProviderPlugin.EVENTS_PROPERTY);
        return events == null || events.isBlank() ? List.of() : List.of(events.split(","));
    }

    /// Clears process-global fixture state and any stale Provider registration.
    ///
    /// @param registry process-wide runtime registry
    private static void clearFixture(RuntimeProviderRegistry registry) {
        registry.unbind(PAYLOAD_ID);
        registry.unregister(PackagedRuntimeProviderPlugin.PROVIDER_ID);
        System.clearProperty(PackagedRuntimeProviderPlugin.EVENTS_PROPERTY);
        System.clearProperty(PackagedRuntimeProviderPlugin.FAIL_HEALTH_PROPERTY);
    }
}
