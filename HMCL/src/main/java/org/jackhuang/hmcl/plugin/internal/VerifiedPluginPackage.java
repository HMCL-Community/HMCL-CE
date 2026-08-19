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
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/// Captures the exact extracted files validated against one complete `.npl` artifact.
///
/// Loaders consume this immutable inventory directly and never walk the cache again, so files injected after package
/// validation cannot become lifecycle class-path entries or script entry points.
@NotNullByDefault
public final class VerifiedPluginPackage {
    /// Monotonic identifier that keeps independently captured in-memory resource URLs distinct.
    private static final AtomicLong RESOURCE_URL_SEQUENCE = new AtomicLong();

    /// Maximum compressed size of one nested JAR captured for a verified lookup.
    static final long MAX_NESTED_JAR_BYTES = 64L * 1024L * 1024L;

    /// Maximum number of nested-JAR entries inspected during one resource lookup.
    static final long MAX_NESTED_JAR_ENTRIES = 10_000L;

    /// Maximum uncompressed size of one nested-JAR entry.
    static final long MAX_NESTED_ENTRY_BYTES = 16L * 1024L * 1024L;

    /// Maximum cumulative nested-JAR bytes decompressed during one resource lookup.
    static final long MAX_NESTED_EXPANDED_BYTES = 256L * 1024L * 1024L;

    /// Maximum cumulative nested-JAR entry bytes retained by one verified package index.
    static final long MAX_NESTED_RETAINED_BYTES = 64L * 1024L * 1024L;

    /// Maximum cumulative resource bytes retained by one lookup result.
    static final long MAX_RESOURCE_SNAPSHOT_BYTES = 32L * 1024L * 1024L;

    /// Immutable extracted package root.
    private final Path directory;

    /// Exact source artifact represented by the directory.
    private final PluginArtifactIdentity identity;

    /// Verified relative files mapped to their lower-case SHA-256 digests.
    private final @Unmodifiable Map<Path, String> fileDigests;

    /// Exact nested JAR files validated for class-path use.
    private final @Unmodifiable List<Path> jarFiles;

    /// Lock serializing the first complete nested-JAR verification and in-memory indexing pass.
    private final Object nestedJarIndexLock = new Object();

    /// Immutable nested-JAR resource index, or `null` until the first nested lookup succeeds.
    private volatile @Nullable NestedJarIndex nestedJarIndex;

    /// Creates a verified package inventory.
    ///
    /// @param directory immutable extracted directory
    /// @param identity exact source artifact
    /// @param fileDigests verified relative file digests
    VerifiedPluginPackage(
            Path directory,
            PluginArtifactIdentity identity,
            Map<Path, String> fileDigests
    ) {
        this.directory = directory.toAbsolutePath().normalize();
        this.identity = identity;
        this.fileDigests = Collections.unmodifiableMap(new LinkedHashMap<>(fileDigests));
        this.jarFiles = this.fileDigests.keySet().stream()
                .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                .map(this.directory::resolve)
                .toList();
    }

    /// Returns the immutable extracted package root.
    ///
    /// @return package directory
    public Path getDirectory() {
        return directory;
    }

    /// Returns the exact source artifact identity.
    ///
    /// @return package identity
    public PluginArtifactIdentity getIdentity() {
        return identity;
    }

    /// Returns exact JAR paths from the verified package inventory.
    ///
    /// @return immutable nested JAR list
    public @Unmodifiable List<Path> getJarFiles() {
        return jarFiles;
    }

    /// Returns exact relative files validated from the source package and generated cache artifacts.
    ///
    /// @return immutable relative path set
    public @Unmodifiable Set<Path> getRelativeFiles() {
        return fileDigests.keySet();
    }

    /// Verifies that every inventoried file still has its original bytes and no symbolic-link component.
    ///
    /// Extra files are deliberately ignored because loaders never enumerate them.
    ///
    /// @throws IOException if an inventoried file changed or became unsafe
    public void verifyIntegrity() throws IOException {
        verifyDirectoryIdentity();
        for (Map.Entry<Path, String> entry : fileDigests.entrySet()) {
            Path file = resolveWithoutSymbolicLinks(entry.getKey());
            if (!Files.isRegularFile(file)
                    || !entry.getValue().equals(PluginPackageVersions.calculateSha256(file))) {
                throw new IOException("Verified plugin cache file changed: " + entry.getKey());
            }
        }
        verifyDirectoryIdentity();
    }

