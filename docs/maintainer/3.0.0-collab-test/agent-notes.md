# Agent notes — 2026-11-23

Role: consumer-project AI agent. Times are America/Phoenix (MST, UTC-7).
Session: `livedce7825a2acd`. Worktree `/workspace/pickleball/wt-collab` at `d265a018`. No pushes; repo not edited.
Human appeared: **yes**. First `doctor` raced the start (11:22:04 PM MST, health=absent). A second `doctor` at 11:22:17 PM MST was `health=ok`. They then went quiet in notes (never updated `human-notes.md` after that first failed row) but did act on the page from 11:35:05 PM MST through 11:42:17 PM MST.

## Session
- session id: livedce7825a2acd
- start command: `pkb.sh controller-start`; `pkb.sh start --url=fixture:form --browser=chrome --headed=true --panel --hold=forever --size=1200x800`
- headed: true (visible Chrome on DISPLAY=:34)
- chrome / doctor (11:22:05.200 PM MST): chrome=present session=livedce7825a2acd health=ok headed=true pid=842464 bridge=present worker=absent lease=held page=attached leaseLeft=forever frame=2EA63BCB7E4290157A9FD728905650B4 placed=0
- controller: running=true pid=842405 port=42307
- teardown: live-stop --all at 11:43:58.070 PM MST, stopped=true, controller=stopped, no leftover Chrome

