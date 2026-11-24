# Retest round 2 (targeted) — R1–R8 fixes only

Scope: only the eight fixes from PR "3.0.0-retest-fixes" (roadmap `docs/roadmaps/3.0.0-retest-fixes-roadmap.md`). Dan's rule: the full-functionality retest (plan.md, 6 scenarios + B1–B9 table) runs again **only after every check here passes**.

- Same host/page and wrappers as round 1: page `http://127.0.0.1:8766/retest.html`, agent `./agent.sh` (`--run-id=agent`), helper `./human.sh` (`--run-id=human`, human-locked).
- Notes: agent `round2-agent-notes.md`, helper `round2-human-notes.md` (timestamped MST, one line per check: verdict pass/fail, latency, the JSON field that proves it).
- Order: R2 first (it is the handshake: helper sends `ready` in the panel, agent sees it with `wait --condition=new-message`), then R1, R3–R8. Agent posts `round2-done` in chat at the end.
- Verdicts: pass / fail / blocked. A fail notes the exact command and reply excerpt. No code fixes during the test.

| # | Fix | Agent side (A) | Helper side (H) | Pass when |
|---|-----|----------------|-----------------|-----------|
| R2 | One chat log per session | `wait --condition=new-message --timeout=120000`, then `chat-read --last=5`; reply `r2-pong` with `chat-send` | Send `ready` in the panel; then `chat-read --last=5` | A wakes `wake=push` with `ready`; H's chat-read lists `r2-pong`; `cursors.chat` non-blank for both |
| R1 | DOM push waits | Start `wait --until-change --selector=#status --timeout=60000`, chat `r1-click-go` | Click Go | A wakes in ~1 s, `wake=push`; repeat with `watch --selector=#status --until=change --duration=60000` and `browser-events --dom` (dom rows > 0) |
| R3 | Minimize / reopen | chat `r3-minimize` | Click Minimize; `snap` → `panel=collapsed`; `find --query=Message` → `visible=false`; `act --kind=type --purpose=message --query=Message --value=x` → refused, reason hidden; click `Open agent panel` | reopen `applied=true`, then `panel=open`, Message `visible=true` |
| R4 | Human upload offer | `ui-poll --wait=60` for `upload-request`; then `upload --query=Upload --file=customers.yaml` | Click Upload File, then `Ask agent to attach a file` | offer appears; A sees `upload-request` with `#upload-file`; `files` line shows customers.yaml |
| R5 | Guide attribution + done state | `guide --steps=@ops/round2-guide.json --id=g2` (one `#go` click step), `ui-poll --wait=60` | Click Go; open Guide tab | step event `by=human`, `run=human`; Guide pane reads "Guide done: 1 of 1 steps completed." (not "No guide yet.") |
| R6 | 120 s waits | `chat-turn --text=r6-reply-after-70s --timeout=110s`; `ui-poll --wait=30` (idle); `watch --selector=#nothing --until=change --duration=90000` (idle) | Wait ~70 s after `r6-reply-after-70s`, then send `r6-reply` | chat-turn returns the reply (~70 s, no `timed out waiting for the live worker`); ui-poll returns ≈30 s; watch times out ≈90 s with the worker's own timeout and a watch-specific fix |
| R7 | Word matching, highlight --query | `find --query=Upload`, `--query=Upload-File` (→ Upload File), `--query=Uplo` (not found); `highlight --query=Email` | `snap --include=highlights` | Upload/Upload-File resolve; Uplo does not; H sees `highlight=yellow` on Email |
| R8 | Minors | `ask-and-wait --say=Old-card --id=t2 --async`, re-ask `--say=New-card --id=t2 --async`, `snap` → `recent[]`; `doctor` → `orphans=none`; `browser-events` after a panel drag/minimize → no `pkb-*` storage rows, no panel form issues; `help wait` mentions CSS-only `--selector` | `session-mode` output; answer `New-card` | `recent[]` shows New-card only; orphans=none; no SLF4J lines on any CLI output; no own storage/issue rows |

Report: `round2-findings.md` — table above with verdicts, plus anything new.
