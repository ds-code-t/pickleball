# Pickleball Maven Consumer Project Guide

This is the canonical human-readable guide for the executable `maven-consumer-project` example and for the basic shape of an external Maven consumer.

The nested consumer project intentionally keeps its own Markdown documentation minimal. Detailed usage and AI guidance live in Pickleball core and are also packaged into the Pickleball Maven artifact so a copied external consumer can materialize the same version-matched documentation.

## Materialize the dependency documentation

From a Maven consumer where Pickleball is available on the test classpath:

```powershell
mvn -q org.codehaus.mojo:exec-maven-plugin:3.5.0:java "-Dexec.mainClass=tools.dscode.common.reporting.diagnostic.DiagnosticCli" "-Dexec.classpathScope=test" "-Dexec.args=export-guidance .pickleball"
```

Then read:

```text
.pickleball/GUIDANCE-MANIFEST.json
.pickleball/AGENT-GUIDE.md
.pickleball/docs/README.md
.pickleball/docs/consumer-project.md
```

The `.pickleball` directory is generated local guidance. The exporter should be rerun before Pickleball work even when the directory already exists; this avoids requiring a human or AI agent to detect dependency-version changes itself. A successful export records the exporting Pickleball version and managed-file list in `GUIDANCE-MANIFEST.json`, removes obsolete files that were managed by the previous manifest, and overwrites current files from the dependency. If export fails, treat any existing `.pickleball` contents as potentially stale.

When the output directory is exactly `.pickleball`, the exporter also tries to keep it out of Git without making export brittle. It first appends an ignore rule to an existing consumer/repository `.gitignore` when available, otherwise it falls back to the repository-local `.git/info/exclude`. It never creates or commits a new `.gitignore`, changes the Git index, or fails the guidance export merely because an ignore rule could not be added. The checked-in consumer example already ignores `/.pickleball/`, so copied consumers normally need no mutation.

AI agents should read `.pickleball/AGENT-GUIDE.md` first after a successful export.

## Purpose

`maven-consumer-project` is both:

1. A normal Maven example showing how a project consumes Pickleball as a test-scoped dependency.
2. An executable compatibility and integration test for the Pickleball framework.

The runner starts a loopback-only local test site so scenarios can exercise Selenium DOM behavior and local REST/SOAP-style service calls without depending on an external application.

## Requirements

- JDK 21
- Maven 3.9 or newer
- Chrome available to Selenium, or another browser configured through Pickleball

## Run the consumer

From `maven-consumer-project`:

```bash
mvn test
```

Or use the included Maven wrapper:

```bash
./mvnw test
```

Windows:

```powershell
.\mvnw.cmd test
```

`PickleballTests` starts the local test server on `127.0.0.1:8765` before the Cucumber run and stops it afterward.

The runner defaults include:

- glue: `com.example.pickleball`
- features: `classpath:features`
- plugin: `pretty`
- tags: `@all`
- browser: `chrome`

Normal Pickleball configuration precedence still applies, so command-line and other supported configuration sources can override applicable values.

## Project layout

```text
maven-consumer-project/
├── pom.xml
├── src/test/java/com/example/pickleball/PickleballTests.java
├── src/test/java/com/example/pickleball/support/LocalTestSite.java
├── src/test/resources/features/
├── src/test/resources/calls/
├── src/test/resources/configs/
├── src/test/resources/data/
├── src/test/resources/site/
├── src/test/resources/profiles.yaml
└── src/test/resources/pickleball*.properties
```

Important locations:

- `src/test/java/com/example/pickleball/PickleballTests.java` — consumer runner and consumer-specific element vocabulary.
- `src/test/java/com/example/pickleball/support/LocalTestSite.java` — loopback browser/service test server.
- `src/test/resources/features` — executable consumer scenarios.
- `src/test/resources/calls` — reusable service-call definitions.
- `src/test/resources/configs` — example shared configuration.
- `src/test/resources/data` — structured example data and reusable feature data.
- `src/test/resources/site` — local HTML/JavaScript test pages.
- `src/test/resources/profiles.yaml` — example Pickleball profiles.

