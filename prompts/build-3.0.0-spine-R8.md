# Grok Build prompt: pickleball 3.0.0 spine R8

You are a fresh headless Grok Build session on Dan's Windows PC. You have not seen the planning chat. Implement R8 only, in full. This is the last roadmap. R1 through R7 are on this branch. Do not redo them.

- **Repo:** https://github.com/ds-code-t/pickleball
- **Branch:** `3.0.0-spine` only. Never `master`, never a 2.x branch, never `3.0.0`. No tags, releases, publishing, or force-push.
- **Roadmap (the spec):** `docs/roadmaps/3.0.0-spine/R8-warm-engine-resource-gate-browser-config.md`
- **Design (decisions are closed):** `docs/plans/2026-11-25-spine-design.md`. The warm classloader, the resource gate, the browser version ladder, and the Playwright-default adapter are closed. Do not reopen them.
- **Index:** `docs/roadmaps/3.0.0-spine/README.md`
- **Scoreboard:** `docs/maintainer/3.0.0-spine/R8-scoreboard.md`
- **Toolchain:** JDK 21. Windows commands. `.\gradlew.bat` and `.\maven-consumer-project\mvnw.cmd`. Do not use `./gradlew`. Never run `.\gradlew.bat --stop` or `./gradlew --stop`. `python`, not `python3`.

## Read first

1. `AGENTS.md` (execution engine, browser protocols, Control Engine, driver startup, Playwright packaging).
2. The design sections on the warm JVM, the three layers, the resource gate, and browser configuration.
3. The R8 roadmap, every phase, including the full-regression command list.
4. `JavaStepHost.java`, `GlueStaticScan.java`, `StoreGlueCompiler.java`, `PlaywrightConnection.java`, `ProtocolSelector.java`, `DriverConstruction.java`, `SeleniumBidiEvents.java`, `DesktopTree.java`, the consumer `CHROME_HEADLESS.yaml`.

The roadmap names the classes, properties, verbs, and test classes. Use those names. Do not invent a parallel set. P1, P2, and P3 run only the targeted commands in those phases. P4 runs its two targeted commands, and only after those pass runs the full Windows regression.

## Hard rules (every phase)

