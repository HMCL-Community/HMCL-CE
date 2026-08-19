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
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/// Immutable in-process registry of exact artifacts activated by the premain Mixin Agent.
///
/// The registry is populated only after class-path attachment, Mixin configuration, and transformer installation all
/// succeed. System properties remain diagnostics and never authorize ordinary lifecycle loading.
@NotNullByDefault
public final class PluginAgentSnapshot {
    /// Empty snapshot used before premain succeeds or after Agent initialization fails.
    private static final PluginAgentSnapshot EMPTY = new PluginAgentSnapshot(List.of());

    /// Current process snapshot, replaced atomically after successful Agent initialization.
    private static volatile PluginAgentSnapshot current = EMPTY;

    /// Active registrations indexed by plugin ID.
    private final @Unmodifiable Map<String, Registration> registrations;

    /// Creates an immutable snapshot from validated unique registrations.
    ///
    /// @param values exact Agent registrations
    private PluginAgentSnapshot(Collection<Registration> values) {
        Map<String, Registration> indexed = new LinkedHashMap<>();
        for (Registration registration : values) {
            @Nullable Registration previous = indexed.putIfAbsent(
                    registration.identity.getPluginId(),
                    registration
            );
            if (previous != null) {
                throw new IllegalArgumentException(
                        "Duplicate Agent registration for " + registration.identity.getPluginId()
                );
            }
        }
        registrations = Map.copyOf(indexed);
    }

    /// Returns the immutable registry published by premain for this process.
    ///
    /// @return current Agent snapshot
    public static PluginAgentSnapshot current() {
        return current;
    }

    /// Publishes the exact artifacts whose transformations are active.
    ///
    /// @param registrations successful Agent registrations
    static void publish(@Unmodifiable List<Registration> registrations) {
        current = registrations.isEmpty() ? EMPTY : new PluginAgentSnapshot(registrations);
    }

    /// Clears all authorization before Agent initialization begins or after it fails.
    static void clear() {
        current = EMPTY;
    }

    /// Returns whether the Agent activated the exact artifact and Mixin declaration digest.
    ///
    /// @param identity exact installed artifact
    /// @param mixinConfigurationDigest digest of the manifest's ordered Mixin configuration list
    /// @return whether this exact artifact is active
    public boolean confirms(PluginArtifactIdentity identity, String mixinConfigurationDigest) {
        @Nullable Registration registration = registrations.get(identity.getPluginId());
        return registration != null
                && registration.identity.equals(identity)
                && registration.mixinConfigurationDigest.equals(mixinConfigurationDigest);
    }

    /// Returns whether a loaded lifecycle class came from one of the exact JARs appended for an active artifact.
    ///
    /// @param identity exact installed artifact
    /// @param mixinConfigurationDigest digest of the ordered Mixin declaration
    /// @param pluginClass lifecycle entry-point class loaded without initialization
    /// @return whether the Agent registration owns the class code source
    public boolean ownsClass(
            PluginArtifactIdentity identity,
            String mixinConfigurationDigest,
            Class<?> pluginClass
    ) {
        @Nullable Registration registration = registrations.get(identity.getPluginId());
        if (registration == null
                || !registration.identity.equals(identity)
                || !registration.mixinConfigurationDigest.equals(mixinConfigurationDigest)
                || pluginClass.getProtectionDomain() == null
                || pluginClass.getProtectionDomain().getCodeSource() == null
                || pluginClass.getProtectionDomain().getCodeSource().getLocation() == null) {
            return false;
        }
        try {
            Path codeSource = Path.of(pluginClass.getProtectionDomain().getCodeSource().getLocation().toURI())
                    .toAbsolutePath()
                    .normalize();
            return registration.classPathEntries.contains(codeSource);
        } catch (Exception exception) {
            return false;
        }
    }

    /// Returns whether any artifact with the supplied ID is active for diagnostics.
    ///
    /// @param pluginId plugin identifier
    /// @return whether one Agent registration uses the ID
    public boolean containsPlugin(String pluginId) {
        return registrations.containsKey(pluginId);
    }

    /// Returns the exact active artifact identities.
    ///
    /// @return immutable active artifact list
    public @Unmodifiable List<PluginArtifactIdentity> getActiveArtifacts() {
        return registrations.values().stream().map(Registration::identity).toList();
    }

    /// Calculates an unambiguous digest of an ordered Mixin configuration declaration.
    ///
    /// @param mixinConfigurations validated ordered configuration names
    /// @return lower-case SHA-256 digest
    public static String calculateMixinConfigurationDigest(
            @Unmodifiable List<String> mixinConfigurations
    ) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
        for (String configuration : mixinConfigurations) {
            byte @Unmodifiable [] bytes = configuration.getBytes(StandardCharsets.UTF_8);
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
            digest.update(bytes);
        }
        return java.util.HexFormat.of().formatHex(digest.digest());
    }

    /// Creates one exact registration for transfer from bootstrap discovery to premain.
    ///
    /// @param identity exact package identity
    /// @param mixinConfigurationDigest ordered Mixin declaration digest
    /// @param classPathEntries exact Agent JAR paths
    /// @return immutable registration
    static Registration registration(
            PluginArtifactIdentity identity,
            String mixinConfigurationDigest,
            @Unmodifiable List<Path> classPathEntries
    ) {
        return new Registration(identity, mixinConfigurationDigest, classPathEntries);
    }

    /// Exact artifact registration owned by the successful Agent initialization.
    @NotNullByDefault
    static final class Registration {
        /// Exact package identity.
        private final PluginArtifactIdentity identity;

        /// Ordered Mixin declaration digest.
        private final String mixinConfigurationDigest;

        /// Exact JARs appended to the system loader for this artifact.
        private final @Unmodifiable List<Path> classPathEntries;

        /// Creates one immutable registration.
        ///
        /// @param identity exact package identity
        /// @param mixinConfigurationDigest ordered Mixin declaration digest
        /// @param classPathEntries exact Agent JAR paths
        private Registration(
                PluginArtifactIdentity identity,
                String mixinConfigurationDigest,
                @Unmodifiable List<Path> classPathEntries
        ) {
            this.identity = identity;
            this.mixinConfigurationDigest = Objects.requireNonNull(mixinConfigurationDigest);
            this.classPathEntries = classPathEntries.stream()
                    .map(path -> path.toAbsolutePath().normalize())
                    .toList();
        }

        /// Returns the exact package identity.
        ///
        /// @return artifact identity
        PluginArtifactIdentity identity() {
            return identity;
        }
    }
}
