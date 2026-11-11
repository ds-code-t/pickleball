# Pickleball Agent Contract

## Purpose

Pickleball is a Java 21 testing framework distributed for use by consumer projects as a Maven dependency. The repository also contains `maven-consumer-project`, which serves as both an example of how a consumer configures and uses Pickleball and an executable compatibility and integration test for the framework. Consumer scenarios start a local test-site server and exercise browser behavior through Selenium as well as REST/SOAP-style service calls, mappings, templates, dynamic Cucumber steps, nested flows, and component scenarios.

## Default Agent Behavior

For any request to add, change, fix, refactor, or remove project functionality:

1. Read this file.
2. Read `docs/agent/feature-map.md`.
3. Locate the current implementation, tests, consumer scenarios, and documentation for the affected capability.
4. Infer repository setup, build configuration, project goals, and established conventions from the repository.
5. Make the requested change across every affected surface.
6. Run the applicable validation.
7. Report changed behavior, compatibility implications, documentation updates, and validation results.

Do not require the user to repeat the project background, repository layout, test-server setup, dependency model, documentation policy, or definition of done. Ask for clarification only when the requested product behavior remains materially ambiguous after reviewing the repository. Prefer existing conventions and backward-compatible behavior when a reasonable interpretation is available.

### Diagnostic Investigation Protocol

When investigating Pickleball diagnostic evidence, use the shallowest evidence layer that completely answers the question. Do not advance to a denser layer merely because it exists. Use this escalation order:

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

### Controlled Diagnostic Reruns

When an AI agent launches Pickleball tests and the intended execution settings are known, use `pkb_runvars` as the authoritative test-run input. Put intentional selection and execution RunVars such as `pkb_tags`, `pkb_name`, `pkb_example`, browser, reporting/logging controls, and other non-secret overrides in `pkb_runvars` rather than relying on ambient optional defaults. Use ordinary JVM `-Dpkb_*` RunVars or `pkb_profile` instead only when the task specifically needs to exercise normal configuration/profile precedence or the user explicitly requests those semantics. Protected secret values and diagnostic lineage remain separate.

Optional sealed `pkb_overriderunvars` freezes a complete already-resolved RunVar snapshot for that execution only. When it is absent or blank (and there are no expanded `pkb_overriderunvars.*` members), resolution is unchanged. When it is non-blank, that run ignores files, defaults, JVM `-D pkb_*` RunVars, profiles, `pkb_runvars`, execution-context inheritance, and templates for the Pickleball RunVar set. Maven/JVM/env still exist as the process; they must not change the Pickleball RunVar set. This is ignore-sources, not erase-process. The six context keys (`pkb_glue`, `pkb_features`, `pkb_datapath`, `pkb_callpath`, `pkb_componentpath`, `pkb_configpath`) must be present (blank tombstones allowed; missing keys are errors). No templates in the sealed bag. Sealed + `pkb_runvars` or a composing `pkb_profile` is a configuration error. `pkb_parallel=auto` is the only allowed derived mutation. Dry-run with `PKB_props.resolveRunVars` / `DiagnosticCli resolve-runvars` / Workbench `hint` without starting tests, then launch `-Dpkb_overriderunvars=<compact complete map>` and compare `runProfileFingerprint`. `pkb_overriderunvars` is INPUT; `pkb_run_profile` remains derived OUTPUT only.

When an investigation requires a rerun, start from the selected run's retained `runProfile` in `run-index.json`. Replay that retained final RunVar set through `pkb_runvars`, using compact `pkb_runvars` for ordinary values or expanded `pkb_runvars.<pkb_var>` members when avoiding nested assignment parsing is safer. Never mix compact and expanded `pkb_runvars` forms. `pkb_run_profile` is internal derived output and must never be supplied as input; compact or expanded external `pkb_run_profile` values are configuration errors.

