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
package org.jackhuang.hmcl.plugin;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.time.Clock;
import java.time.Duration;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// Invokes immutable Hook endpoints with transactional validation, bounded timeouts, and lease isolation.
@NotNullByDefault
final class PluginHookDispatcher {
    /// Default callback timeout for production dispatch.
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    /// Maximum number of plugin callbacks that may execute concurrently across dispatches.
    private static final int DEFAULT_WORKERS = 4;

    /// Maximum number of callbacks awaiting a production worker.
    private static final int DEFAULT_QUEUE_CAPACITY = 64;

    /// Shared bounded daemon executor used by process-wide production dispatchers.
    private static final ExecutorService DEFAULT_EXECUTOR = new ThreadPoolExecutor(
            DEFAULT_WORKERS,
            DEFAULT_WORKERS,
            30L,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(DEFAULT_QUEUE_CAPACITY),
            daemonThreadFactory(),
            new ThreadPoolExecutor.AbortPolicy()
    );

    /// Executor that isolates callback execution from the launching thread.
    private final ExecutorService executor;

    /// Per-subscriber wait limit expressed without conversion during dispatch.
    private final long timeoutNanos;

    /// Clock shared with policies that construct deterministic event envelopes and launch sessions.
    private final Clock clock;

    /// Source of already filtered and deterministically ordered leased subscribers.
    private final SubscriberSource subscriberSource;

    /// Creates a production dispatcher backed by the process-wide bounded daemon executor.
    ///
    /// @param pluginManager process-wide or isolated plugin manager
    PluginHookDispatcher(PluginManager pluginManager) {
        this(
                DEFAULT_EXECUTOR,
                DEFAULT_TIMEOUT,
                Clock.systemUTC(),
                Objects.requireNonNull(pluginManager, "pluginManager")::snapshotHookSubscribers
        );
    }

    /// Creates an injectable dispatcher.
    ///
    /// @param executor daemon callback executor
    /// @param timeout per-subscriber wait limit
    /// @param clock event and session clock
    /// @param subscriberSource leased subscriber snapshot source
    PluginHookDispatcher(
            ExecutorService executor,
            Duration timeout,
            Clock clock,
        SubscriberSource subscriberSource
    ) {
        this.executor = Objects.requireNonNull(executor, "executor");
        Duration timeoutValue = Objects.requireNonNull(timeout, "timeout");
        if (timeoutValue.isZero() || timeoutValue.isNegative()) {
            throw new IllegalArgumentException("Plugin Hook timeout must be positive");
        }
        try {
            timeoutNanos = timeoutValue.toNanos();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Plugin Hook timeout is too large", exception);
        }
        this.clock = Objects.requireNonNull(clock, "clock");
        this.subscriberSource = Objects.requireNonNull(subscriberSource, "subscriberSource");
    }

    /// Dispatches one cancellable or fail-fast before Hook and returns the last committed complete data object.
    ///
    /// @param point Hook point being dispatched
    /// @param initialData initial complete callback data
    /// @param policy operation-specific event and validation policy
    /// @return final committed data
    /// @throws PluginHookDispatchException if any subscriber fails, times out, returns invalid data, or cancels
    PluginDataObject dispatchBefore(
            PluginHookPoint point,
            PluginDataObject initialData,
            Policy policy
    ) throws PluginHookDispatchException {
        @Unmodifiable List<PluginHookSubscriber> subscribers = snapshot(point);
        Set<PluginHookSubscriber> submitted = Collections.newSetFromMap(new IdentityHashMap<>());
        PluginDataObject currentData = Objects.requireNonNull(initialData, "initialData");
        try {
            for (PluginHookSubscriber subscriber : subscribers) {
                PluginHookEvent event = eventFor(point, subscriber, currentData, policy);
                submitted.add(subscriber);
                PluginHookResult result = invokeEndpoint(point, subscriber, event);
                Candidate candidate = validateResult(point, subscriber, currentData, result, policy);
                try {
                    candidate.commit().run();
                } catch (RuntimeException | Error exception) {
                    throw failure(point, subscriber, PluginHookDispatchException.Category.EXCEPTION, exception);
                }
                currentData = candidate.data();
            }
            return currentData;
        } finally {
            closeUntouched(subscribers, submitted);
        }
    }

