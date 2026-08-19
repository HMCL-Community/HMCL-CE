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

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringReader;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies legacy, schema-v3, and schema-v4 plugin manifest parsing and validation.
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

        assertEquals(PluginManifest.CURRENT_SCHEMA_VERSION, manifest.getSchemaVersion());
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
}
