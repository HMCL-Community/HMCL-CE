# HMCL CE Next Plugin Contract Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make HMCL CE `next` install and execute schema-v4 and schema-v5 plugins through one runtime/ABI/platform compatibility contract, then publish the matching prerelease SDK surface on `schema-v5` while retaining `schema-v4` as the SDK's primary branch.

**Architecture:** `PluginManifest` owns schema parsing and declaration validation; immutable compatibility requirements are evaluated by one `PluginCompatibilityEvaluator` backed by `RuntimeProviderRegistry` and an injected host platform. Plugin Manager, Mixin bootstrap, local installation, reuse, and Store filtering all consume that evaluator before code loading. The SDK mirrors the finalized public contract, but actual Hook dispatch, bytecode patching, external runtime hosts, runtime lifecycle management, and Store artifact matrices remain deferred.

**Tech Stack:** Java 17, Gson, JUnit 5, Gradle 9, PowerShell 5+, Git/GitHub CLI, ZIP-compatible `.npl` archives.

---

## File And Ownership Map

- `HMCL/src/main/java/org/jackhuang/hmcl/plugin/PluginManifest.java`: schema-v4 mapping and schema-v5 manifest contract.
- `HMCL/src/main/java/org/jackhuang/hmcl/plugin/PluginCapabilityLevel.java`: derived API/HOOK/PATCH capability.
- `HMCL/src/main/java/org/jackhuang/hmcl/plugin/PluginHookPoint.java`: supported launcher Hook identifiers.
- `HMCL/src/main/java/org/jackhuang/hmcl/plugin/PluginPatchDeclaration.java`: validated, overload-specific Patch declaration.
- `HMCL/src/main/java/org/jackhuang/hmcl/plugin/PluginPermission.java`: Hook/Patch permission identifiers.
- `HMCL/src/main/java/org/jackhuang/hmcl/plugin/PluginPermissionTier.java`: explicit risk classification.
- `HMCL/src/main/java/org/jackhuang/hmcl/plugin/runtime/PluginCompatibility*.java`: shared immutable compatibility model and evaluator.
- `HMCL/src/main/java/org/jackhuang/hmcl/plugin/runtime/RuntimeProviderRegistry.java`: protected built-in Java provider plus external provider lookup.
- `HMCL/src/main/java/org/jackhuang/hmcl/plugin/PluginManager.java`: lifecycle, inspection, enablement, and install gates.
- `HMCL/src/main/java/org/jackhuang/hmcl/plugin/PluginReusePolicy.java`: reusable-package compatibility gate.
- `HMCL/src/main/java/org/jackhuang/hmcl/plugin/mixin/bootstrap/HmclMixinBootstrap.java`: startup Mixin preload gate.
- `HMCL/src/main/java/org/jackhuang/hmcl/plugin/store/PluginStoreManifest.java`: version-entry compatibility metadata.
- `HMCL/src/main/java/org/jackhuang/hmcl/plugin/store/PluginStoreManager.java`: Store filtering and downloaded-package reconciliation.
- `HMCL/src/test/java/org/jackhuang/hmcl/plugin/**`: manifest, runtime, manager, lifecycle, installation, and Mixin regression tests.
- `HMCL/src/test/java/org/jackhuang/hmcl/plugin/store/**`: Store metadata/filter/download regression tests.
- `C:/Users/ACX/Documents/Plugins/HMCL-CE-Plugin-SDK/tools/validate-npl.ps1`: v4/v5 archive validator on `schema-v5`.
- `C:/Users/ACX/Documents/Plugins/HMCL-CE-Plugin-SDK/tools/test-validate-npl.ps1`: deterministic validator test harness.
- `C:/Users/ACX/Documents/Plugins/HMCL-CE-Plugin-SDK/tools/sync-api-references.ps1`: public Java API snapshot list.
- `C:/Users/ACX/Documents/Plugins/HMCL-CE-Plugin-SDK/references/hmcl-plugin-api/**`: synchronized `next` API snapshots.
- `C:/Users/ACX/Documents/Plugins/HMCL-CE-Plugin-SDK/examples/**/plugin.json`: schema-v5 example manifests.
- `C:/Users/ACX/Documents/Plugins/HMCL-CE-Plugin-SDK/store/**`: schema-v5 publishing metadata templates.
- `C:/Users/ACX/Documents/Plugins/HMCL-CE-Plugin-SDK/dist/**`: rebuilt, validated example packages and checksums.

## Scope Locks

- Preserve the four Claude working-tree changes and absorb them into Task 2; never discard or reset them.
- HMCL `main` remains schema v4. HMCL `next` accepts schema v4 and v5.
- SDK remote `main` is renamed to `schema-v4`; no remote `main` remains. `schema-v4` remains GitHub default.
- SDK `schema-v4` contents remain unchanged because all apparent `main` URLs in controlled files target downstream plugin repositories, not the SDK branch.
- Do not implement Hook event dispatch, Patch bytecode transformation, external runtime providers, runtime auto-installation, Store artifact matrices, or release-channel promotion.
- All new or modified Java declarations follow the repository instructions: GPL header, `@NotNullByDefault` on every newly added class, explicit `@Nullable`, `@Unmodifiable`/`@UnmodifiableView` for immutable arrays and collections, `///` documentation for every added class/field/method, and final LF newline.

### Task 1: Restore A Meaningful HMCL Baseline

**Files:**
- Modify: `HMCL/src/test/java/org/jackhuang/hmcl/plugin/NextPluginRuntimeTest.java`
- Modify: `HMCL/src/main/java/org/jackhuang/hmcl/plugin/PluginCapabilityLevel.java`
- Modify: `HMCL/src/main/java/org/jackhuang/hmcl/plugin/PluginHookPoint.java`
- Modify: `HMCL/src/main/java/org/jackhuang/hmcl/plugin/PluginPatchDeclaration.java`
- Modify: `HMCL/src/main/java/org/jackhuang/hmcl/plugin/PluginPermissionTier.java`
- Modify: `HMCL/src/main/java/org/jackhuang/hmcl/plugin/runtime/JavaRuntimeProvider.java`
- Modify: `HMCL/src/main/java/org/jackhuang/hmcl/plugin/runtime/PluginAbi.java`
- Modify: `HMCL/src/main/java/org/jackhuang/hmcl/plugin/runtime/PluginPlatformTarget.java`
- Modify: `HMCL/src/main/java/org/jackhuang/hmcl/plugin/runtime/PluginRuntimeTypes.java`
- Modify: `HMCL/src/main/java/org/jackhuang/hmcl/plugin/runtime/RuntimeProvider.java`
- Modify: `HMCL/src/main/java/org/jackhuang/hmcl/plugin/runtime/RuntimeProviderRegistry.java`

