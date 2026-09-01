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
    "pickleball-workbench/AGENTS.md",
    "gradle/consumer-guidance.gradle",
    "scripts/refresh_agent_index.py",
    "scripts/sync_consumer_guidance.py",
    "scripts/verify_agent_contract.py",
    "scripts/agent_validate.sh",
    "scripts/agent_validate.ps1",
    "maven-consumer-project/AGENTS.md",
    "maven-consumer-project/.github/copilot-instructions.md",
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
    "pickleball-control-protocol/src/main/",
    "pickleball-control-api/src/main/",
    "pickleball-workbench/src/main/",
)
BEHAVIOR_FILES = {
    "build.gradle",
    "settings.gradle",
    "gradle/consumer-guidance.gradle",
    "pickleball-control-protocol/build.gradle",
    "pickleball-control-api/build.gradle",
    "pickleball-workbench/build.gradle",
    "gradle.properties",
}
TEST_PREFIXES = (
    "src/test/",
    "pickleball-control-protocol/src/test/",
    "pickleball-control-api/src/test/",
    "pickleball-workbench/src/test/",
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
    "pickleball-workbench/AGENTS.md",
    "maven-consumer-project/README.md",
    "maven-consumer-project/AGENTS.md",
    "maven-consumer-project/.github/copilot-instructions.md",
}

WORKBENCH_CONTRACT_FILES = (
    "AGENTS.md",
    "pickleball-workbench/AGENTS.md",
    "docs/agent/feature-map.md",
)


def validate_workbench_controller_contract(errors: list[str]) -> None:
    required_fragments = (
        "pickleball-control-protocol",
        "Pickleball may contain Workbench",
        "Workbench must not contain Pickleball",
        "@control-bridge",
        "pkb_parallel=80",
    )
    forbidden_fragments = (
        "pickleball-workbench -> pickleball",
        "Workbench POM contract is exactly `tools.dscode:pickleball`",
        "uses the public `tools.dscode.control.bridge.*`",
    )

    for relative in WORKBENCH_CONTRACT_FILES:
        path = ROOT / relative
        if not path.is_file():
            continue
        text = path.read_text(encoding="utf-8")
        for required in required_fragments:
            if required not in text:
                errors.append(
                    f"Workbench controller guidance must retain {required!r}: {relative}"
                )
        for forbidden in forbidden_fragments:
            if forbidden in text:
                errors.append(
                    f"Workbench controller guidance retains the obsolete dependency rule "
                    f"{forbidden!r}: {relative}"
                )


def validate_workbench_source_boundary(errors: list[str]) -> None:
    build = ROOT / "pickleball-workbench" / "build.gradle"
    if build.is_file():
        text = build.read_text(encoding="utf-8")
        if "implementation project(':pickleball-control-protocol')" not in text:
            errors.append(
                "Workbench must depend on exactly the neutral protocol project: "
                "pickleball-workbench/build.gradle"
            )
        for forbidden in (
            "pickleballPublishedElements",
            "project(path: ':'",
            "implementation project(':')",
            "tools.dscode:pickleball",
        ):
            if forbidden in text:
                errors.append(
                    f"Workbench build restores a forbidden core dependency ({forbidden}): "
                    "pickleball-workbench/build.gradle"
                )

    workbench_sources = ROOT / "pickleball-workbench" / "src" / "main" / "java"
    if workbench_sources.is_dir():
        for source in workbench_sources.rglob("*.java"):
            for line in source.read_text(encoding="utf-8").splitlines():
                if not line.startswith((
                    "import tools.dscode.",
                    "import static tools.dscode.",
                )):
                    continue
                imported = line.removeprefix("import ").removeprefix("static ")
                if imported.startswith("tools.dscode.workbench.") or imported.startswith(
                    "tools.dscode.control.protocol."
                ):
                    continue
                errors.append(
                    "Workbench source imports a Pickleball execution package: "
                    f"{source.relative_to(ROOT)} -> {line.strip()}"
                )

    protocol_sources = ROOT / "pickleball-control-protocol" / "src" / "main" / "java"
    if protocol_sources.is_dir():
        for source in protocol_sources.rglob("*.java"):
            for line in source.read_text(encoding="utf-8").splitlines():
                if line.startswith("import ") and not line.startswith("import java."):
                    errors.append(
                        "Neutral protocol source has a non-JDK import: "
                        f"{source.relative_to(ROOT)} -> {line.strip()}"
                    )


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


