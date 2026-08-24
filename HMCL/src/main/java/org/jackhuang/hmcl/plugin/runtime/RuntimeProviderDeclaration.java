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

import com.google.gson.JsonParseException;
import com.google.gson.TypeAdapter;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import org.jackhuang.hmcl.util.gson.LowerCaseEnumTypeAdapter;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/// Immutable declaration of one runtime implementation published by a runtime-provider plugin.
@JsonAdapter(RuntimeProviderDeclaration.GsonAdapter.class)
@NotNullByDefault
public final class RuntimeProviderDeclaration {
    /// Canonical runtime identifier provided by the declaring plugin.
    private final String runtime;

    /// Immutable set of positive plugin ABI generations implemented by this runtime.
    private final @Unmodifiable Set<Integer> abis;

    /// Launcher-to-provider bridge ABI generation implemented by this runtime.
    private final int bridgeAbi;

    /// Immutable set of supported execution boundaries.
    private final @Unmodifiable Set<PluginExecutionMode> executionModes;

    /// Immutable set of runtime features implemented by this declaration.
    private final @Unmodifiable Set<RuntimeFeature> features;

    /// Creates a validated immutable runtime provider declaration.
    ///
    /// @param runtime provided runtime identifier
    /// @param abis implemented positive plugin ABI generations, including future generations
    /// @param bridgeAbi implemented bridge ABI generation
    /// @param executionModes supported execution boundaries
    /// @param features implemented runtime features
    /// @throws IllegalArgumentException when the declaration is incomplete or incompatible with this launcher
    public RuntimeProviderDeclaration(
            String runtime,
            Set<Integer> abis,
            int bridgeAbi,
            Set<PluginExecutionMode> executionModes,
            Set<RuntimeFeature> features) {
        String canonicalRuntime = PluginRuntimeTypes.requireValid(runtime);
        if (!canonicalRuntime.equals(runtime)) {
            throw new IllegalArgumentException("Runtime provider identifier must be canonical: " + runtime);
        }
        this.runtime = canonicalRuntime;
        this.abis = Set.copyOf(abis);
        if (this.abis.isEmpty()) {
            throw new IllegalArgumentException("Runtime provider ABI set cannot be empty");
        }
        for (Integer abi : this.abis) {
            if (abi == null) {
                throw new IllegalArgumentException("Runtime provider ABI cannot be null");
            }
            if (abi <= 0) {
                throw new IllegalArgumentException("Runtime provider ABI must be positive: " + abi);
            }
        }
        if (bridgeAbi <= 0) {
            throw new IllegalArgumentException("Runtime provider bridge ABI must be positive: " + bridgeAbi);
        }
        this.bridgeAbi = bridgeAbi;
        this.executionModes = Set.copyOf(executionModes);
        if (this.executionModes.isEmpty()) {
            throw new IllegalArgumentException("Runtime provider execution mode set cannot be empty");
        }
        this.features = Set.copyOf(features);
        if (!this.features.contains(RuntimeFeature.BRIDGE)) {
            throw new IllegalArgumentException("Runtime providers must implement bridge");
        }
    }

    /// Returns the canonical provided runtime identifier.
    ///
    /// @return canonical runtime identifier
    public String getRuntime() {
        return runtime;
    }

    /// Returns the immutable implemented plugin ABI generations.
    ///
    /// @return supported plugin ABIs
    public @Unmodifiable Set<Integer> getAbis() {
        return abis;
    }

    /// Returns the implemented bridge ABI generation.
    ///
    /// @return bridge ABI generation
    public int getBridgeAbi() {
        return bridgeAbi;
    }

    /// Returns the immutable supported execution boundaries.
    ///
    /// @return supported execution modes
    public @Unmodifiable Set<PluginExecutionMode> getExecutionModes() {
        return executionModes;
    }

    /// Returns the immutable implemented runtime features.
    ///
    /// @return implemented runtime features
    public @Unmodifiable Set<RuntimeFeature> getFeatures() {
        return features;
    }

    /// Compares every runtime implementation contract field.
    ///
    /// @param other comparison target
    /// @return whether both declarations are equivalent
    @Override
    public boolean equals(@Nullable Object other) {
        return this == other
                || other instanceof RuntimeProviderDeclaration declaration
                && bridgeAbi == declaration.bridgeAbi
                && runtime.equals(declaration.runtime)
                && abis.equals(declaration.abis)
                && executionModes.equals(declaration.executionModes)
                && features.equals(declaration.features);
    }

    /// Returns a hash derived from every runtime implementation contract field.
    ///
    /// @return declaration hash
    @Override
    public int hashCode() {
        return Objects.hash(runtime, abis, bridgeAbi, executionModes, features);
    }

    /// Reads and writes the schema-v5 object form while preserving immutable construction.
    @NotNullByDefault
    public static final class GsonAdapter extends TypeAdapter<@Nullable RuntimeProviderDeclaration> {
        /// Creates the stateless runtime-provider declaration adapter.
        public GsonAdapter() {
        }

        /// Writes a declaration using its canonical schema-v5 object form.
        ///
        /// @param writer JSON output
        /// @param declaration declaration to serialize, or `null`
        /// @throws IOException if writing fails
        @Override
        public void write(JsonWriter writer, @Nullable RuntimeProviderDeclaration declaration) throws IOException {
            if (declaration == null) {
                writer.nullValue();
                return;
            }
            writer.beginObject();
            writer.name("runtime").value(declaration.runtime);
            writer.name("abis").beginArray();
            for (Integer abi : declaration.abis) {
                writer.value(abi);
            }
            writer.endArray();
            writer.name("bridgeAbi").value(declaration.bridgeAbi);
            writeEnumSet(writer, "executionModes", declaration.executionModes);
            writeEnumSet(writer, "features", declaration.features);
            writer.endObject();
        }

