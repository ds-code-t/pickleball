# AI Diagnostic Reporting, Visual Evidence, and Multi-Run Investigation Plan

## 1. Purpose

Pickleball diagnostic reporting is an alternate evidence pipeline for AI-assisted troubleshooting. It preserves complete structured evidence while minimizing:

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
- Compare multiple runs under controlled conditions.
- Reproduce intermittent issues.
- Isolate root causes by changing one condition at a time.
- Validate candidate fixes.
- Detect regressions after changes.
- Retrieve detailed traces and images only when higher-level evidence indicates that they are necessary.
- Recover useful evidence after interrupted or crashed runs.

## 1.1 Pickleball 2.1.3 implementation decisions

The following decisions are authoritative for the 2.1.3 implementation and supersede older design wording:

- `pkb_reportingmode=diagnostic` is the only setting required to enable diagnostic mode. Missing or other values use normal reporting.
- Diagnostic mode uses a separate append-oriented evidence pipeline. Automatic normal HTML/ReportPortal/XLSX converter output and Base64 screenshot duplication are bypassed while explicitly invoked reporting steps remain functional.
- Diagnostic files capture TRACE-through-ERROR evidence regardless of console `pkb_loglevel`; console verbosity still follows `pkb_loglevel`.
- `pkb_reportretention=all|failed|none` controls automatic local report/evidence retention and defaults to `all`.
- Run outcomes are `PASSED`, `FAILED`, `NO_TESTS`, or `UNKNOWN`; completion is tracked separately as `COMPLETE` or `INTERRUPTED`.
- Pickleball does not assign a run-level comparability class. Comparability is derived from lightweight run metadata and scenario matching.
- Scenario identity uses multiple signals including execution ID, URI, name, scenario/outline lines, tags, example values, exact-source key, semantic key, name key, and source-order hint.
- Navigation evidence remains plain JSONL. TRACE/DEBUG log events are written live as raw JSONL and losslessly gzip-compressed after controlled completion. Interrupted raw trace remains valid evidence.
- Diagnostic screenshots use Selenium binary PNG bytes. Compact visual fingerprint sidecars are generated eagerly when screenshots are captured.
- V1 visual fingerprints use deterministic Y/Cb/Cr sampling, edge data, a normalized histogram, dHash, dimensions, and canonical decoded-pixel SHA-256.
- Adjacent screenshots record compact `comparisonToPrevious` metadata so an AI can often answer visual-stability questions without loading either fingerprint or PNG.
- Fingerprints are consumed by Pickleball comparison code rather than manually decoded by agents.
- Configuration provenance records effective execution settings and the observed winning source. Secret-like values are redacted and retain only one-way comparison hashes.
- Environment capture is intentionally focused on debugging-relevant runtime metadata rather than the full workstation/user/network snapshot.
- Consumer Git/source provenance and build-embedded Pickleball version/Git/artifact provenance are captured best-effort.
- Failure signatures are **site-aware V2 signatures** when structured step-site metadata is available. V2 combines normalized failure class/message with a stable failure-site key derived from canonical feature source, Gherkin step line, and resolved definition method. Failures without a structured site deliberately fall back to the V1 class/message-only signature.
- Sparse failure metadata includes `failureSignatureVersion`, `failureSiteKey`, and `failureSite` where available. Rebuilt indexes/clusters and cross-run comparisons preserve this metadata.
- Derived indexes, clusters, catalogs, interrupted summaries, and missing fingerprint sidecars can be rebuilt from surviving evidence with `DiagnosticIndexRebuilder`.
- `tools.dscode.common.reporting.diagnostic.DiagnosticCli` is the maintained command-line front end for routine run comparison, explicit fingerprint comparison, and rebuild/recovery operations. It delegates to the existing diagnostic APIs rather than duplicating comparison or rebuild logic.
- Pickleball 2.1.3 produces evidence for external AI/developer investigation. Autonomous agent command execution, permissions, source changes, and dependency installation remain outside the framework implementation scope.

## 2. Diagnostic architecture and evidence hierarchy

The framework uses progressive disclosure:

```text
run catalog
  -> run index / failure clusters
      -> scenario summary
          -> relevant normal events
              -> existing visual comparison metadata / fingerprints
                  -> representative screenshot
                      -> deep TRACE/DEBUG evidence
```

The governing investigation rule is:

> Use the shallowest evidence layer that completely answers the current question. Do not advance to a denser layer merely because it exists.

Recommended order:

```text
run-catalog.json
-> selected run-index.json / clusters.json
-> selected summary.json
-> targeted events.jsonl only when needed
-> comparisonToPrevious or Pickleball fingerprint/run comparison
-> representative PNG only when semantic visual meaning is required
-> trace.jsonl.gz or interrupted trace.jsonl only when structured/INFO+ evidence is insufficient
```

At every layer, stop when the evidence already answers the investigation with sufficient confidence.

Examples:

- Different `failureSignature` / `failureSiteKey` values already establish distinct clusters; dense events are not required merely to prove clustering.
- If a targeted normal event identifies the failed assertion and lifecycle, TRACE/DEBUG evidence is unnecessary.
- `decodedPixelsExactlyEqual=true` already establishes decoded-pixel equality; opening a PNG adds no evidence to the question “did these screenshots differ?”
- `VERY_SIMILAR`, `SOMEWHAT_SIMILAR`, or `VERY_DIFFERENT` establishes that rendered pixels differ and provides magnitude. Open the representative PNG only if the question requires understanding what visibly changed.

Do not recursively ingest an entire run. Select evidence by run, scenario, event range, and representative visual reference.

## 3. Diagnostic reporting mode

Diagnostic mode changes capture, storage, durability, artifact generation, and automatic report output. It is not a new logging severity.

```text
reporting mode: DIAGNOSTIC
capture level: TRACE
```

Normal logging levels remain:

```text
TRACE
DEBUG
INFO
WARN
ERROR
```

Structured diagnostic events are preferred over large volumes of loosely related text.

## 4. Exclusive automatic reporting behavior

When diagnostic mode is active, Pickleball bypasses automatic normal report sinks such as:

- Composite HTML output.
- Per-scenario HTML output.
- Automatic ReportPortal forwarding.
- Automatic framework XLSX/status-row output.
- Completed normal report-tree retention.
- Base64 screenshot duplication.

Existing explicitly invoked reporting steps remain functional. Pickleball does not claim to suppress output produced independently by Cucumber, Selenium, the JVM, consumer applications, or third-party libraries.

## 5. Run lineage and investigation context

External tooling may organize multiple runs into an investigation. Optional lineage properties are preserved in lightweight run metadata when supplied:

```text
pkb_investigation_id
pkb_run_purpose
pkb_parent_run_id
pkb_baseline_run_id
pkb_changed_variables
```

These values are optional and are not required to enable diagnostic mode.

## 6. Run outcome and completion

Outcome and completion are separate dimensions.

Outcomes:

```text
PASSED
FAILED
NO_TESTS
UNKNOWN
```

Completion:

```text
COMPLETE
INTERRUPTED
```

A single passing run is not automatically considered proof of stability or of a fix.

## 7. Diagnostic output structure

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
                trace.jsonl.gz      # after controlled completion when TRACE/DEBUG exists
                screenshots/
                    <screenshot-id>.png
                fingerprints/
                    <screenshot-id>.pkbf
