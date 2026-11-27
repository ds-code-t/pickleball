# Build prompt: 3.0.0 spine R5, fix-back 2 (real process boundary)

This prompt stands alone. You are a fresh headless Grok Build session on Dan's Windows PC. You have not seen the planning chat. Your job is to close every R5 item listed below, on branch `3.0.0-spine` only, and then stop.

- **Repo:** https://github.com/ds-code-t/pickleball
- **Branch:** `3.0.0-spine` only. Never `master`. Never `3.0.0-spine-r8`. No tags, releases, publishing, or force-push.
- **Worktree:** `C:\Users\Admin\IdeaProjects\pkb-spine`
- **Toolchain:** JDK 21. Windows commands. `.\gradlew.bat`. Never run `.\gradlew.bat --stop` or `./gradlew --stop`. Use `python`, not `python3`.

## Read first

Read these files in full before you change anything. The branch docs win over this prompt if they disagree. If they disagree, follow the docs and say so on the scoreboard.

1. `AGENTS.md`
2. `docs/roadmaps/3.0.0-spine/R5-workbench-one-control-plane.md` (the R5 roadmap is the source of truth for behavior, including **Out of R5**)
3. `docs/maintainer/3.0.0-spine/R5-scoreboard.md`, including the `## Fix-back 1` section
4. `prompts/build-3.0.0-spine-R5-fixback1.md` (what fix-back 1 was asked to do)
5. `docs/release-notes.md` (the `Spine R5` paragraphs, including `Spine R5 fix-back 1.`)
6. `docs/tech-debt.md`
7. `docs/roadmaps/3.0.0-spine/final-regression.md`
8. `docs/plans/2026-11-25-spine-design.md`, sections on the Node workbench, overlay, chat, sessions, and **Distribution and licensing**
9. `docs/pickleball-workbench.md`, `docs/dynamic-control-api.md`, `docs/direct-control-handoff.md`, `docs/verb-inventory.md`

Also follow the functionality-change rules in `AGENTS.md` for code, tests, docs, release notes, consumer guidance sync (`python scripts/sync_consumer_guidance.py`), and the repository index (`python scripts/refresh_agent_index.py`).

## State

R1, R2, R3, R4, R7, R5 P1 to P5, and R5 fix-back 1 (head `fde3359f`) are on `3.0.0-spine`. A static review of the pushed code found that fix-back 1 closed most gaps, but several still work only inside the test JVM:

- `inject`, `input block`, and `lease` are not in the `AgentHop` hop list, so `pickleball inject on` and `pickleball input block --on` run in the short-lived CLI JVM, where `PageSurfaces.managed()` is empty. The overlay and the input shield are never installed on a real page. The reload replay in `PageSurfaces.replayStored` only runs in a JVM that itself ran `inject` (it needs `injectProject`). `InjectLivePageTest` and `InputBlockLivePageTest` pass because they register a fake page with `PageSurfaces.manage` in the test JVM and call `WorkbenchAgentCommands.run` in that same process.
- `OverlayReceiptAskTest` sets `read_at` by queuing a fake `click` event. Nothing proves the in-page runtime sends that event; its document click handler in `pkb-runtime.js` returns early for `ours(node)`, which may skip clicks on the overlay itself.
- `WorkbenchRendererHost` ignores `--run-id` (it reads only the project argument), and its `show-run` answers `SHOWN` without doing anything. `OpenWindowRendererTest` reads the run marker from the CLI's own output.
- Fix-back 1 gap 13 made every `VerbDispatch` call require a session. The Node renderer's `/store` bridge goes through `VerbDispatch`, but `workbench-ui/pages/index.html` sends no `--session`, so on a fresh project every page call fails with `fix: spine session new`. `WorkbenchPagesTest` uses canned store replies, so it does not catch this. `engine help` and `domengine version` now also need a session.
- The lease gates only `ui page` and the old `WorkbenchController` path. The renderer's mutating calls (`store glue put`, `store var set`) are not lease-gated.
- `PolishRoadmapTest.warmPingIsUnderOneSecond` fails on Windows with `CreateProcess error=206` because it passes the full classpath with `-cp` on the command line. It was left failing and is not recorded in tech-debt.
- The `Spine R5 fix-back 1.` release-note paragraph says `inject on` installs the overlay on each managed page and `input block --on` stops human mouse and keyboard. Through the CLI, neither happens on a real page today. The `Deferred from the 3.0.0 consumer-fixes branch` table in `docs/tech-debt.md` still lists Point, typed answers, overlay read receipts, and runtime injection on attached windows as deferred.

R8 is being fixed at the same time in another worktree (`C:\Users\Admin\IdeaProjects\pkb-spine-r8`, branch `3.0.0-spine-r8`). R6 has not started. Do no R6 or R8 work, and do not merge `3.0.0-spine-r8`.

