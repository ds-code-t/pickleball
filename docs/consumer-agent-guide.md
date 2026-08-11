# Pickleball Consumer Agent Guide

This is the canonical AI-agent contract for projects that consume Pickleball as a Maven dependency.

A consumer project may contain only a short `AGENTS.md` bridge. That bridge can use Pickleball's `DiagnosticCli export-guidance` command to materialize the version-matched guidance embedded in the installed Pickleball dependency. When this file is materialized as `.pickleball/AGENT-GUIDE.md`, the supporting documentation is under `.pickleball/docs/`.

## Generated guidance lifecycle

Treat `.pickleball` as generated dependency guidance, not as a durable source of truth by itself. Do not skip `export-guidance` merely because the directory already exists. The consumer bridge intentionally reruns the exporter before Pickleball work so the Maven dependency currently resolved on the test classpath remains authoritative.

A successful `export-guidance .pickleball` run:

- overwrites the current version's managed guidance files;
- writes `.pickleball/GUIDANCE-MANIFEST.json` last, recording the exporting Pickleball version and the files managed by that completed export;
- removes files that were managed by the previous manifest but are no longer shipped by the current dependency, while leaving unrelated files alone; and
- best-effort ensures `.pickleball` is ignored by Git. It prefers an existing consumer/repository `.gitignore` so the ignore rule can be committed and synchronized, then falls back to the repository-local `.git/info/exclude`. Ignore-file problems produce a warning and never block guidance export.

The exporter does not create or commit a new `.gitignore`, alter the Git index, or untrack files that were already committed. If `.pickleball` was already tracked, fix that repository state separately.

The generated `AGENT-GUIDE.md` also carries the exporting Pickleball version. If export fails, treat any existing `.pickleball` directory as potentially stale and do not rely on it as current guidance. Inspect `GUIDANCE-MANIFEST.json` only as evidence of the last **completed** export; it is not a substitute for rerunning the exporter.

Compatibility note: the manifest-based managed-file cleanup above is provided by Pickleball releases that include that lifecycle. If a consumer downgrades to an older Pickleball release whose exporter predates the manifest lifecycle, that older exporter may overwrite the files it ships while leaving newer files or a newer manifest from a later release in `.pickleball`. Those leftovers are not authoritative for the downgraded dependency. Prefer the dependency actually resolved on the test classpath and the files freshly exported by that dependency; a subsequent export by a modern Pickleball release restores the managed tree.

## First actions

For Pickleball scenario authoring, configuration, execution, diagnostics, or troubleshooting:

1. Follow the consumer project's own instructions first. They remain authoritative for project-specific behavior.
2. Read this guide before changing Pickleball scenarios or diagnosing a Pickleball run.
3. Use `docs/README.md` as the documentation map.
4. Inspect the consumer project's `pom.xml`, Pickleball runner subclass, feature files, configuration, data, and test support before changing them.
5. Do not assume the Pickleball core source repository is present. A normal consumer may only have the Maven dependency.

The exported documentation is version-matched to the Pickleball artifact on the consumer's test classpath. Prefer it over instructions copied from a different Pickleball release.

## Scenario authoring and fixes

When changing a consumer scenario:

- Prefer existing documented Pickleball syntax and examples.
- Do not invent a new Gherkin phrase when an existing Pickleball step or supported dynamic-step form already expresses the behavior.
- Preserve standard Cucumber behavior and project-specific custom glue.
- Inspect related mappings, component scenarios, service-call definitions, configuration, and test-site support before assuming a failing line is self-contained.
- Make the smallest change supported by the evidence.
- Rerun the narrowest useful scenario or tag selection first, then broaden validation when needed.

Use these supporting guides as appropriate:

- `docs/dynamic-steps.md`
- `docs/component-scenarios.md`
- `docs/service-call-scenarios.md`
- `docs/mapping-and-templating.md`
- `docs/data-values-and-elements.md`
- `docs/configuration.md`
- `docs/cucumber-compatibility.md`

## Diagnostic investigation protocol

When Pickleball diagnostic evidence exists, use the shallowest evidence layer that completely answers the question. Do not advance to a denser layer merely because it exists.

For AI-controlled diagnostic runs, keep terminal logging minimal. Diagnostic mode captures TRACE-through-ERROR evidence independently of console `pkb_loglevel`, so prefer `pkb_loglevel=warn` or, when appropriate, `pkb_loglevel=error`. Use terminal output primarily to detect Maven, compilation, JVM, dependency, command-line, or other startup failures that may occur before Pickleball diagnostic capture begins; use the structured diagnostic artifacts as the primary investigation evidence after the run starts successfully.

Use this escalation order:

1. `run-catalog.json` to choose relevant runs.
2. Selected `run-index.json` files and `clusters.json` for outcomes, scenario identity, failure grouping, capabilities, retention state, step rollups, execution-profile fingerprints, and representative visual references.
3. Selected scenario `summary.json` files for additional sparse scenario detail.
4. Only the relevant scenario `events.jsonl` when exact step, lifecycle, ordering, nested execution, or INFO+ detail remains unanswered.
5. Existing `comparisonToPrevious` visual metadata or Pickleball fingerprint/run comparison before opening any screenshot.
6. A representative PNG only when the semantic content of a visual difference must be understood.
7. `trace.jsonl.gz` or interrupted raw `trace.jsonl` only when structured and INFO+ evidence is insufficient.

Stop reading as soon as the current layer answers the investigation with sufficient confidence.

Do not recursively ingest an entire diagnostic run. Select evidence by run, scenario, event range, and representative visual reference.

See `docs/diagnostic-reporting.md` for the complete evidence schema and retention behavior.

## Visual evidence rules

- Never open a PNG merely to determine whether two screenshots differ.
- Prefer already-recorded `comparisonToPrevious` metadata for adjacent screenshots.
- For cross-run or explicit fingerprint comparison, use Pickleball's `DiagnosticCli`, `DiagnosticRunComparator`, or `VisualFingerprintComparator`.
- Do not manually decode `.pkbf` files or invent a separate image-comparison algorithm.
- `decodedPixelsExactlyEqual=true` establishes rendered-pixel equality; no PNG inspection is needed for that question.
- `IDENTICAL` ends a visual-difference investigation unless the user explicitly needs the image itself.
- `VERY_SIMILAR`, `SOMEWHAT_SIMILAR`, or `VERY_DIFFERENT` establishes that pixels differ and gives the magnitude. Open a representative PNG only when the visible meaning of the change matters.
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

Use `guidance` to print this agent guide and `export-guidance` to materialize the complete version-matched Pickleball Markdown documentation.

Prefer `DiagnosticCli` over constructing Maven classpaths and JShell scripts for routine diagnostic operations.

## Controlled diagnostic reruns

When an investigation requires a rerun and the intended Pickleball execution settings are known:

1. Start from the selected run's retained `runProfile` in `run-index.json`.
2. Use compact `pkb_run_profile` for simple values, or expanded `pkb_run_profile.<pkb_var>` members when avoiding nested assignment parsing is safer.
3. Never mix compact and expanded direct-profile forms in one resolved configuration.
4. Change only the RunVars required by the current hypothesis.
5. Do not reconstruct effective RunVars by manually combining defaults, property files, named profiles, system properties, and Cucumber aliases when a retained final run profile is available.
6. Supply diagnostic lineage separately through `pkb_investigation_id`, `pkb_run_purpose`, `pkb_parent_run_id`, `pkb_baseline_run_id`, and `pkb_changed_variables`.
7. Treat lineage as descriptive investigation context, not as proof of an execution or source difference.
8. Use `pkb_changed_variables` only for canonical Pickleball RunVar names intentionally changed for the rerun, for example `pkb_browser` or `pkb_tags`. Do not put source paths, feature files, commits, test-data changes, reasons, or derived diagnostic fields there. If the rerun changes source but preserves the execution RunVars, omit `pkb_changed_variables` and describe the source-fix goal in `pkb_run_purpose`.
9. Use `pkb_parent_run_id` for the immediate predecessor and `pkb_baseline_run_id` for the stable comparison anchor. They may be the same on the first rerun and diverge on later reruns.
10. Do not put profile/control properties such as `pkb_profile`, `pkb_run_profile`, `pkb_run_profile.*`, profile definitions, or `pkb_options` in `pkb_changed_variables`; declare the effective RunVar names that intentionally changed instead.
11. Remember that evidence/logging controls such as `pkb_reportingmode`, `pkb_reportretention`, `pkb_diagnostic_output`, `pkb_platformlog`, `pkb_gitsnapshot`, and `pkb_loglevel` are RunVars, not lineage metadata. If intentionally changed, declare their names in `pkb_changed_variables`.
12. After the rerun, verify `runProfileFingerprint`, `directRunProfile`, and the actual source/comparison evidence before attributing observed differences to the intended change. `pkb_changed_variables` is a declaration; Pickleball does not use it as the equality check.
13. Use `runProfileFingerprint`, not `configurationHash`, as the equality check for the final RunVar set.
14. Never expand protected values into logs, prompts, committed files, or diagnostic evidence.

See `docs/diagnostic-lineage-metadata.md` for field-by-field lineage semantics and `docs/ai-run-configuration.md` for the complete controlled-rerun contract.

## Human-readable consumer guidance

Use `docs/consumer-project.md` for the Maven consumer project layout, local test site, common tag entry points, diagnostic usage, and example commands.

Use `docs/README.md` to navigate the complete bundled Pickleball documentation.

## When the core Pickleball repository is also present

If the consumer happens to be nested inside the Pickleball source repository, the repository-level `AGENTS.md` may impose additional maintainer rules for framework changes. Those core-maintainer rules are additive and do not replace this consumer-facing contract.

For a normal external consumer, do not assume those core files exist.
