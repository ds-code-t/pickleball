# Grok Build Prompt: Pickleball 3.0.0 Embedded Store

You are Grok Build. Replace the Dolt sql-server layer under Pickleball's `dolt` commands with an embedded SQLite store, phase by phase, on branch `3.0.0`. Do not create a new repo. Do not do work outside the phases. This prompt is standalone; do not depend on chat history.

**Only start when Dan has said to build it.** On 2026-11-15 Dan asked for the design and roadmap only.

- **Repo:** https://github.com/ds-code-t/pickleball
- **Branch:** `3.0.0` only.
- **Start SHA:** the commit that adds this prompt, `docs/maintainer/3.0.0-embedded-store-design.md` and `docs/roadmaps/3.0.0-embedded-store-roadmap.md` (written against `7666bae655006159b5ab896c4b53b470e8db22af`). Fetch the current tip of `origin/3.0.0`, which may be newer, and record the SHA you actually start from in the scoreboard.
- **Toolchain:** JDK 21. Gradle wrapper. Every Gradle call is `timeout 600 ./gradlew --no-daemon …`. Never `./gradlew --stop`.

## Decision you are implementing (Dan, 2026-11-15, about 10:37 PM MST)

- Pickleball 3.0.0 currently runs a Dolt 2.7.3 `sql-server` as a child process, talks to it through the MariaDB JDBC driver, and downloads the Dolt binary at runtime from GitHub releases (about 38 to 42 MB per platform). Locked-down machines that can reach only Maven Central or a corporate mirror cannot get that binary. Dan wants a simplified, embedded, Java-only store built from existing libraries: no server, no runtime download, everything from Maven Central.
- **Library:** SQLite through `org.xerial:sqlite-jdbc:3.47.2.0`, already a published runtime dependency (Apache-2.0, natives for all platforms inside the jar). H2, HSQLDB, Derby and DuckDB were rejected because they cannot serve several processes from one file without a server. The SQLite session extension is not compiled into `sqlite-jdbc` and has no Java API, so changesets are emulated with triggers. JGit was rejected because it versions files, not rows.
- **Branching:** a sandbox is its own SQLite file, a `VACUUM INTO` snapshot of `main` (or of another sandbox) with its own change log. Later edits to `main` cannot leak into it. The overlay alternative (changed rows plus tombstones over an attached base) was rejected because SQLite refuses an upsert on a view and every view would need a base-version filter. A content-addressed blob sharing optimization is triggered only if measured criteria are missed (design section 6.4).
- **Merge:** new `sandbox merge` with row-level three-way detection on `files` and `entities` (ancestor from the sandbox change log, base from `main` now plus `main`'s change log since `base_seq`, branch from the sandbox now). Derived tables are rebuilt, not merged. Policy `fail` (default), `branch-wins` or `base-wins`. One transaction. A JSON and text conflict report with hashes, never raw content.
- **History:** run history, timeline and rewind already read run-state tables and port unchanged into `runstate.db`. The append-only change log replaces Dolt commit history, `dolt_diff`, `dolt_status` and hydrate commits (`hydrate_generation`).
- **Concurrency:** WAL on every file, `busy_timeout=5000`, `BEGIN IMMEDIATE` for writes, 3 retries with jitter, hydrate and extract serialized by a file lock, leases as held `FileChannel` locks (the OS releases them when a process dies). Windows: close pools and wait up to 15 s for `.db`, `-wal` and `-shm` to be released before deleting.
- **Removed:** the Dolt server, supervisor, installer, release catalog, MariaDB driver, CI install and cache steps. `dolt install` and `dolt server …` become aliases that print the embedded line and exit 0.
- **Naming:** keep `pickleball dolt`, `pkb_dolt*`, `dolt.properties`, `dolt-entities/`, Gradle `dolt*`, MCP `dolt` and `.pickleball/dolt/` through E6. E7 renames to `store` with aliases for 3.0.x, **only if Dan approves**. Never reintroduce the pre-Dolt mis-transcribed name; leave `RenameGateTest`, `LegacyNames` and `scripts/rename-history-allowlist.txt` as they are.
- **Migration:** from an existing Dolt store, rehydrate from files, and import DB-only edits, sandboxes and run-state through an already-installed Dolt binary's `dolt sql -r json` (never download). From the pre-Dolt SQLite working copy, keep today's rules.
- **Out of scope:** the diagnostic-report SQLite index and its "SQLite index parity" CI; the held execution-path audit fixes F1 to F12.

## Read first, in order

1. `AGENTS.md` (whole file; `## Dolt`, "One spine, multiple adapters", "Public contracts").
2. `docs/maintainer/3.0.0-embedded-store-design.md`: every decision, the evidence, and section 17 (verify in E1; write answers back).
3. `docs/roadmaps/3.0.0-embedded-store-roadmap.md`: phases, functionality, docs, named tests, port tables, and the scoreboard you fill.
4. `docs/maintainer/3.0.0-dolt-core-design.md` (sections 3 to 13: store seam, dialect table, sandboxes, concurrency, logging, adapters), `docs/maintainer/3.0.0-dolt-working-copy-design.md`, `docs/maintainer/3.0.0-linked-tracking-design.md`, `docs/maintainer/dolt-isolation-analysis-2026-11-15.md` (SQLite facts from before Dolt).
5. `docs/configuration.md` (Dolt part), `docs/dolt-entities.md`, `docs/agent/feature-map.md`, `docs/release-notes.md`.
6. The source: `src/main/java/tools/dscode/dolt/**` (start with `store/DoltStore.java`, `DoltSession.java`, `DoltConnections.java`, `DoltDialect.java`, `Binding.java`, then `DoltSandbox.java`, `DoltHydrator.java`, `DoltMigrate.java`, `DoltCli.java`), the `dolt*` Gradle tasks in `build.gradle`, `.github/workflows/agent-contract.yml`, and the Workbench `DoltCliForwarder`.

Branch docs win over this prompt on repo facts. Dan's decision above wins over any older recommendation.

## Hard rules (every phase)

- NEVER commit, push or merge to `master`, `2.2.0` or `2.1.19`. No tags, releases, publishing or force-push. Never run `./gradlew --stop`.
- Keep Gherkin syntax unchanged. Cucumber step annotations stay single string literals with identical values.
- Never weaken, skip or delete tests. A Dolt-only assertion is replaced by its embedded equivalent and listed in the phase's port table and scoreboard notes.
- CLI-first: Workbench, MCP and Gradle only forward to the CLI. The Workbench never opens a database or loads core store classes.
- No Cucumber types or syntax in `tools.dscode.dolt` (or `tools.dscode.store` after E7).
- Opening and using the store starts no process, opens no socket and downloads nothing. No new dependency.
- Logging for new code: toggleable, per-entity, ISO date-stamped, size-capped (`pkb_dolt_log`, `pkb_dolt_log_entity`, `pkb_dolt_log_max_bytes`).
- No secrets in cleartext anywhere: rows, change logs, logs, diffs, merge reports, sandbox dumps, commits, prompts.
- Docs are rewritten IN PLACE to describe what is implemented at each commit; history goes to `docs/release-notes.md`. Never replace or truncate `AGENTS.md` or `docs/agent/feature-map.md` wholesale; edit only changed paragraphs and compare `wc -c` before each push. Keep the `AGENTS.md` lines `verify_agent_contract.py` checks.
- Regenerate `docs/agent/repository-index.md` only with `python3 scripts/refresh_agent_index.py`. Before every push: `python3 scripts/refresh_agent_index.py --check`, `python3 scripts/verify_agent_contract.py`, `python3 scripts/sync_consumer_guidance.py --check`. Remove `scripts/__pycache__`.
- Exported guidance docs must not link to `docs/maintainer/`, `docs/roadmaps/` or `prompts/` paths; name them in words.
- In current-behaviour docs, do not put the word "opt-in" near "Dolt" (`CurrentDocsTest`).
- Never write placeholder or 0-byte files.
- Do not open Grok chats or other paid assistant sessions for this work.
- Push every green phase: `git fetch origin`, `git pull --rebase origin 3.0.0`, then `git push origin HEAD:3.0.0`. Never force. Check the Agent Contract CI run for that push and record it.

## Tests

- After each phase run ONLY the targeted tests the roadmap names for it: `timeout 600 ./gradlew --no-daemon <module>:cleanTest <module>:test --tests <filter>`.
- HELD until Dan asks: the full multi-module regression, `validate --all`, first-start timing, consumer-sim, and Maven `@all`. Do not run them.
- Timing tests print their numbers and assert only a ceiling about ten times the design target.
- A failed targeted test stops the ladder until it is fixed honestly.

## Phases (details in the roadmap)

- **E1** Spike and verification with `sqlite-jdbc` on Linux, Windows and macOS CI; record Dolt baselines; fill design sections 14, 15 and 17.
- **E2** SQLite backend behind `DoltStore`; dialect port back to SQLite; run-state router; `store_schema=1`; change-log table and generated triggers.
- **E3** Core on the embedded store by default; hydrate generations; migration from Dolt stores and the pre-Dolt SQLite copy.
- **E4** Sandboxes as snapshot files: create, list, use, diff, extract, drop, prune; leases; Windows release wait.
- **E5** `sandbox merge` with policies and the conflict report; `changes` command and retention; forwarding.
- **E6** Remove the Dolt server, installer and MariaDB driver; aliases for `install` and `server`; CI without Dolt; store-open timing.
- **E7** Rename to `store` with aliases, only if Dan approves.
- **E8** Final docs and measurements.

## Completion

Every phase except E7 is mandatory once started. Do not stop early, do not skip phases, and do not mark anything optional, deferred or "follow-up" unless the roadmap says so. Stop only on an honest test failure you cannot fix, a hard-rule violation, or a real blocker. Then report the blocker with what you tried.

After each green phase: fill that phase's row in the roadmap scoreboard (status, full SHA, CI runs, test commands with counts, notes including any replaced assertions), commit, push to `3.0.0`, and continue to the next phase in the same session unless you are near your context limit, in which case say exactly where to resume.

## Every reply ends with a scoreboard

```
Start SHA: <sha>   Current tip: <sha>
Phases done: E1 ✅ E2 … (status per phase)
Tests: <class> <run>/<failed> … per phase
Commits: <phase> <sha> …
CI: <Agent Contract run> / <SQLite parity run>
Blocker: none | <what, where, what you tried>
```
