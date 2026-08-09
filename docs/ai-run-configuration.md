# AI and Automation Run Configuration

This guide defines the preferred Pickleball configuration contract for AI agents and other automation that need deterministic test reruns.

## Prefer `pkb_run_profile` for a controlled rerun

When an agent already knows the exact RunVars required for the next test run, pass them as one explicit `pkb_run_profile` value:

```bash
mvn test "-Dpkb_run_profile=pkb_glue=com.example.pickleball, pkb_features=classpath:features, pkb_tags=@checkout, pkb_browser=CHROME_HEADLESS, pkb_reportingmode=diagnostic"
```

An explicitly supplied `pkb_run_profile` is a **full RunVar override**. Pickleball determines the winning `pkb_run_profile` through the normal configuration-source precedence, then bypasses normal RunVar composition. It does not merge:

- `default_profile`;
- `pkb_profile` selections;
- runner default/property RunVars;
- Pickleball property-file RunVars;
- projected Cucumber CLI RunVar overrides.

Only assignments in `pkb_run_profile` become active RunVars. Normal downstream alias conversion still applies so `pkb_tags` can configure Cucumber and `pkb_rp_*` values can configure ReportPortal.

A selected YAML or inline named profile may also contain `pkb_run_profile`. In that case the selected profile acts as a reusable launcher for direct mode. A top-level `pkb_run_profile` supplied through normal configuration precedence takes priority over profile selection.

This is the preferred mode for an AI-controlled diagnostic rerun when the goal is to change a known, bounded set of conditions without accidentally inheriting another local/default RunVar.

## Reuse the retained final run profile

After normal or named-profile resolution, Pickleball retains the resolved RunVars in `pkb_run_profile`. This makes the effective configuration easy to copy into a subsequent controlled run.

Example retained value:

```text
pkb_glue=com.example.pickleball, pkb_features=classpath:features, pkb_tags=@checkout, pkb_browser=CHROME_HEADLESS, pkb_reportingmode=diagnostic
```

An agent may use that value as the baseline for a counterfactual rerun and change only the intended assignment, for example the browser or tag selector.

## Protected values

Sensitive variables are never embedded in plaintext in the retained serialized `pkb_run_profile`. They are represented as protected placeholders:

```text
pkb_rp_api_key=${protected:pkb_rp_api_key}
```

For a new process, provide the protected value separately through an approved configuration source, for example:

```bash
mvn test \
  -Drp.api.key="$REPORT_PORTAL_API_KEY" \
  '-Dpkb_run_profile=pkb_tags=@checkout, pkb_browser=CHROME_HEADLESS, pkb_rp_enable=true, pkb_rp_api_key=${protected:pkb_rp_api_key}'
```

Pickleball constructs `default_profile` as reference data, restores the protected field from it, then applies only the direct run-profile RunVars. The separately supplied secret is therefore available for substitution without becoming an additional active RunVar source.

Do not replace `${protected:...}` with a secret in logs, diagnostic evidence, prompts, issue comments, or committed files.

## Named profiles remain useful for reusable project configuration

Use `pkb_profile` when the run should intentionally use reusable project-defined profile composition:

```bash
mvn test -Dpkb_profile=default_profile,qa,browser_firefox
```

Use `pkb_run_profile` instead when the purpose of the rerun is to eliminate uncertainty about other RunVar defaults/overrides.

## Diagnostic investigation workflow

For AI-assisted troubleshooting:

1. Inspect existing sparse diagnostic evidence first.
2. Identify the smallest useful rerun and exact configuration change.
3. Start from the retained `pkb_run_profile` when available.
4. Supply it explicitly as `-Dpkb_run_profile=...` so the rerun has one authoritative RunVar set.
5. Change only the intended variables.
6. Preserve `pkb_investigation_id`, `pkb_parent_run_id`, `pkb_baseline_run_id`, `pkb_run_purpose`, and `pkb_changed_variables` when useful for lineage.
7. Supply protected values separately rather than expanding them into the run-profile text.
8. Compare the resulting diagnostic evidence before expanding to a broader run.

See [AI Diagnostic Reporting](ai-diagnostic-reporting-plan.md) for the evidence-reading hierarchy and [Execution Configuration](configuration.md) for the complete profile syntax and precedence contract.
