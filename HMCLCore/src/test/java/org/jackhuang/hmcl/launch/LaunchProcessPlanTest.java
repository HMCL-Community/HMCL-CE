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
package org.jackhuang.hmcl.launch;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies the immutable, implementation-neutral process plan shared by launch execution and script rendering.
@NotNullByDefault
public final class LaunchProcessPlanTest {
    /// Uses structured Java fields as the sole source until an explicit raw-command replacement.
    @Test
    public void structuredAndRawModesHaveOneAuthoritativeSource() {
        LaunchCommandPlan structured = LaunchCommandPlan.structuredJava(
                List.of(LaunchPlanText.literal("nice"), LaunchPlanText.literal("-n"), LaunchPlanText.literal("1")),
                LaunchPlanText.literal("/jdk/bin/java"),
                List.of(LaunchPlanText.literal("-Xmx2G")),
                List.of(LaunchPlanText.literal("a.jar"), LaunchPlanText.literal("b.jar")),
                LaunchPlanText.literal("net.minecraft.client.main.Main"),
                List.of(LaunchPlanText.literal("--username"), LaunchPlanText.literal("Alex"))
        );

        assertEquals(List.of("nice", "-n", "1", "/jdk/bin/java", "-Xmx2G", "-cp",
                "a.jar" + File.pathSeparator + "b.jar", "net.minecraft.client.main.Main",
                "--username", "Alex"), structured.resolve(slot -> null));
        assertEquals(LaunchCommandPlan.Mode.STRUCTURED_JAVA, structured.mode());
        assertTrue(structured.rawCommand().isEmpty());

        LaunchCommandPlan raw = structured.replaceWithRawCommand(List.of(
                LaunchPlanText.literal("custom-host"), LaunchPlanText.literal("--launch")));

        assertEquals(LaunchCommandPlan.Mode.RAW, raw.mode());
        assertEquals(List.of("custom-host", "--launch"), raw.resolve(slot -> null));
        assertNull(raw.javaExecutable());
        assertNull(raw.mainClass());
        assertTrue(raw.jvmArguments().isEmpty());
    }

    /// Resolves opaque secret segments only through the supplied resolver and preserves segment snapshots.
    @Test
    public void templatesRemainOpaqueUntilResolution() {
        List<LaunchPlanText.Segment> segments = new ArrayList<>();
        segments.add(new LaunchPlanText.LiteralSegment("--accessToken="));
        segments.add(new LaunchPlanText.SecretSegment("access-token"));
        LaunchPlanText template = LaunchPlanText.template(segments);
        segments.add(new LaunchPlanText.LiteralSegment("late"));

        assertEquals(Set.of("access-token"), template.secretSlots());
        assertEquals("--accessToken=secret-value", template.resolve(
                slot -> "access-token".equals(slot) ? "secret-value" : null));
        assertEquals(2, template.segments().size());
        assertThrows(UnsupportedOperationException.class,
                () -> template.segments().add(new LaunchPlanText.LiteralSegment("bad")));
        assertThrows(UnsupportedOperationException.class, () -> template.secretSlots().add("bad"));
        assertThrows(IllegalArgumentException.class, () -> template.resolve(slot -> null));
        assertFalse(template.toString().contains("secret-value"));
    }

