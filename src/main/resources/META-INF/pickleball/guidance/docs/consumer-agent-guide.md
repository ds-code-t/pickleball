# Pickleball Consumer Agent Guide

This is the canonical AI-agent contract for projects that consume Pickleball as a Maven dependency.

A consumer project may contain only a short `AGENTS.md` bridge. That bridge uses Pickleball Workbench (`tools.dscode.launcher.PickleballWorkbenchLauncher`) `export-guidance` to materialize the version-matched guidance embedded in the installed Pickleball dependency. When this file is materialized as `.pickleball/AGENT-GUIDE.md`, supporting documentation is under `.pickleball/docs/` and a curated reference snapshot of Pickleball's executable Maven consumer is under `.pickleball/maven-consumer-project/`. Full `docs/` and the snapshot stay exported for on-demand lookup. Do not dump them into first-read context.

## Tool chooser

Pickleball Workbench is the one front door. It is a Java/Maven program, not an IDE feature and not a GUI requirement. Do not start the GUI. Do not register IDE MCP.

If `workbench_*` tools are already present in this session, use them as an alias for the same Isolate loop. Otherwise keep using Workbench CLI verbs. Missing `workbench_*` tools is not a reason to skip Discover.

From the consumer project, with Pickleball on the test classpath (`classpathScope=test`):

1. **Discover** — `PickleballWorkbenchLauncher discover` (optional `--tags` / `--name`). Workbench applies complete AI `pkb_runvars`: browser ladder, high/auto parallel, diagnostic, warn, failed retention. It wraps consumer `mvn test`. Do not start a live worker to run the whole suite. Then read `run-catalog.json` and the retained `pkb_run_profile`.
2. **Isolate** — `PickleballWorkbenchLauncher isolate` with `--tags` / `--name` for a known scenario. Replays the last Discover `pkb_run_profile` as `pkb_runvars` (never supply `pkb_run_profile` as input). One paused scenario; do not parallelize isolate. If isolate cannot obtain a live worker, tell the human that Workbench CLI isolate failed.
3. **Confirm** — `PickleballWorkbenchLauncher confirm` with the same Discover snapshot and narrow tags/name.
4. **Emit the human handoff, then edit real consumer source** — write `.pickleball/investigations/<id>/` then in chat print only `.pickleball/investigations/<id>/report.html`.

`hint` (alias `discover-hint`) prints the recommended Discover `pkb_runvars` and `NEXT: run discover`. `mcp` and `ui` are host/human commands. Agents must not use `ui`. Hosts may already wire `mcp .`; that is optional host wiring, mentioned once, not an agent setup step.

Do not copy consumer features into `.pickleball` as a sandbox.

### Live isolation loop

This loop isolates a **known** failing scenario after Discover. Prefer Workbench CLI `isolate`. If `workbench_*` tools are already in this session, they are the same loop (`workbench_sync` / `workbench_worker_start` must use the Discover snapshot replayed as `pkb_runvars`).

1. Workbench `isolate` (CLI) or, when already connected, `workbench_sync` then `workbench_worker_start`.
2. `workbench_request_control` when using the MCP alias.
3. Isolate with `workbench_execute_step` and/or `workbench_player_replace_document`.
4. Inspect with `workbench_browser_page`, `workbench_element_inspect`, and paged `workbench_events`. Prefer those over `workbench_browser_screenshot`.
5. Confirm with Workbench `confirm`. Read the pack with `workbench_diagnostic_catalog`, `workbench_diagnostic_run`, and `workbench_diagnostic_summary` when those tools already exist.
6. Emit the human handoff with `workbench_investigation_emit` or `DiagnosticCli emit-investigation`. In chat print only `.pickleball/investigations/<id>/report.html`.

`workbench_execute_step` returns a structured `SUCCESS` / `FAILED` / `UNAVAILABLE` result. A FAILED Gherkin hypothesis does not end the worker, does not fail the paused scenario, and is not an MCP `isError`. Page `workbench_events` with `afterSequence` and a small `limit` (default 100, max 500). Live buffer edits do not require `workbench_sync` and do not write the original `.feature` until explicit Save (`workbench_request_save`). Worker restart without rebuild already exists (`workbench_worker_restart`). Step Overrides compile worker-side (`workbench_step_override_compile`).

