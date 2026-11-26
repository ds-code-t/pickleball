# Heavy session: roadmaps for Dan's SQLite spine plan (pickleball 3.x)

Repo: https://github.com/ds-code-t/pickleball  Branch: `3.0.0-spine` (cut from `3.0.0` at 5faed5a5). Work and push ONLY on `3.0.0-spine`. Never touch `master` or any 2.x branch. No tags, releases, publishing, or force-push.

## Dan's direction (2026-11-25, overrides the plan file's header)
`docs/plans/2026-11-25-sqlite-spine-plan.md` is Dan's plan. Its header says "BRAINSTORM ONLY — DO NOT IMPLEMENT". Dan has now decided: **fully brainstorm and implement everything in that plan, with no exception.** Treat every LOCKED item as a requirement. Resolve every "open question" and every item under "Open Items Remaining" yourself as a concrete design decision, and record why. TypeScript/JavaScript step definitions and the bundled Node runtime ARE in scope, because the plan includes them. Dan's earlier "hold off on JS/TS" applied only to the consumer-fixes round.

## Leftovers to fold in
`docs/tech-debt.md` lists deferred items: the "Deferred from the 3.0.0 consumer-fixes branch" table (6.x numbers; full descriptions are in `docs/maintainer/3.0.0-consumer-fixes/triage.md` and `docs/roadmaps/3.0.0-consumer-fixes.md`) and the older step-engine entries (unused step ceiling fields, hung step watchdog, accepted steps that do nothing, unsupported context/condition words, any-parent context, phrase-local context, inline until). For EACH item decide: (a) still relevant, so fold it into a specific roadmap phase, or (b) superseded by the plan, naming the plan section that supersedes it. Do not leave anything "deferred". Exception: "Deferred Linux and macOS tests" stays tech debt (Dan: test on Windows only; log Linux/macOS testing as tech debt).

## Read first, in order
1. `AGENTS.md` and the docs it points to (the branch docs are the truth about the current design; the plan describes the target).
2. `docs/plans/2026-11-25-sqlite-spine-plan.md` (the whole file).
3. `docs/tech-debt.md`, then the triage file above.
4. The existing roadmap and scoreboard conventions (`docs/roadmaps/3.0.0-consumer-fixes.md`, `docs/maintainer/3.0.0-consumer-fixes/scoreboard.md`) and the current release notes and version files.

## What to do
1. Brainstorm thoroughly first: map every plan section to today's code (what exists, what changes, what is new), find conflicts, migration risks and ordering dependencies, and settle the open questions. Write this to `docs/plans/2026-11-25-spine-design.md`.
2. Split the work into as many roadmaps as it needs (expect several: e.g. hydration/SQLite spine and ingest; Cucumber-as-adapter decoupling; step-definition language adapters incl. Node/TS/JS; CLI/MCP universal surface and verb inventory; workbench/one control plane/overlay/chat/multi-agent addressing; dynamic mode, branching and promote; runtime snapshots and retention; warm-engine classloader). Order them so each lands green on its own and later ones build on earlier ones. Each roadmap is multi-phase; each phase lists functionality, docs and tests.
3. Write:
   - `docs/roadmaps/3.0.0-spine/README.md`: the index, order, dependencies, and a table mapping EVERY plan bullet (by section) and EVERY leftover item to its roadmap and phase, or to "superseded by …". Nothing may be unmapped.
   - `docs/roadmaps/3.0.0-spine/R<n>-<name>.md`: one per roadmap.
   - `docs/maintainer/3.0.0-spine/R<n>-scoreboard.md`: one per roadmap, every phase "pending".
   - `prompts/build-3.0.0-spine-R<n>.md`: one fully standalone Grok Build prompt per roadmap. Each one carries every rule, spec and guardrail it needs (from the branch docs, not memory) and works for a fresh session that has never seen this chat.
4. Every roadmap and Build prompt must say:
   - every phase is mandatory and must be completed in full, and nothing is optional or deferred;
   - each phase runs TARGETED tests only for what that phase changed; the full regression runs at the end of the last roadmap;
   - testing is on Windows only, and Linux/macOS test gaps go into `docs/tech-debt.md`;
   - never weaken, skip or delete tests; keep Gherkin syntax; step annotations stay single string literals; never run `./gradlew --stop`;
   - fetch and rebase before every push, push after every phase, single git commands (no chaining with `&&`, `;` or pipes), never push to master;
   - before pushing run `python scripts/sync_consumer_guidance.py` and `python scripts/refresh_agent_index.py`, and update the SurfaceAssetsTest hash if surface assets change;
   - docs describe only the current implemented design, rewritten in place; history goes in release notes;
   - record the implementation SHA on the scoreboard after each phase.
5. Include the "CLI-first and logging checklist" in each roadmap: every capability reachable from the CLI; every non-CLI surface (GUI, MCP, Node renderer, bindings) only forwards to the CLI or Java entry points; toggleable, per-entity, date-stamped, size-capped logging for new or changed code.
6. Call out any distribution, licensing, security or external-download implications (for example npm at build time, packaging node_modules or the Playwright Node binary in the JAR) in the design doc under a heading "Distribution and licensing", so Dan can approve them before Build.

## Finish
Commit and push everything to `3.0.0-spine`. Do not write placeholder or empty files; each file must be complete. Reply with: the list of files with their byte sizes, the roadmap order, the commit SHA, and any question that truly needs Dan.
