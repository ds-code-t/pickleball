# Dolt isolation analysis (2026-11-15)

**Analyzed SHA:** `ca811fe8791c5054627a08f1142107bccdcaf3e2` (`origin/3.0.0`, "AGENTS: record one-spine, multiple-adapters execution philosophy")
**Kind:** read-only analysis. This file gives findings and recommendations. It does not describe implemented behavior, and nothing here changes product code. Where this file and `AGENTS.md` or the guides disagree about behavior, the guides and the code win. The decision is the maintainer's.
**Question:** should Dalton's working copy be replaced by, or backed by, [Dolt](https://www.dolthub.com/), so an agent or test can branch, change a Java step definition, a feature file, or data, and run a closed project state without touching other agents? Git stays the source of truth and the only thing synced to GitHub.
**Method:** I read `AGENTS.md` (Dalton, Execution engine, One spine), `docs/maintainer/3.0.0-dalton-design.md`, `docs/maintainer/3.0.0-linked-tracking-design.md`, `docs/maintainer/execution-path-audit-2026-11-15.md`, `docs/configuration.md` (Dalton), and `docs/dalton-entities.md`, then traced the Dalton source. Dolt facts come from docs.dolthub.com and github.com/dolthub. I also ran Dolt 2.7.3 on a scratch Linux box, outside the repo, to check dialect and branch behavior (section 5). No repo build or test was run. Line numbers are at the analyzed SHA.

## 1. Short answers

| # | Question | Answer |
|---|---|---|
| A1 | Is Dolt a good fit for this isolation goal? | **Not as the first step.** Dolt branches are cheap and isolate rows well, but the database holds only part of what a run uses. Java glue is compiled from disk and loaded by a JVM that keeps global state. Dolt cannot isolate that. A per-agent project root (a Git worktree) already isolates everything Dalton keys on, with no new dependency. |
| A2 | What would a Dolt-backed Dalton look like? | A storage seam in `DaltonDatabase`, a MySQL dialect port of about 114 SQL statements, a loopback `dolt sql-server` per project started on first open, and a `hydrate` that ends with a Dolt commit so branches have a base. Section 6. |
| A3 | Isolation model? | One Dolt branch per sandbox, bound per connection (`dalton/<branch>`), one warm worker JVM per sandbox, branches dropped by name or TTL. Section 7. |
| A4 | Java step-definition changes? | They need source on disk or an in-process compile plus a class directory first on the classpath, and a separate JVM. `StepRegistry.scanPackage` only reads `file:` and `jar:` resources. A Dolt branch does not remove any of that. Section 8. |
| A5 | Cheapest equal isolation? | A Git worktree per agent, each with its own `dalton.db`, `target/`/`build/`, and warm worker. That works today because every Dalton path derives from the project root. Section 9. |
| A6 | Effort? | Worktree sandbox helper: about 1–3 days. Optional Dolt backend with sandboxes and Java overlay: about 25–40 engineer-days, plus CI for a ~126 MB binary on three OSes. Section 10. |

## 2. What Dalton holds today, and what it does not

