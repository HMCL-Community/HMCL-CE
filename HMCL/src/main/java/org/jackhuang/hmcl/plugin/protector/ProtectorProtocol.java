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
package org.jackhuang.hmcl.plugin.protector;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/// Encodes and strictly decodes one authenticated local Protector session.
///
/// Each JSON envelope occupies exactly one LF-terminated UTF-8 line. A protocol instance tracks the last accepted
/// monotonic timestamp so replayed or reordered messages fail before they affect startup supervision state.
@NotNullByDefault
public final class ProtectorProtocol {
    /// Current control-envelope schema version.
    public static final int VERSION = 1;

    /// Maximum encoded UTF-8 message size including its LF terminator.
    public static final int MAX_MESSAGE_BYTES = 16 * 1024;

    /// Maximum time for the protected child to establish its authenticated session.
    public static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);

    /// Interval between protected-child heartbeat messages.
    public static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(5);

    /// Maximum accepted time without a heartbeat after connection.
    public static final Duration HEARTBEAT_LOSS_TIMEOUT = Duration.ofSeconds(20);

    /// Maximum time from JVM start to Core readiness.
    public static final Duration CORE_READY_TIMEOUT = Duration.ofSeconds(90);

    /// Maximum startup lease for one active Runtime Provider.
    public static final Duration PROVIDER_READY_TIMEOUT = Duration.ofSeconds(60);

    /// Maximum startup lease for one active ordinary plugin.
    public static final Duration PLUGIN_READY_TIMEOUT = Duration.ofSeconds(30);

    /// Absolute startup duration that lease renewal cannot extend.
    public static final Duration HARD_STARTUP_TIMEOUT = Duration.ofMinutes(10);

    /// Grace period after diagnostics and graceful termination are requested.
    public static final Duration TERMINATION_GRACE_TIMEOUT = Duration.ofSeconds(10);

    /// Exact 256-bit URL-safe unpadded nonce syntax.
    private static final Pattern NONCE_PATTERN = Pattern.compile("[A-Za-z0-9_-]{43}");

    /// Unsigned canonical JSON integer syntax.
    private static final Pattern UNSIGNED_INTEGER_PATTERN = Pattern.compile("0|[1-9][0-9]*");

    /// Compact JSON encoder preserving explicit field insertion order.
    private static final Gson GSON = new GsonBuilder().serializeNulls().create();

    /// Session authentication nonce retained only in memory.
    private final String nonce;

    /// Last accepted sender monotonic timestamp, or `-1` before the first message.
    private long lastDecodedTimestampNanos = -1L;

    /// Creates one authenticated session codec.
    ///
    /// @param nonce URL-safe unpadded encoding of a random 256-bit nonce
    public ProtectorProtocol(String nonce) {
        if (!NONCE_PATTERN.matcher(nonce).matches()) {
            throw new IllegalArgumentException("Protector nonce must encode exactly 256 bits");
        }
        this.nonce = nonce;
    }

    /// Encodes one stable, single-line, bounded JSON envelope.
    ///
    /// @param message validated control payload
    /// @return exactly one LF-terminated JSON line
    /// @throws IOException if the resulting envelope exceeds the protocol bound
    public String encode(ProtectorMessage message) throws IOException {
        JsonObject envelope = new JsonObject();
        envelope.addProperty("version", VERSION);
        envelope.addProperty("nonce", nonce);
        envelope.addProperty("timestampNanos", message.monotonicTimestampNanos());
        envelope.addProperty("stage", message.stage().wireName());
        addNullableString(envelope, "activeProviderId", message.activeProviderId());
        addNullableString(envelope, "activePluginId", message.activePluginId());
        envelope.addProperty("kind", message.kind().wireName());
        String encoded = GSON.toJson(envelope) + "\n";
        if (encoded.length() > MAX_MESSAGE_BYTES
                || encoded.getBytes(StandardCharsets.UTF_8).length > MAX_MESSAGE_BYTES) {
            throw invalidMessage();
        }
        return encoded;
    }

    /// Reads one bounded UTF-8 transport frame without consuming bytes from the following frame.
    ///
    /// LF and CRLF are accepted and returned as one LF-terminated Java string. Clean EOF before any byte returns
    /// `null`; EOF after any byte, NUL, bare CR, malformed UTF-8, and frames exceeding the sixteen-KiB bound fail.
    /// At most one byte beyond the bound is consumed to establish that a frame is oversized.
    ///
    /// @param input unbuffered or caller-buffered Protector transport
    /// @return one normalized LF-terminated line, or `null` at clean EOF
    /// @throws IOException if framing, size, or UTF-8 validation fails
    public static @Nullable String readLine(InputStream input) throws IOException {
        byte[] bytes = new byte[MAX_MESSAGE_BYTES];
        int length = 0;
        while (true) {
            int next = input.read();
            if (next == -1) {
                if (length == 0) {
                    return null;
                }
                throw invalidMessage();
            }
            if (length == MAX_MESSAGE_BYTES) {
                throw invalidMessage();
            }
            if (next == 0) {
                throw invalidMessage();
            }
            bytes[length++] = (byte) next;
            if (next == '\n') {
                break;
            }
        }

        int contentLength = length - 1;
        if (contentLength > 0 && bytes[contentLength - 1] == '\r') {
            contentLength--;
        }
        for (int index = 0; index < contentLength; index++) {
            if (bytes[index] == '\r') {
                throw invalidMessage();
            }
        }
        String content;
        try {
            content = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, 0, contentLength))
                    .toString();
        } catch (IOException exception) {
            throw invalidMessage();
        }
        return content + "\n";
    }

    /// Strictly decodes one authenticated line and enforces strictly increasing sender monotonic time.
    ///
    /// @param encoded exactly one LF-terminated UTF-8 JSON document represented as a Java string
    /// @return validated control payload
    /// @throws IOException if authentication, framing, schema, type, bound, or monotonic validation fails
    public synchronized ProtectorMessage decode(String encoded) throws IOException {
        if (encoded.length() > MAX_MESSAGE_BYTES
                || encoded.getBytes(StandardCharsets.UTF_8).length > MAX_MESSAGE_BYTES
                || encoded.length() < 2
                || encoded.charAt(encoded.length() - 1) != '\n'
                || encoded.indexOf('\n') != encoded.length() - 1
                || encoded.indexOf('\r') >= 0) {
            throw invalidMessage();
        }

        ParsedEnvelope parsed = parseEnvelope(encoded.substring(0, encoded.length() - 1));
        if (!MessageDigest.isEqual(
                nonce.getBytes(StandardCharsets.UTF_8),
                parsed.nonce().getBytes(StandardCharsets.UTF_8)
        )) {
            throw new IOException("Protector message authentication failed");
        }
        if (lastDecodedTimestampNanos >= 0L && parsed.timestampNanos() <= lastDecodedTimestampNanos) {
            throw new IOException("Protector message timestamp did not increase");
        }

        ProtectorMessage message;
        try {
            message = new ProtectorMessage(
                    parsed.kind(),
                    parsed.timestampNanos(),
                    parsed.stage(),
                    parsed.activeProviderId(),
                    parsed.activePluginId()
            );
        } catch (IllegalArgumentException exception) {
            throw invalidMessage(exception);
        }
        lastDecodedTimestampNanos = parsed.timestampNanos();
        return message;
    }

    /// Adds one explicit JSON string or JSON null property.
    ///
    /// @param object destination object
    /// @param name property name
    /// @param value property value, or `null`
    private static void addNullableString(JsonObject object, String name, @Nullable String value) {
        if (value == null) {
            object.add(name, JsonNull.INSTANCE);
        } else {
            object.addProperty(name, value);
        }
    }

    /// Parses one JSON object with exact fields, types, and vocabulary.
    ///
    /// @param json unterminated JSON document
    /// @return strictly validated envelope components
    /// @throws IOException if the document is malformed or unsupported
    private static ParsedEnvelope parseEnvelope(String json) throws IOException {
        boolean hasVersion = false;
        boolean hasNonce = false;
        boolean hasTimestamp = false;
        boolean hasStage = false;
        boolean hasActiveProvider = false;
        boolean hasActivePlugin = false;
        boolean hasKind = false;
        int version = 0;
        long timestampNanos = -1L;
        @Nullable String parsedNonce = null;
        @Nullable String stageName = null;
        @Nullable String activeProviderId = null;
        @Nullable String activePluginId = null;
        @Nullable String kindName = null;
        Set<String> fields = new HashSet<>();

        try (JsonReader reader = new JsonReader(new StringReader(json))) {
            reader.setStrictness(Strictness.STRICT);
            reader.beginObject();
            while (reader.hasNext()) {
                String name = reader.nextName();
                if (!fields.add(name)) {
                    throw invalidMessage();
                }
                switch (name) {
                    case "version" -> {
                        version = readUnsignedInt(reader);
                        hasVersion = true;
                    }
                    case "nonce" -> {
                        parsedNonce = readRequiredString(reader);
                        hasNonce = true;
                    }
                    case "timestampNanos" -> {
                        timestampNanos = readUnsignedLong(reader);
                        hasTimestamp = true;
                    }
                    case "stage" -> {
                        stageName = readRequiredString(reader);
                        hasStage = true;
                    }
                    case "activeProviderId" -> {
                        activeProviderId = readNullableString(reader);
                        hasActiveProvider = true;
                    }
                    case "activePluginId" -> {
                        activePluginId = readNullableString(reader);
                        hasActivePlugin = true;
                    }
                    case "kind" -> {
                        kindName = readRequiredString(reader);
                        hasKind = true;
                    }
                    default -> throw invalidMessage();
                }
            }
            reader.endObject();
            if (reader.peek() != JsonToken.END_DOCUMENT) {
                throw invalidMessage();
            }
        } catch (IOException | IllegalStateException | NumberFormatException exception) {
            throw invalidMessage();
        }

        if (!hasVersion || version != VERSION || !hasNonce || parsedNonce == null
                || !hasTimestamp || !hasStage || stageName == null
                || !hasActiveProvider || !hasActivePlugin || !hasKind || kindName == null) {
            throw invalidMessage();
        }
        @Nullable ProtectorStage stage = ProtectorStage.fromWireName(stageName);
        @Nullable ProtectorMessage.Kind kind = ProtectorMessage.Kind.fromWireName(kindName);
        if (stage == null || kind == null) {
            throw invalidMessage();
        }
        return new ParsedEnvelope(
                parsedNonce,
                timestampNanos,
                stage,
                activeProviderId,
                activePluginId,
                kind
        );
    }

    /// Reads one exact JSON string token.
    ///
    /// @param reader strict JSON reader
    /// @return decoded string
    /// @throws IOException if the next token is not a string
    private static String readRequiredString(JsonReader reader) throws IOException {
        if (reader.peek() != JsonToken.STRING) {
            throw invalidMessage();
        }
        return reader.nextString();
    }

    /// Reads one exact JSON string or null token.
    ///
    /// @param reader strict JSON reader
    /// @return decoded string, or `null`
    /// @throws IOException if the next token has another type
    private static @Nullable String readNullableString(JsonReader reader) throws IOException {
        JsonToken token = reader.peek();
        if (token == JsonToken.NULL) {
            reader.nextNull();
            return null;
        }
        if (token != JsonToken.STRING) {
            throw invalidMessage();
        }
        return reader.nextString();
    }

    /// Reads one canonical non-negative JSON integer in the Java `int` range.
    ///
    /// @param reader strict JSON reader
    /// @return decoded integer
    /// @throws IOException if the token is not canonical or exceeds the range
    private static int readUnsignedInt(JsonReader reader) throws IOException {
        long value = readUnsignedLong(reader);
        if (value > Integer.MAX_VALUE) {
            throw invalidMessage();
        }
        return (int) value;
    }

    /// Reads one canonical non-negative JSON integer in the Java `long` range.
    ///
    /// @param reader strict JSON reader
    /// @return decoded integer
    /// @throws IOException if the token is not canonical or exceeds the range
    private static long readUnsignedLong(JsonReader reader) throws IOException {
        if (reader.peek() != JsonToken.NUMBER) {
            throw invalidMessage();
        }
        String number = reader.nextString();
        if (!UNSIGNED_INTEGER_PATTERN.matcher(number).matches()) {
            throw invalidMessage();
        }
        try {
            return Long.parseLong(number);
        } catch (NumberFormatException exception) {
            throw invalidMessage(exception);
        }
    }

    /// Creates a fixed diagnostic that contains no hostile input or authentication material.
    ///
    /// @return safe protocol exception
    private static IOException invalidMessage() {
        return new IOException("Invalid Protector control message");
    }

    /// Creates a fixed diagnostic with a parser cause that contains no raw input.
    ///
    /// @param cause parser or validation failure
    /// @return safe protocol exception
    private static IOException invalidMessage(Exception cause) {
        return new IOException("Invalid Protector control message", cause);
    }

    /// Strictly parsed envelope fields before nonce and monotonic-sequence validation.
    ///
    /// @param nonce presented authentication nonce
    /// @param timestampNanos sender monotonic timestamp
    /// @param stage current startup stage
    /// @param activeProviderId active Runtime Provider ID, or `null`
    /// @param activePluginId active ordinary plugin ID, or `null`
    /// @param kind control message kind
    @NotNullByDefault
    private record ParsedEnvelope(
            String nonce,
            long timestampNanos,
            ProtectorStage stage,
            @Nullable String activeProviderId,
            @Nullable String activePluginId,
            ProtectorMessage.Kind kind
    ) {
        /// Creates one parsed envelope after exact field validation.
        private ParsedEnvelope {
        }
    }
}
