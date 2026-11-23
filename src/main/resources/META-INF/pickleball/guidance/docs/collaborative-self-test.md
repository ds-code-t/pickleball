# Collaborative self-test

Instruction recipes for a consumer AI agent. The sections above Human participation are unattended. Human participation needs a person, or a second agent taking that role. Neither kind is an `@all` suite, and neither replaces the contracts in `docs/collaborative-recording.md` and `docs/dashboard.md`.

Run them from the consumer project with that project's wrapper. Do not require a machine-wide Maven or Gradle install. A scratch directory is optional. Use one whose path contains no spaces.

## Invoke the launcher

From the consumer project on Windows, replace only the `exec.args` value. Keep that value free of spaces.

```bat
.\mvnw.cmd -q org.codehaus.mojo:exec-maven-plugin:3.5.0:java "-Dexec.mainClass=tools.dscode.launcher.PickleballWorkbenchLauncher" "-Dexec.classpathScope=test" "-Dexec.args=selftest ui --component=feedback"
```

The same class and plugin on macOS or Linux:

```bash
./mvnw -q org.codehaus.mojo:exec-maven-plugin:3.5.0:java "-Dexec.mainClass=tools.dscode.launcher.PickleballWorkbenchLauncher" "-Dexec.classpathScope=test" "-Dexec.args=selftest ui --component=feedback"
```

`record` and `selftest` accept a scratch directory as the last token when the token contains a slash or a backslash (`C:\pkb-selftest`). `dashboard` does not. Pass `--project=C:\pkb-selftest`. A positional directory in front of `query` is the first non-flag token, so `dashboard C:\pkb-selftest query` writes HTML and does not run query. The HTML lines are `dashboard=.pickleball/dashboard/index.html` and `runs=<n>`.

Omit the scratch path to use the consumer project. The routine file is then `.pickleball/recordings/routines/selftest.json` under that project.

## How to read a result

Stdout is one JSON object. Read `ok`, `sentence`, and `skipped` when those fields are present. `sum` repeats `sentence`. `dashboard query`, `dashboard overlap`, and `dashboard xref` print that object directly (`op` is `query`, `overlap`, or `xref`). A failure also prints `error:` and `fix:` on stderr. `err.fix` is the short hint, such as `help record`. The stderr `fix:` line may be the help page for that hint.

| Exit | Meaning |
|---|---|
| 0 and the expected `sentence` | The check passed. |
| 1, `ok` false, `skipped` true, and a no-Chrome sentence | This host has no Chrome binary. The product was not shown to be broken. |
| 1 and `record --start first` | `record --inject` ran before `record --start`. |
| 2 and `error:` plus `fix:` | The command line was wrong. Follow `fix:`. |
| 1 and `error:` plus `fix: dashboard ...` | The dashboard read failed. Follow `fix:`. |

`selftest ui` with no `--component` samples idle CPU and looks for Chrome. These recipes pass `--component=feedback`, `--component=pages`, or the `record` subcommand.

## Quoting and split phrases

Maven exec splits a space inside `-Dexec.args` into another argument. A quoted phrase can also be taken as a lifecycle phase (`Unknown lifecycle phase`). Keep every flag value free of spaces: `--query=Approve`, `--text=,press-Escape`, `--name=selftest`, `--role=button`.

A phrase without a leading comma is stored with one added comma. `--text=,press-Escape` stays one comma. If the stored step is only `, click`, the rest of the phrase was split off. That is the invocation, not the recorder. Do not pass `--text-file` or `--text-b64` to `record`. Those flags belong to `execute-step`.

## Feedback states

No Chrome. This is the unattended proof that chat and push can show sending, delivered, and read.

```text
selftest ui --component=feedback
```

Pass: exit 0. `sentence` is `feedback states sending, delivered, and read are in the page runtime`. `component` is `feedback`. `sending`, `delivered`, and `read` are true. `chrome` is false.

Fail: exit 1. `sentence` is `feedback states missing`.

## Question cards and guides

`selftest ui --component=pages` places a pin `Pinned note`, a toast `Toast note`, a checklist `Alpha|Beta`, and a status `Waiting here`, then reads that text back from the page. It needs Chrome.

