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

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/// Owns synchronized launch-scoped secret slots outside ordinary plugin Hook data.
@NotNullByDefault
final class GameLaunchSecretStore {
    /// Canonical syntax shared with launch-plan secret references.
    private static final Pattern SECRET_SLOT = Pattern.compile("[a-z][a-z0-9]*(?:-[a-z0-9]+)*");

    /// Stable identity used by uniformly denied accessors without disclosing slot existence.
    private static final String DENIED_ACCESSOR_ID = "hook-callback";

    /// Mutable committed secret values guarded by this instance's monitor.
    private final Map<String, String> secrets = new LinkedHashMap<>();

    /// Creates a store from a copied launch-preparation secret snapshot.
    ///
    /// @param initialSecrets initial slot values
    GameLaunchSecretStore(Map<String, String> initialSecrets) {
        Objects.requireNonNull(initialSecrets, "initialSecrets");
        for (Map.Entry<String, String> entry : initialSecrets.entrySet()) {
            String slot = requireCanonicalSlot(entry.getKey());
            secrets.put(slot, Objects.requireNonNull(entry.getValue(), "Secret value"));
        }
    }

    /// Creates a callback-scoped accessor with an immutable value snapshot.
    ///
    /// @param accountGranted whether the callback holds the `account` permission
    /// @return granted snapshot accessor or uniform denied accessor
    synchronized PluginSecretAccess accessor(boolean accountGranted) {
        if (!accountGranted) {
            return PluginSecretAccess.denied(DENIED_ACCESSOR_ID);
        }
        Map<String, String> snapshot = Map.copyOf(secrets);
        return slot -> resolveFrom(snapshot, slot);
    }

