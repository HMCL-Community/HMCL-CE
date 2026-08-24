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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/// Describes every mutable process-launch field as one immutable, versioned value object.
@NotNullByDefault
public final class LaunchProcessPlan {
    /// Plan generation implemented by this launcher.
    public static final int CURRENT_PLAN_VERSION = 1;

    /// Portable environment variable syntax accepted by the Hook contract.
    private static final Pattern ENVIRONMENT_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    /// Launcher visibility values supported by the current HMCL UI integration.
    private static final @Unmodifiable Set<String> LAUNCHER_VISIBILITIES = Set.of(
            "close", "hide", "keep", "hide-and-reopen");

    /// Process-plan contract generation.
    private final int planVersion;

    /// Direct execution or script rendering mode.
    private final LaunchExecutionMode executionMode;

    /// Authoritative process command.
    private final LaunchCommandPlan command;

    /// Main process working directory.
    private final Path workingDirectory;

    /// Whether the main process inherits the launcher environment.
    private final boolean inheritEnvironment;

    /// Main-process environment values to set.
    private final @Unmodifiable Map<String, LaunchPlanText> environmentSet;

    /// Main-process environment names to remove.
    private final @Unmodifiable Set<String> environmentUnset;

    /// Optional process run before the main process.
    private final @Nullable LaunchAuxiliaryProcessPlan preLaunch;

    /// Optional process run after the main process exits.
    private final @Nullable LaunchAuxiliaryProcessPlan postExit;

    /// Stable lower-case launcher visibility identifier.
    private final String launcherVisibility;

    /// Whether direct execution inherits process IO.
    private final boolean inheritIo;

    /// Whether output and exit monitor threads are daemon threads.
    private final boolean daemonMonitors;

    /// Creates one complete unresolved launch process plan.
    ///
    /// @param planVersion process-plan generation
    /// @param executionMode direct or script mode
    /// @param command authoritative command
    /// @param workingDirectory main process working directory
    /// @param inheritEnvironment whether to inherit the launcher environment
    /// @param environmentSet environment values to set
    /// @param environmentUnset environment names to remove
    /// @param preLaunch optional pre-launch process
    /// @param postExit optional post-exit process
    /// @param launcherVisibility launcher visibility identifier
    /// @param inheritIo whether direct execution inherits process IO
    /// @param daemonMonitors whether monitor threads are daemon threads
    public LaunchProcessPlan(
            int planVersion,
            LaunchExecutionMode executionMode,
            LaunchCommandPlan command,
            Path workingDirectory,
            boolean inheritEnvironment,
            Map<String, LaunchPlanText> environmentSet,
            Set<String> environmentUnset,
            @Nullable LaunchAuxiliaryProcessPlan preLaunch,
            @Nullable LaunchAuxiliaryProcessPlan postExit,
            String launcherVisibility,
            boolean inheritIo,
            boolean daemonMonitors
    ) {
        this.planVersion = planVersion;
        this.executionMode = Objects.requireNonNull(executionMode, "executionMode");
        this.command = Objects.requireNonNull(command, "command");
        this.workingDirectory = Objects.requireNonNull(workingDirectory, "workingDirectory");
        this.inheritEnvironment = inheritEnvironment;
        this.environmentSet = copyEnvironment(environmentSet);
        this.environmentUnset = copyEnvironmentUnset(environmentUnset);
        this.preLaunch = preLaunch;
        this.postExit = postExit;
        this.launcherVisibility = Objects.requireNonNull(launcherVisibility, "launcherVisibility");
        this.inheritIo = inheritIo;
        this.daemonMonitors = daemonMonitors;
    }

    /// Returns the process-plan generation.
    ///
    /// @return plan generation
    public int planVersion() {
        return planVersion;
    }

    /// Returns direct or script execution mode.
    ///
    /// @return execution mode
    public LaunchExecutionMode executionMode() {
        return executionMode;
    }