First commit: add a section `## Spine R5 fix-back 2 (open)` to `docs/tech-debt.md` with one line per item below (number and one sentence). Push it. Remove each line in the commit that closes that item. Remove the section when all items are closed.

## The items

For each item: make the roadmap behavior happen through the real production path (the `pickleball` launcher main, `AgentHop` / the controller hop, the live bridge, `VerbDispatch`, the Node bridge, the overlay runtime, or the page). Do not satisfy an item by calling a test hook from production code, by adding another test-only hook, by running the behavior inside the test JVM as a stand-in for another process, or by changing the docs to describe less.

1. **`inject`, `input block`, and `lease` reach the page JVM.** Route `inject` (on, off, the attached-window runtime install, `--mutate`, per-agent layers, reload replay), `input block --on|--off`, and `lease take|release` to the JVM that holds the managed pages, through the `AgentHop` list / controller hop or the live bridge the other agent verbs use (add `AgentControl` ops as needed). Do not invent a private channel. The page JVM must know the project and session so `replayStored` works after a navigation there. Agent `input` still passes the shield.
   Test: start the page-holding JVM (the controller or live host) with a real local Chrome or Edge on a local fixture page served by a local HTTP server, then run `pickleball inject on --session=...` and `pickleball input block --on --session=...` from a **separate CLI process** (`PickleballWorkbenchLauncher` main in its own JVM). Prove the sibling overlay layer exists in the real DOM, two agents give two layers, a reload re-shows it, a real synthetic human event (a CDP `Input.dispatchMouseEvent` / `Input.dispatchKeyEvent` or a WebDriver Actions click and key that the page sees as user input) does not reach the page's handler while the shield is on and does reach it after `--off`, and agent `input` still goes through. Use the locally installed browser; no browser download.
