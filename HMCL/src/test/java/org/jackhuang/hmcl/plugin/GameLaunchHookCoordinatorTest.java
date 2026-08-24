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
import org.jackhuang.hmcl.launch.LaunchPreparation;
import org.jackhuang.hmcl.launch.LaunchProcessPlan;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies launch-scoped before-Hook coordination, transactional transformations, and secret isolation.
@NotNullByDefault
public final class GameLaunchHookCoordinatorTest {
    /// Deterministic launch and callback time.
    private static final Instant STARTED_AT = Instant.parse("2026-08-24T02:03:04Z");

    /// Executors owned by individual test coordinators.
    private final List<ExecutorService> executors = new ArrayList<>();

    /// Temporary root for absolute launch-plan paths.
    @TempDir
    public Path temporaryDirectory;

    /// Stops every test-owned callback executor.
    @AfterEach
    public void stopExecutors() {
        executors.forEach(ExecutorService::shutdownNow);
    }

    /// Preserves the caller's preparation when no before subscriber exists while creating immutable session state.
    @Test
    public void noSubscriberPreservesPreparationAndCreatesSessionIdentity() {
        LaunchPreparation original = preparation(LaunchExecutionMode.DIRECT);
        PluginDataObject metadata = metadata("direct");
        GameLaunchHookCoordinator coordinator = coordinator(List.of(), true);

        GameLaunchHookCoordinator.LaunchSession session = coordinator.beforeLaunch(original, metadata);

        assertSame(original, session.preparation());
        assertSame(original.plan(), session.finalPlan());
        assertEquals(metadata, session.metadata());
        assertNotSame(metadata, session.metadata());
        assertEquals(STARTED_AT, session.startedAt());
        assertEquals(session.dispatchId(), UUID.fromString(session.dispatchId()).toString());
        assertTrue(session.hasAfterSubscribers());
    }

    /// Commits complete structured replacements in subscriber order, including every process-plan field.
    @Test
    public void sequentialStructuredTransformsReplaceEveryMutableField() {
        LaunchPreparation original = preparation(LaunchExecutionMode.DIRECT);
        PluginDataObject metadata = metadata("direct");
        Path replacementRoot = temporaryDirectory.resolve("replacement").toAbsolutePath();
        LaunchAuxiliaryProcessPlan replacementPostExit = new LaunchAuxiliaryProcessPlan(
                List.of(LaunchPlanText.literal("post-helper")), replacementRoot, false,
                Map.of("POST_MODE", LaunchPlanText.literal("enabled")), Set.of("OLD_POST"));
        List<String> observedDirectories = new ArrayList<>();

        GameLaunchHookCoordinator coordinator = coordinator(List.of(
                subscriber("dev.test.structure", Set.of(), event -> {
                    LaunchProcessPlan current = decode(event, original.secrets().keySet());
                    LaunchProcessPlan replacement = current
                            .withWorkingDirectory(replacementRoot)
                            .withEnvironment(false,
                                    Map.of("PLUGIN_VALUE", LaunchPlanText.literal("enabled")),
                                    Set.of("INHERITED_VALUE"))
                            .withPreLaunch(null)
                            .withPostExit(replacementPostExit)
                            .withProcessBehavior("close", true, false);
                    return replace(event, replacement);
                }),
                subscriber("dev.test.command", Set.of("dev.test.structure"), event -> {
                    LaunchProcessPlan current = decode(event, original.secrets().keySet());
                    observedDirectories.add(current.workingDirectory().toString());
                    LaunchCommandPlan command = LaunchCommandPlan.structuredJava(
                            List.of(LaunchPlanText.literal("wrapper")),
                            LaunchPlanText.literal(replacementRoot.resolve("java").toString()),
                            List.of(LaunchPlanText.literal("-Xmx4G")),
                            List.of(LaunchPlanText.literal(replacementRoot.resolve("client.jar").toString())),
                            LaunchPlanText.literal("example.Main"),
                            List.of(LaunchPlanText.literal("--demo"))
                    );
                    return replace(event, current.withCommand(command));
                })
        ), false);

        GameLaunchHookCoordinator.LaunchSession session = coordinator.beforeLaunch(original, metadata);
        LaunchProcessPlan transformed = session.preparation().plan();

        assertEquals(List.of(replacementRoot.toString()), observedDirectories);
        assertEquals(replacementRoot, transformed.workingDirectory());
        assertFalse(transformed.inheritEnvironment());
        assertEquals(Set.of("INHERITED_VALUE"), transformed.environmentUnset());
        assertEquals(Set.of("PLUGIN_VALUE"), transformed.environmentSet().keySet());
        assertEquals(null, transformed.preLaunch());
        assertEquals(replacementPostExit, transformed.postExit());
        assertEquals("close", transformed.launcherVisibility());
        assertTrue(transformed.inheritIo());
        assertFalse(transformed.daemonMonitors());
        assertEquals(LaunchCommandPlan.Mode.STRUCTURED_JAVA, transformed.command().mode());
        assertEquals(List.of("wrapper", replacementRoot.resolve("java").toString(), "-Xmx4G", "-cp",
                        replacementRoot.resolve("client.jar").toString(), "example.Main", "--demo"),
                transformed.command().resolve(slot -> session.preparation().secrets().get(slot)));
        assertEquals(original.secrets(), session.preparation().secrets());
        assertEquals(original.plan(), preparation(LaunchExecutionMode.DIRECT).plan());
        assertFalse(session.hasAfterSubscribers());
    }

