# Block Conditionals

> **Working feature example:** [`nested-and-block-conditionals.feature`](../maven-consumer-project/src/test/resources/features/nested-and-block-conditionals.feature) demonstrates `IF:`, `ELSE-IF:`, and `ELSE:` branches together with nested executable steps.

Block conditionals choose one business path while normal reports emphasize the steps that actually ran. A false `IF:` does not fail the scenario; use [`ASSERT:`](log-and-assert-steps.md) when a false condition should fail.

Use uppercase `IF:`, `ELSE-IF:`, and `ELSE:`. Do not place the dynamic-step comma before the block keyword.

```gherkin
* IF: 1 == 1 THEN: , save "A" as "result"
```

A normal dynamic conditional begins differently:

```gherkin
* , if 1 == 1, save "A" as "result"
```

## Phrase-style conditions

Phrase-style conditions use the same assertions, elements, contexts, chains, inheritance, and truthiness rules as dynamic steps:

```gherkin
* IF: the "Validation Error" Text is displayed:
  : * , click the "Refresh Request" Button
  : * , ensure "Workflow State: review" Text is displayed
* ELSE:
  : * , click the "Submit Request" Button
```

A direct value can be used as a truthy or false-like condition. A quoted string, a saved reference, a bare `true` or `false`, a number, or `<{ ... }>` is enough: non-blank text, non-zero numbers, and true run the branch; blank text, `0`, `false`, and a missing reference skip it. A zero-only string such as `"0"`, the words `"no"` and `"null"`, and `<^~NULL~^>` are false-like and skip. A backtick string such as `` `abc` `` runs. An empty backtick skips. Neither throws.

Phrase comparisons and element states use the same words as a dynamic step. `ends with`, `starts with`, and `is blank` compare the quoted value. `matches` compiles a Java regular expression and requires `Matcher.matches`, so the pattern must cover the whole value. Double quotes are case-sensitive. A single quote on either side is case-insensitive. On the forms playground, `is selected`, `is unselected`, `is present`, `is required`, and `is non-required` select the branch without failing when the state is false. `is present` is false for an element that is not on the page, and that branch skips. See [`phrase-plain-forms.feature`](../maven-consumer-project/src/test/resources/features/phrase-plain-forms.feature).

```gherkin
* IF: <configs.TEST_DATA.featureFlags.workflowEnabled>:
  : * , save "enabled" as "state"
* ELSE:
  : * , save "disabled" as "state"
```

## Expression-style conditions

Expression-style conditions use explicit operators:

```gherkin
* IF: 1 < 4 && true && 6 && "A" THEN: , click the "Use Ready State" Button
```

Common operators:

```text
==  !=  <  <=  >  >=  &&  ||  !
```

Parentheses can group expression parts:

```gherkin
* IF: (1 < 4 && 6) || false THEN: , save "true" as "result"
```

Expression parts are evaluated independently. They do not inherit a subject or comparison from a neighboring expression.

`&&` and `||` read each side only when that side runs. A skipped side is not pasted and is not parsed, so a missing reference, a `$` call, a `file:` reference, or bad syntax on that side does not run. A blank side is false. A single `|` is not a short-circuit and still reads both sides. A taken comparison still pastes saved text, so `<A> > 5` with A saved as `6` is the source `6 > 5`.

`condition ? whenTrue : whenFalse` uses the same rule. The condition runs first. Only the arm that runs is pasted and evaluated. The other arm stays as written, so a missing reference, a `$` call, a `file:` reference, or bad syntax there does not throw. A taken arm still pastes and can throw. Nested `&&`, `||`, or `?:` inside a skipped arm or side is not pasted. A `?` at the very end of an expression is still the boolean marker. The value before it is read with `isTruthy`, then the `?` is dropped. It is not a cast to Boolean and it is not this operator. `<{ "yes"? }>` and `<{ 1? }>` are truthy. `<{ "0"? }>` and `<{ false? }>` are falsy. None of those throw. `!`, comparisons, math, a single `|`, and function arguments still run.

`bool(x)` is that same truthiness as a Boolean. `bool(true)` runs and `bool(false)` skips, inline or as a block `IF:`.

`firstNotBlank` returns the first argument that is not null, empty, or whitespace, or nil when every argument is blank. `firstNotEmpty` returns the first argument that is not null or `""`, so a whitespace-only string still counts. `firstNotNull` returns the first argument that does not start with `<` and end with `>`. `getBool(x)` is the truthiness of its first argument. Inside `IF: <{ ... }>`, a returned string or Boolean uses ordinary truthiness, and nil is falsy.

