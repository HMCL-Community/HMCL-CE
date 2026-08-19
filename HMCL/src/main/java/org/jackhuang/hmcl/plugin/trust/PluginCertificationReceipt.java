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

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jackhuang.hmcl.plugin.PluginArtifactIdentity;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/// Durable certification receipt containing both original role-signed proof envelopes and derived lookup fields.
///
/// Derived fields are never trusted on their own. Runtime loading re-verifies both envelopes with the embedded root,
/// binds them to the current NPL bytes and size, and then compares every derived field to the verified result.
///
/// @param pluginId exact installed plugin ID
/// @param version exact installed package version
/// @param sha256 exact complete NPL SHA-256
/// @param size exact complete NPL size
/// @param repositoryId immutable GitHub repository ID
/// @param repository normalized GitHub repository identity
/// @param repositorySignerKeyId repository proof signer key ID
/// @param artifactSignerKeyId artifact proof signer key ID
/// @param repositoryVerificationId historical weekly verification ID
/// @param artifactAttestationJson complete artifact-attestation envelope JSON
/// @param repositoryAttestationJson complete historical repository-attestation envelope JSON
@NotNullByDefault
public record PluginCertificationReceipt(
        String pluginId,
        String version,
        String sha256,
        long size,
        long repositoryId,
        String repository,
        String repositorySignerKeyId,
        String artifactSignerKeyId,
        String repositoryVerificationId,
        String artifactAttestationJson,
        String repositoryAttestationJson
) {
    /// Accepted Ed25519 key identifier format.
    private static final Pattern KEY_ID_PATTERN = Pattern.compile("ed25519:[0-9a-f]{64}");

    /// Accepted opaque verification identifier format.
    private static final Pattern VERIFICATION_ID_PATTERN =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

    /// Normalizes immutable fields and rejects structurally incomplete receipts before persistence.
    public PluginCertificationReceipt {
        PluginArtifactIdentity identity = new PluginArtifactIdentity(pluginId, version, sha256);
        sha256 = identity.getSha256();
        if (size <= 0 || size > CanonicalJson.MAX_SAFE_INTEGER
                || repositoryId <= 0 || repositoryId > CanonicalJson.MAX_SAFE_INTEGER) {
            throw new IllegalArgumentException("Certification size and repository ID must be positive safe integers");
        }
        repository = PluginTrustVerifier.normalizeRepository(repository);
        repositorySignerKeyId = requireKeyId(repositorySignerKeyId, "repository signer");
        artifactSignerKeyId = requireKeyId(artifactSignerKeyId, "artifact signer");
        if (!VERIFICATION_ID_PATTERN.matcher(repositoryVerificationId).matches()) {
            throw new IllegalArgumentException("Certification repository verification ID is invalid");
        }
        artifactAttestationJson = requireJsonEnvelope(artifactAttestationJson, "artifact attestation");
        repositoryAttestationJson = requireJsonEnvelope(repositoryAttestationJson, "repository attestation");
    }

    /// Creates a durable receipt from a just-verified certification and its original proof envelopes.
    ///
    /// @param certification values derived by the verifier
    /// @param artifactAttestation complete artifact proof envelope
    /// @param repositoryAttestation complete historical repository proof envelope
    /// @return immutable receipt
    public static PluginCertificationReceipt fromVerified(
            PluginVerifiedCertification certification,
            JsonObject artifactAttestation,
            JsonObject repositoryAttestation
    ) {
        return new PluginCertificationReceipt(
                certification.pluginId(),
                certification.version(),
                certification.sha256(),
                certification.size(),
                certification.repositoryId(),
                certification.repository(),
                certification.repositorySignerKeyId(),
                certification.artifactSignerKeyId(),
                certification.repositoryVerificationId(),
                artifactAttestation.toString(),
                repositoryAttestation.toString()
        );
    }

    /// Re-verifies both stored proofs and binds them to the exact current package identity and size.
    ///
    /// @param verifier embedded-root verifier
    /// @param identity exact current package identity
    /// @param actualSize exact current package size in bytes
    /// @return values derived anew from both role-signed envelopes
    public PluginVerifiedCertification verify(
            PluginTrustVerifier verifier,
            PluginArtifactIdentity identity,
            long actualSize
    ) {
        if (!pluginId.equals(identity.getPluginId())
                || !version.equals(identity.getVersion())
                || !sha256.equals(identity.getSha256())
                || size != actualSize) {
            throw new IllegalArgumentException("Certification receipt does not match the installed NPL identity");
        }
        PluginVerifiedCertification verified = verifier.verifyInstalledCertification(
                parseEnvelope(artifactAttestationJson, "artifact attestation"),
                parseEnvelope(repositoryAttestationJson, "repository attestation"),
                identity.getPluginId(),
                identity.getVersion(),
                identity.getSha256(),
                actualSize
        );
        if (!verified.equals(derivedCertification())) {
            throw new IllegalArgumentException("Certification receipt fields do not match the signed proofs");
        }
        return verified;
    }

    /// Reconstructs the persisted derived fields for comparison with freshly verified proof values.
    ///
    /// @return persisted certification identity
    private PluginVerifiedCertification derivedCertification() {
        return new PluginVerifiedCertification(
                pluginId,
                version,
                sha256,
                size,
                repositoryId,
                repository,
                repositorySignerKeyId,
                artifactSignerKeyId,
                repositoryVerificationId
        );
    }

    /// Requires one canonical Ed25519 key identifier.
    ///
    /// @param value serialized key ID
    /// @param description diagnostic role
    /// @return normalized key ID
    private static String requireKeyId(String value, String description) {
        String normalized = Objects.requireNonNull(value, description).toLowerCase(Locale.ROOT);
        if (!KEY_ID_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException("Certification " + description + " key ID is invalid");
        }
        return normalized;
    }

    /// Requires one complete JSON object envelope and returns its compact normalized representation.
    ///
    /// @param value serialized envelope
    /// @param description diagnostic proof name
    /// @return compact envelope JSON
    private static String requireJsonEnvelope(String value, String description) {
        return parseEnvelope(Objects.requireNonNull(value, description), description).toString();
    }

    /// Parses one stored proof envelope without accepting arrays, primitives, or JSON null.
    ///
    /// @param value serialized envelope
    /// @param description diagnostic proof name
    /// @return parsed envelope object
    private static JsonObject parseEnvelope(String value, String description) {
        try {
            JsonElement parsed = JsonParser.parseString(value);
            if (!parsed.isJsonObject()) {
                throw new IllegalArgumentException(description + " is not an object");
            }
            return parsed.getAsJsonObject();
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Certification " + description + " is invalid", exception);
        }
    }
}