```

An interrupted scenario may retain raw `trace.jsonl` instead of the gzip form. `pkb_diagnostic_output` may move the diagnostic-runs root.

## 8. Run catalog

`run-catalog.json` is the first investigation layer. It lets an agent select candidate runs by compact run metadata without opening every run directory.

The catalog is derived and rebuildable.

## 9. Run index

`run-index.json` is the primary sparse per-run navigation artifact. It contains comparison-oriented metadata such as:

- Run ID, outcome, completion, retention, and evidence integrity.
- Browser/environment/runtime metadata.
- Deterministic configuration/environment/dependency/selection/source fingerprints.
- Optional external lineage.
- Compact scenario identities and outcomes.
- Step and capability rollups.
- Failure signatures/sites.
- Representative screenshot references.
- Links to deeper evidence.

The index references dense evidence rather than duplicating it.

## 10. Scenario identity

No single field is treated as a permanent scenario key. Complementary signals include:

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

This supports duplicate scenario names, Scenario Outline rows, and cross-launcher URI differences without depending on one fragile identifier.

## 11. Scenario summary

Each top-level scenario retains a small `summary.json` containing compact scenario state such as:

- Identity.
- Outcome and completion.
- Duration and event range/count.
- Retention state.
- Screenshot count.
- Bounded representative screenshot references.
- Failure class/message/signature/site metadata when available.
- Native capability observations.
- Paths to retained normal/deep evidence.

Summaries remain available when retention prunes dense passing evidence.

## 12. Structured steps and native capability observations

Diagnostic mode records structured step metadata and positive-only native capability observations.

Definition origin may be:

```text
PICKLEBALL
NON_PICKLEBALL
UNKNOWN
```

`NON_PICKLEBALL` is not assumed to mean “consumer”; third-party glue may also be non-Pickleball.

Capability semantics are asymmetric:

> Presence means Pickleball positively observed the capability. Absence does not prove the capability was unused.

Initial capabilities include browser/WebDriver activity, navigation, DOM, screenshots, nested/component scenarios, service-call scenarios, and native HTTP execution.

## 13. Event format and ordering

Events use independent JSON Lines records with logical ordering fields including:

```text
schemaVersion
runId
eventSeq
timestamp
monotonicOffsetNanos
thread
type
scenarioExecutionId
scenarioSeq
nestedInvocationId
```

Logical `eventSeq` / `scenarioSeq` are authoritative across normal and deep trace files. Indexes reference logical ranges/counts, never byte offsets.

## 14. Normal events versus deep TRACE/DEBUG evidence

`events.jsonl` remains plain for inexpensive navigation and carries lifecycle, structured steps, screenshot events, failures, INFO-and-higher log records, and other non-deep evidence.

TRACE/DEBUG log records retain full event metadata in the deep trace stream:

- live: `trace.jsonl`;
- controlled completion: losslessly compressed `trace.jsonl.gz`.

The summary exposes the deep trace path, encoding, count, and first/last sequence values so an agent can decide whether it is worth opening.

## 15. Durability and crash recovery

Evidence is written incrementally. Summaries, manifests, indexes, clusters, and catalogs are atomically replaced where practical.

A later diagnostic run may recover a still-`RUNNING` prior manifest as `UNKNOWN` / `INTERRUPTED` while preserving already-written evidence.

`DiagnosticIndexRebuilder` can:

- Read surviving manifest/configuration/environment/source metadata.
- Read valid normal/deep JSONL evidence.
- Ignore malformed/incomplete trailing records where recoverable.
- Mark unterminated scenarios interrupted.
- Reconstruct missing/stale summaries where evidence permits.
- Discover surviving screenshots and regenerate missing fingerprint sidecars.
- Rebuild run indexes.
- Rebuild failure clusters.
- Rebuild the shared run catalog.

The maintained command-line entry point is:

```text
DiagnosticCli rebuild <diagnostic-runs-root-or-run-root>
```

This avoids manual Maven-classpath construction and JShell scripting for routine recovery.

## 16. Failure signatures and within-run correlation

### 16.1 Site-aware V2 signature

For a failure associated with a structured Cucumber/Pickleball step, Pickleball records a V2 failure signature based on:

- Normalized exception class/message.
- Stable failure-site key.

The site key is derived from stable structured location signals:

```text
canonical feature source
Gherkin step line
resolved declaringClass#method
```

Sparse failure fields include:

```text
failureSignature
failureSignatureVersion = 2
failureSiteKey
failureSite.feature
failureSite.stepLine
failureSite.definition
```

This prevents unrelated assertions with the same generic outer exception message from collapsing into one cluster while allowing repeated failures at the same assertion site to remain clusterable.

### 16.2 V1 no-site fallback

If a failure occurs outside a structured step and no stable site can be identified, Pickleball deliberately falls back to the legacy class/message-only signature:

```text
failureSignatureVersion = 1
```

No synthetic failure site is invented.

### 16.3 Clusters and rebuild

`clusters.json` groups scenarios by failure signature and carries the same site/version metadata when available.

`DiagnosticIndexRebuilder` preserves V2/V1 metadata when rebuilding summaries, run indexes, and clusters.

`DiagnosticRunComparator` preserves the metadata in compact scenario/failure transitions so an agent can distinguish root-cause candidates before opening dense events.

A failure signature is an investigation aid, not a permanent public identifier or proof of one root cause.

## 17. Configuration provenance

`configuration.json` records execution-relevant effective settings and their observed winning source when available.

Secret-like configuration keys are redacted before persistence. A one-way value hash may be retained so an agent can compare whether protected values differ without learning either value.

Large values are bounded and marked when truncated.

## 18. Environment and source provenance

`environment.json` contains a focused runtime summary such as Java, OS, architecture, timezone, processors, heap, and CI/container hints.

`source-provenance.json` records best-effort consumer and Pickleball source identity, including Git metadata, dirty/reproducibility state, framework version, and artifact hash where available.

`pkb_gitsnapshot` controls consumer Git capture:

```text
metadata   # default
none
diff
```

`diff` may retain a gzipped working-tree patch for a dirty consumer checkout. HTTP(S) credentials embedded in Git remotes are removed before persistence.

## 19. Screenshot storage

Diagnostic screenshot capture writes one binary image and one compact fingerprint sidecar. The original image bytes are preserved.

The normal browser-step capture cadence is preserved. A best-effort failure screenshot is also captured before cleanup. Explicit screenshot APIs continue to work.

Service-only and mapping-only scenarios are not automatically given browser screenshots.

## 20. Visual fingerprint V1

V1 fingerprints are deterministic compact sidecars containing:

- Canonical decoded-pixel SHA-256.
- Original dimensions.
- `64 × 36` Y/Cb/Cr grid.
- `32 × 18` edge grid.
- Normalized `4 × 4 × 4` color histogram.
- 64-bit dHash.

Expected size is roughly 7–8 KB for typical browser screenshots.

PNG is the primary browser format. ImageIO-supported JPEG inputs are accepted. WebP is deferred.

## 21. Fingerprint comparison

`VisualFingerprintComparator` returns compact metrics:

```text
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

