#!/usr/bin/env python3
"""Apply the Pickleball date/time documentation and consumer-example update."""

from __future__ import annotations

import argparse
import re
import shutil
from pathlib import Path

BUNDLE_ROOT = Path(__file__).resolve().parent
SKIP_FILES = {
    "CHANGESET.md",
    "README.md",
    "apply_datetime_update.py",
}

FEATURE_ROOT = "../maven-consumer-project/src/test/resources/features"

DOC_FEATURE_LINKS: dict[str, list[tuple[str, str]]] = {
    "dynamic-steps.md": [
        ("dynamic-steps.feature", f"{FEATURE_ROOT}/dynamic-steps.feature"),
        ("forms-dynamic-steps.feature", f"{FEATURE_ROOT}/forms-dynamic-steps.feature"),
        ("dialogs.feature", f"{FEATURE_ROOT}/dialogs.feature"),
        ("navigation.feature", f"{FEATURE_ROOT}/navigation.feature"),
    ],
    "mapping-and-templating.md": [
        ("mapping-and-resources.feature", f"{FEATURE_ROOT}/mapping-and-resources.feature"),
    ],
    "config-files-and-resource-mapping.md": [
        ("mapping-and-resources.feature", f"{FEATURE_ROOT}/mapping-and-resources.feature"),
        ("date-time-utilities.feature", f"{FEATURE_ROOT}/date-time-utilities.feature"),
    ],
    "nested-steps.md": [
        ("nested-and-block-conditionals.feature", f"{FEATURE_ROOT}/nested-and-block-conditionals.feature"),
    ],
    "block-conditionals.md": [
        ("nested-and-block-conditionals.feature", f"{FEATURE_ROOT}/nested-and-block-conditionals.feature"),
    ],
    "component-scenarios.md": [
        ("component-scenarios.feature", f"{FEATURE_ROOT}/component-scenarios.feature"),
    ],
    "key-parser-dsl.md": [
        ("keyboard.feature", f"{FEATURE_ROOT}/keyboard.feature"),
    ],
    "custom-element-definitions.md": [
        ("catalog-context.feature", f"{FEATURE_ROOT}/catalog-context.feature"),
    ],
    "configuration.md": [
        ("navigation.feature", f"{FEATURE_ROOT}/navigation.feature"),
    ],
    "getting-started.md": [
        ("dynamic-steps.feature", f"{FEATURE_ROOT}/dynamic-steps.feature"),
    ],
}

MARKER = "<!-- consumer-feature-examples -->"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("repo", type=Path, help="Path to the pickleball repository root")
    parser.add_argument(
        "--calendar",
        help="Existing CALENDARS.yaml key to replace YOUR_CALENDAR in the feature and documentation",
    )
    return parser.parse_args()


def backup_once(path: Path) -> None:
    if not path.exists():
        return
    backup = path.with_name(path.name + ".datetime-update-backup")
    if not backup.exists():
        shutil.copy2(path, backup)


def write_text(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    backup_once(path)
    path.write_text(text, encoding="utf-8", newline="\n")


def copy_overlay(repo: Path, calendar: str | None) -> int:
    copied = 0
    for source in BUNDLE_ROOT.rglob("*"):
        if not source.is_file():
            continue
        relative = source.relative_to(BUNDLE_ROOT)
        if relative.as_posix() in SKIP_FILES:
            continue
        if "__pycache__" in relative.parts or relative.suffix == ".pyc":
            continue
        if relative.name.endswith((".zip", ".sha256", ".patch")):
            continue

        target = repo / relative
        text = source.read_text(encoding="utf-8")
        if calendar:
            text = text.replace("YOUR_CALENDAR", calendar)
        write_text(target, text)
        copied += 1
    return copied


def remove_top_level_datetime_section(repo: Path) -> bool:
    """Remove the date/time section created by the first bundle, if present."""
    readme = repo / "README.md"
    if not readme.exists():
        return False
    text = readme.read_text(encoding="utf-8")
    updated = re.sub(
        r"\n## Date and time expressions\n.*?(?=\n## |\Z)",
        "\n",
        text,
        flags=re.DOTALL,
    ).rstrip() + "\n"
    if updated == text:
        return False
    write_text(readme, updated)
    return True


def executable_section(links: list[tuple[str, str]]) -> str:
    if len(links) == 1:
        label, path = links[0]
        body = f"See [{label}]({path}) in the Maven consumer project."
    else:
        joined = ", ".join(f"[{label}]({path})" for label, path in links[:-1])
        last_label, last_path = links[-1]
        body = f"See {joined}, and [{last_label}]({last_path}) in the Maven consumer project."
    return f"{MARKER}\n## Executable examples\n\n{body}\n"


def insert_before_footer(text: str, section: str) -> str:
    footer = re.search(r"\n---\n(?=\[)", text)
    if footer:
        return text[: footer.start()] .rstrip() + "\n\n" + section.rstrip() + text[footer.start():]
    return text.rstrip() + "\n\n" + section


def update_doc_feature_links(repo: Path) -> int:
    docs = repo / "docs"
    changed = 0
    for filename, links in DOC_FEATURE_LINKS.items():
        path = docs / filename
        if not path.exists():
            continue
        text = path.read_text(encoding="utf-8")
        if MARKER in text or all(target in text for _, target in links):
            continue
        updated = insert_before_footer(text, executable_section(links))
        write_text(path, updated.rstrip() + "\n")
        changed += 1

    # The status page describes the whole feature set rather than one feature.
    status = docs / "feature-status-notes.md"
    if status.exists():
        text = status.read_text(encoding="utf-8")
        suite_link = "../maven-consumer-project/src/test/resources/features/"
        if MARKER not in text and suite_link not in text:
            section = (
                f"{MARKER}\n## Executable examples\n\n"
                f"Compare the status notes with the Maven consumer project's "
                f"[feature suite]({suite_link}).\n"
            )
            updated = insert_before_footer(text, section)
            write_text(status, updated.rstrip() + "\n")
            changed += 1
    return changed


def update_navigation_chain(repo: Path) -> int:
    replacements = {
        repo / "docs" / "mapping-and-templating.md": (
            "[Next: Shared configuration files](config-files-and-resource-mapping.md)",
            "[Next: Date and Time Utilities](date-time-utilities.md)",
        ),
        repo / "docs" / "config-files-and-resource-mapping.md": (
            "[Previous: Mapping and Templating](mapping-and-templating.md)",
            "[Previous: Date and Time Utilities](date-time-utilities.md)",
        ),
    }
    changed = 0
    for path, (old, new) in replacements.items():
        if not path.exists():
            continue
        text = path.read_text(encoding="utf-8")
        if new in text or old not in text:
            continue
        write_text(path, text.replace(old, new, 1))
        changed += 1
    return changed


def main() -> int:
    args = parse_args()
    repo = args.repo.expanduser().resolve()
    if not (repo / "maven-consumer-project").is_dir() or not (repo / "docs").is_dir():
        raise SystemExit(f"Not a Pickleball checkout: {repo}")

    copied = copy_overlay(repo, args.calendar)
    cleaned_root = remove_top_level_datetime_section(repo)
    linked = update_doc_feature_links(repo)
    navigation = update_navigation_chain(repo)

    print(f"Copied or updated {copied} bundled files")
    print(f"Added consumer-feature links to {linked} existing documentation pages")
    print(f"Updated {navigation} documentation navigation links")
    if cleaned_root:
        print("Removed the date/time section from the top-level README")
    if not args.calendar:
        print(
            "Reminder: replace YOUR_CALENDAR in date-time-utilities.feature before "
            "running @business-calendar scenarios."
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
