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
package org.jackhuang.hmcl.plugin.mixin.bootstrap;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.launch.platform.container.ContainerHandleVirtual;
import org.spongepowered.asm.launch.platform.container.IContainerHandle;
import org.spongepowered.asm.mixin.MixinEnvironment.CompatibilityLevel;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;
import org.spongepowered.asm.mixin.transformer.IMixinTransformerFactory;
import org.spongepowered.asm.service.IClassBytecodeProvider;
import org.spongepowered.asm.service.IClassProvider;
import org.spongepowered.asm.service.IClassTracker;
import org.spongepowered.asm.service.IMixinAuditTrail;
import org.spongepowered.asm.service.ITransformer;
import org.spongepowered.asm.service.ITransformerProvider;
import org.spongepowered.asm.service.MixinServiceAbstract;
import org.spongepowered.asm.transformers.MixinClassReader;

import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/// Connects SpongePowered Mixin to HMCL's system class loader and Java instrumentation agent.
@NotNullByDefault
public final class HmclMixinService extends MixinServiceAbstract
        implements IClassProvider, IClassBytecodeProvider, IClassTracker, ITransformerProvider {
    /// Highest class-file major version accepted by Mixin 0.8.7 metadata checks (Java 21).
    private static final int MIXIN_METADATA_CLASS_MAJOR = 65;

    /// System loader configured by the premain agent before `MixinBootstrap` requests this service.
    private static volatile @Nullable ClassLoader classLoader;

    /// Instrumentation handle used to answer loaded-class queries.
    private static volatile @Nullable Instrumentation instrumentation;

    /// Creates a Mixin host service discovered through `ServiceLoader`.
    public HmclMixinService() {
    }

    /// Configures the system loader and instrumentation handle used by the Mixin service.
    ///
    /// @param loader application system class loader
    /// @param instrumentationHandle active premain instrumentation handle
    public static void configure(ClassLoader loader, Instrumentation instrumentationHandle) {
        classLoader = loader;
        instrumentation = instrumentationHandle;
    }

    /// Creates the transformer factory offered by Mixin for the instrumentation bridge.
    ///
    /// @return active Mixin transformer
    public IMixinTransformer createTransformer() {
        @Nullable IMixinTransformerFactory factory = getInternal(IMixinTransformerFactory.class);
        if (factory == null) {
            throw new IllegalStateException("Mixin did not offer a transformer factory to the HMCL service");
        }
        return factory.createTransformer();
    }

    /// Returns the service display name.
    ///
    /// @return service name
    @Override
    public String getName() {
        return "HMCL Instrumentation";
    }

    /// Returns whether premain configured this service.
    ///
    /// @return whether the service can operate
    @Override
    public boolean isValid() {
        return classLoader != null && instrumentation != null;
    }

    /// Returns the class provider supplied by this service.
    ///
    /// @return this service
    @Override
    public IClassProvider getClassProvider() {
        return this;
    }

    /// Returns the bytecode provider supplied by this service.
    ///
    /// @return this service
    @Override
    public IClassBytecodeProvider getBytecodeProvider() {
        return this;
    }

    /// Returns the empty preceding-transformer provider supplied by this service.
    ///
    /// @return this service
    @Override
    public ITransformerProvider getTransformerProvider() {
        return this;
    }

    /// Returns the instrumentation-backed class tracker.
    ///
    /// @return this service
    @Override
    public IClassTracker getClassTracker() {
        return this;
    }

    /// Returns no audit trail because diagnostics are emitted through HMCL and Mixin logs.
    ///
    /// @return `null`
    @Override
    public @Nullable IMixinAuditTrail getAuditTrail() {
        return null;
    }

    /// Returns no legacy launch platform agents.
    ///
    /// @return empty platform-agent collection
    @Override
    public Collection<String> getPlatformAgents() {
        return List.of();
    }

    /// Returns the virtual HMCL instrumentation container.
    ///
    /// @return primary container
    @Override
    public IContainerHandle getPrimaryContainer() {
        return new ContainerHandleVirtual("HMCL Instrumentation");
    }

    /// Opens a resource through the configured system class loader.
    ///
    /// @param name resource name
    /// @return resource stream or `null`
    @Override
    public @Nullable InputStream getResourceAsStream(String name) {
        return requireClassLoader().getResourceAsStream(name);
    }

    /// Returns the minimum class-file compatibility supported by HMCL plugins.
    ///
    /// @return Java 17 compatibility
    @Override
    public CompatibilityLevel getMinCompatibilityLevel() {
        return CompatibilityLevel.JAVA_17;
    }

    /// Returns the highest compatibility understood by Mixin 0.8.7.
    ///
    /// @return Java 21 compatibility
    @Override
    public CompatibilityLevel getMaxCompatibilityLevel() {
        return CompatibilityLevel.JAVA_21;
    }

    /// Returns class path URLs used for compatibility with Mixin's deprecated provider API.
    ///
    /// @return class path URLs
    @Override
    @Deprecated
    public URL[] getClassPath() {
        @Nullable String rawClassPath = System.getProperty("java.class.path");
        if (rawClassPath == null || rawClassPath.isBlank()) {
            return new URL[0];
        }

        List<URL> urls = new ArrayList<>();
        for (String entry : rawClassPath.split(java.util.regex.Pattern.quote(System.getProperty("path.separator")))) {
            if (entry.isBlank()) {
                continue;
            }
            try {
                urls.add(Path.of(entry).toAbsolutePath().normalize().toUri().toURL());
            } catch (MalformedURLException ignored) {
            }
        }
        return urls.toArray(URL[]::new);
    }

    /// Loads and initializes a class through the configured system loader.
    ///
    /// @param name binary class name
    /// @return loaded class
    /// @throws ClassNotFoundException if unavailable
    @Override
    public Class<?> findClass(String name) throws ClassNotFoundException {
        return findClass(name, true);
    }

    /// Loads a class through the configured system loader.
    ///
    /// @param name binary class name
    /// @param initialize whether to initialize the class
    /// @return loaded class
    /// @throws ClassNotFoundException if unavailable
    @Override
    public Class<?> findClass(String name, boolean initialize) throws ClassNotFoundException {
        return Class.forName(name, initialize, requireClassLoader());
    }

    /// Loads an agent class through the configured system loader.
    ///
    /// @param name binary class name
    /// @param initialize whether to initialize the class
    /// @return loaded class
    /// @throws ClassNotFoundException if unavailable
    @Override
    public Class<?> findAgentClass(String name, boolean initialize) throws ClassNotFoundException {
        return Class.forName(name, initialize, requireClassLoader());
    }

    /// Reads a class into an expanded ASM tree.
    ///
    /// @param name binary class name
    /// @return class tree
    /// @throws ClassNotFoundException if unavailable
    /// @throws IOException if reading fails
    @Override
    public ClassNode getClassNode(String name) throws ClassNotFoundException, IOException {
        return getClassNode(name, true, ClassReader.EXPAND_FRAMES);
    }

    /// Reads a class into an expanded ASM tree.
    ///
    /// HMCL has no transformer chain preceding Mixin, so `runTransformers` does not alter the bytes.
    ///
    /// @param name binary class name
    /// @param runTransformers retained for service compatibility
    /// @return class tree
    /// @throws ClassNotFoundException if unavailable
    /// @throws IOException if reading fails
    @Override
    public ClassNode getClassNode(String name, boolean runTransformers) throws ClassNotFoundException, IOException {
        return getClassNode(name, runTransformers, ClassReader.EXPAND_FRAMES);
    }

    /// Reads a class into an ASM tree using the requested reader flags.
    ///
    /// @param name binary class name
    /// @param runTransformers retained for service compatibility
    /// @param readerFlags ASM reader flags
    /// @return class tree
    /// @throws ClassNotFoundException if unavailable
    /// @throws IOException if reading fails
    @Override
    public ClassNode getClassNode(String name, boolean runTransformers, int readerFlags)
            throws ClassNotFoundException, IOException {
        byte @Nullable [] classBytes = readClassBytes(name.replace('/', '.'));
        if (classBytes == null) {
            throw new ClassNotFoundException(name);
        }

        byte[] metadataBytes = normalizeMetadataClassVersion(classBytes);
        ClassNode classNode = new ClassNode();
        new MixinClassReader(metadataBytes, name).accept(classNode, readerFlags);
        return classNode;
    }

    /// Accepts an invalid-class notification; the system loader owns actual class definition policy.
    ///
    /// @param className binary class name
    @Override
    public void registerInvalidClass(String className) {
    }

    /// Returns whether instrumentation reports that the class is already loaded.
    ///
    /// @param className binary class name
    /// @return whether the class is loaded
    @Override
    public boolean isClassLoaded(String className) {
        for (Class<?> loadedClass : requireInstrumentation().getAllLoadedClasses()) {
            if (className.equals(loadedClass.getName())) {
                return true;
            }
        }
        return false;
    }

    /// Returns no service-specific class restrictions.
    ///
    /// @param className binary class name
    /// @return empty restriction string
    @Override
    public String getClassRestrictions(String className) {
        return "";
    }

    /// Returns no preceding transformers.
    ///
    /// @return empty transformer collection
    @Override
    public Collection<ITransformer> getTransformers() {
        return List.of();
    }

    /// Returns no delegated transformers.
    ///
    /// @return empty transformer collection
    @Override
    public Collection<ITransformer> getDelegatedTransformers() {
        return List.of();
    }

    /// Ignores exclusions because HMCL has no preceding transformer chain.
    ///
    /// @param name transformer name
    @Override
    public void addTransformerExclusion(String name) {
    }

    /// Reads untransformed class bytes through system resources.
    ///
    /// @param className binary class name
    /// @return class bytes or `null`
    /// @throws IOException if reading fails
    private static byte @Nullable [] readClassBytes(String className) throws IOException {
        String resourceName = className.replace('.', '/') + ".class";
        try (InputStream input = requireClassLoader().getResourceAsStream(resourceName)) {
            if (input != null) {
                return input.readAllBytes();
            }
        }
        try (InputStream input = ClassLoader.getSystemResourceAsStream(resourceName)) {
            return input == null ? null : input.readAllBytes();
        }
    }

    /// Caps only the metadata copy of newer JDK classes so Mixin 0.8.7 can inspect them on Java 22+.
    ///
    /// ASM 9.8 parses the real structure; HMCL and plugin target bytecode remains unchanged and targets Java 17.
    ///
    /// @param classBytes original class bytes
    /// @return original bytes or a copied header-normalized metadata view
    private static byte[] normalizeMetadataClassVersion(byte[] classBytes) {
        if (classBytes.length < 8) {
            return classBytes;
        }
        int majorVersion = (Byte.toUnsignedInt(classBytes[6]) << 8) | Byte.toUnsignedInt(classBytes[7]);
        if (majorVersion <= MIXIN_METADATA_CLASS_MAJOR) {
            return classBytes;
        }

        byte[] normalized = classBytes.clone();
        normalized[6] = (byte) (MIXIN_METADATA_CLASS_MAJOR >>> 8);
        normalized[7] = (byte) MIXIN_METADATA_CLASS_MAJOR;
        return normalized;
    }

    /// Returns the configured system class loader.
    ///
    /// @return configured class loader
    private static ClassLoader requireClassLoader() {
        @Nullable ClassLoader loader = classLoader;
        if (loader == null) {
            throw new IllegalStateException("HMCL Mixin service has no configured class loader");
        }
        return loader;
    }

    /// Returns the configured instrumentation handle.
    ///
    /// @return instrumentation handle
    private static Instrumentation requireInstrumentation() {
        @Nullable Instrumentation handle = instrumentation;
        if (handle == null) {
            throw new IllegalStateException("HMCL Mixin service has no instrumentation handle");
        }
        return handle;
    }
}
