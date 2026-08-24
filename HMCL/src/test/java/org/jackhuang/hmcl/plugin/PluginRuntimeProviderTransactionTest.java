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

import org.jackhuang.hmcl.plugin.internal.PluginPackageVersions;
import org.jackhuang.hmcl.plugin.runtime.RuntimeProviderBinding;
import org.jackhuang.hmcl.plugin.runtime.PluginExecutionMode;
import org.jackhuang.hmcl.plugin.runtime.RuntimeFeature;
import org.jackhuang.hmcl.plugin.runtime.RuntimeProviderDeclaration;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies atomic Runtime Provider binding publication and reverse-dependency protection.
@EnabledIf("org.jackhuang.hmcl.JavaFXLauncher#isStarted")
@NotNullByDefault
public final class PluginRuntimeProviderTransactionTest {
    /// Removes a dependent's runtime binding in the same transaction as immediate package uninstall.
    ///
    /// @param temporaryDirectory isolated launcher-local home
    /// @throws Exception if fixture publication or uninstall fails unexpectedly
    @Test
    public void immediateDependentUninstallRemovesRuntimeBinding(@TempDir Path temporaryDirectory) throws Exception {
        Path localHome = temporaryDirectory.resolve("home");
        PluginManager manager = new PluginManager(localHome);
        String providerId = "dev.test.uninstall-provider";
        String dependentId = "dev.test.uninstall-dependent";
        writeApiFourPackage(manager.getPluginsDirectory().resolve(providerId + ".npl"), providerId, "1.0.0");
        writeApiFourPackage(manager.getPluginsDirectory().resolve(dependentId + ".npl"), dependentId, "1.0.0");
        writeBindings(localHome, providerId, dependentId);

        manager.uninstallPlugin(dependentId);

        assertFalse(new PluginRuntimeBindingStore(localHome, new PluginMutationLock(localHome))
                .readStrict().containsKey(dependentId));
    }

    /// Removes a dependent's runtime binding when uninstall is staged for the next restart.
    ///
    /// @param temporaryDirectory isolated launcher-local home
    /// @throws Exception if fixture publication or pending uninstall persistence fails unexpectedly
    @Test
    public void pendingDependentUninstallRemovesRuntimeBinding(@TempDir Path temporaryDirectory) throws Exception {
        Path localHome = temporaryDirectory.resolve("home");
        PluginManager manager = new PluginManager(localHome);
        String providerId = "dev.test.pending-provider";
        String dependentId = "dev.test.pending-dependent";
        writeApiFourPackage(manager.getPluginsDirectory().resolve(providerId + ".npl"), providerId, "1.0.0");
        writeApiFourPackage(manager.getPluginsDirectory().resolve(dependentId + ".npl"), dependentId, "1.0.0");
        writeBindings(localHome, providerId, dependentId);

        manager.markForUninstall(dependentId);

        assertFalse(new PluginRuntimeBindingStore(localHome, new PluginMutationLock(localHome))
                .readStrict().containsKey(dependentId));
    }

    /// Removes a stale external-runtime binding when the dependent is replaced by a Java package.
    ///
    /// @param temporaryDirectory isolated launcher-local home and replacement source
    /// @throws Exception if fixture publication or replacement fails unexpectedly
    @Test
    public void replacingDependentWithJavaRemovesOldBindingBeforeGraphValidation(
            @TempDir Path temporaryDirectory
    ) throws Exception {
        Path localHome = temporaryDirectory.resolve("home");
        PluginManager manager = new PluginManager(localHome);
        String providerId = "dev.test.java-update-provider";
        String dependentId = "dev.test.java-update-dependent";
        writeRuntimeProviderPackage(manager.getPluginsDirectory().resolve(providerId + ".npl"), providerId);
        writeRuntimeConsumerPackage(manager.getPluginsDirectory().resolve(dependentId + ".npl"), dependentId);
        writeBindings(localHome, providerId, dependentId);
        Path replacement = temporaryDirectory.resolve("java-update.npl");
        writeApiFourPackage(replacement, dependentId, "2.0.0");

        manager.prepareLocalPluginInstallation(replacement, Set.of());

        assertFalse(new PluginRuntimeBindingStore(localHome, new PluginMutationLock(localHome))
                .readStrict().containsKey(dependentId));
        assertEquals("2.0.0", readManifest(manager.getPluginsDirectory().resolve(dependentId + ".npl")).getVersion());
    }

