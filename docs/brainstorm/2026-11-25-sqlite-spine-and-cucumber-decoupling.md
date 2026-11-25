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
- CLI is the universal capability surface: anything a GUI or MCP can do, CLI can do. If a button exists, its click handler is one line invoking the equivalent CLI command or Java entry point. If it cannot be triggered from a terminal, it should not exist in the GUI.
- MCP is the structured agent transport over CLI — typed args in, structured JSON out. Humans use CLI or Workbench GUI; MCP's real consumers are agents.
- Bidirectional streams (browser-events follow, recording streams, chat sessions) are the named exception for long-lived connections that one-shot CLI commands cannot serve cleanly.
- Hydration is the core operating model: Git stores source files (the extracted, human-editable form); SQLite is the hydrated build artifact the engine runs against. Like Java source compiled to classes — you don't run .java files, you run the compiled output. The hydrated SQLite lives in target/build and is gitignored; it is never the Git artifact.
- At execution time the engine sees ONLY logical objects already hydrated into SQLite. No OS paths, no classpath, no directory structure, no file-system concepts remain in the runtime. Everything is rows, booleans, tags, and references.
- Bidirectional sync is the default posture: edit in the Pickleball SQL GUI and it writes back to source files; edit a source file and hydration picks it up. The SQL GUI is expected to be where most people end up editing.
- Granular fine-detail editing in SQL is the philosophy — not "toggle as a feature" but everything as an editable, queryable SQL object. Entities, scenarios, steps, step definitions, tags, paths, run variables: all rows with attributes, all editable at any granularity, one at a time or in folders.

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
- The bundled Node.js engine from the Playwright dependency can execute TypeScript/JavaScript step definitions directly. The adapter talks to it over stdio or a small socket, registers handlers; the spine never knows Node exists. Verified: it is a real Node 24.22.1 binary, complete runtime (TLS, CA store, standard library), shipped as the driver-bundle artifact with platform binaries; Python/Java/.NET bindings already spawn it as a subprocess; PLAYWRIGHT_NODEJS_PATH override exists.
- Node.js is the target for any human-facing interface (Workbench, dashboard, GUI). The bundled Playwright Node binary is the execution vehicle. npm is build-time only: npm install runs during the Maven build (e.g. frontend-maven-plugin or exec step), the resulting node_modules tree is packaged into the JAR as a resource or exploded alongside it. At runtime Java points the bundled binary at that pre-built tree. End user never sees npm, never installs anything, nothing on the system PATH. NODE_PATH or absolute paths set from Java at spawn time. No new Node distribution dependency.
- Direct MCP calls for AI agents: MCP tools hit Java directly, skipping CLI forwarding on the hot path. Removes shell escaping, stdout scraping, quote/character issues. CLI forwarding path remains as a fallback for debugging when an MCP verb misbehaves, but is not the hot path.
- Direct MCP and CLI layers share the same Java entry points — no duplicated logic. Same seam pattern as the parsing-map forwarding.
- Any new Node dashboard surface registers against a documented verb map (CLI-only / MCP-only / bidirectional stream) rather than inventing its own channel. A documented inventory of which verbs fall in which category is needed.
- Granular on/off control is universal: every hydrated entity (feature, scenario, step, step definition, folder) carries an enabled/disabled boolean attribute in SQLite. Glue-path / feature-path / classpath membership at ingest sets the default toggle state, but any entity can be flipped individually afterward regardless of its original path membership. Folders can be toggled as a group.
- PKB-ignore pseudo-tag: when a toggle is flipped off in SQL for a legacy Cucumber-style project, the sync layer adds a Pickleball-specific ignore marker (e.g. @pkb-ignore) to the corresponding source file entity, and removes it when re-enabled. This keeps old-style projects working and round-trips the toggle into the file format Cucumber understands. Folder-level ignores supported the same way.
- PKB run variables and similar config follow the same pattern: ingested as logical attributes, toggleable and editable in SQL, synced back to source files in legacy mode.
- Tags (Cucumber tags and beyond) are first-class queryable SQL attributes on scenarios — easily editable, easily filtered, not parsed at runtime from file syntax.
- Sandbox branching: a temporary sandbox copy of the hydrated SQLite can be created for isolated testing without affecting the main store. Sandbox duplicates are not synced and files are not extracted unless explicitly told. Reconciliation back to main is manual.
- Conflict resolution: if an SQL row and its source file both changed since last sync (hash detects file movement but not divergent edits), surface a side-by-side diff of both versions and let the user choose which to keep. Hold the discarded version in a temp reference folder until next restart or next day so nothing is lost. No merge library for v1 — user decides.
- Hash-based incremental hydration: on run, only re-hydrate entities whose source-file hashes changed. Unchanged entities are reused as-is.
- JAR-hash clean trigger: the Pickleball JAR itself is hashed as part of the hydration check. If the JAR hash changes (new build, any class file, resource, or bundled schema definition changed), the hydrated store is wiped and fully re-hydrated under current rules — equivalent to mvn clean. Source-file hashes handle the incremental path; JAR hash handles the "rules changed" path. Schema definitions ship inside the JAR and are never external, so no separate migration-file hash is needed. A version-number alternative was considered and rejected: a JAR hash is automatic and cannot be forgotten.
- Warm Java engine: the JVM hosting the spine ideally stays running in the background across runs so class reloading (custom classloader) handles Java step-definition changes without full recompilation. This is a dev optimization only — correctness must not depend on it, because CI/CD always starts a fresh JVM.
- Modular layered architecture: business-language document layer (any text), adapter layer (Cucumber, Playwright-test, future formats translating into the spine), step-definition layer (Java/TS/JS bindings). Each layer swappable without affecting the others. Hydration stores entities in their logical layered structure in SQLite, navigable as an expandable tree (feature → scenario → step → step definition) distinct from but parallel to the file-directory tree, with a GUI to browse either.

## Open Questions (not locked)

- Playwright adapter: hydrate implicit fixture/page context into the row at ingest, or re-resolve at execution? (ON HOLD — revisit after other items resolve)
- Persistent socket connection vs per-command spawn for chatty dashboard interactions. CLI-style spawn matches the testability ideal; a persistent channel is an optimization that can be added without changing the contract.
- Build the documented verb inventory: which verbs are CLI-only, which are MCP-only, which are bidirectional streams, and the rule that new Node surfaces register against it.
- Custom classloader mechanics for warm-engine class reloading: static-state leakage and loader-retention risks need a concrete design before implementation.
