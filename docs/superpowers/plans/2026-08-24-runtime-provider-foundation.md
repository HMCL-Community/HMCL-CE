# HMCL CE Next Runtime Provider Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the schema-v5 Runtime Provider foundation, capability-aware dependency installation, shared Bridge/permission primitives, and startup Protector while preserving executable schema-v4 plugins.

**Architecture:** Runtime requirements remain separate virtual edges derived from schema-v5 manifests and are resolved by the existing Store planner. Optional Provider plugins use Java bootstraps to register descriptors and loaders; the built-in Java provider remains immutable. A Next-owned Protector supervises the post-Mixin launcher JVM and applies persistent third-party quarantine before plugin code can load.

**Tech Stack:** Java 17, JavaFX 17, Gson, JUnit 5, Gradle 9, local named pipes/Unix-domain sockets, PowerShell 5+, Git.

---

## Dependency On The Approved Design

Implement against `docs/superpowers/specs/2026-08-24-runtime-provider-platform-design.md`. This is
Stage 1 only. It establishes contracts and lifecycle ownership; the executable Rust engine, JVM
bytecode Patch engine, generated foreign-language SDKs, and six-platform native CI belong to Stages
2 and 3.

## File And Ownership Map

### Manifest and compatibility model

- `HMCL/src/main/java/org/jackhuang/hmcl/plugin/PluginManifest.java`: schema-v5 language and Provider fields.
- `HMCL/src/main/java/org/jackhuang/hmcl/plugin/PluginKind.java`: normal/Runtime-Provider package role.
- `HMCL/src/main/java/org/jackhuang/hmcl/plugin/runtime/PluginExecutionMode.java`: embedded/isolated enum.
- `HMCL/src/main/java/org/jackhuang/hmcl/plugin/runtime/RuntimeFeature.java`: Provider feature vocabulary.
- `HMCL/src/main/java/org/jackhuang/hmcl/plugin/runtime/RuntimeProviderDeclaration.java`: one `providesRuntimes` entry.
- `HMCL/src/main/java/org/jackhuang/hmcl/plugin/runtime/RuntimeRequirement.java`: immutable virtual dependency requirement.
- `HMCL/src/main/java/org/jackhuang/hmcl/plugin/runtime/PluginCompatibilityRequirements.java`: extended executable requirements.

### Provider selection and Store planning

- `HMCL/src/main/java/org/jackhuang/hmcl/plugin/runtime/RuntimeProviderDescriptor.java`: registered Provider identity and capabilities.
- `HMCL/src/main/java/org/jackhuang/hmcl/plugin/runtime/RuntimeProviderRegistry.java`: multi-provider registry and plugin bindings.
- `HMCL/src/main/java/org/jackhuang/hmcl/plugin/runtime/RuntimeProviderSelector.java`: deterministic installed Provider selection.
- `HMCL/src/main/java/org/jackhuang/hmcl/plugin/store/PluginStoreArtifact.java`: one platform-specific download.
- `HMCL/src/main/java/org/jackhuang/hmcl/plugin/store/PluginStoreManifest.java`: artifact matrix and Provider metadata.
- `HMCL/src/main/java/org/jackhuang/hmcl/plugin/store/PluginStoreDependencyResolver.java`: virtual requirement resolution.
- `HMCL/src/main/java/org/jackhuang/hmcl/plugin/store/PluginInstallPlan.java`: Provider binding and artifact details.

### Lifecycle, Bridge, and permission authority

- `HMCL/src/main/java/org/jackhuang/hmcl/plugin/runtime/RuntimeProvider.java`: lifecycle-capable Provider SPI.
- `HMCL/src/main/java/org/jackhuang/hmcl/plugin/runtime/RuntimeProviderRegistration.java`: plugin-owned registration handle.
- `HMCL/src/main/java/org/jackhuang/hmcl/plugin/runtime/RuntimePayloadContext.java`: immutable payload identity, path, mode, and token source.
- `HMCL/src/main/java/org/jackhuang/hmcl/plugin/runtime/RuntimePayloadHandle.java`: Provider-owned loaded payload handle.
- `HMCL/src/main/java/org/jackhuang/hmcl/plugin/runtime/RuntimeSupervisor.java`: Provider state machine and reverse shutdown.
- `HMCL/src/main/java/org/jackhuang/hmcl/plugin/loader/RuntimePluginLoader.java`: delegates payload operations to a selected Provider.
- `HMCL/src/main/java/org/jackhuang/hmcl/plugin/bridge/BridgeValue.java`: language-neutral tagged value.
- `HMCL/src/main/java/org/jackhuang/hmcl/plugin/bridge/BridgeHandleRegistry.java`: owner-scoped object handles.
- `HMCL/src/main/java/org/jackhuang/hmcl/plugin/bridge/PluginCapabilityToken.java`: unforgeable invocation authority.
- `HMCL/src/main/java/org/jackhuang/hmcl/plugin/bridge/PluginPermissionAuthority.java`: token issuance and verification.
- `HMCL/src/main/java/org/jackhuang/hmcl/plugin/PluginContext.java`: Provider registration and bridge access available to Host plugins.

### Protector and recovery

- `HMCL/src/main/java/org/jackhuang/hmcl/plugin/protector/ProtectorProtocol.java`: authenticated stage/heartbeat messages.
- `HMCL/src/main/java/org/jackhuang/hmcl/plugin/protector/ProtectorBootstrap.java`: parent/child role selection and supervision.
- `HMCL/src/main/java/org/jackhuang/hmcl/plugin/protector/PluginRecoveryRecord.java`: bounded recovery JSON model.
- `HMCL/src/main/java/org/jackhuang/hmcl/plugin/protector/PluginRecoveryStore.java`: atomic record persistence.
- `HMCL/src/main/java/org/jackhuang/hmcl/plugin/protector/StartupReporter.java`: child heartbeat and stage API.
- `HMCL/src/main/java/org/jackhuang/hmcl/EntryPoint.java`: invoke Protector after Mixin relaunch.
- `HMCL/src/main/java/org/jackhuang/hmcl/Launcher.java`: report Core/UI stages.
- `HMCL/src/main/java/org/jackhuang/hmcl/plugin/PluginStateStore.java`: persistent quarantine set.
- `HMCL/src/main/java/org/jackhuang/hmcl/plugin/PluginManager.java`: quarantine before discovery and lifecycle stage reporting.
- `HMCL/src/main/java/org/jackhuang/hmcl/ui/main/PluginRecoveryPage.java`: report and dependency-safe restore controls.

