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

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies authenticated status refresh, conditional requests, rollback resistance, and offline cache behavior.
@NotNullByDefault
public final class PluginTrustStatusCacheTest {
    /// Stable test clock used by fresh snapshots.
    private static final Instant NOW = Instant.parse("2026-08-14T08:00:00Z");

    /// Exact revoked test checksum.
    private static final String HASH = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    /// Persists a verified response atomically and reuses it after an ETag 304 response.
    @Test
    public void persistsSnapshotAndUsesEtag304(@TempDir Path temporaryDirectory) throws Exception {
        KeyPair signer = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        AtomicReference<byte @Unmodifiable []> response = new AtomicReference<>(snapshot(signer, 4, NOW, false));
        AtomicReference<String> etag = new AtomicReference<>("\"status-4\"");
        AtomicInteger conditionalRequests = new AtomicInteger();
        try (StatusServer server = StatusServer.start(response, etag, conditionalRequests)) {
            PluginTrustVerifier verifier = verifier(signer, server.url(), NOW);
            Path cacheFile = temporaryDirectory.resolve("trust-status.json");
            try (PluginTrustStatusCache cache = new PluginTrustStatusCache(
                    verifier, cacheFile, Clock.fixed(NOW, ZoneOffset.UTC)
            )) {
                cache.refresh();
                byte[] firstCache = Files.readAllBytes(cacheFile);
                cache.refresh();

                assertEquals(4, Objects.requireNonNull(cache.getLatestSnapshot()).version());
                assertEquals(4, Objects.requireNonNull(cache.getFreshSnapshot()).version());
                assertEquals(1, conditionalRequests.get());
                assertArrayEquals(firstCache, Files.readAllBytes(cacheFile));
                try (var children = Files.list(temporaryDirectory)) {
                    assertFalse(children.anyMatch(path -> path.getFileName().toString().contains(".tmp")));
                }
            }
        }
    }

    /// Rejects a 304 after signed expiry while retaining the authenticated snapshot for explicit revocation.
    @Test
    public void rejects304ForExpiredSnapshotButRetainsRevocations(@TempDir Path temporaryDirectory) throws Exception {
        KeyPair signer = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        AdjustableClock clock = new AdjustableClock(NOW);
        AtomicReference<byte @Unmodifiable []> response = new AtomicReference<>(snapshot(signer, 4, NOW, true));
        AtomicReference<String> etag = new AtomicReference<>("\"status-4\"");
        AtomicInteger conditionalRequests = new AtomicInteger();
        Path cacheFile = temporaryDirectory.resolve("trust-status.json");
        try (StatusServer server = StatusServer.start(response, etag, conditionalRequests);
             PluginTrustStatusCache cache = new PluginTrustStatusCache(
                     verifier(signer, server.url(), clock),
                     cacheFile,
                     clock
             )) {
            cache.refresh();
            byte[] accepted = Files.readAllBytes(cacheFile);
            clock.advance(Duration.ofHours(37));

            assertNull(cache.getFreshSnapshot());
            assertThrows(IOException.class, cache::refresh);

            assertEquals(1, conditionalRequests.get());
            assertEquals(4, Objects.requireNonNull(cache.getLatestSnapshot()).version());
            assertTrue(cache.isArtifactRevoked("dev.example.plugin", "1.2.3", HASH));
            assertArrayEquals(accepted, Files.readAllBytes(cacheFile));
        }
    }

