# R5 — Workbench, one control plane, overlay, chat, multi-agent

Branch `3.0.0-spine` only. Never `master` or any 2.x branch. No tags, releases, publishing, or force-push.

Requires R4. Design: `docs/plans/2026-11-25-spine-design.md`. Dan approves the Distribution and licensing section before this Build session, because P1 and P4 package npm output. Scoreboard: `docs/maintainer/3.0.0-spine/R5-scoreboard.md`. Build prompt: `prompts/build-3.0.0-spine-R5.md`.

Every phase is mandatory and must be completed in full. Nothing in R5 is optional or deferred. The code tab is this roadmap, not a later milestone. Stop only on an honest test failure you cannot fix, or on a hard-rule violation.

Targeted tests only. Do not run consumer `@all`. Phases that touch the control bridge run `@control-bridge` and `@step-override-bridge` only when that phase changes bridge payloads, with `-Dpkb_runvars.pkb_parallel=80`. R5's window is a renderer over existing verbs. It does not change the bridge payload unless a test proves it must. Prefer the in-process CLI tests. Full regression waits until the end of R8. Windows only.

Never weaken, skip, or delete a test. Keep Gherkin. Step annotations stay single string literals. Never run `.\gradlew.bat --stop`.

Fetch and rebase before every push. Push after every green phase. Each git command stands alone. Before every push run `python scripts/sync_consumer_guidance.py` and `python scripts/refresh_agent_index.py`. Overlay and surface-asset changes update `tools.dscode.browser.SurfaceAssetsTest` when the hashed bytes change.

Workbench isolation: the Node UI is not compiled into `pickleball-workbench`. Assets live at `META-INF/pickleball/workbench-ui/` in the pickleball jar. The nested workbench jar stays free of pickleball classes and free of `node_modules`. The Workbench process does not open SQLite and does not spawn Node. `PickleballWorkbenchLauncher` spawns Node. Do not add `implementation project(':')` to the workbench build.

## CLI-first and logging checklist

- Every panel action is a CLI verb from R4's catalog, or a verb this roadmap adds to that catalog in the same phase.
- The Node server does not invent a private command channel. `VerbCatalogTest` scans `workbench-ui/` and fails a fetch or HTTP path that is not a catalog verb.
- Log the renderer with `EngineLog` capability `host`, entity `ui`. Off unless named.

## P1 — Node renderer shell

### Functionality

Add `workbench-ui/package.json`, `workbench-ui/package-lock.json`, `workbench-ui/server.mjs`, and `workbench-ui/pages/`. The Gradle task `workbenchUi` runs the driver-bundle Node (the same resolution as `PlaywrightNode.locate`) against a checksum-pinned npm CLI in the Gradle cache, then `npm ci --omit=dev`. Registry override `pkb_npm_registry`. Allowed licenses: MIT, Apache-2.0, BSD-2-Clause, BSD-3-Clause, ISC. P1's lockfile may contain only packages required to serve static files. The server itself is `node:http` and `node:fs`. Do not depend on Express. If the lockfile would be empty, do not run npm in P1. P4 adds Monaco and is the phase that must run npm. P1 still adds the server and the pages as jar resources copied from `workbench-ui/` without `node_modules`.

`PickleballWorkbenchLauncher` verb `ui` starts the server only when a person will look at it. Tests drive verbs headless and do not start the window. The server binds `127.0.0.1` on an ephemeral port, writes `.pickleball/workbench-ui/host.json` with the port and a 128-bit hex token, and checks the `Host` header the way `PopupHost` does. The browser window is `PopupBrowserLocator` (system Chrome or Edge). No Chromium writes `state=closed` and the CLI still works.

Pages, in this order, most prominent first:

1. Scenarios / Tests. Business-language steps from `store scenarios` and `store steps`. A step expands by calling `store steps list` and `store glue show` for the matched definition. Java, JS, and TS all show.
2. Globals. Forms over `store var list` and `store var set`.
3. Data. File list from `store files list`, shared search box.
4. Raw SQL. Least prominent. Calls a new read-only verb `store sql --query=<select>` that allows a single `SELECT` and rejects anything else with `error:` and `fix:`. Implemented in `StoreCli` using the existing connection. No writes.
5. Branch selector on every page. Calls `store sandbox list` and `store sandbox use`. Title and accent color use the sandbox name. The color palette is fixed in CSS. Fork colors are R6.

Search on Data and SQL accepts `exact:`, `re:`, or a plain substring. The server sends that as `--text` or as a `LIKE` / `REGEXP` the `store sql` verb runs. SQLite `REGEXP` is not built in. Implement `re:` in Java by filtering the result rows, not by a SQL function the driver does not have.

Remove the Swing window in this phase. `WorkbenchFrame` and the Swing panel classes stop being the launched UI. Keep a class only if a test still constructs it. After the Node pages cover the same verbs, delete `WorkbenchFrame` launch from `WorkbenchApplication` and point `ui` at the Node server. Do not leave two windows. Update tests that instantiated `WorkbenchFrame` so they call `VerbDispatch` instead of clicking Swing. That is a replacement, not a deleted assertion: the assertion moves to the verb result.

