package com.google.android.accessibility.utils.output;

import android.content.Intent;
import android.content.IntentFilter;
import com.google.android.accessibility.utils.broadcast.SameThreadBroadcastReceiver;
import java.util.function.Consumer;

/** {@link SameThreadBroadcastReceiver} for detecting media mount and unmount events. */
final class MediaMountStateMonitor extends SameThreadBroadcastReceiver {
  private final IntentFilter mediaIntentFilter;
  private final Consumer<String> onMediaStateChanged;

  MediaMountStateMonitor(Consumer<String> onMediaStateChanged) {
    this.onMediaStateChanged = onMediaStateChanged;
    mediaIntentFilter = new IntentFilter();
    mediaIntentFilter.addAction(Intent.ACTION_MEDIA_MOUNTED);
    mediaIntentFilter.addAction(Intent.ACTION_MEDIA_UNMOUNTED);
    mediaIntentFilter.addDataScheme("file");
  }

  IntentFilter getFilter() {
    return mediaIntentFilter;
  }

  @Override
  public void onReceiveIntent(Intent intent) {
    onMediaStateChanged.accept(intent.getAction());
  }
}
