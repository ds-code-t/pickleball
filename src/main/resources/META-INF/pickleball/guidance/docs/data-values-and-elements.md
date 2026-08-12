# Data Values and Data Elements

> **Working feature examples:** [`mapping-value-type-preservation.feature`](../maven-consumer-project/src/test/resources/features/mapping-value-type-preservation.feature) verifies Jackson container preservation and Data Element conversion. [`scenario-data-references.feature`](../maven-consumer-project/src/test/resources/features/scenario-data-references.feature) verifies scenario-marker references, escaped marker addresses, and `data:/` file references.

## Normal storage and retrieval

`NodeMap` stores supported values through a JSON-backed path. Jackson containers remain Jackson containers when read normally:

| Stored value | Normal read result |
|---|---|
| `ObjectNode` | `ObjectNode` |
| `ArrayNode` | `ArrayNode` |
| textual node | `String` |
| numeric node | corresponding Java number |
| boolean node | `Boolean` |
| null or missing node | `null` |
| Cucumber `DataTable` | stored `DataTable` reference |
| Cucumber `DocString` | stored `DocString` reference |

Explicit Java `Map` and `List` conversion belongs to the Data Element or converter that requests it. Normal retrieval does not convert Jackson objects and arrays into Java maps and lists.

A structured Jackson value embedded in text is rendered as compact JSON. A template consisting only of one reference preserves the underlying structured value for callers that request a whole value rather than text.

## Data Elements

Data Elements determine explicit conversion behavior:

- `Data` keeps existing `ObjectNode` and `ArrayNode` values unchanged, converts a `DataTable` through the framework DataTable-to-JSON converter, and converts a `DocString` through the existing DocString converter.
- `Data Table` returns a stored native `DataTable` unchanged. A requested `JsonNode` is converted to a DataTable.
- `Doc String` returns a stored native `DocString` unchanged.
- `Data Row`, `Data Cell`, `Data Header`, `Data Value`, and `Data Entry` operate on the active data context.

### Quoted Data and Data Table elements

Quoted text can resolve a mapping/native-object reference:

```gherkin
* , save "customerPayload" Data as "savedPayload"
* , in the "<customerRows>" Data Table, for every Data Row:
```

For `Data`, an existing Jackson container is returned unchanged. Referenced native `DataTable` or `DocString` values are converted through the standard converters.

### Unquoted Data Table and Data elements

An unquoted `Data Table` first retains directly attached/active DataTable behavior. When no active table exists, Pickleball searches unnamed marker steps in the same scenario, regardless of nesting:

1. nearest qualifying unnamed marker below the referencing step;
2. otherwise nearest qualifying unnamed marker above it.

For unquoted `Data`, unnamed markers with either a DocString or DataTable qualify and are converted using the standard `Data` rules.

## Named scenario-marker data

The lowercase `data:` prefix without a slash addresses marker data:

```text
<data:marker>
<data:scenario.marker>
<data:feature.scenario.marker>
```

The component meanings are right-aligned:

| Components | Meaning |
|---|---|
| 1 | marker |
| 2 | scenario + marker |
| 3 | feature + scenario + marker |

Only unescaped periods delimit components. Use `\.` for a literal period and `\\` for a literal backslash:

```text
<data:Data\.reference\.records.Customer\.record.payload\.marker>
```

That reference points to authored names:

```text
Feature: Data.reference.records
Scenario: Customer.record
Marker: payload.marker
```

Named marker references resolve to the marker's native `DataTable` or `DocString`. Unnamed markers still cannot be addressed explicitly.

Marker-data discovery continues to use the existing resolved `pkb_datapath` behavior.

## Data files with `data:/`

A slash as the first character after `data:` selects file mode:

```text
<data:/file>
<data:/directory/file>
<data:/directory/file.property[0].value>
```

The slash does **not** mean an OS absolute path. The file is resolved beneath the same Pickleball data root used by scenario-marker data.

The existing data-path resolution/default fallback remains unchanged. File parsing and nested lookup reuse `file:` behavior, including suffix-agnostic discovery where supported.

For the consumer fixture:

```text
src/test/resources/data/files/customerPayload.json
```

these references are valid:

```text
<data:/files/customerPayload>
<data:/files/customerPayload.customer>
<data:/files/customerPayload.customer.orders>
<data:/files/customerPayload.customer.orders[0]>
<data:/files/customerPayload.customer.orders[1].id>
```

Type behavior is preserved:

| `data:/` result | Returned value |
|---|---|
| whole JSON document | `ObjectNode` |
| nested JSON object | `ObjectNode` |
| nested JSON array | `ArrayNode` |
| indexed object | `ObjectNode` |
| string/number/boolean path | natural scalar type |

Because whole-value template resolution preserves structured values, a `data:/` reference can be mapped directly without converting it to JSON text first.

## JSON to DataTable conversion

Conversion uses the top-level JSON shape:

| JSON shape | DataTable shape |
|---|---|
| array of arrays | one physical row per nested array; shorter rows padded with blanks |
| array of objects | header union in first-seen order, then one row per object |
| single object | one header row and one value row |
| flat or mixed array | one physical row |
| scalar | one row with one cell |
| null or missing | conversion error |

Cell rendering is consistent across shapes:

- null or missing becomes an empty string;
- scalar nodes use their text value;
- nested objects and arrays use compact JSON text.

The converter does not infer whether an array-of-arrays row is a header.

[Documentation home](README.md)
