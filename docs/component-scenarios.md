# Component Scenarios

> **Working feature examples:** [`component-scenarios.feature`](../maven-consumer-project/src/test/resources/features/component-scenarios.feature) contains a reusable component. [`reusable-scenario-selection.feature`](../maven-consumer-project/src/test/resources/features/reusable-scenario-selection.feature) demonstrates selection, ordering, limits, and singular/plural behavior. [`scenario-data-references.feature`](../maven-consumer-project/src/test/resources/features/scenario-data-references.feature) demonstrates marker DataTable and DocString references.
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
    | RunKey   | customerName |
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

The first three hyphens identify the step as a marker. Any additional leading
hyphens or whitespace are removed before the marker is stored or matched. Once
the first non-hyphen, non-whitespace character is reached, the remaining text is
preserved apart from surrounding whitespace:

```gherkin
* ---payload
* ----payload
* ------ --payload
* ------ -- pay-load 2
```

The first three steps are all named `payload`. The final step is named
`pay-load 2`; the hyphen inside the name is preserved.

A marker with no text after normalization is unnamed. For example, `---`,
`----`, and `------ --` are unnamed markers. They are cached by their one-based
scenario step position for Java access and cannot be selected with `START:`,
`Step_Marker`, or an explicit `<data:...>` Gherkin reference.

Unnamed marker arguments can nevertheless be selected implicitly by an
unquoted `Data Table` or `Data` element in the same scenario. Lookup ignores
nesting, prefers the qualifying marker with the smallest source-line number
greater than the referencing step, and falls back to the qualifying marker with
the greatest source-line number smaller than the referencing step. See
[Data Values and Data Elements](data-values-and-elements.md#unquoted-data-table-and-data-elements).

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

Marker data can also be retrieved through Java:

```java
ScenarioStepData data = ModularScenarios.getScenarioMarkerData(
        "Customer record.payload"
);
```

The Java API returns `ScenarioStepData`, allowing callers to inspect marker text, passed values, Examples values, and attached arguments.

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

Unlike the Java API, a mapping reference resolves directly to the marker step's attached native `DataTable` or `DocString`. It does not return `ScenarioStepData`. A marker without either attached argument does not produce a mapping value.

For example, store a table on a marker in a feature under `src/test/resources/data`:

```gherkin
Feature: data store feature

  Scenario: data tables
    * ---DataTable B
      | Key1 | Key2 |
      | qq   | ww   |
      | ee   | rr   |
      | tt   | yy   |
```

The table can then be used directly by a dynamic `Data Table` element match in another scenario:

```gherkin
Then , in the "<data:data store feature.data tables.DataTable B>" Data Table, for every Data Row:
: * , save "<Key1>" as "B1", and save "<Key2>" as "B2"
```

The reference remains a native `DataTable`, so no JSON or text conversion is required before `for every Data Row` iterates it. The same address syntax returns a native `DocString` when the marker step has a DocString argument.

Data addresses require a nonblank marker name. An unnamed marker cannot be
referenced explicitly through `<data:...>`. It can be consumed implicitly by
an unquoted `Data Table` or `Data` element in the same scenario, or retrieved
from Java by its original one-based scenario position:

```java
StepExtension marker = scenarioStep.getUnnamedStepMarkerStep(3);
ScenarioStepData data = new ScenarioStepData(scenarioStep, marker);
Object table = data.getDataTableValue();
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
