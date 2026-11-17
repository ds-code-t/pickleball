# Grok Build — Pickleball 3.0.0 Consumer-Ready Phases A and B

You are a fresh Build session with no chat memory. This prompt is complete on its own.

**Repository:** https://github.com/ds-code-t/pickleball  
**Branch:** `3.0.0-consumer-ready` only. Push only to this branch.  
**Never** touch `master` or `3.0.0`. No tags, releases, Maven publishing, or force-push.  
Fetch and rebase before every push. Never run `./gradlew --stop`. No secrets.  
Keep Gherkin syntax. Step annotations stay single string literals.  
Commit and push after each phase. Finish with a scoreboard.

## Read first (on the branch tip)
1. `AGENTS.md`
2. `docs/agent/feature-map.md`
3. `docs/roadmaps/3.0.0-consumer-ready-roadmap.md` (this is the plan of record)
4. The three report files under `docs/maintainer/3.0.0-consumer-report-2026-11-17/`
5. `docs/maintainer/3.0.0-linked-tracking-design.md`, `docs/maintainer/3.0.0-embedded-store-design.md`
6. `docs/consumer-agent-guide.md`, `docs/pickleball-workbench.md`, `docs/release-notes.md`

## Phase A: Integrity and bug fixes
Implement every item in the roadmap Phase A.  
Start with the Workbench freeze (A1) and the worker lock-up + watchdog (A2).  
Then A3–A9 (A8 = the three store test failures from the last full regression; A9 = empty Tracking and Store tabs, consumer report P2-24).  
Update the roadmap scoreboard after the phase.  
Named tests must pass. No weakened tests.

## Phase B: Turnkey agent tools and instructions
Implement every item in the roadmap Phase B.  
CLI-first: every capability reachable from the CLI; MCP and Workbench only forward.  
Update the consumer agent guide so an agent can start without searching.  
Update the roadmap scoreboard after the phase.

## Rules for done
- Nothing is optional. Don't stop at a summary: implement, test, commit, push each item.
- If a report item can't be reproduced, write a test that encodes the reported behavior and fix the code path the report points to; don't mark it 'not reproducible' without that test.
- Run the named tests plus the affected module test suites before each push (root `:test` for store/engine changes, `pickleball-workbench` tests for Workbench changes).

## Finish
- Final scoreboard listing each item as done or not, with SHAs and test results.
- Commit and push.
