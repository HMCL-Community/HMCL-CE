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
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies the implementation-neutral public value contract used by plugin Hook endpoints.
@NotNullByDefault
public final class PluginHookContractTest {
    /// Copies nested collections and provides copy-on-write object replacement.
    @Test
    public void dataObjectsAreDeeplyImmutableAndCopyOnWrite() {
        List<PluginDataValue> arguments = new ArrayList<>();
        arguments.add(PluginDataValue.string("-Xmx2G"));
        Map<String, PluginDataValue> source = new LinkedHashMap<>();
        source.put("mode", PluginDataValue.string("structured-java"));
        source.put("arguments", PluginDataValue.array(arguments));

        PluginDataObject original = PluginDataObject.of(source);
        arguments.add(PluginDataValue.string("-Dlate=true"));
        source.put("mode", PluginDataValue.string("raw"));
        PluginDataObject changed = original.with("mode", PluginDataValue.string("raw"));

        assertEquals("structured-java", original.requireString("mode"));
        assertEquals(List.of(PluginDataValue.string("-Xmx2G")), original.requireArray("arguments"));
        assertEquals("raw", changed.requireString("mode"));
        assertEquals("structured-java", original.requireString("mode"));
        assertThrows(UnsupportedOperationException.class,
                () -> changed.values().put("bad", PluginDataValue.nullValue()));
        assertThrows(UnsupportedOperationException.class,
                () -> original.requireArray("arguments").add(PluginDataValue.nullValue()));
        assertEquals(original, original.without("missing"));
        assertFalse(changed.without("mode").values().containsKey("mode"));
    }

    /// Rejects null object keys, values, and recursively nested array values.
    @Test
    public void dataValuesRejectNullStructure() {
        Map<@Nullable String, PluginDataValue> nullKey = new LinkedHashMap<>();
        nullKey.put(null, PluginDataValue.nullValue());
        Map<String, @Nullable PluginDataValue> nullValue = new LinkedHashMap<>();
        nullValue.put("bad", null);
        List<@Nullable PluginDataValue> nestedNull = new ArrayList<>();
        nestedNull.add(null);

        assertThrows(NullPointerException.class, () -> PluginDataObject.of(nullKey));
        assertThrows(NullPointerException.class, () -> PluginDataObject.of(nullValue));
        assertThrows(NullPointerException.class, () -> PluginDataValue.array(nestedNull));
        assertThrows(NullPointerException.class, () -> PluginDataValue.number(null));
        assertThrows(NullPointerException.class, () -> PluginDataValue.string(null));
        assertThrows(NullPointerException.class, () -> PluginDataValue.object(null));
    }

    /// Exposes every JSON-compatible scalar and composite kind without implementation objects.
    @Test
    public void dataValueFactoriesPreserveJsonKinds() {
        assertSame(PluginDataValue.nullValue(), PluginDataValue.nullValue());
        assertTrue(assertInstanceOf(PluginDataValue.BooleanValue.class,
                PluginDataValue.bool(true)).value());
        assertEquals(new BigDecimal("1.25"), assertInstanceOf(PluginDataValue.NumberValue.class,
                PluginDataValue.number(new BigDecimal("1.25"))).value());
        assertEquals("value", assertInstanceOf(PluginDataValue.StringValue.class,
                PluginDataValue.string("value")).value());
        assertEquals(List.of(PluginDataValue.bool(false)), assertInstanceOf(PluginDataValue.ArrayValue.class,
                PluginDataValue.array(List.of(PluginDataValue.bool(false)))).values());
        assertEquals(PluginDataObject.empty(), assertInstanceOf(PluginDataValue.ObjectValue.class,
                PluginDataValue.object(PluginDataObject.empty())).value());
    }

