# 3.0.0 PKB-HCR fixes scoreboard

Source: PKB-HCR run of 2026-11-28 on consumer `myq-release-testing`, Pickleball `199a3576`, Windows, headed Chrome (40 pass, 27 fail, 6 partial, 2 not run). Branch `3.0.0-hcr-fixes` off `3.0.0-spine`. Fixed directly on the box without Grok sessions, highest impact first. Each fix has a test that failed before the fix and passes after it. Box runs are Linux headless Chrome; the Windows and headed check is listed per row.

| Item | Report bug | Status | Commit | Tests (fail before, pass after) | Windows/headed check |
|---|---|---|---|---|---|
| PMR-11 | Parallel isolation | Done | `37f0b5c95` | `:testLive` now 70 classes; `:dom-engine:testLive` runs `ActionSmokeTest`, `LiveChromeObserveEventsTest`, `premap.PreMapStalenessTest` at one fork | Short tier at 24 forks |
| 1 | `aria-snap` printed the password in human mode (HCR-07, security) | Fixed | `94221ffd4` | `AriaSnapRedactTest` (5), `InputLiveTest.ariaSnapMasksPasswordAndOneTimeCode` (aria-snap, `--mode=ai --boxes`, `snap --aria`) | `InputLiveTest` in `:testLive` with headed Chrome |
| 2 | `cdp` and every `pw` verb hung (HCR-50/51) | Fixed (bounded). Root cause of the hang on the Windows live session not reproduced on the box | `b55bf2791` | `PwCdpTimeoutTest` (silent DevTools socket: old code waited 15 s+ and gave a different error) | HCR-50/51 on the PC live `start` session: `cdp --method=Runtime.evaluate --params={"expression":"1"}` and `pw page list` |
| 3 | `input --actions` / `--release` exit 2 `input needs --text`; `input --text --at` typed nothing, empty `--at` was ok (HCR-03/63) | Fixed | `94221ffd4` | `PageJvmRouteTest.inputActionsReleaseAndAtWorkOnTheLiveSession`, `InputLiveTest` | HCR-03, HCR-63 on the PC (A and H) |
