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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies role separation, developer constraints, and downgrade resistance for plugin metadata.
@NotNullByDefault
public final class PluginTrustVerifierTest {
    /// Stable validation instant used by all certificate fixtures.
    private static final Instant NOW = Instant.parse("2026-08-14T08:00:00Z");

    /// Developer CA signing pair.
    private KeyPair developerCa;

    /// Official repository signing pair.
    private KeyPair officialRepository;

    /// Weekly repository-attestation signing pair.
    private KeyPair repositoryAttestor;

    /// Per-release artifact-attestation signing pair.
    private KeyPair artifactAttestor;

    /// Online trust-status snapshot signing pair.
    private KeyPair trustStatusSigner;

    /// Plugin developer signing pair.
    private KeyPair developer;

    /// Verifier initialized from the role-separated fixture root.
    private PluginTrustVerifier verifier;

    /// Creates independent Ed25519 role keys before each test.
    @BeforeEach
    public void setUp() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
        developerCa = generator.generateKeyPair();
        officialRepository = generator.generateKeyPair();
        repositoryAttestor = generator.generateKeyPair();
        artifactAttestor = generator.generateKeyPair();
        trustStatusSigner = generator.generateKeyPair();
        developer = generator.generateKeyPair();
        verifier = PluginTrustVerifier.fromRoot(
                root(developerCa, officialRepository, repositoryAttestor, artifactAttestor, trustStatusSigner),
                Clock.fixed(NOW, ZoneOffset.UTC),
                Set.of(),
                Set.of()
        );
    }

    /// Treats a manifest with no signing declaration as installable community content.
    @Test
    public void acceptsUnsignedCommunityManifest() {
        JsonObject manifest = manifestPayload("dev.example.plugin", "github.com/example/plugin");

        PluginDocumentVerification result = verifier.verifyManifest(
                manifest,
                "dev.example.plugin",
                "github.com/example/plugin"
        );

        assertEquals(PluginTrustLevel.COMMUNITY, result.trust().level());
        assertTrue(result.trust().canInstall());
        assertEquals(manifest, result.signed());
    }

    /// Retains legacy signed manifests for parsing without granting current certification.
    @Test
    public void certifiesBoundDeveloperManifest() throws Exception {
        JsonObject certificate = developerCertificate(
                developerCa,
                developer,
                "serial-1",
                "dev.example.plugin",
                "github.com/example/plugin"
        );
        JsonObject payload = manifestPayload("dev.example.plugin", "github.com/example/plugin");
        JsonObject document = envelope(
                PluginTrustVerifier.MANIFEST_DOMAIN,
                payload,
                developer.getPrivate(),
                keyId(developer),
                certificate
        );

        PluginDocumentVerification result = verifier.verifyManifest(
                document,
                "dev.example.plugin",
                "github.com/example/plugin"
        );

        assertEquals(PluginTrustLevel.COMMUNITY, result.trust().level());
        assertTrue(result.trust().requiresSourceWarning());
    }

    /// Rejects a valid signature when the certificate is reused from another GitHub repository.
    @Test
    public void rejectsRepositoryMismatch() throws Exception {
        JsonObject certificate = developerCertificate(
                developerCa,
                developer,
                "serial-2",
                "dev.example.plugin",
                "github.com/example/original"
        );
        JsonObject payload = manifestPayload("dev.example.plugin", "github.com/example/copied");
        JsonObject document = envelope(
                PluginTrustVerifier.MANIFEST_DOMAIN,
                payload,
                developer.getPrivate(),
                keyId(developer),
                certificate
        );

        PluginDocumentVerification result = verifier.verifyManifest(
                document,
                "dev.example.plugin",
                "github.com/example/copied"
        );

        assertEquals(PluginTrustLevel.REJECTED, result.trust().level());
        assertFalse(result.trust().canInstall());
    }

    /// Rejects partially removed signing material instead of downgrading it to community content.
    @Test
    public void rejectsPartialSigningDeclaration() {
        JsonObject document = new JsonObject();
        document.add("signed", manifestPayload("dev.example.plugin", "github.com/example/plugin"));

        PluginDocumentVerification result = verifier.verifyManifest(
                document,
                "dev.example.plugin",
                "github.com/example/plugin"
        );

        assertEquals(PluginTrustLevel.REJECTED, result.trust().level());
    }

    /// Accepts an official registry signed by the official repository role.
    @Test
    public void acceptsOfficialRepositoryRole() throws Exception {
        JsonObject registry = new JsonObject();
        registry.addProperty("schemaVersion", 1);
        registry.addProperty("name", "HMCL CE Official Plugins");
        registry.add("plugins", new JsonArray());
        JsonObject document = envelope(
                PluginTrustVerifier.OFFICIAL_REGISTRY_DOMAIN,
                registry,
                officialRepository.getPrivate(),
                keyId(officialRepository),
                null
        );

        PluginDocumentVerification result = verifier.verifyOfficialRegistry(document);

        assertEquals(PluginTrustLevel.OFFICIAL, result.trust().level());
        assertEquals(registry, result.signed());
    }

    /// Grants certification only when the weekly repository proof, release proof, and fresh status snapshot agree.
    @Test
    public void certifiesVersionWithDualAttestationsAndFreshStatus() throws Exception {
        PluginRepositoryAttestation repository = verifier.verifyRepositoryAttestation(
                repositoryAttestation(repositoryAttestor, "approved", "verification-17")
        );
        PluginTrustStatusSnapshot status = verifier.verifyTrustStatusSnapshot(
                trustStatusSnapshot(trustStatusSigner, "approved", "verification-17", new JsonArray())
        );

        PluginTrustResult result = verifier.verifyArtifactAttestation(
                artifactAttestation(artifactAttestor, "1.2.3", HASH_A, 42, "verification-17"),
                repository,
                status,
                "github.com/example/plugin",
                "dev.example.plugin",
                "1.2.3",
                "https://github.com/example/plugin/releases/download/v1.2.3/plugin.npl",
                HASH_A,
                42
        );

        assertEquals(PluginTrustLevel.CERTIFIED, result.level());
        assertTrue(result.canInstall());
    }

    /// Keeps an older NPL valid across weekly repository rotation by verifying its referenced historical proof.
    @Test
    public void certifiesHistoricalRepositoryProofAfterWeeklyRotation() throws Exception {
        JsonObject historicalDocument = repositoryAttestation(repositoryAttestor, "approved", "verification-17");
        historicalDocument.getAsJsonObject("signed").addProperty("validUntil", "2026-08-14T07:30:00Z");
        PluginRepositoryAttestation repository = verifier.verifyHistoricalRepositoryAttestation(
                resign(PluginTrustVerifier.REPOSITORY_ATTESTATION_DOMAIN, historicalDocument, repositoryAttestor)
        );
        PluginTrustStatusSnapshot status = verifier.verifyTrustStatusSnapshot(
                trustStatusSnapshot(trustStatusSigner, "approved", "verification-18", new JsonArray())
        );

        PluginTrustResult result = verifier.verifyArtifactAttestation(
                artifactAttestation(artifactAttestor, "1.2.3", HASH_A, 42, "verification-17"),
                repository,
                status,
                "github.com/example/plugin",
                "dev.example.plugin",
                "1.2.3",
                "https://github.com/example/plugin/releases/download/v1.2.3/plugin.npl",
                HASH_A,
                42
        );

        assertEquals(PluginTrustLevel.CERTIFIED, result.level());
    }

    /// Rejects signed NPL metadata that is inconsistent with its selected version or historical repository proof.
    @Test
    public void rejectsTagCommitPolicyAndApprovalWindowMismatch() throws Exception {
        PluginRepositoryAttestation repository = verifier.verifyRepositoryAttestation(
                repositoryAttestation(repositoryAttestor, "approved", "verification-17")
        );
        PluginTrustStatusSnapshot status = verifier.verifyTrustStatusSnapshot(
                trustStatusSnapshot(trustStatusSigner, "approved", "verification-18", new JsonArray())
        );

        assertRejectedMutation(repository, status, "tag", "v9.9.9");
        assertRejectedMutation(repository, status, "sourceCommit", "ffffffffffffffffffffffffffffffffffffffff");
        assertRejectedMutation(repository, status, "policyVersion", "different-policy");
        assertRejectedMutation(repository, status, "approvedAt", "2026-08-13T07:59:59Z");
    }

    /// Rejects certification after the current repository missed its signed next-verification deadline.
    @Test
    public void rejectsRepositoryWhoseNextVerificationIsPast() throws Exception {
        PluginRepositoryAttestation repository = verifier.verifyRepositoryAttestation(
                repositoryAttestation(repositoryAttestor, "approved", "verification-17")
        );
        JsonObject statusDocument = trustStatusSnapshot(
                trustStatusSigner, "approved", "verification-18", new JsonArray()
        );
        statusDocument.getAsJsonObject("signed").getAsJsonArray("repositories").get(0)
                .getAsJsonObject().addProperty("nextVerificationAt", "2026-08-14T07:30:00Z");
        PluginTrustStatusSnapshot status = verifier.verifyTrustStatusSnapshot(
                resign(PluginTrustVerifier.TRUST_STATUS_DOMAIN, statusDocument, trustStatusSigner)
        );

        assertEquals(PluginTrustLevel.REJECTED, verifier.verifyArtifactAttestation(
                artifactAttestation(artifactAttestor, "1.2.3", HASH_A, 42, "verification-17"),
                repository, status, "github.com/example/plugin", "dev.example.plugin", "1.2.3",
                "https://github.com/example/plugin/releases/download/v1.2.3/plugin.npl", HASH_A, 42
        ).level());
    }

    /// Rejects proofs made by either online attestation key after a signed key-ID revocation.
    @Test
    public void rejectsRevokedRepositoryOrArtifactSigningKey() throws Exception {
        PluginRepositoryAttestation repository = verifier.verifyRepositoryAttestation(
                repositoryAttestation(repositoryAttestor, "approved", "verification-17")
        );
        for (String revokedKeyId : Set.of(keyId(repositoryAttestor), keyId(artifactAttestor))) {
            JsonObject statusDocument = trustStatusSnapshot(
                    trustStatusSigner, "approved", "verification-18", new JsonArray()
            );
            statusDocument.getAsJsonObject("signed").getAsJsonArray("revokedKeyIds").add(revokedKeyId);
            PluginTrustStatusSnapshot status = verifier.verifyTrustStatusSnapshot(
                    resign(PluginTrustVerifier.TRUST_STATUS_DOMAIN, statusDocument, trustStatusSigner)
            );

            assertEquals(PluginTrustLevel.REJECTED, verifier.verifyArtifactAttestation(
                    artifactAttestation(artifactAttestor, "1.2.3", HASH_A, 42, "verification-17"),
                    repository, status, "github.com/example/plugin", "dev.example.plugin", "1.2.3",
                    "https://github.com/example/plugin/releases/download/v1.2.3/plugin.npl", HASH_A, 42
            ).level());
        }
    }

    /// Refuses a repository whose online status was suspended after its weekly proof was issued.
    @Test
    public void rejectsSuspendedRepositoryStatus() throws Exception {
        PluginRepositoryAttestation repository = verifier.verifyRepositoryAttestation(
                repositoryAttestation(repositoryAttestor, "approved", "verification-17")
        );
        PluginTrustStatusSnapshot status = verifier.verifyTrustStatusSnapshot(
                trustStatusSnapshot(trustStatusSigner, "suspended", "verification-17", new JsonArray())
        );

        PluginTrustResult result = verifier.verifyArtifactAttestation(
                artifactAttestation(artifactAttestor, "1.2.3", HASH_A, 42, "verification-17"),
                repository,
                status,
                "github.com/example/plugin",
                "dev.example.plugin",
                "1.2.3",
                "https://github.com/example/plugin/releases/download/v1.2.3/plugin.npl",
                HASH_A,
                42
        );

        assertEquals(PluginTrustLevel.REJECTED, result.level());
        assertTrue(result.detail().contains("suspended"));
    }

    /// Refuses exact artifacts listed by the online revocation snapshot.
    @Test
    public void rejectsRevokedArtifact() throws Exception {
        JsonArray revokedArtifacts = new JsonArray();
        JsonObject revoked = new JsonObject();
        revoked.addProperty("sha256", HASH_A);
        revoked.addProperty("pluginId", "dev.example.plugin");
        revoked.addProperty("version", "1.2.3");
        revoked.addProperty("revokedAt", "2026-08-14T07:30:00Z");
        revoked.addProperty("reasonCode", "malware");
        revokedArtifacts.add(revoked);
        PluginRepositoryAttestation repository = verifier.verifyRepositoryAttestation(
                repositoryAttestation(repositoryAttestor, "approved", "verification-17")
        );
        PluginTrustStatusSnapshot status = verifier.verifyTrustStatusSnapshot(
                trustStatusSnapshot(trustStatusSigner, "approved", "verification-17", revokedArtifacts)
        );

        PluginTrustResult result = verifier.verifyArtifactAttestation(
                artifactAttestation(artifactAttestor, "1.2.3", HASH_A, 42, "verification-17"),
                repository,
                status,
                "github.com/example/plugin",
                "dev.example.plugin",
                "1.2.3",
                "https://github.com/example/plugin/releases/download/v1.2.3/plugin.npl",
                HASH_A,
                42
        );

        assertEquals(PluginTrustLevel.REJECTED, result.level());
        assertTrue(result.detail().contains("revoked"));
    }

    /// Rejects two revocation records for the same artifact bytes even when their descriptive fields differ.
    @Test
    public void rejectsDuplicateRevokedArtifactShaAcrossMetadata() throws Exception {
        JsonObject first = new JsonObject();
        first.addProperty("sha256", HASH_A);
        first.addProperty("pluginId", "dev.example.plugin");
        first.addProperty("version", "1.2.3");
        first.addProperty("revokedAt", "2026-08-14T07:30:00Z");
        first.addProperty("reasonCode", "malware");
        JsonObject second = first.deepCopy();
        second.addProperty("pluginId", "dev.example.renamed");
        second.addProperty("version", "9.9.9");
        JsonArray revokedArtifacts = new JsonArray();
        revokedArtifacts.add(first);
        revokedArtifacts.add(second);

        JsonObject snapshot = trustStatusSnapshot(
                trustStatusSigner,
                "approved",
                "verification-17",
                revokedArtifacts
        );

        assertThrows(IllegalArgumentException.class, () -> verifier.verifyTrustStatusSnapshot(snapshot));
    }

    /// Accepts the inclusive JavaScript-safe upper boundary for a signed protocol counter.
    @Test
    public void acceptsMaximumSafeStatusVersion() throws Exception {
        JsonObject snapshot = trustStatusSnapshot(
                trustStatusSigner,
                "approved",
                "verification-17",
                new JsonArray()
        );
        snapshot.getAsJsonObject("signed").addProperty("version", 9_007_199_254_740_991L);
        snapshot = resign(PluginTrustVerifier.TRUST_STATUS_DOMAIN, snapshot, trustStatusSigner);

        assertEquals(9_007_199_254_740_991L, verifier.verifyTrustStatusSnapshot(snapshot).version());
    }

    /// Binds certification to the selected version, URL, checksum, and size rather than the surrounding manifest.
    @Test
    public void rejectsCrossVersionAndArtifactFieldSubstitution() throws Exception {
        PluginRepositoryAttestation repository = verifier.verifyRepositoryAttestation(
                repositoryAttestation(repositoryAttestor, "approved", "verification-17")
        );
        PluginTrustStatusSnapshot status = verifier.verifyTrustStatusSnapshot(
                trustStatusSnapshot(trustStatusSigner, "approved", "verification-17", new JsonArray())
        );
        JsonObject proof = artifactAttestation(
                artifactAttestor, "1.2.3", HASH_A, 42, "verification-17"
        );

        assertEquals(PluginTrustLevel.REJECTED, verifier.verifyArtifactAttestation(
                proof, repository, status, "github.com/example/plugin", "dev.example.plugin", "9.9.9",
                "https://github.com/example/plugin/releases/download/v1.2.3/plugin.npl", HASH_A, 42
        ).level());
        assertEquals(PluginTrustLevel.REJECTED, verifier.verifyArtifactAttestation(
                proof, repository, status, "github.com/example/plugin", "dev.example.plugin", "1.2.3",
                "https://github.com/example/plugin/releases/download/v1.2.3/other.npl", HASH_A, 42
        ).level());
        assertEquals(PluginTrustLevel.REJECTED, verifier.verifyArtifactAttestation(
                proof, repository, status, "github.com/example/plugin", "dev.example.plugin", "1.2.3",
                "https://github.com/example/plugin/releases/download/v1.2.3/plugin.npl", HASH_B, 42
        ).level());
        assertEquals(PluginTrustLevel.REJECTED, verifier.verifyArtifactAttestation(
                proof, repository, status, "github.com/example/plugin", "dev.example.plugin", "1.2.3",
                "https://github.com/example/plugin/releases/download/v1.2.3/plugin.npl", HASH_A, 43
        ).level());
    }

    /// Keeps root roles non-interchangeable even though every role uses Ed25519.
    @Test
    public void rejectsRoleConfusion() throws Exception {
        JsonObject repositorySignedByArtifactRole = repositoryAttestation(
                artifactAttestor, "approved", "verification-17"
        );

        assertThrows(IllegalArgumentException.class,
                () -> verifier.verifyRepositoryAttestation(repositorySignedByArtifactRole));
    }

    /// Rejects a proof after any signed artifact field is changed without recomputing its signature.
    @Test
    public void rejectsSignatureMutation() throws Exception {
        JsonObject mutated = artifactAttestation(artifactAttestor, "1.2.3", HASH_A, 42, "verification-17");
        mutated.getAsJsonObject("signed").addProperty("size", 43);
        PluginRepositoryAttestation repository = verifier.verifyRepositoryAttestation(
                repositoryAttestation(repositoryAttestor, "approved", "verification-17")
        );
        PluginTrustStatusSnapshot status = verifier.verifyTrustStatusSnapshot(
                trustStatusSnapshot(trustStatusSigner, "approved", "verification-17", new JsonArray())
        );

        assertEquals(PluginTrustLevel.REJECTED, verifier.verifyArtifactAttestation(
                mutated, repository, status, "github.com/example/plugin", "dev.example.plugin", "1.2.3",
                "https://github.com/example/plugin/releases/download/v1.2.3/plugin.npl", HASH_A, 43
        ).level());
    }

    /// Exposes only the status endpoint embedded in the root metadata.
    @Test
    public void exposesFixedRootStatusUrl() {
        assertEquals("https://trust.hmclce.example/status.json", verifier.getTrustStatusUrl());
    }

    /// Creates embedded root metadata with separately constrained developer CA and repository roles.
    private static JsonObject root(
            KeyPair developerCa,
            KeyPair officialRepository,
            KeyPair repositoryAttestor,
            KeyPair artifactAttestor,
            KeyPair trustStatusSigner
    ) throws Exception {
        JsonObject signed = new JsonObject();
        signed.addProperty("_type", "root");
        signed.addProperty("schemaVersion", 1);
        signed.addProperty("version", 1);
        signed.addProperty("expires", "2036-01-01T00:00:00Z");
        signed.addProperty("statusUrl", "https://trust.hmclce.example/status.json");
        JsonObject keys = new JsonObject();
        keys.add(keyId(developerCa), key(developerCa));
        keys.add(keyId(officialRepository), key(officialRepository));
        keys.add(keyId(repositoryAttestor), key(repositoryAttestor));
        keys.add(keyId(artifactAttestor), key(artifactAttestor));
        keys.add(keyId(trustStatusSigner), key(trustStatusSigner));
        signed.add("keys", keys);
        JsonObject roles = new JsonObject();
        roles.add("developer-ca", role(keyId(developerCa)));
        roles.add("official-repository", role(keyId(officialRepository)));
        roles.add("repository-attestor", role(keyId(repositoryAttestor)));
        roles.add("artifact-attestor", role(keyId(artifactAttestor)));
        roles.add("trust-status", role(keyId(trustStatusSigner)));
        signed.add("roles", roles);
        JsonObject root = new JsonObject();
        root.add("signed", signed);
        root.add("signatures", new JsonArray());
        return root;
    }

    /// Stable first artifact checksum.
    private static final String HASH_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    /// Stable different artifact checksum.
    private static final String HASH_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

    /// Creates one weekly repository proof signed by the requested role key.
    private static JsonObject repositoryAttestation(KeyPair signer, String status, String verificationId) throws Exception {
        JsonObject signed = new JsonObject();
        signed.addProperty("_type", "repository-attestation");
        signed.addProperty("schemaVersion", 1);
        signed.addProperty("repository", "github.com/example/plugin");
        signed.addProperty("repositoryId", 1701);
        signed.addProperty("defaultBranch", "main");
        JsonArray topics = new JsonArray();
        topics.add("HMCLCE");
        signed.add("topics", topics);
        JsonArray pluginIds = new JsonArray();
        pluginIds.add("dev.example.plugin");
        signed.add("pluginIds", pluginIds);
        signed.addProperty("status", status);
        signed.addProperty("checkedAt", "2026-08-13T08:00:00Z");
        signed.addProperty("validUntil", "2026-08-20T08:00:00Z");
        signed.addProperty("policyVersion", "2026-08");
        signed.addProperty("sourceCommit", "0123456789abcdef0123456789abcdef01234567");
        signed.addProperty("verificationId", verificationId);
        return envelope(
                PluginTrustVerifier.REPOSITORY_ATTESTATION_DOMAIN,
                signed,
                signer.getPrivate(),
                keyId(signer),
                null
        );
    }

    /// Creates one per-release NPL proof signed by the requested role key.
    private static JsonObject artifactAttestation(
            KeyPair signer,
            String version,
            String sha256,
            long size,
            String repositoryVerificationId
    ) throws Exception {
        JsonObject signed = new JsonObject();
        signed.addProperty("_type", "npl-attestation");
        signed.addProperty("schemaVersion", 1);
        signed.addProperty("repository", "github.com/example/plugin");
        signed.addProperty("repositoryId", 1701);
        signed.addProperty("pluginId", "dev.example.plugin");
        signed.addProperty("version", version);
        signed.addProperty("tag", "v" + version);
        signed.addProperty("assetName", "plugin.npl");
        signed.addProperty("assetUrl", "https://github.com/example/plugin/releases/download/v1.2.3/plugin.npl");
        signed.addProperty("sha256", sha256);
        signed.addProperty("size", size);
        signed.addProperty("sourceCommit", "0123456789abcdef0123456789abcdef01234567");
        signed.addProperty("repositoryVerificationId", repositoryVerificationId);
        signed.addProperty("approvedAt", "2026-08-14T07:00:00Z");
        signed.addProperty("policyVersion", "2026-08");
        signed.addProperty("jobId", "job-42");
        return envelope(
                PluginTrustVerifier.NPL_ATTESTATION_DOMAIN,
                signed,
                signer.getPrivate(),
                keyId(signer),
                null
        );
    }

    /// Creates one short-lived online trust-status snapshot.
    private static JsonObject trustStatusSnapshot(
            KeyPair signer,
            String status,
            String verificationId,
            JsonArray revokedArtifacts
    ) throws Exception {
        JsonObject signed = new JsonObject();
        signed.addProperty("_type", "trust-status-snapshot");
        signed.addProperty("schemaVersion", 1);
        signed.addProperty("version", 4);
        signed.addProperty("generatedAt", "2026-08-14T07:00:00Z");
        signed.addProperty("expires", "2026-08-15T08:00:00Z");
        signed.addProperty("policyVersion", "2026-08");
        JsonObject repository = new JsonObject();
        repository.addProperty("repositoryId", 1701);
        repository.addProperty("repository", "github.com/example/plugin");
        repository.addProperty("verificationId", verificationId);
        repository.addProperty("status", status);
        repository.addProperty("lastVerifiedAt", "2026-08-13T08:00:00Z");
        repository.addProperty("nextVerificationAt", "2026-08-20T08:00:00Z");
        JsonArray pluginIds = new JsonArray();
        pluginIds.add("dev.example.plugin");
        repository.add("pluginIds", pluginIds);
        JsonArray repositories = new JsonArray();
        repositories.add(repository);
        signed.add("repositories", repositories);
        signed.add("revokedArtifacts", revokedArtifacts);
        signed.add("revokedKeyIds", new JsonArray());
        return envelope(
                PluginTrustVerifier.TRUST_STATUS_DOMAIN,
                signed,
                signer.getPrivate(),
                keyId(signer),
                null
        );
    }

    /// Re-signs the mutated payload from one existing envelope.
    private static JsonObject resign(String domain, JsonObject document, KeyPair signer) throws Exception {
        return envelope(
                domain,
                document.getAsJsonObject("signed"),
                signer.getPrivate(),
                keyId(signer),
                null
        );
    }

    /// Asserts rejection after replacing one signed NPL field and recomputing a valid role signature.
    private void assertRejectedMutation(
            PluginRepositoryAttestation repository,
            PluginTrustStatusSnapshot status,
            String field,
            String value
    ) throws Exception {
        JsonObject document = artifactAttestation(artifactAttestor, "1.2.3", HASH_A, 42, "verification-17");
        document.getAsJsonObject("signed").addProperty(field, value);
        JsonObject resigned = resign(PluginTrustVerifier.NPL_ATTESTATION_DOMAIN, document, artifactAttestor);
        assertEquals(PluginTrustLevel.REJECTED, verifier.verifyArtifactAttestation(
                resigned, repository, status, "github.com/example/plugin", "dev.example.plugin", "1.2.3",
                "https://github.com/example/plugin/releases/download/v1.2.3/plugin.npl", HASH_A, 42
        ).level());
    }

    /// Creates one single-key threshold role.
    private static JsonObject role(String keyId) {
        JsonObject role = new JsonObject();
        JsonArray keyIds = new JsonArray();
        keyIds.add(keyId);
        role.add("keyIds", keyIds);
        role.addProperty("threshold", 1);
        return role;
    }

    /// Creates one Ed25519 public-key declaration.
    private static JsonObject key(KeyPair pair) {
        JsonObject key = new JsonObject();
        key.addProperty("keyType", "ed25519");
        key.addProperty("scheme", "ed25519");
        key.addProperty("publicKey", Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()));
        return key;
    }

    /// Creates a CA-signed developer certificate with exact plugin and repository constraints.
    private static JsonObject developerCertificate(
            KeyPair ca,
            KeyPair developer,
            String serial,
            String pluginId,
            String repository
    ) throws Exception {
        JsonObject signed = new JsonObject();
        signed.addProperty("_type", "developer-certificate");
        signed.addProperty("schemaVersion", 1);
        signed.addProperty("serial", serial);
        signed.addProperty("subject", "Example Developer");
        signed.add("key", key(developer));
        signed.addProperty("keyId", keyId(developer));
        JsonArray pluginIds = new JsonArray();
        pluginIds.add(pluginId);
        signed.add("pluginIds", pluginIds);
        signed.add("pluginIdPrefixes", new JsonArray());
        JsonArray repositories = new JsonArray();
        repositories.add(repository);
        signed.add("repositories", repositories);
        JsonArray usages = new JsonArray();
        usages.add("plugin-manifest");
        signed.add("usages", usages);
        signed.addProperty("notBefore", "2026-01-01T00:00:00Z");
        signed.addProperty("notAfter", "2027-01-01T00:00:00Z");
        return envelope(
                PluginTrustVerifier.DEVELOPER_CERTIFICATE_DOMAIN,
                signed,
                ca.getPrivate(),
                keyId(ca),
                null
        );
    }

    /// Creates a compact manifest payload.
    private static JsonObject manifestPayload(String pluginId, String repository) {
        JsonObject payload = new JsonObject();
        payload.addProperty("schemaVersion", 2);
        payload.addProperty("id", pluginId);
        payload.addProperty("repository", repository);
        payload.add("versions", new JsonArray());
        return payload;
    }

    /// Creates and signs one envelope with an optional developer certificate.
    private static JsonObject envelope(
            String domain,
            JsonObject signed,
            PrivateKey privateKey,
            String keyId,
            JsonObject certificate
    ) throws Exception {
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(privateKey);
        signer.update(CanonicalJson.signatureInput(domain, signed));
        JsonObject signature = new JsonObject();
        signature.addProperty("keyId", keyId);
        signature.addProperty("signature", Base64.getEncoder().encodeToString(signer.sign()));
        JsonArray signatures = new JsonArray();
        signatures.add(signature);
        JsonObject document = new JsonObject();
        document.add("signed", signed);
        document.add("signatures", signatures);
        if (certificate != null) {
            document.add("certificate", certificate);
        }
        return document;
    }

    /// Computes the wire key ID for one Ed25519 pair.
    private static String keyId(KeyPair pair) throws Exception {
        return keyIdFromPublicKey(pair.getPublic());
    }

    /// Computes the wire key ID for one encoded public key.
    private static String keyIdFromPublicKey(java.security.PublicKey publicKey) throws Exception {
        return "ed25519:" + HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(publicKey.getEncoded())
        );
    }
}
