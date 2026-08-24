# HMCL CE Next Game Launch Hook Dispatcher Design

## Purpose

This milestone turns the schema-v5 Hook declarations into one executable vertical slice. It adds a
general Hook dispatcher, connects `before-game-launch` to the complete process plan, and dispatches
`after-game-launch` when a directly launched Minecraft process terminates. The design deliberately
supports the future external Runtime Hosts without bundling any external runtime into HMCL.

The milestone does not connect the other ten declared Hook points, implement Runtime Provider
lifecycle ownership, or add a Patch engine. Those remain separate milestones in the agreed roadmap.

## Compatibility And Scope

- Schema-v4 plugins continue to load and run unchanged. They cannot declare or receive Hooks.
- Existing schema-v5 Java/Kotlin plugins remain source and binary compatible because the new
  `Plugin.onHook(PluginHookEvent)` method has a default unchanged result.
- Only loaded and enabled schema-v5 plugins that declare the current Hook and currently hold the
  required `launcher-hook` permission are eligible for dispatch.
- The dispatcher is generic over all `PluginHookPoint` values, but this milestone registers payload
  policies only for `before-game-launch` and `after-game-launch`.
- Script generation dispatches `before-game-launch` with execution mode `script`. It cannot dispatch
  `after-game-launch` because HMCL does not own or observe the later script process.

## Architecture

The implementation separates four responsibilities:

1. `LaunchProcessPlan` in HMCLCore is the launcher-neutral, mutable-by-replacement description of
   everything required to start Minecraft or render an equivalent script.
2. `PluginHookDispatcher` in HMCL selects subscribers, orders them, invokes each endpoint, validates
   each result, and commits transformations one plugin at a time.
3. `GameLaunchHookCoordinator` translates between `LaunchProcessPlan` and the versioned Hook data
   envelope. It owns launch cancellation, secret slots, and after-process observations.
4. `HMCLGameLauncher` composes the coordinator with `DefaultLauncher`. HMCLCore never depends on the
   plugin module or on a Runtime Host.

`DefaultLauncher` is refactored into a prepare/execute pipeline:

```text
LaunchOptions
    -> prepareLaunchProcessPlan()
    -> before-game-launch transformations
    -> validate final plan
    -> executeLaunchProcessPlan() OR renderLaunchScript()
    -> observe owned process termination
    -> after-game-launch notification
```

Direct launch and script generation therefore consume the same validated final plan. Command
construction, environment expansion, pre-launch commands, and post-exit commands are not rebuilt
through a second path after the Hook runs.

## Complete Launch Process Plan

`LaunchProcessPlan` is a versioned value object. Callers and plugins receive snapshots and return
replacements; no callback can mutate a plan already owned by another callback or by the launcher.
Its wire representation contains only JSON-compatible scalars, arrays, objects, and secret
references.

The mutable portion contains:

- execution mode: `direct` or `script`;
- the main command in either `structured-java` or `raw` mode;
- Java executable, JVM arguments, classpath entries, main class, and game arguments in structured
  mode;
- the complete ordered command token list in raw mode;
- working directory;
- inherited environment policy plus explicit set and unset operations;
- optional pre-launch and post-exit process specifications, each with command tokens, working
  directory, and environment changes;
- launcher visibility behavior and the existing process/output behavior needed by HMCL.

Structured mode is authoritative until a plugin explicitly replaces the command with raw tokens.
The dispatcher exposes a derived complete-token preview for inspection, but that preview is not a
second mutable source of truth. Calling `replaceWithRawCommand` switches the plan to raw mode and
preserves the exact supplied token order. A later plugin sees the selected mode and may either edit
that representation or explicitly replace it again. This avoids ambiguous precedence between JVM
fields and an independently edited command array.

The event also contains immutable launch metadata, including the instance ID, resolved game
version, launcher version, host OS/architecture, and execution mode. Metadata describes the
operation and cannot be rewritten through a Hook result. A plugin with `launcher-hook` can still
replace the actual command and process settings, which is the intended high-privilege capability.

Before execution or rendering, validation rejects:

- empty raw commands or empty Java executables/main classes;
- null values, NUL characters, malformed environment names, or unknown plan versions;
- relative or invalid working directories where the selected operation requires an absolute path;
- unresolved, unknown, or unauthorized secret slots;
- contradictory structured/raw command state;
- payload fields that belong to immutable launch metadata.

Validation errors identify the responsible plugin and JSON path without including secret values.

## Hook Event And Result Contract

