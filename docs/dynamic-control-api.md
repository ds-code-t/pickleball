# Dynamic Control API

Pickleball exposes a small core interception contract plus a separately organized `pickleball-control-api` source module for dynamic tooling. The behavioral control API classes are bundled into the main `tools.dscode:pickleball` artifact; consumers do not add a second Maven dependency. Versioned wire records live separately in the JDK-only `pickleball-control-protocol` module so the Workbench controller never depends on behavioral runtime classes.

## Artifact and compatibility

All consumers, including tooling that uses dynamic control, depend only on Pickleball. `pickleball-control-api` is an internal Gradle source module rather than a separately published Maven artifact.

Installing Pickleball with no control handler and never invoking the bundled control API preserves normal execution behavior. Dynamic execution, Mapping interception, bridge startup, browser/service evidence, breakpoints, and Step Overrides are opt-in.

## Browser facade

`tools.dscode.browser.BrowserFacade` is the protocol boundary for a live page. Methods are `send`, `evaluateIsolated`, `addPreload`, `addBinding`, `screenshot`, `frameTree`, `find`, `click`, and `type`. `ProtocolSelector.forDriver` returns `CdpAdapter` (Chromium, own websocket, `Page.createIsolatedWorld`, `Page.addScriptToEvaluateOnNewDocument`, `Runtime.addBinding`, `Page.captureScreenshot`, `Page.getFrameTree`), `BidiAdapter` (Firefox, `script.callFunction` in a sandbox, `script.addPreloadScript`, `script.message` channels, `browsingContext.captureScreenshot`, `browsingContext.getTree`), or `SeleniumAdapter` when that socket is not available. The Selenium choice logs one WARN per run and reason. `SeleniumAdapter.send` is refused with `error:` and `fix:` and does not call `executeCdpCommand`. `evaluateIsolated` on that adapter is `JavascriptExecutor.executeScript` after the same WARN. `PlaywrightAdapter` is the optional library `[1.40.0, 2.0.0)` and is opened with a Playwright `Page`, not by `forDriver`. `ElementOperations`, agent commands, and the control bridge do not go through this facade yet. `pkb_composite` is unchanged.

## Dynamic execution and Mapping

`DynamicControl` executes Cucumber/Pickleball steps against the currently active Pickleball test context. Detached failures are returned as structured `FAILED` results instead of automatically failing the paused scenario, allowing controller tooling to inspect a failed hypothesis and try another action.

`MappingControl` provides retry-friendly access to the live `ParsingMap` / `NodeMap` structures, caller-defined Mapping contexts, scoped overrides, snapshots, and resolution explanation. Live mutations are deliberate and are not automatically rolled back.

The control API returns `SUCCESS`, `FAILED`, or `UNAVAILABLE` for exploratory operations. Calls without a required live Pickleball context return `UNAVAILABLE`. `DynamicControl.executeStep` does not require that context. With no Cucumber scenario on the thread it runs the step on the native engine. Glue is `NativeDynamicSteps.useGlue` or the `pkb_engine_glue` class list. A live or paused scenario keeps the existing detached execution, including the failure restore. The control bridge uses that native call only when no scenario lane is active. A live lane is unchanged. Step creation, mapping interception, browser evidence, and service calls still return `UNAVAILABLE` without a live scenario.

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

`look=` is computed style on that same query string: `look=color=red` or `look=opacity>0.5,background-color=#fff`. A comma is AND and a pipe is OR inside the stage. `contrast` is the contrast of `color` against the painted `background-color`. It is not a screenshot match. There is no engine named `attribute`. A step may add `with look color equaling red` or `with look opacity greater than 0.5` after the existing `with id attribute` / `with id noattribute` clause. That clause becomes the `look=` stage and the live find applies it. `with id attribute` and `with id noattribute` still filter. `pkb_composite=off` fails that step instead of ignoring look. The older attribute phrases are unchanged.

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

`observe` reads `Network.*`, `Runtime.consoleAPICalled`, and `Log.entryAdded` from the CDP Session query, resolve, action, and settle already use for that page. It does not open a capture socket beside that Session. Filters are type, URL substring, time window, and limit. `--wait-ms` is forwarded. `0` reads the retained deque only and fails when no Session is retained, instead of pretending a fresh socket had history. A positive wait opens one Session only when none exists, then collects live traffic for that window. `--events` is a recorded snapshot of that same shape. There is no second buffer. Socket closure and other drain failures are failures, logged as `observe failure`, not as empty traffic. An attached Session with no matching rows is empty traffic (`count` 0). `capture-scenario` does not write a feature when that read fails. When the caller omits `--session` and `--cdp`, observe uses the engine session focused for the current browser and tab. Switching tabs on one browser selects the Session for that page target (`/devtools/page/<id>`), not a host:port shared by every tab. A second browser still has its own session.

