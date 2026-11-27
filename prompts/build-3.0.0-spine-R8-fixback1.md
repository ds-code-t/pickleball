# Build prompt: 3.0.0 spine R8, fix-back 1 (production wiring)

This prompt stands alone. You are a Grok Build session. Your job is to close the eight R8 production-wiring gaps listed below, on branch `3.0.0-spine-r8` only, and then stop.

## Read first

Read these files in full before you change anything:

1. `AGENTS.md`
2. `docs/roadmaps/3.0.0-spine/R8-warm-engine-resource-gate-browser-config.md` (the R8 roadmap; it is the source of truth for behavior)
3. `docs/maintainer/3.0.0-spine/R8-scoreboard.md`
4. `docs/tech-debt.md`, section `## Spine R8 production wiring (open, found in R8 verification 2026-11-26)` (added at commit `f468f155`)

Also follow the functionality-change rules in `AGENTS.md` for code, tests, docs, release notes, consumer guidance sync (`python scripts/sync_consumer_guidance.py`), and the repository index (`python scripts/refresh_agent_index.py`).

## State

R1, R2, R3, R4, R7, and R8 P1 to P4 are on `3.0.0-spine-r8`. Each R8 class and its targeted test exists, but the paths below are reached only through test hooks (`*ForTests`, injected suppliers, inline holds). Production code never calls them. The docs (`docs/configuration.md`, `docs/release-notes.md`, `docs/playwright.md`, `AGENTS.md`) already describe the roadmap behavior. Your work makes production match those docs.

R5 and R6 are not on this branch. R5 is being built at the same time in another worktree.

## The eight gaps

For each gap: wire a real production caller so the roadmap behavior happens in a normal run. Do not satisfy a gap by calling the test hook from production code, by adding another test-only hook, or by changing the docs to describe less.

