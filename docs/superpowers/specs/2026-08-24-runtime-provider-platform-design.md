# HMCL CE Next Runtime Provider Platform Design

## Purpose

HMCL CE Next will make non-JVM languages first-class plugin implementations without bundling their
runtimes into the launcher. Runtime hosts remain ordinary, optional HMCL plugins. Installing a
language plugin can resolve and install its host through the existing dependency and Store
transaction machinery; uninstalling the last dependent makes that host removable again.

The platform must maximize what an explicitly authorized external-language plugin can do. It
therefore provides a stable Core/UI bridge, Hook and JVM Patch services, and an unsafe direct
JVM/native path. The default plugin execution topology keeps HMCL, the Host, and its payload in one
JVM process. The small Protector process is only a supervisor and executes no plugin code. A plugin
may request isolation when it accepts proxy access instead of process-local JVM pointers.

This design also adds startup recovery. A small Protector owned by Next detects one startup crash or
timeout, then starts HMCL with all third-party plugins persistently quarantined. Plugin files,
configuration, and data are retained for diagnosis and manual recovery.

## Product-Line Boundaries

- HMCL CE `main` remains the stable schema-v4 line.
- HMCL CE `next` accepts executable schema-v4 and schema-v5 packages and carries the `-next` build
  identity.
- SDK `schema-v4` remains the primary stable branch and is not given multi-language fields.
- SDK `schema-v5` owns Runtime Provider contracts and remains prerelease until the Next product line
  is promoted.
- Schema-v4 Java/Kotlin packages continue using the built-in Java provider. Runtime Provider work
  must not narrow their install, permission, or execution behavior.
- Runtime identifiers include the existing `java`, `dotnet`, `python`, `javascript`, and `native`
  values. Schema v5 adds the formal `rust` and `wasm` identifiers. Rust packages use `rust`, not
  `native`. One JavaScript/WASM Host plugin may provide both `javascript` and `wasm`.

## Goals

- Keep Java as the only runtime included in HMCL itself.
- Make every external Runtime Host independently installable, updatable, disableable, and removable
  as a normal plugin.
- Extend the current dependency graph instead of creating a second package manager.
- Select only platform-compatible Host and plugin artifacts.
- Share one compatible Host among multiple language plugins.
- Support embedded and isolated execution through one semantic ABI.
- Let authorized external languages modify Core behavior, JavaFX UI, launcher Hooks, JVM bytecode,
  and native facilities.
- Recover automatically from one third-party startup crash or timeout without deleting user data.
- Deliver Rust as the complete reference Host on Windows, Linux, and macOS for x64 and arm64.
- Freeze equivalent .NET, JavaScript/WASM, and Python contracts and SDK surfaces so later Hosts do not
  fragment the architecture.

## Non-Goals

- This work does not turn unsafe plugins into an adversarial security sandbox. A plugin granted raw
  JVM, native, process, shell, or unrestricted filesystem access can interfere with the launcher and
  the Protector's files. The recovery design protects against faulty plugins, not a malicious plugin
  with equivalent user privileges.
- The first delivery does not bundle or claim executable .NET, JavaScript/WASM, or Python Hosts.
- Schema v4 does not gain non-JVM payloads.
- Stable Bridge compatibility does not extend to raw JVM implementation details. Raw plugins target
  a specific Next build range and may break when HMCL internals change.
- Platforms outside Windows/Linux/macOS x64/arm64 are not claimed by the first Rust Host. HMCL may
  continue to support them without Rust availability.

## Architecture

The built-in launcher side contains six language-neutral services:

- `RuntimeProviderRegistry` stores provider implementations and per-plugin provider bindings.
- Runtime capability resolution contributes virtual dependency edges to
  `PluginDependencyPlanner`.
- Plugin ABI Bridge owns values, object handles, callbacks, asynchronous operations, and thread
  dispatch.
- Hook/Patch services connect schema-v5 declarations to launcher operations and JVM
  instrumentation.
- Permission Authority issues plugin-scoped capability tokens and validates every privileged bridge
  call.
- Runtime Supervisor owns Provider registration, health, dependent loading, shutdown, and recovery
  diagnostics.

A Runtime Host is a schema-v5 Java plugin with `pluginKind: runtime-provider`. Its Java bootstrap is
loaded by the built-in Java provider and registers one or more external runtime capabilities. Native
engines and platform artifacts remain inside that Host package; HMCL does not acquire a compile-time
dependency on them.

