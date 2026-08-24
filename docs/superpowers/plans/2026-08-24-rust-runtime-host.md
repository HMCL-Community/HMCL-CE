# HMCL CE Next Rust Runtime Host Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver Rust as the complete optional Runtime Provider reference, with embedded `cdylib`, isolated execution, Core/UI Bridge, Hook/Patch callbacks, raw JVM access, examples, and six-platform packages.

**Architecture:** A normal schema-v5 Java Host plugin registers the Rust Provider and loads a small JNI engine. That engine loads third-party Rust `cdylib` payloads through a versioned C ABI or starts the isolated runner using the same semantic MessagePack protocol. HMCL owns permission checks, object handles, JavaFX dispatch, and bytecode transformation; the Rust SDK supplies safe wrappers and an explicitly unsafe raw-JVM module.

**Tech Stack:** Rust stable, Cargo workspaces, `jni`, `libloading`, `serde`, `rmp-serde`, Java 17, JNI/JVMTI, ASM 9, JUnit 5, Gradle 9, GitHub Actions.

---

## Prerequisite

Complete and verify `docs/superpowers/plans/2026-08-24-runtime-provider-foundation.md`. The HMCL
Runtime Provider SPI, Bridge values, capability tokens, Protector, Store artifact matrices, and SDK
schema-v5 validator must exist before this plan begins.

## Repository Ownership

- HMCL `next` owns generic Core/UI Bridge operations, the JVM Patch engine, Instrumentation access,
  and integration tests.
- SDK `schema-v5` owns the optional Rust Host plugin, Rust workspace, Rust SDK, reference payloads,
  packaging, native CI, and authoring documentation.
- SDK `schema-v4` and HMCL stable `main` remain untouched.

## File And Ownership Map

### HMCL executable services

- `HMCL/src/main/java/org/jackhuang/hmcl/plugin/bridge/CoreBridgeService.java`: stable launcher operations.
- `HMCL/src/main/java/org/jackhuang/hmcl/plugin/bridge/UiBridgeService.java`: JavaFX-thread UI operations.
- `HMCL/src/main/java/org/jackhuang/hmcl/plugin/bridge/BridgeServiceRegistry.java`: numeric method dispatch.
- `HMCL/src/main/java/org/jackhuang/hmcl/plugin/patch/PluginPatchEngine.java`: owned Patch registration and transformation.
- `HMCL/src/main/java/org/jackhuang/hmcl/plugin/patch/PluginPatchTransformer.java`: ASM transformation pipeline.
- `HMCL/src/main/java/org/jackhuang/hmcl/plugin/mixin/bootstrap/HmclMixinAgent.java`: publish Instrumentation before application main.
- `HMCL/src/main/java/org/jackhuang/hmcl/plugin/mixin/bootstrap/PluginInstrumentation.java`: guarded Instrumentation/JVM access.

### SDK Rust workspace

- `C:/Users/ACX/Documents/Plugins/HMCL-CE-Plugin-SDK/runtime-hosts/rust/Cargo.toml`: workspace definition and locked dependency policy.
- `runtime-hosts/rust/crates/hmcl-runtime-abi/`: C-compatible ABI structs, constants, and MessagePack model.
- `runtime-hosts/rust/crates/hmcl-plugin-sdk/`: safe Rust authoring API.
- `runtime-hosts/rust/crates/hmcl-rust-host-native/`: JNI Host engine and dynamic payload loader.
- `runtime-hosts/rust/crates/hmcl-rust-isolated-runner/`: child-process transport and payload owner.
- `runtime-hosts/rust/host-plugin/`: Java Runtime Host bootstrap and `.npl` packaging.
- `runtime-hosts/rust/examples/`: Bridge, UI, Hook/Patch, raw JVM, and isolated reference plugins.
- `.github/workflows/rust-runtime-host.yml`: Windows/Linux/macOS x64/arm64 build and conformance matrix.

