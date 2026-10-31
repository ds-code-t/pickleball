#!/usr/bin/env python3
"""Consumer-simulation E2E harness for the Workbench CLI.

Drives maven-consumer-project the way an agent does: discover (before and
after test-compile), isolate, execute-step via base64 and an absolute
--text-file, events, query errors/tail, a rerun of the same run id, confirm,
and finish. Asserts exit codes, summary lines, and record.json. Prints a
timing line per stage and fails when a stage exceeds its budget.

Linux CI calls this script. The windows-latest job calls it from PowerShell.
Wrappers are absolute paths. On Windows a .cmd/.bat is started with cmd.exe
because CreateProcess does not run batch files and does not search the child cwd.
"""

from __future__ import annotations

import argparse
import base64
import json
import os
import shutil
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CONSUMER = ROOT / "maven-consumer-project"
RUN_UNCOMPILED = "sim-uncompiled"
RUN = "sim-compiled"
TAGS = "@log-assert-steps"
NAME = "TRACE.DEBUG.INFO"
STEP = 'INFO: harness "quoted" step'
# Generous CI budgets. They fail a hang or a large regression, not a warm laptop.
BUDGETS = {
    "discover-uncompiled": 900,
    "test-compile": 900,
    "workspace-seed": 180,
    "discover-compiled": 900,
    "isolate": 600,
    "execute-step-b64": 180,
    "execute-step-file": 180,
    "events": 60,
    "query-errors": 60,
    "query-tail": 60,
    "discover-rerun": 900,
    "confirm": 900,
    "finish": 60,
    "stop": 60,
}


def main() -> int:
    parser = argparse.ArgumentParser(description="Pickleball consumer-simulation harness")
    parser.add_argument("--publish", action="store_true", help="publish the framework to mavenLocal first")
    args = parser.parse_args()
    if args.publish:
        publish()
    configure_browser()
    timings: list[dict] = []
    try:
        prepare_tree()
        run_stage(timings, "discover-uncompiled", [
            "discover", "--run-id=" + RUN_UNCOMPILED, "--tags=" + TAGS, "--name=" + NAME,
            "--runvar=pkb_browser=CHROME_HEADLESS", "--retention=failed", "--agent=consumer-sim",
        ], allow_exit={0, 1})
        assert_record(RUN_UNCOMPILED)
        assert_summary_present(timings[-1])
        run_stage(timings, "test-compile", [], maven_args=["-q", "test-compile"], allow_exit={0})
        run_stage(timings, "workspace-seed", [
            "workspace-seed", "--run-id=" + RUN, "--reseed",
        ], allow_exit={0})
        compiled = run_stage(timings, "discover-compiled", [
            "discover", "--run-id=" + RUN, "--tags=" + TAGS, "--name=" + NAME,
            "--runvar=pkb_browser=CHROME_HEADLESS", "--retention=failed", "--agent=consumer-sim",
        ], allow_exit={0})
        assert_in(compiled, "summary status=PASSED")
        record = assert_record(RUN)
        assert_field(record, "status", "PASSED")
        run_stage(timings, "isolate", [
            "isolate", "--run-id=" + RUN, "--tags=" + TAGS, "--name=" + NAME,
            "--runvar=pkb_browser=CHROME_HEADLESS", "--agent=consumer-sim",
        ], allow_exit={0})
        b64 = base64.b64encode(STEP.encode("utf-8")).decode("ascii")
        stepped = run_stage(timings, "execute-step-b64", [
            "execute-step", "--run-id=" + RUN, "--text-b64=" + b64,
        ], allow_exit={0})
        assert_in(stepped, "ACK ")
        assert_in(stepped, "harness")
        step_file = CONSUMER / ".pickleball" / "consumer-sim-step.txt"
        step_file.parent.mkdir(parents=True, exist_ok=True)
        step_file.write_text(STEP + "\n", encoding="utf-8", newline="\n")
        filed = run_stage(timings, "execute-step-file", [
            "execute-step", "--run-id=" + RUN, "--text-file=" + step_file.resolve().as_posix(),
        ], allow_exit={0})
        assert_in(filed, "ACK ")
        assert_not_in(filed, STEP + "\r")
        events = run_stage(timings, "events", ["events", "--run-id=" + RUN], allow_exit={0})
        assert_true(events.stdout.strip() != "" or events.stderr.strip() != "", "events produced no output")
        run_stage(timings, "query-errors", ["query", "errors", "--run-id=" + RUN], allow_exit={0})
        run_stage(timings, "query-tail", ["query", "tail", "--run-id=" + RUN], allow_exit={0})
        rerun = run_stage(timings, "discover-rerun", [
            "discover", "--run-id=" + RUN, "--tags=" + TAGS, "--name=" + NAME,
            "--runvar=pkb_browser=CHROME_HEADLESS", "--retention=failed", "--agent=consumer-sim",
        ], allow_exit={0, 1})
        assert_in(rerun, "summary status=")
        confirm = run_stage(timings, "confirm", [
            "confirm", "--run-id=" + RUN, "--tags=" + TAGS, "--name=" + NAME,
            "--agent=consumer-sim",
        ], allow_exit={0, 1})
        assert_in(confirm, "summary status=")
        run_stage(timings, "finish", [
            "finish", "--run-id=" + RUN, "--status=PASSED", "--agent=consumer-sim",
        ], allow_exit={0})
        finished = assert_record(RUN)
        stopped = str(finished.get("stoppedAt") or "")
        assert_true(stopped.strip() != "", "finish did not set stoppedAt on " + RUN)
    finally:
        if (CONSUMER / ".pickleball" / "runs" / RUN).exists():
            try:
                run_stage(timings, "stop", ["stop", "--run-id=" + RUN, "--force"], allow_exit={0, 1, 2})
            except SystemExit:
                pass
        write_timings(timings)
    print("consumer-sim OK")
    for row in timings:
        print(f"timing stage={row['stage']} seconds={row['seconds']} exit={row['exit']}")
    return 0


