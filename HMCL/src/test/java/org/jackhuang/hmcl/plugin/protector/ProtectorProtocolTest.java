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

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies the authenticated, bounded, line-delimited Protector control protocol.
@NotNullByDefault
public final class ProtectorProtocolTest {
    /// Canonical 256-bit test nonce encoded without padding.
    private static final String NONCE = "0123456789abcdef0123456789abcdef0123456789a";

    /// Different valid nonce used to verify authentication failure.
    private static final String OTHER_NONCE = "abcdef0123456789abcdef0123456789abcdef01234";

    /// Exact kind-to-stage matrix accepted by the Task 10 control-flow contract.
    private static final @Unmodifiable Map<ProtectorMessage.Kind, @Unmodifiable Set<ProtectorStage>> LEGAL_STAGES =
            Map.of(
                    ProtectorMessage.Kind.HEARTBEAT, Set.of(ProtectorStage.values()),
                    ProtectorMessage.Kind.STAGE, Set.of(
                            ProtectorStage.JVM_STARTED,
                            ProtectorStage.CORE_READY,
                            ProtectorStage.RUNTIME_PROVIDERS_LOADING,
                            ProtectorStage.ORDINARY_PLUGINS_LOADING
                    ),
                    ProtectorMessage.Kind.READY, Set.of(ProtectorStage.UI_READY),
                    ProtectorMessage.Kind.CANCEL, Set.of(
                            ProtectorStage.JVM_STARTED,
                            ProtectorStage.CORE_READY,
                            ProtectorStage.RUNTIME_PROVIDERS_LOADING,
                            ProtectorStage.ORDINARY_PLUGINS_LOADING
                    ),
                    ProtectorMessage.Kind.NORMAL_SHUTDOWN, Set.of(ProtectorStage.values()),
                    ProtectorMessage.Kind.DIAGNOSTICS_REQUEST, preReadyStages(),
                    ProtectorMessage.Kind.DIAGNOSTICS_RESPONSE, preReadyStages(),
                    ProtectorMessage.Kind.LEASE_RENEWAL, Set.of(
                            ProtectorStage.CORE_READY,
                            ProtectorStage.RUNTIME_PROVIDERS_LOADING,
                            ProtectorStage.ORDINARY_PLUGINS_LOADING
                    ),
                    ProtectorMessage.Kind.TERMINATION_REQUEST, preReadyStages(),
                    ProtectorMessage.Kind.TERMINATION_ACKNOWLEDGED, preReadyStages()
            );

    /// Asserts every startup supervision duration exactly matches the approved design.
    @Test
    public void exposeExactStartupDeadlines() {
        assertEquals(Duration.ofSeconds(30), ProtectorProtocol.CONNECT_TIMEOUT);
        assertEquals(Duration.ofSeconds(5), ProtectorProtocol.HEARTBEAT_INTERVAL);
        assertEquals(Duration.ofSeconds(20), ProtectorProtocol.HEARTBEAT_LOSS_TIMEOUT);
        assertEquals(Duration.ofSeconds(90), ProtectorProtocol.CORE_READY_TIMEOUT);
        assertEquals(Duration.ofSeconds(60), ProtectorProtocol.PROVIDER_READY_TIMEOUT);
        assertEquals(Duration.ofSeconds(30), ProtectorProtocol.PLUGIN_READY_TIMEOUT);
        assertEquals(Duration.ofMinutes(10), ProtectorProtocol.HARD_STARTUP_TIMEOUT);
        assertEquals(Duration.ofSeconds(10), ProtectorProtocol.TERMINATION_GRACE_TIMEOUT);
    }

