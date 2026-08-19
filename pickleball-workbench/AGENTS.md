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

Interactive workers use a session-private anchor feature and the neutral `PKB_CONTROL_BRIDGE_*` environment contract. The anchor body must be a guaranteed no-op core step. The bridge's historical pause-first behavior stops first at `SCENARIO_START`, which occurs before `CurrentScenarioState.startScenarioRun()` finishes Pickleball scenario initialization; Workbench treats that pause only as a bootstrap rendezvous. Before returning an interactive worker, the controller installs a one-shot `BEFORE_STEP` breakpoint filtered to the anchor marker step `---pickleball-workbench-anchor`, resumes the bootstrap pause, and lets the root scenario step initialize normal logging/runtime state. It returns only after the marker itself is paused immediately before execution. Live controller operations must run only after that promotion. Pause leases remain finite; the controller renews the owned anchor lease while active. Graceful stop cancels renewal, resumes the anchor so normal lifecycle hooks can finish, waits a bounded period, then terminates and only force-kills as a final fallback. Restart must reuse the existing manifest/classpath, require the previous worker to have stopped cleanly, and must not run Maven/Gradle.

Worker JVM system-property overrides are explicit controller inputs. The default worker constructor supplies none and therefore preserves consumer configuration. Acceptance tooling may provide a narrow override, such as `pkb_browser=CHROME_HEADLESS`, without changing the synchronized snapshot.

## Live runtime operations

`WorkbenchLiveSession` is the controller-side scenario-bound facade for operations on the persistent paused worker. It delegates to `ControlBridgeClient` and the published Pickleball bridge DTOs; it must not reimplement Gherkin matching, mappings, browser behavior, service calls, or semantic hook behavior.

Each live operation resolves the currently owned paused scenario, performs the bridge call on that scenario lane, and verifies afterward that the same process id, bridge runtime id, and scenario id remain active and paused. Normal live operations must not invoke Maven/Gradle, resynchronize the project, or restart the worker.

V1 live Gherkin execution is limited to raw steps that are already matchable by the active consumer/Pickleball glue. Undefined raw text is not converted into an override-only step in this layer; first-class Step Override behavior belongs to the later worker-side override phase.

`live-check` is the direct acceptance probe for this contract. Against the Maven example consumer it executes consumer and Pickleball Gherkin, mutates/resolves the live mapping, performs the existing `%health-full-url` service call, navigates with the existing `navigate to: URL.home` step, reads browser page evidence, repeats an operation, verifies one PID/runtime/scenario was retained, then resumes and requires a clean exit.
