# Build Prompt: Pickleball 3.0.0 Visual Browser Components

You are implementing visual in-page browser components on branch `3.0.0-visual-components` (cut from `3.0.0` at `fa19763d`). VERSION stays `3.0.0`. Never touch master, 3.0.0, 2.2.0, 2.1.19. Do not open a PR. Do not merge. Push only to `3.0.0-visual-components`. No tags, releases, publishing, force-push. Keep the branch merge-friendly: before the final phase, fetch and merge `origin/3.0.0` into the feature branch if it moved. Never `./gradlew --stop`. No secrets in cleartext. Don't touch dom-composite PRs #2 and #3.

## Read first, in order (on the branch tip)
1. AGENTS.md (Execution engine / "One spine, multiple adapters"; "Agent surfaces"; Release checklist)
2. docs/agent/README.md, change-checklist.md, feature-map.md (row "Agent browser control"), repository-index.md
3. docs/dynamic-control-api.md (agent commands, overlay/highlight/html contract, logging), docs/direct-control-handoff.md
4. docs/maintainer/3.0.0-locked-decisions-design.md section B; 2.1.19-workbench-expansion-roadmap.md; docs/roadmaps/3.0.0-locked-G4-surfaces-dalton-rust.md
5. docs/roadmaps/3.0.0-workbench-edit-roadmap.md (format: mandatory phases, scoreboard table, CLI-first and logging checklist)
6. docs/roadmaps/3.0.0-visual-components-roadmap.md (this work)
7. docs/release-notes.md

## Dan's ask (scope is decided; no long brainstorm)
Implement as visual in-page browser components:
1. arrows
2. numbered steps (visual badges on the page, NOT Gherkin editing)
3. magnifier
4. spotlight (dim page, cut out the target)
5. a standalone border component (beyond highlight presets)
6. a label command
7. overlay width and height: accept and store w,h; boxes over resolved targets use the resolved element's bounds.

Plus partial gaps where reasonable: query-resolved bounds used the same way on CDP and native paths; label and border as first-class kinds; `PageSurfaces.highlight` ignores its x,y,width,height args; remembered geometry keeps only a point `rememberGeometry(handle,x,y)`); overlay is one fixed callout style. Also fix the stray merge marker at docs/dynamic-control-api.md line 121 `>>>>>> d5f4d52f`).

## Existing architecture (verify against the repo)
- `AgentControl.execute` is the one controller. `AgentSurfaceRouter.route` runs first and sends an op to the page, the Chromium popup (`agentsurface` package), or native (`NativeHighlightBox`).
- CLI verbs: `WorkbenchCommandLine.AGENT_CORE_COMMANDS`, usage in `WorkbenchCliHelp`, inventory docs/roadmaps/workbench-cli-inventory.json. Today: highlight/unhighlight, overlay/overlay-poll/overlay-remove, html-write/replace/insert/remove/cleanup, popup-*, chat-*. MCP `McpRegistry`, `workbench_agent_command`, `surfaces`), POST /v1/agent/command and Workbench only forward.
- In-page code: `PageSurfaces` (place/remove/cleanup, presetCss, tryHighlightNode via CDP `Overlay.highlightNode`), resources tools/dscode/browser/surface/ live-helper.js, overlay.html, overlay.css; `SurfaceAssets` kinds are only html, overlay, highlight(style). `CompositeWebElement` binds a ranked handle and remembers visual-viewport geometry.
- Injected nodes carry `data-pkb-agent=agent_injected` plus a sentence; a parser strips scripts/on* handlers/javascript: URLs; ids pkb-N under a file lock; placements ledger .pickleball/runs/<run-id>/agent/placements.json; undo outside the document; html-cleanup and scenario end remove everything; `--target=auto|page|popup|native`, `--pane`.
- Logging: `pkb_composite_log` entities (overlay, highlight, html...), off unless named, date-stamped, size-capped.
- Tests to extend: AgentExpansionTest, AgentLiveFixTest, AgentForwardTest, PopupSurfaceParityTest, SurfaceAssetsTest, NativeHighlightBoxTest, NativeHighlightRouteTest, LiveChromeCdpTest, WorkbenchMcpServerTest. Targeted only: `timeout 600 ./gradlew --no-daemon <module>:cleanTest <module>:test --tests <filter>`.

## Constraints
- One spine, multiple adapters; CLI-first: every component reachable from the CLI; MCP/Workbench/popup/Gradle only forward, no business logic.
- Same commands on Windows/macOS/Linux and across in-page/CDP, popup and native paths where practical; only required platform differences, documented.
- Injected content stays labeled agent_injected, scripts stripped, removable by remove/cleanup/undo and scenario end.
- Toggleable per-entity date-stamped size-capped logging for every new kind.
- Gherkin syntax unchanged; step annotations stay single string literals; never weaken, skip or delete tests.
- No full regression, validate --all, first-start timing, or Maven @all; targeted tests per phase only.
- Push only to 3.0.0-visual-components. Do NOT open a PR. Do NOT merge into 3.0.0 or any branch; the coordinator merges into 3.0.0 after verification. Never push master, 3.0.0, 2.2.0, 2.1.19. No tags, releases, publishing, force-push. Keep the branch merge-friendly: before the final phase, fetch and merge origin/3.0.0 into the feature branch if it moved.
- Don't touch dom-composite PRs #2 and #3. Never `./gradlew --stop`. No secrets in cleartext.

## CLI-first and logging checklist (copy into roadmap and every reply)
- [ ] Every capability is reachable from the CLI.
- [ ] Every non-CLI surface (Workbench, MCP, popup, HTTP, Gradle) only forwards.
- [ ] Toggleable, per-entity, date-stamped, size-capped logging covers new or changed code.

## Rules
- Do not stop early. Do not skip phases. Nothing is optional or deferred.
- Stop only on honest failure or a hard-rule violation.
- Every reply ends with a scoreboard.
- Follow the phases in docs/roadmaps/3.0.0-visual-components-roadmap.md exactly.
- All phases are mandatory.

Begin with Phase 1.
