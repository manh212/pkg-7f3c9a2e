# Common command discovery

The common Commands list includes commands from general gesture assignment,
edge gestures, text and element navigation, menu actions, recognition modes and
quick device controls. Installed apps, custom menus, scripts, gesture profiles,
voice profiles and saved text commands are supplied by their existing dynamic
providers. Specialized settings and command pickers remain available.

The discovery audit restored these missing commands without changing their
saved identifiers or execution paths:

| Commands | Common category |
| --- | --- |
| Previous and next recent app | System actions |
| Previous and next sentence | Text navigation |
| Append selection to clipboard | Clipboard and copy |
| Read current time | Clock and timers |

Reading-group navigation, current time and date, and translation of the last
spoken phrase keep their existing physical-keyboard action associations. Their
existing shortcuts resolve to the same common command instead of appearing as
unrelated keyboard-only values.

Deprecated keyboard enum members without valid shortcut keys or executable
actions are not new commands. Gboard-only and combined media/voice-input
commands remain removed, as required by their existing regression coverage.
Their legacy dispatch paths continue to support saved assignments. Browse-mode
aliases already have a common command and do not require another row.

`CommandCatalogCoverageTest` checks provider coverage, the restored categories
and keyboard associations. `CommandsSearchTest` checks visible search results,
accent-insensitive queries, system-action grouping and existing edge bindings.
Search filtering retains preference objects and changes their visibility, so
regressions must assert visibility rather than object removal. GitHub Actions
runs the selected tests, lint, APK build and development publication. The owner
checks the resulting interface on a device.
