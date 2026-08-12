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
    "docs/consumer-agent-guide.md",
    "docs/consumer-project.md",
    "docs/diagnostic-lineage-metadata.md",
    "docs/agent/README.md",
    "docs/agent/feature-map.md",
    "docs/agent/change-checklist.md",
    "docs/agent/prompt-examples.md",
    "docs/agent/repository-index.md",
    "scripts/refresh_agent_index.py",
    "scripts/sync_consumer_guidance.py",
    "scripts/verify_agent_contract.py",
    "scripts/agent_validate.sh",
    "scripts/agent_validate.ps1",
    "maven-consumer-project/AGENTS.md",
    "maven-consumer-project/README.md",
    "maven-consumer-project/.gitignore",
    "maven-consumer-project/mvnw",
    "maven-consumer-project/mvnw.cmd",
    "maven-consumer-project/.mvn/wrapper/maven-wrapper.properties",
)
FORBIDDEN_CONSUMER_DOCS = (
    "maven-consumer-project/TAGGING.md",
    "maven-consumer-project/CHANGESET.md",
)
FORBIDDEN_TRACKED_CONSUMER_PREFIXES = (
    "maven-consumer-project/.idea/",
    "maven-consumer-project/.run/",
    "maven-consumer-project/.pickleball/",
    "maven-consumer-project/.agents/",
    "maven-consumer-project/.aiassistant/",
    "maven-consumer-project/.amazonq/",
    "maven-consumer-project/.claude/",
    "maven-consumer-project/.continue/",
    "maven-consumer-project/.cursor/",
    "maven-consumer-project/.github/agents/",
    "maven-consumer-project/.github/instructions/",
    "maven-consumer-project/.github/skills/",
    "maven-consumer-project/.clinerules/",
    "maven-consumer-project/.junie/",
    "maven-consumer-project/.windsurf/",
)
FORBIDDEN_TRACKED_CONSUMER_FILES = {
    "maven-consumer-project/CLAUDE.md",
    "maven-consumer-project/GEMINI.md",
    "maven-consumer-project/REVIEW.md",
    "maven-consumer-project/.clinerules",
    "maven-consumer-project/.github/copilot-instructions.md",
}
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
    "maven-consumer-project/AGENTS.md",
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


def git_tracked_files(path: str) -> list[str]:
    result = subprocess.run(
        ["git", "ls-files", "--", path],
        cwd=ROOT,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )
    if result.returncode != 0:
        return []
    return [line.strip().replace("\\", "/") for line in result.stdout.splitlines() if line.strip()]


def git_tracked_temp_files() -> list[str]:
    return git_tracked_files(TEMP_WORKSPACE)


def starts_with_any(path: str, prefixes: tuple[str, ...]) -> bool:
    return any(path.startswith(prefix) for prefix in prefixes)


def validate_consumer_bridge(errors: list[str]) -> None:
    bridge = ROOT / "maven-consumer-project" / "AGENTS.md"
    if not bridge.is_file():
        return

    text = bridge.read_text(encoding="utf-8").strip()
    nonblank_lines = [line for line in text.splitlines() if line.strip()]
    if len(nonblank_lines) != 1:
        errors.append(
            "Consumer AGENTS bridge must stay a single nonblank bootstrap line: "
            "maven-consumer-project/AGENTS.md"
        )

    for required in (
        "DiagnosticCli",
        "export-guidance",
        ".pickleball/AGENT-GUIDE.md",
    ):
        if required not in text:
            errors.append(
                f"Consumer AGENTS bridge must reference {required}: "
                "maven-consumer-project/AGENTS.md"
            )

    for forbidden in (
        "GUIDANCE-MANIFEST.json",
        ".git/info/exclude",
        "pkb_changed_variables",
        "runProfileFingerprint",
        "Diagnostic investigation protocol",
    ):
        if forbidden in text:
            errors.append(
                f"Consumer AGENTS bridge contains dependency-owned guidance ({forbidden}); "
                "keep only the bootstrap command and generated-guide pointer."
            )


def validate_consumer_readme(errors: list[str]) -> None:
    readme = ROOT / "maven-consumer-project" / "README.md"
    if not readme.is_file():
        return

    text = readme.read_text(encoding="utf-8")
    for forbidden in (
        "export-guidance",
        ".pickleball/",
        "GUIDANCE-MANIFEST.json",
        "AGENT-GUIDE.md",
        ".git/info/exclude",
    ):
        if forbidden in text:
            errors.append(
                f"Consumer README must not duplicate dependency guidance lifecycle ({forbidden}): "
                "maven-consumer-project/README.md"
            )


def validate_dependency_owned_guidance(errors: list[str]) -> None:
    guide = ROOT / "docs" / "consumer-agent-guide.md"
    if not guide.is_file():
        return

    text = guide.read_text(encoding="utf-8")
    for required in (
        "Generated guidance lifecycle",
        "GUIDANCE-MANIFEST.json",
        ".git/info/exclude",
        "potentially stale",
        "managed guidance files",
        "version-matched",
    ):
        if required not in text:
            errors.append(
                f"Dependency-owned consumer guide must retain lifecycle guidance ({required}): "
                "docs/consumer-agent-guide.md"
            )


def validate_consumer_tracked_artifacts(errors: list[str]) -> None:
    for relative in git_tracked_files("maven-consumer-project"):
        if not (ROOT / relative).exists():
            continue
        if relative in FORBIDDEN_TRACKED_CONSUMER_FILES or starts_with_any(
            relative, FORBIDDEN_TRACKED_CONSUMER_PREFIXES
        ):
            errors.append(
                "Consumer project must not track local AI/IDE/generated guidance artifacts: "
                + relative
            )


def validate_consumer_ignore(errors: list[str]) -> None:
    path = ROOT / "maven-consumer-project" / ".gitignore"
    if not path.is_file():
        return

    normalized = {
        line.strip().replace("\\", "/").strip("/")
        for line in path.read_text(encoding="utf-8").splitlines()
        if line.strip() and not line.lstrip().startswith("#")
    }
    if ".pickleball" not in normalized:
        errors.append(
            "Consumer .gitignore must ignore /.pickleball/: "
            "maven-consumer-project/.gitignore"
        )


def validate_packaged_guidance(errors: list[str]) -> None:
    script = ROOT / "scripts" / "sync_consumer_guidance.py"
    if not script.is_file():
        return

    result = subprocess.run(
        [sys.executable, str(script), "--check"],
        cwd=ROOT,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )
    if result.returncode != 0:
        details = (result.stderr or result.stdout).strip()
        errors.append(
            "Packaged consumer guidance is stale. "
            "Run: python scripts/sync_consumer_guidance.py"
            + (f" ({details})" if details else "")
        )


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

    for relative in FORBIDDEN_CONSUMER_DOCS:
        if (ROOT / relative).exists():
            errors.append(
                f"Consumer documentation must be centralized in core; remove: {relative}"
            )

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

    validate_consumer_bridge(errors)
    validate_consumer_readme(errors)
    validate_dependency_owned_guidance(errors)
    validate_consumer_tracked_artifacts(errors)
    validate_consumer_ignore(errors)
    validate_packaged_guidance(errors)

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
