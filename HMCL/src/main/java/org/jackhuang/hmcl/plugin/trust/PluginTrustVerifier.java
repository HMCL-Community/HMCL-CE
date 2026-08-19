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
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.math.BigDecimal;
import java.util.Base64;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/// Verifies official repositories and constrained developer plugin manifests against one embedded trust root.
@NotNullByDefault
public final class PluginTrustVerifier {
    /// Signature domain for official registry documents.
    public static final String OFFICIAL_REGISTRY_DOMAIN = "HMCLCE-OFFICIAL-REGISTRY-V1";

    /// Signature domain for developer certificates.
    public static final String DEVELOPER_CERTIFICATE_DOMAIN = "HMCLCE-DEVELOPER-CERTIFICATE-V1";

    /// Signature domain for certified plugin manifests.
    public static final String MANIFEST_DOMAIN = "HMCLCE-PLUGIN-MANIFEST-V1";

    /// Signature domain for weekly repository approval proofs.
    public static final String REPOSITORY_ATTESTATION_DOMAIN = "HMCLCE-REPOSITORY-ATTESTATION-V1";

    /// Signature domain for per-release NPL artifact proofs.
    public static final String NPL_ATTESTATION_DOMAIN = "HMCLCE-NPL-ATTESTATION-V1";

    /// Signature domain for the short-lived online trust-status snapshot.
    public static final String TRUST_STATUS_DOMAIN = "HMCLCE-TRUST-STATUS-V1";

    /// Root role allowed to issue developer certificates.
    private static final String DEVELOPER_CA_ROLE = "developer-ca";

    /// Root role allowed to publish the official registry.
    private static final String OFFICIAL_REPOSITORY_ROLE = "official-repository";

    /// Root role allowed to approve a repository after its weekly inspection.
    private static final String REPOSITORY_ATTESTOR_ROLE = "repository-attestor";

    /// Root role allowed to attest an exact NPL release artifact.
    private static final String ARTIFACT_ATTESTOR_ROLE = "artifact-attestor";

    /// Root role allowed to publish online suspension and revocation state.
    private static final String TRUST_STATUS_ROLE = "trust-status";

    /// Maximum lifetime accepted for one weekly repository proof.
    private static final Duration MAX_REPOSITORY_WINDOW = Duration.ofDays(8);

    /// Maximum lifetime accepted for one online status snapshot.
    private static final Duration MAX_STATUS_WINDOW = Duration.ofHours(48);

    /// Small allowance for clocks that run behind the signing service.
    private static final Duration CLOCK_SKEW = Duration.ofMinutes(5);

    /// Exact hexadecimal representation of an artifact digest.
    private static final Pattern SHA256_PATTERN = Pattern.compile("[0-9a-fA-F]{64}");

    /// Exact lowercase Ed25519 key ID used by online revocation snapshots.
    private static final Pattern KEY_ID_PATTERN = Pattern.compile("ed25519:[0-9a-f]{64}");

    /// Safe opaque repository verification ID accepted as one fixed API path segment.
    private static final Pattern VERIFICATION_ID_PATTERN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

    /// Parsed immutable trust root.
    private final PluginTrustRoot root;

    /// Validation clock for certificate intervals.
    private final Clock clock;

    /// Revoked developer certificate serial numbers.
    private final @Unmodifiable Set<String> revokedSerials;

    /// Revoked developer key IDs.
    private final @Unmodifiable Set<String> revokedKeyIds;

    /// Creates a verifier from explicit root metadata.
    public static PluginTrustVerifier fromRoot(
            JsonObject root,
            Clock clock,
            @Unmodifiable Set<String> revokedSerials,
            @Unmodifiable Set<String> revokedKeyIds
    ) {
        return new PluginTrustVerifier(new PluginTrustRoot(root), clock, revokedSerials, revokedKeyIds);
    }

