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
package org.jackhuang.hmcl.plugin;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies atomic, artifact-bound persistence of user plugin permission decisions.
@NotNullByDefault
public final class PluginPermissionStoreTest {
    /// Keeps decisions isolated when two packages reuse the same plugin ID and version with different bytes.
    ///
    /// @param temporaryDirectory isolated test directory
    /// @throws Exception if permission persistence fails
    @Test
    public void bindDecisionToPackageDigest(@TempDir Path temporaryDirectory) throws Exception {
        Path permissionFile = temporaryDirectory.resolve("plugin-permissions.json");
        PluginPermissionStore.Artifact approved = new PluginPermissionStore.Artifact(
                "dev.hmclce.test.artifact",
                "1.0.0",
                "a".repeat(64)
        );
        PluginPermissionStore.Artifact repacked = new PluginPermissionStore.Artifact(
                "dev.hmclce.test.artifact",
                "1.0.0",
                "b".repeat(64)
        );
        PluginPermissionStore store = new PluginPermissionStore(permissionFile);
        store.setGrantedPermissions(approved, Set.of(PluginPermission.NETWORK));

        PluginPermissionStore reloaded = new PluginPermissionStore(permissionFile);

        assertEquals(Set.of(PluginPermission.NETWORK), reloaded.getGrantedPermissions(approved));
        assertTrue(reloaded.getGrantedPermissions(repacked).isEmpty());
        assertFalse(reloaded.containsArtifact(repacked));
    }

    /// Restores the exact previous document after an installation transaction fails.
    ///
    /// @param temporaryDirectory isolated test directory
    /// @throws Exception if permission persistence fails
    @Test
    public void restoreTransactionSnapshot(@TempDir Path temporaryDirectory) throws Exception {
        Path permissionFile = temporaryDirectory.resolve("plugin-permissions.json");
        PluginPermissionStore.Artifact original = new PluginPermissionStore.Artifact(
                "dev.hmclce.test.snapshot",
                "1.0.0",
                "c".repeat(64)
        );
        PluginPermissionStore.Artifact replacement = new PluginPermissionStore.Artifact(
                "dev.hmclce.test.snapshot",
                "2.0.0",
                "d".repeat(64)
        );
        PluginPermissionStore store = new PluginPermissionStore(permissionFile);
        store.setGrantedPermissions(original, Set.of(PluginPermission.FILESYSTEM));
        PluginPermissionStore.Snapshot snapshot = store.snapshot();
        store.setGrantedPermissions(replacement, Set.of(PluginPermission.NETWORK));

        store.restore(snapshot);

        PluginPermissionStore reloaded = new PluginPermissionStore(permissionFile);
        assertEquals(Set.of(PluginPermission.FILESYSTEM), reloaded.getGrantedPermissions(original));
        assertFalse(reloaded.containsArtifact(replacement));
    }

    /// Removes records for artifacts that are no longer installed while retaining active and pending versions.
    ///
    /// @param temporaryDirectory isolated test directory
    /// @throws Exception if permission persistence fails
    @Test
    public void retainCurrentAndPendingArtifacts(@TempDir Path temporaryDirectory) throws Exception {
        PluginPermissionStore store = new PluginPermissionStore(
                temporaryDirectory.resolve("plugin-permissions.json")
        );
        PluginPermissionStore.Artifact current = new PluginPermissionStore.Artifact(
                "dev.hmclce.test.retained",
                "1.0.0",
                "e".repeat(64)
        );
        PluginPermissionStore.Artifact pending = new PluginPermissionStore.Artifact(
                "dev.hmclce.test.retained",
                "2.0.0",
                "f".repeat(64)
        );
        PluginPermissionStore.Artifact stale = new PluginPermissionStore.Artifact(
                "dev.hmclce.test.stale",
                "1.0.0",
                "1".repeat(64)
        );
        store.setGrantedPermissions(current, Set.of(PluginPermission.FILESYSTEM));
        store.setGrantedPermissions(pending, Set.of(PluginPermission.NETWORK));
        store.setGrantedPermissions(stale, Set.of(PluginPermission.PROCESS));

        store.retainArtifacts(Set.of(current, pending));

        assertTrue(store.containsArtifact(current));
        assertTrue(store.containsArtifact(pending));
        assertFalse(store.containsArtifact(stale));
    }

    /// Clears stale in-memory grants when another launcher removes the permission document.
    ///
    /// @param temporaryDirectory isolated test directory
    /// @throws Exception if permission persistence or deletion fails
    @Test
    public void clearStaleGrantsWhenDocumentDisappears(@TempDir Path temporaryDirectory) throws Exception {
        Path permissionFile = temporaryDirectory.resolve("plugin-permissions.json");
        PluginPermissionStore.Artifact artifact = new PluginPermissionStore.Artifact(
                "dev.hmclce.test.removed-document",
                "1.0.0",
                "2".repeat(64)
        );
        PluginPermissionStore writer = new PluginPermissionStore(permissionFile);
        writer.setGrantedPermissions(artifact, Set.of(PluginPermission.NETWORK));
        PluginPermissionStore staleReader = new PluginPermissionStore(permissionFile);
        assertEquals(Set.of(PluginPermission.NETWORK), staleReader.getGrantedPermissions(artifact));

        Files.delete(permissionFile);
        staleReader.removePlugin(artifact.getPluginId());

        assertTrue(staleReader.getGrantedPermissions(artifact).isEmpty());
        assertFalse(staleReader.containsArtifact(artifact));
    }

    /// Observes permission revocation written through another store without an explicit reload call.
    ///
    /// @param temporaryDirectory isolated test directory
    /// @throws Exception if permission persistence fails
    @Test
    public void observeCrossProcessRevocationBeforeNextRead(@TempDir Path temporaryDirectory) throws Exception {
        Path permissionFile = temporaryDirectory.resolve("plugin-permissions.json");
        PluginPermissionStore.Artifact artifact = new PluginPermissionStore.Artifact(
                "dev.hmclce.test.cross-process-revocation",
                "1.0.0",
                "3".repeat(64)
        );
        PluginPermissionStore writer = new PluginPermissionStore(permissionFile);
        writer.setGrantedPermissions(artifact, Set.of(PluginPermission.NETWORK));
        PluginPermissionStore reader = new PluginPermissionStore(permissionFile);
        assertEquals(Set.of(PluginPermission.NETWORK), reader.getGrantedPermissions(artifact));

        writer.setGrantedPermissions(artifact, Set.of());

        assertTrue(reader.getGrantedPermissions(artifact).isEmpty());
        assertTrue(reader.containsArtifact(artifact));

        writer.removePlugin(artifact.getPluginId());

        assertFalse(reader.containsArtifact(artifact));
    }
}
