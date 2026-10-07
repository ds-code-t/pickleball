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
| trailing `?` | `IF: <{ true? }> THEN:` | `true?`, `"yes"?`, and `1?` run | `false?` and `"0"?` skip | trailing `?` uses `isTruthy`; it is not a cast and not a ternary | `expression-plain-forms.feature` / A trailing question mark is the boolean marker |
| nested block IF | `IF: "abc":` then `: * IF: "abc":` | depth 1, 2, and 3 run when each parent is truthy | a falsy parent (`""`, `0`, `false`, missing) does not run the inner IF | does not throw | `single-element-condition.feature` / A depth-3 block runs the inner IF only when every parent is truthy |
| inline IF inside a block | `IF: "abc":` then `: * IF: "abc" THEN:` | truthy outer runs the inline IF | falsy outer skips it | does not throw | `single-element-condition.feature` / An inline IF THEN inside a block IF follows the outer branch |
| plain step before ELSE | `IF: false:` then a plain step then `ELSE:` | false IF still takes ELSE | true IF still skips ELSE | the plain step runs | `single-element-condition.feature` / A plain step between IF and ELSE does not hide a false IF |
| comma else-if | `, if "", save "no" as "q", else if "abc", save "yes" as "q", else, save "else" as "q"` | `"abc"` saves yes | `""` does not save no | does not throw | `comma-single-element-condition.feature` / A comma else if uses a plain element |
| comma block IF | `, if "abc":` then one nested save | the nested save runs | | does not throw | `comma-single-element-condition.feature` / A comma block if runs one nested save |
| plain RunIf | `RunIf` cell `"abc"`, `<{ 1 }>`, or `<A>` saved as hello | `"abc"`, `<{ 1 }>`, and hello run the row | `"0"`, `0`, `<{ 0 }>`, and a missing reference skip | a resolved whole-cell reference is saved text, not an element name | `syntax-since-2.1.14.feature` / A plain RunIf cell uses IF truthiness |
| `bool()` | `IF: <{ bool(true) }> THEN:` | `bool(true)` inline and block | `bool(false)` inline and block skip | does not throw | `expression-plain-forms.feature` / bool is truthy or falsy inline and in a block |
| `firstNotBlank` / `firstNotEmpty` / `firstNotNull` | `IF: <{ firstNotBlank("", "abc") }> THEN:` | first real string runs | blank, empty, or only `<...>` skips | nil is falsy and does not throw | `expression-plain-forms.feature` / firstNotBlank firstNotEmpty firstNotNull and getBool select the branch |
| `getBool` | `IF: <{ getBool("yes") }> THEN:` | `getBool("yes")` runs | `getBool(false)` skips | does not throw | `expression-plain-forms.feature` / firstNotBlank firstNotEmpty firstNotNull and getBool select the branch |
| block expression IF | `IF: <{ 1? && !false }>:` | the block runs | `<{ "0"? || false }>` skips the block | does not throw | `expression-plain-forms.feature` / A block expression condition uses isTruthy |
| word plus extra letters | `IF: "false123" THEN:` | `"false123"` and `"no1"` run | `" no "` skips | quotes and whitespace are stripped; the false-like word must match exactly | `expression-plain-forms.feature` / false123 and no1 stay truthy and a spaced no stays falsy |
| comma `until` | `, until "abc", save "yes" as "q"` | `"abc"` runs once, the same as `if` | `""` skips | not a loop redesign; a comma `until` follows `if` | `comma-single-element-condition.feature` / A comma until selects a branch the way if does |
| `file:` | `<file:files/customers #1.name>` | Ava, Phoenix, Premium, then Ben; `[0]` is also Ava | skipped `false && <file:files/no-such-file>` does not throw | a taken missing file is falsy and does not throw | `map-ref-plain-forms.feature` / A file reference reads customers.yaml ; A taken missing file does not throw |
| `<&key>` | `<&key>` after save `"ava"` as `"key"` | resolves to `ava` | | may warn; deprecation is not a hard error | `map-ref-plain-forms.feature` / A deprecated ampersand reference still resolves |
| `ASSERT:` single element | `ASSERT: "abc"` | `"abc"`, `7`, and `true` pass, then a later step runs | | a hard-failing `ASSERT:` stays out of `@all` | `assert-single-element.feature` / A single-element ASSERT passes and a later step runs |
| `SOFT ASSERT:` single element | `SOFT ASSERT: false` | | `false` and `""` do not throw | a later step still runs; not a hard fail | `assert-single-element.feature` / A single-element SOFT ASSERT does not throw and a later step runs |
| `ends with` | `IF: "Phoenix" ends with "nix" THEN:` | ends with `nix` runs | ends with `Ava` skips | does not throw | `phrase-plain-forms.feature` / Ends with and starts with select the branch |
| `starts with` | `IF: "Phoenix" starts with "Pho" THEN:` | starts with `Pho` runs | starts with `Tem` skips | does not throw | `phrase-plain-forms.feature` / Ends with and starts with select the branch |
| `matches` | `IF: "Ava" matches "A.+" THEN:` | whole-string Java regex `A.+` runs | `T.+` skips | `Pattern.compile` and `Matcher.matches`; double quotes are case-sensitive; a single quote on either side is case-insensitive | `phrase-plain-forms.feature` / A quoted value matches a whole-string Java regex |
| `is blank` | `IF: "" is blank THEN:` | `""` runs | `"abc"` skips | does not throw | `phrase-plain-forms.feature` / Is blank selects an empty string |
| `is selected` / `is unselected` | `IF: "Email" Radio Button is selected THEN:` | selected Email and unselected Phone run, then the reverse after Phone is chosen | the opposite radio state skips | forms playground radios; does not throw | `phrase-plain-forms.feature` / Forms playground element states select the branch |
| `is present` | `IF: "Submit Form" Button is present THEN:` | the visible Submit Form button runs | an element that is not on the forms page skips | does not throw | `phrase-plain-forms.feature` / Forms playground element states select the branch |
| `is required` / `is non-required` | `IF: "Required Marker" Textbox is required THEN:` | Required Marker runs; Last Name non-required runs | the inverses skip | Required Marker is the forms-playground fixture; does not throw | `phrase-plain-forms.feature` / Forms playground element states select the branch |
| backtick single element | `IF: \`abc\` THEN:` | backtick text runs | `IF: \`\` THEN:` skips | does not throw | `phrase-plain-forms.feature` / A backtick runs and zero no null and the null marker skip |
| quoted zero | `IF: "0" THEN:` | | a zero-only string skips | falsy; does not throw | `phrase-plain-forms.feature` / A backtick runs and zero no null and the null marker skip |
| `"no"` | `IF: "no" THEN:` | | skips | does not throw | `phrase-plain-forms.feature` / A backtick runs and zero no null and the null marker skip |
| `"null"` | `IF: "null" THEN:` | | skips | does not throw | `phrase-plain-forms.feature` / A backtick runs and zero no null and the null marker skip |
| null marker | `IF: <^~NULL~^> THEN:` | | skips | does not throw | `phrase-plain-forms.feature` / A backtick runs and zero no null and the null marker skip |
| comma chain | `, save "one" as "q", save "two" as "r"` | both saves run | | does not throw | `area-b-control-flow.feature` / A comma chains two saves |
| semicolon chain | `, save "one" as "q"; save "two" as "r"` | both saves run in one sentence | | does not throw | `area-b-control-flow.feature` / A semicolon chains two saves |
| period sentence | `, save "a" as "x". save "b" as "y":` | the colon child sees both saves | | does not throw | `area-b-control-flow.feature` / A period splits sentences and the colon child sees both saves |
| nested `:` | `, if "abc":` then a nested save | the child save runs | `, if "":` saves nothing | the following sibling still runs | `area-b-control-flow.feature` / A nested colon runs a truthy parent and skips a falsy parent |
| `?` parent | `, if "abc"?` | the child save runs | `, if ""?` saves nothing | condition only; no page context | `area-b-control-flow.feature` / A question-mark parent runs a truthy child and skips a falsy child |
| `::` | a grandchild under a truthy parent | the grandchild save runs | a falsy middle level saves nothing | does not throw | `area-b-control-flow.feature` / Nesting colons run depth 3 and a falsy middle level saves nothing |
| `:::` | one more colon under truthy parents | the depth-3 save runs | | does not throw | `area-b-control-flow.feature` / Nesting colons run depth 3 and a falsy middle level saves nothing |
| block `until` | `, until "<flag>" equals "done":` | a failing pass runs the body; the true pass stops | | exhausted `stepRepeatMaxCount` or `stepRepeatMaxTime` hard-fails; observed by Java checks | `area-b-control-flow.feature` / A block until loops until the condition is true on the second pass |
| inline `until` | `, until "abc", save "yes" as "q"` | one shot, the same as `if` | `""` skips | not a loop | `area-b-control-flow.feature` / An inline until is a one-shot if |
| `times` | `, save "tick" as "q" 3 times` | the save log shows three writes | | the first throw stops the repeat | `area-b-control-flow.feature` / Times saves the same value three times |
| context `in` | `, in the "Secondary Queue" Test Panel, click the "Approve" Button` | the secondary button runs | the Review badge in the primary panel is not displayed | does not throw | `area-c.feature` / in scopes a repeated button and misses the other panel |
| context `from` | `, from the "Primary Queue" Test Panel, click the "Approve" Button` | the primary button runs | the Approved badge in the secondary panel is not displayed | does not throw | `area-c.feature` / from scopes a repeated button and misses the other panel |
| context `for` | `, for the "Secondary Queue" Test Panel, click the "Approve" Button` | the secondary button runs | the Review badge in the primary panel is not displayed | does not throw | `area-c.feature` / for scopes a repeated button and misses the other panel |
| context `after` | `, after the "Order Anchor" Button, click the "After Marker" Button` | the following button runs | the preceding button is not displayed | document order, not visual position | `area-c.feature` / after chooses the following button and misses the preceding one |
| context `before` | `, before the "Order Anchor" Button, click the "Before Marker" Button` | the preceding button runs | the following button is not displayed | document order | `area-c.feature` / before chooses the preceding button and misses the following one |
| context `below` | `, below the "Spatial Anchor" Button, click the "Below Target" Button` | the lower button runs | the upper button is not displayed | rectangle overlap | `area-c.feature` / below chooses the lower button and misses the upper one |
| context `above` | `, above the "Spatial Anchor" Button, click the "Above Target" Button` | the upper button runs | the lower button is not displayed | rectangle overlap | `area-c.feature` / above chooses the upper button and misses the lower one |
| context `left of` | `, left of the "Spatial Anchor" Button, click the "Left Target" Button` | the left button runs | the right button is not displayed | rectangle overlap | `area-c.feature` / left of chooses the left button and misses the right one |
| context `right of` | `, right of the "Spatial Anchor" Button, click the "Right Target" Button` | the right button runs | the left button is not displayed | rectangle overlap | `area-c.feature` / right of chooses the right button and misses the left one |
| `hover` | `, hover the "Interaction Target" Button` | inline and block both record the pointer | a missing button fails in the Java checks | alias of `move` | `area-c.feature` / hover moves the pointer inline and in a block |
| `dragAndDrop` | `, dragAndDrop the "Drag Source" Button the "Drop Target" Button` | inline and block both drop | a missing source fails in the Java checks | two HTML elements after the verb | `area-c.feature` / dragAndDrop runs inline and in a block |
| `contains` | `, ensure "Phoenix" contains "nix"` | the phrase passes | `"Tempe"` fails in the Java checks | not combined with another operator | `area-c.feature` / contains passes on its own |
| `greater than` | `, ensure 5 is greater than 1` | the phrase passes | `1 is greater than 5` fails in the Java checks | not combined with another operator | `area-c.feature` / greater than passes on its own |
| `less than` | `, ensure 1 is less than 5` | the phrase passes | `5 is less than 1` fails in the Java checks | not combined with another operator | `area-c.feature` / less than passes on its own |
| `or equal` | `, ensure 5 is greater than or equal to 5` | greater-or-equal and less-or-equal pass | a smaller left side fails in the Java checks | not combined with another operator | `area-c.feature` / greater than or equal passes on its own |
| `enabled` / `disabled` | `, ensure the "Submit Form" Button is enabled` | enabled and disabled pass | the opposite state fails in the Java checks | not combined with another operator | `area-c.feature` / enabled and disabled pass on their own |
| `is on` / `is off` | `, ensure the "Receive Updates" Checkbox is off` | off, then on after a click | the opposite state fails in the Java checks | not combined with another operator | `area-c.feature` / is on and is off pass on their own |
| `is false` | `, if "false" is false, save "ran" as "isFalseHit"` | a falsy value takes the branch | bare `false` is truthiness and does not take the branch; `"abc" is false` does not | `is true` is the truthy assertion | `area-c.feature` / is false checks a falsy value and bare false stays falsy |
| key case | `, press "CONTROL[A]"` | uppercase names and `SPACE` work | `control` and `NOT_A_KEY` fail in the Java checks | names are case-sensitive; space separates sequential keys | `keyboard.feature` / A held modifier remains active across a sequential group |
| `navigate to:` once | `navigate to: URL.home` | Load Count is 1 | a second `get` would show 2 | one call per step | `area-c.feature` / keyboard hold dialog accept and one navigation |
| Doc String query | `, save "<doc>" Doc String as "nativeDocString"` | the whole value saves | context, every, any, and predicates fail in the Java checks | no query runtime | `area-c-data-elements.feature` / Doc String saves the whole value |
| element `wait` | `, wait the "Missing" Button` | | a missing element does not poll forever | exhausted `stepRepeatMaxCount` or `stepRepeatMaxTime` fails the step; observed by Java checks | `area-b-control-flow.feature` / Exhausted until, element wait, empty comma, SOFT FAIL, and dynamic step text are observed |
| `RETRY:` | `RETRY: not implemented` | the step passes and the next step runs | | not implemented; a no-op | `area-b-control-flow.feature` / RETRY and unimplemented flag steps are passing no-ops |
| unimplemented flags | `IGNORE FAILURES` | the step passes and the next step runs | | `LOG FAILURES BUT CONTINUE SCENARIO` and `RUN IF SCENARIO FINISHED` are also no-ops | `area-b-control-flow.feature` / RETRY and unimplemented flag steps are passing no-ops |
| `SOFT FAIL SCENARIO` | `SOFT FAIL SCENARIO "warning"` | later steps still run | | the step throws, so Cucumber fails the scenario; the observer does not fail the parent | `area-b-control-flow.feature` / Exhausted until, element wait, empty comma, SOFT FAIL, and dynamic step text are observed |
| child table / doc string | a colon child with a table, `DT:::`, or `DS:::` | the child table and doc string are kept; `DT:::` overrides that child | | only `DT:::` becomes a table | `area-b-control-flow.feature` / A colon child keeps its table and doc string and only DT becomes a table |

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
