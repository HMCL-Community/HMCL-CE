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
package org.jackhuang.hmcl.plugin.loader;

import org.jackhuang.hmcl.plugin.Plugin;
import org.jackhuang.hmcl.plugin.PluginArtifactIdentity;
import org.jackhuang.hmcl.plugin.PluginManifest;
import org.jackhuang.hmcl.plugin.bridge.PluginCapabilityToken;
import org.jackhuang.hmcl.plugin.bridge.PluginPermissionAuthority;
import org.jackhuang.hmcl.plugin.internal.PluginPackageVersions;
import org.jackhuang.hmcl.plugin.internal.VerifiedPluginPackage;
import org.jackhuang.hmcl.plugin.runtime.PluginAbi;
import org.jackhuang.hmcl.plugin.runtime.PluginExecutionMode;
import org.jackhuang.hmcl.plugin.runtime.RuntimeFeature;
import org.jackhuang.hmcl.plugin.runtime.RuntimePayloadContext;
import org.jackhuang.hmcl.plugin.runtime.RuntimePayloadHandle;
import org.jackhuang.hmcl.plugin.runtime.RuntimeProvider;
import org.jackhuang.hmcl.plugin.runtime.RuntimeProviderDeclaration;
import org.jackhuang.hmcl.plugin.runtime.RuntimeProviderDescriptor;
import org.jackhuang.hmcl.plugin.runtime.RuntimeProviderRegistration;
import org.jackhuang.hmcl.plugin.runtime.RuntimeProviderRegistry;
import org.jackhuang.hmcl.plugin.runtime.RuntimeSupervisor;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/// Verifies external payload context construction and lifecycle delegation through a selected runtime Provider.
@NotNullByDefault
public final class RuntimePluginLoaderTest {
    /// Preserves exact package authority and delegates every lifecycle callback through the bound Provider.
    ///
    /// @param temporaryDirectory isolated package, cache, and data paths
    /// @throws Exception if test package or Provider lifecycle setup fails
    @Test
    public void delegateExactPayloadLifecycle(@TempDir Path temporaryDirectory) throws Exception {
        String pluginId = "dev.plugin.rust";
        Path nplFile = temporaryDirectory.resolve("rust-plugin.npl");
        PluginManifest manifest = manifest(pluginId);
        writePackage(nplFile, manifestJson(pluginId));
        PluginArtifactIdentity identity = new PluginArtifactIdentity(
                pluginId,
                "1.0.0",
                PluginPackageVersions.calculateSha256(nplFile)
        );
        VerifiedPluginPackage pluginPackage = PluginPackageVersions.prepareVerifiedLifecyclePackage(
                nplFile,
                temporaryDirectory.resolve("cache"),
                identity
        );
        Path dataDirectory = temporaryDirectory.resolve("storage").resolve(pluginId);
        PluginPermissionAuthority authority = new PluginPermissionAuthority();
        PluginCapabilityToken token = authority.issue(
                identity,
                PluginExecutionMode.EMBEDDED,
                Set.of(),
                "runtime.payload",
                Duration.ofMinutes(1)
        );

        RuntimeProviderRegistry registry = new RuntimeProviderRegistry();
        RuntimeSupervisor supervisor = new RuntimeSupervisor(registry);
        RecordingProvider provider = new RecordingProvider();
        supervisor.discover("dev.host.rust");
        supervisor.resolve("dev.host.rust");
        supervisor.bootstrapLoaded("dev.host.rust");
        RuntimeProviderRegistration registration = supervisor.register("dev.host.rust", provider);
        supervisor.activate(registration);
        registry.bind(pluginId, manifest.getRuntimeRequirement());
        RuntimePluginLoader loader = new RuntimePluginLoader(
                supervisor,
                ignored -> dataDirectory,
                ignored -> () -> token,
                authority
        );

        Plugin plugin = loader.load(manifest, pluginPackage, nplFile);
        RuntimePayloadContext context = Objects.requireNonNull(provider.context);

        assertSame(identity, context.artifactIdentity());
        assertEquals(pluginPackage.getDirectory(), context.packagePath());
        assertEquals("payload/plugin.dll", context.entrypoint());
        assertEquals(PluginExecutionMode.EMBEDDED, context.executionMode());
        assertEquals(dataDirectory.toAbsolutePath().normalize(), context.dataDirectory());
        assertSame(token, context.capabilityTokenSupplier().get());
        assertSame(manifest, plugin.getManifest());

        plugin.onLoad(new org.jackhuang.hmcl.plugin.PluginContext(
                manifest,
                pluginPackage.getDirectory(),
                dataDirectory,
                getClass().getClassLoader()
        ));
        plugin.onEnable();
        plugin.onDisable();
        plugin.onUnload();

        assertEquals(List.of("load", "enable", "disable", "unload"), provider.events);
        registration.close();
    }

