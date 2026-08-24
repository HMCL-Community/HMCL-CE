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
import org.jackhuang.hmcl.plugin.runtime.RuntimeProvider;
import org.jackhuang.hmcl.plugin.runtime.RuntimeProviderDeclaration;
import org.jackhuang.hmcl.plugin.runtime.RuntimeProviderDescriptor;
import org.jackhuang.hmcl.plugin.runtime.RuntimeProviderRegistration;
import org.jackhuang.hmcl.plugin.runtime.RuntimeProviderRegistry;
import org.jackhuang.hmcl.plugin.runtime.RuntimeProviderState;
import org.jackhuang.hmcl.plugin.runtime.RuntimeSupervisor;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies that external runtime Provider registrations belong to and close with their Host container.
@NotNullByDefault
public final class RuntimeProviderRegistrationOwnershipTest {
    /// Registers an exact manifest-matching Provider and closes it automatically with the Host container.
    ///
    /// @param temporaryDirectory isolated package and data paths
    /// @throws Exception if manifest parsing or Provider cleanup fails
    @Test
    public void closeHostOwnedRegistrationWithContainer(@TempDir Path temporaryDirectory) throws Exception {
        PluginManifest manifest = providerManifest();
        RuntimeProviderRegistry registry = new RuntimeProviderRegistry();
        RuntimeSupervisor supervisor = new RuntimeSupervisor(registry);
        supervisor.discover(manifest.getId());
        supervisor.resolve(manifest.getId());
        supervisor.bootstrapLoaded(manifest.getId());
        PluginContext context = context(manifest, temporaryDirectory,
                provider -> supervisor.register(manifest.getId(), provider));
        RuntimeProvider provider = provider(manifest, manifest.getProvidesRuntimes());
        PluginContainer container = new PluginContainer(new EmptyPlugin(manifest), context,
                temporaryDirectory.resolve("host.npl"));

        RuntimeProviderRegistration registration = context.registerRuntimeProvider(provider);
        supervisor.activate(registration);
        container.closeRuntimeProviderRegistrations();

        assertTrue(registration.isClosed());
        assertEquals(RuntimeProviderState.STOPPED, supervisor.state(manifest.getId()).orElseThrow());
        assertTrue(registry.findById(manifest.getId()).isEmpty());
    }

    /// Rejects Provider implementations whose identity or capabilities differ from the Host manifest.
    ///
    /// @param temporaryDirectory isolated package and data paths
    /// @throws IOException if the Provider manifest cannot be parsed
    @Test
    public void rejectRegistrationOutsideHostManifest(@TempDir Path temporaryDirectory) throws IOException {
        PluginManifest manifest = providerManifest();
        PluginContext context = context(manifest, temporaryDirectory, provider -> {
            throw new AssertionError("Mismatched Provider must not reach the registry");
        });
        RuntimeProvider wrongId = provider(manifest, manifest.getProvidesRuntimes(), "dev.host.other");
        RuntimeProvider wrongCapabilities = provider(manifest, List.of(new RuntimeProviderDeclaration(
                "rust",
                Set.of(PluginAbi.ABI_1),
                1,
                Set.of(PluginExecutionMode.EMBEDDED),
                Set.of(RuntimeFeature.BRIDGE)
        )));

        assertThrows(IllegalArgumentException.class, () -> context.registerRuntimeProvider(wrongId));
        assertThrows(IllegalArgumentException.class, () -> context.registerRuntimeProvider(wrongCapabilities));
    }

