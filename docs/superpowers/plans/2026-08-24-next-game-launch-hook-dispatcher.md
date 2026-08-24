# HMCL CE Next Game Launch Hook Dispatcher Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Execute schema-v5 `before-game-launch` and `after-game-launch` Hooks against one complete, secret-safe process plan shared by direct launch and script generation.

**Architecture:** HMCLCore prepares, validates, executes, and renders an implementation-neutral `LaunchProcessPlan`; it never depends on the plugin module. HMCL adds an immutable JSON-compatible Hook DOM, deterministic transactional dispatcher, secret store, and game-launch codec. `HMCLGameLauncher` composes both layers, while the existing `ProcessListener` exit path supplies exactly-once after events and a shutdown lease keeps HMCL alive only when an eligible after subscriber needs it.

**Tech Stack:** Java 17, Gson, JUnit 5, JavaFX 17, Gradle 9, PowerShell 5+, Git/GitHub CLI.

---

## Progress Snapshot (2026-08-24)

- Development is paused before Task 9. The `main` and `next` relationship has been normalized.
- Tasks 1-7 are complete on `next`; Task 7 is commit `60c3388` (`Execute before game launch hooks`).
- Task 8 is complete in commit `80abe23` (`Dispatch after game launch hooks`). Its listener/coordinator
  tests, `:HMCLCore:test`, HMCL main/test Checkstyle, and `git diff --check` were re-run immediately
  before the commit and passed.
- HMCL refs at the pause: `main`/`origin/main` = `aa11f6c`, `next` = `60c3388`,
  `origin/next` = `d597972`, merge base = `dbde134`; `main...next` contains 1 main-only and
  29 next-only commits. `next` must absorb committed `main`; `next` must not be merged into stable
  `main`.
- Normalization completed in merge commit `421f67e` (`Merge branch 'main' into next`). The resolved
  tree matches the pre-merge `next` tree because `next` already contained the final `main` behavior.
  After the merge, `main` is an ancestor of `next` and `main...next` contains 0 main-only and 33
  next-only commits. The forced focused HMCL tests, full `:HMCLCore:test`, HMCL main/test Checkstyle,
  translation checks, and `git diff --check origin/next...next` passed.
- The `main-release` worktree has an unrelated uncommitted `HMCL/build.gradle.kts` change. Preserve it
  exactly and merge only the committed `main` ref from the `next` checkout.
- SDK refs are already normalized and clean: default branch `schema-v4` = `3a86706`, future branch
  `schema-v5` = `e11dac2`; both match their remote tracking branches. No SDK ref operation is pending.
- Task 9 remains intentionally paused until development is explicitly resumed.

---

## File And Ownership Map

### HMCLCore launch model

- `HMCLCore/src/main/java/org/jackhuang/hmcl/launch/LaunchExecutionMode.java`: direct-versus-script operation mode.
- `HMCLCore/src/main/java/org/jackhuang/hmcl/launch/LaunchPlanText.java`: immutable literal/template text with opaque secret segments.
- `HMCLCore/src/main/java/org/jackhuang/hmcl/launch/LaunchCommandPlan.java`: authoritative structured-Java or raw command representation.
- `HMCLCore/src/main/java/org/jackhuang/hmcl/launch/LaunchAuxiliaryProcessPlan.java`: pre-launch or post-exit process specification.
- `HMCLCore/src/main/java/org/jackhuang/hmcl/launch/LaunchProcessPlan.java`: complete immutable public launch plan.
- `HMCLCore/src/main/java/org/jackhuang/hmcl/launch/LaunchPreparation.java`: plan plus launcher-private native/encoding resources and scoped secret values.
- `HMCLCore/src/main/java/org/jackhuang/hmcl/launch/LaunchScriptRenderer.java`: platform-specific rendering from a resolved plan.
- `HMCLCore/src/main/java/org/jackhuang/hmcl/launch/DefaultLauncher.java`: preparation and execution pipeline using the new model.

### HMCL public plugin contract

- `HMCL/src/main/java/org/jackhuang/hmcl/plugin/PluginDataValue.java`: immutable JSON-compatible value tree.
- `HMCL/src/main/java/org/jackhuang/hmcl/plugin/PluginDataObject.java`: immutable string-keyed object and copy-on-write helpers.
- `HMCL/src/main/java/org/jackhuang/hmcl/plugin/PluginHookEvent.java`: versioned Hook envelope.
- `HMCL/src/main/java/org/jackhuang/hmcl/plugin/PluginHookResult.java`: unchanged, replacement, or cancellation result.
- `HMCL/src/main/java/org/jackhuang/hmcl/plugin/PluginSecretAccess.java`: permission-aware scoped secret accessor.
- `HMCL/src/main/java/org/jackhuang/hmcl/plugin/Plugin.java`: default `onHook` callback.

### HMCL dispatcher and game integration

- `HMCL/src/main/java/org/jackhuang/hmcl/plugin/PluginHookEndpoint.java`: runtime-neutral callback endpoint.
- `HMCL/src/main/java/org/jackhuang/hmcl/plugin/PluginHookSubscriber.java`: immutable dispatch snapshot entry.
- `HMCL/src/main/java/org/jackhuang/hmcl/plugin/PluginHookDispatchException.java`: categorized, redacted dispatch failure.
- `HMCL/src/main/java/org/jackhuang/hmcl/plugin/PluginHookDispatcher.java`: filtering-independent ordering, timeout, and transactional dispatch.
- `HMCL/src/main/java/org/jackhuang/hmcl/plugin/PluginManager.java`: eligible subscriber snapshot and Java endpoint adapter.
- `HMCL/src/main/java/org/jackhuang/hmcl/plugin/PluginContainer.java`: callback lease count and deferred class-loader close.
- `HMCL/src/main/java/org/jackhuang/hmcl/plugin/PluginAdministrativeGuard.java`: value-returning guarded callback support.
- `HMCL/src/main/java/org/jackhuang/hmcl/plugin/GameLaunchSecretStore.java`: per-launch secret slots and protected updates.
- `HMCL/src/main/java/org/jackhuang/hmcl/plugin/GameLaunchHookCodec.java`: process-plan/event conversion and validation.
- `HMCL/src/main/java/org/jackhuang/hmcl/plugin/GameLaunchHookCoordinator.java`: before/after policy and launch session ownership.
- `HMCL/src/main/java/org/jackhuang/hmcl/game/GameLaunchHookProcessListener.java`: existing-listener composition and exactly-once exit notification.
- `HMCL/src/main/java/org/jackhuang/hmcl/game/HMCLGameLauncher.java`: direct and script Hook composition.
- `HMCL/src/main/java/org/jackhuang/hmcl/ApplicationShutdownCoordinator.java`: pure shutdown request and lease state machine.
- `HMCL/src/main/java/org/jackhuang/hmcl/Launcher.java`: JavaFX shutdown integration.
- `HMCL/src/main/java/org/jackhuang/hmcl/game/LauncherHelper.java`: process-wide coordinator injection and close-mode behavior.

### Tests and SDK

- `HMCLCore/src/test/java/org/jackhuang/hmcl/launch/LaunchProcessPlanTest.java`: command modes, templates, validation, and resolution.
- `HMCLCore/src/test/java/org/jackhuang/hmcl/launch/LaunchScriptRendererTest.java`: direct/script token parity and quoting.
- `HMCL/src/test/java/org/jackhuang/hmcl/plugin/PluginHookContractTest.java`: public DOM and result contract.
- `HMCL/src/test/java/org/jackhuang/hmcl/plugin/PluginHookSubscriberOrderTest.java`: subscriber selection and topological ordering.
- `HMCL/src/test/java/org/jackhuang/hmcl/plugin/PluginHookDispatcherTest.java`: transactional/error/timeout/TCCL behavior.
- `HMCL/src/test/java/org/jackhuang/hmcl/plugin/GameLaunchHookCodecTest.java`: complete process-plan and secret round trips.
- `HMCL/src/test/java/org/jackhuang/hmcl/plugin/GameLaunchHookCoordinatorTest.java`: before cancellation and after notification policy.
- `HMCL/src/test/java/org/jackhuang/hmcl/game/GameLaunchHookProcessListenerTest.java`: exit metadata and delegate preservation.
- `HMCL/src/test/java/org/jackhuang/hmcl/ApplicationShutdownCoordinatorTest.java`: deferred shutdown lease behavior.
- `C:/Users/ACX/Documents/Plugins/HMCL-CE-Plugin-SDK/references/hmcl-plugin-api/*`: schema-v5 public API snapshots only.
- `C:/Users/ACX/Documents/Plugins/HMCL-CE-Plugin-SDK/examples/java-launch-hook/*`: executable Java Hook example.