### SDK schema-v5 parity

- `C:/Users/ACX/Documents/Plugins/HMCL-CE-Plugin-SDK/tools/validate-npl.ps1`: new schema and Store validation.
- `C:/Users/ACX/Documents/Plugins/HMCL-CE-Plugin-SDK/tools/test-validate-npl.ps1`: parity fixtures.
- `C:/Users/ACX/Documents/Plugins/HMCL-CE-Plugin-SDK/references/hmcl-plugin-api/`: synchronized public contract snapshots.
- `C:/Users/ACX/Documents/Plugins/HMCL-CE-Plugin-SDK/store/manifest.template.json`: Provider/artifact example.

## Style And Scope Locks

- Every new Java source file and every newly declared nested type uses `@NotNullByDefault`.
- Every nullable Java position is `@Nullable`; immutable collections and arrays use
  `@Unmodifiable`, `@UnmodifiableView`, or type-use array syntax.
- Every class, field, method, constructor, and enum constant written or modified has accurate `///`
  documentation. Convert old comments in any touched method rather than adding mixed styles.
- Keep `PluginDependency` serialization unchanged. Runtime requirements are separate graph edges.
- Keep `java` immutable and built in. Do not register a fake Rust Provider in production.
- Provider packages are ordinary Java plugins with `runtime: java`; language payload packages name
  their external runtime.
- Protector starts only after `HmclMixinBootstrap.relaunchIfNeeded(...)` returns false, so the
  existing Agent relaunch is never treated as a plugin crash.
- Each RED command must fail for the asserted missing behavior, not compilation setup.
- Commit HMCL and SDK changes separately because they are different repositories.

### Task 1: Add The Schema-V5 Runtime Vocabulary

**Files:**
- Create: `HMCL/src/main/java/org/jackhuang/hmcl/plugin/runtime/PluginExecutionMode.java`
- Create: `HMCL/src/main/java/org/jackhuang/hmcl/plugin/runtime/RuntimeFeature.java`
- Create: `HMCL/src/main/java/org/jackhuang/hmcl/plugin/runtime/RuntimeProviderDeclaration.java`
- Create: `HMCL/src/main/java/org/jackhuang/hmcl/plugin/runtime/RuntimeRequirement.java`
- Create: `HMCL/src/main/java/org/jackhuang/hmcl/plugin/PluginKind.java`
- Modify: `HMCL/src/main/java/org/jackhuang/hmcl/plugin/runtime/PluginRuntimeTypes.java`
- Modify: `HMCL/src/main/java/org/jackhuang/hmcl/plugin/PluginManifest.java`
- Test: `HMCL/src/test/java/org/jackhuang/hmcl/plugin/PluginManifestTest.java`

- [ ] **Step 1: Write failing manifest tests**

Add tests proving `rust` and `wasm` are canonical, `executionMode` defaults to embedded, isolated plus
`jvm-raw` is rejected, a Provider must use `runtime: java`, duplicate `providesRuntimes` entries are
rejected, and equality/hash include all new fields:

```java
@Test
public void runtimeProviderManifestProducesVirtualCapabilities() throws Exception {
    PluginManifest manifest = PluginManifest.fromJson(new StringReader("""
            {
              "schemaVersion": 5,
              "id": "dev.hmclce.runtime.rust",
              "name": "Rust Runtime Host",
              "version": "1.0.0-next",
              "type": "java",
              "entrypoint": "dev.hmclce.runtime.rust.RustRuntimeHostPlugin",
              "launcherVersion": "*",
              "permissions": ["native-code"],
              "requiredPermissions": ["native-code"],
              "runtime": "java",
              "abi": 2,
              "platforms": [],
              "pluginKind": "runtime-provider",
              "providesRuntimes": [{
                "runtime": "rust",
                "abis": [2],
                "bridgeAbi": 1,
                "executionModes": ["embedded", "isolated"],
                "features": ["bridge", "hooks", "patches", "raw-jvm", "native"]
              }]
            }
            """));

    assertEquals(PluginKind.RUNTIME_PROVIDER, manifest.getPluginKind());
    assertEquals("rust", manifest.getProvidedRuntimes().get(0).runtime());
    assertEquals(Set.of(PluginExecutionMode.EMBEDDED, PluginExecutionMode.ISOLATED),
            manifest.getProvidedRuntimes().get(0).executionModes());
}
```

- [ ] **Step 2: Run the focused RED test**

Run:

```powershell
$env:JAVA_HOME='C:\Users\ACX\AppData\Local\Temp\codex-hmcl-jdk17-20260824\jdk-17.0.20+8'
.\gradlew.bat :HMCL:test --tests org.jackhuang.hmcl.plugin.PluginManifestTest
```

Expected: FAIL because the new fields and types do not exist.

- [ ] **Step 3: Implement immutable schema types and validation**

Use these public shapes:

