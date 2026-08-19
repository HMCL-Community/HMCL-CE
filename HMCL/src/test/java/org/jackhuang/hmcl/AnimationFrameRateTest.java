/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2013-2026 huangyuhui <huanghongxun2008@126.com> and contributors
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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class AnimationFrameRateTest {
    @Test
    public void detectedRefreshRateHasSixtyFrameFloor() {
        assertEquals(60, Launcher.selectDetectedAnimationFrameRate(32));
        assertEquals(60, Launcher.selectDetectedAnimationFrameRate(59));
        assertEquals(60, Launcher.selectDetectedAnimationFrameRate(60));
    }

    @Test
    public void highRefreshRateIsPreserved() {
        assertEquals(90, Launcher.selectDetectedAnimationFrameRate(90));
        assertEquals(144, Launcher.selectDetectedAnimationFrameRate(144));
    }

    @Test
    public void invalidRefreshRateFallsBackToSixtyFrames() {
        assertEquals(60, Launcher.selectDetectedAnimationFrameRate(0));
        assertEquals(60, Launcher.selectDetectedAnimationFrameRate(-1));
    }
}