- [ ] **Step 1: Reproduce the inherited compile failure**

Run:

```powershell
.\gradlew.bat :HMCL:compileTestJava --no-daemon --stacktrace
```

Expected: FAIL at `NextPluginRuntimeTest.java` because an anonymous class attempts to extend final `JavaRuntimeProvider`.

- [ ] **Step 2: Replace the invalid fake with a real RuntimeProvider test double**

Use this complete anonymous implementation in `runtimeProviderRegistryLifecycle()`:

```java
RuntimeProvider dotnet = new RuntimeProvider() {
    @Override
    public String runtimeType() {
        return "dotnet";
    }

    @Override
    public @Unmodifiable Set<Integer> implementedPluginAbis() {
        return Set.of(PluginAbi.ABI_1);
    }

    @Override
    public String describe() {
        return "Test .NET host";
    }
};
```

Add the GPL header, `@NotNullByDefault`, the `Set` import, `@Unmodifiable`, and `///` documentation required for the test class and its methods.

- [ ] **Step 3: Restore the Checkstyle baseline without changing behavior**

Add the exact repository GPL header from `config/checkstyle/license-header.txt` to the ten reported main-source files and ensure every Java file listed above ends with LF. Do not change manifest or registry behavior in this task.

- [ ] **Step 4: Verify compilation and style are green**

Run:

```powershell
.\gradlew.bat :HMCL:compileTestJava :HMCL:checkstyleMain :HMCL:checkstyleTest --no-daemon --stacktrace
```

Expected: BUILD SUCCESSFUL. The existing `JSObject` removal messages may remain warnings, but there are zero Java errors and zero Checkstyle violations.

- [ ] **Step 5: Commit only baseline repairs**

```powershell
git add HMCL/src/main/java/org/jackhuang/hmcl/plugin/PluginPermissionTier.java
git add HMCL/src/main/java/org/jackhuang/hmcl/plugin/runtime/JavaRuntimeProvider.java
git add HMCL/src/main/java/org/jackhuang/hmcl/plugin/runtime/PluginAbi.java
git add HMCL/src/main/java/org/jackhuang/hmcl/plugin/runtime/PluginPlatformTarget.java
git add HMCL/src/main/java/org/jackhuang/hmcl/plugin/runtime/PluginRuntimeTypes.java
git add HMCL/src/main/java/org/jackhuang/hmcl/plugin/runtime/RuntimeProvider.java
git add HMCL/src/main/java/org/jackhuang/hmcl/plugin/runtime/RuntimeProviderRegistry.java
git add HMCL/src/test/java/org/jackhuang/hmcl/plugin/NextPluginRuntimeTest.java
git commit -m "Fix next plugin contract build baseline"
```

Expected: the commit contains license/EOF changes and the RuntimeProvider test double, while the four Claude contract files remain present for Task 2.

### Task 2: Complete The Schema-V5 Manifest, Hook, Patch, And Permission Contract

**Files:**
- Modify: `HMCL/src/test/java/org/jackhuang/hmcl/plugin/PluginManifestTest.java`
- Modify: `HMCL/src/test/java/org/jackhuang/hmcl/plugin/NextPluginRuntimeTest.java`
- Modify: `HMCL/src/main/java/org/jackhuang/hmcl/plugin/PluginManifest.java`
- Modify: `HMCL/src/main/java/org/jackhuang/hmcl/plugin/PluginCapabilityLevel.java`
- Modify: `HMCL/src/main/java/org/jackhuang/hmcl/plugin/PluginHookPoint.java`
- Modify: `HMCL/src/main/java/org/jackhuang/hmcl/plugin/PluginPatchDeclaration.java`
- Modify: `HMCL/src/main/java/org/jackhuang/hmcl/plugin/PluginPermission.java`
- Modify: `HMCL/src/main/java/org/jackhuang/hmcl/plugin/PluginPermissionTier.java`

- [ ] **Step 1: Write a valid schema-v5 parse test before changing production code**

Add a test that parses this exact contract and asserts normalized runtime, ABI, platforms, Hook/Patch lists, ordered Patch parameters, and `PATCH` capability:

```json
{
  "schemaVersion": 5,
  "id": "dev.hmclce.test.schema-five",
  "name": "Schema Five",
  "version": "1.0.0",
  "type": "java",
  "entrypoint": "dev.hmclce.test.Plugin",
  "permissions": ["launcher-hook", "launcher-patch"],
  "requiredPermissions": ["launcher-hook", "launcher-patch"],
  "launcherVersion": "*",
  "runtime": "java",
  "abi": 2,
  "platforms": ["windows-x64", "linux"],
  "hooks": ["before-game-launch"],
  "patches": [{
    "target": "org.jackhuang.hmcl.game.GameLaunchService",
    "method": "launch",
    "type": "before",
    "parameters": ["org.jackhuang.hmcl.game.LaunchContext"]
  }]
}
```

- [ ] **Step 2: Run the parse test and observe the semantic RED**

```powershell
.\gradlew.bat :HMCL:test --tests "org.jackhuang.hmcl.plugin.PluginManifestTest.parseSchemaVersionFiveContract" --no-daemon --stacktrace
```

Expected: FAIL because `PluginManifest` does not expose Hooks/Patches/capability and `PluginPatchDeclaration` does not expose ordered parameters.

- [ ] **Step 3: Implement Patch identity and manifest getters minimally**