For capability-specific framework documentation, use `docs/README.md`.

## What the consumer exercises

The executable project covers framework behavior including:

- Selenium navigation, element selection, interactions, assertions, dialogs, and custom element categories.
- Dynamic steps and chained phrases.
- Nested steps and block conditionals.
- Keyboard expressions.
- Mapping, templating, files, scenario data, and Data Elements.
- Component scenarios and reusable scenario selection.
- REST/SOAP-style service-call scenarios against the local server.
- Date/time utilities.
- Configuration sources, named profiles, direct run profiles, and system-property compatibility.
- Diagnostic reporting, retention, run indexes, failure evidence, comparison utilities, and controlled rerun metadata.
- Internal Java compatibility checks compiled and executed from the consumer side against the Pickleball dependency.

## Tagging and focused runs

Common suite tags include:

| Tag | Purpose |
|---|---|
| `@all` | Aggregate consumer entry point and runner default |
| `@regression` | Broad regression coverage |
| `@smoke` | Representative smoke coverage |
| `@browser` | Scenarios that require browser behavior |
| `@data` | Data/resource-oriented coverage |

Common functional-area tags include:

| Tag | Area |
|---|---|
| `@navigation` | Navigation |
| `@forms` | Form controls and dynamic actions |
| `@catalog` | Element catalog/context behavior |
| `@mapping` | Mapping and templating |
| `@resources` | Resource lookup |
| `@workflow` | Nested and conditional flows |
| `@keyboard` | Keyboard expressions |
| `@dialogs` | Browser dialogs |
| `@components` | Component scenarios |

Run a tag expression directly:

```bash
mvn test -Dpkb_tags="@forms and @state-assertions"
```

Examples:

```bash
mvn test -Dpkb_tags="@catalog"
mvn test -Dpkb_tags="@workflow and @nested-steps and not @block-conditionals"
mvn test -Dpkb_tags="@browser and not @dialogs"
mvn test -Dpkb_tags="@data and @resource-mapping"
```

The consumer `pom.xml` also defines Maven profiles as convenient tag entry points:

```bash
mvn test -Pall
mvn test -Psmoke
mvn test -Pforms
mvn test -Pworkflow
mvn test -Pcomponents
```

The executable feature files remain the source of truth for the exact tags attached to each scenario. Do not maintain a separate static scenario/tag matrix.

## Diagnostic reporting

Enable the AI-oriented diagnostic evidence pipeline with:

```properties
pkb_reportingmode=diagnostic
```

Diagnostic runs are written beneath `reports/diagnostic-runs` by default.

For troubleshooting, humans and AI agents should follow `docs/diagnostic-reporting.md`. Its key rule is to use the shallowest evidence layer that answers the question and stop there.

The preferred order is:

```text
run-catalog.json
-> selected run-index.json / clusters.json
-> selected scenario summary.json
-> targeted events.jsonl when needed
-> existing visual comparison metadata / fingerprint comparison
-> representative screenshot only when its visual meaning matters
-> deep trace only when higher-level evidence is insufficient
```

Do not recursively ingest an entire diagnostic run.

### Diagnostic CLI

Compare two run indexes:

```powershell
mvn org.codehaus.mojo:exec-maven-plugin:3.5.0:java "-Dexec.mainClass=tools.dscode.common.reporting.diagnostic.DiagnosticCli" "-Dexec.classpathScope=test" "-Dexec.args=compare-runs reports/diagnostic-runs/<left-run>/run-index.json reports/diagnostic-runs/<right-run>/run-index.json target/diagnostic-comparison.json"
```

Compare two visual fingerprint sidecars without opening PNG screenshots:

```powershell
mvn org.codehaus.mojo:exec-maven-plugin:3.5.0:java "-Dexec.mainClass=tools.dscode.common.reporting.diagnostic.DiagnosticCli" "-Dexec.classpathScope=test" "-Dexec.args=compare-fingerprints reports/diagnostic-runs/<left-run>/scenarios/<scenario>/fingerprints/<left>.pkbf reports/diagnostic-runs/<right-run>/scenarios/<scenario>/fingerprints/<right>.pkbf target/fingerprint-comparison.json"
```

Rebuild derived indexes, clusters, the shared catalog, and recoverable fingerprint sidecars:

```powershell
mvn org.codehaus.mojo:exec-maven-plugin:3.5.0:java "-Dexec.mainClass=tools.dscode.common.reporting.diagnostic.DiagnosticCli" "-Dexec.classpathScope=test" "-Dexec.args=rebuild reports/diagnostic-runs"
```

See `docs/diagnostic-reporting.md` for the complete evidence contract and CLI behavior.

## Controlled AI/automation reruns

When diagnostic evidence supports a bounded rerun, use the selected run's retained `runProfile` rather than manually reconstructing effective RunVars from defaults, properties, profiles, system properties, and Cucumber aliases.

Follow `docs/ai-run-configuration.md` for:

- compact `pkb_run_profile`;
- expanded `pkb_run_profile.<pkb_var>` values;
- `runProfileFingerprint`;
- protected values;
- diagnostic lineage such as `pkb_investigation_id`, `pkb_parent_run_id`, and `pkb_changed_variables`.

Use `pkb_changed_variables` only for Pickleball RunVar names intentionally changed for the rerun. If only source, Gherkin, mappings, or test data changed, omit it and describe the validation goal in `pkb_run_purpose`; diagnostic source provenance records the source difference. `pkb_parent_run_id` is the immediate predecessor, while `pkb_baseline_run_id` is the stable comparison anchor across a multi-run investigation. See `docs/diagnostic-lineage-metadata.md` for the complete metadata contract.

## Local configuration

To keep machine-specific settings out of Git, use the consumer's local Pickleball property conventions rather than changing shared defaults for one workstation.

See `docs/configuration.md` for the complete source precedence and profile contract.

## Consumer-specific element vocabulary

`PickleballTests` demonstrates that a consumer can extend the execution dictionary without adding custom Cucumber steps. The example registers consumer-specific element categories such as:

- `Radio Button`
- `Test Panel`
- `Product Card`
- `Status Badge`

The rest of the feature suite can continue using Pickleball's reusable dynamic steps.

## Notes

- The Pickleball dependency is intentionally test-scoped.
- The runner class name ends in `Tests`, so Maven Surefire discovers it normally.
- Standard Cucumber steps and consumer-specific Java glue can coexist with Pickleball dynamic steps.
- Port `8765` must be available while the local test server is running.
- The nested README and AGENTS files are deliberately minimal adapters. Detailed guidance is owned by Pickleball core and exported from the Maven dependency.

## Sample-project evolution

The current multi-page consumer evolved from the original smaller example by:

1. Replacing the single-page-only server with a classpath static-file server.
2. Replacing the original `index.html` playground with a navigation dashboard.
3. Adding focused playground pages plus shared CSS and JavaScript.
4. Splitting dynamic behavior across purpose-specific feature files.
5. Adding nested and block-conditional examples.
6. Adding reusable component-scenario coverage.
7. Adding keyboard-expression and browser-dialog coverage.
8. Adding YAML, JSON, CSV, text, and on-demand resource mapping examples.
9. Adding project-specific element categories in the test runner.
10. Expanding shared URL configuration for the local pages.
11. Adding broad, functional-area, and focused capability tags.
12. Adding Maven profiles as convenient tag-suite entry points.
13. Preserving direct `-Dpkb_tags` overrides.
14. Expanding the consumer over time to cover service calls, Data Elements, diagnostic reporting, controlled run profiles, and other framework contracts represented by the current documentation.
