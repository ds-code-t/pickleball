# Grok Heavy — 3.0.0 locked decisions, group G2: Query language & locators (rows 6, 7, 8, 9, 10, 11, 13)

Repository https://github.com/ds-code-t/pickleball, branch `3.0.0` ONLY. This prompt is fully standalone: do not rely on any earlier chat.

## Who decides
Dan (the owner) requires every locked decision in this group to end **100% implemented, with no excuses, no deferrals, no "optional" phases, and no "follow-up later"**. He delegates the design decisions to you (Heavy) together with the coordinator bot. Brainstorm briefly in chat, then decide and write the decisions down. Where the existing design doc already decides something, keep it unless the code shows it is wrong; if you change it, say why in the roadmap.

You plan **only group G2**. Three other Heavy sessions plan the other groups in parallel, and a separate engine-defects Build is pushing code to `3.0.0` right now. Stay inside group G2's owned files as listed in the groups plan.

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
- **Row 6** The engine is the "Control Engine" unifying browsers and Windows/macOS/Linux desktops. Today the Java dom-engine does this but docs call it "Java DOM engine". Decide naming (docs and capability output; package rename only if you decide it's worth it and it breaks nothing public).
- **Row 7** Layered architecture: syntax layer (query/Gherkin phrase parsing), dictionaries layer (category mappings → combined selectors), protocol adapters layer (G1 implements the adapters; you define and enforce the layer boundary in the query/dictionary code and docs).
- **Row 8** Unified Boolean query with capital AND/OR, parentheses (implemented in `Expressions.java`). Confirm with tests; fix any gap you find.
- **Row 9** XPath is one clause type among image, colour, CSS, accessibility, text and relatives. Old XPath still works but is deprecated (WARN once per definition). The dictionary emits the combined form first; XPath is the fallback. Today `SelectorTranslator` passes XPath through first.
- **Row 10** Universal syntax: the same syntax and verbs on browsers and desktops; platform differences only where physically required, reported through capabilities.
- **Row 11** Relative locators (near, above, below, left-of, right-of, inside, after, before). Confirm; add `inside`/`after`/`before` if missing.
- **Row 13** Negative relations: `not-near`, `not-inside`, `not-above`, ... (and `NOT near ...`) as filters or score penalties that feed scored affinity (G3 owns the scorer).

## Code to read before deciding (start here, follow references)
- `dom-engine/src/main/java/dev/domengine/query/Expressions.java` (338 lines), `Engines.java` (830), `Query.java` (427), `Look.java`, `HtmlTree.java`.
- `dom-engine/src/main/java/dev/domengine/desktop/NativeDesktop.java` (verb dispatch on desktop), `dom-engine/.../cli/DomEngineMain.java` (CLI surface; read only — engine-defects edits it until Phase 4).
- `src/main/java/tools/dscode/common/composite/SelectorTranslator.java`, `CompositeSelector.java`, `EngineQuery.java`, `LookFind.java`, `ElementPhrase.java`, `VerbPlan.java`.
- `src/main/java/tools/dscode/common/domoperations/ExecutionDictionary.java`, `NestedByLocator.java`.
- Tests: `dom-engine/src/test/.../query/*`, `src/test/.../composite/SelectorAndAdapterTest.java`, `ActionVerbPlanTest.java`, `EnginePositionGateTest.java`.

## Contracts with other groups (from the groups plan, section 4)
- **You create (first phase):** `dev.domengine.query.RelationTerm` (relation enum incl. `NEAR, ABOVE, BELOW, LEFT_OF, RIGHT_OF, INSIDE, AFTER, BEFORE`; `negated`; `anchor` sub-query; optional `weight`), parsed from positive and negative syntax, with parser tests. G3 consumes it.
- **You consume:** G3's `dev.domengine.premap.PreMapLookup`, `dev.domengine.affinity.AffinityScorer`, `dev.domengine.premap.OneMatch`. G3 ships pass-through defaults first; call each from exactly one place in `Query` resolution so G3 can switch them on without touching your files.
- **You consume:** engine-defects Phase 3 (XPath through `document.evaluate`) and Phase 4 (flags, frames, adapter gating). Don't redo them; build the clause model on top.
- G1 owns the protocol adapters; you document the layer boundary and keep the query/dictionary layers free of protocol calls.

## Build waves for your group (from the groups plan, section 5)
- **W1 (now):** you may plan and write tests-first scaffolding only in new files (e.g. the `RelationTerm` record and its parser tests in new classes). Don't edit `Engines.java` XPath code, `SelectorTranslator.java`, or `DomCompositeAdapter.java` yet.
- **W2 (after engine-defects Phase 4 is pushed and verified):** the full G2 work in `query/*`, `SelectorTranslator`, `ExecutionDictionary`, `NativeDesktop`.
- G3's "queries prefer pre-map" lands after your hook call sites exist; land them early in W2.

## Task
1. **Brainstorm and decide** (in chat, briefly) every open question for your items. Then record the decisions at the top of your roadmap (a "Decisions" section: per item the decision, approach, files, tests, docs).
2. **Write `docs/roadmaps/3.0.0-locked-G2-query.md`**: a substantive multi-phase roadmap (typically 4–8 phases). Each phase lists: **Functionality** (concrete classes, methods, config keys, CLI verbs, wire methods), **Docs** (which files are rewritten in place and what they must say afterwards), **Tests** (named SHORT targeted test classes with exact `./gradlew --no-daemon :<module>:test --tests <fqcn>` commands; no full regression), **Acceptance** (observable, checkable), **Gate** (which engine-defects phase or other group's contract must be on `origin/3.0.0` first, per the groups plan waves), and the **CLI-first and logging checklist** items that apply. Include a scoreboard table (phase, status, SHA, CI run, notes). Mark which phases are W1 (can start now) versus later waves. No phase may be optional; every item in your group must be fully covered by some phase.
3. **Write `prompts/3.0.0-locked-G2-build.md`**: a fully standalone Build prompt for a fresh Grok Build session with no chat memory. It must contain: repo/branch; the exact push workflow; all guardrails below, verbatim in spirit; the owned-files list and the "never touch" list (other groups' files and engine-defects-held files until their gate); the decisions and full specs per phase (not just pointers); the interface contracts it must create or consume; the build waves and what to do at a gate it can't cross yet (finish everything possible, push, record "waiting on <gate>" in the scoreboard, stop); CLI-first verification steps (run the `domengine` CLI or root CLI directly and show output before wiring Workbench/MCP/Gradle); logging requirements (toggleable, per entity, date-stamped, size-capped, every protocol fallback logs a WARN once per run and reason); short targeted tests per phase; docs rewritten in place per phase; a monitoring note (never leave a Gradle/browser process hanging: use timeouts, kill process trees, record stalls); and the final report format (per phase: SHA, files, tests run with results, CI run).
4. **Push both files to `3.0.0`** and verify: `git fetch origin && git show origin/3.0.0:<path> | wc -c` for each; real content, no placeholders, no 0-byte files. If a push fails, paste the full markdown in chat instead. Never overwrite `AGENTS.md` or any other doc with a placeholder or shortened text. If you rename a contract type, update `docs/maintainer/3.0.0-locked-groups-plan.md` section 4 in the same push and say so.
5. **Reply** with the exact paths, byte sizes (from `origin/3.0.0`) and commit SHAs.

## Guardrails (put all of these into the Build prompt too)
- Branch `3.0.0` only. Never commit, push or merge to `master`, `2.2.0`, or `2.1.19`. No tags, releases or publishing. No force-push.
- Push after every change: `git fetch origin && git rebase origin/3.0.0`, then `git push origin HEAD:3.0.0`. Another Build pushes concurrently; resolve conflicts only in your own files, otherwise stop and report.
- Don't change existing Gherkin syntax. Don't weaken, skip, or delete tests. Cucumber annotations stay single string literals with identical values.
- Docs and `AGENTS.md` are rewritten **in place** to describe only current implemented behaviour. `AGENTS.md` is **not** append-only. Remove stale Rust and superseded wording; history goes to `docs/release-notes.md`. Never overwrite a doc with a placeholder.
- Before pushing docs: `python3 scripts/refresh_agent_index.py`, then `python3 scripts/refresh_agent_index.py --check` and `python3 scripts/sync_consumer_guidance.py --check` must pass. Never hand-edit `docs/agent/repository-index.md`.
- Short targeted tests per phase only. No full multi-module regression (that is the coordinator's integration gate). Never run `validate --all`, first-start timing, or Maven `@all`.
- Stay inside group G2's owned files per `docs/maintainer/3.0.0-locked-groups-plan.md`; touch other groups' behaviour only through the documented contracts; don't touch engine-defects-held files until the gate in the groups plan.
- The existing PageSurfaces sanitizing, labeling, undo and cleanup behaviour and its tests (`AgentLiveFixTest`, `LiveChromeCdpTest`, `SurfaceIdChild`, `DomAccessGuardTest`) are preserved through any migration to CDP/BiDi.
- Temporary files go in `.agent-work/` only.
- Every decision in the group is implemented 100%. "Truly blocked" means an external fact you can prove (e.g. no macOS runner available for a live test); then implement everything else, add the test so it runs where the platform exists, and record the proof. It never means skipping work.
