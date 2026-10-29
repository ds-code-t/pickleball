# Direct-control handoff

Contract version 1 (`session-descriptor.v1`). Pickleball 2.1.18.

A live Workbench session can publish a descriptor so an external tool can attach to the browser Pickleball already owns. dom-composite 2.4.0 is the optional tool. Pickleball stays the owner of the browser process and the WebDriver. The external tool only attaches, acts, and detaches.

A normal `mvn test` or `gradlew test` does not write a descriptor. An unset or blank `pkb_direct_control_provider` does nothing: no provider process, no extra log line, no change to that run.

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
| `startedAt` | ISO-8601 instant. Fixed for this session. |
| `updatedAt` | ISO-8601 instant. Changes when the descriptor is replaced. |

`session list` prints one tab-separated line per session whose state is not `stopped`:

```text
sessionId	runId	state	browserType	cdpDebuggerAddress	currentUrl
```

A null browser, address, or URL is `-`. No live session prints `(no live sessions)`. `session list --json` prints a JSON array of the same descriptor objects. A stopped session stays on disk and is omitted from the list. `session info` still returns it.

`session info <sessionId>` prints the descriptor file. `--json` prints those same bytes. `<sessionId>` may also be the `runId` when the session id does not match. An unknown id exits 1 and prints `No session descriptor for <id>.` A bare `session` command is still the controller launch, not this query.

The Workbench window shows this CLI output. It is not a second copy of the descriptor.

## Attach with dom-composite 2.4.0

Read the descriptor first (`session info <sessionId> --json`, or the file). Stop if `cdpDebuggerAddress` is null. Stop if `state` is `running`, `stopping`, or `stopped`. Act only while `state` is `paused` or `held`.

If the address has no scheme, prefix `http://`. `127.0.0.1:9222` becomes `http://127.0.0.1:9222`. A value that already starts with `http://`, `https://`, or `ws://` is passed unchanged. Composite accepts `http://host:port` or a page `ws://` URL.

Do not pass a `url`. Do not launch a browser. Attach requires the local-attach acknowledgement so composite records `launched: false`.

CLI:

```text
composite session attach --cdp http://127.0.0.1:9222 --id <sessionId> --i-accept-local-attach
```

`composite attach --cdp ...` is the same command. Later commands in that process use `--session <sessionId>` or `--cdp` with the same address.

Java (`dev.domcomposite.Client`, binding 2.4.0). `openSession` sends `session create` / `POST /v1/session/create`. It takes the fields this contract names (`cdp` and `id`). Also pass `i_accept_local_attach` so composite records `launched: false`. Do not pass `url`. The CLI command that only attaches, and does not create a browser, is `composite session attach`.

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

Drive the page with composite's own CLI, MCP (`composite serve --stdio`), or HTTP `/v1`. That is in addition to Gherkin and the Workbench session commands, not a replacement.

## Detach

Close the composite session before Pickleball stops the run or closes the browser.

```text
composite session close --id <sessionId>
```

`Session.close()` and `Client.close()` send that close. Closing a session this process did not launch sets `killed` false and does not stop Chrome or Edge. Detach drops the composite registry entry and the DevTools socket. It must not call WebDriver quit, must not kill the browser process, and must not delete the run directory.

If composite exits or crashes, the browser stays up. Pickleball is still the owner.

## Pause and hold

Do not click, type, or navigate through composite while a Gherkin step is running (`state` is `running`).

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

The value may instead be the path of a `composite` binary. Unset or blank means Pickleball does not start a provider. When it is set, Workbench may start that binary and pass the descriptor path, the session id, and the CDP address. The provider attaches, acts, and detaches under this contract. Its stdout and stderr go to that run's session log.

The binary is found at runtime. `COMPOSITE_BIN` is the path. Otherwise `COMPOSITE_HOME` or a sibling checkout named `dom-composite` next to the project may have `target/debug/composite` or `target/release/composite`. The Java binding is not on a registry. Builds and ordinary tests do not need the binary. A missing binary skips a provider check; it does not fail the suite.

Pickleball writes the descriptor, the CLI, the hold, and the provider key. dom-composite stays a general tool and does not grow a Pickleball-only flag.
