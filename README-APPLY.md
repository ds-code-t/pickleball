# Pickleball 2.1.3 diagnostic fix validation

Copy this bundle over the repository root, preserving paths.

## Included fixes

1. Failure screenshot capture no longer creates a WebDriver for a scenario that never used one.
2. The normal `navigate to:` step now records `browser.navigation`.
3. Diagnostic index recovery preserves scenario-level capabilities such as `scenario.nested` and infers recoverable nested/screenshot capabilities from structured events.
4. Classpath feature sources resolve through the consumer module so Maven runs record repository-relative feature paths and hashes.
5. Scenario exact/semantic identity hashes use a canonical resource URI so Maven `classpath:` and IntelliJ `file:` executions of the same feature compare consistently while retaining the original feature URI in evidence.

## Build and publish

From the Pickleball repository root:

```powershell
.\gradlew.bat test publishToMavenLocal
python scripts/refresh_agent_index.py
python scripts/refresh_agent_index.py --check
python scripts/verify_agent_contract.py
```

## Automated Maven validation

Delete or rename the previous diagnostic output before this validation if you want a clean archive.

From `maven-consumer-project` run:

```powershell
.\run-diagnostic-fix-validation.ps1
```

The script runs seven separate `mvn test` processes and waits two seconds between them. Three runs intentionally fail at the scenario level; the script treats those non-zero Maven exits as expected and continues.

The script prints the absolute diagnostic output directory. Use that exact directory for the IntelliJ runs below so all runs land in one `run-catalog.json`.

## IntelliJ Cucumber properties

Set these in the Pickleball properties file used by the IntelliJ Cucumber run configuration:

```properties
pkb_reportingmode=diagnostic
pkb_reportretention=all
pkb_loglevel=INFO
pkb_browser=CHROME_HEADLESS
pkb_tags=@diagnostic-validation
pkb_investigation_id=diag-213-fix-validation
pkb_run_purpose=intellij-fix-validation
pkb_diagnostic_output=<ABSOLUTE PATH PRINTED BY THE MAVEN SCRIPT>
```

Run these scenarios individually from the IntelliJ Cucumber plugin:

1. `Diagnostic service call evidence`
2. `Diagnostic browser baseline`
3. `Diagnostic soft assertion failure evidence` — expected failure
4. `Diagnostic browser failure evidence` — expected failure

No separate IntelliJ hard-assertion rerun is needed because the automated Maven set already validates both `verify` and `ensure`, while the IntelliJ soft failure exercises the same no-browser cleanup path.

## What the returned evidence should prove

- Maven service and mixed-suite service scenarios retain `scenario.nested` in their summaries and indexes.
- `navigate to: URL.forms` records `browser.navigation`.
- Soft/hard assertion-only failures do not create browser capability flags, screenshot directories, or `data:,` screenshots.
- Browser failure still records the pre-cleanup `Scenario Failure` screenshot because a live driver already exists.
- Maven classpath feature sources include `maven-consumer-project/src/test/resources/...` plus SHA-256.
- Maven and IntelliJ executions of the same validation scenario have matching exact/semantic scenario identity keys despite different original URI forms.
- Run finalization, compressed trace evidence, sequencing, and CLI metadata remain unchanged.

After all Maven and IntelliJ runs, zip the entire diagnostic output directory printed by the script and attach it for review.