## Action log
| t (MST) | t_ms | scenario | side | verb | args | exit | envelope ms / workerMs | gap_ms (from previous) | result (short) |
|---|---|---|---|---|---|---|---|---|---|
| 11:22:01.823 PM MST | | boot | agent | controller-start | | 0 | wall 909 | | running=true pid=842405 port=42307 |
| 11:22:02.734 PM MST | | boot | agent | start | --url=fixture:form --browser=chrome --headed=true --panel --hold=forever --size=1200x800 | 0 | envelope ms=0, wall 2463 | 0 after controller | sentence=live browser ready, panel open; headed=true; lease=held |
| 11:22:05.200 PM MST | | boot | agent | doctor | | 0 | wall 170 | ~3s | chrome=present health=ok headed=true page=attached |
| 11:22:04 PM MST | | boot | human | doctor | | 0 | wall 136 | | health=absent (raced start). Logged only this row in human-notes.md for the whole run. |
| 11:22:17.329 PM MST | | boot | human | doctor | | 0 | | ~13s | consumer-cli.log: health=ok on livedce7825a2acd. Human attached. Agent then waited ~10 min for notes/snap text (deadlock: human was waiting for controls). |
| 11:32:13.818 PM MST | | boot | agent | doctor | | 0 | | | still health=ok; placed=0. Human doctor also ok at 11:32:13.953. |
| 11:32:23.826 PM MST | 1795501943825 | 1 | agent | ask-and-wait | --say=Pick-one --choices=Alpha,Beta --timeout=120000 | 1 | 120083 / 120075 | | error=timeout; no value; card q1 removed. During this wait, human doctor saw health=down at 11:33:02 and 11:34:06; health=ok again at 11:34:27 and 11:34:32 after the wait ended. |
| 11:34:24.077 PM MST | | 1 | agent | (return) | | 1 | wall 120251 | | official ask-and-wait failed. |
| 11:35:00.335 PM MST | | 1 | agent | doctor | | 0 | | | health=ok placed=0 |
| 11:35:00.500 PM MST | | 1 | agent | ui-apply | ops/ask.json Pick-one Alpha,Beta | 0 | 26 / (total 26) | 136 wall | ok, id=q2 |
| 11:35:00.639 PM MST | | 1 | agent | snap | --target=browser:auto | 0 | 14 / 11 | 112 wall | Alpha s1.h12, Beta s1.h13 visible (open shadow / pkb-layer) |
| 11:35:05.170 PM MST | | 1 | human | (click Alpha) | q2 | | | **~4.7s after card** | events: choice=Alpha, id=q2, source=page |
| 11:35:12.126 PM MST | | 1 | agent | ui-poll | --wait=25 | 0 | 26 / 26 | | items answer q2 choice=Alpha (already present) |
| 11:35:15.581 PM MST | | 2 | human | chat Send | please-fill-email | | | **~10s after Alpha / ~15s after card** | page chat, id=h1-mwubln12 |
| 11:35:33.100 PM MST | | 2 | agent | form-fill | --fields=email=abc@example.com | 0 | 30 / 26 | 179 wall | applied=true, value read back |
| 11:35:33.255 PM MST | | 2 | (event) | click Email | | | | same instant as form-fill | recorded source=human id=email value="" — likely form-fill focus mis-tagged as human |
| 11:35:33.282 PM MST | | 2 | agent | chat-turn | --text=email-filled | 0 | 39 / 35 | 134 wall | until defaulted to human; returned existing please-fill-email; sentence=human replied. Did not clearly send email-filled. |
| 11:35:33.420 PM MST | | 2 | agent | snap --format=json --fields=name,id,role | | 0 | 16 / 12 | | Email s1.h1 present; compact snap has no field value |
| 11:35:33.546 PM MST | | 2 | agent | find | Email | 0 | 17 / 14 | | s1.h1, no value field |
| 11:35:45.739 PM MST | | 2 | agent | chat-send | --text=email-filled --source=agent | 0 | 11 / 6 | 113 wall | message id=3 stored |
| 11:35:45.854 PM MST | | 3 | agent | highlight | --handle=s1.h1 --preset=outline-red | 0 | 20 / 5 | 119 wall | ok; handle resolved to email; sentence=agent style; cdp=style |
| 11:35:45.977 PM MST | | 3 | agent | ui-apply | Type-in-the-outlined-Email Ready,Skip | 0 | 18 / 18 | 120 wall | id=q3 |
| 11:35:52.137–11:37:52 PM MST | | 3 | agent | ui-poll --wait=25 | loop | 0 | 25s worker each | | first poll replayed q2 + chat + Email click; later polls empty. **Long ui-poll holds the start-host worker** (same class of problem as ask-and-wait). No q3 in this window. |
| 11:38:03.851 PM MST | | 3 | agent | doctor + snap | | 0 | snap 31 / 26 | | health=ok; Ready s1.h14 and Skip s1.h15 still visible |
| 11:38:10.815 PM MST | | 3 | human | click Email | value=abc@example.com | | | **~7s after worker freed / ~145s after card** (delay explained by blocked worker) | field already filled from scenario 2 |
| 11:38:15.527 PM MST | | 3 | human | click Ready | q3 | | | **~5s after Email click** | choice=Ready |
| 11:38:42.027 PM MST | | 4 | agent | eval | location.href=http://127.0.0.1:8765/components.html | 0 | 43 / 39 | 147 wall | navigated; static server on 8765 |
| 11:38:43.178 PM MST | | 4 | agent | snap | | 0 | 18 / 15 | 126 wall | Upload File s1.h41, Choose File s1.h42 visible |
| 11:38:43.306 PM MST | | 4 | agent | panel-open | --tab=chat | 0 | 43 / 40 | | panel open after navigation |
| 11:38:43.449 PM MST | | 4 | agent | chat-send | please-try-Upload-File-then-tell-me-if-you-cannot-attach | 0 | 7 / 3 | | message id=4 |
| 11:38:51.518 PM MST | | 4 | human | click Upload File | id=upload-file | | | **~8s after prompt** | human-mode click only; no file set |
| 11:38:51.846 PM MST | | 4 | agent | act --kind=attach | --query=Upload-File --value=customers.yaml | 1 | 44 / 41 | | not found (query missed) |
| 11:38:52.0xx PM MST | | 4 | agent | act --kind=attach | --query=Choose File / css=#upload-file | 2 / 1 | 39 / 36; 37 / 34 | | **error: unknown kind attach** (and not found on css). No start-session upload kind. |
| 11:38:52.377 PM MST | | 4 | agent | attach --target=browser:auto | | 0 | 6 / 3 | 106 wall | joins CDP ws://127.0.0.1:43043/devtools/page/<frame> — not a file upload |
| 11:38:52.486 PM MST | | 4 | agent | execute-step | , attach the InternalFileInput "customers.yaml" | 1 | wall 655 | | No healthy Workbench session. Run isolate / session-start... (never-started) |
| 11:38:58.364 PM MST | | 4 | human | chat | cannot-attach-please-upload-customers-yaml | | | **~15s after prompt / ~7s after their click** | expected human-mode hand-off |
| 11:39:12.839 PM MST | | 4 | agent | chat-send | no-start-session-upload-verb | 0 | 9 / 5 | | ack of the API gap |
| 11:39:12.952 PM MST | | 5 | agent | ask-and-wait | --say=Timeout-card --choices=Later,Now --timeout=5000 | 1 | 5068 / 5064 | wall 5207 | error=timeout; id=q4; no value; human did not click (correct) |
| 11:39:18.162 PM MST | | 5 | agent | find Later | | 1 | 43 / 41 | | not found — first card removed |
| 11:39:18.458 PM MST | | 5 | agent | ui-apply | Retry-pick Alpha,Beta | 0 | 11 / 11 | | id=q5 |
| 11:39:28.382 PM MST | | 5 | human | click Alpha | q5 | | | **~10s after retry card** | choice=Alpha |
| 11:39:39.308 PM MST | | 6 | agent | eval | location.href=http://127.0.0.1:8766/guide-form.html | 0 | 13 / 10 | 96 wall | fresh page with #email (not in git; served from collab-test) |
| 11:39:40.4xx PM MST | | 6 | agent | panel-open | --tab=chat | 0 | 20 / 17 | | panel reopened |
| 11:39:40.511 PM MST | | 6 | agent | snap | | 0 | 11 / 9 | | Email s1.h58, Go, chat panel. Guide chrome not listed in compact snap. |
| 11:39:40.623 PM MST | | 6 | agent | guide | --steps=@ops/guide-steps.json | 0 | 41 / 38 | 131 wall | id=g1, steps=1, current=1, sentence=Guide shown... |
| 11:39:46–11:41:46 PM MST | | 6 | agent | (watch events, non-blocking) | 120s | | | | no new email/input/guide events from the human |
| 11:41:53.114 PM MST | | 6 | agent | eval | email.value | 0 | 5 / 3 | | value="" (empty) |
| 11:41:53.6xx PM MST | | 6 | agent | guide-done | | 0 | 14 / 10 | | Guide closed. how=agent in events at 11:41:53.637. Closed because the wait expired, not because the step completed. |
| 11:42:11.908 PM MST | | end | agent | chat-send | collab-test-done | 0 | 15 / 8 | 135 wall | message id=6 |
| 11:42:17.393 PM MST | | end | human | record popout chat | | | error=blocked | **~5s after done** | popup blocked; late, not part of a scenario |
| 11:43:58.070 PM MST | | end | agent | live-stop --all | | 0 | | after ~60s wait | stopped=true, controller=stopped, orphans=none |

