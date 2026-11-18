# Pickleball 3.0.0 Workbench Field Report: Live Browser Control, Page UI, and Chat for AI Agents

- **Date:** 2026-11-18
- **Pickleball:** 3.0.0 (commit f5ccc9f4a0272188ef0c0eca15e0cdcf d64fc92b), guidance exported with `export-guidance`
- **Setup:** a Maven consumer project on Windows (PowerShell), visible Chrome (`pkb_browser=chrome`), 945 × 1132 window
- **Target page:** an enterprise web application reached through a test SSO URL. Its main content sits in an iframe, and it shows loading masks during navigation.
- **Goal:** drive a live browser through Workbench, inject Pickleball's predefined page UI (chat panel, callouts, labels, badges, spotlight, question card), chat with a person through the page, and measure how fast DOM lookups and actions are.

## Summary

| Area | Result |
|------|--------|
| Navigation and step execution | Works. First navigation 10–11 s (includes browser launch); later navigations about 1 s. |
| Element lookup (`workbench_element_inspect`) | Fast: 13–69 ms warm, 336 ms first call. Replies are very large. |
| Page UI components (`ui` / `ui-apply`) | Work: step badges, label, border, overlay callout, spotlight, and question card all rendered. 7 ops in 607 ms. |
| In-page chat panel (`panel-open`) | Broken. Opening it starts an endless loop in the page script (about 1.6 CPU cores). Chrome stalls within minutes, then the worker restarts and the browser is lost. Agent messages never showed in the panel. |
| Two-way chat with the person | Not achieved, because of the panel bug and the worker restarts. |
| Worker resilience | Poor. The worker restarted three times. Each automatic restart left the old Chrome window running. |
| CLI route for page UI | Does not reach a live browser. Page UI only worked through the MCP tool `workbench_agent_command`. |

## How the session was driven

1. `export-guidance` through the Maven wrapper (`mvnw.cmd`).
2. A temporary one-step scenario (`* navigate to: <application URL>`), then `discover --runvar=pkb_browser=chrome --runvar=pkb_environment=<env>`, which passed.
3. `isolate` plus CLI `execute-step`. Steps worked, but no page UI verb could reach the browser (see B2).
4. Switched to the documented forwarder, MCP `workbench_agent_command`. The consumer project did not allow registering an IDE MCP server, so a small loopback stdio client kept one `mcp` process alive and forwarded calls to it. The worker came from `workbench_request_control` and `workbench_worker_start`.
5. All temporary files, processes, and the scratch scenario were removed afterwards.

## Bugs

### B1 — In-page chat panel runs an endless loop (critical)

**Symptom:** After `panel-open`, Chrome's CPU use climbs from 0.00 to 1.3–1.7 cores and stays there until `panel-close`.

| Page state | Chrome CPU (cores) |
|------------|--------------------|
| Application page, nothing injected | 0.00–0.16 |
| `panel-open` (chat tab) | 1.59–1.67 |
| `panel-open --minimized=true` | 1.35 |
| `panel-open --tab=steps` | 1.36 |
| `panel-open --theme=light` | 1.60 |
| After `panel-close` | 0.01–0.02 |
| One label only, no panel | 0.02–0.03 |
| Plain localhost page plus `panel-open` | 1.63–1.66 |

The loop also happens on a plain page, so the application under test is not the cause.

**Likely cause** (`tools/dscode/browser/surface/pkb-runtime.js` in `pickleball-3.0.0.jar`):

```javascript
on(document, 'toggle', function () { raiseLayer(); schedule(); }, true); // line ~477
function raiseLayer() { // line 129
  ...
  if (layer.matches(':popover-open')) layer.hidePopover();
  layer.showPopover();
}
```

`raiseLayer()` hides and re-shows the `<pkb-layer>` popover. Each hide or show fires a `toggle` event. The capture-phase document listener catches that event and calls `raiseLayer()` again. Nothing filters out the layer's own events and nothing guards against re-entry, so the cycle never ends. `panel-open` starts the cycle. Labels do not, because they call `raiseLayer()` only when a page `<dialog>` appears. A page dialog would therefore start the same loop with any placement.

**Suggested fix:** ignore toggle events whose target is the layer (`if (e.target === layer) return;`), or skip `raiseLayer()` when the layer is already the top popover.

