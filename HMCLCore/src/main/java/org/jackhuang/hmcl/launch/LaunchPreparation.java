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
package org.jackhuang.hmcl.launch;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/// Couples a public launch process plan with launcher-private resources and scoped secret values.
@NotNullByDefault
public final class LaunchPreparation {
    /// Immutable process plan exposed to Hook transformation.
    private final LaunchProcessPlan plan;

    /// Launch-scoped secret values stored outside the public process plan.
    private final @Unmodifiable Map<String, String> secrets;

    /// Optional temporary native-library link required by legacy Minecraft versions.
    private final @Nullable Path temporaryNativeLink;

    /// Selected native-library source directory.
    private final Path nativeFolder;

    /// Effective Java native-library extraction directory.
    private final Path javaNativeFolder;

    /// Charset used to decode process output.
    private final Charset outputEncoding;

    /// Creates one complete launch preparation.
    ///
    /// @param plan public immutable process plan
    /// @param secrets launch-scoped secret values
    /// @param temporaryNativeLink optional private native-library link
    /// @param nativeFolder selected native directory
    /// @param javaNativeFolder effective Java native extraction directory
    /// @param outputEncoding process output encoding
    public LaunchPreparation(
            LaunchProcessPlan plan,
            Map<String, String> secrets,
            @Nullable Path temporaryNativeLink,
            Path nativeFolder,
            Path javaNativeFolder,
            Charset outputEncoding
    ) {
        this.plan = Objects.requireNonNull(plan, "plan");
        this.secrets = copySecrets(secrets);
        this.temporaryNativeLink = temporaryNativeLink;
        this.nativeFolder = Objects.requireNonNull(nativeFolder, "nativeFolder");
        this.javaNativeFolder = Objects.requireNonNull(javaNativeFolder, "javaNativeFolder");
        this.outputEncoding = Objects.requireNonNull(outputEncoding, "outputEncoding");
    }

    /// Returns the immutable process plan.
    ///
    /// @return process plan
    public LaunchProcessPlan plan() {
        return plan;
    }

    /// Returns an immutable snapshot of launch-scoped secrets.
    ///
    /// @return immutable secret map
    public @Unmodifiable Map<String, String> secrets() {
        return secrets;
    }

    /// Returns the optional private temporary native link.
    ///
    /// @return temporary link or `null`
    public @Nullable Path temporaryNativeLink() {
        return temporaryNativeLink;
    }

    /// Returns the selected native directory.
    ///
    /// @return native directory
    public Path nativeFolder() {
        return nativeFolder;
    }

    /// Returns the effective Java native extraction directory.
    ///
    /// @return Java native directory
    public Path javaNativeFolder() {
        return javaNativeFolder;
    }

    /// Returns the process output encoding.
    ///
    /// @return output encoding
    public Charset outputEncoding() {
        return outputEncoding;
    }

    /// Returns a copy with a replacement public process plan.
    ///
    /// @param replacement replacement process plan
    /// @return updated immutable preparation
    public LaunchPreparation withPlan(LaunchProcessPlan replacement) {
        return new LaunchPreparation(replacement, secrets, temporaryNativeLink,
                nativeFolder, javaNativeFolder, outputEncoding);
    }

    /// Returns a copy with replacement launch-scoped secret values.
    ///
    /// @param replacement replacement secret values
    /// @return updated immutable preparation
    public LaunchPreparation withSecrets(Map<String, String> replacement) {
        return new LaunchPreparation(plan, replacement, temporaryNativeLink,
                nativeFolder, javaNativeFolder, outputEncoding);
    }

    /// Returns a diagnostic representation containing only secret slot names.
    ///
    /// @return redacted preparation representation
    @Override
    public String toString() {
        return "LaunchPreparation[plan=" + plan + ", secretSlots=" + secrets.keySet()
                + ", temporaryNativeLink=" + temporaryNativeLink + ", nativeFolder=" + nativeFolder
                + ", javaNativeFolder=" + javaNativeFolder + ", outputEncoding=" + outputEncoding + "]";
    }

    /// Copies secret values while rejecting null keys and values.
    ///
    /// @param source secret source
    /// @return immutable copied secrets
    private static @Unmodifiable Map<String, String> copySecrets(Map<String, String> source) {
        Objects.requireNonNull(source, "secrets");
        Map<String, String> copy = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : source.entrySet()) {
            copy.put(Objects.requireNonNull(entry.getKey(), "Secret slot"),
                    Objects.requireNonNull(entry.getValue(), "Secret value"));
        }
        return Collections.unmodifiableMap(copy);
    }
}
