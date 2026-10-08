# Log and Assert Steps

> **Working feature example:** [`log-and-assert-steps.feature`](../maven-consumer-project/src/test/resources/features/log-and-assert-steps.feature) covers author-facing `TRACE:` / `DEBUG:` / `INFO:` / `WARN:` tokens and passing `ASSERT:` / `SOFT ASSERT:` clauses. `ERROR:` / `FAIL:` hard-fail, hard-assert fail-fast, and multi-clause `SOFT ASSERT:` failures are observed by the feature's Java checks without failing the parent scenario. [`area-b-control-flow.feature`](../maven-consumer-project/src/test/resources/features/area-b-control-flow.feature) pins `END SCENARIO`, `END TEST`, scenario flags, and the `SOFT FAIL SCENARIO` observer.

Uppercase control tokens write a log line or evaluate the same conditions used after `IF:`. Optional whitespace is allowed after the colon.

```gherkin
* INFO: starting checkout
* ERROR: missing session
* FAIL: ready state never reached
* ASSERT: 1 < 2
* ASSERT: "A" equals "A"
* ASSERT: 'A' equals 'a'
* ASSERT: 1 < 2 || false
* ASSERT: 1 < 2 | "A" equals "A"
* SOFT ASSERT: 1 == 2 | "A" equals "B"
```

Templates in the rest of the line resolve the same way other ordinary step text does before the glue method runs. For example, `INFO: hello <name>` uses the current step ParsingMap if `<name>` is already saved.

## Logging tokens

`TRACE`, `DEBUG`, `INFO`, `WARN`, and `ERROR` are log levels. `FAIL` is not a log level; it is a control token that records a fail entry and then hard-fails the scenario.

| Step | Effect | Scenario result |
|---|---|---|
| `TRACE:` / `DEBUG:` / `INFO:` / `WARN:` | log the captured text and continue | stays passing |
| `ERROR:` | `logError(text)` then hard-fail | hard-failed |
| `FAIL:` | `logFail(text)` then hard-fail | hard-failed |

Existing fail steps are unchanged:

```gherkin
* FAIL SCENARIO "checkout did not finish"
* SOFT FAIL SCENARIO "optional warning"
* END SCENARIO
* END TEST
* Scenario Log: a free-form note
```

`FAIL SCENARIO` hard-fails. Later steps do not run, and Cucumber fails the scenario.

`SOFT FAIL SCENARIO` throws `SoftRuntimeException`. The step result is failed, so Cucumber fails the scenario. The scenario is marked soft-failed and is not marked complete, so later steps still run. That is not what `SOFT ASSERT:` does. `SOFT ASSERT:` logs every failing clause and does not throw, so Cucumber stays green and later steps run. A direct `SOFT FAIL SCENARIO` is not an `@all` step. The area B Java check observes the throw without failing the parent scenario.

`END SCENARIO` stops the rest of the current scenario. Steps after it do not run, including flagged steps. `END TEST` is the whole token only. A longer line is not this step. `END TEST` marks the scenario complete without failing it, so a later step runs only when a scenario flag below says it should.

## Scenario flags

These flags are the ones the runner reads. A child nested under the flag with a leading colon inherits the flag. A trailing colon on the flag step is optional, and the no-colon form is unchanged:

```gherkin
* ALWAYS RUN
  : * , save "cleaned" as "status"
* ALWAYS RUN:
  : * , save "cleaned" as "status"
* RUN IF SCENARIO FAILED
  : * , save "x" as "y"
```

An inline child requires the colon. The text after the colon runs as a child step under the flag, the same as a nested child:

```gherkin
* RUN IF SCENARIO FAILED: , save "x" as "y"
```

That line is the same as:

```gherkin
* RUN IF SCENARIO FAILED:
  : * , save "x" as "y"
```

| Flag | A child runs when |
|---|---|
| `ALWAYS RUN` | always, including after the scenario is complete |
| `RUN IF SCENARIO FAILED` | the scenario has a soft or hard failure |
| `RUN IF SCENARIO SOFT FAILED` | the scenario has a soft failure |
| `RUN IF SCENARIO HARD FAILED` | the scenario has a hard failure |
| `RUN IF SCENARIO PASSING` | the scenario has not failed |

A hard failure marks the scenario complete, so a later step without one of these flags is skipped. A soft failure does not mark the scenario complete, so a later step without a flag still runs. `RUN IF SCENARIO PASSING` uses the same colon and inline forms.

Internal prefixed `INFO:` / `ERROR:` / `FAIL ERROR:` steps used by Pickleball itself keep their invisible prefix and do not collide with these author-facing tokens.

## ASSERT vs IF vs ensure / verify

| Form | When the condition is false |
|---|---|
| `IF:` | selects another branch; a false condition does not fail the scenario |
| `ASSERT:` | fail-on-false, hard-fail, fail-fast across `\|` clauses |
| `SOFT ASSERT:` | fail-on-false, evaluate every `\|` clause, log each failure, and continue without throwing |
| `, ensure ...` | long-form hard assertion |
| `, verify ...` | long-form soft assertion |

`ASSERT:` and `SOFT ASSERT:` reuse the same clause syntax that follows `IF:` and the same `, ensure` / `, verify` evaluation path. Do not use `IF:` when a false result should fail the scenario.

Each clause may be:

- expression-style: `1 < 2`, `1 < 2 || false`, `(1 < 2) || (3 < 1)`
- phrase-style: `"A" equals "A"`
- case-insensitive phrase-style: `'A' equals 'a'`
- element / state assertions: `the "Validation Error" Text is displayed`

## `|` vs `||`

`|` separates clauses. `||` is boolean OR inside one clause.

These are one clause each:

```gherkin
* ASSERT: 1 < 2 || false
* ASSERT: (1 < 2) || (3 < 1)
* ASSERT: "A|B" equals "A|B"
* ASSERT: 'A|B' equals 'a|b'
* ASSERT: `A|B` equals `A|B`
```

These are two clauses:

```gherkin
* ASSERT: 1 < 2 | "A" equals "A"
* ASSERT: 1 < 2 || false | "A" equals "A"
```

`ASSERT:` evaluates clauses left to right and stops at the first failure. The failing clause text is logged, for example `ASSERT failed: 2 < 1`. Later clauses are not evaluated.

`SOFT ASSERT:` evaluates every clause, logs each failure separately (`SOFT ASSERT failed: "A" equals "B"`), and returns without throwing. Later steps in the same scenario still run, and Cucumber does not mark the scenario failed.

[Previous: Block Conditionals](block-conditionals.md) · [Documentation home](README.md) · [Next: Component Scenarios](component-scenarios.md)
