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

MCP and Swing are adapters over the same Workbench service seam. They must not introduce a second runtime implementation. A visible UI keeps one Workbench JVM and one consumer worker. An AI agent attaches to that live session through a localhost HTTP JSON facade; it must not start a second Workbench or a second worker.

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

Run the small launcher from the consumer test classpath. For Maven consumers, this command requires no cache path, separate Workbench dependency, or separately selected version.

Consumer AI agents use headless MCP:

```bash
mvn -q org.codehaus.mojo:exec-maven-plugin:3.5.0:java \
  -Dexec.mainClass=tools.dscode.launcher.PickleballWorkbenchLauncher \
  -Dexec.classpathScope=test \
  "-Dexec.args=mcp ."
```

```powershell
mvn -q org.codehaus.mojo:exec-maven-plugin:3.5.0:java "-Dexec.mainClass=tools.dscode.launcher.PickleballWorkbenchLauncher" "-Dexec.classpathScope=test" "-Dexec.args=mcp ."
```

Humans who want the Swing player can pass `ui .` instead. With no launcher arguments, `ui` and the current directory are selected automatically for that human default. Other Workbench commands are forwarded in the same form, for example `"-Dexec.args=sync ."`. Agents for this release should not use the GUI, `ui .`, or `.pickleball/workbench/attach.json` as their path; see `.pickleball/AGENT-GUIDE.md`.

Gradle consumers can expose the same dependency-owned launcher without resolving a cache path or adding a Workbench dependency:

```groovy
tasks.register('pickleballWorkbench', JavaExec) {
    classpath = sourceSets.test.runtimeClasspath
    mainClass = 'tools.dscode.launcher.PickleballWorkbenchLauncher'
    args 'mcp', projectDir.absolutePath // consumer agents; pass 'ui' for the Swing player
}
```

Run it with `./gradlew pickleballWorkbench` (or `gradlew.bat pickleballWorkbench`). The task uses the consumer's resolved test runtime only to locate the tiny launcher and nested bytes; actual controller code still starts in a separate `java -jar` process.

The launcher streams the nested payload (it does not load the controller JAR into a byte array), hashes SHA-256 while copying, and rejects payloads larger than 512 MiB. OpenJFX WebView natives make the executable larger than a plain Java controller. It extracts atomically to:

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

Read the last synchronization manifest without invoking Maven/Gradle:

```powershell
java -jar $workbenchJar status ".\maven-consumer-project"
```

Synchronization uses the selected project wrapper to establish compiled output and the effective test runtime classpath. It keeps `-DskipTests` (Surefire never runs during sync). Input fingerprints of Java sources, resources, build files, and dependency artifacts decide how much of the wrapper to run:

- **Skip** when those inputs match the last recorded input fingerprints and the live snapshot is present. The output `fingerprint` in `manifest.json` is provenance over merged classes plus dependency bytes; it is not the skip key.
- **Resources-only** when only feature/config/data (test resources) changed: Maven `process-resources` / `process-test-resources`, or Gradle `processResources` / `processTestResources`, without `test-compile` / `testClasses`. If compiled outputs were cleaned, sync escalates to a full compile so live classes are not wiped.
- **Full** when Java sources, the build descriptor, or dependency artifact bytes changed, or when no prior snapshot exists.

Live Gherkin buffer edits never require sync and never write the original `.feature` until explicit Save. Worker restart without rebuild already exists. Step Overrides stay worker-side compile.

`.pickleball/workbench/base/classes` is provenance only; the worker runs against the merged `.pickleball/workbench/live/classes` state plus captured external dependencies. Do not use `live/classes` as an editor.

At worker connection time, Workbench requires a different PID, compatible protocol range and capabilities, a Pickleball code source that is exactly one captured consumer classpath entry, the synchronized Pickleball version (except explicit development output), and no Workbench controller artifact on the worker classpath. It fails clearly instead of falling back to a bundled runtime.

## Swing UI

Start Workbench for one consumer project:

```powershell
java -jar $workbenchJar ui ".\maven-consumer-project"
```

The Swing UI is a presentation adapter over the same `WorkbenchServices` / `WorkbenchController` seam used by MCP. It does not own a second worker manager, bridge client, Mapping implementation, Gherkin execution engine, or Pickleball runtime model.

### Player-style layout

The primary workspace is an interactive Gherkin player with a project feature picker:

```text
+-------------------------------------------------------------------------+
| Scenarios | Project / readiness      Play  Pause  Stop    Player status |
+-----------+--------------------------+----------------------------------+
| Name +    | LIVE GHERKIN EDITOR      | Mapping | Terminal | Diagnostic  |
| match mode| [Text | Blocks]          | WebView tree / typed values      |
| tags AND  | playhead on same buffer  | worker log / retained-run frames |
| tags NOT  |                          |                                  |
| scenario  |                          |                                  |
| list      |                          |                                  |
| (feature  |                          |                                  |
|  filter   |                          |                                  |
|  hidden)  |                          |                                  |
+-----------+--------------------------+                                  |
|           | Step Editor [Step] [From |                                  |
|           | Here] [ live command ]   |                                  |
+-----------+--------------------------+----------------------------------+
| Footer activity                                                         |
+-------------------------------------------------------------------------+
```

The left rail is a scenario filter, not a feature-file browser. Primary controls are scenario name (starts with / contains / ends with / full match; default contains; all four are case-insensitive against the Gherkin Scenario / Scenario Outline title), tags the scenario must have (AND), and tags it must not have (NOT). Include/exclude fields accept any number of tags, with or without a leading `@`, split on commas and/or whitespace. Empty include/exclude means no tag constraint. Feature-level tags, optional Rule tags, the scenario/outline's own tags, and Examples tags on an outline are inherited the same way Cucumber does; Workbench parses those tags from the catalog `.feature` files and does not call Cucumber. Feature-file selection is collapsed behind **Filter by feature** (Gherkin Feature name vs file path lives in that panel). Default: no feature filter, so name/tag apply to every catalog scenario in the synchronized project. Clicking a result still loads that scenario into the live session buffer. Workbench does not write `.feature` files unless you use the explicit **Save** control.

The center editor shows the same `LiveScenarioPlayer` buffer as ordinary Gherkin text or as the embedded HTML/JS block editor hosted in JavaFX `WebView` (`JFXPanel`). A prominent **Text | Blocks** toggle next to the editor heading switches views without losing playhead, selection, or document text. Blocks are Gherkin text — including `Given` / `When` / `Then` — not a second language compiled to Gherkin. Nested steps and `IF` / `ELSE` blocks snap as parent/child using Pickleball's leading-colon grammar. The play header is unchanged: click-to-seek, global **Play** from the first step in a fresh worker context, **Step** = isolated `executeStep`, **From Here** = selected/playhead through the rest, stay in play at end, Enter append-and-run. JavaScript never executes Gherkin. If JavaFX cannot start, Text is already the fallback and Blocks stays disabled/unavailable.

The right side remains Mapping, Terminal, and Diagnostic Log Explorer. Low-level lifecycle controls stay under **Session**. Existing investigation tools stay under **Tools > Advanced Controls**.

### Live scenario buffer and player state

`LiveScenarioPlayer` is a headless Workbench-side presentation model. It owns only:

- stable line identities independent of display line number;
- the live session buffer as editable Gherkin text;
- selected line;
- playhead (the user-visible needle);
- player states `STOPPED`, `PAUSED`, `RUNNING`, and `WAITING_FOR_STEP`.

The Live Scenario Editor is a session-scoped Gherkin document presented as snap-together blocks. Users can type Gherkin into a block, including text that already ran. Stable line ids are preserved across in-place edits so the player can keep selection, playhead, and execution cursor coherent. Loading a picker scenario replaces the live buffer only. The default remains session/live. **Save** is confirmation-gated: it copies the live scenario into the originating `.feature` file and scenario only after Allow. The Workbench-owned demo has no save path. Workbench never writes `.feature` files on picker load or on Deny.

The playhead behaves like an audio-player needle:

- clicking a scenario line instantly seeks the playhead to that line;
- while a run is active, `executeStep` advances the playhead once on success (or pauses it on failure); the UI Play loop continues from that new next step without remaking the same mark;
- **Pause** and **Stop** do not claim to rewind browser, Mapping, service, or other worker side effects.

Global **Play** always starts a fresh interactive scenario context and runs from the first executable step, even if the playhead is elsewhere. Fresh **Play** / **From Here** runs restart the consumer worker so prior side effects do not masquerade as the start of a scenario.

### Step Editor play actions

The Step Editor exposes two distinct play actions on the same `WorkbenchServices.executeStep` seam used by MCP:

```text
▶ Step       execute only the Step Editor text in the current paused live context
▶ From Here  start a fresh scenario context and run from the selected/playhead step through the rest of the buffer
Enter        insert a step (append-and-run while waiting at end)
Ctrl+Enter   update the selected line in place
```

**Step** pauses automatic playback, sends the displayed Gherkin unchanged, and leaves the main player paused. **From Here** treats the selected executable step as the first step of a new run.

