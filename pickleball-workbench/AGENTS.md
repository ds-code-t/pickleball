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

## Synchronization and worker lifecycle

Workbench synchronization is build-tool-assisted, not a replacement build system. Use the selected Maven/Gradle wrapper to establish compiled main/test output, processed resources, and the effective test runtime classpath. Gradle synchronization must use build-native init-script/task injection rather than the Gradle Tooling API.

`.pickleball/workbench/base/classes` is synchronization provenance/reset state and must never be on a worker runtime classpath. `.pickleball/workbench/live/classes` is the one merged project-owned runtime root; main output is materialized first and test output overlays it so one class/resource path is visible exactly once. External dependency entries stay referenced from their normal caches. The synchronization fingerprint covers both merged project output and dependency artifact contents, so replacing a same-version local dependency still changes the snapshot identity.

The controller owns one interactive worker per selected project by default. Workers launch directly with Java from the existing Workbench snapshot, use the Pickleball-side `WorkbenchWorkerMain` bootstrap, and set `pickleball.workbench.testOutputRoot` so `DynamicSuiteBootstrap` intentionally scans the merged live root instead of relying on Maven/Gradle output suffixes.

Interactive workers use a session-private anchor feature and the neutral `PKB_CONTROL_BRIDGE_*` environment contract. The anchor body must be a guaranteed no-op core step; controller operations happen while the scenario is paused, and completing the anchor must not introduce an unrelated mutation/failure. Pause leases remain finite; the controller renews the owned anchor lease while active. Graceful stop cancels renewal, resumes the anchor so normal lifecycle hooks can finish, explicitly closes the worker bridge after Cucumber returns, waits a bounded period, then terminates and only force-kills as a final fallback. Restart must reuse the existing manifest/classpath, require the previous worker to have stopped cleanly, and must not run Maven/Gradle.
