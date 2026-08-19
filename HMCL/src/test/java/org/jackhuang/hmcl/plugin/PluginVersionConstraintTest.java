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

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies the supported plugin dependency version-constraint grammar and matching behavior.
@NotNullByDefault
public final class PluginVersionConstraintTest {
    /// Accepts every version for the wildcard constraint.
    @Test
    public void matchWildcard() {
        assertSame(PluginVersionConstraint.ANY, PluginVersionConstraint.parse("*"));
        assertTrue(PluginVersionConstraint.ANY.matches("0.0.1-alpha"));
    }

    /// Rejects malformed candidates for wildcard, exact, and relational constraints.
    ///
    /// @param invalidVersion malformed or nonnumeric candidate
    @ParameterizedTest
    @ValueSource(strings = {
            "", " ", "garbage", ".", ".1", "1.", "1..2", "+", "v", "V+metadata", "-1",
            "1 rc", "1,<2", "1=2", "1*2", "1>2"
    })
    public void rejectInvalidCandidates(String invalidVersion) {
        assertFalse(PluginVersionConstraint.ANY.matches(invalidVersion));
        assertFalse(PluginVersionConstraint.parse("1.0").matches(invalidVersion));
        assertFalse(PluginVersionConstraint.parse(">=1.0").matches(invalidVersion));
    }

    /// Rejects malformed prerelease and build syntax for every constraint shape.
    ///
    /// @param invalidVersion malformed candidate version
    @ParameterizedTest
    @ValueSource(strings = {
            "1-", "1--rc", "1-rc.", "1-rc..1",
            "1+", "1++build", "1+build+other"
    })
    public void rejectMalformedCandidates(String invalidVersion) {
        assertFalse(PluginVersionConstraint.ANY.matches(invalidVersion));
        assertFalse(PluginVersionConstraint.parse("1.0").matches(invalidVersion));
        assertFalse(PluginVersionConstraint.parse(">=1.0").matches(invalidVersion));
    }

    /// Treats bare and equals-prefixed single versions as exact constraints.
    @Test
    public void matchExactVersions() {
        assertTrue(PluginVersionConstraint.parse("1.2.0").matches("1.2"));
        assertTrue(PluginVersionConstraint.parse("=1.2.0").matches("1.2.0+build.4"));
        assertFalse(PluginVersionConstraint.parse("=1.2.0").matches("1.2.1"));
    }

    /// Applies whitespace- and comma-separated relational clauses as a conjunction.
    @Test
    public void matchRelationalConjunctions() {
        PluginVersionConstraint constraint = PluginVersionConstraint.parse(">=1.2.0, <2.0.0");
        assertTrue(constraint.matches("1.9.4"));
        assertFalse(constraint.matches("1.1.9"));
        assertFalse(constraint.matches("2.0.0"));
        assertTrue(PluginVersionConstraint.parse("> 1.0 <= 1.1").matches("1.0.5"));
    }

    /// Rejects blank, incomplete, disjunctive, wildcard-combined, and malformed conjunctions.
    @Test
    public void rejectInvalidSyntax() {
        assertThrows(IllegalArgumentException.class, () -> PluginVersionConstraint.parse(""));
        assertThrows(IllegalArgumentException.class, () -> PluginVersionConstraint.parse(">="));
        assertThrows(IllegalArgumentException.class, () -> PluginVersionConstraint.parse(">=1 || <2"));
        assertThrows(IllegalArgumentException.class, () -> PluginVersionConstraint.parse(">=1, *"));
        assertThrows(IllegalArgumentException.class, () -> PluginVersionConstraint.parse(">=1,,<2"));
    }

    /// Rejects exact and relational operands that contain no numeric release component.
    ///
    /// @param invalidConstraint constraint with an invalid version operand
    @ParameterizedTest
    @ValueSource(strings = {
            "garbage", ".", ".1", "1.", "1..2", "+", "v",
            "=garbage", "=.", "=.1", "=1.", "=1..2", "=+", "=v",
            ">=garbage", ">=.", ">=.1", ">=1.", ">=1..2", ">=+", ">=v"
    })
    public void rejectOperandsWithoutNumericCore(String invalidConstraint) {
        assertThrows(IllegalArgumentException.class, () -> PluginVersionConstraint.parse(invalidConstraint));
    }

    /// Rejects malformed prerelease and build syntax in exact and relational operands.
    ///
    /// @param invalidVersion malformed operand version
    @ParameterizedTest
    @ValueSource(strings = {
            "1-", "1--rc", "1-rc.", "1-rc..1",
            "1+", "1++build", "1+build+other"
    })
    public void rejectMalformedOperands(String invalidVersion) {
        assertThrows(IllegalArgumentException.class, () -> PluginVersionConstraint.parse(invalidVersion));
        assertThrows(IllegalArgumentException.class, () -> PluginVersionConstraint.parse("=" + invalidVersion));
        assertThrows(IllegalArgumentException.class, () -> PluginVersionConstraint.parse(">=" + invalidVersion));
    }
}
