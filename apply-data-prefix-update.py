#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parent
pending: dict[Path, str] = {}
changed: list[str] = []


def read(relative_path: str, *, required: bool = True) -> str | None:
    path = ROOT / relative_path
    if not path.exists():
        if required:
            raise RuntimeError(f"Required file was not found: {relative_path}")
        return None
    return pending.get(path, path.read_text(encoding="utf-8"))


def stage(relative_path: str, text: str) -> None:
    path = ROOT / relative_path
    original = path.read_text(encoding="utf-8")
    if text != original:
        pending[path] = text
        changed.append(relative_path)


def replace_required(
    relative_path: str,
    text: str,
    old: str,
    new: str,
    description: str,
) -> str:
    if new in text:
        return text
    if old not in text:
        raise RuntimeError(
            f"Could not locate {description} in {relative_path}. "
            "No files from this run were written."
        )
    return text.replace(old, new, 1)


# Framework implementation
path = "src/main/java/tools/dscode/common/mappings/MappingProcessor.java"
text = read(path)

nested_data_lookup = '''                if (key.startsWith("&")) {
                    key = parsedObj.restoreAndStripBookEnds(decodeBackToText(key));
                    String reference = key.substring(1);
                    replacement = reference.startsWith(DATA_REFERENCE_PREFIX)
                            ? getScenarioMarkerData(
                                    reference.substring(
                                            DATA_REFERENCE_PREFIX.length()
                                    )
                            )
                            : getReturnValue(reference);
                    break;
                }
'''
return_value_only = '''                if (key.startsWith("&")) {
                    key = parsedObj.restoreAndStripBookEnds(decodeBackToText(key));
                    String reference = key.substring(1);
                    replacement = getReturnValue(reference);
                    break;
                }
'''
text = replace_required(
    path,
    text,
    nested_data_lookup,
    return_value_only,
    "the '&' return-value branch",
)

file_prefix_branch = '''            if (key.startsWith(FILE_REFERENCE_PREFIX)) {
                return buildJsonFromPath(key.substring(FILE_REFERENCE_PREFIX.length()));
            }
'''
source_prefix_branches = file_prefix_branch + '''            if (key.startsWith(DATA_REFERENCE_PREFIX)) {
                return getScenarioMarkerData(key.substring(DATA_REFERENCE_PREFIX.length()));
            }
'''
text = replace_required(
    path,
    text,
    file_prefix_branch,
    source_prefix_branches,
    "the file-reference branch",
)
stage(path, text)

# Consumer-hosted executable check
path = "maven-consumer-project/src/test/java/com/example/pickleball/DataReferenceSteps.java"
text = read(path)
if 'resolveWholeValue("<data:" + address + ">")' not in text:
    if 'resolveWholeValue("<&data:" + address + ">")' not in text:
        raise RuntimeError(
            f"Could not locate the data-reference assertion in {path}. "
            "No files from this run were written."
        )
    text = text.replace(
        'resolveWholeValue("<&data:" + address + ">")',
        'resolveWholeValue("<data:" + address + ">")',
        1,
    )
stage(path, text)

# Canonical component-scenario documentation
path = "docs/component-scenarios.md"
text = read(path)
component_section = '''Mapping references use the lowercase `data:` source prefix. This prefix is
resolved alongside other source prefixes, such as `file:`, before ordinary map
lookup:

```gherkin
<data:payload>
<data:Customer record.payload>
<data:Data reference records.Customer record.payload>
```
A complete data reference resolves to `ScenarioStepData`; embedded use converts
the object to text in the same way as other non-string reference values.

The `&` namespace is separate and always resolves a step return value through
`getReturnValue(reference)`. `<&data:...>` therefore addresses a step return
named `data:...`; it no longer performs scenario-marker lookup.
'''
if "The `&` namespace is separate and always resolves a step return value" not in text:
    pattern = re.compile(
        r"Mapping references use.*?(?=The data snapshot retains)",
        re.DOTALL,
    )
    text, count = pattern.subn(component_section + "\n", text, count=1)
    if count != 1:
        raise RuntimeError(
            f"Could not locate the mapping-reference subsection in {path}. "
            "No files from this run were written."
        )
stage(path, text)

# General mapping documentation
path = "docs/mapping-and-templating.md"
text = read(path)
source_section = '''### Source-qualified references

Recognized lowercase source prefixes are resolved before ordinary map lookup:

```text
<file:files/customers #1.name>
<data:Customer record.payload>
```

`file:` loads and queries a classpath resource through the file parser. `data:`
retrieves scenario marker data.

Source prefixes are separate from leading reference sigils. `<&reference>`
always resolves a step return value. Only recognized source prefixes are
reserved; an arbitrary lowercase key containing `:` is not automatically
treated as a source reference.

'''
if "### Source-qualified references" not in text:
    heading = re.search(
        r"^### Scenario and run-map references(?: in reusable components)?$",
        text,
        flags=re.MULTILINE,
    )
    if not heading:
        raise RuntimeError(
            f"Could not locate the scenario-reference heading in {path}. "
            "No files from this run were written."
        )
    text = text[:heading.start()] + source_section + text[heading.start():]
