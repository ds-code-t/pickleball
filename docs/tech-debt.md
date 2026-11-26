# Tech debt

This page lists behavior that is not a feature. Do not document an open item as syntax. A heading marked closed describes behavior that shipped.

## Step ceilings (closed, spine R2 P3)

`steps.max_iterations` and `steps.timeout_seconds` are nullable integers. Hydrate leaves them untouched. A null column keeps the run-wide `pkb_stepRepeatMaxCount` and `pkb_stepMaxTime`. When a row sets them, the native runner and the Cucumber adapter assign `StepBase.stepMaxIterations` and `StepBase.stepTimeoutSeconds`. A block `until`, an inline `until`, and `RETRY:` use that cap. An element `wait` still uses the run-wide time cap only. A null column is not read as zero.

## Hung step watchdog (closed, spine R2 P3)

`StepWatchdog` is a daemon started by `NativeScenarioRun` and stopped in a `finally` before scenario cleanup. Every 60 seconds it looks at the current non-repeating step. When that step's `timeout_seconds`, or `pkb_stepMaxTime` when the column is null, has been exceeded, the step fails with `error: step exceeded its time cap` and `fix: raise timeout_seconds or pkb_stepMaxTime`. The watchdog does not interrupt the step thread. A hung driver call may still be running. A repeating `until` is not double-failed. `pkb_engine_log=watchdog` logs each sample and each fire. The entity is the scenario name. The log stays off unless that capability is named.

## Scenario flag steps (closed, spine R2 P3)

`RETRY:`, `IGNORE FAILURES`, `LOG FAILURES BUT CONTINUE SCENARIO`, and `RUN IF SCENARIO FINISHED` change which steps run and how a failure is recorded. `FlagSteps` and `GeneralSteps.retryStep` call `StepFlags.applyFromPhrase`. Hydrate writes the matching columns on `steps`. `IGNORE FAILURES` and `LOG FAILURES BUT CONTINUE SCENARIO` as their own steps mark the following step. A suffix `AND IGNORE FAILURES` marks that same step. `AND SCENARIO FINISHED` on `RUN IF SCENARIO PASSING` sets both passing and finished. `RUN IF SCENARIO FINISHED` runs after the scenario outcome is known. `RETRY:` runs the remainder again until it passes or the repeat cap is hit. The cap is `max_iterations` when set, otherwise `pkb_stepRepeatMaxCount`.

## Context words (closed, spine R2 P4)

`inside` and `within` use the `inside` lookup. `near` and `next to` use `near` (center distance at most 150). `following` is document order `after`. `preceding` is document order `before`. `on` and `of` use the same lookup as `in`. `between` A `and` B keeps a displayed candidate whose center lies in the axis-aligned box of the two anchor centers. A missing second anchor is an error. A word inside quotes is not a relation. `pkb_engine_log=match` logs `word -> relation` for a translated context word. The log stays off unless that capability is named.

## Condition word exists (closed, spine R2 P4)

`exists` on an element uses the same result as `is present`. A hidden element matches `exists` only when `is present` would. `exists` on a value is true when the value is non-null and is not an empty reference such as `<name>`. `exists` is not another spelling of `is displayed`.

## Any parent context (closed, spine R2 P4)

`in any parent` sets `steps.context_scope` to `any-parent`. `PhraseData.getPhraseContextList` keeps every ancestor context the phrase chain already walks. The search ORs those ancestor contexts. `AllContextsScan` has no branch for this syntax.

## Phrase-local context (closed, spine R2 P4)

A trailing `with local context` sets `steps.context_mode` to `local` and `ElementMatch.searchesAllContexts` returns false, so `Loading` stays on the inherited `in`, `from`, or `for` context. A trailing `with all contexts` sets `all` and the scan runs. The default `inherit` keeps the category `ALL_CONTEXTS` flag. The mode is read before that flag.

## Inline until (closed, spine R2 P3)

A mid-line `until`, a comma `until`, and a period-ending `until` loop while the condition is true. `PhraseData.runUntilOperation` is that loop. It stops at `max_iterations` or `timeout_seconds` when the step row sets them, and at `pkb_stepRepeatMaxCount` or `pkb_stepMaxTime` when those columns are null. A block `until` ending in `:` or `?` keeps the nested-step loop and honors the same per-step ceiling. An assertion-chain member is the condition check and does not start that inline loop.

