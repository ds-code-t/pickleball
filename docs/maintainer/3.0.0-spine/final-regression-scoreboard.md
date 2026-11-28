# Final regression scoreboard — 3.0.0-spine

Pass: full Windows regression, R1 to R8 sweep, and cross-roadmap docs audit.
Prompt: `prompts/build-3.0.0-spine-final-regression.md`.
Plan: `docs/roadmaps/3.0.0-spine/final-regression.md`.
Worktree: `C:\Users\Admin\IdeaProjects\pkb-spine` on DESKTOP-TUUEDJG.
Branch: `3.0.0-spine` only. No Linux or macOS runs. No GitHub Actions, pull request, or push to `3.0.0`.
Started from `253257e1` (prompt commit on `fa107755`).
Host Java processes seen before the pass: pid 48240 and pid 50896, both `java.exe` started 2026-11-23 with a junit temp argfile. They are not a Gradle publish and not `mvnw`. They were left running.

## Test totals

First-run rows are filled when that command finishes. Final-run rows are the green run on the final head, or the same row when the first run was already that head.

| Command | Run | Total | Passed | Failed | Skipped | Duration | Head | Report timestamp |
|---|---|---:|---:|---:|---:|---|---|---|
| `.\gradlew.bat :test --console=plain` | first | 2096 | 1986 | 103 | 7 | 1h 27m 50s | 4383ed02e | 2026-11-27T08:00Z–09:24Z |
| `.\gradlew.bat :test --console=plain` | final | 2116 | 2109 | 0 | 7 | 1h 30m 53s | 1309439a7 | 2026-11-27T21:54:02Z–23:24:30Z |
| `.\gradlew.bat :dom-engine:test --console=plain` | first | 260 | 248 | 0 | 12 | 1m 1s | 4630c638f | 2026-11-27T23:26:19Z–23:27:14Z |
| `.\gradlew.bat :dom-engine:test --console=plain` | final | 260 | 248 | 0 | 12 | 1m 1s | 4630c638f | 2026-11-27T23:26:19Z–23:27:14Z |
| `.\gradlew.bat :pickleball-workbench:test --console=plain` | first | 295 | 294 | 1 | 0 | 27s | 4630c638f | before 2026-11-27T23:30:38Z |
| `.\gradlew.bat :pickleball-workbench:test --console=plain` | final | 295 | 295 | 0 | 0 | 33s | 2995b98fa | 2026-11-27T23:32:19Z–23:32:44Z |
| `.\gradlew.bat :pickleball-control-protocol:test --console=plain` | first | 122 | 121 | 0 | 1 | 9s | 334f2dba8 | 2026-11-27T23:33:23Z–23:33:26Z |
| `.\gradlew.bat :pickleball-control-protocol:test --console=plain` | final | 123 | 123 | 0 | 0 | 15s | 3684931ed | 2026-11-27T23:40:08Z–23:40:13Z |
| `.\gradlew.bat :pickleball-control-api:test --console=plain` | first | 31 | 31 | 0 | 0 | 17s | 334f2dba8 | 2026-11-27T23:34:22Z–23:34:25Z |
| `.\gradlew.bat :pickleball-control-api:test --console=plain` | final | 31 | 31 | 0 | 0 | 17s | 334f2dba8 | 2026-11-27T23:34:22Z–23:34:25Z |
| `.\gradlew.bat publishToMavenLocal` | first | n/a | n/a | n/a | n/a | 24s | edcf008e4 | 2026-11-27T23:42:16Z–23:42:40Z |
| `.\gradlew.bat publishToMavenLocal` | final | n/a | n/a | n/a | n/a | 21s | ed8a70980 | 2026-11-28T00:28:29Z–00:28:50Z |
| `mvnw @all` | first | 443 | 436 | 7 | 0 | 22:09 min | b974f9b8f | 2026-11-27T23:47:54Z–2026-11-28T00:10:03Z |
| `mvnw @all` | final | 439 | 439 | 0 | 0 | 22:18 min | ed8a70980 | 2026-11-28T00:29:13Z–00:51:31Z |
| `.\gradlew.bat verifyStrictControllerIsolation --console=plain` | first | n/a | n/a | n/a | n/a | 15s | ed8a70980 | 2026-11-28T00:52:48Z–00:53:03Z |
| `.\gradlew.bat verifyStrictControllerIsolation --console=plain` | final | n/a | n/a | n/a | n/a | 15s | ed8a70980 | 2026-11-28T00:52:48Z–00:53:03Z |
| `python scripts/verify_agent_contract.py` | first | n/a | n/a | n/a | n/a | 0.23s | ed8a70980 | 2026-11-28T00:56:25Z–00:56:25Z |
| `python scripts/verify_agent_contract.py` | final | n/a | n/a | n/a | n/a | 0.23s | ed8a70980 | 2026-11-28T00:56:25Z–00:56:25Z |
| `python scripts/refresh_agent_index.py --check` | first | n/a | n/a | n/a | n/a | 0.64s | ed8a70980 | 2026-11-28T00:56:25Z–00:56:26Z |
| `python scripts/refresh_agent_index.py --check` | final | n/a | n/a | n/a | n/a | 0.64s | ed8a70980 | 2026-11-28T00:56:25Z–00:56:26Z |
| `python scripts/sync_consumer_guidance.py --check` | first | n/a | n/a | n/a | n/a | 0.08s | ed8a70980 | 2026-11-28T00:56:26Z–00:56:26Z |
| `python scripts/sync_consumer_guidance.py --check` | final | n/a | n/a | n/a | n/a | 0.08s | ed8a70980 | 2026-11-28T00:56:26Z–00:56:26Z |