stage(path, text)

# Agent navigation context
path = "docs/agent/feature-map.md"
text = read(path)
text = text.replace("<&data:marker>", "<data:marker>")
text = text.replace("<&data:scenario.marker>", "<data:scenario.marker>")
text = text.replace(
    "<&data:feature.scenario.marker>",
    "<data:feature.scenario.marker>",
)
if "<&reference>` remains exclusively a step-return lookup" not in text:
    anchor = "  - `<data:feature.scenario.marker>`\n"
    if anchor not in text:
        raise RuntimeError(
            f"Could not locate the data-reference syntax list in {path}. "
            "No files from this run were written."
        )
    text = text.replace(
        anchor,
        anchor + "  - `<&reference>` remains exclusively a step-return lookup\n",
        1,
    )
stage(path, text)

# Consumer configuration example
path = "maven-consumer-project/src/test/resources/pickleball_local.properties"
text = read(path)
text = text.replace(
    "# Optional feature path for <&data:...> scenario-marker lookups.",
    "# Optional feature path for <data:...> scenario-marker lookups.",
)
stage(path, text)

# Keep the branch's existing surgical updater aligned.
path = "apply-update.py"
text = read(path, required=False)
if text is not None:
    old_expression = '''                    replacement = reference.startsWith(DATA_REFERENCE_PREFIX)
                            ? getScenarioMarkerData(
                                    reference.substring(
                                            DATA_REFERENCE_PREFIX.length()
                                    )
                            )
                            : getReturnValue(reference);
'''
    new_expression = '''                    replacement = getReturnValue(reference);
'''
    text = text.replace(old_expression, new_expression)

    updater_data_branch = '''replace_once(
    "src/main/java/tools/dscode/common/mappings/MappingProcessor.java",
    \'\''            if (key.startsWith(FILE_REFERENCE_PREFIX)) {
                return buildJsonFromPath(key.substring(FILE_REFERENCE_PREFIX.length()));
            }
            if (key.contains("_") && key.toLowerCase().startsWith(PKB_PREFIX)) {
\'\'',
    \'\''            if (key.startsWith(FILE_REFERENCE_PREFIX)) {
                return buildJsonFromPath(key.substring(FILE_REFERENCE_PREFIX.length()));
            }
            if (key.startsWith(DATA_REFERENCE_PREFIX)) {
                return getScenarioMarkerData(key.substring(DATA_REFERENCE_PREFIX.length()));
            }
            if (key.contains("_") && key.toLowerCase().startsWith(PKB_PREFIX)) {
\'\'',
)
'''
    if "return getScenarioMarkerData(key.substring(DATA_REFERENCE_PREFIX.length()))" not in text:
        service_anchor = '''replace_once(
    "src/main/java/tools/dscode/coredefinitions/ServiceCallSteps.java",
'''
        if service_anchor in text:
            text = text.replace(
                service_anchor,
                updater_data_branch + service_anchor,
                1,
            )

    text = text.replace(
        "# Optional feature path for <&data:...> scenario-marker lookups.",
        "# Optional feature path for <data:...> scenario-marker lookups.",
    )
    stage(path, text)

# Keep branch-local application notes accurate when present.
path = "APPLY_UPDATE.md"
text = read(path, required=False)
if text is not None and "The `<&...>` namespace remains exclusively" not in text:
    heading = "# Apply the 2.1.2 data-reference update\n"
    if heading in text:
        text = text.replace(
            heading,
            heading
            + "\nScenario marker mappings use `<data:...>`. "
              "The `<&...>` namespace remains exclusively\n"
              "a step-return lookup.\n",
            1,
        )
    stage(path, text)

path = "README.md"
text = read(path, required=False)
if text is not None and text.startswith("# 2.1.2 surgical fixes"):
    text = text.replace(
        "Copy the three repository-relative files over the matching files in the\n"
        "`2.1.2` checkout.",
        "Copy the repository-relative files over the matching files in the `2.1.2`\n"
        "checkout.",
    )
    if "`MappingProcessor` resolves `file:` and `data:`" not in text:
        anchor = (
            "- `ScenarioDataSteps` expects unresolved Examples placeholders "
            "from unresolved\n  getters.\n"
        )
        if anchor in text:
            text = text.replace(
                anchor,
                anchor
                + "- `MappingProcessor` resolves `file:` and `data:` source prefixes "
                  "independently\n  from the `&` step-return prefix.\n"
                + "- Scenario-data consumer checks and documentation use `<data:...>`.\n",
                1,
            )
    stage(path, text)

# Commit only after every required transformation has validated.
for file_path, new_text in pending.items():
    file_path.write_text(new_text, encoding="utf-8")

if changed:
    print("Updated:")
    for relative_path in dict.fromkeys(changed):
        print(f"  {relative_path}")
else:
    print("No changes required; the data-prefix update is already applied.")

print("Run: python scripts/refresh_agent_index.py")
