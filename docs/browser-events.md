# Browser events

`browser-events` is one stream for everything the browser reports, from whichever backend serves the session (see [Browser protocols](browser-protocols.md)). `observe` stays as it was: it is the network and console subset, with its existing flags and output.

## Rows

Schema `urn:pickleball:browser-event:1`. One JSON object per row; fields are omitted when empty.

| Field | Meaning |
|-------|---------|
| `seq` | Monotonic per session; the cursor for `--since`. |
| `at` | ISO-8601 time with offset, when the row was recorded. |
| `session` | The protocol host session id. |
| `run` | Run id (`--run-id`, `PKB_RUN_ID`, else `live`). `step` and `scenario` are set by `browser-events --step= --scenario=`. |
| `context` | Tab or target id (CDP target id, BiDi browsing context). |
| `frame` | Frame id for navigation rows. |
| `source` | `playwright`, `bidi`, `cdp`, `classic`, or `runtime`. |
| `kind` | `net`, `console`, `error`, `ws`, `dialog`, `download`, `file-dialog`, `nav`, `target`, `perf`, `security`, `audit`, `sw`, `storage`, `permission`, `input`, `dom`. |
| `subkind` | For example `request`, `response`, `finished`, `failed` (net); `log`, `error`, `log.violation`, `log.intervention` (console); `committed`, `same-document` (nav); `opened`, `closed` (dialog); `mutation` (dom). |
| `url`, `method`, `status`, `level`, `text` | As the backend reported them. `text` is capped at 2000 characters. |
| `requestId`, `durationMs`, `bytes` | Network correlation, timing, and encoded size. |
| `redacted` | Names of values replaced by `[redacted]`. |

## Reading

```
browser-events [--start] [--since=<seq|iso>] [--kinds=net,console] [--match=<text|re:regex>] [--level=error] [--limit=<n>] [--wait-ms=<n>]
browser-events --follow [--since=<seq>] [--wait-ms=<n>] [--limit=<n>]
browser-events --status | --stop
```

The first call starts the project's protocol host and subscribes once, on Playwright when the session is Playwright-attached, else BiDi, else CDP. The reply names the backend (`via`). `--since` returns only rows after that seq (or that time). `--follow` prints NDJSON rows as they arrive for up to `--wait-ms` (default 10000, max 120000), then one `{"cursor":...}` line; it runs in the CLI process, so a follow never blocks the start-host worker or the command lane. When two backends report the same event (same `requestId` and phase, or the same console text within 50 ms), the row from the backend that served the subscription is kept.

## Toggles and retention

| Key | Default | Meaning |
|-----|---------|---------|
| `pkb_browser_events` | on | `off` keeps no rows and writes no file. |
| `pkb_browser_events_kinds` | all | Comma list of kinds to keep. `--kinds` on `--start` narrows it further. |
| `pkb_browser_events_max_kb` | 8192 | Spill cap per file; the full file rotates once to `.1`. |
| `pkb_browser_events_ring` | 5000 | Rows kept in memory for `--since`. |
| `pkb_proto_log` | on | `.pickleball/logs/proto-YYYY-MM-DD.log` (capped at 2 MB per day); `off` turns it off. |
| `pkb_proto_host_idle_min` | 240 | The host stops itself after this many idle minutes. |

Rows spill to `.pickleball/runs/<run>/browser-events-YYYY-MM-DD.ndjson`.

## Redaction

Before a row reaches the ring, the file, or a reply: `Authorization`, `Proxy-Authorization`, `Cookie`, `Set-Cookie` and token-like headers, cookie values, query parameters and JSON fields whose names are secret (password, token, secret, api key, plus `pkb_store_secret_fields`), and `Bearer`/`Basic` credentials or `token=`-style pairs in text become `[redacted]`, and the row lists what was redacted. `--reveal` (control tier, where a verb offers it) shows a value in that reply only; it is never logged.

## Errors and logpoints

