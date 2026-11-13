# Grok Heavy — 3.0.0 locked decisions: Phase 2 (brainstorm, decide, write split roadmaps)

Repo https://github.com/ds-code-t/pickleball, branch `3.0.0` ONLY (never master, 2.2.0, 2.1.19; no tags or releases; no force-push).

Dan's instruction (2026-11-13): every locked decision must end up 100% implemented, with no excuses. Dan delegates the design decisions to Heavy plus the coordinator bot: brainstorm, then decide. Split the work into SMALLER roadmaps of related phases so no single Build session is overwhelmed. Each phase runs only short targeted tests; the full regression runs only at the very end of everything.

## Read first
AGENTS.md, README.md, docs/, all docs/roadmaps/3.0.0-*.md, and:
- docs/maintainer/locked-decisions-digest-2026-11-13.md (the 22 decisions)
- docs/maintainer/3.0.0-locked-decisions-sweep.md (your Phase 1 verdicts)
- docs/maintainer/3.0.0-snapshot-coverage-audit.md
- docs/maintainer/3.0.0-engine-defects-design.md and docs/roadmaps/3.0.0-engine-defects-roadmap.md. This round runs FIRST and is already planned: don't duplicate its items (XPath via document.evaluate, unsupported-flag reporting, loud fallback, engine lookup order, page-tab attach, hooks, live-Chrome flake). Build on its outcome.

## Task
1. Brainstorm briefly (in chat), then make and record the decisions for every row that isn't "implemented": facade shape and adapters, the library-default vs agent-protocol boundary, BiDi/Firefox, MCP as the single entry point, control-engine naming, the three layers, the XPath-as-clause/combined form, universal syntax, scored affinity plus negative relations, box caps and the one-match rule, section detection, the universal composite snapshot on all 7 platforms, PRE-MAPPING as a core principle (the snapshot pre-maps labels to interactables, sections and nesting, boxes, viewport coords, XPath indexes, image/color features), Dalton central, removing the remaining Rust, and stale-doc cleanup. Also confirm the "implemented" rows really are complete; if not, add work.
2. Write the decisions to `docs/maintainer/3.0.0-locked-decisions-design.md` (substantive, per row: decision, approach, files, tests, docs).
3. Split the work into 3–6 sequenced roadmaps `docs/roadmaps/3.0.0-locked-R<N>-<slug>.md`, each 3–6 related phases. Every phase lists functionality, docs updates, SHORT targeted tests (named test classes and commands; no full regression) and acceptance criteria. Include the CLI-first and logging checklist. The last roadmap ends with a final full multi-module regression phase.
4. For each roadmap, write a fully standalone Build prompt `prompts/3.0.0-locked-R<N>-build.md`: rules and guardrails from AGENTS.md, branch 3.0.0, push after every change, short targeted tests per phase, no skipping/weakening tests, no Gherkin syntax changes, Cucumber annotations stay single string literals, AGENTS.md append-only, finish every phase unless truly blocked.
5. Append a short pointer section at the END of AGENTS.md (append-only; never rewrite or shorten it).

## Rules
- Docs only; no product code.
- NEVER push placeholders or empty files. Push in section-sized commits if needed, and after pushing, re-read each file from GitHub and confirm its real size. If you can't push, paste the full markdown in chat.
- End with a list of pushed files, their byte sizes and commit SHAs.
