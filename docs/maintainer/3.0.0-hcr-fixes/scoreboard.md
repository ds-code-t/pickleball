# 3.0.0 PKB-HCR fixes scoreboard

Source: PKB-HCR run of 2026-11-28 on consumer `myq-release-testing`, Pickleball `199a3576`, Windows, headed Chrome (40 pass, 27 fail, 6 partial, 2 not run). Branch `3.0.0-hcr-fixes` off `3.0.0-spine`. Fixed directly on the box without Grok sessions, highest impact first. Each fix has a test that failed before the fix and passes after it. Box runs are Linux headless Chrome; the Windows and headed check is listed per row.

| Item | Report bug | Status | Commit | Tests (fail before, pass after) | Windows/headed check |
|---|---|---|---|---|---|
| PMR-11 | Parallel isolation | Done | `37f0b5c95` | `:testLive` now 70 classes; `:dom-engine:testLive` runs `ActionSmokeTest`, `LiveChromeObserveEventsTest`, `premap.PreMapStalenessTest` at one fork | Short tier at 24 forks |
| 1 | `aria-snap` printed the password in human mode (HCR-07, security) | Fixed | `94221ffd4` | `AriaSnapRedactTest` (5), `InputLiveTest.ariaSnapMasksPasswordAndOneTimeCode` (aria-snap, `--mode=ai --boxes`, `snap --aria`) | `InputLiveTest` in `:testLive` with headed Chrome |
| 2 | `cdp` and every `pw` verb hung (HCR-50/51) | Fixed (bounded). Root cause of the hang on the Windows live session not reproduced on the box | `b55bf2791` | `PwCdpTimeoutTest` (silent DevTools socket: old code waited 15 s+ and gave a different error) | HCR-50/51 on the PC live `start` session: `cdp --method=Runtime.evaluate --params={"expression":"1"}` and `pw page list` |
| 3 | `input --actions` / `--release` exit 2 `input needs --text`; `input --text --at` typed nothing, empty `--at` was ok (HCR-03/63) | Fixed | `94221ffd4` | `PageJvmRouteTest.inputActionsReleaseAndAtWorkOnTheLiveSession`, `InputLiveTest` | HCR-03, HCR-63 on the PC (A and H) |
| 4 | Human panel chat not pushed: `wait-any --chat=human` timed out, `chat-history` empty until a `chat-poll` (HCR-12) | Fixed | `ed1ea62de` | `HumanChatPushLiveTest.humanPanelMessageIsPushedAndWakesWaitAny` (wait-any timed out at 20 s before), `ChatCursorTest` (unchanged cursor contract: `--after=0` lists all) | Headed panel: type in the panel and press Send, `wait-any --chat=human` fires and `chat-history` shows it |
| 5 | `live-stop` printed `unknown verb live-stop` (teardown) | Fixed | `ed1ea62de` | `LiveStopVerbTest` (2; exit 2 unknown verb before), `VerbCatalogTest`, `WorkbenchAgentUsabilityTest` (catalog golden) | `pickleball live-stop` after a headed `start` |
| 6 | Recorder steps began `, in the "<top-frame CDP id>" IFrame,`; two typings became three steps (HCR-36/37) | Fixed (`--add` text keeps its single leading comma by design) | `b848eda66` | `RecordedFrameStepsTest` (2; failed before with the IFrame prefix and 3 steps), CollaborativeRecordingTest, StepEditorTest, RefinementTest, IframeRecordingTest, NoiseRecordingTest, ScriptQualityTest | Record a typing on a headed page and check the steps |
| 8 | JS dialogs auto-dismissed before the `dialogs` policy (HCR-62) | Fixed | `774538636` | `ContextLiveTest.dialogsPolicyWinsOverTheHostPlaywrightSession` (no dialog row / dismissed before), `ContextLiveTest.covers_command_dialogs` | Headed: `dialogs --policy=prompt:Ann`, `aria-snap`, then trigger a prompt |
| 7 | Stale state across sessions: recorder session `selftest-record`, stale editor/Paused bar on `record --popin` (HCR-13/36/39/40) | Fixed for the code cause (`selftest record` wrote into the real project and never restored it). `wbtest-routine` and the 106 `capture-steps` rows are project data from earlier runs (no code writes them); a `reset-state` verb is not built | `d0c15a420` | `RefinementTest.selftestRecordLeavesTheProjectsRecorderStateAlone` (state changed before), `StepEditorTest` | Headed: `selftest record`, then `record --popin` shows no stale editor |
| 3b | `input --text --at` outside the live session typed nowhere (HCR-03/63 gap) | Fixed | `38a76f016` | `InputLiveTest.textAtFocusesTheTargetOnTheProtocolPath` (`focused` absent before), `ConsumerFixesP3ProtoTest`, `PageJvmRouteTest` | Attached CDP session: `input --text=Zed --at=#u` |
| 11 | `ui-apply --ttl` ignored | Fixed | `079808da4` | `OverlayRuntimeLiveTest.uiApplyTtlRemovesWhatItPlaced` (label stayed before) | Headed: `ui-apply --ttl=10`, label gone after 10 s |
| 12b | `emulate --reset` omitted `network` from `cleared` (the service-worker half of bug 12 is not fixed) | Fixed (reset reply only) | `fe4d3ad96` | `EmulateLiveTest.resetListsTheNetworkItRestored` | none |
| 15 | `guide-back` / `guide-done` events had `by=human` | Fixed | `079808da4` | `OverlayRuntimeLiveTest.guideBackAndDoneEventsAreByAgent` (by=human before) | Headed guide: agent `guide-back`, check `overlay-poll` |
| 28b | Second `lease take` by the same holder said "another holder" | Fixed (message only; the second take is still refused by design) | `e4824649a` | `LeaseAndBlockTest.secondTakeFailsAndACleanSessionCannotBlockInput` | none |
| G1 | Stale `agent-controller.classpath` and log after export | Fixed | `6d8d67fa0` | `StaleControllerFilesTest` (2; stale files kept before) | none |
| D1 | Catalog and guidance: spine session first; `.pickleball/v/<version>/` paths and no `index.txt`; consumer S0 variant and the repo-only `URL.*` keys; comma quoting; wrong steps HCR-06/16/18/29/37/46; start-session event verbs | Fixed (docs) | `0ba02836a`, `0f0f72319` | validators (`sync_consumer_guidance.py`, `refresh_agent_index.py`, `verify_agent_contract.py`) | none |

