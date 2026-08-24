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

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

/// Stores immutable process text as literal and opaque secret-reference segments.
@NotNullByDefault
public final class LaunchPlanText {
    /// Canonical syntax shared by launch-scoped secret slots.
    private static final Pattern SECRET_SLOT = Pattern.compile("[a-z][a-z0-9]*(?:-[a-z0-9]+)*");

    /// Immutable ordered text segments.
    private final @Unmodifiable List<Segment> segments;

    /// Creates one text value from already validated copied segments.
    ///
    /// @param segments ordered text segments
    private LaunchPlanText(List<Segment> segments) {
        this.segments = List.copyOf(Objects.requireNonNull(segments, "segments"));
    }

    /// Creates text containing only one literal segment.
    ///
    /// @param value literal text
    /// @return immutable literal text
    public static LaunchPlanText literal(String value) {
        return new LaunchPlanText(List.of(new LiteralSegment(value)));
    }

    /// Creates text from an ordered copied segment list.
    ///
    /// @param segments literal and secret segments
    /// @return immutable template text
    public static LaunchPlanText template(List<Segment> segments) {
        return new LaunchPlanText(segments);
    }

    /// Returns immutable ordered text segments.
    ///
    /// @return copied segment list
    public @Unmodifiable List<Segment> segments() {
        return segments;
    }

    /// Returns every referenced secret slot in encounter order.
    ///
    /// @return immutable secret-slot set
    public @Unmodifiable Set<String> secretSlots() {
        Set<String> slots = new LinkedHashSet<>();
        for (Segment segment : segments) {
            if (segment instanceof SecretSegment secret) {
                slots.add(secret.slot());
            }
        }
        return Collections.unmodifiableSet(slots);
    }

    /// Resolves every secret segment at the final execution or rendering boundary.
    ///
    /// @param resolver secret-slot resolver
    /// @return resolved text
    /// @throws IllegalArgumentException if a referenced slot cannot be resolved
    public String resolve(Function<String, @Nullable String> resolver) {
        Objects.requireNonNull(resolver, "resolver");
        StringBuilder result = new StringBuilder();
        for (Segment segment : segments) {
            if (segment instanceof LiteralSegment literal) {
                result.append(literal.value());
            } else if (segment instanceof SecretSegment secret) {
                @Nullable String value = resolver.apply(secret.slot());
                if (value == null) {
                    throw new IllegalArgumentException("Unknown launch secret slot: " + secret.slot());
                }
                if (value.indexOf('\0') >= 0) {
                    throw new IllegalArgumentException("Resolved launch secret contains NUL: " + secret.slot());
                }
                result.append(value);
            }
        }
        return result.toString();
    }

    /// Returns whether this text consists only of whitespace literal segments.
    ///
    /// Secret references are treated as potentially non-blank and validated after resolution.
    ///
    /// @return whether the known text is blank
    boolean isDefinitelyBlank() {
        StringBuilder literalValue = new StringBuilder();
        for (Segment segment : segments) {
            if (segment instanceof SecretSegment) {
                return false;
            }
            literalValue.append(((LiteralSegment) segment).value());
        }
        return literalValue.toString().isBlank();
    }

    /// Compares text segment structure without resolving secrets.
    ///
    /// @param other candidate value
    /// @return whether both values contain equal segments
    @Override
    public boolean equals(@Nullable Object other) {
        return this == other || other instanceof LaunchPlanText that && segments.equals(that.segments);
    }

    /// Returns the structural segment hash.
    ///
    /// @return segment hash
    @Override
    public int hashCode() {
        return segments.hashCode();
    }

    /// Returns an unresolved representation that contains slot names but no resolved secret values.
    ///
    /// @return unresolved text representation
    @Override
    public String toString() {
        return "LaunchPlanText" + segments;
    }

    /// Marks one permitted immutable text segment kind.
    @NotNullByDefault
    public sealed interface Segment permits LiteralSegment, SecretSegment {
    }

    /// Stores one ordinary literal text segment.
    ///
    /// @param value literal text
    @NotNullByDefault
    public record LiteralSegment(String value) implements Segment {
        /// Rejects null and NUL-containing literals before they enter a plan.
        public LiteralSegment {
            Objects.requireNonNull(value, "value");
            if (value.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("Launch plan literal contains NUL");
            }
        }
    }

    /// Stores one opaque canonical secret-slot reference.
    ///
    /// @param slot canonical slot name
    @NotNullByDefault
    public record SecretSegment(String slot) implements Segment {
        /// Rejects null and non-canonical slot names.
        public SecretSegment {
            Objects.requireNonNull(slot, "slot");
            if (!SECRET_SLOT.matcher(slot).matches()) {
                throw new IllegalArgumentException("Invalid launch secret slot: " + slot);
            }
        }
    }
}
