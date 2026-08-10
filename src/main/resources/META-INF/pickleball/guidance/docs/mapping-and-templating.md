# Mapping and Templating

> **Working feature examples:** [`mapping-and-resources.feature`](../maven-consumer-project/src/test/resources/features/mapping-and-resources.feature) covers supported mapping definitions. [`mapping-value-type-preservation.feature`](../maven-consumer-project/src/test/resources/features/mapping-value-type-preservation.feature) covers Jackson type preservation. [`scenario-data-references.feature`](../maven-consumer-project/src/test/resources/features/scenario-data-references.feature) covers marker-data and `data:/` file references.

Pickleball maps values into `NodeMap` scopes and resolves them later through templates such as `<customer.name>`.

## Supported mapping steps

```gherkin
CLEAR SAVED VALUES
MAP "key" TEXT VALUE
MAP "key" OBJECT VALUE
MAP "prefix" TABLE VALUES
MAP "prefix" NON-BLANK TABLE VALUES
MAP "prefix" NON-NULL TABLE VALUES
```

Each mapping step may optionally end with `TO DEFAULT MAP`, `TO OVERRIDE MAP`, `TO SINGLETON MAP`, `TO STEP MAP`, `TO ROOT SCENARIO MAP`, `TO SCENARIO MAP`, or `TO RUN MAP`. Omitting the target uses the run map.

## Table values

The first cell in each row contains the property path. Remaining cells are candidate values. The default and `NON-BLANK` forms select the first resolved nonblank candidate. `NON-NULL` allows blank values but skips null and unresolved references.

```gherkin
Given MAP "customer" TABLE VALUES TO RUN MAP
  | name         | Ava     |
  | address.city | Phoenix |
  | tier         | Premium |
```

Values resolve as:

```text
<customer.name>
<customer.address.city>
```

Structured whole-value references are stored without converting Jackson objects and arrays to text.

## Raw text and structured DocStrings

`TEXT` resolves templates but keeps the final DocString as text:

```gherkin
Given MAP "REQUEST.body" TEXT VALUE TO SCENARIO MAP
  """xml
  <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/">
    <soapenv:Body/>
  </soapenv:Envelope>
  """
```

`OBJECT` resolves templates and parses JSON, XML, or YAML to a Jackson `JsonNode`:

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

## Save and clear values

Dynamic comma steps can save values:

```gherkin
* , save "Ava" as "customerName"
* , save 3 as "retryCount"
```

Clear the run map with:

```gherkin
CLEAR SAVED VALUES
```

or selected top-level keys with:

```gherkin
CLEAR SAVED VALUES:temporaryToken,customerDraft
```

## Template paths

Normal nested references include:

```text
<customer.name>
<customer.address.city>
<orders[0].id>
<orders #2.items #1.sku>
```

XML-safe bookends are available where angle brackets conflict with XML markup:

```text
~[~customer.name~]~
```

## Source-qualified references

Recognized lowercase source prefixes are resolved before ordinary map lookup.

### `file:`

`file:` uses the existing resource lookup, parsing, suffix-agnostic discovery, and nested-query behavior:

```text
<file:files/customers>
<file:files/customers.customer.name>
```

### Scenario marker `data:`

Without a slash, `data:` performs scenario-marker lookup. Addresses are right-aligned:

```text
<data:marker>
<data:scenario.marker>
<data:feature.scenario.marker>
```

Only unescaped periods separate components. Escape a literal period with `\.` and a literal backslash with `\\`:

```text
<data:Data\.reference\.records.Customer\.record.payload\.marker>
```

This addresses:

```text
Feature: Data.reference.records
Scenario: Customer.record
Marker: payload.marker
```

Marker references resolve directly to the marker's attached native `DataTable` or `DocString`.

### Data-file `data:/`

A slash immediately after `data:` switches to file lookup rooted below the resolved Pickleball data path:

```text
<data:/file>
<data:/files/customerPayload>
<data:/files/customerPayload.customer>
<data:/files/customerPayload.customer.orders>
<data:/files/customerPayload.customer.orders[0]>
<data:/files/customerPayload.customer.orders[1].id>
```

The leading slash is only a syntax discriminator. It is **not** an operating-system absolute path.

`data:/` uses the same already-resolved `pkb_datapath` and default fallback as marker-data lookup. It then reuses the existing `file:` machinery for:

- filesystem/classpath resource resolution supported by the data root;
- suffix-agnostic file discovery;
- JSON/YAML/XML and other supported formats;
- nested object and array queries.

For example, with:

```text
pkb_datapath=src/test/resources/data
```

this reference:

```text
<data:/files/customerPayload>
```

can resolve:

```text
src/test/resources/data/files/customerPayload.json
```

without requiring the `.json` suffix.

Whole documents and nested objects/arrays remain Jackson `JsonNode` values (`ObjectNode`/`ArrayNode`). Nested scalar queries return their natural scalar value.

## Scenario and run-map references in reusable components

A reusable component has its own scenario map. Use explicit map prefixes for caller-owned values:

```text
<PARENT.SCENARIO:requestTemplate.client>
<PARENT.SCENARIO:requestTemplate.body.quantity>
```

A completed named service call is stored in the shared RunMap and is referenced directly:

```text
<seedCall.RESPONSE.statusCode>
<seedCall.RESPONSE.body.body.quantity>
```

## `~unquote` for raw JSON values

Append `~unquote` when a quoted template must insert a number, boolean, object, or array as raw JSON:

```text
"<order.quantity~unquote>"
"<order.active~unquote>"
"<order.metadata~unquote>"
"<order.items~unquote>"
```

`~unquote` removes one directly surrounding matching quote pair after resolution. Normal string references should remain ordinarily quoted.

## Index selectors

Square-bracket indexes use zero-based JSONata indexing. Pickleball `#` selectors provide one-based-friendly access:

| Pickleball selector | Effective selector |
|---|---|
| `#1` | `[0]` |
| `#2` | `[1]` |
| `#first` | `[0]` |
| `#last` | `[-1]` |
| `#1-3` | `[0..2]` |
| `#1,3` | `[0,2]` |

## Working examples

- [Supported mapping and save-action tests](../maven-consumer-project/src/test/resources/features/mapping-and-resources.feature)
- [Type-preservation and `data:/` tests](../maven-consumer-project/src/test/resources/features/scenario-data-references.feature)
- [Generic mappings in reusable service calls](../maven-consumer-project/src/test/resources/calls/service-call-definitions.feature)

[Previous: Dynamic Steps](dynamic-steps.md) · [Documentation home](README.md) · [Next: Configuration Files and Resource Mapping](config-files-and-resource-mapping.md)
