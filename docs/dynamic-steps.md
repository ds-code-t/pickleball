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
* , click the Button matching "Save"
* , select the Dropdown starting with "Account"
```

`matches` is a full-match Java regular expression everywhere, including element text and phrase text. `containing` is a literal partial match. For a partial regular expression, wrap the pattern in `.*`, for example `".*Sa.e.*"`. For exact text that contains `(` `)` `.` or `$`, use `equals` or escape the pattern. Quote types change case and whitespace only. There is no separate regex syntax and no regex warning.

Changed in 2.1.16. Element `matching` used to succeed when the pattern occurred anywhere in the text. It is now a full match, the same rule phrase `matches` already used. On buttons Save, Save All, and Autosave, `matching "Save"` selects only Save, `matching "Save.*"` selects Save and Save All, and `matching 'save all'` selects Save All.

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

`navigate to:` loads the resolved URL once. A second call is a second navigation. The text is a config key first, as in `URL.forms`. When that lookup is not a URL string, a literal `http://` or `https://` value is used, including a value a placeholder already resolved to. A missing key that is not a URL still fails. An existing `URL.xxx` key is unchanged.

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
does not contain
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

When composite is primary, a visible or present check on a composite hit is one `query`: `--wait-for visible` or `attached`, `--poll-ms 150`, `--stability-ms 0`. That is the same 150 ms poll as the Java loop, with no extra stability hold. Before, each poll was a displayed or connected check. A binary that rejects `--poll-ms` keeps that loop. An engine timeout fails that wait once and is not polled again in Java. `pkb_composite=off` stays on the loop and does not query. `pkb_composite_log=adapter` writes `INFO adapter wait visible poll=150`, or `wait fallback` when the flag is rejected.

Deepest or outermost containment is one `query --nesting deepest|outermost` when `capabilities.query_flags` lists `nesting`. Displayed is the `displayed` filter on that same query, so it still runs before nesting. A binary that does not list `nesting` keeps the anchor union: two or more composite hits that have an id or name are one extra xpath (`anchor/ancestor::*` or `anchor/descendant::*`). A hit with no id or name on that older binary keeps the per-node walk. `pkb_composite_log=adapter` writes `INFO adapter nesting deepest` (or `outermost`) when the flag is used, and `INFO adapter nesting fallback` when it is not.

`wait Loading` waits until that loading element is gone. `Loading` is looked up in the current document, the top document, frames, and open shadow roots, not only inside an `in` / `from` / `for` context. When composite is primary and the binary accepts `query --pierce open`, that scan is one query (`mode=composite`, `all_frames`, open shadows). Before, it was a find, a script, and a frame switch per context. A binary that rejects `--pierce`, or `pkb_composite=off`, keeps that walk. A displayed match in another frame keeps the wait going. Clicking, entering, saving, reading, or using that match as a context fails. A match in the current document, including its open shadow root, can be clicked. Closed shadows are still skipped. Depth 5 and 20 frames still warn once. The window is restored either way. `pkb_composite_log=adapter` writes `INFO adapter scan all-contexts`. The same scan honors `first`, `#N`, `last`, and `every #N`. Those words share `PositionSelection` with a normal find, so `the 3rd Button` is the third displayed match, not the first. Only the matches that position keeps are wrapped. Before, the scan wrapped every hit and then kept the first unless the word was `last`. `pkb_composite_log=position` writes `TRACE position apply size=<n> position=<p> selection=<s> kept=<k> durationMs=<ms>`. See [Reserved element names](reserved-element-names.md).

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

## Dom-composite translation

Gherkin element phrases do not change. `SelectorTranslator` is the only place that turns a phrase into a dom-composite pipeline. `ExecutionDictionary` still builds the XPath it already built. That XPath, and any CSS, is passed through. A space between an XPath function name and `(` is removed, because composite rejects `not (`.

| Phrase | Pipeline |
|---|---|
| the `"Save"` Button | `role=button >> text="Save"` |
| the Button containing `'log'` | `role=button >> text~="log"` (single quotes fold case) |
| the Button matching `"Save"` | `role=button >> text="^Save$"` (full match) |
| the `"Save"` Button in the `"Billing"` Section | context stages, then the target |
| the `"Save"` Button after the `"Billing"` Section | context, then `below=1`, then the target |
| the `"Save"` Button before the `"Billing"` Section | target, then `below=1`, then the context |
| a custom `Product Card` | `desc="Product Card"` plus the text stage |
| `first` / `2nd` / `last` / `every 2nd` | one `PositionSelection` after displayed, nesting, and regex. A composite query ordinal uses that same index |

