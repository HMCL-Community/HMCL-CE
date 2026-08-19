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
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// Matches plugin versions against an exact value or a conjunction of ordered comparisons.
@NotNullByDefault
public final class PluginVersionConstraint {
    /// Constraint that accepts every plugin version.
    public static final PluginVersionConstraint ANY = new PluginVersionConstraint("*", List.of());

    /// Relational clause syntax used while parsing conjunctions.
    private static final Pattern RELATIONAL_CLAUSE = Pattern.compile("(<=|>=|<|>)\\s*([^\\s,]+)");

    /// Original normalized expression exposed to callers and serialization.
    private final String expression;

    /// Ordered clauses that all must match; an empty list represents `*`.
    private final @Unmodifiable List<Clause> clauses;

    /// Creates an immutable parsed constraint.
    ///
    /// @param expression normalized expression
    /// @param clauses comparison clauses
    private PluginVersionConstraint(String expression, @Unmodifiable List<Clause> clauses) {
        this.expression = expression;
        this.clauses = List.copyOf(clauses);
    }

    /// Parses `*`, an exact version with an optional `=`, or a conjunction of range comparisons.
    ///
    /// Comparison clauses may be separated by whitespace, a comma, or a comma surrounded by whitespace.
    ///
    /// @param source constraint expression
    /// @return immutable parsed constraint
    /// @throws IllegalArgumentException if the expression does not match the supported grammar
    public static PluginVersionConstraint parse(String source) {
        String expression = source.trim();
        if (expression.isEmpty()) {
            throw new IllegalArgumentException("Plugin version constraint cannot be blank");
        }
        if (expression.equals("*")) {
            return ANY;
        }

        if (expression.charAt(0) == '=') {
            String version = expression.substring(1).trim();
            requireVersionToken(version, source);
            return new PluginVersionConstraint("=" + version, List.of(new Clause(Operator.EQUAL, version)));
        }
        if (expression.charAt(0) != '<' && expression.charAt(0) != '>') {
            requireVersionToken(expression, source);
            return new PluginVersionConstraint(expression, List.of(new Clause(Operator.EQUAL, expression)));
        }

        List<Clause> clauses = new ArrayList<>();
        Matcher matcher = RELATIONAL_CLAUSE.matcher(expression);
        int offset = 0;
        while (offset < expression.length()) {
            matcher.region(offset, expression.length());
            if (!matcher.lookingAt()) {
                throw invalidConstraint(source);
            }

            String version = matcher.group(2);
            requireVersionToken(version, source);
            clauses.add(new Clause(Operator.fromSymbol(matcher.group(1)), version));
            offset = matcher.end();
            if (offset == expression.length()) {
                break;
            }

            boolean sawSeparator = false;
            boolean sawComma = false;
            while (offset < expression.length()) {
                char character = expression.charAt(offset);
                if (Character.isWhitespace(character)) {
                    sawSeparator = true;
                    offset++;
                } else if (character == ',') {
                    if (sawComma) {
                        throw invalidConstraint(source);
                    }
                    sawSeparator = true;
                    sawComma = true;
                    offset++;
                } else {
                    break;
                }
            }
            if (!sawSeparator || offset == expression.length()) {
                throw invalidConstraint(source);
            }
        }
        return new PluginVersionConstraint(expression, clauses);
    }

    /// Returns whether the supplied version satisfies every clause in this constraint.
    ///
    /// @param version installed plugin version
    /// @return whether the version matches
    public boolean matches(String version) {
        if (!PluginVersion.isValid(version)) {
            return false;
        }
        for (Clause clause : clauses) {
            if (!clause.matches(version)) {
                return false;
            }
        }
        return true;
    }

    /// Returns the normalized expression used to create this constraint.
    ///
    /// @return constraint expression
    public String getExpression() {
        return expression;
    }

    /// Compares constraints by their normalized serialized expressions.
    ///
    /// @param other comparison target
    /// @return whether both constraints have the same expression
    @Override
    public boolean equals(@Nullable Object other) {
        return this == other
                || other instanceof PluginVersionConstraint constraint
                && expression.equals(constraint.expression);
    }

    /// Returns a hash derived from the normalized expression.
    ///
    /// @return expression hash
    @Override
    public int hashCode() {
        return Objects.hash(expression);
    }

    /// Returns the normalized expression.
    ///
    /// @return constraint expression
    @Override
    public String toString() {
        return expression;
    }

    /// Rejects empty or delimiter-containing version operands.
    ///
    /// @param version version operand
    /// @param source complete source expression used in diagnostics
    private static void requireVersionToken(String version, String source) {
        if (version.isEmpty()
                || version.chars().anyMatch(character -> Character.isWhitespace(character)
                || character == ','
                || character == '<'
                || character == '>'
                || character == '='
                || character == '*')
                || !PluginVersion.isValid(version)) {
            throw invalidConstraint(source);
        }
    }

    /// Creates a consistent parse failure for an unsupported expression.
    ///
    /// @param source rejected source expression
    /// @return parse exception
    private static IllegalArgumentException invalidConstraint(String source) {
        return new IllegalArgumentException("Invalid plugin version constraint: " + source);
    }

    /// One comparison in a conjunctive version constraint.
    @NotNullByDefault
    private static final class Clause {
        /// Comparison operation.
        private final Operator operator;

        /// Version operand.
        private final String version;

        /// Creates one immutable comparison clause.
        ///
        /// @param operator comparison operation
        /// @param version version operand
        private Clause(Operator operator, String version) {
            this.operator = operator;
            this.version = version;
        }

        /// Returns whether the candidate version satisfies this comparison.
        ///
        /// @param candidate candidate plugin version
        /// @return comparison result
        private boolean matches(String candidate) {
            return operator.matches(PluginVersion.compare(candidate, version));
        }
    }

    /// Supported comparison operators.
    @NotNullByDefault
    private enum Operator {
        /// Exact version equality.
        EQUAL,

        /// Strictly older than the operand.
        LESS_THAN,

        /// Older than or equal to the operand.
        LESS_THAN_OR_EQUAL,

        /// Strictly newer than the operand.
        GREATER_THAN,

        /// Newer than or equal to the operand.
        GREATER_THAN_OR_EQUAL;

        /// Resolves a relational symbol to its operation.
        ///
        /// @param symbol parsed comparison symbol
        /// @return comparison operation
        private static Operator fromSymbol(String symbol) {
            return switch (symbol) {
                case "<" -> LESS_THAN;
                case "<=" -> LESS_THAN_OR_EQUAL;
                case ">" -> GREATER_THAN;
                case ">=" -> GREATER_THAN_OR_EQUAL;
                default -> throw new IllegalArgumentException("Unsupported comparison operator: " + symbol);
            };
        }

        /// Applies this operation to a three-way version comparison result.
        ///
        /// @param comparison result from [PluginVersion#compare]
        /// @return whether the comparison satisfies this operation
        private boolean matches(int comparison) {
            return switch (this) {
                case EQUAL -> comparison == 0;
                case LESS_THAN -> comparison < 0;
                case LESS_THAN_OR_EQUAL -> comparison <= 0;
                case GREATER_THAN -> comparison > 0;
                case GREATER_THAN_OR_EQUAL -> comparison >= 0;
            };
        }
    }
}
