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
package org.jackhuang.hmcl.plugin.mixin.bootstrap;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.service.IGlobalPropertyService;
import org.spongepowered.asm.service.IPropertyKey;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/// Stores SpongePowered Mixin global properties for the HMCL-hosted runtime.
@NotNullByDefault
public final class HmclGlobalPropertyService implements IGlobalPropertyService {
    /// Process-wide property values shared by the Mixin environment and transformer.
    private static final Map<String, @Nullable Object> PROPERTIES = Collections.synchronizedMap(new HashMap<>());

    /// Creates a global property service discovered through `ServiceLoader`.
    public HmclGlobalPropertyService() {
    }

    /// Resolves a textual property name to an HMCL property key.
    ///
    /// @param name property name
    /// @return resolved property key
    @Override
    public IPropertyKey resolveKey(String name) {
        return new PropertyKey(name);
    }

    /// Returns the property value, or `null` when no value is registered.
    ///
    /// @param key property key
    /// @param <T> expected value type
    /// @return stored value or `null`
    @Override
    @SuppressWarnings("unchecked")
    public <T> @Nullable T getProperty(IPropertyKey key) {
        return (T) PROPERTIES.get(key.toString());
    }

    /// Stores a property value, removing the property when the value is `null`.
    ///
    /// @param key property key
    /// @param value replacement value or `null`
    @Override
    public void setProperty(IPropertyKey key, @Nullable Object value) {
        if (value == null) {
            PROPERTIES.remove(key.toString());
        } else {
            PROPERTIES.put(key.toString(), value);
        }
    }

    /// Returns the property value, falling back to the supplied default.
    ///
    /// @param key property key
    /// @param defaultValue fallback value, which may be `null`
    /// @param <T> expected value type
    /// @return stored value or the fallback value
    @Override
    @SuppressWarnings("unchecked")
    public <T> @Nullable T getProperty(IPropertyKey key, @Nullable T defaultValue) {
        @Nullable Object value = PROPERTIES.get(key.toString());
        return value == null ? defaultValue : (T) value;
    }

    /// Returns the string representation of a property value.
    ///
    /// @param key property key
    /// @param defaultValue fallback string
    /// @return stored value converted to a string, or the fallback string
    @Override
    public String getPropertyString(IPropertyKey key, String defaultValue) {
        @Nullable Object value = PROPERTIES.get(key.toString());
        return value == null ? defaultValue : value.toString();
    }

    /// Text-backed key used by the global Mixin property map.
    @NotNullByDefault
    private static final class PropertyKey implements IPropertyKey {
        /// Property name represented by this key.
        private final String name;

        /// Creates a property key for the supplied name.
        ///
        /// @param name property name
        private PropertyKey(String name) {
            this.name = name;
        }

        /// Returns the textual property name.
        ///
        /// @return property name
        @Override
        public String toString() {
            return name;
        }
    }
}
