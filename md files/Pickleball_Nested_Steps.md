# 🔁 Nested Steps & Conditional Flow

Pickleball supports hierarchical **Step Nesting**, allowing dynamic
steps to execute in structured conditional trees similar to traditional
programming languages.

Nesting enables:

-   Conditional gating of descendant steps
-   DOM context inheritance
-   Structured `if / else if / else` branching
-   Scoped execution trees
-   Logical grouping of actions

------------------------------------------------------------------------

# 🧱 Nesting Syntax

A step becomes nested when it begins with one or more colon (`:`)
characters **before the step keyword**.

Each colon represents one nesting level.

Example:

``` gherkin
Then , parent step:
: Then , child step
:: Then , grandchild step
```

Each additional colon increases nesting depth.

------------------------------------------------------------------------

## Important Rule About `:`

A colon only signifies nesting when it appears **before the step
keyword**.

This is nesting:

``` gherkin
: Then , click the "Submit" Button
```

This is not nesting:

``` gherkin
Then , click the "Submit" Button:
```

A colon at the end of a step controls context inheritance, not nesting
(explained below).

------------------------------------------------------------------------

# 🌳 Execution Tree Model

Every scenario builds an execution tree:

-   Top-level steps
-   Child steps
-   Descendant steps

A step will attempt execution only if:

1.  It is top-level\
2.  OR all ancestor steps have allowed execution

If any ancestor step passes a false conditional state downward, the
entire subtree under that step is skipped.

------------------------------------------------------------------------

# 🔐 Conditional Gating with `:`

When a step ends with a colon (`:`), it passes:

-   Conditional Boolean result\
-   HTML / DOM context

to all child and descendant steps.

Example:

``` gherkin
Then , if the "Submit" Button is enabled:
: Then , click the "Submit" Button
```

If the button is disabled:

-   The child step is skipped.

If enabled:

-   The child executes.

------------------------------------------------------------------------

# 🔄 Equivalent Nesting Structures

These two examples perform identical logic.

## Example 1

``` gherkin
Then , if the "Submit" Button is enabled, and "Error" Banner is not displayed:
: Then , click the "Submit" Button
: And , wait 5 seconds
```

## Example 2

``` gherkin
Then , if the "Submit" Button is enabled:
: And , if the "Error" Banner is not displayed:
:: Then , click the "Submit" Button
:: And , wait 5 seconds
```

Example 2 separates the chained condition into structured nested steps.

------------------------------------------------------------------------

# ⚖️ Branching with `else` and `else if`

Branching works similarly to structured programming languages.

Rules:

-   `else if` and `else` only apply to steps at the same nesting level
-   They depend on the immediately preceding sibling step
-   They execute only if:
    -   The sibling step executed
    -   It was conditional
    -   It resolved to false

------------------------------------------------------------------------

## Example 3 -- if / else

``` gherkin
Then , if the "Submit" Button is enabled:
: Then , if the "Error" Banner is displayed:
:: Then , save "Error" Banner as "error text"
: Then , else click the "Submit" Button
```

Execution logic:

-   The third step executes only if:
    -   The first step is true
    -   The second step is true
-   The fourth step executes only if:
    -   The first step is true
    -   The second step is false

------------------------------------------------------------------------

## Example 4 -- if / else if / else

``` gherkin
Then , if the "Submit" Button is displayed:
: Then , if the "Error" Banner is displayed:
:: Then , save "Error" Banner as "error text"
: Then , else if "Submit" Button is enabled:
:: Then , click the "Submit" Button
: Then , else click the "Refresh" Link
```

Only one branch at that nesting level executes.

Execution order:

1.  First `if`
2.  `else if`
3.  `else`

------------------------------------------------------------------------

# 🧩 Execution Requirements

A step attempts execution only if:

### 1. Ancestor Conditions Allow It

No ancestor step has passed a false conditional state using `:` or `?`.

### 2. Branching Rules Allow It

If a step begins with `else` or `else if`, then:

-   Its immediately preceding sibling must:
    -   Be a conditional step
    -   Have executed
    -   Have resolved to false

If any earlier sibling at the same nesting level resolved to true, the
step is skipped.

------------------------------------------------------------------------

# ❓ Difference Between `:` and `?`

Both pass conditional state.

Only `:` passes DOM context.

  Ending   Passes Conditional State   Passes DOM Context
  -------- -------------------------- --------------------
  `:`      Yes                        Yes
  `?`      Yes                        No

------------------------------------------------------------------------

## Example 5 -- Using `:`

``` gherkin
Then , in the "Accounts" Section, if the "Case Found" Text is displayed:
: Then , click the "Submit" Button
```

Behavior:

-   DOM search scope is set to "Accounts" Section
-   Conditional state is passed
-   Child step searches within that section

------------------------------------------------------------------------

## Example 6 -- Using `?`

``` gherkin
Then , in the "Accounts" Section, if the "Case Found" Text is displayed?
: Then , click the "Submit" Button
```

Behavior:

-   Conditional state is passed
-   DOM scope is NOT passed
-   Child step searches entire DOM

------------------------------------------------------------------------

# ✨ Implied Conditional with `?`

Using `?` allows omission of the `if` keyword.

These are equivalent:

``` gherkin
Then , if the "Case Found" Text is displayed?
```

``` gherkin
Then , the "Case Found" Text is displayed?
```

This is useful for short checks:

``` gherkin
Then , "Case Found" Text is displayed?
```

------------------------------------------------------------------------

# 🧠 Default Nested Inheritance

By default:

-   Nested steps DO NOT inherit conditional state
-   Nested steps DO NOT inherit DOM context
-   Nested steps DO inherit scenario state storage

Conditional and DOM inheritance must be explicitly passed using `:` or
`?`.

------------------------------------------------------------------------

# 🔄 Nesting Flexibility

You may:

-   Nest to any depth
-   Combine with chained assertions
-   Use `else if` chains at any level
-   Mix `:` and `?` endings

However:

Branching logic always applies only to steps at the same nesting level.

------------------------------------------------------------------------

**Pickleball** --- Structured conditional logic through dynamic
natural-language test steps.
