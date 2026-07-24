# Component Scenarios

> **Working feature example:** [`component-scenarios.feature`](../maven-consumer-project/src/test/resources/features/component-scenarios.feature) contains both the `RUN SCENARIOS` caller and the reusable `%save_customer` component definition.

Component scenarios are reusable, scenario-sized business flows. A caller uses `RUN SCENARIOS`, identifies a component with a `%` tag, and supplies values through a table.

## Call a component

```gherkin
* RUN SCENARIOS
    | Run Tags      | customerName | tier     |
    | %save_customer | Ava          | Premium  |
    | %save_customer | Ben          | Standard |
```

Each table row is a separate call. Pickleball finds the matching component, combines values, inserts its executable steps beneath the caller, and runs the component before continuing.

## Define a component

Use a `Scenario Outline` with a `Scenario Tags` column:

```gherkin
Scenario Outline: Save customer component
  * , enter "<customerName>" in the "Customer Name" Textbox
  * , select "<tier>" in the "Customer Tier" Dropdown
  * , click the "Save Customer" Button

Examples:
  | Scenario Tags | ?customerName  | tier     |
  | %save_customer | Default Customer | Standard |
```

The `%` prefix identifies a reusable component rather than a normal Cucumber `@tag`.

## Caller values and defaults

Values can come from:

- the caller's `RUN SCENARIOS` row; and
- the component's matching `Examples` row.

A normal component header supplies a default only when the caller omits the key. If the caller includes the key with a blank value, the blank remains.

Prefix a component header with `?` when the component default should also replace a blank caller value:

```gherkin
| Scenario Tags  | ?customerName  |
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

`RUN SCENARIOS` remains the parent step. Each called component and its executable steps appear beneath it. Components can be called inside nested or block-conditional branches.

Avoid component cycles that repeatedly call each other.

## Working example

See [component-scenarios.feature](../maven-consumer-project/src/test/resources/features/component-scenarios.feature) and the page it tests, [components.html](../maven-consumer-project/src/test/resources/site/components.html).

[Previous: Block Conditionals](block-conditionals.md) · [Documentation home](README.md) · [Next: Service-call Scenarios](service-call-scenarios.md)
