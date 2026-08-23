# HMCL CE Windows Launcher Header

`HMCLauncher.exe` is the Windows executable header prepended to HMCL CE's distributable JAR.

It is derived from `org.glavo.hmcl:HMCLauncher:3.7.0.1`, whose corresponding source is available at
<https://github.com/Glavo/HMCLauncher>. HMCLauncher and this modified binary are distributed under GPLv3 with the
additional terms stated by that project.

The CE binary changes only Windows resources:

- The application icon is generated from `HMCL/src/main/resources/assets/img/icon@8x.png`.
- The product name and description identify `HMCL CE Launcher`.
- The resource version is `3.7.0.2` to distinguish it from the unmodified `3.7.0.1` binary.
- The original copyright notice is retained, with the CE resource modification credited separately.

Artifact hashes:

- Original `HMCLauncher.exe`: `07476d13694bdba5ffcbb4786acf213c26eec650b78e710e0cd95c23d0e4147e`
- Modified `HMCLauncher.exe`: `8861063b1026120c14cdc4d2584427f54b35de15f9bef21b9925c379ea6ee533`

The icon was converted with `png2icons 2.0.1` using its Windows-executable ICO profile, then the resources were
updated with `rcedit 5.0.2`. `HMCL_LAUNCHER_EXE` can still override this bundled header for local builds.
