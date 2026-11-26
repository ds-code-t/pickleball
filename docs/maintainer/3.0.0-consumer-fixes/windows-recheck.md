# Windows recheck (P6)

Host: this Windows PC. Date: 2026-11-26. Branch `3.0.0-consumer-fixes`. No Notepad window. Desktop proofs use the private windows those tests already start.

The full local suite was not run. `scripts/agent_validate.ps1` with no switch runs `gradlew test` and then the Maven consumer `@all` tag. That is the PR CI matrix, not this recheck.

## Commands

Union (the green run):

```text
.\gradlew.bat :test --tests tools.dscode.launcher.HeadedFlagTest --tests tools.dscode.launcher.HumanTierTest --tests tools.dscode.launcher.RetestFixesLiveTest --tests tools.dscode.launcher.InputLiveTest.findRoleSearchesEveryFrame --tests tools.dscode.launcher.ConsumerFixesFrameLiveTest --tests tools.dscode.launcher.FieldReportRegressionTest --tests tools.dscode.launcher.ConsumerFixesP2CliTest --tests tools.dscode.common.agentfast.ConsumerFixesP2FindTest --tests tools.dscode.common.agentcontrol.ConsumerFixesP2WaitTest --tests tools.dscode.browser.proto.ConsumerFixesP2TabTest --tests tools.dscode.launcher.PwCommandLiveTest --tests tools.dscode.launcher.ContextLiveTest --tests tools.dscode.launcher.ConsumerFixesP3CliTest --tests tools.dscode.common.agentfast.ConsumerFixesP3ViewTest --tests tools.dscode.browser.proto.ConsumerFixesP3ProtoTest --tests tools.dscode.launcher.ConsumerFixesP3LiveTest --tests tools.dscode.browser.SurfaceAssetsTest --tests tools.dscode.launcher.RefLifetimeTest --tests tools.dscode.launcher.GuidanceCommandGoldenTest --tests tools.dscode.common.agentfast.ConsumerFixesP4EnvelopeTest --tests tools.dscode.common.agentcontrol.ConsumerFixesP4WaitTest --tests tools.dscode.launcher.ConsumerFixesP4CliTest --tests tools.dscode.launcher.WorkbenchCliCoverageGateTest --tests tools.dscode.launcher.WorkbenchAgentUsabilityTest --tests tools.dscode.common.agentcontrol.AgentDataFoundationTest --console=plain
```

Result: 136 tests, 0 failed, 0 skipped. BUILD SUCCESSFUL in 3m 17s.

Dom-engine:

```text
.\gradlew.bat :dom-engine:test --tests dev.domengine.ConsumerFixesP3QueryTest --tests dev.domengine.desktop.LiveDesktopTest --console=plain
```

Result: 9 tests, 8 passed, 1 skipped (`LiveDesktopTest.liveAttachSucceedsWhenAccessibilityBusIsUp`, assumption `no AT-SPI bus`), 0 failed. BUILD SUCCESSFUL in 12s.

An earlier union on the same filters failed before the parser fix (30 failures, all `pw needs an area`, then 2 failures, `unknown positional` on `browser-events` and `context-new`). Those counts are not the proof. The times below are from the green XML.

## Timings

