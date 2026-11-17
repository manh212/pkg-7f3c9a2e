package com.google.android.accessibility.talkback.actor.gemini.progress;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import com.google.android.accessibility.talkback.Pipeline;
import com.google.android.accessibility.talkback.actor.gemini.progress.ProgressToneProvider.InfiniteIterator;
import com.google.android.accessibility.talkback.actor.gemini.progress.ProgressToneProvider.Tone;
import com.google.android.accessibility.utils.Consumer;
import com.google.android.accessibility.utils.Supplier;

/** Schedules and sequences playing a sequence of earcons to indicate loading progress */
public class ProgressTonePlayer {

  private static final int DELAY_PLAY_TONE_MS = 2500;
  private final Handler handler;
  private final ProgressToneProvider toneProvider;
  private final Consumer<Tone> tonePlayer;
  private final Runnable onTimeoutListener;
  private final Supplier<Long> currentTimeMsSupplier;
  private final Supplier<Long> timeoutDelayMsSupplier;

  @Nullable private InfiniteIterator<Tone> tones;
  private long endTimeMs;

  /**
   * @param toneProvider provides the set of tones to play for each invocation of {@link
   *     #play(boolean, boolean)}
   * @param tonePlayer given a Tone, actually plays the sound via the feedback {@link Pipeline}
   * @param timeoutDelayMsSupplier how long to play tones before timing out, read on each play
   * @param onTimeoutListener called after timeoutDelayMs is reached after calling {@link
   *     #play(boolean, boolean)}
   */
  public ProgressTonePlayer(
      ProgressToneProvider toneProvider,
      Consumer<Tone> tonePlayer,
      Supplier<Long> timeoutDelayMsSupplier,
      Runnable onTimeoutListener) {
    this(
        toneProvider,
        tonePlayer,
        timeoutDelayMsSupplier,
        onTimeoutListener,
        new Handler(Looper.getMainLooper()),
        SystemClock::elapsedRealtime);
  }

  /**
   * @param toneProvider provides the set of tones to play for each invocation of {@link
   *     #play(boolean, boolean)}
   * @param tonePlayer given a Tone, actually plays the sound via the feedback {@link Pipeline}
   * @param timeoutDelayMsSupplier how long to play tones before timing out, read on each play
   * @param onTimeoutListener called after timeoutDelayMs is reached
   * @param handler handler to use for testing
   * @param currentTimeMsSupplier returns the current elapsed system realtime in milliseconds
   */
  @VisibleForTesting
  public ProgressTonePlayer(
      ProgressToneProvider toneProvider,
      Consumer<Tone> tonePlayer,
      Supplier<Long> timeoutDelayMsSupplier,
      Runnable onTimeoutListener,
      Handler handler,
      Supplier<Long> currentTimeMsSupplier) {
    this.handler = handler;
    this.toneProvider = toneProvider;
    this.tonePlayer = tonePlayer;
    this.onTimeoutListener = onTimeoutListener;
    this.currentTimeMsSupplier = currentTimeMsSupplier;
    this.timeoutDelayMsSupplier = timeoutDelayMsSupplier;
  }

  /**
   * Starts playback of the tones.
   *
   * @param playTone if true, plays the tones. If false, simply waits for the timeout without
   *     playing any tones.
   * @param delayPlayTone if true, delay the tone play.
   */
  public void play(boolean playTone, boolean delayPlayTone) {
    stop();
    long timeoutDelayMs = timeoutDelayMsSupplier.get();
    endTimeMs = currentTimeMsSupplier.get() + timeoutDelayMs;
    handler.postDelayed(onTimeoutListener, timeoutDelayMs);
    if (!playTone) {
      return;
    }
    tones = toneProvider.getNextToneGroup();
    if (delayPlayTone) {
      handler.postDelayed(playNextToneRunnable, DELAY_PLAY_TONE_MS);
    } else {
      playNextTone();
    }
  }

  /** Stops playback and cancels pending tones. Does not call onTimeoutListener. */
  public void stop() {
    stopTones();
    handler.removeCallbacks(onTimeoutListener);
  }

  public void stopTones() {
    tones = null;
    handler.removeCallbacks(playNextToneRunnable);
  }

  private final Runnable playNextToneRunnable = this::playNextTone;

  private void playNextTone() {
    if (tones == null || currentTimeMsSupplier.get() >= endTimeMs) {
      return;
    }
    Tone tone = tones.next();
    tonePlayer.accept(tone);
    handler.postDelayed(playNextToneRunnable, tone.delayMs);
  }
}
