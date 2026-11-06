# Dynamic Control API

Pickleball exposes a small core interception contract plus a separately organized `pickleball-control-api` source module for dynamic tooling. The behavioral control API classes are bundled into the main `tools.dscode:pickleball` artifact; consumers do not add a second Maven dependency. Versioned wire records live separately in the JDK-only `pickleball-control-protocol` module so the Workbench controller never depends on behavioral runtime classes.

## Artifact and compatibility

All consumers, including tooling that uses dynamic control, depend only on Pickleball. `pickleball-control-api` is an internal Gradle source module rather than a separately published Maven artifact.

Installing Pickleball with no control handler and never invoking the bundled control API preserves normal execution behavior. Dynamic execution, Mapping interception, bridge startup, browser/service evidence, breakpoints, and Step Overrides are opt-in.

## Dynamic execution and Mapping

`DynamicControl` executes Cucumber/Pickleball steps against the currently active Pickleball test context. Detached failures are returned as structured `FAILED` results instead of automatically failing the paused scenario, allowing controller tooling to inspect a failed hypothesis and try another action.

`MappingControl` provides retry-friendly access to the live `ParsingMap` / `NodeMap` structures, caller-defined Mapping contexts, scoped overrides, snapshots, and resolution explanation. Live mutations are deliberate and are not automatically rolled back.

The control API returns `SUCCESS`, `FAILED`, or `UNAVAILABLE` for exploratory operations. Calls without a required live Pickleball context return `UNAVAILABLE`.

## Semantic hooks

`ControlRuntime` exposes synchronous semantic hooks across scenario, step, phrase, Mapping, DOM, browser, driver, and service-call boundaries. Handlers may observe, pause by blocking, or use supported skip/value interception semantics. With no handler installed, original Pickleball behavior is retained.

`BEFORE_FIXED_WAIT` and `AFTER_FIXED_WAIT` fire only when a framework fixed wait is about to run. A skip decision suppresses that wait. A literal `save` and a nested `RUN_STEP` do not take the 300 ms browser-settle sleep, even when a session is already open, so they do not emit `BEFORE_FIXED_WAIT`. A click, type, or other page action that still waits does.

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

The descriptor advertises the current and minimum-compatible protocol versions, capabilities, PID, Pickleball implementation version, and runtime code source. Workbench rejects incompatible capabilities/versions, a same-process worker, a runtime origin outside the synchronized consumer classpath, version drift, or a worker classpath containing the controller. It never falls back to executing a Workbench-bundled runtime.

The bridge keeps live operations on the real scenario thread through `ControlBridgeCoordinator`. This preserves access to thread-local Cucumber/Pickleball state, glue, browser, services, mappings, and other scenario resources.

Bridge capabilities include:

- runtime/status and active-scenario discovery;
- bounded semantic events;
- finite-lease pause/resume;
- retry-friendly detached step execution;
- read-only step resolution (`/v1/steps/resolve`, capability `resolve_step`) to consumer glue, Pickleball dynamic/framework steps, Step Overrides, or unmatched text;
- Mapping get/put/resolve/snapshot/restore;
- browser page/screenshot evidence;
- Pickleball-native element inspection;
- existing Pickleball service-call execution/evidence;
- semantic breakpoint management;
- scenario-scoped Step Override management.

Workbench owns the controller-side protocol client. MCP and Swing access these capabilities through `WorkbenchServices` / `WorkbenchController`; they do not connect to the bridge independently or implement a second runtime. Workbench imports only `tools.dscode.control.protocol.*`; bridge server/coordinator/bootstrap and conversion to runtime objects remain worker-side.

## Scenario targeting and finite pauses

A consumer runtime may observe more than one scenario thread. Controller calls may target a scenario id discovered from the bridge. A wrong or stale id returns `UNAVAILABLE`; the bridge does not guess.

Pauses and breakpoint pauses use finite leases. Expiry resumes execution automatically. Workbench renews the pause it owns while its persistent interactive worker remains active and resumes before clean shutdown.

## Semantic event evidence

The bridge retains bounded immutable semantic hook metadata rather than arbitrary consumer object graphs. Event reads are read-only and do not require the scenario-thread command queue, so reading evidence cannot consume a hook or block a paused scenario.

## Browser and service investigation