    /// Resolves a direct package file only when it belongs to the verified source inventory.
    ///
    /// @param relativePath manifest-provided relative path
    /// @return verified absolute file path
    /// @throws IOException if the path escapes, is absent, or contains a symbolic link
    public Path resolveVerifiedFile(String relativePath) throws IOException {
        Path relative = parseSafeRelativePath(relativePath);
        if (!fileDigests.containsKey(relative)) {
            throw new IOException("File is not present in the verified plugin package: " + relativePath);
        }
        Path file = resolveWithoutSymbolicLinks(relative);
        String expectedDigest = fileDigests.get(relative);
        if (!Files.isRegularFile(file)
                || !expectedDigest.equals(PluginPackageVersions.calculateSha256(file))) {
            throw new IOException("Verified plugin file changed: " + relativePath);
        }
        return file;
    }

    /// Returns whether one safe relative path is an inventoried loose package file.
    ///
    /// @param relativePath package-relative path
    /// @return whether the exact loose file belongs to the source artifact
    /// @throws IOException if the path is malformed or unsafe
    public boolean containsLooseFile(String relativePath) throws IOException {
        return fileDigests.containsKey(parseSafeRelativePath(relativePath));
    }

    /// Reads one resource into verified immutable bytes from a loose file or inventoried nested JAR.
    ///
    /// The digest is calculated from the bytes actually returned, preventing swap-read-restore races between a path
    /// check and later class or resource consumption.
    ///
    /// @param resource safe package-relative resource name
    /// @return verified bytes or `null` when absent
    /// @throws IOException if an inventoried file changed or the resource path is unsafe
    public byte @Nullable @Unmodifiable [] readResourceBytes(String resource) throws IOException {
        @Unmodifiable List<byte @Unmodifiable []> resources = readResourceByteArrays(resource, true);
        return resources.isEmpty() ? null : resources.get(0);
    }

    /// Captures the first matching resource in an immutable in-memory URL.
    ///
    /// Opening the returned URL never touches the extracted package again. The URL therefore keeps serving the exact
    /// bytes whose owning loose file or nested JAR passed digest verification during this method call.
    ///
    /// @param resource safe package-relative resource name
    /// @return immutable in-memory resource URL or `null` when absent
    /// @throws IOException if an inventoried file changed or the resource path is unsafe
    public @Nullable URL snapshotResourceUrl(String resource) throws IOException {
        @Unmodifiable List<byte @Unmodifiable []> resources = readResourceByteArrays(resource, true);
        return resources.isEmpty() ? null : createMemoryResourceUrl(resources.get(0));
    }

    /// Captures every matching loose and nested-JAR resource in immutable in-memory URLs.
    ///
    /// Resource order matches class-path precedence: the inventoried loose file first, followed by nested JARs in
    /// verified inventory order. Every returned URL owns a private copy of bytes derived from a verified file read.
    ///
    /// @param resource safe package-relative resource name
    /// @return immutable in-memory resource URL list
    /// @throws IOException if an inventoried file changed or the resource path is unsafe
    public @Unmodifiable List<URL> snapshotResourceUrls(String resource) throws IOException {
        List<URL> urls = new ArrayList<>();
        for (byte @Unmodifiable [] bytes : readResourceByteArrays(resource, false)) {
            urls.add(createMemoryResourceUrl(bytes));
        }
        return List.copyOf(urls);
    }

    /// Reads matching resources from bytes captured and verified during this method call.
    ///
    /// @param resource safe package-relative resource name
    /// @param firstOnly whether to stop after the first class-path match
    /// @return immutable list whose byte arrays are newly owned by the caller
    /// @throws IOException if an inventoried file changed or the resource path is unsafe
    private @Unmodifiable List<byte @Unmodifiable []> readResourceByteArrays(
            String resource,
            boolean firstOnly
    ) throws IOException {
        Path relative = parseSafeRelativePath(resource);
        List<byte @Unmodifiable []> resources = new ArrayList<>();
        BoundedCounter retainedBytes = new BoundedCounter(
                MAX_RESOURCE_SNAPSHOT_BYTES,
                "Plugin resource lookup retained more than 32 MiB"
        );
        if (fileDigests.containsKey(relative)) {
            byte @Unmodifiable [] bytes = readVerifiedFileBytes(relative, MAX_RESOURCE_SNAPSHOT_BYTES);
            retainedBytes.consume(bytes.length);
            resources.add(bytes);
            if (firstOnly) {
                return List.copyOf(resources);
            }
        }
        resources.addAll(getNestedJarIndex().copyResources(resource, firstOnly, retainedBytes));
        return List.copyOf(resources);
    }

