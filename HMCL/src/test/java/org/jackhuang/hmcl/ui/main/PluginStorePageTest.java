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

import com.google.gson.JsonParser;
import org.jackhuang.hmcl.plugin.PluginManifest;
import org.jackhuang.hmcl.plugin.PluginPermission;
import org.jackhuang.hmcl.plugin.PluginRuntimeStatus;
import org.jackhuang.hmcl.plugin.runtime.PluginPlatformTarget;
import org.jackhuang.hmcl.plugin.store.PluginInstallPlan;
import org.jackhuang.hmcl.plugin.store.PluginSource;
import org.jackhuang.hmcl.plugin.store.PluginSourceConfiguration;
import org.jackhuang.hmcl.plugin.store.PluginSourceLoadResult;
import org.jackhuang.hmcl.plugin.store.PluginStoreDependencyResolver;
import org.jackhuang.hmcl.plugin.store.PluginStoreItem;
import org.jackhuang.hmcl.plugin.store.PluginStoreManager;
import org.jackhuang.hmcl.plugin.store.PluginStoreManifest;
import org.jackhuang.hmcl.plugin.store.PluginStoreRegistry;
import org.jackhuang.hmcl.plugin.store.PluginStoreSnapshot;
import org.jackhuang.hmcl.plugin.trust.PluginCertificationReceipt;
import org.jackhuang.hmcl.plugin.trust.PluginTrustLevel;
import org.jackhuang.hmcl.plugin.trust.PluginTrustResult;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.util.gson.JsonUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies README sanitization and mandatory fail-closed permission review in the plugin store UI model.
@NotNullByDefault
public final class PluginStorePageTest {
    /// Displays the selected current-platform artifact size for a schema-v5 matrix.
    @Test
    public void selectedVersionSizeUsesCurrentPlatformArtifact() throws IOException {
        String pluginId = "dev.test.current-platform-size";
        PluginStoreManifest manifest = PluginStoreManifest.fromJson(JsonParser.parseString("""
                {
                  "schemaVersion": 2,
                  "id": "%s",
                  "versions": [{
                    "version": "1.0.0",
                    "pluginApiVersion": 5,
                    "permissions": [],
                    "requiredPermissions": [],
                    "launcherVersion": "*",
                    "runtime": "java",
                    "abi": 2,
                    "platforms": [],
                    "pluginKind": "normal",
                    "dependencies": [],
                    "artifacts": [{
                      "platform": "%s",
                      "packageUrl": "https://example.test/current.npl",
                      "sha256": "%s",
                      "size": 73
                    }]
                  }]
                }
                """.formatted(pluginId, PluginPlatformTarget.current().getId(), "a".repeat(64))), pluginId);

        assertEquals(Long.valueOf(73), PluginStorePage.selectedVersionSize(manifest.getVersions().get(0)));
    }

    /// Allows official-reference certification to proceed without a legacy proof receipt.
    @Test
    public void delegatedCertificationDoesNotRequireInstallationReceipt() {
        Map<String, PluginCertificationReceipt> receipts = new LinkedHashMap<>();

        PluginStorePage.collectCertificationReceipt(
                receipts,
                "dev.test.delegated",
                PluginTrustResult.certifiedByOfficialReference()
        );

        assertTrue(receipts.isEmpty());
    }

    /// Changes certification text and installation availability with the exact selected version's trust result.
    @Test
    public void selectedVersionSwitchesCertificationLabelAndInstallState() {
        PluginTrustResult certified = PluginTrustResult.certified("ed25519:artifact", "verification-17");
        PluginTrustResult rejected = PluginTrustResult.rejected("artifact hash mismatch");

        assertEquals(
                "plugin.store.trust.certified",
                PluginStorePage.trustLabelKey(certified.level())
        );
        assertTrue(PluginStorePage.canInstallSelectedVersion(true, certified));
        assertEquals(
                "plugin.store.trust.rejected",
                PluginStorePage.trustLabelKey(rejected.level())
        );
        assertFalse(PluginStorePage.canInstallSelectedVersion(true, rejected));
        assertFalse(PluginStorePage.canInstallSelectedVersion(false, certified));
        assertEquals("plugin.store.trust.official", PluginStorePage.trustLabelKey(PluginTrustLevel.OFFICIAL));
        assertEquals("plugin.store.trust.community", PluginStorePage.trustLabelKey(PluginTrustLevel.COMMUNITY));
    }

