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

import org.jackhuang.hmcl.util.io.FileUtils;
import org.jackhuang.hmcl.util.platform.CommandBuilder;
import org.jackhuang.hmcl.util.platform.OperatingSystem;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/// Renders one resolved launch process plan as Batch, PowerShell, Bash, or macOS command script text.
@NotNullByDefault
public final class LaunchScriptRenderer {
    /// Prevents construction of the static renderer.
    private LaunchScriptRenderer() {
    }

    /// Renders a script selected by the target file extension and marks it executable.
    ///
    /// @param scriptFile target script file
    /// @param plan immutable unresolved process plan
    /// @param secretResolver final launch-scoped secret resolver
    /// @throws IOException if rendering or file creation fails
    public static void render(
            Path scriptFile,
            LaunchProcessPlan plan,
            Function<String, @Nullable String> secretResolver
    ) throws IOException {
        render(scriptFile, plan, secretResolver, null, null);
    }

    /// Renders a script with launcher-private native-link lifecycle commands.
    ///
    /// @param scriptFile target script file
    /// @param plan immutable unresolved process plan
    /// @param secretResolver final launch-scoped secret resolver
    /// @param temporaryNativeLink optional private native-library link
    /// @param nativeFolder native-library link target, required when a link is supplied
    /// @throws IOException if rendering or file creation fails
    static void render(
            Path scriptFile,
            LaunchProcessPlan plan,
            Function<String, @Nullable String> secretResolver,
            @Nullable Path temporaryNativeLink,
            @Nullable Path nativeFolder
    ) throws IOException {
        Objects.requireNonNull(scriptFile, "scriptFile");
        Kind kind = kindFor(scriptFile);
        String script = renderToString(kind, plan, secretResolver, temporaryNativeLink, nativeFolder);
        @Nullable Path parent = scriptFile.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Charset charset = kind == Kind.BATCH ? OperatingSystem.NATIVE_CHARSET : StandardCharsets.UTF_8;
        byte[] content = script.getBytes(charset);
        if (kind == Kind.POWERSHELL) {
            byte[] withBom = new byte[content.length + 3];
            withBom[0] = (byte) 0xEF;
            withBom[1] = (byte) 0xBB;
            withBom[2] = (byte) 0xBF;
            System.arraycopy(content, 0, withBom, 3, content.length);
            content = withBom;
        }
        Files.write(scriptFile, content);
        FileUtils.setExecutable(scriptFile);
        if (!Files.isExecutable(scriptFile)) {
            throw new PermissionException();
        }
    }

    /// Renders deterministic script text for one explicit kind.
    ///
    /// @param kind script kind
    /// @param plan immutable unresolved process plan
    /// @param secretResolver final launch-scoped secret resolver
    /// @return rendered script text without an encoding marker
    /// @throws IOException if the Batch command exceeds the Windows command limit
    static String renderToString(
            Kind kind,
            LaunchProcessPlan plan,
            Function<String, @Nullable String> secretResolver
    ) throws IOException {
        return renderToString(kind, plan, secretResolver, null, null);
    }

