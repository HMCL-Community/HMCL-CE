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
import org.jackhuang.hmcl.plugin.internal.VerifiedPluginPackage;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies that discovery metadata cannot authorize different bytes selected later for execution.
@NotNullByDefault
public final class PluginPackageCandidateTest {
    /// Rejects a package whose immutable snapshot contains a different permission contract.
    ///
    /// @param temporaryDirectory isolated package and cache root
    /// @throws Exception if the fixture cannot be created or verified
    @Test
    public void rejectManifestFromDifferentRead(@TempDir Path temporaryDirectory) throws Exception {
        String pluginId = "dev.hmclce.test.manifest-swap";
        Path nplFile = temporaryDirectory.resolve("manifest-swap.npl");
        String actualJson = manifestJson(pluginId, "[\"filesystem\"]");
        writePackage(nplFile, actualJson);
        String sha256 = PluginPackageVersions.calculateSha256(nplFile);
        PluginManifest discoveredManifest = PluginManifest.fromJson(new StringReader(
                manifestJson(pluginId, "[\"network\"]")
        ));
        PluginArtifactIdentity identity = PluginArtifactIdentity.of(discoveredManifest, sha256);
        VerifiedPluginPackage pluginPackage = PluginPackageVersions.prepareVerifiedLifecyclePackage(
                nplFile,
                temporaryDirectory.resolve("cache"),
                identity
        );
        PluginPackageCandidate candidate = new PluginPackageCandidate(nplFile, discoveredManifest, identity);

        assertThrows(IOException.class, () -> candidate.verifySnapshotManifest(pluginPackage));
    }

    /// Builds a complete schema-v3 manifest with a caller-provided permission array.
    ///
    /// @param pluginId fixture plugin ID
    /// @param permissionsJson permission array JSON
    /// @return manifest JSON
    private static String manifestJson(String pluginId, String permissionsJson) {
        return """
                {
                  "schemaVersion": 3,
                  "id": "%s",
                  "name": "Manifest Swap",
                  "version": "1.0.0",
                  "type": "java",
                  "entrypoint": "dev.hmclce.test.ManifestSwap",
                  "permissions": %s,
                  "dependencies": []
                }
                """.formatted(pluginId, permissionsJson);
    }

    /// Writes one minimal JVM plugin package containing the supplied manifest and entry point resource.
    ///
    /// @param target target NPL path
    /// @param manifestJson root manifest JSON
    /// @throws IOException if the archive cannot be written
    private static void writePackage(Path target, String manifestJson) throws IOException {
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(target))) {
            output.putNextEntry(new ZipEntry("plugin.json"));
            output.write(manifestJson.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
            output.putNextEntry(new ZipEntry("dev/hmclce/test/ManifestSwap.class"));
            output.write(new byte[]{0});
            output.closeEntry();
        }
    }
}
