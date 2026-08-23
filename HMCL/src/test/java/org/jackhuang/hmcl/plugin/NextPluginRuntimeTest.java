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
import org.jackhuang.hmcl.plugin.runtime.PluginPlatformTarget;
import org.jackhuang.hmcl.plugin.runtime.PluginRuntimeTypes;
import org.jackhuang.hmcl.plugin.runtime.RuntimeProvider;
import org.jackhuang.hmcl.plugin.runtime.RuntimeProviderRegistry;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Guards the next-generation plugin foundation: ABI generations, platform targets,
/// the runtime provider registry, permission tiers, and schema-v5 manifest fields.
@NotNullByDefault
public final class NextPluginRuntimeTest {
    /// Verifies supported ABI generations and rejects unknown generations.
    @Test
    public void abiBackwardCompatibility() {
        assertTrue(PluginAbi.supports(PluginAbi.ABI_1));
        assertTrue(PluginAbi.supports(PluginAbi.ABI_2));
        assertFalse(PluginAbi.supports(PluginAbi.ABI_2 + 1));
        assertFalse(PluginAbi.supports(0));
        assertEquals(2, PluginAbi.requireValid(2));
        assertThrows(IllegalArgumentException.class, () -> PluginAbi.requireValid(3));
    }

    /// Verifies platform parsing, normalization, and host matching.
    @Test
    public void platformTargetParsingAndMatching() {
        PluginPlatformTarget windowsX64 = PluginPlatformTarget.parse("windows-x64");
        assertEquals("windows", windowsX64.getOperatingSystem());
        assertEquals("x64", windowsX64.getArchitecture());
        assertEquals("windows-x64", windowsX64.getId());
        assertTrue(windowsX64.matches(PluginPlatformTarget.parse("windows-x64")));
        assertFalse(windowsX64.matches(PluginPlatformTarget.parse("windows-arm64")));
        assertFalse(windowsX64.matches(PluginPlatformTarget.parse("linux-x64")));
        PluginPlatformTarget anyMac = PluginPlatformTarget.parse("macos");
        assertTrue(anyMac.matches(PluginPlatformTarget.parse("macos-arm64")));
        assertTrue(anyMac.matches(PluginPlatformTarget.parse("macos-x64")));
        assertEquals(PluginPlatformTarget.parse("Linux-ARM64"), PluginPlatformTarget.parse("linux-arm64"));
        assertThrows(IllegalArgumentException.class, () -> PluginPlatformTarget.parse("os2-x64"));
        assertThrows(IllegalArgumentException.class, () -> PluginPlatformTarget.parse("windows-x99"));
        assertThrows(IllegalArgumentException.class, () -> PluginPlatformTarget.parse(" "));
    }

    /// Verifies the current host platform is represented by a known target.
    @Test
    public void currentPlatformIsKnown() {
        PluginPlatformTarget current = PluginPlatformTarget.current();
        assertTrue(PluginPlatformTarget.KNOWN_OPERATING_SYSTEMS.contains(current.getOperatingSystem()));
        assertTrue(current.matches(current));
    }

    /// Verifies registration, lookup, and protection of the built-in runtime provider.
    @Test
    public void runtimeProviderRegistryLifecycle() {
        RuntimeProviderRegistry registry = new RuntimeProviderRegistry();
        assertEquals(1, registry.size());
        assertTrue(registry.isAvailable(PluginRuntimeTypes.JAVA));
        assertFalse(registry.isAvailable("dotnet"));
        RuntimeProvider dotnet = new RuntimeProvider() {
            /// Returns the test provider's runtime identifier.
            @Override
            public String runtimeType() {
                return "dotnet";
            }

            /// Returns the ABI generations supported by the test provider.
            @Override
            public @Unmodifiable Set<Integer> implementedPluginAbis() {
                return Set.of(PluginAbi.ABI_1);
            }

            /// Returns the test provider description.
            @Override
            public String describe() {
                return "Test .NET host";
            }
        };
        registry.register(dotnet);
        Optional<RuntimeProvider> found = registry.find("dotnet");
        assertTrue(found.isPresent());
        assertTrue(found.get().supportsAbi(PluginAbi.ABI_1));
        registry.unregister(PluginRuntimeTypes.JAVA);
        assertTrue(registry.isAvailable(PluginRuntimeTypes.JAVA));
        assertThrows(IllegalArgumentException.class, () -> registry.find("Dot Net"));
    }

