# Service-call scenarios

Service-call scenarios are reusable scenarios stored below:

```text
maven-consumer-project/src/test/resources/calls
```

A normal feature invokes one call by tag:

```gherkin
When SERVICE CALL: %rest-get-item
  | name         | itemId | include   | traceId          | responseKey |
  | readItemCall | 73     | inventory | get-feature-test | getResult   |
```

See:

- [Service-call definitions](../maven-consumer-project/src/test/resources/calls/service-call-definitions.feature)
- [Features that invoke and verify the calls](../maven-consumer-project/src/test/resources/features/service-call-execution.feature)
- [Local REST and SOAP endpoints](../maven-consumer-project/src/test/java/com/example/pickleball/support/LocalTestSite.java)

## Request steps

### Endpoint

Use one complete endpoint value, including scheme, host, and port:

```gherkin
Given ENDPOINT:<endpoint>/api/items/<itemId>?include=<include>
```

The example default can contain the test-site address:

```gherkin
| ?endpoint             |
| http://127.0.0.1:8765 |
```

REST Assured can configure `baseUri` and `port` separately, but that is not required. Passing a complete URI to `request(method, endpoint)` is simpler and avoids REST Assured's `http://localhost:8080` defaults when no base configuration is present.

### Method

```gherkin
And METHOD:<method>
```

The method is passed to REST Assured's generic `request(method, endpoint)` API.

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
    "name": "<itemName>"
  }
  """
```

For XML bodies, use `~[~` and `~]~` for literal angle brackets:

```gherkin
And BODY:xml
  """
  ~[~calc:Add~]~
    ~[~calc:left~]~<left>~[~/calc:left~]~
  ~[~/calc:Add~]~
  """
```

### Other REST Assured settings

`REQUEST CONFIGURATION` remains available for optional request-specification settings that are not represented by endpoint, method, headers, or body:

```gherkin
Given REQUEST CONFIGURATION
  | relaxedHTTPSValidation |     |
  | urlEncodingEnabled     | false |
```

It is not needed merely to set the test-site host and port.

## Execution and response

```gherkin
When EXECUTE SERVICE CALL
```

The request and response are stored together below the service-call name:

```text
<Service call name>.request.endpoint
<Service call name>.request.method
<Service call name>.request.headers
<Service call name>.request.body

<Service call name>.response.method
<Service call name>.response.statusCode
<Service call name>.response.headers
<Service call name>.response.body
```

The caller's nonblank `name` value is used as the service-call name. Otherwise, the called scenario name is used.

`MAP SERVICE RESPONSE` copies values from the service-call scenario's default `NodeMap` into the running map:

```gherkin
And MAP SERVICE RESPONSE
  | <SERVICE CALL NAME>.response | <responseKey> |
```

## Request and response logging

`EXECUTE SERVICE CALL` writes REST Assured request and response logging through Pickleball's `logInfo(...)` output.

The execution step now reads the complete request with:

```java
nodeMap.get(callName + ".request")
```

If the endpoint is absent, it records an immediate error instead of allowing REST Assured to send a request to its default `http://localhost:8080/` URI.

For the consumer test site, the request log should begin with a URI such as:

```text
Request URI: http://127.0.0.1:8765/api/items/73?include=inventory
```