    /// Encodes a heartbeat as one stable bounded JSON line with the five-second cadence represented by the timestamp.
    ///
    /// @throws Exception if protocol encoding fails
    @Test
    public void encodeStableFiveSecondHeartbeatLine() throws Exception {
        ProtectorProtocol protocol = new ProtectorProtocol(NONCE);
        ProtectorMessage heartbeat = new ProtectorMessage(
                ProtectorMessage.Kind.HEARTBEAT,
                ProtectorProtocol.HEARTBEAT_INTERVAL.toNanos(),
                ProtectorStage.JVM_STARTED,
                null,
                null
        );

        String encoded = protocol.encode(heartbeat);

        assertEquals(
                "{\"version\":1,\"nonce\":\"" + NONCE + "\",\"timestampNanos\":5000000000,"
                        + "\"stage\":\"jvm-started\",\"activeProviderId\":null,"
                        + "\"activePluginId\":null,\"kind\":\"heartbeat\"}\n",
                encoded
        );
        assertEquals(1L, encoded.chars().filter(character -> character == '\n').count());
        assertTrue(encoded.getBytes(StandardCharsets.UTF_8).length <= ProtectorProtocol.MAX_MESSAGE_BYTES);
        assertEquals(heartbeat, protocol.decode(encoded));
    }

    /// Round-trips every legal kind-stage pair needed by later supervision work.
    ///
    /// @throws Exception if a supported envelope cannot round-trip
    @Test
    public void roundTripEveryLegalKindAndStageCombination() throws Exception {
        ProtectorProtocol protocol = new ProtectorProtocol(NONCE);
        long timestamp = 1L;
        for (ProtectorMessage.Kind kind : ProtectorMessage.Kind.values()) {
            for (ProtectorStage stage : LEGAL_STAGES.get(kind)) {
                ProtectorMessage message = message(kind, timestamp++, stage, identityBearing(kind));
                assertEquals(message, protocol.decode(protocol.encode(message)));
            }
        }
    }

    /// Rejects representative illegal stage combinations for every stage-restricted control kind.
    @Test
    public void rejectIllegalKindAndStageCombinations() {
        assertInvalidCombination(ProtectorMessage.Kind.STAGE, ProtectorStage.UI_READY);
        assertInvalidCombination(ProtectorMessage.Kind.READY, ProtectorStage.CORE_READY);
        assertInvalidCombination(ProtectorMessage.Kind.CANCEL, ProtectorStage.UI_READY);
        assertInvalidCombination(ProtectorMessage.Kind.DIAGNOSTICS_REQUEST, ProtectorStage.UI_READY);
        assertInvalidCombination(ProtectorMessage.Kind.DIAGNOSTICS_RESPONSE, ProtectorStage.UI_READY);
        assertInvalidCombination(ProtectorMessage.Kind.LEASE_RENEWAL, ProtectorStage.JVM_STARTED);
        assertInvalidCombination(ProtectorMessage.Kind.LEASE_RENEWAL, ProtectorStage.UI_READY);
        assertInvalidCombination(ProtectorMessage.Kind.TERMINATION_REQUEST, ProtectorStage.UI_READY);
        assertInvalidCombination(ProtectorMessage.Kind.TERMINATION_ACKNOWLEDGED, ProtectorStage.UI_READY);
    }

    /// Rejects active identities on terminal and parent-control messages while retaining them on state reports.
    @Test
    public void rejectActiveIdentitiesOnNonStateBearingKinds() {
        assertInvalidActiveIdentity(ProtectorMessage.Kind.READY, ProtectorStage.UI_READY);
        assertInvalidActiveIdentity(ProtectorMessage.Kind.CANCEL, ProtectorStage.RUNTIME_PROVIDERS_LOADING);
        assertInvalidActiveIdentity(ProtectorMessage.Kind.NORMAL_SHUTDOWN, ProtectorStage.RUNTIME_PROVIDERS_LOADING);
        assertInvalidActiveIdentity(ProtectorMessage.Kind.DIAGNOSTICS_REQUEST,
                ProtectorStage.RUNTIME_PROVIDERS_LOADING);
        assertInvalidActiveIdentity(ProtectorMessage.Kind.DIAGNOSTICS_RESPONSE,
                ProtectorStage.RUNTIME_PROVIDERS_LOADING);
        assertInvalidActiveIdentity(ProtectorMessage.Kind.TERMINATION_REQUEST,
                ProtectorStage.RUNTIME_PROVIDERS_LOADING);
        assertInvalidActiveIdentity(ProtectorMessage.Kind.TERMINATION_ACKNOWLEDGED,
                ProtectorStage.RUNTIME_PROVIDERS_LOADING);
    }