    /// Rejects duplicate external runtime providers without replacing the first registration.
    @Test
    public void rejectDuplicateExternalRuntimeProvider() {
        RuntimeProviderRegistry registry = new RuntimeProviderRegistry();
        RuntimeProvider first = provider("dotnet", "First .NET host", Set.of(PluginAbi.ABI_1));
        RuntimeProvider duplicate = provider(" DOTNET ", "Duplicate .NET host", Set.of(PluginAbi.ABI_2));
        registry.register(first);

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> registry.register(duplicate));

        assertTrue(exception.getMessage().contains("dotnet"));
        assertSame(first, registry.find("dotnet").orElseThrow());
    }

    /// Rejects a provider whose canonical runtime identifier would replace built-in Java.
    @Test
    public void rejectDuplicateJavaRuntimeProvider() {
        RuntimeProviderRegistry registry = new RuntimeProviderRegistry();
        RuntimeProvider duplicate = provider(" JAVA ", "External Java host", Set.of(PluginAbi.ABI_1));

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> registry.register(duplicate));

        assertTrue(exception.getMessage().contains(PluginRuntimeTypes.JAVA));
        assertEquals(1, registry.size());
        assertEquals("Built-in Java plugin runtime (in-process JVM)",
                registry.find(PluginRuntimeTypes.JAVA).orElseThrow().describe());
    }

    /// Protects the built-in Java provider after canonicalizing the unregister identifier.
    @Test
    public void preserveJavaProviderForCanonicalUnregisterInput() {
        RuntimeProviderRegistry registry = new RuntimeProviderRegistry();

        registry.unregister(" JAVA ");

        assertTrue(registry.isAvailable(PluginRuntimeTypes.JAVA));
        assertEquals(1, registry.size());
    }

    /// Allows an explicitly removed external runtime provider to be registered again.
    @Test
    public void reregisterRemovedExternalRuntimeProvider() {
        RuntimeProviderRegistry registry = new RuntimeProviderRegistry();
        RuntimeProvider first = provider("dotnet", "First .NET host", Set.of(PluginAbi.ABI_1));
        RuntimeProvider replacement = provider("dotnet", "Replacement .NET host", Set.of(PluginAbi.ABI_2));
        registry.register(first);

        registry.unregister(" DOTNET ");
        registry.register(replacement);

        assertEquals(2, registry.size());
        assertSame(replacement, registry.find("dotnet").orElseThrow());
    }

    /// Exposes the registered provider contract through canonical lookup.
    @Test
    public void findRuntimeProviderContract() {
        RuntimeProviderRegistry registry = new RuntimeProviderRegistry();
        registry.register(provider("dotnet", "Test .NET host", Set.of(PluginAbi.ABI_1)));

        RuntimeProvider found = registry.find(" DOTNET ").orElseThrow();

        assertEquals("dotnet", found.runtimeType());
        assertEquals("Test .NET host", found.describe());
        assertTrue(found.supportsAbi(PluginAbi.ABI_1));
        assertFalse(found.supportsAbi(PluginAbi.ABI_2));
    }

    /// Keys provider descriptions by the canonical identifiers stored in the registry.
    @Test
    public void describeRuntimeProvidersWithCanonicalRegistryIds() {
        RuntimeProviderRegistry registry = new RuntimeProviderRegistry();
        registry.register(provider(" PYTHON ", "Test Python host", Set.of(PluginAbi.ABI_1)));

        @Unmodifiable Map<String, String> descriptions = registry.describeAll();

        assertEquals("Test Python host", descriptions.get("python"));
        assertFalse(descriptions.containsKey(" PYTHON "));
    }

    /// Returns an immutable provider-description snapshot independent of later registry changes.
    @Test
    public void describeRuntimeProvidersAsImmutableSnapshot() {
        RuntimeProviderRegistry registry = new RuntimeProviderRegistry();
        registry.register(provider("dotnet", "Test .NET host", Set.of(PluginAbi.ABI_1)));
        @Unmodifiable Map<String, String> descriptions = registry.describeAll();

        registry.unregister("dotnet");

        assertEquals("Test .NET host", descriptions.get("dotnet"));
        assertThrows(UnsupportedOperationException.class,
                () -> descriptions.put("python", "Mutable description"));
    }

    /// Verifies the risk tier assigned to each declared permission.
    @Test
    public void permissionTierClassification() {
        assertEquals(PluginPermissionTier.NORMAL, PluginPermissionTier.tierOf(PluginPermission.LAUNCHER_UI));
        assertEquals(PluginPermissionTier.NORMAL, PluginPermissionTier.tierOf(PluginPermission.GAME_LAUNCH));
        assertEquals(PluginPermissionTier.NORMAL, PluginPermissionTier.tierOf(PluginPermission.CLIPBOARD));
        assertEquals(PluginPermissionTier.ADVANCED, PluginPermissionTier.tierOf(PluginPermission.FILESYSTEM));
        assertEquals(PluginPermissionTier.ADVANCED, PluginPermissionTier.tierOf(PluginPermission.NETWORK));
        assertEquals(PluginPermissionTier.ADVANCED, PluginPermissionTier.tierOf(PluginPermission.PROCESS));
        assertEquals(PluginPermissionTier.ADVANCED, PluginPermissionTier.tierOf(PluginPermission.ACCOUNT));
        assertEquals(PluginPermissionTier.DANGEROUS, PluginPermissionTier.tierOf(PluginPermission.MIXIN));
        assertEquals(PluginPermissionTier.DANGEROUS, PluginPermissionTier.tierOf(PluginPermission.NATIVE_CODE));
        assertEquals(PluginPermissionTier.DANGEROUS, PluginPermissionTier.tierOf(PluginPermission.LAUNCHER_HOOK));
        assertEquals(PluginPermissionTier.DANGEROUS, PluginPermissionTier.tierOf(PluginPermission.LAUNCHER_PATCH));
    }

    /// Derives the highest capability tier from hook and patch declarations.
    @Test
    public void capabilityLevelDerivation() {
        assertEquals(PluginCapabilityLevel.API, PluginCapabilityLevel.of(false, false));
        assertEquals(PluginCapabilityLevel.HOOK, PluginCapabilityLevel.of(true, false));
        assertEquals(PluginCapabilityLevel.PATCH, PluginCapabilityLevel.of(false, true));
        assertEquals(PluginCapabilityLevel.PATCH, PluginCapabilityLevel.of(true, true));
    }

    /// Creates a runtime provider with the supplied immutable test contract.
    ///
    /// @param runtimeType provider runtime identifier
    /// @param description provider diagnostic description
    /// @param implementedAbis ABI generations implemented by the provider
    /// @return runtime provider exposing the supplied values
    private static RuntimeProvider provider(
            String runtimeType,
            String description,
            @Unmodifiable Set<Integer> implementedAbis) {
        return new RuntimeProvider() {
            /// Returns the configured runtime identifier.
            @Override
            public String runtimeType() {
                return runtimeType;
            }

            /// Returns the configured ABI generations.
            @Override
            public @Unmodifiable Set<Integer> implementedPluginAbis() {
                return implementedAbis;
            }

            /// Returns the configured diagnostic description.
            @Override
            public String describe() {
                return description;
            }
        };
    }
}
