# Data Values and Data Elements

> **Working feature examples:** [`mapping-value-type-preservation.feature`](../maven-consumer-project/src/test/resources/features/mapping-value-type-preservation.feature) verifies Jackson container preservation through normal mapping reads. [`internal-framework-java-checks.feature`](../maven-consumer-project/src/test/resources/features/internal-framework-java-checks.feature) runs focused DataTable and mapping conversion checks against the locally published dependency.

## Normal storage and retrieval

`NodeMap` stores supported values through one JSON-backed path. Jackson containers remain Jackson containers when read normally:

| Stored value | Normal read result |
|---|---|
| `ObjectNode` | `ObjectNode` |
| `ArrayNode` | `ArrayNode` |
| textual node | `String` |
| numeric node | corresponding Java number |
| boolean node | `Boolean` |
| null or missing node | `null` |
| Cucumber `DataTable` | the stored `DataTable` reference |
| Cucumber `DocString` | the stored `DocString` reference |

Explicit Java `Map` and `List` conversion belongs to the Data Element or converter that requests it. Normal retrieval does not convert Jackson objects and arrays into Java maps and lists.

## Template rendering

A structured Jackson value embedded in text is rendered as compact JSON:

```text
payload=<customer>
items=<order.items>
```

For an object such as `{"name":"Alice"}`, `<customer>` renders as `{"name":"Alice"}`. For an array such as `["a","b"]`, `<order.items>` renders as `["a","b"]`. Scalar values retain their normal text form.

Resolving a template that consists only of one reference preserves the underlying structured value for callers that request the whole value rather than text.

## Data Elements

Data Elements determine explicit conversion behavior:

- `Data` keeps existing `ObjectNode` and `ArrayNode` values unchanged, converts a `DataTable` through the framework DataTable-to-JSON converter, and converts a `DocString` through the existing DocString converter.
- `Data Table` returns a stored native `DataTable` unchanged. A requested `JsonNode` is converted to a DataTable using the rules below.
- `Doc String` returns a stored native `DocString` unchanged.
- `Data Row`, `Data Cell`, `Data Header`, `Data Value`, and `Data Entry` operate on the active data context.

A saved native `DataTable` can be selected as a `Data Table` context and iterated with the existing `Data Row` syntax. The stored value remains the same `DataTable`; the row view is created only for context iteration.

## JSON to DataTable conversion

Conversion uses the top-level JSON shape:

| JSON shape | DataTable shape |
|---|---|
| array of arrays | one physical row per nested array; shorter rows are padded with blanks |
| array of objects | header union in first-seen order, followed by one row per object |
| single object | one header row and one value row |
| flat or mixed array | one physical row |
| scalar | one row with one cell |
| null or missing | conversion error |

Cell rendering is consistent across shapes:

- null or missing becomes an empty string;
- scalar nodes use their text value;
- nested objects and arrays use compact JSON text.

The converter does not infer whether an array-of-arrays row is a header. Its first nested array remains the first physical DataTable row.
