# Pickleball Maven Consumer Project Guide

This is the canonical human-readable guide for the executable `maven-consumer-project` example and the basic shape of an external Maven consumer.

The nested consumer intentionally keeps its own Markdown minimal. Detailed usage and AI guidance live in Pickleball core and are packaged into the Maven artifact so an external consumer can materialize version-matched instructions.

## Materialize dependency guidance

From a Maven consumer with Pickleball on the test classpath:

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

Rerun export before Pickleball work even when `.pickleball` already exists. A successful export overwrites current managed files, removes obsolete previously managed files, writes the manifest last, and best-effort keeps `.pickleball` ignored by Git. If export fails, treat existing generated guidance as potentially stale.

Compatibility note: an older Pickleball release whose exporter predates the manifest lifecycle may leave newer files or a newer manifest behind after a downgrade. Those leftovers are not authoritative for the downgraded dependency; prefer the dependency actually resolved on the test classpath and the files freshly exported by that dependency.

AI agents should read `.pickleball/AGENT-GUIDE.md` first after a successful export.

## Purpose

`maven-consumer-project` is both:

1. A normal Maven example consuming Pickleball as a test-scoped dependency.
2. An executable compatibility/integration test for the framework.

Its runner starts a loopback-only local test site so scenarios can exercise Selenium DOM behavior and local REST/SOAP-style calls without an external application.

## Requirements and run

- JDK 21
- Maven 3.9 or newer
- Chrome available to Selenium, or another configured browser

```bash
mvn test
```

or use the included wrappers:

```bash
./mvnw test
```

```powershell
.\mvnw.cmd test
```

`PickleballTests` starts the test server on `127.0.0.1:8765` before Cucumber and stops it afterward.

Runner defaults include:

- glue `com.example.pickleball`;
- features `classpath:features`;
- config mapping root `configs`;
- plugin `pretty`;
- tags `@all`;
- browser `chrome`.