    /// Dispatches one notification-only after Hook, reports each failure, and continues with remaining subscribers.
    ///
    /// Replacement candidates are validated but never committed because an after Hook cannot rewrite completed
    /// launcher state.
    ///
    /// @param point Hook point being dispatched
    /// @param data immutable notification data
    /// @param policy operation-specific event and validation policy
    void dispatchAfter(
            PluginHookPoint point,
            PluginDataObject data,
            Policy policy
    ) {
        @Unmodifiable List<PluginHookSubscriber> subscribers = snapshot(point);
        Set<PluginHookSubscriber> submitted = Collections.newSetFromMap(new IdentityHashMap<>());
        try {
            for (PluginHookSubscriber subscriber : subscribers) {
                try {
                    PluginHookEvent event = eventFor(point, subscriber, data, policy);
                    submitted.add(subscriber);
                    PluginHookResult result = invokeEndpoint(point, subscriber, event);
                    validateResult(point, subscriber, data, result, policy);
                } catch (PluginHookDispatchException failure) {
                    reportAfterFailure(policy, subscriber, failure);
                    if (Thread.currentThread().isInterrupted()) {
                        break;
                    }
                }
            }
        } finally {
            closeUntouched(subscribers, submitted);
        }
    }

    /// Returns the dispatcher clock used by the launch-scoped coordinator.
    ///
    /// @return event and session clock
    Clock clock() {
        return clock;
    }

    /// Copies one subscriber source result so later source mutation cannot alter this dispatch.
    ///
    /// @param point Hook point being dispatched
    /// @return immutable leased subscriber snapshot
    private @Unmodifiable List<PluginHookSubscriber> snapshot(PluginHookPoint point) {
        return List.copyOf(Objects.requireNonNull(
                subscriberSource.snapshot(Objects.requireNonNull(point, "point")),
                "subscriber snapshot"
        ));
    }

    /// Builds one policy event and converts malformed policy output into a redacted invalid-result failure.
    ///
    /// @param point Hook point being dispatched
    /// @param subscriber current subscriber
    /// @param currentData currently committed data
    /// @param policy event policy
    /// @return non-null event
    private static PluginHookEvent eventFor(
            PluginHookPoint point,
            PluginHookSubscriber subscriber,
            PluginDataObject currentData,
            Policy policy
    ) {
        try {
            return Objects.requireNonNull(
                    policy.eventFor(subscriber, currentData),
                    "Plugin Hook policy returned a null event"
            );
        } catch (PluginHookDispatchException exception) {
            throw exception;
        } catch (RuntimeException | Error exception) {
            throw failure(point, subscriber, PluginHookDispatchException.Category.INVALID_RESULT, exception);
        }
    }

    /// Applies dispatcher-owned action rules and delegates complete replacement validation to the operation policy.
    ///
    /// @param point Hook point being dispatched
    /// @param subscriber current subscriber
    /// @param currentData currently committed data
    /// @param result endpoint result
    /// @param policy operation-specific validation policy
    /// @return fully validated staged candidate
    private static Candidate validateResult(
            PluginHookPoint point,
            PluginHookSubscriber subscriber,
            PluginDataObject currentData,
            @Nullable PluginHookResult result,
            Policy policy
    ) {
        if (result == null) {
            throw failure(point, subscriber, PluginHookDispatchException.Category.INVALID_RESULT, null);
        }
        if (result.action() == PluginHookResult.Action.CANCEL) {
            try {
                policy.validateCancellation(subscriber, result);
            } catch (PluginHookDispatchException exception) {
                throw exception;
            } catch (RuntimeException | Error exception) {
                throw failure(point, subscriber, PluginHookDispatchException.Category.INVALID_RESULT, exception);
            }
            throw PluginHookDispatchException.cancelled(
                    point,
                    subscriber.pluginId(),
                    Objects.requireNonNull(result.reasonCode(), "Cancellation reason"),
                    Objects.requireNonNull(result.message(), "Cancellation message")
            );
        }
        try {
            return Objects.requireNonNull(
                    policy.validate(subscriber, currentData, result),
                    "Plugin Hook policy returned a null candidate"
            );
        } catch (PluginHookDispatchException exception) {
            throw exception;
        } catch (RuntimeException | Error exception) {
            throw failure(point, subscriber, PluginHookDispatchException.Category.INVALID_RESULT, exception);
        }
    }

