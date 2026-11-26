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

## Universal Control Guiding Principle (LOCKED)

- Headless core is the spine: CLI and MCP verbs cover every capability. A browser or workbench window is always optional presentation, never required machinery. Everything visible in the Workbench GUI is reachable without any GUI, without any web page, without any browser at all.
- Agent-generated UI: an AI agent asked to "show me a report" or "organize these logs" may compose a purpose-built transient surface — a screenshot gallery sorted by failure cluster, a log diff viewer, a selectable timeline — served as a page that lives only as long as the question does. The Workbench does not bloat into a kitchen-sink app; agents generate the right interface for the moment.
- Any UI an agent generates is itself just another surface on the same control plane: it reads and writes through the same verbs, so it is accountable to the CLI contract like everything else. A generated page that cannot be driven from a terminal has drifted outside the philosophy.
- Future-proof addressing: per-surface session IDs and agent IDs are built in now so multiple concurrent agents — not communicating with each other directly — can address distinct surfaces, chat panels, and browser sessions without collision. Agent self-assigned tasks and instructions are part of the addressing model.

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
- Universal hydration: everything in the project folder is hydrated into SQLite whether or not it is used in a given execution. Toggled-off entities remain as rows with a boolean attribute. Large binaries that cannot be inlined are stored as references; the reference itself is always saved. Presence and execution are separate axes.

## Execution Model: Three Layers (LOCKED)

Three layers separate long-lived infrastructure from per-run work and from cross-project memory.

**Layer 1 — Warm spine.** One long-running JVM holding the hydrated SQLite, the parsing map, and all classloaders. Maven and Gradle enter the picture only when Java source actually changes and needs recompilation. Running scenarios, switching snapshots, and querying logs all happen inside this process with no startup tax. This is the compile-once-edit-after model: one full compilation pass populates the class cache the spine draws from; after that, only changed files recompile.

**Layer 2 — Per-snapshot execution contexts.** Each snapshot or sandbox branch gets its own isolated execution context inside the warm spine — a sandboxed classloader scope by default. Starting a scenario against snapshot B does not rebuild anything; it points the execution context at B's delta chain and runs. When heavy parallel regression or A/B matrix work would overwhelm the shared heap, the spine spawns isolated JVMs from a subprocess pool, each with its own heap, while the warm spine stays lean for interactive work. Pool size comes from config, not hard-coded. Subprocess JVMs go through the full build process again on spin-up.

**Layer 3 — Cross-cutting store.** A separate file-based SQLite outside any project and outside Git that survives Pickleball being fully off. Agent notes, cross-snapshot links, dashboard state, who ran what and when across machines and people. The spine opens it on demand. Even if every execution JVM dies, the notes and links survive.

## Resource-Sampling Gate (LOCKED)

The spine samples JVM heap and off-heap usage, GC pressure, and thread counts, exposing that as queryable state through the same CLI and MCP verbs so an agent can check before spinning up parallel work. If heap crosses a configured threshold, the spine can refuse new snapshot contexts or force the subprocess path automatically. Sampling is not just observability — it is a gate the agent respects.

## Hot-Reload Rules (LOCKED)

**Java step definitions.** Reload via a custom classloader that drops the old class and loads the new one; the spine never restarts. The rule: no static mutable state in step definitions, because static fields become shared global state across reloads. This is enforced as a brief, non-blocking warning at ingest time — "this would reload faster as a static function" — not a hard block, since forcing it would break existing step definitions that rely on injected state or framework contexts. Initial build is one full compilation pass (Maven/Gradle); after that the spine watches source directories and triggers incremental recompilation itself through the Java compiler API directly, with no build tool in the loop for unchanged code.

**TypeScript/JavaScript step definitions.** Node's module system lets you re-require or re-import a file to get a fresh module instance. The spine's Node subprocess pool gives each execution context its own module registry, so reloading a TS/JS step def is just re-importing it. tsx watch mode or equivalent handles the watch-and-recompile side. No classloader gymnastics.

