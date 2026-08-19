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

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// Verifies that package discovery never follows caller-controlled filesystem links.
@NotNullByDefault
public final class PluginPackageRepositoryTest {
    /// Fails the complete installed-manifest snapshot when an archive exists but cannot be read.
    ///
    /// @param temporaryDirectory isolated plugin directory
    /// @throws IOException if the damaged package fixture cannot be created
    @Test
    public void damagedInstalledPackageFailsSnapshot(@TempDir Path temporaryDirectory) throws IOException {
        Path packageFile = temporaryDirectory.resolve("damaged.npl");
        Files.writeString(packageFile, "not a zip archive");
        PluginPackageRepository repository = new PluginPackageRepository(temporaryDirectory);

        assertThrows(IOException.class, () -> repository.readInstalledManifests(List.of()));
    }

    /// Rejects a symbolic `.npl` source before manifest or archive bytes are consumed.
    ///
    /// @param temporaryDirectory isolated source directory
    /// @throws IOException if the regular target fixture cannot be created
    @Test
    public void rejectSymbolicPackage(@TempDir Path temporaryDirectory) throws IOException {
        Path target = temporaryDirectory.resolve("target.npl");
        Files.write(target, new byte[] {0});
        Path link = temporaryDirectory.resolve("linked.npl");
        try {
            Files.createSymbolicLink(link, target.getFileName());
        } catch (IOException | UnsupportedOperationException exception) {
            assumeTrue(false, "Symbolic links are unavailable: " + exception.getMessage());
        }

        assertThrows(IOException.class, () -> PluginPackageRepository.validateLocalPackage(link));
    }
}
