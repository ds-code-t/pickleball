# R3 — Step-definition language adapters

Branch `3.0.0-spine` only. Never `master` or any 2.x branch. No tags, releases, publishing, or force-push.

Requires R1 and R2. Design: `docs/plans/2026-11-25-spine-design.md`, including the Distribution and licensing section. Dan approves that section before this roadmap's Build session starts, because P2 spawns the driver-bundle Node. Scoreboard: `docs/maintainer/3.0.0-spine/R3-scoreboard.md`. Build prompt: `prompts/build-3.0.0-spine-R3.md`.

Every phase is mandatory and must be completed in full. Nothing in R3 is optional or deferred. Stop only on an honest test failure you cannot fix, or on a hard-rule violation.

Targeted tests only. Full regression waits until the end of R8. Windows only. Append new Windows-only class names to `docs/tech-debt.md`. Never weaken, skip, or delete a test. Keep Gherkin. Step annotations stay single string literals. Never run `.\gradlew.bat --stop`.

Fetch and rebase before every push. Push after every green phase. Each git command stands alone. Before every push run `python scripts/sync_consumer_guidance.py` and `python scripts/refresh_agent_index.py`. R3 does not change surface-asset bytes unless a phase edits them; then update `tools.dscode.browser.SurfaceAssetsTest`.

Rewrite docs in place. History in `docs/release-notes.md`. Record the SHA on the scoreboard.

Do not run npm in R3. Do not add `frontend-maven-plugin`. Do not copy the Node binary into the pickleball jar. Do not shade Playwright. `verifyPlaywrightPackaging` stays green.

## CLI-first and logging checklist

- `store glue` already edits `glue/js/*.js` and `glue/js/*.ts`. Extend it. Do not add a GUI editor in this roadmap.
- `engine step` runs a JS or TS definition through the same CLI as a Java step.
- Log with `EngineLog` capability `host`. Off unless named.

## P1 — Java host seam and the static-field warning

### Functionality

The native matcher from R2 invokes a Java method by the `definition_id` row. This phase makes that call go through `tools.dscode.engine.match.ForeignStepHost` so a later host can register without an `if` on language inside `PickleballEngine`.

```
public interface ForeignStepHost {
    String language();
    boolean supports(String language);
    Object invoke(String definitionId, String stepText, List<String> argsJson);
}
```

`tools.dscode.engine.match.JavaStepHost` implements it for `language=java`. It reflects the method named on the `step_definitions` row. It uses the application classloader. It does not use `WarmClassLoader`. That class arrives in R8.

`tools.dscode.store.GlueStaticScan` reads the class file with `java.lang.classfile.ClassFile` (JDK 21). A static field that is not a compile-time constant (`static final` primitive or `String`) produces one warning on hydrate: `warn: static mutable field <Class>.<field> will survive a warm reload; move it to scenario state`. The hydrate still succeeds. `StoreStepCatalog.refresh` calls the scan for every Java definition it upserts. The warning goes to stderr once per class and field per process, and to `pkb_store_log=hydrate` entity `glue`.

ServiceLoader: `JavaStepHost` is registered in `META-INF/services/tools.dscode.engine.match.ForeignStepHost`. `PickleballEngine` loads hosts once per run. A missing host for the row's `host` column fails that step with `error:` and `fix:`.

### Docs

`AGENTS.md` step-definition paragraph: Java definitions execute through `JavaStepHost`; the warning text is exact. Release notes. Do not document the warm loader as if it exists.

### Tests

Create `src/test/java/tools/dscode/engine/match/JavaStepHostTest.java`. A fixture class with one `@Given` literal is invoked through the host and returns the captured group. The engine test does not import `io.cucumber` except inside the fixture class, which is allowed to wear the annotation the way consumer glue does.

Create `src/test/java/tools/dscode/store/GlueStaticScanTest.java`. A class file with `static int n` warns. A class file with `static final String NAME = "a"` does not. Hydrate status is still success.

Re-run `SpineCucumberImportBanTest` from R2. `ForeignStepHost` and `JavaStepHost` must not import Cucumber or Playwright.

```
.\gradlew.bat :test --tests tools.dscode.engine.match.JavaStepHostTest --tests tools.dscode.store.GlueStaticScanTest --tests tools.dscode.engine.SpineCucumberImportBanTest --console=plain
```

## P2 — Node step host

### Functionality

`tools.dscode.launcher.NodeStepHost` implements `ForeignStepHost` for `js` and `ts`. It is not in the `tools.dscode.engine` package, so the engine import ban stays intact. Register it with ServiceLoader from the launcher jar, which is the pickleball jar.

The host locates Node with `PlaywrightNode.locate()`. Empty means `error: bundled Node is not available` and `fix: the playwright driver-bundle dependency must be on the classpath`. It does not download Node and it does not look on `PATH`.

Protocol: the host spawns `node` with the resource script `META-INF/pickleball/node/step-host.mjs` (source file `src/main/resources/META-INF/pickleball/node/step-host.mjs`). One JSON object per line on stdin and stdout.

