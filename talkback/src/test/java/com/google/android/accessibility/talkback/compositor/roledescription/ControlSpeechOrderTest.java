package com.google.android.accessibility.talkback.compositor.roledescription;

import static com.google.android.accessibility.talkback.compositor.roledescription.RoleDescriptionExtractor.DESC_ORDER_NAME_ROLE_STATE_POSITION;
import static com.google.android.accessibility.talkback.compositor.roledescription.RoleDescriptionExtractor.DESC_ORDER_NAME_STATE_ROLE_POSITION;
import static com.google.android.accessibility.talkback.compositor.roledescription.RoleDescriptionExtractor.DESC_ORDER_ROLE_NAME_STATE_POSITION;
import static com.google.android.accessibility.talkback.compositor.roledescription.RoleDescriptionExtractor.DESC_ORDER_ROLE_STATE_NAME_POSITION;
import static com.google.android.accessibility.talkback.compositor.roledescription.RoleDescriptionExtractor.DESC_ORDER_STATE_NAME_ROLE_POSITION;
import static com.google.android.accessibility.talkback.compositor.roledescription.RoleDescriptionExtractor.DESC_ORDER_STATE_ROLE_NAME_POSITION;
import static org.junit.Assert.assertEquals;

import android.text.Spanned;
import com.google.android.accessibility.talkback.compositor.CompositorUtils;
import com.google.android.accessibility.talkback.compositor.roledescription.RoleDescriptionExtractor.DescriptionOrder;
import com.google.android.accessibility.utils.SpannableUtils.IdentifierSpan;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class ControlSpeechOrderTest {
  private static final String NAME = "Wi-Fi";
  private static final String ROLE = "Công tắc";
  private static final String STATE = "Bật";

  @Test
  public void descriptionUsesTheConfiguredOrder() {
    assertOrder(DESC_ORDER_NAME_ROLE_STATE_POSITION, false, NAME, ROLE, STATE);
    assertOrder(DESC_ORDER_NAME_STATE_ROLE_POSITION, false, NAME, STATE, ROLE);
    assertOrder(DESC_ORDER_ROLE_NAME_STATE_POSITION, false, ROLE, NAME, STATE);
    assertOrder(DESC_ORDER_ROLE_STATE_NAME_POSITION, true, ROLE, STATE, NAME);
    assertOrder(DESC_ORDER_STATE_NAME_ROLE_POSITION, true, STATE, NAME, ROLE);
    assertOrder(DESC_ORDER_STATE_ROLE_NAME_POSITION, true, STATE, ROLE, NAME);
  }

  @Test
  public void nameFirstDescriptionCanBeSplitAfterTheName() {
    Spanned description =
        (Spanned) RoleDescriptionExtractor.joinInOrder(
            DESC_ORDER_NAME_ROLE_STATE_POSITION, NAME, ROLE, STATE);
    IdentifierSpan[] separators =
        description.getSpans(0, description.length(), IdentifierSpan.class);
    assertEquals(2, separators.length);
    assertEquals(
        NAME.length(),
        Math.min(description.getSpanStart(separators[0]), description.getSpanStart(separators[1])));
  }

  private static void assertOrder(
      @DescriptionOrder int order, boolean stateBeforeName, String... expectedOrder) {
    assertEquals(
        String.join(CompositorUtils.getSeparator(), expectedOrder),
        RoleDescriptionExtractor.joinInOrder(order, NAME, ROLE, STATE).toString());
    assertEquals(stateBeforeName, RoleDescriptionExtractor.isStateBeforeName(order));
  }
}
