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
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/// Defines the single authoritative structured-Java or raw process command representation.
@NotNullByDefault
public final class LaunchCommandPlan {
    /// Active command representation.
    private final Mode mode;

    /// Tokens that precede the Java executable in structured mode.
    private final @Unmodifiable List<LaunchPlanText> prefixTokens;

    /// Java executable in structured mode.
    private final @Nullable LaunchPlanText javaExecutable;

    /// JVM arguments in structured mode.
    private final @Unmodifiable List<LaunchPlanText> jvmArguments;

    /// Classpath entries in structured mode.
    private final @Unmodifiable List<LaunchPlanText> classpathEntries;

    /// Main class in structured mode.
    private final @Nullable LaunchPlanText mainClass;

    /// Game arguments in structured mode.
    private final @Unmodifiable List<LaunchPlanText> gameArguments;

    /// Complete command tokens in raw mode.
    private final @Unmodifiable List<LaunchPlanText> rawCommand;

    /// Creates one internally consistent command representation.
    ///
    /// @param mode active representation
    /// @param prefixTokens structured prefix tokens
    /// @param javaExecutable structured Java executable
    /// @param jvmArguments structured JVM arguments
    /// @param classpathEntries structured classpath entries
    /// @param mainClass structured main class
    /// @param gameArguments structured game arguments
    /// @param rawCommand raw command tokens
    private LaunchCommandPlan(
            Mode mode,
            List<LaunchPlanText> prefixTokens,
            @Nullable LaunchPlanText javaExecutable,
            List<LaunchPlanText> jvmArguments,
            List<LaunchPlanText> classpathEntries,
            @Nullable LaunchPlanText mainClass,
            List<LaunchPlanText> gameArguments,
            List<LaunchPlanText> rawCommand
    ) {
        this.mode = Objects.requireNonNull(mode, "mode");
        this.prefixTokens = copyTokens(prefixTokens, "prefixTokens", true);
        this.javaExecutable = javaExecutable;
        this.jvmArguments = copyTokens(jvmArguments, "jvmArguments", true);
        this.classpathEntries = copyTokens(classpathEntries, "classpathEntries", true);
        this.mainClass = mainClass;
        this.gameArguments = copyTokens(gameArguments, "gameArguments", true);
        this.rawCommand = copyTokens(rawCommand, "rawCommand", true);

        if (mode == Mode.STRUCTURED_JAVA) {
            if (javaExecutable == null || javaExecutable.isDefinitelyBlank()) {
                throw new IllegalArgumentException("Structured Java executable must not be blank");
            }
            if (mainClass == null || mainClass.isDefinitelyBlank()) {
                throw new IllegalArgumentException("Structured Java main class must not be blank");
            }
            if (!this.rawCommand.isEmpty()) {
                throw new IllegalArgumentException("Structured command cannot carry raw command tokens");
            }
        } else {
            if (javaExecutable != null || mainClass != null || !this.prefixTokens.isEmpty()
                    || !this.jvmArguments.isEmpty() || !this.classpathEntries.isEmpty()
                    || !this.gameArguments.isEmpty()) {
                throw new IllegalArgumentException("Raw command cannot carry structured Java fields");
            }
            if (this.rawCommand.isEmpty()) {
                throw new IllegalArgumentException("Raw command must not be empty");
            }
        }
    }

    /// Creates a structured Java command.
    ///
    /// @param prefixTokens tokens before the Java executable
    /// @param javaExecutable Java executable
    /// @param jvmArguments JVM arguments
    /// @param classpathEntries classpath entries
    /// @param mainClass main class
    /// @param gameArguments game arguments
    /// @return immutable structured command
    public static LaunchCommandPlan structuredJava(
            List<LaunchPlanText> prefixTokens,
            LaunchPlanText javaExecutable,
            List<LaunchPlanText> jvmArguments,
            List<LaunchPlanText> classpathEntries,
            LaunchPlanText mainClass,
            List<LaunchPlanText> gameArguments
    ) {
        return new LaunchCommandPlan(Mode.STRUCTURED_JAVA, prefixTokens, javaExecutable, jvmArguments,
                classpathEntries, mainClass, gameArguments, List.of());
    }

