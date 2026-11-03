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
8. `stop --force` so the worker does not stay up.

Each stage prints `stage=<name> exit=<code> seconds=<n>` and must finish inside the budget in the script. `record.json` for the compiled run must be `PASSED` and `finish` must set `stoppedAt`. Timings are also written to `maven-consumer-project/.pickleball/consumer-sim-timings.json`.

Linux CI runs the script under `xvfb-run` after `publishToMavenLocal` in `.github/workflows/agent-contract.yml` (job `validate`, which also runs the golden tests inside `gradlew test` and the full suite). The Windows harness is job `windows` in that same workflow. It uses PowerShell and `scripts/consumer_sim.ps1`, which runs the same Python harness and `mvnw.cmd`. Job `gate` fails unless `validate` and `windows` both succeed, so a release cannot go out on a green Linux job alone.

A normal `mvn test` does not run this script.
