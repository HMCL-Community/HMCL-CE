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
import org.jackhuang.hmcl.plugin.trust.PluginTrustVerifier;
import org.jackhuang.hmcl.util.gson.JsonUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.time.Clock;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies that signed official indexes pin the exact bytes of every remote plugin manifest.
@NotNullByDefault
public final class OfficialManifestHashTest {
    /// Loads an official manifest whose raw bytes match the digest inside the signed registry.
    @Test
    public void acceptsPinnedOfficialManifest() throws Exception {
        byte[] manifest = manifest("dev.hmclce.official");
        try (Fixture fixture = Fixture.start(manifest, manifest)) {
            PluginStoreManifest loaded = fixture.manager().getPluginManifest(
                    "dev.hmclce.official", fixture.manifestUrl()
            );
            assertEquals("dev.hmclce.official", loaded.getId());
        }
    }

    /// Rejects a byte-level remote replacement even while the official registry signature remains valid.
    @Test
    public void rejectsManifestWhoseBytesChangedAfterRegistrySigning() throws Exception {
        byte[] pinned = manifest("dev.hmclce.official");
        byte[] served = (new String(pinned, StandardCharsets.UTF_8) + " ").getBytes(StandardCharsets.UTF_8);
        try (Fixture fixture = Fixture.start(pinned, served)) {
            IOException exception = assertThrows(IOException.class, () -> fixture.manager().getPluginManifest(
                    "dev.hmclce.official", fixture.manifestUrl()
            ));
            assertTrue(exception.getMessage().contains("SHA-256 mismatch"), exception.getMessage());
        }
    }

    /// Creates one valid schema-two plugin manifest.
    private static byte @Unmodifiable [] manifest(String pluginId) {
        return ("""
                {
                  "schemaVersion": 2,
                  "id": "%s",
                  "versions": [
                    {
                      "version": "1.0.0",
                      "packageUrl": "https://example.com/plugin.npl",
                      "sha256": "0000000000000000000000000000000000000000000000000000000000000000",
                      "pluginApiVersion": 4,
                      "permissions": [],
                      "requiredPermissions": [],
                      "launcherVersion": "*",
                      "dependencies": [],
                      "size": 1
                    }
                  ]
                }
                """.formatted(pluginId)).getBytes(StandardCharsets.UTF_8);
    }

    /// Writes one bounded HTTP response.
    private static void respond(HttpExchange exchange, byte @Unmodifiable [] body) throws IOException {
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    /// Owns an official signed registry, its trust root, and a local manifest endpoint.
    private record Fixture(HttpServer server, PluginStoreManager manager, String manifestUrl) implements AutoCloseable {
        private static Fixture start(
                byte @Unmodifiable [] pinnedManifest,
                byte @Unmodifiable [] servedManifest
        ) throws Exception {
            KeyPair keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            String keyId = keyId(keyPair);
            PluginTrustVerifier verifier = PluginTrustVerifier.fromRoot(
                    root(keyPair, keyId), Clock.systemUTC(), Set.of(), Set.of()
            );

            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            String manifestUrl = baseUrl + "/manifest.json";
            byte[] registry = signedRegistry(keyPair, keyId, manifestUrl, sha256(pinnedManifest));
            server.createContext("/plugins.json", exchange -> respond(exchange, registry));
            server.createContext("/manifest.json", exchange -> respond(exchange, servedManifest));
            server.start();

            PluginStoreManager manager = new PluginStoreManager(verifier);
            manager.loadSource(new PluginSource(
                    PluginSource.OFFICIAL_ID, baseUrl + "/plugins.json", null, true, true
            ));
            return new Fixture(server, manager, manifestUrl);
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    /// Creates one embedded root containing only the test official-repository role.
    private static JsonObject root(KeyPair pair, String keyId) {
        JsonObject key = new JsonObject();
        key.addProperty("keyType", "ed25519");
        key.addProperty("scheme", "ed25519");
        key.addProperty("publicKey", Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()));
        JsonObject keys = new JsonObject();
        keys.add(keyId, key);
        JsonArray keyIds = new JsonArray();
        keyIds.add(keyId);
        JsonObject role = new JsonObject();
        role.add("keyIds", keyIds);
        role.addProperty("threshold", 1);
        JsonObject roles = new JsonObject();
        roles.add("official-repository", role);
        JsonObject signed = new JsonObject();
        signed.addProperty("_type", "root");
        signed.addProperty("schemaVersion", 1);
        signed.add("keys", keys);
        signed.add("roles", roles);
        JsonObject root = new JsonObject();
        root.add("signed", signed);
        root.add("signatures", new JsonArray());
        return root;
    }

    /// Creates and signs an official registry with one manifest pin.
    private static byte @Unmodifiable [] signedRegistry(
            KeyPair pair,
            String keyId,
            String manifestUrl,
            String manifestSha256
    ) throws Exception {
        JsonObject entry = new JsonObject();
        entry.addProperty("id", "dev.hmclce.official");
        entry.addProperty("name", "Official Test");
        entry.addProperty("manifestUrl", manifestUrl);
        entry.addProperty("manifestSha256", manifestSha256);
        JsonArray plugins = new JsonArray();
        plugins.add(entry);
        JsonObject payload = new JsonObject();
        payload.addProperty("schemaVersion", 1);
        payload.addProperty("name", "Official Test Store");
        payload.add("plugins", plugins);

        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(pair.getPrivate());
        signer.update(CanonicalJson.signatureInput(PluginTrustVerifier.OFFICIAL_REGISTRY_DOMAIN, payload));
        JsonObject signature = new JsonObject();
        signature.addProperty("keyId", keyId);
        signature.addProperty("signature", Base64.getEncoder().encodeToString(signer.sign()));
        JsonArray signatures = new JsonArray();
        signatures.add(signature);
        JsonObject envelope = new JsonObject();
        envelope.add("signed", payload);
        envelope.add("signatures", signatures);
        return JsonUtils.GSON.toJson(envelope).getBytes(StandardCharsets.UTF_8);
    }

    /// Computes one lowercase SHA-256 digest.
    private static String sha256(byte @Unmodifiable [] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    /// Computes the trust-root key identifier for one public key.
    private static String keyId(KeyPair pair) throws Exception {
        return "ed25519:" + sha256(pair.getPublic().getEncoded());
    }
}