    /// Rejects a valid envelope authenticated with another nonce without exposing either nonce.
    ///
    /// @throws Exception if fixture encoding fails
    @Test
    public void rejectNonceMismatchWithoutEchoingSecrets() throws Exception {
        String hostile = new ProtectorProtocol(OTHER_NONCE).encode(message(
                ProtectorMessage.Kind.HEARTBEAT,
                1L,
                ProtectorStage.JVM_STARTED
        ));

        IOException exception = assertThrows(IOException.class, () -> new ProtectorProtocol(NONCE).decode(hostile));

        assertFalse(exception.getMessage().contains(NONCE));
        assertFalse(exception.getMessage().contains(OTHER_NONCE));
        assertFalse(exception.getMessage().contains(hostile));
    }

    /// Rejects a UTF-8 control document larger than sixteen KiB before parsing hostile content.
    @Test
    public void rejectOversizedControlDocument() {
        String hostile = "x".repeat(ProtectorProtocol.MAX_MESSAGE_BYTES + 1);

        IOException exception = assertThrows(
                IOException.class,
                () -> new ProtectorProtocol(NONCE).decode(hostile)
        );

        assertFalse(exception.getMessage().contains(hostile));
    }

    /// Rejects unsupported protocol versions, message kinds, stages, and unknown fields.
    ///
    /// @throws Exception if the valid fixture cannot be encoded
    @Test
    public void rejectUnknownEnvelopeVocabulary() throws Exception {
        String valid = validLine();

        assertRejected(valid.replace("\"version\":1", "\"version\":2"));
        assertRejected(valid.replace("\"kind\":\"heartbeat\"", "\"kind\":\"future-kind\""));
        assertRejected(valid.replace("\"stage\":\"jvm-started\"", "\"stage\":\"future-stage\""));
        assertRejected(valid.replace("\"kind\":", "\"futureField\":true,\"kind\":"));
    }

    /// Rejects duplicate properties and every scalar type mismatch instead of accepting Gson coercions.
    ///
    /// @throws Exception if the valid fixture cannot be encoded
    @Test
    public void rejectDuplicateAndWronglyTypedFields() throws Exception {
        String valid = validLine();

        assertRejected(valid.replace("\"version\":1", "\"version\":1,\"version\":1"));
        assertRejected(valid.replace("\"version\":1", "\"version\":\"1\""));
        assertRejected(valid.replace("\"nonce\":\"" + NONCE + "\"", "\"nonce\":1"));
        assertRejected(valid.replace("\"timestampNanos\":1", "\"timestampNanos\":1.5"));
        assertRejected(valid.replace("\"stage\":\"jvm-started\"", "\"stage\":false"));
        assertRejected(valid.replace("\"activeProviderId\":null", "\"activeProviderId\":false"));
        assertRejected(valid.replace("\"activePluginId\":null", "\"activePluginId\":[]"));
        assertRejected(valid.replace("\"kind\":\"heartbeat\"", "\"kind\":{}"));
    }

    /// Requires exactly one LF terminator and rejects multi-message or embedded-line confusion.
    ///
    /// @throws Exception if the valid fixture cannot be encoded
    @Test
    public void enforceSingleLineMessageBoundary() throws Exception {
        String valid = validLine();

        assertRejected(valid.substring(0, valid.length() - 1));
        assertRejected(valid + "\n");
        assertRejected(valid + valid);
        assertRejected(valid.substring(0, valid.length() - 2) + "\n}\n");
        assertRejected(valid.substring(0, valid.length() - 1) + "\r\n");
    }

    /// Rejects negative, replayed, and regressing monotonic timestamps.
    ///
    /// @throws Exception if fixture encoding fails
    @Test
    public void enforceMonotonicTimestampSequence() throws Exception {
        ProtectorProtocol encoder = new ProtectorProtocol(NONCE);
        ProtectorProtocol decoder = new ProtectorProtocol(NONCE);

        assertRejected(encoder.encode(message(
                ProtectorMessage.Kind.HEARTBEAT,
                1L,
                ProtectorStage.JVM_STARTED
        )).replace("\"timestampNanos\":1", "\"timestampNanos\":-1"));
        decoder.decode(encoder.encode(message(ProtectorMessage.Kind.HEARTBEAT, 10L, ProtectorStage.JVM_STARTED)));
        assertThrows(
                IOException.class,
                () -> decoder.decode(encoder.encode(message(
                        ProtectorMessage.Kind.STAGE,
                        10L,
                        ProtectorStage.CORE_READY
                )))
        );
        assertThrows(
                IOException.class,
                () -> decoder.decode(encoder.encode(message(
                        ProtectorMessage.Kind.HEARTBEAT,
                        9L,
                        ProtectorStage.CORE_READY
                )))
        );
    }