    /// Reads required object fields by type and rejects missing or mismatched values.
    @Test
    public void dataObjectsRequireExpectedKinds() {
        PluginDataObject nested = PluginDataObject.of(Map.of("name", PluginDataValue.string("nested")));
        PluginDataObject data = PluginDataObject.of(Map.of(
                "enabled", PluginDataValue.bool(true),
                "count", PluginDataValue.number(BigDecimal.TEN),
                "name", PluginDataValue.string("hook"),
                "nested", PluginDataValue.object(nested),
                "items", PluginDataValue.array(List.of(PluginDataValue.nullValue()))
        ));

        assertTrue(data.requireBoolean("enabled"));
        assertEquals(BigDecimal.TEN, data.requireNumber("count"));
        assertEquals("hook", data.requireString("name"));
        assertEquals(nested, data.requireObject("nested"));
        assertEquals(List.of(PluginDataValue.nullValue()), data.requireArray("items"));
        assertNull(data.get("missing"));
        assertThrows(IllegalArgumentException.class, () -> data.requireString("missing"));
        assertThrows(IllegalArgumentException.class, () -> data.requireString("enabled"));
    }

    /// Carries stable dispatch metadata, immutable data, and a scoped secret accessor.
    @Test
    public void hookEventPreservesMetadata() {
        Instant occurredAt = Instant.parse("2026-08-24T00:00:00Z");
        PluginDataObject data = PluginDataObject.of(Map.of("name", PluginDataValue.string("launch")));
        PluginSecretAccess secrets = slot -> "secret:" + slot;

        PluginHookEvent event = new PluginHookEvent(
                PluginHookEvent.CURRENT_CONTRACT_VERSION,
                "dispatch-1",
                PluginHookPoint.BEFORE_GAME_LAUNCH,
                occurredAt,
                data,
                secrets
        );

        assertEquals(1, event.contractVersion());
        assertEquals("dispatch-1", event.dispatchId());
        assertEquals(PluginHookPoint.BEFORE_GAME_LAUNCH, event.point());
        assertEquals(occurredAt, event.occurredAt());
        assertSame(data, event.data());
        assertSame(secrets, event.secrets());
        assertEquals("secret:access-token", event.secrets().resolve("access-token"));
        assertThrows(IllegalArgumentException.class, () -> new PluginHookEvent(
                2, "dispatch-1", PluginHookPoint.BEFORE_GAME_LAUNCH, occurredAt, data, secrets));
        assertThrows(IllegalArgumentException.class, () -> new PluginHookEvent(
                1, " ", PluginHookPoint.BEFORE_GAME_LAUNCH, occurredAt, data, secrets));
    }

    /// Represents unchanged, complete replacement, and cancellation as closed result states.
    @Test
    public void hookResultsEnforceActionSpecificState() {
        PluginDataObject replacement = PluginDataObject.of(Map.of("name", PluginDataValue.string("changed")));
        Map<String, String> protectedSecrets = new LinkedHashMap<>();
        protectedSecrets.put("access-token", "hidden-value");

        PluginHookResult unchanged = PluginHookResult.unchanged();
        PluginHookResult replace = PluginHookResult.replace(replacement, protectedSecrets);
        PluginHookResult cancel = PluginHookResult.cancel("policy-denied", "Launch denied");
        protectedSecrets.put("late-secret", "late-value");

        assertEquals(PluginHookResult.Action.UNCHANGED, unchanged.action());
        assertNull(unchanged.data());
        assertTrue(unchanged.protectedSecrets().isEmpty());
        assertEquals(PluginHookResult.Action.REPLACE, replace.action());
        assertSame(replacement, replace.data());
        assertEquals(Map.of("access-token", "hidden-value"), replace.protectedSecrets());
        assertThrows(UnsupportedOperationException.class,
                () -> replace.protectedSecrets().put("bad", "value"));
        assertEquals(PluginHookResult.Action.CANCEL, cancel.action());
        assertEquals("policy-denied", cancel.reasonCode());
        assertEquals("Launch denied", cancel.message());
        assertFalse(replace.toString().contains("hidden-value"));
        assertFalse(replace.toString().contains("late-value"));
        assertFalse(cancel.toString().contains("Launch denied"));
        assertThrows(IllegalArgumentException.class, () -> PluginHookResult.cancel("Not Kebab", "Denied"));
        assertThrows(IllegalArgumentException.class, () -> PluginHookResult.cancel("", "Denied"));
        assertThrows(IllegalArgumentException.class, () -> PluginHookResult.cancel("denied", " "));
    }

