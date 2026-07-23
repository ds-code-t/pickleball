# Mapping and Templating

Templates let a feature file reuse values instead of repeating them. Pickleball supports:

1. normal Cucumber `Scenario Outline` values;
2. values saved or supplied while a scenario is running;
3. nested values from shared data files; and
4. values returned by a reusable Cucumber step.

All forms use angle brackets:

```text
<key>
```

## Cucumber `Scenario Outline` values

A `Scenario Outline` reads values from its `Examples` table:

```gherkin
Scenario Outline: Display an account
  * print "Account <accountId> belongs to <customerName>"

  Examples:
    | accountId | customerName |
    | A-100     | Ava          |
    | A-200     | Ben          |
```

The outline runs once for each row. For the first row, the effective text is:

```text
Account A-100 belongs to Ava
```

This is standard Cucumber templating and may be used in scenario names, steps, and data tables.

## Values available while a scenario runs

Pickleball can also replace a template with a value saved earlier or supplied by another part of the scenario:

```gherkin
* , save "Ava" as "customerName"
* print "Customer: <customerName>"
```

The printed value is:

```text
Customer: Ava
```

Runtime values may come from saved values, component-scenario tables, configuration files, loaded resources, or other reusable scenario data.

Cucumber resolves `Examples` values first. Pickleball then resolves any remaining templates while the step runs.

## Nested paths

Treat structured data like a JSON document. Use periods to move through named properties.

Assume the available data is:

```json
{
  "customer": {
    "name": "Ava",
    "address": {
      "city": "Phoenix",
      "postalCode": "85001"
    }
  },
  "orders": [
    {
      "id": "A-100",
      "items": [
        { "sku": "BOOK-1", "price": 12.50 },
        { "sku": "PEN-2", "price": 2.00 }
      ]
    },
    {
      "id": "A-101",
      "items": [
        { "sku": "BAG-3", "price": 30.00 }
      ]
    }
  ]
}
```

| Template | Result |
|---|---|
| `<customer.name>` | `Ava` |
| `<customer.address.city>` | `Phoenix` |
| `<customer.address.postalCode>` | `85001` |
| `<orders[0].id>` | `A-100` |
| `<orders[1].items[0].sku>` | `BAG-3` |

Continue a dot-separated path for as many named levels as needed:

```text
<company.division.department.manager.name>
<response.account.profile.contact.address.city>
```

## NodeMap query behavior

Pickleball uses JSONata for reads and a closely related writable-path syntax for saved values. Ordinary top-level names are collection roots. Top-level names beginning with `_` are singleton roots.

### Default collection roots

A read that begins directly with an ordinary property implicitly selects the last item in that top-level collection:

```text
orders.id
```

is evaluated as:

```jsonata
orders[][-1].id
```

This matches write behavior:

```java
nodeMap.put("orders", order);
```

appends `order` to the top-level `orders` array, while:

```java
nodeMap.put("orders.status", "ready");
```

sets `status` on the last `orders` entry.

The implicit `[][-1]` is not added when the first property already has square brackets or when the expression is parenthesized:

```jsonata
orders[0].id
(orders.id)[]
```

### Singleton roots beginning with `_`

A top-level property beginning with `_` is read and written directly rather than as an implicit array:

```java
nodeMap.put("_CONFIG.endpoint", "/service");
```

creates or updates:

```json
{
  "_CONFIG": {
    "endpoint": "/service"
  }
}
```

The corresponding read remains normal JSONata:

```jsonata
_CONFIG.endpoint
```

An underscore is a default-storage convention, not a permanent type restriction. Explicit array syntax such as `_VALUES[0]` or `_VALUES[*]` still works when that property contains an array.

## Array positions and Pickleball selectors

Standard JSONata indexes are zero-based:

```text
orders[0]   first order
orders[1]   second order
orders[-1]  last order
```

Pickleball also preprocesses `#` selectors by subtracting one from every supplied position:

| Pickleball input | JSONata expression |
|---|---|
| `orders #1` | `orders[0]` |
| `orders #2` | `orders[1]` |
| `orders #0` | `orders[-1]` |
| `orders #-1` | `orders[-2]` |
| `orders #first` | `orders[0]` |
| `orders #last` | `orders[-1]` |
| `orders #1-3` | `orders[[0..2]]` |
| `orders #1,3` | `orders[[0,2]]` |

The conversion can be used at several path levels:

```gherkin
* print "First item in the second order: <orders #2.items #1.sku>"
```

Text inside quoted strings, regular expressions, comments, and backticked property names is not changed. For example, `[1..3]` remains a JSONata range and a string containing `"#1"` remains a string.

## Returning collections with JSONata

`as:LIST` is no longer needed. Use normal JSONata grouping and array syntax when the complete result is required.