## ABI Locks

- Every exported C symbol uses `extern "C"`, fixed-width integers, `#[repr(C)]`, and no Rust enum,
  trait object, slice, `String`, `Vec`, panic, or allocator ownership across the boundary.
- Every function table starts with `struct_size` and `abi_version`. Readers ignore unknown trailing
  fields and reject tables shorter than required fields.
- The Host supplies allocation/free functions; buffers are returned with the matching owner and
  released exactly once.
- No panic or Java exception crosses the ABI. Rust catches panics; JNI boundaries translate pending
  Java exceptions into structured `HmclStatus` errors.
- `raw-jvm` APIs are inside an unsafe Rust module and require an embedded capability token.
- Isolated mode never serializes addresses, JNI handles, Java objects, or capability-token bytes.

### Task 1: Create The Versioned Rust ABI Workspace

**Files:**
- Create: `C:/Users/ACX/Documents/Plugins/HMCL-CE-Plugin-SDK/runtime-hosts/rust/Cargo.toml`
- Create: `runtime-hosts/rust/Cargo.lock`
- Create: `runtime-hosts/rust/rust-toolchain.toml`
- Create: `runtime-hosts/rust/crates/hmcl-runtime-abi/Cargo.toml`
- Create: `runtime-hosts/rust/crates/hmcl-runtime-abi/src/lib.rs`
- Create: `runtime-hosts/rust/crates/hmcl-runtime-abi/tests/layout.rs`

- [ ] **Step 1: Write compile-time layout and symbol tests**

Assert table alignment, offsets, fixed discriminants, null-safe optional callbacks, and ABI version 1:

```rust
#[test]
fn host_api_v1_has_stable_prefix() {
    assert_eq!(std::mem::align_of::<HmclHostApiV1>(), std::mem::align_of::<usize>());
    assert_eq!(HmclStatus::Ok as i32, 0);
    assert_eq!(HMCL_BRIDGE_ABI_V1, 1);
    assert!(std::mem::size_of::<HmclHostApiV1>() >= 8 + 6 * std::mem::size_of::<usize>());
}
```

- [ ] **Step 2: Run the RED test**

Run `cargo test -p hmcl-runtime-abi` from `runtime-hosts/rust`.
Expected: FAIL because the workspace and ABI types do not exist.

- [ ] **Step 3: Implement the C ABI prefix**

Define `HmclStatus`, `HmclSlice`, `HmclOwnedBuffer`, opaque plugin/token/handle IDs, callback IDs,
`HmclHostApiV1`, and `HmclPluginApiV1`. Exported payloads expose exactly:

```rust
#[no_mangle]
pub unsafe extern "C" fn hmcl_plugin_query_v1(
    host: *const HmclHostApiV1,
    out_plugin: *mut HmclPluginApiV1,
) -> HmclStatus;
```

The query checks both table sizes and versions before copying its own table. Add `#![deny(unsafe_op_in_unsafe_fn)]` and document every unsafe precondition.

- [ ] **Step 4: Run format, lint, and tests**

```powershell
cargo fmt --all -- --check
cargo clippy --workspace --all-targets -- -D warnings
cargo test -p hmcl-runtime-abi
```

Expected: PASS.

- [ ] **Step 5: Commit in SDK**

```powershell
git add runtime-hosts/rust
git commit -m "Define Rust plugin ABI v1"
```

### Task 2: Implement Rust Bridge Values And Safe SDK Ownership

**Files:**
- Create: `runtime-hosts/rust/crates/hmcl-plugin-sdk/Cargo.toml`
- Create: `runtime-hosts/rust/crates/hmcl-plugin-sdk/src/lib.rs`
- Create: `runtime-hosts/rust/crates/hmcl-plugin-sdk/src/value.rs`
- Create: `runtime-hosts/rust/crates/hmcl-plugin-sdk/src/handle.rs`
- Create: `runtime-hosts/rust/crates/hmcl-plugin-sdk/src/error.rs`
- Create: `runtime-hosts/rust/crates/hmcl-plugin-sdk/src/future.rs`
- Create: `runtime-hosts/rust/crates/hmcl-plugin-sdk/tests/ownership.rs`

