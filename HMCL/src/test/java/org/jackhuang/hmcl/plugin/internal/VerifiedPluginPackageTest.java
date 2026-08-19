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
import org.jackhuang.hmcl.plugin.loader.PluginClassLoader;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.List;
import java.util.Objects;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies path, byte-integrity, and verified-only class-path enforcement for extracted packages.
@NotNullByDefault
public final class VerifiedPluginPackageTest {
    /// Loads inventoried loose and nested-JAR classes while ignoring files injected after verification.
    ///
    /// @param temporaryDirectory isolated package cache
    /// @throws Exception if package generation or class loading fails
    @Test
    public void ignorePostVerificationClassPathInjection(@TempDir Path temporaryDirectory) throws Exception {
        VerifiedPluginPackage pluginPackage = preparePackage(temporaryDirectory);
        Path injectedLoose = pluginPackage.getDirectory().resolve("injected/Loose.class");
        Files.createDirectories(Objects.requireNonNull(injectedLoose.getParent()));
        Files.write(injectedLoose, createClass("injected.Loose", "injected-loose"));
        Files.writeString(
                pluginPackage.getDirectory().resolve("injected/resource.txt"),
                "injected-loose-resource",
                StandardCharsets.UTF_8
        );
        Files.write(
                pluginPackage.getDirectory().resolve("injected.jar"),
                createJarWithResource(
                        "injected.Nested",
                        createClass("injected.Nested", "injected-nested"),
                        "injected/resource.txt",
                        "injected-jar-resource".getBytes(StandardCharsets.UTF_8)
                )
        );

        try (PluginClassLoader loader = createLoader(pluginPackage)) {
            assertEquals(loader, loader.loadPluginClass("verified.Loose").getClassLoader());
            assertEquals(loader, loader.loadPluginClass("verified.Nested").getClassLoader());
            assertThrows(ClassNotFoundException.class, () -> loader.loadPluginClass("injected.Loose"));
            assertThrows(ClassNotFoundException.class, () -> loader.loadPluginClass("injected.Nested"));
            assertThrows(
                    ClassNotFoundException.class,
                    () -> loader.loadPluginClass(VerifiedPluginPackageTest.class.getName())
            );
            assertNull(loader.findResource("injected/resource.txt"));
            assertFalse(loader.findResources("injected/resource.txt").hasMoreElements());
        }
    }

    /// Uses package-owned helper classes, rejects unowned third-party parent fallback, and keeps host identity for
    /// launcher-namespace fallback classes.
    ///
    /// @param temporaryDirectory isolated package and parent class paths
    /// @throws Exception if class generation or loading fails
    @Test
    public void rejectParentClasspathHelperInjection(@TempDir Path temporaryDirectory) throws Exception {
        VerifiedPluginPackage pluginPackage = preparePackage(temporaryDirectory);
        Path parentClasses = temporaryDirectory.resolve("parent-classes");
        Path collidingClass = parentClasses.resolve("verified/Loose.class");
        Path parentOnlyClass = parentClasses.resolve("parentonly/Helper.class");
        Files.createDirectories(Objects.requireNonNull(collidingClass.getParent()));
        Files.createDirectories(Objects.requireNonNull(parentOnlyClass.getParent()));
        Files.write(collidingClass, createClass("verified.Loose", "untrusted-parent"));
        Files.write(parentOnlyClass, createClass("parentonly.Helper", "untrusted-parent-only"));

        try (URLClassLoader parent = new URLClassLoader(
                new URL[]{parentClasses.toUri().toURL()},
                VerifiedPluginPackageTest.class.getClassLoader()
        ); PluginClassLoader loader = new PluginClassLoader(parent, pluginPackage)) {
            Class<?> packageClass = loader.loadClass("verified.Loose");
            assertEquals(loader, packageClass.getClassLoader());
            assertEquals("trusted-loose", invokeMarker(packageClass));
            assertThrows(ClassNotFoundException.class, () -> loader.loadClass("parentonly.Helper"));
            assertEquals(VerifiedPluginPackageTest.class, loader.loadClass(VerifiedPluginPackageTest.class.getName()));
            assertEquals(String.class, loader.loadClass("java.lang.String"));
        }
    }

