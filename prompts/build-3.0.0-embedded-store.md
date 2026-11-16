# Grok Build Prompt: Pickleball 3.0.0 Embedded Store

You are Grok Build. Replace Pickleball's Dolt sql-server layer with an embedded SQLite store, remove Dolt entirely, and rename `dolt` to `store`, phase by phase. This prompt is standalone; do not depend on chat history. Dan approved the build on 2026-11-16.

- **Repo:** https://github.com/ds-code-t/pickleball
- **Work branch:** create `3.0.0-embedded-store` from `origin/3.0.0` (`git fetch origin && git checkout -b 3.0.0-embedded-store origin/3.0.0`) and push only there. **Do not merge into `3.0.0`**; the orchestrator merges after verification. Record the start SHA.
- **Toolchain:** JDK 21, Gradle wrapper. Every Gradle call is `timeout 600 ./gradlew --no-daemon …`.

## Read first, in order

1. `AGENTS.md` (whole file; the `## Dolt` section, "One spine, multiple adapters", "Public contracts").
2. `docs/maintainer/3.0.0-embedded-store-design.md`: every decision, schema, rule and the section 16 items you answer in S1.
3. `docs/roadmaps/3.0.0-embedded-store-roadmap.md`: phases S1 to S9, named tests, port tables, the CLI-first and logging checklist, the scoreboard you fill, and the verification checklist.
4. The source: `src/main/java/tools/dscode/dolt/**` (start with `store/DoltStore.java`, `DoltSession.java`, `DoltConnections.java`, `DoltDialect.java`, `DoltStatements.java`, `Binding.java`, then `DoltSandbox.java`, `DoltHydrator.java`, `DoltDiscovery.java`, `DoltExtractor.java`, `DoltCli.java`), the `dolt*` tasks in `build.gradle`, `.github/workflows/agent-contract.yml`, and the Workbench `tools.dscode.workbench.dolt` package.

Branch docs win over this prompt on repo facts. Dan's decisions below win over any older doc.

## Dan's locked decisions

1. **Independent branches.** Every sandbox is a fully independent, self-contained SQLite copy (`VACUUM INTO` from `main.db`, always from `main`). No reference to a parent, `main` or any other sandbox: no lineage, no overlay, no central registry. It records only `_pkb_branch` (name, created_at, created_from_commit, dirty flag, owner, ttl, store_schema) and `_pkb_base` (per `files` and `entities` row: base hash, location, Git commit at creation). Run-state is project-level history in one shared `runstate.db` with a `sandbox` column; no branch holds a pointer to it.
2. **Large binaries are the only exception.** `pkb_store_binary_policy=skip|include` (default `skip`), `pkb_store_large_file_bytes` (default 262144; 0 = every binary), detection by extension and content sniff. A large binary whose bytes equal its Git blob is stored as a reference (`binary_ref=1`, `location`=path, hash, `git_blob`, `git_commit`, size, no bytes). Otherwise its bytes are stored. Read verifies the hash and falls back to `git cat-file blob <git_blob>`; if both fail, `error:` plus `fix:`. When a branch changes one, the bytes are copied into that branch's database (`binary_ref=0`, `location='db'`, `git_commit=''`) and it stops referencing.
3. **Remove Dolt entirely.** Server, supervisor, installer, platform and release catalog, `DoltJdbc`, `dolt-release.properties`, ports, download and checksum code, `GET_LOCK`/`PROCESSLIST`, the MariaDB JDBC driver (build, POM, notices), `install` and `server` commands, Gradle `doltInstall`/`doltServer`, CI Dolt cache/install/server steps, removed settings (`pkb_dolt_bin`, `_auto_install`, `_port`, `_server_start_ms`, `_server_idle_minutes`, `_server_log_max_bytes`, `_tool_cache`). No import or migration from Dolt, no aliases, no shims, no warnings for old settings. Delete the Dolt-only docs listed in design 12.1 (Git keeps history).
4. **Rename `dolt` → `store`, no aliases** (design section 11): `pickleball store`, `pkb_store*`/`PKB_STORE*`, `store.properties`, `store-entities/`, `.pickleball/store/`, `store.log`, `tools.dscode.store` and `tools.dscode.store.sql` (`Sql*` classes), every `Dolt*` class → `Store*`, Gradle `store*`, MCP `store`, Workbench Store tab and package, `docs/store-entities.md`, `scripts/time_store_run.py`. Old names are simply unknown.
5. **`sandbox merge <name> [--policy=fail|branch-wins|main-wins] [--dry-run] [--drop] [--json]`**: three-way hash compare of `_pkb_base` vs `main` now vs the branch now on `files` and `entities`; branch unchanged → skip; main unchanged → apply; equal → converged; else conflict. Default `fail`: nothing written, JSON plus text-table report saved to `sandboxes/<name>/merge-<ISO>.json`, exit 1. Derived tables rebuilt; one `BEGIN IMMEDIATE` transaction; re-baseline `_pkb_base` after success. Reports show hashes only.
6. **Keep:** `org.xerial:sqlite-jdbc:3.47.2.0` (no bump, no new dependency); `_pkb_changes` trigger change log plus `store changes` and `pkb_store_changes_days` (14); WAL, `busy_timeout=5000`, `BEGIN IMMEDIATE`, 3 retries with jitter; hydrate lock files; leases as held `FileChannel` locks; Windows 15 s release wait and `pending-delete`; sandboxes for parallel agents; Git as the only project sync; CLI-first; toggleable, per-capability, per-entity, ISO date-stamped, size-capped logging (`pkb_store_log`, `_log_entity`, `_log_max_bytes`); the opt-out `pkb_store=false` stays file-only; the pre-Dolt SQLite working-copy import (`store migrate`, `LegacySqlite`, `LegacyNames`).
7. **Leftover gate** (S9; both must print nothing):
   `git grep -niE 'dolt|mariadb' -- . ':(exclude)docs/release-notes.md' ':(exclude)docs/roadmaps/3.0.0-embedded-store-roadmap.md' ':(exclude)prompts/build-3.0.0-embedded-store.md'` and `git ls-files | grep -iE 'dolt|mariadb'`. Past release-note entries stay intact with one `Superseded:` line. Enforce it with `NoDoltLeftTest` (pattern built from pieces).