    /// Parses the external-runtime manifest used by the loader test.
    ///
    /// @param pluginId dependent plugin ID
    /// @return validated schema-v5 manifest
    /// @throws IOException if the fixture is invalid
    private static PluginManifest manifest(String pluginId) throws IOException {
        return PluginManifest.fromJson(new StringReader(manifestJson(pluginId)));
    }

    /// Creates the schema-v5 JSON used both for parsing and package publication.
    ///
    /// @param pluginId dependent plugin ID
    /// @return manifest JSON
    private static String manifestJson(String pluginId) {
        return """
                {
                  "schemaVersion": 5,
                  "id": "%s",
                  "name": "Rust Payload",
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
                """.formatted(pluginId);
    }

    /// Writes a minimal package containing its manifest and runtime-owned payload entrypoint.
    ///
    /// @param nplFile package path
    /// @param manifestJson validated manifest JSON
    /// @throws IOException if package creation fails
    private static void writePackage(Path nplFile, String manifestJson) throws IOException {
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(nplFile))) {
            writeEntry(output, "plugin.json", manifestJson.getBytes(StandardCharsets.UTF_8));
            writeEntry(output, "payload/plugin.dll", new byte[]{1, 2, 3, 4});
        }
    }

    /// Writes one deterministic package entry.
    ///
    /// @param output target archive
    /// @param name package-relative entry name
    /// @param bytes entry bytes
    /// @throws IOException if writing fails
    private static void writeEntry(ZipOutputStream output, String name, byte[] bytes) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(0);
        output.putNextEntry(entry);
        output.write(bytes);
        output.closeEntry();
    }

    /// Recording embedded Rust Provider fixture.
    @NotNullByDefault
    private static final class RecordingProvider implements RuntimeProvider {
        /// Provider callbacks in invocation order.
        private final List<String> events = new ArrayList<>();

        /// Exact payload context received during loading, or `null` before loading.
        private @Nullable RuntimePayloadContext context;

        /// Returns the fake Host descriptor.
        @Override
        public RuntimeProviderDescriptor descriptor() {
            return new RuntimeProviderDescriptor(
                    "dev.host.rust",
                    "1.0.0",
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

        /// Captures the exact context and returns an opaque handle.
        @Override
        public RuntimePayloadHandle loadPayload(RuntimePayloadContext context) {
            events.add("load");
            this.context = context;
            return new RuntimePayloadHandle(
                    context.artifactIdentity().getPluginId(),
                    descriptor().providerId(),
                    "payload-1"
            );
        }

        /// Records payload enablement.
        @Override
        public void enablePayload(RuntimePayloadHandle handle) {
            events.add("enable");
        }

        /// Records payload disablement.
        @Override
        public void disablePayload(RuntimePayloadHandle handle) {
            events.add("disable");
        }

        /// Records payload unloading.
        @Override
        public void unloadPayload(RuntimePayloadHandle handle) {
            events.add("unload");
        }
    }
}
