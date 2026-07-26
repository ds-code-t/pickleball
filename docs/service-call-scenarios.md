# Service-call Scenarios

> **Working feature examples:** [`service-call-execution.feature`](../maven-consumer-project/src/test/resources/features/service-call-execution.feature) locates and invokes calls; [`service-call-definitions.feature`](../maven-consumer-project/src/test/resources/calls/service-call-definitions.feature) builds each request with the general mapping steps and executes it.

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

Select a component scenario with an inline percent tag:

```gherkin
When "inlineRead" SERVICE CALL: %inspect-get
  | endpoint                  | client      | traceId     | include   | mode |
  | http://127.0.0.1:8765     | caller-test | trace-get-1 | inventory | full |
```

Or supply the selector through a `Run Tags` column:

```gherkin
When SERVICE CALLS
  | Run Tags     | Call Key  | endpoint              | status |
  | %status-call | tableWins | http://127.0.0.1:8765 | 422    |
```

The saved object key is chosen in this order:

1. `Call Key` from the invocation table;
2. the quoted name before `SERVICE CALL`; or
3. the resolved component scenario name.

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

### XML or other raw body

Use the generic `TEXT` mapper:

```gherkin
And MAP "REQUEST.body" TEXT VALUE TO SCENARIO MAP
  """xml
  <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/"
                    xmlns:calc="urn:pickleball:calculator">
    <soapenv:Body>
      <calc:Add>
        <calc:left><left></calc:left>
        <calc:right><right></calc:right>
      </calc:Add>
    </soapenv:Body>
  </soapenv:Envelope>
  """
```

The `xml` media type is useful for IntelliJ formatting, while `TEXT` preserves the content as a string.

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
