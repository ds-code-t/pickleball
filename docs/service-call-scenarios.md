# Service-call Scenarios

> **Working feature examples:** [`service-call-execution.feature`](../maven-consumer-project/src/test/resources/features/service-call-execution.feature) locates and invokes calls; [`service-call-definitions.feature`](../maven-consumer-project/src/test/resources/calls/service-call-definitions.feature) builds each request with the general mapping steps and executes it.

Pickleball treats service calls as reusable component scenarios. `ServiceCallSteps.java` locates those scenarios, registers their scenario-root objects when appropriate, runs them, and executes the assembled requests. It does **not** provide separate mapping steps for endpoints, methods, headers, bodies, configuration, or responses.

All request and response data is built or inspected with the general mapping syntax from `MappingSteps.java`.

## Current public steps

| Step | Purpose |
|---|---|
| `SERVICE CALL` / `SERVICE CALLS` | Locate one or more service-call component scenarios, register each selected component root under its resolved key in the shared `RunMap`, and allow the normal modular-scenario runner to execute it. |
| `CALL:%tag` | Locate exactly one service-call component, execute it synchronously as an inline value, and return either its explicit `RETURN` value or its completed scenario root. |
| `EXECUTE SERVICE CALL` | Read the current component scenario's mapped `REQUEST` and optional `CONFIGURATION`, perform the HTTP request, and create or replace `RESPONSE` on that same scenario root. |

The default call-feature directory is:

```text
src/test/resources/calls
```

It can be changed with `pkb_callspath`.

## Shared `RunMap` behavior

There is one shared `RunMap` for a scenario run.

An ordinary `SERVICE CALL` registers the selected component root by reference in that `RunMap` before the component executes. The component's later `REQUEST` and `RESPONSE` changes are therefore visible through the selected call key.

For example:

```gherkin
When "inspectCall" SERVICE CALL: %inspect-get
  | endpoint              | client      |
  | http://127.0.0.1:8765 | caller-test |
```

The completed call can be inspected through:

```text
<inspectCall.REQUEST.endpoint>
<inspectCall.RESPONSE.statusCode>
<inspectCall.RESPONSE.body>
```

An ordinary nested `SERVICE CALL` also registers its child root in this same shared `RunMap`. It does not automatically place that child beneath the owning component's scenario root.

## Invoke a reusable service call

Select a component scenario with an inline percent tag:

```gherkin
When "inlineRead" SERVICE CALL: %inspect-get
  | endpoint              | client      | traceId     | include   | mode |
  | http://127.0.0.1:8765 | caller-test | trace-get-1 | inventory | full |
```

Or supply the selector through a `Run Tags` column:

```gherkin
When SERVICE CALLS
  | Run Tags     | Call Key  | endpoint              | status |
  | %status-call | tableWins | http://127.0.0.1:8765 | 422    |
```

The saved object key is selected in this order:

1. `Call Key` from the invocation table;
2. the quoted name before `SERVICE CALL`; or
3. the resolved component scenario name.

Use unique keys when multiple nested calls must remain independently available in the same scenario run.

## Define a service-call component

A service-call component maps `REQUEST`, optionally maps `CONFIGURATION`, and then executes the request:

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

`TO SCENARIO MAP` is the mapping target. It keeps the request data on the reusable component scenario while the component's child steps run.

Do not confuse that mapping phrase with a reference selector. In angle-bracket references, the selector is `SCENARIO`, not `SCENARIO MAP`.

## Scenario-map ancestry references

Nested and inline components can read values from the scenario that contains them without explicitly passing or copying a `PARENT` object.

Use these map selectors:

```text
<SCENARIO:key>
<PARENT.SCENARIO:key>
<PARENT.PARENT.SCENARIO:key>
```

They mean:

| Selector | Meaning |
|---|---|
| `<SCENARIO:key>` | Read `key` from the closest current scenario map. |
| `<PARENT.SCENARIO:key>` | Read `key` from the immediately containing scenario map. |
| `<PARENT.PARENT.SCENARIO:key>` | Move up one additional scenario ancestor and read `key`. |

The value after the colon may be a nested path:

```gherkin
<PARENT.SCENARIO:url>
<PARENT.SCENARIO:client>
<PARENT.SCENARIO:authentication.scope>
<SCENARIO:RESPONSE.body.accessToken>
```

