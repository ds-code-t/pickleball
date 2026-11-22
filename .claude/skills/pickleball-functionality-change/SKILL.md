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

Update the canonical documentation in the same task. Documentation maintenance is part of the functionality change, not a separate optional step. Page pushes go through `pkbSurface` into `.pickleball/agent-events.jsonl`; agents read them with `events --follow`. `export-guidance --warm` is the warm path (`pingMs` on the second ping, target at most 1 s). A down controller runs the verb in-process (no 30 s wait). Do not use `/proc/<pid>` to decide if that controller is alive. `PKB_NO_AGENT_HOP=1` still forces in-process. Empty consumer glue is `noop:true`. Plain `validate` lists it. `pkb_noop_glue=fail` fails the step, including via `--runvar`. A covering loading mask blocks a click until it clears, or the click fails. A click is applied when the page or that element's own state changes. Hover on a visible element is applied and dispatched. A step names the frame it entered and restores the document it started in. `state.browser` is down when the recorded Chrome pid is dead. `live-stop` clears the consumer session. `leaseLeft=0` is not a held lease. `ui-poll` reports `browser_lost` once. `gc-runs --dry-run` lists paths and deletes nothing. Any other `gc-runs` flag is rejected and deletes nothing.

When authoring reusable `RUN` examples, prefer one bare table-driven `RUN` with a `RunType` column and one row per invocation. Related rows may mix regular scenarios, component scenarios, and service calls. Use inline `RUN ...` type, quoted `RunKey`, and inline selector syntax as shorthand only when those values are common to the rows or eliminate the table. Preserve executable coverage for both the canonical table form and supported shorthand variations. Replies put one list in `items` and drop an equal duplicate. `--fields` returns only those keys. `--budget` caps the JSON. Short ids are the default and work on `form-fill`, `highlight`, `border`, `magnifier`, `spotlight`, and `label`. Cursors are per channel. Push rows include `sessionId`. Frame ids are CDP ids. Person-facing controls show `sending`, `delivered`, and `read`. `ask-and-wait` returns only the clicked choice and does not drop an answer that is already stored. A guide input waits for a typing pause, finds a digit-leading id, and refuses a missing current target. Checklist rows are checkboxes. `--id` is the placement id and `--check` is the 1-based row. Collaborative recording is `record --start`, then `record --script` for parameterized dynamic steps, `record --save --name=`, and `record --run --param=name=value`. The in-page step editor (`record --editor`) is collapsible, resizable, and draggable. `--edit`, `--move`, `--nest`, `--outdent`, `--add`, and `--delete` write it back; drag-and-drop, Tab, and Alt+Up/Down do the same. `--popout --panel=editor|chat` and `--popin` move the step editor or the chat panel into its own window. Interaction rows use `event=interaction`. Read them with `events --follow --type=interaction`.

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