## Scope And Style Locks

- Do not connect the other ten declared Hook points.
- Do not implement Store artifact matrices, Runtime Provider lifecycle, remote object proxies, Patch execution, or a concrete external Runtime Host.
- Preserve schema-v4 behavior and the SDK `schema-v4` branch byte-for-byte.
- Do not expose `LaunchOptions`, `ManagedProcess`, JavaFX, Gson, or class-loader objects through Hook event data.
- Every new Java source file and every nested declared type uses `@NotNullByDefault`; every nullable position is explicit; immutable arrays/collections use `@Unmodifiable` or `@UnmodifiableView`; every class, field, method, constructor, and enum constant added or modified has `///` Markdown documentation.
- Each RED command must fail for the asserted missing behavior, not from an unrelated compilation or environment failure.

### Task 1: Add The Immutable Public Hook Value Contract

**Files:**
- Create: `HMCL/src/test/java/org/jackhuang/hmcl/plugin/PluginHookContractTest.java`
- Create: `HMCL/src/main/java/org/jackhuang/hmcl/plugin/PluginDataValue.java`
- Create: `HMCL/src/main/java/org/jackhuang/hmcl/plugin/PluginDataObject.java`
- Create: `HMCL/src/main/java/org/jackhuang/hmcl/plugin/PluginHookEvent.java`
- Create: `HMCL/src/main/java/org/jackhuang/hmcl/plugin/PluginHookResult.java`
- Create: `HMCL/src/main/java/org/jackhuang/hmcl/plugin/PluginSecretAccess.java`
- Modify: `HMCL/src/main/java/org/jackhuang/hmcl/plugin/Plugin.java`

- [x] **Step 1: Write the contract tests first**

Test immutable object replacement, recursive value validation, unchanged/replacement/cancel result factories, cancellation code validation, event metadata, denied secret access, and the default plugin callback:

```java
@Test
public void dataObjectsAreImmutableAndCopyOnWrite() {
    PluginDataObject original = PluginDataObject.of(Map.of(
            "mode", PluginDataValue.string("structured-java"),
            "arguments", PluginDataValue.array(List.of(PluginDataValue.string("-Xmx2G")))
    ));

    PluginDataObject changed = original.with("mode", PluginDataValue.string("raw"));

    assertEquals("structured-java", original.requireString("mode"));
    assertEquals("raw", changed.requireString("mode"));
    assertThrows(UnsupportedOperationException.class,
            () -> changed.values().put("bad", PluginDataValue.nullValue()));
}

@Test
public void defaultHookCallbackPreservesPayload() {
    Plugin plugin = new NoOpPlugin();
    PluginHookResult result = plugin.onHook(event(PluginHookPoint.BEFORE_GAME_LAUNCH));
    assertEquals(PluginHookResult.Action.UNCHANGED, result.action());
}
```

- [x] **Step 2: Run the test and verify RED**

```powershell
.\gradlew.bat :HMCL:test --tests "org.jackhuang.hmcl.plugin.PluginHookContractTest" --no-daemon --stacktrace
```

Expected: test compilation fails because the five public Hook contract types and `Plugin.onHook` do not exist.

- [x] **Step 3: Implement the minimal neutral DOM and callback shapes**

Use a sealed value hierarchy whose public factories copy every input collection:

```java
@NotNullByDefault
public sealed interface PluginDataValue permits PluginDataValue.NullValue,
        PluginDataValue.BooleanValue, PluginDataValue.NumberValue, PluginDataValue.StringValue,
        PluginDataValue.ArrayValue, PluginDataValue.ObjectValue {
    static PluginDataValue nullValue() { return NullValue.INSTANCE; }
    static PluginDataValue bool(boolean value) { return new BooleanValue(value); }
    static PluginDataValue number(BigDecimal value) { return new NumberValue(value); }
    static PluginDataValue string(String value) { return new StringValue(value); }
    static PluginDataValue array(List<PluginDataValue> values) { return new ArrayValue(values); }
    static PluginDataValue object(PluginDataObject value) { return new ObjectValue(value); }
}
```

`PluginDataObject` must expose `of`, `empty`, `values`, `get`, `requireBoolean`, `requireNumber`,
`requireString`, `requireObject`, `requireArray`, `with`, and `without`. `PluginHookEvent` carries `contractVersion`, `dispatchId`,
`point`, `occurredAt`, immutable `data`, and `PluginSecretAccess`. `PluginHookResult` uses:

```java
public enum Action { UNCHANGED, REPLACE, CANCEL }

public static PluginHookResult unchanged();
public static PluginHookResult replace(PluginDataObject data);
public static PluginHookResult replace(PluginDataObject data, Map<String, String> protectedSecrets);
public static PluginHookResult cancel(String reasonCode, String message);
```

Reject blank/non-kebab cancellation codes, reject protected secret updates on unchanged/cancel
results, copy the protected map, and redact its values from `toString`. Add this binary-compatible
method to `Plugin`:

```java
/// Handles one manifest-declared Hook event.
/// @param event immutable event envelope
/// @return transactional Hook result
default PluginHookResult onHook(PluginHookEvent event) {
    return PluginHookResult.unchanged();
}
```

- [x] **Step 4: Run tests and Checkstyle GREEN**

```powershell
.\gradlew.bat :HMCL:test --tests "org.jackhuang.hmcl.plugin.PluginHookContractTest" --no-daemon --stacktrace
.\gradlew.bat :HMCL:checkstyleMain :HMCL:checkstyleTest --no-daemon --stacktrace
```

Expected: both commands succeed; reflection assertions confirm no public Hook member mentions Gson,
JavaFX, `LaunchOptions`, or `ManagedProcess`.

- [x] **Step 5: Commit the public Hook contract**

```powershell
git add HMCL/src/main/java/org/jackhuang/hmcl/plugin/Plugin.java
git add HMCL/src/main/java/org/jackhuang/hmcl/plugin/PluginDataValue.java
git add HMCL/src/main/java/org/jackhuang/hmcl/plugin/PluginDataObject.java
git add HMCL/src/main/java/org/jackhuang/hmcl/plugin/PluginHookEvent.java
git add HMCL/src/main/java/org/jackhuang/hmcl/plugin/PluginHookResult.java
git add HMCL/src/main/java/org/jackhuang/hmcl/plugin/PluginSecretAccess.java
git add HMCL/src/test/java/org/jackhuang/hmcl/plugin/PluginHookContractTest.java
git commit -m "Add neutral plugin hook value contract"
```

### Task 2: Model A Complete Immutable Launch Process Plan

**Files:**
- Create: `HMCLCore/src/test/java/org/jackhuang/hmcl/launch/LaunchProcessPlanTest.java`
- Create: `HMCLCore/src/main/java/org/jackhuang/hmcl/launch/LaunchExecutionMode.java`
- Create: `HMCLCore/src/main/java/org/jackhuang/hmcl/launch/LaunchPlanText.java`
- Create: `HMCLCore/src/main/java/org/jackhuang/hmcl/launch/LaunchCommandPlan.java`
- Create: `HMCLCore/src/main/java/org/jackhuang/hmcl/launch/LaunchAuxiliaryProcessPlan.java`
- Create: `HMCLCore/src/main/java/org/jackhuang/hmcl/launch/LaunchProcessPlan.java`

- [x] **Step 1: Write plan-mode, rendering, and validation tests**

Cover structured Java rendering, raw replacement, template resolution, environment set/unset,
auxiliary commands, immutable copies, and every invalid state:

```java
@Test
public void structuredAndRawModesHaveOneAuthoritativeSource() {
    LaunchCommandPlan structured = LaunchCommandPlan.structuredJava(
            List.of(LaunchPlanText.literal("nice"), LaunchPlanText.literal("-n"), LaunchPlanText.literal("1")),
            LaunchPlanText.literal("/jdk/bin/java"),
            List.of(LaunchPlanText.literal("-Xmx2G")),
            List.of(LaunchPlanText.literal("a.jar"), LaunchPlanText.literal("b.jar")),
            LaunchPlanText.literal("net.minecraft.client.main.Main"),
            List.of(LaunchPlanText.literal("--username"), LaunchPlanText.literal("Alex"))
    );

    assertEquals(List.of("nice", "-n", "1", "/jdk/bin/java", "-Xmx2G", "-cp",
            "a.jar" + File.pathSeparator + "b.jar", "net.minecraft.client.main.Main",
            "--username", "Alex"), structured.resolve(slot -> null));

    LaunchCommandPlan raw = structured.replaceWithRawCommand(List.of(
            LaunchPlanText.literal("custom-host"), LaunchPlanText.literal("--launch")));
    assertEquals(LaunchCommandPlan.Mode.RAW, raw.mode());
    assertEquals(List.of("custom-host", "--launch"), raw.resolve(slot -> null));
}
```

