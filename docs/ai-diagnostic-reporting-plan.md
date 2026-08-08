# AI Diagnostic Reporting, Visual Evidence, and Multi-Run Investigation Plan

## 1. Purpose

Implement an exclusive diagnostic reporting system for Pickleball that captures complete, structured test evidence while minimizing:

- Heap usage.
- Report-generation overhead.
- Storage duplication.
- Logging contention.
- AI token consumption.
- Repeated screenshot analysis.
- Unnecessary test reruns.

The system must allow an AI agent or developer to:

- Identify failures quickly from lightweight indexes.
- Investigate individual scenarios.
- Correlate issues across parallel scenarios.
- Distinguish scenario-specific failures from shared infrastructure or application failures.
- Compare multiple test runs under controlled conditions.
- Reproduce intermittent issues.
- Isolate root causes by changing one condition at a time.
- Validate candidate fixes.
- Detect regressions after changes.
- Retrieve detailed traces and images only when higher-level evidence indicates that they are necessary.
- Recover useful evidence after interrupted or crashed runs.

## 1.1 Pickleball 2.1.3 Implementation Decisions

The following decisions are authoritative for the 2.1.3 implementation and supersede older wording later in this plan where necessary:

- `pkb_reportingmode=diagnostic` is the only setting required to enable diagnostic mode. Missing or other values use normal reporting. The mode is resolved once at runner startup and exposed as a public static runtime state.
- Diagnostic mode uses a separate append-only evidence pipeline. Normal converter output, automatic HTML/ReportPortal output, automatic framework XLSX/status-row output, completed report-tree retention, and Base64 screenshot copies are bypassed. Existing logging APIs remain source-compatible.
- Diagnostic files capture TRACE-through-ERROR evidence regardless of `pkb_loglevel`; console verbosity still follows `pkb_loglevel`.
- `pkb_reportretention=all|failed|none` controls automatic local report/evidence retention and defaults to `all`. Explicit reporting steps remain functional. In diagnostic `failed` mode, clean-PASS scenarios retain only lightweight summaries/index entries while dense JSONL, screenshots, and fingerprints are pruned after completion.
- Logs and indexes are not immutable. JSONL is appended during execution; manifests, summaries, catalogs, and derived indexes may be atomically updated/rebuilt.
- Run outcomes are `PASSED`, `FAILED`, `NO_TESTS`, or `UNKNOWN`; completion is tracked separately. `MIXED` is not used.
- Pickleball does not assign a run-level comparability class. The run catalog/index exposes inexpensive browser, environment, configuration, selection, time, scenario-set, and runtime metadata so an AI can decide which runs are relevant and how strongly they can be compared before reading deeper evidence.
- Scenario identity uses multiple signals: execution/invocation ID, full feature URI, scenario name, scenario-definition line, Scenario Outline example line, sorted tags/tag hash, normalized example-values hash, exact source key, semantic/name keys, and source ordering hints. Duplicate names and outline rows are therefore distinguishable without making line number the only cross-run key.
- Top-level scenario source identity and nested caller/callee source identity are logged at normal INFO level and recorded structurally in diagnostic events.
- Navigation JSONL remains plain. Full-fidelity TRACE/DEBUG log events are written live as raw `trace.jsonl` and losslessly gzip-compressed to `trace.jsonl.gz` after controlled scenario completion. Interrupted raw trace remains valid. References use logical global `eventSeq` and scenario `scenarioSeq`, never byte offsets.
- Diagnostic screenshots use Selenium binary PNG bytes. Existing browser-visible after-step capture cadence is preserved, a best-effort failure screenshot is added before cleanup, explicit screenshot APIs remain supported, and service-only/mapping-only steps are not auto-captured.
- V1 visual fingerprints use deterministic 64×36 Y/Cb/Cr sampling, a 32×18 edge grid, normalized 4×4×4 color histogram, dHash, dimensions, and canonical-pixel SHA-256. Expected storage is roughly 7–8 KB. PNG is required; JPEG is accepted through ImageIO; WebP is deferred.
- Fingerprints are consumed by comparison code, not directly by an AI. AI-facing indexes contain compact similarity/category results and references to full screenshots.
- Configuration provenance records execution-relevant effective values and their observed winning source in a deeper configuration artifact. Run indexes expose only a smaller comparison-oriented subset. Secret-like values are redacted and retain only a one-way comparison hash.
- Environment capture is focused on debugging-relevant runtime data and does not directly persist the full workstation/user/network `PlatformSnapshot`.
- Top-level comparison metadata includes deterministic configuration, environment, source-control, test-selection, scenario-source, and dependency fingerprints; consumer/Pickleball Git provenance and framework artifact identity are cheap comparison dimensions. V1 resource measurements are lightweight run-start/run-completion heap/process CPU snapshots rather than a background sampler.
- Diagnostic values are bounded before JSON persistence; large text/collections are marked when truncated, and focused environment capture explicitly omits unrelated workstation/system data.
- Derived indexes, clusters, catalogs, interrupted scenario summaries, and missing fingerprint sidecars can be rebuilt from surviving metadata/JSONL/screenshots with `DiagnosticIndexRebuilder`.
- Pickleball 2.1.3 produces evidence for external AI/developer investigation. Autonomous agent command execution, permissions, source changes, and dependency installation are outside the framework implementation scope for this release.

## 2. Diagnostic Architecture

The framework uses the following V1 hierarchy:

```text
run catalog
  -> durable diagnostic runs with append-only event streams
      -> run index
          -> scenario summaries and failure clusters
              -> relevant event ranges
                  -> screenshot fingerprints
                      -> screenshots and detailed evidence
```

Each level supports progressive disclosure. External AI tooling may build its own investigation/session state on top of these artifacts; Pickleball does not own that orchestration in 2.1.3.

An AI should normally consume evidence in this order:

```text
run-catalog.json
-> selected run indexes / lightweight comparison
-> failure cluster or scenario summary
-> relevant event range
-> fingerprint comparison result
-> representative screenshot
-> full trace or additional evidence only when required
```

## 3. Diagnostic Reporting Mode

