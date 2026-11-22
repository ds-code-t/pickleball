# Collaborative recording

Record a person and an agent working on the same page, or on a desktop window, and turn that interaction into parameterized dynamic steps.

## Record shape

Each gesture is one JSON object on `.pickleball/agent-events.jsonl` and on `.pickleball/recordings/stream.jsonl`.

| Field | Meaning |
|---|---|
| `event` | Always `interaction`, so `events --follow --type=interaction` matches it. |
| `type`, `kind` | `click`, `input`, `change`, `select`, `key`, `hover`, `drag`, `focus`, or `check`. |
| `source` | `human`, `agent`, or `system`. |
| `id`, `ref` | DOM id, placement id, or short ref when the agent used one. |
| `role`, `name`, `text`, `target` | Accessible role and label. |
| `value`, `oldValue` | Typed or selected value. A password or `sensitive` row stores `filled`, not the secret. |
| `frame` | `top`, a frame id, or `desktop`. |
| `bounds` | `x,y,width,height` when the page reported a box. |
| `window` | Desktop window title when the gesture was not in the browser. |
| `sessionId`, `at`, `eid` | Session, timestamp, and the page event id. |

The page runtime (`pkb-runtime.js`, version `3.0.0-record`) pushes these through `pkbSurface`. Click, input (debounced), select, check, and Enter/Tab/Escape are always sensed. Hover, focus, and drag are sensed while record mode is on. Agent `act` and `form-fill` are recorded too, including `used:desktop`.

`events --follow --type=interaction` tails the same file and exits on the first match. It does not need a Workbench session.

## Verbs

```
record --start [--session=<id>]
record --stop
record --status
record --pause
record --resume
record --script
record --inject --kind=click --query=Approve --role=button
record --rename --from=accountNumber --to=acct
record --literal=note
record --context --text=in the "Billing" Section
record --reject --index=2
record --accept --index=2
record --save --name=checkout
record --load --name=checkout
record --list
record --export --name=checkout --format=feature --out=checkout.feature
record --run --name=checkout --param=acct=42 --param=accountType=Basic
```

`--script` prints dynamic steps. A typed account number in the "Account Number" textbox becomes:

```gherkin
Then , enter "<accountNumber>" in the "Account Number" Textbox
```

A multi-select `Premium|Plus` becomes two `select` phrases. An empty value stays a parameter with an empty example. A value that contains a quote stays in the parameter example; the step text keeps `<name>`. `--literal` puts the example in the step instead of a placeholder. `--rename` changes the parameter name. `--context` leads the first phrase.

Repeated input on the same control keeps the last value. Focus is not a step. A drag needs a drop target (`dragAndDrop the "From" Button the "To" Button`).

## Save, export, replay

Routines are JSON files under `.pickleball/recordings/routines/<name>.json`. They survive a new process. `--export --format=feature` writes a Scenario Outline whose steps are dynamic steps and whose Examples row is the recorded values. `--run --param=name=value` substitutes those placeholders, writes `.pickleball/recordings/replay/<name>.feature`, and writes `execute-step` rows in `<name>.steps.json`. Replay uses the same dynamic-step path as any other feature. `<name>` is an Examples or mapping placeholder, not a new syntax.

The in-page bar (`window.__pkbUi.record`) shows the script while recording. Pause, Resume, Accept, and Reject use the same `sending` then `delivered` feedback as the panel. Those buttons push `type:record` onto the event stream.

## MCP

`workbench_record_start`, `workbench_record_stop`, `workbench_record_status`, `workbench_record_script`, `workbench_record_save`, `workbench_record_load`, `workbench_record_list`, `workbench_record_export`, `workbench_record_inject`, and `workbench_record_refine` call the same `record` verb. `workbench_agent_command` with `op: record` does too.

## Desktop

A desktop `act` while recording stores `source:agent`, `surface:desktop`, and `window`. The generated phrase is the same dynamic step (`click the "Save" Button`). The window stays on the interaction row. There is no OS-wide hook; the agent records the desktop verb it just ran, and a fixture or live tree is the target.