    /// Requires every source-management message used by the aggregate plugin store to have an English fallback.
    @Test
    public void everyPluginSourceManagementKeyExists() {
        for (String key : List.of(
                "button.add",
                "button.cancel",
                "button.delete",
                "button.disable",
                "button.edit",
                "button.enable",
                "button.save",
                "plugin.disabled",
                "plugin.enabled",
                "plugin.store.manage_sources",
                "plugin.store.id",
                "plugin.store.sources.summary",
                "plugin.store.sources.none_enabled",
                "plugin.store.sources.none_enabled.description",
                "plugin.store.sources.all_failed",
                "plugin.store.sources.all_failed.description",
                "plugin.store.sources.degraded",
                "plugin.store.sources.conflicts",
                "plugin.store.source",
                "plugin.store.source.official",
                "plugin.store.source.custom",
                "plugin.store.source.third_party",
                "plugin.store.source.label",
                "plugin.store.source.conflict",
                "plugin.store.source.unavailable",
                "plugin.store.source.configured",
                "plugin.store.source.other_candidates",
                "plugin.store.source.plan_entry",
                "plugin.store.source.provenance.official",
                "plugin.store.source.provenance.third_party",
                "plugin.store.source.accessible.row",
                "plugin.store.source.accessible.enable",
                "plugin.store.source.accessible.disable",
                "plugin.store.source.accessible.previous",
                "plugin.store.source.accessible.next",
                "plugin.store.source.accessible.more",
                "plugin.store.source.status.unchecked",
                "plugin.store.source.status.loading",
                "plugin.store.source.status.loaded",
                "plugin.store.source.status.partial_failure",
                "plugin.store.source.status.failed",
                "plugin.store.source.registry_fallback",
                "plugin.store.source.test.failed",
                "plugin.store.source.test.missing_registry",
                "plugin.store.source.details.url",
                "plugin.store.source.details.alias",
                "plugin.store.source.details.registry",
                "plugin.store.source.details.description",
                "plugin.store.source.details.homepage",
                "plugin.store.source.details.type",
                "plugin.store.source.details.enabled",
                "plugin.store.source.details.priority",
                "plugin.store.source.details.status",
                "plugin.store.source.details.failure",
                "plugin.store.source.details.plugins",
                "plugin.store.source.details.duration",
                "plugin.store.source.details.partial_manifest_failures",
                "plugin.store.source.details.conflicts",
                "plugin.store.source.preview.homepage",
                "plugin.store.source.preview.confirm",
                "plugin.store.source.preview.missing_registry",
                "plugin.store.catalog.unavailable",
                "plugin.store.source.delete.confirm",
                "plugin.store.source.delete.installed_confirm",
                "plugin.store.category",
                "plugin.store.category.all",
                "plugin.store.changelog",
                "plugin.store.changelog.empty",
                "plugin.store.channel",
                "plugin.store.compatibility",
                "plugin.store.compatibility.any_launcher",
                "plugin.store.compatibility.current",
                "plugin.store.compatibility.incompatible",
                "plugin.store.compatibility.java",
                "plugin.store.compatibility.launcher",
                "plugin.store.compatibility.plugin_api",
                "plugin.store.compatibility.unsupported_api",
                "plugin.store.dependencies",
                "plugin.store.dependencies.none",
                "plugin.store.details",
                "plugin.store.empty",
                "plugin.store.empty.description",
                "plugin.store.favorite.add",
                "plugin.store.favorite.only",
                "plugin.store.favorite.remove",
                "plugin.store.favorite.show_all",
                "plugin.store.install.conflict",
                "plugin.store.install.download_complete",
                "plugin.store.install.downloading_package",
                "plugin.store.install.incompatible",
                "plugin.store.install.plan_failed",
                "plugin.store.install.publishing_package",
                "plugin.store.install.resolving",
                "plugin.store.install.selected",
                "plugin.store.install_failed",
                "plugin.store.installed",
                "plugin.store.installed_version",
                "plugin.store.installing",
                "plugin.store.launcher_version",
                "plugin.store.license",
                "plugin.store.load_failed",
                "plugin.store.load_failed.detail",
                "plugin.store.loaded",
                "plugin.store.loading",
                "plugin.store.manifest_load_failed",
                "plugin.store.no_result",
                "plugin.store.no_result.description",
                "plugin.store.open_repository",
                "plugin.store.permissions",
                "plugin.store.plan.dependency",
                "plugin.store.plan.install",
                "plugin.store.plan.keep",
                "plugin.store.plan.update",
                "plugin.store.plugins",
                "plugin.store.readme",
                "plugin.store.readme.failed",
                "plugin.store.readme.loading",
                "plugin.store.readme.retry",
                "plugin.store.readme.unavailable",
                "plugin.store.refresh",
                "plugin.store.registry_url",
                "plugin.store.release_date",
                "plugin.store.requires_restart",
                "plugin.store.requires_restart.no",
                "plugin.store.requires_restart.yes",
                "plugin.store.search",
                "plugin.store.settings",
                "plugin.store.size",
                "plugin.store.tags",
                "plugin.store.update",
                "plugin.store.update_available",
                "plugin.store.version",
                "plugin.store.version.incompatible",
                "plugin.store.install.degraded_sources_warning"
        )) {
            assertTrue(org.jackhuang.hmcl.util.i18n.I18n.hasKey(key), key);
        }
    }

    /// Removes every resource-loading element while retaining text and explicit hyperlinks.
    @Test
    public void stripAutomaticReadmeResources() {
        Document document = PluginStorePage.parseSafeReadmeHtml("""
                <h1>Plugin</h1>
                <img src="http://127.0.0.1:8123/probe" alt="probe">
                <picture><source srcset="https://example.com/image.png"></picture>
                <iframe src="https://example.com/frame"></iframe>
                <script src="https://example.com/script.js"></script>
                <a href="docs/usage.html">Usage</a>
                """, "https://example.com/repository/README.html");

        assertTrue(document.select(
                "img, picture, source, video, audio, iframe, object, embed, link, script, style"
        ).isEmpty());
        assertTrue(document.text().contains("Plugin"));
        assertEquals(
                "https://example.com/repository/docs/usage.html",
                document.selectFirst("a").absUrl("href")
        );
    }

    /// Accepts public HTTPS and development loopback HTTP while rejecting unsafe or remote cleartext links.
    @Test
    public void externalLinksRequireHttpsOrLoopbackHttp() {
        assertTrue(PluginStorePage.isSafeExternalLink("https://example.com/plugin"));
        assertTrue(PluginStorePage.isSafeExternalLink("http://localhost:8080/plugin"));
        assertTrue(PluginStorePage.isSafeExternalLink("http://127.0.0.1:8080/plugin"));
        assertFalse(PluginStorePage.isSafeExternalLink("http://127.0.0.1.attacker.example/plugin"));
        assertFalse(PluginStorePage.isSafeExternalLink("http://example.com/plugin"));
        assertFalse(PluginStorePage.isSafeExternalLink("file:///tmp/plugin"));
        assertFalse(PluginStorePage.isSafeExternalLink("javascript:alert(1)"));
        assertFalse(PluginStorePage.isSafeExternalLink("data:text/plain,plugin"));
    }

    /// Normalizes registry prose and bounds descriptions so one row cannot dominate the store list.
    @Test
    public void storeDescriptionSummaryIsBounded() {
        String summary = PluginStorePage.summarizeDescription("A\n\nB " + "x".repeat(220));
        assertTrue(summary.startsWith("A B "));
        assertTrue(summary.length() <= 71);
        assertTrue(summary.endsWith("\u2026"));

        String wideSummary = PluginStorePage.summarizeDescription("插件".repeat(100));
        assertTrue(wideSummary.codePointCount(0, wideSummary.length()) <= 36);
        assertTrue(wideSummary.endsWith("\u2026"));
    }

    /// Bounds long registry names so minimum-width rows retain their status and action controls.
    @Test
    public void storeTitleSummaryIsBounded() {
        String narrow = PluginStorePage.summarizeTitle("Plugin ".repeat(30));
        assertTrue(narrow.endsWith("\u2026"));
        assertTrue(narrow.length() <= 35);

        String wide = PluginStorePage.summarizeTitle("插件".repeat(40));
        assertTrue(wide.endsWith("\u2026"));
        assertTrue(wide.codePointCount(0, wide.length()) <= 18);
    }

    /// Separates descriptive prose from metadata while avoiding blank lines for missing sections.
    @Test
    public void storeRowSubtitleUsesReadableSections() {
        assertEquals(
                "Description\n\nAuthor\nCategory\nVersion",
                PluginStorePage.composePluginRowSubtitle(
                        "Description",
                        List.of("Author", "Category", "Version")
                )
        );
        assertEquals(
                "Author\nVersion",
                PluginStorePage.composePluginRowSubtitle("", List.of("Author", "Version"))
        );
        assertEquals(
                "Description",
                PluginStorePage.composePluginRowSubtitle("Description", List.of())
        );
    }

    /// Uses the same future installed-state definition as dependency planning for root install actions.
    @Test
    public void rootInstallActionUsesFuturePlanningState() {
        PluginManifest installed = new PluginManifest(
                "dev.hmclce.test.installed",
                "Installed",
                "1.0.0",
                PluginManifest.PluginType.JAVA,
                "dev.hmclce.test.Plugin"
        );

        assertEquals(PluginInstallPlan.Action.INSTALL, PluginStorePage.rootInstallationAction(null));
        assertEquals(PluginInstallPlan.Action.UPDATE, PluginStorePage.rootInstallationAction(installed));
    }

