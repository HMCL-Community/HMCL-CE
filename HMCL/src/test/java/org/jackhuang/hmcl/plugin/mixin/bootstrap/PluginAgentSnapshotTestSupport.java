/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2026 huangyuhui <huanghongxun2008@126.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.jackhuang.hmcl.plugin.mixin.bootstrap;

import org.jackhuang.hmcl.plugin.PluginArtifactIdentity;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/// Publishes exact Agent snapshots for lifecycle gate tests outside the bootstrap package.
@NotNullByDefault
public final class PluginAgentSnapshotTestSupport {
    /// Prevents instantiation of the test bridge.
    private PluginAgentSnapshotTestSupport() {
    }

    /// Publishes one exact artifact registration that owns the supplied already-loaded test class.
    ///
    /// @param identity exact package artifact
    /// @param mixinConfigurations ordered Mixin configuration declaration
    /// @param ownedClass system-loaded class whose code source represents the Agent class path
    /// @throws URISyntaxException if the test code source cannot be converted to a path
    public static void publish(
            PluginArtifactIdentity identity,
            @Unmodifiable List<String> mixinConfigurations,
            Class<?> ownedClass
    ) throws URISyntaxException {
        Path codeSource = Path.of(Objects.requireNonNull(
                ownedClass.getProtectionDomain().getCodeSource()
        ).getLocation().toURI()).toAbsolutePath().normalize();
        PluginAgentSnapshot.publish(List.of(PluginAgentSnapshot.registration(
                identity,
                PluginAgentSnapshot.calculateMixinConfigurationDigest(mixinConfigurations),
                List.of(codeSource)
        )));
    }

    /// Clears every test registration and restores the fail-closed empty snapshot.
    public static void clear() {
        PluginAgentSnapshot.clear();
    }
}
