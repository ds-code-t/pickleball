# Data Element Query Runtime

This guide describes the runtime integration introduced after the shared Data
Element model and read-only tabular query engine.

## Result modes

A Data Element query produces a `DataSelection`. The runtime then applies one
of three result modes.

| Result mode | Behavior |
|---|---|
| `ContextResult` | Carries one materialized value into the next context. A plural query carries one aggregate collection. |
| `IterationResult` | Expands the selected candidates one at a time. |
| `TerminalResult` | Carries one final action value. A plural query carries one aggregate collection. |

Only `IterationResult` expands candidates automatically.

This means a terminal plural save performs one save operation:

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

Filtering occurs before ordinal or stride selection.

## Active table resolution

Read-only tabular queries use the current phrase's native `DataTable` when one
is available. This preserves physical order, duplicate headers, duplicate
first-column values, and blank cells.

An unquoted `Data Table` keeps the established resolution order:

1. directly attached or active DataTable;
2. nearest qualifying unnamed marker DataTable.

Quoted `Data Table` values continue to resolve through the existing mapping and
native-reference mechanism.

When a previous tabular projection has become the phrase context, the runtime
adapts that local JSON projection as the source for the next query segment.
This allows nested reads such as rows to cells without changing the original
native table.

## Current scope

This runtime slice activates the read-only tabular categories:

- `Data Table`
- `Data Row`
- `Data Column`
- `Data List`
- `Data Column List`
- `Data Cell`
- `Data Entry`
- `Data Header`
- `Data Value`

Java `Map`, `List`, `Set`, and `Multimap` projections are implemented in the
next phase. Context-local writes and structured JSON/YAML/XML formats remain
later phases.

## Compatibility

The runtime keeps these existing behaviors:

- native marker `DataTable` and `DocString` references;
- quoted DataTable lookup;
- active/direct DataTable precedence;
- unnamed-marker fallback;
- legacy `Data` and `Doc String` conversion paths;
- existing NodeMap latest-value retrieval.