```java
@NotNullByDefault
public enum PluginExecutionMode { EMBEDDED, ISOLATED }

@NotNullByDefault
public enum RuntimeFeature { BRIDGE, HOOKS, PATCHES, RAW_JVM, NATIVE }

@NotNullByDefault
public record RuntimeProviderDeclaration(
        String runtime,
        @Unmodifiable Set<Integer> abis,
        int bridgeAbi,
        @Unmodifiable Set<PluginExecutionMode> executionModes,
        @Unmodifiable Set<RuntimeFeature> features) {
    public RuntimeProviderDeclaration {
        runtime = PluginRuntimeTypes.requireValid(runtime);
        abis = Set.copyOf(abis);
        executionModes = Set.copyOf(executionModes);
        features = Set.copyOf(features);
        if (abis.isEmpty() || bridgeAbi != 1 || executionModes.isEmpty()
                || !features.contains(RuntimeFeature.BRIDGE)) {
            throw new IllegalArgumentException("Invalid runtime provider declaration for " + runtime);
        }
    }
}

@NotNullByDefault
public record RuntimeRequirement(
        String runtime,
        int pluginAbi,
        int bridgeAbi,
        PluginExecutionMode executionMode,
        @Unmodifiable Set<RuntimeFeature> features,
        @Nullable String pinnedProviderId) {
    public RuntimeRequirement {
        runtime = PluginRuntimeTypes.requireValid(runtime);
        PluginAbi.requireValid(pluginAbi);
        features = Set.copyOf(features);
        if (executionMode == PluginExecutionMode.ISOLATED && features.contains(RuntimeFeature.RAW_JVM)) {
            throw new IllegalArgumentException("raw-jvm requires embedded execution");
        }
    }
}
```

Add `PluginKind.NORMAL` and `PluginKind.RUNTIME_PROVIDER`, manifest fields `pluginKind`,
`executionMode`, `runtimeProvider`, and `providesRuntimes`, their declared flags, getters, equality,
hashing, and schema checks. Derive `RuntimeRequirement` from runtime, ABI, Hook/Patch declarations,
permissions, and the optional pin. Add `RUST` and `WASM` constants to `PluginRuntimeTypes`.

- [ ] **Step 4: Run tests and Checkstyle**

Run the focused test plus:

```powershell
.\gradlew.bat :HMCL:checkstyleMain :HMCL:checkstyleTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add HMCL/src/main/java/org/jackhuang/hmcl/plugin/PluginManifest.java HMCL/src/main/java/org/jackhuang/hmcl/plugin/runtime HMCL/src/test/java/org/jackhuang/hmcl/plugin/PluginManifestTest.java
git commit -m "Define schema v5 runtime providers"
```

### Task 2: Add Platform Artifact Matrices To Store Metadata

**Files:**
- Create: `HMCL/src/main/java/org/jackhuang/hmcl/plugin/store/PluginStoreArtifact.java`
- Modify: `HMCL/src/main/java/org/jackhuang/hmcl/plugin/store/PluginStoreManifest.java`
- Modify: `HMCL/src/main/java/org/jackhuang/hmcl/plugin/store/PluginStoreManager.java`
- Test: `HMCL/src/test/java/org/jackhuang/hmcl/plugin/store/PluginStoreManifestTest.java`
- Test: `HMCL/src/test/java/org/jackhuang/hmcl/plugin/store/PluginStoreManagerTest.java`

- [ ] **Step 1: Write artifact-selection tests**

Create fixtures with `windows-x64`, `windows-arm64`, and `linux-x64` artifacts and assert exact
selection, package URL/hash/size ownership, duplicate-target rejection, and no-match diagnostics:

```java
@Test
public void selectsOneExactPlatformArtifact() throws Exception {
    PluginStoreManifest.PluginVersionEntry version = parseVersionWithArtifacts(
            artifact("windows-x64", "https://example.test/win-x64.npl", "a".repeat(64), 41),
            artifact("linux-x64", "https://example.test/linux-x64.npl", "b".repeat(64), 73));

    PluginStoreArtifact selected = version.requireArtifact(PluginPlatformTarget.parse("linux-x64"));

    assertEquals("https://example.test/linux-x64.npl", selected.packageUrl());
    assertEquals(73, selected.size());
}
```

- [ ] **Step 2: Run RED tests**

Run `:HMCL:test` for `PluginStoreManifestTest` and `PluginStoreManagerTest`.
Expected: FAIL because `artifacts` is not parsed or selected.

- [ ] **Step 3: Implement `PluginStoreArtifact` and backward-compatible parsing**

Use one immutable entry per target:

```java
@NotNullByDefault
public record PluginStoreArtifact(
        PluginPlatformTarget platform,
        String packageUrl,
        String sha256,
        long size) {
    public PluginStoreArtifact {
        if (!packageUrl.startsWith("https://") || !sha256.matches("[0-9a-f]{64}") || size <= 0) {
            throw new IllegalArgumentException("Invalid Store artifact for " + platform.getId());
        }
    }
}
```

For schema-v5 entries, accept either the existing single package fields or non-empty `artifacts`,
never both. The artifact matrix is mandatory for `runtime-provider` entries. Select exact OS/arch;
do not fall back from an architecture-specific host to OS-only native bytes. Make downloads consume
the selected artifact rather than the version-level URL/hash/size.

- [ ] **Step 4: Run focused tests and Checkstyle**

Expected: PASS with old single-artifact Store fixtures unchanged.

- [ ] **Step 5: Commit**

```powershell
git add HMCL/src/main/java/org/jackhuang/hmcl/plugin/store HMCL/src/test/java/org/jackhuang/hmcl/plugin/store
git commit -m "Select platform plugin artifacts"
```

### Task 3: Support Multiple Registered Providers And Deterministic Bindings