    /// Returns an immutable snapshot of currently available slot names.
    ///
    /// @return immutable slot names
    synchronized @Unmodifiable Set<String> slots() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(secrets.keySet()));
    }

    /// Returns an immutable snapshot of currently committed slot values for final launcher use.
    ///
    /// @return immutable secret value snapshot
    synchronized @Unmodifiable Map<String, String> snapshot() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(secrets));
    }

    /// Creates an isolated mutable copy for transactional candidate validation.
    ///
    /// @return independent secret store copy
    synchronized GameLaunchSecretStore fork() {
        return new GameLaunchSecretStore(secrets);
    }

    /// Replaces committed values with a previously validated immutable snapshot.
    ///
    /// @param validatedSnapshot complete validated secret state
    synchronized void commitValidated(Map<String, String> validatedSnapshot) {
        secrets.clear();
        secrets.putAll(validatedSnapshot);
    }

    /// Rejects ordinary callback data containing any secret visible to that callback.
    ///
    /// @param pluginId callback plugin ID
    /// @param data candidate ordinary Hook data
    /// @param accountGranted whether the callback could resolve stored secrets
    void validateOrdinaryData(String pluginId, PluginDataObject data, boolean accountGranted) {
        Objects.requireNonNull(pluginId, "pluginId");
        Objects.requireNonNull(data, "data");
        if (!accountGranted) {
            return;
        }
        Map<String, String> visibleSecrets;
        synchronized (this) {
            visibleSecrets = Map.copyOf(secrets);
        }
        scanObject(pluginId, data, visibleSecrets.values(), "$");
    }

    /// Rejects a cancellation message containing any secret visible to that callback.
    ///
    /// @param pluginId callback plugin ID
    /// @param message candidate user-facing cancellation message
    /// @param accountGranted whether the callback could resolve stored secrets
    void validateCancellationMessage(String pluginId, String message, boolean accountGranted) {
        Objects.requireNonNull(pluginId, "pluginId");
        Objects.requireNonNull(message, "message");
        if (!accountGranted) {
            return;
        }
        Map<String, String> visibleSecrets;
        synchronized (this) {
            visibleSecrets = Map.copyOf(secrets);
        }
        for (String secret : visibleSecrets.values()) {
            if (!secret.isEmpty() && message.contains(secret)) {
                throw invalidResult(pluginId,
                        new IllegalArgumentException("Cancellation message contains a protected value"));
            }
        }
    }

    /// Applies authorized protected updates whose slots are all referenced by the candidate plan.
    ///
    /// @param pluginId callback plugin ID
    /// @param updates protected slot updates
    /// @param accountGranted whether the callback holds the `account` permission
    /// @param referencedSlots slots referenced by the candidate process plan
    synchronized void applyProtectedUpdates(
            String pluginId,
            Map<String, String> updates,
            boolean accountGranted,
            Set<String> referencedSlots
    ) {
        Objects.requireNonNull(pluginId, "pluginId");
        Objects.requireNonNull(updates, "updates");
        Objects.requireNonNull(referencedSlots, "referencedSlots");
        if (updates.isEmpty()) {
            return;
        }
        if (!accountGranted) {
            throw new PluginPermissionException(pluginId, PluginPermission.ACCOUNT);
        }

        Map<String, String> validated = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : updates.entrySet()) {
            String slot;
            try {
                slot = requireCanonicalSlot(entry.getKey());
            } catch (RuntimeException exception) {
                throw invalidResult(pluginId, exception);
            }
            if (!referencedSlots.contains(slot)) {
                throw invalidResult(pluginId,
                        new IllegalArgumentException("Protected secret update is not referenced by the candidate plan"));
            }
            validated.put(slot, Objects.requireNonNull(entry.getValue(), "Protected secret value"));
        }
        secrets.putAll(validated);
    }

    /// Applies authorized protected updates that are treated as referenced by the caller.
    ///
    /// @param pluginId callback plugin ID
    /// @param updates protected slot updates
    /// @param accountGranted whether the callback holds the `account` permission
    synchronized void applyProtectedUpdates(
            String pluginId,
            Map<String, String> updates,
            boolean accountGranted
    ) {
        applyProtectedUpdates(pluginId, updates, accountGranted, updates.keySet());
    }

    /// Resolves one committed slot for final process execution or script rendering.
    ///
    /// @param slot canonical slot name
    /// @return copied secret value
    synchronized String resolve(String slot) {
        return resolveFrom(secrets, slot);
    }

    /// Resolves one value without revealing any other slot names.
    ///
    /// @param source secret snapshot
    /// @param slot requested slot
    /// @return copied secret value
    private static String resolveFrom(Map<String, String> source, String slot) {
        Objects.requireNonNull(slot, "slot");
        @Nullable String value = source.get(slot);
        if (value == null) {
            throw new IllegalArgumentException("Unknown launch secret slot");
        }
        return new String(value);
    }

    /// Recursively scans one object for visible secret disclosure.
    ///
    /// @param pluginId callback plugin ID
    /// @param object candidate object
    /// @param visibleSecrets secrets visible to the callback
    /// @param path redacted data path
    private static void scanObject(
            String pluginId,
            PluginDataObject object,
            Iterable<String> visibleSecrets,
            String path
    ) {
        for (Map.Entry<String, PluginDataValue> entry : object.values().entrySet()) {
            scanValue(pluginId, PluginDataValue.string(entry.getKey()), visibleSecrets, path + ".<key>");
            scanValue(pluginId, entry.getValue(), visibleSecrets, path + "." + entry.getKey());
        }
    }

    /// Recursively scans one value for visible secret disclosure.
    ///
    /// @param pluginId callback plugin ID
    /// @param value candidate value
    /// @param visibleSecrets secrets visible to the callback
    /// @param path redacted data path
    private static void scanValue(
            String pluginId,
            PluginDataValue value,
            Iterable<String> visibleSecrets,
            String path
    ) {
        if (value instanceof PluginDataValue.StringValue stringValue) {
            for (String secret : visibleSecrets) {
                if (!secret.isEmpty() && stringValue.value().contains(secret)) {
                    throw invalidResult(pluginId,
                            new IllegalArgumentException("Ordinary Hook data contains a protected value at " + path));
                }
            }
        } else if (value instanceof PluginDataValue.ArrayValue arrayValue) {
            List<PluginDataValue> children = arrayValue.values();
            for (int index = 0; index < children.size(); index++) {
                scanValue(pluginId, children.get(index), visibleSecrets, path + "[" + index + "]");
            }
        } else if (value instanceof PluginDataValue.ObjectValue objectValue) {
            scanObject(pluginId, objectValue.value(), visibleSecrets, path);
        }
    }

    /// Validates canonical slot syntax without exposing current store contents.
    ///
    /// @param slot candidate slot
    /// @return validated slot
    private static String requireCanonicalSlot(String slot) {
        Objects.requireNonNull(slot, "Secret slot");
        if (!SECRET_SLOT.matcher(slot).matches()) {
            throw new IllegalArgumentException("Invalid launch secret slot");
        }
        return slot;
    }

    /// Wraps a safe validation cause in the categorized before-launch failure type.
    ///
    /// @param pluginId callback plugin ID
    /// @param cause redacted validation cause
    /// @return categorized invalid-result failure
    private static PluginHookDispatchException invalidResult(String pluginId, RuntimeException cause) {
        return new PluginHookDispatchException(
                PluginHookPoint.BEFORE_GAME_LAUNCH,
                pluginId,
                PluginHookDispatchException.Category.INVALID_RESULT,
                cause
        );
    }
}
