# Grok Build prompt: fix the 3.0.0 Windows full-regression failures (standalone)

Paste this whole file into a **fresh** Grok Build session started in your pickleball checkout on the Windows PC (DESKTOP-TUUEDJG).

## How this run works (headless)
- You run headless through the `grok` CLI on Dan's Windows PC (DESKTOP-TUUEDJG), in a dedicated worktree already on branch `3.0.0-windows-regression`. No human is watching: never stop to ask a question. Decide, note the decision in the report doc, and keep going.
- Work only inside this worktree. Never touch, clean or switch any other folder or checkout under `C:\Users\Admin\IdeaProjects`.
- A pull request from `3.0.0-windows-regression` into `3.0.0` is already open; CI runs on every push to it. Never merge it.
- Commit and push to `origin 3.0.0-windows-regression` at the end of every step below (after triage, after each real-bug group, after portability, after the flaky fix, after the proof run). Each push is how progress is tracked, so don't sit on uncommitted work for more than about an hour.
- Keep a `## Status` line at the top of `docs/maintainer/3.0.0-windows-regression.md`: `IN PROGRESS: step N`, then `DONE` when step 6 is complete and green, or `BLOCKED: <reason>` if you truly cannot continue. Push that change too.

## Context
- Repo: github.com/ds-code-t/pickleball. Branch `3.0.0` was at `eaf6109c` when the full regression ran on 2026-11-22 (10:36–11:28 PM MST) on the 24-core i9 Windows PC.
- Results: Gradle 2,076 passed, 173 failed and 18 skipped; 172 failures also failed when rerun alone. Maven `@all` consumer suite: 427 passed and 5 scenarios fail. The full report is at the end of this file.
- Before you start, read `AGENTS.md`, the 3.0.0 docs under `docs/maintainer/` (including `3.0.0-agent-fast-design.md` and `docs/maintainer/3.0.0-agent-fast/`), and the scoreboard. The branch docs win over this file if they disagree.

## Rules (non-negotiable)
- Create and push to a working branch `3.0.0-windows-regression`, branched from the current `origin/3.0.0`. Never commit, push or merge to `master`. No tags, releases, Maven publishing or force-push.
- Fetch and rebase before every push. Small, focused commits.
- **Never weaken, skip or delete a test to make it pass.** For a test that depends on the platform:
  - Prefer making the **test** portable. Use Java process and file APIs instead of `sh`, `sleep`, `true` or Python. Pick `mvnw.cmd` on Windows. Use ACL-based checks or `PosixFileAttributeView`-aware code. Use argument files for long command lines.
  - Fix the **production** code when the product itself is wrong on Windows. Examples: launching through `sh`, POSIX-only permission calls with no Windows fallback, a command line over the Windows length limit.
  - Use an `assumeTrue(isPosix)`-style guard only when the behavior under test truly can't exist on Windows (for example, POSIX mode bits). Then add an equivalent Windows assertion, such as the ACL for owner-only access, so coverage is kept. List every such guard in the report.
- Keep Gherkin syntax. Step annotations stay single string literals. Never run `./gradlew --stop`. No secrets in cleartext.
- Flag any distribution, licensing, security or external-download implication before building it.
- Every production fix gets a test on the real production path that fails when the fix is reverted.

## Work, in order
1. **Triage.** Classify every failing test from the report into one of three groups:
   - (a) the test is Windows-unportable;
   - (b) a real product bug on Windows;
   - (c) a real bug on every platform, such as a stale expectation or wrong behavior.

   Write the table to `docs/maintainer/3.0.0-windows-regression.md`, with columns test, group, root cause and fix.
