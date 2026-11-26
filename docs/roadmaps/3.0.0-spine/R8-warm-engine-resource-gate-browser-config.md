# R8 — Warm engine, resource gate, and browser configuration

Branch `3.0.0-spine` only. Never `master` or any 2.x branch. No tags, releases, publishing, or force-push.

Requires R1, R3, R4, and R7. Design: `docs/plans/2026-11-25-spine-design.md`. Scoreboard: `docs/maintainer/3.0.0-spine/R8-scoreboard.md`. Build prompt: `prompts/build-3.0.0-spine-R8.md`.

Every phase is mandatory and must be completed in full. Nothing in R8 is optional or deferred except the Linux and macOS execution of tests, which stays in `docs/tech-debt.md`. Stop only on an honest test failure you cannot fix, or on a hard-rule violation.

P1, P2, and P3 run targeted tests only. P4 runs those targeted tests and then the full Windows regression described at the bottom of P4. Do not run the full regression before P4 is otherwise green. Do not run Linux or macOS agents.

Never weaken, skip, or delete a test. Keep Gherkin. Step annotations stay single string literals. Never run `.\gradlew.bat --stop`.

Fetch and rebase before every push. Push after every green phase, including after the full regression if you had to fix it. Each git command stands alone. Before every push run `python scripts/sync_consumer_guidance.py` and `python scripts/refresh_agent_index.py`. Update `tools.dscode.browser.SurfaceAssetsTest` if surface-asset bytes change.

`pkb_warm` defaults to `off`. A test that wants the warm JVM sets it. CI and `mvn test` stay cold unless a test sets the flag.

Correctness does not depend on the warm JVM. Every behavior P1 adds has a cold assertion and a warm assertion, except the reload identity assertion which is warm-only and has a cold twin that proves the same scenario passes on the application classloader.

## CLI-first and logging checklist

- Verbs: `spine start`, `spine stop`, `spine sample`, `spine warm`. Browser resolution is not a new verb. It runs inside the existing browser start. `spine sample` reports it.
- `EngineLog` capability `gate`. `BrowserLog` entity `resolve`. Off unless named.

## P1 — Warm classloader and incremental compile

### Functionality

Closes the classloader open item, the Java hot-reload rule, and the dependency-change rule.

`tools.dscode.engine.runtime.WarmClassLoader` extends `URLClassLoader`. Parent is the spine loader. Parent-last only for packages listed in the ingested glue attribute. `pkb_warm_packages` overrides that list. Packages `tools.dscode`, `dev.domengine`, and `io.cucumber` are always parent-first. `loadClass` of a spine class from the child must return the parent's class.

`tools.dscode.engine.runtime.WarmSpine` holds `ConcurrentHashMap<String, WarmClassLoader>` keyed by snapshot id. `ExecutionContext` holds the current loader and clears it in `close`. The loader is not stored in a `ThreadLocal`. Replacing a loader `close()`s the old one and removes the map entry. A `loadClass` on a closed loader throws `IllegalStateException`.

`pkb_warm=off` (default): `JavaStepHost` uses the application loader. `pkb_warm=on`: it uses `WarmClassLoader` for consumer glue packages.

`GlueStaticScan` from R3 already warns. Do not make the warning a failure. The exact text stays `warn: static mutable field <Class>.<field> will survive a warm reload; move it to scenario state`.

Incremental compile: `StoreGlueCompiler` gains a path that uses `javax.tools.JavaCompiler` for files whose source hash changed since the last warm compile. The first compile on `spine start` may shell out to the project wrapper (`mvnw.cmd` or `gradlew.bat`) once. Later single-file changes use `JavaCompiler` and do not spawn Maven or Gradle. A new dependency in the source (an import whose package is not on the current classpath) is a rebuild: stop the warm JVM, run the wrapper compile, start again. Detect that by a compile error whose message contains `cannot find symbol` or `package ... does not exist` after a single-file compile. Do not guess from the import list alone.

CLI:

- `spine start [--project=<dir>]` starts the warm JVM process if it is not up and writes `.pickleball/spine/warm.json` with pid and started-at.
- `spine stop` stops that pid and its child processes.
- `spine warm status` prints `warm=on|off` and the pid.

