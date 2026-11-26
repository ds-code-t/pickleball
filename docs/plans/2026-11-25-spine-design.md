# 2026-11-25 Spine Design

This document is the concrete design that turns Dan's locked SQLite spine plan (`docs/plans/2026-11-25-sqlite-spine-plan.md`) into implementable roadmaps. It maps every plan section to today's code (branch tip 91f067f7 / 812a7b31), identifies conflicts and migration risks, settles every open question and every Open Items Remaining with a concrete decision and reason, and records the ordering dependencies. Nothing is left deferred.

## Mapping to today's code

Today's store (see `docs/agent/feature-map.md` and `AGENTS.md`) is already an embedded SQLite working copy with hydrate/extract (`StoreHydrator`, `StoreExtractor`), sandboxes as independent DBs (`StoreSandbox`), step-definition catalog, warm `store run` (`StoreRun`), entities (`StoreEntities`), history, and bidirectional-ish edit (`StoreContent`, `feature put`). The native engine exists (`PickleballEngine`, `pkb_engine=cucumber` selects the adapter). Workbench is currently Swing + OpenJFX WebView with CLI forwarders (`StoreCliForwarder`, `EngineCliForwarder`). Step definitions are Java-only with AspectJ/Cucumber glue. Playwright is present for browser adapters and ships a Node binary (Node 24.22.1).

Key gaps vs plan:
- Runtime still walks paths/classpath in places (`FeaturePathLoadCache`, glue fingerprint); not fully logical-row-only.
- Cucumber is still an integrated adapter rather than pure ingest translator (`pkb_engine=cucumber`).
- No universal entity (commit pin + delta chain); sandboxes are full copies, not COW deltas.
- No Node/TS/JS step-definition adapter; no custom classloader warm-reload for Java.
- Workbench is not a Node renderer; no multi-agent addressing, overlay-as-sibling, external-state model.
- No documented verb inventory distinguishing CLI/MCP/stream.
- Runtime snapshots are file-based + FTS; no per-run frozen hydrated SQLite.
- Browser config/version resolution is Selenium-centric; no progressive ladder or shared YAML block with Playwright.

## Conflicts and migration risks

- Existing sandboxes are independent full SQLite copies (`VACUUM INTO`). Plan wants COW delta files with parent reference and flatten-on-promote. Migration: keep existing sandbox CLI commands, add delta mode alongside, document conversion path in R1.
- Current hydrate is full or hash-skip; plan adds JAR-hash clean trigger and rolling delta window. Risk: large projects; mitigate with incremental and quiet-moment flatten.
- Cucumber glue/features still used at runtime in some paths. Must translate fully at ingest so spine never sees Cucumber concepts.
- Step annotations are Java strings today; TS/JS must use identical regex style via Node adapter without sharing Java objects.
- Distribution: packaging node_modules and Playwright Node binary into the JAR (or exploded) has size and licensing implications (see below).
- Warm JVM vs CI fresh starts: correctness must not depend on warm path.

## Open questions and Open Items Remaining resolved

All settled as concrete decisions:

- **MAJOR — Runtime snapshot SQLite and universal entity linking.** Decision: Shared project-level run-history SQLite (`runstate.db` evolves into the cross-cutting layer) containing per-run snapshot tables that freeze the hydrated entity state (optimized schema with FKs to the universal entity commit SHA + delta head id at start) plus dynamic run attributes. All entities linked via foreign keys. Dashboard queries are ordinary SQL joins. One DB for cross-run queryability. Retention/GC: configurable days and byte cap (extend existing `store history retain`), drop or archive deltas beyond the rolling ~100 window; flattened head remains. Git-commit linkage: every snapshot records the commit SHA + delta head id. Sandbox branches link via their own universal entity snapshot. Reason: Matches plan's "dashboard queries become ordinary SQL joins" and existing runstate.db. Per-run DBs would fragment queries.

- **Playwright adapter fixture/page context handling:** Hydrate static fixture definitions at ingest. Re-resolve dynamic page context at execution time from the live browser session (not frozen at ingest). Reason: Static parts are source; dynamic page state is runtime. ON HOLD resolved.

- **Persistent socket vs per-command spawn:** Per-command spawn for chatty dashboard (as DECIDED in plan). Persistent socket is an optional later optimization. Reason: Simpler initial implementation; avoids long-lived connection complexity until needed.

