# Grok Build Prompt: Pickleball 3.0.0 Linked Tracking

You are Grok Build. Implement the linked data model, tracking dashboard, and timeline for Pickleball 3.0.0 on top of Dalton. Do not create a new repo. Do not implement anything outside the phases. Work only on branch `3.0.0`.

## HARD GUARDRAILS (must follow every phase)

- Work only on branch `3.0.0`. NEVER commit, push, or merge to `master`, `2.2.0`, or `2.1.19`. No force-push, no tags, no releases, no publishing. Version stays 3.0.0.
- Do not change existing Gherkin syntax. Do not weaken, skip, or delete tests. Cucumber step annotations stay single string literals with identical values.
- Do not touch open PRs #2 and #3 on `ds-code-t/dom-composite`.
- CLI-first: every capability is reachable from the Dalton/Pickleball CLI. Workbench, MCP, Gradle tasks and any HTTP surface only forward to the CLI. The Workbench never opens SQLite or uses Dalton classes directly.
- Logging for new code: toggleable, per-entity, ISO date-stamped, size-capped, following the existing `pkb_dalton_log` / `DaltonLog` pattern.
- Docs are rewritten IN PLACE to describe only what is currently implemented (AGENTS.md included). No append-only notes and no stale or superseded wording. History goes to `docs/release-notes.md`.
- Regenerate `docs/agent/repository-index.md` only with `python3 scripts/refresh_agent_index.py`. Before every push, run `python3 scripts/refresh_agent_index.py --check`, `python3 scripts/verify_agent_contract.py`, and `python3 scripts/sync_consumer_guidance.py --check`.
- Pull the tip before each push. Push every completed change to `origin/3.0.0`; never leave work unpushed.
- Tests: after each phase, run only SHORT TARGETED tests (`timeout 600 ./gradlew <module>:cleanTest <module>:test --tests <filter>`), named per phase in the roadmap. Do NOT run the full multi-module regression, `validate --all`, Maven `@all`, or first-start timing. The owner is holding those.
- Never write placeholder or 0-byte files. Verify file sizes and contents before claiming success.
- Secrets: never store cleartext secret values in any new table, log, timeline, or dashboard. Mask through the existing secrets approach.

## START PROCEDURE

1. `git fetch origin 3.0.0` and check out the CURRENT tip of `origin/3.0.0`. Record the actual SHA you start from in every doc you write and in the scoreboard.
2. Read, in order: `AGENTS.md`, `docs/maintainer/3.0.0-dalton-design.md`, `docs/dalton-entities.md`, `docs/release-notes.md`, `docs/pickleball-workbench.md`, `docs/pickleball-workbench-player.md`, `docs/agent/feature-map.md`, and the roadmaps under `docs/roadmaps/3.0.0-*` (Dalton, Dalton round 2, decouple round 3, workbench-edit, locked G4). The branch docs win over this prompt wherever they disagree; say so in the design if needed.
3. Read the Dalton source in `src/main/java/tools/dscode/dalton/` (especially `DaltonDatabase`, `DaltonHistory`, `DaltonEntities`, `DaltonRun`, `DaltonRunTrace`, `DaltonQuery`, `DaltonCli`, `DaltonLog`, `DaltonGherkin`, `GlueRules`) and the existing retained diagnostic-run artifacts and Workbench Explorer code (`reports/diagnostic-runs`, `events.jsonl`, `run-catalog.json`, investigation.json, Explorer scrubber/play/speed).
4. Then implement phase by phase according to `docs/roadmaps/3.0.0-linked-tracking-roadmap.md` and the design in `docs/maintainer/3.0.0-linked-tracking-design.md`.

Starting facts (re-verify; do not trust blindly): Dalton ON by default; SCHEMA_VERSION was 5; tables include files, scenarios, steps, entities (PK type+id), runs (scenario TEXT), run_logs (entity free TEXT). No step_definitions, no execution tables, no entity usage joins. Workbench Explorer is view-only rewind over one diagnostic run. CLI-first enforced.

## PHASES (implement in order; fill scoreboard after each)

See `docs/roadmaps/3.0.0-linked-tracking-roadmap.md` for full phase details, functionality, docs, targeted tests, and acceptance checks.

Phase 1: Schema upgrade to 6 + step_definitions catalog from glue. Tests: DaltonSchemaUpgradeTest, DaltonStepDefinitionCatalogTest.
Phase 2: Scenario/step execution recording. Tests: DaltonExecutionRecordingTest.
Phase 3: Entity usage capture (resolution hook + additive API). Tests: DaltonEntityUsageTest.
Phase 4: Log entity links + artifacts. Tests: DaltonLogLinkTest.
Phase 5: CLI query + timeline + retain. Tests: DaltonQueryCliTest, DaltonTimelineTest.
Phase 6: Workbench Tracking + Timeline (forward only, reuse Explorer) + MCP. Tests: WorkbenchTrackingForwarderTest.
Phase 7: Polish, in-place docs, index refresh, checks, scoreboard final.

After each phase: run the named targeted tests only, update scoreboard (phase | status | SHA | CI run | notes), commit, pull tip, push to origin/3.0.0, run the three check scripts.

## SCOREBOARD UPDATE RULE

After each green phase, edit the scoreboard table in the roadmap.md in place. Set Status=done, fill SHA of the commit that completed the phase, CI run if available, notes with test names that passed.

## FINAL

When Phase 7 is done and checks pass, the implementation is complete. Do not start other work. Report the final SHA, the three check results, and the filled scoreboard.

This prompt is fully standalone. Do not depend on any chat history.
