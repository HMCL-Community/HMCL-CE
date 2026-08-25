/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2020  huangyuhui <huanghongxun2008@126.com> and contributors
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
package org.jackhuang.hmcl.plugin.store;

import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import org.jackhuang.hmcl.plugin.PluginKind;
import org.jackhuang.hmcl.plugin.PluginPermission;
import org.jackhuang.hmcl.plugin.runtime.PluginAbi;
import org.jackhuang.hmcl.plugin.runtime.PluginCompatibilityRequirements;
import org.jackhuang.hmcl.plugin.runtime.PluginPlatformTarget;
import org.jackhuang.hmcl.plugin.runtime.PluginRuntimeTypes;
import org.jackhuang.hmcl.plugin.trust.PluginTrustLevel;
import org.jackhuang.hmcl.plugin.trust.PluginTrustResult;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies repository schema compatibility, version history, and version-scoped security metadata.
@NotNullByDefault
public final class PluginStoreManifestTest {
    /// Wraps malformed Gson field mappings in the manifest parser's checked I/O contract.
    @Test
    public void wrapMalformedFieldTypeAsIOException() {
        IOException exception = assertThrows(IOException.class, () -> PluginStoreManifest.fromJson(
                JsonParser.parseString("""
                        {
                          "schemaVersion": 2,
                          "id": "dev.hmclce.test.malformed-type",
                          "versions": {}
                        }
                        """),
                "dev.hmclce.test.malformed-type"
        ));

        assertInstanceOf(JsonParseException.class, exception.getCause());
    }

    /// Parses repository identity and keeps certification declarations and trust decisions on their exact versions.
    @Test
    public void certificationMetadataAndTrustAreVersionScoped() throws IOException {
        PluginStoreManifest manifest = parseManifest("dev.hmclce.test.version-trust", """
                {
                  "schemaVersion": 2,
                  "id": "dev.hmclce.test.version-trust",
                  "repository": "github.com/example/plugin",
                  "versions": [
                    {
                      "version": "2.0.0",
                      "packageUrl": "https://github.com/example/plugin/releases/download/v2/plugin.npl",
                      "sha256": "2222222222222222222222222222222222222222222222222222222222222222",
                      "pluginApiVersion": 2,
                      "size": 2,
                      "certification": {"artifactAttestation": {"signed": {}, "signatures": []}}
                    },
                    {
                      "version": "1.0.0",
                      "packageUrl": "https://github.com/example/plugin/releases/download/v1/plugin.npl",
                      "sha256": "1111111111111111111111111111111111111111111111111111111111111111",
                      "pluginApiVersion": 2,
                      "size": 1
                    }
                  ]
                }
                """);
        PluginStoreManifest.PluginVersionEntry certified = Objects.requireNonNull(manifest.getVersion("2.0.0"));
        PluginStoreManifest.PluginVersionEntry community = Objects.requireNonNull(manifest.getVersion("1.0.0"));

        assertEquals("github.com/example/plugin", manifest.getRepository());
        assertTrue(certified.hasCertificationDeclaration());
        assertTrue(certified.getArtifactAttestation() != null);
        assertFalse(community.hasCertificationDeclaration());
        certified.setTrust(PluginTrustResult.certified("ed25519:artifact", "verification-17"));

        assertEquals(PluginTrustLevel.CERTIFIED, certified.getTrust().level());
        assertEquals(PluginTrustLevel.COMMUNITY, community.getTrust().level());
    }

    /// Rejects manifest-controlled repository proof locations before any network request can occur.
    @Test
    public void rejectManifestProvidedRepositoryAttestationUrl() {
        assertThrows(IOException.class, () -> parseManifest("dev.hmclce.test.injected-proof", """
                {
                  "schemaVersion": 2,
                  "id": "dev.hmclce.test.injected-proof",
                  "repository": "github.com/example/plugin",
                  "repositoryAttestationUrl": "https://attacker.invalid/proof.json",
                  "versions": [{
                    "version": "1.0.0",
                    "packageUrl": "https://example.com/plugin.npl",
                    "sha256": "0000000000000000000000000000000000000000000000000000000000000000",
                    "pluginApiVersion": 2,
                    "size": 1024
                  }]
                }
                """));
    }