2. **Overlay ack sets `read_at` through the real runtime.** Make a human click (or the runtime's read/ack) on a shown overlay message send the event that `overlay-poll` turns into `read_at`, fixing the `ours(node)` early return in `pkb-runtime.js` if it drops those clicks. Test in a real page: `overlay` from a separate CLI process, a real click on the overlay node in the browser, then `overlay-poll` from a CLI process returns a non-null `read_at`. Update `SurfaceAssetsTest` when the runtime bytes change.
3. **`open-window --run-id` and `show-run` load the run into the renderer.** `WorkbenchRendererHost` must read `--run-id` (and `--agent-id`) and load that run into the Node renderer; `show-run --run-id=` on an open window must switch the renderer to that run. Test: the run marker is read from the renderer's state or served page (for example an HTTP request to the renderer, or a renderer state file it writes), not from the CLI's output. Show a second run and prove the renderer switched.
4. **Session rule and the renderer.** The Node renderer's `/store` calls must carry a session, or the renderer creates or uses the default session at launch, as the roadmap and docs say. Decide deliberately whether read-only help and version (`engine help`, `domengine version`, and `<family> help`) are exempt from the session rule; exempt them if that matches the roadmap and docs, document the decision, and record it on the scoreboard. Test: a page test that goes through the real `/store` path into `StoreCli` (no canned replies) on a fresh project, proving the pages load and that a glue open then save round-trips the real source text into the glue file with a session.
5. **Lease-gate the renderer's writes.** `store glue put`, `store var set`, and every other mutating call the renderer can make are refused with `error:` and `fix:` while another holder (agent or human) holds the `ui` or `both` lease for that session, and allowed for the holder. Test through the real `/store` path.
6. **`PolishRoadmapTest.warmPingIsUnderOneSecond` on Windows.** Pass the child JVM's arguments in an argument file (`java @file`), as R8 did for its cold child, so it no longer fails with `CreateProcess error=206`. Do not weaken the timing assertion or the test. Record the before and after on the scoreboard.
7. **Docs.** Correct the `Spine R5 fix-back 1.` release-note claims about `inject` and `input block` only as far as needed, and add a `Spine R5 fix-back 2.` paragraph describing exactly what is now implemented. Rewrite in place the stale rows of the `Deferred from the 3.0.0 consumer-fixes branch` table for the items R5 now delivers (6.5.2 Point, 6.5.3 typed card answers, 6.5.7 overlay read receipts, 6.6.4 runtime injection on attached windows, and any other row R5 delivered), stating where each now lives. Keep the `TypeScript package in the Workbench UI bundle` tech-debt section unchanged. Update `docs/pickleball-workbench.md`, `docs/dynamic-control-api.md`, `AGENTS.md`, and the guidance copies to match.
8. **Optional, if cheap.** Serve generated pages with a `Content-Security-Policy` that allows only same-origin requests (no external `connect-src`, `script-src`, `img-src`, `frame-src`), as a backstop to the `VerbCatalog.outsideCatalog` text scan. Test that the header is present on a generated page and absent on nothing that needs it. If it is not cheap, record it in tech-debt instead.

## Tests

- For each item, add a test that fails before your fix and passes after it. The test must exercise the production caller and cross the real process boundary where the item is about one: a separate CLI process talking to the JVM that holds the page or window. Not an in-process `WorkbenchAgentCommands.run` call, not a fake page registered with `PageSurfaces.manage` in the test JVM, not canned store replies.
- Real-browser tests use the locally installed Chrome or Edge on a local fixture page. No browser download.
- Before committing each fix, prove the new test fails without the fix (run it once before the production change) and say so in the scoreboard notes.
- Never weaken, skip, disable, or delete a test. No `@Disabled`, `assumeTrue` skips, or empty tests. Keep Gherkin syntax. Step annotations stay single string literals.
- Run targeted tests only: the new tests, the R5 and fix-back 1 tests that touch the code you change (`InjectLivePageTest`, `InputBlockLivePageTest`, `OverlayReceiptAskTest`, `OpenWindowRendererTest`, `SessionRequiredTest`, `WorkbenchPagesTest`, `WorkbenchUiServerTest`, `LeaseGateTest`, `LeaseAndBlockTest`, `GeneratedPageAccessTest`, `VerbCatalogTest`, `SurfaceAssetsTest`, `PolishRoadmapTest`, `GuidanceCommandGoldenTest`, `WorkbenchControlLeaseTest`), and the direct test of any other class you edit. Use `.\gradlew.bat :test --tests <class> --console=plain` and `.\gradlew.bat :pickleball-workbench:test --tests <class> --console=plain`.
- No full-suite run: no unfiltered `:test`, `:pickleball-workbench:test`, `:dom-engine:test`, `:pickleball-control-protocol:test`, `:pickleball-control-api:test`, and no consumer `@all`. The full regression is deferred per `docs/roadmaps/3.0.0-spine/final-regression.md`.
- Windows only (Dan's standing rule). No Linux or macOS runs. No GitHub Actions or workflow runs, no `workflow_dispatch`, no pull request to get CI. Append every new Windows-only test class to the `**Spine R5, Windows only:**` line in `docs/tech-debt.md`.

## Docs

- Remove each item's line from `## Spine R5 fix-back 2 (open)` when it closes. Remove the section when all are closed.
- Run `python scripts/sync_consumer_guidance.py` and `python scripts/refresh_agent_index.py`. Regenerate `docs/verb-inventory.md`, `docs/roadmaps/workbench-cli-inventory.json`, and the help goldens when a verb or help text changes.
- Add a `## Fix-back 2` section to `docs/maintainer/3.0.0-spine/R5-scoreboard.md` with one row per item: item number, status, commit SHA, targeted test classes with pass and fail counts, and a note that the new test failed before the fix. A row with any failing test is not `done`.

## Branch rules

- Work only in `C:\Users\Admin\IdeaProjects\pkb-spine` on branch `3.0.0-spine`.
- Push after every change. Before every push run, each on its own: `python scripts/sync_consumer_guidance.py`, `python scripts/refresh_agent_index.py`, `git fetch origin 3.0.0-spine`, `git rebase origin/3.0.0-spine`, `git push origin HEAD:3.0.0-spine`. If the rebase rewrites a recorded SHA, update the scoreboard in a new commit.
- Never push to `master` or `3.0.0-spine-r8`. Do not merge `3.0.0-spine-r8`. No tags, releases, publishing, or force-push. Never run `gradlew --stop`.
- Do no R6 or R8 work. Do not touch `SpineCli`, the R8 `spine start|stop|warm|sample` verbs, BiDi, browser ports, or the warm engine.
- Never touch the `C:\Users\Admin\IdeaProjects\pkb-spine-r8` or `C:\Users\Admin\IdeaProjects\pkb-spine-r7` folders.
- No secrets or tokens in cleartext in code, logs, pages, commits, or process arguments.
- If an item genuinely needs R6 or R8 code, close everything else and record that item on the scoreboard as `waiting on R6/R8 merge` with the reason.

## Shared Maven local repository

Before any `publishToMavenLocal` or `maven-consumer-project` `mvnw` run, check whether a java, gradle, or mvn process whose command line contains `pkb-spine-r8` is running `publishToMavenLocal` or `mvnw`. If one is, wait with `Start-Sleep 120` and re-check, up to 60 minutes, before you publish. Run your consumer tag tests right after your own `publishToMavenLocal`.

## Finish

After the last push, confirm the working tree is clean and `origin/3.0.0-spine` equals your HEAD. Then STOP and report the head commit SHA, the per-item scoreboard rows, and anything recorded as waiting on R6/R8.
