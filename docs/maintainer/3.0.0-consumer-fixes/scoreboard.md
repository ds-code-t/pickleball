# 3.0.0 consumer fixes scoreboard

Roadmap: `docs/roadmaps/3.0.0-consumer-fixes.md`. Branch `3.0.0-consumer-fixes`. Every phase is mandatory.

| Phase | Items | Status | SHAs | Targeted tests run (filters, pass/fail) | Windows live repro | Notes / blockers |
|---|---|---|---|---|---|---|
| P0 | Report, triage, round-2 findings, roadmap, scoreboard, Build prompt | done | 2e1f3490 | — | — | prep on the box |
| P1 | a/D7/D10, D1/G3, D4/D5, D13/D14/D19/D16/L1, L2, D8/D9 | done | | `.\gradlew.bat :test --tests tools.dscode.launcher.HeadedFlagTest --tests tools.dscode.launcher.HumanTierTest --tests tools.dscode.launcher.RetestFixesLiveTest --tests tools.dscode.launcher.InputLiveTest.findRoleSearchesEveryFrame --tests tools.dscode.launcher.ConsumerFixesFrameLiveTest --tests tools.dscode.launcher.FieldReportRegressionTest --console=plain`: 24 passed, 0 failed, 0 skipped (HeadedFlag 1, HumanTier 5, FieldReport 7, InputLive.findRole 1, ConsumerFixesFrame 1, RetestFixes 9). BUILD SUCCESSFUL in 2m 37s. | D8/D9 `ConsumerFixesFrameLiveTest` cross-origin iframe, 3.759s, passed: type Austin verified (`cdp-insertText`/`cdp-keys`/`input-chain`), select Red verified, short-id highlight/label/arrow/step-badge/unstep-badge/guide. | `r3-item-a-wip.patch` deleted in this phase. SHA recorded after push. |
| P2 | D20, D22, D21, E1/E2, D26 + 6.7.2, b, c, d | pending | | | | |
| P3 | D2, D11 + e, D12, D15/D17/D18, D24, D25, D27, D28/D29, L3, L4, E10, E9, D23, D3, e rest | pending | | | | |
| P4 | 6.4.3, 6.5.5, D6/G5 warning, 6.8.2 codes | pending | | | | |
| P5 | G1–G9, 6.9.1–6.9.5, feature map, index | pending | | | | |
| P6 | union of targeted tests, agent_validate, Windows recheck, tech-debt | pending | | | | |