2. **Real bugs first (b and c).** In particular:
   - The 4 Maven JavaScript alert and confirm scenarios fail because the CDP socket closed (`Runtime.addBinding` not answered, then `NoAlertPresentException`). Make alert and confirm handling work when a dialog blocks CDP.
   - `PickleballGuidanceChecks.discoverHintPrintsDiagnosticMvnTestAndRunCatalogNext`.
   - The chat and overlay UI payload tests, the stale first-read guide size, and the `chrome_headless` vs `firefox` default. Decide which side is right from the docs.
   - The two DOM-access guard tests that flag `AgentControl.java`.
   - The browser-lost and `cdp connection is closed` tests, the warm-JVM kill tests (`stopKillsTheWarmJvmAndItsChild`, where the process tree isn't killed on Windows), and the other singular failures listed in the report.
3. **Portability (a).** Group by cause, such as POSIX permissions, shell utilities, `mvnw`, Python and long command lines. Fix each cause once, with a shared test helper where that makes sense.
4. **Flaky test.** `WorkbenchWarmLifecycleTest.sessionThatExitsBeforeHealthyKillsTheWarmJvm` fails only under parallel load. Fix the race; don't just add a longer sleep.
5. **Prove it.** Rerun the same commands as the report, Gradle `--parallel --max-workers=16 --continue test verifyStrictControllerIsolation` and the Maven `@all` run with `CHROME_HEADLESS`. Target: zero failures, with every skip explained. Also confirm GitHub Actions CI is green on Linux, macOS and Windows for the branch.
6. **Report.** Print the before and after counts, the commits, the list of assumption guards, and anything still open. When everything is green, Dan or his bot fast-forwards `3.0.0`; don't do that yourself.

---

# Regression report (verbatim, 2026-11-22)

     1|I'll confirm branch `3.0.0` is current with origin, then size a parallel regression run to this machine and run the project's own Gradle and Maven suites.
     2|
     3|Branch `3.0.0` matches `origin/3.0.0` at `eaf6109`. Next I’ll size Gradle and Maven parallelism to this machine and see which suites share browser or port state.
     4|
     5|Branch `3.0.0` matches origin. This machine is a 24-core / 32-thread i9 with 64 GB RAM, so I’ll parallelize modules and pure unit-test forks, and keep browser and fixed-port suites on one fork.
     6|
     7|Gradle finished in about 8 minutes with failures. I’ll tally each module from the test reports, then rerun the failures one at a time.
     8|
     9|Isolation reruns are done. I’ll remove the build output and temp files, then write the report.
    10|
    11|Branch `3.0.0` matches `origin/3.0.0` at `eaf6109`. The suite is red: **172 Gradle failures and 5 Maven scenarios failed again when rerun alone**. One Gradle test failed only in the parallel run.
    12|
    13|## Commands and time
    14|
    15|Machine: 24 cores / 32 threads, 64 GB RAM, about 39 GB free.
    16|
    17|| Command | Time | Result |
    18||---|---|---|
    19|| `.\gradlew.bat --parallel --build-cache --max-workers=16 --continue test verifyStrictControllerIsolation` | 8m 1s | failed |
    20|| Same failed tests again, one class per JVM, modules not parallel | 4m 11s | 172 still failed, 1 passed |
    21|| `.\maven-consumer-project\mvnw.cmd -f maven-consumer-project\pom.xml -U test` with `-Xmx8g`, `pkb_browser=CHROME_HEADLESS`, `pkb_tags=@all`, `pkb_parallel=auto` | 21m 54s | failed |
    22|| Those 5 scenarios again with `pkb_parallel=1`, then `@step-override-bridge` | 1m 23s | 5 failed, bridge passed |
    23|
    24|Forks during the Gradle run: control-protocol 6, control-api 4, workbench 2, root 4, dom-engine 1. `pkb_parallel=auto` on an 8 GB test heap resolves to 16. `verifyStrictControllerIsolation` passed. Build directories and temp files were removed. No source files were edited.
    25|
    26|## Counts
    27|
    28|| Module | Pass | Fail | Skip |
    29||---|---:|---:|---:|
    30|| root (`:test`) | 1474 | 149 | 8 |
    31|| pickleball-control-protocol | 114 | 5 | 1 |
    32|| pickleball-control-api | 24 | 7 | 0 |
    33|| pickleball-workbench | 284 | 9 | 0 |
    34|| dom-engine | 180 | 3 | 9 |
    35|| **Gradle total** | **2076** | **173** | **18** |
    36|| Maven `@all` (Surefire) | 427 | 1 failure + 7 errors | 0 |
    37|| Maven `@step-override-bridge` (not in `@all`) | 1 | 0 | 0 |
    38|
    39|The 18 Gradle skips are live Chrome, Edge, Firefox, desktop-bus, and macOS tests that skipped because that binary or bus is absent. Surefire’s 7 Maven errors are 4 scenarios plus 3 feature-level rollups of those same scenarios. `LazyBooleanSideTest` (17 tests) passed inside the Maven run.
    40|
    41|## Flaky
    42|
    43|`WorkbenchWarmLifecycleTest.sessionThatExitsBeforeHealthyKillsTheWarmJvm` failed in the parallel run with `warm pid 24236 still alive=true` and passed alone.
    44|
    45|## Real Maven failures
    46|
    47|All five failed again alone:
    48|
    49|- `Accept a JavaScript alert`, `Accept a JavaScript confirmation`, `Dismiss a JavaScript confirmation`, and `keyboard hold dialog accept and one navigation`: `accept`/`dismiss` failed because the CDP socket closed (`Runtime.addBinding` was not answered: `Output closed`), then Selenium reported `NoAlertPresentException`.
    50|- `Run diagnostic reporting checks`: `PickleballGuidanceChecks.discoverHintPrintsDiagnosticMvnTestAndRunCatalogNext` expected true and was false.
    51|
    52|## Real Gradle failures
    53|
    54|**58 — `Files.setPosixFilePermissions` is not supported on Windows**
    55|
    56|- `AgentLiveFixTest`: `debuggerAddressOnlyActivateDoesNotClaimATabWhosePageTargetIsMissing`, `failedPageActivationDoesNotClaimTheDestinationTab`, `closeDoesNotLeaveTheClosedTabsSessionFocused`, `activateDoesNotClaimATabWhosePageTargetIsMissing`, `aSecondBrowserKeepsItsOwnEngineSession`, `failedPageSessionDoesNotClaimTheGlobalJoinPointer`, `queryOriginFollowsTheThreadSessionNotTheGlobalJoinPointer`, `twoTabsOnOneBrowserKeepIndependentEngineSessions`, `firstQueryDoesNotInheritAnotherThreadsJoinedCdp`, `publicSwitchDoesNotReportSuccessWhenThePageSessionFails`
    57|- `ActionVerbPlanTest`: `binaryListsTheVerbOnce`, `binaryWithoutTheVerbKeepsTheOldMapping`, `pressLettersAppendAndSendKeysReplaces`
    58|- `AllContextsPlanTest.scanQueryIsOneCall`
    59|- `CapabilityProbeTest`: `rustFailureKeepsTheTwoFourDesktopSet`, `javaEngineFailureUsesTheJavaDesktopVerbs`, `secondReadSeesTheRealActionList`
    60|- `CompositeFallbackLogTest`: `explicitCompositeBinLogsTheOverrideSource`, `clientFailureAndJoinFailureEachLogOnce`
    61|- `CompositeLocationTest`: `propertyAndSiblingCheckoutResolve`, `propertyBinBeatsCompositeBinOnBothResolvers`, `relativeCompositeBinResolvesTheSameAbsolutePathOnBothResolvers`, `clientOpensWhenTheBinarySpeaksStdio`, `explicitBinAndRustFlagBeatTheJavaEngine`, `javaEnginePrecedesTheRustSibling`, `primaryFollowsTheInjectedBinaryNotTheHost`
    62|- `ContextNestGateTest`: `withinFindsUnderAContextThatHasNoId`, `parenthesizedXPathStaysInsideTheContext`, `missingFlagsKeepTheAnchorFallback`, `multiEvalIsOneCall`, `nestingWithoutAnchorsIsOneQuery`
    63|- `ElementSnapshotEvalTest.evalListedIsOneCallAndZeroScripts`
    64|- `EnginePositionGateTest`: `regexStaysInJavaBeforePosition`, `thirdLastAndEveryAreOneQuery`, `countOnlyIsOneQueryAndNoNodes`, `missingNthKeepsJavaPosition`
    65|- `GeometryViewTest`: `openControlExposesRevealedCornersInOneQuery`, `oneQueryReturnsSpacesWhenTheFlagIsListed`, `hitAtIsOnePointQueryWhenListedAndAbsentOtherwise`, `scrollableRegionExposesClientAndContentInOneQuery`, `missingFlagKeepsTheAabbAndDoesNotAsk`
    66|- `LeanWaitNestLiveTest`: `engineTimeoutDoesNotWaitAgain`, `nestBatchIsOneExtraQuery`, `liveRefreshIsTwoEvalsAndNoFind`, `rejectedPollKeepsTheJavaWait`, `visibleWaitIsOneQueryAt150ms`
    67|- `SelectorAndAdapterTest`: `adapterClicksTypesAndQueriesDesktopThroughOneClient`, `desktopClickAndTypeNameTheMissingUpstreamVerb`, `desktopClickForwardsWhenCapabilitiesListsTheVerb`
    68|- `ElementWrapperActionTest.compositeOnUsesTheAdapterForEachAction`
    69|- `WorkbenchCliProcessCoverageTest.failureIgnoreWrapperExitZeroStillFailsTheProcess`
    70|- `PageElementQueryTest`: `visualRelativeWithoutGeometryDoesNotInventABox`, `visualRelativeForwardsTheSelectorAndLeavesGeometryNull`, `liveFindUsesCompositeInsteadOfTheDriver`, `fastSurfingBatchReturnsHandlesForOneFollowUpAct`, `imagePromoteBindsANodeAndAVisualHitStaysLabeled`, `engineQueryForwardsTheSelectorAndKeepsCategoryCounts`, `humanActRefusesAndLowerActKeepsTheSentence`
    71|
    72|**12 — Windows cannot run Unix `sleep`**
    73|
    74|- `ProcessStartGuardTest.matchingStartInstantKillsSleep`
    75|- `StartDrivesPageTest`: `liveStopDoesNotKillPidsWithNoRecordedStartTimes`, `liveStopLeavesAProcessWhoseStartTimeDoesNotMatch`, `startRefusesALiveWorkerAndForwardsThatSession`
    76|- `WorkbenchCliSurfaceCoverageTest.covers_flag_force`
    77|- `WorkbenchLaunchOutputContractTest`: `directPathDoesNotEchoConsoleMarkersOrCallATestFailureAWarmFault`, `directSequentialAndLauncherEntryShareTheFilteredContract`
    78|- `WorkbenchSessionCommandsTest.startDetachedClosesStdinAndTheChildStarts`
    79|- `DirectControlProviderTest`: `providerExitDoesNotKillTheBrowser`, `presentBinaryAttachesAndDetachLeavesTheBrowserRunning`
    80|- `SpeedTypesTest.warmHandleIsReusableOnlyWhileThePidAndHashMatch`
    81|- `LiveSessionPublicationTest.isolateAndGuiPathPublishesCdpAndDetachesWithoutKillingTheBrowser`
    82|
    83|**11 — Windows cannot run Unix `true`**
    84|
    85|- `BrowserStateTest`: `deadChromeIsDownEvenWhenTheHostJvmIsAlive`, `liveStopClearsSessionAndChromePresence`
    86|- `DoctorTest.deadHostPidReportsHealthDown`
    87|- `WorkbenchCliSurfaceCoverageTest.covers_command_open_window`
    88|- `WorkbenchWarmFallbackTest`: `startedThenDelayedExitIsNotAFallback`, `exit2WithoutStartedIsARejectionNotAFallback`, `startedThenDropDoesNotFallBack`, `secondRunDuringAnInFlightServeDoesNotReleaseOrFallBack`, `runTimeoutAfterStartedIsNotAFallback`, `closedPortMayStillFallBack`, `delayedExitWithoutStartedIsAMissAndWouldRelaunch`
    89|
    90|**8 — assertion expected true, was false**
    91|
    92|- `SurfaceAssetsTest.oneCopySharedByPageAndPopupKinds`
    93|- `WorkbenchCliProcessCoverageTest.quotingPathsTagsAndNonAsciiSurviveTheProcess`
    94|- `WorkbenchCliSurfaceCoverageTest.covers_flag_cursor`
    95|- `WorkbenchWarmLifecycleTest.deletedStateFileExitsTheWarmJvm`
    96|- `DirectControlProviderTest.nonZeroAttachFailsClearly`
    97|- `SessionDescriptorTest.atomicReplaceNeverLeavesAPartialDescriptor`
    98|- `WorkbenchSynchronizerTest.escalatesResourcesOnlyToFullWhenCompiledOutputsAreMissing`
    99|- `WorkbenchSynchronizerTest.refreshesResourcesWithoutTestCompileWhenOnlyConfigChanges`
   100|
   101|**4 — JUnit failed to close the extension context**
   102|
   103|- `ControllerReliabilityTest.controllerStartUsesAClasspathThatContainsPkbMain`
   104|- `ControllerUtf8Test.chatSendRoundTripsNonAsciiThroughTheController`
   105|- `PolishRoadmapTest.warmPingIsUnderOneSecond`
   106|- `PopupVerbCoverageTest.covers_command_popup_open`
   107|
   108|**3 — test invoked the Unix wrapper `mvnw`**
   109|
   110|- `WorkbenchConsumerScenarioEndToEndTest`: `discoverRunsARealScenarioAndRecordsThePack`, `realMavenFailureIgnoreStillFailsTheWorkbench`, `usageErrorThroughExecJavaExits2`
   111|
   112|**3 — popup status stayed `closed`, expected `open`**
   113|
   114|- `PopupHostCloseTest`: `cliPopupCloseUsesTheSameWait`, `closeAndWaitStopsTheHostAndDeletesTheProfile`
   115|- `PopupLifecycleTest.fakeBrowserRecordsArgvReusesAndObeysExitRules`
   116|
   117|**Other single or paired causes**
   118|
   119|- `StoreRunTest.timingScriptSelfCheckDoesNotLaunchASuite` and `timingScriptMeasuresAFixtureAndReusesTheWorker`: Python is not installed (Microsoft Store stub).
   120|- `FeaturePathLoadCacheTest.secondLoadDoesNotReparseAndKeepsUriOrder` and `WorkbenchCommandLineTest.choiceFragmentsRejoinOnCommasAndASpaceStaysOneChoice`: path contains a character Windows rejects.
   121|- `PopupChatLiveTest.sendButtonStaysDisabledForABlankMessage` and `humanMessageEchoesInThePane`: no element `#text`.
   122|- `DiscoverHintJvmLeakTest.discoverHintIgnoresJvmPkbParallelButResolveRunVarsStillSeesIt` and `WorkbenchAgentCommandsTest.hintPrintsLadderRunVarsAndNextDiscover`: hint text did not contain the expected workspace seed sentence.
   123|- `WorkspaceLaunchTest.withoutWorkspaceHooksStillRun` and `WorkspaceSeedTest.consumerStyleHooksAreCapturedAndHeadlessIsOnlyTheFallback`: expected `firefox`, was `chrome_headless`.
   124|- `FieldReportRegressionTest.consumerExampleAndGuidanceStayCurrent` and `WorkbenchPointerContractTest.firstReadGuideStaysSmallAndShowsTheDiscoverEditConfirmLoop`: first-read guide was not 5185 bytes.
   125|- `FrameRestoreTest.inFrameClickRestoresThePageForTheNextStep` and `MaskQuietTest.gherkinClickWaitsForTheCoveringMask`: `CLICK` failed.
   126|- `WorkerRestartBrowserTest.stopKillsTheChildTreeAndEmitsBrowserLost`: Windows cannot run `sh`.
   127|- `ConsumerMavenTestRunnerTest.wrapperOutputIsCopiedOntoTheStreamsTheCallerPassed`: cannot run `/bin/sh`.
   128|- `WorkspaceLaunchEndToEndTest.editedWorkspaceDrivesMavenDirectJavaAndLiveWorker`, `RunVarLaunchParityTest.dryRunMatchesARealScenarioAcrossTheMatrix`, and `savedCopyKeepsTheEditAndDoesNotReapplyHooks`: `CreateProcess error=206` (command line too long) launching `java`.
   129|- `PickleballLocalStoreTest.staleCacheOneShotExitsWithJavaNotMaven` and `openScriptOneShotDoesNotStartMavenWhenTheCacheIsFresh`: cannot run `pickleball-workbench.sh`.
   130|- `WorkbenchFrameLoadTest.aLargeCatalogIsAppliedOnceWithAFixedRowAndOneSelectionCall`: `ArrayIndexOutOfBoundsException: 0 >= 0`.
   131|- `MavenWorkspaceLaunchEndToEndTest.mavenTestLaunchesTheEditedWorkspace`: `UnsupportedOperationException`.
   132|- `LargeFileReferenceTest.bothMissingFailsWithFix`: `AccessDeniedException` on a temp `.git/objects` file.
   133|- `SuiteDualRunTest.consumerFeaturesMatch`: `FileSystemException` on `src\test\resources\calls` (`A required privilege is not held by the client`).
   134|- `WorkbenchSynchronizerTest.refreshesResourcesWithoutTestCompileWhenOnlyFeaturesChanged`: missing `demo.feature` under the live classes directory.
   135|- `OverlayRuntimeLiveTest.askConfirmReturnsTheChoice`: answer `"No"` was not recorded.
   136|- `SessionJoinLogTest.mcpForwardsSessionJoinAndLog`: response body was `Content-Length: 75` where the expected payload was missing.
   137|- `AgentLiveFixTest.overlayUsesRememberedGeometryWhenCallerOmitsCoordinates`: expected `IllegalArgumentException`, got `IllegalStateException`.
   138|- `WorkbenchSessionCommandsTest.sessionStartTimeoutKillsTheProcessTreeAndDeletesTheSessionFile`: session process exited before it became healthy.
   139|- `StoreRunTest.fingerprintAndCompilePlanFollowTheGlue`: `mvnw.cmd -q test-compile` did not succeed.
   140|- `WorkbenchOneShotLaunchTest.missingOrStaleCacheUsesJavaClasspathWhenItResolves` and `oneShotWithoutAFreshCacheKeepsJavaExit2`: `mvnw` exec-java launch failed.
   141|- `WorkbenchCliSurfaceCoverageTest.windowsPathsSpacesAndWrapperJarStayOneArgument`: `gradlew.bat` under a `café build` path did not stay one argument.
   142|- `OverlayRuntimeLiveTest.guideAdvancesOnInput`: guide event did not match the expected input record.
   143|- `WorkbenchOneShotLaunchTest.freshCacheLaunchesWithJavaClasspathAndDoesNotStartMaven`: `build-classpath` did not run when no snapshot exists.
   144|- `WorkbenchSessionCommandsTest.stopForceKillsTheProcessTreeAndRecordsStopped`: child pid was not published.
   145|- `UntilPassOverheadTest.literalUntilWithABrowserSessionPaysNoBrowserWait`: click settle was not 1.
   146|- `WorkbenchAgentCommandsTest.agentGuideSaysResolveRunVarsIsNotTheDiscoverEnvironment`: `docs/consumer-agent-guide.md` was not found in the output.
   147|- `WorkbenchPointerContractTest.agentGuidesDriveAnOpenWindowAndUseTheProjectWrapper`: `docs\consumer-agent-guide.md` was not found in the output.
   148|- `WorkbenchSessionQueryTest.emptyListPrintsNothingLive`: expected `(no live sessions)`.
   149|- `OverlayRuntimeLiveTest.agentMessageAppearsInPanel`: expected 0, was 1.
   150|- `SurfaceAssetsTest.loadsEachAssetAtItsHash`: asset hash differed.
   151|- `PlainResolutionGoldenTest.exampleAndConsumerStyleResolutionMatchThePhase0Baseline`: resolution text did not match the golden `EXAMPLE` block.
   152|- `NavigateDataFileTest.fileDataAndBlankNavigate`: expected `FilePage`, was empty.
   153|- `AgentDiscoverPlannerTest.seedKeepsANamedBrowserAndDoesNotStampEstimatedParallel`: expected `auto`, was `80`.
   154|- `StoreQueryTest.listAndShowAreStableJsonAndHumanLabels`: expected `conflicts 0`.
   155|- `StoreEntityTest.crudAndFileRoundTripKeepTheOriginalBytesUntilADatabaseEdit`: expected `entities 0`.
   156|- `WorkbenchSessionQueryTest.singleSessionInfoMatchesTheDescriptorFile`: session info line did not match the descriptor.
   157|- `StoreGradleForwardTest.everyStoreTaskResolvesArgumentsWithAndWithoutProperties`: expected `tools.dscode.store.StoreCli`, got the same name plus an ANSI erase (`?[0K`).
   158|- `StoreQueryTest.missingDatabaseListsAreEmptyAndDoNotCreateOne`: expected `{"files":[]}`.
   159|- `PopupChatLiveTest.secondAgentMessageArrivesAfterTheFirstBatch`: `first:` was false.
   160|- `OverlayRuntimeLiveTest.windowResizeRepositions`: fixture target did not move.
   161|- `WorkbenchSynchronizerTest.freshJarKeepsFingerprintAndStopsHashingAfterTheRacyWindow`: progress did not show `stage=materialize` as expected.
   162|- `DomAccessGuardTest.mainSourcesStayInsideTheClassifiedAllowlist`: `AgentControl.java` was outside the allowlist.
   163|- `PrimaryPathAuditTest.primaryPathDoesNotCallWebDriverFindOrActions`: `AgentControl.java:66` calls `findElements`.
   164|- `UiBatchTest.rejectsUnknownComponentsOptionsAndMissingFields`: `toast needs text`.
   165|- `WorkbenchWarmLifecycleTest.stopKillsTheWarmJvmAndItsChild`: `warm pid 8016 still alive=true`.
   166|- `WorkbenchMcpRetrievalTest.everyToolDescriptionIsOneShortLine`: `workbench_dashboard_xref` description is longer than one short line.
   167|- `LiveDesktopTest.liveAttachFailsClearlyAndForeignPlatformsDoNotRun`: desktop attach JSON did not match.
   168|- `ObserveEventsTest.workerServeAttachThenObserveOnTheSameProcess`: exit 1, `session not found: w`.
   169|- `StartDrivesPageTest.chatPollAfterChromeDiesReturnsBrowserLostOnce` and `BrowserLostRunIdTest.browserLostReachesEachPollOnce`: `cdp connection is closed`.
   170|- `WorkbenchStructuredErrorTest.blankArgumentAndUnknownToolAreErrorAndFix`: `workbench_execute_step requires text`.
   171|- `GlueEditTest.compileFailureKeepsTheWorkerAndSuccessRestartsIt`: glue put JSON did not match.
   172|- `AgentExpansionTest.covers_command_ui_poll` and `OverlayRuntimeLiveTest.uiApplyGuidedFlowInThreeCalls`: `ui-poll` payload did not match.
   173|- `FrameActTest.actInSameOriginOopifAndNestedFrameReadsTheEffect`: wait result did not match.
   174|- `CompositeVerbsCliTest.covers_command_chat_turn_lists_the_human_once`: chat-turn payload did not match.
   175|- `RefinementTest.selftestRecordClicksInsideTheFixtureIframe`: selftest record returned `ok=false`.
   176|- `RunVarMatrixParityTest.projectPropertyFilesUseOneReaderAndLaterFilesWin`: property map did not match.
   177|- `OverlayRuntimeLiveTest.guideClickStep`: `no guide g1 on the page`.
   178|- `WorkspaceSeedTest.hookDescriptionFileDefaultAndCliOverrideEachKeepTheirSource`: resolved RunVar map did not match.
   179|
   180|That notification is the first isolation rerun, which exited in about 8 seconds because the Gradle init script set a property this Gradle version does not have. That attempt did not run tests. The rerun that followed, one class per JVM, is the one in the report: 172 of 173 failures came back, and one passed.
   181|
---

# Targeted rerun (2026-11-23, about 12:30 AM MST, same commit eaf6109c, Windows PC)

Gradle reran only the 80 failing test classes. Result: 588 passed, 174 failed, 2 skipped.
- 172 of the 173 reported failures failed again.
- `StoreGradleForwardTest.everyStoreTaskResolvesArgumentsWithAndWithoutProperties` passed this time. Treat it as flaky and find out why.
- These failed now but are not in the report above. Include them:
  - `StartDrivesPageTest.selftestCpuLeavesTheHostPage`
  - `WorkbenchWarmLifecycleTest.shortLivedCliExitLeavesTheWarmJvm`

  Both are likely flaky under parallel load, like `sessionThatExitsBeforeHealthyKillsTheWarmJvm`.

Top causes:

| Count | Cause |
|---:|---|
| 59 | `UnsupportedOperationException` (POSIX file permissions) |
| 11 | no `sleep` on Windows |
| 10 | no `true` on Windows |
| 8 | plain `expected true but was false` |
| 5 | JUnit extension close failure |
| 3 | `.jdks` java launch fails |
| 3 | `.sh` launch fails |
| 2 | each for: `cdp connection is closed`, warm pid still alive, CLICK failed, first-read guide size, `firefox` vs `chrome`, run-seed path, Python not found, `NoSuchElementException` |

`SuiteDualRunTest` also fails because creating a symbolic link needs a privilege Windows doesn't grant by default. Fall back to copying the files, or use a junction.

Tip for Maven: on Windows, `-Dcucumber.filter.name` with `|` breaks through `mvnw.cmd`, and the `CUCUMBER_FILTER_NAME` environment variable was not honored. Add a supported way to run named consumer scenarios alone, such as a dedicated tag, and use it to prove the 5 Maven fixes.