`errors [--since] [--follow]` is `browser-events --kinds=error`. Each uncaught exception (`subkind=exception`) and unhandled rejection (`subkind=unhandled-rejection`) carries `text` (the first line of the message), `url`, `line`, `column`, a `stack` of up to 20 `function (url:line:col)` frames, `worker` when it came from a worker target, and `original` (`source`, `line`, `column`, `name`) when the script names a source map the page can fetch. Sources: CDP `Runtime.exceptionThrown`, BiDi `log.entryAdded` type `javascript`, Playwright `pageError` (message only). The map is fetched through the page's own network with a page `fetch()`, and decoded by repo code following the Source Map v3 format ([ECMA-426](https://tc39.es/ecma426/)); when it cannot be fetched or decoded the generated location stays. Console rows keep `level`, `text` (arguments joined), `url` and `line`; `Log.entryAdded` rows keep their source as `subkind` (`log.violation`, `log.intervention`, `log.network`, ...).

`logpoint --at=<url|path>:<line>[:<col>] --expr=<js>` logs the expression each time that line runs, without pausing. It is a CDP `Debugger.setBreakpointByUrl` whose condition calls `console.log('[logpoint <id>]', value)` and returns false, so its output arrives as console rows. `--list` and `--clear` manage them. Chromium only.

