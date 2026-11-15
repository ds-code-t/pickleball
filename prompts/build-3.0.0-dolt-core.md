# Grok Build Prompt: Pickleball 3.0.0 Dolt Core

You are Grok Build. Replace Pickleball's SQLite-backed "Dalton" layer with the Dolt-backed core, add Dolt-branch sandboxes, and remove the Dalton name, phase by phase, on branch `3.0.0`. Do not create a new repo. Do not do work outside the phases. This prompt is standalone; do not depend on chat history.

- **Repo:** https://github.com/ds-code-t/pickleball
- **Branch:** `3.0.0` only.
- **Start SHA:** `START_SHA` (it adds the roadmap, the design and this prompt). Fetch the current tip of `origin/3.0.0`, which may be newer, and record the SHA you actually start from in the scoreboard.

## Decision you are implementing (Dan, 2026-11-15)

- Pickleball 3.0.0's core is Dolt (dolthub.com), the SQL database with Git-style branches. "Dalton" was a voice mis-transcription of "Dolt". The `tools.dscode.dalton` SQLite layer is replaced by a Dolt-backed core reached through one store abstraction, talking to a loopback `dolt sql-server` over a MySQL-protocol JDBC driver.
- **One spine, multiple adapters.** The Dolt-backed execution core is the single spine. CLI, Workbench, MCP, the native suite, feature files and GUI/DOM triggers are thin adapters on the same engine, query definition dictionaries and execution path. The core treats current and future adapters alike and stays decoupled.
- The Cucumber adapter (`pkb_engine=cucumber`) is the one isolated legacy side branch. Its language, syntax and functionality never enter the Dolt core.
- CLI-first for everything.
- Dolt branches are isolated test sandboxes: branch, change a Java step definition, a feature file or data, and run a closed project state without affecting other agents. Git/GitHub stays the source of truth and the only project sync. Dolt never saves the project; the database is never committed and is rebuilt from files.
- Remove ALL "Dalton" naming outside history files, with no aliases: `tools.dscode.dolt`, `pickleball dolt <cmd>`, `pkb_dolt*`, `PKB_DOLT*`, `dolt.properties`, Gradle `dolt*`, `.pickleball/dolt/`. Docs must say Pickleball's `dolt` commands are Pickleball's layer backed by the real Dolt database, not Dolt's own `dolt` binary.
- Out of scope: the diagnostic-report SQLite index and its "SQLite index parity" CI. `sqlite-jdbc` stays for it.

## Read first, in order

1. `AGENTS.md` (whole file; "Dalton" and "One spine, multiple adapters" sections).
2. `docs/maintainer/3.0.0-dolt-core-design.md` — every locked decision. Follow it; where it says "to verify in P1", verify and write the answer back into it.
3. `docs/roadmaps/3.0.0-dolt-core-roadmap.md` — phases, functionality, docs, named tests, acceptance, the rename inventory, and the scoreboard you fill.
4. `docs/maintainer/dolt-isolation-analysis-2026-11-15.md` — verified Dolt facts and dialect traps (its recommendation is overridden).
5. `docs/maintainer/3.0.0-dalton-design.md`, `docs/maintainer/3.0.0-linked-tracking-design.md`, `docs/maintainer/execution-path-audit-2026-11-15.md`, `docs/agent/feature-map.md`, `docs/configuration.md` (Dalton part), `docs/release-notes.md`.
6. The source in `src/main/java/tools/dscode/dalton/`, `DaltonScenarioSource`, `FeaturePathLoadCache`, the launcher dispatch, and `pickleball-workbench/.../workbench/dalton/`.

Branch docs win over this prompt on repo facts. Dan's decision above wins over any older recommendation.

## Hard rules (every phase)

