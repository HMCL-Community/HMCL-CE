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
package org.jackhuang.hmcl.plugin.store;

import org.jackhuang.hmcl.util.StringUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;

/// Produces credential-safe compact labels for configured plugin sources.
@NotNullByDefault
public final class PluginSourceLabels {
    /// Matches explicit hierarchical URI tokens embedded in untrusted metadata.
    private static final Pattern URI_TOKEN_PATTERN = Pattern.compile(
            "\\b[a-z][a-z0-9+.-]*://[^\\s]+",
            Pattern.CASE_INSENSITIVE
    );

    /// Prevents construction of this static utility class.
    private PluginSourceLabels() {
    }

    /// Returns a safe local alias, remote registry name, or credential-free source URL fallback.
    ///
    /// @param source persisted source configuration
    /// @param remoteName optional remote registry name
    /// @return compact source label without URL credentials, query, or fragment
    public static String displayName(PluginSource source, @Nullable String remoteName) {
        @Nullable String alias = source.getAlias();
        if (isSafeHumanLabel(alias)) {
            return Objects.requireNonNull(alias).trim();
        }
        if (isSafeHumanLabel(remoteName)) {
            return Objects.requireNonNull(remoteName).trim();
        }
        return sourceUrlFallback(source.getUrl());
    }

    /// Returns a credential-free compact label derived from a configured source URL.
    ///
    /// @param source source whose configured URL supplies the fallback
    /// @return safe host and final path component, or a generic marker
    public static String sourceUrlFallback(PluginSource source) {
        return sourceUrlFallback(source.getUrl());
    }

    /// Returns a credential-free URI suitable for diagnostics and logs.
    ///
    /// Arbitrary paths can contain bearer tokens or webhook secrets even without URI delimiters, so diagnostics retain
    /// only the validated scheme, host, and port. Full configured URLs remain available only in explicit source details.
    ///
    /// @param url source, manifest, or package URL
    /// @return URI origin without user information, path, query, or fragment, or a generic marker when malformed
    public static String diagnosticUrl(String url) {
        try {
            URI uri = new URI(url);
            if (StringUtils.isBlank(uri.getScheme()) || StringUtils.isBlank(uri.getHost())) {
                return i18n("plugin.store.source.configured");
            }
            return new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), null, null, null).toString();
        } catch (URISyntaxException exception) {
            return i18n("plugin.store.source.configured");
        }
    }

    /// Replaces URL-shaped tokens in untrusted descriptive metadata with credential-safe diagnostics.
    ///
    /// @param text registry-provided description or other untrusted prose, possibly `null`
    /// @return text with URL user information, query strings, and fragments removed
    public static String sanitizeMetadata(@Nullable String text) {
        if (StringUtils.isBlank(text)) {
            return "";
        }
        Matcher matcher = URI_TOKEN_PATTERN.matcher(Objects.requireNonNull(text));
        StringBuffer sanitized = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(sanitized, Matcher.quoteReplacement(diagnosticUrl(matcher.group())));
        }
        matcher.appendTail(sanitized);
        return sanitized.toString();
    }

    /// Returns whether a label is ordinary human text rather than a URL or sensitive credential expression.
    ///
    /// @param label candidate local alias or remote registry name
    /// @return whether the label is safe to show verbatim
    public static boolean isSafeHumanLabel(@Nullable String label) {
        if (StringUtils.isBlank(label)) {
            return false;
        }
        String value = Objects.requireNonNull(label).trim();
        if (value.codePointCount(0, value.length()) > 80
                || value.contains("://")
                || value.indexOf('@') >= 0
                || value.indexOf('?') >= 0
                || value.indexOf('#') >= 0
                || value.indexOf('=') >= 0
                || value.indexOf('%') >= 0
                || value.indexOf(':') >= 0
                || value.indexOf('/') >= 0) {
            return false;
        }
        return value.codePoints().allMatch(codePoint -> !Character.isISOControl(codePoint));
    }

    /// Derives a host-only compact label without exposing an arbitrary credential-bearing URL path.
    ///
    /// @param url configured source URL
    /// @return safe compact source host label
    private static String sourceUrlFallback(String url) {
        try {
            @Nullable String host = new URI(url).getHost();
            return StringUtils.isBlank(host) ? i18n("plugin.store.source.configured") : host;
        } catch (URISyntaxException exception) {
            return i18n("plugin.store.source.configured");
        }
    }
}
