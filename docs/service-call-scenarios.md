# Service-call Scenarios

> **Working feature examples:** [`service-call-execution.feature`](../maven-consumer-project/src/test/resources/features/service-call-execution.feature) locates and invokes calls; [`reusable-scenario-selection.feature`](../maven-consumer-project/src/test/resources/features/reusable-scenario-selection.feature) covers name selection and singular/plural cardinality; [`service-call-definitions.feature`](../maven-consumer-project/src/test/resources/calls/service-call-definitions.feature) builds each request with the general mapping steps and executes it.

Pickleball treats service calls as reusable component scenarios. `ServiceCallSteps.java` is responsible for locating those scenarios, running them, executing the assembled request, and saving the completed call object. It does **not** provide separate mapping steps for endpoints, methods, headers, bodies, configuration, or responses.

All request data is built with the general steps from `MappingSteps.java`.

## Responsibilities of `ServiceCallSteps.java`

The public service-call steps are:

| Step | Purpose |
|---|---|
| `SERVICE CALL` / `SERVICE CALLS` | Locate and execute one or more component scenarios from the call-feature directory. |
| `EXECUTE SERVICE CALL` | Read the component scenario's mapped `REQUEST` and optional `CONFIGURATION`, perform the HTTP request, and populate `RESPONSE`. |

An internal always-run finalizer saves the completed component object into the caller's run map. It is framework plumbing and is not written manually in feature files.

The default call-feature directory is:

```text
src/test/resources/calls
```

It can be changed with `pkb_callspath`.

## Invoke a reusable call

Inline arguments beginning with `@` or `%` are treated as Cucumber tag expressions:

```gherkin
When "inlineRead" SERVICE CALL: %inspect-get
  | endpoint              | client      | traceId     | include   | mode |
  | http://127.0.0.1:8765 | caller-test | trace-get-1 | inventory | full |
```

Any other inline argument is treated as an exact scenario name:

```gherkin
When "healthByName" SERVICE CALL: HealthCall
  | endpoint              |
  | http://127.0.0.1:8765 |
```

Include the exact feature name before the first `.` to restrict the match:

```gherkin
When "qualifiedHealth" SERVICE CALL: Reusable service call definitions.HealthCall
  | endpoint              |
  | http://127.0.0.1:8765 |
```

Both names must be present in the qualified `Feature name.Scenario name` form. Because the first `.` is the separator, use table options when a literal feature or scenario name itself contains a period.

Selectors can also be supplied through invocation-table Cucumber options:

```gherkin
When SERVICE CALLS
  | pkb_featurename                  | pkb_name                  | pkb_order | pkb_limit | endpoint              | status |
  | Reusable service call definitions | ^(HealthCall\|StatusCall)$ | lexical   | 2         | http://127.0.0.1:8765 | 418    |
```

Supported options include:

| Purpose | Pickleball option | Cucumber option |
|---|---|---|
| Feature paths | `pkb_features` | `cucumber.features` |
| Exact feature name | `pkb_featurename` | — |
| Scenario-name regex | `pkb_name` | `cucumber.filter.name` |
| Tag expression | `pkb_tags` or `Run Tags` | `cucumber.filter.tags` |
| Result order | `pkb_order` | `cucumber.execution.order` |
| Result limit | `pkb_limit` | `cucumber.execution.limit` |

A nonblank tag, feature-name, or scenario-name filter must match at least one service-call scenario. Otherwise the step throws a descriptive no-match error.

Feature paths, ordering, and limits do not select scenarios by themselves. When all tag, feature-name, and scenario-name filters are blank or absent, the step returns silently and executes no service calls.

### Singular and plural cardinality

`SERVICE CALL` allows zero or one returned scenario. `SERVICE CALLS` allows any number.

Ordering and limit options are applied before cardinality is checked. If a singular call still returns more than one scenario, it throws an error naming the matches and instructing the caller to use `SERVICE CALLS`. Plural matches execute in the order returned by the existing Cucumber ordering and limit logic. Invocation-table row order is preserved.

The saved object key is chosen in this order:

1. `Call Key` from the invocation table;
2. the quoted name before `SERVICE CALL`; or
3. the resolved component scenario name.

When a plural selector can return multiple scenarios, use distinct `Call Key` values per row or rely on distinct scenario names to avoid ordinary run-map replacement.

## Define a service-call component