    /// Copies every collection and updates complete plan snapshots without modifying prior values.
    @Test
    public void completePlanIsImmutableAndCopyOnWrite(@TempDir Path directory) {
        Map<String, LaunchPlanText> environmentSet = new LinkedHashMap<>();
        environmentSet.put("INST_ID", LaunchPlanText.literal("example"));
        Set<String> environmentUnset = new LinkedHashSet<>();
        environmentUnset.add("OLD_VALUE");
        LaunchProcessPlan plan = new LaunchProcessPlan(
                LaunchProcessPlan.CURRENT_PLAN_VERSION,
                LaunchExecutionMode.DIRECT,
                rawCommand("java", "Main"),
                directory,
                true,
                environmentSet,
                environmentUnset,
                auxiliary(directory, "PRE"),
                auxiliary(directory, "POST"),
                "hide",
                false,
                true
        );
        environmentSet.put("LATE", LaunchPlanText.literal("bad"));
        environmentUnset.add("LATE_UNSET");

        LaunchCommandPlan replacement = rawCommand("custom-host", "--launch");
        LaunchProcessPlan changed = plan
                .withCommand(replacement)
                .withWorkingDirectory(directory.resolve("other"))
                .withEnvironment(false, Map.of("ONLY", LaunchPlanText.literal("value")), Set.of("REMOVE"))
                .withPreLaunch(null)
                .withPostExit(null)
                .withProcessBehavior("keep", true, false);

        assertEquals(Set.of("INST_ID"), plan.environmentSet().keySet());
        assertEquals(Set.of("OLD_VALUE"), plan.environmentUnset());
        assertThrows(UnsupportedOperationException.class,
                () -> plan.environmentSet().put("bad", LaunchPlanText.literal("bad")));
        assertThrows(UnsupportedOperationException.class, () -> plan.environmentUnset().add("bad"));
        assertEquals(rawCommand("java", "Main"), plan.command());
        assertEquals(replacement, changed.command());
        assertEquals(directory, plan.workingDirectory());
        assertEquals(directory.resolve("other"), changed.workingDirectory());
        assertTrue(plan.inheritEnvironment());
        assertFalse(changed.inheritEnvironment());
        assertTrue(plan.preLaunch() != null);
        assertTrue(plan.postExit() != null);
        assertNull(changed.preLaunch());
        assertNull(changed.postExit());
        assertEquals("hide", plan.launcherVisibility());
        assertEquals("keep", changed.launcherVisibility());
        assertFalse(plan.inheritIo());
        assertTrue(changed.inheritIo());
        assertTrue(plan.daemonMonitors());
        assertFalse(changed.daemonMonitors());
    }

    /// Preserves complete auxiliary process commands, environment changes, and working directory.
    @Test
    public void auxiliaryProcessPlansAreImmutable(@TempDir Path directory) {
        List<LaunchPlanText> command = new ArrayList<>(List.of(LaunchPlanText.literal("helper")));
        Map<String, LaunchPlanText> environmentSet = new LinkedHashMap<>(Map.of(
                "TOKEN_REF", LaunchPlanText.template(List.of(new LaunchPlanText.SecretSegment("helper-token")))
        ));
        Set<String> environmentUnset = new LinkedHashSet<>(Set.of("OLD_TOKEN"));
        LaunchAuxiliaryProcessPlan auxiliary = new LaunchAuxiliaryProcessPlan(
                command, directory, false, environmentSet, environmentUnset);
        command.add(LaunchPlanText.literal("late"));
        environmentSet.clear();
        environmentUnset.clear();

        assertEquals(List.of("helper"), auxiliary.resolveCommand(slot -> "secret"));
        assertEquals(Set.of("helper-token"), auxiliary.secretSlots());
        assertEquals(Set.of("TOKEN_REF"), auxiliary.environmentSet().keySet());
        assertEquals(Set.of("OLD_TOKEN"), auxiliary.environmentUnset());
        assertThrows(UnsupportedOperationException.class,
                () -> auxiliary.command().add(LaunchPlanText.literal("bad")));
        assertThrows(UnsupportedOperationException.class,
                () -> auxiliary.environmentSet().put("bad", LaunchPlanText.literal("bad")));
    }

