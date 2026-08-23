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

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import org.jackhuang.hmcl.plugin.trust.PluginTrustVerifier;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// Discovers bounded plugin manifests from repositories carrying one GitHub Topic.
@NotNullByDefault
public final class GitHubTopicDiscovery {
    /// Maximum API response size.
    private static final int MAX_API_BYTES = 2 * 1024 * 1024;

    /// Maximum individual manifest size.
    private static final int MAX_MANIFEST_BYTES = 2 * 1024 * 1024;

    /// GitHub search endpoint.
    private final String searchUrl;

    /// Raw-content base endpoint.
    private final String rawBaseUrl;

    /// Normalized Topic name.
    private final String topic;

    /// Optional GitHub API token.
    private final @Nullable String token;

    /// Maximum result pages.
    private final int maxPages;

    /// Creates a bounded discovery client.
    public GitHubTopicDiscovery(
            String searchUrl,
            String rawBaseUrl,
            String topic,
            @Nullable String token,
            int maxPages
    ) {
        this.searchUrl = requireHttpUrl(searchUrl, "GitHub search URL");
        this.rawBaseUrl = requireHttpUrl(rawBaseUrl, "GitHub raw base URL").replaceAll("/+$", "");
        this.topic = topic.trim().toLowerCase(Locale.ROOT);
        if (!this.topic.matches("[a-z0-9][a-z0-9-]{0,49}")) {
            throw new IllegalArgumentException("Invalid GitHub Topic");
        }
        this.token = token == null || token.isBlank() ? null : token.trim();
        if (maxPages < 1 || maxPages > 10) {
            throw new IllegalArgumentException("GitHub Topic page limit must be between 1 and 10");
        }
        this.maxPages = maxPages;
    }

    /// Discovers repositories, logs and skips invalid or ambiguous candidates, and builds one synthetic registry.
    public Result discover() throws IOException {
        Map<String, DiscoveredRepository> candidatesByPluginId = new LinkedHashMap<>();
        Set<String> conflictingPluginIds = new HashSet<>();
        Set<String> seenRepositoryIdentities = new HashSet<>();
        int skippedRepositoryCount = 0;
        for (int page = 1; page <= maxPages; page++) {
            String separator = searchUrl.contains("?") ? "&" : "?";
            String requestUrl = searchUrl + separator
                    + "q=" + encode("topic:" + topic)
                    + "&per_page=100&page=" + page;
            JsonObject response = parseObject(fetch(requestUrl, MAX_API_BYTES, true), "GitHub Topic response");
            JsonArray repositories = requiredArray(response, "items");
            for (JsonElement repositoryElement : repositories) {
                if (!repositoryElement.isJsonObject()) {
                    skippedRepositoryCount++;
                    LOG.warning("Skipping a malformed repository result returned by GitHub Topic discovery");
                    continue;
                }
                JsonObject repositoryObject = repositoryElement.getAsJsonObject();
                if (isExcludedRepository(repositoryObject)) {
                    continue;
                }
                String repositoryLabel = repositoryLabel(repositoryObject);
                final String repositoryIdentity;
                try {
                    repositoryIdentity = repositoryIdentity(repositoryObject);
                } catch (IOException | IllegalArgumentException exception) {
                    skippedRepositoryCount++;
                    LOG.warning("Skipping invalid or unavailable GitHub Topic repository " + repositoryLabel);
                    continue;
                }
                if (!seenRepositoryIdentities.add(repositoryIdentity)) {
                    continue;
                }
                final DiscoveredRepository repository;
                try {
                    repository = discoverRepository(repositoryObject, repositoryIdentity);
                } catch (IOException | IllegalArgumentException exception) {
                    skippedRepositoryCount++;
                    LOG.warning("Skipping invalid or unavailable GitHub Topic repository " + repositoryLabel);
                    continue;
                }
                String pluginId = repository.entry().getId();
                if (conflictingPluginIds.contains(pluginId)) {
                    skippedRepositoryCount++;
                    LOG.warning("Excluding another GitHub Topic repository claiming ambiguous plugin ID "
                            + pluginId + ": " + repository.repositoryIdentity());
                    continue;
                }
                @Nullable DiscoveredRepository previous = candidatesByPluginId.putIfAbsent(pluginId, repository);
                if (previous != null) {
                    candidatesByPluginId.remove(pluginId);
                    conflictingPluginIds.add(pluginId);
                    skippedRepositoryCount += 2;
                    LOG.warning("Excluding ambiguous GitHub Topic plugin ID " + pluginId + " claimed by "
                            + previous.repositoryIdentity() + " and " + repository.repositoryIdentity());
                }
            }
            if (repositories.size() < 100) {
                break;
            }
        }
        List<PluginStoreRegistry.PluginStoreEntry> entries = new ArrayList<>();
        Map<String, String> manifests = new LinkedHashMap<>();
        Map<String, String> identities = new LinkedHashMap<>();
        for (DiscoveredRepository repository : candidatesByPluginId.values()) {
            entries.add(repository.entry());
            manifests.put(repository.manifestUrl(), repository.manifestContent());
            identities.put(repository.manifestUrl(), repository.repositoryIdentity());
        }
        PluginStoreRegistry registry = PluginStoreRegistry.discovered("GitHub Topic: " + topic, entries);
        registry.validate();
        return new Result(registry, manifests, identities, skippedRepositoryCount);
    }

