# Diagnostic Reporting

> **Executable checks:** [`internal-framework-java-checks.feature`](../maven-consumer-project/src/test/resources/features/internal-framework-java-checks.feature) runs the consumer-hosted diagnostic reporting checks.

Pickleball diagnostic reporting is an alternate evidence pipeline for AI-assisted troubleshooting. It keeps inexpensive indexes at the top and dense events, screenshots, fingerprints, and deep trace evidence behind targeted references.

## Enable diagnostic mode

```properties
pkb_reportingmode=diagnostic
```

Missing/other values use the normal reporting lifecycle. Diagnostic mode captures TRACE-through-ERROR diagnostic evidence independently of console `pkb_loglevel`, keeps console verbosity controlled by `pkb_loglevel`, bypasses automatic normal HTML/ReportPortal/XLSX sinks, keeps explicitly invoked reporting steps functional, and stores Selenium screenshots as binary PNG evidence.

The resolved state is exposed as `PickleballRunner.DIAGNOSTIC_MODE`.

## Retention

`pkb_reportretention` supports:

| Value | Behavior |
|---|---|
| `all` | default; retain all automatic evidence |
| `failed` | retain dense evidence for failures/interruption and sparse summaries for passes |
| `none` | prune automatic dense evidence while retaining minimal navigation indexes |

Explicit report steps are not suppressed by this setting.

## Diagnostic layout

Default root:

```text
reports/diagnostic-runs/
    run-catalog.json
    <run-id>/
        manifest.json
        run-index.json
        run-events.jsonl
        configuration.json
        environment.json
        source-provenance.json
        clusters.json
        scenarios/
            <scenario-execution-id>/
                summary.json
                events.jsonl
                trace.jsonl.gz
                screenshots/
                fingerprints/
```

An interrupted scenario may retain raw `trace.jsonl` instead of the gzip form. `pkb_diagnostic_output` may move the diagnostic-runs root.

`run-index.json` includes sparse scenario/outcome/comparison metadata plus the sanitized final `runProfile`, deterministic `runProfileFingerprint`, and compatibility field `directRunProfile`. The field name `directRunProfile` remains for diagnostic schema compatibility even though new direct controlled input is `pkb_runvars`. `run-catalog.json` copies each run's `runProfile` / `runProfileFingerprint` when present. Scenario `summary.json` also includes the same `runProfile` so agents can inspect the complete resolved RunVar snapshot without opening `configuration.json`.

## AI evidence access protocol

Use the shallowest evidence layer that completely answers the question:

1. `run-catalog.json` — choose candidate runs. Catalog entries include the retained `runProfile` when present.
2. Selected `run-index.json` / `clusters.json` — outcomes, identities, failure groups, capabilities, retention, the complete `runProfile`, profile fingerprints, representative visuals.
3. Selected scenario `summary.json` — additional sparse detail, including the same `runProfile`.
4. Targeted `events.jsonl` — exact step/lifecycle/order/INFO+ detail only when needed.
5. Existing `comparisonToPrevious`, run comparison, or fingerprint comparison.
6. Representative PNG only when semantic visual meaning matters.
7. `trace.jsonl.gz` / interrupted `trace.jsonl` only when structured/INFO+ evidence is insufficient.

Stop as soon as the evidence answers the question. Do not recursively ingest an entire diagnostic run.

## Controlled diagnostic reruns

Use the selected run's retained `runProfile` as the starting final RunVar contract. Replay it through `pkb_runvars`:

```text
-Dpkb_runvars="<retained runProfile>"
```

or expanded members:

```text
-Dpkb_runvars.pkb_browser=firefox
-Dpkb_runvars.pkb_tags=@smoke
```

Do not mix compact and expanded `pkb_runvars`. Never supply `pkb_run_profile` as input; it is derived output.

A partial controlled input automatically inherits only missing project execution-context RunVars:

```text
pkb_glue
pkb_features
pkb_datapath
pkb_callpath
pkb_componentpath
pkb_configpath
```

An explicit blank value suppresses that inheritance and remains blank in canonical `runProfile`; replaying the blank preserves the same subsystem fallback behavior instead of turning the fallback into an explicit path. Optional normal RunVars do not leak into a controlled run.

`runProfileFingerprint` fingerprints the canonical final execution RunVars. Actual protected values participate only through one-way hash input while the retained profile stays sanitized. Use this fingerprint, not broader `configurationHash`, to test final RunVar equality.

Diagnostic lineage remains separate:

```text
pkb_investigation_id
pkb_run_purpose
pkb_parent_run_id
pkb_baseline_run_id
pkb_changed_variables
```

See `docs/ai-run-configuration.md` and `docs/diagnostic-lineage-metadata.md`.

## Diagnostic CLI

Supported command-line operations:

```text
DiagnosticCli guidance
DiagnosticCli export-guidance [output-directory]
DiagnosticCli discover-hint
DiagnosticCli emit-investigation <investigation-json-or--> <consumer-project-root>
DiagnosticCli compare-runs <left-run-index> <right-run-index> [output-json]
DiagnosticCli compare-fingerprints <left.pkbf> <right.pkbf> [output-json]
DiagnosticCli rebuild <diagnostic-runs-root-or-run-root>
```