    /// Preserves complete HMCL CE version constraints instead of presenting them as minimum versions.
    @Test
    public void launcherVersionRequirementKeepsFullConstraint() {
        assertEquals(
                ">=26.8-beta.3-fix <27.0",
                PluginStorePage.launcherVersionRequirementText(">=26.8-beta.3-fix <27.0")
        );
        assertFalse(PluginStorePage.launcherVersionRequirementText("*").isBlank());
    }

    /// Maps incompatible versions to the launcher error symbol instead of a success checkmark.
    @Test
    public void compatibilityIconMatchesRuntimeState() {
        assertEquals(SVG.CHECK_CIRCLE, PluginStorePage.compatibilityIcon(true));
        assertEquals(SVG.ERROR, PluginStorePage.compatibilityIcon(false));
    }

    /// Displays both executable plugin API generations as compatible when the shared evaluator accepts them.
    @Test
    public void compatibilityTextAcceptsEveryExecutableSchema() throws IOException {
        PluginStoreManager manager = new PluginStoreManager();

        for (int schemaVersion : List.of(4, 5)) {
            String text = PluginStorePage.compatibilityText(
                    manager,
                    compatibleVersion(schemaVersion)
            );

            assertTrue(text.startsWith(org.jackhuang.hmcl.util.i18n.I18n.i18n(
                    "plugin.store.compatibility.current"
            )));
            assertTrue(text.contains(Integer.toString(schemaVersion)));
        }
    }

    /// Treats every backend-supported manifest generation as executable in permission controls.
    @Test
    public void permissionControlsUseExecutableSchemaRange() {
        assertFalse(PluginPermissionManagementPage.isExecutableSchema(3));
        assertTrue(PluginPermissionManagementPage.isExecutableSchema(4));
        assertTrue(PluginPermissionManagementPage.isExecutableSchema(5));
        assertFalse(PluginPermissionManagementPage.isExecutableSchema(6));
    }

    /// Selects every required permission while carrying forward only optional grants retained by an update.
    @Test
    public void permissionPreselectionSeparatesRequiredAndOptionalCapabilities() {
        assertEquals(
                Set.of(PluginPermission.MIXIN, PluginPermission.NETWORK),
                PluginStorePage.initialPermissionsFor(
                        PluginInstallPlan.Action.UPDATE,
                        List.of(PluginPermission.MIXIN),
                        List.of(PluginPermission.NETWORK, PluginPermission.CLIPBOARD),
                        Set.of(PluginPermission.NETWORK, PluginPermission.PROCESS)
                )
        );
        assertEquals(
                Set.of(PluginPermission.FILESYSTEM),
                PluginStorePage.initialPermissionsFor(
                        PluginInstallPlan.Action.INSTALL,
                        List.of(PluginPermission.FILESYSTEM),
                        List.of(PluginPermission.NETWORK),
                        Set.of(PluginPermission.NETWORK)
                )
        );
    }

    /// Creates a new editable review request for updates whose permission declaration is unchanged or empty.
    @Test
    public void everyUpdateCreatesPermissionReviewRequest() {
        PluginPermissionRequest unchangedRequest = PluginStorePage.createPermissionReviewRequest(
                "dev.hmclce.test.unchanged-update",
                "Unchanged Update",
                "1.0.0",
                PluginInstallPlan.Action.UPDATE,
                List.of(),
                List.of(PluginPermission.NETWORK),
                Set.of(PluginPermission.NETWORK),
                List.of(),
                List.of(PluginPermission.NETWORK)
        );
        PluginPermissionRequest emptyRequest = PluginStorePage.createPermissionReviewRequest(
                "dev.hmclce.test.empty-update",
                "Empty Update",
                "1.0.0",
                PluginInstallPlan.Action.UPDATE,
                List.of(),
                List.of(),
                Set.of(PluginPermission.NETWORK),
                List.of(),
                List.of(PluginPermission.NETWORK)
        );
        PluginPermissionRequest expandedRequest = PluginStorePage.createPermissionReviewRequest(
                "dev.hmclce.test.expanded-update",
                "Expanded Update",
                "2.0.0",
                PluginInstallPlan.Action.UPDATE,
                List.of(PluginPermission.NETWORK),
                List.of(PluginPermission.CLIPBOARD),
                Set.of(PluginPermission.NETWORK),
                List.of(),
                List.of(PluginPermission.NETWORK)
        );

        assertTrue(unchangedRequest.isEditable());
        assertTrue(unchangedRequest.isUpdate());
        assertEquals(List.of(PluginPermission.NETWORK), unchangedRequest.getDeclaredPermissions());
        assertEquals(Set.of(PluginPermission.NETWORK), unchangedRequest.getInitiallyGrantedPermissions());
        assertTrue(unchangedRequest.getNewlyRequiredPermissions().isEmpty());
        assertTrue(unchangedRequest.getNewlyOptionalPermissions().isEmpty());
        assertTrue(emptyRequest.isEditable());
        assertTrue(emptyRequest.isUpdate());
        assertTrue(emptyRequest.getDeclaredPermissions().isEmpty());
        assertTrue(emptyRequest.getInitiallyGrantedPermissions().isEmpty());
        assertEquals(Set.of(PluginPermission.NETWORK), expandedRequest.getNewlyRequiredPermissions());
        assertEquals(Set.of(PluginPermission.CLIPBOARD), expandedRequest.getNewlyOptionalPermissions());
        assertEquals(Set.of(PluginPermission.NETWORK), expandedRequest.getInitiallyGrantedPermissions());
    }

    /// Rejects defensive attempts to create a permission review request for an unchanged reused artifact.
    @Test
    public void reusedArtifactCannotCreatePermissionReviewRequest() {
        assertThrows(
                IllegalArgumentException.class,
                () -> PluginStorePage.createPermissionReviewRequest(
                        "dev.hmclce.test.reuse",
                        "Reuse",
                        "1.0.0",
                        PluginInstallPlan.Action.REUSE,
                        List.of(),
                        List.of(PluginPermission.NETWORK),
                        Set.of(PluginPermission.NETWORK),
                        List.of(),
                        List.of(PluginPermission.NETWORK)
                )
        );
    }

    /// Removes undeclared capabilities before an installation form can expose a selected grant.
    @Test
    public void permissionRequestCannotPregrantUndeclaredCapability() {
        PluginPermissionRequest request = new PluginPermissionRequest(
                "dev.hmclce.test.permissions",
                "Permissions",
                "1.0.0",
                List.of(PluginPermission.FILESYSTEM),
                List.of(PluginPermission.NETWORK),
                Set.of(PluginPermission.NETWORK, PluginPermission.PROCESS),
                true,
                false,
                List.of(),
                List.of()
        );

        assertEquals(
                Set.of(PluginPermission.FILESYSTEM, PluginPermission.NETWORK),
                request.getInitiallyGrantedPermissions()
        );
        assertEquals(
                List.of(PluginPermission.FILESYSTEM, PluginPermission.NETWORK),
                request.getDeclaredPermissions()
        );
    }