- [ ] **Step 1: Write SDK round-trip and drop tests**

Use a fake Host table to assert scalar/bytes/map values, one free per owned buffer, cloned handle
retain/release, stale handle errors, callback cancellation, and panic conversion.

- [ ] **Step 2: Run RED tests**

Run `cargo test -p hmcl-plugin-sdk`.
Expected: FAIL because the SDK crate does not exist.

- [ ] **Step 3: Implement safe wrappers over the raw table**

Expose `PluginContext`, `Value`, `ObjectHandle<T>`, `Callback`, `PluginFuture`, and `Error`. `Drop`
releases only resources owned by the current plugin. Convert strings and bytes through Host-owned
buffers; never retain a borrowed pointer after a call. Provide a `hmcl_plugin!` macro that emits the
query symbol and catches panics around every callback.

- [ ] **Step 4: Run Rust verification**

Expected: `fmt`, `clippy -D warnings`, and ABI/SDK tests PASS.

- [ ] **Step 5: Commit in SDK**

```powershell
git add runtime-hosts/rust/crates/hmcl-plugin-sdk runtime-hosts/rust/Cargo.toml runtime-hosts/rust/Cargo.lock
git commit -m "Add safe Rust plugin SDK"
```

### Task 3: Expose Stable Core And JavaFX Bridge Operations

**Files:**
- Create: `HMCL/src/main/java/org/jackhuang/hmcl/plugin/bridge/BridgeMethod.java`
- Create: `HMCL/src/main/java/org/jackhuang/hmcl/plugin/bridge/BridgeServiceRegistry.java`
- Create: `HMCL/src/main/java/org/jackhuang/hmcl/plugin/bridge/CoreBridgeService.java`
- Create: `HMCL/src/main/java/org/jackhuang/hmcl/plugin/bridge/UiBridgeService.java`
- Modify: `HMCL/src/main/java/org/jackhuang/hmcl/plugin/PluginUIRegistry.java`
- Test: `HMCL/src/test/java/org/jackhuang/hmcl/plugin/bridge/CoreBridgeServiceTest.java`
- Test: `HMCL/src/test/java/org/jackhuang/hmcl/plugin/bridge/UiBridgeServiceTest.java`

- [ ] **Step 1: Write method-ID, permission, and FX-thread tests**

Freeze numeric IDs for launcher version, plugin directories, sidebar action/page registration,
property mutation, navigation, and unregister-owner. Assert unknown IDs fail, Core operations require
`launcher-core`, UI operations require `launcher-ui`, and UI mutation executes on the FX Application
Thread or the deterministic pre-toolkit path.

- [ ] **Step 2: Run RED tests**

Expected: FAIL because no stable numeric Bridge service exists.

- [ ] **Step 3: Implement a small typed service registry**

Each `BridgeMethod` declares ID, permission, accepted argument schema, result schema, and thread
policy. Dispatcher validates values before resolving handles. UI page contributions initially use a
Bridge-owned declarative node tree plus callbacks; raw plugins may separately use JNI objects. Owner
unregister removes sidebar items, callbacks, handles, and declarative nodes.

- [ ] **Step 4: Run Bridge/UI tests and Checkstyle**

Expected: PASS.

- [ ] **Step 5: Commit in HMCL**

```powershell
git add HMCL/src/main/java/org/jackhuang/hmcl/plugin/bridge HMCL/src/main/java/org/jackhuang/hmcl/plugin/PluginUIRegistry.java HMCL/src/test/java/org/jackhuang/hmcl/plugin/bridge
git commit -m "Expose core and UI runtime bridge"
```

### Task 4: Build The Optional Java Rust Host Bootstrap