`mvnw @all` is `.\maven-consumer-project\mvnw.cmd -f maven-consumer-project\pom.xml -U test -Dpkb_runvars.pkb_browser=CHROME_HEADLESS -Dpkb_runvars.pkb_tags=@all`. On this PowerShell host the recorded runs inserted `--%` before `-f` so the dotted `-Dpkb_runvars` arguments reached Maven. A launch without `--%` exited in 0.061s with `Unknown lifecycle phase ".pkb_browser=CHROME_HEADLESS"`. That parse is not a suite result.

Maven-local wait: checked before the focused re-run. `tasklist` showed only java.exe pids 48240 and 50896 (started 2026-11-23). No Gradle publish and no `mvnw`. Wait was 0. `:test` depends on `publishToMavenLocal`, so that focused re-run publishes to the local repository. That publish is not the Part A `publishToMavenLocal` row. Checked again before the Part A publish. Those same two pids were the only Java processes. No other worktree was publishing and no `mvnw` was running. Wait was 0. The Part A publish exited 0 in 24s (28 tasks, 11 executed) on `edcf008e4`. Checked again before the republish on `ed8a70980`. Those same two pids were still the only Java processes. Wait was 0. That republish exited 0 in 21s (28 tasks, 9 executed). The consumer jar is `pickleball-3.0.0.jar`. The first `mvnw` row ran while git HEAD was `b974f9b8f` and executed the jar published from `edcf008e4`. The final `mvnw` row executed the jar published from `ed8a70980`.

The first `mvnw` Surefire total is 443 (Cucumber engine 426 with 1 failure and 6 errors, plus `LazyBooleanSideTest` 17 passed). The final total is 439 (Cucumber engine 422 passed, plus the same 17). The four extra first-run tests are parent `a scenario failed` errors from `NativeSuiteExecution.walk`. They are not separate scenarios. Both runs skipped 0 tests. `LazyBooleanSideTest` prints an Aviator stack on a passing test; the class XML is 17 tests, 0 failures, on both runs. The final Maven line is `BUILD SUCCESS`, total time 22:18 min, finished `2026-11-27T17:51:31-07:00`.

`verifyStrictControllerIsolation` is not a JUnit suite. The first run was already green, so the final row is that same run: `BUILD SUCCESSFUL` in 15s, 27 tasks (12 executed), exit 0, on `ed8a70980`. The three Python checks exited 0 on that same head while this scoreboard and the 3.0.0 release-notes lead were uncommitted. They were run again immediately before the push.

The first `:test` ran on head `4383ed02e`. The working tree already held uncommitted fixes. The test JVM compiled before those edits, so this row is the committed head.

Root `:test` skips (7), same on the first run and the final run, all pre-existing assumptions:

| Class | Count | Reason |
|---|---:|---|
| `PopupSandboxFallbackTest` | 2 | `the fake browser is a POSIX shell script` (`PopupHost.windows()`) |
| `LiveFirefoxSnapshotTest` | 1 | `firefox is not on PATH` |
| `BrowserEventsBidiLiveTest` | 1 | `no Firefox binary on this machine` |
| `BrowserProtocolsLiveTest` | 1 | `no Firefox binary on this machine` |
| `DesktopSameVerbTest` | 1 | `AT-SPI tree has no named window for a process id` (live attach returned a tree with no pid and name) |
| `WorkbenchShellQuotingTest` | 1 | `pwsh is not on PATH` |

`:dom-engine:test` skips (12), all pre-existing assumptions:

| Class | Count | Reason |
|---|---:|---|
| `DesktopXPathAccelTest` | 1 | `no desktop driver is running on this machine` |
| `GridDriversTest` | 1 | `no FlaUI.WebDriver, NovaWindows, WinAppDriver, or Mac2 driver is running` |
| `LiveDesktopSnapshotTest` | 1 | `no AT-SPI bus` |
| `LiveDesktopTest` | 1 | `no AT-SPI bus` |
| `MacAccessibilityScriptTest` | 1 | `osacompile exists only on macOS` |
| `WaylandDetectionTest` | 1 | `the attach path refusal is Linux only` |
| `OnnxOcrRuntimeTest` | 1 | `live OCR skipped: set PKB_OCR_LIVE_DIR` |
| `ChromeProcessTest` | 3 | `/bin/sh is missing` |
| `LiveEdgeSnapshotTest` | 1 | `microsoft-edge is not on PATH` |
| `SnapshotCoverageTest` | 1 | `microsoft-edge is not on PATH` |

The workbench final row used `--rerun-tasks` so Gradle executed the tests on commit `2995b98fa`. The plain command on that commit was `UP-TO-DATE` and `BUILD SUCCESSFUL` in 5s. No workbench tests were skipped.

`:pickleball-control-protocol:test` first-run skip (1), fixed before the final row:

| Class | Count | Reason |
|---|---:|---|
| `DirectControlProviderTest` | 1 | `realCompositeAttachesQueriesAndCloseLeavesChromeRunning` aborted with `skipped: Chrome is not present`. `chromeBinary()` split `PATH` on `:` and looked for Unix names, so it missed `C:\Program Files\Google\Chrome\Application\chrome.exe`. |

The final protocol row used `--rerun-tasks` so Gradle executed the tests on commit `3684931ed` (123 tests, 0 failures, 0 skipped). The plain command on that commit was `UP-TO-DATE` and `BUILD SUCCESSFUL` in 4s. No protocol tests were skipped. `:pickleball-control-api:test` had no skips. That module's sources are unchanged by `3684931ed`.

## Failures and fixes