- **Storage.** One SQLite file per project, `DaltonPaths.database(project)` = `<project>/.pickleball/dalton/dalton.db` (`DaltonPaths.java:21-23`). The project is `pkb_dalton_project` or the working directory (`DaltonConfig.java:22-28`). `DaltonDatabase.open` always opens that file, takes a per-file lock (`OPEN_LOCKS`, `DaltonDatabase.java:27`, `:53-60`), sets WAL, `busy_timeout=5000`, and `foreign_keys=ON` (`:92-101`), and runs `migrate()` on every open (`:103-303`). The driver is loaded by name, `org.sqlite.JDBC` (`:22`, `:34-41`).
- **What hydrate copies.** Features, configs, calls, components, templates, resource maps, property and profile files, and `dalton-entities/` (`DaltonSources.java:15-38`). Java and JS glue are not copied as files. Hydrate only catalogs step patterns from them into `step_definitions` (`AGENTS.md:119`, `DaltonStepCatalog.java:196`).
- **What execution reads from the database.** Feature bytes, through `FeaturePathLoadCache` (`FeaturePathLoadCache.java:88-96`, `:115`, `:258`, `:289`) and `DaltonFeatureSource.bytesFor` (`DaltonFeatureSource.java:21-40`), and entity lookups (`ParsingMap.java:347-348`, `:372-373`, `:1223-1224`). The audit already notes no path executes the projected scenario and step rows (`execution-path-audit-2026-11-15.md:68`). Element categories are read from disk (`ProjectElementCategories.java:82-106`).
- **Java glue.** The warm path fingerprints `.java` and `.class` files on disk (`DaltonRun.java:127-136`), runs `mvnw -q test-compile` or `gradlew testClasses --no-daemon` on change (`:141-151`), and restarts a worker JVM (`:211-235`) whose classpath comes from `pkb_dalton_run_classpath` or the Workbench `classpath.cache` (`:185-209`). The worker listens on an ephemeral loopback port (`DaltonRunWorker.java:39`).
- **A gap that matters for any DB-first plan.** With Dalton on, `dalton edit glue put` on a `.java` path writes the source into the `files` table, not to disk (`DaltonEdit.java:233-251` → `DaltonContent.put`). The fingerprint reads disk, so `GlueRules.afterWrite` reports `glue fingerprint unchanged` and nothing compiles until `dalton extract` (`GlueRules.java:40-62`). This is read from code, not run.
- **JS/TS glue.** Source only. There is no JS/TS step runtime (`DaltonStepCatalog.java:23`, `GlueRules.java:42-43`).
- **JVM-global state.** The run id is a system property (`DaltonExecution.java:274`, `:333-349`), the project is a system property, and auto-hydrate is a static flag (`DaltonRuntime.java:10`). Two sandboxes cannot safely share one JVM, whatever the storage.
- **Isolation that already exists.** The database, the warm worker state, the fingerprint and the classpath cache all live under the project root (`DaltonRun.java:350-352`, `:194`). Dalton tests already isolate this way: 29 test classes in `src/test/java/tools/dscode/dalton` (and 3 more Dalton tests elsewhere) give each test its own `@TempDir` project.

## 3. SQLite surface

A scan of string literals in `tools.dscode.dalton` and `DaltonScenarioSource` finds about 114 SQL statements in 15 files: 46 SELECT, 19 INSERT, 18 DELETE, 16 CREATE TABLE, 9 UPDATE, 5 PRAGMA, 1 ALTER. `DaltonDatabase` has 29, `DaltonExecution` 13, `DaltonHistory` 12, `DaltonGherkin` 9, `DaltonEntities` 8, `DaltonCli`, `DaltonStepCatalog` and `DaltonTrack` 7 each. Twelve explicit transactions use `setAutoCommit(false)` (for example `DaltonHydrator.java:74-147`, `DaltonContent.java:44-55`). Nineteen main-source files touch Dalton JDBC.

| Construct | Where | On Dolt 2.7.3 (section 5) | Port |
|---|---|---|---|
| `TEXT PRIMARY KEY` | 7 tables, e.g. `DaltonDatabase.java:107`, `:113`, `:192`, `:214` | Fails: key without length | `VARCHAR(n)` keys |
| `BLOB` for `files.raw_content` | `DaltonDatabase.java:115` | 70,000-byte insert fails; `max-file-size` default is 1 MiB | `LONGBLOB` |
| `INTEGER PRIMARY KEY AUTOINCREMENT` | `DaltonDatabase.java:204`, `:254` | Syntax error | `AUTO_INCREMENT` |
| `INSERT ... ON CONFLICT ... DO UPDATE` with `excluded.` | 10 sites: `DaltonArtifacts.java:58`, `DaltonDatabase.java:298`, `:300`, `DaltonEntities.java:405`, `DaltonExecution.java:411`, `:435`, `:467`, `DaltonFiles.java:70`, `DaltonHistory.java:98`, `DaltonStepCatalog.java:78` | Syntax error | `ON DUPLICATE KEY UPDATE` |
| `INSERT OR IGNORE` | `DaltonDatabase.java:325`, `DaltonHistory.java:170` | Syntax error | `INSERT IGNORE` |
| `last_insert_rowid()` | `DaltonEntities.java:104`, `DaltonHistory.java:126` | Not found | `LAST_INSERT_ID()` or generated keys |
| `PRAGMA` (journal, busy, foreign keys, `table_info`) | `DaltonDatabase.java:87`, `:94`, `:98-99`, `:351` | Syntax error | server config, `information_schema.columns` |
| `\|\|` string concat | 10 lines in `DaltonTrack.java:209-278` | **Runs and returns 0** (logical OR) | `CONCAT()` or `PIPES_AS_CONCAT` mode |
| `COLLATE NOCASE` | `DaltonTrack.java:181` | Unknown collation | case-insensitive collation or `LOWER()` |
| `CAST(... AS INTEGER)` | `DaltonTrack.java:272-273`, `:279-280` | Syntax error | `CAST(... AS SIGNED)` |
| `instr()`, `LIKE ... ESCAPE '\\'`, `LIMIT ? OFFSET ?`, `CHECK (id = 1)` | `DaltonTrack.java:177`, `:198-232`, `:258`, `DaltonDatabase.java:125` | Work | none |
| `jdbc:sqlite:` URL, driver check, `MISSING_DRIVER` text | `DaltonDatabase.java:19-22`, `:63-66` | n/a | new URL and message |

