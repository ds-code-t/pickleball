# Pickleball Studio runtime bridge

Pickleball Studio can opt a managed test run into a private bridge between the Studio JVM and the consumer test JVM. The bridge lets Studio desktop and MCP clients inspect live Pickleball scenarios, target one scenario during parallel execution, pause it at a semantic control boundary, inspect or mutate live mappings, capture and explicitly restore bounded Studio-owned mapping snapshots, execute retry-friendly detached steps through the existing dynamic-control API, read bounded semantic execution evidence, and resume normal traversal.

The bridge is **not** enabled by merely depending on Pickleball. Ordinary Maven/Gradle runs, normal Studio `maven_start` / `gradle_start` calls, and the desktop **Run Tests** action retain their existing behavior. The desktop **Runtime > Runtime Control... > Start Control Run** path is explicitly bridge-enabled.

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

`runtime_start` and the desktop **Start Control Run** action create a Studio-owned session directory under:

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

The current bridge uses protocol version `1`.

Every bridge request requires:

```text
Authorization: Bearer <session-token>
```

The bridge accepts loopback traffic only. The token is generated per Studio runtime session and retained by the owning Studio process. Bridge responses are marked `Cache-Control: no-store`.

The current bridge endpoints are:

```text
GET  /v1/status
GET  /v1/scenarios
GET  /v1/events
POST /v1/pause
POST /v1/resume
POST /v1/steps/execute
POST /v1/mappings/get
POST /v1/mappings/put
POST /v1/mappings/snapshot
POST /v1/mappings/restore
POST /v1/mappings/resolve
```

The additive Phase 3F descriptor capabilities are `mapping_snapshot` and `mapping_restore`; protocol version `1` is unchanged.

This protocol is internal to the Studio/Pickleball integration. MCP clients should use Studio's `runtime_*` tools instead of connecting to consumer bridge ports directly. The desktop UI uses the same `RuntimeBridgeService` as MCP and does not connect to bridge ports independently.

## Scenario-thread execution

Pickleball's active `CurrentScenarioState` is thread-local. For that reason, an HTTP handler thread cannot safely call `DynamicControl.executeStep(...)` directly.

`ControlBridgeCoordinator` registers as an additive `ControlRuntime` observer. Incoming control work is queued, then executed by the real scenario thread when it reaches a semantic control hook. This preserves access to the active Cucumber/Pickleball state, glue, browser, services, mappings, and other live scenario resources.

The bridge observers do not replace the application's existing `ControlRuntime` global or thread-local handler:

- the normal handler still owns `ControlDecision` and value replacement;
- bridge observers are observation-only;
- bridge-triggered detached work still reaches the normal handler;
- observer re-entry is suppressed so bridge work does not recursively redispatch bridge observers.

With no observer and no bridge environment, existing control-runtime behavior remains unchanged.

## Pause and resume

`runtime_pause` and the desktop **Pause** action request a pause at a semantic control hook. If no scenario has started yet, an unqualified pause request applies to the next observed active scenario.

Pauses use a finite lease:

- default lease: `120` seconds;
- maximum lease: `3600` seconds.

When the lease expires, scenario execution resumes automatically. A pause request that times out before reaching a hook is withdrawn; it does not remain armed for a later scenario.

`runtime_start` and desktop controlled runs default `pauseFirstScenario` to `true`, which makes the first scenario controllable before it advances through normal traversal.

`runtime_resume` and the desktop **Resume** action are idempotent.

## Parallel scenarios and explicit targeting

A bridge process may observe more than one scenario thread. `runtime_scenarios` returns every currently active scenario with its stable scenario id for that scenario run, thread id, current step/phrase text, latest semantic hook, and pause state.

`runtime_pause`, `runtime_resume`, `runtime_execute_step`, and live mapping capture/read/write/resolve operations accept an optional `scenarioId`. When several scenarios are active, callers should pass the id returned by `runtime_scenarios`. A wrong or stale id returns `UNAVAILABLE`; the bridge does not guess.

The desktop Runtime Control window renders the discovered runtimes and scenarios as selectors. Its Pause, Resume, detached-step, and mapping actions target the selected scenario id. The original unqualified API behavior remains for compatibility when exactly one scenario is active or exactly one scenario is already uniquely paused.

## Bounded semantic event history

Phase 3D adds read-only semantic hook history for each participating consumer runtime.

`ControlBridgeEventRecorder` is an additive observation-only `ControlRuntime` observer. It is registered **before** the pausing coordinator, so the hook at which a scenario becomes paused is recorded before that same scenario thread blocks in the pause loop.

