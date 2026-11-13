# Direct-control handoff

Contract version 1 (`session-descriptor.v1`). Pickleball 3.0.0.

A live Workbench session can publish a descriptor so the DOM engine can attach to the browser Pickleball already owns. `pkb_direct_control_provider=composite` runs the in-repo Java DOM engine. Rust is an explicit override only. Pickleball stays the owner of the browser process and the WebDriver. The engine only attaches, acts, and detaches.

A normal `mvn test` or `gradlew test` does not write a descriptor. An unset or blank `pkb_direct_control_provider` does nothing: no provider process, no extra log line, no change to that run.

## Find

Workbench, MCP, and the control bridge forward one engine query string through `ElementOperations` → `DomCompositeAdapter`. The string is the composite selector (`css=`, `xpath=`, `text=`, `image=`, `near=`, `below=`, `above=`, `left-of=`, `right-of=`, `>>`, AND/OR/NOT). Those relative stages are visual distance in the geometry view, not document order. An engine without that view does not get a box invented in Java. `--explain` ranks the batch. `image=` promotes to a node when asked and the engine can bind one. A visual-only hit stays labeled and is not a DOM node. A limit returns handles. One later act uses one handle. There is no batch click. Category+text still uses the execution dictionary. Gherkin phrases, including `after` and `before` as document order, are unchanged. A controller query defaults to `exposure=human` (inside the visual viewport). `lower` is per query and labels every node. An action defaults to `human` and refuses a target a person could not click. Offline `--html` is a labelled source read and does not pretend to run that query.

## Descriptor

Schema `session-descriptor.v1`. `contractVersion` is `"1"`. Later versions add fields or are negotiated. They do not change the meaning of a field that already exists.

The file is `.pickleball/runs/<runId>/session/descriptor.json`. It is written only for a live session and replaced atomically. The same bytes are what `session info` prints.

| Field | Meaning |
|---|---|
| `schema` | `session-descriptor.v1` |
| `contractVersion` | `"1"` |
| `sessionId` | Stable id for this live session. Join on this. |
| `runId` | The run directory name. |
| `workspacePath` | Absolute workspace directory for that run. |
| `browserType` | `chrome`, `edge`, or null. |
| `cdpDebuggerAddress` | Chrome or Edge debugger address (`host:port`), or null when CDP is not available. |
| `currentUrl` | Current page, or null. |
| `windowHandles` | WebDriver window handles. Empty when there are none. |
| `state` | `idle`, `running`, `paused`, `held`, `stopping`, or `stopped`. |
| `compositeSessionDir` | Absolute path of this run's composite registry, `.pickleball/runs/<runId>/session/composite`, or null when no provider has attached. An older file that omits the field means null. |
| `compositeSessionId` | Id recorded by `composite session attach`, or null. Join with this id and `compositeSessionDir`. |
| `startedAt` | ISO-8601 instant. Fixed for this session. |
| `updatedAt` | ISO-8601 instant. Changes when the descriptor is replaced. |

`session list` prints one tab-separated line per session whose state is not `stopped`:

```text
sessionId	runId	state	browserType	cdpDebuggerAddress	currentUrl
```

A null browser, address, or URL is `-`. No live session prints `(no live sessions)`. `session list --json` prints a JSON array of the same descriptor objects. A stopped session stays on disk and is omitted from the list. `session info` still returns it.

`session info <sessionId>` prints the descriptor file. `--json` prints those same bytes. `<sessionId>` may also be the `runId` when the session id does not match. An unknown id exits 1 and prints `No session descriptor for <id>.` A bare `session` command is still the controller launch, not this query.

The Workbench window shows this CLI output. It is not a second copy of the descriptor.

## Attach with the DOM engine

Read the descriptor first (`session info <sessionId> --json`, or the file). Stop if `cdpDebuggerAddress` is null. Stop if `state` is `running`, `stopping`, or `stopped`. Act only while `state` is `paused` or `held`.

