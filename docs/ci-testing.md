# Automatic CI test selection

GitHub Actions runs a lightweight `plan` job before setting up Java or building
Android artifacts. `scripts/select_ci_tests.py` produces `test-plan.json`, the
`test-plan` artifact and the run's Test selection summary. The summary includes
the selected classes, module scopes and reasons for widening coverage.

## Comparison baseline

For a push to `master`, compare the checkout with the commit in the published
dev release's name. Read the release metadata rather than relying only on the
movable `dev` tag: publication moves that tag before uploading the APK and
updating the release name. If publication fails partway through, the next run
must still include the unpublished changes. Several commits pushed together,
failed builds and later documentation commits are therefore covered by the
same accumulated diff until publication succeeds.

For a pull request, use the merge base with its target commit. Stable release
tags require every test module. Missing or unrelated release metadata broadens
coverage to every test module. A manual run with no narrower change set also
validates every test module. A scheduled run (nightly, 02:00 Asia/Saigon)
always validates every test module, so breakage that per-change selection
cannot see is still caught within a day; it never builds a release.

## Selection rules

| Change | Selected application tests |
| --- | --- |
| Root Markdown, `docs/`, release notes and issue templates only | None; skip the application build and publication too. |
| An existing unit test class | That class; validate it without building or publishing an APK if no application code changed. |
| Test-only resources, instrumentation tests or test-selection tests | Related module tests (or all test modules for CI selector tests); do not build or publish an APK for test-only changes. |
| A test helper or removed test | Its module and dependent modules. |
| Source in a recognized feature package | Tests in its feature groups plus tests elsewhere that reference symbols from those groups. |
| Any other source file (core code, shared library code, unclassified packages) | Tests that reference the changed file's symbols; if no test references it, the owning module and modules that depend on it. |
| Resources, manifests, native code or other non-source files | The owning module and modules that depend on it. |
| Shared build files, CI configuration or test-selection scripts | Every test module. |
| A module without discoverable test coverage or known tested consumers | Broaden to every test module. |

Recognized packages currently include AI services, backup, collapse, dictation,
direct touch, granular editing, individual feedback, keyboard echo, labels,
migration, notifications, quick settings, diagnostics, scripts, sound themes,
stuck-touch handling, timers and updates. Timer and notification tests run
together, as do scripts and collapse, and sound themes and individual feedback.
Source files outside the recognized packages use the same reference-based
selection without the feature groups: only tests that mention the changed
symbols run.

The selector reads both current and removed source files. Renames contribute
both the old and new paths. Gradle project dependencies determine which modules
consume a changed library. Source selection includes both `test` and `testPhone`
unit-test source sets. Device tests remain the owner's responsibility.

## Execution and reporting

`scripts/run_ci_tests.py` checks that the downloaded plan belongs to the current
commit, then runs the planned module tasks with class filters where applicable.
Failures stop execution and block publication. Application changes cannot pass
with an empty test plan. Gradle's failure when a class filter matches no tests
is retained.

The validation job runs repository checks, the settings key contract and selected
unit tests. If only tests change, this validation job runs
without APK assembly, lint or publication. Application changes start APK assembly
and lint in separate GitHub Actions jobs in parallel with validation.
Publication waits for validation, assembly and lint all to pass, and the APK signature is checked before
publication. The selector's own Python tests run in CI when its scripts or
workflow change. Gradle caches are restored separately on each runner, so
parallelization can trade more runner usage for a shorter elapsed build time.
No builds, tests, lint or packaging run on the coding machine.

Documentation changes need no `[skip ci]` marker. The plan job confirms their
scope and skips application work automatically. A documentation commit after
an unpublished application change still validates and publishes that pending
application change.

## Limits and maintenance

This is a source-reference heuristic, not a complete
runtime dependency analysis. Reflection, script dispatch and indirect behavior
can break tests that per-change selection does not run; the nightly scheduled
run covers every test module to catch that breakage. When introducing such a dependency,
extend the feature groups and their CI tests, or retain the module fallback.
Resource, manifest and native changes deliberately use wider coverage.

The class counts in the plan describe discovered classes, not executed test
methods. Use the actual test logs and reports when reporting test results.
A focused green run verifies its selected coverage; it does not claim the
unselected tests passed. Test compilation and dependency setup can still involve
more source files than the test classes executed, and build and lint time remain.

Selection failures retain `gradle-log-tail`; build failures retain
`gradle-log-tail`; test and lint failures retain `gradle-log-tail-tests`
and `gradle-log-tail-lint` respectively. Read the relevant logs before
diagnosing or fixing a failed run.
