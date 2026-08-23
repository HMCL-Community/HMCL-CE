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
package org.jackhuang.hmcl;

import org.jackhuang.hmcl.plugin.store.PluginStoreManager;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// Verifies the launcher exposes its current CE distribution identity.
@NotNullByDefault
public final class CeBrandingTest {
    /// Ensures both short and full product names use the CE brand.
    @Test
    public void exposesCeProductName() {
        assertEquals("HMCL CE", Metadata.NAME);
        assertEquals("HMCL CE", Metadata.FULL_NAME);
    }

    /// Ensures Windows treats HMCL CE as distinct from the upstream launcher on the taskbar.
    @Test
    public void exposesIndependentWindowsTaskbarIdentity() {
        assertEquals("org.hmclcommunity.hmclce", Metadata.WINDOWS_APP_USER_MODEL_ID);
    }

    /// Ensures every CE-owned launcher endpoint targets the HMCL Community repositories.
    @Test
    public void exposesHmclCommunityEndpoints() {
        assertEquals("https://github.com/HMCL-Community/HMCL-CE", Metadata.PUBLISH_URL);
        assertEquals("https://github.com/HMCL-Community/HMCL-CE/releases", Metadata.DOWNLOAD_URL);
        assertEquals("https://api.github.com/repos/HMCL-Community/HMCL-CE/releases",
                Metadata.GITHUB_RELEASES_API_URL);
        assertEquals("https://github.com/HMCL-Community/HMCL-CE/releases/latest", Metadata.MANUAL_UPDATE_URL);
        assertEquals("https://github.com/HMCL-Community/HMCL-CE/issues/new/choose", Metadata.CONTACT_URL);
        assertEquals("https://raw.githubusercontent.com/HMCL-Community/HMCL-CE-Plugin-Store/main/plugins.json",
                PluginStoreManager.DEFAULT_REGISTRY_URL);
    }
}
