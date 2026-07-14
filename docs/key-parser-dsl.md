# Keyboard Expressions

Keyboard expressions describe exact key timing for shortcuts and multi-key interactions. Use them with the `press` action when ordinary text entry is not enough.

```gherkin
Then , press "CONTROL[A]" in the 1st Textbox
```

The quoted text is the keyboard expression.

## Symbols

| Syntax | Meaning |
|---|---|
| Space | Finish one key or group, then begin the next |
| `+` | Press keys together |
| `[ ... ]` | Hold the key or group before `[` while performing the inner sequence |

## Sequential keys

```text
A B C
```

Meaning:

```text
press A, release A
press B, release B
press C, release C
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

Keys are pressed from left to right and released from right to left.

## Common modifier patterns

### Control and A together

```text
CONTROL+A
```

Spaces around `+` are ignored:

```text
CONTROL + A
```

### Hold Control while pressing A

```text
CONTROL[A]
```

### Hold Control across several sequential keys

```text
CONTROL[A B]
```

Control remains held while A and then B are pressed and released.

### Hold Control while pressing A and B together

```text
CONTROL[A+B]
```

## Several held modifiers

```text
CONTROL+SHIFT[A+B]
```

Control and Shift remain held while A and B are pressed together.

## Several groups inside a held section

```text
CONTROL+SHIFT[A+B C+B]
```

Inside the brackets:

- `A+B` is one simultaneous group;
- the space ends that group; and
- `C+B` is the next simultaneous group.

Control and Shift remain held across both groups.

## Nested held groups

```text
CONTROL+SHIFT[A B ALT[A B]]
```

The inner Alt sequence runs while Control and Shift remain held.

## Key names and literal characters

A token may be a recognized key name:

```text
CONTROL SHIFT ALT ENTER TAB BACK_SPACE DELETE ESCAPE
ARROW_LEFT ARROW_RIGHT HOME END SPACE
```

or one literal character:

```text
A B 1 . / -
```

A multi-character token is treated as one key name rather than ordinary text. Therefore:

```text
hello
```

is not five letter presses. Write:

```text
h e l l o
```

## Common examples

Select all:

```text
CONTROL[A]
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

Unknown multi-character key name:

```text
hello
```

An empty held group is normally not useful:

```text
CONTROL[]
```

## Quick reference

```text
space    sequential groups
+        keys pressed together
[ ... ]  hold the preceding key or group
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

[Previous: Component Scenarios](component-scenarios.md) · [Documentation home](README.md) · [Next: Execution Configuration](configuration.md)
