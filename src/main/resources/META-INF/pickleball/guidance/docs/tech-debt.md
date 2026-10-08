# Tech debt

This page lists behavior that is not a feature. Do not document it as syntax.

## Unused step ceiling fields

`StepBase.stepMaxIterations` and `StepBase.stepTimeoutSeconds` are never assigned. A block `until` and an element `wait` use only the run-wide `stepRepeatMaxCount` and `stepRepeatMaxTime` values. There is no per-step ceiling. Do not read those fields as one.