### Generated trees are not the project

- `.pickleball/maven-consumer-project/` is a version-matched **read-only** reference snapshot of Pickleball's own example consumer. Do not copy, edit, or execute it as the project under test.
- `.pickleball/workbench/live/classes` is the compiled overlay for the worker classpath. Do not use it as an editor.
- `.pickleball/investigations/` is unmanaged consumer-agent output. `export-guidance` leaves it alone.
- `export-guidance` does **not** copy this consumer's own features into `.pickleball` for testing. It still materializes full `docs/` plus the example-consumer snapshot for on-demand/human use.

## First-read

Keep first-read small. After a successful export:

1. Follow the consumer project's own instructions first; they remain authoritative for project-specific behavior.
2. Stay in this guide's tool chooser: Workbench `discover` when the failing scenario is unknown. Isolate a known failure with Workbench `isolate`, or with already-connected `workbench_*` tools as the same loop. Do not start the GUI.
3. Inspect the **real** consumer `pom.xml`, Pickleball runner subclass, features, configuration, data, mappings, and test support before changing them.
4. Open a specific exported guide only when that topic is needed, for example `docs/dynamic-steps.md`, `docs/diagnostic-reporting.md`, `docs/configuration.md`, or `docs/ai-run-configuration.md`.
5. Do not assume the Pickleball core source repository is present. A normal consumer may only have the Maven dependency.

Do not read `docs/README.md`, the whole `maven-consumer-project/` snapshot, or Workbench GUI pages as first actions. Those remain available on demand.

The exported documentation and Maven consumer reference are version-matched to the Pickleball artifact on the consumer's test classpath. Prefer them over instructions or examples copied from another release.

## Generated guidance lifecycle

Treat `.pickleball` as generated dependency guidance, not as a durable source of truth by itself. Do not skip `export-guidance` merely because the directory already exists. The consumer bridge intentionally reruns the exporter before Pickleball work so the Maven dependency currently resolved on the test classpath remains authoritative.

A successful `export-guidance .pickleball` run:

- overwrites the current version's managed guidance files, documentation, and Maven consumer reference snapshot;
- writes `.pickleball/GUIDANCE-MANIFEST.json` last, recording the exporting Pickleball version and managed files;
- removes files managed by the previous manifest that are no longer shipped, while leaving unrelated files alone, including `.pickleball/investigations/`; and
- best-effort ensures `.pickleball` is ignored by Git, preferring an existing `.gitignore` and then repository-local `.git/info/exclude`.

The exporter does not create/commit a new `.gitignore`, alter the Git index, or untrack files that were already committed. If export fails, treat any existing `.pickleball` contents as potentially stale. The manifest records the last completed export; it is not a substitute for rerunning the exporter.

Compatibility note: an older Pickleball release whose exporter predates the manifest lifecycle may leave newer files behind after a downgrade. Those leftovers are not authoritative for the downgraded dependency. Prefer the dependency actually resolved on the test classpath and files freshly exported by that dependency.

## Generated Maven consumer reference

`.pickleball/maven-consumer-project/` is a generated, read-only reference snapshot of the canonical Maven consumer used by Pickleball itself. It preserves repository-relative paths so links from the exported Markdown documentation continue to resolve locally. It is not the consumer project under test and is not a writable sandbox.

The snapshot intentionally includes the consumer `pom.xml`, Pickleball runner, local browser/service test server, executable feature files, service-call definitions, configuration/data fixtures, local test-site resources, and the committed shared/local profile and property examples. It intentionally excludes Maven wrappers, Git/IDE/generated artifacts, the consumer `AGENTS.md` bridge, internal Java verification classes, and maintainer-only `_local2` files.

Use the snapshot only to answer questions such as how a working feature, profile, property file, service call, configuration resource, browser fixture, or runner is structured. Do not copy, modify, or execute files under `.pickleball/maven-consumer-project/` as the project under test. Make requested changes in the consumer project's own source tree. A later `export-guidance` run may overwrite or remove every managed reference file. `export-guidance` does not copy the consumer's own features into `.pickleball` for testing.

## Scenario authoring and fixes