    /// Parses schema-v2 permissions and dependencies as authoritative metadata for the selected package version.
    @Test
    public void parseSchemaVersionTwoManifest() throws IOException {
        PluginStoreManifest manifest = parseManifest("dev.hmclce.test.schema-two", """
                {
                  "schemaVersion": 2,
                  "id": "dev.hmclce.test.schema-two",
                  "license": "GPL-3.0-or-later",
                  "website": "https://example.com/plugin",
                  "source": "https://example.com/plugin/source",
                  "readmeUrl": "https://example.com/plugin/README.md",
                  "versions": [
                    {
                      "version": "2.0.0",
                      "packageUrl": "https://example.com/plugin-2.0.0.npl",
                      "sha256": "1111111111111111111111111111111111111111111111111111111111111111",
                      "pluginApiVersion": 3,
                      "permissions": ["network", "filesystem"],
                      "dependencies": [
                        "dev.hmclce.test.legacy",
                        {"id": "dev.hmclce.test.ranged", "version": ">=1.2.0, <2.0.0"}
                      ],
                      "size": 2048
                    }
                  ]
                }
                """);
        PluginStoreManifest.PluginVersionEntry version = Objects.requireNonNull(manifest.getLatestVersion());

        assertEquals(PluginStoreManifest.CURRENT_SCHEMA_VERSION, manifest.getSchemaVersion());
        assertEquals("GPL-3.0-or-later", manifest.getLicense());
        assertEquals("https://example.com/plugin", manifest.getWebsite());
        assertEquals("https://example.com/plugin/source", manifest.getSource());
        assertEquals("https://example.com/plugin/README.md", manifest.getReadmeUrl());
        assertIterableEquals(
                List.of(PluginPermission.NETWORK, PluginPermission.FILESYSTEM),
                version.getPermissions()
        );
        assertTrue(version.getRequiredPermissions().isEmpty());
        assertEquals(version.getPermissions(), version.getOptionalPermissions());
        assertEquals("dev.hmclce.test.legacy", version.getDependencies().get(0).getId());
        assertEquals("*", version.getDependencies().get(0).getVersion());
        assertEquals("dev.hmclce.test.ranged", version.getDependencies().get(1).getId());
        assertEquals(">=1.2.0, <2.0.0", version.getDependencies().get(1).getVersion());
        assertTrue(version.hasAuthoritativeDependencies());
    }

    /// Parses API-v4 permission classifications and the authoritative launcher version constraint.
    @Test
    public void parsePluginApiVersionFourMetadata() throws IOException {
        PluginStoreManifest manifest = parseManifest("dev.hmclce.test.api-four", """
                {
                  "schemaVersion": 2,
                  "id": "dev.hmclce.test.api-four",
                  "versions": [
                    {
                      "version": "4.0.0",
                      "packageUrl": "https://example.com/plugin-4.0.0.npl",
                      "sha256": "4444444444444444444444444444444444444444444444444444444444444444",
                      "pluginApiVersion": 4,
                      "permissions": ["filesystem", "network", "launcher-ui"],
                      "requiredPermissions": ["filesystem", "launcher-ui"],
                      "launcherVersion": ">=26.8-beta.1, <27.0",
                      "dependencies": [],
                      "size": 4096
                    }
                  ]
                }
                """);
        PluginStoreManifest.PluginVersionEntry version = Objects.requireNonNull(manifest.getLatestVersion());

        assertEquals(
                List.of(PluginPermission.FILESYSTEM, PluginPermission.LAUNCHER_UI),
                version.getRequiredPermissions()
        );
        assertEquals(List.of(PluginPermission.NETWORK), version.getOptionalPermissions());
        assertEquals(">=26.8-beta.1, <27.0", version.getLauncherVersion());
        assertTrue(version.matchesLauncherVersion("26.8-beta.3"));
        assertFalse(version.matchesLauncherVersion("27.0"));
        assertEquals(PluginRuntimeTypes.JAVA, version.getRuntime());
        assertEquals(PluginAbi.ABI_1, version.getAbi());
        assertTrue(version.getPlatforms().isEmpty());
    }