    /// Reads exactly one LF or CRLF frame without consuming the following frame.
    ///
    /// @throws Exception if bounded transport decoding fails
    @Test
    public void readOneNormalizedTransportLineAtATime() throws Exception {
        String first = validLine();
        String second = first.replace("\"timestampNanos\":1", "\"timestampNanos\":2");
        String transport = first.substring(0, first.length() - 1) + "\r\n" + second;
        InputStream input = chunked(transport.getBytes(StandardCharsets.UTF_8), 3);

        assertEquals(first, ProtectorProtocol.readLine(input));
        assertEquals(second, ProtectorProtocol.readLine(input));
        assertNull(ProtectorProtocol.readLine(input));
    }

    /// Distinguishes clean EOF from a truncated frame and rejects forbidden control bytes.
    @Test
    public void rejectTruncatedAndControlByteTransportFrames() {
        assertNull(assertDoesNotThrowRead(new byte[0]));
        assertThrows(IOException.class, () -> ProtectorProtocol.readLine(bytes("unterminated")));
        assertThrows(IOException.class, () -> ProtectorProtocol.readLine(bytes("nul\0byte\n")));
        assertThrows(IOException.class, () -> ProtectorProtocol.readLine(bytes("bare\rcarriage\n")));
    }

    /// Rejects malformed UTF-8 before constructing a Java protocol line.
    @Test
    public void rejectMalformedUtf8TransportFrame() {
        byte[] malformed = {(byte) 0xc3, (byte) 0x28, (byte) '\n'};

        assertThrows(IOException.class, () -> ProtectorProtocol.readLine(new ByteArrayInputStream(malformed)));
    }

    /// Accepts the exact byte bound and reads only one byte beyond it to reject an oversized stream.
    ///
    /// @throws Exception if exact-bound framing fails
    @Test
    public void enforceBoundedTransportReadBeforeAllocation() throws Exception {
        byte[] exact = new byte[ProtectorProtocol.MAX_MESSAGE_BYTES];
        java.util.Arrays.fill(exact, (byte) 'x');
        exact[exact.length - 1] = (byte) '\n';
        assertEquals(ProtectorProtocol.MAX_MESSAGE_BYTES, ProtectorProtocol.readLine(
                new ByteArrayInputStream(exact)
        ).length());

        byte[] oversized = new byte[ProtectorProtocol.MAX_MESSAGE_BYTES + 2];
        java.util.Arrays.fill(oversized, (byte) 'x');
        oversized[oversized.length - 1] = (byte) '\n';
        CountingInputStream input = new CountingInputStream(oversized);

        assertThrows(IOException.class, () -> ProtectorProtocol.readLine(input));
        assertEquals(ProtectorProtocol.MAX_MESSAGE_BYTES + 1, input.readCount());
    }

    /// Accepts canonical active identities only on their matching loading stages.
    ///
    /// @throws Exception if valid messages cannot round-trip
    @Test
    public void constrainActiveIdentityToMatchingStage() throws Exception {
        ProtectorProtocol protocol = new ProtectorProtocol(NONCE);
        ProtectorMessage provider = new ProtectorMessage(
                ProtectorMessage.Kind.STAGE,
                1L,
                ProtectorStage.RUNTIME_PROVIDERS_LOADING,
                "org.example.rust-provider",
                null
        );
        ProtectorMessage plugin = new ProtectorMessage(
                ProtectorMessage.Kind.STAGE,
                2L,
                ProtectorStage.ORDINARY_PLUGINS_LOADING,
                null,
                "org.example.language-plugin"
        );

        assertEquals(provider, protocol.decode(protocol.encode(provider)));
        assertEquals(plugin, protocol.decode(protocol.encode(plugin)));
        assertThrows(IllegalArgumentException.class, () -> new ProtectorMessage(
                ProtectorMessage.Kind.STAGE,
                3L,
                ProtectorStage.CORE_READY,
                "org.example.provider",
                null
        ));
        assertThrows(IllegalArgumentException.class, () -> new ProtectorMessage(
                ProtectorMessage.Kind.STAGE,
                3L,
                ProtectorStage.RUNTIME_PROVIDERS_LOADING,
                null,
                "org.example.plugin"
        ));
    }

