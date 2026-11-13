# Grok Heavy — 3.0.0 locked decisions, group G1: Protocol & facade boundary (rows 1–5 + addendum A)

Repository https://github.com/ds-code-t/pickleball, branch `3.0.0` ONLY. This prompt is fully standalone: do not rely on any earlier chat.

## Who decides
Dan (the owner) requires every locked decision in this group to end **100% implemented, with no excuses, no deferrals, no "optional" phases, and no "follow-up later"**. He delegates the design decisions to you (Heavy) together with the coordinator bot. Brainstorm briefly in chat, then decide and write the decisions down. Where the existing design doc already decides something, keep it unless the code shows it is wrong; if you change it, say why in the roadmap.

You plan **only group G1**. Three other Heavy sessions plan the other groups in parallel, and a separate engine-defects Build is pushing code to `3.0.0` right now. Stay inside group G1's owned files as listed in the groups plan.

## Read first (on branch 3.0.0, from the repository, not from memory)
- `AGENTS.md` (rules, test policy, documentation policy, build and validation).
- `docs/maintainer/locked-decisions-digest-2026-11-13.md` (the 22 locked decisions).
- `docs/maintainer/3.0.0-locked-decisions-sweep.md` (verdict per row, stale doc lines).
- `docs/maintainer/3.0.0-locked-decisions-design.md` (per-row decisions, owner addendum A and B).
- `docs/maintainer/3.0.0-locked-groups-plan.md` (**binding**: group membership, owned files, coupling matrix, interface contracts, build waves, shared merge rule).
- `docs/maintainer/3.0.0-engine-defects-design.md` and `docs/roadmaps/3.0.0-engine-defects-roadmap.md` (running now; don't duplicate its items: XPath via `document.evaluate`, unsupported-flag reporting, loud fallback log, engine lookup order, page-tab attach, native suite hooks, live-Chrome flake, row states, Workbench classpath, live CI gating; build on their outcome).
- `docs/maintainer/3.0.0-snapshot-coverage-audit.md`, `docs/maintainer/3.0.0-query-visual-design.md`, `docs/maintainer/3.0.0-dalton-design.md`, `docs/configuration.md`, `docs/direct-control-handoff.md`, `docs/dynamic-control-api.md`, `docs/pickleball-workbench.md`, `docs/custom-element-definitions.md`, `docs/dynamic-steps.md` as relevant.
- The real code listed below for your group. Read it before deciding. When code and docs disagree, the code wins; record the difference.

## Your group's items
- **Row 1** Single browser facade over Selenium, Playwright and raw CDP, with wide version ranges. Today: no facade; Selenium strictly pinned `[4.44,5.0)` (`build.gradle` ~195); no Playwright.
- **Row 2** Libraries are the default for scripted runs; direct protocols (CDP/BiDi) are for agents and narrow gaps; every fallback logs a warning. Today this is **reversed**: `pkb_composite=auto` makes the Java engine over CDP the scripted element path.
- **Row 3** Java owns agent protocol control behind a separate agent-only boundary (`AgentProtocolController` behind MCP; scripted `ElementOperations` never call direct protocols).
- **Row 4** WebDriver BiDi is the Firefox protocol path. Today: none.
- **Row 5** MCP server is the single agent entry point with predefined, parameterized commands (query, click, type, snapshot, observe, launch, surfaces, ...). Today MCP is one of several entries (control API, CLI, Workbench).
- **Addendum A** PageSurfaces (html-write/replace/insert/overlay/remove/cleanup and overlay event capture) move to direct CDP by default on Chromium (`Page.createIsolatedWorld`, `Page.addScriptToEvaluateOnNewDocument`, `Runtime.addBinding` for push events with no polling, `DOM.*` for structural writes) and WebDriver BiDi on Firefox (`script.addPreloadScript`, sandboxed `script.callFunction`, channels). Selenium `executeScript` only as a logged last-resort fallback. Keep every existing sanitizing, labeling, undo and cleanup behaviour and test.
- Decide carefully how row 2 (libraries default) coexists with the shipped Java engine: what the scripted default element path becomes, what `pkb_composite` values mean afterwards, which "narrow gaps" may use direct protocols in scripted runs (each with a WARN), and how existing consumers keep working without Gherkin changes.

## Code to read before deciding (start here, follow references)
- `src/main/java/tools/dscode/common/agentcontrol/PageSurfaces.java` (2362 lines; reflective `executeCdpCommand` at ~925–934, `executeScript` at ~709 and ~1445, `CompositeRuntime.focusPage` at ~243/2246), `ObserveEvents.java`, `AgentControl.java`, `AgentLaunch.java`, `AgentDriverHooks.java`, `WindowRegistry.java`.
- `src/main/java/tools/dscode/common/composite/CompositeRuntime.java`, `CompositeMode.java`, `ElementOperations.java`, `DomCompositeAdapter.java`, `CompositeWebElement.java`.
- `src/main/java/tools/dscode/common/driver/DriverConstruction.java`, `build.gradle` (Selenium block, ~195 and ~833).
- `dom-engine/src/main/java/dev/domengine/mcp/McpServer.java`, `dom-engine/.../cdp/CdpConnection.java`, `dom-engine/.../session/BrowserSession.java`.
- `pickleball-workbench/src/main/java/tools/dscode/workbench/mcp/WorkbenchMcpServer.java`, `WorkbenchMcpTools.java`, `WorkbenchAttachServer.java`, `WorkbenchCommandQueue.java`.
- `pickleball-control-protocol/.../DirectControlProvider.java`, `pickleball-control-api/.../DynamicControl.java`.
- Tests: `src/test/java/tools/dscode/common/agentcontrol/*`, `src/test/java/tools/dscode/common/composite/DomAccessGuardTest.java`, `pickleball-workbench/src/test/.../mcp/*`.

## Contracts with other groups (from the groups plan, section 4)
- **You create (W1, first commits):** `tools.dscode.browser` facade: `BrowserFacade`, `ProtocolSelector.forDriver(...)`, `CdpAdapter`, `BidiAdapter`, Selenium fallback, with `send`, `evaluateIsolated`, `addPreload`, `addBinding`, `screenshot`, `frameTree`. G3 producers and G4's popup will call these.
- **You create:** the MCP command registry (`McpCommand`: name, JSON-schema params, handler) so G4 can add popup commands without editing your server classes, and the `snapshot` MCP command that returns G3's `CompositeSnapshot` JSON (G3 owns the schema).
- **You create:** one shared location and loader for the PageSurfaces HTML/CSS/JS assets so G4's popup renders the same surfaces.
- **You consume:** engine-defects' page-target pinning (`CompositeRuntime.focusPage` / `focusedCdp`) once its Phase 1 is on origin. Don't re-implement attach.

## Build waves for your group (from the groups plan, section 5)
- **W1 (now):** the new `tools.dscode.browser` package, adapters (Selenium, Playwright with a wide range, CDP over its own socket, BiDi), the MCP registry, the asset loader. Nothing in engine-defects-held files.
- **W3 (after engine-defects Phase 8 is verified):** libraries-default flip in `CompositeRuntime` / `ElementOperations` / `CompositeMode`, the agent-only boundary, the PageSurfaces migration to CDP isolated world / BiDi preload with Selenium WARN fallback, `DriverConstruction` protocol selection.
- G4's Rust removal waits for your W3 `CompositeRuntime` change. Land it in clean, small commits and record the SHA in your scoreboard.

## Task
1. **Brainstorm and decide** (in chat, briefly) every open question for your items. Then record the decisions at the top of your roadmap (a "Decisions" section: per item the decision, approach, files, tests, docs).
2. **Write `docs/roadmaps/3.0.0-locked-G1-protocol.md`**: a substantive multi-phase roadmap (typically 4–8 phases). Each phase lists: **Functionality** (concrete classes, methods, config keys, CLI verbs, wire methods), **Docs** (which files are rewritten in place and what they must say afterwards), **Tests** (named SHORT targeted test classes with exact `./gradlew --no-daemon :<module>:test --tests <fqcn>` commands; no full regression), **Acceptance** (observable, checkable), **Gate** (which engine-defects phase or other group's contract must be on `origin/3.0.0` first, per the groups plan waves), and the **CLI-first and logging checklist** items that apply. Include a scoreboard table (phase, status, SHA, CI run, notes). Mark which phases are W1 (can start now) versus later waves. No phase may be optional; every item in your group must be fully covered by some phase.
3. **Write `prompts/3.0.0-locked-G1-build.md`**: a fully standalone Build prompt for a fresh Grok Build session with no chat memory. It must contain: repo/branch; the exact push workflow; all guardrails below, verbatim in spirit; the owned-files list and the "never touch" list (other groups' files and engine-defects-held files until their gate); the decisions and full specs per phase (not just pointers); the interface contracts it must create or consume; the build waves and what to do at a gate it can't cross yet (finish everything possible, push, record "waiting on <gate>" in the scoreboard, stop); CLI-first verification steps (run the `domengine` CLI or root CLI directly and show output before wiring Workbench/MCP/Gradle); logging requirements (toggleable, per entity, date-stamped, size-capped, every protocol fallback logs a WARN once per run and reason); short targeted tests per phase; docs rewritten in place per phase; a monitoring note (never leave a Gradle/browser process hanging: use timeouts, kill process trees, record stalls); and the final report format (per phase: SHA, files, tests run with results, CI run).
4. **Push both files to `3.0.0`** and verify: `git fetch origin && git show origin/3.0.0:<path> | wc -c` for each; real content, no placeholders, no 0-byte files. If a push fails, paste the full markdown in chat instead. Never overwrite `AGENTS.md` or any other doc with a placeholder or shortened text. If you rename a contract type, update `docs/maintainer/3.0.0-locked-groups-plan.md` section 4 in the same push and say so.
5. **Reply** with the exact paths, byte sizes (from `origin/3.0.0`) and commit SHAs.

## Guardrails (put all of these into the Build prompt too)
- Branch `3.0.0` only. Never commit, push or merge to `master`, `2.2.0`, or `2.1.19`. No tags, releases or publishing. No force-push.
- Push after every change: `git fetch origin && git rebase origin/3.0.0`, then `git push origin HEAD:3.0.0`. Another Build pushes concurrently; resolve conflicts only in your own files, otherwise stop and report.
- Don't change existing Gherkin syntax. Don't weaken, skip, or delete tests. Cucumber annotations stay single string literals with identical values.
- Docs and `AGENTS.md` are rewritten **in place** to describe only current implemented behaviour. `AGENTS.md` is **not** append-only. Remove stale Rust and superseded wording; history goes to `docs/release-notes.md`. Never overwrite a doc with a placeholder.
- Before pushing docs: `python3 scripts/refresh_agent_index.py`, then `python3 scripts/refresh_agent_index.py --check` and `python3 scripts/sync_consumer_guidance.py --check` must pass. Never hand-edit `docs/agent/repository-index.md`.
- Short targeted tests per phase only. No full multi-module regression (that is the coordinator's integration gate). Never run `validate --all`, first-start timing, or Maven `@all`.
- Stay inside group G1's owned files per `docs/maintainer/3.0.0-locked-groups-plan.md`; touch other groups' behaviour only through the documented contracts; don't touch engine-defects-held files until the gate in the groups plan.
- The existing PageSurfaces sanitizing, labeling, undo and cleanup behaviour and its tests (`AgentLiveFixTest`, `LiveChromeCdpTest`, `SurfaceIdChild`, `DomAccessGuardTest`) are preserved through any migration to CDP/BiDi.
- Temporary files go in `.agent-work/` only.
- Every decision in the group is implemented 100%. "Truly blocked" means an external fact you can prove (e.g. no macOS runner available for a live test); then implement everything else, add the test so it runs where the platform exists, and record the proof. It never means skipping work.
