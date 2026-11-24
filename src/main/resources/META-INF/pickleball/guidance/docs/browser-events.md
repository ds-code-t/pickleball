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
