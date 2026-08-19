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

import javafx.scene.image.Image;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/// Reads installed plugin archives without loading or extracting lifecycle code.
@NotNullByDefault
final class PluginPackageRepository {
    /// Root manifest entry inside every plugin package.
    private static final String PLUGIN_MANIFEST = "plugin.json";

    /// Maximum uncompressed size accepted for `plugin.json`.
    private static final int MAX_MANIFEST_BYTES = 1024 * 1024;

    /// Maximum uncompressed size accepted for one optional plugin icon.
    private static final int MAX_ICON_BYTES = 2 * 1024 * 1024;

    /// Directory containing installed `.npl` files.
    private final Path pluginsDirectory;

    /// Creates a repository rooted at one launcher's installed plugin directory.
    ///
    /// @param pluginsDirectory installed package directory
    PluginPackageRepository(Path pluginsDirectory) {
        this.pluginsDirectory = pluginsDirectory;
    }

    /// Reads and validates a bounded manifest directly from a plugin package.
    ///
    /// @param nplFile plugin package path
    /// @return validated manifest
    /// @throws IOException if the package or manifest is invalid
    PluginManifest readManifest(Path nplFile) throws IOException {
        try (ZipFile zipFile = new ZipFile(nplFile.toFile())) {
            @Nullable ZipEntry manifestEntry = zipFile.getEntry(PLUGIN_MANIFEST);
            if (manifestEntry == null || manifestEntry.isDirectory()) {
                throw new IOException(PLUGIN_MANIFEST + " not found in " + nplFile.getFileName());
            }
            if (manifestEntry.getSize() > MAX_MANIFEST_BYTES) {
                throw new IOException("Plugin manifest is too large: " + nplFile.getFileName());
            }
            byte @Unmodifiable [] bytes;
            try (InputStream input = zipFile.getInputStream(manifestEntry)) {
                bytes = input.readNBytes(MAX_MANIFEST_BYTES + 1);
            }
            if (bytes.length > MAX_MANIFEST_BYTES) {
                throw new IOException("Plugin manifest is too large: " + nplFile.getFileName());
            }
            return PluginManifest.fromJson(new java.io.StringReader(new String(bytes, StandardCharsets.UTF_8)));
        }
    }

    /// Loads a declared icon directly from one installed local package.
    ///
    /// The caller owns fallback behavior: absent declarations return `null`, while malformed or oversized archive
    /// entries are reported as IO failures without affecting manifest discovery.
    ///
    /// @param nplFile installed plugin package path
    /// @param manifest already validated package manifest
    /// @return decoded icon, or `null` when no icon was declared
    /// @throws IOException if the declared resource cannot be read or decoded
    @Nullable Image readIcon(Path nplFile, PluginManifest manifest) throws IOException {
        @Nullable String iconPath = manifest.getIcon();
        if (iconPath == null) {
            return null;
        }
        try (ZipFile zipFile = new ZipFile(nplFile.toFile())) {
            @Nullable ZipEntry iconEntry = zipFile.getEntry(iconPath);
            if (iconEntry == null || iconEntry.isDirectory()) {
                throw new IOException("Plugin icon not found in " + nplFile.getFileName() + ": " + iconPath);
            }
            if (iconEntry.getSize() > MAX_ICON_BYTES) {
                throw new IOException("Plugin icon is too large: " + nplFile.getFileName());
            }
            byte @Unmodifiable [] bytes;
            try (InputStream input = zipFile.getInputStream(iconEntry)) {
                bytes = input.readNBytes(MAX_ICON_BYTES + 1);
            }
            if (bytes.length > MAX_ICON_BYTES) {
                throw new IOException("Plugin icon is too large: " + nplFile.getFileName());
            }
            try {
                return FXUtils.loadImage(
                        new java.io.ByteArrayInputStream(bytes),
                        iconPath,
                        42,
                        42,
                        true,
                        true
                );
            } catch (Exception exception) {
                throw new IOException("Plugin icon is invalid: " + nplFile.getFileName(), exception);
            }
        }
    }

    /// Finds readable installed packages declaring one exact plugin ID.
    ///
    /// @param pluginId validated plugin ID
    /// @return immutable sorted matching package paths
    /// @throws IOException if the installed package directory cannot be listed
    @Unmodifiable List<Path> findInstalledPackages(String pluginId) throws IOException {
        List<Path> matches = new ArrayList<>();
        for (Path packageFile : listPackageFiles()) {
            try {
                if (pluginId.equals(readManifest(packageFile).getId())) {
                    matches.add(packageFile);
                }
            } catch (IOException | RuntimeException ignored) {
                // Discovery reports invalid archives with their exact paths.
            }
        }
        return List.copyOf(matches);
    }

    /// Reads every installed manifest and overlays loaded lifecycle manifests only when their package is absent.
    ///
    /// A package that is present but unreadable fails the complete snapshot. Callers rely on this distinction to
    /// report damaged installations instead of presenting an empty or partially installed plugin set.
    ///
    /// @param loadedContainers currently loaded lifecycle containers
    /// @return immutable installed manifests indexed by plugin ID
    /// @throws IOException if the installed package directory or any installed package cannot be read
    @Unmodifiable Map<String, PluginManifest> readInstalledManifests(
            Collection<PluginContainer> loadedContainers
    ) throws IOException {
        Map<String, PluginManifest> manifests = new LinkedHashMap<>();
        for (Path packageFile : listPackageFiles()) {
            try {
                PluginManifest manifest = readManifest(packageFile);
                manifests.putIfAbsent(manifest.getId(), manifest);
            } catch (IOException | RuntimeException exception) {
                throw new IOException(
                        "Failed to read installed plugin package " + packageFile.getFileName(),
                        exception
                );
            }
        }
        for (PluginContainer container : loadedContainers) {
            manifests.putIfAbsent(container.getManifest().getId(), container.getManifest());
        }
        return Map.copyOf(manifests);
    }

    /// Rejects missing, unreadable, non-regular, or incorrectly named local packages.
    ///
    /// @param packageFile candidate local package
    /// @throws IOException if the package cannot be safely consumed
    static void validateLocalPackage(Path packageFile) throws IOException {
        @Nullable Path fileName = packageFile.getFileName();
        if (fileName == null
                || !fileName.toString().toLowerCase(Locale.ROOT).endsWith(".npl")
                || !Files.isRegularFile(packageFile, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(packageFile)
                || !Files.isReadable(packageFile)) {
            throw new IOException("Plugin package is not a readable .npl file: " + packageFile);
        }
    }

    /// Lists readable regular `.npl` files in deterministic order.
    ///
    /// @return immutable normalized package paths
    /// @throws IOException if directory enumeration fails
    private @Unmodifiable List<Path> listPackageFiles() throws IOException {
        try (Stream<Path> files = Files.list(pluginsDirectory)) {
            return files
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> !Files.isSymbolicLink(path))
                    .filter(Files::isReadable)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".npl"))
                    .map(path -> path.toAbsolutePath().normalize())
                    .sorted()
                    .toList();
        }
    }
}