    /// Returns the authoritative process command.
    ///
    /// @return immutable command
    public LaunchCommandPlan command() {
        return command;
    }

    /// Returns the main process working directory.
    ///
    /// @return working directory
    public Path workingDirectory() {
        return workingDirectory;
    }

    /// Returns whether the main process inherits the launcher environment.
    ///
    /// @return environment inheritance policy
    public boolean inheritEnvironment() {
        return inheritEnvironment;
    }

    /// Returns main-process environment values to set.
    ///
    /// @return immutable environment map
    public @Unmodifiable Map<String, LaunchPlanText> environmentSet() {
        return environmentSet;
    }

    /// Returns main-process environment names to remove.
    ///
    /// @return immutable environment-name set
    public @Unmodifiable Set<String> environmentUnset() {
        return environmentUnset;
    }

    /// Returns the optional pre-launch process.
    ///
    /// @return pre-launch process or `null`
    public @Nullable LaunchAuxiliaryProcessPlan preLaunch() {
        return preLaunch;
    }

    /// Returns the optional post-exit process.
    ///
    /// @return post-exit process or `null`
    public @Nullable LaunchAuxiliaryProcessPlan postExit() {
        return postExit;
    }

    /// Returns the stable launcher visibility identifier.
    ///
    /// @return launcher visibility
    public String launcherVisibility() {
        return launcherVisibility;
    }

    /// Returns whether direct execution inherits process IO.
    ///
    /// @return IO inheritance policy
    public boolean inheritIo() {
        return inheritIo;
    }

    /// Returns whether process monitor threads are daemon threads.
    ///
    /// @return monitor daemon policy
    public boolean daemonMonitors() {
        return daemonMonitors;
    }

    /// Returns a copy with a replacement authoritative command.
    ///
    /// @param replacement replacement command
    /// @return updated immutable plan
    public LaunchProcessPlan withCommand(LaunchCommandPlan replacement) {
        return copy(replacement, workingDirectory, inheritEnvironment, environmentSet, environmentUnset,
                preLaunch, postExit, launcherVisibility, inheritIo, daemonMonitors);
    }

    /// Returns a copy with a replacement main working directory.
    ///
    /// @param replacement replacement working directory
    /// @return updated immutable plan
    public LaunchProcessPlan withWorkingDirectory(Path replacement) {
        return copy(command, replacement, inheritEnvironment, environmentSet, environmentUnset,
                preLaunch, postExit, launcherVisibility, inheritIo, daemonMonitors);
    }

    /// Returns a copy with replacement main-process environment policy and edits.
    ///
    /// @param inherit whether to inherit the launcher environment
    /// @param set environment values to set
    /// @param unset environment names to remove
    /// @return updated immutable plan
    public LaunchProcessPlan withEnvironment(
            boolean inherit,
            Map<String, LaunchPlanText> set,
            Set<String> unset
    ) {
        return copy(command, workingDirectory, inherit, set, unset,
                preLaunch, postExit, launcherVisibility, inheritIo, daemonMonitors);
    }

    /// Returns a copy with a replacement optional pre-launch process.
    ///
    /// @param replacement replacement process or `null`
    /// @return updated immutable plan
    public LaunchProcessPlan withPreLaunch(@Nullable LaunchAuxiliaryProcessPlan replacement) {
        return copy(command, workingDirectory, inheritEnvironment, environmentSet, environmentUnset,
                replacement, postExit, launcherVisibility, inheritIo, daemonMonitors);
    }

    /// Returns a copy with a replacement optional post-exit process.
    ///
    /// @param replacement replacement process or `null`
    /// @return updated immutable plan
    public LaunchProcessPlan withPostExit(@Nullable LaunchAuxiliaryProcessPlan replacement) {
        return copy(command, workingDirectory, inheritEnvironment, environmentSet, environmentUnset,
                preLaunch, replacement, launcherVisibility, inheritIo, daemonMonitors);
    }