A component scenario maps a `REQUEST`, optionally maps `CONFIGURATION`, and then executes the call:

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

The `SCENARIO MAP` target keeps the request data on the reusable component scenario while its child steps run.

### Cucumber substitutions and Pickleball references

In a `Scenario Outline`, a token such as `<endpoint>` that matches an Examples header is substituted by Cucumber before the component executes.

A Pickleball runtime reference is resolved later by `MappingProcessor`. Runtime references are useful when the request body depends on mapped caller values or an earlier service response:

```text
<PARENT.SCENARIO:jsonTemplate.item.quantity>
<seedCall.RESPONSE.body.body.quantity>
```

From inside the component:

- Use `PARENT.SCENARIO:` for values the caller mapped to its scenario map.
- Use the unqualified call key for a completed named service call because the finalizer saves that object in the shared run map.

For XML runtime references, use the XML-safe bookends:

```text
~[~PARENT.SCENARIO:soapTemplate.left~]~
~[~seedCall.RESPONSE.body.body.quantity~]~
```

## Request object

The working consumer uses these `REQUEST` properties:

| Property | Purpose |
|---|---|
| `REQUEST.endpoint` | Complete URL, including scheme, host, port, and path. |
| `REQUEST.method` | HTTP method such as `GET`, `POST`, or `DELETE`. |
| `REQUEST.accept` | Accept media type. |
| `REQUEST.contentType` | Request-body media type. |
| `REQUEST.headers` | Header name/value object. |
| `REQUEST.queryParams` | Query-parameter object. |
| `REQUEST.cookies` | Cookie name/value object. |
| `REQUEST.body` | Parsed object or raw text body. |

### JSON request body

Use the generic `OBJECT` mapper:

```gherkin
And MAP "REQUEST.body" OBJECT VALUE TO SCENARIO MAP
  """json
  {
    "name": "<name>",
    "quantity": <quantity>,
    "active": true
  }
  """
```

In this example, `<name>` and `<quantity>` are Cucumber `Scenario Outline` placeholders. Cucumber replaces them before Pickleball resolves and parses the body.

For Pickleball runtime references, prefer a JSON template that is valid before resolution. Quote each reference and add `~unquote` when the resolved value must be a non-string JSON value:

```gherkin
And MAP "REQUEST.body" OBJECT VALUE TO SCENARIO MAP
  """json
  {
    "name": "<PARENT.SCENARIO:unquoteTemplate.name>",
    "quantity": "<PARENT.SCENARIO:unquoteTemplate.quantity~unquote>",
    "active": "<PARENT.SCENARIO:unquoteTemplate.active~unquote>",
    "unitPrice": "<PARENT.SCENARIO:unquoteTemplate.unitPrice~unquote>",
    "metadata": "<PARENT.SCENARIO:unquoteTemplate.metadata~unquote>",
    "items": "<PARENT.SCENARIO:unquoteTemplate.items~unquote>"
  }
  """
```

`MAP ... OBJECT VALUE` resolves the complete DocString and then parses the resulting JSON. A bare unresolved reference in a value position is not valid JSON:

```json
{
  "quantity": <PARENT.SCENARIO:unquoteTemplate.quantity>
}
```

The quoted `~unquote` pattern avoids that parsing problem while preserving the resolved type:

```json
{
  "quantity": "<PARENT.SCENARIO:unquoteTemplate.quantity~unquote>"
}
```

When the mapped value is `12`, the resolved JSON is:

```json
{
  "quantity": 12
}
```

Use:

- `"<reference>"` for JSON strings;
- `"<reference~unquote>"` for complete numbers, booleans, objects, or arrays.

`~unquote` removes only a directly surrounding matching quote pair after a successful replacement. It inserts the resolved value as raw JSON and does not convert arbitrary text into a JSON type. A raw value must therefore be valid JSON. Mapped Jackson object and array nodes are serialized as JSON before insertion.

The working `%unquoted-json-body` component and its execution scenario test integer, decimal, boolean, object, and array insertion.

### Values from an earlier service response

A previous named service call can provide data for a later request. The earlier result is stored under its call key:

```gherkin
And "seedJson" SERVICE CALL: %inspect-post
  | endpoint              | client      | traceId        | cookieValue | mode | status | name        | quantity |
  | http://127.0.0.1:8765 | seed-client | seed-json-call | seed-cookie | seed | 201    | Seed Widget | 8        |
```

