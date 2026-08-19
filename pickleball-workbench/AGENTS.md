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

Workbench-only dependencies, including the MCP SDK, belong only on the Workbench classpath. Phase 6A uses the non-Spring MCP Java SDK core plus its Jackson 2 adapter; do not replace them with the convenience/Jackson 3 artifact or Spring transports without a new architecture decision. Do not move consumer-worker runtime semantics into the controller merely to simplify dependencies.

## Runtime ownership

The Workbench controller owns synchronization, worker process/session lifecycle, bridge client behavior, MCP stdio, and the thin Swing UI. Pickleball owns consumer-worker behavior such as the bridge server/coordinator, DynamicControl/Gherkin execution, Step Override runtime, Mapping state, browser/service-call access, and woven Cucumber integration.

`WorkbenchServices` is the shared plain-Java adapter boundary. `WorkbenchController` composes synchronization and `WorkbenchLiveSession`; MCP and Swing must delegate to that service surface instead of implementing their own worker ownership, bridge calls, Mapping semantics, Step Override behavior, or scenario retry rules.

The Workbench bridge client uses the public `tools.dscode.control.bridge.*` DTOs from the normal Pickleball artifact. Do not create a second controller-side model of Pickleball execution semantics.

The canonical consumer-worker bridge environment is:

```text
PKB_CONTROL_BRIDGE_SESSION_DIR
PKB_CONTROL_BRIDGE_SESSION_ID
PKB_CONTROL_BRIDGE_TOKEN
PKB_CONTROL_BRIDGE_PAUSE_FIRST_SCENARIO
```

During the Studio-to-Workbench migration, Pickleball may accept the old `PKB_STUDIO_BRIDGE_*` names as input aliases. New Workbench code must emit only the neutral `PKB_CONTROL_BRIDGE_*` names.

Project-local `.pickleball/workbench/` content is disposable state and must not be treated as source.

## Swing UI

Phase 6B adds the thin Swing adapter under:

```text
tools.dscode.workbench.ui
```

Launch it with:

```text
java -jar pickleball-workbench-<version>.jar ui <project>
```

The UI must remain execution-oriented and use `WorkbenchServices` / `WorkbenchController`. Do not recreate the old Studio project IDE, file editor, generic process manager, generic Maven/Gradle UI, Gradle Tooling API browser, source navigator, or collaboration subsystem.

Phase 6B-1 owns only the UI shell plus selected project, synchronization/status, worker start/restart/stop/status, and clean close. Blocking synchronization, process, bridge, screenshot, and service-call actions must not run on the Swing Event Dispatch Thread. Prefer headless-safe tests around presentation/controller delegation rather than tests requiring a visible desktop.

Later Phase 6B increments may add live Gherkin, Mapping, Step Override authoring, events/evidence, browser/service evidence, and basic breakpoints, but those controls must continue to delegate to the shared Workbench service seam.

## MCP stdio

Phase 6A provides:

```text
java -jar pickleball-workbench-<version>.jar mcp <project>
```

The MCP adapter is `tools.dscode.workbench.mcp.WorkbenchMcpServer` plus `WorkbenchMcpTools`. It exposes project synchronization/status, interactive worker lifecycle, live Gherkin, Mapping operations, events/evidence, browser/service controls, semantic breakpoints, and Step Override authoring through `WorkbenchServices`.

MCP stdout is a hard protocol boundary. `WorkbenchApplication` reserves the original process stdout for the stdio transport and redirects ordinary `System.out` output to stderr before constructing the MCP SDK/controller. The executable must explicitly remain alive for the stdio session until stdin reaches EOF; do not rely on MCP SDK worker-thread liveness to keep the JVM running. Workbench diagnostic text must use stderr or `.pickleball/workbench/logs/`; worker stdout/stderr remain separately redirected to worker log files. No banner, normal log, worker output, test output, or diagnostic chatter may be written to MCP stdout.

MCP tool failures are represented as MCP tool results with `isError=true`; they must not escape as arbitrary stdout text. Keep protocol tests covering initialize, tool listing, representative controller calls, invalid requests, Step Override compile invocation, protocol-only output, and cleanup.