The ancestry is structural. Pickleball does not insert a property named `PARENT` into the child scenario map.

### Invalid or obsolete parent forms

Do not use:

```text
<PARENT.url>
<PARENT.client>
<PARENT.SCENARIO MAP:url>
<PARENT.SCENARIO MAP:client>
```

Use:

```text
<PARENT.SCENARIO:url>
<PARENT.SCENARIO:client>
```

`PARENT.SCENARIO MAP` is invalid because `SCENARIO MAP` is not a supported map selector. `SCENARIO MAP` is only wording used by mapping steps such as `MAP ... TO SCENARIO MAP`.

## Inline `CALL`

Inline `CALL` is used inside a resolvable value such as:

```gherkin
| TOKEN | <$CALL:%TokenCall> |
```

It has different execution and storage behavior from an ordinary `SERVICE CALL`:

1. exactly one matching component scenario is selected;
2. that child component executes synchronously before the mapping step continues;
3. the child can use `<PARENT.SCENARIO:...>` while it runs;
4. after execution, the inline child is detached from deferred child execution so it is not run a second time;
5. `CALL` returns the child's `RETURN` value when available, otherwise it returns the completed child scenario root.

The returned root is the child's actual scenario-root object, but the child has already completed by the time `CALL` returns. Do not describe inline execution as waiting for later deferred child mutations.

### Inline `CALL` without `RETURN`

When the child does not set `RETURN`, the complete child scenario root is returned.

The owning component first stores values in its own scenario map and then maps the inline result:

```gherkin
Scenario Outline: ServiceCallA
  Given MAP TABLE VALUES TO SCENARIO MAP
    | url    | <url>    |
    | client | <client> |
    | scope  | <scope>  |

  And MAP TABLE VALUES TO SCENARIO MAP
    | TOKEN | <$CALL:%TokenCall> |

  And MAP "REQUEST" TABLE VALUES TO SCENARIO MAP
    | endpoint | <url>/api/service-calls/protected |
    | method   | GET                                |
    | accept   | application/json                   |
  And MAP "REQUEST.headers" TABLE VALUES TO SCENARIO MAP
    | X-Test-Client | <client>                                 |
    | Authorization | Bearer <TOKEN.RESPONSE.body.accessToken> |
  And MAP "REQUEST.queryParams" TABLE VALUES TO SCENARIO MAP
    | scope | <scope> |
  When EXECUTE SERVICE CALL

  Examples:
    | Scenario Tags | url                   | client         | scope        |
    | %serviceCallA | http://127.0.0.1:8765 | default-client | catalog.read |
```

The inline child implicitly reads the containing `ServiceCallA` scenario map:

```gherkin
Scenario Outline: TokenCall
  Given MAP "REQUEST" TABLE VALUES TO SCENARIO MAP
    | endpoint    | <PARENT.SCENARIO:url>/api/service-calls/token |
    | method      | POST                                         |
    | accept      | application/json                             |
    | contentType | application/json                             |
  And MAP "REQUEST.headers" TABLE VALUES TO SCENARIO MAP
    | X-Test-Client | <PARENT.SCENARIO:client> |
  And MAP "REQUEST.queryParams" TABLE VALUES TO SCENARIO MAP
    | scope | <PARENT.SCENARIO:scope> |
  And MAP "REQUEST.body" OBJECT VALUE TO SCENARIO MAP
    """json
    {
      "grantType": "client_credentials"
    }
    """
  When EXECUTE SERVICE CALL

  Examples:
    | Scenario Tags |
    | %TokenCall    |
```

Because `TokenCall` does not set `RETURN`, `TOKEN` receives the completed root:

```text
TOKEN.REQUEST
TOKEN.RESPONSE
```

After the owner is registered as `inlineCall`, callers can inspect paths such as:

```text
<inlineCall.TOKEN.REQUEST.endpoint>
<inlineCall.TOKEN.RESPONSE.statusCode>
<inlineCall.REQUEST.endpoint>
<inlineCall.RESPONSE.statusCode>
```

### Inline `CALL` with explicit `RETURN`

A child can set the special `RETURN` property when the owner needs only one value rather than the entire child root.

Use an explicit current-scenario selector when reading the child's own response:

