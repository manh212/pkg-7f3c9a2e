# Navigation latency experiments

The developer settings contain **Thử nghiệm độ trễ điều hướng**. Experiment
switches default to **off**, independently of existing preferences. Existing
settings, including the reading-order cache, split speech and text lookahead,
keep their stored values. Changing one experiment never enables another.

| Preference suffix (`pref_latency_…`) | Enabled behavior |
| --- | --- |
| `trace` | Retain the latest 1,024 gesture records, individual stages, work spans, outcomes and counts exceeding 20 ms and 50 ms. Request Android motion timestamps when supported. Turning this switch on or off starts a new statistics session. |
| `reset_session` | Reset performance, traversal, event and audio-gap counters when clearing diagnostic logs. |
| `stall_capture` | Capture main-thread stalls from 50 ms, retaining stacks in the diagnostic export even without the verbose log file. |
| `light_logging` | Suppress most verbose/debug formatting during touch and for 250 ms after input; retain warnings, errors and latency markers. Disable the legacy debug watchdog unless explicit stall capture is enabled. |
| `nonblocking_prefetch` | Skip the synchronous window-node prefetch wait on navigation. Existing background prefetch remains governed by the extras master switch. |
| `async_order` | Build a missing logical reading order on a single background worker, then resume the original gesture. Queue subsequent navigation actions in arrival order. Requires the existing reading-order cache switch. |
| `scoped_cache` | Preserve reading order and prepared speech when a known foreign window changes. Preserve the order for same-window metadata-only changes and prepared speech whose node is outside a known change region. Active-window transitions invalidate prepared text. |
| `input_priority` | Defer text/state-only updates during touch and the input grace interval, invalidate prepared text immediately, prioritize focus events, coalesce more aggressively and yield the compositor queue after a 2 ms slice. |
| `toast_filter` | Avoid heads-up window-tree scans for toast events without an Android notification payload. Real notification processing remains available. |
| `early_controls` | Allow early complete speech for sliders, progress controls and page indicators already exposing text, description or range information. Web, keyboard and known focus-dependent content retain their event path. |
| `cached_text` | Prepare announcements only from a current saved tree, without an extra refresh. Prediction itself can still query nodes. |
| `fresh_prepared` | Allow prepared speech younger than 100 ms when its content generation is still current. Expiry, target type and invalidation remain enforced. |
| `pcm_adjacent` | Prepare the first audio segment of the predicted adjacent target in both directions. |
| `pcm_second` | Prepare the first audio segment for targets two steps away, independently of adjacent PCM. |
| `immediate_speech` | Prioritize speech from navigation actions and linked responses over older noncritical speech. Preserve globally uninterruptible and wait-cap-exempt messages under their existing policies. |
| `fast_single_finger_swipe` | With two-finger rotation enabled, skip rotation checks on one-finger moves and reuse the active gesture matchers until another pointer joins. Use the normal confirmation event and gesture order. |
| `touch_speech_with_rotation` | Apply the existing stop-speech-on-touch setting when a new finger-down follows a completed gesture in the same Android touch interaction. Hold local speech before clearing the feedback queue. Requires two-finger rotation and stop-speech-on-touch to be enabled. |
| `fast_window_transitions` | Use a ready, named application window on the default display when an existing transition or a new active/focused window signal identifies it. Reuse the target window's root during one window-navigation action. |
| `fast_scroll` | Resume from confirmed scroll data without a settling delay when a matching event reports a valid visible item range. Reuse local traversal where adaptive traversal is already active and shorten settling for manual native scrolling after touch ends. |
| `live_edge_reuse` | Allow same-window edge checks to reuse a live traversal strategy. |
| `audio_resample` | Resample each new speech stream outside the shared audio-output lock, under a per-stream lock. Takes effect for newly opened streams. |
| `audio_keep_warm` | Extend idle audio-track retention from 2 seconds to 10 seconds. The shorter microphone-in-use policy remains. |

The two touch timing sliders show absolute milliseconds and use the current
device timings by default. **Tổng thời gian chạm mô phỏng sau nhấn đúp** starts
at two system tap durations plus the existing 40 ms gap when Meridian simulates
activation. A shorter setting shortens both strokes. A longer setting holds
the gesture before the first stroke to keep the strokes valid. The gap and
the physical double-tap recognition thresholds stay the same.
On Android 13 and later, **Phản hồi nhanh khi nhấn đúp** sends an ordinary
double-tap to Meridian as soon as the second finger-up is recognized, with or
without rotation. Meridian uses the existing accessibility click and click
feedback. If that click is unavailable, it asks Android's touch controller
to click before using its own simulated fallback. The slider controls only
that last fallback. The switch retains the value stored by the former
simulated activation experiment. Latency tracing records which click path ran.

**Thời gian chờ đọc khi giữ yên một ngón** starts at the existing exploration
delay, the system double-tap timeout minus 50 ms with a 150 ms floor. It
applies while Meridian handles touch for rotation, extended gestures or fast double-tap feedback on
Android 13 and later. Real taps, movement beyond touch slop and multi-finger
gestures leave the stationary timer.

## Preparation and cache boundaries

**Tắt việc phụ gây trễ** is the master veto for background preparation. It must
be off for text lookahead, background node prefetch and both new PCM switches
to work. It now also prevents the previously ungated synchronous prefetch.
The individual PCM switches are disabled in settings while that veto is on;
their stored values are retained. Async construction requested by an actual
navigation action is foreground work dispatched to a worker, so it does not
require idle preparation to be allowed. Idle async warmup does require it.

