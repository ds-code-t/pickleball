# Component Scenarios

> **Working feature examples:** [`component-scenarios.feature`](../maven-consumer-project/src/test/resources/features/component-scenarios.feature) contains the reusable `%save_customer` component. [`reusable-scenario-selection.feature`](../maven-consumer-project/src/test/resources/features/reusable-scenario-selection.feature) demonstrates tag, scenario-name, feature-name, ordering, limit, and singular/plural selection.

Component scenarios are reusable, scenario-sized business flows. A caller uses `RUN SCENARIO` or `RUN SCENARIOS`, selects one or more component scenarios, and optionally supplies values through a table.

## Select components inline

Inline arguments beginning with `@` or `%` are Cucumber tag expressions:

```gherkin
* RUN SCENARIO: %save_customer
    | customerName | tier    |
    | Ava          | Premium |
```

Any other inline argument is treated as an exact scenario name:

```gherkin
* RUN SCENARIO: Save customer component
    | customerName | tier    |
    | Ava          | Premium |
```

Include a feature name before the first `.` to restrict the exact scenario-name match:

```gherkin
* RUN SCENARIO: Reuse a customer-saving business flow.Save customer component
    | customerName | tier    |
    | Ava          | Premium |
```

Both names must be present in the qualified `Feature name.Scenario name` form. Because the first `.` is the separator, use table options when a literal feature or scenario name itself contains a period.

## Select components with Cucumber options

Every invocation-table column is passed to the scenario scan. Supported selection and result options include:

| Purpose | Pickleball option | Cucumber option |
|---|---|---|
| Feature paths | `pkb_features` | `cucumber.features` |
| Exact feature name | `pkb_featurename` | — |
| Scenario-name regex | `pkb_name` | `cucumber.filter.name` |
| Tag expression | `pkb_tags` or `Run Tags` | `cucumber.filter.tags` |
| Result order | `pkb_order` | `cucumber.execution.order` |
| Result limit | `pkb_limit` | `cucumber.execution.limit` |

For example:

```gherkin
* RUN SCENARIOS
    | pkb_featurename                       | pkb_name                    | pkb_order |
    | Reuse a customer-saving business flow | ^Save customer component$   | lexical   |
```

A nonblank tag, feature-name, or scenario-name filter must match at least one component scenario. Otherwise the step throws a descriptive no-match error.

Feature paths, ordering, and limits do not select scenarios by themselves. When all tag, feature-name, and scenario-name filters are blank or absent, the step returns silently and executes no components.

## Singular and plural cardinality

`RUN SCENARIO` allows zero or one returned component. `RUN SCENARIOS` allows any number.

Ordering and limit options are applied before cardinality is checked. Therefore a broad selector may be used with `pkb_limit = 1` in a singular step. If a singular step still returns more than one scenario, it throws an error naming the matches and instructing the caller to use `RUN SCENARIOS`.

Plural results execute in the order returned by the existing Cucumber ordering and limit logic. When the invocation table contains multiple rows, row order is preserved and each row's returned scenarios execute in their returned order.

## Call a component once per table row

```gherkin
* RUN SCENARIOS
    | Run Tags       | customerName | tier     |
    | %save_customer | Ava          | Premium  |
    | %save_customer | Ben          | Standard |
```

Each table row is a separate call. Pickleball finds the matching component or components, combines values, inserts their executable steps beneath the caller, and runs them before continuing.

## Define a component

Use a `Scenario Outline` with a `Scenario Tags` column:

```gherkin
Scenario Outline: Save customer component
  * , enter "<customerName>" in the "Customer Name" Textbox
  * , select "<tier>" in the "Customer Tier" Dropdown
  * , click the "Save Customer" Button

Examples:
  | Scenario Tags  | ?customerName   | tier     |
  | %save_customer | Default Customer | Standard |
```

The `%` prefix identifies a reusable component rather than a normal Cucumber `@tag`.

## Caller values and defaults

Values can come from:

- the caller's invocation-table row; and
- the component's matching `Examples` row.

A normal component header supplies a default only when the caller omits the key. If the caller includes the key with a blank value, the blank remains.

Prefix a component header with `?` when the component default should also replace a blank caller value:

```gherkin
| Scenario Tags  | ?customerName   |
| %save_customer | Default Customer |
```

| Situation | Value used |
|---|---|
| caller supplies a nonblank value | caller value |
| caller supplies blank; normal component header | blank caller value |
| caller supplies blank; `?` component header | component default |
| caller omits the key | component default, when present |
| key exists only in caller | caller value |
| key exists only in component | component value |

## Nesting and reports

The `RUN SCENARIO` or `RUN SCENARIOS` step remains the parent. Each called component and its executable steps appear beneath it. Components can be called inside nested or block-conditional branches.

Avoid component cycles that repeatedly call each other.

## Working examples

See:

- [component-scenarios.feature](../maven-consumer-project/src/test/resources/features/component-scenarios.feature);
- [reusable-scenario-selection.feature](../maven-consumer-project/src/test/resources/features/reusable-scenario-selection.feature); and
- [components.html](../maven-consumer-project/src/test/resources/site/components.html).

[Previous: Block Conditionals](block-conditionals.md) · [Documentation home](README.md) · [Next: Service-call Scenarios](service-call-scenarios.md)