    /// Creates a complete raw command.
    ///
    /// @param rawCommand complete command tokens
    /// @return immutable raw command
    public static LaunchCommandPlan raw(List<LaunchPlanText> rawCommand) {
        return new LaunchCommandPlan(Mode.RAW, List.of(), null, List.of(), List.of(),
                null, List.of(), rawCommand);
    }

    /// Replaces either representation with one complete raw command.
    ///
    /// @param command complete raw command tokens
    /// @return immutable raw replacement
    public LaunchCommandPlan replaceWithRawCommand(List<LaunchPlanText> command) {
        return raw(command);
    }

    /// Returns the active command mode.
    ///
    /// @return command mode
    public Mode mode() {
        return mode;
    }

    /// Returns structured prefix tokens, or an empty list in raw mode.
    ///
    /// @return immutable prefix tokens
    public @Unmodifiable List<LaunchPlanText> prefixTokens() {
        return prefixTokens;
    }

    /// Returns the structured Java executable.
    ///
    /// @return Java executable or `null` in raw mode
    public @Nullable LaunchPlanText javaExecutable() {
        return javaExecutable;
    }

    /// Returns structured JVM arguments, or an empty list in raw mode.
    ///
    /// @return immutable JVM arguments
    public @Unmodifiable List<LaunchPlanText> jvmArguments() {
        return jvmArguments;
    }

    /// Returns structured classpath entries, or an empty list in raw mode.
    ///
    /// @return immutable classpath entries
    public @Unmodifiable List<LaunchPlanText> classpathEntries() {
        return classpathEntries;
    }

    /// Returns the structured main class.
    ///
    /// @return main class or `null` in raw mode
    public @Nullable LaunchPlanText mainClass() {
        return mainClass;
    }

    /// Returns structured game arguments, or an empty list in raw mode.
    ///
    /// @return immutable game arguments
    public @Unmodifiable List<LaunchPlanText> gameArguments() {
        return gameArguments;
    }

    /// Returns raw command tokens, or an empty list in structured mode.
    ///
    /// @return immutable raw command
    public @Unmodifiable List<LaunchPlanText> rawCommand() {
        return rawCommand;
    }

    /// Resolves this representation to the exact ordered process token list.
    ///
    /// @param resolver secret-slot resolver
    /// @return immutable resolved command tokens
    public @Unmodifiable List<String> resolve(Function<String, @Nullable String> resolver) {
        Objects.requireNonNull(resolver, "resolver");
        List<String> result = new ArrayList<>();
        if (mode == Mode.RAW) {
            resolveTokens(rawCommand, resolver, result);
        } else {
            resolveTokens(prefixTokens, resolver, result);
            result.add(Objects.requireNonNull(javaExecutable).resolve(resolver));
            resolveTokens(jvmArguments, resolver, result);
            if (!classpathEntries.isEmpty()) {
                result.add("-cp");
                List<String> classpath = new ArrayList<>();
                resolveTokens(classpathEntries, resolver, classpath);
                result.add(String.join(File.pathSeparator, classpath));
            }
            result.add(Objects.requireNonNull(mainClass).resolve(resolver));
            resolveTokens(gameArguments, resolver, result);
        }
        for (String token : result) {
            if (token.isBlank()) {
                throw new IllegalArgumentException("Resolved launch command contains a blank token");
            }
        }
        return List.copyOf(result);
    }

    /// Returns every secret slot referenced by this command.
    ///
    /// @return immutable referenced slots
    public @Unmodifiable Set<String> secretSlots() {
        Set<String> slots = new LinkedHashSet<>();
        collectSlots(prefixTokens, slots);
        if (javaExecutable != null) {
            slots.addAll(javaExecutable.secretSlots());
        }
        collectSlots(jvmArguments, slots);
        collectSlots(classpathEntries, slots);
        if (mainClass != null) {
            slots.addAll(mainClass.secretSlots());
        }
        collectSlots(gameArguments, slots);
        collectSlots(rawCommand, slots);
        return Collections.unmodifiableSet(slots);
    }

