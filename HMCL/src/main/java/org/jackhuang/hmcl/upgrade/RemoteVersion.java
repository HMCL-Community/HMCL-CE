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
package org.jackhuang.hmcl.upgrade;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import org.jackhuang.hmcl.task.FileDownloadTask.IntegrityCheck;
import org.jackhuang.hmcl.util.io.NetworkUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.Locale;

/// Describes one launcher update and the integrity check required before installation.
@NotNullByDefault
public record RemoteVersion(
        UpdateChannel channel,
        String version,
        String url,
        Type type,
        IntegrityCheck integrityCheck,
        boolean preview,
        boolean force
) {

    /// Fetches and parses a legacy update object or a GitHub Releases API response.
    ///
    /// @param channel requested launcher update channel
    /// @param preview whether preview releases are allowed
    /// @param url update source URL
    /// @return the first eligible release with an exact, digest-bearing JAR asset
    /// @throws IOException if the response is malformed or no verified asset is available
    public static RemoteVersion fetch(UpdateChannel channel, boolean preview, String url) throws IOException {
        return parse(channel, preview, NetworkUtils.doGet(url));
    }

    /// Parses one legacy update object or a GitHub Releases API array without performing network I/O.
    ///
    /// @param channel requested launcher update channel
    /// @param preview whether preview releases are allowed
    /// @param response JSON response body
    /// @return the parsed update metadata
    /// @throws IOException if the response is malformed or no eligible release has a verified JAR asset
    static RemoteVersion parse(UpdateChannel channel, boolean preview, String response) throws IOException {
        try {
            JsonElement root = JsonParser.parseString(response);
            if (root.isJsonObject()) {
                JsonObject object = root.getAsJsonObject();
                if (object.has("version")) {
                    return parseLegacy(channel, preview, object);
                }
                return parseGithubRelease(channel, preview, object);
            } else if (root.isJsonArray()) {
                return parseGithubReleases(channel, preview, root.getAsJsonArray());
            }
            throw new IOException("Update response must be a JSON object or array");
        } catch (JsonParseException | IllegalStateException | UnsupportedOperationException exception) {
            throw new IOException("Malformed update response", exception);
        }
    }

    /// Parses the existing single-object update source format.
    ///
    /// @param channel requested launcher update channel
    /// @param preview whether preview releases are allowed
    /// @param response legacy update object
    /// @return legacy update metadata
    /// @throws IOException if a required legacy field is missing
    private static RemoteVersion parseLegacy(UpdateChannel channel, boolean preview, JsonObject response)
            throws IOException {
        String version = requiredString(response, "version");
        String url = requiredString(response, "jar");
        String checksum = requiredString(response, "jarsha1");
        boolean force = optionalBoolean(response, "force", false);
        return new RemoteVersion(channel, version, url, Type.JAR, new IntegrityCheck("SHA-1", checksum), preview, force);
    }

    /// Parses one GitHub Releases object when an API proxy returns a single release.
    ///
    /// @param channel requested launcher update channel
    /// @param preview whether preview releases are allowed
    /// @param response GitHub release object
    /// @return parsed release metadata
    /// @throws IOException if the release is not eligible or has no verified JAR asset
    private static RemoteVersion parseGithubRelease(UpdateChannel channel, boolean preview, JsonObject response)
            throws IOException {
        boolean prerelease = requiredBoolean(response, "prerelease");
        if (!isEligible(channel, preview, prerelease)) {
            throw new IOException("No eligible release is available");
        }
        return parseGithubReleaseAsset(channel, preview, response);
    }

    /// Selects the first API release matching the requested channel and parses its exact JAR asset.
    ///
    /// GitHub returns releases newest first; preserving that order makes the API's publication ordering the source
    /// of truth while still skipping releases from other channels.
    ///
    /// @param channel requested launcher update channel
    /// @param preview whether preview releases are allowed
    /// @param releases GitHub Releases API array
    /// @return parsed release metadata
    /// @throws IOException if no eligible release exists or its asset metadata is invalid
    private static RemoteVersion parseGithubReleases(UpdateChannel channel, boolean preview, JsonArray releases)
            throws IOException {
        for (JsonElement element : releases) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject release = element.getAsJsonObject();
            if (isEligible(channel, preview, requiredBoolean(release, "prerelease"))) {
                return parseGithubReleaseAsset(channel, preview, release);
            }
        }
        throw new IOException("No eligible release is available");
    }

    /// Reads the exact versioned JAR asset from one eligible GitHub release.
    ///
    /// @param channel requested launcher update channel
    /// @param preview whether preview releases are allowed
    /// @param release eligible GitHub release object
    /// @return release metadata backed by a SHA-256 digest
    /// @throws IOException if tag, asset, URL, or digest metadata is missing or invalid
    private static RemoteVersion parseGithubReleaseAsset(UpdateChannel channel, boolean preview, JsonObject release)
            throws IOException {
        String version = normalizeTag(requiredString(release, "tag_name"));
        String expectedAssetName = "HMCL-CE-" + version + ".jar";
        JsonElement assetsElement = release.get("assets");
        if (assetsElement == null || !assetsElement.isJsonArray()) {
            throw new IOException("GitHub release has no assets: " + version);
        }

        for (JsonElement assetElement : assetsElement.getAsJsonArray()) {
            if (!assetElement.isJsonObject()) {
                continue;
            }
            JsonObject asset = assetElement.getAsJsonObject();
            if (!expectedAssetName.equals(optionalString(asset, "name"))) {
                continue;
            }
            String url = requiredString(asset, "browser_download_url");
            String checksum = parseSha256Digest(requiredString(asset, "digest"));
            return new RemoteVersion(
                    channel,
                    version,
                    url,
                    Type.JAR,
                    new IntegrityCheck("SHA-256", checksum),
                    preview,
                    false
            );
        }
        throw new IOException("GitHub release has no verified asset: " + expectedAssetName);
    }

    /// Returns whether a release belongs to the requested channel.
    ///
    /// @param channel requested launcher update channel
    /// @param preview whether any release may be selected
    /// @param prerelease GitHub prerelease flag
    /// @return whether the release is eligible
    private static boolean isEligible(UpdateChannel channel, boolean preview, boolean prerelease) {
        if (preview) {
            return true;
        }
        return switch (channel) {
            case STABLE -> !prerelease;
            case DEVELOPMENT, NIGHTLY -> prerelease;
        };
    }

    /// Removes the conventional leading `v` from a Git tag.
    ///
    /// @param tag GitHub tag name
    /// @return normalized launcher version
    /// @throws IOException if the tag is blank or contains no version after normalization
    private static String normalizeTag(String tag) throws IOException {
        String version = tag.startsWith("v") ? tag.substring(1) : tag;
        if (version.isBlank()) {
            throw new IOException("GitHub release tag has no version");
        }
        return version;
    }

    /// Validates and strips the GitHub `sha256:` digest prefix.
    ///
    /// @param digest GitHub asset digest
    /// @return normalized lower-case SHA-256 hex digest
    /// @throws IOException if the digest is missing, uses another algorithm, or has an invalid length
    private static String parseSha256Digest(String digest) throws IOException {
        if (!digest.regionMatches(true, 0, "sha256:", 0, "sha256:".length())) {
            throw new IOException("GitHub asset is missing a SHA-256 digest");
        }
        String checksum = digest.substring("sha256:".length());
        if (!checksum.matches("[0-9a-fA-F]{64}")) {
            throw new IOException("GitHub asset has an invalid SHA-256 digest");
        }
        return checksum.toLowerCase(Locale.ROOT);
    }

    /// Reads a required non-null JSON string property.
    ///
    /// @param object JSON object
    /// @param name property name
    /// @return property value
    /// @throws IOException if the property is absent, null, or blank
    private static String requiredString(JsonObject object, String name) throws IOException {
        @Nullable JsonElement value = object.get(name);
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) {
            throw new IOException("Update response is missing " + name);
        }
        String text = value.getAsString();
        if (text.isBlank()) {
            throw new IOException("Update response has a blank " + name);
        }
        return text;
    }

    /// Reads an optional JSON string property without accepting null or non-primitive values.
    ///
    /// @param object JSON object
    /// @param name property name
    /// @return trimmed property value, or `null` when absent/null
    private static @Nullable String optionalString(JsonObject object, String name) {
        @Nullable JsonElement value = object.get(name);
        if (value == null || value.isJsonNull()) {
            return null;
        }
        if (!value.isJsonPrimitive()) {
            return null;
        }
        return value.getAsString();
    }

    /// Reads an optional boolean property with a deterministic default.
    ///
    /// @param object JSON object
    /// @param name property name
    /// @param fallback value used when the property is absent/null
    /// @return boolean property value
    private static boolean optionalBoolean(JsonObject object, String name, boolean fallback) {
        @Nullable JsonElement value = object.get(name);
        return value == null || value.isJsonNull() ? fallback : value.getAsBoolean();
    }

    /// Reads a required boolean property.
    ///
    /// @param object JSON object
    /// @param name property name
    /// @return boolean property value
    /// @throws IOException if the property is absent, null, or not boolean-like
    private static boolean requiredBoolean(JsonObject object, String name) throws IOException {
        @Nullable JsonElement value = object.get(name);
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) {
            throw new IOException("Update response is missing " + name);
        }
        try {
            return value.getAsBoolean();
        } catch (UnsupportedOperationException | IllegalStateException exception) {
            throw new IOException("Update response has an invalid " + name, exception);
        }
    }

    /// Identifies the archive format used by launcher updates.
    public enum Type {
        /// Executable launcher JAR.
        JAR
    }

    /// Formats the update for diagnostic logs without exposing response internals.
    @Override
    public String toString() {
        return "[" + version + " from " + url + "]";
    }
}
