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
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies the authenticated, bounded, line-delimited Protector control protocol.
@NotNullByDefault
public final class ProtectorProtocolTest {
    /// Canonical 256-bit test nonce encoded without padding.
    private static final String NONCE = "0123456789abcdef0123456789abcdef0123456789a";

    /// Different valid nonce used to verify authentication failure.
    private static final String OTHER_NONCE = "abcdef0123456789abcdef0123456789abcdef01234";

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

    /// Round-trips all startup stages and all control kinds needed by later supervision work.
    ///
    /// @throws Exception if a supported envelope cannot round-trip
    @Test
    public void roundTripEveryStageAndControlKind() throws Exception {
        ProtectorProtocol protocol = new ProtectorProtocol(NONCE);
        long timestamp = 1L;
        for (ProtectorStage stage : ProtectorStage.values()) {
            for (ProtectorMessage.Kind kind : ProtectorMessage.Kind.values()) {
                ProtectorMessage message = message(kind, timestamp++, stage);
                assertEquals(message, protocol.decode(protocol.encode(message)));
            }
        }
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

    /// Rejects negative and regressing monotonic timestamps while accepting equal timestamps.
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
        decoder.decode(encoder.encode(message(ProtectorMessage.Kind.STAGE, 10L, ProtectorStage.CORE_READY)));
        assertThrows(
                IOException.class,
                () -> decoder.decode(encoder.encode(message(
                        ProtectorMessage.Kind.HEARTBEAT,
                        9L,
                        ProtectorStage.CORE_READY
                )))
        );
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
        @Nullable String providerId = stage == ProtectorStage.RUNTIME_PROVIDERS_LOADING
                ? "org.example.provider"
                : null;
        @Nullable String pluginId = stage == ProtectorStage.ORDINARY_PLUGINS_LOADING
                ? "org.example.plugin"
                : null;
        return new ProtectorMessage(kind, timestamp, stage, providerId, pluginId);
    }

    /// Encodes one canonical heartbeat fixture.
    ///
    /// @return valid line-delimited envelope
    /// @throws IOException if encoding fails
    private static String validLine() throws IOException {
        return new ProtectorProtocol(NONCE).encode(message(
                ProtectorMessage.Kind.HEARTBEAT,
                1L,
                ProtectorStage.JVM_STARTED
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
