# Pickleball Consumer Agent Guide

This is the canonical AI-agent contract for projects that consume Pickleball as a Maven dependency.

A consumer project may contain only a short `AGENTS.md` bridge. That bridge can use Pickleball's `DiagnosticCli export-guidance` command to materialize the version-matched guidance embedded in the installed Pickleball dependency. When this file is materialized as `.pickleball/AGENT-GUIDE.md`, supporting documentation is under `.pickleball/docs/` and a curated reference snapshot of Pickleball's executable Maven consumer is under `.pickleball/maven-consumer-project/`.

## Generated guidance lifecycle

Treat `.pickleball` as generated dependency guidance, not as a durable source of truth by itself. Do not skip `export-guidance` merely because the directory already exists. The consumer bridge intentionally reruns the exporter before Pickleball work so the Maven dependency currently resolved on the test classpath remains authoritative.

A successful `export-guidance .pickleball` run:

- overwrites the current version's managed guidance files, documentation, and Maven consumer reference snapshot;
- writes `.pickleball/GUIDANCE-MANIFEST.json` last, recording the exporting Pickleball version and managed files;
- removes files managed by the previous manifest that are no longer shipped, while leaving unrelated files alone; and
- best-effort ensures `.pickleball` is ignored by Git, preferring an existing `.gitignore` and then repository-local `.git/info/exclude`.

The exporter does not create/commit a new `.gitignore`, alter the Git index, or untrack files that were already committed. If export fails, treat any existing `.pickleball` contents as potentially stale. The manifest records the last completed export; it is not a substitute for rerunning the exporter.

Compatibility note: an older Pickleball release whose exporter predates the manifest lifecycle may leave newer files behind after a downgrade. Those leftovers are not authoritative for the downgraded dependency. Prefer the dependency actually resolved on the test classpath and files freshly exported by that dependency.

## First actions

For Pickleball scenario authoring, configuration, execution, diagnostics, or troubleshooting:

1. Follow the consumer project's own instructions first; they remain authoritative for project-specific behavior.
2. Read this guide before changing Pickleball scenarios or diagnosing a Pickleball run.
3. Use `docs/README.md` as the documentation map.
4. Inspect the consumer project's `pom.xml`, Pickleball runner subclass, features, configuration, data, mappings, and test support before changing them.
5. Use `maven-consumer-project/` as a version-matched read-only reference when a documented syntax/configuration example or working Pickleball consumer structure is useful.
6. Do not assume the Pickleball core source repository is present. A normal consumer may only have the Maven dependency.

The exported documentation and Maven consumer reference are version-matched to the Pickleball artifact on the consumer's test classpath. Prefer them over instructions or examples copied from another release.

## Generated Maven consumer reference

`.pickleball/maven-consumer-project/` is a generated, read-only reference snapshot of the canonical Maven consumer used by Pickleball itself. It preserves repository-relative paths so links from the exported Markdown documentation continue to resolve locally.

The snapshot intentionally includes the consumer `pom.xml`, Pickleball runner, local browser/service test server, executable feature files, service-call definitions, configuration/data fixtures, local test-site resources, and the committed shared/local profile and property examples. It intentionally excludes Maven wrappers, Git/IDE/generated artifacts, the consumer `AGENTS.md` bridge, internal Java verification classes, and maintainer-only `_local2` files.

Use the snapshot to answer questions such as how a working feature, profile, property file, service call, configuration resource, browser fixture, or runner is structured. Do not modify or execute files under `.pickleball/maven-consumer-project/` as the consumer project's implementation. Make requested changes in the consumer project's own source tree. A later `export-guidance` run may overwrite or remove every managed reference file.

## Scenario authoring and fixes

When changing a consumer scenario:

- Prefer existing documented Pickleball syntax and executable examples.
- Use the version-matched `maven-consumer-project/` reference when it provides a working example of the same syntax or configuration.
- Do not invent a new Gherkin phrase when a Pickleball step or supported dynamic-step form already expresses the behavior.
- Preserve standard Cucumber behavior and project-specific custom glue.
- Inspect related mappings, component scenarios, service-call definitions, configuration, and test-site support before assuming a failing line is self-contained.
- Make the smallest change supported by evidence.
- Rerun the narrowest useful scenario/tag selection first, then broaden validation when needed.

Use supporting guides as appropriate, especially `docs/dynamic-steps.md`, `docs/component-scenarios.md`, `docs/service-call-scenarios.md`, `docs/mapping-and-templating.md`, `docs/data-values-and-elements.md`, `docs/configuration.md`, and `docs/cucumber-compatibility.md`.

## Configuration and controlled RunVars

Treat these as distinct concepts:

- `default_profile` — internal reference snapshot of normal resolved project RunVars;
- `pkb_runvars` — preferred controlled-run **input**;
- `pkb_run_profile` — canonical resolved RunVar **output** retained for diagnostics/replay.

Never supply `pkb_run_profile` or `pkb_run_profile.<pkb_var>` as input. They are reserved internal derived-output names; Pickleball rejects external use. Use `pkb_runvars` or `pkb_runvars.<pkb_var>` for controlled execution.

### Default AI test-launch rule

When you launch Pickleball tests and the intended execution settings are known, use `pkb_runvars` as the authoritative input. Put intentional tag/name selection, browser, evidence/logging controls, and other non-secret RunVar changes inside `pkb_runvars`; do not default to ambient optional project settings or separate JVM `-Dpkb_*` RunVars. Use `pkb_profile` or ordinary JVM RunVar overrides only when the task specifically tests those configuration semantics or the user asks for them. Keep protected secrets and diagnostic lineage outside `pkb_runvars`.

A selected profile or partial `pkb_runvars` input inherits only missing project execution-context RunVars:

```text
pkb_glue
pkb_features
pkb_datapath
pkb_callpath
pkb_componentpath
pkb_configpath
```

Optional RunVars such as browser, tags, reporting, logging, and ReportPortal values do not leak into a controlled run merely because normal configuration contains them.

Explicit JVM `-Dpkb_*` RunVars are different from ambient project defaults: they are intentional runtime overrides and remain active with a selected profile or controlled run. With a selected profile they override the same profile key; with `pkb_runvars`, the controlled value wins any conflict.

For those six inherited keys, missing means inherit, nonblank means override, and blank/null means suppress inheritance. A blank remains a blank tombstone in the retained final `runProfile`, because replaying that blank is what tells the underlying Pickleball subsystem to use its historical fallback behavior instead of re-inheriting a project value.

`pkb_configpath` selects the source loaded beneath the configuration mapping. Prefer `<config:...>` references such as `<config:URL.forms>`; legacy `<configs...>` references remain supported. Missing/blank `pkb_configpath` uses the historical `configs` Java fallback. Runtime config data cannot resolve profiles, `pkb_runvars`, or the path that loads the configs themselves.

Do not normalize other resource-path RunVar conventions. Follow the version-matched `docs/configuration.md` and `docs/config-files-and-resource-mapping.md` for each path.

## Diagnostic investigation protocol

When Pickleball diagnostic evidence exists, use the shallowest evidence layer that completely answers the question. Do not advance to denser evidence merely because it exists.

For AI-controlled diagnostic runs, keep terminal logging minimal. Diagnostic mode captures TRACE-through-ERROR evidence independently of console `pkb_loglevel`, so prefer `pkb_loglevel=warn` or `error` when appropriate. Use terminal output primarily for Maven, compilation, JVM, dependency, command-line, or other startup failures; use structured diagnostic artifacts after the run begins successfully.

Use this escalation order:

1. `run-catalog.json` to choose relevant runs.
2. Selected `run-index.json` and `clusters.json` for outcomes, scenario identity, failure grouping, capabilities, retention, step rollups, profile fingerprints, and representative visual references.
3. Selected scenario `summary.json` when additional sparse detail is needed.
4. Relevant `events.jsonl` only when exact step/lifecycle/order/INFO+ detail remains unanswered.
5. Existing `comparisonToPrevious` or Pickleball run/fingerprint comparison before opening screenshots.
6. A representative PNG only when semantic visual meaning must be understood.
7. `trace.jsonl.gz` or interrupted `trace.jsonl` only when structured/INFO+ evidence is insufficient.

Stop reading as soon as the current layer answers the investigation. Do not recursively ingest an entire diagnostic run.

## Visual evidence rules

- Never open a PNG merely to determine whether two screenshots differ.
- Prefer already-recorded `comparisonToPrevious` for adjacent screenshots.
- For cross-run or explicit comparison, use `DiagnosticCli`, `DiagnosticRunComparator`, or `VisualFingerprintComparator`.
- Do not manually decode `.pkbf` files or invent another image-comparison algorithm.
- `decodedPixelsExactlyEqual=true` establishes rendered-pixel equality.
- `IDENTICAL` ends a visual-difference investigation unless the image itself is required.
- Other similarity categories establish that pixels differ and their magnitude; open a representative PNG only when interpreting what visibly changed matters.
- Raw PNG byte inequality does not prove rendered pixels differ.

## Diagnostic utility commands

From a Maven consumer where Pickleball is on the test classpath:

```text
DiagnosticCli guidance
DiagnosticCli export-guidance [output-directory]
DiagnosticCli compare-runs <left-run-index> <right-run-index> [output-json]
DiagnosticCli compare-fingerprints <left.pkbf> <right.pkbf> [output-json]
DiagnosticCli rebuild <diagnostic-runs-root-or-run-root>
```

