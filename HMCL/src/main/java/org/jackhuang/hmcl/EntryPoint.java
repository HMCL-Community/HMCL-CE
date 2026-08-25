/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2020  huangyuhui <huanghongxun2008@126.com> and contributors
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
package org.jackhuang.hmcl;

import org.jackhuang.hmcl.util.FileSaver;
import org.jackhuang.hmcl.util.RestartBarrier;
import org.jackhuang.hmcl.util.SelfDependencyPatcher;
import org.jackhuang.hmcl.util.SwingUtils;
import org.jackhuang.hmcl.java.JavaRuntime;
import org.jackhuang.hmcl.plugin.mixin.bootstrap.HmclMixinBootstrap;
import org.jackhuang.hmcl.plugin.protector.ProtectorBootstrap;
import org.jackhuang.hmcl.plugin.protector.StartupReporter;
import org.jackhuang.hmcl.util.io.FileUtils;
import org.jackhuang.hmcl.util.io.JarUtils;
import org.jackhuang.hmcl.util.platform.OperatingSystem;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import javax.swing.JOptionPane;
import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;
import static org.jackhuang.hmcl.util.i18n.I18n.i18n;

/// Prepares launcher process prerequisites and selects Mixin, Protector parent, or protected-child execution roles.
@NotNullByDefault
public final class EntryPoint {

    /// Prevents construction of the launcher entry point.
    private EntryPoint() {
    }

    /// Starts launcher process preparation and delegates only the protected child to normal launcher initialization.
    ///
    /// @param args process arguments
    public static void main(String @Unmodifiable [] args) {
        String[] launcherArgs = RestartBarrier.awaitParentsAndStrip(args);

        // Mixin premain may load plugin classes that reference JavaFX, so repair JavaFX before relaunching the Agent JVM.
        checkJavaFX();
        verifyJavaFX();
        try {
            runProtectedLauncher(
                    launcherArgs,
                    HmclMixinBootstrap::relaunchIfNeeded,
                    ProtectorBootstrap::enter,
                    EntryPoint::launchProtectedChild
            );
        } catch (IOException exception) {
            LOG.error("Failed to initialize launcher startup protection", exception);
            exit(1);
        }
    }

    /// Executes ordered Mixin and Protector role selection before starting normal launcher runtime.
    ///
    /// @param launcherArgs restart-barrier-stripped launcher arguments
    /// @param mixinRelaunch Mixin relaunch selector
    /// @param protectorEntry Protector parent-or-child selector
    /// @param launcherRuntime normal protected-child runtime entry
    /// @throws IOException if Protector role selection fails
    static void runProtectedLauncher(
            String @Unmodifiable [] launcherArgs,
            Predicate<String @Unmodifiable []> mixinRelaunch,
            ProtectorEntry protectorEntry,
            Consumer<String @Unmodifiable []> launcherRuntime
    ) throws IOException {
        if (mixinRelaunch.test(launcherArgs)) {
            return;
        }
        String @Nullable @Unmodifiable [] childArgs = protectorEntry.enter(launcherArgs);
        if (childArgs == null) {
            return;
        }
        launcherRuntime.accept(childArgs);
    }

    /// Initializes process-wide launcher services only inside the authenticated protected child.
    ///
    /// @param launcherArgs Protector-stripped launcher arguments
    private static void launchProtectedChild(String @Unmodifiable [] launcherArgs) {

        System.getProperties().putIfAbsent("java.net.useSystemProxies", "true");
        System.getProperties().putIfAbsent("javafx.autoproxy.disable", "true");
        System.getProperties().putIfAbsent("http.agent", "HMCL/" + Metadata.VERSION);

        createHMCLDirectories();
        LOG.start(Metadata.HMCL_LOCAL_HOME.resolve("logs"));

        checkWine();

        if (OperatingSystem.CURRENT_OS == OperatingSystem.MACOS) {
            System.getProperties().putIfAbsent("apple.awt.application.appearance", "system");
            if (!isInsideMacAppBundle())
                initIcon();
        }

        addEnableNativeAccess();
        enableUnsafeMemoryAccess();

        Launcher.main(launcherArgs);
    }

    /// Checked Protector entry operation used by the ordered role-selection seam.
    @FunctionalInterface
    interface ProtectorEntry {
        /// Selects the Protector parent or connects an authenticated protected child.
        ///
        /// @param launcherArgs restart-barrier-stripped launcher arguments
        /// @return child launcher arguments, or `null` after parent supervision
        /// @throws IOException if local startup protection fails closed
        String @Nullable @Unmodifiable [] enter(String @Unmodifiable [] launcherArgs) throws IOException;
    }

    /// Terminates the launcher, reporting an explicit zero-status startup cancellation before process cleanup.
    ///
    /// @param exitCode process exit status
    public static void exit(int exitCode) {
        if (exitCode == 0) {
            StartupReporter.reportCancel();
        }
        FileSaver.shutdown();
        LOG.shutdown();
        System.exit(exitCode);
    }