`spine start` is opt-in. `store run` without a running warm JVM stays cold.

`pkb_engine_log=gate` is not used for compile. Use `pkb_engine_log=host` entity `warm` for start, stop, reload, and rebuild.

### Docs

`AGENTS.md` execution-engine section: warm is a dev optimization, default off, CI is cold. `docs/configuration.md` gains `pkb_warm` and `pkb_warm_packages`. Release notes. Do not tell consumers to run `spine start` in CI.

### Tests

Create `src/test/java/tools/dscode/engine/runtime/WarmClassLoaderTest.java`. Load a fixture class that sets `static int n = 1` in a static initializer. Reload. The new class's `n` is 1 from its own initializer, not a leftover 5 written into the old class. `close()` was called. A further `loadClass` on the old loader throws. A class in `tools.dscode` loaded through the child is the parent class (same `Class` object).

Create `src/test/java/tools/dscode/engine/runtime/WarmColdParityTest.java`. One scenario passes with `pkb_warm=off` and with `pkb_warm=on`.

Create `src/test/java/tools/dscode/store/IncrementalGlueCompileTest.java`. Change one glue file. The second compile command line is `JavaCompiler`, not `mvnw`. Introduce an import of a missing package. The host reports rebuild and the wrapper path is what would be invoked. Do not require a full Maven download in the unit test: assert the decision and the command list, and run the compiler API for real on a fixture that does compile.

```
.\gradlew.bat :test --tests tools.dscode.engine.runtime.WarmClassLoaderTest --tests tools.dscode.engine.runtime.WarmColdParityTest --tests tools.dscode.store.IncrementalGlueCompileTest --console=plain
```

## P2 — Resource gate and the subprocess pool

### Functionality

`tools.dscode.engine.runtime.ResourceGate` reads `MemoryMXBean`, GC bean collection time, and `ThreadMXBean` thread count.

`spine sample --json` returns `heapUsed`, `heapMax`, `nonHeapUsed`, `gcTimeMs`, `threadCount`, `decision`. Decision is `allow`, `subprocess`, or `refuse`.

Properties, all config, none hard-coded in a branch:

- `pkb_heap_subprocess_percent` default 70
- `pkb_heap_refuse_percent` default 85
- `pkb_worker_pool` default 1

Below the subprocess line, new snapshot contexts run in-process. Between the lines, a new context starts a subprocess JVM if the pool has a free slot. At or above the refuse line, if the pool is full, the call fails with `error: heap gate refused a new context` and `fix: spine sample`. The warm spine does not accept the work in-process past the refuse line.

Subprocess JVMs use the project wrapper classpath. They are cold (they do not inherit the warm loader). Pool size is the config value. `WarmSpine` counts live children.

`pkb_engine_log=gate` logs each sample and each decision. Entity is the snapshot id.

Layer 3 is already R7. This phase does not reopen `cross.db`.

### Docs

`docs/configuration.md` the three properties. `AGENTS.md` one paragraph. Release notes.

### Tests

Create `src/test/java/tools/dscode/engine/runtime/ResourceGateTest.java`. Inject a memory reading. 50 percent decides `allow`. 75 percent with a free slot decides `subprocess`. 90 percent with a full pool decides `refuse` and `spine sample`'s decision matches. A test that starts a real subprocess uses a tiny main that prints `up` and exits. Assert the pool returns to zero after `spine stop`.

```
.\gradlew.bat :test --tests tools.dscode.engine.runtime.ResourceGateTest --console=plain
```

## P3 — Browser block, version ladder, Safari leaf, ports

### Functionality

Closes the browser-configuration bullets, the Selenium/Safari bullets, the BiDi open item, and leftover 6.1.5.

Shared YAML, added inside the existing browser config files (the same files `CHROME_HEADLESS.yaml` belongs to). Do not invent a second file format.

```
browser:
  channel: chrome
  version: ""
  source: local
  headless: true
  viewport: { width: 1280, height: 720 }
  userAgent: ""
  arguments: []
  framePolicy: top
  selenium:
    gridUrl: ""
    bidi: off
  playwright:
    connectOverCdp: true
```

