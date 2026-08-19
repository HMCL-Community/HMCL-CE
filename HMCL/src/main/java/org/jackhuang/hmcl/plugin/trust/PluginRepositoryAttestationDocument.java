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

import java.util.Objects;

/// Couples one historical repository proof's complete signed envelope with values derived by the root verifier.
@NotNullByDefault
public final class PluginRepositoryAttestationDocument {
    /// Complete immutable proof envelope serialized for later installation receipts.
    private final String envelopeJson;

    /// Values derived from the verified envelope.
    private final PluginRepositoryAttestation attestation;

    /// Creates one proof document after verification.
    ///
    /// @param envelope complete signed envelope
    /// @param attestation verified parsed values
    public PluginRepositoryAttestationDocument(
            JsonObject envelope,
            PluginRepositoryAttestation attestation
    ) {
        envelopeJson = Objects.requireNonNull(envelope, "envelope").toString();
        this.attestation = Objects.requireNonNull(attestation, "attestation");
    }

    /// Returns a fresh mutable JSON object containing the original complete proof envelope.
    ///
    /// @return defensive envelope copy
    public JsonObject envelope() {
        return com.google.gson.JsonParser.parseString(envelopeJson).getAsJsonObject();
    }

    /// Returns values derived from the verified envelope.
    ///
    /// @return immutable repository attestation
    public PluginRepositoryAttestation attestation() {
        return attestation;
    }
}
