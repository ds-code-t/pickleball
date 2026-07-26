# Mapping and Templating

> **Working feature examples:** [`mapping-and-resources.feature`](../maven-consumer-project/src/test/resources/features/mapping-and-resources.feature) tests the supported mapping definitions, saved-value clearing, and comma-step `save` actions. [`service-call-definitions.feature`](../maven-consumer-project/src/test/resources/calls/service-call-definitions.feature) uses the same mapping definitions to assemble REST and SOAP request objects.

Pickleball maps values into `NodeMap` scopes and resolves them later through templates such as `<customer.name>`. This guide intentionally documents only these supported definitions from `MappingSteps.java`:

```gherkin
CLEAR SAVED VALUES
MAP "key" TEXT VALUE
MAP "key" OBJECT VALUE
MAP "prefix" TABLE VALUES
```

Other definitions currently present in `MappingSteps.java` are intentionally omitted from this guide and from the consumer mapping feature.

## Supported mapping steps

| Step | Purpose |
|---|---|
| `MAP "key" TEXT VALUE` | Store an attached DocString as raw text. |
| `MAP "key" OBJECT VALUE` | Parse an attached JSON, XML, or YAML DocString into a Jackson tree. |
| `MAP "prefix" TABLE VALUES` | Map two-column table rows beneath an optional prefix. |
| `CLEAR SAVED VALUES` | Clear the complete run map. |
| `CLEAR SAVED VALUES:key1,key2` | Remove selected top-level keys from the run map. |

Each `MAP` step can optionally end with `TO DEFAULT MAP`, `TO OVERRIDE MAP`, `TO SINGLETON MAP`, `TO STEP MAP`, `TO ROOT SCENARIO MAP`, `TO SCENARIO MAP`, or `TO RUN MAP`. Omitting the target uses the run map.

## Map table values

Each table row contains a property path followed by its value. The table has no header row.

```gherkin
Given MAP "customer" TABLE VALUES TO RUN MAP
  | name         | Ava     |
  | address.city | Phoenix |
  | tier         | Premium |
```

The values can then be resolved as:

```text
<customer.name>
<customer.address.city>
<customer.tier>
```

The prefix is optional. Without one, the first column supplies the complete key:

```gherkin
Given MAP TABLE VALUES TO RUN MAP
  | status  | ready |
  | retries | 2     |
```

Nested request sections use nested prefixes:

```gherkin
Given MAP "REQUEST" TABLE VALUES TO SCENARIO MAP
  | endpoint | http://127.0.0.1:8765/api/health |
  | method   | GET                               |

And MAP "REQUEST.headers" TABLE VALUES TO SCENARIO MAP
  | Accept        | application/json |
  | X-Test-Client | consumer-test    |
```

These general mapping steps are the supported way to prepare values used by reusable service-call scenarios.

## Map raw text

`TEXT` stores the DocString content without parsing it:

```gherkin
Given MAP "REQUEST.body" TEXT VALUE TO SCENARIO MAP
  """xml
  <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/">
    <soapenv:Body/>
  </soapenv:Envelope>
  """
```

A DocString content type may still be supplied for editor formatting, but it does not change how `TEXT` is stored.

## Map JSON, XML, or YAML objects

`OBJECT` parses the DocString into a Jackson `JsonNode`:

```gherkin
Given MAP "customer" OBJECT VALUE TO RUN MAP
  """json
  {
    "name": "Ava",
    "active": true,
    "orders": [
      { "id": "A-100" },
      { "id": "A-200" }
    ]
  }
  """
```

The DocString content type is required for `OBJECT`.

| Format | Accepted content types |
|---|---|
| JSON | `json`, `application/json`, `text/json` |
| XML | `xml`, `application/xml`, `text/xml` |
| YAML | `yaml`, `yml`, `application/yaml`, `application/x-yaml`, `text/yaml`, `text/x-yaml` |

Examples:

```gherkin
Given MAP "yamlCustomer" OBJECT VALUE
  """yaml
  name: Ben
  address:
    city: Tempe
  """

And MAP "xmlCustomer" OBJECT VALUE
  """xml
  <customer>
    <name>Cara</name>
    <city>Mesa</city>
  </customer>
  """
```

## Map targets

| Target | Selected map |
|---|---|
| omitted or `RUN` | Run map |
| `DEFAULT` | Default/fallback map |
| `OVERRIDE` | Override map |
| `SINGLETON` | Singleton map |
| `STEP` | Current step map |
| `SCENARIO` | Closest scenario-step map |
| `ROOT SCENARIO` | Root scenario-step map |

`SCENARIO` is useful for reusable component scenarios because the mapped request stays associated with that scenario while its child steps execute.

> **Current implementation note:** the Cucumber expressions accept `ROOT SCENARIO`, but the map-selection switch currently checks `SCENARIO ROOT`. Do not rely on this target until those two forms are made consistent.

## Save values from comma dynamic steps

The comma dynamic-step parser has a `save` action. It stores a resolved value under a named key so later steps can retrieve it through the normal template syntax.

Save text or a number:

```gherkin
* , save "Ava" as "customerName"
* , save 3 as "retryCount"
```

Save a value already supplied by a mapping:

```gherkin
Given MAP "customer" TABLE VALUES
  | city | Phoenix |

When , save "<customer.city>" as "savedCity"
Then , ensure "<savedCity>" equals "Phoenix"
```

Saving another value under the same key adds a newer value. A normal template lookup returns the latest value:

```gherkin
* , save "draft" as "status"
* , save "ready" as "status"
* , ensure "<status>" equals "ready"
```

The save action is part of the comma dynamic-step language rather than a separate `MappingSteps` Cucumber definition. See [Dynamic Steps](dynamic-steps.md) for the general comma-step syntax.

## Clear saved run values

Clear the complete run map:

```gherkin
Given CLEAR SAVED VALUES
```

Clear selected top-level keys while leaving other run-map values intact:

```gherkin
Given CLEAR SAVED VALUES:temporaryToken,customerDraft
```

Only the run map is cleared. Default, override, singleton, step, and scenario maps are not cleared by this definition.

## Templates and nested paths

Use angle brackets to resolve saved or mapped values:

```text
<customer.name>
<customer.address.city>
<orders[0].id>
<orders #2.items #1.sku>
```

XML-safe bookends are also available where ordinary angle brackets would conflict with XML markup:

```text
~[~customer.name~]~
```

Square-bracket indexes use normal zero-based JSONata indexing. Pickleball `#` selectors provide one-based-friendly access:

| Pickleball selector | Effective selector |
|---|---|
| `#1` | `[0]` |
| `#2` | `[1]` |
| `#first` | `[0]` |
| `#last` | `[-1]` |
| `#1-3` | `[0..2]` |
| `#1,3` | `[0,2]` |

Ordinary top-level mapped properties retain Pickleball's history behavior, so an unqualified lookup returns the latest entry. Use `[]` or `[*]` where the complete array is required.

## Working examples

- [Supported mapping and save-action tests](../maven-consumer-project/src/test/resources/features/mapping-and-resources.feature)
- [Generic mappings in reusable service calls](../maven-consumer-project/src/test/resources/calls/service-call-definitions.feature)
- [Service-call caller feature](../maven-consumer-project/src/test/resources/features/service-call-execution.feature)

[Previous: Dynamic Steps](dynamic-steps.md) · [Documentation home](README.md) · [Next: Configuration Files and Resource Mapping](config-files-and-resource-mapping.md)
