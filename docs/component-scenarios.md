# Component Scenarios
> **Working feature examples:** [`component-scenarios.feature`](../maven-consumer-project/src/test/resources/features/component-scenarios.feature) contains the reusable `%save_customer` component. [`reusable-scenario-selection.feature`](../maven-consumer-project/src/test/resources/features/reusable-scenario-selection.feature) demonstrates tag, scenario-name, feature-name, ordering, limit, and singular/plural selection.
[`scenario-step-markers.feature`](../maven-consumer-project/src/test/resources/features/scenario-step-markers.feature) covers default, custom, nested, and end markers. [`scenario-marker-data.feature`](../maven-consumer-project/src/test/resources/features/scenario-marker-data.feature) demonstrates reading marker data without executing the selected component.
Component scenarios are reusable, scenario-sized business flows. A caller uses `RUN SCENARIO` or `RUN SCENARIOS`, selects one or more component scenarios, and optionally supplies values through a table.
## Select components inline

Inline arguments beginning with `@` or `%` remain Cucumber tag expressions:

```gherkin
* RUN SCENARIO: %save_customer
    | customerName | tier    |
    | Ava          | Premium |
```

Name selection is explicit. Prefix an exact scenario name with `SCENARIO:`:

```gherkin
* RUN SCENARIO: SCENARIO: Save customer component
    | customerName | tier    |
    | Ava          | Premium |
```

Prefix an exact feature name with `FEATURE:`. Feature and scenario selectors can be combined in either order:

```gherkin
* RUN SCENARIO: FEATURE: Reuse a customer-saving business flow SCENARIO: Save customer component
```

Each labelled value continues until the next `FEATURE:`, `SCENARIO:`, or `START:` label. Unlabelled non-tag text is rejected rather than guessed as a feature or scenario name. This allows periods and other punctuation to remain part of either name.
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
## Start and end markers

A component can contain no-op marker steps defined by `---<marker text>`.

Without an override, `---startstep` removes every component step that precedes it. `---endstep` includes the end marker and removes every step that follows it:

```gherkin
Scenario: Reusable section
  * , verify "this failure" equals "is skipped"
  : * ---startstep
  : * , verify "selected body" equals "selected body"
  * ---endstep
  * , verify "this failure" equals "is also skipped"
```

A start marker may be nested. Pickleball retains the existing placeholder-padding behavior so the selected nested step tree remains valid after earlier ancestors are removed.

Each `ScenarioStep` indexes all marker steps from the original scenario before start/end execution filtering is applied. Named marker keys retain their original unresolved text. Empty marker text and marker text containing only additional `-` characters are stored separately by the marker's one-based position in the original scenario step list.

The marker indexes are available through:

```java
Map<String, StepExtension> namedMarkers =
        scenarioStep.getStepMarkerSteps();
Map<Integer, StepExtension> unnamedMarkers =
        scenarioStep.getUnnamedStepMarkerSteps();
```

`getStepMarkerStep(String)` resolves each stored named key against the currently running parsing map and performs an exact, trimmed, case-insensitive comparison. If multiple unresolved keys resolve to the same marker name, the last matching marker in scenario order is returned. Unnamed markers are retrieved by scenario step number through `getUnnamedStepMarkerStep(int)` and are not considered by named lookup.

Override the component start marker inline with `START:`:

```gherkin
* RUN SCENARIO: FEATURE: Reusable flows SCENARIO: Save customer START: submit section
```

The component then starts at `---submit section`. For that invocation, `---startstep` is no longer treated as the start marker. `---endstep` remains fixed.
The same override can be supplied per invocation-table row:

```gherkin
* RUN SCENARIO
    | pkb_featurename | pkb_name        | Step_Marker   |
    | Reusable flows  | ^Save customer$ | submit section |
```

Inline `START:` overrides a `Step_Marker` value from the table. Marker text is resolved with the component parsing map before it is compared. Matching is exact after trimming and is case-insensitive.
## Read marker data without execution

Java utilities can select a component with the same inline arguments and optional
`DataTable` used by `RUN SCENARIO`, then read the selected start-marker step
without attaching or executing the component:

```java
ScenarioStepData data = ModularScenarios.getScenarioStepData(
        "FEATURE: Reusable flows SCENARIO: Save customer START: request data",
        invocationTable
);
```

The lookup uses the existing component scan, ordering, limit, passed-map,
Examples-map, and marker-selection behavior. It must return at most one
component scenario. A missing or blank `START:`/`Step_Marker` returns `null`.
A selector that returns multiple scenarios throws a descriptive ambiguity
error.
`ScenarioStepData` exposes the marker step text, marker text, step expression,
DocString argument value, DataTable argument value, stored passed map, and
stored Examples map. No getter executes the selected step.

Unresolved getters preserve Pickleball template references that remain after
Cucumber expands Scenario Outline Examples values. Resolved getter overloads
accept an additional passed `NodeMap`:
```java
NodeMap overrides = new NodeMap(MapConfigurations.MapType.PASSED_MAP);
overrides.put("customerName", "Ava");

String marker = data.getStepMarkerText(overrides);
Object table = data.getDataTableValue(overrides);
```

Resolved getters create a fresh parsing map when called. They inherit only the
running context's `STEP_MAP` and `PHRASE_MAP`, matching component
`ScenarioStep` inheritance. Data precedence is:
1. The getter's passed `NodeMap`
2. The stored component passed map
3. The selected Scenario Outline Examples map
## Scenario marker data references

Scenario marker data can be retrieved without attaching or executing the selected
component scenario:

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

Periods separate address components, so feature names, scenario names, and marker
names used by this convenience syntax cannot contain periods. Use
`getScenarioStepData(...)` with explicit `FEATURE:`, `SCENARIO:`, and `START:`
arguments when those names contain periods.

`pkb_datapath` overrides the lookup feature path. When it is not configured:
- an explicitly named scenario defaults to `src/test/resources/data`;
- a marker-only address with no option rows uses the in-memory marker index on
  the closest running component scenario or root scenario, avoiding feature
  scanning and duplicate `ScenarioStep` construction;
- marker-only lookup resolves unresolved marker keys against the currently
  running parsing map before matching;
- marker-only lookup with option rows continues through the existing
  `RUN SCENARIO` filtering path so passed values, selectors, ordering, and limits
  retain their existing behavior;
- scenario, tag, feature, ordering, and limit options supplied in the optional
  DataTable are passed through to the existing `RUN SCENARIO` filtering logic;
- a `pkb_features` or `cucumber.features` option supplied in that table remains
  in effect.

Mapping references use the lowercase `data:` source prefix. This prefix is
resolved alongside other source prefixes, such as `file:`, before ordinary map
lookup:

```gherkin
<data:payload>
<data:Customer record.payload>
<data:Data reference records.Customer record.payload>
```
A complete data reference resolves to `ScenarioStepData`; embedded use converts
the object to text in the same way as other non-string reference values.

The `&` namespace is separate and always resolves a step return value through
`getReturnValue(reference)`. `<&data:...>` therefore addresses a step return
named `data:...`; it no longer performs scenario-marker lookup.

The data snapshot retains the marker step's unresolved values plus defensive
copies of its passed and Examples maps. Resolved getters use this precedence:

1. the getter-supplied passed `NodeMap`;
2. the stored passed `NodeMap`;
3. the stored Examples `NodeMap`.

At getter time, `STEP_MAP` and `PHRASE_MAP` are inherited from the currently
running parsing map, matching ScenarioStep child inheritance.
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
- [reusable-scenario-selection.feature](../maven-consumer-project/src/test/resources/features/reusable-scenario-selection.feature);
- [scenario-step-markers.feature](../maven-consumer-project/src/test/resources/features/scenario-step-markers.feature);
- [scenario-data-references.feature](../maven-consumer-project/src/test/resources/features/scenario-data-references.feature); and
- [components.html](../maven-consumer-project/src/test/resources/site/components.html).
[Previous: Block Conditionals](block-conditionals.md) · [Documentation home](README.md) · [Next: Service-call Scenarios](service-call-scenarios.md)