Java to Node:

- `{"id":1,"op":"load","definitionId":"...","language":"js","source":"<file bytes>","pattern":"..."}`
- `{"id":2,"op":"invoke","definitionId":"...","text":"...","args":["..."]}`

Node to Java, on the same stdout, which the host reads on a thread:

- `{"id":3,"op":"call","fn":"resolveText","args":["..."]}`

Java replies on stdin:

- `{"id":3,"ok":true,"value":"..."}`

Allowed `fn` values, and no others: `resolveText`, `saveValue`, `getValue`, `softFail`, `secret`, `log`, `entityGet`. `secret` and a secret `entityGet` return `~MASK;` or the reference string. They never return cleartext. An unknown `fn` is `{"ok":false,"error":"unknown fn"}`. The parsing map stays inside Java. Node never receives a map object, only the string `resolveText` returns.

The host writes source bytes to `.pickleball/store/sandboxes/<name>/js/<definitionId>.mjs` because Node needs a filename. `PickleballEngine` does not call `Files.write`. A test reads the engine class file and fails if it references `step-host.mjs` or `PlaywrightNode`.

TypeScript: Node 24.22.1 runs erasable TypeScript. The host passes the `.ts` bytes through. `enum`, `namespace`, and parameter properties fail at `load` with `error:` and `fix: keep the TypeScript erasable`. Do not add the `typescript` npm package in this phase. Do not pass `--experimental-strip-types` unless a probe of this binary shows that type stripping is off. The probe runs once, in-process, `node --version` and a one-line `.ts` file, and caches the result on the host. If the probe fails, `load` of a `.ts` file fails with that error. It does not fall back to downloading a compiler.

A bare import (`import x from "left-pad"`) fails `load` with `error: bare import is a rebuild` and `fix: add the package to workbench-ui and rebuild`. Relative imports of other hydrated JS or TS files are loaded from row bytes the same way.

`pkb_engine_log=host` logs spawn, load, invoke, and call names. Not the `secret` return value.

Extend `tools.dscode.store.NoRuntimeDownloadTest` (or the existing class of that name) so a host `load` plus `invoke` does not open a socket to `registry.npmjs.org` or `playwright.azureedge.net`. Use the test's existing network spy if it has one. If it does not, assert the spawned command line contains no `npm` and that `PlaywrightNode.command` is not passed `install`.

### Docs

`docs/playwright.md`: the bundled Node also runs step definitions. It still does not download browsers. `AGENTS.md` step-definition paragraph. `THIRD-PARTY-NOTICES.md` does not need a new Node entry; the driver-bundle paragraph already covers the binary. Say that in the release note so nobody adds a second copy. `docs/configuration.md` does not gain a Node install step.

### Tests

Create `src/test/java/tools/dscode/launcher/NodeStepHostTest.java`. Load a JS source that calls `resolveText` and returns the result. Assert the Java `resolveText` implementation was called and the step return equals its result. A `secret` call returns `~MASK;` when the name is a secret. An unknown `fn` fails the step.

Create `src/test/java/tools/dscode/launcher/NodeStepHostTsTest.java`. An erasable `.ts` step runs. A source containing `enum ` fails at load.

Create `src/test/java/tools/dscode/engine/EngineDoesNotSpawnNodeTest.java`. The `tools.dscode.engine` class files, including `adapter`, do not reference `PlaywrightNode`, `step-host.mjs`, or `com.microsoft.playwright`.

```
.\gradlew.bat :test --tests tools.dscode.launcher.NodeStepHostTest --tests tools.dscode.launcher.NodeStepHostTsTest --tests tools.dscode.engine.EngineDoesNotSpawnNodeTest --tests tools.dscode.store.NoRuntimeDownloadTest --console=plain
```

## P3 — JavaScript and TypeScript step definitions from the project

### Functionality

`StoreStepCatalog` already records `defineStep` calls in `glue/js`. This phase executes them.

A JS or TS file may define a step in either of these forms, both stored as the pattern string:

- `defineStep(/pattern/, function () { ... })`
- `defineStep("pattern", function () { ... })`

The catalog stores `host=js` or `host=ts` from the file extension, `language` the same, and the pattern. A Cucumber-style `Given("pattern", ...)` in JS is accepted as the same pattern, because the plan says the same annotation shape. The function body is the source the host loads. `store glue put` of a `.js` or `.ts` file refreshes the catalog the way a Java put already does.

Re-import: `NodeStepHost.invoke` sends `load` again when the row's `content_hash` changed since the last load in that process. That is the reload rule for this roadmap. The per-context module registry inside one long-lived Node pool is R8, which will call this same `load`. Do not build the pool here. One Node process per `PickleballEngine.run`, stopped in a `finally`.

`engine step --definition=<id> --text=<step text>` runs one definition through `PickleballEngine.runStep` and therefore through the host. Add the flag to `tools.dscode.engine.EngineCli` if `engine step` does not already accept a definition id. Read `EngineCli` and extend the flag it already has rather than adding a second command.

