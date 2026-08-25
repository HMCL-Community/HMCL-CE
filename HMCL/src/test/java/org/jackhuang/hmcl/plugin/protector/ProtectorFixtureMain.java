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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/// Subprocess fixture that drives the real protected-child reporting path without initializing launcher Core or UI.
@NotNullByDefault
public final class ProtectorFixtureMain {
    /// Prevents construction of the subprocess fixture.
    private ProtectorFixtureMain() {
    }

    /// Executes one requested child behavior after consuming authenticated internal Protector arguments.
    ///
    /// @param args internal Protector arguments followed by one fixture mode
    /// @throws Exception if connection or reporting fails
    public static void main(String @Unmodifiable [] args) throws Exception {
        for (String argument : args) {
            if (argument.equals("stubborn")) {
                runStubborn(args);
                return;
            }
            if (argument.startsWith("never-connect:")) {
                runWithoutConnecting(argument.substring("never-connect:".length()));
                return;
            }
            if (argument.equals("preconnect-crash")) {
                Runtime.getRuntime().halt(23);
            }
        }
        String @Nullable [] launcherArgs = ProtectorBootstrap.enter(args);
        if (launcherArgs == null) {
            throw new IllegalStateException("Fixture unexpectedly entered the Protector parent role");
        }
        String mode = launcherArgs[0];
        if (mode.equals("ready")) {
            StartupReporter.reportCoreReady();
            StartupReporter.reportUiReady();
            return;
        }
        if (mode.equals("cancel")) {
            StartupReporter.reportCancel();
            return;
        }
        if (mode.equals("normal")) {
            StartupReporter.reportNormalShutdown();
            return;
        }
        if (mode.equals("nonzero-exit")) {
            org.jackhuang.hmcl.EntryPoint.exit(23);
            return;
        }
        if (mode.equals("zero-exit")) {
            org.jackhuang.hmcl.EntryPoint.exit(0);
            return;
        }
        if (mode.equals("uncaught")) {
            throw new IllegalStateException("Fixture uncaught startup failure");
        }
        if (mode.startsWith("invalid-ready:")) {
            Path marker = Path.of(mode.substring("invalid-ready:".length()));
            Files.writeString(marker, Long.toString(ProcessHandle.current().pid()), StandardCharsets.UTF_8);
            StartupReporter.reportUiReady();
            Thread.sleep(30_000L);
            return;
        }
        if (mode.startsWith("shutdown-marker:")) {
            Path marker = Path.of(mode.substring("shutdown-marker:".length()));
            Runtime.getRuntime().addShutdownHook(new Thread(
                    () -> writeShutdownMarker(marker),
                    "Protector Fixture Shutdown Marker"
            ));
            Thread.sleep(30_000L);
            return;
        }
        if (mode.startsWith("crash:")) {
            reportStage(mode.substring("crash:".length()));
            Runtime.getRuntime().halt(23);
        }
        if (mode.startsWith("hang:")) {
            reportStage(mode.substring("hang:".length()));
            Thread.sleep(30_000L);
            return;
        }
        if (mode.startsWith("renew:")) {
            reportStage(mode.substring("renew:".length()));
            while (true) {
                Thread.sleep(100L);
                StartupReporter.renewCurrentStage();
            }
        }
        throw new IllegalArgumentException("Unknown Protector fixture mode");
    }

    /// Records this fixture's process ID and remains alive without connecting to the parent.
    ///
    /// @param markerName process-marker path
    /// @throws Exception if marker writing or waiting fails
    private static void runWithoutConnecting(String markerName) throws Exception {
        Files.writeString(Path.of(markerName), Long.toString(ProcessHandle.current().pid()), StandardCharsets.UTF_8);
        Thread.sleep(30_000L);
    }

    /// Writes a marker from a graceful JVM shutdown hook.
    ///
    /// @param marker marker path
    private static void writeShutdownMarker(Path marker) {
        try {
            Files.writeString(marker, "shutdown", StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            // Test assertion reports an absent marker without leaking shutdown-hook failures.
        }
    }

    /// Records timeout control requests but deliberately remains alive for force-termination verification.
    ///
    /// @param args internal endpoint and nonce pairs followed by mode and marker path
    /// @throws Exception if transport, protocol, or marker writing fails
    private static void runStubborn(String @Unmodifiable [] args) throws Exception {
        String endpoint = requireInternalValue(args, ProtectorBootstrap.ENDPOINT_ARGUMENT);
        String nonce = requireInternalValue(args, ProtectorBootstrap.NONCE_ARGUMENT);
        Path marker = Path.of(args[args.length - 1]);
        ProtectorProtocol outbound = new ProtectorProtocol(nonce);
        ProtectorProtocol inbound = new ProtectorProtocol(nonce);
        try (ProtectorBootstrap.LocalConnection connection = ProtectorBootstrap.connectLocal(
                endpoint,
                Duration.ofSeconds(5)
        )) {
            ProtectorMessage started = new ProtectorMessage(
                    ProtectorMessage.Kind.STAGE,
                    Math.max(0L, System.nanoTime()),
                    ProtectorStage.JVM_STARTED,
                    null,
                    null
            );
            connection.output().write(outbound.encode(started).getBytes(StandardCharsets.UTF_8));
            connection.output().flush();
            StringBuilder received = new StringBuilder();
            while (received.toString().lines().count() < 2L) {
                @Nullable String line = ProtectorProtocol.readLine(connection.input());
                if (line == null) {
                    throw new IllegalStateException("Protector parent closed before escalation completed");
                }
                ProtectorMessage request = inbound.decode(line);
                received.append(request.kind().wireName()).append('\n');
            }
            Files.writeString(marker, received, StandardCharsets.UTF_8);
            Thread.sleep(30_000L);
        }
    }

    /// Returns one exact required internal argument value.
    ///
    /// @param args complete fixture arguments
    /// @param name internal argument name
    /// @return following argument value
    private static String requireInternalValue(String @Unmodifiable [] args, String name) {
        for (int index = 0; index + 1 < args.length; index++) {
            if (args[index].equals(name)) {
                return args[index + 1];
            }
        }
        throw new IllegalArgumentException("Missing fixture internal argument");
    }

    /// Reports one exact pre-ready stage requested by a fixture mode.
    ///
    /// @param stageName enum constant name
    /// @throws Exception if reporting fails
    private static void reportStage(String stageName) throws Exception {
        ProtectorStage stage = ProtectorStage.valueOf(stageName);
        switch (stage) {
            case JVM_STARTED -> {
                // Child connection already reports this initial stage.
            }
            case CORE_READY -> StartupReporter.reportCoreReady();
            case RUNTIME_PROVIDERS_LOADING -> StartupReporter.reportRuntimeProvider("org.example.provider");
            case ORDINARY_PLUGINS_LOADING -> StartupReporter.reportOrdinaryPlugin("org.example.plugin");
            case UI_READY -> throw new IllegalArgumentException("Fixture crash must occur before UI readiness");
        }
    }
}
