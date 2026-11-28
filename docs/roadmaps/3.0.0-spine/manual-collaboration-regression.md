# PKB-HCR — pickleball Human Collaboration Regression

Tag: **PKB-HCR** (pickleball Human Collaboration Regression). This is the third regression tier, next to the full and short tiers (Dan, 2026-11-27). It is a catalog of 75 instructions (stable ids **HCR-01** through **HCR-75**; older `M-*` ids remain as aliases) that an AI agent carries out by hand on a live, headed Chrome page. Some tests have the agent act as the consumer project's agent with every tool. Others have it play the human collaborator, limited to Human Mode tools and the human (composite) view.

## How to invoke it

Any agent should recognize these phrases:

- `Run PKB-HCR` — run the whole catalog (HCR-01 through HCR-75), after Setup S0 and M0.
- `Run PKB-HCR HCR-12` — run one test. Any id `HCR-01`..`HCR-75` works, and so does the older alias (for example `Run PKB-HCR M-CA-01` is HCR-14).
- `Run PKB-HCR paired only` — run only the paired tests, whose roles are `A→H`, `H→A`, `A→H→A`, `H→A→H`, or `A, H` / `A, H check`. Skip A-only and H-only tests.

## When it may run

- **The whole catalog:** only when Dan explicitly asks for the manual collaboration regression. Finishing the roadmaps does not trigger it. A full-tier or short-tier pass does not trigger it either.
- **One test or a few tests:** may run without asking, when they cover functionality that a new implementation may have impacted. Pick them with the "Covers" line on each test and the [Targeted selection](#targeted-selection) table. Record why each one was picked.
- **Failures:** the batching rule in [Regression tiers](final-regression.md#failures-batch-the-fixes-dan-2026-11-27) applies. Let the run finish and collect every failure. Fix them together, each at the root with a reproducing automated test where one can exist. Run the targeted tests for the whole batch together. Then re-run only the manual tests that failed. Do not re-run the whole catalog unless Dan asks.
- **Platform:** Dan's Windows PC with Google Chrome, as in the short tier. No GitHub Actions. Linux and macOS desktop tests (M-PP-03) belong to a full-tier request.
- **Results:** copy the PKB-HCR scoreboard template to a dated results file and fill it in. In this repository the template is `docs/maintainer/3.0.0-spine/manual-collaboration-scoreboard-template.md` and the usual results path is `docs/maintainer/3.0.0-spine/manual-collaboration-<yyyy-mm-dd>.md`. In a consumer project, copy `.pickleball/PKB-HCR-scoreboard.md` (see below). Times are MST (America/Phoenix).

## For a consumer project's agent

This catalog ships in the published Pickleball jar, so a consumer agent does not need this source repository.

1. **Where to find it.** After a successful `export-guidance`, the catalog is `.pickleball/PKB-HCR.md` and the scoreboard template is `.pickleball/PKB-HCR-scoreboard.md`. Both are listed in `.pickleball/index.txt`. Inside the jar they are `META-INF/pickleball/guidance/PKB-HCR.md` and `META-INF/pickleball/guidance/PKB-HCR-scoreboard.md`.
2. **What to load first.** Follow the consumer project's `AGENTS.md`: run `export-guidance`, then read `.pickleball/AGENT-GUIDE.md`. Then load `.pickleball/PKB-HCR.md` and stay on this file for setup, roles, and the tests.
3. **Roles.** When the test role is **H** (human collaborator), the agent is limited to Human Mode tools and the human (composite) views: `snap` and `find` at `exposure=human`, plus `aria-snap`, `screenshot`, and `capture`. When the role is **A** (agent), it may use every tool, including control-tier verbs. The Roles table below is the contract. Keep H and A notes separate. An H assertion may use only H output.
4. **How to record results.** Copy `.pickleball/PKB-HCR-scoreboard.md` to a dated file the owner names (for example `pkb-hcr-<yyyy-mm-dd>.md` in the consumer project) and fill every HCR id that was in scope.
5. **Explicit request only.** The whole catalog runs only when the project owner explicitly asks, for example `Run PKB-HCR`. Do not start it unprompted. A single test such as `Run PKB-HCR HCR-12` may run when the request names it, or when a new implementation may have impacted what that test covers.

## Roles

| Role | Run id | Tools | Must not |
|---|---|---|---|
| **H** (human collaborator) | `helper`, locked with `session-mode --human=on --run-id=helper` before its first page action | The human tier in `VerbTiers`: `snap`, `find`, `act` (`--kind=click`, `type`, `press`, `hover`, `select`), `aria-snap`, `screenshot`, `capture`, `explain`, `wait`, `wait-for`, `input` (key, pointer, wheel, and pause actions at viewport coordinates only), `chat-read`, `doctor`, `help`. The composite views are the `snap` and `find` rows under `exposure=human` (the default in a locked session), including the collaboration fields (`value`, `checked`, `purpose`, `badge`, `highlight`, `guide`, `card`, `msg`), plus `aria-snap` and the PNGs from `screenshot` and `capture`. | Pass `--exposure=full`, `full-access`, or `lower`. Use `--human-override`. Run any control-tier verb except where a step expects the refusal. Read `.pickleball/` files, `agent-full/` dumps, logs, page source, or the agent's notes. Use anything the agent printed. H learns things only from the page, through H tools. |
| **A** (agent) | `agent` | Every verb, including control-tier verbs: consumer direct control, HTML injection, overlays, listeners, CDP, BiDi, Playwright, and the network and DevTools verbs. | Answer its own wait. The actor that starts a wait (`answer-wait`, `chat-turn`, `ui-poll --wait`, `wait-any`) is never the one that answers it. |

Run H and A as two separate agents when possible, like the 2026-11-23 two-bot run. If one agent plays both, it must keep two notes files. An H assertion may use only H output, even when the agent already knows the answer from an A step.

Paired tests are written **A→H** (A acts, H verifies with H tools) or **H→A** (H acts as the person, A verifies with agent tools). **A** alone means A both acts and verifies. In that case, the "H check" line adds what a person would see, wherever the effect is visible on the page.

Pass criteria come from the reply fields, events, exit codes, and page text named in each test, not from `applied`. A click on a card may report `applied:false` with `no visible change` while the answer still arrives.

## Conventions

- `pkb <verb> ...` is the consumer launcher, run from `maven-consumer-project` (see Setup S0, step 3).
- Every A command ends with `--run-id=agent` and every H command ends with `--run-id=helper`. That flag is left out below.
- `snap`, `find`, `act`, `wait`, `wait-for`, `explain`, `capture`, `eval`, `form-fill`, and `watch` take `--target=browser:auto`. It is left out below unless the test uses another target.
- With the Maven launcher form, keep flag values free of spaces. With the `java -cp` form in PowerShell, put a flag that holds spaces or `| { } < > ;` in single quotes (`'--fields=pw=secret1|email=a@b.c'`). Put JSON in a file under `C:\pkb-manual\` and pass `@file` or `--args-file` where the verb accepts it. Otherwise use a hyphenated word (`--say=Pick-one`).
- Every step records the wall-clock time (MST), the exit code, and the envelope `ms` or `timing.workerMs`. A verb that takes more than 2 s while the other side waits is a lane problem: record it as a failure.
- **Unclear** marks a capability whose name or behavior the branch documents but does not make clear. Run it, record what happened, and list it under Gaps on the scoreboard. It is not a pass or a fail.

## Setup

### S0 — once per run

1. Use the `3.0.0-spine` worktree at the SHA being tested. Run `.\gradlew.bat publishToMavenLocal`, then `.\maven-consumer-project\mvnw.cmd -f maven-consumer-project\pom.xml -U -q test-compile`.
2. Set `$env:PKB_AGENT_POPUP_BROWSER = 'C:\Program Files\Google\Chrome\Application\chrome.exe'` in every shell, so the popup host uses Chrome.
3. Define `pkb`. The documented launcher is `.\mvnw.cmd -q org.codehaus.mojo:exec-maven-plugin:3.5.0:java "-Dexec.mainClass=tools.dscode.launcher.PickleballWorkbenchLauncher" "-Dexec.classpathScope=test" "-Dexec.args=<verb and flags>"` (`docs/collaborative-self-test.md`). resolved: `start` uses `java.class.path` only when that path already contains `LiveBrowserHost`. A classworlds-only path is not used. The next choice is the versioned or `.pickleball/workbench/classpath.cache` when that file contains the class, then the classes that loaded the launcher. When none of those contain it, `start` fails and does not spawn. The 2026-11-23 `ClassNotFoundException: tools.dscode.common.agentfast.LiveBrowserHost` was the old path. Use the documented `exec:java` form.
4. Create `C:\pkb-manual\site`. Copy `maven-consumer-project\src\test\resources\site\*` into it, and add the M0 files below. Serve it in its own shell with `python -m http.server 8765 --bind 127.0.0.1 --directory C:\pkb-manual\site`. Port 8765 makes the consumer `URL.*` keys in `configs/URL.yaml` resolve (`URL.components`, `URL.loading`, `URL.dialogs`). The manual page is `http://127.0.0.1:8765/manual.html`.
5. A: `pkb controller-start`, then `pkb start --url=http://127.0.0.1:8765/manual.html --browser=chrome --headed=true --panel --hold=forever --size=1400x900`. Pass: exit 0, `sentence` `live browser ready, panel open`, `headed=true`. Then run `pkb doctor` until `health=ok` and `chrome=present`.
6. H: only after step 5, run `pkb session-mode --human=on`, then a first `pkb snap`. The reply must not contain `warning: the human lock applies to the default run id`.
7. Notes: A appends to `C:\pkb-manual\agent-notes.md` and H to `C:\pkb-manual\helper-notes.md`. Neither side waits on the other's file.

Teardown: A runs `pkb live-stop --all`, then `pkb controller-stop`, and stops the HTTP server. Afterwards, `pkb doctor` must show no session and no `orphans=`.

### M0 — fixture files

`manual.html`:

```html
<!DOCTYPE html><html><head><title>Manual</title><script src="manual.js" defer></script></head><body>
<h1>Manual collaboration page</h1>
<label for="email">Email</label><input id="email" name="email">
<label for="pw">Password</label><input id="pw" type="password">
<label><input id="agree" type="checkbox"> Agree</label>
<label for="size">Size</label><select id="size"><option>Small</option><option>Large</option></select>
<button id="go">Go</button> <p id="went">Went: none</p>
<button id="add">Add row</button><div id="rows"></div>
<button id="load">Load data</button> <p id="data">Data: none</p>
<button id="note-save">Save note</button> <p id="note">Note: none</p>
<a id="dl" href="data.txt" download>Download data</a>
<button id="alert">Alert</button> <button id="ask">Prompt</button> <p id="dialog">Dialog: none</p>
<button id="boom">Throw</button> <button id="log">Log</button>
<button id="where">Where am I</button> <p id="geo">Geo: none</p>
<p id="now">Now: none</p> <p id="scheme">Scheme: light</p>
<label for="files">Attachments</label><input id="files" type="file" multiple> <p id="picked">Picked: none</p>
<div style="position:relative"><button id="covered">Covered</button>
<div style="position:absolute;inset:0;background:#fff;opacity:1"></div></div>
<button id="hidden" style="display:none">Hidden</button>
</body></html>
```

`manual.js`. The line numbers matter for M-LS-06:

```js
function boom() {
  throw new Error('manual-boom');
}
function logLine(n) {
  console.log('manual-log ' + n);
}
document.addEventListener('DOMContentLoaded', () => {
  const $ = (id) => document.getElementById(id);
  $('go').onclick = () => { $('went').textContent = 'Went: ' + $('email').value; };
  $('add').onclick = () => setTimeout(() => { const b = document.createElement('button'); b.textContent = 'New row'; $('rows').appendChild(b); }, 500);
  $('load').onclick = () => fetch('data.json').then(r => r.text()).then(t => { $('data').textContent = 'Data: ' + t; }).catch(e => { $('data').textContent = 'Data: failed'; });
  $('note-save').onclick = () => { localStorage.setItem('note', $('email').value); $('note').textContent = 'Note: ' + localStorage.getItem('note'); };
  $('note').textContent = 'Note: ' + (localStorage.getItem('note') || 'none');
  $('alert').onclick = () => { alert('manual-alert'); $('dialog').textContent = 'Dialog: alert closed'; };
  $('ask').onclick = () => { $('dialog').textContent = 'Dialog: ' + prompt('Name?'); };
  $('boom').onclick = () => boom();
  $('log').onclick = () => logLine(1);
  $('where').onclick = () => navigator.geolocation.getCurrentPosition(p => { $('geo').textContent = 'Geo: ' + p.coords.latitude.toFixed(1) + ',' + p.coords.longitude.toFixed(1); }, e => { $('geo').textContent = 'Geo: denied'; });
  setInterval(() => { $('now').textContent = 'Now: ' + new Date().toISOString().slice(0, 10); }, 250);
  const dark = matchMedia('(prefers-color-scheme: dark)');
  const scheme = () => { $('scheme').textContent = 'Scheme: ' + (dark.matches ? 'dark' : 'light'); };
  dark.addEventListener('change', scheme); scheme();
  $('files').onchange = () => { $('picked').textContent = 'Picked: ' + [...$('files').files].map(f => f.name).join(','); };
  if ('serviceWorker' in navigator) navigator.serviceWorker.register('sw.js');
});
```

Also write `data.json` (`{"v":"real"}`), `data.txt` (`manual download`), `sw.js` (`self.addEventListener('fetch', () => {});`), `C:\pkb-manual\a.txt` and `C:\pkb-manual\b.txt` (one line each), and `C:\pkb-manual\guide-steps.json`:

```json
[{"t":"#email","say":"Enter \"abc\" in this textbox","do":"input","expect":"abc"},{"t":"#go","say":"Now click Go","do":"click"}]
```

## Catalog

Stable ids are **HCR-01** through **HCR-75**, in the order below. Each heading also keeps its older `M-*` alias.

Areas: HL human lock and tiers, HV human view, CH chat, CA question cards and waits, GD guides, VS visual surfaces, HI HTML injection, PT panel and toolbar, RC recording and step editor, UP file hand-off, NV navigation and re-injection, LS listeners and push, DT DevTools and protocol verbs, PP popup, native highlight and desktop, LK spine session, lease and inject, MC MCP forwarding.

### HL — human lock and tiers

**HCR-01 (M-HL-01 Control verbs are refused in the human lock)** — Role: H. Covers: `VerbTiers`, `HumanLock.check`, `WorkbenchAgentCommands.run`, `AgentControl.execute`, help tier lines.
- Steps (H): `pkb eval --expr=1`. Then `pkb highlight --query=Email --preset=yellow`. Then `pkb upload --query=Attachments --file=C:\pkb-manual\a.txt`. Then `pkb act --kind=attach --query=Attachments --value=C:\pkb-manual\a.txt`. Then `pkb help eval` and `pkb help snap`.
- Assert: each of the first three exits 2 with `error: <verb> is a control-tier verb and this session is human-locked` and a `fix:` line naming `session-mode --human=off`. `upload` also says `File attachment and OS-level actions are unavailable in human mode`. `act --kind=attach` exits 2 with `act --kind=attach is not human input`. `help eval` prints `Tier: control.` and `help snap` prints `Tier: human.`
- A check: `pkb highlight --query=Email --preset=yellow` under `agent` exits 0, so the lock is per run id. Remove it with `pkb unhighlight`.

**HCR-02 (M-HL-02 Override is for the owner only)** — Role: A. Covers: `HumanLock` owner and override log.
- Steps: `pkb session-mode --human=on --run-id=owned --agent=lead`. Then `pkb highlight --query=Go --preset=yellow --run-id=owned --agent=other --human-override=demo`. Then the same command with `--agent=lead`. Then `pkb session-mode --human=off --run-id=owned`.
- Assert: the first call exits 2 with `--human-override is only for the session owner (lead)`. The second exits 0, and the composite log has a `human-lock override owned highlight reason=demo` line. `session-mode` with no `--human` prints the mode. resolved: the owner is `--agent` (the same value as `--agent-id`). Only that owner may pass `--human-override=<reason>`.

**HCR-03 (M-HL-03 Human input forms)** — Role: H. Covers: `VerbTiers.HUMAN_ACT_KINDS`, `HumanLock.inputIsHuman`, `TargetVerb`, `ProtoVerbs` input.
- Steps (H): `pkb act --kind=type --query=Email --value=h1@example.com`, then `act --kind=select --query=Size --value=Large`, `act --kind=hover --query=Go`, `act --kind=press --query=Email --value=Tab`, and `act --kind=click --query=Agree`. Then `pkb input --actions=@C:\pkb-manual\keys.json`, where the file holds a key chain that types `x` into the focused field. Then `pkb input --actions=@C:\pkb-manual\element.json`, where a pointer move has `origin` set to an element object. Then `pkb act --kind=scroll --query=Go`.
- Assert: the five `act` calls exit 0. `snap --include=values` then shows `value=h1@example.com`, `Size` showing `Large`, and `checked=true` on Agree. The coordinate chain exits 0. The element-anchored chain exits 2 with `not human input`. `pkb act --kind=scroll --query=Go` exits 0, `applied` is true, and the reply reports `dx`, `dy`, and `scrollX`/`scrollY`; the page or element actually moves. A human-locked session allows the kind. Desktop scroll is not part of this test.

**HCR-04 (M-HL-04 start --human locks a new session)** — Role: A, then H. Covers: `start --human`, `humanRequested`. Run this after teardown, or in a second scratch project, never beside the main `start`.
- Steps: `pkb start --url=fixture:form --browser=chrome --headed=true --human --run-id=solo`, then `pkb eval --expr=1 --run-id=solo`, then `pkb live-stop --all`.
- Assert: `start` prints `human=on`, and `eval` exits 2 with the control-tier refusal.

### HV — human view and composite views

**HCR-05 (M-HV-01 A covered or hidden control is not human input (A→H))** — Covers: `ViewMask`, `Perception`, `exposure=human`, `act` hit-test, `explain`.
- Steps (H): `pkb find --query=Covered`, then `pkb act --kind=click --query=Covered`, `pkb explain --query=Covered`, and `pkb find --query=Hidden`.
- Assert (H): the click is reported `covered` and is not clicked through. `explain` says the element is not visible. `Hidden` is not found or is listed with `visible=false`.
- Assert (A): `pkb find --query=Hidden --exposure=full` lists the button. `pkb eval --expr=document.activeElement.id` does not return `covered`.

**HCR-06 (M-HV-02 Collaboration fields in the human view (A→H))** — Covers: `ElementRecord`, snap `--include`, redaction, `data-pkb-*` attributes.
- Steps (A): `pkb form-fill --fields=pw=secret1|email=a2@example.com`, `pkb highlight --query=Go --preset=yellow`, `pkb ask --say=Pick-one --choices=Alpha,Beta --id=q-hv`, and `pkb chat-send --text=hello-hv`.
- Assert (H): `pkb snap --include=values,highlights,cards,chat` shows `value=redacted` on Password, `value=a2@example.com`, `highlight=yellow` on Go, `card=q-hv "Pick-one" open` on the Alpha and Beta rows, and a `message` row `msg=<id> agent <status>` for `hello-hv`. `--include=values` alone drops the other keys.
- Cleanup (A): `pkb unhighlight`, and close the card with `pkb ask-answer --id=q-hv --value=Alpha`.

**HCR-07 (M-HV-03 Aria view and aria refs (H))** — Covers: `aria-snap`, `act --aria-ref`, Playwright on the start session.
- Steps (H): `pkb aria-snap --mode=ai`, then `pkb act --aria-ref=<eN of Go> --kind=click`.
- Assert: YAML of roles and names with `[ref=eN]`. After the click, `pkb find --query=Went` shows the `Went:` text with the current Email value. If the reply says Playwright is not on the session, record that as a gap: the human role then has no aria view.

**HCR-08 (M-HV-04 Screenshot and capture (H))** — Covers: `screenshot`, `capture`.
- Steps (H): `pkb screenshot --out=C:\pkb-manual\h-shot.png`, then `pkb capture --target=browser:auto --out=C:\pkb-manual\h-go.png`. resolved: `--query` is not a capture flag and does not crop. The file is the whole page, or the desktop window PNG when `--target` is desktop. An element crop is `pw screenshot --out=<file> --selector=<selector>`.
- Assert: both files exist and open. H looks at the PNGs as a person would, and they show the page and the agent panel.

**HCR-09 (M-HV-05 Name ranking and pkb surfaces (A→H))** — Covers: `find` exact-name ranking, `--purpose`, page control above pkb chat text (NEW-3 fix).
- Steps (A): `pkb panel-open --tab=chat`, then `pkb chat-send --text=please-press-attachments-upload`.
- Assert (H): `pkb find --query=Chat` is the Chat tab, not "Pop out chat". `pkb find --purpose=chat-tab` returns one row. `pkb find --query=Attachments` is the page file input, not the chat bubble.

**HCR-10 (M-HV-06 Snapshot deltas (A→H))** — Covers: `snap --since`, push delta, `mode=diff`.
- Steps: H runs `pkb snap` and keeps `snapCursor`. A runs `pkb html-insert --handle=go --text=<b>Inserted</b> --where=after`. H runs `pkb snap --since=<snapCursor>`.
- Assert (H): the reply has `delta` with `mode=push` (or `mode=poll`, recorded) and a non-zero `mutations` count.

### CH — chat

**HCR-11 (M-CH-01 Agent asks, person answers in the panel (A→H→A))** — Covers: `chat-turn`, panel composer, read state, `wait --condition=new-message --from=agent`, push wake.
- Steps: A runs `pkb panel-open --tab=chat`, then starts `pkb chat-turn --text=Type-your-email-then-say-done --timeout=60s`. H runs `pkb wait --condition=new-message --from=agent --timeout=30000`, then `pkb chat-read --last=3`. Then `pkb act --kind=type --query=Email --value=ch1@example.com`, `pkb act --kind=type --purpose=message --query=Message --value=done`, and `pkb act --kind=click --purpose=send --query=Send`.
- Assert: H's wait returns with the agent line. `chat-read` lists it. `chat-turn` returns `done` once, with `wake=push`. H's `snap --include=chat` shows the H message moving to `read`. A runs `pkb chat-poll --wait=2` and does not see `done` again.

**HCR-12 (M-CH-02 Person writes first; listed once (H→A))** — Covers: chat cursors, `chat_wait --from=human`, `chat-history`, `wait-any --chat=human`.
- Steps: A starts `pkb wait-any --chat=human --timeout=60s`. H types `hello-from-person` in the composer and presses Enter (`pkb act --kind=press --purpose=message --query=Message --value=Enter`). A then runs `pkb chat-poll --wait=2`, `pkb chat_wait --from=human --timeout=2`, and `pkb chat-history --limit=10`.
- Assert: `wait-any` reports `fired=chat`. Enter sends the message, and the bubble shows `sending` and then `delivered` in H's `snap --include=chat`. The later poll and wait do not return the same message again. `chat-history` lists it.

**HCR-13 (M-CH-03 Chat pop-out keeps read marks (A, H check))** — Covers: `record --popout/--popin --panel=chat`, `ChatLog`.
- Steps (A): leave one agent message unread. Run `pkb record --popout --panel=chat`, then `pkb record --popin --panel=chat`.
- Assert (A): `where=window` and `popped out`, or `popup blocked` with the panel left on the page. Either is valid. Assert (H): `pkb chat-read --last=5` still shows the person's message as read and the agent message as unread.

### CA — question cards and waits

**HCR-14 (M-CA-01 Choose card (A→H))** — the 2026-11-23 scenario 1 and part of `TwoActorRecipeLiveTest`. Covers: `ask-and-wait --async`, `answer-wait`, card rendering.
- Steps: A runs `pkb ask-and-wait --say=Pick-one --choices=Alpha,Beta --async` and keeps `id`. H runs `pkb snap`, then `pkb act --kind=click --query=Alpha`. A runs `pkb answer-wait --id=<id> --timeout=10s`.
- Assert: the value is `Alpha` with no added quotes. H then sees `card=<id> ... answered` in `snap --include=cards`, and the card shows `Answer sent: Alpha` for about 1.5 s (in H's screenshot).

**HCR-15 (M-CA-02 Timeout and retry under the same id (A→H))** — scenario 5. Covers: `answer-wait` timeout, card close, re-ask.
- Steps: A runs `pkb ask-and-wait --say=Ready --choices=Yes,No --async`, then `pkb answer-wait --id=<id> --timeout=300`. Then A runs `pkb ask-and-wait --say=Ready --choices=Yes,No --async --id=<id>`. H clicks `Yes`. A runs `pkb answer-wait --id=<id> --timeout=10s`.
- Assert: the first wait exits 1 with `timeout`, and H sees the card `timed-out` or gone. The second wait returns `Yes`.

**HCR-16 (M-CA-03 Text, confirm, and sensitive cards (A→H))** — Covers: `ask --type=text|confirm`, `--sensitive=true`, `ui-poll --id`.
- Steps: A runs `pkb ask --type=text --prompt=Your-code --id=q-text --sensitive=true`. H types `1234` in the card field (`pkb act --kind=type --query=Your-code --value=1234`) and clicks the card's submit button (found with `pkb find --purpose=card-option`). A runs `pkb ui-poll --id=q-text --wait=30`. Repeat with `--type=confirm --prompt=Proceed --id=q-conf`, and H clicks the confirm choice.
- Assert: A gets an answer event for each card. The sensitive value is not in `overlay-poll` or `ui-poll` output, the event says it was answered, and H's `snap` shows `value=redacted` or nothing for that field. resolved: a text card has a field named `pkb-answer` and a button labeled Send. Send, or Enter in the field, submits when the trimmed text is not blank. An empty field does not submit. The event is `type=answer`.

**HCR-17 (M-CA-04 wait-any watch (A→H))** — Covers: `wait-any --watch=<short id>:click`.
- Steps: H runs `pkb find --query=Go` and passes the short id to A out of band. This is the only out-of-band hand-off, standing in for "the agent points at Go". A starts `pkb wait-any --watch=<id>:click --chat=human --timeout=60s`. H clicks Go.
- Assert: `fired=watch`. Clicking another control does not fire it.

**HCR-18 (M-CA-05 Agent answers a card (A, H check))** — Covers: `ask-answer`.
- Steps: A runs `pkb ask --say=Agent-answers --choices=One,Two --id=q-aa`, then `pkb ask-answer --id=q-aa --value=Two`.
- Assert (H): `snap --include=cards` shows the card answered or closed, and its buttons are gone.

**HCR-19 (M-CA-06 Quoted value chip (A→H))** — Covers: `.pkb-value` chip rendering.
- Steps: A writes `C:\pkb-manual\say.txt` containing `Enter "abc" in Email`, then `pkb ask --say=@C:\pkb-manual\say.txt --id=q-chip`. resolved: `ask --say=@file` and `ask --prompt=@file` read that file. A path is relative to the project, or absolute. A missing file is `error:` and `fix:` and does not ask for a live page.
- Assert (H): the screenshot shows `abc` as a chip, and the quote characters are kept in the card text.

### GD — guides

**HCR-20 (M-GD-01 Person follows the guide (A→H→A))** — scenario 6. Covers: `guide`, Guide pane, `data-pkb-guide-step`, step events `by=human`.
- Steps: A runs `pkb panel-open --tab=chat`, then `pkb guide --steps=@C:\pkb-manual\guide-steps.json --id=g1`. H runs `pkb snap --include=guide`, types `abc` into Email, presses Enter, and clicks Go. A runs `pkb ui-poll --wait=30` until both steps are reported.
- Assert: H sees `guide=g1 step 1/2 ...` on Email, then on Go. Pausing between keystrokes does not end the step. A gets `step` events with `how=input` and `how=click` and `by=human`. The Guide pane shows `Guide done: 2 of 2 steps completed.` Field badges are gone (H `snap`).

**HCR-21 (M-GD-02 Agent moves the guide (A, H check))** — Covers: `guide-next`, `guide-back`, `guide-done`, Guide tab badge.
- Steps (A): start `g2` with the same steps. Run `pkb panel-open --tab=chat`, then `pkb guide-next --id=g2`, `pkb guide-back --id=g2`, and `pkb guide-done --id=g2`.
- Assert: events say `by=agent`. H sees the Guide tab `badge=` count while the Chat tab is showing, sees the step row move, and then sees no `guide=` rows. A second `guide-done` returns closed and pushes no second event.

**HCR-22 (M-GD-03 Guide survives reload (A, H check))** — Covers: `UiState` re-inject, `pkb-surface` preload.
- Steps (A): start `g3`, then `pkb page-history --reload`.
- Assert (H): `snap --include=guide` shows the same current step after the reload.

**HCR-23 (M-GD-04 Missing steps file (A))** — Covers: guide file errors (NEW-2).
- Steps: `pkb guide --steps=@nope.json`.
- Assert: `steps file not found: <path>`, plus a note that relative paths are from the project.

### VS — visual surfaces

**HCR-24 (M-VS-01 Highlight (A→H))** — Covers: `highlight`, `unhighlight`, presets, short ids.
- Steps (A): `pkb highlight --query=Email` (the default preset is yellow), then `pkb highlight --handle=<short id of Go> --preset=outline-red`, then `pkb unhighlight`.
- Assert (H): `snap --include=highlights` shows `highlight=yellow` on Email and the outline preset on Go, and the screenshot matches. After `unhighlight`, no `highlight=` rows remain.

**HCR-25 (M-VS-02 Visual components (A→H))** — Covers: `SurfaceAssets` label, border, arrow, step badge, magnifier, spotlight; `ui-verify`; the `un*` verbs.
- Steps (A): `pkb label --text=Email-here --handle=email`, `pkb border --handle=go --color=red`, `pkb arrow --handle=go --direction=left`, `pkb step-badge --number=1 --text=First --handle=email`, `pkb magnifier --zoom=2 --handle=go`, `pkb spotlight --dim=0.6 --handle=email`, then `pkb ui-verify`.
- Assert (A): `ui-verify` lists six placements with `visible=true`, a `rect`, and `overlaps`. Assert (H): `snap --include=placements` lists them, the screenshot shows each one, and H can still click Go through the spotlight unless the placement used `--block=true`. Then A removes each with its `un*` verb and `--id`. H's `snap --include=placements` is then empty.

**HCR-26 (M-VS-03 Overlay and blocking (A→H→A))** — Covers: `overlay`, `--at`, `--block=true`, `overlay-poll`, `overlay-remove`.
- Steps (A): `pkb overlay --text=<b>Read-me</b> --handle=go --at=right`, then `pkb overlay --text=Blocked --handle=email --block=true`. H tries `act --kind=type --query=Email --value=x`. A runs `pkb overlay-poll`, then `pkb overlay-remove --id=<each>`.
- Assert: H sees `Read-me` in `find --query=Read-me`, or in the screenshot. H's type on the blocked field is reported `covered` or blocked, not typed. After removal, H can type.

**HCR-27 (M-VS-04 Pin, toast, checklist, progress (A→H))** — Covers: `pin`, `toast`, `checklist`, `progress` (`progress` needs a spine `--session`, see M-LK-01).
- Steps (A): `pkb pin --text=Pinned-note`, `pkb toast --text=Saved`, `pkb checklist --items=One|Two|Three`, then `pkb checklist --id=<id> --check=2`.
- Assert (H): the toast and checklist appear in the screenshot, and row 2 is checked. The 2026-11-23 run found pins invisible to `snap` and `find`. Record whether `snap --include=placements` lists the pin now. resolved: a person can tick a checklist checkbox. The change posts `type=check` `source=human` with `id`, `check`, and `checked`, even when recording is off. `events --follow --type=check` reads it, and `ui-poll` keeps a `type=check` row. That tick is not `checklist --check`. H tries clicking row 3, and A checks `ui-poll`.

**HCR-28 (M-VS-05 Batch UI and TTL (A, H check))** — Covers: `ui-catalog`, `ui-apply` (`--scope`, `--ttl`), `ui-poll`, `UiBatch`.
- Steps (A): `pkb ui-catalog`, then `pkb ui-apply --ops=@C:\pkb-manual\ops.json --ttl=10`, where the file holds a label on `#email` and a toast. Wait 12 s.
- Assert: the catalog lists every component in `UiCatalog.coveredVerbs`. H sees the label before the TTL and does not see it after.

**HCR-29 (M-VS-06 Placements follow layout changes (A, H check))** — Covers: `pkb-runtime.js` tracking through scroll, resize, and zoom.
- Steps (A): place a label on Go, then `pkb emulate --viewport=900x700`, then `pkb ui-verify`, then `pkb emulate --reset`.
- Assert: the `rect` moves with Go. H's screenshot shows the label still on Go.

### HI — HTML injection

**HCR-30 (M-HI-01 HTML write, replace, insert, remove, cleanup (A→H))** — Covers: `html-*` verbs, `AgentSurfaceRouter`, `PageSurfaces`.
- Steps (A): `pkb html-insert --handle=go --text=<p>Injected-note</p> --where=after`, then `pkb html-write --text=<p>Written-block</p>`, then `pkb html-replace --handle=<id from html-write> --text=<p>Replaced-block</p>`.
- Assert (H): `find --query=Injected-note` and then `find --query=Replaced-block` are listed (or seen in the screenshot when pkb-layer text is not listed; record which). Then A runs `pkb html-remove --id=<insert id>` and `pkb html-cleanup`. H finds neither afterwards.

**HCR-31 (M-HI-02 Injected script is inert (A))** — Covers: HTML neutralization.
- Steps (A): `pkb html-insert --handle=go --text=@C:\pkb-manual\evil.html --where=after`. The file holds `<img src=x onerror="document.title='pwned'"><script>document.title='pwned'</script>`. resolved: `html-insert --text=@file` reads the file. The body is not trimmed. A missing file is `error:` and `fix:`.
- Assert: `pkb eval --expr=document.title` is still `Manual`. H's `snap` title is `Manual`.

**HCR-32 (M-HI-03 Consumer direct DOM and JS (A, H check))** — Covers: `eval` (`--world=pkb|main`, `--await`, `--frame`), `form-fill`, `frame`.
- Steps (A): `pkb eval --expr=document.getElementById('went').textContent='Went: eval' --world=main`, then `pkb eval --expr=typeof window.__pkbUi --world=pkb`, then `pkb form-fill --fields=email=ff@example.com`.
- Assert: H finds `Went: eval`, and `snap --include=values` shows `ff@example.com`. The `pkb` world reports the runtime object.

### PT — panel and toolbar

**HCR-33 (M-PT-01 Panel tabs, collapse, reopen (A→H→A))** — Covers: `panel-open`, `panel-close`, `panel-state`, toolbar dock, `pkb-dock`.
- Steps: A runs `pkb panel-open --tab=guide`, then `pkb panel-open --collapsed=true`. H clicks `Open agent panel` (purpose `reopen`), then clicks the `Chat` tab, then folds the toolbar with the `Tools` handle (purpose `toolbar-handle`). A runs `pkb panel-state` after each step. A runs `pkb navigate --url=http://127.0.0.1:8765/manual.html`, then `pkb panel-state`.
- Assert: `panel-state` follows each H action (`open` is `panel:guide`, then collapsed, then `panel:chat`, then the toolbar collapsed). The navigate reply says `panel: kept`. The dock position and collapsed flag survive the navigation. The clicked tab is `aria-selected=true` in H's `snap`.

**HCR-34 (M-PT-02 Close asks first (H→A))** — Covers: `confirm-close`, `cancel-close`, `panel` and `close-cancel` events.
- Steps: H types unsent text in the composer and clicks the panel close button (purpose `close`). H clicks `Keep open`, then repeats and clicks `Discard and close`. A runs `pkb ui-poll --wait=5` after each click.
- Assert: A gets `close-cancel`, then a `panel` event with `discarded`. `pkb panel-close` from A does not ask.

**HCR-35 (M-PT-03 Hidden composer is not clickable (H))** — Covers: human hit-test on a collapsed panel.
- Steps: A collapses the panel. H runs `act --kind=type --purpose=message --query=Message --value=x`.
- Assert: refused as `hidden`. After the panel is minimized, `find --query=Message` in a human-locked session (default `--exposure=human`) drops the Message row; it does not list it with `visible=false`. A minimized panel hides the composer with the HTML `hidden` attribute, which is not the a11y `collapsed` / `aria-expanded=false` state. Human exposure keeps only a collapsed control (`collapsed=true`) with `visible=false`; other hidden rows are dropped. `find --exposure=full` lists the hidden Message composer with `visible=false`.

### RC — recording and step editor

**HCR-36 (M-RC-01 Recording with the person (A→H→A))** — Covers: `record --start/--stop/--script/--pause/--resume`, noise filtering, `IframeRecording`.
- Steps: A runs `pkb record --start`. H clicks Go, types into Email twice, hovers the recording bar, and clicks its Pause button. A runs `pkb record --script`, then `pkb record --stop`.
- Assert: the script has the Go click and one step for Email. It has no `Pause` or hover step, and no panel or toolbar step. `record --stop` says `recording stopped`. On `URL.loading`, a click inside an iframe is recorded as `in the "<id>" IFrame` plus the inner element.

**HCR-37 (M-RC-02 Step editor edits (A, H check))** — Covers: `record --editor/--edit/--move/--nest/--outdent/--add/--delete/--save/--load/--list/--export/--run/--inject`.
- Steps (A): `pkb record --editor`, then `pkb record --add --text=click-the-Go-button`, `--edit --index=1 --text=...`, `--move --from=1 --to=2`, `--nest --index=2`, `--outdent --index=2`, and `--delete --index=2`. Then `pkb record --save --name=manual1`, `pkb record --list`, `pkb record --export --name=manual1 --format=feature --out=C:\pkb-manual\manual1.feature`, `pkb record --inject --kind=click --query=Go`, and `pkb record --run --name=manual1`.
- Assert: each reply reflects the change. H sees the editor rows change in its `snap`. The exported feature has Gherkin steps. `record --run` clicks Go (H finds `Went:` updated).

**HCR-38 (M-RC-03 Editor pop-out (A))** — Covers: `record --popout/--popin --panel=editor`.
- Assert: `where=window` and `popped out`, then `where=page` and `popped in`. `popup blocked` is a valid result.

**HCR-39 (M-RC-04 Capture to steps (A))** — Covers: `capture-steps`, `capture-scenario`.
- Steps (A): after M-RC-01, `pkb capture-steps --out=C:\pkb-manual\steps.json`. Then `pkb capture-scenario --url=127.0.0.1 --out=C:\pkb-manual\captured.feature`.
- Assert: both files exist and name the Go click and the data request.

### UP — file hand-off

**HCR-40 (M-UP-01 Person asks the agent to attach (H→A→H))** — scenario 4. Covers: `request-upload` offer, the `upload-request` event, `upload`.
- Steps: H clicks the Attachments input, then `Ask agent to attach a file` (purpose `request-upload`). A runs `pkb ui-poll --wait=30`, reads the `upload-request` event `ref`, then runs `pkb upload --ref=<ref> --file=C:\pkb-manual\a.txt --file=C:\pkb-manual\b.txt`.
- Assert: the event has `ref`, `label`, `name`, and `multiple=true`. The `upload` reply lists `files`. H finds `Picked: a.txt,b.txt`. A's own clicks do not raise the offer (`data-pkb-agent-acting`).

**HCR-41 (M-UP-02 Upload forms and errors (A))** — Covers: `act --kind=attach`, `upload --via`, file errors.
- Steps (A): `pkb act --kind=attach --query=Attachments --value=C:\pkb-manual\a.txt`, then `pkb upload --query=Attachments --file=C:\pkb-manual\missing.txt`, then `pkb upload --selector=#files --file=C:\pkb-manual\b.txt --via=cdp`.
- Assert: exit 0 and `files`, then `error: file not found:`, then exit 0. H confirms `Picked:` each time.

**HCR-42 (M-UP-03 Drag and drop (A→H))** — Covers: `drop`.
- Steps (A): `pkb drop --selector=#files --file=C:\pkb-manual\a.txt`.
- Assert: the reply is `op=drop` and reports `via` and the `files` count (the number of paths dropped, not `input.files`). A CDP or BiDi drop dispatches `dragenter`, `dragover`, and `drop` with a DataTransfer of those files; it does not assign `input.files` or fire `change`. After that path, `#files` stays empty and H still sees `Picked:` unchanged (the catalog page only updates `Picked:` from the input's `change` handler). Use `upload --selector=#files --file=C:\pkb-manual\a.txt` (or `act --kind=attach`) to set a file input. Playwright `drop --via=playwright` uses Locator.drop and is not required to populate `input.files` either.

### NV — navigation and re-injection

**HCR-43 (M-NV-01 Navigate keeps the session (A, H check))** — Covers: `navigate` (`URL.*`, `fixture:`, literal), `UiState`, panel restore.
- Steps (A): place a label on Go. Then `pkb navigate --url-key=URL.components`, `pkb navigate --url=fixture:form`, and `pkb navigate --url=http://127.0.0.1:8765/manual.html`. Then `pkb navigate --url=nope:thing`.
- Assert: each reply has `url`, `title`, `applied: true`, and `panel: kept`, and the last one exits 1 with `url did not resolve`. H's `snap` shows each page with the panel and chat history.

### LS — listeners and push

**HCR-44 (M-LS-01 DOM push stream (H→A))** — Covers: `browser-events --start --dom`, `ProtocolHost`, `MutationObserver` binding, own-surface filter.
- Steps: A runs `pkb browser-events --start --dom --selector=#rows`. H clicks `Add row`. A runs `pkb browser-events --kinds=dom --limit=5`, then `pkb browser-events --status`. A runs `pkb chat-send --text=noise`, then `pkb browser-events --kinds=dom --limit=5` again.
- Assert: one `kind=dom subkind=mutation` row with `added` 1 and target `div#rows`. The chat bubble is not in the stream (no `--own`). The status shows events on (the 2026-11-24 note: `--start --dom` alone showed `events=off`; record).

**HCR-45 (M-LS-02 Agent waits that wake on push (H→A))** — Covers: `watch`, `wait --until-change`, `wait --condition=new-control`, the `wake` field.
- Steps: A starts `pkb watch --selector=#rows --until=change --duration=60000`. H clicks `Add row`. Repeat with `pkb wait --until-change --selector=#rows --timeout=60000` and `pkb wait --condition=new-control --timeout=60000`.
- Assert: each returns after H's click with `wake=push` (or `wake=poll`, recorded as a finding) and `pushLagMs` under 1000.

**HCR-46 (M-LS-03 Person-side waits (A→H))** — Covers: human `wait`, `wait-for`.
- Steps: H starts `pkb wait-for --condition=New --timeout=60000`. A clicks `Add row` (`pkb act --kind=click --query=Add-row`). Then H starts `pkb wait --condition=new-message --from=agent --timeout=60000`, and A runs `pkb chat-send --text=ping`.
- Assert: both H waits return without H polling.

**HCR-47 (M-LS-04 Collaboration waits use push (A))** — Covers: `pkb_push_wake`, `ui-poll --wait`, `chat-poll --wait`.
- Assert: in M-CA-01 and M-CH-01, the replies show `wake=push` and a `wakeMs`. Record the values.

**HCR-48 (M-LS-05 Event streams (A))** — Covers: `observe`, `events`, `browser-events --follow`, `chat --follow`, `record --follow`.
- Steps (A): `pkb observe --type=Network --url=data.json --limit=5` after H clicks `Load data`. Then `pkb browser-events --follow --kinds=console --wait-ms=20000` while H clicks `Log`. Then `pkb events --follow --timeout=5`.
- Assert: each stream returns those rows and ends on its timeout. A stream leaves no process behind (`doctor`).

**HCR-49 (M-LS-06 Page errors and logpoints (H→A))** — Covers: `errors`, `logpoint`, source location.
- Steps: A runs `pkb logpoint --at=manual.js:5 --expr=n`. H clicks `Throw`, then `Log`. A runs `pkb errors --limit=5` and `pkb browser-events --kinds=console --limit=5`, then `pkb logpoint --list` and `pkb logpoint --clear`.
- Assert: the error row has `manual-boom`, `manual.js`, line 2. The console has a `[logpoint <id>]` row with `1`. After `--clear`, `--list` is empty.

### DT — DevTools and protocol verbs

Each DT test records the backend in the reply (`via=`). Chrome is the default target. A `--via=bidi` check is part of the test where the verb lists BiDi.

**HCR-50 (M-DT-01 Raw CDP (A→H))** — Covers: `cdp`, `PwCommand`.
- Steps (A): write `C:\pkb-manual\cdp.json` as `{"expression":"document.getElementById('went').textContent='Went:cdp'"}`, then `pkb cdp --method=Runtime.evaluate --params=@C:\pkb-manual\cdp.json`. resolved: `cdp --params=@file` reads a JSON object. A missing file or a value that is not an object fails before the browser connects. Then `pkb cdp --method=Browser.getVersion`.
- Assert: H finds `Went:cdp`. The version reply names Chrome.

**HCR-51 (M-DT-02 Playwright over CDP (A→H))** — Covers: `pw` areas (page, locator, screenshot, trace, video), `pw run`, `execute-steps` with a `pw` line.
- Steps (A): `pkb pw page title`, `pkb pw locator click --selector=#go`, `pkb pw screenshot --out=C:\pkb-manual\pw.png`, `pkb pw run --steps=@C:\pkb-manual\trace.json` (trace start, then stop with `out`), and `pkb pw video --dir=C:\pkb-manual\vid`. Then `pkb execute-steps --file=C:\pkb-manual\steps-pw.json`.
- Assert: the title is `Manual`. H finds `Went:` updated after the locator click. The trace zip exists. `pw video` exits with `video recording is not available over connectOverCDP`. The steps file reports each line.

**HCR-52 (M-DT-03 Selenium BiDi verbs (A))** — Covers: `bidi script`, `bidi intercept`, `SeleniumBidiCommands`. Needs a Selenium session: an `isolate` worker with `--runvar=pkb_selenium_bidi=on --runvar=pkb_browser=CHROME` (or `browser.selenium.bidi: on`). The `bidi` verbs also need `--session=<id>` or the default session from `spine session new`.
- Steps (A): `pkb spine session new --agent=agent-1`, then `pkb isolate --tags=@primary-navigation --runvar=pkb_selenium_bidi=on --runvar=pkb_browser=CHROME --run-id=bidi`. Then `pkb bidi script --source=document.title --run-id=bidi`, `pkb bidi intercept --url=*data.json* --body=bidi-body --run-id=bidi`, and `pkb bidi intercept off --id=<id> --run-id=bidi`. Stop the worker with `pkb stop --run-id=bidi`.
- Assert: the title comes back, an intercept id is returned, and `off` removes it. `@primary-navigation` is the consumer primary-navigation pack and does not fetch `data.json`. The page with that fetch is this catalog's own manual fixture (Setup: the `#load` button runs `fetch('data.json')` and writes `Data: ...`). After the worker is up, navigate that session to the manual fixture (or start Chrome on that file) before `bidi intercept --url=*data.json*` and a `Load data` click. `bidi script --source=document.title` works on any loaded page and does not need a fetch. `bidi grid-attach` needs a Grid URL that Dan provides; otherwise record it as not run.

**HCR-53 (M-DT-04 Network capture (H→A))** — Covers: `net --capture-bodies`, net rows, `net-body`, `har`.
- Steps: A runs `pkb net --capture-bodies=all`, then `pkb har --start`. H clicks `Load data`. A runs `pkb browser-events --kinds=net --match=data.json --limit=3`, then `pkb net-body --request-id=<id>`, then `pkb har --stop --out=C:\pkb-manual\s.har`.
- Assert: a net row with timing and bytes. The body is `{"v":"real"}`. The HAR file contains `data.json`.

**HCR-54 (M-DT-05 Network control (A→H))** — Covers: `net-route`, `net-unroute`, `net-block`, `net-headers`, `net-cache`.
- Steps: A runs `pkb net-route --match=*data.json --action=fulfill --status=200 --body=routed`, and H clicks `Load data`. A runs `pkb net-unroute --all`, then `pkb net-block --match=*data.json`, and H clicks again. A runs `pkb net-unroute --all`, then `pkb net-headers --set=@C:\pkb-manual\headers.json` (`{"X-Manual":"1"}`), and H clicks again. Then A runs `pkb browser-events --kinds=net --match=data.json --limit=1`, `pkb net-headers --clear`, `pkb net-cache --disable`, and `pkb net-cache --enable`.
- Assert: H finds `Data: routed`, then `Data: failed`. The last net row shows the request header `X-Manual`. Each verb exits 0.

**HCR-55 (M-DT-06 WebSocket routing (A))** — Covers: `ws-route` (Playwright only).
- Steps: `pkb ws-route --match=ws://127.0.0.1:8799/* --rules=@C:\pkb-manual\ws-rules.json --via=playwright`.
- Assert: resolved: `--rules` is a JSON array. `on` is a substring (empty matches every message; `re:` is a regex search). `reply` is sent when that key is present, including empty. `close` true, or any `closeCode` key, closes the socket, and the code value is not sent. The first match wins. A rule does not need `reply` or `close`. Example: `[{"on":"ping","reply":"pong"}]`. The fixture page still opens no WebSocket, so record the command reply and do not treat a missing socket as a schema gap.

**HCR-56 (M-DT-07 Emulation (A→H))** — Covers: `emulate`.
- Steps (A): `pkb emulate --media=prefers-color-scheme:dark`, then `--vision=deuteranopia`, `--locale=de-DE --timezone=Europe/Berlin`, `--viewport=800x600@2`, `--network=offline`, `--show`, and `--reset`.
- Assert (H): `find --query=Scheme` shows `Scheme: dark`. The screenshot after `--vision` shows the simulated colors. After `--network=offline`, a `Load data` click shows `Data: failed`. After `--reset`, `Scheme: light` returns. `--show` lists each override.

**HCR-57 (M-DT-08 Clock (A→H))** — Covers: `clock`.
- Steps (A): `pkb clock --install=2030-01-02T00:00:00Z`, then `pkb clock --set-fixed=2031-05-06T00:00:00Z`, then `pkb clock --resume`.
- Assert (H): `find --query=Now` shows `Now: 2031-05-06` after `--set-fixed`.

**HCR-58 (M-DT-09 Permissions and geolocation (H→A→H))** — Covers: `permissions`, `emulate --geo`.
- Steps: A runs `pkb permissions --grant=geolocation --origin=http://127.0.0.1:8765`, then `pkb emulate --geo=33.4,-112.1`. H clicks `Where am I`. A runs `pkb permissions --deny=geolocation --origin=http://127.0.0.1:8765`, and H clicks again. A runs `pkb permissions --reset`.
- Assert (H): `Geo: 33.4,-112.1`, then `Geo: denied`.

**HCR-59 (M-DT-10 Contexts, tabs, history, print (A, H check))** — Covers: `context-new`, `context-list`, `context-close`, `tab-new`, `tab-activate`, `tabs`, `windows`, `page-history`, `print`.
- Steps (A): `pkb context-new`, then `pkb tab-new --url=http://127.0.0.1:8765/index.html --context=<id> --activate=true`, `pkb context-list`, `pkb tabs`, `pkb tab-activate --page=manual.html`, `pkb page-history --back`, `pkb page-history --forward`, `pkb page-history --reload=hard`, `pkb print --out=C:\pkb-manual\page.pdf`, and `pkb context-close --id=<id>`.
- Assert: the new tab is listed with its context. H's `snap` follows the protocol target after `tab-activate`, and `warning` is set when it differs from the visible tab. The PDF exists. After `context-close`, the tab is gone.

**HCR-60 (M-DT-11 Storage (H→A→H))** — Covers: `storage`, `storage-state`, cookie redaction.
- Steps: H types `n1@example.com` and clicks `Save note`. A runs `pkb storage --get --kind=local`, then `pkb storage --set=@C:\pkb-manual\storage.json` (the file holds `{"local":{"note":"from-agent"},"cookies":[{"name":"c1","value":"v1","url":"http://127.0.0.1:8765"}]}`), `pkb page-history --reload`, `pkb storage --get --kind=cookies`, `pkb storage --get --kind=cookies --reveal`, `pkb storage-state --save=C:\pkb-manual\state.json`, `pkb storage --clear --kind=local`, and `pkb storage-state --load=C:\pkb-manual\state.json`.
- Assert: A reads `note=n1@example.com`. After the reload, H finds `Note: from-agent`. The cookie value is `[redacted]` without `--reveal`. After `--load`, the note is back.

**HCR-61 (M-DT-12 Downloads (H→A))** — Covers: `downloads`.
- Steps: A runs `pkb downloads --dir=manual-dl`. H clicks `Download data`. A runs `pkb downloads --wait --timeout=10000`, then `pkb downloads --list`. A runs `pkb downloads --deny`, H clicks again, and A runs `pkb downloads --list`.
- Assert: a completed `kind=download` row, with `maven-consumer-project\manual-dl\data.txt` present. The denied download is `canceled`.

**HCR-62 (M-DT-13 JavaScript dialogs (H→A))** — Covers: `dialogs`, `DialogBlocksBindingTest` behavior.
- Steps: A runs `pkb dialogs --policy=accept`, and H clicks `Alert`. A runs `pkb dialogs --policy=prompt:Ann`, and H clicks `Prompt`. A runs `pkb dialogs --policy=ask`, and H clicks `Alert`. Then A runs `pkb dialogs --policy=dismiss` and `pkb dialogs --policy=default`.
- Assert: H finds `Dialog: alert closed`, then `Dialog: Ann`. Under `ask`, the alert stays open. H cannot answer it with H tools (a known gap, see the end of this page): H's `snap` either times out or reports the blocking dialog; record which. resolved: `dialogs --policy=dismiss` answers the next dialog-open event. It does not close a dialog that is already open. A later policy applies to the next opening. Every dialog is a `kind=dialog` row.

**HCR-63 (M-DT-14 Input chains and text (A, H))** — Covers: `input --actions`, `input --text --at`, `--release`, `--via`.
- Steps: A runs `pkb input --text=typed-by-input --at=<short id of Email>`. H runs `pkb input --actions=@C:\pkb-manual\click-go.json` (a pointer move to Go's viewport point, then down and up). A runs `pkb input --actions=@C:\pkb-manual\click-go.json --via=bidi`, then `--via=cdp`, then `--release`.
- Assert: H's `snap --include=values` shows `typed-by-input`. Each H or A click updates `Went:`. The reply names the backend.

**HCR-64 (M-DT-15 WebAuthn, extensions, WebMCP (A))** — Covers: `webauthn`, `extension`, `webmcp`.
- Steps (A): `pkb webauthn --add-authenticator --protocol=ctap2 --resident --uv`, then `pkb webauthn --list`, `pkb webauthn --remove`, `pkb extension --list`, and `pkb webmcp --list`.
- Assert: the authenticator is added and listed, then removed. `extension --list` either lists extensions or says Chrome needs `--enable-unsafe-extension-debugging`. `webmcp --list` reports `unsupported` on this page. Record both as environment notes, not failures.

**HCR-65 (M-DT-16 Performance, memory, screencast (A))** — Covers: `perf`, `memory`, `screencast`.
- Steps (A): `pkb perf --metrics`, then `pkb perf --vitals`, `pkb perf --trace-start`, then H clicks Go, then `pkb perf --trace-stop --out=C:\pkb-manual\trace.json` and `pkb perf --report --slowest=3`. Then `pkb memory --gc`, `pkb memory --snapshot --out=C:\pkb-manual\h.heapsnapshot --max-mb=200`, `pkb screencast --start --frames-dir=C:\pkb-manual\frames --fps=2`, then H clicks Go, then `pkb screencast --stop`.
- Assert: metrics and vitals rows, a trace file, a heap snapshot within the cap, and frames with a count over 0.

**HCR-66 (M-DT-17 Audits, service workers, security, CSP, auth (A))** — Covers: `audits`, `sw`, `security`, `csp`, `net-auth`.
- Steps (A): `pkb audits --check-forms`, then `pkb audits --list`. The page labels its fields, so expect few form issues. Then `pkb sw --list --wait-ms=3000`, `pkb sw --bypass`, `pkb sw --bypass=false`, and `pkb sw --unregister=http://127.0.0.1:8765/`. Then `pkb security --ignore-cert-errors`, `pkb security --ignore-cert-errors=false`, `pkb csp --bypass`, `pkb csp --bypass=false`, `$env:PKB_MANUAL_AUTH='u:p'`, `pkb net-auth --credential=env:PKB_MANUAL_AUTH`, and `pkb net-auth --clear`.
- Assert: the `sw.js` registration is listed and then unregistered. Each toggle exits 0. The credential value is never printed or logged (search `.pickleball/proto/host.log` for `u:p`).

**HCR-67 (M-DT-18 Optional add-ons (A, needs Dan's OK))** — Covers: `optional-list`, `optional-install`, `audit`, `bridge --devtools-mcp`, `optional-uninstall`.
- Steps (A): `pkb optional-list`, then `pkb optional-install axe` without `--confirm`, which prints the plan only.
- Assert: the plan names the npm package, registry URL, size, license, and cache dir. `optional-install ... --confirm` downloads axe-core 4.14.1, Lighthouse 13.6.0, or chrome-devtools-mcp 1.13.0 from registry.npmjs.org. Run it, and `audit --axe`, `audit --lighthouse`, and `bridge --devtools-mcp`, only when Dan has approved that download in his request. Otherwise record them as not run (external download).

**HCR-68 (M-DT-19 Attach routes and sharing (A))** — Covers: `browser ports`, `doctor --attach`, `attach --cdp-endpoint`, `attach --discover`, `share-browser`.
- Steps (A): `pkb browser ports`, then `pkb doctor --attach`, then `pkb attach --discover`. Then `pkb share-browser --name=manual` and `pkb share-browser --stop`.
- Assert: the live Chrome's debug port is listed. `attachRoutes=` is printed. The share prints a local endpoint and stops. Do not run `relaunch-with-debug` or attach to Dan's own browser channels: that restarts or joins his personal browser.

**HCR-69 (M-DT-20 Frames and shadow roots (A→H))** — Covers: `frame --list/--enter/--reset`, OOPIF, `eval --frame`, human rows with `frame=`.
- Steps (A): `pkb navigate --url-key=URL.loading`, then `pkb frame --list`, `pkb frame --enter=mid`, `pkb find --query=<a control inside mid>`, `pkb eval --frame=<id> --expr=document.title`, and `pkb frame --reset`.
- Assert: the frame rows have `id`, `name`, `url`, and `sameOrigin`. H's `snap` lists the iframe controls with their own `frame=`, and lists open shadow-root controls. Then navigate back to `manual.html`.

### PP — popup, native highlight, desktop

**HCR-70 (M-PP-01 Agent popup window (A))** — Covers: `popup-open`, `popup-status`, `popup-close`, `PopupHost`, `PopupBrowserLocator`, popup chat.
- Steps (A): `pkb popup-open`, then `pkb popup-status`, then `pkb chat-send --text=popup-hello --target=popup`, then `pkb overlay --text=Popup-note --target=popup` (`--target=popup` comes from the routing table in `docs/direct-control-handoff.md`; neither usage line lists it), then `pkb popup-close`.
- Assert: the status shows a live host, browser Chrome (from `PKB_AGENT_POPUP_BROWSER`), and `alwaysOnTop.applied=true` on Windows. `popup-close` reports `exited=true`, and no host or browser pid remains. resolved: `help popup-open`, `help popup-status`, and `help popup-close` print Usage. Each takes `--run-id`. `--on-top` and `--headless` are not `popup-open` flags. `--target=popup` routes other surface ops onto the popup. It does not open the window. Control tier. H cannot see or drive the popup window with H tools (gap). A person would type in its Chat pane.

**HCR-71 (M-PP-02 Native highlight (A))** — Covers: `highlight --target=native`, the popup Instructions caption.
- Steps (A): `pkb highlight --target=native --x=100 --y=100 --width=200 --height=80 --preset=outline-red`.
- Assert: `drawn=true` on headed Windows, and the caption goes to the popup Instructions pane. H has no screen-level capture (gap). Only A and Dan's eyes can confirm the strips.

**HCR-72 (M-PP-03 Desktop surface (A→H, Windows))** — Covers: `domengine desktop launch/attach/query/click/type/screenshot/close`, human `snap --target=desktop:app~<name>`.
- Steps (A): `pkb domengine desktop launch --exe=notepad.exe --name=Notepad` and keep the JSON `id`. Then H runs `pkb snap --target=desktop:app~Notepad`. Then A runs `pkb domengine desktop type --id=<id> --role=document --text=desktop-typed`. resolved: `domengine help` lists `type --id` with `--text`, `--role`, `--name` (accessible name, only with `--role`), `--css`, `--xpath`, `--pipeline`, `--selector`, `--node-id`, and `--append=true`. `--query` is not a type flag. `--name` alone is the window title for attach, launch, and screenshot, not for type. `close` uses `--id`. Then H runs `pkb snap --target=desktop:app~Notepad` again, then A runs `pkb domengine desktop close --id=<id>`.
- Assert: H's first snap lists the Notepad edit control, and the second shows the typed text. Close leaves no Notepad process.

### LK — spine session, lease, inject

**HCR-73 (M-LK-01 Spine session surfaces (A))** — Covers: `spine session new/show/end`, `inject on/off`, `lease take/release`, `chat send/list`, `chat --follow`, `progress`, `point`.
- Steps (A): `pkb spine session new --agent=agent-1`, then `pkb inject on`, `pkb lease take --target=browser`, `pkb lease take --target=browser` again, `pkb chat send --text=spine-hello`, `pkb chat list`, `pkb chat --follow --timeout=3`, `pkb progress --pct=40 --label=Working`, `pkb point --session=<id> --x=200 --y=200`, `pkb lease release --target=browser`, `pkb inject off`, and `pkb spine session end`.
- Assert: the second `lease take` fails with `error:` and `fix:`. `chat list` shows `spine-hello`. Each verb exits 0 otherwise. resolved: `spine session` is the overlay, lease, and inject session. It is not the browser page `start` opens. `progress` writes `.pickleball/workbench-ui/progress/<session>.json` and does not paint the start page, so H's `snap` does not show a progress bar from that file. `point` highlights on the coordination `--run-id`. H's `snap` shows that highlight only when it landed on the live start page.

**HCR-74 (M-LK-02 Human lock is not the Workbench lease (A))** — Covers: `HumanLock` versus `WorkbenchControlLease`.
- Assert: with `helper` locked, `pkb lease take --target=ui` under `agent` still works, and `session-mode` under `helper` is unaffected by the lease. The Workbench GUI "Take control" button is outside this catalog.

### MC — MCP forwarding

**HCR-75 (M-MC-01 call forwards tools with tiers (A, H))** — Covers: `call`, `VerbTiers.toolTier`, compact MCP tools.
- Steps: A runs `pkb call workbench_snap --args-file=C:\pkb-manual\snap-args.json` (`{"target":"browser:auto","budget":2048}`), then `pkb call workbench_find --args-file=C:\pkb-manual\find-args.json` (`{"target":"browser:auto","query":"Go"}`). H runs `pkb call workbench_snap --args-file=C:\pkb-manual\snap-args.json`.
- Assert: A gets the snap and find envelopes. H's `call` exits 2 with the control-tier refusal, because `call` itself is control tier. A human role over MCP needs a real MCP client that sends human-tier tools (gap, see below).

## Targeted selection

When an implementation touches these areas, run these tests:

| Code area | Tests |
|---|---|
| `VerbTiers`, `HumanLock`, `WorkbenchAgentCommands` dispatch, `AgentControl.execute` | HCR-01 (M-HL-01) to 04, HCR-75 (M-MC-01), HCR-74 (M-LK-02) |
| `ViewMask`, `Perception`, `ElementRecord`, snap and find rows, `--exposure` | HCR-05 (M-HV-01) to 06, HCR-35 (M-PT-03) |
| Chat (`ChatLog`, chat cursors, panel composer, popup chat) | HCR-11 (M-CH-01) to 03, HCR-09 (M-HV-05), HCR-70 (M-PP-01) |
| Cards and waits (`ask*`, `answer-wait`, `wait-any`, `ui-poll`, push wake) | HCR-14 (M-CA-01) to 06, HCR-47 (M-LS-04) |
| Guides | HCR-20 (M-GD-01) to 04 |
| `SurfaceAssets`, `pkb-runtime.js`, `UiCatalog`, `UiBatch`, placements | HCR-24 (M-VS-01) to 06, HCR-30 (M-HI-01) |
| HTML injection and `AgentSurfaceRouter` | HCR-30 (M-HI-01) to 03, HCR-70 (M-PP-01) |
| Panel, toolbar dock, step editor, recording | HCR-33 (M-PT-01) to 03, HCR-36 (M-RC-01) to 04, HCR-13 (M-CH-03) |
| Upload and file controls | HCR-40 (M-UP-01) to 03, HCR-01 (M-HL-01) |
| `navigate`, `UiState`, re-injection | HCR-43 (M-NV-01), HCR-22 (M-GD-03), HCR-33 (M-PT-01) |
| Protocol host, `browser-events`, DOM push, `watch`, `wait` | HCR-44 (M-LS-01) to 06, HCR-10 (M-HV-06) |
| `ProtoVerbs` network and DevTools verbs | the matching M-DT test |
| `PwCommand`, `cdp`, `execute-steps` | HCR-50 (M-DT-01), HCR-51 (M-DT-02) |
| Selenium BiDi | HCR-52 (M-DT-03) |
| `ConsumerReadyCommands.start`, `LiveBrowserHost`, controller | S0, HCR-04 (M-HL-04), HCR-43 (M-NV-01) |
| Popup host, native highlight, desktop | HCR-70 (M-PP-01) to 03 |
| Spine sessions, leases, inject | HCR-73 (M-LK-01) and 02 |

## Known gaps (no human-mode equivalent)

These are expected results, not failures. A run records each one it meets.

- No human-tier way to answer a native JavaScript dialog (`dialogs --policy=ask` leaves it open), to pick a file in the OS file chooser, or to drive the popup window or the Workbench GUI.
- No human-tier screen capture outside the page. Native highlight strips and the popup are visible only to a person or to A.
- `call` is control tier. A human role over MCP needs an MCP client that offers only the human-tier tools.
- `act --kind=scroll` passes the human lock, `help act` lists it, and the act scrolls the page or a named element. The reply reports `dx`, `dy`, `into_view`, `scrollX`, and `scrollY`.

## Earlier collaboration runs this catalog builds on

- `docs/collaborative-self-test.md`, "Human participation" and "Two-actor recipe". Scenarios 1 to 6 are HCR-14 (M-CA-01), HCR-11 (M-CH-01), HCR-24 (M-VS-01) with HCR-06 (M-HV-02), HCR-40 (M-UP-01), HCR-15 (M-CA-02), and HCR-20 (M-GD-01).
- `docs/maintainer/3.0.0-collab-test/` (`plan.md`, `recipe.md`, findings 2026-11-23, retest rounds 2026-11-24). The automated part is `TwoActorRecipeLiveTest`, which runs scenarios 1, 2, and 5 headless in the short and full tiers.
- `docs/collaborative-recording.md`, `docs/dynamic-control-api.md`, `docs/browser-events.md`, `docs/browser-protocols.md`, `docs/playwright.md`, and `docs/direct-control-handoff.md` for each verb's contract.
