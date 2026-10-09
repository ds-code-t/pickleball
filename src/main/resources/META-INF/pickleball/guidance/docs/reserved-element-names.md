# Reserved element names

> **Working feature example:** [`step-state-elements.feature`](../maven-consumer-project/src/test/resources/features/step-state-elements.feature) covers Step Repetition, Step Duration, a combined until, saving a duration, and the reserved-name error.

The parser treats the names on this page as special. Any other name, including a name that matches an internal enum constant such as `Url`, `Html Iframe`, or `Value Type`, is an ordinary HTML element. A trailing `s` is ignored for the comparisons below (`Step Repetitions` is `Step Repetition`), except where a plural is its own Data Element name.

Data Element names are matched without regard to case. The `Step` prefix is also case-insensitive. `Time`, `Duration`, `Match`, `Loading`, and the browser names are case-sensitive.

## Step state

A name that starts with `Step` followed by a word boundary is reserved. Only these two return a value:

- `Step Repetition` and `Step Repetitions` — the running step's pass count
- `Step Duration` and `Step Durations` — elapsed time since that step started

`Step`, `Step Count`, `Steps`, and `Step Repetition Count` fail. The message is:

```text
'Step Count' is a reserved element name. Names starting with 'Step' are reserved for step-state elements; supported: Step Repetition, Step Duration.
```

`Stepwise` does not start with the reserved word `Step` and is an ordinary HTML element.

Step Repetition reads the step that is running, not its parent. A child step has its own count. On a normal step the count is 1 while the step's own `if` or `save` runs. In a block `until`, the count is the pass being evaluated: 1 on the first check, 2 on the second, and so on. This runs the body exactly three times and stops when the check reads 4:

```gherkin
* , until the Step Repetition is greater than 3:
  : * , save "tick" as "looped"
```

A child inside that body still sees its own count of 1:

```gherkin
* , if the Step Repetition is greater than 0:
  : * , save "ran" as "seen"
```

Step Duration is the time since that same step started. In a block `until`, the condition reads the loop step, so the duration is the time since the loop's first pass. The loop waits 400 milliseconds between passes. The condition does not also wait out the browser's implicit timeout or the page-ready quiet period; the loop is the retry.

```gherkin
* , until the Step Duration is greater than 1 second:
  : * , save "tick" as "waited"
```

Combine a missing element with the count by putting a comma before `or`. The body still runs three times when the button is not on the page:

```gherkin
* , until the "Missing" Button is displayed, or the Step Repetition is greater than 3:
  : * , save "tick" as "retried"
```

Saving Step Duration stores `java.time.Duration.toString()`, an ISO-8601 duration such as `PT0.003S` or `PT0S`. A raw duration cannot be stored through the mapping converter. Comparisons still see a duration, not that saved string.

```gherkin
* , save the Step Duration as "elapsed"
* , ensure "<elapsed>" matches "PT.*S"
```

`pkb_stepRepeatMaxCount` caps a block `until`. `pkb_stepMaxTime` caps every block `until` and every element `wait`. An element `wait` does not use the count. They are run-wide. There is no per-step ceiling. See [Repeat ceilings](configuration.md#repeat-ceilings).

## Data Elements

These names are Data Elements, not HTML. Plurals and aliases are included. See [Data values and Data Elements](data-values-and-elements.md).

- `Data Table`, `Data Tables`
- `Data Row`, `Data Rows`
- `Data Column`, `Data Columns`
- `Data List`, `Data Lists`
- `Data Column List`, `Data Column Lists`
- `Data Cell`, `Data Cells`
- `Data Entry`, `Data Entries`
- `Data Header`, `Data Headers`
- `Data Value`, `Data Values`
- `Data Doc String`, `Data Doc Strings`, and the aliases `Doc String`, `Doc Strings`
- `Map`, `Maps`
- `List`, `Lists`
- `Set`, `Sets`
- `Multimap`, `Multimaps`
- `Structured Data` (no plural), and the aliases `Data`, `Data Object`, `Data Objects`
- `JSON Data`, `YAML Data`, `XML Data` (no plural)
- `Data String`, `Data Strings`
- `JSON String`, `JSON Strings`
- `YAML String`, `YAML Strings`
- `XML String`, `XML Strings`

`Table`, `Row`, `Column`, `Header`, and `Cell` without the `Data` word are ordinary HTML categories.

## Time values

- `Time` and `Times` — an instant
- `Time Range` and `Time Ranges`
- `Duration` and `Durations`

`1 second` in a comparison is a duration unit on a value, not an element named `second`. The units are `second`, `minute`, `hour`, `day`, `week`, `month`, and `year`.

## Match

`Match` and `Matches` are the regular-expression element, not HTML.

## Loading

`Loading` and `Loadings` are the built-in loading element. It is still an HTML element. A project may overlay that category the same way it overlays `Button`. That overlay does not warn.

## Browser, alert, and window names

These names are browser elements, not HTML:

- `Browser`
- `Alert` and `Alerts` — the alert text
- `Window` and `Windows`
- `BROWSER`
- `Browser Tab` and `Browser Tabs`
- `Address Bar` and `Address Bars`

A name that contains `Window` is a window picker when the text outside that word is empty or one of `Url`, `Title`, `Index`, `First`, `Last`, `New`, `Next`, or `Previous`. Empty means the current window by title. Examples: `Url Window`, `Title Window`, `Index Window`, `First Window`, `Last Window`, `New Window`, `Next Window`, `Previous Window`.

`Url` by itself is an ordinary HTML element. `Url Window` is the window picker.

## Custom categories

`ExecutionDictionary.category`, `categories`, and `children` log a WARN when the name is reserved or starts with `Step `. The message names the category, what it is reserved for, and that the custom definition will not be used. Registration is not rejected. The parser still uses the reserved meaning, so a custom xpath under that name is not how the element resolves.

No warning is logged for an ordinary HTML name. That includes built-ins Pickleball registers, such as `Button` and `Loading`, and a consumer overlay of a built-in HTML category such as `Close Button`.