    /// Normalizes schema-v5 runtime compatibility metadata and converts it to shared requirements.
    @Test
    public void parsePluginApiVersionFiveCompatibilityMetadata() throws IOException {
        PluginStoreManifest manifest = parseManifest(
                "dev.hmclce.test.invalid-declarations",
                versionDeclarations(5, """
                        "permissions": [],
                        "requiredPermissions": [],
                        "launcherVersion": ">=26.8, <28",
                        "runtime": "java",
                        "abi": 2,
                        "platforms": ["windows-x64", "linux"],
                        "dependencies": []
                        """)
        );
        PluginStoreManifest.PluginVersionEntry version = Objects.requireNonNull(manifest.getLatestVersion());
        PluginCompatibilityRequirements requirements = version.toCompatibilityRequirements();

        assertEquals(PluginRuntimeTypes.JAVA, version.getRuntime());
        assertEquals(PluginAbi.ABI_2, version.getAbi());
        assertEquals(List.of("linux", "windows-x64"), version.getPlatforms());
        assertThrows(UnsupportedOperationException.class, () -> version.getPlatforms().add("macos"));
        assertEquals(5, requirements.schemaVersion());
        assertEquals(">=26.8, <28", requirements.launcherVersion());
        assertEquals(PluginRuntimeTypes.JAVA, requirements.runtime());
        assertEquals(PluginAbi.ABI_2, requirements.abi());
        assertEquals(List.of("linux", "windows-x64"), requirements.platforms().stream()
                .map(Object::toString)
                .toList());
    }

    /// Rejects missing, null, malformed, and noncanonical schema-v5 runtime identifiers.
    @Test
    public void rejectInvalidRuntimeDeclarations() {
        assertManifestRejected(schemaFiveVersionDeclarations("""
                "permissions": [],
                "requiredPermissions": [],
                "launcherVersion": "*",
                "abi": 2,
                "dependencies": []
                """));
        assertManifestRejected(schemaFiveVersionDeclarations("""
                "permissions": [],
                "requiredPermissions": [],
                "launcherVersion": "*",
                "runtime": null,
                "abi": 2,
                "dependencies": []
                """));
        assertManifestRejected(schemaFiveVersionDeclarations("""
                "permissions": [],
                "requiredPermissions": [],
                "launcherVersion": "*",
                "runtime": "bad/runtime",
                "abi": 2,
                "dependencies": []
                """));
        assertManifestRejected(schemaFiveVersionDeclarations("""
                "permissions": [],
                "requiredPermissions": [],
                "launcherVersion": "*",
                "runtime": "Java",
                "abi": 2,
                "dependencies": []
                """));
    }

    /// Rejects missing, null, nonpositive, and unsupported schema-v5 ABI declarations.
    @Test
    public void rejectInvalidAbiDeclarations() {
        for (String abiDeclaration : List.of("", "\"abi\": null,", "\"abi\": 0,", "\"abi\": 3,")) {
            assertManifestRejected(schemaFiveVersionDeclarations("""
                    "permissions": [],
                    "requiredPermissions": [],
                    "launcherVersion": "*",
                    "runtime": "java",
                    %s
                    "dependencies": []
                    """.formatted(abiDeclaration)));
        }
    }

    /// Rejects null, malformed, noncanonical, and duplicate schema-v5 platform declarations.
    @Test
    public void rejectInvalidPlatformDeclarations() {
        for (String platforms : List.of(
                "null",
                "[null]",
                "[\"plan9\"]",
                "[\"Windows\"]",
                "[\"windows\", \"windows\"]"
        )) {
            assertManifestRejected(schemaFiveVersionDeclarations("""
                    "permissions": [],
                    "requiredPermissions": [],
                    "launcherVersion": "*",
                    "runtime": "java",
                    "abi": 2,
                    "platforms": %s,
                    "dependencies": []
                    """.formatted(platforms)));
        }
    }

    /// Rejects schema-v4 declarations of compatibility fields, including explicit null values.
    @Test
    public void rejectSchemaFourRuntimeCompatibilityDeclarations() {
        for (String declaration : List.of(
                "\"runtime\": null,",
                "\"abi\": null,",
                "\"platforms\": null,"
        )) {
            assertManifestRejected(schemaFourVersionDeclarations("""
                    "permissions": [],
                    "requiredPermissions": [],
                    "launcherVersion": "*",
                    %s
                    "dependencies": []
                    """.formatted(declaration)));
        }
    }