Pass: exit 0. `sentence` is `pages read Pinned note, Toast note, Alpha, Beta, Waiting here`.

Miss: exit 1. `sentence` is `pages missed a card`, or `<card> did not place`.

No Chrome: exit 1, `skipped` true, `sentence` `selftest ui --component=pages found no Chrome binary.`

A click on an `ask-and-wait` choice, or a typed guide value, needs a person or a second agent. A timeout while waiting for that answer is not a product failure when nobody answers. Do not add those waits to an unattended run.

## Chat and push

The three feedback states above are the unattended check. `chat-turn --until=human` waits for a non-agent chat message, an answer, or an agent-UI click. With nobody at the page, the wait ends in timeout. That timeout means nobody answered. `events --follow` and `ui-poll` start at now unless `--cursor` or `--after` is set.

## Recording, iframe, and noise

`selftest record` needs Chrome. It opens a fixture page, clicks the recording-bar pause control, clicks a Pay tab inside an iframe named `billing`, and types `Ada` into the account field.

Pass: exit 0. `sentence` is `selftest record passed`. The script contains `in the "billing" IFrame`, `"Pay"`, `Tab`, and `Ada`. It does not contain `Pause`, `hover`, or `Accept`. `.pickleball/recordings/stream.jsonl` has a row whose name contains `Pay` or whose value is `Ada`.

A click without the typed value is not that pass. Exit 1, `streamed` true, and `script` equal to `Then , in the "billing" IFrame, click the "Pay" Tab` means the iframe click was recorded and the pause control was not a step, and `Ada` was not a step. `sentence` is `selftest record did not match the iframe script`. Report the missing type. Do not treat the click line as a full pass.

Any other miss uses that same sentence. The page runtime missing is `selftest record did not install the page runtime.` Any other throw is `selftest record failed`.

No Chrome: exit 1, `skipped` true, `sentence` `selftest record found no Chrome binary.`

Hover and focus stay off the script unless noise mode is on. The panel, the step editor, the recording bar, and other injected UI are not steps. That is what the missing `Pause`, `hover`, and `Accept` text proves.

## Step editor, pop-out, and save

No Chrome. Indexes are 0-based. Run each line as its own launcher invocation. Add the scratch directory as the last token when you are not using the consumer project.

```text
record --start --session=selftest
record --inject --kind=click --query=Approve --role=button
record --inject --kind=click --query=Save --role=button
record --editor
record --edit --index=0 --text=,press-Escape
record --move --from=1 --to=0
record --nest --index=1
record --outdent --index=1
record --delete --index=1
record --script
record --save --name=selftest
record --popout --panel=chat
record --popin --panel=chat
record --popout --panel=editor
record --popin --panel=editor
record --stop
```

| Command | Pass |
|---|---|
| `record --start --session=selftest` | Exit 0. `sentence` is `recording`. `status` is `recording`. |
| `record --inject` before `record --start` | Exit 1. `sentence` is `record --start first`. |
| First inject | Exit 0. `sentence` is `action recorded`. The script is on the next `record --editor`, not on the inject object: `Then , click the "Approve" Button` and `And , click the "Save" Button`. The role is Button and the name is quoted. |
| `record --editor` with steps | Exit 0. `sentence` is `editor ready`. An empty editor is `no steps recorded`. |
| `record --edit` | Exit 0. `sentence` is `editor updated`. Index 0 is `,press-Escape` (one leading comma). |
| `record --move --from=1 --to=0` | Exit 0. `sentence` is `editor updated`. The Save click and the Escape step swap. |
| `record --nest --index=1` | Exit 0. Depth is 1. The script is `Then , click the "Save" Button:` and then `: And ,press-Escape`. |
| `record --outdent --index=1` | Exit 0. That row's depth is 0. |
| `record --delete --index=1` | Exit 0. The Escape row is gone. The script still contains `Save`. |
| `record --script` with steps | Exit 0. `sentence` is `script ready`. |
| `record --script` on an empty session | Exit 0. `sentence` is `no steps recorded`. `count` is 0. |
| `record --save --name=selftest` | Exit 0. `sentence` is `routine saved`. The file is `.pickleball/recordings/routines/selftest.json`. |
| `record --popout --panel=chat` or `--panel=editor` | Exit 0. `sentence` is `popped out`. `where` is `window`. |
| `record --popin` for that panel | Exit 0. `sentence` is `popped in`. `where` is `page`. |
| `record --stop` | Exit 0. `sentence` is `recording stopped`. |
| `record --move --from=9 --to=0` with fewer rows | Exit 2. `sentence` is `record --move --from is out of range`. `record --nest` uses `record --nest --index is out of range`. |