```gherkin
Scenario Outline: TokenValueCall
  Given MAP "REQUEST" TABLE VALUES TO SCENARIO MAP
    | endpoint    | <PARENT.SCENARIO:url>/api/service-calls/token |
    | method      | POST                                         |
    | accept      | application/json                             |
    | contentType | application/json                             |
  And MAP "REQUEST.headers" TABLE VALUES TO SCENARIO MAP
    | X-Test-Client | <PARENT.SCENARIO:client> |
  And MAP "REQUEST.queryParams" TABLE VALUES TO SCENARIO MAP
    | scope | <PARENT.SCENARIO:scope> |
  And MAP "REQUEST.body" OBJECT VALUE TO SCENARIO MAP
    """json
    {
      "grantType": "client_credentials"
    }
    """
  When EXECUTE SERVICE CALL
  And MAP TABLE VALUES TO SCENARIO MAP
    | RETURN | <SCENARIO:RESPONSE.body.accessToken> |

  Examples:
    | Scenario Tags   |
    | %TokenValueCall |
```

The owner receives only the returned token:

```gherkin
Scenario Outline: ServiceCallB
  Given MAP TABLE VALUES TO SCENARIO MAP
    | url    | <url>    |
    | client | <client> |
    | scope  | <scope>  |

  And MAP TABLE VALUES TO SCENARIO MAP
    | TOKEN | <$CALL:%TokenValueCall> |

  And MAP "REQUEST.headers" TABLE VALUES TO SCENARIO MAP
    | X-Test-Client | <client>       |
    | Authorization | Bearer <TOKEN> |
```

In this form, `TOKEN` is the explicit return value. It is not an object containing `REQUEST` and `RESPONSE`.

Do not use the ambiguous old assignment:

```gherkin
| RETURN | <RESPONSE.body.accessToken> |
```

Use:

```gherkin
| RETURN | <SCENARIO:RESPONSE.body.accessToken> |
```

The explicit selector makes it clear that `RESPONSE` belongs to the inline child component's own scenario map.

## Ordinary nested `SERVICE CALL`

Use an ordinary nested `SERVICE CALL` when the child should be registered as another named service call in the shared `RunMap`.

### Preferred implicit-parent form

The owner stores its inputs in its scenario map and invokes the child without duplicating an invocation table:

```gherkin
Scenario Outline: NestedComponent
  Given MAP TABLE VALUES TO SCENARIO MAP
    | url    | <url>    |
    | client | <client> |
    | scope  | <scope>  |

  And "TOKEN" SERVICE CALL: %nestedTokenComponent

  And MAP "REQUEST" TABLE VALUES TO SCENARIO MAP
    | endpoint | <url>/api/service-calls/protected |
    | method   | GET                                |
    | accept   | application/json                   |
  And MAP "REQUEST.headers" TABLE VALUES TO SCENARIO MAP
    | X-Test-Client | <client>                                 |
    | Authorization | Bearer <TOKEN.RESPONSE.body.accessToken> |
  And MAP "REQUEST.queryParams" TABLE VALUES TO SCENARIO MAP
    | scope | <scope> |
  When EXECUTE SERVICE CALL
```

The nested child implicitly retrieves those values from the owner:

```gherkin
Scenario Outline: NestedTokenComponent
  Given MAP "REQUEST" TABLE VALUES TO SCENARIO MAP
    | endpoint    | <PARENT.SCENARIO:url>/api/service-calls/token |
    | method      | POST                                         |
    | accept      | application/json                             |
    | contentType | application/json                             |
  And MAP "REQUEST.headers" TABLE VALUES TO SCENARIO MAP
    | X-Test-Client | <PARENT.SCENARIO:client> |
  And MAP "REQUEST.queryParams" TABLE VALUES TO SCENARIO MAP
    | scope | <PARENT.SCENARIO:scope> |
  When EXECUTE SERVICE CALL

  Examples:
    | Scenario Tags         |
    | %nestedTokenComponent |
```

The outer feature invokes the owner normally:

```gherkin
When "nestedCall" SERVICE CALL: %nestedComponent
  | url                   | client        | scope          |
  | http://127.0.0.1:8765 | nested-client | inventory.read |
```

The resulting keys are separate shared-`RunMap` entries:

