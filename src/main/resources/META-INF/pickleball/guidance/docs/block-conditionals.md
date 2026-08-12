# Block Conditionals

> **Working feature example:** [`nested-and-block-conditionals.feature`](../maven-consumer-project/src/test/resources/features/nested-and-block-conditionals.feature) demonstrates `IF:`, `ELSE-IF:`, and `ELSE:` branches together with nested executable steps.

Block conditionals choose one business path while normal reports emphasize the steps that actually ran.

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

A direct value can be used as a truthy or false-like condition:

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

## Inline branch chains

Use `THEN:` when the result fits on one line:

```gherkin
* IF: 5 == 1 THEN: , click the "Use Error State" Button
  ELSE-IF: 5 == 5 THEN: , click the "Use Ready State" Button
  ELSE: , click the "Use Review State" Button
```

Branches are considered from left to right. Only the first matching branch runs.

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

## Logging and reports

At the normal `info` level, the selected branch's business steps remain prominent while control-flow details are reduced. Use `pkb_loglevel=debug` or `trace` when troubleshooting condition evaluation.

## Working example

See [nested-and-block-conditionals.feature](../maven-consumer-project/src/test/resources/features/nested-and-block-conditionals.feature).

[Previous: Nested Steps](nested-steps.md) · [Documentation home](README.md) · [Next: Component Scenarios](component-scenarios.md)
