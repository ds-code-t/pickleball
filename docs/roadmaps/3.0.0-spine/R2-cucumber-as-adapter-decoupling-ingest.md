# R2: Cucumber-as-Adapter Decoupling & Ingest Translation

Branch: 3.0.0-spine only. Never master. Every phase mandatory. Nothing deferred.

Targeted tests only. Windows only. Never weaken tests. Gherkin kept. Single git commands. Sync scripts before push. Docs in place. Record SHA.

CLI-first: every capability from CLI. Non-CLI forwards. Toggleable logging.

Depends on R1.

## P1: Ingest translation layer (no runtime Cucumber concepts)

**Functionality:**
1. In `tools.dscode.store.StoreHydrator` and new `tools.dscode.engine.IngestTranslator`: translate glue paths, feature paths, run variables into logical attributes at ingest only. Never used as-is at runtime.
2. Spine (`PickleballEngine`) never imports Cucumber. `pkb_engine=cucumber` is pure adapter on-ramp.
3. Even Cucumber-looking start hydrates to SQLite rows and executes from there.
4. Any text is business language; matching via regex in step definitions. Unsupported context/condition words superseded (clear errors only for truly invalid).

**Modules/files:** StoreHydrator.java, new IngestTranslator.java, PickleballEngine.java, EngineConfig.java.

**CLI verbs:** `store hydrate` (enhanced reporting of translation), `engine run` (confirms no Cucumber types).

**Docs:** Rewrite `docs/cucumber-compatibility.md` and engine sections in place.

**Targeted tests:** New `IngestTranslatorTest`, `NoCucumberRuntimeTest` (classpath or reflection check). Existing `PickleballEngineTest` pass.

**Windows live:** Hydrate a Cucumber-style feature and run native.

## P2: Adapter boundary enforcement and residual cleanup

**Functionality:**
1. Enforce zero Cucumber deps on spine execution path (build check or runtime assert).
2. Legacy projects work via adapter + @pkb-ignore from R1.
3. Empty accepted steps (RETRY etc.) implemented as row attributes or rejected with clear error (no silent no-op). Fold into step model.

**Modules/files:** StoreGherkin.java, step execution in engine, new boundary check in build or EngineCli.

**CLI verbs:** `engine step` reports flags correctly.

**Docs:** Update any remaining Cucumber mentions to adapter-only.

**Targeted tests:** `AdapterBoundaryTest`, `EmptyStepMigrationTest`. Existing Gherkin tests pass.

All plan items for Cucumber decoupling covered.