    /// Treats JSON null as no active identity and rejects blank, unsafe, or non-canonical IDs.
    ///
    /// @throws Exception if null-valued stage messages cannot round-trip
    @Test
    public void enforceActiveIdentityNullAndSafetyBoundary() throws Exception {
        ProtectorProtocol protocol = new ProtectorProtocol(NONCE);
        ProtectorMessage noActiveProvider = new ProtectorMessage(
                ProtectorMessage.Kind.HEARTBEAT,
                1L,
                ProtectorStage.RUNTIME_PROVIDERS_LOADING,
                null,
                null
        );

        assertEquals(noActiveProvider, protocol.decode(protocol.encode(noActiveProvider)));
        assertInvalidProviderId("");
        assertInvalidProviderId("Org.Example.Provider");
        assertInvalidProviderId("../org.example.provider");
        assertInvalidProviderId("con.provider");
        assertInvalidProviderId("org.example.provider\nforged");
        assertInvalidPluginId("");
        assertInvalidPluginId("Org.Example.Plugin");
        assertInvalidPluginId("../org.example.plugin");
    }

    /// Creates one valid message with stage-appropriate active identity fields.
    ///
    /// @param kind control message kind
    /// @param timestamp monotonic timestamp
    /// @param stage startup stage
    /// @return valid message
    private static ProtectorMessage message(
            ProtectorMessage.Kind kind,
            long timestamp,
            ProtectorStage stage
    ) {
        return message(kind, timestamp, stage, true);
    }

    /// Creates one message with optional stage-appropriate active identity fields.
    ///
    /// @param kind control message kind
    /// @param timestamp monotonic timestamp
    /// @param stage startup stage
    /// @param includeActiveIdentity whether to include a matching active identity
    /// @return message candidate
    private static ProtectorMessage message(
            ProtectorMessage.Kind kind,
            long timestamp,
            ProtectorStage stage,
            boolean includeActiveIdentity
    ) {
        @Nullable String providerId = includeActiveIdentity
                && stage == ProtectorStage.RUNTIME_PROVIDERS_LOADING
                ? "org.example.provider"
                : null;
        @Nullable String pluginId = includeActiveIdentity
                && stage == ProtectorStage.ORDINARY_PLUGINS_LOADING
                ? "org.example.plugin"
                : null;
        return new ProtectorMessage(kind, timestamp, stage, providerId, pluginId);
    }

    /// Returns every startup stage before UI readiness.
    ///
    /// @return immutable pre-ready stage set
    private static @Unmodifiable Set<ProtectorStage> preReadyStages() {
        return Set.of(
                ProtectorStage.JVM_STARTED,
                ProtectorStage.CORE_READY,
                ProtectorStage.RUNTIME_PROVIDERS_LOADING,
                ProtectorStage.ORDINARY_PLUGINS_LOADING
        );
    }

    /// Returns whether a kind is allowed to carry a stage-appropriate active identity.
    ///
    /// @param kind control message kind
    /// @return whether active identity fields carry state for this kind
    private static boolean identityBearing(ProtectorMessage.Kind kind) {
        return kind == ProtectorMessage.Kind.HEARTBEAT
                || kind == ProtectorMessage.Kind.STAGE
                || kind == ProtectorMessage.Kind.LEASE_RENEWAL;
    }

    /// Asserts one kind-stage pair is rejected at construction so it can never be encoded.
    ///
    /// @param kind control message kind
    /// @param stage illegal stage
    private static void assertInvalidCombination(ProtectorMessage.Kind kind, ProtectorStage stage) {
        assertThrows(IllegalArgumentException.class, () -> message(kind, 1L, stage, false));
    }