Controller isolation tests that scan the workbench jar stay. `node_modules` must not appear inside `pickleball-workbench.jar`.

### Docs

Rewrite `docs/pickleball-workbench.md` so the window is the Node renderer and Swing is gone. Update `docs/README.md` and `docs/getting-started.md` sentences that say Swing UI. `AGENTS.md` Workbench paragraphs. `THIRD-PARTY-NOTICES.md` only if P1 actually packaged an npm module. Release notes.

### Tests

Create `src/test/java/tools/dscode/launcher/WorkbenchUiServerTest.java`. Start the server on a temp project, `GET /` with the token and `Host: 127.0.0.1`, assert the body contains `Scenarios` before `Raw SQL`. A request with the wrong token is 401. A request with a non-loopback Host is rejected.

Create `src/test/java/tools/dscode/launcher/StoreSqlReadOnlyTest.java`. `SELECT` returns rows. `DELETE` exits 2.

Update `VerbCatalogTest.privateRouteFails` to scan `workbench-ui/pages` and `server.mjs` for a URL path. Every path the server handles is a catalog verb or the static page itself (`/`, `/scenarios`, `/globals`, `/data`, `/sql`). A path shaped like `/secret-action` fails the test.

```
.\gradlew.bat :test --tests tools.dscode.launcher.WorkbenchUiServerTest --tests tools.dscode.launcher.StoreSqlReadOnlyTest --tests tools.dscode.launcher.VerbCatalogTest --console=plain
```

Do not open a browser in the unit test. `PopupBrowserLocator` is not called.

## P2 — Overlay, injection, sessions, agents

### Functionality

This phase closes 6.6.4 and the one-control-plane bullets for overlay, lease addressing, external state, and multi-agent layers. The lease *takeover* verb is P5. This phase stores the session ids that lease will use.

Default injection is a sibling overlay. `tools.dscode.common.agentcontrol.PageSurfaces` and `tools.dscode.browser.SurfaceAssets` already draw in-page. The default for a new session is `inject=off` (clean). `inject on --session=<id>` sets the flag and installs the sibling overlay. `inject off` removes it. An attached CDP window uses the same flag. The fail-clearly path that already shipped stays for `inject=off`. `inject on` on an attached window runs `RuntimeInstall` against that target. Direct DOM mutation remains the fallback when the verb is `highlight` with `--mutate=dom`. The default `--mutate` is `sibling`.

External state stays in the existing `UiState` and `ChatLog` stores. A reload test re-reads that state and re-emits the overlay. In-page variables are not the source of truth.

Every `VerbDispatch` call requires `--session=<id>` or a default session created by `spine session new`. Agent id is `--agent=<id>`, default `agent-1` for a human CLI with no agent. Two agents on one session get two overlay layers. The layer id is the agent id. Commands carry both ids into `AgentEnvelope.state`.

CDP and BiDi remain the observation path (`SeleniumBidiEvents`, `BrowserEventHub`). Do not add a mutation listener that duplicates `DOM.attributeModified` or network events. A listener is allowed only for overlay-ack (`read_at`), which the protocol does not store. That ack is P3.

`pkb_browser_log` entity `facade` logs `inject on` and `inject off` with the session id.

Verbs added to the catalog and the inventory JSON: `inject`, `spine session`.

### Docs

`docs/direct-control-handoff.md`, `AGENTS.md` agent-surface paragraphs, tech-debt row 6.6.4 removed. Release notes: clean is the default. Update `SurfaceAssetsTest` if the overlay markup bytes changed.

### Tests

Create `src/test/java/tools/dscode/common/agentcontrol/InjectSessionTest.java`. Default session is clean. `inject on` sets the flag. A second attach to a fixture target records `injected=true`. `inject off` clears it.

Create `src/test/java/tools/dscode/common/agentfast/SessionAddressTest.java`. Two agent ids produce two envelope `state.agent` values and do not share an overlay layer id.

Extend `NativeHighlightRouteTest` so the default mutate mode is `sibling`.

```
.\gradlew.bat :test --tests tools.dscode.common.agentcontrol.InjectSessionTest --tests tools.dscode.common.agentfast.SessionAddressTest --tests tools.dscode.common.agentsurface.NativeHighlightRouteTest --tests tools.dscode.browser.SurfaceAssetsTest --console=plain
```

## P3 — Chat, point, cards, progress, receipts

### Functionality

Closes 6.5.1, 6.5.2, 6.5.3, 6.5.4, 6.5.6, 6.5.7.

Chat exists from `spine session new`, before any scenario. Store remains `ChatLog`. Channel id defaults to the surface session id. `pkb_chat_channel=run` uses one channel per run, which is today's behavior. Default is `session`.

`chat send --text= --ref= --action=` stores `ref` and `action` columns on the chat row. Empty is allowed. `chat --follow` is the stream. One-shot `chat list` stays.

`point --session= --x= --y=` or `point --session= --handle=` records a point row and highlights through the existing highlight verb. The Node page button calls `point`. There is no button-only path.

