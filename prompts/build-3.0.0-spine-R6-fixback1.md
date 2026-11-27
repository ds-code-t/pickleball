# Build prompt: 3.0.0 spine R6 fix-back 1 (real page controls, discard on exit)

This prompt stands alone. You are a fresh headless Grok Build session on Dan's Windows PC. You have not seen the planning chat. R6 (P0 to P3) is on `3.0.0-spine` at `f184dd41` and was verified against the pushed code. The CLI side passed. Two page-side items did not: they are proven only by grepping `workbench-ui/pages/index.html` for strings, and one of them cannot work in a current browser. Close those two items, then stop. Do nothing else.

- **Repo:** https://github.com/ds-code-t/pickleball
- **Branch:** `3.0.0-spine` only. Never `master`, never `3.0.0`, never `3.0.0-spine-r8`, never a 2.x branch. No tags, releases, publishing, or force-push.
- **Worktree:** `C:\Users\Admin\IdeaProjects\pkb-spine`
- **Toolchain:** JDK 21. Windows commands. `.\gradlew.bat`. Never run `.\gradlew.bat --stop` or `./gradlew --stop`. Use `python`, not `python3`.

## Read first

The branch docs win over this prompt if they disagree. If they do, follow the docs and say so on the scoreboard.

1. `AGENTS.md`.
2. `docs/roadmaps/3.0.0-spine/R6-dynamic-mode-branching-promote.md` (P1: "The shell calls `discard` on exit when `store delta list` shows only the fork delta"; P2 Map tab; P3 promote control that will not submit until `clear` or `archive` is chosen; the branch tree is drawn as a collapsible list).
3. `docs/maintainer/3.0.0-spine/R6-scoreboard.md` and `prompts/build-3.0.0-spine-R6.md`.
4. `docs/roadmaps/3.0.0-spine/final-regression.md` and `docs/tech-debt.md`.
5. `workbench-ui/pages/index.html`, `workbench-ui/server.mjs`, `WorkbenchUiServer.java` (the `/store` bridge, `rendererMutation`, `LeaseHold.require`), `StoreBranch.java`.
6. `src/test/java/tools/dscode/launcher/OverlayAckLiveTest.java` and `RendererShownRunTest.java`: the existing pattern for driving a real browser against a real page. Use that same mechanism. Do not add a new browser dependency or download.

## Items

Each item needs a test that fails before the fix and passes after. The test must go through the production caller: a real browser page served by the real Node renderer (`workbench-ui/server.mjs`) talking to the real `WorkbenchUiServer` `/store` bridge and `VerbDispatch`, with a spine session, the way the renderer runs in production. Grepping the page source, or posting to `/store` yourself with the arguments the page would send, does not count. Record the before failure on the scoreboard.

1. **Discard on exit actually works.** The current `pagehide` handler in `index.html` sends two synchronous `XMLHttpRequest`s (`store delta list`, then `store branch discard`). Chromium and WebKit block synchronous XHR during page dismissal (`beforeunload`, `unload`, `pagehide`, `visibilitychange`), so in a real browser the empty fork is never discarded. Fix it with a path that works in a current browser. Pick one and record the choice on the scoreboard:
   - a single `fetch(..., {keepalive: true})` (it can carry the `x-pkb-ui-token` header) that posts `store branch discard --name=<fork>`, relying on the verb's existing refusal of a non-empty fork without `--force`; or
   - a server-side hook: the renderer host or the Node server, when the page or the renderer exits, runs the same `store branch discard` check for the fork the page created.
   Either way the decision stays "discard only when the fork has only its `fork` delta", and a non-empty fork is never discarded. Test (real browser): open the page, click the fork control on a finished run, close or navigate away from the page, and assert the fork sandbox is gone. Second case: fork, add a second delta (for example a `map set` or a `store file put` on the fork), close the page, and assert the fork is still there.
2. **The other R6 page controls run in a real page.** In the same real-browser harness, drive the actual controls and assert the store result, not the page text:
   - Fork control (`id="fork"`): clicking it creates the fork sandbox, and the title and accent change to the fork name and its `colors.json` color.
   - Map tab: `map show` renders `boundRun`/`boundStep` for the selected run; `map set` writes one `kind=map` delta on the fork (check with `store delta list --sandbox=<fork>`), and is refused while another holder has the `ui` lease (the lease gate in `WorkbenchUiServer`).
   - Promote control (`id="promote"`): with no `clear`/`archive` chosen it sends nothing (no promote delta on the parent); with `clear` chosen it promotes and the parent head changes.
   - Branch tree (`id="tree"`): renders one collapsible entry per fork that touched the file, from the verb's rows.
   Fix anything in `index.html` or the bridge that this exposes (for example the `"engine"` verb route for `map show`/`map set`).

## Rules

- Windows only (Dan's standing rule). No Linux or macOS runs. No GitHub Actions or workflow runs, no pull request for CI. Add any new Windows-only class to the `**Spine R6, Windows only:**` line in the Linux and macOS section of `docs/tech-debt.md`.
- Targeted tests only: the new test classes plus `BranchColorTest`, `BranchForkTest`, `PreStepHookTest`, `BranchTreeTest`, `RendererStoreSessionTest`, `GeneratedPageAccessTest`, `WorkbenchPagesTest`, and `SurfaceAssetsTest` if surface bytes change. No full regression (deferred per `final-regression.md`). No unfiltered `:test`.
- Never weaken, skip, disable, or delete a test. No `@Disabled`, `assumeTrue` skips, or empty tests. Keep Gherkin syntax. Step annotations stay single string literals.
- Workbench isolation holds. No new npm packages. No browser download.
- Docs describe only what is implemented: rewrite `docs/pickleball-workbench.md` (dynamic mode, discard on exit) and the R6 release-note paragraph in place.
- Add a tech-debt section `Spine R6 fix-back 1 (open)` with one line per item in the first commit and remove each line as it closes.
- Add a `Fix-back 1` section to `docs/maintainer/3.0.0-spine/R6-scoreboard.md` with a SHA, the before failure, and pass counts per item.
- Push after every change. Before every push, each command on its own (do not chain with `&&`, `;`, or a pipe):

```
python scripts/sync_consumer_guidance.py
python scripts/refresh_agent_index.py
git fetch origin 3.0.0-spine
git rebase origin/3.0.0-spine
git push origin HEAD:3.0.0-spine
```

- `python scripts/verify_agent_contract.py` exits 0 today. Keep it at 0.
- Never touch `C:\Users\Admin\IdeaProjects\pkb-spine-r8` or `pkb-spine-r7`. Do not change R8 code, the session allow-list, or the CLI verbs except where an item requires it. Do not start the final regression.
- Stop only on an honest test failure you cannot fix without weakening a test, or on a hard-rule violation, and report it.

## Finish

You are done when both items are green, the tech-debt section is gone, the scoreboard has the Fix-back 1 rows, the working tree is clean, and `origin/3.0.0-spine` equals your HEAD. Then STOP and report the head commit SHA and the Fix-back 1 table.