The feature will be implemented as a reporting mode, not as a new logging severity.

```text id="l805l5"
reporting mode: DIAGNOSTIC
capture level: TRACE
```

The standard logging levels remain:

```text id="pbx4e7"
TRACE
DEBUG
INFO
WARN
ERROR
```

Diagnostic mode changes the capture, storage, durability, artifact, and reporting pipeline.

It may record structured evidence that does not naturally belong to a severity below `TRACE`, including:

- Selector attempts and rejection reasons.
- Retry counts and timings.
- Browser state transitions.
- Request and response metadata.
- Mapping and expression diagnostics.
- Screenshot and fingerprint references.
- Shared-resource lifecycle events.
- Correlation relationships.
- Expected and actual state transitions.
- Resource usage measurements.
- Test-selection and environment metadata.

Structured diagnostic events are preferred over large volumes of loosely related text messages.

## 4. Exclusive Operation

Diagnostic mode is mutually exclusive with Pickleball’s normal report sinks.

When diagnostic mode is active, Pickleball will disable:

- Composite HTML reports.
- Individual scenario HTML reports.
- ReportPortal forwarding.
- In-memory retention of completed report trees.
- Base64 screenshot copies.
- Unnecessary verbose console duplication.

A minimal console channel will remain available for:

- Run and scenario progress.
- Warnings and errors.
- Diagnostic-writer failures.
- Fatal startup and shutdown failures.
- Diagnostic output location.
- Final run status.

Pickleball will not claim to suppress logging produced independently by the JVM, Selenium, Cucumber, consumer applications, or third-party logging frameworks.

## 5. Investigation Sessions

External AI/developer tooling may organize multiple Pickleball runs into an **Investigation Session**. Pickleball 2.1.3 does not own or persist a separate investigation state machine. Its responsibility is to make each run cheap to identify, compare, and drill into.

External tooling may categorize runs as baseline, reproduction, control, controlled-variation, candidate-fix, fix-verification, or regression runs and may maintain hypotheses, comparisons, conclusions, commands, permissions, budgets, and stop conditions outside Pickleball.

Pickleball can preserve optional lineage values supplied by that tooling so the relationship is visible from `manifest.json`, `run-index.json`, and `run-catalog.json`. Raw event streams are append-oriented while active; manifests, indexes, summaries, catalogs, clusters, comparisons, and external investigation conclusions are mutable/rebuildable artifacts.

## 6. Run Identity and Classification

Every execution will have a unique `runId` and an associated `investigationId` when part of an investigation.

Each run will be classified across separate dimensions.

### 6.1 Run purpose

```text id="vmhvir"
BASELINE
REPRODUCTION
CONTROL
DIAGNOSTIC_VARIATION
CANDIDATE_FIX
FIX_VERIFICATION
REGRESSION
```

### 6.2 Completion

```text
COMPLETE
INTERRUPTED
```

A previously active run discovered during recovery may be marked `INTERRUPTED`. More detailed setup/crash causes belong in structured events and failure metadata rather than a competing outcome enum.

### 6.3 Run outcome

```text
PASSED
FAILED
NO_TESTS
UNKNOWN
```

Definitions:

- `PASSED`: at least one scenario executed and all established scenario outcomes passed.
- `FAILED`: at least one scenario failed.
- `NO_TESTS`: no scenario executed.
- `UNKNOWN`: no failure was established but at least one scenario outcome could not be established.

Exact passed/failed/unknown counts remain in the run index; `MIXED` is not used.

### 6.4 Cross-run stability and comparability

Stability and comparability are **derived judgments**, not stored run-level classifications. A run is only comparable relative to another run, and stability requires evidence across runs.

The lightweight run catalog/index therefore exposes the execution conditions needed to judge relevance before deeper evidence is consumed, including browser, environment, effective configuration fingerprint, test selection, runtime/platform summary, timestamps, scenario identities, outcomes, and failure signatures.

An AI or comparison tool may then describe comparison confidence and the reasons for it without Pickleball assigning a comparability enum to the run itself.

A single passing run must not automatically be considered stable or sufficient proof of a fix.

## 7. Run Lineage

Lineage is optional. When external tooling supplies the corresponding Pickleball properties, the run records:

```text
investigationId
runPurpose
parentRunId
baselineRunId
changedVariables
```

These values come from optional `pkb_investigation_id`, `pkb_run_purpose`, `pkb_parent_run_id`, `pkb_baseline_run_id`, and `pkb_changed_variables` settings. None is required to enable diagnostic mode.

More detailed hypothesis IDs, agent-action IDs, comparison sets, and unexpected-difference annotations belong to external investigation tooling in 2.1.3.

`changedVariables` may describe intentional differences from a parent or baseline run, for example browser version, parallelism, test data, environment, source revision, test selection, or a candidate fix. The actual effective run metadata remains authoritative for detecting unexpected differences.

## 8. Diagnostic Output Structure

The V1 framework-owned structure is:

```text
reports/diagnostic-runs/
    run-catalog.json

    <run-id>/
        manifest.json
        run-index.json
        run-events.jsonl
        configuration.json
        environment.json
        clusters.json

        scenarios/
            <scenario-execution-id>/
                summary.json
                events.jsonl
                screenshots/
                    <screenshot-id>.png
                fingerprints/
                    <screenshot-id>.pkbf
```

`pkb_diagnostic_output` may optionally move the `diagnostic-runs` root. Comparison output may be written wherever the caller chooses. Investigation, hypothesis, action, and conclusion files are external/tooling concerns for 2.1.3 rather than framework-owned artifacts.

`events.jsonl` remains plain for navigation. TRACE/DEBUG `log` events preserve their full structured metadata in a separate deep trace stream. The active writer uses `trace.jsonl`; controlled scenario completion replaces it with lossless `trace.jsonl.gz`. Logical `eventSeq` / `scenarioSeq` values span both files, so physical compression does not alter execution ordering or references.

Active files must remain appendable and recoverable.