**Files:**
- Create: `HMCL/src/main/java/org/jackhuang/hmcl/plugin/runtime/RuntimeProviderDescriptor.java`
- Create: `HMCL/src/main/java/org/jackhuang/hmcl/plugin/runtime/RuntimeProviderBinding.java`
- Create: `HMCL/src/main/java/org/jackhuang/hmcl/plugin/runtime/RuntimeProviderSelector.java`
- Create: `HMCL/src/main/java/org/jackhuang/hmcl/plugin/runtime/RuntimePayloadContext.java`
- Create: `HMCL/src/main/java/org/jackhuang/hmcl/plugin/runtime/RuntimePayloadHandle.java`
- Modify: `HMCL/src/main/java/org/jackhuang/hmcl/plugin/runtime/RuntimeProvider.java`
- Modify: `HMCL/src/main/java/org/jackhuang/hmcl/plugin/runtime/JavaRuntimeProvider.java`
- Modify: `HMCL/src/main/java/org/jackhuang/hmcl/plugin/runtime/RuntimeProviderRegistry.java`
- Modify: `HMCL/src/main/java/org/jackhuang/hmcl/plugin/runtime/PluginCompatibilityRequirements.java`
- Modify: `HMCL/src/main/java/org/jackhuang/hmcl/plugin/runtime/PluginCompatibilityEvaluator.java`
- Test: `HMCL/src/test/java/org/jackhuang/hmcl/plugin/runtime/RuntimeProviderRegistryTest.java`
- Test: `HMCL/src/test/java/org/jackhuang/hmcl/plugin/runtime/PluginCompatibilityEvaluatorTest.java`

- [ ] **Step 1: Write registry, selector, and compatibility tests**

Cover two Rust Providers, explicit pinning, installed-enabled preference, feature/mode rejection,
per-dependent bindings, unregister refusal while bound, and immutable Java registration:

```java
@Test
public void bindsEachDependentToItsSelectedProvider() {
    registry.register(provider("dev.host.rust.a", "rust", "1.0.0", Set.of(PluginExecutionMode.EMBEDDED)));
    registry.register(provider("dev.host.rust.b", "rust", "2.0.0", Set.of(PluginExecutionMode.EMBEDDED)));

    RuntimeProviderBinding binding = registry.bind("dev.plugin.demo",
            requirement("rust", "dev.host.rust.a"));

    assertEquals("dev.host.rust.a", binding.providerId());
    assertThrows(IllegalStateException.class, () -> registry.unregister("dev.host.rust.a"));
}
```

- [ ] **Step 2: Run RED tests**

Expected: FAIL because the current registry is keyed only by runtime type.

- [ ] **Step 3: Implement descriptor-based registration and binding**

`RuntimeProvider` exposes `descriptor()`, `initialize()`, `healthCheck()`,
`loadPayload(RuntimePayloadContext)`, `enablePayload(RuntimePayloadHandle)`,
`disablePayload(RuntimePayloadHandle)`, `unloadPayload(RuntimePayloadHandle)`, and `close()`.
The Java provider returns a reserved descriptor and delegates Java packages to the existing loader.
`RuntimeProviderRegistry` indexes descriptors by provider ID,
maintains immutable runtime candidate snapshots, and stores bindings by dependent plugin ID.

`RuntimeProviderSelector` accepts only compatible descriptors and orders them by enabled installed,
disabled installed, source priority, descending `PluginVersion`, then provider ID. Pins bypass
ranking and fail closed. `PluginCompatibilityEvaluator` reports missing Provider, unsupported ABI,
mode, bridge ABI, or feature as distinct statuses.

- [ ] **Step 4: Run focused tests and Checkstyle**

Expected: PASS; existing Java ABI tests remain green.

- [ ] **Step 5: Commit**

```powershell
git add HMCL/src/main/java/org/jackhuang/hmcl/plugin/runtime HMCL/src/test/java/org/jackhuang/hmcl/plugin/runtime
git commit -m "Bind plugins to runtime providers"
```

### Task 4: Resolve Virtual Runtime Dependencies In Store Plans

**Files:**
- Modify: `HMCL/src/main/java/org/jackhuang/hmcl/plugin/store/PluginStoreDependencyResolver.java`
- Modify: `HMCL/src/main/java/org/jackhuang/hmcl/plugin/store/PluginInstallPlan.java`
- Modify: `HMCL/src/main/java/org/jackhuang/hmcl/plugin/store/PluginStoreAggregator.java`
- Modify: `HMCL/src/main/java/org/jackhuang/hmcl/plugin/PluginDependencyPlanner.java`
- Modify: `HMCL/src/main/java/org/jackhuang/hmcl/plugin/PluginManager.java`
- Modify: `HMCL/src/main/java/org/jackhuang/hmcl/ui/main/PluginStorePage.java`
- Modify: `HMCL/src/main/java/org/jackhuang/hmcl/ui/main/PluginDialogs.java`
- Test: `HMCL/src/test/java/org/jackhuang/hmcl/plugin/store/PluginStoreDependencyResolverTest.java`
- Test: `HMCL/src/test/java/org/jackhuang/hmcl/plugin/PluginManagerLocalInstallTest.java`
- Test: `HMCL/src/test/java/org/jackhuang/hmcl/ui/main/PluginInstallPlanPresentationTest.java`

- [ ] **Step 1: Write failing end-to-end planning tests**

Assert that installing one Rust plugin adds one compatible Rust Host before it, two plugins reuse the
same Host, a custom-source-only Host requires explicit source confirmation, a pin never falls back,
and disabling/removing a bound Host reports all dependent IDs.

```java
@Test
public void addsOneSharedProviderBeforeLanguageDependents() throws Exception {
    PluginInstallPlan plan = resolver.resolve("dev.example.rust-tool", installed(), enabled(), grants());

    assertEquals(List.of("dev.hmclce.runtime.rust", "dev.example.rust-tool"),
            plan.getEntries().stream().map(PluginInstallPlan.Entry::getPluginId).toList());
    assertEquals("dev.hmclce.runtime.rust",
            plan.getRuntimeBindings().get("dev.example.rust-tool").providerId());
}
```

- [ ] **Step 2: Run RED tests**

Expected: FAIL with missing runtime instead of a Provider install entry.

- [ ] **Step 3: Extend the existing resolver, plan, and reverse-dependency checks**

