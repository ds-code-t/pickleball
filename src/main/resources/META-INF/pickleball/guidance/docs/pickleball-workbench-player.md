# Pickleball Workbench Live Player

This document describes the live-player behavior implemented by the Workbench UI on the 2.1.9 line. The canonical Workbench guide is [pickleball-workbench.md](pickleball-workbench.md).

## Architecture boundary

The Workbench distribution and process model is unchanged:

```text
published pickleball JAR
  -> embeds one opaque pickleball-workbench.jar
  -> launcher extracts it and starts `java -jar` in a separate controller JVM

Workbench controller JVM
  -> controller/UI/MCP only
  -> shares only pickleball-control-protocol wire classes
  -> never loads Pickleball core, control API, consumer classes, Cucumber, Selenium, or REST-assured

consumer worker JVM
  -> runs from the synchronized consumer test-runtime classpath
  -> owns Pickleball, Cucumber, DynamicControl, Mapping/ParsingMap/NodeMap, browser, and service behavior
```

The player and Mapping editor do not change this boundary. Swing is a presentation adapter over `WorkbenchServices` / `WorkbenchController`. Automatic buffered execution and add-and-continue use the existing `executeStep` worker contract; Workbench does not invent a second Gherkin matcher.

## Live Scenario Editor

The left-side editor is a session-scoped Gherkin text editor with a player playhead. Clicking a line instantly seeks the playhead, like clicking a waveform. The execution cursor is internal to an active run.

The initial buffer is Workbench-owned sample content. It is not written back to consumer `.feature` files. The default demo is a small browser scenario against the Maven consumer local test site:

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

The Mapping tab is an object editor rather than a get/put/resolve form.

It contains:

1. A **NodeMap** dropdown populated from the actual NodeMaps in the current worker-side `ParsingMap`.
2. One editable JSON text area containing the materialized root object of the selected NodeMap.

The dropdown is populated through the existing Mapping snapshot contract using a reserved neutral protocol reference. The worker resolves the reserved reference to a catalog generated from the current `ParsingMap`; the Workbench sees only neutral snapshot data.

Each catalog entry uses a second reserved reference that resolves back to the same current NodeMap through `MappingControl`. Ordinary NodeMap references continue to behave unchanged.

### Editing

For an ordinary restorable NodeMap:

- change scalar values directly;
- add or delete properties;
- add or edit nested objects;
- add or edit arrays;
- assign an object as a value by entering its JSON object structure.

After a short debounce, valid JSON is restored through the existing `mapping_restore` bridge operation. Invalid intermediate JSON is not sent to the worker.

NodeMap implementations that are not exact ordinary `NodeMap` instances remain inspection-only, preserving the existing restore safety rule.

## Focused validation

The included consumer `@control-bridge` scenario verifies:

- a full `Given CONTROL API TEST STEP` line is parsed in the consumer worker and normalized to the existing step text;
- the current ParsingMap catalog contains at least one NodeMap;
- a catalog reference resolves back to a live NodeMap.

Workbench player/editor unit tests cover click-to-seek, global Play from start, the two Step Editor play actions, wait-at-end / Enter-to-append-and-run, in-place edit of previously executed text, and the non-empty browser demo seed.

Workbench changes should continue to use the repository's focused validation policy:

```powershell
.\gradlew.bat verifyStrictControllerIsolation :pickleball-workbench:test
.\maven-consumer-project\mvnw.cmd -f maven-consumer-project\pom.xml -U test -Dpkb_runvars.pkb_browser=CHROME_HEADLESS -Dpkb_runvars.pkb_parallel=80 -Dpkb_runvars.pkb_tags=@control-bridge
```

Do not use `@all` as Workbench migration validation.
