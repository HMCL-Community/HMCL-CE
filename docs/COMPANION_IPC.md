# HMCL CE Companion IPC

HMCL CE can optionally start the separate `.NET 10` Companion Host. CE's JVM and the Companion remain independent
processes and exchange only authenticated local IPC frames; they never share runtime objects.

## Configuration

Set one of the following to a local Host executable or managed DLL:

```text
HMCL_CE_COMPANION_PATH=D:\HMCL-CE-Companion\src\HMCL.CE.Companion.Host\bin\Debug\net10.0\HMCL.CE.Companion.Host.dll
```

```text
-Dhmcl.ce.companion.path=D:\path\to\HMCL.CE.Companion.Host.dll
```

Managed DLLs launch through `dotnet`; override its command with `-Dhmcl.ce.companion.dotnet=<command>` when required.
Windows distribution builds embed a self-contained `win-x64` Companion Host. When no external path is configured, CE
extracts that bundled executable once to its local Companion runtime cache and starts it without requiring a machine-wide
.NET Runtime installation. An explicit path still takes precedence over the bundled Host.

Each child Host receives a new endpoint and random one-time token. Windows uses a Named Pipe; Linux and macOS use a Unix
Domain Socket. CE authenticates with `bridge.hello`, validates `bridge.ping`, and supplies `launcher.describe` before
the Host enables configured C# extensions.

## C# Extension Directories

By default CE passes these directories to the Host:

```text
<CE local home>/companion/extensions
<CE local home>/companion/extension-data
```

Override them with `hmcl.ce.companion.extensions` and `hmcl.ce.companion.extension_data`. The first contains C# extension
packages; the second contains one private persistent directory per extension.

## JVM Mixin Hooks

C# extensions can request an explicitly registered JVM Mixin Hook on the live authenticated connection. They cannot access
JVM objects directly and cannot request a bytecode transformation. CE code or a JVM Mixin registers a stable hook name:

```java
CeJvmMixinHooks.register("example.action", invocation -> {
    JsonObject result = new JsonObject();
    result.addProperty("accepted", true);
    return result;
});
```

The calling C# extension must declare `jvm.mixin.invoke` in `extension.json`. A hook name that CE has not registered is
rejected with `unknown_mixin_hook`.