`PluginPatchDeclaration` must expose this API and validate each element:

```java
@SerializedName("parameters")
private @Nullable List<@Nullable String> parameters;

public PluginPatchDeclaration(String target, String method, PatchType type, List<String> parameters) {
    this.target = target;
    this.method = method;
    this.type = type;
    this.parameters = List.copyOf(parameters);
    validate();
}

public @Unmodifiable List<String> getParameters() {
    @Nullable List<@Nullable String> values = parameters;
    if (values == null) {
        throw new IllegalStateException("Patch parameters were not validated");
    }
    return values.stream().map(Objects::requireNonNull).toList();
}
```

Add value-based `equals`/`hashCode`. In `PluginManifest`, add serialized nullable lists plus presence flags, immutable getters, `hasHooks()`, `hasPatches()`, and `getCapabilityLevel()`.

- [ ] **Step 4: Write rejection tests for every schema and permission boundary**

Add individual assertions that reject:

```text
schema 5 missing runtime
schema 5 runtime explicitly null
schema 5 missing abi
schema 5 abi explicitly null or unsupported
schema 5 null/noncanonical/unknown/duplicate platform targets
schema 5 null/unknown/duplicate Hook points
schema 5 Patch missing target, method, type, or parameters
schema 5 Patch with null or blank parameter name
schema 5 duplicate Patch identity
Hook without launcher-hook in permissions
Hook without launcher-hook in requiredPermissions
Patch without launcher-patch in permissions
Patch without launcher-patch in requiredPermissions
schema 4 declaring runtime, abi, platforms, hooks, patches, launcher-hook, or launcher-patch
```

Use a `schemaFiveWithDeclarations(String declarationsJson)` helper that always supplies schema, identity, Java type, entry point, empty dependencies, and no implicit v5 declaration beyond the caller's JSON.

- [ ] **Step 5: Run the rejection tests and verify they fail for missing behavior**

```powershell
.\gradlew.bat :HMCL:test --tests "org.jackhuang.hmcl.plugin.PluginManifestTest.rejectInvalidSchemaVersionFiveDeclarations" --tests "org.jackhuang.hmcl.plugin.PluginManifestTest.requireHookAndPatchPermissions" --tests "org.jackhuang.hmcl.plugin.PluginManifestTest.rejectSchemaVersionFiveFieldsBeforeSchemaFive" --no-daemon --stacktrace
```

Expected: FAIL on at least one absent-field, duplicate, or permission-coupling assertion rather than a test compilation error.

- [ ] **Step 6: Complete presence-aware parsing and validation**

Parse the root once in `fromJson(Reader)`, set `runtimeDeclared`, `abiDeclared`, `platformsDeclared`, `hooksDeclared`, and `patchesDeclared` with `JsonObject.has`, and reject an explicitly JSON-null `abi` before Gson can preserve the primitive field's default value:

```java
@Nullable JsonElement abiElement = root.get("abi");
if (abiElement != null && abiElement.isJsonNull()) {
    throw new IOException("Plugin manifest abi cannot be null");
}
```

Then validate using these rules:

```java
if (schemaVersion >= 5) {
    if (!runtimeDeclared || runtime == null) {
        throw new IOException("Schema-v5 plugin manifest must declare runtime");
    }
    if (!abiDeclared) {
        throw new IOException("Schema-v5 plugin manifest must declare abi");
    }
} else if (runtimeDeclared || abiDeclared || platformsDeclared || hooksDeclared || patchesDeclared) {
    throw new IOException("Plugin manifest schemaVersion " + schemaVersion + " cannot declare schema-v5 fields");
}
```

Normalize v4 as Java/ABI 1/unrestricted, treat absent and empty v5 platforms as unrestricted, validate duplicate normalized values, require both declared and required permissions for Hook/Patch, and initialize programmatic manifests as schema 5/Java/ABI 2 with presence flags set.

- [ ] **Step 7: Add equality, constructor, capability, and permission-tier tests**

Assert that changing each of runtime, ABI, normalized platforms, Hooks, Patch target/method/type/ordered parameters changes equality/hash identity; assert `new PluginManifest(...).validate()` succeeds; assert API/HOOK/PATCH derivation; assert both new permissions are `DANGEROUS`. Correct the v4 parse assertion to `assertEquals(4, manifest.getSchemaVersion())`.

- [ ] **Step 8: Run focused tests and style checks**

```powershell
.\gradlew.bat :HMCL:test --tests "org.jackhuang.hmcl.plugin.PluginManifestTest" --tests "org.jackhuang.hmcl.plugin.NextPluginRuntimeTest" --no-daemon --stacktrace
.\gradlew.bat :HMCL:checkstyleMain :HMCL:checkstyleTest --no-daemon --stacktrace
```

Expected: both commands succeed, including schema-v4 compatibility assertions.

- [ ] **Step 9: Commit the complete manifest contract**

```powershell
git add HMCL/src/main/java/org/jackhuang/hmcl/plugin HMCL/src/test/java/org/jackhuang/hmcl/plugin/PluginManifestTest.java HMCL/src/test/java/org/jackhuang/hmcl/plugin/NextPluginRuntimeTest.java
git commit -m "Complete schema v5 plugin manifest contract"
```

### Task 3: Add The Shared Runtime Compatibility Evaluator

**Files:**
- Create: `HMCL/src/main/java/org/jackhuang/hmcl/plugin/runtime/PluginCompatibilityStatus.java`
- Create: `HMCL/src/main/java/org/jackhuang/hmcl/plugin/runtime/PluginCompatibilityResult.java`
- Create: `HMCL/src/main/java/org/jackhuang/hmcl/plugin/runtime/PluginCompatibilityRequirements.java`
- Create: `HMCL/src/main/java/org/jackhuang/hmcl/plugin/runtime/PluginCompatibilityEvaluator.java`
- Create: `HMCL/src/test/java/org/jackhuang/hmcl/plugin/runtime/PluginCompatibilityEvaluatorTest.java`
- Modify: `HMCL/src/main/java/org/jackhuang/hmcl/plugin/runtime/RuntimeProviderRegistry.java`
- Modify: `HMCL/src/test/java/org/jackhuang/hmcl/plugin/NextPluginRuntimeTest.java`

