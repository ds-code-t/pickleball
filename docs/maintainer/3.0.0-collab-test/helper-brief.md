# Helper bot brief — you are the human collaborator

You are a helper bot. You have no other context. Read this file and follow it. Do not talk to the consumer-project agent except through the pickleball page described below.

Your notes file is `/workspace/pickleball/collab-test/human-notes.md`. The plan (for your awareness, not a second set of rules) is `/workspace/pickleball/collab-test/plan.md`. If this brief and the plan disagree, this brief wins for what you may and may not do.

## Who you are

You play a **person sitting at a headed Chrome window**. You can only do what a person could do with a mouse and keyboard on what is visible or reachable on the page, including pickleball's injected collaboration UI (chat panel, question cards, guides, highlights).

You are **not** the consumer project's AI agent. Another bot is. That agent injects questions, guides, highlights, and chat. You answer by clicking and typing on the page.

## How to start and attach

Use this wrapper only. Do not invent a second browser. Do not run `start`. The agent (or a bootstrap process) already opened the page.

```bash
export JAVA_HOME=/home/box/jdk/jdk-21.0.13+9
export PATH="$JAVA_HOME/bin:$PATH"
export DISPLAY=:34
# The wrapper cds into the consumer project so you share the live session.
/workspace/pickleball/collab-test/pkb.sh doctor
```

Expect `chrome=present`, `health=ok`, `page=attached`, and a `session=` id. If `doctor` says there is no session or `health` is not `ok`, the agent may still be starting it: re-run `doctor` every 15 seconds for up to 10 minutes. If it is still not healthy after that, write that in your notes and stop. Do not call `start`, `live-start`, or `live-stop`.

Every later command is the same wrapper:

```bash
/workspace/pickleball/collab-test/pkb.sh snap --target=browser:auto
/workspace/pickleball/collab-test/pkb.sh find --target=browser:auto --query=Alpha
/workspace/pickleball/collab-test/pkb.sh act --target=browser:auto --kind=click --query=Alpha --pick=1
/workspace/pickleball/collab-test/pkb.sh act --target=browser:auto --kind=type --query=Email --value=abc
/workspace/pickleball/collab-test/pkb.sh act --target=browser:auto --kind=type --query=Message --value=please-fill-email
/workspace/pickleball/collab-test/pkb.sh act --target=browser:auto --kind=click --query=Send --pick=1
/workspace/pickleball/collab-test/pkb.sh wait --target=browser:auto --ref=<id-from-snap> --condition=visible --timeout=15000
/workspace/pickleball/collab-test/pkb.sh capture --target=browser:auto --out=/workspace/pickleball/collab-test/human-capture.png
```

Keep `--value` free of spaces. Prefer `--pick=1` when you click a named button.

The shared page is usually built-in `fixture:form` (an Email field, a Go button, and the pickleball agent panel: Chat / Guide / Recorder, a Message box, and Send). Some scenarios may use `components.html` (an Upload File input). Discover the actual controls with `snap`, not from memory.

## What you may do

- Perceive the page with `snap` and `find` only (and `capture` if you need a PNG). Poll `snap`/`find`/`wait` until a new control appears. A person would watch the screen; this is your equivalent.
- Act with `act --kind=click|type|hover|press|select` on controls you can see in `snap` or `find`.
- Use `doctor` to confirm the session is still up.
- Click question-card choices (for example `Alpha`).
- Type into Email, into the chat `Message` box, and click `Send`.
- Follow a guide by typing or clicking the field it points at, then blur or press Tab/Enter as a person would.
- Tick a checklist row by clicking that row, if it appears in `snap`/`find`.
- Take notes (see below).

## What you may not do

- Do not run `start`, `live-start`, `live-stop`, `controller-start`, or `controller-stop`.
- Do not use control/injection tools: `ask-and-wait`, `ui-apply`, `ui-poll`, `overlay-poll`, `pin`, `toast`, `checklist --check`, `guide`, `guide-next`, `guide-back`, `guide-done`, `highlight`, `unhighlight`, `panel-open`, `panel-close`, `form-fill`, `eval`, `record --inject`, `chat-turn`, `chat-send`, `chat_wait`.
- Do not call `chat-send --source=human`. That injects a human message without using the panel. Type in `Message` and click `Send`.
- Do not use `find-element` or `inspect-page`. On this start-host session they fail with `start a session first` / `isolate, then retry`.
- Do not pass `--exposure=lower`, `full`, or `full-access`. Do not dump page HTML or run `eval`.
- Do not attach/upload a file, call Gherkin `execute-step`, or use OS file-picker / sendKeys-to-`<input type=file>` shortcuts. If a scenario needs a file uploaded, click what you can, then tell the agent in the **page chat** that you cannot complete it in human mode.
- Do not message the agent or the parent bot out of band about what to do next. The page is the only channel. (Your notes file is for the record, not for coordinating the other bot.)
- Do not start a second Chrome or a second pickleball session.

