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
package org.jackhuang.hmcl.plugin.trust;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies that every in-memory protocol identity preserves the Node.js safe-integer contract.
@NotNullByDefault
public final class PluginProtocolNumberTest {
    /// Rejects oversized repository IDs in weekly proofs and online repository state.
    @Test
    public void rejectsUnsafeRepositoryIds() {
        long unsafe = CanonicalJson.MAX_SAFE_INTEGER + 1;
        assertThrows(IllegalArgumentException.class, () -> new PluginRepositoryAttestation(
                "github.com/example/plugin",
                unsafe,
                "main",
                List.of("HMCLCE"),
                List.of("dev.example.plugin"),
                "approved",
                Instant.parse("2026-08-14T00:00:00Z"),
                Instant.parse("2026-08-21T00:00:00Z"),
                "2026-08",
                "0123456789abcdef0123456789abcdef01234567",
                "verification-1",
                "ed25519:" + "1".repeat(64)
        ));
        assertThrows(IllegalArgumentException.class, () -> new PluginTrustStatusSnapshot.RepositoryStatus(
                unsafe,
                "github.com/example/plugin",
                "verification-1",
                "approved",
                Instant.parse("2026-08-14T00:00:00Z"),
                Instant.parse("2026-08-21T00:00:00Z"),
                List.of("dev.example.plugin")
        ));
    }

    /// Rejects oversized status versions independently of the signed JSON parser.
    @Test
    public void rejectsUnsafeStatusVersion() {
        assertThrows(IllegalArgumentException.class, () -> new PluginTrustStatusSnapshot(
                CanonicalJson.MAX_SAFE_INTEGER + 1,
                Instant.parse("2026-08-14T00:00:00Z"),
                Instant.parse("2026-08-15T00:00:00Z"),
                "2026-08",
                List.of(),
                List.of(),
                Set.of(),
                "ed25519:" + "1".repeat(64)
        ));
    }

    /// Rejects oversized package sizes and repository IDs in verified and persisted certification identities.
    @Test
    public void rejectsUnsafeCertificationNumbers() {
        long unsafe = CanonicalJson.MAX_SAFE_INTEGER + 1;
        String sha256 = "a".repeat(64);
        String repositoryKey = "ed25519:" + "1".repeat(64);
        String artifactKey = "ed25519:" + "2".repeat(64);
        assertThrows(IllegalArgumentException.class, () -> new PluginVerifiedCertification(
                "dev.example.plugin",
                "1.0.0",
                sha256,
                unsafe,
                1701,
                "github.com/example/plugin",
                repositoryKey,
                artifactKey,
                "verification-1"
        ));
        assertThrows(IllegalArgumentException.class, () -> new PluginCertificationReceipt(
                "dev.example.plugin",
                "1.0.0",
                sha256,
                42,
                unsafe,
                "github.com/example/plugin",
                repositoryKey,
                artifactKey,
                "verification-1",
                "{\"signed\":{},\"signatures\":[]}",
                "{\"signed\":{},\"signatures\":[]}"
        ));
    }
}