## Not done (needs a redesign or a longer investigation)

| Item | Reason |
|---|---|
| 9 two card systems, `ask-answer` on page cards | One card API is a redesign. The catalog now uses the right verb for each card type. |
| 10 `--block=true` does not stop human `act --kind=type` | Needs the human-lock path traced on a live Windows session. |
| 12 offline emulation vs service worker | Needs emulation on worker targets. Only the `cleared` half is fixed. |
| 13 short ids scoped per run id, foreign id gives no error | An error breaks existing wait-any unit tests that watch ids with no page; needs an id-scope design. |
| 14 event streams tagged with the starter's run id | Run binding for streams, spill dirs and `downloads`: design work. |
| 16 `frame --list` says no live browser | Needs a repro on a live `start` session. |
| 17 desktop `--exe` resolved against the project | Windows-only; needs a desktop session. |
| 18 `upload --ref=#files` | Needs CSS-selector refs in the upload resolver; not a one-line change. |
| 19 `browser ports` shows none | Windows port discovery; needs the PC. |
| 20 `act --kind=press --value=Enter` reports not applied | Needs the chat panel read-back traced live. |
| 21, 22 H views miss agent surfaces; magnifier clone is clickable | View and exposure design. |
| 23 `navigate` resets dock position; `panel-open --tab` | Needs the page runtime re-injection traced on a headed page. |
| 24 popup `alwaysOnTop` | Windows `SetWindowPos`; needs the PC. |
| 25 no `human-lock override` log line | Needs a decision on where the line is logged. |
| 26 covered button `visible=true`; act `bounds` 0,0,0,0 | Needs the visibility model reconciled between `snap` and `explain`. |
| 27 `wake` fields on `chat-turn` and `watch` | Needs a reply-shape decision. |
| 28 `start --run-id` ignored; `progress` shows `session:"session"` | The envelope shows `session` when no run id is set; changing run-id binding is a design change. |
| `doctor` exempt from the spine-session gate; `live-stop` plus `doctor --prune` | Owner decision (2026-11-26 exempt list is fixed). |
| Slowdowns (client JVM start, `pkb batch`, first-call idle, `start` 23 s) | A persistent or batch client is a redesign. |
| Noise (triple errors, `recent`/`cursors` on every reply, `--compact`, `--limit` newest, `--fields` format, `eval failed` text, `main.db` size, relative `--dir`/`--exe`) | Cross-cutting reply-shape changes. |
