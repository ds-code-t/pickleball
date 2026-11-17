# Pickleball 3.0.0: AI agent blockers

**Context:** One AI agent spent about 2 hours testing Pickleball Workbench against MyQ DEV (Pega) with a visible Chrome browser. It covered browser control, in-page chat and overlays, run history, the timeline, and the Store test database.

## Hard bugs
1. **The worker locks up on Pega pages.** About 2 minutes after the chat panel opens, the worker's JDK `HttpClient` selector thread gets stuck in a loop (321 s of CPU in 548 s). After that, every page and chat call times out at 60 s. `workbench_worker_restart` stops the old worker but never starts a new one. Reproduced twice.
2. **Legacy glue fails in the warm JVM.** DataTable steps throw "DataTable was created without a converter", and state set by the consumer's `@Before` hooks is null. The existing MyQ login steps can't run.
3. **Run history records the wrong results.** Skipped steps and failures inside `RUN SCENARIOS` are stored as `PASSED`, so a failed run shows `PASSED failed=0`. Nested component steps aren't stored.
4. **Entity references don't resolve.** `<entity:Operator#ae-mgr-all.app>` stayed literal during a run, even though the `.entity` file is there.
5. **Reusing a run id overwrites results.** Running `isolate` on a finished discover run id turned its `FAILED` result into `PASSED`.
6. **Agent runs are missing from the run catalog,** so `query runs` and the Explorer tab never show them.
7. **Smaller defects:**
   - The `ui chat` op returns `ok` but nothing appears.
   - `inspect-page` reports no session while one is live.
   - The documented `java -jar pickleball-3.0.0.jar domengine` fails with `NoClassDefFoundError`.
   - The diagnostic step text is `null`.
   - `store steps list` drops nested `:` steps.
   - `store run` (the "fast path") took 247 s for four log steps.

## Missing instructions
1. The CLI can't reach the browser. Page UI only works through MCP or the GUI.
2. The consumer repo bans IDE MCP, but the guide's page-UI path needs MCP.
3. "Headless Workbench" doesn't mean a headless browser. With no project browser set, it falls back to `CHROME_HEADLESS`.
4. Consumer hooks and DataTable converters don't apply in the warm JVM.
5. `query` and `store` are two history sources that disagree, and neither doc says which one to trust.
6. Nothing explains how to open a chosen run in the Explorer or Tracking tab.
7. The step id format for `store entity use` isn't documented, and neither is how to build the search index.
8. `dalton ...` in the release notes is the old name for `store ...`.

## Missing tools (the agent's workaround and what it cost)
| Missing | Workaround | Cost |
|---|---|---|
| Fast launcher (each `mvnw` call takes 10–16 s) | Cached classpath plus a `java -cp` script, 1–3 s | About 10 s per call, about 60 calls |
| Agent MCP client | Custom stdio-to-HTTP relay, 0.04–0.6 s per call | About 10 min |
| "Open browser at URL, ready" verb | Temporary feature using `discover`, `isolate` and `execute-step` | About 5 min, then dropped |
| Route from the CLI to a live browser | Probed the session server, the bridge and the DOM attach, then used MCP | About 10 min |
| Chat push or a blocking wait | Polling loops | About 30 s or more per reply |
| Bridge health signal | Manual thread dump and CPU sort | About 5 min |
| History summary or web dashboard | Many single queries at 1–8 s each | Minutes per set of questions |
| Open a run in Explorer from the CLI | None; the user picks the run by hand | Blocked |

## Top fixes
1. Fix the worker lock-up and add a watchdog that reports a stuck bridge.
2. Add `start --url= --browser= --env=`, which returns a live session that CLI page commands are sent to.
3. Provide a supported agent route (MCP or CLI) that the exported guide allows, plus `chat_wait`.
4. Make history accurate: a `SKIPPED` status, nested step rows, agent runs in the catalog, and one source of truth.
5. Add `dashboard --serve`, a local web page with run history, per-run replay and logs.
