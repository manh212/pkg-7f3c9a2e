package com.google.android.accessibility.talkback.actor;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;
import com.google.android.accessibility.talkback.Feedback.TalkBackUI;
import com.google.android.accessibility.utils.widget.SimpleOverlay;
import java.util.EnumMap;
import java.util.Map;

/**
 * Shows the TalkBack UI overlays: the on-screen message shown while the user moves through the
 * selector menu or changes a setting with a gesture or keyboard shortcut, so a sighted onlooker
 * can follow what TalkBack is announcing. Each overlay type has its own banner at the bottom of
 * the screen; the banner never takes focus or touch.
 */
public class TalkBackUIActor {
  public enum Type {
    SELECTOR_MENU_ITEM_OVERLAY_SINGLE_FINGER,
    SELECTOR_MENU_ITEM_OVERLAY_MULTI_FINGER,
    SELECTOR_ITEM_ACTION_OVERLAY,
    GESTURE_OR_KEYBOARD_ACTION_OVERLAY,
  }

  private static final long MESSAGE_DURATION_MS = 2500;

  private final Handler handler = new Handler(Looper.getMainLooper());
  private final Context context;
  private final Map<Type, SimpleOverlay> overlays = new EnumMap<>(Type.class);
  private final Map<Type, TextView> messageViews = new EnumMap<>(Type.class);
  private final Map<Type, Boolean> supported = new EnumMap<>(Type.class);

  public TalkBackUIActor(Context context) {
    this.context = context;
  }

  public boolean showQuickMenu(TalkBackUI talkBackUI) {
    Type type = talkBackUI.type();
    if (!isSupported(type)) {
      return false;
    }
    TextView messageView = getMessageView(type);
    CharSequence message = talkBackUI.message();
    messageView.setText(message == null ? "" : message);
    handler.removeCallbacksAndMessages(type);
    SimpleOverlay overlay = overlays.get(type);
    overlay.show();
    if (!overlay.isVisible()) {
      return false;
    }
    handler.postAtTime(() -> hide(type), type, SystemClock.uptimeMillis() + MESSAGE_DURATION_MS);
    return true;
  }

  public boolean hide(Type type) {
    handler.removeCallbacksAndMessages(type);
    SimpleOverlay overlay = overlays.get(type);
    if (overlay != null) {
      overlay.hide();
    }
    return true;
  }

  public boolean setSupported(Type type, boolean supported) {
    this.supported.put(type, supported);
    if (!supported) {
      hide(type);
    }
    return true;
  }

  public boolean isShowing(Type type) {
    SimpleOverlay overlay = overlays.get(type);
    return overlay != null && overlay.isVisible();
  }

  public boolean onConfigurationChanged(Configuration configuration) {
    for (SimpleOverlay overlay : overlays.values()) {
      overlay.updateViewLayout();
    }
    return true;
  }

  private boolean isSupported(Type type) {
    Boolean value = supported.get(type);
    return value == null || value;
  }

  private TextView getMessageView(Type type) {
    TextView messageView = messageViews.get(type);
    if (messageView != null) {
      return messageView;
    }
    SimpleOverlay overlay = new SimpleOverlay(context);
    WindowManager.LayoutParams params = overlay.getParams();
    params.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
    params.width = WindowManager.LayoutParams.MATCH_PARENT;
    params.height = WindowManager.LayoutParams.MATCH_PARENT;
    overlay.setParams(params);
    messageView = new TextView(context);
    messageView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    messageView.setTextColor(Color.WHITE);
    messageView.setBackgroundColor(Color.argb(0xCC, 0x00, 0x00, 0x00));
    messageView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
    int padding =
        (int)
            TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 16, context.getResources().getDisplayMetrics());
    messageView.setPadding(padding, padding, padding, padding);
    messageView.setGravity(Gravity.CENTER_HORIZONTAL);
    messageView.setLayoutParams(
        new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM));
    overlay.setContentView(messageView);
    overlays.put(type, overlay);
    messageViews.put(type, messageView);
    return messageView;
  }
}