If the address has no scheme, prefix `http://`. `127.0.0.1:9222` becomes `http://127.0.0.1:9222`. A value that already starts with `http://`, `https://`, or `ws://` is passed unchanged. The engine accepts `http://host:port` or a page `ws://` URL.

Do not pass a `url`. Do not launch a browser. Attach requires the local-attach acknowledgement so the engine records `launched: false`.

CLI, when you are attaching yourself and Pickleball has not already attached. Consumers run the engine from the Pickleball jar (a bare `domengine` script exists only from `:dom-engine:installDist`):

```text
java -jar pickleball-<version>.jar domengine session attach --cdp http://127.0.0.1:9222 --id <sessionId> --i-accept-local-attach --session-dir <directory>
```

`<version>` is `3.0.0`. `composite session attach ...` in older examples is the same verb set. The command Pickleball itself runs is whatever path the locator or launcher resolved.

Use a directory you own. Do not reuse `.pickleball/runs/<runId>/session/composite` while Pickleball's provider is attached; that id is already in the registry.

`domengine attach --cdp ...` is the same command. Later commands use `--session <sessionId> --session-dir <directory>`, or `--cdp` with the same address. Attach itself exits as soon as the registry entry is written.

Java (`dev.domcomposite.Client` / `Session`). This is the stdio client for the engine CLI. By default that CLI is the in-repo Java engine launcher (`java -cp ... dev.domengine.cli.DomEngineMain`). An explicit binary uses the same client. `openSession` sends `session create` / `POST /v1/session/create`. It takes the fields this contract names (`cdp` and `id`). Also pass `i_accept_local_attach` so the engine records `launched: false`. Do not pass `url`. The CLI command that only attaches, and does not create a browser, is `domengine session attach` (older examples say `composite session attach`).

```java
try (Client client = new Client();
     Session session = client.openSession(Map.of(
            "cdp", "http://127.0.0.1:9222",
            "id", sessionId,
            "i_accept_local_attach", true
     ))) {
    session.query(Map.of("css", "body"));
}
```

`Session.query`, `click`, `type`, `scroll`, and `resolve` are the page operations. `Session.close()` detaches.

HTTP is `POST /v1/session/attach` with `"cdp"`, `"id"`, and `"i_accept_local_attach": true`. MCP and the other bindings only forward those arguments.

Drive the page with the engine CLI, MCP (`domengine serve --stdio`, or `composite serve --stdio` on an explicit binary), or HTTP `/v1`. That is in addition to Gherkin and the Workbench session commands, not a replacement.

## Detach

Close the engine session before Pickleball stops the run or closes the browser.

```text
java -jar pickleball-<version>.jar domengine session close --id <sessionId> --session-dir <directory>
```

`composite session close ...` is the same verb set. `Session.close()` and `Client.close()` send that close. Closing a session this process did not launch sets `killed` false and does not stop Chrome or Edge. Detach drops the engine registry entry and the DevTools socket. It must not call WebDriver quit, must not kill the browser process, and must not delete the run directory.

If the engine exits or crashes, the browser stays up. Pickleball is still the owner.

## Pause and hold

Do not click, type, or navigate through the engine while a Gherkin step is running (`state` is `running`).

`pause` is the existing session command. The cooperative hold is:

```text
session hold <sessionId>
session release <sessionId>
```

`session hold` sets `state` to `held`. Workbench does not advance steps while the state is `held` or `paused`. `session release` returns the session to the state it had when the hold began (`running` or `paused`) and step execution continues from there. The descriptor file updates on both commands. Read it again with `session info` before acting, and again before detach.

`stopping` and `stopped` mean detach only. Do not attach.

## Optional provider

Workspace `runvars.properties`, or the system property of the same name:

```text
pkb_direct_control_provider=composite
```

The value may instead be the path of an engine binary. Unset or blank means Pickleball does not start a provider. When it is set, and the live session has a CDP address, Workbench runs one short command and waits for it to exit (30 seconds). Attach is not a resident process. It records the session and prints JSON. A non-zero exit fails that start and names the exit code. Stdout and stderr go to that run's `session/provider.log`. Start and detach append a line to `.pickleball/agent-log`.

