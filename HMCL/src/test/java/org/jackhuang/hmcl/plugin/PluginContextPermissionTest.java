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
import org.jackhuang.hmcl.plugin.bridge.PluginPermissionAuthority;
import org.jackhuang.hmcl.plugin.runtime.PluginExecutionMode;
import org.jackhuang.hmcl.plugin.runtime.RuntimeFeature;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies dynamic capability enforcement in the official plugin context API.
@NotNullByDefault
public final class PluginContextPermissionTest {
    /// Classifies the schema-v5 external-runtime permissions by their install-time warning level.
    @Test
    public void classifyExternalRuntimePermissions() {
        assertEquals(PluginPermissionTier.ADVANCED,
                PluginPermissionTier.tierOf(PluginPermission.LAUNCHER_CORE));
        assertEquals(PluginPermissionTier.DANGEROUS,
                PluginPermissionTier.tierOf(PluginPermission.JVM_RAW));
        assertEquals(PluginPermissionTier.DANGEROUS,
                PluginPermissionTier.tierOf(PluginPermission.SHELL));
    }

    /// Keeps external-runtime authority vocabulary exclusive to schema v5.
    @Test
    public void rejectExternalRuntimePermissionsBeforeSchemaFive() {
        assertThrows(IOException.class, () -> PluginManifest.fromJson(new StringReader("""
                {
                  "schemaVersion": 4,
                  "id": "dev.hmclce.test.context-permissions-v4-next",
                  "name": "Invalid V4 Next Permission",
                  "version": "1.0.0",
                  "type": "java",
                  "entrypoint": "example.Plugin",
                  "permissions": ["launcher-core"],
                  "requiredPermissions": ["launcher-core"],
                  "launcherVersion": "*",
                  "dependencies": []
                }
                """)));
    }

    /// Derives unsafe Provider features from permissions and rejects raw JVM access across isolation.
    ///
    /// @throws IOException if the valid embedded fixture cannot be parsed
    @Test
    public void deriveUnsafeRuntimeFeaturesAndRejectIsolatedRawJvm() throws IOException {
        PluginManifest embedded = unsafeRuntimeManifest("embedded");

        assertTrue(embedded.getRuntimeRequirement().getRequiredFeatures().contains(RuntimeFeature.RAW_JVM));
        assertTrue(embedded.getRuntimeRequirement().getRequiredFeatures().contains(RuntimeFeature.NATIVE));
        assertThrows(IOException.class, () -> unsafeRuntimeManifest("isolated"));
    }

    /// Applies permission changes immediately and filters grants not requested by the developer.
    ///
    /// @throws IOException if the test manifest is invalid
    @Test
    public void enforceDynamicDeclaredGrants() throws IOException {
        PluginManifest manifest = manifest("[\"filesystem\",\"launcher-ui\"]");
        AtomicReference<Set<PluginPermission>> grants = new AtomicReference<>(Set.of(
                PluginPermission.FILESYSTEM,
                PluginPermission.NETWORK
        ));
        PluginContext context = new PluginContext(
                manifest,
                Path.of("package"),
                Path.of("data"),
                getClass().getClassLoader(),
                "a".repeat(64),
                grants::get
        );

        assertEquals(
                Set.of(PluginPermission.FILESYSTEM, PluginPermission.LAUNCHER_UI),
                context.getDeclaredPermissions()
        );
        assertEquals(Set.of(PluginPermission.FILESYSTEM), context.getGrantedPermissions());
        assertTrue(context.isPermissionGranted(PluginPermission.FILESYSTEM));
        assertFalse(context.isPermissionGranted(PluginPermission.NETWORK));
        PluginPermissionException denied = assertThrows(
                PluginPermissionException.class,
                () -> context.requirePermission(PluginPermission.LAUNCHER_UI)
        );
        assertEquals(PluginPermissionException.Reason.USER_DENIED, denied.getReason());
        PluginPermissionException undeclared = assertThrows(
                PluginPermissionException.class,
                () -> context.requirePermission(PluginPermission.NETWORK)
        );
        assertEquals(PluginPermissionException.Reason.NOT_DECLARED, undeclared.getReason());

        grants.set(Set.of(PluginPermission.LAUNCHER_UI));

        assertFalse(context.isPermissionGranted(PluginPermission.FILESYSTEM));
        assertTrue(context.isPermissionGranted(PluginPermission.LAUNCHER_UI));
        assertThrows(
                PluginPermissionException.class,
                () -> context.requirePermission(PluginPermission.FILESYSTEM)
        );
    }

    /// Gives manually constructed compatibility contexts no sensitive launcher capabilities by default.
    ///
    /// @throws IOException if the test manifest is invalid
    @Test
    public void denyPermissionsInCompatibilityConstructor() throws IOException {
        PluginContext context = new PluginContext(
                manifest("[\"filesystem\"]"),
                Path.of("package"),
                Path.of("data"),
                getClass().getClassLoader()
        );

        assertTrue(context.getGrantedPermissions().isEmpty());
        assertThrows(PluginPermissionException.class, context::getLauncherDataDirectory);
    }

