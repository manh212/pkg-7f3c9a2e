# Experimental standalone SODA dictation

The SODA choice in Dictation uses Meridian's microphone capture and a private
`:soda` process. It does not switch keyboards, launch PixelBoard or bind to
Google Speech Services. It supports Vietnamese and an ARM64 application process.
The existing voice-input commands and keyboard microphone use the selected mode.

Select SODA in Dictation settings and download its data. First use of voice input
also starts the download, without opening the microphone automatically when the
download completes. Record speech and invoke voice input again to stop. The
existing silence and recording limits still apply. Ordinary SODA dictation skips
cloud correction and emoji rewriting. The separately invoked translation action
still needs its configured translation provider and accepts Vietnamese input.

## Runtime and data

The download retrieves PixelBoard 1.3.6's published APK, extracts only its ARM64
`libintegrated_shared_object.so`, and downloads Google's Chrome SODA vi-VN
1.3071.0 component. Neither downloaded application is installed. Both archives
and the extracted native library have pinned SHA-256 hashes. Recognition runs
locally after installation. Archive files are removed after extraction; installed
data is excluded from Android backup. The settings page can cancel, retry or
delete the download. Approximately 183 MB is downloaded; installation requires
additional temporary disk space.

The engine and trained data are third-party binaries, not Meridian source code.
They are retrieved from their original publishers and are not checked into this
repository. PixelBoard's patch-source license does not establish the licensing
terms of Google's native engine or trained data.

## Compatibility and validation

This is an experimental native integration. The downloaded Vietnamese pack is
from the same Google SODA platform, but has not been established as the identical
checkpoint used by a particular PixelBoard installation. Native initialization,
endpoint behavior and punctuation quality require owner testing on Android.
CI protocol tests cannot establish native compatibility or recognition accuracy.

The adapter preserves formatted hypotheses when the engine returns them. It does
not substitute an unrelated model, emulate punctuation with fixed rules, or fall
back to an external speech service. A native crash is confined to the recognition
process. Cancellation, timeout and process death discard pending text; results
remain subject to the existing dictation generation and editor-target checks.

The JNI surface and protobuf field numbers were identified from the published
PixelBoard APK. `SodaWire` bounds message parsing and rejects malformed status
and result payloads. Automated coverage includes Vietnamese text and punctuation
preservation, formatted hypotheses, partial-to-final replacement, archive path
validation and cancellation. Device acceptance must separately cover offline
recognition, automatic punctuation, long pauses, multiple sentences, cancellation
and compatibility with the engine's ARM64 native dependencies.
