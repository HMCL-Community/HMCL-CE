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
package org.jackhuang.hmcl.plugin;

import org.jackhuang.hmcl.launch.LaunchAuxiliaryProcessPlan;
import org.jackhuang.hmcl.launch.LaunchCommandPlan;
import org.jackhuang.hmcl.launch.LaunchExecutionMode;
import org.jackhuang.hmcl.launch.LaunchPlanText;
import org.jackhuang.hmcl.launch.LaunchProcessPlan;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies symmetric game-launch Hook encoding and launch-scoped secret isolation.
@NotNullByDefault
public final class GameLaunchHookCodecTest {
    /// Preserves every mutable structured process-plan field through the Hook envelope.
    @Test
    public void structuredPlanRoundTripPreservesEveryMutableField() {
        LaunchProcessPlan original = completeStructuredPlan();
        PluginDataObject metadata = immutableMetadata();

        PluginDataObject encoded = GameLaunchHookCodec.encodeBefore(original, metadata);
        LaunchProcessPlan decoded = GameLaunchHookCodec.decodeBefore(
                encoded, metadata, Set.of("access-token"));

        assertEquals(original, decoded);
        assertEquals(metadata, encoded.requireObject("metadata"));
        assertEquals(BigDecimal.ONE, encoded.requireNumber("contractVersion"));
        assertFalse(containsString(encoded, "top-secret"));
    }

    /// Preserves exact raw-command order independently of structured Java fields.
    @Test
    public void rawPlanRoundTripPreservesExactTokens() {
        LaunchProcessPlan original = completeStructuredPlan().withCommand(LaunchCommandPlan.raw(List.of(
                LaunchPlanText.literal("runtime-host"),
                LaunchPlanText.literal("--launch"),
                secretText("--token=")
        )));

        PluginDataObject encoded = GameLaunchHookCodec.encodeBefore(original, immutableMetadata());
        LaunchProcessPlan decoded = GameLaunchHookCodec.decodeBefore(
                encoded, immutableMetadata(), Set.of("access-token"));

        assertEquals(original, decoded);
        assertEquals("raw", encoded.requireObject("plan").requireObject("command").requireString("mode"));
    }

    /// Rejects immutable metadata changes before accepting a replacement plan.
    @Test
    public void immutableMetadataRewriteIsRejected() {
        PluginDataObject encoded = GameLaunchHookCodec.encodeBefore(
                completeStructuredPlan(), immutableMetadata());
        PluginDataObject rewritten = encoded.with("metadata", PluginDataValue.object(
                immutableMetadata().with("instanceId", PluginDataValue.string("other"))));

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> GameLaunchHookCodec.decodeBefore(
                        rewritten, immutableMetadata(), Set.of("access-token")));

