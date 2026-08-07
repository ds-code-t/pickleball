# Data Element Query Runtime

This guide describes the shared Data Element runtime used by tabular sources,
Java collections, and structured JSON/YAML/XML conversions.

## Result modes

A Data Element query produces a `DataSelection`. The runtime then applies one
of three result modes.

| Result mode | Behavior |
|---|---|
| `ContextResult` | Carries one materialized value into the next context. A plural query carries one aggregate collection. |
| `IterationResult` | Expands the selected candidates one at a time. |
| `TerminalResult` | Carries one final action value. A plural query carries one aggregate collection. |

Only `IterationResult` expands candidates automatically.

A terminal plural save therefore performs one save operation:

```gherkin
* , save the Data Rows as "rows"
```

The saved value is one collection containing every selected row. It is not a
sequence of writes to the same key.

## Cardinality at runtime

| Syntax | Runtime behavior |
|---|---|
| singular, no modifier | required first match |
| plural, no modifier | optional aggregate collection |
| `every` | required iteration over all selected candidates |
| `any` | optional iteration over zero or more candidates |
| `every 3rd` | required iteration over filtered positions 3, 6, 9, and so on |
| `any 3rd` | optional iteration over the same stride |
| `first` | required first filtered candidate |
| `last` | required last filtered candidate |

Filtering occurs before ordinal, boundary, or stride selection.

## Source resolution

### Explicit Data Element sources

For Data Elements that accept an explicit source, the leading quoted operand is
the source and is not a comparison predicate.

```gherkin
save "<mapsJson>" Map with key equaling "id" as "mapByKey"
```

This is compiled as:

- source: `<mapsJson>`
- candidate kind: `Map`
- comparison attribute: `key`
- predicate: `equaling "id"`
- return projection: the whole matching Map

The source operand is captured from the existing `ElementMatch` parse state.
`DefinitionContext` does not require a special Data Element regex.

Mappings and native object references are resolved before query execution.
When template expansion renders a saved structured value as JSON text, JSON
object/array text is rehydrated to structured data before Java collection or
`Data Table` adaptation. Structured-format categories can continue to consume
their literal text directly.

### Data tables

Read-only tabular queries use the current phrase's native `DataTable` when one
is available. This preserves physical order, duplicate headers, duplicate
first-column values, and blank cells.

An unquoted `Data Table` keeps the established resolution order:

1. directly attached or active `DataTable`;
2. nearest qualifying unnamed marker `DataTable`.

Quoted `Data Table` values continue to resolve through the existing mapping and
native-reference mechanism. JSON array/object text produced from a saved
structured value is rehydrated before tabular adaptation.

When a previous tabular projection has become the phrase context, the runtime
adapts that local JSON projection as the source for the next query segment.
This allows nested reads such as rows to cells without changing the original
native table.

### Java collections and structured formats

Quoted `Map`, `List`, `Set`, `Multimap`, and structured-format categories
resolve existing mappings and native references before query execution.

Unquoted Java collection categories use the current phrase context. Structured
format categories use an active table first, then the nearest unnamed marker
`DataTable` or `DocString`, then the current phrase context.

Unresolved quoted text is treated as a literal only for structured conversion
categories. The legacy quoted `Data` behavior is unchanged.

## Java collection projections

The runtime supports these Java kinds:

- `Map` / `Maps`
- `List` / `Lists`
- `Set` / `Sets`
- `Multimap` / `Multimaps`

Discovery expands only one direct level per query segment.

| Requested kind | Candidate discovery |
|---|---|
| `List` | A scalar List is one candidate. A direct List/array of Lists exposes each direct child List. |
| `Map` | A scalar Map is one candidate. A direct collection/array of Maps exposes each direct child Map. Jackson objects are adapted as Maps. |
| `Set` | A scalar Set is one candidate. A direct collection of Sets exposes each direct child Set. Lists are not converted to Sets. |
| `Multimap` | A scalar Multimap is one candidate. A direct collection of Multimaps exposes each direct child. A Map with collection/array values converts only when Multimap is explicitly requested. |

Nested values are not searched recursively. Select a nested value as a new
context before querying its contents.

### Explicit collection comparison attributes

Collection predicates require an explicit comparison attribute. There is no
implicit rule such as "Map compares keys" or "List equals compares the first
member."

Examples:

```gherkin
save "<mapsJson>" Map with key equaling "id" as "mapByKey"
save "<mapsJson>" Map with value equaling "pending" as "mapByValue"

save "<listsJson>" List with first equaling "alpha" as "firstList"
save "<listsJson>" List with last equaling "tail" as "lastList"
save "<listsJson>" List with values containing "middle" as "memberList"
save "<listsJson>" List with size equaling 3 as "sizeList"

save "<multimapJson>" Multimap with key equaling "status" as "statusMultimap"
```

