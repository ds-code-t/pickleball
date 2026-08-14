# Mapping and Templating

> **Working feature examples:** [`mapping-and-resources.feature`](../maven-consumer-project/src/test/resources/features/mapping-and-resources.feature) covers supported mapping definitions and mapping directives. [`mapping-value-type-preservation.feature`](../maven-consumer-project/src/test/resources/features/mapping-value-type-preservation.feature) covers Jackson type preservation. [`scenario-data-references.feature`](../maven-consumer-project/src/test/resources/features/scenario-data-references.feature) covers marker-data and `data:/` file references.

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

Conversion directives, `~unresolved;`, and the destination-key behavior `~merge;` can be appended to a table row key. Conversion directives are applied to the selected value, while `~merge;` is preserved for the final NodeMap put. `~unquoted;` is invalid on table row keys because row values are not quote-splice positions:

```gherkin
Given MAP TABLE VALUES TO RUN MAP
  | payload~JSON;                  | {"active":true}               |
  | deferred~unresolved;           | <laterValue>                    |
  | payload~JSON;~merge;           | {"metadata":{"source":"api"}} |
```

The first row stores structured JSON at `payload`. The second stores the literal unresolved reference at `deferred`. The third resolves the normal current `payload` value and merges the converted JSON into that returned container in place.

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

Destination keys on mapping puts can use `~merge;`:

```gherkin
Given MAP "customer~merge;" OBJECT VALUE TO RUN MAP
  """json
  {
    "active": true,
    "settings": {
      "retries": 4
    }
  }
  """
```

`~merge;` is a put behavior, not a reference conversion. The suffix is removed, Pickleball performs the same normal `NodeMap.get(key)` selection that the unsuffixed key would use, and a compatible returned Jackson container is mutated in place. Future gets therefore observe the merged value. If the get returns null, the value is stored using the ordinary put behavior.

Structured object keys can carry conversion directives. The directive suffix is removed from the final property name. Behavior directives (`~unresolved;` and `~unquoted;`) are invalid on structured keys; put `~unresolved;` on the value reference instead, and use `~unquoted;` only in a directly quoted raw-text insertion:

```json
{
  "payload~JSON;": "{\"active\":true}"
}
```

A single key made only of directives converts and replaces the whole object value:

```json
{
  "~JSON;": "{\"score\":4}"
}
```

A directive-only key is invalid when its object contains other keys.

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

Mapping directives and pipelines can also be used inside the XML-safe bookends:

```text
~[~payload~JSON;::name~]~
```

## Mapping directives

A directive is appended to a mapping source or pipeline stage as `~NAME;`. Multiple directives form a left-to-right conversion chain:

```text
<payload~JSON;>
<payload~JSON;~XML-STRING;>
<payload~JSON;~unresolved;>
```

Supported suffix directives and destination-key behaviors are:

| Directive | Result |
|---|---|
| `~unresolved;` | Resolve the carrier reference but leave mapping references contained in its returned value unresolved. |
| `~unquoted;` | When the reference is directly surrounded by one matching quote pair, remove that pair and insert the resolved value raw. |
| `~JSON;` | Convert to structured JSON data. |
| `~JSON-STRING;` | Convert to a JSON string. |
| `~XML;` | Convert XML input to structured data. |
| `~XML-STRING;` | Convert to an XML string. |
| `~YAML;` | Convert YAML input to structured data. |
| `~YAML-STRING;` | Convert to a YAML string. |
| `~DATA;` | Best-effort structured-data conversion. |
| `~STRING;` | Convert to the generic Pickleball data-string representation. |
| `~MAP;` | Convert to a Java `Map`. |
| `~LIST;` | Convert to a Java `List`. |
| `~SET;` | Convert to a Java `Set`. |
| `~MULTIMAP;` | Convert to a Guava multimap. |
| `~DATATABLE;` | Convert structured tabular data to a Cucumber `DataTable`. |
| `~DOCSTRING;` | Convert to a Cucumber `DocString`. |
| `~merge;` | Destination-key put behavior: merge into the container returned by the normal unsuffixed `NodeMap.get` selection. |