Text lookahead and PCM are distinct. The existing adjacent/second text switches
prepare feedback; the PCM switches additionally request audio. Lookahead resumes
after touch, retries a missing saved order once and prepares adjacent targets
before second-ahead targets, starting in the most recent navigation direction.
The touch controller can resume lookahead at finger-up within a grouped Android
touch interaction. Speculative targets do not replace the navigation pivot or
increase the foreground order's cache hit totals.
PCM misses immediately use normal synthesis. There is no wait for preparation.

PCM preparation uses a separate TTS connection to the current engine and voice,
only while input and foreground synthesis are quiet. New input cancels active
preparation asynchronously. Pending preparations can resume until they expire
or a newer focus supplies a replacement set. A shared
engine process can still contend internally; this is an experiment to measure
on the actual engine. Voice profiles and semantic TTS spans fall back to normal
synthesis. Engine, voice, locale, pitch, rate, output sample rate and configuration
isolate cache entries. Voice/settings changes invalidate prepared audio. Local
audio playback is required. The cache has an 8 MiB/eight-entry bound, a four-item
pending queue, six-second clip limit and a 2.5-second synthesis timeout. Temporary
files are private and deleted on completion, cancellation or shutdown. The
diagnostic output distinguishes missing order, touch/engine deferrals,
invalidations and lookup failures without including prepared text.

Async order construction publishes complete trees only after a revision check.
A cooperative 200 ms budget rejects partial results; a single blocked Android
node query cannot be preempted by that budget. Failed or stale builds use the
existing synchronous path and record the fallback. Window transitions and
activation cancel pending actions; queue overflow is reported as a failure.
Script node overrides use foreground traversal to preserve their thread model.
Turning async order off cancels outstanding work. No gesture is silently skipped
by treating a failed or partial build as a complete order.

The input-priority slice yields between events. It cannot interrupt a listener
already blocked in an Android IPC. At levels other than very low, the experiment
can defer background updates and active-window text/state-only updates during
interaction, keeping the latest update in its deferred slot.

Very low requests a restricted event mask from Android. Notifications,
window transitions, focus, typing and touch events still reach the usual
handlers. Scroll feedback is admitted during recent interaction in the same
window or when it matches a pending scroll action. Passive content and geometry
changes only invalidate the affected caches, with no source-node lookup or
replay into the feedback queue. Unsolicited announcements and selection feedback
are skipped. The diagnostic report counts this cache-only path separately from
events admitted to feedback.

## Repeatable device measurements

1. Keep app content, TTS engine/voice/rate, speech splitting, old controls and event
   level unchanged. Record cold start, warm navigation and scrolling separately.
2. Enable per-gesture trace only when the diagnostic log needs detailed stages; the
   timed result dialog captures its summary independently. Enable explicit stall capture
   when investigating stalls. Compare logging modes separately because debug logging
   itself has a cost.
3. Choose **Đo độ trễ trong 10 giây** after changing settings. It resets measurement
   counters and records navigation gestures for ten seconds without clearing log files
   or requiring the per-gesture trace switch. The result dialog reports average
   navigation-result and audio timings for the whole capture, plus details for the latest
   ten gestures. Turning on reset-on-clear keeps the existing log-clear action separate
   and resets counters when logs are cleared.
4. Repeat the same swipe sequence with the baseline, then one experiment at a time.
   Do not omit startup, cancellation, failure, missing audio or slow samples.
5. Export diagnostics after each run. Verify source commit, raw preferences,
   settings-enabled preparation and actual PCM availability/hit statistics.

Stage times are cumulative from the original recognized gesture callback, even
through async order, scroll and focus responses. `input_to_callback_ms` separately
measures the last supplied motion timestamp to callback entry. The timed result
dialog includes that interval when Android supplies it. When the rotation touch
experiment holds local speech, tracing also records finger-down to local hold.
`ACTION_RESULT` and the legacy action stage are not action durations;
`action_<id>_ms` is the measured duration of that action. Work spans report wall
milliseconds and CPU microseconds measured on the same thread. Aggregate stage
percentiles cannot be subtracted to infer per-gesture work.

`FIRST_PCM` is the first PCM callback (or ready cached PCM). `FIRST_SOUND_PCM`
is the first PCM block containing a nonzero sample. `AUDIO_SUBMITTED` is the
first successful track write; `SOUND_SUBMITTED` is the write containing the first
nonzero samples. Neither proves physical speaker onset. Canceled/no-audio records
and overdue pending gestures remain visible. Counts, budget violations, mean
and maximum cover the session; percentiles cover the latest 300 samples and
individual records the latest 1,024 gestures. Starting a session resets both
populations. The diagnostic export retains up to sixteen captured stall records.

## Validation limits

CI validates cache limits/keying, independent settings, content invalidation,
background deferral, preserved critical speech, gesture attribution, bounded
traversal, prefetch vetoes and toast filtering, along with affected module tests,
compilation and lint. Android IPC scheduling, real TTS startup/contention, audio
routing, physical onset and accessibility focus order require device testing.

The engineering target is typical navigation near 20 ms and a 50 ms upper budget.
These experiments expose budget violations and attempt to remove identifiable
waits. They do not establish or guarantee those figures before matched device
measurements; external app response, scrolling and the selected TTS engine can
still exceed the budget. A separate native audio backend or replacement TTS
engine should be considered only if measurements identify that remaining need.
