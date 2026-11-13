FRESH SESSION — standalone. You are Grok Heavy. GitHub connector is already attached to github.com/ds-code-t/pickleball. Do not create a new repo. Do not implement product or test code in this session. Design, re-verify, write a phased roadmap plus a standalone Build prompt, and push only those documentation artifacts to branch `3.0.0`.

This prompt is self-contained. An earlier read-only code audit against `origin/3.0.0` at `1059667f` (2026-11-13) is summarized below with file:line evidence so you do not need box access. That SHA was the architecture snapshot used for the file:line evidence below. While this prompt was being written, `origin/3.0.0` also gained `44d4c7b9` (`Add 3.0.0 doc-fix Build prompt`, docs-only, `prompts/build-3.0.0-doc-fixes.md`). A separate documentation-fix Build session may still push further doc commits and a lookup-order unification on top of that. Treat the audit as a starting hypothesis, not as current truth.

================================================================================
START PROCEDURE
================================================================================

1. `git fetch origin 3.0.0` and check out the CURRENT tip of `origin/3.0.0`. Record the actual SHA in every doc you write.
2. Re-read the branch docs and source on THAT tip (not this prompt's memory of `1059667f`). When this prompt and the tip disagree, the tip wins; say so in the roadmap.
3. Re-verify every defect and degraded item below against that tip before you lock a design. Drop or shrink anything already fixed. Keep anything still broken. Add anything new that the same investigation surfaces.
4. Then write the design, the phased roadmap, and the Build prompt, and push those files to `3.0.0` as specified under Deliverables.

================================================================================
HARD GUARDRAILS (put these in the roadmap and in the Build prompt)
================================================================================

- Work only on branch `3.0.0`. NEVER commit, push, or merge to `master`, `2.2.0`, or `2.1.19`.
- No force-push, no tags, no releases, no publishing. Version stays 3.0.0.
- Do not change existing Gherkin syntax. Do not weaken, skip, or delete tests. Fake-binary / Rust-argv contract tests stay; they must not become the only coverage.
- Cucumber step annotations must stay single string literals with identical values.
- The Java DOM engine interface stays general-purpose: no Pickleball-specific types, config, or behavior inside `dom-engine`. Pickleball integration stays in the adapter (`DomCompositeAdapter`, `CompositeRuntime`, `ElementOperations`, locators).
- Open engine PRs #2 and #3 on `ds-code-t/dom-composite` must not be merged and must not be touched.
- `AGENTS.md` is append-only: never rewrite, truncate, or delete existing text. Append a short pointer section for this work if the repo convention requires indexing it.
- Regenerate `docs/agent/repository-index.md` only with `python scripts/refresh_agent_index.py`. Never hand-edit or truncate that file.
- Before every push, run:
  - `python scripts/refresh_agent_index.py` (if indexed files changed; then `--check`)
  - `python scripts/verify_agent_contract.py`
  - `python scripts/sync_consumer_guidance.py --check`
- Push every completed change to `origin/3.0.0` (docs in this Heavy session; each implementation change later in Build). Pull the tip before each push.
- Do not invent a second implementation of something that already works on the tip.
- Do not switch Dalton from opt-in to default as part of this work. Do not retire the Cucumber adapter or Cucumber-core aspects.

================================================================================
CURRENT ARCHITECTURE (from origin/3.0.0 docs and source at 1059667f — re-verify)
================================================================================

Read these first, in order, on the tip you actually checked out:

1. `AGENTS.md`
2. `docs/maintainer/3.0.0-java-dom-design.md`
3. `docs/roadmaps/3.0.0-java-dom-roadmap.md`
4. `docs/maintainer/3.0.0-engine-design.md`
5. `docs/roadmaps/3.0.0-decouple-round3-roadmap.md`
6. `docs/agent/feature-map.md`
7. `docs/agent/change-checklist.md`
8. `.github/workflows/agent-contract.yml`
9. The source files named in the defect list

State the architecture from those documents and from current source. Do not use remembered or older wording when the tip has moved. In particular, some 3.0.0 docs still contradict later appends; later `AGENTS.md` appends and current source win over earlier design-doc phase text.

What the branch said at `1059667f` (for orientation only):

- **Java DOM engine.** Gradle module `dom-engine`, package `dev.domengine`, CLI main `dev.domengine.cli.DomEngineMain`, command `domengine`. It is general-purpose: no dependency on Pickleball, Dalton, Cucumber, or Selenium. It replaced the Rust `dom-composite` / `composite` binary as the default engine. It talks to Chrome over CDP (WebSocket). The command surface matches `composite serve --stdio`. Capabilities the design requires it to retain: query (css, xpath, text, role, frame, shadow, near, geometry), observe (including a per-session CDP event buffer), actions (click, type, scroll, submit, key, hover, eval, screenshot, …), desktop (UIA / AX / AT-SPI + fixture), CLI, MCP server, conformance suite. Workbench, MCP, and Gradle only forward to the CLI.

- **How Pickleball locates it.** `CompositeBinaryLocator` / `JavaEngineLocator` in the root module, and `DirectControlProvider` / `JavaEngineLauncher` in `pickleball-control-protocol`. Intended default is the bundled Java engine. A Rust binary is only an explicit opt-in (`pkb_composite_bin` pointing at a Rust binary, or `pkb_composite_engine=rust` / `PKB_COMPOSITE_ENGINE=rust`). `pkb_composite=off` uses the Selenium element path. The locked design's intended explicit-path order is `pkb_composite_bin`, then `COMPOSITE_BIN`, then the bundled Java engine. At `1059667f` the two locators still disagreed on the first two keys (see G2).

- **Default element path.** With the default `pkb_composite=auto`, element find / click / type / hover / wait / inspect go through `ElementOperations` → `DomCompositeAdapter` → the Java engine. WebDriver remains for browser lifecycle (navigation, window handles, CDP). `AGENTS.md` public-contracts paragraph still said (at `1059667f`) that this path runs "when a dom-composite 2.4.0 binary is present"; that sentence is stale contract text, not the live default. A parallel doc-fix session may already have corrected it. The Java engine is always shipped inside the pickleball jar and, in the packaged Workbench jar, via the `workbenchEmbed` configuration.

- **Native step engine vs Cucumber adapter.** Pickleball-owned native execution lives in `tools.dscode.engine`. `EngineConfig` default is `native` (`pkb_engine`, else `PKB_ENGINE`, else `engine.properties` `mode=`). `pkb_engine=cucumber` keeps the Cucumber adapter, which stays supported. G9 on this branch closed dual-run parity for repo features and every `maven-consumer-project` feature, including dynamic phrases. The earlier `3.0.0-engine-design.md` Phase 12 text still says the default stays `cucumber`; that is outdated relative to the later `AGENTS.md` G9 append and to `EngineConfig.java`. Trust the later append and current source. The native engine still discovers glue from the same `@Given` / `@When` / `@Then` annotations (values remain single string literals) and matches with Cucumber's `ExpressionFactory`. Do not retire Cucumber-core aspects.

- **Dalton is opt-in.** With no `pkb_dalton` setting and no Dalton config file, consumer projects stay on the file-only path. Auto-hydrate and entity references run only when `pkb_dalton=true` or `dalton.properties` sets `enabled=true`. Git files are the source of truth; the working copy is `.pickleball/dalton/dalton.db` (gitignored). This work must not change that default. Do not describe Dalton as "always on" or "the default archive."

- **Workbench isolation.** Workbench is the control plane, not a second Pickleball runtime. The only shared Java boundary with the worker is `pickleball-control-protocol` (JDK-only). Workbench must not compile against, resolve, shade, load, or execute Pickleball core, `pickleball-control-api`, Cucumber, Selenium, or REST-assured. The mnemonic is: Pickleball may contain Workbench; Workbench must not contain Pickleball. `./gradlew verifyStrictControllerIsolation` enforces this. `dom-engine` is allowed on a Workbench classpath only because it is general-purpose and has none of those dependencies. At `1059667f`, `pickleball-workbench/build.gradle` embeds `dom-engine` non-transitively in `workbenchEmbed` (packaged jar only) and does not declare it as a compile or runtime project dependency, so `:pickleball-workbench:run` / `installDist` / tests can start without an engine (see G3).

- **CI already has a live Chrome job.** `.github/workflows/agent-contract.yml` job `dom-engine-live`, name `Java DOM live Chrome`, runs on pull_request and on push to `master`, `2.2.0`, and `3.0.0`. It installs Chrome for Testing, sets `DOMENGINE_CHROME`, and runs `:dom-engine:test`, then `:pickleball-control-protocol:test --tests tools.dscode.control.protocol.DirectControlProviderTest`, then the unjoined-driver repro tests. At `1059667f` the workflow `gate` job required only `windows`, not `dom-engine-live`. Account for that when you wire new tests so live failures are actually visible on every push.

- **Consumer project.** `maven-consumer-project` is both the example and the executable compatibility suite. `@LifecycleHook(BEFORE_CUCUMBER_RUN)` in `maven-consumer-project/src/test/java/com/example/pickleball/PickleballTests.java` (lines 39–43 at `1059667f`) registers the project element vocabulary (including `Product Card`, whose XPath uses `concat(' ', normalize-space(@class), ' ')`) and starts `LocalTestSite` on port 8765. `@LifecycleHook(AFTER_CUCUMBER_RUN)` stops the site. The `@dom-composite` features (6 scenarios) exist but, at the audit, ran only inside the held `@all` set.

- **Other 3.0.0 conventions you must follow.** Java 21. CLI-first: every new engine capability is reachable from the `domengine` CLI; Workbench, MCP, and Gradle only forward. Logging is toggleable, per-entity, date-stamped, and size-capped. Prefer updating the existing canonical guide over adding a competing one. Temporary agent files go in `.agent-work/`, never in `scripts/` or docs. Definition of done is in `AGENTS.md`: behavior, compatibility, tests, docs, feature-map, generated index.

================================================================================
WHY THIS WORK EXISTS
================================================================================

Stale architecture docs (Rust binary as the normal engine, Selenium as the primary element path, Cucumber as the only suite lifecycle) meant the default Java-engine path was never driven end to end. Composite tests stub a fake Rust binary and assert argv. The consumer `@dom-composite` features are not in CI except via held `@all`. The default configuration on 3.0.0 therefore shipped with a broken element path and a native engine that does not fire suite-level hooks.

On `1059667f`, with real Chrome, default settings failed browser scenarios. The only consumer `@dom-composite` configuration that passed all 6 scenarios was the Cucumber adapter with `pkb_composite=off` (Selenium).

================================================================================
YOUR TASK
================================================================================

Re-verify, root-cause, and design fixes. Then produce a mandatory phased roadmap (functionality, docs, and tests per phase, plus CLI-first and logging checklist) and a fully standalone Build prompt. You do not implement the engine or test-code changes yourself.

Required fix areas, once re-verified on the current tip:

1. **Page/tab attach (D1).** The engine must attach to the page/tab target, not the browser-level CDP target, so `DOM.getDocument` works. The fix must cover the Cucumber adapter and the native engine, attach and join paths, Workbench, and direct control — not only `CompositeRuntime.attach`.
2. **Suite-level hooks (D11).** The native engine must fire suite-level `BEFORE_CUCUMBER_RUN` / `AFTER_CUCUMBER_RUN` (and the equivalent `@BeforeAll` / `@AfterAll` work) with Cucumber-adapter parity: consumer site startup, element-name registration, `Log.global().closeAll()`, `ReportPortalBridge.finishLaunch`, `pickleballLog.stop()`, `AgentCoordination.finishCurrent`.
3. **Full XPath 1.0 (D2).** Implement full XPath 1.0 evaluation in the Java engine. Prefer delegating to the browser's `document.evaluate` via CDP, then mapping results to backend node ids (`DOM.requestNode` / `describeNode`). Do not ship another hand-rolled subset. Required expressions include `or`, `and`, `normalize-space`, positional predicates, `(//x)[n]`, and descendant paths such as `//li/a`. Never return 0 matches for an unsupported expression; raise an error.
4. **Honest capabilities, and implement the ones Pickleball relies on (D3–D9).** The engine must reject or explicitly report unsupported options and capabilities. Silent accept-and-ignore is a defect. Then implement the flags and behaviors Pickleball actually uses: real wait-until-visible / present with polling and stability, hover, displayed filter, frame and shadow scoping, all-frames search, stable element identity for duplicate elements (`--node-id` / snapshots), and related snapshot eval. Adapter fallbacks are a safety net, not a substitute for implementing those relied-on flags.
5. **Displayed / selected state (D10).** Report real `hidden` / `checked` / `selected` (and any other states the previous row format carried) so `isDisplayed()` / `isSelected()` are correct.
6. **Driver creation outside a scenario (D12).** `DriverConstruction.applyDebugPort` must not NPE when there is no current scenario state. Null-guard and fall back to global run vars. Confirm reach into agent and Workbench driver creation.

Also design fixes for the degraded items, once re-verified:

- **G1.** In `auto`, a fallback to Selenium must be loud (logged, and configurable). It must not be silent. The only silent/explicit Selenium path is `pkb_composite=off`. With the Java engine always bundled, a fallback is a bug the operator should see.
- **G2.** Unify engine lookup order across the runtime locator and the control-protocol / Workbench locator (one shared resolver, or identical documented order). The design's intended order is `pkb_composite_bin`, then `COMPOSITE_BIN`, then the bundled Java engine, unless rust is explicitly requested. A parallel doc-fix session may already have done this; re-verify and only keep remaining work.
- **G3.** Make the Workbench Gradle run / test / `installDist` classpath include the Java engine, without breaking Workbench dependency-boundary / controller-isolation checks. Do not add a Pickleball-core, control-api, Cucumber, or Selenium dependency to Workbench. Run `./gradlew verifyStrictControllerIsolation` as a required check for that phase.
- **G4.** Add `eval_nodes` and `limit` support in the engine, or make the adapter fallback actually slice / batch correctly.
- **G5.** A failed capability probe must not cache a downgrade to hard-coded Rust 2.4.0 verb/flag assumptions.
- **G6 / tests.** See the Tests section. This is why D1–D11 shipped unnoticed.

Also investigate the audit's uncertain items on the current tip and, if confirmed, include them in the roadmap: the iframe miss cause (timing vs frame-descent); blast radius of D2–D10 after D1 is fixed (not just the probe primitives — exercise generated phrase XPaths and consumer `@dom-composite`); whether agent or Workbench driver creation hits the D12 NPE; the native-run NPE in `MappingProcessor.boundRunningStep()` for `$nope` (possible leftover Cucumber-coupling). Optionally design a one-line INFO log when an explicit Rust override (`COMPOSITE_BIN` / `pkb_composite_engine=rust`) is selected, so a stale shell override cannot go unnoticed.

Stale user-facing strings and Javadoc that still tell operators to install the Rust binary should be included only if they remain on the tip; the parallel doc-fix session may already have reworded them. Do not redo landed text-only fixes.

================================================================================
AUDIT FINDINGS TO RE-VERIFY (evidence from 1059667f — concise but complete)
================================================================================

Method at audit time: code reading; driving the Java engine with real Chrome against a probe page (`serve --stdio`); throwaway JUnit probes through the real adapter (`CompositeRuntime.attach` on a real ChromeDriver, the same call `DriverConstruction.ready()` makes); consumer `@dom-composite` (6 scenarios) in five configurations. Counts then: 12 defects, 6 degraded, 5 stale-text groups, 9 intentional.

Consumer `@dom-composite` results at `1059667f`:

| Configuration | Result |
|---|---|
| `pkb_engine=cucumber`, `pkb_composite=off` (Selenium) | **6/6 pass** (`Tests run: 6, Failures: 0, Errors: 0` in the Cucumber engine; `BUILD SUCCESS`) |
| `pkb_engine=cucumber`, default composite (Java engine) | 6/6 error: `dev.domcomposite.FailedException: DOM.getDocument {"code":-32601,"message":"'DOM.getDocument' wasn't found"}` (`Tests run: 23, Failures: 0, Errors: 6`; `BUILD FAILURE`) |
| default native engine, either composite mode, no external site | all browser scenarios error `unknown error: net::ERR_CONNECTION_REFUSED` (suite hook never started `LocalTestSite`; e.g. `Tests run: 25, Failures: 0, Errors: 8`) |
| default native engine, `pkb_composite=off`, site served by hand | 5/6; the "Starter Plan" Product Card displayed assertion evaluated to false because the vocabulary hook never ran (`native-off-site.log`; `Tests run: 7, … Errors: 2` in the engine row). Cucumber with the hook registered passed the same phrase. |
| default native engine, default composite, site served by hand | 6/6 error: same `DOM.getDocument … wasn't found` (`native-auto-site.log`; `Tests run: 25, Failures: 0, Errors: 8`) |

The native vs Cucumber test-count difference (7/8 vs 6 for the same tag; 25 vs 23 in the Maven totals) was not investigated; it is probably container/aggregate rows. Confirm if it matters.

Probe page used for engine/adapter primitives: a hidden `#hid` button (`display:none`, class `b`), a visible `#vis` button, a `#hov` div whose `mouseover` sets text to `hovered` (starts as `nohover`), a `#late` div shown after 2500 ms, an iframe (`inner.html` with `#inner` button), an open shadow root with `#sh`, two `.dup` paragraphs, a `.s` "Save" span inside `.w`, and two identical `.del` "Delete" links.

Live adapter probe (`CompositeRuntime.attach` on real ChromeDriver) printed, among other lines:

```
PROBE cdpAddress=ws://localhost:34191/devtools/browser/444c21e0-98c3-4554-b1d7-5bc0a062163b
PROBE joined=true focused=http://localhost:34191
PROBE browser-level join find #vis threw FailedException: DOM.getDocument {"code":-32601,"message":"'DOM.getDocument' wasn't found"}
PROBE focusPage(target)=true focused=ws://localhost:34191/devtools/page/8E7F5BB9969A1B27DE9E5032860707FE
PROBE xpath //button[@id='vis'] selenium=1 engine=1
PROBE xpath //*[self::button or @role='button'] selenium=2 engine=ERR InvalidException: invalid engine: @role
PROBE xpath //button[normalize-space(.)='Visible'] selenium=1 engine=0
PROBE xpath //*[self::button or @role='button'] … already above
PROBE xpath //*[@class='b' and @id='vis'] selenium=1 engine=ERR InvalidException: invalid engine: @id
PROBE xpath (//button)[2] selenium=1 engine=0
PROBE xpath //li/a selenium=2 engine=0
PROBE displayedOnly+outermost .b -> [hid(shown), vis(shown)]
PROBE waitVisible returned after 6 ms; selenium says displayed=false
PROBE hover -> textContent=nohover
PROBE click .s threw NotFoundException: not found
PROBE dels=2
PROBE live(second)=false
PROBE eval(second) threw NotFoundException: not found
PROBE click second .del threw NotFoundException: not found
PROBE iframe #inner selenium(after switch)=1
PROBE iframe #inner engine(after enterFrame)=0
PROBE multiEval=false limitFlag=false
```

After an explicit `focusPage(target)` to the `/devtools/page/…` socket, simple `//button[@id='vis']` worked (1=1). The browser-level join never could.

### D1. Engine joins the browser-level CDP target, so every DOM call fails (critical)

**Where (1059667f):**
- `src/main/java/tools/dscode/common/composite/CompositeRuntime.java:262-296` (`attach`) and `:412` (`cdpAddress`, which prefers `se:cdp`)
- `dom-engine/src/main/java/dev/domengine/session/BrowserSession.java:306` (`discover` uses a `ws://` URL as-is)

**What:** `attach` passes ChromeDriver's `se:cdp` value, `ws://host:port/devtools/browser/<id>`, to `join`. The engine opens that socket as if it were a page. Every `DOM.getDocument` returns `-32601 'DOM.getDocument' wasn't found`. Only the agent window-focus code (`PageSurfaces.focus` → `focusPage(target)`) ever pins a page target. Rust `connect_page` had the same as-is behavior; it was hidden because Selenium usually took over. The Java engine is now always shipped, so this is the default path.

**Evidence:** probe and consumer table above. Cucumber + default composite failed 6/6 with that error; `off` passed 6/6.

**Fix direction:** At `attach` / `join`, resolve the current tab's page target (for example `/json/list` matched to the window handle, or `Target.getTargets`) and join or focus that target. Alternatively, `BrowserSession.discover` must treat a `/devtools/browser/` URL as a browser endpoint and select or attach a page target. Must work for Cucumber and native, attach and join, Workbench, and direct control. Add a live test: real ChromeDriver, `DriverConstruction.ready()`, then a composite find and click through the real adapter.

### D2. Java engine XPath is a small subset; Pickleball generates full XPath 1.0 (high)

**Where:** `dom-engine/.../query/Engines.java:556-613` (`xpathMatches`, `xpathPredicate`). Callers: every adapter query that sends `--xpath`, and `CompositeWebElement.actionBody` `localXPath` (`CompositeWebElement.java:193-223`).

**What:** supports `//tag`, `//tag[@a='v']`, `contains(...)`, and simple steps. Anything else throws `InvalidException` ("invalid engine: @role") or silently matches 0. The adapter and selector translation were written against the Rust CLI contract (sxd_xpath, full XPath 1.0). Engine capabilities do not advertise the limits.

**Evidence (live engine vs Selenium, after a page-target focus):**

| Expression | Selenium | Engine |
|---|---|---|
| `//*[self::button or @role='button']` | 2 | `InvalidException: invalid engine: @role` |
| `//*[@class='b' and @id='vis']` | 1 | `InvalidException: invalid engine: @id` |
| `//button[normalize-space(.)='Visible']` (and `//*[normalize-space(.)='Visible']`) | 1 | 0 |
| `(//button)[2]` | 1 | 0 |
| `//li/a` | 2 | 0 |

Consumer logs and the consumer dictionary use `translate()`, `ancestor-or-self::`, `self::`, `or`, `normalize-space`, and `concat(' ', normalize-space(@class), ' ')`. Clicking a text-only element through `actionBody` threw `NotFoundException` (`click .s`).

**Fix direction (locked preference):** evaluate XPath in the page (`Runtime.evaluate` with `document.evaluate`, then `DOM.requestNode` / `describeNode` to backend node ids). A complete in-process XPath 1.0 engine over a dumped tree is a fallback only if CDP `document.evaluate` is proven insufficient; do not hand-roll a subset. Never return 0 for an unsupported expression; raise an error. This surfaces as soon as D1 is fixed.

### D3. Engine silently accepts and ignores flags the adapter depends on (enabler for D4–D9)

**Where:** `dom-engine/.../cli/DomEngineMain.java:540` (`flags()` stores any `--name value`); `Query.run` ignores unknown flags.

**What:** the adapter was coded to the Rust 2.4.0 CLI contract. Its fallback detection (`unexpected` / `unrecognized` argument) never fires, because the Java parser never rejects a flag.

**Fix direction:** reject unknown flags, or advertise per-flag capabilities that the adapter gates on — and implement the relied-on flags in D4–D9. Silent ignore is not acceptable.

### D4. Waits return immediately (`--wait-for` / `--timeout-ms` / `--poll-ms` ignored)

**Where:** `DomCompositeAdapter.java:556` (`waitOn`); `LeanWaits.java:154` (visible) and `:178` (attached), via `compositeWait` at `:199-204`.

**Evidence:** `waitForElementVisible` on a hidden element returned after 6 ms with the element still hidden. A missing node returned ok and empty instead of timing out. Rust implemented `wait-for`.

**Fix direction:** implement wait/poll (and stability) in the engine. Adapter fallback to the Selenium / LeanWaits polling loop is allowed when the engine honestly lacks a `wait` capability, but the relied-on behavior must work on the default path.

### D5. Hover is a no-op (`--force-hover` ignored)

**Where:** `DomCompositeAdapter.java:423` (`hover`), reached from `ElementOperations.hover` (`:178-185`).

**Evidence:** on `#hov`, the `mouseover` handler never fired (text stayed `nohover`). Selenium hover worked.

**Fix direction:** implement hover in the engine (`Input.dispatchMouseEvent mouseMoved` plus any force-hover pseudo-state the contract requires), advertised in `actions`.

### D6. `--filter displayed` ignored, so hidden elements match

**Where:** `NestedByLocator.java:241-256` (findCollected → `engineNest`, filter `displayed`, `:331-350`); `DomCompositeAdapter.java:129-189` (`countQuery` / `findScoped`).

**Evidence:** `displayedOnly+outermost .b -> [hid(shown), vis(shown)]`. A `display:none` element was returned and reported shown.

**Fix direction:** implement the `filter` flag (visibility via box model or computed style). Adapter post-filter is a safety net, not the default design.

### D7. Frame and shadow scoping ignored (`--frame` / `--shadow`)

**Where:** `CompositeWebElement.java:366-369` (`enterFrame`) and `:318-325` (`getShadowRoot` pushes a shadow scope that is never popped); `DomCompositeAdapter.find` `:353-376`.

**Evidence:** an element inside an http iframe was found by Selenium (1) and by the engine (0), both via the adapter stack and via the engine's own `frame=` pipeline. A CSS query with `--pierce open` also returned 0, while an XPath pierce form worked in at least one probe. Audit did not settle timing vs frame-descent.

**Fix direction:** implement frame descent (`DOM.getDocument` with `pierce` / `contentDocument`, or a per-frame session) and shadow scoping in the engine. Pop the shadow scope in `CompositeWebElement`. Investigate and state the iframe-miss cause.

### D8. `--node-id` ignored, so eval/live re-resolve by selector

**Where:** `DomCompositeAdapter.java:538` (`eval`) and `:586` (`live`); `ElementSnapshotScript.onEval` `:294-305` / `onEvalAll` `:311-330`; `CompositeSnapshotProducer.java:34`. `PageActions.one` (`:83-95`) then raises ambiguous or not found.

**Evidence:** with two identical `.del` links, `live(second)` returned `false` (a live element was reported stale), and eval/click on the second link failed. Snapshots therefore break on non-unique elements. `dels=2` then `live(second)=false`.

**Fix direction:** honor `node-id` / `node-ids` (`DOM.resolveNode` by backend id) so identity is stable across duplicates.

### D9. `--all-frames` ignored: `queryAllContexts` returns main-frame results only

**Where:** `DomCompositeAdapter.java:383` (`queryAllContexts`). Catch-based fallback never fires (D3).

**Evidence:** only main-frame buttons were returned (iframe `#inner` missing).

**Fix direction:** implement all-frames / all-contexts search in the engine, or honestly lack the capability so the per-frame adapter loop runs.

### D10. `isDisplayed()` always true, `isSelected()` always false on composite elements

**Where:** `CompositeWebElement.java:75-77` reads `hidden` / `checked` / `selected` from `states`; `dom-engine/.../query/Query.java:323` (`compositeJson`) emits only `disabled`.

**Evidence:** probe printed `hid(shown)` for a `display:none` element. `isSelected` was wrong by code reading; it was not run live.

**Fix direction:** emit `hidden`, `checked`, `selected`, and any other states the previous row format carried, with a contract test that compares row fields.

### D11. Native (default) engine never fires `BEFORE_CUCUMBER_RUN` / `AFTER_CUCUMBER_RUN` (high)

**Where:**
- `src/main/java/tools/dscode/coredefinitions/GeneralSteps.java:163-221`: the only place these phases fire is Cucumber `@BeforeAll` / `@AfterAll` (`lifecycle.fire(BEFORE_CUCUMBER_RUN)` at `:163-165`; AfterAll body at `:168-221` fires `AFTER_CUCUMBER_RUN`, then `Log.global().closeAll()`, `ReportPortalBridge.finishLaunch`, `pickleballLog.stop()`, `AgentCoordination.finishCurrent`).
- `src/main/java/tools/dscode/engine/match/StepRegistry.java` imports only `Before` / `After` / `BeforeStep` / `AfterStep` (no `BeforeAll` / `AfterAll`).
- `src/main/java/tools/dscode/engine/adapter/NativeSuiteExecution` neither fires the phases nor runs `@BeforeAll` / `@AfterAll`.

**Skipped on the default (native) engine:** every consumer `@LifecycleHook(BEFORE_CUCUMBER_RUN)` / `(AFTER_CUCUMBER_RUN)`; Pickleball's own AfterAll work listed above.

**Evidence:** `PickleballTests.java:39-43` registers vocabulary and starts `LocalTestSite`. Native runs without a hand-started site: every scenario hit `ERR_CONNECTION_REFUSED`. With the site served by hand: 5/6, Product Card assertion failed because the vocabulary was never registered. Cucumber engine: 6/6.

**Fix direction:** in `NativeSuiteExecution.execute` (or equivalent), fire `BEFORE_CUCUMBER_RUN` before the first scenario and `AFTER_CUCUMBER_RUN` plus the `GeneralSteps` AfterAll body in a `finally`. Alternatively, register `@BeforeAll` / `@AfterAll` in `StepRegistry` and keep cucumber-adapter parity. Add a native-engine test that asserts both phases fire (site start / vocabulary registration / log and ReportPortal / agent coordination close).

### D12. `DriverConstruction.applyDebugPort` NPEs without scenario state (low; scope unverified)

**Where:** `DriverConstruction.java:720-721` (audit said `:719-721`) dereferences `getCurrentScenarioState()` (`if (!getCurrentScenarioState().debugBrowser)`).

**Evidence:** a standalone `DriverConstruction` probe hit this NPE. Reach into `AgentControl.createLocalDriver` / Workbench was not confirmed.

**Fix direction:** null-guard and fall back to global run vars. Confirm agent and Workbench paths.

### G1. Silent Selenium fallback in `auto` hides engine failures

**Where:** `CompositeRuntime.java:351-380` (`decide`; "client failed" at `:375`) and attach `forceLegacy` at `:295`; `CompositeLog` `fallbackOnce` / `errorFix`.

**What:** when the engine is missing, fails to start, or fails to join, `auto` quietly uses Selenium. The message prints only with `pkb_composite_log=fallback` or `pkb_composite=on`. "Binary usually missing" was treated as normal. With the Java engine always bundled, any fallback is a bug.

**Fix direction:** log one loud, configurable signal per run whenever `auto` falls back (WARN or equivalent, on by default or clearly configurable). Keep silence only for explicit `off`.

### G2. Lookup order differs between control protocol and runtime

**Where (1059667f):**
- `pickleball-control-protocol/.../DirectControlProvider.java:76-104` (Javadoc `:67-74`): for token `composite`, `COMPOSITE_BIN` first, then `pkb_composite_bin`, then the Java engine unless rust is requested, then Rust homes. Relative configured paths resolve against the project (`:101-104`); `explicitBin` also consults system properties (`:128-131`).
- `CompositeBinaryLocator.java:76-125`: named path is `pkb_composite_bin` property, then `pkb_runvars.pkb_composite_bin`, then `COMPOSITE_BIN`, then `pkb_composite_bin` env; then the Java engine unless rust is requested. Javadoc `:22-28` still describes the old Rust-only search and still presents `VERSION = "2.4.0"`.

**Effect:** the agent/control path and test runs can pick different engines. A parallel doc-fix session may already have unified this; re-verify.

**Fix direction:** one shared resolver, or identical order matching the design: `pkb_composite_bin`, then `COMPOSITE_BIN`, then the bundled Java engine.

### G3. Workbench Gradle run/test classpath lacks `dom-engine`

**Where:** `pickleball-workbench/build.gradle:58-65` (`dom-engine` only in `workbenchEmbed`, `transitive = false`, comment says it is not a compile or runtime project dependency); `:91-93` (`application` `mainClass`).

**Effect:** `:pickleball-workbench:run` / `installDist` / tests can run without the engine, so `DirectControlProvider` reports the binary missing or falls back. The packaged jar does include it.

**Fix direction:** put the engine on the Gradle run / test / `installDist` classpath without violating controller isolation. `verifyStrictControllerIsolation` must stay green.

### G4. Engine lacks `eval_nodes` and `limit`; adapter fallbacks are slower or incomplete

**Where:** `DomCompositeAdapter.java:96-101` (`multiEval`) and `:212-238` (`evalAll`): one round trip per node. `:275-282` (`limit`): the fallback logs but does not slice. Probe: `multiEval=false limitFlag=false`. Rust 2.4.0 had neither feature either, so this is not a Rust regression.

**Fix direction:** implement both in the engine, or make the adapter fallback actually slice / batch.

### G5. Hard-coded Rust 2.4.0 fallback sets used when capabilities cannot be read

**Where:** `DomCompositeAdapter.java:24-27` (`DESKTOP_VERBS_2_4_0`), `:710` (`desktopVerbs`), `:768-782` (`loadNamedVerbs`), and the `VerbPlan` 2.4.0 actions.

**Effect:** if the capabilities call fails once, the cached result downgrades the Java engine to Rust 2.4.0 behavior (for example, refusing desktop click/type with Rust guidance).

**Fix direction:** base the fallback on the Java engine's set, or do not cache a failed read.

### G6. Tests assert the Rust argv contract via stub binaries; there is no live Java-engine adapter test

**Where:** about 24 tests stub a fake binary and assert Rust argv flags, including `LeanWaitNestLiveTest`, `ContextNestGateTest`, `EnginePositionGateTest`, `AllContextsPlanTest`, `ElementSnapshotEvalTest`, `CompositeLocationTest`, `SelectorAndAdapterTest`, `ActionVerbPlanTest`, and the 2.4.0 stubs in `AgentLiveFixTest`. The `Java DOM live Chrome` job tests the engine CLI (`:dom-engine:test`) and a few protocol/unjoined tests, not `CompositeRuntime.attach` through `DomCompositeAdapter` / `ElementOperations` on a real page. Consumer `@dom-composite` features run only inside held `@all`.

**Fix direction:** see Tests. Do not delete or weaken the fake-binary tests.

### Stale text (behavior OK, message or Javadoc wrong) — include only if still present

1. Runtime messages: `CompositeRuntime.java:27` (MISSING text), join `errorFix` at `:292-293` / `:316-317`, and "client failed" at `:375` point users at installing the Rust `dom-composite` binary.
2. Locator and mode Javadoc: `CompositeBinaryLocator.java:21-28`, `:30` (`VERSION "2.4.0"` presented as the engine version), `:123` debug "no composite binary", `CompositeMode.java:6-12`.
3. Control protocol: `DirectControlProvider` Javadoc and skip message at `:167` (may already have been updated for the Java engine; re-check).
4. Adapter and core Javadoc: `DomCompositeAdapter.requireDesktopVerb` (`:636-652`), comments at `:50` and `:553`, `DESKTOP_VERBS` comment; `ElementOperations` class Javadoc (`:22-25`, "Selenium primary"); `SnapshotLookup:17`, `CompositeLog:16`, `ElementSnapshot:12`, `DriverConstruction:316`, `NestedByLocator:226`, `ElementSnapshotScript:309`, `VerbPlan:9-14`.
5. Vendored client: `dev.domcomposite.Client.findBin` has a Rust-only default lookup in its Javadoc and code. Runtime always passes an explicit path, so only tests reach it.

Reword remaining text to name the bundled Java engine and `pkb_composite_engine=rust` as the opt-in.

### Intentional / correct at audit time (do not "fix" these unless the tip shows a real defect)

1. Rust override paths (`pkb_composite_engine=rust`, explicit bin, `COMPOSITE_HOME`, sibling / natives / classifier) and `JavaEngineLauncher.rustRequested` are a documented opt-in. Risk: a stale `COMPOSITE_BIN` in the shell silently selects Rust; consider an INFO log.
2. Vendored `dev.domcomposite` client is the live wire client for both engines.
3. `dom-engine` parity comments that refer to Rust as history are accurate.
4. Desktop click/type gating reads `capabilities.desktop`. The Java engine lists click/type, so it works; only the refusal message may be stale.
5. Native use of Cucumber annotations, `ExpressionFactory`, and the StepExtension namespace follows the Round 3 design. A sentence that "the dynamic runtime does not depend on Cucumber" is inaccurate at the library level; that is a doc fix, not a code fix.
6. Dalton is opt-in; file-only is the default. `DaltonRunTrace` works under native. No pre-Dalton code defect was found.
7. Capability negotiation for `actions` / `query_flags` matched the Java engine's lists, apart from G4.
8. CI workflows carried no stale Rust-first assumptions. The live Chrome job is sound but narrow (G6).
9. Remaining test skips covered only an absent pwsh or Chrome, which is legitimate. Do not call Rust-binary skips legitimate; the Java engine replaced that binary.

### Uncertain / not verified (investigate; add to the roadmap if confirmed)

- Native vs Cucumber test counts for the same tag.
- D7 cause: tree-load timing vs engine frame-descent bug.
- Blast radius of D2–D10 once D1 is fixed (primitives only were tested).
- D12 reach into agent or Workbench driver creation.
- Native consumer run also logged an NPE for `$nope` (`MappingProcessor.boundRunningStep()` null during expression evaluation). Tests did not fail on it; it may be leftover Cucumber coupling.
- Side effect of the audit only (not a product defect): root `:test` depends on `publishToMavenLocal`, so the probe runs republished `~/.m2` `tools.dscode:pickleball:3.0.0` from `1059667f`, locally.

================================================================================
TESTS (mandatory; put these in the roadmap and the Build prompt)
================================================================================

- Add real end-to-end tests that drive the Java engine through the real adapter (`CompositeRuntime.attach` / `DomCompositeAdapter` / `ElementOperations`, the same path `DriverConstruction.ready()` uses) against real headless Chrome, for every capability in D1–D10 and the relied-on flags: page-target attach, full XPath 1.0 cases above, wait-until-visible / present with polling and stability, hover, displayed filter, frame and shadow scoping, all-frames search, stable identity / snapshots on duplicate elements, displayed and selected state, and eval_nodes / limit once designed.
- Skip those tests only when no Chrome binary is available (`DOMENGINE_CHROME`, then the same lookup `LiveChromeCdpTest` / `LiveChromeActionSmokeTest` already use). That is a legitimate skip. Do not skip for a missing Rust binary.
- Wire them so they run on every push: extend the existing GitHub Actions job `Java DOM live Chrome` (`dom-engine-live` in `.github/workflows/agent-contract.yml`). Do not invent a duplicate Chrome-install job if this one can run the new classes. Make failures visible (account for the `gate` job currently depending only on `windows`).
- Make the consumer `@dom-composite` features run in CI under the default engine, for both `native` and `cucumber` (`pkb_engine=native` and `pkb_engine=cucumber`), without needing the held `@all` suite. Do not use `@all` as Workbench/controller-isolation validation (existing `AGENTS.md` rule).
- Keep the existing fake-binary / argv contract tests. They must not be weakened, skipped, or deleted, and they must not remain the only coverage.
- Add a native-engine test that suite-level `BEFORE_CUCUMBER_RUN` / `AFTER_CUCUMBER_RUN` fire (D11), including consumer-hook parity.
- Follow `AGENTS.md` test policy: narrowest useful test first; consumer-visible behavior gets an executable consumer scenario when practical; keep a test under root `src/test` only when it must run inside the framework build (these live adapter tests likely do). Do not add a feature file per form.
- Between Build phases, only light targeted tests tied to the changed code. If those fail or stall, leave them failing, note them on the push, and (unless the failure is in the phase's own new required test) follow the roadmap's stated rule. Do not weaken tests to move on. A full multi-module regression is not this Heavy session's work; include it as the last Build phase if that matches current 3.0.0 process, not as the inner loop.

================================================================================
DELIVERABLES (this Heavy session)
================================================================================

You write documentation only, then push it to `origin/3.0.0`.

1. **Design doc:** `docs/maintainer/3.0.0-engine-defects-design.md`. Root-cause each remaining defect/degraded item against the current tip SHA. Record what you re-verified, what was already fixed, and the chosen design for D1–D12 and G1–G6 (attach model, hook parity, XPath via `document.evaluate`, capability honesty, fallback logging, unified lookup, Workbench classpath vs isolation, capability-probe policy). Engine API stays general-purpose. Include a CLI-first and logging checklist.

2. **Phased roadmap:** `docs/roadmaps/3.0.0-engine-defects-roadmap.md`. Every phase lists functionality, docs, and targeted tests. All phases mandatory. Follow the conventions of the existing 3.0.0 roadmaps in that directory. Include scoreboard placeholders. End with a final validation phase (index/guidance checks, live Chrome job, consumer `@dom-composite` on both engines, `verifyStrictControllerIsolation` if Workbench classpath changed). Push after the files are in place.

3. **Standalone Build prompt, in your final reply and as a file:** `prompts/3.0.0-engine-defects-build.md` (the repo already tracks `prompts/`). The Build prompt must be fully standalone: repo URL, branch, start SHA (the tip after your docs land), every remaining defect with evidence, the locked designs, phase list, test requirements, and all guardrails above. A fresh Grok Build chat must be able to implement every phase until finished or blocked without this Heavy chat. Tell Build to push each implementation change to `3.0.0`, to re-fetch before each push, and to run the index and guidance check scripts before pushing.

4. **AGENTS.md:** append-only short section pointing at the new design and roadmap. Do not rewrite existing sections, even if they are stale; stale live instructions that this work itself supersedes can be noted, not deleted. If public-contracts text is still Rust-binary-first and the doc-fix session has not yet corrected it, you may append a clarifying sentence only if that matches append-only practice; otherwise leave that correction to remaining doc-fix work or a dedicated docs phase in this roadmap.

5. Refresh `docs/agent/repository-index.md` with the script, keep the feature map accurate if you add canonical test/doc pointers, run the three check scripts, commit, and push to `origin/3.0.0`. Do not merge anything to master. Do not open a PR into master.

6. Final reply here must include: the tip SHA you started from and the SHA you pushed, the paths of the three files, a re-verification table (still broken / already fixed / new finding), the phase list, and the full Build prompt text.

================================================================================
PROCESS NOTES FOR THIS HEAVY SESSION
================================================================================

- GitHub auth is already set up. Push only to `3.0.0`, and only the documentation artifacts listed above.
- Do not start implementing engine or test code, even if a fix looks small.
- Do not truncate AGENTS.md, the repository index, or other docs.
- Temporary investigation output belongs in `.agent-work/` and must not be committed.
- If you need to run a short read-only reproduction to re-verify a finding, you may, but do not commit probe tests, republish artifacts as a "fix," or leave a dirty tree.
