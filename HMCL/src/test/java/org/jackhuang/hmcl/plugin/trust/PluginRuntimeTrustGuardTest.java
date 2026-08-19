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
import org.jackhuang.hmcl.plugin.PluginArtifactIdentity;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies proof-backed runtime revocation decisions for already installed certified plugins.
@NotNullByDefault
public final class PluginRuntimeTrustGuardTest {
    /// Exact certified plugin ID used by every decision fixture.
    private static final String PLUGIN_ID = "dev.hmclce.test.certified";

    /// Exact certified plugin version used by every decision fixture.
    private static final String VERSION = "1.2.3";

    /// Exact installed package digest used by every decision fixture.
    private static final String SHA256 = "a".repeat(64);

    /// Exact signed package byte length.
    private static final long SIZE = 4312L;

    /// Immutable GitHub repository ID bound into the certification proof.
    private static final long REPOSITORY_ID = 421337L;

    /// Normalized GitHub repository identity bound into the certification proof.
    private static final String REPOSITORY = "github.com/hmclce/example-plugin";

    /// Historical weekly proof identifier bound into the artifact approval.
    private static final String VERIFICATION_ID = "verification-2026-08-14";

    /// Fixed verification clock after the historical weekly proof has expired.
    private static final Instant NOW = Instant.parse("2026-08-14T00:00:00Z");

    /// Leaves every artifact executable when online proof enforcement is inactive by policy.
    @Test
    public void inactiveGuardDoesNotBlockLegacyCertifiedArtifact() {
        assertNull(PluginRuntimeTrustGuard.inactive().getBlockReason(identity(), SIZE));
    }

    /// Uses a stale authenticated snapshot to block an exact revoked NPL artifact.
    @Test
    public void staleSnapshotStillBlocksExactArtifactRevocation() throws Exception {
        Fixture fixture = fixture();
        PluginRuntimeTrustGuard guard = guard(
                fixture,
                fixture.receipt,
                List.of(repository(REPOSITORY_ID, REPOSITORY, "approved")),
                List.of(new PluginTrustStatusSnapshot.RevokedArtifact(
                        SHA256,
                        PLUGIN_ID,
                        VERSION,
                        Instant.parse("2026-08-01T00:00:00Z"),
                        "malware"
                )),
                Set.of()
        );

        assertNotNull(guard.getBlockReason(identity(), SIZE));
    }

    /// Blocks a certified artifact when its exact receipt-bound repository is suspended.
    @Test
    public void blocksReceiptRepositorySuspension() throws Exception {
        Fixture fixture = fixture();
        PluginRuntimeTrustGuard guard = guard(
                fixture,
                fixture.receipt,
                List.of(repository(REPOSITORY_ID, REPOSITORY, "suspended")),
                List.of(),
                Set.of()
        );

        assertNotNull(guard.getBlockReason(identity(), SIZE));
    }

    /// Blocks a certified artifact when either signer re-derived from its proofs is revoked.
    @Test
    public void blocksReceiptSignerRevocation() throws Exception {
        Fixture fixture = fixture();
        PluginRuntimeTrustGuard guard = guard(
                fixture,
                fixture.receipt,
                List.of(repository(REPOSITORY_ID, REPOSITORY, "approved")),
                List.of(),
                Set.of(fixture.receipt.artifactSignerKeyId())
        );

        assertNotNull(guard.getBlockReason(identity(), SIZE));
    }

    /// Does not let a different repository with the same plugin ID suspend this receipt-bound artifact.
    @Test
    public void ignoresDifferentRepositoryWithSamePluginId() throws Exception {
        Fixture fixture = fixture();
        PluginRuntimeTrustGuard guard = guard(
                fixture,
                fixture.receipt,
                List.of(repository(REPOSITORY_ID + 1, "github.com/attacker/lookalike", "suspended")),
                List.of(),
                Set.of()
        );

        assertNull(guard.getBlockReason(identity(), SIZE));
    }

    /// Leaves an exact community or local artifact unaffected when no certification receipt exists.
    @Test
    public void communityArtifactWithoutReceiptIsNotBlocked() throws Exception {
        Fixture fixture = fixture();
        PluginTrustStatusSnapshot snapshot = snapshot(
                fixture,
                List.of(repository(REPOSITORY_ID, REPOSITORY, "revoked")),
                List.of(new PluginTrustStatusSnapshot.RevokedArtifact(
                        SHA256,
                        PLUGIN_ID,
                        VERSION,
                        Instant.parse("2026-08-01T00:00:00Z"),
                        "malware"
                )),
                Set.of(fixture.receipt.repositorySignerKeyId(), fixture.receipt.artifactSignerKeyId())
        );
        PluginRuntimeTrustGuard guard = PluginRuntimeTrustGuard.of(List.of(), snapshot, fixture.verifier);

        assertNull(guard.getBlockReason(identity(), SIZE));
    }

