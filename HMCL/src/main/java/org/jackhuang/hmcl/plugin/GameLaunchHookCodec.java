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
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/// Encodes and validates the versioned Runtime-neutral game-launch Hook data envelope.
@NotNullByDefault
final class GameLaunchHookCodec {
    /// Current game-launch Hook envelope contract generation.
    private static final int CONTRACT_VERSION = 1;

    /// Required version-one process-plan fields.
    private static final @Unmodifiable Set<String> PLAN_FIELDS = Set.of(
            "planVersion", "executionMode", "command", "workingDirectory", "inheritEnvironment",
            "environmentSet", "environmentUnset", "preLaunch", "postExit", "launcherVisibility",
            "inheritIo", "daemonMonitors");

    /// Required structured-Java command fields.
    private static final @Unmodifiable Set<String> STRUCTURED_COMMAND_FIELDS = Set.of(
            "mode", "prefixTokens", "javaExecutable", "jvmArguments", "classpathEntries",
            "mainClass", "gameArguments");

    /// Required raw command fields.
    private static final @Unmodifiable Set<String> RAW_COMMAND_FIELDS = Set.of("mode", "rawCommand");

    /// Required auxiliary-process fields.
    private static final @Unmodifiable Set<String> AUXILIARY_FIELDS = Set.of(
            "command", "workingDirectory", "inheritEnvironment", "environmentSet", "environmentUnset");

    /// Required template-text fields.
    private static final @Unmodifiable Set<String> TEMPLATE_FIELDS = Set.of("kind", "segments");

    /// Required literal-segment fields.
    private static final @Unmodifiable Set<String> LITERAL_SEGMENT_FIELDS = Set.of("kind", "value");

    /// Required secret-segment fields.
    private static final @Unmodifiable Set<String> SECRET_SEGMENT_FIELDS = Set.of("kind", "slot");

    /// Prevents construction of the static codec.
    private GameLaunchHookCodec() {
    }

