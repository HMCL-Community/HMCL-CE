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
package org.jackhuang.hmcl.plugin.trust;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jackhuang.hmcl.Metadata;
import org.jackhuang.hmcl.util.io.HttpRequest;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// Owns the root-controlled online trust-status request and its authenticated rollback-resistant disk cache.
@NotNullByDefault
public final class PluginTrustStatusCache implements AutoCloseable {
    /// Cache wrapper schema independent of the signed snapshot schema.
    private static final int CACHE_SCHEMA_VERSION = 1;

    /// Maximum bytes accepted from either network or disk.
    private static final int MAX_STATUS_BYTES = 2 * 1024 * 1024;

    /// Maximum bytes accepted for one immutable repository proof.
    private static final int MAX_REPOSITORY_ATTESTATION_BYTES = 256 * 1024;

    /// Required refresh cadence after the plugin store has initialized this service.
    private static final Duration REFRESH_INTERVAL = Duration.ofHours(6);

    /// Lightweight scheduler cadence used to notice short failure retries promptly.
    private static final Duration SCHEDULER_POLL_INTERVAL = Duration.ofMinutes(1);

    /// Bounded failure backoffs; the final value remains the cap for subsequent failures.
    private static final @Unmodifiable List<Duration> FAILURE_BACKOFFS = List.of(
            Duration.ofMinutes(5),
            Duration.ofMinutes(30),
            Duration.ofHours(1)
    );

    /// Symmetric percentage jitter applied to each failure backoff to spread client retries.
    private static final int FAILURE_JITTER_PERCENT = 10;

    /// Maximum redirect count for the fixed status origin.
    private static final int MAX_REDIRECTS = 5;

    /// Verifier bound to the embedded role keys and fixed status URL.
    private final PluginTrustVerifier verifier;

    /// Atomic cache target.
    private final Path cacheFile;

    /// Clock used for freshness and refresh cadence.
    private final Clock clock;

    /// Single daemon scheduler used only after [#start()] is requested.
    private final ScheduledExecutorService scheduler;

    /// Prevents duplicate periodic scheduling.
    private final AtomicBoolean started = new AtomicBoolean();

    /// Latest cryptographically verified snapshot, including a stale snapshot retained for explicit revocations.
    private volatile @Nullable PluginTrustStatusSnapshot latestSnapshot;

    /// Exact signed envelope corresponding to [#latestSnapshot].
    private volatile @Nullable JsonObject latestEnvelope;

    /// Validator accepted with the latest authenticated 200 response.
    private volatile @Nullable String etag;

    /// Most recent successful authenticated refresh, including a valid HTTP 304 response.
    private volatile Instant lastSuccessfulRefresh = Instant.EPOCH;

    /// Most recent failed refresh completion used as the short-retry reference point.
    private volatile Instant lastFailedRefresh = Instant.EPOCH;

    /// Consecutive failed refresh count, capped at the final configured backoff stage.
    private volatile int consecutiveRefreshFailures;

    /// Per-instance entropy that gives otherwise identical retry stages independent jitter.
    private final long failureJitterSeed;

    /// Creates a cache, loading an existing authenticated file before returning.
    ///
    /// @param verifier root-bound verifier
    /// @param cacheFile atomic cache file
    /// @param clock freshness clock
    /// @throws IOException if an existing cache file is malformed or fails authentication
    public PluginTrustStatusCache(PluginTrustVerifier verifier, Path cacheFile, Clock clock) throws IOException {
        this(verifier, cacheFile, clock, true);
    }

    /// Creates one cache with optional startup loading for default error recovery.
    private PluginTrustStatusCache(
            PluginTrustVerifier verifier,
            Path cacheFile,
            Clock clock,
            boolean loadExisting
    ) throws IOException {
        this.verifier = Objects.requireNonNull(verifier, "verifier");
        this.cacheFile = Objects.requireNonNull(cacheFile, "cacheFile").toAbsolutePath().normalize();
        this.clock = Objects.requireNonNull(clock, "clock");
        this.scheduler = Executors.newSingleThreadScheduledExecutor(statusThreadFactory());
        this.failureJitterSeed = ThreadLocalRandom.current().nextLong();
        if (loadExisting && Files.exists(this.cacheFile)) {
            loadCache();
        }
    }

    /// Returns the process-wide cache stored below the launcher-local home.
    public static PluginTrustStatusCache getDefault() {
        return DefaultHolder.INSTANCE;
    }

