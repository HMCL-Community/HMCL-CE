# HMCL CE 插件契约

本文档是启动器与插件之间约定行为的正式契约。`docs/PLUGIN_SYSTEM.md` 介绍插件系统的使用方式；本文定义插件作者可以依赖的保证，以及启动器要求插件必须遵守的规则。两者冲突时以实现为准，并应视为需要修复的文档缺陷。

- **插件作者的义务**：契约中标记为"必须"的条款是加载和执行的硬性前提，违反会导致安装失败或运行时异常。
- **启动器的义务**：契约中标记为"保证"的条款是插件可以依赖的行为；仅在随附新的 `schemaVersion` 或明确的重大版本公告时才会被破坏。

## 1. 契约版本

- 清单字段 `schemaVersion` 标识插件面向的契约版本。当前版本为 `4`（`PluginManifest.CURRENT_SCHEMA_VERSION`），且 `4` 同时是最低可执行版本（`MIN_EXECUTABLE_SCHEMA_VERSION`）；低于 `4` 的清单只能被读取用于迁移，不能被执行。
- `schemaVersion >= 4` 的清单必须显式声明 `launcherVersion` 版本约束。启动器只在自身版本满足约束时加载该包。
- 不破坏现有插件的新增能力（新增可选字段、新增权限枚举值、新增上下文方法）不提升 `schemaVersion`。移除或改变既有语义的提升版本。

## 2. 插件包契约

### 2.1 包格式

`.npl` 是 ZIP 容器，必须包含根目录下唯一的 `plugin.json`：

```text
example-plugin.npl
├── plugin.json
└── libs/
    └── example-plugin.jar
```

### 2.2 清单字段

| 字段 | 类型 | 要求 |
| --- | --- | --- |
| `schemaVersion` | int | 必须为 `4` |
| `id` | string | 全局唯一插件标识；同一 ID 只能存在一个已安装/已加载实例 |
| `name` | string | 显示名称，非空 |
| `version` | string | 包版本，参与依赖解析与授权绑定 |
| `type` | string | `java` / `kotlin` / `csharp` |
| `entrypoint` | string | Java/Kotlin：实现 `Plugin` 接口的完整类名；C#：固定为 `companion/extension.json` |
| `dependencies` | array | 结构化插件依赖（ID + 版本约束） |
| `permissions` | array | 声明的敏感能力，schema v3 起必填 |
| `requiredPermissions` | array | 必须获得用户授权才可执行的子集，schema v4 起必填 |
| `launcherVersion` | string | 启动器版本约束表达式，schema v4 起必填 |

**保证**：

- 清单在安装时被完整校验；校验失败的包不会进入待重启事务。
- 已验证包的清单是不可变快照。运行期通过 `PluginContext.getManifest()` 与 `Plugin.getManifest()` 返回的清单一致，且与磁盘上 `.npl` 内的清单具有相同的可执行契约字段（含契约哈希）。
- 声明 Mixin 的 Java/Kotlin 包必须同时声明并要求 `mixin` 权限，否则被拒绝。

## 3. 生命周期契约

### 3.1 回调顺序

```text
安装/更新 → 待重启事务（当前进程不执行新代码）
启动后：   onLoad(context) → onEnable()
禁用：     onDisable()
卸载：     onDisable() → onUnload() → 关闭类加载器
```

- `onLoad` 收到不可变的 `PluginContext`；`onEnable` 保证所有已声明的插件依赖均已加载并启用。
- `onUnload` 是默认空实现的可选回调，在类加载器关闭前调用，插件必须在此时释放资源、停止线程、取消注册。

### 3.2 执行环境

**保证**：每次生命周期回调执行时——

- 在 JavaFX 应用线程上执行；
- 线程上下文类加载器（TCCL）被设置为该插件的类加载器，`ServiceLoader` 等 TCCL 敏感机制按插件自身类路径工作；
- 启动器管理入口（安装、卸载、启用状态变更等）对普通插件代码拒绝调用，防止绕过用户确认。

