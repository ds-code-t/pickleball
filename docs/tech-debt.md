# Tech debt

This page lists behavior that is not a feature. Do not document an open item as syntax. A heading marked closed describes behavior that shipped.

## Step ceilings (closed, spine R2 P3)

`steps.max_iterations` and `steps.timeout_seconds` are nullable integers. Hydrate leaves them untouched. A null column keeps the run-wide `pkb_stepRepeatMaxCount` and `pkb_stepMaxTime`. When a row sets them, the native runner and the Cucumber adapter assign `StepBase.stepMaxIterations` and `StepBase.stepTimeoutSeconds`. A block `until`, an inline `until`, and `RETRY:` use that cap. An element `wait` still uses the run-wide time cap only. A null column is not read as zero.

## Hung step watchdog (closed, spine R2 P3)

`StepWatchdog` is a daemon started by `NativeScenarioRun` and stopped in a `finally` before scenario cleanup. Every 60 seconds it looks at the current non-repeating step. When that step's `timeout_seconds`, or `pkb_stepMaxTime` when the column is null, has been exceeded, the step fails with `error: step exceeded its time cap` and `fix: raise timeout_seconds or pkb_stepMaxTime`. The watchdog does not interrupt the step thread. A hung driver call may still be running. A repeating `until` is not double-failed. `pkb_engine_log=watchdog` logs each sample and each fire. The entity is the scenario name. The log stays off unless that capability is named.

## Scenario flag steps (closed, spine R2 P3)

`RETRY:`, `IGNORE FAILURES`, `LOG FAILURES BUT CONTINUE SCENARIO`, and `RUN IF SCENARIO FINISHED` change which steps run and how a failure is recorded. `FlagSteps` and `GeneralSteps.retryStep` call `StepFlags.applyFromPhrase`. Hydrate writes the matching columns on `steps`. `IGNORE FAILURES` and `LOG FAILURES BUT CONTINUE SCENARIO` as their own steps mark the following step. A suffix `AND IGNORE FAILURES` marks that same step. `AND SCENARIO FINISHED` on `RUN IF SCENARIO PASSING` sets both passing and finished. `RUN IF SCENARIO FINISHED` runs after the scenario outcome is known. `RETRY:` runs the remainder again until it passes or the repeat cap is hit. The cap is `max_iterations` when set, otherwise `pkb_stepRepeatMaxCount`.

## Context words (closed, spine R2 P4)

`inside` and `within` use the `inside` lookup. `near` and `next to` use `near` (center distance at most 150). `following` is document order `after`. `preceding` is document order `before`. `on` and `of` use the same lookup as `in`. `between` A `and` B keeps a displayed candidate whose center lies in the axis-aligned box of the two anchor centers. A comma separates the two anchor queries, and `AND` inside an anchor stays in that query. `and` separates anchors only when the list has no comma. A missing second anchor is an error. A word inside quotes is not a relation. `pkb_engine_log=match` logs `word -> relation` for a translated context word. The log stays off unless that capability is named.

## Condition word exists (closed, spine R2 P4)

`exists` on an element uses the same result as `is present`. A hidden element matches `exists` only when `is present` would. `exists` on a value is true when the value is non-null and is not an empty reference such as `<name>`. `exists` is not another spelling of `is displayed`.

## Any parent context (closed, spine R2 P4)

`in any parent` sets `steps.context_scope` to `any-parent`. `PhraseData.getPhraseContextList` keeps every ancestor context the phrase chain already walks. The search ORs those ancestor contexts. `AllContextsScan` has no branch for this syntax.

## Phrase-local context (closed, spine R2 P4)

A trailing `with local context` sets `steps.context_mode` to `local` and `ElementMatch.searchesAllContexts` returns false, so `Loading` stays on the inherited `in`, `from`, or `for` context. A trailing `with all contexts` sets `all` and the scan runs. The default `inherit` keeps the category `ALL_CONTEXTS` flag. The mode is read before that flag.

## Inline until (closed, spine R2 P3)

A mid-line `until`, a comma `until`, and a period-ending `until` loop while the condition is true. `PhraseData.runUntilOperation` is that loop. It stops at `max_iterations` or `timeout_seconds` when the step row sets them, and at `pkb_stepRepeatMaxCount` or `pkb_stepMaxTime` when those columns are null. A block `until` ending in `:` or `?` keeps the nested-step loop and honors the same per-step ceiling. An assertion-chain member is the condition check and does not start that inline loop.

