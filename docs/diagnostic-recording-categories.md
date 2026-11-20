# Diagnostic recording categories

The **Nhật ký và báo lỗi** screen has one recording category: All, Accessibility focus,
Reading order and scrolling, Gestures and touch, Accessibility events and windows, Latency and
performance, TTS and audio, Speech preparation and cache, Input and Braille, Recognition and AI,
Connectivity and support, or System and settings.

The selected category gates Meridian's own debug log before formatting. All records every
category. Changing categories does not erase existing log files. Turning debug logging off stops
recording without deleting history. Export, copy and support attachment include all retained
log-file history regardless of the category currently selected.

Component tags determine categories for legacy logs. Dedicated diagnostic tags distinguish
cross-cutting events. Unclassified tags belong to System. Android logcat does not respect
Meridian's category filter, so exported diagnostic history comes only from Meridian's log files.

Two rotating files bound retained history, and records rotated away cannot be recovered. In a
non-All category, the exporter avoids generating an unrelated full state snapshot or performance
dump but preserves all earlier category records.