Every command Pickleball runs passes the same per-run directory:

```text
--session-dir .pickleball/runs/<runId>/session/composite
```

The absolute directory and the id from the attach JSON are written on the descriptor as `compositeSessionDir` and `compositeSessionId`. Those names stay. Pickleball does not set `PICKLEBALL_SESSION_DESCRIPTOR`. The engine does not read it.

Detach runs `session close` for that id and directory. It does not destroy a process. A session this command did not launch returns `"killed": false` and leaves Chrome or Edge running. A missing engine skips; it does not fail the run.

`composite` resolves to the in-repo Java engine (`dev.domengine.cli.DomEngineMain`) when its classes are on the classpath. The Pickleball jar and the thin Workbench jar both carry it, and Workbench supplies Jackson from its runtime libs. `JavaEngineLauncher` writes a small `java -cp <engine+jackson> dev.domengine.cli.DomEngineMain "$@"` launcher (`.cmd` on Windows). Workbench uses it through `DirectControlProvider`. Element operations use it through `CompositeBinaryLocator`. The Java engine takes `--session-dir` before or after any verb, as the Rust CLI did, and prints the same pretty JSON layout (`"launched": false`).

`DirectControlProvider.resolveBinary("composite", ...)` and `CompositeBinaryLocator.locate` share one explicit-engine order (`explicitEngineCandidates`):

1. system property `pkb_composite_bin`
2. system property `pkb_runvars.pkb_composite_bin`
3. env `COMPOSITE_BIN`
4. env `pkb_composite_bin`

Then, unless Rust is requested (`pkb_composite_engine=rust` as a property, a `pkb_runvars.` property, env `PKB_COMPOSITE_ENGINE`, or env `pkb_composite_engine`), the Java engine launcher. In a normal run the Java engine is on the classpath, so that launcher is what runs.

Then the Rust homes, and only when the steps above did not choose an engine: `COMPOSITE_HOME`, or a sibling checkout named `dom-composite` (`target/debug/composite`, then `target/release/composite`). The root locator continues on that Rust path only: a classpath native `dev/domcomposite/natives/<classifier>/composite` (`linux-x64`, `linux-aarch64`, `windows-x64`, `macos-aarch64`, `macos-x64`; Windows uses `composite.exe`), then the Maven classifier jar `dev.domcomposite:composite-native:2.4.0:<classifier>` under `~/.m2`, then an executable named `composite` on `PATH` when `pkb_composite=on`. The provider does not search those last three. Any other `pkb_direct_control_provider` value is a path.

A missing engine logs once (`error:` / `fix:`) and that run keeps the legacy Selenium element path. `pkb_composite=off` skips the lookup and uses Selenium for elements. `pkb_composite=auto` is the default. Selenium also remains the element path when the engine cannot be started, when a join fails, or when the WebDriver has not joined an engine session. WebDriver still starts the browser either way.

Element operations join an already-attached session when the driver publishes a CDP address. That join uses `Client.openSession` with `cdp` and `i_accept_local_attach`. It does not launch a second browser and it does not replace `pkb_direct_control_provider`.

Logging is off unless `pkb_composite_log` is `all` or a comma list of `location`, `adapter`, `session`, `element`, `fallback`, `batch`, `state`, `position`. `batch` writes one `TRACE batch mode=<composite|selenium-batch|selenium-legacy> selector=<by> nodes=<count> durationMs=<n>` line per describe and no per-element line. `state` writes one `TRACE state snapshot miss fallback` line only when a state check has no snapshot. `adapter` writes the verb, snapshot, scan, wait, nest, and live lines. `position` writes one `TRACE position apply` line per find. Lines go to the CLI and to `composite-YYYY-MM-DD.log` under `pkb_composite_log_dir` (default the temp directory `pickleball-composite-logs`). One file is capped at 1 MiB.

### Optional Rust override

