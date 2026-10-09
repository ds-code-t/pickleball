# Tech debt

This page lists behavior that is not a feature. Do not document it as syntax.

## Unused step ceiling fields

`StepBase.stepMaxIterations` and `StepBase.stepTimeoutSeconds` are never assigned. A block `until` and an element `wait` use only the run-wide `stepRepeatMaxCount` and `stepRepeatMaxTime` values. There is no per-step ceiling. Do not read those fields as one.

## Accepted steps that do nothing

The parser and glue accept these steps. Their methods are empty. They do not change which later steps run. Do not document them or use them as features until they are implemented:

- `RETRY:`
- `IGNORE FAILURES`
- `LOG FAILURES BUT CONTINUE SCENARIO`
- `RUN IF SCENARIO FINISHED`

`ALWAYS RUN` and `RUN IF SCENARIO PASSING` also accept an optional ` AND IGNORE FAILURES` suffix. `RUN IF SCENARIO PASSING` also accepts an optional ` AND SCENARIO FINISHED` suffix. The runner stores the whole line. That text is not the bare flag, so the suffix does not apply `ALWAYS RUN` or `RUN IF SCENARIO PASSING` and does not change failure handling.

## Unsupported context words

These words do not resolve and are not context words: `between`, `inside`, `within`, `of`, `on`, `near`, `next to`, `following`, and `preceding`. `between` is under investigation.

## Unsupported condition word

`exists` is not a condition. Use `is present` or `is displayed` for elements, or a bare reference for values. Do not document `exists` as syntax.

## Inline until

A mid-line or comma `until`, and a period-ending `until`, do not loop. They act as a one-time `if` or do nothing. Only a block `until` ending in `:` or `?` with nested steps loops. `runUntilOperation` is not that runtime path.
