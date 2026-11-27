# Build prompt: 3.0.0 spine R8, fix-back 3 (scenario child and warm reload)

This prompt stands alone. You are a fresh headless Grok Build session on Dan's Windows PC. You have not seen the planning chat. Your job is to close the R8 items listed below, on branch `3.0.0-spine-r8` only, and then stop.

- **Repo:** https://github.com/ds-code-t/pickleball
- **Branch:** `3.0.0-spine-r8` only. Never `master`. Never `3.0.0-spine`. No tags, releases, publishing, or force-push.
- **Worktree:** `C:\Users\Admin\IdeaProjects\pkb-spine-r8`
- **Toolchain:** JDK 21. Windows commands. `.\gradlew.bat`. Never run `.\gradlew.bat --stop` or `./gradlew --stop`. Use `python`, not `python3`.

## Read first

Read these files in full before you change anything. The branch docs win over this prompt if they disagree. If they disagree, follow the docs and say so on the scoreboard.

1. `AGENTS.md`
2. `docs/roadmaps/3.0.0-spine/R8-warm-engine-resource-gate-browser-config.md` (the R8 roadmap; it is byte-identical to commit `b79a799b` and is the source of truth for behavior. Do not edit it.)
3. `docs/maintainer/3.0.0-spine/R8-scoreboard.md`, including the `## Fix-back 1` and `## Fix-back 2` sections
4. `prompts/build-3.0.0-spine-R8-fixback2.md`
5. `docs/release-notes.md` (the `Spine R8` paragraphs, including `Spine R8 fix-back 1.` and `Spine R8 fix-back 2.`)
6. `docs/tech-debt.md`
7. `docs/configuration.md`, `docs/playwright.md`
8. `docs/plans/2026-11-25-spine-design.md`, sections on the warm engine, resource gate, and browser config

Also follow the functionality-change rules in `AGENTS.md` for code, tests, docs, release notes, consumer guidance sync, and the repository index.

## State

R8 fix-back 2 (head `24b266e2`) is on `3.0.0-spine-r8`. A static review found:

- **Heap-gate child.** `SharedStepRun.call` sends each step to `SnapshotContextWorker` through `SnapshotChannel`, and the child runs every step as its own one-step `PickleballEngine.run`. Scenario state (glue instances, scenario variables, browser and context state) does not carry between steps of one scenario. `SubprocessStepTest` only uses one-step scenarios.
- **Incremental glue reload.** `ExecutionContext.open` / `JavaStepHost.selectLoader` now put `.pickleball/spine/classes` on the warm loader, and hashes persist in `.pickleball/spine/source-hashes.txt`. But `StoreRun.execute` still calls `stop(root)` after every glue compile, incremental ones included, so the worker restarts and the in-JVM reload never happens in production. `WarmGlueScenarioTest` drives its own `main()` harness in a fresh JVM per version, not `store run` and not one long-lived worker.
- **Debug port.** `DriverConstruction.ready` installs a command executor that releases the debug-port claim on `QUIT` and also on `CLOSE`. A single-window `close` while the browser is still up frees the port.
- **CDN.** `BrowserVersionResolver.installedAfterDownload` can fall back to a different cached version of the channel after a download without saying so.

R5 is being fixed at the same time in another worktree (`C:\Users\Admin\IdeaProjects\pkb-spine`, branch `3.0.0-spine`). R6 has not started. Do no R5 or R6 work.

First commit: add a section `## Spine R8 fix-back 3 (open)` to `docs/tech-debt.md` with one line per item below (number and one sentence). Push it. Remove each line in the commit that closes that item. Remove the section when all items are closed.

## The items

For each item: make the roadmap behavior happen through the real run or CLI path. Do not satisfy an item by calling a test hook from production code, by adding another test-only hook, by running the behavior inside the test JVM as a stand-in for another process, or by changing the docs or roadmap to describe less.

1. **One child scenario per parent scenario.** When the gate sends a context to the cold child, the child keeps one scenario's state across all of that scenario's steps: one child scenario (one glue instance set, one scenario context) per parent scenario, not one engine run per step. Results, step status, returned values, logs, and failures still return to the parent per step over the existing `SnapshotChannel` JSON lines, and the parent's report matches a cold run of the same scenario. Close ends that child scenario and returns the pool slot. Test: a multi-step scenario through `PickleballEngine.run` with an injected heap reading in the `subprocess` band, where step 1 stores a value on a glue instance field or scenario variable, step 2 reads it and records the child pid, and step 3 fails. Prove the value carried over, every step ran in the same child pid (not the test JVM), and the failure and results reached the parent.
2. **Incremental glue reloads inside the long-lived worker.** Read the restored roadmap's incremental-compile paragraph and follow it exactly. Where it says edited glue is compiled with `javax.tools` into the warm classes and reloaded through the warm loader without a restart, stop `StoreRun.execute` from stopping the worker after an incremental-only compile, so the next scenario in that same worker JVM gets a fresh warm loader with the new class. Keep the stop, wrapper compile, start path (`rebuildAndRestart`) for a `rebuild` decision exactly as fix-back 2 item 5 implemented. Test: a `store run` test where one warm worker JVM runs a scenario, the glue source is edited, the next `store run` reuses the **same worker pid**, compiles incrementally (`detail=incremental`), and executes the new class body; a third run with no edit compiles nothing. If, after reading it, the roadmap truly requires a worker restart for every compile, implement that instead, correct the docs, and say so explicitly on the scoreboard with the roadmap sentence you followed.
3. **Debug port and cdn reporting.**
   - Release the debug-port claim on driver `quit` (and JVM shutdown) only, not on a single-window `close` while the browser is still up. Test through `DriverConstruction`: `close()` of one window keeps the claim, `quit()` releases it.
   - After a `cdn` download, if the cache read-back falls back to a different version than the one requested or downloaded, say so in the resolution output and the engine log (requested, downloaded, and used versions). Test through the real `fromCdn` path with a stub downloader that populates a temp Playwright cache.