        assertFalse(failure.getMessage().contains("top-secret"));
    }

    /// Rejects unsupported contracts, missing required fields, and version-one unknown plan fields.
    @Test
    public void malformedVersionOneEnvelopeIsRejected() {
        PluginDataObject encoded = GameLaunchHookCodec.encodeBefore(
                completeStructuredPlan(), immutableMetadata());
        PluginDataObject badVersion = encoded.with("contractVersion", PluginDataValue.number(new BigDecimal("2")));
        PluginDataObject missingPlan = encoded.without("plan");
        PluginDataObject unknownPlanField = encoded.with("plan", PluginDataValue.object(
                encoded.requireObject("plan").with("futureField", PluginDataValue.bool(true))));

        assertThrows(IllegalArgumentException.class,
                () -> GameLaunchHookCodec.decodeBefore(badVersion, Set.of("access-token")));
        assertThrows(IllegalArgumentException.class,
                () -> GameLaunchHookCodec.decodeBefore(missingPlan, Set.of("access-token")));
        assertThrows(IllegalArgumentException.class,
                () -> GameLaunchHookCodec.decodeBefore(unknownPlanField, Set.of("access-token")));
    }

    /// Rejects plan references to slots that are unavailable after protected updates.
    @Test
    public void unknownSecretSlotIsRejectedDuringDecode() {
        PluginDataObject encoded = GameLaunchHookCodec.encodeBefore(
                completeStructuredPlan(), immutableMetadata());

        assertThrows(IllegalArgumentException.class,
                () -> GameLaunchHookCodec.decodeBefore(encoded, Set.of()));
    }

    /// Gives account-authorized callbacks copied secret values and uniformly denies other callbacks.
    @Test
    public void secretAccessorEnforcesAccountPermission() {
        GameLaunchSecretStore store = secretStore();

        assertEquals("top-secret", store.accessor(true).resolve("access-token"));
        assertThrows(PluginPermissionException.class,
                () -> store.accessor(false).resolve("access-token"));
        assertThrows(PluginPermissionException.class,
                () -> store.accessor(false).resolve("unknown-slot"));
        assertThrows(IllegalArgumentException.class,
                () -> store.accessor(true).resolve("unknown-slot"));
    }

    /// Applies only authorized canonical protected updates referenced by the candidate plan.
    @Test
    public void protectedUpdatesRequirePermissionAndPlanReferences() {
        GameLaunchSecretStore store = secretStore();

        assertThrows(PluginPermissionException.class,
                () -> store.applyProtectedUpdates("dev.test.denied", Map.of("new-slot", "new-value"),
                        false, Set.of("new-slot")));
        assertThrows(PluginHookDispatchException.class,
                () -> store.applyProtectedUpdates("dev.test.invalid", Map.of("Bad Slot", "new-value"),
                        true, Set.of("Bad Slot")));
        assertThrows(PluginHookDispatchException.class,
                () -> store.applyProtectedUpdates("dev.test.unused", Map.of("new-slot", "new-value"),
                        true, Set.of("access-token")));

        store.applyProtectedUpdates("dev.test.allowed", Map.of(
                "access-token", "updated-secret",
                "new-slot", "new-value"
        ), true, Set.of("access-token", "new-slot"));

        assertEquals(Set.of("access-token", "new-slot"), store.slots());
        assertEquals("updated-secret", store.resolve("access-token"));
        assertEquals("new-value", store.resolve("new-slot"));
    }

    /// Rejects privileged ordinary data containing a complete secret or any secret substring.
    @Test
    public void privilegedLiteralSecretIsRejectedBeforeNextSubscriber() {
        GameLaunchSecretStore store = secretStore();
        PluginDataObject exact = PluginDataObject.of(Map.of(
                "plan", PluginDataValue.string("top-secret")));
        PluginDataObject substring = PluginDataObject.of(Map.of(
                "plan", PluginDataValue.object(PluginDataObject.of(Map.of(
                        "argument", PluginDataValue.string("--token=top-secret-suffix"))))));

        PluginHookDispatchException exactFailure = assertThrows(PluginHookDispatchException.class,
                () -> store.validateOrdinaryData("dev.test.exact", exact, true));
        PluginHookDispatchException substringFailure = assertThrows(PluginHookDispatchException.class,
                () -> store.validateOrdinaryData("dev.test.substring", substring, true));

        assertTrue(exactFailure.getMessage().contains("dev.test.exact"));
        assertFalse(exactFailure.getMessage().contains("top-secret"));
        assertFalse(substringFailure.getMessage().contains("top-secret"));
    }

    /// Does not reveal stored-slot existence or scan inaccessible values for denied callbacks.
    @Test
    public void deniedCallbacksCannotProbeStoredSecrets() {
        GameLaunchSecretStore store = secretStore();
        PluginDataObject opaque = PluginDataObject.of(Map.of(
                "value", PluginDataValue.string("top-secret")));

        store.validateOrdinaryData("dev.test.denied", opaque, false);
        PluginPermissionException known = assertThrows(PluginPermissionException.class,
                () -> store.accessor(false).resolve("access-token"));
        PluginPermissionException unknown = assertThrows(PluginPermissionException.class,
                () -> store.accessor(false).resolve("unknown-slot"));

        assertEquals(known.getMessage(), unknown.getMessage());
    }

    /// Encodes after-process observations while retaining only opaque secret references.
    @Test
    public void afterEnvelopeContainsObservationsWithoutResolvedSecrets() {
        Instant startedAt = Instant.parse("2026-08-24T01:02:03Z");
        Instant endedAt = startedAt.plusMillis(2500);

        PluginDataObject encoded = GameLaunchHookCodec.encodeAfter(
                completeStructuredPlan(),
                immutableMetadata(),
                4242L,
                137,
                "externally-killed",
                startedAt,
                endedAt,
                2500L
        );

        assertEquals(new BigDecimal("4242"), encoded.requireNumber("pid"));
        assertEquals(new BigDecimal("137"), encoded.requireNumber("exitCode"));
        assertEquals("externally-killed", encoded.requireString("terminationKind"));
        assertEquals(startedAt.toString(), encoded.requireString("startedAt"));
        assertEquals(endedAt.toString(), encoded.requireString("endedAt"));
        assertEquals(new BigDecimal("2500"), encoded.requireNumber("elapsedMilliseconds"));
        assertFalse(containsString(encoded, "top-secret"));
    }

    /// Represents absent process exit codes as JSON null.
    @Test
    public void afterEnvelopeSupportsAbsentExitCode() {
        PluginDataObject encoded = GameLaunchHookCodec.encodeAfter(
                completeStructuredPlan(), immutableMetadata(), 1L, null, "unknown",
                Instant.EPOCH, Instant.EPOCH, 0L);

        assertEquals(PluginDataValue.nullValue(), encoded.get("exitCode"));
    }

    /// Creates a structured fixture containing every mutable plan field and secret segment form.
    ///
    /// @return complete structured launch plan
    private static LaunchProcessPlan completeStructuredPlan() {
        Path root = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().resolve("hmcl-hook-codec");
        Map<String, LaunchPlanText> environment = new LinkedHashMap<>();
        environment.put("INST_ID", LaunchPlanText.literal("example"));
        environment.put("ACCESS_HINT", secretText("Bearer "));
        LaunchAuxiliaryProcessPlan preLaunch = new LaunchAuxiliaryProcessPlan(
                List.of(LaunchPlanText.literal("helper"), LaunchPlanText.literal("before")),
                root.resolve("before"), true,
                Map.of("HELPER_MODE", LaunchPlanText.literal("before")), Set.of("OLD_HELPER"));
        LaunchAuxiliaryProcessPlan postExit = new LaunchAuxiliaryProcessPlan(
                List.of(LaunchPlanText.literal("helper"), LaunchPlanText.literal("after")),
                root.resolve("after"), false,
                Map.of("HELPER_MODE", LaunchPlanText.literal("after")), Set.of("OLD_HELPER"));
        LaunchCommandPlan command = LaunchCommandPlan.structuredJava(
                List.of(LaunchPlanText.literal("nice"), LaunchPlanText.literal("-n"),
                        LaunchPlanText.literal("1")),
                LaunchPlanText.literal(root.resolve("jdk").resolve("java").toString()),
                List.of(LaunchPlanText.literal("-Xmx2G"), secretText("-Dtoken=")),
                List.of(LaunchPlanText.literal(root.resolve("a.jar").toString()),
                        LaunchPlanText.literal(root.resolve("b.jar").toString())),
                LaunchPlanText.literal("net.minecraft.client.main.Main"),
                List.of(LaunchPlanText.literal("--username"), LaunchPlanText.literal("Alex"))
        );
        return new LaunchProcessPlan(
                LaunchProcessPlan.CURRENT_PLAN_VERSION,
                LaunchExecutionMode.DIRECT,
                command,
                root,
                true,
                environment,
                Set.of("OLD_VALUE"),
                preLaunch,
                postExit,
                "hide-and-reopen",
                false,
                true
        );
    }

    /// Creates immutable launch metadata independent of mutable process-plan fields.
    ///
    /// @return immutable metadata fixture
    private static PluginDataObject immutableMetadata() {
        return PluginDataObject.of(Map.of(
                "instanceId", PluginDataValue.string("example-instance"),
                "gameVersion", PluginDataValue.string("1.21.8"),
                "launcherVersion", PluginDataValue.string("3.6-next"),
                "hostOs", PluginDataValue.string("windows"),
                "hostArchitecture", PluginDataValue.string("x86_64"),
                "executionMode", PluginDataValue.string("direct")
        ));
    }

    /// Creates a deterministic launch-scoped secret store fixture.
    ///
    /// @return secret store
    private static GameLaunchSecretStore secretStore() {
        return new GameLaunchSecretStore(Map.of("access-token", "top-secret"));
    }

    /// Creates one template ending in the access-token secret slot.
    ///
    /// @param prefix literal prefix
    /// @return secret-aware text
    private static LaunchPlanText secretText(String prefix) {
        return LaunchPlanText.template(List.of(
                new LaunchPlanText.LiteralSegment(prefix),
                new LaunchPlanText.SecretSegment("access-token")
        ));
    }

    /// Recursively searches ordinary Hook data for one forbidden string fragment.
    ///
    /// @param object root object
    /// @param fragment forbidden fragment
    /// @return whether any string leaf contains the fragment
    private static boolean containsString(PluginDataObject object, String fragment) {
        for (PluginDataValue value : object.values().values()) {
            if (containsString(value, fragment)) {
                return true;
            }
        }
        return false;
    }

    /// Recursively searches one Hook value for a forbidden string fragment.
    ///
    /// @param value Hook value
    /// @param fragment forbidden fragment
    /// @return whether any nested string contains the fragment
    private static boolean containsString(PluginDataValue value, String fragment) {
        if (value instanceof PluginDataValue.StringValue stringValue) {
            return stringValue.value().contains(fragment);
        }
        if (value instanceof PluginDataValue.ArrayValue arrayValue) {
            for (PluginDataValue child : arrayValue.values()) {
                if (containsString(child, fragment)) {
                    return true;
                }
            }
        } else if (value instanceof PluginDataValue.ObjectValue objectValue) {
            return containsString(objectValue.value(), fragment);
        }
        return false;
    }
}
