# Helper bot brief — RETEST 2026-11-24 — you are the human collaborator

You are a helper bot with no other context. Read this file and follow it. You talk to the consumer-project agent **only** through pickleball's collaboration UI on the shared page (question cards, the panel's Chat / Guide / Recorder tabs and composer, highlights, the toolbar dock, the "Ask agent to attach a file" offer). No other channel.

- Your notes: `/workspace/pickleball/collab-test/retest-2026-11-24/human-notes.md` (you create and append; nobody else edits it).
- Plan (awareness only): `/workspace/pickleball/collab-test/retest-2026-11-24/plan.md`. If this brief and the plan disagree, this brief wins for what you may do.
- Last run, for comparison: `/workspace/pickleball/collab-test/findings-2026-11-23.md` (bugs B1–B9).

## Who you are

A person at a headed Chrome window. You may only do what a person could do with mouse and keyboard on what is visible on the page. Human mode only: your session is locked to human-tier verbs, and a control verb exits 2 with `error: <verb> is a control-tier verb`. That error is expected; log it if you hit it, do not work around it, and never pass `--human-override`.

## Start and attach

The page and host are **already running** (started by the agent side). Do not run `start`, `live-start`, `live-stop`, `controller-start`, `controller-stop`, or open a second browser.

Use only this wrapper (it adds `--run-id=human` and cds into the consumer project):

```bash
export JAVA_HOME=/home/box/jdk/jdk-21.0.13+9
export PATH="$JAVA_HOME/bin:$PATH"
export DISPLAY=:34
H=/workspace/pickleball/collab-test/retest-2026-11-24/human.sh
$H doctor                       # expect chrome=present health=ok headed=true page=attached session=livecc4dc4acb1b1
$H session-mode --human=on      # lock YOUR run id to human tier (do this once, first)
$H session-mode                 # prints the mode; expect human on
$H snap --target=browser:auto
```

If `doctor` is not `health=ok`, re-run it every 15 s for up to 10 minutes; if still not healthy, note it and stop.

Page: `http://127.0.0.1:8766/retest.html` — Email, Go, Upload File (a file input; `Choose File` button), a `files` line, a `status` line, plus the pickleball panel (tabs Chat / Guide / Recorder, Message box, Send, Pop out chat, Minimize, Close) and the toolbar dock (`Tools`, `Open agent panel`). Discover the real controls with `snap`, not from memory.

When attached, type `ready` in the panel Message box and click Send. That tells the agent to begin.

## Allowed verbs (human tier)

```bash
$H snap --target=browser:auto [--include=values,highlights,cards,guide,placements,chat] [--since=<snapCursor>]
$H find --target=browser:auto --query=Alpha            # or --purpose=send / query with purpose=
$H act  --target=browser:auto --kind=click --query=Alpha
$H act  --target=browser:auto --kind=type --query=Email --value=abc@example.com
$H act  --target=browser:auto --kind=type --purpose=message --query=Message --value=please-fill-email
$H act  --target=browser:auto --kind=click --purpose=send --query=Send
$H act  --target=browser:auto --kind=press --query=Email --value=Tab
$H wait --target=browser:auto --condition=new-control --timeout=15000
$H wait --target=browser:auto --until-change --selector=#status --timeout=15000
$H chat-read --last=5            # read-only; do not pass --mark-read unless you note why
$H capture --target=browser:auto --out=/workspace/pickleball/collab-test/retest-2026-11-24/human-capture.png
$H doctor | explain | help <verb>
```

`act` kinds: click, type, press, hover, select. Keep `--value` free of spaces.

## Not allowed

- Any control-tier verb: `ask-and-wait`, `answer-wait`, `ui-apply`, `ui-poll`, `chat-send`, `chat-turn`, `guide*`, `highlight`, `unhighlight`, `pin`, `toast`, `checklist --check`, `panel-open`, `panel-close`, `panel-state`, `form-fill`, `eval`, `upload`, `act --kind=attach`, `watch`, `browser-events`, `navigate`, `record --inject`, `execute-step`.
- `--human-override`, `--exposure=full`/`lower`/`full-access`, dumping HTML.
- OS file pickers or setting the file input yourself. For the file scenario, click what you can and use the page's "Ask agent to attach a file".
- Messaging the agent or the parent bot out of band. The page is the only channel. Your notes file is a record, not a channel.
- Session control: `session-mode --human=off`, a second `start`, a second Chrome.

## How to notice work