**Dependency changes.** If a step's Java code pulls in a new library, or a TS file imports a new npm package, that is not a hot-reload event — it is a rebuild event that forces the full initial-compilation path again, including spinning a fresh JVM if one is already running. That is the boundary where compile-once stops being free.

**Code-tab editing.** Syntax highlighting for the workbench code tabs pulls in Monaco or CodeMirror through the bundled Node layer, consistent with the zero-install rule. This is a later milestone, not a blocker.

## Browser Configuration and Version Resolution (LOCKED)

Playwright ships its own browser binaries, downloaded from its CDN at playwright.azureedge.net into a versioned cache folder (AppData/Local/ms-playwright on Windows, ~/.cache/ms-playwright on Linux). These are not Maven Central artifacts and not copies of pre-installed Chrome or Edge. Bundled Chromium, Firefox, and WebKit are version-matched to the Playwright install. Branded Chrome or Edge can be targeted via the channel option when present on the machine, but the default path is self-contained. The CDN URL is overridable via an environment variable for air-gapped, proxied, or corporate-restricted networks.

Browser profiles are defined in YAML using the same format already established for Selenium configurations. A universal browser config block is shared by both the Selenium and Playwright adapters so there is one format rather than two parallel ones. The block carries launch arguments common to both (channel, headless, viewport, user agent) plus an adapter-specific section for flags unique to each.

Version resolution uses a progressive-precision ladder. A version string may specify major only, major.minor, or major.minor.patch. At each rung the resolver tries the allowed sources in order. Major 16 means any local major-16 install, otherwise the newest minor of 16. Minor precision means that exact minor with any patch. Patch precision means that exact version. When a download occurs, it pins to the newest patch of the specified minor and logs exactly which version landed.

Source is an explicit enum with three values, defaulting to local for safety so nothing downloads accidentally:
- **local**: the machine's registered installs only (system Chrome, Edge, paths checked directly) — never the cache. If the wanted version is absent locally, it fails hard with a clear message rather than silently pulling from the network.
- **CDN**: version resolution against the registry combined with cache lookup. If the wanted version is already cached, use it with no re-download; if not, download and update the cache; if the CDN is unreachable, fall back to whatever cached version is available.
- **any**: local first, and if that fails, fall through to the full CDN logic as the fallback chain.

Latest means the highest version available from whatever sources the source setting allows. Latest with local-only resolves to the highest version among local installs. Latest with CDN or any checks the registry first, falls back to cache, and downloads the newest patch if nothing matches.

One convention note: local without a version means any local browser; local with a version means that version or nothing.

## Selenium / Safari Adapter (LOCKED)

Selenium is retained as a permanent adapter specifically for real Apple Safari (safaridriver). Playwright's bundled WebKit is not a substitute for actual Safari engine behavior. Playwright is the primary browser adapter for new work (Chromium, Firefox, WebKit). Selenium is the leaf for Safari-only requirements, legacy suites, or compliance contexts that mandate WebDriver.

No silent fallback between drivers. Adapter selection lives at the lowest translation layer: the DSL or agent call is mapped to a concrete driver. Playwright is the default; Selenium is used when config or platform demands it (Safari). MCP verbs, CLI, and higher surfaces never change. The agent sees one browser control interface.

Opt-in Selenium verbs exist for specialist capabilities (certain BiDi commands, Selenium Grid for distributed runs). These are off by default and enabled only when the session config requests them.

Multi-port debugging mode: browsers can be launched with remote debugging enabled on distinct ports. The workbench acts as a port manager. Playwright (connectOverCDP) or Selenium (BiDi websocket) can attach to any listening port. One owner per session — concurrent attachment to the same browser on the same port is disallowed to avoid race conditions. Different ports / different browsers is fine.

Direct Java CDP/BiDi access remains available as a raw escape hatch for the spine itself (not a day-to-day agent surface).