While retained, JSONL streams and binary artifacts are the deepest evidence source. Scenario summaries are intentionally retained when `pkb_reportretention` prunes dense passing evidence, so recovery/rebuild logic uses the best surviving layer rather than assuming raw files always remain. Catalogs, run indexes, clusters, and comparisons are derived/rebuildable.

## 9. Run Manifest

Every run has an updateable `manifest.json` containing the small amount of information needed to orient an investigation before opening scenario evidence.

### 9.1 Run timing and lifecycle

V1 records:

- Run ID.
- Start and stop time.
- Duration when complete.
- Outcome and independent completion state.
- Shutdown/recovery reason.
- Evidence integrity (`COMPLETE` or `PARTIAL`).
- Timezone.
- Monotonic run origin.
- Process ID and worker thread.
- Report retention policy.
- Optional external lineage metadata when supplied.

### 9.2 Source, dependency, and framework identity

V1 records Git/source provenance in addition to compact hashes. The consumer working tree is inspected best-effort, while Pickleball embeds its own build-time Git revision into the Maven artifact so a consumer does not need a Pickleball checkout:

- Pickleball implementation version when package metadata exposes it; otherwise `unknown` rather than a guessed value.
- Deterministic dependency/classpath fingerprint based on dependency file names and sizes, without persisting absolute paths.
- Common CI source revision when exposed by supported CI environment variables.
- Scenario-source fingerprint based on the executed scenario source identities.

Repository name, branch, dirty-tree state, full source diff, build command, and consumer-project version are not guessed. External tooling may attach those values through its own investigation metadata if available.

### 9.3 Test selection

`configuration.json` preserves execution-relevant effective Pickleball/Cucumber settings, including feature selectors, tags, name filters, parallelism, and other applicable options. `run-index.json` exposes the smaller subset used for fast run comparison plus a deterministic test-selection fingerprint.

The actual executed scenario set is represented by the scenario entries accumulated in the run index; Scenario Outline rows keep their example-row identity.

### 9.4 Stable scenario identity

No single field is treated as a permanent scenario primary key. Each scenario records complementary signals:

```text
scenarioExecutionId
featureUri
scenarioName
scenarioLine
exampleLine
tags + tagKey
exampleValuesHash
exactSourceKey
semanticKey
nameKey
sourceOrderHint
```

`nameKey` is independent of feature URI. `semanticKey` keeps URI/name/example-value context. `exactSourceKey` uses URI and source lines. The comparator combines these signals with source-order proximity instead of depending on one fragile key.

### 9.5 Effective Pickleball configuration

`configuration.json` stores execution-relevant effective settings after precedence is resolved. For each retained property it records:

```text
property name
effective value or protected representation
observed winning source
whether it appears to be a framework/default source
whether it was redacted
one-way value hash when redacted
```

Secret-like configuration values are never persisted in clear text. Raw JVM/environment properties unrelated to test execution are intentionally omitted.

### 9.6 Runtime environment

`environment.json` is deliberately focused and records values useful for debugging and run relevance, including:

- Java version/vendor.
- OS name/version/architecture.
- Locale and timezone.
- Processor count and maximum heap.
- CI/container hints.
- Environment fingerprint.

Browser/environment/test-selection values are taken from the effective execution configuration and exposed in the run comparison metadata. Pickleball does not dump user name, user home, host network interfaces, or the complete `PlatformSnapshot` into diagnostic artifacts.

## 10. Run-Level Fingerprints

Each run exposes compact deterministic hashes for cheap equality/relevance checks.

### 10.1 Scenario-source fingerprint

Represents the sorted semantic/exact source identities of scenarios observed in the run. It changes when the executed source scenario set changes.

### 10.2 Dependency fingerprint

Represents the effective Java classpath using dependency file names and sizes. Absolute classpath locations are not persisted.

### 10.3 Configuration fingerprint

Represents the sanitized execution-relevant effective configuration. Capture timestamps are excluded so identical configurations produce the same hash across runs.

### 10.4 Environment fingerprint

Represents the focused Java/OS/locale/timezone/processor/container/CI environment summary.

### 10.5 Test-selection fingerprint

Represents configured feature selectors, tag/name filters, parallelism, and applicable Pickleball/Cucumber options.

These hashes provide fast equality checks while normalized fields remain available to explain important differences.

## 11. Resource Measurements

V1 captures lightweight resource snapshots at run boundaries rather than starting a background sampler. This keeps diagnostic overhead predictable while still exposing useful context for cross-run comparison.

### 11.1 Run-start measurements

Capture:

- Heap currently used.
- Heap currently committed.
- Maximum configured heap.
- Available processors.
- Process CPU duration when the JVM exposes it.

The focused `environment.json` separately records Java/OS/architecture/timezone, processor count, maximum heap, CI/container hints, and an environment fingerprint.

### 11.2 Runtime measurements

V1 does **not** start a periodic resource-sampling thread. Scenario and framework logs remain the primary source for runtime incidents. If later evidence shows that periodic sampling materially improves diagnosis, it may be added as a bounded optional feature without changing the event/index reference model.

### 11.3 Run-completion measurements

Capture the same lightweight heap/process CPU snapshot again at normal run completion. The start/end snapshots are written into the run manifest and run-boundary events so an agent can inspect them only when relevant.

## 12. Capture and Memory Model

Diagnostic evidence is written incrementally instead of building a complete report tree in memory.

The normal logging API remains source-compatible, but in diagnostic mode:

- normal converters are not attached to scenario entries;
- converter lifecycle callbacks are suppressed;
- completed child `Entry` objects are removed from their parent collection after creation while the local object remains usable by existing call sites;
- scenario evidence is appended directly to its JSONL stream;
- only active scenario/nested context, sequence counters, compact summaries, and the immediately previous visual fingerprint remain in diagnostic state.

Screenshot fingerprint generation is synchronous in the scenario execution thread, so its concurrency is naturally bounded by the configured scenario concurrency and no separate unbounded processing queue is created.

## 13. Event Format

Events are independent JSON Lines records. Common V1 fields are:

```text
schemaVersion
runId
eventSeq
timestamp
monotonicOffsetNanos
thread
type
```

