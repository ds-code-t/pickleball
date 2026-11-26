# Grok Build prompt: pickleball 3.0.0 spine R1

You are a fresh headless Grok Build session on Dan's Windows PC. You have not seen the planning chat. Implement R1 only, in full, from the files named here.

- **Repo:** https://github.com/ds-code-t/pickleball
- **Branch:** `3.0.0-spine` only. Never `master`, never a 2.x branch, never `3.0.0`. No tags, releases, publishing, or force-push.
- **Roadmap (the spec):** `docs/roadmaps/3.0.0-spine/R1-core-hydration-spine-universal-entity.md`
- **Design (decisions are closed):** `docs/plans/2026-11-25-spine-design.md`
- **Index:** `docs/roadmaps/3.0.0-spine/README.md`
- **Scoreboard:** `docs/maintainer/3.0.0-spine/R1-scoreboard.md`
- **Toolchain:** JDK 21. Windows commands. `.\gradlew.bat`. Do not use `./gradlew`. Never run `.\gradlew.bat --stop` or `./gradlew --stop`.

## Read first

1. `AGENTS.md` (Store, public contracts, build and validation, change boundaries).
2. The design doc, sections on conflicts, schema, and decisions. Do not reopen them.
3. The R1 roadmap, every phase.
4. `src/main/java/tools/dscode/store/sql/SqlDialect.java`, `StoreCli.java`, `StoreHydrator.java`, `StoreSandbox.java`, `GherkinRewrite.java`.

The roadmap names the classes, verbs, tables, columns, and test classes. Use those names. Do not invent a parallel set.

## Hard rules (every phase)

- Every phase is mandatory and must be completed in full. Nothing is optional or deferred. Do not start R2.
- Each phase runs targeted tests only, the Gradle command in that phase. The full regression runs at the end of R8, not here.
- Testing is on Windows only. Append any new Windows-only class to the Linux and macOS section of `docs/tech-debt.md`. Do not delete that section. Do not run Linux or macOS.
- Never weaken, skip, or delete a test. Keep Gherkin syntax. Step annotations stay single string literals.
- `store_schema` stays 1. The database stays under `.pickleball/store/`. Do not move it to `target/` or `build/`.
- Do not change `pkb_callpath` or `pkb_componentpath` path grammar.
- `--mode=copy` sandboxes stay independent. Default create becomes `cow` only as the roadmap says. `SandboxIndependenceTest` keeps passing for copy mode.
- No secrets in cleartext in rows, logs, discards, or commits.
- Docs describe only the behavior this phase implemented. Rewrite them in place. History goes in `docs/release-notes.md`. Do not replace `AGENTS.md` wholesale.
- Before every push, each command on its own:

```
python scripts/sync_consumer_guidance.py
python scripts/refresh_agent_index.py
```

If surface-asset bytes change, update the hash assertion in `tools.dscode.browser.SurfaceAssetsTest`. R1 does not change those bytes.

- Record the implementation SHA, the test command, and the pass count on `docs/maintainer/3.0.0-spine/R1-scoreboard.md` after each green phase, in the same commit as the code or in the immediately following commit before the push.
- Fetch and rebase before every push. Each git command stands alone. Do not chain with `&&`, `;`, or a pipe. Never force-push. Never push to `master`.

```
git fetch origin 3.0.0-spine
git rebase origin/3.0.0-spine
git push origin HEAD:3.0.0-spine
```

- Stop only on an honest test failure you cannot fix without weakening a test, or on a hard-rule violation. Report what you ran and what failed. Do not mark a phase done when its tests were not run.

## CLI-first and logging

Every new capability is a `store` subcommand on `StoreCli`. Workbench `StoreCliForwarder`, Gradle, and MCP only forward. They do not open SQLite. Log through `StoreLog` with capabilities `pin`, `delta`, `enable`, and `conflict`. Off unless `pkb_store_log` names them. Date-stamped, entity filter, 1 MiB cap, sandbox name on the line.

## Phases

Implement P1, then P2, then P3, then P4, in that order. The roadmap is the acceptance criteria: classes, DDL, CLI help, docs, and the exact test command.

- **P1.** `UniversalEntity`, `DeltaLog`, `HeadMaterializer`. Tables `pins`, `deltas`, `heads`, `head_rows`. Undo appends an inverse delta. Window 100.
- **P2.** `StoreHashes.jarHash`. Enabled columns, `folders`, `scenario_tags`, `run_variables`, `@pkb-ignore` through `GherkinRewrite`. JAR-hash wipe does not delete `runstate.db`.
- **P3.** `StoreConflictChoice`. `store conflicts show` and `choose`. Discard directory. No merge library.
- **P4.** `CowSandbox`. `--mode=cow|copy`, default cow. `store sandbox promote` requires `--logs=clear|archive`.

## Finish

You are done when P4 is green, the scoreboard has four SHAs, and the branch is pushed. Reply with the scoreboard table and the tip SHA.
