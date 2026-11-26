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

import java.util.Arrays;
import java.util.Comparator;

final class BrailleCalibrationGeometry {

  private BrailleCalibrationGeometry() {}

  static int applyStyleMapping(int dot, boolean invertKeyboard180, boolean slateAndStylusMode) {
    int mapped = dot;
    if (invertKeyboard180) {
      mapped = 7 - mapped;
    }
    if (slateAndStylusMode) {
      if (mapped <= 3) {
        mapped += 3;
      } else {
        mapped -= 3;
      }
    }
    return mapped;
  }

  static void assignAllSixDots(
      float[] step1X,
      float[] step1Y,
      float[] step2X,
      float[] step2Y,
      boolean invertKeyboard180,
      boolean slateAndStylusMode,
      float[] outCalDotX,
      float[] outCalDotY) {
    float spanX1 = span(step1X);
    float spanY1 = span(step1Y);
    float spanX2 = span(step2X);
    float spanY2 = span(step2Y);

    float avgY1 = average(step1Y);
    float avgY2 = average(step2Y);

    boolean yAxisSpread = (spanY1 + spanY2) > (spanX1 + spanX2);

    Integer[] order1 = {0, 1, 2};
    Integer[] order2 = {0, 1, 2};

    if (yAxisSpread) {

      boolean step1AscendingY = avgY1 > avgY2;
      sortIndices(order1, step1Y, step1AscendingY);
      sortIndices(order2, step2Y, !step1AscendingY);
    } else {

      boolean ascendingX = slateAndStylusMode || (avgY1 <= avgY2);
      sortIndices(order1, step1X, ascendingX);
      sortIndices(order2, step2X, ascendingX);
    }

    for (int i = 0; i < 3; i++) {
      int slot1 = applyStyleMapping(1 + i, invertKeyboard180, slateAndStylusMode);
      outCalDotX[slot1] = step1X[order1[i]];
      outCalDotY[slot1] = step1Y[order1[i]];

      int slot2 = applyStyleMapping(4 + i, invertKeyboard180, slateAndStylusMode);
      outCalDotX[slot2] = step2X[order2[i]];
      outCalDotY[slot2] = step2Y[order2[i]];
    }
  }

  private static float span(float[] values) {
    float min = Float.MAX_VALUE;
    float max = -Float.MAX_VALUE;
    for (float v : values) {
      if (v < min) min = v;
      if (v > max) max = v;
    }
    return max - min;
  }

  private static float average(float[] values) {
    float sum = 0f;
    for (float v : values) {
      sum += v;
    }
    return sum / values.length;
  }

  private static void sortIndices(
      Integer[] indices, final float[] coords, final boolean ascending) {
    Arrays.sort(
        indices,
        new Comparator<Integer>() {
          @Override
          public int compare(Integer a, Integer b) {
            return ascending
                ? Float.compare(coords[a], coords[b])
                : Float.compare(coords[b], coords[a]);
          }
        });
  }
}
