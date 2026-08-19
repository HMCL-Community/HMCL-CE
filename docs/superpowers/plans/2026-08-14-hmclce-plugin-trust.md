# HMCL CE Plugin Trust Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add `hmclce` GitHub Topic discovery and cryptographically derived official/certified/community plugin trust to HMCL CE, its SDK, and its official plugin store.

**Architecture:** A focused Java trust package verifies JCS-canonicalized Ed25519 envelopes against an embedded role-separated root. `PluginStoreManager` loads either signed official registries, ordinary JSON registries, or a synthetic registry discovered from GitHub Topic results, and attaches immutable trust results to every store item. SDK/store Java tooling uses the same wire format to generate keys, issue certificates, sign manifests, and validate CI artifacts.

**Tech Stack:** Java 17 Ed25519/JCA, Gson JSON trees, Java `HttpURLConnection`, JUnit 5, PowerShell 7, GitHub Actions.

---

### Task 1: Canonical JSON and Signature Envelopes

**Files:**
- Create: `HMCL/src/main/java/org/jackhuang/hmcl/plugin/trust/CanonicalJson.java`
- Create: `HMCL/src/main/java/org/jackhuang/hmcl/plugin/trust/PluginSignatureEnvelope.java`
- Create: `HMCL/src/test/java/org/jackhuang/hmcl/plugin/trust/CanonicalJsonTest.java`

- [ ] Write tests asserting sorted object keys, stable nested values, minimal string escaping, exact integers, and rejection of non-integral JSON numbers.
- [ ] Run `./gradlew :HMCL:test --tests org.jackhuang.hmcl.plugin.trust.CanonicalJsonTest` and verify the missing classes fail compilation.
- [ ] Implement recursive UTF-8 canonicalization and strict envelope parsing. Signature input must be `domain + "\n" + canonical signed JSON`.
- [ ] Re-run the focused test and verify it passes.

### Task 2: Role-Separated Ed25519 Trust Verification

**Files:**
- Create: `HMCL/src/main/java/org/jackhuang/hmcl/plugin/trust/PluginTrustLevel.java`
- Create: `HMCL/src/main/java/org/jackhuang/hmcl/plugin/trust/PluginTrustResult.java`
- Create: `HMCL/src/main/java/org/jackhuang/hmcl/plugin/trust/PluginTrustRoot.java`
- Create: `HMCL/src/main/java/org/jackhuang/hmcl/plugin/trust/PluginTrustVerifier.java`
- Create: `HMCL/src/main/resources/assets/hmclce-plugin-root.json`
- Create: `HMCL/src/test/java/org/jackhuang/hmcl/plugin/trust/PluginTrustVerifierTest.java`

- [ ] Add deterministic fixture-key tests for valid official registry signatures, developer certificates, manifest signatures, repository/plugin ID constraints, expiry, revocation, wrong usage, mutation, and partial-signature downgrade resistance.
- [ ] Run the verifier test and confirm it fails before implementation.
- [ ] Parse the embedded root key/role document, verify Ed25519 SPKI keys, enforce role thresholds, validate developer certificate scope, and return immutable `OFFICIAL`, `CERTIFIED`, `COMMUNITY`, or `REJECTED` results.
- [ ] Run the verifier and canonicalization tests and verify they pass.

### Task 3: Build-Time Root Injection

**Files:**
- Modify: `HMCL/build.gradle.kts`
- Modify: `.github/workflows/gradle.yml`
- Create: `HMCL/src/test/java/org/jackhuang/hmcl/plugin/trust/PluginTrustRootResourceTest.java`

- [ ] Add a resource test that loads `/assets/hmclce-plugin-root.json` and verifies the schema even when no production keys are configured.
- [ ] Generate the processed root resource from `HMCLCE_PLUGIN_ROOT_JSON`; use the checked-in empty development root only when the variable is absent.
- [ ] Pass `${{ vars.HMCLCE_PLUGIN_ROOT_JSON }}` into official GitHub builds without treating public root metadata as a secret.
- [ ] Run the resource test and inspect `HMCL/build/resources/main/assets/hmclce-plugin-root.json`.