    /// Rejects ambiguous declarations that classify one capability as both required and optional.
    @Test
    public void permissionRequestRejectsOverlappingClassifications() {
        assertThrows(IllegalArgumentException.class, () -> new PluginPermissionRequest(
                "dev.hmclce.test.overlap",
                "Overlap",
                "1.0.0",
                List.of(PluginPermission.NETWORK),
                List.of(PluginPermission.NETWORK),
                Set.of(),
                true,
                false,
                List.of(),
                List.of()
        ));
    }

    /// Always includes required permissions and discards selections outside the optional declaration.
    @Test
    public void permissionPaneGrantMergeIsRequiredAndDeclarationLimited() {
        assertEquals(
                Set.of(PluginPermission.FILESYSTEM, PluginPermission.NETWORK),
                PluginPermissionPane.mergeGrantedPermissions(
                        List.of(PluginPermission.FILESYSTEM),
                        List.of(PluginPermission.NETWORK),
                        Set.of(PluginPermission.NETWORK, PluginPermission.PROCESS)
                )
        );
    }

    /// Requires every supported permission, including high-risk Mixin access, to have user-facing text.
    @Test
    public void everyPermissionHasLocalizedNameAndDescription() {
        for (PluginPermission permission : PluginPermission.values()) {
            assertTrue(
                    org.jackhuang.hmcl.util.i18n.I18n.hasKey("plugin.permission." + permission.getId()),
                    () -> "Missing permission name for " + permission.getId()
            );
            assertTrue(
                    org.jackhuang.hmcl.util.i18n.I18n.hasKey(
                            "plugin.permission." + permission.getId() + ".description"
                    ),
                    () -> "Missing permission description for " + permission.getId()
            );
        }
        for (String key : List.of(
                "plugin.permission.required",
                "plugin.permission.optional",
                "plugin.permissions.required",
                "plugin.permissions.optional",
                "plugin.permissions.optional_denied_warning",
                "plugin.install.permissions.new_required",
                "plugin.install.permissions.new_optional",
                "plugin.store.launcher_version",
                "plugin.store.install.incompatible"
        )) {
            assertTrue(
                    org.jackhuang.hmcl.util.i18n.I18n.hasKey(key),
                    () -> "Missing plugin UI translation for " + key
            );
        }
    }

    /// Summarizes the persisted enabled-source count without selecting an active registry URL.
    @Test
    public void sourceSummaryReportsEnabledSourceCount() {
        assertEquals(
                org.jackhuang.hmcl.util.i18n.I18n.i18n("plugin.store.sources.summary", 1, 2),
                PluginStorePage.sourceSummary(List.of(
                        new PluginSource(PluginSource.OFFICIAL_ID, PluginStoreManager.DEFAULT_REGISTRY_URL, null, true, true),
                        new PluginSource("source_one", "https://plugins.example.org/plugins.json", null, false, false)
                ))
        );
    }

    /// Publishes the current snapshot before source-management refresh callbacks read it.
    @Test
    public void snapshotPublicationPrecedesSourceManagementRefresh() {
        java.util.concurrent.atomic.AtomicReference<PluginStoreSnapshot> current = new java.util.concurrent.atomic.AtomicReference<>();
        PluginStoreSnapshot snapshot = degradedSnapshot();
        java.util.concurrent.atomic.AtomicLong observedGeneration = new java.util.concurrent.atomic.AtomicLong(-1);

        PluginStorePage.publishSnapshotThenNotify(
                current::set,
                snapshot,
                published -> observedGeneration.set(current.get().getGeneration())
        );

        assertEquals(1, observedGeneration.get());
    }

    /// Presents successful catalog rows while visibly warning that one enabled source failed.
    @Test
    public void partialSourceFailureProducesVisibleDegradedState() {
        PluginStorePage.StorePresentation presentation = PluginStorePage.presentationFor(degradedSnapshot());

        assertEquals(PluginStorePage.StoreState.DEGRADED, presentation.state());
        assertTrue(presentation.showPluginRows());
        assertEquals(1, presentation.failedSourceCount());
    }

    /// Treats a successful empty registry plus a failed source as degraded instead of claiming every source failed.
    @Test
    public void emptySuccessfulRegistryAlongsideFailureProducesDegradedEmptyState() {
        PluginSource successfulSource = new PluginSource(
                "source_a", "https://source-a.example.test/plugins.json", "Source A", true, false);
        PluginSource failedSource = new PluginSource(
                "source_b", "https://source-b.example.test/plugins.json", "Source B", true, false);
        PluginStoreSnapshot snapshot = new PluginStoreSnapshot(1, List.of(
                successfulEmptyResult(successfulSource),
                PluginSourceLoadResult.failed(failedSource, 1, new IOException("offline"))
        ));

        PluginStorePage.StorePresentation presentation = PluginStorePage.presentationFor(snapshot);

        assertEquals(PluginStorePage.StoreState.DEGRADED, presentation.state());
        assertTrue(presentation.showPluginRows());
        assertTrue(Objects.requireNonNull(PluginStorePage.degradedCatalogWarning(snapshot)).contains("Source B"));
    }

    /// Treats a source with unavailable manifests as degraded even when its registry loaded successfully.
    @Test
    public void partialManifestFailureProducesDegradedStateAndWarning() {
        PluginSource source = new PluginSource(
                "source_a", "https://source-a.example.test/plugins.json", "Source A", true, false);
        PluginStoreSnapshot snapshot = new PluginStoreSnapshot(1, List.of(
                partialResult(source, "dev.hmclce.partial", "Partial Plugin")
        ));

        PluginStorePage.StorePresentation presentation = PluginStorePage.presentationFor(snapshot);

        assertEquals(PluginStorePage.StoreState.DEGRADED, presentation.state());
        assertEquals(1, presentation.failedSourceCount());
        assertTrue(Objects.requireNonNull(PluginStorePage.degradedCatalogWarning(snapshot)).contains("Source A"));
    }