    /// Returns a copy with replacement launcher visibility and process-monitor behavior.
    ///
    /// @param visibility launcher visibility identifier
    /// @param inheritProcessIo whether direct execution inherits process IO
    /// @param daemonProcessMonitors whether monitor threads are daemon threads
    /// @return updated immutable plan
    public LaunchProcessPlan withProcessBehavior(
            String visibility,
            boolean inheritProcessIo,
            boolean daemonProcessMonitors
    ) {
        return copy(command, workingDirectory, inheritEnvironment, environmentSet, environmentUnset,
                preLaunch, postExit, visibility, inheritProcessIo, daemonProcessMonitors);
    }

    /// Validates the complete unresolved plan against launch-scoped available secrets.
    ///
    /// @param availableSecretSlots secret slots owned by the launch preparation
    public void validate(Set<String> availableSecretSlots) {
        Set<String> available = Set.copyOf(Objects.requireNonNull(availableSecretSlots, "availableSecretSlots"));
        if (planVersion != CURRENT_PLAN_VERSION) {
            throw new IllegalArgumentException("planVersion is unsupported: " + planVersion);
        }
        if (!workingDirectory.isAbsolute()) {
            throw new IllegalArgumentException("workingDirectory must be absolute");
        }
        if (!LAUNCHER_VISIBILITIES.contains(launcherVisibility)) {
            throw new IllegalArgumentException("launcherVisibility is unsupported: " + launcherVisibility);
        }
        command.validate(available, "command");
        validateEnvironment(environmentSet, environmentUnset, available, "environment");
        if (preLaunch != null) {
            preLaunch.validate(available, "preLaunch");
        }
        if (postExit != null) {
            postExit.validate(available, "postExit");
        }
    }

    /// Creates a copy while preserving plan generation and execution mode.
    ///
    /// @param replacementCommand replacement command
    /// @param replacementDirectory replacement working directory
    /// @param replacementInheritEnvironment replacement inheritance policy
    /// @param replacementEnvironmentSet replacement set operations
    /// @param replacementEnvironmentUnset replacement unset operations
    /// @param replacementPreLaunch replacement pre-launch process
    /// @param replacementPostExit replacement post-exit process
    /// @param replacementVisibility replacement visibility
    /// @param replacementInheritIo replacement IO policy
    /// @param replacementDaemonMonitors replacement monitor policy
    /// @return updated immutable plan
    private LaunchProcessPlan copy(
            LaunchCommandPlan replacementCommand,
            Path replacementDirectory,
            boolean replacementInheritEnvironment,
            Map<String, LaunchPlanText> replacementEnvironmentSet,
            Set<String> replacementEnvironmentUnset,
            @Nullable LaunchAuxiliaryProcessPlan replacementPreLaunch,
            @Nullable LaunchAuxiliaryProcessPlan replacementPostExit,
            String replacementVisibility,
            boolean replacementInheritIo,
            boolean replacementDaemonMonitors
    ) {
        return new LaunchProcessPlan(planVersion, executionMode, replacementCommand, replacementDirectory,
                replacementInheritEnvironment, replacementEnvironmentSet, replacementEnvironmentUnset,
                replacementPreLaunch, replacementPostExit, replacementVisibility,
                replacementInheritIo, replacementDaemonMonitors);
    }

    /// Copies environment set operations in stable insertion order.
    ///
    /// @param source source environment values
    /// @return immutable copied environment map
    static @Unmodifiable Map<String, LaunchPlanText> copyEnvironment(Map<String, LaunchPlanText> source) {
        Objects.requireNonNull(source, "environmentSet");
        Map<String, LaunchPlanText> copy = new LinkedHashMap<>();
        for (Map.Entry<String, LaunchPlanText> entry : source.entrySet()) {
            copy.put(Objects.requireNonNull(entry.getKey(), "Environment name"),
                    Objects.requireNonNull(entry.getValue(), "Environment value"));
        }
        return Collections.unmodifiableMap(copy);
    }