    /// Observes a newer authenticated status source before a not-yet-loaded plugin passes its runtime gate.
    @Test
    public void refreshAfterGuardConstructionBlocksLaterLoad() throws Exception {
        Fixture fixture = fixture();
        AtomicReference<@org.jetbrains.annotations.Nullable PluginTrustStatusSnapshot> latest =
                new AtomicReference<>();
        PluginRuntimeTrustGuard guard = PluginRuntimeTrustGuard.ofDynamic(
                List.of(fixture.receipt),
                fixture.verifier,
                latest::get
        );
        assertNull(guard.getBlockReason(identity(), SIZE));

        latest.set(snapshot(
                fixture,
                List.of(repository(REPOSITORY_ID, REPOSITORY, "approved")),
                List.of(new PluginTrustStatusSnapshot.RevokedArtifact(
                        SHA256,
                        PLUGIN_ID,
                        VERSION,
                        NOW.minusSeconds(60),
                        "malware"
                )),
                Set.of()
        ));

        assertNotNull(guard.getBlockReason(identity(), SIZE));
    }

    /// Rejects a receipt whose derived repository ID was changed without changing either signed proof.
    @Test
    public void blocksTamperedDerivedRepositoryId() throws Exception {
        Fixture fixture = fixture();
        PluginCertificationReceipt tampered = copyReceipt(
                fixture.receipt,
                fixture.receipt.sha256(),
                REPOSITORY_ID + 1,
                fixture.receipt.artifactSignerKeyId(),
                fixture.receipt.artifactAttestationJson()
        );

        assertNotNull(guard(fixture, tampered, List.of(), List.of(), Set.of())
                .getBlockReason(identity(), SIZE));
    }

    /// Rejects a receipt whose derived SHA-256 was changed to another structurally valid digest.
    @Test
    public void blocksTamperedDerivedSha256() throws Exception {
        Fixture fixture = fixture();
        PluginCertificationReceipt tampered = copyReceipt(
                fixture.receipt,
                "e".repeat(64),
                REPOSITORY_ID,
                fixture.receipt.artifactSignerKeyId(),
                fixture.receipt.artifactAttestationJson()
        );

        assertNotNull(guard(fixture, tampered, List.of(), List.of(), Set.of())
                .getBlockReason(identity(), SIZE));
    }

    /// Rejects a receipt whose derived signer key ID was changed without a matching role signature.
    @Test
    public void blocksTamperedDerivedSignerKeyId() throws Exception {
        Fixture fixture = fixture();
        PluginCertificationReceipt tampered = copyReceipt(
                fixture.receipt,
                fixture.receipt.sha256(),
                REPOSITORY_ID,
                "ed25519:" + "f".repeat(64),
                fixture.receipt.artifactAttestationJson()
        );

        assertNotNull(guard(fixture, tampered, List.of(), List.of(), Set.of())
                .getBlockReason(identity(), SIZE));
    }

    /// Rejects a receipt after one signed artifact field changes without re-signing the envelope.
    @Test
    public void blocksTamperedArtifactProof() throws Exception {
        Fixture fixture = fixture();
        JsonObject tamperedProof = com.google.gson.JsonParser.parseString(
                fixture.receipt.artifactAttestationJson()
        ).getAsJsonObject();
        tamperedProof.getAsJsonObject("signed").addProperty("repositoryId", REPOSITORY_ID + 1);
        PluginCertificationReceipt tampered = copyReceipt(
                fixture.receipt,
                fixture.receipt.sha256(),
                REPOSITORY_ID,
                fixture.receipt.artifactSignerKeyId(),
                tamperedProof.toString()
        );

        assertNotNull(guard(fixture, tampered, List.of(), List.of(), Set.of())
                .getBlockReason(identity(), SIZE));
    }