    /// Accepts a complete raw command replacement without retaining stale structured Java fields.
    @Test
    public void rawCommandReplacementBecomesAuthoritative() {
        LaunchPreparation original = preparation(LaunchExecutionMode.DIRECT);
        GameLaunchHookCoordinator coordinator = coordinator(List.of(
                subscriber("dev.test.raw", Set.of(), event -> {
                    LaunchProcessPlan current = decode(event, original.secrets().keySet());
                    return replace(event, current.withCommand(LaunchCommandPlan.raw(List.of(
                            LaunchPlanText.literal("runtime-host"),
                            LaunchPlanText.literal("--launch"),
                            secretText("--token=")
                    ))));
                })
        ), false);

        GameLaunchHookCoordinator.LaunchSession session = coordinator.beforeLaunch(original, metadata("direct"));

        assertEquals(LaunchCommandPlan.Mode.RAW, session.finalPlan().command().mode());
        assertEquals(List.of("runtime-host", "--launch", "--token=top-secret"),
                session.finalPlan().command().resolve(session.preparation().secrets()::get));
        assertTrue(session.finalPlan().command().jvmArguments().isEmpty());
        assertTrue(session.finalPlan().command().classpathEntries().isEmpty());
    }

    /// Gives account-authorized callbacks secret snapshots and commits protected updates before the next callback.
    @Test
    public void protectedSecretUpdatesArePermissionScopedAndSequential() {
        LaunchPreparation original = preparation(LaunchExecutionMode.DIRECT);
        AtomicBoolean deniedSubscriberObservedNewSlot = new AtomicBoolean();
        GameLaunchHookCoordinator coordinator = coordinator(List.of(
                accountSubscriber("dev.test.account", event -> {
                    assertEquals("top-secret", event.secrets().resolve("access-token"));
                    LaunchProcessPlan current = decode(event, original.secrets().keySet());
                    LaunchProcessPlan replacement = current.withCommand(LaunchCommandPlan.raw(List.of(
                            LaunchPlanText.literal("runtime-host"), secretText("rotated-token"))));
                    return PluginHookResult.replace(
                            GameLaunchHookCodec.encodeBefore(replacement, event.data().requireObject("metadata")),
                            Map.of("rotated-token", "rotated-secret"));
                }),
                subscriber("dev.test.denied", Set.of("dev.test.account"), event -> {
                    assertThrows(PluginPermissionException.class,
                            () -> event.secrets().resolve("rotated-token"));
                    LaunchProcessPlan current = decode(event, Set.of("access-token", "rotated-token"));
                    deniedSubscriberObservedNewSlot.set(current.command().secretSlots().contains("rotated-token"));
                    return PluginHookResult.unchanged();
                })
        ), false);

        GameLaunchHookCoordinator.LaunchSession session = coordinator.beforeLaunch(original, metadata("direct"));

        assertTrue(deniedSubscriberObservedNewSlot.get());
        assertEquals("rotated-secret", session.preparation().secrets().get("rotated-token"));
        assertFalse(original.secrets().containsKey("rotated-token"));
        assertEquals(List.of("runtime-host", "rotated-secret"),
                session.finalPlan().command().resolve(session.preparation().secrets()::get));
    }