    /// Persists a historical repository proof and reuses it without network access while status remains fresh.
    @Test
    public void persistsRepositoryAttestationForFreshOfflineUse(@TempDir Path temporaryDirectory) throws Exception {
        KeyPair statusSigner = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        KeyPair repositorySigner = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String verificationId = "11111111-1111-4111-8111-111111111117";
        AtomicInteger proofRequests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String statusUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/status.json";
        byte @Unmodifiable [] status = snapshot(statusSigner, 4, NOW, false);
        byte @Unmodifiable [] proof = repositoryAttestation(repositorySigner, verificationId)
                .toString().getBytes(StandardCharsets.UTF_8);
        server.createContext("/status.json", exchange -> respond(exchange, status));
        server.createContext("/api/v1/repositories/attestations/" + verificationId, exchange -> {
            proofRequests.incrementAndGet();
            respond(exchange, proof);
        });
        server.start();
        Path cacheFile = temporaryDirectory.resolve("trust-status.json");
        PluginTrustVerifier verifier = verifier(statusSigner, repositorySigner, statusUrl, NOW);
        try {
            try (PluginTrustStatusCache cache = new PluginTrustStatusCache(
                    verifier, cacheFile, Clock.fixed(NOW, ZoneOffset.UTC)
            )) {
                cache.refresh();
                assertEquals(verificationId, cache.resolveRepositoryAttestation(verificationId).verificationId());
            }
        } finally {
            server.stop(0);
        }

        try (PluginTrustStatusCache offline = new PluginTrustStatusCache(
                verifier, cacheFile, Clock.fixed(NOW, ZoneOffset.UTC)
        )) {
            assertNotNull(offline.getFreshSnapshot());
            assertEquals(verificationId, offline.resolveRepositoryAttestation(verificationId).verificationId());
            assertEquals(1, proofRequests.get());
        }
    }

    /// Counts a direct refresh as the latest attempt so a due refresh cannot immediately repeat it.
    @Test
    public void directRefreshUpdatesDueCadence(@TempDir Path temporaryDirectory) throws Exception {
        KeyPair signer = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        AtomicReference<byte @Unmodifiable []> response = new AtomicReference<>(snapshot(signer, 4, NOW, false));
        AtomicReference<String> etag = new AtomicReference<>("\"status-4\"");
        AtomicInteger conditionalRequests = new AtomicInteger();
        try (StatusServer server = StatusServer.start(response, etag, conditionalRequests);
             PluginTrustStatusCache cache = new PluginTrustStatusCache(
                     verifier(signer, server.url(), NOW),
                     temporaryDirectory.resolve("trust-status.json"),
                     Clock.fixed(NOW, ZoneOffset.UTC)
             )) {
            cache.refresh();
            cache.refreshIfDue();
            assertEquals(0, conditionalRequests.get());
        }
    }

    /// Coalesces concurrent due checks so startup and store entry cannot issue duplicate status requests.
    @Test
    public void concurrentDueRefreshesIssueOneRequest(@TempDir Path temporaryDirectory) throws Exception {
        KeyPair signer = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        AtomicInteger requests = new AtomicInteger();
        CountDownLatch requestReceived = new CountDownLatch(1);
        CountDownLatch releaseResponse = new CountDownLatch(1);
        byte @Unmodifiable [] status = snapshot(signer, 4, NOW, false);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/status.json", exchange -> {
            requests.incrementAndGet();
            requestReceived.countDown();
            try {
                if (!releaseResponse.await(5, TimeUnit.SECONDS)) {
                    exchange.sendResponseHeaders(HttpURLConnection.HTTP_UNAVAILABLE, -1);
                    exchange.close();
                    return;
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                exchange.close();
                return;
            }
            respond(exchange, status);
        });
        server.start();
        String statusUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/status.json";
        ExecutorService callers = Executors.newFixedThreadPool(2);
        CountDownLatch callersReady = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (PluginTrustStatusCache cache = new PluginTrustStatusCache(
                verifier(signer, statusUrl, NOW),
                temporaryDirectory.resolve("trust-status.json"),
                Clock.fixed(NOW, ZoneOffset.UTC)
        )) {
            Future<Void> first = callers.submit(() -> {
                callersReady.countDown();
                start.await();
                cache.refreshIfDue();
                return null;
            });
            Future<Void> second = callers.submit(() -> {
                callersReady.countDown();
                start.await();
                cache.refreshIfDue();
                return null;
            });

            assertTrue(callersReady.await(5, TimeUnit.SECONDS));
            start.countDown();
            assertTrue(requestReceived.await(5, TimeUnit.SECONDS));
            releaseResponse.countDown();
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);