Categories are:

```text
IDENTICAL
VERY_SIMILAR
SOMEWHAT_SIMILAR
VERY_DIFFERENT
```

`IDENTICAL` is reserved for exact canonical decoded-pixel equality with matching dimensions.

## 22. Visual equality rules

Agents must distinguish encoded-file equality from rendered-pixel equality:

- Raw PNG byte equality proves the files are identical.
- Raw PNG byte inequality does **not** prove rendered pixels differ; metadata/compression/encoding may differ.
- `decodedPixelsExactlyEqual=true` is the preferred equality signal for visual investigation because it compares canonical decoded pixels and dimensions.

Therefore:

- Never open a PNG merely to determine whether two screenshots differ.
- Prefer already-recorded `comparisonToPrevious` for adjacent screenshots.
- Use Pickleball fingerprint comparison for cross-run or explicit comparisons.
- Open a PNG only when the semantic content of an established difference must be interpreted.

## 23. Adjacent screenshot comparison

During a scenario, each newly captured screenshot is compared to the previous compact fingerprint.

The screenshot event may record:

```text
previousScreenshotId
comparisonToPrevious
```

This compact metadata includes the same equality/similarity metrics used by `VisualFingerprintComparator` and should be consumed before loading either sidecar or image.

## 24. Representative evidence

Scenario summaries keep a bounded representative screenshot list, prioritizing reasons such as:

```text
FIRST_VISUAL_STATE
VISUAL_CHANGE_SOMEWHAT_SIMILAR
VISUAL_CHANGE_VERY_DIFFERENT
FAILURE
FINAL_VISUAL_STATE
```

This prevents an AI from scanning every screenshot while retaining meaningful visual states.

## 25. Cross-run scenario comparison

`DiagnosticRunComparator` reads two `run-index.json` files first and greedily matches scenarios using weighted identity signals such as:

- exact-source key;
- semantic key;
- name key;
- feature/example identity;
- example-value/tag agreement;
- source-order proximity.

Scenario transitions include:

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

Failure-cluster transitions include:

```text
NEW
RESOLVED
PERSISTENT
COUNT_CHANGED
```

## 26. Cross-run visual comparison

After scenarios are matched, `DiagnosticRunComparator` may consume only a bounded set of corresponding representative `.pkbf` sidecars, never full PNGs automatically.

At most four representative pairs per matched scenario are compared.

For explicit fingerprint comparison, use:

```text
DiagnosticCli compare-fingerprints <left.pkbf> <right.pkbf> [output-json]
```

Do not manually decode `.pkbf`; the binary format is an implementation detail consumed by Pickleball.

## 27. Diagnostic CLI

`DiagnosticCli` is the supported command-line front end for routine diagnostic utility operations:

```text
compare-runs <left-run-index> <right-run-index> [output-json]
compare-fingerprints <left.pkbf> <right.pkbf> [output-json]
rebuild <diagnostic-runs-root-or-run-root>
```

It delegates to:

- `DiagnosticRunComparator` for run comparison.
- `VisualFingerprint` / `VisualFingerprintComparator` for explicit fingerprint comparison.
- `DiagnosticIndexRebuilder` for recovery/rebuild.

From a Maven consumer where Pickleball is on the test classpath, PowerShell should quote each complete `-Dexec.*` argument:

```powershell
mvn org.codehaus.mojo:exec-maven-plugin:3.5.0:java "-Dexec.mainClass=tools.dscode.common.reporting.diagnostic.DiagnosticCli" "-Dexec.classpathScope=test" "-Dexec.args=compare-runs reports/diagnostic-runs/<left-run>/run-index.json reports/diagnostic-runs/<right-run>/run-index.json target/diagnostic-comparison.json"
```

```powershell
mvn org.codehaus.mojo:exec-maven-plugin:3.5.0:java "-Dexec.mainClass=tools.dscode.common.reporting.diagnostic.DiagnosticCli" "-Dexec.classpathScope=test" "-Dexec.args=compare-fingerprints <left.pkbf> <right.pkbf> target/fingerprint-comparison.json"
```

```powershell
mvn org.codehaus.mojo:exec-maven-plugin:3.5.0:java "-Dexec.mainClass=tools.dscode.common.reporting.diagnostic.DiagnosticCli" "-Dexec.classpathScope=test" "-Dexec.args=rebuild reports/diagnostic-runs"
```

If comparison output is omitted, JSON is written to stdout. `rebuild` prints a compact result after rebuilding runs and the shared catalog.

Agents should prefer this CLI to Maven dependency-classpath construction plus JShell for routine operations.

## 28. External AI investigation guidance

Before rerunning tests, inspect existing evidence first.

Natural investigation order:

```text
run catalog
-> selected run indexes / clusters
-> selected failed summaries
-> targeted normal events only when necessary
-> existing visual comparison metadata / fingerprints
-> representative PNG only for semantic interpretation
-> TRACE/DEBUG only as the final evidence layer
```

Every diagnostic rerun should have a purpose and should change as few variables as practical.

Preferred test expansion:

```text
affected scenario
-> related failure cluster
-> affected feature
-> targeted regression set
-> complete suite
```

## 29. Intermittency and fix verification

When intermittency is plausible, repeat controlled conditions and report observed rates rather than unsupported certainty.

When warranted, use counterfactual verification:

```text
baseline fails
-> candidate fix passes
-> fix reverted and failure returns
-> fix restored and verification passes
```

Broader regression verification should follow targeted verification rather than precede it unless the investigation requires otherwise.

## 30. Stop conditions

An agent should stop when:

- The current evidence sufficiently answers the investigation question.
- The issue is reproducibly isolated.
- The required verification level is satisfied.
- Additional reads/runs would only repeat existing evidence.
- The run/resource budget is reached.
- Required permission or resources are unavailable.
- Evidence shows the investigation scope is incorrect.

This applies both to **file-reading escalation** and to **test reruns**.

## 31. External agent permissions and guardrails

Autonomous execution/orchestration remains external to Pickleball 2.1.3. External tooling should define permitted actions, run budgets, resource limits, cleanup requirements, and destructive-operation restrictions.

## 32. Sensitive and large data

Diagnostic capture must not imply unlimited persistence of sensitive or large values.

Use:

- Allowlists.
- Sensitive-name detection.
- Redaction.
- Hash-only representations.
- Key-only recording.
- Explicit capture configuration.
- Bounded/truncated value representations.

Stored evidence should indicate when content was redacted, hashed, truncated, or omitted.

Screenshot pixels are preserved as captured; broader screenshot-redaction tooling is outside V1.

## 33. Performance requirements

The implementation should:

- Decode each screenshot once.
- Use primitive arrays where practical.
- Release decoded images promptly.
- Avoid unbounded queues.
- Avoid retaining completed event graphs.
- Avoid quadratic screenshot comparison.
- Avoid duplicate binary artifacts.
- Keep navigation JSONL plain.
- Losslessly gzip only completed deep TRACE/DEBUG evidence.
- Preserve interrupted raw-trace recovery.
- Avoid unnecessary full-suite reruns.

