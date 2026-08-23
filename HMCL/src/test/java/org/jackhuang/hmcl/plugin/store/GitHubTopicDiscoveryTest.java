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

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/// Verifies bounded GitHub Topic discovery and credential isolation.
@NotNullByDefault
public final class GitHubTopicDiscoveryTest {
    /// Discovers a default-branch manifest while sending authorization only to the API request.
    @Test
    public void discoversHmclceTopicWithoutLeakingToken() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> apiQuery = new AtomicReference<>();
        AtomicReference<String> apiAuthorization = new AtomicReference<>();
        AtomicReference<String> rawAuthorization = new AtomicReference<>();
        server.createContext("/search/repositories", exchange -> {
            apiQuery.set(exchange.getRequestURI().getRawQuery());
            apiAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, """
                    {
                      "total_count": 1,
                      "items": [{
                        "full_name": "Example/Plugin",
                        "name": "Plugin",
                        "description": "Example plugin",
                        "html_url": "https://github.com/Example/Plugin",
                        "default_branch": "stable/release",
                        "archived": false,
                        "disabled": false,
                        "fork": false
                      }]
                    }
                    """);
        });
        server.createContext("/raw/Example/Plugin/stable/release/manifest.json", exchange -> {
            rawAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, """
                    {"schemaVersion":2,"id":"dev.example.plugin","versions":[]}
                    """);
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            GitHubTopicDiscovery discovery = new GitHubTopicDiscovery(
                    base + "/search/repositories",
                    base + "/raw",
                    "hmclce",
                    "test-token",
                    2
            );

            GitHubTopicDiscovery.Result result = discovery.discover();

            assertEquals(1, result.registry().getPlugins().size());
            PluginStoreRegistry.PluginStoreEntry entry = result.registry().getPlugins().get(0);
            assertEquals("dev.example.plugin", entry.getId());
            assertEquals("https://github.com/Example/Plugin", entry.getRepository());
            assertEquals("github.com/example/plugin", result.repositoryIdentities().get(entry.getManifestUrl()));
            assertEquals(0, result.skippedRepositoryCount());
            assertEquals("Bearer test-token", apiAuthorization.get());
            assertNull(rawAuthorization.get());
            assertFalse(apiQuery.get().contains("HMCLCE"));
            assertEquals("q=topic%3Ahmclce&per_page=100&page=1", apiQuery.get());
        } finally {
            server.stop(0);
        }
    }

    /// Fetches a repository manifest only once when GitHub repeats the repository across result pages.
    @Test
    public void deduplicatesRepositoryIdentityBeforeFetchingManifest() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger rawRequests = new AtomicInteger();
        String archivedRepositories = IntStream.range(0, 99)
                .mapToObj(index -> """
                        ,{
                          "full_name": "Example/Archived%d",
                          "html_url": "https://github.com/Example/Archived%d",
                          "archived": true
                        }
                        """.formatted(index, index))
                .collect(Collectors.joining());
        server.createContext("/search/repositories", exchange -> {
            if (exchange.getRequestURI().getRawQuery().endsWith("page=1")) {
                respond(exchange, """
                        {
                          "total_count": 101,
                          "items": [{
                            "full_name": "Example/Repeated",
                            "name": "Repeated",
                            "html_url": "https://github.com/Example/Repeated",
                            "default_branch": "main"
                          }%s]
                        }
                        """.formatted(archivedRepositories));
            } else {
                respond(exchange, """
                        {
                          "total_count": 101,
                          "items": [{
                            "full_name": "Example/Repeated",
                            "name": "Repeated",
                            "html_url": "https://github.com/Example/Repeated",
                            "default_branch": "main"
                          }]
                        }
                        """);
            }
        });
        server.createContext("/raw/Example/Repeated/main/manifest.json", exchange -> {
            if (rawRequests.incrementAndGet() == 1) {
                respond(exchange, """
                        {"schemaVersion":2,"id":"dev.example.repeated","versions":[]}
                        """);
            } else {
                exchange.sendResponseHeaders(500, -1);
                exchange.close();
            }
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            GitHubTopicDiscovery discovery = new GitHubTopicDiscovery(
                    base + "/search/repositories",
                    base + "/raw",
                    "hmclce",
                    null,
                    2
            );

            GitHubTopicDiscovery.Result result = discovery.discover();

            assertEquals(1, rawRequests.get());
            assertEquals(1, result.registry().getPlugins().size());
            assertEquals("dev.example.repeated", result.registry().getPlugins().get(0).getId());
            assertEquals(1, result.manifestContents().size());
            assertEquals(1, result.repositoryIdentities().size());
            assertEquals(0, result.skippedRepositoryCount());
        } finally {
            server.stop(0);
        }
    }

    /// Skips an unavailable repository manifest while retaining later valid Topic results.
    @Test
    public void skipsUnavailableRepositoryAndContinuesDiscovery() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/search/repositories", exchange -> respond(exchange, """
                {
                  "total_count": 2,
                  "items": [{
                    "full_name": "Example/Missing",
                    "name": "Missing",
                    "description": "Repository without a manifest",
                    "html_url": "https://github.com/Example/Missing",
                    "default_branch": "main",
                    "archived": false,
                    "disabled": false,
                    "fork": false
                  }, {
                    "full_name": "Example/Available",
                    "name": "Available",
                    "description": "Repository with a valid manifest",
                    "html_url": "https://github.com/Example/Available",
                    "default_branch": "main",
                    "archived": false,
                    "disabled": false,
                    "fork": false
                  }]
                }
                """));
        server.createContext("/raw/Example/Available/main/manifest.json", exchange -> respond(exchange, """
                {"schemaVersion":2,"id":"dev.example.available","versions":[]}
                """));
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            GitHubTopicDiscovery discovery = new GitHubTopicDiscovery(
                    base + "/search/repositories",
                    base + "/raw",
                    "hmclce",
                    null,
                    1
            );

            GitHubTopicDiscovery.Result result = discovery.discover();

            assertEquals(1, result.registry().getPlugins().size());
            PluginStoreRegistry.PluginStoreEntry entry = result.registry().getPlugins().get(0);
            assertEquals("dev.example.available", entry.getId());
            assertEquals(1, result.manifestContents().size());
            assertEquals(1, result.repositoryIdentities().size());
            assertEquals("github.com/example/available", result.repositoryIdentities().get(entry.getManifestUrl()));
            assertEquals(1, result.skippedRepositoryCount());
        } finally {
            server.stop(0);
        }
    }

    /// Skips an invalid repository identity without publishing partially resolved state.
    @Test
    public void skipsInvalidRepositoryIdentityAtomically() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/search/repositories", exchange -> respond(exchange, """
                {
                  "total_count": 2,
                  "items": [{
                    "full_name": "Example/InvalidIdentity",
                    "name": "Invalid identity",
                    "description": "Repository with an invalid identity",
                    "html_url": "https://example.com/Example/InvalidIdentity",
                    "default_branch": "main",
                    "archived": false,
                    "disabled": false,
                    "fork": false
                  }, {
                    "full_name": "Example/Available",
                    "name": "Available",
                    "description": "Repository with a valid identity",
                    "html_url": "https://github.com/Example/Available",
                    "default_branch": "main",
                    "archived": false,
                    "disabled": false,
                    "fork": false
                  }]
                }
                """));
        server.createContext("/raw/Example/InvalidIdentity/main/manifest.json", exchange -> respond(exchange, """
                {"schemaVersion":2,"id":"dev.example.invalid-identity","versions":[]}
                """));
        server.createContext("/raw/Example/Available/main/manifest.json", exchange -> respond(exchange, """
                {"schemaVersion":2,"id":"dev.example.available","versions":[]}
                """));
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            GitHubTopicDiscovery discovery = new GitHubTopicDiscovery(
                    base + "/search/repositories",
                    base + "/raw",
                    "hmclce",
                    null,
                    1
            );

            GitHubTopicDiscovery.Result result = discovery.discover();

            assertEquals(1, result.registry().getPlugins().size());
            PluginStoreRegistry.PluginStoreEntry entry = result.registry().getPlugins().get(0);
            assertEquals("dev.example.available", entry.getId());
            assertEquals(1, result.manifestContents().size());
            assertEquals(1, result.repositoryIdentities().size());
            assertEquals("github.com/example/available", result.repositoryIdentities().get(entry.getManifestUrl()));
            assertEquals(1, result.skippedRepositoryCount());
        } finally {
            server.stop(0);
        }
    }

    /// Rejects mismatched API identities before they can hide the genuine repository result.
    @Test
    public void rejectsMismatchedFullNameAndRepositoryUrl() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/search/repositories", exchange -> respond(exchange, """
                {
                  "total_count": 2,
                  "items": [{
                    "full_name": "Example/SpoofedContent",
                    "name": "Spoofed content",
                    "description": "Content attributed to another repository",
                    "html_url": "https://github.com/Example/Available",
                    "default_branch": "main",
                    "archived": false,
                    "disabled": false,
                    "fork": false
                  }, {
                    "full_name": "Example/Available",
                    "name": "Available",
                    "description": "Repository with matching API identities",
                    "html_url": "https://github.com/Example/Available",
                    "default_branch": "main",
                    "archived": false,
                    "disabled": false,
                    "fork": false
                  }]
                }
                """));
        server.createContext("/raw/Example/SpoofedContent/main/manifest.json", exchange -> respond(exchange, """
                {"schemaVersion":2,"id":"dev.example.spoofed","versions":[]}
                """));
        server.createContext("/raw/Example/Available/main/manifest.json", exchange -> respond(exchange, """
                {"schemaVersion":2,"id":"dev.example.available","versions":[]}
                """));
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            GitHubTopicDiscovery discovery = new GitHubTopicDiscovery(
                    base + "/search/repositories",
                    base + "/raw",
                    "hmclce",
                    null,
                    1
            );

            GitHubTopicDiscovery.Result result = discovery.discover();

            assertEquals(1, result.registry().getPlugins().size());
            PluginStoreRegistry.PluginStoreEntry entry = result.registry().getPlugins().get(0);
            assertEquals("dev.example.available", entry.getId());
            assertEquals(1, result.manifestContents().size());
            assertEquals(1, result.repositoryIdentities().size());
            assertEquals(
                    "{\"schemaVersion\":2,\"id\":\"dev.example.available\",\"versions\":[]}",
                    result.manifestContents().get(entry.getManifestUrl()).trim()
            );
            assertEquals("github.com/example/available", result.repositoryIdentities().get(entry.getManifestUrl()));
            assertEquals(1, result.skippedRepositoryCount());
        } finally {
            server.stop(0);
        }
    }

    /// Skips a repository whose manifest declares an invalid plugin ID.
    @Test
    public void skipsInvalidPluginIdAndContinuesDiscovery() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/search/repositories", exchange -> respond(exchange, """
                {
                  "total_count": 2,
                  "items": [{
                    "full_name": "Example/InvalidId",
                    "name": "Invalid ID",
                    "description": "Repository with an invalid plugin ID",
                    "html_url": "https://github.com/Example/InvalidId",
                    "default_branch": "main",
                    "archived": false,
                    "disabled": false,
                    "fork": false
                  }, {
                    "full_name": "Example/Available",
                    "name": "Available",
                    "description": "Repository with a valid plugin ID",
                    "html_url": "https://github.com/Example/Available",
                    "default_branch": "main",
                    "archived": false,
                    "disabled": false,
                    "fork": false
                  }]
                }
                """));
        server.createContext("/raw/Example/InvalidId/main/manifest.json", exchange -> respond(exchange, """
                {"schemaVersion":2,"id":"invalid plugin id","versions":[]}
                """));
        server.createContext("/raw/Example/Available/main/manifest.json", exchange -> respond(exchange, """
                {"schemaVersion":2,"id":"dev.example.available","versions":[]}
                """));
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            GitHubTopicDiscovery discovery = new GitHubTopicDiscovery(
                    base + "/search/repositories",
                    base + "/raw",
                    "hmclce",
                    null,
                    1
            );

            GitHubTopicDiscovery.Result result = discovery.discover();

            assertEquals(1, result.registry().getPlugins().size());
            PluginStoreRegistry.PluginStoreEntry entry = result.registry().getPlugins().get(0);
            assertEquals("dev.example.available", entry.getId());
            assertEquals(1, result.manifestContents().size());
            assertEquals(1, result.repositoryIdentities().size());
            assertEquals(1, result.skippedRepositoryCount());
        } finally {
            server.stop(0);
        }
    }

    /// Excludes every repository claiming an ambiguous plugin ID while retaining unique plugins.
    @Test
    public void excludesAmbiguousPluginIdAndContinuesDiscovery() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/search/repositories", exchange -> respond(exchange, """
                {
                  "total_count": 3,
                  "items": [{
                    "full_name": "Example/CollisionOne",
                    "name": "Collision one",
                    "description": "First repository claiming the ambiguous ID",
                    "html_url": "https://github.com/Example/CollisionOne",
                    "default_branch": "main",
                    "archived": false,
                    "disabled": false,
                    "fork": false
                  }, {
                    "full_name": "Example/CollisionTwo",
                    "name": "Collision two",
                    "description": "Second repository claiming the ambiguous ID",
                    "html_url": "https://github.com/Example/CollisionTwo",
                    "default_branch": "main",
                    "archived": false,
                    "disabled": false,
                    "fork": false
                  }, {
                    "full_name": "Example/Available",
                    "name": "Available",
                    "description": "Repository with a unique plugin ID",
                    "html_url": "https://github.com/Example/Available",
                    "default_branch": "main",
                    "archived": false,
                    "disabled": false,
                    "fork": false
                  }]
                }
                """));
        server.createContext("/raw/Example/CollisionOne/main/manifest.json", exchange -> respond(exchange, """
                {"schemaVersion":2,"id":"dev.example.collision","versions":[]}
                """));
        server.createContext("/raw/Example/CollisionTwo/main/manifest.json", exchange -> respond(exchange, """
                {"schemaVersion":2,"id":"dev.example.collision","versions":[]}
                """));
        server.createContext("/raw/Example/Available/main/manifest.json", exchange -> respond(exchange, """
                {"schemaVersion":2,"id":"dev.example.available","versions":[]}
                """));
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            GitHubTopicDiscovery discovery = new GitHubTopicDiscovery(
                    base + "/search/repositories",
                    base + "/raw",
                    "hmclce",
                    null,
                    1
            );

            GitHubTopicDiscovery.Result result = discovery.discover();

            assertEquals(1, result.registry().getPlugins().size());
            PluginStoreRegistry.PluginStoreEntry entry = result.registry().getPlugins().get(0);
            assertEquals("dev.example.available", entry.getId());
            assertEquals(1, result.manifestContents().size());
            assertEquals(1, result.repositoryIdentities().size());
            assertEquals(2, result.skippedRepositoryCount());
        } finally {
            server.stop(0);
        }
    }

    /// Writes one compact UTF-8 fixture response.
    private static void respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(200, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
