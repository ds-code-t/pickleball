# Build prompt: 3.0.0 spine R5, fix-back 1 (production wiring and gaps)

This prompt stands alone. You are a fresh headless Grok Build session on Dan's Windows PC. You have not seen the planning chat. Your job is to close every R5 gap listed below, on branch `3.0.0-spine` only, and then stop.

- **Repo:** https://github.com/ds-code-t/pickleball
- **Branch:** `3.0.0-spine` only. Never `master`. Never `3.0.0-spine-r8`. No tags, releases, publishing, or force-push.
- **Worktree:** `C:\Users\Admin\IdeaProjects\pkb-spine`
- **Toolchain:** JDK 21. Windows commands. `.\gradlew.bat`. Never run `.\gradlew.bat --stop` or `./gradlew --stop`. Use `python`, not `python3`.

## Read first

Read these files in full before you change anything. The branch docs win over this prompt if they disagree. If they disagree, follow the docs and say so on the scoreboard.

1. `AGENTS.md`
2. `docs/roadmaps/3.0.0-spine/R5-workbench-one-control-plane.md` (the R5 roadmap is the source of truth for behavior, including **Out of R5**)
3. `docs/maintainer/3.0.0-spine/R5-scoreboard.md`
4. `docs/release-notes.md` (the `Spine R5 P1` to `Spine R5 P5` paragraphs)
5. `docs/tech-debt.md`
6. `docs/plans/2026-11-25-spine-design.md`, sections on the Node workbench, overlay, chat, and **Distribution and licensing**
7. `docs/pickleball-workbench.md`, `docs/dynamic-control-api.md`, `docs/direct-control-handoff.md`, `docs/verb-inventory.md`, `THIRD-PARTY-NOTICES.md`

Also follow the functionality-change rules in `AGENTS.md` for code, tests, docs, release notes, consumer guidance sync (`python scripts/sync_consumer_guidance.py`), and the repository index (`python scripts/refresh_agent_index.py`).

## State

R1, R2, R3, R4, R7, and R5 P1 to P5 are on `3.0.0-spine` (R5 head `efe775e2`). Every R5 class and its targeted test exists and passes. A static review of the pushed code found that much of R5 is reached only when a test calls a class directly (`WorkbenchAgentCommands.run`, `AskCards.answer`, `OverlayReceipts.ack`, `SpineSession.attach`), or is a stored flag or label with no behavior behind it. The docs and release notes already describe the roadmap behavior. Your work makes production match the roadmap.

R8 is being fixed at the same time in another worktree (`C:\Users\Admin\IdeaProjects\pkb-spine-r8`, branch `3.0.0-spine-r8`). R6 has not started. Do no R6 or R8 work.

First commit: add a section `## Spine R5 production wiring (open, found in R5 verification 2026-11-26)` to `docs/tech-debt.md` with one line per gap below (number and one sentence). Push it. Remove each line in the commit that closes that gap. Remove the section when all gaps are closed.

## The gaps

For each gap: wire a real production caller so the roadmap behavior happens in a normal run (the `pickleball` launcher main, `VerbDispatch`, the Node bridge, the overlay runtime, or the page). Do not satisfy a gap by calling a test hook from production code, by adding another test-only hook, or by changing the docs to describe less.