`pkb_fail_on_js_error=warn|fail` checks scenarios: at the end of each step, page errors that happened during it (collected over WebDriver BiDi through Selenium's `LogInspector`) are logged (`warn`) or fail the step (`fail`) with the message and stack. `pkb_js_error_ignore=<regex>` allows errors whose message matches. A driver without BiDi (`pkb_bidi=false`) prints one WARN and is not checked.

## Network

Network rows (`kind=net`) carry `subkind` `request`, `response`, `finished` and `failed`, joined by `requestId`. Requests keep `httpMethod`, `type`, `initiator` and `requestHeaders`. Responses keep `status`, `statusText`, `mime`, `protocol`, `remote` (ip:port), `cache` (`memory`, `disk`, `service-worker`, `prefetch` or `network`), `security`, `ttfbMs`, `headersMs` and `responseHeaders`. `finished` keeps `encodedBytes`. Headers are redacted when they hold credentials or cookies (`Authorization`, `Proxy-Authorization`, `Cookie`, `Set-Cookie`, `X-Api-Key`, `X-Auth-Token`, `X-CSRF-Token`, `X-XSRF-Token`, and any name containing `token`, `secret` or `password`), and the row lists them in `redacted`. Chrome does not report `Set-Cookie` in `responseReceived`, so that header usually never reaches a row at all.

`net --capture-bodies=none|api|all [--max-kb=<n>]` controls response bodies. `none` (the default) keeps none. `api` keeps JSON, XML, text and form bodies of `fetch`/XHR requests. `all` keeps every body. The host keeps the last 200 bodies, each cut at `--max-kb` (default 256). `net-body --request-id=<id> [--out=<file>]` returns one body. JSON bodies have secret-named keys (`password`, `token`, `secret`, `apikey`, ...) replaced with `[redacted]`; other text bodies have `key=value` secrets masked. A binary body is only written to `--out`, never printed. Sources: CDP `Network.getResponseBody` on `loadingFinished`; BiDi `network.addDataCollector` and `network.getData`; Playwright `Response.body()`.

WebSocket rows (`kind=ws`): `created`, `handshake`, `frame-sent`, `frame-received`, `closed` and `error`, with `url`, `requestId`, `bytes`, `method` (`opcode-<n>` for frames) and `text` (the first 200 characters of the payload). EventSource messages are `subkind=eventsource-message`. CDP and Playwright report frames; BiDi has no WebSocket frame events yet, so a BiDi session shows only the handshake request.

`har --start [--content=omit|embed] [--url-filter=<glob|re:regex>]`, `har --status` and `har --stop [--out=<file>]` record a HAR 1.2 file. When Playwright serves the session, Playwright's own HAR recorder writes it. Otherwise the host builds it from the network rows with the in-repo `HarWriter` (entries grouped by `requestId`, with timings, headers, query string, status, content size and, for `--content=embed`, the captured body). The default output is `.pickleball/runs/<run>/session.har`, written owner-only. Headers in the HAR are redacted the same way as rows.

`capture-scenario --har=<file>` turns a HAR from `har --stop`, or from any browser's DevTools export, into a service-call scenario. It gives the same feature as `capture-scenario --events=<file>` for the same requests.

## Network control

`net-route --match=<glob|re:regex> --action=fulfill|abort|continue|modify` routes matching requests. `fulfill` answers with `--status` (default 200), `--body=<text>|@file` and `--headers=<json>|@file`; `abort` fails the request; `modify` sends it with `--headers` merged in; `continue` lets it through. `--delay-ms` holds it first and `--times=N` drops the route after N hits. A glob matches the whole URL (`*` any run, `?` one character), `re:` is a regex search, and text without wildcards is a substring. The newest route wins. Backends in order: Playwright `BrowserContext.route`, BiDi `network.addIntercept` with `provideResponse`/`continueRequest`/`failRequest`, CDP `Fetch.enable` with `fulfillRequest`/`continueRequest`/`failRequest`. Every hit is a `kind=net subkind=routed` row (`method` is the action, `text` the route id). `net-unroute --id=|--all` removes routes; `net-block --match=` is an abort route. `doctor --protocols` prints `routes=` and one `route.<id>=` line each, and `browser-events --status` shows the count.

`net-headers --set=<json>|@file` adds request headers to every request (`--clear` removes them). `net-auth --credential=<secret-ref>` answers HTTP auth challenges: the ref is `env:NAME`, `prop:NAME` or a pkb setting name, its value is `user:password`, and it is resolved in the CLI and never printed or logged. `ws-route --match= --rules=[{"on":"ping","reply":"pong","close":false}]` mocks WebSockets through Playwright `routeWebSocket` (no server is contacted). `emulate --network=offline|online|slow-3g|fast-3g|<downKbps,upKbps,latencyMs>` sets network conditions; `net-cache --disable|--enable` turns the HTTP cache off or on; `security --ignore-cert-errors[=false]` accepts invalid certificates (CDP); `csp --bypass[=false]` ignores Content-Security-Policy from the next navigation. These are control tier and logged to `.pickleball/logs/proto-*.log` (credentials and header values are not). Everything is undone when the host stops (`browser-events --stop`). There is no Gherkin step for routes; use the CLI or MCP tools.

## Emulation, clock and permissions

`emulate` changes the live session: `--locale`, `--timezone`, `--geo=lat,lon[,accuracy]`, `--ua` (with `--ua-ch=<json>` for client hints on CDP), `--media=<feature:value,...>`, `--forced-colors=active|none`, `--reduced-motion`, `--touch`, `--orientation=portrait|landscape`, `--cpu=<rate>x`, `--vision=<deficiency>`, `--no-js`, `--viewport=WxH[@dpr]` and `--network=` (see Network control). Each option goes to the first backend in fallback order that can apply it. The reply has `applied` (option to backend) and `unsupported` (option to the reason for each backend tried); an option is never dropped silently. Backends: BiDi `emulation.*` and `browsingContext.setViewport`; CDP `Emulation.*` (locale also sets `acceptLanguage`, so `navigator.language` follows); Playwright `emulateMedia`, `setGeolocation` and `setViewportSize` (locale, timezone, UA, touch, CPU, vision and JavaScript are fixed when a Playwright context is created, so they fall to BiDi or CDP). `emulate --show` lists active overrides with their backend, plus network conditions and the clock. `emulate --reset` clears them; the host clears them when it stops.

`clock --install[=<iso>]`, `--pause-at=<iso>`, `--fast-forward=<dur>`, `--run-for=<dur>`, `--set-fixed=<iso>` and `--resume` use Playwright's clock whenever Playwright can reach the browser. On CDP alone, `--pause-at`, `--run-for`, `--fast-forward` and `--resume` use `Emulation.setVirtualTimePolicy`; `--install` and `--set-fixed` are unsupported there. Durations are `500ms`, `30s`, `5m`, `1h` or `mm:ss`.

`permissions --grant=<list> [--origin=]`, `--deny=<list>` and `--reset` use Playwright `grantPermissions`/`clearPermissions` (Playwright cannot deny, so a deny goes to the next backend), BiDi `permissions.setPermission` (needs `--origin`), or CDP `Browser.setPermission`/`resetPermissions`.

## Contexts, tabs, storage, downloads, uploads and dialogs

`context-new [--storage-state=<file>]` makes an isolated browser context (BiDi `browser.createUserContext`, CDP `Target.createBrowserContext`, Playwright `newContext`); `context-list` shows every context with its tabs (id, url, title) and `context-close --id=` closes one. `tab-new [--url] [--context]` opens a tab and `tab-activate --id=` brings one to the front. `page-history --back|--forward|--reload[=hard]` moves a tab through its history (the name avoids the Workbench `history` notes verb). `print [--out=<file.pdf>]` writes a PDF (Playwright `page.pdf`, BiDi `browsingContext.print`, CDP `Page.printToPDF`). Contexts made here are closed when the host stops.

`storage --get|--set=<json>|--clear [--kind=cookies,local,session,idb,opfs] [--origin=] [--context=]` reads, writes or clears cookies, localStorage, sessionStorage, IndexedDB and the origin private file system. Cookie values are redacted in replies and rows unless `--reveal` (control tier) is given, and revealed values are never logged. `storage-state --save=<file>|--load=<file>` uses Playwright's storage-state format on every backend. The file holds live session secrets: it is written owner-only (rw-------), it is never logged, and it should not be committed; keep it out of version control.

`downloads [--dir=] [--deny]` sends downloads into the project (default `.pickleball/runs/<run>/downloads`) or blocks them; each is a `kind=download` row (began, progress, completed, canceled); `--list` and `--wait [--count] [--timeout]` read them. `upload --selector= --file=a,b --via=` sets a file input through CDP `DOM.setFileInputFiles`, BiDi `input.setFiles` or Playwright `setInputFiles`; `upload --on-dialog --file=` answers the next file chooser (CDP `Page.setInterceptFileChooserDialog`, BiDi `input.fileDialogOpened`) with `kind=file-dialog` rows. `drop --selector= --file=|--data=<mime>:<text>` drops files or data (Playwright `Locator.drop`, else a DataTransfer built in an isolated world).

`dialogs --policy=accept|dismiss|prompt:<text>|ask|default` answers alert, confirm and prompt dialogs as they open on CDP (`Page.javascriptDialogOpening`), BiDi (`browsingContext.userPromptOpened`) or Playwright (`page.onDialog`). Accepting a prompt without text keeps its default value. Each answer is a `kind=dialog subkind=handled` row; a dialog another client closed first is `subkind=unanswered`. A Playwright client attached to the same browser with no dialog listener of its own dismisses every dialog it sees, whatever the policy; use `--via=playwright` when a Playwright session stays attached. The Gherkin alert steps go through `BrowserAlerts`: Selenium's alert API first, then Selenium BiDi `browsingContext.handleUserPrompt`, then the reflective CDP call.

## Input, accessibility locators, WebAuthn, extensions and WebMCP

`input --actions=<json>|@chain.json` runs a W3C action chain: `key` sources (keyDown/keyUp; W3C key code points such as `\uE007` for Enter), `pointer` sources (mouse, pen or touch; pointerMove with `origin` viewport or pointer, pointerDown/pointerUp), `wheel` sources (scroll with deltaX/deltaY) and pauses, tick by tick. Backends in order: BiDi `input.performActions`, CDP `Input.dispatchKeyEvent`/`dispatchMouseEvent`/`dispatchTouchEvent`, Playwright mouse, keyboard and touchscreen. `input --release` lets go of held keys and buttons. A chain with only coordinates and keys is human tier, so it runs in a human-locked session; an element origin needs BiDi and is refused there. The log records the action count, never key values.

`find --role=<role> [--name=] [--description=]` finds elements by accessibility role and exact name through the BiDi accessibility locator (`browsingContext.locateNodes`), Playwright `getByRole` (with its `description` option) or CDP `Accessibility.queryAXTree`. Hits have `source=role` with tag, id, text, description and box. `find` without `--role` ranks as before.

`aria-snap [--boxes] [--depth=] [--mode=ai]` (also `snap --aria`) prints Playwright's aria snapshot. In `--mode=ai` each node has a `[ref=eN]`; `act --aria-ref=eN --kind=click|type|hover|press|select` acts on it through Playwright `getByRef`. Aria snapshots need Playwright, which the host attaches for these verbs.

`webauthn --add-authenticator [--protocol=ctap2|u2f] [--resident] [--uv]` adds a virtual authenticator (Playwright `credentials()` first, CDP `WebAuthn.*` for u2f or non-default options), so passkey create and get ceremonies complete without hardware. `--add-credential=@cred.json` adds a credential (rpId, id, userHandle, signCount); the private key comes only from `--private-key=<secret-ref>` (resolved in the CLI) or a `privateKeyFile`, and a key inside the JSON is refused. `--list` shows ids, rpIds, user handles and sign counts, never keys; `--remove [--id=]` removes one credential or the authenticator.

`extension --install=<unpacked dir> | --uninstall=<id> | --list` uses BiDi `webExtension.install`/`uninstall` or CDP `Extensions.loadUnpacked`/`uninstall`/`getExtensions`. Chrome allows these only when started with `--enable-unsafe-extension-debugging`; without it the reply says so. `webmcp --list | --call=<tool> --params=` lists or calls the tools a page registers through Playwright `page.webmcp`; on a page or browser without WebMCP it reports `unsupported: webmcp` with the reason.

## Performance, traces and memory

`perf --vitals` reports LCP, CLS, INP, FCP and TTFB. Pickleball's own PerformanceObserver script (no library) is installed for new documents in an isolated world (CDP `Page.addScriptToEvaluateOnNewDocument` with a world name, BiDi `script.addPreloadScript` in a sandbox; Playwright init scripts run in the page world) and read from that world. The metrics follow the published definitions: LCP is the last `largest-contentful-paint` entry before input ([web.dev/articles/lcp](https://web.dev/articles/lcp)); CLS is the largest session window of `layout-shift` entries without recent input, with gaps under 1 s and windows under 5 s ([web.dev/articles/cls](https://web.dev/articles/cls)); INP is the worst interaction latency from `event` entries grouped by `interactionId`, or the 98th percentile from 50 interactions on ([web.dev/articles/inp](https://web.dev/articles/inp)); FCP is the `first-contentful-paint` paint entry ([web.dev/articles/fcp](https://web.dev/articles/fcp)); TTFB is `responseStart` minus `activationStart` of the navigation entry ([web.dev/articles/ttfb](https://web.dev/articles/ttfb)). Each value is a `kind=perf subkind=vital` row (`metric`, `value`, `url`); on Chromium, CDP `PerformanceTimeline` events add `subkind=timeline` rows. Run `perf --vitals` once before navigating so the observer sees the whole page load.

`perf --metrics` returns CDP `Performance.getMetrics`, or navigation timing and `performance.memory` from the page on other backends. `perf --trace-start [--categories=]` and `perf --trace-stop --out=trace.json` record CDP `Tracing` as Chrome trace-event JSON (open it in DevTools or Perfetto); `--format=playwright` records a Playwright trace zip instead, and `--aria-snapshots`/`--screen-snapshots` pass through (also on `pw trace start`). `memory --gc` collects garbage (Playwright `requestGC`, CDP `HeapProfiler.collectGarbage`); `memory --snapshot --out= --max-mb=` streams a heap snapshot to a file and stops at the cap (`truncated:true`).

The run summary (`workbench/summary.json`) gains a `browser` section and `workbench/browser.html` when the run has a browser-events spill: vitals per navigation, the 10 slowest requests, failed requests and JS errors (URLs, timings and first lines only; no bodies or headers). `perf --report [--run-id=]` builds the same section on demand.

## Security, audits, workers, service workers, storage and lifecycle

With a CDP stream, pickleball attaches worker targets (dedicated, shared and service workers) and auto-attaches OOPIFs, so their console, errors and network join the stream with a `target` field (`worker`, `shared_worker`, `service_worker`). Workers are not paused at start, and the browser's own extension workers are not attached. On BiDi, `script.realmCreated`/`realmDestroyed` rows cover worker realms.

- `kind=security`: `subkind=state` from CDP `Security.visibleSecurityStateChanged` (secure, insecure, neutral, with certificate protocol, issuer and subject), and `subkind=certificate-error` when a request fails with `ERR_CERT_*`/`ERR_SSL_*` (the failed `net` row still lands).
- `kind=audit`: CDP `Audits.issueAdded` (mixed content, CORS, CSP, cookie, deprecation, low contrast, form issues). `subkind` is the issue code in kebab case and `method` the raw code. `audits --list [--since=]` reads them back; `audits --check-forms` runs `Audits.checkFormsIssues`.
- `kind=sw`: registration, version status and worker errors (CDP `ServiceWorker`). `sw --list [--wait-ms=]` lists the page registrations and their versions; the browser's own extension workers appear only with `--all`. `sw --unregister=<scope>` and `sw --bypass[=false]` (`Network.setBypassServiceWorker`, switched off again when the stream stops).
- `kind=storage`: localStorage and sessionStorage item added, updated, removed and cleared (CDP `DOMStorage`). Rows carry the origin, `key` and `valueLength`; the value is `[redacted]`. `browser-events --reveal` (control tier) shows values, which the host holds only in memory: they are never written to the ring, the spill or a log.
- `kind=nav` and `kind=target`: navigation started, committed, failed and aborted, history updates, `subkind=bfcache-not-used`, `subkind=window-open` (`Page.windowOpen`), `subkind=page-attached` for a page opened after the stream started (with its first real URL), and `subkind=crashed`. A crashed target is counted in `host --status` (`crashedTargets`) and `doctor` prints a warning.

## Screencasts, sharing the browser and Selenium sessions

`screencast --start --out=<file.webm>` records a webm through Playwright's screencast (it needs Playwright's own ffmpeg, which pickleball never downloads; without it the reply says how to install it or to use `--frames-dir`) (`--fps=`, `--show-actions` overlays the actions Playwright performs). `screencast --start --frames-dir=<dir>` writes JPEG frames instead: Playwright frames, BiDi `browsingContext.startScreencast` where a browser has it, or CDP `Page.startScreencast` (frames are acknowledged as they land; no encoder is bundled, so CDP and BiDi cannot make a webm). `screencast --stop` reports `form` (`webm` or `frames`), `path`, and `bytes` or `frames`.

