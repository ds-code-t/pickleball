# Pickleball Agent Contract
## Purpose

Pickleball is a Java 21 testing framework distributed for use by consumer projects as a Maven dependency.

The repository also contains `maven-consumer-project`, which is both:

1. An example of how a consumer configures and uses Pickleball.
2. An executable compatibility and integration test for the framework.

Consumer scenarios start a local test-site server and exercise browser behavior through Selenium as well as REST/SOAP-style service calls, mappings, templates, dynamic Cucumber steps, nested flows, and component scenarios.
## Default agent behavior

For any request to add, change, fix, refactor, or remove project functionality:
1. Read this file.
2. Read `docs/agent/feature-map.md`.
3. Locate the current implementation, tests, consumer scenarios, and documentation for the affected capability.
4. Infer repository setup, build configuration, project goals, and established conventions from the repository.
5. Make the requested change across every affected surface.
6. Run the applicable validation.
7. Report changed behavior, compatibility implications, documentation updates, and validation results.

Do not require the user to repeat the project background, repository layout, test-server setup, dependency model, documentation policy, or definition of done.

Ask for clarification only when the requested product behavior remains materially ambiguous after reviewing the repository. Prefer existing conventions and backward-compatible behavior when a reasonable interpretation is available.

### Diagnostic investigation protocol

When investigating Pickleball diagnostic evidence, use the shallowest evidence layer that completely answers the question. Do not advance to a denser layer merely because it exists.

Use this escalation order:

1. `run-catalog.json` to choose relevant runs.
2. Selected `run-index.json` files and `clusters.json` for outcomes, scenario identity, failure grouping, capabilities, retention state, and representative visual references.
3. Selected scenario `summary.json` files for additional sparse scenario detail.
4. Only the relevant scenario `events.jsonl` when exact step/lifecycle/order detail is still unanswered.
5. Existing `comparisonToPrevious` visual metadata or Pickleball fingerprint/run comparison before opening any screenshot.
6. A representative PNG only when the semantic content of a visual difference must be understood.
7. `trace.jsonl.gz` or interrupted raw `trace.jsonl` only when structured/INFO+ evidence is insufficient.

Stop reading as soon as the current layer answers the investigation with sufficient confidence.

For visual evidence:

- Never open a PNG merely to determine whether two screenshots differ.
- Prefer already-recorded `comparisonToPrevious` metadata for adjacent screenshots.
- For cross-run or explicit fingerprint comparison, use Pickleball's `DiagnosticCli`, `DiagnosticRunComparator`, or `VisualFingerprintComparator`; do not manually decode `.pkbf` files or invent an image-comparison algorithm.
- `decodedPixelsExactlyEqual=true` means the decoded rendered pixels are equal; no PNG inspection is needed to establish visual equality.
- `IDENTICAL` ends a visual-difference investigation unless the user explicitly needs the image itself.
- `VERY_SIMILAR`, `SOMEWHAT_SIMILAR`, or `VERY_DIFFERENT` establish that pixels differ and provide magnitude. Open a representative PNG only if the question requires interpreting what changed.
- Raw PNG byte equality proves the files are identical, but raw PNG byte inequality does not prove their rendered pixels differ because encoding or metadata may differ. Prefer decoded-pixel equality from the fingerprint comparator.

For routine diagnostic utility operations, prefer `tools.dscode.common.reporting.diagnostic.DiagnosticCli` over constructing a Maven classpath and JShell script. See `docs/diagnostic-reporting.md` for commands.
## Sources of truth

Use all relevant evidence rather than trusting one file in isolation:
- Current implementation under `src/main/java` and `src/main/aspectj`
- Consumer-hosted internal Java checks under `maven-consumer-project/src/test/java`
- Executable consumer examples under `maven-consumer-project/src/test`
- `README.md` and the guides under `docs`
- Build and dependency configuration in `build.gradle` and `maven-consumer-project/pom.xml`
- `docs/agent/feature-map.md` for navigation, not as a replacement for source inspection

When implementation, tests, examples, and documentation disagree:

1. Identify the inconsistency.
2. Determine the intended contract from the strongest available evidence.
3. Preserve established consumer behavior unless the user explicitly requests a breaking change.
4. Update the inconsistent surfaces together.
5. State the resolution in the final report.
## Repository structure
- `src/main/java` — framework implementation and Cucumber integrations
- `src/main/aspectj` — AspectJ integrations and weaving behavior
- `src/main/resources` — framework resources
- `src/test` — reserved for tests that must run inside the framework build
- `docs` — detailed user-facing documentation
- `maven-consumer-project` — executable Maven consumer example
- `maven-consumer-project/src/test/resources/features` — consumer acceptance scenarios
- `maven-consumer-project/src/test/resources/calls` — service-call definitions
- `maven-consumer-project/src/test/resources/configs` — example configuration
- `maven-consumer-project/src/test/resources/site` — local browser/service test site
- `maven-consumer-project/src/test/java` — runner, local server, support code, and internal framework checks compiled against the locally published dependency
## Public contracts

