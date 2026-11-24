# Helper brief — retest ROUND 2 (targeted, R1–R8 only)

You are the human side again. Same rules as `helper-brief.md` (human tier only, page UI is the only channel, never `--human-override`, no out-of-band messages). This round checks only the eight fixes in `round2-plan.md`; it is short (about 15 minutes).

- Notes: `/workspace/pickleball/collab-test/retest-2026-11-24/round2-human-notes.md` — one timestamped MST line per check: R#, what you did, what you saw, pass/fail.
- Host/page are already running. Do not start or stop anything.

```bash
export JAVA_HOME=/home/box/jdk/jdk-21.0.13+9 PATH="/home/box/jdk/jdk-21.0.13+9/bin:$PATH" DISPLAY=:34
H=/workspace/pickleball/collab-test/retest-2026-11-24/human.sh
$H doctor                     # expect health=ok headed=true page=attached session=SESSION_ID
$H session-mode --human=on    # R8: note whether any "SLF4J" line is printed (expect none)
$H snap --target=browser:auto
```

Send messages by typing in the panel and clicking Send:
`$H act --target=browser:auto --kind=type --purpose=message --query=Message --value=<text>` then `$H act --target=browser:auto --kind=click --purpose=send --query=Send`.

Steps (the agent leads; watch chat with `$H chat-read --last=5` or `$H wait --target=browser:auto --condition=new-message --timeout=120000`):

1. **R2** Send `ready`. Then `chat-read --last=5` until you see the agent's `r2-pong`; note `cursors.chat` in the reply.
2. **R1** On `r1-click-go`: click Go (`act --kind=click --query=Go`). The agent repeats this up to 3 times (wait, watch, browser-events); click Go each time it asks.
3. **R3** On `r3-minimize`: click `Minimize`. Then `snap` (note `panel=`), `find --query=Message` (note `visible`), try `act --kind=type --purpose=message --query=Message --value=x` (expect refused, reason hidden), then click `Open agent panel` (note `applied`) and `snap` again (expect `panel=open`).
4. **R4** On `r4-upload`: click `Upload File`, then click `Ask agent to attach a file` when it appears (note whether it appears). Then check the `files` line.
5. **R5** On `r5-guide`: click Go. Open the Guide tab (click `Guide`) and note its text (expect "Guide done: 1 of 1 steps completed.", not "No guide yet."). Click `Chat` to go back.
6. **R6** On `r6-reply-after-70s`: wait about 70 s (by the clock), then send `r6-reply`.
7. **R7** On `r7-highlight`: `snap --include=highlights`; note which field shows `highlight=yellow` (expect Email).
8. **R8** Answer the `New-card` question with either choice. Note any SLF4J line from any command you ran.

Stop when the agent posts `round2-done`, or after 20 minutes with nothing new. Then report back to the parent by message: verdict per R1–R8 and the notes path.
