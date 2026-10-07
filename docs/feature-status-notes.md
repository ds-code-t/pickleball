# Feature Status and Documentation Backlog

This maintainer-only file is intentionally not linked from the public documentation. Items belong here until their behavior is fully tested and suitable for feature authors.

## Context wording awaiting verification

### `between`

Not a public context word. `resolveContextXPathy` does not apply it, and the phrase regex does not capture it. Confirm the business-language meaning and the two-anchor form before adding it.

### `in between`

Confirm whether this should become a supported alias of a future `between`, and how it behaves with one or two surrounding elements.

## Action wording awaiting verification

### `run step`

Confirm the public syntax, return behavior, error handling, and reporting before documenting it as a normal action.

### `start` and `end`

Confirm whether these should be standalone assertions or only parts of `starts with` and `ends with`.

### `switch`

Document tested forms for windows, tabs, frames, alerts, and any other supported targets.

### `close`

Document which browser targets can be closed and what context remains active afterward.

`hover` is an alias of `move`. `dragAndDrop` takes the source element and then the target element. `create and attach` creates a temporary file and uploads it. The old `TAB` action was removed; press `"TAB"` remains the keyboard token. A prompt send-and-accept phrase does not exist; use `BrowserAlerts.sendKeys` from Java and then accept.

## Maintenance checklist

- [ ] Add automated coverage for each proposed public phrase.
- [ ] Confirm feature-file syntax and punctuation.
- [ ] Confirm inheritance behavior when used in a phrase chain.
- [ ] Confirm report and log output.
- [ ] Add a business-readable example before moving an item into public documentation.
