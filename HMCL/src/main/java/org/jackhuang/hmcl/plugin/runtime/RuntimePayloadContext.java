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

import org.jackhuang.hmcl.plugin.PluginArtifactIdentity;
import org.jackhuang.hmcl.plugin.bridge.PluginCapabilityToken;
import org.jackhuang.hmcl.plugin.internal.VerifiedPluginPackage;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Supplier;

/// Immutable package identity and runtime-owned loading context for one external payload.
@NotNullByDefault
public final class RuntimePayloadContext {
    /// Exact package bytes approved for loading.
    private final PluginArtifactIdentity artifactIdentity;

    /// Normalized absolute path of the verified package or extracted package root.
    private final Path packagePath;

    /// Normalized runtime-owned entrypoint selected from the package.
    private final String entrypoint;

    /// Requested execution boundary.
    private final PluginExecutionMode executionMode;

    /// Normalized absolute plugin data directory.
    private final Path dataDirectory;

    /// Supplies the current opaque plugin-scoped capability authority.
    private final Supplier<PluginCapabilityToken> capabilityTokenSupplier;

    /// Creates one immutable payload-loading context.
    ///
    /// The supplier deliberately remains opaque until the language-neutral capability token contract is introduced.
    ///
    /// @param artifactIdentity exact verified plugin package identity
    /// @param packagePath verified package or extracted package root
    /// @param entrypoint runtime-owned selected entrypoint
    /// @param executionMode requested execution boundary
    /// @param dataDirectory plugin-owned data directory
    /// @param capabilityTokenSupplier supplier of the current plugin-scoped authority
    public RuntimePayloadContext(
            PluginArtifactIdentity artifactIdentity,
            Path packagePath,
            String entrypoint,
            PluginExecutionMode executionMode,
            Path dataDirectory,
            Supplier<PluginCapabilityToken> capabilityTokenSupplier) {
        validateEntrypoint(entrypoint);
        this.artifactIdentity = Objects.requireNonNull(artifactIdentity, "artifactIdentity");
        this.packagePath = packagePath.toAbsolutePath().normalize();
        this.entrypoint = entrypoint;
        this.executionMode = Objects.requireNonNull(executionMode, "executionMode");
        this.dataDirectory = dataDirectory.toAbsolutePath().normalize();
        this.capabilityTokenSupplier = Objects.requireNonNull(capabilityTokenSupplier, "capabilityTokenSupplier");
    }

    /// Returns the exact package identity approved for loading.
    public PluginArtifactIdentity artifactIdentity() {
        return artifactIdentity;
    }

    /// Returns the normalized absolute package path.
    public Path packagePath() {
        return packagePath;
    }

    /// Returns the normalized runtime-owned selected entrypoint.
    public String entrypoint() {
        return entrypoint;
    }

    /// Returns the requested execution boundary.
    public PluginExecutionMode executionMode() {
        return executionMode;
    }

    /// Returns the normalized absolute plugin data directory.
    public Path dataDirectory() {
        return dataDirectory;
    }

    /// Returns the supplier of current plugin-scoped capability authority.
    public Supplier<PluginCapabilityToken> capabilityTokenSupplier() {
        return capabilityTokenSupplier;
    }

    /// Validates one runtime-owned entrypoint against the shared package-relative path contract.
    ///
    /// @param entrypoint runtime-owned package path
    /// @throws IllegalArgumentException if the path is absolute, platform-specific, escaping, or non-normalized
    private static void validateEntrypoint(String entrypoint) {
        if (!entrypoint.equals(entrypoint.trim())) {
            throw new IllegalArgumentException("Runtime payload entrypoint must not contain outer whitespace");
        }
        try {
            VerifiedPluginPackage.parseSafeRelativePath(entrypoint);
        } catch (IOException exception) {
            throw new IllegalArgumentException("Unsafe runtime payload entrypoint: " + entrypoint, exception);
        }
    }
}