- Every phase is mandatory and must be completed in full. Nothing is optional or deferred except Linux and macOS execution of tests, which stays in `docs/tech-debt.md`. Do not start another roadmap. There is not one.
- P1, P2, and P3 run targeted tests only. Do not run `@all` before P4's full regression. Do not run the full regression before P4's targeted tests are green.
- Testing is on Windows only. Append any new Windows-only class to the Linux and macOS section of `docs/tech-debt.md`. Do not delete that section. In P4, append every new Windows-only live class from R1 through R8 that did not run on Linux or macOS. Do not remove classes that were already listed. Do not run Linux or macOS. The Safari live run is a macOS gap. The Windows test asserts channel `safari` selects Selenium without starting a process.
- Never weaken, skip, or delete a test. Keep Gherkin syntax. Step annotations stay single string literals. A full-regression failure stops the roadmap until it is fixed honestly. Do not delete the failing test. Do not mark P4 done on a red suite.
- `pkb_warm` defaults to `off`. CI and `mvn test` stay cold unless a test sets the flag. Correctness does not depend on the warm JVM. A behavior P1 adds has a cold assertion and a warm assertion. The reload-identity assertion is warm-only and has a cold twin that proves the same scenario passes on the application classloader.
- `WarmClassLoader` is parent-last only for packages in the ingested glue attribute. `pkb_warm_packages` overrides that list. `tools.dscode`, `dev.domengine`, and `io.cucumber` are always parent-first and are never reloaded. The loader is held by `ExecutionContext`, keyed in `WarmSpine` by snapshot id. It is not stored in a `ThreadLocal`. `close()` runs on replace. `loadClass` on a closed loader throws `IllegalStateException`.
- The static-field warning text stays exactly: `warn: static mutable field <Class>.<field> will survive a warm reload; move it to scenario state`. The warning does not fail hydrate. `GlueStaticScan` already emits it. Do not make it a failure.
- Incremental compile uses `javax.tools.JavaCompiler` inside `StoreGlueCompiler`. The first compile on `spine start` may shell the project wrapper once. Later single-file changes do not spawn Maven or Gradle. A `cannot find symbol` or `package does not exist` after a single-file compile forces a rebuild: stop the warm JVM, run the wrapper compile, start again. Do not guess from the import list alone. Maven and Gradle remain CI and the first full compile. Do not embed Maven or Gradle in MCP or the launcher.
- Subprocess JVMs use the project wrapper classpath, are cold, and do not inherit the warm loader. `pkb_worker_pool` default is 1. At or above the refuse line with a full pool, the warm spine does not accept the work in-process. The error is `error: heap gate refused a new context` and `fix: spine sample`.
- Do not reopen `cross.db`. Layer 3 shipped in R7.
- Browser download happens only when YAML `browser.source` is `cdn` or `any`. Default `local` never downloads and never reads `%LOCALAPPDATA%\ms-playwright`. A blank `source` and a missing `browser:` block keep today's no-download path. Do not set `CHROME_HEADLESS.yaml` to `source: cdn`. In P3 set `browser.channel: chrome` and `source: local` explicitly in the consumer headless YAML so the P4 `@all` run is not an accidental adapter flip. Read that YAML first and make the smallest edit that states the adapter the scenarios already use.
- Playwright is the default adapter for `chrome`, `msedge`, `chromium`, `firefox`, and `webkit` once `browser.channel` is set. `safari` forces Selenium `safaridriver`. `pkb_protocol=selenium` forces Selenium. A Playwright connect failure when the resolved adapter was Playwright fails with `error:` and `fix:` and does not fall back to Selenium. Configs that do not set `browser.channel` keep today's `pkb_protocol` resolution.
- `PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1` stays for every path whose source is not `cdn` or `any`. Host override is `PLAYWRIGHT_DOWNLOAD_HOST` from run var `pkb_browser_cdn_host`. `BrowserLog` entity `resolve` logs the requested version, the source, and the resolved version. It does not log a download URL that contains a token.
- BiDi events stay the five names already in `SeleniumBidiEvents`, still gated by `pkb_scenario_events=on`. Do not add events. New commands live in `SeleniumBidiCommands` and stay off unless `browser.selenium.bidi: on` or `pkb_selenium_bidi=on`. Recording stays `pw codegen`. Do not add a Selenium recorder. `AgentProtocolController` stays the escape hatch and is not a catalog verb for raw methods. Add `browser ports` only when no existing verb lists ports. Search `WorkbenchAgentCommands` first.
- `framePolicy` default `top` does not change current finds. `iframe` is how a consumer expresses leftover 6.1.5. Ingest stores `frame_policy` in `run_variables`. This is separate from the R2 phrase context words.
- One owner per debugging port. A second attach to the same port fails. `DebugPortOwnerTest` uses port 9222.
- Desktop UIA cache is Windows-tested. Do not call `grim`. Wayland stays unsupported with the existing error and fix. `pkb_warm=off` does not cache.
- Workbench isolation holds. Do not add `implementation project(':')`.
- Docs describe only the behavior this phase implemented. Rewrite them in place. History goes in `docs/release-notes.md`. Do not replace `AGENTS.md` wholesale. Remove tech-debt row 6.1.5 in P3. Remove 6.3.6 and 6.7.3 in P4. Do not add links to `docs/roadmaps/`, `docs/maintainer/`, or `prompts/` inside `docs/tech-debt.md`. After P4, `docs/tech-debt.md` has no leftover from the spine README mapping except the Linux and macOS section.
- Before every push, each command on its own:

```
python scripts/sync_consumer_guidance.py
python scripts/refresh_agent_index.py
```

Update `tools.dscode.browser.SurfaceAssetsTest` only if surface-asset bytes change.

- A new verb updates, in the same commit: the CLI help, `docs/roadmaps/workbench-cli-inventory.json`, `docs/verb-inventory.md`, and `documented-help.txt`.
- Record the implementation SHA, the test command, and the pass count on `docs/maintainer/3.0.0-spine/R8-scoreboard.md` after each green phase, in the same commit as the code or in the immediately following commit before the push. P4's notes include the full-regression pass and fail counts.
- Fetch and rebase before every push, including after a regression fix. Each git command stands alone. Do not chain with `&&`, `;`, or a pipe. Never force-push. Never push to `master`.

```
git fetch origin 3.0.0-spine
git rebase origin/3.0.0-spine
git push origin HEAD:3.0.0-spine
```

- Stop only on an honest test failure you cannot fix without weakening a test, or on a hard-rule violation. Report what you ran and what failed. Do not mark a phase done when its tests were not run.

## CLI-first and logging