Consumer example: add `maven-consumer-project/src/test/resources/glue/js/sample-step.js` with one `defineStep`, and a scenario in an existing feature that the change checklist already runs for glue (do not add a feature file per form; put the scenario in the feature that already calls a Java step next to it, or in `area-b-control-flow.feature` if that is the smallest existing file that will not force a browser). Prefer a feature that does not launch a browser. Tag it with the feature's existing tag. The scenario's step text matches the JS pattern and asserts the value `defineStep` saved through `saveValue`.

### Docs

`docs/cucumber-compatibility.md` and the glue section of `AGENTS.md`: JS and TS `defineStep` execute. They are no longer source-only. Release notes. `docs/agent/feature-map.md` row for step definitions.

### Tests

Create `tools.dscode.store.JsStepCatalogTest`. A `defineStep(/I count (\d+)/, ...)` row has `host=js` and a pattern that matches `I count 2`.

Create `tools.dscode.launcher.JsStepRunTest`. End-to-end through `PickleballEngine` with Store on. The JS step calls `saveValue` and a following Java assertion reads `getValue`. Use a temp project. No browser.

Extend `StoreGlueCompileTest` only if it claims JS is never executed. Update that assertion to the new contract instead of deleting the test.

Consumer tag run after `publishToMavenLocal`, same rule as R2: the feature's real tag, not `@all`.

```
.\gradlew.bat :test --tests tools.dscode.store.JsStepCatalogTest --tests tools.dscode.launcher.JsStepRunTest --tests tools.dscode.store.StoreGlueCompileTest --console=plain
```

## P4 — Playwright test ingest

### Functionality

Playwright tests are a second ingest shape. They do not become Gherkin text.

`tools.dscode.engine.adapter.playwright.PlaywrightTestIngest` reads `*.spec.ts` and `*.spec.js` that discovery already stored. It recognizes `test.describe("name")`, `test("name")`, `test.beforeEach`, `test.afterEach`, `test.beforeAll`, `test.afterAll`. Each `test()` becomes a `scenarios` row whose `keyword` is `Playwright` and whose steps are the call expressions inside the callback, stored as step text equal to the source line trimmed. The original file bytes stay on `files`. `step_definitions.host` for a `test()` body is `playwright`.

`fixtures` table: `name TEXT, kind TEXT, deps TEXT, source TEXT, file_path TEXT, PRIMARY KEY (file_path, name)`. `kind` is `option` or `auto` or `builtin`. The built-in names `page`, `context`, and `browser` are rows with `kind=builtin` and empty source. They are not instances.

At execution, `tools.dscode.launcher.PlaywrightSpecHost` (a `ForeignStepHost` for `playwright`) creates the browser with the existing `PlaywrightConnection` / `DriverConstruction` path. It does not use the R8 version ladder. It does not download a browser. `PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1` stays. The live page handle is a run attribute `playwright.page=<handle>`. The spine stores the handle string, not a Playwright object.

A spec that needs a browser is skipped by the unit test with a fixture page served from the existing test site only when a Chrome binary is present. The unit test that does not need a browser parses a spec and asserts the rows. The live test is `PlaywrightSpecIngestLiveTest` and it runs on Windows when Chrome is on PATH, using the same lookup `LiveChromeCdpTest` uses. If Chrome is absent the live test fails with a message that names the missing binary. It does not skip. This machine has Chrome. Do not hide a failure.

`pkb_engine_log=ingest` logs `playwright spec <path> tests=<n>`.

### Docs

`docs/playwright.md` gains an ingest section: specs become rows, fixtures definitions hydrate, the page is created at execution, files are not rewritten as Gherkin. `AGENTS.md` adapter paragraph. Release notes.

### Tests

Create `src/test/java/tools/dscode/engine/adapter/playwright/PlaywrightTestIngestTest.java`. A fixture spec with one `test.describe` and two `test` calls produces two scenarios. `page` is a builtin fixture row. The scenario text is not wrapped in `Given` / `When` / `Then`.

Create `src/test/java/tools/dscode/launcher/PlaywrightSpecHostTest.java`. With a stub `ForeignStepHost` seam that does not launch a browser, invoking a spec step records `playwright.page` as a handle string and the engine object graph contains no `com.microsoft.playwright` type. The stub lives in the test. The production host is covered by the live test.

Create `src/test/java/tools/dscode/launcher/PlaywrightSpecIngestLiveTest.java`. One local HTML file, one spec that opens it through the existing CDP attach, one assertion that the step status is passed. Chrome lookup matches `LiveChromeCdpTest`.

```
.\gradlew.bat :test --tests tools.dscode.engine.adapter.playwright.PlaywrightTestIngestTest --tests tools.dscode.launcher.PlaywrightSpecHostTest --tests tools.dscode.launcher.PlaywrightSpecIngestLiveTest --console=plain
```

## Out of R3

Do not build the warm classloader, the resource gate, the version ladder, Monaco, or the Node workbench window. Do not run `npm`.
