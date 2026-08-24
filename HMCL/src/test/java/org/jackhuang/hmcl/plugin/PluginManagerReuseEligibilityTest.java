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

import org.jackhuang.hmcl.plugin.internal.PluginPackageVersions;
import org.jackhuang.hmcl.plugin.runtime.PluginAbi;
import org.jackhuang.hmcl.plugin.runtime.PluginCompatibilityEvaluator;
import org.jackhuang.hmcl.plugin.runtime.PluginPlatformTarget;
import org.jackhuang.hmcl.plugin.runtime.PluginRuntimeTypes;
import org.jackhuang.hmcl.plugin.runtime.RuntimeProvider;
import org.jackhuang.hmcl.plugin.runtime.RuntimeProviderRegistry;
import org.jackhuang.hmcl.plugin.trust.PluginRuntimeTrustGuard;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies exact-artifact permission checks used by plugin-store dependency reuse planning.
@NotNullByDefault
public final class PluginManagerReuseEligibilityTest {
    /// Exposes only fully compatible, authorized, trusted disabled artifacts as activatable Store Providers.
    ///
    /// @param temporaryDirectory isolated launcher homes
    /// @throws IOException if package, permission, or planning state cannot be prepared
    @Test
    public void activatableSnapshotRejectsEveryNonDisabledReuseFailure(@TempDir Path temporaryDirectory)
            throws IOException {
        Path localHome = temporaryDirectory.resolve("ordinary");
        PluginManager manager = new PluginManager(localHome);
        String compatibleId = "dev.test.activatable.compatible";
        String underGrantedId = "dev.test.activatable.under-granted";
        String launcherIncompatibleId = "dev.test.activatable.launcher-incompatible";
        writeSchemaFourPackage(
                manager.getPluginsDirectory().resolve(compatibleId + ".npl"),
                compatibleId, "[]", "[]", "*", "compatible"
        );
        writeSchemaFourPackage(
                manager.getPluginsDirectory().resolve(underGrantedId + ".npl"),
                underGrantedId, "[\"filesystem\"]", "[\"filesystem\"]", "*", "under-granted"
        );
        writeSchemaFourPackage(
                manager.getPluginsDirectory().resolve(launcherIncompatibleId + ".npl"),
                launcherIncompatibleId, "[]", "[]", ">=9999.0.0", "incompatible"
        );

        PluginInstallationPlanningSnapshot snapshot = manager.getInstallationPlanningSnapshot();

        assertTrue(snapshot.getActivatableArtifacts().containsKey(compatibleId));
        assertFalse(snapshot.getActivatableArtifacts().containsKey(underGrantedId));
        assertFalse(snapshot.getActivatableArtifacts().containsKey(launcherIncompatibleId));

        Path untrustedHome = temporaryDirectory.resolve("untrusted");
        PluginManager untrustedManager = new PluginManager(
                untrustedHome,
                PluginRuntimeTrustGuard.unavailable("test trust state unavailable")
        );
        String untrustedId = "dev.test.activatable.untrusted";
        writeSchemaFourPackage(
                untrustedManager.getPluginsDirectory().resolve(untrustedId + ".npl"),
                untrustedId, "[]", "[]", "*", "untrusted"
        );

        assertFalse(untrustedManager.getInstallationPlanningSnapshot()
                .getActivatableArtifacts().containsKey(untrustedId));
    }