Recording: Playwright codegen is the sole recorder path (first-party, CLI + VS Code extension + inspector). No parallel Selenium recorder is built. Safari-specific reproduction can fall back to raw BiDi if needed; it is debugging, not a product feature.

## CENTRAL — Universal Entity: Commit Pin + Delta (LOCKED)

The universal entity is the single primitive everything else hangs off. It is a format-agnostic Java object: a Git commit reference (the pinned project state) plus an ordered list of deltas on top of it. Storage format is just a projection of this entity — SQLite, report file, sandbox branch are all the same thing at different thicknesses.

- The entity lazily builds project state only as far as needed. Unchanged portions are never materialized; resolution walks the chain: delta rows override parent rows, parent falls back to the committed source.
- Rolling delta window: keep the last ~100 deltas as individual entries for audit and replay. Beyond that, older deltas are folded into a maintained flattened head. Eager flattening on each change by default (re-merging a handful of rows is cheap); if a single delta is expensive (bulk toggle, re-import), defer it and mark the head stale until the next quiet moment.
- The flattened head is what a run actually executes against. It is also what the JAR-hash clean trigger invalidates — a clean build rebuilds the head directly from the pinned commit and current source files, never by replaying deltas.
- After the window, older deltas can be dropped or archived; the flattened head already contains their effect. Same pattern as Git's reflog: the tip is what matters, the log is for recovery.
- Undo/redo/revert: the delta history in the SQLite core engine supports stepping backward and forward through states.
- Branching: a branch is a snapshot of the universal entity at a point, continuing from there with its own delta chain.
- Sandbox: copy-on-write SQLite file storing only differing rows, with parent reference. Edits write into the sandbox; deletions are tombstone entries. No subfolder of extracted files required — the sandbox is a SQLite file of deltas, hydrated through the chain. Flatten-on-promote when a sandbox graduates to a real branch, keeping resolution chains shallow. Materializing real files on disk is an explicit opt-in (e.g. "create new project"), never required to run.
- Deltas are Git-like in concept (immutable base, append-only changes) but live in SQL, not in Git. Git versions project files; the delta entity versions hydrated state. Both sync, but the delta does not need Git to function.
- Report/script file: a custom Pickleball file format that is simultaneously a report and a runnable script. It references an existing hydrated SQLite version plus the git commit and delta that existed when it was generated. Interchangeable: open it as a report (screenshots, steps, errors, logs) or run it as a script (it lazily hydrates the referenced state and executes). Passive read-only HTML export available for sharing. Retains its own internal versioning so you can revert to older step versions. Not Cucumber-specific — it stores references to state, so it works with any adapter (Cucumber, Playwright, future).
- Visual/DOM definition: HTML snapshots and screenshots are first-class step surfaces — the same way a regex is a match target, a captured DOM fragment or screenshot is an addressable match target. Definable visually by clicking and recording with the human/AI agent collaboration layer; playback runs the same captured steps. Parameterizable for data-varying parts. Versioned like any other step row.
- Standalone scenario reports: generatable from a script run (screenshots, recorded steps, errors, logging) and from a walked-through visual session. Both land in the same substrate.
- Dashboard: its own page, hostable on GitHub Pages or driven by GitHub Actions, acting as a central logging hub — ReportPortal-like. Reusing a ReportPortal backend is a viable option since it already has user/org management and is backend-amendable. Timeline view: rewindable, fast-forward, showing different case types executing over time.
- Business entities as first-class queryable linked entities: account numbers, customer IDs, and other domain values recorded via special steps in scripts ("record this as part of the snapshot") become global queryable rows linked to everything — scenarios, steps, runs, the universal entity. Open-one-case/close-one-case tracking across different users and sessions. Test accounts used in automation represent real human business roles and are tracked as entities too.
- Linking is the central operation: business entities ↔ scenarios ↔ automation states ↔ the universal git+delta reference. Every timestamped entity, action, and occurrence is queryable through these links.
- Cross-reference and queryable is central: everything is cross-referenced and queryable so AI agents can perform querying and testing directly. Repeating issues tied to a business entity (e.g. a customer account number failing across many case types) are spottable by query. Pseudo-entities like exception types are first-class too — one bug surfacing across many business entities or one entity type flagging at different times becomes visible. Bottlenecks (traffic, location capacity, time-of-day environmental failures universal to all case types vs entity-specific) are queryable dimensions. Analytics and machine-learning flagging of repeating patterns are natural consumers of the same linked substrate. Implementation is flexible — the principle is that no entity, action, or occurrence is isolated; all are joinable.
- Universal-exception extension: PickleballException is a base class carrying severity and scope metadata (universal, environmental, entity-specific). Any thrown exception automatically carries that metadata into the runtime snapshot. The dashboard queries scope=universal for the global view. Environmental tagging starts manual (a step or config marks "ran under load" / "off-hours"); later an ML pass clusters by entity, time-of-day, and exception signature to surface untagged patterns. No separate exception hierarchy to maintain.
- A/B testing via reference switching: take a report/script, point it at a different SQLite version (different git+delta), rerun — old history retained, new run created under the new reference. If an underlying step definition (Java/TS/JS) changed between versions, the diff is highlighted for that specific scenario: what code changed, what config changed. If a referenced step definition no longer exists, that's surfaced as a break to adjust, not silently skipped.

