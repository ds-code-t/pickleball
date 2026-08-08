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
source-provenance.json
clusters.json
scenarios/
```

Each scenario has a lightweight `summary.json`. When dense evidence is retained it also has an append-only plain `events.jsonl`. TRACE/DEBUG log records are written live to `trace.jsonl` and, after controlled scenario completion, converted losslessly to `trace.jsonl.gz`. An interrupted process may therefore leave the raw `trace.jsonl`; recovery understands either form. `screenshots/` and `fingerprints/` are created lazily only when visual evidence is actually captured.

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

## Event ranges and deep trace evidence

Every event records schema/run identity, wall-clock and monotonic timestamps, thread, and a monotonically increasing global `eventSeq`; scenario evidence also receives a `scenarioSeq`. Those logical sequence values remain authoritative even though events may live in two physical files.

`events.jsonl` stays plain for inexpensive AI navigation and contains lifecycle, structured step, screenshot, failure, INFO-and-higher, and other non-deep events. TRACE/DEBUG log events retain their full event metadata and exact logical ordering in `trace.jsonl.gz` after normal scenario completion. `summary.json` describes the trace path, `contentEncoding`, event count, and first/last global sequence values so an agent can discover the deeper evidence without opening it. This is a storage/access-layer optimization, not filtering: `pkb_reportretention=all` retains the same TRACE-through-ERROR evidence.

The raw `trace.jsonl` is used while a scenario is running so append/recovery behavior remains simple. It is gzip-compressed only after controlled completion. If execution is interrupted before that point, the raw file remains valid diagnostic evidence and `DiagnosticIndexRebuilder` can consume it directly.

## Structured steps and native capability observations

Diagnostic mode records a compact structured `step` event for each executed Pickleball/Cucumber step entry. Scenario and run indexes roll those records up into counts for executed/passed/failed/skipped/other steps and for definitions resolved from Pickleball versus outside the Pickleball artifact.

Resolved step-definition metadata includes `origin` (`PICKLEBALL`, `NON_PICKLEBALL`, or `UNKNOWN`), declaring class, method, Cucumber code location, repository/source-path pointers when available, source or class-binary hashes, and the relevant source commit/reproducibility state. `NON_PICKLEBALL` deliberately does not mean “consumer” in every case because a third-party glue library may also supply a step definition.

Pickleball also records positive-only, expandable `nativeCapabilitiesObserved` values at step/scenario/run scope. Initial capabilities include browser WebDriver initialization/use, browser navigation/DOM/screenshot activity, nested/component scenarios, service-call scenarios, and native HTTP execution. Capability counts are rolled up into lightweight indexes.

The semantics are intentionally asymmetric: **presence means native Pickleball instrumentation positively observed the capability; absence does not prove the capability was unused.** A consumer-defined Cucumber step can use Selenium, HTTP clients, desktop automation, or future integrations without passing through Pickleball's native instrumentation. Dotted capability names make the set extensible for future functionality without changing the surrounding schema.

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

## Git and source provenance

`source-provenance.json` records best-effort source identity for the consumer repository plus build-time provenance embedded in the Pickleball Maven artifact. For Git-backed sources it can include repository name/remote/web URL, branch, commit hash, commit message, dirty state, a one-way working-tree difference hash, and whether the committed revision alone is sufficient to reproduce the source. The Pickleball entry additionally records the framework version and artifact SHA-256 when available.

Feature/scenario summaries carry the original feature URI, source line, repository-relative path and source hash when the file can be resolved. Structured step events carry the resolved Java definition class/method and source pointer. This lets an external AI retrieve the historical Gherkin and Java source at the recorded commit rather than accidentally analyzing today's branch. A dirty repository is explicitly marked `reproducibleFromGit=false`; the commit alone must not be treated as the exact executed source.

`pkb_gitsnapshot=metadata` is the default and stores metadata/hashes only. `pkb_gitsnapshot=none` disables live consumer Git inspection. `pkb_gitsnapshot=diff` additionally stores `source/consumer-working-tree.patch.gz` for a dirty consumer checkout so uncommitted changes can be retained when that is acceptable. HTTP(S) credentials embedded in Git remote URLs are removed before persistence.

The Pickleball build embeds `META-INF/pickleball-build.properties`, so a consumer using Pickleball only as a Maven dependency can still identify the framework source revision without having a Pickleball Git checkout on that machine.

## Configuration provenance and environment

`configuration.json` stores execution-relevant effective settings with their winning source when that source can be observed during runner merging. Secret-like keys are redacted and retain only a one-way value hash so an agent can tell that two secret values differ without learning either value. Large diagnostic text/maps/collections are bounded and carry truncation markers rather than expanding without limit. The run index contains only the smaller subset useful for deciding whether runs are related.

`environment.json` intentionally stores a focused runtime summary such as Java/OS/architecture/timezone/processor/container hints. It does not copy the full `PlatformSnapshot` workstation/user/network inventory. Existing platform/caller stamps used by normal logs and external reporting remain independent and are preserved by default because repeated caller identity can be operationally important when scenarios run in different environments or report to external systems. `pkb_platformlog` can explicitly select `default`, `default+git`, `none`, `keys:...`, or `template:...` behavior without changing the default contract.

The manifest and run boundary events also contain lightweight heap/process CPU snapshots at run start and end; V1 does not start a background resource sampler.

Optional external lineage values (`pkb_investigation_id`, `pkb_run_purpose`, `pkb_parent_run_id`, `pkb_baseline_run_id`, and `pkb_changed_variables`) are copied into the lightweight run metadata when supplied. None is required to enable diagnostic mode.

## Failure safety and recovery

Diagnostic evidence failures do not change the test result. The run is marked with `evidenceIntegrity=PARTIAL` and a concise error is written to stderr.

Plain JSONL is appended as execution proceeds and index/summary files are replaced atomically. Deep TRACE/DEBUG evidence remains raw JSONL while active and is compressed only after controlled scenario completion. If a prior run still has a `RUNNING` manifest when a new diagnostic run starts, it is marked `UNKNOWN` / `INTERRUPTED`; already-written evidence remains available. `DiagnosticIndexRebuilder` understands raw or gzip trace evidence and can reconstruct derived navigation metadata without expanding the compressed artifact on disk.

[Previous: Execution configuration](configuration.md) · [Documentation home](README.md)