**Effects seen:**

- Chromedriver calls stall. A thread dump of the stuck worker showed the scenario thread parked in `RemoteWebDriver.getPageSource` → `ControlBridgeCoordinator.browserPageOnScenarioThread`.
- `workbench_browser_page` and `workbench_browser_screenshot` returned `CONTROL_TIMEOUT` after 60 s ("Timed out waiting for the scenario thread to complete the control command").
- The bridge stopped answering (`Could not communicate with control bridge ... after 21 s`), and the selector watchdog restarted the worker.

### B2 — CLI page UI verbs never reach the live isolate browser (high)

With an `isolate` session running and its browser on the application page:

- `windows --run-id=<run-id>` and `tabs --run-id=<run-id>` returned `start: 0`
- `label --run-id=<run-id> ...` returned `no live browser; label was not applied`
- `panel-open --run-id=<run-id>` waited about 8 s then returned `panel did not open on the page`. That message is misleading: no page was reachable at all.
- `chat-send --run-id=<run-id>` reported `ok: true` with `target: popup`, but `chat-status` showed `state: closed`. The message was stored where nobody would see it.
- `inspect-page --run-id=<run-id>` **did** reach the live worker (`source=live-session`).

The docs say the CLI "calls that same class directly". In practice, CLI agent verbs run `AgentControl` inside the short-lived CLI JVM, which has no driver. Only MCP `workbench_agent_command` forwards to the worker's `POST /v1/agent/command`. An agent that cannot register MCP has no supported CLI path to page UI on a live session.

### B3 — `start --url --browser` does not start a browser (medium)

`start --url=<url> --browser=chrome` returned in 1.1 s with `live=true` and wrote `.pickleball/consumer-session.json`. No browser opened. The docs say `start` "publishes a live session" that later page commands use, but those commands found no browser (B2).

### B4 — Watchdog and manual worker restarts leave the old Chrome running (high)

Both the automatic watchdog restart and `workbench_worker_restart` started a new worker JVM without quitting the previous WebDriver session. Two orphan Chrome instances (plus their chromedriver processes) kept running. One of them still burned CPU 30+ minutes later because of B1. Anyone watching sees extra stale windows, and the agent loses all page state with no notice.

### B5 — Paused live scenario ends while idle; the browser closes (high)

The MCP worker's anchor scenario (`Keep an interactive Pickleball context available`) resumed and finished on its own. In one case this happened about 2 m 16 s after the last agent call. Teardown closes the browser and removes all page UI and the panel. Later calls fail with `The selected scenario has not created a browser.` or `ui-apply needs a live browser page; run launch first`. Nothing tells the agent that the session ended. The pause or lease length is not documented. A person reading the chat panel would lose the conversation without warning.

### B6 — The chat panel shows no messages (medium)

`chat-send` returned `ok` for every message, and the `chat` op inside `ui` also succeeded. The panel's message list stayed empty in every screenshot, including after closing and reopening the panel and after `ui-poll` or `chat-poll`. The list had a scrollbar, so content may exist but be invisible. The panel is drawn in a shadow root in an isolated world, so the DOM inspector could not reach it to confirm. No message typed by the person arrived during the session, though B1 and B5 may explain that.

### B7 — Placements on hidden targets report success (low)

`arrow` and `magnifier` on a `text:` target returned `ok: true` with placement ids. That element was hidden in the narrow layout, and nothing appeared. The reply gives no `hidden` or `lost` flag, so the agent believes the user can see something they cannot.

### B8 — Placements overlap (low)

An `ask` card targeted at one top-bar link covered most of an `overlay` callout placed on the link next to it. Each placement avoids its own target but not other placements.

### B9 — Frame context changes silently and stays changed (medium)

A Pickleball step (`*, click the "<tab text>" Link`) moved the driver into the application's content iframe, failed, and left the driver there. After that:

- A link in the top document could no longer be found by steps (`No elements found`) or by the inspector (`Link` matches dropped from 22 to 0).
- The page source fell from about 101 KB to 58 KB. That was the only clue that the frame had changed.

Neither the step result nor the inspector reply says which frame is current.

### B10 — Inspector and steps search different frames (medium)

