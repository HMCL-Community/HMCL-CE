/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2026  huangyuhui <huanghongxun2008@126.com> and contributors
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

import org.jackhuang.hmcl.task.FileDownloadTask.IntegrityCheck;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies channel selection and integrity requirements for launcher update metadata.
@NotNullByDefault
public final class RemoteVersionTest {

    /// Selects a non-prerelease release and its exact versioned JAR asset for the stable channel.
    @Test
    public void stableSelectsNonPrereleaseExactJar() throws IOException {
        String digest = "sha256:" + "a".repeat(64);
        String response = """
                [
                  {
                    "tag_name": "v26.8-beta.3",
                    "prerelease": true,
                    "assets": [{"name":"HMCL-CE-26.8-beta.3.jar","browser_download_url":"https://example.invalid/beta.jar","digest":"%s"}]
                  },
                  {
                    "tag_name": "v26.8-release.2",
                    "prerelease": false,
                    "assets": [{"name":"HMCL-CE-26.8-release.2.jar","browser_download_url":"https://example.invalid/release.jar","digest":"%s"}]
                  }
                ]
                """.formatted(digest, digest);

        RemoteVersion result = RemoteVersion.parse(UpdateChannel.STABLE, false, response);

        assertEquals("26.8-release.2", result.version());
        assertEquals("https://example.invalid/release.jar", result.url());
        assertEquals(new IntegrityCheck("SHA-256", "a".repeat(64)), result.integrityCheck());
        assertEquals(UpdateChannel.STABLE, result.channel());
    }

    /// Selects a prerelease for development channels while retaining the requested channel metadata.
    @Test
    public void developmentSelectsPrerelease() throws IOException {
        String digest = "sha256:" + "b".repeat(64);
        String response = """
                [
                  {
                    "tag_name": "v26.9-beta.1",
                    "prerelease": true,
                    "assets": [{"name":"HMCL-CE-26.9-beta.1.jar","browser_download_url":"https://example.invalid/dev.jar","digest":"%s"}]
                  },
                  {
                    "tag_name": "v26.8-release.2",
                    "prerelease": false,
                    "assets": [{"name":"HMCL-CE-26.8-release.2.jar","browser_download_url":"https://example.invalid/stable.jar","digest":"%s"}]
                  }
                ]
                """.formatted(digest, digest);

        RemoteVersion result = RemoteVersion.parse(UpdateChannel.DEVELOPMENT, false, response);

        assertEquals("26.9-beta.1", result.version());
        assertEquals(UpdateChannel.DEVELOPMENT, result.channel());
        assertTrue(result.integrityCheck().checksum().matches("b{64}"));
    }

    /// Allows preview checks to consume the newest release regardless of prerelease status.
    @Test
    public void previewSelectsFirstEligibleRelease() throws IOException {
        String digest = "sha256:" + "c".repeat(64);
        String response = """
                [
                  {
                    "tag_name": "v26.8-beta.4",
                    "prerelease": true,
                    "assets": [{"name":"HMCL-CE-26.8-beta.4.jar","browser_download_url":"https://example.invalid/preview.jar","digest":"%s"}]
                  },
                  {
                    "tag_name": "v26.8-release.2",
                    "prerelease": false,
                    "assets": [{"name":"HMCL-CE-26.8-release.2.jar","browser_download_url":"https://example.invalid/stable.jar","digest":"%s"}]
                  }
                ]
                """.formatted(digest, digest);

        RemoteVersion result = RemoteVersion.parse(UpdateChannel.STABLE, true, response);

        assertEquals("26.8-beta.4", result.version());
        assertEquals("https://example.invalid/preview.jar", result.url());
        assertTrue(result.preview());
    }

    /// Rejects a release that has no exact JAR asset or has no GitHub SHA-256 digest.
    @Test
    public void rejectsMissingExactAssetOrDigest() {
        String missingExactAsset = """
                [{
                  "tag_name":"v26.8-release.2",
                  "prerelease":false,
                  "assets":[{"name":"HMCL-CE-26.8-release.2-extra.jar","browser_download_url":"https://example.invalid/wrong.jar","digest":"sha256:%s"}]
                }]
                """.formatted("d".repeat(64));
        String missingDigest = """
                [{
                  "tag_name":"v26.8-release.2",
                  "prerelease":false,
                  "assets":[{"name":"HMCL-CE-26.8-release.2.jar","browser_download_url":"https://example.invalid/release.jar"}]
                }]
                """;

        assertThrows(IOException.class, () -> RemoteVersion.parse(UpdateChannel.STABLE, false, missingExactAsset));
        assertThrows(IOException.class, () -> RemoteVersion.parse(UpdateChannel.STABLE, false, missingDigest));
    }

    /// Retains compatibility with the existing single-object update source response.
    @Test
    public void parsesLegacyUpdateObject() throws IOException {
        String response = """
                {"version":"3.16.3","jar":"https://example.invalid/legacy.jar","jarsha1":"%s","force":true}
                """.formatted("e".repeat(40));

        RemoteVersion result = RemoteVersion.parse(UpdateChannel.STABLE, false, response);

        assertEquals("3.16.3", result.version());
        assertEquals("https://example.invalid/legacy.jar", result.url());
        assertEquals(new IntegrityCheck("SHA-1", "e".repeat(40)), result.integrityCheck());
        assertTrue(result.force());
    }
}
