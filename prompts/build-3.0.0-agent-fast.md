# Grok Build — Pickleball 3.0.0-agent-fast (all 7 phases)

You are a fresh Grok Build session with no chat memory, running headless via the grok CLI in a checkout of branch `3.0.0-agent-fast`. This prompt is complete on its own. Work through ALL seven phases in order without stopping early.

**Repository:** https://github.com/ds-code-t/pickleball
**Branch:** `3.0.0-agent-fast` only (from 3.0.0 at f5ccc9f4). The orchestrator merges into 3.0.0 after verification.

## Hard rules
- Never commit, push or merge to `master`, `3.0.0` or any 2.x branch. Push only to `3.0.0-agent-fast`. No tags, releases, Maven publishing or force-push.
- Never run `./gradlew --stop`. Every Gradle call: `timeout 600 ./gradlew --no-daemon ...`.
- Keep Gherkin syntax. Cucumber step annotations stay single string literals.
- Never weaken, skip, disable or delete tests. No secrets in cleartext.
- Do not add Playwright, Chrome DevTools MCP, or any Node dependency. Borrow designs only. Flag (in the scoreboard) any new external download, licensing or distribution implication before adding it.
- CLI-first: Workbench, MCP and Gradle tasks only forward to the CLI. No new layers or parallel catalogs; extend the existing dispatcher, runtime, CDP client and snapshot producer.
- Toggleable, per-entity, date-stamped, size-capped logging (same pattern as StoreLog / EngineLog).
- Docs rewritten in place; history only in `docs/release-notes.md`. Never truncate `AGENTS.md` or `docs/agent/feature-map.md` (compare `wc -c` before push). Exported guidance never links to `docs/maintainer/`, `docs/roadmaps/` or `prompts/`.
- Do not use port 8765 for anything long-lived; leave it free when tests finish.
- HELD (do not run): full multi-module regression, `validate --all`, Maven `@all`, audit fixes F1–F12.

## Read first (on the branch tip)
1. `AGENTS.md` (whole file) and `docs/agent/feature-map.md`.
2. `docs/roadmaps/3.0.0-agent-fast-roadmap.md` — the plan of record: phases, named targeted tests, acceptance, scoreboard. Implement every item in it.
3. `docs/maintainer/3.0.0-agent-fast-design.md` — locked decisions, target-agnostic API (target selector + verbs), tier table, fallback chains, push-channel/watcher, frame/shadow strategy, snapshot integration, compact replies, prebuilts, what is NOT done.
4. `docs/maintainer/3.0.0-agent-fast/README.md` (Dan's principles + locked orchestrator decisions), `evaluation.md` (code-checked bug verdicts with file:line evidence), `browser-control-research.md`, `consumer-field-report-2026-11-18.md`.
5. The source those docs cite (pkb-runtime.js, AgentControl, AgentSurfaceRouter, control bridge, worker/watchdog, LeanWaits, dom-engine CDP client, BidiAdapter, desktop engine).
Branch docs win over this prompt on repo facts. Locked decisions are not up for re-debate.

## First step
Add `3.0.0-agent-fast` to CI `push.branches` in the workflow files if missing, run the pre-push checks below (the repository index is currently stale), commit and push.

## Phases (all mandatory, in order)
1. Critical bugs B1, B4, B5, B2/P3, B14, B16.
2. Performance: timing fields, compact defaults, no re-inject, selftest `ui --cpu`; measure and report against targets (first warm verb ≤1 s, UI op p95 ≤150 ms, idle CPU after panel-open ≤0.1 core within 500 ms, compact reply ≤2 KiB, per-step latency tracked).
3. Agent-efficiency: `start` launches/attaches a real browser (B3), `doctor`, session-scoped chat cursor, warm path, the "drive a live browser and talk to a person" recipe. Also fix B6 if it survives the B1 fix (verify; report).
4. Unified target-agnostic API for browser and desktop with logged fallbacks. Includes the push-channel fix (`Runtime.addBinding` with `executionContextName:"pkb-surface"`) and event-driven waits from the design, if not already landed in Phase 2.
5. Frames/shadow/SPA/virtualized: continuous OOPIF auto-attach, frame in every reply, inspect parity, closed shadow via `DOM.resolveNode`.
6. Snapshot integration: compact ref view, reusable refs, `snap=diff`, same for desktop.
7. Prebuilt one-call functions and components, plus remaining bugs B7, B8, B9, B10, B11, B12, B13, B15, and selftest ui pages.
Each phase: functionality + docs + its named targeted tests from the roadmap (add the tests; they must genuinely exercise the behavior) + acceptance.

## Tests
After each phase run ONLY that phase's targeted tests (plus any directly affected existing test classes). A failed targeted test stops the ladder until honestly fixed. Live-Chrome tests may use the installed Chrome headless.

## Push procedure (at least once per phase, small pushes)
Before every push: `python3 scripts/refresh_agent_index.py`, `python3 scripts/refresh_agent_index.py --check`, `python3 scripts/verify_agent_contract.py`, `python3 scripts/sync_consumer_guidance.py --check`, `rm -rf scripts/__pycache__`, and the `wc -c` check on AGENTS.md / feature-map.
Then: `git fetch origin && git pull --rebase origin 3.0.0-agent-fast && git push origin HEAD:3.0.0-agent-fast`.

## Scoreboard
After every phase, update the scoreboard table in the roadmap (status, targeted tests, timings vs targets, commit SHA, CI status if known, blocker) and push it. Set roadmap Status to `in progress`, then `done` when all seven phases pass. If truly blocked, write the blocker in the scoreboard, push, and stop.

## Finish
Do not stop early, skip phases, or mark anything optional. End with a final scoreboard: each phase done or not, SHAs, test results, measured timings vs targets, and any gaps.