Scenario events additionally include:

```text
scenarioExecutionId
scenarioSeq
nestedInvocationId   # when inside a nested/component invocation
```

Log events add the applicable entry ID, parent entry ID, level, status, sanitized text, tags, fields, and attachment count. Scenario/run boundary, nested-call, screenshot, and diagnostic-error events add their own structured fields.

Large text/maps/collections are bounded before persistence and carry explicit truncation markers. Secret-like assignments/fields are redacted.

### 13.1 Ordering

- `scenarioSeq` defines deterministic logical order within one scenario.
- `eventSeq` provides a run-local increasing event reference.
- `monotonicOffsetNanos` supports same-JVM timing correlation without depending on wall-clock adjustments.
- UTC timestamps support human/external interpretation.
- Nested invocation IDs provide explicit call lineage inside reusable component/service scenarios.

Indexes reference scenario logical sequence ranges/counts, never byte offsets.

## 14. Parallel Scenario Handling

Each active scenario writes to its own `events.jsonl`, using a per-file append lock. Different scenarios therefore avoid a shared high-contention writer. Run-scoped events use `run-events.jsonl`.

Framework logs/events emitted with no active scenario context naturally go to the run stream. Scenario streams carry their own execution IDs and sequence numbers, and nested reusable-scenario invocations carry nested invocation IDs.

This design keeps parallel evidence separable while still allowing the lightweight run index to correlate outcomes, failure signatures, timings, and scenario identities across the run.

## 15. Durability and Crash Recovery

V1 writes each JSONL event with an append operation as execution proceeds. Scenario summaries, manifests, catalogs, clusters, and run indexes are written through atomic replace where the filesystem supports it. Diagnostic mode does not force a physical disk synchronization after every trace event.

There is no unbounded asynchronous event queue. Per-file append locks serialize writers to the same JSONL file while allowing independent scenario files to progress concurrently.

Each JSONL record is independently recoverable. A recovery reader discards malformed/incomplete records while retaining every valid record around them.

The manifest records outcome and completion independently. A run whose manifest still says `RUNNING` when a later diagnostic run starts is recovered as `UNKNOWN` / `INTERRUPTED`.

### 15.1 Recovery processing

`DiagnosticIndexRebuilder` can:

- Read surviving manifest, configuration, and environment metadata.
- Read valid scenario JSONL records while ignoring an incomplete/malformed trailing record.
- Mark scenarios without a terminal event as `UNKNOWN` / `INTERRUPTED`.
- Reconstruct a missing or stale scenario summary from surviving scenario events.
- Discover surviving screenshots and generate missing fingerprint sidecars.
- Rebuild the run index.
- Rebuild failure clusters.
- Rebuild the shared run catalog.

Pairwise comparison output remains derived and may be regenerated with `DiagnosticRunComparator` from rebuilt run indexes.

## 16. Lightweight Run Index

The AI should begin with `run-index.json`, not raw trace files.

V1 contains:

- Schema version, run ID, outcome, completion, start time, retention policy, and evidence integrity.
- Configuration, environment, dependency, selection, and executed-scenario source fingerprints.
- Small comparison metadata: browser/environment/filter/options values, Java/OS/timezone, optional CI source revision, and Pickleball package version when available.
- Exact passed/failed/unknown counts.
- Compact scenario entries with identities, outcomes, durations, failure signatures, representative screenshot references, and logical event ranges.
- Optional external lineage metadata.
- Paths to manifest, configuration, environment, run-events, clusters, and the shared run catalog.

The index deliberately references rather than duplicates dense events, full screenshot arrays, configuration provenance, failure-cluster membership, and run-boundary resource details. Those layers are opened only when needed.

## 17. Scenario Summary

Each scenario keeps a small `summary.json` and a corresponding compact run-index entry containing:

- `scenarioExecutionId` and the multi-signal source identity.
- Outcome and completion state.
- Start/stop time and duration.
- Event count and logical scenario sequence range.
- Whether detailed evidence was retained or pruned by `pkb_reportretention`.
- Screenshot count.
- A bounded representative-screenshot list.
- Failure class/message/signature when available.
- References to its summary and retained JSONL stream.

Detailed step/phrase logs, browser state, service-call detail, bounded exception/cause/stack-frame failure records, and individual screenshot comparison results remain in the scenario JSONL. The summary intentionally does not duplicate those dense records.

## 18. Failure Signatures and Within-Run Correlation

V1 failure signatures are deterministic hashes of:

- Exception class.
- Sanitized/normalized exception message, with volatile numeric and hexadecimal values reduced.

`clusters.json` groups scenarios sharing the same signature and records the affected scenario execution IDs/count. This is intentionally conservative: Pickleball supplies evidence-linked clustering without claiming that the signature proves one root cause.

`DiagnosticRunComparator` derives cluster transitions (`NEW`, `RESOLVED`, `PERSISTENT`, `COUNT_CHANGED`) between two run indexes. External AI/developer tooling can open the linked scenario evidence when it needs to decide whether visually/logically similar failures actually share a cause.

## 19. Screenshot Storage

Diagnostic browser capture bypasses the normal Base64 attachment storage path.

For each capture V1:

1. Selenium returns PNG bytes with `OutputType.BYTES` (or an existing explicit Base64 screenshot API is decoded in memory).
2. Pickleball writes one binary screenshot file.
3. The screenshot is decoded once for fingerprint generation.
4. The compact `.pkbf` sidecar is written.
5. The scenario JSONL receives the screenshot reference, dimensions, byte sizes, current URL/title when available, and comparison-to-previous summary.
6. Decoded image/fingerprint working state is released except for the immediately previous compact fingerprint.

No `.b64` duplicate is created in diagnostic mode. A best-effort failure screenshot is taken before scenario cleanup. Passing-scenario screenshot/fingerprint directories are removed after completion when `pkb_reportretention=failed`; `none` prunes dense evidence for every scenario.

If a crash leaves a screenshot without its sidecar, `DiagnosticIndexRebuilder` can regenerate the missing fingerprint from the surviving image.