    /// Submits and awaits one endpoint while keeping its lease on the worker until actual callback completion.
    ///
    /// @param point Hook point being dispatched
    /// @param subscriber current subscriber
    /// @param event immutable callback event
    /// @return endpoint result, possibly `null` for malformed plugin output
    private @Nullable PluginHookResult invokeEndpoint(
            PluginHookPoint point,
            PluginHookSubscriber subscriber,
            PluginHookEvent event
    ) {
        long startedAt = System.nanoTime();
        Object startGate = new Object();
        AtomicBoolean started = new AtomicBoolean();
        AtomicBoolean cancelledBeforeStart = new AtomicBoolean();
        PluginHookEndpoint.Invocation invocation;
        try {
            invocation = Objects.requireNonNull(
                    subscriber.endpoint().prepareInvocation(event),
                    "Plugin Hook endpoint returned a null invocation"
            );
        } catch (RuntimeException | Error exception) {
            subscriber.close();
            throw failure(point, subscriber, PluginHookDispatchException.Category.EXCEPTION, exception);
        }
        Future<@Nullable PluginHookResult> future;
        try {
            future = executor.submit(() -> {
                synchronized (startGate) {
                    if (cancelledBeforeStart.get()) {
                        return null;
                    }
                    started.set(true);
                }
                try {
                    long remainingNanos = remainingNanos(startedAt);
                    if (remainingNanos <= 0) {
                        invocation.cancel();
                        throw new CallbackDeadlineExceededException(
                                "Plugin Hook deadline elapsed before callback start");
                    }
                    return invocation.invoke(Duration.ofNanos(remainingNanos));
                } finally {
                    subscriber.close();
                }
            });
        } catch (RejectedExecutionException exception) {
            invocation.cancel();
            subscriber.close();
            throw failure(point, subscriber, PluginHookDispatchException.Category.EXCEPTION, exception);
        }

        try {
            long remainingNanos = remainingNanos(startedAt);
            if (remainingNanos <= 0) {
                throw new TimeoutException("Plugin Hook deadline elapsed before callback wait");
            }
            return future.get(remainingNanos, TimeUnit.NANOSECONDS);
        } catch (TimeoutException exception) {
            invocation.cancel();
            future.cancel(true);
            releaseIfCancelledBeforeStart(subscriber, startGate, started, cancelledBeforeStart);
            throw failure(point, subscriber, PluginHookDispatchException.Category.TIMEOUT, exception);
        } catch (InterruptedException exception) {
            invocation.cancel();
            future.cancel(true);
            releaseIfCancelledBeforeStart(subscriber, startGate, started, cancelledBeforeStart);
            Thread.currentThread().interrupt();
            throw failure(point, subscriber, PluginHookDispatchException.Category.EXCEPTION, exception);
        } catch (CancellationException exception) {
            invocation.cancel();
            releaseIfCancelledBeforeStart(subscriber, startGate, started, cancelledBeforeStart);
            throw failure(point, subscriber, PluginHookDispatchException.Category.EXCEPTION, exception);
        } catch (ExecutionException exception) {
            @Nullable Throwable cause = exception.getCause();
            if (cause instanceof CallbackDeadlineExceededException deadlineFailure) {
                throw failure(point, subscriber, PluginHookDispatchException.Category.TIMEOUT, deadlineFailure);
            }
            invocation.cancel();
            if (cause instanceof PluginHookDispatchException dispatchFailure
                    && dispatchFailure.point() == point
                    && dispatchFailure.pluginId().equals(subscriber.pluginId())
                    && dispatchFailure.category() == PluginHookDispatchException.Category.MISSING_ENDPOINT) {
                throw dispatchFailure;
            }
            throw failure(point, subscriber, PluginHookDispatchException.Category.EXCEPTION, null);
        }
    }

    /// Returns the unspent portion of one callback's original absolute dispatcher budget.
    ///
    /// @param startedAt monotonic start time captured before invocation preparation
    /// @return remaining nanoseconds, clamped to zero
    private long remainingNanos(long startedAt) {
        long elapsedNanos = System.nanoTime() - startedAt;
        if (elapsedNanos <= 0) {
            return timeoutNanos;
        }
        return Math.max(0, timeoutNanos - elapsedNanos);
    }

    /// Internal worker marker distinguishing absolute deadline exhaustion from lifecycle cancellation.
    @NotNullByDefault
    private static final class CallbackDeadlineExceededException extends CancellationException {
        /// Creates one stable deadline failure.
        ///
        /// @param message non-sensitive failure description
        private CallbackDeadlineExceededException(String message) {
            super(message);
        }
    }

    /// Closes a subscriber whose timeout cancellation won before its worker began endpoint execution.
    ///
    /// The synchronized handshake prevents a worker from starting after the timeout thread releases the lease.
    ///
    /// @param subscriber cancelled subscriber
    /// @param startGate worker and timeout handshake monitor
    /// @param started whether endpoint execution already owns lease release
    /// @param cancelledBeforeStart whether the worker must skip endpoint execution
    private static void releaseIfCancelledBeforeStart(
            PluginHookSubscriber subscriber,
            Object startGate,
            AtomicBoolean started,
            AtomicBoolean cancelledBeforeStart
    ) {
        boolean release = false;
        synchronized (startGate) {
            if (!started.get()) {
                cancelledBeforeStart.set(true);
                release = true;
            }
        }
        if (release) {
            subscriber.close();
        }
    }