**义务**：

- 回调不得长期阻塞主线程；耗时工作应自行调度到后台线程。
- 回调抛出的异常会被启动器记录，不会崩溃整个启动器；`onLoad`/`onEnable` 失败的插件不会进入已启用状态，其类加载器会在清理回调后被关闭。

### 3.3 Mixin 插件的额外约束

包含 Mixin 配置的 Java/Kotlin 插件：安装、启用、禁用、更新与卸载全部需要重启才能生效，且在类路径附加前和转换器执行前都会重新校验权限与信任状态。

## 4. 权限契约

### 4.1 权限集合

| 标识 | 含义 |
| --- | --- |
| `filesystem` | 访问插件私有目录之外的文件系统 |
| `network` | 打开网络连接或与远程服务通信 |
| `process` | 启动、检查或控制操作系统进程 |
| `account` | 读取或操作启动器账户与认证状态 |
| `game-launch` | 参与或修改 Minecraft 启动流程 |
| `launcher-ui` | 注册或修改启动器界面元素 |
| `clipboard` | 读写系统剪贴板 |
| `mixin` | 通过 SpongePowered Mixin 转换启动器类 |
| `native-code` | 加载原生库或调用本地代码 |

### 4.2 授权模型

- **declared**：清单 `permissions` 中声明的能力上限。未声明的能力即使被用户允许也不可用。
- **required**：`requiredPermissions` 中的能力必须全部被接受，该包才会被执行。
- **granted / effective**：有效权限 = 用户实际授权 ∩ 清单声明。每次调用官方 API 时实时求值，授权变化立即影响后续调用。
- 受保护操作通过 `PluginContext.requirePermission(...)` 强制检查；未声明抛出 `PluginPermissionException`（原因 `NOT_DECLARED`），已声明但被用户拒绝抛出（原因 `USER_DENIED`）。

**义务**：插件在调用受保护的 SDK 方法前应先用 `isPermissionGranted` 检查，并必须处理 `PluginPermissionException`。

**保证**：用户的授权决定与插件 ID、版本及该 `.npl` 的精确 SHA-256 绑定；替换包内容会使既有授权失效。

## 5. 存储契约

- **包目录**（`getPackageDirectory()`）：内容寻址、只读，更新后路径会改变。插件不得持久化此路径，也不得在此写入私有状态。
- **数据目录**（`getDataDirectory()`）：按插件 ID 分配的私有持久化目录，更新后保持不变；插件的所有持久状态必须写在这里。
- 安装、卸载、权限与启用状态的持久化均为原子事务；进程中断不会留下半更新的不一致状态。

## 6. UI 契约

- `registerSidebarItem(title, action)` 与 `registerSidebarPage(title, supplier)` 需要 `launcher-ui` 权限，否则抛出 `PluginPermissionException`。
- 页面通过惰性 Supplier 创建，由当前主题渲染在自己的内容区域内；注册的条目属于该插件 ID，禁用或卸载时随之注销。

## 7. C# Companion 契约

- `type: "csharp"` 的包 `entrypoint` 固定为 `companion/extension.json`；根 `plugin.json` 与嵌套 `extension.json` 的 `id` 和 `version` 必须完全一致。
- C# 包不能声明 JVM Mixin。
- 启动器只把验证过的 `companion/` 载荷解包到 `.hmcl/companion/extensions/<id>/`，交给独立 .NET Companion Host 执行；DLL 永远不会进入 JVM 插件类加载器。

## 8. 兼容性承诺

以下变更被视为契约破坏，必须伴随 `schemaVersion` 提升：

- 移除或重命名清单必填字段、权限标识、`Plugin` 生命周期方法；
- 改变生命周期回调的顺序或线程保证；
- 使先前合法的清单结构变为非法；
- 降低 `requirePermission` 之外官方 API 的可用性而不提供替代路径。

新增可选清单字段、新增权限枚举值、新增 `PluginContext` 方法、以及更严格的安全校验不属于破坏性变更。
