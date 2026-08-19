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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// Immutable outcome of loading one configured plugin source during an aggregate refresh.
@NotNullByDefault
public final class PluginSourceLoadResult {
    /// Matches explicit hierarchical URI tokens embedded in transport error messages for sanitization.
    private static final Pattern URI_TOKEN_PATTERN = Pattern.compile(
            "\\b[a-z][a-z0-9+.-]*://[^\\s]+",
            Pattern.CASE_INSENSITIVE
    );

    /// Source configuration this outcome belongs to.
    private final PluginSource source;

    /// Explicit outcome status for this source.
    private final Status status;

    /// Elapsed source load duration in milliseconds.
    private final long durationMillis;

    /// Source-bound items published by a successfully loaded registry.
    private final @Unmodifiable List<PluginStoreItem> items;

    /// Number of registry items whose repository manifests could not be resolved.
    private final int partialManifestFailureCount;

    /// Validated source registry when the registry request succeeded.
    private final @Nullable PluginStoreRegistry registry;

    /// Source-scoped manager that owns the items and their source context.
    private final @Nullable PluginStoreManager manager;

    /// Full source failure retained for source details and logs.
    private final @Nullable IOException failure;

    /// Credential-safe compact failure detail suitable for rows and banners.
    private final @Nullable String failureMessage;

    /// Creates a validated source outcome after factory-level field combination checks.
    ///
    /// @param source source configuration
    /// @param status source result status
    /// @param durationMillis elapsed load duration in milliseconds
    /// @param items source-bound loaded items
    /// @param partialManifestFailureCount failed repository manifest count
    /// @param registry validated source registry, when present
    /// @param manager source-scoped item manager, when present
    /// @param failure full source failure, when present
    private PluginSourceLoadResult(
            PluginSource source,
            Status status,
            long durationMillis,
            @Unmodifiable List<PluginStoreItem> items,
            int partialManifestFailureCount,
            @Nullable PluginStoreRegistry registry,
            @Nullable PluginStoreManager manager,
            @Nullable IOException failure
    ) {
        this.source = Objects.requireNonNull(source, "source");
        this.status = Objects.requireNonNull(status, "status");
        if (durationMillis < 0) {
            throw new IllegalArgumentException("durationMillis must not be negative");
        }
        this.durationMillis = durationMillis;
        this.items = List.copyOf(items);
        if (partialManifestFailureCount < 0) {
            throw new IllegalArgumentException("partialManifestFailureCount must not be negative");
        }
        this.partialManifestFailureCount = partialManifestFailureCount;
        this.registry = registry;
        this.manager = manager;
        this.failure = failure;
        this.failureMessage = failure == null ? null : sanitizeFailureMessage(failure);
    }

    /// Returns an outcome for a configured source that was disabled before any request was submitted.
    ///
    /// @param source disabled source configuration
    /// @return disabled source result
    public static PluginSourceLoadResult disabled(PluginSource source) {
        return new PluginSourceLoadResult(source, Status.DISABLED, 0, List.of(), 0, null, null, null);
    }

    /// Returns a full or partial successful load result for one validated source registry.
    ///
    /// @param source loaded source configuration
    /// @param durationMillis elapsed load duration in milliseconds
    /// @param items source-bound loaded items
    /// @param partialManifestFailureCount repository manifests unavailable from this source
    /// @param registry validated source registry
    /// @param manager source-scoped manager bound to the returned items
    /// @return successful source result
    public static PluginSourceLoadResult success(
            PluginSource source,
            long durationMillis,
            @Unmodifiable List<PluginStoreItem> items,
            int partialManifestFailureCount,
            PluginStoreRegistry registry,
            PluginStoreManager manager
    ) {
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(manager, "manager");
        long actualPartialManifestFailureCount = items.stream().filter(item -> item.getManifest() == null).count();
        if (partialManifestFailureCount != actualPartialManifestFailureCount) {
            throw new IllegalArgumentException(
                    "partialManifestFailureCount does not match items with unavailable manifests"
            );
        }
        Status status = partialManifestFailureCount == 0 ? Status.SUCCESS : Status.PARTIAL_FAILURE;
        return new PluginSourceLoadResult(
                source,
                status,
                durationMillis,
                items,
                partialManifestFailureCount,
                registry,
                manager,
                null
        );
    }

