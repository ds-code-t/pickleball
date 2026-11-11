#!/usr/bin/env python3
"""Time a cold Gradle or Maven test against warm dalton run.

The cold wrapper is the CI source of truth. Warm ``dalton run`` must exit
with that same code and the same scenario and step lines. The worker calls
WorkbenchDirectRun, so the engine id is pickleball-test-engine and the run
sets ``pkb_dalton=true``. The second warm run must reuse the worker.

``--self-check`` prints that contract and exits 0. It does not launch a suite.
``--project DIR`` runs one cold wrapper and two ``dalton run`` calls.
``--tags``, ``--name``, ``--glue``, and repeated ``--define KEY=VALUE`` narrow
both sides. ``maven-consumer-project`` already has ``mvnw`` and ``mvnw.cmd``.
"""

from __future__ import annotations

import os
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def main(argv: list[str]) -> int:
    if argv == ["--self-check"]:
        print("cold Gradle or Maven test is the CI source of truth")
        print("warm dalton run uses pickleball-test-engine via WorkbenchDirectRun")
        print("two warm runs must exit with the same code as the cold run")
        print("mvnw is maven-consumer-project/mvnw; this script does not add one")
        print("self-check does not launch a suite")
        return 0
    if not argv or argv[0] != "--project" or len(argv) < 2:
        return usage()
    project = Path(argv[1]).resolve()
    tags = None
    name = None
    glue = None
    defines: list[str] = []
    index = 2
    while index < len(argv):
        token = argv[index]
        if token.startswith("--tags="):
            tags = token[len("--tags="):]
        elif token.startswith("--name="):
            name = token[len("--name="):]
        elif token.startswith("--glue="):
            glue = token[len("--glue="):]
        elif token.startswith("--define="):
            defines.append(token[len("--define="):])
        else:
            print(f"error: unknown argument: {token}", file=sys.stderr)
            return usage()
        index += 1
    for item in defines:
        if "=" not in item or item.startswith("="):
            print(f"error: --define needs KEY=VALUE: {item}", file=sys.stderr)
            print("fix: --define pkb_features=wd-file:/path", file=sys.stderr)
            return 2
    return compare(project, tags, name, glue, defines)


def usage() -> int:
    print(
        "Usage: scripts/time_dalton_run.py --self-check | --project DIR "
        "[--tags=EXPR] [--name=TEXT] [--glue=PKGS] [--define KEY=VALUE]",
        file=sys.stderr,
    )
    return 2


def compare(project: Path, tags: str | None, name: str | None, glue: str | None, defines: list[str]) -> int:
    if not project.is_dir():
        print(f"error: project is not a directory: {project}", file=sys.stderr)
        print("fix: pass a project that has mvnw or gradlew", file=sys.stderr)
        return 2
    extra = selection(tags, name, glue, defines)
    cold = cold_command(project) + extra
    if len(cold) == len(extra):
        print("error: project has no mvnw, gradlew, pom.xml, or build.gradle", file=sys.stderr)
        print("fix: run this script from a wrapper project", file=sys.stderr)
        return 2
    classpath = ensure_classpath()
    text = classpath.read_text(encoding="utf-8").strip()
    cache = project / ".pickleball" / "dalton" / "classpath.cache"
    cache.parent.mkdir(parents=True, exist_ok=True)
    cache.write_text(text + "\n", encoding="utf-8")
    cold_code, cold_ms, cold_out, cold_err = timed(cold, project)
    warm = [
        java_bin(),
        "-cp",
        "@" + str(classpath),
        "tools.dscode.dalton.DaltonCli",
        "dalton",
        "run",
        "--project=" + str(project),
    ]
    if tags:
        warm.append("--tags=" + tags)
    if name:
        warm.append("--name=" + name)
    if glue:
        warm.append("--glue=" + glue)
    for item in defines:
        warm.append("-D" + item)
    first_code, first_ms, first_out, first_err = timed(warm, ROOT)
    second_code, second_ms, second_out, second_err = timed(warm, ROOT)
    first_reused = reused_flag(first_out)
    second_reused = reused_flag(second_out)
    print(f"coldMs={cold_ms} coldExit={cold_code}")
    print(f"warm1Ms={first_ms} warm1Exit={first_code} warm1Reused={flag(first_reused)}")
    print(f"warm2Ms={second_ms} warm2Exit={second_code} warm2Reused={flag(second_reused)}")
    cold_lines = scenario_lines(cold_out)
    first_lines = scenario_lines(first_out)
    second_lines = scenario_lines(second_out)
    failed = False
    if first_code != cold_code or second_code != cold_code:
        print("error: dalton run exit does not match the cold wrapper", file=sys.stderr)
        print("fix: the fast path must use the same engine", file=sys.stderr)
        failed = True
    if cold_lines != first_lines or first_lines != second_lines:
        print("error: scenario lines do not match", file=sys.stderr)
        print("cold " + " | ".join(cold_lines), file=sys.stderr)
        print("warm1 " + " | ".join(first_lines), file=sys.stderr)
        print("warm2 " + " | ".join(second_lines), file=sys.stderr)
        print("fix: pass the same --tags, --glue, and --define to both sides", file=sys.stderr)
        failed = True
    if second_reused is not True:
        print("error: the second warm run did not reuse the worker", file=sys.stderr)
        print("fix: seed .pickleball/dalton/glue.fingerprint so the run does not restart", file=sys.stderr)
        failed = True
    if failed:
        print(tail("cold stdout", cold_out), file=sys.stderr)
        print(tail("cold stderr", cold_err), file=sys.stderr)
        print(tail("warm1 stderr", first_err), file=sys.stderr)
        print(tail("warm2 stderr", second_err), file=sys.stderr)
        return 1
    print("results match")
    return 0


