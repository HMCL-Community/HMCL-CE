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

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies durable ordered plugin-source configuration and legacy preference migration.
@NotNullByDefault
public final class PluginSourceRepositoryTest {
    /// Installs immutable official and hmclce Topic sources for a fresh profile.
    @Test
    public void createsBuiltInOfficialAndTopicSources(@TempDir Path localHome) {
        PluginStorePreferences preferences = new PluginStorePreferences(localHome);

        assertEquals(
                List.of(PluginSource.OFFICIAL_ID, PluginSource.HMCLCE_TOPIC_ID),
                sourceIds(preferences.getSources())
        );
        assertTrue(preferences.getSources().get(0).isOfficial());
        assertTrue(preferences.getSources().get(1).isGitHubTopic());
        assertTrue(preferences.getSources().get(1).isEnabled());
        assertThrows(IllegalArgumentException.class,
                () -> preferences.removeSource(PluginSource.HMCLCE_TOPIC_ID));
    }

    /// Redacts credentials, query strings, fragments, and exception text from legacy migration diagnostics.
    @Test
    public void migrationDiagnosticRedactsSensitiveAndMalformedRegistryUrls() {
        String valid = "https://user:secret@host.example/catalog/plugins.json?token=secret#fragment";
        String malformed = "https://user:secret@host.example/%zz?token=secret#fragment";

        assertEquals("https://host.example", PluginStorePreferences.migrationDiagnostic(valid));
        assertEquals("invalid plugin registry", PluginStorePreferences.migrationDiagnostic(malformed));
        for (String diagnostic : List.of(
                PluginStorePreferences.migrationDiagnostic(valid),
                PluginStorePreferences.migrationDiagnostic(malformed)
        )) {
            assertFalse(diagnostic.contains("secret"));
            assertFalse(diagnostic.contains("token"));
            assertFalse(diagnostic.contains("?"));
            assertFalse(diagnostic.contains("#"));
        }
    }

    /// Never decodes encoded credential and query delimiters in legacy migration diagnostics.
    @Test
    public void migrationDiagnosticDoesNotDiscloseEncodedSensitivePath() {
        String encoded = "https://host.example/catalog/user%3Asecret%40host%3Ftoken%3Dprivate";

        String diagnostic = PluginStorePreferences.migrationDiagnostic(encoded);

        assertEquals("https://host.example", diagnostic);
        assertFalse(diagnostic.contains("secret"));
        assertFalse(diagnostic.contains("token"));
        assertFalse(diagnostic.contains("%"));
    }

    /// Migrates version-one sources with the old active custom source directly after the official source.
    @Test
    public void migratesVersionOneAndMovesTheOldActiveCustomSourceAfterOfficial(
            @TempDir Path localHome
    ) throws Exception {
        String first = "https://one.example/plugins.json";
        String active = "https://two.example/plugins.json";
        Files.writeString(localHome.resolve("plugin-store.json"), """
                {
                  "favoritePluginIds": ["dev.hmclce.pcltheme"],
                  "customRegistryUrls": ["%s", "%s"],
                  "activeRegistryUrl": "%s"
                }
                """.formatted(first, active, active));

        PluginStorePreferences preferences = new PluginStorePreferences(localHome);

        assertEquals(4, preferences.getSources().size());
        assertEquals(PluginSource.OFFICIAL_ID, preferences.getSources().get(0).getId());
        assertEquals(active, preferences.getSources().get(1).getUrl());
        assertEquals(PluginSource.HMCLCE_TOPIC_ID, preferences.getSources().get(2).getId());
        assertEquals(first, preferences.getSources().get(3).getUrl());
        assertTrue(preferences.isFavorite("dev.hmclce.pcltheme"));
        assertEquals(2, readSavedState(localHome).get("schemaVersion").getAsInt());
    }

