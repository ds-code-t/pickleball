# Feature Status and Documentation Backlog

This maintainer-only file is intentionally not linked from the public documentation. Items belong here until their behavior is fully tested and suitable for feature authors.

## Context wording awaiting verification

### `between`

Confirm the intended business-language meaning and add tested examples before listing it as a public context phrase.

### `in between`

Confirm whether this is a supported alias of `between` and how it behaves with one or two surrounding elements.

## Action wording awaiting verification

### `create and attach`

Confirm the supported sentence forms, required values, and file behavior before documenting it as a normal action.

### `run step`

Confirm the public syntax, return behavior, error handling, and reporting before documenting it as a normal action.

### `hover`

`move` is the documented pointer-over wording. Confirm whether `hover` should become a supported alias.

### `dragAndDrop`

Confirm the intended public spelling, source and target phrasing, and browser behavior.

## Operations needing focused tests

### `tab`

Confirm public phrase recognition and verify that the resulting key behavior is the Tab key.

### `start` and `end`

Confirm whether these should be standalone assertions or only parts of `starts with` and `ends with`.

### `switch`

Document tested forms for windows, tabs, frames, alerts, and any other supported targets.

### `close`

Document which browser targets can be closed and what context remains active afterward.

## Maintenance checklist

- [ ] Add automated coverage for each proposed public phrase.
- [ ] Confirm feature-file syntax and punctuation.
- [ ] Confirm inheritance behavior when used in a phrase chain.
- [ ] Confirm report and log output.
- [ ] Add a business-readable example before moving an item into public documentation.