`framePolicy` is `top` (default), `iframe`, or `all`. Default `top` does not change current finds. `iframe` is how a consumer expresses leftover 6.1.5. Ingest stores `frame_policy` in `run_variables`.

`tools.dscode.browser.BrowserVersionResolver` resolves `version` against `source`:

- `local`: registered installs only. On Windows, the Chrome and Edge locations `DriverConstruction` already probes. Do not read `%LOCALAPPDATA%\ms-playwright`. A miss fails with `error:` and `fix:` and does not download.
- `cdn`: the Playwright cache, then a download of that browser through Playwright's own downloader. Host is `PLAYWRIGHT_DOWNLOAD_HOST` when `pkb_browser_cdn_host` is set. If the CDN cannot be reached and a cached build exists, use the cache and log `resolve cache-fallback`. If neither exists, fail.
- `any`: local, then the `cdn` rules.
- Blank `source` and a missing `browser:` block: today's no-download path. Do not change `CHROME_HEADLESS.yaml` to `cdn`.
- Precision: `131` is any 131. `131.0` is that minor with the newest patch. `131.0.6778.69` is that exact build. `latest` is the highest the source allows.
- `local` with no version: any local browser of that channel. `local` with a version: that version or a failure.

Adapter choice after resolution: `safari` uses Selenium `safaridriver`. `chrome`, `msedge`, `chromium`, `firefox`, and `webkit` use Playwright. `pkb_protocol=selenium` forces Selenium. A Playwright connect failure fails the run. It does not open Selenium. Update `ProtocolSelector` so the quiet fallback does not run when the resolved adapter was Playwright. Existing configs with no `browser.channel` keep today's `pkb_protocol` behavior. Set `browser.channel: chrome` and `source: local` explicitly in the consumer headless YAML in this phase so the `@all` run at the end of P4 is not an accidental adapter flip. If that YAML is the shared default for every consumer scenario, read it first and make the smallest edit that states the adapter the scenarios already use.

Opt-in verbs, off unless `browser.selenium.bidi: on` or `pkb_selenium_bidi=on`:

- `bidi script` in new class `tools.dscode.browser.SeleniumBidiCommands`
- `bidi intercept` for a Selenium response-body replacement. Playwright sessions do not use it.
- `bidi grid-attach --url=` through `DriverConstruction`

Event subscriptions stay the five already in `SeleniumBidiEvents` (`onConsoleEntry`, `onJavaScriptException`, `onBeforeRequestSent`, `onResponseCompleted`, `onFetchError`), still gated by `pkb_scenario_events=on`. Do not add events.

One owner per debug port. A second attach to the same port fails. The owner is recorded in the session row the launcher already keeps. The workbench port list is `spine sample` plus `browser ports` if a ports verb does not exist. Add `browser ports` only if no existing verb lists them. Search `WorkbenchAgentCommands` first.

Recording stays `pw codegen`. Do not add a Selenium recorder. `AgentProtocolController` stays the escape hatch and is not a catalog verb for raw methods.

`BrowserLog` entity `resolve` logs the requested version, the source, and the resolved version. Not a download URL that contains a token.

Windows cannot launch Safari. `BrowserVersionResolver` unit test asserts channel `safari` selects Selenium without starting a process. The macOS live run is appended to `docs/tech-debt.md`. The code is not omitted.

### Docs

`docs/configuration.md` browser block and the source enum. `docs/playwright.md`: default remains no download; `cdn` and `any` may download; no silent Selenium fallback. `AGENTS.md` protocol paragraphs that describe the quiet fallback, rewritten. Tech-debt row 6.1.5 removed. Release notes name the adapter default and the YAML keys. `docs/verb-inventory.md` regenerated if verbs were added.

### Tests

Create `src/test/java/tools/dscode/browser/BrowserVersionResolverTest.java`. Local miss does not call a downloader (inject a downloader spy that fails the test if called). CDN miss calls it once. CDN failure with a cached version returns the cache. `131` versus `131.0.1` precision. `latest` with local returns the highest fake install. Safari selects Selenium. Playwright failure does not call the Selenium factory.

Create `src/test/java/tools/dscode/browser/SeleniumBidiCommandTest.java`. With `pkb_selenium_bidi` unset, `bidi script` exits 2. With it on, a stub driver records one script call. The event list asserted by reflection or by a package-visible method is exactly the five names above.