```text
<TOKEN.REQUEST.endpoint>
<TOKEN.RESPONSE.body.accessToken>
<nestedCall.REQUEST.endpoint>
<nestedCall.RESPONSE.statusCode>
```

An ordinary nested call does **not** automatically create:

```text
<nestedCall.TOKEN...>
```

That nested path exists only when a value is explicitly mapped beneath the owner's scenario root, as with the inline `CALL` examples.

### Invocation-table form remains supported

Passing values explicitly through the nested `SERVICE CALL` invocation table is still valid and is not deprecated:

```gherkin
Given "TOKEN" SERVICE CALL: %nestedTokenComponent
  | endpoint   | client   | scope   |
  | <endpoint> | <client> | <scope> |
```

Use this form when the child should receive values different from the owner's scenario-map values. Use `<PARENT.SCENARIO:...>` when the child should naturally inherit values already stored by its containing component.

## Request object

The working consumer uses these `REQUEST` properties:

| Property | Purpose |
|---|---|
| `REQUEST.endpoint` | Complete URL, including scheme, host, optional port, and path. |
| `REQUEST.method` | HTTP method such as `GET`, `POST`, or `DELETE`. The executor defaults a missing or blank method to `GET`. |
| `REQUEST.accept` | Accept media type. |
| `REQUEST.contentType` | Request-body media type. |
| `REQUEST.headers` | Header name/value object. |
| `REQUEST.queryParams` | Query-parameter object. |
| `REQUEST.cookies` | Cookie name/value object. |
| `REQUEST.body` | Parsed object or raw text body. |

A port in `REQUEST.endpoint`, such as `http://127.0.0.1:8765/...`, is a normal part of the complete URL. It is not special service-call syntax.

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

### XML or another raw body

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

`CONFIGURATION` is not required merely to provide the host, port, or base URL. The current consumer definitions place the complete URL in `REQUEST.endpoint`.

## Execute and inspect the result

```gherkin
When EXECUTE SERVICE CALL
```

`EXECUTE SERVICE CALL` initializes `RESPONSE` before request validation and execution. It then replaces that object with the extracted REST Assured response when a response is available.

The caller can inspect both request and response data:

```text
<inlineRead.REQUEST.endpoint>
<inlineRead.REQUEST.headers.X-Test-Client>
<inlineRead.RESPONSE.method>
<inlineRead.RESPONSE.statusCode>
<inlineRead.RESPONSE.headers.Content-Type>
<inlineRead.RESPONSE.body.status>
```

HTTP `4xx` and `5xx` responses are retained as normal service responses rather than being treated as missing results. A no-content response is also retained with its status and headers.

If `END SCENARIO` stops a component before `EXECUTE SERVICE CALL`, the already-registered partial root and mapped `REQUEST` remain visible, but the executor has not added `RESPONSE`.

## Removed and deprecated service-call patterns

Do not use the removed custom REST-mapping forms:

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

Also remove or update these obsolete nested-call patterns:

| Obsolete pattern | Current replacement |
|---|---|
| Child receives an explicitly inserted `PARENT` object | Child reads its containing scenario through `<PARENT.SCENARIO:key>`. |
| `<PARENT.key>` | `<PARENT.SCENARIO:key>` |
| `<PARENT.SCENARIO MAP:key>` | `<PARENT.SCENARIO:key>` |
| Documentation says inline `CALL` always returns the child root | Document `RETURN` first, completed root as fallback. |
| `RETURN` mapped from bare `<RESPONSE...>` | Use `<SCENARIO:RESPONSE...>` for the child's own scenario map. |
| Inline child described as remaining queued for later execution | Inline `CALL` executes synchronously and is detached from deferred execution afterward. |

## Local endpoints in the consumer

The consumer's [`LocalTestSite.java`](../maven-consumer-project/src/test/java/com/example/pickleball/support/LocalTestSite.java) starts a loopback-only server for the Cucumber run. The current call definitions exercise:

- `/api/service-calls/inspect`;
- `/api/service-calls/no-content/{itemId}`;
- `/api/service-calls/token`;
- `/api/service-calls/protected`;
- `/api/health`; and
- `/soap/calculator`.

This lets the example project test both Selenium DOM behavior and service calls without an external test environment.

[Previous: Component Scenarios](component-scenarios.md) · [Documentation home](README.md) · [Next: Date and Time Utilities](date-time-utilities.md)