    /// Loads actual named-module classes from the platform loader while keeping third-party namespaces package-owned.
    ///
    /// @param temporaryDirectory isolated package and parent class paths
    /// @throws Exception if package generation or class loading fails
    @Test
    public void distinguishPlatformSharedAndPackageOwnedClasses(@TempDir Path temporaryDirectory) throws Exception {
        VerifiedPluginPackage pluginPackage = preparePackage(temporaryDirectory);
        try (PluginClassLoader loader = createLoader(pluginPackage)) {
            assertEquals(String.class, loader.loadClass(String.class.getName()));
            assertEquals(org.w3c.dom.Document.class, loader.loadClass(org.w3c.dom.Document.class.getName()));
            assertEquals(org.xml.sax.InputSource.class, loader.loadClass(org.xml.sax.InputSource.class.getName()));
            assertEquals(org.ietf.jgss.GSSName.class, loader.loadClass(org.ietf.jgss.GSSName.class.getName()));
            assertEquals(
                    netscape.javascript.JSObject.class,
                    loader.loadClass(netscape.javascript.JSObject.class.getName())
            );
            assertEquals(
                    org.jackhuang.hmcl.plugin.Plugin.class,
                    loader.loadClass(org.jackhuang.hmcl.plugin.Plugin.class.getName())
            );
            assertEquals(javafx.scene.Node.class, loader.loadClass(javafx.scene.Node.class.getName()));
            assertEquals(
                    org.spongepowered.asm.mixin.Mixin.class,
                    loader.loadClass(org.spongepowered.asm.mixin.Mixin.class.getName())
            );
            assertEquals(
                    org.objectweb.asm.ClassReader.class,
                    loader.loadClass(org.objectweb.asm.ClassReader.class.getName())
            );

            Class<?> packageJnaClass = loader.loadClass("com.sun.jna.PluginOwnedProbe");
            assertEquals(loader, packageJnaClass.getClassLoader());
            assertEquals("package-jna", invokeMarker(packageJnaClass));
            Class<?> packageJavaxClass = loader.loadClass("javax.inject.PluginOwnedProbe");
            assertEquals(loader, packageJavaxClass.getClassLoader());
            assertEquals("package-javax", invokeMarker(packageJavaxClass));
            Class<?> packageHmclClass = loader.loadPluginClass(
                    "org.jackhuang.hmcl.plugin.PackageOwnedEntrypoint"
            );
            assertEquals(loader, packageHmclClass.getClassLoader());
            assertEquals("package-hmcl", invokeMarker(packageHmclClass));

            assertThrows(
                    ClassNotFoundException.class,
                    () -> loader.loadPluginClass(org.jackhuang.hmcl.plugin.Plugin.class.getName())
            );
            assertThrows(ClassNotFoundException.class, () -> loader.loadPluginClass("org.w3c.dom.Document"));
        }
    }

    /// Refuses lazy nested-JAR loading after an inventoried file is replaced.
    ///
    /// @param temporaryDirectory isolated package cache
    /// @throws Exception if package generation or tampering fails
    @Test
    public void rejectReplacedVerifiedJarBeforeLazyClassLoad(@TempDir Path temporaryDirectory) throws Exception {
        VerifiedPluginPackage pluginPackage = preparePackage(temporaryDirectory);
        Path nestedJar = pluginPackage.getJarFiles().get(0);
        Files.write(nestedJar, createJar("tampered.Nested", createClass("tampered.Nested", "tampered")));

        try (PluginClassLoader loader = createLoader(pluginPackage)) {
            assertThrows(ClassNotFoundException.class, () -> loader.loadPluginClass("verified.Nested"));
        }
        assertThrows(IOException.class, pluginPackage::verifyIntegrity);
    }