| Test or scenario | Error | Root cause | Fix SHA | Reproducing test | Before | After |
|---|---|---|---|---|---|---|
| BetweenLiveFindTest.liveStepFindKeepsTheSaveButtonBetweenTheTwoAnchors | expected `[in]`, was `[]` | `Relations.splitAnchors` split `AND` inside each comma-separated anchor, so both anchors were the first button and the box collapsed to a point | a1c51714f | BetweenRelationTest.xmlPageSourceKeepsTheSaveButtonBetweenTheAnchors, BetweenLiveFindTest | fail | pass (3/3 live, 3/3 relation, 2026-11-27) |
| WorkbenchAgentUsabilityTest.deprecatedFlagAliasesNormalizeToTheCanonicalFlag | golden catalog differed from `WorkbenchCliHelp.catalog()` | `src/test/resources/workbench-help-golden.txt` was the previous 18-line catalog | a1c51714f | same method | fail | pass (1/1, XML 2026-11-27T10:52:28.502Z, 0.046s) |
| SuiteDualRunTest.areaBMatches and ignoreFailuresMatches | adapter scenario FAILED, native PASSED; later undefined steps showed FAIL ERROR ids 3 vs 103 | Cucumber emitted ignore, log-and-continue, and RETRY attempts as failed result steps and burned throwable ids | a1c51714f | SuiteDualRunTest.ignoreFailuresMatches, retryKeepsTheGherkinLine, definedRetryMatches, areaBMatches | fail | pass (XML 2026-11-27T15:43:00Z, 5 tests, 0 failures, 58.9s) |
| SuiteDualRunTest.consumerFeaturesMatch call lookup | `No scenarios matched` for `@%TokenCall` on the Cucumber side | call and component files are stored `enabled=0`, and the adapter treated that as absent while the native engine read the file | a1c51714f | CallLibraryScanTest.explicitLibraryPathLoadsWhileAFeatureRunSkipsEnabledZero; SuiteDualRunTest.serviceCallExecutionMatches; SuiteDualRunTest.consumerFeaturesMatch | fail | pass (CallLibraryScanTest 0.424s; service-call feature 26.868s; consumerFeaturesMatch 3643.8s, 0 failures, XML start 2026-11-27T15:46:22Z) |
| ClosedShadowResolveTest closed-shadow find | expected `closed`, was empty | `ElementRecord` dropped `shadow` and `shadow_path` that the find row already had | a1c51714f | ElementRecordTest.presentRowFieldsStayOnTheRecord; ClosedShadowResolveTest | fail | pass (Surefire 2 tests, 0 failures, 7.455s, 2026-11-27) |
| PlaywrightSpecIngestLiveTest.specOpensTheLocalPage | Gherkin parser rejected `test.describe` | store ownership used `pkb_store_project` or the working directory, so a spec in the engine project stayed on the file loader | a1c51714f | StoreFeatureOwnershipTest.theEngineProjectOwnsASpecWhenTheConfiguredProjectIsElsewhere; PlaywrightSpecIngestLiveTest | fail | pass (Surefire 1 test, 0 failures, 1.565s, 2026-11-27) |
| BrowserEventsLiveTest initialization | `DevToolsActivePort` sharing violation | the test helper read the port file once while Chrome still held it, and the exception left the wait | a1c51714f | PlaywrightCdpLiveTest.startChrome retry; BrowserEventsLiveTest | fail | pass (Surefire 5 tests, 0 failures, 7.227s, 2026-11-27) |
| WorkbenchCliCoverageGateTest | `covers_command_ui` had no behavior token | the real `ui` launch moved into `bareUiOpensAndStaysUp`, and the gate only reads the `covers_*` body | a1c51714f | WorkbenchCliCoverageGateTest | fail | pass (focused class, with the 10-test batch, 2026-11-27) |
| ScenarioObjectHookTest | run id `warm-first` is already finished | in-process `WorkbenchDirectRun` left `pkb_run_id` set | a1c51714f | WorkbenchWarmFallbackTest.inProcessRunRestoresThePreviousRunId; ScenarioObjectHookTest | fail | pass (same 10-test batch, 2026-11-27) |
| StoreSandboxPruneTest | `store run --sandbox=linbox` exit 1, no scenario lines | a cow child stores no parent file bytes, and the run read only the child | a1c51714f | StoreSandboxPruneTest | fail | pass (focused with WarmWorkerReloadTest, BUILD SUCCESSFUL, 1m 2s, 2026-11-27) |
| WarmWorkerReloadTest | second mark stayed `1` after a classpath prepend, and the first run was undefined before that | `.pickleball/spine/classes` on the worker application classpath pinned `glue.Mark`; the warm loader was not used for `StepRegistry.bind` | a1c51714f | WarmWorkerReloadTest | fail | pass (same focused run, 1m 2s, 2026-11-27) |
| WorkbenchMcpRetrievalTest.everyToolDescriptionIsOneShortLine | `workbench_browser_page` description length 175; later `ui` length 164. `expected: <true> but was: <false>`. Module: 295 completed, 1 failed, BUILD FAILED in 27s | Each description gets ` Tier: <tier>.` appended, and the check allows 160 characters on one line. Eight blurbs were already over that cap | 2995b98fa | WorkbenchMcpRetrievalTest.everyToolDescriptionIsOneShortLine | fail (head 4630c638f) | pass (class XML 2026-11-27T23:30:38.673Z, 2 tests, 0 failures, 0.19s). Module on 2995b98fa: 295 tests, 0 failures, 0 skipped, BUILD SUCCESSFUL in 33s |
| SuiteDualRunTest.consumerFeaturesMatch and serviceCallExecutionMatches on 7380c4407 | adapter FAILED, native PASSED. `consumerFeaturesMatch`: 8 features diverged. `serviceCallExecutionMatches`: 366 divergences. Full `:test` on that head: 2116 completed, 2 failed, 7 skipped, 1h 30m 52s | A jar-hash hydrate at suite start ran before the test created `src/test/resources/calls`, wiped leftover call rows, and stored 0 files because this repo has no default hydrate directory. The adapter then missed the call library | 2e032ba68 | SuiteDualRunTest.serviceCallExecutionMatches and consumerFeaturesMatch call `hydrateCallLibrary` after the link | fail (head 7380c4407) | pass (class XML start 2026-11-27T20:45:35.181Z, 12 tests, 0 failures, 3866.224s; serviceCallExecutionMatches 23.085s; consumerFeaturesMatch 3586.704s; BUILD SUCCESSFUL in 1h 4m 42s). Full `:test` on 1309439a7: 2116 tests, 0 failures, 7 skipped |
| DirectControlProviderTest.realCompositeAttachesQueriesAndCloseLeavesChromeRunning | first protocol run skipped it: `Assumption failed: skipped: Chrome is not present`. `chromeLookupAgreesWithTheSharedFinder` then failed: `protocol lookup missed C:\Program Files\Google\Chrome\Application\chrome.exe` | `chromeBinary()` split `PATH` on `:` and looked for Unix chrome names. It never checked the Windows install path that `BrowserSession.findChrome()` uses | 3684931ed | DirectControlProviderTest.chromeLookupAgreesWithTheSharedFinder; DirectControlProviderTest.realCompositeAttachesQueriesAndCloseLeavesChromeRunning | skip on 334f2dba8; lookup test fail before the fix | pass (module XML 2026-11-27T23:40:08Z–23:40:13Z, 123 tests, 0 failures, 0 skipped). The live method took 3.1s in the class run and is not skipped in the module XML |
| `@area-b-record-entity` Record a business entity during a tracked step | `error: RECORD ENTITY needs a tracked step` / `fix: set pkb_store_track=true`. The next ensure never saw `Account#ada`. Parent `a scenario failed` | `@all` does not set `pkb_store_track` or `pkb_run_id`, so `shouldTrack()` stays false and `RecordedEntity.write` had no step frame. The default stays off | ed8a70980 | ReportScriptTest.recordEntityOpensAStepWhenTheSuiteDidNotTrack | fail before the fix (`IllegalStateException` at `RecordedEntity.write`). Consumer first run on jar `edcf008e4` | pass. The focused class run exited 0 (`shouldTrack()` still false, `pkb_run_id` still blank, explicit `pkb_store_track=false` still throws). Consumer final run: ensure `Account#ada` equals `Account#ada` |
| `@area-until-as-if` A comma until selects a branch the way if does | `Until loop exhausted pkb_stepRepeatMaxCount 100` for `, until "abc"` | A one-element comma until was rewritten to a truthy `if` and then looped while the literal stayed true | ed8a70980 | NativeDynamicExecutionTest.oneElementCommaUntilSelectsABranchOnce. PhraseSyntaxTest empty-body until tests still exhaust the cap | fail before the fix (expected PASSED, was FAILED with the exhaustion message). Consumer first run on jar `edcf008e4` | pass. Focused `NativeDynamicExecutionTest` and `PhraseSyntaxTest` exited 0. Consumer final run: `untilYes` is `yes`, `untilNo` stays `untouched` |
| LoadingContextChecks four displayed checks | one `AssertionError`: `depthCapWarnsOnceAndSkipsTheDeeperFrame`, `frameCapWarnsOnceAndStillUsesAMatchInsideTheCap`, `nestedFramesCountAndAButtonThereDoesNot`, `scanRestoresTheFrameItStartedIn`. `Cannot invoke WebElement.isDisplayed() because "<local7>" is null` | An all-contexts hit in another frame is `ElementWrapper.foreignPresence` with a null element. `ElementWrapper.isDisplayed` returns true. `ElementReads.displayed` unwrapped it and called `isDisplayed` on null | ed8a70980 | AllContextsPositionTest.foreignPresenceCountsAsDisplayed | fail before the fix (NPE at `ElementReads.displayed`). Consumer first run on jar `edcf008e4` | pass. Focused class exited 0. Consumer final run: all four methods logged PASS, and the other loading checks stayed PASS |