    private static void createHMCLDirectories() {
        if (!Files.isDirectory(Metadata.HMCL_LOCAL_HOME)) {
            try {
                Files.createDirectories(Metadata.HMCL_LOCAL_HOME);
                if (OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS) {
                    try {
                        Files.setAttribute(Metadata.HMCL_LOCAL_HOME, "dos:hidden", true);
                    } catch (IOException e) {
                        LOG.warning("Failed to set hidden attribute of " + Metadata.HMCL_LOCAL_HOME, e);
                    }
                }
            } catch (IOException e) {
                // Logger has not been started yet, so print directly to System.err
                System.err.println("Failed to create HMCL directory: " + Metadata.HMCL_LOCAL_HOME);
                e.printStackTrace(System.err);
                showErrorAndExit(i18n("fatal.create_hmcl_current_directory_failure", Metadata.HMCL_LOCAL_HOME));
            }
        }

        if (!Files.isDirectory(Metadata.HMCL_USER_HOME)) {
            try {
                Files.createDirectories(Metadata.HMCL_USER_HOME);
            } catch (IOException e) {
                LOG.warning("Failed to create HMCL user home " + Metadata.HMCL_USER_HOME, e);
            }
        }
    }

    private static boolean isInsideMacAppBundle() {
        Path thisJar = JarUtils.thisJarPath();
        if (thisJar == null)
            return false;

        for (Path current = thisJar.getParent();
             current != null && current.getParent() != null;
             current = current.getParent()
        ) {
            if ("Contents".equals(FileUtils.getName(current))
                    && FileUtils.getName(current.getParent()).endsWith(".app")
                    && Files.exists(current.resolve("Info.plist"))
            ) {
                return true;
            }
        }
        return false;
    }

    private static void initIcon() {
        try {
            if (java.awt.Taskbar.isTaskbarSupported()) {
                var image = java.awt.Toolkit.getDefaultToolkit().getImage(EntryPoint.class.getResource("/assets/img/icon-mac.png"));
                java.awt.Taskbar.getTaskbar().setIconImage(image);
            }
        } catch (Throwable e) {
            LOG.warning("Failed to set application icon", e);
        }
    }

    private static void checkJavaFX() {
        try {
            SelfDependencyPatcher.patch();
        } catch (SelfDependencyPatcher.PatchException e) {
            LOG.error("Unable to patch JVM", e);
            showErrorAndExit(i18n("fatal.javafx.missing"));
        } catch (CancellationException e) {
            LOG.error("User cancels downloading JavaFX", e);
            exit(0);
        }
    }

    /**
     * Check if JavaFX exists but is incomplete
     */
    private static void verifyJavaFX() {
        try {
            Class.forName("javafx.beans.binding.Binding"); // javafx.base
            Class.forName("javafx.stage.Stage");           // javafx.graphics
            Class.forName("javafx.scene.control.Skin");    // javafx.controls
        } catch (Exception e) {
            LOG.warning("JavaFX is incomplete or not found", e);
            showErrorAndExit(i18n("fatal.javafx.incomplete"));
        }
    }

    private static void checkWine() {
        if (OperatingSystem.isRunningUnderWine()) {
            LOG.warning("HMCL is running under Wine or its distributions!");
            showWarning(i18n("fatal.wine_warning"));
        }
    }

    private static void addEnableNativeAccess() {
        if (JavaRuntime.CURRENT_VERSION > 21) {
            try {
                // javafx.graphics
                Module module = Class.forName("javafx.stage.Stage").getModule();
                if (module.isNamed()) {
                    try {
                        MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(Module.class, MethodHandles.lookup());
                        MethodHandle implAddEnableNativeAccess = lookup.findVirtual(Module.class,
                                "implAddEnableNativeAccess", MethodType.methodType(Module.class));
                        Module ignored = (Module) implAddEnableNativeAccess.invokeExact(module);
                    } catch (Throwable e) {
                        e.printStackTrace(System.err);
                    }
                }
            } catch (ClassNotFoundException e) {
                LOG.error("Failed to add enable native access for JavaFX", e);
                showErrorAndExit(i18n("fatal.javafx.incomplete"));
            }
        }
    }

    private static void enableUnsafeMemoryAccess() {
        // https://openjdk.org/jeps/498
        if (JavaRuntime.CURRENT_VERSION == 24 || JavaRuntime.CURRENT_VERSION == 25) {
            try {
                Class<?> clazz = Class.forName("sun.misc.Unsafe");
                boolean ignored = (boolean) MethodHandles.privateLookupIn(clazz, MethodHandles.lookup())
                        .findStatic(clazz, "trySetMemoryAccessWarned", MethodType.methodType(boolean.class))
                        .invokeExact();
            } catch (Throwable e) {
                LOG.warning("Failed to enable unsafe memory access", e);
            }
        }
    }

    static void showWarning(String message) {
        SwingUtils.initLookAndFeel();

        int result = JOptionPane.showOptionDialog(null, message, i18n("message.warning"), JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.WARNING_MESSAGE, null, null, null);

        if (result == JOptionPane.CANCEL_OPTION || result == JOptionPane.CLOSED_OPTION) {
            exit(1);
        }
    }

    /**
     * Indicates that a fatal error has occurred, and that the application cannot start.
     */
    static void showErrorAndExit(String message) {
        SwingUtils.showErrorDialog(message);
        exit(1);
    }
}