- NEVER commit, push or merge to `master`, `2.2.0` or `2.1.19`. No tags, releases, publishing or force-push. Never run `./gradlew --stop`.
- Do not touch open PRs #2 and #3 on `ds-code-t/dom-composite`.
- Keep Gherkin syntax unchanged. Cucumber step annotations stay single string literals with identical values.
- Never weaken, skip or delete tests. A SQLite-only assertion is replaced by its Dolt equivalent and listed in the scoreboard notes.
- CLI-first: Workbench, MCP and Gradle only forward to `pickleball dolt`. The Workbench never opens a database, loads core store classes or starts the Dolt binary.
- No Cucumber types or syntax in `tools.dscode.dolt`.
- Logging for new code: toggleable, per-entity, ISO date-stamped, size-capped (`pkb_dolt_log`, `pkb_dolt_log_entity`, `pkb_dolt_log_max_bytes`).
- No secrets in cleartext anywhere: rows, logs, diffs, sandbox source dumps, commits, prompts.
- Dolt binary: pinned 2.7.3 with the SHA-256 values in the design; never shaded into the jar.
- Docs are rewritten IN PLACE to describe what is implemented at each commit; history goes to `docs/release-notes.md`. Never replace or truncate `AGENTS.md` or `docs/agent/feature-map.md` wholesale; edit only changed paragraphs and compare `wc -c` before each push. Keep the AGENTS.md lines `verify_agent_contract.py` checks.
- Regenerate `docs/agent/repository-index.md` only with `python3 scripts/refresh_agent_index.py`. Before every push: `python3 scripts/refresh_agent_index.py --check`, `python3 scripts/verify_agent_contract.py`, `python3 scripts/sync_consumer_guidance.py --check`. Remove `scripts/__pycache__`.
- Never write placeholder or 0-byte files.
- Exported guidance docs must not link to `docs/maintainer/`, `docs/roadmaps/` or `prompts/` paths (`verify_agent_contract.py` fails); name them in words.
- Push every green phase: `git pull --rebase origin 3.0.0`, then `git push origin HEAD:3.0.0`. Never force.

## Tests

- After each phase run ONLY the targeted tests the roadmap names for it: `timeout 600 ./gradlew --no-daemon <module>:cleanTest <module>:test --tests <filter>`.
- HELD until Dan asks: the full multi-module regression, `validate --all`, first-start timing, consumer-sim, and Maven `@all`. Do not run them.
- A failed targeted test stops the ladder until it is fixed honestly.

## Phases (details in the roadmap)

- **P0** Add the missing fix-back scoreboard row for `defc3c51` (docs only).
- **P1** Dolt binary install (pinned, checksum, auto-install, offline error), sql-server lifecycle (supervisor, lock, port, reuse, stop), MariaDB driver, JDBC branch spike; record every "to verify" answer in the design.
- **P2** `DoltStore`/`DoltDialect`, full schema, port all ~114 statements; one test per dialect trap, especially `||` returning 0.
- **P3** Core on Dolt by default: hydrate ends with a Dolt commit, fresh clone, migration of `.pickleball/dalton/dalton.db` (edits imported, history dropped), core suite ported.
- **P4** Full Dalton → Dolt rename from the roadmap inventory, no aliases, `LegacyNames` loud failures, rg gate.
- **P5** Sandboxes: `sandbox create|list|use|diff|extract|drop|prune`, per-connection binding, per-sandbox worker JVM and compiled-glue dir first on the classpath, the `.java` gap fix.
- **P6** Workbench/MCP/Gradle forwarding, CI installs Dolt on the Linux and Windows jobs, AGENTS.md, guidance sync, release notes, final scoreboard.

## Completion

**Every phase is mandatory. Do not stop early, do not skip phases, and do not mark anything optional, deferred, "follow-up" or "out of scope" unless the roadmap says so. Complete every item in every phase, in full.** Stop only on an honest test failure you cannot fix, a hard-rule violation, or a real blocker (for example the Dolt download is unreachable). Then report the blocker with what you tried.

After each green phase: fill that phase's row in the roadmap scoreboard (status, full SHA, CI runs if available, test commands with counts, notes including any replaced assertions), commit, push to `3.0.0`, and continue to the next phase in the same session unless you are near your context limit, in which case say exactly where to resume.

## Every reply ends with a scoreboard

```
Start SHA: <sha>   Current tip: <sha>
Phases done: P0 ✅ P1 ✅ P2 … (status per phase)
Tests: <class> <run>/<failed> … per phase
Commits: <phase> <sha> …
CI: <Agent Contract run> / <SQLite parity run>
Blocker: none | <what, where, what you tried>
```
