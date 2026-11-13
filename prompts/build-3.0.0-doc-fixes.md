# Grok Build task: fix every stale architecture statement on pickleball branch 3.0.0

You are working in the GitHub repository **ds-code-t/pickleball** on branch **`3.0.0`**. You have no other context, and this prompt is complete. Read all of it before you start.

## 0. Goal

A documentation audit, `docs/audits/3.0.0-architecture-doc-audit.md`, is on branch `docs/3.0.0-architecture-audit` (PR #23 into 3.0.0). It found docs, agent instructions, comments, and messages on 3.0.0 that still describe the old architecture:
- Pickleball "built on Cucumber"
- Selenium as the element path
- the external Rust `composite` binary from `ds-code-t/dom-composite` 2.4.0, found through `COMPOSITE_BIN`, `COMPOSITE_HOME`, or a sibling checkout, as the normal engine

Your job:
1. Investigate and fix **every** finding in that audit.
2. Fix **everything related it did not cover**. The worklist in section 4 includes a sweep beyond the audit.
3. Record the resolution in the audit report.

The audit was written **before** commit `1059667f` and its parent `b1b1adac`. Those commits changed the following:
- `DirectControlProvider` and Workbench direct control now resolve the in-repo Java engine.
- A paragraph was added to `docs/direct-control-handoff.md`.
- `src/main/resources/dev/domcomposite/natives/README.txt` was rewritten.
- A 3.0.0 release note was added.

**Re-check every finding against the current tip of `origin/3.0.0` before you edit.** Where code and the audit disagree, the code wins. Section 2 lists places where the audit itself is wrong.

## 1. Branch, commits, and pushing

- Work only on branch `3.0.0`. Start with `git fetch origin && git checkout 3.0.0 && git reset --hard origin/3.0.0`. This is a fresh clone, so the reset is safe.
- Push directly to `origin 3.0.0` with normal fast-forward pushes: `git push origin 3.0.0`.
- **Push after every implementation change.** Make small, focused commits, one area per commit, and push each one right after you commit it. Never leave work committed but unpushed, or uncommitted, at the end.
- If a push is rejected because the remote moved, run `git pull --rebase origin 3.0.0`, re-run the checks in section 5, and push again. **Never force-push.**
- **First commit: bring the audit report onto 3.0.0.**
  1. Run `git fetch origin docs/3.0.0-architecture-audit`.
  2. Run `git checkout origin/docs/3.0.0-architecture-audit -- docs/audits/3.0.0-architecture-doc-audit.md`. Bring in only that one file. Do **not** merge the branch: its `docs/agent/repository-index.md` and packaged-guidance copies are older than 3.0.0.
  3. Regenerate the index and guidance with the scripts in section 5. Never hand-copy them.
  4. Commit and push.
  5. Do **not** merge or close PR #23. Mention in your final report that its content is now on 3.0.0.
- **Keep the audit out of the consumer jar (preferred).** `scripts/sync_consumer_guidance.py` mirrors every `docs/**/*.md` into `src/main/resources/META-INF/pickleball/guidance/`, so the audit report would ship to consumers. Do this in its own commit:
  1. Add `"docs/audits/"` to `MAINTAINER_ONLY_PREFIXES` in `scripts/sync_consumer_guidance.py`. That tuple already holds `docs/maintainer/` and `docs/roadmaps/`.
  2. Re-run sync and the index refresh.
  3. Confirm that `python3 scripts/verify_agent_contract.py`, `GuidancePackagingTest`, and `PickleballLocalStoreTest` all pass.
  4. If that change breaks a check you can't fix without weakening it, revert that one commit, leave the report mirrored, and say so in your final report.
- **Last commit:** append a `## Resolution (YYYY-MM-DD)` section to `docs/audits/3.0.0-architecture-doc-audit.md`.
  - Give one line per audit finding (1 to 9, plus the audit's "Prioritized Fix List" items 8 to 10) and one line per extra item (E1 to E33 below).
  - Mark each line **fixed** with the commit SHA, **left as history** with the reason, or **not applicable** with the reason.
  - Also list the audit statements that were wrong (section 2).
  - Do not rewrite the audit's original text. Only append.

## 2. Verified current architecture (state this accurately; do not invent)

I verified these facts in code on `origin/3.0.0` at `1059667f`. Re-verify anything you're unsure of before writing it into a doc.

**Version**
- There is **no `VERSION` file**.
- The version is `3.0.0`. It appears in `build.gradle` (`version = '3.0.0'`), `maven-consumer-project/pom.xml` (`<pickleball.version>3.0.0</pickleball.version>`), and `dom-engine/src/main/java/dev/domengine/EngineInfo.java` (`NAME = "domengine"`, `VERSION = "3.0.0"`).
- Use `3.0.0` in install snippets.

**Execution engine (`pkb_engine`)**
- `src/main/java/tools/dscode/engine/EngineConfig.java` sets the precedence: system property `pkb_engine`, then env `PKB_ENGINE`, then `engine.properties` `mode=`.
- The **default is `native`**. `pkb_engine=cucumber` selects the Cucumber adapter, which stays supported, along with its AspectJ aspects.
- The dual-run parity harness (G9) is green. `docs/roadmaps/3.0.0-decouple-round3-roadmap.md` row P12 says "Default is native".
- Cucumber is a **compatibility adapter**. Gherkin feature files, tags, Outlines, DataTables, hooks, and custom Java step definitions keep working. The dynamic runtime does not depend on Cucumber.

**Element engine**
- The in-repo **Java DOM engine** lives in module `dom-engine`, package `dev.domengine`, with main class `dev.domengine.cli.DomEngineMain`. Its classes and sources ship **inside the pickleball jar**, and there is no separate artifact (`DomEnginePackagingTest`).
- The thin Workbench jar also carries the engine classes (non-transitive; Jackson comes from Workbench runtime libs). See `pickleball-workbench/build.gradle` ~line 59.
- Ways to run the CLI:
  - `java -jar pickleball-<version>.jar domengine <args>` (`PickleballWorkbenchLauncher.dispatchDomEngineIfAny`)
  - Gradle `./gradlew domengine -PdomengineArgs='...'` (forward only)
  - Workbench `DomEngineCliForwarder`, or MCP `domengine`
  - `dom-engine/build.gradle` sets `applicationName = 'domengine'`
- `capabilities` reports:
  - `actions`: `click, type, scroll, submit, key, dblclick, contextclick, drag, eval`
  - `query_flags`: `within, nesting, nth, every, count-only, geometry, pierce, point, viewport, exposure`
  - `desktop`: `list, attach, walk, query, resolve, close, click, type`. **The Java engine has `desktop click` and `desktop type`.** Only the Rust 2.4.0 binary lacks them.
  - `observe.events` = true.
- **How the engine is located for normal element operations:** `src/main/java/tools/dscode/common/composite/CompositeBinaryLocator.locate(Sources)`, in this order:
  1. An explicit binary: property `pkb_composite_bin`, then `pkb_runvars.pkb_composite_bin`, then env `COMPOSITE_BIN`, then env `pkb_composite_bin`.
  2. Unless Rust is requested (`pkb_composite_engine=rust`, as a property, `pkb_runvars.` property, env `PKB_COMPOSITE_ENGINE`, or env `pkb_composite_engine`): the **Java engine launcher** (`JavaEngineLocator` → `tools.dscode.control.protocol.JavaEngineLauncher.script`). It writes a small `java -cp <engine+jackson> dev.domengine.cli.DomEngineMain "$@"` launcher, or `.cmd` on Windows.
  3. Only after that, the Rust fallbacks: `DirectControlProvider.resolveRustBinary` (`COMPOSITE_BIN`, then `COMPOSITE_HOME` or a sibling `dom-composite` checkout, `target/debug/composite` then `target/release/composite`), then a classpath native `dev/domcomposite/natives/<classifier>/composite[.exe]`, then the Maven classifier jar `dev.domcomposite:composite-native:2.4.0:<classifier>` under `~/.m2`, then `composite` on `PATH` (only when `pkb_composite=on`).
  - In a normal run the Java engine is always on the classpath, so steps 1–2 decide. **Rust is used only when someone explicitly sets a binary path or `pkb_composite_engine=rust`.**
- **`pkb_composite`** (`CompositeMode`): values are `auto` (default), `on`, `off`.
  - `off` (also `false`, `legacy`, `selenium`) uses the Selenium element path and does not look for an engine.
  - `on` (also `true`, `required`, `composite`) behaves like auto but always prints the missing-engine `error:` / `fix:` line, and also accepts `composite` on PATH.
  - The element **path** is chosen by `pkb_composite`. The engine **implementation** (Java or Rust) is chosen by `pkb_composite_engine` or an explicit binary.
- **Client:** the vendored `dev.domcomposite.Client` / `Session` package (`src/main/java/dev/domcomposite`) is **still the live client**. `CompositeRuntime` creates `new Client(<located path>)`, which talks to a long-lived `<path> serve --stdio` child, and by default that path is the Java engine launcher. **Do not delete, rename, or move `dev.domcomposite`.** Just describe it accurately as the stdio JSON client for the engine CLI, Java by default.
- **When the Selenium element path is used:**
  - `pkb_composite=off`
  - no engine could be located or started (`CompositeRuntime.MISSING`, or "client failed")
  - the engine could not join the browser
  - a WebDriver that has not joined an engine session (process-only is not a join). The `ElementOperations` find then uses Selenium, and `pkb_composite_log=element` writes `INFO element find selenium unattached`.
  - WebDriver/Selenium always keeps **browser lifecycle**: local Chrome/Edge startup, Selenium Manager, navigation, window handles, CDP address, frames.
- **Capability-gated behavior in `AGENTS.md` and docs** ("when the binary lists X ..."): this still applies. It now describes what the **engine** reports in `capabilities`. The Java engine lists all of those verbs and flags, so the "binary that does not list it" fallbacks are now only reached with an older Rust override. Reword "binary" to "engine" where it means the default path, but keep the fallback descriptions. They are still real code paths and are tested.

**Workbench direct control (`pkb_direct_control_provider`)**
- The setting lives in `runvars.properties`, or as a system property / `pkb_runvars.` form.
- `DirectControlProvider.resolveBinary("composite", ...)` uses this order:
  1. env `COMPOSITE_BIN`
  2. `pkb_composite_bin` (system property, `pkb_runvars.` property, env)
  3. the Java engine launcher (unless Rust is requested)
  4. the Rust homes (`COMPOSITE_HOME`, sibling `dom-composite`)
- Any other value is treated as a path.
- **Note the precedence difference:** the root locator checks `pkb_composite_bin` *before* `COMPOSITE_BIN`, and the provider checks `COMPOSITE_BIN` first. The owner wants consistent behavior, so **fix it**: make `DirectControlProvider.resolveBinary` use the same precedence as the root `CompositeBinaryLocator` (explicit `pkb_composite_bin` first, then `COMPOSITE_BIN`, then the Java engine unless Rust is requested, then the Rust fallbacks). Ideally share one ordering helper so the two cannot drift. Add a test asserting both resolvers give the same answer when both variables are set. Adjust any existing test that asserted the old provider order only by updating its expected precedence (no weakening, no deletion). Commit and push it on its own, then document the single order everywhere.
- The provider runs `<engine> session attach --cdp ... --id ... --i-accept-local-attach --session-dir .pickleball/runs/<runId>/session/composite`.
- The descriptor fields `compositeSessionDir` and `compositeSessionId` and the directory name `session/composite` are **wire/contract names. Do not rename them.**
- `DirectControlProviderTest` is green with 0 skips (it skips only without Chrome), and CI job `dom-engine-live` runs it.

**Dalton**
- Dalton is **opt-in** (`AGENTS.md` "Dalton (3.0.0)": "Dalton is opt-in. With no `pkb_dalton` setting ... consumer projects stay on the file-only path"). Git files remain the source of truth.
- Describe it as the opt-in Git-files-to-SQLite working copy for features, configs, logs, and entities. **Do not call it "central", "the core", or the default**, because that contradicts code and docs.

**Rust**
- Rust may be documented **only** as the optional, explicit override (`pkb_composite_engine=rust`, or an explicit `pkb_composite_bin` / `COMPOSITE_BIN` path, plus `COMPOSITE_HOME`, sibling checkout, or native classifiers as places that override looks).
- Never describe it as required, recommended, or the normal path.
- Never describe a Rust-binary test skip as legitimate. The Java engine replaced it.
- Rust **wire-compatibility** comments inside `dom-engine` are legitimate and stay. Examples: `SessionRegistry`, `SessionEvents`, `DomEngineMain` "--session-dir is global on the Rust CLI", `Jsons` "Same layout as the Rust CLI", and `Conformance` / `LiveConformance` "Rust fixtures". They document deliberate parity.

**Where the audit is wrong or outdated (do not copy these)**
- A1. The audit says Dalton is "central". Code and `AGENTS.md` say Dalton is **opt-in**. Use opt-in wording.
- A2. The audit says Selenium remains "only for browser lifecycle" and that Selenium is the fallback "only when explicitly off". Selenium is also the element path when no engine can be located or started, when a join fails, and for an unjoined WebDriver.
- A3. Finding 7 implies the vendored `dev.domcomposite` binding is no longer the mechanism. It **is** still the live client. It drives the Java engine launcher's `serve --stdio`.
- A4. Finding 4 and the audit header say the Workbench / `DirectControlProvider` binary-only lookup "is being fixed separately". It **was fixed** in `b1b1adac` / `1059667f`. Do not write "being fixed separately" anywhere.
- A5. The audit says `docs/cucumber-compatibility.md` is "already current". Line 5 still says "Pickleball is built on Cucumber rather than replacing it" (item E1).
- A6. Finding 4 suggests showing a bare `domengine` CLI. Consumers run it as `java -jar pickleball-<version>.jar domengine ...` or Gradle `domengine`. A bare `domengine` script exists only from `:dom-engine:installDist`. For the attach examples, the command is whatever path the locator or launcher resolves.
- A7. Finding 8 suggests a note that the native engine is the default. That is right, but the audit missed that three 3.0.0 subsections (`### Java DOM engine`, `### Regression Fix-back 1`, `### Regression Fix-back 2`, currently `docs/release-notes.md` ~lines 266–294) sit under `## 2.1.16` (item E14).
- A8. The audit did not cover `docs/configuration.md` rows, Java/Javadoc messages, or several live docs (items E2–E13, E15–E22).

## 3. Guardrails (hard rules)

- **Never** commit, push, or merge to `master`, `2.2.0`, or `2.1.19`. Do not open PRs into them.
- No force-push, no tags, no GitHub releases, no publishing (Maven Central, JitPack, `publish*` tasks to remote). `publishToMavenLocal` is fine if you need it locally. Don't bump versions.
- Do not merge or touch PRs #2/#3 on `ds-code-t/dom-composite`, and do not push to that repo.
- **AGENTS.md files are append-only.** This applies to the root `AGENTS.md`, `pickleball-workbench/AGENTS.md`, and `maven-consumer-project/AGENTS.md`.
  - The root `AGENTS.md` does not itself forbid edits, but the repository owner's standing rule is that existing `AGENTS.md` text is never edited or deleted.
  - Correct stale statements by **appending** a new dated section at the end of the root `AGENTS.md`. Use a heading like `## 3.0.0 architecture corrections (2026-11-13)`.
  - That section must say explicitly that it **supersedes** named earlier paragraphs. Quote the first few words of each stale paragraph so a reader can find it.
  - Then give the corrected statement. Base it on section 2, using the current terms ("engine" vs "binary", Java default, Rust override only, native default execution engine, Cucumber adapter, Dalton opt-in, Selenium lifecycle and fallback).
  - Do not modify any existing line of any `AGENTS.md`.
  - The bridge files (`CLAUDE.md`, `GEMINI.md`, `REVIEW.md`, `.github/copilot-instructions.md`, `.cursor/rules/pickleball.mdc`, etc.) are separate files. Edit them only if they actually contain stale architecture text. The sweep found none.
- Do not change Gherkin syntax, feature-file step text, or `.feature` file names. Do not rename `maven-consumer-project/src/test/resources/features/dom-composite.feature`. Only link *text* that points at it may change.
- Do not weaken, skip, disable, or delete tests or assertions. Cucumber annotations (`@Given/@When/@Then/...`) stay single string literals with identical values.
- **If you change the text of an error, help, or log message:**
  - Update any test that asserts it by changing **only** the expected text to the new correct message. Do not loosen the assertion, for example to `contains("")` or removing it.
  - Known asserting tests:
    - `pickleball-control-protocol/src/test/java/tools/dscode/control/protocol/DirectControlProviderTest.java` ~line 57 expects `"composite binary not found"` in the `BINARY_MISSING` message.
    - `src/test/java/tools/dscode/common/composite/SelectorAndAdapterTest.java` ~line 126 expects the desktop-verb `fix:` line to contain `dev.domcomposite:composite-native:2.4.0`. That is the Rust-override path. **Keep that coordinate in the message** and lead with the Java-engine guidance.
  - Search for other tests yourself: `rg -n "<old message fragment>" --glob '*Test*.java' --glob '*.feature'`. Also check `src/test/resources/guidance-golden/documented-help.txt` and `src/test/resources/workbench-help-golden.txt` if you touch launcher help. Regenerate goldens only per `docs/maintainer/guidance-goldens.md` (`-Dpkb.update.golden=true`) and only when the help text is meant to change.
  - Keep every test that exercises a Rust or explicit-binary override path working (for example, the `COMPOSITE_BIN` fakes in `AgentLiveFixTest` and `LiveChromeCdpTest`, and the explicit-bin and Rust precedence tests in `DirectControlProviderTest`).
- **Do not change runtime behavior.** This task is docs, comments, and message text only. Exceptions:
  - message strings
  - the optional `MAINTAINER_ONLY_PREFIXES` entry in section 1
  - If you find a real code bug, do not fix it here. Report it under gaps.
- Do not change public constants' names or values, such as `CompositeBinaryLocator.VERSION = "2.4.0"`. It names the Rust override's version and is used in tests and messages. Do not change wire names, config keys, or CLI verbs.
- **Never hand-edit or truncate `docs/agent/repository-index.md`**, or its packaged copy. A previous session accidentally replaced about 1,700 lines of it. Regenerate it only with `python3 scripts/refresh_agent_index.py`.
  - After regenerating, check `git diff --stat docs/agent/repository-index.md`. A doc-only change should add or remove only a few lines.
  - If the diff shows hundreds of deletions, stop. Restore it with `git checkout origin/3.0.0 -- docs/agent/repository-index.md` and re-run the script.
- Never hand-edit files under `src/main/resources/META-INF/pickleball/guidance/`. They are generated by `python3 scripts/sync_consumer_guidance.py`. Every edit to a mirrored `docs/*.md` (and the root `README.md`, if mirrored) needs that sync in the **same commit**. Otherwise the agent-contract CI fails with "Packaged consumer guidance is stale".
- **History stays history.** Changelog entries for past releases (`docs/release-notes.md` sections `## 2.2.0` and older), dated 2.1.x roadmaps and design docs, scoreboard rows, and root 2.1.9 drop-in files are records. Do not rewrite their facts.
  - Where a historical doc is still linked as if current, or records something later reversed, you may add **one** short line at the top of the section or row. For example: `> Historical (2.1.x). Superseded in 3.0.0: the in-repo Java DOM engine is the default; Rust is an explicit override only. See docs/direct-control-handoff.md.`
- Follow `AGENTS.md` "Documentation policy":
  - Update the existing canonical guide. Don't create competing guides.
  - Don't create new module READMEs. No module has one today. `pickleball-workbench/AGENTS.md` and `maven-consumer-project/README.md` are the module docs.
  - Keep wording plain and consistent with the surrounding doc style: short declarative sentences, backticked keys.

## 4. Worklist

Line numbers are approximate, from `origin/3.0.0` at `1059667f`. Re-locate each item with `rg` before editing. "Direction" is the target meaning, not mandatory wording. Write it in the doc's own style, and keep it accurate to section 2.

### 4.1 Audit findings (9)

**F1 — `README.md` ~3, ~9, ~11**
- Stale text: "Pickleball is a Java 21 testing framework built on Cucumber. It keeps normal Gherkin and Cucumber behavior..."; "A step can locate a Selenium element..."; bullet "Java Selenium integration for browser navigation, element selection, actions, and assertions".
- Direction:
  - Pickleball is a Java 21 testing framework whose dynamic runtime reads feature-file text.
  - The default execution engine is native. Cucumber is a supported compatibility adapter (`pkb_engine=cucumber`), so standard Gherkin features and Java step definitions still work.
  - Element finding, actions, state, geometry, and observation use the in-repo Java DOM engine packaged in the jar.
  - Selenium/WebDriver handles browser lifecycle and remains the element path with `pkb_composite=off`.
  - Keep the "How Pickleball differs from standard Cucumber" content and the compatibility paragraph (~line 26). They are still true. Reword only the stale claims.
  - Optionally add one bullet for opt-in Dalton, worded as opt-in.

**F2 — `docs/README.md` ~3, ~46**
- Stale text: "Pickleball extends Cucumber with a dynamic feature-file language while preserving normal Cucumber behavior."; the link text "how dom-composite 2.4.0 attaches to a live Workbench browser without closing it".
- Direction: make the intro match F1. Change the link text to say how the in-repo Java DOM engine (or an explicit Rust override) attaches to a live Workbench browser without closing it.

**F3 — `docs/getting-started.md` ~12, ~19**
- Stale text: "optional: the `composite` binary from [dom-composite](...) 2.4.0 when element operations should use that entry point. Without it, Pickleball keeps Selenium for that run. Set `COMPOSITE_BIN` or `pkb_composite_bin`..."; `<pickleball.version>2.1.16</pickleball.version>`.
- Direction:
  - Requirements: nothing extra is needed for element operations, because the Java DOM engine is in the Pickleball jar.
  - `pkb_composite=off` forces the Selenium element path.
  - `pkb_composite_engine=rust` with an explicit binary path is an optional override. Link to `configuration.md`.
  - Change the Maven example to `3.0.0`.
  - Keep "a Selenium-supported browser for browser scenarios". It is still true, since WebDriver starts the browser.

**F4 — `docs/direct-control-handoff.md` ~5, ~48 (`## Attach with dom-composite 2.4.0`), ~55–80 (CLI and `dev.domcomposite.Client` examples), ~128–160**
- Stale text: "dom-composite 2.4.0 is the optional tool."; the section header; examples presented as the dom-composite tool; line ~134, "The binary is found at runtime by `CompositeBinaryLocator`... `pkb_composite_bin` is first, then `COMPOSITE_BIN`, then `COMPOSITE_HOME` or a sibling checkout..." **This order is wrong. The Java engine comes before the Rust homes.**
- Direction:
  - Lead with the in-repo Java engine as what `pkb_direct_control_provider=composite` runs.
  - Rename the section, for example `## Attach with the DOM engine`.
  - Show the attach command in a form a consumer can run: `java -jar pickleball-<version>.jar domengine session attach --cdp ... --id <sessionId> --i-accept-local-attach --session-dir <dir>`. Say that `composite ...` in older examples is the same verb set.
  - Keep the `Client` Java example. It is still the binding. Describe it as the stdio client for the engine CLI, Java by default.
  - Give both resolution orders exactly as in section 2: provider and root locator.
  - Move `COMPOSITE_BIN` / `COMPOSITE_HOME` / sibling / native-classifier details into a short `### Optional Rust override` subsection.
  - Keep the paragraph that `1059667f` added (~132), merged so it isn't repeated.
  - Do **not** say anything is "being fixed separately".
  - Also see E10 for the desktop section in this file.

**F5 — `docs/dynamic-steps.md` ~320 (`## Dom-composite translation`), ~322, ~341, ~345**
- Stale text: section title; "`SelectorTranslator` is the only place that turns a phrase into a dom-composite pipeline"; "When composite is primary..." paragraphs; "dom-composite reports `submit`, ... when the binary has them".
- Direction:
  - Retitle to `## DOM engine translation`. No anchors link to the old title; I checked.
  - "dom-composite pipeline" becomes "engine pipeline".
  - "when composite is primary" becomes "with the DOM engine (default; `pkb_composite=off` uses Selenium)".
  - Capability sentences: the engine reports these on `capabilities`. The Java engine lists all of them. A Rust override that does not list one keeps the older mapping.
  - Change the link text at ~345 to "DOM engine adapter actions, lookups, and backward-compatible phrases". **Keep the target file name `dom-composite.feature` unchanged.**

**F6 — `AGENTS.md` ~11 (Purpose) and ~126 (Public contracts, element bullet)**
- Stale text: "Consumer scenarios ... exercise browser behavior through Selenium as well as ... dynamic Cucumber steps"; "Element find, click, ... go through `ElementOperations` and `DomCompositeAdapter` when a dom-composite 2.4.0 binary is present (`pkb_composite=auto`)" and "`pkb_composite=off`, or a missing binary, uses the Selenium calls...".
- Direction: **append-only** (section 3). Append the dated correction section. It supersedes the Purpose sentence and the opening clause of the element-contract bullet. State that:
  - Element operations go through `ElementOperations` → `DomCompositeAdapter` → the located engine: the in-repo Java DOM engine by default, or an explicit binary or `pkb_composite_engine=rust` for the Rust override.
  - The Selenium element path is used for `pkb_composite=off`, a missing or failed engine, or an unjoined driver.
  - The capability-gated rules in that bullet still apply, read as "engine" instead of "binary".
  - Scenarios exercise browsers through the DOM engine with WebDriver lifecycle, and run on the native engine by default, with the Cucumber adapter.
- Also supersede E13 in the same appended section.

**F7 — `build.gradle` ~186–191 (the comment above `selenium-java`)**
- Stale text: "dom-composite 2.4.0 Java binding (dev.domcomposite.Client / Session) is vendored ... Native classifiers are optional...".
- Direction:
  - Selenium is browser lifecycle and the `pkb_composite=off` element path.
  - The in-repo Java DOM engine (`project(':dom-engine')`) is embedded in the jar and is the default element engine.
  - The vendored `dev.domcomposite` `Client` / `Session` is the stdio client Pickleball uses to talk to the engine CLI. Keep the d63887c provenance.
  - Rust native classifiers matter only for the `pkb_composite_engine=rust` override.
  - This is a comment only. No build logic changes.

**F8 — `docs/release-notes.md` `## 3.0.0` (~3–46)**
- Stale text: the Round 3 bullets ~39–42 ("The default remains `cucumber`", "Phase 12 leaves the default engine at `cucumber`") are followed by ~44 "The default engine is now `native`", which reads as contradictory.
- Direction:
  - Add a short summary at the top of `## 3.0.0`: the native engine is the default execution engine (`pkb_engine=cucumber` keeps the adapter); the in-repo Java DOM engine is the default element engine and ships in the jar; Rust is an explicit override only; Dalton is opt-in.
  - Add "(later superseded by G9/P12 below: the default is now `native`)" to the Phase 9/11/12 bullets rather than deleting them.
  - Also see E14.

**F9 — `docs/maintainer/3.0.0-engine-design.md` ~18, ~48, ~54**
- Stale text: "Default engine stays the Cucumber adapter until the dual-run parity harness is green"; Phase 9 "Default `cucumber`"; Phase 12 "The default stays `cucumber`".
- Direction: this is a locked design doc. Add an "As built (current)" note near the top, and a note on the Phase 9/12 lines: parity (G9) is green and the default is `native` per the P12 row in `docs/roadmaps/3.0.0-decouple-round3-roadmap.md`. Cucumber remains a supported adapter. Keep the original text as the design record.

**Audit fix-list items 8–10** (also resolve these):
- configuration.md: see E11.
- Remaining error and help messages: see E15–E19.
- Index refresh: section 5.

### 4.2 Extra sweep beyond the audit (E1–E33)

Live instructions (fix):

- **E1 `docs/cucumber-compatibility.md` ~5.** "Pickleball is built on Cucumber rather than replacing it." The audit wrongly called this file current. Direction: Cucumber is a supported compatibility adapter. Standard Cucumber features and Java step definitions keep working, and dynamic steps can be mixed with them. The default engine is native, and `pkb_engine=cucumber` keeps Cucumber. Keep the rest of the page, which already covers the native engine and `pkb_engine=cucumber` (~79–81).
- **E2 `docs/README.md` ~16.** The link text says "describe Selenium elements, actions, ...". Change it to "describe page elements, actions, ...".
- **E3 `docs/dynamic-steps.md` ~15.** The heading is `## Selenium element descriptions`. Change it to `## Element descriptions`; no anchor links to it. Fix any body sentence that says elements are Selenium elements on the default path.
- **E4 `docs/consumer-agent-guide.md` ~57.** "Elements use dom-composite when its binary is present, else Selenium (`pkb_composite=off` forces Selenium)". Change it to: elements use the in-repo Java DOM engine by default (Selenium when `pkb_composite=off`, or when no engine is available). This file has required strings checked by `scripts/verify_agent_contract.py` (`validate_dependency_owned_guidance`), so don't remove any of them.
- **E5 `docs/workbench-agent-detail.md` ~39.** "Use the descriptor to attach dom-composite 2.4.0 to the browser Pickleball already owns." Change it to: attach the DOM engine (in-repo Java engine; Rust only as an explicit override).
- **E6 `docs/pickleball-workbench.md` ~161.** "A live session publishes that descriptor so dom-composite 2.4.0 can attach..." and "The binary is `COMPOSITE_BIN`, `pkb_composite_bin`, `COMPOSITE_HOME`, a sibling `dom-composite` checkout, or..." Direction: the engine is the in-repo Java engine carried in the thin Workbench jar. Give the provider resolution order from section 2. `session/composite` and the `compositeSession*` fields keep their names. Check ~725 (already Java-engine text) for consistency.
- **E7 `docs/custom-element-definitions.md` ~164.** "When dom-composite is the primary path, that XPath is sent as `xpath`..." Change it to: with the DOM engine (default), that XPath is sent as `xpath`. Check the desktop sentences right after (~166) against E8.
- **E8 `docs/dynamic-steps.md` ~341, desktop part.** "The 2.4.0 desktop verbs are `list`, `attach`, `walk`, `query`, `resolve`, and `close` ... The native jar is `dev.domcomposite:composite-native:2.4.0`..." Direction: the Java engine reports desktop `click` and `type` (`DesktopTree.VERBS`), so desktop click and type work by default. The `error:` / `fix:` refusal applies only to an engine whose `capabilities.desktop` lacks them, such as the Rust 2.4.0 override. Keep the classifier coordinates only inside that override sentence.
- **E9 `docs/getting-started.md` ~107–130.** Re-read the rest of the page for consistency with F3. For example, "Local Chrome and Edge still start through Selenium" is true; keep it.
- **E10 `docs/direct-control-handoff.md` ~140–156** (`## Desktop click, type, and native classifiers`). "composite 2.4.0 desktop verbs are only ... That binary has no `desktop click`..." and the classifier table. Direction: retitle, for example `## Desktop click and type`. Say the Java engine supports `desktop click` and `desktop type`. Move the 2.4.0 limitation and the classifier table into the Rust-override subsection from F4.
- **E11 `docs/configuration.md` ~435–438** (settings table).
  - `pkb_composite`: change "`auto` (default) uses dom-composite 2.4.0 when the binary is found and otherwise Selenium for that run" to: `auto` (default) uses the DOM engine (the in-repo Java engine unless an explicit binary or `pkb_composite_engine=rust` is set), and Selenium for that run when no engine can be located or started. Describe `on` and `off` per `CompositeMode`, including the aliases `off|false|legacy|selenium` and `on|true|required|composite` if the table style allows.
  - `pkb_composite_bin`: "`composite` executable. Checked before `COMPOSITE_BIN`" stays true for the root locator. Add "explicit engine path; wins over the Java engine".
  - **Add missing rows**, verified in code:
    - `pkb_composite_engine` (env `PKB_COMPOSITE_ENGINE`): `rust` skips the Java engine and uses the Rust binary search order. Unset or anything else means the Java engine.
    - `pkb_engine` (env `PKB_ENGINE`, `engine.properties` `mode=`): `native` (default) or `cucumber`. Link to `cucumber-compatibility.md`.
    - `pkb_direct_control_provider`: blank means off; `composite` means the DOM engine via the provider order; any other value is a path. Link to `direct-control-handoff.md`.
  - Sweep the rest of the file (8 Selenium mentions) for default-path claims. Leave the browser-startup text (~489, ~512); it is correct.
- **E12 `docs/agent/feature-map.md` ~26.** The row title is `dom-composite element entry point (2.1.19)`, and it says "otherwise keep the 2.4 mapping". Direction: retitle, for example "Element entry point (`ElementOperations` / `DomCompositeAdapter`; 2.1.19, Java engine default in 3.0.0)". Note that the engine is the in-repo Java engine by default. Keep all class and test pointers. Row ~11 is already accurate; leave it.
- **E13 `AGENTS.md` ~110** (Dalton section). "Round 3 (engine decoupling from Cucumber, in progress)". Round 3 is complete, and native is the default. Fix this **only via the appended correction section** from F6. Also review ~372–412 (`## Native engine`, `## Java DOM Engine`) and supersede any statement there that contradicts section 2 in the same appended section. The sweep found ~402–410 accurate.
- **E14 `docs/release-notes.md` ~266–294.** `### Java DOM engine`, `### Regression Fix-back 1`, and `### Regression Fix-back 2` are 3.0.0 content but sit under `## 2.1.16`, after `### Other`. Move them, unchanged except for wording fixes required elsewhere in this list, to the end of `## 3.0.0` (before `## 2.2.0`).
  - Do not move or alter the `## 2.1.18` section. `GuidanceCommandGoldenTest` reads it.
  - Also check the duplicated "Sample `pkb_callpath` and `pkb_componentpath`..." bullet that appears in both 3.0.0 (~12) and 2.1.16 (~261). Leave both if each belongs to its own release.
  - Within the moved `### Java DOM engine` text, "`dom-engine` is the general-purpose Java replacement for the Rust composite binary" is fine and accurate.
- **E15 `src/main/java/tools/dscode/common/composite/CompositeRuntime.java`.**
  - ~23–25 Javadoc "A missing binary logs once and leaves the legacy Selenium path" becomes: a missing engine.
  - ~27 `MISSING = "dom-composite binary not found; using the legacy Selenium element path for this run"`. Change it to, for example, "DOM engine not found (no in-repo Java engine on the classpath and no explicit binary); using the legacy Selenium element path for this run". Keep the "using the legacy Selenium element path for this run" tail.
  - ~29 `MISSING_FIX` already leads with the Java engine. Keep the Rust sentence as "For the optional Rust override set `pkb_composite_engine=rust` and COMPOSITE_BIN, COMPOSITE_HOME, or pkb_composite_bin, or build the sibling dom-composite checkout (target/release/composite)".
  - ~292–293 and ~316–317 `"dom-composite could not join the browser|page session: ..."` / `"Leave pkb_composite=auto so this run stays on Selenium, or fix COMPOSITE_BIN."` Change to "DOM engine could not join ..." / "... stays on Selenium; check that the in-repo Java engine is on the classpath, or fix the explicit pkb_composite_bin / COMPOSITE_BIN path."
  - ~375 `"dom-composite client failed (...)"` becomes "DOM engine client failed (...)".
  - Search tests for each old fragment first. None were found for these at `1059667f`.
- **E16 `CompositeBinaryLocator.java` ~21–28** (class Javadoc). It lists only the Rust order. Rewrite it to the full order in section 2: explicit bin, then the Java engine unless Rust is requested, then the Rust fallbacks. Note that `VERSION = "2.4.0"` and `GROUP` / `NATIVE_ARTIFACT` / `CLASSIFIERS` describe the Rust override. ~123 debug `"no composite binary for "` becomes "no DOM engine for " (a log line; check tests, including `CompositeLocationTest`).
- **E17 `CompositeMode.java` ~6–12** (Javadoc). "`auto` (default) uses dom-composite when a binary is located". Change it to the engine wording from E11.
- **E18 `pickleball-control-protocol/.../DirectControlProvider.java`.**
  - ~20 class Javadoc "Optional dom-composite attach for one live Workbench session" becomes "Optional DOM-engine attach (in-repo Java engine by default) ...".
  - ~167 `"Direct-control provider skipped: composite binary not found."` becomes, for example, "Direct-control provider skipped: no DOM engine found (Java engine not on the classpath; no COMPOSITE_BIN, pkb_composite_bin, or Rust build)." Adjust `DirectControlProviderTest` ~57's expected substring to match, changing **text only**, or keep the substring `composite binary not found` inside the new message.
  - The ~68–75 and ~108–109 Javadoc is accurate; leave it.
  - `JavaEngineLauncher` and `JavaEngineLocator` Javadoc are accurate.
- **E19 `DomCompositeAdapter.java` ~636–652** (`requireDesktopVerb`). Error `"dom-composite " + VERSION + " has no desktop <verb> verb ..."` and fix "Upstream must add desktop verb ... Drop in dev.domcomposite:composite-native:2.4.0 classifiers ...". Direction: error "DOM engine has no desktop <verb> verb (capabilities.desktop is ...)". The fix should first say the in-repo Java engine supports `desktop click` / `desktop type` (unset `pkb_composite_engine=rust` / the explicit binary to use it), then keep the existing Rust 2.4.0 classifier guidance for the override. `SelectorAndAdapterTest` ~126 must still find `dev.domcomposite:composite-native:2.4.0`. Read the whole test and keep every assertion passing with text-only expected-value changes, if any are needed.
- **E20 Javadoc and comments describing architecture:**
  - `src/main/java/tools/dscode/common/driver/DriverConstruction.java` ~316 "Locates dom-composite when a browser session starts. A missing binary does not fail the driver." becomes "the DOM engine" / "a missing engine".
  - `CompositeLog.java` ~16 "CLI-first log for the dom-composite adapter" becomes "the DOM engine adapter".
  - `seleniumextensions/ElementSnapshot.java` ~12 "Both the Selenium path and the dom-composite path" becomes "the DOM engine path".
  - `seleniumextensions/SnapshotLookup.java` ~17 "dom-composite 2.4.0 has no element-scoped JavaScript eval". Verify how the snapshot path behaves when `capabilities.actions` lists `eval`, as the Java engine does: `AGENTS.md` says "A composite snapshot is one `eval` when `capabilities.actions` lists `eval`". Then reword: this lookup is the fallback for an engine without `eval`, such as the Rust 2.4.0 override.
  - `seleniumextensions/ElementWrapper.java` ~42 "dom-composite `StaleException`" may stay, because that is the class `dev.domcomposite.StaleException`. Optionally say "the engine client's".
  - `src/main/java/dev/domcomposite/Client.java` class Javadoc "Thin client for the `composite` CLI": add that the CLI is the in-repo Java engine launcher by default, or an explicit Rust binary. Check `Session.java` and `package-info` too, if present.
  - `src/test/java/tools/dscode/common/composite/CompositeElements.java` ~6 "Not a live dom-composite session" becomes "Not a live engine session". This is a comment only.
- **E21 `src/main/resources/dev/domcomposite/natives/README.txt`.** It was partly fixed in `1059667f`. Line 1 "dom-composite 2.4.0 native layout." becomes "Optional Rust override: dom-composite 2.4.0 native layout." Verify the order sentence against section 2. Do not add or remove native binaries.
- **E22 `pickleball-workbench/build.gradle` ~59 and other build comments.** These are accurate. Re-check every `build.gradle` comment that mentions composite, natives, or the engine (`rg -n -i "composite|natives|domengine|rust" -g '*.gradle'`) and fix any that describe the old architecture.
- **E23 Module docs.** No module has a README (`dom-engine`, `pickleball-control-api`, `pickleball-control-protocol`, `pickleball-workbench`). Sweep `pickleball-workbench/AGENTS.md` (append-only), `maven-consumer-project/README.md`, `maven-consumer-project/AGENTS.md`, and `maven-consumer-project/.github/copilot-instructions.md` for stale engine or Cucumber-foundation claims. The sweep found none. Report "checked, no change" if that holds. Do not create new READMEs.
- **E24 CI workflows** (`.github/workflows/agent-contract.yml`, `sqlite-index.yml`). The sweep found no stale architecture comments, and the `dom-engine-live` job names are accurate. Do not change triggers or jobs, including the `2.1.18` trigger in `sqlite-index.yml`; that is out of scope. Report "checked".
- **E25 Agent bridge files**: `CLAUDE.md`, `GEMINI.md`, `REVIEW.md`, `.aiassistant/`, `.junie/`, `.github/copilot-instructions.md`, `.github/agents/`, `.github/instructions/*.md`, `.amazonq/`, `.cursor/rules/`, `.continue/`, `.clinerules/`, `.windsurf/`, and `docs/agent/README.md`, `change-checklist.md`, `prompt-examples.md`. The sweep found no stale engine claims. Mentions of "Selenium behavior" as a compatibility contract are fine. Re-check and report.
- **E26 Final repo-wide grep**, after the fixes. Every remaining hit must be legitimate: the Rust override, wire or contract names, dom-engine parity comments, or history. Exclude generated `src/main/resources/META-INF/pickleball/guidance/**` and `docs/agent/repository-index.md`.
  ```
  rg -n -i "dom-composite|composite binary|COMPOSITE_BIN|COMPOSITE_HOME|sibling .{0,20}checkout|built on Cucumber|extends Cucumber|\bRust\b|composite-native|2\.4\.0|composite is (the )?primary|binary is present|being fixed separately" \
     -g '!src/main/resources/META-INF/pickleball/guidance/**' -g '!docs/agent/repository-index.md' -g '!md files/**'
  rg -n "2\.1\.1[0-9]" README.md docs/getting-started.md docs/consumer-project.md maven-consumer-project/README.md
  ```
  Put the count and the classification of the remaining hits in the final report.

History (leave; at most a one-line "Historical / superseded in 3.0.0" note):

- **E27 `docs/maintainer/3.0.0-java-dom-design.md`** ~3, ~7, ~9, ~14, ~24, ~33. This is the locked design. ~24 gives an order ("`pkb_composite_bin` (now path to Java CLI jar or script), `COMPOSITE_BIN`, classpath-embedded Java entry point...") that differs from what was built. Add an "As built" note pointing to `CompositeBinaryLocator` and `docs/direct-control-handoff.md`. The other lines are accurate design history.
- **E28 `docs/roadmaps/3.0.0-java-dom-roadmap.md`** ~109 (R-5) and ~120 (F-4), and **`docs/roadmaps/3.0.0-workbench-edit-roadmap.md`** ~229 (R-4). These rows record skips ("no composite binary for observe", "composite binary not vendored", "Observe still skips without the composite binary"). Those skips were removed later, in PR #22, `b1b1adac`, and `1059667f`, and the current regression shows only the `pwsh` skip. Add a short note after each row or table: "Superseded: those Rust-binary skips were removed; the tests now run on the in-repo Java engine." Do not edit the recorded numbers. Other mentions in these files (PR #2/#3 rule, "match the previous Rust suite") are fine.
- **E29 `docs/roadmaps/3.0.0-decouple-round3-roadmap.md`** ~219, ~233 ("The default stays `cucumber`"). These are already superseded in the same file by the G9 and P12 rows. Optionally add "(superseded by P12: default is native)". No other change.
- **E30 2.1.x maintainer and roadmap docs.** `docs/maintainer/2.1.19-*.md` (dom-composite, dom-efficiency, mapping-geometry, find-interface, element-snapshot, dom-access, workbench-expansion*, ctx-position), `docs/maintainer/2.1.18-report-7-roadmap.md`, and `docs/roadmaps/2.1.18-*.md` (direct-control-handoff, agent-retrieval, agent-retrieval-fixes). Leave them as history. Add a one-line historical header only to a file that a live doc links to as current. Check with `rg -n "2.1.19-dom-composite-roadmap|2.1.18-direct-control-handoff" docs README.md AGENTS.md`.
- **E31 `docs/release-notes.md` `## 2.2.0`, `## 2.1.19` (~52–60: "dom-composite 2.4.0 is the element entry point ...", ~138–147 in 2.1.18), and older.** These are history. Leave them.
- **E32 Root 2.1.9-era drop-in artifacts** (`README-APPLY.md`, `WORKBENCH-PLAYER-DROP-IN.md`, `BUNDLE-MANIFEST.txt`, `BUG-1-blank-datatable-cells.md`) and `md files/`. These are history and must not be deleted. Optionally add a one-line "Historical (2.1.9)" header to the `.md` ones. Don't touch `BUNDLE-MANIFEST.txt`.
- **E33 Legitimate Rust references in code: leave unchanged.**
  - The `dom-engine` parity comments: `session/SessionRegistry.java`, `session/SessionEvents.java`, `session/BrowserSession.java` ~160, `cli/DomEngineMain.java` ~56/~235, `json/Jsons.java` ~13, `conform/Conformance.java`, `conform/LiveConformance.java`.
  - `WorkbenchFeatureIndex.java` ~45 ("A Rust sidecar was measured and not added"), and the matching docs (`docs/pickleball-workbench.md` ~92, `docs/workbench-agent-detail.md` ~120, `docs/agent/feature-map.md` ~17, `docs/release-notes.md` ~147).
  - `JavaEngineLauncher` and `DirectControlProvider` Rust-override Javadoc.

Total: 9 audit findings plus 3 audit fix-list items, and 33 extra items (E1–E26 fix or check, E27–E33 history or leave).

## 5. Required checks (run them, in this order, before each push that touches the listed area)

Environment: Java 21 (Temurin). Use the Gradle wrapper `./gradlew` (Windows: `gradlew.bat`). Python 3.12 (CI) or any Python 3. `gradle.properties` already bounds the JVM. Use `--no-daemon` if your sandbox is small. Do not run `./gradlew --stop`. To force a rerun, use `cleanTest` or `--rerun-tasks` on the specific task.

**After every doc or guidance change** (the same commit must include the regenerated files):
```
python3 scripts/sync_consumer_guidance.py          # regenerates src/main/resources/META-INF/pickleball/guidance/**
python3 scripts/refresh_agent_index.py             # regenerates docs/agent/repository-index.md (NEVER hand-edit)
git diff --stat docs/agent/repository-index.md     # must be a few lines, never hundreds of deletions
python3 scripts/sync_consumer_guidance.py --check  # "Packaged consumer guidance is current."
python3 scripts/refresh_agent_index.py --check     # "Repository index is current."
python3 scripts/verify_agent_contract.py --base-ref origin/master   # as CI runs on push; must print "Agent contract files are valid."
```
`scripts/agent_validate.sh --quick` runs the three Python checks plus `./gradlew test`. Use it near the end.

**Doc-reading tests** (run after doc and guidance edits):
```
./gradlew --no-daemon :test --tests tools.dscode.GuidancePackagingTest --tests tools.dscode.launcher.GuidanceCommandGoldenTest --tests tools.dscode.launcher.WorkbenchCliCoverageGateTest --tests tools.dscode.launcher.WorkbenchPointerContractTest --tests tools.dscode.launcher.WorkbenchAgentCommandsTest --tests tools.dscode.launcher.WorkbenchSessionCommandsTest --tests tools.dscode.control.protocol.PickleballLocalStoreTest
```

**Classes whose messages or comments changed:**
```
./gradlew --no-daemon :test --tests tools.dscode.common.composite.SelectorAndAdapterTest --tests tools.dscode.common.composite.CompositeLocationTest --tests tools.dscode.common.composite.DomEnginePackagingTest --tests tools.dscode.common.seleniumextensions.ElementSnapshotParityTest --tests tools.dscode.common.agentcontrol.AgentLiveFixTest --tests tools.dscode.engine.EngineConfigTest
./gradlew --no-daemon :pickleball-control-protocol:test --tests tools.dscode.control.protocol.DirectControlProviderTest
./gradlew --no-daemon :pickleball-workbench:test --tests tools.dscode.workbench.worker.LiveSessionPublicationTest
./gradlew --no-daemon :dom-engine:test        # only if you touched anything under dom-engine (you should not need to)
```
`DirectControlProviderTest`'s live test needs Chrome (`DOMENGINE_CHROME`, or `google-chrome` / `chromium` / `chrome` on `PATH`). Without Chrome it skips only that one test. Report this honestly, and rely on CI job "Java DOM live Chrome" for the live run.

**Before the final push:**
- Run the full root suite once, `./gradlew --no-daemon :test`, if your sandbox allows. Compare it to the last known baseline: root 971 tests, 0 failures, 1 skip (`WorkbenchShellQuotingTest` without `pwsh`). Report any difference.
- Then confirm CI on the **final tip** of `3.0.0`: `gh run list -R ds-code-t/pickleball --branch 3.0.0 --limit 4`, or the Actions tab.
  - Workflows "Agent Contract and Consumer Validation" (jobs `windows`, `dom-engine-live`, `gate`) and "SQLite index parity" must both be `success` for the final SHA.
  - Wait for them, and fix any failure with a new commit and push.
  - Do not declare done while CI is red or still running.

## 6. Final response format

Reply with these sections:

1. **Commits:** every commit you pushed to `origin/3.0.0`, oldest first: `<short SHA> <subject>`. Also give the final tip SHA, and confirm `master`, `2.2.0`, and `2.1.19` were not touched.
2. **Worklist:** one line for each of F1–F9, audit fix-list items 8–10, and E1–E33. Mark each **fixed (SHA)**, **checked – no change needed (why)**, **left as history (why)**, or **not done (why)**.
3. **Audit errors confirmed:** A1–A8 from section 2, plus any others you found.
4. **Checks run:** each command, with pass/fail and counts (tests run, failed, skipped, with the skip reasons). Include the final grep count and how you classified the remaining hits, the `repository-index.md` diff size, and the CI conclusions for the final SHA with run URLs.
5. **Messages changed:** old text → new text, and which test expectations you adjusted (text only).
6. **Gaps / follow-ups:** anything not done, real code bugs found but not fixed, the status of PR #23 (content now on 3.0.0; left open for the owner), and any check you could not run.
