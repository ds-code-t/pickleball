# R3 scoreboard — step-definition language adapters

Roadmap: `docs/roadmaps/3.0.0-spine/R3-step-definition-language-adapters.md`. Design: `docs/plans/2026-11-25-spine-design.md`. Branch `3.0.0-spine`. Every phase is mandatory. Windows only. Targeted tests per phase. Full regression is the last phase of R8, not this roadmap. Dan approves the design's Distribution and licensing section before P2.

| Phase | Items | Status | SHAs | CI | Targeted tests run (filters, pass/fail) | Windows live repro | Notes / blockers |
|---|---|---|---|---|---|---|---|
| P1 | JavaStepHost, GlueStaticScan warning | done | 45e986209b2bf8e6c20c9fe49ccb1081bdfc4511 | | `.\gradlew.bat :test --tests tools.dscode.engine.match.JavaStepHostTest --tests tools.dscode.store.GlueStaticScanTest --tests tools.dscode.engine.SpineCucumberImportBanTest --console=plain` — 3 passed, 0 failed | | Windows. JDK 21 has no `java.lang.classfile` (final in JDK 24). `GlueStaticScan` reads the class-file field table and `ConstantValue` attribute. The warning text is unchanged and hydrate still succeeds. |
| P2 | NodeStepHost on the driver-bundle Node, JSON seam | pending | | | | | |
| P3 | Project JS and TS defineStep execution | pending | | | | | |
| P4 | Playwright spec ingest, fixture rows, live page at execution | pending | | | | | |
