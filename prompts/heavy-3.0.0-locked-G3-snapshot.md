# Grok Heavy — 3.0.0 locked decisions, group G3: Snapshot & pre-map (rows 12, 14, 15, 16, 17)

Repository https://github.com/ds-code-t/pickleball, branch `3.0.0` ONLY. This prompt is fully standalone: do not rely on any earlier chat.

## Who decides
Dan (the owner) requires every locked decision in this group to end **100% implemented, with no excuses, no deferrals, no "optional" phases, and no "follow-up later"**. He delegates the design decisions to you (Heavy) together with the coordinator bot. Brainstorm briefly in chat, then decide and write the decisions down. Where the existing design doc already decides something, keep it unless the code shows it is wrong; if you change it, say why in the roadmap.

You plan **only group G3**. Three other Heavy sessions plan the other groups in parallel, and a separate engine-defects Build is pushing code to `3.0.0` right now. Stay inside group G3's owned files as listed in the groups plan.

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
- **Row 12** Scored label affinity: labels bind to interactables through a scored function (distance, alignment/reading order, role compatibility, text similarity, section co-membership, `for`/`aria-labelledby` evidence, negative-relation penalties) rather than a fixed rule. Explainable scores. Used by the pre-map and by runtime queries.
- **Row 14** Bounding boxes with configurable size caps (max area/width/height; caps keep page-sized containers from matching as targets) and the one-match rule: a query resolves to exactly one element or fails with an error listing candidates and why.
- **Row 15** Section detection with nesting: headings, ARIA landmarks, roles, forms, dialogs, tables, desktop panes/groups; nested sections in the pre-map.
- **Row 16** Universal composite snapshot on ALL 7 platforms (Chrome, Chromium, Edge via CDP; Firefox via BiDi; Windows UIA; macOS AX; Linux AT-SPI): screenshot + structure/page source + XPaths/paths + viewport mapping in ONE artifact. The snapshot coverage audit says no platform produces a combined artifact today.
- **Row 17** Pre-mapping as a CORE principle: snapshot production always pre-maps labels → interactables (via affinity), sections and nesting, boxes (with caps), viewport coordinates, XPath/path indexes, image and colour features, so runtime queries are light lookups. Mandatory, not a flag.

## Code to read before deciding (start here, follow references)
- `docs/maintainer/3.0.0-snapshot-coverage-audit.md` (what each platform produces today).
- `dom-engine/src/main/java/dev/domengine/desktop/DesktopTree.java`, `AtSpi.java`, `WindowsUia.java`, `MacAccessibility.java`, `NativeDesktop.java` (read; G2 owns `NativeDesktop` verbs).
- `dom-engine/src/main/java/dev/domengine/tree/DomNode.java`, `LiveTree.java`, `ContextWalker.java`; `image/TemplateMatch.java`; `query/Engines.java` relatives and `Look.java` (read; G2 owns them).
- `src/main/java/tools/dscode/common/seleniumextensions/CompositeSnapshotProducer.java`, `SnapshotProducers.java`; control-api `MappingSnapshot.java`, `NodeMapSnapshot.java`; protocol `ControlBridgeMappingSnapshot*.java`, `ControlBridgeBrowserScreenshot*.java`.
- Tests: `ElementSnapshotParityTest`, `ElementStateRoundTripTest`, `ElementSnapshotEvalTest`, `GeometryViewTest`, dom-engine desktop/tree tests.

## Contracts with other groups (from the groups plan, section 4)
- **You create (first phase, pass-through defaults so nobody blocks):** `dev.domengine.premap.PreMapLookup` (`current(sessionId, docVersion)`, `candidates(clauseKind, value)`), `dev.domengine.affinity.AffinityScorer` (`score(label, interactable, List<RelationTerm>)` with explainable parts), `dev.domengine.premap.OneMatch` (exactly one or an error listing candidates and why). G2's `Query` calls each from one place.
- **You own** the `CompositeSnapshot` JSON schema (document it in `docs/snapshot.md` or the location you decide). G1's MCP `snapshot` command returns it.
- **You consume:** G2's `dev.domengine.query.RelationTerm` (incl. negated relations); G1's `BrowserFacade` / `CdpAdapter` / `BidiAdapter` for browser screenshots, trees and frame data (no reflective `executeCdpCommand`); engine-defects D9 frame paths and D10 row states once on origin.
- Control-api snapshot types change additively only (no breaking change for consumers).