CONSUMER_BRIDGE_FILES = (
    "maven-consumer-project/AGENTS.md",
    "maven-consumer-project/.github/copilot-instructions.md",
)


def validate_consumer_bridge(errors: list[str]) -> None:
    texts: list[str] = []
    for relative in CONSUMER_BRIDGE_FILES:
        path = ROOT / relative
        if not path.is_file():
            continue

        text = path.read_text(encoding="utf-8").strip()
        texts.append(text)
        nonblank_lines = [line for line in text.splitlines() if line.strip()]
        if not (3 <= len(nonblank_lines) <= 8):
            errors.append(
                "Consumer guidance bridge must keep the Workbench export-guidance one-liner plus a short "
                "Discover/Isolate/Confirm pointer (not the full guide): "
                + relative
            )

        first_line = nonblank_lines[0] if nonblank_lines else ""
        for required in (
            "PickleballWorkbenchLauncher",
            "export-guidance",
            ".pickleball/AGENT-GUIDE.md",
            "classpathScope=test",
        ):
            if required not in first_line:
                errors.append(
                    f"Consumer guidance bridge one-liner must reference {required}: {relative}"
                )

        lowered = text.lower()
        if "do not start the gui" not in lowered:
            errors.append(
                "Consumer guidance bridge must say not to start the GUI: " + relative
            )
        for required in (
            "hint",
            "discover",
            "confirm",
            "isolate",
            "execute-step",
        ):
            if required not in lowered:
                errors.append(
                    f"Consumer guidance bridge must mention {required}: " + relative
                )
        if "ide mcp" in lowered or "register mcp" in lowered:
            errors.append(
                "Consumer guidance bridge must not mention IDE MCP: " + relative
            )
        if "do not isolate" in lowered or "do not maven-exec isolate" in lowered:
            errors.append(
                "Consumer guidance bridge must not tell agents not to isolate: " + relative
            )
        if "`hint`, `discover`, `isolate`, and `confirm`" in text:
            errors.append(
                "Consumer guidance bridge must not list isolate as a required first-path verb: "
                + relative
            )

        if "do not skip workbench" in lowered:
            errors.append(
                "Consumer guidance bridge must not say 'Do not skip Workbench': " + relative
            )
        if "parallel is ok when the project supports it" in lowered:
            errors.append(
                "Consumer guidance bridge must not say parallel is optional: " + relative
            )
        if "stdio" in lowered:
            errors.append(
                "Consumer guidance bridge must not mention IDE-owned stdio: " + relative
            )
        if "must" in lowered and "chrome_headless" in lowered:
            errors.append(
                "Consumer guidance bridge must not MUST-use CHROME_HEADLESS for all projects: "
                + relative
            )

        for forbidden in (
            "DiagnosticCli",
            "GUIDANCE-MANIFEST.json",
            ".git/info/exclude",
            "pkb_changed_variables",
            "runProfileFingerprint",
            "Diagnostic investigation protocol",
            "attach.json",
            "ui .",
            "@agent-pointer-eval",
            "IntelliJ",
            "stdio",
        ):
            if forbidden in text:
                errors.append(
                    f"Consumer guidance bridge contains dependency-owned guidance ({forbidden}); "
                    "keep only the Workbench bootstrap command and a short Discover/Isolate/Confirm pointer: "
                    + relative
                )

    if len(texts) == len(CONSUMER_BRIDGE_FILES) and len(set(texts)) != 1:
        errors.append(
            "Consumer AGENTS.md and .github/copilot-instructions.md bridges must be identical."
        )


