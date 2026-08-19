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
package org.jackhuang.hmcl.plugin.store;

import org.jackhuang.hmcl.plugin.trust.PluginTrustResult;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/// Combines one registry entry with its resolved repository manifest.
@NotNullByDefault
public final class PluginStoreItem {
    /// Immutable source configuration that produced this catalog entry.
    private final PluginSource source;

    /// Validated registry that published this catalog entry.
    private final PluginStoreRegistry registry;

    /// Source-scoped client retaining the entry's trusted transport and manifest caches.
    private final PluginStoreManager sourceManager;

    /// Registry metadata used for listing and search.
    private final PluginStoreRegistry.PluginStoreEntry entry;

    /// Resolved repository manifest, or `null` when that repository is temporarily unavailable.
    private final @Nullable PluginStoreManifest manifest;

    /// Opaque source-context token used by the producing manager for source-bound cache operations.
    private final @Nullable PluginStoreManager.SourceContext sourceContext;

    /// Fallback trust used only when no exact resolved version is available.
    private final PluginTrustResult trust;

    /// Creates a resolved or partially resolved store item without exposing manager cache state.
    ///
    /// @param source immutable source configuration
    /// @param registry validated registry that published the entry
    /// @param sourceManager source-scoped manager retaining resolved remote state
    /// @param entry registry entry
    /// @param manifest repository manifest or `null`
    public PluginStoreItem(
            PluginSource source,
            PluginStoreRegistry registry,
            PluginStoreManager sourceManager,
            PluginStoreRegistry.PluginStoreEntry entry,
            @Nullable PluginStoreManifest manifest
    ) {
        this(source, registry, sourceManager, entry, manifest, null, PluginTrustResult.community());
    }

    /// Creates a resolved or partial item with an explicit trust result.
    public PluginStoreItem(
            PluginSource source,
            PluginStoreRegistry registry,
            PluginStoreManager sourceManager,
            PluginStoreRegistry.PluginStoreEntry entry,
            @Nullable PluginStoreManifest manifest,
            PluginTrustResult trust
    ) {
        this(source, registry, sourceManager, entry, manifest, null, trust);
    }

    /// Creates a resolved or partially resolved store item for one manager-internal source context.
    ///
    /// @param source immutable source configuration
    /// @param registry validated registry that published the entry
    /// @param sourceManager source-scoped manager retaining resolved remote state
    /// @param entry registry entry
    /// @param manifest repository manifest or `null`
    /// @param sourceContext producing manager's opaque source-context token
    PluginStoreItem(
            PluginSource source,
            PluginStoreRegistry registry,
            PluginStoreManager sourceManager,
            PluginStoreRegistry.PluginStoreEntry entry,
            @Nullable PluginStoreManifest manifest,
            @Nullable PluginStoreManager.SourceContext sourceContext,
            PluginTrustResult trust
    ) {
        this.source = source;
        this.registry = registry;
        this.sourceManager = sourceManager;
        this.entry = entry;
        this.manifest = manifest;
        this.sourceContext = sourceContext;
        this.trust = trust;
    }

    /// Returns the immutable source configuration that produced this entry.
    ///
    /// @return producing source
    public PluginSource getSource() {
        return source;
    }

    /// Returns the validated registry that published this entry.
    ///
    /// @return producing registry
    public PluginStoreRegistry getRegistry() {
        return registry;
    }

    /// Returns the source-scoped manager that resolved this entry.
    ///
    /// @return producing manager
    public PluginStoreManager getSourceManager() {
        return sourceManager;
    }

    /// Returns the producing source context for package-local source-bound operations.
    ///
    /// @return producing source context, or `null` for externally constructed compatibility items
    @Nullable PluginStoreManager.SourceContext getSourceContext() {
        return sourceContext;
    }

    /// Returns registry metadata.
    ///
    /// @return registry entry
    public PluginStoreRegistry.PluginStoreEntry getEntry() {
        return entry;
    }

    /// Returns the repository manifest when it resolved successfully.
    ///
    /// @return repository manifest or `null`
    public @Nullable PluginStoreManifest getManifest() {
        return manifest;
    }

    /// Returns trust for the latest published version, or the fallback result for an unresolved item.
    ///
    /// Catalog conflict ranking uses this latest-version projection only. Installation and dependency resolution
    /// must call [#getTrust(PluginStoreManifest.PluginVersionEntry)] with the exact selected version.
    public PluginTrustResult getTrust() {
        @Nullable PluginStoreManifest.PluginVersionEntry latest = getLatestVersion();
        return latest == null ? trust : latest.getTrust();
    }

    /// Returns the trust result bound to one exact version from this item's manifest.
    ///
    /// @param version selected version metadata
    /// @return exact version trust, or fallback trust for unresolved compatibility items
    public PluginTrustResult getTrust(PluginStoreManifest.PluginVersionEntry version) {
        return manifest == null ? trust : version.getTrust();
    }

    /// Returns the greatest published version from the repository manifest.
    ///
    /// @return latest version or `null`
    public @Nullable PluginStoreManifest.PluginVersionEntry getLatestVersion() {
        return manifest == null ? null : manifest.getLatestVersion();
    }
}
