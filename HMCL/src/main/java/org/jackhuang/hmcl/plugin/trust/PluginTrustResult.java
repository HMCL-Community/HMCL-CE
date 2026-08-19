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
package org.jackhuang.hmcl.plugin.trust;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/// Immutable trust decision and credential-safe diagnostic metadata.
@NotNullByDefault
public record PluginTrustResult(
        PluginTrustLevel level,
        String detail,
        @Nullable String keyId,
        @Nullable String certificateSerial,
        @Nullable PluginCertificationReceipt certificationReceipt
) {
    /// Validates one trust result at construction.
    public PluginTrustResult {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(detail, "detail");
    }

    /// Creates an unsigned community decision.
    public static PluginTrustResult community() {
        return new PluginTrustResult(PluginTrustLevel.COMMUNITY, "unsigned community manifest", null, null, null);
    }

    /// Creates a rejected signing decision.
    public static PluginTrustResult rejected(String detail) {
        return new PluginTrustResult(PluginTrustLevel.REJECTED, detail, null, null, null);
    }

    /// Creates an official repository decision.
    public static PluginTrustResult official(String keyId) {
        return new PluginTrustResult(PluginTrustLevel.OFFICIAL, "official repository signature", keyId, null, null);
    }

    /// Creates a certified developer decision.
    public static PluginTrustResult certified(String keyId, String serial) {
        return new PluginTrustResult(
                PluginTrustLevel.CERTIFIED,
                "certified developer signature",
                keyId,
                serial,
                null
        );
    }

    /// Creates a community-review certification delegated by the signed official plugin registry.
    ///
    /// The registry reference itself carries the trust decision, so this result has no release signer,
    /// verification serial, or installation receipt.
    public static PluginTrustResult certifiedByOfficialReference() {
        return new PluginTrustResult(
                PluginTrustLevel.CERTIFIED,
                "referenced by official plugin registry",
                null,
                null,
                null
        );
    }

    /// Attaches the two proof envelopes required to persist this certified decision safely.
    ///
    /// @param receipt exact proof-backed installation receipt
    /// @return certified decision carrying the receipt
    public PluginTrustResult withCertificationReceipt(PluginCertificationReceipt receipt) {
        if (level != PluginTrustLevel.CERTIFIED
                || keyId == null
                || certificateSerial == null
                || !keyId.equals(receipt.artifactSignerKeyId())
                || !certificateSerial.equals(receipt.repositoryVerificationId())) {
            throw new IllegalStateException("Only the matching certified decision can carry this receipt");
        }
        return new PluginTrustResult(level, detail, keyId, certificateSerial, receipt);
    }

    /// Returns whether installation may continue to permission review.
    public boolean canInstall() {
        return level.isInstallable();
    }

    /// Returns whether installation requires a source warning.
    public boolean requiresSourceWarning() {
        return level.requiresSourceWarning();
    }
}
