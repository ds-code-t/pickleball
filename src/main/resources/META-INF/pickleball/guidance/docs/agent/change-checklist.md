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

Plain forms `@all` must run. Group them into the feature named in the last column. Do not add a feature file per form.

| form | plain example | truthy | falsy | hard-error vs wrong-value | @all feature/scenario |
|---|---|---|---|---|---|
| single-element IF | `IF: "abc" THEN:` | non-blank text, non-zero number, `true` | blank, `0`, `false`, missing reference | falsy skips; does not throw | `single-element-condition.feature` / Inline IF/THEN runs a truthy single element and skips a falsy one |
| comma single-element IF | `, if "abc", save "yes" as "q"` | quoted text runs the save | `false`, `0`, and a missing reference take else; `""` leaves the prior value | does not throw | `comma-single-element-condition.feature` / A comma if runs a truthy single element and skips a falsy one |
| leading `!` | `IF: !false THEN:` | `!false` runs | `!true` and `!"abc"` skip | does not throw | `expression-plain-forms.feature` / A leading bang and comparisons select the branch |
| comparison | `IF: 1 != 0 THEN:` | `!=`, equal `<=`, `>=` | smaller `<=` skips | does not throw | `expression-plain-forms.feature` / A leading bang and comparisons select the branch |
| tilde expression | `IF: ~[~{ 1 == 1 }~]~ THEN:` | `1 == 1` runs | `true && <missingRef>` and `false && <missingRef>` skip | a missing reference does not throw | `expression-plain-forms.feature` / A tilde expression runs and a missing reference does not throw |
| trailing `?` | `IF: <{ true? }> THEN:` | `true?` runs | `false?` skips | trailing `?` is the boolean marker, not a ternary | `expression-plain-forms.feature` / A trailing question mark is the boolean marker |
| `file:` | `<file:files/customers #1.name>` | Ava, Phoenix, Premium, then Ben; `[0]` is also Ava | skipped `false && <file:files/no-such-file>` does not throw | a taken missing file is falsy and does not throw | `map-ref-plain-forms.feature` / A file reference reads customers.yaml ; A taken missing file does not throw |
| `<&key>` | `<&key>` after save `"ava"` as `"key"` | resolves to `ava` | | may warn; deprecation is not a hard error | `map-ref-plain-forms.feature` / A deprecated ampersand reference still resolves |
| `ASSERT:` single element | `ASSERT: "abc"` | `"abc"`, `7`, and `true` pass, then a later step runs | | a hard-failing `ASSERT:` stays out of `@all` | `assert-single-element.feature` / A single-element ASSERT passes and a later step runs |
| `SOFT ASSERT:` single element | `SOFT ASSERT: false` | | `false` and `""` do not throw | a later step still runs; not a hard fail | `assert-single-element.feature` / A single-element SOFT ASSERT does not throw and a later step runs |
| `ends with` | `IF: "Phoenix" ends with "nix" THEN:` | ends with `nix` runs | ends with `Ava` skips | does not throw | `phrase-plain-forms.feature` / Ends with and starts with select the branch |
| `starts with` | `IF: "Phoenix" starts with "Pho" THEN:` | starts with `Pho` runs | starts with `Tem` skips | does not throw | `phrase-plain-forms.feature` / Ends with and starts with select the branch |
| `matches` | `IF: "Ava" matches "A.+" THEN:` | whole-string Java regex `A.+` runs | `T.+` skips | `Pattern.compile` and `Matcher.matches`; double quotes are case-sensitive; a single quote on either side is case-insensitive | `phrase-plain-forms.feature` / A quoted value matches a whole-string Java regex |
| `is blank` | `IF: "" is blank THEN:` | `""` runs | `"abc"` skips | does not throw | `phrase-plain-forms.feature` / Is blank selects an empty string |
| `is selected` / `is unselected` | `IF: "Email" Radio Button is selected THEN:` | selected Email and unselected Phone run, then the reverse after Phone is chosen | the opposite radio state skips | forms playground radios; does not throw | `phrase-plain-forms.feature` / Forms playground element states select the branch |
| `is present` | `IF: "Submit Form" Button is present THEN:` | the visible Submit Form button runs | | does not throw | `phrase-plain-forms.feature` / Forms playground element states select the branch |
| `is required` / `is non-required` | `IF: "Required Marker" Textbox is required THEN:` | Required Marker runs; Last Name non-required runs | the inverses skip | Required Marker is the forms-playground fixture; does not throw | `phrase-plain-forms.feature` / Forms playground element states select the branch |
| backtick single element | `IF: \`abc\` THEN:` | backtick text runs | | does not throw | `phrase-plain-forms.feature` / A backtick runs and zero no null and the null marker skip |
| quoted zero | `IF: "0" THEN:` | | a zero-only string skips | falsy; does not throw | `phrase-plain-forms.feature` / A backtick runs and zero no null and the null marker skip |
| `"no"` | `IF: "no" THEN:` | | skips | does not throw | `phrase-plain-forms.feature` / A backtick runs and zero no null and the null marker skip |
| `"null"` | `IF: "null" THEN:` | | skips | does not throw | `phrase-plain-forms.feature` / A backtick runs and zero no null and the null marker skip |
| null marker | `IF: <^~NULL~^> THEN:` | | skips | does not throw | `phrase-plain-forms.feature` / A backtick runs and zero no null and the null marker skip |

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
