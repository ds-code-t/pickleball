# R5 scoreboard — workbench one control plane

Roadmap: `docs/roadmaps/3.0.0-spine/R5-workbench-one-control-plane.md`. Design: `docs/plans/2026-11-25-spine-design.md`. Branch `3.0.0-spine`. Every phase is mandatory. Windows only. Targeted tests per phase. Full regression is the last phase of R8, not this roadmap. Dan approves the design's Distribution and licensing section before P4 packages npm modules.

| Phase | Items | Status | SHAs | CI | Targeted tests run (filters, pass/fail) | Windows live repro | Notes / blockers |
|---|---|---|---|---|---|---|---|
| P1 | Node renderer shell, Swing window removed, read-only store sql | done | 3c01f5a4fc39417525d8d986f1ab61b9c22edb73 | | `.\gradlew.bat :test --tests tools.dscode.launcher.WorkbenchUiServerTest --tests tools.dscode.launcher.StoreSqlReadOnlyTest --tests tools.dscode.launcher.VerbCatalogTest --console=plain` — 8 passed, 0 failed (WorkbenchUiServerTest 1, StoreSqlReadOnlyTest 1, VerbCatalogTest 6) | | Windows. `pickleball ui` starts the Node renderer in the pickleball JVM on 127.0.0.1 with a 128-bit token. A wrong token is 401. A non-loopback Host is 403. No Chromium writes `state=closed` and the CLI still works. The controller does not spawn Node and does not launch Swing. Tab order is Scenarios, Globals, Data, Raw SQL. `store sql` runs one SELECT. A write or a second statement exits 2. `re:` is filtered in Java. An empty lockfile skips npm. Assets live at `META-INF/pickleball/workbench-ui/`. |
| P2 | Sibling overlay, inject flag, session and agent ids, attached-window injection | pending | | | | | |
| P3 | Chat channel, structured ref and action, point, ask, progress, read receipts | pending | | | | | |
| P4 | Monaco code tab, license allow-list, packaged node_modules | pending | | | | | |
| P5 | Lease verb, input block, generated pages | pending | | | | | |
