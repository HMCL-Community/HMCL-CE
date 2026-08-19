/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2020  huangyuhui <huanghongxun2008@126.com> and contributors
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

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/// Compares launcher and plugin versions using semantic-version-compatible numeric and qualifier ordering.
@NotNullByDefault
public final class PluginVersion {
    /// Prevents construction of the version utility.
    private PluginVersion() {
    }

    /// Compares two version strings.
    ///
    /// Build metadata is ignored, missing numeric components are zero, and a release sorts after its prerelease.
    ///
    /// @param left first version
    /// @param right second version
    /// @return a negative value, zero, or a positive value when the first version is older, equal, or newer
    /// @throws IllegalArgumentException if either version is malformed or has no numeric release component
    public static int compare(String left, String right) {
        ParsedVersion leftVersion = ParsedVersion.parse(left);
        ParsedVersion rightVersion = ParsedVersion.parse(right);

        int coreLength = Math.max(leftVersion.core.size(), rightVersion.core.size());
        for (int index = 0; index < coreLength; index++) {
            BigInteger leftPart = index < leftVersion.core.size() ? leftVersion.core.get(index) : BigInteger.ZERO;
            BigInteger rightPart = index < rightVersion.core.size() ? rightVersion.core.get(index) : BigInteger.ZERO;
            int comparison = leftPart.compareTo(rightPart);
            if (comparison != 0) {
                return comparison;
            }
        }

        if (leftVersion.prerelease.isEmpty() && rightVersion.prerelease.isEmpty()) {
            return 0;
        }
        if (leftVersion.prerelease.isEmpty()) {
            return 1;
        }
        if (rightVersion.prerelease.isEmpty()) {
            return -1;
        }

        int prereleaseLength = Math.max(leftVersion.prerelease.size(), rightVersion.prerelease.size());
        for (int index = 0; index < prereleaseLength; index++) {
            if (index >= leftVersion.prerelease.size()) {
                return -1;
            }
            if (index >= rightVersion.prerelease.size()) {
                return 1;
            }

            String leftPart = leftVersion.prerelease.get(index);
            String rightPart = rightVersion.prerelease.get(index);
            int comparison = comparePrereleasePart(leftPart, rightPart);
            if (comparison != 0) {
                return comparison;
            }
        }
        return 0;
    }

    /// Returns whether a version contains a numeric release component and can be compared.
    ///
    /// @param source candidate version
    /// @return whether the version is valid for comparison
    static boolean isValid(String source) {
        try {
            ParsedVersion.parse(source);
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    /// Compares one prerelease identifier using numeric ordering and common qualifier precedence.
    ///
    /// @param left first identifier
    /// @param right second identifier
    /// @return identifier ordering
    private static int comparePrereleasePart(String left, String right) {
        boolean leftNumeric = left.chars().allMatch(Character::isDigit);
        boolean rightNumeric = right.chars().allMatch(Character::isDigit);
        if (leftNumeric && rightNumeric) {
            return new BigInteger(left).compareTo(new BigInteger(right));
        }
        if (leftNumeric != rightNumeric) {
            return leftNumeric ? -1 : 1;
        }

        int leftRank = qualifierRank(left);
        int rightRank = qualifierRank(right);
        if (leftRank != rightRank) {
            return Integer.compare(leftRank, rightRank);
        }
        return left.compareToIgnoreCase(right);
    }

    /// Returns the conventional rank of a prerelease qualifier.
    ///
    /// @param qualifier normalized qualifier
    /// @return qualifier rank
    private static int qualifierRank(String qualifier) {
        return switch (qualifier.toLowerCase(Locale.ROOT)) {
            case "snapshot", "dev" -> -50;
            case "alpha", "a" -> -40;
            case "beta", "b" -> -30;
            case "milestone", "m" -> -20;
            case "rc", "cr" -> -10;
            case "final", "ga", "release" -> 0;
            case "sp" -> 10;
            default -> -5;
        };
    }

    /// Parsed numeric core and prerelease identifiers for one version string.
    @NotNullByDefault
    private static final class ParsedVersion {
        /// Numeric release components.
        private final @Unmodifiable List<BigInteger> core;

        /// Normalized prerelease identifiers.
        private final @Unmodifiable List<String> prerelease;

        /// Creates an immutable parsed version.
        ///
        /// @param core numeric release components
        /// @param prerelease prerelease identifiers
        private ParsedVersion(@Unmodifiable List<BigInteger> core, @Unmodifiable List<String> prerelease) {
            this.core = List.copyOf(core);
            this.prerelease = List.copyOf(prerelease);
        }

        /// Parses a permissive launcher or plugin version string.
        ///
        /// @param source source version
        /// @return parsed version
        /// @throws IllegalArgumentException if the version is malformed or has no numeric release component
        private static ParsedVersion parse(String source) {
            String normalized = source.trim();
            if (normalized.chars().anyMatch(character -> Character.isWhitespace(character)
                    || character == ','
                    || character == '<'
                    || character == '>'
                    || character == '='
                    || character == '*')) {
                throw invalidVersion(source);
            }
            if (normalized.startsWith("v") || normalized.startsWith("V")) {
                normalized = normalized.substring(1);
            }
            int buildMetadata = normalized.indexOf('+');
            if (buildMetadata >= 0) {
                if (buildMetadata == normalized.length() - 1
                        || normalized.indexOf('+', buildMetadata + 1) >= 0) {
                    throw invalidVersion(source);
                }
                normalized = normalized.substring(0, buildMetadata);
            }

            int prereleaseSeparator = normalized.indexOf('-');
            String release = prereleaseSeparator >= 0
                    ? normalized.substring(0, prereleaseSeparator)
                    : normalized;
            List<BigInteger> core = new ArrayList<>();
            List<String> prerelease = new ArrayList<>();
            boolean readingPrerelease = false;
            for (String part : release.split("\\.", -1)) {
                if (part.isEmpty()) {
                    throw invalidVersion(source);
                }
                if (!readingPrerelease && part.chars().allMatch(Character::isDigit)) {
                    core.add(new BigInteger(part));
                } else {
                    readingPrerelease = true;
                    prerelease.add(part.toLowerCase(Locale.ROOT));
                }
            }
            if (core.isEmpty()) {
                throw invalidVersion(source);
            }

            if (prereleaseSeparator >= 0) {
                String explicitPrerelease = normalized.substring(prereleaseSeparator + 1);
                for (String part : explicitPrerelease.split("[.-]", -1)) {
                    if (part.isEmpty()) {
                        throw invalidVersion(source);
                    }
                    prerelease.add(part.toLowerCase(Locale.ROOT));
                }
            }
            return new ParsedVersion(core, prerelease);
        }

        /// Creates a consistent parse failure for a malformed version.
        ///
        /// @param source rejected source version
        /// @return parse exception
        private static IllegalArgumentException invalidVersion(String source) {
            return new IllegalArgumentException("Invalid plugin version: " + source);
        }
    }
}