`ElementControl.inspect(...)` resolves a Pickleball category through the active `ExecutionDictionary`, or forwards one engine query string when `query` is set. That string is the composite selector: `css=`, `xpath=`, `text=`, `image=`, `near=`, `below=`, `above=`, `left-of=`, `right-of=`, `>>` stages, and AND/OR/NOT. Workbench, MCP, and the control bridge only forward it. There is no second locator language. Gherkin stays phrase sugar via `SelectorTranslator`. Inspection requires an existing scenario-owned browser and returns `UNAVAILABLE` rather than creating one. A controller query with no `exposure` is `human`: displayed, non-zero size, and inside the visual viewport. `lower` is opt-in on that one query, not a session setting, and every returned node carries reason codes plus a sentence. An action with no exposure is `human` and refuses a node a person could not click. `lower` still acts and keeps the sentence, which starts with `acted on node <handle>;`. A binary that does not list `exposure` or `limit` omits that flag and does not invent a result. `left-of`, `right-of`, `above`, `near`, and `below` are visual distance on that same query, using the geometry view. A binary that does not list `geometry` or the engine name logs `rel fallback` and invents no box. Gherkin `after` and `before` stay document order (`following::` / `preceding::`). `--explain` returns rank and score on that same query. `image=` with promote binds a DOM node when the engine can. Otherwise the hit is `visualOnly` and is not a DOM node. A query plus an optional limit returns handles. A later act sends one of those handles as its selector. Offline HTML is a labelled source read and does not run the engine query. Category+text and Gherkin do not send `exposure`.

`ServiceCallControl.execute(selector)` runs the same reusable service-call component selected by normal `CALL:` semantics. Existing Pickleball discovery, Mapping, request/configuration processing, REST/SOAP execution, response mapping, and failure behavior remain authoritative.

## Semantic breakpoints

Semantic breakpoints are temporary filters over existing `ControlHook` boundaries. They may filter by scenario, hook, hook signature, current step text, and current phrase text. They use the same finite pause lane as ordinary control pauses and disappear with the consumer runtime.

They are semantic Pickleball interception points, not arbitrary Java source breakpoints.

## Step Overrides

The consumer worker also owns scenario-scoped Step Overrides. Workbench can compile/register REGEX/REPLACE rules through the bridge; matching and execution occur before ordinary Cucumber glue fallback. When no override matches, normal Cucumber matching remains authoritative. Removing or clearing an override restores ordinary fallback without restarting the worker.

Worker-side compilation requires `javax.tools.JavaCompiler`. Workbench sends a Java source template containing `{{CLASS_NAME}}`; the worker owns generated naming, compilation, classloading, matching, captures, replacement, and cleanup.

## Architecture boundary

The control API and bridge intentionally remain independent of MCP, Spring, GUI frameworks, generic project IDE behavior, and build orchestration. Pickleball owns live execution semantics. Workbench is the external controller/adaptation layer and its artifact contains no Pickleball core, behavioral control API, Cucumber, Selenium, or service runtime. The only shared code is the dependency-neutral wire protocol.

## Agent browser control

`POST /v1/agent/command` forwards one agent command to `AgentControl`. The CLI calls that same class directly. Workbench, MCP `workbench_agent_command`, and the bridge do not keep a second implementation. The worker capability is `agent_command`. No active scenario is required.

Commands: `observe`, `launch`, `windows`, `tabs`, `chat-send`, `chat-history`, `chat-poll`, `highlight`, `unhighlight`, `overlay`, `overlay-poll`, `overlay-remove`, `html-write`, `html-replace`, `html-insert`, `html-remove`, `html-cleanup`, `capture-scenario`.

`observe` reads `Network.*`, `Runtime.consoleAPICalled`, and `Log.entryAdded` from the CDP Session the engine process already retained for that page. It does not open a new empty Session on every call. Filters are type, URL substring, time window, and limit. `--wait-ms` is forwarded. `0` reads the retained deque only and fails when no Session is retained, instead of pretending a fresh socket had history. A positive wait attaches and collects live traffic for that window. `--events` is a recorded snapshot of that same shape. There is no second buffer. Socket closure and other drain failures are failures, logged as `observe failure`, not as empty traffic. An attached Session with no matching rows is empty traffic (`count` 0). `capture-scenario` does not write a feature when that read fails.

