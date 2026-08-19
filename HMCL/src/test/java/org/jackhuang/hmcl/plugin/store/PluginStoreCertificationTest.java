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
package org.jackhuang.hmcl.plugin.store;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.jackhuang.hmcl.plugin.trust.CanonicalJson;
import org.jackhuang.hmcl.plugin.trust.PluginTrustLevel;
import org.jackhuang.hmcl.plugin.trust.PluginTrustStatusCache;
import org.jackhuang.hmcl.plugin.trust.PluginTrustVerifier;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// Verifies end-to-end version-scoped certification while loading ordinary third-party store manifests.
@NotNullByDefault
public final class PluginStoreCertificationTest {
    /// Stable validation instant.
    private static final Instant NOW = Instant.parse("2026-08-14T08:00:00Z");

    /// First plugin ID authorized by the shared repository proof.
    private static final String FIRST_ID = "dev.example.first";

    /// Second plugin ID authorized by the shared repository proof.
    private static final String SECOND_ID = "dev.example.second";

    /// Repository identity established by registry metadata.
    private static final String REPOSITORY = "github.com/example/plugins";

    /// Historical verification ID referenced by the NPL proof.
    private static final String VERIFICATION_ID = "11111111-1111-4111-8111-111111111117";

    /// Ignores retired certification declarations while loading an otherwise valid community manifest.
    @Test
    public void legacyCertificationDeclarationDoesNotAffectCommunityTrust() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/registry.json", exchange -> respond(exchange, """
                {"schemaVersion":1,"name":"Community","plugins":[{
                  "id":"dev.example.legacy",
                  "name":"Legacy",
                  "manifestUrl":"%s/manifest.json",
                  "repository":"https://github.com/example/legacy"
                }]}
                """.formatted(baseUrl)));
        server.createContext("/manifest.json", exchange -> respond(exchange, """
                {
                  "schemaVersion":2,
                  "id":"dev.example.legacy",
                  "repository":"github.com/example/legacy",
                  "versions":[{
                    "version":"1.0.0",
                    "packageUrl":"https://github.com/example/legacy/releases/download/v1.0.0/legacy.npl",
                    "sha256":"%s",
                    "pluginApiVersion":4,
                    "permissions":[],
                    "requiredPermissions":[],
                    "launcherVersion":"*",
                    "dependencies":[],
                    "size":1,
                    "certification":{}
                  }]
                }
                """.formatted("0".repeat(64))));
        server.start();

        try {
            PluginStoreManager manager = new PluginStoreManager(PluginTrustVerifier.loadDefault());
            manager.loadSource(new PluginSource("community", baseUrl + "/registry.json", null, true, false));
            PluginStoreManifest manifest = Objects.requireNonNull(manager.getStoreItems().get(0).getManifest());

            assertEquals(
                    PluginTrustLevel.COMMUNITY,
                    Objects.requireNonNull(manifest.getVersion("1.0.0")).getTrust().level()
            );
        } finally {
            server.stop(0);
        }
    }

    /// Ignores complete legacy proof chains because official registry references now determine certification.
    @Test
    public void ignoresLegacyRepositoryAndArtifactProofs(@TempDir Path temporaryDirectory) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
        KeyPair repositorySigner = generator.generateKeyPair();
        KeyPair artifactSigner = generator.generateKeyPair();
        KeyPair statusSigner = generator.generateKeyPair();
        AtomicInteger repositoryRequests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        PluginTrustVerifier verifier = verifier(
                repositorySigner,
                artifactSigner,
                statusSigner,
                baseUrl + "/status.json"
        );
        JsonObject repositoryProof = repositoryProof(repositorySigner);
        JsonObject status = statusSnapshot(statusSigner);
        String firstHash = "1".repeat(64);
        String secondHash = "2".repeat(64);
        JsonObject firstArtifact = artifactProof(
                artifactSigner,
                FIRST_ID,
                "2.0.0",
                firstHash,
                20,
                "first.npl"
        );
        JsonObject mismatchedSecondArtifact = artifactProof(
                artifactSigner,
                SECOND_ID,
                "3.0.0",
                "3".repeat(64),
                30,
                "second.npl"
        );
        server.createContext("/status.json", exchange -> respond(exchange, status.toString()));
        server.createContext("/api/v1/repositories/attestations/" + VERIFICATION_ID, exchange -> {
            repositoryRequests.incrementAndGet();
            respond(exchange, repositoryProof.toString());
        });
        server.createContext("/registry.json", exchange -> respond(exchange, """
                {"schemaVersion":1,"name":"Third Party","plugins":[
                  {"id":"%s","name":"First","manifestUrl":"%s/first.json","repository":"https://%s"},
                  {"id":"%s","name":"Second","manifestUrl":"%s/second.json","repository":"https://%s"}
                ]}
                """.formatted(FIRST_ID, baseUrl, REPOSITORY, SECOND_ID, baseUrl, REPOSITORY)));
        server.createContext("/first.json", exchange -> respond(exchange, manifest(
                FIRST_ID,
                "2.0.0",
                "first.npl",
                firstHash,
                20,
                firstArtifact,
                true
        )));
        server.createContext("/second.json", exchange -> respond(exchange, manifest(
                SECOND_ID,
                "3.0.0",
                "second.npl",
                secondHash,
                30,
                mismatchedSecondArtifact,
                false
        )));
        server.start();

        try (PluginTrustStatusCache cache = new PluginTrustStatusCache(
                verifier,
                temporaryDirectory.resolve("status-cache.json"),
                Clock.fixed(NOW, ZoneOffset.UTC)
        )) {
            cache.refresh();
            PluginStoreManager manager = new PluginStoreManager(verifier, cache);
            manager.loadSource(new PluginSource("third-party", baseUrl + "/registry.json", null, true, false));
            List<PluginStoreItem> items = manager.getStoreItems();
            PluginStoreManifest first = Objects.requireNonNull(items.get(0).getManifest());
            PluginStoreManifest second = Objects.requireNonNull(items.get(1).getManifest());

            assertEquals(PluginTrustLevel.COMMUNITY,
                    Objects.requireNonNull(first.getVersion("2.0.0")).getTrust().level());
            assertEquals(PluginTrustLevel.COMMUNITY,
                    Objects.requireNonNull(first.getVersion("1.0.0")).getTrust().level());
            assertEquals(PluginTrustLevel.COMMUNITY,
                    Objects.requireNonNull(second.getVersion("3.0.0")).getTrust().level());
            assertEquals(0, repositoryRequests.get());
        } finally {
            server.stop(0);
        }
    }

    /// Creates one role-separated root-bound verifier.
    private static PluginTrustVerifier verifier(
            KeyPair repositorySigner,
            KeyPair artifactSigner,
            KeyPair statusSigner,
            String statusUrl
    ) throws Exception {
        JsonObject signed = new JsonObject();
        signed.addProperty("_type", "root");
        signed.addProperty("schemaVersion", 1);
        signed.addProperty("version", 1);
        signed.addProperty("expires", "2036-01-01T00:00:00Z");
        signed.addProperty("statusUrl", statusUrl);
        JsonObject keys = new JsonObject();
        keys.add(keyId(repositorySigner), key(repositorySigner));
        keys.add(keyId(artifactSigner), key(artifactSigner));
        keys.add(keyId(statusSigner), key(statusSigner));
        signed.add("keys", keys);
        JsonObject roles = new JsonObject();
        roles.add("repository-attestor", role(keyId(repositorySigner)));
        roles.add("artifact-attestor", role(keyId(artifactSigner)));
        roles.add("trust-status", role(keyId(statusSigner)));
        signed.add("roles", roles);
        JsonObject root = new JsonObject();
        root.add("signed", signed);
        root.add("signatures", new JsonArray());
        return PluginTrustVerifier.fromRoot(
                root,
                Clock.fixed(NOW, ZoneOffset.UTC),
                Set.of(),
                Set.of()
        );
    }

    /// Creates one weekly repository proof covering both plugin IDs.
    private static JsonObject repositoryProof(KeyPair signer) throws Exception {
        JsonObject signed = new JsonObject();
        signed.addProperty("_type", "repository-attestation");
        signed.addProperty("schemaVersion", 1);
        signed.addProperty("repository", REPOSITORY);
        signed.addProperty("repositoryId", 1701);
        signed.addProperty("defaultBranch", "main");
        JsonArray topics = new JsonArray();
        topics.add("HMCLCE");
        signed.add("topics", topics);
        signed.add("pluginIds", pluginIds());
        signed.addProperty("status", "approved");
        signed.addProperty("checkedAt", "2026-08-13T08:00:00Z");
        signed.addProperty("validUntil", "2026-08-20T08:00:00Z");
        signed.addProperty("policyVersion", "2026-08");
        signed.addProperty("sourceCommit", "0123456789abcdef0123456789abcdef01234567");
        signed.addProperty("verificationId", VERIFICATION_ID);
        return envelope(PluginTrustVerifier.REPOSITORY_ATTESTATION_DOMAIN, signed, signer);
    }

    /// Creates one fresh online approval snapshot for the shared repository.
    private static JsonObject statusSnapshot(KeyPair signer) throws Exception {
        JsonObject signed = new JsonObject();
        signed.addProperty("_type", "trust-status-snapshot");
        signed.addProperty("schemaVersion", 1);
        signed.addProperty("version", 4);
        signed.addProperty("generatedAt", "2026-08-14T07:00:00Z");
        signed.addProperty("expires", "2026-08-15T08:00:00Z");
        signed.addProperty("policyVersion", "2026-08");
        JsonObject repository = new JsonObject();
        repository.addProperty("repositoryId", 1701);
        repository.addProperty("repository", REPOSITORY);
        repository.addProperty("verificationId", VERIFICATION_ID);
        repository.addProperty("status", "approved");
        repository.addProperty("lastVerifiedAt", "2026-08-13T08:00:00Z");
        repository.addProperty("nextVerificationAt", "2026-08-20T08:00:00Z");
        repository.add("pluginIds", pluginIds());
        JsonArray repositories = new JsonArray();
        repositories.add(repository);
        signed.add("repositories", repositories);
        signed.add("revokedArtifacts", new JsonArray());
        signed.add("revokedKeyIds", new JsonArray());
        return envelope(PluginTrustVerifier.TRUST_STATUS_DOMAIN, signed, signer);
    }

    /// Creates one exact NPL proof.
    private static JsonObject artifactProof(
            KeyPair signer,
            String pluginId,
            String version,
            String sha256,
            long size,
            String assetName
    ) throws Exception {
        JsonObject signed = new JsonObject();
        signed.addProperty("_type", "npl-attestation");
        signed.addProperty("schemaVersion", 1);
        signed.addProperty("repository", REPOSITORY);
        signed.addProperty("repositoryId", 1701);
        signed.addProperty("pluginId", pluginId);
        signed.addProperty("version", version);
        signed.addProperty("tag", "v" + version);
        signed.addProperty("assetName", assetName);
        signed.addProperty("assetUrl", "https://github.com/example/plugins/releases/download/v" + version + "/" + assetName);
        signed.addProperty("sha256", sha256);
        signed.addProperty("size", size);
        signed.addProperty("sourceCommit", "0123456789abcdef0123456789abcdef01234567");
        signed.addProperty("repositoryVerificationId", VERIFICATION_ID);
        signed.addProperty("approvedAt", "2026-08-14T07:00:00Z");
        signed.addProperty("policyVersion", "2026-08");
        signed.addProperty("jobId", "job-42");
        return envelope(PluginTrustVerifier.NPL_ATTESTATION_DOMAIN, signed, signer);
    }

    /// Creates a schema-v2 manifest with one attested version and optionally one unsigned older version.
    private static String manifest(
            String pluginId,
            String version,
            String assetName,
            String sha256,
            long size,
            JsonObject artifactProof,
            boolean includeCommunityVersion
    ) {
        String community = includeCommunityVersion ? """
                ,{
                  "version":"1.0.0",
                  "packageUrl":"https://github.com/example/plugins/releases/download/v1.0.0/old.npl",
                  "sha256":"%s",
                  "pluginApiVersion":2,
                  "size":10
                }
                """.formatted("0".repeat(64)) : "";
        return """
                {
                  "schemaVersion":2,
                  "id":"%s",
                  "repository":"%s",
                  "versions":[{
                    "version":"%s",
                    "packageUrl":"https://github.com/example/plugins/releases/download/v%s/%s",
                    "sha256":"%s",
                    "pluginApiVersion":2,
                    "size":%d,
                    "certification":{"artifactAttestation":%s}
                  }%s]
                }
                """.formatted(
                pluginId,
                REPOSITORY,
                version,
                version,
                assetName,
                sha256,
                size,
                artifactProof,
                community
        );
    }

    /// Returns both plugin IDs in stable order.
    private static JsonArray pluginIds() {
        JsonArray pluginIds = new JsonArray();
        pluginIds.add(FIRST_ID);
        pluginIds.add(SECOND_ID);
        return pluginIds;
    }

    /// Creates one root public-key declaration.
    private static JsonObject key(KeyPair pair) {
        JsonObject key = new JsonObject();
        key.addProperty("keyType", "ed25519");
        key.addProperty("scheme", "ed25519");
        key.addProperty("publicKey", Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()));
        return key;
    }

    /// Creates one threshold-one root role.
    private static JsonObject role(String keyId) {
        JsonArray keyIds = new JsonArray();
        keyIds.add(keyId);
        JsonObject role = new JsonObject();
        role.add("keyIds", keyIds);
        role.addProperty("threshold", 1);
        return role;
    }

    /// Signs one canonical envelope with the selected domain.
    private static JsonObject envelope(String domain, JsonObject signed, KeyPair pair) throws Exception {
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(pair.getPrivate());
        signer.update(CanonicalJson.signatureInput(domain, signed));
        JsonObject signature = new JsonObject();
        signature.addProperty("keyId", keyId(pair));
        signature.addProperty("signature", Base64.getEncoder().encodeToString(signer.sign()));
        JsonArray signatures = new JsonArray();
        signatures.add(signature);
        JsonObject envelope = new JsonObject();
        envelope.add("signed", signed);
        envelope.add("signatures", signatures);
        return envelope;
    }

    /// Computes the wire key ID for one Ed25519 pair.
    private static String keyId(KeyPair pair) throws Exception {
        return "ed25519:" + HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(pair.getPublic().getEncoded())
        );
    }

    /// Writes one JSON response.
    private static void respond(HttpExchange exchange, String body) throws IOException {
        byte @Unmodifiable [] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(HttpURLConnection.HTTP_OK, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