- [ ] **Step 1: Write registry protection tests**

Assert duplicate runtime registration throws, `" JAVA "` cannot replace or remove the built-in provider, an external provider can be removed after canonicalization, and `describeAll()` is an immutable snapshot keyed by canonical runtime IDs.

- [ ] **Step 2: Run the registry tests and verify RED**

```powershell
.\gradlew.bat :HMCL:test --tests "org.jackhuang.hmcl.plugin.NextPluginRuntimeTest.runtimeProviderRegistryRejectsDuplicateProviders" --tests "org.jackhuang.hmcl.plugin.NextPluginRuntimeTest.runtimeProviderRegistryProtectsCanonicalJavaProvider" --no-daemon --stacktrace
```

Expected: FAIL because `register()` currently replaces entries and Java removal checks the unnormalized input.

- [ ] **Step 3: Make registry ownership deterministic**

Implement registration and removal around one canonical key:

```java
public void register(RuntimeProvider provider) {
    String type = PluginRuntimeTypes.requireValid(provider.runtimeType());
    RuntimeProvider previous = providers.putIfAbsent(type, provider);
    if (previous != null) {
        throw new IllegalStateException("Runtime provider already registered: " + type);
    }
}

public void unregister(String runtimeType) {
    String type = PluginRuntimeTypes.requireValid(runtimeType);
    if (!PluginRuntimeTypes.JAVA.equals(type)) {
        providers.remove(type);
    }
}
```

Return immutable snapshots from provider collection APIs.

- [ ] **Step 4: Define the evaluator's wished-for behavior in tests**

Create one focused test per status using injected providers and host targets:

```java
assertEquals(PluginCompatibilityStatus.COMPATIBLE, evaluate(schema4()).status());
assertEquals(PluginCompatibilityStatus.UNSUPPORTED_SCHEMA, evaluate(schema6()).status());
assertEquals(PluginCompatibilityStatus.UNSUPPORTED_LAUNCHER, evaluate(launcherMismatch()).status());
assertEquals(PluginCompatibilityStatus.UNSUPPORTED_PLATFORM, evaluate(windowsOnLinux()).status());
assertEquals(PluginCompatibilityStatus.MISSING_RUNTIME, evaluate(missingDotnet()).status());
assertEquals(PluginCompatibilityStatus.UNSUPPORTED_ABI, evaluate(dotnetAbi2WithAbi1Provider()).status());
```

Also assert unknown host architecture accepts unrestricted and OS-only requirements but rejects architecture-specific requirements.

- [ ] **Step 5: Run evaluator tests and verify RED**

```powershell
.\gradlew.bat :HMCL:test --tests "org.jackhuang.hmcl.plugin.runtime.PluginCompatibilityEvaluatorTest" --no-daemon --stacktrace
```

Expected: test compilation fails because the four compatibility types do not exist.

- [ ] **Step 6: Implement immutable requirements and deterministic evaluation**

Use these public shapes:

```java
public enum PluginCompatibilityStatus {
    COMPATIBLE,
    UNSUPPORTED_SCHEMA,
    UNSUPPORTED_LAUNCHER,
    UNSUPPORTED_PLATFORM,
    MISSING_RUNTIME,
    UNSUPPORTED_ABI
}

public record PluginCompatibilityResult(PluginCompatibilityStatus status, String detail) {
    public boolean isCompatible() {
        return status == PluginCompatibilityStatus.COMPATIBLE;
    }
}

public record PluginCompatibilityRequirements(
        int schemaVersion,
        String launcherVersion,
        String runtime,
        int abi,
        @Unmodifiable List<PluginPlatformTarget> platforms) {
    public PluginCompatibilityRequirements {
        platforms = List.copyOf(platforms);
    }
}
```

`PluginCompatibilityEvaluator.evaluate(requirements, launcherVersion)` must evaluate strictly in this order: schema, launcher constraint, platform, runtime availability, provider ABI. Diagnostics include the offending requirement and host/provider value.

- [ ] **Step 7: Verify runtime tests and Checkstyle**

```powershell
.\gradlew.bat :HMCL:test --tests "org.jackhuang.hmcl.plugin.NextPluginRuntimeTest" --tests "org.jackhuang.hmcl.plugin.runtime.PluginCompatibilityEvaluatorTest" --no-daemon --stacktrace
.\gradlew.bat :HMCL:checkstyleMain :HMCL:checkstyleTest --no-daemon --stacktrace
```

Expected: BUILD SUCCESSFUL for both commands.

- [ ] **Step 8: Commit the evaluator boundary**

```powershell
git add HMCL/src/main/java/org/jackhuang/hmcl/plugin/runtime HMCL/src/test/java/org/jackhuang/hmcl/plugin/runtime HMCL/src/test/java/org/jackhuang/hmcl/plugin/NextPluginRuntimeTest.java
git commit -m "Add shared plugin compatibility evaluator"
```

### Task 4: Gate Startup, Loading, Reuse, And Local Installation

**Files:**
- Modify: `HMCL/src/main/java/org/jackhuang/hmcl/plugin/PluginManager.java`
- Modify: `HMCL/src/main/java/org/jackhuang/hmcl/plugin/PluginReusePolicy.java`
- Modify: `HMCL/src/main/java/org/jackhuang/hmcl/plugin/mixin/bootstrap/HmclMixinBootstrap.java`
- Modify: `HMCL/src/test/java/org/jackhuang/hmcl/plugin/PluginManagerLocalInstallTest.java`
- Modify: `HMCL/src/test/java/org/jackhuang/hmcl/plugin/PluginManagerLifecycleStateTest.java`
- Modify: `HMCL/src/test/java/org/jackhuang/hmcl/plugin/PluginManagerReuseEligibilityTest.java`
- Modify: `HMCL/src/test/java/org/jackhuang/hmcl/plugin/mixin/bootstrap/HmclMixinBootstrapPermissionTest.java`