    /// Requires complete grants for the current package hash and invalidates eligibility when package bytes change.
    @Test
    public void reuseEligibilityIsBoundToRequiredGrantsAndExactPackage(@TempDir Path temporaryDirectory)
            throws IOException {
        String pluginId = "dev.test.reuse.required";
        PluginManager manager = new PluginManager(temporaryDirectory);
        Path packageFile = manager.getPluginsDirectory().resolve(pluginId + ".npl");
        writeSchemaFourPackage(
                packageFile,
                pluginId,
                "[\"filesystem\", \"network\"]",
                "[\"filesystem\"]",
                "*",
                "first"
        );
        manager.enablePlugin(pluginId);

        @Unmodifiable Map<String, PluginManifest> installed = manager.getInstalledManifests();
        assertFalse(manager.getReusableInstalledPluginIds(installed).contains(pluginId));

        manager.setGrantedPermissions(pluginId, Set.of(PluginPermission.FILESYSTEM));
        assertTrue(manager.getReusableInstalledPluginIds(installed).contains(pluginId));

        writeSchemaFourPackage(
                packageFile,
                pluginId,
                "[\"filesystem\", \"network\"]",
                "[\"filesystem\"]",
                "*",
                "replacement-bytes"
        );
        @Unmodifiable Map<String, PluginManifest> rewritten = manager.getInstalledManifests();
        assertFalse(manager.getReusableInstalledPluginIds(rewritten).contains(pluginId));
    }

    /// Accepts optional-only executable artifacts without synthesized grants and rejects legacy packages.
    @Test
    public void reuseEligibilityRequiresExecutableCompatibleManifest(@TempDir Path temporaryDirectory)
            throws IOException {
        String optionalId = "dev.test.reuse.optional";
        String legacyId = "dev.test.reuse.legacy";
        String nonCanonicalId = "Dev.test.reuse.noncanonical";
        String disabledId = "dev.test.reuse.disabled";
        PluginManager manager = new PluginManager(temporaryDirectory);
        writeSchemaFourPackage(
                manager.getPluginsDirectory().resolve(optionalId + ".npl"),
                optionalId,
                "[\"network\"]",
                "[]",
                "*",
                "optional"
        );
        writeLegacyPackage(manager.getPluginsDirectory().resolve(legacyId + ".npl"), legacyId);
        writeSchemaFourPackage(
                manager.getPluginsDirectory().resolve(nonCanonicalId + ".npl"),
                nonCanonicalId,
                "[]",
                "[]",
                "*",
                "noncanonical"
        );
        writeSchemaFourPackage(
                manager.getPluginsDirectory().resolve(disabledId + ".npl"),
                disabledId,
                "[]",
                "[]",
                "*",
                "disabled"
        );
        manager.enablePlugin(optionalId);
        manager.enablePlugin(legacyId);
        manager.enablePlugin(nonCanonicalId);

        @Unmodifiable Map<String, PluginManifest> installed = manager.getInstalledManifests();
        @Unmodifiable Set<String> reusable = manager.getReusableInstalledPluginIds(installed);

        assertTrue(reusable.contains(optionalId));
        assertFalse(reusable.contains(legacyId));
        assertFalse(reusable.contains(nonCanonicalId));
        assertFalse(reusable.contains(disabledId));
    }

    /// Rejects a published artifact explicitly marked for removal even when a caller supplies it in the snapshot.
    ///
    /// @param temporaryDirectory isolated launcher home
    /// @throws IOException if package or state fixture creation fails
    @Test
    public void reuseEligibilityRejectsPendingUninstallArtifact(@TempDir Path temporaryDirectory)
            throws IOException {
        String pluginId = "dev.test.reuse.pending";
        PluginManager manager = new PluginManager(temporaryDirectory);
        writeSchemaFourPackage(
                manager.getPluginsDirectory().resolve(pluginId + ".npl"),
                pluginId,
                "[]",
                "[]",
                "*",
                "pending"
        );
        Files.writeString(
                temporaryDirectory.resolve("plugin-states.json"),
                "{\"enabled\":[],\"pendingUninstall\":[\"" + pluginId + "\"]}",
                StandardCharsets.UTF_8
        );
        PluginManager reloaded = new PluginManager(temporaryDirectory);
        @Unmodifiable Map<String, PluginManifest> published = reloaded.getPublishedPluginManifests();

        assertFalse(reloaded.getReusableInstalledPluginIds(published).contains(pluginId));
    }