    /// Replaces an old ABI/provider binding before validating the prospective replacement graph.
    ///
    /// @param temporaryDirectory isolated launcher-local home and replacement source
    /// @throws Exception if fixture publication or replacement fails unexpectedly
    @Test
    public void runtimeUpdateReplacesOldBindingBeforeProspectiveValidation(
            @TempDir Path temporaryDirectory
    ) throws Exception {
        Path localHome = temporaryDirectory.resolve("home");
        PluginManager manager = new PluginManager(localHome);
        String oldProviderId = "dev.test.old-abi-provider";
        String newProviderId = "dev.test.new-abi-provider";
        String dependentId = "dev.test.abi-dependent";
        Path oldProvider = manager.getPluginsDirectory().resolve(oldProviderId + ".npl");
        Path newProvider = manager.getPluginsDirectory().resolve(newProviderId + ".npl");
        Path installedDependent = manager.getPluginsDirectory().resolve(dependentId + ".npl");
        writeRuntimeProviderPackage(oldProvider, oldProviderId, 1);
        writeRuntimeProviderPackage(newProvider, newProviderId, 2);
        writeRuntimeConsumerPackage(installedDependent, dependentId, 1);
        writeBindings(localHome, oldProviderId, dependentId);
        Path replacement = temporaryDirectory.resolve("abi-dependent-v2.npl");
        writeRuntimeConsumerPackage(replacement, dependentId, 2);
        LocalPluginInspection inspection = manager.inspectStorePluginPackage(replacement);
        PluginRuntimeInstallAuthorization authorization = new PluginRuntimeInstallAuthorization(
                Map.of(dependentId, new RuntimeProviderBinding(dependentId, newProviderId, "rust")),
                Set.of(), Set.of(), Set.of(), Set.of(), Set.of(),
                Map.of(dependentId, PluginPackageRuntimeContract.fromManifest(inspection.getManifest()))
        );

        manager.stagePluginInstallations(
                List.of(inspection),
                Map.of(dependentId, Set.of()),
                Map.of(newProviderId, identity(newProvider)),
                Map.of(dependentId, Optional.of(identity(installedDependent))),
                Map.of(),
                authorization
        );

        RuntimeProviderBinding binding = Objects.requireNonNull(
                new PluginRuntimeBindingStore(localHome, new PluginMutationLock(localHome))
                        .readStrict().get(dependentId)
        );
        assertEquals(newProviderId, binding.providerId());
        assertEquals("rust", binding.runtime());
    }

    /// Rejects a replacement batch whose runtime and concrete dependency edges form one mixed cycle.
    ///
    /// @param temporaryDirectory isolated launcher home and package sources
    /// @throws Exception if package creation, inspection, or graph validation fails unexpectedly
    @Test
    public void rejectsMixedRuntimeAndConcreteReplacementCycle(@TempDir Path temporaryDirectory) throws Exception {
        Path localHome = temporaryDirectory.resolve("home");
        PluginManager manager = new PluginManager(localHome);
        String dependentId = "dev.test.mixed-cycle-dependent";
        String providerId = "dev.test.mixed-cycle-provider";
        Path dependentPackage = temporaryDirectory.resolve("dependent.npl");
        Path providerPackage = temporaryDirectory.resolve("provider.npl");
        writeRuntimeConsumerPackage(dependentPackage, dependentId);
        writeRuntimeProviderPackage(
                providerPackage,
                providerId,
                2,
                "[{\"id\":\"" + dependentId + "\",\"version\":\"*\"}]"
        );
        LocalPluginInspection dependentInspection = manager.inspectStorePluginPackage(dependentPackage);
        LocalPluginInspection providerInspection = manager.inspectStorePluginPackage(providerPackage);
        PluginRuntimeInstallAuthorization authorization = new PluginRuntimeInstallAuthorization(
                Map.of(dependentId, new RuntimeProviderBinding(dependentId, providerId, "rust")),
                Set.of(), Set.of(), Set.of(), Set.of(), Set.of(),
                Map.of(
                        dependentId, PluginPackageRuntimeContract.fromManifest(dependentInspection.getManifest()),
                        providerId, PluginPackageRuntimeContract.fromManifest(providerInspection.getManifest())
                )
        );

        IOException exception = assertThrows(IOException.class, () -> manager.stagePluginInstallations(
                List.of(providerInspection, dependentInspection),
                Map.of(providerId, Set.of(), dependentId, Set.of()),
                Map.of(),
                Map.of(providerId, Optional.empty(), dependentId, Optional.empty()),
                Map.of(),
                authorization
        ));

        assertTrue(Objects.requireNonNull(exception.getMessage()).contains("Cyclic"));
        assertNoRuntimeTransactionState(localHome, manager, providerId, dependentId);
    }

