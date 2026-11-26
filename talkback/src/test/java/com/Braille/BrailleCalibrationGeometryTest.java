/*
 * Copyright 2026 SandFly contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.Braille;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class BrailleCalibrationGeometryTest {

  private static int resolveNearestDot(
      float x,
      float y,
      float[] calX,
      float[] calY,
      boolean invert180,
      boolean slateAndStylus) {
    int bestDot = 1;
    float minDistSq = Float.MAX_VALUE;
    for (int d = 1; d <= 6; d++) {
      float dx = x - calX[d];
      float dy = y - calY[d];
      float distSq = dx * dx + dy * dy;
      if (distSq < minDistSq) {
        minDistSq = distSq;
        bestDot = d;
      }
    }
    return BrailleCalibrationGeometry.applyStyleMapping(
        bestDot, invert180, slateAndStylus);
  }

  @Test
  public void tabletopStandardOrientation_leftIndexIsDot1_leftRingIsDot3_rightUnchanged() {

    float[] step1X = {300f, 290f, 305f};
    float[] step1Y = {100f, 200f, 300f};
    float[] step2X = {305f, 290f, 300f};
    float[] step2Y = {500f, 600f, 700f};

    float[] calX = new float[7];
    float[] calY = new float[7];
    BrailleCalibrationGeometry.assignAllSixDots(
        step1X, step1Y, step2X, step2Y, false, false, calX, calY);

    assertEquals(1, resolveNearestDot(305f, 300f, calX, calY, false, false));
    assertEquals(2, resolveNearestDot(290f, 200f, calX, calY, false, false));
    assertEquals(3, resolveNearestDot(300f, 100f, calX, calY, false, false));
    assertEquals(4, resolveNearestDot(305f, 500f, calX, calY, false, false));
    assertEquals(5, resolveNearestDot(290f, 600f, calX, calY, false, false));
    assertEquals(6, resolveNearestDot(300f, 700f, calX, calY, false, false));
  }

  @Test
  public void tabletopRotated180_mapsBothHandsFromCenterOutward() {

    float[] step1X = {300f, 295f, 305f};
    float[] step1Y = {700f, 600f, 500f};
    float[] step2X = {305f, 295f, 300f};
    float[] step2Y = {100f, 200f, 300f};

    float[] calX = new float[7];
    float[] calY = new float[7];
    BrailleCalibrationGeometry.assignAllSixDots(
        step1X, step1Y, step2X, step2Y, false, false, calX, calY);

    assertEquals(1, resolveNearestDot(305f, 500f, calX, calY, false, false));
    assertEquals(2, resolveNearestDot(295f, 600f, calX, calY, false, false));
    assertEquals(3, resolveNearestDot(300f, 700f, calX, calY, false, false));
    assertEquals(4, resolveNearestDot(300f, 300f, calX, calY, false, false));
    assertEquals(5, resolveNearestDot(295f, 200f, calX, calY, false, false));
    assertEquals(6, resolveNearestDot(305f, 100f, calX, calY, false, false));
  }

  @Test
  public void screenAwayStandardOrientation_mapsTopToBottomAlongX() {

    float[] step1X = {100f, 300f, 500f};
    float[] step1Y = {200f, 210f, 195f};
    float[] step2X = {100f, 300f, 500f};
    float[] step2Y = {600f, 610f, 595f};

    float[] calX = new float[7];
    float[] calY = new float[7];
    BrailleCalibrationGeometry.assignAllSixDots(
        step1X, step1Y, step2X, step2Y, false, false, calX, calY);

    assertEquals(1, resolveNearestDot(100f, 200f, calX, calY, false, false));
    assertEquals(2, resolveNearestDot(300f, 210f, calX, calY, false, false));
    assertEquals(3, resolveNearestDot(500f, 195f, calX, calY, false, false));
    assertEquals(4, resolveNearestDot(100f, 600f, calX, calY, false, false));
    assertEquals(5, resolveNearestDot(300f, 610f, calX, calY, false, false));
    assertEquals(6, resolveNearestDot(500f, 595f, calX, calY, false, false));
  }

  @Test
  public void tabletopWithInvert180AndSlateActive_stillResolvesExactCalibratedDots() {
    float[] step1X = {300f, 290f, 305f};
    float[] step1Y = {100f, 200f, 300f};
    float[] step2X = {305f, 290f, 300f};
    float[] step2Y = {500f, 600f, 700f};

    for (boolean invert : new boolean[] {false, true}) {
      for (boolean slate : new boolean[] {false, true}) {
        float[] calX = new float[7];
        float[] calY = new float[7];
        BrailleCalibrationGeometry.assignAllSixDots(
            step1X, step1Y, step2X, step2Y, invert, slate, calX, calY);

        assertEquals(1, resolveNearestDot(305f, 300f, calX, calY, invert, slate));
        assertEquals(2, resolveNearestDot(290f, 200f, calX, calY, invert, slate));
        assertEquals(3, resolveNearestDot(300f, 100f, calX, calY, invert, slate));
        assertEquals(4, resolveNearestDot(305f, 500f, calX, calY, invert, slate));
        assertEquals(5, resolveNearestDot(290f, 600f, calX, calY, invert, slate));
        assertEquals(6, resolveNearestDot(300f, 700f, calX, calY, invert, slate));
      }
    }
  }
}
