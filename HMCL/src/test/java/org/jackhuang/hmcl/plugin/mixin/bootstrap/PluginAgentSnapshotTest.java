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
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies exact artifact and Mixin-declaration binding in the in-process Agent registry.
@NotNullByDefault
public final class PluginAgentSnapshotTest {
    /// Confirms only the exact ID, version, package SHA-256, and ordered Mixin declaration.
    ///
    /// @throws Exception if the test code source cannot be resolved
    @Test
    public void confirmExactArtifactAndConfiguration() throws Exception {
        PluginArtifactIdentity identity = new PluginArtifactIdentity(
                "dev.hmclce.test.agent-snapshot",
                "1.0.0",
                "a".repeat(64)
        );
        String digest = PluginAgentSnapshot.calculateMixinConfigurationDigest(List.of("first.json", "second.json"));
        Path codeSource = Path.of(Objects.requireNonNull(
                PluginAgentSnapshotTest.class.getProtectionDomain().getCodeSource()
        ).getLocation().toURI()).toAbsolutePath().normalize();
        PluginAgentSnapshot.Registration registration = PluginAgentSnapshot.registration(
                identity,
                digest,
                List.of(codeSource)
        );

        PluginAgentSnapshot.publish(List.of(registration));
        try {
            PluginAgentSnapshot snapshot = PluginAgentSnapshot.current();
            assertTrue(snapshot.confirms(identity, digest));
            assertTrue(snapshot.ownsClass(identity, digest, PluginAgentSnapshotTest.class));
            assertFalse(snapshot.confirms(
                    new PluginArtifactIdentity(identity.getPluginId(), identity.getVersion(), "b".repeat(64)),
                    digest
            ));
            assertFalse(snapshot.confirms(
                    identity,
                    PluginAgentSnapshot.calculateMixinConfigurationDigest(List.of("second.json", "first.json"))
            ));
        } finally {
            PluginAgentSnapshot.clear();
        }
    }

    /// Rejects multiple active artifacts that claim the same plugin ID.
    @Test
    public void rejectDuplicatePluginId() {
        PluginArtifactIdentity first = new PluginArtifactIdentity(
                "dev.hmclce.test.agent-duplicate",
                "1.0.0",
                "c".repeat(64)
        );
        PluginArtifactIdentity second = new PluginArtifactIdentity(
                first.getPluginId(),
                "2.0.0",
                "d".repeat(64)
        );
        PluginAgentSnapshot.Registration firstRegistration = PluginAgentSnapshot.registration(
                first,
                PluginAgentSnapshot.calculateMixinConfigurationDigest(List.of("first.json")),
                List.of()
        );
        PluginAgentSnapshot.Registration secondRegistration = PluginAgentSnapshot.registration(
                second,
                PluginAgentSnapshot.calculateMixinConfigurationDigest(List.of("second.json")),
                List.of()
        );

        try {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> PluginAgentSnapshot.publish(List.of(firstRegistration, secondRegistration))
            );
        } finally {
            PluginAgentSnapshot.clear();
        }
    }
}
