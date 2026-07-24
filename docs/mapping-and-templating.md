# Mapping and Templating

> **Working feature example:** [`mapping-and-resources.feature`](../maven-consumer-project/src/test/resources/features/mapping-and-resources.feature) demonstrates Scenario Outline templates, shared configuration values, and on-demand resource mapping.

Templates allow feature files to reuse Scenario Outline values, saved runtime values, configuration data, loaded resources, and service responses.

All template forms use angle brackets:

```text
<value>
```

## Scenario Outline values

Cucumber resolves `Examples` values first:

```gherkin
Scenario Outline: Submit a customer
  * , enter "<name>" in the "Customer Name" Textbox
  * , select "<tier>" in the "Customer Tier" Dropdown

Examples:
  | name | tier     |
  | Ava  | Premium  |
  | Ben  | Standard |
```

## Runtime values

```gherkin
* , save "Ava" as "customerName"
* print "Customer: <customerName>"
```

Runtime values may also come from component tables, shared resource files, loaded files, service calls, or custom steps.

## Nested paths

Structured values are queried like JSON:

```text
<customer.name>
<customer.address.city>
<orders[0].id>
<orders #2.items #1.sku>
```

Square-bracket indexes are normal zero-based JSONata indexes. `#` selectors are Pickleball's one-based-friendly selectors.

## Top-level collection behavior

Ordinary top-level properties are treated as histories or collections. A read without explicit array syntax selects the last entry:

| Query | Meaning |
|---|---|
| `REQUEST` | last entry in `REQUEST` |
| `REQUEST.endpoint` | `endpoint` on the last `REQUEST` object |
| `REQUEST[]` | complete `REQUEST` array |
| `REQUEST[*]` | complete `REQUEST` array |
| `REQUEST[].endpoint` | the last object in `REQUEST` that has `endpoint` |
| `REQUEST[*].endpoint` | an array of all existing `endpoint` values |

The same rules apply to nested arrays:

```text
<orders[].products[*].id>
```

A top-level name beginning with `_` is a singleton and does not receive automatic last-entry behavior:

```text
<_CONFIG.endpoint>
```

## `#` selectors

Pickleball converts each `#` position by subtracting one:

| Pickleball selector | Effective JSONata selector |
|---|---|
| `#1` | `[0]` |
| `#2` | `[1]` |
| `#0` | `[-1]` |
| `#-1` | `[-2]` |
| `#first` | `[0]` |
| `#last` | `[-1]` |
| `#1-3` | `[0..2]` |
| `#1,3` | `[0,2]` |

Examples:

```text
<orders #first.id>
<orders #last.id>
<orders #1-3.id>
<orders #1,3.id>
```

`..` remains valid native JSONata range syntax. The `#` conversion is not applied inside quoted strings, regular expressions, comments, or backticked property names.

## Property names with spaces or dashes

Simple path properties containing spaces are backtick-wrapped automatically. A dash is treated as part of a property name when it is surrounded by non-whitespace characters:

```text
<Customer Requests.Endpoint Name>
<order-items.product-id>
```

These normalize to JSONata backtick property names. For complicated predicates or functions, write explicit backticks:

```jsonata
`Customer Requests`[`Customer Name` = "Ava"].`Endpoint Name`
```

## Writable NodeMap paths

Pickleball uses related path rules when a step or Java call stores data.

| Write path | Effect |
|---|---|
| `REQUEST` | append a new value to the top-level `REQUEST` array |
| `REQUEST.method` | set `method` on the last request; create the object if needed |
| `REQUEST[].id` | append a new object when needed and set its `id` |
| `REQUEST[*].status` | update `status` on every existing request object |
| `orders[*].products[*].status` | update existing products in existing orders |
| `_CONFIG.endpoint` | set a singleton value without top-level array behavior |

Wildcards update existing arrays only. They do not create a missing array or replace an existing scalar with an array.

A JSONata selector can choose writable attached objects:

```java
nodeMap.put("orders[status = \"active\"].result.code", "OK");
```

Computed scalars and newly constructed JSONata objects are not writable targets.

## Return values from Cucumber steps

A template beginning with `$` executes a Cucumber step that returns a value:

```gherkin
* , save "<$DateTime:now>" as "currentDateTime"
* IF: `<$string:"Assa" contains: "ss">`:
  : Then , save "matched" as "result"
```

Use this only with steps designed to return a value.

## Working examples

- [Scenario Outline values and shared resources](../maven-consumer-project/src/test/resources/features/mapping-and-resources.feature)
- [Shared YAML test data](../maven-consumer-project/src/test/resources/configs/TEST_DATA.yaml)
- [On-demand customer data](../maven-consumer-project/src/test/resources/files/customers.yaml)
- [Service responses mapped into the caller](../maven-consumer-project/src/test/resources/features/service-call-execution.feature)

[Previous: Dynamic Steps](dynamic-steps.md) · [Documentation home](README.md) · [Next: Configuration Files and Resource Mapping](config-files-and-resource-mapping.md)