When changing a consumer scenario:

- Prefer existing documented Pickleball syntax and executable examples.
- Use the version-matched `maven-consumer-project/` reference when it provides a working example of the same syntax or configuration.
- Do not invent a new Gherkin phrase when a Pickleball step or supported dynamic-step form already expresses the behavior.
- Preserve standard Cucumber behavior and project-specific custom glue.
- Inspect related mappings, component scenarios, service-call definitions, configuration, and test-site support before assuming a failing line is self-contained.
- Make the smallest change supported by evidence.
- Rerun the narrowest useful scenario/tag selection first, then broaden validation when needed.

### Preferred reusable RUN authoring

When a scenario invokes reusable regular scenarios, component scenarios, or service calls, prefer one table-driven `RUN` step with one row per invocation. Rows may mix the runnable kinds in the same step:

```gherkin
When RUN
  | RunType            | RunKey | Run Tags         |
  | SCENARIO           | setup  | %setup           |
  | COMPONENT SCENARIO | login  | %login-component |
  | SERVICE CALL       | health | %health-full-url |
```

Treat `RunType` as the complete kind plus multiplicity. Valid values are `SCENARIO`, `SCENARIOS`, `COMPONENT SCENARIO`, `COMPONENT SCENARIOS`, `SERVICE CALL`, and `SERVICE CALLS`. Singular/plural validation is per row, not a property of the whole table. A nonblank table `RunType` overrides any inline type for that row.

Use parameterized step text as shorthand when it eliminates the table or moves a value common to every row out of the table. For example:

```gherkin
When RUN SCENARIO
  | Run Tags |
  | %tagA    |
```

and:

```gherkin
When RUN SCENARIO: %tagA
```

The same rule applies to a quoted inline `RunKey`: table `RunKey` wins when nonblank; otherwise the inline key is the shared fallback. Do not expand a concise table into several adjacent `RUN` steps merely because the rows use different `RunType` values.

A keyed deferred `RUN` stores its result only after the selected scenario subtree completes. Save explicit `RETURN` when present; otherwise save the completed default scenario root. Normal RunMap/NodeMap collection semantics apply, so repeating an ordinary top-level `RunKey` appends results and an unindexed read resolves the latest item. Do not assume a keyed `RUN` exposes a live pre-execution scenario-root reference.

Use supporting guides as appropriate, especially `docs/dynamic-steps.md`, `docs/component-scenarios.md`, `docs/service-call-scenarios.md`, `docs/mapping-and-templating.md`, `docs/data-values-and-elements.md`, `docs/configuration.md`, and `docs/cucumber-compatibility.md`.

## Configuration and controlled RunVars

Treat these as distinct concepts:

- `default_profile` — internal reference snapshot of normal resolved project RunVars;
- `pkb_runvars` — preferred controlled-run **input**;
- `pkb_run_profile` — canonical resolved RunVar **output** retained for diagnostics/replay.

Never supply `pkb_run_profile` or `pkb_run_profile.<pkb_var>` as input. They are reserved internal derived-output names; Pickleball rejects external use. Use `pkb_runvars` or `pkb_runvars.<pkb_var>` for controlled execution.

### Default AI test-launch rule

When you launch Pickleball tests and the intended execution settings are known, use `pkb_runvars` as the authoritative input. Put intentional tag/name selection, browser, evidence/logging controls, and other non-secret RunVar changes inside `pkb_runvars`; do not default to ambient optional project settings or separate JVM `-Dpkb_*` RunVars. Use `pkb_profile` or ordinary JVM RunVar overrides only when the task specifically tests those configuration semantics or the user asks for them. Keep protected secrets and diagnostic lineage outside `pkb_runvars`.

For an agent's bounded confirmation (not the human runner defaults), include diagnostic evidence controls, the browser ladder (keep a remote `pkb_browser`; otherwise prefer `CHROME_HEADLESS`), and high parallelism when more than one scenario will run. Documented AI Discover/Confirm `pkb_runvars` keys:

```text
pkb_browser=<browser ladder>
pkb_parallel=<conservative JVM estimate or auto>
pkb_reportingmode=diagnostic
pkb_loglevel=warn
pkb_reportretention=failed
```

