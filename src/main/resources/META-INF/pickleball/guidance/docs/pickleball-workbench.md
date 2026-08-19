# Pickleball Workbench

Pickleball Workbench is the separate executable companion for interactive Pickleball execution and investigation. It depends on the normal shaded/woven `tools.dscode:pickleball` artifact; normal Pickleball consumers do not depend on Workbench.

Workbench replaces the former Pickleball Studio application. The final surface is deliberately execution-oriented: project synchronization, a persistent consumer worker, live runtime control, Mapping, browser/service evidence, semantic breakpoints, Step Override authoring, lightweight non-Spring MCP stdio, and a thin Swing UI.

## Architecture

Dependency direction is strictly:

```text
pickleball-workbench -> pickleball
```

Pickleball owns scenario execution semantics, Cucumber integration, DynamicControl/Gherkin execution, Mapping, browser/service behavior, the consumer-side Control Bridge, semantic hooks/breakpoints, Step Overrides, and woven Cucumber/AspectJ behavior.

Workbench owns synchronization, `.pickleball/workbench/` disposable state, worker lifecycle, the controller-side bridge client, `WorkbenchLiveSession`, `WorkbenchServices` / `WorkbenchController`, MCP stdio, and the thin Swing UI.

MCP and Swing are adapters over the same Workbench service seam. They must not introduce a second runtime implementation.

The canonical worker bridge environment is:

```text
PKB_CONTROL_BRIDGE_SESSION_DIR
PKB_CONTROL_BRIDGE_SESSION_ID
PKB_CONTROL_BRIDGE_TOKEN
PKB_CONTROL_BRIDGE_PAUSE_FIRST_SCENARIO
```

Pickleball may accept `PKB_STUDIO_BRIDGE_*` as deprecated compatibility input aliases only. Workbench emits only the neutral names.

## Build and run

Build the executable companion:

```powershell
.\gradlew.bat :pickleball-workbench:build
```

The executable is:

```text
pickleball-workbench/build/libs/pickleball-workbench-<version>.jar
```

Synchronize a consumer project before starting a worker:

```powershell
$workbenchJar = ".\pickleball-workbench\build\libs\pickleball-workbench-2.1.8.jar"
java -jar $workbenchJar sync ".\maven-consumer-project"
```

Synchronization uses the selected project wrapper to establish compiled output and the effective test runtime classpath. `.pickleball/workbench/base/classes` is provenance only; the worker runs against the merged `.pickleball/workbench/live/classes` state plus captured external dependencies.

## Swing UI

Start the thin Workbench UI for one consumer project:

```powershell
java -jar $workbenchJar ui ".\maven-consumer-project"
```

The Swing UI is a presentation adapter over the same `WorkbenchServices` / `WorkbenchController` seam used by MCP. It does not own a second worker manager, bridge client, Mapping implementation, or Pickleball execution model.

The UI provides:

- selected project display and synchronization/status refresh;
- synchronize, start worker, restart fresh worker without rebuilding, and stop worker;
- worker PID/runtime/scenario/pause status;
- live raw Gherkin step input with optional argument text and result/status output;
- Mapping get, put, and resolve;
- incremental semantic-event display with timestamp, hook, step/phrase, and signature detail;
- Step Override list, worker-side compile/replace, remove, and clear;
- browser page evidence and lightweight PNG screenshot display;
- existing Pickleball service-call execution/evidence;
- semantic breakpoint list, add, remove, and clear with hook/filter/one-shot/finite-lease controls;
- clean Workbench shutdown when the window closes.

The Mapping put control stores the entered Swing value as text. MCP continues to support arbitrary JSON-compatible Mapping values through the same service method.

The Step Override editor sends its source template unchanged to the worker. The source must contain `{{CLASS_NAME}}`; generated class naming, compilation, classloading, rule registration, matching, captures, replacement, and cleanup remain worker-side Pickleball responsibilities. Browser page, screenshot, service-call, event, and breakpoint controls expose the existing bridge contracts rather than reimplementing them in Swing.