## Wait for human / observed latency
- Agent over-waited ~10 minutes (11:22:17–11:32:23) for `human-notes.md` to show a session id or snap. The human *had* attached (`doctor health=ok` at 11:22:17) and was supposed to poll snap until a card appeared. That was a coordination deadlock, not a product hang.
- After that, once a card was on the page **and the start-host worker was not blocked**, human response latency was short:
  - Scenario 1 retry (q2 Alpha): **4.7 s**
  - Scenario 2 chat please-fill-email: **~10 s after Alpha**
  - Scenario 3 Ready: **~5 s** after they clicked Email, but **~145 s** after the card (agent had been holding the worker with `ui-poll --wait=25`)
  - Scenario 4 Upload click: **~8 s**; chat cannot-attach: **~15 s**
  - Scenario 5 retry Alpha: **~10 s**
  - Scenario 6: no human action in 120 s (page had just been navigated)
- `human-notes.md` was never updated after the 11:22:04 failed doctor, despite all of the above page activity.

## Bugs and issues
1. **Start-host command channel is exclusive during long waits (high).** `ask-and-wait --timeout=120000` and `ui-poll --wait=25` run on the live worker. While they run, the other process's `doctor` reports `health=down`, and snap/act from the human side cannot proceed. After the wait ends, doctor is ok again and the human acts within seconds. This broke the official scenario-1 `ask-and-wait` and delayed scenario 3. A two-party session cannot have one side block the worker.
2. **No start-session file-upload verb (high for scenario 4).** `act --kind=attach` → `unknown kind attach` (kinds are click|type|hover|press|select). CLI `attach --target=` joins a CDP websocket, it does not upload. `execute-step` with `, attach the InternalFileInput "customers.yaml"` requires a paused isolate worker (`never-started`). Human mode correctly cannot complete `<input type=file>`.
3. **`find-element` / `inspect-page` do not attach to a `start` host** (known from planning; not re-litigated here). Human used snap/find/act successfully for question-card buttons.
4. **Compact `snap` omissions (medium).** No field values (Email value invisible after form-fill). No pin/placement nodes. No highlight marker. Guide chrome not listed. Chat sending/delivered/read not listed. Human must infer highlight/guide from the ask/chat, not from snap.
5. **`chat-turn --text=email-filled` defaulted to `--until=human`** and immediately returned the already-queued human message. It was not a reliable way to *send* an agent reply. `chat-send --source=agent` did send.
6. **Possible source mis-tag:** form-fill at 11:35:33.100 produced an interaction event at 11:35:33.255 with `source=human` on Email (empty value). May be agent input recorded as human.
7. **`record --popout --panel=chat` (or a human click that pushed that action) returned `error=blocked`** at 11:42:17, after `collab-test-done`. Popup blocked; panel stays on the page. Valid per docs, but worth logging.
8. **Human-notes not kept in sync** (process/helper issue, not pickleball): only the first failed doctor was written.

