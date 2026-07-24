# Service-call Scenarios

> **Working feature examples:** [`service-call-execution.feature`](../maven-consumer-project/src/test/resources/features/service-call-execution.feature) invokes the calls; [`service-call-definitions.feature`](../maven-consumer-project/src/test/resources/calls/service-call-definitions.feature) defines the reusable REST and SOAP call scenarios.

Pickleball can define reusable REST and SOAP calls as feature scenarios and invoke them from ordinary test features.

The working consumer stores its call definitions under:

```text
maven-consumer-project/src/test/resources/calls/
```

## Invoke a reusable call

```gherkin
When SERVICE CALL: %rest-get-item
  | name         | itemId | include   | traceId          | responseKey |
  | readItemCall | 73     | inventory | get-feature-test | getResult   |
```

The first token identifies the reusable scenario. The table supplies call-specific values and the map key that will receive the response.

A `Run Tags` column is also supported:

```gherkin
When SERVICE CALL
  | Run Tags | name        | left | right | responseKey |
  | %soap-add | soapAddCall | 11   | 6     | soapResult |
```

## Define a call

```gherkin
Scenario Outline: Read an item through REST
  Given ENDPOINT:<endpoint>/api/items/<itemId>?include=<include>
  And METHOD:<method>
  And HEADERS
    | Accept   | X-Test-Trace |
    | <accept> | <traceId>    |
  When EXECUTE SERVICE CALL
  And MAP SERVICE RESPONSE
    | .response | <responseKey> |

Examples:
  | Scenario Tags  | ?endpoint              | ?method | ?accept          | ?responseKey |
  | %rest-get-item | http://127.0.0.1:8765 | GET     | application/json | getResponse  |
```

See the complete [service-call-definitions.feature](../maven-consumer-project/src/test/resources/calls/service-call-definitions.feature).

## Request steps

### Endpoint

Use a complete endpoint value containing the scheme, host, port, and path:

```gherkin
Given ENDPOINT:<endpoint>/api/items/<itemId>
```

### Method

```gherkin
And METHOD:<method>
```

### Headers

```gherkin
And HEADERS
  | Accept   | X-Test-Trace |
  | <accept> | <traceId>    |
```

### Body

```gherkin
And BODY:json
"""
{
  "name": "<itemName>",
  "quantity": <quantity>
}
"""
```

For XML, use `BODY:xml`. In feature content that must preserve literal angle brackets through Pickleball templating, the working example uses `~[~` and `~]~` escape forms.

### Optional request configuration

`REQUEST CONFIGURATION` can set other REST Assured request-specification values:

```gherkin
Given REQUEST CONFIGURATION
  | relaxedHTTPSValidation |       |
  | urlEncodingEnabled     | false |
```

It is not required merely to set a complete endpoint.

## Execute and map the response

```gherkin
When EXECUTE SERVICE CALL
And MAP SERVICE RESPONSE
  | .response | <responseKey> |
```

The request and response are retained under the call name. Typical mapped paths include:

```text
<getResult.statusCode>
<getResult.body.method>
<getResult.body.itemId>
<getResult.headers.Content-Type>
```

The consumer verifies REST `GET`, `POST`, `PATCH`, and `DELETE` calls and a SOAP call in [service-call-execution.feature](../maven-consumer-project/src/test/resources/features/service-call-execution.feature).

## Local endpoints in the consumer

The consumer's [LocalTestSite.java](../maven-consumer-project/src/test/java/com/example/pickleball/support/LocalTestSite.java) starts a loopback-only server before the Cucumber run. It serves:

- the HTML browser test site;
- REST test endpoints below `/api/`; and
- a SOAP calculator endpoint at `/soap/calculator`.

This lets the same working project test Selenium DOM behavior and service calls without depending on an external environment.

[Previous: Component Scenarios](component-scenarios.md) · [Documentation home](README.md) · [Next: Date and Time Utilities](date-time-utilities.md)
