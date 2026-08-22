# HMCL CE 插件系统

启动器与插件之间约定行为的正式契约见 [插件契约](./PLUGIN_CONTRACT.md)。

HMCL CE 支持运行在 JVM 上的 Java/Kotlin 插件，以及由独立 .NET Companion Host 执行的 C# 扩展。插件包使用 `.npl`（ZIP）格式，并由启动器在安装、更新和启动时验证清单、权限、依赖和包身份。

> 破坏性变更：`type: "javascript"` 已不再受支持。CE 不会下载、安装或调用 Node.js，也不会执行旧 JavaScript 插件包。

## 插件类型

| `plugin.json` type | 运行方式 |
| --- | --- |
| `java` | 由 Java 插件类加载器加载 |
| `kotlin` | 使用同一 JVM 类加载器加载 Kotlin 字节码 |
| `csharp` | 启动前解包 `companion/` 载荷，由 .NET Companion Host 加载 |

Java 与 Kotlin 插件的 `entrypoint` 必须是实现 `Plugin` 接口的完整类名。JavaScript 文件入口、`hmcl-ui-v1` 协议和 Node.js 运行时均已移除。

C# 包的 `entrypoint` 固定为 `companion/extension.json`。根 `plugin.json` 与嵌套 `extension.json` 的 `id` 和 `version` 必须完全一致；C# 包不能声明 JVM Mixin。CE 只解包已验证的 `companion/` 载荷到 `.hmcl/companion/extensions/<id>/`，不会把 DLL 交给 JVM 插件类加载器。

## 插件包

```text
example-plugin.npl
├── plugin.json
└── libs/
    └── example-plugin.jar
```

```text
example-companion.npl
├── plugin.json
└── companion/
    ├── extension.json
    ├── Example.Companion.dll
    └── Example.Companion.deps.json
```

```json
{
  "schemaVersion": 4,
  "id": "com.example.ce.plugin",
  "name": "CE Plugin",
  "version": "1.0.0",
  "type": "java",
  "entrypoint": "com.example.ce.PluginMain",
  "dependencies": [],
  "permissions": ["launcher-ui"],
  "requiredPermissions": [],
  "launcherVersion": ">=26.8"
}
```

## 生命周期

1. CE 读取 `.npl` 的 `plugin.json`，验证 schema、插件类型、依赖和授权。
2. 新安装或更新的包进入待重启事务，当前进程不会执行它。
3. 下一次启动后，CE 创建插件上下文并依次调用 `onLoad`、`onEnable`。
4. 禁用与卸载时调用 `onDisable`、`onUnload`。

包含 `mixins` 的 Java/Kotlin 插件还必须声明并要求 `mixin` 权限；它们的安装、启用、禁用、更新与卸载都需要重启。

## 仓库发现与社区审核

第三方仓库通过全小写 GitHub Topic `hmclce` 自动发现，也可以由用户添加自定义仓库源。插件是否进入社区仓库、是否值得信任以及后续维护情况由社区公开审核；未获得官方认证不会阻止插件被发现、安装或更新。

HMCL CE 会验证仓库清单、插件 ID、版本、下载地址、SHA-256、依赖和权限声明，并在安装前展示来源与权限信息。用户仍应结合仓库源码、Release 记录、维护者身份和社区反馈自行判断。包含 Mixin 的插件必须经过额外确认，并在重启后加载。

如果将来提供带签名的官方索引，其中引用的同 ID、同仓库插件可以显示为已认证，但这只是来源标识，不是安装前提。CE 不再要求社区开发者接入审批 API、提交逐版本签发材料、维护在线状态证明或等待官方人员定期复核。

旧版认证字段和本地收据仅为兼容已有数据而保留，不参与社区插件的日常审核，也不会触发后台在线认证服务。插件包、权限记录、启用状态和安装事务仍保持原子更新，避免更新失败后留下不一致状态。

## 权限

`permissions` 声明插件可能使用的能力，`requiredPermissions` 是必须接受才会执行的子集。启动器保存的实际授权与插件 ID、版本和 `.npl` SHA-256 绑定。插件调用受保护的 SDK 方法前应检查权限并处理 `PluginPermissionException`。

官方开发示例、打包校验脚本与 API 说明位于 `HMCL-CE-Plugin-SDK` 目录，其中同样仅保留 Java/Kotlin 插件支持。
