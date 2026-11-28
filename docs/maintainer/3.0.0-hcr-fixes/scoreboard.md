# 3.0.0 PKB-HCR fixes scoreboard

Source: PKB-HCR run of 2026-11-28 on consumer `myq-release-testing`, Pickleball `199a3576`, Windows, headed Chrome (40 pass, 27 fail, 6 partial, 2 not run). Branch `3.0.0-hcr-fixes` off `3.0.0-spine`. Fixed directly on the box without Grok sessions, highest impact first. Each fix has a test that failed before the fix and passes after it. Box runs are Linux headless Chrome; the Windows and headed check is listed per row.

| Item | Report bug | Status | Commit | Tests (fail before, pass after) | Windows/headed check |
|---|---|---|---|---|---|
| PMR-11 | Parallel isolation | Done | `37f0b5c95` | `:testLive` now 70 classes; `:dom-engine:testLive` runs `ActionSmokeTest`, `LiveChromeObserveEventsTest`, `premap.PreMapStalenessTest` at one fork | Short tier at 24 forks |
