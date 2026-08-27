# Main Dead Source Cleanup Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove eight unreachable HMCLCore production classes and disposable ignored state from the isolated stable `main` worktree, then verify and publish the cleanup.

**Architecture:** This is a subtractive change with no replacement interfaces. Static reference checks establish the deletion boundary, the existing Gradle suite and Shadow JAR build guard runtime and packaging behavior, and an allowlisted PowerShell cleanup removes only ignored state inside `main-release`.

**Tech Stack:** Java 17, Gradle Wrapper, PowerShell, Git, GitHub CLI

**Spec:** `docs/superpowers/specs/2026-08-27-main-dead-source-cleanup-design.md`

## Global Constraints

- Work only in `C:\Users\ACX\Documents\HMCL-CE\.worktrees\main-release` on branch `main`.
- Do not modify or delete anything under the active `C:\Users\ACX\Documents\HMCL-CE` `next` worktree.
- Delete only the eight approved Java files and eleven approved ignored directories.
- Keep implicit runtime entries, tests, plugin contracts, CI, repository metadata, and upstream history.
- Do not terminate user processes; report any locked cleanup target.
- Preserve stable `main` versioning: local Shadow JAR `HMCL-CE-26.8-beta.SNAPSHOT.jar` with `Implementation-Version: 26.8-beta.SNAPSHOT`.
- Push only after local compilation, tests, packaging, and version checks pass.

---

### Task 1: Remove The Unreachable Production Types

**Files:**
- Delete: `HMCLCore/src/main/java/org/jackhuang/hmcl/auth/authlibinjector/AuthlibInjectorDownloader.java`
- Delete: `HMCLCore/src/main/java/org/jackhuang/hmcl/download/java/mojang/MojangJavaDistribution.java`
- Delete: `HMCLCore/src/main/java/org/jackhuang/hmcl/download/java/mojang/MojangJavaRemoteVersion.java`
- Delete: `HMCLCore/src/main/java/org/jackhuang/hmcl/game/GameException.java`
- Delete: `HMCLCore/src/main/java/org/jackhuang/hmcl/modpack/mcbbs/McbbsModpackRemoteInstallTask.java`
- Delete: `HMCLCore/src/main/java/org/jackhuang/hmcl/util/function/ExceptionalBiFunction.java`
- Delete: `HMCLCore/src/main/java/org/jackhuang/hmcl/util/gson/JsonMap.java`
- Delete: `HMCLCore/src/main/java/org/jackhuang/hmcl/util/javafx/PropertyUtils.java`

**Interfaces:**
- Consumes: Existing HMCLCore packages and the approved deletion list from the design spec.
- Produces: The same launcher and plugin interfaces with the eight undocumented, unreachable types absent.

- [ ] **Step 1: Reconfirm each type has no consumer outside its own declaration**

Run from the isolated worktree:

```powershell
$names = @(
    'AuthlibInjectorDownloader',
    'MojangJavaDistribution',
    'MojangJavaRemoteVersion',
    'GameException',
    'McbbsModpackRemoteInstallTask',
    'ExceptionalBiFunction',
    'JsonMap',
    'PropertyUtils'
)
foreach ($name in $names) {
    "### $name"
    rg -n -w --glob '!**/build/**' --glob '!**/.gradle/**' -- $name `
        HMCL HMCLCore HMCLBoot buildSrc config minecraft .github docs
}
```

Expected: each name appears only in its declaration file and in the approved design or plan. No
source, resource, SPI registration, Gradle script, or test consumes it.

- [ ] **Step 2: Delete the eight exact source files with one reviewable patch**

Use `apply_patch` with one `*** Delete File` entry for every file listed in this task. Do not remove
their parent packages, because those packages contain active source.

- [ ] **Step 3: Confirm the deletion diff is exact**

Run:

```powershell
git diff --name-status
git diff --check
```

Expected: eight `D` entries and no whitespace errors. The already committed design and plan files do
not appear in the unstaged diff.

- [ ] **Step 4: Compile every production source set**

Run:

```powershell
.\gradlew.bat classes --no-daemon --parallel --stacktrace
```

Expected: `BUILD SUCCESSFUL`. Any missing-symbol failure means a deletion was not actually
unreachable and blocks further work.

---

### Task 2: Verify Tests, Packaging, And Stable Version Identity

**Files:**
- Inspect: `HMCL/build.gradle.kts`
- Inspect: `config/project.properties`
- Test: all Gradle test source sets
- Artifact: `HMCL/build/libs/HMCL-CE-26.8-beta.SNAPSHOT.jar`

**Interfaces:**
- Consumes: The source tree produced by Task 1 and the existing Gradle build configuration.
- Produces: Passing test reports and a verified stable-line Shadow JAR.

- [ ] **Step 1: Run the complete test suite from fresh task execution**

Run:

```powershell
.\gradlew.bat test --rerun-tasks --no-daemon --console=plain --stacktrace
```

Expected: `BUILD SUCCESSFUL`; no failed or errored tests. The established baseline is 969 tests,
with two skipped, so any material count reduction must be explained before proceeding.

- [ ] **Step 2: Build the distributable Shadow JAR**

Run:

```powershell
.\gradlew.bat :HMCL:shadowJar --rerun-tasks --no-daemon --console=plain --stacktrace
```

Expected: `BUILD SUCCESSFUL` and
`HMCL/build/libs/HMCL-CE-26.8-beta.SNAPSHOT.jar` exists.

- [ ] **Step 3: Verify filename and embedded implementation version**

Run:

```powershell
$jarPath = Resolve-Path 'HMCL/build/libs/HMCL-CE-26.8-beta.SNAPSHOT.jar'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [System.IO.Compression.ZipFile]::OpenRead($jarPath)
try {
    $entry = $archive.GetEntry('META-INF/MANIFEST.MF')
    $reader = [System.IO.StreamReader]::new($entry.Open())
    try {
        $manifest = $reader.ReadToEnd()
    } finally {
        $reader.Dispose()
    }
} finally {
    $archive.Dispose()
}
$jarPath.Path
$manifest | Select-String '^Implementation-Version: 26\.8-beta\.SNAPSHOT$'
```

Expected: the exact `HMCL-CE-26.8-beta.SNAPSHOT.jar` path and one matching manifest line. No `-next`
suffix is introduced on stable `main`.

- [ ] **Step 4: Review the final tracked diff**

Run:

```powershell
git diff --stat
git diff --check
git status --short
```

Expected: only the eight approved Java deletions are uncommitted, totaling 642 deleted physical lines.

---

### Task 3: Commit, Clean Ignored State, Push, And Verify CI

**Files:**
- Commit: the eight Java deletions
- Remove locally: the eleven allowlisted ignored directories from the design spec

**Interfaces:**
- Consumes: Verified source deletions and generated build/runtime state from Tasks 1 and 2.
- Produces: A clean local `main`, synchronized `origin/main`, and successful GitHub Actions runs.

- [ ] **Step 1: Commit only the approved Java deletions**

Run:

```powershell
git add -- `
    HMCLCore/src/main/java/org/jackhuang/hmcl/auth/authlibinjector/AuthlibInjectorDownloader.java `
    HMCLCore/src/main/java/org/jackhuang/hmcl/download/java/mojang/MojangJavaDistribution.java `
    HMCLCore/src/main/java/org/jackhuang/hmcl/download/java/mojang/MojangJavaRemoteVersion.java `
    HMCLCore/src/main/java/org/jackhuang/hmcl/game/GameException.java `
    HMCLCore/src/main/java/org/jackhuang/hmcl/modpack/mcbbs/McbbsModpackRemoteInstallTask.java `
    HMCLCore/src/main/java/org/jackhuang/hmcl/util/function/ExceptionalBiFunction.java `
    HMCLCore/src/main/java/org/jackhuang/hmcl/util/gson/JsonMap.java `
    HMCLCore/src/main/java/org/jackhuang/hmcl/util/javafx/PropertyUtils.java
git commit -m 'Remove unreachable HMCLCore source'
```

Expected: one commit containing eight deleted files and no unrelated changes.

- [ ] **Step 2: Resolve and validate every ignored cleanup target**

Run:

```powershell
$worktree = (Resolve-Path '.').Path.TrimEnd('\')
$relativeTargets = @(
    '.gradle',
    '.hmcl',
    'HMCL/.hmcl',
    'HMCL/build',
    'HMCLBoot/build',
    'HMCLCore/build',
    'build',
    'buildSrc/.gradle',
    'buildSrc/build',
    'minecraft/libraries/HMCLMultiMCBootstrap/build',
    'minecraft/libraries/HMCLTransformerDiscoveryService/build'
)
$targets = foreach ($relativeTarget in $relativeTargets) {
    $candidate = [System.IO.Path]::GetFullPath((Join-Path $worktree $relativeTarget))
    if (-not $candidate.StartsWith($worktree + '\', [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Cleanup target escaped main-release: $candidate"
    }
    $candidate
}
$targets
```

Expected: eleven absolute paths, all below
`C:\Users\ACX\Documents\HMCL-CE\.worktrees\main-release`.

- [ ] **Step 3: Remove only the validated ignored targets**

Run in the same PowerShell session that produced `$targets`:

```powershell
foreach ($target in $targets) {
    if (Test-Path -LiteralPath $target) {
        Remove-Item -LiteralPath $target -Recurse -Force -ErrorAction Stop
    }
}
```

Expected: all eleven targets are absent. If an artifact is locked, stop and report its exact path;
do not terminate the process holding it.

- [ ] **Step 4: Verify repository state before push**

Run:

```powershell
git status --short --branch
git log -3 --oneline --decorate
git diff origin/main..HEAD --check
```

Expected: no tracked or untracked changes; `main` is ahead of `origin/main` by the design, plan, and
source-cleanup commits.

- [ ] **Step 5: Push the stable main branch**

Run:

```powershell
git push origin main
```

Expected: `origin/main` advances to the source-cleanup commit without force push.

- [ ] **Step 6: Wait for both source-triggered GitHub Actions workflows**

Run:

```powershell
$headCommit = git rev-parse HEAD
gh run list --repo HMCL-Community/HMCL-CE --commit $headCommit --json databaseId,name,status,conclusion,url
```

Expected: `Java CI` and `Check Codes` appear for `$headCommit`. Watch each incomplete run with
`gh run watch <databaseId> --repo HMCL-Community/HMCL-CE --exit-status`; both must finish with
`success` before reporting completion.