    /// Starts one immediate background refresh followed by minute-level due checks.
    public void start() {
        if (verifier.getTrustStatusUrl().isBlank() || !started.compareAndSet(false, true)) {
            return;
        }
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                refreshIfDue();
            } catch (IOException | RuntimeException exception) {
                LOG.warning("Unable to refresh HMCL CE plugin trust status; retaining the last valid cache", exception);
            }
        }, 0, SCHEDULER_POLL_INTERVAL.toMinutes(), TimeUnit.MINUTES);
    }

    /// Refreshes synchronously after the success interval or current bounded failure backoff becomes due.
    ///
    /// @throws IOException if the due network response cannot be authenticated or persisted
    public synchronized void refreshIfDue() throws IOException {
        if (verifier.getTrustStatusUrl().isBlank()) {
            return;
        }
        Instant now = clock.instant();
        Instant cadenceReference = consecutiveRefreshFailures == 0
                ? lastSuccessfulRefresh
                : lastFailedRefresh;
        Duration cadence = consecutiveRefreshFailures == 0
                ? REFRESH_INTERVAL
                : failureBackoff(consecutiveRefreshFailures);
        if (now.isBefore(cadenceReference.plus(cadence))) {
            return;
        }
        refresh();
    }

    /// Fetches and atomically publishes one newer authenticated snapshot.
    ///
    /// A 304 response retains the current cache byte-for-byte. Every failure leaves both the in-memory snapshot and
    /// disk cache unchanged.
    ///
    /// @throws IOException if transport, bounds, signature, freshness, rollback, or atomic persistence fails
    public synchronized void refresh() throws IOException {
        try {
            refreshNow();
            lastSuccessfulRefresh = clock.instant();
            lastFailedRefresh = Instant.EPOCH;
            consecutiveRefreshFailures = 0;
        } catch (IOException | RuntimeException exception) {
            lastFailedRefresh = clock.instant();
            consecutiveRefreshFailures = Math.min(
                    consecutiveRefreshFailures + 1,
                    FAILURE_BACKOFFS.size()
            );
            throw exception;
        }
    }

    /// Performs one network refresh without changing cadence bookkeeping.
    private void refreshNow() throws IOException {
        String statusUrl = verifier.getTrustStatusUrl();
        if (statusUrl.isBlank()) {
            throw new IOException("The embedded plugin trust root has no statusUrl");
        }
        StatusResponse response = fetch(statusUrl, etag, MAX_STATUS_BYTES, "trust-status");
        if (response.notModified()) {
            @Nullable PluginTrustStatusSnapshot current = latestSnapshot;
            if (current == null) {
                throw new IOException("Trust-status endpoint returned 304 without a cached snapshot");
            }
            if (!current.isFreshAt(clock.instant())) {
                throw new IOException("Trust-status endpoint returned 304 for an expired cached snapshot");
            }
            return;
        }
        @Nullable byte @Unmodifiable [] body = response.body();
        if (body == null) {
            throw new IOException("Trust-status response has no body");
        }
        final JsonObject envelope;
        final PluginTrustStatusSnapshot candidate;
        try {
            JsonElement parsed = JsonParser.parseString(new String(body, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) {
                throw new IllegalArgumentException("status response is not an object");
            }
            envelope = parsed.getAsJsonObject();
            candidate = verifier.verifyTrustStatusSnapshot(envelope);
        } catch (RuntimeException exception) {
            throw new IOException("Trust-status response failed signature or schema verification", exception);
        }
        Instant now = clock.instant();
        if (!candidate.isFreshAt(now)) {
            throw new IOException("Trust-status response is not fresh");
        }
        @Nullable PluginTrustStatusSnapshot previous = latestSnapshot;
        if (previous != null && (candidate.version() <= previous.version()
                || !candidate.generatedAt().isAfter(previous.generatedAt()))) {
            throw new IOException("Trust-status response attempted a version or generation-time rollback");
        }
        if (previous != null) {
            enforceMonotonicRevocations(previous, candidate);
        }
        JsonObject envelopeCopy = envelope.deepCopy();
        persist(envelopeCopy, candidate, response.etag());
        latestEnvelope = envelopeCopy;
        latestSnapshot = candidate;
        etag = response.etag();
    }

    /// Returns the capped failure delay with stable per-instance symmetric jitter.
    ///
    /// @param failureCount positive consecutive failure count
    /// @return retry delay within ten percent of the selected backoff stage
    private Duration failureBackoff(int failureCount) {
        Duration base = FAILURE_BACKOFFS.get(Math.min(failureCount, FAILURE_BACKOFFS.size()) - 1);
        long jitterRangeSeconds = Math.max(1, base.toSeconds() * FAILURE_JITTER_PERCENT / 100);
        long jitterSlots = jitterRangeSeconds * 2 + 1;
        long mixedSeed = failureJitterSeed ^ (0x9E3779B97F4A7C15L * failureCount);
        long jitterSeconds = Math.floorMod(mixedSeed, jitterSlots) - jitterRangeSeconds;
        return base.plusSeconds(jitterSeconds);
    }

    /// Returns the latest authenticated snapshot even after it becomes stale.
    ///
    /// @return latest snapshot or `null` before one is available
    public @Nullable PluginTrustStatusSnapshot getLatestSnapshot() {
        return latestSnapshot;
    }

    /// Returns only a snapshot fresh enough to grant new installation or update certification.
    ///
    /// @return fresh snapshot or `null`
    public @Nullable PluginTrustStatusSnapshot getFreshSnapshot() {
        @Nullable PluginTrustStatusSnapshot snapshot = latestSnapshot;
        return snapshot != null && snapshot.isFreshAt(clock.instant()) ? snapshot : null;
    }

    /// Returns whether the latest authenticated cache explicitly revokes one exact artifact.
    ///
    /// Staleness does not erase an already observed revocation. A stale cache still cannot grant certification.
    ///
    /// @param pluginId plugin ID
    /// @param version package version
    /// @param sha256 complete NPL SHA-256
    /// @return whether an explicit exact revocation exists
    public boolean isArtifactRevoked(String pluginId, String version, String sha256) {
        @Nullable PluginTrustStatusSnapshot snapshot = latestSnapshot;
        return snapshot != null && snapshot.findRevokedArtifact(pluginId, version, sha256) != null;
    }

    /// Prevents a newer signed snapshot from erasing any irreversible revocation already accepted by this client.
    private static void enforceMonotonicRevocations(
            PluginTrustStatusSnapshot previous,
            PluginTrustStatusSnapshot candidate
    ) throws IOException {
        for (PluginTrustStatusSnapshot.RevokedArtifact revoked : previous.revokedArtifacts()) {
            if (candidate.findRevokedArtifact(revoked.pluginId(), revoked.version(), revoked.sha256()) == null) {
                throw new IOException("Trust-status response removed an accepted artifact revocation");
            }
        }
        for (String revokedKeyId : previous.revokedKeyIds()) {
            if (!candidate.isKeyRevoked(revokedKeyId)) {
                throw new IOException("Trust-status response removed an accepted key revocation");
            }
        }
        for (PluginTrustStatusSnapshot.RepositoryStatus repository : previous.repositories()) {
            if (!repository.status().equals("revoked")) {
                continue;
            }
            @Nullable PluginTrustStatusSnapshot.RepositoryStatus replacement =
                    candidate.findRepository(repository.repositoryId());
            if (replacement == null || !replacement.status().equals("revoked")) {
                throw new IOException("Trust-status response removed an accepted repository revocation");
            }
        }
    }

    /// Resolves one immutable historical repository proof from an authenticated disk cache or the root origin.
    ///
    /// The verification ID is already role-signed by the artifact proof. It is used only as one bounded path segment,
    /// and redirects cannot leave the root status origin.
    ///
    /// @param verificationId artifact-bound repository verification ID
    /// @return role-verified historical repository proof
    /// @throws IOException if no valid cached or online proof is available
    public synchronized PluginRepositoryAttestation resolveRepositoryAttestation(String verificationId)
            throws IOException {
        return resolveRepositoryAttestationDocument(verificationId).attestation();
    }

    /// Resolves one immutable historical repository proof together with its complete signed envelope.
    ///
    /// The envelope is retained by certified installation receipts so startup can re-verify the original role
    /// signature instead of trusting derived repository IDs or signer key IDs from local state.
    ///
    /// @param verificationId artifact-bound repository verification ID
    /// @return complete proof document and verified values
    /// @throws IOException if no valid cached or online proof is available
    public synchronized PluginRepositoryAttestationDocument resolveRepositoryAttestationDocument(
            String verificationId
    ) throws IOException {
        if (!verificationId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) {
            throw new IOException("Repository verification ID is invalid");
        }
        Path proofDirectory = repositoryAttestationDirectory();
        Path proofFile = proofDirectory.resolve(verificationId + ".json").normalize();
        if (!proofFile.getParent().equals(proofDirectory)) {
            throw new IOException("Repository verification ID escaped its cache directory");
        }
        if (Files.exists(proofFile)) {
            return readRepositoryAttestationDocument(proofFile, verificationId);
        }

        URI endpoint = repositoryAttestationUri(verificationId);
        StatusResponse response = fetch(
                endpoint.toString(),
                null,
                MAX_REPOSITORY_ATTESTATION_BYTES,
                "repository attestation"
        );
        @Nullable byte @Unmodifiable [] body = response.body();
        if (response.notModified() || body == null) {
            throw new IOException("Repository attestation endpoint returned no proof");
        }
        PluginRepositoryAttestationDocument document = parseRepositoryAttestationDocument(body, verificationId);
        writeAtomically(proofFile, body, "repository attestation cache");
        return document;
    }

    /// Stops periodic refresh work owned by this cache instance.
    @Override
    public void close() {
        scheduler.shutdownNow();
    }

    /// Loads and authenticates the existing cache wrapper.
    private void loadCache() throws IOException {
        byte @Unmodifiable [] bytes;
        try (InputStream input = new BufferedInputStream(Files.newInputStream(cacheFile))) {
            bytes = input.readNBytes(MAX_STATUS_BYTES + 1);
        }
        if (bytes.length > MAX_STATUS_BYTES) {
            throw new IOException("Plugin trust-status cache exceeds the maximum size");
        }
        try {
            JsonElement parsed = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) {
                throw new IllegalArgumentException("cache wrapper is not an object");
            }
            JsonObject wrapper = parsed.getAsJsonObject();
            if (PluginTrustRoot.integer(wrapper, "schemaVersion") != CACHE_SCHEMA_VERSION) {
                throw new IllegalArgumentException("unsupported cache wrapper schema");
            }
            JsonObject envelope = PluginTrustRoot.object(wrapper, "envelope");
            PluginTrustStatusSnapshot snapshot = verifier.verifyTrustStatusSnapshot(envelope);
            if (positiveLong(wrapper, "highestVersion") != snapshot.version()
                    || !Instant.parse(PluginTrustRoot.string(wrapper, "highestGeneratedAt"))
                    .equals(snapshot.generatedAt())) {
                throw new IllegalArgumentException("cache rollback metadata does not match its signed snapshot");
            }
            @Nullable String cachedEtag = PluginTrustRoot.optionalString(wrapper, "etag");
            validateEtag(cachedEtag);
            latestEnvelope = envelope.deepCopy();
            latestSnapshot = snapshot;
            etag = cachedEtag;
        } catch (RuntimeException exception) {
            throw new IOException("Plugin trust-status cache is invalid", exception);
        }
    }

    /// Atomically writes one authenticated cache wrapper in the target directory.
    private void persist(
            JsonObject envelope,
            PluginTrustStatusSnapshot snapshot,
            @Nullable String responseEtag
    ) throws IOException {
        validateEtag(responseEtag);
        JsonObject wrapper = new JsonObject();
        wrapper.addProperty("schemaVersion", CACHE_SCHEMA_VERSION);
        wrapper.addProperty("highestVersion", snapshot.version());
        wrapper.addProperty("highestGeneratedAt", snapshot.generatedAt().toString());
        if (responseEtag != null) {
            wrapper.addProperty("etag", responseEtag);
        }
        wrapper.add("envelope", envelope);
        byte[] bytes = (wrapper.toString() + "\n").getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_STATUS_BYTES) {
            throw new IOException("Plugin trust-status cache exceeds the maximum size");
        }
        writeAtomically(cacheFile, bytes, "plugin trust-status cache");
    }

    /// Writes bounded authenticated bytes through a durable same-directory atomic replacement.
    private static void writeAtomically(Path target, byte @Unmodifiable [] bytes, String description)
            throws IOException {
        @Nullable Path parent = target.getParent();
        if (parent == null) {
            throw new IOException(description + " has no parent directory");
        }
        Files.createDirectories(parent);
        Path temporary = parent.resolve("." + target.getFileName() + "." + UUID.randomUUID() + ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(
                    temporary,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE
            )) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            try {
                Files.move(
                        temporary,
                        target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING
                );
            } catch (AtomicMoveNotSupportedException exception) {
                throw new IOException("Atomic " + description + " replacement is unavailable", exception);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /// Returns the normalized immutable-proof cache directory beside the status cache.
    private Path repositoryAttestationDirectory() throws IOException {
        @Nullable Path parent = cacheFile.getParent();
        if (parent == null) {
            throw new IOException("Plugin trust-status cache has no parent directory");
        }
        return parent.resolve("repository-attestations").toAbsolutePath().normalize();
    }

    /// Derives one fixed repository-proof endpoint solely from the root status origin.
    private URI repositoryAttestationUri(String verificationId) throws IOException {
        URI status = validateStatusUri(verifier.getTrustStatusUrl());
        try {
            return new URI(
                    status.getScheme(),
                    null,
                    status.getHost(),
                    status.getPort(),
                    "/api/v1/repositories/attestations/" + verificationId,
                    null,
                    null
            );
        } catch (URISyntaxException exception) {
            throw new IOException("Unable to derive repository attestation URL", exception);
        }
    }

    /// Reads and verifies one immutable cached repository proof.
    private PluginRepositoryAttestationDocument readRepositoryAttestationDocument(
            Path proofFile,
            String verificationId
    )
            throws IOException {
        byte @Unmodifiable [] bytes;
        try (InputStream input = new BufferedInputStream(Files.newInputStream(proofFile))) {
            bytes = input.readNBytes(MAX_REPOSITORY_ATTESTATION_BYTES + 1);
        }
        if (bytes.length > MAX_REPOSITORY_ATTESTATION_BYTES) {
            throw new IOException("Repository attestation cache exceeds the maximum size");
        }
        return parseRepositoryAttestationDocument(bytes, verificationId);
    }

    /// Parses, authenticates, and binds one historical repository proof to its requested ID.
    private PluginRepositoryAttestationDocument parseRepositoryAttestationDocument(
            byte @Unmodifiable [] bytes,
            String verificationId
    ) throws IOException {
        try {
            JsonElement parsed = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) {
                throw new IllegalArgumentException("repository attestation is not an object");
            }
            JsonObject envelope = parsed.getAsJsonObject();
            PluginRepositoryAttestation attestation = verifier.verifyHistoricalRepositoryAttestation(envelope);
            if (!attestation.verificationId().equals(verificationId)) {
                throw new IllegalArgumentException("repository attestation verification ID does not match");
            }
            return new PluginRepositoryAttestationDocument(envelope, attestation);
        } catch (RuntimeException exception) {
            throw new IOException("Repository attestation failed signature or schema verification", exception);
        }
    }

    /// Downloads one bounded conditional response while constraining redirects to the root-selected origin.
    private static StatusResponse fetch(
            String statusUrl,
            @Nullable String etag,
            int maximumBytes,
            String description
    ) throws IOException {
        URI initial = validateStatusUri(statusUrl);
        URI current = initial;
        for (int redirect = 0; redirect <= MAX_REDIRECTS; redirect++) {
            HttpRequest request = HttpRequest.GET(current.toString()).accept("application/json");
            if (etag != null) {
                request.header("If-None-Match", etag);
            }
            HttpURLConnection connection = request.createConnection();
            connection.setInstanceFollowRedirects(false);
            try {
                int code = connection.getResponseCode();
                if (code == HttpURLConnection.HTTP_NOT_MODIFIED) {
                    return new StatusResponse(true, null, etag);
                }
                if (isRedirect(code)) {
                    if (redirect == MAX_REDIRECTS) {
                        throw new IOException(description + " request has too many redirects");
                    }
                    @Nullable String location = connection.getHeaderField("Location");
                    if (location == null || location.isBlank()) {
                        throw new IOException(description + " redirect has no Location header");
                    }
                    URI target;
                    try {
                        target = current.resolve(new URI(location));
                    } catch (IllegalArgumentException | URISyntaxException exception) {
                        throw new IOException(description + " redirect is invalid", exception);
                    }
                    target = validateStatusUri(target.toString());
                    if (!sameOrigin(initial, target)) {
                        throw new IOException(description + " redirect left the root-selected origin");
                    }
                    current = target;
                    continue;
                }
                if (code / 100 != 2) {
                    throw new IOException(description + " request failed with HTTP " + code);
                }
                if (connection.getContentLengthLong() > maximumBytes) {
                    throw new IOException(description + " response exceeds the maximum size");
                }
                byte @Unmodifiable [] body;
                try (InputStream input = new BufferedInputStream(connection.getInputStream())) {
                    body = input.readNBytes(maximumBytes + 1);
                }
                if (body.length > maximumBytes) {
                    throw new IOException(description + " response exceeds the maximum size");
                }
                @Nullable String responseEtag = connection.getHeaderField("ETag");
                validateEtag(responseEtag);
                return new StatusResponse(false, body, responseEtag);
            } finally {
                connection.disconnect();
            }
        }
        throw new IOException(description + " request has too many redirects");
    }

    /// Parses a status URI while allowing loopback HTTP only for isolated development and tests.
    private static URI validateStatusUri(String url) throws IOException {
        final URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException exception) {
            throw new IOException("Plugin trust-status URL is invalid", exception);
        }
        @Nullable String host = uri.getHost();
        boolean loopback = host != null && (host.equalsIgnoreCase("localhost")
                || host.equals("127.0.0.1") || host.equals("::1") || host.equals("[::1]"));
        if (host == null || uri.getUserInfo() != null
                || !("https".equalsIgnoreCase(uri.getScheme())
                || loopback && "http".equalsIgnoreCase(uri.getScheme()))) {
            throw new IOException("Plugin trust-status URL must use HTTPS");
        }
        return uri;
    }

    /// Returns whether two status URIs share scheme, host, and effective port.
    private static boolean sameOrigin(URI first, URI second) {
        return first.getScheme().equalsIgnoreCase(second.getScheme())
                && first.getHost().equalsIgnoreCase(second.getHost())
                && effectivePort(first) == effectivePort(second);
    }

    /// Returns the explicit or scheme-default port for origin comparison.
    private static int effectivePort(URI uri) {
        if (uri.getPort() >= 0) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    /// Returns whether a response code carries a Location redirect.
    private static boolean isRedirect(int code) {
        return code >= 300 && code <= 308 && code != 304 && code != 306;
    }

    /// Validates a bounded header value before persisting or replaying it.
    private static void validateEtag(@Nullable String value) {
        if (value != null && (value.isBlank() || value.length() > 512
                || value.chars().anyMatch(character -> character < 0x20 || character == 0x7f))) {
            throw new IllegalArgumentException("Plugin trust-status ETag is invalid");
        }
    }

    /// Reads one positive exact long from a cache wrapper.
    private static long positiveLong(JsonObject object, String field) {
        JsonElement element = object.get(field);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("Cache integer field is missing: " + field);
        }
        try {
            long value = element.getAsBigDecimal().longValueExact();
            if (value <= 0 || value > CanonicalJson.MAX_SAFE_INTEGER) {
                throw new IllegalArgumentException("Cache integer field must be a positive safe integer: " + field);
            }
            return value;
        } catch (ArithmeticException | NumberFormatException exception) {
            throw new IllegalArgumentException("Cache integer field is invalid: " + field, exception);
        }
    }

    /// Creates named daemon threads that cannot keep HMCL alive.
    private static ThreadFactory statusThreadFactory() {
        return task -> {
            Thread thread = new Thread(task, "plugin-trust-status-refresh");
            thread.setDaemon(true);
            return thread;
        };
    }

    /// One bounded HTTP result.
    ///
    /// @param notModified whether HTTP 304 retained the cache
    /// @param body response bytes or `null` for 304
    /// @param etag authenticated-response validator or `null`
    @NotNullByDefault
    private record StatusResponse(
            boolean notModified,
            byte @Nullable @Unmodifiable [] body,
            @Nullable String etag
    ) {
    }

    /// Lazily constructs a best-effort process cache without making malformed local state fatal to launcher startup.
    @NotNullByDefault
    private static final class DefaultHolder {
        /// Process-wide cache instance.
        private static final PluginTrustStatusCache INSTANCE = create();

        /// Creates the process cache and discards only unauthenticated cached input on load failure.
        private static PluginTrustStatusCache create() {
            try {
                PluginTrustVerifier verifier = PluginTrustVerifier.loadDefault();
                Path cache = Metadata.HMCL_LOCAL_HOME.resolve("plugin-trust").resolve("trust-status.json");
                try {
                    return new PluginTrustStatusCache(verifier, cache, Clock.systemUTC());
                } catch (IOException exception) {
                    LOG.warning("Ignoring invalid cached HMCL CE plugin trust status", exception);
                    return new PluginTrustStatusCache(verifier, cache, Clock.systemUTC(), false);
                }
            } catch (IOException exception) {
                throw new IllegalStateException("Failed to initialize HMCL CE plugin trust status", exception);
            }
        }
    }
}
