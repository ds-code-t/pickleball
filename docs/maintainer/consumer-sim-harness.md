# Consumer-simulation harness

`scripts/consumer_sim.py` is the end-to-end agent session. It installs nothing by itself. Publish first (`./gradlew publishToMavenLocal` or `scripts/consumer_sim.py --publish`), then run it from the repo root with the consumer project as the process working directory inside the script.

The session, in order:

1. `discover` on `maven-consumer-project` before `target/test-classes` exists (`sim-uncompiled`).
2. `mvnw test-compile` (or `mvnw.cmd` on Windows).
3. `workspace-seed --reseed`, then `discover` of `@log-assert-steps` / `TRACE.DEBUG.INFO` (`sim-compiled`). This discover must exit 0 with `summary status=PASSED`.
4. `isolate` on that run id.
5. `execute-step --text-b64` and `execute-step --text-file=<absolute path>`.
6. `events`, `query errors`, `query tail`.
7. `discover` again on the same run id, then `confirm`, then `finish`.
8. Report-7 symptoms (`report7()`): multiple run ids, regex and Cucumber-expression glue, a blank `pkb_glue`, validate over 2000 steps, `--quiet` line budget, `failedScenarios` path form, status right after `isolate`, a start whose `startup-progress.json` must not keep a stage left in an older log, the newest log, or the previous progress file, and an index-end to jvm gap on a large cache-hit classpath (40 jars, 64 MiB each) gated at 1500ms. The same block then measures that gap on every back-to-back restart, rejects a commented-out `// @And` as a second definition, requires an ambiguous line to name each conflicting class, method, and pattern, gates `validate --all` `elapsedMs` on a generated suite (2000 patterns, 8000 steps, budget 1500ms, progress still printed), and requires `--quiet` to keep `summary` and `fix:` while dropping `WARN:` / `WARNING:` seed lines. It then checks the report-8 gaps: a cold start, a Java edit plus `test-compile`, a resource edit, a removed manifest, and a `discover` between restarts, each index-end to jvm gap within 1500ms with `index-end` present and materialize legs naming `sync`, `materialize`, `hash` (`hashed=0`), and `maven skipped=unchanged`; `^items?`, `colou?r`, `cats*`, and `|` must not be reported undefined; glue whose superclass lives only in a dependency jar, repeated `@Given`s, and `io.cucumber.java.fr.Et` must register; a class file older than its source must warn.
9. `stop --force` so the worker does not stay up.

`python3 scripts/consumer_sim.py --report7-only` runs just that report-7 block (publish first). It exits 1 when any item fails. The `failedScenarios` check reads the summary line only: `pkb_run_profile:` contains the letters `file:` and is not a URI.

Each stage prints `stage=<name> exit=<code> seconds=<n>` and must finish inside the budget in the script. `record.json` for the compiled run must be `PASSED` and `finish` must set `stoppedAt`. Timings are also written to `maven-consumer-project/.pickleball/consumer-sim-timings.json`.

Push and pull-request CI runs the harness only on Windows, in `.github/workflows/agent-contract.yml` (job `windows`). That job also checks the repository index and the agent contract, then `publishToMavenLocal -x test` and `scripts/consumer_sim.ps1` (PowerShell, `mvnw.cmd`). Job `gate` depends only on `windows`. There is no Linux or macOS CI job. Linux is verified locally on the maintainer box with JDK 21 (`/opt/jdk/jdk-21.0.13+9` in the sandbox, `/home/box/jdk/jdk-21.0.13+9` on the maintainer machine). macOS testing is tech debt. See `docs/maintainer/tech-debt.md`.

A normal `mvn test` does not run this script.
