# Planning session: pickleball 3.0.0 "consumer-ready" roadmap

You are the planning and roadmap session for github.com/ds-code-t/pickleball. Your job is to write the roadmap and the Grok Build prompts and push them as docs only. Don't implement code.

- Working branch: `3.0.0-consumer-ready`, which is based on `3.0.0` at 6df91885. Push only to this branch.
- Never commit, push or merge to `master` or `3.0.0`. No tags, releases, Maven publishing or force-push. Fetch and rebase before every push.

## Read first, in order (on branch 3.0.0-consumer-ready)
1. `AGENTS.md`
2. `docs/agent/feature-map.md`
3. `docs/maintainer/3.0.0-consumer-report-2026-11-17/agent-blockers-digest.md`: a consumer AI agent's report on 3.0.0. It lists 7 hard bugs, missing instructions and missing tools.
4. `docs/maintainer/3.0.0-consumer-report-2026-11-17/bug-workbench-window-freeze.md`: a 4-minute Workbench UI freeze on a 10,900-scenario project, with the root cause and a suggested fix. It also covers the JVM not exiting when the window closes.
5. `docs/maintainer/3.0.0-consumer-report-2026-11-17/agreed-plan.md`: the plan Dan agreed to.
6. `docs/maintainer/3.0.0-linked-tracking-design.md`, `docs/maintainer/3.0.0-embedded-store-design.md`, `docs/consumer-agent-guide.md`, `docs/pickleball-workbench.md`, `docs/release-notes.md`, plus any other docs and source you need.
   - The branch docs are the source of truth.

## What to produce
Before writing, check every report item against the current code, and note in the roadmap where each lives (file and line).

### 1. `docs/roadmaps/3.0.0-consumer-ready-roadmap.md`
One roadmap that owns every phase, in this order. Everything must be implemented. All phases and all items are required, and nothing is optional unless it's marked deferred below.

- **Phase A: integrity and bug fixes.**
  - Put the Workbench window freeze (plus the JVM-exit/reuse defect) and the worker lock-up with a watchdog and a working restart first.
  - Then:
    - Warm-JVM glue: DataTable converters and consumer `@Before` state.
    - Accurate run history: a SKIPPED status, nested component steps stored, agent runs in the catalog, and one source of truth for `query` vs `store`.
    - Entity reference resolution.
    - Run-id reuse must never overwrite a finished run.
    - All the smaller defects: `ui chat`, `inspect-page`, the `java -jar ... domengine` NoClassDefFoundError, null diagnostic step text, `store steps list` dropping nested steps, and `store run` speed.
- **Phase B: turnkey agent tools and instructions.**
  - Ready-made, fast CLI calls with MCP forwarding to them:
    - `start --url= --browser= --env=` returning a live session that CLI page commands are sent to
    - a blocking `chat_wait`
    - a history summary and query
    - entity lookup
    - open a run in the Explorer or Tracking tab
    - a bridge health signal
    - a fast launcher, so agents don't pay 10–16 s per `mvnw` call
  - A supported agent route that the exported consumer guide allows without IDE MCP.
  - Fix every "missing instructions" item: headless meaning, the step-id format, building the search index, and `dalton` renamed to `store` in the release notes.
  - Update the consumer agent guide so an agent can start working without searching around.
- **Phase C: a polished static HTML dashboard.**
  - Passive and view-only, generated from the same embedded SQLite store, with no second database.
  - Add the git commit SHA and feature-file path to runs if they're missing.
  - Features:
    - a rewindable timeline with markers for runs, scenarios and case IDs
    - filters
    - business-entity and case-ID panels
    - common-error and case-type aggregation
    - run detail
    - links to the feature file, data files, commit and artifacts
  - Opened from Workbench and the CLI (`dashboard` command). Existing Workbench features stay.
  - A thin data-access layer so a central or hosted backend can replace the local file later, with no hardcoded localhost.
  - Virtualize long lists. It must look consumer-polished.
  - Deferred (not in scope): central hosting, Java code views, and case opened/closed lifecycle events.

Each phase lists:
- its functionality
- its docs updates
- named tests that prove each item, with no weakened, skipped or deleted tests
- acceptance criteria
- the "CLI-first and logging checklist": every capability is reachable from the CLI, every non-CLI surface only forwards to the CLI, and logging is toggleable, per-entity, date-stamped and size-capped

Flag any distribution, licensing, security or external-download implication (for example, any JS library the dashboard would need must be vendored or avoided, with no CDN downloads at runtime).

### 2. Two standalone Grok Build prompts
- `prompts/build-3.0.0-consumer-ready-AB.md` covers Phases A and B.
- `prompts/build-3.0.0-consumer-ready-C.md` covers Phase C and assumes A and B are merged.

Each must be fully self-contained: context, files to read, rules, specs, tests, and the branch to push to (`3.0.0-consumer-ready`).

Include these rules:
- never touch master or 3.0.0
- keep Gherkin syntax
- step annotations stay single string literals
- never run `./gradlew --stop`
- fetch and rebase before every push
- no secrets
- commit and push after each phase
- finish with a scoreboard listing each item as done or not, with SHAs and test results

## Finish
- Commit and push the roadmap and the two prompts to `3.0.0-consumer-ready`.
- Reply with the commit SHA, a short phase list, and any question that truly blocks the plan.