The recorder stores only immutable clipped metadata:

- monotonically increasing runtime sequence;
- timestamp;
- scenario thread id;
- scenario id and name;
- hook name;
- hook signature;
- current step text;
- current phrase text.

It never retains or serializes `ControlEvent.target`, `ControlEvent.arguments`, browser objects, mapping objects, service objects, or other consumer object graphs.

Retention is intentionally bounded:

- maximum retained events per consumer runtime: `2048`;
- default page size: `100`;
- maximum page size: `500`;
- event text fields are clipped to `2048` characters.

The bridge endpoint is:

```text
GET /v1/events
```

Supported query parameters are:

```text
scenarioId=<optional active-or-completed scenario id>
afterSequence=<exclusive runtime sequence cursor; default 0>
limit=<1..500; default 100>
```

The response contains:

```text
events
nextSequence
earliestAvailableSequence
latestSequence
gap
hasMore
```

`nextSequence` is the exclusive cursor to use on the next read. When additional matching events remain inside the current retained window, `hasMore=true` and `nextSequence` is the last returned sequence. Otherwise it advances to the runtime's latest known sequence so a filtered client does not repeatedly rescan unrelated events.

`gap=true` means the supplied nonzero cursor is older than the earliest event still retained. Clients must treat that as incomplete history instead of assuming that no events occurred.

The event ring is runtime-scoped rather than lane-scoped, so retained events remain readable after an individual scenario completes, until they are evicted by the bounded ring or the consumer runtime bridge closes.

Event history is read directly from immutable snapshots and does **not** require the scenario-thread command queue. Reading evidence therefore cannot consume a control hook or block a paused scenario.

`ControlRuntime` intentionally suppresses recursive observer dispatch caused by work initiated from an observer. For that reason, the semantic event ring records the normal traversal boundaries observed by the bridge, but does not claim to enumerate every nested hook fired inside a detached bridge command. The returned detached-command result remains authoritative for that exploratory action.

## Live mapping control

Phase 3B adds direct mapping operations on the selected scenario thread:

```text
runtime_mapping_get
runtime_mapping_put
runtime_mapping_resolve
```

`runtime_mapping_get` reads one key from a live `NodeMap` reference such as `OVERRIDE`, `RUN`, `STEP`, `PARENT.STEP`, or `SCENARIO`. `runtime_mapping_put` writes one value to that live map. `runtime_mapping_resolve` resolves an input through the scenario's current `ParsingMap`, preserving the normal Pickleball resolution order.

`runtime_mapping_put` accepts the value as one JSON literal so its intended type crosses the Studio/process boundary explicitly. Examples include:

```text
"READY"
3
true
null
[1,2]
{"a":1}
```

Mapping results use a safe value envelope containing the runtime type, a clipped text representation, and a structured `jsonValue` only when the value is JSON-compatible. Arbitrary consumer objects are not generically serialized. This keeps direct mapping inspection deterministic without turning the bridge into an unrestricted object serializer.

These operations mutate the same live maps used by normal execution. They are intentionally not rolled back automatically.

### Studio-owned mapping snapshots

Phase 3F adds explicit capture/list/restore around those live mapping operations:

```text
runtime_mapping_snapshot
runtime_mapping_snapshots
runtime_mapping_restore
```

The consumer bridge endpoints are:

```text
POST /v1/mappings/snapshot
POST /v1/mappings/restore
```

Capture runs on the selected scenario thread and materializes one referenced live `NodeMap` as JSON object state plus map reference, map type, concrete class, and sorted data-source metadata. The bridge itself does not retain snapshot ids or history. On a successful capture, `RuntimeBridgeService` stores the materialized state in Studio and returns a generated `snapshotId`.

Studio snapshot history is deliberately bounded and ephemeral:

- maximum retained snapshots: **50 per Studio runtime session**;
- maximum materialized values payload per captured map: **512 KiB of compact UTF-8 JSON**;
- retention lives only in the running Studio JVM;
- snapshots are not written to the bridge descriptor or consumer filesystem;
- snapshots are cleared when Studio's `RuntimeBridgeService` closes;
- listing may filter by runtime id, scenario id, or map reference.

Capture may inspect any `NodeMap` implementation, but only an exact ordinary `NodeMap` is marked `restorable=true`. Specialized subclasses such as live data-context maps are materialized for inspection only because serialized JSON cannot recreate their cursor/reference/live-object semantics.

