package org.jackhuang.hmcl.plugin.runtime;

import org.jetbrains.annotations.NotNullByDefault;

import java.util.Set;

/// Built-in runtime provider that executes Java and Kotlin plugins inside the launcher JVM.
///
/// This provider is always registered first and cannot be replaced by an installed runtime plugin, so
/// the Java plugin system keeps working even when every external runtime is missing.
@NotNullByDefault
public final class JavaRuntimeProvider implements RuntimeProvider {
    @Override
    public String runtimeType() {
        return PluginRuntimeTypes.JAVA;
    }

    @Override
    public Set<Integer> implementedPluginAbis() {
        return Set.of(PluginAbi.ABI_1, PluginAbi.ABI_2);
    }

    @Override
    public String describe() {
        return "Built-in Java plugin runtime (in-process JVM)";
    }
}