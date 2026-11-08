# Subtitle OCR

The owner approved automatic spoken subtitle updates, a dedicated settings page, selectable OCR engines, seven region modes, locked automatic regions with manual rediscovery, configurable interruption and a full stop when changing apps. Tesseract is the default. The waiting speech policy finishes current speech and retains only the latest pending subtitle.

`SubtitleSettings` maps persisted choices and custom percentages to the active app window. Focus mode snapshots the current accessibility focus without changing it. `SubtitleTracker` groups candidate lines, locks a padded horizontal region after repeated observations, filters stable text and suppresses overlapping lines. Empty observations never release the locked region. Rotation scales the existing region rather than starting another search.

`SubtitleController` runs one capture/OCR task at a time. It invalidates old callbacks when stopped, restarted, resized or manually reset. Package checks run before the normal accessibility event gate as well as at capture completion and before speech. Screen-off and pause stop the session. A separate speech group lets user input, stop and a newer subtitle affect subtitle speech without interrupting unrelated navigation feedback. Recognition uses the selected local OCR engine and never falls back silently to another engine.

The update dialog's Save to Downloads switch is off by default and remembered. With it off, installation still deletes the cached APK. With it on, the existing package/signature checks run before publishing a separate APK copy to Downloads. Android 10+ uses a pending MediaStore download; Android 8–9 requests write permission and registers the completed public file. A failed copy removes the partial public file. Normal and Shizuku installation retain their existing paths.

## CI coverage

- `SubtitleTrackerTest`: stable text, punctuation, rolling lines, overlapping words, repeated sentences after gaps, locked regions and rotation.
- `SubtitleSettingsTest`: defaults, engine and speech choices, seven regions, focused bounds, malformed restored settings and custom percentages.
- `SubtitleControllerTest`: app changes, screen off, old callbacks, latest pending speech, interruption and explicit user input.
- `UpdateDownloadsTest`: saving disabled by default, scoped storage publication, persistence after cache cleanup, incomplete-copy rollback and legacy storage permissions.

Run these tests in GitHub Actions only. Shared speech-group, resource and manifest changes allow the automatic CI selector to broaden coverage to the affected modules. Device testing remains necessary for real subtitle accuracy, speech timing, accessibility focus and OEM screen-capture behavior.

## Subtitle status overlay and recovery (2026-11-08)

Subtitle mode requests Android's **Display over other apps** special access to show a small non-interactive status overlay. This permission is **only** for the status overlay, not screenshot OCR. The screenshot is captured with `AccessibilityService.takeScreenshot()`; Android 11+ service resource configurations declare `android:canTakeScreenshot="true"`. The overlay is hidden during capture so it does not contaminate the image submitted to OCR.

The capture/OCR watchdog times out after 8 seconds and invalidates stale callbacks. UI transitions involving Meridian's menu or briefly missing application roots are handled with a 2.5-second grace period. The OCR pipeline tracks empty results separately from failures, explains missing subtitles after repeated empty frames, and releases stale automatically discovered regions. Certain content-change events from the video application can supply eligible accessibility text directly, without screenshot OCR. The debug log records selected engine, region, screen geometry, frame line count, and failure reason without full subtitle text.

User action: enable YouTube captions/CC and play the video; grant overlay access in Android Settings the first time; return to YouTube and enable subtitle reading again.

Caveats: secure/protected video surfaces may not be capturable; not every app exposes timed captions to accessibility. Compile, run CI tests, and verify capture/overlay behavior on Android 16 before release.

## Burned-in video caption OCR recovery (2026-11-08)

On-screen subtitles exposed as Android accessibility text are read directly. Subtitles *burned into video pixels* require a screenshot of the video player and OCR. In automatic mode, Meridian attempts to locate the video player's accessibility bounds (for instance, YouTube's `watch_player`) and scans those bounds, falling back to the application window when the player cannot be located.

The OCR tracker distinguishes an empty screenshot recognition result, OCR text outside the subtitle region, pending stability, duplicates and newly detected subtitles. Other interface words cannot reset the missing-subtitle counter. Short captions are supported after the subtitle region is identified. Subtitle-region scanning recovers from repeated absence of candidates and stale unchanged UI text; it does not stay locked on one wrong region indefinitely.

Image OCR now alternates normal frames with locally contrast-enhanced and potentially 2x upscaled frames after repeated misses, preserving original pixels on alternate frames. Tesseract uses sparse-text page segmentation for caption recognition; after continued misses or technical OCR errors, Meridian occasionally tries the other installed engine (Tesseract or ML Kit). Image bounding boxes are mapped back to screen coordinates before subtitle-region filtering.

Privacy-friendly debug logs include the selected video viewport, screenshot size and crop, aggregate brightness/contrast, chosen OCR engine, returned line counts and filtering outcomes. Captured images and subtitle text are **not** stored or uploaded by this diagnostic. The screenshot still relies on Android's AccessibilityService screenshot API. Android may reject screenshots of secure windows or render protected video surfaces black; Meridian cannot OCR video pixels that the OS refuses to provide. The overlay permission grants only a visual status overlay and does not change screenshot access.

Compilation/CI checks and a real-device YouTube test remain separate acceptance criteria; successful CI does not prove every video surface is capturable.
