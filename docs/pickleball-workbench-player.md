# Pickleball Workbench Live Player

This document describes the live-player behavior implemented by the Workbench UI drop-in for the 2.1.9 branch.

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

The player and Mapping editor do not change this boundary.

## Live Scenario Editor

The left-side editor is a player-oriented scenario buffer. There is no separately user-controlled playhead. The user's selected line is the editing/navigation target; the execution cursor is transient and exists only while a run is active.

The initial buffer contains a small working smoke scenario:

```gherkin
Feature: Workbench Live Scenario

Scenario: Quick player smoke test
  Given ---workbench-player-smoke-1
  And ---workbench-player-smoke-2
  Then ---workbench-player-smoke-3
```

Pickleball core already owns the `---...` marker definition as a guaranteed no-op, so this smoke scenario does not depend on consumer-specific glue.

### Controls

- The global **Play** button always creates a fresh interactive scenario context and runs from the first executable scenario step.
- **Pause** prevents the next automatic step from starting. An already in-flight step is allowed to finish.
- **Stop** stops automatic advancement but does not kill the consumer worker. Worker lifecycle remains under **Session**.
- The Step Editor has two execution actions:
  - **Step** executes only the Step Editor text against the current paused live context and leaves automatic scenario playback paused.
  - **From Here** creates a fresh interactive scenario context and treats the selected executable step as the first step of that run, then continues through the remaining scenario-buffer steps.
- Fresh scenario playback restarts the consumer worker so browser, Mapping, service, and other side effects from a previous run do not leak into a new **Play** or **From Here** run.
- Reaching the end while playing changes the player to **Waiting for step** rather than stopping. Adding another step while waiting executes it immediately in the current live context.
- **Enter** inserts a new step after the selected line. With no selection, it appends after the last executable scenario step.
- **Ctrl+Enter** updates the selected executable step. Steps remain editable after earlier runs because a later **Play** or **From Here** establishes a fresh scenario context.
- The editor shows only a transient `▶` on the current/next execution line. Successful lines do not retain checkmarks or become grayed out.

Selection does not mutate runtime state. Choosing **From Here** is the explicit action that starts a new fresh run from that selected step.

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

Workbench changes should continue to use the repository's focused validation policy:

```powershell
.\gradlew.bat verifyStrictControllerIsolation :pickleball-workbench:test
.\maven-consumer-project\mvnw.cmd -f maven-consumer-project\pom.xml -U test -Dpkb_runvars.pkb_browser=CHROME_HEADLESS -Dpkb_runvars.pkb_parallel=80 -Dpkb_runvars.pkb_tags=@control-bridge
```

Do not use `@all` as Workbench migration validation.
