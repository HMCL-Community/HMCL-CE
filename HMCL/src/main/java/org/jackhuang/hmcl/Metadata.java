/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2021  huangyuhui <huanghongxun2008@126.com> and contributors
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

import org.jackhuang.hmcl.util.StringUtils;
import org.jackhuang.hmcl.util.io.JarUtils;
import org.jackhuang.hmcl.util.platform.Architecture;
import org.jackhuang.hmcl.util.platform.OperatingSystem;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.EnumSet;

/// Stores product identity, repository endpoints, release metadata, and local storage locations.
@NotNullByDefault
public final class Metadata {
    /// Prevents construction of the application metadata holder.
    private Metadata() {
    }

    /// Short product name displayed by the launcher.
    public static final String NAME = "HMCL CE";
    /// Full product name displayed by the launcher.
    public static final String FULL_NAME = "HMCL CE";
    /// Build version embedded by Gradle or overridden for development diagnostics.
    public static final String VERSION = System.getProperty("hmcl.version.override", JarUtils.getAttribute("hmcl.version", "@develop@"));

    /// Explicit Application User Model ID used for Windows taskbar grouping and pinning.
    public static final String WINDOWS_APP_USER_MODEL_ID = "org.hmclcommunity.hmclce";

    /// Window title containing the short product name and version.
    public static final String TITLE = NAME + " " + VERSION;
    /// Full window title containing the product name and version.
    public static final String FULL_TITLE = FULL_NAME + " v" + VERSION;

    /// Oldest Java feature version capable of starting the launcher.
    public static final int MINIMUM_REQUIRED_JAVA_VERSION = 17;
    /// Oldest Java feature version supported by the project.
    public static final int MINIMUM_SUPPORTED_JAVA_VERSION = 17;
    /// Java feature version recommended for launcher operation.
    public static final int RECOMMENDED_JAVA_VERSION = 21;

    /// Canonical HMCL CE source repository.
    public static final String PUBLISH_URL = "https://github.com/HMCL-Community/HMCL-CE";
    /// Public page containing all HMCL CE downloads.
    public static final String DOWNLOAD_URL = PUBLISH_URL + "/releases";
    /// GitHub Releases API endpoint consumed by the automatic updater.
    public static final String GITHUB_RELEASES_API_URL =
            "https://api.github.com/repos/HMCL-Community/HMCL-CE/releases";
    /// Update endpoint, with an explicit system-property override for mirrors and testing.
    public static final String HMCL_UPDATE_URL = System.getProperty("hmcl.update_source.override", GITHUB_RELEASES_API_URL);
    /// Latest stable release page used when automatic installation fails.
    public static final String MANUAL_UPDATE_URL = DOWNLOAD_URL + "/latest";

    /// Upstream HMCL documentation site retained for general launcher documentation.
    public static final String DOCS_URL = "https://docs.hmcl.net";
    /// HMCL CE issue form used for launcher and crash feedback.
    public static final String CONTACT_URL = PUBLISH_URL + "/issues/new/choose";
    /// Prefix for the GitHub release page corresponding to a launcher version.
    public static final String CHANGELOG_URL = PUBLISH_URL + "/releases/tag/v";
    /// Upstream end-user license information retained by the CE distribution.
    public static final String EULA_URL = DOCS_URL + "/eula/hmcl.html";
    /// Community chat group information page.
    public static final String GROUPS_URL = "https://www.bilibili.com/opus/905435541874409529";

    /// Build channel embedded by Gradle.
    public static final String BUILD_CHANNEL = JarUtils.getAttribute("hmcl.version.type", "nightly");
    /// Git commit embedded by GitHub Actions, or `null` for local builds.
    public static final @Nullable String GITHUB_SHA = JarUtils.getAttribute("hmcl.version.hash", null);

    /// Process working directory normalized to an absolute path.
    public static final Path CURRENT_DIRECTORY = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
    /// Default Minecraft game directory for the current platform.
    public static final Path MINECRAFT_DIRECTORY = OperatingSystem.getWorkingDirectory("minecraft");
    /// User-wide HMCL data directory.
    public static final Path HMCL_USER_HOME;
    /// Launcher-local HMCL data directory.
    public static final Path HMCL_LOCAL_HOME;
    /// Directory containing downloaded runtime dependencies.
    public static final Path DEPENDENCIES_DIRECTORY;