    /// Exposes schema-v4 required and optional sets while keeping required grants non-revocable and optional grants
    /// dynamically revocable through the exact-artifact permission service.
    ///
    /// @param temporaryDirectory isolated permission-store directory
    /// @throws IOException if the manifest or permission document cannot be created
    @Test
    public void exposeSchemaFourPermissionKindsAndRejectRequiredRevocation(
            @TempDir Path temporaryDirectory
    ) throws IOException {
        PluginManifest manifest = schemaFourManifest();
        String sha256 = "a".repeat(64);
        PluginPermissionStore.Artifact artifact = new PluginPermissionStore.Artifact(
                manifest.getId(),
                manifest.getVersion(),
                sha256
        );
        PluginPermissionService permissionService = new PluginPermissionService(
                temporaryDirectory.resolve("plugin-permissions.json"),
                ignored -> new PluginPermissionService.ResolvedArtifact(manifest, artifact),
                new PluginMutationLock(temporaryDirectory)
        );
        permissionService.setGrantedPermissions(
                manifest,
                sha256,
                Set.of(PluginPermission.FILESYSTEM, PluginPermission.LAUNCHER_UI)
        );
        PluginContext context = new PluginContext(
                manifest,
                Path.of("package"),
                Path.of("data"),
                getClass().getClassLoader(),
                sha256,
                () -> permissionService.getGrantedPermissions(manifest, sha256)
        );

        assertEquals(Set.of(PluginPermission.FILESYSTEM), context.getRequiredPermissions());
        assertEquals(Set.of(PluginPermission.LAUNCHER_UI), context.getOptionalPermissions());
        assertTrue(context.isPermissionRequired(PluginPermission.FILESYSTEM));
        assertFalse(context.isPermissionRequired(PluginPermission.LAUNCHER_UI));
        assertThrows(
                IllegalArgumentException.class,
                () -> permissionService.setGrantedPermissions(
                        manifest,
                        sha256,
                        Set.of(PluginPermission.LAUNCHER_UI)
                )
        );
        assertEquals(
                Set.of(PluginPermission.FILESYSTEM, PluginPermission.LAUNCHER_UI),
                context.getGrantedPermissions()
        );

        permissionService.setGrantedPermissions(manifest, sha256, Set.of(PluginPermission.FILESYSTEM));

        assertTrue(context.isPermissionGranted(PluginPermission.FILESYSTEM));
        assertFalse(context.isPermissionGranted(PluginPermission.LAUNCHER_UI));
    }

    /// Issues context-owned tokens from current grants and revokes them when the context closes.
    ///
    /// @throws IOException if the schema-v5 manifest is invalid
    @Test
    public void issueAndRevokeContextCapabilityTokens() throws IOException {
        PluginManifest manifest = schemaFiveManifest("1.0.0");
        String sha256 = "d".repeat(64);
        PluginPermissionAuthority authority = new PluginPermissionAuthority();
        AtomicReference<Set<PluginPermission>> grants = new AtomicReference<>(
                Set.of(PluginPermission.LAUNCHER_CORE));
        PluginContext context = new PluginContext(
                manifest,
                Path.of("package"),
                Path.of("data"),
                getClass().getClassLoader(),
                sha256,
                grants::get,
                provider -> {
                    throw new AssertionError("Provider registration is unrelated to token issuance");
                },
                authority
        );

        PluginCapabilityToken token = context.issueCapabilityToken("bridge.core", Duration.ofMinutes(1));
        PluginArtifactIdentity identity = new PluginArtifactIdentity(
                manifest.getId(), manifest.getVersion(), sha256);
        authority.requirePermission(
                token,
                manifest.getId(),
                identity,
                PluginExecutionMode.EMBEDDED,
                PluginPermission.LAUNCHER_CORE,
                "bridge.core"
        );

        grants.set(Set.of());
        context.revokeCapabilityTokens();

        assertThrows(SecurityException.class, () -> authority.requirePermission(
                token,
                manifest.getId(),
                identity,
                PluginExecutionMode.EMBEDDED,
                PluginPermission.LAUNCHER_CORE,
                "bridge.core"
        ));
        PluginCapabilityToken reducedToken = context.issueCapabilityToken("bridge.core", Duration.ofMinutes(1));
        assertThrows(SecurityException.class, () -> authority.requirePermission(
                reducedToken,
                manifest.getId(),
                identity,
                PluginExecutionMode.EMBEDDED,
                PluginPermission.LAUNCHER_CORE,
                "bridge.core"
        ));
    }

