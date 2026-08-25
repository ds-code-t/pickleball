# Pickleball Code Review Rules

Review changes against the following repository requirements.
## Functionality and compatibility

- Flag behavior changes that are incomplete across implementation, callers, and public contracts.
- Flag accidental breaking changes to Java APIs, Cucumber phrases, DSL/reference syntax, configuration, Selenium behavior, service-call behavior, or Maven consumer compatibility.
- Flag unrelated refactoring bundled into a focused functionality change.
- Verify Java 21 compatibility.
## Tests and executable examples

- Flag consumer-visible behavior changes without focused framework tests.
- Flag consumer-visible behavior changes without a Maven consumer scenario when one is practical.
- Check supporting service definitions, test data, configuration, local endpoints, and pages.
- Flag weakened or removed assertions that merely hide failures.
- For Workbench/protocol changes, require focused `@control-bridge` and/or `@step-override-bridge` coverage with `pkb_parallel=80` where practical; flag `@all` as the migration-validation tag.

## Workbench controller isolation

- Reject any Workbench compile/runtime dependency on root Pickleball, `tools.dscode:pickleball`, a published-equivalent variant, behavioral `pickleball-control-api`, Cucumber, Selenium, or REST-assured.
- Require shared Java types to stay in the JDK-only `pickleball-control-protocol`; worker bridge behavior and runtime translation stay in core.
- Require the Workbench artifact/process to be core-free and the separate worker to load Pickleball only from the consumer's captured test-runtime classpath.
- Require dependency provenance, nested JAR/service scans, distinct PID, runtime code-source/version checks, worker exclusion of the controller artifact, and clear incompatibility failure.
- Require the outer Pickleball JAR to contain exactly one byte-identical opaque Workbench payload without flattened Workbench/MCP classes. Pickleball may contain Workbench; Workbench must not contain Pickleball.

## Documentation and maintained context

- Flag changes to behavior, syntax, inputs, outputs, defaults, constraints, errors, edge cases, or compatibility that do not update the canonical documentation.
- Flag competing documentation when an existing guide should be updated.
- Check `docs/agent/feature-map.md` when ownership, canonical examples, syntax, or contracts change.
- Check `docs/agent/repository-index.md` when indexed files are added, moved, or removed.
- Flag disposable agent-created files outside `.agent-work/`.
- Flag any `.agent-work/` content that has been force-added to Git.
## Validation claims

- Do not accept claims that tests passed unless the commands were executed.
- For consumer-visible changes, expect framework tests, local Maven publication, and the Maven consumer suite using `CHROME_HEADLESS`.
- Clearly identify any validation that could not run.