### Task 4: GitHub Topic Discovery

**Files:**
- Create: `HMCL/src/main/java/org/jackhuang/hmcl/plugin/store/GitHubTopicDiscovery.java`
- Modify: `HMCL/src/main/java/org/jackhuang/hmcl/plugin/store/PluginStoreRegistry.java`
- Modify: `HMCL/src/main/java/org/jackhuang/hmcl/plugin/store/PluginStoreManager.java`
- Create: `HMCL/src/test/java/org/jackhuang/hmcl/plugin/store/GitHubTopicDiscoveryTest.java`

- [ ] Add local HTTP fixture tests for `topic:hmclce`, pagination, default branches, fork/archive filtering, bounded responses, and repository-derived identities.
- [ ] Run the discovery test and confirm it fails before implementation.
- [ ] Query the GitHub repository search API, build synthetic registry entries whose repository identity comes from API results, and fetch each default-branch `manifest.json`.
- [ ] Ensure optional GitHub authorization is attached only to `api.github.com` requests.
- [ ] Run discovery and store manager tests.

### Task 5: Built-In Official and Topic Sources

**Files:**
- Modify: `HMCL/src/main/java/org/jackhuang/hmcl/plugin/store/PluginSource.java`
- Modify: `HMCL/src/main/java/org/jackhuang/hmcl/plugin/store/PluginStorePreferences.java`
- Modify: `HMCL/src/main/java/org/jackhuang/hmcl/plugin/store/PluginSourceManagementPage.java`
- Modify: `HMCL/src/test/java/org/jackhuang/hmcl/plugin/store/PluginSourceRepositoryTest.java`
- Modify: `HMCL/src/test/java/org/jackhuang/hmcl/ui/main/PluginSourceManagementPageTest.java`

- [ ] Add tests requiring immutable built-in `official` and `github-topic-hmclce` sources and migration from older preference files.
- [ ] Make the official registry URL default to the HMCL CE Plugin Store raw `plugins.json` URL and add an enabled Topic source for `hmclce`.
- [ ] Prevent editing/removing/reordering built-ins in ways that change their security identity, while retaining enable/disable controls.
- [ ] Run preference and source-management tests.

### Task 6: Trust-Aware Manifest Loading and Aggregation

**Files:**
- Modify: `HMCL/src/main/java/org/jackhuang/hmcl/plugin/store/PluginStoreItem.java`
- Modify: `HMCL/src/main/java/org/jackhuang/hmcl/plugin/store/PluginStoreManager.java`
- Modify: `HMCL/src/main/java/org/jackhuang/hmcl/plugin/store/PluginStoreSnapshot.java`
- Modify: `HMCL/src/main/java/org/jackhuang/hmcl/plugin/store/PluginSourceProvenance.java`
- Modify: `HMCL/src/main/java/org/jackhuang/hmcl/plugin/store/PluginStoreDependencyResolver.java`
- Modify: `HMCL/src/test/java/org/jackhuang/hmcl/plugin/store/PluginStoreManagerTest.java`
- Modify: `HMCL/src/test/java/org/jackhuang/hmcl/plugin/store/PluginStoreAggregatorTest.java`

- [ ] Test unsigned manifests as `COMMUNITY`, valid signed manifests as `CERTIFIED`, bad or partial signatures as `REJECTED`, and signed official indices as `OFFICIAL`.
- [ ] Parse plain manifests and signed envelopes without ambiguous downgrade behavior, preserving exact source-bound trust evidence on each item.
- [ ] Select ID winners by `OFFICIAL > CERTIFIED > COMMUNITY > REJECTED`, retaining equal-rank and lower-rank candidates as conflicts.
- [ ] Reject dependency resolution when any selected downloadable item is `REJECTED` and carry trust level into immutable install provenance.
- [ ] Run all plugin store and dependency resolver tests.