    /// Loads the production root embedded during the HMCL CE build.
    public static PluginTrustVerifier loadDefault() throws IOException {
        try (InputStream input = PluginTrustVerifier.class.getResourceAsStream("/assets/hmclce-plugin-root.json")) {
            if (input == null) {
                throw new IOException("Embedded HMCL CE plugin trust root is missing");
            }
            JsonElement parsed = JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) {
                throw new IOException("Embedded HMCL CE plugin trust root is not an object");
            }
            JsonObject root = parsed.getAsJsonObject();
            JsonObject signed = PluginTrustRoot.object(root, "signed");
            Clock clock = Clock.systemUTC();
            Instant rootExpiry = Instant.parse(PluginTrustRoot.string(signed, "expires"));
            if (!clock.instant().isBefore(rootExpiry)) {
                throw new IllegalArgumentException("Embedded HMCL CE plugin trust root has expired");
            }
            return fromRoot(
                    root,
                    clock,
                    embeddedRevocations(signed, "revokedCertificateSerials"),
                    embeddedRevocations(signed, "revokedKeyIds")
            );
        } catch (IllegalArgumentException exception) {
            throw new IOException("Embedded HMCL CE plugin trust root is invalid", exception);
        }
    }

    /// Parses one optional immutable string set from build-injected root metadata.
    private static @Unmodifiable Set<String> embeddedRevocations(JsonObject root, String field) {
        JsonElement value = root.get(field);
        if (value == null) {
            return Set.of();
        }
        if (!value.isJsonArray()) {
            throw new IllegalArgumentException("Root revocation field must be an array: " + field);
        }
        Set<String> entries = new HashSet<>();
        for (JsonElement entry : value.getAsJsonArray()) {
            if (!entry.isJsonPrimitive() || !entry.getAsJsonPrimitive().isString()
                    || entry.getAsString().isBlank() || !entries.add(entry.getAsString())) {
                throw new IllegalArgumentException("Root revocation field has an invalid entry: " + field);
            }
        }
        return Set.copyOf(entries);
    }

    /// Creates one immutable verifier.
    private PluginTrustVerifier(
            PluginTrustRoot root,
            Clock clock,
            Set<String> revokedSerials,
            Set<String> revokedKeyIds
    ) {
        this.root = Objects.requireNonNull(root, "root");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.revokedSerials = Set.copyOf(revokedSerials);
        this.revokedKeyIds = Set.copyOf(revokedKeyIds);
    }

    /// Returns the only online trust-status endpoint this verifier may contact.
    ///
    /// The value comes from the embedded root and never from a store manifest or registry.
    public String getTrustStatusUrl() {
        return root.getStatusUrl();
    }

    /// Verifies a registry envelope using only the official repository role.
    public PluginDocumentVerification verifyOfficialRegistry(JsonObject document) {
        try {
            PluginSignatureEnvelope envelope = new PluginSignatureEnvelope(document);
            @Nullable String keyId = root.verifyRole(
                    OFFICIAL_REPOSITORY_ROLE,
                    OFFICIAL_REGISTRY_DOMAIN,
                    envelope.signed(),
                    envelope.signatures()
            );
            if (keyId == null) {
                return rejected(envelope.signed(), "official repository signature threshold was not met");
            }
            return new PluginDocumentVerification(envelope.signed(), PluginTrustResult.official(keyId));
        } catch (RuntimeException exception) {
            return rejected(payloadOrDocument(document), "invalid official repository envelope");
        }
    }

    /// Verifies an unsigned or certified plugin manifest for one externally established repository identity.
    public PluginDocumentVerification verifyManifest(
            JsonObject document,
            String expectedPluginId,
            String expectedRepository
    ) {
        boolean hasSigned = document.has("signed");
        boolean hasSignatures = document.has("signatures");
        boolean hasCertificate = document.has("certificate");
        if (!hasSigned && !hasSignatures && !hasCertificate) {
            return new PluginDocumentVerification(document, PluginTrustResult.community());
        }
        if (!hasSigned || !hasSignatures || !hasCertificate) {
            return rejected(payloadOrDocument(document), "partial signing declaration");
        }
        try {
            PluginSignatureEnvelope manifestEnvelope = new PluginSignatureEnvelope(document);
            PluginSignatureEnvelope certificateEnvelope = new PluginSignatureEnvelope(
                    PluginTrustRoot.object(document, "certificate")
            );
            Certificate certificate = validateCertificate(certificateEnvelope, expectedPluginId, expectedRepository);
            if (certificate.failure() != null) {
                return rejected(manifestEnvelope.signed(), certificate.failure());
            }
            @Nullable String manifestKeyId = verifyDeveloperSignature(
                    certificate.publicKey(),
                    certificate.keyId(),
                    manifestEnvelope.signed(),
                    manifestEnvelope.signatures()
            );
            if (manifestKeyId == null) {
                return rejected(manifestEnvelope.signed(), "developer manifest signature is invalid");
            }
            if (!expectedPluginId.equals(PluginTrustRoot.string(manifestEnvelope.signed(), "id"))) {
                return rejected(manifestEnvelope.signed(), "manifest plugin ID does not match discovery identity");
            }
            @Nullable String declaredRepository = PluginTrustRoot.optionalString(manifestEnvelope.signed(), "repository");
            if (declaredRepository != null && !normalizeRepository(expectedRepository).equals(normalizeRepository(declaredRepository))) {
                return rejected(manifestEnvelope.signed(), "manifest repository does not match discovery identity");
            }
            return new PluginDocumentVerification(manifestEnvelope.signed(), PluginTrustResult.community());
        } catch (RuntimeException exception) {
            return rejected(payloadOrDocument(document), "invalid signed manifest");
        }
    }

    /// Verifies and parses one weekly repository attestation using only the repository-attestor role.
    ///
    /// @param document signed repository proof envelope
    /// @return immutable verified repository proof
    /// @throws IllegalArgumentException if the envelope, signature, fields, topic, status, or time window is invalid
    public PluginRepositoryAttestation verifyRepositoryAttestation(JsonObject document) {
        return verifyRepositoryAttestation(document, true);
    }

    /// Verifies a historical repository proof without requiring its weekly interval to still be current.
    ///
    /// Historical proofs only authorize artifacts approved inside their signed interval. Current repository approval
    /// is established independently from a fresh trust-status snapshot.
    ///
    /// @param document signed historical repository proof envelope
    /// @return immutable verified repository proof
    /// @throws IllegalArgumentException if the envelope, role signature, schema, or signed interval is invalid
    public PluginRepositoryAttestation verifyHistoricalRepositoryAttestation(JsonObject document) {
        return verifyRepositoryAttestation(document, false);
    }

    /// Parses one role-signed repository proof with optional current-window enforcement.
    private PluginRepositoryAttestation verifyRepositoryAttestation(JsonObject document, boolean requireCurrent) {
        PluginSignatureEnvelope envelope = new PluginSignatureEnvelope(document);
        JsonObject signed = envelope.signed();
        @Nullable String keyId = root.verifyRole(
                REPOSITORY_ATTESTOR_ROLE,
                REPOSITORY_ATTESTATION_DOMAIN,
                signed,
                envelope.signatures()
        );
        if (keyId == null || revokedKeyIds.contains(keyId)) {
            throw new IllegalArgumentException("repository attestation signature is invalid");
        }
        requireDocumentType(signed, "repository-attestation");
        String repository = normalizeRepository(PluginTrustRoot.string(signed, "repository"));
        long repositoryId = positiveLong(signed, "repositoryId");
        String defaultBranch = nonBlank(signed, "defaultBranch");
        List<String> topics = stringList(signed, "topics");
        if (topics.stream().noneMatch(topic -> "HMCLCE".equalsIgnoreCase(topic))) {
            throw new IllegalArgumentException("repository attestation lacks HMCLCE topic");
        }
        List<String> pluginIds = stringList(signed, "pluginIds");
        String status = PluginTrustRoot.string(signed, "status");
        if (!status.equals("approved") && !status.equals("suspended")) {
            throw new IllegalArgumentException("repository attestation status is invalid");
        }
        Instant checkedAt = instant(signed, "checkedAt");
        Instant validUntil = instant(signed, "validUntil");
        validateWindow(checkedAt, validUntil, MAX_REPOSITORY_WINDOW, "repository attestation");
        Instant now = clock.instant();
        if (checkedAt.isAfter(now.plus(CLOCK_SKEW)) || requireCurrent && !now.isBefore(validUntil)) {
            throw new IllegalArgumentException("repository attestation is outside its validity interval");
        }
        return new PluginRepositoryAttestation(
                repository,
                repositoryId,
                defaultBranch,
                topics,
                pluginIds,
                status,
                checkedAt,
                validUntil,
                nonBlank(signed, "policyVersion"),
                nonBlank(signed, "sourceCommit"),
                nonBlank(signed, "verificationId"),
                keyId
        );
    }

    /// Verifies and parses one short-lived online trust-status snapshot.
    ///
    /// Expired snapshots remain parseable so an authenticated cached revocation can still block already installed
    /// code. Callers must use [PluginTrustStatusSnapshot#isFreshAt(Instant)] before granting new certification.
    ///
    /// @param document signed status snapshot envelope
    /// @return immutable verified status snapshot
    /// @throws IllegalArgumentException if the signature, schema, time window, or entries are invalid
    public PluginTrustStatusSnapshot verifyTrustStatusSnapshot(JsonObject document) {
        PluginSignatureEnvelope envelope = new PluginSignatureEnvelope(document);
        JsonObject signed = envelope.signed();
        @Nullable String keyId = root.verifyRole(
                TRUST_STATUS_ROLE,
                TRUST_STATUS_DOMAIN,
                signed,
                envelope.signatures()
        );
        if (keyId == null) {
            throw new IllegalArgumentException("trust-status snapshot signature is invalid");
        }
        requireDocumentType(signed, "trust-status-snapshot");
        long version = positiveLong(signed, "version");
        Instant generatedAt = instant(signed, "generatedAt");
        Instant expires = instant(signed, "expires");
        validateWindow(generatedAt, expires, MAX_STATUS_WINDOW, "trust-status snapshot");
        if (generatedAt.isAfter(clock.instant().plus(CLOCK_SKEW))) {
            throw new IllegalArgumentException("trust-status snapshot was generated in the future");
        }

        List<PluginTrustStatusSnapshot.RepositoryStatus> repositories = new ArrayList<>();
        Set<Long> repositoryIds = new HashSet<>();
        Set<String> repositoryNames = new HashSet<>();
        for (JsonElement element : PluginTrustRoot.array(signed, "repositories")) {
            if (!element.isJsonObject()) {
                throw new IllegalArgumentException("trust-status repository entry is not an object");
            }
            JsonObject repository = element.getAsJsonObject();
            long repositoryId = positiveLong(repository, "repositoryId");
            String repositoryName = normalizeRepository(PluginTrustRoot.string(repository, "repository"));
            if (!repositoryIds.add(repositoryId) || !repositoryNames.add(repositoryName)) {
                throw new IllegalArgumentException("trust-status snapshot contains a duplicate repository");
            }
            String status = PluginTrustRoot.string(repository, "status");
            if (!status.equals("approved") && !status.equals("suspended") && !status.equals("revoked")) {
                throw new IllegalArgumentException("trust-status repository state is invalid");
            }
            Instant lastVerifiedAt = instant(repository, "lastVerifiedAt");
            Instant nextVerificationAt = instant(repository, "nextVerificationAt");
            validateWindow(lastVerifiedAt, nextVerificationAt, MAX_REPOSITORY_WINDOW, "repository status");
            repositories.add(new PluginTrustStatusSnapshot.RepositoryStatus(
                    repositoryId,
                    repositoryName,
                    nonBlank(repository, "verificationId"),
                    status,
                    lastVerifiedAt,
                    nextVerificationAt,
                    stringList(repository, "pluginIds")
            ));
        }

        List<PluginTrustStatusSnapshot.RevokedArtifact> revokedArtifacts = new ArrayList<>();
        Set<String> artifactHashes = new HashSet<>();
        for (JsonElement element : PluginTrustRoot.array(signed, "revokedArtifacts")) {
            if (!element.isJsonObject()) {
                throw new IllegalArgumentException("revoked artifact entry is not an object");
            }
            JsonObject artifact = element.getAsJsonObject();
            String sha256 = PluginTrustRoot.string(artifact, "sha256").toLowerCase(Locale.ROOT);
            if (!SHA256_PATTERN.matcher(sha256).matches()) {
                throw new IllegalArgumentException("revoked artifact has an invalid SHA-256");
            }
            String pluginId = nonBlank(artifact, "pluginId");
            String artifactVersion = nonBlank(artifact, "version");
            if (!artifactHashes.add(sha256)) {
                throw new IllegalArgumentException("trust-status snapshot contains a duplicate artifact revocation");
            }
            revokedArtifacts.add(new PluginTrustStatusSnapshot.RevokedArtifact(
                    sha256,
                    pluginId,
                    artifactVersion,
                    instant(artifact, "revokedAt"),
                    nonBlank(artifact, "reasonCode")
            ));
        }
        PluginTrustStatusSnapshot snapshot = new PluginTrustStatusSnapshot(
                version,
                generatedAt,
                expires,
                nonBlank(signed, "policyVersion"),
                repositories,
                revokedArtifacts,
                revokedOnlineKeyIds(signed),
                keyId
        );
        if (snapshot.isKeyRevoked(keyId)) {
            throw new IllegalArgumentException("trust-status signer cannot revoke itself");
        }
        return snapshot;
    }

    /// Verifies an NPL proof's artifact-role signature before using its repository reference for a fixed-origin fetch.
    ///
    /// Full package and repository binding is still performed by [#verifyArtifactAttestation].
    ///
    /// @param document signed NPL proof envelope
    /// @return safe opaque repository verification ID
    /// @throws IllegalArgumentException if the envelope, role signature, document type, or reference is invalid
    public String verifyArtifactRepositoryReference(JsonObject document) {
        PluginSignatureEnvelope envelope = new PluginSignatureEnvelope(document);
        JsonObject signed = envelope.signed();
        @Nullable String keyId = root.verifyRole(
                ARTIFACT_ATTESTOR_ROLE,
                NPL_ATTESTATION_DOMAIN,
                signed,
                envelope.signatures()
        );
        if (keyId == null || revokedKeyIds.contains(keyId)) {
            throw new IllegalArgumentException("artifact attestation signature is invalid");
        }
        requireDocumentType(signed, "npl-attestation");
        String verificationId = nonBlank(signed, "repositoryVerificationId");
        if (!VERIFICATION_ID_PATTERN.matcher(verificationId).matches()) {
            throw new IllegalArgumentException("artifact repository verification reference is invalid");
        }
        return verificationId;
    }

    /// Re-verifies the two proof envelopes retained by an installed certification receipt.
    ///
    /// This path deliberately does not require a fresh online status snapshot: stale authenticated snapshots still
    /// carry irreversible revocations, while the receipt proofs establish only the historical identity that those
    /// revocations address. Both signatures, the repository approval interval, and every current NPL identity field
    /// are checked again before derived receipt fields may be used.
    ///
    /// @param artifactDocument complete artifact-attestation envelope
    /// @param repositoryDocument complete historical repository-attestation envelope
    /// @param expectedPluginId current installed plugin ID
    /// @param expectedVersion current installed package version
    /// @param expectedSha256 current complete NPL SHA-256
    /// @param expectedSize current complete NPL size
    /// @return values derived exclusively from the two verified envelopes
    /// @throws IllegalArgumentException if either proof or any package/repository binding is invalid
    public PluginVerifiedCertification verifyInstalledCertification(
            JsonObject artifactDocument,
            JsonObject repositoryDocument,
            String expectedPluginId,
            String expectedVersion,
            String expectedSha256,
            long expectedSize
    ) {
        PluginRepositoryAttestation repository = verifyHistoricalRepositoryAttestation(repositoryDocument);
        if (!repository.status().equals("approved") || !repository.pluginIds().contains(expectedPluginId)) {
            throw new IllegalArgumentException("repository proof does not authorize the installed plugin");
        }

        PluginSignatureEnvelope envelope = new PluginSignatureEnvelope(artifactDocument);
        JsonObject signed = envelope.signed();
        @Nullable String artifactKeyId = root.verifyRole(
                ARTIFACT_ATTESTOR_ROLE,
                NPL_ATTESTATION_DOMAIN,
                signed,
                envelope.signatures()
        );
        if (artifactKeyId == null || revokedKeyIds.contains(artifactKeyId)) {
            throw new IllegalArgumentException("artifact attestation signature is invalid");
        }
        requireDocumentType(signed, "npl-attestation");

        String signedRepository = normalizeRepository(PluginTrustRoot.string(signed, "repository"));
        long signedRepositoryId = positiveLong(signed, "repositoryId");
        String signedPluginId = PluginTrustRoot.string(signed, "pluginId");
        String signedVersion = PluginTrustRoot.string(signed, "version");
        String signedSha256 = PluginTrustRoot.string(signed, "sha256").toLowerCase(Locale.ROOT);
        long signedSize = positiveLong(signed, "size");
        if (!repository.repository().equals(signedRepository)
                || repository.repositoryId() != signedRepositoryId
                || !expectedPluginId.equals(signedPluginId)
                || !expectedVersion.equals(signedVersion)
                || !PluginTrustRoot.string(signed, "tag").equals("v" + expectedVersion)
                || !SHA256_PATTERN.matcher(signedSha256).matches()
                || !signedSha256.equalsIgnoreCase(expectedSha256)
                || signedSize != expectedSize) {
            throw new IllegalArgumentException("artifact proof does not match the installed NPL identity");
        }

        String assetUrl = PluginTrustRoot.string(signed, "assetUrl");
        if (!PluginTrustRoot.string(signed, "assetName").equals(assetName(assetUrl))) {
            throw new IllegalArgumentException("artifact proof asset name does not match its URL");
        }
        String verificationId = nonBlank(signed, "repositoryVerificationId");
        if (!verificationId.equals(repository.verificationId())
                || !nonBlank(signed, "sourceCommit").equals(repository.sourceCommit())
                || !nonBlank(signed, "policyVersion").equals(repository.policyVersion())) {
            throw new IllegalArgumentException("artifact proof does not match its historical repository proof");
        }
        nonBlank(signed, "jobId");
        Instant approvedAt = instant(signed, "approvedAt");
        if (approvedAt.isAfter(clock.instant().plus(CLOCK_SKEW))
                || approvedAt.isBefore(repository.checkedAt())
                || !approvedAt.isBefore(repository.validUntil())) {
            throw new IllegalArgumentException("artifact approval time is outside its repository proof interval");
        }

        return new PluginVerifiedCertification(
                signedPluginId,
                signedVersion,
                signedSha256,
                signedSize,
                signedRepositoryId,
                signedRepository,
                repository.keyId(),
                artifactKeyId,
                verificationId
        );
    }

    /// Parses unique online attestation key revocations from a signed status snapshot.
    private static @Unmodifiable Set<String> revokedOnlineKeyIds(JsonObject signed) {
        List<String> values = stringList(signed, "revokedKeyIds");
        Set<String> keyIds = new HashSet<>();
        for (String value : values) {
            if (!KEY_ID_PATTERN.matcher(value).matches() || !keyIds.add(value)) {
                throw new IllegalArgumentException("trust-status snapshot contains an invalid revoked key ID");
            }
        }
        return Set.copyOf(keyIds);
    }

    /// Verifies one per-release artifact proof against its weekly repository proof, live status, and manifest fields.
    ///
    /// @param document signed NPL proof envelope
    /// @param repository weekly repository proof
    /// @param statusSnapshot current root-role-verified online status
    /// @param expectedRepository repository identity established outside the manifest signing declaration
    /// @param expectedPluginId selected plugin ID
    /// @param expectedVersion selected version
    /// @param expectedPackageUrl selected package URL
    /// @param expectedSha256 selected package digest
    /// @param expectedSize selected package size
    /// @return certified or fail-closed rejected decision for this exact version
    public PluginTrustResult verifyArtifactAttestation(
            JsonObject document,
            PluginRepositoryAttestation repository,
            PluginTrustStatusSnapshot statusSnapshot,
            String expectedRepository,
            String expectedPluginId,
            String expectedVersion,
            String expectedPackageUrl,
            String expectedSha256,
            long expectedSize
    ) {
        try {
            Instant now = clock.instant();
            if (!statusSnapshot.isFreshAt(now)) {
                return PluginTrustResult.rejected("official trust status is expired");
            }
            if (!repository.status().equals("approved")) {
                return PluginTrustResult.rejected("repository proof is not approved");
            }
            String normalizedExpectedRepository = normalizeRepository(expectedRepository);
            if (!repository.repository().equals(normalizedExpectedRepository)
                    || !repository.pluginIds().contains(expectedPluginId)) {
                return PluginTrustResult.rejected("repository proof does not authorize this plugin");
            }
            @Nullable PluginTrustStatusSnapshot.RepositoryStatus onlineRepository =
                    statusSnapshot.findRepository(repository.repositoryId());
            if (onlineRepository == null
                    || !onlineRepository.repository().equals(normalizedExpectedRepository)
                    || !onlineRepository.pluginIds().contains(expectedPluginId)) {
                return PluginTrustResult.rejected("online repository status does not match the repository identity");
            }
            if (!onlineRepository.status().equals("approved")) {
                return PluginTrustResult.rejected("repository is " + onlineRepository.status());
            }
            if (!now.isBefore(onlineRepository.nextVerificationAt())) {
                return PluginTrustResult.rejected("repository verification is overdue");
            }
            if (statusSnapshot.isKeyRevoked(repository.keyId())) {
                return PluginTrustResult.rejected("repository attestation key is revoked");
            }
            @Nullable PluginTrustStatusSnapshot.RevokedArtifact revocation =
                    statusSnapshot.findRevokedArtifact(expectedPluginId, expectedVersion, expectedSha256);
            if (revocation != null) {
                return PluginTrustResult.rejected("artifact is revoked: " + revocation.reasonCode());
            }

            PluginSignatureEnvelope envelope = new PluginSignatureEnvelope(document);
            JsonObject signed = envelope.signed();
            @Nullable String keyId = root.verifyRole(
                    ARTIFACT_ATTESTOR_ROLE,
                    NPL_ATTESTATION_DOMAIN,
                    signed,
                    envelope.signatures()
            );
            if (keyId == null) {
                return PluginTrustResult.rejected("artifact attestation signature is invalid");
            }
            if (revokedKeyIds.contains(keyId) || statusSnapshot.isKeyRevoked(keyId)) {
                return PluginTrustResult.rejected("artifact attestation key is revoked");
            }
            requireDocumentType(signed, "npl-attestation");
            if (!normalizeRepository(PluginTrustRoot.string(signed, "repository"))
                    .equals(normalizedExpectedRepository)
                    || positiveLong(signed, "repositoryId") != repository.repositoryId()
                    || !PluginTrustRoot.string(signed, "pluginId").equals(expectedPluginId)
                    || !PluginTrustRoot.string(signed, "version").equals(expectedVersion)
                    || !PluginTrustRoot.string(signed, "tag").equals("v" + expectedVersion)) {
                return PluginTrustResult.rejected("artifact identity does not match the selected version");
            }
            String assetUrl = PluginTrustRoot.string(signed, "assetUrl");
            String assetName = PluginTrustRoot.string(signed, "assetName");
            if (!assetUrl.equals(expectedPackageUrl) || !assetName.equals(assetName(expectedPackageUrl))) {
                return PluginTrustResult.rejected("artifact URL or asset name does not match the selected version");
            }
            String sha256 = PluginTrustRoot.string(signed, "sha256");
            if (!SHA256_PATTERN.matcher(sha256).matches() || !sha256.equalsIgnoreCase(expectedSha256)) {
                return PluginTrustResult.rejected("artifact SHA-256 does not match the selected version");
            }
            if (positiveLong(signed, "size") != expectedSize) {
                return PluginTrustResult.rejected("artifact size does not match the selected version");
            }
            if (!nonBlank(signed, "repositoryVerificationId").equals(repository.verificationId())) {
                return PluginTrustResult.rejected("artifact repository verification reference does not match");
            }
            if (!nonBlank(signed, "sourceCommit").equals(repository.sourceCommit())) {
                return PluginTrustResult.rejected("artifact source commit does not match its repository proof");
            }
            if (!nonBlank(signed, "policyVersion").equals(repository.policyVersion())) {
                return PluginTrustResult.rejected("artifact policy version does not match its repository proof");
            }
            nonBlank(signed, "jobId");
            Instant approvedAt = instant(signed, "approvedAt");
            if (approvedAt.isAfter(now.plus(CLOCK_SKEW))) {
                return PluginTrustResult.rejected("artifact approval time is in the future");
            }
            if (approvedAt.isBefore(repository.checkedAt()) || !approvedAt.isBefore(repository.validUntil())) {
                return PluginTrustResult.rejected("artifact approval time is outside its repository proof interval");
            }
            return PluginTrustResult.certified(keyId, repository.verificationId());
        } catch (RuntimeException exception) {
            @Nullable String message = exception.getMessage();
            return PluginTrustResult.rejected(message == null || message.isBlank()
                    ? "invalid artifact attestation"
                    : message);
        }
    }

    /// Validates one developer certificate and its exact scope.
    private Certificate validateCertificate(
            PluginSignatureEnvelope envelope,
            String expectedPluginId,
            String expectedRepository
    ) {
        JsonObject signed = envelope.signed();
        @Nullable String issuerKey = root.verifyRole(
                DEVELOPER_CA_ROLE,
                DEVELOPER_CERTIFICATE_DOMAIN,
                signed,
                envelope.signatures()
        );
        if (issuerKey == null) {
            return Certificate.failed("developer certificate signature is invalid");
        }
        if (!"developer-certificate".equals(PluginTrustRoot.string(signed, "_type"))
                || PluginTrustRoot.integer(signed, "schemaVersion") != 1) {
            return Certificate.failed("unsupported developer certificate");
        }
        String serial = PluginTrustRoot.string(signed, "serial");
        String keyId = PluginTrustRoot.string(signed, "keyId");
        if (revokedSerials.contains(serial) || revokedKeyIds.contains(keyId)) {
            return Certificate.failed("developer certificate or key is revoked");
        }
        Instant now = clock.instant();
        try {
            Instant notBefore = Instant.parse(PluginTrustRoot.string(signed, "notBefore"));
            Instant notAfter = Instant.parse(PluginTrustRoot.string(signed, "notAfter"));
            if (now.isBefore(notBefore) || !now.isBefore(notAfter)) {
                return Certificate.failed("developer certificate is outside its validity interval");
            }
        } catch (DateTimeParseException exception) {
            return Certificate.failed("developer certificate validity interval is invalid");
        }
        if (!contains(PluginTrustRoot.array(signed, "usages"), "plugin-manifest")) {
            return Certificate.failed("developer certificate lacks plugin-manifest usage");
        }
        if (!matchesPluginId(signed, expectedPluginId)) {
            return Certificate.failed("developer certificate does not authorize this plugin ID");
        }
        if (!containsNormalizedRepository(PluginTrustRoot.array(signed, "repositories"), expectedRepository)) {
            return Certificate.failed("developer certificate does not authorize this repository");
        }
        try {
            JsonObject keyDeclaration = PluginTrustRoot.object(signed, "key");
            if (!"ed25519".equals(PluginTrustRoot.string(keyDeclaration, "keyType"))
                    || !"ed25519".equals(PluginTrustRoot.string(keyDeclaration, "scheme"))) {
                return Certificate.failed("developer certificate uses an unsupported key algorithm");
            }
            byte[] encoded = Base64.getDecoder().decode(PluginTrustRoot.string(keyDeclaration, "publicKey"));
            String computedKeyId = "ed25519:" + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(encoded)
            );
            if (!keyId.equals(computedKeyId)) {
                return Certificate.failed("developer certificate key ID mismatch");
            }
            PublicKey key = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(encoded));
            return new Certificate(serial, keyId, key, null);
        } catch (Exception exception) {
            return Certificate.failed("developer certificate public key is invalid");
        }
    }

    /// Verifies a manifest signature made by the certified developer key.
    private static @Nullable String verifyDeveloperSignature(
            PublicKey key,
            String keyId,
            JsonObject payload,
            JsonArray signatures
    ) {
        byte[] input = CanonicalJson.signatureInput(MANIFEST_DOMAIN, payload);
        for (JsonElement element : signatures) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject declaration = element.getAsJsonObject();
            if (keyId.equals(PluginTrustRoot.optionalString(declaration, "keyId"))) {
                @Nullable String signature = PluginTrustRoot.optionalString(declaration, "signature");
                if (signature != null && PluginTrustRoot.verify(key, input, signature)) {
                    return keyId;
                }
            }
        }
        return null;
    }

    /// Returns whether a certificate authorizes one exact ID or dot-terminated prefix.
    private static boolean matchesPluginId(JsonObject certificate, String pluginId) {
        if (contains(PluginTrustRoot.array(certificate, "pluginIds"), pluginId)) {
            return true;
        }
        for (JsonElement prefixElement : PluginTrustRoot.array(certificate, "pluginIdPrefixes")) {
            String prefix = prefixElement.getAsString();
            if (prefix.endsWith(".") && pluginId.startsWith(prefix) && pluginId.length() > prefix.length()) {
                return true;
            }
        }
        return false;
    }

    /// Returns whether an array contains one exact string.
    private static boolean contains(JsonArray values, String expected) {
        for (JsonElement value : values) {
            if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                    && expected.equals(value.getAsString())) {
                return true;
            }
        }
        return false;
    }

    /// Returns whether an array contains one normalized repository identity.
    private static boolean containsNormalizedRepository(JsonArray values, String expected) {
        String normalizedExpected = normalizeRepository(expected);
        for (JsonElement value : values) {
            if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                    && normalizedExpected.equals(normalizeRepository(value.getAsString()))) {
                return true;
            }
        }
        return false;
    }

    /// Requires one supported schema-v1 signed payload type.
    ///
    /// @param signed signed payload
    /// @param expectedType required `_type`
    private static void requireDocumentType(JsonObject signed, String expectedType) {
        if (!expectedType.equals(PluginTrustRoot.string(signed, "_type"))
                || PluginTrustRoot.integer(signed, "schemaVersion") != 1) {
            throw new IllegalArgumentException("unsupported " + expectedType + " schema");
        }
    }

    /// Reads one positive integral JSON number without accepting fractional or lossy values.
    ///
    /// @param object containing object
    /// @param field required field name
    /// @return positive integer value
    private static long positiveLong(JsonObject object, String field) {
        JsonElement element = object.get(field);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("required integer field is missing: " + field);
        }
        try {
            BigDecimal decimal = element.getAsBigDecimal();
            long value = decimal.longValueExact();
            if (value <= 0 || value > CanonicalJson.MAX_SAFE_INTEGER) {
                throw new IllegalArgumentException("integer field must be a positive safe integer: " + field);
            }
            return value;
        } catch (ArithmeticException | NumberFormatException exception) {
            throw new IllegalArgumentException("integer field is invalid: " + field, exception);
        }
    }

    /// Reads one required non-blank string.
    ///
    /// @param object containing object
    /// @param field required field name
    /// @return non-blank string
    private static String nonBlank(JsonObject object, String field) {
        String value = PluginTrustRoot.string(object, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException("string field is blank: " + field);
        }
        return value;
    }

    /// Reads one unique non-blank string array.
    ///
    /// @param object containing object
    /// @param field required array field
    /// @return immutable string values
    private static @Unmodifiable List<String> stringList(JsonObject object, String field) {
        List<String> values = new ArrayList<>();
        Set<String> unique = new HashSet<>();
        for (JsonElement element : PluginTrustRoot.array(object, field)) {
            if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
                throw new IllegalArgumentException("array field contains a non-string: " + field);
            }
            String value = element.getAsString();
            if (value.isBlank() || !unique.add(value)) {
                throw new IllegalArgumentException("array field contains a blank or duplicate value: " + field);
            }
            values.add(value);
        }
        return List.copyOf(values);
    }

    /// Reads one required ISO-8601 instant.
    ///
    /// @param object containing object
    /// @param field required field name
    /// @return parsed instant
    private static Instant instant(JsonObject object, String field) {
        try {
            return Instant.parse(PluginTrustRoot.string(object, field));
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException("instant field is invalid: " + field, exception);
        }
    }

    /// Validates an exclusive positive interval and its maximum allowed lifetime.
    ///
    /// @param start interval start
    /// @param end exclusive interval end
    /// @param maximum maximum accepted duration
    /// @param description diagnostic subject
    private static void validateWindow(Instant start, Instant end, Duration maximum, String description) {
        Duration duration;
        try {
            duration = Duration.between(start, end);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(description + " interval overflow", exception);
        }
        if (duration.isZero() || duration.isNegative() || duration.compareTo(maximum) > 0) {
            throw new IllegalArgumentException(description + " validity window is invalid");
        }
    }

    /// Extracts the literal final path component from an exact package URL.
    ///
    /// @param packageUrl selected package URL
    /// @return non-blank asset file name
    private static String assetName(String packageUrl) {
        try {
            URI uri = URI.create(packageUrl);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getRawQuery() != null || uri.getRawFragment() != null) {
                throw new IllegalArgumentException("artifact URL is not an immutable HTTPS asset URL");
            }
            @Nullable String path = uri.getPath();
            int separator = path == null ? -1 : path.lastIndexOf('/');
            if (path == null || separator < 0 || separator == path.length() - 1) {
                throw new IllegalArgumentException("artifact URL has no asset name");
            }
            return path.substring(separator + 1);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("artifact URL is invalid", exception);
        }
    }

    /// Normalizes an exact GitHub owner/repository identity without accepting arbitrary URLs.
    public static String normalizeRepository(String repository) {
        String normalized = repository.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("https://")) {
            normalized = normalized.substring("https://".length());
        }
        if (normalized.endsWith(".git")) {
            normalized = normalized.substring(0, normalized.length() - 4);
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (!normalized.matches("github\\.com/[a-z0-9](?:[a-z0-9-]{0,38})/[a-z0-9._-]{1,100}")) {
            throw new IllegalArgumentException("Invalid GitHub repository identity");
        }
        return normalized;
    }

    /// Selects a safe payload for rejected-envelope diagnostics.
    private static JsonObject payloadOrDocument(JsonObject document) {
        JsonElement signed = document.get("signed");
        return signed != null && signed.isJsonObject() ? signed.getAsJsonObject() : document;
    }

    /// Creates a rejected verification result.
    private static PluginDocumentVerification rejected(JsonObject payload, String detail) {
        return new PluginDocumentVerification(payload, PluginTrustResult.rejected(detail));
    }

    /// Parsed developer certificate or a credential-safe validation failure.
    private record Certificate(
            String serial,
            String keyId,
            PublicKey publicKey,
            @Nullable String failure
    ) {
        /// Creates a failed certificate placeholder whose key data is never observed.
        private static Certificate failed(String failure) {
            return new Certificate("", "", NullPublicKey.INSTANCE, failure);
        }
    }

    /// Inert public key used only inside rejected certificate values.
    private enum NullPublicKey implements PublicKey {
        /// Singleton inert key.
        INSTANCE;

        /// Returns an inert algorithm label.
        @Override
        public String getAlgorithm() {
            return "none";
        }

        /// Returns an inert encoding label.
        @Override
        public String getFormat() {
            return "none";
        }

        /// Returns an empty encoding.
        @Override
        public byte @Unmodifiable [] getEncoded() {
            return new byte[0];
        }
    }
}