## Sluggish or unresponsive
- Official `ask-and-wait` 120 s: workerMs=120075, and it made the *other* side unresponsive (`doctor health=down`). This is the main sluggish/unresponsive finding.
- `ui-poll --wait=25` loops: each call held the worker ~25 s (`workerMs` 25156, 25138, …). Same effect.
- Warm controller verbs were otherwise snappy: pin/ui-apply/highlight/chat-send ~3–40 ms worker; snap/find ~9–31 ms; form-fill 26 ms; click settle still adds ~500 ms when used. Cold-ish `execute-step` failure was 655 ms wall.
- Scenario 6 guide was shown (41 ms) but no human input followed a same-session navigation; the page/guide may have been easy to miss in snap (no guide chrome). Not a slow verb, a missed interaction.

## Attributes and tagging ideas
- Surface `data-pkb-purpose`, `data-pkb-card`, `data-pkb-meta`, and highlight/guide markers in compact snap (or a `--include=pkb` flag).
- Include control **values** (and checked state) on snap/find rows so the agent can read Email back without `eval`.
- Tag chat bubbles with sending/delivered/read in snap.
- Distinguish agent-initiated clicks from human ones in the event `source` field (see form-fill mis-tag).
- `health=down` during a long wait should be a wait-state, not “browser down”, or long waits should not occupy the only command slot.

## Feature ideas
- Non-blocking `ask` + `wait-for-answer --id=q2` (or subscribe) so the worker stays free for a second actor.
- First-class `upload` / `act --kind=attach --query=… --value=<path>` on a `start` session (no isolate worker).
- `--exposure=human|lower|full` on snap/find/act (currently only find-element, which cannot use this session).
- A `navigate --url=` verb so the agent does not need `eval` for fixture/handoff pages.
- Snap `--include=values,placements,highlights,guide`.

## Docs / instruction improvements
- Two-bot / two-process rule: **do not use long `ask-and-wait` or `ui-poll --wait` while another actor needs doctor/snap/act.** Use `ui-apply` to place the card and poll events or `ui-poll --wait=0` from the shell with sleep.
- `chat-turn --text=` still waits for a human by default; use `chat-send` to post an agent reply when a human message is already queued.
- Repeat from planning (still true): `--headed=true` as one token; do not use `./mvnw exec:java` for `start`; `ask-and-wait --timeout` is milliseconds, `chat-turn --timeout` is seconds.
- Coordinating via `human-notes.md` can deadlock if the human only writes notes after seeing controls. Watch `consumer-cli.log` / `agent-events.jsonl` as well.

## Extra API / MCP calls wished for
- `upload` / `act --kind=attach` on the start host (and MCP equivalent).
- `--exposure` on `workbench_snap` / `workbench_find` / `workbench_act`.
- `workbench_element_inspect` / `find-element` against a start-host session id.
- A non-blocking answer waiter (`wait --until=answer --id=q2`) that does not hold the worker.
- Snap fields for value, highlight, guide step, and placements.
- `navigate --url=` (or `start --url=` that reuses the live host).

## Verdict per scenario
- **1 Choose:** pass-with-gap. Official `ask-and-wait` (120 s) timed out because the wait marked doctor `health=down` and blocked the human. Retry via non-blocking `ui-apply` passed: human clicked Alpha in **4.7 s**, value exactly `Alpha`.
- **2 Chat hand-off:** pass. Human sent `please-fill-email` via the Message/Send panel (not `chat-send`). Agent `form-fill` applied `abc@example.com`. Agent reply required `chat-send` (`chat-turn --text=` consumed the queued human message). Observed human latency ~10 s after Alpha.
- **3 Highlight + type/confirm:** pass-with-gap. Highlight command ok. Human clicked Email (value already `abc@example.com` from scenario 2) and Ready (~5 s once the worker was free). Compact snap never showed the highlight. Latency of ~145 s from card to Ready is on the agent (blocked worker), not a slow human.
- **4 File-attachment hand-off:** pass-as-documented-API-gap. Human clicked Upload File (~8 s) and chatted `cannot-attach-please-upload-customers-yaml` (~15 s). Agent could not upload on the start session (`unknown kind attach`; `attach` is CDP join; `execute-step` needs isolate). Did not cheat with sendKeys. Upload Result was not updated.
- **5 Timeout recovery:** pass. 5 s `ask-and-wait` timed out, no value, Later not found (card removed). Retry card q5: human clicked Alpha in **~10 s**. No late click on the removed card was observed.
- **6 Guide:** fail. Guide g1 shown on a fresh `#email` page; compact snap listed Email but not guide chrome. After 120 s the field was still empty (`eval` value=""). Agent then `guide-done` (how=agent). No human input after the navigation.

## Human appearance
Yes. They hit a start race, attached at 11:22:17 PM MST, stayed silent in the notes file, but completed page interactions for scenarios 1 (retry), 2, 3, 4, and 5. They also tried a chat pop-out at 11:42:17 PM MST (`error=blocked`) after `collab-test-done`. They did not complete the guide. `live-stop --all` at 11:43:58 PM MST left no Chrome or controller.
