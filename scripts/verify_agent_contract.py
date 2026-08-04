#!/usr/bin/env python3
"""Validate repository agent files and optionally check change coverage."""

from __future__ import annotations

import argparse
import hashlib
import os
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
TEMP_WORKSPACE = ".agent-work"
TEMP_WORKSPACE_IGNORE = ".agent-work/"
REQUIRED_FILES = (
    ".gitignore",
    "AGENTS.md",
    "CLAUDE.md",
    "GEMINI.md",
    "REVIEW.md",
    ".aiassistant/rules/pickleball.md",
    ".junie/guidelines.md",
    ".github/copilot-instructions.md",
    ".github/agents/pickleball.agent.md",
    ".github/instructions/java.instructions.md",
    ".github/instructions/documentation.instructions.md",
    ".github/instructions/consumer-project.instructions.md",
    ".amazonq/rules/pickleball.md",
    ".cursor/rules/pickleball.mdc",
    ".continue/rules/01-pickleball.md",
    ".clinerules/01-pickleball.md",
    ".windsurf/rules/pickleball.md",
    "docs/agent/README.md",
    "docs/agent/feature-map.md",
    "docs/agent/change-checklist.md",
    "docs/agent/prompt-examples.md",
    "docs/agent/repository-index.md",
    "scripts/refresh_agent_index.py",
    "scripts/verify_agent_contract.py",
    "scripts/agent_validate.sh",
    "scripts/agent_validate.ps1",
    "maven-consumer-project/mvnw",
    "maven-consumer-project/mvnw.cmd",
    "maven-consumer-project/.mvn/wrapper/maven-wrapper.properties",
)
ADAPTER_FILES = (
    "CLAUDE.md",
    "GEMINI.md",
    ".aiassistant/rules/pickleball.md",
    ".junie/guidelines.md",
    ".github/copilot-instructions.md",
    ".amazonq/rules/pickleball.md",
    ".cursor/rules/pickleball.mdc",
    ".continue/rules/01-pickleball.md",
    ".clinerules/01-pickleball.md",
    ".windsurf/rules/pickleball.md",
)
SKILL_FILES = (
    ".agents/skills/pickleball-functionality-change/SKILL.md",
    ".claude/skills/pickleball-functionality-change/SKILL.md",
    ".github/skills/pickleball-functionality-change/SKILL.md",
    ".cursor/skills/pickleball-functionality-change/SKILL.md",
    ".windsurf/skills/pickleball-functionality-change/SKILL.md",
)
BEHAVIOR_PREFIXES = (
    "src/main/java/",
    "src/main/aspectj/",
    "src/main/resources/",
)
BEHAVIOR_FILES = {
    "build.gradle",
    "settings.gradle",
    "gradle.properties",
}
TEST_PREFIXES = (
    "src/test/",
    "maven-consumer-project/src/test/java/",
    "maven-consumer-project/src/test/resources/features/",
    "maven-consumer-project/src/test/resources/calls/",
    "maven-consumer-project/src/test/resources/configs/",
    "maven-consumer-project/src/test/resources/site/",
)
DOC_PREFIXES = (
    "docs/",
)
DOC_FILES = {
    "README.md",
    "maven-consumer-project/README.md",
}


def env_true(name: str) -> bool:
    return os.environ.get(name, "").strip().lower() in {"1", "true", "yes", "on"}


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def git_changed_files(base_ref: str) -> list[str] | None:
    command = ["git", "diff", "--name-only", f"{base_ref}...HEAD"]
    result = subprocess.run(
        command,
        cwd=ROOT,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )
    if result.returncode != 0:
        print(
            f"WARNING: Could not compare changes with {base_ref}: "
            f"{result.stderr.strip()}",
            file=sys.stderr,
        )
        return None
    return [line.strip() for line in result.stdout.splitlines() if line.strip()]


def git_tracked_temp_files() -> list[str]:
    result = subprocess.run(
        ["git", "ls-files", "--", TEMP_WORKSPACE],
        cwd=ROOT,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )
    if result.returncode != 0:
        return []
    return [line.strip() for line in result.stdout.splitlines() if line.strip()]


def starts_with_any(path: str, prefixes: tuple[str, ...]) -> bool:
    return any(path.startswith(prefix) for prefix in prefixes)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--base-ref",
        help="Git base ref used to heuristically check docs/test coverage.",
    )
    parser.add_argument(
        "--strict",
        action="store_true",
        help="Treat change-coverage warnings as errors.",
    )
    args = parser.parse_args()
    errors: list[str] = []
    warnings: list[str] = []

    if not (ROOT / "build.gradle").exists() or not (ROOT / "maven-consumer-project").exists():
        errors.append(
            "This does not look like the Pickleball repository root "
            "(expected build.gradle and maven-consumer-project)."
        )

    for relative in REQUIRED_FILES:
        if not (ROOT / relative).is_file():
            errors.append(f"Missing required agent file: {relative}")

    gitignore = ROOT / ".gitignore"
    if gitignore.is_file():
        ignore_lines = {
            line.strip()
            for line in gitignore.read_text(encoding="utf-8").splitlines()
        }
        if TEMP_WORKSPACE_IGNORE not in ignore_lines:
            errors.append(
                f".gitignore must contain the temporary workspace entry: "
                f"{TEMP_WORKSPACE_IGNORE}"
            )

    tracked_temp_files = git_tracked_temp_files()
    if tracked_temp_files:
        errors.append(
            "Temporary agent workspace files are tracked by Git: "
            + ", ".join(tracked_temp_files)
        )

    for relative in SKILL_FILES:
        if not (ROOT / relative).is_file():
            errors.append(f"Missing Agent Skill copy: {relative}")

    for relative in ADAPTER_FILES:
        path = ROOT / relative
        if path.is_file() and "AGENTS.md" not in path.read_text(encoding="utf-8"):
            errors.append(f"Adapter does not reference AGENTS.md: {relative}")

    existing_skills = [ROOT / path for path in SKILL_FILES if (ROOT / path).is_file()]
    if existing_skills:
        hashes = {sha256(path) for path in existing_skills}
        if len(hashes) != 1:
            errors.append(
                "Agent Skill copies differ. Keep all "
                "pickleball-functionality-change/SKILL.md files identical."
            )

    if args.base_ref:
        changed = git_changed_files(args.base_ref)
        if changed is not None:
            behavior_changed = any(
                starts_with_any(path, BEHAVIOR_PREFIXES) or path in BEHAVIOR_FILES
                for path in changed
            )
            tests_changed = any(starts_with_any(path, TEST_PREFIXES) for path in changed)
            docs_changed = any(
                starts_with_any(path, DOC_PREFIXES) or path in DOC_FILES
                for path in changed
            )
            if behavior_changed and not tests_changed and not env_true("AGENT_CONTRACT_ALLOW_NO_TESTS"):
                warnings.append(
                    "Framework/build behavior changed but no framework or Maven "
                    "consumer test/support files changed."
                )
            if behavior_changed and not docs_changed and not env_true("AGENT_CONTRACT_ALLOW_NO_DOCS"):
                warnings.append(
                    "Framework/build behavior changed but no README/docs files changed. "
                    "Confirm the change is purely internal or update documentation."
                )

    for warning in warnings:
        print(f"WARNING: {warning}", file=sys.stderr)

    if args.strict and warnings:
        errors.extend(f"Strict mode: {warning}" for warning in warnings)

    if errors:
        for error in errors:
            print(f"ERROR: {error}", file=sys.stderr)
        return 1

    print("Agent contract files are valid.")
    if args.base_ref:
        print(f"Change coverage checked against {args.base_ref}.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