`DiagnosticCli help`, `--help`, and `-h` print this same command list.

Prefer `DiagnosticCli` over custom Maven-classpath/JShell workflows for routine comparison and recovery.

`emit-investigation` writes a small human handoff under the consumer project:

```text
.pickleball/investigations/<pkb_investigation_id>/
    investigation.json    # source of truth
    report.html           # one-page local render
```

Input is investigation JSON from a file or stdin (`-`) plus the consumer project root. The command prints the project-relative `report.html` path. JSON is the source of truth. HTML renders that JSON plus at most two screenshots *linked* from the existing diagnostic pack; extra screenshot paths are ignored, and a missing image becomes a short note rather than a failed emit. The writer does not copy `reports/diagnostic-runs/` and does not change `pkb_diagnostic_output`. Headless Workbench MCP exposes the same emit as `workbench_investigation_emit` and returns only that relative report path.

Suggested investigation JSON fields, using existing lineage/diagnostic names where they already exist:

```text
pkb_investigation_id   # or investigationId
createdAt
scenario.name / scenario.feature / scenario.scenarioId
outcome          # cause-only | cause-and-fix
cause
fix              # text, or "not fixed"
category         # selector | gherkin | java | data | other
failureSignature
failureSite
runId            # or diagnosticRunId
runIndexPath     # pointer, not a copy
screenshots      # at most two project-relative PNG paths
pickleballVersion
```

Canonical names are `pkb_investigation_id` and `runId`. `investigationId` and `diagnosticRunId` are accepted aliases and are written back under the canonical names.

`export-guidance` does not manage or delete `.pickleball/investigations/`.

## Outcomes and completion

Run outcomes:

```text
PASSED
FAILED
NO_TESTS
UNKNOWN
```

Completion is separate:

```text
COMPLETE
INTERRUPTED
```

A single passing run is not automatically proof of stability or a fix.

## Failure clustering

For failures associated with structured steps, Pickleball records site-aware signatures combining normalized failure class/message with a stable failure-site key based on canonical feature source, Gherkin step line, and resolved definition method. Sparse evidence can include:

```text
failureSignature
failureSignatureVersion
failureSiteKey
failureSite.feature
failureSite.stepLine
failureSite.definition
```

Failures without a stable structured site deliberately use the class/message-only fallback. Signatures are investigation aids, not permanent identifiers or proof of one root cause.

## Scenario identity

Scenario matching uses multiple signals rather than one fragile key, including execution ID, URI, name, scenario/outline lines, tags, example values, exact-source key, semantic/name keys, and source-order hints. This supports duplicate names and Scenario Outline rows across changing launchers/source locations.

## Event ordering and deep trace

Events carry logical `eventSeq` and scenario-local sequence values. These logical sequences remain authoritative across normal and deep trace files.

`events.jsonl` stays plain for inexpensive navigation. TRACE/DEBUG log events use a raw append file while running and are losslessly compressed after controlled completion. Recovery understands either representation.

## Structured steps and capability observations

Diagnostic mode records structured step metadata and positive-only native capability observations. Definition origin can be `PICKLEBALL`, `NON_PICKLEBALL`, or `UNKNOWN`; `NON_PICKLEBALL` does not necessarily mean consumer code because third-party glue can also exist.

Capability semantics are asymmetric: presence means Pickleball positively observed the capability; absence does not prove it was unused.

## Screenshots and fingerprints

Diagnostic screenshot capture stores Selenium PNG bytes plus compact `.pkbf` fingerprints. Adjacent captures record `comparisonToPrevious` when available.

Rules for AI agents:

- do not open PNGs merely to determine whether they differ;
- use existing comparison metadata first;
- use Pickleball's fingerprint comparator for explicit/cross-run comparisons;
- `decodedPixelsExactlyEqual=true` establishes rendered-pixel equality;
- open a representative PNG only if interpreting the visible change matters;
- raw PNG byte inequality alone does not prove rendered pixels differ.

## Rebuild/recovery

`DiagnosticIndexRebuilder` can reconstruct derived indexes, clusters, catalogs, interrupted summaries, and recoverable fingerprint sidecars from surviving evidence. Use:

```text
DiagnosticCli rebuild <diagnostic-runs-root-or-run-root>
```

## Configuration/source provenance

`configuration.json` records effective configuration/provenance and the sanitized final run profile. Secret-like values are redacted; one-way hashes may be retained for comparison. `environment.json` captures focused runtime metadata. `source-provenance.json` records best-effort consumer/Pickleball source identity.

`pkb_gitsnapshot` supports `metadata`, `diff`, or `none` for consumer Git/source capture. Keep credentials out of remotes/evidence and never expose protected RunVar values in prompts or lineage.

For the full controlled execution contract, use [AI Run Configuration](ai-run-configuration.md). For the consumer workflow, use [Consumer Project](consumer-project.md).