    /// Keeps a lower-priority conflict visible while a winning partial source still degrades the catalog.
    @Test
    public void partialManifestFailureWithConflictRemainsDegradedAndReportsConflict() {
        PluginSource partialSource = new PluginSource(
                "source_a", "https://source-a.example.test/plugins.json", "Source A", true, false);
        PluginSource lowerPrioritySource = new PluginSource(
                "source_b", "https://source-b.example.test/plugins.json", "Source B", true, false);
        PluginStoreSnapshot snapshot = new PluginStoreSnapshot(1, List.of(
                partialResult(partialSource, "dev.hmclce.partial.conflict", "Partial Conflict"),
                successfulResult(lowerPrioritySource, "dev.hmclce.partial.conflict", "Lower Candidate")
        ));

        PluginStorePage.StorePresentation presentation = PluginStorePage.presentationFor(snapshot);

        assertEquals(PluginStorePage.StoreState.DEGRADED, presentation.state());
        assertTrue(presentation.hasConflicts());
        assertTrue(Objects.requireNonNull(PluginStorePage.degradedCatalogWarning(snapshot)).contains("Source A"));
    }

    /// Replaces stale loaded counters with terminal aggregate status text.
    @Test
    public void terminalPresentationUsesCurrentStatusInsteadOfStaleLoadedCount() {
        assertEquals(
                org.jackhuang.hmcl.util.i18n.I18n.i18n("plugin.store.sources.none_enabled"),
                PluginStorePage.statusTextFor(
                        PluginStorePage.presentationFor(noEnabledSnapshot()), 4, 4, null
                )
        );
        assertEquals(
                org.jackhuang.hmcl.util.i18n.I18n.i18n("plugin.store.sources.all_failed"),
                PluginStorePage.statusTextFor(
                        PluginStorePage.presentationFor(allFailedSnapshot()), 4, 4, null
                )
        );
    }

    /// Rejects an aggregate snapshot for installation if persisted sources changed after the catalog was loaded.
    @Test
    public void snapshotMustMatchCurrentSourcesBeforeInstallation() {
        PluginSource source = new PluginSource(
                "source_a", "https://source-a.example.test/plugins.json", "Source A", true, false);
        PluginStoreSnapshot snapshot = new PluginStoreSnapshot(1, List.of(successfulResult(
                source, "dev.hmclce.source.fresh", "Fresh Plugin"
        )));

        assertTrue(PluginStorePage.canUseSnapshotForInstallation(
                snapshot,
                new PluginSourceConfiguration(0, List.of(source))
        ));
        assertFalse(PluginStorePage.canUseSnapshotForInstallation(
                snapshot,
                new PluginSourceConfiguration(0, List.of(source.withEnabled(false)))
        ));
        assertFalse(PluginStorePage.canUseSnapshotForInstallation(
                snapshot,
                new PluginSourceConfiguration(0, List.of(source.withConfiguration(
                        source.getUrl(), "Changed Alias"
                )))
        ));
        assertFalse(PluginStorePage.canUseSnapshotForInstallation(
                snapshot,
                new PluginSourceConfiguration(1, List.of(source))
        ));
    }

    /// Keeps an install operation actionable only while its exact source snapshot remains current.
    @Test
    public void installOperationStopsWhenSourcesChangeDuringDownloadOrPublication() {
        PluginSource source = new PluginSource(
                "source_a", "https://source-a.example.test/plugins.json", "Source A", true, false);
        PluginStoreSnapshot snapshot = new PluginStoreSnapshot(1, List.of(successfulResult(
                source, "dev.hmclce.source.fresh", "Fresh Plugin"
        )));

        assertTrue(PluginStorePage.canContinueInstallOperation(
                snapshot,
                new PluginSourceConfiguration(0, List.of(source))
        ));
        assertFalse(PluginStorePage.canContinueInstallOperation(
                snapshot,
                new PluginSourceConfiguration(0, List.of(source.withEnabled(false)))
        ));
        assertFalse(PluginStorePage.canContinueInstallOperation(
                snapshot,
                new PluginSourceConfiguration(0, List.of(source.withConfiguration(
                        source.getUrl(), "Changed Alias"
                )))
        ));
        assertFalse(PluginStorePage.canContinueInstallOperation(
                snapshot,
                new PluginSourceConfiguration(0, List.of())
        ));
        assertFalse(PluginStorePage.canContinueInstallOperation(
                snapshot,
                new PluginSourceConfiguration(1, List.of(source))
        ));
    }

    /// Uses shared source label sanitization in catalog provenance and degradation warnings.
    @Test
    public void catalogProvenanceAndWarningsSanitizeHostileSourceLabels() {
        PluginSource source = new PluginSource(
                "source_a",
                "https://user:secret@plugins.example.test/catalog.json?token=secret#fragment",
                "https://user:secret@host/catalog?token=secret#fragment",
                true,
                false
        );
        PluginStoreItem item = successfulItem(source, "dev.hmclce.source.safe", "Safe Plugin");
        PluginStoreSnapshot failed = new PluginStoreSnapshot(1, List.of(
                PluginSourceLoadResult.failed(source, 1, new IOException("offline"))
        ));
        String provenance = PluginStorePage.buildPluginRowSubtitle(item, null);
        String warning = Objects.requireNonNull(PluginStorePage.degradedCatalogWarning(failed));

        assertTrue(provenance.contains("plugins.example.test"));
        assertTrue(warning.contains("plugins.example.test"));
        for (String text : List.of(provenance, warning)) {
            assertFalse(text.contains("secret"));
            assertFalse(text.contains("token"));
            assertFalse(text.contains("#fragment"));
            assertFalse(text.contains(source.getUrl()));
        }
    }

    /// Identifies every remote plan winner by immutable trust status and configured host rather than its alias.
    @Test
    public void everyDownloadedPlanRowIdentifiesItsWinningSource() throws IOException {
        List<String> rows = PluginStorePage.formatInstallPlan(planWithTwoRemoteSources());

        List<String> sourceRows = rows.stream()
                .filter(row -> row.contains("source-a.example.test") || row.contains("source-b.example.test"))
                .toList();
        assertEquals(2, sourceRows.size());
        assertTrue(sourceRows.stream().allMatch(row -> row.contains(
                org.jackhuang.hmcl.util.i18n.I18n.i18n("plugin.store.source.third_party")
        )));
        assertTrue(rows.stream().noneMatch(row -> row.contains("Source A") || row.contains("Source B")));
    }