## Open Questions (not locked)

- **MAJOR — Runtime snapshot SQLite and universal entity linking.** Current run/log storage is file-based with a derived FTS5 SQLite search index. Target model: a runtime snapshot SQLite per run (or shared run-history DB) that freezes the hydrated entity state as it existed at execution time, plus dynamic run attributes. All entities linked via foreign keys. Dashboard queries become ordinary SQL joins. Open sub-questions: one DB vs per-run DBs; snapshot as copy vs optimized schema; git-commit linkage with sandbox branches; retention/GC policy.
- Playwright adapter fixture/page context handling: hydrate at ingest or re-resolve at execution? (ON HOLD)
- Persistent socket vs per-command spawn for chatty dashboard interactions: DECIDED — start with per-command spawn. Persistent socket is an optional later optimization.
- Build the documented verb inventory: which verbs are CLI-only, which are MCP-only, which are bidirectional streams.
- Custom classloader mechanics for warm-engine class reloading: static-state leakage and loader-retention risks need a concrete design before implementation.
- Node.js Workbench GUI details: exact tab layout and prominence; dynamic run mode ParsingMap override semantics; whether the Workbench acts as a custom browser or collaborates side-by-side with a real browser DOM session.
- One-owner-per-session rule and Playwright-primary posture with Selenium leaf for Safari are locked above; remaining open is the precise BiDi event surface that Selenium exposes as opt-in verbs.

## Node.js Workbench GUI & Playwright Integration (LOCKED / REFINED)

Node.js is the primary human-facing Workbench GUI, replacing Java Swing. It is a projection surface over the hydrated SQLite logical structure and forms one logical entity with full Playwright browser DOM control/injection (CDP/BiDi, network, overlays, chat panels, listeners, console, etc.). CLI remains the universal surface; MCP is the agent path; all GUI actions map to CLI/Java entry points.

### Tab Layout (LOCKED)

Primary ordering matches actual workflow:
- **Scenarios / Tests tab (most prominent)**: Business-language steps as the main view. Each step expands in place to show and edit its associated Java / TypeScript / JavaScript step definition. Optional dedicated code editor tab or one-click jump from the expand. Business language stays primary; code is one click deep.
- **Globals / Run Vars / Configs tab**: Flat, intuitive forms for global settings, run variables, paths, and browser configs (nested under logical parents such as "browser" with Selenium / Playwright children). Edits write to SQL and sync to extracted files unless in a sandbox.
- **Data tab**: Human-readable view of data files. Shares a search box with the SQL tab (exact, partial text, regex).
- **Raw SQL tab (least prominent)**: Full queryable/searchable view of the entire project (SQL queries, exact match, partial text, regex).
- **Branch selector**: Visible across tabs. Selecting a branch or run changes styling/color and title bar.

