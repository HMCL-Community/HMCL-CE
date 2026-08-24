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
package org.jackhuang.hmcl.ui.main;

import org.jackhuang.hmcl.plugin.runtime.PluginPlatformTarget;
import org.jackhuang.hmcl.plugin.store.PluginInstallPlan;
import org.jackhuang.hmcl.plugin.store.PluginSource;
import org.jackhuang.hmcl.plugin.store.PluginStoreDependencyResolver;
import org.jackhuang.hmcl.plugin.store.PluginStoreItem;
import org.jackhuang.hmcl.plugin.store.PluginStoreManager;
import org.jackhuang.hmcl.plugin.store.PluginStoreManifest;
import org.jackhuang.hmcl.plugin.store.PluginStoreRegistry;
import org.jackhuang.hmcl.util.gson.JsonUtils;
import org.jackhuang.hmcl.util.i18n.I18n;
import org.jackhuang.hmcl.util.i18n.SupportedLocale;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies the language-neutral text model shown before a Runtime Provider Store transaction is confirmed.
@NotNullByDefault
public final class PluginInstallPlanPresentationTest {
    /// Requires every independently applicable install acknowledgement before confirmation.
    @Test
    public void customSourceAndDangerousPermissionAcknowledgementsAreIndependent() {
        assertTrue(PluginDialogs.canConfirmPluginInstall(false, false, false, false));
        assertTrue(PluginDialogs.canConfirmPluginInstall(true, true, false, false));
        assertTrue(PluginDialogs.canConfirmPluginInstall(false, false, true, true));
        assertTrue(PluginDialogs.canConfirmPluginInstall(true, true, true, true));
        assertFalse(PluginDialogs.canConfirmPluginInstall(true, false, true, true));
        assertFalse(PluginDialogs.canConfirmPluginInstall(true, true, true, false));
    }

    /// Shows Host identity, provenance, artifact, execution mode, binding reason, and exact byte totals.
    @Test
    public void showsCompleteRuntimeProviderInstallPlan() throws Exception {
        SupportedLocale originalLocale = I18n.getLocale();
        I18n.setLocale(SupportedLocale.getLocale(Locale.ENGLISH));
        try {
            PluginInstallPlan plan = runtimePlan();

            String presentation = String.join("\n", PluginStorePage.formatInstallPlan(plan));

            assertTrue(presentation.contains("Runtime Host"));
            assertTrue(presentation.contains("official.example.test"));
            assertTrue(presentation.contains(PluginPlatformTarget.current().getId()));
            assertTrue(presentation.contains("41"));
            assertTrue(presentation.contains("17"));
            assertTrue(presentation.contains("58"));
            assertTrue(presentation.contains("embedded"));
            assertTrue(presentation.contains("dev.test.rust-tool"));
            assertTrue(presentation.contains("dev.test.rust-host"));
            assertTrue(presentation.contains("rust"));
        } finally {
            I18n.setLocale(originalLocale);
        }
    }

    /// Builds one official Rust consumer and Host plan for presentation assertions.
    ///
    /// @return resolved dependency-first plan
    /// @throws IOException if generated Store metadata is invalid
    private static PluginInstallPlan runtimePlan() throws IOException {
        String target = PluginPlatformTarget.current().getId();
        PluginStoreRegistry registry = Objects.requireNonNull(JsonUtils.GSON.fromJson("""
                {"schemaVersion":1,"name":"Runtime Presentation","plugins":[
                  {"id":"dev.test.rust-tool","name":"Rust Tool",
                   "manifestUrl":"https://official.example.test/rust-tool.json"},
                  {"id":"dev.test.rust-host","name":"Rust Host",
                   "manifestUrl":"https://official.example.test/rust-host.json"}
                ]}
                """, PluginStoreRegistry.class));
        registry.validate();
        PluginStoreManifest rootManifest = storeManifest("dev.test.rust-tool", """
                "runtime":"rust","abi":2,"pluginKind":"normal","executionMode":"embedded",
                "artifacts":[{"platform":"%s","packageUrl":"https://official.example.test/tool.npl",
                  "sha256":"%s","size":17}]
                """.formatted(target, "a".repeat(64)));
        PluginStoreManifest hostManifest = storeManifest("dev.test.rust-host", """
                "runtime":"java","abi":2,"pluginKind":"runtime-provider",
                "providesRuntimes":[{"runtime":"rust","abis":[2],"bridgeAbi":1,
                  "executionModes":["embedded"],"features":["bridge"]}],
                "artifacts":[{"platform":"%s","packageUrl":"https://official.example.test/host.npl",
                  "sha256":"%s","size":41}]
                """.formatted(target, "b".repeat(64)));
        PluginSource source = new PluginSource(
                "official", "https://official.example.test/plugins.json", "Official", true, true);
        PluginStoreManager manager = new PluginStoreManager();
        Map<String, PluginStoreItem> catalog = new LinkedHashMap<>();
        catalog.put("dev.test.rust-tool", new PluginStoreItem(
                source, registry, manager, registry.getPlugins().get(0), rootManifest));
        catalog.put("dev.test.rust-host", new PluginStoreItem(
                source, registry, manager, registry.getPlugins().get(1), hostManifest));
        return new PluginStoreDependencyResolver(catalog, List.of(source)).resolveInstallPlan(
                "dev.test.rust-tool", rootManifest.getVersions().get(0), Map.of(), Map.of(), Map.of());
    }

    /// Parses one schema-v5 Store manifest with a single version.
    ///
    /// @param pluginId repository plugin ID
    /// @param declarations runtime declarations and artifacts
    /// @return validated Store manifest
    /// @throws IOException if generated metadata is invalid
    private static PluginStoreManifest storeManifest(String pluginId, String declarations) throws IOException {
        return PluginStoreManifest.fromJson(JsonUtils.GSON.fromJson("""
                {"schemaVersion":2,"id":"%s","versions":[{
                  "version":"1.0.0","pluginApiVersion":5,"permissions":[],"requiredPermissions":[],
                  "launcherVersion":"*","platforms":["%s"],"dependencies":[],%s
                }]}
                """.formatted(pluginId, PluginPlatformTarget.current().getId(), declarations),
                com.google.gson.JsonElement.class), pluginId);
    }
}
