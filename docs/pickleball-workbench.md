# Pickleball Workbench

Pickleball Workbench is the separate executable companion for interactive Pickleball execution and investigation. It depends on the normal shaded/woven `tools.dscode:pickleball` artifact; normal Pickleball consumers do not depend on Workbench.

The current migration surface includes project synchronization, a persistent consumer worker, live runtime control, Step Override authoring, lightweight MCP stdio, and the first thin Swing UI increment. The old Studio remains temporarily present until the later migration-removal phase.

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

Phase 6B-1 provides:

- selected project display;
- synchronization/status refresh;
- synchronize project;
- start worker;
- restart the worker in a fresh JVM without rebuilding;
- stop worker;
- worker PID/runtime/scenario/pause status;
- clean Workbench shutdown when the window closes.

Synchronization and worker actions run off the Swing Event Dispatch Thread so build-wrapper and bridge/process work do not freeze the UI.

Later Phase 6B increments will add live Gherkin, Mapping, Step Override authoring, events/evidence, and breakpoint controls over the same service seam. The UI is intentionally execution-oriented and is not a replacement IDE, file editor, generic process manager, or generic Maven/Gradle task runner.

## MCP stdio

Start the lightweight non-Spring MCP server for a synchronized consumer project:

```powershell
java -jar $workbenchJar mcp ".\maven-consumer-project"
```

The server uses the official Java MCP SDK core and stdio transport with the Jackson 2 JSON adapter. MCP dependencies are Workbench-only and are shaded into the executable companion. Workbench deliberately does not use Spring Boot, Spring Framework, Spring AI, WebMVC, or Tomcat for this adapter. The executable remains alive for the stdio session and exits cleanly after the MCP client closes stdin.

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

Workbench MCP and Swing intentionally do not expose a generic IDE or build system. They do not add generic file editing/search, arbitrary process management, generic Maven/Gradle task execution, Gradle Tooling API project browsing, source navigation, or the old Studio collaboration model.

## Dependency and artifact checks

The Workbench build keeps the published-equivalent Pickleball boundary and verifies that:

- the Workbench executable contains the MCP adapter;
- normal Pickleball contains neither Workbench classes nor MCP SDK classes;
- Workbench does not resolve the unpublished `pickleball-control-api` project;
- separate unwoven Cucumber modules do not appear on the Workbench runtime;
- the MCP convenience artifact / Jackson 3 path is not used;
- the published Workbench POM still declares only `tools.dscode:pickleball`.

Report the final executable size and resolved MCP SDK artifacts with:

```powershell
.\gradlew.bat :pickleball-workbench:reportWorkbenchMcpImpact
```

For a before/after Phase 6A measurement, record the Phase 5 JAR length before applying the Phase 6A overlay, then compare it with `Workbench executable bytes` from this task.

The direct MCP dependencies are:

```text
io.modelcontextprotocol.sdk:mcp-core:2.0.0
io.modelcontextprotocol.sdk:mcp-json-jackson2:2.0.0
```

Notable SDK runtime transitives include Reactor Core, SLF4J API, Jackson 2, and the JSON Schema validator used by the SDK. The SDK's servlet API dependency is provided scope rather than part of the Workbench runtime.

## Phase 6B-1 validation

Start with the exact headless-safe UI/controller test:

```powershell
.\gradlew.bat :pickleball-workbench:test `
  --tests "tools.dscode.workbench.ui.WorkbenchUiControllerTest.workerLifecycleDelegatesToSharedWorkbenchServices"
```

Then run the complete UI controller test and Workbench application test:

```powershell
.\gradlew.bat :pickleball-workbench:test `
  --tests "tools.dscode.workbench.ui.WorkbenchUiControllerTest" `
  --tests "tools.dscode.workbench.WorkbenchApplicationTest"
```

Then build the Workbench:

```powershell
.\gradlew.bat :pickleball-workbench:build
```

Because this increment only adds the Swing presentation/lifecycle adapter over existing Workbench services, it does not add a Maven consumer tag. If the packaged UI launches correctly, manually verify the shell with:

```powershell
$workbenchJar = ".\pickleball-workbench\build\libs\pickleball-workbench-2.1.8.jar"
java -jar $workbenchJar ui ".\maven-consumer-project"
```

The expected first-increment UI surface is project/synchronization status plus synchronize/start/restart/stop worker controls. Do not expect live Gherkin, Mapping, Step Override, evidence, or breakpoint controls until the later Phase 6B increments.

## Phase 6A regression

When shared controller/MCP behavior changes, re-run:

```powershell
.\gradlew.bat :pickleball-workbench:verifyWorkbenchMcpStdio
```

Then re-run the persistent-worker/live regression against the synchronized Maven example when shared worker behavior was touched:

```powershell
$workbenchJar = ".\pickleball-workbench\build\libs\pickleball-workbench-2.1.8.jar"

java -jar $workbenchJar sync ".\maven-consumer-project"
java -jar $workbenchJar worker-check ".\maven-consumer-project"
java -jar $workbenchJar live-check ".\maven-consumer-project"
```