`share-browser --name=<name> [--port=<n>]` shares the Playwright-attached browser through Playwright `Browser.bind`: a local pipe by default, or a WebSocket on 127.0.0.1 with `--port`. The reply has the `endpoint`, the `playwright-cli attach <name>` command and a `@playwright/mcp --endpoint` config. Every bind and stop is logged (the endpoint id is not). Security: anything on this machine that can reach the endpoint can drive the browser, including its signed-in sessions; share only while needed and end it with `share-browser --stop`. Control tier.

`pkb_scenario_events=on` (default off) gives a scenario's own Selenium session browser-event rows through Selenium's high-level BiDi modules (`LogInspector` for console and page errors, `Network` for requests, responses and failures), on the BiDi socket Selenium already holds. Rows are `source=bidi`, `transport=selenium`, are redacted like any other row and spill to the project's browser-events file.

## DOM changes (push)

Pickleball listens for DOM changes instead of polling the page. One page script runs a `MutationObserver` and reports debounced, batched, scoped deltas through the backend's push channel:

| Backend | Channel |
|---------|---------|
| Playwright | `BrowserContext.exposeBinding` plus `addInitScript` for later documents |
| BiDi (Chrome, Firefox) | `script.addPreloadScript` with a `channel` argument; batches arrive as `script.message` |
| CDP | `Runtime.addBinding` plus `Page.addScriptToEvaluateOnNewDocument`; batches arrive as `Runtime.bindingCalled` |

