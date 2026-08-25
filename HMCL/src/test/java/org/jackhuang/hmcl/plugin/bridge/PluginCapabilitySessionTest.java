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
package org.jackhuang.hmcl.plugin.bridge;

import org.jackhuang.hmcl.plugin.PluginArtifactIdentity;
import org.jackhuang.hmcl.plugin.PluginPermission;
import org.jackhuang.hmcl.plugin.runtime.PluginExecutionMode;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies linearizable capability issuance suspension, rotation, and permanent closure.
@NotNullByDefault
public final class PluginCapabilitySessionTest {
    /// Exact test payload identity.
    private static final PluginArtifactIdentity IDENTITY = new PluginArtifactIdentity(
            "dev.hmclce.test.capability-session", "1.0.0", "a".repeat(64));

    /// Stable test clock instant.
    private static final Instant NOW = Instant.parse("2026-08-25T00:00:00Z");

    /// Makes close wait for an issue already inside the session, then proves close revokes its completed token.
    ///
    /// @throws Exception if concurrent test coordination times out
    @Test
    public void closeLinearizesAgainstConcurrentIssue() throws Exception {
        BlockingSecureRandom random = new BlockingSecureRandom();
        PluginPermissionAuthority authority = new PluginPermissionAuthority(
                Clock.fixed(NOW, ZoneOffset.UTC), random);
        PluginCapabilitySession session = session(
                authority, new AtomicReference<>(Set.of(PluginPermission.LAUNCHER_CORE)));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<PluginCapabilityToken> issuing = executor.submit(session::issue);
            assertTrue(random.entered.await(5, TimeUnit.SECONDS));
            Future<?> closing = executor.submit(session::close);

            assertFalse(closing.isDone());
            random.release.countDown();
            PluginCapabilityToken token = issuing.get(5, TimeUnit.SECONDS);
            closing.get(5, TimeUnit.SECONDS);

            assertDenied(authority, token);
            assertThrows(IllegalStateException.class, session::issue);
            assertThrows(IllegalStateException.class, session::resume);
        } finally {
            random.release.countDown();
            executor.shutdownNow();
        }
    }

    /// Rotates generations across suspension, re-enable, and permission changes without reviving old tokens.
    @Test
    public void suspendResumeAndRotateGenerations() {
        PluginPermissionAuthority authority = new PluginPermissionAuthority(
                Clock.fixed(NOW, ZoneOffset.UTC), new SecureRandom());
        AtomicReference<Set<PluginPermission>> grants = new AtomicReference<>(
                Set.of(PluginPermission.LAUNCHER_CORE));
        PluginCapabilitySession session = session(authority, grants);
        PluginCapabilityToken first = session.issue();

        session.suspend();

        assertDenied(authority, first);
        assertThrows(IllegalStateException.class, session::issue);
        session.resume();
        PluginCapabilityToken second = session.issue();
        assertAuthorized(authority, second);
        assertDenied(authority, first);

        grants.set(Set.of());
        session.rotate();
        PluginCapabilityToken reduced = session.issue();

        assertDenied(authority, second);
        assertDenied(authority, reduced);
        session.close();
        assertDenied(authority, reduced);
    }

    /// Keeps two lifecycle sessions for identical artifact bytes in independent revocation families.
    @Test
    public void isolateSessionsForSameArtifact() {
        PluginPermissionAuthority authority = new PluginPermissionAuthority(
                Clock.fixed(NOW, ZoneOffset.UTC), new SecureRandom());
        AtomicReference<Set<PluginPermission>> grants = new AtomicReference<>(
                Set.of(PluginPermission.LAUNCHER_CORE));
        PluginCapabilitySession firstSession = session(authority, grants);
        PluginCapabilitySession secondSession = session(authority, grants);
        PluginCapabilityToken first = firstSession.issue();
        PluginCapabilityToken second = secondSession.issue();

        firstSession.close();

        assertDenied(authority, first);
        assertAuthorized(authority, second);
        assertDoesNotThrow(secondSession::rotate);
        secondSession.close();
    }

    /// Avoids the session-to-permission-lock inversion while a permission mutation rotates the current generation.
    ///
    /// @throws Exception if concurrent test coordination times out
    @RepeatedTest(20)
    public void issueDoesNotDeadlockWithPermissionMutationRotation() throws Exception {
        PluginPermissionAuthority authority = new PluginPermissionAuthority(
                Clock.fixed(NOW, ZoneOffset.UTC), new SecureRandom());
        CoordinatedPermissionProvider permissions = new CoordinatedPermissionProvider();
        PluginCapabilitySession session = authority.openSession(
                IDENTITY,
                PluginExecutionMode.EMBEDDED,
                permissions,
                "runtime.payload",
                Duration.ofMinutes(1)
        );
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> mutation = executor.submit(() -> permissions.mutateAndRotate(session));
            assertTrue(permissions.mutationHeld.await(5, TimeUnit.SECONDS));
            Future<PluginCapabilityToken> issuing = executor.submit(session::issue);

            PluginCapabilityToken token = issuing.get(2, TimeUnit.SECONDS);
            mutation.get(2, TimeUnit.SECONDS);

            assertDenied(authority, token);
        } finally {
            permissions.abort();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    /// Makes closure complete during a permission snapshot and rejects the in-flight issuance afterward.
    ///
    /// @throws Exception if concurrent test coordination times out
    @Test
    public void closeDuringPermissionSnapshotPreventsIssuance() throws Exception {
        assertLifecycleChangeDuringPermissionSnapshot(PluginCapabilitySession::close);
    }

    /// Makes suspension complete during a permission snapshot and rejects the in-flight issuance afterward.
    ///
    /// @throws Exception if concurrent test coordination times out
    @Test
    public void suspendDuringPermissionSnapshotPreventsIssuance() throws Exception {
        assertLifecycleChangeDuringPermissionSnapshot(PluginCapabilitySession::suspend);
    }

    /// Fails closed after bounded retries when every permission snapshot races a generation rotation.
    @Test
    public void failClosedWhenGenerationChangesContinuously() {
        PluginPermissionAuthority authority = new PluginPermissionAuthority(
                Clock.fixed(NOW, ZoneOffset.UTC), new SecureRandom());
        AtomicReference<PluginCapabilitySession> sessionReference = new AtomicReference<>();
        AtomicInteger attempts = new AtomicInteger();
        PluginCapabilitySession session = authority.openSession(
                IDENTITY,
                PluginExecutionMode.EMBEDDED,
                () -> {
                    attempts.incrementAndGet();
                    sessionReference.get().rotate();
                    return Set.of(PluginPermission.LAUNCHER_CORE);
                },
                "runtime.payload",
                Duration.ofMinutes(1)
        );
        sessionReference.set(session);

        IllegalStateException failure = assertThrows(IllegalStateException.class, session::issue);

        assertEquals("Capability session changed continuously during permission snapshot", failure.getMessage());
        assertEquals(8, attempts.get());
        session.close();
    }

    /// Verifies one lifecycle transition linearizes before a blocked permission provider is released.
    ///
    /// @param lifecycleChange close or suspend operation under test
    /// @throws Exception if concurrent test coordination times out
    private static void assertLifecycleChangeDuringPermissionSnapshot(
            java.util.function.Consumer<PluginCapabilitySession> lifecycleChange
    ) throws Exception {
        PluginPermissionAuthority authority = new PluginPermissionAuthority(
                Clock.fixed(NOW, ZoneOffset.UTC), new SecureRandom());
        BlockingPermissionProvider permissions = new BlockingPermissionProvider();
        PluginCapabilitySession session = authority.openSession(
                IDENTITY,
                PluginExecutionMode.EMBEDDED,
                permissions,
                "runtime.payload",
                Duration.ofMinutes(1)
        );
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<PluginCapabilityToken> issuing = executor.submit(session::issue);
            assertTrue(permissions.entered.await(5, TimeUnit.SECONDS));
            Future<?> changing = executor.submit(() -> lifecycleChange.accept(session));

            changing.get(2, TimeUnit.SECONDS);
            permissions.release.countDown();
            ExecutionException failure = assertThrows(
                    ExecutionException.class,
                    () -> issuing.get(2, TimeUnit.SECONDS)
            );
            assertTrue(failure.getCause() instanceof IllegalStateException);
        } finally {
            permissions.release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    /// Creates one active embedded capability session.
    ///
    /// @param authority launcher-owned authority
    /// @param grants mutable effective permission source
    /// @return active session
    private static PluginCapabilitySession session(
            PluginPermissionAuthority authority,
            AtomicReference<Set<PluginPermission>> grants
    ) {
        return authority.openSession(
                IDENTITY,
                PluginExecutionMode.EMBEDDED,
                grants::get,
                "runtime.payload",
                Duration.ofMinutes(1)
        );
    }

    /// Verifies one token still carries launcher-core authority.
    ///
    /// @param authority launcher-owned authority
    /// @param token token under test
    private static void assertAuthorized(
            PluginPermissionAuthority authority,
            PluginCapabilityToken token
    ) {
        assertDoesNotThrow(() -> authority.requirePermission(
                token,
                IDENTITY.getPluginId(),
                IDENTITY,
                PluginExecutionMode.EMBEDDED,
                PluginPermission.LAUNCHER_CORE,
                "runtime.payload"
        ));
    }

    /// Verifies one token cannot invoke launcher-core authority.
    ///
    /// @param authority launcher-owned authority
    /// @param token token under test
    private static void assertDenied(
            PluginPermissionAuthority authority,
            PluginCapabilityToken token
    ) {
        assertThrows(SecurityException.class, () -> authority.requirePermission(
                token,
                IDENTITY.getPluginId(),
                IDENTITY,
                PluginExecutionMode.EMBEDDED,
                PluginPermission.LAUNCHER_CORE,
                "runtime.payload"
        ));
    }

    /// Secure random fixture that pauses identifier generation until the close race is observable.
    @NotNullByDefault
    private static final class BlockingSecureRandom extends SecureRandom {
        /// Signals that issuance entered token identifier generation.
        private final CountDownLatch entered = new CountDownLatch(1);

        /// Releases identifier generation after close begins waiting.
        private final CountDownLatch release = new CountDownLatch(1);

        /// Blocks the first identifier generation and then writes deterministic nonzero bytes.
        ///
        /// @param bytes requested identifier buffer
        @Override
        public void nextBytes(byte[] bytes) {
            entered.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out waiting to release token generation");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Token generation interrupted", exception);
            }
            java.util.Arrays.fill(bytes, (byte) 7);
        }
    }

    /// Permission provider that blocks until a lifecycle transition has completed.
    @NotNullByDefault
    private static final class BlockingPermissionProvider
            implements Supplier<@Unmodifiable Set<PluginPermission>> {
        /// Signals that permission retrieval started outside the session monitor.
        private final CountDownLatch entered = new CountDownLatch(1);

        /// Releases the permission result after the lifecycle transition completes.
        private final CountDownLatch release = new CountDownLatch(1);

        /// Waits for test coordination and returns one launcher-core grant.
        ///
        /// @return immutable permission snapshot
        @Override
        public @Unmodifiable Set<PluginPermission> get() {
            entered.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out waiting to release permission retrieval");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Permission retrieval interrupted", exception);
            }
            return Set.of(PluginPermission.LAUNCHER_CORE);
        }
    }

    /// Simulates permission storage mutation and its generation rotation under an external mutation lock.
    @NotNullByDefault
    private static final class CoordinatedPermissionProvider
            implements Supplier<@Unmodifiable Set<PluginPermission>> {
        /// Simulated permission-store mutation lock.
        private final ReentrantLock mutationLock = new ReentrantLock();

        /// Signals that mutation owns its lock before issuance begins.
        private final CountDownLatch mutationHeld = new CountDownLatch(1);

        /// Signals that issuance reached permission retrieval.
        private final CountDownLatch readAttempted = new CountDownLatch(1);

        /// Allows failed-test cleanup to break the intentionally reproduced lock cycle.
        private final AtomicBoolean aborted = new AtomicBoolean();

        /// Current immutable effective permission snapshot.
        private final AtomicReference<@Unmodifiable Set<PluginPermission>> grants = new AtomicReference<>(
                Set.of(PluginPermission.LAUNCHER_CORE));

        /// Waits for the mutation lock without retaining an uninterruptible deadlock after a failed assertion.
        ///
        /// @return current immutable permission snapshot
        @Override
        public @Unmodifiable Set<PluginPermission> get() {
            readAttempted.countDown();
            while (!aborted.get()) {
                try {
                    if (mutationLock.tryLock(25, TimeUnit.MILLISECONDS)) {
                        try {
                            return grants.get();
                        } finally {
                            mutationLock.unlock();
                        }
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Permission retrieval interrupted", exception);
                }
            }
            throw new IllegalStateException("Permission retrieval aborted after test timeout");
        }

        /// Holds the mutation lock, publishes reduced grants, and rotates the owning session before releasing it.
        ///
        /// @param session Manager-owned session whose permissions changed
        private void mutateAndRotate(PluginCapabilitySession session) {
            mutationLock.lock();
            try {
                mutationHeld.countDown();
                try {
                    if (!readAttempted.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Timed out waiting for permission retrieval");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Permission mutation interrupted", exception);
                }
                grants.set(Set.of());
                session.rotate();
            } finally {
                mutationLock.unlock();
            }
        }

        /// Releases a permission retrieval blocked by the intentionally reproduced old lock order.
        private void abort() {
            aborted.set(true);
        }
    }
}
