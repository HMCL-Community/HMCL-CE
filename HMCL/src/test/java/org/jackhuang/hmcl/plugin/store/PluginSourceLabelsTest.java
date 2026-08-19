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

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/// Verifies credential-safe source labels shared by plugin-store presentation and diagnostics.
@NotNullByDefault
public final class PluginSourceLabelsTest {
    /// Keeps an ordinary local alias unchanged.
    @Test
    public void preservesOrdinaryHumanAlias() {
        PluginSource source = new PluginSource(
                "source", "https://plugins.example.test/catalog.json", "Community Plugins", true, false);

        assertEquals("Community Plugins", PluginSourceLabels.displayName(source, "Registry"));
    }

    /// Replaces hostile aliases and remote registry names with a credential-free source URL fallback.
    @Test
    public void hostileLabelsFallBackWithoutCredentialsOrParameters() {
        PluginSource source = new PluginSource(
                "source",
                "https://user:secret@plugins.example.test/catalog.json?token=secret#fragment",
                "https://user:secret@host/catalog?token=secret#fragment",
                true,
                false
        );
        String label = PluginSourceLabels.displayName(
                source,
                "https://user:secret@host/catalog?token=secret#fragment"
        );

        assertEquals("plugins.example.test", label);
        assertFalse(label.contains("secret"));
        assertFalse(label.contains("token"));
        assertFalse(label.contains("?"));
        assertFalse(label.contains("#"));
        assertFalse(label.contains(source.getUrl()));
    }

    /// Never reuses an encoded final path segment as a compact source identity.
    @Test
    public void encodedSensitivePathFallsBackToHostOnlyLabel() {
        PluginSource source = new PluginSource(
                "source",
                "https://plugins.example.test/catalog/user%3Asecret%40host%3Ftoken%3Dprivate",
                null,
                true,
                false
        );

        String label = PluginSourceLabels.displayName(source, null);

        assertEquals("plugins.example.test", label);
        assertEquals("https://plugins.example.test", PluginSourceLabels.diagnosticUrl(source.getUrl()));
        assertFalse(label.contains("secret"));
        assertFalse(label.contains("token"));
        assertFalse(label.contains("%"));
    }

    /// Never exposes arbitrary plain path tokens through compact labels or diagnostics.
    @Test
    public void plainSensitivePathFallsBackToHostOnly() {
        PluginSource source = new PluginSource(
                "source",
                "https://plugins.example.test/hooks/PlainBearerSecret123",
                null,
                true,
                false
        );

        String label = PluginSourceLabels.displayName(source, null);
        String diagnostic = PluginSourceLabels.diagnosticUrl(source.getUrl());

        assertEquals("plugins.example.test", label);
        assertEquals("https://plugins.example.test", diagnostic);
        assertFalse(label.contains("PlainBearerSecret123"));
        assertFalse(diagnostic.contains("PlainBearerSecret123"));
    }
}
