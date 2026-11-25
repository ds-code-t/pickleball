# BRAINSTORM ONLY — DO NOT IMPLEMENT
# Not a roadmap. Not tech debt. Not an action item.
# Agreed/locked items only. Date-stamped scratch space.

# 2026-11-25 — SQLite Spine & Cucumber Decoupling

## Locked Decisions

- Cucumber is entirely decoupled from the spine. It is a legacy adapter / on-ramp, not integrated into core execution.
- Cucumber-specific concepts (glue paths, feature paths, run variables) are translated into the new system at ingest — never used as-is at runtime.
- Pickleball is agnostic about script origin: scenario-type scripts, Gherkin-type scripts, or arbitrary text. A step is a step.
- Step definitions use regex annotations (Cucumber-like syntax is acceptable as one format) but Cucumber is not required. Future non-Gherkin formats are expected.
- Same step-definition annotations must run on TypeScript/JavaScript step definitions.
- Playwright test definitions and Playwright scripts run interchangeably with the same system.
- "Interchangeable" means different ingest shapes landing in one executable substrate — not that a Playwright file becomes Gherkin.
- SQLite is the central source of truth for a run: everything hydrates into it, tagged with provenance and original positions.
- Runtime execution reads only hydrated SQLite rows. No OS path or classpath resolution during a run.
- Paths (relative or absolute) are normalized per-OS at ingest and stored as queryable attributes — path is something you query, not something the runtime walks.
- Glue path is no longer a Cucumber concept; it is just another ingested attribute. Scripts need not come from feature files.
- External binaries that cannot be inlined (Java source, compiled classes, feature files containing Gherkin) stay outside the DB as references. Inlinable internals (JSON, XML, YAML) pass into the database.
- Even a Cucumber-looking start hydrates into SQLite and executes from there — Cucumber is only the entry door.

## Open Questions (not locked)

- (none yet)
