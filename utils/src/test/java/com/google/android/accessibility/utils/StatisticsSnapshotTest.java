package com.google.android.accessibility.utils;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class StatisticsSnapshotTest {
  @Test
  public void maximumRetainsOldOutliersWhilePercentilesUseRecentSamples() {
    Statistics statistics = new Statistics();
    statistics.increment(900);
    for (int i = 0; i < 300; i++) statistics.increment(20);
    statistics.incrementNumMissing();
    Statistics snapshot = statistics.snapshot();
    statistics.clear();
    assertEquals(301, snapshot.getCount());
    assertEquals(900, snapshot.getMaximum());
    assertEquals(20, snapshot.getPercentile(99));
    assertEquals(1, snapshot.getNumMissing());
    assertEquals(0, statistics.getMaximum());
    assertEquals(0, statistics.getCount());
  }

  @Test
  public void getPercentileRejectsOutOfRangeRanks() {
    Statistics statistics = new Statistics();
    for (int i = 1; i <= 10; i++) statistics.increment(i);
    assertEquals(-1, statistics.getPercentile(0));
    assertEquals(-1, statistics.getPercentile(101));
    assertEquals(-1, statistics.getPercentile(-5));
    assertEquals(1, statistics.getPercentile(1));
    assertEquals(10, statistics.getPercentile(100));
  }
}
