# Pickleball Workbench

Pickleball Workbench is the external controller for interactive Pickleball execution and investigation. Its executable contains controller code, GUI/MCP adapters, synchronization support, JSON transport, and the neutral wire protocol—but no Pickleball core/runtime. Real execution occurs only in a separate consumer worker using the consumer project's compiled output and resolved test runtime.

Workbench replaces the former Pickleball Studio application. The supported architecture is deliberately execution-oriented: project synchronization, a persistent consumer worker, live runtime control, Mapping, browser/service evidence, semantic breakpoints, Step Override authoring, lightweight non-Spring MCP stdio, and a Swing UI over the same service seam.

## Architecture

Source dependencies and distribution are strictly separated:

```text
pickleball core/worker --------> JDK-only control protocol
pickleball-workbench ----------> JDK-only control protocol
published pickleball JAR ------> opaque Workbench executable bytes
```

The distribution arrow is an assembly input, not a Workbench-to-core Java dependency. **Pickleball may contain Workbench; Workbench must not contain Pickleball.** Separate JVMs are required, but they are not sufficient: dependency graphs, class visibility, JAR entries, nested JARs, service providers, and runtime origins are checked too.

Pickleball owns scenario execution semantics, Cucumber integration, DynamicControl/Gherkin execution, Mapping, browser/service behavior, the consumer-side Control Bridge, semantic hooks/breakpoints, Step Overrides, and woven Cucumber/AspectJ behavior.

Workbench owns synchronization, `.pickleball/workbench/` disposable state, worker lifecycle, the protocol client, `WorkbenchLiveSession`, `WorkbenchServices` / `WorkbenchController`, MCP stdio, the headless live-scenario presentation model, and the Swing adapter. It does not import the worker entry point; it launches the protocol's class-name string on the captured consumer classpath.

`pickleball-control-protocol` owns only immutable wire records, request/response envelopes, transport constants, capabilities, and version/minimum-version negotiation. Worker-side bridge server/coordinator/bootstrap and all translation to runtime operations remain in Pickleball core.

MCP and Swing are adapters over the same Workbench service seam. They must not introduce a second runtime implementation.

The canonical worker bridge environment is:

```text
PKB_CONTROL_BRIDGE_SESSION_DIR
PKB_CONTROL_BRIDGE_SESSION_ID
PKB_CONTROL_BRIDGE_TOKEN
PKB_CONTROL_BRIDGE_PAUSE_FIRST_SCENARIO
```

Pickleball may accept `PKB_STUDIO_BRIDGE_*` as deprecated compatibility input aliases only. Workbench emits only the neutral names.

## Launch from a consumer project

The normal `tools.dscode:pickleball:<version>` test dependency already carries the matching controller at:

```text
META-INF/pickleball/workbench/pickleball-workbench.jar
```

Run the small launcher from the consumer test classpath. For Maven consumers, this command requires no cache path, separate Workbench dependency, or separately selected version:

```bash
mvn -q org.codehaus.mojo:exec-maven-plugin:3.5.0:java \
  -Dexec.mainClass=tools.dscode.launcher.PickleballWorkbenchLauncher \
  -Dexec.classpathScope=test \
  "-Dexec.args=ui ."
```

```powershell
mvn -q org.codehaus.mojo:exec-maven-plugin:3.5.0:java "-Dexec.mainClass=tools.dscode.launcher.PickleballWorkbenchLauncher" "-Dexec.classpathScope=test" "-Dexec.args=ui ."
```

With no launcher arguments, `ui` and the current directory are selected automatically. Other Workbench commands are forwarded in the same form, for example `"-Dexec.args=sync ."` or `"-Dexec.args=mcp ."`.

Gradle consumers can expose the same dependency-owned launcher without resolving a cache path or adding a Workbench dependency:

```groovy
tasks.register('pickleballWorkbench', JavaExec) {
    classpath = sourceSets.test.runtimeClasspath
    mainClass = 'tools.dscode.launcher.PickleballWorkbenchLauncher'
    args 'ui', projectDir.absolutePath
}
```

Run it with `./gradlew pickleballWorkbench` (or `gradlew.bat pickleballWorkbench`). The task uses the consumer's resolved test runtime only to locate the tiny launcher and nested bytes; actual controller code still starts in a separate `java -jar` process.

The launcher reads the nested payload, limits its size, calculates SHA-256, and extracts it atomically to:

```text
.pickleball/workbench/controller/<sha256>/pickleball-workbench.jar
```

It verifies existing/extracted bytes, starts `java -jar` in a new Workbench JVM, inherits stdio, and propagates non-zero exit status. The content-addressed path prevents a stale payload from silently replacing the version carried by the consumer dependency.

## Maintainer build and direct run

