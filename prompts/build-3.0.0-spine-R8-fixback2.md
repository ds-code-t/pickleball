# Build prompt: 3.0.0 spine R8, fix-back 2 (real run and CLI paths)

This prompt stands alone. You are a fresh headless Grok Build session on Dan's Windows PC. You have not seen the planning chat. Your job is to close the R8 items listed below, on branch `3.0.0-spine-r8` only, and then stop.

- **Repo:** https://github.com/ds-code-t/pickleball
- **Branch:** `3.0.0-spine-r8` only. Never `master`. Never `3.0.0-spine`. No tags, releases, publishing, or force-push.
- **Worktree:** `C:\Users\Admin\IdeaProjects\pkb-spine-r8`
- **Toolchain:** JDK 21. Windows commands. `.\gradlew.bat`. Never run `.\gradlew.bat --stop` or `./gradlew --stop`. Use `python`, not `python3`.

## Read first

Read these files in full before you change anything. The branch docs win over this prompt if they disagree. If they disagree, follow the docs and say so on the scoreboard.

1. `AGENTS.md`
2. `docs/roadmaps/3.0.0-spine/R8-warm-engine-resource-gate-browser-config.md` (the R8 roadmap; it is the source of truth for behavior)
3. `docs/maintainer/3.0.0-spine/R8-scoreboard.md`, including the `## Fix-back 1` section
4. `prompts/build-3.0.0-spine-R8-fixback1.md` (what fix-back 1 was asked to do)
5. `docs/release-notes.md` (the `Spine R8` paragraphs, including `Spine R8 fix-back 1.`)
6. `docs/tech-debt.md`
7. `docs/plans/2026-11-25-spine-design.md`, sections on the warm engine, resource gate, browser config, and Distribution and licensing
8. The original R8 roadmap text at commit `b79a799b`: `git show b79a799b:docs/roadmaps/3.0.0-spine/R8-warm-engine-resource-gate-browser-config.md`

Also follow the functionality-change rules in `AGENTS.md` for code, tests, docs, release notes, consumer guidance sync, and the repository index.

## State