## Parallel-unsafe live tests (closed, 3.0.0-spine-parallel-tests)

On Dan's Windows PC, 2026-11-27, `:test --rerun-tasks -Ppkb.test.forks=24` ran 2118 tests in about 8 minutes and failed 79 tests in 60 classes. A rerun of those 60 classes at 12 forks ran 497 tests and failed 24 (20 classes). The same 24 methods at 1 fork ran 96 tests and failed 0. The failures were parallel contention (live Chrome/CDP attach, Playwright CDP, timing budgets, popups), not product bugs. Those 60 classes now run in `:testLive` at `-Ppkb.test.liveForks` (default 1) and are excluded from the `:test` fork pool. `:test` finalizes `:testLive`, which finalizes `:testSerial` (`SuiteDualRunTest`). CI and the box stay at 1 fork when the properties are omitted. The recommended PC command is `-Ppkb.test.forks=24 -Ppkb.test.liveForks=1`. Do not raise `liveForks` above 1 without a new measurement that includes the 20 classes that still failed at 12 forks.

## Deferred Linux and macOS tests (per Dan, 2026-11-24)

The 3.0.0 retest-fix rounds (R, S) ran only targeted tests on the Linux box and the CI jobs (Windows, Linux live Chrome, macOS Store and core). These significant runs are deferred and have not been run for those changes. They belong to the full regression tier in `docs/roadmaps/3.0.0-spine/final-regression.md`, which runs only when Dan explicitly asks. The short (Chrome + Windows) tier does not cover them:

- **Linux, full suite:** a complete `./gradlew :test` (every class, not the per-phase targeted sets) on Linux with Chrome, Edge, and Firefox present.
- **Linux, live collaboration and push:** `RetestFixesLiveTest`, `CollabFixesLiveTest`, `TwoActorRecipeLiveTest`, `OverlayRuntimeLiveTest`, `DomPushLiveTest`, `BrowserEventsLiveTest`, `BrowserEventsBidiLiveTest`, `MonitorLiveTest` in CI (they ran only on the box), plus headed (not headless) runs of the same classes.
- **Linux, other browsers and desktop:** `LiveEdgeSnapshotTest`, `LiveFirefoxSnapshotTest`, and the Linux desktop (AT-SPI) live tests.
- **macOS, live browser:** the macOS CI job runs only Store and core. The live Chrome/Edge suites above, the collaboration two-actor tests, and the desktop (AX) live tests have not run on macOS.
- **macOS, processes:** `doctor` `orphans=` reads `/proc`, so on macOS it always reports `none`; the orphan and stop process-tree behavior (including not killing a live pickleball host) is untested there. `QuietSlf4jTest` (child JVM) has not run on macOS.
- **Consumer-fixes branch, Linux and macOS:** the P1–P6 targeted classes on `3.0.0-consumer-fixes` ran on Windows only. Live classes not repeated on Linux or macOS: `HeadedFlagTest`, `HumanTierTest`, `RetestFixesLiveTest`, `ConsumerFixesFrameLiveTest`, `InputLiveTest.findRoleSearchesEveryFrame`, `PwCommandLiveTest`, `ContextLiveTest`, `ConsumerFixesP2FindTest`, `ConsumerFixesP3LiveTest`, `RefLifetimeTest`, and `LiveDesktopTest` (Windows UI Automation; the AT-SPI case still skips with `no AT-SPI bus` on Windows). Put them in the Linux full suite and the macOS live-browser gap above.
- **Spine R1, Windows only:** `UniversalEntityTest`, `DeltaWindowTest`, `HeadMaterializerTest`, `JarHashResetTest`, `EnabledFlagTest`, `ScenarioTagTableTest`, `RunVariableSyncTest`, `PkbIgnoreRoundTripTest`, `ConflictChoiceTest`, `CowSandboxTest`, and `SandboxPromoteTest`.
- **Spine R2, Windows only:** `PatternMatcherParityTest`, `CucumberIngestTest`, `SpineCucumberImportBanTest`, `NativeRunUsesStoreOnlyTest`, `StepWatchdogTest`, `StepCeilingTest`, `PhraseContextIngestTest`, `ExistsConditionTest`, `AnyParentContextTest`, `PhraseLocalContextTest`, and `BetweenRelationTest`.
- **Spine R3, Windows only:** `JavaStepHostTest`, `GlueStaticScanTest`, `NodeStepHostTest`, `NodeStepHostTsTest`, `EngineDoesNotSpawnNodeTest`, `JsStepCatalogTest`, `JsStepRunTest`, `PlaywrightTestIngestTest`, `PlaywrightSpecHostTest`, and `PlaywrightSpecIngestLiveTest`.
- **Spine R4, Windows only:** `VerbDispatchTest`, `McpDirectHotPathTest`, `VerbCatalogTest`, `ElementRecordTest`, `ArgsFileTest`, `AgentEnvelopeStoreTest`, `RouteModifyBodyTest`, `ExecuteStepsPwTest`, `DesktopLaunchTest`, `DesktopScreenshotVerbTest`, and `DesktopLaunch`.
- **Spine R7, Windows only:** `RunSnapshotTest`, `ReportScriptTest`, `VisualTargetTest`, `PickleballExceptionTest`, `SnapFindIndexTest` (`tools.dscode.common.composite`), `SnapshotRetainTest`, and `CrossStoreTest`.
- **Spine R5, Windows only:** `WorkbenchUiServerTest`, `StoreSqlReadOnlyTest`, `InjectSessionTest`, `SessionAddressTest`, `ChatChannelTest`, `AskProgressPointTest`, `WorkbenchUiLicenseTest`, `LeaseAndBlockTest`, `UiPageDispatchTest`, `ChatFollowStreamTest`, `WorkbenchUiJarAssetsTest`, `GeneratedPageAccessTest`, `OpenWindowRendererTest`, `LeaseGateTest`, `InjectLivePageTest`, `OverlayReceiptAskTest`, `InputBlockLivePageTest`, `WorkbenchPagesTest`, `SessionRequiredTest`, `NodeDesignDocsTest`, `PageJvmRouteTest`, `OverlayAckLiveTest`, `RendererShownRunTest`, and `RendererStoreSessionTest`.
- **Spine R8, Windows only:** `WarmClassLoaderTest`, `WarmColdParityTest`, `IncrementalGlueCompileTest`, `ResourceGateTest`, `BrowserVersionResolverTest`, `BrowserCacheAndLocalVersionTest`, `SeleniumBidiCommandTest`, `DebugPortOwnerTest`, `DebugPortAttachTest`, `SnapshotContextGateTest`, `WarmCdpTest`, `DesktopTreeCacheTest`, `WindowsStructureChangedTest`, `WarmGlueCompilePathTest`, `SubprocessStepTest`, `SubprocessScenarioTest`, `WarmGlueScenarioTest`, `BidiCliSessionTest`, `WindowsStructureLiveTest`, `WarmRebuildRestartTest`, `CdnDownloadRereadTest`, `DebugPortQuitTest`, `WarmWorkerReloadTest`, `CdnCacheFallbackTest`, and `SpineR8FixBack3DocsTest`, `SpineHelpMergeTest`, `SpineRouteCliTest`, `BidiSessionGateTest`, and `SpineCommandCoverageTest`.
- **Spine R6, Windows only:** `SessionExemptTest`, `BranchForkTest`, `BranchColorTest`, `PreStepHookTest`, `ForkGluePutTest`, `BranchTreeTest`, `SpineR6PageLive`, `SpineR6FixBack1DiscardTest`, and `SpineR6FixBack1PageControlsTest`.
- **macOS, Safari:** channel `safari` selects Selenium `safaridriver` and the Windows test does not start a process. A live Safari run on macOS has not been executed.

## Deferred from the 3.0.0 consumer-fixes branch (per Dan, 2026-11-25)

These items stay out of `3.0.0-consumer-fixes`. The roadmap lists them under "Not in scope". P6 records them here. Do not treat a later drive-by as permission to implement them.

| Item | Why it is deferred |
|---|---|

R5 P2 and P3 already removed 6.5.2 Point, 6.5.3 typed card answers, 6.5.7 overlay read receipts, and 6.6.4 runtime injection on attached windows from this table. Point and typed answers live with `AskProgressPointTest` and the spine chat section of `docs/dynamic-control-api.md`. Overlay read receipts live with `OverlayReceiptAskTest` and `OverlayAckLiveTest`. Attached-window injection lives with `InjectLivePageTest` and `PageJvmRouteTest`. This pass does not put those rows back. R8 P3 and P4 removed 6.1.5, 6.3.6, and 6.7.3 because shared frame policy, warm CDP at start, and the UIA tree cache shipped. Those rows stay out.

