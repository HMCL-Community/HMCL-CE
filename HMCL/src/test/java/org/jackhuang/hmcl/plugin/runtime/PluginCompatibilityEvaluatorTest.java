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
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies shared plugin compatibility requirements and ordered compatibility diagnostics.
@NotNullByDefault
public final class PluginCompatibilityEvaluatorTest {
    /// Evaluates only the bound Provider and never substitutes another compatible candidate.
    @Test
    public void rejectIncompatibleBoundProviderWithoutCandidateFallback() {
        RuntimeProviderRegistry registry = new RuntimeProviderRegistry();
        registry.register(provider("dev.host.rust.bound", "rust", 1,
                Set.of(PluginExecutionMode.ISOLATED), Set.of(RuntimeFeature.BRIDGE)));
        registry.register(provider("dev.host.rust.fallback", "rust", 1,
                Set.of(PluginExecutionMode.EMBEDDED), Set.of(RuntimeFeature.BRIDGE)));
        PluginCompatibilityEvaluator evaluator = new PluginCompatibilityEvaluator(
                registry, PluginPlatformTarget.parse("windows"));
        PluginCompatibilityRequirements requirements = requirements(new RuntimeRequirement(
                "rust", PluginAbi.ABI_2, 1, PluginExecutionMode.EMBEDDED,
                Set.of(RuntimeFeature.BRIDGE), null));

        assertStatus(PluginCompatibilityStatus.UNSUPPORTED_EXECUTION_MODE,
                evaluator.evaluateForProvider(requirements, "26.8", "dev.host.rust.bound"));
    }

    /// Shares one mutable provider registry and evaluator across production compatibility consumers.
    @Test
    public void exposeProcessWideCompatibilityServices() {
        String runtimeType = "process-wide-test";
        RuntimeProviderRegistry registry = RuntimeProviderRegistry.processWide();
        PluginCompatibilityEvaluator evaluator = PluginCompatibilityEvaluator.processWide();
        PluginCompatibilityRequirements requirements = new PluginCompatibilityRequirements(
                5, "*", runtimeType, PluginAbi.ABI_1, List.of());
        registry.unregister(runtimeType);

        assertSame(registry, RuntimeProviderRegistry.processWide());
        assertSame(evaluator, PluginCompatibilityEvaluator.processWide());
        assertStatus(PluginCompatibilityStatus.MISSING_RUNTIME, evaluator.evaluate(requirements, "26.8"));

        try {
            registry.register(provider(runtimeType, Set.of(PluginAbi.ABI_1)));
            assertStatus(PluginCompatibilityStatus.COMPATIBLE, evaluator.evaluate(requirements, "26.8"));
        } finally {
            registry.unregister(runtimeType);
        }
    }

    /// Defensively copies platform requirements and exposes them as an immutable list.
    @Test
    public void copyRequiredPlatformsDefensively() {
        List<PluginPlatformTarget> platforms = new ArrayList<>();
        platforms.add(PluginPlatformTarget.parse("windows"));
        PluginCompatibilityRequirements requirements = new PluginCompatibilityRequirements(
                5, "*", PluginRuntimeTypes.JAVA, PluginAbi.ABI_2, platforms);

        platforms.clear();

        assertEquals(List.of(PluginPlatformTarget.parse("windows")), requirements.platforms());
        assertThrows(UnsupportedOperationException.class,
                () -> requirements.platforms().add(PluginPlatformTarget.parse("linux")));
    }

    /// Canonicalizes launcher and runtime identifiers when requirements are constructed.
    @Test
    public void canonicalizeRequirementIdentifiers() {
        PluginCompatibilityRequirements requirements = new PluginCompatibilityRequirements(
                5, " >=26.8 ", " JAVA ", PluginAbi.ABI_2, List.of());

        assertEquals(">=26.8", requirements.launcherVersion());
        assertEquals(PluginRuntimeTypes.JAVA, requirements.runtime());
    }

    /// Derives legacy executable compatibility defaults through schema-v4 manifest getters.
    ///
    /// @throws IOException if the valid test manifest cannot be parsed
    @Test
    public void deriveSchemaFourRequirementsFromManifest() throws IOException {
        PluginManifest manifest = PluginManifest.fromJson(new StringReader("""
                {
                  "schemaVersion": 4,
                  "id": "dev.hmclce.test.compatibility-v4",
                  "name": "Compatibility V4",
                  "version": "1.0.0",
                  "type": "java",
                  "entrypoint": "dev.hmclce.test.Plugin",
                  "permissions": [],
                  "requiredPermissions": [],
                  "launcherVersion": ">=26.8"
                }
                """));

        PluginCompatibilityRequirements requirements = PluginCompatibilityRequirements.fromManifest(manifest);

        assertEquals(4, requirements.schemaVersion());
        assertEquals(">=26.8", requirements.launcherVersion());
        assertEquals(PluginRuntimeTypes.JAVA, requirements.runtime());
        assertEquals(PluginAbi.ABI_1, requirements.abi());
        assertEquals(List.of(), requirements.platforms());
    }

