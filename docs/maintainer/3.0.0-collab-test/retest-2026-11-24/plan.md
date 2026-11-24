# Two-bot collaboration RETEST plan — 2026-11-24

Base: `origin/3.0.0` at `5f86daf4` (merge of PR #41; groups C, D, E in). Worktree: `/workspace/pickleball/wt-collab2` (detached). Consumer project for the live session: `/workspace/pickleball/wt-collab2/maven-consumer-project`. Previous run and findings: `/workspace/pickleball/collab-test/` (`findings-2026-11-23.md`, bugs B1–B9).

A helper bot plays the **human** (human tier only, run id `human`, locked with `session-mode --human=on`). The main bot plays the **consumer project's AI agent** (control tier, run id `agent`). One live headed Chrome page, one `start` host. The page's pickleball collaboration UI (cards, panel chat, Guide pane, highlights, toolbar dock, upload hand-off) is the only channel between the bots.

## Setup (already running)

- Jar: `wt-collab2/build/libs/pickleball-3.0.0.jar` (shadowJar of 5f86daf4, 12,624,965 bytes) first on `classpath.txt`, then the Gradle runtime classpath. `~/.m2` is not touched.
- Wrappers in this folder: `pkb.sh` (cds into the consumer project), `agent.sh` (= pkb.sh … `--run-id=agent`), `human.sh` (= pkb.sh … `--run-id=human`).
- Page: `http://127.0.0.1:8766/retest.html` served by `python3 -m http.server 8766 --bind 127.0.0.1 --directory site/` (Email, Go, Upload File, a `files` line, a `status` line). Upload sample: `customers.yaml`.
- Host started detached by the agent side:
  ```bash
  export DISPLAY=:34
  nohup ./pkb.sh start --url=http://127.0.0.1:8766/retest.html --browser=chrome --headed=true --panel --hold=forever --size=1200x800 --run-id=agent > logs/start.log 2>&1 &
  ./pkb.sh doctor   # chrome=present health=ok headed=true page=attached lease=held waits=0
  ```
- Session at 8:37 AM MST: `livecc4dc4acb1b1`. Logs: `logs/start.log`, `logs/http.log`.

## Start order (from docs/collaborative-self-test.md "Two-actor recipe")

1. Agent `start` alone, `doctor` until `health=ok` (done).
2. Helper attaches: `human.sh session-mode --human=on`, then `human.sh snap --target=browser:auto`.
3. Agent posts `chat-send --text=collab-retest-start` and waits for the helper's `ready` in chat (`chat-turn --timeout=…`).
4. Each side writes only its own notes file (`agent-notes.md`, `human-notes.md`). Neither waits on the other's file.

## Core scenarios (same six as 2026-11-23)

| # | Scenario | Agent | Helper | Pass when |
|---|---|---|---|---|
| 1 | Choose card | `ask-and-wait --say=Pick-one --choices=Alpha,Beta --async`, then `answer-wait --id=<id> --timeout=10s` (retry) | snap/`wait --condition=new-control`, click `Alpha` | answer is `Alpha`; helper doctor/snap stay fast during the wait (B1) |
| 2 | Chat hand-off | `panel-open --tab=chat`, `chat-turn --text=What-should-I-do? --timeout=60s` | sees the agent line (snap `--include=chat` / `chat-read`), types `please-fill-email` (`--purpose=message`), Send (`--purpose=send`) | chat-turn returns helper text; agent `form-fill` Email; helper reads value in snap; no duplicate bubbles (B4, B6) |
| 3 | Highlight | `highlight --query=Email`, `chat-send` "type-in-the-outlined-field" | `find` shows `highlight=` on Email; type `abc@example.com`; press Tab | agent reads value; Tab press `applied` true/unknown, not false (B3, B7) |
| 4 | File hand-off | on `upload-request` (ui-poll / browser-events), `upload --query=Upload-File --file=…/customers.yaml` | click Upload File, then "Ask agent to attach a file" | `files` line shows `customers.yaml` (B2) |
| 5 | Timeout/retry | short card `--id=t1` (`Timeout-card`, Later/Now) with ~8s timeout; `answer-wait` exits 1 `timeout`; re-ask same id as `Retry-pick` Alpha/Beta | do not click the first card (watch it via snap/`wait --until-change`); click `Alpha` on the retry | second wait returns `Alpha` |
| 6 | Guide | `guide --steps=@ops/guide-steps.json` (Email step) | Guide tab selects/badges, Email row shows `guide=`; type `abc`, press Tab/Enter | `step` event `by=human` arrives; Guide pane shows the steps (B8) |

## Explicit rechecks

| Item | What to check | Who |
|---|---|---|
| B1 wait lane | During every agent wait, helper `doctor` stays `health=ok`; snap/find < 1 s; doctor `waits=` counts the wait | both |
| B2 upload | `upload` / `act --kind=attach` on the start host; human "Ask agent to attach a file" posts `upload-request` | both |
| B3 snap state | `snap --include=values,highlights,cards,guide,placements,chat` shows value, highlight, card title/state, guide, chat `message` rows | helper |
| B4 chat-turn | agent line delivered first (`sentId`, `status`), returns only a newer human reply | agent |
| B5 tabs | Chat tab still findable with unread badge (`--purpose=` / `data-pkb-badge`); tab click switches pane; `--pick` reports `candidates`/`picked` | helper |
| B6 duplicates | one bubble per message; unread does not count replays; after `navigate` history not duplicated | both |
| B7 applied | `act` returns true/false/unknown honestly; card click = true | helper |
| B8 guide | Guide pane fills, tab badge/select, `data-pkb-guide-step`, survives reload | both |
| B9 cursors.chat | `cursors.chat` advances in envelopes | both |
| Unified toolbar dock | one collapsible dock (panel, step editor, recorder); `.pkb-pill` reopen; collapse/position persist per origin; close asks before discarding unsent text; `panel-state`; `panel-open --collapsed=…` | agent reads, helper clicks Tools/Minimize/Close/reopen |
| ask-and-wait --async | returns id at once; `answer-wait --id` | agent |
| Upload hand-off | see scenario 4 | both |
| Guide pane | see scenario 6 | both |
| chat-turn / chat-read | helper `chat-read --last=5` read-only (nothing marked read unless `--mark-read=true`); lists `recent` cards | both |
| data-pkb-purpose | snap/find rows carry `purpose=`; `find --purpose=send`, `act --purpose=message` | helper |
| snap --since | helper passes the previous `snapCursor`; only changes return | helper |
| watch / wait --until-change | agent `watch --selector=#status --until=change`; helper `wait --until-change --selector=…` and `wait --condition=new-control` wake on push, not poll (record latency) | both |
| browser-events | agent `browser-events --start`, then `--since=<seq> --kinds=nav,console,net,dom,input`; confirm rows for helper actions, redaction, `via=` | agent |

## Timings and pass criteria

Each side logs wall-clock time per verb plus envelope `ms`, `timing.totalMs`, `timing.workerMs`. Warm snap/find tens of ms; click < 1 s. Any verb > 2 s while the other side waits is a lane failure. Pass criteria come from answer, chat, step and upload events, not from `applied`. The run passes when all six scenarios pass and no verb reports `unreachable`.

## End

Agent posts `collab-test-done` in chat, then `live-stop --all` and stops the http server. Merged findings go to `findings-2026-11-24.md` here.

## Out of scope

Merging, pushing, tags, master/2.x, full Gradle suites, Workbench GUI.
