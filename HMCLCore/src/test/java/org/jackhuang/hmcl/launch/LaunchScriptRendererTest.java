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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies direct-token and script-rendering parity for one complete immutable launch plan.
@NotNullByDefault
public final class LaunchScriptRendererTest {
    /// Renders every supported script kind exclusively from the same resolved plan.
    @ParameterizedTest
    @EnumSource(LaunchScriptRenderer.Kind.class)
    public void scriptUsesTheSameResolvedPlanAsDirectExecution(
            LaunchScriptRenderer.Kind kind,
            @TempDir Path directory
    ) throws IOException {
        LaunchProcessPlan plan = completePlan(directory);
        List<String> directTokens = plan.command().resolve(this::resolveSecret);

        String script = LaunchScriptRenderer.renderToString(kind, plan, this::resolveSecret);

        directTokens.forEach(token -> assertTrue(script.contains(
                LaunchScriptRenderer.quoteForTest(kind, token)), token));
        assertEquals(1, occurrences(script, "PRE_SENTINEL"));
        assertEquals(1, occurrences(script, "POST_SENTINEL"));
        assertTrue(script.contains(LaunchScriptRenderer.quoteForTest(kind, directory.toString())));
        assertTrue(script.contains("INST_ID"));
        assertTrue(script.contains("example instance"));
        assertTrue(script.contains("OLD_VALUE"));
    }

    /// Uses kind-specific environment syntax for explicit set and unset operations.
    @ParameterizedTest
    @EnumSource(LaunchScriptRenderer.Kind.class)
    public void environmentEditsUseNativeScriptSyntax(
            LaunchScriptRenderer.Kind kind,
            @TempDir Path directory
    ) throws IOException {
        String script = LaunchScriptRenderer.renderToString(kind, completePlan(directory), this::resolveSecret);

        switch (kind) {
            case BATCH -> {
                assertTrue(script.startsWith("@echo off"));
                assertTrue(script.contains("set \"OLD_VALUE=\""));
                assertTrue(script.contains("set \"INST_ID=example instance\""));
            }
            case POWERSHELL -> {
                assertTrue(script.contains("Remove-Item Env:OLD_VALUE -ErrorAction SilentlyContinue"));
                assertTrue(script.contains("$Env:INST_ID='example instance'"));
            }
            case BASH, MACOS_COMMAND -> {
                assertTrue(script.startsWith("#!/usr/bin/env bash"));
                assertTrue(script.contains("unset OLD_VALUE"));
                assertTrue(script.contains("export INST_ID=\"example instance\""));
            }
        }
    }

    /// Writes platform-appropriate encoding markers and executable script files.
    @ParameterizedTest
    @EnumSource(LaunchScriptRenderer.Kind.class)
    public void publicRendererWritesExecutableFiles(
            LaunchScriptRenderer.Kind kind,
            @TempDir Path directory
    ) throws IOException {
        Path scriptFile = directory.resolve("launch" + extension(kind));

        LaunchScriptRenderer.render(scriptFile, completePlan(directory), this::resolveSecret);

        assertTrue(Files.isRegularFile(scriptFile));
        assertTrue(Files.isExecutable(scriptFile));
        byte[] bytes = Files.readAllBytes(scriptFile);
        if (kind == LaunchScriptRenderer.Kind.POWERSHELL) {
            assertTrue(bytes.length >= 3);
            assertEquals(0xEF, bytes[0] & 0xFF);
            assertEquals(0xBB, bytes[1] & 0xFF);
            assertEquals(0xBF, bytes[2] & 0xFF);
        } else {
            assertFalse(bytes.length >= 3
                    && (bytes[0] & 0xFF) == 0xEF
                    && (bytes[1] & 0xFF) == 0xBB
                    && (bytes[2] & 0xFF) == 0xBF);
        }
    }

