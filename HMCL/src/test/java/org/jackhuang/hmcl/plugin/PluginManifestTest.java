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
package org.jackhuang.hmcl.plugin;

import org.jackhuang.hmcl.plugin.runtime.PluginAbi;
import org.jackhuang.hmcl.plugin.runtime.PluginExecutionMode;
import org.jackhuang.hmcl.plugin.runtime.PluginRuntimeTypes;
import org.jackhuang.hmcl.plugin.runtime.RuntimeFeature;
import org.jackhuang.hmcl.plugin.runtime.RuntimeProviderDeclaration;
import org.jackhuang.hmcl.plugin.runtime.RuntimeRequirement;
import org.jackhuang.hmcl.util.gson.JsonUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies legacy through schema-v5 plugin manifest parsing, validation, and runtime-provider contracts.
@NotNullByDefault
public final class PluginManifestTest {
    /// Parses a schema-v1 manifest with legacy string dependencies and no permission declaration.
    @Test
    public void parseSchemaVersionOneManifest() throws IOException {
        PluginManifest manifest = PluginManifest.fromJson(new StringReader("""
                {
                  "id": "dev.hmclce.test.legacy-one",
                  "name": "Legacy One",
                  "version": "1.0.0",
                  "type": "java",
                  "entrypoint": "dev.hmclce.test.Plugin",
                  "dependencies": ["dev.hmclce.test.base"]
                }
                """));

        assertEquals(1, manifest.getSchemaVersion());
        assertEquals(List.of("dev.hmclce.test.base"), manifest.getDependencies());
        assertEquals("*", manifest.getPluginDependencies().get(0).getVersion());
        assertTrue(manifest.getPermissions().isEmpty());
    }

    /// Parses a valid JVM Mixin manifest and exposes immutable configuration names.
    @Test
    public void parseMixinManifest() throws IOException {
        PluginManifest manifest = PluginManifest.fromJson(new StringReader("""
                {
                  "schemaVersion": 2,
                  "id": "dev.hmclce.test.mixin",
                  "name": "Mixin Test",
                  "version": "1.0.0",
                  "type": "java",
                  "entrypoint": "dev.hmclce.test.Plugin",
                  "dependencies": ["dev.hmclce.test.base"],
                  "mixins": ["mixins.dev.hmclce.test.json"]
                }
                """));

        assertEquals(2, manifest.getSchemaVersion());
        assertTrue(manifest.hasMixins());
        assertEquals("mixins.dev.hmclce.test.json", manifest.getMixins().get(0));
    }

    /// Parses schema-v3 permissions and both legacy and structured dependency representations.
    @Test
    public void parseSchemaVersionThreeManifest() throws IOException {
        PluginManifest manifest = PluginManifest.fromJson(new StringReader("""
                {
                  "schemaVersion": 3,
                  "id": "dev.hmclce.test.schema-three",
                  "name": "Schema Three",
                  "version": "3.0.0",
                  "type": "java",
                  "entrypoint": "dev.hmclce.test.Plugin",
                  "permissions": ["filesystem", "game-launch", "native-code"],
                  "dependencies": [
                    "dev.hmclce.test.legacy",
                    {"id": "dev.hmclce.test.ranged", "version": ">=1.2.0, <2.0.0"},
                    {"id": "dev.hmclce.test.any"}
                  ]
                }
                """));

        assertEquals(3, manifest.getSchemaVersion());
        assertIterableEquals(
                List.of(PluginPermission.FILESYSTEM, PluginPermission.GAME_LAUNCH, PluginPermission.NATIVE_CODE),
                manifest.getPermissions()
        );
        assertTrue(manifest.declaresPermission(PluginPermission.GAME_LAUNCH));
        assertFalse(manifest.declaresPermission(PluginPermission.NETWORK));
        assertTrue(manifest.getRequiredPermissions().isEmpty());
        assertEquals(manifest.getPermissions(), manifest.getOptionalPermissions());
        assertEquals(List.of(
                "dev.hmclce.test.legacy",
                "dev.hmclce.test.ranged",
                "dev.hmclce.test.any"
        ), manifest.getDependencies());
        assertEquals("*", manifest.getPluginDependencies().get(0).getVersion());
        assertEquals(">=1.2.0, <2.0.0", manifest.getPluginDependencies().get(1).getVersion());
        assertEquals("*", manifest.getPluginDependencies().get(2).getVersion());
    }

    /// Parses schema-v4 required and optional permissions together with a launcher version range.
    @Test
    public void parseSchemaVersionFourManifest() throws IOException {
        PluginManifest manifest = PluginManifest.fromJson(new StringReader("""
                {
                  "schemaVersion": 4,
                  "id": "dev.hmclce.test.schema-four",
                  "name": "Schema Four",
                  "version": "4.0.0",
                  "type": "java",
                  "entrypoint": "dev.hmclce.test.Plugin",
                  "permissions": ["filesystem", "network", "launcher-ui"],
                  "requiredPermissions": ["filesystem", "launcher-ui"],
                  "launcherVersion": ">=26.8-beta.1, <27.0",
                  "dependencies": []
                }
                """));

        assertEquals(4, manifest.getSchemaVersion());
        assertEquals(
                List.of(PluginPermission.FILESYSTEM, PluginPermission.LAUNCHER_UI),
                manifest.getRequiredPermissions()
        );
        assertEquals(List.of(PluginPermission.NETWORK), manifest.getOptionalPermissions());
        assertTrue(manifest.isPermissionRequired(PluginPermission.LAUNCHER_UI));
        assertFalse(manifest.isPermissionRequired(PluginPermission.NETWORK));
        assertEquals(">=26.8-beta.1, <27.0", manifest.getLauncherVersion());
        assertTrue(manifest.matchesLauncherVersion("26.8-beta.3"));
        assertFalse(manifest.matchesLauncherVersion("27.0"));
        assertEquals(PluginRuntimeTypes.JAVA, manifest.getRuntime());
        assertEquals(PluginAbi.ABI_1, manifest.getAbi());
        assertEquals(List.of(), manifest.getPlatforms());
        assertFalse(manifest.isPlatformRestricted());
    }

    /// Parses a valid schema-v5 manifest whose empty platform list leaves it unrestricted.
    @Test
    public void parseValidSchemaVersionFiveManifest() throws IOException {
        PluginManifest manifest = PluginManifest.fromJson(new StringReader("""
                {
                  "schemaVersion": 5,
                  "id": "dev.hmclce.test.schema-five",
                  "name": "Schema Five",
                  "version": "5.0.0",
                  "type": "java",
                  "entrypoint": "dev.hmclce.test.Plugin",
                  "permissions": [],
                  "requiredPermissions": [],
                  "launcherVersion": "*",
                  "runtime": "java",
                  "abi": 2,
                  "platforms": []
                }
                """));

        assertEquals(5, manifest.getSchemaVersion());
        assertEquals(PluginRuntimeTypes.JAVA, manifest.getRuntime());
        assertEquals(PluginAbi.ABI_2, manifest.getAbi());
        assertEquals(List.of(), manifest.getPlatforms());
        assertFalse(manifest.isPlatformRestricted());
    }

    /// Treats Rust and WebAssembly as canonical official runtime identifiers rather than native code aliases.
    @Test
    public void recognizeRustAndWasmRuntimeIdentifiers() {
        assertEquals(PluginRuntimeTypes.RUST, PluginRuntimeTypes.requireValid("rust"));
        assertEquals(PluginRuntimeTypes.WASM, PluginRuntimeTypes.requireValid("wasm"));
        assertTrue(PluginRuntimeTypes.RESERVED.contains(PluginRuntimeTypes.RUST));
        assertTrue(PluginRuntimeTypes.RESERVED.contains(PluginRuntimeTypes.WASM));
    }

