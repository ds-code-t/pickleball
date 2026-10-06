# Pickleball Workbench Agent Context

Root `AGENTS.md` remains authoritative. Read it and `docs/agent/feature-map.md` before changing this module.

## Module role

`pickleball-workbench` is the external controller/control plane for interactive Pickleball tooling. It is not a Pickleball runtime. The dependency and distribution graph is strictly:

```text
pickleball core/worker --------> pickleball-control-protocol
pickleball-workbench ----------> pickleball-control-protocol
published pickleball JAR ------> opaque completed Workbench JAR bytes
```

The final line is an assembly input, not a Java/runtime dependency. Pickleball may contain Workbench for delivery; Workbench must not contain Pickleball for execution.

## Build boundary

Workbench must compile and run without resolving the root project, `tools.dscode:pickleball`, a published-equivalent/shaded root configuration, or the behavioral `pickleball-control-api`. Its only project dependency is the JDK-only `pickleball-control-protocol` module. Never restore `implementation project(':')`, `pickleballPublishedElements`, a Pickleball Maven dependency, or core shading to fix compilation.

The protocol module owns only stable wire DTOs, request/response envelopes, transport constants, capability lists, and explicit version negotiation. It owns no bridge server, bootstrap, mapping logic, Cucumber/Selenium/service behavior, filesystem synchronization, UI, or MCP behavior. When a new runtime capability is required, implement it in core/worker and expose neutral wire data; do not move the behavior into Workbench or protocol.

Workbench-only dependencies, including Jackson and the MCP SDK, belong only on the Workbench compile/runtime classpath and the forked controller `-cp`. The thin JAR plus every nested JAR/service descriptor must remain free of Pickleball core, `pickleball-control-api`, bridge-server/worker implementation, consumer classes, Cucumber, Selenium, REST-assured, OpenJFX, MCP, and Jackson packages. The MCP adapter uses the non-Spring MCP Java SDK core plus its Jackson 2 adapter; do not replace them with the convenience/Jackson 3 artifact or Spring transports without a new architecture decision.

## Runtime ownership

The Workbench controller owns synchronization, worker process/session lifecycle, bridge client behavior, MCP stdio, the localhost UI-attach endpoint (`attach.json`), the headless CLI session (`session` command, `cli-session.json`, serial execute-step queue), the watched-agent control lease, and the thin Swing UI. Pickleball owns consumer-worker behavior such as the bridge server/coordinator, DynamicControl/Gherkin execution, Step Override runtime, Mapping state, browser/service-call access, and woven Cucumber integration.

`WorkbenchServices` is the shared plain-Java adapter boundary. `WorkbenchController` composes synchronization, `WorkbenchLiveSession`, `LiveScenarioPlayer`, and the control lease; MCP, HTTP attach, and Swing must delegate to that service surface instead of implementing their own worker ownership, bridge calls, Mapping semantics, Step Override behavior, scenario retry rules, or a second live Gherkin document.

`WorkbenchSessionActions` is the one implementation of the actions that change the open session (`open-scenario`, `example`, `play`, `from-here`, `pause`, `execute-step`, `insert-step`, `update-step`, `diagnostic-run`, `save`, `refresh`, `session-sync`, `worker-start`, `worker-restart`, `worker-stop`, `stop`). The Swing window calls those commands. The command queue calls them too. Do not add a second GUI-only copy. `example` is the same Examples-row filter as `pkb_example`. Play shows the first source-order match. Headless `pkb_example` still runs every match. Advanced inspector buttons keep calling the existing `WorkbenchServices` methods that MCP already exposes. Do not add a pixel, WebView, or JavaFX click tool.

The Workbench bridge client uses only `tools.dscode.control.protocol.*`. Worker-side `ControlBridgeRuntime`, `ControlBridgeCoordinator`, bootstrap, adapters, step compilation, mappings, and execution semantics stay in Pickleball. Workbench may hold the worker entry-point class name as `ControlProtocol.WORKER_MAIN_CLASS`; it must never import or load that class.