    /// Rejects unsupported script extensions before writing a partial file.
    @Test
    public void publicRendererRejectsUnknownExtension(@TempDir Path directory) {
        Path scriptFile = directory.resolve("launch.txt");

        assertThrows(IllegalArgumentException.class,
                () -> LaunchScriptRenderer.render(scriptFile, completePlan(directory), this::resolveSecret));
        assertFalse(Files.exists(scriptFile));
    }

    /// Enforces the Windows command-line limit only for batch rendering.
    @Test
    public void batchRejectsLongMainCommand(@TempDir Path directory) throws IOException {
        LaunchProcessPlan plan = completePlan(directory).withCommand(LaunchCommandPlan.raw(List.of(
                LaunchPlanText.literal("java"), LaunchPlanText.literal("x".repeat(32768)))));

        assertThrows(CommandTooLongException.class,
                () -> LaunchScriptRenderer.renderToString(
                        LaunchScriptRenderer.Kind.BATCH, plan, this::resolveSecret));
        assertTrue(LaunchScriptRenderer.renderToString(
                LaunchScriptRenderer.Kind.POWERSHELL, plan, this::resolveSecret).contains("x".repeat(32768)));
    }

    /// Resolves secret slots only while rendering and never stores the returned value in the plan.
    @Test
    public void rendererResolvesSecretsOnlyAtFinalBoundary(@TempDir Path directory) throws IOException {
        AtomicInteger resolutions = new AtomicInteger();
        LaunchProcessPlan plan = completePlan(directory);

        assertEquals(0, resolutions.get());
        assertFalse(plan.toString().contains("resolved-secret"));
        String script = LaunchScriptRenderer.renderToString(
                LaunchScriptRenderer.Kind.BASH,
                plan,
                slot -> {
                    resolutions.incrementAndGet();
                    return "resolved-secret";
                }
        );

        assertTrue(resolutions.get() > 0);
        assertTrue(script.contains("resolved-secret"));
        assertFalse(plan.toString().contains("resolved-secret"));
    }

    /// Keeps private native-link setup and cleanup outside the public launch plan.
    @Test
    public void posixRendererPreservesPrivateNativeLinkLifecycle(@TempDir Path directory) throws IOException {
        Path nativeFolder = directory.resolve("native libraries");
        Path temporaryLink = directory.resolve("native-link");

        String script = LaunchScriptRenderer.renderToString(
                LaunchScriptRenderer.Kind.BASH,
                completePlan(directory),
                this::resolveSecret,
                temporaryLink,
                nativeFolder
        );

        assertTrue(script.contains("ln -s -- "
                + LaunchScriptRenderer.quoteForTest(LaunchScriptRenderer.Kind.BASH, nativeFolder.toString())
                + " "
                + LaunchScriptRenderer.quoteForTest(LaunchScriptRenderer.Kind.BASH, temporaryLink.toString())));
        assertTrue(script.contains("rm -f -- "
                + LaunchScriptRenderer.quoteForTest(LaunchScriptRenderer.Kind.BASH, temporaryLink.toString())));
        assertFalse(completePlan(directory).toString().contains(temporaryLink.toString()));
    }

    /// Copies private resources and secrets while redacting diagnostic output.
    @Test
    public void preparationIsImmutableAndSecretSafe(@TempDir Path directory) {
        LaunchProcessPlan plan = completePlan(directory);
        Map<String, String> secrets = new LinkedHashMap<>(Map.of("access-token", "top-secret"));
        LaunchPreparation preparation = new LaunchPreparation(
                plan,
                secrets,
                directory.resolve("native-link"),
                directory.resolve("natives"),
                directory.resolve("java-natives"),
                StandardCharsets.UTF_8
        );
        secrets.put("late", "late-secret");

        assertEquals(Map.of("access-token", "top-secret"), preparation.secrets());
        assertThrows(UnsupportedOperationException.class,
                () -> preparation.secrets().put("bad", "bad"));
        assertFalse(preparation.toString().contains("top-secret"));
        assertFalse(preparation.toString().contains("late-secret"));
        LaunchPreparation withPlan = preparation.withPlan(plan.withProcessBehavior("keep", false, true));
        LaunchPreparation withSecrets = preparation.withSecrets(Map.of("new-slot", "new-secret"));
        assertNotSame(preparation, withPlan);
        assertEquals("keep", withPlan.plan().launcherVisibility());
        assertEquals(Map.of("new-slot", "new-secret"), withSecrets.secrets());
        assertEquals(preparation.temporaryNativeLink(), withPlan.temporaryNativeLink());
        assertEquals(preparation.nativeFolder(), withPlan.nativeFolder());
        assertEquals(preparation.javaNativeFolder(), withPlan.javaNativeFolder());
        assertEquals(preparation.outputEncoding(), withPlan.outputEncoding());
    }

