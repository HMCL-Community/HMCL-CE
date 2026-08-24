# HMCL CE Next Cross-Language Runtime SDKs Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Freeze one language-neutral Runtime Provider contract and publish non-executable .NET, JavaScript/WASM, and Python SDKs, examples, and Provider conformance tooling that match the working Rust reference.

**Architecture:** A canonical IDL and golden MessagePack corpus are generated from the tested HMCL Bridge/Hook/Patch surface. Each language package supplies values, handles, callbacks, errors, manifest helpers, and Host adapter interfaces without bundling an engine. A transport-neutral conformance runner validates any later Host before it may advertise a runtime, mode, feature, or platform.

**Tech Stack:** Java 17, Rust stable, JSON Schema, MessagePack, .NET 8/C#, TypeScript 5/Node 22 tooling, WebAssembly Component Model WIT, Python 3.11+, PowerShell 7, GitHub Actions.

---

## Prerequisite

Complete and verify both earlier plans:

- `docs/superpowers/plans/2026-08-24-runtime-provider-foundation.md`
- `docs/superpowers/plans/2026-08-24-rust-runtime-host.md`

Rust behavior is normative. This plan may expose omissions by adding cross-language golden vectors;
fix such omissions in the shared HMCL/Rust contract rather than adding language-specific semantics.

## Publication Truthfulness

- No .NET, JavaScript/WASM, or Python Runtime Provider is registered by HMCL after this plan.
- Examples are compile/type/package fixtures and are labeled `Host required`; they are not listed as
  executable Store plugins.
- SDK packages contain no CoreCLR, V8, QuickJS, Wasmtime, Node, or CPython binaries.
- Runtime IDs remain distinct: `dotnet`, `javascript`, `wasm`, and `python`.
- A future combined JavaScript/WASM Host may advertise both IDs only after passing both suites.

## File And Ownership Map

### Canonical contracts

- `C:/Users/ACX/Documents/Plugins/HMCL-CE-Plugin-SDK/contracts/runtime-provider-v1.json`: Provider lifecycle and capability schema.
- `contracts/bridge-v1.json`: method IDs, value schemas, permissions, thread policy, and errors.
- `contracts/hook-v1.json`: Hook envelope/cancellation/error semantics.
- `contracts/patch-v1.json`: target, callback, ordering, and restoration semantics.
- `contracts/protocol-v1.json`: embedded table and isolated MessagePack method IDs.
- `contracts/golden/`: canonical JSON and MessagePack request/result vectors.
- `tools/export-runtime-contract.ps1`: export HMCL snapshots and reject drift.

### Language SDKs

- `sdk/dotnet/src/Hmcl.PluginSdk/`: .NET types and Provider adapter interfaces.
- `sdk/javascript/src/`: TypeScript package for JavaScript payloads.
- `sdk/wasm/hmcl-plugin.wit`: WASM Component Model contract.
- `sdk/python/src/hmcl_plugin_sdk/`: typed Python package.
- `examples/dotnet-runtime-skeleton/`, `examples/javascript-runtime-skeleton/`,
  `examples/wasm-runtime-skeleton/`, `examples/python-runtime-skeleton/`: package fixtures.

### Conformance

- `conformance/runtime-provider/`: language-neutral runner, vectors, and reports.
- `conformance/adapters/`: SDK-side in-memory adapters used before executable Hosts exist.
- `.github/workflows/cross-language-sdks.yml`: build/type/test/package matrix.

### Task 1: Freeze Canonical Runtime And Bridge IDL

**Files:**
- Create: `C:/Users/ACX/Documents/Plugins/HMCL-CE-Plugin-SDK/contracts/runtime-provider-v1.json`
- Create: `contracts/bridge-v1.json`
- Create: `contracts/hook-v1.json`
- Create: `contracts/patch-v1.json`
- Create: `contracts/protocol-v1.json`
- Create: `contracts/golden/manifest-provider.json`
- Create: `contracts/golden/bridge-values.json`
- Create: `tools/export-runtime-contract.ps1`
- Modify: `tools/test-publishing-tools.ps1`

- [ ] **Step 1: Write a drift test before creating IDL files**

The test exports HMCL `BridgeMethod` IDs, permissions, thread policies, Provider enums, Hook points,
Patch positions, error codes, and Rust ABI constants, then compares them to checked-in canonical
files. It must report the first exact mismatched property.

- [ ] **Step 2: Run the RED test**

Run `pwsh -NoProfile -File .\tools\test-publishing-tools.ps1`.
Expected: FAIL because the canonical contract files are absent.

- [ ] **Step 3: Write complete version-1 schemas and exporter**