A later component can reference that completed call directly:

```gherkin
And MAP "REQUEST.body" OBJECT VALUE TO SCENARIO MAP
  """json
  {
    "name": "<seedJson.RESPONSE.body.body.name>",
    "quantity": "<seedJson.RESPONSE.body.body.quantity~unquote>",
    "sourceStatus": "<seedJson.RESPONSE.statusCode~unquote>"
  }
  """
```

Do not add `PARENT.SCENARIO:` to `seedJson` in this situation. `PARENT.SCENARIO` addresses the caller's scenario map, while the completed named call is registered in the shared run map.

### XML or other raw body

Use the generic `TEXT` mapper. Pickleball runtime references inside XML should use `~[~...~]~` so XML element tags are not confused with template bookends:

```gherkin
And MAP "REQUEST.body" TEXT VALUE TO SCENARIO MAP
  """xml
  <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/"
                    xmlns:calc="urn:pickleball:calculator">
    <soapenv:Header/>
    <soapenv:Body>
      <calc:Add>
        <calc:left>~[~PARENT.SCENARIO:soapTemplate.left~]~</calc:left>
        <calc:right>~[~soapSeed.RESPONSE.body.body.quantity~]~</calc:right>
      </calc:Add>
    </soapenv:Body>
  </soapenv:Envelope>
  """
```

The left value above comes from the caller's scenario map. The right value comes from an earlier named service call in the run map.

The `xml` media type is useful for IntelliJ formatting, while `TEXT` preserves the content as a string.

A plain `<left>` token can still be a Cucumber `Scenario Outline` placeholder when `left` is an Examples column. It is not the recommended syntax for a Pickleball runtime map reference inside XML.

## REST Assured configuration

Map optional REST Assured behavior beneath `CONFIGURATION`:

```gherkin
And MAP "CONFIGURATION" TABLE VALUES TO SCENARIO MAP
  | urlEncodingEnabled     | true |
  | relaxedHTTPSValidation |      |
```

`CONFIGURATION` is not needed merely to provide the host or base URL. The working definitions put the complete URL in `REQUEST.endpoint`.

## Execute and inspect the result

```gherkin
When EXECUTE SERVICE CALL
```

The framework creates `RESPONSE` and the finalizer saves the whole component object into the caller. The caller can inspect both the request and response:

```text
<inlineRead.REQUEST.endpoint>
<inlineRead.REQUEST.headers.X-Test-Client>
<inlineRead.RESPONSE.method>
<inlineRead.RESPONSE.statusCode>
<inlineRead.RESPONSE.headers.Content-Type>
<inlineRead.RESPONSE.body.status>
```

HTTP `4xx` and `5xx` responses are retained as normal service responses rather than being treated as missing call results. A no-content response is also retained with its status and headers.

`EXECUTE SERVICE CALL` initializes `RESPONSE` before attempting the request. Consequently, an early component exit can still be finalized and saved with an empty response object.

## No custom REST mapping steps

Do not use the removed forms:

```text
ENDPOINT:...
METHOD:...
HEADERS
BODY:...
REQUEST CONFIGURATION
MAP SERVICE RESPONSE
```

Use the generic mappings instead:

```gherkin
MAP "REQUEST" TABLE VALUES TO SCENARIO MAP
MAP "REQUEST.headers" TABLE VALUES TO SCENARIO MAP
MAP "REQUEST.queryParams" TABLE VALUES TO SCENARIO MAP
MAP "REQUEST.cookies" TABLE VALUES TO SCENARIO MAP
MAP "REQUEST.body" OBJECT VALUE TO SCENARIO MAP
MAP "REQUEST.body" TEXT VALUE TO SCENARIO MAP
MAP "CONFIGURATION" TABLE VALUES TO SCENARIO MAP
```

## Local endpoints in the consumer

The consumer's [`LocalTestSite.java`](../maven-consumer-project/src/test/java/com/example/pickleball/support/LocalTestSite.java) starts a loopback-only server before the Cucumber run. The current call definitions exercise:

- `/api/service-calls/inspect`;
- `/api/service-calls/no-content/{itemId}`;
- `/api/health`; and
- `/soap/calculator`.

This lets the example project test both Selenium DOM behavior and service calls without an external test environment.

[Previous: Component Scenarios](component-scenarios.md) · [Documentation home](README.md) · [Next: Date and Time Utilities](date-time-utilities.md)