Pop-out here updates the panel map. A popup blocker on a live page returns `popup blocked` and leaves the panel on the page. That live window check is a collaborator scenario, not this unattended list.

## Dashboard cross-reference

Use an empty scratch store when you want the zero-row shape. A consumer store that already has runs returns those runs. `count` 0 is a pass only when nothing retained matches.

```text
dashboard query --project=C:\pkb-selftest --limit=5
dashboard overlap --project=C:\pkb-selftest --limit=5
dashboard xref --project=C:\pkb-selftest --role=button --name=Submit
```

Empty query: exit 0. `{"ok":true,"op":"query","count":0,"total":0,"runs":[]}`. A non-empty run has `name`, `ref`, `status`, `startedAt`, `finishedAt`, `feature`, `scenarios`, `artifacts`, `data`, and `summary`.

Empty overlap: exit 0. `{"ok":true,"op":"overlap","count":0,"total":0,"groups":[]}`. A failure line appears only when at least two runs in a group share it.

Empty xref: exit 0. `ok` is true, `op` is `xref`, `count` is 0, and the element role and name are echoed. `summary` is `No history mentions button Submit.` `overlay` and `chat` are both `History for button Submit: no related runs.` The object also has `runs`, `scenarios`, `logs`, `screenshots`, and `concurrent`.

Missing hint: exit 2. `error: dashboard xref needs --id, --selector, or --role and --name`. `fix: dashboard xref --id=#save`.

A read that throws: exit 1. `error:` and `fix: dashboard query --project=<dir>` (or the `xref` / `overlap` form of that fix).

`dashboard --project=C:\pkb-selftest` writes `.pickleball/dashboard/index.html`. It does not start a server and it does not change the store. `dashboard xref --window` writes one HTML file and does not open a browser. Pass `overlay` to the overlay verb, or put `chat` into chat, when a live page is up. Driving that page while you read history is a collaborator scenario.

## Distinction and tagging

Person-facing text quotes a concrete value (`Enter "abc" in this textbox`). Guides, question cards, chat, the step editor, callouts, and the recording script paint that quote as a `.pkb-value` chip. The stored `ask-and-wait` answer stays the clicked label, without added quote characters. The card shows `Answer sent: "Alpha"` while the stored answer is `Alpha`.

Injected UI carries `data-pkb-meta="true"` and an overlapping `data-pkb-purpose` token: `panel`, `guide`, `placement`, `editor`, `callout`, `recorder`, or `layer`. An assigned id starts with `pkb-`. Recording treats a tagged node as own UI. Select it with `[data-pkb-meta="true"]` or `[data-pkb-purpose~="guide"]`.

Unattended proof: the feedback selftest for the three states, `selftest record` for the missing pause and hover noise, and the editor sequence above for write-back. The quote chip and a live click on a tagged node need the page in front of someone.

## Consumer example

`maven-consumer-project/src/test/resources/features/recorded-collaboration.feature` is the shape `record --export --format=feature` writes. It navigates to `URL.dynamicSteps` (`site/dynamic-steps.html`) and names `record --editor`, `--popout`, and the example rows. It is not a live collaborative session. Do not add a second feature for that path.

The five-command path stays in the first-read guide: `start --url-key`, `chat-turn`, `act --query`, `ui-verify`, `doctor`. This file is the longer self-test.

## Human participation

These scenarios need a person at the page, or a second agent who only types and clicks. The agent that starts a wait must not also be the one who answers it. They cannot run as unattended regression. A timeout with nobody present is the result already described above, not a new failure.

