# Diagnostic Lineage and Metadata

This guide defines how Pickleball diagnostic lineage fields should be authored and how they differ from execution RunVars and derived diagnostic evidence.

The key rule is:

> Lineage metadata explains why runs are related. It does not control execution and it is not proof that a configuration or source change actually occurred.

## Four different kinds of diagnostic/configuration data

Keep these categories separate when authoring or investigating a run.

### 1. Lineage metadata: descriptive inputs

These five properties describe the investigation relationship between runs:

```text
pkb_investigation_id
pkb_run_purpose
pkb_parent_run_id
pkb_baseline_run_id
pkb_changed_variables
```

They are metadata, not RunVars. Pickleball keeps them out of `pkb_run_profile`, `runProfileFingerprint`, and the execution `configurationHash`.

### 2. RunVars: settings that affect execution or evidence behavior

Normal Pickleball settings remain RunVars even when they mainly control diagnostics or logging. Examples include:

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
```

These values belong in the effective run profile. If an agent intentionally changes one during a controlled rerun, its canonical `pkb_*` name may be listed in `pkb_changed_variables`.

### 3. Profile/control properties: configuration inputs that are not RunVars

Some `pkb_*` properties control how the final execution model is selected or represented but are deliberately excluded from the RunVar set. Important examples are:

```text
pkb_profile
pkb_run_profile
pkb_run_profile.<pkb_var>
pkb_profile_<name>
pkb_options
```

Do not list these control names in `pkb_changed_variables`. For example, if a direct rerun changes the browser inside `pkb_run_profile`, declare `pkb_browser`, not `pkb_run_profile`. If a profile/control change causes effective RunVars or selection metadata to differ, verify those resolved outputs directly.

### 4. Derived evidence: read-only outputs

Fields such as these are produced by Pickleball and should be read, not supplied as lineage properties:

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

## Lineage field meanings

| Property | Meaning | Recommended use | Do not use it for |
|---|---|---|---|
| `pkb_investigation_id` | Stable label grouping related runs into one investigation | Reuse the same value across related reruns; include it on the original/control run when the investigation is known at launch | Encoding configuration differences or source paths |
| `pkb_run_purpose` | Human/agent-readable reason this specific run exists | State the hypothesis or validation goal, such as `verify-source-fix` or `browser-counterfactual` | Machine-verifying that a change occurred |
| `pkb_parent_run_id` | Immediate predecessor from which this run was derived | Point to the run whose evidence/profile directly motivated this rerun | A permanent baseline when several generations of reruns exist |
| `pkb_baseline_run_id` | Stable comparison anchor for an investigation | Keep the original failed/control run as the baseline while later reruns chain through different parents | Automatically selecting the previous run |
| `pkb_changed_variables` | Declaration of the Pickleball RunVar names intentionally changed for this rerun | Use canonical names such as `pkb_browser` or `pkb_tags`; for several, use a comma-separated list | Source files, feature files, commits, test data, application changes, reasons, or derived evidence fields |

All five fields are optional. Omit a field when it would add no accurate information.

## `pkb_changed_variables` means changed RunVars only

`pkb_changed_variables` is intentionally narrow. It names the Pickleball execution/evidence RunVars that the rerun intentionally changes relative to the retained execution contract used as the rerun starting point, normally the selected parent run.

Good examples:

```text
pkb_changed_variables=pkb_browser
pkb_changed_variables=pkb_tags
pkb_changed_variables=pkb_browser,pkb_tags
pkb_changed_variables=pkb_reportretention
```

Do not use source-oriented or control-property values such as:

```text
pkb_changed_variables=source:navigation.feature
pkb_changed_variables=src/test/resources/features/navigation.feature
pkb_changed_variables=git-commit
pkb_changed_variables=test-data
pkb_changed_variables=pkb_run_profile
pkb_changed_variables=pkb_profile
```

Those are not execution RunVar names. `pkb_run_profile` and `pkb_profile` may control how RunVars are resolved, but the changed-variable declaration should name the effective RunVars themselves.

If a rerun changes only project source, Gherkin, mappings, test data, or application code while preserving the same execution RunVars, **omit `pkb_changed_variables`**. Describe the reason in `pkb_run_purpose`; use diagnostic source provenance to determine what source actually changed.

Example source-fix validation:

```bash
mvn test \
  -Dpkb_run_profile="<retained runProfile>" \
  -Dpkb_investigation_id="diag-navigation" \
  -Dpkb_parent_run_id="<failed-run-id>" \
  -Dpkb_baseline_run_id="<original-failed-run-id>" \
  -Dpkb_run_purpose="verify-source-fix"