    /// Rejects final publication when a reused optional-only dependency is rewritten after plan confirmation.
    ///
    /// @param temporaryDirectory isolated launcher home and package staging directory
    /// @throws Exception if fixture creation, planning, or package inspection fails
    @Test
    public void finalPublicationRejectsSameManifestDependencyRewrite(@TempDir Path temporaryDirectory)
            throws Exception {
        Path localHome = temporaryDirectory.resolve("home");
        PluginManager manager = new PluginManager(localHome);
        String dependencyId = "dev.test.reuse.rewritten-dependency";
        String rootId = "dev.test.reuse.rewritten-root";
        Path installedDependency = manager.getPluginsDirectory().resolve(dependencyId + ".npl");
        writeSchemaFourPackage(
                installedDependency,
                dependencyId,
                "1.0.0",
                "[]",
                "[]",
                "*",
                "[]",
                "planned-bytes"
        );
        manager.enablePlugin(dependencyId);
        @Unmodifiable Map<String, PluginManifest> planningManifests = manager.getInstalledManifests();
        @Unmodifiable Map<String, PluginArtifactIdentity> expectedReusableArtifacts =
                manager.getReusableInstalledPluginArtifacts(planningManifests);
        assertTrue(expectedReusableArtifacts.containsKey(dependencyId));

        Path rootPackage = temporaryDirectory.resolve("root.npl");
        writeSchemaFourPackage(
                rootPackage,
                rootId,
                "1.0.0",
                "[]",
                "[]",
                "*",
                "[{\"id\":\"" + dependencyId + "\",\"version\":\"1.0.0\"}]",
                "root"
        );
        LocalPluginInspection rootInspection = manager.inspectLocalPluginPackage(rootPackage);

        writeSchemaFourPackage(
                installedDependency,
                dependencyId,
                "1.0.0",
                "[]",
                "[]",
                "*",
                "[]",
                "rewritten-bytes"
        );

        IOException failure = assertThrows(
                IOException.class,
                () -> manager.stagePluginInstallations(
                        List.of(rootInspection),
                        Map.of(rootId, Set.of()),
                        expectedReusableArtifacts
                )
        );

        assertTrue(Objects.requireNonNull(failure.getMessage()).contains("changed after planning"));
        assertFalse(Files.exists(manager.getPluginsDirectory().resolve(rootId + ".npl")));
    }

    /// Rejects final publication when a reused dependency loses a required grant after plan confirmation.
    ///
    /// @param temporaryDirectory isolated launcher home and package staging directory
    /// @throws Exception if fixture creation, planning, hashing, or permission persistence fails
    @Test
    public void finalPublicationRejectsRequiredPermissionRevocation(@TempDir Path temporaryDirectory)
            throws Exception {
        Path localHome = temporaryDirectory.resolve("home");
        PluginManager manager = new PluginManager(localHome);
        String dependencyId = "dev.test.reuse.revoked-dependency";
        String rootId = "dev.test.reuse.revoked-root";
        Path installedDependency = manager.getPluginsDirectory().resolve(dependencyId + ".npl");
        writeSchemaFourPackage(
                installedDependency,
                dependencyId,
                "1.0.0",
                "[\"filesystem\"]",
                "[\"filesystem\"]",
                "*",
                "[]",
                "dependency"
        );
        manager.enablePlugin(dependencyId);
        manager.setGrantedPermissions(dependencyId, Set.of(PluginPermission.FILESYSTEM));
        @Unmodifiable Map<String, PluginManifest> planningManifests = manager.getInstalledManifests();
        @Unmodifiable Map<String, PluginArtifactIdentity> expectedReusableArtifacts =
                manager.getReusableInstalledPluginArtifacts(planningManifests);
        assertTrue(expectedReusableArtifacts.containsKey(dependencyId));

        Path rootPackage = temporaryDirectory.resolve("required-root.npl");
        writeSchemaFourPackage(
                rootPackage,
                rootId,
                "1.0.0",
                "[]",
                "[]",
                "*",
                "[{\"id\":\"" + dependencyId + "\",\"version\":\"1.0.0\"}]",
                "root"
        );
        LocalPluginInspection rootInspection = manager.inspectLocalPluginPackage(rootPackage);
        PluginPermissionStore permissionStore = new PluginPermissionStore(
                localHome.resolve("plugin-permissions.json")
        );
        permissionStore.setGrantedPermissions(
                new PluginPermissionStore.Artifact(
                        dependencyId,
                        "1.0.0",
                        PluginPackageVersions.calculateSha256(installedDependency)
                ),
                Set.of()
        );

        assertThrows(
                IOException.class,
                () -> manager.stagePluginInstallations(
                        List.of(rootInspection),
                        Map.of(rootId, Set.of()),
                        expectedReusableArtifacts
                )
        );
        assertFalse(Files.exists(manager.getPluginsDirectory().resolve(rootId + ".npl")));
    }

