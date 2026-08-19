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

import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies that every HMCL CE build contains a syntactically valid plugin trust anchor resource.
@NotNullByDefault
public final class PluginTrustRootResourceTest {
    /// Loads the development root and retains unsigned community behavior.
    @Test
    public void loadsEmbeddedDevelopmentRoot() throws Exception {
        PluginTrustVerifier verifier = PluginTrustVerifier.loadDefault();
        JsonObject manifest = new JsonObject();
        manifest.addProperty("schemaVersion", 2);
        manifest.addProperty("id", "dev.example.plugin");
        manifest.add("versions", new com.google.gson.JsonArray());

        assertEquals(
                PluginTrustLevel.COMMUNITY,
                verifier.verifyManifest(manifest, "dev.example.plugin", "github.com/example/plugin").trust().level()
        );
    }

    /// Rejects online roles whose otherwise different key sets partially overlap.
    @Test
    public void rejectsPartiallyOverlappingOnlineRoleKeys() throws Exception {
        KeyPair first = keyPair();
        KeyPair shared = keyPair();
        KeyPair third = keyPair();
        JsonObject root = root(first, shared, third);
        JsonObject roles = root.getAsJsonObject("signed").getAsJsonObject("roles");
        roles.add("official-repository", role(1, keyId(first), keyId(shared)));
        roles.add("repository-attestor", role(1, keyId(shared), keyId(third)));

        assertThrows(IllegalArgumentException.class, () -> new PluginTrustRoot(root));
    }

    /// Rejects a duplicate key ID inside one online role declaration.
    @Test
    public void rejectsDuplicateKeyWithinOnlineRole() throws Exception {
        KeyPair signer = keyPair();
        JsonObject root = root(signer);
        root.getAsJsonObject("signed").getAsJsonObject("roles").add(
                "artifact-attestor",
                role(1, keyId(signer), keyId(signer))
        );

        assertThrows(IllegalArgumentException.class, () -> new PluginTrustRoot(root));
    }

    /// Rejects a fractional root schema version instead of truncating it to one.
    @Test
    public void rejectsFractionalSchemaVersion() throws Exception {
        JsonObject root = root(keyPair());
        root.getAsJsonObject("signed").addProperty("schemaVersion", 1.5);

        assertThrows(IllegalArgumentException.class, () -> new PluginTrustRoot(root));
    }

    /// Rejects a fractional online-role threshold instead of truncating it to one.
    @Test
    public void rejectsFractionalOnlineRoleThreshold() throws Exception {
        KeyPair signer = keyPair();
        JsonObject root = root(signer);
        JsonObject role = role(1, keyId(signer));
        role.addProperty("threshold", 1.5);
        root.getAsJsonObject("signed").getAsJsonObject("roles").add("trust-status", role);

        assertThrows(IllegalArgumentException.class, () -> new PluginTrustRoot(root));
    }

    /// Rejects multisignature thresholds for online envelopes that carry only one signature.
    @Test
    public void rejectsOnlineRoleThresholdAboveOne() throws Exception {
        KeyPair first = keyPair();
        KeyPair second = keyPair();
        JsonObject root = root(first, second);
        root.getAsJsonObject("signed").getAsJsonObject("roles").add(
                "repository-attestor",
                role(2, keyId(first), keyId(second))
        );

        assertThrows(IllegalArgumentException.class, () -> new PluginTrustRoot(root));
    }

    /// Creates a syntactically complete root around independent Ed25519 public keys.
    private static JsonObject root(KeyPair... signers) throws Exception {
        JsonObject signed = new JsonObject();
        signed.addProperty("_type", "root");
        signed.addProperty("schemaVersion", 1);
        signed.addProperty("version", 1);
        signed.addProperty("expires", "2036-01-01T00:00:00Z");
        signed.addProperty("statusUrl", "https://trust.example/status.json");
        JsonObject keys = new JsonObject();
        for (KeyPair signer : signers) {
            keys.add(keyId(signer), key(signer));
        }
        signed.add("keys", keys);
        signed.add("roles", new JsonObject());
        JsonObject root = new JsonObject();
        root.add("signed", signed);
        root.add("signatures", new com.google.gson.JsonArray());
        return root;
    }

    /// Creates one Ed25519 public-key declaration.
    private static JsonObject key(KeyPair signer) {
        JsonObject declaration = new JsonObject();
        declaration.addProperty("keyType", "ed25519");
        declaration.addProperty("scheme", "ed25519");
        declaration.addProperty("publicKey", Base64.getEncoder().encodeToString(signer.getPublic().getEncoded()));
        return declaration;
    }

    /// Creates one role declaration using the exact key order supplied by the root author.
    private static JsonObject role(int threshold, String... keyIds) {
        com.google.gson.JsonArray selected = new com.google.gson.JsonArray();
        for (String keyId : keyIds) {
            selected.add(keyId);
        }
        JsonObject role = new JsonObject();
        role.add("keyIds", selected);
        role.addProperty("threshold", threshold);
        return role;
    }

    /// Generates one independent Ed25519 signing pair.
    private static KeyPair keyPair() throws Exception {
        return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }

    /// Derives the protocol key ID from one encoded Ed25519 public key.
    private static String keyId(KeyPair signer) throws Exception {
        return "ed25519:" + HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(signer.getPublic().getEncoded())
        );
    }
}