    /// Defaults an ordinary schema-v5 manifest to embedded execution and derives its runtime bridge requirement.
    @Test
    public void defaultSchemaVersionFiveExecutionModeAndRuntimeRequirement() throws IOException {
        PluginManifest manifest = PluginManifest.fromJson(new StringReader(schemaFiveWithDeclarations(
                "\"runtime\": \"rust\", \"abi\": 2")));

        assertEquals(PluginKind.NORMAL, manifest.getPluginKind());
        assertEquals(PluginExecutionMode.EMBEDDED, manifest.getExecutionMode());
        RuntimeRequirement requirement = manifest.getRuntimeRequirement();
        assertEquals(PluginRuntimeTypes.RUST, requirement.getRuntime());
        assertEquals(PluginExecutionMode.EMBEDDED, requirement.getExecutionMode());
        assertEquals(Set.of(RuntimeFeature.BRIDGE), requirement.getRequiredFeatures());
    }

    /// Parses canonical embedded and isolated execution mode vocabulary for schema-v5 packages.
    @Test
    public void parseSchemaVersionFiveExecutionModes() throws IOException {
        PluginManifest embedded = PluginManifest.fromJson(new StringReader(schemaFiveWithDeclarations(
                "\"runtime\": \"java\", \"abi\": 2, \"executionMode\": \"embedded\"")));
        PluginManifest isolated = PluginManifest.fromJson(new StringReader(schemaFiveWithDeclarations(
                "\"runtime\": \"java\", \"abi\": 2, \"executionMode\": \"isolated\"")));

        assertEquals(PluginExecutionMode.EMBEDDED, embedded.getExecutionMode());
        assertEquals(PluginExecutionMode.ISOLATED, isolated.getExecutionMode());
    }

    /// Parses a Java bootstrap package that publishes an isolated Rust runtime implementation.
    @Test
    public void parseSchemaVersionFiveRuntimeProviderManifest() throws IOException {
        PluginManifest manifest = PluginManifest.fromJson(new StringReader(schemaFiveWithDeclarations("""
                "runtime": "java",
                "abi": 2,
                "pluginKind": "runtime-provider",
                "providesRuntimes": [{"runtime": "rust", "abis": [1, 2], "bridgeAbi": 1,
                                      "executionModes": ["isolated"], "features": ["bridge", "hooks"]}]
                """)));

        assertEquals(PluginKind.RUNTIME_PROVIDER, manifest.getPluginKind());
        assertEquals(PluginExecutionMode.EMBEDDED, manifest.getExecutionMode());
        assertEquals(1, manifest.getProvidesRuntimes().size());
        assertEquals(PluginRuntimeTypes.RUST, manifest.getProvidesRuntimes().get(0).getRuntime());
        assertEquals(Set.of(PluginAbi.ABI_1, PluginAbi.ABI_2), manifest.getProvidesRuntimes().get(0).getAbis());
        assertEquals("\"runtime-provider\"", JsonUtils.GSON.toJson(PluginKind.RUNTIME_PROVIDER));
        assertEquals("\"isolated\"", JsonUtils.GSON.toJson(PluginExecutionMode.ISOLATED));
        assertEquals("\"raw-jvm\"", JsonUtils.GSON.toJson(RuntimeFeature.RAW_JVM));
    }

    /// Preserves future positive Bridge ABI declarations so compatibility can diagnose negotiation mismatches.
    ///
    /// @throws IOException if the valid future-ABI provider manifest cannot be parsed
    @Test
    public void parseFuturePositiveRuntimeProviderBridgeAbi() throws IOException {
        PluginManifest manifest = PluginManifest.fromJson(new StringReader(schemaFiveWithDeclarations("""
                "runtime": "java",
                "abi": 2,
                "pluginKind": "runtime-provider",
                "providesRuntimes": [{"runtime": "rust", "abis": [2], "bridgeAbi": 2,
                                      "executionModes": ["embedded"], "features": ["bridge"]}]
                """)));

        assertEquals(2, manifest.getProvidesRuntimes().get(0).getBridgeAbi());
        assertManifestRejectedAtParsingOrValidation(schemaFiveWithDeclarations("""
                "runtime": "java",
                "abi": 2,
                "pluginKind": "runtime-provider",
                "providesRuntimes": [{"runtime": "rust", "abis": [2], "bridgeAbi": 0,
                                      "executionModes": ["embedded"], "features": ["bridge"]}]
                """));
    }

    /// Rejects schema-v5 declarations that are structurally incompatible with provider selection.
    @Test
    public void rejectInvalidSchemaVersionFiveRuntimeProviderDeclarations() {
        assertManifestRejected(schemaFiveWithDeclarations(
                "\"runtime\": \"rust\", \"abi\": 2, \"pluginKind\": \"runtime-provider\","
                        + " \"providesRuntimes\": [{\"runtime\": \"rust\", \"abis\": [2], \"bridgeAbi\": 1,"
                        + " \"executionModes\": [\"isolated\"], \"features\": [\"bridge\"]}]"));
        assertManifestRejected(schemaFiveWithDeclarations(
                "\"runtime\": \"java\", \"abi\": 2, \"pluginKind\": \"runtime-provider\","
                        + " \"runtimeProvider\": \"dev.hmclce.test.provider\", \"providesRuntimes\": []"));
        assertManifestRejected(schemaFiveWithDeclarations(
                "\"runtime\": \"java\", \"abi\": 2, \"pluginKind\": \"runtime-provider\","
                        + " \"providesRuntimes\": [{\"runtime\": \"rust\", \"abis\": [2], \"bridgeAbi\": 1,"
                        + " \"executionModes\": [\"isolated\"], \"features\": [\"bridge\"]},"
                        + " {\"runtime\": \"rust\", \"abis\": [2], \"bridgeAbi\": 1,"
                        + " \"executionModes\": [\"isolated\"], \"features\": [\"bridge\"]}]"));
        assertManifestRejected(schemaFiveWithDeclarations(
                "\"runtime\": \"java\", \"abi\": 2, \"providesRuntimes\":"
                        + " [{\"runtime\": \"rust\", \"abis\": [2], \"bridgeAbi\": 1,"
                        + " \"executionModes\": [\"isolated\"], \"features\": [\"bridge\"]}]"));
        assertManifestRejected(schemaFiveWithDeclarations(
                "\"runtime\": \"java\", \"abi\": 2, \"pluginKind\": \"runtime-provider\","
                        + " \"executionMode\": \"isolated\", \"providesRuntimes\": [{\"runtime\": \"rust\","
                        + " \"abis\": [2], \"bridgeAbi\": 1, \"executionModes\": [\"isolated\"],"
                        + " \"features\": [\"bridge\"]}]"));
    }

    /// Rejects isolated raw-JVM runtime requirements before a provider can be selected.
    @Test
    public void rejectIsolatedRawJvmRuntimeRequirement() {
        assertThrows(IllegalArgumentException.class, () -> new RuntimeRequirement(
                PluginRuntimeTypes.JAVA,
                PluginAbi.ABI_2,
                1,
                PluginExecutionMode.ISOLATED,
                Set.of(RuntimeFeature.BRIDGE, RuntimeFeature.RAW_JVM),
                null));
    }

    /// Rejects non-canonical runtime identifiers in direct runtime requirement construction.
    @Test
    public void rejectNonCanonicalRuntimeRequirementIdentifier() {
        assertThrows(IllegalArgumentException.class, () -> new RuntimeRequirement(
                " Java ",
                PluginAbi.ABI_2,
                1,
                PluginExecutionMode.EMBEDDED,
                Set.of(RuntimeFeature.BRIDGE),
                null));
    }

