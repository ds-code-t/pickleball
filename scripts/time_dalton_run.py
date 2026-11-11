#!/usr/bin/env python3
"""Time a cold Gradle or Maven test against warm dalton run.

The cold wrapper is the CI source of truth. Warm ``dalton run`` must exit
with that same code. The worker calls WorkbenchDirectRun, so the engine id
is pickleball-test-engine and the run sets ``pkb_dalton=true``.

``--self-check`` prints that contract and exits 0. It does not launch a suite.
``--project DIR`` runs one cold wrapper and two ``dalton run`` calls.
``maven-consumer-project`` already has ``mvnw`` and ``mvnw.cmd``.
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
    if len(argv) == 2 and argv[0] == "--project":
        return compare(Path(argv[1]).resolve())
    print("Usage: scripts/time_dalton_run.py --self-check | --project DIR", file=sys.stderr)
    return 2


def compare(project: Path) -> int:
    if not project.is_dir():
        print(f"error: project is not a directory: {project}", file=sys.stderr)
        print("fix: pass a project that has mvnw or gradlew", file=sys.stderr)
        return 2
    cold = cold_command(project)
    if not cold:
        print("error: project has no mvnw, gradlew, pom.xml, or build.gradle", file=sys.stderr)
        print("fix: run this script from a wrapper project", file=sys.stderr)
        return 2
    classpath = ensure_classpath()
    text = classpath.read_text(encoding="utf-8").strip()
    cache = project / ".pickleball" / "dalton" / "classpath.cache"
    cache.parent.mkdir(parents=True, exist_ok=True)
    cache.write_text(text + "\n", encoding="utf-8")
    cold_code, cold_ms = timed(cold, project)
    warm = [
        java_bin(),
        "-cp",
        "@" + str(classpath),
        "tools.dscode.dalton.DaltonCli",
        "dalton",
        "run",
        "--project=" + str(project),
    ]
    first_code, first_ms = timed(warm, ROOT)
    second_code, second_ms = timed(warm, ROOT)
    print(f"coldMs={cold_ms} coldExit={cold_code}")
    print(f"warm1Ms={first_ms} warm1Exit={first_code}")
    print(f"warm2Ms={second_ms} warm2Exit={second_code}")
    if first_code != cold_code or second_code != cold_code:
        print("error: dalton run exit does not match the cold wrapper", file=sys.stderr)
        print("fix: the fast path must use the same engine", file=sys.stderr)
        return 1
    print("results match")
    return 0


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


def timed(command: list[str], cwd: Path) -> tuple[int, int]:
    started = time.monotonic()
    completed = subprocess.run(command, cwd=cwd)
    return completed.returncode, int((time.monotonic() - started) * 1000)


def java_bin() -> str:
    home = os.environ.get("JAVA_HOME")
    if home:
        candidate = Path(home) / "bin" / "java"
        if candidate.is_file():
            return str(candidate)
    return "java"


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