Java/Kotlin plugins receive one backwards-compatible callback:

```java
default PluginHookResult onHook(PluginHookEvent event)
```

The conceptual event envelope is independent of the Java representation:

```json
{
  "contractVersion": 1,
  "dispatchId": "opaque-id",
  "point": "before-game-launch",
  "occurredAt": "2026-08-24T00:00:00Z",
  "data": {}
}
```

The data model supports JSON null, boolean, number, string, array, and string-keyed object values.
The JVM API wraps that model in immutable plugin API types; it does not expose `LaunchOptions`,
`ManagedProcess`, JavaFX properties, class loaders, or implementation objects.

A result is exactly one of:

- unchanged;
- replace data with a complete candidate payload;
- cancel a cancellable before Hook with a stable reason code and user-facing message.

Cancellation is valid only for before Hook policies. Cancelling `before-game-launch` stops the
operation before pre-launch commands or process creation and surfaces a dedicated launch-cancelled
failure to the existing launch UI. Results for notification-only after Hooks cannot rewrite
launcher state; returned data is validated for envelope correctness and otherwise ignored.

The dispatcher invokes endpoints rather than hard-coding Java plugins. The first endpoint adapter
calls `Plugin.onHook`. Future Runtime Providers can supply endpoints that transport the same
envelope over IPC, a remote JVM-object bridge, or an in-process hosting boundary without changing
the dispatcher or process-plan contract.

## Secret Slots

Account credentials and tokens never appear as ordinary plan strings. Sensitive command values use
templates composed from literal and opaque secret segments, for example:

```json
{
  "kind": "template",
  "segments": [
    "--accessToken=",
    { "kind": "secret", "slot": "access-token" }
  ]
}
```

The coordinator owns an out-of-band secret store scoped to one launch dispatch. Every plugin sees
the slot name and may retain, move, or remove the reference. A plugin without `account` permission
cannot resolve it. An eligible plugin with `account` permission receives a scoped secret accessor
and may create or replace slot values through a protected result channel. Protected secret values
are stripped before the ordinary result is passed onward.

Ordinary result fields are checked against all secrets visible to that callback. Returning a secret
as literal payload is rejected, so a privileged plugin cannot accidentally expose a credential to
the next plugin. New or transformed sensitive values must be returned as a protected secret update
and referenced by slot. Immediately before process or script rendering, the coordinator resolves
only the slots still referenced by the final plan. Diagnostics, logs, event snapshots, and the
after-game-launch payload retain opaque references and never resolved values.

Script generation necessarily writes any retained credentials into the user-requested script just
as the current launcher does. Resolution happens only inside the final script renderer and no
resolved script text is sent back through Hook dispatch.

## Subscriber Selection And Ordering

For each dispatch, `PluginManager` takes a stable snapshot without holding its state lock while
plugin code runs. A subscriber must satisfy all of these conditions:

- its lifecycle is loaded and enabled;
- its authoritative manifest declares the dispatched Hook point;
- its current artifact permission decision grants `launcher-hook`.

Runtime compatibility is already a preload requirement. If an otherwise eligible loaded plugin has
no endpoint at dispatch time, the dispatcher treats that as an infrastructure failure instead of
silently skipping a declared Hook.

Subscribers run in dependency topological order so dependencies observe and transform the plan
before their dependents. Unrelated nodes and otherwise equal choices are ordered lexicographically
by canonical plugin ID. The resulting order is deterministic across installations and JVM runs.

Every callback runs with that plugin's class loader as TCCL and under the existing administrative
guard. The dispatcher uses a bounded callback executor and an injectable timeout policy with a
30-second default. A timed-out Java callback is interrupted and its result is discarded; immutable
input snapshots ensure late completion cannot alter committed state. Because interruption cannot
forcibly terminate arbitrary JVM code, the plugin endpoint and class-loader lease remain held until
the callback actually exits, even though the launch operation has already failed or after-Hook
dispatch has moved on. The callback executor uses daemon workers, so a non-cooperative plugin cannot
keep HMCL alive during final shutdown. Runtime Provider milestones may enforce stronger process
termination for external endpoints.

Each transformation is transactional. The dispatcher validates a plugin's complete result against
the Hook policy before making it the input to the next subscriber. No partial object mutation can
survive an exception, timeout, invalid result, or cancellation.

## Failure Semantics

