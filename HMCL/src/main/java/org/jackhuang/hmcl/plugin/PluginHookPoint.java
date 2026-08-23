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

import com.google.gson.annotations.SerializedName;
import org.jetbrains.annotations.NotNullByDefault;

/// Launcher lifecycle hook points a schema-v5 plugin may subscribe to.
///
/// Hook plugins receive an ABI context object before or after core operations such as downloads, login, or game
/// launch and may adjust arguments or observe results. They never touch JVM classes directly: the launcher
/// marshals the operation into a Plugin ABI object, applies plugin adjustments, and only then continues.
@NotNullByDefault
public enum PluginHookPoint {
    /// Raised before a file download queue starts; the context exposes the target list.
    @SerializedName("before-download")
    BEFORE_DOWNLOAD("before-download"),

    /// Raised after a file download queue finishes; the context exposes per-file results.
    @SerializedName("after-download")
    AFTER_DOWNLOAD("after-download"),

    /// Raised before the Minecraft process starts; the context exposes JVM and game arguments.
    @SerializedName("before-game-launch")
    BEFORE_GAME_LAUNCH("before-game-launch"),

    /// Raised after the Minecraft process exits; the context exposes the exit code.
    @SerializedName("after-game-launch")
    AFTER_GAME_LAUNCH("after-game-launch"),

    /// Raised before an account login attempt; the context exposes the account and auth mode.
    @SerializedName("before-login")
    BEFORE_LOGIN("before-login"),

    /// Raised after an account login attempt; the context exposes the resulting profile.
    @SerializedName("after-login")
    AFTER_LOGIN("after-login"),

    /// Raised before a new instance is created; the context exposes the template and name.
    @SerializedName("before-instance-create")
    BEFORE_INSTANCE_CREATE("before-instance-create"),

    /// Raised after a new instance is created; the context exposes the instance id.
    @SerializedName("after-instance-create")
    AFTER_INSTANCE_CREATE("after-instance-create"),

    /// Raised before a mod is installed into an instance; the context exposes the mod file.
    @SerializedName("before-mod-install")
    BEFORE_MOD_INSTALL("before-mod-install"),

    /// Raised after a mod is installed into an instance; the context exposes the mod entry.
    @SerializedName("after-mod-install")
    AFTER_MOD_INSTALL("after-mod-install"),

    /// Raised before launcher settings are read; the context exposes the settings source.
    @SerializedName("before-settings-load")
    BEFORE_SETTINGS_LOAD("before-settings-load"),

    /// Raised after launcher settings are read; the context exposes the loaded values.
    @SerializedName("after-settings-load")
    AFTER_SETTINGS_LOAD("after-settings-load");

    /// Stable kebab-case identifier used in `plugin.json`.
    private final String id;

    /// Creates one hook point with its stable serialized identifier.
    ///
    /// @param id stable JSON identifier
    PluginHookPoint(String id) {
        this.id = id;
    }

    /// Returns the stable kebab-case identifier used in manifests.
    ///
    /// @return stable JSON identifier
    public String getId() {
        return id;
    }

    /// Returns the stable JSON identifier used by HMCL's enum adapter.
    ///
    /// @return stable JSON identifier
    @Override
    public String toString() {
        return id;
    }
}