Restore is explicit rather than automatic rollback. Studio resolves the `snapshotId` to its originally captured runtime, scenario, and map reference, then sends that materialized state back through the scenario-thread command queue. Restore succeeds only when the current target is still an ordinary `NodeMap` whose concrete class, map type, and data-source metadata match the capture. It clears and repopulates that **same live map object**, preserving object identity already held by active `ParsingMap` instances. Map-type metadata is reconstructed from the live map rather than trusted from the transferred JSON payload.

A snapshot can remain listed in Studio after its scenario or consumer runtime exits, until bounded eviction or Studio shutdown. A later restore then returns `UNAVAILABLE`; Studio does not recreate a dead scenario or consumer JVM.

The desktop Mapping tab is a direct adapter over these same operations. In addition to Get/Put/Resolve it can **Snapshot** the current map, select retained snapshots for the current runtime/scenario, and **Restore Snapshot** by id. An inspection-only snapshot is labeled accordingly. The desktop does not maintain a second snapshot store or restoration algorithm.

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

Effects that happened before a failed detached call are not rolled back. The desktop detached-step action displays the same logical result and does not reinterpret `FAILED` as a Swing/process failure.

## Studio MCP flow

Phase 3F adds three mapping-snapshot tools to the existing runtime contract, bringing Studio to **34** MCP tools. Runtime control/evidence includes:

```text
runtime_start
runtime_list
runtime_status
runtime_scenarios
runtime_events
runtime_pause
runtime_resume
runtime_execute_step
runtime_mapping_get
runtime_mapping_put
runtime_mapping_resolve
runtime_mapping_snapshot
runtime_mapping_snapshots
runtime_mapping_restore
```

`runtime_events` is a read-only adapter over `RuntimeBridgeService.events(...)`. It accepts the runtime session id, runtime id, optional scenario id, optional exclusive `afterSequence` cursor, and optional page limit.

A typical controller flow is:

```text
runtime_start
  -> process id + runtime session id

runtime_list
  -> wait until one or more consumer JVM descriptors appear

runtime_scenarios
  -> choose a scenario id when parallel scenarios are active

runtime_events
  -> read retained semantic context before taking action
  -> retain nextSequence for the next evidence read

runtime_pause
  -> pause the chosen scenario if it is not already paused

runtime_events
  -> inspect the semantic boundary where the pause was reached

runtime_mapping_get / runtime_mapping_resolve
  -> inspect live data and resolution state

runtime_mapping_snapshot
  -> capture a restorable baseline before deliberate mutation when useful

runtime_mapping_put
  -> test a typed mapping override

runtime_mapping_restore
  -> explicitly return an ordinary live NodeMap to the captured baseline when desired

runtime_execute_step
  -> inspect SUCCESS / FAILED / UNAVAILABLE
  -> retry another detached step when useful

runtime_resume
  -> continue that scenario's normal traversal
```

The existing managed-process tools remain the source for build lifecycle and output:

```text
process_status
process_output
process_cancel
```

Cancelling the managed build still terminates the owned process tree through the existing Studio process service.

## Desktop runtime-control flow

Phase 3C exposes the same runtime control service through the Swing desktop UI.

Open:

```text
Runtime > Runtime Control...
```

The modeless Runtime Control window provides:

- **Start Control Run** — starts a managed `test` build through `RuntimeBridgeService`, with first-scenario pause enabled;
- **Cancel Run** — delegates to `ManagedProcessService` for the controlled build process;
- runtime and scenario selectors backed by bridge descriptor/scenario discovery;
- live runtime/scenario status refresh;
- **Pause** and **Resume** for the selected scenario;
- retry-friendly detached-step text plus optional argument execution;
- mapping Get/Put/Resolve using the Phase 3B mapping APIs plus Phase 3F Snapshot/Restore backed by Studio-owned snapshot ids;
- bounded incremental stdout/stderr display for the controlled build;
- bounded operation-result display using the same `SUCCESS` / `FAILED` / `UNAVAILABLE` semantics returned to MCP.

`StudioDesktopSession` is the desktop facade over `RuntimeBridgeService` and `ManagedProcessService`; `RuntimeControlDialog` is only a Swing adapter. The dialog does not build HTTP requests, own bearer tokens, construct Maven/Gradle commands, or call Pickleball Core directly.

The existing main-window **Run Tests** action remains bridge-free. Closing or hiding the Runtime Control window does not silently cancel the managed build. Cancelling uses the explicit **Cancel Run** action, while pause safety remains governed by the finite bridge lease. Closing Pickleball Studio closes `RuntimeBridgeService`, resumes discovered paused scenarios best-effort, then terminates owned managed processes.

## Desktop runtime-event evidence