Create `src/test/java/tools/dscode/browser/DebugPortOwnerTest.java`. Two attaches to port 9222: the second fails. A different port succeeds.

```
.\gradlew.bat :test --tests tools.dscode.browser.BrowserVersionResolverTest --tests tools.dscode.browser.SeleniumBidiCommandTest --tests tools.dscode.browser.DebugPortOwnerTest --console=plain
```

## P4 — Warm CDP, UIA cache, and the full regression

### Functionality

Closes leftovers 6.3.6 and 6.7.3.

**Warm Playwright/CDP.** When `pkb_warm=on` and `ResourceGate` returns `allow`, `spine start` opens one CDP connection to a browser the project config already names, using the existing `PlaywrightConnection` path, and holds it. `pkb_warm=off` does not open it. A `refuse` decision does not open it. The connection is closed on `spine stop`. This is the warm start leftover 6.3.6. It does not run when the gate refuses.

**UIA cache.** `dev.domengine.desktop.DesktopTree` keeps one cached tree per attached window id when `pkb_warm=on`. Live Windows attach subscribes to the UI Automation structure-changed event for that window. The handler calls `DesktopTree.structureChanged`, which drops that window's entry. Close unsubscribes. A listener that does not start does not fail attach. Other platforms do not subscribe. `pkb_warm=off` does not cache. The cache byte size is checked by `ResourceGate` before insert. A tree that would cross the refuse line is not cached, and the call reads through. `pkb_engine_log=gate` entity `desktop` logs `cache hit`, `cache miss`, or `cache refuse`.

Remove tech-debt rows 6.3.6 and 6.7.3.

### Targeted tests

Create `src/test/java/tools/dscode/launcher/WarmCdpTest.java`. `pkb_warm=off` does not call connect. `pkb_warm=on` with an injected `allow` calls connect once. A second start reuses it. `spine stop` closes it. An injected `refuse` does not connect.

Create `dom-engine/src/test/java/dev/domengine/desktop/DesktopTreeCacheTest.java`. Two reads with warm on and no structure event hit the cache once. A structure event misses. Warm off misses both. A tree whose declared size exceeds the refuse line is not stored.

```
.\gradlew.bat :test --tests tools.dscode.launcher.WarmCdpTest --console=plain
.\gradlew.bat :dom-engine:test --tests dev.domengine.desktop.DesktopTreeCacheTest --console=plain
```

### Full regression

Run this only after the targeted tests above pass. Windows only. Record pass and fail counts on the R8 scoreboard. A failure stops the roadmap until it is fixed honestly. Do not delete the failing test.

```
.\gradlew.bat :test --console=plain
.\gradlew.bat :dom-engine:test --console=plain
.\gradlew.bat :pickleball-workbench:test --console=plain
.\gradlew.bat :pickleball-control-protocol:test --console=plain
.\gradlew.bat :pickleball-control-api:test --console=plain
.\gradlew.bat publishToMavenLocal
.\maven-consumer-project\mvnw.cmd -f maven-consumer-project\pom.xml -U test -Dpkb_runvars.pkb_browser=CHROME_HEADLESS -Dpkb_runvars.pkb_tags=@all
```

`@all` is allowed here because this is the end of the last roadmap, not a workbench migration check. Phases before this one do not run `@all`.

Also run:

```
python scripts/verify_agent_contract.py
python scripts/refresh_agent_index.py --check
python scripts/sync_consumer_guidance.py --check
```

Append every new Windows-only live class from R1 through R8 that did not run on Linux or macOS to the existing section in `docs/tech-debt.md`. Do not remove the classes that were already listed.

### Docs

`AGENTS.md` matches the shipped warm, gate, and browser behavior. `docs/tech-debt.md` has no leftover from the README mapping except the Linux and macOS section. `docs/release-notes.md` has one paragraph per roadmap that landed, written as current behavior plus a short history line. Feature map rows that name relations, store, engine, or the workbench match the code.

### Done when

The scoreboard rows P1 through P4 are `done`, each with a SHA and a test count. P4's notes include the full-regression counts. The branch is pushed. Linux and macOS were not run.