Given an `orders` array:

| Need | Query |
|---|---|
| Last order | `orders` |
| First order | `orders[0]` |
| Complete orders array | `(orders)` or `orders[]` |
| Last order ID | `orders.id` |
| Every order ID | `(orders.id)[]` |
| Every item SKU | `(orders.items.sku)[]` |
| Last active order | `(orders[status = "active"])[-1]` |
| Every active order ID | `(orders[status = "active"].id)[]` |

Parentheses also provide an explicit way to suppress the implicit top-level last-item selection.

## Property names containing spaces

Simple path property names containing spaces are automatically converted to JSONata backtick syntax:

```text
Customer Requests.Endpoint Name
```

is normalized to:

```jsonata
`Customer Requests`[][-1].`Endpoint Name`
```

Already backticked properties remain unchanged. In complex predicates or function expressions, use explicit JSONata backticks so the expression is unambiguous:

```jsonata
`Customer Requests`[`Customer Name` = "Ava"].`Endpoint Name`
```

Dots inside backticks, strings, regular expressions, and bracket expressions are not treated as path delimiters.

## Writable NodeMap paths

`NodeMap.put` supports direct paths made from:

- properties and backticked properties;
- numeric indexes such as `[0]` and `[-1]`;
- `[]` to append;
- `[*]` to update every existing array element; and
- `*` to update every existing child value.

### Direct write examples

| Query | Effect |
|---|---|
| `orders` | Append a new value to the top-level `orders` array |
| `orders.status` | Set or create `status` on the last order |
| `orders[0].status` | Set or create `status` on the first order |
| `orders[].status` | Append a new order object and set its `status` |
| `orders[*].status` | Set or create `status` on every existing order |
| `orders[*].items[*].status` | Update every existing item in every existing order |
| `_CONFIG.endpoint` | Set or create a singleton configuration property |
| `Customer Requests.Endpoint Name` | Use collection behavior with spaced property names |

A wildcard updates existing entries only. It does not append an entry to an empty array. Missing descendants after a selected object may be created, but a wildcard does not replace an existing scalar with an object or array.

### JSONata-selected writes

When a write is not a direct path, Pickleball finds the earliest selector boundary that leaves a complete direct writable suffix. This keeps as much of the trailing property path as possible so missing descendants can be created.

```java
nodeMap.put("orders[status = \"active\"].result.code", "OK");
```

This selects every attached order whose `status` is `active`, then creates or updates `result.code` on each selected order.

The selector must return objects or arrays that are attached to the original `NodeMap`. Computed scalars and newly constructed JSONata objects are not writable targets.

All assigned values still pass through Pickleball's safe JSON conversion. Values that cannot be safely serialized continue to be stored by unique reference ID before the resulting JSON node is assigned.

## Templates that use the return value of a Cucumber step

When a template key begins with `$`, the remaining text is resolved as a Cucumber step and its returned value is inserted into the surrounding step.

```text
<$step text that returns a value>
```

Examples:

```gherkin
* , save "<$DateTime:now>" as "currentDateTime"
Given , save "<$DateTime:today>" as "today"
Given , save "<$DateTime:tomorrow>" as "tomorrow"
```

A returned Boolean may be used directly in a condition:

```gherkin
* IF: `<$string:"Assa" contains: "ss">`:
: Then , save "matched" as "result"
```

Use this form only with a Cucumber step that is intended to return a value.

## Choosing a template form

| Need | Syntax |
|---|---|
| Value from a `Scenario Outline` row | `<header>` |
| Saved or supplied scenario value | `<key>` |
| Nested property | `<parent.child.value>` |
| Zero-based array position | `<items[0]>` |
| One-based business position | `<items #1>` |
| Complete array result | `<(items)[]>` |
| Return value from a Cucumber step | `<$step text>` |

## Rules to remember

1. Cucumber example values and Pickleball runtime values both use `<` and `>`.
2. Cucumber replaces `Scenario Outline` values before the step runs.
3. Pickleball replaces runtime values while the step runs.
4. Use periods for nested named properties.
5. Square-bracket positions are zero-based.
6. `#` positions are converted by subtracting one; `#first`, `#last`, ranges, and lists are supported.
7. Use parentheses or native JSONata `[]` when a complete collection should be returned.
8. `[*]` is a writable-path wildcard that updates existing array entries.
9. Ordinary top-level names are collection roots; names beginning with `_` are singleton roots.
10. Use backticks explicitly for spaced property names inside complex JSONata expressions.
11. A key beginning with `$` uses the returned value of a matching Cucumber step.

---

[Previous: Dynamic steps](dynamic-steps.md) · [Documentation home](README.md) · [Next: Shared configuration files](config-files-and-resource-mapping.md)