Use `guidance` to print this guide and `export-guidance` to materialize the complete version-matched documentation plus curated Maven consumer reference. Prefer `DiagnosticCli` over constructing Maven classpaths and JShell scripts for routine diagnostic operations.

## Controlled diagnostic reruns

When an investigation requires a rerun and the intended execution settings are known:

1. Start from the selected run's retained `runProfile` in `run-index.json`.
2. Replay that retained final RunVar set through compact `pkb_runvars` or expanded `pkb_runvars.<pkb_var>` members.
3. Never mix compact and expanded `pkb_runvars` forms. Never supply `pkb_run_profile` as input.
4. Preserve explicit blank assignments from the retained profile.
5. Change only RunVars required by the current hypothesis.
6. Do not reconstruct optional effective RunVars by manually combining defaults, property files, profiles, system properties, and Cucumber aliases when a retained profile is available.
7. Supply diagnostic lineage separately through `pkb_investigation_id`, `pkb_run_purpose`, `pkb_parent_run_id`, `pkb_baseline_run_id`, and `pkb_changed_variables`.
8. Treat lineage as descriptive investigation context, not proof of an execution/source difference.
9. Use `pkb_changed_variables` only for canonical execution RunVar names intentionally changed, such as `pkb_browser` or `pkb_tags`. Do not put source paths, feature files, commits, test-data changes, reasons, profile controls, or derived fields in `pkb_changed_variables`.
10. If source changes but final execution RunVars should remain identical, omit `pkb_changed_variables` and describe the goal in `pkb_run_purpose`.
11. Evidence/logging controls such as `pkb_reportingmode`, `pkb_reportretention`, `pkb_diagnostic_output`, `pkb_platformlog`, `pkb_gitsnapshot`, and `pkb_loglevel` are RunVars; declare them when intentionally changed.
12. After the rerun, verify `runProfileFingerprint`, compatibility field `directRunProfile`, and actual source/comparison evidence before attributing differences.
13. Use `runProfileFingerprint`, not `configurationHash`, as the equality signal for the final RunVar set.
14. Never expand protected values into logs, prompts, committed files, or diagnostic evidence.

Example controlled replay:

```text
-Dpkb_runvars="<retained runProfile with only intended edits>"
-Dpkb_investigation_id=<investigation>
-Dpkb_parent_run_id=<parent>
-Dpkb_baseline_run_id=<baseline>
-Dpkb_run_purpose=<hypothesis>
-Dpkb_changed_variables=pkb_browser
```

See `docs/ai-run-configuration.md` for the full profile/RunVar contract and `docs/diagnostic-lineage-metadata.md` for lineage semantics.

## Pickleball syntax documentation

The exported `docs/` tree is the version-matched reference for all supported Pickleball behavior and syntax. Use `docs/README.md` to select the relevant guide. Its links to the working consumer resolve into the exported `maven-consumer-project/` reference snapshot. In particular:

- dynamic Gherkin/action/assertion syntax — `docs/dynamic-steps.md`;
- element vocabulary/selectors — `docs/custom-element-definitions.md`;
- mappings/templates — `docs/mapping-and-templating.md`;
- Data Elements and values — `docs/data-values-and-elements.md` and `docs/data-element-query-runtime.md`;
- reusable component scenarios — `docs/component-scenarios.md`;
- service calls — `docs/service-call-scenarios.md`;
- nested flow and conditionals — `docs/nested-steps.md`, `docs/block-conditionals.md`;
- keyboard expressions — `docs/key-parser-dsl.md`;
- execution/configuration/profiles — `docs/configuration.md`, `docs/ai-run-configuration.md`;
- resource/config mapping — `docs/config-files-and-resource-mapping.md`;
- Cucumber compatibility — `docs/cucumber-compatibility.md`;
- diagnostics and lineage — `docs/diagnostic-reporting.md`, `docs/diagnostic-lineage-metadata.md`.

Do not guess Pickleball syntax when the version-matched guide or executable consumer reference can answer it.

## Human-readable consumer guidance

Use `docs/consumer-project.md` for the Maven consumer layout, local test site, common tag entry points, diagnostic usage, and example commands. Use `docs/README.md` to navigate the complete bundled documentation. Human readers can open files under `maven-consumer-project/` directly in the IDE to inspect the version-matched working features, configuration, calls, data, runner, and test-site examples linked from those guides.

## When the core Pickleball repository is also present

If the consumer is nested inside the Pickleball source repository, repository-level `AGENTS.md` may impose additional maintainer rules for framework changes. Those core-maintainer rules are additive and do not replace this consumer-facing contract.

For a normal external consumer, do not assume those core files exist.
