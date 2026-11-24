# Agent notes — retest 2026-11-24

## Session
- session id: livecc4dc4acb1b1 (host started 8:37 AM MST, headed, base 5f86daf4)

## Action log
| t (MST) | scenario | verb | args | exit | envelope ms / workerMs | gap_ms | result |
|---|---|---|---|---|---|---|---|
| 8:41:20.431 AM MST | 0 | browser-events | --start  | 0 |  /  | wall 1546 | {"op":"browser-events","via":"cdp","started":true,"cursor":0,"spill":"/workspace/pickleball/wt-collab2/maven-consumer-pr |
| 8:41:21.997 AM MST | 0 | chat-read | --last=5  | 0 | 68 / 5 | wall 810 | {"ok":true,"sentence":"Chat read; nothing was marked read.","op":"chat-read","count":0,"markedRead":0,"recent":[],"frame |
| 8:41:27.244 AM MST | 0 | chat-turn | --text=collab-retest-start:send-ready-when-attached --timeout=300s  | 2 | 64 / 0 | wall 806 | {"ok":false,"error":"chat-turn --timeout is seconds from 0 to 120","frame":"CB6DA432B6432CEAB90589965CE31810","elapsedMs |
| 8:41:31.340 AM MST | 0 | chat-turn | --text=collab-retest-start:send-ready-when-attached --timeout=120s  | 0 | 2118 / 2042 | wall 2985 | {"ok":true,"sentence":"human replied","op":"chat-turn","until":"human","sentId":"1","status":"delivered","count":1,"fram |
| 8:41:40.376 AM MST | 1 | ask-and-wait | --say=Pick-one --choices=Alpha,Beta --async  | 0 | 81 / 11 | wall 816 | {"ok":true,"sentence":"Question shown. answer-wait --id=q1 reads the choice without holding the command lane.","op":"ask |
| 8:41:41.244 AM MST | 1 | answer-wait | --id=q1 --timeout=60s  | 0 | 6335 / 6249 | wall 7127 | {"ok":true,"sentence":"Answer received.","op":"answer-wait","id":"q1","value":"Alpha","choice":"Alpha","applied":true,"w |
| 8:41:53.065 AM MST | 2 | panel-open | --tab=chat  | 0 | 82 / 20 | wall 849 | {"ok":true,"sentence":"Agent panel open on the page.","op":"panel-open","target":"page","panel":{"x":792,"y":235,"w":360 |
| 8:41:53.951 AM MST | 2 | chat-turn | --text=Scenario-2:What-should-I-do? --timeout=90s  | 1 | 60024 /  | wall 60808 | {"ok":false,"op":"chat-turn","error":"timeout","sentence":"timed out waiting for the live worker","fix":"chat-send --sou |
| 8:42:59.311 AM MST | 2 | chat-read | --last=5  | 0 | 62 / 3 | wall 794 | {"ok":true,"sentence":"Chat read; nothing was marked read.","op":"chat-read","count":3,"markedRead":0,"recent":[{"id":"a |
| 8:44:15 AM MST | 2 | chat-read loop | 18x4s | 0 | | | human please-fill-email (id 4) seen ~8:44:10; chat-turn --timeout=90s had timed out at 60s |
| 8:44:15.018 AM MST | 2 | form-fill | --target=browser:auto --fields=email=filled@example.com  | 0 | 97 / 37 | wall 835 | {"ok":true,"op":"form-fill","applied":true,"used":"cdp","kind":"browser","chain":"locate","fields":[{"field":"email","ki |
| 8:44:15.883 AM MST | 2 | chat-send | --text=Email-filled:filled@example.com  | 0 | 68 / 6 | wall 815 | {"ok":true,"sentence":"Chat message stored for this run channel.","message":{"id":"5","at":"2026-11-24T15:44:16.33756794 |
| 8:44:21.623 AM MST | 3 | highlight | --query=Email  | 2 | 65 / 0 | wall 821 | {"ok":false,"error":"highlight requires --handle","frame":"CB6DA432B6432CEAB90589965CE31810","elapsedMs":65,"timing":{"j |
| 8:44:22.474 AM MST | 3 | chat-send | --text=Scenario-3:type-abc@example.com-in-the-outlined-Email-field-then-press-Ta | 0 | 82 / 8 | wall 809 | {"ok":true,"sentence":"Chat message stored for this run channel.","message":{"id":"6","at":"2026-11-24T15:44:22.90979077 |
| 8:44:27.470 AM MST | 3 | find | --target=browser:auto --query=Email  | 0 | 92 / 27 | wall 835 | {"ok":true,"op":"find","applied":true,"used":"cdp","kind":"browser","chain":"locate","docVersion":"http://127.0.0.1:8766 |
| 8:44:28.340 AM MST | 3 | highlight | --handle=s1.h1 --preset=outline-red  | 0 | 71 / 7 | wall 817 | {"codes":["style_modified"],"sentence":"agent style; a person did not author this highlight","handle":"email","preset":" |
| 8:46:24 AM MST | 3 | snap loop | --include=values,highlights 3s | 0 | | ~110s after ask | Email value=abc@example.com highlight=outline-red seen |
| 8:46:24.403 AM MST | 3 | unhighlight | --handle=email  | 0 | 67 / 8 | wall 761 | {"codes":["style_modified"],"sentence":"agent style; a person did not author this highlight","handle":"email","reversed" |
| 8:46:25.195 AM MST | 4 | chat-send | --text=Scenario-4:please-click-Upload-File-then-Ask-agent-to-attach-a-file  | 0 | 63 / 5 | wall 795 | {"ok":true,"sentence":"Chat message stored for this run channel.","message":{"id":"7","at":"2026-11-24T15:46:25.63459350 |
| 8:46:26.024 AM MST | 4 | ui-poll | --after=0 --wait=60  | 0 | 95 /  | wall 848 | {"ok":true,"chat":[{"id":"4","source":"human","agent":"","text":"please-fill-email","pageId":"h2-mwuv7j0k"}],"next":"c.N |
| 8:56:06 AM MST | 4 | ui-poll loop | --wait=25 | 0 | | | human click upload-file 8:51:37, toolbar-collapse 8:51:51, chat cannot-attach 8:52:01; NO upload-request event |
| 8:56:06.627 AM MST | 4 | upload | --query=Upload-File --file=/workspace/pickleball/collab-test/retest-2026-11-24/cust | 1 | 90 / 18 | wall 844 | {"ok":false,"op":"upload","applied":false,"error":"not found","frame":"CB6DA432B6432CEAB90589965CE31810","elapsedMs":90, |
| 8:56:07.504 AM MST | 4 | snap | --target=browser:auto --format=lines  | 0 |  /  | wall 910 | v=1 ok=true op=snap ms=75 state.session=agent state.browser=up state.frame=CB6DA432B6432CEAB90589965CE31810 state.lease= |
| 8:56:12.638 AM MST | 4 | upload | --query=Upload --file=/workspace/pickleball/collab-test/retest-2026-11-24/customers | 1 | 79 / 19 | wall 816 | {"ok":false,"op":"upload","applied":false,"error":"not found","frame":"CB6DA432B6432CEAB90589965CE31810","elapsedMs":79, |
| 8:56:28.377 AM MST | 4 | upload | --ref=ref:duaj6yr:4 --file=/workspace/pickleball/collab-test/retest-2026-11-24/cust | 0 | 90 / 30 | wall 953 | {"ok":true,"op":"upload","applied":true,"used":"cdp","kind":"attach","chain":"act","docVersion":"http://127.0.0.1:8766/r |
| 8:56:30.194 AM MST | 4 | find | --target=browser:auto --query=Upload-File  | 1 | 73 / 16 | wall 803 | {"ok":false,"op":"find","applied":false,"error":"not found","frame":"CB6DA432B6432CEAB90589965CE31810","elapsedMs":73,"t |
| 8:56:44 AM MST | 4 | eval/find | files line; find --query=Upload | 0 | | | files='Files: customers.yaml' OK; find --query=Upload and Upload-File -> not found, 'Upload File' ok (substring miss) |
| 8:56:44.532 AM MST | 4 | chat-send | --text=Scenario-4-done:customers.yaml-attached.Next:scenario-5-do-not-click-the- | 0 | 71 / 6 | wall 815 | {"ok":true,"sentence":"Chat message stored for this run channel.","message":{"id":"9","at":"2026-11-24T15:56:44.98574696 |
| 8:56:50.418 AM MST | 5 | ask-and-wait | --say=Timeout-card --choices=Later,Now --id=t1 --async  | 0 | 65 / 4 | wall 818 | {"ok":true,"sentence":"Question shown. answer-wait --id=t1 reads the choice without holding the command lane.","op":"ask |
| 8:56:51.271 AM MST | 5 | answer-wait | --id=t1 --timeout=15s  | 1 | 15078 / 15008 | wall 15822 | {"ok":false,"error":"timeout","op":"answer-wait","id":"t1","frame":"CB6DA432B6432CEAB90589965CE31810","elapsedMs":15078, |
| 8:57:07.126 AM MST | 5 | ask-and-wait | --say=Retry-pick --choices=Alpha,Beta --id=t1 --async  | 0 | 73 / 4 | wall 842 | {"ok":true,"sentence":"Question shown. answer-wait --id=t1 reads the choice without holding the command lane.","op":"ask |
| 8:57:08.003 AM MST | 5 | answer-wait | --id=t1 --timeout=120s  | 0 | 14753 / 14677 | wall 15553 | {"ok":true,"sentence":"Answer received.","op":"answer-wait","id":"t1","value":"Alpha","choice":"Alpha","applied":true,"w |
| 8:57:27.940 AM MST | 6 | chat-send | --text=Scenario-6:follow-the-guide-in-the-Guide-tab  | 0 | 83 / 6 | wall 901 | {"ok":true,"sentence":"Chat message stored for this run channel.","message":{"id":"10","at":"2026-11-24T15:57:28.4714197 |
| 8:57:28.871 AM MST | 6 | guide | --steps=@/workspace/pickleball/collab-test/retest-2026-11-24/ops/guide-steps.json  | 0 | 97 / 26 | wall 872 | {"ok":true,"sentence":"Guide shown on the page; steps advance as the person acts.","op":"guide","id":"g1","steps":1,"cur |
| 8:57:29.774 AM MST | 6 | panel-state |  | 0 | 74 / 1 | wall 831 | {"ok":true,"sentence":"Toolbar and panel state.","op":"panel-state","dock":{"x":748,"y":619,"collapsed":true,"open":"pan |
| 8:57:52 AM MST | 6 | ui-poll | | 0 | | | helper typed 8:57:45; step g1/1 done how=input by=agent (expected by=human); guide done |
| 8:58:03.685 AM MST | R | chat-send | --text=Recheck:please-click-Go-now  | 0 | 66 / 2 | wall 801 | {"ok":true,"sentence":"Chat message stored for this run channel.","message":{"id":"11","at":"2026-11-24T15:58:04.1298293 |
| 8:58:04.516 AM MST | R | doctor |  | 0 |  /  | wall 944 | chrome=present session=livecc4dc4acb1b1 health=ok headed=true pid=1950470 bridge=present worker=absent lease=held page=a |
| 8:58:01.683 AM MST | R | watch | --target=browser:auto --selector=#status --until=change --duration=120000  | 1 | 60033 /  | wall 60846 | {"ok":false,"op":"watch","error":"timeout","sentence":"timed out waiting for the live worker","fix":"chat-send --source= |
| 9:00:29.692 AM MST | R | watch | --target=browser:auto --selector=#status --until=change --duration=20000  | 1 | 20076 / 20004 | wall 20880 | {"ok":false,"op":"watch","applied":false,"error":"timeout","until":"change","mode":"push","frame":"CB6DA432B6432CEAB9058 |
| 9:01:06.667 AM MST | R | watch | --target=#status --until=mutation --duration=20000  | 0 | 3119 / 3050 | wall 3901 | {"ok":true,"op":"watch","applied":true,"used":"cdp","kind":"browser","chain":"wait","mode":"until","until":"mutation","m |
| 9:01:18.096 AM MST | R | wait | --target=browser:auto --until-change --selector=#status --timeout=15000  | 1 | 15085 / 15011 | wall 15909 | {"ok":false,"op":"wait","applied":false,"error":"timeout","frame":"CB6DA432B6432CEAB90589965CE31810","elapsedMs":15085," |
| 9:01:42.356 AM MST | R | wait | --target=browser:auto --condition=new-control --timeout=15000  | 0 | 3214 / 3128 | wall 4015 | {"ok":true,"op":"wait","applied":true,"used":"cdp","kind":"browser","chain":"wait","mode":"poll","condition":"new-contro |
| 9:02:00.127 AM MST | R | form-fill | --target=browser:auto --fields=email=since@example.com  | 0 | 110 / 30 | wall 902 | {"ok":true,"op":"form-fill","applied":true,"used":"cdp","kind":"browser","chain":"locate","fields":[{"field":"email","ki |
| 9:02:01.063 AM MST | R | snap | --target=browser:auto --since=s.agent.609 --format=lines  | 0 |  /  | wall 905 | v=1 ok=true op=snap ms=92 state.session=agent state.browser=up state.frame=CB6DA432B6432CEAB90589965CE31810 state.lease= |
| 9:02:08.892 AM MST | R | guide | --steps=@/workspace/pickleball/collab-test/retest-2026-11-24/ops/guide2.json --id=g2 | 0 | 84 / 16 | wall 875 | {"ok":true,"sentence":"Guide shown on the page; steps advance as the person acts.","op":"guide","id":"g2","steps":1,"cur |
| 9:02:10.712 AM MST | R | navigate | --url=http://127.0.0.1:8766/retest.html  | 0 | 197 / 119 | wall 991 | {"ok":true,"op":"navigate","applied":true,"used":"cdp","kind":"browser","chain":"locate","url":"http://127.0.0.1:8766/re |
| 9:02:25.926 AM MST | R | chat-send | --text=Recheck-guide:please-click-Go-again-for-the-guide-step  | 0 | 80 / 11 | wall 955 | {"ok":true,"sentence":"Chat message stored for this run channel.","message":{"id":"12","at":"2026-11-24T16:02:26.4997788 |
| 9:02:55.017 AM MST | R | panel-open | --tab=chat --collapsed=false  | 0 | 100 / 29 | wall 989 | {"ok":true,"sentence":"Agent panel open on the page.","op":"panel-open","target":"page","panel":{"x":792,"y":235,"w":360 |
| 9:02:56.047 AM MST | R | panel-state |  | 0 | 64 / 1 | wall 862 | {"ok":true,"sentence":"Toolbar and panel state.","op":"panel-state","dock":{"x":742,"y":619,"collapsed":false,"open":"pa |
| 9:02:56.943 AM MST | R | find | --target=browser:auto --query=Chat --pick=1  | 0 | 119 / 24 | wall 901 | {"ok":true,"op":"find","applied":true,"used":"cdp","kind":"browser","chain":"locate","docVersion":"http://127.0.0.1:8766 |
| 9:03:03.650 AM MST | R | chat-turn | --text=Agent-scenarios-done.Finish-your-rechecks-then-send-rechecks-done --timeo | 0 | 12575 / 12495 | wall 13341 | {"ok":true,"sentence":"human replied","op":"chat-turn","until":"human","sentId":"13","status":"delivered","count":1,"fra |
| 9:03:23.533 AM MST | END | chat-send | --text=collab-test-done  | 0 | 71 / 4 | wall 827 | {"ok":true,"sentence":"Chat message stored for this run channel.","message":{"id":"15","at":"2026-11-24T16:03:23.9987149 |

## Timeline (MST)
- 8:41:31 chat-turn start line; helper `ready` 8:41:33 (2.1 s). Scenarios 1–6 8:41–8:58; rechecks 8:58–9:03; `rechecks-done` 9:03:16; `collab-test-done` 9:03:3x. Host left running.

## Verdicts (agent side)
1 Choose: **pass** — `ask-and-wait --async` 81 ms, `answer-wait` Alpha, wake=push wakeMs=4.
2 Chat hand-off: **pass-with-gap** — chat-turn delivered first (sentId/status), but `chat-turn --timeout=90s` returned `timeout ... live worker` at 60.0 s (client cap below the documented 120 s max); reply then read by chat-read polling. form-fill applied, no dup bubbles.
3 Highlight: **pass-with-gap** — `highlight --query=Email` (as the two-actor recipe says) is refused: highlight needs `--handle`. With the handle it worked; snap showed `highlight=outline-red` and the typed value.
4 File hand-off: **pass-with-gap** — `upload --ref` attached customers.yaml (files line correct, 90 ms). No `upload-request` ever arrived (helper saw no "Ask agent to attach a file"). `upload/find --query=Upload` and `--query=Upload-File` → not found; only exact "Upload File" matches.
5 Timeout/retry: **pass** — answer-wait 15 s timeout exit 1, re-ask same id, Alpha (push wake 1 ms).
6 Guide: **pass-with-gap** — Guide tab selected (panel tab=steps), step done, but `by=agent` although the helper (human-locked run) typed; same for g2 click step after reload.

## Rechecks (agent side)
- B1 wait lane: **fixed** — doctor health=ok, waits=1 during waits; human snap/act 65–140 ms envelope.
- B2 upload: **partial** — agent half fixed; human offer / upload-request missing.
- B3 snap state: **fixed** (values, highlight, guide, card, chat message rows); snap `--include=chat` lists only the last 5 messages.
- B4 chat-turn: **fixed** except the 60 s client timeout cap (also wrong fix text "chat-send --source=human").
- B5 tabs: **fixed** — find Chat → purpose `panel,chat-tab`.
- B6 duplicates: **fixed** — one bubble per message; navigate kept 5/5 rows, no replays.
- B7 applied: **mostly fixed** (true/false honest for most; helper saw "Open agent panel" applied=true with no effect).
- B8 guide: **fixed** for pane/tab/`guide=`/survives reload; attribution `by=agent` wrong; pane says "No guide yet." after done.
- B9 cursors.chat: **agent fixed, human broken** — agent envelopes advance (2,4,…,14); helper's are always "" and helper `chat-read` returned 0 messages. Likely chat is scoped to the agent run id's channel ("channel":"run"), so the human run id reads an empty channel.
- Toolbar dock: panel-state reads dock (x,y,collapsed,open); collapse persisted; `panel-open --tab=chat --collapsed=false` restored it; close-confirm event `close-cancel` arrived. Helper could not reopen after Minimize (bug).
- ask-and-wait --async / answer-wait: **works**.
- data-pkb-purpose: present on panel rows (chat-tab, send, …).
- snap --since: **works** for agent (only the changed Email row).
- watch / wait --until-change / browser-events --dom: **broken on the start host** — push `watch --selector=#status --until=change` and `wait --until-change --selector=#status` never woke on a real textContent change (eval) and on the helper's Go click; `browser-events --dom` (status dom=cdp) recorded 0 dom rows. `watch --until=mutation` (counter mode) woke in 3.1 s; `wait --condition=new-control` woke (wake=push but mode=poll in the same envelope).
- watch with --duration=120000 also hit the 60 s client cap with a chat-turn fix hint.
- browser-events: **works** for net/nav/security/storage/audit, via=cdp; storage values `[redacted]` with length. Noise: pickleball's own `pkb-dock` localStorage writes and DevTools issues from the panel's own inputs (FormEmptyIdAndName…) appear as page rows.

## Bugs (agent side)
A1 CLI-side 60 s cap: chat-turn (90 s) and watch (120 s) end at 60.0 s with "timed out waiting for the live worker" and a chat-turn-specific fix line.
A2 DOM push listeners never deliver on the start host (watch --selector, wait --until-change, browser-events --dom).
A3 Guide steps done by the human-locked run are `by=agent`.
A4 No upload-request / human attach offer.
A5 Chat is per run id: human run sees no messages, no cursors.chat.
A6 find/upload --query substring miss ("Upload" does not find "Upload File").
A7 Recipe doc says `highlight --query=Email`; verb needs `--handle`.
A8 ui-poll items: human chat/panel events carry `sessionId:"agent"` (e.g. close-cancel, toolbar-expand, "ready"), answers `sessionId:"human"` — attribution inconsistent.
A9 ui-poll --wait=30 returns at ~25 s; doctor lists Chrome children as `orphans=`; session-mode prints SLF4J warnings.
A10 browser-events stream includes pickleball's own storage/issue noise (no own=true tag).

## Latencies
Warm agent verbs 65–100 ms envelope, ~800–900 ms wall (JVM start). answer-wait push wake 1–4 ms. form-fill 97 ms, upload 90 ms, navigate 197 ms, guide 97 ms. No verb > 2 s except intended waits.

## Extra API/MCP wished for
- `chat-wait --after=<id> --timeout` (wait for a human line without sending one).
- `ui-poll --types=upload-request,step` filter.
- `highlight --query`.
- `snap --include=chat --last=N`.
