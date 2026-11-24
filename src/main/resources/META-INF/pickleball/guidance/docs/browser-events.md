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

## Protocol host

The host is one process per project. It listens on 127.0.0.1 only, on a random port, behind a random token stored in `.pickleball/proto/host.json` (owner-only where the OS allows), and holds the CDP, BiDi, and Playwright connections, the event ring, and every route or override a protocol verb installs. `browser-events --stop` stops it and removes those routes. Its output goes to `.pickleball/proto/host.log`.
