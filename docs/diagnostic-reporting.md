# Diagnostic Reporting

> **Executable checks:** [`internal-framework-java-checks.feature`](../maven-consumer-project/src/test/resources/features/internal-framework-java-checks.feature) runs the consumer-hosted diagnostic reporting checks.

Pickleball diagnostic reporting is an alternate capture pipeline intended for AI-assisted troubleshooting. It preserves lightweight indexes first and keeps dense event, screenshot, and fingerprint evidence deeper in the run directory so an agent can drill down only when needed.

## Enable diagnostic mode

Diagnostic mode requires one setting:

```properties
pkb_reportingmode=diagnostic
```

If the property is absent or has any value other than `diagnostic`, Pickleball uses its normal logging/reporting lifecycle.

The mode is resolved once during runner startup. Diagnostic mode:

- captures TRACE-through-ERROR diagnostic events regardless of console `pkb_loglevel`;
- keeps console verbosity controlled by `pkb_loglevel`;
- bypasses automatic Simple HTML and ReportPortal converter output;
- bypasses automatic framework XLSX/status-row output;
- keeps explicitly invoked reporting steps functional;
- captures Selenium screenshots as binary PNG bytes rather than the normal Base64 attachment path.

The resolved state is also exposed as `PickleballRunner.DIAGNOSTIC_MODE`.

## Report retention

`pkb_reportretention` controls automatic local report/evidence retention:

```properties
pkb_reportretention=all
```

Supported values are:

| Value | Behavior |
|---|---|
| `all` | Default. Retain all automatic report/evidence output. |
| `failed` | Retain dense scenario output only for failed or interrupted scenarios. |
| `none` | Do not retain automatic dense report/evidence output. |

Unknown values fall back to `all`.

In normal reporting mode, `failed` suppresses passing per-scenario HTML and writes automatic run-level HTML/XLSX files only when a problem scenario exists. ReportPortal is not controlled by this local-file retention setting. Explicit report steps are never suppressed by `pkb_reportretention`.

In diagnostic mode, evidence is written while the scenario is running because its final status is not yet known. After a clean PASS:

- `all` keeps the complete scenario evidence;
- `failed` prunes the detailed scenario JSONL, screenshots, fingerprints, and other dense evidence while retaining the scenario summary/index;
- `none` prunes dense evidence for every scenario while retaining the minimal run/scenario navigation indexes.

Failed or interrupted scenarios are retained by `failed`.

## Diagnostic run layout

By default, runs are written beneath:

```text
reports/diagnostic-runs/<runId>/
```

The shared root also contains `run-catalog.json`, a compact multi-run catalog used to choose candidate runs before opening any run directory.

The top layer inside each run is intentionally small:

```text
manifest.json
run-index.json
run-events.jsonl
configuration.json
environment.json
clusters.json
scenarios/
```

Each scenario has a lightweight `summary.json`. When dense evidence is retained it also has an append-only `events.jsonl`, plus `screenshots/` and `fingerprints/` directories.

`run-index.json` contains comparison-oriented metadata such as effective browser/environment/tag/options values, deterministic configuration/environment/dependency/selection/source fingerprints, runtime information, scenario identities, outcomes, durations, failure signatures, representative screenshot references, and links to deeper evidence. Pickleball does not assign a run-level comparability classification; an AI agent can decide which runs are relevant by reading `run-catalog.json` and these inexpensive indexes first. `DiagnosticRunComparator` first compares two run indexes without opening dense scenario JSONL or screenshots. After it matches scenarios, it may read at most four matching representative fingerprint pairs per matched scenario to produce compact cross-run visual transitions; it still never opens the full screenshots. `DiagnosticIndexRebuilder` can rebuild interrupted scenario summaries, missing fingerprint sidecars, the derived run index, failure clusters, and shared run catalog from surviving diagnostic evidence.

## Outcomes

Run outcomes are:

- `PASSED` — at least one scenario executed and every completed scenario passed;
- `FAILED` — at least one scenario failed;
- `NO_TESTS` — no scenarios executed;
- `UNKNOWN` — no failure was established but at least one scenario outcome could not be established.

Scenario/run completion is tracked separately from outcome. A scenario that exits through an unexpected runner-level exception is `INTERRUPTED`; normal completion is `COMPLETE`.

