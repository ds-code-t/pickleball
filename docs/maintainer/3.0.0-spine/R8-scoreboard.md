# R8 scoreboard — warm engine, resource gate, browser configuration

Roadmap: `docs/roadmaps/3.0.0-spine/R8-warm-engine-resource-gate-browser-config.md`. Design: `docs/plans/2026-11-25-spine-design.md`. Branch `3.0.0-spine-r8`. Every phase is mandatory. Windows only. Targeted tests per phase. The full Windows regression waits until the last phase note.

| Phase | Items | Status | SHAs | CI | Targeted tests run (filters, pass/fail) | Windows live repro | Notes / blockers |
|---|---|---|---|---|---|---|---|
| P1 | WarmClassLoader, cold default, incremental JavaCompiler | done | 3060df1b4e63993b928c151f2078fc751bd901b1 | | `.\gradlew.bat :test --tests tools.dscode.engine.runtime.WarmClassLoaderTest --tests tools.dscode.engine.runtime.WarmColdParityTest --tests tools.dscode.store.IncrementalGlueCompileTest --console=plain` — 7 passed, 0 failed (WarmClassLoaderTest 2, WarmColdParityTest 3, IncrementalGlueCompileTest 2) | | Windows. `pkb_warm` defaults off. `spine sample` and `spine session` stay unknown commands in this phase. |
| P2 | ResourceGate, spine sample, subprocess pool | pending | | | | | |
| P3 | Browser YAML, version ladder, Safari leaf, BiDi opt-in, one port owner | pending | | | | | |
| P4 | Warm CDP, UIA tree cache, full Windows regression | pending | | | | | |
