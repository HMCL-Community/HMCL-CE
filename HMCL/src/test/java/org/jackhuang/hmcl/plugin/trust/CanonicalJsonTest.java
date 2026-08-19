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

import com.google.gson.JsonParser;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies the deterministic JSON representation shared by every plugin signature role.
@NotNullByDefault
public final class CanonicalJsonTest {
    /// Sorts object keys recursively while retaining array order and exact integer values.
    @Test
    public void canonicalizesNestedJson() {
        String canonical = new String(CanonicalJson.canonicalize(JsonParser.parseString(
                "{\"z\":[3,{\"b\":true,\"a\":null}],\"a\":-12}"
        )), StandardCharsets.UTF_8);

        assertEquals("{\"a\":-12,\"z\":[3,{\"a\":null,\"b\":true}]}", canonical);
    }

    /// Uses minimal JSON escaping so equivalent strings have one signature representation.
    @Test
    public void escapesStringsDeterministically() {
        String canonical = new String(CanonicalJson.canonicalize(JsonParser.parseString(
                "{\"value\":\"line\\nquote\\\" slash/ snowman ☃\"}"
        )), StandardCharsets.UTF_8);

        assertEquals("{\"value\":\"line\\nquote\\\" slash/ snowman ☃\"}", canonical);
    }

    /// Rejects fractional values because the v1 trust schema intentionally permits exact integers only.
    @Test
    public void rejectsNonIntegralNumbers() {
        assertThrows(IllegalArgumentException.class, () ->
                CanonicalJson.canonicalize(JsonParser.parseString("{\"value\":1.5}")));
    }

    /// Retains both inclusive JavaScript-safe integer boundaries without precision loss.
    @Test
    public void canonicalizesSafeIntegerBoundaries() {
        String canonical = new String(CanonicalJson.canonicalize(JsonParser.parseString(
                "{\"maximum\":9007199254740991,\"minimum\":-9007199254740991}"
        )), StandardCharsets.UTF_8);

        assertEquals("{\"maximum\":9007199254740991,\"minimum\":-9007199254740991}", canonical);
    }

    /// Rejects integral values outside the exact number range shared with the Node.js signer.
    @Test
    public void rejectsIntegersOutsideSafeRange() {
        assertThrows(IllegalArgumentException.class, () ->
                CanonicalJson.canonicalize(JsonParser.parseString("{\"value\":9007199254740992}")));
        assertThrows(IllegalArgumentException.class, () ->
                CanonicalJson.canonicalize(JsonParser.parseString("{\"value\":-9007199254740992}")));
    }

    /// Prefixes the canonical payload with an explicit signature domain.
    @Test
    public void separatesSignatureDomains() {
        String input = new String(CanonicalJson.signatureInput(
                "HMCLCE-PLUGIN-MANIFEST-V1",
                JsonParser.parseString("{\"id\":\"dev.example\"}")
        ), StandardCharsets.UTF_8);

        assertEquals("HMCLCE-PLUGIN-MANIFEST-V1\n{\"id\":\"dev.example\"}", input);
    }
}