Verbs: `spine start`, `spine stop`, `spine warm status`, `spine sample --json`. Browser resolution is not a new verb. It runs inside the existing browser start. `bidi script`, `bidi intercept`, and `bidi grid-attach` are CLI verbs, off unless the BiDi flag is on. `spine sample` prints `heapUsed`, `heapMax`, `nonHeapUsed`, `gcTimeMs`, `threadCount`, and `decision` (`allow`, `subprocess`, or `refuse`). `pkb_engine_log=host` entity `warm` logs start, stop, reload, and rebuild. `pkb_engine_log=gate` logs samples, decisions, and desktop cache `hit`, `miss`, or `refuse`. `BrowserLog` entity `resolve` logs version resolution. Off unless named. Date-stamped, size-capped. Do not add a new logger class. MCP and the Node renderer only forward.

## Phases

Implement P1, then P2, then P3, then P4. The roadmap is the acceptance criteria.

- **P1.** `WarmClassLoader`, `WarmSpine`, `spine start` / `spine stop` / `spine warm status`. `spine start` writes `.pickleball/spine/warm.json`. `store run` without a warm JVM stays cold. `JavaStepHost` uses the application loader when `pkb_warm=off` and `WarmClassLoader` for consumer glue packages when `pkb_warm=on`. `IncrementalGlueCompileTest` runs `JavaCompiler` for real on a fixture that compiles. It does not require a full Maven download. The missing-package case asserts the rebuild decision and the wrapper command list.
- **P2.** `ResourceGate` via `MemoryMXBean`, the GC bean, and `ThreadMXBean`. Defaults: `pkb_heap_subprocess_percent` 70, `pkb_heap_refuse_percent` 85, `pkb_worker_pool` 1. Injected readings: 50 `allow`, 75 with a free slot `subprocess`, 90 with a full pool `refuse`. A real subprocess test uses a tiny main that prints `up` and exits. The pool returns to zero after `spine stop`. Do not reopen `cross.db`.
- **P3.** The browser YAML block in the roadmap, inside the existing browser config files. `BrowserVersionResolver`. Version precision: `131` any 131, `131.0` newest patch of that minor, `131.0.6778.69` exact, `latest` the highest the source allows. `local` plus a version is that version or a failure. `local` with no version is any local browser of that channel. `cdn` uses the cache, then a download, then `resolve cache-fallback` when the CDN is down and a cache exists. `any` is local, then `cdn`. Quiet Selenium fallback is removed only when the resolved adapter was Playwright. `SeleniumBidiCommands`. `DebugPortOwnerTest`. `framePolicy` ingested as `frame_policy`. Remove tech-debt row 6.1.5.
- **P4.** Warm CDP only when `pkb_warm=on` and `ResourceGate` returns `allow`. `refuse` does not open it. `pkb_warm=off` does not open it. Closed on `spine stop`. A second start reuses it. `DesktopTree` caches one tree per attached window id when `pkb_warm=on`, dropped on structure-changed, and refused by the gate when the tree would cross the heap line (read-through, not stored). Remove tech-debt rows 6.3.6 and 6.7.3. Targeted tests first:

```
.\gradlew.bat :test --tests tools.dscode.launcher.WarmCdpTest --console=plain
.\gradlew.bat :dom-engine:test --tests dev.domengine.desktop.DesktopTreeCacheTest --console=plain
```

Then, only after those pass, the full Windows regression. Each command stands alone. `@all` is allowed only here, because this is the end of the last roadmap, not a workbench migration check.

```
.\gradlew.bat :test --console=plain
.\gradlew.bat :dom-engine:test --console=plain
.\gradlew.bat :pickleball-workbench:test --console=plain
.\gradlew.bat :pickleball-control-protocol:test --console=plain
.\gradlew.bat :pickleball-control-api:test --console=plain
.\gradlew.bat publishToMavenLocal
.\maven-consumer-project\mvnw.cmd -f maven-consumer-project\pom.xml -U test -Dpkb_runvars.pkb_browser=CHROME_HEADLESS -Dpkb_runvars.pkb_tags=@all
python scripts/verify_agent_contract.py
python scripts/refresh_agent_index.py --check
python scripts/sync_consumer_guidance.py --check
```

Record pass and fail counts on the R8 scoreboard. A failure stops the roadmap until it is fixed honestly.

## Finish

You are done when P1 through P4 are `done` on the scoreboard, each with a SHA and a test count, P4's notes include the full-regression counts, and the branch is pushed. Linux and macOS were not run. Reply with the scoreboard table and the tip SHA.