Every ID and discriminant is an explicit integer or canonical kebab-case string. Include null,
booleans, signed fixed-width numbers, strings, bytes, arrays, maps, handles, errors, futures,
callbacks, cancellation, lifecycle states, execution modes, and all permission gates. Include
`additionalProperties: false` for closed objects and exact maximum frame/value sizes.

Exporter reads the built Next Shadow JAR plus Rust contract constants and produces normalized JSON
with sorted object keys and stable list order. `-Check` compares without rewriting; `-Write` performs
an explicit contract update.

- [ ] **Step 4: Run exporter check and publishing tests**

Expected: PASS and a second `-Write` produces no diff.

- [ ] **Step 5: Commit in SDK**

```powershell
git add contracts tools
git commit -m "Freeze runtime provider contract v1"
```

### Task 2: Generate And Test The .NET SDK

**Files:**
- Create: `sdk/dotnet/Hmcl.PluginSdk.sln`
- Create: `sdk/dotnet/src/Hmcl.PluginSdk/Hmcl.PluginSdk.csproj`
- Create: `sdk/dotnet/src/Hmcl.PluginSdk/BridgeValue.cs`
- Create: `sdk/dotnet/src/Hmcl.PluginSdk/ObjectHandle.cs`
- Create: `sdk/dotnet/src/Hmcl.PluginSdk/PluginContext.cs`
- Create: `sdk/dotnet/src/Hmcl.PluginSdk/RuntimeProviderAdapter.cs`
- Create: `sdk/dotnet/src/Hmcl.PluginSdk/PluginException.cs`
- Create: `sdk/dotnet/tests/Hmcl.PluginSdk.Tests/Hmcl.PluginSdk.Tests.csproj`
- Create: `sdk/dotnet/tests/Hmcl.PluginSdk.Tests/GoldenContractTests.cs`

- [ ] **Step 1: Write .NET golden and ownership tests**

Test every Bridge value, MessagePack vector, cancellation token, async callback, exception code,
handle equality/owner, double-dispose protection, Provider lifecycle order, and raw-JVM absence from
isolated adapters.

```csharp
[Fact]
public async Task HandleCannotCrossPluginOwners()
{
    using var fixture = GoldenFixture.Load();
    ObjectHandle<Page> handle = fixture.PluginA.CreateHandle<Page>();
    PluginException error = await Assert.ThrowsAsync<PluginException>(
        () => fixture.PluginB.ResolveAsync(handle));
    Assert.Equal(PluginErrorCode.InvalidHandleOwner, error.Code);
}
```

- [ ] **Step 2: Run the RED test**

Run `dotnet test sdk/dotnet/Hmcl.PluginSdk.sln`.
Expected: FAIL because the solution does not exist.

- [ ] **Step 3: Implement a `net8.0` SDK without a runtime engine**

Use closed record types and `IAsyncDisposable` handles. `PluginContext` delegates through
`IRuntimeTransport`; `RuntimeProviderAdapter` defines Host implementer callbacks but has no CoreCLR
bootstrap. Generate method/error enums from canonical JSON and fail the build if generated output is
dirty. Package ID is `Hmcl.CE.PluginSdk.Next` with prerelease version metadata.

- [ ] **Step 4: Run format, build, tests, and package inspection**

```powershell
dotnet format sdk/dotnet/Hmcl.PluginSdk.sln --verify-no-changes
dotnet test sdk/dotnet/Hmcl.PluginSdk.sln -c Release
dotnet pack sdk/dotnet/src/Hmcl.PluginSdk/Hmcl.PluginSdk.csproj -c Release
```

Expected: PASS; `.nupkg` contains no runtime engine or native binaries.

- [ ] **Step 5: Commit in SDK**

```powershell
git add sdk/dotnet
git commit -m "Add schema v5 dotnet SDK contract"
```

### Task 3: Generate And Test JavaScript And WASM SDKs

**Files:**
- Create: `sdk/javascript/package.json`
- Create: `sdk/javascript/tsconfig.json`
- Create: `sdk/javascript/src/value.ts`
- Create: `sdk/javascript/src/context.ts`
- Create: `sdk/javascript/src/provider-adapter.ts`
- Create: `sdk/javascript/src/errors.ts`
- Create: `sdk/javascript/test/golden.test.ts`
- Create: `sdk/wasm/hmcl-plugin.wit`
- Create: `sdk/wasm/package.json`
- Create: `sdk/wasm/test/wit-contract.test.mjs`

- [ ] **Step 1: Write TypeScript golden tests and WIT validation**

Assert bigint range handling, immutable byte copies, map key rules, Promise cancellation, callback
errors, handle owner/generation, lifecycle order, and exact WIT method/error discriminants.

- [ ] **Step 2: Run RED tests**

Run `npm ci && npm test` in both SDK directories.
Expected: FAIL because package files are absent.

- [ ] **Step 3: Implement engine-neutral TypeScript and WIT bindings**