The canonical consumer-worker bridge environment is:

```text
PKB_CONTROL_BRIDGE_SESSION_DIR
PKB_CONTROL_BRIDGE_SESSION_ID
PKB_CONTROL_BRIDGE_TOKEN
PKB_CONTROL_BRIDGE_PAUSE_FIRST_SCENARIO
```

Pickleball may accept the old `PKB_STUDIO_BRIDGE_*` names as deprecated compatibility input aliases. Workbench code must emit only the neutral `PKB_CONTROL_BRIDGE_*` names. The aliases must never require Studio code or dependencies.

Project-local Workbench state is `.pickleball/v/<current>/workbench` when `current.json` is complete: session, attach, last-discover, logs, and `live/classes`. `.pickleball/workbench/` is only the fallback when `current.json` is missing or not usable. Do not describe both layouts as current, and do not treat either as source. Investigations stay at `.pickleball/investigations/`, not under `v/`.

## Swing UI

The thin Swing adapter lives under:

```text
tools.dscode.workbench.ui
```

The headless live-scenario presentation model lives under:

```text
tools.dscode.workbench.player
```

Launch the UI with:

```text
java -cp pickleball-workbench-<version>.jar:<resolved-libs> tools.dscode.workbench.WorkbenchApplication ui <project>
```

The UI is player-style and execution-oriented. Its primary layout is:

```text
left rail: scenario name/tag filters + results; optional feature-file filter; collapsible Sealed RunVars panel
center:    Live Gherkin text editor + compact Step Editor / Command
right:     Mapping | Config | Terminal | Explorer | Report
```

The Sealed RunVars panel is JDK/Workbench-only: it displays a map and writes LastDiscoverSnapshot `sealed=true` for the next worker `-Dpkb_overriderunvars=`. It must not import `PKB_props`, `PickleballProfiles`, or Pickleball core. Panel edits do not mutate an in-flight worker. Unused, it changes no behavior. The panel shows `pkb_compositereport` and `pkb_scenarioreport`. Agent and Workbench runs default both to false. A normal test still writes both HTML reports. An explicit true writes that report again.

The Workbench GUI is only a lightweight head over state and controls that already exist under the hood. Agents have direct access to that state and those controls, and direct control of the GUI controls, so they can collaborate with a person or present data and reports in the window. The GUI must not own behavior the agents cannot reach. Do not click JavaFX or WebView from tests. Do not embed Maven or Gradle. Agents run the project wrapper.

Low-level lifecycle controls live under the Session menu and existing investigation controls remain available under Advanced Controls rather than dominating the permanent workspace.

`LiveScenarioPlayer` owns presentation/session-buffer state only: stable line IDs, the editable Gherkin document, selected line, playhead, and `STOPPED` / `PAUSED` / `RUNNING` / `WAITING_FOR_STEP`. It must remain headless-testable and must not parse/execute Pickleball steps, implement runtime rewind, model Mapping inheritance, or become Swing component state.

The playhead is the user-visible needle. Clicking a scenario line instantly seeks it. Global Play runs a derived plan (Background plus the selected scenario, with one Examples row substituted when selected) in a fresh worker context, not from the playhead. The Live Scenario Editor is one ordinary Gherkin text document over `LiveScenarioPlayer`. Tab at the start of a line (or in the leading-colon/space prefix) inserts one extra leading `:`; Shift-Tab removes one leading `:` if present. Mid-line Tab inserts a space and does not move focus. Typing the first letters of a Gherkin keyword after optional leading colons/whitespace offers completion; Tab or Enter accepts the selected keyword and inserts a trailing space. When the completion popup is open, Tab accepts the completion; otherwise Tab at the indent prefix inserts `:`. The player bar shows `PickleballVersion.running`. Users may edit any line, including previously executed text. The picker filters by scenario name and tags as before; clicking a result opens the whole originating `.feature` file. Scenario Outlines expand to selectable Examples rows. The default buffer is a Workbench-owned browser demo against `URL.home` and has no save path. **Save** writes the editor buffer to the original file after confirmation. An attached agent must use `workbench_request_save` and wait for Allow/Deny when the UI is present. Deny and Take control write nothing. Ctrl+click / Open target follows `RUN` / `CALL` / `data:/` without executing. The Step definition panel is display-only. If JavaFX/WebView is unavailable, Mapping, Explorer, and Report use their text fallbacks; the live editor remains the Gherkin text editor.