    /// Selects one artifact only when its operating system and architecture exactly equal the requested target.
    @Test
    public void selectOneExactPlatformArtifact() throws IOException {
        PluginStoreManifest.PluginVersionEntry version = parseManifest(
                "dev.hmclce.test.artifact-selection",
                schemaFiveArtifactManifest(
                        "dev.hmclce.test.artifact-selection",
                        "\"pluginKind\": \"normal\",",
                        """
                                {"platform": "windows-x64", "packageUrl": "https://example.test/win-x64.npl",
                                 "sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "size": 41},
                                {"platform": "windows-arm64", "packageUrl": "https://example.test/win-arm64.npl",
                                 "sha256": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", "size": 59},
                                {"platform": "linux-x64", "packageUrl": "https://example.test/linux-x64.npl",
                                 "sha256": "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc", "size": 73}
                                """
                )
        ).getVersions().get(0);

        PluginStoreArtifact selected = version.requireArtifact(PluginPlatformTarget.parse("linux-x64"));

        assertEquals("linux-x64", selected.platform().getId());
        assertEquals("https://example.test/linux-x64.npl", selected.packageUrl());
        assertEquals("cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc", selected.sha256());
        assertEquals(73, selected.size());
        assertEquals(3, version.getArtifacts().size());

        IOException noMatch = assertThrows(
                IOException.class,
                () -> version.requireArtifact(PluginPlatformTarget.parse("linux-arm64"))
        );
        assertTrue(noMatch.getMessage().contains("linux-arm64"), noMatch.getMessage());
        assertTrue(noMatch.getMessage().contains("linux-x64"), noMatch.getMessage());
    }

    /// Rejects duplicate artifact targets and architecture-independent targets instead of applying native fallback.
    @Test
    public void rejectAmbiguousPlatformArtifactMatrices() {
        assertManifestRejected(schemaFiveArtifactManifest(
                "dev.hmclce.test.invalid-declarations",
                "\"pluginKind\": \"normal\",",
                """
                        {"platform": "windows-x64", "packageUrl": "https://example.test/one.npl",
                         "sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "size": 1},
                        {"platform": "windows-x64", "packageUrl": "https://example.test/two.npl",
                         "sha256": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", "size": 2}
                        """
        ));
        assertManifestRejected(schemaFiveArtifactManifest(
                "dev.hmclce.test.invalid-declarations",
                "\"pluginKind\": \"normal\",",
                """
                        {"platform": "windows", "packageUrl": "https://example.test/native.npl",
                         "sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "size": 1}
                        """
        ));
    }

    /// Rejects artifact sizes unless their JSON spelling is a positive base-ten integer that fits in a long.
    @Test
    public void rejectNonIntegralOrOverflowingArtifactSizes() {
        for (String size : List.of("1.0", "1e0", "18446744073709551617", "-1", "0")) {
            IOException exception = assertThrows(IOException.class, () -> parseManifest(
                    "dev.hmclce.test.invalid-declarations",
                    schemaFiveArtifactManifest(
                            "dev.hmclce.test.invalid-declarations",
                            "\"pluginKind\": \"normal\",",
                            """
                                    {"platform": "windows-x64", "packageUrl": "https://example.test/plugin.npl",
                                     "sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                                     "size": %s}
                                    """.formatted(size)
                    )
            ));
            assertInstanceOf(JsonParseException.class, exception.getCause());
        }
    }

    /// Keeps the legacy single-package compatibility view aligned with Manager's accepted IPv6 loopback spelling.
    @Test
    public void acceptIpv6LoopbackForLegacySinglePackageArtifactView() throws IOException {
        PluginStoreManifest.PluginVersionEntry version = parseManifest(
                "dev.hmclce.test.ipv6-loopback",
                """
                        {
                          "schemaVersion": 2,
                          "id": "dev.hmclce.test.ipv6-loopback",
                          "versions": [{
                            "version": "1.0.0",
                            "packageUrl": "http://[::1]:8080/plugin.npl",
                            "sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                            "pluginApiVersion": 4,
                            "permissions": [],
                            "requiredPermissions": [],
                            "launcherVersion": "*",
                            "dependencies": [],
                            "size": 1
                          }]
                        }
                        """
        ).getVersions().get(0);

        PluginStoreArtifact artifact = version.requireArtifact(PluginPlatformTarget.parse("windows-x64"));

        assertEquals("http://[::1]:8080/plugin.npl", artifact.packageUrl());
        assertEquals("windows-x64", artifact.platform().getId());
    }