    /// Resolves one eligible repository without publishing partial state.
    ///
    /// @param repository GitHub repository search result
    /// @param repositoryIdentity validated normalized GitHub repository identity
    /// @return fully resolved candidate
    /// @throws IOException if repository metadata or its manifest cannot be loaded
    private DiscoveredRepository discoverRepository(JsonObject repository, String repositoryIdentity) throws IOException {
        String fullName = requiredString(repository, "full_name");
        String[] identityParts = fullName.split("/", -1);
        if (identityParts.length != 2 || identityParts[0].isBlank() || identityParts[1].isBlank()) {
            throw new IOException("GitHub Topic result contains an invalid full_name");
        }
        String repositoryUrl = requiredString(repository, "html_url");
        String defaultBranch = requiredString(repository, "default_branch");
        String manifestUrl = rawBaseUrl + "/" + encodePath(identityParts[0]) + "/" + encodePath(identityParts[1])
                + "/" + encodeBranch(defaultBranch) + "/manifest.json";
        String manifestContent = fetch(manifestUrl, MAX_MANIFEST_BYTES, false);
        JsonObject manifestDocument = parseObject(manifestContent, "plugin manifest");
        JsonObject payload = manifestDocument.has("signed") && manifestDocument.get("signed").isJsonObject()
                ? manifestDocument.getAsJsonObject("signed")
                : manifestDocument;
        String pluginId = requiredString(payload, "id");
        String displayName = optionalString(repository, "name", pluginId);
        String description = optionalString(repository, "description", "");
        PluginStoreRegistry.PluginStoreEntry entry = PluginStoreRegistry.PluginStoreEntry.discovered(
                pluginId,
                displayName,
                identityParts[0],
                description,
                manifestUrl,
                repositoryUrl
        );
        return new DiscoveredRepository(entry, manifestUrl, manifestContent, repositoryIdentity);
    }

    /// Returns whether GitHub marks a repository as ineligible for Topic publication.
    ///
    /// @param repository GitHub repository search result
    /// @return whether the repository is archived, disabled, or a fork
    private static boolean isExcludedRepository(JsonObject repository) {
        return optionalBoolean(repository, "archived")
                || optionalBoolean(repository, "disabled")
                || optionalBoolean(repository, "fork");
    }

    /// Validates and normalizes the repository identity before any manifest request is made.
    ///
    /// @param repository GitHub repository search result
    /// @return normalized GitHub repository identity
    /// @throws IOException if `full_name` is missing, malformed, or disagrees with `html_url`
    private static String repositoryIdentity(JsonObject repository) throws IOException {
        String fullName = requiredString(repository, "full_name");
        String[] identityParts = fullName.split("/", -1);
        if (identityParts.length != 2 || identityParts[0].isBlank() || identityParts[1].isBlank()) {
            throw new IOException("GitHub Topic result contains an invalid full_name");
        }
        String expectedRepositoryIdentity = PluginTrustVerifier.normalizeRepository("github.com/" + fullName);
        String repositoryIdentity = PluginTrustVerifier.normalizeRepository(requiredString(repository, "html_url"));
        if (!expectedRepositoryIdentity.equals(repositoryIdentity)) {
            throw new IOException("GitHub Topic result has mismatched repository identities");
        }
        return repositoryIdentity;
    }

    /// Returns a safe normalized repository label for diagnostics without trusting descriptive API metadata.
    ///
    /// @param repository GitHub repository search result
    /// @return normalized repository identity, or a generic label when `full_name` is invalid
    private static String repositoryLabel(JsonObject repository) {
        try {
            return PluginTrustVerifier.normalizeRepository("github.com/" + requiredString(repository, "full_name"));
        } catch (IOException | IllegalArgumentException exception) {
            return "unknown GitHub repository";
        }
    }

