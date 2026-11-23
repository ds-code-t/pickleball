---
name: pickleball-functionality-change
description: Use whenever adding, changing, fixing, refactoring, or removing Pickleball framework behavior, DSL syntax, mappings, templates, Selenium behavior, service calls, configuration, Cucumber integration, consumer compatibility, or public APIs.
---

# Pickleball Functionality Change

Apply this workflow automatically when a user requests a functionality change.

## 1. Load project context

Read:

- `/AGENTS.md`
- `/docs/agent/feature-map.md`
- The relevant existing guide
- Relevant framework tests and Maven consumer scenarios

Do not ask the user to repeat repository setup, dependency model, test-server architecture, Java version, documentation policy, or definition of done.

## 2. Establish the current contract

Locate and compare:

- Implementation and callers
- Focused tests
- Gherkin phrases and scenarios
- Consumer configuration, call definitions, local endpoints, and pages
- Documentation and examples

If they disagree, resolve the inconsistency using established consumer behavior and the instructions in `AGENTS.md`.

## 3. Determine impact

Identify every affected surface:

- Framework source or AspectJ integration
- Unit/component tests
- Maven consumer features and support resources
- README or canonical guide
- Feature map and repository index
- Build or dependency configuration

Prefer backward-compatible behavior unless the user explicitly requests otherwise.

## 4. Implement coherently

Make the smallest complete change. Do not perform unrelated refactoring.

Add focused tests and an executable Maven consumer scenario for consumer-visible behavior whenever practical.

Update the canonical documentation in the same task. Documentation maintenance is part of the functionality change, not a separate optional step. Page pushes go through `pkbSurface` into `.pickleball/agent-events.jsonl`; agents read them with `events --follow`. `export-guidance --warm` is the warm path (`pingMs` on the second ping, target at most 1 s). A fresh JVM that prints elapsedMs for one verb is classload, not that ping (about 1.1-1.2 s on a 2-CPU host; the check allows 2 s). A down controller runs the verb in-process (no 30 s wait). Do not use `/proc/<pid>` to decide if that controller is alive. `PKB_NO_AGENT_HOP=1` still forces in-process. Empty consumer glue is `noop:true`. Plain `validate` lists it. `pkb_noop_glue=fail` fails the step, including via `--runvar`. A covering loading mask blocks a click until it clears, or the click fails. A click is applied when the page or that element's own state changes. Hover on a visible element is applied and dispatched. A step names the frame it entered and restores the document it started in. `state.browser` is down when the recorded Chrome pid is dead. `live-stop` clears the consumer session. `leaseLeft=0` is not a held lease. `ui-poll` reports `browser_lost` once. `gc-runs --dry-run` lists paths and deletes nothing. Any other `gc-runs` flag is rejected and deletes nothing.

