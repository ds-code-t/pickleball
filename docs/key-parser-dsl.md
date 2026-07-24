# Keyboard Expressions

> **Working feature example:** [`keyboard.feature`](../maven-consumer-project/src/test/resources/features/keyboard.feature) demonstrates modifier expressions, named keys, and keyboard input against a real page.

Keyboard expressions describe exact key timing for shortcuts and multi-key interactions. Use them with the `press` action:

```gherkin
* , press "CONTROL[A]" in the 1st Textbox
```

## Operators

| Syntax | Meaning |
|---|---|
| space | finish one key or group, then begin the next |
| `+` | press keys together |
| `[ ... ]` | hold the key or group before `[` while running the inner sequence |

## Sequential keys

```text
A B C
```

Presses and releases A, then B, then C.

## Simultaneous keys

```text
A+B
```

Presses A and B together, then releases them in reverse order.

## Held keys

```text
CONTROL[A]
CONTROL[A B]
CONTROL[A+B]
CONTROL+SHIFT[A+B C+B]
```

Examples:

- `CONTROL[A]` holds Control while pressing A.
- `CONTROL[A B]` holds Control across sequential A and B presses.
- `CONTROL[A+B]` holds Control while A and B are pressed together.
- `CONTROL+SHIFT[A+B C+B]` holds Control and Shift across two simultaneous groups.

Held groups can be nested:

```text
CONTROL+SHIFT[A B ALT[A B]]
```

## Key names and literal characters

Recognized names include:

```text
CONTROL SHIFT ALT ENTER TAB BACK_SPACE DELETE ESCAPE
ARROW_LEFT ARROW_RIGHT ARROW_UP ARROW_DOWN HOME END SPACE
```

A single literal character can be used directly:

```text
A B 1 . / -
```

A multi-character token is interpreted as one key name, not as text. Write:

```text
h e l l o
```

rather than:

```text
hello
```

## Common shortcuts

```text
CONTROL[A]
CONTROL[A] BACK_SPACE
CONTROL+SHIFT[A]
SHIFT[A B C]
```

## Working examples

- [Keyboard feature](../maven-consumer-project/src/test/resources/features/keyboard.feature)
- [Keyboard test page](../maven-consumer-project/src/test/resources/site/keyboard.html)

[Previous: Date and Time Utilities](date-time-utilities.md) · [Documentation home](README.md) · [Next: Execution Configuration](configuration.md)
