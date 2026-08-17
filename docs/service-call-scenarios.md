# Service-call Scenarios

> **Working feature examples:** [`service-call-execution.feature`](../maven-consumer-project/src/test/resources/features/service-call-execution.feature) invokes reusable calls. [`run-step-parameter-variations.feature`](../maven-consumer-project/src/test/resources/features/run-step-parameter-variations.feature) demonstrates the canonical table-driven `RUN` form and per-row run types/cardinality. [`reusable-scenario-selection.feature`](../maven-consumer-project/src/test/resources/features/reusable-scenario-selection.feature) covers named and qualified service-call selectors. [`service-call-definitions.feature`](../maven-consumer-project/src/test/resources/calls/service-call-definitions.feature) builds and executes requests.

Pickleball treats service calls as reusable scenarios. `ModularScenarios.java` owns selection and nested-scenario execution. `ServiceCallSteps.java` owns the synchronous convenience wrapper and HTTP execution.

## Public service-call steps

| Step | Purpose |
|---|---|
| `RUN` with `RunType=SERVICE CALL` / `SERVICE CALLS` | Preferred table-driven form; compose service calls with regular/component scenarios in one step. |
| `RUN ["key"] SERVICE CALL` / `RUN ["key"] SERVICE CALLS` | Shorthand when the service-call type is common or no DataTable is needed. |
| `CALL:` | Select exactly one call scenario, execute it synchronously, and return its result. |
| `EXECUTE SERVICE CALL` | Execute mapped `REQUEST`, with optional `CONFIGURATION`, and populate `RESPONSE`. |

## Preferred table-driven RUN form

Use one DataTable row per invocation. A bare `RUN` can mix service calls with other runnable scenario kinds:

```gherkin
When RUN
  | RunType            | RunKey | Run Tags         | endpoint              |
  | SCENARIO           | setup  | %setup           |                       |
  | COMPONENT SCENARIO | login  | %login-component |                       |
  | SERVICE CALL       | health | %health-full-url | http://127.0.0.1:8765 |
```

`RunType` accepts `SCENARIO`, `SCENARIOS`, `COMPONENT SCENARIO`, `COMPONENT SCENARIOS`, `SERVICE CALL`, and `SERVICE CALLS`. Multiplicity belongs to each resolved row value: singular allows at most one match for that row, and plural allows multiple matches for that row.

Inline syntax is shorthand for parameters common to all rows:

```gherkin
When RUN SERVICE CALL
  | RunKey | Run Tags         | endpoint              |
  | health | %health-full-url | http://127.0.0.1:8765 |
```

or, when no table is needed:

```gherkin
When RUN "health" SERVICE CALL: %health-full-url
```

A nonblank table `RunType` overrides the inline type for that row, including singular/plural multiplicity. A nonblank table `RunKey` overrides a quoted inline key.

Call lookup uses `pkb_callpath`. When it is not configured, the path defaults to:

```text
src/test/resources/calls
```

A nonblank `pkb_callpath` invocation-table value overrides the global value for that row.

## Inline selectors

Service-call named selectors use the same path syntax as regular and component scenarios:

```text
scenario
feature.scenario
feature.scenario.marker
```

Examples:

```gherkin
When RUN "healthByName" SERVICE CALL: HealthCall
  | endpoint              |
  | http://127.0.0.1:8765 |
```

```gherkin
When RUN "qualifiedHealth" SERVICE CALL: Reusable service call definitions.HealthCall
  | endpoint              |
  | http://127.0.0.1:8765 |
```

```gherkin
When RUN "healthFromMarker" SERVICE CALL: Reusable service call definitions.HealthCall.execute health
```

The former inline labels `FEATURE:`, `SCENARIO:`, and `START:` are not supported.

An argument beginning with `@` or `%` is still a tag selector:

```gherkin
When RUN "inlineRead" SERVICE CALL: %inspect-get
```

Only unescaped periods delimit path components. Escape literal periods and backslashes as `\.` and `\\`.

## Synchronous `CALL:`

`CALL:` uses the same selector parser and optional DataTable:

```gherkin
When CALL: HealthCall
  | RunKey | endpoint              |
  | health | http://127.0.0.1:8765 |
```

Qualified calls work the same way:

```gherkin
When CALL: Reusable service call definitions.HealthCall
```

`CALL:` requires exactly one match, executes it immediately, returns `RETURN` when present, otherwise returns the scenario root, and saves the returned value when `RunKey` is nonblank.

Embedded `$CALL:` expressions delegate to the same selection contract:

```text
<$CALL:ExplicitNullReturnCall>
<$CALL:Reusable service call definitions.ExplicitNullReturnCall>
```

A keyed deferred `RUN` now uses the same result contract after its selected scenario completes. This makes the result of:

```gherkin
When RUN "KeyA" SERVICE CALL: %TagA
```

semantically equivalent to mapping the synchronous call result into the RunMap:

```gherkin
And MAP TABLE VALUES TO RUN MAP
  | KeyA | <$CALL:%TagA> |
```

Both store explicit `RETURN` when present and otherwise store the completed scenario root.

## Invocation-table options

