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
import java.util.concurrent.atomic.AtomicReference;

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
            assertEquals("Bearer test-token", apiAuthorization.get());
            assertNull(rawAuthorization.get());
            assertFalse(apiQuery.get().contains("HMCLCE"));
            assertEquals("q=topic%3Ahmclce&per_page=100&page=1", apiQuery.get());
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
