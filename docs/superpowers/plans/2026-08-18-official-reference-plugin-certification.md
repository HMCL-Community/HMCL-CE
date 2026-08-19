# Official Reference Plugin Certification Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Certify community plugin repositories solely through matching references in the signed official registry, without release attestations, online status, or mandatory receipts.

**Architecture:** Source managers validate and load registries as before, but ordinary manifests always begin as community content. `PluginStoreSnapshot` derives a `(pluginId, normalizedRepository)` allowlist from the successfully loaded official source and applies delegated `CERTIFIED` trust before conflict ranking. Default store and runtime paths stop activating the retired online proof system, while proof model classes remain readable for compatibility.

**Tech Stack:** Java 17, Gson, JUnit 5, Gradle

---

### Task 1: Delegate certification from the official registry

**Files:**
- Modify: `HMCL/src/main/java/org/jackhuang/hmcl/plugin/trust/PluginTrustResult.java`
- Modify: `HMCL/src/main/java/org/jackhuang/hmcl/plugin/store/PluginStoreSnapshot.java`
- Modify: `HMCL/src/test/java/org/jackhuang/hmcl/plugin/store/PluginStoreAggregatorTest.java`

- [x] **Step 1: Write failing aggregate tests**

Add tests that construct successful official and community source results and assert:

```java
assertEquals(PluginTrustLevel.CERTIFIED, matchingCommunityVersion.getTrust().level());
assertEquals(PluginTrustLevel.COMMUNITY, mismatchedRepositoryVersion.getTrust().level());
assertEquals(PluginTrustLevel.COMMUNITY, idOnlyCollisionVersion.getTrust().level());
```

Also cover every version in a matching manifest and a failed official source.

- [x] **Step 2: Verify the tests fail**

Run:

```powershell
.\gradlew.bat :HMCL:test --tests org.jackhuang.hmcl.plugin.store.PluginStoreAggregatorTest
```

Expected: new matching-reference assertions report `COMMUNITY` instead of `CERTIFIED`.

- [x] **Step 3: Add the delegated trust result**

Add a documented factory whose credential fields are absent because registry membership is the proof:

```java
public static PluginTrustResult certifiedByOfficialReference() {
    return new PluginTrustResult(
            PluginTrustLevel.CERTIFIED,
            "referenced by official plugin registry",
            null,
            null,
            null
    );
}
```

- [x] **Step 4: Apply official references before winner selection**

In `PluginStoreSnapshot`, derive normalized keys only from successful `source.isOfficial()` results. For each resolved non-official item, require the same plugin ID and normalized `entry.repository`; assign `certifiedByOfficialReference()` to all versions before ranking. Invalid or blank repository values do not match.

- [x] **Step 5: Verify aggregate tests pass**

Run the Task 1 test command and expect all tests to pass.

### Task 2: Retire per-version online proof evaluation

**Files:**
- Modify: `HMCL/src/main/java/org/jackhuang/hmcl/plugin/store/PluginStoreManager.java`
- Replace focused behavior in: `HMCL/src/test/java/org/jackhuang/hmcl/plugin/store/PluginStoreCertificationTest.java`

- [x] **Step 1: Write failing compatibility tests**

Replace the online-attestation scenario with an ordinary community source whose manifest contains a malformed legacy `certification` declaration. Assert that all valid versions remain `COMMUNITY`, no status endpoint is requested, and `refreshVersionTrust` preserves the snapshot-assigned trust.

- [x] **Step 2: Verify the tests fail**

Run:

```powershell
.\gradlew.bat :HMCL:test --tests org.jackhuang.hmcl.plugin.store.PluginStoreCertificationTest
```

Expected: the legacy declaration currently produces `REJECTED` or requests proof/status data.

- [x] **Step 3: Simplify source trust assignment**

Keep `OFFICIAL` and transport/schema rejection unchanged. For all other successfully parsed manifests, set every version to `PluginTrustResult.community()` regardless of legacy certification fields. Make `refreshVersionTrust` return the version's current trust without online reevaluation.

- [x] **Step 4: Stop default status-cache startup**