    /// Prevents a custom source alias or registry name from impersonating official final-install provenance.
    @Test
    public void customSourceCannotImpersonateOfficialInstallPlanProvenance() throws IOException {
        PluginSource source = new PluginSource(
                "impersonator",
                "https://user:secret@third-party.example.test/catalog/registry.json?token=secret#fragment",
                "HMCL CE Plugin Store",
                true,
                false
        );
        PluginStoreItem item = itemWithManifest(
                source, "dev.hmclce.impersonator", "Impersonator", "[]", "a"
        );
        PluginStoreManifest.PluginVersionEntry version = Objects.requireNonNull(
                Objects.requireNonNull(item.getManifest()).getVersion("1.0.0")
        );
        PluginInstallPlan plan = new PluginStoreDependencyResolver(
                Map.of(item.getEntry().getId(), item),
                List.of(source)
        ).resolveInstallPlan(item.getEntry().getId(), version, Map.of(), Map.of(), Map.of());

        PluginInstallPlan.Entry entry = plan.getRootEntry();
        String finalConfirmationRow = PluginStorePage.formatInstallPlan(plan).get(0);

        assertEquals("HMCL CE Plugin Store", entry.getSourceDisplayName());
        assertFalse(entry.requireSourceProvenance().isOfficial());
        assertEquals("third-party.example.test", entry.requireSourceProvenance().getHostIdentity());
        assertTrue(finalConfirmationRow.contains(
                org.jackhuang.hmcl.util.i18n.I18n.i18n("plugin.store.source.third_party")
        ));
        assertTrue(finalConfirmationRow.contains("third-party.example.test"));
        assertFalse(finalConfirmationRow.contains("HMCL CE Plugin Store"));
        assertFalse(finalConfirmationRow.contains("user"));
        assertFalse(finalConfirmationRow.contains("secret"));
        assertFalse(finalConfirmationRow.contains("/catalog"));
        assertFalse(finalConfirmationRow.contains("token"));
        assertFalse(finalConfirmationRow.contains("#fragment"));
    }

    /// Retains the accepted aggregate state while a newer refresh is still active.
    @Test
    public void refreshingPresentationRetainsPriorAcceptedPluginRows() {
        assertTrue(PluginStorePage.presentationFor(degradedSnapshot(), true).showPluginRows());
    }

    /// Rejects a superseded completion even when its aggregate result was once current.
    @Test
    public void staleCompletionCannotReplaceNewerAcceptedSnapshot() {
        PluginStoreSnapshot stale = degradedSnapshot();
        PluginStoreSnapshot current = conflictingSnapshot();

        PluginSourceConfiguration staleConfiguration = stale.getSourceConfiguration();
        PluginSourceConfiguration currentConfiguration = current.getSourceConfiguration();

        assertFalse(PluginStorePage.canPublishSnapshot(1, 2, stale, stale, staleConfiguration));
        assertFalse(PluginStorePage.canPublishSnapshot(2, 2, stale, current, staleConfiguration));
        assertTrue(PluginStorePage.canPublishSnapshot(2, 2, current, current, currentConfiguration));
    }

    /// Rejects a current-generation completion after source values pass through an ABA cycle.
    @Test
    public void sourceRevisionChangeRejectsOtherwiseCurrentSnapshotPublication() {
        PluginSource source = new PluginSource(
                "source_a", "https://source-a.example.test/plugins.json", "Source A", true, false);
        PluginStoreSnapshot completed = new PluginStoreSnapshot(1, 4, List.of(successfulResult(
                source, "dev.hmclce.source.stale", "Stale Plugin"
        )));

        assertFalse(PluginStorePage.canPublishSnapshot(
                1,
                1,
                completed,
                completed,
                new PluginSourceConfiguration(6, List.of(source))
        ));
    }

    /// Closes a page-owned aggregate loader exactly once at its deterministic application lifetime boundary.
    @Test
    public void aggregateLoaderClosesOnceAtApplicationShutdown() {
        AtomicBoolean closed = new AtomicBoolean();
        AtomicInteger closeCount = new AtomicInteger();

        PluginStorePage.closeAggregatorWhenApplicationStops(false, closed, closeCount::incrementAndGet);
        PluginStorePage.closeAggregatorWhenApplicationStops(false, closed, closeCount::incrementAndGet);
        assertEquals(0, closeCount.get());

        PluginStorePage.closeAggregatorWhenApplicationStops(true, closed, closeCount::incrementAndGet);
        PluginStorePage.closeAggregatorWhenApplicationStops(true, closed, closeCount::incrementAndGet);

        assertEquals(1, closeCount.get());
    }

    /// Retains the degradation warning for the snapshot that resolved a plan when a later refresh is healthy.
    @Test
    public void planWarningUsesResolvedSnapshotAfterLaterRefresh() {
        PluginStoreSnapshot resolvedSnapshot = degradedSnapshot();
        PluginStoreSnapshot laterSnapshot = conflictingSnapshot();

        assertTrue(Objects.requireNonNull(PluginStorePage.degradedCatalogWarning(resolvedSnapshot)).contains("Source B"));
        assertNull(PluginStorePage.degradedCatalogWarning(laterSnapshot));
    }

    /// Uses ordinary Unicode aliases in warnings while replacing URL-shaped or credential-shaped labels.
    @Test
    public void degradedCatalogWarningUsesOnlyStrictPlainSourceNames() {
        for (String hostileLabel : List.of(
                "https://id.example.test/catalog?token=secret#fragment",
                "Source%20Name",
                "token=secret",
                "password=secret",
                "user%40source.example.test",
                "source:name",
                "source/name",
                "Source\nName",
                "x".repeat(81)
        )) {
            PluginSource hostile = new PluginSource(
                    hostileLabel,
                    "https://source.example.test/plugins.json",
                    hostileLabel,
                    true,
                    false
            );
            PluginStoreSnapshot snapshot = new PluginStoreSnapshot(1, List.of(
                    PluginSourceLoadResult.failed(hostile, 1, new IOException("offline"))
            ));

            String warning = Objects.requireNonNull(PluginStorePage.degradedCatalogWarning(snapshot));
            assertTrue(warning.contains("source.example.test"));
            assertFalse(warning.contains("secret"));
            assertFalse(warning.contains("token"));
            assertFalse(warning.contains("#fragment"));
        }

        PluginSource ordinary = new PluginSource(
                "source_plain", "https://source.example.test/plugins.json", "社区 插件", true, false);
        PluginStoreSnapshot ordinarySnapshot = new PluginStoreSnapshot(1, List.of(
                PluginSourceLoadResult.failed(ordinary, 1, new IOException("offline"))
        ));
        assertTrue(Objects.requireNonNull(PluginStorePage.degradedCatalogWarning(ordinarySnapshot)).contains("社区 插件"));
    }

    /// Returns no plugin rows when every configured enabled source fails.
    @Test
    public void allFailedSourcesProduceAnAllFailedPresentation() {
        PluginSource source = new PluginSource(
                "source_a", "https://source-a.example.test/plugins.json", "Source A", true, false);
        PluginStorePage.StorePresentation presentation = PluginStorePage.presentationFor(new PluginStoreSnapshot(1, List.of(
                PluginSourceLoadResult.failed(source, 1, new IOException("offline"))
        )));

        assertEquals(PluginStorePage.StoreState.ALL_FAILED, presentation.state());
        assertFalse(presentation.showPluginRows());
    }

