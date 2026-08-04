# Service-call Scenarios

> **Working feature examples:** [`service-call-execution.feature`](../maven-consumer-project/src/test/resources/features/service-call-execution.feature) invokes reusable calls. [`service-call-definitions.feature`](../maven-consumer-project/src/test/resources/calls/service-call-definitions.feature) builds requests with the general mapping steps and executes them.

Pickleball treats service calls as reusable scenarios. `ModularScenarios.java` owns selection and nested-scenario execution. `ServiceCallSteps.java` owns the service-call convenience wrapper and HTTP execution. Request data is built with the general steps from `MappingSteps.java`.

## Public service-call steps

| Step | Purpose |
|---|---|
| `RUN ["key"] SERVICE CALL` / `RUN ["key"] SERVICE CALLS` | Select one or more call scenarios for deferred nested execution. |
| `CALL:` | Select exactly one call scenario, execute it synchronously, and return its result. |
| `EXECUTE SERVICE CALL` | Execute the mapped `REQUEST` with optional `CONFIGURATION` and populate `RESPONSE`. |

Call lookup uses `pkb_callpath`. When it is not configured, the path defaults to:

```text
src/test/resources/calls
```

A nonblank `pkb_callpath` value in an invocation table overrides the global value for that row. A blank table value is ignored.

## Invoke a call

Select by reusable tag:

```gherkin
When RUN "inlineRead" SERVICE CALL: %inspect-get
  | endpoint              | client      | traceId     | include   | mode |
  | http://127.0.0.1:8765 | caller-test | trace-get-1 | inventory | full |
```

Select by exact scenario name:

```gherkin
When RUN "healthByName" SERVICE CALL: SCENARIO: HealthCall
  | endpoint              |
  | http://127.0.0.1:8765 |
```

Select by feature and scenario name:

```gherkin
When RUN "qualifiedHealth" SERVICE CALL: FEATURE: Reusable service call definitions SCENARIO: HealthCall
  | endpoint              |
  | http://127.0.0.1:8765 |
```

The inline argument syntax and invocation-table selectors are shared with regular and component scenario runs.

## Synchronous `CALL:`

`CALL:` accepts the same inline arguments and optional DataTable:

```gherkin
When CALL: SCENARIO: HealthCall
  | RunKey | endpoint              |
  | health | http://127.0.0.1:8765 |
```

It uses the same active parsing map, inline `DT:::` conversion, passed-map construction, selected Scenario Outline Examples row, marker selection, and template resolution as `RUN SERVICE CALL`.

It differs only in execution behavior:

- exactly one scenario must match;
- the selected scenario executes synchronously;
- when the scenario root contains `RETURN`, that field is returned;
- otherwise the complete scenario root is returned;
- a nonblank `RunKey` saves the same returned value;
- without `RunKey`, the value is returned but not saved.

Inline DataTables work identically:

```gherkin
When CALL: %inspect-get DT::: | "RunKey":"health", "endpoint":"http://127.0.0.1:8765" |
```

## Invocation-table options

| Purpose | Pickleball option | Cucumber option |
|---|---|---|
| Call feature path | `pkb_callpath` | — |
| Exact feature name | `pkb_featurename` | — |
| Scenario-name regex | `pkb_name` | `cucumber.filter.name` |
| Tag expression | `pkb_tags` or `Run Tags` | `cucumber.filter.tags` |
| Result order | `pkb_order` | `cucumber.execution.order` |
| Result limit | `pkb_limit` | `cucumber.execution.limit` |

```gherkin
When RUN SERVICE CALLS
  | pkb_featurename                   | pkb_name     | RunKey | endpoint              |
  | Reusable service call definitions | ^HealthCall$ | health | http://127.0.0.1:8765 |
  | Reusable service call definitions | ^StatusCall$ | status | http://127.0.0.1:8765 |
```

## Singular and plural behavior

`RUN SERVICE CALL` permits zero or one result. `RUN SERVICE CALLS` permits multiple results. `CALL:` requires exactly one result.

