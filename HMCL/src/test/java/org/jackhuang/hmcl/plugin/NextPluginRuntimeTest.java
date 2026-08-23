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

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    }
}
