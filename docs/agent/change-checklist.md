# Functionality Change Checklist

Use this checklist for changes to Pickleball behavior. Coding agents should complete it automatically rather than asking the user to repeat it.

## Understand

- [ ] Read `AGENTS.md`.
- [ ] Read the relevant row in `docs/agent/feature-map.md`.
- [ ] Identify the existing behavior from source, tests, consumer scenarios, and documentation.
- [ ] Identify public contracts and backward-compatibility risks.
- [ ] Resolve discrepancies among source, tests, examples, and documentation.

## Implement

- [ ] Make the smallest coherent implementation change.
- [ ] Preserve Java 21 compatibility.
- [ ] Preserve established public behavior unless a breaking change was requested.
- [ ] Avoid unrelated refactoring.

## Verify behavior

- [ ] Add or update focused framework tests.
- [ ] Add or update Maven consumer scenarios for consumer-visible behavior.
- [ ] Update service-call definitions, configuration, data, local endpoints, or pages when needed.
- [ ] Cover meaningful edge and compatibility cases.

## Maintain knowledge

- [ ] Update the canonical README or guide for changed behavior.
- [ ] Update `docs/agent/feature-map.md` if ownership, paths, syntax, examples, or contracts changed.
- [ ] Run `python scripts/refresh_agent_index.py` when indexed files changed.

## Validate

- [ ] Run `python scripts/verify_agent_contract.py`.
- [ ] Run `python scripts/refresh_agent_index.py --check`.
- [ ] Run `./gradlew test`.
- [ ] For consumer-visible changes, run `./gradlew publishToMavenLocal`.
- [ ] For consumer-visible changes, run `./maven-consumer-project/mvnw -f maven-consumer-project/pom.xml -U test -Dpkb_browser=CHROME_HEADLESS`.
- [ ] Report anything not run and the reason.

## Report

- [ ] Summarize behavior changed.
- [ ] List documentation and executable examples updated.
- [ ] Describe compatibility implications.
- [ ] Report exact validation commands and results.