def validate_consumer_readme(errors: list[str]) -> None:
    readme = ROOT / "maven-consumer-project" / "README.md"
    if not readme.is_file():
        return

    text = readme.read_text(encoding="utf-8")
    if "AGENTS.md" not in text:
        errors.append(
            "Consumer README must point to AGENTS.md for guidance export: "
            "maven-consumer-project/README.md"
        )
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
        "Generated Maven consumer reference",
        "maven-consumer-project/",
        "@agent-pointer-eval",
        "Maintainer-only: pointer-eval harness",
        "-Dexec.mainClass=tools.dscode.launcher.PickleballWorkbenchLauncher",
    ):
        if required not in text:
            errors.append(
                f"Dependency-owned consumer guide must retain lifecycle guidance ({required}): "
                "docs/consumer-agent-guide.md"
            )

    chooser_start = text.find("## Tool chooser")
    live_start = text.find("### Live isolation loop")
    first_read = text.find("## First-read")
    maintainer = text.find("## Maintainer-only: pointer-eval harness")
    if chooser_start < 0 or live_start < 0 or first_read < 0 or maintainer < 0:
        errors.append(
            "docs/consumer-agent-guide.md must keep Tool chooser, Live isolation loop, "
            "First-read, and Maintainer-only pointer-eval sections."
        )
        return

    chooser = text[chooser_start:live_start]
    live = text[live_start:first_read]
    if "-Dexec.mainClass=tools.dscode.launcher.PickleballWorkbenchLauncher" not in chooser:
        errors.append(
            "AGENT-GUIDE tool chooser must show the same Maven exec wrapper as the pointer "
            "(-Dexec.mainClass=tools.dscode.launcher.PickleballWorkbenchLauncher)."
        )
    if "-Dexec.args=discover" not in chooser:
        errors.append(
            "AGENT-GUIDE tool chooser must show -Dexec.args=discover, not a bare launcher verb."
        )
    if "PickleballWorkbenchLauncher discover" in chooser:
        errors.append(
            "AGENT-GUIDE tool chooser must not show bare PickleballWorkbenchLauncher discover; "
            "use the Maven exec wrapper and change exec.args."
        )
    if "-Dexec.args=isolate" not in chooser:
        errors.append(
            "AGENT-GUIDE tool chooser must show -Dexec.args=isolate for live debug."
        )
    if "execute-step" not in chooser:
        errors.append(
            "AGENT-GUIDE tool chooser must mention execute-step for live debug."
        )
    if "do not maven-exec isolate" in chooser.lower() or "do not isolate" in chooser.lower():
        errors.append(
            "AGENT-GUIDE tool chooser must not tell agents not to isolate."
        )
    if "register ide mcp" in chooser.lower() or "ide-owned stdio" in chooser.lower():
        errors.append(
            "AGENT-GUIDE tool chooser must not tell agents to register MCP."
        )
    if "execute-step" not in live or "isolate" not in live:
        errors.append(
            "AGENT-GUIDE live isolation loop must use isolate then execute-step."
        )
    if maintainer < text.find("## When the core Pickleball repository is also present"):
        errors.append(
            "@agent-pointer-eval guidance must stay maintainer-only at the bottom of "
            "docs/consumer-agent-guide.md."
        )

    workbench = ROOT / "docs" / "pickleball-workbench.md"
    if workbench.is_file():
        workbench_text = workbench.read_text(encoding="utf-8")
        if "That is the consumer-agent path" in workbench_text:
            errors.append(
                "docs/pickleball-workbench.md must not call mcp the consumer-agent path."
            )
        groovy = workbench_text
        if "args 'mcp'" in groovy and "args 'hint'" not in groovy:
            errors.append(
                "docs/pickleball-workbench.md Gradle pickleballWorkbench example must not "
                "default to mcp."
            )


