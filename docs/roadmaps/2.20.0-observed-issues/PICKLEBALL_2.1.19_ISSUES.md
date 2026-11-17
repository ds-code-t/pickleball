# Pickleball 2.1.19 — Observed Issues

_Transcribed from photos of Dan's screen on 2026-11-17. The photos are slightly blurry, so a few characters in code samples (mainly quote marks and `<placeholder>` names) are best-effort readings._

- **Version:** `tools.dscode:pickleball` 2.1.19
- **Runtime:** Java 21, Windows, runs started with Pickleball Workbench `discover`
- **Date:** 2026-11-17

Each issue below has a minimal reproduction that does not depend on any application or browser page.

| # | Issue | Severity | Confidence |
|---|---|---|---|
| 1 | Placeholders after the first phrase of an `, or` condition are not resolved | High | High (reproduced 4 ways) |
| 2 | `number is equal to` compares text when the operands are quoted | Medium | High (reproduced) |
| 3 | Workbench can run a stale copy of a classpath-resolved (called) feature file | Medium | Medium (seen once, not isolated) |
| 4 | A second Workbench run on the warm JVM drops a run that is in progress | Low | Medium (seen once) |

---

## 1. Placeholders after the first phrase of an `, or` condition are not resolved

### Summary
In a condition that chains phrases with `, or`, Pickleball fills in `<placeholder>` values only for the first phrase. Later phrases are evaluated against the raw placeholder text. When the first phrase is false and only a later phrase would be true, the condition is wrongly false and the branch is skipped.

The bug does not depend on where the value comes from. It happens with a value saved by `I save ... as ...`, and with a parameter passed to a component through `RUN SCENARIOS`. It also does not depend on quoting or on the `number` qualifier.

### Reproduction
```gherkin
Feature: or-chain placeholder resolution

  @OrChainRepro
  Scenario: second phrase of an or-chain uses a placeholder
    Then , I save "2" as "N"

    # Expected: taken ("2" is equal to "2"). Actual: skipped.
    * IF: "2" is less than "<N>", or "2" is equal to "<N>":
    : Then , I save "yes" as "A"
    : Then , I continue.

    # Expected: taken. Actual: skipped.
    * IF: "x" is equal to "y", or "<N>" is equal to "2":
    : Then , I save "yes" as "B"
    : Then , I continue.

    # Control: no placeholders. Taken as expected.
    * IF: "a" is equal to "b", or "c" is equal to "c":
    : Then , I save "yes" as "C"
    : Then , I continue.

    # Control: placeholder in a single phrase. Taken as expected.
    * IF: "2" is less than or equal to "<N>":
    : Then , I save "yes" as "D"
    : Then , I continue.

    Then , I verify "<A>" is equal to "yes"
    Then , I verify "<B>" is equal to "yes"
    Then , I verify "<C>" is equal to "yes"
    Then , I verify "<D>" is equal to "yes"
```

### Expected
`A`, `B`, `C`, and `D` are all saved, and all four verifications pass.

### Actual
`C` and `D` pass. `A` and `B` fail with `checking equality between: '<A>' and 'yes'`, because their branches never ran.

### Evidence from the diagnostic log
The first phrase is logged with its resolved values. The second phrase is logged only with the raw template, and no `Running Phrase:` line shows a resolved value for it:

```
Running Phrase:   if "2" is less than "2"
if "2" is less than "<N>", : Executing Assertion LESS_THAN
or "2" is equal to "<N>", : Executing Assertion EQUAL
```

In the control case with literal values, the same `, or` form falls through to the second phrase and the branch runs. So the `or` operator works. The defect is placeholder resolution in later phrases.

### Variants tested

| Condition form | Placeholder source | Result |
|---|---|---|
| `"2" number is less than "<N>", or "2" number is equal to "<N>"` | `RUN SCENARIOS` parameter | Wrongly skipped |
| `2 is less than <N>, or 2 is equal to <N>` (unquoted) | `RUN SCENARIOS` parameter | Wrongly skipped |
| `"2" is less than "<N>", or "2" is equal to "<N>"` | saved value | Wrongly skipped |
| `"x" is equal to "y", or "<N>" is equal to "2"` | saved value | Wrongly skipped |
| `"2" number is equal to "<N>", or "2" number is less than "<N>"` | `RUN SCENARIOS` parameter | Taken (first phrase is true, so the second is not needed) |
| `"a" is equal to "b", or "c" is equal to "c"` | none | Taken |
| `"2" number is less than or equal to "<N>"` (single phrase) | `RUN SCENARIOS` parameter | Taken |

