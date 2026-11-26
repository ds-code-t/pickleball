# R4 scoreboard — CLI and MCP universal surface

Roadmap: `docs/roadmaps/3.0.0-spine/R4-cli-mcp-universal-surface-verb-inventory.md`. Design: `docs/plans/2026-11-25-spine-design.md`. Branch `3.0.0-spine`. Every phase is mandatory. Windows only. Targeted tests per phase. Full regression is the last phase of R8, not this roadmap.

| Phase | Items | Status | SHAs | CI | Targeted tests run (filters, pass/fail) | Windows live repro | Notes / blockers |
|---|---|---|---|---|---|---|---|
| P1 | VerbDispatch, direct MCP, cli transport fallback | done | 4494b3d623aa9dfc57db6ed173d703fb941105d1 | | `.\gradlew.bat :test --tests tools.dscode.launcher.VerbDispatchTest --tests tools.dscode.launcher.McpDirectHotPathTest --console=plain` — 5 passed, 0 failed (VerbDispatchTest 4, McpDirectHotPathTest 1). `.\gradlew.bat :pickleball-workbench:test --tests tools.dscode.workbench.mcp.WorkbenchMcpServerTest --console=plain` — 24 passed, 0 failed | | Windows. Default transport is direct (`POST /v1/verb`). `pkb_mcp_transport=cli` keeps the shell-out. Workbench MCP server tests set cli so the existing forwarder assertions stay. No MCP-only verb. Six follow streams stay in `VerbDispatch` until they end or `--once` is set. |
| P2 | VerbCatalog, engine inventory, verb-inventory.md | pending | | | | | |
| P3 | AgentEnvelope for every JSON reply, ElementRecord, @args file | pending | | | | | |
| P4 | Route modify body, pw in execute-steps, desktop launch, desktop screenshot | pending | | | | | |
