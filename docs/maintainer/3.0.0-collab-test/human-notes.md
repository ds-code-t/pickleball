# Human notes — (helper bot fills this in during the live run)

# Human notes — 2026-11-23

## Session
- session id: livedce7825a2acd
- doctor line: chrome=present session=livedce7825a2acd health=ok headed=true page=attached lease=held (11:22:17 PM MST)
- headed: true

## Action log
| t (MST) | scenario | verb | args | exit | envelope ms / workerMs | gap_ms | result |
|---|---|---|---|---|---|---|---|
| 11:22:04 PM MST | 0 | doctor |  | 0 | 136 wall |  | chrome/session/health/page absent; lease=held; 11 orphans listed; agent still booting — polling every 15 s |
| 11:22:17 PM MST | 0 | doctor |  | 0 | 191 wall |  | chrome=present session=livedce7825a2acd health=ok headed=true page=attached |
| 11:22:20 PM MST | 0 | snap | compact | 0 | 51 / 44 | 3422 | baseline fixture:form: Email, Go, panel (Chat/Guide/Recorder, Message, Send disabled) |
| 11:22:29–11:32:22 PM MST | 0 | snap poll | every ~2 s | 0 | ~110–130 wall each | ~2030 | no change for ~10 min |
| 11:32:24 PM MST | 0 | snap |  | 1 | 60159 wall | 2027 | hung, then `live worker unreachable: request timed out` |
| 11:33:02 PM MST | 0 | doctor |  | 0 | 4126 wall |  | health=down, worker=absent, page=absent (same session id) |
| 11:34:27 PM MST | 0 | doctor |  | 0 | 164 wall |  | health=ok again, same session, page=attached |
| 11:35:01 PM MST | 1 | snap |  | 0 | 130 wall | 2029 | `button "Alpha"` + `button "Beta"` appeared |
| 11:35:05 PM MST | 1 | act | click Alpha pick=1 | 0 | 634 / 631 (742 wall) | 3100 | dispatched; applied=false "no visible change"; buttons gone on next snap |
| 11:35:07 PM MST | 1 | find | Answer | 1 | 20 / 15 | 10 | not found ("Answer sent" text not exposed to find) |
| 11:35:15 PM MST | 2 | act | type Message please-fill-email | 0 | 52 / 48 | 8084 | applied=true |
| 11:35:15 PM MST | 2 | find | Send | 0 | 20 / 16 | 7 | s1.h10 |
| 11:35:16 PM MST | 2 | act | click Send pick=1 | 0 | 603 / 599 | 6 | applied=false; capture showed bubble "You · Delivered" then "Read" |
| 11:35:25 PM MST | 2 | capture |  | 0 | 42 / 39 |  | screenshot only way to read chat |
| ~11:35:43 PM MST | 2 | capture (watch) |  | 0 |  |  | agent "email-filled" + Email = abc@example.com (filled by agent) |
| 11:36:17–11:37:52 PM MST | 3 | snap poll |  | 0 | 17243–23550 wall (4 snaps) |  | snap very slow during this window |
| ~11:38:01 PM MST | 3 | capture (watch) |  | 0 |  |  | card "Type-in-the-outlined-Email" Ready/Skip; red outline on Email (pixels only) |
| 11:38:10 PM MST | 3 | find | Email | 0 | 122 wall | 10 | no highlight/outline attribute in item |
| 11:38:10 PM MST | 3 | act | type Email abc@example.com | 0 | 34 / ~30 (138 wall) | 10 | applied=true; replaced agent's value (not appended) |
| 11:38:15 PM MST | 3 | act | press Email Tab | 1 | 36 / 33 | 4384 | "not applied", dispatched=false |
| 11:38:16 PM MST | 3 | act | click Ready pick=1 | 0 | 661 wall | 6 | applied=false; card gone; outline stays |
| 11:38:42 PM MST | 4 | snap |  | 0 | 123 wall | 2011 | navigated to components.html; panel not in first snap |
| 11:38:51 PM MST | 4 | act | click "Upload File" pick=1 | 0 | 444 wall | 8689 | applied=true; no picker visible; "No file chosen"; panel back (Message s1.h53) |
| 11:38:58 PM MST | 4 | act | type Message cannot-attach-please-upload-customers-yaml | 0 | 230 wall | 4894 | applied=true |
| 11:38:58 PM MST | 4 | act | click Send pick=1 | 0 | 773 wall | 6 | applied=false; bubble Delivered; agent replied "no-start-session-upload-verb" at 11:39 |
| 11:39:18 PM MST | 5 | snap |  | 0 | 4080 wall | 2027 | slow snap |
| 11:39:20 PM MST | 5 | snap |  | 0 | 128 wall | 2028 | `Retry-pick` Alpha/Beta appeared (Timeout-card never seen) |
| 11:39:28 PM MST | 5 | act | click Alpha pick=1 | 0 | 668 wall | 4441 | applied=false; buttons gone |
| 11:39:30 PM MST | 5 | find | Alpha | 1 | 37 / 35 | 1010 | not found (card gone) |
| 11:39:40 PM MST | 6 | snap |  | 0 | 105 wall | 2027 | back on Email form; tab "Chat, 4 unread" |
| ~11:41:55 PM MST | 6 | capture (watch) |  | 0 |  |  | panel switched to Guide tab, "No guide yet." |
| 11:42:13 PM MST | 6 | snap |  | 0 | 157 wall | 2031 | tab "Chat, 5 unread"; page now 127.0.0.1:8766/guide-form.html |
| 11:42:17 PM MST | 6 | act | click query=Chat pick=1 | 0 | 655 wall | 4093 | MISTAKE: matched "Pop out chat", not the Chat tab; nothing visibly popped out |
| 11:42:23 PM MST | 6 | find | Chat | 0 | 125 wall | 3861 | only "Pop out chat" returned; tab "Chat, 5 unread" not matched |
| 11:42:30 PM MST | 6 | act | click ref=s1.h60 (Chat tab) | 0 | 661 wall | 2677 | applied=true, but capture still showed Guide "No guide yet." (next snap showed tab label "Chat") |
| 11:42:35–11:43:57 PM MST | 6 | snap+capture poll |  | 0 |  |  | no guide ever rendered |
| 11:43:59 PM MST | 6 | snap |  | 1 | 22 | 2011 | "no live browser" |
| 11:44:14–11:49:24 PM MST | end | doctor poll ×20 |  | 0 | ~160–700 wall |  | session/health/page absent; stopped |