1. Prefer push waits over polling: `wait --condition=new-control` (new card/button) or `wait --until-change --selector=...`; record how long after the agent's change it woke (approx).
2. Otherwise `snap` every 250–1000 ms while waiting for a card or chat line. Use `snap --since=<last snapCursor>` to get only changes, and record whether it returned only the delta.
3. Read chat with `snap --include=chat` (message rows) and `chat-read --last=5`. Note author, status (delivered/read), duplicates, and whether `cursors.chat` advanced in the envelope.
4. Success comes from what the page shows (card says `Answer sent`/goes away, field value, `files` line, Guide pane), not from `applied`. Log `applied` (true/false/unknown) every time; flag `false` when the page did change.

## Scenarios (human steps only)

The agent drives the order and may pause between scenarios. Keep watching until the agent posts `collab-test-done` in chat or the session stops. Give up after 20 minutes with no new control or message.

1. **Choose.** When `Alpha` and `Beta` appear, click `Alpha`. While the agent is waiting, run one `doctor` and one `snap` and log their times (B1).
2. **Chat hand-off.** When the agent's question appears in chat, type `please-fill-email` in Message (`--purpose=message`) and click Send (`--purpose=send`). Watch Email get filled by the agent; read its value from `snap --include=values`. Note duplicate bubbles or unread counts (B6).
3. **Highlight.** When asked to type in the outlined field, `find` it (look for `highlight=` on the Email row), type `abc@example.com`, then `act --kind=press --value=Tab`. Log `applied` for the press (B7).
4. **File hand-off.** Click Upload File / Choose File once (a person's click). Then click "Ask agent to attach a file" if offered. Wait for the `files` line to read `Files: customers.yaml`. If no offer appears, post `cannot-attach-please-upload-customers-yaml` in chat.
5. **Timeout.** For the first short card (`Timeout-card` / `Later` / `Now`), do **not** click; watch it disappear (try `wait --until-change` or `--condition=new-control`). When `Retry-pick` with `Alpha`/`Beta` appears, click `Alpha`.
6. **Guide.** When the Guide tab is selected or badged, check the Guide pane and the Email row's `guide=` in snap. Type `abc` in Email and press Tab (or Enter). Note whether the guide advanced or finished.

Also, anytime between scenarios (keep each short):
- **Tabs and badge (B5):** with unread messages, `find --purpose=` for the Chat tab still works; click the Guide tab then the Chat tab and confirm the pane switches. Log `candidates`/`picked` when you use `--pick`.
- **Toolbar dock:** click `Tools`, then `Minimize`, then reopen via the dock / `Open agent panel`. Note if position/collapse look persisted. If you type a draft in Message and click `Close`, note whether it asks before discarding (then cancel/keep). Do not leave the panel closed — reopen it.
- **data-pkb-purpose:** note which rows show `purpose=` and which do not.

If the session dies mid-run, run `doctor`, write what you have, and stop.

## Notes format

Append only to `human-notes.md`. Times in America/Phoenix (MST, UTC-7), e.g. `9:02:31.430 AM MST`.

```markdown
# Human notes — retest 2026-11-24

## Session
- session id:
- doctor line:
- session-mode after lock:

## Action log
| t (MST) | scenario | verb | args | exit | envelope ms / workerMs | gap_ms | result (applied, what the page showed) |
|---|---|---|---|---|---|---|---|

## Rechecks (B1–B9, toolbar, purpose, snap --since, wait --until-change, chat-read)
- one line each: fixed / still broken / partial, with evidence
## Bugs and issues
## Sluggish or unresponsive (verb + workerMs)
## Attributes and tagging ideas
## Feature ideas
## Docs / instruction improvements
## Extra API / MCP calls you wished you had
## Verdict per scenario (your side)
- 1–6: pass / pass-with-gap / fail, one line each
```

Record every action and query with exit code and envelope `ms` / `timing.workerMs` / `timing.totalMs`, gaps between commands, anything slow, controls you expected but did not see, and whether you were tempted to break a rule (and did not).

## When you finish

Report back **by message to the person who briefed you** (not on the page): a short summary plus the path to `human-notes.md`:

- Session id and whether doctor stayed healthy; session-mode result.
- Per-scenario verdict 1–6 from your side.
- B1–B9 recheck results (one line each) and toolbar / purpose / snap --since / wait push results.
- Top three bugs or gaps; top three API/docs ideas.
- Anything slow (verb + workerMs).
- Confirmation you used only human-tier verbs and no out-of-band coordination.

Do not tear down the browser or the http server. The agent runs `live-stop --all` at the end.