    /// Asserts one non-state-bearing kind rejects a matching active Provider identity.
    ///
    /// @param kind control message kind
    /// @param stage stage on which an active Provider would otherwise be structurally valid
    private static void assertInvalidActiveIdentity(ProtectorMessage.Kind kind, ProtectorStage stage) {
        assertThrows(IllegalArgumentException.class, () -> new ProtectorMessage(
                kind,
                1L,
                stage,
                "org.example.provider",
                null
        ));
    }

    /// Encodes one canonical heartbeat fixture.
    ///
    /// @return valid line-delimited envelope
    /// @throws IOException if encoding fails
    private static String validLine() throws IOException {
        return new ProtectorProtocol(NONCE).encode(message(
                ProtectorMessage.Kind.HEARTBEAT,
                1L,
                ProtectorStage.JVM_STARTED,
                true
        ));
    }

    /// Asserts that one hostile document is rejected without echoing its content.
    ///
    /// @param hostile hostile wire document
    private static void assertRejected(String hostile) {
        IOException exception = assertThrows(
                IOException.class,
                () -> new ProtectorProtocol(NONCE).decode(hostile)
        );
        assertFalse(exception.getMessage().contains(hostile));
    }

    /// Creates a byte stream from ASCII-compatible test content.
    ///
    /// @param value test content
    /// @return byte stream
    private static InputStream bytes(String value) {
        return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
    }

    /// Splits bytes across real component streams to model arbitrarily chunked transport delivery.
    ///
    /// @param value complete transport bytes
    /// @param chunkSize maximum bytes in each component stream
    /// @return sequential chunked stream
    private static InputStream chunked(byte @Unmodifiable [] value, int chunkSize) {
        List<InputStream> chunks = new ArrayList<>();
        for (int offset = 0; offset < value.length; offset += chunkSize) {
            chunks.add(new ByteArrayInputStream(
                    value,
                    offset,
                    Math.min(chunkSize, value.length - offset)
            ));
        }
        return new SequenceInputStream(Collections.enumeration(chunks));
    }

    /// Reads one fixture while converting an unexpected checked failure into an assertion failure.
    ///
    /// @param value fixture bytes
    /// @return decoded line or `null` at clean EOF
    private static @Nullable String assertDoesNotThrowRead(byte @Unmodifiable [] value) {
        try {
            return ProtectorProtocol.readLine(new ByteArrayInputStream(value));
        } catch (IOException exception) {
            throw new AssertionError(exception);
        }
    }

    /// Input stream that counts bounded single-byte reads.
    @NotNullByDefault
    private static final class CountingInputStream extends InputStream {
        /// Complete source bytes.
        private final byte @Unmodifiable [] source;

        /// Current source offset.
        private int position;

        /// Number of bytes returned to the reader.
        private int readCount;

        /// Creates one counted source.
        ///
        /// @param source complete source bytes
        private CountingInputStream(byte @Unmodifiable [] source) {
            this.source = source.clone();
        }

        /// Returns the next source byte.
        ///
        /// @return unsigned byte, or `-1` at EOF
        @Override
        public int read() {
            if (position >= source.length) {
                return -1;
            }
            readCount++;
            return source[position++] & 0xff;
        }

        /// Returns the number of bytes exposed to the reader.
        ///
        /// @return byte read count
        private int readCount() {
            return readCount;
        }
    }

    /// Asserts one provider ID is rejected by the canonical identity boundary.
    ///
    /// @param providerId invalid provider ID
    private static void assertInvalidProviderId(String providerId) {
        assertThrows(IllegalArgumentException.class, () -> new ProtectorMessage(
                ProtectorMessage.Kind.STAGE,
                1L,
                ProtectorStage.RUNTIME_PROVIDERS_LOADING,
                providerId,
                null
        ));
    }

    /// Asserts one ordinary plugin ID is rejected by the canonical identity boundary.
    ///
    /// @param pluginId invalid plugin ID
    private static void assertInvalidPluginId(String pluginId) {
        assertThrows(IllegalArgumentException.class, () -> new ProtectorMessage(
                ProtectorMessage.Kind.STAGE,
                1L,
                ProtectorStage.ORDINARY_PLUGINS_LOADING,
                null,
                pluginId
        ));
    }
}