    /// Provides priority-ordered credential-safe diagnostics and direct retry eligibility when every enabled source fails.
    @Test
    public void allFailedDiagnosticsAreSafeAndRetryableOnlyWhenIdle() {
        PluginSource hostile = new PluginSource(
                "source_a",
                "https://source-a.example.test/plugins.json",
                "https://user:secret@host.example/catalog?token=private#fragment",
                true,
                false
        );
        PluginSource ordinary = new PluginSource(
                "source_b", "https://source-b.example.test/plugins.json", "Source B", true, false);
        PluginStoreSnapshot snapshot = new PluginStoreSnapshot(1, List.of(
                PluginSourceLoadResult.failed(hostile, 1,
                        new IOException("Unable to load https://user:secret@host.example/catalog?token=private#fragment")),
                PluginSourceLoadResult.failed(ordinary, 1, new IOException("offline"))
        ));
        PluginStorePage.StorePresentation presentation = PluginStorePage.presentationFor(snapshot);

        List<PluginStorePage.SourceFailureDiagnostic> diagnostics = PluginStorePage.allFailedDiagnostics(snapshot);
        assertEquals(2, diagnostics.size());
        assertEquals("source-a.example.test", diagnostics.get(0).sourceName());
        assertEquals("Source B", diagnostics.get(1).sourceName());
        assertFalse(diagnostics.get(0).message().contains("secret"));
        assertFalse(diagnostics.get(0).message().contains("token"));
        assertFalse(diagnostics.get(0).message().contains("?"));
        assertFalse(diagnostics.get(0).message().contains("#"));
        assertTrue(PluginStorePage.canRetryFailedSources(presentation, false));
        assertFalse(PluginStorePage.canRetryFailedSources(presentation, true));
        assertFalse(PluginStorePage.canRetryFailedSources(
                PluginStorePage.presentationFor(noEnabledSnapshot()), false));
    }

    /// Returns the source-management state when no configured source is enabled.
    @Test
    public void disabledSourcesProduceNoEnabledSourcesPresentation() {
        PluginSource source = new PluginSource(
                "source_a", "https://source-a.example.test/plugins.json", "Source A", false, false);
        PluginStorePage.StorePresentation presentation = PluginStorePage.presentationFor(new PluginStoreSnapshot(1, List.of(
                PluginSourceLoadResult.disabled(source)
        )));

        assertEquals(PluginStorePage.StoreState.NO_ENABLED_SOURCES, presentation.state());
        assertFalse(presentation.showPluginRows());
    }

    /// Marks aggregate winners with source conflicts for a dedicated warning and details presentation.
    @Test
    public void conflictingCandidatesProduceConflictPresentation() {
        PluginStorePage.StorePresentation presentation = PluginStorePage.presentationFor(conflictingSnapshot());

        assertEquals(PluginStorePage.StoreState.CONFLICTS, presentation.state());
        assertTrue(presentation.hasConflicts());
    }

    /// Adds the winning source to aggregate row metadata rather than selecting a global registry name.
    @Test
    public void rowSubtitleContainsWinningSourceBadge() {
        PluginStoreItem item = successfulItem(
                new PluginSource("source_a", "https://source-a.example.test/plugins.json", "Source A", true, false),
                "dev.hmclce.source.badge",
                "Badge Plugin"
        );

        assertTrue(PluginStorePage.buildPluginRowSubtitle(item, null).contains(
                org.jackhuang.hmcl.util.i18n.I18n.i18n("plugin.store.source.label", "Source A")
        ));
    }

    /// Keeps favorites attached to plugin identity regardless of the source that currently wins that identity.
    @Test
    public void favoriteFilteringKeysOnlyByPluginId() {
        assertTrue(PluginStorePage.matchesFavorite(Set.of("dev.hmclce.favorite"),
                successfulItem(
                        new PluginSource("source_b", "https://source-b.example.test/plugins.json", "Source B", true, false),
                        "dev.hmclce.favorite",
                        "Favorite"
                )));
    }

    /// Retains installed state after a source deletion removes the catalog winner.
    @Test
    public void installedStateSurvivesSourceRemoval() {
        PluginManifest installed = new PluginManifest(
                "dev.hmclce.removed", "Removed Source Plugin", "1.0.0",
                PluginManifest.PluginType.JAVA, "dev.hmclce.test.Plugin"
        );

        assertEquals(PluginInstallPlan.Action.UPDATE, PluginStorePage.rootInstallationAction(installed));
        assertFalse(PluginStorePage.presentationFor(new PluginStoreSnapshot(2, List.of())).showPluginRows());
    }

    /// Creates an aggregate snapshot containing no enabled source.
    ///
    /// @return no-enabled aggregate snapshot
    private static PluginStoreSnapshot noEnabledSnapshot() {
        PluginSource source = new PluginSource(
                "source_a", "https://source-a.example.test/plugins.json", "Source A", false, false);
        return new PluginStoreSnapshot(1, List.of(PluginSourceLoadResult.disabled(source)));
    }

    /// Creates an aggregate snapshot containing only registry-level source failures.
    ///
    /// @return all-failed aggregate snapshot
    private static PluginStoreSnapshot allFailedSnapshot() {
        PluginSource source = new PluginSource(
                "source_a", "https://source-a.example.test/plugins.json", "Source A", true, false);
        return new PluginStoreSnapshot(1, List.of(PluginSourceLoadResult.failed(source, 1, new IOException("offline"))));
    }

    /// Creates an aggregate snapshot containing one successful winner and one unavailable enabled source.
    ///
    /// @return degraded aggregate snapshot
    private static PluginStoreSnapshot degradedSnapshot() {
        PluginSource sourceA = new PluginSource(
                "source_a", "https://source-a.example.test/plugins.json", "Source A", true, false);
        PluginSource sourceB = new PluginSource(
                "source_b", "https://source-b.example.test/plugins.json", "Source B", true, false);
        return new PluginStoreSnapshot(1, List.of(
                successfulResult(sourceA, "dev.hmclce.aggregate", "Aggregate Plugin"),
                PluginSourceLoadResult.failed(sourceB, 1, new IOException("offline"))
        ));
    }

    /// Creates an aggregate snapshot with the same plugin ID published by two priority-ordered sources.
    ///
    /// @return conflict-bearing aggregate snapshot
    private static PluginStoreSnapshot conflictingSnapshot() {
        PluginSource sourceA = new PluginSource(
                "source_a", "https://source-a.example.test/plugins.json", "Source A", true, false);
        PluginSource sourceB = new PluginSource(
                "source_b", "https://source-b.example.test/plugins.json", "Source B", true, false);
        return new PluginStoreSnapshot(1, List.of(
                successfulResult(sourceA, "dev.hmclce.conflict", "Source A Plugin"),
                successfulResult(sourceB, "dev.hmclce.conflict", "Source B Plugin")
        ));
    }