Totals: ~430 snaps; mean snap wall ~480 ms (median ~120 ms; outliers below).

## Bugs and issues
- Session went `health=down` 11:32:24–11:34:27 PM MST (snap hung 60 s, `live worker unreachable: request timed out`), then recovered on the same session id. Session ended for good at 11:43:59 PM MST before scenario 6 got a guide or `collab-test-done`.
- `find --query=Chat` does not match the tab once its label becomes `Chat, 5 unread`; `--pick=1` then silently clicks `Pop out chat`. Unread-count suffix breaks name matching.
- Clicking the Chat tab (ref) returned applied=true but the screenshot still showed the Guide pane; panel seemed pinned to Guide.
- Agent message `email-filled` rendered twice; after navigation the chat history re-rendered with duplicate entries (`please-fill-email` twice) and the unread badge counted replayed messages.
- `act --kind=press --query=Email --value=Tab` fails `not applied` / dispatched=false — no way to Tab/blur as a person would.
- Two buttons named `Close` on components.html (page dialog + panel) — ambiguous for `--query=Close`.
- After navigation the panel is missing from the first snap for several seconds.

## Sluggish or unresponsive
- snap 60159 ms wall then error at 11:32:24 (worker hang).
- snap 17243–23550 ms wall ×4 between 11:36:17 and 11:37:52 PM MST (likely while the agent's wait/highlight calls held the worker); 4080 ms at 11:39:18. Normal snap is ~110–130 ms wall / ~45 ms worker.
- doctor 4.1 s wall while health=down (normally ~160 ms).
- Click acts are consistently ~600–660 ms workerMs (500 ms settle), and almost always report applied=false "no visible change" for panel/card buttons.

## Attributes and tagging ideas
- Expose highlight state on the element in snap/find (e.g. `highlight=outline-red` or `data-pkb-highlight`). The red outline was only visible in the screenshot.
- Expose ask-card title text (e.g. `Type-in-the-outlined-Email`, `Retry-pick`) as a group/label on the card buttons, plus card id/kind (`card=q3 kind=ask`).
- Expose field values (`value=abc@example.com`) in compact snap so a human-mode helper can see that Email got filled without a screenshot.
- Tag panel controls with `data-pkb-purpose` (chat-tab, guide-tab, popout) so `Chat` doesn't collide with `Pop out chat`.

## Feature ideas
- A human-mode read of the chat transcript (last N messages with author/time/status Delivered/Read). Today the only way to see agent messages is `capture` + reading the PNG.
- Snap `cursors.chat` / `events` never advanced (always `c.CjAKMAo`) even as messages arrived — a change token would let a watcher poll cheaply.
- Short-lived cards (Timeout-card) need a longer minimum lifetime or an event trail visible in human mode; with ~2 s polling I never saw it.

## Docs / instruction improvements
- Brief should say how the human learns of scenario 2's prompt; the agent's `what-should-I-do` message was not visible to me (I sent please-fill-email blind).
- Document that `--query` is a substring/prefix match and that tabs with unread badges change their accessible name.
- Document how a human-mode helper should blur/Tab (press fails).

## Extra API / MCP calls you wished you had
- `chat-read --last=5` (read-only, human mode).
- `snap --since=<token>` returning only diffs.
- `wait --condition=new-control` (wait for any new interactable, instead of polling).

## Rule temptations
- Tempted to use `ui-poll`/`chat_wait` to read chat and see the timeout card; did not. Never ran start/live-start/live-stop, never used control/injection tools, never messaged the agent out of band.

## Verdict per scenario (your side)
- 1 Choose: pass (Alpha clicked, card gone).
- 2 Chat hand-off: pass (message Delivered→Read, agent replied email-filled and filled Email). Gap: agent prompt not seen in snap.
- 3 Highlight: pass-with-gap (typed abc@example.com, clicked Ready; highlight only detectable via screenshot; Tab press failed).
- 4 File input: pass-with-gap (Upload File clicked, no picker, told agent in chat; agent replied no-start-session-upload-verb).
- 5 Timeout: pass-with-gap (Timeout-card never observed — I did not click it; possible it lived inside my 11:38:59–11:39:09 polling gap or during slow snaps; Retry-pick Alpha clicked OK).
- 6 Guide: fail / not run (Guide tab showed "No guide yet."; session ended 11:43:59 PM MST before any guide pointed at Email; collab-test-done never seen).
