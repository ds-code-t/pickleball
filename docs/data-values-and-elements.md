# Data Values and Data Elements
> **Working feature examples:** [`mapping-value-type-preservation.feature`](../maven-consumer-project/src/test/resources/features/mapping-value-type-preservation.feature) verifies Jackson container preservation, native and JSON-converted DataTable iteration, Data Row context resolution, explicit `Data`/`Data Table` conversion, and compact JSON string embedding.
[`scenario-data-references.feature`](../maven-consumer-project/src/test/resources/features/scenario-data-references.feature) verifies implicit unnamed-marker lookup for unquoted `Data Table` and `Data` elements.
[`internal-framework-java-checks.feature`](../maven-consumer-project/src/test/resources/features/internal-framework-java-checks.feature) runs focused DataTable and mapping conversion checks against the locally published dependency.
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
Normal indexed and nested reads apply to the latest value stored under the root key. For example, when `rows` contains an `ArrayNode`, `rows[0].name` reads the first object in that stored array rather than indexing the internal root-key history wrapper. An explicit trailing `[]` still requests the complete root-key collection.
## Template rendering

A structured Jackson value embedded in text is rendered as compact JSON:

```text
payload=<customer>
items=<order.items>
```

For an object such as `{"name":"Alice"}`, `<customer>` renders as `{"name":"Alice"}`. For an array such as `["a","b"]`, `<order.items>` renders as `["a","b"]`. Scalar values retain their normal text form.
When compact JSON text is placed inside a quoted dynamic-step argument, parser escaping protects the surrounding phrase but is removed before the value is saved. The stored string therefore contains normal JSON quotes, not literal backslashes before each quote.

Resolving a template that consists only of one reference preserves the underlying structured value for callers that request the whole value rather than text.
## Data Elements

Data Elements determine explicit conversion behavior:
- `Data` keeps existing `ObjectNode` and `ArrayNode` values unchanged, converts a `DataTable` through the framework DataTable-to-JSON converter, and converts a `DocString` through the existing DocString converter.
- `Data Table` returns a stored native `DataTable` unchanged. A requested `JsonNode` is converted to a DataTable using the rules below.
- `Doc String` returns a stored native `DocString` unchanged.
- `Data Row`, `Data Cell`, `Data Header`, `Data Value`, and `Data Entry` operate on the active data context.
A saved native `DataTable` can be selected as a `Data Table` context and iterated with the existing `Data Row` syntax. The stored value remains the same `DataTable`; the row view is created only for context iteration.

### Quoted Data and Data Table elements

Quoted text is treated as a mapping key or preserved native-object reference:

```gherkin
* , save "customerPayload" Data as "savedPayload"
* , in the "<customerRows>" Data Table, for every Data Row:
```

For `Data`, an existing Jackson container is returned unchanged. A referenced
native `DataTable` or `DocString` is converted through the same converters used
elsewhere by the framework. Other referenced values are returned unchanged.

### Unquoted Data Table and Data elements

An unquoted `Data Table` first retains the existing directly attached or active
DataTable behavior. When no active table exists, Pickleball searches unnamed
marker steps in the same scenario, regardless of nesting:

1. the nearest qualifying unnamed marker with a greater source-line number;
2. when none exists below, the nearest qualifying unnamed marker with a smaller
   source-line number.

For `Data Table`, only unnamed markers with a `DataTable` qualify:

```gherkin
When , in the Data Table, for every Data Row:
: * , save "<value>" as "<key>"
* ------
  | key   | value |
  | first | one   |
```

For unquoted `Data`, unnamed markers with either a `DocString` or `DataTable`
qualify. The selected native argument is converted using the normal `Data`
conversion rules:

```gherkin
When , save Data as "payload"
* ------
  """json
  {
    "status": "ready"
  }
  """
```

The result is a Jackson `JsonNode`. A DataTable with one body row converts to
an `ObjectNode`; multiple body rows convert to an `ArrayNode`. A JSON-object
DocString converts to an `ObjectNode`. If no qualifying marker exists above or
below, the element resolves to no values.

Named `<data:...>` references remain unchanged. Unnamed markers still cannot be
addressed explicitly by marker name; this unquoted lookup is positional and
local to the running scenario.
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