    /// Keeps captured loose and nested resources independent from later package-cache changes.
    ///
    /// @param temporaryDirectory isolated package cache
    /// @throws Exception if package generation, capture, or resource reading fails
    @Test
    public void retainCapturedResourceBytesAfterDiskChanges(@TempDir Path temporaryDirectory) throws Exception {
        VerifiedPluginPackage pluginPackage = preparePackage(temporaryDirectory);
        try (PluginClassLoader loader = createLoader(pluginPackage)) {
            URL looseResource = Objects.requireNonNull(loader.findResource("verified/loose-resource.txt"));
            URL nestedResource = Objects.requireNonNull(loader.findResource("verified/nested-resource.txt"));

            Files.writeString(
                    pluginPackage.getDirectory().resolve("verified/loose-resource.txt"),
                    "tampered-loose",
                    StandardCharsets.UTF_8
            );
            Files.write(
                    pluginPackage.getJarFiles().get(0),
                    createJarWithResource(
                            "verified.Nested",
                            createClass("verified.Nested", "tampered-nested"),
                            "verified/nested-resource.txt",
                            "tampered-resource".getBytes(StandardCharsets.UTF_8)
                    )
            );
            Files.write(
                    pluginPackage.getDirectory().resolve("libs/b-plugin.jar"),
                    createJar("verified.SecondNested", createClass("verified.SecondNested", "tampered-second"))
            );

            assertEquals("trusted-loose-resource", readUtf8(looseResource));
            assertEquals("trusted-nested-resource", readUtf8(nestedResource));
            assertEquals("trusted-second-nested", invokeMarker(loader.loadPluginClass("verified.SecondNested")));
        }
    }

    /// Does not cache an unverified nested-JAR class after a failed load attempt.
    ///
    /// @param temporaryDirectory isolated package cache
    /// @throws Exception if package generation, replacement, restoration, or class loading fails
    @Test
    public void retryNestedJarClassAfterVerifiedBytesAreRestored(@TempDir Path temporaryDirectory) throws Exception {
        VerifiedPluginPackage pluginPackage = preparePackage(temporaryDirectory);
        Path nestedJar = pluginPackage.getJarFiles().get(0);
        byte @Unmodifiable [] trustedJar = Files.readAllBytes(nestedJar);
        Files.write(
                nestedJar,
                createJar("verified.Nested", createClass("verified.Nested", "tampered-nested"))
        );

        try (PluginClassLoader loader = createLoader(pluginPackage)) {
            assertThrows(ClassNotFoundException.class, () -> loader.loadPluginClass("verified.Nested"));
            Files.write(nestedJar, trustedJar);
            assertEquals("trusted-nested", invokeMarker(loader.loadPluginClass("verified.Nested")));
        }
    }

    /// Does not cache an unverified loose class after a failed load attempt.
    ///
    /// @param temporaryDirectory isolated package cache
    /// @throws Exception if package generation, replacement, restoration, or class loading fails
    @Test
    public void retryLooseClassAfterVerifiedBytesAreRestored(@TempDir Path temporaryDirectory) throws Exception {
        VerifiedPluginPackage pluginPackage = preparePackage(temporaryDirectory);
        Path looseClass = pluginPackage.getDirectory().resolve("verified/Loose.class");
        byte @Unmodifiable [] trustedClass = Files.readAllBytes(looseClass);
        Files.write(looseClass, createClass("verified.Loose", "tampered-loose"));

        try (PluginClassLoader loader = createLoader(pluginPackage)) {
            assertThrows(ClassNotFoundException.class, () -> loader.loadPluginClass("verified.Loose"));
            Files.write(looseClass, trustedClass);
            assertEquals("trusted-loose", invokeMarker(loader.loadPluginClass("verified.Loose")));
        }
    }

    /// Returns loose and nested-JAR resources in verified class-path order using byte snapshots.
    ///
    /// @param temporaryDirectory isolated package cache
    /// @throws Exception if package generation or resource lookup fails
    @Test
    public void findAllResourcesInVerifiedClassPathOrder(@TempDir Path temporaryDirectory) throws Exception {
        VerifiedPluginPackage pluginPackage = preparePackage(temporaryDirectory);
        try (PluginClassLoader loader = createLoader(pluginPackage)) {
            Enumeration<URL> resources = loader.findResources("verified/shared-resource.txt");
            assertEquals("trusted-loose-shared", readUtf8(resources.nextElement()));
            assertEquals("trusted-jar-a-shared", readUtf8(resources.nextElement()));
            assertEquals("trusted-jar-b-shared", readUtf8(resources.nextElement()));
            assertFalse(resources.hasMoreElements());
        }
    }