    /// Encodes immutable launch metadata and one mutable process plan for before dispatch.
    ///
    /// @param plan immutable unresolved process plan
    /// @param metadata immutable launch metadata
    /// @return versioned before-launch envelope
    static PluginDataObject encodeBefore(LaunchProcessPlan plan, PluginDataObject metadata) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(metadata, "metadata");
        return PluginDataObject.of(Map.of(
                "contractVersion", number(CONTRACT_VERSION),
                "metadata", PluginDataValue.object(metadata),
                "plan", PluginDataValue.object(encodePlan(plan))
        ));
    }

    /// Decodes one before-launch envelope without comparing metadata to an earlier snapshot.
    ///
    /// @param data candidate before-launch envelope
    /// @param secretSlots available launch secret slots
    /// @return validated immutable process plan
    static LaunchProcessPlan decodeBefore(PluginDataObject data, Set<String> secretSlots) {
        return decodeBefore(data, null, secretSlots);
    }

    /// Decodes one before-launch envelope and rejects immutable metadata replacement.
    ///
    /// @param data candidate before-launch envelope
    /// @param expectedMetadata expected immutable launch metadata, or `null` to skip comparison
    /// @param secretSlots available launch secret slots
    /// @return validated immutable process plan
    static LaunchProcessPlan decodeBefore(
            PluginDataObject data,
            @Nullable PluginDataObject expectedMetadata,
            Set<String> secretSlots
    ) {
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(secretSlots, "secretSlots");
        int contractVersion = requireInteger(data, "contractVersion", "$");
        if (contractVersion != CONTRACT_VERSION) {
            throw invalid("$.contractVersion is unsupported");
        }
        PluginDataObject metadata = requireObject(data, "metadata", "$");
        if (expectedMetadata != null && !expectedMetadata.equals(metadata)) {
            throw invalid("$.metadata is immutable");
        }
        LaunchProcessPlan plan = decodePlan(requireObject(data, "plan", "$"));
        plan.validate(secretSlots);
        return plan;
    }

    /// Encodes a redacted final plan and immutable process termination observations.
    ///
    /// @param plan final unresolved process plan
    /// @param metadata immutable launch metadata
    /// @param pid owned process ID
    /// @param exitCode process exit code, or `null` when unavailable
    /// @param terminationKind stable termination kind
    /// @param startedAt process start instant
    /// @param endedAt process end instant
    /// @param elapsedMilliseconds elapsed process lifetime
    /// @return versioned after-launch envelope
    static PluginDataObject encodeAfter(
            LaunchProcessPlan plan,
            PluginDataObject metadata,
            long pid,
            @Nullable Integer exitCode,
            String terminationKind,
            Instant startedAt,
            Instant endedAt,
            long elapsedMilliseconds
    ) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(terminationKind, "terminationKind");
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(endedAt, "endedAt");
        if (pid < 0 || elapsedMilliseconds < 0 || terminationKind.isBlank()) {
            throw new IllegalArgumentException("Invalid process termination observation");
        }
        Map<String, PluginDataValue> values = new LinkedHashMap<>();
        values.put("contractVersion", number(CONTRACT_VERSION));
        values.put("metadata", PluginDataValue.object(metadata));
        values.put("plan", PluginDataValue.object(encodePlan(plan)));
        values.put("pid", number(pid));
        values.put("exitCode", exitCode == null ? PluginDataValue.nullValue() : number(exitCode));
        values.put("terminationKind", PluginDataValue.string(terminationKind));
        values.put("startedAt", PluginDataValue.string(startedAt.toString()));
        values.put("endedAt", PluginDataValue.string(endedAt.toString()));
        values.put("elapsedMilliseconds", number(elapsedMilliseconds));
        return PluginDataObject.of(values);
    }

    /// Encodes every mutable process-plan field.
    ///
    /// @param plan process plan
    /// @return encoded plan object
    private static PluginDataObject encodePlan(LaunchProcessPlan plan) {
        Map<String, PluginDataValue> values = new LinkedHashMap<>();
        values.put("planVersion", number(plan.planVersion()));
        values.put("executionMode", PluginDataValue.string(executionMode(plan.executionMode())));
        values.put("command", PluginDataValue.object(encodeCommand(plan.command())));
        values.put("workingDirectory", PluginDataValue.string(plan.workingDirectory().toString()));
        values.put("inheritEnvironment", PluginDataValue.bool(plan.inheritEnvironment()));
        values.put("environmentSet", PluginDataValue.object(encodeEnvironment(plan.environmentSet())));
        values.put("environmentUnset", encodeStrings(plan.environmentUnset()));
        values.put("preLaunch", encodeNullableAuxiliary(plan.preLaunch()));
        values.put("postExit", encodeNullableAuxiliary(plan.postExit()));
        values.put("launcherVisibility", PluginDataValue.string(plan.launcherVisibility()));
        values.put("inheritIo", PluginDataValue.bool(plan.inheritIo()));
        values.put("daemonMonitors", PluginDataValue.bool(plan.daemonMonitors()));
        return PluginDataObject.of(values);
    }

    /// Decodes every required version-one process-plan field.
    ///
    /// @param value encoded plan object
    /// @return immutable decoded plan
    private static LaunchProcessPlan decodePlan(PluginDataObject value) {
        requireOnly(value, PLAN_FIELDS, "$.plan");
        return new LaunchProcessPlan(
                requireInteger(value, "planVersion", "$.plan"),
                decodeExecutionMode(requireString(value, "executionMode", "$.plan")),
                decodeCommand(requireObject(value, "command", "$.plan")),
                Path.of(requireString(value, "workingDirectory", "$.plan")),
                requireBoolean(value, "inheritEnvironment", "$.plan"),
                decodeEnvironment(requireObject(value, "environmentSet", "$.plan"),
                        "$.plan.environmentSet"),
                decodeStringSet(requireArray(value, "environmentUnset", "$.plan"),
                        "$.plan.environmentUnset"),
                decodeNullableAuxiliary(require(value, "preLaunch", "$.plan"), "$.plan.preLaunch"),
                decodeNullableAuxiliary(require(value, "postExit", "$.plan"), "$.plan.postExit"),
                requireString(value, "launcherVisibility", "$.plan"),
                requireBoolean(value, "inheritIo", "$.plan"),
                requireBoolean(value, "daemonMonitors", "$.plan")
        );
    }

    /// Encodes the active structured or raw command representation.
    ///
    /// @param command command plan
    /// @return encoded command object
    private static PluginDataObject encodeCommand(LaunchCommandPlan command) {
        Map<String, PluginDataValue> values = new LinkedHashMap<>();
        if (command.mode() == LaunchCommandPlan.Mode.RAW) {
            values.put("mode", PluginDataValue.string("raw"));
            values.put("rawCommand", encodeTexts(command.rawCommand()));
        } else {
            values.put("mode", PluginDataValue.string("structured-java"));
            values.put("prefixTokens", encodeTexts(command.prefixTokens()));
            values.put("javaExecutable", encodeText(Objects.requireNonNull(command.javaExecutable())));
            values.put("jvmArguments", encodeTexts(command.jvmArguments()));
            values.put("classpathEntries", encodeTexts(command.classpathEntries()));
            values.put("mainClass", encodeText(Objects.requireNonNull(command.mainClass())));
            values.put("gameArguments", encodeTexts(command.gameArguments()));
        }
        return PluginDataObject.of(values);
    }

    /// Decodes the command representation selected by its explicit mode.
    ///
    /// @param value encoded command
    /// @return immutable command plan
    private static LaunchCommandPlan decodeCommand(PluginDataObject value) {
        String mode = requireString(value, "mode", "$.plan.command");
        return switch (mode) {
            case "raw" -> {
                requireOnly(value, RAW_COMMAND_FIELDS, "$.plan.command");
                yield LaunchCommandPlan.raw(decodeTexts(
                        requireArray(value, "rawCommand", "$.plan.command"), "$.plan.command.rawCommand"));
            }
            case "structured-java" -> {
                requireOnly(value, STRUCTURED_COMMAND_FIELDS, "$.plan.command");
                yield LaunchCommandPlan.structuredJava(
                        decodeTexts(requireArray(value, "prefixTokens", "$.plan.command"),
                                "$.plan.command.prefixTokens"),
                        decodeText(require(value, "javaExecutable", "$.plan.command"),
                                "$.plan.command.javaExecutable"),
                        decodeTexts(requireArray(value, "jvmArguments", "$.plan.command"),
                                "$.plan.command.jvmArguments"),
                        decodeTexts(requireArray(value, "classpathEntries", "$.plan.command"),
                                "$.plan.command.classpathEntries"),
                        decodeText(require(value, "mainClass", "$.plan.command"), "$.plan.command.mainClass"),
                        decodeTexts(requireArray(value, "gameArguments", "$.plan.command"),
                                "$.plan.command.gameArguments")
                );
            }
            default -> throw invalid("$.plan.command.mode is unsupported");
        };
    }

    /// Encodes one optional auxiliary process.
    ///
    /// @param auxiliary auxiliary process or `null`
    /// @return encoded object or JSON null
    private static PluginDataValue encodeNullableAuxiliary(@Nullable LaunchAuxiliaryProcessPlan auxiliary) {
        return auxiliary == null ? PluginDataValue.nullValue() : PluginDataValue.object(encodeAuxiliary(auxiliary));
    }

    /// Encodes every auxiliary-process field.
    ///
    /// @param auxiliary auxiliary process
    /// @return encoded auxiliary object
    private static PluginDataObject encodeAuxiliary(LaunchAuxiliaryProcessPlan auxiliary) {
        Map<String, PluginDataValue> values = new LinkedHashMap<>();
        values.put("command", encodeTexts(auxiliary.command()));
        values.put("workingDirectory", PluginDataValue.string(auxiliary.workingDirectory().toString()));
        values.put("inheritEnvironment", PluginDataValue.bool(auxiliary.inheritEnvironment()));
        values.put("environmentSet", PluginDataValue.object(encodeEnvironment(auxiliary.environmentSet())));
        values.put("environmentUnset", encodeStrings(auxiliary.environmentUnset()));
        return PluginDataObject.of(values);
    }

    /// Decodes one optional auxiliary process.
    ///
    /// @param value encoded object or JSON null
    /// @param path diagnostic path
    /// @return auxiliary process or `null`
    private static @Nullable LaunchAuxiliaryProcessPlan decodeNullableAuxiliary(
            PluginDataValue value,
            String path
    ) {
        if (value instanceof PluginDataValue.NullValue) {
            return null;
        }
        if (!(value instanceof PluginDataValue.ObjectValue objectValue)) {
            throw invalid(path + " must be object or null");
        }
        PluginDataObject object = objectValue.value();
        requireOnly(object, AUXILIARY_FIELDS, path);
        return new LaunchAuxiliaryProcessPlan(
                decodeTexts(requireArray(object, "command", path), path + ".command"),
                Path.of(requireString(object, "workingDirectory", path)),
                requireBoolean(object, "inheritEnvironment", path),
                decodeEnvironment(requireObject(object, "environmentSet", path), path + ".environmentSet"),
                decodeStringSet(requireArray(object, "environmentUnset", path), path + ".environmentUnset")
        );
    }

    /// Encodes environment values as secret-aware text properties.
    ///
    /// @param environment environment values
    /// @return encoded environment object
    private static PluginDataObject encodeEnvironment(Map<String, LaunchPlanText> environment) {
        Map<String, PluginDataValue> values = new LinkedHashMap<>();
        for (Map.Entry<String, LaunchPlanText> entry : environment.entrySet()) {
            values.put(entry.getKey(), encodeText(entry.getValue()));
        }
        return PluginDataObject.of(values);
    }

    /// Decodes environment values as secret-aware text properties.
    ///
    /// @param value encoded environment object
    /// @param path diagnostic path
    /// @return immutable environment map
    private static @Unmodifiable Map<String, LaunchPlanText> decodeEnvironment(
            PluginDataObject value,
            String path
    ) {
        Map<String, LaunchPlanText> environment = new LinkedHashMap<>();
        for (Map.Entry<String, PluginDataValue> entry : value.values().entrySet()) {
            environment.put(entry.getKey(), decodeText(entry.getValue(), path + "." + entry.getKey()));
        }
        return Collections.unmodifiableMap(environment);
    }

    /// Encodes ordered secret-aware text values.
    ///
    /// @param texts text values
    /// @return encoded array
    private static PluginDataValue encodeTexts(List<LaunchPlanText> texts) {
        List<PluginDataValue> values = new ArrayList<>(texts.size());
        for (LaunchPlanText text : texts) {
            values.add(encodeText(text));
        }
        return PluginDataValue.array(values);
    }

    /// Decodes ordered secret-aware text values.
    ///
    /// @param values encoded values
    /// @param path diagnostic path
    /// @return immutable decoded text list
    private static @Unmodifiable List<LaunchPlanText> decodeTexts(
            List<PluginDataValue> values,
            String path
    ) {
        List<LaunchPlanText> texts = new ArrayList<>(values.size());
        for (int index = 0; index < values.size(); index++) {
            texts.add(decodeText(values.get(index), path + "[" + index + "]"));
        }
        return List.copyOf(texts);
    }

    /// Encodes literal text directly and templates as explicit segment objects.
    ///
    /// @param text launch-plan text
    /// @return encoded text
    private static PluginDataValue encodeText(LaunchPlanText text) {
        List<LaunchPlanText.Segment> segments = text.segments();
        if (segments.size() == 1 && segments.get(0) instanceof LaunchPlanText.LiteralSegment literal) {
            return PluginDataValue.string(literal.value());
        }
        List<PluginDataValue> encodedSegments = new ArrayList<>(segments.size());
        for (LaunchPlanText.Segment segment : segments) {
            if (segment instanceof LaunchPlanText.LiteralSegment literal) {
                encodedSegments.add(PluginDataValue.object(PluginDataObject.of(Map.of(
                        "kind", PluginDataValue.string("literal"),
                        "value", PluginDataValue.string(literal.value())
                ))));
            } else {
                LaunchPlanText.SecretSegment secret = (LaunchPlanText.SecretSegment) segment;
                encodedSegments.add(PluginDataValue.object(PluginDataObject.of(Map.of(
                        "kind", PluginDataValue.string("secret"),
                        "slot", PluginDataValue.string(secret.slot())
                ))));
            }
        }
        return PluginDataValue.object(PluginDataObject.of(Map.of(
                "kind", PluginDataValue.string("template"),
                "segments", PluginDataValue.array(encodedSegments)
        )));
    }

    /// Decodes one literal string or explicit template object.
    ///
    /// @param value encoded text
    /// @param path diagnostic path
    /// @return immutable launch-plan text
    private static LaunchPlanText decodeText(PluginDataValue value, String path) {
        if (value instanceof PluginDataValue.StringValue stringValue) {
            return LaunchPlanText.literal(stringValue.value());
        }
        if (!(value instanceof PluginDataValue.ObjectValue objectValue)) {
            throw invalid(path + " must be string or template");
        }
        PluginDataObject template = objectValue.value();
        requireOnly(template, TEMPLATE_FIELDS, path);
        if (!"template".equals(requireString(template, "kind", path))) {
            throw invalid(path + ".kind is unsupported");
        }
        List<PluginDataValue> encodedSegments = requireArray(template, "segments", path);
        List<LaunchPlanText.Segment> segments = new ArrayList<>(encodedSegments.size());
        for (int index = 0; index < encodedSegments.size(); index++) {
            String segmentPath = path + ".segments[" + index + "]";
            PluginDataValue encodedSegment = encodedSegments.get(index);
            if (!(encodedSegment instanceof PluginDataValue.ObjectValue segmentObject)) {
                throw invalid(segmentPath + " must be object");
            }
            PluginDataObject segment = segmentObject.value();
            String kind = requireString(segment, "kind", segmentPath);
            switch (kind) {
                case "literal" -> {
                    requireOnly(segment, LITERAL_SEGMENT_FIELDS, segmentPath);
                    segments.add(new LaunchPlanText.LiteralSegment(
                            requireString(segment, "value", segmentPath)));
                }
                case "secret" -> {
                    requireOnly(segment, SECRET_SEGMENT_FIELDS, segmentPath);
                    segments.add(new LaunchPlanText.SecretSegment(
                            requireString(segment, "slot", segmentPath)));
                }
                default -> throw invalid(segmentPath + ".kind is unsupported");
            }
        }
        return LaunchPlanText.template(segments);
    }

    /// Encodes ordered strings as one immutable array.
    ///
    /// @param strings string values
    /// @return encoded array
    private static PluginDataValue encodeStrings(Iterable<String> strings) {
        List<PluginDataValue> values = new ArrayList<>();
        for (String string : strings) {
            values.add(PluginDataValue.string(string));
        }
        return PluginDataValue.array(values);
    }

    /// Decodes an ordered string array into an immutable insertion-ordered set.
    ///
    /// @param values encoded values
    /// @param path diagnostic path
    /// @return immutable decoded strings
    private static @Unmodifiable Set<String> decodeStringSet(
            List<PluginDataValue> values,
            String path
    ) {
        Set<String> strings = new LinkedHashSet<>();
        for (int index = 0; index < values.size(); index++) {
            PluginDataValue value = values.get(index);
            if (!(value instanceof PluginDataValue.StringValue stringValue)) {
                throw invalid(path + "[" + index + "] must be string");
            }
            if (!strings.add(stringValue.value())) {
                throw invalid(path + " contains duplicate value");
            }
        }
        return Collections.unmodifiableSet(strings);
    }

    /// Encodes direct or script execution mode using stable wire identifiers.
    ///
    /// @param mode execution mode
    /// @return stable identifier
    private static String executionMode(LaunchExecutionMode mode) {
        return switch (mode) {
            case DIRECT -> "direct";
            case SCRIPT -> "script";
        };
    }

    /// Decodes one stable execution-mode identifier.
    ///
    /// @param value stable identifier
    /// @return execution mode
    private static LaunchExecutionMode decodeExecutionMode(String value) {
        return switch (value) {
            case "direct" -> LaunchExecutionMode.DIRECT;
            case "script" -> LaunchExecutionMode.SCRIPT;
            default -> throw invalid("$.plan.executionMode is unsupported");
        };
    }

    /// Requires an object to contain exactly the version-one allowlisted fields.
    ///
    /// @param object candidate object
    /// @param fields required allowlist
    /// @param path diagnostic path
    private static void requireOnly(PluginDataObject object, Set<String> fields, String path) {
        if (!object.values().keySet().equals(fields)) {
            throw invalid(path + " has missing or unknown fields");
        }
    }

    /// Requires one property to exist.
    ///
    /// @param object containing object
    /// @param key property name
    /// @param path containing-object path
    /// @return property value
    private static PluginDataValue require(PluginDataObject object, String key, String path) {
        @Nullable PluginDataValue value = object.get(key);
        if (value == null) {
            throw invalid(path + "." + key + " is required");
        }
        return value;
    }

    /// Requires one object property.
    ///
    /// @param object containing object
    /// @param key property name
    /// @param path containing-object path
    /// @return object value
    private static PluginDataObject requireObject(PluginDataObject object, String key, String path) {
        PluginDataValue value = require(object, key, path);
        if (!(value instanceof PluginDataValue.ObjectValue objectValue)) {
            throw invalid(path + "." + key + " must be object");
        }
        return objectValue.value();
    }

    /// Requires one array property.
    ///
    /// @param object containing object
    /// @param key property name
    /// @param path containing-object path
    /// @return immutable array values
    private static @Unmodifiable List<PluginDataValue> requireArray(
            PluginDataObject object,
            String key,
            String path
    ) {
        PluginDataValue value = require(object, key, path);
        if (!(value instanceof PluginDataValue.ArrayValue arrayValue)) {
            throw invalid(path + "." + key + " must be array");
        }
        return arrayValue.values();
    }

    /// Requires one string property.
    ///
    /// @param object containing object
    /// @param key property name
    /// @param path containing-object path
    /// @return string value
    private static String requireString(PluginDataObject object, String key, String path) {
        PluginDataValue value = require(object, key, path);
        if (!(value instanceof PluginDataValue.StringValue stringValue)) {
            throw invalid(path + "." + key + " must be string");
        }
        return stringValue.value();
    }

    /// Requires one boolean property.
    ///
    /// @param object containing object
    /// @param key property name
    /// @param path containing-object path
    /// @return boolean value
    private static boolean requireBoolean(PluginDataObject object, String key, String path) {
        PluginDataValue value = require(object, key, path);
        if (!(value instanceof PluginDataValue.BooleanValue booleanValue)) {
            throw invalid(path + "." + key + " must be boolean");
        }
        return booleanValue.value();
    }

    /// Requires one exact integer property.
    ///
    /// @param object containing object
    /// @param key property name
    /// @param path containing-object path
    /// @return exact integer value
    private static int requireInteger(PluginDataObject object, String key, String path) {
        PluginDataValue value = require(object, key, path);
        if (!(value instanceof PluginDataValue.NumberValue numberValue)) {
            throw invalid(path + "." + key + " must be integer");
        }
        try {
            return numberValue.value().intValueExact();
        } catch (ArithmeticException exception) {
            throw invalid(path + "." + key + " must be integer", exception);
        }
    }

    /// Wraps an integer as one arbitrary-precision Hook number.
    ///
    /// @param value integer value
    /// @return Hook number
    private static PluginDataValue number(long value) {
        return PluginDataValue.number(BigDecimal.valueOf(value));
    }

    /// Creates one path-only malformed-data exception.
    ///
    /// @param message safe diagnostic message
    /// @return validation exception
    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }

    /// Creates one path-only malformed-data exception with an internal cause.
    ///
    /// @param message safe diagnostic message
    /// @param cause internal cause
    /// @return validation exception
    private static IllegalArgumentException invalid(String message, Throwable cause) {
        return new IllegalArgumentException(message, cause);
    }
}
