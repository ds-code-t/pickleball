# 3.0.0-spine Roadmaps Index

Order and dependencies for implementing the full SQLite spine plan. Work only on 3.0.0-spine. Never master.

## Roadmap Order

1. **R1 Core Hydration Spine & Universal Entity Foundation** — commit-pin + deltas, incremental/JAR-hash, bidirectional sync, enabled flags, @pkb-ignore, tags, conflicts. Foundation for all.
2. **R2 Cucumber Adapter Decoupling & Ingest Translation** — translate at ingest, zero runtime Cucumber in spine.
3. **R3 Step Definition Language Adapters** — Java custom classloader hot-reload + Node/TS/JS via bundled Playwright Node.
4. **R4 CLI/MCP Universal Surface & Verb Inventory** — every capability CLI-first, direct MCP hot path, documented inventory.
5. **R5 Workbench One Control Plane, Overlay, Chat & Multi-Agent Addressing** — Node renderer, overlay-as-sibling, per-surface/agent IDs, external state.
6. **R6 Dynamic Mode, Branching & Promote** — delta snapshots, Map tab, promote, color-shift.
7. **R7 Runtime Snapshots, Retention & Cross-cutting Store** — per-run snapshot SQLite, retention/GC, layer 3 store.
8. **R8 Warm Engine Classloader, Resource Gate & Browser Config Resolution** — hot-reload rules, heap gate, progressive version ladder, Selenium Safari leaf.

Each roadmap is multi-phase. Every phase is mandatory. Targeted tests per phase; full regression only after R8. Windows only.

## Mapping of Plan Bullets and Leftovers

| Plan section / leftover | Roadmap / phase | Notes |
|-------------------------|-----------------|-------|
| Guiding Philosophy, Universal Control | All, esp R4 R5 | CLI universal surface |
| Hydration core, JAR-hash, incremental, bidir sync, enabled, @pkb-ignore, tags | R1 | Foundation |
| Universal Entity commit+delta, branching, promote, sandbox COW | R1 R6 | |
| Three-layer execution, resource gate | R7 R8 | |
| Hot-reload rules (Java classloader, TS/JS re-import) | R3 R8 | |
| Browser config, version resolution, Selenium Safari | R8 | |
| Node Workbench, overlay, chat, multi-agent, dynamic mode | R5 R6 | |
| Runtime snapshots, retention, cross-cutting store | R7 | |
| CLI/MCP verb inventory, direct MCP | R4 | |
| Step ceilings, hung watchdog, empty steps | R1 R3 | Folded as step attrs and engine |
| Unsupported contexts, any-parent, phrase-local, inline until | R2 R3 | Superseded by any-text regex matching or folded |
| Deferred Linux/macOS tests | tech-debt.md | Per Dan, stays |
| Consumer-fixes 6.x (chat, overlay, snaps, etc.) | R5 R7 R4 | Folded or superseded as noted in design doc |

All items mapped. Nothing deferred without explicit note.

See individual R<n>-*.md for phases. Scoreboards in docs/maintainer/3.0.0-spine/. Standalone Build prompts in prompts/build-3.0.0-spine-R<n>.md.
