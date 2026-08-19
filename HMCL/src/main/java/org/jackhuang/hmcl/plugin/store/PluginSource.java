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

import org.jackhuang.hmcl.util.StringUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/// Immutable persisted configuration for one plugin registry source.
@NotNullByDefault
public final class PluginSource {
    /// Stable identifier reserved for the built-in HMCL CE source.
    public static final String OFFICIAL_ID = "official";

    /// Stable identifier reserved for the built-in GitHub Topic source.
    public static final String HMCLCE_TOPIC_ID = "github-topic-hmclce";

    /// Stable local source identifier.
    private final String id;

    /// Persisted registry URL.
    private final String url;

    /// Optional normalized local display name.
    private final @Nullable String alias;

    /// Whether the source participates in catalog aggregation.
    private final boolean enabled;

    /// Whether this source is the built-in official registry.
    private final boolean official;

    /// Creates an immutable source configuration.
    ///
    /// @param id stable source identifier
    /// @param url persisted registry URL
    /// @param alias optional local display name
    /// @param enabled whether the source participates in aggregation
    /// @param official whether the source is the built-in official registry
    public PluginSource(String id, String url, @Nullable String alias, boolean enabled, boolean official) {
        this.id = Objects.requireNonNull(id, "id");
        this.url = Objects.requireNonNull(url, "url");
        this.alias = normalizeAlias(alias);
        this.enabled = enabled;
        this.official = official;
    }

    /// Returns the stable local source identifier.
    ///
    /// @return source identifier
    public String getId() {
        return id;
    }

    /// Returns the persisted registry URL.
    ///
    /// @return registry URL
    public String getUrl() {
        return url;
    }

    /// Returns the optional normalized local display name.
    ///
    /// @return local display name, or `null` when none is configured
    public @Nullable String getAlias() {
        return alias;
    }

    /// Returns whether the source participates in catalog aggregation.
    ///
    /// @return whether the source is enabled
    public boolean isEnabled() {
        return enabled;
    }

    /// Returns whether the source is the built-in official registry.
    ///
    /// @return whether the source is official
    public boolean isOfficial() {
        return official;
    }

    /// Returns whether this source performs built-in GitHub Topic discovery.
    public boolean isGitHubTopic() {
        return HMCLCE_TOPIC_ID.equals(id);
    }

    /// Returns whether this source has a launcher-defined immutable security identity.
    public boolean isBuiltIn() {
        return official || isGitHubTopic();
    }

    /// Returns a copy with a replacement URL and alias.
    ///
    /// @param url replacement registry URL
    /// @param alias replacement optional local display name
    /// @return independently immutable source copy
    public PluginSource withConfiguration(String url, @Nullable String alias) {
        return new PluginSource(id, url, alias, enabled, official);
    }

    /// Returns a copy with replacement enablement.
    ///
    /// @param enabled replacement enablement
    /// @return independently immutable source copy
    public PluginSource withEnabled(boolean enabled) {
        return new PluginSource(id, url, alias, enabled, official);
    }

    /// Converts blank aliases to `null` and trims user-entered names.
    ///
    /// @param alias optional local display name
    /// @return normalized local display name, or `null` if blank
    private static @Nullable String normalizeAlias(@Nullable String alias) {
        return alias == null || StringUtils.isBlank(alias) ? null : alias.trim();
    }
}