    /// Keeps new required grants artifact-bound so an upgrade cannot reuse the previous package's decision.
    ///
    /// @param temporaryDirectory isolated permission-store directory
    /// @throws IOException if manifests or permission state cannot be created
    @Test
    public void requireFreshConsentForRequiredPermissionUpgrade(
            @TempDir Path temporaryDirectory
    ) throws IOException {
        PluginManifest oldManifest = schemaFiveManifestWithOptionalCorePermission();
        PluginManifest upgradedManifest = schemaFiveManifest("2.0.0");
        String oldSha256 = "a".repeat(64);
        String upgradedSha256 = "b".repeat(64);
        PluginPermissionService permissionService = new PluginPermissionService(
                temporaryDirectory.resolve("plugin-permissions.json"),
                ignored -> null,
                new PluginMutationLock(temporaryDirectory)
        );
        permissionService.setGrantedPermissions(
                oldManifest,
                oldSha256,
                Set.of(PluginPermission.LAUNCHER_CORE)
        );

        assertTrue(permissionService.getGrantedPermissions(upgradedManifest, upgradedSha256).isEmpty());
    }

    /// Creates a schema-v3 manifest with caller-selected permission requests.
    ///
    /// @param permissionsJson raw permission JSON array
    /// @return validated test manifest
    /// @throws IOException if the generated manifest is invalid
    private static PluginManifest manifest(String permissionsJson) throws IOException {
        return PluginManifest.fromJson(new StringReader("""
                {
                  "schemaVersion": 3,
                  "id": "dev.hmclce.test.context-permissions",
                  "name": "Context Permission Test",
                  "version": "1.0.0",
                  "type": "java",
                  "entrypoint": "example.Plugin",
                  "permissions": %s,
                  "dependencies": []
                }
                """.formatted(permissionsJson)));
    }

    /// Creates a schema-v4 manifest with one required and one optional capability.
    ///
    /// @return validated schema-v4 test manifest
    /// @throws IOException if the generated manifest is invalid
    private static PluginManifest schemaFourManifest() throws IOException {
        return PluginManifest.fromJson(new StringReader("""
                {
                  "schemaVersion": 4,
                  "id": "dev.hmclce.test.context-permissions-v4",
                  "name": "Context Permission Test V4",
                  "version": "1.0.0",
                  "type": "java",
                  "entrypoint": "example.Plugin",
                  "permissions": ["filesystem", "launcher-ui"],
                  "requiredPermissions": ["filesystem"],
                  "launcherVersion": "*",
                  "dependencies": []
                }
                """));
    }

    /// Creates a schema-v5 external payload with one required privileged Core permission.
    ///
    /// @param version package version
    /// @return validated schema-v5 test manifest
    /// @throws IOException if the generated manifest is invalid
    private static PluginManifest schemaFiveManifest(String version) throws IOException {
        return PluginManifest.fromJson(new StringReader("""
                {
                  "schemaVersion": 5,
                  "id": "dev.hmclce.test.context-permissions-v5",
                  "name": "Context Permission Test V5",
                  "version": "%s",
                  "type": "java",
                  "entrypoint": "payload/plugin.dll",
                  "permissions": ["launcher-core"],
                  "requiredPermissions": ["launcher-core"],
                  "launcherVersion": "*",
                  "runtime": "rust",
                  "abi": 2,
                  "platforms": []
                }
                """.formatted(version)));
    }

    /// Creates the pre-upgrade schema-v5 artifact where privileged Core authority remains optional.
    ///
    /// @return validated schema-v5 test manifest with optional Core permission
    /// @throws IOException if the generated manifest is invalid
    private static PluginManifest schemaFiveManifestWithOptionalCorePermission() throws IOException {
        return PluginManifest.fromJson(new StringReader("""
                {
                  "schemaVersion": 5,
                  "id": "dev.hmclce.test.context-permissions-v5",
                  "name": "Context Permission Test V5",
                  "version": "1.0.0",
                  "type": "java",
                  "entrypoint": "payload/plugin.dll",
                  "permissions": ["launcher-core"],
                  "requiredPermissions": [],
                  "launcherVersion": "*",
                  "runtime": "rust",
                  "abi": 2,
                  "platforms": []
                }
                """));
    }

    /// Creates a schema-v5 runtime payload requesting raw JVM and native access.
    ///
    /// @param executionMode serialized execution mode
    /// @return validated manifest
    /// @throws IOException if the execution mode conflicts with its requested capabilities
    private static PluginManifest unsafeRuntimeManifest(String executionMode) throws IOException {
        return PluginManifest.fromJson(new StringReader("""
                {
                  "schemaVersion": 5,
                  "id": "dev.hmclce.test.context-permissions-unsafe",
                  "name": "Unsafe Context Permission Test",
                  "version": "1.0.0",
                  "type": "java",
                  "entrypoint": "payload/plugin.dll",
                  "permissions": ["jvm-raw", "native-code"],
                  "requiredPermissions": ["jvm-raw", "native-code"],
                  "launcherVersion": "*",
                  "runtime": "rust",
                  "abi": 2,
                  "executionMode": "%s",
                  "platforms": []
                }
                """.formatted(executionMode)));
    }
}
