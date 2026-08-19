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

import com.google.gson.JsonParseException;
import com.google.gson.TypeAdapter;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.Objects;

/// Identifies a required plugin and the installed version range that satisfies the dependency.
@JsonAdapter(PluginDependency.GsonAdapter.class)
@NotNullByDefault
public final class PluginDependency {
    /// Required plugin identifier.
    private final String id;

    /// Version constraint applied to the installed dependency.
    private final PluginVersionConstraint versionConstraint;

    /// Creates an unconstrained dependency on a plugin ID.
    ///
    /// @param id required plugin ID
    public PluginDependency(String id) {
        this(id, PluginVersionConstraint.ANY);
    }

    /// Creates a dependency from a serialized version-constraint expression.
    ///
    /// @param id required plugin ID
    /// @param versionConstraint version-constraint expression
    public PluginDependency(String id, String versionConstraint) {
        this(id, PluginVersionConstraint.parse(versionConstraint));
    }

    /// Creates a dependency from an already parsed version constraint.
    ///
    /// @param id required plugin ID
    /// @param versionConstraint parsed version constraint
    public PluginDependency(String id, PluginVersionConstraint versionConstraint) {
        if (!PluginManifest.isValidId(id)) {
            throw new IllegalArgumentException("Invalid plugin dependency ID: " + id);
        }
        this.id = id;
        this.versionConstraint = versionConstraint;
    }

    /// Returns the required plugin ID.
    ///
    /// @return required plugin ID
    public String getId() {
        return id;
    }

    /// Returns the immutable parsed version constraint.
    ///
    /// @return version constraint
    public PluginVersionConstraint getVersionConstraint() {
        return versionConstraint;
    }

    /// Returns the version constraint in its serialized form.
    ///
    /// @return version-constraint expression
    public String getVersion() {
        return versionConstraint.getExpression();
    }

    /// Returns whether an installed plugin version satisfies this dependency.
    ///
    /// @param installedVersion installed dependency version
    /// @return whether the version is compatible
    public boolean matchesVersion(String installedVersion) {
        return versionConstraint.matches(installedVersion);
    }

    /// Compares dependencies by ID and version constraint.
    ///
    /// @param other comparison target
    /// @return whether both dependency declarations are equal
    @Override
    public boolean equals(@Nullable Object other) {
        return this == other
                || other instanceof PluginDependency dependency
                && id.equals(dependency.id)
                && versionConstraint.equals(dependency.versionConstraint);
    }

    /// Returns a hash derived from the ID and version constraint.
    ///
    /// @return dependency hash
    @Override
    public int hashCode() {
        return Objects.hash(id, versionConstraint);
    }

    /// Returns a concise dependency representation for diagnostics.
    ///
    /// @return dependency ID and constraint
    @Override
    public String toString() {
        return id + " " + versionConstraint;
    }

    /// Reads legacy string dependencies and schema-v3 object dependencies for Gson.
    @NotNullByDefault
    public static final class GsonAdapter extends TypeAdapter<PluginDependency> {
        /// Creates the stateless dependency adapter.
        public GsonAdapter() {
        }

        /// Writes dependencies using the structured `{id, version}` representation.
        ///
        /// @param writer JSON writer
        /// @param dependency dependency value or `null`
        /// @throws IOException if writing fails
        @Override
        public void write(JsonWriter writer, @Nullable PluginDependency dependency) throws IOException {
            if (dependency == null) {
                writer.nullValue();
                return;
            }
            writer.beginObject();
            writer.name("id").value(dependency.id);
            writer.name("version").value(dependency.versionConstraint.getExpression());
            writer.endObject();
        }

        /// Reads a legacy string ID or a structured dependency object.
        ///
        /// @param reader JSON reader
        /// @return parsed dependency or `null` for a JSON null
        /// @throws IOException if token reading fails
        /// @throws JsonParseException if the dependency representation or constraint is invalid
        @Override
        public @Nullable PluginDependency read(JsonReader reader) throws IOException {
            JsonToken token = reader.peek();
            if (token == JsonToken.NULL) {
                reader.nextNull();
                return null;
            }
            try {
                if (token == JsonToken.STRING) {
                    return new PluginDependency(reader.nextString());
                }
                if (token != JsonToken.BEGIN_OBJECT) {
                    throw new JsonParseException("Plugin dependency must be a string or object");
                }

                @Nullable String id = null;
                @Nullable String version = null;
                reader.beginObject();
                while (reader.hasNext()) {
                    switch (reader.nextName()) {
                        case "id" -> id = readRequiredString(reader, "id");
                        case "version" -> version = readRequiredString(reader, "version");
                        default -> reader.skipValue();
                    }
                }
                reader.endObject();
                if (id == null) {
                    throw new JsonParseException("Plugin dependency object has no id");
                }
                return new PluginDependency(id, version == null ? PluginVersionConstraint.ANY
                        : PluginVersionConstraint.parse(version));
            } catch (IllegalArgumentException exception) {
                throw new JsonParseException(exception.getMessage(), exception);
            }
        }

        /// Reads one required JSON string property.
        ///
        /// @param reader JSON reader positioned at the property value
        /// @param fieldName property name used in diagnostics
        /// @return property string
        /// @throws IOException if token reading fails
        private static String readRequiredString(JsonReader reader, String fieldName) throws IOException {
            if (reader.peek() != JsonToken.STRING) {
                throw new JsonParseException("Plugin dependency " + fieldName + " must be a string");
            }
            return reader.nextString();
        }
    }
}