    /// Preserves future plugin ABI declarations from runtime providers while rejecting non-positive values.
    @Test
    public void acceptFutureRuntimeProviderAbiDeclarations() {
        RuntimeProviderDeclaration declaration = assertDoesNotThrow(() -> new RuntimeProviderDeclaration(
                PluginRuntimeTypes.RUST,
                Set.of(PluginAbi.ABI_2, 3),
                1,
                Set.of(PluginExecutionMode.ISOLATED),
                Set.of(RuntimeFeature.BRIDGE)));

        assertEquals(Set.of(PluginAbi.ABI_2, 3), declaration.getAbis());
        assertThrows(IllegalArgumentException.class, () -> new RuntimeProviderDeclaration(
                PluginRuntimeTypes.RUST,
                Set.of(0),
                1,
                Set.of(PluginExecutionMode.ISOLATED),
                Set.of(RuntimeFeature.BRIDGE)));
    }

    /// Rejects null runtime declarations through manifest validation rather than leaking a getter null pointer.
    @Test
    public void rejectNullProvidedRuntimeDeclaration() {
        assertManifestRejected(schemaFiveWithDeclarations(
                "\"runtime\": \"java\", \"abi\": 2, \"providesRuntimes\": [null]"));
    }

    /// Rejects attempts to publish the launcher's reserved built-in Java runtime from a plugin package.
    @Test
    public void rejectProvidedBuiltInJavaRuntime() {
        assertManifestRejected(schemaFiveWithDeclarations("""
                "runtime": "java",
                "abi": 2,
                "pluginKind": "runtime-provider",
                "providesRuntimes": [{"runtime": "java", "abis": [2], "bridgeAbi": 1,
                                      "executionModes": ["embedded"], "features": ["bridge"]}]
                """));
    }

    /// Rejects non-canonical schema-v5 enum spellings at the manifest and runtime-provider declaration levels.
    @Test
    public void rejectNonCanonicalSchemaVersionFiveRuntimeProviderEnumTokens() {
        assertManifestRejected(schemaFiveWithDeclarations("""
                "runtime": "java",
                "abi": 2,
                "pluginKind": "RUNTIME-PROVIDER",
                "providesRuntimes": [{"runtime": "rust", "abis": [2], "bridgeAbi": 1,
                                      "executionModes": ["isolated"], "features": ["bridge"]}]
                """));
        assertManifestRejected(schemaFiveWithDeclarations(
                "\"runtime\": \"java\", \"abi\": 2, \"executionMode\": \"ISOLATED\""));
        assertManifestRejectedAtParsingOrValidation(schemaFiveWithDeclarations("""
                "runtime": "java",
                "abi": 2,
                "pluginKind": "runtime-provider",
                "providesRuntimes": [{"runtime": "rust", "abis": [2], "bridgeAbi": 1,
                                      "executionModes": ["ISOLATED"], "features": ["bridge"]}]
                """));
        assertManifestRejectedAtParsingOrValidation(schemaFiveWithDeclarations("""
                "runtime": "java",
                "abi": 2,
                "pluginKind": "runtime-provider",
                "providesRuntimes": [{"runtime": "rust", "abis": [2], "bridgeAbi": 1,
                                      "executionModes": ["isolated"], "features": ["Bridge"]}]
                """));
        assertManifestRejectedAtParsingOrValidation(schemaFiveWithDeclarations("""
                "runtime": "java",
                "abi": 2,
                "pluginKind": "runtime-provider",
                "providesRuntimes": [{"runtime": "rust", "abis": [2], "bridgeAbi": 1,
                                      "executionModes": ["isolated"], "features": ["RAW-JVM"]}]
                """));
    }

    /// Rejects coercible JSON values and non-integer provider ABI values before manifest validation uses them.
    @Test
    public void rejectInvalidSchemaVersionFiveRuntimeProviderFieldTypes() {
        assertManifestRejectedAtParsingOrValidation(schemaFiveWithDeclarations(
                "\"runtime\": \"java\", \"abi\": 2, \"pluginKind\": true"));
        assertManifestRejectedAtParsingOrValidation(schemaFiveWithDeclarations(
                "\"runtime\": \"java\", \"abi\": 2, \"executionMode\": 1"));
        assertManifestRejectedAtParsingOrValidation(schemaFiveWithDeclarations(
                "\"runtime\": \"java\", \"abi\": 2, \"runtimeProvider\": true"));
        assertManifestRejectedAtParsingOrValidation(schemaFiveWithDeclarations(
                "\"runtime\": \"java\", \"abi\": 2, \"runtimeProvider\": 123"));
        assertManifestRejectedAtParsingOrValidation(schemaFiveWithDeclarations(
                "\"runtime\": \"java\", \"abi\": 2, \"providesRuntimes\": true"));
        assertManifestRejectedAtParsingOrValidation(schemaFiveWithDeclarations("""
                "runtime": "java",
                "abi": 2,
                "pluginKind": "runtime-provider",
                "providesRuntimes": [{"runtime": "rust", "abis": [2.5], "bridgeAbi": 1,
                                      "executionModes": ["isolated"], "features": ["bridge"]}]
                """));
        assertManifestRejectedAtParsingOrValidation(schemaFiveWithDeclarations("""
                "runtime": "java",
                "abi": 2,
                "pluginKind": "runtime-provider",
                "providesRuntimes": [{"runtime": "rust", "abis": [2], "bridgeAbi": 1.5,
                                      "executionModes": ["isolated"], "features": ["bridge"]}]
                """));
    }

    /// Includes the schema-v5 provider vocabulary in manifest value identity.
    @Test
    public void compareSchemaVersionFiveRuntimeProviderIdentity() throws IOException {
        String base = schemaFiveWithDeclarations("""
                "runtime": "java",
                "abi": 2,
                "executionMode": "embedded",
                "runtimeProvider": "dev.hmclce.test.provider"
                """);
        PluginManifest provider = PluginManifest.fromJson(new StringReader(base));
        PluginManifest identical = PluginManifest.fromJson(new StringReader(base));

        assertEquals(provider, identical);
        assertEquals(provider.hashCode(), identical.hashCode());
        assertNotEquals(provider, PluginManifest.fromJson(new StringReader(
                base.replace("\"executionMode\": \"embedded\"", "\"executionMode\": \"isolated\""))));
        assertNotEquals(provider, PluginManifest.fromJson(new StringReader(
                base.replace("dev.hmclce.test.provider", "dev.hmclce.test.other-provider"))));
        String providerBase = schemaFiveWithDeclarations("""
                "runtime": "java",
                "abi": 2,
                "pluginKind": "runtime-provider",
                "providesRuntimes": [{"runtime": "rust", "abis": [2], "bridgeAbi": 1,
                                      "executionModes": ["isolated"], "features": ["bridge"]}]
                """);
        assertNotEquals(
                PluginManifest.fromJson(new StringReader(providerBase)),
                PluginManifest.fromJson(new StringReader(providerBase.replace("\"rust\"", "\"wasm\"")))
        );
        assertNotEquals(
                PluginManifest.fromJson(new StringReader(providerBase)),
                PluginManifest.fromJson(new StringReader(schemaFiveWithDeclarations("""
                        "runtime": "java",
                        "abi": 2
                        """)))
        );
    }

    /// Rejects all schema-v5 provider declarations when attached to schema-v4 manifests.
    @Test
    public void rejectSchemaVersionFiveProviderFieldsInSchemaVersionFour() {
        assertManifestRejected(schemaFourWithDeclarations("""
                "permissions": [], "requiredPermissions": [], "launcherVersion": "*",
                "pluginKind": "normal", "executionMode": "embedded",
                "runtimeProvider": "dev.hmclce.test.provider", "providesRuntimes": []
                """));
        assertManifestRejected(schemaFourWithDeclarations("""
                "permissions": [], "requiredPermissions": [], "launcherVersion": "*",
                "executionMode": "embedded"
                """));
        assertManifestRejected(schemaFourWithDeclarations("""
                "permissions": [], "requiredPermissions": [], "launcherVersion": "*",
                "runtimeProvider": "dev.hmclce.test.provider"
                """));
        assertManifestRejected(schemaFourWithDeclarations("""
                "permissions": [], "requiredPermissions": [], "launcherVersion": "*",
                "providesRuntimes": []
                """));
    }

