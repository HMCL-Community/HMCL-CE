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
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
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
}