Build the standalone controller and strict isolation checks:

```powershell
.\gradlew.bat :pickleball-workbench:build verifyStrictControllerIsolation
```

The executable is:

```text
pickleball-workbench/build/libs/pickleball-workbench-<version>.jar
```

Synchronize a consumer project before starting a worker manually from repository output:

```powershell
$workbenchJar = ".\pickleball-workbench\build\libs\pickleball-workbench-<version>.jar"
java -jar $workbenchJar sync ".\maven-consumer-project"
```

Synchronization uses the selected project wrapper to establish compiled output and the effective test runtime classpath. `.pickleball/workbench/base/classes` is provenance only; the worker runs against the merged `.pickleball/workbench/live/classes` state plus captured external dependencies.

At worker connection time, Workbench requires a different PID, compatible protocol range and capabilities, a Pickleball code source that is exactly one captured consumer classpath entry, the synchronized Pickleball version (except explicit development output), and no Workbench controller artifact on the worker classpath. It fails clearly instead of falling back to a bundled runtime.

## Swing UI

Start Workbench for one consumer project:

```powershell
java -jar $workbenchJar ui ".\maven-consumer-project"
```

The Swing UI is a presentation adapter over the same `WorkbenchServices` / `WorkbenchController` seam used by MCP. It does not own a second worker manager, bridge client, Mapping implementation, Gherkin execution engine, or Pickleball runtime model.

### Player-style layout

The primary workspace is now arranged as an interactive scenario player:

```text
┌─────────────────────────────────────────────────────────────────────┐
│ Project / readiness        ⏮  ◀  ▶  ⏸  ■          Player status  │
├───────────────────────────────┬─────────────────────────────────────┤
│ LIVE SCENARIO EDITOR          │ Mapping | Terminal | Diagnostic Log │
│                               │                                     │
│ Feature: ...                  │ selected right-side workspace       │
│ Scenario: ...                 │                                     │
│ ▶ next playhead step          │                                     │
│   selected/other line         │                                     │
├───────────────────────────────┤                                     │
│ Step Editor / Command   ▶     │                                     │
│ [ live command text       ]   │                                     │
└───────────────────────────────┴─────────────────────────────────────┘
│ Workbench/session activity                                         │
└─────────────────────────────────────────────────────────────────────┘
```

The left side contains only the Live Scenario Editor and compact Step Editor / Command. The right side has one tabbed workspace for Mapping, Terminal, and Diagnostic Log Explorer. Low-level lifecycle controls are available from the **Session** menu. Existing investigation tools are available from **Tools > Advanced Controls** so the underlying capabilities are preserved without dominating the normal workflow.

### Live scenario buffer and player state

`LiveScenarioPlayer` is a headless Workbench-side presentation model. It owns only:

- stable line identities independent of display line number;
- the live session buffer;
- selected line;
- playhead insertion point;
- pending/executed/failed presentation status;
- player states `STOPPED`, `PAUSED`, `RUNNING`, and `WAITING_FOR_STEP`.

Selection and playhead are independent. Selecting another line does not move the playhead. New Step Editor commands are inserted at the playhead insertion point, not at the text caret or selected line.

The initial buffer is an interactive session buffer; it is not automatically written back to consumer `.feature` files.

Step Editor gestures are intentionally explicit:

```text
Enter       insert a new command at the playhead
Ctrl+Enter  update the selected pending executable step
▶           execute the Step Editor text in isolation through the existing live service
```

Executed or failed buffer steps cannot be edited in place in this phase because the UI must not imply that browser, service, or other external side effects were undone.

The **First** and **Step Back** controls are navigation-only in this phase. They do not claim to rewind Pickleball runtime state or undo external side effects.

### Phase 1 execution boundary

The current player-style increment establishes the new layout and headless player/buffer state without inventing new Pickleball runtime semantics.

The main Play/Pause/Stop controls currently update the buffer/player state model only. Automatic buffered execution, wait-at-end execution, and add-and-continue behavior are the next implementation phase. That loop must be wired through an explicit Pickleball-owned Gherkin/runtime contract; Swing must not strip `Given`/`When`/`Then`, create a second step matcher, or otherwise guess how displayed Gherkin maps to detached step execution.

The small Step Editor Play button continues to use the existing `WorkbenchServices.executeStep` contract unchanged. It pauses the main player state before isolated execution and does not automatically resume it afterward.

### Mapping tab

There is no GUI-defined `Current Scope` concept.

The target Mapping design is a single NodeMap selector populated from the real `ParsingMap` associated with the selected step, with common NodeMaps remaining available across the live scenario. The GUI must not hard-code names or recreate inheritance rules.