`ask --type=text|choice|confirm --prompt= --options=` writes a card. `ui-poll` returns the answer with `type` and `value`. A choice answer not in `--options` is rejected.

`progress --pct= --label=` writes a progress card. `pct` is 0 through 100.

Overlay messages gain `read_at`. The overlay ack sets it. `overlay-poll` returns `read_at`. Null until acked.

All of these verbs go into `VerbCatalog` and the help files.

### Docs

`docs/dynamic-control-api.md` chat section. Remove tech-debt rows 6.5.1 through 6.5.4, 6.5.6, 6.5.7. Release notes name the channel default change. `docs/configuration.md` gains `pkb_chat_channel`.

### Tests

Create `src/test/java/tools/dscode/common/agentcontrol/ChatChannelTest.java`. Default channel is the session id. `pkb_chat_channel=run` shares one channel. `chat send --ref=h1 --action=open` round-trips both fields.

Create `src/test/java/tools/dscode/common/agentcontrol/AskProgressPointTest.java`. `ask --type=choice` rejects an unknown option and accepts a listed one. `progress --pct=140` fails. `point` stores the handle. `overlay-poll` shows `read_at` after the ack method is called.

```
.\gradlew.bat :test --tests tools.dscode.common.agentcontrol.ChatChannelTest --tests tools.dscode.common.agentcontrol.AskProgressPointTest --tests tools.dscode.launcher.VerbCatalogTest --tests tools.dscode.launcher.GuidanceCommandGoldenTest --console=plain
```

## P4 — Monaco code tab

### Functionality

Add `monaco-editor` (MIT) and `typescript` (Apache-2.0) to `workbench-ui/package.json`. Commit the lockfile. `workbenchUi` runs npm as specified in the design. The packaged tree is `META-INF/pickleball/workbench-ui/`. The license check fails the build on any other license. No React, no Electron, no `tsx`, no `axe-core`.

The Scenarios tab's "open definition" action loads Monaco from that tree in the page. Edits call `store glue put`. They do not write the file from Node. A failed `store glue put` shows the CLI `error` and `fix` in the page.

`THIRD-PARTY-NOTICES.md` lists `monaco-editor` and `typescript` with their licenses and the jar path.

### Docs

`docs/pickleball-workbench.md` code tab. `THIRD-PARTY-NOTICES.md`. Release notes. Distribution paragraph matches the design: build-time npm, no user install.

### Tests

Create `src/test/java/tools/dscode/launcher/WorkbenchUiLicenseTest.java`. Read the packaged resource listing and the lockfile. Fail if a package name is outside `monaco-editor` and `typescript` and their required transitive packages that the test's allow-list of license strings accepts. Fail if `node_modules` appears in the workbench jar. Assert the pickleball jar contains `monaco-editor` under `META-INF/pickleball/workbench-ui/`.

`WorkbenchUiServerTest` gains one request for the code page that returns 200 and contains `monaco` only after the assets are built. If the unit test cannot unpack the jar, assert the resource URL from the classpath.

```
.\gradlew.bat :test --tests tools.dscode.launcher.WorkbenchUiLicenseTest --tests tools.dscode.launcher.WorkbenchUiServerTest --console=plain
```

## P5 — Lease, input block, generated pages

### Functionality

`lease take --target=ui|browser|both --session=` and `lease release` call the existing `WorkbenchControlLease` / `LeaseHold` methods. A second holder is refused with `error:` and `fix:`. The Node shell's lease button calls this verb.

`input block --session= --on|--off` is the mouse and keyboard block on a browser session. It uses the existing human-lock / input path in `HumanLock` and `InputCommands`. It does not add a new input stack. `--on` fails if the session is clean (`inject=off`) with `fix: inject on`. Blocking a clean page is not allowed, because the block is an injection.

A generated page is a static file written under `.pickleball/workbench-ui/generated/<session>/<name>.html` by `ui page --session= --name= --html-file=`. The server serves it only for that session token. The HTML is checked for a script that calls a URL outside the catalog. `VerbCatalogTest` performs that check. The page is deleted on `spine session` end.

### Docs

`docs/pickleball-workbench.md` lease and generated pages. `docs/dynamic-control-api.md`. Release notes.

### Tests

Create `src/test/java/tools/dscode/launcher/LeaseAndBlockTest.java`. Two `lease take` calls on one target: the second fails. `input block --on` on a clean session fails. After `inject on`, it succeeds and `input block --off` clears it.

Extend `VerbCatalogTest` so a generated HTML fixture with `fetch("/not-a-verb")` fails, and one that only posts to a catalog verb passes.

```
.\gradlew.bat :test --tests tools.dscode.launcher.LeaseAndBlockTest --tests tools.dscode.launcher.VerbCatalogTest --console=plain
```

## Out of R5

Do not implement dynamic-mode forks, promote UI behavior beyond the R1 verb, snapshot retention, the warm classloader, or browser CDN downloads. The branch selector may call `store sandbox use`. It does not fork.
