---
name: pickleball-functionality-change
description: Use whenever adding, changing, fixing, refactoring, or removing Pickleball framework behavior, DSL syntax, mappings, templates, Selenium behavior, service calls, configuration, Cucumber integration, consumer compatibility, or public APIs.
---

# Pickleball Functionality Change

Apply this workflow automatically when a user requests a functionality change.

## 1. Load project context

Read:

- `/AGENTS.md`
- `/docs/agent/feature-map.md`
- The relevant existing guide
- Relevant framework tests and Maven consumer scenarios

Do not ask the user to repeat repository setup, dependency model, test-server architecture, Java version, documentation policy, or definition of done.

## 2. Establish the current contract

Locate and compare:

- Implementation and callers
- Focused tests
- Gherkin phrases and scenarios
- Consumer configuration, call definitions, local endpoints, and pages
- Documentation and examples

If they disagree, resolve the inconsistency using established consumer behavior and the instructions in `AGENTS.md`.

## 3. Determine impact

Identify every affected surface:

- Framework source or AspectJ integration
- Unit/component tests
- Maven consumer features and support resources
- README or canonical guide
- Feature map and repository index
- Build or dependency configuration

Prefer backward-compatible behavior unless the user explicitly requests otherwise.

## 4. Implement coherently

Make the smallest complete change. Do not perform unrelated refactoring.

Add focused tests and an executable Maven consumer scenario for consumer-visible behavior whenever practical.

Update the canonical documentation in the same task. Documentation maintenance is part of the functionality change, not a separate optional step.

When authoring reusable `RUN` examples, prefer one bare table-driven `RUN` with a `RunType` column and one row per invocation. Related rows may mix regular scenarios, component scenarios, and service calls. Use inline `RUN ...` type, quoted `RunKey`, and inline selector syntax as shorthand only when those values are common to the rows or eliminate the table. Preserve executable coverage for both the canonical table form and supported shorthand variations.

## 5. Maintain agent context

Update `docs/agent/feature-map.md` when capability ownership, canonical examples, public syntax, or contracts change.

Run:

```shell
python scripts/refresh_agent_index.py
```

when indexed files are added, moved, or removed.

## 6. Validate

Run the narrowest relevant test first, followed by:

```shell
python scripts/verify_agent_contract.py
python scripts/refresh_agent_index.py --check
./gradlew test
```

For consumer-visible changes also run:

```shell
./gradlew publishToMavenLocal
./maven-consumer-project/mvnw -f maven-consumer-project/pom.xml -U test -Dpkb_browser=CHROME_HEADLESS
```

## 7. Report

Summarize:

- Behavior implemented
- Public compatibility implications
- Tests and consumer scenarios added or changed
- Documentation updated
- Commands executed and results
- Anything not validated and why
