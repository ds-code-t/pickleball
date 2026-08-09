# Pickleball 2.1.3 failure-cluster metadata follow-up

This bundle is intended to be copied over the repository after the 2.1.3 site-aware failure-signature fix has already been applied.

It keeps the verified V2 clustering behavior and adds the sparse metadata an AI/developer needs to understand why two failures are in different clusters without opening dense event logs.

## Replacement files

- `src/main/aspectj/tools/dscode/common/reporting/diagnostic/Diagnostic213CompletionAspect.aj`
- `maven-consumer-project/src/test/java/tools/dscode/common/reporting/diagnostic/Diagnostic213CompletionChecks.java`
- `docs/diagnostic-reporting.md`
- `docs/agent/feature-map.md`

All project files in this bundle are full replacements, not patches.

## Resulting failure metadata

For a structured step failure, the sparse scenario summary and run index now retain:

```json
{
  "failureSignature": "...",
  "failureSignatureVersion": 2,
  "failureSiteKey": "...",
  "failureSite": {
    "feature": "features/diagnostic-reporting-validation.feature",
    "stepLine": 60,
    "definition": "tools.dscode.coredefinitions.DynamicSteps#executeDynamicStep"
  }
}
```

`clusters.json` carries the same metadata. `DiagnosticRunComparator` keeps it in compact scenario transitions. `DiagnosticIndexRebuilder` preserves it when rebuilding clusters from surviving scenario summaries.

If no structured step site exists, the previous class/message-only signature is preserved and `failureSignatureVersion` is `1`; no fake site metadata is created.

## Validate

From the Pickleball repository root:

```powershell
.\gradlew.bat test publishToMavenLocal
python scripts/refresh_agent_index.py --check
python scripts/verify_agent_contract.py
```

From `maven-consumer-project`, run the consumer-hosted internal checks:

```powershell
mvn test -Dpkb_tags="@diagnostic-single"
```

Then rerun the focused cluster scenario set:

```powershell
mvn test -Dpkb_tags="@diagnostic-cluster-validation" -Dpkb_reportingmode="diagnostic" -Dpkb_reportretention="all" -Dpkb_browser="CHROME_HEADLESS" -Dpkb_investigation_id="diag-213-cluster-metadata" -Dpkb_run_purpose="failure-signature-metadata"
```

The cluster-validation Maven command is expected to fail because both scenarios intentionally fail.

For each failed scenario, verify `summary.json` and the corresponding `run-index.json` scenario entry contain `failureSignatureVersion`, `failureSiteKey`, and `failureSite`. Verify each `clusters.json` entry contains the same metadata.

The two intentional failures should still have different `failureSignature` and `failureSiteKey` values.

No IntelliJ Cucumber rerun is required for this follow-up; the internal checks validate canonical Maven/IntelliJ feature-source handling and the sparse metadata/rebuild/comparison behavior.