The Step Editor has two play actions: **Step** executes only the editor text through `WorkbenchServices.executeStep` and leaves automatic playback paused; **From Here** restarts into a fresh scenario context and runs from the selected/playhead step through the rest of the buffer. Enter while waiting at end appends the step and continues the live run. Do not strip Gherkin keywords or add a Swing-side step matcher. Worker-side `DynamicControl` / `GherkinControl` remain the only Gherkin interpreters.

### Current player implementation phase

Buffered Play / From Here / add-and-continue now execute through the existing live `executeStep` contract. Swing remains a presentation adapter: it sends displayed Gherkin unchanged and never owns a second worker manager, Mapping implementation, or Pickleball runtime.

The Mapping tab must not hard-code NodeMap names. It is one current-ParsingMap NodeMap selector plus a structured property tree. Typed edits go through `mappingPut`; renames/object replacement use `mappingRestore`. Do not create a fake ParsingMap in Swing or WebView. The Config tab beside it is only a view of the open run's `config/` copy (`ConfigTabModel` / protocol `RunConfigs`). Switching to it does not sync or start a run. Save writes that copy and does not write the project configs.

Blocking synchronization, catalog loads, worker start, and other long startup work must not run on the Swing event thread (`LongWork`, thread name `pickleball-workbench-load`). A banner names `Syncing the project`, `Starting the run`, `Loading the project`, or the other long load, then clears. A failure stays on that banner and names the error. Do not rely on the footer line for that status. Every `JSplitPane` is dragged without a large minimum size, so one panel can take almost the whole window.

The Terminal tab tails the existing worker stdout/stderr files and filters TRACE–ERROR. Do not implement it by redirecting MCP stdout or inventing log lines. Explorer is a two-panel replay of retained Pickleball runs: an indented execution tree on the left (indent = call depth; no return arrows, no Mermaid, no live graph) and screenshot / honest gap / Gherkin / `source.path:line` / definition / INFO+ on the right. It also tails in-progress isolate `events.jsonl` (`completion` `IN_PROGRESS`) and live `workbench_events` as a `live-isolate` stream. Color is status at the playhead. Click seeks; Ctrl+click / Open / double-click is `workbench_go` peek of the pack-local `source/files/` copy and must not replace the live buffer. Pause/Stop/explorer rewind do not rewind browser, Mapping, or services. Report stays the investigation tab over the same `investigation.json` as portable `report.html` (indented list, Gherkin/business first, no Mermaid). Do not populate either tab with fake production data.

Picker “Play this scenario” still replaces the live Gherkin buffer. Explorer / Report / `workbench_go` from a retained run open a peek tab. Prefer pack copies. Never write. Never open framework sources from the JAR. Paths stay inside the consumer project. A missing target is a visible miss.

Heavy panels use Workbench-only OpenJFX `WebView` (`JFXPanel`). That choice is documented in `docs/pickleball-workbench.md`. Do not add JCEF, heavy JS graph libraries, or Pickleball-core UI dependencies. Explorer stays vanilla JS.

Existing capabilities remain available: project/synchronization status, worker lifecycle, live raw Gherkin, read-only step resolution (`workbench_step_resolve`), Mapping get/put/resolve, semantic events, Step Override list/compile/remove/clear, browser page/screenshot evidence, service-call evidence, and semantic breakpoint list/add/remove/clear.

