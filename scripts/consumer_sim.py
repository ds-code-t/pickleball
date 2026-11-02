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
import tempfile
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
    "validate": 30,
    "isolate": 600,
    "execute-step-b64": 180,
    "execute-step-file": 180,
    "events": 60,
    "status": 60,
    "query-errors": 60,
    "query-tail": 60,
    "discover-rerun": 900,
    "query-errors-after": 60,
    "confirm": 900,
    "finish": 60,
    "stop": 60,
    "unknown-verb": 60,
    "exec-java-usage": 300,
    "exec-java-split-tags": 300,
    "documented-exec-usage": 300,
    "documented-exec-split-tags": 300,
    "stock-mvn-cmd": 180,
}


def main() -> int:
    parser = argparse.ArgumentParser(description="Pickleball consumer-simulation harness")
    parser.add_argument("--publish", action="store_true", help="publish the framework to mavenLocal first")
    args = parser.parse_args()
    if args.publish:
        publish()
    configure_browser()
    test_classpath()
    timings: list[dict] = []
    try:
        prepare_tree()
        unknown = run_stage(timings, "unknown-verb", ["not-a-verb"], allow_exit={2})
        assert_in(unknown, "error:")
        assert_in(unknown, "fix:")
        exec_usage = run_exec_stage(timings, "exec-java-usage", ["not-a-verb"], allow_exit={2})
        assert_in(exec_usage, "error:")
        assert_in(exec_usage, "fix:")
        exec_tags = run_exec_stage(
            timings, "exec-java-split-tags", ["plan", "--tags=@a", "and", "not", "@b"], allow_exit={2}
        )
        assert_in(exec_tags, "Tag quoting error")
        assert_in(exec_tags, "fix:")
        documented_usage = run_documented_exec(timings, "documented-exec-usage", "not-a-verb")
        assert_in(documented_usage, "error:")
        assert_in(documented_usage, "fix:")
        documented_tags = run_documented_exec(
            timings, "documented-exec-split-tags", "plan --tags=@a and not @b"
        )
        assert_in(documented_tags, "Tag quoting error")
        assert_in(documented_tags, "fix:")
        stock_wrapper(timings)
        run_stage(timings, "discover-uncompiled", [
            "discover", "--run-id=" + RUN_UNCOMPILED, "--tags=" + TAGS, "--name=" + NAME,
            "--runvar=pkb_browser=CHROME_HEADLESS", "--retention=failed", "--agent=consumer-sim",
        ], allow_exit={0, 1})
        assert_record(RUN_UNCOMPILED)
        assert_summary_present(timings[-1])
        assert_launch_gap(RUN_UNCOMPILED)
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
        assert_cache_hit(RUN)
        validated = run_stage(timings, "validate", [
            "validate", "--tags=" + TAGS, "--name=" + NAME,
        ], allow_exit={0})
        assert_in(validated, "validate undefined=0")
        assert_not_in(validated, "summary status=")
        assert_validate_warm(validated.stdout)
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
        status = run_stage(timings, "status", ["status", "--run-id=" + RUN], allow_exit={0})
        assert_in(status, "status=idle")
        assert_in(status, "elapsed-step-ms=idle")
        run_stage(timings, "query-errors", ["query", "errors", "--run-id=" + RUN], allow_exit={0})
        run_stage(timings, "query-tail", ["query", "tail", "--run-id=" + RUN], allow_exit={0})
        rerun = run_stage(timings, "discover-rerun", [
            "discover", "--run-id=" + RUN, "--tags=" + TAGS, "--name=" + NAME,
            "--runvar=pkb_browser=CHROME_HEADLESS", "--retention=failed", "--agent=consumer-sim",
        ], allow_exit={0, 1})
        assert_in(rerun, "summary status=")
        attempts = CONSUMER / ".pickleball" / "runs" / RUN / "attempts"
        assert_true(attempts.is_dir(), "rerun did not archive the previous diagnostic attempt")
        if "status=PASSED" in (rerun.stdout or ""):
            assert_in(rerun, "failed=0")
        after = run_stage(timings, "query-errors-after", ["query", "errors", "--run-id=" + RUN], allow_exit={0})
        assert_not_in(after, "attempts/")
        if "status=PASSED" in (rerun.stdout or ""):
            assert_true('"matched":0' in after.stdout, "a passed rerun still reported errors: " + after.stdout[:500])
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
    assert_session_start(timings)
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
    state = CONSUMER / ".pickleball"
    # classpath.txt is the session worker classpath (live/classes first).
    # seed-classpath.txt is only the pre-sync fork list. Drop both so the
    # uncompiled discover must compile and record that stage.
    for name in ("classpath.txt", "seed-classpath.txt"):
        for classpath in state.glob(f"workbench/{name}"):
            classpath.unlink(missing_ok=True)
        for classpath in state.glob(f"v/*/workbench/{name}"):
            classpath.unlink(missing_ok=True)
    for handle in list(state.glob("workbench/warm-jvm.v1")) + list(state.glob("v/*/workbench/warm-jvm.v1")):
        stop_warm(handle)
        handle.unlink(missing_ok=True)