    /// Rejects unsafe manifest paths before resolving package files.
    ///
    /// @param temporaryDirectory isolated package cache
    /// @throws Exception if package generation fails
    @Test
    public void rejectUnsafeRelativePaths(@TempDir Path temporaryDirectory) throws Exception {
        VerifiedPluginPackage pluginPackage = preparePackage(temporaryDirectory);
        @Unmodifiable List<String> unsafePaths = List.of(
                "../escape.class",
                "/absolute.class",
                "folder\\entry.class",
                "C:drive-relative.class",
                "folder//entry.class",
                "folder/./entry.class",
                "folder/../entry.class",
                ""
        );

        for (String unsafePath : unsafePaths) {
            assertThrows(IOException.class, () -> pluginPackage.resolveVerifiedFile(unsafePath), unsafePath);
        }
    }

    /// Rejects a highly compressed nested-JAR entry before retaining an unbounded byte array.
    ///
    /// @param temporaryDirectory isolated package cache
    /// @throws Exception if package generation or verification fails ucepectedly
    @Test
    public void rejectOversizedNestedJarEntry(@TempDir Path temporaryDirectory) throws Exception {
        byte @Unmodifiable [] nestedJar = createJarWithRepeatedResource(
                "oversized/resource.bin",
                VerifiedPluginPackage.MAX_NESTED_ENTRY_BYTES + 1
        );
        VerifiedPluginPackage pluginPackage = preparePackageWithNestedJar(temporaryDirectory, nestedJar);

        assertThrows(IOException.class, () -> pluginPackage.readResourceBytes("oversized/resource.bin"));
    }

    /// Rejects a nested-JAR entry flood before an absent-resource scan becomes unbounded.
    ///
    /// @param temporaryDirectory isolated package cache
    /// @throws Exception if package generation or verification fails ucepectedly
    @Test
    public void rejectNestedJarEntryFlood(@TempDir Path temporaryDirectory) throws Exception {
        byte @Unmodifiable [] nestedJar = createJarWithEntries(
                Math.toIntExact(VerifiedPluginPackage.MAX_NESTED_JAR_ENTRIES + 1)
        );
        VerifiedPluginPackage pluginPackage = preparePackageWithNestedJar(temporaryDirectory, nestedJar);

        assertThrows(IOException.class, () -> pluginPackage.readResourceBytes("missing/resource.bin"));
    }