    /// Derives explicit schema-v5 runtime, ABI, and platform requirements.
    ///
    /// @throws IOException if the valid test manifest cannot be parsed
    @Test
    public void deriveSchemaFiveRequirementsFromManifest() throws IOException {
        PluginManifest manifest = PluginManifest.fromJson(new StringReader("""
                {
                  "schemaVersion": 5,
                  "id": "dev.hmclce.test.compatibility-v5",
                  "name": "Compatibility V5",
                  "version": "1.0.0",
                  "type": "java",
                  "entrypoint": "dev.hmclce.test.Plugin",
                  "permissions": [],
                  "requiredPermissions": [],
                  "launcherVersion": "*",
                  "runtime": "java",
                  "abi": 2,
                  "platforms": ["windows"]
                }
                """));

        PluginCompatibilityRequirements requirements = PluginCompatibilityRequirements.fromManifest(manifest);

        assertEquals(5, requirements.schemaVersion());
        assertEquals("*", requirements.launcherVersion());
        assertEquals(PluginRuntimeTypes.JAVA, requirements.runtime());
        assertEquals(PluginAbi.ABI_2, requirements.abi());
        assertEquals(List.of(PluginPlatformTarget.parse("windows")), requirements.platforms());
    }

    /// Recognizes only the compatible status as a successful compatibility result.
    @Test
    public void recognizeCompatibleResult() {
        PluginCompatibilityResult compatible = new PluginCompatibilityResult(
                PluginCompatibilityStatus.COMPATIBLE, "All requirements satisfied");
        PluginCompatibilityResult incompatible = new PluginCompatibilityResult(
                PluginCompatibilityStatus.MISSING_RUNTIME, "Missing runtime dotnet");

        assertTrue(compatible.isCompatible());
        assertFalse(incompatible.isCompatible());
    }

    /// Rejects null compatibility result components at construction time.
    @Test
    public void rejectNullCompatibilityResultComponents() {
        assertThrows(NullPointerException.class,
                () -> new PluginCompatibilityResult(null, "Specific detail"));
        assertThrows(NullPointerException.class,
                () -> new PluginCompatibilityResult(PluginCompatibilityStatus.COMPATIBLE, null));
    }

    /// Accepts requirements satisfied by the launcher, host platform, and built-in runtime.
    @Test
    public void evaluateCompatibleRequirements() {
        PluginCompatibilityEvaluator evaluator = new PluginCompatibilityEvaluator(
                new RuntimeProviderRegistry(), PluginPlatformTarget.parse("windows"));
        PluginCompatibilityRequirements requirements = new PluginCompatibilityRequirements(
                5, "*", PluginRuntimeTypes.JAVA, PluginAbi.ABI_1, List.of());

        PluginCompatibilityResult result = evaluator.evaluate(requirements, "26.8");

        assertStatus(PluginCompatibilityStatus.COMPATIBLE, result);
        assertTrue(result.isCompatible());
    }

    /// Preserves schema-v5 Java Hook and Patch compatibility through the reserved built-in provider.
    @Test
    public void acceptBuiltInJavaRuntimeFeatures() {
        PluginCompatibilityEvaluator evaluator = new PluginCompatibilityEvaluator(
                new RuntimeProviderRegistry(), PluginPlatformTarget.parse("windows"));
        PluginCompatibilityRequirements requirements = requirements(new RuntimeRequirement(
                PluginRuntimeTypes.JAVA,
                PluginAbi.ABI_2,
                1,
                PluginExecutionMode.EMBEDDED,
                Set.of(RuntimeFeature.BRIDGE, RuntimeFeature.HOOKS, RuntimeFeature.PATCHES),
                null
        ));

        assertStatus(PluginCompatibilityStatus.COMPATIBLE, evaluator.evaluate(requirements, "26.8"));
    }