## Build waves for your group (from the groups plan, section 5)
- **W1 (now):** contract types with pass-through defaults, `CompositeSnapshot` model and schema, `SectionDetector`, `BoxCaps`, `OneMatch`, `AffinityScorer` real implementation over the existing offline tree, desktop screenshot capture and producers for UIA/AX/AT-SPI (your desktop files), pre-map builder.
- **W2 (after engine-defects Phase 4/5):** browser producers wired to live trees with frame paths and row states; Firefox producer via G1's `BidiAdapter` once it exists.
- **W3 (after G2's hook call sites land):** switch the hooks from pass-through to the real pre-map so queries become pre-map lookups, with tests proving the runtime path uses the pre-map (`QueryUsesPreMapTest`).

## Task
1. **Brainstorm and decide** (in chat, briefly) every open question for your items. Then record the decisions at the top of your roadmap (a "Decisions" section: per item the decision, approach, files, tests, docs).
2. **Write `docs/roadmaps/3.0.0-locked-G3-snapshot.md`**: a substantive multi-phase roadmap (typically 4–8 phases). Each phase lists: **Functionality** (concrete classes, methods, config keys, CLI verbs, wire methods), **Docs** (which files are rewritten in place and what they must say afterwards), **Tests** (named SHORT targeted test classes with exact `./gradlew --no-daemon :<module>:test --tests <fqcn>` commands; no full regression), **Acceptance** (observable, checkable), **Gate** (which engine-defects phase or other group's contract must be on `origin/3.0.0` first, per the groups plan waves), and the **CLI-first and logging checklist** items that apply. Include a scoreboard table (phase, status, SHA, CI run, notes). Mark which phases are W1 (can start now) versus later waves. No phase may be optional; every item in your group must be fully covered by some phase.
3. **Write `prompts/3.0.0-locked-G3-build.md`**: a fully standalone Build prompt for a fresh Grok Build session with no chat memory. It must contain: repo/branch; the exact push workflow; all guardrails below, verbatim in spirit; the owned-files list and the "never touch" list (other groups' files and engine-defects-held files until their gate); the decisions and full specs per phase (not just pointers); the interface contracts it must create or consume; the build waves and what to do at a gate it can't cross yet (finish everything possible, push, record "waiting on <gate>" in the scoreboard, stop); CLI-first verification steps (run the `domengine` CLI or root CLI directly and show output before wiring Workbench/MCP/Gradle); logging requirements (toggleable, per entity, date-stamped, size-capped, every protocol fallback logs a WARN once per run and reason); short targeted tests per phase; docs rewritten in place per phase; a monitoring note (never leave a Gradle/browser process hanging: use timeouts, kill process trees, record stalls); and the final report format (per phase: SHA, files, tests run with results, CI run).
4. **Push both files to `3.0.0`** and verify: `git fetch origin && git show origin/3.0.0:<path> | wc -c` for each; real content, no placeholders, no 0-byte files. If a push fails, paste the full markdown in chat instead. Never overwrite `AGENTS.md` or any other doc with a placeholder or shortened text. If you rename a contract type, update `docs/maintainer/3.0.0-locked-groups-plan.md` section 4 in the same push and say so.
5. **Reply** with the exact paths, byte sizes (from `origin/3.0.0`) and commit SHAs.

## Guardrails (put all of these into the Build prompt too)
- Branch `3.0.0` only. Never commit, push or merge to `master`, `2.2.0`, or `2.1.19`. No tags, releases or publishing. No force-push.
- Push after every change: `git fetch origin && git rebase origin/3.0.0`, then `git push origin HEAD:3.0.0`. Another Build pushes concurrently; resolve conflicts only in your own files, otherwise stop and report.
- Don't change existing Gherkin syntax. Don't weaken, skip, or delete tests. Cucumber annotations stay single string literals with identical values.
- Docs and `AGENTS.md` are rewritten **in place** to describe only current implemented behaviour. `AGENTS.md` is **not** append-only. Remove stale Rust and superseded wording; history goes to `docs/release-notes.md`. Never overwrite a doc with a placeholder.
- Before pushing docs: `python3 scripts/refresh_agent_index.py`, then `python3 scripts/refresh_agent_index.py --check` and `python3 scripts/sync_consumer_guidance.py --check` must pass. Never hand-edit `docs/agent/repository-index.md`.
- Short targeted tests per phase only. No full multi-module regression (that is the coordinator's integration gate). Never run `validate --all`, first-start timing, or Maven `@all`.
- Stay inside group G3's owned files per `docs/maintainer/3.0.0-locked-groups-plan.md`; touch other groups' behaviour only through the documented contracts; don't touch engine-defects-held files until the gate in the groups plan.
- The existing PageSurfaces sanitizing, labeling, undo and cleanup behaviour and its tests (`AgentLiveFixTest`, `LiveChromeCdpTest`, `SurfaceIdChild`, `DomAccessGuardTest`) are preserved through any migration to CDP/BiDi.
- Temporary files go in `.agent-work/` only.
- Every decision in the group is implemented 100%. "Truly blocked" means an external fact you can prove (e.g. no macOS runner available for a live test); then implement everything else, add the test so it runs where the platform exists, and record the proof. It never means skipping work.
