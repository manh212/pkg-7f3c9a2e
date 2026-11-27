# Switching between the two most recent apps

The existing commands `EDGE_GESTURE_PREVIOUS_RECENT` and
`EDGE_GESTURE_NEXT_RECENT` both switch to the other app in the same recent pair.
Their saved identifiers remain unchanged, so existing gesture assignments keep
working. Holding an edge gesture does not repeat either command. Previous-window
and next-window commands remain separate and retain their window navigation.

Both commands are registered in the common Commands list under system actions
and are available in general gesture assignment lists. Searching for their
Vietnamese names, with or without accents, finds them even when no gesture has
been assigned. Existing edge assignments also appear in the assigned-only
filter. Sentence-stepping commands remain limited to edge gesture pickers.

Meridian records the two most recently focused, launchable application packages
while its accessibility service is active. Opening a third app replaces the
older member. The pair is stored locally and loaded after a service or device
restart, once credential storage is unlocked. Keyboard windows, accessibility
overlays, System UI, the home screen and packages without a launcher entry do not
replace the pair. From the home screen, the command opens the most recent app.
Locked-device events do not update the pair, and switching is disabled while
locked. No Shizuku or usage-access grant is required.

This is observed app history, not Android's complete recent-task list. Two apps
must have been observed before switching between them becomes available. An app
removed from Android's recent screen can be reopened. Switching uses the app's
launcher intent with `FLAG_ACTIVITY_NEW_TASK`; restoration of an exact activity,
task, document or work-profile instance depends on Android and the target app.

Only an observed foreground change updates the pair after a switch. Repeated
commands while a launch is pending do not launch it again. An unavailable target
is removed from the stored pair. Launch errors return the existing command
failure result; a launch that does not reach the foreground within two seconds
plays the existing failure sound. Shutdown cancels pending verification.

`RecentAppSwitcherTest` covers alternation, third-app replacement, excluded
windows, home behavior, persisted history, uninstall, launch failure and retry,
timeout, lock state, direct boot, shutdown and corrupt saved data.
`EdgeGestureRecentCommandsTest` protects saved command identifiers and gesture
repeat behavior. `CommandsSearchTest` covers common catalog and picker discovery,
accent-insensitive search, system-action grouping and existing edge assignments.
GitHub Actions provides execution; device task restoration and
gesture feel are checked by the owner using the published dev build.