    /// Rejects an ABA-restored source configuration while leaving its revision unchanged for favorite updates.
    @Test
    public void sourceRevisionRejectsRestoredValuesButIgnoresFavoriteUpdates(@TempDir Path localHome) throws Exception {
        PluginStorePreferences preferences = new PluginStorePreferences(localHome, () -> "source_guarded");
        PluginSource custom = preferences.addSource("https://one.example/plugins.json", "One");
        PluginSourceConfiguration expected = preferences.getSourceConfiguration();
        AtomicInteger publications = new AtomicInteger();

        preferences.executeIfSourcesMatch(expected, publications::incrementAndGet);
        preferences.setEnabled(custom.getId(), true);
        assertEquals(expected.getRevision(), preferences.getSourceConfiguration().getRevision());
        preferences.setFavorite("dev.hmclce.favorite", true);
        assertEquals(expected.getRevision(), preferences.getSourceConfiguration().getRevision());
        preferences.executeIfSourcesMatch(expected, publications::incrementAndGet);
        assertEquals(2, publications.get());

        preferences.setEnabled(custom.getId(), false);
        assertThrows(IOException.class, () -> preferences.executeIfSourcesMatch(
                expected,
                publications::incrementAndGet
        ));
        preferences.setEnabled(custom.getId(), true);
        PluginSourceConfiguration restored = preferences.getSourceConfiguration();
        assertTrue(PluginStoreSnapshot.matchesSourceConfigurations(expected.getSources(), restored.getSources()));
        assertTrue(restored.getRevision() > expected.getRevision());
        assertThrows(IOException.class, () -> preferences.executeIfSourcesMatch(
                expected,
                publications::incrementAndGet
        ));
        assertEquals(2, publications.get());
    }

    /// Preserves version-two source IDs, aliases, enablement, and priority through a reload.
    @Test
    public void roundTripsVersionTwoSourcesAndFavorites(@TempDir Path localHome) throws Exception {
        AtomicInteger generatedIds = new AtomicInteger();
        PluginStorePreferences preferences = new PluginStorePreferences(
                localHome,
                () -> "source_test" + generatedIds.incrementAndGet()
        );
        PluginSource first = preferences.addSource("https://one.example/plugins.json", "One");
        PluginSource second = preferences.addSource("https://two.example/plugins.json", null);
        preferences.updateSource(first.getId(), "https://one.example/changed.json", "Changed");
        preferences.setEnabled(first.getId(), false);
        preferences.reorder(List.of(
                second.getId(), PluginSource.OFFICIAL_ID, PluginSource.HMCLCE_TOPIC_ID, first.getId()
        ));
        preferences.setFavorite("dev.hmclce.pcltheme", true);

        PluginStorePreferences reloaded = new PluginStorePreferences(localHome);
        List<PluginSource> sources = reloaded.getSources();

        assertEquals(
                List.of(second.getId(), PluginSource.OFFICIAL_ID, PluginSource.HMCLCE_TOPIC_ID, first.getId()),
                sourceIds(sources)
        );
        PluginSource reloadedFirst = sources.get(3);
        assertEquals("https://one.example/changed.json", reloadedFirst.getUrl());
        assertEquals("Changed", reloadedFirst.getAlias());
        assertFalse(reloadedFirst.isEnabled());
        assertEquals(Set.of("dev.hmclce.pcltheme"), reloaded.getFavoritePluginIds());
        JsonObject state = readSavedState(localHome);
        assertEquals(2, state.get("schemaVersion").getAsInt());
        assertFalse(state.has("customRegistryUrls"));
        assertFalse(state.has("activeRegistryUrl"));
    }

    /// Rejects duplicate source URLs after canonical scheme, host, port, and path normalization.
    @Test
    public void rejectsCanonicalDuplicateUrls(@TempDir Path localHome) throws Exception {
        PluginStorePreferences preferences = new PluginStorePreferences(localHome);
        preferences.addSource("https://EXAMPLE.com:443", "One");

        assertThrows(
                IllegalArgumentException.class,
                () -> preferences.addSource("https://example.com/", "Duplicate")
        );
    }

    /// Keeps a custom source's identity and list position when its URL changes.
    @Test
    public void retainsSourceIdAndPositionWhenEditingUrl(@TempDir Path localHome) throws Exception {
        PluginStorePreferences preferences = new PluginStorePreferences(localHome);
        PluginSource first = preferences.addSource("https://one.example/plugins.json", "One");
        PluginSource second = preferences.addSource("https://two.example/plugins.json", "Two");

        PluginSource updated = preferences.updateSource(
                first.getId(),
                "https://one.example/replacement.json",
                "Replacement"
        );

        assertEquals(first.getId(), updated.getId());
        assertEquals(
                List.of(PluginSource.OFFICIAL_ID, PluginSource.HMCLCE_TOPIC_ID, first.getId(), second.getId()),
                sourceIds(preferences.getSources())
        );
        assertEquals("https://one.example/replacement.json", preferences.getSources().get(2).getUrl());
    }