| Item | Test | Time | Result |
|---|---|---|---|
| D1 | `HeadedFlagTest.bareHeadedIsTrueBesideAnotherFlagAndWhenItIsLast` | 0.000s | passed |
| D2 | `ConsumerFixesP3CliTest.unknownPositionalIsAnErrorAndDoesNotBecomeAProject` | 0.001s | passed |
| D2 | `ConsumerFixesP3CliTest.recordStillAcceptsAScratchDirectoryThatDoesNotExistYet` | 0.001s | passed |
| D2 | `ConsumerFixesP3CliTest.absoluteMissingProjectPathIsTheProject` | 0.001s | passed |
| D4 | `HumanTierTest.covers_flag_human` | 0.064s | passed |
| D4 | `HumanTierTest.covers_flag_human_override` | 0.044s | passed |
| D5 | `HumanTierTest.covers_act_and_call_human_override` | 0.038s | passed |
| D4/D5 | `HumanTierTest.covers_command_session_mode` | 3.431s | passed, live Chrome, explicit run id |
| D7 | `RetestFixesLiveTest.pageControlsOutrankPickleballChatText` | 0.075s | passed |
| D7 | `RetestFixesLiveTest.checklistRowsAreNotPageCandidates` | 0.054s | passed |
| D10 | `RetestFixesLiveTest.pickRoleBreaksATieAndAmbiguousListsWhy` | 0.657s | passed |
| D8/D9/D13/D14/D16/D19 | `ConsumerFixesFrameLiveTest.crossOriginFrameTypeSelectAndShortIdPlacements` | 3.489s | passed |
| L1 | `InputLiveTest.findRoleSearchesEveryFrame` | 3.211s | passed |
| D11 | `ConsumerFixesP3ViewTest.hiddenInputIsNotVisible` | 0.002s | passed |
| D11 | `ConsumerFixesP3ViewTest.humanFindKeepsACollapsedRow` | 0.001s | passed |
| D11 | `ConsumerFixesP3ViewTest.collapsedAncestorMarksTheRow` | 0.004s | passed |
| D11 | `ConsumerFixesP3QueryTest.hiddenInputIsNotATextbox` | 0.014s | passed |
| D12 | `RefLifetimeTest.refSurvivesUnrelatedChangesAndStalesWithItsNode` | 3.633s | passed |
| D15 | `ConsumerFixesP3LiveTest.stepBadgeReportsItsOwnBoxThenUnstepBadgeRemovesIt` | 3.434s | passed |
| D17 | `ConsumerFixesP3ViewTest.placementRuntimeAvoidsPageControls` | 0.002s | passed |
| D18 | `ConsumerFixesP3ViewTest.leanPlacementDropsGuidanceUnlessVerbose` | 0.002s | passed |
| D20 | `ConsumerFixesP2CliTest.pwSkipsOnlyTheProjectPathAndFlagsWin` | 0.005s | passed |
| D20 | `ConsumerFixesP2CliTest.callPwArgsFileIsAnAreaNotAUsageError` | 0.004s | passed |
| D20 | `ConsumerFixesP2CliTest.pwKeepsAMissingProjectPathAheadOfAnAreaWord` | 0.002s | passed |
| D20 | `PwCommandLiveTest.covers_command_pw` | 0.299s | passed |
| D20 | `PwCommandLiveTest.areaAndActionFlagsReadThePageTitle` | 0.027s | passed |
| D20 | `PwCommandLiveTest.stepsFileRunsThePageTitle` | 0.028s | passed |
| D20 | `PwCommandLiveTest.projectPathIsNotAnAreaAndAMissingStepsFileFailsBeforeConnect` | 0.001s | passed |
| D20 | `PwCommandLiveTest.noEndpointIsAnErrorWithFix` | 0.001s | passed |
| D21 | `ContextLiveTest.frameListOnALivePageReportsSameOrigin` | 0.725s | passed |
| D21 | `ConsumerFixesP2CliTest.frameListUsesTheStartHostWhileAWorkerIsAlive` | 0.011s | passed |
| D21 | `ConsumerFixesP2FindTest.sameOriginNormalizesDefaultPortsAndTheTopFrameStaysSameOrigin` | 0.002s | passed |
| D22 | `ConsumerFixesP2TabTest.targetWarningIsOnlyWhenTheTabsDiffer` | 0.001s | passed |
| D22 | `ContextLiveTest.tabNewStaysOffTheProtocolTargetUnlessActivate` | 2.178s | passed |
| D23 | `ConsumerFixesP3CliTest.doctorLabelsTheStartProbe` | 0.014s | passed |
| D23 | `ConsumerFixesP3CliTest.startHostWithoutAWorkerNamesTheInspectCommand` | 0.005s | passed |
| D23 | `ConsumerFixesP3ProtoTest.tabActivatePageMatchesAUrlSubstring` | 0.002s | passed |
| D24 | `ConsumerFixesP3ViewTest.unknownFieldFailsAndIdValuePairsDoNot` | 0.002s | passed |
| D24 | `ConsumerFixesP3CliTest.unknownFieldFixIsOneLine` | 0.005s | passed |
| D25 | `ConsumerFixesP3ViewTest.attachedCollaborationTargetIsRefused` | 0.002s | passed |
| D26 | `ConsumerFixesP2FindTest.minimizedWindowRefusesTypeUntilItIsForeground` | 5.861s | passed, private window |
| D26/E6 | `LiveDesktopTest.ownedEditTypeReadsBackTheValuePattern` | 3.262s | passed, private window |
| D27/D28/D29 | `ConsumerFixesP3LiveTest.desktopEqualsTargetStaysOnThatWindow` | 5.542s | passed, private window |
| D28 | `ConsumerFixesP3ViewTest.desktopAppEqualsMatchesTheHyphenatedName` | 0.002s | passed |
| D29 | `ConsumerFixesP3ViewTest.desktopReplyStateFrameKeepsTheTitleOnTheBody` | 0.001s | passed |
| L2 | `ConsumerFixesFrameLiveTest.crossOriginFrameTypeSelectAndShortIdPlacements` | 3.489s | passed (same run as D8/D9) |
| L3 | `ConsumerFixesP3LiveTest` live `eval` inside the step-badge method | 3.434s | passed. `--await` is `TargetVerb` `awaitPromise`. This union has no separate `--await` method. |
| L4 | `ConsumerFixesP3CliTest.startHostWithoutAWorkerNamesTheInspectCommand` | 0.005s | passed |
| L4 | `ConsumerFixesP3CliTest.emptySessionStillSaysStartASessionFirst` | 0.004s | passed |
| E1 | `ConsumerFixesP2FindTest.budgetMsZeroKeepsTheHitAndALongBudgetAddsNoField` | 0.016s | passed |
| E2 | `ConsumerFixesP2FindTest.lookupProgressPrintsAfterTwoSeconds` | 2.109s | passed |
| E9 | `ConsumerFixesP3ProtoTest.textAtIsOneInsertTextAndActionsStayAChain` | 0.002s | passed |