def stop_warm(handle: Path) -> None:
    try:
        lines = handle.read_text(encoding="utf-8", errors="replace").splitlines()
        if len(lines) >= 2 and lines[1].strip().isdigit():
            os.kill(int(lines[1].strip()), 15)
    except OSError:
        return


def assert_launch_gap(run_id: str) -> None:
    """Index overlaps compile. Seed and maven-start still happen. Order of the pair is not fixed."""
    timing = CONSUMER / ".pickleball" / "runs" / run_id / "launch-timing.txt"
    text = timing.read_text(encoding="utf-8", errors="replace") if timing.is_file() else ""
    for stage in ("index", "compile", "seed-fork", "overlap"):
        token = f"stage={stage}"
        if token not in text:
            fail_text(run_id, text, f"launch-timing.txt missing {token}")
    if "index+compile" not in text and "compile+index" not in text:
        fail_text(run_id, text, "launch-timing.txt has no index/compile overlap")
    if "stage=maven-start" not in text and "stage=direct" not in text:
        fail_text(run_id, text, "launch-timing.txt missing maven-start or direct")


def assert_cache_hit(run_id: str) -> None:
    timing = CONSUMER / ".pickleball" / "runs" / run_id / "launch-timing.txt"
    text = timing.read_text(encoding="utf-8", errors="replace") if timing.is_file() else ""
    marker = "cache-hit elapsedMs="
    at = text.find(marker)
    if at < 0:
        fail_text(run_id, text, "launch-timing.txt missing maven cache-hit")
    digits = []
    for ch in text[at + len(marker):]:
        if ch.isdigit():
            digits.append(ch)
        else:
            break
    elapsed = int("".join(digits) or "999999")
    print(f"scoreboard maven-cache-hit run={run_id} elapsedMs={elapsed} target=1000", flush=True)
    if elapsed >= 1000:
        fail_text(run_id, text, f"maven cache-hit elapsedMs={elapsed} target < 1000")


def assert_validate_warm(stdout: str) -> None:
    marker = "elapsedMs="
    at = stdout.rfind(marker)
    if at < 0:
        raise SystemExit("validate did not print elapsedMs")
    digits = []
    for ch in stdout[at + len(marker):]:
        if ch.isdigit():
            digits.append(ch)
        else:
            break
    if not digits:
        raise SystemExit("validate elapsedMs was not a number")
    elapsed = int("".join(digits))
    print(f"scoreboard validate elapsedMs={elapsed} target=2000", flush=True)
    if elapsed >= 2000:
        raise SystemExit(f"validate warm elapsedMs={elapsed} target < 2000")


def assert_session_start(rows: list[dict]) -> None:
    isolate = next((row for row in rows if row["stage"] == "isolate"), None)
    if isolate is None:
        raise SystemExit("isolate timing missing")
    seconds = float(isolate["seconds"])
    print(f"scoreboard session-start seconds={seconds} target=60", flush=True)
    if seconds >= 60:
        raise SystemExit(f"session start {seconds}s exceeded 60s")