The Swing Mapping put control sends entered values as text; it does not create a second Mapping parser or state model. Step Override source is sent unchanged to worker-side compilation and must contain `{{CLASS_NAME}}`; the UI must never compile handlers in the controller JVM. Browser/service/screenshot controls only present bridge evidence already supplied by Pickleball. Breakpoint controls delegate the hook/filter/lease contract to the shared service and must not recreate coordinator semantics.

Blocking synchronization, process, bridge, Mapping, event, screenshot, service-call, Step Override, and breakpoint actions must not run on the Swing Event Dispatch Thread. Live controls must target the controller-owned running/paused worker. When an agent holds the control lease, lock picker/filter fields, scenario list, feature-filter disclosure, and the live Gherkin text editor the same way other play/edit controls lock. Semantic-event cursors are worker-local and must reset when a fresh worker is started/restarted. Prefer headless-safe tests around player state, catalog/filter models, colon-Tab indent, keyword completion, and presentation/controller delegation rather than tests requiring a visible desktop.

## MCP stdio

Workbench provides:

```text
java -cp pickleball-workbench-<version>.jar:<resolved-libs> tools.dscode.workbench.WorkbenchApplication mcp <project>
```

The MCP adapter is `tools.dscode.workbench.mcp.WorkbenchMcpServer` plus `WorkbenchMcpTools`. It exposes project synchronization/status, interactive worker lifecycle, live Gherkin, read-only step resolution (`workbench_step_resolve`), Mapping operations, events/evidence, browser/service controls, semantic breakpoints, Step Override authoring, the watched-agent control lease, player-state inspection, gated Save, sparse diagnostic catalog/run/summary readers, `workbench_investigation_emit`, and one navigation tool `workbench_go` through `WorkbenchServices`. After emit, chat prints the six-line bottom-line block from `docs/consumer-agent-guide.md` (Gherkin/business first), not a dump. `workbench_go` requires UI attach plus a control lease to move the window; headless it validates and echoes the resolved target. It does not write files. Do not add generic IDE / git / process MCP tools. Do not `git checkout` or reset the consumer HEAD. Do not copy `reports/diagnostic-runs/` into `.pickleball/investigations`. Do not embed PNG bytes in investigation JSON. Do not create a Git repo under `.pickleball`. File copies and filtered patches are written by Pickleball core diagnostic reporting (worker); Workbench only reads the pack. Consumer agents use Workbench `discover` / `confirm` as the front door. When a Workbench window is already open, drive that session with the same commands the window calls so the person sees the result. When nobody is watching, launcher `isolate` / `execute-step` stay on the headless CLI session. Hosts may already wire `mcp .` as optional alias. Do not open the GUI for your own testing. Open it to show a person a specific run, or when they ask. Close it when you are done showing it. While testing for yourself, stay headless. Opening the window loads the run you already have. It does not start a second test. Do not click the JavaFX or WebView UI. Do not embed Maven or Gradle in MCP or the launcher. If a tool needs a build, it invokes the project wrapper (`mvnw`/`mvnw.cmd`, `gradlew`/`gradlew.bat`, or `java -jar` on the wrapper jar). Pick the script from the OS. Do not require a machine-wide Maven or Gradle install. Do not add a general `mvn` or `gradle` tool. Read the short log `.pickleball/agent-log` before a run, after a run, and during a long session, instead of the dense diagnostic log. Use your own run id. When a Workbench window is already open, only one agent drives it. Other agents stay headless on their own run ids. Write start and stop lines. Put purpose and findings on the run record. Use the inbox only for a short note to one named agent. Taking that note deletes it. A note for every agent is a post, not an inbox file, and listing or taking does not delete it. Workbench does not own that board; the consumer launcher does. A per-run `--session-file` is only a path plus `--run-id` / `--agent-id` strings copied onto the worker.