`workbench_element_inspect` with `{"category":"Link","text":"<tab text>"}` found 0 matches from the top document, because it does not descend into iframes. Steps do search iframes. An agent cannot use the inspector to predict what a step will match.

### B11 — Tab clicks report success during a loading mask (medium)

Two `*, click the "<text>" Tab` steps each returned `SUCCESS` (about 2.3 s), while an earlier tab click was still showing the application's loading spinner. Neither click changed the selected tab. The spinner was still there 6+ s later, so the test environment was slow, but the steps should not report success for clicks that had no effect.

### B12 — `navigate to:` rejects `data:` and `file:` URLs (low)

`* navigate to: data:text/html,...` and `* navigate to: file:///<path>/plain.html` both failed with `URL resolved to 'null'`. A local HTTP server was needed for a plain test page.

### B13 — Engine `text=` queries need a runtime that is off by default (low)

`workbench_element_inspect` with `{"query":"text=<words>"}` failed with `engine query needs the composite runtime`. The tool description lists `text=`, `near=`, `below=`, and similar selectors as supported, but they fail unless the optional composite provider is on.

### B14 — Windows PowerShell opener breaks before the first discover (medium)

`.pickleball/open/pickleball-workbench.ps1` passes `-Dmdep.includeScope=test` without quotes. PowerShell splits the argument at the `=`, and Maven fails with `Unknown lifecycle phase ".includeScope=test"`. The script then falls back to `java -jar` on the thin jar, and agent verbs crash with `NoClassDefFoundError: com/fasterxml/jackson/databind/ObjectMapper`. The opener only works once `discover` has written `.pickleball/v/3.0.0/workbench/classpath.cache`. Its fallback classpath also leaves out `target/test-classes` and `target/classes`.

**Fix:** quote the argument (`"-Dmdep.includeScope=test"`).

### B15 — `isolate` with a new run id needs a manual seed (low)

`isolate --run-id=<new-id>` failed with `no workspace for run id <new-id>`. A `workspace-seed --run-id=<new-id> --from-run=<discover-run-id>` was needed first. The guide says `isolate` starts from the last Discover snapshot.

### B16 — Consumer no-op step definitions shadow Pickleball phrases without warning (high)

Consumer projects can carry step definitions with empty bodies that match common phrases, for example:

```
@Then("I click the {string} Link") // empty body
@Then("I click {string} Button") // empty body
```

Pickleball resolves the step to that consumer glue, runs it, and reports `SUCCESS`. Then `I click the "Zzz Does Not Exist" Link` passed in 101 ms. `workbench_step_resolve` showed `CONSUMER_GLUE` and the method, but nothing in the step result or the run report flags that the step did nothing. Any scenario using such a phrase passes without acting. Pickleball's own form is `*, click the "X" Link`. P11 below proposes a guard.

## Inefficiencies

| Item | Cost | Note |
|------|------|------|
| Maven exec per Workbench call | 10–20 s per call | `java -cp @classpath.cache` runs the same verb in about 0.5 s. |
| `isolate` startup | about 50 s | Copies about 5,400 files (229 MB) into the session, then scans the classpath. |
| MCP worker start / restart | 25–37 s / 54 s | Each restart also loses the browser and page state. |
| Element inspect reply size | 8–25 KB per call | Category lookups return the full generated XPath (one category was about 6 KB) plus `outerHTML` per element. There is no compact mode, so token use is high. |
| `workbench_browser_page` | about 100 KB | Returns the full page source on every call. |
| Screenshot | about 128 KB of base64 inline | Fast (59–296 ms) but large. |
| `chat-history` / `chat-poll` without a cursor | Full history | The project-level chat channel (`.pickleball/agent/chat/run.jsonl`) mixes in messages from earlier sessions. |
| Agent page verbs need a live MCP process | Extra client code | The CLI route cannot reach the browser (B2). Consumer projects that do not allow IDE MCP registration leave no supported route. |
| XPath-only element definitions | Deprecation warning on every run | A consumer category defined only by XPath warns on each use and matched 0 elements in this layout. |

## Speed measurements