Validation tests reject blank executable/main class/raw command, null/NUL text, invalid environment
keys, contradictory mode fields, unknown secret slots, and non-absolute working directories.

- [x] **Step 2: Run the model test and verify RED**

```powershell
.\gradlew.bat :HMCLCore:test --tests "org.jackhuang.hmcl.launch.LaunchProcessPlanTest" --no-daemon --stacktrace
```

Expected: test compilation fails because the launch-plan types do not exist.

- [x] **Step 3: Implement literal/template text and command modes**

`LaunchPlanText` stores an immutable ordered segment list:

```java
public sealed interface Segment permits LiteralSegment, SecretSegment { }
public record LiteralSegment(String value) implements Segment { }
public record SecretSegment(String slot) implements Segment { }

public static LaunchPlanText literal(String value);
public static LaunchPlanText template(List<Segment> segments);
public @Unmodifiable Set<String> secretSlots();
public String resolve(Function<String, @Nullable String> resolver);
```

`LaunchCommandPlan` has mode `STRUCTURED_JAVA` or `RAW`. Structured mode owns `prefixTokens`,
`javaExecutable`, `jvmArguments`, `classpathEntries`, `mainClass`, and `gameArguments`; raw mode owns
only `rawCommand`. Its constructor validates exclusivity, all accessors return immutable copies, and
`replaceWithRawCommand` is the only mode switch.

- [x] **Step 4: Implement the complete process plan**

`LaunchProcessPlan` owns:

```java
LaunchExecutionMode executionMode;
LaunchCommandPlan command;
Path workingDirectory;
boolean inheritEnvironment;
Map<String, LaunchPlanText> environmentSet;
Set<String> environmentUnset;
@Nullable LaunchAuxiliaryProcessPlan preLaunch;
@Nullable LaunchAuxiliaryProcessPlan postExit;
String launcherVisibility;
boolean inheritIo;
boolean daemonMonitors;
```

Provide copy-on-write `withCommand`, `withWorkingDirectory`, `withEnvironment`,
`withPreLaunch`, `withPostExit`, and `withProcessBehavior` methods. `validate(Set<String>
availableSecretSlots)` traverses every text value and produces a path-specific
`IllegalArgumentException` without resolving secrets.

- [x] **Step 5: Run model tests and style checks GREEN**

```powershell
.\gradlew.bat :HMCLCore:test --tests "org.jackhuang.hmcl.launch.LaunchProcessPlanTest" --no-daemon --stacktrace
.\gradlew.bat :HMCLCore:checkstyleMain :HMCLCore:checkstyleTest --no-daemon --stacktrace
```

Expected: both commands succeed and mutation attempts against every returned collection throw.

- [x] **Step 6: Commit the launch-plan model**

```powershell
git add HMCLCore/src/main/java/org/jackhuang/hmcl/launch/LaunchExecutionMode.java
git add HMCLCore/src/main/java/org/jackhuang/hmcl/launch/LaunchPlanText.java
git add HMCLCore/src/main/java/org/jackhuang/hmcl/launch/LaunchCommandPlan.java
git add HMCLCore/src/main/java/org/jackhuang/hmcl/launch/LaunchAuxiliaryProcessPlan.java
git add HMCLCore/src/main/java/org/jackhuang/hmcl/launch/LaunchProcessPlan.java
git add HMCLCore/src/test/java/org/jackhuang/hmcl/launch/LaunchProcessPlanTest.java
git commit -m "Model complete game launch process plans"
```

### Task 3: Unify DefaultLauncher Direct And Script Pipelines

**Files:**
- Create: `HMCLCore/src/main/java/org/jackhuang/hmcl/launch/LaunchPreparation.java`
- Create: `HMCLCore/src/main/java/org/jackhuang/hmcl/launch/LaunchScriptRenderer.java`
- Create: `HMCLCore/src/test/java/org/jackhuang/hmcl/launch/LaunchScriptRendererTest.java`
- Modify: `HMCLCore/src/main/java/org/jackhuang/hmcl/launch/DefaultLauncher.java`

- [x] **Step 1: Write renderer parity tests**

Build one resolved plan containing prefix/Java/JVM/classpath/main/game tokens, environment set/unset,
pre-launch, and post-exit commands. Assert each supported renderer emits those exact values once:

```java
@ParameterizedTest
@EnumSource(LaunchScriptRenderer.Kind.class)
public void scriptUsesTheSameResolvedPlanAsDirectExecution(LaunchScriptRenderer.Kind kind,
                                                            @TempDir Path directory) throws IOException {
    LaunchProcessPlan plan = completePlan(kind, directory);
    List<String> directTokens = plan.command().resolve(this::resolveSecret);
    String script = LaunchScriptRenderer.renderToString(kind, plan, this::resolveSecret);

    directTokens.forEach(token -> assertTrue(script.contains(
            LaunchScriptRenderer.quoteForTest(kind, token)), token));
    assertEquals(1, occurrences(script, "PRE_SENTINEL"));
    assertEquals(1, occurrences(script, "POST_SENTINEL"));
}
```

Add BAT, PowerShell, Bash, and macOS command tests for quoting, `APPDATA`, unset variables, working
directory, long Windows commands, BOM, executable permission, and secret resolution only at render.

- [x] **Step 2: Run renderer tests and verify RED**

```powershell
.\gradlew.bat :HMCLCore:test --tests "org.jackhuang.hmcl.launch.LaunchScriptRendererTest" --no-daemon --stacktrace
```

Expected: test compilation fails because `LaunchPreparation` and `LaunchScriptRenderer` do not exist.

- [x] **Step 3: Extract the script renderer without changing behavior**

Move the current BAT/PowerShell/Bash quoting branches from `DefaultLauncher.makeLaunchScript` into
`LaunchScriptRenderer`. Its production API is:

```java
public static void render(
        Path scriptFile,
        LaunchProcessPlan plan,
        Function<String, @Nullable String> secretResolver
) throws IOException;
```

Keep `quoteForTest` package-private. Render the resolved command, environment, and auxiliary process
specifications exclusively from `LaunchProcessPlan`; never read `LaunchOptions` in the renderer.
Provide package-private `renderToString(Kind, LaunchProcessPlan, Function)` and `quoteForTest` helpers
for deterministic tests; the public file-writing method selects `Kind` from the target extension.

- [x] **Step 4: Refactor preparation and execution around one plan**

Replace private `Command` with immutable `LaunchPreparation`, containing:

```java
LaunchProcessPlan plan;
Map<String, String> secrets;
@Nullable Path temporaryNativeLink;
Path nativeFolder;
Path javaNativeFolder;
Charset outputEncoding;
```

`LaunchPreparation` is a final class rather than a record so `toString` can redact the secret map.
It copies secrets on construction, exposes immutable snapshots, and provides `withPlan` and
`withSecrets` methods used by the Hook coordinator without exposing native resources through event data.

Add protected methods:

```java
protected LaunchPreparation prepareLaunch(LaunchExecutionMode mode) throws IOException;
protected ManagedProcess executeLaunch(LaunchPreparation preparation, @Nullable ProcessListener listener)
        throws IOException, InterruptedException;
protected ManagedProcess executeLaunch(LaunchPreparation preparation, @Nullable ProcessListener listener,
                                       Runnable exitCleanup) throws IOException, InterruptedException;
protected void renderLaunchScript(LaunchPreparation preparation, Path scriptFile) throws IOException;
```

Split command generation into prefix, Java executable, JVM arguments, classpath entries, main
class, and game arguments while preserving their current final order. Convert every occurrence of
`authInfo.getAccessToken()` inside a generated token into an `access-token` secret segment and keep
the value only in `LaunchPreparation.secrets()`. Move native-link creation, native decompression,
Log4j extraction, and process creation after preparation so a future before Hook can cancel first.

`launch()` becomes prepare-direct then execute; `makeLaunchScript()` becomes prepare-script then
render. `ProcessBuilder` consumes the resolved plan, applies inherited environment plus set/unset,
runs the resolved pre-command before process creation, and runs the resolved post-command from the
existing exit waiter. The three-argument execution overload invokes `exitCleanup` in a `finally`
block after the ordinary listener and post-exit command complete; the two-argument overload supplies
a no-op cleanup and preserves existing callers.

- [x] **Step 5: Run focused and regression tests GREEN**

