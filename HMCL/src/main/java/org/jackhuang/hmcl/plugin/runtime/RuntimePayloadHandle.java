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
package org.jackhuang.hmcl.plugin.runtime;

import org.jackhuang.hmcl.plugin.PluginManifest;
import org.jetbrains.annotations.NotNullByDefault;

/// Opaque identity returned by a provider for one loaded payload.
///
/// The handle contains no language engine object, preventing callers from crossing provider ownership boundaries.
///
/// @param ownerPluginId plugin that owns the loaded payload
/// @param providerId provider that issued and may consume the handle
/// @param payloadId provider-scoped opaque payload identifier
@NotNullByDefault
public record RuntimePayloadHandle(String ownerPluginId, String providerId, String payloadId) {
    /// Validates owner, provider, and opaque payload identities.
    public RuntimePayloadHandle {
        if (!PluginManifest.isCanonicalExecutableId(ownerPluginId)) {
            throw new IllegalArgumentException("Runtime payload owner ID must be canonical: " + ownerPluginId);
        }
        if (!PluginManifest.isCanonicalExecutableId(providerId)) {
            throw new IllegalArgumentException("Runtime provider ID must be canonical: " + providerId);
        }
        if (payloadId.isBlank()) {
            throw new IllegalArgumentException("Runtime payload ID cannot be blank");
        }
    }
}
