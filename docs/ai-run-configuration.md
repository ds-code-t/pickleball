# AI and Automation Run Configuration

This guide defines the preferred Pickleball configuration contract for AI agents and other automation that need deterministic test reruns.

## Prefer `pkb_run_profile` for a controlled rerun

When an agent already knows the exact RunVars required for the next test run, pass them as one explicit `pkb_run_profile` value:

```bash
mvn test -Dpkb_run_profile="pkb_glue=com.example.pickleball, pkb_features=classpath:features, pkb_tags=@checkout, pkb_browser=CHROME_HEADLESS, pkb_reportingmode=diagnostic"
```

An explicitly supplied `pkb_run_profile` is a **full RunVar override**. Pickleball determines the winning `pkb_run_profile` through the normal configuration-source precedence, then bypasses normal RunVar composition. It does not merge:

- `default_profile`;
- `pkb_profile` selections;
- runner default/property RunVars;
- Pickleball property-file RunVars;
- projected Cucumber CLI RunVar overrides.

Only assignments in `pkb_run_profile` become active RunVars. Normal downstream alias conversion still applies so `pkb_tags` can configure Cucumber and `pkb_rp_*` values can configure ReportPortal.

A selected YAML or inline named profile may also contain `pkb_run_profile`. In that case the selected profile acts as a reusable launcher for direct mode. A top-level `pkb_run_profile` supplied through normal configuration precedence takes priority over profile selection.

This is the preferred mode for an AI-controlled diagnostic rerun when the goal is to change a known, bounded set of execution conditions without accidentally inheriting another local/default RunVar.

## Diagnostic lineage is separate from RunVars

These diagnostic/investigation properties describe a run but do not control scenario execution:

- `pkb_investigation_id`;
- `pkb_run_purpose`;
- `pkb_parent_run_id`;
- `pkb_baseline_run_id`;
- `pkb_changed_variables`.

They are **run metadata, not RunVars**. They survive direct `pkb_run_profile` resolution when supplied separately and are intentionally excluded from the retained run profile, its fingerprint, and the execution-configuration hash. YAML profiles, inline profiles, and direct `pkb_run_profile` text reject these keys so investigation lineage cannot accidentally become part of the execution contract.

For a controlled diagnostic rerun, pass the exact execution profile and lineage separately:

```bash
mvn test \
  -Dpkb_run_profile="pkb_glue=com.example.pickleball, pkb_features=classpath:features, pkb_tags=@checkout, pkb_browser=CHROME_HEADLESS, pkb_reportingmode=diagnostic" \
  -Dpkb_investigation_id="diag-checkout" \
  -Dpkb_parent_run_id="<previous-run-id>" \
  -Dpkb_baseline_run_id="<baseline-run-id>" \
  -Dpkb_run_purpose="controlled-browser-rerun" \
  -Dpkb_changed_variables="pkb_browser"
```

This separation means an agent can reuse the same execution contract while changing only the lineage that explains why a new run exists.

## Reuse the retained final run profile

After normal or named-profile resolution, Pickleball retains the resolved RunVars in `pkb_run_profile`. Diagnostic mode additionally exposes the sanitized value directly in sparse `run-index.json` as `runProfile`, together with:

- `runProfileFingerprint` — SHA-256 of the canonical final execution RunVars, including secret values only as hash input;
- `directRunProfile` — whether the run was launched from a direct full RunVar override.

`comparisonMetadata` carries the fingerprint and direct-mode flag so `run-catalog.json` and `DiagnosticRunComparator` can compare execution contracts without duplicating the full profile string into the catalog.

### `runProfileFingerprint` versus `configurationHash`

Use `runProfileFingerprint` as the authoritative equality check for the final execution RunVars. If two runs have the same `runProfileFingerprint`, their canonical final RunVar sets are the same, including protected values through one-way hash input.

`configurationHash` is intentionally broader. It represents execution-relevant configuration provenance and representation in addition to the final RunVars, so it may differ between two runs even when `runProfileFingerprint` is identical. For example, the same final RunVars may have been reached through different configuration sources or equivalent option representations.

