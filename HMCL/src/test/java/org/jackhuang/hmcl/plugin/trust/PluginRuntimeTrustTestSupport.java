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
import org.jetbrains.annotations.NotNullByDefault;

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
import java.util.Set;

/// Builds real role-signed certification receipts for cross-package runtime integration tests.
@NotNullByDefault
public final class PluginRuntimeTrustTestSupport {
    /// Fixed immutable repository identity used only by isolated tests.
    private static final String REPOSITORY = "github.com/hmclce/runtime-test";

    /// Fixed immutable repository numeric ID used only by isolated tests.
    private static final long REPOSITORY_ID = 81173L;

    /// Fixed historical verification ID shared by both proof envelopes.
    private static final String VERIFICATION_ID = "runtime-test-verification";

    /// Fixed verifier clock after the historical weekly proof expired.
    private static final Instant NOW = Instant.parse("2026-08-14T00:00:00Z");

    /// Prevents construction of the test utility.
    private PluginRuntimeTrustTestSupport() {
    }

    /// Creates one proof-backed certification receipt for an exact test NPL identity.
    ///
    /// @param pluginId exact plugin ID
    /// @param version exact package version
    /// @param sha256 exact complete NPL SHA-256
    /// @param size exact complete NPL size
    /// @return receipt containing two independently role-signed proof envelopes
    /// @throws Exception if the platform cannot generate or sign Ed25519 test proofs
    public static PluginCertificationReceipt certificationReceipt(
            String pluginId,
            String version,
            String sha256,
            long size
    ) throws Exception {
        KeyPair repositorySigner = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        KeyPair artifactSigner = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        KeyPair statusSigner = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        PluginTrustVerifier verifier = verifier(repositorySigner, artifactSigner, statusSigner);
        JsonObject repositoryProof = repositoryProof(repositorySigner, pluginId);
        JsonObject artifactProof = artifactProof(artifactSigner, pluginId, version, sha256, size);
        PluginVerifiedCertification certification = verifier.verifyInstalledCertification(
                artifactProof,
                repositoryProof,
                pluginId,
                version,
                sha256,
                size
        );
        return PluginCertificationReceipt.fromVerified(certification, artifactProof, repositoryProof);
    }

    /// Creates a guard whose stale authenticated snapshot revokes the supplied exact certified artifact.
    ///
    /// @param pluginId exact plugin ID
    /// @param version exact package version
    /// @param sha256 exact complete NPL SHA-256
    /// @param size exact complete NPL size
    /// @return proof-backed runtime guard
    /// @throws Exception if the platform cannot generate or sign Ed25519 test proofs
    public static PluginRuntimeTrustGuard revokedArtifactGuard(
            String pluginId,
            String version,
            String sha256,
            long size
    ) throws Exception {
        KeyPair repositorySigner = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        KeyPair artifactSigner = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        KeyPair statusSigner = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        PluginTrustVerifier verifier = verifier(repositorySigner, artifactSigner, statusSigner);
        JsonObject repositoryProof = repositoryProof(repositorySigner, pluginId);
        JsonObject artifactProof = artifactProof(artifactSigner, pluginId, version, sha256, size);
        PluginVerifiedCertification certification = verifier.verifyInstalledCertification(
                artifactProof,
                repositoryProof,
                pluginId,
                version,
                sha256,
                size
        );
        PluginCertificationReceipt receipt = PluginCertificationReceipt.fromVerified(
                certification,
                artifactProof,
                repositoryProof
        );
        PluginTrustStatusSnapshot snapshot = new PluginTrustStatusSnapshot(
                3,
                NOW.minusSeconds(14 * 24 * 60 * 60L),
                NOW.minusSeconds(12 * 24 * 60 * 60L),
                "runtime-test-policy",
                List.of(new PluginTrustStatusSnapshot.RepositoryStatus(
                        REPOSITORY_ID,
                        REPOSITORY,
                        VERIFICATION_ID,
                        "approved",
                        NOW.minusSeconds(21 * 24 * 60 * 60L),
                        NOW.minusSeconds(14 * 24 * 60 * 60L),
                        List.of(pluginId)
                )),
                List.of(new PluginTrustStatusSnapshot.RevokedArtifact(
                        sha256,
                        pluginId,
                        version,
                        NOW.minusSeconds(13 * 24 * 60 * 60L),
                        "runtime-test-revocation"
                )),
                Set.of(),
                keyId(statusSigner)
        );
        return PluginRuntimeTrustGuard.of(List.of(receipt), snapshot, verifier);
    }

