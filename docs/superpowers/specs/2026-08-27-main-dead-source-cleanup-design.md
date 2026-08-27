# Main Dead Source Cleanup

## Goal

Remove production Java source that has no launcher, test, resource, service-provider, reflection,
or build-time consumers on the stable `main` line. Also remove ignored build and launcher-runtime
state from the isolated `main-release` worktree without touching the active `next` worktree.

This cleanup does not change plugin behavior, plugin contracts, launcher versioning, repository
metadata, CI definitions, documentation other than this record, or files retained for upstream
synchronization.

## Source Deletions

Delete these eight production source files:

- `HMCLCore/src/main/java/org/jackhuang/hmcl/auth/authlibinjector/AuthlibInjectorDownloader.java`
- `HMCLCore/src/main/java/org/jackhuang/hmcl/download/java/mojang/MojangJavaDistribution.java`
- `HMCLCore/src/main/java/org/jackhuang/hmcl/download/java/mojang/MojangJavaRemoteVersion.java`
- `HMCLCore/src/main/java/org/jackhuang/hmcl/game/GameException.java`
- `HMCLCore/src/main/java/org/jackhuang/hmcl/modpack/mcbbs/McbbsModpackRemoteInstallTask.java`
- `HMCLCore/src/main/java/org/jackhuang/hmcl/util/function/ExceptionalBiFunction.java`
- `HMCLCore/src/main/java/org/jackhuang/hmcl/util/gson/JsonMap.java`
- `HMCLCore/src/main/java/org/jackhuang/hmcl/util/javafx/PropertyUtils.java`

The files contain 561 lines in total. Repository-wide exact type-name searches found no consumer
outside each declaration. None is registered through `META-INF/services`, named in a Gradle task,
loaded by a reflection path, or documented as part of the HMCL CE plugin contract.

`AuthlibInjectorDownloader` lost its launcher consumer when authlib-injector changed to an embedded
artifact extracted by `AuthlibInjectorExtractor`. `McbbsModpackRemoteInstallTask` is also deleted by
HMCL upstream commit `116629743` as part of the GameRepository refactor.

## Compatibility

The deleted classes are public Java types, so code that bypasses the documented plugin contract and
links directly against HMCLCore internals would lose binary compatibility. HMCL CE does not expose
these types through the plugin contract or Plugin SDK, and no repository code consumes them. This
cleanup accepts that undocumented-internal compatibility cost; documented plugin APIs remain
unchanged.

## Explicit Retentions

Keep source that appears unreferenced to text-only analysis but has an implicit runtime consumer:

- `JFXTabPaneSkin` overrides the same class supplied by `JFoenix.jar` and is selected by JFoenix.
- `Translator_en_Qabs` is loaded by locale-derived reflection in `SupportedLocale`.
- `HMCLURLStreamHandlerProvider` and Mixin services are loaded through `META-INF/services`.
- `HmclMixinAgent` is the JAR `Premain-Class`.
- `package-info.java` files carry package documentation and annotations.

Keep tests, historical design documents, CI configuration, mirror metadata, launcher assets, bundled
libraries, and all five Gradle modules. No private-member or resource cleanup is included because the
available evidence does not justify expanding the deletion surface.

## Isolated Worktree Cleanup

After verification, remove these ignored directories only from
`C:\Users\ACX\Documents\HMCL-CE\.worktrees\main-release`:

- `.gradle`
- `.hmcl`
- `HMCL/.hmcl`
- `HMCL/build`
- `HMCLBoot/build`
- `HMCLCore/build`
- `build`
- `buildSrc/.gradle`
- `buildSrc/build`
- `minecraft/libraries/HMCLMultiMCBootstrap/build`
- `minecraft/libraries/HMCLTransformerDiscoveryService/build`

The targets must be resolved and checked to remain inside the isolated worktree before deletion.
The active `C:\Users\ACX\Documents\HMCL-CE` worktree and its `next` branch are out of scope. Existing
launcher-runtime data under the isolated `.hmcl` directory is disposable test state and will not be
preserved.

No user process will be terminated. If a running process holds a build artifact open, report the
remaining locked target rather than killing that process.

## Verification

1. Confirm all eight type names have no remaining references and the worktree diff contains only the
   approved source deletions plus this design record.
2. Run the complete Gradle test suite with rerun enabled and no daemon.
3. Build the `HMCL-CE-*` Shadow JAR.
4. Verify the Shadow JAR filename, Gradle project version, and manifest `Implementation-Version` have
   not changed from the stable `main` versioning rules.
5. Remove the approved ignored directories after verification and confirm `git status` is clean after
   committing tracked changes.
6. Push the verified commit to `origin/main` and inspect the resulting GitHub Actions run.

Any compilation, test, packaging, version, or CI failure blocks the push until it is understood and
resolved within this cleanup scope.