| Operation | Time |
|-----------|------|
| `java -cp` launcher verb (`help`, `status`, `chat-send`) | 0.5–2 s |
| `discover` (1 scenario, chrome) | about 80 s |
| `execute-step` navigate, first (browser launch) | 10.0–11.5 s |
| `execute-step` navigate, later | 0.4–1.1 s |
| `*, click "..."` Tab (inside an iframe) | 2.3–2.5 s |
| `*, click "..."` Link, not found | 1.9–2.1 s (wait then fail) |
| `workbench_element_inspect` category or CSS (warm) | 13–69 ms |
| `workbench_element_inspect` first call | 336 ms |
| `workbench_agent_command` tabs | 54 ms |
| `panel-open` | 40–276 ms |
| `chat-send` | 23–149 ms |
| `ui` batch, 7 ops | 607 ms (about 87 ms per op) |
| `ui` batch, 3 ops (overlay, spotlight, ask) | 1,233 ms |
| `ui` single label | 98 ms |
| `ui_catalog` | 24 ms |
| `ui_poll` (wait 25 s) | returns at about 25.5 s when idle |
| Screenshot | 59–296 ms |

## What worked well

- `execute-step` streams `ACK` / `STILL WORKING` / `DONE` and reports failures with a clear message.
- The `ui` batch checks every op against the catalog before running any of them and replies compactly (`{"i":1,"c":"label","ok":true,"id":"pkb-2"}`).
- Step badges, label, border, overlay callout (with HTML and a link), spotlight, and the question card all drew correctly and quickly on a complex iframe-based page.
- `workbench_step_resolve` found the no-op consumer glue (B16) at once.
- Usage errors are clear (`workbench_worker_start` → "Call workbench_request_control first"; schema errors name the missing field).

## Agent cost review (from the session log)

Source: the chat log of this session. The agent used about 156 tool actions: 105 commands, 21 file reads, 13 searches, 9 terminal reads, 8 helper files written, and 3 questions to the user. Only about 30 of them tested what the user asked for. The rest went to start-up, workarounds, trimming output, and diagnosis that Pickleball could do in one call. One long scratch-folder path alone appears on 63 of the 555 log lines.

### Where the actions went (approximate)

| Phase | Actions | What the agent had to do by hand | Pickleball change that removes it |
|-------|---------|----------------------------------|-----------------------------------|
| Orientation | ~22 | Export guidance, then grep and page through five docs (`dynamic-control-api.md` in three slices) to learn which verbs exist and how they route. Dig through old runs and the project's URL config to find a target URL and test user. | One recipe in AGENT-GUIDE for "drive a live browser and talk to a person" (P13). URL keys from config (P1). |
| Launcher set-up | ~8 | `mvn` missing; opener script broken (B14); read the opener, build the classpath file by hand, compile tests, write a launcher wrapper. | `export-guidance --warm` (P12). |
| Getting a live browser | ~12 | `start` opened nothing (B3). Write a scratch scenario, `discover`, `workspace-seed`, `isolate`, then navigate. About 3 minutes. | `live-start` (P1). |
| Reaching the browser with page UI | ~16 | CLI verbs saw no browser (B2). Read session descriptors, hunt for the bridge port and token, probe HTTP endpoints, read more docs. | Agent verbs forward to the live worker (P3). |
| Writing an MCP client | ~14 | Write a stdio MCP client, a loopback daemon to keep one `mcp` process alive, and a call wrapper. List schemas tool by tool. Two retries for JSON quoting in PowerShell, one for a missing `agentName`. | `call` verb plus a Workbench-owned controller (P2). `--args-file` / `--args-b64` (P2). Schema and example in errors (P13). |
| Trimming output | ~10 | Inspect replies of 8–25 KB, page source of about 100 KB, 128 KB of base64 screenshot, truncated JSON that broke parsing. Write three helpers to cut them down. | Compact agent view (P4). |
| Feature tests (the real work) | ~30 | Navigate, panel, chat, `ui` batches, inspect, clicks, screenshots. | — |
| Diagnosing failures | ~35 | Find worker logs, parse events, grep for the lease, `jcmd` thread dumps, write a CPU sampler, list orphan Chrome processes, write a keep-alive watcher, try `data:` and `file:` pages, start an HTTP server, unzip the jar, and read `pkb-runtime.js`. | `doctor` (P5), lease and restart control (P6), `selftest ui` (P8). |
| Frame and glue confusion | ~12 | Infer the current frame from page-source length; check iframes; find the no-op consumer step with `step_resolve` and grep. | Frame in every reply (P10). No-op step warning (P11). |
| Clean-up | ~6 | Stop worker, daemon, server; find and kill orphan processes; delete temp files. | `live-stop --all` (P14). |