## 34. Versioning

V1 writes `schemaVersion: 1` on principal JSON artifacts and events. The binary fingerprint carries `VERSION = 1`.

Future changes that alter interpretation of stored evidence should increment the relevant schema/version rather than silently reusing V1 semantics.

`failureSignatureVersion` is independent of the overall artifact schema version and identifies the failure-signature algorithm:

```text
1 = legacy class/message-only fallback
2 = site-aware structured failure signature
```

## 35. Acceptance criteria

The 2.1.3 implementation is complete when it can:

1. Run in exclusive diagnostic reporting mode.
2. Capture TRACE-through-ERROR diagnostic evidence while preserving console `pkb_loglevel` behavior.
3. Write scenario evidence incrementally.
4. Preserve and recover interrupted evidence.
5. Maintain sparse run catalogs, run indexes, clusters, and scenario summaries.
6. Maintain scenario identity across supported Maven/IDE launcher source URI forms.
7. Preserve configuration/environment/source provenance without exposing secret-like values.
8. Record structured steps and positive-only native capabilities.
9. Produce V2 site-aware failure signatures with V1 fallback only when no structured site is available.
10. Preserve failure-signature version/site metadata through rebuild and run comparison.
11. Store TRACE/DEBUG separately and losslessly gzip it after controlled completion.
12. Preserve original screenshot bytes without Base64 duplication.
13. Generate deterministic compact visual fingerprints.
14. Record adjacent `comparisonToPrevious` metadata.
15. Distinguish encoded-file equality from canonical decoded-pixel equality.
16. Compare fingerprints without reopening PNGs.
17. Compare matching scenarios across runs using sparse indexes first.
18. Compare at most a bounded representative fingerprint set per matched scenario.
19. Rebuild missing indexes, clusters, catalog, summaries where possible, and fingerprint sidecars from surviving evidence.
20. Provide `DiagnosticCli compare-runs`, `compare-fingerprints`, and `rebuild` as maintained utility entry points.
21. Allow agents to answer common investigations without manual `.pkbf` decoding, Maven classpath assembly, JShell, or unnecessary PNG/TRACE inspection.
22. Keep autonomous agent execution/orchestration outside the framework.

## 36. Default decisions

| Area | Default |
|---|---|
| Public feature name | `DIAGNOSTIC` reporting mode |
| Captured severity | TRACE through ERROR |
| Console output | Follows `pkb_loglevel` |
| Normal automatic report sinks | Bypassed in diagnostic mode |
| Event storage | Plain normal JSONL + separate deep TRACE/DEBUG stream |
| Deep trace completion | Lossless gzip |
| Interrupted deep trace | Raw JSONL remains valid |
| Run catalog/index | Sparse, derived, rebuildable |
| Failure signature | V2 site-aware when site exists; V1 fallback otherwise |
| Screenshot format | Preserve original PNG bytes |
| Base64 screenshot copy | Not created |
| Fingerprint storage | Versioned compact `.pkbf` sidecar |
| Adjacent visual comparison | `comparisonToPrevious` metadata |
| Full fingerprint arrays in AI indexes | No |
| Cross-run visual matching | At most four representative pairs per matched scenario |
| Visual exactness signal | `decodedPixelsExactlyEqual` + dimensions |
| PNG inspection | Only when semantic visual content matters |
| TRACE inspection | Final evidence layer when structured/INFO+ is insufficient |
| Routine comparison/rebuild | `DiagnosticCli` |
| Large values | Bounded; truncation marked |
| Sensitive values | Redacted/hashed before persistence |
| Agent execution/orchestration | External to Pickleball 2.1.3 |

## 37. Final design principle

The system preserves detailed evidence as efficient, recoverable records while presenting AI consumers with compact run catalogs/indexes, scenario summaries, failure clusters, visual comparison metadata, and precise references.

An AI should not repeatedly consume every trace event, screenshot, fingerprint, or run.

It should progressively identify:

```text
which investigation matters
-> which runs provide meaningful comparison
-> which conditions changed
-> which failures or states changed
-> which scenario or cluster requires investigation
-> whether sparse/normal evidence already answers the question
-> whether fingerprint metadata answers the visual question
-> which representative image or trace is actually decisive
```

That progression is the core resource-efficiency contract for Pickleball 2.1.3 diagnostic evidence.