    /// Rejects removal and URL edits for the built-in official source.
    @Test
    public void rejectsOfficialDeletionAndUrlModification(@TempDir Path localHome) {
        PluginStorePreferences preferences = new PluginStorePreferences(localHome);

        assertThrows(IllegalArgumentException.class,
                () -> preferences.removeSource(PluginSource.OFFICIAL_ID));
        assertThrows(IllegalArgumentException.class,
                () -> preferences.updateSource(
                        PluginSource.OFFICIAL_ID,
                        "https://example.org/plugins.json",
                        null
                ));
    }

    /// Allows the official source to be disabled without allowing its identity to be removed.
    @Test
    public void allowsOfficialSourceToBeDisabled(@TempDir Path localHome) throws Exception {
        PluginStorePreferences preferences = new PluginStorePreferences(localHome);

        PluginSource disabled = preferences.setEnabled(PluginSource.OFFICIAL_ID, false);

        assertFalse(disabled.isEnabled());
        assertFalse(preferences.getSources().get(0).isEnabled());
        assertTrue(preferences.getSources().get(0).isOfficial());
    }

    /// Updates only a custom source alias while retaining its existing URL and stable ID.
    @Test
    public void updatesCustomSourceAliasWithoutChangingItsUrl(@TempDir Path localHome) throws Exception {
        PluginStorePreferences preferences = new PluginStorePreferences(localHome);
        PluginSource source = preferences.addSource("https://one.example/plugins.json", "One");

        PluginSource updated = preferences.updateAlias(source.getId(), "Renamed");

        assertEquals(source.getId(), updated.getId());
        assertEquals(source.getUrl(), updated.getUrl());
        assertEquals("Renamed", updated.getAlias());
    }

    /// Removes a custom source from the persisted source snapshot.
    @Test
    public void removesCustomSourceFromPersistedSnapshot(@TempDir Path localHome) throws Exception {
        PluginStorePreferences preferences = new PluginStorePreferences(localHome);
        PluginSource source = preferences.addSource("https://one.example/plugins.json", null);

        preferences.removeSource(source.getId());

        assertEquals(
                List.of(PluginSource.OFFICIAL_ID, PluginSource.HMCLCE_TOPIC_ID),
                sourceIds(new PluginStorePreferences(localHome).getSources())
        );
    }

    /// Requires a reorder request to contain every current source ID exactly once.
    @Test
    public void requiresExactMembershipWhenReordering(@TempDir Path localHome) throws Exception {
        PluginStorePreferences preferences = new PluginStorePreferences(localHome);
        PluginSource custom = preferences.addSource("https://one.example/plugins.json", null);
        List<String> expectedIds = List.of(
                PluginSource.OFFICIAL_ID, PluginSource.HMCLCE_TOPIC_ID, custom.getId()
        );

        assertThrows(IllegalArgumentException.class,
                () -> preferences.reorder(List.of(PluginSource.OFFICIAL_ID, custom.getId())));
        assertThrows(IllegalArgumentException.class,
                () -> preferences.reorder(List.of(
                        PluginSource.OFFICIAL_ID, PluginSource.HMCLCE_TOPIC_ID, PluginSource.OFFICIAL_ID
                )));
        assertThrows(IllegalArgumentException.class,
                () -> preferences.reorder(List.of(
                        PluginSource.OFFICIAL_ID, PluginSource.HMCLCE_TOPIC_ID, "unknown"
                )));
        assertEquals(expectedIds, sourceIds(preferences.getSources()));
    }