    /// Enables a disabled installed dependency when the same batch downloads and replaces that dependency.
    ///
    /// @param temporaryDirectory isolated launcher home and package staging directory
    /// @throws Exception if fixture creation, inspection, or atomic publication fails
    @Test
    public void replacementDependencyIsEnabledForNextRestart(@TempDir Path temporaryDirectory)
            throws Exception {
        Path localHome = temporaryDirectory.resolve("home");
        PluginManager manager = new PluginManager(localHome);
        String dependencyId = "dev.test.reuse.replaced-disabled-dependency";
        String rootId = "dev.test.reuse.replaced-disabled-root";
        writeSchemaFourPackage(
                manager.getPluginsDirectory().resolve(dependencyId + ".npl"),
                dependencyId,
                "1.0.0",
                "[]",
                "[]",
                "*",
                "[]",
                "installed-disabled"
        );
        assertFalse(manager.isPluginEnabled(dependencyId));

        Path dependencyReplacement = temporaryDirectory.resolve("dependency-replacement.npl");
        Path rootPackage = temporaryDirectory.resolve("replacement-root.npl");
        writeSchemaFourPackage(
                dependencyReplacement,
                dependencyId,
                "2.0.0",
                "[]",
                "[]",
                "*",
                "[]",
                "replacement"
        );
        writeSchemaFourPackage(
                rootPackage,
                rootId,
                "1.0.0",
                "[]",
                "[]",
                "*",
                "[{\"id\":\"" + dependencyId + "\",\"version\":\">=2.0.0\"}]",
                "root"
        );
        LocalPluginInspection dependencyInspection =
                manager.inspectLocalPluginPackage(dependencyReplacement);
        LocalPluginInspection rootInspection = manager.inspectLocalPluginPackage(rootPackage);

        manager.stagePluginInstallations(
                List.of(dependencyInspection, rootInspection),
                Map.of(dependencyId, Set.of(), rootId, Set.of()),
                Map.of()
        );

        assertTrue(manager.isPluginEnabled(dependencyId));
        assertEquals(
                "2.0.0",
                manager.getInstalledManifests().get(dependencyId).getVersion()
        );
        assertTrue(new PluginManager(localHome).isPluginEnabled(dependencyId));
    }

    /// Rejects a confirmed installation when another launcher publishes the same plugin ID before final staging.
    ///
    /// @param temporaryDirectory isolated shared launcher home and package staging directory
    /// @throws Exception if fixture creation, inspection, or the competing launcher publication fails
    @Test
    public void finalPublicationRejectsInstallTargetCreatedByAnotherLauncher(@TempDir Path temporaryDirectory)
            throws Exception {
        Path localHome = temporaryDirectory.resolve("home");
        PluginManager confirmingManager = new PluginManager(localHome);
        String pluginId = "dev.test.prior.concurrent-install";
        Path confirmedSource = temporaryDirectory.resolve("confirmed-install.npl");
        Path competingSource = temporaryDirectory.resolve("competing-install.npl");
        writeSchemaFourPackage(
                confirmedSource,
                pluginId,
                "1.0.0",
                "[]",
                "[]",
                "*",
                "[]",
                "confirmed"
        );
        writeSchemaFourPackage(
                competingSource,
                pluginId,
                "1.0.0",
                "[]",
                "[]",
                "*",
                "[]",
                "competing"
        );
        LocalPluginInspection confirmedInspection =
                confirmingManager.inspectLocalPluginPackage(confirmedSource);
        assertNull(confirmedInspection.getPriorArtifactIdentity());

        PluginManager competingManager = new PluginManager(localHome);
        competingManager.prepareLocalPluginInstallation(competingSource, Set.of());

        assertThrows(
                IOException.class,
                () -> confirmingManager.stagePluginInstallations(
                        List.of(confirmedInspection),
                        Map.of(pluginId, Set.of()),
                        Map.of(),
                        Map.of(pluginId, Optional.empty())
                )
        );
        Path installedPackage = confirmingManager.getPluginsDirectory().resolve(pluginId + ".npl");
        assertEquals(
                PluginPackageVersions.calculateSha256(competingSource),
                PluginPackageVersions.calculateSha256(installedPackage)
        );
    }

