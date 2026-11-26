# R8 scoreboard — warm engine, resource gate, browser configuration

Roadmap: `docs/roadmaps/3.0.0-spine/R8-warm-engine-resource-gate-browser-config.md`. Design: `docs/plans/2026-11-25-spine-design.md`. Branch `3.0.0-spine-r8`. Every phase is mandatory. Windows only. Targeted tests per phase. The full Windows regression waits until the last phase note.

| Phase | Items | Status | SHAs | CI | Targeted tests run (filters, pass/fail) | Windows live repro | Notes / blockers |
|---|---|---|---|---|---|---|---|
| P1 | WarmClassLoader, cold default, incremental JavaCompiler | done | 3060df1b4e63993b928c151f2078fc751bd901b1 | | `.\gradlew.bat :test --tests tools.dscode.engine.runtime.WarmClassLoaderTest --tests tools.dscode.engine.runtime.WarmColdParityTest --tests tools.dscode.store.IncrementalGlueCompileTest --console=plain` — 7 passed, 0 failed (WarmClassLoaderTest 2, WarmColdParityTest 3, IncrementalGlueCompileTest 2) | | Windows. `pkb_warm` defaults off. `spine sample` and `spine session` stay unknown commands in this phase. |
| P2 | ResourceGate, spine sample, subprocess pool | done | baf38b1827d0a130895a5065b6e5aa3878259d9a | | `.\gradlew.bat :test --tests tools.dscode.engine.runtime.ResourceGateTest --tests tools.dscode.engine.runtime.WarmColdParityTest --console=plain` — ResourceGateTest 1 passed, 0 failed. WarmColdParityTest 3 passed, 0 failed (`spine sample` is no longer an unknown command; `spine session` still is). | | Windows. Injected 50 is `allow`, 75 with a free slot is `subprocess` (child prints `up`), 90 with a full pool is `refuse` and `spine sample --json` matches. `spine stop` returns the pool to zero. `spine session` stays unknown. |
| P3 | Browser YAML, version ladder, Safari leaf, BiDi opt-in, one port owner | pending | | | | | |
| P4 | Warm CDP, UIA tree cache, full Windows regression | pending | | | | | |
