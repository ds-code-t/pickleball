# 2026-11-25 Spine Design

This document is the concrete design that turns Dan's locked SQLite spine plan (`docs/plans/2026-11-25-sqlite-spine-plan.md`) into implementable roadmaps. It maps every plan section to today's code (branch tip 91f067f7), identifies conflicts and migration risks, settles every open question, and records the ordering dependencies. Nothing is left deferred.

## Mapping to today's code

Today's store (see `docs/agent/feature-map.md` and `AGENTS.md`) is already an embedded SQLite working copy with hydrate/extract, sandboxes as independent DBs, step-definition catalog, warm `store run`, entities, history, and bidirectional-ish edit. The native engine exists (`PickleballEngine`, `pkb_engine=cucumber` selects the adapter). Workbench is currently Swing+WebView with CLI forwarders. Step definitions are Java-only with AspectJ/Cucumber glue. Playwright is present for browser adapters and ships a Node binary.

Key gaps vs plan:
- Runtime still walks paths/classpath in places; not fully logical-row-only.
- Cucumber is still an integrated adapter rather than pure ingest translator.
- No universal entity (commit pin + delta chain); sandboxes are full copies, not COW deltas.
- No Node/TS/JS step-definition adapter; no custom classloader warm-reload for Java.
- Workbench is not a Node renderer; no multi-agent addressing, overlay-as-sibling, external-state model.
- No documented verb inventory distinguishing CLI/MCP/stream.
- Runtime snapshots are file-based + FTS; no per-run frozen hydrated SQLite.
- Browser config/version resolution is Selenium-centric; no progressive ladder or shared YAML block with Playwright.

## Conflicts and migration risks

- Existing sandboxes are independent full SQLite copies. Plan wants COW delta files with parent reference and flatten-on-promote. Migration: keep existing sandbox CLI, add delta mode, document conversion.
- Current hydrate is full or hash-skip; plan adds JAR-hash clean trigger and rolling delta window. Risk: large projects; mitigate with incremental and quiet-moment flatten.
- Cucumber glue/features still used at runtime in some paths. Must translate fully at ingest so spine never sees Cucumber concepts.
- Step annotations are Java strings today; TS/JS must use identical regex style via Node adapter without sharing Java objects.
- Distribution: packaging node_modules and Playwright Node binary into the JAR (or exploded) has size and licensing implications (see below).
- Warm JVM vs CI fresh starts: correctness must not depend on warm path.

## Open questions resolved

- **Runtime snapshot SQLite**: Shared cross-run history DB (layer 3) plus per-run snapshot tables that freeze a copy of the hydrated entity state (universal entity head) at execution start, plus dynamic run attributes. Linked by foreign keys to the commit-pin + delta id. One DB for queryability; per-run files only for very large artifacts. Retention: days + byte cap (reuse existing history policy), GC deltas beyond ~100 window by folding into head. Git-commit linkage is the pin; sandbox branches are delta chains.
- **Playwright fixture/page context**: Hydrate static fixture data at ingest; re-resolve dynamic page/context objects at execution time via the adapter seam. (Not on hold; concrete decision.)
- **Persistent socket**: Per-command spawn for dashboard/chatty paths as decided; persistent is later optional.
- **Verb inventory**: Built as a maintained table in the CLI/MCP roadmap (R4). Categories: CLI-only, MCP-direct (hot path), bidirectional streams (browser-events, recording, chat).
- **Custom classloader**: Per-snapshot isolated URLClassLoader. Warn (non-blocking) at ingest on static mutable fields. Drop loader on reload. No statics required for correctness; CI always fresh. Concrete design in R8.
- **Node Workbench**: Primary human-facing is Node renderer (tabs as locked). Collaborates side-by-side with a real browser DOM session (overlay-as-sibling); does not replace the browser. Dynamic mode ParsingMap is per-step in-memory override on a cheap delta fork.
- **BiDi event surface**: Opt-in Selenium verbs for the events listed in the plan (certain BiDi commands, Grid). Off by default. Exact list documented in the browser roadmap phase.

## Ordering dependencies

Roadmaps must land green independently and build on earlier ones:
1. Core hydration + universal entity foundation (enables everything).
2. Cucumber decoupling (ingest translation, so later adapters are clean).
3. Step-definition language adapters (Java reload + Node/TS/JS).
4. CLI/MCP universal surface + verb inventory (so all later surfaces are CLI-first).
5. Workbench control plane, overlay, chat, multi-agent (uses the surface).
6. Dynamic mode, branching, promote (uses entity + control plane).
7. Runtime snapshots and retention (uses entity + runs).
8. Warm engine classloader, resource gate, browser config resolution (optimizations and remaining browser items).

Full regression only at the end of R8. Targeted tests per phase. Windows only; Linux/macOS gaps stay in tech-debt.

## Distribution and licensing

- npm is build-time only (frontend-maven-plugin or exec in the Maven/Gradle build). Resulting node_modules tree is packaged as a JAR resource or exploded alongside the artifact. End user never runs npm.
- The bundled Playwright Node binary (verified Node 24.22.1) is used at runtime for TS/JS step defs and the Workbench renderer. Java sets NODE_PATH or absolute paths at spawn. PLAYWRIGHT_NODEJS_PATH override remains.
- No new Node distribution dependency. Playwright's binary and any packaged node_modules must be reviewed for license compatibility with Pickleball's distribution model (Dan approval required before Build implements packaging).
- Nothing is downloaded at runtime by the spine. Browser CDN downloads remain under the explicit source enum (local/CDN/any) with air-gap override.

All plan bullets and leftovers are mapped in the roadmap index. Docs will be rewritten in place as each phase lands; history in release notes.
