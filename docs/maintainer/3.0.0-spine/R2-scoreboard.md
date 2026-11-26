# R2 scoreboard — Cucumber as adapter, decoupling and ingest

Roadmap: `docs/roadmaps/3.0.0-spine/R2-cucumber-as-adapter-decoupling-ingest.md`. Design: `docs/plans/2026-11-25-spine-design.md`. Branch `3.0.0-spine`. Every phase is mandatory. Windows only. Targeted tests per phase. Full regression is the last phase of R8, not this roadmap.

| Phase | Items | Status | SHAs | CI | Targeted tests run (filters, pass/fail) | Windows live repro | Notes / blockers |
|---|---|---|---|---|---|---|---|
| P1 | CucumberIngest, PatternMatcher, import ban | done | 6b2c0f2221c13123646630fdc4fe75d780a25c5a | | `.\gradlew.bat :test --tests tools.dscode.engine.match.PatternMatcherParityTest --tests tools.dscode.engine.adapter.cucumber.CucumberIngestTest --tests tools.dscode.engine.SpineCucumberImportBanTest --console=plain` — 4 passed, 0 failed | | Windows. StepRegistryTest 6 passed. CucumberBoundaryTest 3 passed. |
| P2 | Native runs read rows only | done | 209951b916fdc53abc1688c44da8bcecbad03a63 | | `.\gradlew.bat :test --tests tools.dscode.engine.NativeRunUsesStoreOnlyTest --tests tools.dscode.engine.parity.ParityHarnessTest --tests tools.dscode.engine.SpineCucumberImportBanTest --console=plain` — 5 passed, 0 failed | | Windows. Parity class is ParityHarnessTest. NativeCucumberRowParityTest was not created. |
| P3 | Step ceilings, watchdog, flag steps, inline until | pending | | | | | |
| P4 | Context words, exists, any parent, phrase-local context, between | pending | | | | | |