    /// Rejects a confirmed update when another launcher replaces the exact prior artifact before publication.
    ///
    /// @param temporaryDirectory isolated shared launcher home and package staging directory
    /// @throws Exception if fixture creation, planning, inspection, or competing publication fails
    @Test
    public void finalPublicationRejectsUpdateTargetChangedByAnotherLauncher(@TempDir Path temporaryDirectory)
            throws Exception {
        Path localHome = temporaryDirectory.resolve("home");
        PluginManager confirmingManager = new PluginManager(localHome);
        String pluginId = "dev.test.prior.concurrent-update";
        Path installedPackage = confirmingManager.getPluginsDirectory().resolve(pluginId + ".npl");
        writeSchemaFourPackage(
                installedPackage,
                pluginId,
                "1.0.0",
                "[]",
                "[]",
                "*",
                "[]",
                "initial"
        );
        PluginInstallationPlanningSnapshot planningSnapshot =
                confirmingManager.getInstallationPlanningSnapshot();
        PluginArtifactIdentity expectedPrior = Objects.requireNonNull(
                planningSnapshot.getInstalledArtifacts().get(pluginId)
        );

        Path confirmedUpdate = temporaryDirectory.resolve("confirmed-update.npl");
        Path competingUpdate = temporaryDirectory.resolve("competing-update.npl");
        writeSchemaFourPackage(
                confirmedUpdate,
                pluginId,
                "2.0.0",
                "[]",
                "[]",
                "*",
                "[]",
                "confirmed"
        );
        writeSchemaFourPackage(
                competingUpdate,
                pluginId,
                "1.5.0",
                "[]",
                "[]",
                "*",
                "[]",
                "competing"
        );
        LocalPluginInspection confirmedInspection =
                confirmingManager.inspectLocalPluginPackage(confirmedUpdate);

        PluginManager competingManager = new PluginManager(localHome);
        competingManager.prepareLocalPluginInstallation(competingUpdate, Set.of());

        assertThrows(
                IOException.class,
                () -> confirmingManager.stagePluginInstallations(
                        List.of(confirmedInspection),
                        Map.of(pluginId, Set.of()),
                        Map.of(),
                        Map.of(pluginId, Optional.of(expectedPrior))
                )
        );
        assertEquals(
                "1.5.0",
                Objects.requireNonNull(confirmingManager.getInstalledManifests().get(pluginId)).getVersion()
        );
    }

    /// Rejects exact-artifact expectations when an installation batch has no replacements.
    ///
    /// @param temporaryDirectory isolated launcher home
    @Test
    public void emptyInstallationRejectsNonEmptyArtifactExpectations(@TempDir Path temporaryDirectory) {
        PluginManager manager = new PluginManager(temporaryDirectory.resolve("home"));
        PluginArtifactIdentity ucepected = new PluginArtifactIdentity(
                "dev.test.prior.empty",
                "1.0.0",
                "f".repeat(64)
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> manager.stagePluginInstallations(
                        List.of(),
                        Map.of(),
                        Map.of(ucepected.getPluginId(), ucepected)
                )
        );
    }