UI mode cannot share process stdout with stdio MCP. `ui` therefore starts a 127.0.0.1-only JSON attach facade (`WorkbenchAttachServer`) over the same tools and writes `attach.json` beside other Workbench state (`v/<current>/workbench/attach.json` when `current.json` is complete, otherwise `.pickleball/workbench/attach.json`) so a Copilot/MCP client can join the visible session. Bind localhost only. Do not launch a second `mcp` process against a running UI.

MCP stdout is a hard protocol boundary. `WorkbenchApplication` reserves the original process stdout for the stdio transport and redirects ordinary `System.out` output to stderr before constructing the MCP SDK/controller. The executable must explicitly remain alive for the stdio session until stdin reaches EOF; do not rely on MCP SDK worker-thread liveness to keep the JVM running. Workbench diagnostic text must use stderr or the Workbench `logs/` directory under `v/<current>/workbench` when `current.json` is complete, otherwise `.pickleball/workbench/logs/`; worker stdout/stderr remain separately redirected to worker log files. No banner, normal log, worker output, test output, or diagnostic chatter may be written to MCP stdout.

MCP tool failures are represented as MCP tool results with `isError=true`; they must not escape as arbitrary stdout text. Keep protocol tests covering initialize, tool listing, representative controller calls, invalid requests, Step Override compile invocation, protocol-only output, and cleanup.

Do not add generic IDE/file/build/process/collaboration tools to this MCP surface. The only new navigation tool is `workbench_go`. Synchronization may invoke project wrappers through the existing synchronizer, but MCP must not become a generic Maven/Gradle execution API.

## Synchronization and worker lifecycle

Workbench synchronization is build-tool-assisted, not a replacement build system. Use the selected project wrapper (`mvnw`/`mvnw.cmd`, `gradlew`/`gradlew.bat`, or `java -jar` on the wrapper jar) to establish compiled main/test output, processed resources, and the effective test runtime classpath. Do not fall back to a machine-wide Maven or Gradle install. Gradle synchronization must use build-native init-script/task injection rather than the Gradle Tooling API. Compare **input** fingerprints (Java sources, resources, build files, dependency artifact bytes) to the last manifest before invoking the wrapper: skip when nothing that requires recompilation changed; run resource processing only when only feature/config/data changed; run full `test-compile` / `testClasses` when Java, the build descriptor, or dependencies changed. The output fingerprint in `manifest.json` is provenance, not the skip key. Always pass `-DskipTests`. Live Gherkin buffer edits must never require sync. If compiled project outputs are missing after a clean, escalate resources-only to full compile.

`v/<current>/workbench/base/classes` (legacy `.pickleball/workbench/base/classes` only when `current.json` is missing or not usable) is synchronization provenance/reset state and must never be on a worker runtime classpath. `live/classes` under that same Workbench state root is the one merged project-owned runtime root; main output is materialized first and test output overlays it so one class/resource path is visible exactly once. Do not treat `live/classes` as an editor. External dependency entries stay referenced from their normal caches. The synchronization fingerprint covers both merged project output and dependency artifact contents, so replacing a same-version local dependency still changes the snapshot identity.

The controller owns one interactive worker per selected project by default. Workers launch directly with Java from the existing Workbench snapshot, use the Pickleball-side worker class-name contract, and set the protocol-owned `pickleball.workbench.testOutputRoot` property so core intentionally scans the merged live root instead of relying on Maven/Gradle output suffixes. The worker PID must differ from the controller PID; its reported Pickleball code source must be exactly one captured consumer classpath entry; its version must match the synchronized manifest; and its classpath must exclude the Workbench controller artifact. Incompatible protocol/capability/origin checks fail clearly and never fall back to a bundled runtime.

