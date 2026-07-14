# Block Conditionals: `IF:`, `ELSE-IF:`, and `ELSE:`

Block conditionals let a scenario choose one business path while normal reports show only the path that actually ran.

At the normal `info` log level, the condition and its true/false result are treated as troubleshooting detail. The selected branch’s business steps remain visible. Set `pkb_loglevel=debug` or `trace` when those condition details are needed.

## Keywords

| Keyword | Purpose |
|---|---|
| `IF:` | Starts a branch and evaluates a condition |
| `ELSE-IF:` | Tries another condition when no earlier branch matched |
| `ELSE:` | Runs when no earlier branch matched |
| `THEN:` | Separates an inline condition from the work to perform |

Use the uppercase spellings shown above. Do not place a comma before the block keyword:

```gherkin
* IF: 1 == 1 THEN: , save "A" as "result"
```

A normal dynamic conditional begins differently:

```gherkin
* , if 1 == 1, save "A" as "result"
```

## Two ways to write a condition

An `IF:` or `ELSE-IF:` condition may be written as:

1. a **phrase-style assertion**, using the same business-language rules as dynamic `if`; or
2. an **expression-style condition**, using explicit comparison and Boolean symbols.

## Phrase-style assertions

Phrase-style conditions use the same syntax as the assertions in [Dynamic Steps](dynamic-steps.md):

- element and value descriptions;
- comparisons and state checks;
- comma-separated assertion chains;
- `and` and `or`;
- natural-language inheritance; and
- implicit truthiness.

The only difference is the opening token:

- use `IF:` instead of `, if`;
- use `ELSE-IF:` instead of `, else if`; and
- do not put a comma before the block token.

This dynamic condition:

```gherkin
Then , if 3 is equal to 1, 2, or 3, save "matched" as "result"
```

can be written as:

```gherkin
* IF: 3 is equal to 1, 2, or 3 THEN: , save "matched" as "result"
```

The assertion chain still means:

```text
3 is equal to 1
3 is equal to 2
3 is equal to 3
```

An assertion at the end can apply to earlier elements:

```gherkin
* IF: the "Agree" Checkbox, the "Submit" Button, or the "Refresh" Button are displayed:
: Then , save "All required controls are visible" as "result"
* ELSE:
: Then , save "A required control is missing" as "result"
```

A value can also be evaluated directly for truthiness:

```gherkin
* IF: <featureEnabled>:
: Then , save "enabled" as "featureState"
* ELSE:
: Then , save "disabled" as "featureState"
```

Separate every phrase with a comma:

```gherkin
* IF: Error Banner is not displayed, and the 3rd Link does not contain "failed":
: Then , save "clean" as "pageState"
```

## Expression-style conditions

Expression-style conditions use explicit operators rather than English assertion phrases:

```gherkin
* IF: 1 < 4 && true && 6 && "A" THEN: print "this is true!!"
```

Common operators include:

```text
==   !=   <   <=   >   >=
&&   ||   !
```

Parentheses may be used for grouping:

```gherkin
* IF: (1 < 4 && 6) || false THEN: , save "true" as "result"
```

### Truthiness in expressions

When a number or string is used where a Boolean is needed, the normal truthiness rules apply:

- non-zero numbers are true;
- zero is false;
- nonblank text is generally true;
- blank text is false;
- recognized false values are false; and
- unresolved templates are false-like.

```gherkin
* IF: <retryCount> && <featureName> && !<disabled>:
: Then , save "ready" as "state"
```

### No grammatical inheritance

Expression parts are evaluated independently. They do not borrow a comparison, subject, or value from another part.

```gherkin
* IF: 1 < 4 && 6 && "A":
: Then , save "true" as "result"
```

This means:

- `1 < 4` is one complete comparison;
- `6` is checked for truthiness; and
- `"A"` is checked for truthiness.

It does **not** mean `1 < 6` or `1 < "A"`.

Use phrase-style assertions when the condition should read like business language:

```gherkin
* IF: the "Submit" Button is displayed, and the "Agree" Checkbox is checked:
```

Use an expression when explicit symbolic logic is clearer:

```gherkin
* IF: <attempts> < 3 && <enabled> && !<cancelled>:
```

## Inline branches with `THEN:`

Use `THEN:` when the condition and result fit naturally on one line:

```gherkin
* IF: 5 == 1 THEN: , save "A" as "shortCircuitResult" ELSE-IF: 5 == 2 THEN: , save "B" as "shortCircuitResult" ELSE-IF: 5 == 3 THEN: , save "C" as "shortCircuitResult" ELSE-IF: 5 == 4 THEN: , save "D" as "shortCircuitResult" ELSE: , save "E" as "shortCircuitResult"
```

Branches are considered from left to right. Only the first matching branch runs. In this example, the result is `E`.

Use a nested block instead when the line becomes hard to read.

## Multi-step branches

End a branch line with a colon and place its steps beneath it:

```gherkin
* IF: "z" equals "A":
: Then , save "Q1" as "W"
* ELSE-IF: "B" equals "A":
: Then , save "Q2" as "W"
* ELSE-IF: "C" equals "A":
: Then , save "Q3" as "W"
* ELSE:
: Then , save "Q4" as "W"
```

Keep all members of the branch chain at the same nesting level.

## Nested block conditions

A selected branch may contain more conditions, ordinary dynamic steps, data tables, or component scenarios:

```gherkin
* IF: "accountType" equals "business":
: * IF: "country" equals "US":
:: Then , save "domestic-business" as "route"
: * ELSE:
:: Then , save "international-business" as "route"
* ELSE:
: Then , save "personal" as "route"
```

Each additional leading colon adds another level.

## Report behavior

At the normal log level, reports focus on the steps from the selected branch. Unselected branch steps do not appear as executed business behavior.

To show condition details while troubleshooting, add this to `pickleball_local.properties`:

```properties
pkb_loglevel=debug
```

or run:

```bash
mvn test -Dpkb_loglevel=debug
```

## Choosing a conditional style

Use a normal dynamic conditional when the condition itself is part of the reportable business sentence:

```gherkin
Then , if the "Refresh" Link is displayed, click the "Refresh" Link
```

Use a block conditional when the report should emphasize the chosen path:

```gherkin
* IF: the "Refresh" Link is displayed:
: Then , click the "Refresh" Link
* ELSE:
: Then , save "No refresh was needed" as "result"
```

## Rules to remember

1. Begin with uppercase `IF:`, `ELSE-IF:`, or `ELSE:`.
2. Do not place a dynamic-step comma before the block token.
3. Phrase-style conditions use the same chaining, separator, inheritance, and truthiness rules as dynamic assertions.
4. Expression-style conditions use explicit operators and no grammatical inheritance.
5. Use `THEN:` for an inline result.
6. Use a trailing colon and nested steps for a multi-step branch.
7. Keep related branches at the same nesting level.
8. Only the first matching branch runs.
9. Use `debug` or `trace` logging only when condition details are needed.

---

[Previous: Nested Steps](nested-steps.md) · [Documentation home](README.md) · [Next: Component Scenarios](component-scenarios.md)
