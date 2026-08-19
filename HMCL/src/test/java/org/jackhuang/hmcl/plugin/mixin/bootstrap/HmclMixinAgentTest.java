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
package org.jackhuang.hmcl.plugin.mixin.bootstrap;

import org.jackhuang.hmcl.plugin.PluginArtifactIdentity;
import org.jackhuang.hmcl.plugin.PluginMutationLock;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies fail-closed premain behavior that keeps the launcher process alive.
@NotNullByDefault
public final class HmclMixinAgentTest {
    /// Clears exact authorization and disables further Mixin relaunch after initialization failure.
    @Test
    public void failClosedAfterInitializationFailure() {
        PluginArtifactIdentity identity = new PluginArtifactIdentity(
                "dev.hmclce.test.agent-failure",
                "1.0.0",
                "a".repeat(64)
        );
        PluginAgentSnapshot.publish(List.of(PluginAgentSnapshot.registration(
                identity,
                PluginAgentSnapshot.calculateMixinConfigurationDigest(List.of("failure.json")),
                List.of()
        )));
        System.setProperty(HmclMixinBootstrap.ACTIVE_PROPERTY, identity.getPluginId());
        System.clearProperty(HmclMixinBootstrap.DISABLE_PROPERTY);
        try {
            assertDoesNotThrow(() -> HmclMixinAgent.handleInitializationFailure(new IOException("expected")));

            assertTrue(PluginAgentSnapshot.current().getActiveArtifacts().isEmpty());
            assertEquals("true", System.getProperty(HmclMixinBootstrap.DISABLE_PROPERTY));
            assertNull(System.getProperty(HmclMixinBootstrap.ACTIVE_PROPERTY));
        } finally {
            clearProperties();
            PluginAgentSnapshot.clear();
        }
    }

    /// Returns before touching instrumentation when safe mode disables plugin Mixins.
    @Test
    public void skipInstrumentationInSafeMode() {
        Instrumentation instrumentation = (Instrumentation) Proxy.newProxyInstance(
                HmclMixinAgentTest.class.getClassLoader(),
                new Class<?>[]{Instrumentation.class},
                (proxy, method, arguments) -> {
                    throw new AssertionError("Instrumentation must not be used in safe mode: " + method.getName());
                }
        );
        System.setProperty(HmclMixinBootstrap.DISABLE_PROPERTY, "true");
        System.setProperty(HmclMixinBootstrap.ACTIVE_PROPERTY, "forged");
        try {
            assertDoesNotThrow(() -> HmclMixinAgent.premain(null, instrumentation));

            assertTrue(PluginAgentSnapshot.current().getActiveArtifacts().isEmpty());
            assertNull(System.getProperty(HmclMixinBootstrap.ACTIVE_PROPERTY));
        } finally {
            clearProperties();
            PluginAgentSnapshot.clear();
        }
    }

