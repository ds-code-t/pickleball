# Tech debt

This page lists behavior that is not a feature. Do not document it as syntax.

## Unused step ceiling fields

`StepBase.stepMaxIterations` and `StepBase.stepTimeoutSeconds` are never assigned. A block `until` uses the run-wide `pkb_stepRepeatMaxCount` and `pkb_stepMaxTime` values. An element `wait` uses `pkb_stepMaxTime` only. There is no per-step ceiling. Do not read those fields as one.

## Hung step watchdog

A non-repeating step never samples `pkb_stepMaxTime`. Only a block `until` and an element `wait` do. There is no watchdog that fails one hung non-repeating step. One should check about once a minute and fail that step. A hung Selenium call on the step thread may keep running after the watchdog fires.

## Accepted steps that do nothing

The parser and glue accept these steps. Their methods are empty. They do not change which later steps run. Do not document them or use them as features until they are implemented:

- `RETRY:`
- `IGNORE FAILURES`
- `LOG FAILURES BUT CONTINUE SCENARIO`
- `RUN IF SCENARIO FINISHED`

`ALWAYS RUN` and `RUN IF SCENARIO PASSING` also accept an optional ` AND IGNORE FAILURES` suffix. `RUN IF SCENARIO PASSING` also accepts an optional ` AND SCENARIO FINISHED` suffix. The runner stores the whole line. That text is not the bare flag, so the suffix does not apply `ALWAYS RUN` or `RUN IF SCENARIO PASSING` and does not change failure handling.

## Unsupported context words

These words do not resolve and are not context words: `between`, `inside`, `within`, `of`, `on`, `near`, `next to`, `following`, and `preceding`. `between` is under investigation.

## Unsupported condition word

`exists` is not a condition. Use `is present` or `is displayed` for elements, or a bare reference for values. Do not document `exists` as syntax.

## Any parent context

There is no syntax that means "any parent context". `PhraseData.context` and `PhraseData.getPhraseContextList` are the hook. `AllContextsScan` must not special-case that syntax.

## Phrase-local context

A phrase cannot opt out of `ALL_CONTEXTS` or pin `Loading` to the inherited `in` / `from` / `for` context. A future flag would be read only by `ElementMatch.searchesAllContexts()`.

## Inline until

A mid-line or comma `until`, and a period-ending `until`, do not loop. They act as a one-time `if` or do nothing. Only a block `until` ending in `:` or `?` with nested steps loops. `runUntilOperation` is not that runtime path.

## Deferred Linux and macOS tests (per Dan, 2026-11-24)

The 3.0.0 retest-fix rounds (R, S) ran only targeted tests on the Linux box and the CI jobs (Windows, Linux live Chrome, macOS Store and core). These significant runs are deferred and have not been run for those changes. Run them before a 3.0.0 release:

- **Linux, full suite:** a complete `./gradlew :test` (every class, not the per-phase targeted sets) on Linux with Chrome, Edge, and Firefox present.
- **Linux, live collaboration and push:** `RetestFixesLiveTest`, `CollabFixesLiveTest`, `TwoActorRecipeLiveTest`, `OverlayRuntimeLiveTest`, `DomPushLiveTest`, `BrowserEventsLiveTest`, `BrowserEventsBidiLiveTest`, `MonitorLiveTest` in CI (they ran only on the box), plus headed (not headless) runs of the same classes.
- **Linux, other browsers and desktop:** `LiveEdgeSnapshotTest`, `LiveFirefoxSnapshotTest`, and the Linux desktop (AT-SPI) live tests.
- **macOS, live browser:** the macOS CI job runs only Store and core. The live Chrome/Edge suites above, the collaboration two-actor tests, and the desktop (AX) live tests have not run on macOS.
- **macOS, processes:** `doctor` `orphans=` reads `/proc`, so on macOS it always reports `none`; the orphan and stop process-tree behavior (including not killing a live pickleball host) is untested there. `QuietSlf4jTest` (child JVM) has not run on macOS.
- **Consumer-fixes branch, Linux and macOS:** the P1–P6 targeted classes on `3.0.0-consumer-fixes` ran on Windows only. Live classes not repeated on Linux or macOS: `HeadedFlagTest`, `HumanTierTest`, `RetestFixesLiveTest`, `ConsumerFixesFrameLiveTest`, `InputLiveTest.findRoleSearchesEveryFrame`, `PwCommandLiveTest`, `ContextLiveTest`, `ConsumerFixesP2FindTest`, `ConsumerFixesP3LiveTest`, `RefLifetimeTest`, and `LiveDesktopTest` (Windows UI Automation; the AT-SPI case still skips with `no AT-SPI bus` on Windows). Put them in the Linux full suite and the macOS live-browser gap above.
- **Spine R1, Windows only:** `UniversalEntityTest`, `DeltaWindowTest`, and `HeadMaterializerTest`.

## Deferred from the 3.0.0 consumer-fixes branch (per Dan, 2026-11-25)

These items stay out of `3.0.0-consumer-fixes`. The roadmap lists them under "Not in scope". P6 records them here. Do not treat a later drive-by as permission to implement them.

| Item | Why it is deferred |
|---|---|
| 6.1.5 | Shared frame policy. The case is consumer config (L13). |
| 6.3.1 | Snap-backed find index: a large design. 6.3.5 budget/progress covers the visible pain. |
| 6.3.6 | Warm PW/CDP at start. |
| 6.5.1 | Structured chat refs/actions. |
| 6.5.2 | "Point" button. |
| 6.5.3 | Typed card answers. |
| 6.5.4 | Per-session chat channels by default. Behaviour change. |
| 6.5.6 | Progress card. |
| 6.5.7 | Overlay read receipts. |
| 6.6.4 | Runtime injection on attached windows. Only the "fail clearly" part is in P3. |
| 6.6.5 | Body-modify mocks. |
| 6.6.6 | `pw` steps in execute-steps. The `pw` fix itself is in P2. |
| 6.7.1 | Desktop launch. |
| 6.7.3 | UIA tree cache. |
| 6.7.4 | Desktop screenshot. |
| 6.8.1 | One reply schema. Breaking. |
| 6.8.5 | Normalised element record. |
| 6.8.6 | `@args.json` on every verb. P5 documents the quoting instead. |
| 6.8.8 | Session journal. |
