# Nested Steps

> **Working feature example:** [`nested-and-block-conditionals.feature`](../maven-consumer-project/src/test/resources/features/nested-and-block-conditionals.feature) demonstrates nested child steps, inherited conditions, and scoped page context.

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