    /// Rejects a Java package when the confirmed Store contract selected a Rust consumer.
    ///
    /// @param temporaryDirectory isolated launcher-local home and package source
    /// @throws Exception if package creation, inspection, or validation fails unexpectedly
    @Test
    public void rejectsDownloadedConsumerWhoseRuntimeDiffersFromStoreContract(
            @TempDir Path temporaryDirectory
    ) throws Exception {
        Path localHome = temporaryDirectory.resolve("home");
        PluginManager manager = new PluginManager(localHome);
        String pluginId = "dev.test.mismatched-rust-consumer";
        Path sourcePackage = temporaryDirectory.resolve("consumer.npl");
        writeApiFourPackage(sourcePackage, pluginId, "1.0.0");
        LocalPluginInspection inspection = manager.inspectStorePluginPackage(sourcePackage);
        PluginRuntimeInstallAuthorization authorization = authorizationWithContracts(Map.of(
                pluginId,
                new PluginPackageRuntimeContract(
                        "1.0.0",
                        5,
                        "rust",
                        2,
                        List.of(),
                        PluginKind.NORMAL,
                        PluginExecutionMode.EMBEDDED,
                        null,
                        List.of()
                )
        ));

        IOException exception = assertThrows(IOException.class, () -> manager.stagePluginInstallations(
                List.of(inspection),
                Map.of(pluginId, Set.of()),
                Map.of(),
                Map.of(pluginId, Optional.empty()),
                Map.of(),
                authorization
        ));

        assertTrue(Objects.requireNonNull(exception.getMessage()).contains(pluginId));
        assertNoRuntimeTransactionState(localHome, manager, pluginId);
    }

    /// Rejects a Provider package whose advertised ABI set differs from the confirmed Store contract.
    ///
    /// @param temporaryDirectory isolated launcher-local home and package source
    /// @throws Exception if package creation, inspection, or validation fails unexpectedly
    @Test
    public void rejectsDownloadedProviderWhoseDeclarationDiffersFromStoreContract(
            @TempDir Path temporaryDirectory
    ) throws Exception {
        Path localHome = temporaryDirectory.resolve("home");
        PluginManager manager = new PluginManager(localHome);
        String providerId = "dev.test.mismatched-rust-provider";
        Path sourcePackage = temporaryDirectory.resolve("provider.npl");
        writeRuntimeProviderPackage(sourcePackage, providerId);
        LocalPluginInspection inspection = manager.inspectStorePluginPackage(sourcePackage);
        RuntimeProviderDeclaration expectedDeclaration = new RuntimeProviderDeclaration(
                "rust",
                Set.of(3),
                1,
                Set.of(PluginExecutionMode.ISOLATED),
                Set.of(RuntimeFeature.BRIDGE)
        );
        PluginRuntimeInstallAuthorization authorization = authorizationWithContracts(Map.of(
                providerId,
                new PluginPackageRuntimeContract(
                        "1.0.0",
                        5,
                        "java",
                        2,
                        List.of(),
                        PluginKind.RUNTIME_PROVIDER,
                        PluginExecutionMode.EMBEDDED,
                        null,
                        List.of(expectedDeclaration)
                )
        ));

        IOException exception = assertThrows(IOException.class, () -> manager.stagePluginInstallations(
                List.of(inspection),
                Map.of(providerId, Set.of()),
                Map.of(),
                Map.of(providerId, Optional.empty()),
                Map.of(),
                authorization
        ));

        assertTrue(Objects.requireNonNull(exception.getMessage()).contains(providerId));
        assertNoRuntimeTransactionState(localHome, manager, providerId);
    }

    /// Rejects a binding whose runtime token differs from the downloaded dependent's exact manifest contract.
    ///
    /// @param temporaryDirectory isolated launcher-local home and package sources
    /// @throws Exception if package creation, inspection, or validation fails unexpectedly
    @Test
    public void rejectsBindingThatDiffersFromDownloadedRuntimeContracts(@TempDir Path temporaryDirectory)
            throws Exception {
        Path localHome = temporaryDirectory.resolve("home");
        PluginManager manager = new PluginManager(localHome);
        String providerId = "dev.test.binding-rust-provider";
        String dependentId = "dev.test.binding-python-consumer";
        Path providerPackage = temporaryDirectory.resolve("binding-provider.npl");
        Path dependentPackage = temporaryDirectory.resolve("binding-consumer.npl");
        writeRuntimeProviderPackage(providerPackage, providerId);
        writePythonRuntimeConsumerPackage(dependentPackage, dependentId);
        LocalPluginInspection providerInspection = manager.inspectStorePluginPackage(providerPackage);
        LocalPluginInspection dependentInspection = manager.inspectStorePluginPackage(dependentPackage);
        PluginRuntimeInstallAuthorization authorization = new PluginRuntimeInstallAuthorization(
                Map.of(dependentId, new RuntimeProviderBinding(dependentId, providerId, "rust")),
                Set.of(),
                Set.of(),
                Set.of(),
                Set.of(),
                Set.of(),
                Map.of(
                        providerId, PluginPackageRuntimeContract.fromManifest(providerInspection.getManifest()),
                        dependentId, PluginPackageRuntimeContract.fromManifest(dependentInspection.getManifest())
                )
        );

        IOException exception = assertThrows(IOException.class, () -> manager.stagePluginInstallations(
                List.of(providerInspection, dependentInspection),
                Map.of(providerId, Set.of(), dependentId, Set.of()),
                Map.of(),
                Map.of(providerId, Optional.empty(), dependentId, Optional.empty()),
                Map.of(),
                authorization
        ));

        assertTrue(Objects.requireNonNull(exception.getMessage()).contains(dependentId));
        assertNoRuntimeTransactionState(localHome, manager, providerId, dependentId);
    }

