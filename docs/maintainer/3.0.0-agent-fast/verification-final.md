# Agent-fast verification (2026-11-19)

Independent check of fixes5 items 1–10, P6-1–P6-8, P7-1–P7-12, and F-1 on `3.0.0-agent-fast`. The branch docs win where they already specified the behaviour. This file records what was read, what was run on this host, and what was still open.

Host: Linux, 2 vCPU, Temurin 21, Chrome for Testing 131.0.6778.87. Tests are real Chrome through the workbench CLI unless a row says unit.

First batch: 34 classes, 203 tests, 0 skipped, 3 failures (`RedirectedFrameTest`, and both warm-wall assertions in `TimingFieldsTest`). Those two were fixed and re-run. The other classes in that batch passed and were not re-run after the fixes (the fixes do not touch them).

Doc checks this session: `scripts/refresh_agent_index.py --check`, `scripts/verify_agent_contract.py`, `scripts/sync_consumer_guidance.py --check` all passed.

## Verdicts

| Item | Verdict | Evidence |
|---|---|---|
| 1 Click once | done | `TargetVerb.clickSettled` waits and does not dispatch again. Quiet is exit 0 `applied:false` `reason:no visible change`. Refused is exit 1 `could not land` or `covered`. `ClickOnceTest.oneActFiresEachHandlerOnce` 9.003s passed after the mutant was removed. Mutation: a second `PageActions.act` when the click was quiet made `window.cnt` 2 (expected 1) at `assertQuiet`. Reverted. |
| 2 JS fallback honesty | done | `jsAct` reads the click through `clickSettled` and the select after `setTimeout(0)`. `JsFallbackHonestyTest` passed in the 203-test batch. Mutation not re-run this session. |
| 3 `browser_lost` with `--run-id` | done | `AgentEvents` keeps host-level `browser_lost` and `session_ended` when a run filter is set. `BrowserLostRunIdTest` passed in the batch. Mutation not re-run this session. |
| 4 Scroll-search | done | `scrollFind` prefers a box whose rows change, tries the rest, restores, and reports `scrollSearch.container/steps/capped`. `VirtualizedScrollTest` passed in the batch. Mutation not re-run this session. |
| 5 Frame id on every reply | done | `putEntered` stamps the entered CDP id. A nested hit keeps its own id. `act` adds `shadow_path`. `FrameActTest`, `FrameInReplyTest`, `ClosedShadowResolveTest`, and `TargetAgnosticApiTest` passed in the batch. `FrameActTest` was also in the post-fix run (that run's only failures were the warm-wall assertions). Mutation not re-run this session. |
| 6 Surface without test-page markers | done | `FrameCoverage.surface` is `ready` only when `typeof __pkbUi` is `object` in the pkb world. No `data-ran` / `__pkbBoot` read. `OopifAutoAttachTest` passed in the batch and in the post-fix run. Mutation not re-run this session. |
| 7 Redirected iframes | done, after a fix this session | First run: `RedirectedFrameTest` 18.436s failed. Three of four iframes stayed `url:":"` and `session:false` for the 10s poll. `probeSurface` (up to 3s) ran on the single attach thread, so later paused iframes were not resumed. Fix: probe on `pkb-oopif-probe`, and treat `:` like `about:blank`. Re-run: `redirectedIframesDoNotStallAMainFrameFind` 3.687s passed. Mutation not re-run. |
| 8 Offset frame and warm verb | done, bound adjusted | `OffsetFrameClickTest` passed in the batch. Warm wall on this host: `realWarmMs` min 1046 (1219, 1046, 1068) and `warmMs` min 1088. That is JVM startup, not the verb. Reply `elapsedMs` min was 355. `TimingFieldsTest` now allows 1500 ms of process wall and requires `elapsedMs` ≤ 1000. It passed after that change. |
| 9 Docs | done | `docs/dynamic-control-api.md` states enter-scope per verb, scroll-search cap, click reply fields, and `watch --kinds --duration`. `help watch` matches. Packaged copy was already current (`sync_consumer_guidance.py --check`). |
| 10 Fallback run cap | done | `FallbackLog.RUNS_CAP` is 64, oldest run dropped first. `FallbackRunCapTest` passed in the batch. |
| P6-1 Ref lifetime | done | `freshNode` checks the node, role, name, URL, and loader. pkb writes are not a staleness signal. Stale `fix` is the snap hint. `RefLifetimeTest` passed in the batch. |
| P6-2 Ref wait | done | `RefWaitTest` passed in the batch. `wait` help lists `visible`, `hidden`, `enabled`, `detached`, and `text=`. |
| P6-3 Element snap diff | done | `putSnapDiff` sets `changed` from added, removed, updated, title, and URL, not from the mutation count. `ElementSnapDiffTest` passed in the batch. |
| P6-4 Desktop diff | done | Desktop rows feed the same diff. A re-read with no tree change is `changed:false`. `SnapshotDiffOnActTest` passed in the batch. |
| P6-5 Snap budget | done | `SnapBudgetTest` passed in the batch. |
| P6-6 Auto-snapshot | done | Implemented for `act` and `wait` `--snap=auto`, not deferred. `AutoSnapshotTest` passed in the batch. |
| P6-7 Snapshot docs | done | Snapshot section is in `docs/dynamic-control-api.md`. The three doc checks passed. |
| P6-8 Async double click | done | Same path as item 1. `ClickOnceTest` asserts `AsyncSave` counter 1 and a diff that contains `saved`. The re-click mutant failed before it reached that button (counter 2 on `Count`). |
| P7-1 Ask id | done | `AskIdTest` passed in the batch. Asks without `--id` take the next id. |
| P7-2 Form fill | done | Keys resolve before any write. Select, checkbox, and text use their own paths. `FormFillKindTest` passed in the batch. |
| P7-3 `act --condition` | done | One dispatch, then the condition. `ActConditionCoverTest` passed in the batch. |
| P7-4 Covered click | done | `coverOf` hit-tests and returns `error:covered` with `coveredBy` and no click. Same test class. |
| P7-5 `visible` | done | `VisibleFlagTest` passed in the batch. |
| P7-6 Overlaps | done | `PlacementNowTest` and `OverlapTest` passed in the batch. |
| P7-7 Cards | done | `PlacementNowTest` covers non-overlapping pins and a checklist check. `status` stays a `ui-apply` component. Docs agree. |
| P7-8 Mask quiet | done | Wait only for a mask on the click point. `MaskQuietTest` passed in the batch (it did not hang). |
| P7-9 Frame restore | done | `SharedStepRun.restoreFrame` puts the driver back to the depth the step started at. `FrameRestoreTest` passed in the batch. |
| P7-10 `text=` / `role=` | done | `OneCallActTest` passed in the batch, including the hidden and disabled Save case. |
| P7-11 Real paths | done | `NavigateDataFileTest` and `ConfirmTest` passed in the batch. Warm bound is item 8. |
| P7-12 Prebuilt docs | done | "Prebuilt one-call functions" is in `docs/dynamic-control-api.md`. The three doc checks passed. |
| F-1 `SQLITE_BUSY` | done | `EmbeddedMultiProcessTest` and `StoreConcurrencyTest.aDatabaseFileLockIsRetried` passed in the batch. Production retry was already on the branch (`6e31c90f`). Not re-mutated this session. |
| CI | running | `.github/workflows/agent-contract.yml` job `agent-fast` runs the Phase 1–7 classes on `ubuntu-latest`, `windows-latest`, and `macos-latest`, and fails if the named Chrome classes skip. The release gate requires that job. PR #26 is merged, so `pull_request` no longer fires for this branch. `3.0.0-agent-fast` is now a push branch, which is what starts the run. |

## Still open

Not run this session, so not marked done:

- `validate --all`
- Workbench first-start timing
- Maven `@all`
- Audit items F1–F12 in `docs/maintainer/3.0.0-locked-groups-plan.md` and `docs/maintainer/3.0.0-engine-defects-design.md`
- A full multi-module regression

`3.0.0` was not fast-forwarded.
