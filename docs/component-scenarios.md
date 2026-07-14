# Component Scenarios and `RUN SCENARIOS`

Component scenarios are reusable business flows. A calling scenario identifies a component with `RUN SCENARIOS`, supplies values, and runs the component’s steps as nested steps of the caller.

This is useful for behavior such as signing in, creating a customer, preparing an order, or checking a standard result without copying the same steps into many scenarios.

## Calling a component

Use `RUN SCENARIOS` with a data table. The required `Run Tags` column names the component. Every other column supplies a value to that component.

```gherkin
Scenario: calling scenario
  * RUN SCENARIOS
    | Run Tags    | A | B | X  | Y  |
    | %comp_scen1 | 1 |   | x1 | y1 |
    | %comp_scen2 |   | 2 | x2 | y2 |
```

Each row is a separate component call. Pickleball:

1. finds component rows with the matching `%` identifier;
2. combines the caller’s values with the component’s defaults;
3. inserts the component steps beneath `RUN SCENARIOS`; and
4. runs those steps before moving to the next table row.

Use unique component identifiers when one exact match is intended.

## Defining a component

Write the reusable flow as a `Scenario Outline`. Its `Examples` table includes a `Scenario Tags` column whose values begin with `%`:

```gherkin
Scenario Outline: Component Scenarios A: '<A>' , B:'<B>' , X:'<X>' , Y:'<Y>' , Z:'<Z>'
  * print A: '<A>' , B:'<B>' , X:'<X>' , Y:'<Y>' , Z:'<Z>'

  Examples:
    | Scenario Tags | ?A | B | Y  | Z  |
    | %comp_scen1   | 3  | 4 | y3 | Z1 |
    | %comp_scen2   | 5  | 6 | y4 | Z2 |
```

The component may be in the same feature file as the caller or anywhere else under the configured feature path.

The `%` prefix distinguishes a component identifier from an ordinary Cucumber tag such as `@smoke`.

## Combining caller values and component defaults

Values can come from:

- the caller’s `RUN SCENARIOS` row; and
- the matching component `Examples` row.

A caller-only value is passed into the component. A component-only value acts as a default. When both contain the same key, the caller normally has priority.

### Normal default columns

A normal component header such as `B` supplies a default only when the caller does not supply that key.

If the caller includes `B` but leaves the cell blank, the blank is preserved:

```gherkin
| Run Tags    | B |
| %comp_scen1 |   |
```

```gherkin
| Scenario Tags | B |
| %comp_scen1   | 4 |
```

The component receives a blank `B`.

### Defaults that replace blanks

Prefix a component header with `?` when the component default should also replace a blank caller value:

```gherkin
| Scenario Tags | ?A |
| %comp_scen1   | 3  |
```

The key is still `A`:

- caller passes `A=1` → component receives `1`;
- caller includes `A` but leaves it blank → component receives `3`;
- caller omits `A` → component receives `3`.

## Value priority

| Situation | Value used by component steps |
|---|---|
| Caller supplies a nonblank value | Caller value |
| Caller supplies blank and component header is normal | Blank caller value |
| Caller supplies blank and component header begins with `?` | Component default |
| Caller omits the key | Component default, when present |
| Key exists only in caller | Caller value |
| Key exists only in component | Component value |

## Complete example

For the first call:

```gherkin
| %comp_scen1 | 1 |   | x1 | y1 |
```

and component row:

```gherkin
| %comp_scen1 | 3 | 4 | y3 | Z1 |
```

| Key | Final value | Reason |
|---|---|---|
| `A` | `1` | Nonblank caller value overrides `?A=3` |
| `B` | blank | The caller explicitly supplied a blank and `B` is a normal header |
| `X` | `x1` | Defined only by the caller |
| `Y` | `y1` | Nonblank caller value overrides `y3` |
| `Z` | `Z1` | Missing from the caller, so the component default is used |

For the second call:

```gherkin
| %comp_scen2 |   | 2 | x2 | y2 |
```

| Key | Final value | Reason |
|---|---|---|
| `A` | `5` | Blank caller value is replaced because the component header is `?A` |
| `B` | `2` | Nonblank caller value overrides `6` |
| `X` | `x2` | Defined only by the caller |
| `Y` | `y2` | Nonblank caller value overrides `y4` |
| `Z` | `Z2` | Missing from the caller, so the component default is used |

## Reports and logs

`RUN SCENARIOS` remains the parent step. Each component appears beneath it, and the component’s executable steps appear one level deeper.

```text
1 STEP  “*  RUN SCENARIOS”
- - - - - - - - - - -
    2 STEP  “*  SCENARIO: Component Scenarios A: '1' , B:'4' , X:'x1' , Y:'y3' , Z:'Z1'”
- - - - - - - - - - -
      3 STEP  “*  print A: '1' , B:'' , X:'x1' , Y:'y1' , Z:'Z1'”
        [INFO] PRINT:  A: '1' , B:'' , X:'x1' , Y:'y1' , Z:'Z1'
```

The nested scenario title may show values from the component’s examples row. The executable steps show the final values after caller values and component defaults have been combined.

## Components inside branches

A component call may be placed inside a nested or block branch:

```gherkin
* IF: "accountType" equals "business":
: * RUN SCENARIOS
    | Run Tags          | accountId |
    | %business_account | <id>      |
```

A component can contain its own nested steps, conditions, and data tables. Keep component calls understandable and avoid cycles where components call each other indefinitely.

## Recommended practices

1. Use descriptive, unique `%` identifiers.
2. Keep the exact headings `Run Tags` and `Scenario Tags` consistent.
3. Use normal headers when an explicitly blank caller value should remain blank.
4. Use `?` headers only when blank should mean “use the component default.”
5. Keep each component focused on one reusable business behavior.
6. Review nested reports to confirm the final values used by the component steps.

---

[Previous: Block Conditionals](block-conditionals.md) · [Documentation home](README.md) · [Next: Keyboard Expressions](key-parser-dsl.md)
