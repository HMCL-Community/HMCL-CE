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
package org.jackhuang.hmcl.util.testsupport;

import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;

/// Provides a real child process whose lifetime is controlled through standard input.
@NotNullByDefault
public final class RestartBarrierParent {
    /// Announces readiness and remains alive until the test closes standard input.
    ///
    /// @param args ignored command-line arguments
    /// @throws IOException if standard input cannot be read
    public static void main(String[] args) throws IOException {
        System.out.println("READY");
        System.out.flush();
        while (System.in.read() >= 0) {
            // Keep the process alive until the controlling test closes the stream.
        }
    }

    /// Prevents construction of the process helper.
    private RestartBarrierParent() {
    }
}
