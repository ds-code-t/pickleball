
# Pickleball Studio runtime bridge

Pickleball Studio can opt a managed test run into a private bridge between the Studio JVM and the consumer test JVM. The bridge lets Studio, MCP clients, and later GUI integrations inspect a live Pickleball scenario, pause it at a semantic control boundary, execute retry-friendly detached steps through the existing dynamic-control API, and resume normal traversal.

The bridge is **not** enabled by merely depending on Pickleball. Ordinary Maven/Gradle runs, normal Studio `maven_start` / `gradle_start` calls, and the desktop **Run Tests** action retain their existing behavior.

## Process boundary

Studio and Pickleball Core remain separate JVMs:

```text
Studio JVM
  RuntimeBridgeService
       |
       | loopback HTTP + bearer token
       v
consumer test JVM
  ControlBridgeRuntime
       |
       | scenario-thread command queue
       v
  DynamicControl / ControlRuntime
```

Studio never loads the consumer test runtime into its own classpath. The consumer bridge is bundled with the normal `tools.dscode:pickleball` artifact through the existing `pickleball-control-api` source module.

## Opt-in launch and discovery

`runtime_start` creates a Studio-owned session directory under:

```text
~/.pickleball/studio/bridge/<session-id>/
```

Studio generates a random bearer token and passes the following values only to that managed build process:

```text
PKB_STUDIO_BRIDGE_SESSION_DIR
PKB_STUDIO_BRIDGE_SESSION_ID
PKB_STUDIO_BRIDGE_TOKEN
PKB_STUDIO_BRIDGE_PAUSE_FIRST_SCENARIO
```

The consumer bridge is started lazily when Pickleball reaches its semantic control runtime. Without `PKB_STUDIO_BRIDGE_SESSION_DIR`, the bootstrap does not start a server.

Each participating consumer JVM binds an HTTP server to `127.0.0.1` on a random operating-system-assigned port and publishes:

```text
runtime-<runtime-id>.json
```

The descriptor contains protocol/runtime metadata and capabilities, but **never contains the bearer token**. Multiple test worker JVMs may publish descriptors into the same Studio session.

## Authentication and transport

Phase 3A uses protocol version `1`.

Every bridge request requires:

```text
Authorization: Bearer <session-token>
```

The bridge accepts loopback traffic only. The token is generated per Studio runtime session and retained by the owning Studio process. Bridge responses are marked `Cache-Control: no-store`.

The initial bridge endpoints are:

```text
GET  /v1/status
POST /v1/pause
POST /v1/resume
POST /v1/steps/execute
```

This protocol is internal to the Studio/Pickleball integration. MCP clients should use Studio's `runtime_*` tools instead of connecting to the consumer bridge directly.

## Scenario-thread execution

Pickleball's active `CurrentScenarioState` is thread-local. For that reason, an HTTP handler thread cannot safely call `DynamicControl.executeStep(...)` directly.

`ControlBridgeCoordinator` registers as an additive `ControlRuntime` observer. Incoming control work is queued, then executed by the real scenario thread when it reaches a semantic control hook. This preserves access to the active Cucumber/Pickleball state, glue, browser, services, mappings, and other live scenario resources.

The bridge observer does not replace the application's existing `ControlRuntime` global or thread-local handler:

- the normal handler still owns `ControlDecision` and value replacement;
- bridge observers are observation-only;
- bridge-triggered detached work still reaches the normal handler;
- observer re-entry is suppressed so bridge work does not recursively redispatch the bridge observer.

With no observer and no bridge environment, existing control-runtime behavior remains unchanged.

## Pause and resume

`runtime_pause` requests a pause at a semantic control hook. If no scenario has started yet, the request applies to the next observed active scenario.

Pauses use a finite lease:

- default lease: `120` seconds;
- maximum lease: `3600` seconds.

When the lease expires, scenario execution resumes automatically. A pause request that times out before reaching a hook is withdrawn; it does not remain armed for a later scenario.

`runtime_start` defaults `pauseFirstScenario` to `true`, which is useful for AI-controlled runs: the first scenario can become controllable before it advances through its normal step tree.

`runtime_resume` is idempotent.

## Parallel scenarios

A bridge process may observe more than one scenario thread. Phase 3A avoids guessing which one a controller intended.

A control command is accepted when:

- exactly one scenario is active; or
- exactly one scenario is already paused.

If several scenarios are active and none is uniquely selected by a pause, the bridge returns `UNAVAILABLE`. A later phase may add explicit scenario/thread targeting.

## Retry-friendly detached execution

`runtime_execute_step` uses the existing `DynamicControl.executeStep(...)` API on the selected scenario thread.

The result is intentionally data rather than a transport failure:

```text
SUCCESS
FAILED
UNAVAILABLE
```

A detached `FAILED` result does not by itself mark the scenario failed, matching the Phase 1 dynamic-control contract. This allows an AI or human controller to inspect a failed hypothesis and try another step.

The bridge returns only a safe textual representation of arbitrary step return values:

- value type;
- clipped `toString()` value;
- structured error type/message/stack trace.

It does not attempt generic serialization of arbitrary consumer objects.

Effects that happened before a failed detached call are not rolled back.

## Studio MCP flow

Phase 3A adds six MCP tools, bringing the Studio tool count to **26**:

```text
runtime_start
runtime_list
runtime_status
runtime_pause
runtime_resume
runtime_execute_step
```

A typical controller flow is:

```text
runtime_start
  -> process id + runtime session id

runtime_list
  -> wait until one or more consumer JVM descriptors appear

runtime_status
  -> inspect selected live runtime

runtime_pause
  -> pause if it is not already paused

runtime_execute_step
  -> inspect SUCCESS / FAILED / UNAVAILABLE
  -> retry another detached step when useful

runtime_resume
  -> continue normal scenario traversal
```

The existing managed-process tools remain the source for build lifecycle and output:

```text
process_status
process_output
process_cancel
```

Cancelling the managed build still terminates the owned process tree through the existing Studio process service.

## Build-tool behavior

`RuntimeBridgeService` uses the same build services as the rest of Studio:

- Gradle workspaces run through the checked-in Gradle Wrapper;
- Maven workspaces run through Studio's bundled Maven runtime;
- Gradle is preferred when a workspace is detected as both;
- default build arguments are `test`;
- the Phase 3A runtime-build timeout defaults to `3600` seconds.

The bridge environment is added only through the new opt-in managed-start overloads. Existing Maven/Gradle execution paths do not receive bridge variables.

Bridge metadata is separate from Pickleball execution RunVars. When a controller knows the intended Pickleball test settings, it should continue to supply those settings through the existing `pkb_runvars` controlled-run contract described in [AI Run Configuration](ai-run-configuration.md). `runtime_start` does not reconstruct or replace that configuration model.

## Current Phase 3A boundaries

Phase 3A establishes transport, discovery, pause/resume, live status, and one generic detached-step command. It does **not** yet add:

- GUI controls for live runtime sessions;
- explicit scenario/thread selection for parallel test execution;
- streaming hook/event history;
- dedicated browser, service-call, mapping, or screenshot bridge commands;
- mapping snapshot transfer through Studio;
- arbitrary object serialization across the JVM boundary;
- persistent runtime sessions after Studio exits;
- remote/non-loopback control.

Dedicated bridge commands should be added only when they provide clearer deterministic semantics than invoking the existing Pickleball step/control APIs.

See also [Dynamic Control API](dynamic-control-api.md) for the in-process retry-friendly execution and semantic hook contract consumed by this bridge.
