# Plan from Grok chat "Pickleball Repo Branch 3.0.0 Discussion" (read 2026-11-17 1:19 PM MST)

Status: agreed order, no written roadmap or Build prompt yet. One roadmap doc should own all three phases, with explicit handoffs.

## Order (Dan approved)
1. Integrity and bug fixes from the consumer-agent report. The worker lock-up goes first.
   - Worker lock-up plus a watchdog and a working restart.
   - Warm-JVM glue: DataTable converters and @Before state.
   - Accurate history: SKIPPED status, nested steps, agent runs in the catalog, one source of truth (query vs store).
   - Entity resolution.
   - Run-id reuse overwriting results.
   - Small defects: ui chat, inspect-page, jar domengine entrypoint, diagnostic step text, store steps list (nested), store run speed.
2. Turnkey agent tools and instructions.
   - Ready-made CLI/MCP calls: start-session (--url/--browser/--env), chat-wait, history query, entity lookup, open run in Explorer.
   - Guidance that matches what the exported docs allow (a CLI route for consumers that ban IDE MCP).
   - Fix doc gaps: headless meaning, step-id format, search index, dalton -> store in release notes.
3. Polished static HTML dashboard.
   - Passive and view-only; it never triggers runs.
   - Built on the same SQLite store, with no second instance. Add commit SHA and feature path columns/metadata to runs.
   - Timeline scrubber with markers, filters, entity and case-ID panels, error aggregation, run detail, links to feature/commit/artifacts.
   - Linked from Workbench; existing Workbench functionality stays.
   - A thin data-access layer so it can later swap to a central backend; no hardcoded localhost. Virtualize long log lists.

## Deferred
- Central hosting (GitHub Pages or a central DB).
- Java and test-data code views in the dashboard.
- Case opened/closed lifecycle events: optional, undecided.

## Notes
- The linked-tracking design doc had chosen the Tracking tab over a standalone dashboard. Dan now wants the dashboard added.
- Grok's estimates: dashboard alone 2-3 h focused, or "a week" for premium polish. A 1-hour run is not practical.
