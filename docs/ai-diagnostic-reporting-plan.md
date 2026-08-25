# AI Diagnostic Reporting, Visual Evidence, and Multi-Run Investigation Plan

## Purpose

Pickleball diagnostic reporting is an alternate evidence pipeline for AI-assisted troubleshooting. It preserves complete useful evidence while minimizing heap/report overhead, storage duplication, logging contention, AI token consumption, repeated screenshot analysis, and unnecessary reruns.

The system supports selecting failures from sparse indexes, targeted scenario investigation, cross-run comparison, controlled counterfactual reruns, source-fix validation, visual comparison without opening images unnecessarily, and recovery after interrupted runs.

## Current implementation decisions

- `pkb_reportingmode=diagnostic` enables diagnostic mode; missing/other values use normal reporting.
- Diagnostic capture retains TRACE-through-ERROR evidence independently of console `pkb_loglevel`.
- `pkb_reportretention=all|failed|none` controls automatic local evidence retention and defaults to `all`.
- Outcomes are `PASSED`, `FAILED`, `NO_TESTS`, or `UNKNOWN`; completion is separately `COMPLETE` or `INTERRUPTED`.
- Evidence is append-oriented while running and derived sparse indexes are rebuildable.
- Diagnostic screenshots store binary PNG plus compact `.pkbf` fingerprints; adjacent captures may include `comparisonToPrevious`.
- Failure clustering uses site-aware signatures when structured step-site metadata exists and a class/message fallback otherwise.
- `DiagnosticCli` is the maintained front end for guidance export, run comparison, fingerprint comparison, and rebuild/recovery.
- Configuration/source provenance is recorded with centralized sensitive-value redaction.

## Controlled execution model

The current controlled-rerun contract supersedes the earlier dual-purpose direct `pkb_run_profile` model:

- `pkb_runvars` is preferred controlled-run input.
- `pkb_run_profile` is deterministic canonical final RunVar output.
- Compact `pkb_runvars` and expanded `pkb_runvars.<pkb_var>` forms represent the same input and cannot be mixed.
- Compact/expanded external `pkb_run_profile` input is rejected because `pkb_run_profile` is reserved derived output.
- `run-index.json` retains sanitized `runProfile`, deterministic `runProfileFingerprint`, and compatibility field `directRunProfile`.
- A partial controlled input inherits only missing project execution-context RunVars: `pkb_glue`, `pkb_features`, `pkb_datapath`, `pkb_callpath`, `pkb_componentpath`, and `pkb_configpath`.
- Explicit blank execution-context members suppress inheritance and remain blank tombstones in the canonical profile/fingerprint so replay preserves each subsystem's historical fallback semantics.
- Optional project RunVars do not leak into controlled execution.
- `pkb_configpath` is resolved before runtime config documents are loaded; `<configs...>` mappings do not participate in RunVar/profile resolution.
- Agents should replay a selected run's retained `runProfile` through `pkb_runvars`, change only the intended RunVars, pass lineage separately, and verify the resulting fingerprint.

Diagnostic lineage remains metadata rather than execution RunVars:

```text
pkb_investigation_id
pkb_run_purpose
pkb_parent_run_id
pkb_baseline_run_id
pkb_changed_variables
```

## Evidence hierarchy

Use progressive disclosure:

```text
run-catalog.json
  -> selected run-index.json / clusters.json
      -> selected scenario summary.json
          -> targeted events.jsonl
              -> existing visual comparison metadata / Pickleball comparison
                  -> representative screenshot when semantic meaning matters
                      -> deep TRACE/DEBUG evidence when still necessary
```

Stop at the shallowest layer that answers the question with sufficient confidence. Do not recursively ingest an entire run.

## Run output structure

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

Interrupted scenarios may retain raw `trace.jsonl`. `DiagnosticIndexRebuilder` can recover derived indexes/summaries/clusters/catalog data and missing fingerprint sidecars when surviving evidence is sufficient.

## Sparse run and scenario evidence

Run/scenario indexes retain compact identities, outcomes, durations, failure signatures/sites, native capability observations, representative visual references, source/environment/configuration fingerprints, and links to deeper evidence.

Scenario identity deliberately uses multiple signals: execution ID, URI, scenario/outline lines, name, tags, example values, exact-source key, semantic/name keys, and source-order hints. No single field is treated as a permanent identity.

Native capability observations are positive-only: presence means Pickleball observed the capability; absence does not prove it was unused.

## Visual evidence contract

- Never open a PNG just to determine whether images differ.
- Prefer existing `comparisonToPrevious` for adjacent captures.
- Use `DiagnosticRunComparator`, `VisualFingerprintComparator`, or `DiagnosticCli` for explicit comparisons.
- `decodedPixelsExactlyEqual=true` establishes decoded rendered-pixel equality.
- Similarity categories establish whether/magnitude pixels differ; open a representative PNG only to interpret the visible meaning.
- Raw PNG byte inequality does not prove rendered-pixel inequality.
- Do not manually decode `.pkbf` or invent another comparison algorithm.

## Failure correlation

When structured step-site metadata exists, failure signatures combine normalized failure class/message with a stable site key derived from canonical feature source, Gherkin step line, and resolved definition method. No-site failures deliberately use the older class/message-only form. Signatures help select likely related failures but do not prove a common root cause.

## Configuration, protected values, and provenance

`configuration.json` retains effective configuration/provenance plus sanitized final `runProfile`, `runProfileFingerprint`, and `directRunProfile`. Sensitive nonblank RunVars use protected/redacted forms and one-way comparison inputs. Explicit blank sensitive tombstones stay blank rather than becoming protected references.

`source-provenance.json` and `environment.json` retain bounded debugging-relevant runtime/source identity. `pkb_gitsnapshot=metadata|diff|none` controls consumer Git capture.

## Controlled rerun protocol

1. Choose the relevant parent/baseline run from sparse evidence.
2. Read its `runProfile` and `runProfileFingerprint`.
3. Replay `runProfile` through `pkb_runvars`.
4. Preserve blank assignments.
5. Change only RunVars required by the hypothesis.
6. Supply lineage separately.
7. Run the narrowest useful scenario/tag selection.
8. Verify the new `runProfileFingerprint` and source/comparison evidence.
9. Escalate into denser evidence only if the result remains unexplained.

For a source-only fix, reuse the retained RunVars unchanged and omit `pkb_changed_variables`. For one intentional RunVar counterfactual, declare that canonical RunVar name (for example `pkb_browser`).

## Maintained CLI

```text
DiagnosticCli guidance
DiagnosticCli export-guidance [output-directory]
DiagnosticCli emit-investigation <investigation-json-or--> <consumer-project-root>
DiagnosticCli compare-runs <left-run-index> <right-run-index> [output-json]
DiagnosticCli compare-fingerprints <left.pkbf> <right.pkbf> [output-json]
DiagnosticCli rebuild <diagnostic-runs-root-or-run-root>
```

See `docs/diagnostic-reporting.md` for evidence use, `docs/ai-run-configuration.md` for controlled execution, and `docs/diagnostic-lineage-metadata.md` for investigation metadata.