Derive a `RuntimeRequirement` for every schema-v5 non-Java candidate. Resolve concrete dependencies
first, then its virtual Provider edge, then the language package. Put the chosen platform artifact,
source provenance, download size, permissions, mode, and binding in `PluginInstallPlan`. Feed Provider
edges into cycle and reverse-dependency traversal without serializing them as `PluginDependency`.

Require an explicit boolean/custom-source receipt when the selected Provider is not from an official
or trusted source. Preserve existing transaction and journal application; Provider and dependent
entries publish atomically. Extend the existing install dialog model to show every plan entry in
dependency order, mark the Runtime Host, display source/provenance, chosen platform target,
individual and total bytes, execution mode, and required/optional permission tiers. The confirm
button remains disabled until custom-source and dangerous-permission acknowledgements are complete.

- [ ] **Step 4: Run Store, manager, transaction, and journal tests**

Run:

```powershell
.\gradlew.bat :HMCL:test --tests 'org.jackhuang.hmcl.plugin.store.*' --tests org.jackhuang.hmcl.plugin.PluginManagerLocalInstallTest --tests org.jackhuang.hmcl.plugin.PluginBatchTransactionJournalTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add HMCL/src/main/java/org/jackhuang/hmcl/plugin HMCL/src/test/java/org/jackhuang/hmcl/plugin
git commit -m "Install runtime provider dependencies"
```

### Task 5: Own Provider Lifecycle And External Payload Delegation

**Files:**
- Create: `HMCL/src/main/java/org/jackhuang/hmcl/plugin/runtime/RuntimeProviderState.java`
- Create: `HMCL/src/main/java/org/jackhuang/hmcl/plugin/runtime/RuntimeProviderRegistration.java`
- Create: `HMCL/src/main/java/org/jackhuang/hmcl/plugin/runtime/RuntimeSupervisor.java`
- Create: `HMCL/src/main/java/org/jackhuang/hmcl/plugin/loader/RuntimePluginLoader.java`
- Modify: `HMCL/src/main/java/org/jackhuang/hmcl/plugin/PluginContext.java`
- Modify: `HMCL/src/main/java/org/jackhuang/hmcl/plugin/PluginContainer.java`
- Modify: `HMCL/src/main/java/org/jackhuang/hmcl/plugin/PluginManager.java`
- Test: `HMCL/src/test/java/org/jackhuang/hmcl/plugin/runtime/RuntimeSupervisorTest.java`
- Test: `HMCL/src/test/java/org/jackhuang/hmcl/plugin/NextPluginRuntimeTest.java`

- [ ] **Step 1: Write lifecycle and loader tests**

Use a recording fake Provider to assert the exact state sequence, load only after `HEALTHY`, reverse
dependent shutdown, registration ownership, duplicate rejection, health failure rollback, and Host
update restoration.

- [ ] **Step 2: Run RED tests**

Expected: FAIL because external providers cannot own lifecycle or payload loading.

- [ ] **Step 3: Implement the state machine and registration handle**

Use the exact states `DISCOVERED`, `RESOLVED`, `BOOTSTRAP_LOADED`, `REGISTERED`, `NEGOTIATED`,
`INITIALIZED`, `HEALTHY`, `READY`, `STOPPING`, `STOPPED`, and `FAILED`. The Host calls:

```java
RuntimeProviderRegistration registration = context.registerRuntimeProvider(provider);
```

The registration is bound to the Host container and is automatically closed on Host unload.
`RuntimePayloadContext` contains the exact artifact identity, normalized selected entrypoint,
execution mode, data directory, and capability-token supplier. `RuntimePayloadHandle` is an opaque
owner/provider/payload-ID tuple and contains no engine object.
`RuntimePluginLoader` asks the registry for the dependent binding and delegates load/enable/disable/
unload to that Provider. `PluginManager` loads Provider plugins before virtual dependents and refuses
language payload loading unless the Supervisor state is `READY`.

- [ ] **Step 4: Run lifecycle tests and Checkstyle**

Expected: PASS with no production Rust registration.

- [ ] **Step 5: Commit**

```powershell
git add HMCL/src/main/java/org/jackhuang/hmcl/plugin HMCL/src/test/java/org/jackhuang/hmcl/plugin
git commit -m "Supervise runtime provider lifecycle"
```

### Task 6: Add The Language-Neutral Bridge And Handle Registry

**Files:**
- Create: `HMCL/src/main/java/org/jackhuang/hmcl/plugin/bridge/BridgeValue.java`
- Create: `HMCL/src/main/java/org/jackhuang/hmcl/plugin/bridge/BridgeError.java`
- Create: `HMCL/src/main/java/org/jackhuang/hmcl/plugin/bridge/BridgeHandle.java`
- Create: `HMCL/src/main/java/org/jackhuang/hmcl/plugin/bridge/BridgeHandleRegistry.java`
- Create: `HMCL/src/main/java/org/jackhuang/hmcl/plugin/bridge/BridgeDispatcher.java`
- Test: `HMCL/src/test/java/org/jackhuang/hmcl/plugin/bridge/BridgeValueTest.java`
- Test: `HMCL/src/test/java/org/jackhuang/hmcl/plugin/bridge/BridgeHandleRegistryTest.java`

- [ ] **Step 1: Write value and ownership tests**

Cover null/scalars/bytes/immutable arrays/maps, invalid nesting, owner and generation checks, stale
handles, cross-plugin lookup, callback cancellation, exception redaction, and complete owner revoke.

- [ ] **Step 2: Run RED tests**

Expected: FAIL because the bridge package does not exist.

- [ ] **Step 3: Implement closed tagged values and generation-safe handles**

Use a sealed `BridgeValue` hierarchy with factory methods and immutable defensive copies. Represent
handles as `(long id, long generation, String type)` and keep owner IDs only inside the registry.
`resolve(token, handle, expectedType)` verifies owner, generation, type, and token before returning an
object. `revokeOwner(pluginId)` invalidates all handles and increments generations before references
are released.