    /// Rejects missing, null, blank, and non-canonical schema-v5 runtime identifiers.
    @Test
    public void rejectInvalidSchemaVersionFiveRuntime() {
        assertManifestRejected(schemaFiveWithDeclarations("\"abi\": 2"));
        assertManifestRejected(schemaFiveWithDeclarations("\"runtime\": null, \"abi\": 2"));
        assertManifestRejected(schemaFiveWithDeclarations("\"runtime\": \" \", \"abi\": 2"));
        assertManifestRejected(schemaFiveWithDeclarations("\"runtime\": \"Java\", \"abi\": 2"));
        assertManifestRejected(schemaFiveWithDeclarations("\"runtime\": \" java \", \"abi\": 2"));
    }

    /// Rejects missing, null, and unsupported schema-v5 ABI declarations.
    @Test
    public void rejectInvalidSchemaVersionFiveAbi() {
        assertManifestRejected(schemaFiveWithDeclarations("\"runtime\": \"java\""));
        assertManifestRejected(schemaFiveWithDeclarations("\"runtime\": \"java\", \"abi\": null"));
        assertManifestRejected(schemaFiveWithDeclarations("\"runtime\": \"java\", \"abi\": 0"));
        assertManifestRejected(schemaFiveWithDeclarations("\"runtime\": \"java\", \"abi\": 3"));
    }

    /// Rejects null, non-canonical, unknown, and normalized-duplicate platform targets.
    @Test
    public void rejectInvalidSchemaVersionFivePlatforms() {
        assertManifestRejected(schemaFiveWithDeclarations(
                "\"runtime\": \"java\", \"abi\": 2, \"platforms\": null"));
        assertManifestRejected(schemaFiveWithDeclarations(
                "\"runtime\": \"java\", \"abi\": 2, \"platforms\": [null]"));
        assertManifestRejected(schemaFiveWithDeclarations(
                "\"runtime\": \"java\", \"abi\": 2, \"platforms\": [\"Windows-X64\"]"));
        assertManifestRejected(schemaFiveWithDeclarations(
                "\"runtime\": \"java\", \"abi\": 2, \"platforms\": [\" windows\"]"));
        assertManifestRejected(schemaFiveWithDeclarations(
                "\"runtime\": \"java\", \"abi\": 2, \"platforms\": [\"os2-x64\"]"));
        assertManifestRejected(schemaFiveWithDeclarations(
                "\"runtime\": \"java\", \"abi\": 2, \"platforms\": [\"windows-x64\", \"Windows-X64\"]"));
        assertManifestRejected(schemaFiveWithDeclarations(
                "\"runtime\": \"java\", \"abi\": 2, \"platforms\": [\"windows-x64\", \"windows-x64\"]"));
    }

    /// Parses absent, operating-system-only, and operating-system/architecture platform declarations.
    @Test
    public void parseSchemaVersionFivePlatformVariants() throws IOException {
        PluginManifest unrestricted = PluginManifest.fromJson(new StringReader(
                schemaFiveWithDeclarations("\"runtime\": \"java\", \"abi\": 2")));
        PluginManifest restricted = PluginManifest.fromJson(new StringReader(schemaFiveWithDeclarations(
                "\"runtime\": \"java\", \"abi\": 2, \"platforms\": [\"linux\", \"windows-x64\"]")));

        assertEquals(List.of(), unrestricted.getPlatforms());
        assertFalse(unrestricted.isPlatformRestricted());
        assertEquals(List.of("linux", "windows-x64"), restricted.getPlatforms());
        assertTrue(restricted.isPlatformRestricted());
        assertThrows(UnsupportedOperationException.class, () -> restricted.getPlatforms().add("macos"));
    }

    /// Normalizes platform declaration order for deterministic manifest value identity.
    @Test
    public void comparePlatformSetsIndependentOfDeclarationOrder() throws IOException {
        PluginManifest first = PluginManifest.fromJson(new StringReader(schemaFiveWithDeclarations(
                "\"runtime\": \"java\", \"abi\": 2, \"platforms\": [\"linux\", \"windows-x64\"]")));
        PluginManifest reversed = PluginManifest.fromJson(new StringReader(schemaFiveWithDeclarations(
                "\"runtime\": \"java\", \"abi\": 2, \"platforms\": [\"windows-x64\", \"linux\"]")));

        assertEquals(List.of("linux", "windows-x64"), first.getPlatforms());
        assertEquals(first.getPlatforms(), reversed.getPlatforms());
        assertEquals(first, reversed);
        assertEquals(first.hashCode(), reversed.hashCode());
    }

    /// Requires every patch declaration to contain an explicit ordered parameter array.
    @Test
    public void requirePatchParameters() {
        PluginPatchDeclaration declaration = JsonUtils.GSON.fromJson("""
                {
                  "target": "org.jackhuang.hmcl.Launcher",
                  "method": "launch",
                  "type": "before"
                }
                """, PluginPatchDeclaration.class);

        assertThrows(IllegalArgumentException.class, declaration::validate);
    }

    /// Distinguishes a missing parameter declaration from an explicit no-argument overload.
    @Test
    public void distinguishMissingFromEmptyPatchParameters() {
        PluginPatchDeclaration missing = new PluginPatchDeclaration();
        PluginPatchDeclaration noArguments = new PluginPatchDeclaration(
                "org.example.GameLaunchService",
                "launch",
                PluginPatchDeclaration.PatchType.BEFORE,
                List.of());

        assertThrows(IllegalStateException.class, missing::getParameters);
        assertEquals(List.of(), noArguments.getParameters());
        assertThrows(UnsupportedOperationException.class, () -> noArguments.getParameters().add("int"));
    }

    /// Accepts Java binary names for nested classes as patch targets.
    @Test
    public void acceptNestedClassBinaryPatchTarget() {
        PluginPatchDeclaration declaration = new PluginPatchDeclaration(
                "org.example.Outer$Inner",
                "launch",
                PluginPatchDeclaration.PatchType.BEFORE,
                List.of());

        assertEquals("org.example.Outer$Inner", declaration.getTarget());
    }

    /// Accepts ordinary and compiler-generated Java method names containing dollar signs.
    @Test
    public void acceptGeneratedJavaPatchMethodNames() {
        for (String method : List.of("launch", "access$000", "lambda$launch$0", "launch$default")) {
            PluginPatchDeclaration declaration = new PluginPatchDeclaration(
                    "org.example.GameLaunchService",
                    method,
                    PluginPatchDeclaration.PatchType.BEFORE,
                    List.of());

            assertEquals(method, declaration.getMethod());
        }
    }

    /// Rejects patch declarations with missing, null, or malformed fields.
    @Test
    public void rejectInvalidPatchDeclarationFields() {
        assertPatchRejected("""
                {"method": "launch", "type": "before", "parameters": []}
                """);
        assertPatchRejected("""
                {"target": "org.jackhuang.hmcl.Launcher", "type": "before", "parameters": []}
                """);
        assertPatchRejected("""
                {"target": "org.jackhuang.hmcl.Launcher", "method": "launch", "parameters": []}
                """);
        assertPatchRejected("""
                {"target": "GameLaunchService", "method": "launch", "type": "before", "parameters": []}
                """);
        assertPatchRejected("""
                {"target": "org.jackhuang.hmcl.Launcher", "method": "bad-method", "type": "before",
                 "parameters": []}
                """);
        assertPatchRejected("""
                {"target": "org.jackhuang.hmcl.Launcher", "method": " ", "type": "before",
                 "parameters": []}
                """);
        assertPatchRejected("""
                {"target": "org.jackhuang.hmcl.Launcher", "method": "launch", "type": "unknown",
                 "parameters": []}
                """);
        assertPatchRejected("""
                {"target": "org.jackhuang.hmcl.Launcher", "method": "launch", "type": "before",
                 "parameters": null}
                """);
        assertPatchRejected("""
                {"target": "org.jackhuang.hmcl.Launcher", "method": "launch", "type": "before",
                 "parameters": [null]}
                """);
        assertPatchRejected("""
                {"target": "org.jackhuang.hmcl.Launcher", "method": "launch", "type": "before",
                 "parameters": [" "]}
                """);
    }