    /// Requires exactly one package representation and mandates an artifact matrix for runtime providers.
    @Test
    public void enforceSchemaFiveArtifactRepresentation() throws IOException {
        assertManifestRejected(schemaFiveArtifactManifest(
                "dev.hmclce.test.invalid-declarations",
                "\"pluginKind\": \"normal\",",
                ""
        ));
        assertManifestRejected(schemaFiveVersionDeclarations("""
                "permissions": [],
                "requiredPermissions": [],
                "launcherVersion": "*",
                "runtime": "java",
                "abi": 2,
                "platforms": [],
                "pluginKind": "normal",
                "artifacts": [{
                  "platform": "windows-x64",
                  "packageUrl": "https://example.test/plugin.npl",
                  "sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                  "size": 1
                }],
                "dependencies": []
                """));
        assertManifestRejected(schemaFiveVersionDeclarations("""
                "permissions": ["native-code"],
                "requiredPermissions": ["native-code"],
                "launcherVersion": "*",
                "runtime": "java",
                "abi": 2,
                "platforms": [],
                "pluginKind": "runtime-provider",
                "dependencies": []
                """));

        PluginStoreManifest.PluginVersionEntry provider = parseManifest(
                "dev.hmclce.test.provider-artifacts",
                schemaFiveArtifactManifest(
                        "dev.hmclce.test.provider-artifacts",
                        """
                                "pluginKind": "runtime-provider",
                                "providesRuntimes": [{"runtime": "rust", "abis": [2], "bridgeAbi": 1,
                                  "executionModes": ["embedded"], "features": ["bridge"]}],
                                """,
                        """
                                {"platform": "windows-x64", "packageUrl": "https://example.test/provider.npl",
                                 "sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "size": 1}
                                """
                )
        ).getVersions().get(0);
        assertEquals(PluginKind.RUNTIME_PROVIDER, provider.getPluginKind());
        assertEquals(1, provider.getArtifacts().size());
    }

    /// Keeps schema-v1 manifests compatible without treating their optional dependency list as authoritative.
    @Test
    public void parseSchemaVersionOneManifest() throws IOException {
        PluginStoreManifest manifest = parseManifest("dev.hmclce.test.schema-one", """
                {
                  "schemaVersion": 1,
                  "id": "dev.hmclce.test.schema-one",
                  "versions": [
                    {
                      "version": "1.0.0",
                      "packageUrl": "https://example.com/plugin-1.0.0.npl",
                      "sha256": "0000000000000000000000000000000000000000000000000000000000000000",
                      "pluginApiVersion": 2,
                      "dependencies": ["dev.hmclce.test.base"],
                      "size": 1024
                    }
                  ]
                }
                """);
        PluginStoreManifest.PluginVersionEntry version = Objects.requireNonNull(manifest.getLatestVersion());

        assertEquals(1, manifest.getSchemaVersion());
        assertTrue(version.getPermissions().isEmpty());
        assertEquals("dev.hmclce.test.base", version.getDependencies().get(0).getId());
        assertEquals("*", version.getDependencies().get(0).getVersion());
        assertFalse(version.hasAuthoritativeDependencies());
        assertEquals("*", version.getLauncherVersion());
    }

    /// Sorts version history semantically and finds only exact published version strings.
    @Test
    public void sortVersionHistoryAndFindExactVersion() throws IOException {
        PluginStoreManifest manifest = parseManifest("dev.hmclce.test.history", """
                {
                  "schemaVersion": 2,
                  "id": "dev.hmclce.test.history",
                  "versions": [
                    {
                      "version": "1.9.0",
                      "packageUrl": "https://example.com/plugin-1.9.0.npl",
                      "sha256": "0000000000000000000000000000000000000000000000000000000000000000",
                      "pluginApiVersion": 2,
                      "size": 1024
                    },
                    {
                      "version": "2.0.0",
                      "packageUrl": "https://example.com/plugin-2.0.0.npl",
                      "sha256": "2222222222222222222222222222222222222222222222222222222222222222",
                      "pluginApiVersion": 2,
                      "size": 3072
                    },
                    {
                      "version": "1.10.0",
                      "packageUrl": "https://example.com/plugin-1.10.0.npl",
                      "sha256": "1111111111111111111111111111111111111111111111111111111111111111",
                      "pluginApiVersion": 2,
                      "size": 2048
                    }
                  ]
                }
                """);

        assertEquals(
                List.of("2.0.0", "1.10.0", "1.9.0"),
                manifest.getVersionsNewestFirst().stream()
                        .map(PluginStoreManifest.PluginVersionEntry::getVersion)
                        .toList()
        );
        assertEquals("1.10.0", Objects.requireNonNull(manifest.getVersion("1.10.0")).getVersion());
        assertEquals("2.0.0", Objects.requireNonNull(manifest.getLatestVersion()).getVersion());
        assertNull(manifest.getVersion("1.10"));
    }