Use the narrowest `pkb_tags` / `pkb_name` that isolate the failure. Do not add the `pretty` plugin; it is console noise for agents. `pkb_reportretention=failed` keeps dense evidence for failing scenarios and does not retain it for passing ones. Workbench `hint` prints the estimated integer `pkb_parallel` and the selected browser for the current project/JVM. `pkb_parallel=auto` also resolves to that estimate at run start and stamps the integer into `pkb_run_profile`.

These are documented agent defaults, not `PickleballTests` human defaults (`pretty`, `@all`, often headed Chrome). Example confirmation after isolation:

```text
PickleballWorkbenchLauncher confirm --tags=@the-failing-tag --name=The failing scenario
```

After any diagnostic run, read `pkb_run_profile` from the pack. That is the complete resolved RunVar list, including inherited execution-context paths and the integer parallel count. Do not treat omitted `pkb_runvars` keys as equal to project `pickleball.properties`. Isolate and confirm replay that retained profile through `pkb_runvars`; they do not silently re-resolve from project defaults.

A selected profile or partial `pkb_runvars` input inherits only missing project execution-context RunVars:

```text
pkb_glue
pkb_features
pkb_datapath
pkb_callpath
pkb_componentpath
pkb_configpath
```

Optional RunVars such as browser, tags, reporting, logging, and ReportPortal values do not leak into a controlled run merely because normal configuration contains them.

Explicit JVM `-Dpkb_*` RunVars are different from ambient project defaults: they are intentional runtime overrides and remain active with a selected profile or controlled run. With a selected profile they override the same profile key; with `pkb_runvars`, the controlled value wins any conflict.

For those six inherited keys, missing means inherit, nonblank means override, and blank/null means suppress inheritance. A blank remains a blank tombstone in the retained final `runProfile`, because replaying that blank is what tells the underlying Pickleball subsystem to use its historical fallback behavior instead of re-inheriting a project value.

`pkb_configpath` selects the source loaded beneath the configuration mapping. Prefer `<config:...>` references such as `<config:URL.forms>`; legacy `<configs...>` references remain supported. Missing/blank `pkb_configpath` uses the historical `configs` Java fallback. Runtime config data cannot resolve profiles, `pkb_runvars`, or the path that loads the configs themselves.

Do not normalize other resource-path RunVar conventions. Follow the version-matched `docs/configuration.md` and `docs/config-files-and-resource-mapping.md` for each path.

## Diagnostic investigation protocol

When Pickleball diagnostic evidence exists, use the shallowest evidence layer that completely answers the question. Do not advance to denser evidence merely because it exists.

For AI-controlled diagnostic runs, keep terminal logging minimal. Diagnostic mode captures TRACE-through-ERROR evidence independently of console `pkb_loglevel`, so prefer `pkb_loglevel=warn` or `error` when appropriate. Use terminal output primarily for Maven, compilation, JVM, dependency, command-line, or other startup failures; use structured diagnostic artifacts after the run begins successfully.

Use this escalation order:

1. `run-catalog.json` to choose relevant runs. Each catalog entry includes the retained `pkb_run_profile` when present.
2. Selected `run-index.json` and `clusters.json` for outcomes, scenario identity, failure grouping, capabilities, retention, step rollups, the complete `runProfile`, profile fingerprints, and representative visual references.
3. Selected scenario `summary.json` when additional sparse detail is needed, including the same `runProfile`.
4. Relevant `events.jsonl` only when exact step/lifecycle/order/INFO+ detail remains unanswered.
5. Existing `comparisonToPrevious` or Pickleball run/fingerprint comparison before opening screenshots.
6. A representative PNG only when semantic visual meaning must be understood.
7. `trace.jsonl.gz` or interrupted `trace.jsonl` only when structured/INFO+ evidence is insufficient.

Stop reading as soon as the current layer answers the investigation. Do not recursively ingest an entire diagnostic run.

From headless Workbench MCP, use `workbench_diagnostic_catalog`, `workbench_diagnostic_run`, and `workbench_diagnostic_summary` for layers 1–3 instead of globbing `reports/diagnostic-runs`. Those tools return sparse JSON only and do not dump `events.jsonl`, traces, or screenshot bytes.

