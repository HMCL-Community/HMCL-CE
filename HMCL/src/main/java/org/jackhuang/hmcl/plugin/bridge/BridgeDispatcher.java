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
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/// Dispatches bounded asynchronous Runtime callbacks with owner cancellation and redacted portable failures.
@NotNullByDefault
public final class BridgeDispatcher {
    /// Maximum encoded operation identifier length.
    public static final int MAX_OPERATION_LENGTH = 128;

    /// Default maximum callbacks reserved across all plugin owners.
    public static final int DEFAULT_GLOBAL_IN_FLIGHT = 128;

    /// Default maximum callbacks reserved by one plugin owner.
    public static final int DEFAULT_PER_OWNER_IN_FLIGHT = 16;

    /// Canonical language-neutral operation identifier syntax.
    private static final Pattern OPERATION_PATTERN = Pattern.compile("[a-z][a-z0-9]*(?:[.-][a-z0-9]+)*");

    /// Executor that owns callback worker scheduling.
    private final ExecutorService executor;

    /// Maximum callbacks reserved across all owners.
    private final int globalInFlightLimit;

    /// Maximum callbacks reserved by one owner.
    private final int perOwnerInFlightLimit;

    /// In-flight callbacks grouped by dependent plugin owner until their workers actually terminate.
    private final Map<String, Set<Dispatch>> activeByOwner = new HashMap<>();

    /// Number of callbacks atomically reserved across all owners.
    private int globalActiveCount;

    /// Creates one dispatcher with documented process defaults and an externally lifecycle-managed executor.
    ///
    /// @param executor callback executor
    public BridgeDispatcher(ExecutorService executor) {
        this(executor, DEFAULT_GLOBAL_IN_FLIGHT, DEFAULT_PER_OWNER_IN_FLIGHT);
    }

    /// Creates one dispatcher with explicit global and per-owner in-flight limits.
    ///
    /// Limits count queued and running callbacks. A cancelled running callback retains its reservation until its
    /// callback actually exits, even when it ignores interruption.
    ///
    /// @param executor callback executor
    /// @param globalInFlightLimit maximum callbacks reserved across all owners
    /// @param perOwnerInFlightLimit maximum callbacks reserved by one owner
    public BridgeDispatcher(
            ExecutorService executor,
            int globalInFlightLimit,
            int perOwnerInFlightLimit
    ) {
        this.executor = Objects.requireNonNull(executor, "executor");
        if (globalInFlightLimit <= 0) {
            throw new IllegalArgumentException("Global Bridge in-flight limit must be positive");
        }
        if (perOwnerInFlightLimit <= 0) {
            throw new IllegalArgumentException("Per-owner Bridge in-flight limit must be positive");
        }
        this.globalInFlightLimit = globalInFlightLimit;
        this.perOwnerInFlightLimit = perOwnerInFlightLimit;
    }

    /// Schedules one language-neutral callback after atomically reserving owner and global capacity.
    ///
    /// Capacity rejection returns an already terminated `UNAVAILABLE` dispatch and never submits the callback.
    ///
    /// @param ownerPluginId canonical dependent plugin ID
    /// @param operation stable operation identifier
    /// @param callback Runtime callback
    /// @return cancellable dispatch and its portable read-only completion view
    public Dispatch dispatch(String ownerPluginId, String operation, Callback callback) {
        requireOwnerId(ownerPluginId);
        requireOperation(operation);
        Objects.requireNonNull(callback, "callback");
        Dispatch dispatch = new Dispatch(ownerPluginId, operation, this::release);
        if (!reserve(dispatch)) {
            dispatch.rejectUnavailable();
            return dispatch;
        }
        try {
            Future<?> task = executor.submit(() -> invoke(dispatch, callback));
            dispatch.attach(task);
        } catch (RejectedExecutionException exception) {
            dispatch.rejectUnavailable();
        }
        return dispatch;
    }

    /// Cancels every accepted callback for one owner and returns a stage that drains their actual execution.
    ///
    /// A callback that ignores interruption keeps this stage incomplete until it exits. Callers may apply their own
    /// lifecycle deadline without gaining mutation access to the dispatch termination futures.
    ///
    /// @param ownerPluginId canonical owner plugin ID
    /// @return read-only stage completed after the snapshot's callbacks actually terminate
    public CompletionStage<@Nullable Void> cancelOwner(String ownerPluginId) {
        requireOwnerId(ownerPluginId);
        @Unmodifiable List<Dispatch> snapshot;
        synchronized (this) {
            Set<Dispatch> active = activeByOwner.get(ownerPluginId);
            snapshot = active == null ? List.of() : List.copyOf(active);
        }
        for (Dispatch dispatch : snapshot) {
            dispatch.cancel();
        }
        CompletableFuture<?> @Unmodifiable [] terminations = snapshot.stream()
                .map(Dispatch::internalTermination)
                .toArray(CompletableFuture<?>[]::new);
        return CompletableFuture.allOf(terminations).minimalCompletionStage();
    }