    /// Rejects a version string that cannot participate in plugin version ordering.
    @Test
    public void rejectUnsortablePluginVersion() {
        assertThrows(IOException.class, () -> parseManifest("dev.hmclce.test.invalid-version", """
                {
                  "schemaVersion": 2,
                  "id": "dev.hmclce.test.invalid-version",
                  "versions": [{
                    "version": "garbage",
                    "packageUrl": "https://example.com/plugin.npl",
                    "sha256": "0000000000000000000000000000000000000000000000000000000000000000",
                    "pluginApiVersion": 2,
                    "size": 1024
                  }]
                }
                """));
    }

    /// Accepts a comparable semantic plugin version during manifest validation.
    @Test
    public void acceptComparablePluginVersion() throws IOException {
        PluginStoreManifest manifest = parseManifest("dev.hmclce.test.valid-version", """
                {
                  "schemaVersion": 2,
                  "id": "dev.hmclce.test.valid-version",
                  "versions": [{
                    "version": "1.2.3-beta.1+build.5",
                    "packageUrl": "https://example.com/plugin.npl",
                    "sha256": "0000000000000000000000000000000000000000000000000000000000000000",
                    "pluginApiVersion": 2,
                    "size": 1024
                  }]
                }
                """);

        assertEquals(
                "1.2.3-beta.1+build.5",
                Objects.requireNonNull(manifest.getLatestVersion()).getVersion()
        );
    }

    /// Rejects missing, unknown, and duplicate permission declarations for API-v3 packages.
    @Test
    public void rejectInvalidPermissionDeclarations() {
        assertManifestRejected(versionDeclarations("""
                "dependencies": []
                """));
        assertManifestRejected(versionDeclarations("""
                "permissions": ["unknown-permission"],
                "dependencies": []
                """));
        assertManifestRejected(versionDeclarations("""
                "permissions": ["network", "network"],
                "dependencies": []
                """));
    }

    /// Rejects invalid API-v4 required permission classifications and API-v3 use of the new field.
    @Test
    public void rejectInvalidRequiredPermissionDeclarations() {
        assertManifestRejected(schemaFourVersionDeclarations("""
                "permissions": ["filesystem"],
                "launcherVersion": "*",
                "dependencies": []
                """));
        assertManifestRejected(schemaFourVersionDeclarations("""
                "permissions": ["filesystem"],
                "requiredPermissions": ["filesystem", "filesystem"],
                "launcherVersion": "*",
                "dependencies": []
                """));
        assertManifestRejected(schemaFourVersionDeclarations("""
                "permissions": ["filesystem"],
                "requiredPermissions": ["network"],
                "launcherVersion": "*",
                "dependencies": []
                """));
        assertManifestRejected(schemaFourVersionDeclarations("""
                "permissions": ["mixin", "network"],
                "requiredPermissions": ["network"],
                "launcherVersion": "*",
                "dependencies": []
                """));
        assertManifestRejected(versionDeclarations("""
                "permissions": ["filesystem"],
                "requiredPermissions": ["filesystem"],
                "dependencies": []
                """));
    }

    /// Rejects schema-v5 runtime permissions in schema-v4 Store metadata.
    @Test
    public void rejectExternalRuntimePermissionsBeforeSchemaFive() {
        assertManifestRejected(schemaFourVersionDeclarations("""
                "permissions": ["launcher-core"],
                "requiredPermissions": ["launcher-core"],
                "launcherVersion": "*",
                "dependencies": []
                """));
    }

    /// Rejects missing, malformed, or schema-incompatible launcher version metadata.
    @Test
    public void rejectInvalidLauncherVersionDeclarations() {
        assertManifestRejected(schemaFourVersionDeclarations("""
                "permissions": [],
                "requiredPermissions": [],
                "dependencies": []
                """));
        assertManifestRejected(schemaFourVersionDeclarations("""
                "permissions": [],
                "requiredPermissions": [],
                "launcherVersion": ">=26.8 || <27",
                "dependencies": []
                """));
        assertManifestRejected(schemaFourVersionDeclarations("""
                "permissions": [],
                "requiredPermissions": [],
                "launcherVersion": "*",
                "minLauncherVersion": "26.8",
                "dependencies": []
                """));
        assertManifestRejected(versionDeclarations("""
                "permissions": [],
                "launcherVersion": "*",
                "dependencies": []
                """));
        assertManifestRejected(versionDeclarations("""
                "permissions": [],
                "minLauncherVersion": "bad version",
                "dependencies": []
                """));
    }