- [ ] **Step 1: Add a schema-v4 execution regression before integration**

Use the existing `writePluginPackage(...)` fixture to install and discover a valid schema-v4 Java/ABI-1 package on `next`; assert inspection, staging, restart discovery, and lifecycle load all succeed.

- [ ] **Step 2: Run the v4 regression and verify RED**

```powershell
.\gradlew.bat :HMCL:test --tests "org.jackhuang.hmcl.plugin.PluginManagerLocalInstallTest.installAndLoadSchemaVersionFourPluginOnNext" --tests "org.jackhuang.hmcl.plugin.PluginManagerLifecycleStateTest.discoverEnabledSchemaVersionFourPlugin" --no-daemon --stacktrace
```

Expected: FAIL because Manager paths still compare schema directly with `CURRENT_SCHEMA_VERSION`.

- [ ] **Step 3: Inject one evaluator into Manager and reuse policy**

Production construction creates a `RuntimeProviderRegistry`, detects `PluginPlatformTarget.current()`, and constructs one evaluator. Add a package-private test constructor accepting the evaluator. Replace direct current-schema and launcher checks in discovery, `getPreLoadBlock`, `preparePluginInternal`, `enablePlugin`, `recordEnableIntent`, `inspectLocalPluginPackage`, `stagePluginInstallationsLocked`, and `PluginReusePolicy.resolveReusableIdentity`.

Map only `UNSUPPORTED_SCHEMA` to the existing legacy-blocked lifecycle state; map other incompatibilities to load/install failure while preserving `PluginCompatibilityResult.detail()`.

- [ ] **Step 4: Add pre-loader fail-closed tests**

For schema-v5 packages, test incompatible platform, missing runtime, and unsupported provider ABI. Instrument existing lifecycle probe/class-loader fixtures and assert their invocation count remains zero after rejection. Add the same three cases to local inspection and final staging so a compatibility change between confirmation and atomic publication still fails.

- [ ] **Step 5: Run Manager tests and verify RED for missing gates**

```powershell
.\gradlew.bat :HMCL:test --tests "org.jackhuang.hmcl.plugin.PluginManagerLocalInstallTest" --tests "org.jackhuang.hmcl.plugin.PluginManagerLifecycleStateTest" --tests "org.jackhuang.hmcl.plugin.PluginManagerReuseEligibilityTest" --no-daemon --stacktrace
```

Expected: at least one schema-v5 incompatibility reaches a probe or produces the old generic schema diagnostic.

- [ ] **Step 6: Complete Manager gate placement**

Run compatibility before trust, permission, Mixin-agent, dependency traversal, verified package cache, loader creation, and lifecycle callbacks. Keep the existing trust and transaction code unchanged. Use the same evaluator again immediately before final install publication as a state-change defense.

- [ ] **Step 7: Add and implement Mixin bootstrap rejection tests**

Write raw package fixtures for platform mismatch, missing runtime, and unsupported ABI; assert none enters Agent configuration or classpath. Replace `HmclMixinBootstrap.isExecutableCandidateAuthorized()` schema/launcher logic with the evaluator and remove its second launcher-compatibility implementation.

- [ ] **Step 8: Verify the complete preload gate**

```powershell
.\gradlew.bat :HMCL:test --tests "org.jackhuang.hmcl.plugin.PluginManager*" --tests "org.jackhuang.hmcl.plugin.mixin.bootstrap.HmclMixinBootstrapPermissionTest" --no-daemon --stacktrace
.\gradlew.bat :HMCL:checkstyleMain :HMCL:checkstyleTest --no-daemon --stacktrace
```

Expected: BUILD SUCCESSFUL; schema v4 executes, schema v5 incompatibilities fail before code loading.

- [ ] **Step 9: Commit lifecycle integration**

```powershell
git add HMCL/src/main/java/org/jackhuang/hmcl/plugin/PluginManager.java HMCL/src/main/java/org/jackhuang/hmcl/plugin/PluginReusePolicy.java HMCL/src/main/java/org/jackhuang/hmcl/plugin/mixin/bootstrap/HmclMixinBootstrap.java HMCL/src/test/java/org/jackhuang/hmcl/plugin HMCL/src/test/java/org/jackhuang/hmcl/plugin/mixin/bootstrap/HmclMixinBootstrapPermissionTest.java
git commit -m "Gate plugin lifecycle compatibility before loading"
```

### Task 5: Share Compatibility With Store Metadata And Download Reconciliation

**Files:**
- Modify: `HMCL/src/main/java/org/jackhuang/hmcl/plugin/store/PluginStoreManifest.java`
- Modify: `HMCL/src/main/java/org/jackhuang/hmcl/plugin/store/PluginStoreManager.java`
- Modify: `HMCL/src/test/java/org/jackhuang/hmcl/plugin/store/PluginStoreManifestTest.java`
- Modify: `HMCL/src/test/java/org/jackhuang/hmcl/plugin/store/PluginStoreManagerTest.java`

- [ ] **Step 1: Write Store metadata parse and rejection tests**

Add schema-v5 version entries with explicit `runtime`, `abi`, and `platforms`. Assert normalized immutable values. Reject missing/null/invalid runtime or ABI, duplicate/invalid platforms, and schema-v4 entries that declare v5 fields. Assert a v4 entry maps to Java/ABI 1/unrestricted.

- [ ] **Step 2: Run Store manifest tests and verify RED**

```powershell
.\gradlew.bat :HMCL:test --tests "org.jackhuang.hmcl.plugin.store.PluginStoreManifestTest" --no-daemon --stacktrace
```

Expected: FAIL because `PluginVersionEntry` does not yet own v5 compatibility metadata.

- [ ] **Step 3: Add version-entry requirements conversion**

Add presence-aware serialized fields and a method returning `PluginCompatibilityRequirements`. The Store version entry follows the same v4 mapping and v5 validation as package manifests; platform normalization uses `PluginPlatformTarget.parse()` and immutable snapshots.

- [ ] **Step 4: Write shared-filter and downloaded-package mismatch tests**