    /// Rejects manifest schemas below or above the launcher's executable range.
    @Test
    public void rejectUnsupportedManifestSchema() {
        PluginCompatibilityEvaluator evaluator = new PluginCompatibilityEvaluator(
                new RuntimeProviderRegistry(), PluginPlatformTarget.parse("windows"));
        PluginCompatibilityRequirements legacy = new PluginCompatibilityRequirements(
                3, "*", PluginRuntimeTypes.JAVA, PluginAbi.ABI_1, List.of());
        PluginCompatibilityRequirements future = new PluginCompatibilityRequirements(
                6, "*", PluginRuntimeTypes.JAVA, PluginAbi.ABI_1, List.of());

        PluginCompatibilityResult legacyResult = evaluator.evaluate(legacy, "26.8");
        PluginCompatibilityResult futureResult = evaluator.evaluate(future, "26.8");

        assertStatus(PluginCompatibilityStatus.UNSUPPORTED_SCHEMA, legacyResult);
        assertStatus(PluginCompatibilityStatus.UNSUPPORTED_SCHEMA, futureResult);
        assertTrue(legacyResult.detail().contains("3"));
        assertTrue(futureResult.detail().contains("6"));
        assertTrue(legacyResult.detail().contains("4..5"));
    }

    /// Rejects a launcher version that does not satisfy the package constraint.
    @Test
    public void rejectUnsupportedLauncherVersion() {
        PluginCompatibilityEvaluator evaluator = new PluginCompatibilityEvaluator(
                new RuntimeProviderRegistry(), PluginPlatformTarget.parse("windows"));
        PluginCompatibilityRequirements requirements = new PluginCompatibilityRequirements(
                5, ">=27", PluginRuntimeTypes.JAVA, PluginAbi.ABI_1, List.of());

        PluginCompatibilityResult result = evaluator.evaluate(requirements, "26.8");

        assertStatus(PluginCompatibilityStatus.UNSUPPORTED_LAUNCHER, result);
        assertTrue(result.detail().contains(">=27"));
        assertTrue(result.detail().contains("26.8"));
    }

    /// Rejects an architecture-specific package when the host architecture is unknown.
    @Test
    public void rejectArchitectureTargetForUnknownHostArchitecture() {
        PluginCompatibilityEvaluator evaluator = new PluginCompatibilityEvaluator(
                new RuntimeProviderRegistry(), PluginPlatformTarget.parse("windows"));
        PluginCompatibilityRequirements requirements = new PluginCompatibilityRequirements(
                5,
                "*",
                PluginRuntimeTypes.JAVA,
                PluginAbi.ABI_1,
                List.of(PluginPlatformTarget.parse("windows-x64"))
        );

        PluginCompatibilityResult result = evaluator.evaluate(requirements, "26.8");

        assertStatus(PluginCompatibilityStatus.UNSUPPORTED_PLATFORM, result);
        assertTrue(result.detail().contains("windows-x64"));
        assertTrue(result.detail().contains("host windows"));
    }

    /// Accepts unrestricted and operating-system-only packages when host architecture is unknown.
    @Test
    public void acceptUnrestrictedAndOsOnlyTargetsForUnknownHostArchitecture() {
        PluginCompatibilityEvaluator evaluator = new PluginCompatibilityEvaluator(
                new RuntimeProviderRegistry(), PluginPlatformTarget.parse("windows"));
        PluginCompatibilityRequirements unrestricted = new PluginCompatibilityRequirements(
                5, "*", PluginRuntimeTypes.JAVA, PluginAbi.ABI_1, List.of());
        PluginCompatibilityRequirements osOnly = new PluginCompatibilityRequirements(
                5,
                "*",
                PluginRuntimeTypes.JAVA,
                PluginAbi.ABI_1,
                List.of(PluginPlatformTarget.parse("windows"))
        );

        PluginCompatibilityResult unrestrictedResult = evaluator.evaluate(unrestricted, "26.8");
        PluginCompatibilityResult osOnlyResult = evaluator.evaluate(osOnly, "26.8");

        assertStatus(PluginCompatibilityStatus.COMPATIBLE, unrestrictedResult);
        assertStatus(PluginCompatibilityStatus.COMPATIBLE, osOnlyResult);
    }

    /// Rejects a package when no provider is registered for its canonical runtime.
    @Test
    public void rejectMissingRuntimeProvider() {
        PluginCompatibilityEvaluator evaluator = new PluginCompatibilityEvaluator(
                new RuntimeProviderRegistry(), PluginPlatformTarget.parse("windows"));
        PluginCompatibilityRequirements requirements = new PluginCompatibilityRequirements(
                5, "*", "dotnet", PluginAbi.ABI_1, List.of());

        PluginCompatibilityResult result = evaluator.evaluate(requirements, "26.8");

        assertStatus(PluginCompatibilityStatus.MISSING_RUNTIME, result);
        assertTrue(result.detail().contains("dotnet"));
    }