    /// Prepares one package containing an inventoried loose class and nested-JAR class.
    ///
    /// @param temporaryDirectory isolated package cache
    /// @return verified extracted package
    /// @throws IOException if package generation or extraction fails
    private static VerifiedPluginPackage preparePackage(Path temporaryDirectory) throws IOException {
        String pluginId = "dev.hmclce.test.verified-loader";
        String version = "1.0.0";
        Path nplFile = temporaryDirectory.resolve("verified-loader.npl");
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(nplFile))) {
            writeZipEntry(output, "plugin.json", "{}".getBytes(StandardCharsets.UTF_8));
            writeZipEntry(output, "verified/Loose.class", createClass("verified.Loose", "trusted-loose"));
            writeZipEntry(
                    output,
                    "com/sun/jna/PluginOwnedProbe.class",
                    createClass("com.sun.jna.PluginOwnedProbe", "package-jna")
            );
            writeZipEntry(
                    output,
                    "javax/inject/PluginOwnedProbe.class",
                    createClass("javax.inject.PluginOwnedProbe", "package-javax")
            );
            writeZipEntry(
                    output,
                    "org/jackhuang/hmcl/plugin/PackageOwnedEntrypoint.class",
                    createClass("org.jackhuang.hmcl.plugin.PackageOwnedEntrypoint", "package-hmcl")
            );
            writeZipEntry(
                    output,
                    "verified/loose-resource.txt",
                    "trusted-loose-resource".getBytes(StandardCharsets.UTF_8)
            );
            writeZipEntry(
                    output,
                    "verified/shared-resource.txt",
                    "trusted-loose-shared".getBytes(StandardCharsets.UTF_8)
            );
            writeZipEntry(
                    output,
                    "libs/a-plugin.jar",
                    createJarWithResources(
                            "verified.Nested",
                            createClass("verified.Nested", "trusted-nested"),
                            "verified/nested-resource.txt",
                            "trusted-nested-resource".getBytes(StandardCharsets.UTF_8),
                            "verified/shared-resource.txt",
                            "trusted-jar-a-shared".getBytes(StandardCharsets.UTF_8)
                    )
            );
            writeZipEntry(
                    output,
                    "libs/b-plugin.jar",
                    createJarWithResource(
                            "verified.SecondNested",
                            createClass("verified.SecondNested", "trusted-second-nested"),
                            "verified/shared-resource.txt",
                            "trusted-jar-b-shared".getBytes(StandardCharsets.UTF_8)
                    )
            );
        }
        PluginArtifactIdentity identity = new PluginArtifactIdentity(
                pluginId,
                version,
                PluginPackageVersions.calculateSha256(nplFile)
        );
        return PluginPackageVersions.prepareVerifiedLifecyclePackage(
                nplFile,
                temporaryDirectory.resolve("plugin-data"),
                identity
        );
    }

    /// Prepares one package containing exactly one supplied nested JAR.
    ///
    /// @param temporaryDirectory isolated package cache
    /// @param nestedJar complete nested-JAR bytes
    /// @return verified extracted package
    /// @throws IOException if package generation or extraction fails
    private static VerifiedPluginPackage preparePackageWithNestedJar(
            Path temporaryDirectory,
            byte @Unmodifiable [] nestedJar
    ) throws IOException {
        String pluginId = "dev.hmclce.test.verified-loader-bounds";
        String version = "1.0.0";
        Path nplFile = temporaryDirectory.resolve("verified-loader-bounds.npl");
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(nplFile))) {
            writeZipEntry(output, "plugin.json", "{}".getBytes(StandardCharsets.UTF_8));
            writeZipEntry(output, "libs/plugin.jar", nestedJar);
        }
        PluginArtifactIdentity identity = new PluginArtifactIdentity(
                pluginId,
                version,
                PluginPackageVersions.calculateSha256(nplFile)
        );
        return PluginPackageVersions.prepareVerifiedLifecyclePackage(
                nplFile,
                temporaryDirectory.resolve("plugin-data"),
                identity
        );
    }

    /// Creates a production class loader backed only by verified package byte reads.
    ///
    /// @param pluginPackage verified package inventory
    /// @return verified-only class loader
    private static PluginClassLoader createLoader(VerifiedPluginPackage pluginPackage) {
        return new PluginClassLoader(VerifiedPluginPackageTest.class.getClassLoader(), pluginPackage);
    }

    /// Generates one public class with a default constructor and static marker method.
    ///
    /// @param binaryName binary class name
    /// @param marker marker returned by the generated class
    /// @return JVM class bytes
    private static byte @Unmodifiable [] createClass(String binaryName, String marker) {
        String internalName = binaryName.replace('.', '/');
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(1, 1);
        constructor.visitEnd();
        MethodVisitor markerMethod = writer.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "marker",
                "()Ljava/lang/String;",
                null,
                null
        );
        markerMethod.visitCode();
        markerMethod.visitLdcInsn(marker);
        markerMethod.visitInsn(Opcodes.ARETURN);
        markerMethod.visitMaxs(1, 0);
        markerMethod.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    /// Invokes the generated static marker method.
    ///
    /// @param generatedClass generated class
    /// @return embedded marker string
    /// @throws ReflectiveOperationException if the generated method cannot be invoked
    private static String invokeMarker(Class<?> generatedClass) throws ReflectiveOperationException {
        return (String) generatedClass.getMethod("marker").invoke(null);
    }

    /// Creates a deterministic nested JAR containing one class entry.
    ///
    /// @param binaryName binary class name
    /// @param bytecode class bytes
    /// @return complete JAR bytes
    /// @throws IOException if JAR generation fails
    private static byte @Unmodifiable [] createJar(
            String binaryName,
            byte @Unmodifiable [] bytecode
    ) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (JarOutputStream output = new JarOutputStream(bytes)) {
            writeJarEntry(output, binaryName.replace('.', '/') + ".class", bytecode);
        }
        return bytes.toByteArray();
    }

    /// Creates a deterministic nested JAR containing one class and one resource.
    ///
    /// @param binaryName binary class name
    /// @param bytecode class bytes
    /// @param resourceName resource entry name
    /// @param resourceBytes resource bytes
    /// @return complete JAR bytes
    /// @throws IOException if JAR generation fails
    private static byte @Unmodifiable [] createJarWithResource(
            String binaryName,
            byte @Unmodifiable [] bytecode,
            String resourceName,
            byte @Unmodifiable [] resourceBytes
    ) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (JarOutputStream output = new JarOutputStream(bytes)) {
            writeJarEntry(output, binaryName.replace('.', '/') + ".class", bytecode);
            writeJarEntry(output, resourceName, resourceBytes);
        }
        return bytes.toByteArray();
    }

    /// Creates a deterministic nested JAR containing one class and two resources.
    ///
    /// @param binaryName binary class name
    /// @param bytecode class bytes
    /// @param firstResourceName first resource entry name
    /// @param firstResourceBytes first resource bytes
    /// @param secondResourceName second resource entry name
    /// @param secondResourceBytes second resource bytes
    /// @return complete JAR bytes
    /// @throws IOException if JAR generation fails
    private static byte @Unmodifiable [] createJarWithResources(
            String binaryName,
            byte @Unmodifiable [] bytecode,
            String firstResourceName,
            byte @Unmodifiable [] firstResourceBytes,
            String secondResourceName,
            byte @Unmodifiable [] secondResourceBytes
    ) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (JarOutputStream output = new JarOutputStream(bytes)) {
            writeJarEntry(output, binaryName.replace('.', '/') + ".class", bytecode);
            writeJarEntry(output, firstResourceName, firstResourceBytes);
            writeJarEntry(output, secondResourceName, secondResourceBytes);
        }
        return bytes.toByteArray();
    }

    /// Creates a compact nested JAR whose single resource expands to the requested size.
    ///
    /// @param resourceName resource entry name
    /// @param resourceBytes uncompressed resource byte count
    /// @return complete compressed JAR bytes
    /// @throws IOException if JAR generation fails
    private static byte @Unmodifiable [] createJarWithRepeatedResource(
            String resourceName,
            long resourceBytes
    ) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (JarOutputStream output = new JarOutputStream(bytes)) {
            JarEntry entry = new JarEntry(resourceName);
            entry.setTime(0);
            output.putNextEntry(entry);
            byte[] buffer = new byte[8192];
            long remaining = resourceBytes;
            while (remaining > 0) {
                int chunk = (int) Math.min(buffer.length, remaining);
                output.write(buffer, 0, chunk);
                remaining -= chunk;
            }
            output.closeEntry();
        }
        return bytes.toByteArray();
    }

    /// Creates a nested JAR containing the requested number of empty unique entries.
    ///
    /// @param entryCount number of entries to create
    /// @return complete JAR bytes
    /// @throws IOException if JAR generation fails
    private static byte @Unmodifiable [] createJarWithEntries(int entryCount) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (JarOutputStream output = new JarOutputStream(bytes)) {
            for (int index = 0; index < entryCount; index++) {
                JarEntry entry = new JarEntry("entries/entry-" + index);
                entry.setTime(0);
                output.putNextEntry(entry);
                output.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    /// Writes one deterministic nested-JAR entry.
    ///
    /// @param output JAR output stream
    /// @param name entry name
    /// @param bytes entry bytes
    /// @throws IOException if writing fails
    private static void writeJarEntry(
            JarOutputStream output,
            String name,
            byte @Unmodifiable [] bytes
    ) throws IOException {
        JarEntry entry = new JarEntry(name);
        entry.setTime(0);
        output.putNextEntry(entry);
        output.write(bytes);
        output.closeEntry();
    }

    /// Reads one UTF-8 resource URL completely.
    ///
    /// @param url resource URL
    /// @return decoded UTF-8 contents
    /// @throws IOException if the URL cannot be read
    private static String readUtf8(URL url) throws IOException {
        try (var input = url.openStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /// Writes one deterministic NPL entry.
    ///
    /// @param output NPL output stream
    /// @param name entry name
    /// @param bytes entry bytes
    /// @throws IOException if writing fails
    private static void writeZipEntry(
            ZipOutputStream output,
            String name,
            byte @Unmodifiable [] bytes
    ) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(0);
        output.putNextEntry(entry);
        output.write(bytes);
        output.closeEntry();
    }
}
