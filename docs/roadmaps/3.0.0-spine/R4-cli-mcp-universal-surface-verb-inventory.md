# R4: CLI/MCP Universal Surface & Verb Inventory

Branch: 3.0.0-spine only. Never master. Every phase mandatory.

Targeted tests. Windows only. CLI-first. Sync scripts. Record SHA.

Depends on R1-R3.

## P1: Every capability CLI-reachable; direct MCP hot path

**Functionality:**
1. Audit and ensure every capability has a CLI entry point (`EngineCli`, `StoreCli`, new verbs as needed). Workbench/MCP/Node only forward or call shared Java entry points.
2. Direct MCP hits Java entry points (hot path, skip shell). CLI forward remains fallback. No duplicated logic.

**Modules/files:** WorkbenchMcpServer.java, EngineCli.java, StoreCli.java, new shared entry points in `tools.dscode.engine` / `tools.dscode.store`.

**CLI verbs:** All existing + any new for spine capabilities (e.g. `engine inventory`, `store entity` enhancements).

**Docs:** Update CLI help. Rewrite surface sections.

**Targeted tests:** New `CliCoverageGateTest` updates, `McpDirectHotPathTest`. Existing `WorkbenchMcpServerTest` pass.

## P2: Documented verb inventory table

**Functionality:**
1. Build and maintain verb inventory: each verb classified CLI-only / MCP-direct / bidirectional stream (browser-events, recording, chat). Name the Java entry point. New Node surfaces register against it.
2. Fold relevant 6.x (one reply schema non-breaking, @args.json, normalised element) into the surface.

**Modules/files:** New `tools.dscode.engine.VerbInventory` or JSON resource, MCP registration.

**CLI verbs:** `engine inventory` or `workbench inventory` to print the table.

**Docs:** New `docs/verb-inventory.md`. Update help.

**Targeted tests:** New `VerbInventoryTest`.

All plan items for CLI/MCP covered.