- **Build the documented verb inventory:** Build as a maintained table in R4. Each verb classified as CLI-only / MCP-direct (hot path) / bidirectional stream (browser-events follow, recording streams, chat sessions), with the shared Java entry point named. New Node surfaces must register against it. Reason: Plan requires the inventory; R4 is the natural place after core entry points exist.

- **Custom classloader mechanics for warm-engine class reloading:** Per-snapshot isolated URLClassLoader (in `tools.dscode.engine.WarmClassLoader` or similar) that can drop the old class and load the new. At ingest (`StoreHydrator`), scan for static mutable state and emit non-blocking warning. Drop loader references after reload so GC can collect. No shared static mutable across reloads. Correctness never depends on warm (CI fresh JVM). Reason: Plan requires concrete design before implementation. Isolation per snapshot matches Layer 2.

- **Node.js Workbench GUI details:** Node primary renderer (bundled Playwright Node). Side-by-side with real browser DOM session (not a custom browser). Tab layout as locked (Scenarios/Tests most prominent, etc.). Dynamic mode ParsingMap override via pre-step hook on the forked delta. Chat always available as home base. Reason: Matches locked tab layout and one control plane; side-by-side avoids replacing the browser.

- **Precise BiDi event surface exposed as opt-in Selenium verbs:** Opt-in verbs for key specialist capabilities: certain BiDi commands (e.g. `bidi.network`, `bidi.log`, `bidi.script`), Selenium Grid attach. Off by default; enabled only when session config requests (`pkb_selenium_bidi=on` or similar). Exact surface documented in R8. Reason: Plan locks the opt-in posture; concrete list avoids open-ended scope.

## Leftover tech-debt decisions (all mapped individually)

See the full table in `docs/roadmaps/3.0.0-spine/README.md`. Summary:
- All 8 older step-engine items folded into R1/R2/R3 as attributes, engine rules, or superseded by regex any-text matching.
- All 19 consumer-fixes 6.x items folded into R4/R5/R7/R8 or marked superseded / remain deferred only if pure consumer-config (with explicit note).
- Deferred Linux/macOS tests stay in `docs/tech-debt.md` (Dan: Windows only).

## Distribution and licensing

- npm is build-time only (frontend-maven-plugin or exec step in the Maven/Gradle build). Resulting node_modules tree is packaged as a JAR resource or exploded alongside the artifact. At runtime Java points the bundled Playwright Node binary at that tree (NODE_PATH or absolute paths set from Java at spawn). End user never sees npm, never installs anything, nothing on the system PATH.
- The bundled Node.js engine is the Playwright-shipped binary (verified Node 24.22.1, complete runtime with TLS, CA store, standard library), shipped as the driver-bundle artifact with platform binaries. No new Node distribution dependency. PLAYWRIGHT_NODEJS_PATH override remains.
- Packaging node_modules or the Playwright Node binary in the JAR (or exploded) has size implications but matches the zero-install rule. Licensing: Playwright's binary and any packaged npm packages (typically Apache-2.0/MIT) must be reviewed for compatibility with Pickleball's distribution model. SQLite is public domain; sqlite-jdbc is Apache-2.0 (already a dependency). **Dan must approve this packaging before any Build prompt that implements the Node adapter or Workbench renderer.**
- Nothing is downloaded at runtime by the spine. Browser CDN downloads (playwright.azureedge.net) remain under the explicit source enum (local/CDN/any) with air-gap override via environment variable.
- Security: bundled binary is from the trusted Playwright artifact; no arbitrary external download at runtime for the spine or Node.

## Architecture notes and ordering

Migration from current independent full-copy sandboxes to universal entity deltas happens in R1 (delta mode alongside existing, conversion documented). Docs describe only the current implemented design, rewritten in place. History in release notes.

Roadmap order (each lands green independently; later build on earlier):
1. R1 Core Hydration Spine & Universal Entity Foundation
2. R2 Cucumber-as-Adapter Decoupling & Ingest Translation
3. R3 Step-Definition Language Adapters (Java + Node/TS/JS)
4. R4 CLI/MCP Universal Surface & Verb Inventory
5. R5 Workbench One Control Plane, Overlay, Chat & Multi-Agent Addressing
6. R6 Dynamic Mode, Branching & Promote
7. R7 Runtime Snapshots, Retention & Cross-Cutting Store
8. R8 Warm Engine Classloader, Resource Gate & Browser Config Resolution

Full regression only at the end of R8. Targeted tests per phase. Windows only.

All plan bullets and leftovers are mapped one-to-one in the roadmap index. Nothing unmapped.