    /// Creates one plan containing every field rendered by supported scripts.
    ///
    /// @param directory absolute working directory
    /// @return complete immutable plan
    private static LaunchProcessPlan completePlan(Path directory) {
        LaunchPlanText secretArgument = LaunchPlanText.template(List.of(
                new LaunchPlanText.LiteralSegment("--accessToken="),
                new LaunchPlanText.SecretSegment("access-token")
        ));
        LaunchCommandPlan command = LaunchCommandPlan.structuredJava(
                List.of(LaunchPlanText.literal("nice"), LaunchPlanText.literal("-n"), LaunchPlanText.literal("1")),
                LaunchPlanText.literal(directory.resolve("jdk with space").resolve("java").toString()),
                List.of(LaunchPlanText.literal("-Xmx2G")),
                List.of(LaunchPlanText.literal("a.jar"), LaunchPlanText.literal("b.jar")),
                LaunchPlanText.literal("net.minecraft.client.main.Main"),
                List.of(LaunchPlanText.literal("--username"), LaunchPlanText.literal("Alex"), secretArgument)
        );
        return new LaunchProcessPlan(
                LaunchProcessPlan.CURRENT_PLAN_VERSION,
                LaunchExecutionMode.SCRIPT,
                command,
                directory,
                true,
                Map.of(
                        "INST_ID", LaunchPlanText.literal("example instance"),
                        "APPDATA", LaunchPlanText.literal(directory.resolve("app data").toString())
                ),
                Set.of("OLD_VALUE"),
                auxiliary(directory, "PRE_SENTINEL"),
                auxiliary(directory, "POST_SENTINEL"),
                "hide",
                false,
                true
        );
    }

    /// Creates one auxiliary command with environment edits.
    ///
    /// @param directory absolute working directory
    /// @param sentinel unique command sentinel
    /// @return auxiliary process plan
    private static LaunchAuxiliaryProcessPlan auxiliary(Path directory, String sentinel) {
        return new LaunchAuxiliaryProcessPlan(
                List.of(LaunchPlanText.literal("helper"), LaunchPlanText.literal(sentinel)),
                directory,
                true,
                Map.of("HELPER_MODE", LaunchPlanText.literal("enabled")),
                Set.of("OLD_HELPER_MODE")
        );
    }

    /// Resolves the single deterministic fixture secret.
    ///
    /// @param slot secret slot
    /// @return secret value
    private String resolveSecret(String slot) {
        assertEquals("access-token", slot);
        return "fixture-secret";
    }

    /// Returns the extension selected by one script kind.
    ///
    /// @param kind script kind
    /// @return extension including the leading dot
    private static String extension(LaunchScriptRenderer.Kind kind) {
        return switch (kind) {
            case BATCH -> ".bat";
            case POWERSHELL -> ".ps1";
            case BASH -> ".sh";
            case MACOS_COMMAND -> ".command";
        };
    }

    /// Counts non-overlapping occurrences of one sentinel.
    ///
    /// @param source rendered script
    /// @param needle sentinel text
    /// @return occurrence count
    private static int occurrences(String source, String needle) {
        int count = 0;
        int offset = 0;
        while ((offset = source.indexOf(needle, offset)) >= 0) {
            count++;
            offset += needle.length();
        }
        return count;
    }
}
