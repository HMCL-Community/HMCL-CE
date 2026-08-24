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

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/// Describes one immutable pre-launch or post-exit auxiliary process.
@NotNullByDefault
public final class LaunchAuxiliaryProcessPlan {
    /// Complete command tokens.
    private final @Unmodifiable List<LaunchPlanText> command;

    /// Process working directory.
    private final Path workingDirectory;

    /// Whether to start from the launcher environment.
    private final boolean inheritEnvironment;

    /// Environment values to set after inheritance.
    private final @Unmodifiable Map<String, LaunchPlanText> environmentSet;

    /// Environment names to remove after inheritance.
    private final @Unmodifiable Set<String> environmentUnset;

    /// Creates one complete auxiliary process description.
    ///
    /// @param command complete command tokens
    /// @param workingDirectory process working directory
    /// @param inheritEnvironment whether to inherit the launcher environment
    /// @param environmentSet environment values to set
    /// @param environmentUnset environment names to remove
    public LaunchAuxiliaryProcessPlan(
            List<LaunchPlanText> command,
            Path workingDirectory,
            boolean inheritEnvironment,
            Map<String, LaunchPlanText> environmentSet,
            Set<String> environmentUnset
    ) {
        Objects.requireNonNull(command, "command");
        this.command = List.copyOf(command);
        if (this.command.isEmpty()) {
            throw new IllegalArgumentException("Auxiliary process command must not be empty");
        }
        for (LaunchPlanText text : this.command) {
            if (text.isDefinitelyBlank()) {
                throw new IllegalArgumentException("Auxiliary process command contains a blank token");
            }
        }
        this.workingDirectory = Objects.requireNonNull(workingDirectory, "workingDirectory");
        this.inheritEnvironment = inheritEnvironment;
        this.environmentSet = LaunchProcessPlan.copyEnvironment(environmentSet);
        this.environmentUnset = LaunchProcessPlan.copyEnvironmentUnset(environmentUnset);
    }

    /// Returns complete unresolved command tokens.
    ///
    /// @return immutable command tokens
    public @Unmodifiable List<LaunchPlanText> command() {
        return command;
    }

    /// Returns the process working directory.
    ///
    /// @return working directory
    public Path workingDirectory() {
        return workingDirectory;
    }

    /// Returns whether the launcher environment is inherited.
    ///
    /// @return inheritance policy
    public boolean inheritEnvironment() {
        return inheritEnvironment;
    }

    /// Returns environment values to set.
    ///
    /// @return immutable environment map
    public @Unmodifiable Map<String, LaunchPlanText> environmentSet() {
        return environmentSet;
    }

    /// Returns environment names to remove.
    ///
    /// @return immutable environment-name set
    public @Unmodifiable Set<String> environmentUnset() {
        return environmentUnset;
    }

    /// Resolves the exact ordered auxiliary command tokens.
    ///
    /// @param resolver secret-slot resolver
    /// @return immutable resolved command
    public @Unmodifiable List<String> resolveCommand(Function<String, @Nullable String> resolver) {
        List<String> resolved = new ArrayList<>();
        for (LaunchPlanText text : command) {
            String token = text.resolve(resolver);
            if (token.isBlank()) {
                throw new IllegalArgumentException("Resolved auxiliary command contains a blank token");
            }
            resolved.add(token);
        }
        return List.copyOf(resolved);
    }

    /// Returns every secret slot referenced by the command or environment.
    ///
    /// @return immutable referenced slots
    public @Unmodifiable Set<String> secretSlots() {
        Set<String> slots = new LinkedHashSet<>();
        for (LaunchPlanText text : command) {
            slots.addAll(text.secretSlots());
        }
        for (LaunchPlanText text : environmentSet.values()) {
            slots.addAll(text.secretSlots());
        }
        return Collections.unmodifiableSet(slots);
    }

    /// Validates paths, environment edits, and secret references.
    ///
    /// @param availableSecretSlots launch-scoped available slots
    /// @param path root diagnostic path
    void validate(Set<String> availableSecretSlots, String path) {
        if (!workingDirectory.isAbsolute()) {
            throw new IllegalArgumentException(path + ".workingDirectory must be absolute");
        }
        for (int index = 0; index < command.size(); index++) {
            LaunchProcessPlan.validateText(command.get(index), availableSecretSlots,
                    path + ".command[" + index + "]");
        }
        LaunchProcessPlan.validateEnvironment(
                environmentSet, environmentUnset, availableSecretSlots, path);
    }

    /// Compares complete unresolved auxiliary process fields.
    ///
    /// @param other candidate value
    /// @return whether both plans are equal
    @Override
    public boolean equals(@Nullable Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof LaunchAuxiliaryProcessPlan that)) {
            return false;
        }
        return inheritEnvironment == that.inheritEnvironment
                && command.equals(that.command)
                && workingDirectory.equals(that.workingDirectory)
                && environmentSet.equals(that.environmentSet)
                && environmentUnset.equals(that.environmentUnset);
    }

    /// Returns the complete unresolved auxiliary process hash.
    ///
    /// @return plan hash
    @Override
    public int hashCode() {
        return Objects.hash(command, workingDirectory, inheritEnvironment, environmentSet, environmentUnset);
    }

    /// Returns an unresolved representation with no resolved secret values.
    ///
    /// @return unresolved auxiliary process representation
    @Override
    public String toString() {
        return "LaunchAuxiliaryProcessPlan[command=" + command + ", workingDirectory=" + workingDirectory
                + ", inheritEnvironment=" + inheritEnvironment + ", environmentSet=" + environmentSet
                + ", environmentUnset=" + environmentUnset + "]";
    }
}
