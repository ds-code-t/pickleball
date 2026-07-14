# Nested Steps and Conditional Flow

Nested steps show that one part of a business flow belongs beneath another. They are useful for conditions, scoped page sections, and multi-step branches.

## Nesting syntax

Place one or more colons before the Cucumber keyword. Each leading colon adds one level:

```gherkin
Then , parent step:
: Then , child step
:: Then , grandchild step
```

A colon before the keyword controls nesting:

```gherkin
: Then , click the "Submit" Button
```

A colon at the end of a parent controls what the parent passes to its children:

```gherkin
Then , if the "Submit" Button is enabled:
: Then , click the "Submit" Button
```

## Reading the execution tree

A scenario forms a visible parent-and-child tree:

- top-level steps begin without a leading colon;
- child steps begin with one colon;
- grandchildren begin with two colons; and
- deeper levels continue the same pattern.

A child runs only when the conditions inherited from its parents allow it to run.

## Passing a condition and page context with `:`

A trailing colon passes both:

- the parent’s true/false result; and
- the page or DOM context established by the parent.

```gherkin
Then , in the "Accounts" Section, if the "Case Found" Text is displayed:
: Then , click the "Open Case" Link
```

The child runs only when the text is displayed, and its element search remains inside the `Accounts` section.

## Passing only the condition with `?`

A trailing question mark passes the true/false result but not the parent’s page context:

```gherkin
Then , in the "Accounts" Section, if the "Case Found" Text is displayed?
: Then , click the "Open Case" Link
```

The child is still conditional, but it searches from the normal page context.

| Parent ending | Passes condition | Passes page context |
|---|---:|---:|
| `:` | Yes | Yes |
| `?` | Yes | No |

## Implied condition with `?`

A trailing `?` can make a check conditional without writing `if`:

```gherkin
Then , the "Case Found" Text is displayed?
: Then , click the "Open Case" Link
```

This has the same conditional meaning as:

```gherkin
Then , if the "Case Found" Text is displayed?
: Then , click the "Open Case" Link
```

## One combined condition or several nested conditions

These structures express similar business rules.

### Combined condition

```gherkin
Then , if the "Submit" Button is enabled, and the "Error" Banner is not displayed:
: Then , click the "Submit" Button
: And , wait 5 seconds
```

### Separate levels

```gherkin
Then , if the "Submit" Button is enabled:
: And , if the "Error" Banner is not displayed:
:: Then , click the "Submit" Button
:: And , wait 5 seconds
```

Use the form that makes the rule easiest to read. Separate levels are often clearer when each condition has a different business meaning.

## `if`, `else if`, and `else`

Place related branches at the same nesting level.

### `if` and `else`

```gherkin
Then , if the "Submit" Button is enabled:
: Then , if the "Error" Banner is displayed:
:: Then , save the "Error" Banner as "error text"
: Then , else click the "Submit" Button
```

The `else` belongs to the inner conditional because it is at the same level as that inner `if`.

### `if`, `else if`, and `else`

```gherkin
Then , if the "Submit" Button is displayed:
: Then , if the "Error" Banner is displayed:
:: Then , save the "Error" Banner as "error text"
: Then , else if the "Submit" Button is enabled:
:: Then , click the "Submit" Button
: Then , else click the "Refresh" Link
```

Only one branch at that level runs.

## Without `:` or `?`

A nested step still has access to values saved in the scenario, but it does not automatically inherit a condition or page context unless the parent ends with `:` or `?`.

Use those endings whenever a parent is meant to control its descendants.

## Readability guidelines

1. Match the number of leading colons to the intended nesting level.
2. Keep related `if`, `else if`, and `else` branches at the same level.
3. Use `:` when children should inherit both the condition and the current page section.
4. Use `?` when children should inherit only the condition.
5. Split complex rules into several nested levels when that makes the business meaning clearer.
6. Avoid nesting so deeply that the flow becomes difficult to scan.

For condition branches whose control details should stay out of normal reports, see [Block Conditionals](block-conditionals.md).

---

[Previous: Shared Configuration Files](config-files-and-resource-mapping.md) · [Documentation home](README.md) · [Next: Block Conditionals](block-conditionals.md)