    /// Rejects structurally empty command representations and malformed text segments at construction.
    @Test
    public void commandConstructionRejectsInvalidText() {
        assertThrows(IllegalArgumentException.class, () -> LaunchCommandPlan.raw(List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> LaunchCommandPlan.raw(List.of(LaunchPlanText.literal(" "))));
        assertThrows(IllegalArgumentException.class, () -> LaunchCommandPlan.structuredJava(
                List.of(), LaunchPlanText.literal(" "), List.of(), List.of(),
                LaunchPlanText.literal("Main"), List.of()));
        assertThrows(IllegalArgumentException.class, () -> LaunchCommandPlan.structuredJava(
                List.of(), LaunchPlanText.literal("java"), List.of(), List.of(),
                LaunchPlanText.literal(" "), List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new LaunchAuxiliaryProcessPlan(List.of(), Path.of("relative"), true, Map.of(), Set.of()));
        assertThrows(IllegalArgumentException.class, () -> LaunchPlanText.literal("bad\0text"));
        assertThrows(IllegalArgumentException.class,
                () -> new LaunchPlanText.SecretSegment("Not Canonical"));
        assertThrows(NullPointerException.class, () -> LaunchPlanText.literal(null));
        assertThrows(NullPointerException.class, () -> LaunchPlanText.template(null));
    }

    /// Reports path-specific validation failures without resolving or including secret values.
    @Test
    public void completePlanValidationRejectsInvalidState(@TempDir Path directory) {
        LaunchPlanText secretReference = LaunchPlanText.template(List.of(
                new LaunchPlanText.LiteralSegment("prefix-"),
                new LaunchPlanText.SecretSegment("access-token")
        ));
        LaunchProcessPlan valid = new LaunchProcessPlan(
                LaunchProcessPlan.CURRENT_PLAN_VERSION,
                LaunchExecutionMode.SCRIPT,
                rawCommand("java", "Main"),
                directory,
                true,
                Map.of("ACCESS_TOKEN", secretReference),
                Set.of("OLD_TOKEN"),
                null,
                null,
                "hide-and-reopen",
                false,
                true
        );
        valid.validate(Set.of("access-token"));

        IllegalArgumentException missingSecret = assertThrows(IllegalArgumentException.class,
                () -> valid.validate(Set.of()));
        assertTrue(missingSecret.getMessage().contains("environmentSet.ACCESS_TOKEN"));
        assertTrue(missingSecret.getMessage().contains("access-token"));
        assertFalse(missingSecret.getMessage().contains("secret-value"));

        LaunchProcessPlan relative = valid.withWorkingDirectory(Path.of("relative"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> relative.validate(Set.of("access-token"))).getMessage().contains("workingDirectory"));

        LaunchProcessPlan invalidKey = valid.withEnvironment(
                true, Map.of("BAD=KEY", LaunchPlanText.literal("value")), Set.of());
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> invalidKey.validate(Set.of())).getMessage().contains("environmentSet"));

        LaunchProcessPlan overlap = valid.withEnvironment(
                true, Map.of("SAME", LaunchPlanText.literal("value")), Set.of("SAME"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> overlap.validate(Set.of())).getMessage().contains("environmentUnset"));

        LaunchProcessPlan unknownVersion = new LaunchProcessPlan(
                2, valid.executionMode(), valid.command(), valid.workingDirectory(),
                valid.inheritEnvironment(), valid.environmentSet(), valid.environmentUnset(),
                valid.preLaunch(), valid.postExit(), valid.launcherVisibility(),
                valid.inheritIo(), valid.daemonMonitors());
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> unknownVersion.validate(Set.of("access-token"))).getMessage().contains("planVersion"));

        assertThrows(IllegalArgumentException.class,
                () -> valid.withProcessBehavior("unknown", false, true).validate(Set.of("access-token")));
    }

    /// Creates a raw command from literal tokens.
    ///
    /// @param tokens command tokens
    /// @return immutable raw command
    private static LaunchCommandPlan rawCommand(String... tokens) {
        return LaunchCommandPlan.raw(List.of(tokens).stream().map(LaunchPlanText::literal).toList());
    }

    /// Creates one deterministic auxiliary command fixture.
    ///
    /// @param directory absolute working directory
    /// @param sentinel command sentinel
    /// @return immutable auxiliary process plan
    private static LaunchAuxiliaryProcessPlan auxiliary(Path directory, String sentinel) {
        return new LaunchAuxiliaryProcessPlan(
                List.of(LaunchPlanText.literal(sentinel)), directory, true, Map.of(), Set.of());
    }
}