The `||` case is the dangerous one. It does not fail. It silently changes tag and feature filters in `dalton query` and `dalton timeline`.

## 4. Dolt facts (sourced)

- **Connection model.** Dolt "ships with a built in MySQL compatible server", started with `dolt sql-server`, default port 3306 ([SQL clients](https://docs.dolthub.com/sql-reference/supported-clients/clients), [server configuration](https://docs.dolthub.com/sql-reference/server/configuration)). Java uses standard MySQL drivers: `mysql-connector-j`, `mariadb-java-client`, `r2dbc-mariadb` (same SQL clients page). The only embedded driver is for Go: "embedding Dolt inside a Go application ... without running a Dolt server process" ([dolthub/driver](https://github.com/dolthub/driver)). I found no JVM embedded engine.
- **Branches per connection.** "Each branch functions as its own isolated database instance, with changes only visible to other clients connected to the same branch." A client picks a branch with `mydb/feature-branch` in the connection string or ``USE `mydb/feature-branch` ``, and can address `` `mydb/branch`.table `` directly ([Using branches](https://docs.dolthub.com/sql-reference/version-control/branches)). A transaction may not commit changes to two branches at once (same page).
- **Procedures.** `DOLT_BRANCH`, `DOLT_CHECKOUT`, `DOLT_ADD`, `DOLT_COMMIT`, `DOLT_MERGE`, `DOLT_RESET`, `DOLT_GC` and others "modify state only for the current session, not for all clients" ([Dolt SQL procedures](https://docs.dolthub.com/sql-reference/version-control/dolt-sql-procedures)). In SQL, uncommitted working-set changes stay on the branch you modified after `DOLT_CHECKOUT` (same page). Deleting or renaming a branch in use by another session needs `--force` (same page). `DOLT_DIFF()` compares a table between two commits ([Dolt SQL functions](https://docs.dolthub.com/sql-reference/version-control/dolt-sql-functions)). `SELECT ... AS OF 'branch'` reads past revisions ([Querying history](https://docs.dolthub.com/sql-reference/version-control/querying-history)).
- **Auto increment.** On one server Dolt keeps a global `auto_increment` counter for all branches ([Dolt pro tips](https://www.dolthub.com/blog/2024-11-20-pro-tips/), [issue 3373](https://github.com/dolthub/dolt/issues/3373)).
- **Install and size.** "Dolt is a single ~100 megabyte program" ([Installation](https://docs.dolthub.com/introduction/installation)). The v2.7.3 release (2026-11-11) ships Linux, macOS (amd64, arm64) and Windows (`.msi`, `.zip`, `.7z`) assets of about 23–46 MB compressed ([GitHub API, latest release](https://api.github.com/repos/dolthub/dolt/releases/latest)). The unpacked Linux binary is 125,762,536 bytes (measured).
- **License.** Dolt, the Go driver and DoltgreSQL are Apache-2.0 ([dolthub/dolt](https://github.com/dolthub/dolt), [dolthub/driver](https://github.com/dolthub/driver), [dolthub/doltgresql](https://github.com/dolthub/doltgresql)). The Java drivers are not: `mysql-connector-j` is "GPL v2 with Universal FOSS Exception" and `mariadb-java-client` is LGPL-2.1-or-later (their Maven Central POMs). Pickleball is Apache-2.0, so the driver choice needs a license review.
- **Performance.** DoltHub's own sysbench page shows Dolt 2.7.3 at a 0.82 read and 0.64 write latency multiple of MySQL, and 71 versus 97 TPC-C TPS ([Latency](https://docs.dolthub.com/sql-reference/benchmarks/latency)). I found no official comparison with SQLite.
- **SQL modes.** Dolt supports 4 of 18 MySQL SQL modes, including `PIPES_AS_CONCAT` and `ANSI_QUOTES` ([SQL modes](https://docs.dolthub.com/sql-reference/sql-support/sql-modes)).
- **DoltgreSQL.** A PostgreSQL-compatible Dolt. Its README says "Doltgres is 1.0" and "ready for your production use case" ([doltgresql README](https://github.com/dolthub/doltgresql)). It is also a Go server process, so it changes the dialect, not the process model. SQLite's dialect is not closer to either in a way that matters here.

## 5. Scratch check on Dolt 2.7.3 (outside the repo)

On the box (Linux x86_64), `dolt init` and `dolt sql-server --host 127.0.0.1 --port 3399 --data-dir .`, then Python `pymysql`:

- The server accepted connections about 0.1 s after start. Resident memory after the test was about 143 MB.
- The constructs in section 3 behaved as the table says.
- After one `DOLT_COMMIT`, 20 branches took 28 ms to create. Twenty threads, each connected to `dalton/s-<n>`, updated the same feature row and inserted 20 log rows. All 20 saw only their own row text and their own 20 log rows. `active_branch()` matched. `main` still saw the base text and 0 log rows. A qualified read of another branch saw its uncommitted working set. `dolt_diff('main','s-3','feats')` showed the committed change. Log ids were unique across branches (1–279). Deleting the 20 branches took 17 ms.
- For comparison, a real 1.4 MB consumer `dalton.db` (76 files) copied in 1–3 ms and `VACUUM INTO` took 6–13 ms. `git worktree add --detach` of this repo took 276 ms for a 22 MB checkout.

These are single-machine spot checks, not benchmarks.

## 6. What a Dolt-backed Dalton would look like

1. **Storage seam.** Put a small `DaltonStore` interface behind `DaltonDatabase.open` with two implementations, SQLite (default) and Dolt (opt-in, for example `dalton.properties` `backend=dolt` or `pkb_dalton_backend`). Move dialect differences into one `DaltonDialect` (DDL, upsert, insert-ignore, generated id, case-insensitive compare, integer cast, concat, column introspection). Callers keep plain JDBC.
2. **Dialect port.** Section 3, plus `migrate()` (`DaltonDatabase.java:103-303`) rewritten so `addColumn` uses `information_schema.columns` instead of `PRAGMA table_info` (`:342-357`). Pin `PIPES_AS_CONCAT` or replace `||` with `CONCAT()`; replacing is safer.
3. **Server lifecycle.** Dalton owns it, like the warm worker. The data directory is `<project>/.pickleball/dalton/dolt/` (already gitignored through `.pickleball/`). The first open runs `dolt init` if needed, starts `dolt sql-server` on loopback with an ephemeral port, writes pid and port to a state file, and holds a lock so two processes do not start two servers. `dalton stop` and an idle timeout stop it. The binary comes from `pkb_dalton_dolt_bin` or `PATH`. It is not shaded into the jar. Without it, fail loudly, matching the `MISSING_DRIVER` rule (`3.0.0-dalton-design.md:132-135`).
4. **Connections.** `DaltonFeatureSource` opens a database on every call (`DaltonFeatureSource.java:27`, audit F3). Over TCP that needs a per-JVM pool or the audit's snapshot cache first.
5. **Fresh clone and CI.** Unchanged in principle: files are the source of truth, the database is rebuilt. Hydrate ends with `DOLT_COMMIT('-A', '-m', 'hydrate <git sha>')` because new branches start from a commit, not a working set. CI keeps SQLite (or file-only) unless a job opts into Dolt and installs the binary (`install.sh` on Linux and macOS, `.msi` or `.zip` on Windows).

## 7. Isolation model with Dolt

- **One branch per sandbox**, named like `sbx/<agent>/<utc-stamp>`, created from the latest hydrate commit.
- **Binding.** A sandbox name in `pkb_dalton_branch` (or `--branch` on the CLI) makes every Dalton connection in that process connect to `dalton/<branch>`. One sandbox per JVM, because of the global state in section 2. `dalton run` keys its worker state by project and branch, so each sandbox gets its own worker.
- **Edits and runs.** `dalton feature put`, `entity put`, and history writes land on the sandbox branch. Other agents and `main` never see them.
- **Promotion.** `dalton extract` from the sandbox writes files, and Git takes it from there. `DOLT_MERGE` into `main` is possible but not needed, since Git is the source of truth.
- **Cleanup.** `dalton sandbox drop <name>` runs `DOLT_BRANCH('-D', ...)`. A TTL sweep drops stale `sbx/` branches, then `DOLT_GC`. A branch still in use needs `--force`, which disconnects that session.
- **Open question.** Run history written on a sandbox disappears when the branch is dropped. Either accept that or write history to a non-versioned store. I did not verify how Dolt's `dolt_ignore` interacts with branches.

## 8. Java and JS/TS glue

Compiling and loading Java glue needs source the compiler can read and classes a JVM can load. Neither lives in a Dolt branch. The options:

1. **Workspace per sandbox (recommended).** Check the sandbox out to its own directory (a Git worktree, or extract the branch into a temp project root) and use the existing path: fingerprint, wrapper compile, worker restart (`DaltonRun.java:127-151`, `:211-235`). It is faithful to Maven or Gradle (annotation processors, plugins, resources). The cost is a first compile per workspace and a classpath cache per workspace, since `classpath.cache` holds absolute paths.
2. **Overlay compile.** Read changed `.java` rows from the sandbox, compile them with `javax.tools.JavaCompiler` against the cached classpath into `.pickleball/dalton/sandbox/<name>/classes`, and start that sandbox's worker with the directory first on the classpath. Limits: it needs a JDK at run time (`ToolProvider.getSystemJavaCompiler()` returns null on a JRE); it skips anything the build does besides plain `javac`; a deleted class cannot be hidden by prepending. A pure in-memory class loader is harder because `StepRegistry.scanPackage` uses `getResources` and only handles `file:` and `jar:` URLs (`StepRegistry.java:92-104`), so it would also have to serve those URLs.
3. **Fix the current gap first either way.** A DB-only `.java` edit is stored but never compiled (section 2). Either the fingerprint includes edited `.java` rows and compiles through option 1 or 2, or `glue put` on a `.java` path writes to disk.

**JS/TS:** nothing to compile today, since there is no runtime (`DaltonStepCatalog.java:23`). If a runtime arrives, it can evaluate sandbox bytes straight from the database.

## 9. Alternatives compared

| Option | Features and data | Java glue | Worker and `.pickleball/` state | New dependency | Effort | Notes |
|---|---|---|---|---|---|---|
| **Git worktree per agent** | Yes, own `dalton.db` | Yes, own sources and `target/`/`build/` | Yes, all under the project root | None | S (1–3 d: helper script, naming, cleanup, docs) | Works today. 276 ms and 22 MB per checkout here, plus one compile per worktree. Promotion is a normal Git branch and PR. |
| **SQLite copy per sandbox** (file copy, `VACUUM INTO`, or the driver's `backup to`) | Yes | No, unless paired with section 8 option 2 | Only if the copy sits in its own project root | None | S–M | 1–13 ms for a real 1.4 MB db. `DaltonPaths` derives the db from the project root, so this needs a temp root or a new `pkb_dalton_db` override. The xerial driver documents `backup to` and `restore from` ([USAGE.md](https://github.com/xerial/sqlite-jdbc/blob/master/USAGE.md)). |
| **SQLite session extension** | Records changesets, does not isolate | No | No | Native code; the xerial USAGE.md documents no session API | M | Useful for "what did this agent change", not for isolation ([session intro](https://www.sqlite.org/sessionintro.html)). |
| **Optional Dolt backend** | Yes, cheap branches, diff | No (section 8) | No, still one JVM per sandbox | Dolt binary (~126 MB), MySQL driver | L (25–40 d) | Adds diff and merge of DB rows, which Git already gives after extract. |
| **Replace SQLite with Dolt** | Yes | No | No | Same, mandatory | XL | Breaks the zero-config and fresh-clone story. `sqlite-jdbc` stays anyway for diagnostics and Workbench. |

## 10. Effort, risk, and what breaks (Dolt backend)

| Phase | Work | Size |
|---|---|---|
| P0 spike | Run the 114 statements and the Dalton test suite against Dolt behind a flag | 2–3 d |
| P1 seam and dialect | `DaltonStore`, `DaltonDialect`, section 3 rewrites, `migrate()` | 6–10 d |
| P2 server and packaging | lifecycle, lock, port file, pool, driver choice after license review, `MISSING_DRIVER` sibling, a packaging check next to `verifySqlitePackaging` (`build.gradle:609-652`) | 5–8 d |
| P3 sandboxes | `dalton sandbox create/list/drop/diff`, `pkb_dalton_branch`, worker per branch, TTL sweep | 4–6 d |
| P4 Java overlay | section 8 option 2 plus the gap fix | 4–8 d |
| P5 tests, docs, CI | backend-specific asserts, docs, an opt-in CI job with the binary on Linux, macOS, Windows | 4–6 d |

**What breaks or needs work:** `DaltonFoundationTest` asserts WAL and `busy_timeout` and reads `PRAGMA table_info` (`DaltonFoundationTest.java:33-34`, `:186`); 20 test files open the database directly; the Gradle `dalton*` and `engineRun` tasks add only `sqlite-jdbc` (`build.gradle:446-575`); `time_dalton_run.py`; consumer guidance and the configuration doc (`configuration.md:664`). **Not affected:** the Workbench and MCP only forward to the CLI and never open SQLite (`AGENTS.md:114`, `:119`). The "SQLite index parity" workflow tests the diagnostic index, not Dalton (`.github/workflows/sqlite-index.yml:37-38`), and `sqlite-jdbc` stays because diagnostics and the Workbench use it (`pickleball-workbench/build.gradle:86`, `pickleball-control-protocol/build.gradle:20`).

**Risks:** silent `||` results; a second process to start, stop and clean up on Windows; port and lock races between agents on one project; 143 MB resident per server; a large binary for consumers; Java driver licensing; two backends to keep in parity.

## 11. Recommendation

1. **Do the worktree sandbox first.** A small `scripts/` helper or `dalton sandbox` command that runs `git worktree add` from a commit, regenerates that worktree's classpath cache, hydrates, and prints the path. Cleanup runs `git worktree remove`. Document that one agent equals one worktree. This gives full isolation for feature, data and Java changes today, and promotion is an ordinary Git branch.
2. **Fix the DB-only `.java` edit gap** (section 8, item 3), whatever happens with Dolt.
3. **Make a cheap DB-only sandbox optional:** a `pkb_dalton_db` override plus `backup to` or `VACUUM INTO`, for feature and data experiments that do not need a separate checkout.
4. **Hold Dolt** until there is a concrete need for row-level diff or merge between agents' database states that extract-plus-Git does not cover. If that day comes, add it as an optional backend (section 9), not a replacement, and port `||` first.

## 12. The name

"Dalton" probably came from a voice mis-transcription of "Dolt". Renaming touches about 3,055 lines in 178 files, 16 `pkb_dalton*` keys, 9 Gradle `dalton*` tasks, the `dalton` CLI, the Workbench tab and the package. Those are consumer-visible contracts (`AGENTS.md` "Public contracts"). If the backend stays SQLite, a rename buys clarity only. If it happens, keep the old CLI, property and task names as aliases for at least one release. Do not rename it to "Dolt", since that is a different product.

## 13. Not verified

- A JVM embedded Dolt engine: none found; only the Go driver is documented.
- Dolt versus SQLite latency for small databases: no official source. Section 5 times are spot checks.
- Whether MariaDB or MySQL JDBC URLs accept `dalton/<branch>` in the path. It worked with Python `pymysql` (`database='dalton/s-3'`). Java may need ``USE `dalton/<branch>` `` after connecting.
- `dolt_ignore` behavior for history tables across branches.
- Dolt server start time and memory on Windows and macOS. Measured only on Linux.
- Gradle and Maven behavior with many worktrees building in parallel on one machine (shared caches, daemons). Not measured.
- The DB-only `.java` gap in section 2 is read from code, not run.