After isolation and the diagnostic rerun, emit a small human handoff. JSON is the source of truth; HTML is a local render of that JSON plus at most two screenshots linked from the existing diagnostic pack. Do not copy the diagnostic run into `.pickleball/investigations/`. In chat print only the project-relative `report.html` path.

## Visual evidence rules

- Never open a PNG merely to determine whether two screenshots differ.
- Prefer already-recorded `comparisonToPrevious` for adjacent screenshots.
- For cross-run or explicit comparison, use `DiagnosticCli`, `DiagnosticRunComparator`, or `VisualFingerprintComparator`.
- Do not manually decode `.pkbf` files or invent another image-comparison algorithm.
- `decodedPixelsExactlyEqual=true` establishes rendered-pixel equality.
- `IDENTICAL` ends a visual-difference investigation unless the image itself is required.
- Other similarity categories establish that pixels differ and their magnitude; open a representative PNG only when interpreting what visibly changed matters.
- Raw PNG byte inequality does not prove rendered pixels differ.

## Diagnostic utility commands

The agent-facing name is Workbench. From a Maven consumer where Pickleball is on the test classpath:

```text
PickleballWorkbenchLauncher export-guidance .pickleball
PickleballWorkbenchLauncher hint
PickleballWorkbenchLauncher discover [--tags <expr>] [--name <expr>]
PickleballWorkbenchLauncher isolate [--tags <expr>] [--name <expr>]
PickleballWorkbenchLauncher confirm [--tags <expr>] [--name <expr>]
```

`DiagnosticCli` remains the implementation behind export-guidance/hint and the comparison/rebuild utilities:

```text
DiagnosticCli guidance
DiagnosticCli export-guidance [output-directory]
DiagnosticCli discover-hint [project]
DiagnosticCli emit-investigation <investigation-json-or--> <consumer-project-root>
DiagnosticCli compare-runs <left-run-index> <right-run-index> [output-json]
DiagnosticCli compare-fingerprints <left.pkbf> <right.pkbf> [output-json]
DiagnosticCli rebuild <diagnostic-runs-root-or-run-root>
```

`DiagnosticCli help`, `--help`, and `-h` print that DiagnosticCli list and state that Workbench is the agent entry.

Use Workbench `export-guidance` to materialize the complete version-matched documentation plus curated Maven consumer reference, `hint` for the complete Discover `pkb_runvars` (browser ladder, estimated `pkb_parallel`, diagnostic evidence controls), and `discover` / `isolate` / `confirm` for the turnkey loop. `emit-investigation` writes `.pickleball/investigations/<id>/investigation.json` and `report.html` and prints the relative HTML path.

## Controlled diagnostic reruns

When an investigation requires a rerun and the intended execution settings are known:

1. Start from the selected run's retained `runProfile` in `run-index.json`.
2. Replay that retained final RunVar set through compact `pkb_runvars` or expanded `pkb_runvars.<pkb_var>` members.
3. Never mix compact and expanded `pkb_runvars` forms. Never supply `pkb_run_profile` as input.
4. Preserve explicit blank assignments from the retained profile.
5. Change only RunVars required by the current hypothesis.
6. Do not reconstruct optional effective RunVars by manually combining defaults, property files, profiles, system properties, and Cucumber aliases when a retained profile is available.
7. Supply diagnostic lineage separately through `pkb_investigation_id`, `pkb_run_purpose`, `pkb_parent_run_id`, `pkb_baseline_run_id`, and `pkb_changed_variables`.
8. Treat lineage as descriptive investigation context, not proof of an execution/source difference.
9. Use `pkb_changed_variables` only for canonical execution RunVar names intentionally changed, such as `pkb_browser` or `pkb_tags`. Do not put source paths, feature files, commits, test-data changes, reasons, profile controls, or derived fields in `pkb_changed_variables`.
10. If source changes but final execution RunVars should remain identical, omit `pkb_changed_variables` and describe the goal in `pkb_run_purpose`.
11. Evidence/logging controls such as `pkb_reportingmode`, `pkb_reportretention`, `pkb_diagnostic_output`, `pkb_platformlog`, `pkb_gitsnapshot`, and `pkb_loglevel` are RunVars; declare them when intentionally changed.
12. After the rerun, verify `runProfileFingerprint`, compatibility field `directRunProfile`, and actual source/comparison evidence before attributing differences.
13. Use `runProfileFingerprint`, not `configurationHash`, as the equality signal for the final RunVar set.
14. Never expand protected values into logs, prompts, committed files, or diagnostic evidence.

