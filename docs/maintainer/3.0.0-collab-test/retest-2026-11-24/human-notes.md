# Human notes — retest 2026-11-24

## Session
- session id: livecc4dc4acb1b1
- doctor line (8:41:19 AM MST): chrome=present health=ok headed=true page=attached lease=held waits=0 worker=absent orphans=8 pids (wall 988 ms)
- session-mode after lock: {"human":"on"} (lock 616 ms, read 265 ms)

## Action log
| t (MST) | scenario | verb | args | exit | envelope ms / workerMs | gap_ms | result (applied, what the page showed) |
|---|---|---|---|---|---|---|---|
| 8:41:19.921 AM | setup | doctor | | 0 | wall 988 | - | health=ok |
| 8:41:20.916 AM | setup | session-mode | --human=on | 0 | wall 616 | 0 | human on |
| 8:41:21.538 AM | setup | session-mode | | 0 | wall 265 | 0 | human on |
| 8:41:24.742 AM | setup | snap | include all | 0 | 85 / 23 (wall 964) | 3200 | 14 controls; purpose= on all panel rows; no chat rows, no cards; Send disabled |
| 8:41:32.317 AM | setup | act type | --purpose=message Message ready | 0 | 276 / 203 | 7500 | applied=true |
| 8:41:33.401 AM | setup | act click | --purpose=send Send | 0 | 151 / 87 | 0 | applied=true; bubble "You · Read ready" |
| 8:41:37.847 AM | 1 | chat-read | --last=5 | 0 | 63 / 4 | 3500 | count=0, recent=[] even though agent c1 + my "ready" were on the panel (bug) |
| 8:41:38.695 AM | 1 | wait | --condition=new-control 15s | 0 | 1796 / 1731 | 0 | woke wake=push control=button "Alpha" |
| 8:41:45.696 AM | 1 | doctor | (during agent wait) | 0 | wall 934 | 6100 | health=ok waits=1 (B1 ok) |
| 8:41:46.636 AM | 1 | snap | --since=s.human.4 --include=cards,chat,values | 0 | 82 / 17 | 0 | delta.unchanged=true but added/removed list all panel rows (purpose= dropped in since rows); Alpha/Beta card="ask-q1 Pick-one open" |
| 8:41:47.488 AM | 1 | act click | Alpha | 0 | 141 / 73 | 0 | applied=true; card later recent state=answered choice=Alpha |
| 8:42:01–8:44:03 AM | 2 | snap poll (watch) | | 0 | ~65–85 ms each | | agent c3 "Scenario-2:What-should-I-do?" was already there at 8:41 (my watcher baseline swallowed it; ~2 min lost on my side) |
| 8:44:1x AM | 2 | act type / click | please-fill-email / Send | 0 | 116 / 108 | | applied=true both; bubble "You · Read please-fill-email" |
| 8:44:1x AM | 2 | wait | --until-change --selector=#s2.h1 | 2 | - | | usage error (my bad selector), message only prints usage, no reason |
| 8:44:2x AM | 2 | wait | --until-change (no selector) | 2 | - | | usage error again; --selector seems required but help says optional |
| 8:44:31 AM | 2/3 | snap | values,chat,highlights | 0 | ~70 | | Email value=filled@example.com highlight=outline-red; agent c5 Email-filled, c6 Scenario-3 |
| 8:46:1x AM | 3 | find | Email | 0 | 98 | | hit Email |
| 8:46:1x AM | 3 | act type | Email abc@example.com | 0 | 121 | | applied=true, value=abc@example.com |
| 8:46:1x AM | 3 | act press | Email Tab | 0 | 111 | | applied=true (B7 fixed) |
| 8:46:2x AM | 3 | wait | --until-change --selector=input 5s | 1 | 5078 | | timeout (Tab blur produced no pushed change in scope) |
| 8:46:25.7 AM | 3 | snap | | 0 | 64 | | highlight removed from Email |
| 8:46:28–8:51:30 AM | 4 | snap poll | | 0 | 65–85 | | agent c7 Scenario-4 at 8:46 (again in baseline; ~5 min lost on my side) |
| 8:51:3x AM | 4 | act click | Upload File | 0 | 1100 | | applied=false; page showed no change, no "Ask agent to attach a file" offer anywhere (snap, find --query=attach not found, screenshot) |
| 8:51:44 AM | 4 | find | --query=attach | 1 | 80 / 15 | | not found |
| 8:51:5x AM | 4 | act click | Tools | 0 | 138 | | applied=true, no visible change while panel open |
| 8:51:5x AM | 4 | snap | --since=s.human.5 | 0 | 75 | | no delta/added/changed fields at all, no snapCursor (stale cursor silently ignored?) |
| 8:52:01 AM | 4 | act type+click | cannot-attach-please-upload-customers-yaml, Send | 0 | 104 / 108 | | applied=true, bubble shown |
| 8:56:30.7 AM | 4 | snap poll | | 0 | 69 | | Upload File value=customers.yaml; page "Files: customers.yaml"; agent c9 Scenario-4-done. NOTE capture at 8:56:3x still showed "No file chosen" and old chat scroll (screenshot lag) |
| 8:56:4x AM | 5 | snap | | 0 | 74 | | Later/Now card="ask-t1 Timeout-card open" — did NOT click |
| 8:56:56.2 AM | 5 | wait | --until-change --selector=body 20s | 1 | 20075 | | timeout even though the card was replaced by Retry-pick at ~8:57:07 (push wake missed) |
| 8:57:17 AM | 5 | snap | | 0 | 78 | | Alpha/Beta card="ask-t1 Retry-pick open"; recent[] still titles it "Timeout-card" |
| 8:57:2x AM | 5 | act click | Alpha | 0 | 111 | | applied=true; recent ask-t1 answered choice=Alpha |
| 8:57:3x AM | B5 | find | --purpose=chat-tab | 0 | 76 | | found |
| 8:57:3x AM | B5 | act click | --purpose=guide-tab Guide, then chat-tab Chat | 0 | 105 / 139 | | applied=true; screenshots: pane switched both ways; Guide tab badge 1 visible on Chat view |
| 8:57:4x AM | 6 | snap | | 0 | ~75 | | Email row guide="g1 step 1/1 current"; Guide pane "1 Type abc in Email, then press Tab" + on-page callout |
| 8:57:5x AM | 6 | act type / press | Email abc / Tab | 0 | 110 / 104 | | applied=true both; guide callout gone; Guide pane then shows "No guide yet." (no done state) |
| 8:58:0x AM | dock | act type + Close | draft-unsent, Close | 0 | 120 | | confirm "Close and discard unsent text?" with "Discard and close"/"Keep open" (purpose tokens start "guide,..." — odd) |
| 8:58:1x AM | dock | Keep open, Minimize | | 0 | 111 | | panel collapsed to dock; snap still says panel=open and all panel rows visible=true (bug) |
| 8:58:1x AM | dock | act click | --purpose=reopen Open agent panel | 0 | 95 | | applied=true but panel did NOT reopen |
| 8:58:3x AM | recheck | act click | Go (agent "Recheck:please-click-Go-now") | 0 | 132 | | page "Went: abc" |
| 8:58:3x AM | dock | act click | Tools | 0 | 95 | | dock shows "Agent" pill; Tools badge=1 |
| 8:58:4x AM | dock | act click | Agent / Open agent panel | 0 | 78 | | applied=false; panel still minimized. Human cannot reopen panel; I stopped there (no workaround) |