Interactive workers use a session-private anchor feature and the neutral `PKB_CONTROL_BRIDGE_*` environment contract. The anchor body must be a guaranteed no-op core step. The bridge's pause-first behavior stops first at `SCENARIO_START`, which occurs before `CurrentScenarioState.startScenarioRun()` finishes Pickleball scenario initialization; Workbench treats that pause only as a bootstrap rendezvous. Before returning an interactive worker, the controller installs a one-shot `BEFORE_STEP` breakpoint filtered to the anchor marker step `---pickleball-workbench-anchor`, resumes the bootstrap pause, and lets the root scenario step initialize normal logging/runtime state. It returns only after the marker itself is paused immediately before execution. Live controller operations must run only after that promotion. Pause leases remain finite; the controller renews the owned anchor lease while active. Graceful stop cancels renewal, resumes the anchor so normal lifecycle hooks can finish, waits a bounded period, then terminates and only force-kills as a final fallback. Restart must reuse the existing manifest/classpath, require the previous worker to have stopped cleanly, and must not run Maven/Gradle.

Worker JVM system-property overrides are explicit controller inputs. The default worker constructor supplies none and therefore preserves consumer configuration. Acceptance tooling may provide a narrow override, such as `pkb_browser=CHROME_HEADLESS`, without changing the synchronized snapshot.

## Live runtime operations

`WorkbenchLiveSession` is the controller-side scenario-bound facade for operations on the persistent paused worker. It delegates to `ControlBridgeClient` and neutral protocol DTOs; it must not reimplement Gherkin matching, mappings, browser behavior, service calls, semantic hook behavior, or Step Override matching/compilation. `resolveStep` is a read-only worker lookup and must not execute the step.

Each live operation resolves the currently owned paused scenario, performs the bridge call for that scenario, and verifies afterward that the same process id, bridge runtime id, and scenario id remain active and paused. A `FAILED` `executeStep` result is still a completed live call: the worker stays paused and available. Normal live operations must not invoke Maven/Gradle, resynchronize the project, or restart the worker.

`compileStepOverride(id, regex, source)` sends a REPLACE-mode REGEX rule to the consumer worker; the Java source must contain `{{CLASS_NAME}}`, and the worker owns class naming, compilation, classloading, registry lifetime, matching, capture extraction, and handler execution. `stepOverrides()`, `removeStepOverride(id)`, and `clearStepOverrides()` operate only on the currently owned scenario. Workbench must not compile handlers in the controller JVM.

With an active override, raw Gherkin may be override-only and need not match ordinary consumer glue. If no override matches, normal Cucumber glue matching remains authoritative. Removing or clearing an override restores that fallback immediately without rebuilding or restarting the worker.

`live-check` is the direct acceptance probe for this contract. Against the Maven example consumer it executes consumer and Pickleball Gherkin, mutates/resolves the live mapping, performs the existing `%health-full-url` service call, reads browser evidence, compiles and replaces one generated Step Override, executes override-only Gherkin, removes the override, verifies fallback behavior, confirms one PID/runtime/scenario was retained, then resumes and requires a clean exit.

## Isolation verification and scenario scope

Keep `verifyWorkbenchArtifact`, `verifyWorkbenchRuntimeBoundary`, `verifyWorkbenchPublishedDependencyContract`, root `verifyEmbeddedWorkbench`, and root `verifyStrictControllerIsolation` aligned with this contract. The checks must inspect resolved provenance, top-level and nested JAR entries, service providers, exact opaque payload count/bytes, controller/runtime class visibility, PIDs, classpaths, runtime origin, protocol version, and capabilities. Do not weaken denylist checks when packages move; update them and retain provenance checks.

For Workbench/control-bridge changes, run only affected focused Cucumber tags—normally `@control-bridge` and/or `@step-override-bridge`—with `-Dpkb_runvars.pkb_parallel=80` where practical. Never use `@all` for this migration or as a substitute for targeted validation. Record commands honestly.