    /// Treats deletion of a required proof from a present receipt document as fail-closed state.
    @Test
    public void missingProofMakesReceiptStateUnavailable(@TempDir Path temporaryDirectory) throws Exception {
        Files.writeString(
                temporaryDirectory.resolve(PluginCertificationReceiptStore.FILE_NAME),
                """
                        {
                          "schemaVersion": 1,
                          "receipts": [{
                            "pluginId": "%s",
                            "version": "%s",
                            "sha256": "%s",
                            "size": %d,
                            "repositoryId": %d,
                            "repository": "%s",
                            "repositorySignerKeyId": "ed25519:%s",
                            "artifactSignerKeyId": "ed25519:%s",
                            "repositoryVerificationId": "%s",
                            "repositoryAttestationJson": "{}"
                          }]
                        }
                        """.formatted(
                        PLUGIN_ID,
                        VERSION,
                        SHA256,
                        SIZE,
                        REPOSITORY_ID,
                        REPOSITORY,
                        "b".repeat(64),
                        "c".repeat(64),
                        VERIFICATION_ID
                ),
                StandardCharsets.UTF_8
        );

        assertThrows(
                java.io.IOException.class,
                () -> new PluginCertificationReceiptStore(temporaryDirectory).readAll()
        );
        PluginRuntimeTrustGuard unavailable = PluginRuntimeTrustGuard.unavailable("invalid receipt document");
        assertNotNull(unavailable.getBlockReason(identity(), SIZE));
    }

    /// Creates a runtime guard containing one receipt and one stale authenticated snapshot.
    ///
    /// @param fixture signed proof fixture
    /// @param receipt receipt under test
    /// @param repositories signed repository status entries
    /// @param revokedArtifacts signed exact artifact revocations
    /// @param revokedKeyIds signed online signer revocations
    /// @return runtime trust guard
    private static PluginRuntimeTrustGuard guard(
            Fixture fixture,
            PluginCertificationReceipt receipt,
            List<PluginTrustStatusSnapshot.RepositoryStatus> repositories,
            List<PluginTrustStatusSnapshot.RevokedArtifact> revokedArtifacts,
            Set<String> revokedKeyIds
    ) {
        return PluginRuntimeTrustGuard.of(
                List.of(receipt),
                snapshot(fixture, repositories, revokedArtifacts, revokedKeyIds),
                fixture.verifier
        );
    }

    /// Creates one stale but previously authenticated status snapshot.
    ///
    /// @param fixture signed proof fixture
    /// @param repositories signed repository status entries
    /// @param revokedArtifacts signed exact artifact revocations
    /// @param revokedKeyIds signed online signer revocations
    /// @return stale status snapshot
    private static PluginTrustStatusSnapshot snapshot(
            Fixture fixture,
            List<PluginTrustStatusSnapshot.RepositoryStatus> repositories,
            List<PluginTrustStatusSnapshot.RevokedArtifact> revokedArtifacts,
            Set<String> revokedKeyIds
    ) {
        return new PluginTrustStatusSnapshot(
                7,
                Instant.parse("2026-07-01T00:00:00Z"),
                Instant.parse("2026-07-02T00:00:00Z"),
                "policy-1",
                repositories,
                revokedArtifacts,
                revokedKeyIds,
                fixture.statusKeyId
        );
    }

    /// Creates one signed repository status entry.
    ///
    /// @param repositoryId immutable GitHub repository ID
    /// @param repository normalized GitHub repository identity
    /// @param status signed approval state
    /// @return repository status entry
    private static PluginTrustStatusSnapshot.RepositoryStatus repository(
            long repositoryId,
            String repository,
            String status
    ) {
        return new PluginTrustStatusSnapshot.RepositoryStatus(
                repositoryId,
                repository,
                VERIFICATION_ID,
                status,
                Instant.parse("2026-06-25T00:00:00Z"),
                Instant.parse("2026-07-02T00:00:00Z"),
                List.of(PLUGIN_ID)
        );
    }

    /// Copies one receipt while replacing fields used by tampering tests.
    ///
    /// @param original verified source receipt
    /// @param sha256 replacement derived digest
    /// @param repositoryId replacement derived repository ID
    /// @param artifactKeyId replacement derived artifact signer
    /// @param artifactProof replacement artifact proof JSON
    /// @return copied receipt
    private static PluginCertificationReceipt copyReceipt(
            PluginCertificationReceipt original,
            String sha256,
            long repositoryId,
            String artifactKeyId,
            String artifactProof
    ) {
        return new PluginCertificationReceipt(
                original.pluginId(),
                original.version(),
                sha256,
                original.size(),
                repositoryId,
                original.repository(),
                original.repositorySignerKeyId(),
                artifactKeyId,
                original.repositoryVerificationId(),
                artifactProof,
                original.repositoryAttestationJson()
        );
    }