Conversion failures are errors. Pickleball does not silently substitute null for an invalid directive conversion.

### Directive placement

| Carrier | Conversion directives | `~unresolved;` | `~unquoted;` | `~merge;` |
|---|---|---|---|---|
| Mapping/value reference / pipeline stage | Yes | Yes | Yes, only when directly surrounded by one matching quote pair | No |
| `MAP TABLE VALUES` row key | Yes | Yes | No | Yes |
| `MAP "key" TEXT/OBJECT VALUE` destination key | No | No | No | Yes |
| Structured object key / directive-only wrapper | Yes | No | No | No |

Invalid placement fails with a migration-oriented error rather than being silently ignored. Resolution behavior is applied before conversion directives; conversion chains execute left-to-right.

### In-place destination merging with `~merge;`

`~merge;` applies only to destination keys used by NodeMap put operations. Conceptually the write is:

```text
baseKey = key without ~merge;
existing = nodeMap.get(baseKey);
```

The `get` is the ordinary NodeMap get. That means all existing query behavior remains authoritative, including the normal top-level latest-value selection and any explicit query/index form supplied in the key. Pickleball then applies these rules:

1. If the incoming value is Java null or a whole Jackson null node, do nothing.
2. If `get(baseKey)` returns null, perform the ordinary put of the incoming value.
3. `ObjectNode + ObjectNode` recursively merges into the existing object in place.
4. `ArrayNode + ArrayNode` appends the incoming array items to the existing array in place.
5. Any other existing/incoming top-level type combination fails with a descriptive merge error.

During recursive object merge, object/object fields recurse, array/array fields append, and other field collisions are replaced by the incoming field. An incoming JSON null field therefore replaces that field with JSON null; the whole-value null no-op rule applies only to the value passed to the `~merge;` put itself.

Because the selected container is mutated rather than replaced, later gets of that same selected value observe the merge without adding another NodeMap history entry.

Examples:

```text
customer~merge;
customer.settings~merge;
customerHistory[][0]~merge;
```

The last example is intentionally just an ordinary NodeMap query plus `~merge;`: whichever container `get("customerHistory[][0]")` returns is the container that is mutated.

### Raw insertion with `~unquoted;`

Use `~unquoted;` when a quoted template position must insert a number, boolean, object, or array as a raw JSON-style value:

```text
"<order.quantity~unquoted;>"
"<order.active~unquoted;>"
"<order.metadata~unquoted;>"
"<order.items~unquoted;>"
```

Only one directly surrounding matching quote pair is removed. Without `~unquoted;`, the resolved value remains inside the surrounding quotes and is escaped as needed.

### Deferred nested references with `~unresolved;`

Normal mapped strings are recursively resolved:

```text
saved = <customer.name>
<saved> -> Ava
```

Use `~unresolved;` to resolve `saved` itself while preserving mapping syntax returned by that value:

```text
<saved~unresolved;> -> <customer.name>
```

References needed to form an outer source or query are still resolved. This allows dynamic selectors such as:

```text
<data:feature.scenario.marker::items[<idx>].name>
```

## Query pipelines

Append `::query` to query the value produced by the previous source or stage:

```text
<source::query>
<payload~JSON;::customer.name>
<payload~JSON;::items[<idx>].name>
```

The source is resolved first, then each query stage runs left-to-right. Each stage can have its own directives:

```text
<payload~JSON;::items~LIST;>
```

Pipeline parsing is aware of quotes and balanced JSON/object/array/parenthesis content. A `::` inside a quoted structured literal is data, not a pipeline separator:

```text
<value:{"text":"a::b"}~JSON;::text>
```