    /// Defensively copies constructor parameters and exposes them through an immutable ordered list.
    @Test
    public void constructImmutablePatchParameters() {
        List<String> source = new ArrayList<>(List.of("java.lang.String", "int"));
        PluginPatchDeclaration declaration = assertDoesNotThrow(() ->
                PluginPatchDeclaration.class
                        .getConstructor(String.class, String.class,
                                PluginPatchDeclaration.PatchType.class, List.class)
                        .newInstance("org.jackhuang.hmcl.Launcher", "launch",
                                PluginPatchDeclaration.PatchType.BEFORE, source));
        source.set(0, "changed");
        Object parameters = assertDoesNotThrow(() ->
                PluginPatchDeclaration.class.getMethod("getParameters").invoke(declaration));

        assertEquals(List.of("java.lang.String", "int"), parameters);
        assertThrows(UnsupportedOperationException.class, () -> ((List<?>) parameters).clear());
    }

    /// Includes target, method, type, and ordered parameters in patch value identity.
    @Test
    public void comparePatchDeclarationIdentity() {
        PluginPatchDeclaration base = new PluginPatchDeclaration(
                "org.jackhuang.hmcl.Launcher", "launch", PluginPatchDeclaration.PatchType.BEFORE,
                List.of("java.lang.String", "int"));
        PluginPatchDeclaration identical = new PluginPatchDeclaration(
                "org.jackhuang.hmcl.Launcher", "launch", PluginPatchDeclaration.PatchType.BEFORE,
                List.of("java.lang.String", "int"));

        assertEquals(base, identical);
        assertEquals(base.hashCode(), identical.hashCode());
        assertNotEquals(base, new PluginPatchDeclaration(
                "org.jackhuang.hmcl.Other", "launch", PluginPatchDeclaration.PatchType.BEFORE,
                List.of("java.lang.String", "int")));
        assertNotEquals(base, new PluginPatchDeclaration(
                "org.jackhuang.hmcl.Launcher", "start", PluginPatchDeclaration.PatchType.BEFORE,
                List.of("java.lang.String", "int")));
        assertNotEquals(base, new PluginPatchDeclaration(
                "org.jackhuang.hmcl.Launcher", "launch", PluginPatchDeclaration.PatchType.AFTER,
                List.of("java.lang.String", "int")));
        assertNotEquals(base, new PluginPatchDeclaration(
                "org.jackhuang.hmcl.Launcher", "launch", PluginPatchDeclaration.PatchType.BEFORE,
                List.of("int", "java.lang.String")));
    }

    /// Parses schema-v5 hook and patch declarations through the immutable manifest capability API.
    @Test
    public void parseSchemaVersionFiveCapabilities() throws IOException {
        PluginManifest manifest = PluginManifest.fromJson(new StringReader("""
                {
                  "schemaVersion": 5,
                  "id": "dev.hmclce.test.capabilities",
                  "name": "Capabilities",
                  "version": "5.0.0",
                  "type": "java",
                  "entrypoint": "dev.hmclce.test.Plugin",
                  "permissions": ["launcher-hook", "launcher-patch"],
                  "requiredPermissions": ["launcher-hook", "launcher-patch"],
                  "launcherVersion": "*",
                  "runtime": "java",
                  "abi": 2,
                  "hooks": ["before-download"],
                  "patches": [{
                    "target": "org.jackhuang.hmcl.Launcher",
                    "method": "launch",
                    "type": "before",
                    "parameters": ["java.lang.String"]
                  }]
                }
                """));

        List<PluginHookPoint> hooks = manifest.getHooks();
        List<PluginPatchDeclaration> patches = manifest.getPatches();
        assertEquals(List.of(PluginHookPoint.BEFORE_DOWNLOAD), hooks);
        assertEquals(List.of(new PluginPatchDeclaration(
                "org.jackhuang.hmcl.Launcher", "launch", PluginPatchDeclaration.PatchType.BEFORE,
                List.of("java.lang.String"))), patches);
        assertTrue(manifest.hasHooks());
        assertTrue(manifest.hasPatches());
        assertEquals(PluginCapabilityLevel.PATCH, manifest.getCapabilityLevel());
        assertThrows(UnsupportedOperationException.class, hooks::clear);
        assertThrows(UnsupportedOperationException.class, patches::clear);
    }

    /// Derives HOOK capability for a manifest without patches.
    @Test
    public void deriveHookOnlyManifestCapability() throws IOException {
        PluginManifest manifest = PluginManifest.fromJson(new StringReader(schemaFiveWithCapabilities(
                "[\"launcher-hook\"]", "[\"launcher-hook\"]",
                "\"hooks\": [\"before-login\"]")));

        assertEquals(PluginCapabilityLevel.HOOK, manifest.getCapabilityLevel());
        assertTrue(manifest.hasHooks());
        assertFalse(manifest.hasPatches());
    }

    /// Treats differently ordered patch parameter lists as distinct overload identities.
    @Test
    public void parseOrderedPatchOverloads() throws IOException {
        PluginManifest manifest = PluginManifest.fromJson(new StringReader(schemaFiveWithCapabilities(
                "[\"launcher-patch\"]", "[\"launcher-patch\"]", """
                "patches": [
                  {"target": "org.jackhuang.hmcl.Launcher", "method": "launch", "type": "before",
                   "parameters": []},
                  {"target": "org.jackhuang.hmcl.Launcher", "method": "launch", "type": "before",
                   "parameters": ["java.lang.String", "int"]},
                  {"target": "org.jackhuang.hmcl.Launcher", "method": "launch", "type": "before",
                   "parameters": ["int", "java.lang.String"]}
                ]
                """)));

        assertEquals(3, manifest.getPatches().size());
        assertEquals(List.of(), manifest.getPatches().get(0).getParameters());
        assertNotEquals(manifest.getPatches().get(1), manifest.getPatches().get(2));
    }

    /// Rejects null, unknown, and duplicate schema-v5 lifecycle hook declarations.
    @Test
    public void rejectInvalidManifestHooks() {
        assertManifestRejected(schemaFiveWithDeclarations(
                "\"runtime\": \"java\", \"abi\": 2, \"hooks\": null"));
        assertManifestRejected(schemaFiveWithDeclarations(
                "\"runtime\": \"java\", \"abi\": 2, \"hooks\": [\"unknown-hook\"]"));
        assertManifestRejected(schemaFiveWithDeclarations(
                "\"runtime\": \"java\", \"abi\": 2, "
                        + "\"hooks\": [\"before-download\", \"before-download\"]"));
    }

    /// Identifies an unknown lifecycle hook token in manifest diagnostics.
    @Test
    public void reportUnknownManifestHookToken() {
        IOException exception = assertThrows(IOException.class, () -> PluginManifest.fromJson(new StringReader(
                schemaFiveWithDeclarations(
                        "\"runtime\": \"java\", \"abi\": 2, \"hooks\": [\"around-launch\"]"))));

        assertTrue(exception.getMessage().contains("around-launch"));
    }