## Regression tier filters (open, 2026-11-27)

The short (Chrome + Windows) tier in `docs/roadmaps/3.0.0-spine/final-regression.md` needs a filter that drops non-Chrome browser tests. None exists. No test class uses JUnit `@Tag`. No Gradle property maps to `excludeTags`. `--tests` can only include. Today these run or skip only because of what is installed:

- Firefox live methods skip on Dan's PC only because Firefox and `geckodriver` are not installed: `LiveFirefoxSnapshotTest.liveFirefoxSnapshotHasScreenshotSourceBoxesAndPremap`, `BrowserProtocolsLiveTest.firefoxListsBidiAndNoCdpWhenPresent`, and `BrowserEventsBidiLiveTest.firefoxBidiWhenPresent`. They would run in the short tier once Firefox is installed.
- Edge live snapshot methods skip on Windows only because their `edgeBinary()` reads Linux paths (`:`-separated `PATH`, `/opt/microsoft/msedge/msedge`): `LiveEdgeSnapshotTest.liveEdgeSnapshotWhenTheBinaryIsPresent` and `SnapshotCoverageTest.liveEdgeWhenPresent` (`dom-engine`). The same lookup means the full tier gets no Windows Edge snapshot coverage either.
- Popup host and renderer window: on Windows `PopupBrowserLocator` prefers Edge from the registry. `AgentSurfacePopupLiveTest`, `PopupChatLiveTest`, and the `WorkbenchUiServer` window use Edge unless `PKB_AGENT_POPUP_BROWSER` (or `pkb_agent_popup_browser`) names Chrome. The short tier sets that variable. It is a workaround, not a tier filter.
- Edge fallback when Chrome is missing: `OverlayAckLiveTest`, `PageJvmRouteTest`, `BidiCliSessionTest`, and the `SpineR6PageLive` classes (`SpineR6FixBack1DiscardTest`, `SpineR6FixBack1PageControlsTest`) quietly switch to Edge. No switch makes them fail instead. The short tier requires Chrome to be installed.
- Consumer: no gap. `-Dpkb_runvars.pkb_browser=CHROME_HEADLESS` selects Chrome for `@all`.
- Non-Windows tests: no gap for the short tier. Linux, macOS, and POSIX-only tests (`LiveDesktopSnapshotTest`, `MacAccessibilityScriptTest`, `PopupSandboxFallbackTest`, and the Linux and macOS branches of `SnapshotCoverageTest.liveDesktopWhenThePlatformExists`) skip themselves on Windows through assumptions.

Proposed mechanism, not implemented: tag browser-specific live methods (`browser-firefox`, `browser-edge`, `browser-safari`) and OS-only methods. Add a Gradle property, for example `-Ppkb.regression=short`, that sets `excludeTags` on every `Test` task and sets `PKB_AGENT_POPUP_BROWSER` to Chrome. Make the Edge fallbacks fail when that property is set. Fix `edgeBinary()` to find Edge on Windows for the full tier.

## TypeScript package in the Workbench UI bundle (reinvestigate, per Dan, 2026-11-26)

The `typescript` npm package is pinned in `workbench-ui/package.json` and packed into the jar next to `monaco-editor`, but nothing loads it today. Monaco has its own TypeScript language service, and `.ts` step definitions run through `NodeStepHost` on Node's built-in type stripping. Dan chose to keep it for now. Later, decide whether to drop it (smaller jar, one fewer pinned version) or use it as a fallback that converts `.ts` step files Node's type stripping cannot run (enums, namespaces, parameter properties, decorators) to plain JavaScript first.

## Spine merge follow-ups (open, 2026-11-26)

