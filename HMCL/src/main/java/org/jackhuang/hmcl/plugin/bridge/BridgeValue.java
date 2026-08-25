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
import org.jetbrains.annotations.Unmodifiable;

import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/// Represents one closed, bounded, language-neutral value in the Plugin ABI Bridge.
@NotNullByDefault
public sealed interface BridgeValue permits BridgeValue.NullValue, BridgeValue.BooleanValue,
        BridgeValue.IntegerValue, BridgeValue.FloatValue, BridgeValue.StringValue, BridgeValue.BytesValue,
        BridgeValue.ArrayValue, BridgeValue.MapValue, BridgeValue.HandleValue, BridgeValue.ErrorValue {
    /// Maximum recursive value depth, counting the root value.
    int MAX_DEPTH = 32;

    /// Maximum number of direct entries in one array or map.
    int MAX_CONTAINER_ENTRIES = 1024;

    /// Maximum UTF-8 byte length of one string or map key.
    int MAX_STRING_UTF8_LENGTH = 1024 * 1024;

    /// Maximum length of one byte array.
    int MAX_BYTE_LENGTH = 16 * 1024 * 1024;

    /// Maximum aggregate UTF-8 string, map-key, and byte content in one value tree.
    int MAX_TOTAL_CONTENT_LENGTH = 16 * 1024 * 1024;

    /// Maximum number of values in one recursively structured value.
    int MAX_TOTAL_VALUES = 65_536;

    /// Returns this value's explicit transport tag.
    ///
    /// @return stable value tag
    Tag tag();

    /// Returns the singleton Bridge null value.
    ///
    /// @return immutable null value
    static BridgeValue nullValue() {
        return NullValue.INSTANCE;
    }

    /// Creates one boolean value.
    ///
    /// @param value boolean value
    /// @return immutable boolean value
    static BridgeValue bool(boolean value) {
        return new BooleanValue(value);
    }

    /// Creates one signed 64-bit integer value.
    ///
    /// @param value signed integer
    /// @return immutable integer value
    static BridgeValue integer(long value) {
        return new IntegerValue(value);
    }

    /// Creates one finite IEEE-754 binary64 value.
    ///
    /// @param value finite floating-point number
    /// @return immutable floating-point value
    static BridgeValue floating(double value) {
        return new FloatValue(value);
    }

    /// Creates one bounded UTF-8 string value.
    ///
    /// @param value string value
    /// @return immutable string value
    static BridgeValue string(String value) {
        return new StringValue(value);
    }

    /// Creates one defensively copied bounded byte value.
    ///
    /// @param value source bytes
    /// @return immutable byte value
    static BridgeValue bytes(byte[] value) {
        return new BytesValue(value);
    }

    /// Creates one bounded immutable array value.
    ///
    /// @param values source values
    /// @return immutable array value
    static BridgeValue array(List<BridgeValue> values) {
        return new ArrayValue(values);
    }

    /// Creates one bounded insertion-ordered immutable map value.
    ///
    /// @param values source entries
    /// @return immutable map value
    static BridgeValue map(Map<String, BridgeValue> values) {
        return new MapValue(values);
    }

    /// Creates one opaque handle value.
    ///
    /// @param value opaque handle token
    /// @return immutable handle value
    static BridgeValue handle(BridgeHandle value) {
        return new HandleValue(value);
    }

    /// Creates one redacted error value.
    ///
    /// @param value portable Bridge error
    /// @return immutable error value
    static BridgeValue error(BridgeError value) {
        return new ErrorValue(value);
    }

    /// Validates one structured value tree after its direct entries have been copied.
    ///
    /// @param children direct array or map values
    /// @param directContentLength encoded map-key content owned directly by the root
    private static void validateStructured(Iterable<BridgeValue> children, int directContentLength) {
        int maximumDepth = 1;
        int values = 1;
        int contentLength = directContentLength;
        for (BridgeValue child : children) {
            TreeStats childStats = inspect(child, 2);
            maximumDepth = Math.max(maximumDepth, childStats.depth());
            values = checkedValueCount(values, childStats.values());
            contentLength = checkedContentLength(contentLength, childStats.contentLength());
        }
        if (maximumDepth > MAX_DEPTH) {
            throw new IllegalArgumentException("Bridge value exceeds maximum depth");
        }
    }

    /// Recursively measures one already type-checked Bridge tree.
    ///
    /// @param value current value
    /// @param depth current root-relative depth
    /// @return aggregate depth and value count
    private static TreeStats inspect(BridgeValue value, int depth) {
        if (depth > MAX_DEPTH) {
            throw new IllegalArgumentException("Bridge value exceeds maximum depth");
        }
        int maximumDepth = depth;
        int values = 1;
        int contentLength = 0;
        if (value instanceof ArrayValue array) {
            for (BridgeValue child : array.values()) {
                TreeStats childStats = inspect(child, depth + 1);
                maximumDepth = Math.max(maximumDepth, childStats.depth());
                values = checkedValueCount(values, childStats.values());
                contentLength = checkedContentLength(contentLength, childStats.contentLength());
            }
        } else if (value instanceof MapValue map) {
            for (Map.Entry<String, BridgeValue> entry : map.values().entrySet()) {
                contentLength = checkedContentLength(contentLength, encodedStringLength(entry.getKey()));
                BridgeValue child = entry.getValue();
                TreeStats childStats = inspect(child, depth + 1);
                maximumDepth = Math.max(maximumDepth, childStats.depth());
                values = checkedValueCount(values, childStats.values());
                contentLength = checkedContentLength(contentLength, childStats.contentLength());
            }
        } else if (value instanceof StringValue string) {
            contentLength = encodedStringLength(string.value());
        } else if (value instanceof BytesValue bytes) {
            contentLength = bytes.value.length;
        }
        return new TreeStats(maximumDepth, values, contentLength);
    }

    /// Adds a subtree count while enforcing the aggregate value bound without integer overflow.
    ///
    /// @param current current aggregate count
    /// @param additional subtree count
    /// @return bounded aggregate count
    private static int checkedValueCount(int current, int additional) {
        if (additional > MAX_TOTAL_VALUES - current) {
            throw new IllegalArgumentException("Bridge value contains too many values");
        }
        return current + additional;
    }

    /// Adds encoded content sizes while enforcing the aggregate byte bound without integer overflow.
    ///
    /// @param current current encoded content size
    /// @param additional additional encoded content size
    /// @return bounded aggregate content size
    private static int checkedContentLength(int current, int additional) {
        if (additional > MAX_TOTAL_CONTENT_LENGTH - current) {
            throw new IllegalArgumentException("Bridge value contains too much encoded content");
        }
        return current + additional;
    }

    /// Validates the UTF-8 size of one string or map key.
    ///
    /// @param value candidate text
    private static void requireBoundedString(String value) {
        int encodedLength = encodedStringLength(value);
        if (encodedLength > MAX_STRING_UTF8_LENGTH) {
            throw new IllegalArgumentException("Bridge string exceeds maximum UTF-8 length");
        }
    }

    /// Returns the UTF-8 encoded length of one non-null string.
    ///
    /// @param value source string
    /// @return UTF-8 byte length
    private static int encodedStringLength(String value) {
        Objects.requireNonNull(value, "value");
        try {
            return StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(value))
                    .remaining();
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException("Bridge string must contain well-formed UTF-16", exception);
        }
    }

    /// Enumerates stable tags used by embedded tables and isolated messages.
    @NotNullByDefault
    enum Tag {
        /// Null singleton.
        NULL,

        /// Boolean scalar.
        BOOLEAN,

        /// Signed 64-bit integer scalar.
        INTEGER,

        /// IEEE-754 binary64 scalar.
        FLOAT,

        /// UTF-8 string scalar.
        STRING,

        /// Opaque bytes.
        BYTES,

        /// Ordered immutable value array.
        ARRAY,

        /// Insertion-ordered immutable string map.
        MAP,

        /// Owner-scoped opaque handle.
        HANDLE,

        /// Redacted portable failure.
        ERROR
    }

    /// Represents the sole Bridge null value.
    @NotNullByDefault
    enum NullValue implements BridgeValue {
        /// Shared null instance.
        INSTANCE;

        /// Returns the null tag.
        ///
        /// @return null tag
        @Override
        public Tag tag() {
            return Tag.NULL;
        }
    }

    /// Represents one Bridge boolean.
    ///
    /// @param value boolean value
    @NotNullByDefault
    record BooleanValue(boolean value) implements BridgeValue {
        /// Returns the boolean tag.
        ///
        /// @return boolean tag
        @Override
        public Tag tag() {
            return Tag.BOOLEAN;
        }
    }

    /// Represents one signed 64-bit Bridge integer.
    ///
    /// @param value signed integer value
    @NotNullByDefault
    record IntegerValue(long value) implements BridgeValue {
        /// Returns the integer tag.
        ///
        /// @return integer tag
        @Override
        public Tag tag() {
            return Tag.INTEGER;
        }
    }

    /// Represents one finite IEEE-754 binary64 Bridge number.
    ///
    /// @param value finite floating-point value
    @NotNullByDefault
    record FloatValue(double value) implements BridgeValue {
        /// Rejects non-finite values that cannot be represented consistently by all Runtime SDKs.
        public FloatValue {
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException("Bridge floating-point value must be finite");
            }
        }

        /// Returns the floating-point tag.
        ///
        /// @return floating-point tag
        @Override
        public Tag tag() {
            return Tag.FLOAT;
        }
    }

    /// Represents one bounded UTF-8 Bridge string.
    ///
    /// @param value string value
    @NotNullByDefault
    record StringValue(String value) implements BridgeValue {
        /// Validates the string's encoded transport size.
        public StringValue {
            requireBoundedString(value);
        }

        /// Returns the string tag.
        ///
        /// @return string tag
        @Override
        public Tag tag() {
            return Tag.STRING;
        }
    }

    /// Represents one defensively copied bounded byte array.
    @NotNullByDefault
    final class BytesValue implements BridgeValue {
        /// Privately owned byte content.
        private final byte @Unmodifiable [] value;

        /// Copies and validates one source byte array.
        ///
        /// @param value source bytes
        public BytesValue(byte[] value) {
            Objects.requireNonNull(value, "value");
            if (value.length > MAX_BYTE_LENGTH) {
                throw new IllegalArgumentException("Bridge byte value exceeds maximum length");
            }
            this.value = value.clone();
        }

        /// Returns a copy of the byte content.
        ///
        /// @return copied bytes
        public byte[] value() {
            return value.clone();
        }

        /// Returns the byte-array tag.
        ///
        /// @return byte-array tag
        @Override
        public Tag tag() {
            return Tag.BYTES;
        }

        /// Compares byte content rather than array identity.
        ///
        /// @param other candidate value
        /// @return whether the byte content is equal
        @Override
        public boolean equals(Object other) {
            return this == other || other instanceof BytesValue that && Arrays.equals(value, that.value);
        }

        /// Returns the byte content hash.
        ///
        /// @return content hash
        @Override
        public int hashCode() {
            return Arrays.hashCode(value);
        }

        /// Returns a redacted structural description without exposing byte content.
        ///
        /// @return structural description
        @Override
        public String toString() {
            return "BytesValue[length=" + value.length + "]";
        }
    }

    /// Represents one bounded immutable ordered Bridge array.
    ///
    /// @param values copied array values
    @NotNullByDefault
    record ArrayValue(@Unmodifiable List<BridgeValue> values) implements BridgeValue {
        /// Copies entries, rejects arbitrary objects, and validates the complete tree.
        public ArrayValue {
            Objects.requireNonNull(values, "values");
            if (values.size() > MAX_CONTAINER_ENTRIES) {
                throw new IllegalArgumentException("Bridge array contains too many entries");
            }
            List<BridgeValue> copied = new ArrayList<>(values.size());
            for (Object candidate : values) {
                if (candidate == null) {
                    throw new NullPointerException("Bridge array value");
                }
                if (!(candidate instanceof BridgeValue bridgeValue)) {
                    throw new IllegalArgumentException("Bridge array contains an unsupported Java object");
                }
                copied.add(bridgeValue);
            }
            values = List.copyOf(copied);
            validateStructured(values, 0);
        }

        /// Returns the array tag.
        ///
        /// @return array tag
        @Override
        public Tag tag() {
            return Tag.ARRAY;
        }
    }

    /// Represents one bounded immutable insertion-ordered Bridge map.
    ///
    /// @param values copied map entries
    @NotNullByDefault
    record MapValue(@Unmodifiable Map<String, BridgeValue> values) implements BridgeValue {
        /// Copies entries, validates keys and values, and validates the complete tree.
        public MapValue {
            Objects.requireNonNull(values, "values");
            if (values.size() > MAX_CONTAINER_ENTRIES) {
                throw new IllegalArgumentException("Bridge map contains too many entries");
            }
            Map<String, BridgeValue> copied = new LinkedHashMap<>(values.size());
            int keyContentLength = 0;
            for (Map.Entry<?, ?> entry : values.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    if (entry.getKey() == null) {
                        throw new NullPointerException("Bridge map key");
                    }
                    throw new IllegalArgumentException("Bridge map contains a non-string key");
                }
                requireBoundedString(key);
                keyContentLength = checkedContentLength(keyContentLength, encodedStringLength(key));
                Object candidate = entry.getValue();
                if (candidate == null) {
                    throw new NullPointerException("Bridge map value");
                }
                if (!(candidate instanceof BridgeValue bridgeValue)) {
                    throw new IllegalArgumentException("Bridge map contains an unsupported Java object");
                }
                copied.put(key, bridgeValue);
            }
            values = Collections.unmodifiableMap(copied);
            validateStructured(values.values(), keyContentLength);
        }

        /// Returns the map tag.
        ///
        /// @return map tag
        @Override
        public Tag tag() {
            return Tag.MAP;
        }
    }

    /// Represents one opaque owner-scoped handle token.
    ///
    /// @param value opaque handle
    @NotNullByDefault
    record HandleValue(BridgeHandle value) implements BridgeValue {
        /// Rejects a null handle.
        public HandleValue {
            Objects.requireNonNull(value, "value");
        }

        /// Returns the handle tag.
        ///
        /// @return handle tag
        @Override
        public Tag tag() {
            return Tag.HANDLE;
        }
    }

    /// Represents one redacted portable error value.
    ///
    /// @param value portable error
    @NotNullByDefault
    record ErrorValue(BridgeError value) implements BridgeValue {
        /// Rejects a null error.
        public ErrorValue {
            Objects.requireNonNull(value, "value");
        }

        /// Returns the error tag.
        ///
        /// @return error tag
        @Override
        public Tag tag() {
            return Tag.ERROR;
        }
    }

    /// Carries recursive validation totals.
    ///
    /// @param depth maximum observed depth
    /// @param values aggregate value count
    /// @param contentLength aggregate encoded string, map-key, and byte length
    @NotNullByDefault
    record TreeStats(int depth, int values, int contentLength) {
    }

}
