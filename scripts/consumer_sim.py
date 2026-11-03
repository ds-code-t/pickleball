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
    parser.add_argument(
        "--report7-only",
        action="store_true",
        help="run only the report-7 consumer scenarios (still requires a published build)",
    )
    args = parser.parse_args()
    if args.publish:
        publish()
    configure_browser()
    test_classpath()
    if args.report7_only:
        timings: list[dict] = []
        try:
            prepare_tree()
            run_stage(timings, "test-compile", [], maven_args=["-q", "test-compile"], allow_exit={0})
            report7(timings)
        finally:
            stop_report7(timings)
            write_timings(timings)
        print("consumer-sim report7 recorded")
        return 0
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
        report7(timings)
    finally:
        warm_before_stop = warm_pids()
        if (CONSUMER / ".pickleball" / "runs" / RUN).exists():
            try:
                run_stage(timings, "stop", ["stop", "--run-id=" + RUN, "--force"], allow_exit={0, 1, 2})
            except SystemExit:
                pass
        assert_warm_dead(warm_before_stop)
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
            pid = int(lines[1].strip())
            if os.name == "nt":
                subprocess.run(
                    ["taskkill", "/PID", str(pid), "/T", "/F"],
                    stdout=subprocess.DEVNULL,
                    stderr=subprocess.DEVNULL,
                )
            else:
                os.kill(pid, 15)
    except OSError:
        return


def warm_pids() -> list[int]:
    state = CONSUMER / ".pickleball"
    pids: list[int] = []
    if not state.is_dir():
        return pids
    handles = list(state.glob("workbench/warm-jvm.v1")) + list(state.glob("v/*/workbench/warm-jvm.v1"))
    for handle in handles:
        try:
            lines = handle.read_text(encoding="utf-8", errors="replace").splitlines()
        except OSError:
            continue
        if len(lines) >= 2 and lines[1].strip().isdigit():
            pids.append(int(lines[1].strip()))
    return pids


def pid_alive(pid: int) -> bool:
    if pid <= 0:
        return False
    if os.name == "nt":
        completed = subprocess.run(
            ["tasklist", "/FI", f"PID eq {pid}", "/FO", "CSV", "/NH"],
            capture_output=True,
            text=True,
        )
        return f'"{pid}"' in (completed.stdout or "")
    try:
        os.kill(pid, 0)
    except ProcessLookupError:
        return False
    except PermissionError:
        return True
    except OSError:
        return False
    return True


