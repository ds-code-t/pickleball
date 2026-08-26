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

Workbench-only dependencies, including Jackson and the MCP SDK, belong only on the Workbench classpath. The executable and every nested JAR/service descriptor must remain free of Pickleball core, `pickleball-control-api`, bridge-server/worker implementation, consumer classes, Cucumber, Selenium, and REST-assured. The MCP adapter uses the non-Spring MCP Java SDK core plus its Jackson 2 adapter; do not replace them with the convenience/Jackson 3 artifact or Spring transports without a new architecture decision.

## Runtime ownership

The Workbench controller owns synchronization, worker process/session lifecycle, bridge client behavior, MCP stdio, the localhost UI-attach endpoint, the watched-agent control lease, and the thin Swing UI. Pickleball owns consumer-worker behavior such as the bridge server/coordinator, DynamicControl/Gherkin execution, Step Override runtime, Mapping state, browser/service-call access, and woven Cucumber integration.

`WorkbenchServices` is the shared plain-Java adapter boundary. `WorkbenchController` composes synchronization, `WorkbenchLiveSession`, `LiveScenarioPlayer`, and the control lease; MCP, HTTP attach, and Swing must delegate to that service surface instead of implementing their own worker ownership, bridge calls, Mapping semantics, Step Override behavior, scenario retry rules, or a second live Gherkin document.

The Workbench bridge client uses only `tools.dscode.control.protocol.*`. Worker-side `ControlBridgeRuntime`, `ControlBridgeCoordinator`, bootstrap, adapters, step compilation, mappings, and execution semantics stay in Pickleball. Workbench may hold the worker entry-point class name as `ControlProtocol.WORKER_MAIN_CLASS`; it must never import or load that class.

The canonical consumer-worker bridge environment is:

```text
PKB_CONTROL_BRIDGE_SESSION_DIR
PKB_CONTROL_BRIDGE_SESSION_ID
PKB_CONTROL_BRIDGE_TOKEN
PKB_CONTROL_BRIDGE_PAUSE_FIRST_SCENARIO
```

Pickleball may accept the old `PKB_STUDIO_BRIDGE_*` names as deprecated compatibility input aliases. Workbench code must emit only the neutral `PKB_CONTROL_BRIDGE_*` names. The aliases must never require Studio code or dependencies.