## Hard rules (every phase)

- NEVER commit, push or merge to `master`, `2.2.0`, `2.1.19` or `3.0.0`. No tags, releases, publishing or force-push. Never run `./gradlew --stop`.
- Keep Gherkin syntax unchanged. Cucumber step annotations stay single string literals with identical values.
- Never weaken, skip or delete tests. Renames keep tests. A test that exists only for Dolt-specific behavior is replaced by an equivalent store test from the roadmap port table, never just deleted. Record the Gradle test count in S1; it must not drop except by one-for-one replacements.
- Do not touch dom-composite PRs #2 and #3. Do not move the Cucumber step model out of Cucumber's package. Do not fix the held audit items F1 to F12.
- CLI-first: Workbench, MCP and Gradle only forward to the CLI. The Workbench never opens a database. No Cucumber types in the store packages.
- Opening and using the store starts no process, opens no socket, downloads nothing.
- No secrets in cleartext anywhere: rows, change log, branch tables, logs, reports, commits.
- Docs rewritten IN PLACE to match each commit; history in `docs/release-notes.md`. Never replace or truncate `AGENTS.md` or `docs/agent/feature-map.md` wholesale; compare `wc -c` before each push. No effort estimates.
- Before every push: `python3 scripts/refresh_agent_index.py` (regenerate), `python3 scripts/refresh_agent_index.py --check`, `python3 scripts/verify_agent_contract.py`, `python3 scripts/sync_consumer_guidance.py --check`; remove `scripts/__pycache__`. Exported guidance docs never link to `docs/maintainer/`, `docs/roadmaps/` or `prompts/`.
- Never write placeholder or 0-byte files. Do not open other paid assistant sessions.
- Small incremental pushes, at least one per phase: `git fetch origin`, `git pull --rebase origin 3.0.0-embedded-store`, `git push origin HEAD:3.0.0-embedded-store`. Never force. Check the Agent Contract and SQLite parity CI runs for each push and record them.

## Tests

- After each phase run ONLY its targeted tests: `timeout 600 ./gradlew --no-daemon <module>:cleanTest <module>:test --tests <filter>` (root `:`, Workbench `:pickleball-workbench`).
- HELD (do not run): the full multi-module regression, `validate --all`, first-start timing, consumer-sim, Maven `@all`.
- Timing tests print their numbers and assert only a ceiling about ten times the design target.
- A failed targeted test stops the ladder until it is fixed honestly. CI must be green on Linux, Windows and macOS.

## Phases (full detail in the roadmap; every phase mandatory, in order)

- **S1** First add `3.0.0-embedded-store` to `push.branches` in `.github/workflows/agent-contract.yml` and `sqlite-index.yml` (CI does not run on this branch otherwise). Spike: `sqlite-jdbc` facts on three OSes; fill design 15/16; record test count.
- **S2** SQLite backend behind the store seam; dialect port; run-state router; `store_schema=1`; change log, `_pkb_branch`, `_pkb_base`, `files.location`/`git_commit`.
- **S3** Core on the embedded store; hydrate generations; status line.
- **S4** Remove Dolt entirely (code, driver, CI, resources, docs).
- **S5** Rename `dolt` → `store` everywhere, no aliases.
- **S6** Large-file references.
- **S7** Independent sandboxes.
- **S8** `sandbox merge` and `store changes`; forwarding.
- **S9** `NoDoltLeftTest` and gate; design rewritten as-built; final docs; verification checklist.

## Completion

Every phase is mandatory. Do not stop early, do not skip or reorder phases, and do not mark anything optional, deferred, "follow-up" or "future work". Stop only on an honest test failure you cannot fix, a hard-rule violation, or a real blocker; report it with what you tried. After each green phase fill that phase's scoreboard row (status, full SHA, CI runs, test commands with counts, notes listing replaced tests), commit, push, and continue to the next phase in the same session. Only if you are near your context limit, say exactly where to resume. You are done only when S9's gate prints nothing and every roadmap verification checklist item is true.

## Every reply ends with a scoreboard

```
Start SHA: <sha>   Branch tip: <sha>   Branch: 3.0.0-embedded-store
Phases: S1 ✅|🔄|⬜ … S9 (status each)
Tests: <class> <run>/<failed> … (this phase) | total count <n> (S1 baseline <n>)
Commits: <phase> <sha> …
CI: <Agent Contract run> / <SQLite parity run> (status)
Blockers: none | <what, where, what you tried>
```