```

There is deliberately no `pkb_changed_variables` assignment in that example.

## Lineage metadata is annotation, not proof

Pickleball records supplied lineage values in lightweight diagnostic metadata. The fields are descriptive strings; they do not replace comparison of the actual run evidence. Nonblank lineage values are copied into the run manifest/index/catalog as investigation context; Pickleball does not automatically prove that referenced runs exist or validate `pkb_changed_variables` against a computed RunVar diff.

In particular:

- `pkb_changed_variables=pkb_browser` does not by itself prove the browser changed; an inaccurate or misspelled declaration can still be recorded as metadata.
- A matching `runProfileFingerprint` means the final canonical RunVar set is the same, regardless of what lineage text claims.
- A different `runProfileFingerprint` means at least one final RunVar differs, but `pkb_changed_variables` should still accurately name only the intentionally changed RunVars.
- `configurationHash` is broader than the final RunVar set and is not a substitute for `runProfileFingerprint`.
- `DiagnosticRunComparator` compares derived comparison metadata and scenario evidence; lineage remains investigation context rather than an execution-equivalence assertion.

For a controlled rerun, verify the actual profile/fingerprint after the run before attributing behavior to the declared change.

## Parent versus baseline

`pkb_parent_run_id` and `pkb_baseline_run_id` often match on the first rerun, but they serve different purposes.

Example investigation:

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

The parent describes the immediate derivation chain. The baseline remains the stable comparison anchor.

Pickleball records these IDs as metadata; agents should not assume the referenced run exists without checking the run catalog when that relationship matters.

## Source changes belong in source provenance

Diagnostic mode captures source identity separately from lineage. Depending on repository state and `pkb_gitsnapshot`, evidence can include consumer commit/branch/dirty state, source hashes, and optionally a working-tree diff snapshot.

Use that evidence for questions such as:

- Did the feature file change?
- Did the consumer commit change?
- Was the working tree dirty?
- Did the resolved step-definition source change?

Use `pkb_run_purpose` to explain why a source change was made. Do not overload `pkb_changed_variables` with file names.

## Logging and evidence controls are still RunVars

Several names may look like "metadata settings" because they control evidence rather than application behavior. They are nevertheless RunVars:

| RunVar | What it controls | If intentionally changed during a controlled rerun |
|---|---|---|
| `pkb_reportingmode` | Normal versus diagnostic reporting pipeline | Include `pkb_reportingmode` in `pkb_changed_variables` |
| `pkb_reportretention` | Automatic local evidence/report retention | Include `pkb_reportretention` |
| `pkb_diagnostic_output` | Diagnostic output root | Include `pkb_diagnostic_output` |
| `pkb_platformlog` | Automatic platform/caller log stamp behavior | Include `pkb_platformlog` |
| `pkb_gitsnapshot` | Source-provenance capture mode (`metadata`, `diff`, `none`) | Include `pkb_gitsnapshot` |
| `pkb_loglevel` | Console logging verbosity | Include `pkb_loglevel` |

This distinction matters because these values participate in the resolved RunVar model and can affect `runProfileFingerprint`.

## Recommended controlled-rerun patterns

### Same RunVars, source-only fix

```text
runProfile: reuse retained profile unchanged
pkb_investigation_id: keep investigation ID
pkb_parent_run_id: failed/control run being followed
pkb_baseline_run_id: stable original baseline
pkb_run_purpose: verify-source-fix
pkb_changed_variables: omit
```

### One RunVar counterfactual

```text
runProfile: retained profile with only pkb_browser changed
pkb_investigation_id: keep investigation ID
pkb_parent_run_id: run being counterfactually rerun
pkb_baseline_run_id: stable original baseline
pkb_run_purpose: browser-counterfactual
pkb_changed_variables: pkb_browser
```

### Several intentional RunVar changes

Use canonical names and keep the declaration bounded:

```text
pkb_changed_variables=pkb_browser,pkb_tags
```

Do not add values that merely happened to differ because of source/runtime provenance. Investigate those through the corresponding derived evidence.

### Protected RunVar change

If a protected RunVar itself is intentionally changed, its name may still be declared without exposing its value:

```text
pkb_changed_variables=pkb_rp_api_key
```

The retained `runProfile` continues to use `${protected:pkb_rp_api_key}` while the actual protected value participates only in one-way fingerprint/hash inputs.

## Agent checklist

Before launching a controlled diagnostic rerun:

1. Read the selected run's `runProfile`, `runProfileFingerprint`, and `directRunProfile`.
2. Decide whether the hypothesis changes any actual RunVars.
3. If yes, change only those RunVars and list their canonical names in `pkb_changed_variables`.
4. If no RunVar changes, omit `pkb_changed_variables`.
5. Use `pkb_run_purpose` for the reason or hypothesis, including source-fix validation.
6. Use `pkb_parent_run_id` for the immediate predecessor and `pkb_baseline_run_id` for the stable comparison anchor.
7. Keep `pkb_investigation_id` stable across related runs when grouping is useful.
8. Keep protected values out of lineage text, prompts, logs, and committed files.
9. After the run, verify the actual `runProfileFingerprint` and relevant source/comparison evidence. Treat lineage as context, not proof.

See [AI and Automation Run Configuration](ai-run-configuration.md) for direct-profile behavior and [Diagnostic Reporting](diagnostic-reporting.md) for the evidence hierarchy and source-provenance model.