4. **Docs.** Rewrite in place `docs/configuration.md`, `docs/playwright.md`, `AGENTS.md`, the guidance copies, and add a `Spine R8 fix-back 3.` release-note paragraph, so they describe item 1's per-scenario child behavior and item 2's real reload behavior accurately, plus item 3. Keep earlier fix-back paragraphs as history. Do not edit the roadmap.

## Tests

- For each item, add a test that fails before your fix and passes after it. The test must exercise the production caller (engine step execution, `store run` and its worker, `DriverConstruction`, `BrowserVersionResolver`), not a test seam. Where a separate process is the point of the item (items 1 and 2), the behavior must cross a real process boundary in the test.
- Before committing each fix, prove the new test fails without the fix (run it once before the production change) and say so in the scoreboard notes.
- Never weaken, skip, disable, or delete a test. No `@Disabled`, `assumeTrue` skips, or empty tests. Keep Gherkin syntax. Step annotations stay single string literals.
- Run targeted tests only: the new tests, and the R8 tests that touch the code you change (`SubprocessStepTest`, `SnapshotContextGateTest`, `ResourceGateTest`, `WarmColdParityTest`, `WarmClassLoaderTest`, `JavaStepHostTest`, `ExecutionContextTest`, `WarmGlueScenarioTest`, `WarmGlueCompilePathTest`, `IncrementalGlueCompileTest`, `StoreRunTest`, `WarmRebuildRestartTest`, `DebugPortQuitTest`, `DebugPortOwnerTest`, `DebugPortAttachTest`, `ApplyDebugPortTest`, `DriverConstructionProtocolTest`, `CdnDownloadRereadTest`, `BrowserVersionResolverTest`, `BrowserCacheAndLocalVersionTest`, `SpineR8FixBack2DocsTest`, `CurrentDocsTest`, `GuidanceCommandGoldenTest`), and the direct test of any other class you edit. Use `.\gradlew.bat :test --tests <class> --console=plain`.
- No full-suite run: no unfiltered `:test`, `:dom-engine:test`, `:pickleball-workbench:test`, `:pickleball-control-protocol:test`, `:pickleball-control-api:test`, and no consumer `@all`. The full regression is deferred per `docs/roadmaps/3.0.0-spine/final-regression.md` on `3.0.0-spine`.
- Windows only (Dan's standing rule). No Linux or macOS runs. No GitHub Actions or workflow runs, no `workflow_dispatch`, no pull request to get CI. Append every new Windows-only test class to the `**Spine R8, Windows only:**` line in `docs/tech-debt.md`.

## Docs

- Remove each item's line from `## Spine R8 fix-back 3 (open)` when it closes. Remove the section when all are closed.
- Run `python scripts/sync_consumer_guidance.py` and `python scripts/refresh_agent_index.py`. Regenerate `docs/verb-inventory.md`, `docs/roadmaps/workbench-cli-inventory.json`, and the help goldens when a verb or help text changes.
- Add a `## Fix-back 3` section to `docs/maintainer/3.0.0-spine/R8-scoreboard.md` with one row per item: item number, status, commit SHA, targeted test classes with pass and fail counts, and a note that the new test failed before the fix. A row with any failing test is not `done`.

## Branch rules

- Work only in `C:\Users\Admin\IdeaProjects\pkb-spine-r8` on branch `3.0.0-spine-r8`.
- Push after every change. Before every push run, each on its own: `python scripts/sync_consumer_guidance.py`, `python scripts/refresh_agent_index.py`, `git fetch origin 3.0.0-spine-r8`, `git rebase origin/3.0.0-spine-r8`, `git push origin HEAD:3.0.0-spine-r8`. If the rebase rewrites a recorded SHA, update the scoreboard in a new commit.
- Never push to `master` or `3.0.0-spine`. Do not merge or rebase onto `3.0.0-spine`. No tags, releases, publishing, or force-push. Never run `gradlew --stop`.
- Do no R5 or R6 work. Keep the R8 spine verbs in `SpineCli`.
- Never touch the `C:\Users\Admin\IdeaProjects\pkb-spine` or `C:\Users\Admin\IdeaProjects\pkb-spine-r7` folders.
- No secrets or tokens in cleartext in code, logs, commits, process arguments, or `java.args`.
- If an item genuinely needs R5 or R6 code, close everything else and record that item on the scoreboard as `waiting on R5/R6 merge` with the reason.

## Shared Maven local repository

Before any `publishToMavenLocal` or `maven-consumer-project` `mvnw` run, check whether a java, gradle, or mvn process whose command line contains `pkb-spine\` (not `pkb-spine-r7` or `pkb-spine-r8`) is running `publishToMavenLocal` or `mvnw`. If one is, wait with `Start-Sleep 120` and re-check, up to 60 minutes, before you publish. Run your consumer tag tests right after your own `publishToMavenLocal`.

## Finish

After the last push, confirm the working tree is clean and `origin/3.0.0-spine-r8` equals your HEAD. Then STOP and report the head commit SHA, the per-item scoreboard rows, and anything recorded as waiting on R5/R6.