```powershell
.\gradlew.bat :HMCLCore:test --tests "org.jackhuang.hmcl.launch.LaunchProcessPlanTest" --tests "org.jackhuang.hmcl.launch.LaunchScriptRendererTest" --no-daemon --stacktrace
.\gradlew.bat :HMCLCore:test --no-daemon --stacktrace
.\gradlew.bat :HMCLCore:checkstyleMain :HMCLCore:checkstyleTest --no-daemon --stacktrace
```

Expected: all commands succeed; the focused tests prove direct and script paths use one resolved
plan and no test/log representation includes the access token.

- [x] **Step 6: Commit the unified launcher pipeline**

```powershell
git add HMCLCore/src/main/java/org/jackhuang/hmcl/launch/DefaultLauncher.java
git add HMCLCore/src/main/java/org/jackhuang/hmcl/launch/LaunchPreparation.java
git add HMCLCore/src/main/java/org/jackhuang/hmcl/launch/LaunchScriptRenderer.java
git add HMCLCore/src/test/java/org/jackhuang/hmcl/launch/LaunchScriptRendererTest.java
git commit -m "Unify direct and script launch plans"
```

### Task 4: Encode Launch Plans And Protect Secret Slots

**Files:**
- Create: `HMCL/src/test/java/org/jackhuang/hmcl/plugin/GameLaunchHookCodecTest.java`
- Create: `HMCL/src/main/java/org/jackhuang/hmcl/plugin/GameLaunchSecretStore.java`
- Create: `HMCL/src/main/java/org/jackhuang/hmcl/plugin/GameLaunchHookCodec.java`

- [x] **Step 1: Write full round-trip and secret isolation tests**

```java
@Test
public void planRoundTripPreservesEveryMutableField() {
    LaunchProcessPlan original = completeStructuredPlan();
    PluginDataObject encoded = GameLaunchHookCodec.encodeBefore(original, immutableMetadata());
    LaunchProcessPlan decoded = GameLaunchHookCodec.decodeBefore(encoded, secretStore().slots());
    assertEquals(original, decoded);
}

@Test
public void privilegedLiteralSecretIsRejectedBeforeNextSubscriber() {
    GameLaunchSecretStore store = new GameLaunchSecretStore(Map.of("access-token", "top-secret"));
    PluginDataObject leaked = PluginDataObject.of(Map.of(
            "plan", PluginDataValue.string("--token=top-secret")));
    assertThrows(PluginHookDispatchException.class,
            () -> store.validateOrdinaryData("dev.example.plugin", leaked, true));
}
```

Cover structured/raw plans, templates, environment unset, auxiliary commands, immutable metadata
rewrite rejection, denied versus granted `account` access, protected secret creation/update, unknown
slot rejection, removed slots, substring leakage, and redacted exception text.

- [x] **Step 2: Run codec tests and verify RED**

```powershell
.\gradlew.bat :HMCL:test --tests "org.jackhuang.hmcl.plugin.GameLaunchHookCodecTest" --no-daemon --stacktrace
```

Expected: test compilation fails because the codec and store do not exist.

- [x] **Step 3: Implement the scoped secret store**

Use synchronized package-private state with immutable snapshots:

```java
PluginSecretAccess accessor(boolean accountGranted);
@Unmodifiable Set<String> slots();
void validateOrdinaryData(String pluginId, PluginDataObject data, boolean accountGranted);
void applyProtectedUpdates(String pluginId, Map<String, String> updates, boolean accountGranted);
String resolve(String slot);
```

The denied accessor throws `PluginPermissionException` without revealing slot existence. The
granted accessor returns a copied string. Protected updates require `account`, canonical slot names,
non-null values, and a matching reference in the returned plan. Scan all ordinary string leaves for
every secret visible to that callback and reject exact or substring disclosure. Exception messages
contain plugin ID and data path but never the offending value.

- [x] **Step 4: Implement a complete symmetric codec**

The before payload has immutable `metadata` and mutable `plan`. Encode/decode all process-plan
fields with explicit `contractVersion: 1`; represent `LaunchPlanText` as literal or template/segment
objects. Decode by allowlist, reject unknown contract versions, require every field and expected
value kind, preserve unknown future fields only outside version 1, and invoke
`LaunchProcessPlan.validate(secretSlots)` before commit. The after encoder contains the redacted
final plan plus PID, nullable exit code, exit kind, start/end ISO instants, and elapsed milliseconds.

- [x] **Step 5: Run codec tests and Checkstyle GREEN**

```powershell
.\gradlew.bat :HMCL:test --tests "org.jackhuang.hmcl.plugin.GameLaunchHookCodecTest" --no-daemon --stacktrace
.\gradlew.bat :HMCL:checkstyleMain :HMCL:checkstyleTest --no-daemon --stacktrace
```

Expected: both commands succeed and a recursive assertion finds no access token in encoded before or
after data.

- [x] **Step 6: Commit codec and secret isolation**

```powershell
git add HMCL/src/main/java/org/jackhuang/hmcl/plugin/GameLaunchSecretStore.java
git add HMCL/src/main/java/org/jackhuang/hmcl/plugin/GameLaunchHookCodec.java
git add HMCL/src/test/java/org/jackhuang/hmcl/plugin/GameLaunchHookCodecTest.java
git commit -m "Protect secrets in game launch hook data"
```

### Task 5: Snapshot And Order Eligible Hook Subscribers

**Files:**
- Create: `HMCL/src/test/java/org/jackhuang/hmcl/plugin/PluginHookSubscriberOrderTest.java`
- Create: `HMCL/src/main/java/org/jackhuang/hmcl/plugin/PluginHookEndpoint.java`
- Create: `HMCL/src/main/java/org/jackhuang/hmcl/plugin/PluginHookSubscriber.java`
- Modify: `HMCL/src/main/java/org/jackhuang/hmcl/plugin/PluginManager.java`
- Modify: `HMCL/src/main/java/org/jackhuang/hmcl/plugin/PluginContainer.java`
- Modify: `HMCL/src/main/java/org/jackhuang/hmcl/plugin/PluginAdministrativeGuard.java`

- [x] **Step 1: Write subscriber snapshot tests**

Create isolated prepared containers for a dependency diamond plus schema-v4, disabled, undeclared,
and permission-revoked plugins. Assert:

```java
assertEquals(List.of("dev.test.base", "dev.test.alpha", "dev.test.beta", "dev.test.final"),
        manager.snapshotHookSubscribers(PluginHookPoint.BEFORE_GAME_LAUNCH).stream()
                .map(PluginHookSubscriber::pluginId)
                .toList());
```

Also assert a permission revocation immediately removes a subscriber, a grant for different bytes
does not authorize the loaded artifact, and snapshot iteration does not hold `stateLock` during an
endpoint callback. `hasEligibleHookSubscriber(point)` performs the same eligibility predicate without
acquiring callback leases and supports the close-mode lifetime decision made before process creation.

- [x] **Step 2: Run ordering tests and verify RED**

```powershell
.\gradlew.bat :HMCL:test --tests "org.jackhuang.hmcl.plugin.PluginHookSubscriberOrderTest" --no-daemon --stacktrace
```

Expected: test compilation fails because endpoint/subscriber types and the snapshot method do not exist.

- [x] **Step 3: Add a runtime-neutral endpoint and guarded value callback**

```java
@FunctionalInterface
interface PluginHookEndpoint {
    PluginHookResult invoke(PluginHookEvent event) throws Exception;
}

final class PluginHookSubscriber implements AutoCloseable {
    PluginHookSubscriber(String pluginId, Set<String> dependencyIds,
                         Set<PluginPermission> permissions,
                         PluginHookEndpoint endpoint, Runnable releaseLease);
    String pluginId();
    @Unmodifiable Set<String> dependencyIds();
    @Unmodifiable Set<PluginPermission> permissions();
    PluginHookEndpoint endpoint();
    @Override public void close();
}
```

Add `PluginAdministrativeGuard.callPluginCallback(Callable<T>)` and a matching package-private
`PluginManager.runPluginCallback(ClassLoader, Callable<T>)`. The Java endpoint invokes
`container.getPlugin().onHook(event)` through that path, so TCCL and the administrative guard match
all existing lifecycle callbacks.

`PluginContainer.acquireHookLease()` increments a synchronized callback count and supplies the
idempotent release action used by `PluginHookSubscriber`. `closeClassLoader()` closes immediately
when the count is zero; otherwise it records a pending close, and the last lease release closes the
URLClassLoader. The close path logs an `IOException` without throwing into plugin callback threads.
Add tests proving an unload request cannot close a class loader while a Hook lease is active and
that the last release performs the deferred close exactly once.

