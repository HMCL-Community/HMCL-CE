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
package org.jackhuang.hmcl.plugin.store;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/// Loads enabled plugin sources concurrently and publishes deterministic priority-based aggregate snapshots.
@NotNullByDefault
public final class PluginStoreAggregator implements AutoCloseable {
    /// Default maximum number of source requests active at once.
    static final int DEFAULT_CONCURRENCY = PluginSourceLoadExecutor.MAX_CONCURRENCY;

    /// Counts daemon threads created for aggregate source requests.
    private static final AtomicInteger THREAD_COUNTER = new AtomicInteger();

    /// Dedicated bounded executor owned and closed by this aggregate client.
    private final ExecutorService executor;

    /// Builds an unloaded manager for each enabled source request.
    private final Function<PluginSource, PluginStoreManager> managerFactory;

    /// Serializes generation allocation with snapshot publication to make the generation gate atomic.
    private final Object publicationLock = new Object();

    /// Monotonically advances for every requested aggregation refresh.
    private final AtomicLong generation = new AtomicLong();

    /// Holds only the newest completed snapshot allowed through the publication gate.
    private final AtomicReference<@Nullable PluginStoreSnapshot> currentSnapshot = new AtomicReference<>();

    /// Creates an aggregator using the default bounded concurrency and normal store managers.
    public PluginStoreAggregator() {
        this(DEFAULT_CONCURRENCY);
    }

    /// Creates an aggregator using the requested bounded concurrency and normal store managers.
    ///
    /// @param concurrency maximum number of active source loads
    PluginStoreAggregator(int concurrency) {
        this(concurrency, ignored -> new PluginStoreManager());
    }

    /// Creates an aggregator with deterministic manager injection for package-local tests.
    ///
    /// @param concurrency maximum number of active source loads
    /// @param managerFactory creates a fresh manager for every enabled source
    PluginStoreAggregator(int concurrency, Function<PluginSource, PluginStoreManager> managerFactory) {
        if (concurrency < 1) {
            throw new IllegalArgumentException("concurrency must be positive");
        }
        this.managerFactory = Objects.requireNonNull(managerFactory, "managerFactory");
        executor = Executors.newFixedThreadPool(concurrency, newSourceThreadFactory());
    }

    /// Creates daemon aggregate threads that cannot keep the launcher alive after an abandoned refresh.
    ///
    /// @return named daemon thread factory
    private static ThreadFactory newSourceThreadFactory() {
        return task -> {
            Thread thread = new Thread(task, "plugin-store-aggregate-" + THREAD_COUNTER.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    /// Starts a priority-ordered refresh without submitting disabled source requests.
    ///
    /// Each caller receives its own computed result. Only the newest generation may update
    /// [#getCurrentSnapshot()], so a delayed earlier request can never replace a newer catalog.
    ///
    /// @param sources configured source priority order
    /// @return future completing with the requested generation's aggregate snapshot
    public CompletableFuture<PluginStoreSnapshot> refresh(@Unmodifiable List<PluginSource> sources) {
        return refresh(new PluginSourceConfiguration(0, sources));
    }

    /// Starts a priority-ordered refresh for one atomic repository source configuration.
    ///
    /// @param configuration revision-bearing configured source priority order
    /// @return future completing with the requested generation's aggregate snapshot
    public CompletableFuture<PluginStoreSnapshot> refresh(PluginSourceConfiguration configuration) {
        long requestGeneration;
        synchronized (publicationLock) {
            requestGeneration = generation.incrementAndGet();
        }
        List<PluginSource> sourceSnapshot = configuration.getSources();
        List<CompletableFuture<PluginSourceLoadResult>> requests = sourceSnapshot.stream()
                .map(source -> source.isEnabled()
                        ? CompletableFuture.supplyAsync(() -> load(source), executor)
                        : CompletableFuture.completedFuture(PluginSourceLoadResult.disabled(source)))
                .toList();
        return CompletableFuture.allOf(requests.toArray(CompletableFuture[]::new))
                .thenApply(ignored -> publishIfCurrent(
                        requestGeneration,
                        configuration.getRevision(),
                        requests
                ));
    }

    /// Loads one enabled source while preventing an individual I/O failure from failing the aggregate refresh.
    ///
    /// @param source enabled source to load
    /// @return success, partial-failure, or failed source outcome
    private PluginSourceLoadResult load(PluginSource source) {
        long startedAt = System.nanoTime();
        try {
            return PluginSourceLoadExecutor.call(() -> loadWithinGlobalLimit(source, startedAt));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return PluginSourceLoadResult.failed(source, elapsedMillis(startedAt),
                    new IOException("Plugin source load was interrupted", exception));
        } catch (Exception exception) {
            if (exception instanceof IOException ioException) {
                return PluginSourceLoadResult.failed(source, elapsedMillis(startedAt), ioException);
            }
            return PluginSourceLoadResult.failed(source, elapsedMillis(startedAt),
                    new IOException("Failed to load plugin source", exception));
        }
    }

    /// Loads one source after the shared process-wide source-load permit has been acquired.
    ///
    /// @param source enabled source configuration
    /// @param startedAt monotonic source request start timestamp
    /// @return completed source load result
    /// @throws IOException if source transport, parsing, or validation fails
    private PluginSourceLoadResult loadWithinGlobalLimit(PluginSource source, long startedAt) throws IOException {
        PluginStoreManager manager = managerFactory.apply(source);
        if (manager == null) {
            throw new IOException("Plugin store manager factory returned null");
        }
        manager.loadSource(source);
        List<PluginStoreItem> items = manager.getStoreItems();
        int partialFailureCount = (int) items.stream().filter(item -> item.getManifest() == null).count();
        @Nullable PluginStoreRegistry registry = manager.getRegistry();
        if (registry == null) {
            throw new IOException("Plugin source loaded without a registry");
        }
        return PluginSourceLoadResult.success(
                source,
                elapsedMillis(startedAt),
                items,
                partialFailureCount,
                registry,
                manager
        );
    }

    /// Converts elapsed monotonic time to a non-negative millisecond duration.
    ///
    /// @param startedAt monotonic start timestamp
    /// @return elapsed milliseconds
    private static long elapsedMillis(long startedAt) {
        return Math.max(0, (System.nanoTime() - startedAt) / 1_000_000);
    }

    /// Builds a completed snapshot and publishes it only when no newer request has begun.
    ///
    /// @param requestGeneration generation for the completing request
    /// @param sourceRevision repository source revision captured for the request
    /// @param requests completed ordered source-result futures
    /// @return computed snapshot for this request, whether stale or current
    private PluginStoreSnapshot publishIfCurrent(
            long requestGeneration,
            long sourceRevision,
            List<CompletableFuture<PluginSourceLoadResult>> requests
    ) {
        PluginStoreSnapshot snapshot = new PluginStoreSnapshot(
                requestGeneration,
                sourceRevision,
                requests.stream().map(CompletableFuture::join).toList()
        );
        synchronized (publicationLock) {
            if (generation.get() == requestGeneration) {
                currentSnapshot.set(snapshot);
            }
            return snapshot;
        }
    }

    /// Returns the newest snapshot admitted by the generation publication gate.
    ///
    /// @return current aggregate snapshot, or `null` before any current refresh finishes
    public @Nullable PluginStoreSnapshot getCurrentSnapshot() {
        return currentSnapshot.get();
    }

    /// Stops unfinished source requests and releases the aggregator's dedicated executor threads.
    @Override
    public void close() {
        executor.shutdownNow();
    }
}