For `before-game-launch`, an exception, timeout, malformed envelope, invalid process plan, secret
violation, or unavailable declared endpoint aborts the launch before any command runs. The error
names the plugin and failure category, logs the underlying exception, and avoids credential data.
A deliberate cancellation uses the plugin-provided reason rather than being reported as a crash.

For `after-game-launch`, failures are isolated. HMCL logs the plugin and category, continues
dispatching to remaining subscribers, releases the after-Hook application lifecycle lease, and
preserves the real game exit result. A timed-out JVM endpoint keeps only its plugin/class-loader
lease until its callback actually exits. An after Hook cannot change the process exit code or
retroactively turn the launch into a failure.

Disabling or uninstalling a plugin during a dispatch does not change that dispatch's subscriber
snapshot. The endpoint and class loader remain leased until its callback actually exits; the
operation stops waiting when its timeout expires, and subsequent dispatches use the new plugin
state.

## Process Termination And Launcher Lifetime

`after-game-launch` means after the directly launched Minecraft process terminates, matching the
existing enum contract. Its data contains:

- the dispatch ID and redacted final process plan;
- process ID when the platform exposes one;
- nullable exit code when no reliable value exists;
- termination kind: normal, nonzero exit, launcher stop, externally killed, or unknown;
- process start and end instants and elapsed milliseconds.

The coordinator attaches exactly one completion observer after successful process creation. A
process-creation failure has no after event because no game process existed. Normal exit, nonzero
exit, and launcher-requested stop all dispatch exactly once.

When launcher visibility is `close` and at least one eligible `after-game-launch` subscriber exists,
HMCL hides its windows but acquires a non-UI lifecycle lease instead of immediately terminating the
application. JavaFX implicit exit is suppressed while the lease is active. After the process has
terminated, all after subscribers have completed or timed out, and other launch leases are empty,
the normal application shutdown path resumes. With no eligible after subscriber, current close
behavior is unchanged.

## External Runtime Boundary

This milestone executes only the built-in Java Runtime endpoint, but its boundaries must remain
usable by all three approved external execution modes:

1. A declarative Core/UI ABI endpoint serializes the versioned event and result data directly.
2. An out-of-process remote JVM/JavaFX bridge may expose separate object handles, but Hook payloads
   still use the neutral envelope and never require those handles.
3. An in-process JNI, GraalVM, .NET Hosting, or native endpoint translates between its language SDK
   and the same value model.

External Runtime Hosts remain independently installable Runtime Provider plugins. HMCL does not
bundle .NET, Python, JavaScript/WASM, or native hosts, and a language plugin package does not embed a
second provider when it can depend on a shared one. Runtime version, Plugin ABI, execution mode,
and platform artifact selection remain independent compatibility dimensions.

## Testing

Development follows red-green-refactor. Focused tests cover:

- immutable process-plan snapshots, structured-to-token rendering, explicit raw-mode replacement,
  environment edits, auxiliary commands, and validation failures;
- backwards compatibility of the default Java `onHook` implementation;
- subscriber filtering by schema, enabled state, manifest declaration, and permission, plus missing
  endpoint failure handling;
- dependency-topological and plugin-ID ordering;
- transactional chaining, cancellation, exception, timeout, malformed result, and TCCL isolation;
- secret visibility, protected updates, literal-secret rejection, slot removal, and final resolution;
- one transformed plan producing equivalent command tokens in direct execution and script rendering;
- cancellation before pre-launch commands and process creation;
- exactly-once after dispatch for each owned termination path and no dispatch on creation failure;
- best-effort after failures and continuation to later subscribers;
- close-visibility background leases and unchanged behavior when no after subscriber exists;
- schema-v4 launch regression and schema-v5 plugins that declare no relevant Hook.

Verification runs the focused HMCLCore and HMCL tests first, then the complete test suite and
Checkstyle. The milestone is not complete until the worktree is clean after its commit and the
remote `next` checks pass.

## Explicitly Deferred Work

- Connecting the remaining download, login, instance-create, mod-install, and settings-load Hooks.
- Store platform artifact matrices and automatic Runtime Provider artifact selection.
- Runtime Provider installation, startup, health, sharing, update, and removal lifecycles.
- Generated cross-language Core/UI SDKs and remote JVM/JavaFX object proxy protocols.
- Patch callback ABI and the Mixin/ASM/ByteBuddy execution engine.
- Concrete .NET, JavaScript/WASM, Python, and native Runtime Host plugins.
- User-facing configuration for the callback timeout; this milestone uses the documented default
  through an injectable internal policy so tests and later settings work do not hard-code timing.
