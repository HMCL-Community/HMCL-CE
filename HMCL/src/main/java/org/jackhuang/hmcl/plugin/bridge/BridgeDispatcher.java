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

import org.jackhuang.hmcl.plugin.PluginManifest;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/// Dispatches asynchronous Runtime callbacks with owner cancellation and redacted portable failures.
@NotNullByDefault
public final class BridgeDispatcher {
    /// Maximum encoded operation identifier length.
    public static final int MAX_OPERATION_LENGTH = 128;

    /// Canonical language-neutral operation identifier syntax.
    private static final Pattern OPERATION_PATTERN = Pattern.compile("[a-z][a-z0-9]*(?:[.-][a-z0-9]+)*");

    /// Executor that owns callback worker scheduling.
    private final ExecutorService executor;

    /// In-flight callbacks grouped by dependent plugin owner.
    private final Map<String, Set<Dispatch>> activeByOwner = new HashMap<>();

    /// Creates one dispatcher using an externally lifecycle-managed executor.
    ///
    /// @param executor callback executor
    public BridgeDispatcher(ExecutorService executor) {
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    /// Schedules one language-neutral callback for a canonical plugin owner.
    ///
    /// @param ownerPluginId canonical dependent plugin ID
    /// @param operation stable operation identifier
    /// @param callback Runtime callback
    /// @return cancellable dispatch and its portable completion value
    public Dispatch dispatch(String ownerPluginId, String operation, Callback callback) {
        requireOwnerId(ownerPluginId);
        requireOperation(operation);
        Objects.requireNonNull(callback, "callback");
        Dispatch dispatch = new Dispatch(ownerPluginId, operation);
        dispatch.completion().whenComplete((@Nullable BridgeValue value, @Nullable Throwable failure) ->
                forget(dispatch));
        synchronized (this) {
            activeByOwner.computeIfAbsent(ownerPluginId, ignored -> new LinkedHashSet<>()).add(dispatch);
        }
        try {
            Future<?> task = executor.submit(() -> invoke(dispatch, callback));
            dispatch.attach(task);
        } catch (RejectedExecutionException exception) {
            forget(dispatch);
            dispatch.complete(BridgeValue.error(BridgeError.of(BridgeError.Category.UNAVAILABLE)));
        }
        return dispatch;
    }

    /// Cancels every in-flight callback for one unloading plugin owner.
    ///
    /// @param ownerPluginId canonical owner plugin ID
    public void cancelOwner(String ownerPluginId) {
        requireOwnerId(ownerPluginId);
        @Unmodifiable List<Dispatch> snapshot;
        synchronized (this) {
            Set<Dispatch> active = activeByOwner.get(ownerPluginId);
            snapshot = active == null ? List.of() : List.copyOf(active);
        }
        for (Dispatch dispatch : snapshot) {
            dispatch.cancel();
        }
    }

    /// Returns the current callback count for one owner.
    ///
    /// @param ownerPluginId canonical owner plugin ID
    /// @return active callback count
    public synchronized int activeCount(String ownerPluginId) {
        requireOwnerId(ownerPluginId);
        Set<Dispatch> active = activeByOwner.get(ownerPluginId);
        return active == null ? 0 : active.size();
    }

    /// Invokes one callback and converts every outcome to the closed Bridge value hierarchy.
    ///
    /// @param dispatch dispatch state
    /// @param callback callback implementation
    private void invoke(Dispatch dispatch, Callback callback) {
        try {
            if (dispatch.cancellation().isCancellationRequested()) {
                dispatch.complete(BridgeValue.error(BridgeError.of(BridgeError.Category.CANCELLED)));
                return;
            }
            @Nullable BridgeValue result = callback.invoke(dispatch.cancellation());
            if (dispatch.cancellation().isCancellationRequested()) {
                dispatch.complete(BridgeValue.error(BridgeError.of(BridgeError.Category.CANCELLED)));
            } else if (result == null) {
                dispatch.complete(BridgeValue.error(BridgeError.of(BridgeError.Category.INVALID_RESULT)));
            } else {
                dispatch.complete(result);
            }
        } catch (BridgeError error) {
            dispatch.complete(BridgeValue.error(error));
        } catch (Throwable throwable) {
            dispatch.complete(BridgeValue.error(BridgeError.of(BridgeError.Category.CALLBACK_FAILED)));
        } finally {
            forget(dispatch);
        }
    }

    /// Removes one completed or cancelled dispatch from owner lifecycle tracking.
    ///
    /// @param dispatch completed dispatch
    private synchronized void forget(Dispatch dispatch) {
        Set<Dispatch> active = activeByOwner.get(dispatch.ownerPluginId());
        if (active == null) {
            return;
        }
        active.remove(dispatch);
        if (active.isEmpty()) {
            activeByOwner.remove(dispatch.ownerPluginId());
        }
    }

    /// Validates one canonical executable plugin ID.
    ///
    /// @param ownerPluginId candidate owner ID
    private static void requireOwnerId(String ownerPluginId) {
        if (!PluginManifest.isCanonicalExecutableId(ownerPluginId)) {
            throw new IllegalArgumentException("Bridge callback owner must be a canonical plugin ID");
        }
    }

    /// Validates one canonical operation identifier.
    ///
    /// @param operation candidate operation ID
    private static void requireOperation(String operation) {
        Objects.requireNonNull(operation, "operation");
        if (operation.length() > MAX_OPERATION_LENGTH || !OPERATION_PATTERN.matcher(operation).matches()) {
            throw new IllegalArgumentException("Bridge operation must be canonical");
        }
    }

    /// Implements one Runtime callback using only closed Bridge values and a portable cancellation signal.
    @FunctionalInterface
    @NotNullByDefault
    public interface Callback {
        /// Invokes one callback.
        ///
        /// @param cancellation cooperative cancellation signal
        /// @return callback result
        /// @throws Exception when callback execution fails
        BridgeValue invoke(Cancellation cancellation) throws Exception;
    }

    /// Exposes cooperative cancellation state without leaking JVM task or thread objects.
    @NotNullByDefault
    public static final class Cancellation {
        /// Whether cancellation was requested.
        private final AtomicBoolean requested = new AtomicBoolean();

        /// Creates one initially active cancellation signal.
        private Cancellation() {
        }

        /// Returns whether dispatch cancellation has been requested.
        ///
        /// @return cancellation state
        public boolean isCancellationRequested() {
            return requested.get();
        }

        /// Marks this signal as cancelled.
        private void request() {
            requested.set(true);
        }
    }

    /// Owns one callback's completion, cancellation signal, and interruptible executor task.
    @NotNullByDefault
    public static final class Dispatch {
        /// Canonical plugin owner.
        private final String ownerPluginId;

        /// Stable operation identifier used only for lifecycle correlation.
        private final String operation;

        /// Portable cooperative cancellation signal.
        private final Cancellation cancellation = new Cancellation();

        /// Completion expressed only as a Bridge value or Bridge error value.
        private final CompletableFuture<BridgeValue> completion = new CompletableFuture<>();

        /// Submitted executor task, assigned after successful submission.
        private volatile @Nullable Future<?> task;

        /// Creates one unsubmitted dispatch.
        ///
        /// @param ownerPluginId canonical owner plugin ID
        /// @param operation stable operation identifier
        private Dispatch(String ownerPluginId, String operation) {
            this.ownerPluginId = ownerPluginId;
            this.operation = operation;
        }

        /// Returns the canonical dependent plugin owner.
        ///
        /// @return plugin owner ID
        public String ownerPluginId() {
            return ownerPluginId;
        }

        /// Returns the stable operation identifier.
        ///
        /// @return operation identifier
        public String operation() {
            return operation;
        }

        /// Returns the portable completion future.
        ///
        /// @return completion future
        public CompletableFuture<BridgeValue> completion() {
            return completion;
        }

        /// Requests cooperative cancellation, interrupts a running callback, and commits a cancelled result.
        ///
        /// @return whether this call initiated cancellation before completion
        public boolean cancel() {
            if (completion.isDone() || cancellation.isCancellationRequested()) {
                return false;
            }
            cancellation.request();
            Future<?> currentTask = task;
            if (currentTask != null) {
                currentTask.cancel(true);
            }
            return completion.complete(BridgeValue.error(BridgeError.of(BridgeError.Category.CANCELLED)));
        }

        /// Returns the callback-visible cooperative cancellation signal.
        ///
        /// @return cancellation signal
        private Cancellation cancellation() {
            return cancellation;
        }

        /// Attaches the submitted task and propagates cancellation requested during submission.
        ///
        /// @param task submitted executor task
        private void attach(Future<?> task) {
            this.task = Objects.requireNonNull(task, "task");
            if (cancellation.isCancellationRequested()) {
                task.cancel(true);
            }
        }

        /// Commits one result unless cancellation or another completion already won the race.
        ///
        /// @param value portable completion value
        private void complete(BridgeValue value) {
            completion.complete(value);
        }
    }
}
