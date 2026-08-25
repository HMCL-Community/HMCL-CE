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
package org.jackhuang.hmcl.plugin.bridge;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies the closed, bounded, and immutable Runtime Bridge value vocabulary.
@NotNullByDefault
class BridgeValueTest {
    /// Covers every scalar tag and the singleton null representation.
    @Test
    void representsNullAndFixedWidthScalars() {
        assertSame(BridgeValue.nullValue(), BridgeValue.nullValue());
        assertEquals(BridgeValue.Tag.NULL, BridgeValue.nullValue().tag());
        assertEquals(new BridgeValue.BooleanValue(true), BridgeValue.bool(true));
        assertEquals(new BridgeValue.IntegerValue(Long.MIN_VALUE), BridgeValue.integer(Long.MIN_VALUE));
        assertEquals(new BridgeValue.FloatValue(0.25D), BridgeValue.floating(0.25D));
        assertEquals(new BridgeValue.StringValue("runtime-neutral"), BridgeValue.string("runtime-neutral"));
    }

    /// Rejects non-finite floating-point values because they have no portable Bridge encoding.
    @Test
    void rejectsNonFiniteFloatingPointValues() {
        assertThrows(IllegalArgumentException.class, () -> BridgeValue.floating(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> BridgeValue.floating(Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> new BridgeValue.FloatValue(Double.NEGATIVE_INFINITY));
    }

    /// Rejects malformed UTF-16 strings and map keys instead of silently replacing isolated surrogates.
    @Test
    void rejectsMalformedUnicodeText() {
        assertThrows(IllegalArgumentException.class, () -> BridgeValue.string("\uD800"));
        assertThrows(IllegalArgumentException.class, () -> BridgeValue.string("\uDC00"));
        assertThrows(IllegalArgumentException.class,
                () -> BridgeValue.map(Map.of("broken-\uD800", BridgeValue.nullValue())));
        assertThrows(IllegalArgumentException.class,
                () -> BridgeValue.map(Map.of("broken-\uDC00", BridgeValue.nullValue())));
    }

    /// Accepts valid surrogate pairs and counts their actual four-byte UTF-8 encoding against limits.
    @Test
    void acceptsPairedSurrogatesWithinUtf8Budget() {
        String emoji = "\uD83D\uDE00";
        String exactBudget = emoji.repeat(BridgeValue.MAX_STRING_UTF8_LENGTH / 4);

        assertEquals(new BridgeValue.StringValue(emoji), BridgeValue.string(emoji));
        assertEquals(exactBudget, ((BridgeValue.StringValue) BridgeValue.string(exactBudget)).value());
        assertThrows(IllegalArgumentException.class, () -> BridgeValue.string(exactBudget + emoji));
    }

    /// Copies byte arrays on input and output so payload code cannot mutate a published value.
    @Test
    void defensivelyCopiesByteArrays() {
        byte[] source = {1, 2, 3};
        BridgeValue.BytesValue value = (BridgeValue.BytesValue) BridgeValue.bytes(source);

        source[0] = 9;
        byte[] firstRead = value.value();
        firstRead[1] = 9;

        assertArrayEquals(new byte[]{1, 2, 3}, value.value());
        assertNotSame(firstRead, value.value());
        assertEquals(BridgeValue.bytes(new byte[]{1, 2, 3}), value);
    }

    /// Copies arrays and maps while retaining deterministic insertion order for maps.
    @Test
    void defensivelyCopiesStructuredValues() {
        List<BridgeValue> sourceArray = new ArrayList<>(List.of(BridgeValue.integer(1)));
        Map<String, BridgeValue> sourceMap = new LinkedHashMap<>();
        sourceMap.put("first", BridgeValue.array(sourceArray));
        sourceMap.put("second", BridgeValue.bool(true));

        BridgeValue.MapValue value = (BridgeValue.MapValue) BridgeValue.map(sourceMap);
        sourceArray.add(BridgeValue.integer(2));
        sourceMap.clear();

        assertEquals(List.of("first", "second"), new ArrayList<>(value.values().keySet()));
        BridgeValue.ArrayValue nested = (BridgeValue.ArrayValue) value.values().get("first");
        assertEquals(List.of(BridgeValue.integer(1)), nested.values());
        assertThrows(UnsupportedOperationException.class,
                () -> nested.values().add(BridgeValue.integer(3)));
        assertThrows(UnsupportedOperationException.class,
                () -> value.values().put("third", BridgeValue.nullValue()));
    }

    /// Allows opaque handles and redacted errors without admitting their referenced Java objects into the value tree.
    @Test
    void representsOpaqueHandlesAndErrors() {
        BridgeHandle handle = new BridgeHandle(5L, 3L, "launcher.profile");
        BridgeError error = BridgeError.of(BridgeError.Category.INVALID_ARGUMENT);

        assertEquals(handle, ((BridgeValue.HandleValue) BridgeValue.handle(handle)).value());
        assertSame(error, ((BridgeValue.ErrorValue) BridgeValue.error(error)).value());
    }

    /// Compares errors structurally by portable category through direct and nested Bridge values.
    @Test
    void comparesErrorValuesStructurally() {
        BridgeValue first = BridgeValue.error(BridgeError.of(BridgeError.Category.CALLBACK_FAILED));
        BridgeValue sameCategory = BridgeValue.error(BridgeError.of(BridgeError.Category.CALLBACK_FAILED));
        BridgeValue differentCategory = BridgeValue.error(BridgeError.of(BridgeError.Category.CANCELLED));

        assertEquals(first, sameCategory);
        assertEquals(first.hashCode(), sameCategory.hashCode());
        assertNotEquals(first, differentCategory);
        assertEquals(
                BridgeValue.array(List.of(first)),
                BridgeValue.array(List.of(sameCategory)));
        assertEquals(
                BridgeValue.map(Map.of("error", first)),
                BridgeValue.map(Map.of("error", sameCategory)));
    }

    /// Rejects null keys, null values, and arbitrary objects smuggled through erased collection types.
    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test
    void rejectsInvalidStructuredNesting() {
        Map<String, BridgeValue> nullValue = new LinkedHashMap<>();
        nullValue.put("invalid", null);
        assertThrows(NullPointerException.class, () -> BridgeValue.map(nullValue));

        Map<String, BridgeValue> nullKey = new LinkedHashMap<>();
        nullKey.put(null, BridgeValue.nullValue());
        assertThrows(NullPointerException.class, () -> BridgeValue.map(nullKey));

        List<BridgeValue> arbitrary = (List) List.of(new Object());
        assertThrows(IllegalArgumentException.class, () -> BridgeValue.array(arbitrary));
    }

    /// Rejects value trees whose recursive depth exceeds the public transport bound.
    @Test
    void rejectsExcessiveNestingDepth() {
        BridgeValue value = BridgeValue.nullValue();
        for (int depth = 1; depth < BridgeValue.MAX_DEPTH; depth++) {
            value = BridgeValue.array(List.of(value));
        }
        BridgeValue accepted = value;

        assertEquals(BridgeValue.Tag.ARRAY, accepted.tag());
        assertThrows(IllegalArgumentException.class, () -> BridgeValue.array(List.of(accepted)));
    }

    /// Rejects oversized binary, text, container, and aggregate payloads before dispatch.
    @Test
    void rejectsValuesBeyondTransportSizeBounds() {
        assertThrows(IllegalArgumentException.class,
                () -> BridgeValue.bytes(new byte[BridgeValue.MAX_BYTE_LENGTH + 1]));

        char[] oversizedText = new char[BridgeValue.MAX_STRING_UTF8_LENGTH + 1];
        Arrays.fill(oversizedText, 'a');
        assertThrows(IllegalArgumentException.class, () -> BridgeValue.string(new String(oversizedText)));

        List<BridgeValue> oversizedContainer = new ArrayList<>(
                java.util.Collections.nCopies(BridgeValue.MAX_CONTAINER_ENTRIES + 1, BridgeValue.nullValue()));
        assertThrows(IllegalArgumentException.class, () -> BridgeValue.array(oversizedContainer));

        byte[] moreThanHalfTheContentLimit = new byte[BridgeValue.MAX_TOTAL_CONTENT_LENGTH / 2 + 1];
        BridgeValue firstBytes = BridgeValue.bytes(moreThanHalfTheContentLimit);
        BridgeValue secondBytes = BridgeValue.bytes(moreThanHalfTheContentLimit);
        assertThrows(IllegalArgumentException.class,
                () -> BridgeValue.array(List.of(firstBytes, secondBytes)));

        List<BridgeValue> oneContainer = new ArrayList<>(
                java.util.Collections.nCopies(BridgeValue.MAX_CONTAINER_ENTRIES, BridgeValue.nullValue()));
        List<BridgeValue> oversizedTree = new ArrayList<>();
        int containers = BridgeValue.MAX_TOTAL_VALUES / BridgeValue.MAX_CONTAINER_ENTRIES + 1;
        for (int index = 0; index < containers; index++) {
            oversizedTree.add(BridgeValue.array(oneContainer));
        }
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class, () -> BridgeValue.array(oversizedTree));
        assertTrue(failure.getMessage().contains("values"));
    }
}
