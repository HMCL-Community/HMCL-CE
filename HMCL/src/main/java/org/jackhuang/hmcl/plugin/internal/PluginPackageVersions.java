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
package org.jackhuang.hmcl.plugin.internal;

import org.jackhuang.hmcl.plugin.PluginArtifactIdentity;
import org.jackhuang.hmcl.plugin.PluginManifest;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/// Publishes extracted plugin packages into immutable content-addressed directories.
///
/// Complete versions are never overwritten or removed during normal loading, so another HMCL
/// process may continue reading an older version without blocking a new package from starting.
@NotNullByDefault
public final class PluginPackageVersions {
    /// Reserved directory containing immutable package versions below a plugin package root.
    public static final String VERSIONS_DIRECTORY = ".versions";

    /// Generated JAR containing resources and loose classes stored at a Mixin plugin package root.
    public static final String ROOT_RESOURCE_JAR = ".hmcl-agent-root.jar";

    /// Root manifest entry required in every plugin package.
    private static final String PLUGIN_MANIFEST = "plugin.json";

    /// Marker written only after a version has been completely prepared.
    private static final String COMPLETION_MARKER = ".hmcl-complete";

    /// Current immutable package layout version stored in the completion marker.
    private static final String LAYOUT_VERSION = "1";

    /// Maximum number of archive entries accepted from one plugin package.
    private static final int MAX_ARCHIVE_ENTRIES = 10_000;

    /// Maximum aggregate uncompressed bytes accepted from one plugin package.
    private static final long MAX_ARCHIVE_BYTES = 512L * 1024L * 1024L;