- Cold-child hooks: the R8 cold child scenario (`SnapshotScenario`) has no tags and fires its own before/after hooks, so hooks may run in both the parent and the child, and tagged hooks may miss in the child.
- Renderer lease identity: the Node renderer sends no agent id and counts as `agent-1`, so a `ui` lease held by `agent-1` does not block the person in the renderer, and any page request can claim the holder with `--agent=`.
- Warm-spine marker seam: `WarmWorkerReloadTest` writes the warm-spine marker with the test JVM's pid instead of a real `spine start` process.
- Session rule scope for help/version/export-guidance (Dan, 2026-11-26). Exempt: `engine help`, `domengine version`, family help (`store`, `engine`, `domengine`, `dashboard`, `cross`, `spine`, `bidi`, `browser`, `log`, `log-tail`, including `<family> help`, `help <family>`, and `--help` or `-h`), and plain `export-guidance`. The allow-list is `VerbDispatch.SESSION_EXEMPT`. `export-guidance --warm` still needs a session because it starts the controller. `export-guidance <dir>` still treats the output folder as the project for the warm path, so that path is refused until `spine session new` has written a session there.
- Revisit the session-rule exemption for help/version/export-guidance. Check later whether exempting those commands was right.

## Spine R6 follow-ups (open, 2026-11-27)

- Help printing: `EngineCli`, `SpineCli`, `BrowserPorts`, and `SeleniumBidiCommands` print help when any argument is exactly `help`, `--help`, or `-h`, so for example `engine step --text=x help` prints help instead of running. The `VerbDispatch` session gate itself stays exact.
- Promote flatten: `HeadMaterializer.flatten` in `CowSandbox.promote` now also changes R1 `store sandbox promote`, which flattens the destination head immediately instead of leaving it to a later materialization.
- The `store branch` and `engine map` verbs are tested in-JVM through `VerbDispatch`, not in a separate CLI JVM (`PickleballWorkbenchLauncher` main).
- No end-to-end `engine run --sandbox=<fork>` test of the pre-step hook; `PreStepHookTest` binds the fork with `StoreScope.bind` and calls `NativeScenarioRun` directly.
- The page only offers `store branch link ... --off`; there is no `--on` control.


## Final regression findings (open, 2026-11-27)

Open items from the 3.0.0-spine final regression on Dan's Windows PC, recorded at `ef54a332e`. They are not passes.

- Renderer lease identity. The Node renderer still sends no agent id and counts as `agent-1`, so a `ui` lease held by `agent-1` does not block the person in the renderer, and any page request can claim the holder with `--agent=`. The sweep marks this as a gap. The original note is under [Spine merge follow-ups (open, 2026-11-26)](#spine-merge-follow-ups-open-2026-11-26).
- R4 ElementRecord roadmap contradiction. `docs/roadmaps/3.0.0-spine/R4-cli-mcp-universal-surface-verb-inventory.md` still says extra keys go under `extra` unless `--fields`. The shipped record passes those keys through. `AGENTS.md` and `docs/release-notes.md` describe the shipped behavior. The roadmap was not edited. See the docs-audit row in `docs/maintainer/3.0.0-spine/final-regression-scoreboard.md`.
- The first root `:test` run failed 103 tests. The failures-and-fixes table groups them under eleven `a1c51714f` rows and does not name every method. No test methods were removed. The green `:test` on `c1bec073e` is 2119 tests, 2112 passed, 0 failed, 7 skipped. A later pass that needs the original method list should take it from the Surefire XML of the first run on `4383ed02e`, if that report is still on the PC.

A new environmental failure from a later regression goes under this section.

## act --kind=scroll (closed, 3.0.0-spine-act-scroll)

`act --kind=scroll` maps to the dom-engine `scroll` verb. With `--query` or `--ref` the node is scrolled into view, then wheeled by `--dx`/`--dy` when a delta is set (omitted deltas are 0, into-view only). Without a target the page scrolls by `--dx`/`--dy`; when both are omitted, `dy` is one viewport height. The reply reports `dx`, `dy`, `into_view`, `scrollX`, and `scrollY` (and `scrollLeft`/`scrollTop` for an element). Desktop scroll is not supported and returns an error with a fix; it is not `unknown kind`. Human Mode allows the kind. This closes the leftover from the doc-gaps branch.

## Doc and help gaps (2026-11-27)

Closed on `3.0.0-spine-doc-gaps`. Help, `VerbTiers`, and `HumanLock` now name the same 13 human verbs. Popup usage, the `ws-route` rules shape, `@file` on `ask --say`, `html-insert --text`, and `cdp --params`, the `--agent` lock owner, and the catalog unclear notes listed in the build prompt are documented from the code that implements them.

- `start` no longer forwards a Maven classworlds-only `java.class.path` to `LiveBrowserHost`. The 2026-11-23 `ClassNotFoundException` was that old path. The chooser is `ConsumerReadyCommands.hostClasspath`.