    /// Creates a verifier with independent keys for every online proof role used by this fixture.
    private static PluginTrustVerifier verifier(
            KeyPair repositorySigner,
            KeyPair artifactSigner,
            KeyPair statusSigner
    ) throws Exception {
        JsonObject signed = new JsonObject();
        signed.addProperty("_type", "root");
        signed.addProperty("schemaVersion", 1);
        signed.addProperty("version", 1);
        signed.addProperty("expires", "2036-01-01T00:00:00Z");
        signed.addProperty("statusUrl", "https://trust.example/status.json");
        JsonObject keys = new JsonObject();
        keys.add(keyId(repositorySigner), keyDeclaration(repositorySigner));
        keys.add(keyId(artifactSigner), keyDeclaration(artifactSigner));
        keys.add(keyId(statusSigner), keyDeclaration(statusSigner));
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

    /// Creates one historical role-signed repository proof.
    private static JsonObject repositoryProof(KeyPair signer, String pluginId) throws Exception {
        JsonObject signed = new JsonObject();
        signed.addProperty("_type", "repository-attestation");
        signed.addProperty("schemaVersion", 1);
        signed.addProperty("repository", REPOSITORY);
        signed.addProperty("repositoryId", REPOSITORY_ID);
        signed.addProperty("defaultBranch", "main");
        JsonArray topics = new JsonArray();
        topics.add("HMCLCE");
        signed.add("topics", topics);
        JsonArray pluginIds = new JsonArray();
        pluginIds.add(pluginId);
        signed.add("pluginIds", pluginIds);
        signed.addProperty("status", "approved");
        signed.addProperty("checkedAt", "2026-07-01T00:00:00Z");
        signed.addProperty("validUntil", "2026-07-08T00:00:00Z");
        signed.addProperty("policyVersion", "runtime-test-policy");
        signed.addProperty("sourceCommit", "0123456789abcdef0123456789abcdef01234567");
        signed.addProperty("verificationId", VERIFICATION_ID);
        return envelope(signed, signer, PluginTrustVerifier.REPOSITORY_ATTESTATION_DOMAIN);
    }

    /// Creates one role-signed exact artifact proof.
    private static JsonObject artifactProof(
            KeyPair signer,
            String pluginId,
            String version,
            String sha256,
            long size
    ) throws Exception {
        JsonObject signed = new JsonObject();
        signed.addProperty("_type", "npl-attestation");
        signed.addProperty("schemaVersion", 1);
        signed.addProperty("repository", REPOSITORY);
        signed.addProperty("repositoryId", REPOSITORY_ID);
        signed.addProperty("pluginId", pluginId);
        signed.addProperty("version", version);
        signed.addProperty("tag", "v" + version);
        signed.addProperty("assetUrl", "https://github.com/hmclce/runtime-test/releases/download/v"
                + version + "/plugin.npl");
        signed.addProperty("assetName", "plugin.npl");
        signed.addProperty("sha256", sha256);
        signed.addProperty("size", size);
        signed.addProperty("repositoryVerificationId", VERIFICATION_ID);
        signed.addProperty("sourceCommit", "0123456789abcdef0123456789abcdef01234567");
        signed.addProperty("policyVersion", "runtime-test-policy");
        signed.addProperty("jobId", "runtime-test-job");
        signed.addProperty("approvedAt", "2026-07-02T00:00:00Z");
        return envelope(signed, signer, PluginTrustVerifier.NPL_ATTESTATION_DOMAIN);
    }

    /// Signs one canonical payload for an explicit role domain.
    private static JsonObject envelope(JsonObject signed, KeyPair signer, String domain) throws Exception {
        Signature signature = Signature.getInstance("Ed25519");
        signature.initSign(signer.getPrivate());
        signature.update(CanonicalJson.signatureInput(domain, signed));
        JsonObject entry = new JsonObject();
        entry.addProperty("keyId", keyId(signer));
        entry.addProperty("signature", Base64.getEncoder().encodeToString(signature.sign()));
        JsonArray signatures = new JsonArray();
        signatures.add(entry);
        JsonObject envelope = new JsonObject();
        envelope.add("signed", signed);
        envelope.add("signatures", signatures);
        return envelope;
    }

    /// Computes the wire key ID for one Ed25519 pair.
    private static String keyId(KeyPair signer) throws Exception {
        return "ed25519:" + HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(signer.getPublic().getEncoded())
        );
    }
}
