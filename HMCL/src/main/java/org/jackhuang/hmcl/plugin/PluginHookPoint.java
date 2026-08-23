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
/// These identifiers define the schema-v5 hook contract and are parsed, validated, and exposed through plugin
/// manifests. The launcher does not yet dispatch these hooks, marshal ABI context objects, or apply plugin
/// adjustments to the corresponding operations.
@NotNullByDefault
public enum PluginHookPoint {
    /// Contract point before a file download queue starts; its future context will expose the target list.
    @SerializedName("before-download")
    BEFORE_DOWNLOAD("before-download"),

    /// Contract point after a file download queue finishes; its future context will expose per-file results.
    @SerializedName("after-download")
    AFTER_DOWNLOAD("after-download"),

    /// Contract point before the Minecraft process starts; its future context will expose JVM and game arguments.
    @SerializedName("before-game-launch")
    BEFORE_GAME_LAUNCH("before-game-launch"),

    /// Contract point after the Minecraft process exits; its future context will expose the exit code.
    @SerializedName("after-game-launch")
    AFTER_GAME_LAUNCH("after-game-launch"),

    /// Contract point before an account login attempt; its future context will expose the account and auth mode.
    @SerializedName("before-login")
    BEFORE_LOGIN("before-login"),

    /// Contract point after an account login attempt; its future context will expose the resulting profile.
    @SerializedName("after-login")
    AFTER_LOGIN("after-login"),

    /// Contract point before a new instance is created; its future context will expose the template and name.
    @SerializedName("before-instance-create")
    BEFORE_INSTANCE_CREATE("before-instance-create"),

    /// Contract point after a new instance is created; its future context will expose the instance id.
    @SerializedName("after-instance-create")
    AFTER_INSTANCE_CREATE("after-instance-create"),

    /// Contract point before a mod is installed into an instance; its future context will expose the mod file.
    @SerializedName("before-mod-install")
    BEFORE_MOD_INSTALL("before-mod-install"),

    /// Contract point after a mod is installed into an instance; its future context will expose the mod entry.
    @SerializedName("after-mod-install")
    AFTER_MOD_INSTALL("after-mod-install"),

    /// Contract point before launcher settings are read; its future context will expose the settings source.
    @SerializedName("before-settings-load")
    BEFORE_SETTINGS_LOAD("before-settings-load"),

    /// Contract point after launcher settings are read; its future context will expose the loaded values.
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