Workbench never strips `Given` / `When` / `Then` / `And` / `But` / `*` and does not contain a second Gherkin matcher. If a displayed line starts with one of those keywords, worker-side `DynamicControl` parses that one line through `GherkinControl` and executes the resulting detached step. Historical raw detached-step text remains supported.

### Stay in play / add-and-continue

Reaching the end of the buffer does not drop out of play. The player remains `WAITING_FOR_STEP`. Typing a new step in the Step Editor and pressing **Enter** appends that step to the end of the live scenario and queues it through the same `executeStep` contract. Adding an executable line at the end of the in-place editor while waiting does the same. Inserting a line earlier in the document does not replay later steps.

### Default demo scenario

A new Workbench session loads a Workbench-owned sample, not a blank buffer and not a consumer `.feature` file. The default scenario is a small browser demo against the existing Maven consumer local test site:

```gherkin
Feature: Workbench Live Scenario

Scenario: Open the local test site
  Given navigate to: URL.home
  When , ensure "Pickleball Test Lab" Text is displayed
  And , click the "Open Forms Playground" Link
  Then , ensure "Forms Playground" Text is displayed
```

`URL.home` comes from the consumer's ordinary config mapping. The sample does not hard-code machine-specific filesystem paths. Once a worker is up, **Play** exercises real browser navigation and a click on the local test site.

### Mapping tab

There is no GUI-defined `Current Scope` concept and no hard-coded NodeMap names. The Mapping tab is a structured property tree populated from the actual NodeMaps in the current worker-side `ParsingMap`. Each property is edited in place: key, value text, and a type dropdown (`string`, `numeric`, `boolean`, `object-as-JSON`, `object-as-XML`). Typed writes go through the existing `mappingPut` service; key renames and whole-object replacement use `mappingRestore`. The GUI must not recreate inheritance rules or keep a second Mapping store.

NodeMap implementations that are not exact ordinary `NodeMap` instances remain inspection-only. MCP continues to support arbitrary JSON-compatible Mapping values through the shared service methods.

### WebView packaging

JDK 21 does not ship a modern browser panel. Workbench embeds OpenJFX `WebView` through `JFXPanel` for the Gherkin editor, Mapping tree, and Diagnostic explorer. That choice stays Workbench-only: Maven-central JavaFX modules are shaded into the controller executable, including platform natives under `javafx-natives/<platform>/`. JCEF was not used because Chromium natives are harder to keep isolation-clean and do not package as ordinary Workbench dependencies. If JavaFX cannot start, the live editor stays on the existing in-place text buffer on the same `LiveScenarioPlayer` model; the **Text | Blocks** toggle remains visible and Blocks is disabled so the fallback is honest.

### Terminal and Diagnostic Log Explorer

The Terminal tab presents the existing consumer-worker stdout/stderr capture files under `.pickleball/workbench/logs/` as a scenario-run log. A dropdown filters `TRACE`, `DEBUG`, `INFO`, `WARNING`, and `ERROR`. Lines continue as the playhead moves because the panel tails those files and also records `executeStep` / Mapping results. Workbench does not redirect MCP stdout. If a worker log line has no printed level, it is shown at `INFO` rather than invented. Structured Pickleball logger output is used when present; there is no second log fabricator.

The Diagnostic Log Explorer is a WebView timeline over Pickleball's retained diagnostic artifacts (`reports/diagnostic-runs`). It follows the existing evidence escalation order and does not create a competing store or fake retained-run data:

1. `run-catalog.json`
2. selected `run-index.json` / `clusters.json`
3. scenario `summary.json`
4. relevant `events.jsonl`
5. existing comparison/fingerprint metadata
6. PNG frames only when a retained screenshot exists, shown with the Gherkin step text that was running when taken
7. raw trace only when structured evidence is insufficient

If the consumer project has no `run-catalog.json`, the explorer says so and stays empty.

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

The UI is intentionally not a project IDE, generic process manager, generic Maven/Gradle task runner, source navigator, or collaboration system. The Live Scenario Editor is a session-scoped Gherkin player/editor, not a workspace file explorer and not an automatic writer of consumer `.feature` files.

### Watched AI-agent control lease

Workbench owns one control lease for the live session. Swing and MCP/HTTP adapters share it; the lease is not Swing-only state.

- Holder is `HUMAN` when the UI is up, or `AGENT` after an attached agent requests control.
- The snapshot also carries the agent display name, `currentAction` banner text, and at most one pending permission request.

