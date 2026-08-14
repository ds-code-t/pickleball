#!/usr/bin/env python3
"""Synchronize version-matched Pickleball consumer guidance metadata and docs."""

from __future__ import annotations

import argparse
from pathlib import Path
import shutil
import sys

ROOT = Path(__file__).resolve().parents[1]
DOCS_ROOT = ROOT / "docs"
AGENT_SOURCE = DOCS_ROOT / "consumer-agent-guide.md"
CONSUMER_ROOT = ROOT / "maven-consumer-project"
OUTPUT_ROOT = ROOT / "src" / "main" / "resources" / "META-INF" / "pickleball" / "guidance"
CONSUMER_PREFIX = "maven-consumer-project/"

CONSUMER_REFERENCE_FILES = (
    "pom.xml",
    "src/test/java/com/example/pickleball/PickleballTests.java",
    "src/test/java/com/example/pickleball/support/LocalTestSite.java",
    "src/test/resources/pickleball.properties",
    "src/test/resources/pickleball_local.properties",
    "src/test/resources/profiles.yaml",
    "src/test/resources/profiles_local.yaml",
)
CONSUMER_REFERENCE_TREES = (
    "src/test/resources/features",
    "src/test/resources/calls",
    "src/test/resources/configs",
    "src/test/resources/data",
    "src/test/resources/files",
    "src/test/resources/site",
)
MAX_REFERENCE_FILE_BYTES = 2 * 1024 * 1024
MAX_REFERENCE_TOTAL_BYTES = 10 * 1024 * 1024


def is_maintainer_local(path: Path) -> bool:
    return "_local2" in path.stem


def consumer_reference_sources() -> list[Path]:
    sources: list[Path] = []

    for relative in CONSUMER_REFERENCE_FILES:
        source = CONSUMER_ROOT / relative
        if not source.is_file():
            raise FileNotFoundError(f"Missing Maven consumer reference file: {source}")
        if not is_maintainer_local(source):
            sources.append(source)

    for relative in CONSUMER_REFERENCE_TREES:
        root = CONSUMER_ROOT / relative
        if not root.is_dir():
            raise FileNotFoundError(f"Missing Maven consumer reference directory: {root}")
        sources.extend(
            path for path in root.rglob("*")
            if path.is_file() and not is_maintainer_local(path)
        )

    unique = sorted(
        set(sources),
        key=lambda path: path.relative_to(CONSUMER_ROOT).as_posix(),
    )

    total_bytes = 0
    for source in unique:
        size = source.stat().st_size
        if size > MAX_REFERENCE_FILE_BYTES:
            raise ValueError(
                f"Maven consumer reference file exceeds {MAX_REFERENCE_FILE_BYTES} bytes: "
                f"{source.relative_to(ROOT)} ({size} bytes)"
            )
        total_bytes += size

    if total_bytes > MAX_REFERENCE_TOTAL_BYTES:
        raise ValueError(
            f"Maven consumer reference snapshot exceeds {MAX_REFERENCE_TOTAL_BYTES} bytes "
            f"({total_bytes} bytes). Review the curated reference scope."
        )

    return unique


def consumer_reference_entries() -> list[str]:
    return [
        CONSUMER_PREFIX + source.relative_to(CONSUMER_ROOT).as_posix()
        for source in consumer_reference_sources()
    ]


def expected_files() -> dict[str, bytes]:
    files: dict[str, bytes] = {}

    if not AGENT_SOURCE.is_file():
        raise FileNotFoundError(f"Missing canonical consumer agent guide: {AGENT_SOURCE}")

    files["AGENT-GUIDE.md"] = AGENT_SOURCE.read_bytes()

    for source in sorted(DOCS_ROOT.rglob("*.md")):
        relative = source.relative_to(DOCS_ROOT).as_posix()
        files[f"docs/{relative}"] = source.read_bytes()

    index_entries = sorted([*files, *consumer_reference_entries()])
    files["index.txt"] = ("\n".join(index_entries) + "\n").encode("utf-8")
    return files


def current_files() -> dict[str, bytes]:
    if not OUTPUT_ROOT.is_dir():
        return {}
    return {
        path.relative_to(OUTPUT_ROOT).as_posix(): path.read_bytes()
        for path in OUTPUT_ROOT.rglob("*")
        if path.is_file()
    }


def check() -> int:
    expected = expected_files()
    current = current_files()
    if current == expected:
        print("Packaged consumer guidance is current.")
        return 0

    missing = sorted(set(expected) - set(current))
    extra = sorted(set(current) - set(expected))
    changed = sorted(
        path for path in set(expected) & set(current)
        if expected[path] != current[path]
    )

    if missing:
        print("Missing packaged guidance: " + ", ".join(missing), file=sys.stderr)
    if extra:
        print("Unexpected packaged guidance: " + ", ".join(extra), file=sys.stderr)
    if changed:
        print("Stale packaged guidance: " + ", ".join(changed), file=sys.stderr)

    print("Run: python scripts/sync_consumer_guidance.py", file=sys.stderr)
    return 1


def sync() -> int:
    expected = expected_files()
    if OUTPUT_ROOT.exists():
        shutil.rmtree(OUTPUT_ROOT)

    for relative, content in expected.items():
        target = OUTPUT_ROOT / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(content)

    reference_sources = consumer_reference_sources()
    reference_bytes = sum(path.stat().st_size for path in reference_sources)
    print(
        f"Updated {OUTPUT_ROOT.relative_to(ROOT)}; indexed "
        f"{len(reference_sources)} Maven consumer reference files ({reference_bytes} bytes)."
    )
    return 0


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--check",
        action="store_true",
        help="Fail when packaged guidance metadata/docs differ from canonical sources.",
    )
    args = parser.parse_args()
    return check() if args.check else sync()


if __name__ == "__main__":
    raise SystemExit(main())