def fail_text(stage: str, text: str, reason: str) -> None:
    print(f"FAIL {stage}: {reason}", file=sys.stderr)
    print(text, file=sys.stderr)
    raise SystemExit(1)


def stock_wrapper(timings: list[dict]) -> None:
    """Stock Apache mvn.cmd collapses every non-zero JVM status to 1.

    Only the patched mvnw.cmd returns 2. This stage runs on Windows only.
    The copy stays named mvnw.cmd. The wrapper turns that filename into
    mvn.cmd under the Maven home (`$script -replace '^mvnw','mvn'`). A
    different name looks for apache-maven/bin/<that name> and exits before
    Maven runs. The copy is in a temp directory, next to .mvn, because the
    wrapper reads distributionUrl from the script directory.
    It asserts a non-zero exit, error: and fix:, and no Maven BUILD FAILURE
    stack. It does not assert exit 2.
    """
    if os.name != "nt":
        print("stage=stock-mvn-cmd skipped (not Windows)", flush=True)
        return
    source = (CONSUMER / "mvnw.cmd").read_bytes()
    needle = b"@call :pkbMaven %*"
    if needle not in source:
        raise SystemExit("stock wrapper patch point missing from mvnw.cmd")
    patched = source.replace(needle, b'@call "%__MVNW_CMD__%" %*', 1)
    budget = BUDGETS["stock-mvn-cmd"]
    started = time.monotonic()
    with tempfile.TemporaryDirectory(prefix="pkb-stock-mvnw-") as temp_name:
        temp = Path(temp_name)
        dest = temp / "mvnw.cmd"
        dest.write_bytes(patched)
        shutil.copytree(CONSUMER / ".mvn", temp / ".mvn")
        result = maven_cmd(script_command(dest, [
            "-q",
            "org.codehaus.mojo:exec-maven-plugin:3.5.0:java",
            "-Dexec.mainClass=tools.dscode.launcher.PickleballWorkbenchLauncher",
            "-Dexec.classpathScope=test",
            "-Dexec.args=not-a-verb",
        ]), budget + 30)
    seconds = round(time.monotonic() - started, 3)
    timings.append({
        "stage": "stock-mvn-cmd",
        "seconds": seconds,
        "exit": result.returncode,
        "stdout": result.stdout,
        "stderr": result.stderr,
    })
    print(f"stage=stock-mvn-cmd exit={result.returncode} seconds={seconds}", flush=True)
    combined = (result.stdout or "") + "\n" + (result.stderr or "")
    if result.returncode == 0:
        fail("stock-mvn-cmd", result, "stock mvn.cmd exit was 0")
    if "error:" not in combined or "fix:" not in combined:
        fail("stock-mvn-cmd", result, "missing error:/fix:")
    if "BUILD FAILURE" in combined:
        fail("stock-mvn-cmd", result, "Maven BUILD FAILURE stack leaked")
    if seconds > budget:
        fail("stock-mvn-cmd", result, f"exceeded budget {budget}s")


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


def run_exec_stage(timings: list[dict], stage: str, launcher_args: list[str], allow_exit: set[int]):
    budget = BUDGETS[stage]
    started = time.monotonic()
    result = exec_java(launcher_args, budget + 30)
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


def run_documented_exec(timings: list[dict], stage: str, exec_args: str):
    """The agent-guide command, including its quotes, via bash or Windows PowerShell."""
    budget = BUDGETS[stage]
    started = time.monotonic()
    result = documented_exec(exec_args, budget + 30)
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
    if result.returncode != 2:
        fail(stage, result, f"documented launch exit {result.returncode} != 2")
    if seconds > budget:
        fail(stage, result, f"exceeded budget {budget}s")
    return result