    /// Prioritizes unsupported schema diagnostics over raw schema-v5 capability token checks.
    @Test
    public void prioritizeSchemaVersionDiagnosticsBeforeCapabilityTokens() {
        IOException schemaFour = assertThrows(IOException.class, () -> PluginManifest.fromJson(new StringReader(
                schemaFourWithDeclarations("""
                        "permissions": [], "requiredPermissions": [], "launcherVersion": "*",
                        "hooks": ["around-launch"]
                        """))));
        IOException schemaSixHook = assertThrows(IOException.class, () -> PluginManifest.fromJson(new StringReader(
                schemaFiveWithDeclarations(
                        "\"runtime\": \"java\", \"abi\": 2, \"hooks\": [\"around-launch\"]")
                        .replace("\"schemaVersion\": 5", "\"schemaVersion\": 6"))));
        IOException schemaSixPatch = assertThrows(IOException.class, () -> PluginManifest.fromJson(new StringReader(
                schemaFiveWithDeclarations("""
                        "runtime": "java", "abi": 2,
                        "patches": [{"target": "org.example.GameLaunchService", "method": "launch",
                                     "type": "around", "parameters": []}]
                        """).replace("\"schemaVersion\": 5", "\"schemaVersion\": 6"))));

        assertAll(
                () -> assertTrue(schemaFour.getMessage().contains("schemaVersion")
                        && schemaFour.getMessage().contains("schema-v5")),
                () -> assertTrue(schemaSixHook.getMessage().contains("Unsupported plugin manifest schemaVersion: 6")),
                () -> assertTrue(schemaSixPatch.getMessage().contains("Unsupported plugin manifest schemaVersion: 6")));
    }

    /// Rejects null, malformed, and duplicate schema-v5 patch declarations.
    @Test
    public void rejectInvalidManifestPatches() {
        assertManifestRejected(schemaFiveWithDeclarations(
                "\"runtime\": \"java\", \"abi\": 2, \"patches\": null"));
        assertManifestRejected(schemaFiveWithDeclarations(
                "\"runtime\": \"java\", \"abi\": 2, \"patches\": [null]"));
        assertManifestRejected(schemaFiveWithDeclarations("""
                "runtime": "java", "abi": 2,
                "patches": [{"target": "org.jackhuang.hmcl.Launcher", "method": "launch",
                             "type": "before", "parameters": [" "]}]
                """));
        assertManifestRejected(schemaFiveWithDeclarations("""
                "runtime": "java", "abi": 2,
                "patches": [
                  {"target": "org.jackhuang.hmcl.Launcher", "method": "launch",
                   "type": "before", "parameters": ["java.lang.String"]},
                  {"target": "org.jackhuang.hmcl.Launcher", "method": "launch",
                   "type": "before", "parameters": ["java.lang.String"]}
                ]
                """));
    }

    /// Identifies an unknown patch type token in manifest diagnostics.
    @Test
    public void reportUnknownManifestPatchTypeToken() {
        IOException exception = assertThrows(IOException.class, () -> PluginManifest.fromJson(new StringReader(
                schemaFiveWithDeclarations("""
                        "runtime": "java", "abi": 2,
                        "patches": [{"target": "org.example.GameLaunchService", "method": "launch",
                                     "type": "around", "parameters": []}]
                        """))));

        assertTrue(exception.getMessage().contains("around"));
    }

    /// Reports malformed patch member values while retaining their validation causes.
    @Test
    public void reportMalformedManifestPatchMembers() {
        IOException targetException = assertThrows(IOException.class, () -> PluginManifest.fromJson(new StringReader(
                schemaFiveWithCapabilities("[\"launcher-patch\"]", "[\"launcher-patch\"]", """
                        "patches": [{"target": "GameLaunchService", "method": "launch",
                                     "type": "before", "parameters": []}]
                        """))));
        IOException methodException = assertThrows(IOException.class, () -> PluginManifest.fromJson(new StringReader(
                schemaFiveWithCapabilities("[\"launcher-patch\"]", "[\"launcher-patch\"]", """
                        "patches": [{"target": "org.example.GameLaunchService", "method": "bad-method",
                                     "type": "before", "parameters": []}]
                        """))));

        assertAll(
                () -> assertTrue(targetException.getMessage().contains("GameLaunchService")),
                () -> assertTrue(targetException.getCause() instanceof IllegalArgumentException),
                () -> assertTrue(methodException.getMessage().contains("bad-method")),
                () -> assertTrue(methodException.getCause() instanceof IllegalArgumentException));
    }

    /// Requires hook and patch permissions to be both declared and required.
    @Test
    public void requireCapabilityPermissions() {
        assertManifestRejected(schemaFiveWithCapabilities("[]", "[]",
                "\"hooks\": [\"before-download\"]"));
        assertManifestRejected(schemaFiveWithCapabilities("[\"launcher-hook\"]", "[]",
                "\"hooks\": [\"before-download\"]"));
        assertManifestRejected(schemaFiveWithCapabilities("[]", "[]", """
                "patches": [{"target": "org.jackhuang.hmcl.Launcher", "method": "launch",
                             "type": "before", "parameters": []}]
                """));
        assertManifestRejected(schemaFiveWithCapabilities("[\"launcher-patch\"]", "[]", """
                "patches": [{"target": "org.jackhuang.hmcl.Launcher", "method": "launch",
                             "type": "before", "parameters": []}]
                """));
    }

    /// Rejects every schema-v5 contract property and permission from earlier schemas.
    @Test
    public void rejectSchemaVersionFiveDeclarationsInEarlierSchemas() {
        assertManifestRejected(schemaFourWithDeclarations("""
                "permissions": [], "requiredPermissions": [], "launcherVersion": "*", "runtime": null
                """));
        assertManifestRejected(schemaFourWithDeclarations("""
                "permissions": [], "requiredPermissions": [], "launcherVersion": "*", "abi": 1
                """));
        assertManifestRejected(schemaFourWithDeclarations("""
                "permissions": [], "requiredPermissions": [], "launcherVersion": "*", "platforms": []
                """));
        assertManifestRejected(schemaFourWithDeclarations("""
                "permissions": [], "requiredPermissions": [], "launcherVersion": "*", "hooks": []
                """));
        assertManifestRejected(schemaFourWithDeclarations("""
                "permissions": [], "requiredPermissions": [], "launcherVersion": "*", "patches": []
                """));
        assertManifestRejected(schemaFourWithDeclarations("""
                "permissions": ["launcher-hook"], "requiredPermissions": ["launcher-hook"],
                "launcherVersion": "*"
                """));
        assertManifestRejected(schemaFourWithDeclarations("""
                "permissions": ["launcher-patch"], "requiredPermissions": ["launcher-patch"],
                "launcherVersion": "*"
                """));
    }

    /// Creates a valid schema-v5 Java ABI-2 API manifest programmatically.
    @Test
    public void constructValidSchemaVersionFiveManifest() {
        PluginManifest manifest = new PluginManifest(
                "dev.hmclce.test.programmatic",
                "Programmatic",
                "5.0.0",
                PluginManifest.PluginType.JAVA,
                "dev.hmclce.test.Plugin");

        assertEquals(5, manifest.getSchemaVersion());
        assertEquals(PluginRuntimeTypes.JAVA, manifest.getRuntime());
        assertEquals(PluginAbi.ABI_2, manifest.getAbi());
        assertEquals(List.of(), manifest.getPlatforms());
        assertEquals(List.of(), manifest.getHooks());
        assertEquals(List.of(), manifest.getPatches());
        assertEquals(PluginCapabilityLevel.API, manifest.getCapabilityLevel());
        assertDoesNotThrow(manifest::validate);
    }