The Phase 1 UI therefore leaves the NodeMap selector unavailable until the required Pickleball-side ParsingMap inspection contract exists. Existing Mapping get/put/resolve controls remain available as compatibility controls. The Swing Mapping put control continues to send entered values as text; MCP continues to support arbitrary JSON-compatible Mapping values through the shared service method.

Structural NodeMap browsing/mutation is a later phase and must be implemented against real worker-side Pickleball state.

### Terminal and Diagnostic Log Explorer

The Terminal tab currently displays Workbench UI activity only. Worker log streaming, level filtering, search, and auto-scroll belong to the Terminal phase and must use the appropriate worker/Workbench logging source without violating the MCP stdout contract.

The Diagnostic Log Explorer tab is intentionally a placeholder in Phase 1. Its implementation must reuse Pickleball's retained diagnostic model and follow the existing evidence escalation order:

1. `run-catalog.json`
2. selected `run-index.json` / `clusters.json`
3. scenario `summary.json`
4. relevant `events.jsonl`
5. existing comparison/fingerprint metadata
6. PNG only when visual content must be inspected
7. raw trace only when structured evidence is insufficient

The Swing UI must not create a competing diagnostic storage format or fake retained-run data.

### Existing advanced capabilities

The redesign preserves the underlying existing capabilities:

- selected project display and synchronization/status refresh;
- synchronize, start worker, restart fresh worker without rebuilding, and stop worker;
- worker PID/runtime/scenario/pause status;
- live raw Gherkin execution;
- Mapping get, put, and resolve;
- incremental semantic-event display with timestamp, hook, step/phrase, and signature detail;
- Step Override list, worker-side compile/replace, remove, and clear;
- browser page evidence and lightweight PNG screenshot display;
- existing Pickleball service-call execution/evidence;
- semantic breakpoint list, add, remove, and clear with hook/filter/one-shot/finite-lease controls;
- clean Workbench shutdown when the window closes.

The Step Override editor sends its source template unchanged to the worker. The source must contain `{{CLASS_NAME}}`; generated class naming, compilation, classloading, rule registration, matching, captures, replacement, and cleanup remain worker-side Pickleball responsibilities. Browser page, screenshot, service-call, event, and breakpoint controls expose existing bridge contracts rather than reimplementing them in Swing.

Synchronization, worker actions, live bridge calls, Mapping operations, event refresh, Step Override actions, browser/screenshot evidence, service calls, and breakpoint actions run off the Swing Event Dispatch Thread. Live controls are enabled only while the Workbench-owned worker is running and paused.

The UI is intentionally not a project IDE, general feature-file editor, generic process manager, generic Maven/Gradle task runner, source navigator, or collaboration system.

## MCP stdio

Start the lightweight non-Spring MCP server for a synchronized consumer project:

```powershell
java -jar $workbenchJar mcp ".\maven-consumer-project"
```

The server uses the official Java MCP SDK core and stdio transport with the Jackson 2 JSON adapter. MCP dependencies are Workbench-only and are shaded into the executable companion. Workbench deliberately does not use Spring Boot, Spring Framework, Spring AI, WebMVC, or Tomcat.

### Stdout contract

In MCP mode:

```text
stdout = MCP JSON-RPC only
```

Workbench reserves the original stdout stream for MCP before constructing the controller/SDK and redirects ordinary `System.out` output to stderr. Worker stdout/stderr are independently captured under:

```text
.pickleball/workbench/logs/
```

Do not add banners, normal logging, worker output, test output, or diagnostic messages to MCP stdout.

### MCP tools

The MCP server is an adapter over `WorkbenchServices` / `WorkbenchController`, which in turn delegates live operations to `WorkbenchLiveSession`. It does not implement a second Pickleball runtime.

Project and worker lifecycle:

```text
workbench_sync
workbench_sync_status
workbench_worker_start
workbench_worker_restart
workbench_worker_stop
workbench_worker_status
```

Live runtime and Mapping:

```text
workbench_execute_step
workbench_mapping_get
workbench_mapping_put
workbench_mapping_resolve
workbench_mapping_snapshot
workbench_mapping_restore
workbench_events
```

Browser/service evidence:

```text
workbench_browser_page
workbench_browser_screenshot
workbench_element_inspect
workbench_service_call
```

Semantic breakpoints:

```text
workbench_breakpoint_list
workbench_breakpoint_add
workbench_breakpoint_remove
workbench_breakpoint_clear
```

Step Override authoring:

```text
workbench_step_override_list
workbench_step_override_compile
workbench_step_override_remove
workbench_step_override_clear
```

`workbench_step_override_compile` sends the Java source template to the consumer worker. The source must contain `{{CLASS_NAME}}`; worker-side Pickleball remains responsible for compilation, generated classloaders, matching, replacement, captures, and execution.

