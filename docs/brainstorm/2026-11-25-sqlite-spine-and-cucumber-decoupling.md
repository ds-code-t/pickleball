# BRAINSTORM ONLY — DO NOT IMPLEMENT
# Not a roadmap. Not tech debt. Not an action item.
# Agreed/locked items only. Date-stamped scratch space.

# 2026-11-25 — SQLite Spine & Cucumber Decoupling

## Guiding Philosophy (LOCKED)

- Pickleball's spine is self-enclosed, isolated logic that runs itself.
- Everything external is an adapter: Cucumber, Playwright, Gherkin, any script syntax, any programming language hosting step definitions.
- The spine runs any text as business language and matches it to code via regex.
- Step definitions may be defined in Java, TypeScript, or JavaScript for now; other languages later.
- Adapters are the only external surface. The spine never imports or depends on Cucumber, Playwright, or any host framework at runtime.

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
- Cross-language shared state (parsing map, run state, pass state, scenario-level or global state of any kind) follows one pattern: TypeScript/JavaScript never holds or sees the Java object. It calls fixed Java entry-point functions (e.g. "resolve this text") that encapsulate all access. The parsing map and all sub-components stay private Java implementation details.
- No additional dependencies for cross-language state (no MapDB). SQLite for durable entities; Java-side objects for live state; function-call seam between them.
- The bundled Node.js engine from the Playwright dependency can execute TypeScript/JavaScript step definitions directly. The adapter talks to it over stdio or a small socket, registers handlers; the spine never knows Node exists.

## Open Questions (not locked)

- Playwright adapter: hydrate implicit fixture/page context into the row at ingest, or re-resolve at execution? (ON HOLD — revisit after other items resolve)
