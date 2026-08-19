# Pickleball Workbench Agent Context

Root `AGENTS.md` remains authoritative. Read it and `docs/agent/feature-map.md` before changing this module.

## Module role

`pickleball-workbench` is the separate executable companion for interactive Pickleball tooling. The dependency direction is strictly:

```text
pickleball-workbench -> pickleball
```

The normal `tools.dscode:pickleball` artifact must never depend on or embed Workbench classes or Workbench-only dependencies.

## Build boundary

Workbench must compile and run against the repository's published-equivalent shaded/woven Pickleball artifact through the dedicated root configuration. Do not replace that boundary with a naïve `implementation project(':')`, and do not add a dependency on the unpublished `pickleball-control-api` module.

Workbench-only dependencies, including the future MCP SDK, belong only on the Workbench classpath. Do not move consumer-worker runtime semantics into the controller merely to simplify dependencies.

## Runtime ownership

The Workbench controller owns synchronization, worker process/session lifecycle, bridge client behavior, MCP stdio, and the thin Swing UI. Pickleball owns consumer-worker behavior such as the bridge server/coordinator, DynamicControl/Gherkin execution, Step Override runtime, Mapping state, browser/service-call access, and woven Cucumber integration.

The Workbench bridge client uses the public `tools.dscode.control.bridge.*` DTOs from the normal Pickleball artifact. Do not create a second controller-side model of Pickleball execution semantics.

The canonical consumer-worker bridge environment is:

```text
PKB_CONTROL_BRIDGE_SESSION_DIR
PKB_CONTROL_BRIDGE_SESSION_ID
PKB_CONTROL_BRIDGE_TOKEN
PKB_CONTROL_BRIDGE_PAUSE_FIRST_SCENARIO
```

During the Studio-to-Workbench migration, Pickleball may accept the old `PKB_STUDIO_BRIDGE_*` names as input aliases. New Workbench code must emit only the neutral `PKB_CONTROL_BRIDGE_*` names.

When MCP mode is added, process stdout is protocol-only. Controller logs, worker stdout/stderr, and Pickleball runtime logging must be routed away from MCP stdout.

Project-local `.pickleball/workbench/` content is disposable state and must not be treated as source.