The syntax reuses the existing generic `with <name> <predicate>` attribute
channel used by DOM element matching. `DataElementMatch` resolves the name as a
`DataAttribute`; no `DefinitionContext` regex change is required.

Supported collection projections include `key`, `value`, `values`, `size`,
`count`, `first`, `last`, `type`, and `string` where meaningful for the
candidate kind.

Comparison and return attributes are independent. For example:

```gherkin
save key of "<mapsJson>" Map with value equaling "pending" as "keys"
```

filters Maps by their values but returns the key projection from the selected
Map. Without a return attribute, the whole matching collection candidate is
returned.

Encounter order is retained from the source. Duplicate Multimap keys and
values remain duplicated and ordered.

## Structured formats

The runtime supports:

- `Structured Data`, `Data Object`, and `Data Objects`
- `JSON Data`
- `YAML Data`
- `XML Data`
- `Data String` / `Data Strings`
- `JSON String` / `JSON Strings`
- `YAML String` / `YAML Strings`
- `XML String` / `XML Strings`

`Structured Data` uses this detection order:

1. existing `JsonNode`;
2. `DataTable`;
3. typed `DocString`;
4. JSON-like String;
5. XML-like String;
6. YAML.

YAML is last because an ordinary scalar String is valid YAML.

Explicit JSON, YAML, and XML categories parse their declared format and report
conversion errors as `DataQueryException`. JSON String output is compact.
XML String output uses `Data` as the serialization root. Existing XML text is
validated and preserved.

When a phrase contains literal XML with a closing or self-closing tag, use the
existing secondary mapping bookends for placeholders so XML markup is not
parsed as mapping syntax. For example:

```gherkin
verify "~[~xmlString~]~" contains "<name>Ada</name>"
```

XML input containing `DOCTYPE` or `ENTITY` declarations is rejected.

Plural string and `Data Objects` forms convert each direct collection/array
member and return one aggregate terminal collection. Singular null sources are
required and fail; plural null sources are optional and produce an empty
aggregate.

## Materialized values

| Data Element | Singular terminal | Plural terminal |
|---|---|---|
| Data Table | native `DataTable` | ordered Java list |
| Data Row / Data Column / Data Entry | `ObjectNode` | `ArrayNode` |
| Data List / Data Column List | Java `List` | Java list of Lists |
| Data Cell / Data Header / Data Value | scalar | Java list |
| Map | Java `Map` | Java list of Maps |
| List | Java `List` | Java list of Lists |
| Set | Java `Set` | Java list of Sets |
| Multimap | Guava `Multimap` | Java list of Multimaps |
| Structured Data formats | `JsonNode` | Java list of converted values |
| String formats | `String` | Java list of Strings |

## Copy-on-write contexts

`ContextResult` and unprojected `IterationResult` values are exposed as
`DataContextNodeMap` instances. They remain compatible with phrase-level
`NodeMap` reads and writes while retaining the native source and the selected
Data Element cursor.

The first write uses a detached working copy. The original `DataTable`, List,
Map, Set, or Multimap is never changed. Candidate contexts produced by one
selection share the same owner, so replacements made during multiple loop
iterations accumulate. A separately resolved query receives an independent
owner.

Phase 5 supports replacement only:

| Cursor | Replacement path |
|---|---|
| Data Table | zero-based `row.column` |
| Data Row / Data Column | existing key, with optional zero-based duplicate occurrence |
| Data List / Data Column List | existing zero-based item index |
| Data Cell / Data Entry / Data Header / Data Value | `value` |
| List | existing zero-based item index |
| Map | existing key |
| Multimap | existing key, with optional zero-based value occurrence |

For duplicate row keys and Multimap keys, omitting the occurrence replaces the
last existing value. Replacement preserves encounter order and duplicates.
A modified `DataTable` materializes as a new native `DataTable`.

Shape-changing operations remain unsupported. This includes adding or removing
rows, columns, List items, Map keys, Set members, and Multimap entries. Clearing
or merging a `DataContextNodeMap` is also rejected.

`getRoot()` returns a compatibility JSON projection. Editing that returned
`ObjectNode` does not alter the authoritative Data Element context; writes must
go through the `DataContextNodeMap` operations.

Terminal results and iteration queries with a return attribute remain normal
public values rather than mutable context wrappers.

## Compatibility and remaining scope

The runtime retains:

- native marker `DataTable` and `DocString` references;
- quoted DataTable lookup;
- active/direct DataTable precedence;
- unnamed-marker fallback;
- legacy `Data` and `Doc String` conversion paths;
- existing `NodeMap` latest-value retrieval.

The collection comparison contract intentionally requires explicit
`DataAttribute` syntax for predicates. Structural mutation and `Tokenized` Data
Element syntax remain out of scope.
