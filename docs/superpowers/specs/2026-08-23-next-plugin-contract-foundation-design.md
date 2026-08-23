# HMCL CE Next Plugin Contract Foundation Design

## Purpose

This milestone resumes the next-generation plugin work at commit `8561501`. It turns the existing
ABI, runtime, platform, and permission sketches into a coherent, tested contract while preserving
the stable schema-v4 product line. It also completes the declarative Hook/Patch manifest work left
uncommitted in the HMCL working tree.

The milestone is intentionally a contract foundation. It does not claim that an external language
runtime, a launcher Hook dispatcher, or a JVM Patch engine is executable yet.

## Repository And Branch Model

HMCL CE keeps its existing product-line branches:

- `main` is the stable 26.x launcher line and continues to use schema v4.
- `next` is the future 27.x launcher line and accepts schema v4 and schema v5 packages.

The Plugin SDK adopts explicit schema branches:

- The GitHub branch `main` is renamed to `schema-v4` through the GitHub branch-rename API.
- `schema-v4` remains the SDK repository's default and primary stable branch.
- `schema-v5` is created from `schema-v4` and targets HMCL CE `next` only.
- The remote `main` branch no longer exists after the rename. GitHub's branch redirect handles old
  web links, while repository-controlled branch references are updated explicitly.
- `schema-v4` receives only the branch-reference changes required by the rename. Schema-v5 API,
  examples, validation, and documentation changes live only on `schema-v5`.

## Compatibility Contract

HMCL CE `next` treats manifest schema and Plugin ABI as separate compatibility dimensions:

- A valid schema-v4 package maps to runtime `java`, ABI 1, and no platform restriction. It remains
  installable and executable on `next`.
- A schema-v5 package must declare a canonical runtime identifier and a supported ABI generation.
- Schema v5 `platforms` is optional. An absent or empty list is platform-independent; otherwise at
  least one declared target must match the current host before code is loaded.
- A package can execute only when its runtime provider is registered and that provider implements
  the requested ABI.
- Unsupported schema, platform, runtime, or ABI states fail before class loading and produce a
  specific diagnostic. They are not reported as generic legacy-schema failures.
- HMCL CE `main` and SDK `schema-v4` retain their existing schema-v4 behavior.

All compatibility checks use one shared evaluator so local installation, startup discovery, store
compatibility filtering, and lifecycle loading cannot disagree.

## Schema-V5 Manifest Contract

Schema v5 retains all schema-v4 identity, dependency, launcher-version, permission, and Mixin fields
and adds:

- `runtime`: canonical runtime provider ID, required by schema v5.
- `abi`: positive Plugin ABI generation supported by HMCL CE `next`.
- `platforms`: optional list of canonical `os` or `os-arch` targets.
- `hooks`: optional list of launcher lifecycle hook points.
- `patches`: optional list of declarative method patch requests.

The current Hook points cover before/after download, game launch, login, instance creation, mod
installation, and settings loading. Duplicate Hook points are invalid.

A Patch declaration contains a target binary class name, method name, callback position
(`before`, `after`, or `replace`), and a required ordered `parameters` list used to identify the
exact overload. A no-argument method uses an empty list. Duplicate declarations with the same
target, method, parameter list, and position are invalid. This milestone validates and exposes Patch
declarations but does not transform bytecode.

The manifest derives one capability level:

- `API` when neither Hooks nor Patches are declared.
- `HOOK` when at least one Hook and no Patch is declared.
- `PATCH` when at least one Patch is declared.

Hook declarations require `launcher-hook` in both `permissions` and `requiredPermissions`. Patch
declarations require `launcher-patch` in both lists. These permissions are dangerous because they
alter launcher behavior. Hook/Patch fields and permissions are rejected before schema v5.

Manifest equality and hashing include runtime, ABI, normalized platforms, Hooks, and Patches because
these fields affect executable behavior. Programmatic schema-v5 construction initializes a valid
Java/ABI-2 default rather than creating an object that fails its own validation.

## Runtime, ABI, And Platform Integration

`RuntimeProviderRegistry` remains the registry boundary and starts with the built-in Java provider.
The Java provider cannot be replaced or removed. Other provider IDs may be registered and removed,
with duplicate registration rejected unless the caller explicitly removes the previous provider.

This milestone connects the registry to compatibility evaluation and Plugin Manager preload gates.
It does not add external provider lifecycle ownership or execute non-JVM payloads. Consequently,
schema-v5 Java/Kotlin packages can pass the full gate; a package naming an unavailable external
runtime is parsed and diagnosed as missing-runtime without reaching a loader.

Platform matching uses the existing canonical OS and architecture identifiers. Unknown local CPU
architectures cannot match architecture-specific packages but can still run OS-only or unrestricted
packages.

## SDK Schema Branches

SDK `schema-v4` remains the supported stable authoring surface. Its validators, examples, API
snapshot, and release packages stay at schema v4.

SDK `schema-v5` is synchronized from HMCL CE `next` and includes:

- updated Java API snapshots for every launcher API type changed by this milestone;
- schema-v5 Java, Kotlin, and Mixin example manifests using `runtime: java` and ABI 2;
- validator support for runtime, ABI, platforms, Hooks, Patches, duplicate detection, and permission
  coupling;
- documentation that marks schema v5 as prerelease and HMCL CE `next`-only;
- publishing templates whose plugin API version is 5.

The schema-v5 validator accepts both schema v4 and schema v5 packages because `next` supports both,
but schema-v5 examples and newly produced templates use schema v5. The schema-v4 validator remains
strictly v4.

## Error Handling

Validation errors identify the exact incompatible dimension and offending value. Runtime and ABI
errors name the requested runtime and ABI; platform errors name the declared targets and detected
host; Hook/Patch errors identify the duplicate or missing permission. Diagnostics must not expose
credentials or package contents unrelated to the error.

Compatibility failures occur before permission decisions, class loader creation, Mixin classpath
attachment, or lifecycle callbacks. Existing transaction and trust checks remain unchanged.

## Testing

Development follows red-green-refactor. Tests cover:

- schema-v4 parsing, installation, and execution compatibility on `next`;
- valid schema-v5 Java manifests and rejection of missing/invalid runtime, ABI, and platform fields;
- platform-independent, OS-only, architecture-specific, and unknown-host-architecture matching;
- immutable Java provider registration and missing/unsupported external providers;
- Hook/Patch parsing, duplicate rejection, capability derivation, and required permission coupling;
- manifest equality and hashing for every new executable field;
- preload rejection before loader or lifecycle code runs;
- SDK v4/v5 validator behavior and all tracked example packages.

Verification includes the focused contract tests, the complete HMCL test suite, Checkstyle, SDK
publishing-tool tests, SDK package validation, and clean Git status checks in both repositories.

## Explicitly Deferred Work

The following roadmap items remain separate milestones:

- dispatching Hook contexts from launcher operations;
- implementing Patch ABI callbacks and the JVM Mixin/ASM/ByteBuddy engine;
- runtime-provider plugin lifecycle ownership and automatic runtime dependency installation/removal;
- Store target-artifact matrices and per-platform download selection;
- concrete .NET, QuickJS/WASM, Python, and native providers;
- generated cross-language Core API SDKs;
- 27.x release channels and final promotion of `next` into `main`.
