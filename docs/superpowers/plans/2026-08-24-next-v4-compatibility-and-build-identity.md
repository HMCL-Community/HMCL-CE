# HMCL CE Next V4 Compatibility And Build Identity Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Restore schema-v4 plugin controls and Store compatibility display on `next`, then make every `next` build identify itself with a `-next` suffix in both its embedded version and artifact filename.

**Architecture:** The Store UI delegates compatibility to the existing shared evaluator instead of comparing against the newest schema. The permission page uses one tested executable-range predicate. Gradle normalizes every version source through one idempotent suffix function, and `AGENTS.md` records the product-line invariant for future agents.

**Tech Stack:** Java 17, JavaFX 17, JUnit 5, Kotlin Gradle DSL, Shadow JAR, PowerShell 5+.

---

### Task 1: Restore Schema-V4 UI Compatibility

**Files:**
- Modify: `HMCL/src/test/java/org/jackhuang/hmcl/ui/main/PluginStorePageTest.java`
- Modify: `HMCL/src/main/java/org/jackhuang/hmcl/ui/main/PluginStorePage.java`
- Modify: `HMCL/src/main/java/org/jackhuang/hmcl/ui/main/PluginPermissionManagementPage.java`

- [ ] **Step 1: Write failing UI regression tests**

Add focused assertions:

```java
/// Treats every backend-supported manifest generation as executable in permission controls.
@Test
public void permissionControlsUseExecutableSchemaRange() {
    assertFalse(PluginPermissionManagementPage.isExecutableSchema(3));
    assertTrue(PluginPermissionManagementPage.isExecutableSchema(4));
    assertTrue(PluginPermissionManagementPage.isExecutableSchema(5));
    assertFalse(PluginPermissionManagementPage.isExecutableSchema(6));
}
```

Create schema-v4 and schema-v5 `PluginVersionEntry` fixtures with `launcherVersion: "*"`, then assert
`PluginStorePage.compatibilityText(manager, entry)` begins with the localized current-compatible
text and contains the matching API number for both versions.

- [ ] **Step 2: Run the focused test and verify RED**

```powershell
.\gradlew.bat :HMCL:test --tests "org.jackhuang.hmcl.ui.main.PluginStorePageTest.permissionControlsUseExecutableSchemaRange" --tests "org.jackhuang.hmcl.ui.main.PluginStorePageTest.compatibilityTextAcceptsEveryExecutableSchema" --no-daemon --stacktrace
```

Expected: test compilation fails because the two package-visible helpers do not exist.

- [ ] **Step 3: Make Store compatibility use the backend evaluator**

Change `compatibilityText` to package-visible static and delete the strict
`version.getPluginApiVersion() != PluginManifest.CURRENT_SCHEMA_VERSION` branch. Keep its existing
`sourceManager.validateCompatibility(version)` call and localized `IOException` handling as the
single result source.

- [ ] **Step 4: Make permission controls use the executable range**

Add and document this package-visible helper:

```java
/// Returns whether the manifest schema can execute on this launcher build.
/// @param schemaVersion plugin manifest schema generation
/// @return whether lifecycle and permission controls may operate on the artifact
static boolean isExecutableSchema(int schemaVersion) {
    return schemaVersion >= PluginManifest.MIN_EXECUTABLE_SCHEMA_VERSION
            && schemaVersion <= PluginManifest.CURRENT_SCHEMA_VERSION;
}
```

Use it in both `refreshStatus()` and `refreshActionState()`.

- [ ] **Step 5: Run focused and backend schema-v4 tests GREEN**

```powershell
.\gradlew.bat :HMCL:test --tests "org.jackhuang.hmcl.ui.main.PluginStorePageTest" --tests "org.jackhuang.hmcl.plugin.runtime.PluginCompatibilityEvaluatorTest" --tests "org.jackhuang.hmcl.plugin.PluginManagerSchemaFourPermissionTest" --tests "org.jackhuang.hmcl.plugin.store.PluginStoreManagerTest.evaluateStoreCompatibilityWithSharedRuntimeContract" --no-daemon --stacktrace
.\gradlew.bat :HMCL:checkstyleMain :HMCL:checkstyleTest --no-daemon --stacktrace
```

Expected: both commands succeed; schemas 4 and 5 are compatible while 1-3 and 6 remain ineligible.

- [ ] **Step 6: Commit the UI correction**

```powershell
git add HMCL/src/main/java/org/jackhuang/hmcl/ui/main/PluginStorePage.java
git add HMCL/src/main/java/org/jackhuang/hmcl/ui/main/PluginPermissionManagementPage.java
git add HMCL/src/test/java/org/jackhuang/hmcl/ui/main/PluginStorePageTest.java
git commit -m "Restore schema v4 plugin UI compatibility"
```