Target ES2022, emit ESM plus declarations, and expose an injected `RuntimeTransport`; do not depend
on Node globals in the public API. Encode i64/u64 as `bigint`, bytes as copied `Uint8Array`, and
handles as opaque branded values. WIT defines equivalent resource handles, futures/streams through
pollable operations, and the same error enum. Both packages are prerelease and state that a Host is
required.

- [ ] **Step 4: Run lint, typecheck, tests, and package inspection**

Expected: PASS; tarballs contain TypeScript/WIT bindings and no JS/WASM engine.

- [ ] **Step 5: Commit in SDK**

```powershell
git add sdk/javascript sdk/wasm
git commit -m "Add JavaScript and WASM SDK contracts"
```

### Task 4: Generate And Test The Python SDK

**Files:**
- Create: `sdk/python/pyproject.toml`
- Create: `sdk/python/src/hmcl_plugin_sdk/__init__.py`
- Create: `sdk/python/src/hmcl_plugin_sdk/value.py`
- Create: `sdk/python/src/hmcl_plugin_sdk/context.py`
- Create: `sdk/python/src/hmcl_plugin_sdk/provider_adapter.py`
- Create: `sdk/python/src/hmcl_plugin_sdk/errors.py`
- Create: `sdk/python/tests/test_golden_contract.py`
- Create: `sdk/python/tests/test_typing.py`

- [ ] **Step 1: Write Python golden, async, and ownership tests**

Test strict integer ranges despite Python arbitrary precision, immutable bytes/maps, async
cancellation, callback exceptions, owner/generation checks, context-manager cleanup, and isolated
raw-access denial.

```python
async def test_stale_handle_is_rejected(fixture: GoldenFixture) -> None:
    handle = await fixture.plugin_a.create_handle("page")
    await handle.aclose()
    with pytest.raises(PluginError, match="stale-handle"):
        await fixture.plugin_a.resolve(handle)
```

- [ ] **Step 2: Run RED tests**

Run `python -m pytest sdk/python/tests`.
Expected: FAIL because the package does not exist.

- [ ] **Step 3: Implement a typed Python 3.11+ SDK**

Use frozen dataclasses, `Protocol` transports, `AsyncContextManager`, explicit range validation, and
`MappingProxyType`/tuple immutable views. Generate enums/method tables from canonical JSON. Build a
pure-Python wheel named `hmcl-ce-plugin-sdk-next`; include no CPython distribution or native module.

- [ ] **Step 4: Run formatter, type checker, tests, and wheel inspection**

Run Ruff format/check, mypy strict, pytest, and `python -m build`.
Expected: PASS; wheel tags are pure Python.

- [ ] **Step 5: Commit in SDK**

```powershell
git add sdk/python
git commit -m "Add schema v5 Python SDK contract"
```

### Task 5: Add Honest Package And Example Skeletons

**Files:**
- Create: `examples/dotnet-runtime-skeleton/plugin.json`
- Create: `examples/dotnet-runtime-skeleton/src/Plugin.cs`
- Create: `examples/javascript-runtime-skeleton/plugin.json`
- Create: `examples/javascript-runtime-skeleton/src/plugin.ts`
- Create: `examples/wasm-runtime-skeleton/plugin.json`
- Create: `examples/wasm-runtime-skeleton/src/plugin.wit`
- Create: `examples/python-runtime-skeleton/plugin.json`
- Create: `examples/python-runtime-skeleton/src/plugin.py`
- Modify: `tools/test-validate-npl.ps1`
- Modify: `docs/PLUGIN_DEVELOPMENT.md`

- [ ] **Step 1: Add validator tests for all four runtime IDs**

Each manifest uses schema v5, ABI 2, explicit mode, runtime-owned entrypoint, exact platform list,
and only the permissions demonstrated. Tests assert validation succeeds but executable availability
is reported false without an installed Provider.

- [ ] **Step 2: Run RED validator tests**

Expected: FAIL on unsupported runtime entrypoint rules or missing skeleton files.

- [ ] **Step 3: Implement minimal compile/type-check examples**

Each example registers one stable Bridge call and one Hook callback through its SDK. Headings and
package metadata say `Requires a separately installed Runtime Host`; Store templates do not publish
these examples as currently runnable. No example requests Patch/raw permissions unless it invokes
that exact API.

- [ ] **Step 4: Build/type-check examples and run package validation**

Expected: all compile/type-check and validate; HMCL compatibility evaluator still reports
`MISSING_RUNTIME` when no optional Host is installed.

- [ ] **Step 5: Commit in SDK**

```powershell
git add examples tools/test-validate-npl.ps1 docs/PLUGIN_DEVELOPMENT.md
git commit -m "Add external runtime plugin skeletons"
```

### Task 6: Build The Provider Conformance Runner