**Files:**
- Create: `runtime-hosts/rust/host-plugin/build.gradle.kts`
- Create: `runtime-hosts/rust/host-plugin/plugin.json`
- Create: `runtime-hosts/rust/host-plugin/src/main/java/dev/hmclce/runtime/rust/RustRuntimeHostPlugin.java`
- Create: `runtime-hosts/rust/host-plugin/src/main/java/dev/hmclce/runtime/rust/RustRuntimeProvider.java`
- Create: `runtime-hosts/rust/host-plugin/src/main/java/dev/hmclce/runtime/rust/RustNativeEngine.java`
- Create: `runtime-hosts/rust/host-plugin/src/test/java/dev/hmclce/runtime/rust/RustRuntimeHostPluginTest.java`

- [ ] **Step 1: Write Java bootstrap tests**

Assert manifest/provider declaration parity, native artifact path selection, one registration on
load, health-check failure propagation, registration close on unload, and absence of Rust classes or
native bytes from the HMCL Shadow JAR.

- [ ] **Step 2: Run RED tests**

Run the host Gradle test with `HMCL_JAR` pointing at the Stage-1 Shadow JAR.
Expected: FAIL because the Host project does not exist.

- [ ] **Step 3: Implement the Host as a normal plugin**

Manifest uses `runtime: java`, `pluginKind: runtime-provider`, `native-code` required permission, and
one `providesRuntimes` Rust declaration. `RustRuntimeHostPlugin.onLoad` chooses the exact platform
native engine, validates it remains under the package directory, calls `System.load`, creates
`RustRuntimeProvider`, and stores the returned `RuntimeProviderRegistration`. `onUnload` closes that
registration and native engine in reverse order.

- [ ] **Step 4: Run Java Host tests and package validation**

Expected: PASS and a schema-v5 `.npl` whose Java bootstrap and selected native engine are present.

- [ ] **Step 5: Commit in SDK**

```powershell
git add runtime-hosts/rust/host-plugin
git commit -m "Add optional Rust runtime host plugin"
```

### Task 5: Implement The JNI Engine And Embedded `cdylib` Loader

**Files:**
- Create: `runtime-hosts/rust/crates/hmcl-rust-host-native/Cargo.toml`
- Create: `runtime-hosts/rust/crates/hmcl-rust-host-native/src/lib.rs`
- Create: `runtime-hosts/rust/crates/hmcl-rust-host-native/src/jni_api.rs`
- Create: `runtime-hosts/rust/crates/hmcl-rust-host-native/src/embedded.rs`
- Create: `runtime-hosts/rust/crates/hmcl-rust-host-native/src/bridge.rs`
- Create: `runtime-hosts/rust/crates/hmcl-rust-host-native/tests/embedded.rs`

- [ ] **Step 1: Write dynamic-library lifecycle tests**

Build a fixture `cdylib` and assert query, ABI/size validation, init, Hook call, shutdown, reverse
resource cleanup, missing symbol, wrong ABI, panic containment, and library path escape rejection.

- [ ] **Step 2: Run RED tests**

Run `cargo test -p hmcl-rust-host-native`.
Expected: FAIL because the engine crate does not exist.

- [ ] **Step 3: Implement JNI entrypoints and `libloading` ownership**

JNI exports create/destroy engine, health check, load/enable/disable/unload payload, dispatch Hook,
and invoke Bridge methods. Cache `JavaVM`, never cache thread-local `JNIEnv`, attach native callback
threads as daemon threads, clear/translate pending Java exceptions, and unload a payload only after
callbacks and handles drain. Call `hmcl_plugin_query_v1` before any plugin lifecycle callback.

- [ ] **Step 4: Run Rust and Java integration tests**

Expected: PASS under the current Windows x64 development host.

- [ ] **Step 5: Commit in SDK**

