# Dynamic Steps

Dynamic steps let a feature file describe behavior directly in business language. A scenario can name an element, perform an action, check a result, save a value, or branch without needing a separate Java step for every sentence.

A dynamic step begins with a Cucumber keyword followed by a comma:

```gherkin
Then , click the "Submit" Button
```

Everything after the first comma is read as one or more Pickleball phrases.

## Phrases and separators

A dynamic step is divided into phrases. A phrase may contain:

- a page or business context;
- an action;
- an assertion;
- a condition;
- an element; or
- a value.

Separate phrases explicitly, usually with a comma:

```gherkin
Then , from the "Account" Section, click the "Save" Button, and verify the "Saved" Text is displayed
```

This step contains three phrases:

1. `from the "Account" Section`
2. `click the "Save" Button`
3. `and verify the "Saved" Text is displayed`

A separator is required even when neighboring phrases are both assertions or both actions:

```gherkin
Then , verify Error Banner is not displayed, and the 3rd Link does not contain 'failed'
```

`and` and `or` connect phrases logically, but they do not create the phrase boundary by themselves.

A comma inside quoted text remains part of the value:

```gherkin
Then , enter "Smith, John" in the "Name" Textbox, and click the "Save" Button
```

## Comma versus semicolon

Both `,` and `;` separate phrases, but they create different browser behavior.

| Separator | Behavior before the next phrase |
|---|---|
| `,` | Uses the normal browser boundary: a short wait, normal focus transition, DOM readiness checks, and readiness checks for referenced elements |
| `;` | Continues immediately without the normal browser synchronization boundary |

Use a comma as the normal and safest choice:

```gherkin
Then , click the "Save" Button, and verify the "Saved" Text is displayed
```

The comma gives the browser time to process the click, update the DOM, and make the next element ready. Starting a new Gherkin step provides a similarly safe boundary:

```gherkin
Then , click the "Save" Button
Then , verify the "Saved" Text is displayed
```

Use a semicolon when several interactions must happen as one uninterrupted gesture. Expanding menus are a common example because waiting or changing focus can close the menu:

```gherkin
Then , move to the "Products" Menu; move to the "Accessories" Menu Item; click the "Keyboards" Link
```

Use semicolons deliberately. After an action that changes the page, creates content, or requires an element to lose focus, use a comma or a new step so the browser can finish updating.

Other punctuation such as `.`, `:`, and `?` can also end phrases or mark stronger context and branching boundaries. For multi-line branches, see [Nested Steps](nested-steps.md).

## Elements

An element describes something the scenario can find or interact with.

Common element words include:

- `Button`
- `Link`
- `Checkbox`
- `Textbox`
- `Textarea`
- `Dropdown`
- `Text`
- `Window`
- `Alert`

Projects may add their own business terms, such as `Account Row`, `Order Panel`, or `Results Frame`.

### Full and partial text

```gherkin
Then , click the "Submit" Button
Then , click the Button containing "Submit"
Then , select the Dropdown starting with "State"
```

### Quote styles

| Syntax | Text behavior |
|---|---|
| `"text"` | Whitespace-normalized, case-sensitive text |
| `'text'` | Whitespace-normalized, case-insensitive text |
| `` `text` `` | Exact or minimally normalized text |

```gherkin
Then , click the "Submit" Button
Then , enter "Hello" in the 'comments' Textbox
Then , verify Text contains `Thank you. A confirmation email has been sent`
```

### Element modifiers

Elements may include state words such as:

- `checked` / `unchecked`
- `selected` / `unselected`
- `expanded` / `collapsed`
- `enabled` / `disabled`
- `required` / `non-required`
- `blank` / `empty`

```gherkin
Then , verify the checked "Agree" Checkbox is displayed
Then , verify the collapsed Row starting with "Account Number" is displayed
```

### Positioning

Use a position when several elements match:

- `first`
- `last`
- `1st`, `2nd`, `3rd`, `4th`, and so on

```gherkin
Then , click the 7th "Submit" Button
Then , click the last 'refresh' Link
Then , verify the 3rd Text containing `Thank you` is displayed
```

When no position is given, the first matching element is normally used.

## Values

Values may be text, numbers, templates, elements, or durations:

```gherkin
Then , enter "text example" in the "Comment" Textbox
Then , save 10 as "expected count"
Then , wait 3 minutes
Then , wait 5 seconds
Then , enter <customer.name> in the "Customer" Textbox
```

For saved values, example values, nested data, and wildcards, see [Mapping and Templating](mapping-and-templating.md).

## Actions

| Action | Meaning |
|---|---|
| `click` | Left-click an element |
| `double click` | Double-click an element |
| `right click` | Perform a context click |
| `move` | Move the pointer over an element |
| `enter` | Type a value into an element |
| `overwrite` | Clear the existing value, then enter a new value |
| `clear` | Clear a field |
| `select` | Choose an option |
| `scroll` | Scroll to an element |
| `wait` | Wait for a duration or condition |
| `save` | Store a value for later use |
| `attach` | Upload or attach a file |
| `switch` | Switch to a supported browser target |
| `close` | Close a supported target |
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

For shortcuts and held-key timing, see [Keyboard Expressions](key-parser-dsl.md).

## Assertions

Assertions describe the result that must be true.

### Comparisons