- [x] **Step 4: Implement deterministic snapshot selection and topological sorting**

Under `stateLock.readLock`, copy only containers that are loaded, enabled, schema v5, declare the
point, and whose exact loaded `PluginContext.getGrantedPermissions()` contains `LAUNCHER_HOOK`.
Release the lock before sorting or invoking code. Sort with Kahn's algorithm: dependencies before
dependents and a `PriorityQueue<String>` for every unrelated ready node. A missing endpoint for an
otherwise eligible loaded plugin is represented by an endpoint that throws a categorized
infrastructure error; it is never silently filtered. Acquire each container lease while taking the
snapshot. If snapshot construction or sorting fails, close every lease already acquired.

- [x] **Step 5: Run ordering/lifecycle tests and Checkstyle GREEN**

```powershell
.\gradlew.bat :HMCL:test --tests "org.jackhuang.hmcl.plugin.PluginHookSubscriberOrderTest" --tests "org.jackhuang.hmcl.plugin.PluginManagerContextClassLoaderTest" --tests "org.jackhuang.hmcl.plugin.PluginManagerLifecycleStateTest" --no-daemon --stacktrace
.\gradlew.bat :HMCL:checkstyleMain :HMCL:checkstyleTest --no-daemon --stacktrace
```

Expected: both commands succeed and the established enable/disable dependency behavior is unchanged.

- [x] **Step 6: Commit subscriber snapshots**

```powershell
git add HMCL/src/main/java/org/jackhuang/hmcl/plugin/PluginAdministrativeGuard.java
git add HMCL/src/main/java/org/jackhuang/hmcl/plugin/PluginHookEndpoint.java
git add HMCL/src/main/java/org/jackhuang/hmcl/plugin/PluginHookSubscriber.java
git add HMCL/src/main/java/org/jackhuang/hmcl/plugin/PluginContainer.java
git add HMCL/src/main/java/org/jackhuang/hmcl/plugin/PluginManager.java
git add HMCL/src/test/java/org/jackhuang/hmcl/plugin/PluginHookSubscriberOrderTest.java
git commit -m "Order eligible plugin hook subscribers"
```

### Task 6: Dispatch Hooks Transactionally With Timeout Isolation

**Files:**
- Create: `HMCL/src/test/java/org/jackhuang/hmcl/plugin/PluginHookDispatcherTest.java`
- Create: `HMCL/src/main/java/org/jackhuang/hmcl/plugin/PluginHookDispatchException.java`
- Create: `HMCL/src/main/java/org/jackhuang/hmcl/plugin/PluginHookDispatcher.java`

- [x] **Step 1: Write dispatcher behavior tests**

Use direct subscriber doubles rather than Plugin Manager. Cover ordered chaining, unchanged,
replacement, cancellation, invalid cancellation on after, exception, null result, malformed data,
missing endpoint, TCCL, timeout interruption, late-result discard, plugin lease retention, before
fail-fast, and after best-effort continuation:

```java
@Test
public void beforeDispatchCommitsOnlyValidatedCompleteResults() throws Exception {
    List<String> observed = new ArrayList<>();
    PluginHookDispatcher dispatcher = dispatcher(Duration.ofSeconds(1), subscribers(
            endpoint("dev.test.a", event -> replaceName(event, "A", observed)),
            endpoint("dev.test.b", event -> replaceName(event, "B", observed))));

    PluginDataObject result = dispatcher.dispatchBefore(
            PluginHookPoint.BEFORE_GAME_LAUNCH, eventWithName("initial"), validatingPolicy());

    assertEquals(List.of("initial", "A"), observed);
    assertEquals("B", result.requireString("name"));
}
```

- [x] **Step 2: Run dispatcher tests and verify RED**

```powershell
.\gradlew.bat :HMCL:test --tests "org.jackhuang.hmcl.plugin.PluginHookDispatcherTest" --no-daemon --stacktrace
```

Expected: test compilation fails because dispatcher and categorized exception types do not exist.

- [x] **Step 3: Implement the dispatcher and injected policy**

The constructor accepts a daemon `ExecutorService`, `Duration timeout`, `Clock`, and subscriber
source. Production uses a bounded executor and 30 seconds. Use these package-private generic policy
shapes so game-launch validation and secret staging remain outside the dispatcher:

```java
interface Policy {
    PluginHookEvent eventFor(PluginHookSubscriber subscriber, PluginDataObject currentData);
    Candidate validate(PluginHookSubscriber subscriber, PluginDataObject currentData,
                       PluginHookResult result) throws PluginHookDispatchException;
    boolean cancellationAllowed();
    void reportAfterFailure(PluginHookSubscriber subscriber, PluginHookDispatchException failure);
}

record Candidate(PluginDataObject data, Runnable commit) { }

PluginDataObject dispatchBefore(PluginHookPoint point, PluginDataObject initialData, Policy policy)
        throws PluginHookDispatchException;
void dispatchAfter(PluginHookPoint point, PluginDataObject data, Policy policy);
```

`validate` performs all checks and builds a staged secret-store snapshot without mutating committed
state. The dispatcher calls `Candidate.commit()` only after validation succeeds; `commit` is a
prevalidated no-throw replacement. Before dispatch algorithm:

```text
snapshot subscribers
for each subscriber in deterministic order
    build a fresh event and permission-specific secret accessor
    invoke endpoint through Future.get(timeout)
    on timeout: cancel(true), discard any late result, retain endpoint lease until Future completes
    validate action and protected secret channel
    unchanged: keep current data
    replace: validate complete candidate, then atomically commit data and secret updates
    cancel: throw the dedicated cancellation exception immediately
return committed data
```

After dispatch uses the same invocation path but logs/collects failures and continues. Define stable
failure categories `EXCEPTION`, `TIMEOUT`, `INVALID_RESULT`, `CANCELLED`, and `MISSING_ENDPOINT`.
`PluginHookDispatchException.getMessage()` contains point/plugin/category only; the cause is retained
for internal logging.

Wrap each submitted endpoint in `try/finally { subscriber.close(); }`. On timeout, call
`future.cancel(true)` to interrupt but do not release a running subscriber from the timeout thread;
its wrapper releases only when plugin code really exits. Use an `AtomicBoolean started` and
idempotent subscriber close so a task cancelled before it starts releases immediately. A before
failure or cancellation closes every subscriber not yet submitted. The outer dispatch `finally`
also closes untouched entries, preventing snapshot leases from escaping any path.

- [x] **Step 4: Run dispatcher tests and Checkstyle GREEN**

```powershell
.\gradlew.bat :HMCL:test --tests "org.jackhuang.hmcl.plugin.PluginHookDispatcherTest" --no-daemon --stacktrace
.\gradlew.bat :HMCL:checkstyleMain :HMCL:checkstyleTest --no-daemon --stacktrace
```

Expected: both commands succeed, timeout tests complete under two seconds, and the non-cooperative
callback runs on a daemon worker without mutating committed output.

- [x] **Step 5: Commit transactional dispatch**

```powershell
git add HMCL/src/main/java/org/jackhuang/hmcl/plugin/PluginHookDispatchException.java
git add HMCL/src/main/java/org/jackhuang/hmcl/plugin/PluginHookDispatcher.java
git add HMCL/src/test/java/org/jackhuang/hmcl/plugin/PluginHookDispatcherTest.java
git commit -m "Dispatch plugin hooks transactionally"
```

### Task 7: Coordinate Before-Game-Launch Transformations

**Files:**
- Create: `HMCL/src/test/java/org/jackhuang/hmcl/plugin/GameLaunchHookCoordinatorTest.java`
- Create: `HMCL/src/main/java/org/jackhuang/hmcl/plugin/GameLaunchHookCoordinator.java`
- Modify: `HMCL/src/main/java/org/jackhuang/hmcl/game/HMCLGameLauncher.java`
- Modify: `HMCL/src/main/java/org/jackhuang/hmcl/plugin/PluginHookPoint.java`

- [x] **Step 1: Write before-policy coordinator tests**

Test no-subscriber identity, structured edit, raw replacement, environment/working-directory/
pre/post/visibility edits, sequential plugin transforms, permission-specific secret access, script
mode, cancellation, invalid result, and cancellation before all launcher side effects:

```java
@Test
public void cancellationReturnsNoExecutablePreparation() {
    GameLaunchHookCoordinator coordinator = coordinator(cancel("policy-denied", "Launch denied"));
    LaunchPreparation preparation = preparationWithSideEffectProbe();

    PluginHookDispatchException failure = assertThrows(PluginHookDispatchException.class,
            () -> coordinator.beforeLaunch(preparation, metadata()));

    assertEquals(PluginHookDispatchException.Category.CANCELLED, failure.category());
    assertEquals(0, sideEffects.startedProcesses());
    assertEquals(0, sideEffects.startedAuxiliaryCommands());
}
```

