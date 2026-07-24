# Dynamic Steps

> **Working feature examples:** [`dynamic-steps.feature`](../maven-consumer-project/src/test/resources/features/dynamic-steps.feature) covers core element selection, actions, assertions, ordinals, and chained steps; [`forms-dynamic-steps.feature`](../maven-consumer-project/src/test/resources/features/forms-dynamic-steps.feature) covers form controls, state assertions, clearing, resetting, and pointer actions.

Dynamic steps let a feature describe browser behavior directly without adding one Java method for every Gherkin sentence.

A dynamic step begins with a Cucumber keyword followed by a comma:

```gherkin
Then , click the "Submit" Button
```

Pickleball parses the text after the comma into values, elements, contexts, actions, assertions, and conditions.

## Selenium element descriptions

An element can be described by its business-visible characteristics:

```gherkin
* , click the "Submit" Button
* , enter "Ava" in the "First Name" Textbox
* , select "Premium" in the "Account Type" Dropdown
* , ensure the "Receive Updates" Checkbox is unchecked
* , click the 2nd "View Details" Button
* , ensure the last "Available" Status Badge is displayed
```

Common element categories include `Button`, `Link`, `Textbox`, `Textarea`, `Dropdown`, `Checkbox`, `Text`, `Window`, and `Alert`. Projects can add names such as `Test Panel`, `Product Card`, or `Status Badge` in the runner.

The selector is assembled dynamically from the element category, text, state, ordinal, and context. Feature authors normally do not need to repeat XPath or CSS selectors.

## Text matching

```gherkin
* , click the "Submit" Button
* , click the Button containing "Submit"
* , select the Dropdown starting with "Account"
```

Quote styles affect text handling:

| Syntax | Typical behavior |
|---|---|
| `"text"` | normalized, case-sensitive text |
| `'text'` | normalized, case-insensitive text |
| `` `text` `` | exact or minimally normalized text |

## Positions and states

Use `first`, `last`, or an ordinal when several elements match:

```gherkin
* , click the first "Choose" Button
* , click the 2nd "Choose" Button
* , click the last "Choose" Button
```

State words can be part of a selector or assertion:

```gherkin
* , ensure the checked "Receive Updates" Checkbox is displayed
* , ensure the "Locked Action" Button is disabled
* , ensure the "Advanced Filters" Button is collapsed
```

## Context

Context phrases restrict the next element lookup:

```gherkin
* , in the "Secondary Queue" Test Panel, click the "Approve" Button
* , below the "Customer Name" Label, enter "Ava" in the Textbox
* , from the "Results" Table, ensure the 2nd Row contains "Approved"
```

Common context words include `in`, `inside`, `within`, `from`, `of`, `on`, `before`, `after`, `above`, `below`, `near`, `next to`, `following`, and `preceding`.

## Actions

Frequently used actions include:

| Action | Purpose |
|---|---|
| `navigate to` | open a URL |
| `click`, `double click`, `right click` | pointer actions |
| `move to` | move the pointer over an element |
| `enter`, `overwrite`, `clear` | edit field values |
| `select` | choose a dropdown or selectable value |
| `scroll` | bring an element into view |
| `wait` | wait for a duration or condition |
| `save` | store a value for later mapping |
| `attach` | attach a file where supported |
| `switch`, `close` | change or close a supported browser target |
| `accept`, `dismiss` | handle browser dialogs |
| `press` | send a keyboard expression |

Examples:

```gherkin
* navigate to: URL.forms
* , overwrite "3" in the "Quantity" Textbox
* , move to the "Interaction Target" Button
* , double click the "Interaction Target" Button
* , accept the Alert
```

## Assertions

Use `ensure` for a hard assertion and `verify` for a soft assertion:

```gherkin
* , ensure the "Submit" Button is enabled
* , verify the "Optional Warning" Text is not displayed
```

Comparisons include:

```text
equals
contains
starts with
ends with
matches
is less than
is less than or equal to
is greater than
is greater than or equal to
```

Common state checks include:

```text
is displayed / is present
is selected / is unselected
is checked / is unchecked
is enabled / is disabled
is required / is non-required
is expanded / is collapsed
is blank
is true / is false
```

## Phrase chains and separators

A dynamic step can contain several phrases:

```gherkin
* , enter "Mia" in the "First Name" Textbox, select "Standard" in the "Account Type" Dropdown, and click the "Submit Form" Button
```

A comma creates the normal browser synchronization boundary before the next phrase. It allows focus changes, DOM updates, readiness checks, and short waits.

A semicolon continues without that normal boundary:

```gherkin
* , move to the "Products" Menu; move to the "Accessories" Menu Item; click the "Keyboards" Link
```

Use semicolons only when an interaction must remain uninterrupted, such as a menu that would close after a normal focus or wait boundary.

## Natural-language inheritance

Pickleball can carry an action, assertion, subject, or comparison across a connected phrase chain:

```gherkin
* , click the "Refresh" Button, the "Agree" Checkbox, and the "Submit" Button
* , enter "same value" in the "User" Textbox, the "Name" Textbox, and the "Notes" Textarea
* , ensure the "Agree" Checkbox, the "Submit" Button, and the "Refresh" Button are displayed
```

Start a new step when the inherited meaning would become unclear.

## Inline conditions

```gherkin
* , if the "Submit" Button is enabled, click the "Submit" Button
* , else if the "Refresh" Link is displayed, click the "Refresh" Link
* , else save "No action was available" as "result"
```

For child steps, use [Nested Steps](nested-steps.md). For report-focused branch blocks, use [Block Conditionals](block-conditionals.md).

## Working examples

- [Core dynamic-step playground](../maven-consumer-project/src/test/resources/features/dynamic-steps.feature)
- [Form actions, state assertions, chains, and pointer actions](../maven-consumer-project/src/test/resources/features/forms-dynamic-steps.feature)
- [Contexts, ordinals, and project-specific elements](../maven-consumer-project/src/test/resources/features/catalog-context.feature)
- [Dialogs](../maven-consumer-project/src/test/resources/features/dialogs.feature)
- [Browser test pages](../maven-consumer-project/src/test/resources/site)

[Documentation home](README.md) · [Next: Mapping and Templating](mapping-and-templating.md)