### Helper code the agent had to write

| Helper | Why it was needed | Built-in that would replace it |
|--------|-------------------|--------------------------------|
| Launcher wrapper (`java -cp @classpath.cache ...`) | Opener broken; Maven exec costs 10–20 s per verb. | `export-guidance --warm` and a short `pkb` / `pkb.cmd` shim at the project root (P12). |
| Stdio MCP client, loopback daemon, call wrapper | Only MCP reaches the live browser. IDE MCP registration was not allowed, and each `mcp` process dies with its caller. | `call` verb plus a persistent controller (P2). |
| Inspect summarizer | Inspect replies too large to read. | Compact agent view (P4). |
| Screenshot-to-file | Screenshot comes back as inline base64. | `screenshot --out=<file>` (P4). |
| Step summarizer | `execute-step` replies are long and get cut mid-JSON. | Compact step reply (P4). |
| Keep-alive chat watcher | No built-in way to stay alive and collect human chat. | `chat-wait` / `chat-watch` and `--hold` (P6, P7). |
| CPU sampler, thread dumps, process listing | No health report. | `doctor` (P5). |
| Plain HTML page plus `python -m http.server` | `navigate to:` rejects `data:` and `file:` URLs. | `selftest` fixture pages (P8). |

## Proposed reusable calls

These are proposals. None of these verbs or flags exist in 3.0.0 unless noted.

### P1. `live-start` — one call to a ready browser

```
live-start --url=<url> | --url-key=<APP>.<ENV> --browser=chrome --headed --env=<env> --hold=30m [--panel] [--size=1600x1000] [--json]
```

It starts the controller and worker, opens the browser, navigates, and (with `--panel`) opens the chat panel. It returns `{"session","runId","worker":{"pid"},"browser":{"pid","size"},"url","ready":true}`. It replaces the scratch scenario, `discover`, `workspace-seed`, `isolate`, `request_control`, `worker_start`, and the first navigate. `--url-key` reads the project's URL config, so the agent does not need to open config files. The existing `start` verb could take on this job.

### P2. `call` — every MCP tool from the CLI, on one live controller

```
call <tool> --args-file=args.json | --args-b64=<base64> | --args=- (stdin)
```

Workbench owns one loopback controller per project and records it in `.pickleball/live/controller.json` (port and token). `call` sends one tool request to it and prints the reply. Every MCP tool works from a shell, with no IDE registration and no client code. Base64 and file input avoid shell quoting failures.

### P3. Agent verbs reach the live worker on their own

When `.pickleball/live` names a session, `panel-open`, `label`, `ui-apply`, `chat-send`, and the other agent verbs forward to that worker's bridge. No `--run-id` is needed. With no live browser they fail at once with `error: no live browser` and `fix: live-start ...`, instead of waiting 8 s or falling back to a closed popup.

### P4. Compact agent view by default

- `inspect`: one line per element — `#n tag "text" id= name= xy= wh= shown= frame= handle=`. `--explain` adds the XPath.
- `page`: url, title, frame path, and element counts. `--source` opts in to the full HTML.
- `screenshot --out=<file>`: writes the PNG and returns the path and size.
- `execute-step`: `{status, ms, message, kind, glue, matched:{tag, text, frame}}`.
- `--fields=` and `--max-chars=` on every read, so replies are never cut mid-JSON.

### P5. `doctor` — one health report

```
doctor [--run-id=<id>] [--json]
```

It reports the controller and worker pids, bridge latency, scenario-thread state (with the top stack frame when busy), pause lease time left, browser pids and CPU, orphan browsers, last restart and its reason, current frame, and placed page UI. It would have replaced about 30 diagnosis actions in this session, including thread dumps, CPU sampling, and process listing.

### P6. Lease and restart control

