# Pickleball Maven Consumer Project Guide

This is the canonical human-readable guide for the executable `maven-consumer-project` example and the basic shape of an external Maven consumer.

The nested consumer intentionally keeps its own Markdown minimal. Detailed usage and AI guidance live in Pickleball core and are packaged into the Maven artifact so an external consumer can materialize version-matched instructions and working reference examples.

## Materialize dependency guidance

From a Maven consumer with Pickleball on the test classpath:

```powershell
mvn -q org.codehaus.mojo:exec-maven-plugin:3.5.0:java "-Dexec.mainClass=tools.dscode.common.reporting.diagnostic.DiagnosticCli" "-Dexec.classpathScope=test" "-Dexec.args=export-guidance .pickleball"
```

Then read or browse:

```text
.pickleball/GUIDANCE-MANIFEST.json
.pickleball/AGENT-GUIDE.md
.pickleball/docs/README.md
.pickleball/docs/consumer-project.md
.pickleball/maven-consumer-project/
```

Rerun export before Pickleball work even when `.pickleball` already exists. A successful export overwrites current managed files, removes obsolete previously managed files, writes the manifest last, and best-effort keeps `.pickleball` ignored by Git. If export fails, treat existing generated guidance as potentially stale.

Compatibility note: an older Pickleball release whose exporter predates the manifest lifecycle may leave newer files or a newer manifest behind after a downgrade. Those leftovers are not authoritative for the downgraded dependency; prefer the dependency actually resolved on the test classpath and the files freshly exported by that dependency.

AI agents should read `.pickleball/AGENT-GUIDE.md` first after a successful export. That guide's tool chooser is the agent path: headless Workbench MCP (`mcp .`), one bounded diagnostic `mvn test`, then edits to the real consumer source. Do not treat `.pickleball/maven-consumer-project/` as the project under test, and do not dump `docs/README.md` or the whole snapshot into first-read context. Human readers can start with `.pickleball/docs/README.md`; links from those guides to `maven-consumer-project` resolve to the exported version-matched reference files.

## Version-matched reference snapshot

`export-guidance` also materializes a curated, read-only snapshot of the canonical Pickleball Maven consumer under `.pickleball/maven-consumer-project/`. It is a version-matched **reference** of Pickleball's own example consumer for on-demand lookup, not a sandbox and not the consumer project under test. `export-guidance` does not copy the current consumer's own features into `.pickleball` for testing.

The snapshot includes:

- `pom.xml`;
- the `PickleballTests` runner and `LocalTestSite` browser/service test server;
- executable feature files;
- reusable service-call definitions;
- configuration, data, and file fixtures;
- static local test-site resources; and
- the committed shared/local `profiles*.yaml` and `pickleball*.properties` examples.

It intentionally excludes Maven wrappers, Git/IDE/generated artifacts, the consumer `AGENTS.md` and `.github/copilot-instructions.md` bridges, internal Java verification classes, and maintainer-only `_local2` files. It is reference material, not another consumer project to copy, edit, or run. Make changes in the real consumer project; a future guidance export may replace every managed file in this snapshot. `.pickleball/workbench/live/classes` is a compiled worker overlay, not an editor.

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

## Launch the dependency-matched Workbench

The test-scoped Pickleball dependency already contains its controller-only Workbench payload. Consumer AI agents start the **headless MCP** launcher from the resolved test classpath:

```bash
./mvnw -q org.codehaus.mojo:exec-maven-plugin:3.5.0:java \
  -Dexec.mainClass=tools.dscode.launcher.PickleballWorkbenchLauncher \
  -Dexec.classpathScope=test \
  "-Dexec.args=mcp ."
```

```powershell
.\mvnw.cmd -q org.codehaus.mojo:exec-maven-plugin:3.5.0:java "-Dexec.mainClass=tools.dscode.launcher.PickleballWorkbenchLauncher" "-Dexec.classpathScope=test" "-Dexec.args=mcp ."
```

Humans who want the Swing player can pass `ui .` instead. Agents for this release should not use the GUI, `ui .`, or `attach.json` as their path. The launcher verifies and extracts the opaque payload beneath `.pickleball/workbench/controller/<sha256>/`, then creates a separate Workbench JVM. Workbench captures this project's compiled outputs and effective test runtime before creating a separate worker JVM. Only the worker loads the consumer-resolved Pickleball runtime; the Workbench artifact and process contain no core implementation. See `docs/pickleball-workbench.md` for commands, lifecycle, protocol compatibility, and isolation checks. The live-loop order lives in `.pickleball/AGENT-GUIDE.md`.

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

When this guide is read from `.pickleball/docs/consumer-project.md`, the same paths are available beneath `.pickleball/maven-consumer-project/` as version-matched reference material.

## What the consumer exercises

The executable project covers Selenium navigation/selection/actions/assertions/dialogs, dynamic/chained steps, nested steps and conditionals, keyboard expressions, mapping/templates/files/Data Elements, component scenarios, reusable scenario selection, REST/SOAP service calls, date/time utilities, configuration/profile/controlled-RunVar behavior, diagnostic evidence/retention/comparison, and consumer-hosted internal Java compatibility checks.

## Focused runs

Common suite tags include `@all`, `@regression`, `@smoke`, `@browser`, and `@data`. Functional areas include `@navigation`, `@forms`, `@catalog`, `@mapping`, `@resources`, `@workflow`, `@keyboard`, `@dialogs`, and `@components`.

Controller/protocol migration checks must remain focused: use `@control-bridge` and/or `@step-override-bridge`, set `pkb_parallel=80` when practical, and do not run `@all` for Workbench isolation work.

```bash
mvn test -Dpkb_tags="@forms and @state-assertions"
mvn test -Dpkb_tags="@workflow and @nested-steps and not @block-conditionals"
```

Human `PickleballTests` defaults remain `pretty` and `@all`. Agents launching a bounded confirmation should not reuse those defaults. Use a separate `pkb_runvars` command, for example:

```bash
mvn test -Dpkb_runvars="pkb_tags=@the-failing-tag, pkb_name=The failing scenario, pkb_browser=CHROME_HEADLESS, pkb_reportingmode=diagnostic, pkb_loglevel=warn, pkb_reportretention=failed"
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
DiagnosticCli emit-investigation <investigation-json-or--> <consumer-project-root>
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
- Nested README/AGENTS files are minimal adapters; detailed guidance and version-matched reference examples are owned by Pickleball core and exported from the dependency.

Human readers can use `docs/README.md` for the complete version-matched Pickleball syntax/documentation map and `maven-consumer-project/` for the corresponding working reference files. Agents should not treat those as first-read.