A partial controlled input inherits only missing project execution-context RunVars: `pkb_glue`, `pkb_features`, `pkb_datapath`, `pkb_callpath`, `pkb_componentpath`, and `pkb_configpath`. An explicit blank value suppresses inheritance and remains a blank tombstone in the canonical `pkb_run_profile`, preserving the existing subsystem fallback semantics when replayed. Do not reconstruct optional RunVars by manually combining runner defaults, property files, named profiles, system properties, and Cucumber aliases when a retained final run profile is available.

Diagnostic lineage is metadata, not part of the execution RunVars. Supply `pkb_investigation_id`, `pkb_run_purpose`, `pkb_parent_run_id`, `pkb_baseline_run_id`, `pkb_changed_variables`, `pkb_run_id`, `pkb_agent_id`, `pkb_run_group`, `pkb_run_sequence`, `pkb_run_who`, and `pkb_run_why` separately from `pkb_runvars`; profiles must not contain those keys. After the rerun, verify `runProfileFingerprint` / compatibility field `directRunProfile` and the declared changed variables before attributing observed differences to the intended configuration change. Use `runProfileFingerprint`, not `configurationHash`, as the equality check for the final RunVar set. See `docs/ai-run-configuration.md`.

## Sources of Truth

Use all relevant evidence rather than trusting one file in isolation:

- Current implementation under `src/main/java` and `src/main/aspectj`
- Dependency-neutral controller/worker wire contracts under `pickleball-control-protocol`; this module must remain JDK-only
- Dynamic-control companion source module under `pickleball-control-api`; its classes are bundled into the main `tools.dscode:pickleball` artifact and are not a separate consumer dependency
- Controller-only Workbench source under `pickleball-workbench`; it may depend on `pickleball-control-protocol` but never on Pickleball core or `pickleball-control-api`
- Consumer-hosted internal Java checks under `maven-consumer-project/src/test/java`
- Executable consumer examples under `maven-consumer-project/src/test`
- `README.md` and the guides under `docs`
- Build and dependency configuration in `build.gradle`, `settings.gradle`, `pickleball-control-protocol/build.gradle`, `pickleball-control-api/build.gradle`, `pickleball-workbench/build.gradle`, and `maven-consumer-project/pom.xml`
- `docs/agent/feature-map.md` for navigation, not as a replacement for source inspection

When implementation, tests, examples, and documentation disagree, the implementation and its tests win for current behavior. Update the others to match unless the request is explicitly a documentation-only change.

## Dalton (3.0.0)

Dalton is opt-in. Without `pkb_dalton` (or `PKB_DALTON`) or a project-root `dalton.properties` with `enabled=true`, the project stays on the file-only path. Design: `docs/maintainer/3.0.0-dalton-design.md`. Round 1 roadmap (complete): `docs/roadmaps/3.0.0-dalton-roadmap.md`. Round 2 roadmap: `docs/roadmaps/3.0.0-dalton-round2-roadmap.md`.

Git files are the source of truth. The working-copy database lives at `.pickleball/dalton/dalton.db` (git-ignored). CLI commands include `dalton status`, `dalton hydrate`, `dalton extract`, `dalton history`, `dalton entity`, and (Round 2) `dalton feature put`, list/show/query commands, and `dalton run`. Auto-hydration occurs only when Dalton is explicitly enabled. `sqlite-jdbc` is not a published dependency and must not be shaded into the consumer JAR; enabling Dalton without it on the classpath fails loudly. Secrets are stored as references only, not cleartext.

All Dalton work stays on branch `3.0.0`. Never commit, push, or merge to `master`, `2.2.0`, or `2.1.19`.

## Definition of Done

A change is done when:

- Implementation, tests, consumer examples, and documentation are consistent.
- Existing behavior is preserved unless the request explicitly changes it.
- New or changed public behavior is covered by tests or an explicit consumer scenario.
- Documentation that describes the behavior is updated in the same change.
- The applicable validation has been run and the results are reported.

For agent-driven changes, prefer small, reviewable commits on the correct branch.