- `equals`
- `starts with`
- `ends with`
- `contains`
- `matches`
- `is less than`
- `is less than or equal to`
- `is greater than`
- `is greater than or equal to`

### State checks

- `is displayed` / `is present`
- `is selected` / `is unselected`
- `is checked` / `is unchecked`
- `is enabled` / `is disabled`
- `is required` / `is non-required`
- `is expanded` / `is collapsed`
- `has value` / `has values`
- `is blank`
- `is on` / `is off`
- `is true` / `is false`

Use `not` or `does not` for negation:

```gherkin
Then , verify the "Submit" Button is disabled
Then , verify the "Case Missing" Text is not displayed
Then , verify the "Total" Text contains "10"
Then , verify the "Agree" Checkbox is checked
```

## Hard and soft assertions

Use `ensure` for a hard assertion. A failed hard assertion stops normal scenario execution:

```gherkin
Then , ensure the "Refresh" Link is displayed
```

Use `verify` for a soft assertion. A failed soft assertion is recorded while later scenario work can continue:

```gherkin
Then , verify the "Agree" Checkbox is unchecked
```

The forms `ensures` and `verifies` may be used when they fit the sentence.

## Chained assertions

Use commas between every assertion phrase:

```gherkin
Then , verify Error Banner is not displayed, and the 3rd Link does not contain 'failed'
```

- `and` requires all connected checks to be true.
- `or` allows the chain to succeed when an alternative is true.

## Natural-language inheritance

Pickleball can carry an action, assertion, subject, or comparison value across neighboring phrases when the meaning is clear from ordinary English. This keeps a feature readable without repeating every verb.

### An assertion at the end can apply to earlier elements

```gherkin
Then , ensure the "Agree" Checkbox, the "Submit" Button, or the "Refresh" Button are displayed
```

This is read as:

```text
"Agree" Checkbox is displayed
"Submit" Button is displayed
"Refresh" Button is displayed
```

### An earlier comparison can apply to later alternatives

```gherkin
Then , ensure 3 is equal to 1, 2, or 3
```

This is read as:

```text
3 is equal to 1
3 is equal to 2
3 is equal to 3
```

### A comparison at the end can apply backward

```gherkin
Then , ensure 1, 2, or 3 is less than or equal to 1
```

This is read as:

```text
1 is less than or equal to 1
2 is less than or equal to 1
3 is less than or equal to 1
```

When a two-value comparison is inherited, the shared subject and comparison stay on the side that matches the natural reading of the sentence.

### An action can apply to later elements

```gherkin
Then , click the "Refresh" Button, the "Agree" Checkbox, and the "Submit" Button
```

Each listed element receives the `click` action.

### An action value can also be shared

```gherkin
Then , enter "text to enter" in the "user" Textbox, the "name" Textbox, and the "Notes" Textarea
```

The same text is entered into each listed field.

Inheritance stays within the same connected phrase chain. Start a new step or use a stronger boundary when a new thought should not share the earlier operation.

## Implicit truthiness

A condition or assertion can evaluate a value directly without an explicit comparison.

These values are false-like:

```gherkin
Then , ensure 0
Then , if 0, "0", "false", "FaLse ", "", or " ":
Then , verify false
```

An unresolved template is also false-like:

```gherkin
Then , ensure <propA>
```

These values are true-like:

```gherkin
Then , ensure 1
Then , if 1, "1", "any non blank text not equal to false", "A", or "ppp@ ":
Then , verify true
```

In general:

- zero, blank text, and recognized false values are false;
- non-zero numbers and nonblank text that is not false are true; and
- an element used by itself is evaluated according to whether it resolves successfully.

## Conditional phrases

A dynamic step can branch with `if`, `else if`, and `else`:

```gherkin
Then , if the "Submit" Button is enabled, click the "Submit" Button
Then , else if the "Refresh" Link is displayed, click the "Refresh" Link
Then , else save "No action was available" as "result"
```

Conditions may use the same assertions, chains, inheritance, and truthiness rules described above.

For branches with child steps, see [Nested Steps](nested-steps.md). For branches that hide condition bookkeeping from normal reports, see [Block Conditionals](block-conditionals.md).

## Context phrases

Context phrases narrow where or how the next operation applies.

Common context words include:

- `in`
- `inside`
- `within`
- `from`
- `of`
- `to`
- `on`
- `with`
- `without`
- `before`
- `after`
- `above`
- `below`
- `under`
- `over`
- `near`
- `next to`
- `following`
- `preceding`

Examples:

```gherkin
Then , in the "Account" Section, click the "Save" Button
Then , from the "Results" Table, verify the 2nd Row contains "Approved"
Then , below the "Customer Name" Label, enter "Ava" in the Textbox
```

Context can also be passed to nested child steps. See [Nested Steps](nested-steps.md).

## Writing readable scenarios

1. Use a comma after the Cucumber keyword to begin a dynamic step.
2. Separate every phrase, even when neighboring phrases are both actions or assertions.
3. Use commas for normal browser synchronization.
4. Use semicolons only for deliberately uninterrupted interactions.
5. Let inheritance remove repetition only when the sentence remains easy to understand.
6. Prefer a new step when the business meaning changes.
7. Use `ensure` for required conditions and `verify` for checks that may be collected before the scenario ends.

---

[Documentation home](README.md) · [Next: Mapping and Templating](mapping-and-templating.md)