Normal Pickleball source precedence applies unless controlled `pkb_runvars` are active. Public normal precedence from strongest to weakest is JVM `-D`, `globalTestProperties()`, `pickleball_local.properties`, `pickleball.properties`, then `globalTestDefaults()`.

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
├── src/test/resources/profiles_local.yaml
├── src/test/resources/pickleball.properties
└── src/test/resources/pickleball_local.properties
```

Important locations:

- runner and consumer element vocabulary — `PickleballTests.java`;
- local browser/service test server — `LocalTestSite.java`;
- executable scenarios — `src/test/resources/features`;
- reusable service calls — `src/test/resources/calls`;
- shared `<configs...>` data — `src/test/resources/configs`;
- structured/scenario data — `src/test/resources/data`;
- local static test site — `src/test/resources/site`;
- shared named profiles — `src/test/resources/profiles.yaml`;
- local named-profile overrides — `src/test/resources/profiles_local.yaml`;
- shared Pickleball properties — `src/test/resources/pickleball.properties`;
- local Pickleball property overrides — `src/test/resources/pickleball_local.properties`.

## What the consumer exercises

The executable project covers Selenium navigation/selection/actions/assertions/dialogs, dynamic/chained steps, nested steps and conditionals, keyboard expressions, mapping/templates/files/Data Elements, component scenarios, reusable scenario selection, REST/SOAP service calls, date/time utilities, configuration/profile/controlled-RunVar behavior, diagnostic evidence/retention/comparison, and consumer-hosted internal Java compatibility checks.

## Focused runs

Common suite tags include `@all`, `@regression`, `@smoke`, `@browser`, and `@data`. Functional areas include `@navigation`, `@forms`, `@catalog`, `@mapping`, `@resources`, `@workflow`, `@keyboard`, `@dialogs`, and `@components`.

```bash
mvn test -Dpkb_tags="@forms and @state-assertions"
mvn test -Dpkb_tags="@workflow and @nested-steps and not @block-conditionals"
```

The consumer `pom.xml` also defines Maven profiles such as:

```bash
mvn test -Pall
mvn test -Psmoke
mvn test -Pforms
mvn test -Pworkflow
mvn test -Pcomponents
```

Feature files remain authoritative for exact scenario tags.

## Named profiles and controlled RunVars

Reusable profiles live in `profiles.yaml`; local property-level overrides for matching profile names can live in `profiles_local.yaml`. Select one or more profiles with `pkb_profile`; selected names compose left-to-right and later profiles win.

For deterministic automation or AI reruns, use `pkb_runvars` as direct input:

```bash
mvn test "-Dpkb_runvars=pkb_tags=@smoke, pkb_browser=CHROME_HEADLESS"
```

or expanded members:

```text
pkb_runvars.pkb_tags=@smoke
pkb_runvars.pkb_browser=CHROME_HEADLESS
```

A partial controlled input inherits only missing project execution-context RunVars: `pkb_glue`, `pkb_features`, `pkb_datapath`, `pkb_callpath`, `pkb_componentpath`, and `pkb_configpath`. Explicit blank members suppress that inheritance. Optional normal values do not leak into the controlled run.

`pkb_run_profile` is the deterministic final serialized RunVar output. External direct `pkb_run_profile` input is rejected; use `pkb_runvars`.

See `docs/configuration.md` and `docs/ai-run-configuration.md`.

## Config mapping path

Prefer the source-qualified configuration syntax regardless of source location:

```text
<config:URL.forms>
<config:users.admin>
```

Legacy `<configs.URL.forms>` / `<configs.users.admin>` remains supported.

`pkb_configpath` changes where that mapping is loaded from. Missing/blank uses the historical `configs` Java fallback. Runtime configs are loaded only after final RunVar/profile resolution, so `<configs...>` cannot be used to resolve `pkb_configpath` or another RunVar.

## Diagnostic reporting

Enable the AI-oriented evidence pipeline with:

```properties
pkb_reportingmode=diagnostic
```

Diagnostic runs default beneath `reports/diagnostic-runs`. For AI-driven execution, keep console verbosity low because structured diagnostic capture is independent of console `pkb_loglevel`; prefer `pkb_loglevel=warn`, or `error` when appropriate.

Use the shallowest evidence that answers the question:

```text
run-catalog.json
-> selected run-index.json / clusters.json
-> selected scenario summary.json
-> targeted events.jsonl
-> visual comparison metadata / fingerprint comparison
-> representative screenshot only when semantic interpretation matters
-> deep trace only when higher-level evidence is insufficient
```

Do not recursively ingest an entire run.

### Diagnostic CLI

```text
DiagnosticCli compare-runs <left-run-index> <right-run-index> [output-json]
DiagnosticCli compare-fingerprints <left.pkbf> <right.pkbf> [output-json]
DiagnosticCli rebuild <diagnostic-runs-root-or-run-root>
```

See `docs/diagnostic-reporting.md` for evidence details.

## Controlled AI/automation reruns

When evidence supports a bounded rerun:

1. Read the selected run's retained `runProfile`.
2. Replay it through `pkb_runvars` (compact or expanded).
3. Preserve blank assignments.
4. Change only RunVars required by the hypothesis.
5. Supply lineage separately through `pkb_investigation_id`, `pkb_run_purpose`, `pkb_parent_run_id`, `pkb_baseline_run_id`, and `pkb_changed_variables`.
6. Use `pkb_changed_variables` only for canonical RunVar names intentionally changed; omit it for source-only fixes.
7. Verify `runProfileFingerprint` and source/comparison evidence afterward.

`pkb_parent_run_id` is the immediate predecessor; `pkb_baseline_run_id` is the stable comparison anchor. The diagnostic field `directRunProfile` is retained for schema compatibility and indicates that direct controlled RunVars were active.

## Consumer-specific element vocabulary

`PickleballTests` demonstrates extending the execution dictionary without custom Cucumber steps. The example registers categories such as `Radio Button`, `Test Panel`, `Product Card`, and `Status Badge`; the feature suite can continue using Pickleball's reusable dynamic steps.

## Notes

- Pickleball is intentionally test-scoped.
- The runner class name ends in `Tests` for Maven Surefire discovery.
- Standard Cucumber and consumer Java glue can coexist with Pickleball dynamic steps.
- Port `8765` must be available for the example test server.
- Nested README/AGENTS files are minimal adapters; detailed guidance is owned by Pickleball core and exported from the dependency.

Use `docs/README.md` for the complete version-matched Pickleball syntax/documentation map.