def configure_browser() -> None:
    if os.environ.get("PKB_CHROME_BINARY") or os.environ.get("CHROME_BINARY"):
        return
    candidate = Path("/opt/pw-browsers/chromium_headless_shell-1248/chrome-headless-shell-linux64/chrome-headless-shell")
    if candidate.is_file():
        os.environ["PKB_CHROME_BINARY"] = str(candidate)
        os.environ["CHROME_BINARY"] = str(candidate)


def publish() -> None:
    script = ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew")
    cmd = script_command(script, ["publishToMavenLocal", "-x", "test", "--no-daemon"])
    print("publish: " + " ".join(cmd), flush=True)
    completed = subprocess.run(cmd, cwd=ROOT)
    if completed.returncode != 0:
        raise SystemExit(completed.returncode)


def prepare_tree() -> None:
    runs = CONSUMER / ".pickleball" / "runs"
    for run_id in (RUN_UNCOMPILED, RUN):
        shutil.rmtree(runs / run_id, ignore_errors=True)
    classes = CONSUMER / "target" / "test-classes"
    if classes.exists():
        shutil.rmtree(classes)


def run_stage(timings: list[dict], stage: str, launcher_args: list[str], allow_exit: set[int], maven_args: list[str] | None = None):
    budget = BUDGETS[stage]
    started = time.monotonic()
    if maven_args is None:
        result = launcher(launcher_args, budget + 30)
    else:
        result = maven(maven_args, budget + 30)
    seconds = round(time.monotonic() - started, 3)
    row = {
        "stage": stage,
        "seconds": seconds,
        "exit": result.returncode,
        "stdout": result.stdout,
        "stderr": result.stderr,
    }
    timings.append(row)
    print(f"stage={stage} exit={result.returncode} seconds={seconds}", flush=True)
    if result.returncode not in allow_exit:
        fail(stage, result, f"exit {result.returncode} not in {sorted(allow_exit)}")
    if seconds > budget:
        fail(stage, result, f"exceeded budget {budget}s")
    return result