## 20. Visual Fingerprint

The screenshot system will implement a deterministic, versioned fingerprint compatible with common browser screenshot formats.

The original image must never be modified or replaced.

Fingerprint generation may temporarily decode the image into:

- 8-bit sRGB.
- Fixed RGB channel order.
- Deterministic row-major traversal.
- Transparent pixels composited onto white.
- Fixed fingerprint dimensions independent of source resolution.

The fingerprint will include:

```text id="q1ze82"
version
sourceFormat
compressionFamily
originalWidth
originalHeight
decodedPixelSha256
fineYcbcrGrid
edgeGrid
colorHistogram
dHash
```

Recommended dimensions:

- Fine grid: `64 × 36`, with one byte each for Y, Cb, and Cr per cell.
- Edge grid: `32 × 18`, one byte per cell.
- Color histogram: normalized `4 × 4 × 4` RGB bins stored as unsigned 16-bit values.
- 64-bit dHash plus canonical decoded-pixel SHA-256 and original dimensions.

Expected V1 fingerprint size is approximately `7–8 KB` per screenshot.

## 21. Fingerprint Components

### 21.1 Decoded-pixel SHA-256

Provides a fast exact-pixel match after canonical decoding.

Matching decoded-pixel hashes mean that the screenshots have identical canonical pixels even if encoded file bytes or metadata differ.

### 21.2 Fine luminance and chroma grids

Detect small UI changes such as:

- Checkbox states.
- Focus or hover highlights.
- Text entry.
- Button selection.
- Small icons.
- Validation messages.
- Status banners.

Luminance receives greater comparison weight because text, borders, and controls are often represented primarily by brightness differences.

### 21.3 Edge grid

Preserves information about:

- Text density.
- Borders.
- Controls.
- Panels.
- General UI structure.

### 21.4 Color histogram

Measures broad palette and background similarity.

Color is supporting evidence only and must not identify an application, hostname, or semantic state by itself.

## 22. Image Format Handling

The fingerprint schema remains common while tolerances may vary.

### 22.1 PNG and other lossless formats

- Use strict noise tolerances.
- Preserve small local details.
- Avoid aggressive blur.
- Compare decoded pixels rather than file-container bytes.

### 22.2 JPEG and other lossy formats

- Use a wider local noise tolerance.
- Allow grid averaging to suppress weak compression artifacts.
- Do not permanently normalize the source image.
- Add preprocessing only when calibration data proves it necessary.

### 22.3 WebP

WebP is deferred in V1. Add support only with an explicit, deterministic decoder dependency and calibration coverage.

### 22.4 Palette and animated formats

- Expand palettes deterministically to canonical RGB.
- Reject animated images or process only frame zero according to a documented policy.

## 23. Fingerprint Comparison

Fingerprint comparison must not reopen the original screenshots.

Before comparison, validate the versioned fingerprint format. The V1 version fixes the grid sizes, alpha-compositing policy, color conversion, edge method, histogram bins, dHash method, and serialization layout. `fromBytes` rejects incompatible versions or field lengths.

Different original image dimensions do not prevent comparison, but the result must indicate whether they matched.

The comparison result will include:

```text id="4eevdm"
category
similarity
luminanceSimilarity
colorSimilarity
edgeSimilarity
histogramSimilarity
changedCellRatio
dHashDistance
decodedPixelsExactlyEqual
dimensionsEqual
```

Similarity components and the overall `similarity` use a `0.0–1.0` range.

### 23.1 Comparison process

1. Check canonical decoded-pixel SHA-256.
2. Compare the 64×36 Y/Cb/Cr grid.
3. Count meaningfully changed cells.
4. Compare edge structure, normalized color histogram, and dHash.
5. Calculate the versioned weighted score.
6. Select a category using the score and changed-cell ratio.

The V1 weighted score is:

```text
0.45 × luminance similarity
+ 0.20 × chroma/color similarity
+ 0.15 × edge similarity
+ 0.10 × histogram similarity
+ 0.10 × dHash similarity
```

A fine grid cell counts as meaningfully changed when luminance differs by at least `24`, or combined Cb/Cr difference is at least `36`. After the exact-pixel check, V1 categories are:

- `VERY_SIMILAR`: `similarity >= 0.985` and `changedCellRatio <= 0.02`.
- `SOMEWHAT_SIMILAR`: `similarity >= 0.88` and `changedCellRatio <= 0.25`.
- `VERY_DIFFERENT`: otherwise.

The category therefore is not selected from the weighted score alone.

## 24. Visual Difference Categories

### `IDENTICAL`

Used only when decoded canonical pixels and image dimensions match exactly. Harmless-but-nonzero differences are classified as `VERY_SIMILAR`, preserving a clean distinction between exact decoded-pixel identity and tolerant visual similarity.

### `VERY_SIMILAR`

Used when:

- Coarse structure remains highly similar.
- Edge and color structure remain highly similar.
- A small percentage of fine cells changed meaningfully.

Typical examples:

- Checkbox changed.
- Element became highlighted.
- Button selected.
- Text entered.
- Small message appeared.

### `SOMEWHAT_SIMILAR`

Used when:

- Many fine or coarse cells differ.
- Broad visual style or page structure remains partially related.

Typical examples:

- Scrolling.
- Navigation within one application.
- Large modal or panel.
- Replaced form or table.
- Different application section.

### `VERY_DIFFERENT`

Used when:

- Coarse layout similarity is low.
- Edge distribution differs substantially.
- Color distribution also differs substantially.

This category describes visual structure only. It does not prove a different application, hostname, semantic state, or cause.

## 25. Screenshot Comparison Strategy

V1 deliberately avoids all-to-all screenshot comparison.

During a scenario, each newly captured screenshot is compared only with the immediately preceding screenshot using the compact fingerprints. The scenario summary records at most eight representative screenshot references, prioritizing:

- first visual state;
- meaningful visual changes;
- failure evidence;
- final visual state when not already represented.

