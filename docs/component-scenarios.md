# Component Scenarios

> **Working feature examples:** [`component-scenarios.feature`](../maven-consumer-project/src/test/resources/features/component-scenarios.feature) contains a reusable component. [`reusable-scenario-selection.feature`](../maven-consumer-project/src/test/resources/features/reusable-scenario-selection.feature) demonstrates selection, ordering, limits, and singular/plural behavior.

Component scenarios are reusable, scenario-sized business flows. Use `RUN COMPONENT SCENARIO` or `RUN COMPONENT SCENARIOS` when reusable components are stored separately from regular feature scenarios.

Component lookup uses `pkb_componentpath`. When it is not configured, the path defaults to:

```text
src/test/resources/component
```

A nonblank `pkb_componentpath` value in an invocation table overrides the global setting for that row. A blank table value is ignored.

`RUN SCENARIO` remains the regular-scenario form and continues to use the project's normal feature-path configuration.

## Select components inline

Inline arguments beginning with `@` or `%` are Cucumber tag expressions:

```gherkin
* RUN COMPONENT SCENARIO: %save_customer
    | customerName | tier    |
    | Ava          | Premium |
```

Select an exact scenario name with `SCENARIO:`:

```gherkin
* RUN COMPONENT SCENARIO: SCENARIO: Save customer component
    | customerName | tier    |
    | Ava          | Premium |
```

Select an exact feature with `FEATURE:`. Feature and scenario selectors can be combined:

```gherkin
* RUN COMPONENT SCENARIO: FEATURE: Reusable flows SCENARIO: Save customer component
```

Each labelled value continues until the next `FEATURE:`, `SCENARIO:`, or `START:` label. Unlabelled non-tag text is rejected.

## Select with invocation options

Every invocation-table column is passed to the scenario scan. Supported options include:

| Purpose | Pickleball option | Cucumber option |
|---|---|---|
| Component feature path | `pkb_componentpath` | — |
| Exact feature name | `pkb_featurename` | — |
| Scenario-name regex | `pkb_name` | `cucumber.filter.name` |
| Tag expression | `pkb_tags` or `Run Tags` | `cucumber.filter.tags` |
| Result order | `pkb_order` | `cucumber.execution.order` |
| Result limit | `pkb_limit` | `cucumber.execution.limit` |

```gherkin
* RUN COMPONENT SCENARIOS
    | pkb_featurename | pkb_name                  | pkb_order |
    | Reusable flows  | ^Save customer component$ | lexical   |
```

A nonblank tag, feature-name, or scenario-name filter must match at least one scenario. Path, ordering, and limit options do not select scenarios by themselves.

## Singular and plural cardinality

`RUN COMPONENT SCENARIO` allows zero or one result. `RUN COMPONENT SCENARIOS` allows multiple results.

Ordering and limits are applied before cardinality is checked. Invocation-table row order is preserved, and each row's matches execute in their returned order.

## RunMap keys

A deferred component run can save the selected scenario root by reference:

```gherkin
* RUN "savedCustomer" COMPONENT SCENARIO: %save_customer
```

A `RunKey` table value overrides the quoted key:

```gherkin
* RUN "quotedKey" COMPONENT SCENARIO: %save_customer
    | RunKey  | customerName |
    | tableKey | Ava          |
```

Only `tableKey` is used. When neither a nonblank `RunKey` nor a quoted key is supplied, no RunMap value is saved.

## Synchronous convenience form

`COMPONENT:` is the singular synchronous convenience form:

```gherkin
* COMPONENT: %save_customer
    | RunKey | customerName |
    | saved  | Ava          |
```

It uses the same selector parsing, active parsing map, invocation DataTable, inline `DT:::`, passed values, selected Scenario Outline Examples row, and marker behavior as `RUN COMPONENT SCENARIO`.

It requires exactly one result and returns the selected scenario's `RETURN` value when that field exists. Otherwise it returns the selected scenario root. A nonblank `RunKey` saves that same returned value.

## Start and end markers

A component can contain no-op marker steps written as `---<marker text>`.

Without an override, `---startstep` removes preceding component steps. `---endstep` includes the end marker and removes following steps:

```gherkin
Scenario: Reusable section
  * , verify "this failure" equals "is skipped"
  : * ---startstep
  : * , verify "selected body" equals "selected body"
  * ---endstep
  * , verify "this failure" equals "is also skipped"
```

Override the start marker inline:

```gherkin
* RUN COMPONENT SCENARIO: FEATURE: Reusable flows SCENARIO: Save customer START: submit section
```

Or per invocation row:

```gherkin
* RUN COMPONENT SCENARIO
    | pkb_featurename | pkb_name        | Step_Marker    |
    | Reusable flows  | ^Save customer$ | submit section |
```

Inline `START:` overrides `Step_Marker`. Marker comparison is exact after trimming and is case-insensitive.

## Read marker data without execution

Java utilities can use the same inline arguments and optional `DataTable` without attaching or executing the selected component:

```java
ScenarioStepData data = ModularScenarios.getScenarioStepData(
        "FEATURE: Reusable flows SCENARIO: Save customer START: request data",
        invocationTable
);
```

The lookup uses the shared scenario scan, ordering, limit, passed-map, Examples-map, and marker-selection behavior. It must resolve to at most one component.

Resolved getters accept an additional passed `NodeMap`:

```java
NodeMap overrides = new NodeMap(MapConfigurations.MapType.PASSED_MAP);
overrides.put("customerName", "Ava");

String marker = data.getStepMarkerText(overrides);
Object table = data.getDataTableValue(overrides);
```

Resolution precedence is:

1. getter-supplied passed values;
2. stored invocation passed values;
3. selected Scenario Outline Examples values.

`STEP_MAP` and `PHRASE_MAP` are inherited from the currently running parsing map.

## Scenario marker data references

Marker data can also be retrieved through:

```java
ScenarioStepData data = ModularScenarios.getScenarioMarkerData(
        "Customer record.payload"
);
```

Supported addresses are:

```text
marker
scenario.marker
feature.scenario.marker
```

`pkb_datapath` overrides the lookup path. Marker-only lookup can use the closest running scenario's in-memory marker index.

Mapping references use the lowercase `data:` prefix:

```gherkin
<data:payload>
<data:Customer record.payload>
<data:Data reference records.Customer record.payload>
```

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

The `%` prefix identifies a reusable component. Values come from the caller's invocation row and the matching Examples row. A normal Examples header provides a default only when the caller omits the key. Prefix a header with `?` when its default should also replace a blank caller value.

## Nesting and reports

The `RUN COMPONENT SCENARIO` or `RUN COMPONENT SCENARIOS` step remains the parent. Each selected component and its executable steps appear beneath it. Avoid component cycles.

[Previous: Block Conditionals](block-conditionals.md) · [Documentation home](README.md) · [Next: Service-call Scenarios](service-call-scenarios.md)
