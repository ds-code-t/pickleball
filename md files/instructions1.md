# 🏓 Pickleball Framework

A turnkey, dynamic testing framework built on a heavily enhanced version
of Cucumber for Java 21.

Pickleball is designed to simplify consumer project setup while enabling
powerful, expressive dynamic test steps using natural English syntax.

------------------------------------------------------------------------

## 📦 Installation

Add Pickleball as a test dependency:

``` xml
<dependencies>
    <dependency>
        <groupId>tools.dscode</groupId>
        <artifactId>pickleball</artifactId>
        <version>2.0.5</version>
        <scope>test</scope>
    </dependency>
</dependencies>
```

All required dependencies are bundled within Pickleball. No additional
setup is required.

------------------------------------------------------------------------

# 🚀 Core Concept: Dynamic Steps

Pickleball extends Cucumber with **Dynamic Steps**.

A step becomes dynamic when it begins with:

``` gherkin
Then ,
```

Everything after the comma is parsed and executed by Pickleball's
dynamic engine.

### Phrase Delimiters

Dynamic phrases are separated by:

    ,  ;  .  :  ?

-   `,` -- General separation
-   `:` -- Indicates nesting
-   Others influence context flow

------------------------------------------------------------------------

# 🧩 Elements

Elements represent DOM entities, browser-level objects, or special
system objects.

## Element Base Rules

An Element Base:

-   Consists of one or more capitalized words
-   Each word must have more than one letter

Examples:

-   Button
-   Link
-   Checkbox
-   Textbox
-   Window
-   Alert

Plural forms are supported (Buttons, Checkboxes, etc.).

------------------------------------------------------------------------

## Text Matching

### Full Match

``` gherkin
"Submit" Button
"Refresh" Link
```

### Partial Match

``` gherkin
Button containing "Submit"
Dropdown starting with "State"
```

------------------------------------------------------------------------

## Quote Types

Quote       Behavior
  ----------- -----------------------------------------
`" "`       Whitespace normalized, case-sensitive
`' '`       Whitespace normalized, case-insensitive
`` ` ` ``   Exact match (minimal normalization)

Example:

``` gherkin
'comments' Textbox
Text containing `  Thank you. A confirmation email has been sent`
```

------------------------------------------------------------------------

## Modifiers

Elements may include state modifiers:

-   checked / unchecked
-   selected / unselected
-   expanded / collapsed
-   enabled / disabled
-   required / non-required
-   blank / empty

Example:

``` gherkin
checked "Agree" Checkbox
collapsed Row starting with "Account Number"
disabled 'submit' Button
```

------------------------------------------------------------------------

## Positioning

Used when multiple matches exist.

Supported forms:

-   first
-   last
-   1st, 2nd, 3rd, 4th, etc.

Example:

``` gherkin
7th "Submit" Button
last 'refresh' Link
3rd Text containing `Thank you`
```

If unspecified, the first match is selected.

------------------------------------------------------------------------

# 🔢 Values

Values are quoted strings or numeric values not tied to Element Bases.

Examples:

``` gherkin
"text example"
10
10 integer
0.232 decimal
3 minutes
5 seconds
```

------------------------------------------------------------------------

# ⚙️ Operations

## Action Keywords

Keyword        Description
  -------------- ------------------------------
click          Left click
double click   Double click
right click    Right click
hover / move   Mouse over
enter          Enter text
overwrite      Clear then enter
clear          Clear text
select         Select dropdown option
scroll         Scroll to element
wait           Wait duration or for element
save           Save value to scenario state
attach         Upload file
switch         Switch window/tab
close          Close element/window/tab
accept         Accept alert
dismiss        Dismiss alert
press          Keyboard press

Example:

``` gherkin
click the "Submit" Button
wait 3 seconds
enter "John" in "First Name" Textbox
```

------------------------------------------------------------------------

# ✅ Assertions

Assertions return Boolean values and support negation using `not`.

### Comparison

-   equals
-   starts with
-   ends with
-   contains
-   matches
-   is less than
-   is greater than

### State Checks

-   is displayed
-   is selected / unselected
-   is checked / unchecked
-   is enabled / disabled
-   is required / non-required
-   has value
-   is blank

Example:

``` gherkin
"Submit" Button is disabled
"Case Missing" Text is not displayed
```

------------------------------------------------------------------------

# 🔀 Conditional Flow

Keywords:

-   if
-   else if
-   else
-   until

Example:

``` gherkin
Then , if "Refresh" Link is displayed,
click the "Refresh" Link,
else save Link as "Displayed Link Text"
```

### Until Loop

``` gherkin
Then , until "Case Ready" Text is displayed,
wait 3 seconds,
click the "Refresh" Link
```

------------------------------------------------------------------------

# 🛑 Hard vs Soft Assertions

### Hard (Stops Scenario)

-   ensure
-   ensures

``` gherkin
Then , user ensures that "Refresh" Link is displayed
```

### Soft (Marks Failed but Continues)

-   verify
-   verifies

``` gherkin
Then , user verifies that "Agree" Checkbox is unchecked
```

------------------------------------------------------------------------

# 🔗 Chained Assertions

Use:

-   and
-   or

Example:

``` gherkin
verify Error Banner is not displayed
and 3rd Link does not contain 'failed'
```

-   `and` → all must pass
-   `or` → short-circuits on first true

------------------------------------------------------------------------

# 📍 Context Phrases

Context narrows DOM scope.

Keywords:

-   for
-   from
-   in
-   after
-   before
-   between
-   in between

Example:

``` gherkin
Then , from the "Results" Table,
click the "Case" Link
```

Nested context:

``` gherkin
Then , after the "Returned Results" Text,
in the "Results" Table,
click the "Case" Link
```

Context applies to all subsequent phrases in the same sentence.

------------------------------------------------------------------------

# 🧠 Dynamic Step Structure

    Step Keyword , [Context] [Condition] [Action/Assertion]

Core Components:

1.  Elements
2.  Values
3.  Action Keywords
4.  Assertion Keywords
5.  Conditional Flow
6.  Context Control
7.  Scenario State Management

------------------------------------------------------------------------

# 📚 Next Steps

This README provides a high-level overview of Pickleball's dynamic
capabilities.

For detailed usage examples, custom element registration, and advanced
parsing behavior, refer to the extended documentation (coming soon).

------------------------------------------------------------------------

**Pickleball** -- Natural language test automation with dynamic
execution power.