- [ ] **Step 4: Run tests and Checkstyle**

Expected: PASS; recursive values cannot contain JavaFX, Gson, class-loader, or arbitrary Java objects.

- [ ] **Step 5: Commit**

```powershell
git add HMCL/src/main/java/org/jackhuang/hmcl/plugin/bridge HMCL/src/test/java/org/jackhuang/hmcl/plugin/bridge
git commit -m "Add runtime bridge value contract"
```

### Task 7: Issue Plugin-Scoped Capability Tokens

**Files:**
- Create: `HMCL/src/main/java/org/jackhuang/hmcl/plugin/bridge/PluginCapabilityToken.java`
- Create: `HMCL/src/main/java/org/jackhuang/hmcl/plugin/bridge/PluginPermissionAuthority.java`
- Modify: `HMCL/src/main/java/org/jackhuang/hmcl/plugin/PluginPermission.java`
- Modify: `HMCL/src/main/java/org/jackhuang/hmcl/plugin/PluginPermissionTier.java`
- Modify: `HMCL/src/main/java/org/jackhuang/hmcl/plugin/PluginPermissionService.java`
- Test: `HMCL/src/test/java/org/jackhuang/hmcl/plugin/bridge/PluginPermissionAuthorityTest.java`
- Test: `HMCL/src/test/java/org/jackhuang/hmcl/plugin/PluginContextPermissionTest.java`

- [ ] **Step 1: Write permission and token isolation tests**

Add `launcher-core`, `jvm-raw`, and `shell` permissions. Assert their tiers, required upgrade
re-consent, version binding, execution-mode binding, expiry/revocation, callback-domain narrowing,
and rejection when a shared Host presents plugin A's token for plugin B.

- [ ] **Step 2: Run RED tests**

Expected: FAIL because no token authority or new permissions exist.

- [ ] **Step 3: Implement opaque token issuance and verification**

Tokens contain a random 256-bit identifier and expose no public constructor. Authority stores plugin
ID, artifact identity, mode, granted permission set, callback domain, and revocation state. Every
verification takes the expected plugin ID and permission. `jvm-raw` requires embedded mode;
`launcher-core` is advanced; `jvm-raw`, `shell`, `native-code`, and `launcher-patch` are dangerous.

- [ ] **Step 4: Run permission suites and Checkstyle**

Expected: PASS, including old schema-v4 permission behavior.

- [ ] **Step 5: Commit**

```powershell
git add HMCL/src/main/java/org/jackhuang/hmcl/plugin HMCL/src/test/java/org/jackhuang/hmcl/plugin
git commit -m "Authorize external runtime bridge calls"
```

### Task 8: Connect External Hooks And Reserve Patch Dispatch

**Files:**
- Create: `HMCL/src/main/java/org/jackhuang/hmcl/plugin/runtime/RuntimeHookEndpoint.java`
- Create: `HMCL/src/main/java/org/jackhuang/hmcl/plugin/runtime/RuntimePatchEndpoint.java`
- Modify: `HMCL/src/main/java/org/jackhuang/hmcl/plugin/PluginManager.java`
- Modify: `HMCL/src/main/java/org/jackhuang/hmcl/plugin/PluginHookDispatcher.java`
- Test: `HMCL/src/test/java/org/jackhuang/hmcl/plugin/runtime/RuntimeHookEndpointTest.java`
- Test: `HMCL/src/test/java/org/jackhuang/hmcl/plugin/PluginHookDispatcherTest.java`

- [ ] **Step 1: Write external endpoint tests**

Assert that a Provider endpoint receives the same immutable `PluginHookEvent`, timeout, secret
redaction, safe cancellation, topological order, callback lease, and error category as a Java plugin.
Assert Patch endpoint registration is retained but returns `PATCH_ENGINE_UNAVAILABLE` in Stage 1.

- [ ] **Step 2: Run RED tests**

Expected: FAIL because Hook snapshots include only Java plugin callbacks.

- [ ] **Step 3: Adapt selected Provider callbacks to existing dispatcher contracts**

`RuntimeHookEndpoint` implements `PluginHookEndpoint` and invokes the selected Provider with a
plugin-scoped token. Do not duplicate Hook policy. `RuntimePatchEndpoint` validates declarations and
ownership but fails closed until Stage 2 installs the Patch engine.

- [ ] **Step 4: Run all Hook tests and Checkstyle**

Expected: PASS; the existing six game-launch-hook fixes remain green.

- [ ] **Step 5: Commit**

```powershell
git add HMCL/src/main/java/org/jackhuang/hmcl/plugin HMCL/src/test/java/org/jackhuang/hmcl/plugin
git commit -m "Route hooks through runtime providers"
```

### Task 9: Implement The Protector Protocol And Recovery Store

**Files:**
- Create: `HMCL/src/main/java/org/jackhuang/hmcl/plugin/protector/ProtectorStage.java`
- Create: `HMCL/src/main/java/org/jackhuang/hmcl/plugin/protector/ProtectorMessage.java`
- Create: `HMCL/src/main/java/org/jackhuang/hmcl/plugin/protector/ProtectorProtocol.java`
- Create: `HMCL/src/main/java/org/jackhuang/hmcl/plugin/protector/PluginRecoveryRecord.java`
- Create: `HMCL/src/main/java/org/jackhuang/hmcl/plugin/protector/PluginRecoveryStore.java`
- Test: `HMCL/src/test/java/org/jackhuang/hmcl/plugin/protector/ProtectorProtocolTest.java`
- Test: `HMCL/src/test/java/org/jackhuang/hmcl/plugin/protector/PluginRecoveryStoreTest.java`

- [ ] **Step 1: Write bounded protocol and atomic-store tests**