def launcher(args: list[str], timeout: int) -> subprocess.CompletedProcess:
    exec_args = join_exec_args(args)
    return maven_cmd(script_command(wrapper_path(), [
        "-q",
        "org.codehaus.mojo:exec-maven-plugin:3.5.0:java",
        "-Dexec.mainClass=tools.dscode.launcher.PickleballWorkbenchLauncher",
        "-Dexec.classpathScope=test",
        "-Dexec.args=" + exec_args,
    ]), timeout)


def maven(args: list[str], timeout: int) -> subprocess.CompletedProcess:
    return maven_cmd(script_command(wrapper_path(), args), timeout)


def maven_cmd(cmd: list[str], timeout: int) -> subprocess.CompletedProcess:
    try:
        return subprocess.run(
            cmd,
            cwd=CONSUMER,
            text=True,
            encoding="utf-8",
            errors="replace",
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            timeout=timeout,
        )
    except subprocess.TimeoutExpired as failure:
        stdout = failure.stdout or ""
        stderr = failure.stderr or ""
        if isinstance(stdout, bytes):
            stdout = stdout.decode("utf-8", "replace")
        if isinstance(stderr, bytes):
            stderr = stderr.decode("utf-8", "replace")
        print(stdout[-4000:])
        print(stderr[-4000:], file=sys.stderr)
        raise SystemExit(f"timeout after {timeout}s: {' '.join(cmd[:4])}") from failure


def wrapper_path() -> Path:
    return CONSUMER / ("mvnw.cmd" if os.name == "nt" else "mvnw")


def script_command(script: Path, args: list[str]) -> list[str]:
    if os.name == "nt":
        comspec = os.environ.get("COMSPEC", "cmd.exe")
        return [comspec, "/d", "/c", str(script), *args]
    return [str(script), *args]


def join_exec_args(args: list[str]) -> str:
    quoted = []
    for arg in args:
        if any(ch.isspace() for ch in arg) or '"' in arg:
            quoted.append('"' + arg.replace('"', '\\"') + '"')
        else:
            quoted.append(arg)
    return " ".join(quoted)


def assert_record(run_id: str) -> dict:
    path = CONSUMER / ".pickleball" / "runs" / run_id / "record.json"
    assert_true(path.is_file(), "missing " + str(path))
    return json.loads(path.read_text(encoding="utf-8"))


def assert_field(record: dict, key: str, expected: str) -> None:
    actual = record.get(key)
    assert_true(actual == expected, f"record {key}={actual!r} expected {expected!r}")


def assert_summary_present(row: dict) -> None:
    text = row["stdout"] + "\n" + row["stderr"]
    assert_true("summary status=" in text or "WARNING:" in text or "error:" in text, "uncompiled discover had no summary, warning, or error")


def assert_in(result: subprocess.CompletedProcess, needle: str) -> None:
    text = result.stdout + "\n" + result.stderr
    assert_true(needle in text, "missing " + needle)


def assert_not_in(result: subprocess.CompletedProcess, needle: str) -> None:
    text = result.stdout + "\n" + result.stderr
    assert_true(needle not in text, "unexpected " + repr(needle))


def assert_true(condition: bool, message: str) -> None:
    if not condition:
        raise SystemExit("consumer-sim: " + message)


def fail(stage: str, result: subprocess.CompletedProcess, message: str) -> None:
    print(f"consumer-sim FAIL stage={stage}: {message}", file=sys.stderr)
    print("--- stdout ---", file=sys.stderr)
    print(result.stdout[-6000:], file=sys.stderr)
    print("--- stderr ---", file=sys.stderr)
    print(result.stderr[-6000:], file=sys.stderr)
    raise SystemExit(1)


def write_timings(timings: list[dict]) -> None:
    path = CONSUMER / ".pickleball" / "consumer-sim-timings.json"
    path.parent.mkdir(parents=True, exist_ok=True)
    public = [{"stage": row["stage"], "seconds": row["seconds"], "exit": row["exit"]} for row in timings]
    path.write_text(json.dumps(public, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except SystemExit:
        raise
    except Exception as failure:
        print("consumer-sim: " + str(failure), file=sys.stderr)
        raise SystemExit(1)
