# Diagnostic Lineage and Metadata

This guide defines how Pickleball diagnostic lineage fields differ from execution RunVars, profile controls, and derived evidence.

> Lineage metadata explains why runs are related. It does not control execution and it is not proof that a configuration or source change occurred.

## Four categories

### 1. Lineage metadata

```text
pkb_investigation_id
pkb_run_purpose
pkb_parent_run_id
pkb_baseline_run_id
pkb_changed_variables
```

These are metadata, not RunVars. Pickleball excludes them from `pkb_run_profile`, `runProfileFingerprint`, and the execution configuration hash.

### 2. Execution/evidence RunVars

Examples:

```text
pkb_reportingmode
pkb_reportretention
pkb_diagnostic_output
pkb_platformlog
pkb_gitsnapshot
pkb_loglevel
pkb_browser
pkb_tags
pkb_parallel
pkb_configpath
```

These belong in the effective RunVar set. If intentionally changed during a controlled rerun, their canonical `pkb_*` names may be listed in `pkb_changed_variables`.

### 3. Profile/control properties

These select or represent the final RunVar set but are not themselves RunVars:

```text
pkb_profile
pkb_profile_<name>
pkb_runvars
pkb_runvars.<pkb_var>
pkb_run_profile
pkb_options
```

`pkb_runvars` is the controlled-run input. `pkb_run_profile` is canonical final serialized output; external direct `pkb_run_profile` input is rejected.

Do not list control names in `pkb_changed_variables`. If `pkb_runvars` changes the browser, declare `pkb_browser`, not `pkb_runvars`.

### 4. Derived evidence

Read-only examples include:

```text
runId
runProfile
runProfileFingerprint
directRunProfile
configurationHash
environmentHash
sourceProvenanceHash
selectionFingerprint
sourceFingerprint
dependencyFingerprint
failureSignature
failureSiteKey
nativeCapabilitiesObserved
```

Use derived evidence to verify what actually happened. Do not turn these fields into ad hoc `pkb_*` inputs.

## Lineage fields

| Property | Meaning | Recommended use | Do not use for |
|---|---|---|---|
| `pkb_investigation_id` | Stable grouping label | Reuse across related reruns | configuration/source encoding |
| `pkb_run_purpose` | Reason the specific run exists | State hypothesis/validation goal | proof that a change occurred |
| `pkb_parent_run_id` | Immediate predecessor | Point to the run that motivated this rerun | permanent baseline |
| `pkb_baseline_run_id` | Stable comparison anchor | Keep the original control/failure as anchor | automatic previous-run selection |
| `pkb_changed_variables` | RunVar names intentionally changed | Canonical names such as `pkb_browser` | source files, commits, data, reasons, controls, derived fields |

All fields are optional. Omit a field when it adds no accurate information.

## `pkb_changed_variables` means RunVars only

Good:

```text
pkb_changed_variables=pkb_browser
pkb_changed_variables=pkb_tags
pkb_changed_variables=pkb_browser,pkb_tags
pkb_changed_variables=pkb_reportretention
```

Wrong:

```text
pkb_changed_variables=source:navigation.feature
pkb_changed_variables=src/test/resources/features/navigation.feature
pkb_changed_variables=git-commit
pkb_changed_variables=test-data
pkb_changed_variables=pkb_runvars
pkb_changed_variables=pkb_run_profile
pkb_changed_variables=pkb_profile
```

If a rerun changes only source, Gherkin, mappings, test data, or application code while preserving the same execution RunVars, omit `pkb_changed_variables`. Put the reason in `pkb_run_purpose` and use source provenance to determine what source actually changed.

Example source-fix validation:

```bash
mvn test \
  -Dpkb_runvars="<retained runProfile>" \
  -Dpkb_investigation_id="diag-navigation" \
  -Dpkb_parent_run_id="<failed-run-id>" \
  -Dpkb_baseline_run_id="<original-failed-run-id>" \
  -Dpkb_run_purpose="verify-source-fix"
```