1. **UI assets from the jar.** `WorkbenchUiServer.assets()` only resolves a `file:` resource or `./workbench-ui`, so `pickleball ui` fails with "assets are missing" for anyone running the published jar. Per the design, the launcher explodes `META-INF/pickleball/workbench-ui/` (including `node_modules`) beside the run (under `.pickleball/`, keyed by the jar version or content hash so a stale copy is replaced), sets the script path and `NODE_PATH`, and starts the driver-bundle Node on it. No network. Test: build or assemble a real jar (or a jar-only classpath with no exploded `META-INF/pickleball/workbench-ui` directory) and prove `WorkbenchUiServer.start` serves `/` and a Monaco asset from that jar.
2. **Launcher routing for `ui page`.** `PickleballWorkbenchLauncher.controllerUiWithoutOps` treats any `ui` without `--ops` as bare `ui`, so `pickleball ui page --session= --name= --html-file=` opens the renderer instead of writing the page. Only bare `ui` (no subcommand) opens the renderer. Test through the launcher's real dispatch (`PickleballWorkbenchLauncher` main path or the exact method it calls, not `WorkbenchAgentCommands.run`).
3. **`open-window`.** `WorkbenchSessionActions.openUi` now returns `playback=NODE`, but `WorkbenchSessionCommands.tryShareController` waits for `OPENING`, then falls through to `startUi`, which starts the controller `ui` that only prints a line and exits ("window exited before it could show the run"). Make `open-window --run-id` (and `show-run` when a window is open) open or reuse the Node renderer on that run, with no fall-through to a dead controller `ui` and no Swing. Agent attach to the open window must still work (the old `attach.json` `ui-attach` endpoint was written by the Swing frame; replace it with the renderer's equivalent or document the replacement). Test the real command path with a stand-in renderer if Node cannot run in the unit test.
4. **Inject, RuntimeInstall, mutate, layers, reload.**
   - `inject on --session=` must install the sibling overlay on the session's live page(s) through `PageSurfaces` / `SurfaceAssets`. `inject off` must remove it. Today `SpineSession.setInject` only writes a JSON flag.
   - `inject on` on an attached window must send `pkb-runtime.js` to that target through the real `RuntimeInstall` path keyed by the real driver key. Today `SpineSession.attach` calls `RuntimeInstall.consume(target.hashCode())`, which only marks a fake key.
   - `--mutate=sibling` (the default) draws in the sibling overlay layer. `--mutate=dom` uses direct DOM mutation for `highlight`. Today `AgentSurfaceRouter.nativeResult` only stamps a label.
   - Two agents on one session draw in two overlay layers whose ids are the agent ids. Today this is metadata only.
   - Add the roadmap's reload test: state stays in `UiState` / `ChatLog` / the session file, and a page reload re-reads it and re-emits the overlay.
   Use a local fixture page or the existing in-process page test harness. No browser download.
5. **Overlay read receipts and typed ask answers.**
   - `OverlayReceipts.record` and `OverlayReceipts.ack` have no production caller. Record a receipt when an overlay message is shown. Set `read_at` from the overlay's own ack (the in-page runtime reports it, through the existing poll / input channel). `overlay-poll` returns `read_at`, null until acked.
   - `AskCards.answer` has no production caller. Add the answer path: a CLI verb (catalog, help, inventory) and a renderer page control, plus the overlay card if `ask` shows one. A choice not in `--options` is rejected. `ui-poll` then returns `type` and `value`.
6. **`chat --follow` is a real stream.** Today it sleeps the timeout and prints one list. It must emit each new message as it arrives (same envelope shape the other stream verbs use), until timeout or interrupt. `--once` still drops `--follow` in `VerbDispatch`.
7. **Lease uses the existing lease.** `UiLease` is a new file lock that nothing consults. `lease take|release --target=ui|browser|both --session=` must call the existing `WorkbenchControlLease` / `LeaseHold` methods, as the roadmap says. `both` conflicts with `ui` and with `browser`. A second holder is refused with `error:` and `fix:`. The holder actually gates the controls that lease protects. Keep `spine session end` releasing that session's leases.
8. **Input block blocks the human.** `input block --on` today only makes the agent's own `input` verb refuse. It must block human mouse and keyboard on the page (an injected blocker through the existing human-lock / input path in `HumanLock` and `InputCommands`, no new input stack), while agent input still goes through. `--on` still fails on a clean session with `fix: inject on`. `--off` removes the blocker.
9. **Generated pages.** `VerbCatalog.outsideCatalog` only checks quoted paths that start with `/`. It must also refuse absolute and external URLs (`http:`, `https:`, `//host`, `ws:`, and so on) and computed URLs it cannot prove are catalog verbs. Generated pages are served under the session token, but `/store` needs the main UI token, so a generated page cannot call any verb. Give generated pages a working path to `/store` under their own session token, limited to catalog verbs.
10. **No route hiding.** `workbench-ui/server.mjs` and `workbench-ui/pages/index.html` build `/node_modules/`, `/ui/`, and the page path by joining strings, which hides them from the `VerbCatalogTest` route scan. Write those routes as plain literals and make the scan accept them by rule (static pages, the asset prefix, the generated-page prefix), so `VerbCatalogTest` really sees every route.
11. **Pages.**
    - Scenarios: a step expands through `store steps list` and `store glue show` for the matched definition. Java, JS, and TS all show.
    - Globals: a form over `store var list` and `store var set`.
    - Data: `store files list` with the shared search box (`exact:`, `re:`, substring).
    - Branch selector: every page lists all sandboxes from `store sandbox list` and switches with `store sandbox use`. Title and accent use the sandbox name.
    - Code tab: "open definition" must load the source text, not the `glue show --json` wrapper JSON. Save must write that source through `store glue put`. Today an open-then-save round trip writes the JSON wrapper into the glue file. Test that round trip.
    - Point button on the Scenarios page posts `point` with the session and a real handle or coordinates.
12. **Monaco page test.** `WorkbenchUiServerTest` passes on the static `id="monaco"` element even when Monaco was never built. Make it fail unless the packaged `monaco-editor` loader is actually served (for example request `/node_modules/monaco-editor/min/vs/loader.js` and assert 200 and real content).
13. **Session required.** Per the roadmap, every `VerbDispatch` call requires `--session=<id>` or a default session created by `spine session new`. Enforce it at the dispatch entry, with `error:` and `fix: spine session new`. Find every existing caller and test that now needs a session and give it one. Do not weaken those tests.
14. **`store sql` read-only connection.** Today read-only is a text check only. Open a read-only connection (SQLite `query_only` / read-only open mode) for `store sql`, keep the text check, and apply `--text` filtering before the row cap so a match after row 200 is not lost. Test that a write smuggled past the text check still fails at the connection.
15. **Docs for the Node design.** Rewrite in place the Swing-as-current sections of `docs/pickleball-workbench.md` (window, lease, play/edit/Save, event thread, editor, adapters) for the Node renderer, and the `AgentHop` comment "Bare ui stays the Swing controller". History goes only in `docs/release-notes.md`.
16. **License allow-list.** `WorkbenchUiPackager.ALLOWED` and `WorkbenchUiLicenseTest` add `Unlicense` and `CC0-1.0`. The design allows MIT, Apache-2.0, BSD-2-Clause, BSD-3-Clause, ISC, and the public domain. Either remove them or justify them on the scoreboard as public-domain dedications under the design's "public domain" entry. Do not loosen anything else.
17. **Packaging stays.** Keep `monaco-editor` (MIT) and `typescript` (Apache-2.0) exactly as the roadmap approved. Do not drop `typescript` (Dan is deciding). Do not add packages, a second Node, or any runtime download.

## Tests

- For each gap, add a test that fails before your fix and passes after it, through the production caller (launcher dispatch, `VerbDispatch`, the Node bridge, the overlay runtime entry point, the real page), not a test seam. Where a real browser cannot run in a unit test, drive the production entry point with a local stand-in (a local fixture page, a local HTTP server, a stub driver registered through the real session registry). No browser download in any test.
- Before committing each fix, prove the new test fails without the fix (run it once before the production change) and say so in the scoreboard notes.
- Never weaken, skip, disable, or delete a test. No `@Disabled`, `assumeTrue` skips, or empty tests. Keep Gherkin syntax. Step annotations stay single string literals.
- Run targeted tests only: the new tests, the R5 tests (`WorkbenchUiServerTest`, `StoreSqlReadOnlyTest`, `VerbCatalogTest`, `InjectSessionTest`, `SessionAddressTest`, `NativeHighlightRouteTest`, `SurfaceAssetsTest`, `ChatChannelTest`, `AskProgressPointTest`, `GuidanceCommandGoldenTest`, `WorkbenchUiLicenseTest`, `LeaseAndBlockTest`), and the direct tests of any class you edit (for example `WorkbenchSessionCommandsTest`, `WorkbenchSessionActionsTest`, `WorkbenchWindowLifecycleTest`, `WorkbenchControlLeaseTest`, `LeaseHoldTest`, `VerbDispatchTest`). Use `.\gradlew.bat :test --tests <class> --console=plain` and `.\gradlew.bat :pickleball-workbench:test --tests <class> --console=plain`. Update `tools.dscode.browser.SurfaceAssetsTest` when overlay bytes change.
- Run `@control-bridge` and `@step-override-bridge` with `-Dpkb_runvars.pkb_parallel=80` only if a change alters bridge payloads.
- No full-suite run: no unfiltered `:test`, `:pickleball-workbench:test`, `:dom-engine:test`, `:pickleball-control-protocol:test`, `:pickleball-control-api:test`, and no consumer `@all`. The full regression, the R1 to R8 Windows-only sweep, and the docs audit stay deferred until after the R8 merge and R6.
- Windows only. Append every new Windows-only test class to the `**Spine R5, Windows only:**` line in `docs/tech-debt.md`.

## Docs

- Remove each gap's line from `## Spine R5 production wiring (open, ...)` when it closes. Remove the section when all are closed.
- Make `docs/pickleball-workbench.md`, `docs/dynamic-control-api.md`, `docs/direct-control-handoff.md`, `docs/configuration.md`, `AGENTS.md`, and `THIRD-PARTY-NOTICES.md` match what is implemented. Add a `Spine R5 fix-back 1.` paragraph to `docs/release-notes.md`.
- Run `python scripts/sync_consumer_guidance.py` and `python scripts/refresh_agent_index.py`. Regenerate `docs/verb-inventory.md`, `docs/roadmaps/workbench-cli-inventory.json`, and the help goldens when a verb or help text changes.
- Add a `## Fix-back 1` section to `docs/maintainer/3.0.0-spine/R5-scoreboard.md` with one row per gap: gap number, status, commit SHA, targeted test classes with pass and fail counts, and a note that the new test failed before the fix.

## Branch rules

- Work only in `C:\Users\Admin\IdeaProjects\pkb-spine` on branch `3.0.0-spine`.
- Push after every change. Before every push run, each on its own: `python scripts/sync_consumer_guidance.py`, `python scripts/refresh_agent_index.py`, `git fetch origin 3.0.0-spine`, `git rebase origin/3.0.0-spine`, `git push origin HEAD:3.0.0-spine`. If the rebase rewrites a recorded SHA, update the scoreboard in a new commit.
- Never push to `master` or `3.0.0-spine-r8`. Do not merge `3.0.0-spine-r8`. No tags, releases, publishing, or force-push. Never run `gradlew --stop`.
- Do no R6 or R8 work. Do not touch `SpineCli`, the R8 `spine start|stop|warm|sample` verbs, BiDi, browser ports, or the warm engine.
- Never touch the `C:\Users\Admin\IdeaProjects\pkb-spine-r8` or `C:\Users\Admin\IdeaProjects\pkb-spine-r7` folders.
- No secrets or tokens in cleartext in code, logs, pages, commits, or process arguments.
- If a gap genuinely needs R6 or R8 code, close everything else and record that gap on the scoreboard as `waiting on R6/R8 merge` with the reason.

## Shared Maven local repository

Before any `publishToMavenLocal` or `maven-consumer-project` `mvnw` run, check whether a java, gradle, or mvn process whose command line contains `pkb-spine-r8` is running `publishToMavenLocal` or `mvnw`. If one is, wait with `Start-Sleep 120` and re-check, up to 60 minutes, before you publish. Run your consumer tag tests right after your own `publishToMavenLocal`.

## Finish

After the last push, confirm the working tree is clean and `origin/3.0.0-spine` equals your HEAD. Then STOP and report the head commit SHA, the per-gap scoreboard rows, and anything recorded as waiting on R6/R8.