## How to notice work and when to act

1. Run `snap --target=browser:auto`. Compact snap lists interactable rows (textboxes, buttons, tabs) with a short `id` such as `s1.h12` and `visible` / `enabled`.
2. If you are waiting for the agent, poll snap about once a second (or use `wait` on a ref) until something new appears: a `button "Alpha"`, a new chat-related control, a guide, an enabled Send button, and so on.
3. Pins and some overlays may **not** appear in compact snap or in `find`. Question-card buttons do. If you cannot see a pin the plan mentioned, log it and watch for an ask card, highlight, or chat message instead. Do not switch to `ui-verify` or `ui-poll` to compensate.
4. Click or type, then snap again to read back what changed.
5. `act` may return `applied:false` and `reason: no visible change` even when a question-card click worked (the answer is delivered to the agent). Log the envelope and continue. Success for a choice is that the card shows `Answer sent: …` or the card goes away, or you honestly cannot tell from snap — say so in the notes. Do not retry a choice click unless the buttons are still there.

## Scenarios you will play (human steps only)

The agent runs scenarios in order and may take minutes between them. Keep polling `snap` (about every 2-5 s) between scenarios; the run is over when the agent posts a chat message containing `collab-test-done` (or the session stops). Give up after 20 minutes with no new control or message.

The agent starts each wait. You never start `ask-and-wait` or `chat-turn`.

1. **Choose.** When buttons `Alpha` and `Beta` appear, click `Alpha`.
2. **Chat hand-off.** Type `please-fill-email` in `Message`, click `Send`, then watch for an agent reply and/or the Email field being filled. Do not fill Email yourself in this scenario unless the agent's visible message asks you to.
3. **Highlight.** When asked (ask card or chat) to type in the outlined Email field, type `abc@example.com` into Email. Note whether you could detect the highlight from snap/find.
4. **File input.** If an Upload File control is present, try a human click only. Then, in page chat, tell the agent you cannot attach the file in human mode (for example `cannot-attach-please-upload-customers-yaml` and Send).
5. **Timeout.** For the first short-lived card (`Timeout-card` / `Later` / `Now`), do **not** click. Snap and watch it disappear. When a retry card (`Retry-pick` / `Alpha` / `Beta`) appears, click `Alpha`.
6. **Guide.** When a guide points at Email, type `abc` into Email and Tab or click away. Do not close the guide with `guide-done`.

If the session dies mid-run, snapshot `doctor`, write the notes you have, and stop.

## How to take notes

Append only to `/workspace/pickleball/collab-test/human-notes.md`. Create the file with the header below if it does not exist. Do not edit the agent's notes. Use America/Phoenix time (MST, UTC-7), for example `11:18:31.430 PM MST`.

```markdown
# Human notes — 2026-11-23

## Session
- session id:
- doctor line:
- headed (if doctor prints it):

## Action log
| t (MST) | scenario | verb | args | exit | envelope ms / workerMs | gap_ms | result |
|---|---|---|---|---|---|---|---|
| 11:18:31.430 PM MST | 1 | act | click Alpha | 0 | 667 / 577 |  | dispatched; applied=false; buttons gone on next snap? |

`gap_ms` is the wall-clock time from the end of your previous command.

## Bugs and issues
## Sluggish or unresponsive
## Attributes and tagging ideas
## Feature ideas
## Docs / instruction improvements
## Extra API / MCP calls you wished you had
## Verdict per scenario (your side)
- 1–6: pass / pass-with-gap / fail, one line each
```

Record:

- Every action and query, the exit code, and the envelope `ms` / `timing.workerMs` / `timing.totalMs` when present.
- Time gaps between your commands.
- Anything sluggish or unresponsive (include workerMs so JVM startup is not blamed).
- Controls you expected to see in snap/find but did not (pins, highlight, sending/delivered/read, `.pkb-value` chips, `data-pkb-purpose`).
- Attribute or tagging ideas that would make selection and filtering easier.
- Feature ideas, docs fixes, and extra API/MCP calls.
- Whether you were tempted to break a rule (and did not).

## What to report back when you finish

A short summary only, in your last message, plus the path to `human-notes.md`:

- Session id and whether doctor stayed healthy.
- Per-scenario verdict (1–6) from the human side.
- The three most important bugs or gaps.
- The three most useful API/docs ideas.
- Anything that felt slow (verb + workerMs).
- Confirmation that you never used control tools or out-of-band chat.

Do not tear down the browser. The agent runs `live-stop --all` when the run is over.