    /// Returns the current queued or running callback count for one owner.
    ///
    /// @param ownerPluginId canonical owner plugin ID
    /// @return active callback count
    public synchronized int activeCount(String ownerPluginId) {
        requireOwnerId(ownerPluginId);
        Set<Dispatch> active = activeByOwner.get(ownerPluginId);
        return active == null ? 0 : active.size();
    }

    /// Returns the current queued or running callback count across all owners.
    ///
    /// @return global active callback count
    public synchronized int globalActiveCount() {
        return globalActiveCount;
    }

    /// Atomically reserves global and owner capacity before executor submission.
    ///
    /// @param dispatch candidate dispatch
    /// @return whether both capacity reservations succeeded
    private synchronized boolean reserve(Dispatch dispatch) {
        Set<Dispatch> ownerActive = activeByOwner.get(dispatch.ownerPluginId());
        int ownerCount = ownerActive == null ? 0 : ownerActive.size();
        if (globalActiveCount >= globalInFlightLimit || ownerCount >= perOwnerInFlightLimit) {
            return false;
        }
        if (ownerActive == null) {
            ownerActive = new LinkedHashSet<>();
            activeByOwner.put(dispatch.ownerPluginId(), ownerActive);
        }
        ownerActive.add(dispatch);
        globalActiveCount++;
        return true;
    }

    /// Releases one reservation only after queued cancellation or actual worker exit.
    ///
    /// @param dispatch terminated dispatch
    private synchronized void release(Dispatch dispatch) {
        Set<Dispatch> active = activeByOwner.get(dispatch.ownerPluginId());
        if (active == null || !active.remove(dispatch)) {
            return;
        }
        globalActiveCount--;
        if (active.isEmpty()) {
            activeByOwner.remove(dispatch.ownerPluginId());
        }
    }

