# HMCL CE Next V4 Compatibility And Build Identity Design

## Purpose

HMCL CE `next` supports executable plugin manifest schemas 4 and 5. The backend already enforces
that range, but two UI paths incorrectly treat schema 5 as the only executable generation. This
milestone removes those UI-only contradictions and gives every `next` build an unmistakable
`-next` version identity.

## Plugin Compatibility Correction

The shared `PluginCompatibilityEvaluator` remains authoritative and continues accepting schemas 4
through 5. Store compatibility text must call the existing Store manager validation instead of
rejecting every version that differs from `CURRENT_SCHEMA_VERSION`.

The permission management page must expose lifecycle and permission controls for every manifest in
the executable range `MIN_EXECUTABLE_SCHEMA_VERSION..CURRENT_SCHEMA_VERSION`. Schemas 1 through 3
remain preserved only for update or removal and do not regain execution controls.

Focused tests cover schema-v4 Store compatibility text and schema-v4 permission-page eligibility so
future changes cannot silently restore strict equality with schema 5. Existing schema-v4 install,
permission, compatibility, and lifecycle tests remain part of verification.

## Next Build Identity

`HMCL/build.gradle.kts` normalizes the selected project version with the suffix `-next`. This applies
to the default local snapshot version, GitHub commit-derived versions, and explicit `BUILD_VERSION`
values. A value already ending in `-next` is left unchanged.

Because Gradle archive versions and the Shadow JAR manifest both consume `project.version`, one
normalization point produces both required signals:

- launcher UI and diagnostics report a version such as `26.8.SNAPSHOT-next`;
- distributable artifacts use names such as `HMCL-CE-26.8.SNAPSHOT-next.jar`.

The ordinary thin JAR also receives the suffix through the project version. The distributable
artifact remains the `HMCL-CE-*` Shadow JAR.

The repository-root `AGENTS.md` gains a `Next Product-Line Identity` section requiring future agents
to preserve the suffix in embedded versions and artifact filenames, including CI builds that set
`BUILD_VERSION`. Promotion into the stable product line must remove that rule in the stable branch
as an explicit release operation; ordinary feature work may not remove or bypass it.

## Testing

Development follows red-green-refactor:

- reproduce schema-v4 UI misclassification with focused tests;
- make both UI paths consume the executable range or shared evaluator;
- demonstrate the pre-change Gradle project version lacks `-next`;
- normalize all build-version sources once and verify the Gradle project version;
- build the Shadow JAR and verify its filename and `Implementation-Version` both end in `-next`;
- run focused schema-v4 backend/UI tests, HMCL tests, and Checkstyle.

No schema-v5 Hook dispatcher production code, SDK branch content, Store artifact matrix, or Runtime
Provider lifecycle behavior is changed by this milestone.