    /// Validates every secret reference with a path identifying its command field.
    ///
    /// @param availableSecretSlots launch-scoped available slots
    /// @param path root diagnostic path
    void validate(Set<String> availableSecretSlots, String path) {
        validateTexts(prefixTokens, availableSecretSlots, path + ".prefixTokens");
        if (javaExecutable != null) {
            LaunchProcessPlan.validateText(javaExecutable, availableSecretSlots, path + ".javaExecutable");
        }
        validateTexts(jvmArguments, availableSecretSlots, path + ".jvmArguments");
        validateTexts(classpathEntries, availableSecretSlots, path + ".classpathEntries");
        if (mainClass != null) {
            LaunchProcessPlan.validateText(mainClass, availableSecretSlots, path + ".mainClass");
        }
        validateTexts(gameArguments, availableSecretSlots, path + ".gameArguments");
        validateTexts(rawCommand, availableSecretSlots, path + ".rawCommand");
    }

    /// Copies command tokens and rejects null or statically blank values.
    ///
    /// @param source source tokens
    /// @param field diagnostic field name
    /// @param allowEmpty whether an empty list is valid
    /// @return immutable copied tokens
    private static @Unmodifiable List<LaunchPlanText> copyTokens(
            List<LaunchPlanText> source, String field, boolean allowEmpty) {
        Objects.requireNonNull(source, field);
        List<LaunchPlanText> copy = List.copyOf(source);
        if (!allowEmpty && copy.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be empty");
        }
        for (LaunchPlanText text : copy) {
            if (text.isDefinitelyBlank()) {
                throw new IllegalArgumentException(field + " contains a blank token");
            }
        }
        return copy;
    }

    /// Resolves and appends ordered text tokens.
    ///
    /// @param source unresolved tokens
    /// @param resolver secret resolver
    /// @param target resolved target list
    private static void resolveTokens(
            List<LaunchPlanText> source,
            Function<String, @Nullable String> resolver,
            List<String> target
    ) {
        for (LaunchPlanText text : source) {
            target.add(text.resolve(resolver));
        }
    }

    /// Adds every secret slot from ordered text values.
    ///
    /// @param source unresolved values
    /// @param target slot target
    private static void collectSlots(List<LaunchPlanText> source, Set<String> target) {
        for (LaunchPlanText text : source) {
            target.addAll(text.secretSlots());
        }
    }

    /// Validates ordered text values with indexed diagnostic paths.
    ///
    /// @param values unresolved values
    /// @param availableSecretSlots available slots
    /// @param path root list path
    private static void validateTexts(List<LaunchPlanText> values, Set<String> availableSecretSlots, String path) {
        for (int index = 0; index < values.size(); index++) {
            LaunchProcessPlan.validateText(values.get(index), availableSecretSlots, path + "[" + index + "]");
        }
    }

    /// Compares complete unresolved command representations.
    ///
    /// @param other candidate value
    /// @return whether every command field is equal
    @Override
    public boolean equals(@Nullable Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof LaunchCommandPlan that)) {
            return false;
        }
        return mode == that.mode
                && prefixTokens.equals(that.prefixTokens)
                && Objects.equals(javaExecutable, that.javaExecutable)
                && jvmArguments.equals(that.jvmArguments)
                && classpathEntries.equals(that.classpathEntries)
                && Objects.equals(mainClass, that.mainClass)
                && gameArguments.equals(that.gameArguments)
                && rawCommand.equals(that.rawCommand);
    }

    /// Returns the complete unresolved command hash.
    ///
    /// @return command hash
    @Override
    public int hashCode() {
        return Objects.hash(mode, prefixTokens, javaExecutable, jvmArguments,
                classpathEntries, mainClass, gameArguments, rawCommand);
    }

    /// Returns an unresolved representation with no resolved secret values.
    ///
    /// @return unresolved command representation
    @Override
    public String toString() {
        return "LaunchCommandPlan[mode=" + mode + ", prefixTokens=" + prefixTokens
                + ", javaExecutable=" + javaExecutable + ", jvmArguments=" + jvmArguments
                + ", classpathEntries=" + classpathEntries + ", mainClass=" + mainClass
                + ", gameArguments=" + gameArguments + ", rawCommand=" + rawCommand + "]";
    }

    /// Selects the authoritative command representation.
    @NotNullByDefault
    public enum Mode {
        /// Separate Java executable, JVM, classpath, main-class, and game-argument fields.
        STRUCTURED_JAVA,

        /// Complete ordered command tokens with no structured fields.
        RAW
    }
}