While the human holds the lease, Swing play/edit/mapping/save/worker controls stay enabled. Agent mutating calls fail clearly until `workbench_request_control`.

While an agent holds the lease, the human can watch the same window. Play, edit, picker/filter, editor view toggle, Mapping writes, Save, and worker lifecycle controls lock. The WebView editors stay mounted and become read-only; they are not torn down. A banner names the agent and shows `currentAction`. **Take control** stays enabled. Take control returns the lease to `HUMAN`, unlocks Swing, and fails any in-flight agent permission wait so a blocked Save does not write.

The agent should update `currentAction` as it works. Playhead, Mapping, Terminal, and screenshots follow because the agent uses the same `LiveScenarioPlayer` / worker as the UI. Testing the live scenario (`executeStep`, play, Mapping reads, evidence) is allowed on the agent lease. Copying the live scenario into the original `.feature` is not; that goes through `workbench_request_save` and waits for Allow/Deny in the Swing banner.

Human **Save** uses the same service. After a picker scenario was loaded, Swing asks: copy these live steps into file X / scenario Y? Deny writes nothing. The demo buffer stays unsavable.

### Attaching an agent to a visible UI

The Swing UI is a human player. Consumer AI agents for this release should start headless `mcp .` instead of attaching to a GUI.

UI mode cannot share process stdout with stdio MCP. Starting `mcp` while the UI is already running would be a second Workbench JVM. Instead, `ui` starts a 127.0.0.1-only JSON attach endpoint over the same `WorkbenchServices` / `WorkbenchMcpTools` methods and writes disposable discovery state:

```text
.pickleball/workbench/attach.json
```

Example:

```json
{
  "url": "http://127.0.0.1:51234",
  "token": "hex-session-token",
  "pid": 12345,
  "project": "/path/to/maven-consumer-project",
  "mode": "ui-attach",
  "bind": "127.0.0.1"
}
```

A Copilot or other MCP-style client finds that file in the consumer project, then:

1. `GET {url}/health` — liveness, no token.
2. `GET {url}/lease` and `GET {url}/player` — `Authorization: Bearer <token>` or `X-Workbench-Token`.
3. `POST {url}/tools/workbench_request_control` with `{"agentName":"Copilot"}`.
4. Use the existing live tools (`workbench_execute_step`, Mapping, evidence, worker) while holding the lease, and `workbench_set_current_action` so the human can watch.
5. `POST {url}/tools/workbench_request_save` to ask to copy the live scenario into the original feature. The call blocks until the human clicks Allow or Deny, or Take control.

Headless `java -jar pickleball-workbench-<version>.jar mcp <project>` stays stdio JSON-RPC only. That is the consumer-agent path. That client may hold the lease without a banner. Save is still a distinct explicit tool and never an implicit write.

A human-watched UI session is optional and separate. From `maven-consumer-project`, a person may start `ui .` and then a watcher can join `.pickleball/workbench/attach.json`. Do not launch a second `mcp` process against the same live UI session, and do not treat that attach file as the default agent path.

## MCP stdio

Start the lightweight non-Spring MCP server for a consumer project. This is the consumer-agent path:

```powershell
java -jar $workbenchJar mcp ".\maven-consumer-project"
```

Or, from a Maven consumer test classpath, `"-Dexec.args=mcp ."`. Do not document or use the Swing GUI as the agent path.

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

`workbench_sync` uses the skip / resources-only / full rules above. Live buffer edits do not require it.

Live runtime, Mapping, and watched-agent control:

```text
workbench_request_control
workbench_release_control
workbench_set_current_action
workbench_control_lease
workbench_player_state
workbench_player_replace_document
workbench_request_save
workbench_execute_step
workbench_mapping_get
workbench_mapping_put
workbench_mapping_resolve
workbench_mapping_snapshot
workbench_mapping_restore
workbench_events
```

`workbench_execute_step` returns a structured `SUCCESS` / `FAILED` / `UNAVAILABLE` result. A FAILED Gherkin hypothesis leaves the same paused worker available so the agent can inspect, insert, nest, or retry. That result is not an MCP `isError` and does not stop the worker. MCP `isError=true` is for controller/runtime problems such as a missing paused worker.

`workbench_events` is a paged read: pass `afterSequence` and a small `limit` (default 100, maximum 500). Do not request the full retained event history when a page answers the question.

Browser/service evidence:

```text
workbench_browser_page
workbench_browser_screenshot
workbench_element_inspect
workbench_service_call
```

Prefer `workbench_browser_page` and `workbench_element_inspect` over `workbench_browser_screenshot` unless the image itself is required. Screenshot bytes are expensive in agent context.

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