    /// Coordinates script plans without allocating direct-execution after state.
    @Test
    public void scriptModeTransformsAndNeverMarksAfterSubscribers() {
        LaunchPreparation original = preparation(LaunchExecutionMode.SCRIPT);
        GameLaunchHookCoordinator coordinator = coordinator(List.of(
                subscriber("dev.test.script", Set.of(), event -> {
                    assertEquals("script", event.data().requireObject("metadata").requireString("executionMode"));
                    LaunchProcessPlan current = decode(event, original.secrets().keySet());
                    return replace(event, current.withWorkingDirectory(
                            temporaryDirectory.resolve("script-root").toAbsolutePath()));
                })
        ), true);

        GameLaunchHookCoordinator.LaunchSession session = coordinator.beforeLaunch(original, metadata("script"));

        assertEquals(LaunchExecutionMode.SCRIPT, session.finalPlan().executionMode());
        assertEquals(temporaryDirectory.resolve("script-root").toAbsolutePath(),
                session.finalPlan().workingDirectory());
        assertFalse(session.hasAfterSubscribers());
    }

    /// Converts deliberate cancellation to the stable category and never invokes a later subscriber.
    @Test
    public void cancellationReturnsNoExecutablePreparation() {
        AtomicBoolean laterInvoked = new AtomicBoolean();
        GameLaunchHookCoordinator coordinator = coordinator(List.of(
                subscriber("dev.test.policy", Set.of(), event ->
                        PluginHookResult.cancel("policy-denied", "Launch denied")),
                subscriber("dev.test.later", Set.of(), event -> {
                    laterInvoked.set(true);
                    return PluginHookResult.unchanged();
                })
        ), false);

        PluginHookDispatchException failure = assertThrows(PluginHookDispatchException.class,
                () -> coordinator.beforeLaunch(preparation(LaunchExecutionMode.DIRECT), metadata("direct")));

        assertEquals(PluginHookDispatchException.Category.CANCELLED, failure.category());
        assertEquals("dev.test.policy", failure.pluginId());
        assertFalse(laterInvoked.get());
    }

    /// Rejects immutable metadata edits and does not mutate the caller's preparation or invoke later callbacks.
    @Test
    public void invalidReplacementIsTransactional() {
        LaunchPreparation original = preparation(LaunchExecutionMode.DIRECT);
        AtomicBoolean laterInvoked = new AtomicBoolean();
        GameLaunchHookCoordinator coordinator = coordinator(List.of(
                accountSubscriber("dev.test.invalid", event -> {
                    PluginDataObject rewrittenMetadata = event.data().requireObject("metadata")
                            .with("instanceId", PluginDataValue.string("other-instance"));
                    PluginDataObject replacement = event.data()
                            .with("metadata", PluginDataValue.object(rewrittenMetadata));
                    return PluginHookResult.replace(replacement,
                            Map.of("access-token", "must-not-commit"));
                }),
                subscriber("dev.test.later", Set.of(), event -> {
                    laterInvoked.set(true);
                    return PluginHookResult.unchanged();
                })
        ), false);

        PluginHookDispatchException failure = assertThrows(PluginHookDispatchException.class,
                () -> coordinator.beforeLaunch(original, metadata("direct")));

        assertEquals(PluginHookDispatchException.Category.INVALID_RESULT, failure.category());
        assertEquals("dev.test.invalid", failure.pluginId());
        assertFalse(laterInvoked.get());
        assertEquals("top-secret", original.secrets().get("access-token"));
        assertNotEquals("must-not-commit", original.secrets().get("access-token"));
    }

    /// Creates a coordinator with deterministic scheduling and ordered before subscribers.
    ///
    /// @param subscribers before subscriber snapshot
    /// @param afterEligible whether an after subscriber is currently eligible
    /// @return deterministic coordinator
    private GameLaunchHookCoordinator coordinator(List<PluginHookSubscriber> subscribers, boolean afterEligible) {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executors.add(executor);
        PluginHookDispatcher dispatcher = new PluginHookDispatcher(
                executor,
                Duration.ofSeconds(1),
                Clock.fixed(STARTED_AT, ZoneOffset.UTC),
                point -> point == PluginHookPoint.BEFORE_GAME_LAUNCH ? subscribers : List.of()
        );
        return new GameLaunchHookCoordinator(dispatcher, () -> afterEligible);
    }

    /// Creates one leased test subscriber with the launch-Hook permission.
    ///
    /// @param pluginId plugin ID
    /// @param dependencies dependency IDs
    /// @param endpoint endpoint implementation
    /// @return test subscriber
    private static PluginHookSubscriber subscriber(
            String pluginId,
            Set<String> dependencies,
            PluginHookEndpoint endpoint
    ) {
        return subscriber(pluginId, dependencies, false, endpoint);
    }

    /// Creates one leased account-authorized test subscriber.
    ///
    /// @param pluginId plugin ID
    /// @param endpoint endpoint implementation
    /// @return test subscriber
    private static PluginHookSubscriber accountSubscriber(
            String pluginId,
            PluginHookEndpoint endpoint
    ) {
        return subscriber(pluginId, Set.of(), true, endpoint);
    }