        /// Reads one schema-v5 runtime-provider declaration.
        ///
        /// @param reader JSON input
        /// @return parsed declaration, or `null` for a JSON null
        /// @throws IOException if token reading fails
        /// @throws JsonParseException if the object is malformed
        @Override
        public @Nullable RuntimeProviderDeclaration read(JsonReader reader) throws IOException {
            if (reader.peek() == JsonToken.NULL) {
                reader.nextNull();
                return null;
            }
            if (reader.peek() != JsonToken.BEGIN_OBJECT) {
                throw new JsonParseException("Runtime provider declaration must be an object");
            }
            @Nullable String runtime = null;
            @Nullable Set<Integer> abis = null;
            int bridgeAbi = 0;
            @Nullable Set<PluginExecutionMode> executionModes = null;
            @Nullable Set<RuntimeFeature> features = null;
            reader.beginObject();
            while (reader.hasNext()) {
                switch (reader.nextName()) {
                    case "runtime" -> runtime = readRequiredString(reader, "runtime");
                    case "abis" -> abis = readAbiSet(reader);
                    case "bridgeAbi" -> bridgeAbi = readInteger(reader, "bridgeAbi");
                    case "executionModes" -> executionModes = readEnumSet(reader, PluginExecutionMode.class, "executionModes");
                    case "features" -> features = readEnumSet(reader, RuntimeFeature.class, "features");
                    default -> reader.skipValue();
                }
            }
            reader.endObject();
            if (runtime == null || abis == null || executionModes == null || features == null) {
                throw new JsonParseException("Runtime provider declaration is missing a required property");
            }
            try {
                return new RuntimeProviderDeclaration(runtime, abis, bridgeAbi, executionModes, features);
            } catch (IllegalArgumentException exception) {
                throw new JsonParseException(exception.getMessage(), exception);
            }
        }

        /// Writes a set of runtime enum values under one object property.
        ///
        /// @param writer JSON output
        /// @param name property name
        /// @param values enum values to serialize
        /// @throws IOException if writing fails
        private static <E extends Enum<E>> void writeEnumSet(JsonWriter writer, String name, Set<E> values) throws IOException {
            writer.name(name).beginArray();
            for (E value : values) {
                writer.value(value.toString());
            }
            writer.endArray();
        }

        /// Reads an array of unique supported plugin ABI generations.
        ///
        /// @param reader JSON input
        /// @return parsed ABI set
        /// @throws IOException if token reading fails
        /// @throws JsonParseException if the array is malformed
        private static Set<Integer> readAbiSet(JsonReader reader) throws IOException {
            if (reader.peek() != JsonToken.BEGIN_ARRAY) {
                throw new JsonParseException("Runtime provider abis must be an array");
            }
            Set<Integer> abis = new java.util.HashSet<>();
            reader.beginArray();
            while (reader.hasNext()) {
                int abi = readInteger(reader, "abis");
                if (!abis.add(abi)) {
                    throw new JsonParseException("Duplicate runtime provider ABI: " + abi);
                }
            }
            reader.endArray();
            return abis;
        }

        /// Reads an array of unique lower-case enum identifiers through the shared enum adapter rules.
        ///
        /// @param reader JSON input
        /// @param enumClass expected enum type
        /// @param fieldName property name used in diagnostics
        /// @param <E> enum type
        /// @return parsed enum set
        /// @throws IOException if token reading fails
        /// @throws JsonParseException if the array contains an unsupported value
        private static <E extends Enum<E>> Set<E> readEnumSet(
                JsonReader reader,
                Class<E> enumClass,
                String fieldName) throws IOException {
            if (reader.peek() != JsonToken.BEGIN_ARRAY) {
                throw new JsonParseException("Runtime provider " + fieldName + " must be an array");
            }
            Set<E> values = EnumSet.noneOf(enumClass);
            reader.beginArray();
            while (reader.hasNext()) {
                String token = readRequiredString(reader, fieldName);
                @Nullable E value = LowerCaseEnumTypeAdapter.fromJson(enumClass, token);
                if (value == null) {
                    throw new JsonParseException("Unknown runtime provider " + fieldName + " value: " + token);
                }
                if (!value.toString().equals(token)) {
                    throw new JsonParseException("Runtime provider " + fieldName + " value must be canonical: " + token);
                }
                if (!values.add(value)) {
                    throw new JsonParseException("Duplicate runtime provider " + fieldName + " value: " + token);
                }
            }
            reader.endArray();
            return values;
        }

        /// Reads one required lexical JSON integer without accepting decimal or exponential notation.
        ///
        /// @param reader JSON input
        /// @param fieldName property name used in diagnostics
        /// @return parsed integer
        /// @throws IOException if token reading fails
        /// @throws JsonParseException if the value is not an integer number
        private static int readInteger(JsonReader reader, String fieldName) throws IOException {
            if (reader.peek() != JsonToken.NUMBER) {
                throw new JsonParseException("Runtime provider " + fieldName + " must be a number");
            }
            String token = reader.nextString();
            try {
                return Integer.parseInt(token);
            } catch (NumberFormatException exception) {
                throw new JsonParseException("Runtime provider " + fieldName + " must be an integer", exception);
            }
        }

        /// Reads one required string JSON value.
        ///
        /// @param reader JSON input
        /// @param fieldName property name used in diagnostics
        /// @return parsed string
        /// @throws IOException if token reading fails
        /// @throws JsonParseException if the value is not a string
        private static String readRequiredString(JsonReader reader, String fieldName) throws IOException {
            if (reader.peek() != JsonToken.STRING) {
                throw new JsonParseException("Runtime provider " + fieldName + " must be a string");
            }
            return reader.nextString();
        }
    }
}