An ordinary external-language plugin keeps the existing schema-v5 `runtime` and `abi` fields. Those
fields create a virtual runtime dependency in addition to its concrete plugin-ID dependencies. The
selected Provider loads the language payload only after its own initialization, ABI negotiation,
health check, and permission setup have succeeded.

The registry evolves from one provider per runtime name to provider records keyed by provider plugin
ID. Multiple implementations may advertise the same runtime. A binding selects one Provider for one
dependent plugin, allowing explicit pins and future alternatives without a global replacement.
`java` remains a reserved built-in binding that cannot be replaced or unregistered.

## Schema-V5 Runtime Contract

### Language Plugins

The existing schema-v5 fields retain their meaning:

- `runtime` is the required virtual runtime capability, such as `rust`.
- `abi` is the exact HMCL Plugin ABI generation required by the package.
- `platforms` constrains compatible OS/architecture targets.

The following schema-v5 fields are added:

- `executionMode` is `embedded` or `isolated` and defaults to `embedded`.
- `runtimeProvider` optionally pins a provider plugin ID. An absent value permits deterministic
  capability resolution.
- `entrypoint` becomes a runtime-owned relative package path. The Java provider continues requiring
  its Java class entrypoint; the Rust provider validates the selected dynamic library or isolated
  executable. A path may not escape the selected package artifact.

Required Provider features are derived rather than duplicated in the manifest. Every non-JVM plugin
requires `bridge`; Hook and Patch declarations require `hooks` and `patches`; unsafe JVM/native
permissions require the corresponding advertised feature. Compatibility fails before code loading
when a Provider lacks a derived feature.

### Runtime Provider Plugins

A Provider manifest uses `runtime: java`, because its bootstrap is an ordinary Java plugin, and adds
`providesRuntimes`. Each provided capability declares:

- canonical runtime name;
- supported Plugin ABI generations;
- Bridge ABI generation;
- supported execution modes;
- supported features, including any of `bridge`, `hooks`, `patches`, `raw-jvm`, and `native`;
- a Provider bootstrap entrypoint and health-check identifier.

The Provider plugin's normal version is its implementation version. Store platform artifacts remain
the source of OS/architecture matching, package hashes, download size, and source metadata. A Host
must not claim a runtime/mode/feature combination for which the selected platform artifact lacks the
required engine.

One Provider may advertise multiple runtime names. This is how a combined JavaScript/WASM Host can
serve `javascript` and `wasm` without treating them as the same payload type.

### Compatibility And Validation

HMCL startup discovery, local installation, Store filtering, SDK validation, and lifecycle loading
must consume the same compatibility evaluator. Validators reject:

- a Provider declaration before schema v5;
- duplicate runtime declarations in one Provider;
- malformed IDs, empty ABI sets, unknown modes, or unknown features;
- provider pins whose selected plugin does not advertise the requested runtime;
- execution modes or derived features unsupported by the Provider;
- runtime-specific entrypoints invalid for the selected platform artifact;
- `raw-jvm` requests in isolated mode.

SDK `schema-v5` mirrors launcher validation. SDK `schema-v4` remains unchanged.

## Capability Dependencies And Store Resolution

`PluginDependency` continues representing a concrete plugin ID and version constraint. A separate
immutable runtime requirement is derived from each schema-v5 manifest and added to the same planning
graph as a virtual edge. This avoids changing the serialized meaning of existing dependency arrays
while reusing closure, cycle, ordering, reverse dependency, transaction, journal, and recovery code.

Resolution follows this order:

1. Use a compatible explicitly pinned installed Provider.
2. Otherwise reuse a compatible enabled installed Provider.
3. Otherwise reuse a compatible disabled installed Provider and include its enablement in the plan.
4. Otherwise query official and trusted enabled sources for current-platform artifacts.
5. Consider an untrusted or custom source only after a separate source confirmation.

Within one step, the existing source priority wins, followed by the highest compatible Provider
version and then lexical provider ID as a deterministic tie breaker. An explicit pin never silently
falls back to a different Provider.

Before confirmation, the install plan displays the requested language plugin, selected Host,
provider source, platform artifact, individual and total download sizes, permissions, execution
mode, and any new unsafe grants. The Host and language plugin are downloaded, verified, installed,
and enabled in one existing Store transaction. Provider failure restores the previous packages and
enablement state through the existing journal recovery path.