    /// Resolves an aggregate plan whose root and dependency intentionally come from different winners.
    ///
    /// @return source-aware dependency plan
    private static PluginInstallPlan planWithTwoRemoteSources() throws IOException {
        PluginSource sourceA = new PluginSource(
                "source_a", "https://source-a.example.test/plugins.json", "Source A", true, false);
        PluginSource sourceB = new PluginSource(
                "source_b", "https://source-b.example.test/plugins.json", "Source B", true, false);
        PluginStoreItem root = itemWithManifest(
                sourceA,
                "dev.hmclce.root",
                "Root",
                "[{\"id\":\"dev.hmclce.dependency\",\"version\":\"*\"}]",
                "1"
        );
        PluginStoreItem dependency = itemWithManifest(
                sourceB, "dev.hmclce.dependency", "Dependency", "[]", "2"
        );
        PluginStoreManifest.PluginVersionEntry rootVersion = Objects.requireNonNull(
                Objects.requireNonNull(root.getManifest()).getVersion("1.0.0")
        );
        return new PluginStoreDependencyResolver(
                Map.of(
                        root.getEntry().getId(), root,
                        dependency.getEntry().getId(), dependency
                ),
                List.of(sourceA, sourceB)
        ).resolveInstallPlan(root.getEntry().getId(), rootVersion, Map.of(), Map.of(), Map.of());
    }

    /// Creates a successful source outcome with an empty registry.
    ///
    /// @param source source owning the empty registry
    /// @return successful empty source outcome
    private static PluginSourceLoadResult successfulEmptyResult(PluginSource source) {
        PluginStoreRegistry registry = Objects.requireNonNull(JsonUtils.GSON.fromJson("""
                {
                  "schemaVersion": 1,
                  "name": "%s",
                  "plugins": []
                }
                """.formatted(source.getAlias()), PluginStoreRegistry.class));
        return PluginSourceLoadResult.success(source, 1, List.of(), 0, registry, new PluginStoreManager());
    }

    /// Creates a successful source outcome with one unavailable repository manifest.
    ///
    /// @param source source owning the partial result
    /// @param pluginId stable plugin ID
    /// @param name display name
    /// @return partial source outcome
    private static PluginSourceLoadResult partialResult(PluginSource source, String pluginId, String name) {
        PluginStoreItem item = successfulItem(source, pluginId, name);
        return PluginSourceLoadResult.success(source, 1, List.of(item), 1, item.getRegistry(), item.getSourceManager());
    }

    /// Creates one successful source result for a registry item without a repository manifest.
    ///
    /// @param source source owning the item
    /// @param pluginId stable plugin ID
    /// @param name display name
    /// @return successful source result
    private static PluginSourceLoadResult successfulResult(PluginSource source, String pluginId, String name) {
        try {
            PluginStoreItem item = itemWithManifest(source, pluginId, name, "[]", "a");
            return PluginSourceLoadResult.success(
                    source, 1, List.of(item), 0, item.getRegistry(), item.getSourceManager());
        } catch (IOException exception) {
            throw new IllegalStateException("Generated source fixture was invalid", exception);
        }
    }

    /// Creates one source-bound registry item without a repository manifest.
    ///
    /// @param source source owning the item
    /// @param pluginId stable plugin ID
    /// @param name display name
    /// @return source-bound catalog item
    private static PluginStoreItem successfulItem(PluginSource source, String pluginId, String name) {
        PluginStoreRegistry registry = Objects.requireNonNull(JsonUtils.GSON.fromJson("""
                {
                  "schemaVersion": 1,
                  "name": "%s",
                  "plugins": [{
                    "id": "%s",
                    "name": "%s",
                    "manifestUrl": "https://plugins.example.test/%s.json"
                  }]
                }
                """.formatted(source.getAlias(), pluginId, name, pluginId), PluginStoreRegistry.class));
        return new PluginStoreItem(source, registry, new PluginStoreManager(), registry.getPlugins().get(0), null);
    }

    /// Creates one source-bound catalog item with a complete repository manifest.
    ///
    /// @param source source owning the item
    /// @param pluginId stable plugin ID
    /// @param name display name
    /// @param dependenciesJson dependency JSON array
    /// @param hashDigit hexadecimal checksum digit
    /// @return source-bound catalog item
    /// @throws IOException if the generated manifest is invalid
    private static PluginStoreItem itemWithManifest(
            PluginSource source,
            String pluginId,
            String name,
            String dependenciesJson,
            String hashDigit
    ) throws IOException {
        PluginStoreItem item = successfulItem(source, pluginId, name);
        PluginStoreManifest manifest = Objects.requireNonNull(JsonUtils.GSON.fromJson("""
                {
                  "schemaVersion": 2,
                  "id": "%s",
                  "versions": [{
                    "version": "1.0.0",
                    "packageUrl": "https://plugins.example.test/%s.npl",
                    "sha256": "%s",
                    "pluginApiVersion": 4,
                    "permissions": [],
                    "requiredPermissions": [],
                    "launcherVersion": "*",
                    "dependencies": %s,
                    "size": 1
                  }]
                }
                """.formatted(pluginId, pluginId, hashDigit.repeat(64), dependenciesJson), PluginStoreManifest.class));
        manifest.validate(pluginId);
        return new PluginStoreItem(source, item.getRegistry(), item.getSourceManager(), item.getEntry(), manifest);
    }

    /// Creates one Store version accepted by the shared compatibility evaluator for the requested API generation.
    ///
    /// @param schemaVersion executable plugin API generation
    /// @return validated compatible version entry
    /// @throws IOException if the generated Store manifest is invalid
    private static PluginStoreManifest.PluginVersionEntry compatibleVersion(int schemaVersion) throws IOException {
        String pluginId = "dev.hmclce.test.ui-compatibility-" + schemaVersion;
        String schemaFiveFields = schemaVersion >= 5
                ? """
                  ,"runtime": "java",
                  "abi": 2,
                  "platforms": []
                  """
                : "";
        PluginStoreManifest manifest = PluginStoreManifest.fromJson(JsonParser.parseString("""
                {
                  "schemaVersion": 2,
                  "id": "%1$s",
                  "versions": [{
                    "version": "1.0.0",
                    "packageUrl": "https://plugins.example.test/ui-compatibility-%2$d.npl",
                    "sha256": "%3$s",
                    "pluginApiVersion": %2$d,
                    "permissions": [],
                    "requiredPermissions": [],
                    "launcherVersion": "*",
                    "dependencies": [],
                    "size": 1%4$s
                  }]
                }
                """.formatted(
                pluginId,
                schemaVersion,
                Integer.toHexString(schemaVersion).repeat(64),
                schemaFiveFields
        )), pluginId);
        return Objects.requireNonNull(manifest.getVersion("1.0.0"));
    }

    /// Requires every runtime state used by plugin rows and permission details to have a localized label.
    @Test
    public void everyRuntimeStatusHasLocalizedLabel() {
        for (PluginRuntimeStatus status : PluginRuntimeStatus.values()) {
            assertFalse(PluginPermissionManagementPage.runtimeStatusLabel(status).isBlank());
        }
        assertFalse(org.jackhuang.hmcl.util.i18n.I18n.hasKey("plugin.permissions.scope_hint"));
    }
}