            assertEquals(1, requests.get());
            assertEquals(4, Objects.requireNonNull(cache.getLatestSnapshot()).version());
        } finally {
            releaseResponse.countDown();
            callers.shutdownNow();
            server.stop(0);
        }
    }

    /// Retries transient failures on bounded five-, thirty-, and sixty-minute backoffs before restoring six hours.
    @Test
    public void transientFailuresUseShortBoundedBackoff(@TempDir Path temporaryDirectory) throws Exception {
        KeyPair signer = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        AdjustableClock clock = new AdjustableClock(NOW);
        AtomicInteger requests = new AtomicInteger();
        AtomicReference<byte @Unmodifiable []> response = new AtomicReference<>(snapshot(signer, 4, NOW, false));
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/status.json", exchange -> {
            if (requests.incrementAndGet() <= 3) {
                exchange.sendResponseHeaders(HttpURLConnection.HTTP_UNAVAILABLE, -1);
                exchange.close();
                return;
            }
            respond(exchange, response.get());
        });
        server.start();
        String statusUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/status.json";
        try (PluginTrustStatusCache cache = new PluginTrustStatusCache(
                verifier(signer, statusUrl, clock),
                temporaryDirectory.resolve("trust-status.json"),
                clock
        )) {
            assertThrows(IOException.class, cache::refreshIfDue);
            clock.advance(Duration.ofMinutes(4));
            cache.refreshIfDue();
            assertEquals(1, requests.get());

            clock.advance(Duration.ofMinutes(2));
            assertThrows(IOException.class, cache::refreshIfDue);
            clock.advance(Duration.ofMinutes(26));
            cache.refreshIfDue();
            assertEquals(2, requests.get());

            clock.advance(Duration.ofMinutes(8));
            assertThrows(IOException.class, cache::refreshIfDue);
            clock.advance(Duration.ofMinutes(53));
            cache.refreshIfDue();
            assertEquals(3, requests.get());

            clock.advance(Duration.ofMinutes(14));
            cache.refreshIfDue();
            assertEquals(4, requests.get());
            assertNotNull(cache.getFreshSnapshot());

            clock.advance(Duration.ofHours(5).plusMinutes(59));
            cache.refreshIfDue();
            assertEquals(4, requests.get());
            response.set(snapshot(signer, 5, clock.instant(), false));
            clock.advance(Duration.ofMinutes(2));
            cache.refreshIfDue();
            assertEquals(5, requests.get());
        } finally {
            server.stop(0);
        }
    }

    /// Rejects lower versions and preserves both memory and disk state after a failed refresh.
    @Test
    public void rejectsRollbackWithoutOverwritingLastValidCache(@TempDir Path temporaryDirectory) throws Exception {
        KeyPair signer = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        AtomicReference<byte @Unmodifiable []> response = new AtomicReference<>(snapshot(signer, 4, NOW, false));
        AtomicReference<String> etag = new AtomicReference<>("\"status-4\"");
        try (StatusServer server = StatusServer.start(response, etag, new AtomicInteger())) {
            PluginTrustVerifier verifier = verifier(signer, server.url(), NOW);
            Path cacheFile = temporaryDirectory.resolve("trust-status.json");
            try (PluginTrustStatusCache cache = new PluginTrustStatusCache(
                    verifier, cacheFile, Clock.fixed(NOW, ZoneOffset.UTC)
            )) {
                cache.refresh();
                byte[] accepted = Files.readAllBytes(cacheFile);
                response.set(snapshot(signer, 3, NOW.minusSeconds(60), false));
                etag.set("\"status-3\"");

                assertThrows(IOException.class, cache::refresh);
                assertEquals(4, Objects.requireNonNull(cache.getLatestSnapshot()).version());
                assertArrayEquals(accepted, Files.readAllBytes(cacheFile));
            }
        }
    }

    /// Rejects a higher snapshot that erases any previously authenticated irreversible revocation.
    @Test
    public void rejectsRevocationRemovalWithoutOverwritingCache(@TempDir Path temporaryDirectory) throws Exception {
        KeyPair signer = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String revokedKeyId = "ed25519:" + "b".repeat(64);
        JsonObject acceptedEnvelope = statusEnvelope(signer, 4, NOW.minusSeconds(60), true);
        acceptedEnvelope.getAsJsonObject("signed").getAsJsonArray("revokedKeyIds").add(revokedKeyId);
        acceptedEnvelope.getAsJsonObject("signed").getAsJsonArray("repositories")
                .add(repositoryStatus("revoked"));
        resign(acceptedEnvelope, signer.getPrivate(), keyId(signer));
        AtomicReference<byte @Unmodifiable []> response = new AtomicReference<>(
                acceptedEnvelope.toString().getBytes(StandardCharsets.UTF_8)
        );
        AtomicReference<String> etag = new AtomicReference<>("\"status-4\"");
        try (StatusServer server = StatusServer.start(response, etag, new AtomicInteger())) {
            Path cacheFile = temporaryDirectory.resolve("trust-status.json");
            try (PluginTrustStatusCache cache = new PluginTrustStatusCache(
                    verifier(signer, server.url(), NOW), cacheFile, Clock.fixed(NOW, ZoneOffset.UTC)
            )) {
                cache.refresh();
                byte[] accepted = Files.readAllBytes(cacheFile);
                JsonObject erased = statusEnvelope(signer, 5, NOW, false);
                response.set(erased.toString().getBytes(StandardCharsets.UTF_8));
                etag.set("\"status-5\"");

                assertThrows(IOException.class, cache::refresh);
                PluginTrustStatusSnapshot retained = Objects.requireNonNull(cache.getLatestSnapshot());
                assertEquals(4, retained.version());
                assertTrue(retained.isKeyRevoked(revokedKeyId));
                assertNotNull(retained.findRevokedArtifact("dev.example.plugin", "1.2.3", HASH));
                assertEquals("revoked", Objects.requireNonNull(retained.findRepository(1701)).status());
                assertArrayEquals(accepted, Files.readAllBytes(cacheFile));
            }
        }
    }

    /// Rejects a snapshot that attempts to establish its own status-signing key revocation.
    @Test
    public void rejectsSelfRevokingTrustStatusSigner(@TempDir Path temporaryDirectory) throws Exception {
        KeyPair signer = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        AtomicReference<byte @Unmodifiable []> response = new AtomicReference<>(
                snapshot(signer, 4, NOW.minusSeconds(60), false)
        );
        AtomicReference<String> etag = new AtomicReference<>("\"status-4\"");
        try (StatusServer server = StatusServer.start(response, etag, new AtomicInteger())) {
            Path cacheFile = temporaryDirectory.resolve("trust-status.json");
            try (PluginTrustStatusCache cache = new PluginTrustStatusCache(
                    verifier(signer, server.url(), NOW), cacheFile, Clock.fixed(NOW, ZoneOffset.UTC)
            )) {
                cache.refresh();
                byte[] accepted = Files.readAllBytes(cacheFile);
                JsonObject selfRevoking = statusEnvelope(signer, 5, NOW, false);
                selfRevoking.getAsJsonObject("signed").getAsJsonArray("revokedKeyIds").add(keyId(signer));
                resign(selfRevoking, signer.getPrivate(), keyId(signer));
                response.set(selfRevoking.toString().getBytes(StandardCharsets.UTF_8));
                etag.set("\"status-5\"");

                assertThrows(IOException.class, cache::refresh);
                assertEquals(4, Objects.requireNonNull(cache.getLatestSnapshot()).version());
                assertArrayEquals(accepted, Files.readAllBytes(cacheFile));
            }
        }
    }

    /// Rejects a signature failure without replacing the previously authenticated cache file.
    @Test
    public void signatureFailureDoesNotOverwriteCache(@TempDir Path temporaryDirectory) throws Exception {
        KeyPair signer = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        KeyPair attacker = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        AtomicReference<byte @Unmodifiable []> response = new AtomicReference<>(snapshot(signer, 4, NOW, false));
        AtomicReference<String> etag = new AtomicReference<>("\"status-4\"");
        try (StatusServer server = StatusServer.start(response, etag, new AtomicInteger())) {
            PluginTrustVerifier verifier = verifier(signer, server.url(), NOW);
            Path cacheFile = temporaryDirectory.resolve("trust-status.json");
            try (PluginTrustStatusCache cache = new PluginTrustStatusCache(
                    verifier, cacheFile, Clock.fixed(NOW, ZoneOffset.UTC)
            )) {
                cache.refresh();
                byte[] accepted = Files.readAllBytes(cacheFile);
                response.set(snapshot(attacker, 5, NOW.plusSeconds(60), false));
                etag.set("\"status-5\"");

                assertThrows(IOException.class, cache::refresh);
                assertEquals(4, Objects.requireNonNull(cache.getLatestSnapshot()).version());
                assertArrayEquals(accepted, Files.readAllBytes(cacheFile));
            }
        }
    }

    /// Loads a stale authenticated cache for explicit revocation while withholding fresh certification state.
    @Test
    public void staleOfflineCacheRetainsExplicitRevocationOnly(@TempDir Path temporaryDirectory) throws Exception {
        KeyPair signer = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        AtomicReference<byte @Unmodifiable []> response = new AtomicReference<>(snapshot(signer, 4, NOW, true));
        AtomicReference<String> etag = new AtomicReference<>("\"status-4\"");
        Path cacheFile = temporaryDirectory.resolve("trust-status.json");
        try (StatusServer server = StatusServer.start(response, etag, new AtomicInteger())) {
            PluginTrustVerifier verifier = verifier(signer, server.url(), NOW);
            try (PluginTrustStatusCache cache = new PluginTrustStatusCache(
                    verifier, cacheFile, Clock.fixed(NOW, ZoneOffset.UTC)
            )) {
                cache.refresh();
                assertNotNull(cache.getFreshSnapshot());
            }
            Instant offlineNow = NOW.plusSeconds(48 * 60 * 60);
            PluginTrustVerifier offlineVerifier = verifier(signer, server.url(), offlineNow);
            try (PluginTrustStatusCache offline = new PluginTrustStatusCache(
                    offlineVerifier, cacheFile, Clock.fixed(offlineNow, ZoneOffset.UTC)
            )) {
                assertNull(offline.getFreshSnapshot());
                assertTrue(offline.isArtifactRevoked("dev.example.plugin", "1.2.3", HASH));
            }
        }
    }

    /// Rejects snapshots whose signed validity interval exceeds forty-eight hours.
    @Test
    public void rejectsStatusWindowLongerThanFortyEightHours() throws Exception {
        KeyPair signer = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        PluginTrustVerifier verifier = verifier(signer, "https://trust.example/status.json", NOW);
        JsonObject envelope = statusEnvelope(signer, 4, NOW, false);
        envelope.getAsJsonObject("signed").addProperty("expires", NOW.plusSeconds(49 * 60 * 60).toString());
        resign(envelope, signer.getPrivate(), keyId(signer));

        assertThrows(IllegalArgumentException.class, () -> verifier.verifyTrustStatusSnapshot(envelope));
    }

    /// Creates one verifier whose root fixes the supplied status endpoint.
    private static PluginTrustVerifier verifier(KeyPair signer, String statusUrl, Instant now) throws Exception {
        return verifier(signer, null, statusUrl, Clock.fixed(now, ZoneOffset.UTC));
    }

    /// Creates one verifier that follows an adjustable test clock.
    private static PluginTrustVerifier verifier(KeyPair signer, String statusUrl, Clock clock) throws Exception {
        return verifier(signer, null, statusUrl, clock);
    }

    /// Creates one verifier with isolated status and repository-attestation role keys.
    private static PluginTrustVerifier verifier(
            KeyPair statusSigner,
            KeyPair repositorySigner,
            String statusUrl,
            Instant now
    ) throws Exception {
        return verifier(statusSigner, repositorySigner, statusUrl, Clock.fixed(now, ZoneOffset.UTC));
    }

    /// Builds root metadata around one status signer and an optional independent repository signer.
    private static PluginTrustVerifier verifier(
            KeyPair statusSigner,
            @Nullable KeyPair repositorySigner,
            String statusUrl,
            Clock clock
    ) throws Exception {
        JsonObject signed = new JsonObject();
        signed.addProperty("_type", "root");
        signed.addProperty("schemaVersion", 1);
        signed.addProperty("version", 1);
        signed.addProperty("expires", "2036-01-01T00:00:00Z");
        signed.addProperty("statusUrl", statusUrl);
        JsonObject keys = new JsonObject();
        keys.add(keyId(statusSigner), keyDeclaration(statusSigner));
        if (repositorySigner != null) {
            keys.add(keyId(repositorySigner), keyDeclaration(repositorySigner));
        }
        signed.add("keys", keys);
        JsonObject roles = new JsonObject();
        roles.add("trust-status", role(keyId(statusSigner)));
        if (repositorySigner != null) {
            roles.add("repository-attestor", role(keyId(repositorySigner)));
        }
        signed.add("roles", roles);
        JsonObject root = new JsonObject();
        root.add("signed", signed);
        root.add("signatures", new JsonArray());
        return PluginTrustVerifier.fromRoot(root, clock, Set.of(), Set.of());
    }

    /// Creates one Ed25519 public-key declaration.
    private static JsonObject keyDeclaration(KeyPair signer) {
        JsonObject declaration = new JsonObject();
        declaration.addProperty("keyType", "ed25519");
        declaration.addProperty("scheme", "ed25519");
        declaration.addProperty("publicKey", Base64.getEncoder().encodeToString(signer.getPublic().getEncoded()));
        return declaration;
    }

    /// Creates one threshold-one role declaration.
    private static JsonObject role(String keyId) {
        JsonArray keyIds = new JsonArray();
        keyIds.add(keyId);
        JsonObject role = new JsonObject();
        role.add("keyIds", keyIds);
        role.addProperty("threshold", 1);
        return role;
    }

    /// Serializes one signed status snapshot for a local HTTP response.
    private static byte @Unmodifiable [] snapshot(
            KeyPair signer,
            long version,
            Instant generatedAt,
            boolean revoked
    ) throws Exception {
        return statusEnvelope(signer, version, generatedAt, revoked).toString().getBytes(StandardCharsets.UTF_8);
    }

    /// Creates one valid status envelope with an optional exact artifact revocation.
    private static JsonObject statusEnvelope(
            KeyPair signer,
            long version,
            Instant generatedAt,
            boolean revoked
    ) throws Exception {
        JsonObject signed = new JsonObject();
        signed.addProperty("_type", "trust-status-snapshot");
        signed.addProperty("schemaVersion", 1);
        signed.addProperty("version", version);
        signed.addProperty("generatedAt", generatedAt.toString());
        signed.addProperty("expires", generatedAt.plusSeconds(36 * 60 * 60).toString());
        signed.addProperty("policyVersion", "2026-08");
        signed.add("repositories", new JsonArray());
        JsonArray revokedArtifacts = new JsonArray();
        if (revoked) {
            JsonObject artifact = new JsonObject();
            artifact.addProperty("sha256", HASH);
            artifact.addProperty("pluginId", "dev.example.plugin");
            artifact.addProperty("version", "1.2.3");
            artifact.addProperty("revokedAt", generatedAt.toString());
            artifact.addProperty("reasonCode", "malware");
            revokedArtifacts.add(artifact);
        }
        signed.add("revokedArtifacts", revokedArtifacts);
        signed.add("revokedKeyIds", new JsonArray());
        JsonObject envelope = new JsonObject();
        envelope.add("signed", signed);
        envelope.add("signatures", new JsonArray());
        resign(envelope, signer.getPrivate(), keyId(signer));
        return envelope;
    }

    /// Creates one signed historical repository proof for the immutable-proof cache.
    private static JsonObject repositoryAttestation(KeyPair signer, String verificationId) throws Exception {
        JsonObject signed = new JsonObject();
        signed.addProperty("_type", "repository-attestation");
        signed.addProperty("schemaVersion", 1);
        signed.addProperty("repository", "github.com/example/plugin");
        signed.addProperty("repositoryId", 1701);
        signed.addProperty("defaultBranch", "main");
        JsonArray topics = new JsonArray();
        topics.add("hmclce");
        signed.add("topics", topics);
        JsonArray pluginIds = new JsonArray();
        pluginIds.add("dev.example.plugin");
        signed.add("pluginIds", pluginIds);
        signed.addProperty("status", "approved");
        signed.addProperty("checkedAt", NOW.minusSeconds(24 * 60 * 60).toString());
        signed.addProperty("validUntil", NOW.minusSeconds(60).toString());
        signed.addProperty("policyVersion", "2026-08");
        signed.addProperty("sourceCommit", "0123456789abcdef0123456789abcdef01234567");
        signed.addProperty("verificationId", verificationId);
        JsonObject envelope = new JsonObject();
        envelope.add("signed", signed);
        envelope.add("signatures", new JsonArray());
        resign(envelope, signer.getPrivate(), keyId(signer), PluginTrustVerifier.REPOSITORY_ATTESTATION_DOMAIN);
        return envelope;
    }

    /// Creates one repository status entry for monotonic-revocation tests.
    private static JsonObject repositoryStatus(String status) {
        JsonObject repository = new JsonObject();
        repository.addProperty("repositoryId", 1701);
        repository.addProperty("repository", "github.com/example/plugin");
        repository.addProperty("verificationId", "11111111-1111-4111-8111-111111111118");
        repository.addProperty("status", status);
        repository.addProperty("lastVerifiedAt", NOW.minusSeconds(60).toString());
        repository.addProperty("nextVerificationAt", NOW.plusSeconds(7 * 24 * 60 * 60).toString());
        JsonArray pluginIds = new JsonArray();
        pluginIds.add("dev.example.plugin");
        repository.add("pluginIds", pluginIds);
        return repository;
    }

    /// Replaces an envelope signature after a test mutates signed fields.
    private static void resign(JsonObject envelope, PrivateKey privateKey, String keyId) throws Exception {
        resign(envelope, privateKey, keyId, PluginTrustVerifier.TRUST_STATUS_DOMAIN);
    }

    /// Replaces an envelope signature using an explicit signature domain.
    private static void resign(JsonObject envelope, PrivateKey privateKey, String keyId, String domain)
            throws Exception {
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(privateKey);
        signer.update(CanonicalJson.signatureInput(
                domain,
                envelope.getAsJsonObject("signed")
        ));
        JsonObject signature = new JsonObject();
        signature.addProperty("keyId", keyId);
        signature.addProperty("signature", Base64.getEncoder().encodeToString(signer.sign()));
        JsonArray signatures = new JsonArray();
        signatures.add(signature);
        envelope.add("signatures", signatures);
    }

    /// Computes the wire key ID for one Ed25519 pair.
    private static String keyId(KeyPair pair) throws Exception {
        return "ed25519:" + HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(pair.getPublic().getEncoded())
        );
    }

    /// Local conditional-response status endpoint.
    @NotNullByDefault
    private static final class StatusServer implements AutoCloseable {
        /// Local HTTP server.
        private final HttpServer server;

        /// Creates one started server wrapper.
        private StatusServer(HttpServer server) {
            this.server = server;
        }

        /// Starts a server that sends 304 only after observing the current ETag.
        private static StatusServer start(
                AtomicReference<byte @Unmodifiable []> response,
                AtomicReference<String> etag,
                AtomicInteger conditionalRequests
        ) throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/status.json", exchange -> {
                if (etag.get().equals(exchange.getRequestHeaders().getFirst("If-None-Match"))) {
                    conditionalRequests.incrementAndGet();
                    exchange.getResponseHeaders().add("ETag", etag.get());
                    exchange.sendResponseHeaders(HttpURLConnection.HTTP_NOT_MODIFIED, -1);
                    exchange.close();
                    return;
                }
                exchange.getResponseHeaders().add("ETag", etag.get());
                respond(exchange, response.get());
            });
            server.start();
            return new StatusServer(server);
        }

        /// Returns the fixed local endpoint URL embedded into the test root.
        private String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/status.json";
        }

        /// Stops this local endpoint.
        @Override
        public void close() {
            server.stop(0);
        }
    }

    /// Mutable UTC clock used to advance refresh cadence without sleeping.
    @NotNullByDefault
    private static final class AdjustableClock extends Clock {
        /// Current deterministic instant.
        private final AtomicReference<Instant> current;

        /// Creates a clock at one initial instant.
        private AdjustableClock(Instant initial) {
            current = new AtomicReference<>(initial);
        }

        /// Advances this clock atomically by a positive duration.
        private void advance(Duration duration) {
            current.updateAndGet(value -> value.plus(duration));
        }

        /// Returns the fixed UTC zone used by signed test timestamps.
        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        /// Returns a fixed view of the current instant in the requested zone.
        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(instant(), zone);
        }

        /// Returns the current adjustable instant.
        @Override
        public Instant instant() {
            return current.get();
        }
    }

    /// Writes one complete local HTTP response.
    private static void respond(HttpExchange exchange, byte @Unmodifiable [] body) throws IOException {
        exchange.sendResponseHeaders(HttpURLConnection.HTTP_OK, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
