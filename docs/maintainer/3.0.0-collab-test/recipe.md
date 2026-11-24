# Two-actor collaboration recipe (durable)

This is the maintained version of the 2026-11-23 two-bot run (`plan.md`, `findings-2026-11-23.md`). The consumer-facing steps are in `docs/collaborative-self-test.md` under "Two-actor recipe". This page covers what maintainers need on top of them.

- **Roles.** The helper runs under run id `helper`, made human-tier by `session-mode --human=on` (D4). The agent runs under run id `agent` with control verbs and non-blocking waits (C1). Both run ids reach the same `start` host through `.pickleball/consumer-session.json` in the shared project directory.
- **Start order.** The agent runs `start`, then `doctor` until `health=ok`. Only then does the helper lock its run id and run `snap`. Each side keeps its own notes file and never blocks on the other's. This fixes both the start race and the notes-file deadlock.
- **Scripted run.** `TwoActorRecipeLiveTest` runs these scenarios end to end on headless Chrome:
  - Choose card: `--async` ask, the helper clicks, `answer-wait`.
  - Chat hand-off: `chat-turn`. The helper reads the agent's line through `snap`, types in the page and the composer, and clicks Send.
  - Timeout and retry: a 300 ms `answer-wait` times out, the agent re-asks under the same id, and the second wait gets the answer.
  
  It also checks that a control verb from the helper is refused. It prints the per-verb wall times, which take 5–700 ms when warm.
- **Found while scripting.** The C9 toolbar dock sat on top of the open panel's Send button, and a human-exposure click on it was refused as `covered`. `dockPlace` now moves the dock off the open panel and step-editor cards.
- **Manual scenarios.** Highlight and type, file hand-off, and guide still need the manual run with `plan.md` and a person or a second bot. Use the cadence, timings, and pass criteria in the consumer recipe.