A query that cannot produce a value fails with a mapping error identifying the query and reference. It does not silently return null.

## Literal `value:` sources

`value:` makes the source text itself the value rather than a map lookup:

```text
<value:hello>
<value:{"a":3}~JSON;::a>
```

Use a mask when literal text contains Pickleball mapping syntax that must not execute:

```text
<value:~^^hello <name>^^~>
```

## Verbatim masks

`~^^ ... ^^~` protects its contents from mapping, directive, pipeline, and expression parsing. The mask markers themselves are removed after the surrounding resolution is complete:

```text
~^^<customer.name>^^~
~^^literal :: text~JSON;^^~
<value:~^^hello <name>^^~>
```

Use masks for literal mapping delimiters or directive-looking text. JSON strings containing ordinary quotes, braces, arrays, or `::` do not need a mask merely because they are structured data; the pipeline parser is quote and bracket aware.

## Special literal markers

The following references produce explicit special values:

```text
<^~NULL~^>
<^~NAN~^>
<^~INF~^>
<^~-INF~^>
<^~TAB~^>
<^~EMPTY~^>
```

They represent explicit null, `NaN`, positive infinity, negative infinity, a tab character, and an empty string respectively.

## Source-qualified references

Recognized lowercase source prefixes are resolved before ordinary map lookup.

### `config:`

`config:` reads from the configuration data loaded after final RunVar resolution from `pkb_configpath`:

```text
<config:application.baseUrl>
<config:users.admin.name>
```

This is the recommended syntax for new configuration references. Legacy references remain valid and address the same data:

```text
<configs.application.baseUrl>
<configs.users.admin.name>
```

Runtime configuration mappings cannot participate in resolving `default_profile`, named profiles, `pkb_runvars`, `pkb_configpath`, or the final `pkb_run_profile`; the RunVar configuration must be complete before the config data can be loaded.

See [Configuration Files and Resource Mapping](config-files-and-resource-mapping.md).

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

Marker data can feed a pipeline directly:

```text
<data:feature.scenario.marker::items[<idx>].name>
```

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

`data:/` uses the same already-resolved `pkb_datapath` and default fallback as marker-data lookup. It then reuses the existing `file:` machinery for filesystem/classpath resource resolution supported by the data root, suffix-agnostic discovery, supported structured formats, and nested object/array queries.

For example, with:

```text
pkb_datapath=src/test/resources/data
```

this reference:

```text
<data:/files/customerPayload>
```

can resolve `src/test/resources/data/files/customerPayload.json` without requiring the `.json` suffix.

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

## Deprecated return-value reference

`<&key>` remains supported for compatibility but logs a deprecation warning. Prefer a named mapping or explicit dynamic-step result instead of introducing new `<&...>` references.

## Removed conversion syntax

The former `~unquote` suffix is removed. Replace it with `~unquoted;`:

```text
<order.metadata~unquoted;>
```

The former `ValConverter` marker forms such as `~JSON~`, `~MAP~`, `~STRING~`, `~INT~`, `~RESOLVE~`, `~JSON~:...`, and the bare `^~NULL~^` form are also removed and are not aliases for the directive grammar. The canonical null marker is `<^~NULL~^>`. Use the `~NAME;` directives, `value:` sources, query pipelines, and explicit special literal markers documented above. Removed forms fail with a migration error rather than being interpreted silently.

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

- [Supported mapping, directive, and save-action tests](../maven-consumer-project/src/test/resources/features/mapping-and-resources.feature)
- [Type-preservation and `data:/` tests](../maven-consumer-project/src/test/resources/features/scenario-data-references.feature)
- [Generic mappings in reusable service calls](../maven-consumer-project/src/test/resources/calls/service-call-definitions.feature)

[Previous: Dynamic Steps](dynamic-steps.md) · [Documentation home](README.md) · [Next: Configuration Files and Resource Mapping](config-files-and-resource-mapping.md)