    /// Copies environment unset operations in stable insertion order.
    ///
    /// @param source source environment names
    /// @return immutable copied name set
    static @Unmodifiable Set<String> copyEnvironmentUnset(Set<String> source) {
        Objects.requireNonNull(source, "environmentUnset");
        Set<String> copy = new LinkedHashSet<>();
        for (String name : source) {
            copy.add(Objects.requireNonNull(name, "Environment name"));
        }
        return Collections.unmodifiableSet(copy);
    }

    /// Validates one environment edit set and all referenced secrets.
    ///
    /// @param environmentSet environment values to set
    /// @param environmentUnset environment names to remove
    /// @param availableSecretSlots available secret slots
    /// @param path root diagnostic path
    static void validateEnvironment(
            Map<String, LaunchPlanText> environmentSet,
            Set<String> environmentUnset,
            Set<String> availableSecretSlots,
            String path
    ) {
        for (Map.Entry<String, LaunchPlanText> entry : environmentSet.entrySet()) {
            validateEnvironmentName(entry.getKey(), path + "Set");
            validateText(entry.getValue(), availableSecretSlots, path + "Set." + entry.getKey());
        }
        for (String name : environmentUnset) {
            validateEnvironmentName(name, path + "Unset");
            if (environmentSet.containsKey(name)) {
                throw new IllegalArgumentException(path + "Unset conflicts with " + path + "Set." + name);
            }
        }
    }

    /// Validates one environment name with a path-specific error.
    ///
    /// @param name environment name
    /// @param path diagnostic path
    private static void validateEnvironmentName(String name, String path) {
        if (!ENVIRONMENT_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException(path + " contains invalid environment name: " + name);
        }
    }

    /// Validates every secret reference in one unresolved text value.
    ///
    /// @param text unresolved text
    /// @param availableSecretSlots available secret slots
    /// @param path diagnostic path
    static void validateText(LaunchPlanText text, Set<String> availableSecretSlots, String path) {
        for (String slot : text.secretSlots()) {
            if (!availableSecretSlots.contains(slot)) {
                throw new IllegalArgumentException(path + " references unknown secret slot " + slot);
            }
        }
    }

    /// Compares every unresolved launch process field.
    ///
    /// @param other candidate value
    /// @return whether both plans are equal
    @Override
    public boolean equals(@Nullable Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof LaunchProcessPlan that)) {
            return false;
        }
        return planVersion == that.planVersion
                && inheritEnvironment == that.inheritEnvironment
                && inheritIo == that.inheritIo
                && daemonMonitors == that.daemonMonitors
                && executionMode == that.executionMode
                && command.equals(that.command)
                && workingDirectory.equals(that.workingDirectory)
                && environmentSet.equals(that.environmentSet)
                && environmentUnset.equals(that.environmentUnset)
                && Objects.equals(preLaunch, that.preLaunch)
                && Objects.equals(postExit, that.postExit)
                && launcherVisibility.equals(that.launcherVisibility);
    }

    /// Returns the complete unresolved plan hash.
    ///
    /// @return plan hash
    @Override
    public int hashCode() {
        return Objects.hash(planVersion, executionMode, command, workingDirectory, inheritEnvironment,
                environmentSet, environmentUnset, preLaunch, postExit, launcherVisibility, inheritIo, daemonMonitors);
    }

    /// Returns an unresolved plan representation that contains no resolved secret values.
    ///
    /// @return unresolved plan representation
    @Override
    public String toString() {
        return "LaunchProcessPlan[planVersion=" + planVersion + ", executionMode=" + executionMode
                + ", command=" + command + ", workingDirectory=" + workingDirectory
                + ", inheritEnvironment=" + inheritEnvironment + ", environmentSet=" + environmentSet
                + ", environmentUnset=" + environmentUnset + ", preLaunch=" + preLaunch
                + ", postExit=" + postExit + ", launcherVisibility=" + launcherVisibility
                + ", inheritIo=" + inheritIo + ", daemonMonitors=" + daemonMonitors + "]";
    }
}
