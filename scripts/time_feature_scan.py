#!/usr/bin/env python3
"""Time a whole WorkbenchFeatureIndex scan of a consumer project.

Cold ignores the on-disk cache (refresh). Warm reads that cache.
The default project is maven-consumer-project. Pass another project path
as the only argument. This does not start Cucumber. discover and plan use
the same WorkbenchFeatureIndex class.

Prints files, hits, coldMs, and warmMs.
"""

from __future__ import annotations

import os
from pathlib import Path
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "scripts" / "FeatureScanTiming.java"
CLASSES = ROOT / "pickleball-control-protocol" / "build" / "classes" / "java" / "main"
MARKER = CLASSES / "tools" / "dscode" / "control" / "protocol" / "WorkbenchFeatureIndex.class"


def toolchain(name: str) -> str:
    home = os.environ.get("JAVA_HOME")
    if home:
        candidate = Path(home) / "bin" / name
        if candidate.is_file():
            return str(candidate)
    return name


def main() -> int:
    if len(sys.argv) > 2:
        print("Usage: scripts/time_feature_scan.py [project]", file=sys.stderr)
        return 2
    project = Path(sys.argv[1] if len(sys.argv) == 2 else ROOT / "maven-consumer-project")
    if not MARKER.is_file():
        subprocess.check_call(
            ["./gradlew", "--offline", ":pickleball-control-protocol:classes"],
            cwd=ROOT,
        )
    if not MARKER.is_file():
        print("WorkbenchFeatureIndex was not compiled.", file=sys.stderr)
        return 1
    with tempfile.TemporaryDirectory(prefix="pickleball-feature-scan-") as directory:
        compiled = Path(directory)
        subprocess.check_call(
            [
                toolchain("javac"),
                "--class-path",
                str(CLASSES),
                "-d",
                str(compiled),
                str(SOURCE),
            ]
        )
        subprocess.check_call(
            [
                toolchain("java"),
                "--class-path",
                os.pathsep.join((str(compiled), str(CLASSES))),
                "tools.dscode.timing.FeatureScanTiming",
                str(project),
            ]
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