1. **`cdn` reads the Playwright cache first.** `tools.dscode.browser.BrowserVersionResolver.cachedInstalls()` returns `List.of()` outside tests. Make it list the real Playwright browser cache: `PLAYWRIGHT_BROWSERS_PATH` when set, otherwise Playwright's default per-OS location (`%LOCALAPPDATA%\ms-playwright` on Windows). Read each browser's directory and its version from Playwright's own metadata or the browser binary. Roadmap: `cdn` uses the Playwright cache, then a download through Playwright's downloader. If the CDN cannot be reached and a cached build exists, it uses the cache and logs `resolve cache-fallback`. `local`, a blank source, and a missing block still never read the Playwright cache and never download.
2. **Real local install versions.** Local installs from `platform()` carry a blank version, so `matches()` is always false for `local` plus a `version`. Detect the real version of each registered local install (on Windows, the Chrome and Edge locations already probed for the driver; read the version from the install's version directory or file version, without starting the browser). Roadmap: `131` is any 131 build, `131.0` is that minor's newest patch, `131.0.6778.69` is that exact build, `latest` is the highest the source allows. `local` with no version is any local browser of that channel. `local` with a version is that version or a failure with `error:` and `fix:`. A local miss never downloads.
3. **One owner per debug port in the real attach path.** `tools.dscode.browser.DebugPortOwner.attach` has no production caller (the claim was removed from `DriverConstruction.applyDebugPort`). Call it from the real CDP and driver attach paths (`DriverConstruction` Chrome and Edge debug-port attach, and the Playwright `connectOverCdp` path in `PlaywrightConnection` or `ProtocolSelector`), and release the claim when that session closes. Roadmap: one owner per debug port; a second attach to the same port fails with `error:` and `fix:`; the owner is recorded in the session row the launcher already keeps; `browser ports` lists owners. A launch that only sets a port for a browser it starts itself must still work (find out why the claim was removed from `applyDebugPort` and solve that case, do not drop the rule).
4. **Heap gate on new snapshot contexts.** `tools.dscode.engine.runtime.ResourceGate.admit` has no production caller. Wire `ResourceGate` into the point where a new snapshot context is created (`ExecutionContext` / `JavaStepHost` / the warm run path) when `pkb_warm=on`. Roadmap: below `pkb_heap_subprocess_percent` the context runs in-process; between the lines a free `pkb_worker_pool` slot starts a cold subprocess JVM on the project wrapper classpath that does not inherit the warm loader, and `WarmSpine` tracks it; at or above `pkb_heap_refuse_percent` with a full pool the call fails with `error: heap gate refused a new context` and `fix: spine sample`. `pkb_engine_log=gate` logs each decision with the snapshot id as the entity. `pkb_warm=off` keeps today's cold path with no gate.
5. **UIA structure-changed drops the cached tree.** `dev.domengine.desktop.DesktopTree.structureChanged` has no caller. Subscribe to the Windows UI Automation structure-changed event for each attached window (in the live Windows desktop backend that `DesktopTree` already uses) and call `DesktopTree.structureChanged(windowId)` from that handler. Unsubscribe on detach. Roadmap: one cached tree per attached window id when `pkb_warm=on`; a structure-changed event drops that window's entry; `pkb_warm=off` does not cache. Other platforms keep their current behavior and must not fail.
6. **Incremental glue compile in the real compile path.** `tools.dscode.store.StoreGlueCompiler.warmCompile` and `compileIncremental` have no production caller; only `spine start` runs `warmStart`. When `pkb_warm=on` and the warm JVM is up, the real glue compile path (`StoreGlueCompiler.compile`, called from `StoreRun`, and the warm reload path that `JavaStepHost` / `WarmSpine` use) must compile only files whose source hash changed with `javax.tools.JavaCompiler` into `.pickleball/spine/classes`, reload the warm loader, and not spawn Maven or Gradle. A diagnostic with `cannot find symbol` or `does not exist` reports `rebuild` and uses the wrapper path. `pkb_warm=off` keeps today's wrapper compile.
7. **BiDi script and intercept on the live Selenium session.** No production code binds `tools.dscode.browser.SeleniumBidiCommands` to a live Selenium session, so `bidi script` and `bidi intercept` always say there is no session. Bind them to the live Selenium driver the launcher or workbench already holds (through `DriverConstruction` / the current session registry), using Selenium BiDi (`script` module for `bidi script`; network interception that replaces the response body for `bidi intercept`). Roadmap: both stay off unless `pkb_selenium_bidi=on` or `browser.selenium.bidi: on`; `bidi intercept` is a Selenium response-body replacement and Playwright sessions refuse it; the scenario event names stay the five in `SeleniumBidiEvents`; recording stays `pw codegen`. `bidi grid-attach` already works; keep it.
8. **The hold JVM gets the warm flags.** `SpineCli.spawn` writes only `-cp` into `java.args`. Pass the starting JVM's `-Dpkb_warm`, `-Dpkb_warm_packages`, `-Dpkb_heap_subprocess_percent`, `-Dpkb_heap_refuse_percent`, `-Dpkb_worker_pool`, and the CDP endpoint property that `PlaywrightConnection.configuredEndpoint` reads, when set, into the hold JVM's `java.args`. Do not pass secrets or tokens in cleartext; if an endpoint carries credentials, pass it by environment, not in `java.args`.

## Tests

- For each gap, add a test that fails before your fix and passes after it. The test must exercise the production caller (the real resolver path, attach path, context creation, event handler entry point, compile path, CLI verb with a bound session, or `spine start` argument file), not the test hook alone. Where a real browser, UIA event, or download cannot run in a unit test, drive the production entry point with a local stand-in (a temporary Playwright cache directory, a fake install directory with a version folder, a local HTTP server, a stub driver registered through the real session registry, the handler method the native callback invokes). No real browser download in any test.
- Before committing each fix, prove the new test fails without the fix (for example by running it once before the production change) and say so in the scoreboard notes.
- Never weaken, skip, disable, or delete a test. No `@Disabled`, `assumeTrue` skips, or empty tests. Keep Gherkin. Step annotations stay single string literals.
- Run targeted tests only: the new tests, the existing R8 tests that touch the code you change (`WarmClassLoaderTest`, `WarmColdParityTest`, `IncrementalGlueCompileTest`, `ResourceGateTest`, `BrowserVersionResolverTest`, `SeleniumBidiCommandTest`, `DebugPortOwnerTest`, `WarmCdpTest`, `DesktopTreeCacheTest`), and any other class you edit's direct test. Use `.\gradlew.bat :test --tests <class> --console=plain` and `.\gradlew.bat :dom-engine:test --tests <class> --console=plain`.
- No full-suite run: no unfiltered `:test`, `:dom-engine:test`, `:pickleball-workbench:test`, `:pickleball-control-protocol:test`, `:pickleball-control-api:test`, and no consumer `@all`. The full regression stays deferred until after the R5 and R6 merge.
- Windows only. Append every new Windows-only test class to the `**Spine R8, Windows only:**` line in `docs/tech-debt.md`.

## Docs

- When a gap is closed, remove its line from the tech-debt section `## Spine R8 production wiring (open, ...)`. When all eight are closed, remove the section.
- Make `docs/configuration.md`, `docs/release-notes.md` (add a `Spine R8 fix-back 1.` paragraph), `docs/playwright.md`, and `AGENTS.md` match what is actually implemented. Run `python scripts/sync_consumer_guidance.py` and `python scripts/refresh_agent_index.py`, and regenerate `docs/verb-inventory.md` / help goldens if a verb or help text changes.
- Add a `## Fix-back 1` section to `docs/maintainer/3.0.0-spine/R8-scoreboard.md` with one row per gap: gap, status, commit SHA, targeted test classes with pass and fail counts, and a note that the new test failed before the fix.

## Branch rules

- Work only in `C:\Users\Admin\IdeaProjects\pkb-spine-r8` on branch `3.0.0-spine-r8`.
- Push after each fix group. Before every push run, each on its own: `git fetch origin 3.0.0-spine-r8`, then `git rebase origin/3.0.0-spine-r8`, then `git push origin HEAD:3.0.0-spine-r8`. If the rebase rewrites a recorded SHA, update the scoreboard in a new commit.
- Never push to `3.0.0-spine` or `master`. Do not merge or rebase onto `3.0.0-spine`. No tags, releases, publishing, or force-push. Never run `gradlew --stop`.
- Do no R5 or R6 work. Do not implement `spine session` or R5's default-session rule. Keep the R8 spine verbs in `SpineCli`.
- Never touch the `C:\Users\Admin\IdeaProjects\pkb-spine` or `C:\Users\Admin\IdeaProjects\pkb-spine-r7` folders.
- No secrets in cleartext in code, logs, commits, or `java.args`.
- If a gap genuinely needs R5 or R6 code, close everything else and record that gap on the scoreboard as `waiting on R5/R6 merge`.

## Shared Maven local repository

Before any `publishToMavenLocal` or `maven-consumer-project` `mvnw` run, check whether a java, gradle, or mvn process whose command line contains `pkb-spine\` (not `pkb-spine-r7` or `pkb-spine-r8`) is running `publishToMavenLocal` or `mvnw`. If one is, wait with `Start-Sleep 120` and re-check, up to 60 minutes, before you publish. Run your consumer tag tests right after your own `publishToMavenLocal`.

## Finish

After the last push, confirm the working tree is clean and `origin/3.0.0-spine-r8` equals your HEAD. Then STOP and report the head commit SHA, the per-gap scoreboard rows, and anything recorded as waiting on R5/R6.