    /// Rejects an ABI generation unsupported by the registered runtime provider.
    @Test
    public void rejectUnsupportedRuntimeAbi() {
        RuntimeProviderRegistry registry = new RuntimeProviderRegistry();
        registry.register(provider("dotnet", Set.of(PluginAbi.ABI_1)));
        PluginCompatibilityEvaluator evaluator = new PluginCompatibilityEvaluator(
                registry, PluginPlatformTarget.parse("windows"));
        PluginCompatibilityRequirements requirements = new PluginCompatibilityRequirements(
                5, "*", "dotnet", PluginAbi.ABI_2, List.of());

        PluginCompatibilityResult result = evaluator.evaluate(requirements, "26.8");

        assertStatus(PluginCompatibilityStatus.UNSUPPORTED_ABI, result);
        assertTrue(result.detail().contains("dotnet"));
        assertTrue(result.detail().contains(Integer.toString(PluginAbi.ABI_2)));
        assertTrue(result.detail().contains("provider implements ABIs [1]"));
    }

    /// Reports execution-mode incompatibility independently from runtime and ABI availability.
    @Test
    public void rejectUnsupportedRuntimeExecutionMode() {
        RuntimeProviderRegistry registry = new RuntimeProviderRegistry();
        registry.register(provider("dev.host.rust.mode", "rust", 1,
                Set.of(PluginExecutionMode.ISOLATED), Set.of(RuntimeFeature.BRIDGE)));
        PluginCompatibilityEvaluator evaluator = new PluginCompatibilityEvaluator(
                registry, PluginPlatformTarget.parse("windows"));
        PluginCompatibilityRequirements requirements = requirements(new RuntimeRequirement(
                "rust", PluginAbi.ABI_2, 1, PluginExecutionMode.EMBEDDED,
                Set.of(RuntimeFeature.BRIDGE), null));

        assertStatus(PluginCompatibilityStatus.UNSUPPORTED_EXECUTION_MODE,
                evaluator.evaluate(requirements, "26.8"));
    }

    /// Reports Bridge ABI incompatibility independently from other provider capabilities.
    @Test
    public void rejectUnsupportedRuntimeBridgeAbi() {
        RuntimeProviderRegistry registry = new RuntimeProviderRegistry();
        registry.register(provider("dev.host.rust.bridge", "rust", 2,
                Set.of(PluginExecutionMode.EMBEDDED), Set.of(RuntimeFeature.BRIDGE)));
        PluginCompatibilityEvaluator evaluator = new PluginCompatibilityEvaluator(
                registry, PluginPlatformTarget.parse("windows"));

        assertStatus(PluginCompatibilityStatus.UNSUPPORTED_BRIDGE_ABI,
                evaluator.evaluate(requirements(new RuntimeRequirement(
                        "rust", PluginAbi.ABI_2, 1, PluginExecutionMode.EMBEDDED,
                        Set.of(RuntimeFeature.BRIDGE), null)), "26.8"));
    }

    /// Reports missing runtime features after ABI, mode, and Bridge compatibility succeed.
    @Test
    public void rejectUnsupportedRuntimeFeatures() {
        RuntimeProviderRegistry registry = new RuntimeProviderRegistry();
        registry.register(provider("dev.host.rust.features", "rust", 1,
                Set.of(PluginExecutionMode.EMBEDDED), Set.of(RuntimeFeature.BRIDGE)));
        PluginCompatibilityEvaluator evaluator = new PluginCompatibilityEvaluator(
                registry, PluginPlatformTarget.parse("windows"));

        assertStatus(PluginCompatibilityStatus.UNSUPPORTED_RUNTIME_FEATURE,
                evaluator.evaluate(requirements(new RuntimeRequirement(
                        "rust", PluginAbi.ABI_2, 1, PluginExecutionMode.EMBEDDED,
                        Set.of(RuntimeFeature.BRIDGE, RuntimeFeature.HOOKS), null)), "26.8"));
    }

    /// Treats an absent or incompatible explicit provider pin as a missing provider without fallback.
    @Test
    public void rejectUnavailablePinnedRuntimeProvider() {
        RuntimeProviderRegistry registry = new RuntimeProviderRegistry();
        registry.register(provider("dev.host.rust.other", "rust", 1,
                Set.of(PluginExecutionMode.EMBEDDED), Set.of(RuntimeFeature.BRIDGE)));
        PluginCompatibilityEvaluator evaluator = new PluginCompatibilityEvaluator(
                registry, PluginPlatformTarget.parse("windows"));

        assertStatus(PluginCompatibilityStatus.MISSING_RUNTIME,
                evaluator.evaluate(requirements(new RuntimeRequirement(
                        "rust", PluginAbi.ABI_2, 1, PluginExecutionMode.EMBEDDED,
                        Set.of(RuntimeFeature.BRIDGE), "dev.host.rust.missing")), "26.8"));
    }