    /// Renders deterministic script text with optional private native-link lifecycle commands.
    ///
    /// @param kind script kind
    /// @param plan immutable unresolved process plan
    /// @param secretResolver final launch-scoped secret resolver
    /// @param temporaryNativeLink optional private native-library link
    /// @param nativeFolder native-library link target, required when a link is supplied
    /// @return rendered script text without an encoding marker
    /// @throws IOException if the Batch command exceeds the Windows command limit
    static String renderToString(
            Kind kind,
            LaunchProcessPlan plan,
            Function<String, @Nullable String> secretResolver,
            @Nullable Path temporaryNativeLink,
            @Nullable Path nativeFolder
    ) throws IOException {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(secretResolver, "secretResolver");
        if (temporaryNativeLink != null) {
            Objects.requireNonNull(nativeFolder, "nativeFolder");
        }
        List<String> command = plan.command().resolve(secretResolver);
        if (kind == Kind.BATCH && commandLine(kind, command).length() > 32767) {
            throw new CommandTooLongException();
        }

        String newline = kind == Kind.BATCH || kind == Kind.POWERSHELL ? "\r\n" : "\n";
        StringBuilder script = new StringBuilder();
        writeHeader(kind, script, newline);
        writeEnvironment(kind, plan.inheritEnvironment(), plan.environmentSet(), plan.environmentUnset(),
                secretResolver, script, newline);
        writeNativeLinkSetup(kind, temporaryNativeLink, nativeFolder, script, newline);
        writeWorkingDirectory(kind, plan.workingDirectory(), script, newline);
        if (plan.preLaunch() != null) {
            writeAuxiliary(kind, plan.preLaunch(), secretResolver, script, newline);
        }
        writeCommand(kind, command, plan.inheritEnvironment(), plan.environmentSet(),
                secretResolver, script, newline);
        if (plan.postExit() != null) {
            writeAuxiliary(kind, plan.postExit(), secretResolver, script, newline);
        }
        writeNativeLinkCleanup(kind, temporaryNativeLink, script, newline);
        if (kind == Kind.BATCH) {
            script.append("pause").append(newline);
        }
        return script.toString();
    }

    /// Writes the optional POSIX native-library symbolic-link creation command.
    ///
    /// @param kind script kind
    /// @param temporaryNativeLink optional temporary link
    /// @param nativeFolder optional link target
    /// @param script output buffer
    /// @param newline line separator
    private static void writeNativeLinkSetup(
            Kind kind,
            @Nullable Path temporaryNativeLink,
            @Nullable Path nativeFolder,
            StringBuilder script,
            String newline
    ) {
        if (temporaryNativeLink == null) {
            return;
        }
        if (kind != Kind.BASH && kind != Kind.MACOS_COMMAND) {
            throw new IllegalArgumentException("Native-library links require a POSIX launch script");
        }
        script.append("ln -s -- ")
                .append(quote(kind, Objects.requireNonNull(nativeFolder).toString()))
                .append(' ')
                .append(quote(kind, temporaryNativeLink.toString()))
                .append(newline);
    }

    /// Writes the optional POSIX native-library symbolic-link cleanup command.
    ///
    /// @param kind script kind
    /// @param temporaryNativeLink optional temporary link
    /// @param script output buffer
    /// @param newline line separator
    private static void writeNativeLinkCleanup(
            Kind kind,
            @Nullable Path temporaryNativeLink,
            StringBuilder script,
            String newline
    ) {
        if (temporaryNativeLink != null) {
            script.append("rm -f -- ")
                    .append(quote(kind, temporaryNativeLink.toString()))
                    .append(newline);
        }
    }

    /// Quotes one token exactly as the selected renderer does.
    ///
    /// @param kind script kind
    /// @param value token value
    /// @return quoted token
    static String quoteForTest(Kind kind, String value) {
        return quote(kind, value);
    }

    /// Selects a renderer from a supported target extension.
    ///
    /// @param scriptFile target file
    /// @return script kind
    private static Kind kindFor(Path scriptFile) {
        String extension = FileUtils.getExtension(scriptFile).toLowerCase(Locale.ROOT);
        return switch (extension) {
            case "bat" -> Kind.BATCH;
            case "ps1" -> Kind.POWERSHELL;
            case "sh", "bash" -> Kind.BASH;
            case "command" -> Kind.MACOS_COMMAND;
            default -> throw new IllegalArgumentException("Unsupported launch script extension: " + scriptFile);
        };
    }

    /// Writes the script interpreter header.
    ///
    /// @param kind script kind
    /// @param script output buffer
    /// @param newline selected line separator
    private static void writeHeader(Kind kind, StringBuilder script, String newline) {
        switch (kind) {
            case BATCH -> script.append("@echo off").append(newline);
            case BASH, MACOS_COMMAND -> script.append("#!/usr/bin/env bash").append(newline);
            case POWERSHELL -> {
            }
        }
    }