```gherkin
* IF: <{ firstNotBlank("", "abc") }> THEN: , save "yes" as "picked"
* IF: <{ getBool("no") }> THEN: , save "no" as "picked"
```

Each expression reference logs one info line after it finishes. A part that ran is shown with its map references filled in. A part that never ran is shown as written. Then the line shows the result, for example `<{ <A> || <Missing> }> -> yes || <Missing> -> true`. A plain expression with nothing to skip logs the original text, the filled-in text, and the result. If evaluation throws, an info line starting with `evaluation failed` names the original expression, shows that same picture, and includes the error. The error still propagates, and it still names the text that failed.

## Inline branch chains

Use `THEN:` when the result fits on one line:

```gherkin
* IF: 5 == 1 THEN: , click the "Use Error State" Button
  ELSE-IF: 5 == 5 THEN: , click the "Use Ready State" Button
  ELSE: , click the "Use Review State" Button
```

Branches are considered from left to right. Only the first matching branch runs.

## Data tables and doc strings

An `IF:` / `THEN:` / `ELSE-IF:` / `ELSE:` branch that runs as its own step inherits the step's data table or doc string. A `THEN:` or `ELSE:` action that does not already start with a comma is run that way. A comma action stays in the IF step and uses that same table or doc string.

A branch's own `DT:::...|` inline table replaces the inherited argument for that branch only. Any other inline type keeps the inherited argument.

A marker peeled off the end of the whole line is put back on the last branch only. Earlier branches keep the step's data table or doc string. That still happens when an earlier branch already contains the same marker text.

## Multi-step branches

End the branch with a colon and place its work beneath it:

```gherkin
* IF: "business" equals "<accountType>":
  : * , save "business-route" as "route"
  : * RUN SCENARIOS
      | Run Tags         | accountId   |
      | %prepare_account | <accountId> |
* ELSE:
  : * , save "personal-route" as "route"
```

Block branches can contain ordinary dynamic steps, nested conditions, tables, and component scenarios.

## Nested block conditionals

A block `IF:` / `ELSE-IF:` / `ELSE:` can sit inside another block branch. A falsy parent does not run the inner `IF`, even when that inner element would be truthy. A truthy parent runs the inner `IF`. The inner condition uses the same single-element rule: `"abc"` runs, and `""`, `0`, `false`, and a missing reference skip. The same chain works at one, two, and three levels. Each extra level adds one leading colon.

```gherkin
* IF: "abc":
  : * IF: "":
    :: * , save "no" as "inner"
    : * ELSE-IF: "abc":
    :: * , save "yes" as "inner"
* IF: false:
  : * IF: "abc":
    :: * , save "no" as "skipped"
```

Three levels:

```gherkin
* IF: "abc":
  : * IF: "abc":
    :: * IF: "abc":
      ::: * , save "yes" as "depth3"
```

An inline `IF ... THEN:` may sit inside a block `IF`. That inline step runs only when the outer block was taken.

A plain step between a false `IF:` and its `ELSE:` does not count as a taken branch. The `ELSE:` still runs. A plain step between a true `IF:` and its `ELSE:` does not make the `ELSE:` run.

```gherkin
* IF: false:
  : * , save "no" as "inner"
* , save "plain" as "between"
* ELSE:
  : * , save "else" as "branch"
```

A block condition may be an expression. `<{ 1? && !false }>` runs the block. `<{ "0"? || false }>` skips it. `&&`, `||`, `?:`, `!`, a trailing `?`, a skipped reference, and a `$` call on an untaken side follow the expression rules above.

## Logging and reports

At the normal `info` level, the selected branch's business steps remain prominent while control-flow details are reduced. Use `pkb_loglevel=debug` or `trace` when troubleshooting condition evaluation.

## Working example

See [nested-and-block-conditionals.feature](../maven-consumer-project/src/test/resources/features/nested-and-block-conditionals.feature).

`IF:` only chooses a branch. For fail-on-false assertions with the same clause syntax, see [Log and assert steps](log-and-assert-steps.md).

[Previous: Nested Steps](nested-steps.md) · [Documentation home](README.md) · [Next: Log and Assert Steps](log-and-assert-steps.md)