    /// Reports only the earliest incompatible dimension in the fixed diagnostic order.
    @Test
    public void prioritizeCompatibilityDiagnostics() {
        RuntimeProviderRegistry registry = new RuntimeProviderRegistry();
        registry.register(provider("dotnet", Set.of(PluginAbi.ABI_1)));
        PluginCompatibilityEvaluator evaluator = new PluginCompatibilityEvaluator(
                registry, PluginPlatformTarget.parse("windows"));

        PluginCompatibilityRequirements invalidSchema = new PluginCompatibilityRequirements(
                3,
                ">=27",
                "python",
                PluginAbi.ABI_2,
                List.of(PluginPlatformTarget.parse("linux"))
        );
        PluginCompatibilityRequirements invalidLauncher = new PluginCompatibilityRequirements(
                5,
                ">=27",
                "python",
                PluginAbi.ABI_2,
                List.of(PluginPlatformTarget.parse("linux"))
        );
        PluginCompatibilityRequirements invalidPlatform = new PluginCompatibilityRequirements(
                5,
                "*",
                "python",
                PluginAbi.ABI_2,
                List.of(PluginPlatformTarget.parse("linux"))
        );
        PluginCompatibilityRequirements missingRuntime = new PluginCompatibilityRequirements(
                5, "*", "python", PluginAbi.ABI_2, List.of());
        PluginCompatibilityRequirements invalidAbi = new PluginCompatibilityRequirements(
                5, "*", "dotnet", PluginAbi.ABI_2, List.of());

        assertStatus(PluginCompatibilityStatus.UNSUPPORTED_SCHEMA,
                evaluator.evaluate(invalidSchema, "26.8"));
        assertStatus(PluginCompatibilityStatus.UNSUPPORTED_LAUNCHER,
                evaluator.evaluate(invalidLauncher, "26.8"));
        assertStatus(PluginCompatibilityStatus.UNSUPPORTED_PLATFORM,
                evaluator.evaluate(invalidPlatform, "26.8"));
        assertStatus(PluginCompatibilityStatus.MISSING_RUNTIME,
                evaluator.evaluate(missingRuntime, "26.8"));
        assertStatus(PluginCompatibilityStatus.UNSUPPORTED_ABI,
                evaluator.evaluate(invalidAbi, "26.8"));
    }

    /// Creates a runtime provider that implements the supplied ABI generations.
    ///
    /// @param runtimeType provider runtime identifier
    /// @param implementedAbis ABI generations implemented by the provider
    /// @return test runtime provider
    private static RuntimeProvider provider(
            String runtimeType,
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

            /// Returns the test provider diagnostic description.
            @Override
            public String describe() {
                return "Test " + runtimeType + " runtime";
            }
        };
    }

    /// Creates a descriptor-backed provider for schema-v5 compatibility diagnostics.
    private static RuntimeProvider provider(
            String providerId,
            String runtime,
            int bridgeAbi,
            @Unmodifiable Set<PluginExecutionMode> modes,
            @Unmodifiable Set<RuntimeFeature> features) {
        RuntimeProviderDescriptor descriptor = new RuntimeProviderDescriptor(
                providerId,
                "1.0.0",
                List.of(new RuntimeProviderDeclaration(
                        runtime, Set.of(PluginAbi.ABI_2), bridgeAbi, modes, features)),
                true,
                true,
                0,
                false
        );
        return new RuntimeProvider() {
            /// Returns the immutable provider descriptor.
            @Override
            public RuntimeProviderDescriptor descriptor() {
                return descriptor;
            }
        };
    }

    /// Wraps one schema-v5 runtime requirement in package-level compatibility requirements.
    private static PluginCompatibilityRequirements requirements(RuntimeRequirement runtimeRequirement) {
        return new PluginCompatibilityRequirements(5, "*", runtimeRequirement, List.of());
    }

    /// Asserts a compatibility status and its required nonempty diagnostic detail.
    ///
    /// @param expectedStatus expected compatibility outcome
    /// @param result actual evaluator result
    private static void assertStatus(
            PluginCompatibilityStatus expectedStatus,
            PluginCompatibilityResult result) {
        assertEquals(expectedStatus, result.status());
        assertFalse(result.detail().isBlank());
    }
}