    /// Includes every schema-v5 runtime capability field in manifest value identity.
    @Test
    public void compareSchemaVersionFiveManifestIdentity() throws IOException {
        String baseJson = """
                {
                  "schemaVersion": 5,
                  "id": "dev.hmclce.test.identity-five",
                  "name": "Identity Five",
                  "version": "5.0.0",
                  "type": "java",
                  "entrypoint": "dev.hmclce.test.Plugin",
                  "permissions": ["launcher-hook", "launcher-patch"],
                  "requiredPermissions": ["launcher-hook", "launcher-patch"],
                  "launcherVersion": "*",
                  "runtime": "java",
                  "abi": 2,
                  "platforms": ["windows-x64"],
                  "hooks": ["before-download"],
                  "patches": [{"target": "org.jackhuang.hmcl.Launcher", "method": "launch",
                               "type": "before", "parameters": ["java.lang.String"]}]
                }
                """;
        PluginManifest base = PluginManifest.fromJson(new StringReader(baseJson));
        PluginManifest identical = PluginManifest.fromJson(new StringReader(baseJson));

        assertEquals(base, identical);
        assertEquals(base.hashCode(), identical.hashCode());
        assertNotEquals(base, PluginManifest.fromJson(new StringReader(
                baseJson.replace("\"runtime\": \"java\"", "\"runtime\": \"dotnet\""))));
        assertNotEquals(base, PluginManifest.fromJson(new StringReader(
                baseJson.replace("\"abi\": 2", "\"abi\": 1"))));
        assertNotEquals(base, PluginManifest.fromJson(new StringReader(
                baseJson.replace("\"windows-x64\"", "\"linux-arm64\""))));
        assertNotEquals(base, PluginManifest.fromJson(new StringReader(
                baseJson.replace("\"before-download\"", "\"after-download\""))));
        assertNotEquals(base, PluginManifest.fromJson(new StringReader(
                baseJson.replace("\"method\": \"launch\"", "\"method\": \"start\""))));
    }

    /// Rejects detached C# package manifests while HMCL CE ships only JVM plugin runtimes.
    @Test
    public void rejectDetachedCsharpCompanionManifest() {
        assertManifestRejected(new StringReader("""
                {
                  "schemaVersion": 4,
                  "id": "dev.hmclce.test.csharp-companion",
                  "name": "C# Companion",
                  "version": "1.0.0",
                  "type": "csharp",
                  "entrypoint": "companion/extension.json",
                  "permissions": [],
                  "requiredPermissions": [],
                  "launcherVersion": "*",
                  "dependencies": []
                }
                """));
    }

    /// Parses an optional package-relative plugin icon declaration.
    @Test
    public void parsePluginIconResource() throws IOException {
        PluginManifest manifest = PluginManifest.fromJson(new StringReader("""
                {
                  "schemaVersion": 4,
                  "id": "dev.hmclce.test.icon",
                  "name": "Icon Test",
                  "version": "1.0.0",
                  "type": "java",
                  "entrypoint": "dev.hmclce.test.Plugin",
                  "permissions": [],
                  "requiredPermissions": [],
                  "launcherVersion": "*",
                  "icon": "assets/plugin-icon.svg"
                }
                """));

        assertEquals("assets/plugin-icon.svg", manifest.getIcon());
    }

    /// Rejects paths that could escape a local plugin archive entry.
    @Test
    public void rejectUnsafePluginIconResources() {
        assertManifestRejected(schemaFourWithDeclarations("""
                "permissions": [],
                "requiredPermissions": [],
                "launcherVersion": "*",
                "icon": "../plugin-icon.png"
                """));
        assertManifestRejected(schemaFourWithDeclarations("""
                "permissions": [],
                "requiredPermissions": [],
                "launcherVersion": "*",
                "icon": "/plugin-icon.png"
                """));
        assertManifestRejected(schemaFourWithDeclarations("""
                "permissions": [],
                "requiredPermissions": [],
                "launcherVersion": "*",
                "icon": "assets\\\\plugin-icon.png"
                """));
    }

    /// Preserves schema-v3 atomic Mixin semantics by treating every declared permission as required.
    @Test
    public void deriveSchemaVersionThreeMixinRequiredPermissions() throws IOException {
        PluginManifest manifest = PluginManifest.fromJson(new StringReader("""
                {
                  "schemaVersion": 3,
                  "id": "dev.hmclce.test.schema-three-mixin",
                  "name": "Schema Three Mixin",
                  "version": "1.0.0",
                  "type": "java",
                  "entrypoint": "dev.hmclce.test.Plugin",
                  "permissions": ["mixin", "launcher-ui"],
                  "mixins": ["mixins.dev.hmclce.test.schema-three.json"]
                }
                """));

        assertEquals(manifest.getPermissions(), manifest.getRequiredPermissions());
        assertTrue(manifest.getOptionalPermissions().isEmpty());
    }

    /// Requires schema-v3 manifests to contain an explicit permission list, including when it is empty.
    @Test
    public void requireSchemaVersionThreePermissions() throws IOException {
        PluginManifest valid = PluginManifest.fromJson(new StringReader("""
                {
                  "schemaVersion": 3,
                  "id": "dev.hmclce.test.empty-permissions",
                  "name": "Empty Permissions",
                  "version": "1.0.0",
                  "type": "java",
                  "entrypoint": "dev.hmclce.test.Plugin",
                  "permissions": []
                }
                """));
        assertTrue(valid.getPermissions().isEmpty());

        assertThrows(IOException.class, () -> PluginManifest.fromJson(new StringReader("""
                {
                  "schemaVersion": 3,
                  "id": "dev.hmclce.test.missing-permissions",
                  "name": "Missing Permissions",
                  "version": "1.0.0",
                  "type": "java",
                  "entrypoint": "dev.hmclce.test.Plugin"
                }
                """)));
    }

    /// Rejects permission declarations in legacy schemas and null, duplicate, or unknown schema-v3 values.
    @Test
    public void rejectInvalidPermissions() {
        assertManifestRejected("""
                {
                  "schemaVersion": 2,
                  "id": "dev.hmclce.test.legacy-permissions",
                  "name": "Legacy Permissions",
                  "version": "1.0.0",
                  "type": "java",
                  "entrypoint": "dev.hmclce.test.Plugin",
                  "permissions": []
                }
                """);
        assertManifestRejected(schemaThreeWithPermissions("null"));
        assertManifestRejected(schemaThreeWithPermissions("[\"network\", \"network\"]"));
        assertManifestRejected(schemaThreeWithPermissions("[\"unknown-permission\"]"));
    }

    /// Rejects missing, duplicate, unknown, undeclared, or schema-incompatible required permission declarations.
    @Test
    public void rejectInvalidRequiredPermissions() {
        assertManifestRejected(schemaFourWithDeclarations("""
                "permissions": ["filesystem"],
                "launcherVersion": "*"
                """));
        assertManifestRejected(schemaFourWithDeclarations("""
                "permissions": ["filesystem"],
                "requiredPermissions": ["filesystem", "filesystem"],
                "launcherVersion": "*"
                """));
        assertManifestRejected(schemaFourWithDeclarations("""
                "permissions": ["filesystem"],
                "requiredPermissions": ["unknown-permission"],
                "launcherVersion": "*"
                """));
        assertManifestRejected(schemaFourWithDeclarations("""
                "permissions": ["filesystem"],
                "requiredPermissions": ["network"],
                "launcherVersion": "*"
                """));
        assertManifestRejected(schemaThreeWithPermissionsAndExtra(
                "[\"filesystem\"]",
                ",\n  \"requiredPermissions\": [\"filesystem\"]"
        ));
    }

    /// Requires schema-v4 Mixin capability declarations to classify `mixin` itself as required.
    @Test
    public void requireMixinPermissionClassification() {
        assertManifestRejected(schemaFourWithDeclarations("""
                "permissions": ["mixin", "network"],
                "requiredPermissions": ["network"],
                "launcherVersion": "*"
                """));
        assertManifestRejected(schemaThreeWithPermissionsAndExtra(
                "[\"network\"]",
                ",\n  \"mixins\": [\"mixins.dev.hmclce.test.invalid.json\"]"
        ));
    }

    /// Rejects missing, malformed, or schema-incompatible launcher version declarations.
    @Test
    public void rejectInvalidLauncherVersions() {
        assertManifestRejected(schemaFourWithDeclarations("""
                "permissions": [],
                "requiredPermissions": []
                """));
        assertManifestRejected(schemaFourWithDeclarations("""
                "permissions": [],
                "requiredPermissions": [],
                "launcherVersion": ">=26.8 || <27"
                """));
        assertManifestRejected(schemaFourWithDeclarations("""
                "permissions": [],
                "requiredPermissions": [],
                "launcherVersion": "*",
                "minLauncherVersion": "26.8"
                """));
        assertManifestRejected(schemaThreeWithPermissionsAndExtra(
                "[]",
                ",\n  \"launcherVersion\": \"*\""
        ));
        assertManifestRejected(schemaThreeWithPermissionsAndExtra(
                "[]",
                ",\n  \"minLauncherVersion\": \"bad version\""
        ));
    }