- `--hold=<duration>|forever` on `live-start` and `worker_start`.
- A `lease` field in `status` and `worker_status`.
- A `browser_lost` event in `ui_poll`, `chat-poll`, and `events`.
- Restart closes the old browser (B4). A `gc-browsers` verb kills orphans from earlier runs.

### P7. Chat helpers

```
chat-wait --from=human --timeout=120   # returns only new human messages
chat-watch --out=.pickleball/live/inbox.jsonl --keepalive   # background
```

The cursor starts at session start, so old history is not returned. Each session gets its own channel instead of the shared project-wide `run.jsonl`. These replace hand-written watchers and poll loops.

### P8. Built-in self-test

```
selftest ui [--component=panel|label|overlay|...] [--cpu] [--json]
```

It serves a bundled page on loopback, places each component, and reports render, visibility, and Chrome CPU for each. It would have found B1 in one call instead of about 15. `navigate to:` should also accept `fixture:<name>` (and possibly `file:`), so agents need no HTTP server.

### P9. UI verify

Each placement reply includes `visible`, `rect`, and `overlaps:[ids]`. `ui-verify` returns that for every placement, plus an optional screenshot path. B7 and B8 would show up without a screenshot.

### P10. Frame tools

Every step and inspect reply includes the current frame path. `inspect --frames=all` searches iframes the way steps do. A `frame --list | --reset | --enter=<name>` verb ends the guessing from B9.

### P11. Step resolution guards

`execute-step` replies include `kind` (`DYNAMIC`, `CONSUMER_GLUE`, `OVERRIDE`) and the glue method. A consumer step with an empty body is flagged `noop:true`, and the run report warns about it. `validate --noop` lists every empty step definition in the consumer project (B16).

### P12. Faster first call

`export-guidance --warm` compiles tests and writes `classpath.cache`, so the first verb runs in about 0.5 s. Fix the opener quoting (B14). Put a short `pkb` / `pkb.cmd` shim at the project root, so commands do not repeat long paths. AGENT-GUIDE should print the exact `java -cp` line.

### P13. Guidance and help

- One AGENT-GUIDE recipe, "work with a person in the browser", in five commands.
- `help <mcp-tool>` prints the schema and one example argument set. For example, `workbench_request_control` needs `agentName`, which only appeared after a failed call.
- State plainly which route reaches a live browser (CLI, bridge, or MCP), and what to do when a consumer project does not allow IDE MCP registration.
- Keep the exported guide and the consumer bridge text (`AGENTS.md`-style files) consistent on whether CLI `isolate` is allowed. In this session they disagreed.

### P14. `live-stop --all`

It stops the controller, worker, browsers, popup, and watchers, removes the live files, and lists anything it could not stop.

## Estimated effect

With P1, P2, P4, P5, P7, and P8, the same session would need about 40 actions instead of about 156:

- 3 for set-up (`export-guidance --warm`, `live-start --panel`, `chat-send`)
- about 30 for the tests
- about 5 for diagnosis (`doctor`, `selftest ui --cpu`)
- 1 for clean-up

That is about a quarter of the actions. The saving in reply text is larger still, because inspect, page, and screenshot replies would shrink from tens of kilobytes to a few lines. This is an estimate based on the log, not a measured result.

## Recommendations

1. Fix B1 (toggle and popover loop) before relying on the in-page panel. Until then, use the popup chat window or the CLI chat, not the in-page panel.
2. Make CLI agent verbs forward to the live session's worker when `--run-id` names one (B2), or document that only MCP reaches a live browser.
3. On worker restart, quit the old WebDriver session or kill the browser process tree (B4). Report the lost browser to the agent.
4. Document the live-session pause lease, or keep the anchor paused while an MCP client is attached (B5).
5. Report the current frame in step and inspect replies, and let the inspector search iframes (B9, B10).
6. Add a compact mode to `workbench_element_inspect` and `workbench_browser_page`.
7. Quote `-Dmdep.includeScope=test` in `pickleball-workbench.ps1` (B14).
8. Flag no-op consumer step definitions in step results and run reports (B16, P11).
9. To cut agent cost, build P1 (`live-start`), P2 (`call`), P4 (compact view), and P5 (`doctor`) first. Together they remove most of the set-up, workaround, and diagnosis actions listed in the cost review.
