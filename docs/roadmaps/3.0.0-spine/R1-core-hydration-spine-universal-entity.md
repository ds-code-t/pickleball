# R1: Core Hydration Spine & Universal Entity Foundation

Branch: 3.0.0-spine only. Never master. Every phase is mandatory and must be completed in full. Nothing is optional or deferred.

Each phase runs TARGETED tests only for what that phase changed. Full regression at the end of the last roadmap (R8).

Testing is on Windows only. Linux/macOS test gaps go into docs/tech-debt.md.

Never weaken, skip or delete tests. Keep Gherkin syntax. Step annotations stay single string literals. Never run ./gradlew --stop.

Fetch and rebase before every push. Push after every phase. Single git commands (no chaining with &&, ; or pipes). Never push to master.

Before pushing run python scripts/sync_consumer_guidance.py and python scripts/refresh_agent_index.py, and update the SurfaceAssetsTest hash if surface assets change.

Docs describe only the current implemented design, rewritten in place; history goes in release notes.

Record the implementation SHA on the scoreboard after each phase.

## CLI-first and logging checklist (check every phase)

- Every capability reachable from the CLI.
- Every non-CLI surface (GUI, MCP, Node renderer, bindings) only forwards to the CLI or Java entry points.
- Toggleable, per-entity, date-stamped, size-capped logging for new or changed code.

## P1: Universal entity (commit pin + deltas) and incremental hydration

**Functionality:**
1. Introduce universal entity as Git commit reference + ordered list of deltas. Storage (SQLite) is a projection.
2. Lazy materialization of unchanged portions; resolution via delta chain.
3. Rolling delta window (~100); older folded into flattened head. Flattened head is what is executed against.
4. JAR-hash clean trigger: if Pickleball JAR hash changes, wipe hydrated store and full re-hydrate.
5. Hash-based incremental: only re-hydrate entities whose source hashes changed.
6. Bidirectional sync: SQL edits write back to source; source edits picked up by hydration.
7. Enabled/disabled boolean on every entity; folders as groups. PKB-ignore for legacy.
8. Tags as first-class SQL attributes. Paths as normalized queryable attrs only.

**Docs:** Update store and entity sections in AGENTS.md, feature-map. Release notes.

**Targeted tests:** Universal entity creation, delta application, incremental hash, JAR-hash clean, bidir sync, enabled/@pkb-ignore, tag queries.

**Windows live:** Hydrate sample project, edit in SQL, verify source update and re-hydrate.

**Release notes:** R1-P1: Universal entity and enhanced hydration spine.

## P2: Conflict resolution, sandbox COW, step attributes

**Functionality:**
1. Conflict resolution: hash detects divergence; side-by-side diff; user chooses; hold discarded in temp until restart.
2. Sandbox as copy-on-write SQLite with parent reference; edits to sandbox; flatten on promote. Existing independent sandboxes remain compatible.
3. Steps as editable rows with optional per-step ceilings (fold unused fields), watchdog for hung steps.
4. Empty accepted steps (RETRY etc.) become real attributes or removed with migration.

**Docs:** Conflict, sandbox, step model sections.

**Targeted tests:** Conflict UI/logic, sandbox COW/promote, step ceilings/watchdog, empty step migration.

**Windows live:** Create conflict, resolve; sandbox edit and promote.

**Release notes:** R1-P2: Conflicts, COW sandboxes, step model.

## Decisions
- Universal entity is the source of truth projection. Existing store tables migrate to this model.
- No merge library for v1; user decides conflicts.

All plan items for core spine are covered. Later roadmaps build on this.