```powershell
git add runtime-hosts/rust/crates/hmcl-rust-host-native runtime-hosts/rust/Cargo.toml runtime-hosts/rust/Cargo.lock
git commit -m "Load embedded Rust plugins"
```

### Task 6: Implement Equivalent Isolated MessagePack Execution

**Files:**
- Create: `runtime-hosts/rust/crates/hmcl-runtime-abi/src/protocol.rs`
- Create: `runtime-hosts/rust/crates/hmcl-rust-isolated-runner/Cargo.toml`
- Create: `runtime-hosts/rust/crates/hmcl-rust-isolated-runner/src/main.rs`
- Create: `runtime-hosts/rust/crates/hmcl-rust-host-native/src/isolated.rs`
- Create: `runtime-hosts/rust/crates/hmcl-rust-host-native/tests/isolated.rs`
- Create: `runtime-hosts/rust/contracts/golden/bridge-v1.msgpack`

- [ ] **Step 1: Write embedded/isolated equivalence tests**

Dispatch the same golden scalar/map/bytes/error/future/callback/cancellation vectors through both
modes and assert byte-for-byte semantic results. Assert nonce failure, oversized frame, child crash,
timeout, and attempted raw-handle transfer are rejected.

- [ ] **Step 2: Run RED tests**

Expected: FAIL because no isolated runner exists.

- [ ] **Step 3: Implement authenticated local IPC**

Use a 4-byte big-endian length followed by MessagePack, capped at 8 MiB. Handshake includes protocol
version, random 256-bit nonce, plugin identity, ABI, and granted capability names but never token
bytes. The Host maps remote calls to its local plugin-scoped token. On shutdown, stop new calls,
cancel outstanding requests, wait the Provider deadline, terminate, then kill if needed.

- [ ] **Step 4: Run equivalence and failure tests**

Expected: PASS; isolated payloads cannot request `raw-jvm`.

- [ ] **Step 5: Commit in SDK**

```powershell
git add runtime-hosts/rust
git commit -m "Run isolated Rust plugins"
```

### Task 7: Publish Instrumentation And Execute Reversible JVM Patches

**Files:**
- Create: `HMCL/src/main/java/org/jackhuang/hmcl/plugin/mixin/bootstrap/PluginInstrumentation.java`
- Create: `HMCL/src/main/java/org/jackhuang/hmcl/plugin/patch/PluginPatchCallback.java`
- Create: `HMCL/src/main/java/org/jackhuang/hmcl/plugin/patch/PluginPatchRegistration.java`
- Create: `HMCL/src/main/java/org/jackhuang/hmcl/plugin/patch/PluginPatchEngine.java`
- Create: `HMCL/src/main/java/org/jackhuang/hmcl/plugin/patch/PluginPatchTransformer.java`
- Modify: `HMCL/src/main/java/org/jackhuang/hmcl/plugin/mixin/bootstrap/HmclMixinAgent.java`
- Modify: `HMCL/build.gradle.kts`
- Test: `HMCL/src/test/java/org/jackhuang/hmcl/plugin/patch/PluginPatchEngineTest.java`
- Test: `HMCL/src/test/java/org/jackhuang/hmcl/plugin/mixin/bootstrap/HmclMixinAgentTest.java`

- [ ] **Step 1: Write transformation, order, and restoration tests**

Patch a test fixture with before/after/replace callbacks. Assert dependency order then plugin ID,
argument/result conversion, permission and token checks, callback exceptions, conflict diagnostics,
class retransformation, owner unload restoration, and no transformation of bootstrap/plugin engine
classes.

- [ ] **Step 2: Run RED tests**

Expected: FAIL because Stage 1 reports `PATCH_ENGINE_UNAVAILABLE`.

- [ ] **Step 3: Capture Instrumentation and install one retransformation transformer**

