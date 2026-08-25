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
import org.jackhuang.hmcl.plugin.PluginPatchDeclaration;
import org.jackhuang.hmcl.plugin.PluginPermission;
import org.jackhuang.hmcl.plugin.bridge.PluginCapabilityToken;
import org.jackhuang.hmcl.plugin.bridge.PluginPermissionAuthority;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/// Reserves external Patch registration while Stage 1 has no JVM bytecode Patch engine.
///
/// The endpoint validates exact manifest declaration, payload ownership, lifecycle-session authority, and current
/// permission before returning the stable fail-closed status consumed by the Stage 2 Patch engine integration.
@NotNullByDefault
public final class RuntimePatchEndpoint {
    /// Exact external payload package identity.
    private final PluginArtifactIdentity artifactIdentity;

    /// External payload execution boundary.
    private final PluginExecutionMode executionMode;

    /// Launcher-owned authority which verifies every registration token.
    private final PluginPermissionAuthority permissionAuthority;

    /// Issues a token from the payload's current lifecycle-session generation.
    private final Supplier<PluginCapabilityToken> capabilityTokenSupplier;

    /// Launcher-owned exact payload lifecycle validator.
    private final RegistrationGate registrationGate;

    /// Immutable authoritative manifest declarations accepted from this payload.
    private final @Unmodifiable List<PluginPatchDeclaration> declarations;

    /// Creates one reserved Patch endpoint bound to an exact external payload.
    ///
    /// @param artifactIdentity exact external payload identity
    /// @param executionMode payload execution boundary
    /// @param permissionAuthority launcher-owned token verifier
    /// @param capabilityTokenSupplier current payload-session token source
    /// @param declarations authoritative manifest Patch declarations
    public RuntimePatchEndpoint(
            PluginArtifactIdentity artifactIdentity,
            PluginExecutionMode executionMode,
            PluginPermissionAuthority permissionAuthority,
            Supplier<PluginCapabilityToken> capabilityTokenSupplier,
            Collection<PluginPatchDeclaration> declarations
    ) {
        this(
                artifactIdentity,
                executionMode,
                permissionAuthority,
                capabilityTokenSupplier,
                declarations,
                () -> {
                }
        );
    }

    /// Creates one reserved Patch endpoint with an exact launcher-owned payload lifecycle gate.
    ///
    /// @param artifactIdentity exact external payload identity
    /// @param executionMode payload execution boundary
    /// @param permissionAuthority launcher-owned token verifier
    /// @param capabilityTokenSupplier current payload-session token source
    /// @param declarations authoritative manifest Patch declarations
    /// @param registrationGate exact payload lifecycle validator
    public RuntimePatchEndpoint(
            PluginArtifactIdentity artifactIdentity,
            PluginExecutionMode executionMode,
            PluginPermissionAuthority permissionAuthority,
            Supplier<PluginCapabilityToken> capabilityTokenSupplier,
            Collection<PluginPatchDeclaration> declarations,
            RegistrationGate registrationGate
    ) {
        this.artifactIdentity = Objects.requireNonNull(artifactIdentity, "artifactIdentity");
        this.executionMode = Objects.requireNonNull(executionMode, "executionMode");
        this.permissionAuthority = Objects.requireNonNull(permissionAuthority, "permissionAuthority");
        this.capabilityTokenSupplier = Objects.requireNonNull(
                capabilityTokenSupplier, "capabilityTokenSupplier");
        this.declarations = copyDeclarations(declarations);
        this.registrationGate = Objects.requireNonNull(registrationGate, "registrationGate");
    }

    /// Validates one exact declared Patch and returns the Stage-1 fail-closed engine status.
    ///
    /// @param declaration requested manifest declaration
    /// @return `PATCH_ENGINE_UNAVAILABLE` until Stage 2 installs the JVM Patch engine
    /// @throws IllegalArgumentException if the Patch was not declared by this payload
    /// @throws SecurityException if current plugin-scoped Patch authority is invalid
    public RegistrationStatus register(PluginPatchDeclaration declaration) {
        PluginPatchDeclaration requested = Objects.requireNonNull(declaration, "declaration");
        requested.validate();
        if (!declarations.contains(requested)) {
            throw new IllegalArgumentException("Patch declaration is not owned by plugin: "
                    + artifactIdentity.getPluginId());
        }
        PluginCapabilityToken token = Objects.requireNonNull(
                capabilityTokenSupplier.get(), "capabilityTokenSupplier result");
        permissionAuthority.requirePermission(
                token,
                artifactIdentity.getPluginId(),
                artifactIdentity,
                executionMode,
                PluginPermission.LAUNCHER_PATCH,
                RuntimeHookEndpoint.CALLBACK_DOMAIN
        );
        try {
            registrationGate.requireActive();
            return RegistrationStatus.PATCH_ENGINE_UNAVAILABLE;
        } finally {
            permissionAuthority.revoke(token);
        }
    }

    /// Returns the immutable authoritative Patch declaration snapshot retained for Stage 2 dispatch.
    ///
    /// @return immutable Patch declarations
    public @Unmodifiable List<PluginPatchDeclaration> declarations() {
        return declarations;
    }

    /// Copies and validates authoritative declarations without retaining caller collection state.
    ///
    /// @param declarations caller declaration collection
    /// @return immutable validated declaration list
    private static @Unmodifiable List<PluginPatchDeclaration> copyDeclarations(
            Collection<PluginPatchDeclaration> declarations
    ) {
        @Unmodifiable List<PluginPatchDeclaration> copy = List.copyOf(
                Objects.requireNonNull(declarations, "declarations"));
        copy.forEach(PluginPatchDeclaration::validate);
        return copy;
    }

    /// Stable Stage-1 Patch registration outcome.
    @NotNullByDefault
    public enum RegistrationStatus {
        /// Declaration and authority are valid, but no JVM Patch engine is installed in Stage 1.
        PATCH_ENGINE_UNAVAILABLE
    }

    /// Launcher-owned validator for one exact retained payload registration generation.
    @FunctionalInterface
    @NotNullByDefault
    public interface RegistrationGate {
        /// Requires that the exact payload registration remains active and enabled.
        void requireActive();
    }
}