Treat these as consumer-visible contracts unless source evidence clearly shows otherwise:
- Public Java APIs
- Maven artifact behavior and runtime dependencies
- Cucumber step phrases and dynamic-step behavior
- Mapping, template, key-expression, and reference syntax
- Configuration keys, defaults, and resource lookup rules
- Selenium element lookup, interaction, retry, and stale-element behavior
- Service-call definitions, request/response mapping, and REST/SOAP behavior
- Component-scenario, nested-step, and conditional-flow semantics
- Cucumber compatibility and AspectJ weaving behavior
- The behavior demonstrated by the Maven consumer scenarios

Do not silently rename or remove public syntax, steps, configuration, APIs, or documented behavior.
## Required impact analysis

Before editing, search for:

- The implementation symbol or behavior
- Unit and component tests
- Gherkin phrases and examples
- Related documentation terms and headings
- Consumer configuration and test-site support
- Callers, adapters, interfaces, and serialization formats
- Compatibility assumptions in `maven-consumer-project`

Do not make a behavior change based only on a method or class name.
## Functionality-change requirements

For externally observable functionality, update all applicable areas:

- Framework implementation
- Consumer-hosted internal Java checks
- Maven consumer feature scenarios
- Service-call definitions
- Local test-site endpoints or pages
- Example configuration and test data
- README or detailed guides
- `docs/agent/feature-map.md` when ownership, locations, syntax, or contracts change
- `docs/agent/repository-index.md` when indexed repository files change

A task is not complete merely because the Java source compiles.
### Documentation policy

Update documentation when a change affects:

- Supported behavior or syntax
- Inputs, outputs, or value types
- Defaults, constraints, errors, or edge cases
- Public APIs, steps, configuration, or compatibility
- A user-visible example or recommended workflow

Do not create documentation churn for a purely internal refactor with no externally observable effect.

Prefer updating the existing canonical guide over creating a competing guide.

### Experimental value-conversion syntax

Do not document `ValConverter` special-value syntax in `README.md` or the
`docs` directory unless the task explicitly approves it. The markers are
experimental and may change or be removed. They may still receive focused
implementation and executable test coverage.

### Test policy

Use the narrowest useful test first, then run broader validation.

For consumer-visible behavior, add or update an executable scenario in `maven-consumer-project` whenever practical. A consumer scenario is preferred over a prose-only example.

Internal Java checks should normally live in `maven-consumer-project` and be exercised by the dedicated Cucumber feature so they compile and run against the locally published Pickleball dependency. Keep a test under root `src/test` only when it must execute inside the framework build itself.

Tests must cover the requested behavior and meaningful compatibility or edge cases. Do not weaken or delete assertions merely to make a change pass.
## Build and validation

Use Java 21.

Framework validation:

```shell
./gradlew test
```

Windows:

```powershell
.\gradlew.bat test
```

For consumer-visible changes, publish the current framework artifact locally and run the Maven consumer:

```shell
./gradlew test publishToMavenLocal
./maven-consumer-project/mvnw -f maven-consumer-project/pom.xml -U test -Dpkb_browser=CHROME_HEADLESS
```

Windows:

```powershell
.\gradlew.bat test publishToMavenLocal
.\maven-consumer-project\mvnw.cmd -f maven-consumer-project\pom.xml -U test -Dpkb_browser=CHROME_HEADLESS
```

Repository contract and generated-index checks:

```shell
python scripts/verify_agent_contract.py
python scripts/refresh_agent_index.py --check
```

Or run the turnkey validator:

```shell
scripts/agent_validate.sh
```

Windows:

```powershell
.\scripts\agent_validate.ps1
```

If a required validation cannot run, state exactly what was not run and why. Never claim that a test passed without executing it.
## Change boundaries
- Keep changes focused on the requested behavior.
- Do not perform unrelated refactors.
- Do not change versions, publish remote artifacts, create releases, or push branches unless explicitly requested.
- Do not edit generated build output.
- Preserve backward compatibility unless the user explicitly approves a breaking change.
- Follow existing code style and patterns before introducing new abstractions.
- Do not replace executable examples with prose.
- Never store secrets, credentials, machine-specific paths, or private data in agent instruction files.

### Temporary agent workspace

Use `.agent-work/` for disposable scripts, patches, generated bundles,
intermediate files, migration utilities, investigation output, and other
agent-created artifacts that are not maintained project code.

Do not place temporary automation in the repository root, `scripts/`, source
directories, test directories, or documentation directories. The committed
`scripts/` directory is only for reusable project-maintenance tooling intended
to remain part of the repository.

Delete temporary files after they have served their purpose. Before completing
a task, confirm that `.agent-work/` contains no files that need to be retained.
Never force-add `.agent-work/` content to Git.
## Agent-maintained context

`docs/agent/feature-map.md` is a living navigation map. Update it when:

- A capability is added or removed.
- Responsibility moves to different files or modules.
- A canonical test, scenario, endpoint, or guide changes.
- Public syntax or compatibility expectations change.

Run:

```shell
python scripts/refresh_agent_index.py
```

after adding, moving, or removing indexed source, test, documentation, or consumer files.

Do not use these files as substitutes for inspecting current source.
## Definition of done

A functionality change is complete only when:
- The requested behavior is implemented.
- Applicable compatibility has been preserved or a breaking change is clearly identified.
- Relevant consumer-hosted internal Java checks exist and pass.
- Relevant consumer scenarios exist and pass when applicable.
- Documentation matches the resulting behavior.
- The feature map remains accurate.
- The generated repository index is current.
- The final response summarizes the implementation, affected contracts, documentation, tests, and any validation not performed.