Project-local `.pickleball/workbench/` content is disposable state and must not be treated as source.

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
java -jar pickleball-workbench-<version>.jar ui <project>
```

The UI is player-style and execution-oriented. Its primary layout is:

```text
left rail: scenario name/tag filters + results; optional feature-file filter
center:    Live Gherkin editor (Text | Blocks) + compact Step Editor / Command
right:     Mapping | Terminal | Diagnostic Log Explorer
```

Low-level lifecycle controls live under the Session menu and existing investigation controls remain available under Advanced Controls rather than dominating the permanent workspace.

`LiveScenarioPlayer` owns presentation/session-buffer state only: stable line IDs, the editable Gherkin document, selected line, playhead, and `STOPPED` / `PAUSED` / `RUNNING` / `WAITING_FOR_STEP`. It must remain headless-testable and must not parse/execute Pickleball steps, implement runtime rewind, model Mapping inheritance, or become Swing component state.

The playhead is the user-visible needle. Clicking a scenario line instantly seeks it. Global Play always starts from the first executable step in a fresh worker context, not from the playhead. The Live Scenario Editor is an in-place Gherkin document presented as snap-together blocks whose text is Gherkin, including `Given` / `When` / `Then`. Users may edit any block, including previously executed text. The picker loads consumer scenarios into the live buffer after filtering by scenario name (starts with / contains / ends with / full match; default contains; all case-insensitive) and Cucumber tags (include AND, exclude NOT, with Feature/Rule/outline/Examples inheritance parsed from the `.feature` files). Feature-file selection is a collapsed secondary filter; with none selected, name/tag apply to every catalog scenario. The default buffer is a Workbench-owned browser demo against `URL.home`. Workbench does not write `.feature` files unless **Save** is explicitly approved. Human Save asks before copying the live scenario into the original scenario in the original `.feature` file. An attached agent must use `workbench_request_save` and wait for Allow/Deny when the UI is present. Deny and Take control write nothing. A prominent **Text | Blocks** toggle shows the same live buffer as ordinary Gherkin or as the WebView block editor without losing playhead, selection, or document text. If JavaFX/WebView is unavailable, Text is the fallback and Blocks stays honestly unavailable. WebView JavaScript must not execute Gherkin.

The Step Editor has two play actions: **Step** executes only the editor text through `WorkbenchServices.executeStep` and leaves automatic playback paused; **From Here** restarts into a fresh scenario context and runs from the selected/playhead step through the rest of the buffer. Enter while waiting at end appends the step and continues the live run. Do not strip Gherkin keywords or add a Swing-side step matcher. Worker-side `DynamicControl` / `GherkinControl` remain the only Gherkin interpreters.

### Current player implementation phase

Buffered Play / From Here / add-and-continue now execute through the existing live `executeStep` contract. Swing remains a presentation adapter: it sends displayed Gherkin unchanged and never owns a second worker manager, Mapping implementation, or Pickleball runtime.

The Mapping tab must not hard-code NodeMap names. It is one current-ParsingMap NodeMap selector plus a structured property tree. Typed edits go through `mappingPut`; renames/object replacement use `mappingRestore`. Do not create a fake ParsingMap in Swing or WebView.

The Terminal tab tails the existing worker stdout/stderr files and filters TRACE–ERROR. Do not implement it by redirecting MCP stdout or inventing log lines. The Diagnostic Log Explorer binds to Pickleball's retained diagnostic artifacts and evidence-escalation model. Do not populate either tab with fake production data.

Heavy panels use Workbench-only OpenJFX `WebView` (`JFXPanel`). That choice is documented in `docs/pickleball-workbench.md`. Do not add JCEF or Pickleball-core UI dependencies.

Existing capabilities remain available: project/synchronization status, worker lifecycle, live raw Gherkin, Mapping get/put/resolve, semantic events, Step Override list/compile/remove/clear, browser page/screenshot evidence, service-call evidence, and semantic breakpoint list/add/remove/clear.

The Swing Mapping put control sends entered values as text; it does not create a second Mapping parser or state model. Step Override source is sent unchanged to worker-side compilation and must contain `{{CLASS_NAME}}`; the UI must never compile handlers in the controller JVM. Browser/service/screenshot controls only present bridge evidence already supplied by Pickleball. Breakpoint controls delegate the hook/filter/lease contract to the shared service and must not recreate coordinator semantics.

Blocking synchronization, process, bridge, Mapping, event, screenshot, service-call, Step Override, and breakpoint actions must not run on the Swing Event Dispatch Thread. Live controls must target the controller-owned running/paused worker. When an agent holds the control lease, lock picker/filter fields, scenario list, feature-filter disclosure, the Text | Blocks toggle, and the live editor the same way other play/edit controls lock. Semantic-event cursors are worker-local and must reset when a fresh worker is started/restarted. Prefer headless-safe tests around player state, catalog/filter models, editor-view toggling, and presentation/controller delegation rather than tests requiring a visible desktop.

## MCP stdio

Workbench provides:

```text
java -jar pickleball-workbench-<version>.jar mcp <project>
```

The MCP adapter is `tools.dscode.workbench.mcp.WorkbenchMcpServer` plus `WorkbenchMcpTools`. It exposes project synchronization/status, interactive worker lifecycle, live Gherkin, Mapping operations, events/evidence, browser/service controls, semantic breakpoints, Step Override authoring, the watched-agent control lease, player-state inspection, gated Save, sparse diagnostic catalog/run/summary readers, and `workbench_investigation_emit` through `WorkbenchServices`. Consumer agents use this headless stdio server (`mcp .`), not the Swing GUI.

UI mode cannot share process stdout with stdio MCP. `ui` therefore starts a 127.0.0.1-only JSON attach facade (`WorkbenchAttachServer`) over the same tools and writes `.pickleball/workbench/attach.json` so a Copilot/MCP client can join the visible session. Bind localhost only. Do not launch a second `mcp` process against a running UI.

MCP stdout is a hard protocol boundary. `WorkbenchApplication` reserves the original process stdout for the stdio transport and redirects ordinary `System.out` output to stderr before constructing the MCP SDK/controller. The executable must explicitly remain alive for the stdio session until stdin reaches EOF; do not rely on MCP SDK worker-thread liveness to keep the JVM running. Workbench diagnostic text must use stderr or `.pickleball/workbench/logs/`; worker stdout/stderr remain separately redirected to worker log files. No banner, normal log, worker output, test output, or diagnostic chatter may be written to MCP stdout.

MCP tool failures are represented as MCP tool results with `isError=true`; they must not escape as arbitrary stdout text. Keep protocol tests covering initialize, tool listing, representative controller calls, invalid requests, Step Override compile invocation, protocol-only output, and cleanup.

Do not add generic IDE/file/build/process/collaboration tools to this MCP surface. Synchronization may invoke project wrappers through the existing synchronizer, but MCP must not become a generic Maven/Gradle execution API.

## Synchronization and worker lifecycle

Workbench synchronization is build-tool-assisted, not a replacement build system. Use the selected Maven/Gradle wrapper to establish compiled main/test output, processed resources, and the effective test runtime classpath. Gradle synchronization must use build-native init-script/task injection rather than the Gradle Tooling API. Compare **input** fingerprints (Java sources, resources, build files, dependency artifact bytes) to the last manifest before invoking the wrapper: skip when nothing that requires recompilation changed; run resource processing only when only feature/config/data changed; run full `test-compile` / `testClasses` when Java, the build descriptor, or dependencies changed. The output fingerprint in `manifest.json` is provenance, not the skip key. Always pass `-DskipTests`. Live Gherkin buffer edits must never require sync. If compiled project outputs are missing after a clean, escalate resources-only to full compile.

`.pickleball/workbench/base/classes` is synchronization provenance/reset state and must never be on a worker runtime classpath. `.pickleball/workbench/live/classes` is the one merged project-owned runtime root; main output is materialized first and test output overlays it so one class/resource path is visible exactly once. Do not treat `live/classes` as an editor. External dependency entries stay referenced from their normal caches. The synchronization fingerprint covers both merged project output and dependency artifact contents, so replacing a same-version local dependency still changes the snapshot identity.

The controller owns one interactive worker per selected project by default. Workers launch directly with Java from the existing Workbench snapshot, use the Pickleball-side worker class-name contract, and set the protocol-owned `pickleball.workbench.testOutputRoot` property so core intentionally scans the merged live root instead of relying on Maven/Gradle output suffixes. The worker PID must differ from the controller PID; its reported Pickleball code source must be exactly one captured consumer classpath entry; its version must match the synchronized manifest; and its classpath must exclude the Workbench controller artifact. Incompatible protocol/capability/origin checks fail clearly and never fall back to a bundled runtime.

Interactive workers use a session-private anchor feature and the neutral `PKB_CONTROL_BRIDGE_*` environment contract. The anchor body must be a guaranteed no-op core step. The bridge's pause-first behavior stops first at `SCENARIO_START`, which occurs before `CurrentScenarioState.startScenarioRun()` finishes Pickleball scenario initialization; Workbench treats that pause only as a bootstrap rendezvous. Before returning an interactive worker, the controller installs a one-shot `BEFORE_STEP` breakpoint filtered to the anchor marker step `---pickleball-workbench-anchor`, resumes the bootstrap pause, and lets the root scenario step initialize normal logging/runtime state. It returns only after the marker itself is paused immediately before execution. Live controller operations must run only after that promotion. Pause leases remain finite; the controller renews the owned anchor lease while active. Graceful stop cancels renewal, resumes the anchor so normal lifecycle hooks can finish, waits a bounded period, then terminates and only force-kills as a final fallback. Restart must reuse the existing manifest/classpath, require the previous worker to have stopped cleanly, and must not run Maven/Gradle.

Worker JVM system-property overrides are explicit controller inputs. The default worker constructor supplies none and therefore preserves consumer configuration. Acceptance tooling may provide a narrow override, such as `pkb_browser=CHROME_HEADLESS`, without changing the synchronized snapshot.

## Live runtime operations

`WorkbenchLiveSession` is the controller-side scenario-bound facade for operations on the persistent paused worker. It delegates to `ControlBridgeClient` and neutral protocol DTOs; it must not reimplement Gherkin matching, mappings, browser behavior, service calls, semantic hook behavior, or Step Override matching/compilation.

Each live operation resolves the currently owned paused scenario, performs the bridge call for that scenario, and verifies afterward that the same process id, bridge runtime id, and scenario id remain active and paused. A `FAILED` `executeStep` result is still a completed live call: the worker stays paused and available. Normal live operations must not invoke Maven/Gradle, resynchronize the project, or restart the worker.

`compileStepOverride(id, regex, source)` sends a REPLACE-mode REGEX rule to the consumer worker; the Java source must contain `{{CLASS_NAME}}`, and the worker owns class naming, compilation, classloading, registry lifetime, matching, capture extraction, and handler execution. `stepOverrides()`, `removeStepOverride(id)`, and `clearStepOverrides()` operate only on the currently owned scenario. Workbench must not compile handlers in the controller JVM.

With an active override, raw Gherkin may be override-only and need not match ordinary consumer glue. If no override matches, normal Cucumber glue matching remains authoritative. Removing or clearing an override restores that fallback immediately without rebuilding or restarting the worker.

`live-check` is the direct acceptance probe for this contract. Against the Maven example consumer it executes consumer and Pickleball Gherkin, mutates/resolves the live mapping, performs the existing `%health-full-url` service call, reads browser evidence, compiles and replaces one generated Step Override, executes override-only Gherkin, removes the override, verifies fallback behavior, confirms one PID/runtime/scenario was retained, then resumes and requires a clean exit.

## Isolation verification and scenario scope

Keep `verifyWorkbenchArtifact`, `verifyWorkbenchRuntimeBoundary`, `verifyWorkbenchPublishedDependencyContract`, root `verifyEmbeddedWorkbench`, and root `verifyStrictControllerIsolation` aligned with this contract. The checks must inspect resolved provenance, top-level and nested JAR entries, service providers, exact opaque payload count/bytes, controller/runtime class visibility, PIDs, classpaths, runtime origin, protocol version, and capabilities. Do not weaken denylist checks when packages move; update them and retain provenance checks.

For Workbench/control-bridge changes, run only affected focused Cucumber tags—normally `@control-bridge` and/or `@step-override-bridge`—with `-Dpkb_runvars.pkb_parallel=80` where practical. Never use `@all` for this migration or as a substitute for targeted validation. Record commands honestly.
