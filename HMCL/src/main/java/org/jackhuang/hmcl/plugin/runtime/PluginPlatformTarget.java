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
package org.jackhuang.hmcl.plugin.runtime;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/// Immutable operating-system and CPU-architecture identifier pair used by schema-v5 plugin manifests.
///
/// A target is written as `os` or `os-arch`, for example `windows-x64`, `linux-arm64`, or `macos` for an
/// architecture-independent package. HMCL currently uses targets to reject incompatible manifests and store
/// entries. Store artifact matrices and automatic per-platform artifact selection are not yet implemented.
@NotNullByDefault
public final class PluginPlatformTarget {
    /// Canonical operating-system identifiers accepted in manifests and store indexes.
    public static final @Unmodifiable Set<String> KNOWN_OPERATING_SYSTEMS = Set.of("windows", "linux", "macos", "freebsd");

    /// Canonical CPU-architecture identifiers accepted in manifests and store indexes.
    public static final @Unmodifiable Set<String> KNOWN_ARCHITECTURES = Set.of(
            "x86", "x64", "arm32", "arm64", "riscv64", "loongarch64", "mips64");

    private final String operatingSystem;
    private final @Nullable String architecture;

    private PluginPlatformTarget(String operatingSystem, @Nullable String architecture) {
        this.operatingSystem = operatingSystem;
        this.architecture = architecture;
    }

    /// Parses a canonical platform identifier such as `windows-x64` or `linux`.
    ///
    /// @throws IllegalArgumentException when the identifier is blank or references unknown components
    public static PluginPlatformTarget parse(String value) {
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("Blank plugin platform target");
        }
        String operatingSystem;
        @Nullable String architecture = null;
        int separator = normalized.indexOf('-');
        if (separator >= 0) {
            operatingSystem = normalized.substring(0, separator);
            architecture = normalized.substring(separator + 1);
            if (architecture.isEmpty()) {
                throw new IllegalArgumentException("Plugin platform target lacks architecture: " + value);
            }
        } else {
            operatingSystem = normalized;
        }
        if (!KNOWN_OPERATING_SYSTEMS.contains(operatingSystem)) {
            throw new IllegalArgumentException("Unknown plugin platform operating system: " + value);
        }
        if (architecture != null && !KNOWN_ARCHITECTURES.contains(architecture)) {
            throw new IllegalArgumentException("Unknown plugin platform architecture: " + value);
        }
        return new PluginPlatformTarget(operatingSystem, architecture);
    }

    /// Detects the platform the launcher is currently running on.
    public static PluginPlatformTarget current() {
        return new PluginPlatformTarget(currentOperatingSystem(), currentArchitecture());
    }

    /// Returns whether a package declaring this target can run on the given host.
    public boolean matches(PluginPlatformTarget host) {
        if (!operatingSystem.equals(host.operatingSystem)) {
            return false;
        }
        return architecture == null || architecture.equals(host.architecture);
    }

    /// Returns the canonical serialized form of this target.
    public String getId() {
        return architecture == null ? operatingSystem : operatingSystem + "-" + architecture;
    }

    /// Returns the canonical operating-system identifier.
    public String getOperatingSystem() {
        return operatingSystem;
    }

    /// Returns the canonical architecture identifier, or null when the target is architecture independent.
    public @Nullable String getArchitecture() {
        return architecture;
    }

    @Override
    public boolean equals(@Nullable Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof PluginPlatformTarget)) {
            return false;
        }
        PluginPlatformTarget target = (PluginPlatformTarget) other;
        return operatingSystem.equals(target.operatingSystem) && Objects.equals(architecture, target.architecture);
    }

    @Override
    public int hashCode() {
        return Objects.hash(operatingSystem, architecture);
    }

    @Override
    public String toString() {
        return getId();
    }

    private static String currentOperatingSystem() {
        String name = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (name.contains("win")) {
            return "windows";
        }
        if (name.contains("mac") || name.contains("darwin")) {
            return "macos";
        }
        if (name.contains("freebsd")) {
            return "freebsd";
        }
        return "linux";
    }

    @Nullable
    private static String currentArchitecture() {
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        switch (arch) {
            case "x86_64":
            case "amd64":
                return "x64";
            case "aarch64":
            case "arm64":
                return "arm64";
            case "x86":
            case "i386":
            case "i486":
            case "i586":
            case "i686":
                return "x86";
            case "arm":
            case "armv7l":
            case "armv8l":
                return "arm32";
            case "riscv64":
                return "riscv64";
            case "loongarch64":
            case "loongarch":
                return "loongarch64";
            case "mips64":
            case "mips64el":
                return "mips64";
            default:
                return KNOWN_ARCHITECTURES.contains(arch) ? arch : null;
        }
    }
}