- [x] **Step 2: Run coordinator tests and verify RED**

```powershell
.\gradlew.bat :HMCL:test --tests "org.jackhuang.hmcl.plugin.GameLaunchHookCoordinatorTest" --no-daemon --stacktrace
```

Expected: test compilation fails because the coordinator does not exist.

- [x] **Step 3: Implement a launch-scoped coordinator session**

`beforeLaunch` creates a UUID dispatch ID, captures start `Instant`, initializes
`GameLaunchSecretStore` from `LaunchPreparation.secrets()`, encodes immutable metadata, runs
`BEFORE_GAME_LAUNCH`, decodes/validates the committed plan, and returns a `LaunchSession` containing
the transformed preparation, dispatch ID, start instant, redacted final plan, secret store, and a
boolean indicating eligible after subscribers. The coordinator never mutates the caller's
preparation.

Update `PluginHookPoint` documentation to state that both game-launch points are now executable and
the other ten remain declaration-only.

- [x] **Step 4: Compose the coordinator in HMCLGameLauncher**

Add a package-private injectable constructor for tests and preserve public constructors by using the
process-wide coordinator. Override direct and script methods as:

```java
@Override
public ManagedProcess launch() throws IOException, InterruptedException {
    generateOptionsTxt();
    LaunchSession session = hookCoordinator.beforeLaunch(
            prepareLaunch(LaunchExecutionMode.DIRECT), launchMetadata());
    return executeLaunch(session.preparation(), session.processListener(listener), session::finishExit);
}

@Override
public void makeLaunchScript(Path scriptFile) throws IOException {
    generateOptionsTxt();
    LaunchSession session = hookCoordinator.beforeLaunch(
            prepareLaunch(LaunchExecutionMode.SCRIPT), launchMetadata());
    renderLaunchScript(session.preparation(), scriptFile);
}
```

Translate Hook cancellation into an `IOException` subtype whose message is shown by the existing
launch task. Script sessions never allocate an after observer or shutdown lease.

- [x] **Step 5: Run before integration tests GREEN**

```powershell
.\gradlew.bat :HMCL:test --tests "org.jackhuang.hmcl.plugin.GameLaunchHookCoordinatorTest" --no-daemon --stacktrace
.\gradlew.bat :HMCLCore:test --tests "org.jackhuang.hmcl.launch.LaunchScriptRendererTest" --no-daemon --stacktrace
.\gradlew.bat :HMCL:checkstyleMain :HMCL:checkstyleTest --no-daemon --stacktrace
```

Expected: all commands succeed; direct and script sessions consume the coordinator's returned plan,
and cancellation happens before native extraction, auxiliary commands, or process creation.

- [x] **Step 6: Commit before-game-launch execution**

```powershell
git add HMCL/src/main/java/org/jackhuang/hmcl/plugin/GameLaunchHookCoordinator.java
git add HMCL/src/main/java/org/jackhuang/hmcl/plugin/PluginHookPoint.java
git add HMCL/src/main/java/org/jackhuang/hmcl/game/HMCLGameLauncher.java
git add HMCL/src/test/java/org/jackhuang/hmcl/plugin/GameLaunchHookCoordinatorTest.java
git commit -m "Execute before game launch hooks"
```

### Task 8: Dispatch Exactly-Once After-Game-Launch Events

**Files:**
- Create: `HMCL/src/test/java/org/jackhuang/hmcl/game/GameLaunchHookProcessListenerTest.java`
- Create: `HMCL/src/main/java/org/jackhuang/hmcl/game/GameLaunchHookProcessListener.java`
- Modify: `HMCL/src/main/java/org/jackhuang/hmcl/plugin/GameLaunchHookCoordinator.java`
- Modify: `HMCL/src/main/java/org/jackhuang/hmcl/game/HMCLGameLauncher.java`

- [x] **Step 1: Write process-listener composition tests**

Use a fake `Process` wrapped by `ManagedProcess` and a delegate probe. Assert delegate `setProcess`,
logs, and exit callbacks are preserved; PID/start/end/duration/exit kind are encoded; normal,
application error, JVM error, SIGKILL, and interrupted paths dispatch once; duplicate `onExit` is
ignored; process creation failure emits nothing; after failures continue and do not replace exit
status.

```java
@Test
public void exitDispatchesAfterOnceAndPreservesDelegate() {
    listener.setProcess(managedProcess(4242L));
    listener.onExit(137, ProcessListener.ExitType.SIGKILL);
    listener.onExit(137, ProcessListener.ExitType.SIGKILL);

    assertEquals(1, delegate.exitCalls());
    assertEquals(1, coordinator.afterCalls());
    assertEquals("externally-killed", coordinator.lastData().requireString("terminationKind"));
    assertEquals(4242L, coordinator.lastData().requireNumber("pid").longValueExact());
}
```

- [x] **Step 2: Run listener tests and verify RED**

```powershell
.\gradlew.bat :HMCL:test --tests "org.jackhuang.hmcl.game.GameLaunchHookProcessListenerTest" --no-daemon --stacktrace
```

Expected: test compilation fails because the composing listener does not exist.

- [x] **Step 3: Implement listener composition and after policy**

The listener stores process/PID and an `AtomicBoolean exited`. It delegates `setProcess` and `onLog`.
On the first exit it invokes the original delegate in `try`, then coordinator after dispatch in
`finally`. Map existing exit types deterministically:

```text
NORMAL + 0        -> normal
NORMAL + nonzero  -> nonzero-exit
JVM_ERROR         -> nonzero-exit
APPLICATION_ERROR -> nonzero-exit
SIGKILL           -> externally-killed
INTERRUPTED       -> launcher-stop
otherwise         -> unknown
```

`GameLaunchHookCoordinator.afterLaunch` encodes the redacted final plan and timing, calls the
best-effort dispatcher, logs each categorized plugin failure, and never throws into `ExitWaiter`.
If there is no eligible after subscriber, return the original listener unchanged. Session cleanup is
not performed by the listener: `DefaultLauncher` calls `session.finishExit()` only after the listener
and resolved post-exit command have both completed.

- [x] **Step 4: Run listener, coordinator, and core exit tests GREEN**

```powershell
.\gradlew.bat :HMCL:test --tests "org.jackhuang.hmcl.game.GameLaunchHookProcessListenerTest" --tests "org.jackhuang.hmcl.plugin.GameLaunchHookCoordinatorTest" --no-daemon --stacktrace
.\gradlew.bat :HMCLCore:test --no-daemon --stacktrace
.\gradlew.bat :HMCL:checkstyleMain :HMCL:checkstyleTest --no-daemon --stacktrace
```

Expected: all commands succeed and no second process waiter is created.

- [x] **Step 5: Commit after-game-launch execution**

```powershell
git add HMCL/src/main/java/org/jackhuang/hmcl/game/GameLaunchHookProcessListener.java
git add HMCL/src/main/java/org/jackhuang/hmcl/game/HMCLGameLauncher.java
git add HMCL/src/main/java/org/jackhuang/hmcl/plugin/GameLaunchHookCoordinator.java
git add HMCL/src/test/java/org/jackhuang/hmcl/game/GameLaunchHookProcessListenerTest.java
git commit -m "Dispatch after game launch hooks"
```

### Task 9: Defer Application Shutdown While After Hooks Are Owed

**Files:**
- Create: `HMCL/src/test/java/org/jackhuang/hmcl/ApplicationShutdownCoordinatorTest.java`
- Create: `HMCL/src/main/java/org/jackhuang/hmcl/ApplicationShutdownCoordinator.java`
- Modify: `HMCL/src/main/java/org/jackhuang/hmcl/Launcher.java`
- Modify: `HMCL/src/main/java/org/jackhuang/hmcl/plugin/GameLaunchHookCoordinator.java`

- [x] **Step 1: Write the pure shutdown state-machine tests**

```java
@Test
public void requestedShutdownWaitsForAllLeases() {
    AtomicInteger shutdowns = new AtomicInteger();
    AtomicInteger hides = new AtomicInteger();
    ApplicationShutdownCoordinator coordinator =
            new ApplicationShutdownCoordinator(shutdowns::incrementAndGet, hides::incrementAndGet);

    AutoCloseable first = coordinator.acquireLease("after-game-launch:a");
    AutoCloseable second = coordinator.acquireLease("after-game-launch:b");
    coordinator.requestShutdown();
    assertEquals(1, hides.get());
    assertEquals(0, shutdowns.get());

    first.close();
    assertEquals(0, shutdowns.get());
    second.close();
    assertEquals(1, shutdowns.get());
}
```