## Rechecks (B1–B9, toolbar, purpose, snap --since, wait --until-change, chat-read)
- B1 wait lane: fixed — doctor health=ok waits=1 during agent wait; snap/find/act all 65–140 ms envelope.
- B2 upload: partial — agent attached customers.yaml (files line correct), but no human "Ask agent to attach a file" offer ever appeared after clicking Upload File; fallback chat used.
- B3 snap state: mostly fixed — values, highlight=outline-red, card="id title state", guide="g1 step 1/1 current", chat message rows all present. Gaps: panel visibility while minimized; card `recent[]` title stale after re-ask.
- B4 chat-turn: agent-side; from my side each agent line appeared once and my replies were picked up.
- B5 tabs: fixed — --purpose=chat-tab found; Guide/Chat clicks switch the pane; no --pick needed.
- B6 duplicates: fixed — one bubble per message, my messages show "Read" status.
- B7 applied: fixed for press Tab (true). Upload File click applied=false (page didn't visibly change, so honest). "Open agent panel" applied=true but nothing happened (dishonest).
- B8 guide: mostly fixed — pane filled, tab badged, Email guide= row; after completion pane says "No guide yet." instead of done.
- B9 cursors.chat: still broken — cursors.chat is "" in every envelope (snap, act, chat-read) all run.
- toolbar: Close confirm works; Minimize works; reopen from human side broken (Open agent panel and Agent pill both no-op).
- purpose=: present on all panel controls/tabs/messages; absent on page controls; missing on rows in snap --since added lists; close-confirm buttons tagged "guide,…".
- snap --since: returns delta but marks unchanged=true while listing added/removed rows that only differ by purpose; with an older cursor returns no delta fields at all.
- wait --until-change: never woke in 2 tries (selector=input, body) despite real DOM changes; without --selector it's a usage error. wait --condition=new-control woke by push fine (~1.7 s).
- chat-read: returned count 0 / recent [] at 8:41:37 although two messages were on the panel.

## Bugs and issues
1. Human cannot reopen the panel after Minimize: "Open agent panel" (applied=true) and the "Agent" pill (applied=false) do nothing; snap still reports panel=open with all panel rows visible=true.
2. No "Ask agent to attach a file" offer after clicking Upload File (B2 human half still missing).
3. wait --until-change never wakes on real changes (card swap, Tab blur); requires --selector despite help text.
4. chat-read empty while chat has messages; cursors.chat always "".
5. snap recent[] keeps old card title (Timeout-card) after re-ask as Retry-pick.
6. capture screenshot lagged page state once (8:56:3x showed "No file chosen" while snap had customers.yaml).
7. Guide pane shows "No guide yet." after completing the guide.

## Sluggish or unresponsive (verb + workerMs)
- None over 2 s envelope. JVM wall ~800–1000 ms per call (startup). act click Upload File 1100 ms envelope (slowest). wait new-control 1796/1731 (expected, waited for card).

## Attributes and tagging ideas
- data-pkb-purpose on the dock pill ("panel,reopen-pill") and on the upload offer.
- panel row visible=false (or panel="collapsed") when minimized.

## Feature ideas
- A human-tier "panel-reopen" equivalent is not needed if the reopen button works; but snap should expose panel="collapsed".
- recent[] should carry current title and prior titles for re-asked ids.

## Docs / instruction improvements
- wait --until-change: document that --selector is required and what selectors are valid (CSS, not snap ids).
- Brief: note that agent messages can arrive before helper starts watching; helpers should read the newest agent line before waiting (my own mistake cost ~7 min).

## Extra API / MCP calls you wished you had
- `wait --condition=new-message` (wake on a new agent chat line, push).

## Verdict per scenario (your side)
- 1: pass — Alpha answered, doctor/snap fast during wait.
- 2: pass — please-fill-email sent, Email filled by agent, no duplicate bubbles.
- 3: pass — highlight seen, typed, Tab applied=true, highlight cleared.
- 4: pass-with-gap — file attached by agent, but no human attach offer; used chat fallback.
- 5: pass-with-gap — did not click Timeout-card, Retry-pick Alpha answered; wait --until-change missed the swap, recent title stale.
- 6: pass-with-gap — guide shown and completed by typing abc + Tab; pane says "No guide yet." after.
- Rules: only human-tier verbs used; no control errors hit; no out-of-band coordination. Tempted to look for another way to reopen the panel; did not.

## Addendum
| 9:02:3x AM | recheck | snap | | 0 | ~70 | | page reloaded by agent (Email empty, files reset, new ids s2.h24/h25); Go row guide="g2 step 1/1 current" (guide survives reload, B8); panel still minimized, pill "Agent 1" badge |
| 9:02:3x AM | recheck | act click | Go | 0 | 120 | | applied=true; page "Went:" |
| 9:03:1x AM | end | act type+click | rechecks-done, Send | 0 | 125 / 139 | | applied=true and delivered ("You · Read rechecks-done") although the panel is minimized — act typed into a composer a person cannot see (visibility check wrong, same root as panel=open bug). Human msg ids restarted at h1 after reload. |
| 9:03:2x AM | end | snap | | 0 | ~70 | | agent c15 collab-test-done. Run over; I stopped watching. doctor stayed health=ok all run. |
