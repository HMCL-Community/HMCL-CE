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
package org.jackhuang.hmcl.plugin.bridge;

import org.jackhuang.hmcl.plugin.PluginArtifactIdentity;
import org.jackhuang.hmcl.plugin.PluginPermission;
import org.jackhuang.hmcl.plugin.runtime.PluginExecutionMode;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/// Owns one independently revocable family of capability-token generations for a runtime payload lifecycle.
@NotNullByDefault
public final class PluginCapabilitySession implements AutoCloseable {
    /// Maximum permission-snapshot attempts before continuous generation churn fails closed.
    private static final int MAX_ISSUE_ATTEMPTS = 8;

    /// Launcher authority which stores the opaque token grants.
    private final PluginPermissionAuthority authority;

    /// Exact package identity bound to every token in this session.
    private final PluginArtifactIdentity artifactIdentity;

    /// Execution boundary bound to every token in this session.
    private final PluginExecutionMode executionMode;

    /// Dynamic source of effective permissions sampled for each issuance.
    private final Supplier<@Unmodifiable Set<PluginPermission>> grantedPermissionProvider;

    /// Exact callback domain bound to every root token in this session.
    private final String callbackDomain;

    /// Positive lifetime applied to every token issued by this session.
    private final Duration lifetime;

    /// Current lifecycle state guarded by this session's monitor.
    private State state = State.ACTIVE;

    /// Current independently revocable generation guarded by this session's monitor.
    private long generation = 1;

    /// Creates one initially active authority-owned capability session.
    ///
    /// @param authority launcher-owned permission authority
    /// @param artifactIdentity exact package identity
    /// @param executionMode payload execution boundary
    /// @param grantedPermissionProvider dynamic effective permission source
    /// @param callbackDomain exact callback domain
    /// @param lifetime positive token lifetime
    PluginCapabilitySession(
            PluginPermissionAuthority authority,
            PluginArtifactIdentity artifactIdentity,
            PluginExecutionMode executionMode,
            Supplier<@Unmodifiable Set<PluginPermission>> grantedPermissionProvider,
            String callbackDomain,
            Duration lifetime
    ) {
        this.authority = Objects.requireNonNull(authority, "authority");
        this.artifactIdentity = Objects.requireNonNull(artifactIdentity, "artifactIdentity");
        this.executionMode = Objects.requireNonNull(executionMode, "executionMode");
        this.grantedPermissionProvider = Objects.requireNonNull(
                grantedPermissionProvider, "grantedPermissionProvider");
        this.callbackDomain = Objects.requireNonNull(callbackDomain, "callbackDomain");
        this.lifetime = Objects.requireNonNull(lifetime, "lifetime");
    }

    /// Issues one token in the current active generation using the latest effective permissions.
    ///
    /// Permission retrieval runs outside the session monitor so permission mutation can rotate this session without
    /// reversing the mutation-lock order. The generation is rechecked under the session monitor before authority
    /// insertion, which keeps suspension, closure, and rotation linearizable with issuance.
    ///
    /// @return opaque token bound to this session and generation
    /// @throws IllegalStateException if the session is unavailable or changes continuously during permission reads
    public PluginCapabilityToken issue() {
        for (int attempt = 0; attempt < MAX_ISSUE_ATTEMPTS; attempt++) {
            long expectedGeneration;
            synchronized (this) {
                requireActive();
                expectedGeneration = generation;
            }

            @Unmodifiable Set<PluginPermission> permissions = Objects.requireNonNull(
                    grantedPermissionProvider.get(), "grantedPermissionProvider result");

            synchronized (this) {
                requireActive();
                if (generation != expectedGeneration) {
                    continue;
                }
                return authority.issueForSession(
                        this,
                        generation,
                        artifactIdentity,
                        executionMode,
                        permissions,
                        callbackDomain,
                        lifetime
                );
            }
        }
        throw new IllegalStateException(
                "Capability session changed continuously during permission snapshot");
    }

    /// Prevents new issuance and revokes every token in the current generation.
    ///
    /// Repeated suspension is idempotent. A closed session remains closed.
    public synchronized void suspend() {
        if (state != State.ACTIVE) {
            return;
        }
        state = State.SUSPENDED;
        authority.revokeFamily(this, generation);
    }

    /// Starts a fresh active generation after suspension.
    ///
    /// Calling this method for an already active session is an idempotent no-op.
    ///
    /// @throws IllegalStateException if this session was permanently closed
    public synchronized void resume() {
        if (state == State.CLOSED) {
            throw new IllegalStateException("Capability session is closed");
        }
        if (state == State.SUSPENDED) {
            generation++;
            state = State.ACTIVE;
        }
    }

    /// Revokes the current generation and advances to an empty generation without changing lifecycle state.
    public synchronized void rotate() {
        if (state == State.CLOSED) {
            throw new IllegalStateException("Capability session is closed");
        }
        authority.revokeFamily(this, generation);
        generation++;
    }

    /// Permanently prevents issuance and revokes every token in the current generation.
    ///
    /// Repeated closure is idempotent and a closed session can never be resumed.
    @Override
    public synchronized void close() {
        if (state == State.CLOSED) {
            return;
        }
        state = State.CLOSED;
        authority.revokeFamily(this, generation);
    }

    /// Requires the session to be active before token issuance.
    ///
    /// @throws IllegalStateException if issuance is currently unavailable
    private void requireActive() {
        if (state != State.ACTIVE) {
            throw new IllegalStateException("Capability session is not active");
        }
    }

    /// Lifecycle state for one capability session.
    @NotNullByDefault
    private enum State {
        /// New token issuance is allowed.
        ACTIVE,

        /// Issuance is paused until an explicit resume creates a new generation.
        SUSPENDED,

        /// Issuance is permanently unavailable.
        CLOSED
    }
}