## Deferred Linux and macOS tests (per Dan, 2026-11-24)

The 3.0.0 retest-fix rounds (R, S) ran only targeted tests on the Linux box and the CI jobs (Windows, Linux live Chrome, macOS Store and core). These significant runs are deferred and have not been run for those changes. Run them before a 3.0.0 release:

- **Linux, full suite:** a complete `./gradlew :test` (every class, not the per-phase targeted sets) on Linux with Chrome, Edge, and Firefox present.
- **Linux, live collaboration and push:** `RetestFixesLiveTest`, `CollabFixesLiveTest`, `TwoActorRecipeLiveTest`, `OverlayRuntimeLiveTest`, `DomPushLiveTest`, `BrowserEventsLiveTest`, `BrowserEventsBidiLiveTest`, `MonitorLiveTest` in CI (they ran only on the box), plus headed (not headless) runs of the same classes.
- **Linux, other browsers and desktop:** `LiveEdgeSnapshotTest`, `LiveFirefoxSnapshotTest`, and the Linux desktop (AT-SPI) live tests.
- **macOS, live browser:** the macOS CI job runs only Store and core. The live Chrome/Edge suites above, the collaboration two-actor tests, and the desktop (AX) live tests have not run on macOS.
- **macOS, processes:** `doctor` `orphans=` reads `/proc`, so on macOS it always reports `none`; the orphan and stop process-tree behavior (including not killing a live pickleball host) is untested there. `QuietSlf4jTest` (child JVM) has not run on macOS.
- **Consumer-fixes branch, Linux and macOS:** the P1–P6 targeted classes on `3.0.0-consumer-fixes` ran on Windows only. Live classes not repeated on Linux or macOS: `HeadedFlagTest`, `HumanTierTest`, `RetestFixesLiveTest`, `ConsumerFixesFrameLiveTest`, `InputLiveTest.findRoleSearchesEveryFrame`, `PwCommandLiveTest`, `ContextLiveTest`, `ConsumerFixesP2FindTest`, `ConsumerFixesP3LiveTest`, `RefLifetimeTest`, and `LiveDesktopTest` (Windows UI Automation; the AT-SPI case still skips with `no AT-SPI bus` on Windows). Put them in the Linux full suite and the macOS live-browser gap above.
- **Spine R1, Windows only:** `UniversalEntityTest`, `DeltaWindowTest`, `HeadMaterializerTest`, `JarHashResetTest`, `EnabledFlagTest`, `ScenarioTagTableTest`, `RunVariableSyncTest`, `PkbIgnoreRoundTripTest`, `ConflictChoiceTest`, `CowSandboxTest`, and `SandboxPromoteTest`.
- **Spine R2, Windows only:** `PatternMatcherParityTest`, `CucumberIngestTest`, `SpineCucumberImportBanTest`, `NativeRunUsesStoreOnlyTest`, `StepWatchdogTest`, `StepCeilingTest`, `PhraseContextIngestTest`, `ExistsConditionTest`, `AnyParentContextTest`, `PhraseLocalContextTest`, and `BetweenRelationTest`.
- **Spine R3, Windows only:** `JavaStepHostTest`, `GlueStaticScanTest`, `NodeStepHostTest`, `NodeStepHostTsTest`, `EngineDoesNotSpawnNodeTest`, `JsStepCatalogTest`, `JsStepRunTest`, `PlaywrightTestIngestTest`, `PlaywrightSpecHostTest`, and `PlaywrightSpecIngestLiveTest`.
- **Spine R7, Windows only:** `RunSnapshotTest`, `ReportScriptTest`, `VisualTargetTest`, `PickleballExceptionTest`, and `SnapFindIndexTest` (`tools.dscode.common.composite`).

## Deferred from the 3.0.0 consumer-fixes branch (per Dan, 2026-11-25)

These items stay out of `3.0.0-consumer-fixes`. The roadmap lists them under "Not in scope". P6 records them here. Do not treat a later drive-by as permission to implement them.

| Item | Why it is deferred |
|---|---|
| 6.1.5 | Shared frame policy. The case is consumer config (L13). |
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
