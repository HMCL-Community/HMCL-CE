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
package org.jackhuang.hmcl.plugin.store;

import com.google.gson.TypeAdapter;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import org.jackhuang.hmcl.plugin.runtime.PluginPlatformTarget;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/// Immutable identity and transport metadata for one exact platform-specific plugin package.
///
/// @param platform exact operating-system and architecture target
/// @param packageUrl package download URL
/// @param sha256 lower-case SHA-256 of the complete package
/// @param size exact package size in bytes
@NotNullByDefault
@JsonAdapter(PluginStoreArtifact.GsonAdapter.class)
public record PluginStoreArtifact(
        PluginPlatformTarget platform,
        String packageUrl,
        String sha256,
        long size
) {
    /// Required lower-case SHA-256 representation for platform artifacts.
    private static final Pattern SHA256_PATTERN = Pattern.compile("[0-9a-f]{64}");

    /// Strict positive base-ten integer spelling accepted for artifact sizes.
    private static final Pattern POSITIVE_DECIMAL_PATTERN = Pattern.compile("[1-9][0-9]*");

    /// Validates that every component identifies one exact downloadable artifact.
    public PluginStoreArtifact {
        Objects.requireNonNull(platform, "platform");
        Objects.requireNonNull(packageUrl, "packageUrl");
        Objects.requireNonNull(sha256, "sha256");
        requireValidPackageUrl(packageUrl);
        if (!SHA256_PATTERN.matcher(sha256).matches()) {
            throw new IllegalArgumentException("Invalid Store artifact SHA-256 for " + platform.getId());
        }
        if (size <= 0) {
            throw new IllegalArgumentException("Invalid Store artifact size for " + platform.getId());
        }
    }

    /// Accepts production HTTPS URLs and loopback HTTP URLs used by local repositories and tests.
    ///
    /// @param value package URL to validate
    private static void requireValidPackageUrl(String value) {
        final URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("Invalid Store artifact package URL", exception);
        }
        String scheme = Objects.requireNonNullElse(uri.getScheme(), "").toLowerCase(Locale.ROOT);
        String host = Objects.requireNonNullElse(uri.getHost(), "").toLowerCase(Locale.ROOT);
        boolean loopbackHttp = scheme.equals("http")
                && (host.equals("localhost") || host.equals("127.0.0.1")
                || host.equals("::1") || host.equals("[::1]"));
        if ((!scheme.equals("https") && !loopbackHttp) || host.isBlank()) {
            throw new IllegalArgumentException("Store artifact package URL must use HTTPS or loopback HTTP");
        }
    }

    /// Parses and writes the compact Store artifact object while preserving canonical platform identifiers.
    @NotNullByDefault
    public static final class GsonAdapter extends TypeAdapter<@Nullable PluginStoreArtifact> {
        /// Creates the stateless Store artifact adapter.
        public GsonAdapter() {
        }

        /// Writes one Store artifact object or JSON null.
        ///
        /// @param writer destination JSON writer
        /// @param value artifact value or `null`
        /// @throws IOException if JSON output fails
        @Override
        public void write(JsonWriter writer, @Nullable PluginStoreArtifact value) throws IOException {
            if (value == null) {
                writer.nullValue();
                return;
            }
            writer.beginObject();
            writer.name("platform").value(value.platform().getId());
            writer.name("packageUrl").value(value.packageUrl());
            writer.name("sha256").value(value.sha256());
            writer.name("size").value(value.size());
            writer.endObject();
        }

        /// Reads and validates one Store artifact object.
        ///
        /// @param reader source JSON reader
        /// @return validated artifact or `null`
        /// @throws IOException if the artifact is malformed or violates its value contract
        @Override
        public @Nullable PluginStoreArtifact read(JsonReader reader) throws IOException {
            if (reader.peek() == JsonToken.NULL) {
                reader.nextNull();
                return null;
            }
            if (reader.peek() != JsonToken.BEGIN_OBJECT) {
                throw new IOException("Plugin Store artifact is not an object");
            }
            @Nullable String platform = null;
            @Nullable String packageUrl = null;
            @Nullable String sha256 = null;
            @Nullable Long size = null;
            reader.beginObject();
            while (reader.hasNext()) {
                switch (reader.nextName()) {
                    case "platform":
                        platform = readString(reader, "platform");
                        break;
                    case "packageUrl":
                        packageUrl = readString(reader, "packageUrl");
                        break;
                    case "sha256":
                        sha256 = readString(reader, "sha256");
                        break;
                    case "size":
                        size = readSize(reader);
                        break;
                    default:
                        reader.skipValue();
                        break;
                }
            }
            reader.endObject();
            if (platform == null || packageUrl == null || sha256 == null || size == null) {
                throw new IOException("Plugin Store artifact is missing required metadata");
            }
            try {
                PluginPlatformTarget parsedPlatform = PluginPlatformTarget.parse(platform);
                if (!parsedPlatform.getId().equals(platform)) {
                    throw new IllegalArgumentException("Store artifact target must be canonical: " + platform);
                }
                return new PluginStoreArtifact(parsedPlatform, packageUrl, sha256, size);
            } catch (IllegalArgumentException exception) {
                throw new IOException("Invalid Plugin Store artifact for " + platform, exception);
            }
        }

        /// Reads one required string property without coercing other JSON token types.
        ///
        /// @param reader source JSON reader
        /// @param name field name used in diagnostics
        /// @return string value
        /// @throws IOException if the property is not a string
        private static String readString(JsonReader reader, String name) throws IOException {
            if (reader.peek() != JsonToken.STRING) {
                throw new IOException("Plugin Store artifact " + name + " is not a string");
            }
            return reader.nextString();
        }

        /// Reads a positive decimal integer without allowing Gson to truncate fractional or exponent notation.
        ///
        /// @param reader source JSON reader
        /// @return positive artifact size that fits in a signed long
        /// @throws IOException if the token is not a canonical positive decimal long
        private static long readSize(JsonReader reader) throws IOException {
            if (reader.peek() != JsonToken.NUMBER) {
                throw new IOException("Plugin Store artifact size is not a number");
            }
            String value = reader.nextString();
            if (!POSITIVE_DECIMAL_PATTERN.matcher(value).matches()) {
                throw new IOException("Plugin Store artifact size is not a positive decimal integer");
            }
            try {
                return Long.parseLong(value);
            } catch (NumberFormatException exception) {
                throw new IOException("Plugin Store artifact size exceeds the supported range", exception);
            }
        }
    }
}