### Task 7: Trust UI and Installation Policy

**Files:**
- Modify: `HMCL/src/main/java/org/jackhuang/hmcl/ui/main/PluginStorePage.java`
- Modify: `HMCL/src/main/resources/assets/lang/I18N.properties`
- Modify: `HMCL/src/main/resources/assets/lang/I18N_zh_CN.properties`
- Modify: `HMCL/src/test/java/org/jackhuang/hmcl/ui/main/PluginStorePageTest.java`

- [ ] Add presentation tests for official, certified, community, and rejected trust labels and install eligibility.
- [ ] Display an official certification label for `CERTIFIED`, a community label and source warning for `COMMUNITY`, and a verification error with disabled installation for `REJECTED`.
- [ ] Keep permission review mandatory for official and certified plugins.
- [ ] Run focused UI logic tests and translation checks.

### Task 8: SDK Signing Tool and Release Workflow

**Files:**
- Create: `HMCL-CE-Plugin-SDK/tools/HmclCeSigner.java`
- Create: `HMCL-CE-Plugin-SDK/tools/sign-plugin.ps1`
- Modify: `HMCL-CE-Plugin-SDK/tools/publish-plugin.ps1`
- Modify: `HMCL-CE-Plugin-SDK/store/github-release-workflow.yml`
- Modify: `HMCL-CE-Plugin-SDK/store/manifest.template.json`
- Modify: `HMCL-CE-Plugin-SDK/docs/PLUGIN_STORE_SETUP.md`

- [ ] Implement commands to generate Ed25519 key pairs, issue constrained developer certificates, sign canonical manifests, and independently verify outputs using Java 17.
- [ ] Make the tag workflow require `HMCLCE_PLUGIN_SIGNING_KEY` and `HMCLCE_PLUGIN_CERTIFICATE` only for certified releases; unsigned community releases remain supported explicitly.
- [ ] Sign after calculating package SHA-256 and size, verify before creating the GitHub Release, and publish the signed manifest beside the NPL.
- [ ] Exercise generate/sign/verify against a temporary fixture and verify one-byte manifest mutation fails.

### Task 9: Official Store Trust Metadata and Validation

**Files:**
- Create: `HMCL-CE-Plugin-Store/trust/root.template.json`
- Create: `HMCL-CE-Plugin-Store/trust/revocations.json`
- Create: `HMCL-CE-Plugin-Store/tools/sign-store.ps1`
- Create: `HMCL-CE-Plugin-Store/tools/test-sign-store.ps1`
- Modify: `HMCL-CE-Plugin-Store/tools/validate-store.ps1`
- Modify: `HMCL-CE-Plugin-Store/.github/workflows/validate.yml`
- Modify: `HMCL-CE-Plugin-Store/README.md`

- [ ] Validate official registry envelopes, role usage, root thresholds, certificate constraints, revocations, expiry, and package hashes.
- [ ] Sign official metadata with the narrowly scoped `HMCLCE_OFFICIAL_SIGNING_KEY`; never accept a developer CA key for store publication.
- [ ] Add CI mutation tests proving forged index, package, certificate, and rollback metadata fail.
- [ ] Run local PowerShell validator and signer tests.

### Task 10: Full Regression Verification

**Files:**
- Modify: `docs/superpowers/specs/2026-08-14-hmclce-plugin-trust-design.md` only if implementation reveals a concrete contradiction.

- [ ] Run `./gradlew :HMCL:test` from `D:\HMCL-CE`.
- [ ] Run `./gradlew :HMCL:checkTranslations` from `D:\HMCL-CE`.
- [ ] Run `./tools/validate-npl.ps1` from `D:\HMCL-CE-Plugin-SDK` against SDK fixtures.
- [ ] Run `./tools/test-sign-store.ps1` and `./tools/test-validate-store-downloads.ps1` from `D:\HMCL-CE-Plugin-Store`.
- [ ] Inspect diffs independently in all three directories and confirm no unrelated user changes were reverted.