Also test no-lease immediate shutdown, idempotent close/request, lease acquisition before a pending
request, and concurrent close calls.

- [x] **Step 2: Run shutdown tests and verify RED**

```powershell
.\gradlew.bat :HMCL:test --tests "org.jackhuang.hmcl.ApplicationShutdownCoordinatorTest" --no-daemon --stacktrace
```

Expected: test compilation fails because the coordinator does not exist.

- [x] **Step 3: Implement leases and integrate Launcher.stopApplication**

The pure coordinator synchronizes `leaseCount`, `shutdownRequested`, and `shutdownStarted`.
`acquireLease(String owner)` returns an idempotent `AutoCloseable`. `requestShutdown()` hides the UI
once when leases exist or invokes the real shutdown once when none exist. Closing the last lease
after a request invokes the real shutdown.

In `Launcher`, separate current shutdown code into `performApplicationShutdown()`, construct one
process-wide coordinator whose defer action hides the primary stage without shutting down
schedulers, and route `stopApplication()` through `requestShutdown()`. The defer action calls
`Stage.hide()` rather than `Stage.close()`, so it cannot enter the close-request handler again. Expose
a documented public `acquireShutdownLease(String)` bridge because the coordinator lives in another
Java package; do not add it to the Plugin SDK API snapshots. Existing startup already sets
`Platform.setImplicitExit(false)` and remains unchanged.

- [x] **Step 4: Acquire and release leases in direct launch sessions**

When a direct launch session has at least one eligible `after-game-launch` subscriber, acquire one
lease before returning from `beforeLaunch`. Release it after all after subscribers complete or time
out and the post-exit command returns, and release it on process-creation failure. Script and
no-subscriber sessions never acquire a lease. A timed-out Java callback retains only its
plugin/class-loader lease, not this application lease.

- [x] **Step 5: Run shutdown and coordinator tests GREEN**

```powershell
.\gradlew.bat :HMCL:test --tests "org.jackhuang.hmcl.ApplicationShutdownCoordinatorTest" --tests "org.jackhuang.hmcl.plugin.GameLaunchHookCoordinatorTest" --tests "org.jackhuang.hmcl.game.GameLaunchHookProcessListenerTest" --no-daemon --stacktrace
.\gradlew.bat :HMCL:checkstyleMain :HMCL:checkstyleTest --no-daemon --stacktrace
```

Expected: both commands succeed; close requests are deferred only while an after event is owed.

- [x] **Step 6: Commit shutdown leases**

```powershell
git add HMCL/src/main/java/org/jackhuang/hmcl/ApplicationShutdownCoordinator.java
git add HMCL/src/main/java/org/jackhuang/hmcl/Launcher.java
git add HMCL/src/main/java/org/jackhuang/hmcl/plugin/GameLaunchHookCoordinator.java
git add HMCL/src/test/java/org/jackhuang/hmcl/ApplicationShutdownCoordinatorTest.java
git commit -m "Keep HMCL alive for after launch hooks"
```

### Task 10: Wire LauncherHelper And Preserve Existing Launch Behavior

**Files:**
- Modify: `HMCL/src/main/java/org/jackhuang/hmcl/game/LauncherHelper.java`
- Modify: `HMCL/src/main/java/org/jackhuang/hmcl/game/HMCLGameLauncher.java`
- Modify: `HMCL/src/test/java/org/jackhuang/hmcl/plugin/GameLaunchHookCoordinatorTest.java`
- Modify: `HMCL/src/test/java/org/jackhuang/hmcl/game/GameLaunchHookProcessListenerTest.java`

- [x] **Step 1: Add regression tests around production composition**

Test that no Hook subscriber keeps the current listener/null-listener paths, close visibility still
stops immediately, script completion still returns `null`, and a close-mode launch with an after
subscriber installs a Hook-only listener so `ExitWaiter` can report termination without constructing
the log UI.

- [x] **Step 2: Run integration-focused tests and verify RED**

```powershell
.\gradlew.bat :HMCL:test --tests "org.jackhuang.hmcl.plugin.GameLaunchHookCoordinatorTest.productionComposition*" --tests "org.jackhuang.hmcl.game.GameLaunchHookProcessListenerTest.closeMode*" --no-daemon --stacktrace
```

Expected: at least the close-mode after subscriber assertion fails because `LauncherHelper` still
passes `null` and stops the application immediately.

- [x] **Step 3: Inject the process-wide coordinator in LauncherHelper**

Construct `HMCLGameLauncher` with `GameLaunchHookCoordinator.processWide(PluginManager.getInstance())`.
Keep the current rich `HMCLProcessListener` for every non-close visibility. For close visibility, let
`HMCLGameLauncher` install its Hook-only composing listener only when the launch session reports an
after subscriber. Continue calling `Launcher.stopApplication()` after successful process creation;
the shutdown coordinator decides whether that call is immediate or deferred.

On process-creation failure, call `session.closeWithoutProcess()` before propagating
`ProcessCreationException`. Do not acquire a lease during script generation.

- [x] **Step 4: Run focused and module regression suites GREEN**

```powershell
.\gradlew.bat :HMCL:test --tests "org.jackhuang.hmcl.plugin.PluginHookContractTest" --tests "org.jackhuang.hmcl.plugin.PluginHookSubscriberOrderTest" --tests "org.jackhuang.hmcl.plugin.PluginHookDispatcherTest" --tests "org.jackhuang.hmcl.plugin.GameLaunchHookCodecTest" --tests "org.jackhuang.hmcl.plugin.GameLaunchHookCoordinatorTest" --tests "org.jackhuang.hmcl.game.GameLaunchHookProcessListenerTest" --tests "org.jackhuang.hmcl.ApplicationShutdownCoordinatorTest" --no-daemon --stacktrace
.\gradlew.bat :HMCL:test --no-daemon --stacktrace
.\gradlew.bat :HMCL:checkstyleMain :HMCL:checkstyleTest --no-daemon --stacktrace
```

Expected: all commands succeed, including schema-v4 plugin and existing launcher lifecycle tests.

- [x] **Step 5: Commit production wiring**

```powershell
git add HMCL/src/main/java/org/jackhuang/hmcl/game/LauncherHelper.java
git add HMCL/src/main/java/org/jackhuang/hmcl/game/HMCLGameLauncher.java
git add HMCL/src/test/java/org/jackhuang/hmcl/plugin/GameLaunchHookCoordinatorTest.java
git add HMCL/src/test/java/org/jackhuang/hmcl/game/GameLaunchHookProcessListenerTest.java
git commit -m "Wire game launch hooks into launcher flow"
```

### Task 11: Publish The Schema-V5 Java Hook Surface In The SDK

**Files:**
- Modify: `C:/Users/ACX/Documents/Plugins/HMCL-CE-Plugin-SDK/tools/sync-api-references.ps1`
- Modify: `C:/Users/ACX/Documents/Plugins/HMCL-CE-Plugin-SDK/tools/test-publishing-tools.ps1`
- Modify: `C:/Users/ACX/Documents/Plugins/HMCL-CE-Plugin-SDK/references/README.md`
- Create: `C:/Users/ACX/Documents/Plugins/HMCL-CE-Plugin-SDK/references/hmcl-plugin-api/PluginDataValue.java`
- Create: `C:/Users/ACX/Documents/Plugins/HMCL-CE-Plugin-SDK/references/hmcl-plugin-api/PluginDataObject.java`
- Create: `C:/Users/ACX/Documents/Plugins/HMCL-CE-Plugin-SDK/references/hmcl-plugin-api/PluginHookEvent.java`
- Create: `C:/Users/ACX/Documents/Plugins/HMCL-CE-Plugin-SDK/references/hmcl-plugin-api/PluginHookResult.java`
- Create: `C:/Users/ACX/Documents/Plugins/HMCL-CE-Plugin-SDK/references/hmcl-plugin-api/PluginSecretAccess.java`
- Modify: `C:/Users/ACX/Documents/Plugins/HMCL-CE-Plugin-SDK/references/hmcl-plugin-api/Plugin.java`
- Modify: `C:/Users/ACX/Documents/Plugins/HMCL-CE-Plugin-SDK/references/hmcl-plugin-api/PluginHookPoint.java`
- Create: `C:/Users/ACX/Documents/Plugins/HMCL-CE-Plugin-SDK/examples/java-launch-hook/build.gradle.kts`
- Create: `C:/Users/ACX/Documents/Plugins/HMCL-CE-Plugin-SDK/examples/java-launch-hook/plugin.json`
- Create: `C:/Users/ACX/Documents/Plugins/HMCL-CE-Plugin-SDK/examples/java-launch-hook/src/main/java/dev/hmclce/example/launchhook/LaunchHookPlugin.java`

