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
package org.jackhuang.hmcl.plugin.store;

import org.jetbrains.annotations.NotNullByDefault;

import java.util.concurrent.Callable;
import java.util.concurrent.Semaphore;

/// Limits process-wide plugin source network loads without owning any page or aggregator executor lifecycle.
@NotNullByDefault
public final class PluginSourceLoadExecutor {
    /// Maximum number of source network and manifest loads active process-wide.
    public static final int MAX_CONCURRENCY = 4;

    /// Shared permits spanning aggregate, preview, and manually tested source loads.
    private static final Semaphore PERMITS = new Semaphore(MAX_CONCURRENCY);

    /// Runs one source load while holding one process-wide permit.
    ///
    /// @param task source network and manifest load
    /// @param <T> successful load result type
    /// @return task result
    /// @throws Exception if the source load fails or is interrupted
    public static <T> T call(Callable<T> task) throws Exception {
        PERMITS.acquire();
        try {
            return task.call();
        } finally {
            PERMITS.release();
        }
    }

    /// Prevents construction of the shared source-load limiter.
    private PluginSourceLoadExecutor() {
    }
}