    /// Writes environment inheritance, unset operations, and resolved set operations.
    ///
    /// @param kind script kind
    /// @param inheritEnvironment whether to preserve the caller environment
    /// @param environmentSet values to set
    /// @param environmentUnset names to remove
    /// @param resolver secret resolver
    /// @param script output buffer
    /// @param newline line separator
    private static void writeEnvironment(
            Kind kind,
            boolean inheritEnvironment,
            Map<String, LaunchPlanText> environmentSet,
            Set<String> environmentUnset,
            Function<String, @Nullable String> resolver,
            StringBuilder script,
            String newline
    ) {
        if (!inheritEnvironment) {
            switch (kind) {
                case BATCH -> script.append("for /F \"delims==\" %%V in ('set') do set \"%%V=\"").append(newline);
                case POWERSHELL -> script.append("Get-ChildItem Env: | Remove-Item -ErrorAction SilentlyContinue")
                        .append(newline);
                case BASH, MACOS_COMMAND -> script.append("# Main command uses env -i").append(newline);
            }
        }
        for (String name : environmentUnset) {
            switch (kind) {
                case BATCH -> script.append("set \"").append(name).append("=\"").append(newline);
                case POWERSHELL -> script.append("Remove-Item Env:").append(name)
                        .append(" -ErrorAction SilentlyContinue").append(newline);
                case BASH, MACOS_COMMAND -> script.append("unset ").append(name).append(newline);
            }
        }
        for (Map.Entry<String, LaunchPlanText> entry : environmentSet.entrySet()) {
            String value = entry.getValue().resolve(resolver);
            switch (kind) {
                case BATCH -> script.append("set \"").append(entry.getKey()).append('=')
                        .append(batchEnvironmentValue(value)).append("\"").append(newline);
                case POWERSHELL -> script.append("$Env:").append(entry.getKey()).append('=')
                        .append(quote(kind, value)).append(newline);
                case BASH, MACOS_COMMAND -> script.append("export ").append(entry.getKey()).append('=')
                        .append(quote(kind, value)).append(newline);
            }
        }
    }

    /// Writes a working-directory change using native literal syntax.
    ///
    /// @param kind script kind
    /// @param directory target directory
    /// @param script output buffer
    /// @param newline line separator
    private static void writeWorkingDirectory(
            Kind kind,
            Path directory,
            StringBuilder script,
            String newline
    ) {
        switch (kind) {
            case BATCH -> script.append("cd /D ").append(quote(kind, directory.toString())).append(newline);
            case POWERSHELL -> script.append("Set-Location -LiteralPath ")
                    .append(quote(kind, directory.toString())).append(newline);
            case BASH, MACOS_COMMAND -> script.append("cd -- ")
                    .append(quote(kind, directory.toString())).append(newline);
        }
    }