## Sweep

Status is `present`, `fixed`, or `gap`. Open follow-ups in `docs/tech-debt.md` stay logged and are not recorded as passes. Rows below are the roadmap and scoreboard items. The sweep fills production caller, test classes, and confirms each scoreboard SHA is on this branch.

| Item | Status | Production caller | Test classes | Scoreboard SHA |
|---|---|---|---|---|
| R1 P1 pin, deltas, head, undo | present | `store pin`, `store delta`, `store head` (`HeadMaterializer`) | UniversalEntityTest, DeltaWindowTest, HeadMaterializerTest, StoreSchemaTest | 6615c459560ae94e64514c37ed8ca6544ece0c28 |
| R1 P2 jar hash, flags, tags, run variables, @pkb-ignore | present | `StoreHydrator`; `store enable`, `store var`, `store tag` | JarHashResetTest, EnabledFlagTest, ScenarioTagTableTest, RunVariableSyncTest, PkbIgnoreRoundTripTest, StoreHydrateTest | bac0cc706e2f69ae75ac0f79e48b9c8c698b85fe |
| R1 P3 conflict choice | present | `store conflicts choose` | ConflictChoiceTest, StoreExtractTest | 9e5ff24951b5f8467f12447d7f96a824e86f9a2d |
| R1 P4 cow sandbox | fixed | `CowSandbox` via `store sandbox`; `StoreQuery.file` reads parent bytes | CowSandboxTest, SandboxPromoteTest, SandboxIndependenceTest, StoreSandboxCliTest | 31fd9de9bd95773d2d956ccc1c723a90e689bc74 |
| R2 P1 ingest and native matcher | present | `StoreHydrator` ingest and `PatternMatcher` | PatternMatcherParityTest, CucumberIngestTest, SpineCucumberImportBanTest | 6b2c0f2221c13123646630fdc4fe75d780a25c5a |
| R2 P2 native runs read rows | fixed | `PickleballEngine` and `StoreScenarioSource`; explicit call path hydrate | NativeRunUsesStoreOnlyTest, ParityHarnessTest | 209951b916fdc53abc1688c44da8bcecbad03a63 |
| R2 P3 ceilings, watchdog, flags, inline until | fixed | `PhraseData.runUntilOperation` (a one-element comma until selects once), `StepWatchdog` | PhraseSyntaxTest, StepWatchdogTest, StepCeilingTest, NativeDynamicExecutionTest | 85bda5df1a66c686087740983f827d83432eed7b |
| R2 P4 context words, exists, any parent, between | fixed | `Relations.splitAnchors` and `ElementOperations` | BetweenRelationTest, PhraseContextIngestTest, ExistsConditionTest, AnyParentContextTest, PhraseLocalContextTest | fd7c9053da9454a5c0bdfb2a0482a7faf5ec73c0 |
| R3 P1 Java host and static scan | present | `JavaStepHost`, `GlueStaticScan` | JavaStepHostTest, GlueStaticScanTest | 45e986209b2bf8e6c20c9fe49ccb1081bdfc4511 |
| R3 P2 Node step host | present | `NodeStepHost` behind `ForeignStepHosts` | NodeStepHostTest, NodeStepHostTsTest, EngineDoesNotSpawnNodeTest | e7cfb4ca4e085680ae46e8d57000c91907c77d3f |
| R3 P3 JS and TS defineStep | present | `store glue put` and `NodeStepHost` | JsStepCatalogTest, JsStepRunTest, StoreGlueCompileTest, GlueEditTest | f35d511d92074b35eee48932475a04bef203b941 |
| R3 P4 Playwright spec ingest | fixed | `PlaywrightTestIngest` and `PlaywrightSpecHost` | PlaywrightTestIngestTest, PlaywrightSpecHostTest, PlaywrightSpecIngestLiveTest | 3506a600def643d9d6dc1357e1f6322ba8513c4f |
| R4 P1 VerbDispatch and direct MCP | present | `VerbDispatch` on `POST /v1/verb` | VerbDispatchTest, McpDirectHotPathTest, WorkbenchMcpServerTest | 4494b3d623aa9dfc57db6ed173d703fb941105d1 |
| R4 P2 verb catalog | present | `VerbCatalog` | VerbCatalogTest, WorkbenchCliCoverageGateTest, GuidanceCommandGoldenTest | baa33a1b2d20afd856a80fb7439e45d4f5b37f72 |
| R4 P3 envelope, element record, args file | fixed | `AgentEnvelope`, `ElementRecord`, args file | ElementRecordTest, ArgsFileTest, AgentEnvelopeStoreTest | 39d6d3ad9f42863ad9c7297d72adcb81c65d00b4 |
| R4 P4 route modify, pw steps, desktop launch and screenshot | present | `DesktopLaunch`, `desktop screenshot`, `pw` steps | RouteModifyBodyTest, ExecuteStepsPwTest, DesktopLaunchTest, DesktopScreenshotVerbTest | 6361afdbbe438c69add534b1ef3e744a2dd39026 |
| R5 P1 Node renderer shell | present | Node renderer `/store` bridge | WorkbenchUiServerTest, StoreSqlReadOnlyTest | 3c01f5a4fc39417525d8d986f1ab61b9c22edb73 |
| R5 P2 overlay, inject, sessions | present | `inject` and `SpineSession` | InjectSessionTest, SessionAddressTest, NativeHighlightRouteTest | fab57728aeaee0f3f630384c822ffbe884e97287 |
| R5 P3 chat, point, ask, progress, receipts | present | `chat`, `point`, `ask-answer`, `overlay-poll` | ChatChannelTest, AskProgressPointTest | b1de827e7ef0a5ab8ad55f2f86dc0b5520cb5f7b |
| R5 P4 Monaco | present | Monaco loaded from the packaged workbench UI | WorkbenchUiLicenseTest, WorkbenchUiServerTest | ffbec7698399031b8430537314d9c39897ea6e1a |
| R5 P5 lease, input block, generated pages | present | `LeaseHold`, `input block`, `ui page` | LeaseAndBlockTest | a2ec1e80dade538be98834c78b2dc040d5cd6710 |
| R5 fix-back 1 gap 16 license allow-list | present | packaged UI license allow-list | WorkbenchUiLicenseTest | fc59bf51fbc65b15d6b8f800eadea3a6e8806a07 |
| R5 fix-back 1 gap 2 ui page dispatch | present | `ui page` through `VerbDispatch` | UiPageDispatchTest | 54a1faee5cc32efec5cbecf80a9ac16d359d669f |
| R5 fix-back 1 gap 6 chat follow | present | `chat --follow` in `VerbDispatch` | ChatFollowStreamTest | b005626780c1caa0b7b98040240fdbe336a962c4 |
| R5 fix-back 1 gap 1 jar assets | present | jar assets under `.pickleball/workbench-ui/assets` | WorkbenchUiJarAssetsTest | fe0c63dd93510d33b5c459e2dd3c1c036a0515fc |
| R5 fix-back 1 gap 12 Monaco loader | present | Monaco loader from the packaged UI | WorkbenchUiServerTest | 0004b3cb4bbf2693cc9178247dc5821a00c5731f |
| R5 fix-back 1 gap 14 store sql cap | present | `store sql` 200-row cap | StoreSqlReadOnlyTest | 0b85c07e0f1e2b2f38138be4c6f78f701de8b4a4 |
| R5 fix-back 1 gap 10 node_modules route | present | renderer `/node_modules/` route | VerbCatalogTest | 29268b4da7393074d64a450f0136fb5e7af2a356 |
| R5 fix-back 1 gap 9 generated page access | present | generated-page access check | GeneratedPageAccessTest | 4a0a6cb75d27b11fc32842d146e449d27fd2af7a |
| R5 fix-back 1 gap 17 packaged modules | present | packaged `node_modules` | WorkbenchUiLicenseTest | 4587d93a2158271044bdf19a7f1fba8b4b58697a |
| R5 fix-back 1 gap 3 open-window renderer | present | `open-window` starts `WorkbenchRendererHost` | OpenWindowRendererTest | f9a258e72c1a26be9eb0070004450af78522ca73 |
| R5 fix-back 1 gap 7 lease both | present | `LeaseHold` refuses `both` against `ui` and `browser` | LeaseGateTest, WorkbenchControlLeaseTest | d457ed0750e7c07b7d03550a05bcdc4a010e21fa |
| R5 fix-back 1 gap 4 live inject | present | `inject on` runs `RuntimeInstall` | InjectLivePageTest | 227752cb511e125e0b58b0e70b3b95b479a57138 |
| R5 fix-back 1 gap 5 overlay receipt and ask | present | overlay receipt and `ask-answer` | OverlayReceiptAskTest | aa23fd0c7f88a1584a94e45e696dc8ceac02b1ac |
| R5 fix-back 1 gap 8 input block | present | `input block` page shield | InputBlockLivePageTest | a053f8c94f3410f9376e0b9a8d4422993bf2f732 |
| R5 fix-back 1 gap 11 pages and glue | present | renderer pages and `store glue show` | WorkbenchPagesTest | 6d1480bdc24186d52f511a86127ad4fea94b01e7 |
| R5 fix-back 1 gap 13 session required | present | `VerbDispatch` requires a session | SessionRequiredTest | 5c0680d31493ac2689216f88d80da97e4bbb706b |
| R5 fix-back 1 gap 15 Node design docs | present | Node design docs locked by `NodeDesignDocsTest` | NodeDesignDocsTest | 4fdba80f21ee42194a890543ae1c061611fd2f3c |
| R5 fix-back 2 item 1 page JVM hop | present | `AgentHop` to the page JVM | PageJvmRouteTest | 16904ea061a7c685a176f11f38293758eb1309b9 |
| R5 fix-back 2 item 2 overlay ack | present | overlay `read_at` ack | OverlayAckLiveTest | 16245d95e5dc7a6345384ebba81e8dd646feed90 |
| R5 fix-back 2 item 3 shown run | present | `shown-run.json` on the renderer pages | RendererShownRunTest | 16245d95e5dc7a6345384ebba81e8dd646feed90 |
| R5 fix-back 2 item 4 renderer session | present | renderer `SpineSession.ensure` | RendererStoreSessionTest | 16245d95e5dc7a6345384ebba81e8dd646feed90 |
| R5 fix-back 2 item 5 renderer lease | gap | `LeaseHold.require`; the renderer sends no agent id (open in tech-debt, not a pass) | RendererStoreSessionTest | 16245d95e5dc7a6345384ebba81e8dd646feed90 |
| R5 fix-back 2 item 6 warm ping argfile | present | `spine start` writes `.pickleball/spine/java.args` | PolishRoadmapTest | 16245d95e5dc7a6345384ebba81e8dd646feed90 |
| R5 fix-back 2 item 7 docs | present | R5 fix-back 2 docs | NodeDesignDocsTest | 0833828005a6e819b875567c8814194fc41a6857 |
| R5 fix-back 2 item 8 CSP | present | generated page `Content-Security-Policy` | GeneratedPageAccessTest | 16245d95e5dc7a6345384ebba81e8dd646feed90 |
| R6 P0 session exemptions | present | `VerbDispatch.SESSION_EXEMPT` | SessionExemptTest | 8b14439fb81691a178fd10a3447e3f5092308656 |
| R6 P1 fork, discard, color | present | `store branch fork` and `store branch discard` | BranchForkTest, BranchColorTest | f38755cd0b15fa8320ee43523519bb38622ec935 |
| R6 P2 map and pre-step hook | present | `PreStepHook` before `NativeScenarioRun` | PreStepHookTest, ForkGluePutTest | 4d927571a7c5f2b8d231ce803d816be174a0f5d4 |
| R6 P3 promote, tree, parent link | present | `CowSandbox.promote`, `store branch tree`, `store branch link` | BranchTreeTest, SandboxPromoteTest | 0cf97f77561ad9dd0d0818fcb93b185c011b9a18 |
| R6 fix-back 1 discard on exit | present | `store branch discard` on fork exit | SpineR6FixBack1DiscardTest | 08c71aad94fa4ff9bff4acd9c734c075e1ebf5ab |
| R6 fix-back 1 page controls | present | renderer branch controls call `store branch` | SpineR6FixBack1PageControlsTest | 08c71aad94fa4ff9bff4acd9c734c075e1ebf5ab |
| R7 P1 run snapshots | present | run snapshots in `runstate.db` | RunSnapshotTest, RunHistoryAccuracyTest | 0208ee2abfba494037993769101db6fc319bde4e |
| R7 P2 report script, visual target, exception, find index | present | `store report`, exception attributes, `snap_find_index` | ReportScriptTest, VisualTargetTest, PickleballExceptionTest, SnapFindIndexTest, DashboardTest | 39166be1ba3082fcdee1d25287d37a16d421c9de |
| R7 P3 retain and delta archive | present | `store retain` and `delta_archive` | SnapshotRetainTest, StoreHistoryTest, HeadMaterializerTest | c3798192470ae4dca13a1598deb14e290bed9258 |
| R7 P4 cross store | present | `CrossStore` (`cross.db`) | CrossStoreTest, StoreCliLaunchTest | 8d424cb944dee81ff091f7533b6dd549286999d1 |
| R8 P1 warm classloader | present | `WarmClassLoader` | WarmClassLoaderTest, WarmColdParityTest, IncrementalGlueCompileTest | 3060df1b4e63993b928c151f2078fc751bd901b1 |
| R8 P2 resource gate | present | `ResourceGate` and `spine sample` | ResourceGateTest, WarmColdParityTest | baf38b1827d0a130895a5065b6e5aa3878259d9a |
| R8 P3 browser block | present | `BrowserVersionResolver`, `ProtocolSelector`, `DebugPortOwner` | BrowserVersionResolverTest, SeleniumBidiCommandTest, DebugPortOwnerTest | a482e3d5a9c796be8dd6f5aa7b6825d575f160ca |
| R8 P4 warm CDP and UIA cache | present | warm CDP on `spine start`; `DesktopTree` | WarmCdpTest, DesktopTreeCacheTest | bbb8f3cc141f19a355d621c76b35b64904b540e1 |
| R8 fix-back 1 gap 1 cdn cache | present | browser CDN cache lookup | BrowserCacheAndLocalVersionTest | d78f88fd2834c86a0d013e855eebbd50049d9918 |
| R8 fix-back 1 gap 2 local versions | present | `BrowserVersionResolver` local installs | BrowserCacheAndLocalVersionTest | d78f88fd2834c86a0d013e855eebbd50049d9918 |
| R8 fix-back 1 gap 3 debug port owner | present | `DebugPortOwner` | DebugPortAttachTest, DebugPortOwnerTest, ApplyDebugPortTest | 0fb8c1c023b2a6c52e9063d511dbd722606ac427 |
| R8 fix-back 1 gap 4 heap gate on open | present | `ExecutionContext.open` calls `ResourceGate.admit` | SnapshotContextGateTest | 2a7aac3d42b55950f4fdd6888eed22e420ad2955 |
| R8 fix-back 1 gap 5 UIA structure-changed | present | Windows UI Automation structure-changed listener | WindowsStructureChangedTest, DesktopTreeCacheTest | 73e23aa807630a9aa33325d22460c56d5746803c |
| R8 fix-back 1 gap 6 incremental glue compile | present | `StoreRun.compileGlue` via `JavaCompiler` | WarmGlueCompilePathTest, IncrementalGlueCompileTest, StoreRunTest | 4cd772e8b3b83a1e4c3414c0809bc73ca12e7dc0 |
| R8 fix-back 1 gap 7 BiDi live session | present | `bidi script` and `bidi intercept` | SeleniumBidiLiveSessionTest | 3413ae51f1fac7822885e8d368ad012cdb3fe0eb |
| R8 fix-back 1 gap 8 hold JVM flags | present | `spine start` hold JVM flags | HoldJvmFlagsTest, WarmCdpTest | 40420877ab341399550f91f3af02cbc4e5eedd47 |
| R8 fix-back 2 item 1 cold child does the work | present | `SnapshotContextWorker` | SubprocessStepTest | 4f2782fbaffbb00e04e54cd2d46d5a733f7be436 |
| R8 fix-back 2 item 2 incremental glue reaches the scenario | present | `WarmClassLoader` on the next scenario | WarmGlueScenarioTest | 1fc56f825119953156b605f41955ed73d7fa0136 |
| R8 fix-back 2 item 3 bidi from the CLI | present | `bidi script` and `bidi intercept` through `VerbDispatch` | BidiCliSessionTest | 108c1316a8132b46bc157059a4d686c45468ef72 |
| R8 fix-back 2 item 4 UIA structure live | present | live Windows structure-changed handler | WindowsStructureLiveTest | a31f2778d703ba8a0860d96663011de96dd6ebf9 |
| R8 fix-back 2 item 5 rebuild restarts the warm JVM | present | `StoreRun` rebuild stops and starts the warm JVM | WarmRebuildRestartTest | c024a67b245e49df40e6ff2e8b961f69a9d53a86 |
| R8 fix-back 2 item 6 docs | present | R8 fix-back 2 docs | SpineR8FixBack2DocsTest | 6586372e11b2457831a305555ef1138434328a44 |
| R8 fix-back 2 item 7 cdn reread and port release | present | CDN reread and debug-port release on quit | CdnDownloadRereadTest, DebugPortQuitTest | d651bd5b666bfa76b889a91d4ad7dfccb73aa810 |
| R8 fix-back 3 item 1 one child scenario | present | one `SnapshotContextWorker` scenario for the parent scenario | SubprocessScenarioTest | e225e12134c7622dc241c2efafd619c14019b051 |
| R8 fix-back 3 item 2 worker reload | fixed | `StepRegistry` uses `WarmClassLoader`; `spine/classes` stays off the worker classpath | WarmWorkerReloadTest | eda67e78127bb3798ee3a29fd5d826bc98bfdeb1 |
| R8 fix-back 3 item 3 debug port and cdn reporting | present | debug-port quit and CDN cache fallback reporting | DebugPortQuitTest, CdnCacheFallbackTest | cd95bbad9abb952468526c41f778e189d574c26f |
| R8 fix-back 3 item 4 docs | present | R8 fix-back 3 docs | SpineR8FixBack3DocsTest | 1c0c927453a24cda3672532d9887f0e696e537a8 |
| Merge commit | present | merged tree compiles | compile only | 0153558265a21276502317b13a8b26caa9b0baad |
| Merge (a) one spine help entry | present | `SpineCli` help | SpineHelpMergeTest | c81710e1ae27949b91d77f6aea0c33e762ce1346 |
| Merge (b) spine routing | present | `PickleballWorkbenchLauncher` spine routes | SpineRouteCliTest | c81710e1ae27949b91d77f6aea0c33e762ce1346 |
| Merge (c) bidi session rule | present | `VerbDispatch` bidi session rule | BidiSessionGateTest | c81710e1ae27949b91d77f6aea0c33e762ce1346 |
| Merge (d) launcher ordering | present | launcher order before `ui page` | UiPageDispatchTest | c81710e1ae27949b91d77f6aea0c33e762ce1346 |
| Merge (e) verify_agent_contract | present | `scripts/verify_agent_contract.py` | script | c81710e1ae27949b91d77f6aea0c33e762ce1346 |
| Merge R8 test given a session | present | `bidi` CLI through `VerbDispatch` | BidiCliSessionTest | c81710e1ae27949b91d77f6aea0c33e762ce1346 |
| Merge extra file URI outside the store | present | `FeaturePathLoadCache` for a file URI outside the store | WorkbenchAgentCommandsTest | c81710e1ae27949b91d77f6aea0c33e762ce1346 |
| Merge extra coverage gate methods | present | `VerbCatalog` coverage gate | SpineCommandCoverageTest, WorkbenchCliCoverageGateTest | c81710e1ae27949b91d77f6aea0c33e762ce1346 |