    /// Fully resolved repository candidate ready for atomic publication.
    ///
    /// @param entry synthetic registry entry
    /// @param manifestUrl raw default-branch manifest URL
    /// @param manifestContent exact fetched manifest content
    /// @param repositoryIdentity normalized GitHub repository identity
    @NotNullByDefault
    private record DiscoveredRepository(
            PluginStoreRegistry.PluginStoreEntry entry,
            String manifestUrl,
            String manifestContent,
            String repositoryIdentity
    ) {
    }

    /// Fetches one bounded UTF-8 response and sends the token only for explicit API requests.
    private String fetch(String url, int maximumBytes, boolean apiRequest) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(15_000);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        connection.setRequestProperty("User-Agent", "HMCL-CE-Plugin-Discovery");
        if (apiRequest && token != null) {
            connection.setRequestProperty("Authorization", "Bearer " + token);
        }
        try {
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                throw new IOException("GitHub plugin discovery request failed with HTTP " + status);
            }
            long declared = connection.getContentLengthLong();
            if (declared > maximumBytes) {
                throw new IOException("GitHub plugin discovery response exceeds the byte limit");
            }
            try (InputStream input = connection.getInputStream();
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int total = 0;
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    total += read;
                    if (total > maximumBytes) {
                        throw new IOException("GitHub plugin discovery response exceeds the byte limit");
                    }
                    output.write(buffer, 0, read);
                }
                return output.toString(StandardCharsets.UTF_8);
            }
        } finally {
            connection.disconnect();
        }
    }

    /// Parses one required JSON object.
    private static JsonObject parseObject(String content, String label) throws IOException {
        try {
            JsonElement parsed = JsonParser.parseString(content);
            if (!parsed.isJsonObject()) {
                throw new IOException(label + " is not an object");
            }
            return parsed.getAsJsonObject();
        } catch (JsonParseException exception) {
            throw new IOException("Failed to parse " + label, exception);
        }
    }

    /// Reads one required string property.
    private static String requiredString(JsonObject object, String name) throws IOException {
        JsonElement value = object.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()
                || value.getAsString().isBlank()) {
            throw new IOException("GitHub plugin discovery value is missing: " + name);
        }
        return value.getAsString();
    }

    /// Reads one optional string property with a fallback.
    private static String optionalString(JsonObject object, String name, String fallback) {
        JsonElement value = object.get(name);
        return value != null && !value.isJsonNull() && value.isJsonPrimitive()
                && value.getAsJsonPrimitive().isString() ? value.getAsString() : fallback;
    }

    /// Reads one optional Boolean property.
    private static boolean optionalBoolean(JsonObject object, String name) {
        JsonElement value = object.get(name);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()
                && value.getAsBoolean();
    }

    /// Reads one required array property.
    private static JsonArray requiredArray(JsonObject object, String name) throws IOException {
        JsonElement value = object.get(name);
        if (value == null || !value.isJsonArray()) {
            throw new IOException("GitHub plugin discovery array is missing: " + name);
        }
        return value.getAsJsonArray();
    }

    /// Requires an HTTP or HTTPS absolute URL.
    private static String requireHttpUrl(String value, String label) {
        URI uri = URI.create(Objects.requireNonNull(value, "value"));
        if (!uri.isAbsolute() || uri.getHost() == null
                || !("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))) {
            throw new IllegalArgumentException(label + " must be an absolute HTTP(S) URL");
        }
        return uri.toString();
    }

    /// Encodes one query value.
    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /// Encodes one path segment.
    private static String encodePath(String value) {
        return encode(value);
    }

    /// Encodes every segment of a potentially slash-containing branch name.
    private static String encodeBranch(String branch) {
        return String.join("/", java.util.Arrays.stream(branch.split("/", -1)).map(GitHubTopicDiscovery::encodePath).toList());
    }

    /// Immutable synthetic registry plus source-bound fetched manifest state.
    ///
    /// @param registry validated synthetic Topic registry
    /// @param manifestContents prefetched manifests by raw URL
    /// @param repositoryIdentities normalized GitHub identities by raw manifest URL
    /// @param skippedRepositoryCount invalid, unavailable, or ambiguous repositories excluded from the registry
    public record Result(
            PluginStoreRegistry registry,
            @Unmodifiable Map<String, String> manifestContents,
            @Unmodifiable Map<String, String> repositoryIdentities,
            int skippedRepositoryCount
    ) {
        /// Defensively copies one discovery result.
        public Result {
            Objects.requireNonNull(registry, "registry");
            manifestContents = Map.copyOf(manifestContents);
            repositoryIdentities = Map.copyOf(repositoryIdentities);
            if (skippedRepositoryCount < 0) {
                throw new IllegalArgumentException("skippedRepositoryCount must not be negative");
            }
        }
    }
}