When a previous run or branch is selected, additional run-specific tabs appear (Map, logs, screenshots, etc.).

### Dynamic Run Mode & ParsingMap (LOCKED)

Dynamic run mode is for on-the-fly iteration while a scenario is live or paused. It is distinct from the static tab editors.

- Entering dynamic mode on a completed run immediately creates an entry-triggered cheap delta snapshot/fork off that run's universal entity. The original completed execution remains immutable. Empty forks (no changes) can be discarded on exit.
- The Map tab shows an expandable tree of the ParsingMap / NodeMap for the selected step. Each step has its own map; children inherit from parents. The view is bound to the specific run and step (labeled so it is clear this is not the live project state).
- Edits in dynamic mode write only to the in-memory run map for that step's moment via a pre-step hook: just before the step executes, the map is updated to the values set in the editor. Children inherit the change; parents and subsequent steps are unaffected unless the hook is set again.
- Step definitions can be created or edited on the fly in the editor.
- Dynamic mode does not modify the original run's logs. New session activity attaches to the working branch. Logs on the branch are ephemeral session data.
- Always branch on edit: there is no in-place mutation of a log-bearing completed run. Branching is explicit (save-state / duplicate button), not automatic on every micro-edit.
- Color-shift per branch for visual awareness.
- Per-file branching tree (collapsible graphic) shows history for each report/script file.
- Parenting links between branches are optional metadata (useful for AI analysis) but not a structural dependency. Each branch has its own independent delta chain.
- Promote is the action to move changes upward: promote-to-parent or promote-to-top. At promote time, a toggle offers to clear associated logs or archive them. Naming uses "promote" (not "merge").

### One Control Plane, Many Surfaces (LOCKED)

- One control plane: the workbench GUI, browser sessions, OS-level automation, and headless runs all hang off the same Java entry points and the same CLI/MCP verbs. The Node layer is a renderer that calls those. No duplicated control logic.
- Chat panel is a first-class, always-available surface before any scenario opens. It is the agent's home base for the whole session. Browser-specific chat is one mode of it, not a separate system.
- Clean-versus-injected toggle: default to no injection when the test cares about natural behavior. Injection is opt-in. The workbench tracks which sessions are injected versus pristine.
- Control lease extends to the workbench UI and to individual browser sessions. The agent can take the lease on either or both. Mouse/keyboard blocking on a browser is just another verb.
- Per-surface addressing: every command carries a session ID or label so the agent can address which surface it means. Built in now for multi-agent concurrency.
- Overlay-as-sibling is the default injection strategy: a full-page transparent overlay with absolutely positioned floating elements sits above the real DOM as a sibling layer. Highlights are divs placed by bounding-box coordinates. Direct DOM mutation is the fallback for cases that genuinely need to interact with page elements rather than just annotate them.
- CDP and BiDi replace most custom mutation listeners for observation. Keep a minimal local listener only for things the protocol does not surface cleanly.
- External state is mandatory: the workbench spine holds session state (chat history, highlights, agent assignments) in Java or SQLite. A page reload wipes in-page variables, but the overlay re-renders from the external source. The page is a disposable viewport; the runtime is the memory.
- Multi-agent panels: each agent gets its own overlay instance or named layer within one overlay, addressed by agent ID.

## Open Items Remaining

- Exact BiDi event surface exposed as opt-in Selenium verbs.
- Runtime snapshot SQLite schema and retention policy (see MAJOR open question above).
- Custom classloader concrete design for warm-engine reloading.
- Documented verb inventory table.