Cover nonce mismatch, oversized/unknown messages, five-second heartbeat encoding, every stage,
redacted recovery records, corrupt-record fail closed, atomic replacement, and exact deadline values:
connect 30 seconds, heartbeat loss 20 seconds, Core 90 seconds, Provider 60 seconds, plugin 30
seconds, hard startup 10 minutes, termination grace 10 seconds.

- [ ] **Step 2: Run RED tests**

Expected: FAIL because the protector package does not exist.

- [ ] **Step 3: Implement line-delimited bounded JSON control messages**

Use an internal local transport envelope with protocol version, nonce, monotonic timestamp, stage,
active plugin ID, and message kind. Reject documents over 16 KiB and recovery files over 1 MiB.
Persist through sibling temporary files and `ATOMIC_MOVE` with replacement fallback, matching current
plugin state-store durability.

- [ ] **Step 4: Run tests and Checkstyle**

Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add HMCL/src/main/java/org/jackhuang/hmcl/plugin/protector HMCL/src/test/java/org/jackhuang/hmcl/plugin/protector
git commit -m "Persist plugin startup recovery records"
```

### Task 10: Supervise The Post-Mixin Launcher Process

**Files:**
- Create: `HMCL/src/main/java/org/jackhuang/hmcl/plugin/protector/ProtectorBootstrap.java`
- Create: `HMCL/src/main/java/org/jackhuang/hmcl/plugin/protector/StartupReporter.java`
- Create: `HMCL/src/test/java/org/jackhuang/hmcl/plugin/protector/ProtectorFixtureMain.java`
- Modify: `HMCL/src/main/java/org/jackhuang/hmcl/EntryPoint.java`
- Modify: `HMCL/src/main/java/org/jackhuang/hmcl/Launcher.java`
- Test: `HMCL/src/test/java/org/jackhuang/hmcl/plugin/protector/ProtectorBootstrapTest.java`

- [ ] **Step 1: Write subprocess tests for every terminal path**

The fixture supports `ready`, `crash:<stage>`, `hang:<stage>`, `renew:<stage>`, and `cancel`. Assert
ready/cancel leave no recovery record, each pre-ready crash/hang records recovery, lease renewal does
not exceed the hard ten-minute rule using an injected clock, and forced termination occurs after the
ten-second grace period.

- [ ] **Step 2: Run RED tests**

Expected: FAIL because no parent/child role exists.

- [ ] **Step 3: Implement parent/child startup without disturbing Mixin relaunch**

Call `ProtectorBootstrap.enter(launcherArgs)` immediately after
`HmclMixinBootstrap.relaunchIfNeeded(launcherArgs)` returns false. Parent mode creates a random nonce,
opens a loopback-local named-pipe/Unix-domain endpoint, copies JVM input arguments and classpath,
adds authenticated internal child arguments, starts the child, supervises it, and returns from
`EntryPoint.main`. Child mode strips internal arguments, connects before Core initialization, starts
heartbeats, and continues normal startup. `Launcher` reports Core and UI ready.

- [ ] **Step 4: Run subprocess tests, EntryPoint tests, and build identity checks**

Run focused tests, `:HMCL:shadowJar`, and inspect the artifact name and `Implementation-Version`.
Expected: all end in `-next`; the distributable remains `HMCL-CE-*`.

- [ ] **Step 5: Commit**

```powershell
git add HMCL/src/main/java/org/jackhuang/hmcl/EntryPoint.java HMCL/src/main/java/org/jackhuang/hmcl/Launcher.java HMCL/src/main/java/org/jackhuang/hmcl/plugin/protector HMCL/src/test/java/org/jackhuang/hmcl/plugin/protector
git commit -m "Supervise plugin startup with protector"
```

### Task 11: Persist Quarantine Before Third-Party Discovery

**Files:**
- Modify: `HMCL/src/main/java/org/jackhuang/hmcl/plugin/PluginPersistedStates.java`
- Modify: `HMCL/src/main/java/org/jackhuang/hmcl/plugin/PluginStateStore.java`
- Modify: `HMCL/src/main/java/org/jackhuang/hmcl/plugin/PluginManager.java`
- Create: `HMCL/src/main/java/org/jackhuang/hmcl/plugin/PluginQuarantineReport.java`
- Test: `HMCL/src/test/java/org/jackhuang/hmcl/plugin/PluginStateStoreTest.java`
- Test: `HMCL/src/test/java/org/jackhuang/hmcl/plugin/PluginManagerRecoveryTest.java`

- [ ] **Step 1: Write safe-mode and restoration tests**

Assert one recovery record moves every non-built-in installed plugin ID from enabled to quarantined
before loader invocation; package/config/data bytes remain unchanged; a completed safe run does not
restore; individual/group/all restore computes concrete and runtime Provider closures first; a
second failure quarantines the whole third-party set again.

- [ ] **Step 2: Run RED tests**

Expected: FAIL because persisted state has no quarantine set.

- [ ] **Step 3: Add durable quarantine and report APIs**

Persist sorted `quarantined` IDs beside `enabled` and `pendingUninstall`. During the first locked part
of `discoverPluginsLocked`, consume the authenticated recovery record, enumerate package manifests
without class loading, remove every external ID from enabled, add it to quarantine, save strictly,
and expose a redacted `PluginQuarantineReport`. Restoration uses `PluginDependencyPlanner` plus runtime
bindings and never deletes files.

- [ ] **Step 4: Run manager/state/recovery tests and Checkstyle**

Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add HMCL/src/main/java/org/jackhuang/hmcl/plugin HMCL/src/test/java/org/jackhuang/hmcl/plugin
git commit -m "Quarantine plugins after startup failure"
```

### Task 12: Add The Recovery Report And Restore UI