Synchronization, worker actions, live bridge calls, Mapping operations, event refresh, Step Override actions, browser/screenshot evidence, service calls, and breakpoint actions run off the Swing Event Dispatch Thread. Live controls are enabled only while the Workbench-owned worker is running and paused.

The UI is intentionally not a project IDE, file editor, generic process manager, generic Maven/Gradle task runner, source navigator, or collaboration system.

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

The Workbench build keeps the published-equivalent Pickleball boundary and verifies that:

- the Workbench executable contains the MCP adapter;
- normal Pickleball contains neither Workbench classes nor MCP SDK classes;
- Workbench does not resolve the unpublished `pickleball-control-api` project;
- separate unwoven Cucumber modules do not appear on the Workbench runtime;
- the MCP convenience artifact / Jackson 3 path is not used;
- the published Workbench POM still declares only `tools.dscode:pickleball`.

Report the executable size and resolved MCP SDK artifacts with:

```powershell
.\gradlew.bat :pickleball-workbench:reportWorkbenchMcpImpact
```

The direct MCP dependencies are:

```text
io.modelcontextprotocol.sdk:mcp-core:2.0.0
io.modelcontextprotocol.sdk:mcp-json-jackson2:2.0.0
```

## Manual UI acceptance

```powershell
$workbenchJar = ".\pickleball-workbench\build\libs\pickleball-workbench-2.1.8.jar"
java -jar $workbenchJar ui ".\maven-consumer-project"
```

Use the UI-owned worker for this smoke test; do not run `worker-check` or `live-check` concurrently with the UI.

1. **Status:** click **Start Worker** and verify `Paused: true` with a PID/runtime/scenario.
2. **Live Gherkin:** execute `CONTROL API TEST STEP` and verify `Status: SUCCESS`.
3. **Mapping:** put/get/resolve `OVERRIDE / workbenchLiveValue = first` and verify `first` is returned.
4. **Step Overrides:** leave the prefilled id/regex/source, click **Compile / Replace**, and verify `Status: SUCCESS` plus one installed override. In **Live Gherkin**, execute `WORKBENCH UI OVERRIDE alpha`; then in **Mapping**, get `OVERRIDE / workbenchStepOverrideValue` and verify `ui-alpha`. Return to **Step Overrides**, click **Remove ID**, and verify the installed list is empty.
5. **Evidence / Service Call:** execute `%health-full-url` and verify `Status: SUCCESS` and `HTTP status: 200`.
6. **Evidence / Browser:** in **Live Gherkin**, execute `navigate to: URL.home`; then click **Read Page** and verify the URL/title/page source contains the Pickleball test page. Click **Capture Screenshot** and verify a PNG image is displayed.
7. **Breakpoints:** with the prefilled `BEFORE_STEP`, `CONTROL API TEST STEP`, one-shot, and `120` second lease, click **Add** and verify one breakpoint is listed. Copy its generated id into **Breakpoint ID (for remove)**, click **Remove ID**, and verify the list is empty.
8. **Recent Events:** verify semantic events are present and include sequence, timestamp, hook, and step/phrase/signature detail.
9. **Lifecycle:** note the PID, click **Restart Worker**, verify a different PID with `Paused: true`, execute one live step successfully, then click **Stop Worker** and verify `Not running (exit=0)`.

## Regression

For shared controller/MCP behavior:

```powershell
.\gradlew.bat :pickleball-workbench:verifyWorkbenchMcpStdio
```

For persistent worker/live behavior:

```powershell
$workbenchJar = ".\pickleball-workbench\build\libs\pickleball-workbench-2.1.8.jar"

java -jar $workbenchJar sync ".\maven-consumer-project"
java -jar $workbenchJar worker-check ".\maven-consumer-project"
java -jar $workbenchJar live-check ".\maven-consumer-project"
```