Multiple dependents share the selected compatible Host. A Provider cannot be disabled, removed, or
updated incompatibly while an enabled dependent binding exists. An explicit cascade operation may
disable the dependent closure first. Disabling retains every package and its data. Restoring a
language plugin causes its Provider binding to be resolved before enablement.

## Provider Lifecycle

Runtime Supervisor enforces this state sequence:

`DISCOVERED -> RESOLVED -> BOOTSTRAP_LOADED -> REGISTERED -> NEGOTIATED -> INITIALIZED -> HEALTHY -> READY`

Dependents can load only in `READY`. Any transition failure records the Provider ID, runtime, ABI,
mode, platform, and transition without including secrets or unrelated package content. Startup then
fails through the normal plugin failure path so Protector recovery can operate.

Shutdown runs in strict reverse dependency order:

1. Stop new plugin callbacks.
2. Cancel or drain callbacks within their declared deadlines.
3. Revoke plugin capability tokens and object handles.
4. Remove plugin Hooks and Patches.
5. Unload language dependents.
6. Stop and unregister the Provider.

A Provider update is planned against every installed dependent before files change. After update,
registration, negotiation, and health checks run before dependents are re-enabled. Failure restores
the old Host artifact, binding, and enablement state.

## Execution Modes

### Embedded

`embedded` is the default. The Provider engine and language payload run inside the HMCL JVM process:

- Rust loads a platform `cdylib` (`.dll`, `.so`, or `.dylib`).
- A future .NET Host embeds CoreCLR through the supported native hosting API.
- A future JavaScript/WASM Host embeds its selected JavaScript and WASM engines.
- A future Python Host embeds CPython.

The embedded ABI uses versioned, size-prefixed C-compatible function tables with explicit ownership
and allocator functions. Rust is the normative implementation. No plugin may assume that a newer
table contains fields beyond the advertised structure size.

### Isolated

An isolated payload runs as a child process owned by the same Provider. It uses authenticated local
IPC over a named pipe on Windows or Unix-domain socket on Unix. Messages are length-prefixed
MessagePack and carry the same method IDs, values, handles, callbacks, errors, and cancellation
semantics as the embedded ABI. Golden vectors prove semantic equivalence between both transports.

Isolation can protect the launcher from ordinary language exceptions and some native crashes, but
it cannot expose process-local pointers. Isolated plugins use Bridge and Patch proxy operations and
cannot receive JNI/JVMTI handles, `jobject` references, raw JavaFX objects, or launcher address-space
symbols.

## Cross-Language Capability Surface

All four external-language families receive three composable access paths.

### Stable Bridge API

The Bridge represents null, booleans, fixed-width numbers, strings, byte arrays, immutable structured
values, errors, futures, streams, callbacks, and opaque object handles. Language SDKs wrap these in
idiomatic types. Handles carry owner plugin, type descriptor, generation, and lifetime. A stale,
cross-plugin, or revoked handle fails before dispatch.

Core and UI services expose stable operations through generated bindings. JavaFX work is marshalled
to the JavaFX Application Thread, while blocking work is rejected there unless an API explicitly
allows it. Callback reentry, cancellation, timeouts, exception conversion, and unload cleanup have
one language-neutral definition.

### Hook And Patch API

Schema-v5 Hook declarations use the existing structured Hook contexts. Mutability, cancellation,
timeout, safe cancellation, secret scanning, and callback error redaction remain consistent across
Java and external languages.

The JVM-side Patch Engine owns instrumentation and bytecode transformation. External plugins
describe targets and register before, after, or replacement callbacks through the Patch ABI; they do
not reimplement ASM or JVM instrumentation. Plugin unload removes its callbacks and retransforms or
restores affected classes when the selected Patch mechanism supports restoration. Patch conflicts
are ordered deterministically and diagnosed with all participating plugin IDs.

### Raw JVM And Native Access

An embedded plugin with explicit unsafe grants may obtain the process `JavaVM`, controlled JNI and
JVMTI entrypoints, HMCL class loader, JVM Instrumentation handle, native symbol lookup, and direct
global references to Java/JavaFX objects. Protector starts the child with the Next Patch and
Instrumentation bootstrap active before application `main`, so these facilities do not depend on a
late-loaded plugin acquiring startup-only JVM capabilities. This path permits reflection, generated
proxies, direct method calls, JavaFX mutation, native APIs, and destructive launcher patches.