Phase 3E adds an **Events** tab to the same modeless Runtime Control window. It is a read-only Swing adapter over `StudioDesktopSession.runtimeEvents(...)` and therefore over `RuntimeBridgeService.events(...)`; it does not connect to the consumer bridge independently or implement another event recorder.

The desktop event view follows the selected runtime and shows the whole runtime timeline by default. **Selected scenario only** changes the service query to the currently selected active scenario id. Switching sessions, runtimes, or the effective scenario filter resets the local cursor to zero so evidence from different streams is not merged.

The client-side display intentionally has a separate bound from bridge retention:

- consumer runtime retention: `2048` events;
- bridge read page: up to `500` events;
- desktop loaded-event display: `1000` events.

If the bridge reports `gap=true`, the Events tab keeps that warning visible and shows the earliest available sequence. **Clear View** removes only loaded Swing text/events and retains the current `nextSequence` cursor. **Reload Retained** clears the view and resets the cursor to `0`, re-reading whatever still exists in the consumer runtime ring. Neither action changes consumer-side retention.

When a page reports `hasMore=true`, the desktop continues reading immediately from `nextSequence`; ordinary periodic refresh then follows new events. Auto-tail is optional so a user inspecting older visible events is not forced back to the end on every refresh.

Phase 3E changes no bridge endpoint, capability, protocol version, or MCP tool. Phase 3F later adds mapping snapshot/restore without changing protocol version `1`; it is an additive capability extension.

## Build-tool behavior

`RuntimeBridgeService` uses the same build services as the rest of Studio:

- Gradle workspaces run through the checked-in Gradle Wrapper;
- Maven workspaces run through Studio's bundled Maven runtime;
- Gradle is preferred when a workspace is detected as both;
- default build arguments are `test`;
- the runtime-build timeout defaults to `3600` seconds.

The bridge environment is added only through the opt-in managed-start overloads. Existing Maven/Gradle execution paths do not receive bridge variables.

Bridge metadata is separate from Pickleball execution RunVars. When a controller knows the intended Pickleball test settings, it should continue to supply those settings through the existing `pkb_runvars` controlled-run contract described in [AI Run Configuration](ai-run-configuration.md). `runtime_start` and the desktop controlled-run action do not reconstruct or replace that configuration model.

## Phase 3G browser evidence

Phase 3G adds two additive protocol-1 capabilities and endpoints:

- `browser_page` / `POST /v1/browser/page`
- `browser_screenshot` / `POST /v1/browser/screenshot`

Both accept `scenarioId` plus the normal scenario-command `timeoutSeconds`, use the existing explicit/unique scenario selection rules, and execute on that scenario thread through `ControlBridgeCoordinator`. They call `BrowserSteps.getCurrentDriverIfPresent()`, so evidence reads never create or register a browser. No browser means a logical `UNAVAILABLE` result rather than an implicit driver launch.

`browser_page` returns bounded current-page evidence: URL, title, current/all window handles, current window dimensions, DOM source, and a truncation flag. DOM source is capped at 256 Ki characters. `browser_screenshot` returns at most 5 MiB of PNG bytes encoded only for the private loopback transport. `RuntimeBridgeService` immediately decodes a successful screenshot into the session `evidence` directory; the `runtime_browser_screenshot` MCP result exposes that file path rather than the transport base64. Studio keeps at most 50 browser screenshot files per runtime session and removes the oldest first.

The browser evidence contract is intentionally read-only. Existing detached steps remain the mutation/control path. Phase 3G does not add a CSS/XPath selector API because that would compete with Pickleball's existing execution-dictionary locator semantics; element-targeted structured inspection should be added only after a shared resolver is available.

The two new MCP tools bring the Studio total from 34 to **36**.

## Current Phase 3G boundaries

Phase 3G adds read-only browser page/screenshot evidence on top of Phase 3F mapping snapshots. It does **not** yet add:
- unbounded or persisted event history after the consumer runtime exits;
- shared Pickleball element-targeted browser inspection or dedicated service-call bridge commands beyond generic detached step execution;
- persistent mapping snapshots across Studio restarts or exact restoration of specialized live `NodeMap` subclasses;
- arbitrary consumer-object serialization across the JVM boundary;
- persistent runtime sessions or desktop control history after Studio exits;
- remote/non-loopback control;
- syntax highlighting, completion, semantic Java resolution, Gherkin step binding, or other editor features unrelated to the runtime bridge.

Dedicated bridge commands should be added only when they provide clearer deterministic semantics than invoking the existing Pickleball step/control APIs.

See also [Dynamic Control API](dynamic-control-api.md) for the in-process retry-friendly execution and semantic hook contract consumed by this bridge.
