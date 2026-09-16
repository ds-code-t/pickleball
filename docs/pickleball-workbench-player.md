# Pickleball Workbench Live Player

This document describes the live-player behavior implemented by the Workbench UI on the 2.1.10 line. The canonical Workbench guide is [pickleball-workbench.md](pickleball-workbench.md).

## Architecture boundary

The Workbench distribution and process model is unchanged:

```text
published pickleball JAR
  -> embeds one opaque pickleball-workbench.jar
  -> launcher extracts it and starts `java -cp` in a separate controller JVM

Workbench controller JVM
  -> controller/UI/MCP only
  -> shares only pickleball-control-protocol wire classes
  -> never loads Pickleball core, control API, consumer classes, Cucumber, Selenium, or REST-assured

consumer worker JVM
  -> runs from the synchronized consumer test-runtime classpath
  -> owns Pickleball, Cucumber, DynamicControl, Mapping/ParsingMap/NodeMap, browser, and service behavior
```

The player, picker, Mapping editor, Terminal, and Diagnostic explorer do not change this boundary. Swing and WebView are presentation adapters over `WorkbenchServices` / `WorkbenchController`. Automatic buffered execution and add-and-continue use the existing `executeStep` worker contract; Workbench does not invent a second Gherkin matcher. WebView JavaScript never executes Gherkin.

## Feature and scenario picker

The left rail lists scenarios from `.feature` files in the synchronized consumer project: manifest source roots, conventional `src/test/resources/features`, live merged `features/` resources, and an explicit project-owned `pkb_features` value when present. It does not crawl an unrelated git worktree.

Name and tag filtering is the primary UI. Type a scenario name and choose a match mode: starts with, contains (default), ends with, or full match. All four modes are case-insensitive and match only the Gherkin `Scenario` / `Scenario Outline` title. Include tags must all be present (AND). Exclude tags drop a scenario if it has any of them (NOT). Tag fields accept values with or without a leading `@` and split on commas and/or whitespace. Empty include/exclude means no tag constraint. Feature, Rule, scenario/outline, and Examples tags are inherited as Cucumber does; Workbench parses them from the same catalog files and does not call Cucumber from the controller JVM.

Feature-file selection is secondary and collapsed behind **Filter by feature**. With no feature selected, name/tag filters apply to every catalog scenario. Opening that panel still toggles browse mode between Gherkin Feature name and file name + directory path, and click still selects or deselects features. Scenario Outlines expand to selectable Examples rows. Clicking a result opens the whole originating `.feature` file and sets that scenario (and optional Examples row) as the play target. The default demo remains loaded until a scenario is chosen. **Save** writes the editor buffer to the original file after confirmation. Ctrl+click or **Open target** on a `RUN` / `CALL` / `data:/` step opens the callee in a new tab; the caller tab stays pinned. The left **Step definition** panel shows consumer Java glue when it can be resolved, or that the line is a Pickleball dynamic step.

## Live Scenario Editor

The center editor is one ordinary Gherkin text document over `LiveScenarioPlayer`. Tab at the start of a line, or while only leading colons/spaces sit before the caret, inserts one extra Pickleball leading `:`; Shift-Tab removes one leading `:` if present. Mid-line Tab inserts a space and does not jump to another Swing control. Typing the first letters of a Gherkin keyword after optional leading colons/whitespace offers completion (Feature, Rule, Background, Scenario, Scenario Outline, Examples, Given, When, Then, And, But, `*`, IF, ELSE, ELSE-IF). Tab or Enter accepts the selected keyword and inserts a trailing space. When the completion popup is open, Tab accepts it; otherwise Tab at the indent prefix inserts `:`. Play, Step, and From Here keep using `LiveScenarioPlayer`. **Play** executes a derived plan: Background steps plus the selected scenario, with one Examples row substituted when selected. Clicking a line instantly seeks the playhead. The top player bar shows the Pickleball version.

Workbench chose OpenJFX `WebView` + `JFXPanel` over JCEF so Mapping and Diagnostic explorer stay Workbench-only Maven dependencies resolved onto the forked controller classpath. JDK 21 does not ship a modern browser component. If JavaFX cannot start, those panels use their text fallbacks. The live editor is always the Gherkin text buffer. The JavaFX unnamed-module warning on Windows is expected and is not a hang; the player window is shown before WebView starts.

The initial buffer is Workbench-owned sample content. It is not written back to consumer `.feature` files unless you use **Save** on a picker-loaded scenario and confirm the copy. The default demo is a small browser scenario against the Maven consumer local test site:

```gherkin
Feature: Workbench Live Scenario

Scenario: Open the local test site
  Given navigate to: URL.home
  When , ensure "Pickleball Test Lab" Text is displayed
  And , click the "Open Forms Playground" Link
  Then , ensure "Forms Playground" Text is displayed
```

`URL.home` is ordinary consumer config, not a machine-specific filesystem path. Users can edit any line in place, including Gherkin that already executed. Stable line identities are preserved across in-place edits.

### Controls

- Clicking a scenario step instantly moves the playhead to that step.
- The global **Play** button always creates a fresh interactive scenario context and runs from the first executable scenario step, not from the current playhead.
- **Pause** prevents the next automatic step from starting. An already in-flight step is allowed to finish.
- **Stop** stops automatic advancement but does not kill the consumer worker. Worker lifecycle remains under **Session**.
- The Step Editor has two execution actions:
  - **Step** executes only the Step Editor text against the current paused live context and leaves automatic scenario playback paused.
  - **From Here** creates a fresh interactive scenario context and treats the selected/playhead executable step as the first step of that run, then continues through the remaining buffer.
