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

## Documentation and maintained context

- Flag changes to behavior, syntax, inputs, outputs, defaults, constraints, errors, edge cases, or compatibility that do not update the canonical documentation.
- Flag competing documentation when an existing guide should be updated.
- Check `docs/agent/feature-map.md` when ownership, canonical examples, syntax, or contracts change.
- Check `docs/agent/repository-index.md` when indexed files are added, moved, or removed.

## Validation claims

- Do not accept claims that tests passed unless the commands were executed.
- For consumer-visible changes, expect framework tests, local Maven publication, and the Maven consumer suite using `CHROME_HEADLESS`.
- Clearly identify any validation that could not run.