    /// Rejects a dangerous-permission Store transaction even when its custom source was acknowledged.
    ///
    /// @param temporaryDirectory isolated launcher-local home
    /// @throws Exception if manager construction or validation fails unexpectedly
    @Test
    public void customSourceReceiptCannotReplaceDangerousPermissionAcknowledgement(
            @TempDir Path temporaryDirectory
    ) throws Exception {
        PluginManager manager = new PluginManager(temporaryDirectory);
        String providerId = "dev.test.dangerous-rust-host";
        PluginRuntimeInstallAuthorization authorization = new PluginRuntimeInstallAuthorization(
                Map.of("dev.test.rust-tool", new RuntimeProviderBinding(
                        "dev.test.rust-tool", providerId, "rust")),
                Set.of(),
                Set.of(providerId),
                Set.of(providerId),
                Set.of(providerId),
                Set.of()
        );

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () ->
                manager.stagePluginInstallations(
                        List.of(),
                        Map.of(),
                        Map.of(),
                        Map.of(),
                        Map.of(),
                        authorization
                ));

        assertTrue(Objects.requireNonNull(exception.getMessage()).contains(providerId));
        assertFalse(Files.exists(temporaryDirectory.resolve("plugin-install-transaction.json")));
    }

    /// Rejects a Store transaction before publication when a custom-source Runtime Host lacks its explicit receipt.
    ///
    /// @param temporaryDirectory isolated launcher-local home
    /// @throws Exception if manager construction or validation fails unexpectedly
    @Test
    public void rejectRuntimeProviderInstallWithoutCustomSourceReceipt(@TempDir Path temporaryDirectory)
            throws Exception {
        PluginManager manager = new PluginManager(temporaryDirectory);
        PluginRuntimeInstallAuthorization authorization = new PluginRuntimeInstallAuthorization(
                Map.of("dev.test.rust-tool", new RuntimeProviderBinding(
                        "dev.test.rust-tool", "dev.test.rust-host", "rust")),
                Set.of(),
                Set.of("dev.test.rust-host"),
                Set.of()
        );

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () ->
                manager.stagePluginInstallations(
                        List.of(),
                        Map.of(),
                        Map.of(),
                        Map.of(),
                        Map.of(),
                        authorization
                ));

        assertTrue(Objects.requireNonNull(exception.getMessage()).contains("dev.test.rust-host"));
        assertFalse(Files.exists(temporaryDirectory.resolve("plugin-install-transaction.json")));
    }

    /// Atomically publishes a runtime binding and enables an already installed disabled Runtime Host.
    ///
    /// @param temporaryDirectory isolated launcher home and package source
    /// @throws Exception if package creation, inspection, or transaction publication fails
    @Test
    public void publishesRuntimeBindingAndEnablesInstalledHost(@TempDir Path temporaryDirectory) throws Exception {
        Path localHome = temporaryDirectory.resolve("home");
        PluginManager manager = new PluginManager(localHome);
        String providerId = "dev.hmclce.test.transaction-rust-host";
        String dependentId = "dev.hmclce.test.transaction-rust-tool";
        Path providerPackage = manager.getPluginsDirectory().resolve(providerId + ".npl");
        writeRuntimeProviderPackage(providerPackage, providerId);
        Path dependentPackage = temporaryDirectory.resolve("transaction-rust-tool.npl");
        writeRuntimeConsumerPackage(dependentPackage, dependentId);
        LocalPluginInspection inspection = manager.inspectStorePluginPackage(dependentPackage);

        manager.stagePluginInstallations(
                List.of(inspection),
                Map.of(dependentId, Set.of()),
                Map.of(providerId, identity(providerPackage)),
                Map.of(dependentId, Optional.empty()),
                Map.of(),
                authorization(inspection, providerId)
        );

        String bindings = Files.readString(localHome.resolve(PluginRuntimeBindingStore.FILE_NAME));
        assertTrue(bindings.contains(dependentId));
        assertTrue(bindings.contains(providerId));
        assertTrue(manager.isPluginEnabled(providerId));
        assertTrue(manager.isPluginEnabled(dependentId));
    }

    /// Restores package, binding, and enablement state when publication fails after binding persistence.
    ///
    /// @param temporaryDirectory isolated launcher home and package source
    /// @throws Exception if package creation, inspection, or rollback fails unexpectedly
    @Test
    public void rollsBackRuntimeBindingAndHostEnablementWhenStatePublicationFails(
            @TempDir Path temporaryDirectory
    ) throws Exception {
        Path localHome = temporaryDirectory.resolve("home");
        PluginManager manager = new PluginManager(localHome);
        String providerId = "dev.hmclce.test.rollback-rust-host";
        String dependentId = "dev.hmclce.test.rollback-rust-tool";
        Path providerPackage = manager.getPluginsDirectory().resolve(providerId + ".npl");
        writeRuntimeProviderPackage(providerPackage, providerId);
        Path dependentPackage = temporaryDirectory.resolve("rollback-rust-tool.npl");
        writeRuntimeConsumerPackage(dependentPackage, dependentId);
        LocalPluginInspection inspection = manager.inspectStorePluginPackage(dependentPackage);
        Files.createDirectory(localHome.resolve("plugin-states.json.tmp"));

        assertThrows(IOException.class, () -> manager.stagePluginInstallations(
                List.of(inspection),
                Map.of(dependentId, Set.of()),
                Map.of(providerId, identity(providerPackage)),
                Map.of(dependentId, Optional.empty()),
                Map.of(),
                authorization(inspection, providerId)
        ));

        assertTrue(Files.isRegularFile(providerPackage));
        assertFalse(Files.exists(manager.getPluginsDirectory().resolve(dependentId + ".npl")));
        assertFalse(Files.exists(localHome.resolve(PluginRuntimeBindingStore.FILE_NAME)));
        assertFalse(manager.isPluginEnabled(providerId));
        assertFalse(manager.isPluginEnabled(dependentId));
    }

    /// Restores the previous dependent binding when a replacement transaction fails after rebinding it.
    ///
    /// @param temporaryDirectory isolated launcher home and replacement source
    /// @throws Exception if package creation, publication failure, or rollback verification fails unexpectedly
    @Test
    public void restoresPreviousRuntimeBindingWhenReplacementStatePublicationFails(
            @TempDir Path temporaryDirectory
    ) throws Exception {
        Path localHome = temporaryDirectory.resolve("home");
        PluginManager manager = new PluginManager(localHome);
        String oldProviderId = "dev.test.rollback-old-provider";
        String newProviderId = "dev.test.rollback-new-provider";
        String dependentId = "dev.test.rollback-rebound-dependent";
        Path oldProvider = manager.getPluginsDirectory().resolve(oldProviderId + ".npl");
        Path newProvider = manager.getPluginsDirectory().resolve(newProviderId + ".npl");
        Path installedDependent = manager.getPluginsDirectory().resolve(dependentId + ".npl");
        writeRuntimeProviderPackage(oldProvider, oldProviderId, 1);
        writeRuntimeProviderPackage(newProvider, newProviderId, 2);
        writeRuntimeConsumerPackage(installedDependent, dependentId, "1.0.0", 1);
        writeBindings(localHome, oldProviderId, dependentId);
        Path replacement = temporaryDirectory.resolve("rebound-dependent-v2.npl");
        writeRuntimeConsumerPackage(replacement, dependentId, "2.0.0", 2);
        LocalPluginInspection inspection = manager.inspectStorePluginPackage(replacement);
        PluginRuntimeInstallAuthorization authorization = new PluginRuntimeInstallAuthorization(
                Map.of(dependentId, new RuntimeProviderBinding(dependentId, newProviderId, "rust")),
                Set.of(newProviderId), Set.of(), Set.of(), Set.of(), Set.of(),
                Map.of(dependentId, PluginPackageRuntimeContract.fromManifest(inspection.getManifest()))
        );
        Files.createDirectory(localHome.resolve("plugin-states.json.tmp"));

        assertThrows(IOException.class, () -> manager.stagePluginInstallations(
                List.of(inspection),
                Map.of(dependentId, Set.of()),
                Map.of(newProviderId, identity(newProvider)),
                Map.of(dependentId, Optional.of(identity(installedDependent))),
                Map.of(),
                authorization
        ));

        RuntimeProviderBinding restoredBinding = Objects.requireNonNull(
                new PluginRuntimeBindingStore(localHome, new PluginMutationLock(localHome))
                        .readStrict().get(dependentId)
        );
        assertEquals(oldProviderId, restoredBinding.providerId());
        assertEquals("1.0.0", readManifest(installedDependent).getVersion());
        assertFalse(manager.isPluginEnabled(newProviderId));
    }

    /// Blocks Runtime Host removal and reports every dependent recorded only by virtual runtime bindings.
    ///
    /// @param temporaryDirectory isolated launcher home
    /// @throws Exception if package creation, binding publication, or uninstall validation fails unexpectedly
    @Test
    public void runtimeBindingsBlockProviderUninstallAndReportAllDependents(
            @TempDir Path temporaryDirectory
    ) throws Exception {
        Path localHome = temporaryDirectory.resolve("home");
        PluginManager manager = new PluginManager(localHome);
        String providerId = "dev.hmclce.test.bound-runtime-host";
        String firstDependentId = "dev.hmclce.test.bound-rust-one";
        String secondDependentId = "dev.hmclce.test.bound-rust-two";
        for (String pluginId : List.of(providerId, firstDependentId, secondDependentId)) {
            writeApiFourPackage(manager.getPluginsDirectory().resolve(pluginId + ".npl"), pluginId, "1.0.0");
        }
        writeBindings(localHome, providerId, firstDependentId, secondDependentId);

        IOException exception = assertThrows(IOException.class, () -> manager.uninstallPlugin(providerId));

        assertTrue(Objects.requireNonNull(exception.getMessage()).contains(firstDependentId));
        assertTrue(exception.getMessage().contains(secondDependentId));
        assertTrue(Files.exists(manager.getPluginsDirectory().resolve(providerId + ".npl")));
    }

    /// Rejects default Runtime Host disablement while reporting every enabled bound dependent.
    ///
    /// @param temporaryDirectory isolated launcher home
    /// @throws Exception if package creation or durable state mutation fails
    @Test
    public void disablingProviderRejectsEnabledBoundRuntimeDependents(@TempDir Path temporaryDirectory)
            throws Exception {
        Path localHome = temporaryDirectory.resolve("home");
        PluginManager manager = new PluginManager(localHome);
        String providerId = "dev.hmclce.test.disable-runtime-host";
        String firstDependentId = "dev.hmclce.test.disable-rust-one";
        String secondDependentId = "dev.hmclce.test.disable-rust-two";
        for (String pluginId : List.of(providerId, firstDependentId, secondDependentId)) {
            writeApiFourPackage(manager.getPluginsDirectory().resolve(pluginId + ".npl"), pluginId, "1.0.0");
            manager.enablePlugin(pluginId);
            assertTrue(manager.isPluginEnabled(pluginId));
        }
        writeBindings(localHome, providerId, firstDependentId, secondDependentId);

        IOException exception = assertThrows(IOException.class, () -> manager.disablePlugin(providerId));

        assertTrue(Objects.requireNonNull(exception.getMessage()).contains(firstDependentId));
        assertTrue(exception.getMessage().contains(secondDependentId));
        assertTrue(manager.isPluginEnabled(providerId));
        assertTrue(manager.isPluginEnabled(firstDependentId));
        assertTrue(manager.isPluginEnabled(secondDependentId));
    }

    /// Disables every virtual runtime dependent only through the explicit cascade operation.
    ///
    /// @param temporaryDirectory isolated launcher home
    /// @throws Exception if package creation or durable state mutation fails
    @Test
    public void cascadeDisableDisablesAllBoundRuntimeDependents(@TempDir Path temporaryDirectory) throws Exception {
        Path localHome = temporaryDirectory.resolve("home");
        PluginManager manager = new PluginManager(localHome);
        String providerId = "dev.test.cascade-disable-runtime-host";
        String firstDependentId = "dev.test.cascade-disable-rust-one";
        String secondDependentId = "dev.test.cascade-disable-rust-two";
        for (String pluginId : List.of(providerId, firstDependentId, secondDependentId)) {
            writeApiFourPackage(manager.getPluginsDirectory().resolve(pluginId + ".npl"), pluginId, "1.0.0");
            manager.enablePlugin(pluginId);
            assertTrue(manager.isPluginEnabled(pluginId));
        }
        writeBindings(localHome, providerId, firstDependentId, secondDependentId);

        manager.disablePluginCascade(providerId);

        assertFalse(manager.isPluginEnabled(providerId));
        assertFalse(manager.isPluginEnabled(firstDependentId));
        assertFalse(manager.isPluginEnabled(secondDependentId));
    }

    /// Rejects a Runtime Host replacement that no longer satisfies an existing virtual binding.
    ///
    /// @param temporaryDirectory isolated launcher home and replacement source
    /// @throws Exception if package creation, inspection, or graph validation fails unexpectedly
    @Test
    public void runtimeBindingBlocksIncompatibleProviderUpdate(@TempDir Path temporaryDirectory) throws Exception {
        Path localHome = temporaryDirectory.resolve("home");
        PluginManager manager = new PluginManager(localHome);
        String providerId = "dev.hmclce.test.updated-runtime-host";
        String dependentId = "dev.hmclce.test.updated-rust-dependent";
        Path providerPackage = manager.getPluginsDirectory().resolve(providerId + ".npl");
        writeApiFourPackage(providerPackage, providerId, "1.0.0");
        writeApiFourPackage(manager.getPluginsDirectory().resolve(dependentId + ".npl"), dependentId, "1.0.0");
        writeBindings(localHome, providerId, dependentId);
        Path replacement = temporaryDirectory.resolve("incompatible-runtime-host.npl");
        writeApiFourPackage(replacement, providerId, "2.0.0");

        IOException exception = assertThrows(
                IOException.class,
                () -> manager.prepareLocalPluginInstallation(replacement, Set.of())
        );

        assertTrue(Objects.requireNonNull(exception.getMessage()).contains(dependentId));
        assertEquals("1.0.0", readManifest(providerPackage).getVersion());
    }

    /// Creates the exact reusable Provider artifact identity for a package.
    ///
    /// @param packageFile installed Provider package
    /// @return exact Provider identity
    /// @throws IOException if the manifest or package digest cannot be read
    private static PluginArtifactIdentity identity(Path packageFile) throws IOException {
        return PluginArtifactIdentity.of(readManifest(packageFile), PluginPackageVersions.calculateSha256(packageFile));
    }

    /// Creates the confirmed runtime authorization used by Store publication tests.
    ///
    /// @param inspection downloaded dependent package inspection
    /// @param providerId Provider plugin ID
    /// @return exact binding and Host enablement authorization
    private static PluginRuntimeInstallAuthorization authorization(
            LocalPluginInspection inspection,
            String providerId
    ) {
        String dependentId = inspection.getManifest().getId();
        return new PluginRuntimeInstallAuthorization(
                Map.of(dependentId, new RuntimeProviderBinding(dependentId, providerId, "rust")),
                Set.of(providerId),
                Set.of(),
                Set.of(),
                Set.of(),
                Set.of(),
                Map.of(dependentId, PluginPackageRuntimeContract.fromManifest(inspection.getManifest()))
        );
    }

    /// Creates Store authorization containing only exact package runtime contracts.
    ///
    /// @param contracts expected contracts indexed by changed plugin ID
    /// @return immutable Store transaction authorization
    private static PluginRuntimeInstallAuthorization authorizationWithContracts(
            Map<String, PluginPackageRuntimeContract> contracts
    ) {
        return new PluginRuntimeInstallAuthorization(
                Map.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), contracts);
    }

    /// Asserts that a rejected operation published no package, binding, or transaction journal state.
    ///
    /// @param localHome launcher-local home
    /// @param manager isolated plugin manager
    /// @param pluginIds rejected plugin IDs
    private static void assertNoRuntimeTransactionState(
            Path localHome,
            PluginManager manager,
            String... pluginIds
    ) {
        for (String pluginId : pluginIds) {
            assertFalse(Files.exists(manager.getPluginsDirectory().resolve(pluginId + ".npl")));
        }
        assertFalse(Files.exists(localHome.resolve(PluginRuntimeBindingStore.FILE_NAME)));
        assertFalse(Files.exists(localHome.resolve("plugin-install-transaction.json")));
    }

    /// Writes a complete binding document for one Provider and all supplied dependents.
    ///
    /// @param localHome launcher-local home
    /// @param providerId Provider plugin ID
    /// @param dependentIds dependent plugin IDs
    /// @throws IOException if the document cannot be written
    private static void writeBindings(Path localHome, String providerId, String... dependentIds) throws IOException {
        PluginRuntimeBindingStore store = new PluginRuntimeBindingStore(localHome, new PluginMutationLock(localHome));
        java.util.LinkedHashMap<String, RuntimeProviderBinding> bindings = new java.util.LinkedHashMap<>();
        for (String dependentId : dependentIds) {
            bindings.put(dependentId, new RuntimeProviderBinding(dependentId, providerId, "rust"));
        }
        store.mergeStrict(Map.copyOf(bindings));
    }

    /// Writes a schema-v5 Java Runtime Host providing the embedded Rust ABI 2 bridge.
    ///
    /// @param target target package path
    /// @param pluginId Provider plugin ID
    /// @throws IOException if package creation fails
    private static void writeRuntimeProviderPackage(Path target, String pluginId) throws IOException {
        writeRuntimeProviderPackage(target, pluginId, 2);
    }

    /// Writes a schema-v5 Java Runtime Host providing one embedded Rust ABI.
    ///
    /// @param target target package path
    /// @param pluginId Provider plugin ID
    /// @param providedAbi provided Rust plugin ABI
    /// @throws IOException if package creation fails
    private static void writeRuntimeProviderPackage(Path target, String pluginId, int providedAbi)
            throws IOException {
        writeRuntimeProviderPackage(target, pluginId, providedAbi, "[]");
    }

    /// Writes a schema-v5 Java Runtime Host with one explicit concrete dependency array.
    ///
    /// @param target target package path
    /// @param pluginId Provider plugin ID
    /// @param providedAbi provided Rust plugin ABI
    /// @param dependenciesJson concrete dependency array JSON
    /// @throws IOException if package creation fails
    private static void writeRuntimeProviderPackage(
            Path target,
            String pluginId,
            int providedAbi,
            String dependenciesJson
    ) throws IOException {
        writePackage(target, pluginId, "1.0.0", """
                "runtime": "java", "abi": 2, "pluginKind": "runtime-provider",
                "executionMode": "embedded", "platforms": [],
                "providesRuntimes": [{"runtime": "rust", "abis": [%s], "bridgeAbi": 1,
                  "executionModes": ["embedded"], "features": ["bridge"]}]
                """.formatted(providedAbi), dependenciesJson);
    }

    /// Writes a schema-v5 embedded Rust ABI 2 consumer package.
    ///
    /// @param target target package path
    /// @param pluginId dependent plugin ID
    /// @throws IOException if package creation fails
    private static void writeRuntimeConsumerPackage(Path target, String pluginId) throws IOException {
        writeRuntimeConsumerPackage(target, pluginId, 2);
    }

    /// Writes a schema-v5 embedded Rust consumer package for one ABI.
    ///
    /// @param target target package path
    /// @param pluginId dependent plugin ID
    /// @param abi required Rust plugin ABI
    /// @throws IOException if package creation fails
    private static void writeRuntimeConsumerPackage(Path target, String pluginId, int abi) throws IOException {
        writeRuntimeConsumerPackage(target, pluginId, "1.0.0", abi);
    }

    /// Writes a schema-v5 embedded Rust consumer package for one version and ABI.
    ///
    /// @param target target package path
    /// @param pluginId dependent plugin ID
    /// @param version package version
    /// @param abi required Rust plugin ABI
    /// @throws IOException if package creation fails
    private static void writeRuntimeConsumerPackage(Path target, String pluginId, String version, int abi)
            throws IOException {
        writePackage(target, pluginId, version, """
                "runtime": "rust", "abi": %s, "pluginKind": "normal",
                "executionMode": "embedded", "platforms": []
                """.formatted(abi));
    }

    /// Writes a schema-v5 embedded Python ABI 2 consumer package.
    ///
    /// @param target target package path
    /// @param pluginId dependent plugin ID
    /// @throws IOException if package creation fails
    private static void writePythonRuntimeConsumerPackage(Path target, String pluginId) throws IOException {
        writePackage(target, pluginId, "1.0.0", """
                "runtime": "python", "abi": 2, "pluginKind": "normal",
                "executionMode": "embedded", "platforms": []
                """);
    }

    /// Writes a minimal API-v4 Java package.
    ///
    /// @param target target package path
    /// @param pluginId plugin ID
    /// @param version package version
    /// @throws IOException if package creation fails
    private static void writeApiFourPackage(Path target, String pluginId, String version) throws IOException {
        writePackage(target, pluginId, version, "");
    }

    /// Writes one executable test package with optional schema-v5 declarations.
    ///
    /// @param target target package path
    /// @param pluginId plugin ID
    /// @param version package version
    /// @param declarations schema-v5 declarations, or blank for API v4
    /// @throws IOException if package creation fails
    private static void writePackage(Path target, String pluginId, String version, String declarations)
            throws IOException {
        writePackage(target, pluginId, version, declarations, "[]");
    }

    /// Writes one executable test package with optional schema-v5 declarations and concrete dependencies.
    ///
    /// @param target target package path
    /// @param pluginId plugin ID
    /// @param version package version
    /// @param declarations schema-v5 declarations, or blank for API v4
    /// @param dependenciesJson concrete dependency array JSON
    /// @throws IOException if package creation fails
    private static void writePackage(
            Path target,
            String pluginId,
            String version,
            String declarations,
            String dependenciesJson
    ) throws IOException {
        Files.createDirectories(Objects.requireNonNull(target.getParent()));
        boolean schemaFive = !declarations.isBlank();
        String manifest = """
                {"schemaVersion": %s, "id": "%s", "name": "Runtime Transaction Test",
                 "version": "%s", "type": "java", "entrypoint": "%s",
                 "permissions": [], "requiredPermissions": [], "launcherVersion": "*",
                 "dependencies": %s%s}
                """.formatted(
                schemaFive ? 5 : 4,
                pluginId,
                version,
                PackagedTestPlugin.class.getName(),
                dependenciesJson,
                schemaFive ? "," + declarations : ""
        );
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(target))) {
            writeEntry(output, "plugin.json", manifest.getBytes(StandardCharsets.UTF_8));
            String classResource = PackagedTestPlugin.class.getName().replace('.', '/') + ".class";
            try (var input = Objects.requireNonNull(
                    PackagedTestPlugin.class.getClassLoader().getResourceAsStream(classResource))) {
                writeEntry(output, classResource, input.readAllBytes());
            }
        }
    }

    /// Writes one deterministic ZIP entry.
    ///
    /// @param output target package stream
    /// @param name entry path
    /// @param contents entry bytes
    /// @throws IOException if the entry cannot be written
    private static void writeEntry(ZipOutputStream output, String name, byte[] contents) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(0);
        output.putNextEntry(entry);
        output.write(contents);
        output.closeEntry();
    }

    /// Reads one validated manifest from a package.
    ///
    /// @param packageFile package path
    /// @return validated manifest
    /// @throws IOException if the package or manifest is invalid
    private static PluginManifest readManifest(Path packageFile) throws IOException {
        try (ZipFile zipFile = new ZipFile(packageFile.toFile())) {
            ZipEntry entry = Objects.requireNonNull(zipFile.getEntry("plugin.json"));
            try (InputStreamReader reader = new InputStreamReader(
                    zipFile.getInputStream(entry), StandardCharsets.UTF_8)) {
                return PluginManifest.fromJson(reader);
            }
        }
    }
}
