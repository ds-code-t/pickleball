# Round 2 findings (targeted R1–R8) — 2026-11-24, 10:41–10:57 AM MST

Session livef54ef76af140, code = 3.0.0 @ b65a716b (PR #42). Agent notes: `round2-agent-notes.md`; helper notes: `round2-human-notes.md`. Helper was ready at 10:41:34 (agent `wait --condition=new-message` woke push in 5.3 s).

| # | Fix | Verdict | Evidence |
|---|-----|---------|----------|
| R1 | DOM push waits | **pass** | `wait --until-change --selector=#status` wake=push, pushLagMs 51; `watch --selector=#status --until=change` met, mode=push; `browser-events --dom` (via=cdp) recorded the click's kind=dom mutation row |
| R2 | One chat log per session | **pass** | agent saw "ready"; helper's chat-read listed r2-pong; cursors.chat non-blank on both sides |
| R3 | Minimize / reopen | **pass** (minors) | panel=collapsed; hidden composer act refused "hidden: …"; `Open agent panel` applied=true → panel=open, Message visible |
| R4 | Human upload offer | **pass** | Upload File click offered "Ask agent to attach a file"; ui-poll upload-request #upload-file; `upload --query=Upload` attached customers.yaml (90 ms) |
| R5 | Guide attribution + done | **pass** (on retry) | step/guide/click events by=human run=human; Guide pane "Guide done: 1 of 1 steps completed." First try was an agent error (relative `@ops/…` path) |
| R6 | 120 s waits | **pass** | chat-turn --timeout=110s returned the reply at 68.8 s; ui-poll --wait=30 held 30.9 s; watch --duration=90000 hit the worker's own timeout at 90.07 s |
| R7 | Word matching, highlight --query | **partial** | Upload-File → Upload File, Uplo not found, highlight --query=Email painted (helper sees yellow). **`--query=Upload` now returns the panel chat bubble "…r4-upload"** (NEW-3) |
| R8 | Minors | **mostly pass; orphans false positive** | recent[] shows New-card only; no SLF4J lines anywhere; no pkb-* storage rows or FormEmptyIdAndName issues; help wait says CSS-only; wait new-message works; **doctor orphans= lists the browser-events protocol host JVM** (NEW-4) |

## New issues (for a follow-up fix round)

1. **NEW-3 (R7, real):** `find/upload --query=Upload` matches pickleball's own chat bubble text ("Agent · 10:47 AM r4-upload") before the page's Upload File control. Pre-existing strict suffix rule (`labelMatches` accepts "…-upload") plus chat bubbles being searchable. Fix: rank page controls above pkb panel/message text; leave chat bubbles out of `--query` unless `--own`/`--purpose`. Add a live test with a chat message containing the query word.
2. **NEW-4 (R8):** doctor `orphans=` flags the live `browser-events` protocol host (java process whose cmdline mentions chrome and the project). Only Chrome browser processes should count; `stop`'s killOrphans could kill a live host.
3. **NEW-1 (helper):** human-side `wait --condition=new-message` cannot wait for agent messages (default human, `any` = non-agent). Add `--from=agent` so the person's side has a push wait for chat; the helper polled and was ~2 min late on R1.
4. **NEW-2:** `guide --steps=@relative.json` resolves against the consumer project, and a missing file replies only `error: <absolute path>`. Say "steps file not found: <path> (relative paths are from the project)".
5. Minor: in human exposure, `find` on a collapsed-panel control says "not found" (hidden rows dropped) instead of a row with visible=false; collapsed snap still lists chat message rows; the `Open agent panel` row reads visible=true while the panel is open.
6. Minor: a human click on the Upload File input reports applied=false "no visible change" although it brings up the attach offer.
7. Minor: a worker-side watch/wait timeout's `fix` is the full usage text; a one-line hint would read better. `browser-events --start --dom` alone shows `events=off` in `--status` until a plain `--start`.
8. Not a bug: the page's "Went:" line is `'Went: ' + email`, empty because Email was blank.

Next per Dan's process: fix NEW-3 and NEW-4 (and optionally NEW-1/NEW-2), then a short targeted recheck of R7/R8 before the full-functionality retest.