    /// Creates a verifier and two independently role-signed historical proof envelopes.
    ///
    /// @return signed proof fixture
    private static Fixture fixture() throws Exception {
        KeyPair repositorySigner = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        KeyPair artifactSigner = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        KeyPair statusSigner = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        PluginTrustVerifier verifier = verifier(repositorySigner, artifactSigner, statusSigner);
        JsonObject repositoryProof = repositoryProof(repositorySigner);
        JsonObject artifactProof = artifactProof(artifactSigner);
        PluginVerifiedCertification certification = verifier.verifyInstalledCertification(
                artifactProof,
                repositoryProof,
                PLUGIN_ID,
                VERSION,
                SHA256,
                SIZE
        );
        return new Fixture(
                verifier,
                PluginCertificationReceipt.fromVerified(certification, artifactProof, repositoryProof),
                keyId(statusSigner)
        );
    }

    /// Creates a verifier with independent repository, artifact, and status role keys.
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

    /// Creates one Ed25519 public-key declaration for the root.
    private static JsonObject keyDeclaration(KeyPair signer) {
        JsonObject declaration = new JsonObject();
        declaration.addProperty("keyType", "ed25519");
        declaration.addProperty("scheme", "ed25519");
        declaration.addProperty("publicKey", Base64.getEncoder().encodeToString(signer.getPublic().getEncoded()));
        return declaration;
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

    /// Creates one expired but historically valid repository proof.
    private static JsonObject repositoryProof(KeyPair signer) throws Exception {
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
        pluginIds.add(PLUGIN_ID);
        signed.add("pluginIds", pluginIds);
        signed.addProperty("status", "approved");
        signed.addProperty("checkedAt", "2026-07-01T00:00:00Z");
        signed.addProperty("validUntil", "2026-07-08T00:00:00Z");
        signed.addProperty("policyVersion", "policy-1");
        signed.addProperty("sourceCommit", "0123456789abcdef0123456789abcdef01234567");
        signed.addProperty("verificationId", VERIFICATION_ID);
        return envelope(signed, signer, PluginTrustVerifier.REPOSITORY_ATTESTATION_DOMAIN);
    }

    /// Creates one artifact proof approved within the historical repository interval.
    private static JsonObject artifactProof(KeyPair signer) throws Exception {
        JsonObject signed = new JsonObject();
        signed.addProperty("_type", "npl-attestation");
        signed.addProperty("schemaVersion", 1);
        signed.addProperty("repository", REPOSITORY);
        signed.addProperty("repositoryId", REPOSITORY_ID);
        signed.addProperty("pluginId", PLUGIN_ID);
        signed.addProperty("version", VERSION);
        signed.addProperty("tag", "v" + VERSION);
        signed.addProperty("assetUrl", "https://github.com/hmclce/example-plugin/releases/download/v1.2.3/plugin.npl");
        signed.addProperty("assetName", "plugin.npl");
        signed.addProperty("sha256", SHA256);
        signed.addProperty("size", SIZE);
        signed.addProperty("repositoryVerificationId", VERIFICATION_ID);
        signed.addProperty("sourceCommit", "0123456789abcdef0123456789abcdef01234567");
        signed.addProperty("policyVersion", "policy-1");
        signed.addProperty("jobId", "job-17");
        signed.addProperty("approvedAt", "2026-07-02T00:00:00Z");
        return envelope(signed, signer, PluginTrustVerifier.NPL_ATTESTATION_DOMAIN);
    }

    /// Wraps and signs one canonical payload for an explicit role domain.
    private static JsonObject envelope(JsonObject signed, KeyPair signer, String domain) throws Exception {
        JsonObject envelope = new JsonObject();
        envelope.add("signed", signed);
        envelope.add("signatures", new JsonArray());
        Signature signature = Signature.getInstance("Ed25519");
        signature.initSign(signer.getPrivate());
        signature.update(CanonicalJson.signatureInput(domain, signed));
        JsonObject entry = new JsonObject();
        entry.addProperty("keyId", keyId(signer));
        entry.addProperty("signature", Base64.getEncoder().encodeToString(signature.sign()));
        JsonArray signatures = new JsonArray();
        signatures.add(entry);
        envelope.add("signatures", signatures);
        return envelope;
    }

    /// Computes the wire key ID for one Ed25519 pair.
    private static String keyId(KeyPair pair) throws Exception {
        return "ed25519:" + HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(pair.getPublic().getEncoded())
        );
    }

    /// Creates the exact installed artifact identity.
    private static PluginArtifactIdentity identity() {
        return new PluginArtifactIdentity(PLUGIN_ID, VERSION, SHA256);
    }

    /// Signed proof fixture and its root verifier.
    ///
    /// @param verifier test root verifier
    /// @param receipt exact proof-backed receipt
    /// @param statusKeyId root-authorized status signer key ID
    private record Fixture(
            PluginTrustVerifier verifier,
            PluginCertificationReceipt receipt,
            String statusKeyId
    ) {
    }
}