Ordering and limit options are applied before cardinality is checked. Invocation-table row order is preserved.

## RunMap keys

For deferred `RUN` execution, key precedence is:

1. nonblank `RunKey`;
2. quoted key;
3. no save.

Only one key is used. For example:

```gherkin
When RUN "quotedName" SERVICE CALL: %status-call
  | RunKey   | endpoint              | status |
  | tableKey | http://127.0.0.1:8765 | 422    |
```

Only `tableKey` is registered. The selected scenario root is registered by reference before its child steps execute, so later `REQUEST` and `RESPONSE` changes are visible through that entry.

For `CALL:`, only `RunKey` is available. It saves the value returned by the synchronous call rather than always saving the root.

## Start markers

Reusable call scenarios support the same start-marker syntax:

```gherkin
When RUN "health" SERVICE CALL: SCENARIO: HealthCall START: execute health
```

The invocation table can also provide `Step_Marker`.

## Define a service-call scenario

A reusable call maps `REQUEST`, optionally maps `CONFIGURATION`, and then executes the request:

```gherkin
Scenario Outline: InspectGetCall
  Given MAP "REQUEST" TABLE VALUES TO SCENARIO MAP
    | endpoint | <endpoint>/api/service-calls/inspect |
    | method   | GET                                  |
    | accept   | application/json                     |
  And MAP "REQUEST.headers" TABLE VALUES TO SCENARIO MAP
    | Accept        | application/json |
    | X-Test-Client | <client>         |
    | X-Test-Trace  | <traceId>        |
  And MAP "REQUEST.queryParams" TABLE VALUES TO SCENARIO MAP
    | include | <include> |
    | mode    | <mode>    |
  When EXECUTE SERVICE CALL
  Examples:
    | Scenario Tags | endpoint              | client         | traceId     | include | mode    |
    | %inspect-get  | http://127.0.0.1:8765 | default-client | get-default | none    | summary |
```

`SCENARIO MAP` keeps the request data on the reusable scenario while its child steps run.

## Template resolution

Cucumber replaces matching Scenario Outline Examples tokens first. Pickleball runtime references are resolved later through the active parsing map.

From inside a reusable call:

```text
<PARENT.SCENARIO:jsonTemplate.item.quantity>
<seedCall.RESPONSE.body.body.quantity>
```

Use `PARENT.SCENARIO:` for values mapped by the caller. Use a RunMap key for a completed keyed deferred call.

For XML content, use the XML-safe reference bookends:

```text
~[~PARENT.SCENARIO:soapTemplate.left~]~
~[~seedCall.RESPONSE.body.body.quantity~]~
```

## JSON body types

A quoted `~unquote` reference preserves resolved numbers, booleans, objects, and arrays as JSON values:

```gherkin
And MAP "REQUEST.body" OBJECT VALUE TO SCENARIO MAP
  """json
  {
    "name": "<PARENT.SCENARIO:template.name>",
    "quantity": "<PARENT.SCENARIO:template.quantity~unquote>",
    "active": "<PARENT.SCENARIO:template.active~unquote>",
    "metadata": "<PARENT.SCENARIO:template.metadata~unquote>"
  }
  """
```

Use a normal quoted reference for a JSON string and `~unquote` for a complete raw JSON value.

## Request structure

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

`endpoint` must be nonblank. `method` defaults to `GET`.

The response is written beneath:

```text
RESPONSE.method
RESPONSE.statusCode
RESPONSE.headers
RESPONSE.body
```

HTTP `4xx` and `5xx` responses are retained as normal results. No-content responses retain status and headers.

## Execute and inspect

```gherkin
When EXECUTE SERVICE CALL
```

For a keyed deferred run, the registered root exposes the completed request and response:

```text
<inlineRead.REQUEST.endpoint>
<inlineRead.REQUEST.headers.X-Test-Client>
<inlineRead.RESPONSE.method>
<inlineRead.RESPONSE.statusCode>
<inlineRead.RESPONSE.headers.Content-Type>
<inlineRead.RESPONSE.body.status>
```

## Generic mappings only

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