    /// Keeps package, state, and permission mutations outside the complete Agent initialization window.
    ///
    /// @param temporaryDirectory isolated launcher home
    /// @throws Exception if concurrency coordination or lock acquisition fails
    @Test
    public void holdMutationLockUntilAgentInitializationCompletes(@TempDir Path temporaryDirectory) throws Exception {
        CountDownLatch initializationEntered = new CountDownLatch(1);
        CountDownLatch releaseInitialization = new CountDownLatch(1);
        CountDownLatch mutationAttempted = new CountDownLatch(1);
        CountDownLatch mutationEntered = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> initialization = executor.submit(() -> {
                HmclMixinAgent.runInitializationUnderMutationLock(temporaryDirectory, () -> {
                    initializationEntered.countDown();
                    await(releaseInitialization);
                });
                return null;
            });
            initializationEntered.await();

            Future<?> mutation = executor.submit(() -> {
                mutationAttempted.countDown();
                new PluginMutationLock(temporaryDirectory).run(mutationEntered::countDown);
                return null;
            });
            mutationAttempted.await();

            assertEquals(1L, mutationEntered.getCount());
            releaseInitialization.countDown();
            initialization.get();
            mutation.get();
            assertEquals(0L, mutationEntered.getCount());
        } finally {
            executor.shutdownNow();
        }
    }

    /// Produces the same content digest from captured bytes and the exact open JAR handle used by premain.
    ///
    /// @param temporaryDirectory isolated JAR directory
    /// @throws Exception if archive creation or hashing fails
    @Test
    public void matchCapturedAndOpenJarContentDigest(@TempDir Path temporaryDirectory) throws Exception {
        byte @Unmodifiable [] jarBytes = createJar("verified.txt", "verified");
        Path jarPath = temporaryDirectory.resolve("verified.jar");
        Files.write(jarPath, jarBytes);

        try (JarFile jarFile = new JarFile(jarPath.toFile())) {
            assertEquals(
                    HmclMixinBootstrap.calculateAgentJarDigest(jarBytes),
                    HmclMixinBootstrap.calculateAgentJarDigest(jarFile)
            );
        }
    }

    /// Refuses to append a JAR whose bytes changed after the Agent configuration captured its digest.
    ///
    /// @param temporaryDirectory isolated JAR directory
    /// @throws Exception if archive creation or mutation fails
    @Test
    public void rejectChangedJarBeforeSystemClassPathAppend(@TempDir Path temporaryDirectory) throws Exception {
        byte @Unmodifiable [] originalJar = createJar("verified.txt", "verified");
        Path jarPath = temporaryDirectory.resolve("plugin.jar");
        Files.write(jarPath, originalJar);
        HmclMixinBootstrap.AgentClassPathEntry entry = new HmclMixinBootstrap.AgentClassPathEntry(
                jarPath,
                HmclMixinBootstrap.calculateAgentJarDigest(originalJar)
        );
        Files.write(jarPath, createJar("replaced.txt", "replaced"));
        AtomicInteger appendCalls = new AtomicInteger();
        Instrumentation instrumentation = instrumentationRecordingAppends(appendCalls);

        assertThrows(
                IOException.class,
                () -> HmclMixinAgent.appendPluginClassPath(List.of(entry), instrumentation)
        );
        assertEquals(0, appendCalls.get());
    }

    /// Creates an Instrumentation proxy that records only system class-path append calls.
    ///
    /// @param appendCalls append invocation counter
    /// @return instrumentation proxy
    private static Instrumentation instrumentationRecordingAppends(AtomicInteger appendCalls) {
        return (Instrumentation) Proxy.newProxyInstance(
                HmclMixinAgentTest.class.getClassLoader(),
                new Class<?>[]{Instrumentation.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("appendToSystemClassLoaderSearch")) {
                        appendCalls.incrementAndGet();
                        return null;
                    }
                    throw new AssertionError("Ucepected Instrumentation call: " + method.getName());
                }
        );
    }

    /// Creates one deterministic JAR entry fixture.
    ///
    /// @param entryName archive entry name
    /// @param contents UTF-8 entry text
    /// @return complete JAR bytes
    /// @throws IOException if archive generation fails
    private static byte @Unmodifiable [] createJar(String entryName, String contents) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (JarOutputStream output = new JarOutputStream(bytes)) {
            JarEntry entry = new JarEntry(entryName);
            entry.setTime(0);
            output.putNextEntry(entry);
            output.write(contents.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
        return bytes.toByteArray();
    }

    /// Waits for a coordination latch while preserving interruption as an I/O failure.
    ///
    /// @param latch latch to await
    /// @throws IOException if the current thread is interrupted
    private static void await(CountDownLatch latch) throws IOException {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while coordinating the Agent lock test", exception);
        }
    }

    /// Clears every Mixin bootstrap diagnostic property changed by these tests.
    private static void clearProperties() {
        System.clearProperty(HmclMixinBootstrap.ACTIVE_PROPERTY);
        System.clearProperty(HmclMixinBootstrap.AGENT_ACTIVE_PROPERTY);
        System.clearProperty(HmclMixinBootstrap.DISABLE_PROPERTY);
    }
}
