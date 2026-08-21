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
- [ ] Put disposable scripts and intermediate artifacts under `.agent-work/`.
- [ ] Keep reusable maintained tooling under `scripts/`.

## Verify behavior

- [ ] Add or update focused framework tests.
- [ ] Add or update Maven consumer scenarios for consumer-visible behavior.
- [ ] Update service-call definitions, configuration, data, local endpoints, or pages when needed.
- [ ] Cover meaningful edge and compatibility cases.
- [ ] When an agent launches Pickleball tests with known execution settings, use `pkb_runvars` as the authoritative input unless the test intentionally exercises normal JVM/profile precedence.
- [ ] Never supply `pkb_run_profile` as test input; it is derived output.
- [ ] For Workbench/protocol/worker changes, preserve the JDK-only shared protocol, core-free controller artifact/process, separate consumer worker, consumer-authoritative classpath, and opaque nested payload.
- [ ] Never restore a root/`tools.dscode:pickleball`/behavioral-control dependency to Workbench to fix compilation.

## Maintain knowledge

- [ ] Update the canonical README or guide for changed behavior.
- [ ] Update `docs/agent/feature-map.md` if ownership, paths, syntax, examples, or contracts changed.
- [ ] Run `python scripts/refresh_agent_index.py` when indexed files changed.
- [ ] Delete disposable `.agent-work/` files before reporting completion.

## Validate

- [ ] Run `python scripts/verify_agent_contract.py`.
- [ ] Run `python scripts/refresh_agent_index.py --check`.
- [ ] Run `python scripts/sync_consumer_guidance.py --check`.
- [ ] Run `./gradlew test`.
- [ ] For consumer-visible changes, run `./gradlew publishToMavenLocal`.
- [ ] For broad consumer-visible changes outside Workbench/controller isolation, run `./maven-consumer-project/mvnw -f maven-consumer-project/pom.xml -U test -Dpkb_runvars.pkb_browser=CHROME_HEADLESS -Dpkb_runvars.pkb_tags=@all`.
- [ ] For Workbench/controller isolation changes, never run `@all`; run only affected `@control-bridge` and/or `@step-override-bridge` scenarios with `-Dpkb_runvars.pkb_parallel=80` where practical.
- [ ] For Workbench boundary changes, run `./gradlew verifyStrictControllerIsolation :pickleball-workbench:test`.
- [ ] Prefer the equivalent focused turnkey command `scripts/agent_validate.sh --workbench` (PowerShell: `.\scripts\agent_validate.ps1 -Workbench`) when the environment supports the complete flow.
- [ ] Report anything not run and the reason.

## Report

- [ ] Summarize behavior changed.
- [ ] List documentation and executable examples updated.
- [ ] Describe compatibility implications.
- [ ] Report exact validation commands and results.
- [ ] Confirm disposable agent-created files were removed.
