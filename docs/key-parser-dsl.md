# Keyboard DSL

`KeyParser` converts a quoted expression into Selenium keyboard actions. Use it when a scenario requires exact key-down and key-up timing rather than ordinary text entry.

Example:

```gherkin
Then , press "CONTROL[A]" in the 1st Textbox
```

The quoted value is the keyboard expression.

At Java level, the parser can be called with an element:

```java
KeyParser.sendComplexKeys(driver, element, input);
```

or without a specific element:

```java
KeyParser.sendComplexKeys(driver, input);
```

## Syntax

| Syntax | Meaning |
|---|---|
| Space | Finish one key or group, then begin the next |
| `+` | Press keys together as one simultaneous group |
| `[ ... ]` | Hold the preceding key/group while executing the inner sequence |

## Sequential keys

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

## Simultaneous keys

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

Simultaneous groups are pressed from left to right and released from right to left.

## Modifier combinations

### Simultaneous Control and A

```text
CONTROL+A
```

Spaces around `+` are ignored, so this is also valid:

```text
CONTROL + A
```

### Hold Control while pressing A

```text
CONTROL[A]
```

For many browser shortcuts, `CONTROL+A` and `CONTROL[A]` produce the same visible result. The expressions remain distinct because the held form can describe more complex timing.

### Hold Control across several sequential keys

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

## Multiple held modifiers

```text
CONTROL+SHIFT[A+B]
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

The whole group before `[` remains held for the bracketed sequence.

## Several inner groups

```text
CONTROL+SHIFT[A+B C+B]
```

Inside the brackets:

- `A+B` is one simultaneous group;
- the space finishes that group; and
- `C+B` begins another simultaneous group.

Control and Shift remain held across both groups.

## Nested held groups

```text
CONTROL+SHIFT[A B ALT[A B]]
```

The nested `ALT[A B]` sequence runs while Control and Shift remain held.

## Supported tokens

A token may be:

1. a Selenium `Keys` enum name, such as:

   ```text
   CONTROL SHIFT ALT ENTER TAB BACK_SPACE DELETE ESCAPE
   ARROW_LEFT ARROW_RIGHT HOME END SPACE
   ```

2. a single literal character, such as:

   ```text
   A B 1 . / -
   ```

A multi-character token is interpreted as one key name, not ordinary text. Therefore:

```text
hello
```

is not equivalent to five key presses. Use:

```text
h e l l o
```

## Common expressions

Select all:

```text
CONTROL[A]
```

or:

```text
CONTROL+A
```

Select all, then delete:

```text
CONTROL[A] BACK_SPACE
```

Hold Shift while pressing several letters:

```text
SHIFT[A B C]
```

Control-plus-Shift shortcut:

```text
CONTROL+SHIFT[A]
```

## Invalid expressions

Missing closing bracket:

```text
CONTROL[
```

Missing key after `+`:

```text
A+
```

Unknown multi-character token:

```text
hello
```

An empty held sequence is normally not useful:

```text
CONTROL[]
```

## Summary

```text
space  = sequential
+      = simultaneous
[ ... ] = hold the preceding key or group
```

Representative expressions:

```text
CONTROL+A
CONTROL[A]
CONTROL[A B]
CONTROL[A+B]
CONTROL+SHIFT[A+B C+B]
```

---

[Previous: Nested steps](nested-steps.md) · [Documentation home](README.md)