    /// Writes one auxiliary process in an environment and directory scope.
    ///
    /// @param kind script kind
    /// @param auxiliary auxiliary process
    /// @param resolver secret resolver
    /// @param script output buffer
    /// @param newline line separator
    private static void writeAuxiliary(
            Kind kind,
            LaunchAuxiliaryProcessPlan auxiliary,
            Function<String, @Nullable String> resolver,
            StringBuilder script,
            String newline
    ) {
        List<String> command = auxiliary.resolveCommand(resolver);
        switch (kind) {
            case BATCH -> {
                script.append("setlocal").append(newline);
                script.append("pushd ").append(quote(kind, auxiliary.workingDirectory().toString())).append(newline);
                writeEnvironment(kind, auxiliary.inheritEnvironment(), auxiliary.environmentSet(),
                        auxiliary.environmentUnset(), resolver, script, newline);
                script.append(commandLine(kind, command)).append(newline);
                script.append("popd").append(newline).append("endlocal").append(newline);
            }
            case POWERSHELL -> {
                script.append("& {").append(newline);
                script.append("$hmclLocation = Get-Location").append(newline);
                script.append("try {").append(newline);
                writeEnvironment(kind, auxiliary.inheritEnvironment(), auxiliary.environmentSet(),
                        auxiliary.environmentUnset(), resolver, script, newline);
                writeWorkingDirectory(kind, auxiliary.workingDirectory(), script, newline);
                script.append(commandLine(kind, command)).append(newline);
                script.append("} finally { Set-Location -LiteralPath $hmclLocation }").append(newline);
                script.append('}').append(newline);
            }
            case BASH, MACOS_COMMAND -> {
                script.append('(').append(newline);
                writeEnvironment(kind, auxiliary.inheritEnvironment(), auxiliary.environmentSet(),
                        auxiliary.environmentUnset(), resolver, script, newline);
                writeWorkingDirectory(kind, auxiliary.workingDirectory(), script, newline);
                writeCommand(kind, command, auxiliary.inheritEnvironment(), auxiliary.environmentSet(),
                        resolver, script, newline);
                script.append(')').append(newline);
            }
        }
    }

    /// Writes one resolved command, adding `env -i` for isolated POSIX execution.
    ///
    /// @param kind script kind
    /// @param command resolved tokens
    /// @param inheritEnvironment whether to preserve inherited variables
    /// @param environmentSet explicit environment values
    /// @param resolver secret resolver
    /// @param script output buffer
    /// @param newline line separator
    private static void writeCommand(
            Kind kind,
            List<String> command,
            boolean inheritEnvironment,
            Map<String, LaunchPlanText> environmentSet,
            Function<String, @Nullable String> resolver,
            StringBuilder script,
            String newline
    ) {
        if ((kind == Kind.BASH || kind == Kind.MACOS_COMMAND) && !inheritEnvironment) {
            script.append("env -i");
            for (Map.Entry<String, LaunchPlanText> entry : environmentSet.entrySet()) {
                script.append(' ').append(entry.getKey()).append('=')
                        .append(quote(kind, entry.getValue().resolve(resolver)));
            }
            script.append(' ');
        }
        script.append(commandLine(kind, command)).append(newline);
    }

    /// Joins resolved command tokens with the selected native quoting.
    ///
    /// @param kind script kind
    /// @param command resolved tokens
    /// @return command line
    private static String commandLine(Kind kind, List<String> command) {
        List<String> quoted = new ArrayList<>(command.size() + 1);
        if (kind == Kind.POWERSHELL) {
            quoted.add("&");
        }
        for (String token : command) {
            quoted.add(quote(kind, token));
        }
        return String.join(" ", quoted);
    }

    /// Quotes one literal token for the selected script kind.
    ///
    /// @param kind script kind
    /// @param value token value
    /// @return quoted token
    private static String quote(Kind kind, String value) {
        Objects.requireNonNull(value, "value");
        return switch (kind) {
            case BATCH -> CommandBuilder.toBatchStringLiteral(value);
            case POWERSHELL -> CommandBuilder.pwshString(value);
            case BASH, MACOS_COMMAND -> CommandBuilder.toShellStringLiteral(value);
        };
    }

    /// Escapes a value embedded inside Batch's `set "NAME=value"` form.
    ///
    /// @param value environment value
    /// @return escaped Batch value
    private static String batchEnvironmentValue(String value) {
        return value.replace("%", "%%").replace("\"", "\\\"");
    }

    /// Identifies one supported script syntax and encoding family.
    @NotNullByDefault
    enum Kind {
        /// Windows Command Prompt batch syntax.
        BATCH,

        /// PowerShell syntax with a UTF-8 BOM in files.
        POWERSHELL,

        /// Portable Bash script syntax.
        BASH,

        /// macOS Finder-executable Bash command syntax.
        MACOS_COMMAND
    }
}