    /// Rejects an incompatible launcher constraint even when HMCL itself uses a snapshot or development version.
    ///
    /// @param temporaryDirectory isolated launcher home and source directory
    /// @throws IOException if the package fixture cannot be created
    @Test
    public void launcherCompatibilityNeverBypassesDevelopmentBuilds(@TempDir Path temporaryDirectory)
            throws IOException {
        PluginManager manager = new PluginManager(temporaryDirectory.resolve("home"));
        Path sourcePackage = temporaryDirectory.resolve("launcher-incompatible.npl");
        writeSchemaFourPackage(
                sourcePackage,
                "dev.test.launcher.incompatible",
                "1.0.0",
                "[]",
                "[]",
                ">=9999.0.0",
                "[]",
                "incompatible"
        );

        assertThrows(IOException.class, () -> manager.inspectLocalPluginPackage(sourcePackage));
    }

    /// Excludes schema-v5 artifacts rejected by platform, runtime availability, or provider ABI gates.
    ///
    /// @param temporaryDirectory isolated launcher home
    /// @throws IOException if package or persisted enablement fixtures cannot be created
    @Test
    public void reuseEligibilityRequiresCompleteRuntimeCompatibility(@TempDir Path temporaryDirectory)
            throws IOException {
        RuntimeProviderRegistry runtimeProviders = new RuntimeProviderRegistry();
        runtimeProviders.register(runtimeProvider("dotnet", Set.of(PluginAbi.ABI_1)));
        PluginCompatibilityEvaluator evaluator = new PluginCompatibilityEvaluator(
                runtimeProviders,
                PluginPlatformTarget.parse("linux-x64")
        );
        PluginManager manager = new PluginManager(temporaryDirectory.resolve("home"), evaluator);
        String platformId = "dev.test.reuse.unsupported-platform";
        String runtimeId = "dev.test.reuse.missing-runtime";
        String abiId = "dev.test.reuse.unsupported-abi";
        writeSchemaFivePackage(
                manager.getPluginsDirectory().resolve(platformId + ".npl"),
                platformId,
                PluginRuntimeTypes.JAVA,
                PluginAbi.ABI_1,
                "[\"windows-x64\"]"
        );
        writeSchemaFivePackage(
                manager.getPluginsDirectory().resolve(runtimeId + ".npl"),
                runtimeId,
                "python",
                PluginAbi.ABI_1,
                "[]"
        );
        writeSchemaFivePackage(
                manager.getPluginsDirectory().resolve(abiId + ".npl"),
                abiId,
                "dotnet",
                PluginAbi.ABI_2,
                "[]"
        );
        manager.enablePlugin(platformId);
        manager.enablePlugin(runtimeId);
        manager.enablePlugin(abiId);

        @Unmodifiable Map<String, PluginManifest> installed = manager.getInstalledManifests();
        @Unmodifiable Set<String> reusable = manager.getReusableInstalledPluginIds(installed);

        assertFalse(reusable.contains(platformId));
        assertFalse(reusable.contains(runtimeId));
        assertFalse(reusable.contains(abiId));
    }

    /// Writes one schema-v5 package with explicit runtime compatibility requirements.
    ///
    /// @param target package path
    /// @param pluginId plugin ID
    /// @param runtime canonical runtime identifier
    /// @param abi required runtime ABI
    /// @param platformsJson raw platform target array
    /// @throws IOException if package creation fails
    private static void writeSchemaFivePackage(
            Path target,
            String pluginId,
            String runtime,
            int abi,
            String platformsJson
    ) throws IOException {
        String manifest = """
                {
                  "schemaVersion": 5,
                  "id": "%s",
                  "name": "Reuse Compatibility Test",
                  "version": "1.0.0",
                  "type": "java",
                  "entrypoint": "dev.test.Plugin",
                  "permissions": [],
                  "requiredPermissions": [],
                  "launcherVersion": "*",
                  "runtime": "%s",
                  "abi": %s,
                  "platforms": %s,
                  "dependencies": []
                }
                """.formatted(pluginId, runtime, abi, platformsJson);
        writePackage(target, manifest, "schema-five");
    }

