package com.google.android.accessibility.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class JsonUtilsTest {
  @Test
  public void getters_returnDefaultsOnWrongType() throws JSONException {
    JSONObject json = new JSONObject("{\"text\": 5, \"count\": \"x\", \"items\": {}, \"nested\": []}");
    assertNull(JsonUtils.getString(json, "text"));
    assertEquals(-1, JsonUtils.getInt(json, "count"));
    assertNull(JsonUtils.getJsonArray(json, "items"));
    assertNull(JsonUtils.getJsonObject(json, "nested"));
  }

  @Test
  public void getters_returnValuesOnRightType() throws JSONException {
    JSONObject json =
        new JSONObject("{\"text\": \"hello\", \"count\": 42, \"items\": [1], \"nested\": {}}");
    assertEquals("hello", JsonUtils.getString(json, "text"));
    assertEquals(42, JsonUtils.getInt(json, "count"));
    assertEquals(1, JsonUtils.getJsonArray(json, "items").length());
    assertEquals(0, JsonUtils.getJsonObject(json, "nested").length());
  }

  @Test
  public void getters_returnDefaultsOnMissingKey() throws JSONException {
    JSONObject json = new JSONObject("{}");
    assertNull(JsonUtils.getString(json, "missing"));
    assertEquals(-1, JsonUtils.getInt(json, "missing"));
    assertNull(JsonUtils.getJsonArray(json, "missing"));
    assertNull(JsonUtils.getJsonObject(json, "missing"));
  }
}