### Task 2: Enforce The Next Build Identity

**Files:**
- Modify: `HMCL/build.gradle.kts`
- Modify: `AGENTS.md`

- [ ] **Step 1: Reproduce the missing suffix**

```powershell
.\gradlew.bat :HMCL:properties --no-daemon | Select-String '^version:'
$env:BUILD_VERSION='26.8-test'; .\gradlew.bat :HMCL:properties --no-daemon | Select-String '^version:'; Remove-Item Env:BUILD_VERSION
```

Expected: RED evidence reports `version: 26.8.SNAPSHOT` and `version: 26.8-test`, neither ending in
`-next`.

- [ ] **Step 2: Normalize every selected version once**

Add above version assignment:

```kotlin
val nextVersionSuffix = "-next"

fun String.withNextVersionSuffix(): String =
    if (endsWith(nextVersionSuffix)) this else "$this$nextVersionSuffix"
```

Compute the existing local/official/unofficial/explicit value into `selectedVersion`, then assign:

```kotlin
version = selectedVersion.withNextVersionSuffix()
```

Do not alter `archiveBaseName`; `project.version` must drive both ordinary and `HMCL-CE` Shadow JAR
filenames plus the embedded `hmcl.version` and `Implementation-Version` values.

- [ ] **Step 3: Add the agent constraint**

Append this section to root `AGENTS.md`:

```markdown
## Next Product-Line Identity

- This branch is the future `next` product line. Every launcher build must end its embedded version
  with `-next`, including builds that receive `BUILD_VERSION` from CI.
- Java and packaged launcher artifact filenames must carry the same `-next` version suffix. The
  distributable Java artifact remains the `HMCL-CE-*` Shadow JAR.
- Do not remove, bypass, or conditionally suppress this suffix during ordinary feature work.
  Removing it is allowed only as an explicit stable-line promotion performed on the target stable
  branch.
- When modifying version or packaging logic, verify the Gradle project version, Shadow JAR filename,
  and JAR `Implementation-Version` before committing.
```

- [ ] **Step 4: Verify local, explicit, and idempotent versions GREEN**

```powershell
.\gradlew.bat :HMCL:properties --no-daemon | Select-String '^version:'
$env:BUILD_VERSION='26.8-test'; .\gradlew.bat :HMCL:properties --no-daemon | Select-String '^version:'
$env:BUILD_VERSION='26.8-test-next'; .\gradlew.bat :HMCL:properties --no-daemon | Select-String '^version:'
Remove-Item Env:BUILD_VERSION
```

Expected: `26.8.SNAPSHOT-next`, `26.8-test-next`, and `26.8-test-next` respectively.

- [ ] **Step 5: Build and inspect the distributable JAR**

```powershell
.\gradlew.bat :HMCL:shadowJar --no-daemon --stacktrace
Get-ChildItem HMCL\build\libs\HMCL-CE-*-next.jar | Sort-Object LastWriteTime -Descending | Select-Object -First 1 Name,Length
```

Open the newest JAR with `System.IO.Compression.ZipFile`, read `META-INF/MANIFEST.MF`, and assert
`Implementation-Version` ends in `-next`. Also read `assets/hmcl.properties` and assert
`hmcl.version` ends in `-next`.

- [ ] **Step 6: Commit build identity and constraint**

```powershell
git add HMCL/build.gradle.kts AGENTS.md
git commit -m "Mark next launcher build artifacts"
```

### Task 3: Complete Verification And Publish

**Files:**
- Verify files changed in Tasks 1-2.
- Compare against: `docs/superpowers/specs/2026-08-24-next-v4-compatibility-and-build-identity-design.md`

- [ ] **Step 1: Run the complete HMCL test and style matrix**

```powershell
.\gradlew.bat :HMCL:test --no-daemon --stacktrace
.\gradlew.bat :HMCL:checkstyleMain :HMCL:checkstyleTest --no-daemon --stacktrace
```

Expected: both commands are BUILD SUCCESSFUL.

- [ ] **Step 2: Verify actual artifact identity and repository scope**

```powershell
git diff --check origin/next...next
git status --short --branch
git log --oneline origin/next..next
```

Expected: no whitespace error; only approved commits are ahead of `origin/next`; the unrelated
untracked Hook implementation plan remains uncommitted.

- [ ] **Step 3: Push and observe CI**

```powershell
git push origin next
gh run list --branch next --limit 10
```

Expected: `origin/next` matches local `next` and every workflow triggered by the push completes
successfully. Reproduce any CI failure locally before changing code.
