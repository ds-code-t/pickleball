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

For simple values, the compact `pkb_run_profile=key=value, key=value` form is the shortest representation. For punctuation-heavy values or CI/property setups where nested assignment quoting would be awkward, use the expanded `pkb_run_profile.<pkb_var>=value` form instead. Both produce the same final RunVar model and `runProfileFingerprint`; do not mix the two direct-profile forms in one resolved configuration.

## Diagnostic lineage is separate from RunVars

These diagnostic/investigation properties describe a run but do not control scenario execution:

- `pkb_investigation_id`;
- `pkb_run_purpose`;
- `pkb_parent_run_id`;
- `pkb_baseline_run_id`;
- `pkb_changed_variables`.

They are **run metadata, not RunVars**. They survive direct `pkb_run_profile` resolution when supplied separately and are intentionally excluded from the retained run profile, its fingerprint, and the execution-configuration hash. YAML profiles, inline profiles, compact `pkb_run_profile` text, expanded `pkb_run_profile.*` members, and YAML direct-profile maps reject these keys so investigation lineage cannot accidentally become part of the execution contract.

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

## Compact and expanded direct-profile syntax

The compact form keeps the normal human-readable syntax:

```text
pkb_run_profile=pkb_tags=@checkout, pkb_browser=CHROME_HEADLESS
```

Its assignment parser is intentionally small and deterministic:

- comma or semicolon separates assignments;
- the first `=` separates key and value;
- `'` or `"` starts a quoted value only when it is the first non-whitespace character after `=`; quotes later in an unquoted value are literal characters;
- quoted values can contain assignment delimiters, with backslash used only for a literal matching quote or literal backslash;
- Pickleball identifies assignment boundaries before resolving `<...>` templates, so a comma or semicolon produced by template resolution stays inside the already-selected RunVar value. Template selector commas inside `<...>` are also kept inside the template token.

Example:

```text
pkb_tags="@checkout, @smoke"; pkb_rp_description=Bob's "QA" run; pkb_browser=CHROME_HEADLESS
```

When nested assignment syntax itself is undesirable, use expanded members:

```text
pkb_run_profile.pkb_tags=@checkout
pkb_run_profile.pkb_browser=CHROME_HEADLESS
pkb_run_profile.pkb_rp_description=Bob's "QA, phase 2; retry" = green
```

Each expanded property is one RunVar value and is not reparsed as an assignment list. The surrounding shell, Maven launcher, CI product, `.properties` parser, or YAML parser can still have its own transport rules; Pickleball cannot remove those external rules, but the expanded form removes the extra nested profile grammar.

Runner subclasses can construct the same form without serialization:

```java
PKB_props.runProfile(Map.of(
    "pkb_tags", "@checkout",
    "pkb_browser", "CHROME_HEADLESS",
    "pkb_rp_description", "Bob's \"QA, phase 2; retry\" = green"
));
```

A selected YAML profile can use a map directly:

```yaml
agent_direct:
  pkb_run_profile:
    pkb_tags: "<default_profile.pkb_tags> and @checkout"
    pkb_browser: CHROME_HEADLESS
    pkb_rp_description: "Bob's QA, phase 2; retry"
```

## Reuse the retained final run profile

After normal or named-profile resolution, Pickleball retains the resolved RunVars in `pkb_run_profile`. Diagnostic mode additionally exposes the sanitized value directly in sparse `run-index.json` as `runProfile`, together with:

- `runProfileFingerprint` — SHA-256 of the canonical final execution RunVars, including secret values only as hash input;
- `directRunProfile` — whether the run was launched from a direct full RunVar override.

`comparisonMetadata` carries the fingerprint and direct-mode flag so `run-catalog.json` and `DiagnosticRunComparator` can compare execution contracts without duplicating the full profile string into the catalog.

### `runProfileFingerprint` versus `configurationHash`

Use `runProfileFingerprint` as the authoritative equality check for the final execution RunVars. If two runs have the same `runProfileFingerprint`, their canonical final RunVar sets are the same, including protected values through one-way hash input.

`configurationHash` is intentionally broader. It can reflect execution-relevant configuration provenance or representation in addition to the final RunVars, so it may differ between two runs even when `runProfileFingerprint` is identical. Treat a `configurationHash` difference as additional configuration/provenance evidence to investigate, not by itself as proof that the final RunVars changed.

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
5. Supply it as one compact `pkb_run_profile` value when the retained values are simple, or as expanded `pkb_run_profile.<pkb_var>` members when avoiding nested assignment parsing is safer for the target environment. Do not mix the forms.
6. Change only the intended execution variables.
7. Supply `pkb_investigation_id`, `pkb_parent_run_id`, `pkb_baseline_run_id`, `pkb_run_purpose`, and `pkb_changed_variables` separately as lineage metadata.
8. Supply protected values separately rather than expanding them into the run-profile text.
9. Verify the resulting run-profile fingerprint and declared changed variables, then compare the resulting diagnostic evidence before expanding to a broader run.

The Maven consumer scenario tagged `@profile-direct-validation` is the end-to-end example. A conflicting ordinary tag property deliberately demonstrates that the direct profile remains authoritative while separately supplied diagnostic lineage survives:

```bash
mvn test -Dpkb_tags="@should-not-win" -Dpkb_investigation_id="diag-214-run-profile" -Dpkb_run_purpose="direct-profile-validation" -Dpkb_changed_variables="pkb_browser" -Dpkb_run_profile="pkb_glue=com.example.pickleball, pkb_features=classpath:features, pkb_tags=@profile-direct-validation, pkb_browser=CHROME_HEADLESS, pkb_reportingmode=diagnostic, pkb_reportretention=all"
```

The consumer also includes `@profile-expanded-validation` for the expanded form. The Pickleball portion of the command has no nested assignment list:

```bash
mvn test \
  -Dpkb_run_profile.pkb_glue=com.example.pickleball \
  -Dpkb_run_profile.pkb_features=classpath:features \
  -Dpkb_run_profile.pkb_tags=@profile-expanded-validation \
  -Dpkb_run_profile.pkb_browser=CHROME_HEADLESS \
  -Dpkb_run_profile.pkb_reportingmode=diagnostic \
  -Dpkb_run_profile.pkb_reportretention=all \
  -Dpkb_run_profile.pkb_rp_description=expanded-direct-profile \
  -Dpkb_investigation_id=diag-214-expanded-profile \
  -Dpkb_run_purpose=expanded-profile-validation
```

See [AI Diagnostic Reporting](ai-diagnostic-reporting-plan.md) for the evidence-reading hierarchy and [Execution Configuration](configuration.md) for the complete profile syntax and precedence contract.
