# R1 scoreboard — core hydration spine and universal entity

Roadmap: `docs/roadmaps/3.0.0-spine/R1-core-hydration-spine-universal-entity.md`. Design: `docs/plans/2026-11-25-spine-design.md`. Branch `3.0.0-spine`. Every phase is mandatory. Windows only. Targeted tests per phase. Full regression is the last phase of R8, not this roadmap.

| Phase | Items | Status | SHAs | CI | Targeted tests run (filters, pass/fail) | Windows live repro | Notes / blockers |
|---|---|---|---|---|---|---|---|
| P1 | Pin, deltas, head, undo, window of 100 | done | 6615c459560ae94e64514c37ed8ca6544ece0c28 | | `.\gradlew.bat :test --tests tools.dscode.store.UniversalEntityTest --tests tools.dscode.store.DeltaWindowTest --tests tools.dscode.store.HeadMaterializerTest --tests tools.dscode.store.sql.StoreSchemaTest --console=plain` — 11 passed, 0 failed | | Windows only |
| P2 | JAR hash, incremental hydrate, enabled flags, tags, paths, run variables, @pkb-ignore | done | bac0cc706e2f69ae75ac0f79e48b9c8c698b85fe | | `.\gradlew.bat :test --tests tools.dscode.store.JarHashResetTest --tests tools.dscode.store.EnabledFlagTest --tests tools.dscode.store.ScenarioTagTableTest --tests tools.dscode.store.RunVariableSyncTest --tests tools.dscode.store.PkbIgnoreRoundTripTest --tests tools.dscode.store.StoreHydrateTest --tests tools.dscode.store.sql.StoreSchemaTest --console=plain` — 15 passed, 0 failed | | Windows only |
| P3 | Conflict show and choose, discarded hold | pending | | | | | |
| P4 | Cow sandbox default, copy mode kept, promote storage | pending | | | | | |