    /// Creates a deterministic runtime provider fixture for reuse compatibility tests.
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
                return "Reuse compatibility test provider";
            }
        };
    }

    /// Writes one schema-v4 package containing a manifest and a caller-selected byte marker.
    ///
    /// @param target package path
    /// @param pluginId plugin ID
    /// @param permissionsJson declared permission array JSON
    /// @param requiredPermissionsJson required permission array JSON
    /// @param launcherVersion launcher compatibility constraint
    /// @param marker package byte marker
    /// @throws IOException if package creation fails
    private static void writeSchemaFourPackage(
            Path target,
            String pluginId,
            String permissionsJson,
            String requiredPermissionsJson,
            String launcherVersion,
            String marker
    ) throws IOException {
        writeSchemaFourPackage(
                target,
                pluginId,
                "1.0.0",
                permissionsJson,
                requiredPermissionsJson,
                launcherVersion,
                "[]",
                marker
        );
    }

    /// Writes one schema-v4 package with caller-selected version, permissions, dependencies, and byte marker.
    ///
    /// @param target package path
    /// @param pluginId plugin ID
    /// @param version plugin version
    /// @param permissionsJson declared permission array JSON
    /// @param requiredPermissionsJson required permission array JSON
    /// @param launcherVersion launcher compatibility constraint
    /// @param dependenciesJson dependency array JSON
    /// @param marker package byte marker
    /// @throws IOException if package creation fails
    private static void writeSchemaFourPackage(
            Path target,
            String pluginId,
            String version,
            String permissionsJson,
            String requiredPermissionsJson,
            String launcherVersion,
            String dependenciesJson,
            String marker
    ) throws IOException {
        String manifest = """
                {
                  "schemaVersion": 4,
                  "id": "%s",
                  "name": "Reuse Eligibility Test",
                  "version": "%s",
                  "type": "java",
                  "entrypoint": "dev.test.Plugin",
                  "permissions": %s,
                  "requiredPermissions": %s,
                  "launcherVersion": "%s",
                  "dependencies": %s
                }
                """.formatted(
                        pluginId,
                        version,
                        permissionsJson,
                        requiredPermissionsJson,
                        launcherVersion,
                        dependenciesJson
                );
        writePackage(target, manifest, marker);
    }

    /// Writes one schema-v2 package that may be managed but must never be reused for executable dependencies.
    ///
    /// @param target package path
    /// @param pluginId plugin ID
    /// @throws IOException if package creation fails
    private static void writeLegacyPackage(Path target, String pluginId) throws IOException {
        String manifest = """
                {
                  "schemaVersion": 2,
                  "id": "%s",
                  "name": "Legacy Reuse Test",
                  "version": "1.0.0",
                  "type": "java",
                  "entrypoint": "dev.test.Plugin",
                  "dependencies": []
                }
                """.formatted(pluginId);
        writePackage(target, manifest, "legacy");
    }

    /// Writes deterministic manifest and marker entries into one package archive.
    ///
    /// @param target package path
    /// @param manifest package manifest JSON
    /// @param marker caller-selected package byte marker
    /// @throws IOException if archive creation fails
    private static void writePackage(Path target, String manifest, String marker) throws IOException {
        Files.createDirectories(Objects.requireNonNull(target.getParent()));
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(target))) {
            writeTextEntry(output, "plugin.json", manifest);
            writeTextEntry(output, "marker.txt", marker);
        }
    }

    /// Writes one deterministic UTF-8 archive entry.
    ///
    /// @param output target archive
    /// @param name entry path
    /// @param value entry contents
    /// @throws IOException if the entry cannot be written
    private static void writeTextEntry(ZipOutputStream output, String name, String value) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(0);
        output.putNextEntry(entry);
        output.write(value.getBytes(StandardCharsets.UTF_8));
        output.closeEntry();
    }
}
