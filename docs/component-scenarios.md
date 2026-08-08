# Component Scenarios

> **Working feature examples:** [`component-scenarios.feature`](../maven-consumer-project/src/test/resources/features/component-scenarios.feature) contains a reusable component. [`reusable-scenario-selection.feature`](../maven-consumer-project/src/test/resources/features/reusable-scenario-selection.feature) demonstrates named selectors, escaped names, ordering, limits, and singular/plural behavior. [`scenario-data-references.feature`](../maven-consumer-project/src/test/resources/features/scenario-data-references.feature) demonstrates marker and data-file references.

Component scenarios are reusable, scenario-sized flows. Use `RUN COMPONENT SCENARIO` or `RUN COMPONENT SCENARIOS` when reusable components are stored separately from regular feature scenarios.

Component lookup uses `pkb_componentpath`. When it is not configured, the path defaults to:

```text
src/test/resources/component
```

A nonblank `pkb_componentpath` value in an invocation table overrides the global setting for that row. `RUN SCENARIO` remains the regular-scenario form and uses the normal feature path.

## Named selector syntax

Inline named selectors use one shared path grammar:

```text
scenario
feature.scenario
feature.scenario.marker
```

Examples:

```gherkin
* RUN COMPONENT SCENARIO: Save customer component
* RUN COMPONENT SCENARIO: Reusable flows.Save customer component
* RUN COMPONENT SCENARIO: Reusable flows.Save customer component.submit section
```

The former inline labels `FEATURE:`, `SCENARIO:`, and `START:` are not supported.

An inline argument beginning with `@` or `%` remains a tag expression:

```gherkin
* RUN COMPONENT SCENARIO: %save_customer
    | customerName | tier    |
    | Ava          | Premium |
```

### Literal periods and backslashes

Only an unescaped period separates path components. Escape literal periods and backslashes as:

```text
\.    literal period
\\    literal backslash
```

For authored names:

```gherkin
Feature: Data.reference.records
Scenario: Customer.record
* ---payload.marker
```

the selector is:

```text
Data\.reference\.records.Customer\.record.payload\.marker
```

Punctuation and whitespace in the authored names are preserved. Scenario matching is exact rather than treating the scenario component as a regular expression.

Blank components and more than three unescaped components are rejected.

## Invocation options

Every invocation-table column is passed to the scenario scan. Common options include:

| Purpose | Pickleball option | Cucumber option |
|---|---|---|
| Component feature path | `pkb_componentpath` | — |
| Exact feature name | `pkb_featurename` | — |
| Scenario-name regex | `pkb_name` | `cucumber.filter.name` |
| Tag expression | `pkb_tags` or `Run Tags` | `cucumber.filter.tags` |
| Result order | `pkb_order` | `cucumber.execution.order` |
| Result limit | `pkb_limit` | `cucumber.execution.limit` |
| Start marker | `Step_Marker` | — |

```gherkin
* RUN COMPONENT SCENARIOS
    | pkb_featurename | pkb_name                  | pkb_order |
    | Reusable flows  | ^Save customer component$ | lexical   |
```

Inline path components overwrite their corresponding feature, scenario, and marker table selectors. An inline tag selector is combined with the row's existing tag selector behavior.

## Singular and plural cardinality

`RUN COMPONENT SCENARIO` allows zero or one result. `RUN COMPONENT SCENARIOS` allows multiple results. Ordering and limits are applied before cardinality is checked.

## RunMap keys

A deferred component run can save the selected scenario root by reference:

```gherkin
* RUN "savedCustomer" COMPONENT SCENARIO: %save_customer
```

A nonblank `RunKey` table value overrides the quoted key:

```gherkin
* RUN "quotedKey" COMPONENT SCENARIO: %save_customer
    | RunKey   | customerName |
    | tableKey | Ava          |
```

Only `tableKey` is used.

## Synchronous convenience forms