For cross-run analysis, `DiagnosticRunComparator` first matches scenarios using only the run indexes. It then reads at most a bounded set of matching representative **fingerprint sidecars** (failure, final, first, then shared reasons), never the full screenshots, and returns compact visual transition results. Full images remain available for an AI only when those results make inspection worthwhile.

Broader run-wide visual-state clustering or historical nearest-neighbor search can be added later without changing the fingerprint format.

## 26. AI-Facing Screenshot Metadata

The AI-facing summaries do not contain fingerprint arrays. Scenario screenshot events and representative references expose compact values such as:

```text
screenshotId
imageReference
fingerprintReference
fingerprintVersion
width / height
imageBytes / fingerprintBytes
currentUrl / pageTitle when available
previousScreenshotId
comparisonToPrevious
representative reason
```

A comparison result contains similarity, luminance/color/edge/histogram components, changed-cell ratio, dHash distance, exact decoded-pixel equality, dimension equality, and category.

Cross-run comparison output links the left/right scenario execution IDs and representative screenshot IDs plus the same compact comparison map. The AI opens full images only when needed.

## 27. Screenshot Consumption Logic

Visual similarity controls prioritization rather than causal interpretation.

- `IDENTICAL` means decoded canonical pixels and dimensions are exact.
- `VERY_SIMILAR` usually does not require full image inspection unless a small UI change matters.
- `SOMEWHAT_SIMILAR` or `VERY_DIFFERENT` makes a representative screenshot more useful.
- Failure-associated screenshots remain eligible regardless of similarity.
- An unchanged screenshot may itself be important when a browser action was expected to change the UI.

The framework does not assign semantic page-state labels or infer root cause from visual similarity alone.

## 28. Expected Visual Transitions

V1 records visual changes and the browser/log operation context but does not maintain a separate `EXPECTED/NOT_EXPECTED` visual-state classifier. External AI/developer tooling can combine the operation log, URL/title, scenario outcome, and fingerprint comparison to decide whether a change or lack of change is suspicious.

An explicit expectation schema may be added later without changing stored screenshots or V1 fingerprints.

## 29. Visual State Labels

V1 intentionally does not assign semantic labels such as “expired-session page” from image similarity. Screenshot comparison produces structural categories only. Semantic interpretation belongs to evidence-aware external analysis using URL, logs, assertions, service responses, DOM/test context, and representative images where needed.

## 30. Representative Evidence

Each retained scenario summary keeps a bounded representative list with reasons such as:

```text
FIRST_VISUAL_STATE
VISUAL_CHANGE_SOMEWHAT_SIMILAR
VISUAL_CHANGE_VERY_DIFFERENT
FAILURE
FINAL_VISUAL_STATE
```

The list is capped so an AI can choose useful images without scanning every screenshot. Failure clusters identify representative candidate scenarios indirectly through their affected scenario IDs; external tooling may choose a representative run/cluster example using run/scenario indexes.

## 31. Cross-Run Comparison

`DiagnosticRunComparator` reads two `run-index.json` files first. It compares the lightweight comparison metadata and greedily matches scenarios using weighted signals:

- exact source key;
- semantic key;
- name key;
- same feature + example identity;
- same feature + tag identity;
- example-value/tag agreement;
- source-order proximity.

This allows duplicate scenario names, Scenario Outline rows, moved line numbers, and some source/name movement to be handled without a single brittle primary key.

### 31.1 Scenario transitions

V1 reports compact transitions including:

```text
PERSISTENT_PASS
NEW_FAILURE
RESOLVED
PERSISTENT_FAILURE
CHANGED_FAILURE_SIGNATURE
INTERRUPTED_OR_UNKNOWN
NEW
MISSING
```

The comparison output includes the match basis/score so an AI can judge how much confidence to place in a pairing.

### 31.2 Failure-cluster transitions

Failure signatures are compared without opening dense evidence and report:

```text
NEW
RESOLVED
PERSISTENT
COUNT_CHANGED
```

Multi-run intermittency is not forced into a run-level enum. An external agent can inspect the root run catalog and repeated pairwise/index history to estimate recurrence rates.

## 32. Cross-Run Screenshot Comparison

After scenarios are matched, the comparator may read a bounded set of representative `.pkbf` files from both run directories. It does not reopen full screenshots.

The preferred representative pair order is failure state, final visual state, first visual state, then other shared representative reasons, with a maximum of four representative pairs per matched scenario. Results contain the compact V1 visual comparison metrics and screenshot IDs.

This provides enough information for an AI to decide whether opening a full image is worthwhile while avoiding unbounded historical visual comparison.

## 33. External AI Investigation Guidance

This section is guidance for external AI/developer tooling consuming Pickleball evidence. Pickleball 2.1.3 does not execute or permission autonomous agent commands.

### 33.1 Establish the investigation

Create an investigation and record:

- Reported issue.
- Expected behavior.
- Initial evidence.
- Allowed commands.
- Allowed modifications.
- Run budget.
- Compute and resource limits.
- Safety constraints.
- Cleanup requirements.

### 33.2 Inspect existing evidence first

Before rerunning tests, inspect:

- Existing investigation indexes.
- Run indexes.
- Failed scenario summaries.
- Failure clusters.
- Relevant trace ranges.
- Representative screenshots.
- Historical comparisons.

This avoids unnecessary execution.

### 33.3 Form an explicit hypothesis

Every diagnostic rerun should record:

- Hypothesis.
- Variable being tested.
- Expected evidence if correct.
- Expected evidence if incorrect.

A rerun with no stated purpose should be avoided.

### 33.4 Change as few variables as possible

Prefer controlled variations such as:

- Repeat without intentional changes.
- Reduce parallelism.
- Run one affected scenario.
- Run one affected failure cluster.
- Change one browser or environment setting.
- Apply one candidate fix.
- Revert one candidate fix.

The agent should not change source, configuration, browser, test selection, and parallelism simultaneously unless unavoidable.

### 33.5 Start with the smallest useful test

Use this expansion order:

```text id="hkxpap"
affected scenario
-> related failure cluster
-> affected feature
-> targeted regression set
-> complete suite
```

A full suite should not normally be the first diagnostic action.

