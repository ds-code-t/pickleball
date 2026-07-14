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

## Array positions

### Standard zero-based positions

Square-bracket positions begin at zero:

```text
orders[0]   first order
orders[1]   second order
orders[2]   third order
```

```gherkin
* print "First order: <orders[0].id>"
* print "First item in the second order: <orders[1].items[0].sku>"
```

### Business-readable one-based positions

Pickleball also supports `#` followed by a one-based position:

```text
orders #1   first order
orders #2   second order
orders #3   third order
```

These are equivalent:

```text
<orders[0].id>
<orders #1.id>
```

The one-based form can be used at several levels:

```gherkin
* print "First item in the second order: <orders #2.items #1.sku>"
```

Use `#1` for the first item, `#2` for the second, and so on.

## Wildcards and lists

Use `[*]` to select every item in an array:

```gherkin
* print "Order IDs: <orders[*].id as:LIST>"
```

Continue through nested arrays by adding another wildcard:

```gherkin
* print "All item SKUs: <orders[*].items[*].sku as:LIST>"
```

A numbered position can be combined with a wildcard:

```gherkin
* print "Every SKU in the first order: <orders #1.items[*].sku as:LIST>"
```

Append `as:LIST` when the complete set of matches is needed:

```text
<orders[*].id as:LIST>
<orders[*].items[*].sku as:LIST>
```

The query style is based on familiar JSONata concepts, with Pickleball’s `#1`, `#2`, and similar one-based positions added for business readability. Ordinary feature files usually need only property paths, positions, and array wildcards.

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
| Every array item | `<items[*] as:LIST>` |
| Return value from a Cucumber step | `<$step text>` |

## Rules to remember

1. Cucumber example values and Pickleball runtime values both use `<` and `>`.
2. Cucumber replaces `Scenario Outline` values before the step runs.
3. Pickleball replaces runtime values while the step runs.
4. Use periods for nested named properties.
5. Square-bracket positions are zero-based.
6. `#` positions are one-based.
7. Use `[*]` for every item in an array.
8. Add `as:LIST` when several matching values should be kept as a list.
9. A key beginning with `$` uses the returned value of a matching Cucumber step.

---

[Previous: Dynamic steps](dynamic-steps.md) · [Documentation home](README.md) · [Next: Shared configuration files](config-files-and-resource-mapping.md)