    static {
        String hmclHome = System.getProperty("hmcl.home", System.getenv("HMCL_USER_HOME"));
        if (StringUtils.isBlank(hmclHome)) {
            if (OperatingSystem.CURRENT_OS.isLinuxOrBSD()) {
                String xdgData = System.getenv("XDG_DATA_HOME");
                if (StringUtils.isNotBlank(xdgData)) {
                    HMCL_USER_HOME = Path.of(xdgData, "hmcl").toAbsolutePath().normalize();
                } else {
                    HMCL_USER_HOME = Path.of(System.getProperty("user.home"), ".local", "share", "hmcl").toAbsolutePath().normalize();
                }
            } else {
                HMCL_USER_HOME = OperatingSystem.getWorkingDirectory("hmcl");
            }
        } else {
            HMCL_USER_HOME = Path.of(hmclHome).toAbsolutePath().normalize();
        }

        String hmclCurrentDir = System.getProperty("hmcl.dir", System.getenv("HMCL_LOCAL_HOME"));
        HMCL_LOCAL_HOME = StringUtils.isNotBlank(hmclCurrentDir)
                ? Path.of(hmclCurrentDir).toAbsolutePath().normalize()
                : CURRENT_DIRECTORY.resolve(".hmcl");

        String hmclDependencies = System.getProperty("hmcl.dependencies.dir", System.getenv("HMCL_DEPENDENCIES_DIR"));
        DEPENDENCIES_DIRECTORY = StringUtils.isNotBlank(hmclDependencies)
                ? Path.of(hmclDependencies).toAbsolutePath().normalize()
                : HMCL_LOCAL_HOME.resolve("dependencies");
    }

    /// Returns whether this build belongs to the stable release channel.
    ///
    /// @return whether the build channel is stable
    public static boolean isStable() {
        return "stable".equals(BUILD_CHANNEL);
    }

    /// Returns whether this build belongs to the development channel.
    ///
    /// @return whether the build channel is development
    public static boolean isDev() {
        return "dev".equals(BUILD_CHANNEL);
    }

    /// Returns whether this build belongs to the nightly channel.
    ///
    /// @return whether the build is neither stable nor development
    public static boolean isNightly() {
        return !isStable() && !isDev();
    }

    /// Selects a supported Java download guide for the current platform and architecture.
    ///
    /// @return Java download guide URL, or `null` when the platform is unsupported
    public static @Nullable String getSuggestedJavaDownloadLink() {
        if (OperatingSystem.CURRENT_OS == OperatingSystem.LINUX && Architecture.SYSTEM_ARCH == Architecture.LOONGARCH64_OW)
            return "https://www.loongnix.cn/zh/api/java/downloads-jdk21/index.html";
        else {
            EnumSet<Architecture> supportedArchitectures;
            if (OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS)
                supportedArchitectures = EnumSet.of(Architecture.X86_64, Architecture.X86, Architecture.ARM64);
            else if (OperatingSystem.CURRENT_OS == OperatingSystem.LINUX)
                supportedArchitectures = EnumSet.of(
                        Architecture.X86_64, Architecture.X86,
                        Architecture.ARM64, Architecture.ARM32,
                        Architecture.RISCV64, Architecture.LOONGARCH64
                );
            else if (OperatingSystem.CURRENT_OS == OperatingSystem.MACOS)
                supportedArchitectures = EnumSet.of(Architecture.X86_64, Architecture.ARM64);
            else
                supportedArchitectures = EnumSet.noneOf(Architecture.class);
            if (supportedArchitectures.contains(Architecture.SYSTEM_ARCH))
                return String.format("https://docs.hmcl.net/downloads/%s/%s.html",
                        OperatingSystem.CURRENT_OS.getCheckedName(),
                        Architecture.SYSTEM_ARCH.getCheckedName()
                );
            else
                return null;
        }
    }
}