Set `pkb_composite_engine=rust` (or `PKB_COMPOSITE_ENGINE=rust`), or set an explicit `pkb_composite_bin` / `COMPOSITE_BIN` path, to skip the Java engine. The override then looks at `COMPOSITE_HOME`, a sibling `dom-composite` checkout, the classpath natives, and `dev.domcomposite:composite-native:2.4.0:<classifier>`.

The Rust 2.4.0 binary's desktop verbs are only `list`, `attach`, `walk`, `query`, `resolve`, and `close`. Browser actions on that binary are `click`, `type`, and `scroll`. That binary has no `desktop click` and no `desktop type`. A desktop click or type then fails immediately. The CLI prints `error:` and `fix:`. It is not a silent no-op, and it is not hidden when logging is off. The `fix:` names the missing verb and still names `dev.domcomposite:composite-native:2.4.0`. Unset the Rust request and the explicit binary to use the in-repo Java engine, which supports `desktop click` and `desktop type`.

Native binaries are not published. Pickleball extracts them when they are present and Rust was requested or an explicit binary was not already chosen:

| Coordinate | Value |
|---|---|
| Maven | `dev.domcomposite:composite-native:2.4.0:<classifier>` |
| Classifiers | `linux-x64`, `linux-aarch64`, `windows-x64`, `macos-aarch64`, `macos-x64` |
| Binary entry | `composite`, or `composite.exe` on `windows-x64` |
| Classpath | `dev/domcomposite/natives/<classifier>/composite[.exe]` |
| Local jar | `~/.m2/repository/dev/domcomposite/composite-native/2.4.0/composite-native-2.4.0-<classifier>.jar` |

The runtime decision reads one set of inputs: project directory, environment, class loader, and Maven home. Production uses the working directory, the process environment, the context class loader, and `user.home`. A test pins those four so a host binary, a host `COMPOSITE_BIN`, or a host `~/.m2` classifier cannot leak into that decision.

## Desktop click and type

The Java engine reports desktop `list`, `attach`, `walk`, `query`, `resolve`, `close`, `click`, and `type`. Desktop click and type work by default. The `error:` / `fix:` refusal applies only when `capabilities.desktop` lacks the verb, which is the Rust 2.4.0 override above. When an engine's `capabilities.desktop` lists `click` or `type`, `DomCompositeAdapter` forwards that verb.

When those two descriptor fields are set, the session is already attached. Do not attach again. Join it:

```text
java -jar pickleball-<version>.jar domengine --session-dir <compositeSessionDir> query --session <compositeSessionId> --css 'body'
```

The same directory is the MCP join:

```text
java -jar pickleball-<version>.jar domengine --session-dir <compositeSessionDir> serve --stdio
```

`composite --session-dir ...` is the same verb set on an explicit binary. Pass `compositeSessionId` on the later tool call. Close with `session close` only when you are the owner of that registry entry. If Pickleball started the provider, Pickleball closes it when the live session stops. Do not close it out from under a step that is still using it. Hold the session first.

Pickleball writes the descriptor, the CLI, the hold, and the provider key. The DOM engine stays a general tool and does not grow a Pickleball-only flag.

Agent browser commands (`observe`, `launch`, windows and tabs, chat, highlight, overlay, HTML write, `capture-scenario`) are the Workbench CLI. They do not add a Pickleball flag to the engine. `observe` reads the same CDP Session query and settle use, not a second capture socket. `capture-scenario` writes existing service-call Gherkin. Join the descriptor session before a live `observe` against that browser. Focusing another managed browser selects that browser's engine session. Focusing another tab on the same browser selects the session for that page target (`/devtools/page/<id>`), not a host:port shared by every tab. Query, observe, and the page helpers follow the Session this thread actually holds, not a global join pointer another thread has moved. A thread with no Session yet does not open from that pointer. A failed page-session open does not leave that pointer claiming the session and does not leave the destination tab focused. The same refusal applies when the only published debugger address is Chromium `debuggerAddress`, not only `se:cdp`. Closing a tab focuses the surviving tab's engine session.