When authoring reusable `RUN` examples, prefer one bare table-driven `RUN` with a `RunType` column and one row per invocation. Related rows may mix regular scenarios, component scenarios, and service calls. Use inline `RUN ...` type, quoted `RunKey`, and inline selector syntax as shorthand only when those values are common to the rows or eliminate the table. Preserve executable coverage for both the canonical table form and supported shorthand variations. Replies put one list in `items` and drop an equal duplicate. `--fields` returns only those keys. `--budget` caps the JSON. Short ids are the default and work on `form-fill`, `highlight`, `border`, `magnifier`, `spotlight`, and `label`. A short id is not expanded to the accessible name; a placement uses the element id or `frame:backend`. `act --query` and `--ref` accept that short id. MCP `session` or `sessionId` attaches to that same live start host or isolate worker and does not need a paused worker. A strict miss is `unknown session`. CLI `--session` falls back to the current live session. Remaining MCP counts (events afterSequence and limit, inspect maxElements and limit, leaseSeconds, record index, ui ttl, ui_poll after and wait) accept a number or a string. A whole number is forwarded without `.0`. A blank record field is omitted. An omitted refine action is inferred, and an omitted pop action is popout. Cursors are per channel. Push rows include `sessionId`. Frame ids are CDP ids. Person-facing controls show `sending`, `delivered`, and `read`. `ask-and-wait` splits `--choices` on commas or a JSON array into one button each, returns only the clicked choice, shows `Answer sent: <choice>` for about 1.5 seconds, and does not drop an answer that is already stored. A guide input ends on Enter, blur, or `expect` (not a typing pause), finds a digit-leading id, refuses a missing current target without listing it in Steps, and removes field step-done badges when the guide ends. Checklist rows are checkboxes. `--id` is the placement id and `--check` is the 1-based row. Collaborative recording is `record --start`, then `record --script` for parameterized dynamic steps, `record --save --name=`, and `record --run --param=name=value`. A person click or type inside a content iframe is recorded as that inner element and frame (`in the "<id>" IFrame`), not as a hover on the iframe host. Hover and focus are not recorded unless noise mode is on. The panel, editor, recording bar, and placements are not recorded. Consecutive edits of one field are one step. A tab stays a Tab, `--inject --role` is honored, the accessible name wins over the field value, `form-fill` is one step, and non-ASCII labels are not garbled. `--rename` changes that exact parameter. `--literal` fails on an unknown name. `--context` is a `#` comment. `--run` replays on a live page, or points at `execute-steps --file=` when there is none. Person rows are on the recording stream. `selftest record` checks an iframe click and type. `capture-steps` drops question-card answers and does not use a placement id as a ref. The in-page step editor (`record --editor`) is collapsible, resizable, and draggable. `--edit`, `--move`, `--nest`, `--outdent`, `--add`, and `--delete` write it back; drag-and-drop, Tab, and Alt+Up/Down do the same. `--popout --panel=editor|chat` and `--popin` move the step editor or the chat panel into its own window. Interaction rows use `event=interaction`. Read them with `events --follow --type=interaction`. `events --follow` and `ui-poll` start at now unless `--cursor` or `--after` is set (`--cursor=0` and `--after=0` still replay). `--until=human` is a non-agent chat message, an answer, or an agent-UI click, not a keystroke. Input, change, select, and Enter/Tab/Escape are pushed only while recording is on. `chat-turn` skips a message the panel already shows as Read. Popping chat back in keeps that Read mark and still shows an unread agent message. A concrete value in person-facing text is quoted (`Enter "abc" in this textbox`) and painted as a `.pkb-value` chip on overlays, callouts, guides, question cards, the step editor, chat, and HTML reports. Agents emit those quotes. The stored answer stays the unquoted choice. Injected UI carries `data-pkb-meta="true"`, an overlapping `data-pkb-purpose` (`panel`, `guide`, `placement`, `editor`, `callout`, `recorder`, or `layer`), and a `pkb-` prefix when an id is assigned. Recording treats a tagged node as own UI. Select it with `[data-pkb-meta="true"]` or `[data-pkb-purpose~="guide"]`. The panel tabs are Chat, Guide, and Recorder. The step editor is the Steps window. A minimized or closed panel or editor leaves a corner pill that reopens it. The recording bar says Recording or Paused with Pause/Resume, Keep step, and Drop step. The historical dashboard is one static HTML file from the embedded store: sticky filters, a colored timeline, entity and case rows that fill the entity filter, and run links grouped as Features, Data, Artifacts, and Commit. It does not start a server and it does not change the store. `dashboard query` prints lean JSON for a time range, status, feature, entity, text, or `--element` hint: `name`, `ref`, `scenarios`, `artifacts`, `data`, and `summary`. MCP `workbench_dashboard_query` forwards that command. `dashboard xref` takes `--id`, `--selector`, or `--role` and `--name` and returns runs, scenario paths, log excerpts, screenshot paths, overlapping runs, summary, overlay, and chat. `--window` writes one HTML file. MCP `workbench_dashboard_xref` forwards that command. `dashboard overlap` groups runs that share a time and lists a failure line only when at least two runs in the group share it. A blank finish is a point at the start. For an intermittent failure, run overlap, then xref for the element. MCP `workbench_dashboard_overlap` forwards that command. A live magnifier scales its copy around the center so the label stays in the oval; snap within and depth include shadow and iframe content without charging a document, shadow root, html, body, or head as a level; an icon-prefixed link, including a glued prefix such as account_circleDan, resolves to the anchor; explain and act share shown-or-not visibility, where visible is the element and applied false with no visible change is the effect. watch mutation and change notice a replaced iframe document and a changed selected tab; a click under a spinner or loading mask waits for the act timeout and fails when the mask still covers it. confirm and discover summary counts are this launch only, so a passed summary does not keep an earlier attempt's failures; a start that does not become healthy names the stage and stops the partial Chrome; screenshot --out is closed before the reply; isolate and session-start seed a missing run id. Discover, confirm, and the first isolate spend their time on the scenario run and the workspace copy. A warm classpath call is the fast path after that copy exists. A live page uses start instead of a worker restart. Those notes do not claim a faster copy.

The page engine is Chrome DevTools. Do not pass `--engine=` or `--browser=selenium` (`launch` still names Selenium Manager as the driver downloader). Live Edge snapshots skip when `microsoft-edge` is not on PATH. Live AT-SPI snapshots skip when there is no session bus (`no AT-SPI bus`). Do not start a fake bus. `observe --request` keeps `Network.requestWillBeSent` and `observe --response` keeps response and loading rows; `*` keeps every row of that kind, and both flags keep a row that matches either one. `observe --follow` and `log-tail --follow` return in this process (they do not leave a child). `selftest ui --component=feedback` checks `sending`, `delivered`, and `read` without Chrome. `confirm` and `discover` keep the larger of the index failure count and the Surefire count. Replies unescape one quote level, drop a long `ref` when a short id exists, and replace a long string handle with that id. The consumer recording example is `maven-consumer-project/src/test/resources/features/recorded-collaboration.feature`. It is the shape `record --export --format=feature` writes: dynamic steps, an Examples row, and the editor, edit, move, nest, add, delete, popout, and popin verbs. It navigates to `URL.dynamicSteps`, the local page `site/dynamic-steps.html`. Do not invent a second feature for the same path.

## 5. Maintain agent context

Update `docs/agent/feature-map.md` when capability ownership, canonical examples, public syntax, or contracts change.

Run:

```shell
python scripts/refresh_agent_index.py
```

when indexed files are added, moved, or removed.

## 6. Validate

Run the narrowest relevant test first, followed by:

```shell
python scripts/verify_agent_contract.py
python scripts/refresh_agent_index.py --check
./gradlew test
```

For consumer-visible changes also run:

```shell
./gradlew publishToMavenLocal
./maven-consumer-project/mvnw -f maven-consumer-project/pom.xml -U test -Dpkb_browser=CHROME_HEADLESS
```

## 7. Report

Summarize:

- Behavior implemented
- Public compatibility implications
- Tests and consumer scenarios added or changed
- Documentation updated
- Commands executed and results
- Anything not validated and why