    /// Returns a result for a source whose registry could not be loaded.
    ///
    /// @param source failed source configuration
    /// @param durationMillis elapsed load duration in milliseconds
    /// @param failure full transport, parsing, or validation failure
    /// @return failed source result
    public static PluginSourceLoadResult failed(PluginSource source, long durationMillis, IOException failure) {
        return new PluginSourceLoadResult(
                source,
                Status.FAILED,
                durationMillis,
                List.of(),
                0,
                null,
                null,
                Objects.requireNonNull(failure, "failure")
        );
    }

    /// Returns the source configuration that produced this outcome.
    ///
    /// @return configured source
    public PluginSource getSource() {
        return source;
    }

    /// Returns this source's aggregate load status.
    ///
    /// @return source load status
    public Status getStatus() {
        return status;
    }

    /// Returns elapsed source loading time.
    ///
    /// @return elapsed load duration in milliseconds
    public long getDurationMillis() {
        return durationMillis;
    }

    /// Returns source-bound catalog items in the registry's order.
    ///
    /// @return immutable source item list
    public @Unmodifiable List<PluginStoreItem> getItems() {
        return items;
    }

    /// Returns the number of registry items without a resolved repository manifest.
    ///
    /// @return partial repository manifest failure count
    public int getPartialManifestFailureCount() {
        return partialManifestFailureCount;
    }

    /// Returns the validated registry when this source loaded it successfully.
    ///
    /// @return validated registry, or `null` for disabled and failed sources
    public @Nullable PluginStoreRegistry getRegistry() {
        return registry;
    }

    /// Returns the source-scoped manager when this source loaded successfully.
    ///
    /// @return source manager, or `null` for disabled and failed sources
    public @Nullable PluginStoreManager getManager() {
        return manager;
    }

    /// Returns the full source failure retained for logs and source details.
    ///
    /// @return full I/O failure, or `null` if the source did not fail
    public @Nullable IOException getFailure() {
        return failure;
    }

    /// Returns a compact failure message without URL user information, paths, queries, or fragments.
    ///
    /// @return credential-safe source failure message, or `null` if the source did not fail
    public @Nullable String getFailureMessage() {
        return failureMessage;
    }

    /// Returns whether this source supplied items that may participate in winner selection.
    ///
    /// @return whether the registry loaded successfully
    public boolean isSuccessful() {
        return status == Status.SUCCESS || status == Status.PARTIAL_FAILURE;
    }

    /// Builds a display-safe message without retaining URL user information, paths, queries, or fragments.
    ///
    /// @param failure full source failure
    /// @return compact sanitized failure message
    private static String sanitizeFailureMessage(IOException failure) {
        String message = failure.getMessage();
        if (message == null || message.isBlank()) {
            return failure.getClass().getSimpleName();
        }
        Matcher matcher = URI_TOKEN_PATTERN.matcher(message);
        StringBuffer sanitized = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(sanitized, Matcher.quoteReplacement(sanitizeUrl(matcher.group())));
        }
        matcher.appendTail(sanitized);
        return sanitized.toString();
    }

    /// Removes a URL's user information, path, query, and fragment.
    ///
    /// @param url URL found in a failure message
    /// @return URL-safe origin or generic diagnostic text
    private static String sanitizeUrl(String url) {
        return PluginSourceLabels.diagnosticUrl(url);
    }

    /// Enumerates every explicit outcome of one source refresh request.
    public enum Status {
        /// The configured source was disabled and made no request.
        DISABLED,

        /// The registry and every repository manifest loaded successfully.
        SUCCESS,

        /// The registry loaded but at least one repository manifest did not.
        PARTIAL_FAILURE,

        /// The registry could not be loaded or validated.
        FAILED
    }
}
