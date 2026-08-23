package org.jackhuang.hmcl.plugin.runtime;

import org.jetbrains.annotations.NotNullByDefault;

import java.util.Set;

/// SPI implemented by every plugin runtime provider.
///
/// The launcher ships exactly one provider (Java) in-process; additional providers are contributed by
/// runtime plugins installed from the plugin store, following the Fabric-Loader style split between a
/// runtime provider and the language plugins that depend on it.
@NotNullByDefault
public interface RuntimeProvider {
    /// Canonical runtime identifier this provider serves, such as java or dotnet.
    String runtimeType();

    /// ABI generations this provider can execute, for example [1, 2] for a current host.
    Set<Integer> implementedPluginAbis();

    /// Returns whether the provider can execute a package that requires the given ABI generation.
    default boolean supportsAbi(int requiredAbi) {
        return implementedPluginAbis().contains(requiredAbi);
    }

    /// Human-readable provider description shown in plugin manager diagnostics.
    String describe();
}