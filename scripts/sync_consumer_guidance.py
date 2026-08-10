#!/usr/bin/env python3
"""Synchronize canonical Pickleball Markdown guidance into packaged resources."""

from __future__ import annotations

import argparse
from pathlib import Path
import shutil
import sys

ROOT = Path(__file__).resolve().parents[1]
DOCS_ROOT = ROOT / "docs"
AGENT_SOURCE = DOCS_ROOT / "consumer-agent-guide.md"
OUTPUT_ROOT = ROOT / "src" / "main" / "resources" / "META-INF" / "pickleball" / "guidance"


def expected_files() -> dict[str, bytes]:
    files: dict[str, bytes] = {}

    if not AGENT_SOURCE.is_file():
        raise FileNotFoundError(f"Missing canonical consumer agent guide: {AGENT_SOURCE}")

    files["AGENT-GUIDE.md"] = AGENT_SOURCE.read_bytes()

    for source in sorted(DOCS_ROOT.rglob("*.md")):
        relative = source.relative_to(DOCS_ROOT).as_posix()
        files[f"docs/{relative}"] = source.read_bytes()

    index_entries = sorted(files)
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

    print(
        "Run: python scripts/sync_consumer_guidance.py",
        file=sys.stderr,
    )
    return 1


def sync() -> int:
    expected = expected_files()
    if OUTPUT_ROOT.exists():
        shutil.rmtree(OUTPUT_ROOT)

    for relative, content in expected.items():
        target = OUTPUT_ROOT / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(content)

    print(f"Updated {OUTPUT_ROOT.relative_to(ROOT)}")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--check",
        action="store_true",
        help="Fail when the packaged guidance differs from canonical docs.",
    )
    args = parser.parse_args()
    return check() if args.check else sync()


if __name__ == "__main__":
    raise SystemExit(main())