**Files:**
- Create: `HMCL/src/main/java/org/jackhuang/hmcl/ui/main/PluginRecoveryPage.java`
- Modify: `HMCL/src/main/java/org/jackhuang/hmcl/ui/main/PluginManagementPage.java`
- Modify: `HMCL/src/main/java/org/jackhuang/hmcl/ui/main/LauncherSettingsPage.java`
- Modify: `HMCL/src/main/resources/assets/lang/I18N.properties`
- Modify: `HMCL/src/main/resources/assets/lang/I18N_zh_CN.properties`
- Test: `HMCL/src/test/java/org/jackhuang/hmcl/ui/main/PluginRecoveryPageTest.java`

- [ ] **Step 1: Write presentation and action-model tests**

Assert the page shows failure reason, timestamp, last stage, active Provider/plugin, log/dump
references, quarantined IDs, and retained-file wording without rendering secret fields. Assert
restore-one, restore dependency-consistent group, and restore-all call the exact manager APIs, while
ordinary page construction never enables a plugin.

- [ ] **Step 2: Run RED tests**

Expected: FAIL because no recovery page exists.

- [ ] **Step 3: Implement the un-nested recovery surface**

Place a recovery banner and navigation action in `PluginManagementPage`; render
`PluginRecoveryPage` as a normal full-width settings page, not a nested card. Use existing icon
buttons and confirmation dialogs. Disable restore actions while a mutation is running, refresh from
the persisted report afterward, and leave quarantine intact after merely viewing or closing the
page. Translate every visible string in English and Simplified Chinese, then let the normal language
fallback process cover other bundles.

- [ ] **Step 4: Run UI model tests, translations, and Checkstyle**

```powershell
.\gradlew.bat :HMCL:test --tests org.jackhuang.hmcl.ui.main.PluginRecoveryPageTest :HMCL:checkTranslations :HMCL:checkstyleMain :HMCL:checkstyleTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add HMCL/src/main/java/org/jackhuang/hmcl/ui/main HMCL/src/main/resources/assets/lang HMCL/src/test/java/org/jackhuang/hmcl/ui/main
git commit -m "Add plugin startup recovery UI"
```

### Task 13: Synchronize Schema-V5 SDK Validation And References

**Files:**
- Modify: `C:/Users/ACX/Documents/Plugins/HMCL-CE-Plugin-SDK/tools/validate-npl.ps1`
- Modify: `C:/Users/ACX/Documents/Plugins/HMCL-CE-Plugin-SDK/tools/test-validate-npl.ps1`
- Modify: `C:/Users/ACX/Documents/Plugins/HMCL-CE-Plugin-SDK/tools/sync-api-references.ps1`
- Modify: `C:/Users/ACX/Documents/Plugins/HMCL-CE-Plugin-SDK/store/manifest.template.json`
- Modify: `C:/Users/ACX/Documents/Plugins/HMCL-CE-Plugin-SDK/docs/PLUGIN_DEVELOPMENT.md`
- Sync: `C:/Users/ACX/Documents/Plugins/HMCL-CE-Plugin-SDK/references/hmcl-plugin-api/`

- [ ] **Step 1: Add failing validator fixtures matching HMCL tests**

Add valid Rust language and Java Provider manifests, artifact matrices, pin/mode fields, and every
invalid combination from Tasks 1 and 2. Keep v4 fixtures proving all new fields are rejected.

- [ ] **Step 2: Run SDK RED tests**

```powershell
pwsh -NoProfile -File .\tools\test-validate-npl.ps1
```

Expected: FAIL on the first new valid Provider fixture.

- [ ] **Step 3: Implement validator parity and sync snapshots**

Parse exact JSON property types before conversion. Enforce canonical IDs, distinct Provider runtime
entries, ABI/mode/feature sets, isolated/raw rejection, runtime-owned entrypoint paths, and exact
artifact target uniqueness. Update the template with one Rust Host matrix while clearly marking the
Host as a future package. Run `sync-api-references.ps1` from HMCL `next`; do not touch SDK
`schema-v4`.

- [ ] **Step 4: Run all SDK publishing tests**

```powershell
pwsh -NoProfile -File .\tools\test-validate-npl.ps1
pwsh -NoProfile -File .\tools\test-publishing-tools.ps1
```

Expected: PASS.

- [ ] **Step 5: Commit in the SDK repository**

```powershell
git add tools store docs references
git commit -m "Define runtime provider package contracts"
```

### Task 14: Run Stage-1 Regression And Recovery Verification

**Files:**
- Verify only; fix failures in the owning files from Tasks 1-13.

- [ ] **Step 1: Run focused plugin suites**

```powershell
$env:JAVA_HOME='C:\Users\ACX\AppData\Local\Temp\codex-hmcl-jdk17-20260824\jdk-17.0.20+8'
.\gradlew.bat :HMCL:test --tests 'org.jackhuang.hmcl.plugin.*' --tests 'org.jackhuang.hmcl.plugin.runtime.*' --tests 'org.jackhuang.hmcl.plugin.store.*' --tests 'org.jackhuang.hmcl.plugin.protector.*'
```

Expected: PASS.

- [ ] **Step 2: Run complete HMCL verification**

```powershell
.\gradlew.bat :HMCL:test :HMCL:checkstyleMain :HMCL:checkstyleTest :HMCL:checkTranslations :HMCL:shadowJar
```

Expected: PASS with no failed tests or Checkstyle/translation violations.

- [ ] **Step 3: Verify v4 and build identity**

Run the schema-v4 compatibility tests, inspect the Shadow JAR filename, and read its manifest.
Expected: schema v4 remains executable; filename and `Implementation-Version` end in `-next`.

- [ ] **Step 4: Verify SDK and repository state**

Run both SDK scripts, `git diff --check` in both repositories, and `git status --short --branch`.
Expected: no uncommitted generated drift; HMCL is on `next`; SDK is on `schema-v5`; neither stable
branch was modified or pushed.

- [ ] **Step 5: Commit only verified fixes, if any**

Use one narrowly named commit per repository. Do not create an empty verification commit.