Do not add generic IDE/file/build/process/collaboration tools to this MCP surface. Synchronization may invoke project wrappers through the existing synchronizer, but MCP must not become a generic Maven/Gradle execution API.

## Synchronization and worker lifecycle

Workbench synchronization is build-tool-assisted, not a replacement build system. Use the selected Maven/Gradle wrapper to establish compiled main/test output, processed resources, and the effective test runtime classpath. Gradle synchronization must use build-native init-script/task injection rather than the Gradle Tooling API.

`.pickleball/workbench/base/classes` is synchronization provenance/reset state and must never be on a worker runtime classpath. `.pickleball/workbench/live/classes` is the one merged project-owned runtime root; main output is materialized first and test output overlays it so one class/resource path is visible exactly once. External dependency entries stay referenced from their normal caches. The synchronization fingerprint covers both merged project output and dependency artifact contents, so replacing a same-version local dependency still changes the snapshot identity.

The controller owns one interactive worker per selected project by default. Workers launch directly with Java from the existing Workbench snapshot, use the Pickleball-side `WorkbenchWorkerMain` bootstrap, and set `pickleball.workbench.testOutputRoot` so `DynamicSuiteBootstrap` intentionally scans the merged live root instead of relying on Maven/Gradle output suffixes.

Interactive workers use a session-private anchor feature and the neutral `PKB_CONTROL_BRIDGE_*` environment contract. The anchor body must be a guaranteed no-op core step. The bridge's historical pause-first behavior stops first at `SCENARIO_START`, which occurs before `CurrentScenarioState.startScenarioRun()` finishes Pickleball scenario initialization; Workbench treats that pause only as a bootstrap rendezvous. Before returning an interactive worker, the controller installs a one-shot `BEFORE_STEP` breakpoint filtered to the anchor marker step `---pickleball-workbench-anchor`, resumes the bootstrap pause, and lets the root scenario step initialize normal logging/runtime state. It returns only after the marker itself is paused immediately before execution. Live controller operations must run only after that promotion. Pause leases remain finite; the controller renews the owned anchor lease while active. Graceful stop cancels renewal, resumes the anchor so normal lifecycle hooks can finish, waits a bounded period, then terminates and only force-kills as a final fallback. Restart must reuse the existing manifest/classpath, require the previous worker to have stopped cleanly, and must not run Maven/Gradle.

Worker JVM system-property overrides are explicit controller inputs. The default worker constructor supplies none and therefore preserves consumer configuration. Acceptance tooling may provide a narrow override, such as `pkb_browser=CHROME_HEADLESS`, without changing the synchronized snapshot.

## Live runtime operations

`WorkbenchLiveSession` is the controller-side scenario-bound facade for operations on the persistent paused worker. It delegates to `ControlBridgeClient` and the published Pickleball bridge DTOs; it must not reimplement Gherkin matching, mappings, browser behavior, service calls, semantic hook behavior, or Step Override matching/compilation.

Each live operation resolves the currently owned paused scenario, performs the bridge call for that scenario, and verifies afterward that the same process id, bridge runtime id, and scenario id remain active and paused. Normal live operations must not invoke Maven/Gradle, resynchronize the project, or restart the worker.

Phase 5 adds first-class Step Override authoring to the same facade. `compileStepOverride(id, regex, source)` sends a REPLACE-mode REGEX rule to the consumer worker; the Java source must contain `{{CLASS_NAME}}`, and the worker owns class naming, compilation, classloading, registry lifetime, matching, capture extraction, and handler execution. `stepOverrides()`, `removeStepOverride(id)`, and `clearStepOverrides()` operate only on the currently owned scenario. Workbench must not compile handlers in the controller JVM.

With an active override, raw Gherkin may be override-only and need not match ordinary consumer glue. If no override matches, normal Cucumber glue matching remains authoritative. Removing or clearing an override restores that fallback immediately without rebuilding or restarting the worker.

`live-check` is the direct acceptance probe for this contract. Against the Maven example consumer it executes consumer and Pickleball Gherkin, mutates/resolves the live mapping, performs the existing `%health-full-url` service call, reads browser evidence, compiles and replaces one generated Step Override, executes override-only Gherkin, removes the override, verifies fallback behavior, confirms one PID/runtime/scenario was retained, then resumes and requires a clean exit.
