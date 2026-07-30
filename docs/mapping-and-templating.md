# Mapping and Templating

> **Working feature examples:** [`mapping-and-resources.feature`](../maven-consumer-project/src/test/resources/features/mapping-and-resources.feature) tests the supported mapping definitions, saved-value clearing, and comma-step `save` actions. [`service-call-definitions.feature`](../maven-consumer-project/src/test/resources/calls/service-call-definitions.feature) uses the same mapping definitions to assemble REST and SOAP request objects.

Pickleball maps values into `NodeMap` scopes and resolves them later through templates such as `<customer.name>`. This guide intentionally documents only these supported definitions from `MappingSteps.java`:

```gherkin
CLEAR SAVED VALUES
MAP "key" TEXT VALUE
MAP "key" OBJECT VALUE
MAP "prefix" TABLE VALUES
MAP "prefix" NON-BLANK TABLE VALUES
MAP "prefix" NON-NULL TABLE VALUES
```

Other definitions currently present in `MappingSteps.java` are intentionally omitted from this guide and from the consumer mapping feature.

## Supported mapping steps

| Step | Purpose |
|---|---|
| `MAP "key" TEXT VALUE` | Resolve templates in an attached DocString and store the result as text without structured parsing. |
| `MAP "key" OBJECT VALUE` | Resolve templates and parse an attached JSON, XML, or YAML DocString into a Jackson tree. |
| `MAP "prefix" TABLE VALUES` | Map the first resolved nonblank candidate value in each row beneath an optional prefix. |
| `MAP "prefix" NON-BLANK TABLE VALUES` | Explicit form of the default table-mapping behavior. |
| `MAP "prefix" NON-NULL TABLE VALUES` | Map the first resolved candidate value that is not null or an unresolved template. Blank values are allowed. |
| `CLEAR SAVED VALUES` | Clear the complete run map. |
| `CLEAR SAVED VALUES:key1,key2` | Remove selected top-level keys from the run map. |

The prefix is optional for table mappings. Each `MAP` step can optionally end with `TO DEFAULT MAP`, `TO OVERRIDE MAP`, `TO SINGLETON MAP`, `TO STEP MAP`, `TO ROOT SCENARIO MAP`, `TO SCENARIO MAP`, or `TO RUN MAP`. Omitting the target uses the run map.

## Map table values

The first cell in each row contains the property path. Every remaining cell is a candidate value. Candidate values are resolved from left to right, and only the first value that satisfies the selected requirement is mapped.

If no candidate value qualifies, nothing is mapped for that row. A row with a blank key is also ignored. The table has no header row.

### Default and `NON-BLANK` behavior

When no requirement is written, table mapping defaults to `NON-BLANK`:

```gherkin
Given MAP "customer" TABLE VALUES TO RUN MAP
  | name         | Ava     |
  | address.city | Phoenix |
  | tier         | Premium |
```

This is equivalent to:

```gherkin
Given MAP "customer" NON-BLANK TABLE VALUES TO RUN MAP
  | name         | Ava     |
  | address.city | Phoenix |
  | tier         | Premium |
```

`NON-BLANK` skips blank values, unresolved templates, and empty object or array values. It continues across the row until it finds the first qualifying value:

```gherkin
Given MAP "customer" TABLE VALUES TO RUN MAP
  | name  | <preferredName> |         | Ava     |
  | city  |                 | Phoenix |         |
  | phone | <missingPhone>  |         |         |
  |       | ignored         |         |         |
```

In this example:

- `customer.name` is set to `Ava` if `<preferredName>` remains unresolved.
- `customer.city` is set to `Phoenix`.
- `customer.phone` is not set because none of its candidate values qualify.
- The last row is ignored because its key is blank.

The values can then be resolved as:

```text
<customer.name>
<customer.city>
```

### `NON-NULL` behavior

Use `NON-NULL` when a blank value should count as a supplied value. It still skips null values and unresolved templates, but it does not skip blank text or empty object or array values.

```gherkin
Given MAP "customer" NON-NULL TABLE VALUES TO RUN MAP
  | nickname |             | Ava |
  | id       | <missingId> | 123 |
```

In this example:

- `customer.nickname` is set to a blank value. Because that value qualifies for `NON-NULL`, the mapper does not continue to `Ava`.
- `customer.id` is set to `123` if `<missingId>` remains unresolved.

### Optional prefixes

Without a prefix, the first column supplies the complete key:

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

`TEXT` resolves templates in the DocString and stores the result as text without parsing it as JSON, XML, or YAML:

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

`OBJECT` resolves templates in the DocString and then parses the result into a Jackson `JsonNode`:

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