    /// Rejects self dependencies, duplicate IDs across representations, and malformed dependency constraints.
    @Test
    public void rejectInvalidDependencies() {
        assertManifestRejected(schemaThreeWithDependencies("[\"dev.hmclce.test.invalid-dependency\"]"));
        assertManifestRejected(schemaThreeWithDependencies("""
                ["dev.hmclce.test.base", {"id": "dev.hmclce.test.base", "version": ">=1.0"}]
                """));
        assertThrows(RuntimeException.class, () -> PluginManifest.fromJson(new StringReader(
                schemaThreeWithDependencies("[{\"id\": \"dev.hmclce.test.base\", \"version\": \">=1.0 || <2.0\"}]")
        )));
    }

    /// Rejects JavaScript packages because CE supports JVM plugin runtimes only.
    @Test
    public void rejectUnsupportedJavaScriptPlugins() {
        assertThrows(IOException.class, () -> PluginManifest.fromJson(new StringReader("""
                {
                  "schemaVersion": 2,
                  "id": "dev.hmclce.test.javascript",
                  "name": "JavaScript Test",
                  "version": "1.0.0",
                  "type": "javascript",
                  "entrypoint": "main.js"
                }
                """)));
    }

    /// Compares every executable contract field while ignoring JSON formatting differences.
    @Test
    public void compareExecutableManifestContract() throws IOException {
        String base = """
                {
                  "schemaVersion": 3,
                  "id": "dev.hmclce.test.identity",
                  "name": "Identity",
                  "version": "1.0.0",
                  "description": "Exact artifact contract",
                  "author": "HMCL CE",
                  "type": "java",
                  "entrypoint": "dev.hmclce.test.Plugin",
                  "permissions": ["filesystem", "launcher-ui", "mixin"],
                  "dependencies": [{"id": "dev.hmclce.test.base", "version": ">=1.0.0"}],
                  "minLauncherVersion": "3.0",
                  "mixins": ["mixins.dev.hmclce.test.json"]
                }
                """;
        PluginManifest first = PluginManifest.fromJson(new StringReader(base));
        PluginManifest identical = PluginManifest.fromJson(new StringReader(base.replace("  ", "    ")));
        PluginManifest changedPermission = PluginManifest.fromJson(new StringReader(
                base.replace(
                        "[\"filesystem\", \"launcher-ui\", \"mixin\"]",
                        "[\"filesystem\", \"network\", \"mixin\"]"
                )
        ));

        assertEquals(first, identical);
        assertEquals(first.hashCode(), identical.hashCode());
        assertNotEquals(first, changedPermission);
    }

    /// Builds a valid schema-v3 manifest with a caller-provided permission JSON value.
    ///
    /// @param permissionsJson raw permission JSON value
    /// @return complete manifest JSON
    private static String schemaThreeWithPermissions(String permissionsJson) {
        return schemaThreeWithPermissionsAndExtra(permissionsJson, "");
    }

    /// Builds a schema-v3 manifest with caller-provided permission JSON and additional root properties.
    ///
    /// @param permissionsJson raw permission JSON value
    /// @param extraJson additional comma-prefixed root properties
    /// @return complete manifest JSON
    private static String schemaThreeWithPermissionsAndExtra(String permissionsJson, String extraJson) {
        return """
                {
                  "schemaVersion": 3,
                  "id": "dev.hmclce.test.invalid-permissions",
                  "name": "Invalid Permissions",
                  "version": "1.0.0",
                  "type": "java",
                  "entrypoint": "dev.hmclce.test.Plugin",
                  "permissions": %s%s
                }
                """.formatted(permissionsJson, extraJson);
    }

    /// Builds a schema-v4 manifest with caller-provided security and launcher declarations.
    ///
    /// @param declarationsJson root declarations appended after the entry point
    /// @return complete manifest JSON
    private static String schemaFourWithDeclarations(String declarationsJson) {
        return """
                {
                  "schemaVersion": 4,
                  "id": "dev.hmclce.test.invalid-schema-four",
                  "name": "Invalid Schema Four",
                  "version": "1.0.0",
                  "type": "java",
                  "entrypoint": "dev.hmclce.test.Plugin",
                  %s
                }
                """.formatted(declarationsJson);
    }

    /// Builds a schema-v5 manifest with caller-provided runtime contract declarations.
    ///
    /// @param declarationsJson root declarations appended after the launcher version
    /// @return complete manifest JSON
    private static String schemaFiveWithDeclarations(String declarationsJson) {
        return """
                {
                  "schemaVersion": 5,
                  "id": "dev.hmclce.test.invalid-schema-five",
                  "name": "Invalid Schema Five",
                  "version": "5.0.0",
                  "type": "java",
                  "entrypoint": "dev.hmclce.test.Plugin",
                  "permissions": [],
                  "requiredPermissions": [],
                  "launcherVersion": "*",
                  %s
                }
                """.formatted(declarationsJson);
    }

    /// Builds a schema-v5 manifest with caller-provided permissions and capability declarations.
    ///
    /// @param permissionsJson declared permission array
    /// @param requiredPermissionsJson required permission array
    /// @param capabilitiesJson hook or patch declarations
    /// @return complete manifest JSON
    private static String schemaFiveWithCapabilities(
            String permissionsJson,
            String requiredPermissionsJson,
            String capabilitiesJson) {
        return """
                {
                  "schemaVersion": 5,
                  "id": "dev.hmclce.test.capability-permissions",
                  "name": "Capability Permissions",
                  "version": "5.0.0",
                  "type": "java",
                  "entrypoint": "dev.hmclce.test.Plugin",
                  "permissions": %s,
                  "requiredPermissions": %s,
                  "launcherVersion": "*",
                  "runtime": "java",
                  "abi": 2,
                  %s
                }
                """.formatted(permissionsJson, requiredPermissionsJson, capabilitiesJson);
    }

    /// Builds a valid schema-v3 manifest with a caller-provided dependency JSON array.
    ///
    /// @param dependenciesJson raw dependency JSON array
    /// @return complete manifest JSON
    private static String schemaThreeWithDependencies(String dependenciesJson) {
        return """
                {
                  "schemaVersion": 3,
                  "id": "dev.hmclce.test.invalid-dependency",
                  "name": "Invalid Dependency",
                  "version": "1.0.0",
                  "type": "java",
                  "entrypoint": "dev.hmclce.test.Plugin",
                  "permissions": [],
                  "dependencies": %s
                }
                """.formatted(dependenciesJson);
    }

    /// Asserts that semantic manifest validation rejects the supplied JSON.
    ///
    /// @param json manifest JSON
    private static void assertManifestRejected(String json) {
        assertManifestRejected(new StringReader(json));
    }

    /// Asserts that semantic manifest validation rejects the supplied JSON reader.
    ///
    /// @param reader manifest JSON reader
    private static void assertManifestRejected(StringReader reader) {
        assertThrows(IOException.class, () -> PluginManifest.fromJson(reader));
    }

    /// Asserts that malformed JSON is rejected either while parsing or during semantic manifest validation.
    ///
    /// @param json manifest JSON
    private static void assertManifestRejectedAtParsingOrValidation(String json) {
        assertThrows(Exception.class, () -> PluginManifest.fromJson(new StringReader(json)));
    }

    /// Asserts that a malformed patch declaration fails semantic validation.
    ///
    /// @param json patch declaration JSON
    private static void assertPatchRejected(String json) {
        PluginPatchDeclaration declaration = JsonUtils.GSON.fromJson(json, PluginPatchDeclaration.class);
        assertThrows(IllegalArgumentException.class, declaration::validate);
    }
}
