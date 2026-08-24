/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2020  huangyuhui <huanghongxun2008@126.com> and contributors
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

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import org.jackhuang.hmcl.Metadata;
import org.jackhuang.hmcl.plugin.PluginArtifactIdentity;
import org.jackhuang.hmcl.plugin.PluginManifest;
import org.jackhuang.hmcl.plugin.PluginVersion;
import org.jackhuang.hmcl.plugin.runtime.PluginCompatibilityEvaluator;
import org.jackhuang.hmcl.plugin.runtime.PluginCompatibilityResult;
import org.jackhuang.hmcl.plugin.trust.PluginCertificationReceipt;
import org.jackhuang.hmcl.plugin.trust.PluginDocumentVerification;
import org.jackhuang.hmcl.plugin.trust.PluginRepositoryAttestation;
import org.jackhuang.hmcl.plugin.trust.PluginRepositoryAttestationDocument;
import org.jackhuang.hmcl.plugin.trust.PluginTrustLevel;
import org.jackhuang.hmcl.plugin.trust.PluginTrustResult;
import org.jackhuang.hmcl.plugin.trust.PluginTrustStatusCache;
import org.jackhuang.hmcl.plugin.trust.PluginTrustStatusSnapshot;
import org.jackhuang.hmcl.plugin.trust.PluginTrustVerifier;
import org.jackhuang.hmcl.plugin.trust.PluginVerifiedCertification;
import org.jackhuang.hmcl.util.gson.JsonUtils;
import org.jackhuang.hmcl.util.io.HttpRequest;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.concurrent.ConcurrentHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// Resolves validated remote registries, checks compatibility, and downloads verified plugin packages atomically.
@NotNullByDefault
public final class PluginStoreManager {
    /// Fixed official HMCL CE plugin registry endpoint.
    public static final String DEFAULT_REGISTRY_URL =
            System.getProperty("hmcl.plugin_store.registry",
                    "https://raw.githubusercontent.com/HMCL-Community/HMCL-CE-Plugin-Store/main/plugins.json");

    /// Whether the optional default registry should be loaded automatically.
    public static final boolean DEFAULT_REGISTRY_ENABLED = Boolean.parseBoolean(
            System.getProperty(
                    "hmcl.plugin_store.enabled",
                    Boolean.toString(hasEmbeddedOfficialRepositoryRole())
            )
    );

    /// GitHub repository search endpoint used by the built-in `hmclce` Topic source.
    public static final String GITHUB_TOPIC_API_URL = System.getProperty(
            "hmcl.plugin_store.github_api", "https://api.github.com/search/repositories"
    );

    /// GitHub raw-content base used by the built-in `hmclce` Topic source.
    public static final String GITHUB_RAW_BASE_URL = System.getProperty(
            "hmcl.plugin_store.github_raw", "https://raw.githubusercontent.com"
    );

    /// Hard upper bound for any downloaded plugin package.
    private static final long MAX_PACKAGE_BYTES = 512L * 1024L * 1024L;

    /// Maximum UTF-8 bytes accepted for the top-level registry document.
    private static final int MAX_REGISTRY_BYTES = 2 * 1024 * 1024;

    /// Maximum UTF-8 bytes accepted for one plugin repository manifest.
    private static final int MAX_STORE_MANIFEST_BYTES = 4 * 1024 * 1024;

    /// Maximum plugin manifest bytes inspected before an atomic package replacement.
    private static final int MAX_PLUGIN_MANIFEST_BYTES = 1024 * 1024;

    /// Maximum README bytes retained and rendered by the store.
    private static final int MAX_README_BYTES = 2 * 1024 * 1024;

    /// Maximum redirects followed for one store-owned HTTP request.
    private static final int MAX_REDIRECTS = 20;

    /// Extracts the first Java feature number from registry text.
    private static final Pattern JAVA_VERSION_PATTERN = Pattern.compile("(\\d+)");