Keep launcher flag values free of spaces. Put text that needs spaces or quotes in a file, and pass that file.

Use a headed page with the panel. From the consumer project:

```text
start --url=fixture:form --browser=chrome --headed --panel
```

`fixture:form` is a built-in data page with an Email field and a Go button. A consumer page that contains a content iframe is the right target when the iframe check below is in scope. Leave this browser up until `live-stop`.

Pass: exit 0. `sentence` is `live browser ready, panel open`. `panel` is `open` and `navigated` is true. `headed` is true only when this JVM has a display. A headless JVM keeps `headed` false even with `--headed`, and a person cannot see that window. Do not run the rest of this section unless `headed` is true.

### Chat delivery and read state

```text
chat-turn --text=ready --until=human --timeout=120
```

`--timeout` here is seconds, from 0 to 120. The command waits.

The person types a short message in the panel and sends it. The bubble shows `sending` at once, then `delivered` when the push channel or the event store takes it, then `read` when the agent consumes it. The agent reply lists that message once. A message the panel already shows as Read is not returned again.

Pop the chat out and back in while a second agent message is unread:

```text
record --popout --panel=chat
record --popin --panel=chat
```

The Read mark on the person's message stays. An unread agent message stays unread. If the browser blocks the window, the result says `popup blocked` and the panel stays on the page.

### Question card

```text
ask-and-wait --say=Pick-one --choices=Alpha,Beta --timeout=120000
```

`--timeout` here is milliseconds. `--choices` is one button per comma-separated token.

The person clicks Alpha. The card shows `Answer sent: "Alpha"` for about 1.5 seconds. The value returned to the agent is `Alpha`, without added quote characters. Only the clicked choice comes back.

When the question text must contain a concrete value, quote it in the source (`Enter "abc" in this textbox`). The page paints that quote as a `.pkb-value` chip. Do not drop the quote characters.

### Guide

Write `guide-steps.json` in the consumer project:

```json
[{"t":"#email","say":"Enter \"abc\" in this textbox","do":"input","expect":"abc"}]
```

```text
guide --steps=@guide-steps.json
```

The person sees the quoted `abc` as a chip. The step ends on Enter, on blur, or when the field matches `expect`. It does not end on a pause between keystrokes. A missing current target is refused and is not listed in Steps. When the guide ends, field step-done badges are removed. `guide-done` ends it if it is still open.

### Recording with the person

```text
record --start --session=human
```

The person clicks Go on the page, then types in Email. They also hover the recording bar and may click Pause.

`record --script` then shows the page click and one step for the email field. Consecutive edits of that field are one step. The script does not contain `Pause`, `hover`, or `Accept`. The panel, the step editor, and the recording bar are not steps.

On a page with a content iframe, a click or type inside that frame is `in the "<id>" IFrame` plus the inner element. It is not a hover on the iframe host.

```text
record --stop
```

`sentence` is `recording stopped`.

### Step editor pop-out

With the recording still useful to look at, open the editor and pop it out before stop, or start again. The person looks at the page:

```text
record --editor
record --popout --panel=editor
record --popin --panel=editor
```

A real window titled for the step editor means `where` is `window` and `sentence` is `popped out`. `record --popin` returns it to the page (`where` is `page`, `sentence` is `popped in`). `popup blocked` leaves the editor on the page. Either observation is valid. The unattended pop-out above only updates the panel map and does not prove this window.

### Dashboard while the page is up

Leave the browser open. In another invocation:

```text
dashboard xref --role=button --name=Go
```

Add `--project=` when the working directory is not the consumer project. The browser stays up. The JSON still has `summary`, `overlay`, and `chat`. An empty store says `No history mentions button Go.` A store that has retained runs lists those runs, scenario paths, log excerpts, and screenshot paths. `dashboard xref --window` writes one HTML file and does not open a second browser.

When a failure looks intermittent, `dashboard overlap` is the time grouping. Run it, then xref for the element. A failure line appears only when at least two runs in the group share it.

Finish with `live-stop`. Do not add this section to `@all`.
