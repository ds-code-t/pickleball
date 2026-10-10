# Dynamic Steps

> **Working feature examples:** [`dynamic-steps.feature`](../maven-consumer-project/src/test/resources/features/dynamic-steps.feature) covers core element selection, actions, assertions, ordinals, and chained steps; [`forms-dynamic-steps.feature`](../maven-consumer-project/src/test/resources/features/forms-dynamic-steps.feature) covers form controls and pointer actions; [`it-placeholder.feature`](../maven-consumer-project/src/test/resources/features/it-placeholder.feature) covers the `it` placeholder, including a trailing phrase after `click it`; [`browser-action-contracts.feature`](../maven-consumer-project/src/test/resources/features/browser-action-contracts.feature) covers window switching and component closing; [`mapping-and-resources.feature`](../maven-consumer-project/src/test/resources/features/mapping-and-resources.feature) covers comma-step `save` actions; [`area-b-control-flow.feature`](../maven-consumer-project/src/test/resources/features/area-b-control-flow.feature) covers a comma chain, a semicolon chain, a period sentence split, `times`, and `DT:::` versus `DS:::`.

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

Context phrases restrict the next element lookup. A context word is public only when it both parses and changes the lookup.

```gherkin
* , in the "Secondary Queue" Test Panel, click the "Approve" Button
* , from the "Results" Table, ensure the 2nd Row contains "Approved"
```

Context words that parse and resolve:

| Word | Lookup |
|---|---|
| `in`, `from`, `for` | inside the anchor |
| `after` | following the anchor in document order |
| `before` | preceding the anchor in document order |

`after` and `before` use document order, not the visual position.

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
| `move`, `hover` | move the pointer over an element. `hover` is the same action as `move` |
| `dragAndDrop` | drag one HTML element onto a second HTML element: `dragAndDrop the "Drag Source" Button the "Drop Target" Button` |
| `enter`, `overwrite`, `clear` | edit field values |
| `select` | choose a dropdown or selectable value |
| `scroll` | bring an element into view |
| `wait` | wait for a duration or condition |
| `save` | store a value under a key for later template resolution |
| `attach` | upload an existing file through an `InternalFileInput` |
| `create and attach` | create a temporary file, then upload it through an `InternalFileInput` |
| `switch` | switch to a matching browser window or tab |
| `close` | close a matched HTML component through its configured `Close Button` |
| `accept`, `dismiss` | handle an open browser alert or confirmation. There is no phrase that types into a prompt; `BrowserAlerts.sendKeys` is Java-only |
| `press` | send a keyboard expression |

Examples:

```gherkin
* navigate to: URL.forms
* , overwrite "3" in the "Quantity" Textbox
* , hover the "Interaction Target" Button
* , double click the "Interaction Target" Button
* , accept the Alert
```

`navigate to:` loads the resolved URL once. A second call is a second navigation.

A phrase that is only `false` is a truthiness check: `IF: false` does not take the branch. `is false` is the falsy assertion, and `is true` is the truthy assertion. `is on` and `is off` check a control. Keep each comparison in its own phrase; do not combine it with another operator.

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

Author-facing `ASSERT:` and `SOFT ASSERT:` are the uppercase short forms of those long-form steps. `IF:` only selects a branch and does not fail when the condition is false. See [Log and assert steps](log-and-assert-steps.md).

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

`matches` compiles a Java regular expression and uses `Matcher.matches`, so the pattern must cover the whole value. A double-quoted pattern is case-sensitive. A single quote on either side is case-insensitive. `"Ava"` matches `"A.+"`.

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

A period, exclamation mark, or question mark ends a sentence when the next character is whitespace or the end of the step. Later sentences become child steps of that dynamic step. A gherkin colon child hangs off the last sentence, so this child runs after both saves:

```gherkin
* , save "a" as "x". save "b" as "y":
  : * , save "<x>-<y>" as "seen"
```

A step that is only a comma, `* ,`, has no phrase. It fails the step with a clear message. It is not an index error.

## `times` and element `wait`

`3 times` repeats that action. `save "tick" as "key" 3 times` stores the same value three times. The repeat stops on the first throw. Later repetitions do not run.

An element `wait` polls until the element is present, or until a loading element is gone. It then continues. The hard-fail cap is the run-wide `pkb_stepMaxTime` (default 60 minutes), the same clock as a block `until`. An element `wait` does not use `pkb_stepRepeatMaxCount`. There is no per-step ceiling and no poll-count cap. When `pkb_stepMaxTime` is hit, the step hard-fails the scenario. The message names the setting, for example `Step exceeded pkb_stepMaxTime (5m)`. It does not poll forever.

A Gherkin time on that wait is a soft limit. Either order is the same:

```gherkin
* , wait the "Submit" Button, or 2 minutes
* , wait 2 minutes, or the "Submit" Button
```

The wait stops when the element appears, or when that time passes, and then continues. It does not fail and it does not log a warning. The time counts from the step's start, the same clock as Step Duration, so page-ready time is included. The clock is checked before each sleep, and the sleep is only the time left, capped at 3 seconds. `, or 0 seconds` does one check. A number word such as `two` is not a duration. The run-var limit always hard-fails; a longer Gherkin time can't extend it.

`wait Loading` waits until that loading element is gone. `Loading` is looked up in the current document, the top document, frames, and open shadow roots, not only inside an `in` / `from` / `for` context. A displayed match in another frame keeps the wait going. Clicking, entering, saving, reading, or using that match as a context fails. A match in the current document, including its open shadow root, can be clicked. See [Reserved element names](reserved-element-names.md).

Step Repetition, Step Duration, and the other reserved names are listed in [Reserved element names](reserved-element-names.md).

## Inline argument markers

On a dynamic step, or on a colon child, `DT:::city|Paris|` replaces that step's data table. Another marker, such as `DS:::hello|` or `NOTE:::hello|`, is stored as the inline type and does not become a table. A doc string on that step stays the doc string.

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