Cover compatible v4 and v5 versions, schema mismatch, launcher mismatch, platform mismatch, missing runtime, unsupported ABI, and each downloaded-package mismatch for runtime, ABI, and normalized platforms. Preserve the existing Java-version Store-only check.

- [ ] **Step 5: Run Store Manager tests and verify RED**

```powershell
.\gradlew.bat :HMCL:test --tests "org.jackhuang.hmcl.plugin.store.PluginStoreManagerTest" --no-daemon --stacktrace
```

Expected: v4 filtering fails under the old `pluginApiVersion != CURRENT_SCHEMA_VERSION` rule and at least one v5 field mismatch is not detected.

- [ ] **Step 6: Use the evaluator before download and reconcile after download**

Replace direct schema/launcher compatibility in `validateCompatibility()` with the shared evaluator. Keep dependency resolution calling that method. In `validateDownloadedPackage()`, compare version entry and package manifest ID, version, schema, permissions, dependencies, runtime, ABI, and normalized platforms before publishing the file.

- [ ] **Step 7: Verify Store and all focused contract tests**

```powershell
.\gradlew.bat :HMCL:test --tests "org.jackhuang.hmcl.plugin.PluginManifestTest" --tests "org.jackhuang.hmcl.plugin.NextPluginRuntimeTest" --tests "org.jackhuang.hmcl.plugin.runtime.PluginCompatibilityEvaluatorTest" --tests "org.jackhuang.hmcl.plugin.PluginManagerLocalInstallTest" --tests "org.jackhuang.hmcl.plugin.PluginManagerLifecycleStateTest" --tests "org.jackhuang.hmcl.plugin.mixin.bootstrap.HmclMixinBootstrapPermissionTest" --tests "org.jackhuang.hmcl.plugin.store.PluginStoreManifestTest" --tests "org.jackhuang.hmcl.plugin.store.PluginStoreManagerTest" --no-daemon --stacktrace
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Commit Store integration**

```powershell
git add HMCL/src/main/java/org/jackhuang/hmcl/plugin/store HMCL/src/test/java/org/jackhuang/hmcl/plugin/store
git commit -m "Filter schema v4 and v5 store compatibility"
```

### Task 6: Rename The SDK Stable Branch And Create Schema V5

**Files:**
- No content files change on `schema-v4`.
- Branch refs change in `C:/Users/ACX/Documents/Plugins/HMCL-CE-Plugin-SDK` and GitHub.

- [ ] **Step 1: Prove the SDK preconditions before remote mutation**

```powershell
Set-Location C:\Users\ACX\Documents\Plugins\HMCL-CE-Plugin-SDK
git status --porcelain=v1
git fetch origin --prune
git rev-parse main
git rev-parse origin/main
gh api repos/HMCL-Community/HMCL-CE-Plugin-SDK --jq .default_branch
gh api repos/HMCL-Community/HMCL-CE-Plugin-SDK/branches/main --jq '{name,protected,sha:.commit.sha}'
```

Expected: clean status; local and remote are `3a867062e8e771ba0e81a5e1c9c1a0f4b4d37816`; default is `main`; branch is not protected.

- [ ] **Step 2: Rename the GitHub branch atomically**

```powershell
gh api --method POST repos/HMCL-Community/HMCL-CE-Plugin-SDK/branches/main/rename -f new_name=schema-v4 --jq '{name,protected,sha:.commit.sha}'
```

Expected: response name `schema-v4` at the same SHA. GitHub default changes to `schema-v4` and remote `main` disappears.

- [ ] **Step 3: Align local refs and create the future branch**

```powershell
git branch -m main schema-v4
git fetch origin --prune
git branch --set-upstream-to=origin/schema-v4 schema-v4
git remote set-head origin --auto
git switch -c schema-v5 schema-v4
```

Expected: local branch is `schema-v5`; `schema-v4` tracks `origin/schema-v4`; `origin/HEAD` targets `origin/schema-v4`.

- [ ] **Step 4: Verify the branch model without content churn**

```powershell
git ls-remote --heads origin main schema-v4 schema-v5
git symbolic-ref refs/remotes/origin/HEAD
gh api repos/HMCL-Community/HMCL-CE-Plugin-SDK --jq .default_branch
git status --short --branch
```

Expected: remote lists only `schema-v4` at this point; default/remote HEAD are `schema-v4`; local clean `schema-v5` is based on `schema-v4`. Do not rewrite downstream `owner/repo/main/...` template URLs.

### Task 7: Add The Schema-V5 SDK Validator And API Snapshot

**Files:**
- Create: `C:/Users/ACX/Documents/Plugins/HMCL-CE-Plugin-SDK/tools/test-validate-npl.ps1`
- Modify: `C:/Users/ACX/Documents/Plugins/HMCL-CE-Plugin-SDK/tools/validate-npl.ps1`
- Modify: `C:/Users/ACX/Documents/Plugins/HMCL-CE-Plugin-SDK/tools/sync-api-references.ps1`
- Modify: `C:/Users/ACX/Documents/Plugins/HMCL-CE-Plugin-SDK/references/README.md`
- Add/refresh: `C:/Users/ACX/Documents/Plugins/HMCL-CE-Plugin-SDK/references/hmcl-plugin-api/*.java`

- [ ] **Step 1: Write validator fixtures before implementation**

`test-validate-npl.ps1` must create temporary ZIP/NPL packages and assert exit success/failure for: valid v4, valid v5 Java ABI 2, missing runtime, missing ABI, noncanonical runtime, invalid/duplicate platforms, unknown/duplicate Hooks, invalid/duplicate Patches including required ordered parameters, Hook/Patch permission coupling, and v5-only declarations under schema v4. Temporary directories are removed in `finally`.

- [ ] **Step 2: Run validator tests and verify RED**

```powershell
Set-Location C:\Users\ACX\Documents\Plugins\HMCL-CE-Plugin-SDK
.\tools\test-validate-npl.ps1
```

Expected: FAIL because the current validator only accepts schema v4 and does not understand v5 declarations.

- [ ] **Step 3: Implement v4/v5 validation in one structured parser**

On `schema-v5`, accept schemas 4 and 5. Keep v4 semantics strict. For v5 require canonical `runtime`, integer ABI supported by the SDK contract, optional canonical `platforms`, the 12 exact Hook values, Patch `target`/`method`/`type`/required ordered `parameters`, normalized duplicate rejection, and both permission-list couplings. Report the exact field/value that fails.

- [ ] **Step 4: Re-run validator tests and publishing-tool regression**

```powershell
.\tools\test-validate-npl.ps1
.\tools\test-publishing-tools.ps1
```

Expected: both scripts exit 0.

- [ ] **Step 5: Expand and run API synchronization**

The sync list must include all finalized public plugin APIs and at least these 23 snapshots: the existing 13 plus `PluginCapabilityLevel.java`, `PluginHookPoint.java`, `PluginPatchDeclaration.java`, `PluginPermissionTier.java`, `runtime/JavaRuntimeProvider.java`, `runtime/PluginAbi.java`, `runtime/PluginPlatformTarget.java`, `runtime/PluginRuntimeTypes.java`, `runtime/RuntimeProvider.java`, and `runtime/RuntimeProviderRegistry.java`.

```powershell
.\tools\sync-api-references.ps1 -HmclRepository C:\Users\ACX\Documents\HMCL-CE
git diff --check
```

Expected: snapshot files exactly mirror HMCL `next`, and `git diff --check` reports no whitespace errors.

- [ ] **Step 6: Commit validator and API surface**

```powershell
git add tools/validate-npl.ps1 tools/test-validate-npl.ps1 tools/sync-api-references.ps1 references
git commit -m "Add schema v5 SDK contract validation"
```

### Task 8: Migrate SDK Examples, Templates, Documentation, And Dist Packages

**Files:**
- Modify: `C:/Users/ACX/Documents/Plugins/HMCL-CE-Plugin-SDK/examples/java-helloworld/plugin.json`
- Modify: `C:/Users/ACX/Documents/Plugins/HMCL-CE-Plugin-SDK/examples/kotlin-helloworld/plugin.json`
- Modify: `C:/Users/ACX/Documents/Plugins/HMCL-CE-Plugin-SDK/examples/java-mixin/plugin.json`
- Modify: `C:/Users/ACX/Documents/Plugins/HMCL-CE-Plugin-SDK/examples/offline-unlocker/plugin.json`
- Modify: `C:/Users/ACX/Documents/Plugins/HMCL-CE-Plugin-SDK/store/manifest.template.json`
- Modify: `C:/Users/ACX/Documents/Plugins/HMCL-CE-Plugin-SDK/README.md`
- Modify: `C:/Users/ACX/Documents/Plugins/HMCL-CE-Plugin-SDK/API_CHEATSHEET.md`
- Modify: `C:/Users/ACX/Documents/Plugins/HMCL-CE-Plugin-SDK/CHANGELOG.md`
- Modify: `C:/Users/ACX/Documents/Plugins/HMCL-CE-Plugin-SDK/docs/PLUGIN_QUICKSTART.md`
- Modify: `C:/Users/ACX/Documents/Plugins/HMCL-CE-Plugin-SDK/docs/PLUGIN_DEVELOPMENT.md`
- Modify: `C:/Users/ACX/Documents/Plugins/HMCL-CE-Plugin-SDK/docs/PLUGIN_STORE_SETUP.md`
- Review/modify: `C:/Users/ACX/Documents/Plugins/HMCL-CE-Plugin-SDK/docs/CSHARP_NATIVE_PAGES.md`
- Modify: `C:/Users/ACX/Documents/Plugins/HMCL-CE-Plugin-SDK/dist/TESTING.md`
- Rebuild: `C:/Users/ACX/Documents/Plugins/HMCL-CE-Plugin-SDK/dist/*.npl`
- Modify: `C:/Users/ACX/Documents/Plugins/HMCL-CE-Plugin-SDK/dist/SHA256SUMS.txt`

- [ ] **Step 1: Convert all tracked examples and the Store template**

Every example uses `"schemaVersion": 5`, `"runtime": "java"`, and `"abi": 2`; preserve existing permissions and Mixin declarations. Set `store/manifest.template.json` `pluginApiVersion` to 5. Do not change downstream repository URLs containing `/main/` or dynamic default-branch publishing behavior.

- [ ] **Step 2: Validate source manifests before packaging**

Build temporary NPLs from all four examples and run the schema-v5 validator. Expected: all four packages validate, including existing Mixin permission requirements.

```powershell
$env:HMCL_JAR = (Get-ChildItem C:\Users\ACX\Documents\HMCL-CE\HMCL\build\libs\HMCL-*.jar | Sort-Object LastWriteTime -Descending | Select-Object -First 1).FullName
$gradlew = 'C:\Users\ACX\Documents\HMCL-CE\gradlew.bat'
& $gradlew -p .\examples\java-helloworld clean packageNpl
& $gradlew -p .\examples\kotlin-helloworld clean packageNpl
& $gradlew -p .\examples\java-mixin clean packageNpl
& $gradlew -p .\examples\offline-unlocker clean packageNpl
Get-ChildItem .\examples -Recurse -Filter *.npl | ForEach-Object { .\tools\validate-npl.ps1 -Package $_.FullName }
```

- [ ] **Step 3: Document the exact prerelease boundary**

Update the listed docs to state: schema v5 targets HMCL CE `next`; v4 remains accepted by `next`; new examples produce v5; external runtimes remain unavailable until a provider is installed; Hook/Patch declarations are contract-only in this milestone. Remove any statement implying C#, QuickJS, Python, native providers, Hook dispatch, or Patch execution already work.

- [ ] **Step 4: Rebuild tracked dist archives and checksums**

Replace only the three already tracked Java/Kotlin/Mixin archives from their `build/npl` outputs. Do not add offline-unlocker to `dist`. Recompute lowercase SHA-256 lines sorted by file name with two spaces between hash and name.

```powershell
Get-ChildItem .\dist -Filter *.npl | Sort-Object Name | ForEach-Object {
    $hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $_.FullName).Hash.ToLowerInvariant()
    "$hash  $($_.Name)"
}
```

- [ ] **Step 5: Verify package contents and publishing tools**

```powershell
.\tools\test-validate-npl.ps1
.\tools\test-publishing-tools.ps1
Get-ChildItem .\dist -Filter *.npl | ForEach-Object { .\tools\validate-npl.ps1 -Package $_.FullName }
Get-ChildItem .\dist -Filter *.npl | ForEach-Object {
    $manifest = tar -xOf $_.FullName plugin.json | ConvertFrom-Json
    if ($manifest.schemaVersion -ne 5 -or $manifest.runtime -ne 'java' -or $manifest.abi -ne 2) {
        throw "Unexpected manifest contract in $($_.Name)"
    }
}
git diff --check
```

Expected: every command exits 0; all three tracked archives contain schema 5/Java/ABI 2 manifests.

- [ ] **Step 6: Commit examples/docs and generated distribution separately**

```powershell
git add examples store/manifest.template.json README.md API_CHEATSHEET.md CHANGELOG.md docs dist/TESTING.md
git commit -m "Document and demonstrate schema v5 plugins"
git add dist/*.npl dist/SHA256SUMS.txt
git commit -m "Rebuild schema v5 SDK example packages"
```

### Task 9: Full Verification, Independent Review, And Publication

**Files:**
- Verification only unless a failing test exposes a defect; any defect receives its own RED/GREEN fix commit.

- [ ] **Step 1: Build HMCL with the supported JDK 17 runtime**

Set `JAVA_HOME` to a Zulu/Temurin JDK 17 installation and prepend its `bin` for this PowerShell process. Confirm `java -version` reports 17, then run:

```powershell
.\gradlew.bat :HMCL:test --no-daemon --stacktrace
.\gradlew.bat test --no-daemon --parallel --stacktrace
.\gradlew.bat checkstyle checkTranslations --no-daemon --parallel --stacktrace
```

Expected: all commands BUILD SUCCESSFUL. JavaFX-conditional tests must also be observed in the Linux `xvfb-run` CI job after push.

- [ ] **Step 2: Re-run the focused HMCL contract matrix**

```powershell
.\gradlew.bat :HMCL:test --tests "org.jackhuang.hmcl.plugin.PluginManifestTest" --tests "org.jackhuang.hmcl.plugin.NextPluginRuntimeTest" --tests "org.jackhuang.hmcl.plugin.runtime.PluginCompatibilityEvaluatorTest" --tests "org.jackhuang.hmcl.plugin.PluginManagerLocalInstallTest" --tests "org.jackhuang.hmcl.plugin.PluginManagerLifecycleStateTest" --tests "org.jackhuang.hmcl.plugin.mixin.bootstrap.HmclMixinBootstrapPermissionTest" --tests "org.jackhuang.hmcl.plugin.store.PluginStoreManifestTest" --tests "org.jackhuang.hmcl.plugin.store.PluginStoreManagerTest" --no-daemon --stacktrace
```

Expected: BUILD SUCCESSFUL with no unexpected skips outside JavaFX environment guards.

- [ ] **Step 3: Re-run the complete SDK matrix**

```powershell
Set-Location C:\Users\ACX\Documents\Plugins\HMCL-CE-Plugin-SDK
.\tools\test-validate-npl.ps1
.\tools\test-publishing-tools.ps1
Get-ChildItem .\examples -Recurse -Filter *.npl | ForEach-Object { .\tools\validate-npl.ps1 -Package $_.FullName }
Get-ChildItem .\dist -Filter *.npl | ForEach-Object { .\tools\validate-npl.ps1 -Package $_.FullName }
git diff --check schema-v4...schema-v5
git merge-base --is-ancestor schema-v4 schema-v5
```

Expected: every script exits 0, diff check is clean, and `schema-v5` descends from `schema-v4`.

- [ ] **Step 4: Perform spec and quality review**

Review every requirement in `docs/superpowers/specs/2026-08-23-next-plugin-contract-foundation-design.md` against the actual diffs. Then run an independent code review over `origin/next..next` and `schema-v4...schema-v5`, fixing all Critical and Important findings through new RED/GREEN commits and re-running the affected matrices.

- [ ] **Step 5: Verify both repositories are ready to publish**

```powershell
Set-Location C:\Users\ACX\Documents\HMCL-CE
git status --short --branch
git log --oneline origin/next..next
Set-Location C:\Users\ACX\Documents\Plugins\HMCL-CE-Plugin-SDK
git status --short --branch
git log --oneline schema-v4..schema-v5
git push --dry-run --set-upstream origin schema-v5
```

Expected: both working trees are clean; HMCL only has approved `next` commits; SDK only has approved `schema-v5` commits; dry-run succeeds.

- [ ] **Step 6: Publish the approved branches**

```powershell
Set-Location C:\Users\ACX\Documents\HMCL-CE
git push origin next
Set-Location C:\Users\ACX\Documents\Plugins\HMCL-CE-Plugin-SDK
git push --set-upstream origin schema-v5
git fetch origin --prune
git rev-parse schema-v5
git rev-parse origin/schema-v5
git ls-remote --heads origin main schema-v4 schema-v5
gh api repos/HMCL-Community/HMCL-CE-Plugin-SDK --jq .default_branch
```

Expected: HMCL `origin/next` matches local `next`; SDK local and remote `schema-v5` match; remote `main` is absent; `schema-v4` and `schema-v5` exist; default remains `schema-v4`.

- [ ] **Step 7: Observe CI before declaring completion**

```powershell
Set-Location C:\Users\ACX\Documents\HMCL-CE
gh run list --branch next --limit 10
Set-Location C:\Users\ACX\Documents\Plugins\HMCL-CE-Plugin-SDK
gh run list --branch schema-v5 --limit 10
```

Expected: all workflows triggered by these pushes finish successfully, including Zulu JDK 17 + JavaFX + `xvfb-run` HMCL tests. A CI defect must be reproduced with a failing local test when possible, fixed in a new commit, pushed, and observed again.
