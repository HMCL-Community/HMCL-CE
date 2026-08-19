# HMCL CE 插件系统

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

## 仓库发现与版本认证

第三方仓库通过全小写 GitHub Topic `hmclce` 发现。社区版本可以保持未认证；显示“官方认证”的具体版本必须同时满足：仓库在最近七天内复核通过、当前签名状态快照仍批准该仓库，以及该版本的 NPL 已由审批服务重新下载并按 SHA-256、大小、tag、源码提交和包内清单独立签名。

认证以 `插件 ID + 版本 + NPL SHA-256` 为单位，不能从一个版本继承到另一个版本。仓库每周复核生成新记录时，历史 NPL 无需重新签名；客户端会验证其签发时引用的历史仓库证明，同时使用最新状态判断仓库目前是否仍获批准。

开发者的 GitHub Actions 使用短期 OIDC token 调用审批 API，不保存官方私钥或长期 API Key。工作流只提交草稿 Release 的资产 ID，服务端自行下载并计算摘要后才签发证明。仓库或精确 NPL 被已认证状态快照明确吊销时，HMCL CE 会在普通插件加载和 Mixin 引导前阻止该包运行；过期缓存不能授予新认证，但不会遗忘已经看到的明确吊销。

认证安装会保存包含完整仓库证明与 NPL 证明的本地收据，并在每次执行前重新验证签名及插件 ID、版本、SHA-256、文件大小绑定。普通插件在创建类加载器前检查；Mixin 插件在加入 JVM classpath 前检查一次，并在注册 transformer 前再次检查。每次门禁都读取最新的已签名状态缓存，因此同一启动器进程中新刷新的仓库、NPL 或签名密钥吊销也会阻止尚未加载的插件。过期快照不能授予新的认证，但其中已经认证的明确吊销继续生效。

NPL、权限记录、启用状态和认证收据属于同一个安装事务。无认证证明的替换会删除旧收据，卸载会删除对应收据；任何一步写入失败时四者一起回滚，避免旧认证被新包继承。

构建通过仓库变量 `HMCLCE_PLUGIN_ROOT_JSON` 注入公开信任根。`official-repository`、`repository-attestor`、`artifact-attestor`、`trust-status` 四个线上角色的 key 集必须互斥且 `threshold` 必须精确为 `1`；一个角色可为密钥轮换列出多个不重复的 key ID，但这不表示多签法定人数。Canonical JSON v1 只接受 `[-9007199254740991, 9007199254740991]` 内的精确整数，仓库 ID、状态版本和 NPL 大小还必须是正整数。`revokedArtifacts` 以小写 SHA-256 唯一，同一摘要不能用不同插件 ID 或版本重复声明。

启动器初始化插件系统时会立即启动异步状态刷新，即使用户从不打开插件商店也会继续执行周期检查。一次成功刷新或仍处于签名有效期内的缓存所对应的有效 `304` 后，状态缓存六小时内不会重复请求；过期缓存收到 `304` 会失败关闭。失败后按约 5 分钟、30 分钟、1 小时封顶重试，每个启动器实例带 `+/-10%` 抖动；一分钟调度轮询只负责发现到期时间，并发触发会合并为一次请求。

## 权限

`permissions` 声明插件可能使用的能力，`requiredPermissions` 是必须接受才会执行的子集。启动器保存的实际授权与插件 ID、版本和 `.npl` SHA-256 绑定。插件调用受保护的 SDK 方法前应检查权限并处理 `PluginPermissionException`。

官方开发示例、打包校验脚本与 API 说明位于 `HMCL-CE-Plugin-SDK` 目录，其中同样仅保留 Java/Kotlin 插件支持。