**Files:**
- Create: `conformance/runtime-provider/Cargo.toml`
- Create: `conformance/runtime-provider/src/main.rs`
- Create: `conformance/runtime-provider/src/case.rs`
- Create: `conformance/runtime-provider/src/report.rs`
- Create: `conformance/adapters/dotnet/`
- Create: `conformance/adapters/javascript/`
- Create: `conformance/adapters/wasm/`
- Create: `conformance/adapters/python/`
- Create: `conformance/README.md`

- [ ] **Step 1: Write runner self-tests and a deliberately broken adapter**

Assert deterministic case order, per-feature skip rules, embedded/isolated vector parity, timeout,
crash, malformed reply, leaked handle, wrong permission, lifecycle order, and machine-readable report
output. The broken adapter must fail with the exact case ID.

- [ ] **Step 2: Run RED tests**

Run `cargo test --manifest-path conformance/runtime-provider/Cargo.toml`.
Expected: FAIL because the runner does not exist.

- [ ] **Step 3: Implement manifest-driven conformance**

Runner accepts a Provider command/adapter and advertised runtime, mode, feature, platform, Plugin
ABI, and Bridge ABI. It runs only claimed capabilities but always runs lifecycle, values, errors,
ownership, permissions, cancellation, cleanup, crash, and timeout. Exit is nonzero for a failed or
unexpectedly skipped required case. JSON report records contract digest, adapter version, platform,
case IDs, durations, and redacted errors.

In-memory language adapters prove each SDK can consume all vectors; they are not executable HMCL
Hosts and do not register runtime capabilities.

- [ ] **Step 4: Run runner self-tests and all adapter suites**

Expected: broken adapter fails; Rust reference and four in-memory SDK adapters pass their declared
contract subsets.

- [ ] **Step 5: Commit in SDK**

```powershell
git add conformance
git commit -m "Add runtime provider conformance kit"
```

### Task 7: Add Cross-Language CI And Prerelease Packages

**Files:**
- Create: `.github/workflows/cross-language-sdks.yml`
- Modify: `README.md`
- Modify: `API_CHEATSHEET.md`
- Modify: `CHANGELOG.md`
- Modify: `dist/TESTING.md`

- [ ] **Step 1: Add a CI configuration assertion**

Publishing-tool tests parse the workflow and assert jobs exist for .NET build/test/pack,
JavaScript test/pack, WIT validation, Python lint/type/test/wheel, contract drift, conformance, and
artifact inspection. Every package version must contain a prerelease marker.

- [ ] **Step 2: Run RED publishing tests**

Expected: FAIL because the workflow is absent.

- [ ] **Step 3: Add CI and documentation**

Use lockfile-respecting installs, cache only package-manager caches, and upload SDK packages plus
conformance reports. Documentation distinguishes supported schema contracts, working Rust Host, and
future .NET/JS/WASM/Python Hosts in one table. It explains Provider installation through virtual
dependencies and the three permission levels without claiming sandbox security.

- [ ] **Step 4: Run every locally available CI command**

Expected: all SDK unit/type/package tests and conformance self-tests PASS; package inspection finds no
runtime engine binaries.

- [ ] **Step 5: Commit in SDK**

```powershell
git add .github/workflows/cross-language-sdks.yml README.md API_CHEATSHEET.md CHANGELOG.md dist/TESTING.md
git commit -m "Publish cross-language SDK contracts"
```

### Task 8: Run Final Three-Stage Verification

**Files:**
- Verify only; repair failures in the task that owns the affected contract.

- [ ] **Step 1: Verify HMCL completely**

Run full HMCL tests, Checkstyle main/test, translations, Shadow JAR, schema-v4 compatibility, Runtime
Provider, Rust integration, Patch, raw JVM, and Protector subprocess suites. Expected: PASS and all
launcher versions/artifacts end in `-next`.

- [ ] **Step 2: Verify Rust and canonical contract drift**

Run Rust workspace format/clippy/tests, six-target matrix validation, and
`export-runtime-contract.ps1 -Check`. Expected: PASS with no generated diff.

- [ ] **Step 3: Verify every language SDK and conformance adapter**

Run .NET format/test/pack, JavaScript tests/pack, WIT validation, Python Ruff/mypy/pytest/build, and
the conformance runner. Expected: all declared subsets PASS; no non-Rust Host claims executable
availability.

- [ ] **Step 4: Verify repository and branch boundaries**

Run `git diff --check` and status in both repositories. Expected: HMCL clean on `next`, SDK clean on
`schema-v5`, no changes on `main` or `schema-v4`, no push, and the user's separate `main-release`
worktree change remains untouched.

- [ ] **Step 5: Commit only verified fixes, if any**

Use narrow commits in the owning repository. Do not create an empty completion commit.
