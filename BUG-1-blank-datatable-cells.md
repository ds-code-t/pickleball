# Bug 1 — Blank data-table cells never resolve

**Status:** Confirmed defect. Fixed.  
**Version found:** Pickleball 2.1.10  
**Severity:** High — silent data corruption that inverts conditional logic.

## Effect

A blank cell in a Gherkin data table was not exposed as an empty value. The reference survived resolution as the literal placeholder text.

```
* SET "Repro" DATA TABLE
| Col1 | Col2 |
| A    |      |

*, in the "<Repro>" Data Table, for every Data Row:
: *, ensure "<Col1>" equals "A"    # passed
: *, ensure "<Col2>" equals ""     # FAILED: compared '<Col2>' against ''
```

`<Col2>` resolved to the literal 6-character string `<Col2>`. A populated cell in the same row resolved correctly, so the row context itself was working.

Two consequences made this worse than a simple failed assertion:

1. **Emptiness guards silently inverted.** Because the surviving value was neither `""` nor `"null"`, a guard written to skip blank rows ran on every row instead:

```
* IF: "<Col2>" != "" && "<Col2>" != "null" THEN: , ensure Text containing "<Col2>" is displayed
```

The scenario then failed later on an unrelated-looking assertion, far from the real cause.

2. **Asymmetry with Examples.** A blank Examples cell *did* resolve to empty. Identical assertions in one scenario disagreed, which is what made the behavior look arbitrary.

## Cause

The `null` did not originate in Pickleball.

Upstream Cucumber rewrites every empty cell to `null` before a step definition receives the `DataTable`, in `io.cucumber.core.stepexpression.ArgumentMatcher`:

```
private static List<List<String>> emptyCellsToNull(List<List<String>> cells) {
    return cells.stream()
        .map(row -> row.stream()
            .map(s -> s.isEmpty() ? null : s)  // <-- origin
            .collect(Collectors.toList()))
        .collect(Collectors.toList());
}
```

So `dataTable.cells()` already contained `null` by the time Pickleball read it. Note this is *upstream of* `DataTable.create`, which itself documents that it rejects null cells — which is why inspecting `DataTable` in isolation did not reveal the source.

Pickleball then propagated that `null` faithfully:

- `TabularDataAdapter.fromDataTable` copied cells verbatim into a `TabularMatrix`.
- The row context materialized as `{"Col1":"A","Col2":null}`.
- `ParsingMap` line 1036 `owner.get(source)` returned `null`.
- Because a `null` return is indistinguishable from "key absent", line 1048 returned `Resolution(MISSING, ...)`, and the caller preserved the `<...>` text verbatim.

The downstream `ParsingMap` behavior was correct in isolation: it was accurately reporting an absent key. The defect was that a blank cell was being presented to it as absent rather than empty.

**Verification method:** instrumented `TabularDataAdapter.fromDataTable`, which logged `cells=[['Col1','Col2'],['A',<NULL>,]]`, and probed `ParsingMap.get`, which showed the row context as `{"Col1":"A","Col2":null}`. Probes were removed after diagnosis.

## Evidence the intended contract was `""`

Two other readers of the same Cucumber tables already guarded against this, which confirms empty string was always the intended semantic:

- `ModularScenarios.dataTableRows` — `row.put(headers.get(column), value == null ? "" : value)`
- `TableUtils.toFlatStringMultimap` / `blankToEmpty` — normalizes null and blank to `""`

Only the Data Element and DataTable-conversion paths lacked the guard.

## Location and fix

A single shared helper now restores blank cells at the boundary where Cucumber tables enter Pickleball.

**New helper** — `src/main/java/io/cucumber/core/runner/util/TableUtils.java:216`

```
public static List<List<String>> cellsWithRestoredBlanks(DataTable dataTable)
```

**Call sites updated:**

| File | Line | Method |
|---|---|---|
| tools/dscode/common/dataelements/TabularDataAdapter.java | 52 | fromDataTable |
| tools/dscode/coredefinitions/DataTableDefinitions.java | 25 | jsonNode |
| tools/dscode/coredefinitions/DataTableDefinitions.java | 76 | object |
| tools/dscode/coredefinitions/DataTableDefinitions.java | 224 | rowsAsStringMaps |
| tools/dscode/coredefinitions/DataTableDefinitions.java | 244 | firstColumnValues |

The `DataTableDefinitions` occurrences are a second, independent instance of the same root cause, affecting the DataTable → JSON conversion path (`*, save "X" Data as "Y"`). It was not in the original report and was discovered by the new regression test below.

A genuine null still requires the explicit `<^~NULL~^>` marker. Blank values inside structured JSON sources keep their own `null`; only Gherkin table cells are restored.

## Tests added

- `data-element-native-tabular.feature` — blank cell resolves to empty; emptiness guard no longer inverts; `Data` projection yields `""`; plus an Examples-vs-DataTable parity outline.
- `DataTableConversionChecks` — `restoresBlankGherkinCellsThatCucumberRewroteToNull`, `blankCellConvertsToAnEmptyStringRatherThanJsonNull`, `blankCellResolvesAsAnEmptyDataRowValue`.

These tests have teeth: before the `DataTableDefinitions` fix, the `Data`-projection assertion failed, which is how the second site was found.

## Documentation

`docs/data-values-and-elements.md` — new "Blank table cells" section stating the contract and the `<^~NULL~^>` escape for a genuine null.

## Compatibility

Blank cells previously produced an unusable literal placeholder, so no correct scenario could have depended on the old behavior. Scenarios that worked around it by asserting against the literal `<name>` text would need updating, but such a workaround is not present in this repository.