`SCENARIO:` and `COMPONENT:` execute exactly one selected scenario synchronously and use the same inline selector parser as their `RUN` equivalents:

```gherkin
* SCENARIO: Reusable flows.Save customer component
* COMPONENT: Reusable flows.Save customer component
```

They accept the same invocation DataTable behavior, marker selection, passed values, and selected Scenario Outline Examples row. A nonblank `RunKey` stores the returned value.

## Start and end markers

A component can contain no-op marker steps written as `---<marker text>`. Additional leading hyphens and whitespace after the required marker prefix are normalized by the existing marker behavior; punctuation inside the marker name remains part of the name.

```gherkin
* ---payload
* ------ -- pay-load 2
```

Without an override, `---startstep` removes preceding component steps. `---endstep` includes the end marker and removes following steps.

Select a custom start marker with the third path component:

```gherkin
* RUN COMPONENT SCENARIO: Reusable flows.Save customer.submit section
```

Or supply it in a row:

```gherkin
* RUN COMPONENT SCENARIO
    | pkb_featurename | pkb_name        | Step_Marker    |
    | Reusable flows  | ^Save customer$ | submit section |
```

If both are supplied, the inline third component overrides `Step_Marker`.

## Read marker data without execution

Java utilities use the same selector path:

```java
ScenarioStepData data = ModularScenarios.getScenarioStepData(
        "Reusable flows.Save customer.request data",
        invocationTable
);
```

Marker-data addresses are right-aligned:

```text
marker
scenario.marker
feature.scenario.marker
```

For example:

```java
ScenarioStepData data = ModularScenarios.getScenarioMarkerData(
        "Customer record.payload"
);
```

Escaped periods use the same grammar:

```text
Data\.reference\.records.Customer\.record.payload\.marker
```

Mapping references use the lowercase `data:` prefix:

```gherkin
<data:payload>
<data:Customer record.payload>
<data:Data reference records.Customer record.payload>
<data:Data\.reference\.records.Customer\.record.payload\.marker>
```

A marker mapping reference resolves to the marker's attached native `DataTable` or `DocString`. Java marker lookup APIs continue to return `ScenarioStepData`.

`pkb_datapath` controls named marker-data discovery and defaults to `src/test/resources/data` when no configured value is resolved.

## Data files under the data root

A slash immediately after `data:` switches to data-file mode:

```text
<data:/file>
<data:/files/customerPayload>
<data:/files/customerPayload.customer.orders[0].id>
```

The slash is a syntax discriminator, not an operating-system absolute path. Lookup is rooted below the same resolved `pkb_datapath` used for scenario data. File parsing, suffix-agnostic discovery, and nested queries reuse the existing `file:` resource machinery.

Structured whole-document and nested object/array results remain Jackson `JsonNode` values; scalar nested results retain their normal scalar type.

## Multiple invocation rows

```gherkin
* RUN COMPONENT SCENARIOS
    | Run Tags       | customerName | tier     |
    | %save_customer | Ava          | Premium  |
    | %save_customer | Ben          | Standard |
```

Each row is a separate invocation.

## Define a component

Use a `Scenario Outline` with a `Scenario Tags` column:

```gherkin
Scenario Outline: Save customer component
  * , enter "<customerName>" in the "Customer Name" Textbox
  * , select "<tier>" in the "Customer Tier" Dropdown
  * , click the "Save Customer" Button
Examples:
  | Scenario Tags  | ?customerName    | tier     |
  | %save_customer | Default Customer | Standard |
```

The `%` prefix identifies a reusable component. Values come from the caller's invocation row and matching Examples row.

## Nesting and reports

The `RUN COMPONENT SCENARIO` or `RUN COMPONENT SCENARIOS` step remains the parent. Each selected component and its executable steps appear beneath it. Avoid component cycles.

[Previous: Block Conditionals](block-conditionals.md) · [Documentation home](README.md) · [Next: Service-call Scenarios](service-call-scenarios.md)
