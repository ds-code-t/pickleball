# Two-bot human-collaboration test plan

Pickleball branch `3.0.0` at `d265a018` (merge of PR #37, composite snapshot v2), 11:01:40 PM MST on 2026-11-23. Read-only planning; nothing was pushed. Worktree: `/workspace/pickleball/wt-collab` (detached at `origin/3.0.0`). Consumer project used for the live session: `/workspace/pickleball/wt-collab/maven-consumer-project`.

A helper bot plays the **human**. The main bot plays the **consumer project's AI agent**. They share one live Chrome page and may coordinate only through pickleball's injected collaboration UI (chat panel, question cards, guides, highlights, pins, checklist). No out-of-band chat between bots.

## Feasibility verdict

**Feasible, with caveats. No hard blocker for the core choose/confirm, chat, highlight, guide, and timeout scenarios.** The file-attachment hand-off will likely expose a missing start-session upload verb rather than a full pass.

A cheap smoke on this box at 11:16–11:19 PM MST (America/Phoenix, UTC-7) confirmed two independent JVMs can drive the **same** `start` session:

1. Agent (or a bootstrap process) runs `start --url=fixture:form --browser=chrome --headed=true --panel --hold=...`.
2. Later JVMs in the **same project directory** post page verbs to that start host via `.pickleball/consumer-session.json`. `doctor` from a second JVM reported `chrome=present`, `health=ok`, `bridge=present`, `page=attached`, `lease=held`.
3. Agent `ui-apply` placed an ask card (`id=q1`, choices Alpha/Beta) in 121 ms worker time.
4. Human-side `snap` listed `button "Alpha"` and `button "Beta"` (open shadow under `/html[1]/pkb-layer[1]`). `find --query=Alpha` returned `s1.h12`.
5. Human-side `act --kind=click --query=Alpha --pick=1` dispatched the click (667 ms, including the 500 ms settle).
6. Agent `ui-poll --after=0` received `{"type":"answer","id":"q1","choice":"Alpha"}`.

A second start with `--headed=true` opened a visible Chrome window on `DISPLAY=:34` (no `--headless=new`). `live-stop --all` cleared the host and Chrome. No leftover processes.

Shared CDP attach (`attach --target=browser:cdp=<url>`) is documented and Chrome is launched with `--remote-debugging-port=0`, but it was **not required**. The supported path is both sides using the same start host. Do not give each bot its own `start`.

### Caveats and gaps (not blockers unless noted)

1. **Do not use the documented `./mvnw exec:java` path for `start`.** Maven sets `java.class.path` to `plexus-classworlds-*.jar`, so the child `LiveBrowserHost` JVM fails with `ClassNotFoundException: tools.dscode.common.agentfast.LiveBrowserHost`. Use `java -cp <resolved test classpath>` (wrapper: `/workspace/pickleball/collab-test/pkb.sh`). The jar at `~/.m2/repository/tools/dscode/pickleball/3.0.0/pickleball-3.0.0.jar` matches the PR #37 build (SHA `3388ef60…`); no Gradle rebuild was run.
2. **Always pass `--headed=true` as one token.** A bare `--headed` is parsed as taking the next flag as its value (`--panel`), so `headed` stays false and Chrome starts with `--headless=new`. Confirmed: `--headed=true` yields `headed=true` and a real window on `:34`.
3. **`find-element` and `inspect-page` (the only CLI verbs with `--exposure=human|lower`) do not attach to a `start` host.** They print `error: start a session first` / `fix: isolate, then retry this command`, even with `--session=<id>`. The human bot must use `snap`, `find`, and `act` against `browser:auto`.
4. **`snap` / `find` / `act` have no `--exposure` flag.** Controller queries are documented to default to human view; `act` still finds a target a person could click, and `PageElementQueryTest.humanActRefusesAndLowerActKeepsTheSentence` refuses an unclickable target unless exposure is `lower`. This is an API gap to note (add `--exposure=human|lower|full` on snap/find/act). Until then, the human bot must not pass `lower` / `full-access`, and must not use `eval` or page-source dumps.
5. **Pins and other non-interactive placements are invisible to `snap` (compact) and `find`.** After `pin --text=Pinned-note`, `find --query=text=Pinned-note` and `find --query=css=[data-pkb-card=pin]` returned not found. `ui-verify` saw `pkb-1` as `visible=true`. Question-card **buttons** do appear in `snap`/`find`. Prefer ask cards, chat, guides, and highlights for coordination. Treat missing pins in human-view queries as a findings item (attribute/tagging idea: expose `data-pkb-purpose` / `data-pkb-card` in compact snap).
6. **`act` may return `applied:false` / `reason: no visible change` on a successful question-card click.** The answer still arrived on `ui-poll`. Do not treat that envelope as a failed click. Pass criteria use the answer/chat event, not `applied`.
7. **No first-class file-upload verb on a `start` session.** Gherkin `, attach the InternalFileInput "customers.yaml"` is a dynamic step. CLI `attach --target=` joins a browser/CDP target; it does not upload a file. `execute-step` still needs a paused Workbench isolate worker. Scenario 4 is designed to surface this gap (recommended new verb: `act --kind=attach --query=… --value=<path>` or `upload --ref= --file=`).
8. **Cold CLI cost is ~700–900 ms JVM startup plus worker time** (find/snap ~15–140 ms, pin/ui-apply ~30–120 ms, click ~580–670 ms including the 500 ms settle). Prefer `controller-start` once per side (or one shared controller) so later verbs hop in-process. Timestamp both wall clock and the envelope `ms` / `timing.totalMs` / `timing.workerMs`.
9. The local fixture site (`http://127.0.0.1:8765/components.html`) is not up unless something serves `maven-consumer-project/src/test/resources/site/`. For file-upload, serve that directory or use `start --url=file://…/components.html`. `fixture:form` is a data URL (Email + Go) and is enough for scenarios 1–3, 5, and 6.

## Environment and commands

```bash
export JAVA_HOME=/home/box/jdk/jdk-21.0.13+9
export PATH="$JAVA_HOME/bin:$PATH"
export DISPLAY=:34          # this agent's box desktop; Xvfb :1 also exists
# Optional: PKB_PROJECT=/workspace/pickleball/wt-collab/maven-consumer-project

# From either bot, in any cwd:
/workspace/pickleball/collab-test/pkb.sh <verb> [flags…]
```

`pkb.sh` cds into the consumer project so both sides share `.pickleball/consumer-session.json`. JDK 21.0.13+9, Chrome 156.0.8078.47, no machine-wide Maven/Gradle required.

### Bootstrap (agent, once)

```bash
/workspace/pickleball/collab-test/pkb.sh controller-start
/workspace/pickleball/collab-test/pkb.sh start \
  --url=fixture:form --browser=chrome --headed=true --panel --hold=forever --size=1200x800
/workspace/pickleball/collab-test/pkb.sh doctor
# Expect: chrome=present health=ok headed=true panel open (from start), lease=held, page=attached
```

`start` must print `sentence` `live browser ready, panel open` and `headed=true`. If `headed` is false, stop and fix the flag (use `--headed=true`).

For scenario 4 only, start against the components page instead (or navigate after start):

```bash
python3 -m http.server 8765 --bind 127.0.0.1 \
  --directory /workspace/pickleball/wt-collab/maven-consumer-project/src/test/resources/site &
/workspace/pickleball/collab-test/pkb.sh start \
  --url=http://127.0.0.1:8765/components.html --browser=chrome --headed=true --panel --hold=forever
```

### Human side (helper bot) — allowed verbs

```bash
/workspace/pickleball/collab-test/pkb.sh doctor
/workspace/pickleball/collab-test/pkb.sh snap --target=browser:auto
/workspace/pickleball/collab-test/pkb.sh find --target=browser:auto --query=Alpha
/workspace/pickleball/collab-test/pkb.sh act --target=browser:auto --kind=click --query=Alpha --pick=1
/workspace/pickleball/collab-test/pkb.sh act --target=browser:auto --kind=type --query=Email --value=abc@example.com
/workspace/pickleball/collab-test/pkb.sh act --target=browser:auto --kind=type --query=Message --value=please-fill-email
/workspace/pickleball/collab-test/pkb.sh act --target=browser:auto --kind=click --query=Send --pick=1
/workspace/pickleball/collab-test/pkb.sh wait --target=browser:auto --ref=s1.h12 --condition=visible --timeout=15000
/workspace/pickleball/collab-test/pkb.sh capture --target=browser:auto --out=/workspace/pickleball/collab-test/human-capture.png
```

Keep `Message` / email values free of spaces in `--value` (or write a file and use a later improvement). Maven-style space splitting is not an issue with `pkb.sh`, but the product docs still recommend space-free flag values.

### Agent side — control tools (full access)

```bash
/workspace/pickleball/collab-test/pkb.sh ask-and-wait --say=Pick-one --choices=Alpha,Beta --timeout=120000
/workspace/pickleball/collab-test/pkb.sh ui-apply --ops=@/workspace/pickleball/collab-test/ops/ask.json
/workspace/pickleball/collab-test/pkb.sh ui-poll --after=0 --wait=25
/workspace/pickleball/collab-test/pkb.sh chat-turn --text=ready --until=human --timeout=120
/workspace/pickleball/collab-test/pkb.sh highlight --handle=s1.h1 --preset=outline-red
/workspace/pickleball/collab-test/pkb.sh unhighlight --handle=s1.h1
/workspace/pickleball/collab-test/pkb.sh pin --text=Pinned-note
/workspace/pickleball/collab-test/pkb.sh toast --text=Toast-note
/workspace/pickleball/collab-test/pkb.sh checklist --items=Alpha|Beta
/workspace/pickleball/collab-test/pkb.sh guide --steps=@/workspace/pickleball/collab-test/ops/guide-steps.json
/workspace/pickleball/collab-test/pkb.sh guide-done
/workspace/pickleball/collab-test/pkb.sh ui-verify
/workspace/pickleball/collab-test/pkb.sh snap --target=browser:auto
/workspace/pickleball/collab-test/pkb.sh form-fill --target=browser:auto --fields=email=abc@example.com
```

Equivalent MCP tools (same live host, pass `session` / `sessionId` when needed): `workbench_start`, `workbench_snap`, `workbench_find`, `workbench_act`, `workbench_form_fill`, `workbench_chat_turn`, `workbench_ui_verify`, `workbench_agent_command` (ops `ask-and-wait`, `pin`, `highlight`, `guide`, `ui-apply`, `ui-poll`, …), plus `ui` / `ui_poll` / `ui_catalog`.

### Teardown (agent, once)

```bash
/workspace/pickleball/collab-test/pkb.sh live-stop --all
```

## Roles and rules

| | Consumer agent (main bot) | Human (helper bot) |
|---|---|---|
| View | Full control tools; may use `ui-poll`, `chat-turn`, `ui-verify`, inject UI | Human mode only: `snap` / `find` / `act` / `wait` / `capture` / `doctor` |
| May inject | Yes: `ask-and-wait`, `ui-apply`, `guide`, `highlight`, `pin`, `chat-turn`, `form-fill` | No |
| Perception | Snap plus overlay/chat events | Re-snap / find until the new control is visible. Do not `ui-poll` or `chat-turn` |
| Input | Any control verb, including file/OS actions if a verb exists | Natural click / type / press / select only. No file picker, no `eval`, no `chat-send --source=human` |
| Coordination | Only through the page (panel, cards, guide, highlight) | Same. No talking to the agent outside the page |

The agent that starts a wait (`ask-and-wait`, `chat-turn --until=human`) must not also be the process that answers it.

## Core scenarios

Run in order. After each, both sides append to their notes. Stop a scenario at the first failed pass criterion, note it, and continue to the next unless the session is dead (`doctor` not `health=ok`).

### 1. Agent asks the human to choose

**Setup:** `fixture:form`, panel open.

**Agent**

1. Start `ask-and-wait --say=Pick-one --choices=Alpha,Beta --timeout=120000` in a process that stays blocked (do not also click).
2. Record the start timestamp. On return, record `value` / `choice`, `ms`, and exit code.

**Human**

1. Poll `snap --target=browser:auto` until `button "Alpha"` and `button "Beta"` appear (or `find --query=Alpha` succeeds).
2. `act --kind=click --query=Alpha --pick=1`.
3. Optionally snap again; the card may show `Answer sent: Alpha` briefly (~1.5 s) and then close.

**Pass**

- Agent exit 0, `choice` / `value` is exactly `Alpha` (no added quotes).
- Human found the buttons via `snap` or `find`, not via `ui-poll`.
- A click with `applied:false` is still a pass if the agent received `Alpha`.

### 2. Human hands off a task over chat

**Agent**

1. `chat-turn --text=what-should-I-do --until=human --timeout=120` (blocks).
2. When the human message arrives, if it asks to fill Email, `form-fill --fields=email=abc@example.com` or `act --kind=type --query=Email --value=abc@example.com`.
3. Reply with a second `chat-turn --text=email-filled` (no `--until`, or a short wait).

**Human**

1. Snap until `textbox "Message"` and `button "Send"` are visible. Send may be `enabled=false` until the field has text.
2. `act --kind=type --query=Message --value=please-fill-email`.
3. Snap; confirm Send is enabled; `act --kind=click --query=Send --pick=1`.
4. Snap until an agent reply is visible in the panel (or the Email field reads back `abc@example.com`).

**Pass**

- Agent `chat-turn` returns the human text once (not a message already marked Read).
- The human typed and clicked in the panel; they did not use `chat-send`.
- Email contains the agreed value, or the notes record why form-fill / type failed.
- Observe sending → delivered → read if the bubbles expose those states in `snap`; if not, log that as a docs/attribute finding.

### 3. Agent highlights an element for the human

**Agent**

1. `find --query=Email` (or snap) and note the short id (`s1.h1` in the smoke).
2. `highlight --handle=s1.h1 --preset=outline-red`.
3. Place a short ask: `ask-and-wait --say=Type-in-the-outlined-Email --choices=Ready,Skip --timeout=120000` or a pin plus chat. Prefer an ask card because pins may not appear in the human snap.
4. After the human types, `snap` or `explain` to read Email back.

**Human**

1. Snap / find until the ask (or the Email box) is available. Note whether the highlight is visible in `snap` (likely not as its own node; it is a style/overlay).
2. Confirm or go to Email; `act --kind=type --query=Email --value=abc@example.com`.

**Pass**

- Highlight command `ok`.
- Human types into Email without the agent calling `form-fill`.
- Notes say whether the highlight was perceivable from `snap`/`find` or only assumed. If the human cannot detect the highlight without `eval`, that is a findings item (e.g. a `data-pkb-highlight` marker in snap).

### 4. File-attachment hand-off (human mode cannot complete it)

**Setup:** components page with `#upload-file` (`InternalFileInput`, label Upload File) and `Upload Result: none`. Use the static server or a `file://` URL. A test file already exists in the consumer resources (`customers.yaml` under the project's test data); record the exact path used.

**Human**

1. Snap / find the file input (`Upload File`).
2. Attempt only human-mode `act` (click the file input). Do not call Gherkin `attach`, `eval`, or OS-level sendKeys.
3. In the chat panel, tell the agent the field cannot be completed in human mode (e.g. type `cannot-attach-please-upload-customers-yaml` and Send).

**Agent**

1. Wait on that chat (`chat-turn` or `ui-poll`).
2. Try every public start-session verb that might upload: `act --kind=attach` (if rejected, log the error), `attach --target=…` (this is CDP/target join, not upload — log that), `execute-step` with `, attach the InternalFileInput "customers.yaml"` (expected: needs a paused isolate worker).
3. Read back `Upload Result:` via snap/find if anything landed.

**Pass (honest)**

- Human could see the file input and could not complete a file chooser in human mode. That part is expected.
- If the agent can upload through a documented start-session verb and the page shows `Upload Result: customers.yaml`, that is a full pass.
- If not, the scenario **passes as a documented API gap**, not as a product failure of human mode. Record the exact errors and the recommended verb (`act --kind=attach` / `upload --file=`). Do not cheat with raw Selenium sendKeys and call it a pass.

### 5. Error / timeout recovery

**Agent**

1. `ask-and-wait --say=Timeout-card --choices=Later,Now --timeout=5000` with the human instructed not to click yet.
2. Expect exit 1, `error: timeout`, no `value`, question removed.
3. Immediately retry `ask-and-wait --say=Retry-pick --choices=Alpha,Beta --timeout=120000`.

**Human**

1. During the first wait, snap but do not click. Note when the first card disappears.
2. When the retry card appears, click `Alpha`.

**Pass**

- First call times out and removes the card (human snap no longer lists those buttons).
- Retry returns `Alpha`.
- If a late click on a removed card is possible, log it (stale-target finding).

### 6. Guide: agent walks the human through a field

Write `/workspace/pickleball/collab-test/ops/guide-steps.json`:

```json
[{"t":"#email","say":"Enter \"abc\" in this textbox","do":"input","expect":"abc"}]
```

**Agent**

1. `guide --steps=@/workspace/pickleball/collab-test/ops/guide-steps.json`
2. Wait with `ui-poll --wait=25` (or poll repeatedly) for the guide-done / input event. Do not type into Email yourself.
3. If the guide is still open after a successful field match, `guide-done` should report already closed and must not push a second done event.

**Human**

1. Snap until the guide callout / Email field is the current step. Note whether the quoted `abc` appears as a `.pkb-value` chip in snap (name/text) or is invisible to snap.
2. `act --kind=type --query=Email --value=abc`, then Tab or click away so the step can end on Enter, blur, or `expect`.

**Pass**

- The Email field reads `abc`.
- The guide completes from the human's input, not from the agent typing.
- Notes record whether guide chrome was listed in `snap`/`find`.

## Shared notes template

Both bots append, never overwrite, to:

- Agent: `/workspace/pickleball/collab-test/agent-notes.md`
- Human: `/workspace/pickleball/collab-test/human-notes.md`

Use America/Phoenix (MST, UTC-7) timestamps. After the run, the main bot merges both files into `/workspace/pickleball/collab-test/findings.md`.

```markdown
# <role> notes — <date>

## Session
- session id:
- start command:
- headed:
- chrome / doctor:

## Action log
| t (MST) | t_ms | scenario | side | verb | args | exit | envelope ms / workerMs | gap_ms (from previous) | result (short) |
|---|---|---|---|---|---|---|---|---|---|
| 11:18:31.430 PM MST |  | 1 | human | act | click Alpha | 0 | 667 / 577 |  | dispatched; applied=false no visible change |

Gaps: wall-clock time from the end of the previous verb (including JVM startup unless `controller-start` is up). Also note workerMs so sluggish page work is not blamed on JVM start.

## Bugs and issues
- id / symptom / verb / envelope excerpt / severity

## Sluggish or unresponsive
- verb, workerMs, what felt slow, whether a retry helped
- clicks that stayed `applied:false` even when the page/event changed

## Attributes and tagging ideas
- What would have made selection/filtering easier (e.g. `data-pkb-card`, `data-pkb-purpose`, highlight marker, Read/sending state on chat bubbles in snap)
- Whether injected UI was missing from compact snap

## Feature ideas
- New collaboration components or flows

## Docs / instruction improvements
- Flag pitfalls (`--headed=true`, no Maven exec for start, find-element vs snap, ask-and-wait timeout units: seconds on chat-turn, ms on ask-and-wait)

## Extra API / MCP calls
- e.g. `--exposure` on snap/find/act; `upload`/`act --kind=attach` on a start session; find-element against a start host; snap including placements/highlights

## Verdict per scenario
- 1–6: pass / pass-with-gap / fail, one line each
```

## Docs and fixtures this plan is based on

- `docs/collaborative-self-test.md` (Human participation: start, chat-turn, ask-and-wait, guide)
- `docs/collaborative-recording.md` (`data-pkb-meta`, `data-pkb-purpose`, record/chat pop-out)
- `docs/dynamic-control-api.md` (start, snap, find, act, ask-and-wait, ui-apply, ui-poll, highlight, attach-as-target)
- `docs/consumer-agent-guide.md`, `docs/workbench-agent-detail.md`, `docs/snapshot.md`, `AGENTS.md`
- `maven-consumer-project/src/test/resources/features/recorded-collaboration.feature` (export shape, not a live two-bot session)
- `maven-consumer-project/src/test/resources/features/area-c.feature` (Gherkin `attach the InternalFileInput`)
- Built-in `fixture:form` in `FixturePages` (Email + Go only)

## Out of scope for this run

- Merging, pushing, tags, or anything on `master` / `2.x`
- Heavy Gradle builds or the full `@all` suite
- Opening the Workbench GUI
- A real person at the keyboard (the helper bot is the person)
