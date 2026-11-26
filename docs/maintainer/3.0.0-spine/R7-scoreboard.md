# R7 scoreboard — runtime snapshots, retention, cross-cutting store

Roadmap: `docs/roadmaps/3.0.0-spine/R7-runtime-snapshots-retention-cross-store.md`. Design: `docs/plans/2026-11-25-spine-design.md`. Worktree branch `3.0.0-spine-r7` (coordinator override; do not push `3.0.0-spine`). Every phase is mandatory. Windows only. Targeted tests per phase. Full regression is the last phase of R8, not this roadmap.

| Phase | Items | Status | SHAs | CI | Targeted tests run (filters, pass/fail) | Windows live repro | Notes / blockers |
|---|---|---|---|---|---|---|---|
| P1 | run_snapshots, entity hashes, sandbox parent head | done | 0208ee2abfba494037993769101db6fc319bde4e | | `.\gradlew.bat :test --tests tools.dscode.store.RunSnapshotTest --tests tools.dscode.store.RunHistoryAccuracyTest --console=plain` — 6 passed, 0 failed (RunSnapshotTest 2, RunHistoryAccuracyTest 4) | | Windows. Snapshot rows are in `runstate.db`. Source `store_schema` stays 1. Run-state `schema_meta` is `run_snapshots=1`. A cow promote does not rewrite the old parent head id. |
| P2 | .pkb.json script, HTML export, visual target, RECORD ENTITY, PickleballException, snap_find_index | pending | | | | | |
| P3 | Retain days and bytes, delta_archive | pending | | | | | |
| P4 | cross.db notes, journal, links | pending | | | | | |
