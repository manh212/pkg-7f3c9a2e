package com.google.android.accessibility.utils;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ArrayUtilsTest {
  @Test
  public void binarySearchClosestIndex_emptyAndNullReturnMinusOne() {
    assertEquals(-1, ArrayUtils.binarySearchClosestIndex(new float[] {}, 1.0f));
    assertEquals(-1, ArrayUtils.binarySearchClosestIndex(null, 1.0f));
  }

  @Test
  public void binarySearchClosestIndex_exactAndClosest() {
    float[] array = new float[] {0.0f, 10.0f, 20.0f, 30.0f};
    assertEquals(0, ArrayUtils.binarySearchClosestIndex(array, -5.0f));
    assertEquals(1, ArrayUtils.binarySearchClosestIndex(array, 10.0f));
    assertEquals(1, ArrayUtils.binarySearchClosestIndex(array, 12.0f));
    assertEquals(2, ArrayUtils.binarySearchClosestIndex(array, 18.0f));
    assertEquals(3, ArrayUtils.binarySearchClosestIndex(array, 100.0f));
  }
}
