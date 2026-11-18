# Grok Build — Pickleball 3.0.0 Consumer-Ready Phase C

You are a fresh Build session with no chat memory. This prompt is complete on its own.  
Assumes Phases A and B are already merged on `3.0.0-consumer-ready`.

**Repository:** https://github.com/ds-code-t/pickleball  
**Branch:** `3.0.0-consumer-ready` only. Push only to this branch.  
**Never** touch `master` or `3.0.0`. No tags, releases, Maven publishing, or force-push.  
Fetch and rebase before every push. Never run `./gradlew --stop`. No secrets.  
Keep Gherkin syntax. Step annotations stay single string literals.  
Commit and push after the phase. Finish with a scoreboard.

## Read first (on the branch tip)
1. `AGENTS.md`
2. `docs/agent/feature-map.md`
3. `docs/roadmaps/3.0.0-consumer-ready-roadmap.md` (Phase C is the plan of record)
4. `docs/maintainer/3.0.0-linked-tracking-design.md`, `docs/maintainer/3.0.0-embedded-store-design.md`
5. Existing store and Workbench code for the data model.

## Phase C: Polished static HTML dashboard
Implement every item in the roadmap Phase C.  
- Same embedded SQLite store; no second database.  
- Thin data-access layer (no hardcoded localhost).  
- Vendored JS only or zero external JS. No CDN downloads at runtime.  
- Virtualize long lists. Consumer-polished appearance.  
- CLI `dashboard` command + Workbench link. Existing Workbench features stay.  
- Deferred items stay deferred.

Update the roadmap scoreboard. Named tests must pass.

## Finish
- Final scoreboard listing each item as done or not, with SHAs and test results.
- Commit and push.
