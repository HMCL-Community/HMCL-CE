package org.jackhuang.hmcl.plugin;

import org.jetbrains.annotations.NotNullByDefault;

/// Risk classification of declared plugin permissions used by install-time consent screens.
///
/// The tier drives how loudly the launcher warns before granting a permission: normal capabilities
/// install silently once declared, advanced capabilities require an explicit consent prompt, and
/// dangerous capabilities additionally explain that the plugin can execute system commands, load
/// native code, or modify launcher behavior. Shell access, native code, and launcher patching are
/// governed by this unified permission system instead of being treated as an implicit escape hatch.
@NotNullByDefault
public enum PluginPermissionTier {
    /// Capabilities confined to launcher-provided surfaces.
    NORMAL,

    /// Capabilities that reach the operating system or sensitive account data.
    ADVANCED,

    /// Capabilities that can execute arbitrary code or alter launcher behavior.
    DANGEROUS;

    /// Returns the tier of one declared permission.
    public static PluginPermissionTier tierOf(PluginPermission permission) {
        switch (permission) {
            case LAUNCHER_UI:
            case GAME_LAUNCH:
            case CLIPBOARD:
                return NORMAL;
            case FILESYSTEM:
            case NETWORK:
            case PROCESS:
            case ACCOUNT:
                return ADVANCED;
            case MIXIN:
            case NATIVE_CODE:
            default:
                return DANGEROUS;
        }
    }
}