def selection(tags: str | None, name: str | None, glue: str | None, defines: list[str]) -> list[str]:
    extra = []
    if tags:
        extra.append("-Dpkb_tags=" + tags)
    if name:
        extra.append("-Dpkb_name=" + name)
    if glue:
        extra.append("-Dpkb_glue=" + glue)
    for item in defines:
        extra.append("-D" + item)
    return extra


def cold_command(project: Path) -> list[str]:
    windows = os.name == "nt"
    if (project / "pom.xml").is_file():
        wrapper = project / ("mvnw.cmd" if windows else "mvnw")
        tool = str(wrapper) if wrapper.is_file() else ("mvn.cmd" if windows else "mvn")
        return [tool, "-q", "test"]
    wrapper = project / ("gradlew.bat" if windows else "gradlew")
    if wrapper.is_file() or (project / "build.gradle").is_file() or (project / "build.gradle.kts").is_file():
        tool = str(wrapper) if wrapper.is_file() else ("gradle.bat" if windows else "gradle")
        return [tool, "test", "--no-daemon"]
    return []


def ensure_classpath() -> Path:
    destination = ROOT / "build" / "dalton-run-classpath.txt"
    if destination.is_file() and destination.stat().st_size > 0:
        return destination
    subprocess.check_call([str(ROOT / "gradlew"), "daltonRunClasspath", "--no-daemon"], cwd=ROOT)
    if not destination.is_file():
        print("error: daltonRunClasspath did not write a classpath", file=sys.stderr)
        print("fix: ./gradlew daltonRunClasspath", file=sys.stderr)
        raise SystemExit(1)
    return destination


def timed(command: list[str], cwd: Path) -> tuple[int, int, str, str]:
    started = time.monotonic()
    completed = subprocess.run(
        command,
        cwd=cwd,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )
    return completed.returncode, int((time.monotonic() - started) * 1000), completed.stdout, completed.stderr


def scenario_lines(text: str) -> list[str]:
    kept = []
    for line in text.splitlines():
        trimmed = line.strip()
        if trimmed.startswith("scenario ") or trimmed.startswith("step "):
            kept.append(trimmed)
    kept.sort()
    return kept


def reused_flag(text: str) -> bool | None:
    for line in text.splitlines():
        if "reused=" not in line:
            continue
        value = line.split("reused=", 1)[1].split()[0].strip()
        if value == "true":
            return True
        if value == "false":
            return False
    return None


def flag(value: bool | None) -> str:
    if value is True:
        return "true"
    if value is False:
        return "false"
    return "missing"


def tail(label: str, text: str) -> str:
    body = text or ""
    if len(body) > 2000:
        body = body[-2000:]
    return label + ":\n" + body


def java_bin() -> str:
    home = os.environ.get("JAVA_HOME")
    if home:
        candidate = Path(home) / "bin" / "java"
        if candidate.is_file():
            return str(candidate)
    return "java"


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
