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
import org.jackhuang.hmcl.plugin.trust.PluginCertificationReceipt;
import org.jackhuang.hmcl.plugin.trust.PluginCertificationReceiptStore;
import org.jackhuang.hmcl.plugin.trust.PluginRuntimeTrustTestSupport;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies atomic certification-receipt publication across installation, replacement, removal, and rollback.
@NotNullByDefault
public final class PluginManagerCertificationReceiptTest {
    /// Persists an exact proof-backed receipt in the same transaction as its certified package.
    ///
    /// @param temporaryDirectory isolated launcher home and source directory
    /// @throws Exception if package creation, proof signing, inspection, or publication fails
    @Test
    public void certifiedInstallationPublishesExactReceipt(@TempDir Path temporaryDirectory) throws Exception {
        Path localHome = temporaryDirectory.resolve("home");
        PluginManager manager = new PluginManager(localHome);
        String pluginId = "dev.hmclce.test.certified-install";
        Path source = temporaryDirectory.resolve("certified.npl");
        writePluginPackage(source, pluginId, "1.0.0", "certified");

        PluginCertificationReceipt expected = stageCertified(manager, source, pluginId, "1.0.0");

        Map<String, PluginCertificationReceipt> receipts =
                new PluginCertificationReceiptStore(localHome).readAll();
        assertEquals(Map.of(pluginId, expected), receipts);
        assertTrue(Files.isRegularFile(manager.getPluginsDirectory().resolve(pluginId + ".npl")));
    }

    /// Removes an old certified identity when community or local bytes replace the package without a new proof.
    ///
    /// @param temporaryDirectory isolated launcher home and source directory
    /// @throws Exception if package creation, proof signing, inspection, or publication fails
    @Test
    public void unsignedReplacementClearsOldReceipt(@TempDir Path temporaryDirectory) throws Exception {
        Path localHome = temporaryDirectory.resolve("home");
        PluginManager manager = new PluginManager(localHome);
        String pluginId = "dev.hmclce.test.community-replacement";
        Path first = temporaryDirectory.resolve("first.npl");
        writePluginPackage(first, pluginId, "1.0.0", "certified");
        stageCertified(manager, first, pluginId, "1.0.0");
        Path installed = manager.getPluginsDirectory().resolve(pluginId + ".npl");
        PluginArtifactIdentity prior = new PluginArtifactIdentity(
                pluginId,
                "1.0.0",
                PluginPackageVersions.calculateSha256(installed)
        );
        Path replacement = temporaryDirectory.resolve("replacement.npl");
        writePluginPackage(replacement, pluginId, "2.0.0", "community");
        LocalPluginInspection inspection = manager.inspectLocalPluginPackage(replacement);

        manager.stagePluginInstallations(
                List.of(inspection),
                Map.of(pluginId, Set.of()),
                Map.of(),
                Map.of(pluginId, Optional.of(prior)),
                Map.of()
        );

        assertTrue(new PluginCertificationReceiptStore(localHome).readAll().isEmpty());
        assertEquals("2.0.0", Objects.requireNonNull(manager.getInstalledManifests().get(pluginId)).getVersion());
    }

    /// Removes a certified receipt atomically when an unloaded plugin is uninstalled.
    ///
    /// @param temporaryDirectory isolated launcher home and source directory
    /// @throws Exception if package creation, proof signing, installation, or removal fails
    @Test
    public void uninstallClearsCertifiedReceipt(@TempDir Path temporaryDirectory) throws Exception {
        Path localHome = temporaryDirectory.resolve("home");
        PluginManager manager = new PluginManager(localHome);
        String pluginId = "dev.hmclce.test.certified-uninstall";
        Path source = temporaryDirectory.resolve("uninstall.npl");
        writePluginPackage(source, pluginId, "1.0.0", "certified");
        stageCertified(manager, source, pluginId, "1.0.0");

        manager.uninstallPlugin(pluginId);

        assertTrue(new PluginCertificationReceiptStore(localHome).readAll().isEmpty());
        assertFalse(Files.exists(manager.getPluginsDirectory().resolve(pluginId + ".npl")));
    }