    /// Returns the immutable nested-JAR index, building it exactly once after full byte verification.
    ///
    /// A failed build is never cached, so restoring the exact inventoried JAR bytes permits a later retry.
    ///
    /// @return immutable private nested-JAR resource index
    /// @throws IOException if a JAR changed, is malformed, or exceeds a resource limit
    private NestedJarIndex getNestedJarIndex() throws IOException {
        @Nullable NestedJarIndex current = nestedJarIndex;
        if (current != null) {
            return current;
        }
        synchronized (nestedJarIndexLock) {
            current = nestedJarIndex;
            if (current == null) {
                current = captureNestedJarIndex();
                nestedJarIndex = current;
            }
            return current;
        }
    }

    /// Verifies every inventoried nested JAR and captures all bounded entries into one immutable index.
    ///
    /// @return immutable nested-JAR resource index
    /// @throws IOException if a JAR changed, is malformed, or exceeds a resource limit
    private NestedJarIndex captureNestedJarIndex() throws IOException {
        Map<String, List<byte @Unmodifiable []>> indexedResources = new LinkedHashMap<>();
        BoundedCounter inspectedEntries = new BoundedCounter(
                MAX_NESTED_JAR_ENTRIES,
                "Plugin package contains more than 10000 nested-JAR entries"
        );
        BoundedCounter expandedBytes = new BoundedCounter(
                MAX_NESTED_EXPANDED_BYTES,
                "Plugin package expands more than 256 MiB from nested JARs"
        );
        BoundedCounter retainedBytes = new BoundedCounter(
                MAX_NESTED_RETAINED_BYTES,
                "Plugin package retains more than 64 MiB of nested-JAR resources"
        );
        for (Path jar : jarFiles) {
            Path jarRelative = directory.relativize(jar.toAbsolutePath().normalize());
            byte @Unmodifiable [] jarBytes = readVerifiedFileBytes(jarRelative, MAX_NESTED_JAR_BYTES);
            try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(jarBytes))) {
                @Nullable ZipEntry entry;
                while ((entry = input.getNextEntry()) != null) {
                    inspectedEntries.consume(1);
                    @Nullable ByteArrayOutputStream captured = entry.isDirectory()
                            ? null
                            : new ByteArrayOutputStream();
                    BoundedCounter entryBytes = new BoundedCounter(
                            MAX_NESTED_ENTRY_BYTES,
                            "Nested-JAR entry exceeds 16 MiB: " + entry.getName()
                    );
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = input.read(buffer)) >= 0) {
                        if (read == 0) {
                            continue;
                        }
                        entryBytes.consume(read);
                        expandedBytes.consume(read);
                        if (captured != null) {
                            retainedBytes.consume(read);
                            captured.write(buffer, 0, read);
                        }
                    }
                    if (captured != null) {
                        indexedResources.computeIfAbsent(entry.getName(), ignored -> new ArrayList<>())
                                .add(captured.toByteArray());
                    }
                    input.closeEntry();
                }
            }
        }
        return new NestedJarIndex(indexedResources);
    }

    /// Returns whether this exact package owns one loose or nested-JAR resource.
    ///
    /// @param resource normalized package-relative resource name
    /// @return whether the verified inventory contains the resource exactly once or more
    /// @throws IOException if an inventoried JAR changed or cannot be read
    public boolean containsResource(String resource) throws IOException {
        return readResourceBytes(resource) != null;
    }

    /// Returns whether this exact package contains the bytecode for one binary class name.
    ///
    /// @param binaryClassName Java binary class name
    /// @return whether the class bytes are package-owned
    /// @throws IOException if package verification fails
    public boolean containsClass(String binaryClassName) throws IOException {
        return containsResource(binaryClassName.replace('.', '/') + ".class");
    }

    /// Reads one inventoried loose file and validates the digest of the returned bytes.
    ///
    /// @param relative inventoried relative path
    /// @param maximumBytes maximum bytes captured for this lookup
    /// @return verified file bytes
    /// @throws IOException if the path, file type, or returned bytes changed
    private byte @Unmodifiable [] readVerifiedFileBytes(Path relative, long maximumBytes) throws IOException {
        @Nullable String expectedDigest = fileDigests.get(relative);
        if (expectedDigest == null) {
            throw new IOException("Plugin file is not present in the verified inventory: " + relative);
        }
        Path file = resolveWithoutSymbolicLinks(relative);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Verified plugin cache file is not regular: " + relative);
        }
        byte[] bytes;
        try (SeekableByteChannel channel = Files.newByteChannel(
                file,
                StandardOpenOption.READ,
                LinkOption.NOFOLLOW_LINKS
        )) {
            long size = channel.size();
            if (size > maximumBytes || size > Integer.MAX_VALUE) {
                throw new IOException("Verified plugin cache file exceeds the lookup limit: " + relative);
            }
            bytes = new byte[(int) size];
            ByteBuffer target = ByteBuffer.wrap(bytes);
            while (target.hasRemaining()) {
                int read = channel.read(target);
                if (read < 0) {
                    bytes = Arrays.copyOf(bytes, target.position());
                    break;
                }
            }
            ByteBuffer overflowProbe = ByteBuffer.allocate(1);
            if (channel.read(overflowProbe) >= 0) {
                throw new IOException("Verified plugin cache file grew during lookup: " + relative);
            }
        }
        if (!expectedDigest.equals(calculateSha256(bytes))) {
            throw new IOException("Verified plugin cache file changed: " + relative);
        }
        verifyDirectoryIdentity();
        return bytes;
    }

    /// Calculates a lower-case SHA-256 digest over bytes already captured for execution.
    ///
    /// @param bytes captured file bytes
    /// @return lower-case SHA-256
    private static String calculateSha256(byte @Unmodifiable [] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    /// Creates one opaque URL backed only by a private copy of captured resource bytes.
    ///
    /// @param bytes verified resource bytes
    /// @return in-memory URL with a unique stable identity
    /// @throws IOException if the JVM rejects the private URL protocol representation
    private static URL createMemoryResourceUrl(byte @Unmodifiable [] bytes) throws IOException {
        long sequence = RESOURCE_URL_SEQUENCE.incrementAndGet();
        return new URL(
                null,
                "hmcl-plugin-memory:/" + Long.toUnsignedString(sequence),
                new MemoryResourceUrlStreamHandler(bytes)
        );
    }

    /// Opens URL connections over one immutable captured resource.
    @NotNullByDefault
    private static final class MemoryResourceUrlStreamHandler extends URLStreamHandler {
        /// Private immutable resource bytes retained by the URL handler.
        private final byte @Unmodifiable [] bytes;

        /// Creates a handler with an isolated copy of verified bytes.
        ///
        /// @param bytes verified resource bytes
        private MemoryResourceUrlStreamHandler(byte @Unmodifiable [] bytes) {
            this.bytes = bytes.clone();
        }

        /// Opens a new read-only connection over the retained byte snapshot.
        ///
        /// @param url owning in-memory URL
        /// @return read-only byte-array connection
        @Override
        protected URLConnection openConnection(URL url) {
            return new MemoryResourceUrlConnection(url, bytes);
        }
    }

    /// Enforces one monotonic count or byte budget without arithmetic overflow.
    @NotNullByDefault
    private static final class BoundedCounter {
        /// Maximum permitted cumulative value.
        private final long limit;

        /// Error reported when the cumulative value exceeds the limit.
        private final String failureMessage;

        /// Current cumulative value.
        private long value;

        /// Creates a counter with a fixed fail-closed limit.
        ///
        /// @param limit maximum cumulative value
        /// @param failureMessage error reported when the limit is exceeded
        private BoundedCounter(long limit, String failureMessage) {
            this.limit = limit;
            this.failureMessage = failureMessage;
        }

        /// Adds one non-negative amount and rejects overflow or a limit violation.
        ///
        /// @param amount amount to add
        /// @throws IOException if the counter would overflow or exceed its limit
        private void consume(long amount) throws IOException {
            if (amount < 0 || value > limit - amount) {
                throw new IOException(failureMessage);
            }
            value += amount;
        }
    }

    /// Owns the complete bounded nested-JAR entry snapshot without exposing mutable backing arrays.
    @NotNullByDefault
    private static final class NestedJarIndex {
        /// Resource entries in verified class-path and duplicate-entry order.
        private final @Unmodifiable Map<String, @Unmodifiable List<byte @Unmodifiable []>> resources;

        /// Creates a deeply unmodifiable map around privately owned entry arrays.
        ///
        /// @param resources captured mutable resource map
        private NestedJarIndex(Map<String, List<byte @Unmodifiable []>> resources) {
            Map<String, @Unmodifiable List<byte @Unmodifiable []>> immutable = new LinkedHashMap<>();
            resources.forEach((name, entries) -> immutable.put(name, List.copyOf(entries)));
            this.resources = Collections.unmodifiableMap(immutable);
        }

        /// Copies matching private entry bytes for one caller without exposing the cached arrays.
        ///
        /// @param resource exact safe resource name
        /// @param firstOnly whether only the first class-path match is needed
        /// @param retainedBytes caller result byte budget
        /// @return immutable list of isolated matching byte arrays
        /// @throws IOException if copying matches would exceed the caller result budget
        private @Unmodifiable List<byte @Unmodifiable []> copyResources(
                String resource,
                boolean firstOnly,
                BoundedCounter retainedBytes
        ) throws IOException {
            @Nullable List<byte @Unmodifiable []> matches = resources.get(resource);
            if (matches == null || matches.isEmpty()) {
                return List.of();
            }
            int count = firstOnly ? 1 : matches.size();
            List<byte @Unmodifiable []> copies = new ArrayList<>(count);
            for (int index = 0; index < count; index++) {
                byte @Unmodifiable [] bytes = matches.get(index);
                retainedBytes.consume(bytes.length);
                copies.add(bytes.clone());
            }
            return List.copyOf(copies);
        }
    }

    /// Serves one captured resource without consulting a mutable file or JAR path.
    @NotNullByDefault
    private static final class MemoryResourceUrlConnection extends URLConnection {
        /// Immutable bytes shared privately with the owning URL handler.
        private final byte @Unmodifiable [] bytes;

        /// Creates a read-only in-memory resource connection.
        ///
        /// @param url owning resource URL
        /// @param bytes private immutable resource bytes
        private MemoryResourceUrlConnection(URL url, byte @Unmodifiable [] bytes) {
            super(url);
            this.bytes = bytes;
        }

        /// Marks the in-memory connection ready for reads.
        @Override
        public void connect() {
            connected = true;
        }

        /// Opens a fresh stream over the captured bytes.
        ///
        /// @return independent resource input stream
        @Override
        public InputStream getInputStream() {
            connect();
            return new ByteArrayInputStream(bytes);
        }

        /// Returns the captured resource size when it fits the legacy integer API.
        ///
        /// @return resource size or `-1` when larger than an integer
        @Override
        public int getContentLength() {
            return bytes.length;
        }

        /// Returns the exact captured resource size.
        ///
        /// @return resource size in bytes
        @Override
        public long getContentLengthLong() {
            return bytes.length;
        }
    }

    /// Resolves a relative path while rejecting symbolic links in every package-owned component.
    ///
    /// @param relative safe normalized relative path
    /// @return absolute path below the package directory
    /// @throws IOException if a component is a symbolic link
    private Path resolveWithoutSymbolicLinks(Path relative) throws IOException {
        verifyDirectoryIdentity();
        Path current = directory;
        for (Path component : relative) {
            current = current.resolve(component);
            if (Files.isSymbolicLink(current)) {
                throw new IOException("Plugin cache contains a symbolic link: " + relative);
            }
        }
        Path normalized = current.toAbsolutePath().normalize();
        if (!normalized.startsWith(directory)) {
            throw new IOException("Plugin cache path escaped its package: " + relative);
        }
        return normalized;
    }

    /// Verifies that the package root and all of its ancestors still resolve to the captured normalized path.
    ///
    /// @throws IOException if the package root is missing, symbolic, or redirected through a symbolic-link ancestor
    private void verifyDirectoryIdentity() throws IOException {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(directory)
                || !directory.equals(directory.toRealPath())) {
            throw new IOException("Verified plugin package root changed or is symbolic: " + directory);
        }
    }

    /// Parses a manifest path as a normalized package-relative path.
    ///
    /// @param value manifest path
    /// @return safe relative path
    /// @throws IOException if the path is absolute, empty, malformed, or escapes the package
    private static Path parseSafeRelativePath(String value) throws IOException {
        try {
            if (value.isBlank()
                    || value.indexOf('\\') >= 0
                    || value.indexOf(':') >= 0
                    || value.startsWith("/")
                    || value.endsWith("/")
                    || value.contains("//")) {
                throw new IOException("Plugin path must use non-empty forward-slash components: " + value);
            }
            for (String component : value.split("/", -1)) {
                if (component.isEmpty() || component.equals(".") || component.equals("..")) {
                    throw new IOException("Plugin path contains an unsafe component: " + value);
                }
            }
            Path path = Path.of(value);
            if (path.isAbsolute()) {
                throw new IOException("Plugin path must be relative: " + value);
            }
            Path normalized = path.normalize();
            if (normalized.getNameCount() == 0 || normalized.startsWith("..")) {
                throw new IOException("Plugin path escapes its package: " + value);
            }
            return normalized;
        } catch (InvalidPathException exception) {
            throw new IOException("Invalid plugin path: " + value, exception);
        }
    }
}
