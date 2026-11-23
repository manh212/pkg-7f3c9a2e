# Meridian update log — November 23, 2026

Development build **2872**, validated revision `6b04a4a6e89962bbd0b8b8b4460f0b6821ec0afd`.

## New features

- Added an optional double-tap input mode for the T9 numeric keyboard. Touching a digit reads it; tapping twice enters it regardless of the letter keyboard's typing mode. The setting is off by default and is available in the Vietnamese keyboard's typing settings.

## Changes

- Moved recurring runtime integrity verification to a background worker with a 45-second refresh interval. Native environment checks reuse successful results for up to 30 seconds and retain detected failures for the process lifetime.
- Added native integrity checks to Lua memory attachment and tied offline verification MAC generation to the native integrity state.
- Expanded instrumentation detection, checked the process package name, rejected unavailable process information, and checked both sizes of the APK signing block.
- Obscured additional activation identifiers and native registration strings, removed selected Kotlin parameter checks through R8, and enabled native section cleanup and symbol stripping.
- Reduced repeated call-permission queries and warning logs. Missing accessibility nodes now produce debug messages rather than error messages.

## Fixes

- Filtered passive SeekBar state updates and coalesced frequent subtree changes to reduce event processing pressure while preserving interactive updates.
- Used the actual dispatch time when delivering deferred events.
- Applied traversal time budgets when rebuilding reading order and recovering after scrolling, with live traversal as a fallback. Live scrolling searches now continue past nodes rejected by the navigation filter.
- Allowed local prediction for default web navigation when PCM preparation is enabled, while avoiding prepared speech descriptions for web-action targets.
- Preserved RecyclerView preference accessibility delegates when tracking settings focus.
- Enabled automatic storage of the application language and used the current resource language when setup has no saved language.
- Prevented the performance selector's initial binding from applying the selected profile again.
- Recorded feedback output when playing prepared speech audio so latency statistics include that path.

## Verification

- [GitHub Actions passed](https://github.com/manh212/pkg-7f3c9a2e/actions/runs/45204955572): repository checks, settings key contract, both module unit-test tasks, APK assembly, lint, activation obfuscation checks, signing certificate checks, and dev publication.
- The CI plan selected all 486 available test classes: 415 in TalkBack and 71 in Utils. Both `:talkback:testPhoneDebugUnitTest` and `:utils:testPhoneDebugUnitTest` completed successfully.
- Added T9 confirmation, hover speech, and unchanged letter-typing regression coverage; updated typing settings structure coverage and fixed the simulated time in spoken-clock broadcast tests.
- [Development build 2872](https://github.com/manh212/pkg-7f3c9a2e/releases/tag/dev) includes the signed `meridian.apk`.
- Device interaction, focus order, language persistence, activation, and perceived responsiveness remain for the owner to verify on a phone.