def assert_warm_dead(pids: list[int]) -> None:
    """stop, idle, or owner death must leave no warm JVM. tasklist on Windows; no signal 0 there."""
    deadline = time.time() + 15
    pending = list(pids)
    while time.time() < deadline:
        pending = [pid for pid in pending if pid_alive(pid)]
        pending.extend(pid for pid in warm_pids() if pid not in pending and pid_alive(pid))
        if not pending:
            return
        time.sleep(0.2)
    raise SystemExit("consumer-sim: warm JVM still alive after stop: " + ",".join(str(pid) for pid in pending))


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
    """Stock Apache mvn.cmd and stock mvnw.cmd collapse every non-zero JVM status to 1.

    A script that needs the exact code matches error: or uses java -cp.
    This stage runs on Windows only.
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


def report7(timings: list[dict]) -> None:
    """Report-7 consumer symptoms. Each item records PASS or FAIL. Any FAIL exits 1.

    These assert what the Windows consumer sees, not internal state. On a4c7ea6f
    they fail. A later fix is done only when the same item passes.
    """
    results: list[dict] = []
    try:
        _report7_body(timings, results)
    except Exception as failure:
        _report7_note(results, "harness", False, type(failure).__name__ + ": " + str(failure))
        raise
    finally:
        _report7_cleanup()
        print("report7 scoreboard", flush=True)
        for row in results:
            print(f"report7 item={row['item']} result={row['result']} detail={row['detail']}", flush=True)
    failed = [row["item"] for row in results if row["result"] != "PASS"]
    if failed:
        raise SystemExit("consumer-sim report7 FAIL " + ",".join(failed))


def _report7_note(results: list[dict], item: str, ok: bool, detail: str) -> None:
    results.append({"item": item, "result": "PASS" if ok else "FAIL", "detail": detail[:500]})
    print(f"report7 item={item} result={'PASS' if ok else 'FAIL'} detail={detail[:300]}", flush=True)


def _report7_cleanup() -> None:
    for relative in (
        "src/test/java/com/example/report7glue/Report7Steps.java",
        "src/test/resources/features/report7-regex.feature",
        "src/test/resources/features/report7-cap.feature",
    ):
        path = CONSUMER / relative
        if path.is_file():
            path.unlink()
    for run_id in ("report7-a", "report7-b", "report7-c", "report7-quiet", "report7-fail"):
        try:
            launcher(["stop", "--run-id=" + run_id, "--force"], 30)
        except SystemExit:
            pass


def _report7_files() -> None:
    glue = CONSUMER / "src/test/java/com/example/report7glue/Report7Steps.java"
    glue.parent.mkdir(parents=True, exist_ok=True)
    glue.write_text(
        "package com.example.report7glue;\n"
        "import io.cucumber.java.en.Given;\n"
        "public final class Report7Steps {\n"
        "    @Given(\"report seven cucumber {int}\")\n"
        "    public void cucumber(int count) {}\n"
        "    @Given(\"^VERIFY DATA ADDRESS \\\"([^\\\"]+)\\\" HAS DATA TABLE WITH (\\\\d+) DATA ROWS$\")\n"
        "    public void rows(String address, int count) {}\n"
        "}\n",
        encoding="utf-8",
        newline="\n",
    )
    feature = CONSUMER / "src/test/resources/features/report7-regex.feature"
    feature.parent.mkdir(parents=True, exist_ok=True)
    feature.write_text(
        "@report7-regex\n"
        "Feature: Report 7 regex glue\n"
        "  Scenario: Regex and expression steps\n"
        "    Given report seven cucumber 3\n"
        "    Given VERIFY DATA ADDRESS \"addr\" HAS DATA TABLE WITH 2 DATA ROWS\n",
        encoding="utf-8",
        newline="\n",
    )
    cap = CONSUMER / "src/test/resources/features/report7-cap.feature"
    lines = ["@report7-cap", "Feature: Report 7 cap", "  Scenario: Lots"]
    for index in range(2100):
        lines.append(f"    Given missing report7 {index}")
    lines.append("  Scenario: Later one")
    lines.append("    Given missing report7 later-1")
    lines.append("  Scenario: Later two")
    lines.append("    Given missing report7 later-2")
    cap.write_text("\n".join(lines) + "\n", encoding="utf-8", newline="\n")


def _report7_compile(timings: list[dict]) -> None:
    """The consumer compiles before validate/isolate. The launcher classpath already points at target/test-classes."""
    started = time.monotonic()
    result = maven(["-q", "test-compile"], 300)
    seconds = round(time.monotonic() - started, 3)
    timings.append({
        "stage": "report7-test-compile",
        "seconds": seconds,
        "exit": result.returncode,
        "stdout": result.stdout,
        "stderr": result.stderr,
    })
    print(f"stage=report7-test-compile exit={result.returncode} seconds={seconds}", flush=True)
    if result.returncode != 0:
        print((result.stdout or "")[-1500:], flush=True)
        print((result.stderr or "")[-1500:], file=sys.stderr, flush=True)


def _report7_launch(timings: list[dict], stage: str, args: list[str], timeout: int):
    started = time.monotonic()
    try:
        result = launcher(args, timeout)
    except SystemExit as failure:
        result = subprocess.CompletedProcess(args, 124, "", str(failure))
    seconds = round(time.monotonic() - started, 3)
    timings.append({
        "stage": stage,
        "seconds": seconds,
        "exit": result.returncode,
        "stdout": result.stdout,
        "stderr": result.stderr,
    })
    print(f"stage={stage} exit={result.returncode} seconds={seconds}", flush=True)
    return result, seconds


def _report7_body(timings: list[dict], results: list[dict]) -> None:
    _report7_files()
    _report7_compile(timings)
    for run_id in ("report7-a", "report7-b", "report7-c"):
        seeded, _ = _report7_launch(timings, "report7-seed-" + run_id, [
            "workspace-seed", "--run-id=" + run_id, "--reseed",
        ], 180)
        if seeded.returncode != 0:
            _report7_note(results, "seed-" + run_id, False, "workspace-seed exit " + str(seeded.returncode))
    edits = {
        "report7-a": "pkb_glue=com.example.report7glue",
        "report7-b": "pkb_glue=com.example.pickleball",
        "report7-c": "pkb_glue=",
    }
    for run_id, glue in edits.items():
        edited, _ = _report7_launch(timings, "report7-edit-" + run_id, [
            "workspace-edit", "--run-id=" + run_id,
            "--runvar=" + glue,
            "--runvar=pkb_browser=CHROME_HEADLESS",
        ], 60)
        if edited.returncode != 0:
            _report7_note(results, "edit-" + run_id, False, (edited.stdout + edited.stderr)[-400:])

    help_validate, _ = _report7_launch(timings, "report7-help-validate", ["help", "validate"], 60)
    help_text = (help_validate.stdout or "") + "\n" + (help_validate.stderr or "")
    _report7_note(
        results, "4-validate-flags",
        "--max-steps" in help_text and "--all" in help_text,
        "help validate missing --max-steps/--all" if "--max-steps" not in help_text or "--all" not in help_text else "help lists --max-steps and --all",
    )

    capped, _ = _report7_launch(timings, "report7-validate-cap", [
        "validate", "--tags=@report7-cap",
    ], 120)
    cap_text = (capped.stdout or "") + "\n" + (capped.stderr or "")
    _report7_note(
        results, "4-validate-skipped",
        "capped=true" in cap_text and "skipped=2" in cap_text,
        _snippet(cap_text, "capped=", "skipped="),
    )
    all_steps, _ = _report7_launch(timings, "report7-validate-all", [
        "validate", "--tags=@report7-cap", "--all",
    ], 180)
    all_text = (all_steps.stdout or "") + "\n" + (all_steps.stderr or "")
    _report7_note(
        results, "4-validate-all",
        "capped=false" in all_text and "skipped=0" in all_text and "later-2" in all_text,
        _snippet(all_text, "capped=", "skipped=", "later-2"),
    )

    defined, _ = _report7_launch(timings, "report7-validate-a", [
        "validate", "--run-id=report7-a", "--tags=@report7-regex",
    ], 120)
    defined_text = (defined.stdout or "") + "\n" + (defined.stderr or "")
    workspace_a = "runs/report7-a/workspace" in defined_text.replace("\\", "/")
    regex_defined = "undefined=0" in defined_text and "VERIFY DATA ADDRESS" not in defined_text.split("validate undefined=")[0][-200:]
    # undefined=0 is the summary. A finding line would contain the step before that summary.
    regex_clean = "undefined=0" in defined_text and "undefined:" not in defined_text
    _report7_note(
        results, "3-validate-run-workspace",
        workspace_a and defined.returncode == 0,
        _snippet(defined_text, "workspace=", "undefined="),
    )
    _report7_note(
        results, "2-validate-regex",
        regex_clean and "report seven cucumber" not in "\n".join(
            line for line in defined_text.splitlines() if line.startswith("undefined:")
        ),
        _snippet(defined_text, "undefined"),
    )
    blank, _ = _report7_launch(timings, "report7-validate-c", [
        "validate", "--run-id=report7-c", "--tags=@report7-regex",
    ], 120)
    blank_text = (blank.stdout or "") + "\n" + (blank.stderr or "")
    _report7_note(
        results, "3-validate-blank-glue",
        "undefined:" in blank_text and "VERIFY DATA ADDRESS" in blank_text and "report seven cucumber" in blank_text,
        _snippet(blank_text, "undefined"),
    )

    quiet, _ = _report7_launch(timings, "report7-quiet", [
        "discover", "--quiet", "--run-id=report7-quiet", "--tags=" + TAGS, "--name=" + NAME,
        "--runvar=pkb_browser=CHROME_HEADLESS", "--retention=failed", "--agent=consumer-sim",
    ], 900)
    quiet_lines = [line for line in (quiet.stdout or "").splitlines() if line.strip()]
    noisy = [
        line for line in quiet_lines
        if not line.startswith("summary ") and not line.startswith("error:") and not line.startswith("fix:")
    ]
    _report7_note(
        results, "6-quiet",
        quiet.returncode in (0, 1) and not noisy and any(line.startswith("summary ") for line in quiet_lines),
        "extra lines: " + " | ".join(noisy[:8]) if noisy else "summary only",
    )

    failed, _ = _report7_launch(timings, "report7-failed-paths", [
        "discover", "--run-id=report7-fail", "--tags=@workbench-cli-fail",
        "--runvar=pkb_browser=CHROME_HEADLESS", "--retention=failed", "--agent=consumer-sim",
    ], 900)
    fail_text = (failed.stdout or "") + "\n" + (failed.stderr or "")
    relative = "src/test/resources/features/workbench-cli-fail.feature:4"
    _report7_note(
        results, "9-failed-scenarios",
        failed.returncode != 0 and relative in fail_text and "file:" not in fail_text,
        _snippet(fail_text, "failedScenarios"),
    )

    first, first_seconds = _report7_launch(timings, "report7-isolate-a", [
        "isolate", "--run-id=report7-a", "--tags=@report7-regex", "--agent=consumer-sim",
        "--runvar=pkb_browser=CHROME_HEADLESS",
    ], 600)
    descriptor = _read_descriptor("report7-a")
    workspace_path = str(descriptor.get("workspacePath") or "").replace("\\", "/")
    _report7_note(
        results, "1-isolate-workspace",
        first.returncode == 0
        and workspace_path.endswith(".pickleball/runs/report7-a/workspace")
        and "report7-b" not in workspace_path
        and "report7-c" not in workspace_path,
        "workspacePath=" + workspace_path + " exit=" + str(first.returncode),
    )
    status, _ = _report7_launch(timings, "report7-status-after-isolate", [
        "status", "--run-id=report7-a",
    ], 60)
    status_text = (status.stdout or "") + "\n" + (status.stderr or "")
    _report7_note(
        results, "5-status-idle",
        "status=idle" in status_text and "elapsed-step-ms=idle" in status_text,
        _snippet(status_text, "status=", "elapsed-step-ms="),
    )
    first_progress = _read_progress("report7-a")
    previous_last = ""
    stages = first_progress.get("stages") if isinstance(first_progress, dict) else None
    if isinstance(stages, list) and stages:
        previous_last = str(stages[-1].get("stage") or "")
    _report7_launch(timings, "report7-stop-a", ["stop", "--run-id=report7-a", "--force"], 60)
    second, second_seconds = _report7_launch(timings, "report7-isolate-a-restart", [
        "isolate", "--run-id=report7-a", "--tags=@report7-regex", "--agent=consumer-sim",
        "--runvar=pkb_browser=CHROME_HEADLESS",
    ], 600)
    restarted = _read_progress("report7-a")
    restarted_stages = restarted.get("stages") if isinstance(restarted, dict) else None
    first_stage = ""
    first_elapsed = None
    if isinstance(restarted_stages, list) and restarted_stages:
        first_stage = str(restarted_stages[0].get("stage") or "")
        first_elapsed = restarted_stages[0].get("enteredElapsedMs")
    stale = bool(previous_last) and first_stage == previous_last and first_elapsed == 0
    _report7_note(
        results, "7-startup-progress",
        second.returncode == 0 and isinstance(restarted_stages, list) and restarted_stages and not stale,
        f"previous={previous_last} first={first_stage}@{first_elapsed}",
    )
    _report7_note(
        results, "8-restart-budget",
        second.returncode == 0 and second_seconds <= 35,
        f"restart_seconds={second_seconds} first_seconds={first_seconds} target=35",
    )


def _read_descriptor(run_id: str) -> dict:
    path = CONSUMER / ".pickleball" / "runs" / run_id / "session" / "descriptor.json"
    if not path.is_file():
        return {}
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError:
        return {}


def _read_progress(run_id: str) -> dict:
    path = CONSUMER / ".pickleball" / "runs" / run_id / "session" / "startup-progress.json"
    if not path.is_file():
        return {}
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError:
        return {}


def _snippet(text: str, *needles: str) -> str:
    lines = []
    for line in text.splitlines():
        if any(needle in line for needle in needles):
            lines.append(line.strip())
    if not lines:
        return text[-240:].replace("\n", " | ")
    return " | ".join(lines[:6])


def stop_report7(timings: list[dict]) -> None:
    _report7_cleanup()


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