def validate_consumer_reference_build_hook(errors: list[str]) -> None:
    settings = ROOT / "settings.gradle"
    if not settings.is_file():
        return
    if "gradle/consumer-guidance.gradle" not in settings.read_text(encoding="utf-8"):
        errors.append(
            "settings.gradle must apply gradle/consumer-guidance.gradle so indexed Maven "
            "consumer reference files are packaged into the Pickleball dependency."
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


AGENT_POINTER_EVAL_FEATURE = (
    "maven-consumer-project/src/test/resources/features/agent-pointer-eval.feature"
)
AGENT_POINTER_EVAL_TAG = "@agent-pointer-eval"
MAVEN_SUITE_PROFILE_TAGS = {
    "@all",
    "@regression",
    "@smoke",
    "@browser",
    "@data",
    "@navigation",
    "@forms",
    "@catalog",
    "@mapping",
    "@resources",
    "@workflow",
    "@block-conditionals",
    "@nested-steps",
    "@keyboard",
    "@dialogs",
    "@components",
}


def parse_gherkin_tagged_elements(text: str) -> list[tuple[str, list[str]]]:
    pending: list[str] = []
    elements: list[tuple[str, list[str]]] = []
    for raw in text.splitlines():
        stripped = raw.strip()
        if not stripped or stripped.startswith("#"):
            continue
        if stripped.startswith("@"):
            pending.extend(token for token in stripped.split() if token.startswith("@"))
            continue
        if (
            stripped.startswith("Feature:")
            or stripped.startswith("Scenario:")
            or stripped.startswith("Scenario Outline:")
        ):
            elements.append((stripped, pending))
            pending = []
    return elements


def validate_agent_pointer_eval_harness(errors: list[str]) -> None:
    relative = AGENT_POINTER_EVAL_FEATURE
    path = ROOT / relative
    if not path.is_file():
        errors.append("Missing opt-in consumer pointer-eval harness: " + relative)
        return

    elements = parse_gherkin_tagged_elements(path.read_text(encoding="utf-8"))
    if not elements or not elements[0][0].startswith("Feature:"):
        errors.append("Pointer-eval harness must declare a Feature: " + relative)
        return

    scenarios = [
        element
        for element in elements
        if element[0].startswith("Scenario:") or element[0].startswith("Scenario Outline:")
    ]
    if len(scenarios) < 2:
        errors.append(
            "Pointer-eval harness must contain more than one Scenario so isolate can "
            "target distinct names: " + relative
        )

    for name, tags in elements:
        missing = AGENT_POINTER_EVAL_TAG not in tags
        forbidden = sorted(tag for tag in tags if tag in MAVEN_SUITE_PROFILE_TAGS)
        extras = [
            tag
            for tag in tags
            if tag != AGENT_POINTER_EVAL_TAG and not tag.startswith(AGENT_POINTER_EVAL_TAG + "-")
        ]
        if missing:
            errors.append(
                f"Pointer-eval harness element must carry {AGENT_POINTER_EVAL_TAG}: "
                f"{relative} -> {name}"
            )
        if forbidden:
            errors.append(
                "Pointer-eval harness must not carry Maven suite-profile tags "
                f"{forbidden}: {relative} -> {name}"
            )
        if extras:
            errors.append(
                "Pointer-eval harness may only add unique @agent-pointer-eval-* tags, "
                f"not {extras}: {relative} -> {name}"
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
    validate_agent_pointer_eval_harness(errors)
    validate_consumer_reference_build_hook(errors)
    validate_consumer_tracked_artifacts(errors)
    validate_consumer_ignore(errors)
    validate_packaged_guidance(errors)
    validate_workbench_controller_contract(errors)
    validate_workbench_source_boundary(errors)

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
