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

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/// Coordinates one process-wide shutdown request with short-lived internal operation leases.
@NotNullByDefault
public final class ApplicationShutdownCoordinator {
    /// Starts irreversible application shutdown.
    private final Runnable shutdownAction;

    /// Hides the application UI while shutdown waits for active leases.
    private final Runnable deferAction;

    /// Number of active shutdown leases.
    private int leaseCount;

    /// Whether any caller has requested application shutdown.
    private boolean shutdownRequested;

    /// Whether the defer action must finish before a final lease release can start shutdown.
    private boolean deferActionInProgress;

    /// Whether the irreversible shutdown action has started.
    private boolean shutdownStarted;

    /// Creates one coordinator around application-specific shutdown and defer actions.
    ///
    /// @param shutdownAction irreversible shutdown action
    /// @param deferAction UI defer action invoked when leases keep a request pending
    public ApplicationShutdownCoordinator(Runnable shutdownAction, Runnable deferAction) {
        this.shutdownAction = Objects.requireNonNull(shutdownAction, "shutdownAction");
        this.deferAction = Objects.requireNonNull(deferAction, "deferAction");
    }

    /// Acquires one idempotently closeable lease before irreversible shutdown begins.
    ///
    /// @param owner stable internal owner label used to identify the lease
    /// @return lease whose first close releases one shutdown hold
    /// @throws IllegalStateException if irreversible shutdown has already started
    public AutoCloseable acquireLease(String owner) {
        Objects.requireNonNull(owner, "owner");
        synchronized (this) {
            if (shutdownStarted) {
                throw new IllegalStateException("Application shutdown has already started");
            }
            leaseCount++;
        }

        AtomicBoolean closed = new AtomicBoolean();
        return () -> {
            if (closed.compareAndSet(false, true)) {
                releaseLease();
            }
        };
    }

    /// Requests shutdown once, deferring it when at least one lease is active.
    public void requestShutdown() {
        Runnable action;
        boolean deferred;
        synchronized (this) {
            if (shutdownRequested) {
                return;
            }
            shutdownRequested = true;
            if (leaseCount == 0) {
                shutdownStarted = true;
                action = shutdownAction;
                deferred = false;
            } else {
                deferActionInProgress = true;
                action = deferAction;
                deferred = true;
            }
        }
        try {
            action.run();
        } finally {
            if (deferred) {
                finishDeferAction();
            }
        }
    }

    /// Releases one active lease and starts a pending shutdown after the final release.
    private void releaseLease() {
        @Nullable Runnable action = null;
        synchronized (this) {
            leaseCount--;
            if (leaseCount == 0 && shutdownRequested && !deferActionInProgress && !shutdownStarted) {
                shutdownStarted = true;
                action = shutdownAction;
            }
        }
        if (action != null) {
            action.run();
        }
    }

    /// Clears defer ordering and starts shutdown when the final lease closed during the defer action.
    private void finishDeferAction() {
        @Nullable Runnable action = null;
        synchronized (this) {
            deferActionInProgress = false;
            if (leaseCount == 0 && !shutdownStarted) {
                shutdownStarted = true;
                action = shutdownAction;
            }
        }
        if (action != null) {
            action.run();
        }
    }
}