The old `MIXED` classification is not used. Exact scenario counts remain in the run index.

## Scenario identity

Every top-level scenario logs its source identity at normal INFO level and records the same data structurally in diagnostic mode. Identity uses multiple signals rather than one fragile key:

- per-run `scenarioExecutionId`;
- full feature URI;
- scenario name;
- scenario-definition line;
- example-row line for Scenario Outlines;
- sorted tags and tag hash;
- normalized example-values hash;
- exact source key derived from URI and source lines;
- semantic/name keys for cross-run matching;
- source line as an ordering hint.

The name key is independent of the feature URI, while the semantic and exact-source keys preserve stronger location context. The comparator uses weighted exact-source, semantic, name, example-value, tag, and source-order signals so renamed or moved scenarios can still be matched when enough other evidence agrees.

This deliberately supports duplicate scenario names in one feature and distinguishes Scenario Outline rows. Cross-run analysis can combine these signals instead of assuming that a single name or line number is permanently stable.

Nested/component/service-call scenario invocations log caller and callee source information and receive their own invocation IDs.

## Event ranges

V1 diagnostic JSONL remains uncompressed. Every event records schema/run identity, wall-clock and monotonic timestamps, thread, and a monotonically increasing `eventSeq`; scenario evidence also receives a `scenarioSeq`. Indexes refer to logical sequence ranges/counts rather than byte offsets, so compression can be added later without changing the reference model.

## Screenshots and fingerprints

The existing browser-step lifecycle already records an after-step screenshot only when a WebDriver was used. Diagnostic mode keeps that browser-only cadence and redirects the capture to Selenium `OutputType.BYTES`, avoiding the normal Base64 duplicate. A best-effort failure screenshot is also captured before scenario cleanup. Explicit screenshot APIs continue to work.

Service-only and mapping-only steps do not receive automatic screenshots.

V1 fingerprints:

- decode PNG/JPEG with Java ImageIO; WebP is deferred;
- alpha-composite onto white before sampling;
- use a deterministic `64 × 36` Y/Cb/Cr area grid;
- include a `32 × 18` edge grid, normalized 4×4×4 color histogram, dHash, dimensions, and SHA-256;
- serialize to a versioned compact binary sidecar of roughly 7–8 KB for typical screenshots.

The comparison engine reads fingerprint sidecars without reopening screenshots. It returns small similarity/category results (`IDENTICAL`, `VERY_SIMILAR`, `SOMEWHAT_SIMILAR`, `VERY_DIFFERENT`). Scenario summaries keep at most eight representative screenshots such as the first visual state, significant visual changes, failures, and final visual state. An AI agent normally consumes only those summaries and opens a full screenshot when the comparison indicates it is useful.

## Configuration provenance and environment

`configuration.json` stores execution-relevant effective settings with their winning source when that source can be observed during runner merging. Secret-like keys are redacted and retain only a one-way value hash so an agent can tell that two secret values differ without learning either value. Large diagnostic text/maps/collections are bounded and carry truncation markers rather than expanding without limit. The run index contains only the smaller subset useful for deciding whether runs are related.

`environment.json` intentionally stores a focused runtime summary such as Java/OS/architecture/timezone/processor/container hints. It does not copy the full `PlatformSnapshot` workstation/user/network inventory. The manifest and run boundary events also contain lightweight heap/process CPU snapshots at run start and end; V1 does not start a background resource sampler.

Optional external lineage values (`pkb_investigation_id`, `pkb_run_purpose`, `pkb_parent_run_id`, `pkb_baseline_run_id`, and `pkb_changed_variables`) are copied into the lightweight run metadata when supplied. None is required to enable diagnostic mode.

## Failure safety and recovery

Diagnostic evidence failures do not change the test result. The run is marked with `evidenceIntegrity=PARTIAL` and a concise error is written to stderr.

JSONL is appended as execution proceeds and index/summary files are replaced atomically. If a prior run still has a `RUNNING` manifest when a new diagnostic run starts, it is marked `UNKNOWN` / `INTERRUPTED`; already-written evidence remains available. Derived navigation artifacts are intentionally rebuildable with `DiagnosticIndexRebuilder`.

[Previous: Execution configuration](configuration.md) · [Documentation home](README.md)