    /// Closes snapshot entries that never transferred lease ownership to an executor task.
    ///
    /// @param subscribers complete snapshot
    /// @param submitted entries whose worker or cancellation path owns release
    private static void closeUntouched(
            List<PluginHookSubscriber> subscribers,
            Set<PluginHookSubscriber> submitted
    ) {
        for (PluginHookSubscriber subscriber : subscribers) {
            if (!submitted.contains(subscriber)) {
                subscriber.close();
            }
        }
    }

    /// Reports one isolated after failure without allowing reporter failure to stop later subscribers.
    ///
    /// @param policy after policy
    /// @param subscriber failed subscriber
    /// @param failure categorized callback failure
    private static void reportAfterFailure(
            Policy policy,
            PluginHookSubscriber subscriber,
            PluginHookDispatchException failure
    ) {
        try {
            policy.reportAfterFailure(subscriber, failure);
        } catch (RuntimeException | Error reportingFailure) {
            LOG.warning("Failed to report plugin after-Hook failure: " + subscriber.pluginId(), reportingFailure);
        }
    }

    /// Creates one stable redacted dispatch failure.
    ///
    /// @param point Hook point being dispatched
    /// @param subscriber responsible subscriber
    /// @param category stable failure category
    /// @param cause internal cause, or `null`
    /// @return categorized failure
    private static PluginHookDispatchException failure(
            PluginHookPoint point,
            PluginHookSubscriber subscriber,
            PluginHookDispatchException.Category category,
            @Nullable Throwable cause
    ) {
        return new PluginHookDispatchException(point, subscriber.pluginId(), category, cause);
    }

    /// Creates named daemon workers for the shared production executor.
    ///
    /// @return daemon thread factory
    private static ThreadFactory daemonThreadFactory() {
        AtomicInteger sequence = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, "PluginHook-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    /// Supplies a leased, already filtered, deterministically ordered subscriber snapshot.
    @FunctionalInterface
    @NotNullByDefault
    interface SubscriberSource {
        /// Takes one subscriber snapshot.
        ///
        /// @param point Hook point being dispatched
        /// @return immutable leased subscriber list
        @Unmodifiable List<PluginHookSubscriber> snapshot(PluginHookPoint point);
    }

    /// Defines operation-specific event creation, complete result validation, staging, and after failure reporting.
    @NotNullByDefault
    interface Policy {
        /// Creates a fresh event for one subscriber and the currently committed data.
        ///
        /// @param subscriber current subscriber
        /// @param currentData currently committed data
        /// @return immutable callback event
        PluginHookEvent eventFor(PluginHookSubscriber subscriber, PluginDataObject currentData);

        /// Validates one complete endpoint result and stages any protected state without committing it.
        ///
        /// @param subscriber current subscriber
        /// @param currentData currently committed data
        /// @param result non-cancel endpoint result
        /// @return complete validated candidate and no-throw commit action
        /// @throws PluginHookDispatchException if ordinary or protected result data is invalid
        Candidate validate(
                PluginHookSubscriber subscriber,
                PluginDataObject currentData,
                PluginHookResult result
        ) throws PluginHookDispatchException;

        /// Validates a deliberate cancel result for this operation without exposing its message to diagnostics.
        ///
        /// @param subscriber current subscriber
        /// @param result cancel endpoint result
        /// @throws PluginHookDispatchException if cancellation is not allowed or its message is unsafe
        void validateCancellation(
                PluginHookSubscriber subscriber,
                PluginHookResult result
        ) throws PluginHookDispatchException;

        /// Reports one isolated after callback failure.
        ///
        /// @param subscriber failed subscriber
        /// @param failure categorized redacted failure
        void reportAfterFailure(
                PluginHookSubscriber subscriber,
                PluginHookDispatchException failure
        );
    }

    /// Holds one complete validated data candidate and its prevalidated atomic commit action.
    ///
    /// @param data complete candidate data
    /// @param commit no-throw protected-state commit action
    @NotNullByDefault
    record Candidate(PluginDataObject data, Runnable commit) {
        /// Rejects null candidate state.
        Candidate {
            Objects.requireNonNull(data, "data");
            Objects.requireNonNull(commit, "commit");
        }
    }
}