    /// Plugin identifiers accepted as safe content-addressed directory names.
    private static final Pattern PLUGIN_ID_PATTERN = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9._-]{1,127}");

    /// JVM-local monitors preventing overlapping file locks for the same version in one process.
    private static final Map<Path, Object> LOCAL_LOCKS = new ConcurrentHashMap<>();

    /// Maximum attempts used when Windows temporarily denies a same-directory cache publication rename.
    private static final int PUBLICATION_MOVE_ATTEMPTS = 5;

    /// Initial delay before retrying a transient cache publication denial.
    private static final long PUBLICATION_RETRY_DELAY_MILLIS = 25L;

    /// Prepares a package version used by the regular plugin lifecycle loader.
    ///
    /// @param nplFile source plugin package
    /// @param packageRoot root such as `plugin-data`
    /// @param pluginId validated plugin identifier
    /// @return immutable extracted package directory
    /// @throws IOException if hashing, extraction, validation, or publication fails
    public static Path prepareLifecyclePackage(Path nplFile, Path packageRoot, String pluginId) throws IOException {
        return preparePackage(nplFile, packageRoot, pluginId, false);
    }

    /// Prepares and inventories a package version used by the regular plugin lifecycle loader.
    ///
    /// @param nplFile source plugin package
    /// @param packageRoot root such as `plugin-data`
    /// @param identity exact source artifact identity
    /// @return immutable verified extracted package
    /// @throws IOException if hashing, extraction, validation, publication, or inventory creation fails
    public static VerifiedPluginPackage prepareVerifiedLifecyclePackage(
            Path nplFile,
            Path packageRoot,
            PluginArtifactIdentity identity
    ) throws IOException {
        return prepareVerifiedPackage(nplFile, packageRoot, identity, false);
    }

    /// Prepares a package version used by the startup Mixin agent.
    ///
    /// A root-resource JAR is generated before publication, ensuring the agent never writes into
    /// a version directory after another process may have begun using it.
    ///
    /// @param nplFile source plugin package
    /// @param packageRoot root such as `plugin-cache`
    /// @param pluginId validated plugin identifier
    /// @return immutable extracted package directory
    /// @throws IOException if hashing, extraction, validation, root-JAR generation, or publication fails
    public static Path prepareMixinPackage(Path nplFile, Path packageRoot, String pluginId) throws IOException {
        return preparePackage(nplFile, packageRoot, pluginId, true);
    }

    /// Prepares and inventories a package version used by the startup Mixin Agent.
    ///
    /// @param nplFile source plugin package
    /// @param packageRoot root such as `plugin-cache`
    /// @param identity exact source artifact identity
    /// @return immutable verified extracted package including the generated root-resource JAR
    /// @throws IOException if hashing, extraction, validation, publication, or inventory creation fails
    public static VerifiedPluginPackage prepareVerifiedMixinPackage(
            Path nplFile,
            Path packageRoot,
            PluginArtifactIdentity identity
    ) throws IOException {
        return prepareVerifiedPackage(nplFile, packageRoot, identity, true);
    }

    /// Returns the directory containing all immutable versions for one plugin.
    ///
    /// @param packageRoot plugin package or cache root
    /// @param pluginId validated plugin identifier
    /// @return version container directory
    public static Path getPluginVersionsDirectory(Path packageRoot, String pluginId) {
        if (!PLUGIN_ID_PATTERN.matcher(pluginId).matches()
                || !PluginManifest.isCanonicalExecutableId(pluginId)) {
            throw new IllegalArgumentException("Invalid plugin ID: " + pluginId);
        }
        return packageRoot.resolve(VERSIONS_DIRECTORY).resolve(pluginId);
    }

    /// Calculates the lower-case SHA-256 digest of a plugin package.
    ///
    /// @param file package file
    /// @return lower-case hexadecimal digest
    /// @throws IOException if the file cannot be read or SHA-256 is unavailable
    public static String calculateSha256(Path file) throws IOException {
        try (InputStream input = new BufferedInputStream(Files.newInputStream(file))) {
            return calculateSha256(input);
        }
    }

    /// Returns whether a verified extracted package owns one root or nested-JAR resource.
    ///
    /// The lookup never delegates to a parent or system class loader, so another artifact cannot satisfy a package's
    /// Mixin resource validation with a same-named entry.
    ///
    /// @param packageDirectory verified extracted package directory
    /// @param resource normalized package-relative resource name
    /// @return whether the resource exists in this package itself
    /// @throws IOException if package traversal or a nested JAR read fails
    public static boolean containsPackageResource(Path packageDirectory, String resource) throws IOException {
        Path directResource = packageDirectory.resolve(resource).normalize();
        if (directResource.startsWith(packageDirectory)
                && !Files.isSymbolicLink(directResource)
                && Files.isRegularFile(directResource)) {
            return true;
        }
        try (Stream<Path> files = Files.walk(packageDirectory)) {
            for (Path jar : files
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                    .sorted()
                    .toList()) {
                try (ZipFile zipFile = new ZipFile(jar.toFile())) {
                    @Nullable ZipEntry entry = zipFile.getEntry(resource);
                    if (entry != null && !entry.isDirectory()) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /// Prepares one content-addressed version and returns the exact validated file inventory.
    ///
    /// @param nplFile source plugin package
    /// @param packageRoot lifecycle or Mixin cache root
    /// @param identity exact source artifact identity
    /// @param generateRootResourceJar whether the generated Mixin resource JAR is required
    /// @return immutable verified package inventory
    /// @throws IOException if the source identity or prepared version cannot be verified
    private static VerifiedPluginPackage prepareVerifiedPackage(
            Path nplFile,
            Path packageRoot,
            PluginArtifactIdentity identity,
            boolean generateRootResourceJar
    ) throws IOException {
        Path snapshot = createPackageSnapshot(nplFile, packageRoot);
        try {
            String sourceHash = calculateSha256(snapshot);
            if (!identity.getSha256().equals(sourceHash)) {
                throw new IOException("Plugin package identity does not match source bytes: " + identity.getPluginId());
            }
            Path directory = preparePackage(
                    snapshot,
                    packageRoot,
                    identity.getPluginId(),
                    generateRootResourceJar
            );
            return createVerifiedPackage(snapshot, directory, identity, generateRootResourceJar);
        } finally {
            Files.deleteIfExists(snapshot);
        }
    }

    /// Copies one source package through a no-follow file handle so all later verification consumes stable bytes.
    ///
    /// A concurrent rename or rewrite can change the source path, but it cannot change the private snapshot already
    /// opened and copied by this method. The snapshot digest is compared with the artifact identity before extraction.
    ///
    /// @param nplFile mutable installed package path
    /// @param packageRoot trusted launcher-owned cache root
    /// @return private package snapshot
    /// @throws IOException if the source is symbolic, non-regular, unreadable, or cannot be copied
    private static Path createPackageSnapshot(Path nplFile, Path packageRoot) throws IOException {
        if (!Files.isRegularFile(nplFile, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(nplFile)) {
            throw new IOException("Plugin package must be a non-symbolic regular file: " + nplFile);
        }
        Files.createDirectories(packageRoot);
        Path normalizedPackageRoot = packageRoot.toAbsolutePath().normalize();
        if (!Files.isDirectory(normalizedPackageRoot, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(normalizedPackageRoot)
                || !normalizedPackageRoot.equals(normalizedPackageRoot.toRealPath())) {
            throw new IOException("Plugin package cache root is symbolic or redirected: " + packageRoot);
        }
        Path snapshot = Files.createTempFile(normalizedPackageRoot, ".npl-snapshot-", ".tmp");
        boolean complete = false;
        try (InputStream input = Files.newInputStream(nplFile, LinkOption.NOFOLLOW_LINKS);
             OutputStream output = Files.newOutputStream(snapshot, StandardOpenOption.TRUNCATE_EXISTING)) {
            input.transferTo(output);
            complete = true;
            return snapshot;
        } finally {
            if (!complete) {
                Files.deleteIfExists(snapshot);
            }
        }
    }

    /// Prepares and publishes one immutable content-addressed package version.
    ///
    /// @param nplFile source plugin package
    /// @param packageRoot package root
    /// @param pluginId validated plugin identifier
    /// @param generateRootResourceJar whether to build the Mixin agent root-resource JAR
    /// @return complete immutable version directory
    /// @throws IOException if preparation fails
    private static Path preparePackage(
            Path nplFile,
            Path packageRoot,
            String pluginId,
            boolean generateRootResourceJar
    ) throws IOException {
        String sourceHash = calculateSha256(nplFile);
        Path versionsDirectory = getPluginVersionsDirectory(packageRoot, pluginId);
        Path canonicalDirectory = versionsDirectory.resolve(sourceHash);
        if (isComplete(nplFile, canonicalDirectory, sourceHash, generateRootResourceJar)) {
            return canonicalDirectory;
        }

        Files.createDirectories(versionsDirectory);
        Path normalizedVersion = canonicalDirectory.toAbsolutePath().normalize();
        Object localLock = LOCAL_LOCKS.computeIfAbsent(normalizedVersion, ignored -> new Object());
        synchronized (localLock) {
            if (isComplete(nplFile, canonicalDirectory, sourceHash, generateRootResourceJar)) {
                return canonicalDirectory;
            }
            @Nullable Path reusableRepair = findCompleteRepair(
                    nplFile,
                    versionsDirectory,
                    sourceHash,
                    generateRootResourceJar
            );
            if (reusableRepair != null) {
                return reusableRepair;
            }

            Path lockFile = versionsDirectory.resolve("." + sourceHash + ".lock");
            try (FileChannel channel = FileChannel.open(
                    lockFile,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE
            ); FileLock ignored = channel.lock()) {
                if (isComplete(nplFile, canonicalDirectory, sourceHash, generateRootResourceJar)) {
                    return canonicalDirectory;
                }
                reusableRepair = findCompleteRepair(
                        nplFile,
                        versionsDirectory,
                        sourceHash,
                        generateRootResourceJar
                );
                if (reusableRepair != null) {
                    return reusableRepair;
                }

                Path temporaryDirectory = versionsDirectory.resolve(
                        ".tmp-" + sourceHash + "-" + UUID.randomUUID()
                );
                Path publicationDirectory = Files.exists(canonicalDirectory)
                        ? versionsDirectory.resolve(sourceHash + ".repair-" + UUID.randomUUID())
                        : canonicalDirectory;
                Files.createDirectory(temporaryDirectory);
                try {
                    extractPackage(nplFile, temporaryDirectory);
                    String extractedSourceHash = calculateSha256(nplFile);
                    if (!sourceHash.equals(extractedSourceHash)) {
                        throw new IOException("Plugin package changed while it was being prepared: " + nplFile);
                    }
                    if (generateRootResourceJar) {
                        createRootResourceJar(temporaryDirectory);
                    }
                    Files.writeString(
                            temporaryDirectory.resolve(COMPLETION_MARKER),
                            completionMarker(sourceHash),
                            StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE_NEW
                    );
                    publish(temporaryDirectory, publicationDirectory);
                    if (!isComplete(nplFile, publicationDirectory, sourceHash, generateRootResourceJar)) {
                        throw new IOException(
                                "Published plugin package version is incomplete: " + publicationDirectory
                        );
                    }
                    return publicationDirectory;
                } finally {
                    deleteRecursively(temporaryDirectory);
                }
            }
        }
    }

    /// Finds a complete repair directory without changing an existing canonical content-addressed directory.
    ///
    /// @param nplFile source plugin package
    /// @param versionsDirectory plugin version container
    /// @param sourceHash complete source package digest
    /// @param requireRootResourceJar whether the generated Mixin resource JAR is required
    /// @return reusable repair directory or `null`
    /// @throws IOException if version enumeration or validation fails
    private static @Nullable Path findCompleteRepair(
            Path nplFile,
            Path versionsDirectory,
            String sourceHash,
            boolean requireRootResourceJar
    ) throws IOException {
        String prefix = sourceHash + ".repair-";
        try (Stream<Path> paths = Files.list(versionsDirectory)) {
            for (Path candidate : paths
                    .filter(Files::isDirectory)
                    .filter(path -> path.getFileName().toString().startsWith(prefix))
                    .sorted()
                    .toList()) {
                if (isComplete(nplFile, candidate, sourceHash, requireRootResourceJar)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    /// Builds an immutable loader inventory from a complete prepared package directory.
    ///
    /// @param nplFile exact source package
    /// @param versionDirectory complete prepared directory
    /// @param identity exact source identity
    /// @param requireRootResourceJar whether the generated Mixin resource JAR is required
    /// @return verified package inventory
    /// @throws IOException if any prepared file no longer matches the source package
    private static VerifiedPluginPackage createVerifiedPackage(
            Path nplFile,
            Path versionDirectory,
            PluginArtifactIdentity identity,
            boolean requireRootResourceJar
    ) throws IOException {
        if (!isComplete(nplFile, versionDirectory, identity.getSha256(), requireRootResourceJar)) {
            throw new IOException("Plugin package cache is incomplete: " + versionDirectory);
        }
        @Nullable @Unmodifiable Set<Path> extractedFiles = validateExtractedFiles(nplFile, versionDirectory);
        if (extractedFiles == null) {
            throw new IOException("Plugin package cache changed during inventory creation: " + versionDirectory);
        }
        Map<Path, String> fileDigests = new java.util.LinkedHashMap<>();
        for (Path relative : extractedFiles.stream().sorted().toList()) {
            fileDigests.put(relative, calculateSha256(versionDirectory.resolve(relative)));
        }
        if (requireRootResourceJar) {
            Path relativeRootJar = Path.of(ROOT_RESOURCE_JAR);
            fileDigests.put(relativeRootJar, calculateSha256(versionDirectory.resolve(relativeRootJar)));
        }
        return new VerifiedPluginPackage(versionDirectory, identity, fileDigests);
    }

    /// Extracts a bounded plugin archive into a newly created directory.
    ///
    /// @param nplFile source package
    /// @param targetDirectory empty target directory
    /// @throws IOException if the archive is unsafe, malformed, or exceeds resource limits
    private static void extractPackage(Path nplFile, Path targetDirectory) throws IOException {
        int entryCount = 0;
        long totalBytes = 0;
        byte[] buffer = new byte[8192];
        Set<Path> extractedFiles = new HashSet<>();

        try (ZipInputStream zipInput = new ZipInputStream(
                new BufferedInputStream(Files.newInputStream(nplFile)),
                StandardCharsets.UTF_8
        )) {
            @Nullable ZipEntry entry;
            while ((entry = zipInput.getNextEntry()) != null) {
                entryCount++;
                if (entryCount > MAX_ARCHIVE_ENTRIES) {
                    throw new IOException("Plugin package contains too many entries");
                }

                Path output = targetDirectory.resolve(entry.getName()).normalize();
                if (!output.startsWith(targetDirectory)) {
                    throw new IOException("Plugin package contains an unsafe path: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(output);
                    continue;
                }
                if (!extractedFiles.add(output)) {
                    throw new IOException("Plugin package contains a duplicate path: " + entry.getName());
                }

                @Nullable Path parent = output.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                try (BufferedOutputStream outputStream = new BufferedOutputStream(Files.newOutputStream(output))) {
                    int read;
                    while ((read = zipInput.read(buffer)) >= 0) {
                        if (read == 0) {
                            continue;
                        }
                        totalBytes = Math.addExact(totalBytes, read);
                        if (totalBytes > MAX_ARCHIVE_BYTES) {
                            throw new IOException("Plugin package expands beyond the allowed size");
                        }
                        outputStream.write(buffer, 0, read);
                    }
                }
            }
        } catch (ArithmeticException exception) {
            throw new IOException("Plugin package size overflow", exception);
        }

        if (!Files.isRegularFile(targetDirectory.resolve(PLUGIN_MANIFEST))) {
            throw new IOException("Extracted package has no " + PLUGIN_MANIFEST);
        }
    }

    /// Creates the immutable JAR used to expose loose package-root resources to the system loader.
    ///
    /// @param packageDirectory prepared package directory not yet published
    /// @throws IOException if the resource JAR cannot be created
    private static void createRootResourceJar(Path packageDirectory) throws IOException {
        Path output = packageDirectory.resolve(ROOT_RESOURCE_JAR);
        try (JarOutputStream jarOutput = new JarOutputStream(
                new BufferedOutputStream(Files.newOutputStream(output, StandardOpenOption.CREATE_NEW))
        ); Stream<Path> files = Files.walk(packageDirectory)) {
            @Unmodifiable List<Path> resourceFiles = files
                    .filter(Files::isRegularFile)
                    .filter(path -> !path.equals(output))
                    .filter(path -> !path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                    .sorted()
                    .toList();
            for (Path file : resourceFiles) {
                String entryName = packageDirectory.relativize(file).toString().replace('\\', '/');
                JarEntry jarEntry = new JarEntry(entryName);
                jarEntry.setTime(0);
                jarOutput.putNextEntry(jarEntry);
                try (BufferedInputStream input = new BufferedInputStream(Files.newInputStream(file))) {
                    input.transferTo(jarOutput);
                }
                jarOutput.closeEntry();
            }
        }
    }

    /// Publishes a prepared directory without replacing an existing immutable version.
    ///
    /// @param temporaryDirectory complete temporary directory
    /// @param versionDirectory final content-addressed directory
    /// @throws IOException if publication fails
    private static void publish(Path temporaryDirectory, Path versionDirectory) throws IOException {
        publishWithRetries(temporaryDirectory, versionDirectory, Files::move);
    }

    /// Publishes a prepared directory with bounded retries for transient Windows sharing violations.
    ///
    /// The default same-directory move preserves the required no-replace contract and reports a
    /// raced target explicitly. A short-lived scanner or indexer handle can still deny the rename;
    /// bounded retry lets that transient handle clear without weakening the immutable cache rules.
    ///
    /// @param temporaryDirectory complete temporary directory
    /// @param versionDirectory final content-addressed directory
    /// @param mover directory move operation
    /// @throws IOException if publication remains denied, a target races into place, or moving fails
    static void publishWithRetries(
            Path temporaryDirectory,
            Path versionDirectory,
            PublicationMover mover
    ) throws IOException {
        List<AccessDeniedException> accessDeniedFailures = new ArrayList<>();
        for (int attempt = 0; attempt < PUBLICATION_MOVE_ATTEMPTS; attempt++) {
            try {
                mover.move(temporaryDirectory, versionDirectory);
                return;
            } catch (AccessDeniedException exception) {
                accessDeniedFailures.add(exception);
                if (Files.exists(versionDirectory, LinkOption.NOFOLLOW_LINKS)) {
                    FileAlreadyExistsException racedTarget = new FileAlreadyExistsException(
                            temporaryDirectory.toString(),
                            versionDirectory.toString(),
                            "Plugin package cache target appeared while publication was retrying"
                    );
                    accessDeniedFailures.forEach(racedTarget::addSuppressed);
                    throw racedTarget;
                }
                if (attempt + 1 >= PUBLICATION_MOVE_ATTEMPTS) {
                    IOException exhausted = new IOException(
                            "Plugin package cache publication remained access-denied after "
                                    + PUBLICATION_MOVE_ATTEMPTS + " attempts: "
                                    + temporaryDirectory + " -> " + versionDirectory,
                            exception
                    );
                    accessDeniedFailures.stream()
                            .limit(accessDeniedFailures.size() - 1L)
                            .forEach(exhausted::addSuppressed);
                    throw exhausted;
                }

                long delayMillis = PUBLICATION_RETRY_DELAY_MILLIS << attempt;
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                    IOException interrupted = new IOException(
                            "Interrupted while retrying plugin package cache publication: "
                                    + temporaryDirectory + " -> " + versionDirectory,
                            interruptedException
                    );
                    accessDeniedFailures.forEach(interrupted::addSuppressed);
                    throw interrupted;
                }
            }
        }
        throw new AssertionError("Publication retry loop completed without returning or throwing");
    }

    /// Returns whether a published directory exactly matches its source package and generated resource JAR.
    ///
    /// @param nplFile source package used to validate every extracted file
    /// @param versionDirectory candidate immutable version
    /// @param sourceHash expected source package hash
    /// @param requireRootResourceJar whether the Mixin root-resource JAR is required
    /// @return whether the version can be safely reused
    private static boolean isComplete(
            Path nplFile,
            Path versionDirectory,
            String sourceHash,
            boolean requireRootResourceJar
    ) throws IOException {
        Path marker = versionDirectory.resolve(COMPLETION_MARKER);
        if (!Files.isRegularFile(marker)
                || !completionMarker(sourceHash).equals(Files.readString(marker, StandardCharsets.UTF_8))) {
            return false;
        }

        @Nullable @Unmodifiable Set<Path> extractedFiles = validateExtractedFiles(nplFile, versionDirectory);
        if (extractedFiles == null) {
            return false;
        }
        if (requireRootResourceJar && !validateRootResourceJar(versionDirectory, extractedFiles)) {
            return false;
        }

        Set<Path> allowedFiles = new HashSet<>(extractedFiles);
        allowedFiles.add(Path.of(COMPLETION_MARKER));
        if (requireRootResourceJar) {
            allowedFiles.add(Path.of(ROOT_RESOURCE_JAR));
        }
        try (Stream<Path> paths = Files.walk(versionDirectory)) {
            for (Path path : paths.toList()) {
                if (Files.isSymbolicLink(path)) {
                    return false;
                }
                if (Files.isRegularFile(path)
                        && !allowedFiles.contains(versionDirectory.relativize(path))) {
                    return false;
                }
            }
        }
        return true;
    }

    /// Validates every extracted file against the corresponding source archive entry.
    ///
    /// @param nplFile source plugin package
    /// @param versionDirectory candidate extracted directory
    /// @return relative extracted file paths, or `null` when any byte or path differs
    /// @throws IOException if the source archive cannot be read
    private static @Nullable @Unmodifiable Set<Path> validateExtractedFiles(
            Path nplFile,
            Path versionDirectory
    ) throws IOException {
        int entryCount = 0;
        long totalBytes = 0;
        byte[] buffer = new byte[8192];
        Set<Path> expectedFiles = new HashSet<>();
        try (ZipInputStream zipInput = new ZipInputStream(
                new BufferedInputStream(Files.newInputStream(nplFile)),
                StandardCharsets.UTF_8
        )) {
            @Nullable ZipEntry entry;
            while ((entry = zipInput.getNextEntry()) != null) {
                entryCount++;
                if (entryCount > MAX_ARCHIVE_ENTRIES) {
                    throw new IOException("Plugin package contains too many entries");
                }
                Path output = versionDirectory.resolve(entry.getName()).normalize();
                if (!output.startsWith(versionDirectory)) {
                    throw new IOException("Plugin package contains an unsafe path: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    continue;
                }
                Path relative = versionDirectory.relativize(output);
                if (!expectedFiles.add(relative)
                        || Files.isSymbolicLink(output)
                        || !Files.isRegularFile(output)) {
                    return null;
                }

                MessageDigest digest = createSha256();
                long entryBytes = 0;
                int read;
                while ((read = zipInput.read(buffer)) >= 0) {
                    if (read == 0) {
                        continue;
                    }
                    entryBytes = Math.addExact(entryBytes, read);
                    totalBytes = Math.addExact(totalBytes, read);
                    if (totalBytes > MAX_ARCHIVE_BYTES) {
                        throw new IOException("Plugin package expands beyond the allowed size");
                    }
                    digest.update(buffer, 0, read);
                }
                if (Files.size(output) != entryBytes
                        || !toHex(digest.digest()).equals(calculateSha256(output))) {
                    return null;
                }
            }
        } catch (ArithmeticException exception) {
            throw new IOException("Plugin package size overflow", exception);
        }
        return expectedFiles.contains(Path.of(PLUGIN_MANIFEST)) ? Set.copyOf(expectedFiles) : null;
    }

    /// Validates the generated root-resource JAR against verified non-JAR package files.
    ///
    /// @param versionDirectory verified extracted package directory
    /// @param extractedFiles verified relative package file paths
    /// @return whether the generated JAR has exactly the expected entries and bytes
    /// @throws IOException if the JAR or a verified package file cannot be read
    private static boolean validateRootResourceJar(
            Path versionDirectory,
            @Unmodifiable Set<Path> extractedFiles
    ) throws IOException {
        Path rootResourceJar = versionDirectory.resolve(ROOT_RESOURCE_JAR);
        if (!Files.isRegularFile(rootResourceJar) || Files.isSymbolicLink(rootResourceJar)) {
            return false;
        }
        Set<String> expectedEntries = new HashSet<>();
        for (Path relative : extractedFiles) {
            if (!relative.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")) {
                expectedEntries.add(relative.toString().replace('\\', '/'));
            }
        }

        try (JarFile jarFile = new JarFile(rootResourceJar.toFile())) {
            Set<String> actualEntries = new HashSet<>();
            for (JarEntry entry : jarFile.stream().filter(candidate -> !candidate.isDirectory()).toList()) {
                if (!actualEntries.add(entry.getName())) {
                    return false;
                }
                Path source = versionDirectory.resolve(entry.getName()).normalize();
                if (!source.startsWith(versionDirectory)
                        || !expectedEntries.contains(entry.getName())
                        || Files.size(source) != entry.getSize()) {
                    return false;
                }
                try (InputStream input = new BufferedInputStream(jarFile.getInputStream(entry))) {
                    if (!calculateSha256(input).equals(calculateSha256(source))) {
                        return false;
                    }
                }
            }
            return actualEntries.equals(expectedEntries);
        }
    }

    /// Moves an invalid immutable version aside so a verified replacement can be published safely.
    ///
    /// Existing processes keep any already-open files, while new discovery never loads the quarantined directory.
    ///
    /// @param versionDirectory invalid content-addressed version
    /// @throws IOException if the directory cannot be quarantined
    private static void quarantineIncompleteVersion(Path versionDirectory) throws IOException {
        Path quarantine = versionDirectory.resolveSibling(
                ".invalid-" + versionDirectory.getFileName() + "-" + UUID.randomUUID()
        );
        try {
            Files.move(versionDirectory, quarantine, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(versionDirectory, quarantine);
        }
    }

    /// Calculates a SHA-256 digest from the current position to the end of an input stream.
    ///
    /// @param input source bytes
    /// @return lower-case hexadecimal digest
    /// @throws IOException if reading fails or SHA-256 is unavailable
    private static String calculateSha256(InputStream input) throws IOException {
        MessageDigest digest = createSha256();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) >= 0) {
            if (read > 0) {
                digest.update(buffer, 0, read);
            }
        }
        return toHex(digest.digest());
    }

    /// Creates the required SHA-256 message digest.
    ///
    /// @return new digest instance
    /// @throws IOException if SHA-256 is unavailable
    private static MessageDigest createSha256() throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IOException("SHA-256 is unavailable", exception);
        }
    }

    /// Converts digest bytes to lower-case hexadecimal text.
    ///
    /// @param bytes digest bytes
    /// @return lower-case hexadecimal digest
    private static String toHex(byte @Unmodifiable [] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        }
        return result.toString();
    }

    /// Builds the exact completion marker contents for one source hash.
    ///
    /// @param sourceHash source package SHA-256
    /// @return marker text
    private static String completionMarker(String sourceHash) {
        return LAYOUT_VERSION + "\n" + sourceHash + "\n";
    }

    /// Deletes a temporary directory tree owned by the current preparation attempt.
    ///
    /// @param path temporary path
    /// @throws IOException if cleanup fails
    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (Stream<Path> files = Files.walk(path)) {
            for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(file);
            }
        }
    }

    /// Moves one prepared directory into its final immutable cache location.
    @FunctionalInterface
    @NotNullByDefault
    interface PublicationMover {
        /// Moves the source directory without replacing an existing target.
        ///
        /// @param source prepared source directory
        /// @param target immutable publication target
        /// @throws IOException if the move cannot complete
        void move(Path source, Path target) throws IOException;
    }

    /// Prevents construction of the immutable package utility.
    private PluginPackageVersions() {
    }
}
