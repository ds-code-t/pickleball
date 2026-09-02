# Dynamic Steps

> **Working feature examples:** [`dynamic-steps.feature`](../maven-consumer-project/src/test/resources/features/dynamic-steps.feature) covers core element selection, actions, assertions, ordinals, and chained steps; [`forms-dynamic-steps.feature`](../maven-consumer-project/src/test/resources/features/forms-dynamic-steps.feature) covers form controls and pointer actions; [`it-placeholder.feature`](../maven-consumer-project/src/test/resources/features/it-placeholder.feature) covers the `it` placeholder, including a trailing phrase after `click it`; [`browser-action-contracts.feature`](../maven-consumer-project/src/test/resources/features/browser-action-contracts.feature) covers window switching and component closing; [`mapping-and-resources.feature`](../maven-consumer-project/src/test/resources/features/mapping-and-resources.feature) covers comma-step `save` actions.

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

An element category is one or more capitalized words, each at least two letters: `Button`, `Radio Button`, `Product Card`. Plural aliases such as `Buttons` and `Textboxes` are registered as children of the singular name.

Built-in HTML categories include:

| Group | Categories |
|---|---|
| Controls | `Button`, `Submit Button`, `Close Button`, `Link`, `Textbox`, `Date Textbox`, `Textarea`, `Dropdown`, `Option`, `Radio Button`, `Checkbox`, `Toggle` |
| Structure | `Text`, `Icon` / `Image`, `Menu` / `Menu Item`, `Modal` / `Dialog`, `Tab` / `Tab Panel`, `Section` / `Question`, `Expandable Section`, `Expandable Header`, `Expandable Icon` |
| Tables | `Table`, `Row`, `Header` / `Header Row`, `Cell`, `Column`, `Field` |
| Other HTML | `IFrame` / `Frame`, `Loading` |

`Window` and `Alert` are browser types, not HTML locators. They do not assemble an XPath.

Projects can add names such as `Test Panel`, `Product Card`, or `Status Badge` in the runner. See [Custom element definitions](custom-element-definitions.md).

The selector is assembled dynamically from the element category, text, state, ordinal, and context. Feature authors normally do not need to repeat XPath or CSS selectors. An unrecognized capitalized name still parses; unmatched names fall through generic name-attribute and descendant-text matching.

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

## The `it` placeholder

`it` refers to a previously named element. The word occupies a text span, so the action or assertion gathers from the correct side of the verb when another phrase follows. Replacement walks previous phrases, including a nested parent or ancestor step whose own elements do not satisfy the action.

```gherkin
* , if the "Submit Form" Button is displayed, click it
* , if the "Submit Form" Button is displayed, click it, and wait 1 seconds.
* , if the "Email" Radio Button is displayed, click it, and click the "Submit Form" Button
* , if the "Account Type" Dropdown is displayed, select "Premium" in it
* , in the "Profile Form" Test Panel, if the "Submit Form" Button is displayed:
: * , click it
* , if the "Submit Form" Button is displayed:
: * , save "nested-marker" as "itAncestorMarker":
:: * , click it
```

Quoted `"it"` remains ordinary text, not the placeholder. The executable consumer contract is the single scenario in [`it-placeholder.feature`](../maven-consumer-project/src/test/resources/features/it-placeholder.feature).

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
| `save` | store a value under a key for later template resolution |
| `attach` | attach a file where supported |
| `switch` | switch to a matching browser window or tab |
| `close` | close a matched HTML component through its configured `Close Button` |
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

Window selection is part of the `Window` element vocabulary. For example:

```gherkin
* , switch the New Window
* , switch the Previous Window
```

`close` is an HTML-element action, not a WebDriver window-close operation. It searches inside the matched component for the project's configured `Close Button` category and clicks that control:

```gherkin
* , close the "Dismissible Notice" Test Panel
```

The executable consumer contract for both behaviors is in [`browser-action-contracts.feature`](../maven-consumer-project/src/test/resources/features/browser-action-contracts.feature), tagged `@contract-coverage-217`.

## Save actions

Use `save ... as ...` to place a resolved value into the active parsing map:

```gherkin
* , save "Ava" as "customerName"
* , save 3 as "retryCount"
```

Mapped values can be resolved first and then saved under another key:

```gherkin
Given MAP "customer" TABLE VALUES
  | city | Phoenix |

When , save "<customer.city>" as "savedCity"
Then , ensure "<savedCity>" equals "Phoenix"
```

Saving the same key again adds a newer value, and a normal lookup resolves the latest one:

```gherkin
* , save "draft" as "status"
* , save "ready" as "status"
* , ensure "<status>" equals "ready"
```

Use `CLEAR SAVED VALUES` or `CLEAR SAVED VALUES:key1,key2` when those run-map values should be removed. See [Mapping and Templating](mapping-and-templating.md).

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
- [The `it` placeholder, including a trailing phrase after `click it`](../maven-consumer-project/src/test/resources/features/it-placeholder.feature)
- [Window switching and component closing](../maven-consumer-project/src/test/resources/features/browser-action-contracts.feature)
- [Saved values and supported mapping steps](../maven-consumer-project/src/test/resources/features/mapping-and-resources.feature)
- [Contexts, ordinals, and project-specific elements](../maven-consumer-project/src/test/resources/features/catalog-context.feature)
- [Dialogs](../maven-consumer-project/src/test/resources/features/dialogs.feature)
- [Browser test pages](../maven-consumer-project/src/test/resources/site)

[Documentation home](README.md) · [Next: Mapping and Templating](mapping-and-templating.md)
