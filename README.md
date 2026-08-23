<div align="center">
  <img src="HMCL/src/main/resources/assets/img/icon@8x.png" alt="HMCL CE" width="80">
  <h1>HMCL CE</h1>
  <p>社区驱动、跨平台、可扩展的 Minecraft 启动器</p>

  [![Release](https://img.shields.io/github/v/release/HMCL-Community/HMCL-CE?style=flat-square)](https://github.com/HMCL-Community/HMCL-CE/releases)
  [![Java CI](https://github.com/HMCL-Community/HMCL-CE/actions/workflows/gradle.yml/badge.svg)](https://github.com/HMCL-Community/HMCL-CE/actions/workflows/gradle.yml)
  [![License](https://img.shields.io/badge/license-GPLv3-blue?style=flat-square)](LICENSE)
  [![QQ Group](https://img.shields.io/badge/QQ-%E7%A4%BE%E5%8C%BA%E7%BE%A4-12B7F5?style=flat-square&logo=qq&logoColor=white)](https://qun.qq.com/universal-share/share?ac=1&authKey=pOw%2BTFtCWoazxuhJo6aSk%2BnmPW3lVVH0t5LCnE3ya2EFzj%2BEy9kHLci1ahepvW6t&busi_data=eyJncm91cENvZGUiOiIxMDk3MTIxNzUxIiwidG9rZW4iOiJXVzhRZkZEYit3N1BRT1o2dWNjQkw4WElFYjR0ZFQ3R01vYVo3bmsvR2htZThZNXhXOTgyQXpZYU5Ua2NNU3VsIiwidWluIjoiMzYxNjQzOTUwNSJ9&data=2bSHbitmgkNabOpdNYYdvazyW7GDY_7Mj7eeonhQ7whmvotadJdwtlQC5Sg60CxIo-uu9ZukUgzQUcQYGRMy6w&svctype=4&tempid=h5_group_info)
  [![Bilibili](https://img.shields.io/badge/Bilibili-%E7%A4%BE%E5%8C%BA%E5%8A%A8%E6%80%81-00A1D6?style=flat-square&logo=bilibili&logoColor=white)](https://b23.tv/CTHjMv6)
</div>

## 项目简介

HMCL CE 是基于 [Hello Minecraft! Launcher（HMCL）](https://github.com/HMCL-dev/HMCL) 持续开发的开源 Minecraft 启动器。它保留 HMCL 的实例管理、自动安装、模组与整合包管理、多账户和跨平台能力，并维护独立的 CE 发布与扩展体系。

CE 目前提供：

- Java、Kotlin 插件扩展；
- 可配置的插件商店来源与 GitHub `hmclce` Topic 发现；
- 插件权限、依赖、安装事务与运行时隔离；
- 由社区完成插件审核，不以官方认证作为社区插件可用的前提；
- GitHub Releases 自动更新、SHA-256 完整性校验和 CE 独立发布签名；
- Windows、Linux、macOS、FreeBSD，以及 x86、ARM、RISC-V、MIPS、LoongArch 等平台支持。

平台兼容详情见 [支持平台](docs/PLATFORM_zh.md)，插件格式与开发约定见 [插件系统文档](docs/PLUGIN_SYSTEM.md)，插件开发 SDK 与示例见 [Plugin SDK](https://github.com/HMCL-Community/HMCL-CE-Plugin-SDK)。

## 下载与运行

当前稳定版本为 **HMCL CE 26.8 Release 1**：

- [下载 HMCL CE 26.8 Release 1](https://github.com/HMCL-Community/HMCL-CE/releases/tag/v26.8-release.1)
- [查看全部版本](https://github.com/HMCL-Community/HMCL-CE/releases)

下载适合当前系统的 `.exe`、`.jar`、`.sh` 或 `.deb` 文件，并使用发布页附带的 `.sha256` 文件核对完整性。直接运行 `.jar` 需要 Java 17 或更高版本，推荐使用 Java 21。

## 从源码构建

准备 JDK 17 或更高版本后，在仓库根目录执行：

```bash
./gradlew build
```

Windows PowerShell：

```powershell
.\gradlew.bat build
```

构建产物位于 `HMCL/build/libs/`。运行开发构建：

```powershell
.\gradlew.bat :HMCL:run
```

持续集成、测试和发布均由 [GitHub Actions](https://github.com/HMCL-Community/HMCL-CE/actions) 完成，不再依赖原 Jenkins 服务。

## 参与贡献

- [报告问题或提出功能建议](https://github.com/HMCL-Community/HMCL-CE/issues/new/choose)
- [提交 Pull Request](https://github.com/HMCL-Community/HMCL-CE/compare)
- 阅读 [贡献指南](docs/Contributing_zh.md)

插件审核与生态治理由社区共同完成。插件作者可以通过公开仓库、Release 和 `hmclce` Topic 发布插件，用户可以自行添加、检查和管理插件来源。

## 上游归属与致谢

HMCL CE 基于 [HMCL-dev/HMCL](https://github.com/HMCL-dev/HMCL) 的代码开发，感谢上游维护者与所有贡献者提供的基础代码、设计和长期工作。

CE 的独立开发者与后续贡献记录可在 [本仓库贡献者页面](https://github.com/HMCL-Community/HMCL-CE/graphs/contributors) 查看。软件界面内保留上游作者、依赖项目及贡献者致谢信息。

## 开源许可与原声明

本项目依据 [GNU General Public License Version 3](LICENSE) 发布，并保留原 README 所声明的 GPLv3 第 7 条附加要求：

1. 分发本软件的修改版本时，必须以合理方式修改软件名称或版本号，使其能够与原始版本区分。该要求依据 GPLv3 Section 7(c)。
2. 不得移除软件界面中显示的版权声明。该要求依据 GPLv3 Section 7(b)。

完整许可文本与条款以仓库中的 [LICENSE](LICENSE) 为准。任何再分发和衍生版本均须继续保留适用的作者归属、版权声明和许可信息。