`pkb_composite=auto` (the default) uses this path when the `composite` binary is present. `pkb_composite=off` keeps Selenium for that run and does not translate. See [Configuration](configuration.md).

Enabled, blank, required, expanded, collapsed, and on/off checks read the snapshot taken with the element. They do not run a second `self::*` find. The same booleans are on the batched describe (`ElementView`). A snapshot that could not be evaluated falls back to that one find and writes one `TRACE state snapshot miss fallback` line when `pkb_composite_log` names `state` or `all`. Visibility locators stay XPath. They were not a second find.

When `capabilities.actions` lists `eval`, a composite snapshot is one `eval` of the same snapshot function. `this` is the node from the hit (`node_id`), so the engine does not search again. That is one command and no WebDriver script (before: one script that searched the page again). `INFO adapter snapshot eval` is written when `pkb_composite_log` names `adapter` or `all`. A binary that does not list `eval` keeps that WebDriver script, and the line says `snapshot script`. The snapshot fields do not change. A refresh of that hit asks one more `eval` (`this.isConnected`) first. When that is true, the new snapshot is another `eval` on the same node and there is no `stableSelector` find (before: one find and one snapshot script). The line is `INFO adapter live <node id>`. Eval absent, a failed call, or a node that is not connected still re-finds by `stableSelector`.

dom-composite reports `submit`, `dblclick`, `contextclick`, `drag`, and `key` on `capabilities.actions` when the binary has them. The adapter sends that verb. `INFO adapter verb <name> real` is written when `pkb_composite_log` names `adapter` or `all`. A binary that lists only click, type, and scroll keeps the old mapping, and the line says `fallback`: submit is click, a double-click is two clicks, a context-click is one click, and a drag is click, then scroll by the offset, then click. An element-to-element drag with no offset is one `drag` call when the verb is present (before: three calls). An offset drag stays click, scroll, click, because `drag` takes two locators and no dx/dy. A double-click is one `dblclick` when listed (before: two clicks). A `press` of only letters and digits uses composite type. A lone `A`–`Z` is lowercased, matching the key parser. Named keys and chords that fit one composite chord (`ENTER`, `CONTROL+A`, `SHIFT+TAB`, and `ENTER` then `TAB`) use `key` when it is listed. Each `key` command focuses that element. `A+B` and `CONTROL[A+B]` focus through a composite click, then use WebDriver Actions, because a chord has only one non-modifier. Desktop queries use `source=desktop` with `image=template=` and `image=ocr~=`. Desktop `click` and `type` are refused with `error:` / `fix:` until `capabilities.desktop` lists them. The 2.4.0 desktop verbs are `list`, `attach`, `walk`, `query`, `resolve`, and `close`. When `capabilities.desktop` lists a verb, the same adapter forwards it. The native jar is `dev.domcomposite:composite-native:2.4.0` with classifiers `linux-x64`, `linux-aarch64`, `windows-x64`, `macos-aarch64`, and `macos-x64` (binary `composite`, or `composite.exe` on Windows). WebDriver stays for browser lifecycle only: navigation, window handles, and the CDP address.

## Working examples

- [Dom-composite adapter actions, lookups, and backward-compatible phrases](../maven-consumer-project/src/test/resources/features/dom-composite.feature)
- [Core dynamic-step playground](../maven-consumer-project/src/test/resources/features/dynamic-steps.feature)
- [Form actions, state assertions, chains, and pointer actions](../maven-consumer-project/src/test/resources/features/forms-dynamic-steps.feature)
- [The `it` placeholder, including a trailing phrase after `click it`](../maven-consumer-project/src/test/resources/features/it-placeholder.feature)
- [Window switching and component closing](../maven-consumer-project/src/test/resources/features/browser-action-contracts.feature)
- [Saved values and supported mapping steps](../maven-consumer-project/src/test/resources/features/mapping-and-resources.feature)
- [Contexts, ordinals, and project-specific elements](../maven-consumer-project/src/test/resources/features/catalog-context.feature)
- [Dialogs](../maven-consumer-project/src/test/resources/features/dialogs.feature)
- [Browser test pages](../maven-consumer-project/src/test/resources/site)

[Documentation home](README.md) · [Next: Mapping and Templating](mapping-and-templating.md)