    /// Creates one leased test subscriber from complete dependency and permission state.
    ///
    /// @param pluginId plugin ID
    /// @param dependencies dependency IDs
    /// @param accountGranted whether the account permission is effective
    /// @param endpoint endpoint implementation
    /// @return test subscriber
    private static PluginHookSubscriber subscriber(
            String pluginId,
            Set<String> dependencies,
            boolean accountGranted,
            PluginHookEndpoint endpoint
    ) {
        Set<PluginPermission> permissions = accountGranted
                ? Set.of(PluginPermission.LAUNCHER_HOOK, PluginPermission.ACCOUNT)
                : Set.of(PluginPermission.LAUNCHER_HOOK);
        return new PluginHookSubscriber(pluginId, dependencies, permissions, endpoint, () -> {
        });
    }

    /// Decodes the current event plan against the supplied secret slots.
    ///
    /// @param event current event
    /// @param secretSlots available secret slots
    /// @return decoded plan
    private static LaunchProcessPlan decode(PluginHookEvent event, Set<String> secretSlots) {
        return GameLaunchHookCodec.decodeBefore(
                event.data(), event.data().requireObject("metadata"), secretSlots);
    }

    /// Encodes one plan as a complete replacement retaining event metadata.
    ///
    /// @param event current event
    /// @param plan replacement plan
    /// @return replacement result
    private static PluginHookResult replace(PluginHookEvent event, LaunchProcessPlan plan) {
        return PluginHookResult.replace(GameLaunchHookCodec.encodeBefore(
                plan, event.data().requireObject("metadata")));
    }

    /// Creates a complete launch preparation for one execution mode.
    ///
    /// @param mode direct or script mode
    /// @return immutable preparation
    private LaunchPreparation preparation(LaunchExecutionMode mode) {
        Path root = temporaryDirectory.resolve("original").toAbsolutePath();
        LaunchAuxiliaryProcessPlan preLaunch = new LaunchAuxiliaryProcessPlan(
                List.of(LaunchPlanText.literal("pre-helper")), root, true,
                Map.of("PRE_MODE", LaunchPlanText.literal("enabled")), Set.of());
        LaunchProcessPlan plan = new LaunchProcessPlan(
                LaunchProcessPlan.CURRENT_PLAN_VERSION,
                mode,
                LaunchCommandPlan.structuredJava(
                        List.of(),
                        LaunchPlanText.literal(root.resolve("java").toString()),
                        List.of(LaunchPlanText.literal("-Xmx2G"), secretText("-Dtoken=")),
                        List.of(LaunchPlanText.literal(root.resolve("client.jar").toString())),
                        LaunchPlanText.literal("net.minecraft.client.main.Main"),
                        List.of(LaunchPlanText.literal("--username"), LaunchPlanText.literal("Alex"))
                ),
                root,
                true,
                Map.of("INSTANCE_ID", LaunchPlanText.literal("example")),
                Set.of("OLD_VALUE"),
                preLaunch,
                null,
                "hide-and-reopen",
                false,
                true
        );
        return new LaunchPreparation(
                plan,
                Map.of("access-token", "top-secret"),
                temporaryDirectory.resolve("native-link"),
                temporaryDirectory.resolve("natives"),
                temporaryDirectory.resolve("java-natives"),
                StandardCharsets.UTF_8
        );
    }

    /// Creates immutable launch metadata for one execution mode.
    ///
    /// @param executionMode stable execution mode identifier
    /// @return metadata object
    private static PluginDataObject metadata(String executionMode) {
        return PluginDataObject.of(Map.of(
                "instanceId", PluginDataValue.string("example-instance"),
                "gameVersion", PluginDataValue.string("1.21.8"),
                "launcherVersion", PluginDataValue.string("3.6-next"),
                "hostOs", PluginDataValue.string("windows"),
                "hostArchitecture", PluginDataValue.string("x86_64"),
                "executionMode", PluginDataValue.string(executionMode)
        ));
    }

    /// Creates one template ending in a protected secret slot.
    ///
    /// @param slot secret slot
    /// @return secret-aware text
    private static LaunchPlanText secretText(String slot) {
        String prefix = slot.endsWith("=") ? slot : "";
        String secretSlot = prefix.isEmpty() ? slot : "access-token";
        return LaunchPlanText.template(List.of(
                new LaunchPlanText.LiteralSegment(prefix),
                new LaunchPlanText.SecretSegment(secretSlot)
        ));
    }
}
