# Grok Build prompt: pickleball 3.0.0 spine R5

You are a fresh headless Grok Build session on Dan's Windows PC. You have not seen the planning chat. Implement R5 only, in full. R1 through R4 are on this branch. Do not redo them and do not start R6.

- **Repo:** https://github.com/ds-code-t/pickleball
- **Branch:** `3.0.0-spine` only. Never `master`, never a 2.x branch, never `3.0.0`. No tags, releases, publishing, or force-push.
- **Roadmap (the spec):** `docs/roadmaps/3.0.0-spine/R5-workbench-one-control-plane.md`
- **Design (decisions are closed):** `docs/plans/2026-11-25-spine-design.md`. Read **Distribution and licensing** before you package anything. Dan's approval of that section is required before this session, because P4 runs npm at build time and packages `node_modules` into the pickleball jar. If this prompt was started, that approval is the go-ahead for the packaging the design and the R5 roadmap describe. Do not switch to `frontend-maven-plugin`. Do not copy the Playwright Node binary into the jar. Do not shade `driver-bundle`.
- **Index:** `docs/roadmaps/3.0.0-spine/README.md`
- **Scoreboard:** `docs/maintainer/3.0.0-spine/R5-scoreboard.md`
- **Toolchain:** JDK 21. Windows commands. `.\gradlew.bat`. Do not use `./gradlew`. Never run `.\gradlew.bat --stop` or `./gradlew --stop`. `python`, not `python3`.

## Read first

1. `AGENTS.md` (Workbench isolation, agent surfaces, overlay, CLI-first). Pickleball may contain Workbench. Workbench must not contain Pickleball.
2. The design sections on the Node workbench, overlay, chat, and Distribution and licensing.
3. The R5 roadmap, every phase, including **Out of R5**.
4. `PickleballWorkbenchLauncher.java`, `PopupHost.java`, `PopupBrowserLocator.java`, `AgentEnvelope.java`, `PageSurfaces.java`, `SurfaceAssets.java`, `ChatLog.java`, `UiState.java`, `WorkbenchControlLease.java`, `HumanLock.java`, `VerbCatalog.java` (from R4).

The roadmap names the classes, verbs, files, and test classes. Use those names. Do not invent a parallel set. The exact `--tests` filters are in the roadmap. Run those commands. Each Gradle invocation stands alone.

## Hard rules (every phase)

- Every phase is mandatory and must be completed in full. Nothing is optional or deferred. The code tab is P4, not a later milestone. Do not start R6.
- Each phase runs targeted tests only, the Gradle command in that phase. Do not run consumer `@all`. Full regression is the last phase of R8, not here.
- A phase runs `@control-bridge` and `@step-override-bridge` with `-Dpkb_runvars.pkb_parallel=80` only when that phase changes bridge payloads. R5's window is a renderer over existing verbs. It does not change the bridge payload unless a test proves it must. Prefer the in-process CLI tests named in the roadmap.
- Testing is on Windows only. Append any new Windows-only class to the Linux and macOS section of `docs/tech-debt.md`. Do not delete that section. Do not run Linux or macOS.
- Never weaken, skip, or delete a test. Keep Gherkin syntax. Step annotations stay single string literals. Tests that constructed `WorkbenchFrame` move their assertions to `VerbDispatch`. That is a replacement, not a deleted assertion.
- Workbench isolation. Do not add `implementation project(':')`, a Pickleball Maven dependency, or core shading to `pickleball-workbench`. The Node UI is not compiled into the workbench jar. Assets live at `META-INF/pickleball/workbench-ui/` inside the pickleball jar. The nested workbench jar stays free of pickleball classes and free of `node_modules`. The Workbench process does not open SQLite and does not spawn Node. `PickleballWorkbenchLauncher` verb `ui` spawns Node. Tests drive verbs headless and do not start the window.
- npm runs only inside the Gradle task `workbenchUi`, at build time, using the driver-bundle Node (`PlaywrightNode.locate`) against a checksum-pinned npm CLI tarball in the Gradle cache. The tarball is not committed and not packaged. Then `npm ci --omit=dev` from the committed lockfile. Registry override `pkb_npm_registry` is `npm --registry`. Allowed licenses: MIT, Apache-2.0, BSD-2-Clause, BSD-3-Clause, ISC. A bad checksum or a disallowed license fails the build with `error:` and `fix:`. P1 may skip npm when the lockfile would be empty, because the server is `node:http` and `node:fs`. P4 must run npm. Product packages are only `monaco-editor` (MIT) and `typescript` (Apache-2.0). Their transitive packages are allowed only when the license is on that list. No React, no Electron, no `tsx`, no `axe-core`, no Express.
- The end user never runs npm, never needs Node on PATH, and downloads nothing at runtime for the UI. `PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1` stays. Do not download a browser.
- No secrets in cleartext in rows, logs, pages, or commits.
- Docs describe only the behavior this phase implemented. Rewrite them in place. History goes in `docs/release-notes.md`. Do not replace `AGENTS.md` wholesale. Remove a tech-debt row only in the phase that implements it (6.6.4 in P2; 6.5.1, 6.5.2, 6.5.3, 6.5.4, 6.5.6, 6.5.7 in P3).
- Before every push, each command on its own:

```
python scripts/sync_consumer_guidance.py
python scripts/refresh_agent_index.py
```

If surface-asset bytes change, update the hash assertion in `tools.dscode.browser.SurfaceAssetsTest`. P2 is the phase that is expected to do that when overlay markup bytes change.

- A new verb updates, in the same commit: the CLI help, `docs/roadmaps/workbench-cli-inventory.json`, `docs/verb-inventory.md`, and `documented-help.txt`.
- Record the implementation SHA, the test command, and the pass count on `docs/maintainer/3.0.0-spine/R5-scoreboard.md` after each green phase, in the same commit as the code or in the immediately following commit before the push.
- Fetch and rebase before every push. Each git command stands alone. Do not chain with `&&`, `;`, or a pipe. Never force-push. Never push to `master`.

```
git fetch origin 3.0.0-spine
git rebase origin/3.0.0-spine
git push origin HEAD:3.0.0-spine
```

- Stop only on an honest test failure you cannot fix without weakening a test, or on a hard-rule violation. Report what you ran and what failed. Do not mark a phase done when its tests were not run.

## CLI-first and logging

Every panel action is a CLI verb already in `VerbCatalog`, or a verb this roadmap adds to that catalog in the same phase. The Node server does not invent a private command channel. `VerbCatalogTest` scans `workbench-ui/` and fails a fetch or HTTP path that is not a catalog verb. Static page paths that are allowed are `/`, `/scenarios`, `/globals`, `/data`, `/sql`. Log the renderer with `EngineLog` capability `host`, entity `ui`. Log inject with `BrowserLog` entity `facade`. Off unless the capability or entity is named. Date-stamped, size-capped. Do not add a new logger class.

## Phases

Implement P1, then P2, then P3, then P4, then P5. The roadmap is the acceptance criteria.

- **P1.** `workbench-ui/server.mjs` and pages. Verb `ui`. Loopback `127.0.0.1`, ephemeral port, `.pickleball/workbench-ui/host.json`, 128-bit token, Host check like `PopupHost`. Window is `PopupBrowserLocator` (system Chrome or Edge), side by side with the page under test. No Chromium writes `state=closed` and the CLI still works. Tab order: Scenarios/Tests, Globals, Data, Raw SQL. Branch selector calls `store sandbox list` and `store sandbox use`. It does not fork. `store sql --query` is one `SELECT` only; anything else exits 2. `re:` is filtered in Java. Tests do not call `PopupBrowserLocator`. Remove `WorkbenchFrame` in this phase once the same verbs are covered.
- **P2.** Default `inject=off` (clean). `inject on --session` installs the sibling overlay. `inject off` removes it. Default `--mutate` is `sibling`. `--mutate=dom` stays the direct-DOM path for highlight only. External state stays in `UiState` and `ChatLog`. `spine session new`. `--agent` default `agent-1`. Layer id is the agent id, both ids in `AgentEnvelope.state`. Do not add a DOM or network mutation listener. Verbs `inject` and `spine session`.
- **P3.** Chat exists from `spine session new`, before any scenario. Channel default is the surface session id. `pkb_chat_channel=run` restores the single run channel. `chat send --ref --action`. `point`. `ask --type=text|choice|confirm`. `progress --pct` is 0 through 100. Overlay `read_at`; `overlay-poll` returns it. `chat --follow` is the stream. `chat list` is one-shot.
- **P4.** `monaco-editor` and `typescript`. Commit `package-lock.json`. `workbenchUi` runs npm. Edits call `store glue put`. Node does not write the file. A failed put shows the CLI `error` and `fix`. Update `THIRD-PARTY-NOTICES.md` with both packages, their licenses, and the jar path. `WorkbenchUiLicenseTest` fails if `node_modules` is inside the workbench jar.
- **P5.** `lease take --target=ui|browser|both` and `lease release` call the existing lease classes. A second holder is refused. `input block --on` fails when the session is clean (`fix: inject on`). `ui page` writes `.pickleball/workbench-ui/generated/<session>/<name>.html`, served only for that session token, deleted when the session ends. `VerbCatalogTest` fails generated HTML that fetches a non-catalog URL.

## Out of R5

Do not add dynamic-mode forks, a promote button beyond the R1 verb, snapshot retention, the warm classloader, or a browser CDN download. The branch selector may call `store sandbox use`. It does not fork.

## Finish

You are done when P5 is green, the scoreboard has five SHAs, and the branch is pushed. Reply with the scoreboard table and the tip SHA.
