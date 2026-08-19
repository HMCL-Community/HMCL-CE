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
import com.google.gson.JsonPrimitive;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/// Produces the deterministic JSON subset used by HMCL CE plugin trust metadata.
///
/// Trust schema version one intentionally accepts only integral JSON numbers. This avoids differences between
/// floating-point serializers while retaining every numeric value currently used by plugin metadata.
@NotNullByDefault
public final class CanonicalJson {
    /// Largest integer represented exactly by both Java long values and JavaScript numbers.
    public static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;

    /// Arbitrary-precision form used while rejecting integers outside the shared protocol range.
    private static final BigInteger MAX_SAFE_INTEGER_VALUE = BigInteger.valueOf(MAX_SAFE_INTEGER);

    /// Serializes one JSON value to deterministic UTF-8 bytes.
    ///
    /// @param value JSON value to canonicalize
    /// @return canonical UTF-8 representation
    /// @throws IllegalArgumentException if a number is fractional or outside the shared safe-integer range, or if a
    /// string contains an invalid surrogate
    public static byte @Unmodifiable [] canonicalize(JsonElement value) {
        StringBuilder output = new StringBuilder();
        append(Objects.requireNonNull(value, "value"), output);
        return output.toString().getBytes(StandardCharsets.UTF_8);
    }

    /// Builds a domain-separated signature input for one signed JSON payload.
    ///
    /// @param domain non-empty ASCII signature domain
    /// @param signed signed JSON payload
    /// @return domain-separated canonical UTF-8 bytes
    public static byte @Unmodifiable [] signatureInput(String domain, JsonElement signed) {
        if (domain.isBlank() || !StandardCharsets.US_ASCII.newEncoder().canEncode(domain)
                || domain.indexOf('\n') >= 0 || domain.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("Signature domain must be non-empty single-line ASCII");
        }
        byte[] payload = canonicalize(signed);
        byte[] prefix = (domain + "\n").getBytes(StandardCharsets.US_ASCII);
        byte[] result = new byte[prefix.length + payload.length];
        System.arraycopy(prefix, 0, result, 0, prefix.length);
        System.arraycopy(payload, 0, result, prefix.length, payload.length);
        return result;
    }

    /// Appends one JSON value recursively.
    ///
    /// @param value JSON value
    /// @param output canonical output
    private static void append(JsonElement value, StringBuilder output) {
        if (value.isJsonNull()) {
            output.append("null");
        } else if (value.isJsonObject()) {
            output.append('{');
            List<String> keys = new ArrayList<>(value.getAsJsonObject().keySet());
            keys.sort(String::compareTo);
            for (int index = 0; index < keys.size(); index++) {
                if (index > 0) {
                    output.append(',');
                }
                String key = keys.get(index);
                appendString(key, output);
                output.append(':');
                append(value.getAsJsonObject().get(key), output);
            }
            output.append('}');
        } else if (value.isJsonArray()) {
            output.append('[');
            for (int index = 0; index < value.getAsJsonArray().size(); index++) {
                if (index > 0) {
                    output.append(',');
                }
                append(value.getAsJsonArray().get(index), output);
            }
            output.append(']');
        } else {
            appendPrimitive(value.getAsJsonPrimitive(), output);
        }
    }

    /// Appends a Boolean, string, or exact integer primitive.
    ///
    /// @param value JSON primitive
    /// @param output canonical output
    private static void appendPrimitive(JsonPrimitive value, StringBuilder output) {
        if (value.isBoolean()) {
            output.append(value.getAsBoolean());
        } else if (value.isString()) {
            appendString(value.getAsString(), output);
        } else if (value.isNumber()) {
            BigDecimal number;
            try {
                number = new BigDecimal(value.getAsString()).stripTrailingZeros();
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("Invalid JSON number", exception);
            }
            if (number.scale() > 0) {
                throw new IllegalArgumentException("Trust metadata only permits integral JSON numbers");
            }
            BigInteger integer = number.toBigIntegerExact();
            if (integer.abs().compareTo(MAX_SAFE_INTEGER_VALUE) > 0) {
                throw new IllegalArgumentException("Trust metadata integer exceeds the shared safe range");
            }
            output.append(integer);
        } else {
            throw new IllegalArgumentException("Unsupported JSON primitive");
        }
    }

    /// Appends one minimally escaped JSON string.
    ///
    /// @param value source string
    /// @param output canonical output
    private static void appendString(String value, StringBuilder output) {
        output.append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> output.append("\\\"");
                case '\\' -> output.append("\\\\");
                case '\b' -> output.append("\\b");
                case '\f' -> output.append("\\f");
                case '\n' -> output.append("\\n");
                case '\r' -> output.append("\\r");
                case '\t' -> output.append("\\t");
                default -> {
                    if (character < 0x20) {
                        output.append(String.format("\\u%04x", (int) character));
                    } else if (Character.isHighSurrogate(character)) {
                        if (index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(index + 1))) {
                            throw new IllegalArgumentException("JSON string contains an unpaired high surrogate");
                        }
                        output.append(character).append(value.charAt(++index));
                    } else if (Character.isLowSurrogate(character)) {
                        throw new IllegalArgumentException("JSON string contains an unpaired low surrogate");
                    } else {
                        output.append(character);
                    }
                }
            }
        }
        output.append('"');
    }

    /// Prevents construction of the stateless canonicalizer.
    private CanonicalJson() {
    }
}