    /// Invokes one callback and converts every outcome to the closed Bridge value hierarchy.
    ///
    /// @param dispatch dispatch state
    /// @param callback callback implementation
    private void invoke(Dispatch dispatch, Callback callback) {
        if (!dispatch.beginExecution()) {
            return;
        }
        try {
            if (dispatch.cancellationWon()) {
                return;
            }
            @Nullable BridgeValue result = callback.invoke(dispatch.cancellation());
            if (result == null) {
                dispatch.complete(BridgeValue.error(BridgeError.of(BridgeError.Category.INVALID_RESULT)));
            } else {
                dispatch.complete(result);
            }
        } catch (BridgeError error) {
            dispatch.complete(BridgeValue.error(error));
        } catch (Throwable throwable) {
            dispatch.complete(BridgeValue.error(BridgeError.of(BridgeError.Category.CALLBACK_FAILED)));
        } finally {
            dispatch.finishExecution();
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

    /// Owns independent result and execution state machines for one Runtime callback.
    @NotNullByDefault
    public static final class Dispatch {
        /// Result state before callback completion or cancellation wins.
        private static final int RESULT_ACTIVE = 0;

        /// Result state after cancellation wins.
        private static final int RESULT_CANCELLED = 1;

        /// Result state after callback completion or rejection wins.
        private static final int RESULT_COMPLETED = 2;

        /// Execution state while reserved but not yet entered by a worker.
        private static final int EXECUTION_QUEUED = 0;

        /// Execution state while callback code may be running.
        private static final int EXECUTION_RUNNING = 1;

        /// Execution state after reservation release.
        private static final int EXECUTION_TERMINATED = 2;

        /// Canonical plugin owner.
        private final String ownerPluginId;

        /// Stable operation identifier used only for lifecycle correlation.
        private final String operation;

        /// Callback invoked exactly once when execution reaches its terminal state.
        private final Consumer<Dispatch> terminalAction;

        /// Portable cooperative cancellation signal.
        private final Cancellation cancellation = new Cancellation();

        /// Atomic result winner.
        private final AtomicInteger resultState = new AtomicInteger(RESULT_ACTIVE);

        /// Atomic queued/running lifecycle state.
        private final AtomicInteger executionState = new AtomicInteger(EXECUTION_QUEUED);

        /// Internally mutable result future.
        private final CompletableFuture<BridgeValue> internalCompletion = new CompletableFuture<>();

        /// Externally immutable minimal result view.
        private final CompletionStage<BridgeValue> completionView = internalCompletion.minimalCompletionStage();

        /// Internally mutable actual-execution termination future.
        private final CompletableFuture<@Nullable Void> internalTermination = new CompletableFuture<>();

        /// Externally immutable minimal termination view.
        private final CompletionStage<@Nullable Void> terminationView = internalTermination.minimalCompletionStage();

        /// Submitted executor task, assigned after successful submission.
        private volatile @Nullable Future<?> task;

        /// Creates one reserved or rejectable dispatch.
        ///
        /// @param ownerPluginId canonical owner plugin ID
        /// @param operation stable operation identifier
        /// @param terminalAction reservation release callback
        private Dispatch(String ownerPluginId, String operation, Consumer<Dispatch> terminalAction) {
            this.ownerPluginId = ownerPluginId;
            this.operation = operation;
            this.terminalAction = terminalAction;
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

        /// Returns an immutable minimal view of the portable completion value.
        ///
        /// Calling `toCompletableFuture()` returns a detached copy; mutating it cannot alter dispatch state.
        ///
        /// @return read-only completion stage
        public CompletionStage<BridgeValue> completion() {
            return completionView;
        }

        /// Returns an immutable minimal view completed after callback code actually stops running.
        ///
        /// @return read-only execution termination stage
        public CompletionStage<@Nullable Void> termination() {
            return terminationView;
        }

        /// Atomically wins cancellation intent, interrupts execution, and commits the cancelled result.
        ///
        /// A running callback retains its reservation until it exits. A queued callback releases immediately and can
        /// never enter callback code afterward.
        ///
        /// @return whether this call won cancellation before callback completion
        public boolean cancel() {
            if (!resultState.compareAndSet(RESULT_ACTIVE, RESULT_CANCELLED)) {
                return false;
            }
            cancellation.request();
            internalCompletion.complete(BridgeValue.error(BridgeError.of(BridgeError.Category.CANCELLED)));
            finishQueued();
            Future<?> currentTask = task;
            if (currentTask != null) {
                currentTask.cancel(true);
            }
            return true;
        }

        /// Returns the callback-visible cooperative cancellation signal.
        ///
        /// @return cancellation signal
        private Cancellation cancellation() {
            return cancellation;
        }

        /// Returns whether cancellation won the result race, including the instant before signal publication.
        ///
        /// @return whether callback work must not begin
        private boolean cancellationWon() {
            return resultState.get() == RESULT_CANCELLED;
        }

        /// Changes a queued dispatch to running when executor entry wins queued cancellation.
        ///
        /// @return whether this worker owns execution
        private boolean beginExecution() {
            return executionState.compareAndSet(EXECUTION_QUEUED, EXECUTION_RUNNING);
        }

        /// Releases one running reservation after callback code actually exits.
        private void finishExecution() {
            if (executionState.compareAndSet(EXECUTION_RUNNING, EXECUTION_TERMINATED)) {
                terminate();
            }
        }

        /// Releases one queued reservation without allowing callback entry.
        private void finishQueued() {
            if (executionState.compareAndSet(EXECUTION_QUEUED, EXECUTION_TERMINATED)) {
                terminate();
            }
        }

        /// Releases dispatcher capacity before publishing termination to drain waiters.
        private void terminate() {
            terminalAction.accept(this);
            internalTermination.complete(null);
        }

        /// Attaches the submitted task and propagates cancellation requested during submission.
        ///
        /// @param task submitted executor task
        private void attach(Future<?> task) {
            this.task = Objects.requireNonNull(task, "task");
            if (executionState.get() == EXECUTION_TERMINATED || cancellation.isCancellationRequested()) {
                task.cancel(true);
            }
        }

        /// Commits one callback result only when callback completion wins the result state race.
        ///
        /// @param value portable completion value
        private void complete(BridgeValue value) {
            if (resultState.compareAndSet(RESULT_ACTIVE, RESULT_COMPLETED)) {
                internalCompletion.complete(value);
            }
        }

        /// Completes an unsubmitted or executor-rejected dispatch as unavailable and releases its reservation.
        private void rejectUnavailable() {
            if (resultState.compareAndSet(RESULT_ACTIVE, RESULT_COMPLETED)) {
                internalCompletion.complete(BridgeValue.error(BridgeError.of(BridgeError.Category.UNAVAILABLE)));
            }
            finishQueued();
        }

        /// Returns the internal termination future solely for aggregate owner draining.
        ///
        /// @return internally controlled termination future
        private CompletableFuture<@Nullable Void> internalTermination() {
            return internalTermination;
        }
    }
}