`launch --template` writes `.pickleball/agent-launch.json` for the requested project. `DriverConstruction` applies that file, not whichever directory the process was started in. `--arg` is repeatable and is forwarded by the CLI, bridge `extraArgs`, MCP, and Workbench. `--start` calls the existing `DriverConstruction` path. It does not invent a second launcher. Without `--start`, launch does not create live window or tab handles. Templates: `chrome-headed-verbose`, `chrome-headed-quiet`, `chrome-headless-verbose`, `chrome-headless-quiet`, `edge-headed-quiet`, `edge-headless-quiet`, `chrome-headed-logs`, and `chromium-headed-quiet` (the existing Chrome launcher; the fallback already searches `chromium` and `chromium-browser`). A live window is one browser instance. A live tab is a Selenium handle inside that instance. An unknown handle fails. Page helpers act on the focused browser and tab. A ranked handle (`frameId:backendNodeId`) keeps the browser and tab captured with the query result; a later focus change does not reassign it, and the handle is not tried against every driver. It is not only a `data-backend-node-id` attribute, and it stores the browser and tab even when the hit has no visual geometry. Entering that frame fails the command when the frame is missing, the owner cannot be named, `switchTo().frame(name)` throws, or restoring the temporary name and `data-pkb-bridge` does not return true. An originally empty `name` or `data-pkb-bridge` is put back as an empty attribute, not removed. A driver without CDP does not report frame entry unless `switchTo().frame` actually runs. Query ownership is this thread's engine Session, not the global join pointer another thread may have moved. All-contexts hits capture the tab before the query is issued. A failed page-session open does not leave that pointer on the target that failed, does not mark the destination tab focused, and does not report the tab activation as successful. A public window or tab switch does not return an open handle when that focus fails, including when the requested tab's page target cannot be confirmed. A null or blank `Target.getTargetInfo` is not treated as a successful browser-level focus for a specific tab when the driver published `se:cdp` or a Chromium `debuggerAddress` (`goog:chromeOptions` or `ms:edgeOptions`); a target already confirmed for that tab may still open its page session. A destination read that switches tabs puts Selenium back on the thread's focused tab when that tab is known, including when the current handle could not be read and the destination target is unknown. A thread with no engine Session yet does not adopt another thread's join pointer for its first query. Closing a tab focuses the surviving tab's engine session when that page session opens. If it does not, the close fails and the engine session is not left on the closed tab. Known geometry without an owner is not resolved against whichever driver is focused now. Removal searches drivers, tabs, and child frames that received the surface, and succeeds only when that surface was actually removed. A helper failure is a failure. `windows --close` fails when quit throws and does not report the window closed. Those commands do not create a page snapshot. No live driver and no resolved target is a failure.

Chat is stored under `.pickleball/runs/<run-id>/agent/chat/<channel>.jsonl` (or `.pickleball/agent` when no run id is set). Sources are `agent`, `human`, and `system`. Message ids continue from the highest id already stored, including rolled files, so a new process does not restart the cursor. The read of that highest id and the append share an exclusive lock file, so two processes cannot take the same id. A second process prints ready before it blocks on that lock. When a channel file passes the size cap it is renamed aside and kept in history and poll. Send and receive are both logged. The Chat tab loads history, polls, and sends on the selected diagnostic run. It does not send with a null run.