For a controlled rerun, use `runProfileFingerprint` to verify that the final execution RunVars were preserved or changed as intended. Treat a `configurationHash` difference as additional configuration/provenance evidence to investigate, not by itself as proof that the final RunVars changed.

Example retained value:

```text
pkb_glue=com.example.pickleball, pkb_features=classpath:features, pkb_tags=@checkout, pkb_browser=CHROME_HEADLESS, pkb_reportingmode=diagnostic
```

An agent may use `runProfile` as the baseline for a counterfactual rerun and change only the intended assignment, for example the browser or tag selector. After the rerun, compare `runProfileFingerprint` and `pkb_changed_variables` to confirm the configuration change was the one intended.

## Protected values

Sensitive variables are never embedded in plaintext in the retained serialized `pkb_run_profile`. Explicit protected names remain centralized in `SensitiveConfiguration`, and conservative secret-like name detection also protects future keys containing terms such as `password`, `secret`, `token`, `credential`, or API/private/access-key variants.

Protected values are represented as placeholders:

```text
pkb_rp_api_key=${protected:pkb_rp_api_key}
```

For a new process, provide the protected value separately through an approved configuration source, for example:

```bash
mvn test \
  -Drp.api.key="$REPORT_PORTAL_API_KEY" \
  -Dpkb_run_profile='pkb_tags=@checkout, pkb_browser=CHROME_HEADLESS, pkb_rp_enable=true, pkb_rp_api_key=${protected:pkb_rp_api_key}'
```

Pickleball constructs `default_profile` as reference data, restores the protected field from it, then applies only the direct run-profile RunVars. The separately supplied secret is therefore available for substitution without becoming an additional active RunVar source.

The diagnostic `runProfileFingerprint` may change when a protected value changes because the actual effective RunVar participates in the one-way hash, but the secret itself is never emitted in `runProfile`, configuration evidence, prompts, or logs.

Do not replace `${protected:...}` with a secret in logs, diagnostic evidence, prompts, issue comments, or committed files.

## Named profiles remain useful for reusable project configuration

Use `pkb_profile` when the run should intentionally use reusable project-defined profile composition:

```bash
mvn test -Dpkb_profile="default_profile,qa,browser_firefox"
```

Use `pkb_run_profile` instead when the purpose of the rerun is to eliminate uncertainty about other RunVar defaults/overrides.

## Diagnostic investigation workflow

For AI-assisted troubleshooting:

1. Inspect existing sparse diagnostic evidence first.
2. Identify the smallest useful rerun and exact configuration change.
3. Read `runProfile`, `runProfileFingerprint`, and `directRunProfile` from the selected `run-index.json`.
4. Start from the retained `runProfile` when available.
5. Supply it explicitly as `-Dpkb_run_profile=...` so the rerun has one authoritative RunVar set.
6. Change only the intended execution variables.
7. Supply `pkb_investigation_id`, `pkb_parent_run_id`, `pkb_baseline_run_id`, `pkb_run_purpose`, and `pkb_changed_variables` separately as lineage metadata.
8. Supply protected values separately rather than expanding them into the run-profile text.
9. Verify the resulting run-profile fingerprint and declared changed variables, then compare the resulting diagnostic evidence before expanding to a broader run.

The Maven consumer scenario tagged `@profile-direct-validation` is the end-to-end example. A conflicting ordinary tag property deliberately demonstrates that the direct profile remains authoritative while separately supplied diagnostic lineage survives:

```bash
mvn test -Dpkb_tags="@should-not-win" -Dpkb_investigation_id="diag-214-run-profile" -Dpkb_run_purpose="direct-profile-validation" -Dpkb_changed_variables="pkb_browser" -Dpkb_run_profile="pkb_glue=com.example.pickleball, pkb_features=classpath:features, pkb_tags=@profile-direct-validation, pkb_browser=CHROME_HEADLESS, pkb_reportingmode=diagnostic, pkb_reportretention=all"
```

See [AI Diagnostic Reporting](ai-diagnostic-reporting-plan.md) for the evidence-reading hierarchy and [Execution Configuration](configuration.md) for the complete profile syntax and precedence contract.