    /// Restores every document and package when receipt replacement fails after permission publication begins.
    ///
    /// @param temporaryDirectory isolated launcher home and source directory
    /// @throws Exception if package creation, proof signing, inspection, or rollback verification fails
    @Test
    public void receiptWriteFailureRollsBackWholeInstallation(@TempDir Path temporaryDirectory) throws Exception {
        Path localHome = temporaryDirectory.resolve("home");
        PluginManager manager = new PluginManager(localHome);
        String pluginId = "dev.hmclce.test.receipt-rollback";
        Path source = temporaryDirectory.resolve("rollback.npl");
        writePluginPackage(source, pluginId, "1.0.0", "rollback");
        LocalPluginInspection inspection = manager.inspectLocalPluginPackage(source);
        PluginCertificationReceipt receipt = PluginRuntimeTrustTestSupport.certificationReceipt(
                pluginId,
                "1.0.0",
                inspection.getSha256(),
                Files.size(source)
        );
        Path receiptFile = localHome.resolve(PluginCertificationReceiptStore.FILE_NAME);
        byte @Unmodifiable [] originalReceiptBytes =
                "malformed-existing-receipt".getBytes(StandardCharsets.UTF_8);
        Files.write(receiptFile, originalReceiptBytes);

        assertThrows(IOException.class, () -> manager.stagePluginInstallations(
                List.of(inspection),
                Map.of(pluginId, Set.of()),
                Map.of(),
                Map.of(pluginId, Optional.empty()),
                Map.of(pluginId, receipt)
        ));

        assertFalse(Files.exists(manager.getPluginsDirectory().resolve(pluginId + ".npl")));
        assertFalse(Files.exists(localHome.resolve("plugin-permissions.json")));
        assertFalse(Files.exists(localHome.resolve("plugin-states.json")));
        assertArrayEquals(originalReceiptBytes, Files.readAllBytes(receiptFile));
        assertFalse(Files.exists(localHome.resolve("plugin-install-transaction.json")));
    }

    /// Installs one exact package with a freshly signed certification receipt.
    ///
    /// @param manager isolated plugin manager
    /// @param source inspected source NPL
    /// @param pluginId exact plugin ID
    /// @param version exact package version
    /// @return published proof-backed receipt
    /// @throws Exception if inspection, signing, or publication fails
    private static PluginCertificationReceipt stageCertified(
            PluginManager manager,
            Path source,
            String pluginId,
            String version
    ) throws Exception {
        LocalPluginInspection inspection = manager.inspectLocalPluginPackage(source);
        PluginCertificationReceipt receipt = PluginRuntimeTrustTestSupport.certificationReceipt(
                pluginId,
                version,
                inspection.getSha256(),
                Files.size(source)
        );
        manager.stagePluginInstallations(
                List.of(inspection),
                Map.of(pluginId, Set.of()),
                Map.of(),
                Map.of(pluginId, Optional.empty()),
                Map.of(pluginId, receipt)
        );
        return receipt;
    }

    /// Writes one minimal API-v4 Java package with package-owned entry-point bytes.
    ///
    /// @param target target NPL path
    /// @param pluginId exact plugin ID
    /// @param version package version
    /// @param marker payload marker that makes replacement bytes distinct
    /// @throws IOException if the archive or class entry cannot be written
    private static void writePluginPackage(
            Path target,
            String pluginId,
            String version,
            String marker
    ) throws IOException {
        Files.createDirectories(Objects.requireNonNull(target.getParent()));
        String manifest = """
                {
                  "schemaVersion": 4,
                  "id": "%s",
                  "name": "Certification Receipt Test",
                  "version": "%s",
                  "type": "java",
                  "entrypoint": "%s",
                  "permissions": [],
                  "requiredPermissions": [],
                  "launcherVersion": "*",
                  "dependencies": []
                }
                """.formatted(pluginId, version, PackagedTestPlugin.class.getName());
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(target))) {
            writeEntry(output, "plugin.json", manifest.getBytes(StandardCharsets.UTF_8));
            writeEntry(output, "marker.txt", marker.getBytes(StandardCharsets.UTF_8));
            String classResource = PackagedTestPlugin.class.getName().replace('.', '/') + ".class";
            try (@Nullable var input = PackagedTestPlugin.class.getClassLoader().getResourceAsStream(classResource)) {
                if (input == null) {
                    throw new IOException("Compiled test plugin class is unavailable: " + classResource);
                }
                ZipEntry classEntry = new ZipEntry(classResource);
                classEntry.setTime(0);
                output.putNextEntry(classEntry);
                input.transferTo(output);
                output.closeEntry();
            }
        }
    }

    /// Writes one deterministic archive entry.
    ///
    /// @param output target archive
    /// @param name archive entry name
    /// @param contents entry contents
    /// @throws IOException if the entry cannot be written
    private static void writeEntry(
            ZipOutputStream output,
            String name,
            byte @Unmodifiable [] contents
    ) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(0);
        output.putNextEntry(entry);
        output.write(contents);
        output.closeEntry();
    }
}