### 33.6 Test intermittency

When intermittency is possible:

- Repeat the original failure.
- Repeat the candidate fix.
- Compare failure rates.
- Preserve execution conditions and seed.
- Deliberately vary the seed when checking order dependence.
- Report observed rates instead of unsupported certainty.

### 33.7 Verify fixes counterfactually

When appropriate, use:

```text id="jfrm5z"
baseline fails
-> candidate fix passes
-> fix reverted and failure returns
-> fix restored and verification passes
```

This provides stronger evidence than one before-and-after run.

It is optional when unnecessary or too costly, but recommended when causal confidence is low.

### 33.8 Run regression verification

After targeted verification:

1. Run directly related scenarios.
2. Run scenarios sharing changed components.
3. Run a broader regression set.
4. Run the complete suite when appropriate.

The investigation must distinguish:

```text id="qn2qr6"
targeted fix verified
related regression verified
full regression verified
```

### 33.9 Stop conditions

The agent should stop when:

- The issue is reproducibly isolated.
- The fix satisfies the required verification level.
- The execution budget is reached.
- Further runs repeat existing evidence without increasing confidence.
- Required permissions or resources are unavailable.
- Evidence shows the investigation scope is incorrect.
- Safety or resource limits are reached.

This prevents uncontrolled rerun loops.

## 34. External Agent Permissions and Guardrails (Out of Pickleball 2.1.3 Scope)

The investigation definition should specify permitted actions:

```text id="477uo5"
RUN_TESTS
CHANGE_TEST_CONFIGURATION
CHANGE_APPLICATION_CODE
CHANGE_FRAMEWORK_CODE
INSTALL_DEPENDENCIES
ACCESS_NETWORK
START_LOCAL_SERVICES
```

It should also define:

- Maximum runs.
- Maximum concurrent runs.
- Maximum resource budget.
- Permitted directories.
- Permitted commands or command patterns.
- Cleanup requirements.
- Whether experimental changes must be reverted.
- Whether external environments may be used.
- Whether destructive operations are prohibited.

Every AI action should record:

```text id="y6lmsv"
agent identifier
timestamp
command or change
purpose
hypothesis ID
result
generated run ID
patch fingerprint
configuration fingerprint
```

## 35. Investigation Conclusions

An investigation conclusion must link to evidence rather than contain only prose.

Recommended classifications:

```text id="qzmpfa"
ROOT_CAUSE_CONFIRMED
ROOT_CAUSE_LIKELY
ISSUE_REPRODUCED_NOT_ISOLATED
FIX_TARGETED_VERIFIED
FIX_REGRESSION_VERIFIED
INTERMITTENT_CONDITION_IDENTIFIED
ENVIRONMENT_DEPENDENT
NOT_REPRODUCED
INCONCLUSIVE
BLOCKED
```

The conclusion should answer:

- Which runs were performed?
- Why was each run performed?
- What intentionally changed?
- What unexpectedly changed?
- Which comparisons are valid?
- Is the failure repeatable?
- Under which conditions does it occur?
- What evidence supports the leading cause?
- Did the candidate fix remove the original failure?
- Did it introduce another failure?
- What verification level was completed?
- What remains uncertain?

## 36. Sensitive and Large Data

Diagnostic capture must not imply unlimited persistence of sensitive or large values.

Capture policies will control:

- HTTP bodies.
- Headers.
- Cookies.
- Authorization data.
- Browser storage.
- DOM snapshots.
- Large objects.
- Environment variables.
- Java system properties.
- Pickleball properties.
- Screenshots containing sensitive information.

Do not dump all environment variables or system properties indiscriminately.

Use:

- Allowlists.
- Sensitive-name detection.
- Redaction.
- Hash-only representations.
- Key-only recording.
- Explicit capture configuration.

Example:

```text id="fwet76"
name: SERVICE_API_KEY
present: true
valueRedacted: true
comparisonHash: ...
sameAsBaseline: true
```

The AI can determine whether a sensitive value changed without receiving the value.

Stored evidence must indicate when content was:

```text id="cx5neh"
redacted
truncated
hashed
omitted
```

Secret-like configuration values should be redacted before persistence. Screenshot pixels are preserved as captured; broader screenshot-redaction tooling is outside V1.

## 37. Performance Requirements

The diagnostic implementation should:

- Decode each screenshot once.
- Scan pixels once where practical.
- Use primitive arrays.
- Release decoded images promptly.
- Use bounded screenshot-processing concurrency.
- Avoid unbounded queues.
- Avoid retaining completed event graphs.
- Avoid quadratic screenshot comparisons.
- Avoid duplicate binary artifacts.
- Keep navigation JSONL plain; losslessly gzip only the completed deep TRACE/DEBUG stream while preserving logical event references and interrupted raw-trace recovery.
- Keep V1 resource capture to bounded run-start/run-completion snapshots.
- Avoid running full test suites unnecessarily.

Target fingerprint performance for a typical `1920 × 1080` screenshot:

- Generation: tens of milliseconds.
- Comparison: generally below one millisecond.
- Temporary memory: approximately `10–20 MB` per concurrent image.
- Stored fingerprint: approximately `7–8 KB`.

Performance assumptions must be validated under parallel execution.

## 38. Calibration

Fingerprint thresholds must be calibrated using representative screenshots, including:

- Repeated unchanged PNG captures.
- Repeated unchanged JPEG captures.
- Expected JPEG quality settings.
- Checkbox and selection changes.
- Focus and hover states.
- Text entry.
- Small messages and icons.
- Scrolling.
- Navigation within one application.
- Large modal changes.
- Visually unrelated pages.

Calibration must ensure:

- Harmless encoding noise remains `IDENTICAL`.
- The smallest required UI change becomes at least `VERY_SIMILAR`.
- Large same-style changes become `SOMEWHAT_SIMILAR` or `VERY_DIFFERENT` according to calibrated magnitude.
- Visually unrelated designs become `VERY_DIFFERENT`.

## 39. Versioning