Selenium's own DOM-mutation handler (`HasLogEvents.onLogEvent(domMutation(...))`) is not used: it lives in the test JVM's WebDriver and is tied to one CDP version module, while the protocol host and the agent verbs need the same listener on every backend. The CDP DOM domain events (`DOM.childNodeInserted` and the rest) are not used either: they need `DOM.getDocument` and node bookkeeping on every change, which costs more than one observer callback.

Each batch is one row with `kind=dom`, `subkind=mutation`, `text` (a short summary), and a `dom` object: `childList`, `attributes`, `text` counts, `added`, `removed`, `records`, up to ten `targets` (`tag#id` or `tag.class`), and `attributeNames`. `durationMs` is the time from the first change to the batch being sent. Pickleball's own overlay, toolbar and panel (any element with a `data-pkb-*` attribute, a `pkb-` id, or a `pkb-*` tag, and `data-pkb-*` attribute changes) are left out unless `--own` is given; a person's actions in those surfaces arrive as their own typed overlay events (card answered, field typed, chat sent).

| Use | Command |
|-----|---------|
| Stream | `browser-events --dom [--selector=<css>] [--kinds=childList,attributes,text] [--debounce=<ms>]`, then read `--kinds=dom` |
| Watch | `watch --selector=<css>\|--ref=<ref> --kinds=childList,attributes,text [--attributes=a,b] [--contains=<text>] [--debounce=<ms>] [--until=change\|<condition>] [--duration=<ms>]` |
| Wait | `wait --until-change [--selector=<css>\|--ref=<ref>] [--timeout=<ms>]` |
| New control | `wait --condition=new-control` wakes on a pushed change (`wake=push`), or polls every 100 ms when the page refuses the binding (`wake=poll`) |
| Snapshot delta | `snap --since=<snapCursor>` adds `delta`: `mode=push` with `batches`, `mutations`, `targets` and `unchanged` when the page socket stays open in the start-host worker; `mode=poll` otherwise |

The collaboration waits (`answer-wait`, `ask-and-wait`, `ui-poll --wait`, `chat-poll --wait`, `chat-turn`) wake when the panel binding delivers an event, a stored overlay input lands, or a chat message is sent, instead of sleeping out their poll interval. Their replies say `wake=push` and `wakeMs`. A 250 ms backstop re-check stays for pages whose surface events only arrive through the queue drain. `pkb_push_wake=off` restores the old fixed sleeps (`wake=poll`).

Measured on the build box (headless Chrome 156, start-host session, three rounds each), the time from a page click handler running to the agent receiving the event was 1, 2 and 1 ms pushed (`watch --selector --kinds=childList,text`) against 36, 41 and 31 ms polled (`watch --until=text=`, which re-reads the page every 40 ms). These are wall-clock times on one machine: `Date.now()` in the click handler against the agent's receipt time.

## Protocol host

The host is one process per project. It listens on 127.0.0.1 only, on a random port, behind a random token stored in `.pickleball/proto/host.json` (owner-only where the OS allows), and holds the CDP, BiDi, and Playwright connections, the event ring, and every route or override a protocol verb installs. `browser-events --stop` stops it and removes those routes. Its output goes to `.pickleball/proto/host.log`.