| Purpose | Pickleball option | Cucumber option |
|---|---|---|
| Run kind/cardinality | `RunType` | — |
| Saved result key | `RunKey` | — |
| Call feature path | `pkb_callpath` | — |
| Exact feature name | `pkb_featurename` | — |
| Scenario-name regex | `pkb_name` | `cucumber.filter.name` |
| Tag expression | `pkb_tags` or `Run Tags` | `cucumber.filter.tags` |
| Result order | `pkb_order` | `cucumber.execution.order` |
| Result limit | `pkb_limit` | `cucumber.execution.limit` |
| Start marker | `Step_Marker` | — |

Inline feature/scenario/marker components overwrite their equivalent table selector fields. A nonblank table `RunType` overrides the inline run type, and `RunKey` keeps its precedence over a quoted key.

## Singular and plural behavior

Cardinality is per resolved invocation row. `SERVICE CALL` permits at most one match for that row; `SERVICE CALLS` permits multiple matches for that row. Ordering and limit options are applied before the row's cardinality validation. `CALL:` still requires exactly one match.

The same rule applies to regular/component rows in a mixed bare `RUN` table.

## RunMap keys

For deferred `RUN` execution, key precedence is:

1. nonblank `RunKey`;
2. quoted key;
3. no save.

```gherkin
When RUN "quotedName" SERVICE CALL: %status-call
  | RunKey   | endpoint              | status |
  | tableKey | http://127.0.0.1:8765 | 422    |
```

Only `tableKey` is used. The assignment happens after the selected scenario and its child/attached work have completed. If the scenario has `RETURN`, that value is saved; otherwise the completed scenario root is saved.

Result assignment uses ordinary RunMap/NodeMap writes rather than live `putReference()` registration. Reusing an ordinary top-level `RunKey` appends results to the key's collection. An unindexed read resolves the latest item, while collection selectors such as `#1` and `#2` can address earlier results.

## Start markers

Reusable calls support the same third selector component:

```gherkin
When RUN "health" SERVICE CALL: Reusable service call definitions.HealthCall.execute health
```

The invocation table can alternatively provide `Step_Marker`; an inline third component overrides it.

## Define a service-call scenario

A reusable call maps `REQUEST`, optionally maps `CONFIGURATION`, and executes the request:

```gherkin
Scenario Outline: InspectGetCall
  Given MAP "REQUEST" TABLE VALUES TO SCENARIO MAP
    | endpoint | <endpoint>/api/service-calls/inspect |
    | method   | GET                                  |
    | accept   | application/json                     |
  And MAP "REQUEST.headers" TABLE VALUES TO SCENARIO MAP
    | Accept        | application/json |
    | X-Test-Client | <client>         |
  When EXECUTE SERVICE CALL
  Examples:
    | Scenario Tags | endpoint              | client         |
    | %inspect-get  | http://127.0.0.1:8765 | default-client |
```

## Template resolution

Cucumber replaces Scenario Outline Examples tokens first. Pickleball runtime references are resolved later through the active parsing map.

From inside a reusable call:

```text
<PARENT.SCENARIO:jsonTemplate.item.quantity>
<seedCall.RESPONSE.body.body.quantity>
```

Use `PARENT.SCENARIO:` for caller-owned scenario-map values. Use a RunMap key for a completed keyed deferred call.

For XML content, use XML-safe reference bookends:

```text
~[~PARENT.SCENARIO:soapTemplate.left~]~
~[~seedCall.RESPONSE.body.body.quantity~]~
```

## JSON body types

A quoted `~unquoted;` reference preserves resolved numbers, booleans, objects, and arrays as raw JSON values:

```gherkin
And MAP "REQUEST.body" OBJECT VALUE TO SCENARIO MAP
  """json
  {
    "name": "<PARENT.SCENARIO:template.name>",
    "quantity": "<PARENT.SCENARIO:template.quantity~unquoted;>",
    "active": "<PARENT.SCENARIO:template.active~unquoted;>",
    "metadata": "<PARENT.SCENARIO:template.metadata~unquoted;>"
  }
  """
```

`~unquoted;` is valid only when the reference is directly surrounded by one matching quote pair. See [Mapping and Templating](mapping-and-templating.md) for conversion directives, pipelines, masks, and directive placement rules.

## Request and response structure

`EXECUTE SERVICE CALL` reads:

```text
REQUEST.endpoint
REQUEST.method
REQUEST.accept
REQUEST.contentType
REQUEST.headers
REQUEST.queryParams
REQUEST.cookies
REQUEST.body
CONFIGURATION
```

The response is written beneath:

```text
RESPONSE.method
RESPONSE.statusCode
RESPONSE.headers
RESPONSE.body
```

HTTP `4xx` and `5xx` responses are retained as normal service results. No-content responses retain status and headers.

## Generic mappings

Build service calls with the shared mapping syntax:

```gherkin
MAP "REQUEST" TABLE VALUES TO SCENARIO MAP
MAP "REQUEST.headers" TABLE VALUES TO SCENARIO MAP
MAP "REQUEST.queryParams" TABLE VALUES TO SCENARIO MAP
MAP "REQUEST.cookies" TABLE VALUES TO SCENARIO MAP
MAP "REQUEST.body" OBJECT VALUE TO SCENARIO MAP
MAP "REQUEST.body" TEXT VALUE TO SCENARIO MAP
MAP "CONFIGURATION" TABLE VALUES TO SCENARIO MAP
```

[Previous: Component Scenarios](component-scenarios.md) · [Documentation home](README.md) · [Next: Date and Time Utilities](date-time-utilities.md)
