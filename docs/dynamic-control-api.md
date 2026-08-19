# Dynamic Control API

Pickleball exposes a small core interception contract plus a separately organized `pickleball-control-api` source module for dynamic tooling. The control API classes are bundled into the main `tools.dscode:pickleball` artifact; consumers do not add a second Maven dependency. The source module is intentionally independent of MCP, Spring AI, GUIs, and process orchestration.

## Artifact and compatibility

All consumers, including tooling that uses dynamic control, depend only on Pickleball. `pickleball-control-api` is an internal Gradle source module rather than a separately published Maven artifact.

Installing Pickleball with no control handler and never invoking the bundled control API preserves normal execution behavior. Dynamic execution, Mapping interception, bridge startup, browser/service evidence, breakpoints, and Step Overrides are opt-in.

## Dynamic execution and Mapping

`DynamicControl` executes Cucumber/Pickleball steps against the currently active Pickleball test context. Detached failures are returned as structured `FAILED` results instead of automatically failing the paused scenario, allowing controller tooling to inspect a failed hypothesis and try another action.

`MappingControl` provides retry-friendly access to the live `ParsingMap` / `NodeMap` structures, caller-defined Mapping contexts, scoped overrides, snapshots, and resolution explanation. Live mutations are deliberate and are not automatically rolled back.

The control API returns `SUCCESS`, `FAILED`, or `UNAVAILABLE` for exploratory operations. Calls without a required live Pickleball context return `UNAVAILABLE`.

## Semantic hooks

`ControlRuntime` exposes synchronous semantic hooks across scenario, step, phrase, Mapping, DOM, browser, driver, and service-call boundaries. Handlers may observe, pause by blocking, or use supported skip/value interception semantics. With no handler installed, original Pickleball behavior is retained.

The bridge coordinator is an additive observer and does not replace the application's normal control handler.

## Consumer-side Control Bridge

Pickleball bundles a loopback-only Control Bridge server/coordinator used by Workbench to operate against a live consumer scenario. The bridge is not enabled during normal consumer execution unless its session environment is present.

Canonical environment variables are:

```text
PKB_CONTROL_BRIDGE_SESSION_DIR
PKB_CONTROL_BRIDGE_SESSION_ID
PKB_CONTROL_BRIDGE_TOKEN
PKB_CONTROL_BRIDGE_PAUSE_FIRST_SCENARIO
```

For backward compatibility, Pickleball may accept the former `PKB_STUDIO_BRIDGE_*` names as deprecated input aliases. They are not canonical configuration and do not require the removed Studio application.

Each participating consumer JVM binds to `127.0.0.1` on an operating-system-assigned port and writes a runtime descriptor into the session directory. Requests require the session bearer token and responses are marked `Cache-Control: no-store`.

The bridge keeps live operations on the real scenario thread through `ControlBridgeCoordinator`. This preserves access to thread-local Cucumber/Pickleball state, glue, browser, services, mappings, and other scenario resources.

Bridge capabilities include:

- runtime/status and active-scenario discovery;
- bounded semantic events;
- finite-lease pause/resume;
- retry-friendly detached step execution;
- Mapping get/put/resolve/snapshot/restore;
- browser page/screenshot evidence;
- Pickleball-native element inspection;
- existing Pickleball service-call execution/evidence;
- semantic breakpoint management;
- scenario-scoped Step Override management.

Workbench owns the controller-side bridge client. MCP and Swing access these capabilities through `WorkbenchServices` / `WorkbenchController`; they do not connect to the bridge independently or implement a second runtime.

## Scenario targeting and finite pauses

A consumer runtime may observe more than one scenario thread. Controller calls may target a scenario id discovered from the bridge. A wrong or stale id returns `UNAVAILABLE`; the bridge does not guess.

Pauses and breakpoint pauses use finite leases. Expiry resumes execution automatically. Workbench renews the pause it owns while its persistent interactive worker remains active and resumes before clean shutdown.

## Semantic event evidence

The bridge retains bounded immutable semantic hook metadata rather than arbitrary consumer object graphs. Event reads are read-only and do not require the scenario-thread command queue, so reading evidence cannot consume a hook or block a paused scenario.

## Browser and service investigation

`ElementControl.inspect(...)` resolves through the active Pickleball `ExecutionDictionary`. It uses Pickleball element vocabulary rather than introducing raw CSS/XPath as a controller language. Inspection requires an existing scenario-owned browser and returns `UNAVAILABLE` rather than creating one.

`ServiceCallControl.execute(selector)` runs the same reusable service-call component selected by normal `CALL:` semantics. Existing Pickleball discovery, Mapping, request/configuration processing, REST/SOAP execution, response mapping, and failure behavior remain authoritative.

## Semantic breakpoints

Semantic breakpoints are temporary filters over existing `ControlHook` boundaries. They may filter by scenario, hook, hook signature, current step text, and current phrase text. They use the same finite pause lane as ordinary control pauses and disappear with the consumer runtime.

They are semantic Pickleball interception points, not arbitrary Java source breakpoints.

## Step Overrides

The consumer worker also owns scenario-scoped Step Overrides. Workbench can compile/register REGEX/REPLACE rules through the bridge; matching and execution occur before ordinary Cucumber glue fallback. When no override matches, normal Cucumber matching remains authoritative. Removing or clearing an override restores ordinary fallback without restarting the worker.

Worker-side compilation requires `javax.tools.JavaCompiler`. Workbench sends a Java source template containing `{{CLASS_NAME}}`; the worker owns generated naming, compilation, classloading, matching, captures, replacement, and cleanup.

## Architecture boundary

The control API and bridge intentionally remain independent of MCP, Spring, GUI frameworks, generic project IDE behavior, and build orchestration. Pickleball owns live execution semantics. Workbench is the external controller/adaptation layer.