- [x] **Step 1: Add the new snapshot names and prove sync detects absence**

Append the five new public files to `$files` in `tools/sync-api-references.ps1`, then run:

```powershell
Set-Location C:\Users\ACX\Documents\Plugins\HMCL-CE-Plugin-SDK
.\tools\sync-api-references.ps1 -HmclRepository C:\Users\ACX\Documents\HMCL-CE
git status --short
```

Expected: the five files appear as untracked and `Plugin.java`/`PluginHookPoint.java` appear modified;
no schema-v4 checkout or file changes occur.

- [x] **Step 2: Create a real before-launch Java example**

The manifest is schema 5, Java ABI 2, declares only `before-game-launch`, and includes
`launcher-hook` in both `permissions` and `requiredPermissions`. The callback must preserve every
unknown field, add one JVM argument in structured mode, and leave raw mode unchanged:

```java
@Override
public PluginHookResult onHook(PluginHookEvent event) {
    if (event.point() != PluginHookPoint.BEFORE_GAME_LAUNCH) {
        return PluginHookResult.unchanged();
    }
    PluginDataObject plan = event.data().requireObject("plan");
    PluginDataObject command = plan.requireObject("command");
    if (!"structured-java".equals(command.requireString("mode"))) {
        return PluginHookResult.unchanged();
    }
    List<PluginDataValue> arguments = new ArrayList<>(command.requireArray("jvmArguments"));
    arguments.add(PluginDataValue.string("-Dhmcl.example.launch-hook=true"));
    PluginDataObject changedCommand = command.with("jvmArguments", PluginDataValue.array(arguments));
    PluginDataObject changedPlan = plan.with("command", PluginDataValue.object(changedCommand));
    return PluginHookResult.replace(event.data().with("plan", PluginDataValue.object(changedPlan)));
}
```

Implement ordinary lifecycle methods and `getManifest()` using the same pattern as the existing
Java example. The Gradle build uses the latest HMCL jar as `compileOnly`, Java release 17,
reproducible archives, and produces `dev.hmclce.example.java.launch-hook-v1.0.0.npl`.

- [x] **Step 3: Build and validate the new SDK example**

```powershell
Set-Location C:\Users\ACX\Documents\HMCL-CE
.\gradlew.bat :HMCL:build --no-daemon --stacktrace
Set-Location C:\Users\ACX\Documents\Plugins\HMCL-CE-Plugin-SDK
..\..\HMCL-CE\gradlew.bat -p .\examples\java-launch-hook clean packageNpl --no-daemon --stacktrace
.\tools\validate-npl.ps1 -Package .\examples\java-launch-hook\build\npl\dev.hmclce.example.java.launch-hook-v1.0.0.npl
.\tools\test-validate-npl.ps1
```

Expected: build and both validators succeed; the package contains `plugin.json` and the compiled
plugin jar and requests no `account` permission.

- [x] **Step 4: Verify snapshot reproducibility and document the executable status**

Run the sync script a second time and assert `git diff --exit-code` reports no further changes after
the generated snapshots are staged. Update `references/README.md` to list the five types and state
that game-launch Hooks execute on HMCL CE `next`, while other declared points and Patch execution
remain unavailable. Add `java-launch-hook` to the example matrix in
`tools/test-publishing-tools.ps1` and run that script so publishing checks cover the new package.

- [x] **Step 5: Commit only to SDK schema-v5**

```powershell
Set-Location C:\Users\ACX\Documents\Plugins\HMCL-CE-Plugin-SDK
git branch --show-current
git add tools/sync-api-references.ps1 tools/test-publishing-tools.ps1 references examples/java-launch-hook
git commit -m "Add schema v5 game launch hook example"
```

Expected: current branch is `schema-v5`; `schema-v4` remains at
`3a867062e8e771ba0e81a5e1c9c1a0f4b4d37816` with no content changes.

### Task 12: Verify, Review, Push, And Observe Both Repositories

**Files:**
- Verify all files changed by Tasks 1-11.
- Compare against: `docs/superpowers/specs/2026-08-24-next-game-launch-hook-dispatcher-design.md`

- [ ] **Step 1: Run the focused HMCL matrix on JDK 17**

```powershell
Set-Location C:\Users\ACX\Documents\HMCL-CE
java -version
.\gradlew.bat :HMCLCore:test --tests "org.jackhuang.hmcl.launch.LaunchProcessPlanTest" --tests "org.jackhuang.hmcl.launch.LaunchScriptRendererTest" --no-daemon --stacktrace
.\gradlew.bat :HMCL:test --tests "org.jackhuang.hmcl.plugin.PluginHookContractTest" --tests "org.jackhuang.hmcl.plugin.PluginHookSubscriberOrderTest" --tests "org.jackhuang.hmcl.plugin.PluginHookDispatcherTest" --tests "org.jackhuang.hmcl.plugin.GameLaunchHookCodecTest" --tests "org.jackhuang.hmcl.plugin.GameLaunchHookCoordinatorTest" --tests "org.jackhuang.hmcl.game.GameLaunchHookProcessListenerTest" --tests "org.jackhuang.hmcl.ApplicationShutdownCoordinatorTest" --no-daemon --stacktrace
```

Expected: `java -version` reports 17 and both Gradle commands are BUILD SUCCESSFUL.

- [ ] **Step 2: Run complete HMCL tests and static checks**

```powershell
.\gradlew.bat test --no-daemon --parallel --stacktrace
.\gradlew.bat checkstyle checkTranslations --no-daemon --parallel --stacktrace
git diff --check origin/next...next
```

Expected: all commands succeed with zero style, translation, or whitespace failures.

- [ ] **Step 3: Run the complete SDK validation matrix**

```powershell
Set-Location C:\Users\ACX\Documents\Plugins\HMCL-CE-Plugin-SDK
.\tools\test-validate-npl.ps1
.\tools\test-publishing-tools.ps1
Get-ChildItem .\examples -Recurse -Filter *.npl | ForEach-Object { .\tools\validate-npl.ps1 -Package $_.FullName }
Get-ChildItem .\dist -Filter *.npl | ForEach-Object { .\tools\validate-npl.ps1 -Package $_.FullName }
git diff --check schema-v4...schema-v5
git merge-base --is-ancestor schema-v4 schema-v5
```

Expected: every command exits 0; every package validates; schema-v5 remains descended from schema-v4.

- [ ] **Step 4: Perform specification and code review**

Check every design section against the implementation: complete plan, event/result envelope, secret
slots, subscriber order/permission, direct-script pipeline, after lifecycle, three external Runtime
boundaries, failures, and tests. Review `origin/next..next` and `schema-v4...schema-v5` for races,
credential disclosure, callback reentrancy, class-loader lifetime, quoting regressions, public API
leaks, nullability, and undocumented declarations. Fix each Critical or Important finding with a
new failing regression test, minimal production change, focused GREEN run, and separate commit.

- [ ] **Step 5: Verify clean publication state**

```powershell
Set-Location C:\Users\ACX\Documents\HMCL-CE
git status --short --branch
git log --oneline origin/next..next
Set-Location C:\Users\ACX\Documents\Plugins\HMCL-CE-Plugin-SDK
git status --short --branch
git log --oneline origin/schema-v5..schema-v5
git rev-parse schema-v4
git push --dry-run origin next
git push --dry-run origin schema-v5
```

Expected: both worktrees are clean; SDK `schema-v4` is exactly
`3a867062e8e771ba0e81a5e1c9c1a0f4b4d37816`; both dry runs succeed.

- [ ] **Step 6: Push both future branches**

```powershell
Set-Location C:\Users\ACX\Documents\HMCL-CE
git push origin next
Set-Location C:\Users\ACX\Documents\Plugins\HMCL-CE-Plugin-SDK
git push origin schema-v5
```

Expected: local and remote future branches match; SDK default branch remains `schema-v4`.

- [ ] **Step 7: Observe CI before declaring the milestone complete**

```powershell
Set-Location C:\Users\ACX\Documents\HMCL-CE
gh run list --branch next --limit 10
Set-Location C:\Users\ACX\Documents\Plugins\HMCL-CE-Plugin-SDK
gh run list --branch schema-v5 --limit 10
```

Expected: every workflow triggered by the pushes completes successfully. Reproduce any CI defect
locally when possible, add a failing regression, fix it in a new commit, push, and observe again.
