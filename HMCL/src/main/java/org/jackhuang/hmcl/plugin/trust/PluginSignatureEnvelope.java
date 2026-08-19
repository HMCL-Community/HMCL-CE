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
import org.jetbrains.annotations.NotNullByDefault;

/// Strict structural parser for signed plugin metadata envelopes.
@NotNullByDefault
final class PluginSignatureEnvelope {
    /// Signed payload object.
    private final JsonObject signed;

    /// Signature declarations.
    private final JsonArray signatures;

    /// Parses a complete envelope.
    PluginSignatureEnvelope(JsonObject document) {
        JsonElement signedElement = document.get("signed");
        JsonElement signaturesElement = document.get("signatures");
        if (signedElement == null || !signedElement.isJsonObject()
                || signaturesElement == null || !signaturesElement.isJsonArray()) {
            throw new IllegalArgumentException("Signed envelope requires object signed and array signatures");
        }
        signed = signedElement.getAsJsonObject();
        signatures = signaturesElement.getAsJsonArray();
        if (signatures.isEmpty()) {
            throw new IllegalArgumentException("Signed envelope has no signatures");
        }
    }

    /// Returns the signed payload.
    JsonObject signed() {
        return signed;
    }

    /// Returns the signature declarations.
    JsonArray signatures() {
        return signatures;
    }
}