- Fresh scenario playback restarts the consumer worker so browser, Mapping, service, and other side effects from a previous run do not leak into a new **Play** or **From Here** run.
- Automatic **Play** / **From Here** send each executable live-buffer line through `executeStep`. While the player is `RUNNING`, that controller call is the single playhead owner: success advances to the next executable line, failure pauses on the failed line. The Swing Play loop then refreshes and schedules the next line without remaking the same mark. An attached agent `execute_step` uses the same follow so the spectator playhead stays aligned. Isolated **Step** pauses first, so it does not move the playhead. Marking an already-consumed step is a no-op, so a leftover UI callback cannot abort playback after a successful worker step.
- Reaching the end while playing changes the player to **Waiting for step** rather than stopping. Typing a new step and pressing **Enter** appends it to the end of the live scenario and executes it as part of the same live run.
- **Ctrl+Enter** updates the selected line in place. The whole-scenario editor also accepts ordinary typing at any line.
- The editor highlights the current playhead line. Successful lines do not retain checkmarks or become locked.

First/Step Back, when present, remain navigation-only and do not rewind runtime side effects. The current player relies on click-to-seek instead of those buttons.

## Full Gherkin line execution

The Workbench sends the displayed line unchanged over the existing `execute_step` bridge operation.

If the input starts with `Given`, `When`, `Then`, `And`, `But`, or `*`, `DynamicControl` parses that one line using Pickleball/Cucumber inside the consumer worker and executes the resulting detached step text.

The controller does not strip keywords or load a Gherkin parser.

Historical raw detached-step input remains supported.

## Mapping tab

The Mapping tab is a structured object/property editor rather than a get/put/resolve form.

It contains:

1. A **NodeMap** dropdown populated from the actual NodeMaps in the current worker-side `ParsingMap`.
2. A property tree for that NodeMap. Each row edits key, value text, and type (`string`, `numeric`, `boolean`, `object-as-JSON`, `object-as-XML`).

The dropdown is populated through the existing Mapping snapshot contract using a reserved neutral protocol reference. The worker resolves the reserved reference to a catalog generated from the current `ParsingMap`; the Workbench sees only neutral snapshot data.

Each catalog entry uses a second reserved reference that resolves back to the same current NodeMap through `MappingControl`. Ordinary NodeMap references continue to behave unchanged.

### Editing

For an ordinary restorable NodeMap:

- change scalar values in place and choose their type;
- add or rename properties;
- assign JSON or XML object text, which is decoded and sent as a structured `mappingPut` value;
- rename keys through `mappingRestore` of the current object.

Invalid typed text is not sent to the worker. NodeMap implementations that are not exact ordinary `NodeMap` instances remain inspection-only, preserving the existing restore safety rule.

## Terminal

The Terminal tails the worker stdout/stderr files Workbench already creates under `.pickleball/workbench/logs/`. Filter by `TRACE`, `DEBUG`, `INFO`, `WARNING`, or `ERROR`. Logs continue as the playhead moves. This is not MCP stdout and is not a fabricated Workbench-only activity dump. Unmarked worker output is shown at `INFO`.

## Diagnostic Log Explorer

The explorer is a rewind/play/focus timeline of retained Pickleball diagnostic runs. The run dropdown uses catalog ids plus retained outcome/purpose when those fields exist. Each step shows its Gherkin, INFO+ log, and the screenshot taken while that step ran — or an explicit gap when no PNG was retained. Play stops at the last step. Denser layers follow the repository evidence order and only open when the retained files exist. If `reports/diagnostic-runs/run-catalog.json` is missing, the panel stays empty and says so.

## Watched-agent control lease

The live player is a collaborative testing space, not a second editor. Swing and an attached agent share one `LiveScenarioPlayer` in the Workbench controller.

- A human can work alone: edit/play the live buffer, then **Save** asks before copying into the original scenario in the original `.feature` file.
- An agent attaches to the running UI through `.pickleball/workbench/attach.json` (localhost JSON tools over the same `WorkbenchServices`). It must not start a second Workbench JVM or worker.
- After `workbench_request_control`, Swing play/edit/picker/filter/editor-view/mapping/save/worker controls lock. A banner shows the agent name and `currentAction`. **Take control** always works and cancels in-flight Save permission waits.
- `workbench_request_save` is the only original-feature write path for the agent. With the UI attached it blocks on Allow/Deny. Deny writes nothing. Headless stdio MCP may hold the lease without a banner; Save is still an explicit tool.

See [pickleball-workbench.md](pickleball-workbench.md) for attach discovery, tool names, and stdout rules.

## Focused validation

The included consumer `@control-bridge` scenario verifies:

- a full `Given CONTROL API TEST STEP` line is parsed in the consumer worker and normalized to the existing step text;
- the current ParsingMap catalog contains at least one NodeMap;
- a catalog reference resolves back to a live NodeMap.

Workbench player/editor unit tests cover picker name/tag/feature filtering (including Feature-level tag inheritance), leading-colon buffer helpers, TEXT-only live editor (no Blocks toggle), colon-Tab indent and keyword completion, click-to-seek, global Play from start, the two Step Editor play actions, wait-at-end / Enter-to-append-and-run, in-place edit of previously executed text, leftover Play-loop playhead marks after `executeStep`, typed Mapping edits through `WorkbenchServices`, the non-empty browser demo seed, control-lease lock/Take control, permission grant/deny, and Save not writing without approval.

Workbench changes should continue to use the repository's focused validation policy:

```powershell
.\gradlew.bat verifyStrictControllerIsolation :pickleball-workbench:test
.\maven-consumer-project\mvnw.cmd -f maven-consumer-project\pom.xml -U test -Dpkb_runvars.pkb_browser=CHROME_HEADLESS -Dpkb_runvars.pkb_parallel=80 -Dpkb_runvars.pkb_tags=@control-bridge
```

Do not use `@all` as Workbench migration validation.