    /// Preserves the previous file and source snapshot when a temporary-file write fails.
    @Test
    public void failedWriteLeavesDiskAndMemoryUnchanged(@TempDir Path localHome) throws Exception {
        PluginStorePreferences preferences = new PluginStorePreferences(localHome);
        PluginSource existing = preferences.addSource("https://one.example/plugins.json", "One");
        String before = Files.readString(localHome.resolve("plugin-store.json"));
        Files.createDirectory(localHome.resolve("plugin-store.json.tmp"));

        assertThrows(IOException.class,
                () -> preferences.setEnabled(existing.getId(), false));
        assertEquals(before, Files.readString(localHome.resolve("plugin-store.json")));
        assertTrue(preferences.getSources().stream()
                .filter(source -> source.getId().equals(existing.getId()))
                .findFirst()
                .orElseThrow()
                .isEnabled());
    }

    /// Falls back to the built-in official and Topic sources after a malformed preference document.
    @Test
    public void fallsBackToOfficialSourceForMalformedJson(@TempDir Path localHome) throws Exception {
        Files.writeString(localHome.resolve("plugin-store.json"), "{ malformed");

        PluginStorePreferences preferences = new PluginStorePreferences(localHome);

        assertEquals(
                List.of(PluginSource.OFFICIAL_ID, PluginSource.HMCLCE_TOPIC_ID),
                sourceIds(preferences.getSources())
        );
        assertTrue(preferences.getFavoritePluginIds().isEmpty());
    }

    /// Recovers valid favorites and sources while skipping malformed collection entries.
    @Test
    public void recoversValidEntriesFromPartiallyMalformedVersionTwoState(@TempDir Path localHome) throws Exception {
        Files.writeString(localHome.resolve("plugin-store.json"), """
                {
                  "schemaVersion": 2,
                  "favoritePluginIds": ["dev.hmclce.valid", null, "invalid favorite id"],
                  "sources": [
                    {
                      "id": "official",
                      "url": "%s",
                      "alias": null,
                      "enabled": true,
                      "official": true
                    },
                    {
                      "id": "source_valid",
                      "url": "https://valid.example/plugins.json",
                      "alias": "Valid",
                      "enabled": true,
                      "official": false
                    },
                    {
                      "id": "source_invalid",
                      "url": "not a URL",
                      "alias": "Invalid",
                      "enabled": true,
                      "official": false
                    }
                  ]
                }
                """.formatted(PluginStoreManager.DEFAULT_REGISTRY_URL));

        PluginStorePreferences preferences = new PluginStorePreferences(localHome);

        assertEquals(Set.of("dev.hmclce.valid"), preferences.getFavoritePluginIds());
        assertEquals(
                List.of(PluginSource.OFFICIAL_ID, PluginSource.HMCLCE_TOPIC_ID, "source_valid"),
                sourceIds(preferences.getSources())
        );
    }

    /// Leaves the version-one document unchanged when migration cannot atomically publish version two.
    @Test
    public void preservesVersionOneFileWhenMigrationCannotPublishVersionTwo(
            @TempDir Path localHome
    ) throws Exception {
        Path stateFile = localHome.resolve("plugin-store.json");
        String versionOne = """
                {
                  "favoritePluginIds": ["dev.hmclce.pcltheme"],
                  "customRegistryUrls": ["https://one.example/plugins.json"]
                }
                """;
        Files.writeString(stateFile, versionOne);
        Files.createDirectory(localHome.resolve("plugin-store.json.tmp"));

        PluginStorePreferences preferences = new PluginStorePreferences(localHome);

        assertEquals(versionOne, Files.readString(stateFile));
        assertTrue(preferences.isFavorite("dev.hmclce.pcltheme"));
        assertEquals(3, preferences.getSources().size());
    }

    /// Returns the persisted JSON object for assertions about the schema written to disk.
    ///
    /// @param localHome launcher-local home containing the state file
    /// @return parsed saved state
    /// @throws IOException if the state file cannot be read
    private static JsonObject readSavedState(Path localHome) throws IOException {
        return JsonParser.parseString(Files.readString(localHome.resolve("plugin-store.json"))).getAsJsonObject();
    }

    /// Extracts source IDs in persisted priority order.
    ///
    /// @param sources source snapshot
    /// @return ordered source IDs
    private static @Unmodifiable List<String> sourceIds(List<PluginSource> sources) {
        return sources.stream().map(PluginSource::getId).toList();
    }
}