    /// Rejects null, duplicate, and self-referential dependency declarations before resolution begins.
    @Test
    public void rejectInvalidDependencyDeclarations() {
        assertManifestRejected(versionDeclarations("""
                "permissions": [],
                "dependencies": [null]
                """));
        assertManifestRejected(versionDeclarations("""
                "permissions": [],
                "dependencies": [
                  "dev.hmclce.test.base",
                  {"id": "dev.hmclce.test.base", "version": ">=1.0.0"}
                ]
                """));
        assertManifestRejected(versionDeclarations("""
                "permissions": [],
                "dependencies": ["dev.hmclce.test.invalid-declarations"]
                """));
    }

    /// Creates a complete schema-v2 manifest around caller-provided version declarations.
    ///
    /// @param declarationsJson permission and dependency properties for the version entry
    /// @return complete repository manifest JSON
    private static String versionDeclarations(String declarationsJson) {
        return versionDeclarations(3, declarationsJson);
    }

    /// Creates a complete schema-v2 manifest around caller-provided API-v4 version declarations.
    ///
    /// @param declarationsJson permission, launcher, and dependency properties for the version entry
    /// @return complete repository manifest JSON
    private static String schemaFourVersionDeclarations(String declarationsJson) {
        return versionDeclarations(4, declarationsJson);
    }

    /// Creates a complete schema-v2 manifest around caller-provided API-v5 version declarations.
    ///
    /// @param declarationsJson permission, launcher, runtime, ABI, platform, and dependency properties
    /// @return complete repository manifest JSON
    private static String schemaFiveVersionDeclarations(String declarationsJson) {
        return versionDeclarations(5, declarationsJson);
    }

    /// Creates a complete schema-v2 manifest around caller-provided version declarations.
    ///
    /// @param pluginApiVersion package manifest schema version
    /// @param declarationsJson permission, launcher, and dependency properties for the version entry
    /// @return complete repository manifest JSON
    private static String versionDeclarations(int pluginApiVersion, String declarationsJson) {
        return """
                {
                  "schemaVersion": 2,
                  "id": "dev.hmclce.test.invalid-declarations",
                  "versions": [
                    {
                      "version": "1.0.0",
                      "packageUrl": "https://example.com/plugin.npl",
                      "sha256": "0000000000000000000000000000000000000000000000000000000000000000",
                      "pluginApiVersion": %d,
                      "size": 1024,
                      %s
                    }
                  ]
                }
                """.formatted(pluginApiVersion, declarationsJson);
    }

    /// Creates a schema-v5 Store manifest whose package identity belongs only to platform artifacts.
    ///
    /// @param pluginId manifest plugin ID
    /// @param pluginKindDeclaration serialized plugin kind declaration
    /// @param artifactsJson serialized artifact objects without the surrounding array
    /// @return complete Store manifest JSON
    private static String schemaFiveArtifactManifest(
            String pluginId,
            String pluginKindDeclaration,
            String artifactsJson
    ) {
        return """
                {
                  "schemaVersion": 2,
                  "id": "%s",
                  "versions": [{
                    "version": "1.0.0",
                    "pluginApiVersion": 5,
                    "permissions": [],
                    "requiredPermissions": [],
                    "launcherVersion": "*",
                    "runtime": "java",
                    "abi": 2,
                    "platforms": [],
                    %s
                    "artifacts": [%s],
                    "dependencies": []
                  }]
                }
                """.formatted(pluginId, pluginKindDeclaration, artifactsJson);
    }

    /// Parses and validates one repository manifest fixture.
    ///
    /// @param expectedPluginId plugin ID bound to the repository
    /// @param json repository manifest JSON
    /// @return validated repository manifest
    /// @throws IOException if the fixture violates repository validation
    private static PluginStoreManifest parseManifest(String expectedPluginId, String json) throws IOException {
        return PluginStoreManifest.fromJson(JsonParser.parseString(json), expectedPluginId);
    }

    /// Asserts that repository validation rejects an invalid declaration fixture.
    ///
    /// @param json repository manifest JSON
    private static void assertManifestRejected(String json) {
        assertThrows(
                IOException.class,
                () -> parseManifest("dev.hmclce.test.invalid-declarations", json)
        );
    }
}