`launch --template` writes `.pickleball/agent-launch.json` for the requested project. `DriverConstruction` applies that file, not whichever directory the process was started in. `--arg` is repeatable and is forwarded by the CLI, bridge `extraArgs`, MCP, and Workbench. `--start` calls the existing `DriverConstruction` path. It does not invent a second launcher. Without `--start`, launch does not create live window or tab handles. Templates: `chrome-headed-verbose`, `chrome-headed-quiet`, `chrome-headless-verbose`, `chrome-headless-quiet`, `edge-headed-quiet`, `edge-headless-quiet`, `chrome-headed-logs`, and `chromium-headed-quiet` (the existing Chrome launcher; the fallback already searches `chromium` and `chromium-browser`). A live window is one browser instance. A live tab is a Selenium handle inside that instance. An unknown handle fails. Page helpers act on the focused browser and tab. A ranked handle (`frameId:backendNodeId`) routes only to the browser and tab that own that frame; it is not tried against every driver, and it is not only a `data-backend-node-id` attribute. Removal searches drivers and tabs and succeeds only when that surface was actually removed. `windows --close` fails when quit throws and does not report the window closed. Those commands do not create a page snapshot. No live driver and no resolved target is a failure.

Chat is stored under `.pickleball/runs/<run-id>/agent/chat/<channel>.jsonl` (or `.pickleball/agent` when no run id is set). Sources are `agent`, `human`, and `system`. Message ids continue from the highest id already stored, including rolled files, so a new process does not restart the cursor. When a channel file passes the size cap it is renamed aside and kept in history and poll. Send and receive are both logged. The Chat tab loads history, polls, and sends on the selected diagnostic run. It does not send with a null run.

`highlight --preset=yellow|outline-red` paints a find-interface handle. A ranked `frameId:backendNodeId` uses CDP `Overlay.highlightNode` on the driver that owns that frame, not on every Chromium driver. The highlight color follows the preset (`yellow` or `outline-red`); an explicit `#RRGGBB` style overrides the border. A successful CDP highlight does not also change DOM style. `unhighlight` calls `Overlay.hideHighlight` and still succeeds when the highlight was CDP-only and DOM resolution fails. Cleanup and scenario end call `Overlay.hideHighlight` as well. DOM style highlighting remains the fallback, and a repeated DOM highlight does not overwrite the remembered CSS. The result is labeled `style_modified` plus a sentence. No agent JavaScript is evaluated.

`overlay` places a surface from the remembered Geometry view (`visual_viewport_relative`) for a resolved handle, or from caller-supplied Geometry JSON. Caller `x`/`y` is used only when that geometry is absent and no handle was supplied. An unresolved handle is rejected even if `x`/`y` were provided. There is no `getBoundingClientRect` fallback and no default box. Clicks and form changes are polled across every managed tab, not only the driver's current document, and written to the `overlay` chat channel. `html-write`, `html-replace`, and `html-insert` accept structural HTML. A small parser strips script elements, iframe `srcdoc`, encoded and slash-separated `on*` handlers, and `javascript:` URLs even when whitespace or entities sit inside the scheme. Normal structural tags and their text stay. That is not a tag allowlist. Every injected element, including descendants, carries `data-pkb-agent=agent_injected` and a sentence. Agents still see those nodes. Replace keeps an undo snapshot beside the file, not inside the document. `html-remove` and `html-cleanup` restore it. Removal logs the snippet sha256 and a short summary from the placement file (`page.html.place.json` for an explicit page, or `.pickleball/runs/<run-id>/agent/placements.json` for a live command), so a later process can still recover them. The Workbench-native overlay shows the guidance text and the provenance sentence from the same command object. It is removed on `overlay-remove`, `html-remove`, `html-cleanup`, `stop`, `worker-stop`, `close-window`, headless play completion (including a failed step), and live-session close. In-page surfaces are cleaned when the scenario ends.

Agent loop for service calls: observe the buffer, filter by URL or time, then `capture-scenario`. The file is existing service-call component syntax (`MAP "REQUEST"`, `EXECUTE SERVICE CALL`, Examples). No new Gherkin phrase.

Logs use `pkb_composite_log` entities `observe`, `launch`, `window`, `chat`, `highlight`, `overlay`, `html`, and `capture`. They are off unless named, date-stamped, and size-capped.