Sparse diagnostic readers (do not glob `reports/diagnostic-runs`; these return JSON only and do not dump events, traces, or PNG bytes):

```text
workbench_diagnostic_catalog
workbench_diagnostic_run
workbench_diagnostic_summary
```

Human investigation handoff (writes `.pickleball/investigations/<id>/` and returns the relative `report.html` path only):

```text
workbench_investigation_emit
```

`workbench_step_override_compile` sends the Java source template to the consumer worker. The source must contain `{{CLASS_NAME}}`; worker-side Pickleball remains responsible for compilation, generated classloaders, matching, replacement, captures, and execution.

Mutating live tools require the agent control lease. `workbench_request_save` never writes the original feature until the human Allows it in the UI, or until the explicit stdio tool call itself is the headless approval. Deny, Take control, and an unsavable demo buffer leave the file unchanged.

Controller/runtime failures are returned as MCP tool results with `isError=true`. They are not printed as arbitrary protocol output. Exploratory `FAILED` step, mapping, browser, or service-call results remain ordinary JSON tool results with `isError=false`.

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
- the outer Pickleball JAR contains exactly one opaque Workbench payload whose SHA-256 matches the standalone controller JAR;
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

## Manual UI acceptance for the live player/editor

```powershell
$workbenchJar = ".\pickleball-workbench\build\libs\pickleball-workbench-<version>.jar"
java -jar $workbenchJar ui ".\maven-consumer-project"
```

Use the UI-owned worker for runtime checks; do not run `worker-check` or `live-check` concurrently with the UI.

1. Verify the top-level layout has a scenario name/tag filter rail (feature-file filter collapsed), the Live Gherkin Editor with a **Text | Blocks** toggle and compact Step Editor in the center, and exactly Mapping / Terminal / Diagnostic Log Explorer on the right.
2. Confirm the default buffer is the Workbench demo scenario and includes `navigate to: URL.home` plus a click on the local test site when no picker scenario is selected.
3. Filter scenarios by name using contains (default) and the other match modes; confirm matching is case-insensitive and applies to the Scenario / Scenario Outline title.
4. Filter with include tags (AND) and exclude tags (NOT), with and without `@`, and confirm Feature-level tags apply to scenarios in that feature.
5. Confirm **Filter by feature** is collapsed by default and that name/tag filters then apply to every catalog scenario. Opening it still supports multi-select and Feature name vs file path.
6. Click a filtered scenario and verify it loads into the live buffer. Switch **Text | Blocks** and verify playhead, selection, and document text are unchanged. If WebView is unavailable, Blocks is disabled and Text remains the editor.
7. Click different scenario blocks/lines and verify the playhead highlight moves immediately to the clicked step.
8. Edit previously typed or previously executed Gherkin directly in the Live Scenario Editor and verify the line text updates in place.
9. Press global **Play** after seeking the playhead to a later step and verify execution still starts from the first executable step in a fresh worker context.
10. Use **From Here** on a later executable step and verify playback starts there and continues through the rest of the buffer.
11. Use **Step** in the Step Editor and verify isolated `executeStep` execution that leaves automatic playback paused.
12. Let a run reach the end and verify the player stays in **Waiting for step**. Type a new step and press Enter; the step is appended and executed without dropping out of play.
13. Treat **Pause** / **Stop** as presentation/control of automatic advancement only; they do not rewind browser or service side effects.
14. Verify Mapping has no `Current Scope` control and no hard-coded NodeMap choices. Top-level properties come from the worker ParsingMap and accept typed in-place edits.
15. Verify Terminal filters worker log files by level and continues as steps run, without writing to MCP stdout.
16. Verify Diagnostic Log Explorer lists retained runs from `reports/diagnostic-runs` only, or shows an honest empty state.
17. Verify **Tools > Advanced Controls** still exposes Status, Recent Events, Step Overrides, Evidence, and Breakpoints.
18. Verify blocking runtime actions leave the Swing UI responsive.
19. Load a picker scenario, click **Save**, and cancel the confirmation; the original `.feature` file must be unchanged. Confirming copies only that scenario back into the originating file.
20. Attach an agent to `.pickleball/workbench/attach.json`, call `workbench_request_control`, and verify the banner plus locked play/edit/picker/filter/editor-view/mapping/save/worker controls. **Take control** remains enabled.
21. While the agent holds the lease, `workbench_request_save` shows Allow/Deny. Deny writes nothing. Take control cancels the wait without writing.
22. The default demo remains unsavable for both human Save and agent `workbench_request_save`.

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