    /// Rejects Provider registration from an ordinary language payload context.
    ///
    /// @param temporaryDirectory isolated package and data paths
    /// @throws IOException if the ordinary manifest cannot be parsed
    @Test
    public void rejectRegistrationFromOrdinaryPlugin(@TempDir Path temporaryDirectory) throws IOException {
        PluginManifest ordinary = PluginManifest.fromJson(new StringReader("""
                {
                  "schemaVersion": 5,
                  "id": "dev.plugin.rust",
                  "name": "Rust Payload",
                  "version": "1.0.0",
                  "type": "java",
                  "entrypoint": "payload/plugin.dll",
                  "permissions": [],
                  "requiredPermissions": [],
                  "launcherVersion": "*",
                  "runtime": "rust",
                  "abi": 2
                }
                """));
        PluginContext context = context(ordinary, temporaryDirectory, provider -> {
            throw new AssertionError("Ordinary plugin must not reach the registry");
        });

        assertThrows(IllegalStateException.class,
                () -> context.registerRuntimeProvider(provider(providerManifest(),
                        providerManifest().getProvidesRuntimes())));
    }

    /// Creates a manager-style plugin context with a caller-controlled Provider registrar.
    ///
    /// @param manifest authoritative manifest
    /// @param temporaryDirectory isolated path root
    /// @param registrar Host-bound registration callback
    /// @return plugin context
    private static PluginContext context(
            PluginManifest manifest,
            Path temporaryDirectory,
            java.util.function.Function<RuntimeProvider, RuntimeProviderRegistration> registrar
    ) {
        return new PluginContext(
                manifest,
                temporaryDirectory.resolve("package"),
                temporaryDirectory.resolve("data"),
                RuntimeProviderRegistrationOwnershipTest.class.getClassLoader(),
                "a".repeat(64),
                Set::of,
                registrar
        );
    }

    /// Parses the schema-v5 Host manifest used by ownership tests.
    ///
    /// @return validated Provider Host manifest
    /// @throws IOException if the fixture is invalid
    private static PluginManifest providerManifest() throws IOException {
        return PluginManifest.fromJson(new StringReader("""
                {
                  "schemaVersion": 5,
                  "id": "dev.host.rust",
                  "name": "Rust Host",
                  "version": "1.0.0",
                  "type": "java",
                  "entrypoint": "dev.host.RustHostPlugin",
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
                """));
    }

    /// Creates a Provider implementation matching the Host ID and supplied capabilities.
    ///
    /// @param manifest Host manifest
    /// @param declarations supplied runtime declarations
    /// @return Provider implementation
    private static RuntimeProvider provider(
            PluginManifest manifest,
            List<RuntimeProviderDeclaration> declarations
    ) {
        return provider(manifest, declarations, manifest.getId());
    }

    /// Creates a Provider implementation with caller-controlled identity and capabilities.
    ///
    /// @param manifest Host manifest
    /// @param declarations supplied runtime declarations
    /// @param providerId descriptor Provider ID
    /// @return Provider implementation
    private static RuntimeProvider provider(
            PluginManifest manifest,
            List<RuntimeProviderDeclaration> declarations,
            String providerId
    ) {
        RuntimeProviderDescriptor descriptor = new RuntimeProviderDescriptor(
                providerId,
                manifest.getVersion(),
                declarations,
                true,
                true,
                0,
                false
        );
        return new RuntimeProvider() {
            /// Returns the configured descriptor.
            @Override
            public RuntimeProviderDescriptor descriptor() {
                return descriptor;
            }
        };
    }

    /// Empty Host lifecycle fixture used only to construct a real container.
    @NotNullByDefault
    private static final class EmptyPlugin implements Plugin {
        /// Authoritative Host manifest.
        private final PluginManifest manifest;

        /// Creates an empty Host lifecycle.
        ///
        /// @param manifest Host manifest
        private EmptyPlugin(PluginManifest manifest) {
            this.manifest = manifest;
        }

        /// Accepts the Host context without side effects.
        @Override
        public void onLoad(PluginContext context) {
        }

        /// Enables without side effects.
        @Override
        public void onEnable() {
        }

        /// Disables without side effects.
        @Override
        public void onDisable() {
        }

        /// Returns the authoritative Host manifest.
        @Override
        public PluginManifest getManifest() {
            return manifest;
        }
    }
}