Keep `PluginTrustVerifier.loadDefault()` for signed official registry verification, but set the default manager's status cache to `null` and remove the due-refresh call from `loadSource`.

- [x] **Step 5: Verify certification compatibility tests pass**

Run the Task 2 test command and expect all tests to pass without status requests.

### Task 3: Permit certified installation without a receipt

**Files:**
- Modify: `HMCL/src/main/java/org/jackhuang/hmcl/ui/main/PluginStorePage.java`
- Modify: `HMCL/src/test/java/org/jackhuang/hmcl/ui/main/PluginStorePageTest.java`

- [x] **Step 1: Write a failing installation-preparation test**

Exercise a delegated `CERTIFIED` trust result with `certificationReceipt() == null` and assert installation preparation does not fail and produces no receipt entry.

- [x] **Step 2: Verify the test fails**

Run:

```powershell
.\gradlew.bat :HMCL:test --tests org.jackhuang.hmcl.ui.main.PluginStorePageTest
```

Expected: preparation fails with `Certified plugin has no verified installation receipt`.

- [x] **Step 3: Make receipt persistence optional**

When download trust is `CERTIFIED`, add a receipt only when `certificationReceipt()` is non-null. Do not reject delegated certification without a receipt. The existing installation transaction receives an empty receipt map and removes stale receipts for replaced plugins.

- [x] **Step 4: Verify page tests pass**

Run the Task 3 test command and expect all tests to pass.

### Task 4: Disable legacy runtime proof gates by default

**Files:**
- Modify: `HMCL/src/main/java/org/jackhuang/hmcl/plugin/trust/PluginRuntimeTrustGuard.java`
- Modify: `HMCL/src/main/java/org/jackhuang/hmcl/plugin/PluginManager.java`
- Modify: `HMCL/src/main/java/org/jackhuang/hmcl/plugin/mixin/bootstrap/HmclMixinBootstrap.java`
- Modify: `HMCL/src/test/java/org/jackhuang/hmcl/plugin/trust/PluginRuntimeTrustGuardTest.java`

- [x] **Step 1: Write a failing inactive-policy test**

Add a test for a documented `PluginRuntimeTrustGuard.inactive()` factory and assert it allows execution without loading receipts or status state.

- [x] **Step 2: Verify the test fails**

Run:

```powershell
.\gradlew.bat :HMCL:test --tests org.jackhuang.hmcl.plugin.trust.PluginRuntimeTrustGuardTest
```

Expected: compilation fails because `inactive()` does not exist.

- [x] **Step 3: Implement and use the inactive default**

Implement:

```java
public static PluginRuntimeTrustGuard inactive() {
    return new PluginRuntimeTrustGuard(Map.of(), null, () -> null, null);
}
```

Use it in normal `PluginManager` and mixin-bootstrap construction. Preserve explicit injected guards for focused compatibility tests.

- [x] **Step 4: Verify runtime and mixin tests pass**

Run:

```powershell
.\gradlew.bat :HMCL:test --tests org.jackhuang.hmcl.plugin.trust.PluginRuntimeTrustGuardTest --tests org.jackhuang.hmcl.plugin.PluginManagerCertificationReceiptTest --tests org.jackhuang.hmcl.plugin.mixin.bootstrap.HmclMixinBootstrapPermissionTest
```

Expected: all selected tests pass.

### Task 5: Regression verification

**Files:**
- Verify all modified production and test files.

- [x] **Step 1: Run focused plugin-store and trust tests**

```powershell
.\gradlew.bat :HMCL:test --tests 'org.jackhuang.hmcl.plugin.store.*' --tests 'org.jackhuang.hmcl.plugin.trust.*' --tests org.jackhuang.hmcl.ui.main.PluginStorePageTest
```

Expected: all focused tests pass.

- [x] **Step 2: Run the complete HMCL test task**

```powershell
.\gradlew.bat :HMCL:test
```

Expected: build succeeds with no test failures.

- [x] **Step 3: Inspect the final diff**

Confirm the diff contains only the certification rollback, tests, and design/plan documentation. This workspace has no `.git` metadata, so record modified paths with filesystem inspection instead of creating commits.