Raw references remain owned by the requesting plugin. Runtime Supervisor tracks and invalidates
registered resources during unload where the underlying platform permits it, but native memory
safety cannot be guaranteed. A raw plugin must declare a compatible Next build range in addition to
Plugin ABI because launcher internals are not stable ABI.

## Permission Model

Permissions are grouped for presentation while remaining individually auditable:

- Level 1, Plugin API: stable data and commands allowed by existing plugin permissions.
- Level 2, privileged Core/UI/Hook: internal Core services, UI mutation, object handles, and Hook
  registration.
- Level 3, unsafe JVM/Patch/Native/Shell: JVM raw access, launcher patches, native loading, process
  control, shell execution, and equivalent destructive facilities.

Installation shows every requested grant and its level. Updating a plugin with new grants pauses
enablement until they are approved. Denied optional grants remain denied; denied required grants
prevent activation with a specific diagnostic.

Permission Authority issues tokens scoped to plugin ID, installed version, granted capabilities,
execution mode, and current callback domain. Providers receive tokens only while dispatching on
behalf of that plugin. A shared Host cannot pool, cache beyond lifetime, or transfer permissions
between dependents. Every privileged Bridge, Hook, Patch, raw-handle, and IPC operation validates
the token at the launcher boundary.

## Protector And Startup Recovery

Protector is a small Next-owned Java bootstrap in the distributable launcher, not a plugin and not
part of any Runtime Host. The top-level launcher process runs only Protector duties and starts a
child HMCL JVM from the same launcher artifact with an authenticated nonce. It does not initialize the
Plugin Manager or execute third-party code.

Protector and HMCL communicate over a nonce-authenticated local named pipe or Unix-domain socket.
HMCL sends a heartbeat every five seconds and reports these stages:

- JVM started;
- Core ready;
- Runtime Providers loading, including the active Provider ID;
- ordinary plugins loading, including the active plugin ID;
- UI ready.

The child entrypoint establishes the authenticated session before Core initialization and has 30
seconds to connect. After connection, twenty seconds without a heartbeat is a hang. Core readiness has a 90-second deadline, each Provider
has 60 seconds, and each ordinary plugin has 30 seconds. A known controlled migration may renew its
current stage lease while heartbeats continue, but startup has a hard ten-minute limit. On timeout,
Protector requests diagnostics and graceful termination, waits ten seconds, then forcibly terminates
the child.

Before UI-ready, one unexpected process exit, crash, heartbeat loss, or hard deadline creates an
atomic recovery record. A normal shutdown message and an explicit user cancellation do not. Exits
after UI-ready are retained in diagnostics but do not automatically quarantine startup plugins.

On the next launch, Protector supplies a recovery nonce and safe-mode flag. Before Plugin Manager
loads third-party classes or native code, HMCL atomically moves every non-built-in plugin, including
Runtime Hosts, into persistent quarantine. It does not delete plugin packages, configuration, or
data. Built-in Java support and the recovery UI remain available.

The recovery report includes timestamps, failure reason, last stage and heartbeat, active Provider
or plugin, launcher log reference, and diagnostic dump reference when available. It excludes secret
values. Users may restore one plugin, a dependency-consistent group, or all plugins. Restore planning
resolves Runtime Hosts first. Another startup failure repeats whole-set quarantine. Merely completing
one safe launch never restores plugins automatically.

## Rust Reference Host

Rust is the complete reference for Provider authors and for all ABI conformance rules. Its Host is a
normal optional schema-v5 Java plugin with per-platform native artifacts for:

- Windows x64 and arm64;
- Linux x64 and arm64;
- macOS x64 and arm64.

The Rust SDK provides the ABI tables, safe ownership wrappers, generated Core/UI bindings, Hook and
Patch registration, permission errors, asynchronous/callback support, and an opt-in unsafe module
for raw JNI/JVMTI/native access. The Host supports embedded `cdylib` payloads and the equivalent
isolated executable protocol.

Reference plugins demonstrate stable Core calls, a JavaFX page contribution and mutation, game
launch Hooks, a reversible Patch, direct raw JVM access, permission denial, clean unload, and an
isolated payload. Examples use only declared permissions and are validated as Store-ready packages.

## Other Language Contracts

