# KeyParser DSL Guide

`KeyParser` converts a quoted DSL string into Selenium keyboard actions.

It is intended for Cucumber steps such as:

```gherkin
Then , press "CONTROL[A]" in the 1st Textbox;
```

The quoted value is the key-expression DSL. The step definition can pass that string directly to:

```java
KeyParser.sendComplexKeys(driver, element, input);
```

or, when no specific element is needed:

```java
KeyParser.sendComplexKeys(driver, input);
```

---

## Core idea

The DSL describes **keyboard timing**, not just typed text.

There are three main syntax rules:

| Syntax | Meaning |
|---|---|
| Space | Finish the previous key action/group, then start the next one. |
| `+` | Press keys together as one simultaneous group. |
| `[ ... ]` | Hold the key/group before the bracket while running the inner sequence. |

---

## Basic examples

### Press one key

```text
A
```

Meaning:

```text
press A
release A
```

---

### Press keys sequentially

```text
A B C
```

Meaning:

```text
press A
release A
press B
release B
press C
release C
```

A space means the previous key or key group is finished before the next one starts.

---

### Press keys together

```text
A+B
```

Meaning:

```text
press A
press B
release B
release A
```

The `+` operator makes the keys cumulative in the same simultaneous group.

---

## Modifier examples

### Control plus A at the same time

```text
CONTROL+A
```

Also valid:

```text
CONTROL + A
```

Meaning:

```text
press CONTROL
press A
release A
release CONTROL
```

This is a simultaneous key combination.

---

### Hold Control, then press A

```text
CONTROL[A]
```

Meaning:

```text
press CONTROL
press A
release A
release CONTROL
```

This looks similar to `CONTROL+A`, but the DSL meaning is different:

- `CONTROL+A` means Control and A are part of the same simultaneous group.
- `CONTROL[A]` means Control is held as an outer state, and A is pressed inside that held state.

For many browser shortcuts, these may behave the same. The parser keeps them distinct because some keyboard scenarios require exact press/release timing.

---

### Hold Control while pressing A, then B

```text
CONTROL[A B]
```

Meaning:

```text
press CONTROL
press A
release A
press B
release B
release CONTROL
```

Control remains held while A and B are pressed one after the other.

---

### Hold Control while pressing A and B together

```text
CONTROL[A+B]
```

Meaning:

```text
press CONTROL
press A
press B
release B
release A
release CONTROL
```

A and B are pressed as one inner simultaneous group while Control is held.

---

## Multiple held modifiers

### Hold Control and Shift, then press A and B together

```text
CONTROL+SHIFT[A+B]
```

Also valid:

```text
CONTROL + SHIFT [A+B]
```

Meaning:

```text
press CONTROL
press SHIFT
press A
press B
release B
release A
release SHIFT
release CONTROL
```

The group before `[` is held for the full bracketed sequence.

---

### Hold Control and Shift, then run multiple inner groups

```text
CONTROL+SHIFT[A+B C+B]
```

Meaning:

```text
press CONTROL
press SHIFT
press A
press B
release B
release A
press C
press B
release B
release C
release SHIFT
release CONTROL
```

Inside the brackets:

- `A+B` is one simultaneous group.
- The space ends that group.
- `C+B` is another simultaneous group.

---

## Nested held groups

```text
CONTROL+SHIFT[A B ALT[A B]]
```

Meaning:

```text
press CONTROL
press SHIFT
press A
release A
press B
release B
press ALT
press A
release A
press B
release B
release ALT
release SHIFT
release CONTROL
```

The nested `ALT[A B]` block runs while Control and Shift are still held.

---

## Cucumber usage

Example Gherkin:

```gherkin
Then , press "CONTROL[A]" in the 1st Textbox;
Then , press "CONTROL+SHIFT[A+B]" in the 1st Textbox;
Then , press "CONTROL[A B ALT[A B]]" in the 1st Textbox;
```

Example step definition shape:

```java
@Then("^, press \"([^\"]*)\" in the (.*) Textbox;$")
public void pressKeysInTextbox(String keyDsl, String textboxName) {
    WebElement element = findTextbox(textboxName);
    KeyParser.sendComplexKeys(driver, element, keyDsl);
}
```

The exact element lookup can be whatever your framework already uses.

---

## Supported key tokens

A token can be either:

1. A Selenium `Keys` enum name, such as:

```text
CONTROL
SHIFT
ALT
ENTER
TAB
BACK_SPACE
DELETE
ESCAPE
ARROW_LEFT
ARROW_RIGHT
HOME
END
SPACE
```

2. A single literal character, such as:

```text
A
B
1
.
/
-
```

Multi-character literal text is not treated as normal typing text.

For example:

```text
hello
```

is not five key presses. It is one token named `hello`, which is invalid unless a matching `Keys` enum exists.

Use:

```text
h e l l o
```

for separate key presses.

---

## Important notes

### Spaces separate terms

```text
A B
```

means A is completed before B starts.

```text
A+B
```

means A and B are pressed together.

Spaces around `+` are ignored, so these are equivalent:

```text
CONTROL+A
CONTROL + A
CONTROL+ A
CONTROL +A
```

---

### Brackets hold the previous group

```text
CONTROL[A B]
```

means Control stays down while the inner sequence runs.

The bracketed sequence can contain normal terms, simultaneous groups, and nested held groups.

---

### Release order

For simultaneous groups, the parser uses stack-style ordering:

```text
press left-to-right
release right-to-left
```

So:

```text
CONTROL+SHIFT+A
```

means:

```text
press CONTROL
press SHIFT
press A
release A
release SHIFT
release CONTROL
```

---

## Common shortcuts

### Select all

```text
CONTROL[A]
```

or:

```text
CONTROL+A
```

### Select all, then delete

```text
CONTROL[A] BACK_SPACE
```

### Hold Shift and press several letters

```text
SHIFT[A B C]
```

### Control plus Shift shortcut

```text
CONTROL+SHIFT[A]
```

---

## Syntax errors

These should be treated as invalid DSL:

```text
CONTROL[
```

Missing closing bracket.

```text
CONTROL[]
```

Empty bracket sequence. This may be allowed as a no-op only if the implementation chooses to allow it, but it is usually not useful.

```text
A+
```

Missing key after `+`.

```text
hello
```

Unknown multi-character token.

---

## Summary

Use this DSL when you need precise Selenium keyboard behavior:

```text
SPACE = sequential
+     = simultaneous
[]    = held outer key/group
```

The most important examples are:

```text
CONTROL+A                // simultaneous Control and A
CONTROL[A]               // hold Control, press/release A, release Control
CONTROL[A B]             // hold Control across A, then B
CONTROL[A+B]             // hold Control while A and B are pressed together
CONTROL+SHIFT[A+B C+B]   // hold Control+Shift across multiple inner groups
```
