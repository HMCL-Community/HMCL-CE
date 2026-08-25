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

import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/// Idempotent Host-owned handle for one external runtime Provider registration.
@NotNullByDefault
public final class RuntimeProviderRegistration implements AutoCloseable {
    /// Supervisor that owns state transitions and teardown ordering.
    private final RuntimeSupervisor supervisor;

    /// Canonical Host plugin ID which created this registration.
    private final String ownerPluginId;

    /// Exact Provider implementation registered by the Host.
    private final RuntimeProvider provider;

    /// Whether registration teardown has already been selected.
    private final AtomicBoolean closed = new AtomicBoolean();

    /// Whether Provider-wide resources closed successfully during a prior teardown attempt.
    private boolean providerClosed;

    /// Provider-scoped monitor serializing callbacks and teardown without blocking unrelated Providers.
    private final Object lifecycleLock = new Object();

    /// Creates a Supervisor-owned registration handle.
    ///
    /// @param supervisor lifecycle owner
    /// @param ownerPluginId Host plugin ID
    /// @param provider registered implementation
    RuntimeProviderRegistration(
            RuntimeSupervisor supervisor,
            String ownerPluginId,
            RuntimeProvider provider
    ) {
        this.supervisor = supervisor;
        this.ownerPluginId = ownerPluginId;
        this.provider = provider;
    }

    /// Returns the canonical Host plugin ID which owns this registration.
    ///
    /// @return Host plugin ID
    public String ownerPluginId() {
        return ownerPluginId;
    }

    /// Returns the exact registered Provider implementation.
    ///
    /// @return Provider implementation
    public RuntimeProvider provider() {
        return provider;
    }

    /// Returns whether teardown has completed or failure rollback closed the registration.
    ///
    /// @return whether the registration is closed
    public boolean isClosed() {
        return closed.get();
    }

    /// Stops dependents and unregisters the Provider, retaining incomplete cleanup for retry.
    ///
    /// @throws IOException if Provider payload or Host resource cleanup fails
    @Override
    public synchronized void close() throws IOException {
        if (closed.get()) {
            return;
        }
        supervisor.closeRegistration(this);
        closed.set(true);
    }

    /// Returns the Provider-scoped lifecycle monitor owned by this registration.
    ///
    /// @return lifecycle monitor
    Object lifecycleLock() {
        return lifecycleLock;
    }

    /// Returns whether Provider-wide resources already closed successfully.
    ///
    /// @return whether Provider close completed
    boolean isProviderClosed() {
        return providerClosed;
    }

    /// Records successful Provider-wide resource shutdown for later unregister retries.
    void markProviderClosed() {
        providerClosed = true;
    }

    /// Marks a registration closed after Supervisor-owned teardown or failure rollback.
    void markClosed() {
        closed.set(true);
    }
}
