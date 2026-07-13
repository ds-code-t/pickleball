# Dynamic Steps

Pickleball extends Cucumber with dynamic steps: test instructions that are parsed and executed at runtime instead of requiring a dedicated Java method for every sentence.

A dynamic step begins with a Cucumber keyword followed by a comma:

```gherkin
Then , click the "Submit" Button
```

Everything after the comma is interpreted by Pickleball's dynamic execution engine.

## Phrase delimiters

Dynamic phrases may be separated by:

```text
,  ;  .  :  ?
```

The comma is the general phrase separator. Colons and question marks also participate in nested-flow and context inheritance; see [Nested steps and conditional flow](nested-steps.md).

## Elements

An element describes a DOM entity, browser object, or supported system object.

Typical element bases include:

- `Button`
- `Link`
- `Checkbox`
- `Textbox`
- `Dropdown`
- `Window`
- `Alert`

Plural forms may be used where the operation supports multiple matches.

### Full text match

```gherkin
Then , click the "Submit" Button
Then , click the "Refresh" Link
```

### Partial text match

```gherkin
Then , click the Button containing "Submit"
Then , select the Dropdown starting with "State"
```

## Quote types

| Syntax | Matching behavior |
|---|---|
| `"text"` | Whitespace-normalized, case-sensitive text |
| `'text'` | Whitespace-normalized, case-insensitive text |
| `` `text` `` | Exact or minimally normalized text |

Examples:

```gherkin
Then , click the "Submit" Button
Then , enter "Hello" in the 'comments' Textbox
Then , verify Text contains `Thank you. A confirmation email has been sent`
```

## Element modifiers

Elements may include state modifiers such as:

- `checked` / `unchecked`
- `selected` / `unselected`
- `expanded` / `collapsed`
- `enabled` / `disabled`
- `required` / `non-required`
- `blank` / `empty`

Examples:

```gherkin
Then , verify the checked "Agree" Checkbox is displayed
Then , click the disabled 'submit' Button
Then , verify the collapsed Row starting with "Account Number" is displayed
```

## Positioning

Use a position when more than one element matches:

- `first`
- `last`
- `1st`, `2nd`, `3rd`, `4th`, and so on

Examples:

```gherkin
Then , click the 7th "Submit" Button
Then , click the last 'refresh' Link
Then , verify the 3rd Text containing `Thank you` is displayed
```

When no position is supplied, Pickleball normally selects the first match.

## Values

Values may be quoted strings, numbers, or typed durations:

```gherkin
Then , enter "text example" in the "Comment" Textbox
Then , save 10 as "expected count"
Then , wait 3 minutes
Then , wait 5 seconds
```

Numeric forms may include descriptions such as:

```text
10 integer
0.232 decimal
```

## Actions

Common action keywords include:

| Action | Purpose |
|---|---|
| `click` | Left-click an element |
| `double click` | Double-click an element |
| `right click` | Open the context-click action |
| `hover` / `move` | Move the pointer over an element |
| `enter` | Enter text |
| `overwrite` | Clear existing text, then enter text |
| `clear` | Clear a field |
| `select` | Choose a dropdown option |
| `scroll` | Scroll to an element |
| `wait` | Wait for a duration or condition |
| `save` | Store a value in scenario state |
| `attach` | Upload or attach a file |
| `switch` | Switch window, tab, frame, or context |
| `close` | Close an element, window, or tab |
| `accept` | Accept an alert |
| `dismiss` | Dismiss an alert |
| `press` | Perform keyboard input |

Examples:

```gherkin
Then , click the "Submit" Button
Then , wait 3 seconds
Then , enter "John" in the "First Name" Textbox
Then , overwrite "new value" in the "Search" Textbox
```

For advanced key combinations, see the [Keyboard DSL](key-parser-dsl.md).

## Assertions

Assertions produce Boolean results and support negation with `not`.

### Comparisons

- `equals`
- `starts with`
- `ends with`
- `contains`
- `matches`
- `is less than`
- `is greater than`

### State checks

- `is displayed`
- `is selected` / `is unselected`
- `is checked` / `is unchecked`
- `is enabled` / `is disabled`
- `is required` / `is non-required`
- `has value`
- `is blank`

Examples:

```gherkin
Then , verify the "Submit" Button is disabled
Then , verify the "Case Missing" Text is not displayed
Then , verify the "Total" Text contains "10"
```

## Hard and soft assertions

A hard assertion stops normal scenario execution when it fails:

```gherkin
Then , user ensures that the "Refresh" Link is displayed
```

Hard assertion words:

- `ensure`
- `ensures`

A soft assertion records failure while allowing later scenario work to continue:

```gherkin
Then , user verifies that the "Agree" Checkbox is unchecked
```

Soft assertion words:

- `verify`
- `verifies`

## Chained assertions

Use `and` and `or` to combine checks:

```gherkin
Then , verify Error Banner is not displayed and the 3rd Link does not contain 'failed'
```

- `and` requires all parts to pass.
- `or` can succeed as soon as one part is true.

## Conditional flow

Dynamic sentences may use:

- `if`
- `else if`
- `else`
- `until`

Example:

```gherkin
Then , if the "Refresh" Link is displayed, click the "Refresh" Link, else save Link as "Displayed Link Text"
```

Repeat-until example:

```gherkin
Then , until the "Case Ready" Text is displayed, wait 3 seconds, click the "Refresh" Link
```

For hierarchical branches spanning several Gherkin lines, use [nested steps](nested-steps.md).

## Context phrases

Context phrases narrow where Pickleball searches for later elements:

- `for`
- `from`
- `in`
- `after`
- `before`
- `between`
- `in between`

Example:

```gherkin
Then , from the "Results" Table, click the "Case" Link
```

Nested context:

```gherkin
Then , after the "Returned Results" Text, in the "Results" Table, click the "Case" Link
```

Context applies to later phrases in the same dynamic sentence unless the operation changes or resets it.

## General structure

A dynamic step can combine these parts:

```text
Cucumber keyword , [context] [condition] [action or assertion]
```

The main building blocks are:

1. elements;
2. values;
3. actions;
4. assertions;
5. conditional flow;
6. context control; and
7. scenario-state storage.

---

[Previous: Custom definitions](custom-element-definitions.md) · [Documentation home](README.md) · [Next: Nested steps](nested-steps.md)
