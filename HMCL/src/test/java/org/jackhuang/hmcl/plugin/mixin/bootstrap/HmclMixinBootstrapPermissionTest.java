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
package org.jackhuang.hmcl.plugin.mixin.bootstrap;

import org.jackhuang.hmcl.plugin.LifecycleProbePlugin;
import org.jackhuang.hmcl.plugin.PluginManifest;
import org.jackhuang.hmcl.plugin.PluginMutationLock;
import org.jackhuang.hmcl.plugin.internal.PluginPackageVersions;
import org.jackhuang.hmcl.plugin.runtime.PluginAbi;
import org.jackhuang.hmcl.plugin.runtime.PluginCompatibilityEvaluator;
import org.jackhuang.hmcl.plugin.runtime.PluginCompatibilityRequirements;
import org.jackhuang.hmcl.plugin.runtime.PluginPlatformTarget;
import org.jackhuang.hmcl.plugin.runtime.PluginRuntimeTypes;
import org.jackhuang.hmcl.plugin.runtime.RuntimeProvider;
import org.jackhuang.hmcl.plugin.runtime.RuntimeProviderRegistry;
import org.jackhuang.hmcl.plugin.trust.PluginRuntimeTrustGuard;
import org.jackhuang.hmcl.plugin.trust.PluginRuntimeTrustTestSupport;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies that startup-time Mixin discovery fails closed unless the exact package artifact is authorized.
@NotNullByDefault
public final class HmclMixinBootstrapPermissionTest {
    /// Stable plugin ID used by every isolated package fixture.
    private static final String PLUGIN_ID = "dev.hmclce.test.mixin-permission";

    /// Host-visible lifecycle class used only by the explicit parent-classpath collision fixture.
    private static final String HOST_ENTRYPOINT = LifecycleProbePlugin.class.getName();

    /// Mixin configuration resource contributed by the fixture package.
    private static final String MIXIN_CONFIG = "mixins.dev.hmclce.test.permission.json";

    /// Dependency plugin ID used to verify that ungranted artifacts never enter premain.
    private static final String DEPENDENCY_ID = "dev.hmclce.test.mixin-dependency";

    /// Independent configuration declared by the dependency fixture when both plugins are authorized.
    private static final String DEPENDENCY_CONFIG = "mixins.dev.hmclce.test.dependency.json";

    /// Configuration resource intentionally present on the test host class path.
    private static final String HOST_CLASSPATH_CONFIG = "mixins.dev.hmclce.test.host-classpath.json";

    /// Structurally valid but non-canonical dependency ID used to verify portable executable naming.
    private static final String NON_CANONICAL_DEPENDENCY_ID = "Dev.hmclce.test.mixin-dependency";

