# Component Scenarios

> **Working feature examples:** [`component-scenarios.feature`](../maven-consumer-project/src/test/resources/features/component-scenarios.feature) contains a reusable component. [`reusable-scenario-selection.feature`](../maven-consumer-project/src/test/resources/features/reusable-scenario-selection.feature) demonstrates named selectors, escaped names, ordering, limits, and singular/plural behavior. [`run-step-parameter-variations.feature`](../maven-consumer-project/src/test/resources/features/run-step-parameter-variations.feature) demonstrates the canonical table-driven `RUN` form, mixed run types, and shorthand variations. [`scenario-data-references.feature`](../maven-consumer-project/src/test/resources/features/scenario-data-references.feature) demonstrates marker and data-file references.

Component scenarios are reusable, scenario-sized flows. `RUN` is the common dispatcher for regular scenarios, component scenarios, and service calls.

## Preferred table-driven RUN form

Prefer a single `RUN` step with one DataTable row per invocation. The `RunType` column tells Pickleball what each row runs, so related regular scenarios, components, and service calls can be composed in one concise step:

```gherkin
When RUN
  | RunType            | RunKey | Run Tags          |
  | SCENARIO           | setup  | %setup            |
  | COMPONENT SCENARIO | login  | %login-component  |
  | SERVICE CALL       | health | %health-full-url  |
```

`RunType` accepts six values:

```text
SCENARIO
SCENARIOS
COMPONENT SCENARIO
COMPONENT SCENARIOS
SERVICE CALL
SERVICE CALLS
```

Multiplicity belongs to the resolved `RunType` for each row. Singular values allow at most one match for that row; plural values allow multiple matches for that row. Different rows in the same bare `RUN` may use different types and multiplicities.

Inline step parameters are shorthand for values shared by the invocation rows, or for calls that do not need a DataTable. For example, these are concise forms when every row is a regular scenario:

```gherkin
When RUN SCENARIO
  | Run Tags |
  | %tagA    |
```

and when no table is needed:

```gherkin
When RUN SCENARIO: %tagA
```

A nonblank table `RunType` overrides the inline type for that row, including singular/plural multiplicity. A nonblank table `RunKey` similarly overrides the quoted inline key. Inline selectors continue to apply as shared shorthand to all table rows.

Component lookup uses `pkb_componentpath`. When it is not configured, the path defaults to:

```text
src/test/resources/component
```

A nonblank `pkb_componentpath` value in an invocation table overrides the global setting for that row. Regular `SCENARIO` rows use the normal feature path, while `SERVICE CALL` rows use the service-call path.

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
| Run kind/cardinality | `RunType` | — |
| Saved result key | `RunKey` | — |
| Component feature path | `pkb_componentpath` | — |
| Exact feature name | `pkb_featurename` | — |
| Scenario-name regex | `pkb_name` | `cucumber.filter.name` |
| Tag expression | `pkb_tags` or `Run Tags` | `cucumber.filter.tags` |
| Result order | `pkb_order` | `cucumber.execution.order` |
| Result limit | `pkb_limit` | `cucumber.execution.limit` |
| Start marker | `Step_Marker` | — |

```gherkin
* RUN
    | RunType            | pkb_featurename | pkb_name                  | pkb_order |
    | COMPONENT SCENARIOS | Reusable flows   | ^Save customer component$ | lexical   |
```

Inline path components overwrite their corresponding feature, scenario, and marker table selectors. An inline tag selector is combined with the row's existing tag selector behavior.

## Singular and plural cardinality

Cardinality is validated independently for each invocation row after ordering and limits are applied. `SCENARIO`, `COMPONENT SCENARIO`, and `SERVICE CALL` allow at most one match for their row. Their plural forms allow multiple matches.

An inline type supplies the default for rows without a nonblank `RunType`:

```gherkin
* RUN COMPONENT SCENARIO
    | Run Tags       |
    | %save_customer |
```

A row can override that default, including its multiplicity:

```gherkin
* RUN COMPONENT SCENARIO
    | RunType            | Run Tags       |
    | COMPONENT SCENARIOS | %save_customer |
```

## RunMap keys and returned values

A keyed `RUN` saves its result only after the selected scenario has completed. The saved value follows the same result contract as the synchronous convenience forms: if the completed scenario contains `RETURN`, that value is saved; otherwise the completed scenario-map root is saved.

```gherkin
* RUN "savedCustomer" COMPONENT SCENARIO: %save_customer
```

A nonblank `RunKey` table value overrides the quoted key:

```gherkin
* RUN "quotedKey" COMPONENT SCENARIO: %save_customer
    | RunKey   | customerName |
    | tableKey | Ava          |
```

Only `tableKey` is used. Result storage uses normal RunMap/NodeMap writes. Reusing an ordinary top-level `RunKey` appends another value to that key's collection; an unindexed read continues to resolve the latest value, while `#1`, `#2`, and other collection selectors can address earlier results.

## Synchronous convenience forms

`SCENARIO:` and `COMPONENT:` execute exactly one selected scenario synchronously and use the same inline selector parser as their `RUN` equivalents:

```gherkin
* SCENARIO: Reusable flows.Save customer component
* COMPONENT: Reusable flows.Save customer component
```

They accept the same invocation DataTable behavior, marker selection, passed values, and selected Scenario Outline Examples row. A nonblank `RunKey` stores the returned value with the same normal RunMap collection semantics used by keyed `RUN`.

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
* RUN
    | RunType            | pkb_featurename | pkb_name        | Step_Marker    |
    | COMPONENT SCENARIO | Reusable flows  | ^Save customer$ | submit section |
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

Each DataTable row is an independent invocation. Prefer bare `RUN` when rows differ in type or other parameters:

```gherkin
* RUN
    | RunType            | Run Tags       | customerName | tier     |
    | COMPONENT SCENARIO | %save_customer | Ava          | Premium  |
    | COMPONENT SCENARIO | %save_customer | Ben          | Standard |
```

When all rows share a type, moving that common value into the step text is equivalent shorthand:

```gherkin
* RUN COMPONENT SCENARIO
    | Run Tags       | customerName | tier     |
    | %save_customer | Ava          | Premium  |
    | %save_customer | Ben          | Standard |
```

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

The outer `RUN` step remains the parent. Each selected scenario/component/service-call and its executable steps appear beneath it. Final result assignment is infrastructure finalization after the selected scenario subtree completes; it is not authored as another Gherkin child step. Avoid component cycles.

[Previous: Block Conditionals](block-conditionals.md) · [Documentation home](README.md) · [Next: Service-call Scenarios](service-call-scenarios.md)
