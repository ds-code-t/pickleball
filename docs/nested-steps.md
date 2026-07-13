# Nested Steps and Conditional Flow

Pickleball supports hierarchical step nesting. Nested dynamic steps can form conditional execution trees similar to structured programming.

Nesting supports:

- conditional gating of descendant steps;
- DOM-context inheritance;
- `if` / `else if` / `else` branches;
- scoped execution trees; and
- logical grouping of actions.

## Nesting syntax

A nested step begins with one or more colons **before** the Cucumber step keyword. Each leading colon represents one nesting level:

```gherkin
Then , parent step:
: Then , child step
:: Then , grandchild step
```

A colon only indicates nesting when it occurs before the step keyword.

This is a nested step:

```gherkin
: Then , click the "Submit" Button
```

This is not a nesting prefix:

```gherkin
Then , click the "Submit" Button:
```

A trailing colon controls what the parent passes to its children.

## Execution tree

A scenario forms a tree containing:

- top-level steps;
- child steps; and
- deeper descendants.

A nested step attempts execution only when its ancestor chain allows it. When an ancestor passes a false conditional state, its gated subtree is skipped.

## Pass condition and context with `:`

When a step ends with `:`, it passes both:

- its Boolean conditional result; and
- its HTML/DOM context

to child and descendant steps.

```gherkin
Then , if the "Submit" Button is enabled:
: Then , click the "Submit" Button
```

If the button is disabled, the child is skipped. If it is enabled, the child executes.

## Equivalent nested structures

These two structures express equivalent logic.

### Chained parent condition

```gherkin
Then , if the "Submit" Button is enabled, and the "Error" Banner is not displayed:
: Then , click the "Submit" Button
: And , wait 5 seconds
```

### Separate nested conditions

```gherkin
Then , if the "Submit" Button is enabled:
: And , if the "Error" Banner is not displayed:
:: Then , click the "Submit" Button
:: And , wait 5 seconds
```

The second form can make each condition and its scope easier to see.

## Branches with `else` and `else if`

An `else` or `else if` branch applies at the same nesting level as its related `if`.

A branch depends on the immediately preceding conditional sibling. It runs only when the relevant earlier branch executed and resolved to false.

### `if` / `else`

```gherkin
Then , if the "Submit" Button is enabled:
: Then , if the "Error" Banner is displayed:
:: Then , save the "Error" Banner as "error text"
: Then , else click the "Submit" Button
```

The innermost save runs when both conditions are true. The sibling `else` runs when the outer condition is true but the inner condition is false.

### `if` / `else if` / `else`

```gherkin
Then , if the "Submit" Button is displayed:
: Then , if the "Error" Banner is displayed:
:: Then , save the "Error" Banner as "error text"
: Then , else if the "Submit" Button is enabled:
:: Then , click the "Submit" Button
: Then , else click the "Refresh" Link
```

Only one branch at that nesting level executes.

## `:` versus `?`

Both endings pass conditional state, but only `:` passes DOM context.

| Ending | Passes conditional result | Passes DOM context |
|---|---:|---:|
| `:` | Yes | Yes |
| `?` | Yes | No |

### Pass condition and DOM context

```gherkin
Then , in the "Accounts" Section, if the "Case Found" Text is displayed:
: Then , click the "Submit" Button
```

The child search remains scoped to the `Accounts` section.

### Pass only the condition

```gherkin
Then , in the "Accounts" Section, if the "Case Found" Text is displayed?
: Then , click the "Submit" Button
```

The condition is passed, but the child searches from the normal DOM context rather than inheriting the `Accounts` section.

## Implied condition with `?`

A trailing `?` can make the conditional intent explicit without writing `if`.

These are equivalent:

```gherkin
Then , if the "Case Found" Text is displayed?
```

```gherkin
Then , the "Case Found" Text is displayed?
```

A concise nested check can therefore be written as:

```gherkin
Then , the "Case Found" Text is displayed?
: Then , click the "Open Case" Link
```

## Default inheritance

Without a trailing `:` or `?`:

- nested steps do not inherit a conditional result;
- nested steps do not inherit DOM context; and
- nested steps do retain access to scenario-state storage.

Use `:` or `?` when a parent is intended to gate descendants.

## Rules to remember

1. Leading colons determine nesting depth.
2. A trailing `:` passes condition and context.
3. A trailing `?` passes condition only.
4. `else if` and `else` relate to branches at the same nesting level.
5. Nesting may continue to any depth, but indentation and consistent colon counts make scenarios easier to maintain.

---

[Previous: Dynamic steps](dynamic-steps.md) · [Documentation home](README.md) · [Next: Keyboard DSL](key-parser-dsl.md)