Controller/runtime failures are returned as MCP tool results with `isError=true`. They are not printed as arbitrary protocol output.

## Scope boundary

Workbench MCP and Swing intentionally do not expose a generic IDE or build system. They do not add generic file editing/search, arbitrary process management, generic Maven/Gradle task execution, Gradle Tooling API project browsing, source navigation, or collaboration tools.

## Dependency and artifact checks

The build proves the controller boundary with `verifyStrictControllerIsolation` and its component tasks:

- `pickleball-control-protocol` has no non-JDK dependency;
- the only Workbench project dependency is `pickleball-control-protocol`;
- the Workbench compile/runtime graph contains no root Pickleball, behavioral control API, Cucumber, Selenium, or REST-assured path;
- the Workbench executable contains its controller, GUI, MCP, protocol client, and runtime isolation guard;
- top-level and nested Workbench entries and service descriptors contain no core/worker implementation or nested Pickleball runtime;
- the published Workbench POM has no dependencies;
- the MCP convenience artifact / Jackson 3 path is not used;
- the outer Pickleball JAR contains exactly one opaque Workbench payload whose bytes equal the standalone output;
- Workbench/MCP entries are not flattened into the outer runtime namespace; and
- the nested payload has the expected Workbench `Main-Class`.

Report the executable size and resolved MCP SDK artifacts with:

```powershell
.\gradlew.bat :pickleball-workbench:reportWorkbenchMcpImpact
```

The direct MCP dependencies are:

```text
io.modelcontextprotocol.sdk:mcp-core:2.0.0
io.modelcontextprotocol.sdk:mcp-json-jackson2:2.0.0
```

## Manual UI acceptance for the Phase 1 player foundation

```powershell
$workbenchJar = ".\pickleball-workbench\build\libs\pickleball-workbench-<version>.jar"
java -jar $workbenchJar ui ".\maven-consumer-project"
```

Use the UI-owned worker for runtime checks; do not run `worker-check` or `live-check` concurrently with the UI.

1. Verify the top-level layout has the Live Scenario Editor and compact Step Editor on the left, and exactly Mapping / Terminal / Diagnostic Log Explorer on the right.
2. Insert multiple commands with Enter and verify each is inserted at the visible playhead while selection can remain on another line.
3. Select a pending command, change its text, press Ctrl+Enter, and verify its displayed line updates without changing its stable position semantics.
4. Use First and Step Back and verify the playhead indicator moves independently from the selection. Treat these as navigation-only; no runtime rewind is claimed.
5. Use Play/Pause/Stop and verify player presentation states, including `Waiting for next step...` when Play has no next buffered command. Do not treat this as Phase 2 automatic runtime execution.
6. Open **Session**, synchronize/start a worker, select or enter a valid existing live raw Gherkin command, click the small Step Editor Play button, and verify it delegates isolated execution and leaves the main player paused.
7. Verify Mapping has no `Current Scope` control and no hard-coded NodeMap choices. Existing get/put/resolve controls remain usable with a paused worker.
8. Verify **Tools > Advanced Controls** still exposes Status, Recent Events, Step Overrides, Evidence, and Breakpoints.
9. Verify blocking runtime actions leave the Swing UI responsive.

## Regression

For the player state model and Swing/controller behavior:

```powershell
.\gradlew.bat :pickleball-workbench:test
```

For protocol, dependency, nested-artifact, and process-boundary checks:

```powershell
.\gradlew.bat verifyStrictControllerIsolation
```

For shared controller/MCP behavior:

```powershell
.\gradlew.bat :pickleball-workbench:verifyWorkbenchMcpStdio
```

For persistent worker/live behavior:

```powershell
$workbenchJar = ".\pickleball-workbench\build\libs\pickleball-workbench-<version>.jar"

java -jar $workbenchJar sync ".\maven-consumer-project"
java -jar $workbenchJar worker-check ".\maven-consumer-project"
java -jar $workbenchJar live-check ".\maven-consumer-project"
```

For changed consumer bridge behavior, use only the affected focused tags—never `@all`—and use parallelism 80 where practical:

```powershell
.\maven-consumer-project\mvnw.cmd -f maven-consumer-project\pom.xml -U test "-Dpkb_runvars.pkb_browser=CHROME_HEADLESS" "-Dpkb_runvars.pkb_parallel=80" "-Dpkb_runvars.pkb_tags=@control-bridge"
.\maven-consumer-project\mvnw.cmd -f maven-consumer-project\pom.xml -U test "-Dpkb_runvars.pkb_browser=CHROME_HEADLESS" "-Dpkb_runvars.pkb_parallel=80" "-Dpkb_runvars.pkb_tags=@step-override-bridge"
```

Run these invocations sequentially because the scenarios deliberately verify the process-global bridge bootstrap.