    /// Denies secret resolution through the standard account-permission exception without exposing slot state.
    @Test
    public void deniedSecretAccessUsesAccountPermission() {
        PluginSecretAccess denied = PluginSecretAccess.denied("dev.test.denied");

        PluginPermissionException failure = assertThrows(PluginPermissionException.class,
                () -> denied.resolve("unknown-slot"));

        assertEquals("dev.test.denied", failure.getPluginId());
        assertEquals(PluginPermission.ACCOUNT, failure.getPermission());
        assertFalse(failure.getMessage().contains("unknown-slot"));
    }

    /// Preserves source and binary behavior for plugins that do not implement Hook callbacks.
    @Test
    public void defaultHookCallbackPreservesPayload() {
        Plugin plugin = new NoOpPlugin();

        PluginHookResult result = plugin.onHook(event(PluginHookPoint.BEFORE_GAME_LAUNCH));

        assertEquals(PluginHookResult.Action.UNCHANGED, result.action());
    }

    /// Keeps the public Hook surface independent from Gson, JavaFX, and launcher implementation types.
    @Test
    public void publicHookSurfaceUsesOnlyNeutralTypes() {
        Set<Class<?>> apiTypes = Set.of(
                PluginDataValue.class,
                PluginDataObject.class,
                PluginHookEvent.class,
                PluginHookResult.class,
                PluginSecretAccess.class
        );

        for (Class<?> apiType : apiTypes) {
            for (Method method : apiType.getDeclaredMethods()) {
                if (Modifier.isPublic(method.getModifiers())) {
                    assertNeutral(method.getReturnType());
                    for (Class<?> parameterType : method.getParameterTypes()) {
                        assertNeutral(parameterType);
                    }
                }
            }
            for (Constructor<?> constructor : apiType.getDeclaredConstructors()) {
                if (Modifier.isPublic(constructor.getModifiers())) {
                    for (Class<?> parameterType : constructor.getParameterTypes()) {
                        assertNeutral(parameterType);
                    }
                }
            }
            for (Field field : apiType.getDeclaredFields()) {
                if (Modifier.isPublic(field.getModifiers())) {
                    assertNeutral(field.getType());
                }
            }
        }
    }

    /// Creates one immutable event for default-callback tests.
    ///
    /// @param point Hook point under test
    /// @return event with empty data and denied secret access
    private static PluginHookEvent event(PluginHookPoint point) {
        return new PluginHookEvent(
                PluginHookEvent.CURRENT_CONTRACT_VERSION,
                "dispatch-default",
                point,
                Instant.parse("2026-08-24T00:00:00Z"),
                PluginDataObject.empty(),
                PluginSecretAccess.denied("dev.test.no-op")
        );
    }

    /// Rejects implementation-specific public signature types.
    ///
    /// @param type public signature type to inspect
    private static void assertNeutral(Class<?> type) {
        String name = type.getName();
        assertFalse(name.startsWith("com.google.gson"), name);
        assertFalse(name.startsWith("javafx."), name);
        assertFalse(name.equals("org.jackhuang.hmcl.game.LaunchOptions"), name);
        assertFalse(name.equals("org.jackhuang.hmcl.util.platform.ManagedProcess"), name);
        assertFalse(name.contains("ClassLoader"), name);
    }

    /// Minimal existing-style plugin that relies on the default Hook callback.
    @NotNullByDefault
    private static final class NoOpPlugin implements Plugin {
        /// Accepts the plugin context without registering services.
        ///
        /// @param context plugin context
        @Override
        public void onLoad(PluginContext context) {
        }

        /// Performs no activation work.
        @Override
        public void onEnable() {
        }

        /// Performs no deactivation work.
        @Override
        public void onDisable() {
        }

        /// Is unused because this fixture is not package-loaded.
        ///
        /// @return never returns
        @Override
        public PluginManifest getManifest() {
            throw new UnsupportedOperationException("Test fixture has no manifest");
        }
    }
}
