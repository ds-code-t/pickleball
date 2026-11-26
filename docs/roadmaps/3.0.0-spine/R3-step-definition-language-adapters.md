# R3: Step-Definition Language Adapters (Java hot-reload + Node/TS/JS)

Branch: 3.0.0-spine only. Never master. Every phase mandatory.

Targeted tests. Windows only. CLI-first. Sync scripts. Record SHA.

Depends on R1, R2.

## P1: Java custom classloader hot-reload

**Functionality:**
1. New `tools.dscode.engine.WarmClassLoader` (per-snapshot URLClassLoader): drop old class, load new. Spine never restarts for step-def changes.
2. In `StoreHydrator`: scan for static mutable; non-blocking warning.
3. After initial Maven/Gradle compile, watch sources; incremental via Java compiler API (`javax.tools`). Dependency change forces full rebuild + fresh JVM.

**Modules/files:** New WarmClassLoader.java, StoreHydrator.java, StoreRunWorker.java, EngineCli.java.

**CLI verbs:** `store run` (warm path reports reload), `engine step`.

**Docs:** Update warm-engine and step-def sections.

**Targeted tests:** New `WarmClassLoaderTest`, `StaticWarningTest`. Existing `StoreRunTest` pass.

**Windows live:** Edit Java step def while warm and re-run.

## P2: Node/TS/JS adapter via bundled Playwright Node

**Functionality:**
1. New `tools.dscode.engine.NodeStepAdapter`: spawn bundled Node (Node 24.22.1 from Playwright), stdio/socket, register handlers. Spine never knows Node.
2. Same regex annotations (single string literal) for .ts/.js. Ingest to same `step_definitions` rows.
3. Shared state only via fixed Java entry points (new `tools.dscode.engine.SharedStateSeam`). No Java objects in JS. No MapDB.
4. Per-context module registry for re-import. npm build-time only; package node_modules into JAR/exploded. Java sets NODE_PATH.

**Modules/files:** New NodeStepAdapter.java, SharedStateSeam.java, build.gradle (frontend-maven-plugin or exec), StoreHydrator.

**CLI verbs:** `store hydrate` (detects .ts/.js), `engine run`.

**Docs:** New section for TS/JS step defs. Distribution notes.

**Targeted tests:** New `NodeStepAdapterTest`, `SharedStateSeamTest`, consumer TS step scenario. Packaging check.

**Windows live:** TS step def execution.

## P3: Seam, rebuild boundary, tech-debt fold

**Functionality:**
1. Documented entry points for all shared state.
2. Dependency change detection enforces rebuild.
3. Fold empty steps and hung watchdog hook into engine/adapter.

**Modules/files:** SharedStateSeam.java, engine step runner.

**CLI verbs:** Existing engine verbs.

**Targeted tests:** Mixed Java/TS scenario. Existing tests pass.

All plan items for languages covered.
