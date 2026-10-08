# Nested Steps

> **Working feature example:** [`nested-and-block-conditionals.feature`](../maven-consumer-project/src/test/resources/features/nested-and-block-conditionals.feature) demonstrates nested child steps, inherited conditions, and scoped page context. [`area-b-control-flow.feature`](../maven-consumer-project/src/test/resources/features/area-b-control-flow.feature) pins colon and question-mark parents, `::` / `:::`, a falsy middle level, same-level `if` / `else if` / `else`, block and inline `until`, markers, and a table or doc string on a colon child. [`step-state-elements.feature`](../maven-consumer-project/src/test/resources/features/step-state-elements.feature) pins Step Repetition and Step Duration.

Nested steps make the parent-and-child structure of a scenario explicit. They are useful for conditions, scoped page sections, and multi-step branches.

## Nesting levels

Place colons before the Cucumber keyword:

```gherkin
Then , parent step:
: Then , child step
:: Then , grandchild step
```

Each leading colon adds one level.

## Passing a condition and page context

A parent ending in `:` passes both:

- its true/false condition result; and
- its current Selenium page or DOM context.

```gherkin
* , in the "Decision Panel" Test Panel, if the "Submit Request" Button is enabled:
  : * , click the "Submit Request" Button
```

The child runs only when the condition succeeds, and its element lookup remains inside the `Decision Panel`.

## Passing only the condition

A parent ending in `?` passes the condition but not the page context:

```gherkin
* , in the "Decision Panel" Test Panel, the "Submit Request" Button is enabled?
  : * , ensure "Workflow State: ready" Text is displayed
```

The child is conditional but searches from the normal page context.

| Parent ending | Condition inherited | Page context inherited |
|---|---:|---:|
| `:` | yes | yes |
| `?` | yes | no |

A question mark can imply the conditional check without writing `if`:

```gherkin
* , the "Case Found" Text is displayed?
  : * , click the "Open Case" Link
```

## Several levels

```gherkin
* , if the "Submit" Button is enabled:
  : * , if the "Error" Text is not displayed:
    :: * , click the "Submit" Button
    :: * , wait 5 seconds
```

Use separate levels when each condition has a distinct business meaning.

Block `IF:` / `ELSE-IF:` / `ELSE:` uses the same colon levels. A falsy parent does not run the inner `IF`. A truthy parent does. `"abc"` is truthy. `""`, `0`, `false`, and a missing reference are not. Three levels is the same chain with one more colon. A plain step between a false `IF:` and its `ELSE:` does not hide that `IF` from the `ELSE:`.

```gherkin
* IF: "abc":
  : * IF: "":
    :: * , save "no" as "inner"
    : * ELSE:
    :: * , save "else" as "inner"
* IF: "abc":
  : * IF: "abc":
    :: * IF: "abc":
      ::: * , save "yes" as "depth3"
```

See [Block Conditionals](block-conditionals.md).

A falsy parent does not run its children. A following sibling of that parent still runs. `::` is the grandchild level and `:::` is one level deeper. A falsy level in the middle saves nothing beneath it. The next sibling of that falsy step still runs when its own parent was truthy.

A colon child keeps the data table or doc string written on that child. `DT:::city|Paris|` on that child replaces the table for that child only. `DS:::`, like `NOTE:::`, stays an inline marker and does not become a table. The doc string on that child is unchanged.

A default `---startstep` / `---endstep` pair, or a custom marker such as `---area b marker`, runs the body between the markers. Steps before the start marker and after the end marker are skipped. Depth 2 and depth 3 inside the body still run. `SCENARIO: Feature.Scenario.marker` starts the called scenario at that marker.

## `until`

Only a block `until` loops. The parent must end in `:` or `?`. A failing pass runs the body. The pass that finds the condition true stops and does not run the body again. `until the Step Repetition is greater than 3` reads 1, then 2, then 3, runs the body on those three passes, and stops when the check reads 4. Step Duration on that check is the time since the loop's first pass. A child step has its own Step Repetition. Hitting `stepRepeatMaxCount` (default 100) or `stepRepeatMaxTime` (default 3600 seconds) hard-fails the Cucumber scenario. Those ceilings are run-wide. The message names the limit that was hit. Zero and negative are defined in [Repeat ceilings](configuration.md#repeat-ceilings). Names the parser treats specially are listed in [Reserved element names](reserved-element-names.md).

An inline `until`, including a comma `until`, is a one-shot `if`. It does not loop. A falsy inline `until` skips its save. `runUntilOperation` is not the runtime path.

`times` repeats the action and stops on the first throw. It does not continue after a failure. See [Dynamic Steps](dynamic-steps.md).

## `if`, `else if`, and `else`

Related branches must be at the same nesting level:

```gherkin
* , if the "Error" Text is displayed:
  : * , click the "Refresh" Button
* , else if the "Submit" Button is enabled:
  : * , click the "Submit" Button
* , else:
  : * , save "No action available" as "result"
```

Only one branch at that level runs.

## Without a parent ending

A child always has access to scenario values, but it does not automatically inherit a condition or page context unless the parent ends with `:` or `?`.

## Working example

The consumer's [nested-and-block-conditionals.feature](../maven-consumer-project/src/test/resources/features/nested-and-block-conditionals.feature) demonstrates:

- a parent that passes both condition and panel context;
- a question-mark parent that passes only its condition;
- phrase-style block conditions;
- expression-style block conditions; and
- inline branch chains.

[Previous: Configuration Files and Resource Mapping](config-files-and-resource-mapping.md) · [Documentation home](README.md) · [Next: Block Conditionals](block-conditionals.md)