`highlight --preset=yellow|outline-red` paints a find-interface handle. A ranked `frameId:backendNodeId` is resolved on the browser and tab that own that frame, including entering the frame, and `Overlay.highlightNode` uses that backend node id. Entering the frame calls `switchTo().frame(name)` after a predefined `Runtime.callFunctionOn` returns the iframe `name` or `id` (setting `name` only when both are empty). It does not call `WebDriver.findElement`. The helper does not require a pickled `data-backend-node-id` attribute. `DOM.resolveNode` plus a predefined `Runtime.callFunctionOn` stamps `data-pkb-bridge` so replace, insert, overlay, and DOM highlight can find the ordinary node. No agent-supplied script is evaluated. The highlight color follows the preset. An explicit style may set the border and the background when the whole value is a named color, `#RGB`, `#RRGGBB`, or `rgb()` / `rgba()` on `background`, `background-color`, `border-color`, or `outline-color`. `border` or `outline` shorthand that is not only a color (for example `border:9px dashed #abc`) and CSS `color` (text) are not reduced to CDP `borderColor`, including when the handle is a ranked `frameId:backendNodeId`. `Overlay.enable` may run; `Overlay.highlightNode` does not. They are applied as reversible DOM styling, the result includes `styleNote`, and `unhighlight` restores the original `style` attribute. A successful CDP highlight does not also change DOM style. `unhighlight` and cleanup call `Overlay.hideHighlight` on every tab that had a highlight. A tab switch that fails is a failure and does not hide a different tab. The hide call fails the command when `Overlay.hideHighlight` throws. DOM style highlighting remains the fallback, and a repeated DOM highlight does not overwrite the remembered CSS. `unhighlight` with no handle and cleanup clear DOM styles in every managed tab and in ranked child frames, not only the current document. Cleanup, remove, and input polling reset to the top document after selecting a tab, then visit ranked child frames and the live child-frame tree, including a surface whose in-process site record is gone. A helper that does not return `ok: true`, a tab or frame enumeration failure, a failed frame entry, or a failed tab restore fails the command. A missing or non-map frame tree, and a null or empty window-handle list, are enumeration failures. Cleanup does not pretend the current tab was visited when tab enumeration returned nothing. Scenario teardown still quits owned drivers, then propagates that cleanup failure. Native-overlay completion and later configured object cleanups still run when in-page cleanup throws. Geometry for a ranked handle keeps the query's driver and tab; a later focus change does not reassign it. A second managed browser keeps its own engine session. Two tabs on one host:port keep two sessions, selected by the page target. Focusing that driver and tab selects that session. The result is labeled `style_modified` plus a sentence.

`overlay` places a surface from the remembered Geometry view (`visual_viewport_relative`) for a resolved handle, or from caller-supplied Geometry JSON. Caller `x`/`y` is used only when that geometry is absent and no handle was supplied. An unresolved handle is rejected even if `x`/`y` were provided. There is no `getBoundingClientRect` fallback and no default box. Clicks and form changes are polled across every managed tab, not only the driver's current document, and written to the `overlay` chat channel. `html-write`, `html-replace`, and `html-insert` accept structural HTML. A small parser strips script elements, iframe `srcdoc`, encoded and slash-separated `on*` handlers, `javascript:` URLs even when whitespace, entities, or leading C0 controls sit inside the scheme, and active nested documents (`data:text/html`, `data:application/xhtml+xml`, `data:image/svg+xml`, `data:text/xml`, `data:application/xml`). Normal structural tags stay, including an unquoted URL that contains `/`. That is not a tag allowlist. Injected surface ids are allocated under the same exclusive file lock as the placement metadata. An explicit `--html` / `--page` file keeps its own counter. A live id is taken from the destination page after that handle's owner and tab are selected, not from the globally focused page. That page shares one counter across run ledgers (debugger address, that tab's own page target, and tab), so two fresh runs do not both receive `pkb-1`, and a write aimed at a page that already has `pkb-1` does not mint another `pkb-1` there. `Target.getTargetInfo` is read while the destination tab is selected. When the current Selenium handle cannot be read, that tab is selected and confirmed before the read, or a target already remembered for that same tab is reused. A blank target does not start a new counter. The active window's target is not paired with a different Selenium handle, and it is never stored under that other tab, including two tabs of one browser. A ledger that already contains `pkb-N` and has no `next` advances past those ids. Two processes writing the same file still cannot take the same id. Every injected element, including descendants, carries `data-pkb-agent=agent_injected` and a sentence. Agents still see those nodes. Replace keeps an undo snapshot beside the file, not inside the document. `html-remove` and `html-cleanup` restore it. Removal logs the snippet sha256 and a short summary from the placement file (`page.html.place.json` for an explicit page, or `.pickleball/runs/<run-id>/agent/placements.json` for a live command), so a later process can still recover them. The Workbench-native overlay shows the guidance text and the provenance sentence from the same command object. It is removed on `overlay-remove`, `html-remove`, `html-cleanup`, `stop`, `worker-stop`, `close-window`, headless play completion (including a failed step), GUI playback pause, stop, and natural completion, scenario cleanup, and live-session close. In-page surfaces are cleaned when the scenario ends.

Agent loop for service calls: observe the buffer, filter by URL or time, then `capture-scenario`. The file is existing service-call component syntax (`MAP "REQUEST"`, `EXECUTE SERVICE CALL`, Examples). No new Gherkin phrase.

Logs use `pkb_composite_log` entities `observe`, `launch`, `window`, `chat`, `highlight`, `overlay`, `html`, and `capture`. They are off unless named, date-stamped, and size-capped.

