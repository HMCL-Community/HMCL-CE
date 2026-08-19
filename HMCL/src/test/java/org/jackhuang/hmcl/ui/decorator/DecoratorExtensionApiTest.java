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
package org.jackhuang.hmcl.ui.decorator;

import javafx.beans.property.ReadOnlyBooleanProperty;
import org.jackhuang.hmcl.ui.construct.Navigator;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// Locks the public window-navigation surface consumed by launcher UI extensions.
@NotNullByDefault
public final class DecoratorExtensionApiTest {
    /// Exposes navigation state without requiring extensions to reflect into the retained window implementation.
    ///
    /// @throws ReflectiveOperationException when a required public API method is missing
    @Test
    public void exposesReadOnlyNavigationState() throws ReflectiveOperationException {
        assertEquals(Navigator.class, Decorator.class.getMethod("getNavigator").getReturnType());
        assertEquals(
                ReadOnlyBooleanProperty.class,
                Decorator.class.getMethod("showCloseAsHomeProperty").getReturnType());
    }
}