There is intentionally no `pkb_changed_variables` assignment.

## Controlled rerun starting contract

Start from the selected run's retained `runProfile`. That string is the canonical final RunVar set from the earlier run. Replay it through `pkb_runvars`, not by manually reconstructing defaults/profiles/property files.

```text
retained runProfile
        ↓
pkb_runvars input
        ↓
intentional edits only
        ↓
new canonical runProfile
```

Expanded `pkb_runvars.<pkb_var>` members may be used instead of compact input. Do not mix compact and expanded forms.

Preserve blank assignments. For example, `pkb_features=` records intentional suppression of inherited project wiring and must survive replay.

A partial `pkb_runvars` input inherits only missing project execution-context RunVars: `pkb_glue`, `pkb_features`, `pkb_datapath`, `pkb_callpath`, `pkb_componentpath`, and `pkb_configpath`. Optional RunVars do not implicitly inherit into a controlled run.

## Lineage is annotation, not proof

- `pkb_changed_variables=pkb_browser` does not prove the browser changed.
- Matching `runProfileFingerprint` means the canonical final RunVar set is the same.
- Different `runProfileFingerprint` means at least one final RunVar differs.
- `configurationHash` is broader than the final RunVar set and is not a substitute for `runProfileFingerprint`.
- `DiagnosticRunComparator` compares derived evidence; lineage remains context.

Verify the resulting profile/fingerprint before attributing behavior to the declared change.

## Parent versus baseline

```text
Run A  original failure

Run B  browser counterfactual
       parent = A
       baseline = A

Run C  second counterfactual derived from B
       parent = B
       baseline = A

Run D  source-fix validation derived from C
       parent = C
       baseline = A
```

The parent is the immediate derivation chain. The baseline is the stable comparison anchor.

## Source changes belong in source provenance

Use source provenance for questions such as:

- Did the feature file change?
- Did the consumer commit change?
- Was the working tree dirty?
- Did resolved step-definition source change?

Use `pkb_run_purpose` to explain why a source change was made. Do not overload `pkb_changed_variables` with file names.

## Evidence controls are still RunVars

| RunVar | What it controls |
|---|---|
| `pkb_reportingmode` | normal versus diagnostic pipeline |
| `pkb_reportretention` | automatic local evidence retention |
| `pkb_diagnostic_output` | diagnostic output root |
| `pkb_platformlog` | platform/caller stamp behavior |
| `pkb_gitsnapshot` | source-provenance capture |
| `pkb_loglevel` | console verbosity |

If one is intentionally changed during a controlled rerun, include its name in `pkb_changed_variables`.

## Protected RunVars

A protected RunVar name may be declared without exposing its value:

```text
pkb_changed_variables=pkb_rp_api_key
```

The retained `runProfile` uses `${protected:pkb_rp_api_key}` for a nonblank secret. The actual protected value participates only in secure runtime configuration/one-way comparison inputs. An explicitly blank protected RunVar remains blank in the canonical profile.

## Agent checklist

1. Read the selected run's `runProfile`, `runProfileFingerprint`, and `directRunProfile`.
2. Replay `runProfile` through `pkb_runvars`.
3. Decide whether the hypothesis changes any actual RunVars.
4. If yes, change only those RunVars and list their canonical names in `pkb_changed_variables`.
5. If no RunVar changes, omit `pkb_changed_variables`.
6. Use `pkb_run_purpose` for the reason/hypothesis.
7. Use parent/baseline IDs for their distinct relationships.
8. Keep `pkb_investigation_id` stable when grouping related runs.
9. Keep protected values out of lineage text, prompts, logs, and committed files.
10. After the run, verify `runProfileFingerprint` and relevant source/comparison evidence.

See [AI Run Configuration](ai-run-configuration.md) for `pkb_runvars` behavior and [Diagnostic Reporting](diagnostic-reporting.md) for evidence navigation.