def documented_exec(exec_args: str, timeout: int) -> subprocess.CompletedProcess:
    """Exact form from docs/consumer-agent-guide.md. Not a Python argv list."""
    quoted = (
        "org.codehaus.mojo:exec-maven-plugin:3.5.0:java "
        '"-Dexec.mainClass=tools.dscode.launcher.PickleballWorkbenchLauncher" '
        '"-Dexec.classpathScope=test" '
        f'"-Dexec.args={exec_args}"'
    )
    if os.name == "nt":
        # A -Command string re-quotes and hides the bug. -File runs the guide line as written.
        script = CONSUMER / ".pickleball" / "documented-exec.ps1"
        script.parent.mkdir(parents=True, exist_ok=True)
        script.write_text(
            f".\\mvnw.cmd -q {quoted}\r\nexit $LASTEXITCODE\r\n",
            encoding="utf-8",
            newline="",
        )
        last = None
        for host in powershell_hosts():
            last = maven_cmd(
                [host, "-NoProfile", "-NonInteractive", "-File", str(script)],
                timeout,
            )
            if last.returncode != 2:
                return last
        return last
    return maven_cmd(["bash", "-c", f"./mvnw -q {quoted}"], timeout)


def powershell_hosts() -> list[str]:
    system = os.environ.get("SystemRoot", r"C:\Windows")
    windows_ps = str(Path(system) / "System32" / "WindowsPowerShell" / "v1.0" / "powershell.exe")
    hosts = []
    if Path(windows_ps).is_file():
        hosts.append(windows_ps)
    pwsh = shutil.which("pwsh")
    if pwsh and pwsh not in hosts:
        hosts.append(pwsh)
    if not hosts:
        hosts.append("powershell.exe")
    return hosts


def exec_java(args: list[str], timeout: int) -> subprocess.CompletedProcess:
    """Documented mvnw exec:java. One argv so the shell does not split exec.args."""
    command = script_command(wrapper_path(), [
        "-q",
        "org.codehaus.mojo:exec-maven-plugin:3.5.0:java",
        "-Dexec.mainClass=tools.dscode.launcher.PickleballWorkbenchLauncher",
        "-Dexec.classpathScope=test",
        "-Dexec.args=" + " ".join(args),
    ])
    return maven_cmd(command, timeout)


def launcher(args: list[str], timeout: int) -> subprocess.CompletedProcess:
    command = [
        java_executable(),
        "-cp",
        test_classpath(),
        "tools.dscode.launcher.PickleballWorkbenchLauncher",
        *args,
    ]
    try:
        return subprocess.run(
            command,
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
        raise SystemExit(f"timeout after {timeout}s: {' '.join(command[:6])}") from failure


_TEST_CLASSPATH = None


def java_executable() -> str:
    home = os.environ.get("JAVA_HOME")
    if home:
        name = "java.exe" if os.name == "nt" else "java"
        candidate = Path(home) / "bin" / name
        if candidate.is_file():
            return str(candidate)
    return "java"


def test_classpath() -> str:
    global _TEST_CLASSPATH
    if _TEST_CLASSPATH:
        return _TEST_CLASSPATH
    output = CONSUMER / ".pickleball" / "harness-classpath.txt"
    output.parent.mkdir(parents=True, exist_ok=True)
    resolved = maven_cmd(script_command(wrapper_path(), [
        "-q",
        "org.apache.maven.plugins:maven-dependency-plugin:3.8.1:build-classpath",
        "-Dmdep.includeScope=test",
        f"-Dmdep.outputFile={output.resolve().as_posix()}",
    ]), 300)
    if resolved.returncode != 0:
        raise SystemExit("could not resolve the consumer test classpath\n" + resolved.stdout + resolved.stderr)
    deps = output.read_text(encoding="utf-8", errors="replace").strip()
    parts = [str((CONSUMER / "target" / "test-classes").resolve()), str((CONSUMER / "target" / "classes").resolve())]
    if deps:
        parts.append(deps)
    _TEST_CLASSPATH = os.pathsep.join(parts)
    return _TEST_CLASSPATH


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
