<!-- #BEGIN BLOCK -->
<!-- #PROPERTY NAME=TITLE -->
<div align="center">
    <img src="../HMCL/src/main/resources/assets/img/icon@8x.png" alt="HMCL CE Logo" width="64"/>
</div>

<h1 align="center">HMCL CE</h1>
<!-- #END BLOCK -->

<!-- #BEGIN BLOCK -->
<!-- #PROPERTY NAME=BADGES -->
<div align="center">

[![GitHub](https://img.shields.io/badge/GitHub-repo-blue?style=flat-square&logo=github)](https://github.com/HMCL-Community/HMCL-CE)
[![QQ Group](https://img.shields.io/badge/QQ-gray?style=flat-square&logo=qq&logoColor=ffffff)](https://qun.qq.com/universal-share/share?ac=1&authKey=pOw%2BTFtCWoazxuhJo6aSk%2BnmPW3lVVH0t5LCnE3ya2EFzj%2BEy9kHLci1ahepvW6t&busi_data=eyJncm91cENvZGUiOiIxMDk3MTIxNzUxIiwidG9rZW4iOiJXVzhRZkZEYit3N1BRT1o2dWNjQkw4WElFYjR0ZFQ3R01vYVo3bmsvR2htZThZNXhXOTgyQXpZYU5Ua2NNU3VsIiwidWluIjoiMzYxNjQzOTUwNSJ9&data=2bSHbitmgkNabOpdNYYdvazyW7GDY_7Mj7eeonhQ7whmvotadJdwtlQC5Sg60CxIo-uu9ZukUgzQUcQYGRMy6w&svctype=4&tempid=h5_group_info)
[![Bilibili](https://img.shields.io/badge/Bilibili-gray?style=flat-square&logo=bilibili)](https://b23.tv/CTHjMv6)

</div>
<!-- #END BLOCK -->

---

<!-- #BEGIN LANGUAGE_SWITCHER -->
**English** (**Standard**, [uʍoᗡ ǝpᴉsd∩](README_en_Qabs.md)) | 中文 ([简体](README_zh.md), [繁體](README_zh_Hant.md), [文言](README_lzh.md)) | [日本語](README_ja.md) | [español](README_es.md) | [русский](README_ru.md) | [українська](README_uk.md)
<!-- #END LANGUAGE_SWITCHER -->

## Introduction

HMCL CE is an open-source, cross-platform Minecraft launcher developed from the HMCL upstream project. In addition to mod management, game customization, modpack installation, and broad platform support, CE provides plugin management, configurable plugin-store sources, and a signed update chain.

HMCL CE has broad cross-platform capabilities. It runs on Windows, Linux, macOS, and FreeBSD, and supports CPU architectures including x86, ARM, RISC-V, MIPS, and LoongArch.

For systems and CPU architectures supported by HMCL, please refer to [this table](PLATFORM.md).

## Download

The current stable release is **HMCL CE 26.8 Release 1**. Download it from:

- [HMCL CE 26.8 Release 1](https://github.com/HMCL-Community/HMCL-CE/releases/tag/v26.8-release.1)
- [All releases](https://github.com/HMCL-Community/HMCL-CE/releases)

Release 1 introduces the independent CE public-key signing chain and GitHub Releases update source. Keep the SHA-256 checksum files from the release page when distributing the binaries.

See the [plugin system documentation](./PLUGIN_SYSTEM.md) for manifest format, permissions, and source management, and the [plugin contract](./PLUGIN_CONTRACT.md) for the formal launcher-plugin behavioral guarantees.

## Contributing

HMCL CE is a community-driven open-source project based on HMCL, with an independent release and plugin track.

You can contribute to HMCL CE development in the following ways:

- Report bugs or request features by [creating an issue](https://github.com/HMCL-Community/HMCL-CE/issues/new/choose) on GitHub.
- Contribute code by forking the repository on GitHub and [submitting a pull request](https://github.com/HMCL-Community/HMCL-CE/compare).

Before contributing, please read the [Contributing Guide](./Contributing.md), which includes the following:

- [How to build and run HMCL from source](./Contributing.md#build-hmcl)
- [Adjusting HMCL behavior using debug options](./Contributing.md#debug-options)

## Contributors

[![Contributors](https://contrib.rocks/image?repo=HMCL-Community/HMCL-CE)](https://github.com/HMCL-Community/HMCL-CE/graphs/contributors)

## Acknowledgements

- [HMCL upstream project](https://github.com/HMCL-dev/HMCL): for the foundation, design, and long-term upstream work used by HMCL CE.

## License

The software is distributed under [GPLv3](https://www.gnu.org/licenses/gpl-3.0.html) license with the following additional terms:

### Additional terms under GPLv3 Section 7

1. When you distribute a modified version of the software, you must change the software name or the version number in a reasonable way in order to distinguish it from the original version. (Under [GPLv3, 7(c)](https://github.com/HMCL-Community/HMCL-CE/blob/main/LICENSE#L372-L374))

   The software name and version are defined in [`Metadata.java`](../HMCL/src/main/java/org/jackhuang/hmcl/Metadata.java) and the build configuration.

2. You must not remove the copyright declaration displayed in the software. (Under [GPLv3, 7(b)](https://github.com/HMCL-Community/HMCL-CE/blob/main/LICENSE#L368-L370))