    /// Prevents startup discovery from observing package, state, and permission files from different transactions.
    ///
    /// @param temporaryDirectory isolated launcher home
    /// @throws Exception if concurrency coordination or lock acquisition fails
    @Test
    public void prepareConfigurationUnderMutationLock(@TempDir Path temporaryDirectory) throws Exception {
        PluginMutationLock mutationLock = new PluginMutationLock(temporaryDirectory);
        CountDownLatch preparationAttempted = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<HmclMixinBootstrap.AgentConfiguration> preparation = mutationLock.call(() -> {
                Future<HmclMixinBootstrap.AgentConfiguration> submitted = executor.submit(() -> {
                    preparationAttempted.countDown();
                    return HmclMixinBootstrap.prepareAgentConfiguration(temporaryDirectory);
                });
                await(preparationAttempted);
                assertThrows(TimeoutException.class, () -> submitted.get(250, TimeUnit.MILLISECONDS));
                return submitted;
            });

            HmclMixinBootstrap.AgentConfiguration configuration = preparation.get();
            assertTrue(configuration.mixinConfigs().isEmpty());
            assertTrue(configuration.registrations().isEmpty());
        } finally {
            executor.shutdownNow();
        }
    }

    /// Blocks every Mixin while an interrupted prepared publication still requires rollback.
    ///
    /// @param temporaryDirectory isolated launcher home
    /// @throws Exception if package, journal, or cache preparation fails
    @Test
    public void blockPreparedTransactionBeforeManagerRecovery(@TempDir Path temporaryDirectory) throws Exception {
        assertTransactionBlocksBootstrap(temporaryDirectory, "prepared");
    }

    /// Blocks every Mixin while a committed publication still requires cleanup and validation.
    ///
    /// @param temporaryDirectory isolated launcher home
    /// @throws Exception if package, journal, or cache preparation fails
    @Test
    public void blockCommittedTransactionBeforeManagerRecovery(@TempDir Path temporaryDirectory) throws Exception {
        assertTransactionBlocksBootstrap(temporaryDirectory, "committed");
    }

    /// Rejects a valid package replacement after the manifest, identity, and extraction snapshot is captured.
    ///
    /// @param temporaryDirectory isolated launcher home
    /// @throws Exception if package creation or cache preparation fails ucepectedly
    @Test
    public void rejectPackageSwapAfterAuthorizationSnapshot(@TempDir Path temporaryDirectory) throws Exception {
        Path packageFile = writePluginPackage(temporaryDirectory, 4, "1.0.1", true);
        try (HmclMixinBootstrap.BootstrapCandidate candidate =
                     HmclMixinBootstrap.capturePluginPackage(packageFile)) {
            writeRawPluginPackage(
                    temporaryDirectory,
                    PLUGIN_ID,
                    requiredSchemaFourManifest(
                            PLUGIN_ID,
                            "1.0.1",
                            "[\"mixin\"]",
                            "[]",
                            ",\n  \"mixins\": [\"" + MIXIN_CONFIG + "\"]"
                    ),
                    List.of(MIXIN_CONFIG, "replacement-marker.txt")
            );

            Path cacheRoot = temporaryDirectory.resolve("plugin-cache");
            Files.createDirectories(cacheRoot);
            IOException failure = assertThrows(
                    IOException.class,
                    () -> HmclMixinBootstrap.prepareVerifiedPluginCache(
                            candidate,
                            cacheRoot
                    )
            );
            assertTrue(failure.getMessage().contains("changed after Mixin authorization snapshot"));
        }
    }

    /// Rejects legacy Mixin packages because their schema cannot declare the required capability.
    ///
    /// @param temporaryDirectory isolated launcher home
    /// @throws Exception if the package fixture cannot be created or inspected
    @Test
    public void rejectLegacyMixinWithoutPermissionDeclaration(@TempDir Path temporaryDirectory) throws Exception {
        writePluginPackage(temporaryDirectory, 2, "1.0.0", false);

        assertMixinDenied(temporaryDirectory);
    }

    /// Rejects an otherwise authorized raw schema-v5 Mixin package targeting another host platform.
    ///
    /// @param temporaryDirectory isolated launcher home
    /// @throws Exception if package, state, grant, or startup inspection fails
    @Test
    public void rejectRawMixinPackageForUnsupportedPlatform(@TempDir Path temporaryDirectory) throws Exception {
        String incompatiblePlatform = PluginPlatformTarget.current().getOperatingSystem().equals("windows")
                ? "linux"
                : "windows";
        writeAuthorizedSchemaFiveMixinPackage(
                temporaryDirectory,
                PluginRuntimeTypes.JAVA,
                PluginAbi.ABI_1,
                "[\"" + incompatiblePlatform + "\"]"
        );

        assertMixinDenied(temporaryDirectory);
    }

    /// Rejects an otherwise authorized raw schema-v5 Mixin package with no runtime provider.
    ///
    /// @param temporaryDirectory isolated launcher home
    /// @throws Exception if package, state, grant, or startup inspection fails
    @Test
    public void rejectRawMixinPackageForMissingRuntime(@TempDir Path temporaryDirectory) throws Exception {
        writeAuthorizedSchemaFiveMixinPackage(
                temporaryDirectory,
                "dotnet",
                PluginAbi.ABI_1,
                "[]"
        );

        assertMixinDenied(temporaryDirectory);
    }

    /// Uses a provider registered through the process-wide registry during default Mixin discovery.
    ///
    /// @param temporaryDirectory isolated launcher home
    /// @throws Exception if package, state, grant, or startup inspection fails
    @Test
    public void acceptProcessWideRuntimeProviderDuringMixinDiscovery(
            @TempDir Path temporaryDirectory
    ) throws Exception {
        String runtimeType = "mixin-shared-test";
        writeAuthorizedSchemaFiveMixinPackage(
                temporaryDirectory,
                runtimeType,
                PluginAbi.ABI_1,
                "[]"
        );
        RuntimeProviderRegistry runtimeProviders = RuntimeProviderRegistry.processWide();
        runtimeProviders.unregister(runtimeType);

        try {
            runtimeProviders.register(runtimeProvider(runtimeType, Set.of(PluginAbi.ABI_1)));
            HmclMixinBootstrap.AgentConfiguration configuration =
                    HmclMixinBootstrap.prepareAgentConfiguration(temporaryDirectory);

            assertEquals(List.of(MIXIN_CONFIG), configuration.mixinConfigs());
            assertEquals(List.of(PLUGIN_ID), configuration.activePluginIds());
        } finally {
            runtimeProviders.unregister(runtimeType);
        }
    }

    /// Rejects an otherwise authorized raw schema-v5 Mixin package requiring an unsupported provider ABI.
    ///
    /// @param temporaryDirectory isolated launcher home
    /// @throws Exception if package, state, grant, or startup inspection fails
    @Test
    public void rejectRawMixinPackageForUnsupportedProviderAbi(@TempDir Path temporaryDirectory) throws Exception {
        writeAuthorizedSchemaFiveMixinPackage(
                temporaryDirectory,
                "dotnet",
                PluginAbi.ABI_2,
                "[]"
        );
        RuntimeProviderRegistry runtimeProviders = new RuntimeProviderRegistry();
        runtimeProviders.register(runtimeProvider("dotnet", Set.of(PluginAbi.ABI_1)));
        PluginCompatibilityEvaluator evaluator = new PluginCompatibilityEvaluator(
                runtimeProviders,
                PluginPlatformTarget.current()
        );

        assertMixinDenied(temporaryDirectory, evaluator);
    }

    /// Rejects a schema-v3 Mixin package until the user records an explicit decision for it.
    ///
    /// @param temporaryDirectory isolated launcher home
    /// @throws Exception if the package fixture cannot be created or inspected
    @Test
    public void rejectDeclaredMixinWithoutGrant(@TempDir Path temporaryDirectory) throws Exception {
        writePluginPackage(temporaryDirectory, 4, "1.0.0", true);

        assertMixinDenied(temporaryDirectory);
    }

    /// Rejects a permission record copied from different package bytes even when ID and version match.
    ///
    /// @param temporaryDirectory isolated launcher home
    /// @throws Exception if the package fixture or permission document cannot be created
    @Test
    public void rejectMixinGrantForDifferentDigest(@TempDir Path temporaryDirectory) throws Exception {
        writePluginPackage(temporaryDirectory, 4, "1.0.0", true);
        writeMixinGrant(temporaryDirectory, PLUGIN_ID, "1.0.0", "0".repeat(64));

        assertMixinDenied(temporaryDirectory);
    }

    /// Enables Mixin only when declaration and grant match the complete installed package artifact.
    ///
    /// @param temporaryDirectory isolated launcher home
    /// @throws Exception if the package fixture, permission document, or cache cannot be created
    @Test
    public void allowExactGrantedMixinArtifact(@TempDir Path temporaryDirectory) throws Exception {
        Path packageFile = writePluginPackage(temporaryDirectory, 4, "1.0.1", true);
        writeMixinGrant(
                temporaryDirectory,
                PLUGIN_ID,
                "1.0.1",
                PluginPackageVersions.calculateSha256(packageFile)
        );

        HmclMixinBootstrap.AgentConfiguration configuration =
                HmclMixinBootstrap.prepareAgentConfiguration(temporaryDirectory);

        assertEquals(List.of(MIXIN_CONFIG), configuration.mixinConfigs());
        assertEquals(List.of(PLUGIN_ID), configuration.activePluginIds());
        assertFalse(configuration.classPathEntries().isEmpty());
    }

    /// Blocks a revoked certified artifact before any NPL JAR enters the premain Agent class path.
    ///
    /// @param temporaryDirectory isolated launcher home
    /// @throws Exception if package, permission, or signed receipt fixture creation fails
    @Test
    public void blockRevokedCertifiedArtifactBeforeAgentClasspath(
            @TempDir Path temporaryDirectory
    ) throws Exception {
        Path packageFile = writePluginPackage(temporaryDirectory, 4, "1.0.1", true);
        String sha256 = PluginPackageVersions.calculateSha256(packageFile);
        writeMixinGrant(temporaryDirectory, PLUGIN_ID, "1.0.1", sha256);
        PluginRuntimeTrustGuard runtimeTrustGuard = PluginRuntimeTrustTestSupport.revokedArtifactGuard(
                PLUGIN_ID,
                "1.0.1",
                sha256,
                Files.size(packageFile)
        );

        HmclMixinBootstrap.AgentConfiguration configuration =
                HmclMixinBootstrap.prepareAgentConfiguration(temporaryDirectory, runtimeTrustGuard);

        assertTrue(configuration.mixinConfigs().isEmpty());
        assertTrue(configuration.classPathEntries().isEmpty());
        assertTrue(configuration.registrations().isEmpty());
    }

    /// Allows a schema-v4 Mixin artifact when every required permission is granted even though an optional
    /// capability remains denied.
    ///
    /// @param temporaryDirectory isolated launcher home
    /// @throws Exception if package, state, permission, or cache creation fails
    @Test
    public void allowSchemaFourMixinWithDeniedOptionalPermission(
            @TempDir Path temporaryDirectory
    ) throws Exception {
        Path packageFile = writeRawPluginPackage(
                temporaryDirectory,
                PLUGIN_ID,
                schemaFourManifest(
                        PLUGIN_ID,
                        "1.0.1",
                        "[\"mixin\",\"launcher-ui\"]",
                        "[\"mixin\"]",
                        "[]",
                        "*",
                        ",\n  \"mixins\": [\"" + MIXIN_CONFIG + "\"]"
                ),
                List.of(MIXIN_CONFIG)
        );
        writeEnabledPlugins(temporaryDirectory, List.of(PLUGIN_ID));
        writeMixinGrant(
                temporaryDirectory,
                PLUGIN_ID,
                "1.0.1",
                PluginPackageVersions.calculateSha256(packageFile)
        );

        HmclMixinBootstrap.AgentConfiguration configuration =
                HmclMixinBootstrap.prepareAgentConfiguration(temporaryDirectory);

        assertEquals(List.of(MIXIN_CONFIG), configuration.mixinConfigs());
        assertEquals(List.of(PLUGIN_ID), configuration.activePluginIds());
    }

    /// Rejects a schema-v4 Mixin artifact when its exact decision omits any required permission.
    ///
    /// @param temporaryDirectory isolated launcher home
    /// @throws Exception if package, state, or permission creation fails
    @Test
    public void rejectSchemaFourMixinWithMissingRequiredPermission(
            @TempDir Path temporaryDirectory
    ) throws Exception {
        Path packageFile = writeRawPluginPackage(
                temporaryDirectory,
                PLUGIN_ID,
                schemaFourManifest(
                        PLUGIN_ID,
                        "1.0.1",
                        "[\"mixin\",\"launcher-ui\"]",
                        "[\"mixin\",\"launcher-ui\"]",
                        "[]",
                        "*",
                        ",\n  \"mixins\": [\"" + MIXIN_CONFIG + "\"]"
                ),
                List.of(MIXIN_CONFIG)
        );
        writeEnabledPlugins(temporaryDirectory, List.of(PLUGIN_ID));
        writeMixinGrant(
                temporaryDirectory,
                PLUGIN_ID,
                "1.0.1",
                PluginPackageVersions.calculateSha256(packageFile)
        );

        assertMixinDenied(temporaryDirectory);
    }

    /// Blocks a Mixin owner when a schema-v4 ordinary dependency has no exact decision for its required capability.
    ///
    /// @param temporaryDirectory isolated launcher home
    /// @throws Exception if package, state, permission, or startup preparation fails
    @Test
    public void rejectMixinWhenSchemaFourDependencyRequiredPermissionIsMissing(
            @TempDir Path temporaryDirectory
    ) throws Exception {
        writeRawPluginPackage(
                temporaryDirectory,
                DEPENDENCY_ID,
                schemaFourManifest(
                        DEPENDENCY_ID,
                        "1.0.0",
                        "[\"filesystem\"]",
                        "[\"filesystem\"]",
                        "[]",
                        "*",
                        ""
                ),
                List.of()
        );
        Path ownerPackage = writeMixinOwnerWithDependency(
                temporaryDirectory,
                DEPENDENCY_ID,
                "1.0.0"
        );
        writeEnabledPlugins(temporaryDirectory, List.of(PLUGIN_ID, DEPENDENCY_ID));
        writeMixinGrant(
                temporaryDirectory,
                PLUGIN_ID,
                "1.0.1",
                PluginPackageVersions.calculateSha256(ownerPackage)
        );

        assertMixinDenied(temporaryDirectory);
    }

    /// Applies schema-v4 launcher constraints identically to formal and snapshot launcher versions.
    ///
    /// @throws IOException if the generated manifest is invalid
    @Test
    public void evaluateSchemaFourLauncherConstraint() throws IOException {
        PluginManifest manifest = PluginManifest.fromJson(new StringReader(schemaFourManifest(
                PLUGIN_ID,
                "1.0.1",
                "[\"mixin\"]",
                "[\"mixin\"]",
                "[]",
                ">=99.0",
                ",\n  \"mixins\": [\"" + MIXIN_CONFIG + "\"]"
        )));
        PluginCompatibilityEvaluator evaluator = new PluginCompatibilityEvaluator(
                new RuntimeProviderRegistry(),
                PluginPlatformTarget.current()
        );
        PluginCompatibilityRequirements requirements = PluginCompatibilityRequirements.fromManifest(manifest);

        assertFalse(evaluator.evaluate(requirements, "26.8-beta.3").isCompatible());
        assertFalse(evaluator.evaluate(requirements, "26.8-beta.SNAPSHOT").isCompatible());
        assertTrue(evaluator.evaluate(requirements, "99.0").isCompatible());
    }

    /// Keeps an executable ordinary dependency outside premain even when it packages the owner's config name.
    ///
    /// @param temporaryDirectory isolated launcher home
    /// @throws Exception if package, state, permission, or cache creation fails
    @Test
    public void excludeOrdinaryDependencyFromPremainClassPath(@TempDir Path temporaryDirectory) throws Exception {
        writeRawPluginPackage(
                temporaryDirectory,
                DEPENDENCY_ID,
                requiredSchemaFourManifest(DEPENDENCY_ID, "1.0.0", "[]", "[]", ""),
                List.of(MIXIN_CONFIG)
        );
        Path ownerPackage = writeRawPluginPackage(
                temporaryDirectory,
                PLUGIN_ID,
                requiredSchemaFourManifest(
                        PLUGIN_ID,
                        "1.0.1",
                        "[\"mixin\"]",
                        "[{\"id\":\"" + DEPENDENCY_ID + "\",\"version\":\"1.0.0\"}]",
                        ",\n  \"mixins\": [\"" + MIXIN_CONFIG + "\"]"
                ),
                List.of(MIXIN_CONFIG)
        );
        writeEnabledPlugins(temporaryDirectory, List.of(PLUGIN_ID, DEPENDENCY_ID));
        writeMixinGrant(
                temporaryDirectory,
                PLUGIN_ID,
                "1.0.1",
                PluginPackageVersions.calculateSha256(ownerPackage)
        );

        HmclMixinBootstrap.AgentConfiguration configuration =
                HmclMixinBootstrap.prepareAgentConfiguration(temporaryDirectory);

        assertEquals(List.of(PLUGIN_ID), configuration.activePluginIds());
        assertTrue(configuration.classPathEntries().stream()
                .noneMatch(path -> path.toString().contains(DEPENDENCY_ID)));
    }

    /// Includes both Mixin artifacts only when the complete dependency closure is executable and authorized.
    ///
    /// @param temporaryDirectory isolated launcher home
    /// @throws Exception if package, state, permission, or cache creation fails
    @Test
    public void allowCompleteAuthorizedMixinDependencyClosure(@TempDir Path temporaryDirectory) throws Exception {
        Path dependencyPackage = writeRawPluginPackage(
                temporaryDirectory,
                DEPENDENCY_ID,
                requiredSchemaFourManifest(
                        DEPENDENCY_ID,
                        "1.0.0",
                        "[\"mixin\"]",
                        "[]",
                        ",\n  \"mixins\": [\"" + DEPENDENCY_CONFIG + "\"]"
                ),
                List.of(DEPENDENCY_CONFIG)
        );
        Path ownerPackage = writeMixinOwnerWithDependency(
                temporaryDirectory,
                DEPENDENCY_ID,
                "1.0.0"
        );
        writeEnabledPlugins(temporaryDirectory, List.of(PLUGIN_ID, DEPENDENCY_ID));
        writeTwoMixinGrants(
                temporaryDirectory,
                PluginPackageVersions.calculateSha256(ownerPackage),
                PluginPackageVersions.calculateSha256(dependencyPackage)
        );

        HmclMixinBootstrap.AgentConfiguration configuration =
                HmclMixinBootstrap.prepareAgentConfiguration(temporaryDirectory);

        assertEquals(List.of(DEPENDENCY_CONFIG, MIXIN_CONFIG), configuration.mixinConfigs());
        assertEquals(List.of(DEPENDENCY_ID, PLUGIN_ID), configuration.activePluginIds());
        assertEquals(
                List.of(DEPENDENCY_ID, PLUGIN_ID),
                configuration.registrations().stream()
                        .map(PluginAgentSnapshot.Registration::identity)
                        .map(identity -> identity.getPluginId())
                        .toList()
        );
    }

    /// Blocks a Mixin owner when one enabled dependency uses a legacy manifest schema.
    ///
    /// @param temporaryDirectory isolated launcher home
    /// @throws Exception if package, state, permission, or startup preparation fails
    @Test
    public void rejectMixinWhenDependencyIsLegacy(@TempDir Path temporaryDirectory) throws Exception {
        writeRawPluginPackage(
                temporaryDirectory,
                DEPENDENCY_ID,
                legacyManifest(DEPENDENCY_ID, "1.0.0"),
                List.of()
        );
        Path ownerPackage = writeMixinOwnerWithDependency(
                temporaryDirectory,
                DEPENDENCY_ID,
                "1.0.0"
        );
        writeEnabledPlugins(temporaryDirectory, List.of(PLUGIN_ID, DEPENDENCY_ID));
        writeMixinGrant(
                temporaryDirectory,
                PLUGIN_ID,
                "1.0.1",
                PluginPackageVersions.calculateSha256(ownerPackage)
        );

        assertMixinDenied(temporaryDirectory);
    }

    /// Blocks a Mixin owner when one dependency ID has no portable canonical executable spelling.
    ///
    /// @param temporaryDirectory isolated launcher home
    /// @throws Exception if package, state, permission, or startup preparation fails
    @Test
    public void rejectMixinWhenDependencyIdIsNonCanonical(@TempDir Path temporaryDirectory) throws Exception {
        writeRawPluginPackage(
                temporaryDirectory,
                NON_CANONICAL_DEPENDENCY_ID,
                requiredSchemaFourManifest(NON_CANONICAL_DEPENDENCY_ID, "1.0.0", "[]", "[]", ""),
                List.of()
        );
        Path ownerPackage = writeMixinOwnerWithDependency(
                temporaryDirectory,
                NON_CANONICAL_DEPENDENCY_ID,
                "1.0.0"
        );
        writeEnabledPlugins(temporaryDirectory, List.of(PLUGIN_ID, NON_CANONICAL_DEPENDENCY_ID));
        writeMixinGrant(
                temporaryDirectory,
                PLUGIN_ID,
                "1.0.1",
                PluginPackageVersions.calculateSha256(ownerPackage)
        );

        assertMixinDenied(temporaryDirectory);
    }

    /// Blocks a Mixin owner when a Mixin dependency omits the mandatory `mixin` permission declaration.
    ///
    /// @param temporaryDirectory isolated launcher home
    /// @throws Exception if package, state, permission, or startup preparation fails
    @Test
    public void rejectMixinWhenMixinDependencyOmitsPermission(@TempDir Path temporaryDirectory) throws Exception {
        writeRawPluginPackage(
                temporaryDirectory,
                DEPENDENCY_ID,
                requiredSchemaFourManifest(
                        DEPENDENCY_ID,
                        "1.0.0",
                        "[]",
                        "[]",
                        ",\n  \"mixins\": [\"" + DEPENDENCY_CONFIG + "\"]"
                ),
                List.of(DEPENDENCY_CONFIG)
        );
        Path ownerPackage = writeMixinOwnerWithDependency(
                temporaryDirectory,
                DEPENDENCY_ID,
                "1.0.0"
        );
        writeEnabledPlugins(temporaryDirectory, List.of(PLUGIN_ID, DEPENDENCY_ID));
        writeMixinGrant(
                temporaryDirectory,
                PLUGIN_ID,
                "1.0.1",
                PluginPackageVersions.calculateSha256(ownerPackage)
        );

        assertMixinDenied(temporaryDirectory);
    }

    /// Blocks a Mixin owner when a dependency receives only part of its declared atomic permission set.
    ///
    /// @param temporaryDirectory isolated launcher home
    /// @throws Exception if package, state, permission, or startup preparation fails
    @Test
    public void rejectMixinWhenDependencyPermissionSetIsIncomplete(@TempDir Path temporaryDirectory) throws Exception {
        Path dependencyPackage = writeRawPluginPackage(
                temporaryDirectory,
                DEPENDENCY_ID,
                requiredSchemaFourManifest(
                        DEPENDENCY_ID,
                        "1.0.0",
                        "[\"mixin\",\"launcher-ui\"]",
                        "[]",
                        ",\n  \"mixins\": [\"" + DEPENDENCY_CONFIG + "\"]"
                ),
                List.of(DEPENDENCY_CONFIG)
        );
        Path ownerPackage = writeMixinOwnerWithDependency(
                temporaryDirectory,
                DEPENDENCY_ID,
                "1.0.0"
        );
        writeEnabledPlugins(temporaryDirectory, List.of(PLUGIN_ID, DEPENDENCY_ID));
        writeTwoMixinGrants(
                temporaryDirectory,
                PluginPackageVersions.calculateSha256(ownerPackage),
                PluginPackageVersions.calculateSha256(dependencyPackage)
        );

        assertMixinDenied(temporaryDirectory);
    }

    /// Blocks a Mixin owner when an enabled dependency package is missing.
    ///
    /// @param temporaryDirectory isolated launcher home
    /// @throws Exception if package, state, permission, or startup preparation fails
    @Test
    public void rejectMixinWhenDependencyPackageIsMissing(@TempDir Path temporaryDirectory) throws Exception {
        Path ownerPackage = writeMixinOwnerWithDependency(
                temporaryDirectory,
                DEPENDENCY_ID,
                "1.0.0"
        );
        writeEnabledPlugins(temporaryDirectory, List.of(PLUGIN_ID, DEPENDENCY_ID));
        writeMixinGrant(
                temporaryDirectory,
                PLUGIN_ID,
                "1.0.1",
                PluginPackageVersions.calculateSha256(ownerPackage)
        );

        assertMixinDenied(temporaryDirectory);
    }

    /// Blocks a Mixin owner when the installed dependency version does not satisfy its declared constraint.
    ///
    /// @param temporaryDirectory isolated launcher home
    /// @throws Exception if package, state, permission, or startup preparation fails
    @Test
    public void rejectMixinWhenDependencyVersionDoesNotMatch(@TempDir Path temporaryDirectory) throws Exception {
        writeRawPluginPackage(
                temporaryDirectory,
                DEPENDENCY_ID,
                requiredSchemaFourManifest(DEPENDENCY_ID, "1.0.0", "[]", "[]", ""),
                List.of()
        );
        Path ownerPackage = writeMixinOwnerWithDependency(
                temporaryDirectory,
                DEPENDENCY_ID,
                "2.0.0"
        );
        writeEnabledPlugins(temporaryDirectory, List.of(PLUGIN_ID, DEPENDENCY_ID));
        writeMixinGrant(
                temporaryDirectory,
                PLUGIN_ID,
                "1.0.1",
                PluginPackageVersions.calculateSha256(ownerPackage)
        );

        assertMixinDenied(temporaryDirectory);
    }

    /// Rejects an authorized plugin when another authorized artifact packages its declared config name.
    ///
    /// @param temporaryDirectory isolated launcher home
    /// @throws Exception if package, state, or permission creation fails
    @Test
    public void rejectForeignMixinConfigurationResource(@TempDir Path temporaryDirectory) throws Exception {
        Path ownerPackage = writeRawPluginPackage(
                temporaryDirectory,
                PLUGIN_ID,
                requiredSchemaFourManifest(
                        PLUGIN_ID,
                        "1.0.1",
                        "[\"mixin\"]",
                        "[]",
                        ",\n  \"mixins\": [\"" + MIXIN_CONFIG + "\"]"
                ),
                List.of(MIXIN_CONFIG)
        );
        Path dependencyPackage = writeRawPluginPackage(
                temporaryDirectory,
                DEPENDENCY_ID,
                requiredSchemaFourManifest(
                        DEPENDENCY_ID,
                        "1.0.0",
                        "[\"mixin\"]",
                        "[]",
                        ",\n  \"mixins\": [\"" + DEPENDENCY_CONFIG + "\"]"
                ),
                List.of(DEPENDENCY_CONFIG, MIXIN_CONFIG)
        );
        writeEnabledPlugins(temporaryDirectory, List.of(PLUGIN_ID, DEPENDENCY_ID));
        writeTwoMixinGrants(
                temporaryDirectory,
                PluginPackageVersions.calculateSha256(ownerPackage),
                PluginPackageVersions.calculateSha256(dependencyPackage)
        );

        IOException exception = assertThrows(
                IOException.class,
                () -> HmclMixinBootstrap.prepareAgentConfiguration(temporaryDirectory)
        );

        assertTrue(exception.getMessage().contains("is also present"));
    }

    /// Rejects a plugin configuration that host code could resolve before the Agent appends plugin JARs.
    ///
    /// @param temporaryDirectory isolated launcher home
    /// @throws Exception if package, state, permission, or class-path inspection fails
    @Test
    public void rejectHostClasspathMixinConfigurationResource(@TempDir Path temporaryDirectory) throws Exception {
        Path packageFile = writeRawPluginPackage(
                temporaryDirectory,
                PLUGIN_ID,
                requiredSchemaFourManifest(
                        PLUGIN_ID,
                        "1.0.1",
                        "[\"mixin\"]",
                        "[]",
                        ",\n  \"mixins\": [\"" + HOST_CLASSPATH_CONFIG + "\"]"
                ),
                List.of(HOST_CLASSPATH_CONFIG)
        );
        writeEnabledPlugins(temporaryDirectory, List.of(PLUGIN_ID));
        writeMixinGrant(
                temporaryDirectory,
                PLUGIN_ID,
                "1.0.1",
                PluginPackageVersions.calculateSha256(packageFile)
        );

        IOException exception = assertThrows(
                IOException.class,
                () -> HmclMixinBootstrap.prepareAgentConfiguration(temporaryDirectory)
        );

        assertTrue(exception.getMessage().contains("already visible from the system class path")
                || exception.getMessage().contains("already visible from the HMCL class path"));
    }

    /// Rejects lifecycle bytecode that duplicates a class already visible from the host test class path.
    ///
    /// @param temporaryDirectory isolated launcher home
    /// @throws Exception if package, state, grant, or host-class capture fails
    @Test
    public void rejectHostClasspathAgentClass(@TempDir Path temporaryDirectory) throws Exception {
        String manifest = """
                {
                  "schemaVersion": 4,
                  "id": "%s",
                  "name": "Host Class Collision",
                  "version": "1.0.1",
                  "type": "java",
                  "entrypoint": "%s",
                  "permissions": ["mixin"],
                  "requiredPermissions": ["mixin"],
                  "launcherVersion": "*",
                  "dependencies": [],
                  "mixins": ["%s"]
                }
                """.formatted(PLUGIN_ID, HOST_ENTRYPOINT, MIXIN_CONFIG);
        String classResource = HOST_ENTRYPOINT.replace('.', '/') + ".class";
        byte @Unmodifiable [] hostClassBytes;
        try (InputStream input = Objects.requireNonNull(
                LifecycleProbePlugin.class.getResourceAsStream("/" + classResource),
                "Compiled host lifecycle fixture is unavailable"
        )) {
            hostClassBytes = input.readAllBytes();
        }
        Path packageFile = writeRawPluginPackage(
                temporaryDirectory,
                PLUGIN_ID,
                manifest,
                List.of(MIXIN_CONFIG),
                false,
                Map.of(classResource, hostClassBytes)
        );
        writeEnabledPlugins(temporaryDirectory, List.of(PLUGIN_ID));
        writeMixinGrant(
                temporaryDirectory,
                PLUGIN_ID,
                "1.0.1",
                PluginPackageVersions.calculateSha256(packageFile)
        );

        IOException exception = assertThrows(
                IOException.class,
                () -> HmclMixinBootstrap.prepareAgentConfiguration(temporaryDirectory)
        );

        assertTrue(exception.getMessage().contains("already visible"));
    }

    /// Rejects an auxiliary class name supplied by two different authorized Mixin artifacts.
    ///
    /// @param temporaryDirectory isolated launcher home
    /// @throws Exception if package, state, grant, or generated-class creation fails
    @Test
    public void rejectDuplicateAgentClassAcrossArtifacts(@TempDir Path temporaryDirectory) throws Exception {
        String sharedBinaryName = "dev.hmclce.test.generated.SharedAgentHelper";
        String sharedResource = sharedBinaryName.replace('.', '/') + ".class";
        byte @Unmodifiable [] sharedClass = createLifecycleClassBytes(sharedBinaryName);
        Path ownerPackage = writeRawPluginPackage(
                temporaryDirectory,
                PLUGIN_ID,
                requiredSchemaFourManifest(
                        PLUGIN_ID,
                        "1.0.1",
                        "[\"mixin\"]",
                        "[]",
                        ",\n  \"mixins\": [\"" + MIXIN_CONFIG + "\"]"
                ),
                List.of(MIXIN_CONFIG),
                true,
                Map.of(sharedResource, sharedClass)
        );
        Path dependencyPackage = writeRawPluginPackage(
                temporaryDirectory,
                DEPENDENCY_ID,
                requiredSchemaFourManifest(
                        DEPENDENCY_ID,
                        "1.0.0",
                        "[\"mixin\"]",
                        "[]",
                        ",\n  \"mixins\": [\"" + DEPENDENCY_CONFIG + "\"]"
                ),
                List.of(DEPENDENCY_CONFIG),
                true,
                Map.of(sharedResource, sharedClass)
        );
        writeEnabledPlugins(temporaryDirectory, List.of(PLUGIN_ID, DEPENDENCY_ID));
        writeTwoMixinGrants(
                temporaryDirectory,
                PluginPackageVersions.calculateSha256(ownerPackage),
                PluginPackageVersions.calculateSha256(dependencyPackage)
        );

        IOException exception = assertThrows(
                IOException.class,
                () -> HmclMixinBootstrap.prepareAgentConfiguration(temporaryDirectory)
        );

        assertTrue(exception.getMessage().contains("provided by both"));
    }

    /// Rejects a Mixin configuration whose named class is supplied only by another authorized artifact.
    ///
    /// @param temporaryDirectory isolated launcher home
    /// @throws Exception if package, state, grant, or generated-class creation fails
    @Test
    public void rejectForeignMixinClassReference(@TempDir Path temporaryDirectory) throws Exception {
        String foreignPackage = "dev.hmclce.test.generated.foreign";
        String foreignBinaryName = foreignPackage + ".ForeignMixin";
        String foreignResource = foreignBinaryName.replace('.', '/') + ".class";
        byte @Unmodifiable [] ownerConfig = ("""
                {
                  "package": "%s",
                  "mixins": ["ForeignMixin"]
                }
                """).formatted(foreignPackage).getBytes(StandardCharsets.UTF_8);
        Path ownerPackage = writeRawPluginPackage(
                temporaryDirectory,
                PLUGIN_ID,
                requiredSchemaFourManifest(
                        PLUGIN_ID,
                        "1.0.1",
                        "[\"mixin\"]",
                        "[]",
                        ",\n  \"mixins\": [\"" + MIXIN_CONFIG + "\"]"
                ),
                List.of(),
                true,
                Map.of(MIXIN_CONFIG, ownerConfig)
        );
        Path dependencyPackage = writeRawPluginPackage(
                temporaryDirectory,
                DEPENDENCY_ID,
                requiredSchemaFourManifest(
                        DEPENDENCY_ID,
                        "1.0.0",
                        "[\"mixin\"]",
                        "[]",
                        ",\n  \"mixins\": [\"" + DEPENDENCY_CONFIG + "\"]"
                ),
                List.of(DEPENDENCY_CONFIG),
                true,
                Map.of(foreignResource, createLifecycleClassBytes(foreignBinaryName))
        );
        writeEnabledPlugins(temporaryDirectory, List.of(PLUGIN_ID, DEPENDENCY_ID));
        writeTwoMixinGrants(
                temporaryDirectory,
                PluginPackageVersions.calculateSha256(ownerPackage),
                PluginPackageVersions.calculateSha256(dependencyPackage)
        );

        IOException exception = assertThrows(
                IOException.class,
                () -> HmclMixinBootstrap.prepareAgentConfiguration(temporaryDirectory)
        );

        assertTrue(exception.getMessage().contains("references class outside its exact artifact"));
    }

    /// Rebuilds a content-addressed cache after an extra untrusted JAR is inserted beside verified files.
    ///
    /// @param temporaryDirectory isolated launcher home
    /// @throws Exception if package, permission, cache, or repair creation fails
    @Test
    public void repairTamperedMixinCache(@TempDir Path temporaryDirectory) throws Exception {
        Path packageFile = writePluginPackage(temporaryDirectory, 4, "1.0.1", true);
        writeMixinGrant(
                temporaryDirectory,
                PLUGIN_ID,
                "1.0.1",
                PluginPackageVersions.calculateSha256(packageFile)
        );
        HmclMixinBootstrap.AgentConfiguration first =
                HmclMixinBootstrap.prepareAgentConfiguration(temporaryDirectory);
        Path cacheDirectory = first.classPathEntries().get(0).getParent();
        Files.write(cacheDirectory.resolve("injected.jar"), new byte[]{1, 2, 3});

        HmclMixinBootstrap.AgentConfiguration repaired =
                HmclMixinBootstrap.prepareAgentConfiguration(temporaryDirectory);
        Path repairedCacheDirectory = repaired.classPathEntries().get(0).getParent();

        assertTrue(repaired.classPathEntries().stream()
                .noneMatch(path -> path.getFileName().toString().equals("injected.jar")));
        assertNotEquals(cacheDirectory, repairedCacheDirectory);
        assertTrue(repairedCacheDirectory.getFileName().toString().contains(".repair-"));
        assertTrue(Files.exists(cacheDirectory.resolve("injected.jar")));
        assertFalse(Files.exists(repairedCacheDirectory.resolve("injected.jar")));
    }

    /// Treats a malformed permission document as an empty grant set.
    ///
    /// @param temporaryDirectory isolated launcher home
    /// @throws Exception if package creation or startup inspection fails
    @Test
    public void rejectMixinWhenPermissionDocumentIsMalformed(@TempDir Path temporaryDirectory) throws Exception {
        writePluginPackage(temporaryDirectory, 4, "1.0.0", true);
        Files.writeString(
                temporaryDirectory.resolve("plugin-permissions.json"),
                "{",
                StandardCharsets.UTF_8
        );

        assertMixinDenied(temporaryDirectory);
    }

    /// Treats an oversized but otherwise valid startup state document as empty.
    ///
    /// @param temporaryDirectory isolated launcher home
    /// @throws Exception if package, permission, or state fixture creation fails
    @Test
    public void rejectMixinWhenPluginStateDocumentIsOversized(@TempDir Path temporaryDirectory) throws Exception {
        Path packageFile = writePluginPackage(temporaryDirectory, 4, "1.0.1", true);
        writeMixinGrant(
                temporaryDirectory,
                PLUGIN_ID,
                "1.0.1",
                PluginPackageVersions.calculateSha256(packageFile)
        );
        Files.writeString(
                temporaryDirectory.resolve("plugin-states.json"),
                "{\"enabled\":[\"" + PLUGIN_ID + "\"],\"pendingUninstall\":[],\"padding\":\""
                        + "x".repeat(1024 * 1024) + "\"}",
                StandardCharsets.UTF_8
        );

        assertMixinDenied(temporaryDirectory);
    }

    /// Writes and authorizes one raw schema-v5 Mixin package with explicit compatibility requirements.
    ///
    /// @param localHome isolated launcher home
    /// @param runtime canonical runtime identifier
    /// @param abi required runtime ABI
    /// @param platformsJson raw platform target array
    /// @return created package path
    /// @throws IOException if package, state, or grant creation fails
    private static Path writeAuthorizedSchemaFiveMixinPackage(
            Path localHome,
            String runtime,
            int abi,
            String platformsJson
    ) throws IOException {
        String manifest = """
                {
                  "schemaVersion": 5,
                  "id": "%s",
                  "name": "Mixin Compatibility Test",
                  "version": "1.0.1",
                  "type": "java",
                  "entrypoint": "%s",
                  "permissions": ["mixin"],
                  "requiredPermissions": ["mixin"],
                  "launcherVersion": "*",
                  "runtime": "%s",
                  "abi": %s,
                  "platforms": %s,
                  "dependencies": [],
                  "mixins": ["%s"]
                }
                """.formatted(
                        PLUGIN_ID,
                        entrypointFor(PLUGIN_ID),
                        runtime,
                        abi,
                        platformsJson,
                        MIXIN_CONFIG
                );
        Path packageFile = writeRawPluginPackage(localHome, PLUGIN_ID, manifest, List.of(MIXIN_CONFIG));
        writeEnabledPlugins(localHome, List.of(PLUGIN_ID));
        writeMixinGrant(
                localHome,
                PLUGIN_ID,
                "1.0.1",
                PluginPackageVersions.calculateSha256(packageFile)
        );
        return packageFile;
    }

    /// Creates one enabled `.npl` package with a root Mixin configuration resource.
    ///
    /// @param localHome isolated launcher home
    /// @param schemaVersion manifest schema version
    /// @param version plugin version
    /// @param declareMixinPermission whether the manifest declares the `mixin` capability
    /// @return created package path
    /// @throws IOException if package or enablement state creation fails
    private static Path writePluginPackage(
            Path localHome,
            int schemaVersion,
            String version,
            boolean declareMixinPermission
    ) throws IOException {
        String permissionProperty;
        if (schemaVersion >= 4) {
            permissionProperty = declareMixinPermission
                    ? ",\n  \"permissions\": [\"mixin\"],"
                    + "\n  \"requiredPermissions\": [\"mixin\"],"
                    + "\n  \"launcherVersion\": \"*\""
                    : ",\n  \"permissions\": [],"
                    + "\n  \"requiredPermissions\": [],"
                    + "\n  \"launcherVersion\": \"*\"";
        } else {
            permissionProperty = declareMixinPermission ? ",\n  \"permissions\": [\"mixin\"]" : "";
        }
        String manifest = """
                {
                  "schemaVersion": %s,
                  "id": "%s",
                  "name": "Mixin Permission Test",
                  "version": "%s",
                  "type": "java",
                  "entrypoint": "%s",
                  "mixins": ["%s"]%s
                }
                """.formatted(
                        schemaVersion,
                        PLUGIN_ID,
                        version,
                        entrypointFor(PLUGIN_ID),
                        MIXIN_CONFIG,
                        permissionProperty
                );
        Path packageFile = writeRawPluginPackage(localHome, PLUGIN_ID, manifest, List.of(MIXIN_CONFIG));
        writeEnabledPlugins(localHome, List.of(PLUGIN_ID));
        return packageFile;
    }

    /// Builds a complete API-v4 manifest whose declared permissions are all required.
    ///
    /// @param pluginId manifest plugin ID
    /// @param version manifest version
    /// @param permissionsJson permission array JSON
    /// @param dependenciesJson dependency array JSON
    /// @param optionalProperties additional comma-prefixed root properties
    /// @return complete manifest JSON
    private static String requiredSchemaFourManifest(
            String pluginId,
            String version,
            String permissionsJson,
            String dependenciesJson,
            String optionalProperties
    ) {
        return """
                {
                  "schemaVersion": 4,
                  "id": "%s",
                  "name": "Mixin Permission Test",
                  "version": "%s",
                  "type": "java",
                  "entrypoint": "%s",
                  "permissions": %s,
                  "requiredPermissions": %s,
                  "launcherVersion": "*",
                  "dependencies": %s%s
                }
                """.formatted(
                        pluginId,
                        version,
                        entrypointFor(pluginId),
                        permissionsJson,
                        permissionsJson,
                        dependenciesJson,
                        optionalProperties
                );
    }

    /// Builds a complete schema-v4 manifest from caller-provided JSON fragments.
    ///
    /// @param pluginId manifest plugin ID
    /// @param version manifest version
    /// @param permissionsJson declared permission array JSON
    /// @param requiredPermissionsJson required permission array JSON
    /// @param dependenciesJson dependency array JSON
    /// @param launcherVersion launcher compatibility constraint
    /// @param optionalProperties additional comma-prefixed root properties
    /// @return complete manifest JSON
    private static String schemaFourManifest(
            String pluginId,
            String version,
            String permissionsJson,
            String requiredPermissionsJson,
            String dependenciesJson,
            String launcherVersion,
            String optionalProperties
    ) {
        return """
                {
                  "schemaVersion": 4,
                  "id": "%s",
                  "name": "Mixin Permission Test V4",
                  "version": "%s",
                  "type": "java",
                  "entrypoint": "%s",
                  "permissions": %s,
                  "requiredPermissions": %s,
                  "launcherVersion": "%s",
                  "dependencies": %s%s
                }
                """.formatted(
                        pluginId,
                        version,
                        entrypointFor(pluginId),
                        permissionsJson,
                        requiredPermissionsJson,
                        launcherVersion,
                        dependenciesJson,
                        optionalProperties
                );
    }

    /// Builds one legacy Java manifest that remains installable but must never execute.
    ///
    /// @param pluginId manifest plugin ID
    /// @param version manifest version
    /// @return complete schema-v2 manifest JSON
    private static String legacyManifest(String pluginId, String version) {
        return """
                {
                  "schemaVersion": 2,
                  "id": "%s",
                  "name": "Legacy Dependency Test",
                  "version": "%s",
                  "type": "java",
                  "entrypoint": "dev.hmclce.test.LegacyDependencyPlugin",
                  "dependencies": []
                }
                """.formatted(pluginId, version);
    }

    /// Writes the authorized Mixin owner with one exact dependency version requirement.
    ///
    /// @param localHome isolated launcher home
    /// @param dependencyId required dependency ID
    /// @param dependencyVersion required dependency version constraint
    /// @return created owner package path
    /// @throws IOException if archive creation fails
    private static Path writeMixinOwnerWithDependency(
            Path localHome,
            String dependencyId,
            String dependencyVersion
    ) throws IOException {
        return writeRawPluginPackage(
                localHome,
                PLUGIN_ID,
                requiredSchemaFourManifest(
                        PLUGIN_ID,
                        "1.0.1",
                        "[\"mixin\"]",
                        "[{\"id\":\"" + dependencyId + "\",\"version\":\"" + dependencyVersion + "\"}]",
                        ",\n  \"mixins\": [\"" + MIXIN_CONFIG + "\"]"
                ),
                List.of(MIXIN_CONFIG)
        );
    }

    /// Writes one raw plugin archive with selected root resources and the real test lifecycle entry point.
    ///
    /// @param localHome isolated launcher home
    /// @param pluginId package plugin ID
    /// @param manifest complete manifest JSON
    /// @param resources root resource names to package with empty JSON content
    /// @return created package path
    /// @throws IOException if archive creation fails
    private static Path writeRawPluginPackage(
            Path localHome,
            String pluginId,
            String manifest,
            List<String> resources
    ) throws IOException {
        return writeRawPluginPackage(localHome, pluginId, manifest, resources, true);
    }

    /// Writes one raw plugin archive with optional ownership of the declared JVM lifecycle entry point.
    ///
    /// @param localHome isolated launcher home
    /// @param pluginId package plugin ID
    /// @param manifest complete manifest JSON
    /// @param resources root resource names to package with empty JSON content
    /// @param includeEntrypoint whether the exact package contains the declared lifecycle class
    /// @return created package path
    /// @throws IOException if archive creation fails
    private static Path writeRawPluginPackage(
            Path localHome,
            String pluginId,
            String manifest,
            List<String> resources,
            boolean includeEntrypoint
    ) throws IOException {
        return writeRawPluginPackage(
                localHome,
                pluginId,
                manifest,
                resources,
                includeEntrypoint,
                Map.of()
        );
    }

    /// Writes one raw plugin archive with optional entrypoint generation and exact additional entries.
    ///
    /// @param localHome isolated launcher home
    /// @param pluginId package plugin ID
    /// @param manifest complete manifest JSON
    /// @param resources root resource names to package with empty JSON content
    /// @param includeEntrypoint whether to generate the plugin-specific lifecycle class
    /// @param additionalEntries exact extra resource names and immutable payloads
    /// @return created package path
    /// @throws IOException if archive creation fails
    private static Path writeRawPluginPackage(
            Path localHome,
            String pluginId,
            String manifest,
            List<String> resources,
            boolean includeEntrypoint,
            @Unmodifiable Map<String, byte @Unmodifiable []> additionalEntries
    ) throws IOException {
        Path pluginsDirectory = localHome.resolve("plugins");
        Files.createDirectories(pluginsDirectory);
        Path packageFile = pluginsDirectory.resolve(pluginId + ".npl");
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(packageFile))) {
            writeZipEntry(output, "plugin.json", manifest.getBytes(StandardCharsets.UTF_8));
            for (String resource : resources) {
                writeZipEntry(output, resource, "{}".getBytes(StandardCharsets.UTF_8));
            }
            if (includeEntrypoint) {
                writeLifecycleEntrypoint(output, pluginId);
            }
            for (Map.Entry<String, byte @Unmodifiable []> entry : additionalEntries.entrySet()) {
                writeZipEntry(output, entry.getKey(), entry.getValue());
            }
        }
        return packageFile;
    }

    /// Writes a valid unique lifecycle class that is absent from the host test class path.
    ///
    /// @param output open plugin package stream
    /// @param pluginId package plugin ID used to derive an exclusive binary name
    /// @throws IOException if the generated class cannot be packaged
    private static void writeLifecycleEntrypoint(ZipOutputStream output, String pluginId) throws IOException {
        String binaryName = entrypointFor(pluginId);
        writeZipEntry(
                output,
                binaryName.replace('.', '/') + ".class",
                createLifecycleClassBytes(binaryName)
        );
    }

    /// Generates one minimal JVM class implementing the HMCL plugin lifecycle contract.
    ///
    /// The generated class is inspected for ownership only and is never instantiated by these bootstrap tests.
    ///
    /// @param binaryName generated Java binary name
    /// @return complete class-file bytes
    private static byte @Unmodifiable [] createLifecycleClassBytes(String binaryName) {
        String internalName = binaryName.replace('.', '/');
        ClassWriter writer = new ClassWriter(0);
        writer.visit(
                Opcodes.V17,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL,
                internalName,
                null,
                "java/lang/Object",
                new String[]{"org/jackhuang/hmcl/plugin/Plugin"}
        );

        MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(1, 1);
        constructor.visitEnd();

        writeNoOpLifecycleMethod(
                writer,
                "onLoad",
                "(Lorg/jackhuang/hmcl/plugin/PluginContext;)V",
                2
        );
        writeNoOpLifecycleMethod(writer, "onEnable", "()V", 1);
        writeNoOpLifecycleMethod(writer, "onDisable", "()V", 1);

        MethodVisitor getManifest = writer.visitMethod(
                Opcodes.ACC_PUBLIC,
                "getManifest",
                "()Lorg/jackhuang/hmcl/plugin/PluginManifest;",
                null,
                null
        );
        getManifest.visitCode();
        getManifest.visitInsn(Opcodes.ACONST_NULL);
        getManifest.visitInsn(Opcodes.ARETURN);
        getManifest.visitMaxs(1, 1);
        getManifest.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    /// Writes one public no-op lifecycle method into a generated test entry point.
    ///
    /// @param writer owning class writer
    /// @param name JVM method name
    /// @param descriptor JVM method descriptor
    /// @param localVariables required local-variable slots
    private static void writeNoOpLifecycleMethod(
            ClassWriter writer,
            String name,
            String descriptor,
            int localVariables
    ) {
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, name, descriptor, null, null);
        method.visitCode();
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, localVariables);
        method.visitEnd();
    }

    /// Derives one portable Java binary name unique to a plugin fixture ID.
    ///
    /// @param pluginId package plugin ID
    /// @return generated lifecycle binary name
    private static String entrypointFor(String pluginId) {
        String simpleName = pluginId.toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9_]", "_");
        return "dev.hmclce.test.generated.p_" + simpleName + ".Lifecycle";
    }

    /// Writes the exact set of plugin IDs enabled for one startup fixture.
    ///
    /// @param localHome isolated launcher home
    /// @param pluginIds enabled plugin IDs
    /// @throws IOException if state persistence fails
    private static void writeEnabledPlugins(Path localHome, List<String> pluginIds) throws IOException {
        String enabledJson = pluginIds.stream()
                .map(pluginId -> "\"" + pluginId + "\"")
                .collect(java.util.stream.Collectors.joining(","));
        Files.writeString(
                localHome.resolve("plugin-states.json"),
                "{\"enabled\":[" + enabledJson + "],\"pendingUninstall\":[]}",
                StandardCharsets.UTF_8
        );
    }

    /// Writes one exact artifact-bound Mixin grant.
    ///
    /// @param localHome isolated launcher home
    /// @param pluginId granted plugin ID
    /// @param version granted plugin version
    /// @param sha256 granted package digest
    /// @throws IOException if the permission document cannot be written
    private static void writeMixinGrant(
            Path localHome,
            String pluginId,
            String version,
            String sha256
    ) throws IOException {
        Files.writeString(
                localHome.resolve("plugin-permissions.json"),
                """
                        {
                          "schemaVersion": 1,
                          "grants": {
                            "%s": [
                              {
                                "version": "%s",
                                "sha256": "%s",
                                "permissions": ["mixin"]
                              }
                            ]
                          }
                        }
                        """.formatted(pluginId, version, sha256),
                StandardCharsets.UTF_8
        );
    }

    /// Writes exact Mixin grants for both authorized configuration ownership fixtures.
    ///
    /// @param localHome isolated launcher home
    /// @param ownerSha256 owner package digest
    /// @param dependencySha256 dependency package digest
    /// @throws IOException if the permission document cannot be written
    private static void writeTwoMixinGrants(
            Path localHome,
            String ownerSha256,
            String dependencySha256
    ) throws IOException {
        Files.writeString(
                localHome.resolve("plugin-permissions.json"),
                """
                        {
                          "schemaVersion": 1,
                          "grants": {
                            "%s": [{"version":"1.0.1","sha256":"%s","permissions":["mixin"]}],
                            "%s": [{"version":"1.0.0","sha256":"%s","permissions":["mixin"]}]
                          }
                        }
                        """.formatted(PLUGIN_ID, ownerSha256, DEPENDENCY_ID, dependencySha256),
                StandardCharsets.UTF_8
        );
    }

    /// Asserts that startup preparation exposes no Mixin configuration or plugin class path.
    ///
    /// @param localHome isolated launcher home
    /// @throws IOException if startup discovery fails ucepectedly
    private static void assertMixinDenied(Path localHome) throws IOException {
        HmclMixinBootstrap.AgentConfiguration configuration =
                HmclMixinBootstrap.prepareAgentConfiguration(localHome);

        assertTrue(configuration.mixinConfigs().isEmpty());
        assertTrue(configuration.activePluginIds().isEmpty());
        assertTrue(configuration.classPathEntries().isEmpty());
        assertTrue(configuration.registrations().isEmpty());
    }

    /// Asserts that startup preparation with an explicit evaluator exposes no Agent configuration or class path.
    ///
    /// @param localHome isolated launcher home
    /// @param evaluator explicit compatibility evaluator
    /// @throws IOException if startup discovery fails unexpectedly
    private static void assertMixinDenied(
            Path localHome,
            PluginCompatibilityEvaluator evaluator
    ) throws IOException {
        HmclMixinBootstrap.AgentConfiguration configuration = HmclMixinBootstrap.prepareAgentConfiguration(
                localHome,
                PluginRuntimeTrustGuard.inactive(),
                evaluator
        );

        assertTrue(configuration.mixinConfigs().isEmpty());
        assertTrue(configuration.activePluginIds().isEmpty());
        assertTrue(configuration.classPathEntries().isEmpty());
        assertTrue(configuration.registrations().isEmpty());
    }

    /// Creates a deterministic runtime provider fixture for bootstrap compatibility tests.
    ///
    /// @param runtimeType canonical runtime identifier
    /// @param implementedAbis immutable implemented ABI generations
    /// @return runtime provider fixture
    private static RuntimeProvider runtimeProvider(
            String runtimeType,
            @Unmodifiable Set<Integer> implementedAbis
    ) {
        return new RuntimeProvider() {
            /// Returns the caller-selected runtime identifier.
            @Override
            public String runtimeType() {
                return runtimeType;
            }

            /// Returns the caller-selected immutable ABI set.
            @Override
            public @Unmodifiable Set<Integer> implementedPluginAbis() {
                return Set.copyOf(implementedAbis);
            }

            /// Describes this deterministic test provider.
            @Override
            public String describe() {
                return "Mixin bootstrap compatibility test provider";
            }
        };
    }

    /// Verifies that one unresolved journal phase suppresses otherwise authorized Mixin startup.
    ///
    /// @param localHome isolated launcher home
    /// @param phase serialized transaction phase
    /// @throws IOException if fixture creation or Bootstrap preparation fails
    private static void assertTransactionBlocksBootstrap(Path localHome, String phase) throws IOException {
        Path packageFile = writePluginPackage(localHome, 4, "1.0.1", true);
        writeMixinGrant(
                localHome,
                PLUGIN_ID,
                "1.0.1",
                PluginPackageVersions.calculateSha256(packageFile)
        );
        Path transactionFile = localHome.resolve("plugin-install-transaction.json");
        Files.writeString(
                transactionFile,
                "{\"phase\":\"" + phase + "\"}",
                StandardCharsets.UTF_8
        );

        assertMixinDenied(localHome);
        Files.delete(transactionFile);

        HmclMixinBootstrap.AgentConfiguration recovered =
                HmclMixinBootstrap.prepareAgentConfiguration(localHome);
        assertEquals(List.of(MIXIN_CONFIG), recovered.mixinConfigs());
        assertEquals(List.of(PLUGIN_ID), recovered.activePluginIds());
    }

    /// Waits for a concurrency-test latch while translating interruption into I/O failure.
    ///
    /// @param latch latch to await
    /// @throws IOException if the current thread is interrupted
    private static void await(CountDownLatch latch) throws IOException {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while coordinating Bootstrap preparation", exception);
        }
    }

    /// Adds one byte payload to the package fixture.
    ///
    /// @param output open package output stream
    /// @param name archive entry name
    /// @param bytes entry payload
    /// @throws IOException if the entry cannot be written
    private static void writeZipEntry(
            ZipOutputStream output,
            String name,
            byte @Unmodifiable [] bytes
    ) throws IOException {
        output.putNextEntry(new ZipEntry(name));
        output.write(bytes);
        output.closeEntry();
    }
}