R1, R2, R3, R4, R7, R8 P1 to P4, and R8 fix-back 1 (head `8b670594`) are on `3.0.0-spine-r8`. A static review of the pushed code found that fix-back 1 closed gaps 1, 2, 3, and 8, but gaps 4, 5, 6, and 7 are wired to a production caller and still do not deliver the roadmap behavior in a normal run. Each fix-back 1 test passes because it runs the behavior inside the test JVM (the test's own pid as the warm JVM, a stub driver registered in-process, a direct call to the handler method). Fix-back 1 also edited the R8 roadmap to describe less.

R5 is being fixed at the same time in another worktree (`C:\Users\Admin\IdeaProjects\pkb-spine`, branch `3.0.0-spine`). R6 has not started. Do no R5 or R6 work.

First commit: add a section `## Spine R8 fix-back 2 (open, found in fix-back 1 verification 2026-11-26)` to `docs/tech-debt.md` with one line per item below (number and one sentence). Push it. Remove each line in the commit that closes that item. Remove the section when all items are closed.

## The items

For each item: make the roadmap behavior happen through the real run or CLI path. Do not satisfy an item by calling a test hook from production code, by adding another test-only hook, by running the behavior inside the test JVM as a stand-in for another process, or by changing the docs or roadmap to describe less.

1. **Heap gate: the cold child does the work.** `ExecutionContext.open` calls `ResourceGate.admit`, but in the `subprocess` decision the child JVM (`tools.dscode.engine.runtime.SnapshotContextWorker`) only sleeps, and the context keeps running in-process on the application loader (`JavaStepHost.selectLoader` returns it when `context.subprocess()`). The warm JVM gets no heap relief. Make the context's work (its steps, through the native engine step execution) run in that cold child JVM on the project wrapper classpath with `-Dpkb_warm=off`, with results, failures, logs, and step status returned to the parent over a defined channel (stdin/stdout JSON or a loopback socket with a token). The child exits or returns its pool slot when the context closes. `WarmSpine` counts it. `pkb_engine_log=gate` logs the decision with the snapshot id. Test: drive `ExecutionContext.open` and a real step through the engine with an injected heap reading in the `subprocess` band, and prove the step body ran in the child (for example the step records `ProcessHandle.current().pid()` and that pid differs from the test JVM and equals the tracked child). Prove a failing step's error reaches the parent.
2. **Incremental glue compile reaches the scenario.** `ExecutionContext.open` creates the scenario's warm loader with `new URL[0]`, and `WarmSpine.loader` returns that cached loader to `JavaStepHost.selectLoader`, so `.pickleball/spine/classes` is never on it. The reload (`StoreGlueCompiler.reloadWarm`) runs in the `store run` CLI process, not the JVM that runs the scenario. The changed-source hashes live in a static map, so every new process recompiles every source one file at a time.
   - Put `.pickleball/spine/classes` on the scenario's warm loader in `ExecutionContext.open` / `WarmSpine.loader` / `JavaStepHost.selectLoader`.
   - Reload in the JVM that runs the scenario (the store worker or engine JVM), not in the `store run` process. When the classes directory changed since that JVM's loader was built, the next context gets a fresh loader.
   - Persist the changed-source hashes on disk under `.pickleball/spine/` so a new process compiles only files whose hash changed.
   - Test: edit a glue class, run a scenario through the real `store run` / engine warm path in a separate JVM from the test, and prove the new class body executed (for example the step writes its version to a file). A second run with no edit compiles nothing.
3. **`bidi` reaches the live Selenium session from the CLI.** `pickleball bidi ...` is dispatched early in `PickleballWorkbenchLauncher` and runs in the short-lived CLI JVM, where `AgentControl.scenarioDriver()` is null, so it always says there is no Selenium session.
   - Forward `bidi script` and `bidi intercept` from the CLI JVM to the JVM that holds the driver, over the existing control path the other live agent verbs use (the agent hop / live host / controller routing). Do not invent a private channel. `bidi grid-attach` keeps working.
   - `bidi intercept` gains a URL filter (`--url=<pattern>`, required or an explicit `--all`), returns an intercept id, and gains a remove (`bidi intercept off --id=<id>` or `bidi intercept remove --id=<id>`; pick one, catalog it, document it). It does not force status 200: it keeps the original status unless `--status=<code>` is given. Only matching responses are replaced.
   - Both stay off unless `pkb_selenium_bidi=on` or `browser.selenium.bidi: on`. Playwright sessions refuse `intercept`. Event names stay the five in `SeleniumBidiEvents`.
   - Update `VerbCatalog`, help, `docs/verb-inventory.md`, `docs/roadmaps/workbench-cli-inventory.json`, and the help goldens.
   - Test: start a JVM (or the live host) holding a Selenium BiDi session against a local fixture page served by a local HTTP server, run `bidi script` and `bidi intercept --url=... ` through the CLI entry point (`PickleballWorkbenchLauncher` / `VerbDispatch` in a separate process from the driver), and prove the script ran and only the matching response body was replaced. Remove the intercept and prove the original body returns. No browser download; use the locally installed Chrome or Edge.
4. **UIA structure-changed actually fires.** `WindowsStructureEvents` runs a PowerShell script block as the UI Automation handler. UIA raises the event on a non-PowerShell thread, where a script block typically fails ("no Runspace available"), so `changed` may never be written.
   - Use a C# handler compiled with `Add-Type` (or `Register-ObjectEvent` with a polling loop) that writes `changed` to stdout.
   - Start the listener only when `pkb_warm` is on (the cache only exists then).
   - When no window matches the attach name, do not watch the whole desktop. Attach still succeeds, the listener is not started, and `pkb_engine_log` / stderr records a line with `error:` and `fix:` naming the window.
   - Avoid `-EncodedCommand` if practical (write the script to a file under `.pickleball/` and run it with `-File`, or pass it another way that does not trip security tools).
   - Add a live Windows test: open a real window (a small Swing/AWT frame or a WinForms window started by the test), attach through `DesktopTree`'s live attach path with `pkb_warm=on`, populate the cache, change the window's structure (add or remove a control), and prove the cached tree is dropped through the event within a bounded wait. Detach stops the listener.
5. **Restore the roadmap.** Fix-back 1 rewrote R8 roadmap text. Restore the original spec wording from `b79a799b` for the incremental-compile paragraph (including "A new dependency in the source ... is a rebuild: stop the warm JVM, run the wrapper compile, start again"), the `Warm Playwright/CDP` paragraph, the `bidi` bullets, and the `UIA cache` paragraph. Implementation detail belongs in `docs/configuration.md` and `AGENTS.md`, not the roadmap. Then implement the original behavior: a `rebuild` decision stops the warm JVM (`spine stop` semantics), runs the wrapper compile, and starts the warm JVM again (`spine start` semantics), and reports that it did. Test it through `store run` / `StoreRun.compileGlue` with a real warm process from `spine start`, not the test's own pid.
6. **Docs say only what is implemented.** Rewrite in place `docs/configuration.md`, `docs/playwright.md`, `AGENTS.md`, `docs/release-notes.md` (the `Spine R8 fix-back 1.` paragraph stays as history; add `Spine R8 fix-back 2.`), and the guidance copies so they describe exactly the behavior items 1 to 7 deliver. Do not claim the gate moves work, the warm loader reloads edited glue, or `bidi` reaches the live session until the code does.
7. **Minor items.**
   - After a successful `cdn` download (`BrowserVersionResolver.liveDownload`), re-read the Playwright cache and return the real installed version and binary path. Do not report the requested version with a null binary.
   - Release the debug-port claim (`DebugPortOwner.releaseDriver`) on any driver quit or close, not only through `PageSurfaces.quitManaged` (for example a quit listener or decorator on the driver `DriverConstruction` builds, plus JVM shutdown).
   - Test each through the production caller: a stub downloader that populates a temp Playwright cache through the real `fromCdn` path, and a driver built through `DriverConstruction` then quit directly.

## Tests

- For each item, add a test that fails before your fix and passes after it. The test must exercise the production caller (engine step execution, `store run`, the CLI entry point in a separate process, the live desktop attach, `DriverConstruction`), not a test seam. Where a separate process is the point of the item (items 1, 2, 3, 5), the behavior must cross a real process boundary in the test.
- Before committing each fix, prove the new test fails without the fix (run it once before the production change) and say so in the scoreboard notes.
- Never weaken, skip, disable, or delete a test. No `@Disabled`, `assumeTrue` skips, or empty tests. Keep Gherkin syntax. Step annotations stay single string literals.
- Run targeted tests only: the new tests, the R8 and fix-back 1 tests that touch the code you change (`WarmClassLoaderTest`, `WarmColdParityTest`, `IncrementalGlueCompileTest`, `WarmGlueCompilePathTest`, `StoreRunTest`, `ResourceGateTest`, `SnapshotContextGateTest`, `JavaStepHostTest`, `BrowserVersionResolverTest`, `BrowserCacheAndLocalVersionTest`, `SeleniumBidiCommandTest`, `SeleniumBidiLiveSessionTest`, `DebugPortOwnerTest`, `DebugPortAttachTest`, `ApplyDebugPortTest`, `WarmCdpTest`, `HoldJvmFlagsTest`, `DesktopTreeCacheTest`, `WindowsStructureChangedTest`, `VerbCatalogTest`, `GuidanceCommandGoldenTest`), and the direct test of any other class you edit. Use `.\gradlew.bat :test --tests <class> --console=plain` and `.\gradlew.bat :dom-engine:test --tests <class> --console=plain`.
- No full-suite run: no unfiltered `:test`, `:dom-engine:test`, `:pickleball-workbench:test`, `:pickleball-control-protocol:test`, `:pickleball-control-api:test`, and no consumer `@all`. The full regression is deferred per `docs/roadmaps/3.0.0-spine/final-regression.md` on `3.0.0-spine`: it runs once, later, after the R5 fix-back, this fix-back, the merge, and R6.
- Windows only (Dan's standing rule). No Linux or macOS runs. No GitHub Actions or workflow runs, no `workflow_dispatch`, no pull request to get CI. Append every new Windows-only test class to the `**Spine R8, Windows only:**` line in `docs/tech-debt.md`.

## Docs

- Remove each item's line from `## Spine R8 fix-back 2 (open, ...)` when it closes. Remove the section when all are closed.
- Run `python scripts/sync_consumer_guidance.py` and `python scripts/refresh_agent_index.py`. Regenerate `docs/verb-inventory.md`, `docs/roadmaps/workbench-cli-inventory.json`, and the help goldens when a verb or help text changes.
- Add a `## Fix-back 2` section to `docs/maintainer/3.0.0-spine/R8-scoreboard.md` with one row per item: item number, status, commit SHA, targeted test classes with pass and fail counts, and a note that the new test failed before the fix.

## Branch rules

- Work only in `C:\Users\Admin\IdeaProjects\pkb-spine-r8` on branch `3.0.0-spine-r8`.
- Push after every change. Before every push run, each on its own: `python scripts/sync_consumer_guidance.py`, `python scripts/refresh_agent_index.py`, `git fetch origin 3.0.0-spine-r8`, `git rebase origin/3.0.0-spine-r8`, `git push origin HEAD:3.0.0-spine-r8`. If the rebase rewrites a recorded SHA, update the scoreboard in a new commit.
- Never push to `master` or `3.0.0-spine`. Do not merge or rebase onto `3.0.0-spine`. No tags, releases, publishing, or force-push. Never run `gradlew --stop`.
- Do no R5 or R6 work. Do not implement `spine session`, the Node workbench, or R5's default-session rule. Keep the R8 spine verbs in `SpineCli`.
- Never touch the `C:\Users\Admin\IdeaProjects\pkb-spine` or `C:\Users\Admin\IdeaProjects\pkb-spine-r7` folders.
- No secrets or tokens in cleartext in code, logs, commits, process arguments, or `java.args`.
- If an item genuinely needs R5 or R6 code, close everything else and record that item on the scoreboard as `waiting on R5/R6 merge` with the reason.

## Shared Maven local repository

Before any `publishToMavenLocal` or `maven-consumer-project` `mvnw` run, check whether a java, gradle, or mvn process whose command line contains `pkb-spine\` (not `pkb-spine-r7` or `pkb-spine-r8`) is running `publishToMavenLocal` or `mvnw`. If one is, wait with `Start-Sleep 120` and re-check, up to 60 minutes, before you publish. Run your consumer tag tests right after your own `publishToMavenLocal`.

## Finish

After the last push, confirm the working tree is clean and `origin/3.0.0-spine-r8` equals your HEAD. Then STOP and report the head commit SHA, the per-item scoreboard rows, and anything recorded as waiting on R5/R6.
