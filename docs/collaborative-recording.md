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

The page runtime (`pkb-runtime.js`, version `3.0.0-editor`) pushes these through `pkbSurface`. Click, input (debounced), select, check, and Enter/Tab/Escape are always sensed. Hover, focus, and drag are sensed while record mode is on. Agent `act` and `form-fill` are recorded too, including `used:desktop`.

`events --follow --type=interaction` tails the same file and exits on the first match. It does not need a Workbench session.

## Verbs

```
record --start [--session=<id>]
record --stop
record --status
record --pause
record --resume
record --script
record --editor
record --edit --index=0 --text=click the "Save" Button
record --move --from=2 --to=0
record --nest --index=1
record --outdent --index=1
record --add --index=1 --text=press "Escape"
record --delete --index=2
record --popout --panel=editor
record --popin --panel=editor
record --popout --panel=chat
record --popin --panel=chat
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

## Step editor

`record --start` opens a floating step editor (`data-pkb-editor`, `window.__pkbUi.editor`). It lists each generated step with a short id (`s1`, or `m1` for a step you added). The panel matches the chat panel: drag the header to move it, drag an edge or corner to resize, and collapse it to a Steps pill. Light, dark, and reduced motion follow `panel.css`.

The list stays live. A new human or agent interaction appends. A desktop `act` (`surface=desktop`) is the same kind of row.

Editing writes the routine back. Indexes are 0-based.

| Action | Command | Page |
|---|---|---|
| Rewrite | `record --edit --index=<n> --text=<phrase>` | Click the text, type, Enter or blur |
| Reorder | `record --move --from=<n> --to=<n>` | Drag the handle. Alt+Up / Alt+Down |
| Nest | `record --nest --index=<n>` | Nest button, Tab, or drop on the right half of a row |
| Outdent | `record --outdent --index=<n>` | Outdent button or Shift+Tab |
| Add | `record --add [--index=<n>] [--text=<phrase>]` | Add step, or Ctrl+Enter / Cmd+Enter |
| Delete | `record --delete --index=<n>` | Delete button, or Ctrl+Backspace / Cmd+Backspace |

A phrase without a leading comma gets one, so it stays a dynamic step. Nesting is a leading colon, the same shape as [nested steps](nested-steps.md):

```gherkin
Then , click the "Open" Button:
: And , click the "Save" Button
```

A deleted generated step does not come back. An edited phrase stays even if the live value would have changed. `record --save` stores `steps` and `depths`. `record --load` puts that list back in the editor. `record --export` and `record --run` keep the colons. The next `record --start` clears the draft so the new session starts empty.

The page pushes the same actions as `type:record` events (`edit`, `move`, `nest`, `outdent`, `add`, `delete`). The next `record` command absorbs them into `.pickleball/recordings/state.json`. Buttons use `sending`, then `delivered` when the push channel takes the event.

## Pop out and pop in

`record --popout --panel=editor` and `record --popout --panel=chat` mark that panel as its own browser window. `record --popin --panel=editor|chat` puts it back on the page. The editor keeps the position and size it had. The chat window keeps the message list and the `sending` / `delivered` / `read` marks.

The page opens a named window (`pkb-step-editor` or `pkb-chat-panel`) and syncs it with `postMessage`. Pop in is on that window and on the pill left on the page. If the original page is refreshed or navigated, the runtime looks for the named window and reattaches. If the window is already gone, it closes the blank leftover and shows the panel on the page again.

MCP: `workbench_record_editor`, `workbench_record_edit` (`action` is `edit`, `move`, `nest`, `outdent`, `add`, or `delete`), and `workbench_record_pop` (`action` is `popout` or `popin`, `panel` is `editor` or `chat`).

## MCP

`workbench_record_start`, `workbench_record_stop`, `workbench_record_status`, `workbench_record_script`, `workbench_record_save`, `workbench_record_load`, `workbench_record_list`, `workbench_record_export`, `workbench_record_inject`, `workbench_record_refine`, `workbench_record_editor`, `workbench_record_edit`, and `workbench_record_pop` call the same `record` verb. `workbench_agent_command` with `op: record` does too.

## Desktop

A desktop `act` while recording stores `source:agent`, `surface:desktop`, and `window`. The generated phrase is the same dynamic step (`click the "Save" Button`). The window stays on the interaction row. There is no OS-wide hook; the agent records the desktop verb it just ran, and a fixture or live tree is the target.