Publish Instrumentation from `premain` through `PluginInstrumentation`, set JAR manifest
`Can-Retransform-Classes` to `true`, and install one ASM transformer owned by `PluginPatchEngine`.
Validate target class/method/descriptor against manifest declarations before registration. Compose
callbacks without injecting foreign code into transformed classes; transformed methods call a stable
Java dispatcher keyed by registration ID. On final registration removal, retransform from the
unmodified class bytes retained by the engine.

- [ ] **Step 4: Run Patch/Mixin suites, Checkstyle, and Shadow JAR manifest inspection**

Expected: PASS; `Premain-Class` remains unchanged and retransformation is advertised.

- [ ] **Step 5: Commit in HMCL**

```powershell
git add HMCL/src/main/java/org/jackhuang/hmcl/plugin/mixin/bootstrap HMCL/src/main/java/org/jackhuang/hmcl/plugin/patch HMCL/src/test/java/org/jackhuang/hmcl/plugin/mixin/bootstrap HMCL/src/test/java/org/jackhuang/hmcl/plugin/patch HMCL/build.gradle.kts
git commit -m "Execute runtime plugin JVM patches"
```

### Task 8: Expose Opt-In Raw JVM And Native Access

**Files:**
- Create: `runtime-hosts/rust/crates/hmcl-plugin-sdk/src/raw.rs`
- Modify: `runtime-hosts/rust/crates/hmcl-rust-host-native/src/jni_api.rs`
- Create: `runtime-hosts/rust/crates/hmcl-rust-host-native/tests/raw_jvm.rs`
- Test: `HMCL/src/test/java/org/jackhuang/hmcl/plugin/bridge/RawJvmPermissionTest.java`

- [ ] **Step 1: Write raw access grant and denial tests**

Assert embedded plus `jvm-raw` returns a valid `JavaVM`, controlled `jvmtiEnv`, HMCL class loader
global reference, and Instrumentation global reference; denial, isolated mode, expired token, wrong owner, and unload all
reject access. Assert the fixture calls a Java method and releases every global reference.

- [ ] **Step 2: Run RED tests**

Expected: FAIL because the Rust SDK has no raw module.

- [ ] **Step 3: Implement the unsafe SDK module and Host checks**

Expose `unsafe fn java_vm`, `unsafe fn jvmti_env`, `unsafe fn hmcl_class_loader`,
`unsafe fn instrumentation`, and native
symbol lookup only through a context that successfully requests `jvm-raw`/`native-code`. Document
thread attachment, global-reference ownership, JVM lifetime, and Next build-range responsibility.
Register every issued reference for owner cleanup before returning it.

- [ ] **Step 4: Run Rust/JNI/permission tests**

Expected: PASS; isolated tests still prove raw access absent.

- [ ] **Step 5: Commit HMCL and SDK separately**

```powershell
# HMCL
git add HMCL/src/test/java/org/jackhuang/hmcl/plugin/bridge
git commit -m "Verify raw JVM runtime permissions"

# SDK
git add runtime-hosts/rust
git commit -m "Expose unsafe Rust JVM bridge"
```

### Task 9: Build Executable Rust Reference Plugins

**Files:**
- Create: `runtime-hosts/rust/examples/core-bridge/`
- Create: `runtime-hosts/rust/examples/ui-page/`
- Create: `runtime-hosts/rust/examples/game-launch-hook/`
- Create: `runtime-hosts/rust/examples/jvm-patch/`
- Create: `runtime-hosts/rust/examples/raw-jvm/`
- Create: `runtime-hosts/rust/examples/isolated/`
- Modify: `tools/publish-plugin.ps1`
- Modify: `tools/test-publishing-tools.ps1`
- Create: `docs/RUST_PLUGIN_DEVELOPMENT.md`

- [ ] **Step 1: Add publishing tests for runtime-owned entrypoints and native artifacts**

Assert each example builds a schema-v5 `.npl`, includes only its target artifact, declares exact
permissions, selects embedded/isolated correctly, and validates against Store metadata.

- [ ] **Step 2: Run RED publishing tests**