    /// Detects whether this build contains a production-capable official repository role.
    private static boolean hasEmbeddedOfficialRepositoryRole() {
        try (InputStream input = PluginStoreManager.class.getResourceAsStream(
                "/assets/hmclce-plugin-root.json"
        )) {
            if (input == null) {
                return false;
            }
            JsonElement document = JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8));
            return document.isJsonObject()
                    && document.getAsJsonObject().has("signed")
                    && document.getAsJsonObject().get("signed").isJsonObject()
                    && document.getAsJsonObject().getAsJsonObject("signed").has("roles")
                    && document.getAsJsonObject().getAsJsonObject("signed").get("roles").isJsonObject()
                    && document.getAsJsonObject().getAsJsonObject("signed")
                    .getAsJsonObject("roles").has("official-repository");
        } catch (IOException | RuntimeException exception) {
            return false;
        }
    }

    /// Atomically published source, registry, and source-owned request caches.
    private volatile @Nullable SourceContext context;

    /// Verifies signed official registries while ordinary manifests remain community content.
    private final PluginTrustVerifier trustVerifier;

    /// Legacy online status cache retained only for explicit compatibility fixtures.
    private final @Nullable PluginTrustStatusCache trustStatusCache;

    /// Raw-content base used for GitHub Topic repository manifests.
    private final String githubRawBaseUrl;

    /// Shared launcher, platform, runtime, and ABI compatibility evaluator.
    private final PluginCompatibilityEvaluator compatibilityEvaluator;

    /// README cache retained only for the historical explicit-manifest API before a source has loaded.
    private final Map<String, String> unloadedReadmeCache = new ConcurrentHashMap<>();

    /// Captures one source generation so readers cannot combine registry state or cache results across replacements.
    @NotNullByDefault
    static final class SourceContext {
        /// Immutable source configuration validated for this generation.
        private final PluginSource source;

        /// Registry validated for this source generation.
        private final PluginStoreRegistry registry;

        /// Validated manifests resolved only for this source generation.
        private final Map<String, PluginStoreManifest> manifestCache = new ConcurrentHashMap<>();

        /// Raw manifests prefetched by GitHub Topic discovery.
        private final Map<String, String> prefetchedManifestContents = new ConcurrentHashMap<>();

        /// Externally established GitHub identities by manifest URL.
        private final Map<String, String> repositoryIdentities = new ConcurrentHashMap<>();

        /// Topic repositories excluded before they could become registry entries.
        private final int skippedRepositoryCount;

        /// Trust results bound to manifests in this source generation.
        private final Map<String, PluginTrustResult> manifestTrust = new ConcurrentHashMap<>();

        /// Historical repository proofs cached by artifact-signed verification ID for this source generation.
        private final Map<String, RepositoryAttestationResolution> repositoryAttestations = new ConcurrentHashMap<>();

        /// Trust result for the registry document itself.
        private final PluginTrustResult registryTrust;

        /// Bounded README text resolved only for this source generation.
        private final Map<String, String> readmeCache = new ConcurrentHashMap<>();

        /// Creates a source context after the registry has completed validation.
        ///
        /// @param source immutable source configuration
        /// @param registry validated registry
        /// @param registryTrust trust result for the registry document
        /// @param prefetchedManifestContents raw manifests prefetched during Topic discovery
        /// @param repositoryIdentities externally established repository identities by manifest URL
        /// @param skippedRepositoryCount Topic repositories excluded before registry publication
        private SourceContext(
                PluginSource source,
                PluginStoreRegistry registry,
                PluginTrustResult registryTrust,
                Map<String, String> prefetchedManifestContents,
                Map<String, String> repositoryIdentities,
                int skippedRepositoryCount
        ) {
            if (skippedRepositoryCount < 0) {
                throw new IllegalArgumentException("skippedRepositoryCount must not be negative");
            }
            this.source = source;
            this.registry = registry;
            this.registryTrust = registryTrust;
            this.prefetchedManifestContents.putAll(prefetchedManifestContents);
            this.repositoryIdentities.putAll(repositoryIdentities);
            this.skippedRepositoryCount = skippedRepositoryCount;
        }
    }

    /// Creates an unloaded source-scoped store client.
    public PluginStoreManager() {
        this(loadDefaultTrustVerifier(), null, GITHUB_RAW_BASE_URL, createDefaultCompatibilityEvaluator());
    }

    /// Creates an unloaded client with an explicit GitHub raw-content base for package-local tests.
    ///
    /// @param githubRawBaseUrl raw-content base used for Topic repository manifests
    PluginStoreManager(String githubRawBaseUrl) {
        this(loadDefaultTrustVerifier(), null, githubRawBaseUrl, createDefaultCompatibilityEvaluator());
    }

    /// Creates an unloaded client with a deterministic compatibility evaluator for package-local tests.
    ///
    /// @param compatibilityEvaluator evaluator with the desired runtime registry and host platform
    PluginStoreManager(PluginCompatibilityEvaluator compatibilityEvaluator) {
        this(loadDefaultTrustVerifier(), null, GITHUB_RAW_BASE_URL, compatibilityEvaluator);
    }

    /// Loads the embedded plugin trust root for a new store manager.
    ///
    /// @return configured trust verifier
    /// @throws IllegalStateException if the embedded trust root cannot be loaded
    private static PluginTrustVerifier loadDefaultTrustVerifier() {
        try {
            return PluginTrustVerifier.loadDefault();
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to load HMCL CE plugin trust root", exception);
        }
    }

    /// Returns the production evaluator backed by the process-wide runtime provider registry.
    ///
    /// @return production compatibility evaluator
    private static PluginCompatibilityEvaluator createDefaultCompatibilityEvaluator() {
        return PluginCompatibilityEvaluator.processWide();
    }

    /// Creates a store manager with an explicit verifier for package-local tests.
    PluginStoreManager(PluginTrustVerifier trustVerifier) {
        this(trustVerifier, null, GITHUB_RAW_BASE_URL, createDefaultCompatibilityEvaluator());
    }

    /// Creates a store manager with explicit trust and status services for package-local tests.
    ///
    /// @param trustVerifier role-separated signature verifier
    /// @param trustStatusCache legacy authenticated status cache, or `null` under the current policy
    PluginStoreManager(PluginTrustVerifier trustVerifier, @Nullable PluginTrustStatusCache trustStatusCache) {
        this(trustVerifier, trustStatusCache, GITHUB_RAW_BASE_URL, createDefaultCompatibilityEvaluator());
    }

    /// Creates a store manager with explicit trust, status, and Topic transport dependencies.
    ///
    /// @param trustVerifier role-separated signature verifier
    /// @param trustStatusCache legacy authenticated status cache, or `null` under the current policy
    /// @param githubRawBaseUrl raw-content base used for Topic repository manifests
    /// @param compatibilityEvaluator shared compatibility evaluator for store filtering
    private PluginStoreManager(
            PluginTrustVerifier trustVerifier,
            @Nullable PluginTrustStatusCache trustStatusCache,
            String githubRawBaseUrl,
            PluginCompatibilityEvaluator compatibilityEvaluator
    ) {
        this.trustVerifier = Objects.requireNonNull(trustVerifier, "trustVerifier");
        this.trustStatusCache = trustStatusCache;
        this.githubRawBaseUrl = Objects.requireNonNull(githubRawBaseUrl, "githubRawBaseUrl");
        this.compatibilityEvaluator = Objects.requireNonNull(compatibilityEvaluator, "compatibilityEvaluator");
    }

    /// Loads and validates one plugin source without persisting user configuration.
    ///
    /// @param source immutable source configuration to load
    /// @throws IOException if transport, parsing, URL policy, or validation fails
    public void loadSource(PluginSource source) throws IOException {
        Objects.requireNonNull(source, "source");
        if (source.isGitHubTopic()) {
            GitHubTopicDiscovery.Result discovery = new GitHubTopicDiscovery(
                    source.getUrl(),
                    githubRawBaseUrl,
                    "hmclce",
                    System.getProperty("hmcl.plugin_store.github_token"),
                    10
            ).discover();
            context = new SourceContext(
                    source,
                    discovery.registry(),
                    PluginTrustResult.community(),
                    discovery.manifestContents(),
                    discovery.repositoryIdentities(),
                    discovery.skippedRepositoryCount()
            );
            return;
        }
        RegistryLoad loaded = loadRegistryForRequest(source);
        Map<String, String> identities = new LinkedHashMap<>();
        for (PluginStoreRegistry.PluginStoreEntry entry : loaded.registry().getPlugins()) {
            if (!entry.getRepository().isBlank()) {
                try {
                    identities.put(entry.getManifestUrl(), PluginTrustVerifier.normalizeRepository(entry.getRepository()));
                } catch (IllegalArgumentException ignored) {
                    // Non-GitHub registries remain valid community sources but cannot receive developer certification.
                }
            }
        }
        context = new SourceContext(source, loaded.registry(), loaded.trust(), Map.of(), identities, 0);
    }

    /// Attempts a due root-controlled status refresh without making an offline source load fail.
    private void refreshTrustStatusIfDue() {
        @Nullable PluginTrustStatusCache cache = trustStatusCache;
        if (cache == null) {
            return;
        }
        try {
            cache.refreshIfDue();
        } catch (IOException exception) {
            LOG.warning("Unable to refresh plugin trust status; using the last authenticated cache", exception);
        }
    }

    /// Returns the source associated with the currently loaded registry.
    ///
    /// @return loaded source
    /// @throws IllegalStateException if no source has loaded successfully
    public PluginSource getSource() {
        return requireContext().source;
    }

    /// Returns Topic repositories excluded before they could become source items.
    ///
    /// @return skipped repository count, or zero before loading and for ordinary registry sources
    public int getSkippedRepositoryCount() {
        @Nullable SourceContext currentContext = context;
        return currentContext == null ? 0 : currentContext.skippedRepositoryCount;
    }

    /// Returns the current source context or rejects operations before a successful source load.
    ///
    /// @return atomically published source context
    /// @throws IllegalStateException if no source has loaded successfully
    private SourceContext requireContext() {
        @Nullable SourceContext currentContext = context;
        if (currentContext == null) {
            throw new IllegalStateException("Plugin source is not loaded");
        }
        return currentContext;
    }

    /// Loads and validates a registry response for the supplied source URL.
    ///
    /// The caller publishes source identity only after this validation succeeds, keeping failed requests from
    /// replacing the previously loaded source context.
    ///
    /// @param registryUrl registry URL
    /// @throws IOException if transport, parsing, URL policy, or validation fails
    private RegistryLoad loadRegistryForRequest(PluginSource source) throws IOException {
        String registryUrl = source.getUrl();
        validateRemoteUrl(registryUrl, "plugin registry");
        LOG.info("Loading plugin registry from: " + PluginSourceLabels.diagnosticUrl(registryUrl));
        try {
            String content = fetchBoundedUtf8(registryUrl, "plugin registry", MAX_REGISTRY_BYTES);
            JsonElement document = JsonParser.parseString(content);
            if (!document.isJsonObject()) {
                throw new IOException("Plugin registry is not an object");
            }
            PluginDocumentVerification verification = source.isOfficial()
                    ? trustVerifier.verifyOfficialRegistry(document.getAsJsonObject())
                    : new PluginDocumentVerification(document.getAsJsonObject(), PluginTrustResult.community());
            if (source.isOfficial() && verification.trust().level() != PluginTrustLevel.OFFICIAL) {
                throw new IOException("Official plugin registry signature verification failed");
            }
            @Nullable PluginStoreRegistry loadedRegistry = JsonUtils.GSON.fromJson(
                    verification.signed(),
                    PluginStoreRegistry.class
            );
            if (loadedRegistry == null) {
                throw new IOException("Empty plugin registry: " + PluginSourceLabels.diagnosticUrl(registryUrl));
            }
            loadedRegistry.validate();
            for (PluginStoreRegistry.PluginStoreEntry entry : loadedRegistry.getPlugins()) {
                validateRemoteUrl(entry.getManifestUrl(), "plugin manifest");
                if (source.isOfficial() && entry.getManifestSha256().isBlank()) {
                    throw new IOException("Official registry entry has no manifestSha256: " + entry.getId());
                }
            }

            LOG.info("Loaded " + loadedRegistry.getPlugins().size() + " plugins from registry");
            return new RegistryLoad(loadedRegistry, verification.trust());
        } catch (JsonParseException exception) {
            throw new IOException("Failed to parse plugin registry", exception);
        }
    }

    /// Loads the fixed official plugin source without persisting any selection state.
    ///
    /// @throws IOException if loading fails
    public void loadDefaultRegistry() throws IOException {
        if (!DEFAULT_REGISTRY_ENABLED) {
            throw new IOException("HMCL CE is configured for local plugin packages only");
        }
        loadSource(new PluginSource(
                PluginSource.OFFICIAL_ID,
                DEFAULT_REGISTRY_URL,
                null,
                DEFAULT_REGISTRY_ENABLED,
                true
        ));
    }

    /// Resolves and validates one plugin repository manifest.
    ///
    /// @param pluginId expected plugin ID
    /// @param manifestUrl repository manifest URL
    /// @return validated repository manifest
    /// @throws IOException if transport, parsing, identity, or schema validation fails
    public PluginStoreManifest getPluginManifest(String pluginId, String manifestUrl) throws IOException {
        return getPluginManifest(requireContext(), pluginId, manifestUrl);
    }

    /// Resolves one repository manifest through the supplied source context only.
    ///
    /// @param sourceContext source context captured before the request begins
    /// @param pluginId expected plugin ID
    /// @param manifestUrl repository manifest URL
    /// @return validated repository manifest
    /// @throws IOException if transport, parsing, identity, or schema validation fails
    private PluginStoreManifest getPluginManifest(
            SourceContext sourceContext,
            String pluginId,
            String manifestUrl
    ) throws IOException {
        @Nullable PluginStoreManifest cached = sourceContext.manifestCache.get(manifestUrl);
        if (cached != null) {
            if (!pluginId.equals(cached.getId())) {
                throw new IOException("Cached plugin manifest ID mismatch for " + pluginId);
            }
            return cached;
        }

        validateRemoteUrl(manifestUrl, "plugin manifest");
        LOG.info("Fetching plugin manifest from: " + PluginSourceLabels.diagnosticUrl(manifestUrl));
        try {
            String content = sourceContext.prefetchedManifestContents.containsKey(manifestUrl)
                    ? sourceContext.prefetchedManifestContents.get(manifestUrl)
                    : fetchBoundedUtf8(manifestUrl, "plugin manifest", MAX_STORE_MANIFEST_BYTES);
            if (sourceContext.registryTrust.level() == PluginTrustLevel.OFFICIAL) {
                @Nullable PluginStoreRegistry.PluginStoreEntry entry = sourceContext.registry.findPlugin(pluginId);
                if (entry == null || !entry.getManifestUrl().equals(manifestUrl)) {
                    throw new IOException("Official registry manifest identity mismatch for " + pluginId);
                }
                String actualManifestSha256 = HexFormat.of().formatHex(
                        createSha256().digest(content.getBytes(StandardCharsets.UTF_8))
                );
                if (!entry.getManifestSha256().equals(actualManifestSha256)) {
                    throw new IOException("Official plugin manifest SHA-256 mismatch for " + pluginId);
                }
            }
            JsonElement document = JsonParser.parseString(content);
            if (!document.isJsonObject()) {
                throw new IOException("Plugin manifest is not an object");
            }
            @Nullable String repositoryIdentity = sourceContext.repositoryIdentities.get(manifestUrl);
            PluginDocumentVerification verification;
            if (sourceContext.registryTrust.level() == PluginTrustLevel.OFFICIAL) {
                verification = new PluginDocumentVerification(document.getAsJsonObject(), sourceContext.registryTrust);
            } else {
                JsonObject manifestDocument = document.getAsJsonObject().has("signed")
                        && document.getAsJsonObject().get("signed").isJsonObject()
                        ? document.getAsJsonObject().getAsJsonObject("signed")
                        : document.getAsJsonObject();
                verification = new PluginDocumentVerification(manifestDocument, PluginTrustResult.community());
            }
            PluginStoreManifest manifest = PluginStoreManifest.fromJson(verification.signed(), pluginId);
            for (PluginStoreManifest.PluginVersionEntry version : manifest.getVersions()) {
                if (version.getArtifacts().isEmpty()) {
                    validateRemoteUrl(version.getPackageUrl(), "plugin package");
                } else {
                    for (PluginStoreArtifact artifact : version.getArtifacts()) {
                        validateRemoteUrl(artifact.packageUrl(), "plugin package");
                    }
                }
            }
            assignVersionTrust(manifest, verification.trust());
            sourceContext.manifestCache.put(manifestUrl, manifest);
            @Nullable PluginStoreManifest.PluginVersionEntry latest = manifest.getLatestVersion();
            sourceContext.manifestTrust.put(
                    manifestUrl,
                    latest == null ? verification.trust() : latest.getTrust()
            );
            return manifest;
        } catch (JsonParseException exception) {
            throw new IOException("Failed to parse plugin manifest", exception);
        }
    }

    /// Assigns official trust to official versions and community trust to every ordinary source version.
    ///
    /// @param manifest validated manifest
    /// @param documentTrust official registry or ordinary community trust
    private void assignVersionTrust(
            PluginStoreManifest manifest,
            PluginTrustResult documentTrust
    ) {
        if (documentTrust.level() == PluginTrustLevel.OFFICIAL) {
            manifest.getVersions().forEach(version -> version.setTrust(documentTrust));
            return;
        }
        manifest.getVersions().forEach(version -> version.setTrust(PluginTrustResult.community()));
    }

    /// Resolves one artifact-referenced historical repository proof from the root-controlled cache and origin.
    private RepositoryAttestationResolution resolveRepositoryAttestation(
            SourceContext sourceContext,
            PluginStoreManifest manifest,
            @Nullable String repositoryIdentity,
            PluginStoreManifest.PluginVersionEntry version
    ) {
        if (repositoryIdentity == null) {
            return RepositoryAttestationResolution.failed("certification has no externally established repository identity");
        }
        final String normalizedManifestRepository;
        try {
            normalizedManifestRepository = PluginTrustVerifier.normalizeRepository(manifest.getRepository());
        } catch (IllegalArgumentException exception) {
            return RepositoryAttestationResolution.failed("certification repository identity is invalid");
        }
        if (!normalizedManifestRepository.equals(repositoryIdentity)) {
            return RepositoryAttestationResolution.failed("certification repository does not match discovery identity");
        }
        @Nullable JsonObject artifactAttestation = version.getArtifactAttestation();
        if (artifactAttestation == null) {
            return RepositoryAttestationResolution.failed("partial artifact certification declaration");
        }
        final String verificationId;
        try {
            verificationId = trustVerifier.verifyArtifactRepositoryReference(artifactAttestation);
        } catch (RuntimeException exception) {
            return RepositoryAttestationResolution.failed("artifact attestation signature or repository reference is invalid");
        }
        synchronized (sourceContext.repositoryAttestations) {
            @Nullable RepositoryAttestationResolution cached =
                    sourceContext.repositoryAttestations.get(verificationId);
            if (cached != null) {
                return cached;
            }
            RepositoryAttestationResolution loaded;
            try {
                @Nullable PluginTrustStatusCache cache = trustStatusCache;
                if (cache == null) {
                    throw new IOException("authenticated repository attestation cache is unavailable");
                }
                PluginRepositoryAttestationDocument document =
                        cache.resolveRepositoryAttestationDocument(verificationId);
                PluginRepositoryAttestation attestation = document.attestation();
                if (!attestation.repository().equals(normalizedManifestRepository)) {
                    throw new IOException("Repository attestation identity does not match its manifest");
                }
                loaded = RepositoryAttestationResolution.verified(document);
            } catch (IOException | RuntimeException exception) {
                loaded = RepositoryAttestationResolution.failed("repository attestation verification failed");
            }
            sourceContext.repositoryAttestations.put(verificationId, loaded);
            return loaded;
        }
    }

    /// Evaluates one exact platform artifact against its NPL proof, weekly repository proof, and online status.
    ///
    /// @param manifest repository manifest owning the selected version
    /// @param repositoryIdentity externally established repository identity, or `null` when unavailable
    /// @param repositoryResolution verified historical repository proof, or `null` when unavailable
    /// @param version selected repository version
    /// @param artifact exact current-platform package metadata
    /// @return current trust decision for the selected artifact
    private PluginTrustResult evaluateCertifiedVersion(
            PluginStoreManifest manifest,
            @Nullable String repositoryIdentity,
            @Nullable RepositoryAttestationResolution repositoryResolution,
            PluginStoreManifest.PluginVersionEntry version,
            PluginStoreArtifact artifact
    ) {
        @Nullable JsonObject artifactAttestation = version.getArtifactAttestation();
        if (artifactAttestation == null) {
            return PluginTrustResult.rejected("partial artifact certification declaration");
        }
        if (repositoryIdentity == null || repositoryResolution == null || repositoryResolution.attestation() == null) {
            return PluginTrustResult.rejected(repositoryResolution == null
                    ? "repository attestation is unavailable"
                    : repositoryResolution.failure());
        }
        @Nullable PluginTrustStatusCache cache = trustStatusCache;
        @Nullable PluginTrustStatusSnapshot status = cache == null ? null : cache.getFreshSnapshot();
        if (status == null) {
            return PluginTrustResult.rejected("official trust status is unavailable or expired");
        }
        PluginTrustResult result = trustVerifier.verifyArtifactAttestation(
                artifactAttestation,
                repositoryResolution.attestation(),
                status,
                repositoryIdentity,
                manifest.getId(),
                version.getVersion(),
                artifact.packageUrl(),
                artifact.sha256(),
                artifact.size()
        );
        if (result.level() != PluginTrustLevel.CERTIFIED) {
            return result;
        }
        try {
            JsonObject repositoryEnvelope = Objects.requireNonNull(repositoryResolution.envelope());
            PluginVerifiedCertification certification = trustVerifier.verifyInstalledCertification(
                    artifactAttestation,
                    repositoryEnvelope,
                    manifest.getId(),
                    version.getVersion(),
                    artifact.sha256(),
                    artifact.size()
            );
            PluginCertificationReceipt receipt = PluginCertificationReceipt.fromVerified(
                    certification,
                    artifactAttestation,
                    repositoryEnvelope
            );
            return result.withCertificationReceipt(receipt);
        } catch (RuntimeException exception) {
            return PluginTrustResult.rejected("certification receipt proof binding failed");
        }
    }

    /// Returns the trust already assigned by source loading and official-reference aggregation.
    ///
    /// @param item source-bound item
    /// @param version selected version
    /// @return current exact-version trust
    public PluginTrustResult refreshVersionTrust(
            PluginStoreItem item,
            PluginStoreManifest.PluginVersionEntry version
    ) {
        return item.getTrust(version);
    }

    /// Resolves all registry entries, retaining unavailable repositories as partial source-bound items.
    ///
    /// @return resolved store items
    public @Unmodifiable List<PluginStoreItem> getStoreItems() {
        @Nullable SourceContext sourceContext = context;
        if (sourceContext == null) {
            return List.of();
        }
        return getStoreItems(sourceContext);
    }

    /// Resolves registry entries against one already captured immutable source generation.
    ///
    /// @param sourceContext exact source generation used for every item and manifest cache lookup
    /// @return resolved store items from that generation
    private @Unmodifiable List<PluginStoreItem> getStoreItems(SourceContext sourceContext) {
        List<PluginStoreItem> items = new ArrayList<>();
        for (PluginStoreRegistry.PluginStoreEntry entry : sourceContext.registry.getPlugins()) {
            try {
                items.add(new PluginStoreItem(
                        sourceContext.source,
                        sourceContext.registry,
                        this,
                        entry,
                        getPluginManifest(sourceContext, entry.getId(), entry.getManifestUrl()),
                        sourceContext,
                        sourceContext.manifestTrust.getOrDefault(entry.getManifestUrl(), PluginTrustResult.community())
                ));
            } catch (IOException exception) {
                LOG.warning("Failed to load plugin manifest: " + entry.getId());
                items.add(new PluginStoreItem(
                        sourceContext.source,
                        sourceContext.registry,
                        this,
                        entry,
                        null,
                        sourceContext,
                        PluginTrustResult.rejected("plugin manifest could not be loaded")
                ));
            }
        }
        return List.copyOf(items);
    }

    /// Registry payload and its derived trust decision.
    private record RegistryLoad(PluginStoreRegistry registry, PluginTrustResult trust) {
    }

    /// Cached weekly repository proof or one credential-safe failure.
    @NotNullByDefault
    private record RepositoryAttestationResolution(
            @Nullable PluginRepositoryAttestation attestation,
            @Nullable JsonObject envelope,
            String failure
    ) {
        /// Creates one verified resolution.
        private static RepositoryAttestationResolution verified(PluginRepositoryAttestationDocument document) {
            return new RepositoryAttestationResolution(document.attestation(), document.envelope(), "");
        }

        /// Creates one failure before a URL can be accepted.
        private static RepositoryAttestationResolution failed(String failure) {
            return new RepositoryAttestationResolution(null, null, failure);
        }
    }

    /// Resolves a requested version and all transitive plugin dependencies before any package is downloaded.
    ///
    /// This compatibility overload never silently reuses installed dependencies because it has no exact
    /// artifact-bound permission snapshot. Callers that can prove reuse eligibility should use the overload accepting
    /// `reusableInstalledPluginIds`.
    ///
    /// @param pluginId requested root plugin ID
    /// @param requestedVersion exact requested remote version
    /// @param installedManifests installed plugin manifests indexed by ID
    /// @return immutable dependency-first install plan
    /// @throws IOException if metadata is unavailable or the dependency graph cannot be satisfied
    public PluginInstallPlan resolveInstallPlan(
            String pluginId,
            PluginStoreManifest.PluginVersionEntry requestedVersion,
            Map<String, PluginManifest> installedManifests
    ) throws IOException {
        return resolveInstallPlan(pluginId, requestedVersion, installedManifests, Map.of(), Map.of());
    }

    /// Resolves a requested version and all transitive plugin dependencies before any package is downloaded.
    ///
    /// This compatibility overload cannot preserve exact package identities. An empty ID set delegates to the
    /// fail-closed resolver, while a non-empty set is rejected instead of allowing a key-only authorization decision.
    ///
    /// @param pluginId requested root plugin ID
    /// @param requestedVersion exact requested remote version
    /// @param installedManifests installed plugin manifests indexed by ID
    /// @param reusableInstalledPluginIds legacy key-only reusable IDs, which must be empty
    /// @return immutable dependency-first install plan
    /// @throws IOException if metadata is unavailable or the dependency graph cannot be satisfied
    public PluginInstallPlan resolveInstallPlan(
            String pluginId,
            PluginStoreManifest.PluginVersionEntry requestedVersion,
            Map<String, PluginManifest> installedManifests,
            @Unmodifiable Set<String> reusableInstalledPluginIds
    ) throws IOException {
        if (!reusableInstalledPluginIds.isEmpty()) {
            throw new IllegalArgumentException("Reusable plugin IDs cannot authorize reuse without exact artifacts");
        }
        return resolveInstallPlan(pluginId, requestedVersion, installedManifests, Map.of(), Map.of());
    }

    /// Compatibility overload for callers that do not carry a complete installed-artifact snapshot.
    ///
    /// This overload is accepted only when no plugin is installed. Installed state requires the five-argument method
    /// so every update and every reuse decision is bound to one atomic exact-artifact snapshot.
    ///
    /// @param pluginId requested root plugin ID
    /// @param requestedVersion exact requested remote version
    /// @param installedManifests installed plugin manifests indexed by ID
    /// @param reusableInstalledArtifacts legacy partial reusable artifact snapshot
    /// @return immutable dependency-first install plan
    /// @throws IOException if metadata is unavailable or the dependency graph cannot be satisfied
    public PluginInstallPlan resolveInstallPlan(
            String pluginId,
            PluginStoreManifest.PluginVersionEntry requestedVersion,
            Map<String, PluginManifest> installedManifests,
            @Unmodifiable Map<String, PluginArtifactIdentity> reusableInstalledArtifacts
    ) throws IOException {
        if (!installedManifests.isEmpty() || !reusableInstalledArtifacts.isEmpty()) {
            throw new IllegalArgumentException("Installed plugin planning requires complete prior artifact identities");
        }
        return resolveInstallPlan(pluginId, requestedVersion, Map.of(), Map.of(), Map.of());
    }

    /// Resolves a requested version and all transitive dependencies using one complete exact-artifact snapshot.
    ///
    /// This compatibility facade derives a single-source winner map from the currently loaded manager. Aggregate
    /// catalog callers should construct {@link PluginStoreDependencyResolver} directly with their snapshot winners.
    ///
    /// @param pluginId requested root plugin ID
    /// @param requestedVersion exact requested remote version
    /// @param installedManifests installed plugin manifests indexed by ID
    /// @param installedArtifactIdentities exact current artifact for every installed manifest
    /// @param reusableInstalledArtifacts exact installed artifacts approved for reuse during planning
    /// @return immutable dependency-first install plan
    /// @throws IOException if metadata is unavailable or the dependency graph cannot be satisfied
    public PluginInstallPlan resolveInstallPlan(
            String pluginId,
            PluginStoreManifest.PluginVersionEntry requestedVersion,
            Map<String, PluginManifest> installedManifests,
            @Unmodifiable Map<String, PluginArtifactIdentity> installedArtifactIdentities,
            @Unmodifiable Map<String, PluginArtifactIdentity> reusableInstalledArtifacts
    ) throws IOException {
        @Nullable SourceContext sourceContext = context;
        if (sourceContext == null) {
            throw new IOException("Plugin Store source is not loaded");
        }
        Map<String, PluginStoreItem> winningItems = new LinkedHashMap<>();
        for (PluginStoreItem item : getStoreItems(sourceContext)) {
            winningItems.putIfAbsent(item.getEntry().getId(), item);
        }
        return new PluginStoreDependencyResolver(winningItems, List.of(sourceContext.source)).resolveInstallPlan(
                pluginId,
                requestedVersion,
                installedManifests,
                installedArtifactIdentities,
                reusableInstalledArtifacts
        );
    }

    /// Downloads a package to a temporary file, validates size and SHA-256, then atomically replaces `pluginId.npl`.
    ///
    /// @param pluginId validated plugin ID
    /// @param version remote version metadata
    /// @param targetDirectory installed plugin directory
    /// @return verified installed package path
    /// @throws IOException if compatibility, transport, size, checksum, or replacement fails
    public Path downloadPlugin(
            String pluginId,
            PluginStoreManifest.PluginVersionEntry version,
            Path targetDirectory
    ) throws IOException {
        PluginStoreArtifact artifact = version.requireArtifact(compatibilityEvaluator.getHostPlatform());
        return downloadPluginToFile(
                pluginId,
                version,
                artifact,
                targetDirectory.resolve(pluginId + ".npl")
        );
    }

    /// Downloads and fully validates a package in a staging directory without touching installed files.
    ///
    /// The stable checksum prefix makes each selected version deterministic while keeping untrusted version text out
    /// of file names. Callers can download an entire dependency plan here before publishing any package.
    ///
    /// @param pluginId validated plugin ID
    /// @param version selected remote version metadata
    /// @param stagingDirectory isolated staging directory
    /// @return verified staged package path
    /// @throws IOException if compatibility, transport, or package verification fails
    public Path downloadPluginToStaging(
            String pluginId,
            PluginStoreManifest.PluginVersionEntry version,
            Path stagingDirectory
    ) throws IOException {
        PluginStoreArtifact artifact = version.requireArtifact(compatibilityEvaluator.getHostPlatform());
        String checksumPrefix = artifact.sha256().substring(0, 12).toLowerCase(Locale.ROOT);
        return downloadPluginToFile(
                pluginId,
                version,
                artifact,
                stagingDirectory.resolve(pluginId + "-" + checksumPrefix + ".npl")
        );
    }

    /// Downloads and validates a package before atomically publishing it to an explicit target file.
    ///
    /// @param pluginId validated plugin ID
    /// @param version selected remote version metadata
    /// @param artifact exact current-platform package metadata
    /// @param targetFile final package path
    /// @return verified target path
    /// @throws IOException if compatibility, transport, size, checksum, metadata, or replacement fails
    private Path downloadPluginToFile(
            String pluginId,
            PluginStoreManifest.PluginVersionEntry version,
            PluginStoreArtifact artifact,
            Path targetFile
    ) throws IOException {
        PluginTrustResult currentTrust = refreshDownloadTrust(pluginId, version, artifact);
        if (!currentTrust.canInstall()) {
            throw new IOException("Plugin trust verification rejected " + pluginId + ": " + currentTrust.detail());
        }
        validateCompatibility(version);
        validateRemoteUrl(artifact.packageUrl(), "plugin package");

        Path normalizedTarget = targetFile.toAbsolutePath().normalize();
        @Nullable Path targetDirectory = normalizedTarget.getParent();
        if (targetDirectory == null) {
            throw new IOException("Plugin package target has no parent directory");
        }
        Files.createDirectories(targetDirectory);
        Path temporaryFile = targetDirectory.resolve(
                "." + pluginId + "-" + UUID.randomUUID() + ".download"
        );
        long declaredSize = artifact.size();
        if (declaredSize > MAX_PACKAGE_BYTES) {
            throw new IOException("Plugin package exceeds the maximum allowed size");
        }

        MessageDigest digest = createSha256();
        long totalBytes = 0;
        byte[] buffer = new byte[8192];
        LOG.info("Downloading plugin " + pluginId + " v" + version.getVersion()
                + " from " + PluginSourceLabels.diagnosticUrl(artifact.packageUrl()));

        @Nullable HttpURLConnection connection = null;
        try {
            connection = openValidatedConnection(artifact.packageUrl(), "plugin package");
            int responseCode = connection.getResponseCode();
            if (responseCode / 100 != 2) {
                throw new IOException("Plugin package request failed with HTTP " + responseCode);
            }

            try (InputStream input = new BufferedInputStream(connection.getInputStream());
                 BufferedOutputStream output = new BufferedOutputStream(Files.newOutputStream(temporaryFile))) {
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read == 0) {
                        continue;
                    }
                    totalBytes = Math.addExact(totalBytes, read);
                    if (totalBytes > MAX_PACKAGE_BYTES || totalBytes > declaredSize) {
                        throw new IOException("Plugin package exceeds its declared or maximum size");
                    }
                    digest.update(buffer, 0, read);
                    output.write(buffer, 0, read);
                }
            }
        } catch (ArithmeticException exception) {
            throw new IOException("Plugin package size overflow", exception);
        } catch (IOException exception) {
            Files.deleteIfExists(temporaryFile);
            throw exception;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }

        try {
            if (totalBytes != declaredSize) {
                throw new IOException("Plugin package size mismatch. Expected " + declaredSize + ", got " + totalBytes);
            }
            String actualHash = toHex(digest.digest());
            if (!actualHash.equals(artifact.sha256())) {
                throw new IOException("Plugin checksum mismatch. Expected " + artifact.sha256()
                        + ", got " + actualHash);
            }
            validateDownloadedPackage(temporaryFile, pluginId, version);
            try {
                Files.move(
                        temporaryFile,
                        normalizedTarget,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING
                );
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporaryFile, normalizedTarget, StandardCopyOption.REPLACE_EXISTING);
            }
            LOG.info("Downloaded and verified plugin package: " + normalizedTarget);
            return normalizedTarget;
        } finally {
            Files.deleteIfExists(temporaryFile);
        }
    }

    /// Uses the snapshot-assigned trust immediately before network bytes are accepted for installation.
    ///
    /// @param pluginId selected plugin ID
    /// @param version selected version
    /// @param artifact exact current-platform package metadata
    /// @return current exact-version trust
    private PluginTrustResult refreshDownloadTrust(
            String pluginId,
            PluginStoreManifest.PluginVersionEntry version,
            PluginStoreArtifact artifact
    ) {
        @Nullable SourceContext sourceContext = context;
        if (sourceContext == null || sourceContext.registryTrust.level() == PluginTrustLevel.OFFICIAL
                || !version.hasCertificationDeclaration()) {
            return version.getTrust();
        }
        @Nullable PluginStoreRegistry.PluginStoreEntry entry = sourceContext.registry.findPlugin(pluginId);
        if (entry == null) {
            return PluginTrustResult.rejected("selected plugin is absent from its source registry");
        }
        @Nullable PluginStoreManifest manifest = sourceContext.manifestCache.get(entry.getManifestUrl());
        if (manifest == null || manifest.getVersion(version.getVersion()) != version) {
            return PluginTrustResult.rejected("selected plugin version is not bound to the current source generation");
        }
        @Nullable String repositoryIdentity = sourceContext.repositoryIdentities.get(entry.getManifestUrl());
        RepositoryAttestationResolution repositoryResolution = resolveRepositoryAttestation(
                sourceContext,
                manifest,
                repositoryIdentity,
                version
        );
        PluginTrustResult current = evaluateCertifiedVersion(
                manifest,
                repositoryIdentity,
                repositoryResolution,
                version,
                artifact
        );
        version.setTrust(current);
        return current;
    }

    /// Validates shared compatibility requirements and the store-only Java version before downloading a package.
    ///
    /// @param version remote version metadata
    /// @throws IOException if the current runtime is incompatible
    public void validateCompatibility(PluginStoreManifest.PluginVersionEntry version) throws IOException {
        PluginCompatibilityResult compatibility = compatibilityEvaluator.evaluate(
                version.toCompatibilityRequirements(),
                Metadata.VERSION
        );
        if (!compatibility.isCompatible()) {
            throw new IOException(compatibility.detail());
        }
        if (!version.getArtifacts().isEmpty()) {
            version.requireArtifact(compatibilityEvaluator.getHostPlatform());
        }

        String requiredJava = version.getRequiredJavaVersion();
        if (!requiredJava.isBlank()) {
            Matcher matcher = JAVA_VERSION_PATTERN.matcher(requiredJava);
            if (!matcher.find()) {
                throw new IOException("Invalid requiredJavaVersion: " + requiredJava);
            }
            int requiredFeature;
            try {
                requiredFeature = Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException exception) {
                throw new IOException("Invalid requiredJavaVersion: " + requiredJava, exception);
            }
            if (Runtime.version().feature() < requiredFeature) {
                throw new IOException("This plugin requires Java " + requiredFeature + " or newer");
            }
        }
    }

    /// Returns whether one remote version is compatible with the current launcher and Java runtime.
    ///
    /// @param version remote version metadata
    /// @return compatibility state
    public boolean isCompatible(PluginStoreManifest.PluginVersionEntry version) {
        try {
            validateCompatibility(version);
            return true;
        } catch (IOException exception) {
            return false;
        }
    }

    /// Returns compatible published versions sorted from newest to oldest.
    ///
    /// @param manifest plugin repository manifest
    /// @return immutable compatible version list
    public @Unmodifiable List<PluginStoreManifest.PluginVersionEntry> getCompatibleVersions(
            PluginStoreManifest manifest
    ) {
        return manifest.getVersionsNewestFirst().stream()
                .filter(this::isCompatible)
                .toList();
    }

    /// Returns the newest compatible version from a repository manifest.
    ///
    /// @param manifest plugin repository manifest or `null`
    /// @return newest compatible version or `null`
    public @Nullable PluginStoreManifest.PluginVersionEntry getLatestCompatibleVersion(
            @Nullable PluginStoreManifest manifest
    ) {
        return manifest == null ? null : getCompatibleVersions(manifest).stream().findFirst().orElse(null);
    }

    /// Returns whether a remote version is newer than the installed plugin manifest.
    ///
    /// @param installed installed package manifest or `null`
    /// @param remoteVersion remote version or `null`
    /// @return whether an update is available
    public boolean hasUpdate(
            @Nullable PluginManifest installed,
            @Nullable PluginStoreManifest.PluginVersionEntry remoteVersion
    ) {
        return installed != null
                && remoteVersion != null
                && PluginVersion.compare(
                remoteVersion.getVersion(),
                installed.getVersion()
        ) > 0;
    }

    /// Compares two plugin versions using the shared semantic-version-compatible comparator.
    ///
    /// @param left first version
    /// @param right second version
    /// @return version ordering
    public static int compareVersion(String left, String right) {
        return PluginVersion.compare(left, right);
    }

    /// Returns the registry URL associated with the currently loaded source.
    ///
    /// @return loaded source URL
    /// @throws IllegalStateException if no source has loaded successfully
    public String getRegistryUrl() {
        return getSource().getUrl();
    }

    /// Returns the currently loaded registry.
    ///
    /// @return loaded registry, or `null` before the first successful source load
    public @Nullable PluginStoreRegistry getRegistry() {
        @Nullable SourceContext currentContext = context;
        return currentContext == null ? null : currentContext.registry;
    }

    /// Downloads and caches a bounded UTF-8 README from one source-bound store item.
    ///
    /// @param item source-bound item declaring the repository manifest
    /// @return README Markdown text, or an empty string when no URL is declared
    /// @throws IOException if transport, URL policy, response status, or size validation fails
    public String fetchReadme(PluginStoreItem item) throws IOException {
        if (item.getSourceManager() != this) {
            throw new IllegalArgumentException("Plugin store item belongs to a different source manager");
        }
        @Nullable PluginStoreManifest manifest = item.getManifest();
        if (manifest == null) {
            throw new IOException("Plugin store item has no resolved manifest: " + item.getEntry().getId());
        }
        @Nullable SourceContext sourceContext = item.getSourceContext();
        if (sourceContext == null) {
            throw new IllegalArgumentException("Plugin store item has no source context");
        }
        return fetchReadme(sourceContext, manifest);
    }

    /// Downloads and caches a bounded UTF-8 README through the legacy explicit-manifest compatibility API.
    ///
    /// This overload never uses a loaded source context because a manifest alone cannot prove which source
    /// produced it. Source-bound callers must use [#fetchReadme(PluginStoreItem)].
    ///
    /// @param manifest plugin repository manifest
    /// @return README Markdown text, or an empty string when no URL is declared
    /// @throws IOException if transport, URL policy, response status, or size validation fails
    public String fetchReadme(PluginStoreManifest manifest) throws IOException {
        return fetchReadme(null, manifest);
    }

    /// Downloads and caches a bounded UTF-8 README through one captured context.
    ///
    /// @param sourceContext captured source context, or `null` before any source loads
    /// @param manifest plugin repository manifest
    /// @return README Markdown text, or an empty string when no URL is declared
    /// @throws IOException if transport, URL policy, response status, or size validation fails
    private String fetchReadme(@Nullable SourceContext sourceContext, PluginStoreManifest manifest) throws IOException {
        String readmeUrl = manifest.getReadmeUrl();
        if (readmeUrl.isBlank()) {
            return "";
        }
        Map<String, String> readmeCache = sourceContext == null
                ? unloadedReadmeCache
                : sourceContext.readmeCache;
        @Nullable String cached = readmeCache.get(readmeUrl);
        if (cached != null) {
            return cached;
        }

        String readme = fetchBoundedUtf8(readmeUrl, "plugin README", MAX_README_BYTES);
        readmeCache.put(readmeUrl, readme);
        return readme;
    }

    /// Clears request caches for the currently published source context or the unloaded README compatibility cache.
    public void clearCache() {
        @Nullable SourceContext currentContext = context;
        if (currentContext == null) {
            unloadedReadmeCache.clear();
            return;
        }
        currentContext.manifestCache.clear();
        currentContext.readmeCache.clear();
    }

    /// Enforces HTTPS for remote hosts while allowing loopback HTTP registries used for local development.
    ///
    /// @param url URL to validate
    /// @param purpose value used in diagnostics
    /// @throws IOException if the URL is malformed or insecure
    static void validateRemoteUrl(String url, String purpose) throws IOException {
        final URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException exception) {
            throw new IOException("Invalid " + purpose + " URL: " + PluginSourceLabels.diagnosticUrl(url), exception);
        }
        @Nullable String scheme = uri.getScheme();
        @Nullable String host = uri.getHost();
        if (scheme == null || host == null) {
            throw new IOException("Invalid " + purpose + " URL: " + PluginSourceLabels.diagnosticUrl(url));
        }
        boolean loopback = host.equalsIgnoreCase("localhost")
                || host.equals("127.0.0.1")
                || host.equals("::1")
                || host.equals("[::1]");
        if (!scheme.equalsIgnoreCase("https") && !(scheme.equalsIgnoreCase("http") && loopback)) {
            throw new IOException("Insecure " + purpose + " URL is not allowed: " + PluginSourceLabels.diagnosticUrl(url));
        }
    }

    /// Downloads bounded UTF-8 text and revalidates the final URL after redirects.
    ///
    /// @param url initial remote URL
    /// @param purpose value used in diagnostics
    /// @param maximumBytes maximum accepted response bytes
    /// @return decoded UTF-8 response
    /// @throws IOException if URL policy, transport, status, or size validation fails
    private static String fetchBoundedUtf8(String url, String purpose, int maximumBytes) throws IOException {
        @Nullable HttpURLConnection connection = null;
        try {
            connection = openValidatedConnection(url, purpose);
            int responseCode = connection.getResponseCode();
            if (responseCode / 100 != 2) {
                throw new IOException(purpose + " request failed with HTTP " + responseCode);
            }
            long contentLength = connection.getContentLengthLong();
            if (contentLength > maximumBytes) {
                throw new IOException(purpose + " exceeds the maximum allowed size");
            }
            byte @Unmodifiable [] bytes;
            try (InputStream input = new BufferedInputStream(connection.getInputStream())) {
                bytes = input.readNBytes(maximumBytes + 1);
            }
            if (bytes.length > maximumBytes) {
                throw new IOException(purpose + " exceeds the maximum allowed size");
            }
            return new String(bytes, StandardCharsets.UTF_8);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /// Opens a GET connection while validating every redirect target before any request is sent to that target.
    ///
    /// @param initialUrl initial request URL
    /// @param purpose value used in diagnostics
    /// @return connected response at the final validated URL
    /// @throws IOException if URL policy, redirect syntax, or redirect depth validation fails
    private static HttpURLConnection openValidatedConnection(String initialUrl, String purpose) throws IOException {
        URI currentUrl = parseRemoteUri(initialUrl, purpose);
        URI firstUrl = currentUrl;
        for (int redirect = 0; redirect <= MAX_REDIRECTS; redirect++) {
            validateRemoteUrl(currentUrl.toString(), redirect == 0 ? purpose : purpose + " redirect");
            if (redirect > 0) {
                validateRedirectTarget(firstUrl, currentUrl, purpose);
            }
            HttpURLConnection connection = HttpRequest.GET(currentUrl.toString()).createConnection();
            connection.setInstanceFollowRedirects(false);
            int responseCode = connection.getResponseCode();
            if (!isRedirect(responseCode)) {
                return connection;
            }

            @Nullable String location = connection.getHeaderField("Location");
            connection.disconnect();
            if (location == null || location.isBlank()) {
                throw new IOException(purpose + " redirect has no Location header");
            }
            if (redirect == MAX_REDIRECTS) {
                throw new IOException(purpose + " has too many redirects");
            }
            try {
                currentUrl = currentUrl.resolve(new URI(location));
            } catch (IllegalArgumentException | URISyntaxException exception) {
                throw new IOException("Invalid " + purpose + " redirect URL: "
                        + PluginSourceLabels.diagnosticUrl(location), exception);
            }
        }
        throw new IOException(purpose + " has too many redirects");
    }

    /// Enforces that only an explicitly local development request may redirect to loopback HTTP.
    ///
    /// Remote HTTPS chains must remain HTTPS and cannot redirect to a loopback host.
    ///
    /// @param initialUrl first URL in the request chain
    /// @param redirectUrl validated redirect target
    /// @param purpose value used in diagnostics
    /// @throws IOException if a remote chain is downgraded or redirected to loopback
    static void validateRedirectTarget(URI initialUrl, URI redirectUrl, String purpose) throws IOException {
        if (!isLoopbackHttp(initialUrl)
                && (!"https".equalsIgnoreCase(redirectUrl.getScheme())
                || isLoopbackHost(redirectUrl.getHost()))) {
            throw new IOException("Remote " + purpose + " cannot redirect to a local or insecure URL: "
                    + PluginSourceLabels.diagnosticUrl(redirectUrl.toString()));
        }
    }

    /// Returns whether an HTTP response code represents a redirect followed by the store client.
    ///
    /// @param responseCode HTTP response code
    /// @return whether a Location redirect should be followed
    private static boolean isRedirect(int responseCode) {
        return responseCode >= 300 && responseCode <= 308 && responseCode != 304 && responseCode != 306;
    }

    /// Parses and structurally validates a remote URI before redirect resolution.
    ///
    /// @param url URL text
    /// @param purpose value used in diagnostics
    /// @return parsed URI
    /// @throws IOException if the URL is malformed
    private static URI parseRemoteUri(String url, String purpose) throws IOException {
        try {
            return new URI(url);
        } catch (URISyntaxException exception) {
            throw new IOException("Invalid " + purpose + " URL: " + PluginSourceLabels.diagnosticUrl(url), exception);
        }
    }

    /// Returns whether a URI is an explicitly local HTTP endpoint used for plugin-store development.
    ///
    /// @param uri parsed URI
    /// @return whether the URI uses HTTP and a loopback host
    private static boolean isLoopbackHttp(URI uri) {
        return "http".equalsIgnoreCase(uri.getScheme()) && isLoopbackHost(uri.getHost());
    }

    /// Returns whether a nullable URI host is one of the accepted loopback spellings.
    ///
    /// @param host URI host or `null`
    /// @return whether the host is loopback
    private static boolean isLoopbackHost(@Nullable String host) {
        return host != null && (host.equalsIgnoreCase("localhost")
                || host.equals("127.0.0.1")
                || host.equals("::1")
                || host.equals("[::1]"));
    }

    /// Creates a SHA-256 message digest.
    ///
    /// @return digest instance
    /// @throws IOException if SHA-256 is unavailable
    private static MessageDigest createSha256() throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IOException("SHA-256 is unavailable", exception);
        }
    }

    /// Validates the internal package manifest before replacing a working installed package.
    ///
    /// @param packageFile verified temporary `.npl` file
    /// @param expectedPluginId plugin ID from the registry
    /// @param expectedVersion complete remote version metadata
    /// @throws IOException if package identity, version, schema, permissions, required permissions, dependencies,
    /// runtime, ABI, platforms, or launcher version differ from the selected metadata
    private static void validateDownloadedPackage(
            Path packageFile,
            String expectedPluginId,
            PluginStoreManifest.PluginVersionEntry expectedVersion
    ) throws IOException {
        try (ZipFile zipFile = new ZipFile(packageFile.toFile())) {
            @Nullable ZipEntry manifestEntry = zipFile.getEntry("plugin.json");
            if (manifestEntry == null || manifestEntry.isDirectory()) {
                throw new IOException("Downloaded package has no plugin.json");
            }
            if (manifestEntry.getSize() > MAX_PLUGIN_MANIFEST_BYTES) {
                throw new IOException("Downloaded package manifest is too large");
            }

            byte @Unmodifiable [] manifestBytes;
            try (InputStream input = zipFile.getInputStream(manifestEntry)) {
                manifestBytes = input.readNBytes(MAX_PLUGIN_MANIFEST_BYTES + 1);
            }
            if (manifestBytes.length > MAX_PLUGIN_MANIFEST_BYTES) {
                throw new IOException("Downloaded package manifest is too large");
            }

            PluginManifest packageManifest = PluginManifest.fromJson(new java.io.StringReader(
                    new String(manifestBytes, java.nio.charset.StandardCharsets.UTF_8)
            ));
            if (!expectedPluginId.equals(packageManifest.getId())) {
                throw new IOException("Downloaded package ID " + packageManifest.getId()
                        + " does not match registry entry " + expectedPluginId);
            }
            if (!packageManifest.getVersion().equals(expectedVersion.getVersion())) {
                throw new IOException("Downloaded package version " + packageManifest.getVersion()
                        + " does not match selected version " + expectedVersion.getVersion());
            }
            if (packageManifest.getSchemaVersion() != expectedVersion.getPluginApiVersion()) {
                throw new IOException("Downloaded package schemaVersion " + packageManifest.getSchemaVersion()
                        + " does not match pluginApiVersion " + expectedVersion.getPluginApiVersion());
            }
            if (!packageManifest.getRuntime().equals(expectedVersion.getRuntime())) {
                throw new IOException("Downloaded package runtime does not match selected version metadata");
            }
            if (packageManifest.getAbi() != expectedVersion.getAbi()) {
                throw new IOException("Downloaded package ABI does not match selected version metadata");
            }
            if (!packageManifest.getPlatforms().equals(expectedVersion.getPlatforms())) {
                throw new IOException("Downloaded package platforms do not match selected version metadata");
            }
            if (packageManifest.getPluginKind() != expectedVersion.getPluginKind()) {
                throw new IOException("Downloaded package pluginKind does not match selected version metadata");
            }
            if (expectedVersion.getPluginApiVersion() >= 3
                    && !new HashSet<>(packageManifest.getPermissions())
                    .equals(new HashSet<>(expectedVersion.getPermissions()))) {
                throw new IOException("Downloaded package permissions do not match selected version metadata");
            }
            if (expectedVersion.getPluginApiVersion() >= 3
                    && !new HashSet<>(packageManifest.getRequiredPermissions())
                    .equals(new HashSet<>(expectedVersion.getRequiredPermissions()))) {
                throw new IOException("Downloaded package requiredPermissions do not match selected version metadata");
            }
            if (!packageManifest.getLauncherVersion().equals(expectedVersion.getLauncherVersion())) {
                throw new IOException("Downloaded package launcherVersion does not match selected version metadata");
            }
            if (expectedVersion.hasAuthoritativeDependencies()
                    && !new HashSet<>(packageManifest.getPluginDependencies())
                    .equals(new HashSet<>(expectedVersion.getDependencies()))) {
                throw new IOException("Downloaded package dependencies do not match selected version metadata");
            }
        }
    }

    /// Converts digest bytes to lower-case hexadecimal text.
    ///
    /// @param bytes digest bytes
    /// @return hexadecimal digest
    private static String toHex(byte @Unmodifiable [] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        }
        return result.toString();
    }
}