The .NET, JavaScript/WASM, and Python deliverables include schema-v5 manifest support, generated SDK
types, ABI/IPC definitions, package validators, example skeletons, and the Provider conformance kit.
They do not include runtime engines or advertise an installed Provider. Their generated surfaces are
derived from the same IDL and golden vectors as Rust, preventing language-specific Core, UI, Hook,
Patch, permission, or lifecycle semantics.

A later Host is releasable only when it passes the conformance suite for every runtime, mode, feature,
and platform it advertises. A Host may implement a subset, but its manifest must advertise only that
subset.

## Error Handling And Diagnostics

Errors are typed by stage: manifest validation, platform selection, trust confirmation, capability
resolution, transaction, Provider bootstrap, registration, negotiation, health, permission,
payload load, callback, unload, and Protector recovery. User-facing messages name the plugin,
Provider, runtime, ABI, execution mode, and actionable incompatibility.

Provider and language exceptions cross the ABI as structured error codes with redacted messages and
optional developer diagnostics. No exception is allowed to unwind across a native ABI boundary.
Panics, unmanaged exceptions, JavaScript throws, and Python exceptions are caught by their Host where
the engine permits; an uncontained embedded crash is handled by Protector at the next launch.

Logs attach plugin and Provider identities to callbacks without recording capability tokens, secrets,
raw object content, or IPC authentication material. Diagnostic dumps are referenced rather than
embedded in ordinary logs.

## Delivery Sequence

### Stage 1: Provider Foundation

- Extend schema v5, validators, registry, compatibility evaluation, and dependency planning.
- Connect Provider installation to Store transactions and recovery journals.
- Implement Runtime Supervisor, Bridge primitives, permission tokens, platform artifact selection,
  and Protector.
- Preserve and test schema-v4 execution and the immutable built-in Java provider.

### Stage 2: Rust Reference Implementation

- Build the optional Rust Host, Rust SDK, embedded and isolated transports, and reference plugins.
- Implement the stable Core/UI bridge, Hook/Patch dispatch, and unsafe raw JVM/native route end to
  end.
- Produce and verify six platform artifact combinations.

### Stage 3: Cross-Language Contract Freeze

- Generate .NET, JavaScript/WASM, and Python SDK contracts and example skeletons.
- Publish the Provider conformance kit, IDL, golden values, lifecycle tests, and authoring docs.
- Clearly mark these languages as awaiting their optional executable Hosts.

Each stage is implemented and verified before the next begins. All changes remain on HMCL CE `next`
and SDK `schema-v5`; stable `main` and `schema-v4` receive none of this feature work.

## Testing And Acceptance

Development follows red-green-refactor. Acceptance requires:

- schema-v4 Java/Kotlin install, permission, compatibility, and execution regression coverage;
- schema-v5 Provider and language-manifest parity between HMCL and SDK validation;
- virtual dependency closure, sharing, pinning, cycles, source priority, custom-source confirmation,
  reverse dependencies, cascade disable, update compatibility, transaction rollback, and journal
  recovery tests;
- platform selection tests for all six Rust targets and explicit unsupported-host diagnostics;
- Provider lifecycle transition, failure, reverse shutdown, update rollback, and resource cleanup
  tests;
- embedded/isolated golden vectors for values, binary data, handles, callbacks, futures, streams,
  cancellation, errors, and timeouts;
- permission denial, upgrade re-consent, token expiry, cross-plugin theft, and shared-Host isolation
  tests;
- JavaFX thread dispatch, UI contribution removal, Hook mutation/cancellation/redaction, Patch order
  and restoration, and raw JNI/JVMTI integration tests;
- Protector process tests that crash, hang, exceed each stage deadline, renew valid leases, cancel
  normally, force termination, write recovery records, quarantine all third-party plugins, retain
  files, and restore dependency-consistent groups;
- Rust Host and reference-plugin conformance on Windows/Linux/macOS x64 and arm64. Project-controlled
  runners are used where the CI service has no suitable managed runner;
- complete HMCL tests, Checkstyle, translation checks, SDK publishing-tool tests, package validation,
  and clean worktree checks;
- built launcher and SDK artifacts retain their required `next` and schema-v5 prerelease identity.

No stage is considered complete merely because a manifest parses. Completion requires an executable
end-to-end path for every capability claimed by that stage and a clean rollback or recovery path for
its failures.