Example controlled replay:

```text
-Dpkb_runvars="<retained runProfile with only intended edits>"
-Dpkb_investigation_id=<investigation>
-Dpkb_parent_run_id=<parent>
-Dpkb_baseline_run_id=<baseline>
-Dpkb_run_purpose=<hypothesis>
-Dpkb_changed_variables=pkb_browser
```

See `docs/ai-run-configuration.md` for the full profile/RunVar contract and `docs/diagnostic-lineage-metadata.md` for lineage semantics.

## Pickleball syntax documentation

The exported `docs/` tree is the version-matched reference for all supported Pickleball behavior and syntax. Open a specific guide when the live loop or a diagnostic layer requires that topic; do not start by reading `docs/README.md` as a dump. Its links to the working consumer resolve into the exported `maven-consumer-project/` reference snapshot. In particular:

- dynamic Gherkin/action/assertion syntax — `docs/dynamic-steps.md`;
- element vocabulary/selectors — `docs/custom-element-definitions.md`;
- mappings/templates — `docs/mapping-and-templating.md`;
- Data Elements and values — `docs/data-values-and-elements.md` and `docs/data-element-query-runtime.md`;
- reusable component scenarios and canonical `RUN` authoring — `docs/component-scenarios.md`;
- service calls and `RUN`/`CALL:` result semantics — `docs/service-call-scenarios.md`;
- nested flow and conditionals — `docs/nested-steps.md`, `docs/block-conditionals.md`;
- keyboard expressions — `docs/key-parser-dsl.md`;
- execution/configuration/profiles — `docs/configuration.md`, `docs/ai-run-configuration.md`;
- resource/config mapping — `docs/config-files-and-resource-mapping.md`;
- Cucumber compatibility — `docs/cucumber-compatibility.md`;
- Workbench MCP tools, skip / resources-only sync, and the live worker — `docs/pickleball-workbench.md`;
- diagnostics and lineage — `docs/diagnostic-reporting.md`, `docs/diagnostic-lineage-metadata.md`.

Do not guess Pickleball syntax when the version-matched guide or executable consumer reference can answer it.

## Human-readable consumer guidance

Use `docs/consumer-project.md` on demand for the Maven consumer layout, local test site, common tag entry points, diagnostic usage, and example commands. Human readers can start with `docs/README.md` and open files under `maven-consumer-project/` in the IDE to inspect the version-matched working features, configuration, calls, data, runner, and test-site examples. Agents should not treat those as first-read.

## When the core Pickleball repository is also present

If the consumer is nested inside the Pickleball source repository, repository-level `AGENTS.md` may impose additional maintainer rules for framework changes. Those core-maintainer rules are additive and do not replace this consumer-facing contract.

For a normal external consumer, do not assume those core files exist.

## Maintainer-only: pointer-eval harness

This section is for Pickleball maintainers. It is not the product suite and is not first-read for consumer agents.

Pickleball's example Maven consumer includes an opt-in mixed pass/fail suite tagged only `@agent-pointer-eval` (`maven-consumer-project/src/test/resources/features/agent-pointer-eval.feature`). It is not part of `@all`, `@regression`, or the other Maven suite-profile tags. The failures are intentional canned fixtures for scoring whether a consumer AI agent follows the short `AGENTS.md` pointer into this guide and then uses Workbench discover-then-isolate. Do not treat those failures as product bugs, and do not "fix" the feature unless a human asked to change the harness. During an eval, still follow Discover / Isolate / Confirm; do not ignore canned fails.

Run it explicitly:

```text
PickleballWorkbenchLauncher discover --tags=@agent-pointer-eval
```

or `-Dpkb_runvars="pkb_tags=@agent-pointer-eval, pkb_browser=CHROME_HEADLESS"`. Do not add this tag to consumer `AGENTS.md` or Copilot pointer files.