### Impact
Any `, or` condition whose deciding phrase is not the first one, and which uses a placeholder, gives a wrong result without any error. Tests can skip setup steps silently and then fail much later, far from the cause.

### Workaround
Avoid placeholders after the first phrase of an `, or` chain:
- Use a single comparison where one exists, such as `is less than or equal to` instead of `is less than ..., or ... is equal to`.
- Or split the chain into separate `IF:` blocks.

---

## 2. `number is equal to` compares text when the operands are quoted

### Summary
With quoted operands, `"A" number is equal to "B"` compares the two strings exactly. The `number` qualifier has no effect. Other numeric comparisons, and unquoted operands, do compare as numbers. They also strip a leading currency sign.

### Reproduction
```gherkin
Feature: numeric equality

  @NumericEqualityRepro
  Scenario: number is equal to
    Then , I verify "1" number is equal to "1.00"
    Then , I verify "1" number is equal to "$1.00"
    Then , I verify "1.5" number is equal to "1.50"
    Then , I verify 1 number is equal to "$1.00"
    Then , I verify 1 is equal to "1.00"
    Then , I verify "1" number is less than or equal to "1.00"
    Then , I verify "1" number is greater than or equal to "$1.00"
```

### Observed results

| Step | Log line | Result |
|---|---|---|
| `"1" number is equal to "1.00"` | `checking equality between: '1' and '1.00'` | Fail |
| `"1" number is equal to "$1.00"` | `checking equality between: '1' and '$1.00'` | Fail |
| `"1.5" number is equal to "1.50"` | `checking equality between: '1.5' and '1.50'` | Fail |
| `1 number is equal to "$1.00"` | `checking numeric equality between: '1' and '1.00'` | Pass |
| `1 is equal to "1.00"` | `checking numeric equality between: '1' and '1.00'` | Pass |
| `"1" number is less than or equal to "1.00"` | `checking numeric less-than-or-equal-to between: '1' and '1.00'` | Pass |
| `"1" number is greater than or equal to "$1.00"` | `checking numeric greater-than-or-equal-to between: '1' and '1.00'` | Pass |

Also, `"abc" number is equal to "abc"` passes. A `number` comparison does not check that its operands are numbers.

### Expected
`number is equal to` should compare numerically, as `number is less than or equal to` already does. At minimum, the behavior should be documented. The docs list the comparison operators but do not describe the `number` qualifier.

### Impact
A check of a formatted value such as `$1.00` or `1.50` against a plain input such as `1` or `1.5` fails with quoted operands, even though the values are equal.

### Workaround
Leave the expected value unquoted (`I ensure <Amount> number is equal to "Amount" Field`). This works only when the value is never blank.

---

## 3. Workbench can run a stale copy of a classpath-resolved (called) feature file

### Summary
After an edit to a feature file that holds component scenarios called through `RUN SCENARIOS`, a following `discover` run used the old text. The run logged the step from `classpath:features/...`, and `target/test-classes` still held the old copy. The top-level feature file, logged as `file:///...`, did pick up edits. After a manual `mvnw process-test-resources`, the next run used the new text.

### Steps
1. Run a scenario that calls a component in a second feature file.
2. Edit a step in that second file.
3. Run `discover` again with a new run id.
4. The diagnostic log still shows the old step text for the component. Its `source.uri` is `classpath:...`.

### Expected
The Workbench docs say input fingerprints decide whether to skip, sync resources only, or do a full compile. An edited resource should trigger a resources-only sync before the run.

### Confidence
Seen once. I have not isolated whether it depends on timing, the warm JVM, or the edit tool. This needs a clean reproduction.

### Workaround
Run `mvnw -q -B process-test-resources` before `discover` after editing any called feature file.

---

## 4. A second Workbench run on the warm JVM drops a run that is in progress

### Summary
While a long `discover` run was in progress, a second, short `discover` (different run id) was started from another shell. The first run ended with:

```
error: warm run was accepted and then the connection dropped
summary status=LAUNCH_ERROR scenarios=0 steps=0
```

The second run finished normally.

### Expected
The docs say each agent uses its own run id and that two isolates do not share a session file, which suggests separate runs can coexist. If the warm JVM serves one run at a time, a second request should queue or be refused. It should not drop the run already accepted.

### Confidence
Seen once.

### Workaround
Do not start a second Workbench run until the first one finishes.