V1 writes `schemaVersion: 1` on event records and the principal JSON artifacts (`manifest`, `run-index`, `run-catalog`, scenario summaries, configuration, environment, clusters, and generated run comparisons). The binary fingerprint carries its own `VERSION = 1`.

The fingerprint version fixes alpha compositing, grid dimensions, color conversion, edge calculation, histogram layout, dHash calculation, serialization, comparison weights, and category thresholds. Incompatible fingerprint versions or field lengths are rejected.

Future changes that alter interpretation of stored data should increment the relevant schema/version rather than silently reusing V1 semantics. External investigation/session schemas are owned by the external tooling that creates them.

## 40. Acceptance Criteria

The implementation is complete when it can:

1. Run in an exclusive diagnostic reporting mode.
2. Capture all events through `TRACE`.
3. Preserve TRACE/DEBUG fidelity while storing the deep stream separately and losslessly compressing it after controlled scenario completion.
4. Record structured diagnostic evidence.
5. Avoid retaining completed report trees in memory.
6. Write scenario evidence incrementally.
7. Preserve useful data after an interrupted run.
8. Reconstruct derived indexes from surviving summaries, metadata, and valid JSONL evidence.
9. Record run identity, outcome, completion, and evidence integrity without a `MIXED` or run-level comparability classification.
10. Record sanitized source, configuration, environment, test-selection, and resource metadata.
11. Compare runs and explain intentional and unexpected differences.
12. Separate scenario failures from shared run-level incidents.
13. Correlate failures across parallel scenarios.
14. Identify persistent, resolved, new, and changed failures in pairwise comparisons and expose enough multi-run indexed history for external tooling to identify intermittent failures.
15. Provide lightweight run catalogs, run indexes, scenario summaries, and failure clusters; external tooling may assemble investigation indexes.
16. Reference exact detailed trace ranges.
17. Preserve original screenshot bytes unchanged.
18. Avoid Base64 screenshot duplication.
19. Generate deterministic, versioned visual fingerprints.
20. Compare fingerprints without reopening screenshots.
21. Distinguish exact-pixel identity from visual identity.
22. Classify visual changes as `IDENTICAL`, `VERY_SIMILAR`, `SOMEWHAT_SIMILAR`, or `VERY_DIFFERENT`.
23. Compare relevant visual states across runs.
24. Select representative screenshots.
25. Retain similar screenshots while deprioritizing redundant AI analysis.
26. Keep screenshot fingerprint generation synchronous within the already-bounded scenario execution concurrency, with no unbounded processing queue.
27. Record redaction, bounded-value truncation, sensitive-value hashing, and focused-environment omission policy explicitly.
28. Expose lightweight run catalogs/indexes so an external AI can select relevant runs before deeper consumption.
29. Preserve optional investigation/run lineage metadata when supplied by external tooling.
30. Keep autonomous AI command execution and permissions outside Pickleball 2.1.3.
31. Support targeted and regression analysis through stable run/scenario evidence references.
32. Produce evidence-linked failure clusters and comparison data that external tooling can use for conclusions.
33. Produce deterministic fingerprints, configuration hashes, and comparison results for identical inputs/configuration.
34. Record structured step counts and resolved definition origin/source pointers.
35. Record positive-only native capability observations at step/scenario/run scope without treating absence as proof of non-use.
36. Record consumer Git/source provenance plus build-embedded Pickleball version/Git/artifact provenance, including dirty/reproducibility state.
37. Preserve current automatic platform/caller logging by default while allowing explicit `pkb_platformlog` selection, templates, Git augmentation, or suppression.

## 41. Default Decisions

| Area | Default |
|---|---|
| Public feature name | `DIAGNOSTIC` reporting mode |
| Captured severity | All events through `TRACE` |
| HTML and ReportPortal | Disabled |
| Console output | Continues to follow `pkb_loglevel` |
| Event storage | Per-scenario append-only JSONL |
| Run-scoped evidence | Separate run event stream |
| Memory retention | Active scopes and compact summaries only |
| Compression | Plain navigation JSONL; lossless gzip for completed deep TRACE/DEBUG evidence; interrupted raw trace remains valid |
| Durability | Every JSONL event appended promptly; summaries/indexes atomically replaced |
| Strict disk synchronization | Not forced per event in V1 |
| Run manifests | Atomically updated and sanitized |
| Run comparisons | Rebuildable derived artifacts |
| Resource monitoring | Lightweight run-start and run-completion snapshots in V1 |
| Screenshot format | Preserve original bytes |
| Base64 screenshot copy | Not created |
| Fingerprint storage | Versioned sidecar |
| Full fingerprint in AI index | No |
| Similar screenshots | Retained but deprioritized |
| Failure screenshots | Always eligible for inspection |
| Semantic visual labels | Not assigned by V1 fingerprint comparison |
| Cross-run visual matching | At most four matched representative fingerprint pairs per matched scenario |
| Large payloads | Text/maps/collections are bounded and truncation is marked |
| Sensitive values | Redacted or hashed before persistence |
| Crash recovery | First-class behavior |
| Investigation evidence | Append-only raw evidence; indexes/history may grow |
| Experimental reruns | One controlled variable where practical |
| Test expansion | Scenario before feature or suite |
| Fix verification | Repeated and counterfactual when warranted |
| Agent execution/orchestration | External to Pickleball 2.1.3 |
| Root-cause statements | Evidence-linked candidate conclusions |

## 42. Final Design Principle

The system will preserve detailed evidence as efficient, recoverable, append-oriented records while presenting AI consumers with compact run catalogs/indexes, scenario summaries, failure clusters, visual comparisons, and precise references. External tooling may compose those artifacts into investigation indexes and conclusions.

The AI should not repeatedly consume every trace event, screenshot, or run.

It should progressively identify:

```text id="5q524x"
which investigation matters
-> which runs provide meaningful comparison
-> which conditions changed
-> which failures or states changed
-> which scenario or cluster requires investigation
-> which trace range or image provides decisive evidence
```

This design enables efficient AI-assisted reproduction, isolation, debugging, fix validation, and regression analysis across individual scenarios, parallel scenario runs, and controlled multi-run investigations.
