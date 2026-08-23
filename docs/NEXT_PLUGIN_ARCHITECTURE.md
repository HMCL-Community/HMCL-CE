# HMCL-CE Next（下一代插件架构）

> HMCL 不捆绑 Python/C#/Rust/JS 运行时；HMCL 支持“插件运行时”，而这些语言只是不同的运行时实现。

## 分支与版本

main 为当前稳定产品线（26.x.y），next 为下一代产品线（27.x.y-next.N），feature/* 从 next 拉出。
next 允许对插件 ABI、运行时与商店架构做破坏性变更；27.x 成熟后并回 main，next 再指向 28.x。
插件 ABI、Runtime、Plugin 各自独立版本号。

## 架构总览

- 运行时即插件：本体只带 Java Runtime + Plugin Manager；dotnet/python/js 等运行时
  与 rust/c-c++ 原生插件都是普通插件，安装首个依赖者时按需下载，卸载最后一个
  依赖者时一并移除（Fabric Loader -> Fabric Mod 模式）。
- Rust 插件是编译后的原生工件（dll/so/dylib），不是 .rs 源码。

## HMCL Plugin ABI

ABI 是插件兼容边界，与启动器版本解耦：实现 ABI N 的启动器可执行所有要求 ABI M（M <= N）的包。
运行时提供者声明实现的 ABI 代际，升级运行时不会让依赖它的插件集体失效。

- ABI_1 = 1：26.x JVM-only 插件系统（manifest schema v4）。
- ABI_2 = 2：27.x——运行时提供者 + 平台目标 + schema v5。

见 org.jackhuang.hmcl.plugin.runtime.PluginAbi。

## plugin.json schema v5

v5 在 v4 基础上新增三个字段：

- runtime：运行时标识。v5 必填；v4 清单缺省读取为 java，完全兼容。保留标识
  java、dotnet、python、javascript、native；自定义标识须为小写字母/数字/连字符，
  最长 32 字符。
- abi：要求的 ABI 代际，缺省 1，当前接受 1 或 2。
- platforms：平台目标数组，缺省表示平台无关。标识为 os 或 os-arch；
  OS：windows / linux / macos / freebsd；架构：x86 / x64 / arm32 / arm64 /
  riscv64 / loongarch64 / mips64。无效或重复条目在 manifest 校验时被拒绝。

平台匹配：windows 覆盖该 OS 全部架构，windows-x64 只覆盖 x64。
启动器用 PluginPlatformTarget.current() 检测当前目标。

## 运行时提供者

- PluginRuntimeTypes：运行时标识规范与校验。
- RuntimeProvider：SPI——runtimeType、implementedPluginAbis、supportsAbi、describe。
- JavaRuntimeProvider：内置 Java 提供者，实现 ABI 1 与 2，不可被移除。
- RuntimeProviderRegistry：进程级注册表；运行时插件安装后注册自己的提供者。

后续：语言运行时以普通插件上架商店；插件管理器解析安装计划时自动补装缺失
运行时（依赖形如 dotnet-runtime >= 8）。

## 权限三级

PluginPermissionTier 将现有九种权限分级，驱动安装时同意界面警示强度：

- NORMAL：launcher-ui、game-launch、clipboard（启动器表面，声明即装）
- ADVANCED：filesystem、network、process、account（触达系统或账户数据）
- DANGEROUS：mixin、native-code（可执行任意代码或修改启动器行为）

Shell、原生 DLL、JVM Patch 属高权限能力，由统一 Permission + ABI + Platform
Capability 系统控制，不以“能执行 shell”为安全边界。

## 商店索引（后续）

商店从“文件商店”升级为“包索引”：版本条目带 targets 平台工件映射，客户端
只下载当前平台工件；无匹配时直接提示“当前平台不支持”，而非下载错误二进制
后在加载时失败。工件校验沿用 SHA-256 与签名体系。