Status is `present` when that production caller is on this branch and every named test passed in Part A with 0 failures. `fixed` means this regression changed that caller (see Failures and fixes). The renderer-lease row is `gap` because the renderer still sends no agent id. That line, the cold-child hook note, the warm-spine marker seam, and the R6 in-JVM and page-control notes stay open in `docs/tech-debt.md` and are not passes.

Known open follow-ups, not passes: `docs/tech-debt.md` sections `Spine merge follow-ups (open, 2026-11-26)`, `Spine R6 follow-ups (open, 2026-11-27)`, `TypeScript package in the Workbench UI bundle`, and `Deferred Linux and macOS tests`.

## Docs audit

| Stale reference | File | Fix SHA |
|---|---|---|
| R4 roadmap says extra ElementRecord keys go under `extra` unless `--fields`. The shipped record passes those keys through. The roadmap file was not edited. `AGENTS.md` and `docs/release-notes.md` state the shipped behavior. | `docs/roadmaps/3.0.0-spine/R4-cli-mcp-universal-surface-verb-inventory.md` | recorded disagreement, no roadmap edit |
| Feature-run docs said every `enabled=0` scenario is left out of both engines. An explicit calls or component path still loads those files. | `docs/cucumber-compatibility.md`, `docs/configuration.md`, `docs/store-entities.md`, `docs/release-notes.md` (Spine R2 P2) | a1c51714f |
| Spine R8 merge notes required a session for `bidi help`. `VerbDispatch.SESSION_EXEMPT` includes `bidi help`. | `docs/release-notes.md` | a1c51714f |
| Spine R5 fix-back 2 notes said help, version, and `export-guidance` are not exempt. The 2026-11-26 allow-list exempts them. `export-guidance --warm` still needs a session. | `docs/release-notes.md` | a1c51714f |
| Consumer project guide called `ui .` the Swing player. Bare `ui` opens the Node renderer. | `docs/consumer-project.md` | a1c51714f |
| A cow child with no file row did not supply parent bytes to a run. List and show already included the parent feature. `StoreQuery.file` and `StoreFeatureSource.bytesFor` now read that parent file unless the child deleted the path. | `AGENTS.md`, `docs/configuration.md`, `docs/release-notes.md`, `docs/agent/feature-map.md` | a1c51714f |
| Warm glue reload was described only as putting `.pickleball/spine/classes` on the warm loader. That directory stays off the store worker application classpath. `StepRegistry` loads the class through `JavaStepHost.selectLoader` when `pkb_warm=on`. | `AGENTS.md`, `docs/configuration.md`, `docs/release-notes.md`, `docs/agent/feature-map.md` | a1c51714f |
| An in-process `WorkbenchDirectRun` left `pkb_` system properties set after the launch. The launch snapshots `pkb_` and `cucumber.` properties and restores them when it returns. | `AGENTS.md`, `docs/configuration.md`, `docs/release-notes.md` | a1c51714f |
| The merge scoreboard still described the session-rule follow-up as awaiting Dan. R6 recorded the allow-list in `docs/tech-debt.md`. | `docs/maintainer/3.0.0-spine/merge-r8-scoreboard.md` | 0a0929a2e |
| Spine R2 release notes said a comma until loops while the condition is true. A one-element comma until selects that branch once. The R2 sentence stays as that phase's note. The current note is the 3.0.0 lead, added with this scoreboard. `RECORD ENTITY` on a suite that left `pkb_store_track` unset now opens a step and restores the run id. A foreign-frame presence hit already counted as displayed in the guides; the null `isDisplayed` call was the bug. | `docs/release-notes.md`, `docs/configuration.md`, `docs/store-entities.md`, `docs/object-registration.md`, `docs/agent/change-checklist.md`, `docs/agent/feature-map.md`, `AGENTS.md` | ed8a70980 for the behavior guides. The release-notes lead is in the scoreboard commit. |

## Environment and flake log

No flake recorded yet. Open follow-ups stay in `docs/tech-debt.md`. A new environmental failure goes under `Final regression findings (open, 2026-11-27)` in that file.