Expected: FAIL because Rust payload packaging is unsupported.

- [ ] **Step 3: Implement six focused examples and package support**

Each example demonstrates one capability and clean unload. The UI example registers and removes a
page; Hook modifies a game JVM argument without secrets; Patch targets a documented testable launcher
method and restores it; raw JVM obtains/releases a Java reference; isolated performs Bridge calls
without raw access. Publishing chooses the runtime-specific entrypoint and platform artifact without
accepting path traversal.

- [ ] **Step 4: Build, validate, and smoke-test all examples**

Expected: all packages validate and execute under the Windows x64 development launcher.

- [ ] **Step 5: Commit in SDK**

```powershell
git add runtime-hosts/rust/examples docs/RUST_PLUGIN_DEVELOPMENT.md tools
git commit -m "Add Rust runtime reference plugins"
```

### Task 10: Produce Six-Platform Host Artifacts And Conformance CI

**Files:**
- Create: `.github/workflows/rust-runtime-host.yml`
- Create: `runtime-hosts/rust/scripts/build-host.ps1`
- Create: `runtime-hosts/rust/scripts/build-host.sh`
- Create: `runtime-hosts/rust/scripts/package-host.ps1`
- Create: `runtime-hosts/rust/contracts/platforms.json`
- Modify: `store/manifest.template.json`
- Modify: `README.md`

- [ ] **Step 1: Add a matrix validation test**

The packaging script must fail unless exactly these targets exist: `windows-x64`, `windows-arm64`,
`linux-x64`, `linux-arm64`, `macos-x64`, `macos-arm64`. It verifies native filenames, SHA-256,
positive sizes, manifest target equality, and no foreign target in one `.npl`.

- [ ] **Step 2: Run the matrix test before workflow creation**

Expected: FAIL listing six missing artifacts.

- [ ] **Step 3: Add reproducible native build and packaging jobs**

CI runs format/clippy/tests once, builds native Host/runner/examples per target, runs native tests on
matching runners, packages one artifact per target, validates each `.npl`, uploads checksums, then
runs a manifest aggregation job. Use project-controlled arm64 runners where managed runners are not
available. Do not mark a target successful when only cross-compilation ran without conformance.

- [ ] **Step 4: Run available local verification and inspect workflow syntax**

Expected: Windows x64 build/tests/package PASS locally; workflow matrix contains all six unique
targets and preserves `schema-v5` prerelease naming.

- [ ] **Step 5: Commit in SDK**

```powershell
git add .github/workflows/rust-runtime-host.yml runtime-hosts/rust store/manifest.template.json README.md
git commit -m "Build Rust runtime host matrix"
```

### Task 11: Run Stage-2 End-To-End Verification

**Files:**
- Verify only; repair failures in the owning Task 1-10 files.

- [ ] **Step 1: Run complete HMCL verification**

Run `:HMCL:test`, main/test Checkstyle, translations, and Shadow JAR. Expected: PASS with v4/v5 and
Protector tests intact.

- [ ] **Step 2: Run complete Rust verification**

Run `cargo fmt --all -- --check`, `cargo clippy --workspace --all-targets -- -D warnings`, and
`cargo test --workspace`. Expected: PASS.

- [ ] **Step 3: Run Host and package verification**

Build the Host Gradle project, package Windows x64 Host and all reference plugins, run both SDK
PowerShell test suites, and launch the integration harness against the Next Shadow JAR. Expected:
Bridge, UI, Hook, Patch, raw JVM, isolated mode, unload, and Protector recovery PASS.

- [ ] **Step 4: Verify product boundaries and repository state**

Confirm the HMCL Shadow JAR contains no Rust Host native library, all launcher artifact versions end
in `-next`, SDK work is only on `schema-v5`, `git diff --check` passes, and both worktrees are clean.

- [ ] **Step 5: Commit only verified fixes, if any**

Use one narrow commit per repository; do not create empty commits or push.
